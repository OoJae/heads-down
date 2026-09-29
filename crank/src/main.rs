//! `hd-crank`: the permissionless Heads Down dig crank.
//!
//! ```text
//! hd-crank run   [--config crank.toml] [--keypair ~/.config/hd-crank/id.json]
//! hd-crank check [--config crank.toml]      # read-only: chain view, pins, gate, dry-run plan
//! ```

use std::path::PathBuf;
use std::process::ExitCode;
use std::sync::Arc;
use std::time::Duration;

use clap::{Parser, Subcommand};
use hd_crank::breaker::Breaker;
use hd_crank::chain::{self, ChainView, WsChainSource};
use hd_crank::config::Config;
use hd_crank::crank::{self, Crank};
use hd_crank::heartbeat::{HeartbeatStore, RigCache, Verifier};
use hd_crank::intake::{self, Intake};
use hd_crank::metrics::Metrics;
use hd_crank::mirror::{HeartbeatMirror, NoMirror};
use hd_crank::rpc::{redact_url, RpcClient, RpcRigSource};
use hd_crank::sender::Submitter;
use hd_crank::{gate, hd, keys, ore};
use solana_signer::Signer;
use tokio::sync::watch;

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
        Cmd::Run => run(cfg).await,
        Cmd::Check => check(cfg).await,
    };
    match r {
        Ok(()) => ExitCode::SUCCESS,
        Err(e) => {
            tracing::error!(error = %e, "hd-crank stopped");
            ExitCode::FAILURE
        }
    }
}

async fn run(cfg: Config) -> anyhow::Result<()> {
    let path = cfg
        .keypair_path
        .clone()
        .ok_or_else(|| anyhow::anyhow!("no keypair: pass --keypair or set HD_CRANK_KEYPAIR"))?;
    let key = keys::load_keypair(&path)?;
    let program_id = cfg.program_id();
    tracing::info!(
        cranker = %key.keypair().pubkey(),
        program = %program_id,
        rpc = %redact_url(&cfg.rpc_url),
        format = ?cfg.dig.tx_format,
        "starting hd-crank"
    );
    let metrics = Arc::new(Metrics::default());
    let breaker = Arc::new(Breaker::new(metrics.clone()));
    let rpc = RpcClient::new(cfg.rpc_url.clone(), cfg.commitment.clone(), Duration::from_secs(10))?;

    // Chain watcher.
    let (chain_tx, chain_rx) = watch::channel(ChainView::default());
    let ws = cfg.ws_url.clone().unwrap_or_default();
    let source = Arc::new(WsChainSource::new(ws, cfg.commitment.clone()));
    {
        let (rpc, breaker, metrics) = (rpc.clone(), breaker.clone(), metrics.clone());
        tokio::spawn(async move {
            if let Err(e) = chain::run_watcher(source, rpc, chain_tx, breaker, metrics, Duration::from_secs(5)).await {
                tracing::error!(error = %e, "chain watcher stopped");
            }
        });
    }

    // Heartbeat intake.
    let store = Arc::new(HeartbeatStore::new(cfg.intake.max_heartbeat_rigs));
    let verifier = Verifier {
        program_id,
        rigs: RigCache::new(
            RpcRigSource { rpc: rpc.clone(), program_id },
            Duration::from_secs(cfg.intake.rig_cache_ttl_secs),
            Duration::from_secs(30),
            cfg.intake.max_heartbeat_rigs,
            cfg.intake.rig_fetches_per_second,
        ),
        store: store.clone(),
    };
    let mirror: Arc<dyn HeartbeatMirror> = Arc::new(NoMirror);
    let intake = Intake::new(cfg.intake.to_runtime(), verifier, metrics.clone(), breaker.clone(), chain_rx.clone(), mirror);
    let listener = tokio::net::TcpListener::bind(&cfg.listen).await?;
    tracing::info!(listen = %cfg.listen, "intake listening (/ws, /healthz, /metrics)");
    tokio::spawn(intake::serve(listener, intake::router(intake.clone())));

    // Submit path.
    let submitter = make_submitter(&cfg, rpc.clone())?;

    let seed = intake.clone();
    let crank = Crank::new(
        cfg,
        rpc,
        submitter,
        key,
        metrics,
        breaker,
        store,
        chain_rx,
        Box::new(move |a, r| seed.verifier().rigs.insert(a, r)),
    );
    tokio::select! {
        r = crank.run() => r,
        _ = tokio::signal::ctrl_c() => {
            tracing::info!("shutting down");
            Ok(())
        }
    }
}

#[cfg(feature = "helius-sender")]
fn make_submitter(cfg: &Config, rpc: RpcClient) -> anyhow::Result<Submitter> {
    match &cfg.sender.helius_sender_url {
        Some(url) => {
            let tips = cfg.sender.tip_accounts.iter().filter_map(|s| s.parse().ok()).collect();
            let sender = RpcClient::new(url.clone(), cfg.commitment.clone(), Duration::from_secs(5))?;
            tracing::info!(sender = %redact_url(url), tip = cfg.sender.tip_lamports, "submitting through Helius Sender");
            Ok(Submitter::helius_sender(rpc, sender, tips, cfg.sender.tip_lamports))
        }
        None => Ok(Submitter::rpc(rpc)),
    }
}

#[cfg(not(feature = "helius-sender"))]
fn make_submitter(cfg: &Config, rpc: RpcClient) -> anyhow::Result<Submitter> {
    if cfg.sender.helius_sender_url.is_some() {
        anyhow::bail!("sender.helius_sender_url needs a build with --features helius-sender");
    }
    Ok(Submitter::rpc(rpc))
}

async fn check(cfg: Config) -> anyhow::Result<()> {
    let program_id = cfg.program_id();
    let rpc = RpcClient::new(cfg.rpc_url.clone(), cfg.commitment.clone(), Duration::from_secs(15))?;
    let metrics = Arc::new(Metrics::default());
    let breaker = Arc::new(Breaker::new(metrics.clone()));
    let mut w = chain::Watcher::new(breaker.clone(), metrics);
    let evs = chain::poll_events(&rpc, None).await?;
    let mut cmds = Vec::new();
    for e in evs {
        cmds.extend(w.apply(e, std::time::Instant::now()));
    }
    if let Some(r) = w.round_address() {
        let acc = rpc.get_account(&r).await?;
        w.apply(chain::ChainEvent::Account { address: r, slot: w.view.slot, account: acc }, std::time::Instant::now());
    }
    let pd = rpc.get_account_slice(&ore::PROGRAMDATA_ADDRESS, 0, 45).await?;
    let pd_slot = pd.and_then(|a| ore::programdata_slot(&a.owner, &a.data));
    breaker.observe_programdata_slot(pd_slot, cfg.dig.ore_programdata_slot);
    let v = &w.view;
    println!("rpc            {}", redact_url(&cfg.rpc_url));
    println!("slot           {}", v.slot);
    if let Some(b) = v.board {
        println!("board          round {} slots [{}, {}) ema {} lamports/ORE", b.round_id, b.start_slot, b.end_slot, b.production_cost_ema);
    }
    if let Some(t) = v.treasury {
        println!("motherlode     {} ORE", t.motherlode as f64 / ore::ONE_ORE as f64);
    }
    if let (Some(b), Some(t)) = (v.board, v.treasury) {
        println!("ema_ev         {:?} lamports/ORE (gate value)", gate::ema_ev(b.production_cost_ema, t.motherlode));
    }
    println!("ore upgrade    slot {:?} (pinned {})", pd_slot, cfg.dig.ore_programdata_slot);
    println!("breaker        {}", breaker.reason().unwrap_or_else(|| "closed (all ORE pins match)".into()));
    let cfg_acc = rpc.get_account(&hd::config_pda(&program_id).0).await?;
    let Some(hd_cfg) = cfg_acc.and_then(|a| hd::HdConfig::decode(&program_id, &a.owner, &a.data).ok()) else {
        println!("heads_down     Config not found at {} (program not initialized on this cluster)", hd::config_pda(&program_id).0);
        return Ok(());
    };
    println!("heads_down     crank_fee {} executor_fee {} paused {}", hd_cfg.crank_fee, hd_cfg.executor_fee, hd_cfg.paused);
    let board = v.board.ok_or_else(|| anyhow::anyhow!("no board"))?;
    let heartbeats = std::collections::HashMap::new();
    let fetched = crank::fetch_for_plan(&rpc, &program_id, board.round_id, &heartbeats, &breaker).await?;
    println!("rigs           {} armed/down, {} with a covering lease", fetched.all_rigs.len(), fetched.rigs.len());
    let plan = crank::plan_with(&program_id, v, &hd_cfg, &fetched, &heartbeats, &cfg.dig.policy(), &|_: &solana_address::Address, _| false)?;
    for d in &plan.digs {
        println!("  dig  {} per_tile {} x {} (+fee) = {}", d.dig.accounts.rig, d.per_tile, d.tiles, d.expected_debit);
    }
    for (a, s) in &plan.skips {
        println!("  skip {a} {}", s.label());
    }
    Ok(())
}
