//! The crank loop.
//!
//! ```text
//! chain view ──► (new round) maintenance: prune, lookup-table sync, checkpoint sweep
//!            ├─► (slots_left <= deploy_margin) dig pass:
//!            │     getProgramAccounts(Armed, Down, Cooling) → keep rigs with a lease or a held heartbeat
//!            │     → getMultipleAccounts(Automations, Miners, Executor) → planner
//!            │     → pack → [simulate → size CU | bisect on failure] → sign → send
//!            │     → ledger (rig, round) pending → confirm task → events → ledger / metrics
//!            └─► (delay after the round starts) record pass: focus-only rigs → record_heartbeats
//! intake ──► SignalHub ──► signal lander: phone-signed BREAK / FREEZE → break_shift / freeze_rig
//! ```
//!
//! Every pass re-reads the chain, so a retry never reuses stale instructions (the fork
//! suite shows a stale checkpoint aborting a whole batch).

use std::collections::{HashMap, VecDeque};
use std::path::PathBuf;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use solana_address::Address;
use solana_instruction::Instruction;
use solana_message::AddressLookupTableAccount;
use solana_signer::Signer;
use tokio::sync::{mpsc, watch};

use crate::account::RawAccount;
use crate::alt::{self, LookupTable};
use crate::breaker::Breaker;
use crate::chain::ChainView;
use crate::config::Config;
use crate::hd::{self, HdConfig, HdEvent, Rig, RigAccounts, RigState};
use crate::heartbeat::{HeartbeatStore, VerifiedHeartbeat, VerifiedSignal};
use crate::keys::CrankKey;
use crate::ledger::{DigStatus, Ledger, RetryPolicy};
use crate::metrics::Metrics;
use crate::ore::{self, Miner, OreKind};
use crate::planner::{self, Plan, Policy, Skip, SubmittedCheck};
use crate::rpc::{self, Filter, RpcClient};
use crate::sender::{ConfirmPolicy, Outcome, Submitter};
use crate::signal::{FeeBudget, SignalHub, SignalState};
use crate::tx::{self, BuildParams, RecordRig, RigDig, TxFormat};

/// Slots between planning and the expected landing slot.
pub const LANDING_LEAD_SLOTS: u64 = 2;
/// Simulations per dig pass (bisecting a failing batch included).
pub const MAX_SIMULATIONS_PER_PASS: usize = 16;
/// Lookup-table extend transactions per round at most.
pub const MAX_ALT_TXS_PER_ROUND: usize = 4;
/// Checkpoint sweep transactions per sweep at most.
pub const MAX_SWEEP_TXS: usize = 5;
/// Record transactions per round at most.
pub const MAX_RECORD_TXS_PER_ROUND: usize = 8;

fn unix_now() -> i64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_secs() as i64).unwrap_or(0)
}

fn lock<T>(m: &Mutex<T>) -> std::sync::MutexGuard<'_, T> {
    match m.lock() {
        Ok(g) => g,
        Err(p) => p.into_inner(),
    }
}

/// Read every Rig that can take a heartbeat: Armed, Down and Cooling.
pub async fn load_diggable_rigs(rpc: &RpcClient, program_id: &Address) -> anyhow::Result<Vec<(Address, Rig)>> {
    let mut out = Vec::new();
    for state in [RigState::Armed, RigState::Down, RigState::Cooling] {
        for (addr, acc) in rpc.get_program_accounts(program_id, &rpc::rig_filters(state)).await? {
            if let Ok(r) = Rig::decode(program_id, &acc.owner, &acc.data) {
                out.push((addr, r));
            }
        }
    }
    Ok(out)
}

/// Everything a plan needs from the chain besides the watcher's view.
pub struct Fetched {
    /// Candidate rigs after the cheap pre-filter.
    pub rigs: Vec<(Address, Rig)>,
    /// All Armed/Down/Cooling rigs read (lookup-table sync, the intake cache, the record pass).
    pub all_rigs: Vec<(Address, Rig)>,
    /// Automations.
    pub automations: HashMap<Address, Option<RawAccount>>,
    /// Miners.
    pub miners: HashMap<Address, Option<RawAccount>>,
    /// Executor PDA balance.
    pub executor_lamports: u64,
    /// heads_down Config read in the same call (`None` if absent or undecodable).
    pub config: Option<HdConfig>,
}

/// Read rigs and their ORE accounts, pre-filtering rigs that cannot dig this round anyway
/// (focus-only, already dug, or neither a covering lease nor a held heartbeat). ORE-owned
/// user accounts that fail their layout pin trip the breaker.
pub async fn fetch_for_plan(
    rpc: &RpcClient,
    program_id: &Address,
    round_id: u64,
    heartbeats: &HashMap<Address, VerifiedHeartbeat>,
    breaker: &Breaker,
) -> anyhow::Result<Fetched> {
    let all_rigs = load_diggable_rigs(rpc, program_id).await?;
    let rigs: Vec<(Address, Rig)> = all_rigs
        .iter()
        .filter(|(a, r)| {
            !r.focus_only() && r.last_dug_round != round_id && (r.lease_covers(round_id) || heartbeats.contains_key(a))
        })
        .cloned()
        .collect();
    let executor = hd::executor_pda(program_id).0;
    // The Config rides along so `paused` and the fees are as fresh as the Automations.
    let mut keys = vec![executor, hd::config_pda(program_id).0];
    for (a, r) in &rigs {
        let acc = RigAccounts::derive(*a, r.authority);
        keys.push(acc.automation);
        keys.push(acc.miner);
    }
    let accs = rpc.get_multiple_accounts(&keys).await?;
    let executor_lamports = accs.first().and_then(|a| a.as_ref()).map_or(0, |a| a.lamports);
    let config = match accs.get(1).and_then(|a| a.as_ref()) {
        Some(c) => match HdConfig::decode(program_id, &c.owner, &c.data) {
            Ok(cfg) => Some(cfg),
            Err(e) => {
                breaker.trip(format!("heads_down Config: {e}"));
                None
            }
        },
        None => None,
    };
    let mut automations = HashMap::new();
    let mut miners = HashMap::new();
    for (i, (a, r)) in rigs.iter().enumerate() {
        let acc = RigAccounts::derive(*a, r.authority);
        let au = accs.get(2 + 2 * i).cloned().flatten();
        let mi = accs.get(3 + 2 * i).cloned().flatten();
        if let Some(x) = &au {
            breaker.observe_user_account(OreKind::Automation, &x.owner, &x.data);
        }
        if let Some(x) = &mi {
            breaker.observe_user_account(OreKind::Miner, &x.owner, &x.data);
        }
        automations.insert(acc.automation, au);
        miners.insert(acc.miner, mi);
    }
    Ok(Fetched { rigs, all_rigs, automations, miners, executor_lamports, config })
}

/// Plan from a view plus freshly fetched accounts.
pub fn plan_with(
    program_id: &Address,
    view: &ChainView,
    config: &HdConfig,
    f: &Fetched,
    heartbeats: &HashMap<Address, VerifiedHeartbeat>,
    policy: &Policy,
    submitted: &dyn SubmittedCheck,
) -> anyhow::Result<Plan> {
    let board = view.board.ok_or_else(|| anyhow::anyhow!("no Board yet"))?;
    let treasury = view.treasury.ok_or_else(|| anyhow::anyhow!("no Treasury yet"))?;
    let inputs = planner::Inputs {
        program_id,
        now_ts: unix_now(),
        landing_slot: view.slot.saturating_add(LANDING_LEAD_SLOTS),
        board: &board,
        treasury: &treasury,
        round: view.round.as_ref(),
        config,
        executor_lamports: f.executor_lamports,
        rigs: &f.rigs,
        automations: &f.automations,
        miners: &f.miners,
        heartbeats,
    };
    Ok(planner::plan(&inputs, policy, submitted))
}

/// The running crank.
pub struct Crank {
    cfg: Config,
    program_id: Address,
    rpc: RpcClient,
    submitter: Submitter,
    key: CrankKey,
    metrics: Arc<Metrics>,
    breaker: Arc<Breaker>,
    store: Arc<HeartbeatStore>,
    signals: Arc<SignalHub>,
    chain: watch::Receiver<ChainView>,
    ledger: Mutex<Ledger>,
    alts: Mutex<Vec<LookupTable>>,
    hd_config: Mutex<Option<HdConfig>>,
    known_rigs: Mutex<Vec<(Address, Rig)>>,
    rig_seed: Box<dyn Fn(Address, Rig) + Send + Sync>,
    nonce: AtomicU64,
    alt_sync: tokio::sync::Mutex<()>,
    record_budget: FeeBudget,
}

impl Crank {
    /// Assemble. `rig_seed` receives every Rig read (the intake uses it to warm its cache).
    #[allow(clippy::too_many_arguments)]
    pub fn new(
        cfg: Config,
        rpc: RpcClient,
        submitter: Submitter,
        key: CrankKey,
        metrics: Arc<Metrics>,
        breaker: Arc<Breaker>,
        store: Arc<HeartbeatStore>,
        signals: Arc<SignalHub>,
        chain: watch::Receiver<ChainView>,
        rig_seed: Box<dyn Fn(Address, Rig) + Send + Sync>,
    ) -> Arc<Self> {
        let program_id = cfg.program_id();
        Arc::new(Crank {
            record_budget: FeeBudget::new(cfg.record.max_lamports_per_hour, Duration::from_secs(3600)),
            cfg,
            program_id,
            rpc,
            submitter,
            key,
            metrics,
            breaker,
            store,
            signals,
            chain,
            ledger: Mutex::new(Ledger::new()),
            alts: Mutex::new(Vec::new()),
            hd_config: Mutex::new(None),
            known_rigs: Mutex::new(Vec::new()),
            rig_seed,
            nonce: AtomicU64::new(0),
            alt_sync: tokio::sync::Mutex::new(()),
        })
    }

    fn cranker(&self) -> Address {
        self.key.keypair().pubkey()
    }

    fn retry_policy(&self) -> RetryPolicy {
        RetryPolicy { retry_after_slots: self.cfg.dig.retry_after_slots, max_attempts: self.cfg.dig.max_attempts_per_round }
    }

    fn state_file(&self) -> PathBuf {
        self.cfg.state_dir.join("lookup_tables.json")
    }

    /// Run until the chain watcher stops.
    pub async fn run(self: Arc<Self>) -> anyhow::Result<()> {
        if let Err(e) = self.load_alts().await {
            tracing::warn!(error = %e, "could not load lookup tables");
        }
        tokio::spawn(self.clone().poll_loop());
        if let Some(rx) = self.signals.take_receiver() {
            tokio::spawn(self.clone().signal_loop(rx));
        }
        let mut chain = self.chain.clone();
        let mut current_round = 0u64;
        let mut round_seen = Instant::now();
        let mut recorded_round = 0u64;
        let mut last_pass_slot: Option<u64> = None;
        loop {
            if let Ok(Err(_)) = tokio::time::timeout(Duration::from_millis(400), chain.changed()).await {
                anyhow::bail!("chain watcher stopped");
            }
            let view = chain.borrow().clone();
            let Some(board) = view.board.filter(|_| view.ready()) else { continue };
            if board.round_id != current_round {
                current_round = board.round_id;
                round_seen = Instant::now();
                last_pass_slot = None;
                let this = self.clone();
                tokio::spawn(async move { this.on_new_round(board.round_id).await });
            }
            if self.breaker.is_tripped() {
                continue;
            }
            // record_heartbeats once per round, once the phones' heartbeats for it are in.
            if self.cfg.record.enabled
                && recorded_round != current_round
                && round_seen.elapsed() >= Duration::from_secs(self.cfg.record.delay_secs)
            {
                recorded_round = current_round;
                let (this, v) = (self.clone(), view.clone());
                tokio::spawn(async move {
                    if let Err(e) = this.record_pass(&v).await {
                        tracing::warn!(error = %e, "record pass failed");
                    }
                });
            }
            if !self.cfg.dig.enabled {
                continue;
            }
            let d = &self.cfg.dig;
            let open = match view.slots_left() {
                Some(left) => left <= d.deploy_margin_slots && left >= d.min_slots_left,
                None => d.start_rounds,
            };
            if !open || last_pass_slot.is_some_and(|s| view.slot < s.saturating_add(d.retry_after_slots)) {
                continue;
            }
            last_pass_slot = Some(view.slot);
            if let Err(e) = self.dig_pass(&view).await {
                tracing::warn!(error = %e, round = board.round_id, "dig pass failed");
            }
        }
    }

    /// Current heads_down Config (cached by the poller).
    pub async fn hd_config(&self) -> Option<HdConfig> {
        if let Some(c) = *lock(&self.hd_config) {
            return Some(c);
        }
        self.refresh_hd_config().await
    }

    async fn refresh_hd_config(&self) -> Option<HdConfig> {
        let addr = hd::config_pda(&self.program_id).0;
        match self.rpc.get_account(&addr).await {
            Ok(Some(acc)) => match HdConfig::decode(&self.program_id, &acc.owner, &acc.data) {
                Ok(c) => {
                    *lock(&self.hd_config) = Some(c);
                    Some(c)
                }
                Err(e) => {
                    self.breaker.trip(format!("heads_down Config: {e}"));
                    None
                }
            },
            Ok(None) => {
                tracing::warn!("heads_down Config account not found (program not initialized?)");
                None
            }
            Err(e) => {
                tracing::warn!(error = %e, "reading heads_down Config failed");
                None
            }
        }
    }

    async fn poll_loop(self: Arc<Self>) {
        let every = Duration::from_secs(self.cfg.dig.config_poll_secs.max(5));
        loop {
            self.refresh_hd_config().await;
            match self.rpc.get_account_slice(&ore::PROGRAMDATA_ADDRESS, 0, 45).await {
                Ok(acc) => self.breaker.observe_programdata_slot(
                    acc.and_then(|a| ore::programdata_slot(&a.owner, &a.data)),
                    self.cfg.dig.ore_programdata_slot,
                ),
                Err(e) => tracing::warn!(error = %e, "reading ORE ProgramData failed"),
            }
            let exec = hd::executor_pda(&self.program_id).0;
            if let Ok(b) = self.rpc.get_balance(&exec).await {
                self.metrics.executor_lamports.set_u64(b);
            }
            if let Ok(b) = self.rpc.get_balance(&self.cranker()).await {
                self.metrics.cranker_lamports.set_u64(b);
            }
            let (board, slots_left) = {
                let v = self.chain.borrow();
                (v.board, v.slots_left())
            };
            if let Some(board) = board {
                self.store.prune(board.round_id, Duration::from_secs(15 * 60));
            }
            self.signals.prune(Duration::from_secs(6 * 3600));
            self.metrics.heartbeats_held.set_u64(self.store.len() as u64);
            // Register new rigs in the lookup table as they appear, but never inside the dig
            // window (an extend is only usable from the next slot anyway).
            let quiet = slots_left.is_none_or(|l| l > self.cfg.dig.deploy_margin_slots.saturating_add(10));
            if quiet && self.alts_in_use() && !self.breaker.is_tripped() {
                match load_diggable_rigs(&self.rpc, &self.program_id).await {
                    Ok(rigs) => {
                        for (a, r) in &rigs {
                            (self.rig_seed)(*a, r.clone());
                        }
                        *lock(&self.known_rigs) = rigs;
                        if let Err(e) = self.sync_alts().await {
                            tracing::warn!(error = %e, "lookup table sync failed");
                        }
                    }
                    Err(e) => tracing::warn!(error = %e, "reading rigs failed"),
                }
            }
            tokio::time::sleep(every).await;
        }
    }

    fn alts_in_use(&self) -> bool {
        self.cfg.dig.tx_format == TxFormat::V0 && self.cfg.alt.enabled
    }

    async fn on_new_round(self: Arc<Self>, round_id: u64) {
        lock(&self.ledger).prune(round_id.saturating_sub(2));
        self.store.prune(round_id, Duration::from_secs(15 * 60));
        if self.breaker.is_tripped() {
            return;
        }
        if self.alts_in_use() {
            if let Err(e) = self.sync_alts().await {
                tracing::warn!(error = %e, "lookup table sync failed");
            }
        }
        let d = &self.cfg.dig;
        if d.checkpoint_sweep && round_id.is_multiple_of(d.checkpoint_sweep_interval_rounds.max(1)) {
            if let Err(e) = self.checkpoint_sweep(round_id).await {
                tracing::warn!(error = %e, "checkpoint sweep failed");
            }
        }
    }

    fn base_params(&self, round_id: u64, cu_price: u64) -> BuildParams {
        let d = &self.cfg.dig;
        BuildParams {
            program_id: self.program_id,
            cranker: self.cranker(),
            round_id,
            format: d.tx_format,
            cu_price_micro_lamports: cu_price,
            cu_limit: None,
            cu_estimate: d.cu_estimate,
            loaded_accounts_data_size_limit: d.loaded_accounts_data_size_limit,
            max_account_locks: d.max_account_locks,
            max_rigs_per_tx: d.max_rigs_per_tx,
            tip: self.submitter.tip_for(0),
        }
    }

    /// One planning + submission pass for the current round.
    pub async fn dig_pass(self: &Arc<Self>, view: &ChainView) -> anyhow::Result<()> {
        let board = view.board.ok_or_else(|| anyhow::anyhow!("no Board"))?;
        let heartbeats: HashMap<Address, VerifiedHeartbeat> =
            self.store.snapshot().into_iter().map(|h| (h.rig, h)).collect();
        let fetched = fetch_for_plan(&self.rpc, &self.program_id, board.round_id, &heartbeats, &self.breaker).await?;
        if self.breaker.is_tripped() {
            return Ok(());
        }
        let config = fetched.config.ok_or_else(|| anyhow::anyhow!("heads_down Config unavailable"))?;
        *lock(&self.hd_config) = Some(config);
        self.metrics.rigs_seen.set_u64(fetched.all_rigs.len() as u64);
        for (a, r) in &fetched.all_rigs {
            (self.rig_seed)(*a, r.clone());
        }
        *lock(&self.known_rigs) = fetched.all_rigs.clone();
        let block_height = self.rpc.get_block_height().await?;
        let retry = self.retry_policy();
        let mut plan = {
            let ledger = lock(&self.ledger);
            let check = |r: &Address, round: u64| !ledger.can_submit(r, round, block_height, view.slot, retry);
            plan_with(&self.program_id, view, &config, &fetched, &heartbeats, &self.cfg.dig.policy(), &check)?
        };
        // A phone-signed BREAK / FREEZE is on its way: do not dig the rig on its old lease.
        let (keep, pending): (Vec<_>, Vec<_>) =
            plan.digs.into_iter().partition(|d| !self.signals.is_pending(&d.dig.accounts.rig));
        plan.digs = keep;
        plan.skips.extend(pending.into_iter().map(|d| (d.dig.accounts.rig, Skip::SignalPending)));
        for (_, s) in &plan.skips {
            self.metrics.digs_skipped.inc(s.label());
        }
        self.metrics.rigs_eligible.set_u64(plan.digs.len() as u64);
        tracing::info!(
            round = board.round_id,
            slot = view.slot,
            ema_ev = plan.ema_ev,
            candidates = fetched.rigs.len(),
            digs = plan.digs.len(),
            skips = plan.skips.len(),
            "planned"
        );
        if plan.digs.is_empty() {
            return Ok(());
        }
        self.submit(view, &plan).await
    }

    async fn priority_price(&self, round: &Address) -> u64 {
        let d = &self.cfg.dig;
        if !d.dynamic_priority_fee {
            return d.cu_price_micro_lamports;
        }
        match self.rpc.get_recent_prioritization_fees(&[ore::BOARD_ADDRESS, *round]).await {
            Ok(samples) => rpc::priority_fee_from_samples(
                samples,
                d.priority_fee_percentile,
                d.cu_price_micro_lamports,
                d.max_cu_price_micro_lamports,
            ),
            Err(_) => d.cu_price_micro_lamports,
        }
    }

    fn usable_alts(&self, slot: u64) -> Vec<AddressLookupTableAccount> {
        lock(&self.alts).iter().map(|t| t.usable(slot)).filter(|t| !t.addresses.is_empty()).collect()
    }

    async fn submit(self: &Arc<Self>, view: &ChainView, plan: &Plan) -> anyhow::Result<()> {
        let d = &self.cfg.dig;
        let round_id = plan.round_id;
        let round = ore::round_pda(round_id);
        let alts = if self.alts_in_use() { self.usable_alts(view.slot) } else { vec![] };
        let base = self.base_params(round_id, self.priority_price(&round).await);
        let rigs: Vec<RigDig> = plan.digs.iter().map(|x| x.dig).collect();
        let debits: HashMap<Address, u64> = plan.digs.iter().map(|x| (x.dig.accounts.rig, x.expected_debit)).collect();
        let (batches, rejected) = tx::pack(&base, &rigs, &alts);
        for (r, m) in rejected {
            tracing::warn!(rig = %r.accounts.rig, misfit = ?m, "rig does not fit a transaction alone");
            self.metrics.digs_skipped.inc("oversize");
        }
        let mut queue: VecDeque<Vec<RigDig>> = batches.into_iter().map(|b| b.rigs).collect();
        let mut sims = 0usize;
        while let Some(batch) = queue.pop_front() {
            let (blockhash, lvbh) = self.rpc.get_latest_blockhash().await?;
            let mut p = base.clone();
            p.tip = self.submitter.tip_for(self.nonce.fetch_add(1, Ordering::Relaxed));
            if d.simulate {
                sims += 1;
                #[allow(clippy::clone_on_copy)] // Hash is Copy only with solana-hash's `copy` feature
                let t = tx::sign_batch(&p, &batch, &alts, blockhash.clone(), self.key.keypair())?;
                match self.rpc.simulate_transaction(&tx::serialize(&t)?).await {
                    Ok(sim) if sim.err.is_none() => {
                        if let Some(u) = sim.units_consumed {
                            let with_margin = (u.saturating_mul(100u64.saturating_add(u64::from(d.cu_margin_percent))) / 100).saturating_add(1_000);
                            p.cu_limit = Some(u32::try_from(with_margin).unwrap_or(tx::MAX_COMPUTE_UNITS).min(tx::MAX_COMPUTE_UNITS));
                        }
                    }
                    Ok(sim) => {
                        self.metrics.txs_failed.inc("simulation");
                        let tail: Vec<&String> = sim.logs.iter().rev().take(6).collect();
                        tracing::warn!(err = ?sim.err, logs = ?tail, rigs = batch.len(), "simulation failed");
                        if batch.len() > 1 && sims < MAX_SIMULATIONS_PER_PASS {
                            // Bisect: isolate the rig that breaks the batch (docs/ORE.md F12).
                            let mut left = batch;
                            let right = left.split_off(left.len() / 2);
                            queue.push_front(right);
                            queue.push_front(left);
                        } else {
                            let mut l = lock(&self.ledger);
                            for r in &batch {
                                l.record_failed_attempt(&r.accounts.rig, round_id);
                            }
                        }
                        continue;
                    }
                    Err(e) => {
                        self.metrics.txs_failed.inc("simulation_rpc");
                        tracing::warn!(error = %e, "simulateTransaction failed");
                        continue;
                    }
                }
            }
            let t = tx::sign_batch(&p, &batch, &alts, blockhash, self.key.keypair())?;
            let wire = tx::serialize(&t)?;
            let sig_bytes: [u8; 64] = t.signatures.first().and_then(|s| <[u8; 64]>::try_from(s.as_ref()).ok()).unwrap_or([0; 64]);
            let sig = t.signatures.first().map(ToString::to_string).unwrap_or_default();
            if let Err(e) = self.submitter.send(&wire).await {
                self.metrics.txs_failed.inc("send");
                tracing::warn!(error = %e, "sendTransaction failed");
                continue;
            }
            let addrs: Vec<Address> = batch.iter().map(|r| r.accounts.rig).collect();
            lock(&self.ledger).mark_pending(&addrs, round_id, sig_bytes, lvbh, view.slot);
            self.metrics.txs_sent.inc();
            self.metrics.digs_submitted.add(batch.len() as u64);
            self.metrics.checkpoints_sent.add(batch.iter().filter(|r| r.checkpoint_round.is_some()).count() as u64);
            tracing::info!(%sig, round = round_id, rigs = batch.len(), cu_limit = ?p.cu_limit, "dig sent");
            let this = self.clone();
            let debits = debits.clone();
            tokio::spawn(async move {
                let slots_left = this.chain.borrow().slots_left().unwrap_or(0);
                let policy = ConfirmPolicy {
                    rebroadcast_for: Duration::from_millis(slots_left.saturating_mul(400)),
                    ..ConfirmPolicy::default()
                };
                let outcome = this.submitter.confirm(&sig, &wire, lvbh, policy).await;
                this.handle_outcome(&sig, &batch, round_id, outcome, &debits).await;
            });
        }
        Ok(())
    }

    async fn fetch_events(&self, sig: &str, max_version: u8) -> Option<crate::rpc::TransactionInfo> {
        for _ in 0..5 {
            if let Ok(Some(i)) = self.rpc.get_transaction(sig, max_version).await {
                return Some(i);
            }
            tokio::time::sleep(Duration::from_millis(800)).await;
        }
        None
    }

    async fn handle_outcome(&self, sig: &str, batch: &[RigDig], round_id: u64, outcome: Outcome, debits: &HashMap<Address, u64>) {
        match outcome {
            Outcome::Landed { err: None, slot } => {
                self.metrics.txs_confirmed.inc();
                let max_version = if self.cfg.dig.tx_format == TxFormat::V1 { 1 } else { 0 };
                let info = self.fetch_events(sig, max_version).await;
                let crank_fee = self.hd_config().await.map_or(0, |c| c.crank_fee);
                let (mut dug, mut skipped) = (0u32, 0u32);
                let mut seen = std::collections::HashSet::new();
                if let Some(i) = &info {
                    self.metrics.fees_lamports.add(i.fee);
                    self.metrics.compute_units.add(i.compute_units.unwrap_or(0));
                    for ev in hd::events_from_logs(&self.program_id, &i.logs) {
                        match ev {
                            HdEvent::RigDug { rig, lamports, .. } if seen.insert(rig) => {
                                dug += 1;
                                lock(&self.ledger).mark(&rig, round_id, DigStatus::Landed);
                                self.metrics.digs_landed.inc();
                                self.metrics.reimbursed_lamports.add(crank_fee);
                                // RigDug.lamports is the SOL on squares; the debit adds the
                                // Automation fee on the rig's first deploy of the round.
                                self.metrics.squares_lamports.add(lamports);
                                self.metrics.automation_debit_lamports.add(debits.get(&rig).copied().unwrap_or(lamports));
                                if let Some(h) = batch.iter().find(|r| r.accounts.rig == rig).and_then(|r| r.heartbeat) {
                                    self.store.remove_if_counter_at_most(&rig, h.fields.counter);
                                }
                            }
                            HdEvent::RigSkipped { rig, error, .. } if seen.insert(rig) => {
                                skipped += 1;
                                lock(&self.ledger).mark(&rig, round_id, DigStatus::SkippedOnChain(error));
                                self.metrics.digs_skipped_onchain.inc(hd::error_name(error));
                            }
                            _ => {}
                        }
                    }
                }
                // Processed without an event we could read: never retry it this round.
                for r in batch.iter().filter(|r| !seen.contains(&r.accounts.rig)) {
                    lock(&self.ledger).mark(&r.accounts.rig, round_id, DigStatus::SkippedOnChain(u32::MAX));
                }
                tracing::info!(%sig, slot, dug, skipped, "dig landed");
            }
            Outcome::Landed { err: Some(err), slot } => {
                self.metrics.txs_failed.inc("onchain");
                tracing::warn!(%sig, slot, %err, "dig transaction failed on-chain");
                let mut l = lock(&self.ledger);
                for r in batch {
                    l.mark(&r.accounts.rig, round_id, DigStatus::Failed);
                }
            }
            Outcome::Expired => {
                self.metrics.txs_failed.inc("expired");
                let mut l = lock(&self.ledger);
                for r in batch {
                    l.mark(&r.accounts.rig, round_id, DigStatus::Failed);
                }
            }
            Outcome::Unknown => self.metrics.txs_failed.inc("unconfirmed"),
        }
    }

    // ---- BREAK / FREEZE -------------------------------------------------------------------

    async fn signal_loop(self: Arc<Self>, mut rx: mpsc::Receiver<VerifiedSignal>) {
        while let Some(s) = rx.recv().await {
            let this = self.clone();
            tokio::spawn(async move { this.land_signal(s).await });
        }
    }

    /// Land one phone-signed BREAK / FREEZE on the P-256 path: `[CU limit, CU price,
    /// Secp256r1SigVerify, break_shift | freeze_rig]`, the crank paying the fee. A signal the
    /// program refuses (in simulation: its counter was consumed, the state moved on) is not
    /// sent and never resubmitted; an expired blockhash is retried with a fresh one.
    pub async fn land_signal(&self, s: VerifiedSignal) {
        let c = &self.cfg.signals;
        let ixs = match tx::signal_instructions(
            &self.program_id,
            s.kind,
            &s.rig,
            &s.authority,
            s.reason,
            s.counter,
            s.sig,
            s.pubkey,
            s.digest,
            c.cu_limit,
            c.cu_price_micro_lamports,
        ) {
            Ok(i) => i,
            Err(e) => {
                tracing::warn!(rig = %s.rig, error = %e, "building the signal transaction failed");
                self.metrics.signals_failed.inc("build");
                self.signals.mark(&s.rig, s.counter, SignalState::Refused);
                self.signals.refund(c.est_fee());
                return;
            }
        };
        for attempt in 1..=c.max_attempts.max(1) {
            let (bh, lvbh) = match self.rpc.get_latest_blockhash().await {
                Ok(x) => x,
                Err(e) => {
                    tracing::warn!(error = %e, "getLatestBlockhash failed (signal)");
                    tokio::time::sleep(Duration::from_millis(500)).await;
                    continue;
                }
            };
            let signed = match tx::sign_legacy(&ixs, self.key.keypair(), bh) {
                Ok(t) => t,
                Err(e) => {
                    tracing::warn!(error = %e, "signing the signal transaction failed");
                    break;
                }
            };
            let Ok(wire) = tx::serialize(&signed) else { break };
            if c.simulate {
                match self.rpc.simulate_transaction(&wire).await {
                    Ok(sim) if sim.err.is_some() => {
                        let tail: Vec<&String> = sim.logs.iter().rev().take(4).collect();
                        tracing::warn!(rig = %s.rig, kind = s.kind.name(), counter = s.counter, err = ?sim.err, logs = ?tail, "signal refused in simulation; not sent");
                        self.metrics.signals_failed.inc("simulation");
                        self.signals.mark(&s.rig, s.counter, SignalState::Refused);
                        self.signals.refund(c.est_fee());
                        return;
                    }
                    Ok(_) => {}
                    Err(e) => tracing::warn!(error = %e, "simulateTransaction failed (signal); sending anyway"),
                }
            }
            let sig = signed.signatures.first().map(ToString::to_string).unwrap_or_default();
            if let Err(e) = self.submitter.send(&wire).await {
                tracing::warn!(error = %e, attempt, "sending the signal failed");
                self.metrics.signals_failed.inc("send");
                continue;
            }
            let policy = ConfirmPolicy {
                poll_every: Duration::from_millis(400),
                rebroadcast_every: Duration::from_secs(1),
                rebroadcast_for: Duration::from_secs(30),
                max_wait: Duration::from_secs(90),
            };
            match self.submitter.confirm(&sig, &wire, lvbh, policy).await {
                Outcome::Landed { err: None, slot } => {
                    self.signals.mark(&s.rig, s.counter, SignalState::Landed);
                    self.store.remove_if_counter_at_most(&s.rig, s.counter);
                    self.metrics.signals_landed.inc(s.kind.name());
                    if let Some(i) = self.fetch_events(&sig, 0).await {
                        self.metrics.signal_fees_lamports.add(i.fee);
                    }
                    tracing::info!(%sig, slot, rig = %s.rig, kind = s.kind.name(), reason = hd::reason_name(s.reason), counter = s.counter, "signal landed");
                    return;
                }
                Outcome::Landed { err: Some(err), slot } => {
                    tracing::warn!(%sig, slot, %err, rig = %s.rig, "signal failed on-chain");
                    self.metrics.signals_failed.inc("onchain");
                    self.signals.mark(&s.rig, s.counter, SignalState::Refused);
                    return;
                }
                Outcome::Expired => {
                    self.metrics.signals_failed.inc("expired");
                    tracing::warn!(%sig, attempt, rig = %s.rig, "signal blockhash expired; retrying with a fresh one");
                }
                Outcome::Unknown => {
                    self.metrics.signals_failed.inc("unconfirmed");
                    self.signals.mark(&s.rig, s.counter, SignalState::Failed);
                    return;
                }
            }
        }
        self.signals.mark(&s.rig, s.counter, SignalState::Failed);
    }

    // ---- record_heartbeats ------------------------------------------------------------------

    /// Record the held heartbeats of focus-only rigs (and, opt-in, gate-closed rigs) so their
    /// dark rounds count on-chain. Budgeted: nothing reimburses these fees.
    pub async fn record_pass(self: &Arc<Self>, view: &ChainView) -> anyhow::Result<()> {
        let (Some(board), Some(treasury)) = (view.board, view.treasury) else { return Ok(()) };
        let heartbeats: HashMap<Address, VerifiedHeartbeat> =
            self.store.snapshot().into_iter().map(|h| (h.rig, h)).collect();
        if heartbeats.is_empty() {
            return Ok(());
        }
        let rigs = load_diggable_rigs(&self.rpc, &self.program_id).await?;
        let policy = self.cfg.record.policy(self.cfg.dig.clock_margin_secs);
        let (decisions, skips) = planner::plan_records(&board, &treasury, unix_now(), &rigs, &heartbeats, &policy);
        for (_, s) in &skips {
            if *s != planner::RecordSkip::NotEligible {
                self.metrics.record_skipped.inc(s.label());
            }
        }
        let decisions: Vec<_> = decisions.into_iter().filter(|d| !self.signals.is_pending(&d.rig)).collect();
        if decisions.is_empty() {
            return Ok(());
        }
        let alts = if self.alts_in_use() { self.usable_alts(view.slot) } else { vec![] };
        let mut p = self.base_params(board.round_id, self.cfg.dig.cu_price_micro_lamports);
        p.max_rigs_per_tx = self.cfg.record.max_rigs_per_tx;
        let est = self.cfg.record.cu_estimate();
        let rigs: Vec<RecordRig> = decisions.iter().map(|d| RecordRig { rig: d.rig, heartbeat: d.heartbeat }).collect();
        let (batches, rejected) = tx::pack_records(&p, &est, &rigs, &alts);
        for (r, m) in rejected {
            tracing::warn!(rig = %r.rig, misfit = ?m, "record does not fit a transaction alone");
        }
        for b in batches.into_iter().take(MAX_RECORD_TXS_PER_ROUND) {
            let fee = tx::fee_for(b.rigs.len(), b.cu_limit, p.cu_price_micro_lamports, tx::LAMPORTS_PER_SIGNATURE);
            if !self.record_budget.try_take(fee) {
                self.metrics.record_skipped.add("budget", b.rigs.len() as u64);
                tracing::warn!(rigs = b.rigs.len(), fee, "record budget spent for now");
                break;
            }
            let (bh, lvbh) = self.rpc.get_latest_blockhash().await?;
            let t = tx::sign_record_batch(&p, &est, &b.rigs, &alts, bh, self.key.keypair())?;
            let wire = tx::serialize(&t)?;
            let sig = t.signatures.first().map(ToString::to_string).unwrap_or_default();
            if let Err(e) = self.submitter.send(&wire).await {
                self.record_budget.refund(fee);
                tracing::warn!(error = %e, "sending record_heartbeats failed");
                continue;
            }
            self.metrics.record_txs_sent.inc();
            tracing::info!(%sig, round = board.round_id, rigs = b.rigs.len(), "record_heartbeats sent");
            let this = self.clone();
            tokio::spawn(async move {
                let out = this.submitter.confirm(&sig, &wire, lvbh, ConfirmPolicy::default()).await;
                if let Outcome::Landed { err: None, .. } = out {
                    let max_version = if this.cfg.dig.tx_format == TxFormat::V1 { 1 } else { 0 };
                    if let Some(i) = this.fetch_events(&sig, max_version).await {
                        this.metrics.record_fees_lamports.add(i.fee);
                        for ev in hd::events_from_logs(&this.program_id, &i.logs) {
                            match ev {
                                HdEvent::HeartbeatsRecorded { rig, dark_rounds_added, .. } => {
                                    this.metrics.heartbeats_recorded.inc();
                                    this.metrics.record_dark_rounds.add(dark_rounds_added);
                                    if let Some(r) = b.rigs.iter().find(|r| r.rig == rig) {
                                        this.store.remove_if_counter_at_most(&rig, r.heartbeat.fields.counter);
                                    }
                                }
                                HdEvent::RigSkipped { error, .. } => this.metrics.record_skipped.inc(hd::error_name(error)),
                                _ => {}
                            }
                        }
                    }
                } else {
                    tracing::warn!(%sig, outcome = ?out, "record_heartbeats did not land");
                }
            });
        }
        Ok(())
    }

    // ---- maintenance ---------------------------------------------------------------------

    /// Send a small legacy transaction (lookup-table and checkpoint maintenance).
    async fn send_simple(&self, mut ixs: Vec<Instruction>) -> anyhow::Result<Outcome> {
        ixs.insert(0, tx::set_compute_unit_price(self.cfg.dig.cu_price_micro_lamports));
        if let Some((to, l)) = self.submitter.tip_for(self.nonce.fetch_add(1, Ordering::Relaxed)) {
            ixs.push(tx::system_transfer(&self.cranker(), &to, l));
        }
        let (bh, lvbh) = self.rpc.get_latest_blockhash().await?;
        let t = tx::sign_legacy(&ixs, self.key.keypair(), bh)?;
        let wire = tx::serialize(&t)?;
        let sig = t.signatures.first().map(ToString::to_string).unwrap_or_default();
        self.submitter.send(&wire).await?;
        self.metrics.txs_sent.inc();
        let out = self.submitter.confirm(&sig, &wire, lvbh, ConfirmPolicy::default()).await;
        if matches!(out, Outcome::Landed { err: None, .. }) {
            self.metrics.txs_confirmed.inc();
        }
        Ok(out)
    }

    async fn load_alts(&self) -> anyhow::Result<()> {
        let mut keys: Vec<Address> = self.cfg.alt.tables.iter().filter_map(|s| s.parse().ok()).collect();
        if let Ok(text) = std::fs::read_to_string(self.state_file()) {
            if let Ok(v) = serde_json::from_str::<serde_json::Value>(&text) {
                keys.extend(v["tables"].as_array().into_iter().flatten().filter_map(|s| s.as_str()?.parse::<Address>().ok()));
            }
        }
        keys.sort_by_key(|k| k.to_bytes());
        keys.dedup();
        let accs = self.rpc.get_multiple_accounts(&keys).await?;
        let tables: Vec<LookupTable> = keys
            .into_iter()
            .zip(accs)
            .filter_map(|(k, a)| a.and_then(|a| LookupTable::decode(k, &a.owner, &a.data)))
            .collect();
        *lock(&self.alts) = tables;
        Ok(())
    }

    fn persist_alts(&self, extra: Option<Address>) -> anyhow::Result<()> {
        let mut keys: Vec<String> = lock(&self.alts).iter().map(|t| t.key.to_string()).collect();
        keys.extend(extra.map(|a| a.to_string()));
        std::fs::create_dir_all(&self.cfg.state_dir)?;
        std::fs::write(self.state_file(), serde_json::json!({ "tables": keys }).to_string())?;
        Ok(())
    }

    async fn create_alt(&self) -> anyhow::Result<()> {
        let recent = self.rpc.get_slot("finalized").await?;
        let (ix, table) = alt::create_table_ix(&self.cranker(), &self.cranker(), recent);
        match self.send_simple(vec![ix]).await? {
            Outcome::Landed { err: None, .. } => {
                tracing::info!(%table, "created lookup table");
                self.persist_alts(Some(table))?;
                self.load_alts().await
            }
            other => anyhow::bail!("creating lookup table: {other:?}"),
        }
    }

    /// Rigs worth a lookup-table slot: the operator pays the table rent (890,880 lamports
    /// per rig), so only rigs that are actually heartbeating (a verified heartbeat is held) or
    /// hold a covering lease qualify. Arming throwaway rigs therefore costs an attacker a Rig
    /// account and a live P-256 key per slot, not just a registration.
    fn rigs_for_alt(&self, rigs: &[(Address, Rig)]) -> Vec<(Address, Rig)> {
        let round = self.chain.borrow().board.map_or(0, |b| b.round_id);
        rigs.iter()
            .filter(|(a, r)| self.store.get(a).is_some() || (round > 0 && r.lease_covers(round)))
            .cloned()
            .collect()
    }

    /// Make sure the crank's tables hold the shared accounts and every known rig's four.
    async fn sync_alts(&self) -> anyhow::Result<()> {
        // The poller and new-round maintenance both sync; never interleave (double creates).
        let _guard = self.alt_sync.lock().await;
        let me = self.cranker();
        if lock(&self.alts).iter().all(|t| t.authority != Some(me)) {
            if !self.cfg.alt.auto_create {
                return Ok(());
            }
            self.create_alt().await?;
        }
        if !self.cfg.alt.auto_extend {
            return Ok(());
        }
        let mut wanted = alt::shared_addresses(&self.program_id);
        let known = lock(&self.known_rigs).clone();
        for (a, r) in self.rigs_for_alt(&known) {
            wanted.extend(alt::rig_addresses(&RigAccounts::derive(a, r.authority)));
        }
        let tables = lock(&self.alts).clone();
        let missing = alt::missing(&tables, &wanted);
        if missing.is_empty() {
            return Ok(());
        }
        let own: Vec<LookupTable> = tables.into_iter().filter(|t| t.authority == Some(me)).collect();
        let (plan, overflow) = alt::plan_extends(&own, missing);
        for (table, chunk) in plan.into_iter().take(MAX_ALT_TXS_PER_ROUND) {
            let n = chunk.len();
            match self.send_simple(vec![alt::extend_table_ix(&table, &me, &me, &chunk)]).await? {
                Outcome::Landed { err: None, .. } => tracing::info!(%table, added = n, "extended lookup table"),
                other => tracing::warn!(%table, outcome = ?other, "extend failed"),
            }
        }
        let owned = lock(&self.alts).iter().filter(|t| t.authority == Some(me)).count();
        if !overflow.is_empty() && self.cfg.alt.auto_create {
            if owned < self.cfg.alt.max_tables {
                self.create_alt().await?;
            } else {
                tracing::warn!(owned, max = self.cfg.alt.max_tables, "lookup tables full; new rigs use static keys");
            }
        }
        self.load_alts().await
    }

    /// Checkpoint miners of registered rigs whose last round is old but not yet in ORE's
    /// 12-hour bot window, so nobody forfeits rewards and the Executor never has to refill
    /// a Miner's CHECKPOINT_FEE (docs/ORE.md sections 6 and 7, F8).
    async fn checkpoint_sweep(&self, board_round: u64) -> anyhow::Result<()> {
        let rigs: Vec<(Address, Rig)> = self
            .rpc
            .get_program_accounts(
                &self.program_id,
                &[Filter::DataSize(hd::RIG_LEN as u64), Filter::Memcmp { offset: 0, bytes: vec![hd::TAG_RIG, hd::ACCOUNT_VERSION] }],
            )
            .await?
            .into_iter()
            .filter_map(|(a, acc)| Rig::decode(&self.program_id, &acc.owner, &acc.data).ok().map(|r| (a, r)))
            .collect();
        let miners: Vec<Address> = rigs.iter().map(|(_, r)| ore::miner_pda(&r.authority)).collect();
        let accs = self.rpc.get_multiple_accounts(&miners).await?;
        let after = self.cfg.dig.checkpoint_sweep_after_rounds;
        let todo: Vec<(Address, u64)> = rigs
            .iter()
            .zip(accs)
            .filter_map(|((_, r), a)| {
                let a = a?;
                let m = Miner::decode(&a.owner, &a.data).ok()?;
                (m.authority == r.authority
                    && m.needs_checkpoint()
                    && m.round_id < board_round
                    && board_round - m.round_id >= after)
                    .then_some((r.authority, m.round_id))
            })
            .collect();
        for chunk in todo.chunks(self.cfg.dig.checkpoints_per_tx.max(1)).take(MAX_SWEEP_TXS) {
            let ixs: Vec<Instruction> = chunk.iter().map(|(auth, rid)| ore::checkpoint_ix(&self.cranker(), auth, *rid)).collect();
            let n = ixs.len();
            let out = self.send_simple(ixs).await?;
            self.metrics.checkpoints_sent.add(n as u64);
            tracing::info!(miners = n, outcome = ?out, "checkpoint sweep");
        }
        Ok(())
    }
}
