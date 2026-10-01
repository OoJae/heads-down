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
use crate::crank::{self, Crank, CrankWiring, InFlight, StackNudge};
use crate::heartbeat::{HeartbeatStore, RigCache, Verifier};
use crate::intake::{self, Intake};
use crate::metrics::Metrics;
use crate::mirror::{HeartbeatMirror, NoMirror, NudgeMirror};
use crate::rpc::{redact_url, RpcClient, RpcRigSource};
use crate::sender::Submitter;
use crate::signal::SignalHub;
use crate::{demo, gate, hd, keys, ore, redact, skr};

/// Run the crank until the chain watcher stops. No signal handling: see [`run_until`].
pub async fn run(cfg: Config) -> anyhow::Result<()> {
    run_until(cfg, std::future::pending::<&'static str>()).await
}

/// One structured line with everything about the run that is not secret: who signs, which
/// program and cluster, which duties are on, and the whole effective configuration with every
/// URL cut down to scheme and host. The fee-payer key and the RPC API key are never part of it.
pub fn log_startup(cfg: &Config, cranker: &Address) {
    tracing::info!(
        version = env!("CARGO_PKG_VERSION"),
        cranker = %cranker,
        program = %cfg.program_id(),
        rpc = %redact_url(&cfg.rpc_url),
        ws = %redact_url(cfg.ws_url.as_deref().unwrap_or_default()),
        commitment = %cfg.commitment,
        listen = %cfg.listen,
        tx_format = ?cfg.dig.tx_format,
        dig = cfg.dig.enabled,
        signals = cfg.signals.enabled,
        streak_protection = cfg.signals.streak_protection,
        record = cfg.record.enabled,
        end_shift = cfg.end_shift.enabled,
        stack = cfg.stack.enabled,
        stack_settle = cfg.stack.settle,
        cleanup = cfg.cleanup.enabled,
        config = %cfg.public_json(),
        "starting hd-crank"
    );
}

/// Run the crank until the chain watcher stops or `shutdown` resolves (the binary passes
/// SIGTERM / SIGINT), then drain gracefully: the intake answers `rate_limited` and `/healthz`
/// turns to `draining` (503), no new dig, record, settle or cleanup starts, the check-ins of
/// Stack seats whose heartbeat is already held are sent at once, and the process waits up to
/// `shutdown_grace_secs` for what is in flight (transactions sent and unconfirmed, and
/// phone-signed BREAK / FREEZE messages that were acknowledged) before it returns.
pub async fn run_until(cfg: Config, shutdown: impl std::future::Future<Output = &'static str>) -> anyhow::Result<()> {
    // Before anything can log: the keys inside the URLs are replaced in every log line.
    redact::register_secrets(cfg.secrets());
    let path = cfg
        .keypair_path
        .clone()
        .ok_or_else(|| anyhow::anyhow!("no keypair: pass --keypair or set HD_CRANK_KEYPAIR"))?;
    // The key is loaded before the port is bound: a container entrypoint may delete the key
    // file as soon as the port answers.
    let key = keys::load_keypair(&path)?;
    let program_id = cfg.program_id();
    log_startup(&cfg, &key.keypair().pubkey());
    let metrics = Arc::new(Metrics::default());
    let breaker = Arc::new(Breaker::new(metrics.clone()));
    let rpc = RpcClient::new(cfg.rpc_url.clone(), cfg.commitment.clone(), Duration::from_secs(10))?;

    let (chain_tx, chain_rx) = watch::channel(ChainView::default());
    // The program's log stream is a hint for the Stack and cleanup loops (they also poll).
    let stream_events = cfg.stack.enabled || cfg.cleanup.enabled;
    let (events_tx, events_rx) = tokio::sync::mpsc::channel(1024);
    let mut source = WsChainSource::new(cfg.ws_url.clone().unwrap_or_default(), cfg.commitment.clone());
    if stream_events {
        source = source.with_program_logs(program_id);
    }
    let source = Arc::new(source);
    {
        let (rpc, breaker, metrics) = (rpc.clone(), breaker.clone(), metrics.clone());
        let program = stream_events.then_some((program_id, events_tx));
        tokio::spawn(async move {
            if let Err(e) = chain::run_watcher_with(source, rpc, chain_tx, breaker, metrics, Duration::from_secs(5), program).await {
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
    let nudge = StackNudge::default();
    let mirror: Arc<dyn HeartbeatMirror> = Arc::new(NudgeMirror { inner: Arc::new(NoMirror), nudge: nudge.clone() });
    let signals = Arc::new(SignalHub::new(cfg.signals.hub()));
    let intake = Intake::new(
        cfg.intake_runtime(),
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
    let grace = Duration::from_secs(cfg.shutdown_grace_secs);
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
        CrankWiring { nudge, events: stream_events.then_some(events_rx), in_flight: Arc::new(InFlight::default()) },
    );
    // The loop runs as its own task so that a shutdown does not cut a pass in half: it keeps
    // running in draining mode (see `Crank::drain`) until the process returns.
    let mut run = tokio::spawn(crank.clone().run());
    tokio::select! {
        r = &mut run => match r {
            Ok(r) => r,
            Err(e) => Err(anyhow::anyhow!("the crank loop stopped: {e}")),
        },
        signal = shutdown => {
            let t0 = Instant::now();
            tracing::info!(signal, grace_secs = grace.as_secs(), "shutdown requested: draining");
            intake.set_draining();
            let left = crank.drain(grace).await;
            run.abort();
            if left == 0 {
                tracing::info!(signal, waited_ms = t0.elapsed().as_millis() as u64, "shutdown complete: nothing in flight");
            } else {
                tracing::warn!(signal, in_flight = left, waited_ms = t0.elapsed().as_millis() as u64, "shutdown: the grace period ended with transactions still unconfirmed (they may still land)");
            }
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
    // INTERFACE v1.2: what the Stack and cleanup loops would find.
    let tables: Vec<(Address, skr::StackTable)> = rpc
        .get_program_accounts(&program_id, &crate::rpc::open_stack_table_filters())
        .await?
        .into_iter()
        .filter_map(|(a, acc)| skr::StackTable::decode(&program_id, &acc.owner, &acc.data).ok().map(|t| (a, t)))
        .collect();
    let seats = rpc.get_program_accounts(&program_id, &crate::rpc::pending_stack_seat_filters(None)).await?.len();
    println!("stack          {} open tables, {seats} seats without an outcome", tables.len());
    for (a, t) in &tables {
        let when = if t.in_window(board.round_id) {
            "live"
        } else if t.settleable(board.round_id) {
            "to settle"
        } else {
            "not started"
        };
        println!(
            "  table {a} rounds {}..{} ({when}) {} seats, bond {} SKR base units, grace {}, {}",
            t.start_round,
            t.end_round,
            t.seat_count,
            t.bond,
            t.grace_gaps,
            skr::flags_name(t.flags)
        );
    }
    let bonds = rpc.get_program_accounts(&program_id, &crate::rpc::focus_bond_filters()).await?.len();
    let gifts: Vec<skr::GiftEscrow> = rpc
        .get_program_accounts(&program_id, &crate::rpc::gift_escrow_filters())
        .await?
        .into_iter()
        .filter_map(|(_, acc)| skr::GiftEscrow::decode(&program_id, &acc.owner, &acc.data).ok())
        .collect();
    let now = rpc.get_cluster_unix_timestamp().await.unwrap_or_else(|_| chain::system_unix_now());
    let expired = gifts.iter().filter(|g| g.refundable_at(now)).count();
    println!("focus bonds    {bonds}");
    println!("gifts          {} in escrow, {expired} past expiry (refundable to their sender)", gifts.len());
    let bury = skr::bury_vault_pda(&program_id).0;
    let bury_ok = rpc.get_account(&bury).await?.is_some_and(|a| skr::BuryVault::decode(&program_id, &a.owner, &a.data).is_ok());
    println!("bury vault     {bury} {}", if bury_ok { "initialized" } else { "not initialized" });
    Ok(())
}

/// `hd-crank decode <signature>`: the heads_down events of a transaction, for captions.
pub async fn decode(cfg: Config, signature: String, json: bool) -> anyhow::Result<()> {
    let rpc = RpcClient::new(cfg.rpc_url.clone(), cfg.commitment.clone(), Duration::from_secs(15))?;
    demo::decode(&rpc, &cfg.program_id(), &signature, json).await
}

/// `hd-crank replay --signature <sig>`: resubmit a landed dig's heartbeats (see [`demo`]).
pub async fn replay(cfg: Config, opts: demo::ReplayOpts) -> anyhow::Result<()> {
    let path = cfg
        .keypair_path
        .clone()
        .ok_or_else(|| anyhow::anyhow!("no keypair: pass --keypair or set HD_CRANK_KEYPAIR (it pays the replay's fee)"))?;
    let key = keys::load_keypair(&path)?;
    let rpc = RpcClient::new(cfg.rpc_url.clone(), cfg.commitment.clone(), Duration::from_secs(15))?;
    demo::replay(&rpc, &cfg.program_id(), &key, opts).await
}
