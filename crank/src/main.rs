//! `hd-crank`: the permissionless Heads Down dig crank.
//!
//! ```text
//! hd-crank run    [--config crank.toml] [--keypair ~/.config/hd-crank/id.json]
//! hd-crank check  [--config crank.toml]      # read-only: chain view, pins, gate, dry-run plan, Stack tables
//! hd-crank config [--env]                    # the effective, non-secret config; or every HD_CRANK_* variable
//! hd-crank decode <signature> [--json]       # heads_down events of a transaction (captions)
//! hd-crank replay --signature <landed dig> [--rig <address>]... [--dry-run]
//!                                             # resubmit a landed dig's heartbeat: RigSkipped(StaleHeartbeat)
//! ```

use std::path::PathBuf;
use std::process::ExitCode;

use clap::{Parser, Subcommand};
use hd_crank::app;
use hd_crank::config::{self, Config};
use hd_crank::demo::ReplayOpts;
use hd_crank::redact;
use solana_address::Address;

#[derive(Parser)]
#[command(name = "hd-crank", version, about = "Permissionless dig crank for Heads Down (liveness only; cannot move funds)")]
struct Cli {
    /// TOML config file (optional; every field has a default and an HD_CRANK_* variable).
    #[arg(long, short, env = "HD_CRANK_CONFIG", global = true)]
    config: Option<PathBuf>,
    /// Fee-payer keypair (Solana CLI JSON). Never commit it.
    #[arg(long, short, global = true)]
    keypair: Option<PathBuf>,
    #[command(subcommand)]
    cmd: Cmd,
}

#[derive(Subcommand)]
enum Cmd {
    /// Run the watcher, the intake, the dig loop, BREAK / FREEZE landing, record_heartbeats,
    /// the permissionless end_shift sweep, the Stack check-ins and settles, and the cleanups.
    Run,
    /// Read-only: print the chain view, the ORE pins, the gate, a dry-run plan and the open
    /// Stack tables, bonds and gifts.
    Check,
    /// Print the effective configuration without anything secret (URLs keep only scheme and
    /// host), as JSON; `--env` lists every setting's environment variable and default instead.
    Config {
        /// One line per setting: `HD_CRANK_<SECTION>_<FIELD>  path  default`.
        #[arg(long)]
        env: bool,
    },
    /// Print the heads_down events of a transaction (RigDug / RigSkipped with error names, ...).
    Decode {
        /// Transaction signature (base58).
        signature: String,
        /// One JSON object instead of caption lines.
        #[arg(long)]
        json: bool,
    },
    /// Resubmit a landed dig's signed heartbeat(s) with a fresh blockhash: the program answers
    /// RigSkipped(StaleHeartbeat). Refuses anything that is not stale; never replays lease
    /// reuses or ORE checkpoints.
    Replay {
        /// Signature of the landed dig transaction.
        #[arg(long)]
        signature: String,
        /// Only replay this rig's heartbeat (repeatable; default: every fresh heartbeat, max 8).
        #[arg(long = "rig")]
        rigs: Vec<Address>,
        /// Simulate and print the expected events, do not send.
        #[arg(long)]
        dry_run: bool,
        /// Priority fee, micro-lamports per CU.
        #[arg(long, default_value_t = 1_000)]
        cu_price: u64,
    },
}

fn load_config(cli: &Cli) -> anyhow::Result<Config> {
    let text = match &cli.config {
        Some(p) => std::fs::read_to_string(p).map_err(|e| anyhow::anyhow!("{}: {e}", p.display()))?,
        None => String::new(),
    };
    let mut cfg = Config::from_toml(&text)?.finalize(&|k| std::env::var(k).ok())?;
    if let Some(k) = &cli.keypair {
        cfg.keypair_path = Some(k.clone());
    }
    Ok(cfg)
}

/// Logs go to stderr through the redacting writer: whatever an event formats, the API keys in
/// the configured URLs come out as `<redacted>`.
fn init_tracing(json: bool) {
    let filter = tracing_subscriber::EnvFilter::try_from_default_env()
        .unwrap_or_else(|_| tracing_subscriber::EnvFilter::new("info,hyper=warn,reqwest=warn"));
    let b = tracing_subscriber::fmt().with_env_filter(filter).with_target(false).with_writer(redact::RedactingStderr);
    if json {
        b.json().init();
    } else {
        b.init();
    }
}

/// SIGTERM (what a container platform sends) or SIGINT (Ctrl-C, and what the Railway
/// entrypoint forwards): the name of the first one that arrives.
async fn shutdown_signal() -> &'static str {
    #[cfg(unix)]
    {
        use tokio::signal::unix::{signal, SignalKind};
        match (signal(SignalKind::terminate()), signal(SignalKind::interrupt())) {
            (Ok(mut term), Ok(mut int)) => tokio::select! {
                _ = term.recv() => "SIGTERM",
                _ = int.recv() => "SIGINT",
            },
            _ => {
                let _ = tokio::signal::ctrl_c().await;
                "SIGINT"
            }
        }
    }
    #[cfg(not(unix))]
    {
        let _ = tokio::signal::ctrl_c().await;
        "SIGINT"
    }
}

fn print_env_settings() {
    println!("# Every setting, its environment variable and its default. Precedence: defaults, the TOML file, the environment.");
    for (name, path, default) in config::env_settings() {
        println!("{name:<48} {path:<44} {default}");
    }
    for (alias, canon) in config::ENV_ALIASES {
        println!("{alias:<48} alias of {canon}");
    }
    println!("{:<48} the TOML file (read by the CLI; not a setting)", "HD_CRANK_CONFIG");
    println!("{:<48} fills {{HELIUS_API_KEY}} in any URL; never logged", "HELIUS_API_KEY");
}

#[tokio::main]
async fn main() -> ExitCode {
    // One process-wide TLS crypto provider (reqwest and tokio-tungstenite both use rustls).
    let _ = rustls::crypto::ring::default_provider().install_default();
    let cli = Cli::parse();
    if let Cmd::Config { env: true } = cli.cmd {
        print_env_settings();
        return ExitCode::SUCCESS;
    }
    let cfg = match load_config(&cli) {
        Ok(c) => c,
        Err(e) => {
            // Config errors name a setting or a variable, never a value.
            eprintln!("hd-crank: {e}");
            return ExitCode::from(2);
        }
    };
    // Before the first log line: the keys inside the URLs are replaced wherever they appear.
    redact::register_secrets(cfg.secrets());
    init_tracing(cfg.log_json);
    // A misspelled override silently does nothing: say so (names only, never values).
    let names: Vec<String> = std::env::vars_os().filter_map(|(k, _)| k.into_string().ok()).collect();
    for name in config::unknown_env_overrides(names.iter().map(String::as_str)) {
        tracing::warn!(variable = %name, "unknown HD_CRANK_* environment variable (not a setting; see `hd-crank config --env`)");
    }
    let r = match cli.cmd {
        Cmd::Run => app::run_until(cfg, shutdown_signal()).await,
        Cmd::Check => app::check(cfg).await,
        Cmd::Config { .. } => {
            println!("{}", serde_json::to_string_pretty(&cfg.public_json()).unwrap_or_default());
            Ok(())
        }
        Cmd::Decode { signature, json } => app::decode(cfg, signature, json).await,
        Cmd::Replay { signature, rigs, dry_run, cu_price } => {
            app::replay(cfg, ReplayOpts { signature, rigs, dry_run, cu_price_micro_lamports: cu_price }).await
        }
    };
    match r {
        Ok(()) => ExitCode::SUCCESS,
        Err(e) => {
            let msg = redact::redact_secrets(&e.to_string()).into_owned();
            tracing::error!(error = %msg, "hd-crank stopped");
            eprintln!("hd-crank: {msg}");
            ExitCode::FAILURE
        }
    }
}
