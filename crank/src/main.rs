//! `hd-crank`: the permissionless Heads Down dig crank.
//!
//! ```text
//! hd-crank run   [--config crank.toml] [--keypair ~/.config/hd-crank/id.json]
//! hd-crank check [--config crank.toml]      # read-only: chain view, pins, gate, dry-run plan
//! ```

use std::path::PathBuf;
use std::process::ExitCode;

use clap::{Parser, Subcommand};
use hd_crank::app;
use hd_crank::config::Config;

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
    /// Run the watcher, the heartbeat intake and the dig loop.
    Run,
    /// Read-only: print the chain view, the ORE pins, the gate and a dry-run plan.
    Check,
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
    let b = tracing_subscriber::fmt().with_env_filter(filter).with_target(false);
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
    };
    match r {
        Ok(()) => ExitCode::SUCCESS,
        Err(e) => {
            tracing::error!(error = %e, "hd-crank stopped");
            ExitCode::FAILURE
        }
    }
}
