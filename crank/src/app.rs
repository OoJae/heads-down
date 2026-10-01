//! Process wiring shared by the `hd-crank` binary and the end-to-end test: chain watcher,
//! heartbeat intake, submitter and the dig loop.

use std::collections::HashMap;
use std::sync::Arc;
use std::time::{Duration, Instant};

use solana_address::Address;
use solana_signer::Signer;
use tokio::sync::watch;

use crate::breaker::Breaker;
use crate::chain::{self, ChainView, WsChainSource};
use crate::config::Config;
use crate::crank::{self, Crank};
use crate::heartbeat::{HeartbeatStore, RigCache, Verifier};
use crate::intake::{self, Intake};
use crate::metrics::Metrics;
use crate::mirror::{HeartbeatMirror, NoMirror};
use crate::rpc::{redact_url, RpcClient, RpcRigSource};
use crate::sender::Submitter;
use crate::signal::SignalHub;
use crate::{gate, hd, keys, ore};

/// Run the crank until the chain watcher stops (the binary adds Ctrl-C handling).
pub async fn run(cfg: Config) -> anyhow::Result<()> {
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

    let (chain_tx, chain_rx) = watch::channel(ChainView::default());
    let source = Arc::new(WsChainSource::new(cfg.ws_url.clone().unwrap_or_default(), cfg.commitment.clone()));
    {
        let (rpc, breaker, metrics) = (rpc.clone(), breaker.clone(), metrics.clone());
        tokio::spawn(async move {
            if let Err(e) = chain::run_watcher(source, rpc, chain_tx, breaker, metrics, Duration::from_secs(5)).await {
                tracing::error!(error = %e, "chain watcher stopped");
            }
        });
    }

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
    let signals = Arc::new(SignalHub::new(cfg.signals.hub()));
    let intake = Intake::new(
        cfg.intake.to_runtime(),
        verifier,
        signals.clone(),
        metrics.clone(),
        breaker.clone(),
        chain_rx.clone(),
        mirror,
    );
    let listener = tokio::net::TcpListener::bind(&cfg.listen).await?;
    tracing::info!(listen = %cfg.listen, signals = cfg.signals.enabled, "intake listening (/ws, /v1/heartbeats, /healthz, /metrics)");
    tokio::spawn(intake::serve(listener, intake::router(intake.clone())));

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
        signals,
        chain_rx,
        Box::new(move |a, r| seed.verifier().rigs.insert(a, r)),
    );
    crank.run().await
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

/// Read-only report: chain view, pins, gate, heads_down Config and a dry-run plan.
pub async fn check(cfg: Config) -> anyhow::Result<()> {
    let program_id = cfg.program_id();
    let rpc = RpcClient::new(cfg.rpc_url.clone(), cfg.commitment.clone(), Duration::from_secs(15))?;
    let metrics = Arc::new(Metrics::default());
    let breaker = Arc::new(Breaker::new(metrics.clone()));
    let mut w = chain::Watcher::new(breaker.clone(), metrics);
    for e in chain::poll_events(&rpc, None).await? {
        w.apply(e, Instant::now());
    }
    if let Some(r) = w.round_address() {
        let acc = rpc.get_account(&r).await?;
        w.apply(chain::ChainEvent::Account { address: r, slot: w.view.slot, account: acc }, Instant::now());
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
    let cfg_addr = hd::config_pda(&program_id).0;
    let Some(hd_cfg) = rpc.get_account(&cfg_addr).await?.and_then(|a| hd::HdConfig::decode(&program_id, &a.owner, &a.data).ok())
    else {
        println!("heads_down     Config not found at {cfg_addr} (program not initialized on this cluster)");
        return Ok(());
    };
    println!("heads_down     crank_fee {} executor_fee {} paused {}", hd_cfg.crank_fee, hd_cfg.executor_fee, hd_cfg.paused);
    let board = v.board.ok_or_else(|| anyhow::anyhow!("no board"))?;
    let heartbeats = HashMap::new();
    let fetched = crank::fetch_for_plan(&rpc, &program_id, board.round_id, &heartbeats, &breaker).await?;
    println!(
        "rigs           {} armed/down/cooling, {} dig candidates with a covering lease",
        fetched.all_rigs.len(),
        fetched.rigs.len()
    );
    let plan = crank::plan_with(&program_id, v, &hd_cfg, &fetched, &heartbeats, &cfg.dig.policy(), &|_: &Address, _| false)?;
    for d in &plan.digs {
        println!(
            "  dig  {} {} x {} = {} on squares + fee {} = debit {}",
            d.dig.accounts.rig, d.per_tile, d.tiles, d.squares_lamports, d.fee_due, d.expected_debit
        );
    }
    for (a, s) in &plan.skips {
        println!("  skip {a} {}", s.label());
    }
    let open = rpc.get_program_accounts(&program_id, &crate::rpc::open_shift_filters()).await?.len();
    println!("open shifts    {open}");
    Ok(())
}
