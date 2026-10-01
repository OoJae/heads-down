//! Which rigs dig this round.
//!
//! The dig planner repeats, off-chain, **every** check `heads_down::dig` makes (INTERFACE v1.1
//! §6.1-§6.5) and every ORE abort or no-op condition the program pre-flights, so the crank
//! only pays for rigs that will actually deploy. The amount is the program's rule, integer
//! for integer:
//!
//! ```text
//! budget   = min(plan_dig, min(cap_round, cap_shift − spent_shift, cap_week − spent_week) − fee)  (saturating)
//! mask     = split / solo least-crowded squares, excluding squares the Miner holds this round
//! k        = popcount(mask)
//! per_tile = min(budget / k, automation.amount)
//! fee_due  = automation.fee if the Miner has no deployment this round, else 0
//! need     = per_tile·k + fee_due          (the Automation debit)
//! RigDug.lamports = per_tile·k             (SOL on squares only)
//! ```
//!
//! It is a pure function of chain state, the held heartbeats and the ledger: no I/O, fully
//! unit-tested. The program remains the authority; a stale read here costs at
//! most a skipped rig (logged on-chain as `RigSkipped`), never a wrong deploy.

use std::collections::HashMap;

use solana_address::Address;

use crate::account::RawAccount;
use crate::gate;
use crate::hd::{self, HdConfig, Rig, RigAccounts, RigState};
use crate::heartbeat::VerifiedHeartbeat;
use crate::ore::{self, Automation, Board, LayoutError, Miner, Round, Treasury};
use crate::tx::RigDig;

/// Seconds in a week (`week_start_ts` rolls every 7 days).
pub const WEEK_SECS: i64 = 7 * 24 * 3600;
/// Rent-exempt minimum for a 0-byte account (the Executor PDA is System-owned, data-less).
pub const RENT_EXEMPT_ZERO_BYTES: u64 = 890_880;

/// Why a rig is not dug this round. [`Skip::label`] is the metric label.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Skip {
    /// `config.paused == 1` (`dig` would fail the whole transaction with `Paused`).
    Paused,
    /// Round has not had its first deploy (`end_slot == u64::MAX`) and policy says wait.
    RoundNotStarted,
    /// Outside `start_slot <= slot < end_slot` (on-chain: `RoundNotActive`).
    OutsideRoundWindow,
    /// Executor PDA cannot cover ORE's CHECKPOINT_FEE top-up (on-chain: `ExecutorUnderfunded`).
    ExecutorFloatLow,
    /// Rig is Idle, Broken, Frozen or unknown.
    NotDiggable(RigState),
    /// Rig is Cooling and no fresh heartbeat is held: a lease cannot be reused while Cooling.
    CoolingNeedsHeartbeat,
    /// Focus-only plan: never deploys (its heartbeats go through `record_heartbeats`).
    FocusOnly,
    /// `last_dug_round == board.round_id`.
    AlreadyDug,
    /// The ledger has a live or final attempt for (rig, round).
    AlreadySubmitted,
    /// A phone-signed BREAK / FREEZE for this rig is being landed.
    SignalPending,
    /// No covering on-chain lease and no usable heartbeat.
    NoLease,
    /// Caps expired (with the clock margin).
    CapsExpired,
    /// Outside the plan window (with the clock margin).
    OutsideWindow,
    /// `ema_ev > min(plan_max_ev_cost, cap_max_cost)` (or overflow).
    CostGate,
    /// No free square to choose (`k == 0`).
    NoTiles,
    /// `per_tile == 0`: the budget left after reserving the fee is below one lamport per square.
    BudgetExhausted,
    /// Automation missing.
    NoAutomation,
    /// Automation failed its layout pin.
    AutomationLayout,
    /// `automation.executor != Executor PDA`.
    ExecutorMismatch,
    /// `automation.authority != rig.authority` (on-chain this fails the whole transaction).
    AuthorityMismatch,
    /// Strategy not Discretionary, or `fee != config.executor_fee`.
    StrategyMismatch,
    /// `balance < per_tile·k + fee_due` (on-chain: `InsufficientAutomationBalance`).
    InsufficientBalance,
    /// Automation's Motherlode conditions fail: ORE would return Ok without deploying.
    MotherlodeCondition,
    /// Miner missing.
    NoMiner,
    /// Miner failed its layout pin or belongs to someone else.
    MinerInvalid,
    /// Arithmetic overflow in the amount computation.
    MathOverflow,
}

impl Skip {
    /// Stable snake_case label.
    pub fn label(&self) -> &'static str {
        match self {
            Skip::Paused => "paused",
            Skip::RoundNotStarted => "round_not_started",
            Skip::OutsideRoundWindow => "outside_round_window",
            Skip::ExecutorFloatLow => "executor_float_low",
            Skip::NotDiggable(_) => "not_diggable",
            Skip::CoolingNeedsHeartbeat => "cooling_needs_heartbeat",
            Skip::FocusOnly => "focus_only",
            Skip::AlreadyDug => "already_dug",
            Skip::AlreadySubmitted => "already_submitted",
            Skip::SignalPending => "signal_pending",
            Skip::NoLease => "no_lease",
            Skip::CapsExpired => "caps_expired",
            Skip::OutsideWindow => "outside_window",
            Skip::CostGate => "cost_gate",
            Skip::NoTiles => "no_tiles",
            Skip::BudgetExhausted => "budget_exhausted",
            Skip::NoAutomation => "no_automation",
            Skip::AutomationLayout => "automation_layout",
            Skip::ExecutorMismatch => "executor_mismatch",
            Skip::AuthorityMismatch => "authority_mismatch",
            Skip::StrategyMismatch => "strategy_mismatch",
            Skip::InsufficientBalance => "insufficient_balance",
            Skip::MotherlodeCondition => "motherlode_condition",
            Skip::NoMiner => "no_miner",
            Skip::MinerInvalid => "miner_invalid",
            Skip::MathOverflow => "math_overflow",
        }
    }
}

/// Planner knobs.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Policy {
    /// Seconds of clock skew to allow for when checking caps expiry and the plan window
    /// (the program uses the cluster clock; the crank only estimates it).
    pub clock_margin_secs: i64,
    /// Dig into a round that has not started (`end_slot == u64::MAX`), which makes the dig
    /// the round's first deploy. Default false: wait for ORE's miners to start it.
    pub start_rounds: bool,
    /// Extra lamports the Executor PDA must hold above rent + CHECKPOINT_FEE.
    pub executor_reserve: u64,
}

impl Default for Policy {
    fn default() -> Self {
        Policy { clock_margin_secs: 5, start_rounds: false, executor_reserve: 0 }
    }
}

/// Everything the planner reads.
#[derive(Clone, Copy, Debug)]
pub struct Inputs<'a> {
    /// heads_down program id.
    pub program_id: &'a Address,
    /// Estimated cluster unix time.
    pub now_ts: i64,
    /// Slot the transactions are expected to land in.
    pub landing_slot: u64,
    /// ORE Board.
    pub board: &'a Board,
    /// ORE Treasury.
    pub treasury: &'a Treasury,
    /// Current Round (tile prediction only).
    pub round: Option<&'a Round>,
    /// heads_down Config.
    pub config: &'a HdConfig,
    /// Executor PDA balance.
    pub executor_lamports: u64,
    /// Candidate rigs (address, decoded account).
    pub rigs: &'a [(Address, Rig)],
    /// Automation accounts by address (`None` = missing).
    pub automations: &'a HashMap<Address, Option<RawAccount>>,
    /// Miner accounts by address (`None` = missing).
    pub miners: &'a HashMap<Address, Option<RawAccount>>,
    /// Latest verified heartbeat per rig.
    pub heartbeats: &'a HashMap<Address, VerifiedHeartbeat>,
}

/// One rig to dig.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Decision {
    /// What goes in the transaction.
    pub dig: RigDig,
    /// Lamports per square the program will deploy.
    pub per_tile: u64,
    /// `k = popcount(mask)`: squares the program will choose.
    pub tiles: u16,
    /// `per_tile · k`: SOL placed on squares, what `RigDug.lamports` will report.
    pub squares_lamports: u64,
    /// The Automation fee ORE charges on the rig's first deploy this round (else 0).
    pub fee_due: u64,
    /// The whole Automation debit: `squares_lamports + fee_due` (what `spent_*` count).
    pub expected_debit: u64,
    /// The mask the program should pick (if the Round was available).
    pub predicted_mask: Option<u32>,
}

/// The plan for one round.
#[derive(Clone, Debug, Default)]
pub struct Plan {
    /// Board round planned for.
    pub round_id: u64,
    /// Gate value this round (`None` on overflow).
    pub ema_ev: Option<u64>,
    /// Rigs to dig, lease reuses first, then fresh heartbeats.
    pub digs: Vec<Decision>,
    /// Rigs not dug, with the reason.
    pub skips: Vec<(Address, Skip)>,
}

/// Is `(rig, round)` already taken care of by an earlier transaction?
pub trait SubmittedCheck {
    /// True to skip with [`Skip::AlreadySubmitted`].
    fn already_submitted(&self, rig: &Address, round: u64) -> bool;
}

impl<F: Fn(&Address, u64) -> bool> SubmittedCheck for F {
    fn already_submitted(&self, rig: &Address, round: u64) -> bool {
        self(rig, round)
    }
}

/// `min(plan_dig, min(cap_round, cap_shift − spent_shift, cap_week − spent_week) − fee)`,
/// saturating: the program's `logic::dig_budget`. The fee is reserved inside every cap
/// because `spent_*` count the whole debit.
pub fn dig_budget(plan_dig: u64, cap_round: u64, cap_shift: u64, spent_shift: u64, cap_week: u64, spent_week: u64, fee: u64) -> u64 {
    let headroom = cap_round.min(cap_shift.saturating_sub(spent_shift)).min(cap_week.saturating_sub(spent_week));
    plan_dig.min(headroom.saturating_sub(fee))
}

/// `spent_week` as the program sees it at `now`: the week rolls (to 0) when `week_start_ts`
/// is 0 or `now − week_start_ts ≥ 604,800` (the program's `logic::roll_week`).
pub fn spent_week_at(rig: &Rig, now: i64) -> u64 {
    if rig.week_start_ts == 0 || now.saturating_sub(rig.week_start_ts) >= WEEK_SECS {
        0
    } else {
        rig.spent_week
    }
}

/// Build the plan.
pub fn plan(inp: &Inputs<'_>, policy: &Policy, submitted: &dyn SubmittedCheck) -> Plan {
    let round_id = inp.board.round_id;
    let ema_ev = gate::ema_ev(inp.board.production_cost_ema, inp.treasury.motherlode);
    let mut out = Plan { round_id, ema_ev, ..Plan::default() };

    // Batch-wide conditions.
    let global = if inp.config.paused {
        Some(Skip::Paused)
    } else if !inp.board.started() && !policy.start_rounds {
        Some(Skip::RoundNotStarted)
    } else if inp.board.started() && !inp.board.accepts_deploy_at(inp.landing_slot) {
        Some(Skip::OutsideRoundWindow)
    } else if inp.executor_lamports
        < RENT_EXEMPT_ZERO_BYTES.saturating_add(ore::CHECKPOINT_FEE).saturating_add(policy.executor_reserve)
    {
        Some(Skip::ExecutorFloatLow)
    } else {
        None
    };
    if let Some(g) = global {
        out.skips = inp.rigs.iter().map(|(a, _)| (*a, g.clone())).collect();
        return out;
    }

    let executor = hd::executor_pda(inp.program_id).0;
    for (addr, rig) in inp.rigs {
        match check_rig(inp, policy, submitted, &executor, addr, rig) {
            Ok(d) => out.digs.push(d),
            Err(s) => out.skips.push((*addr, s)),
        }
    }
    // Lease reuses are cheapest (no precompile signature); keep a stable order within each.
    out.digs.sort_by_key(|d| (d.dig.heartbeat.is_some(), d.dig.accounts.rig.to_bytes()));
    out
}

fn check_rig(
    inp: &Inputs<'_>,
    policy: &Policy,
    submitted: &dyn SubmittedCheck,
    executor: &Address,
    addr: &Address,
    rig: &Rig,
) -> Result<Decision, Skip> {
    let round_id = inp.board.round_id;
    // State (INTERFACE §6.7): Armed and Down dig; Cooling only with a fresh heartbeat.
    let cooling = match rig.state {
        RigState::Armed | RigState::Down => false,
        RigState::Cooling => true,
        s => return Err(Skip::NotDiggable(s)),
    };
    if rig.focus_only() {
        return Err(Skip::FocusOnly);
    }
    // Idempotency, on-chain then local.
    if rig.last_dug_round == round_id {
        return Err(Skip::AlreadyDug);
    }
    if submitted.already_submitted(addr, round_id) {
        return Err(Skip::AlreadySubmitted);
    }
    // Lease. Reuse the on-chain lease when it covers this round and the state allows it (no
    // precompile signature to pay for); otherwise a held heartbeat must leave a covering lease.
    let heartbeat = if !cooling && rig.lease_covers(round_id) {
        None
    } else {
        match usable_heartbeat(inp.heartbeats.get(addr), rig, round_id) {
            Some(h) => Some(h),
            None if cooling => return Err(Skip::CoolingNeedsHeartbeat),
            None => return Err(Skip::NoLease),
        }
    };
    // Caps, window, gate.
    let m = policy.clock_margin_secs;
    if inp.now_ts.saturating_add(m) > rig.caps_expiry_ts {
        return Err(Skip::CapsExpired);
    }
    if inp.now_ts < rig.plan_window_start_ts.saturating_add(m) || inp.now_ts.saturating_add(m) > rig.plan_window_end_ts {
        return Err(Skip::OutsideWindow);
    }
    if !gate::gate_open(
        inp.board.production_cost_ema,
        inp.treasury.motherlode,
        rig.plan_max_ev_cost,
        rig.cap_max_cost,
    ) {
        return Err(Skip::CostGate);
    }
    // The user's ORE accounts.
    let accounts = RigAccounts::derive(*addr, rig.authority);
    let automation = decode_automation(inp.automations.get(&accounts.automation))?;
    if automation.authority != rig.authority {
        return Err(Skip::AuthorityMismatch);
    }
    if automation.executor != *executor {
        return Err(Skip::ExecutorMismatch);
    }
    if automation.strategy != ore::STRATEGY_DISCRETIONARY || automation.fee != inp.config.executor_fee {
        return Err(Skip::StrategyMismatch);
    }
    let miner = decode_miner(inp.miners.get(&accounts.miner))?;
    if miner.authority != rig.authority {
        return Err(Skip::MinerInvalid);
    }
    // Squares and amount, exactly as the program computes them.
    let held = miner.held_mask(round_id);
    let k = ore::tiles_available(round_id, held, rig.plan_split_tiles, rig.plan_solo_tiles);
    if k == 0 {
        return Err(Skip::NoTiles);
    }
    let budget = dig_budget(
        rig.plan_dig_lamports,
        rig.cap_round,
        rig.cap_shift,
        rig.spent_shift,
        rig.cap_week,
        spent_week_at(rig, inp.now_ts),
        automation.fee,
    );
    let per_tile = (budget / u64::from(k)).min(automation.amount);
    if per_tile == 0 {
        return Err(Skip::BudgetExhausted);
    }
    let squares_lamports = per_tile.checked_mul(u64::from(k)).ok_or(Skip::MathOverflow)?;
    let sum_before = miner.deployed_in(round_id).ok_or(Skip::MathOverflow)?;
    let fee_due = if sum_before == 0 { automation.fee } else { 0 };
    let expected_debit = squares_lamports.checked_add(fee_due).ok_or(Skip::MathOverflow)?;
    if automation.balance < expected_debit {
        return Err(Skip::InsufficientBalance);
    }
    // ORE pre-flight.
    if !automation.conditions.motherlode_allows(inp.treasury.motherlode) {
        return Err(Skip::MotherlodeCondition);
    }
    // The Miner must be checkpointed; the crank prepends ORE `checkpoint` when it is not.
    let checkpoint_round = (!miner.deploy_would_pass_checkpoint_assert(round_id)).then_some(miner.round_id);
    let predicted_mask = inp
        .round
        .filter(|r| r.id == round_id)
        .map(|r| ore::select_tiles_excluding(round_id, &r.deployed, held, rig.plan_split_tiles, rig.plan_solo_tiles));
    Ok(Decision {
        dig: RigDig { accounts, heartbeat, checkpoint_round },
        per_tile,
        tiles: u16::try_from(k).unwrap_or(u16::MAX),
        squares_lamports,
        fee_due,
        expected_debit,
        predicted_mask,
    })
}

/// A held heartbeat the program will accept for `round_id` and that leaves a lease covering
/// it (INTERFACE §6.2 step 3-4 and §6.3): same shift, counter above the rig's, signed by the
/// rig's current key, not from the future, and — after "leases only move forward" — a lease
/// `[from, to]` with `from ≤ round_id ≤ to`.
pub fn usable_heartbeat(hb: Option<&VerifiedHeartbeat>, rig: &Rig, round_id: u64) -> Option<VerifiedHeartbeat> {
    let hb = hb?;
    if !fresh_for(hb, rig, round_id) {
        return None;
    }
    let g = hd::lease_after(rig, &hb.fields)?;
    (g.from <= round_id && round_id <= g.to).then_some(*hb)
}

/// `hb` would verify on-chain against `rig` in `round_id`: same shift, counter above the rig's,
/// signed by the rig's current key, not from the future, a non-empty lease.
pub fn fresh_for(hb: &VerifiedHeartbeat, rig: &Rig, round_id: u64) -> bool {
    hb.fields.shift_id == rig.shift_id
        && hb.fields.counter > rig.hb_counter
        && hb.pubkey == rig.p256_pubkey
        && hb.fields.round_id <= round_id
        && hb.fields.lease_rounds > 0
}

fn decode_automation(a: Option<&Option<RawAccount>>) -> Result<Automation, Skip> {
    match a {
        Some(Some(acc)) if !acc.is_closed() => {
            Automation::decode(&acc.owner, &acc.data).map_err(|_: LayoutError| Skip::AutomationLayout)
        }
        _ => Err(Skip::NoAutomation),
    }
}

fn decode_miner(m: Option<&Option<RawAccount>>) -> Result<Miner, Skip> {
    match m {
        Some(Some(acc)) if !acc.is_closed() => Miner::decode(&acc.owner, &acc.data).map_err(|_| Skip::MinerInvalid),
        _ => Err(Skip::NoMiner),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn labels_are_unique() {
        let all = [
            Skip::Paused,
            Skip::RoundNotStarted,
            Skip::OutsideRoundWindow,
            Skip::ExecutorFloatLow,
            Skip::NotDiggable(RigState::Idle),
            Skip::CoolingNeedsHeartbeat,
            Skip::FocusOnly,
            Skip::AlreadyDug,
            Skip::AlreadySubmitted,
            Skip::SignalPending,
            Skip::NoLease,
            Skip::CapsExpired,
            Skip::OutsideWindow,
            Skip::CostGate,
            Skip::NoTiles,
            Skip::BudgetExhausted,
            Skip::NoAutomation,
            Skip::AutomationLayout,
            Skip::ExecutorMismatch,
            Skip::AuthorityMismatch,
            Skip::StrategyMismatch,
            Skip::InsufficientBalance,
            Skip::MotherlodeCondition,
            Skip::NoMiner,
            Skip::MinerInvalid,
            Skip::MathOverflow,
        ];
        let set: std::collections::HashSet<_> = all.iter().map(Skip::label).collect();
        assert_eq!(set.len(), all.len());
    }

    #[test]
    fn budget_matches_the_program() {
        // programs/heads-down/program/src/logic.rs budget_reserves_the_fee_inside_every_cap
        assert_eq!(dig_budget(1_000, 10_000, 10_000, 0, 10_000, 0, 5), 1_000);
        assert_eq!(dig_budget(10_000, 1_000, 10_000, 0, 10_000, 0, 5), 995);
        assert_eq!(dig_budget(10_000, 10_000, 10_000, 9_000, 10_000, 0, 5), 995);
        assert_eq!(dig_budget(10_000, 10_000, 10_000, 0, 10_000, 9_500, 5), 495);
        assert_eq!(dig_budget(10_000, 10_000, 10_000, 10_000, 10_000, 0, 5), 0);
        assert_eq!(dig_budget(10_000, 4, 10_000, 0, 10_000, 0, 5), 0);
        assert_eq!(dig_budget(10_000, 10_000, 1_000, 5_000, 10_000, 0, 5), 0);
        assert_eq!(dig_budget(u64::MAX, u64::MAX, u64::MAX, 0, u64::MAX, 0, u64::MAX), 0);
        assert_eq!(dig_budget(u64::MAX, u64::MAX, u64::MAX, 0, u64::MAX, 0, 0), u64::MAX);
        // INTERFACE §6.4 example: cap_round = plan_dig = 1,000,000 on 10 squares, fee 5,000.
        let b = dig_budget(1_000_000, 1_000_000, u64::MAX, 0, u64::MAX, 0, 5_000);
        assert_eq!(b / 10, 99_500);
        assert_eq!((b / 10) * 10 + 5_000, 1_000_000, "the whole debit is exactly cap_round");
    }

    #[test]
    fn week_roll_matches_the_program() {
        let mut r = Rig { spent_week: 99, week_start_ts: 1_000, ..Rig::default() };
        assert_eq!(spent_week_at(&r, 1_000 + WEEK_SECS - 1), 99);
        assert_eq!(spent_week_at(&r, 1_000 + WEEK_SECS), 0);
        r.week_start_ts = 0;
        assert_eq!(spent_week_at(&r, 5), 0, "week_start 0 rolls");
    }
}
