//! The crank loop.
//!
//! ```text
//! chain view ──► (new round) maintenance: prune, lookup-table sync, checkpoint sweep
//!            ├─► (slots_left <= deploy_margin) dig pass, unless the crank is idle (`crate::idle`):
//!            │     getProgramAccounts(Armed, Down, Cooling) → keep rigs with a lease or a held heartbeat
//!            │     → getMultipleAccounts(Automations, Miners, Executor) → planner
//!            │     → pack → [simulate → size CU | bisect on failure] → sign → send
//!            │     → ledger (rig, round) pending → confirm task → events → ledger / metrics
//!            ├─► (delay after the round starts) record pass: focus-only rigs → record_heartbeats
//!            └─► every end_shift.poll_secs: permissionless end_shift for stale shifts
//! intake ──► SignalHub ──► signal lander: phone-signed BREAK / FREEZE → break_shift / freeze_rig
//! ```
//!
//! Every pass that plans re-reads the chain, so a retry never reuses stale instructions (the
//! fork suite shows a stale checkpoint aborting a whole batch). A pass reads nothing only
//! while no heartbeat is held, no known rig holds a lease and nothing happened in the last few
//! rounds; one pass in every `dig.idle_full_read_rounds` rounds still reads.
//!
//! Lookup tables lock rent, so their maintenance never guesses: nothing is created before
//! the tables were read, a create is sent only when the fee payer can pay for it and after
//! its address is in the state file, a transaction that did not land is retried after a
//! growing wait, and the tables are read again before the next decision.
//!
//! INTERFACE v1.2 adds two duties, each in its own file: the Stack loop
//! ([`stack_loop`](self): discovery, a check-in per seat per round, settles) runs in the main
//! loop **before** the dig and record passes and owns the seated rigs' fresh heartbeats for
//! the round; the cleanup loop (`cleanup_loop`) forfeits broken Focus Bonds and refunds
//! expired gifts on a timer.

mod cleanup_loop;
mod stack_loop;

pub use cleanup_loop::{plan_forfeits, plan_refunds, CleanupAction};

use std::collections::{HashMap, HashSet, VecDeque};
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, AtomicU64, AtomicUsize, Ordering};
use std::sync::{Arc, Mutex, RwLock};
use std::time::{Duration, Instant};

use solana_address::Address;
use solana_instruction::Instruction;
use solana_message::AddressLookupTableAccount;
use solana_signer::Signer;
use tokio::sync::{mpsc, watch, Notify};

use crate::account::RawAccount;
use crate::alt::{self, LookupTable};
use crate::breaker::Breaker;
use crate::chain::{ChainView, ProgramEvents};
use crate::config::Config;
use crate::hd::{self, HdConfig, HdEvent, Rig, RigAccounts, RigState};
use crate::heartbeat::{HeartbeatStore, VerifiedHeartbeat, VerifiedSignal};
use crate::idle::{IdleTracker, Pass};
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
/// Room for the fee of one lookup-table transaction (5,000 lamports per signature plus the
/// priority fee). Before one is sent the fee payer must hold the new rent, this margin (twice
/// for a create: the extend that fills the table follows it), and the rent-exempt minimum of
/// its own account, below which the chain refuses the transaction outright.
pub const ALT_FEE_MARGIN_LAMPORTS: u64 = 50_000;
/// Checkpoint sweep transactions per sweep at most.
pub const MAX_SWEEP_TXS: usize = 5;
/// Record transactions per round at most.
pub const MAX_RECORD_TXS_PER_ROUND: usize = 8;
/// Do not retry `end_shift` for the same (rig, shift) within this long.
pub const END_SHIFT_RETRY_AFTER: Duration = Duration::from_secs(600);
/// ShiftLog rent at the default rent parameters, used if RPC cannot tell.
pub const SHIFT_LOG_RENT_FALLBACK: u64 = 1_781_760;
/// A graceful shutdown waits this long for the loop's last Stack pass before it concludes
/// that nothing is in flight (the loop wakes within 400 ms; the pass is two RPC reads).
pub const DRAIN_FLUSH_WAIT: Duration = Duration::from_millis(2_500);

fn lock<T>(m: &Mutex<T>) -> std::sync::MutexGuard<'_, T> {
    match m.lock() {
        Ok(g) => g,
        Err(p) => p.into_inner(),
    }
}

/// Transactions sent and not yet confirmed or given up on, and messages accepted and not yet
/// landed: what a graceful shutdown waits for.
#[derive(Debug, Default)]
pub struct InFlight {
    n: AtomicUsize,
}

impl InFlight {
    /// Count one more until the guard is dropped.
    pub fn guard(self: &Arc<Self>, metrics: &Arc<Metrics>) -> InFlightGuard {
        let n = self.n.fetch_add(1, Ordering::SeqCst).saturating_add(1);
        metrics.in_flight.set_u64(n as u64);
        InFlightGuard { inner: self.clone(), metrics: metrics.clone() }
    }

    /// How many are in flight.
    pub fn count(&self) -> usize {
        self.n.load(Ordering::SeqCst)
    }
}

/// See [`InFlight::guard`].
#[derive(Debug)]
pub struct InFlightGuard {
    inner: Arc<InFlight>,
    metrics: Arc<Metrics>,
}

impl Drop for InFlightGuard {
    fn drop(&mut self) {
        let before = self.inner.n.fetch_sub(1, Ordering::SeqCst);
        self.metrics.in_flight.set_u64(before.saturating_sub(1) as u64);
    }
}

/// Wakes the Stack loop when something about a seated rig changed: its phone's heartbeat for
/// the round arrived, its BREAK landed, or the program logged an event about it. Shared with
/// the intake (see [`crate::mirror::NudgeMirror`]).
#[derive(Clone, Debug, Default)]
pub struct StackNudge {
    /// Notified to run a Stack pass now.
    pub notify: Arc<Notify>,
    /// Set by [`Self::wake`], cleared by the pass it asked for.
    pub pending: Arc<AtomicBool>,
    /// Rigs seated at an open table (kept current by the Stack loop's discovery).
    pub rigs: Arc<RwLock<HashSet<Address>>>,
}

impl StackNudge {
    /// Ask for a Stack pass now.
    pub fn wake(&self) {
        self.pending.store(true, Ordering::SeqCst);
        self.notify.notify_one();
    }

    /// Is `rig` seated at an open table?
    pub fn is_seated(&self, rig: &Address) -> bool {
        self.rigs.read().map(|r| r.contains(rig)).unwrap_or(false)
    }

    /// A verified heartbeat for `rig` was stored: wake the Stack loop if the rig is seated.
    pub fn heartbeat(&self, rig: &Address) {
        if self.is_seated(rig) {
            self.wake();
        }
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
        // The cluster's clock (what the program compares caps and plan windows with), or the
        // system clock until the Clock sysvar has been read.
        now_ts: view.unix_now(),
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

/// What the crank knows about its lookup tables besides the decoded accounts (behind a mutex
/// that is never held across an await).
#[derive(Default)]
struct AltRuntime {
    /// The state file was read (once per process).
    file_read: bool,
    /// The tables were read from the chain since the last lookup-table transaction was sent.
    loaded: bool,
    /// What the state file holds: the tables, the creates that may still land, how fresh a
    /// read of them must be, the backoff.
    saved: alt::TableState,
    /// What the fee payer needed for the last lookup-table transaction and did not hold.
    short_of: Option<u64>,
    /// The state file could not be written (one error line until a write works again).
    unsaved: bool,
    /// `alt.max_tables` kept the crank from creating a table while one it created is not on
    /// chain (one warning per process).
    limit_logged: bool,
}

/// Unix seconds, for the lookup-table backoff.
pub type UnixClock = Arc<dyn Fn() -> i64 + Send + Sync>;

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
    alt_state: Mutex<AltRuntime>,
    fee_payer_rent: AtomicU64,
    clock: UnixClock,
    hd_config: Mutex<Option<HdConfig>>,
    known_rigs: Mutex<Vec<(Address, Rig)>>,
    rig_seed: Box<dyn Fn(Address, Rig) + Send + Sync>,
    /// Which dig passes may skip their chain reads.
    idle: Mutex<IdleTracker>,
    /// The last dig pass found the crank idle (one log line per change, not per pass).
    idle_logged: AtomicBool,
    nonce: AtomicU64,
    alt_sync: tokio::sync::Mutex<()>,
    record_budget: FeeBudget,
    end_shift_budget: FeeBudget,
    end_shift_tried: Mutex<HashMap<(Address, u64), Instant>>,
    shift_log_rent: AtomicU64,
    // ---- INTERFACE v1.2: Stack and the cleanups ----
    stack: Mutex<stack_loop::StackRuntime>,
    stack_budget: FeeBudget,
    nudge: StackNudge,
    /// Rigs whose fresh heartbeat is in an unconfirmed `record_heartbeats` transaction.
    record_in_flight: Mutex<HashSet<Address>>,
    bury_ready: AtomicBool,
    /// One Bury setup at a time (a settle and a forfeit may both need it).
    bury_sync: tokio::sync::Mutex<()>,
    cleanup_budget: FeeBudget,
    cleanup_tried: Mutex<HashMap<Address, Instant>>,
    cleanup_notify: Arc<Notify>,
    events: Mutex<Option<mpsc::Receiver<ProgramEvents>>>,
    in_flight: Arc<InFlight>,
    draining: AtomicBool,
    /// The main loop ran its final Stack pass after the drain started.
    drain_flushed: AtomicBool,
}

/// The optional parts a [`Crank`] is wired with (the binary passes all of them; tests may
/// pass the defaults).
#[derive(Default)]
pub struct CrankWiring {
    /// Shared with the intake: wakes the Stack loop when a seated rig's heartbeat arrives.
    pub nudge: StackNudge,
    /// The program's events from the chain stream (a hint for the Stack and cleanup loops).
    pub events: Option<mpsc::Receiver<ProgramEvents>>,
    /// Shared in-flight counter (the shutdown waits for it).
    pub in_flight: Arc<InFlight>,
    /// The clock the lookup-table backoff reads (`None`: the system clock). Tests move it.
    pub clock: Option<UnixClock>,
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
        wiring: CrankWiring,
    ) -> Arc<Self> {
        let program_id = cfg.program_id();
        Arc::new(Crank {
            record_budget: FeeBudget::new(cfg.record.max_lamports_per_hour, Duration::from_secs(3600)),
            end_shift_budget: FeeBudget::new(cfg.end_shift.max_lamports_per_day, Duration::from_secs(86_400)),
            stack_budget: FeeBudget::new(cfg.stack.max_lamports_per_hour, Duration::from_secs(3600)),
            cleanup_budget: FeeBudget::new(cfg.cleanup.max_lamports_per_day, Duration::from_secs(86_400)),
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
            alt_state: Mutex::new(AltRuntime::default()),
            fee_payer_rent: AtomicU64::new(0),
            clock: wiring.clock.unwrap_or_else(|| Arc::new(crate::chain::system_unix_now)),
            hd_config: Mutex::new(None),
            known_rigs: Mutex::new(Vec::new()),
            rig_seed,
            idle: Mutex::new(IdleTracker::default()),
            idle_logged: AtomicBool::new(false),
            nonce: AtomicU64::new(0),
            alt_sync: tokio::sync::Mutex::new(()),
            end_shift_tried: Mutex::new(HashMap::new()),
            shift_log_rent: AtomicU64::new(0),
            stack: Mutex::new(stack_loop::StackRuntime::default()),
            nudge: wiring.nudge,
            record_in_flight: Mutex::new(HashSet::new()),
            bury_ready: AtomicBool::new(false),
            bury_sync: tokio::sync::Mutex::new(()),
            cleanup_tried: Mutex::new(HashMap::new()),
            cleanup_notify: Arc::new(Notify::new()),
            events: Mutex::new(wiring.events),
            in_flight: wiring.in_flight,
            draining: AtomicBool::new(false),
            drain_flushed: AtomicBool::new(false),
        })
    }

    /// The cluster's unix time as the chain watcher last read it (the system clock until then).
    fn now_ts(&self) -> i64 {
        self.chain.borrow().unix_now()
    }

    /// The process is shutting down: no new pass starts.
    pub fn is_draining(&self) -> bool {
        self.draining.load(Ordering::SeqCst)
    }

    /// Stop starting new work, then wait (at most `grace`) for what is in flight: transactions
    /// sent and unconfirmed, and phone-signed BREAK / FREEZE messages that were acknowledged
    /// and are not landed yet. Returns what was still in flight when the wait ended.
    ///
    /// One thing is still started: the running loop makes a last Stack pass that sends the
    /// check-in of every seat whose heartbeat for this round is already held, without waiting
    /// for the rest of its table (Stack is fail-closed: a heartbeat that dies with this
    /// process is a round the seat cannot get back). The wait gives that pass
    /// [`DRAIN_FLUSH_WAIT`] to happen.
    pub async fn drain(&self, grace: Duration) -> usize {
        self.draining.store(true, Ordering::SeqCst);
        self.metrics.shutting_down.set(1);
        self.nudge.wake();
        let started = Instant::now();
        let deadline = started + grace;
        loop {
            let left = self.in_flight.count().saturating_add(self.signals.pending_count());
            let flushed = !self.cfg.stack.enabled || self.drain_flushed.load(Ordering::SeqCst) || started.elapsed() >= DRAIN_FLUSH_WAIT;
            if (left == 0 && flushed) || Instant::now() >= deadline {
                return left;
            }
            tokio::time::sleep(Duration::from_millis(50)).await;
        }
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
        if self.alts_in_use() {
            if let Err(e) = self.load_alts().await {
                tracing::warn!(error = %e, "could not read the lookup tables; none is created or extended until they are read");
            }
        }
        self.load_stack_spend();
        tokio::spawn(self.clone().poll_loop());
        if let Some(rx) = self.signals.take_receiver() {
            tokio::spawn(self.clone().signal_loop(rx));
        }
        if self.cfg.end_shift.enabled {
            tokio::spawn(self.clone().end_shift_loop());
        }
        if self.cfg.cleanup.enabled {
            tokio::spawn(self.clone().cleanup_loop());
        }
        if let Some(rx) = lock(&self.events).take() {
            tokio::spawn(self.clone().events_loop(rx));
        }
        let mut chain = self.chain.clone();
        let mut current_round = 0u64;
        let mut round_seen = Instant::now();
        let mut recorded_round = 0u64;
        let mut last_pass_slot: Option<u64> = None;
        loop {
            // Wake on a chain update, on a Stack nudge (a seated rig's heartbeat arrived, a
            // check-in landed), or after 400 ms.
            tokio::select! {
                r = tokio::time::timeout(Duration::from_millis(400), chain.changed()) => {
                    if let Ok(Err(_)) = r {
                        anyhow::bail!("chain watcher stopped");
                    }
                }
                _ = self.nudge.notify.notified() => {}
            }
            let view = chain.borrow().clone();
            if self.is_draining() {
                // Shutting down: no dig, no record, no settle. Only the check-ins of seats whose
                // heartbeat is already held go out (see `drain`); what is in flight finishes on
                // its own.
                if let Some(board) = view.board.filter(|_| view.ready() && self.cfg.stack.enabled && !self.breaker.is_tripped()) {
                    if !self.drain_flushed.load(Ordering::SeqCst) || self.stack_due(board.round_id) {
                        if let Err(e) = self.stack_tick(&view).await {
                            tracing::warn!(error = %e, round = board.round_id, "the last Stack pass failed");
                        }
                    }
                }
                self.drain_flushed.store(true, Ordering::SeqCst);
                continue;
            }
            let Some(board) = view.board.filter(|_| view.ready()) else { continue };
            if board.round_id != current_round {
                current_round = board.round_id;
                round_seen = Instant::now();
                last_pass_slot = None;
                let this = self.clone();
                tokio::spawn(async move { this.on_new_round(board.round_id).await });
            }
            // On every turn, so a heartbeat that a check-in or a record applies before the dig
            // window opens still marks its round as active.
            self.note_heartbeats(current_round);
            if self.breaker.is_tripped() {
                continue;
            }
            // Stack first: a seat's check-in must land inside its round (fail-closed), and the
            // pass decides which rigs' fresh heartbeats the record and dig passes leave alone.
            if self.stack_due(current_round) {
                if let Err(e) = self.stack_tick(&view).await {
                    tracing::warn!(error = %e, round = current_round, "Stack pass failed");
                }
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
            if let Err(e) = self.dig_tick(&view).await {
                tracing::warn!(error = %e, round = board.round_id, "dig pass failed");
            }
        }
    }

    /// Tell the idle tracker what the heartbeat store holds during `round`.
    fn note_heartbeats(&self, round: u64) {
        lock(&self.idle).note_store(round, self.store.stored_total(), !self.store.is_empty());
    }

    /// What the dig pass of `round` does: read the chain, or nothing while the crank is idle
    /// (no heartbeat held, no known rig with a lease covering the round, nothing held or
    /// planned in the last few rounds, and the periodic full read not due; see [`crate::idle`]).
    fn pass_for(&self, round: u64) -> Pass {
        let held = !self.store.is_empty();
        let lease = lock(&self.known_rigs).iter().any(|(_, r)| r.lease_covers(round));
        let mut idle = lock(&self.idle);
        idle.note_store(round, self.store.stored_total(), held);
        idle.decide(round, held, lease, self.cfg.dig.idle_full_read_rounds)
    }

    /// The dig pass of this loop turn: [`Self::dig_pass`], or nothing at all while the crank
    /// is idle. Returns what was decided (also counted in `hd_crank_dig_passes_total`).
    pub async fn dig_tick(self: &Arc<Self>, view: &ChainView) -> anyhow::Result<Pass> {
        let board = view.board.ok_or_else(|| anyhow::anyhow!("no Board"))?;
        let pass = self.pass_for(board.round_id);
        self.metrics.dig_passes.inc(pass.label());
        // One line when the crank goes idle and one when it wakes, not one per pass.
        let idle_now = matches!(pass, Pass::Skip | Pass::Periodic);
        if self.idle_logged.swap(idle_now, Ordering::Relaxed) != idle_now {
            if idle_now {
                tracing::info!(
                    round = board.round_id,
                    full_read_every_rounds = self.cfg.dig.idle_full_read_rounds,
                    "idle: no heartbeat is held and no known rig has a lease, so dig passes stop reading the chain (one full read every few rounds stays)"
                );
            } else {
                tracing::info!(round = board.round_id, why = pass.label(), "awake: dig passes read the chain again");
            }
        }
        if pass.reads() {
            self.dig_pass(view).await?;
        }
        Ok(pass)
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
            self.poll_once().await;
            tokio::time::sleep(every).await;
        }
    }

    /// One turn of the poller: the heads_down Config, ORE's upgrade pin, the Executor's and
    /// the fee payer's balance, pruning, and the rig scan that feeds the lookup tables.
    pub async fn poll_once(&self) {
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
            self.note_fee_payer_balance(b);
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
        if quiet && self.alts_in_use() && !self.breaker.is_tripped() && self.rig_scan_due(board.map(|b| b.round_id)) {
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
    }

    /// Is the poller's rig scan worth its three `getProgramAccounts`? It only feeds the
    /// lookup-table sync, and a rig gets a slot in a table only with a held heartbeat or a
    /// covering lease. So it is skipped while that sync can send nothing (the fee payer is
    /// short, or the wait after a failed transaction runs) and while the crank is idle, as a
    /// dig pass would be (see [`crate::idle`]). The sync of every new round still runs.
    fn rig_scan_due(&self, round: Option<u64>) -> bool {
        if self.alt_short() || !self.alt_retry_due() {
            return false;
        }
        round.is_none_or(|r| !matches!(self.pass_for(r), Pass::Skip | Pass::Periodic))
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
        let mut heartbeats: HashMap<Address, VerifiedHeartbeat> =
            self.store.snapshot().into_iter().map(|h| (h.rig, h)).collect();
        // A seated rig's fresh heartbeat belongs to its Stack check-in this round (the check-in
        // applies it and counts the round in one instruction); the dig then reuses the lease it
        // leaves. Two transactions racing for one counter would cost the seat its round.
        let stack_owned: Vec<Address> = heartbeats.keys().filter(|r| self.stack_owns(r, board.round_id)).copied().collect();
        for r in &stack_owned {
            heartbeats.remove(r);
        }
        let fetched = fetch_for_plan(&self.rpc, &self.program_id, board.round_id, &heartbeats, &self.breaker).await?;
        if self.breaker.is_tripped() {
            return Ok(());
        }
        // What a pass leaves behind for the others (the rig list for the lookup tables and the
        // idle check, the intake's rig cache, the idle tracker) is kept even when the pass
        // stops at a missing Config: with the program not initialized there is nothing to
        // dig, and the passes after this one may then skip their reads.
        self.metrics.rigs_seen.set_u64(fetched.all_rigs.len() as u64);
        for (a, r) in &fetched.all_rigs {
            (self.rig_seed)(*a, r.clone());
        }
        *lock(&self.known_rigs) = fetched.all_rigs.clone();
        {
            let mut idle = lock(&self.idle);
            idle.note_full_read(board.round_id);
            if !fetched.rigs.is_empty() {
                idle.note_active(board.round_id);
            }
        }
        let config = fetched.config.ok_or_else(|| anyhow::anyhow!("heads_down Config unavailable"))?;
        *lock(&self.hd_config) = Some(config);
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
        // Seated rigs that are waiting for their check-in (no covering lease yet): the next dig
        // pass of this round reuses the lease.
        for r in stack_owned {
            if !plan.digs.iter().any(|d| d.dig.accounts.rig == r) && !plan.skips.iter().any(|(a, _)| *a == r) {
                plan.skips.push((r, Skip::StackCheckinPending));
            }
        }
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
            let guard = self.in_flight.guard(&self.metrics);
            tokio::spawn(async move {
                let _guard = guard;
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
                                    // The dig applied this round's heartbeat: a seat of this rig
                                    // can now be counted in observe mode.
                                    self.nudge.heartbeat(&rig);
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
            // Counted as in flight until it lands or is given up on: a graceful shutdown waits
            // for a BREAK / FREEZE the phone was told is accepted.
            let guard = self.in_flight.guard(&self.metrics);
            tokio::spawn(async move {
                let _guard = guard;
                this.land_signal(s).await
            });
        }
    }

    /// The program's events from the chain stream: hints for the Stack and cleanup loops.
    async fn events_loop(self: Arc<Self>, mut rx: mpsc::Receiver<ProgramEvents>) {
        while let Some(ev) = rx.recv().await {
            self.on_program_events(&ev);
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
            // Streak protection, checked again at the moment of sending (the intake checked it
            // when the phone's message arrived): a BREAK is never put on-chain once the rig's
            // plan window has ended, or is about to within the landing margin. It would make
            // end_shift seal a completed night as a break. The cluster's own clock decides.
            if s.kind == hd::SignalKind::Break && c.streak_protection {
                let now = match self.rpc.get_cluster_unix_timestamp().await {
                    Ok(t) => t,
                    Err(_) => self.now_ts(),
                };
                if !c.window_rule().allows(s.kind, s.plan_window_end_ts, now) {
                    tracing::info!(rig = %s.rig, counter = s.counter, window_end = s.plan_window_end_ts, now, "BREAK not landed: the plan window has ended (streak protection)");
                    self.metrics.signals_failed.inc("window_ended");
                    self.signals.mark(&s.rig, s.counter, SignalState::Refused);
                    self.signals.refund(c.est_fee());
                    return;
                }
            }
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
                    // A seated rig just broke: its table must see it inside this round (a break
                    // after the seat's last check-in of end_round is otherwise never recorded).
                    self.nudge.heartbeat(&s.rig);
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
        let (decisions, skips) = planner::plan_records(&board, &treasury, view.unix_now(), &rigs, &heartbeats, &policy);
        for (_, s) in &skips {
            if *s != planner::RecordSkip::NotEligible {
                self.metrics.record_skipped.inc(s.label());
            }
        }
        // A seated rig's heartbeat is recorded by its Stack check-in (verify mode applies it
        // exactly as record_heartbeats does, and counts the round): never by both.
        let (decisions, stack_owned): (Vec<_>, Vec<_>) = decisions.into_iter().partition(|d| !self.stack_owns(&d.rig, board.round_id));
        self.metrics.record_skipped.add("stack_owned", stack_owned.len() as u64);
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
            // While this is unconfirmed the Stack loop does not put the same heartbeats in a
            // check-in (should one of these rigs be seated without being owned by it).
            lock(&self.record_in_flight).extend(b.rigs.iter().map(|r| r.rig));
            let this = self.clone();
            let guard = self.in_flight.guard(&self.metrics);
            tokio::spawn(async move {
                let _guard = guard;
                let out = this.submitter.confirm(&sig, &wire, lvbh, ConfirmPolicy::default()).await;
                {
                    let mut busy = lock(&this.record_in_flight);
                    for r in &b.rigs {
                        busy.remove(&r.rig);
                    }
                }
                if b.rigs.iter().any(|r| this.nudge.is_seated(&r.rig)) {
                    this.stack_nudge();
                }
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

    // ---- permissionless end_shift -----------------------------------------------------------

    async fn end_shift_loop(self: Arc<Self>) {
        let every = Duration::from_secs(self.cfg.end_shift.poll_secs.max(1));
        loop {
            tokio::time::sleep(every).await;
            if self.breaker.is_tripped() || self.is_draining() {
                continue;
            }
            if let Err(e) = self.end_shift_sweep().await {
                tracing::warn!(error = %e, "end_shift sweep failed");
            }
        }
    }

    async fn shift_log_rent(&self) -> u64 {
        let cached = self.shift_log_rent.load(Ordering::Relaxed);
        if cached != 0 {
            return cached;
        }
        let rent = self.rpc.get_minimum_balance_for_rent_exemption(hd::SHIFT_LOG_LEN).await.unwrap_or(SHIFT_LOG_RENT_FALLBACK);
        self.shift_log_rent.store(rent, Ordering::Relaxed);
        rent
    }

    /// Seal shifts whose window has passed and whose lease has been expired for more than the
    /// program's 3-round grace (INTERFACE §5 `end_shift`, §12.13: anyone may then, and pays
    /// the ShiftLog rent). At most `max_per_pass` per pass, within the
    /// daily lamport budget, oldest window first; a failing (rig, shift) is left alone for 10 min.
    pub async fn end_shift_sweep(&self) -> anyhow::Result<usize> {
        let c = &self.cfg.end_shift;
        let Some(board) = self.chain.borrow().board else { return Ok(0) };
        let now = self.now_ts();
        let mut due: Vec<(Address, Rig)> = self
            .rpc
            .get_program_accounts(&self.program_id, &rpc::open_shift_filters())
            .await?
            .into_iter()
            .filter_map(|(a, acc)| Rig::decode(&self.program_id, &acc.owner, &acc.data).ok().map(|r| (a, r)))
            .filter(|(_, r)| {
                r.shift_open
                    && now > r.plan_window_end_ts.saturating_add(c.grace_secs)
                    && r.lease_to_round.saturating_add(hd::PERMISSIONLESS_END_GRACE_ROUNDS) < board.round_id
            })
            .collect();
        {
            let mut tried = lock(&self.end_shift_tried);
            tried.retain(|_, at| at.elapsed() < END_SHIFT_RETRY_AFTER);
            due.retain(|(a, r)| !tried.contains_key(&(*a, r.shift_id)));
        }
        due.sort_by_key(|(_, r)| r.plan_window_end_ts);
        let rent = self.shift_log_rent().await;
        let price = self.cfg.dig.cu_price_micro_lamports;
        let mut ended = 0usize;
        for (rig, r) in due.into_iter().take(c.max_per_pass) {
            let cost = rent.saturating_add(tx::fee_for(0, c.cu_limit, price, tx::LAMPORTS_PER_SIGNATURE));
            if !self.end_shift_budget.try_take(cost) {
                self.metrics.end_shift_failed.inc("budget");
                tracing::warn!(%rig, cost, "end_shift budget spent for today");
                break;
            }
            lock(&self.end_shift_tried).insert((rig, r.shift_id), Instant::now());
            let ixs = tx::end_shift_instructions(&self.program_id, &self.cranker(), &rig, r.shift_id, c.cu_limit, price);
            match self.send_signed(&ixs).await {
                Ok((sig, Outcome::Landed { err: None, slot })) => {
                    ended += 1;
                    self.metrics.shifts_ended.inc();
                    self.metrics.end_shift_lamports.add(cost);
                    tracing::info!(%sig, slot, %rig, shift = r.shift_id, rent, "ended a stale shift (permissionless)");
                }
                Ok((sig, other)) => {
                    self.end_shift_budget.refund(rent);
                    self.metrics.end_shift_failed.inc("onchain");
                    tracing::warn!(%sig, %rig, shift = r.shift_id, outcome = ?other, "end_shift did not land");
                }
                Err(e) => {
                    self.end_shift_budget.refund(cost);
                    self.metrics.end_shift_failed.inc("send");
                    tracing::warn!(%rig, error = %e, "end_shift failed");
                }
            }
        }
        Ok(ended)
    }

    /// Sign `ixs` (legacy, crank pays), simulate, send, confirm.
    async fn send_signed(&self, ixs: &[Instruction]) -> anyhow::Result<(String, Outcome)> {
        let _guard = self.in_flight.guard(&self.metrics);
        let (bh, lvbh) = self.rpc.get_latest_blockhash().await?;
        let t = tx::sign_legacy(ixs, self.key.keypair(), bh)?;
        let wire = tx::serialize(&t)?;
        let sig = t.signatures.first().map(ToString::to_string).unwrap_or_default();
        let sim = self.rpc.simulate_transaction(&wire).await?;
        if let Some(err) = sim.err {
            let tail: Vec<&String> = sim.logs.iter().rev().take(4).collect();
            anyhow::bail!("simulation failed: {err} {tail:?}");
        }
        self.submitter.send(&wire).await?;
        self.metrics.txs_sent.inc();
        let out = self.submitter.confirm(&sig, &wire, lvbh, ConfirmPolicy::default()).await;
        if matches!(out, Outcome::Landed { err: None, .. }) {
            self.metrics.txs_confirmed.inc();
        }
        Ok((sig, out))
    }

    // ---- maintenance ---------------------------------------------------------------------

    /// Sign a small legacy transaction (lookup-table and checkpoint maintenance): its wire
    /// bytes, its signature and the last block height it can land in.
    async fn sign_simple(&self, mut ixs: Vec<Instruction>) -> anyhow::Result<(Vec<u8>, String, u64)> {
        ixs.insert(0, tx::set_compute_unit_price(self.cfg.dig.cu_price_micro_lamports));
        if let Some((to, l)) = self.submitter.tip_for(self.nonce.fetch_add(1, Ordering::Relaxed)) {
            ixs.push(tx::system_transfer(&self.cranker(), &to, l));
        }
        let (bh, lvbh) = self.rpc.get_latest_blockhash().await?;
        let t = tx::sign_legacy(&ixs, self.key.keypair(), bh)?;
        let wire = tx::serialize(&t)?;
        let sig = t.signatures.first().map(ToString::to_string).unwrap_or_default();
        Ok((wire, sig, lvbh))
    }

    /// Send what [`Self::sign_simple`] signed and wait for its outcome.
    async fn send_wire(&self, wire: &[u8], sig: &str, lvbh: u64) -> anyhow::Result<Outcome> {
        self.submitter.send(wire).await?;
        self.metrics.txs_sent.inc();
        let out = self.submitter.confirm(sig, wire, lvbh, ConfirmPolicy::default()).await;
        if matches!(out, Outcome::Landed { err: None, .. }) {
            self.metrics.txs_confirmed.inc();
        }
        Ok(out)
    }

    /// Send a small legacy transaction (lookup-table and checkpoint maintenance).
    async fn send_simple(&self, ixs: Vec<Instruction>) -> anyhow::Result<Outcome> {
        let (wire, sig, lvbh) = self.sign_simple(ixs).await?;
        self.send_wire(&wire, &sig, lvbh).await
    }

    // ---- lookup tables ---------------------------------------------------------------------

    /// The crank's lookup tables as it last read them.
    pub fn lookup_tables(&self) -> Vec<LookupTable> {
        lock(&self.alts).clone()
    }

    /// The fee payer's balance as just read (the poller reads it every `dig.config_poll_secs`).
    /// Lookup-table work that stopped for lack of funds starts again once it is enough.
    pub fn note_fee_payer_balance(&self, lamports: u64) {
        self.metrics.cranker_lamports.set_u64(lamports);
    }

    fn now_unix(&self) -> i64 {
        (self.clock)()
    }

    fn owned_tables(&self) -> usize {
        let me = self.cranker();
        lock(&self.alts).iter().filter(|t| t.authority == Some(me)).count()
    }

    /// The tables that count against `alt.max_tables`: those the chain shows with this crank
    /// as their authority, and those the state file says this crank created, whether or not
    /// the last read shows them. A read is never taken as proof that a table is gone: each
    /// one locks rent, and only the operator knows that one was closed.
    fn tables_counted(&self) -> usize {
        let me = self.cranker();
        let mut keys: HashSet<Address> = lock(&self.alts).iter().filter(|t| t.authority == Some(me)).map(|t| t.key).collect();
        keys.extend(lock(&self.alt_state).saved.tables.iter().copied());
        keys.len()
    }

    /// Room for one more table: no create may still land, and fewer than `alt.max_tables`
    /// count ([`Self::tables_counted`]).
    fn table_slot_free(&self) -> bool {
        lock(&self.alt_state).saved.pending.is_empty() && self.tables_counted() < self.cfg.alt.max_tables
    }

    /// The crank owns no table and may not create one. When that is because the state file
    /// names tables the chain does not show under this crank's key, say so once: they were
    /// closed by hand, the key was changed, or the read is wrong, and the crank cannot tell
    /// which.
    fn note_table_limit(&self) {
        let mut st = lock(&self.alt_state);
        if st.saved.tables.is_empty() || !st.saved.pending.is_empty() || std::mem::replace(&mut st.limit_logged, true) {
            return;
        }
        let tables: Vec<String> = st.saved.tables.iter().map(ToString::to_string).collect();
        self.metrics.lookup_tables.inc("limit");
        tracing::warn!(
            alert = "lookup_table_not_on_chain",
            file = %self.state_file().display(),
            tables = %tables.join(","),
            max_tables = self.cfg.alt.max_tables,
            "the state file names lookup tables this crank created that the chain does not show with this crank as their authority, \
             and alt.max_tables allows no other: no table is created. Digs go on without a table of the crank's own (fewer rigs fit a transaction). \
             If they were closed by hand, or the fee payer's key was changed, remove them from the state file (or remove the file) and restart"
        );
    }

    /// Read the state file, once: the tables this crank created, the creates that may still
    /// land, the backoff. No file is a first start. A file that is there and cannot be read
    /// or understood is an error: read as empty, it would make the crank forget a table and
    /// create another.
    fn read_alt_state(&self) -> anyhow::Result<()> {
        let mut st = lock(&self.alt_state);
        if st.file_read {
            return Ok(());
        }
        let path = self.state_file();
        let saved = match std::fs::read_to_string(&path) {
            Ok(text) => alt::TableState::from_json(&text).map_err(|e| {
                anyhow::anyhow!("state file {} is {e}: fix or remove it (no lookup table is created or extended until then)", path.display())
            })?,
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => alt::TableState::default(),
            Err(e) => anyhow::bail!("state file {}: {e} (no lookup table is created or extended until it can be read)", path.display()),
        };
        st.saved = alt::TableState { retry: saved.retry.restored(self.now_unix()), ..saved };
        st.file_read = true;
        Ok(())
    }

    /// Write the state file: to a temporary file first, then renamed over the old one, so a
    /// crash cannot leave half a file. The file is synced before the rename, and the
    /// directory after it (best effort): the address of a table is on the disk, not in a
    /// cache, when its create is sent.
    fn persist_alts(&self) -> std::io::Result<()> {
        use std::io::Write;
        let text = lock(&self.alt_state).saved.to_json();
        std::fs::create_dir_all(&self.cfg.state_dir)?;
        let path = self.state_file();
        let tmp = path.with_extension("json.tmp");
        let mut file = std::fs::File::create(&tmp)?;
        file.write_all(text.as_bytes())?;
        file.sync_all()?;
        std::fs::rename(&tmp, &path)?;
        if let Ok(dir) = std::fs::File::open(&self.cfg.state_dir) {
            let _ = dir.sync_all();
        }
        Ok(())
    }

    /// [`Self::persist_alts`], with one error line when it fails and one line when it works
    /// again. The tables stay in use from memory either way.
    fn save_alts(&self) -> bool {
        let result = self.persist_alts();
        let mut st = lock(&self.alt_state);
        match result {
            Ok(()) => {
                if std::mem::take(&mut st.unsaved) {
                    tracing::info!(file = %self.state_file().display(), "the lookup-table state file is written again");
                }
                true
            }
            Err(e) => {
                if !std::mem::replace(&mut st.unsaved, true) {
                    let tables: Vec<String> = st.saved.tables.iter().map(ToString::to_string).collect();
                    self.metrics.lookup_tables.inc("state_file");
                    tracing::error!(
                        alert = "lookup_table_state_unsaved",
                        file = %self.state_file().display(),
                        error = %e,
                        tables = %tables.join(","),
                        "the lookup-table state file cannot be written: these tables stay in use from memory, and no table is created while it cannot be written"
                    );
                }
                false
            }
        }
    }

    /// Read the crank's tables from the chain: the configured ones, those of the state file,
    /// and any create that may have landed unseen. Nothing is created or extended before this
    /// has worked once, nor after a lookup-table transaction until it has worked again.
    async fn load_alts(&self) -> anyhow::Result<()> {
        self.read_alt_state()?;
        let (mut keys, pending, mut min_slot, settle_height) = {
            let st = lock(&self.alt_state);
            let mut keys: Vec<Address> = self.cfg.alt.tables.iter().filter_map(|s| s.parse().ok()).collect();
            keys.extend(st.saved.tables.iter().copied());
            keys.extend(st.saved.pending.iter().copied());
            (keys, st.saved.pending.clone(), st.saved.min_slot, st.saved.settle_height)
        };
        keys.sort_by_key(|k| k.to_bytes());
        keys.dedup();
        if settle_height > 0 || !pending.is_empty() {
            // A transaction whose outcome was not seen is judged on one node's view: its
            // block height must be past the transaction's last valid block, and the tables
            // are then read as of that node's slot or later.
            let (slot, height) = self.rpc.get_slot_and_block_height().await?;
            if !alt::settled(height, settle_height) {
                anyhow::bail!(
                    "a lookup-table transaction whose outcome was not seen could land until block height {settle_height}: \
                     the tables are read again once the chain is {} blocks past it (now {height})",
                    alt::SETTLE_MARGIN_BLOCKS
                );
            }
            min_slot = min_slot.max(slot);
        }
        // A node that has not processed `min_slot` answers with an error, never with the
        // state before the crank's last lookup-table transaction.
        let accs = self
            .rpc
            .get_multiple_accounts_at(&keys, min_slot)
            .await
            .map_err(|e| anyhow::anyhow!("reading the lookup tables (as of slot {min_slot} or later): {e}"))?;
        let tables: Vec<LookupTable> = keys
            .into_iter()
            .zip(accs)
            .filter_map(|(k, a)| a.and_then(|a| LookupTable::decode(k, &a.owner, &a.data)))
            .collect();
        let changed = {
            let mut st = lock(&self.alt_state);
            // This read is final for every create that was in doubt: its table is there, or
            // the create never landed.
            for table in &pending {
                if tables.iter().any(|t| t.key == *table) {
                    st.saved.remember(*table);
                    tracing::info!(%table, "a lookup table whose create was not seen to land is on chain: it is in use");
                } else {
                    st.saved.pending.retain(|p| p != table);
                    tracing::info!(%table, "a lookup-table create never landed");
                }
            }
            st.loaded = true;
            let was_in_doubt = std::mem::take(&mut st.saved.settle_height) != 0;
            was_in_doubt || !pending.is_empty()
        };
        *lock(&self.alts) = tables;
        if changed {
            self.save_alts();
        }
        Ok(())
    }

    /// Is the wait after a failed lookup-table transaction over? The wait is held to its cap
    /// each time it is looked at: a system clock that was set back while the crank runs
    /// cannot stretch it past [`alt::RETRY_MAX_SECS`].
    fn alt_retry_due(&self) -> bool {
        let now = self.now_unix();
        let mut st = lock(&self.alt_state);
        st.saved.retry = st.saved.retry.restored(now);
        st.saved.retry.due(now)
    }

    /// The fee payer was short for the last lookup-table transaction and, by the poller's
    /// last balance reading, still is: nothing is asked and nothing is sent.
    fn alt_short(&self) -> bool {
        let known = u64::try_from(self.metrics.cranker_lamports.get()).unwrap_or(0);
        lock(&self.alt_state).short_of.is_some_and(|need| known < need)
    }

    /// What the fee payer's own account must keep to stay rent-exempt. A transaction that
    /// would leave it with less (and not with exactly zero) is refused by the chain before
    /// it runs, so it would be sent and never land.
    async fn fee_payer_rent(&self) -> anyhow::Result<u64> {
        let cached = self.fee_payer_rent.load(Ordering::Relaxed);
        if cached != 0 {
            return Ok(cached);
        }
        let rent = self.rpc.get_minimum_balance_for_rent_exemption(0).await?;
        self.fee_payer_rent.store(rent, Ordering::Relaxed);
        Ok(rent)
    }

    /// Does the fee payer hold `rent` lamports of new rent, [`ALT_FEE_MARGIN_LAMPORTS`] for
    /// each of `txs` transactions, and what its own account must keep? If not: one warning,
    /// and the lookup tables are left alone until the poller's balance reading says it does.
    async fn alt_funded(&self, rent: u64, txs: u64, what: &'static str) -> anyhow::Result<bool> {
        let keep = self.fee_payer_rent().await?;
        let need = rent.saturating_add(ALT_FEE_MARGIN_LAMPORTS.saturating_mul(txs)).saturating_add(keep);
        let balance = self.rpc.get_balance(&self.cranker()).await?;
        self.note_fee_payer_balance(balance);
        let mut st = lock(&self.alt_state);
        if balance >= need {
            st.short_of = None;
            return Ok(true);
        }
        if st.short_of.replace(need).is_none() {
            self.metrics.lookup_tables.inc("low_balance");
            tracing::warn!(
                alert = "lookup_table_unfunded",
                fee_payer = %self.cranker(),
                balance,
                need,
                rent,
                keep,
                "the fee payer cannot pay for {what} (its rent, the fees, and what the fee payer's own account must keep): \
                 nothing is sent, and it is looked at again once the balance is enough. \
                 Digs go on without it (fewer rigs fit a transaction). Fund the fee payer, or set alt.enabled = false"
            );
        }
        Ok(false)
    }

    /// A lookup-table transaction valid until block height `lvbh` is about to be sent. From
    /// here until its outcome is seen, the tables are not trusted as read before the chain is
    /// past it. Returns the settle height to go back to.
    fn alt_tx_begins(&self, lvbh: u64) -> u64 {
        let mut st = lock(&self.alt_state);
        let before = st.saved.settle_height;
        st.saved.settle_height = before.max(lvbh);
        st.loaded = false;
        before
    }

    /// The outcome of that transaction was seen: it landed in `slot` (and worked, or failed).
    fn alt_tx_seen(&self, before: u64, slot: u64) {
        let mut st = lock(&self.alt_state);
        st.saved.settle_height = before;
        st.saved.min_slot = st.saved.min_slot.max(slot);
    }

    /// A lookup-table transaction did not land, or could not be sent: wait before the next
    /// one (see [`alt::Retry`]).
    fn alt_failed(&self, event: &'static str) {
        self.metrics.lookup_tables.inc(event);
        let now = self.now_unix();
        let (failures, wait) = {
            let mut st = lock(&self.alt_state);
            st.saved.retry.fail(now);
            (st.saved.retry.failures, st.saved.retry.not_before_unix.saturating_sub(now))
        };
        tracing::warn!(failures, retry_in_secs = wait, "lookup-table maintenance backs off");
        self.save_alts();
    }

    /// Create a lookup table. Its address goes into the state file before the transaction is
    /// sent, so a create that lands is found again whatever happens to this process.
    async fn create_alt(&self) -> anyhow::Result<()> {
        let me = self.cranker();
        let recent = self.rpc.get_slot("finalized").await?;
        let (ix, table) = alt::create_table_ix(&me, &me, recent);
        let (wire, sig, lvbh) = self.sign_simple(vec![ix]).await?;
        let before = self.alt_tx_begins(lvbh);
        lock(&self.alt_state).saved.pending.push(table);
        if !self.save_alts() {
            let mut st = lock(&self.alt_state);
            st.saved.pending.retain(|p| *p != table);
            st.saved.settle_height = before;
            anyhow::bail!(
                "the state file {} cannot be written: no lookup table is created, because its address would be lost at the next restart",
                self.state_file().display()
            );
        }
        match self.send_wire(&wire, &sig, lvbh).await? {
            Outcome::Landed { err: None, slot } => {
                tracing::info!(%table, slot, "created lookup table");
                self.alt_tx_seen(before, slot);
                {
                    let mut st = lock(&self.alt_state);
                    st.saved.remember(table);
                    st.saved.retry.clear();
                }
                // In memory at once, whatever the next read says: a table is empty when created.
                lock(&self.alts).push(LookupTable::empty(table, me));
                self.metrics.lookup_tables.inc("created");
                if let Err(e) = self.persist_alts() {
                    lock(&self.alt_state).unsaved = true;
                    self.metrics.lookup_tables.inc("state_file");
                    tracing::error!(
                        alert = "lookup_table_state_unsaved",
                        %table,
                        file = %self.state_file().display(),
                        error = %e,
                        "the lookup table was created but the state file could not be updated: the table stays in use from memory and no other one is created. Keep this address: it is needed to close the table"
                    );
                }
                Ok(())
            }
            Outcome::Landed { err: Some(err), slot } => {
                // It landed and failed: the table does not exist.
                self.alt_tx_seen(before, slot);
                lock(&self.alt_state).saved.pending.retain(|p| *p != table);
                anyhow::bail!("creating lookup table {table} failed on-chain in slot {slot}: {err}")
            }
            other => anyhow::bail!("creating lookup table {table}: {other:?} (it stays on record until the chain is well past its blockhash)"),
        }
    }

    /// Create a table if the fee payer can pay for it with its shared accounts. `Ok(false)`:
    /// it cannot, and nothing was sent. An attempt that fails starts the backoff.
    async fn create_alt_if_funded(&self) -> anyhow::Result<bool> {
        let attempt = async {
            let len = alt::table_len(alt::shared_addresses(&self.program_id).len());
            let rent = self.rpc.get_minimum_balance_for_rent_exemption(len).await?;
            // Two transactions: the create, and the extend that puts the shared accounts in.
            if !self.alt_funded(rent, 2, "a lookup table").await? {
                return Ok(false);
            }
            self.create_alt().await.map(|()| true)
        };
        let result: anyhow::Result<bool> = attempt.await;
        if result.is_err() {
            self.alt_failed("create_failed");
        }
        result
    }

    /// Add `chunk` to `table`, which holds `have` addresses. `Ok(None)`: the fee payer cannot
    /// pay the rent of the new entries, and nothing was sent.
    async fn extend_alt(&self, table: &Address, have: usize, chunk: &[Address]) -> anyhow::Result<Option<Outcome>> {
        let me = self.cranker();
        // An extend pays the rent of the bytes it adds.
        let after = self.rpc.get_minimum_balance_for_rent_exemption(alt::table_len(have + chunk.len())).await?;
        let current = self.rpc.get_minimum_balance_for_rent_exemption(alt::table_len(have)).await?;
        if !self.alt_funded(after.saturating_sub(current), 1, "the rent of new lookup-table entries").await? {
            return Ok(None);
        }
        let (wire, sig, lvbh) = self.sign_simple(vec![alt::extend_table_ix(table, &me, &me, chunk)]).await?;
        let before = self.alt_tx_begins(lvbh);
        // Best effort (unlike before a create): a crank that cannot write its state file
        // still fills the table it holds in memory.
        self.save_alts();
        let outcome = self.send_wire(&wire, &sig, lvbh).await?;
        if let Outcome::Landed { slot, .. } = &outcome {
            self.alt_tx_seen(before, *slot);
        }
        Ok(Some(outcome))
    }

    /// Rigs worth a lookup-table slot: the operator pays the table rent (4 addresses of 32
    /// bytes: 650,240 lamports per rig at mainnet's 5,080 lamports per byte), so only rigs
    /// that are actually heartbeating (a verified heartbeat is held) or hold a covering lease
    /// qualify. Arming throwaway rigs therefore costs an attacker a Rig account and a live
    /// P-256 key per slot, not just a registration.
    fn rigs_for_alt(&self, rigs: &[(Address, Rig)]) -> Vec<(Address, Rig)> {
        let round = self.chain.borrow().board.map_or(0, |b| b.round_id);
        rigs.iter()
            .filter(|(a, r)| self.store.get(a).is_some() || (round > 0 && r.lease_covers(round)))
            .cloned()
            .collect()
    }

    /// Make sure the crank's tables hold the shared accounts and every known rig's four.
    ///
    /// A table locks rent that only comes back by closing it by hand, so this never guesses.
    /// It sends nothing while the wait after a failed transaction runs or while the fee payer
    /// is known to be short. It reads the tables before deciding, at start and again after
    /// every transaction it sent; a read sends nothing, so it is not held back by the wait.
    /// And it creates a table only when the crank owns none, no create may still land,
    /// `alt.max_tables` allows one and the fee payer can pay for it.
    pub async fn sync_alts(&self) -> anyhow::Result<()> {
        // The poller and new-round maintenance both sync; never interleave (double creates).
        let _guard = self.alt_sync.lock().await;
        self.read_alt_state()?;
        if !lock(&self.alt_state).loaded {
            self.load_alts().await?;
        }
        if !self.alt_retry_due() || self.alt_short() {
            return Ok(());
        }
        let c = &self.cfg.alt;
        let me = self.cranker();
        if self.owned_tables() == 0 {
            if !c.auto_create {
                return Ok(());
            }
            if !self.table_slot_free() {
                self.note_table_limit();
                return Ok(());
            }
            if !self.create_alt_if_funded().await? {
                return Ok(());
            }
        }
        if !c.auto_extend {
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
            // Everything is in place: the count of failures in a row starts over.
            let had_failed = std::mem::take(&mut lock(&self.alt_state).saved.retry) != alt::Retry::default();
            if had_failed {
                self.save_alts();
            }
            return Ok(());
        }
        let own: Vec<LookupTable> = tables.into_iter().filter(|t| t.authority == Some(me)).collect();
        let mut held: HashMap<Address, usize> = own.iter().map(|t| (t.key, t.addresses.len())).collect();
        let (plan, overflow) = alt::plan_extends(&own, missing);
        for (table, chunk) in plan.into_iter().take(MAX_ALT_TXS_PER_ROUND) {
            let have = held.get(&table).copied().unwrap_or(0);
            match self.extend_alt(&table, have, &chunk).await {
                Ok(Some(Outcome::Landed { err: None, .. })) => {
                    held.insert(table, have + chunk.len());
                    self.metrics.lookup_tables.inc("extended");
                    lock(&self.alt_state).saved.retry.clear();
                    self.save_alts();
                    tracing::info!(%table, added = chunk.len(), "extended lookup table");
                }
                // Not funded: nothing was sent, and what is in memory is still right.
                Ok(None) => return Ok(()),
                // It may or may not have landed: nothing more is sent until the wait is over,
                // and the tables are read again before that (a second extend with the same
                // addresses would pay their rent twice).
                Ok(Some(other)) => {
                    tracing::warn!(%table, outcome = ?other, "extend failed");
                    self.alt_failed("extend_failed");
                    return Ok(());
                }
                Err(e) => {
                    self.alt_failed("extend_failed");
                    return Err(e);
                }
            }
        }
        if !overflow.is_empty() && c.auto_create {
            let owned = self.tables_counted();
            if owned >= c.max_tables {
                tracing::warn!(owned, max = c.max_tables, "lookup tables full; new rigs use static keys");
            } else if self.table_slot_free() {
                self.create_alt_if_funded().await?;
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
