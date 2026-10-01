//! `hd-crank`: the permissionless Heads Down dig crank.
//!
//! ```text
//! hd-crank run    [--config crank.toml] [--keypair ~/.config/hd-crank/id.json]
//! hd-crank check  [--config crank.toml]      # read-only: chain view, pins, gate, dry-run plan
//! hd-crank decode <signature> [--json]       # heads_down events of a transaction (captions)
//! hd-crank replay --signature <landed dig> [--rig <address>]... [--dry-run]
//!                                             # resubmit a landed dig's heartbeat: RigSkipped(StaleHeartbeat)
//! ```

use std::path::PathBuf;
use std::process::ExitCode;

use clap::{Parser, Subcommand};
use hd_crank::app;
use hd_crank::config::Config;
use hd_crank::demo::ReplayOpts;
use solana_address::Address;

#[derive(Parser)]
#[command(name = "hd-crank", version, about = "Permissionless dig crank for Heads Down (liveness only; cannot move funds)")]
struct Cli {
    /// TOML config file (optional; every field has a default).
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
    /// Run the watcher, the intake, the dig loop, BREAK / FREEZE landing, record_heartbeats
    /// and the permissionless end_shift sweep.
    Run,
    /// Read-only: print the chain view, the ORE pins, the gate and a dry-run plan.
    Check,
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

fn init_tracing(json: bool) {
    let filter = tracing_subscriber::EnvFilter::try_from_default_env()
        .unwrap_or_else(|_| tracing_subscriber::EnvFilter::new("info,hyper=warn,reqwest=warn"));
    let b = tracing_subscriber::fmt().with_env_filter(filter).with_target(false).with_writer(std::io::stderr);
    if json {
        b.json().init();
    } else {
        b.init();
    }
}

#[tokio::main]
async fn main() -> ExitCode {
    // One process-wide TLS crypto provider (reqwest and tokio-tungstenite both use rustls).
    let _ = rustls::crypto::ring::default_provider().install_default();
    let cli = Cli::parse();
    let cfg = match load_config(&cli) {
        Ok(c) => c,
        Err(e) => {
            eprintln!("hd-crank: {e}");
            return ExitCode::from(2);
        }
    };
    init_tracing(cfg.log_json);
    let r = match cli.cmd {
        Cmd::Run => tokio::select! {
            r = app::run(cfg) => r,
            _ = tokio::signal::ctrl_c() => {
                tracing::info!("shutting down");
                Ok(())
            }
        },
        Cmd::Check => app::check(cfg).await,
        Cmd::Decode { signature, json } => app::decode(cfg, signature, json).await,
        Cmd::Replay { signature, rigs, dry_run, cu_price } => {
            app::replay(cfg, ReplayOpts { signature, rigs, dry_run, cu_price_micro_lamports: cu_price }).await
        }
    };
    match r {
        Ok(()) => ExitCode::SUCCESS,
        Err(e) => {
            tracing::error!(error = %e, "hd-crank stopped");
            eprintln!("hd-crank: {e}");
            ExitCode::FAILURE
        }
    }
}
