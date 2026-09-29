//! Which rigs dig this round.
//!
//! The planner repeats, off-chain, **every** check `heads_down::dig` makes (INTERFACE.md,
//! `dig` steps 1-5) and every ORE abort or no-op condition the program pre-flights
//! (`docs/ORE.md` section 5), so the crank only pays for rigs that will actually deploy.
//! It is a pure function of chain state, the held heartbeats and the ledger: no I/O, fully
//! unit-tested. The program remains the authority; a stale read here costs at most a
//! skipped rig (logged on-chain as `RigSkipped`), never a wrong deploy.

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
    /// `config.paused == 1`.
    Paused,
    /// Round has not had its first deploy (`end_slot == u64::MAX`) and policy says wait.
    RoundNotStarted,
    /// Outside `start_slot <= slot < end_slot`.
    OutsideRoundWindow,
    /// Executor PDA cannot cover ORE's CHECKPOINT_FEE top-up.
    ExecutorFloatLow,
    /// Rig not Armed/Down.
    NotDiggable(RigState),
    /// Focus-only plan: never deploys.
    FocusOnly,
    /// `last_dug_round == board.round_id`.
    AlreadyDug,
    /// The ledger has a live or final attempt for (rig, round).
    AlreadySubmitted,
    /// No covering on-chain lease and no usable heartbeat.
    NoLease,
    /// Caps expired (with the clock margin).
    CapsExpired,
    /// Outside the plan window (with the clock margin).
    OutsideWindow,
    /// `ema_ev > min(plan_max_ev_cost, cap_max_cost)` (or overflow).
    CostGate,
    /// `plan_split + plan_solo == 0`.
    NoTiles,
    /// Remaining budget rounds to 0 lamports per tile.
    BudgetExhausted,
    /// Automation missing.
    NoAutomation,
    /// Automation failed its layout pin.
    AutomationLayout,
    /// `automation.executor != Executor PDA`.
    ExecutorMismatch,
    /// `automation.authority != rig.authority`.
    AuthorityMismatch,
    /// Strategy not Discretionary, or `fee != config.executor_fee`.
    StrategyMismatch,
    /// `balance < per_tile * k + fee`: ORE would close the Automation and no-op.
    InsufficientBalance,
    /// Automation's Motherlode conditions fail: ORE would return Ok without deploying.
    MotherlodeCondition,
    /// Miner missing (ORE would try to create it with the signer's seeds and fail).
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
            Skip::FocusOnly => "focus_only",
            Skip::AlreadyDug => "already_dug",
            Skip::AlreadySubmitted => "already_submitted",
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
    /// Lamports per tile the program will deploy.
    pub per_tile: u64,
    /// `k = split + solo`.
    pub tiles: u16,
    /// Expected Automation debit: `per_tile * k + fee`.
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
    // Step 2 (state) and plan kind.
    if !rig.state.diggable() {
        return Err(Skip::NotDiggable(rig.state));
    }
    if rig.focus_only() {
        return Err(Skip::FocusOnly);
    }
    // Step 3: idempotency, on-chain then local.
    if rig.last_dug_round == round_id {
        return Err(Skip::AlreadyDug);
    }
    if submitted.already_submitted(addr, round_id) {
        return Err(Skip::AlreadySubmitted);
    }
    // Step 2: lease. Reuse the on-chain lease when it covers this round (no precompile
    // signature to pay for); otherwise a held heartbeat must grant one.
    let heartbeat = if rig.lease_covers(round_id) {
        None
    } else {
        Some(usable_heartbeat(inp.heartbeats.get(addr), rig, round_id).ok_or(Skip::NoLease)?)
    };
    // Step 4: caps, window, gate.
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
    // Step 5: amount.
    let spent_week = if inp.now_ts >= rig.week_start_ts.saturating_add(WEEK_SECS) { 0 } else { rig.spent_week };
    let dig_lamports = rig
        .plan_dig_lamports
        .min(rig.cap_round)
        .min(rig.cap_shift.saturating_sub(rig.spent_shift))
        .min(rig.cap_week.saturating_sub(spent_week));
    let k = rig.tiles();
    if k == 0 {
        return Err(Skip::NoTiles);
    }
    let accounts = RigAccounts::derive(*addr, rig.authority);
    let automation = decode_automation(inp.automations.get(&accounts.automation))?;
    if automation.executor != *executor {
        return Err(Skip::ExecutorMismatch);
    }
    if automation.authority != rig.authority {
        return Err(Skip::AuthorityMismatch);
    }
    if automation.strategy != ore::STRATEGY_DISCRETIONARY || automation.fee != inp.config.executor_fee {
        return Err(Skip::StrategyMismatch);
    }
    let per_tile = (dig_lamports / u64::from(k)).min(automation.amount);
    if per_tile == 0 {
        return Err(Skip::BudgetExhausted);
    }
    let expected_debit = per_tile
        .checked_mul(u64::from(k))
        .and_then(|v| v.checked_add(automation.fee))
        .ok_or(Skip::MathOverflow)?;
    if automation.balance < expected_debit {
        return Err(Skip::InsufficientBalance);
    }
    if !automation.conditions.motherlode_allows(inp.treasury.motherlode) {
        return Err(Skip::MotherlodeCondition);
    }
    // ORE pre-flight: the Miner must exist, be the rig's, and be checkpointed (or be
    // checkpointed by an instruction the crank prepends).
    let miner = decode_miner(inp.miners.get(&accounts.miner))?;
    if miner.authority != rig.authority {
        return Err(Skip::MinerInvalid);
    }
    let checkpoint_round = (!miner.deploy_would_pass_checkpoint_assert(round_id)).then_some(miner.round_id);
    let predicted_mask = inp
        .round
        .filter(|r| r.id == round_id)
        .map(|r| ore::select_tiles(round_id, &r.deployed, rig.plan_split_tiles, rig.plan_solo_tiles));
    Ok(Decision {
        dig: RigDig { accounts, heartbeat, checkpoint_round },
        per_tile,
        tiles: k,
        expected_debit,
        predicted_mask,
    })
}

/// A held heartbeat the program will accept for `round_id` (dig step 2): same shift, a
/// counter above the rig's, signed by the rig's current key, not from the future, and a
/// lease `[round_id, round_id + min(lease, plan_lease) - 1]` that covers the round.
pub fn usable_heartbeat(hb: Option<&VerifiedHeartbeat>, rig: &Rig, round_id: u64) -> Option<VerifiedHeartbeat> {
    let hb = hb?;
    if hb.fields.shift_id != rig.shift_id || hb.fields.counter <= rig.hb_counter || hb.pubkey != rig.p256_pubkey {
        return None;
    }
    if hb.fields.round_id > round_id {
        return None;
    }
    let (from, to) = hd::lease_range(hb.fields.round_id, hb.fields.lease_rounds, rig.plan_lease_rounds)?;
    (from <= round_id && round_id <= to).then_some(*hb)
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
            Skip::FocusOnly,
            Skip::AlreadyDug,
            Skip::AlreadySubmitted,
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
}
