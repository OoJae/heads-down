//! `end_shift` (tag 11): seal the open shift into a ShiftLog PDA and do the
//! streak / freeze accounting.
//!
//! Accounts:
//! 0. `[signer, writable]` caller (pays the ShiftLog rent)
//! 1. `[writable]` Rig
//! 2. `[writable]` ShiftLog PDA `[b"shift", rig, shift_id u64 LE]`
//! 3. `[]` ORE Board (for `end_round`)
//! 4. `[]` System program
//!
//! Data: empty.
//!
//! Emits `ShiftEnded` (tag 4, v1) and then `ShiftEndedV2` (tag 10), which
//! adds `start_round`, `end_round` and `mode`.
//!
//! v1.3: the log records the first 16 bytes of the caller's address
//! (`payer_prefix`), so `close_shift_log` can return the rent to whoever
//! paid it, 30 days later.
//!
//! The rig's authority may end its shift at any time. Anyone else may end it
//! only once `now > plan_window_end_ts` **and** the heartbeat lease has
//! expired by the grace (`lease_to_round + 3 < board.round_id`).
//!
//! Break reason: the stored one for Cooling / Broken, `freeze` for Frozen;
//! for Armed / Down, `lease_lapse` if no lease was ever granted, `manual` if
//! the authority ended it inside the window, else `completed`. A shift
//! qualifies for the streak iff its reason is `completed` and it had at
//! least one dark round.

use pinocchio::{error::ProgramError, instruction::seeds, AccountView, ProgramResult};

use crate::{
    error::HdError,
    events, logic, ore, pda,
    state::{self, break_reason, plan_flags, rig_state, Header, Rig, ShiftLog},
    util::{clock, require_signer},
    ID, SHIFT_SEED,
};

/// Seconds per day (streak days are UTC unix days).
pub const DAY_SECONDS: i64 = 86_400;

/// The first 16 bytes of `address`: what a ShiftLog keeps of its rent payer
/// (v1.3). 128 bits bind the refund to that address as firmly as a full
/// address would: finding another address with the same prefix is a
/// 2^128 search.
pub fn payer_prefix(address: &[u8; 32]) -> [u8; 16] {
    let mut out = [0u8; 16];
    for (o, b) in out.iter_mut().zip(address.iter()) {
        *o = *b;
    }
    out
}

/// Handler.
pub fn process(accounts: &mut [AccountView], data: &[u8]) -> ProgramResult {
    let [caller, rig, shift_log, board, system_program, ..] = accounts else {
        return Err(ProgramError::NotEnoughAccountKeys);
    };
    crate::util::Reader::new(data).finish()?;
    require_signer(caller)?;

    let end_round = ore::read_board(board)?.round_id;
    let now = clock()?.unix_timestamp;
    let rig_address = *rig.address();

    // Read what we need, decide, then create the log (the rig borrow is
    // released around the System CPI for clarity; the rig is not passed).
    let (shift_id, reason, mode, dark, gaps, rounds_dug, lamports, start_round, start_ts) = {
        let g = state::load::<Rig>(rig)?;
        if g.shift_open != 1 {
            return Err(HdError::InvalidRigState.into());
        }
        let is_authority = caller.address().as_array() == &g.authority;
        let inside_window = now <= g.plan_window_end_ts.get();
        let lease_live = g
            .lease_to_round
            .get()
            .saturating_add(logic::PERMISSIONLESS_END_GRACE_ROUNDS)
            >= end_round;
        if !is_authority && (inside_window || lease_live) {
            return Err(HdError::Unauthorized.into());
        }
        let (dark, gaps) = logic::settle_shift(
            g.shift_dark_rounds.get(),
            u64::from(g.gap_count.get()),
            g.lease_to_round.get(),
            g.shift_start_round.get(),
            end_round,
        );
        let reason = match g.state {
            rig_state::BROKEN | rig_state::COOLING => g.break_reason,
            rig_state::FROZEN => break_reason::FREEZE,
            _ if dark == 0 => break_reason::LEASE_LAPSE,
            _ if is_authority && inside_window => break_reason::MANUAL,
            _ => break_reason::COMPLETED,
        };
        let mode = if g.plan_flags & plan_flags::FOCUS_ONLY != 0 {
            2
        } else if g.plan_flags & plan_flags::DAY != 0 {
            1
        } else {
            0
        };
        (
            g.shift_id.get(),
            reason,
            mode,
            dark,
            gaps,
            g.shift_rounds_dug.get(),
            g.spent_shift.get(),
            g.shift_start_round.get(),
            g.shift_start_ts.get(),
        )
    };

    let id = shift_id.to_le_bytes();
    let (log_pda, bump) = pda::find(&[SHIFT_SEED, rig_address.as_ref(), &id], &ID);
    if shift_log.address() != &log_pda {
        return Err(ProgramError::InvalidSeeds);
    }
    let bump_seed = [bump];
    let signer_seeds = seeds!(SHIFT_SEED, rig_address.as_ref(), &id, &bump_seed);
    pda::create_pda_account(
        caller,
        shift_log,
        system_program,
        core::mem::size_of::<ShiftLog>(),
        &signer_seeds,
    )?;
    {
        let mut l = state::load_uninit_mut::<ShiftLog>(shift_log)?;
        l.header = Header::new(state::tag::SHIFT_LOG, bump);
        l.rig = *rig_address.as_array();
        l.shift_id.set(shift_id);
        l.start_round.set(start_round);
        l.end_round.set(end_round);
        l.dark_rounds.set(dark);
        l.rounds_dug.set(rounds_dug);
        l.lamports_deployed.set(lamports);
        l.break_reason = reason;
        l.mode = mode;
        l.start_ts.set(start_ts);
        l.end_ts.set(now);
        l.payer_prefix = payer_prefix(caller.address().as_array());
    }

    let mut g = state::load_mut::<Rig>(rig)?;
    g.shift_dark_rounds.set(dark);
    g.gap_count.set(u32::try_from(gaps).unwrap_or(u32::MAX));
    let v = g.lifetime_dark_rounds.get().saturating_add(dark);
    g.lifetime_dark_rounds.set(v);
    let qualifies = reason == break_reason::COMPLETED && dark > 0;
    let (streak, freezes, last_day) = logic::update_streak(
        g.streak.get(),
        g.freezes_left,
        g.last_shift_day.get(),
        now.div_euclid(DAY_SECONDS),
        qualifies,
    );
    g.streak.set(streak);
    g.freezes_left = freezes;
    g.last_shift_day.set(last_day);
    g.shift_open = 0;
    if g.state != rig_state::FROZEN {
        g.state = rig_state::IDLE;
    }
    drop(g);

    events::shift_ended(
        &rig_address,
        &events::ShiftSummary {
            shift_id,
            dark_rounds: dark,
            rounds_dug,
            lamports,
            reason,
            start_round,
            end_round,
            mode,
        },
    );
    Ok(())
}
