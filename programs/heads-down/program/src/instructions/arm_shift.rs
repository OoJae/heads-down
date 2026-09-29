//! `arm_shift` (tag 5): authorized by the wallet **or** a P-256 PLAN.
//!
//! Accounts:
//! 0. `[writable]` Rig
//! 1. `[signer if mode 0]` authority (must equal `rig.authority` in both modes)
//! 2. `[]` ORE Board (for `shift_start_round`)
//! 3. `[]` Instructions sysvar (mode 1 only)
//!
//! Data: `mode u8 (0 wallet, 1 P-256) | max_ev_cost u64 | dig_lamports u64 |
//! split u8 | solo u8 | lease u8 | flags u8 | window_start i64 |
//! window_end i64` then, for mode 1, `counter u64 | p256_ix u8 |
//! p256_sig_index u8` (37 or 47 bytes).
//!
//! The plan must fit inside the wallet-signed caps (the phone key can only
//! tighten). `shift_id += 1`, state = Armed, shift counters reset.

use pinocchio::{error::ProgramError, AccountView, ProgramResult};

use crate::{
    error::HdError,
    events, logic,
    message::{self, Plan},
    ore,
    state::{self, plan_flags, rig_state, Rig},
    util::{authorize_signal, clock, Preimage, Reader, SignalAuth},
};

/// Maximum split tiles.
pub const MAX_SPLIT: u8 = 15;
/// Maximum solo tiles.
pub const MAX_SOLO: u8 = 10;
/// Maximum heartbeat lease.
pub const MAX_LEASE: u8 = 3;

/// Validate a plan's own fields (not against caps).
pub fn validate_plan(p: &Plan, now: i64) -> Result<(), HdError> {
    if p.lease == 0 || p.lease > MAX_LEASE || p.split > MAX_SPLIT || p.solo > MAX_SOLO {
        return Err(HdError::InvalidInstruction);
    }
    if p.flags & !plan_flags::ALL != 0 {
        return Err(HdError::InvalidInstruction);
    }
    let focus_only = p.flags & plan_flags::FOCUS_ONLY != 0;
    if !focus_only && (p.split.saturating_add(p.solo) == 0 || p.dig_lamports == 0) {
        return Err(HdError::InvalidInstruction);
    }
    if p.window_start >= p.window_end {
        return Err(HdError::InvalidInstruction);
    }
    if now > p.window_end {
        return Err(HdError::OutsideWindow);
    }
    Ok(())
}

/// Handler.
pub fn process(accounts: &mut [AccountView], data: &[u8]) -> ProgramResult {
    let [rig, authority, board, rest @ ..] = accounts else {
        return Err(ProgramError::NotEnoughAccountKeys);
    };
    let mut r = Reader::new(data);
    let p256_mode = SignalAuth::parse_mode(r.u8()?)?;
    let plan = Plan {
        max_ev_cost: r.u64()?,
        dig_lamports: r.u64()?,
        split: r.u8()?,
        solo: r.u8()?,
        lease: r.u8()?,
        flags: r.u8()?,
        window_start: r.i64()?,
        window_end: r.i64()?,
    };
    let auth = if p256_mode {
        SignalAuth::read_p256(&mut r)?
    } else {
        SignalAuth::Wallet
    };
    r.finish()?;

    let board = ore::read_board(board)?;
    let clk = clock()?;
    let now = clk.unix_timestamp;
    let rig_address = *rig.address();
    let mut g = state::load_mut::<Rig>(rig)?;
    // Authority first: nothing about the rig is checked for strangers.
    if authority.address().as_array() != &g.authority {
        return Err(HdError::Unauthorized.into());
    }

    match g.state {
        rig_state::IDLE => {}
        rig_state::FROZEN => return Err(HdError::RigFrozen.into()),
        _ => return Err(HdError::InvalidRigState.into()),
    }
    validate_plan(&plan, now)?;
    if now > g.caps_expiry_ts.get() {
        return Err(HdError::CapsExpired.into());
    }
    if plan.max_ev_cost > g.cap_max_cost.get() || plan.dig_lamports > g.cap_round.get() {
        return Err(HdError::PlanExceedsCaps.into());
    }

    authorize_signal(&mut g, authority, rest.first(), auth, |counter| {
        Preimage::Plan(message::plan_preimage(&rig_address, counter, &plan))
    })?;

    let shift_id = g
        .shift_id
        .get()
        .checked_add(1)
        .ok_or(HdError::MathOverflow)?;
    g.shift_id.set(shift_id);
    g.state = rig_state::ARMED;
    g.plan_max_ev_cost.set(plan.max_ev_cost);
    g.plan_dig_lamports.set(plan.dig_lamports);
    g.plan_split_tiles = plan.split;
    g.plan_solo_tiles = plan.solo;
    g.plan_lease_rounds = plan.lease;
    g.plan_flags = plan.flags;
    g.plan_window_start_ts.set(plan.window_start);
    g.plan_window_end_ts.set(plan.window_end);
    g.lease_from_round.set(0);
    g.lease_to_round.set(0);
    g.gap_count.set(0);
    g.spent_shift.set(0);
    g.shift_start_round.set(board.round_id);
    g.shift_dark_rounds.set(0);
    g.shift_rounds_dug.set(0);
    g.shift_open = 1;
    g.break_reason = state::break_reason::COMPLETED;
    g.shift_start_ts.set(now);
    let (ws, sw) = logic::roll_week(g.week_start_ts.get(), g.spent_week.get(), now);
    g.week_start_ts.set(ws);
    g.spent_week.set(sw);
    drop(g);

    events::shift_armed(&rig_address, shift_id);
    Ok(())
}
