//! `record_heartbeats` (tag 7): the same heartbeat verification as `dig`,
//! updating counters, leases and dark rounds, with no CPI (focus-only
//! shifts, Stack, or rounds where the gate is closed).
//!
//! Data: `n u8 | n x entry(20)` (same entry as `dig`; `hb_ix` must name a
//! precompile instruction).
//!
//! Accounts:
//! 0. `[]` ORE Board
//! 1. `[]` Instructions sysvar
//! 2.. `[writable]` rig_i
//!
//! A rig whose heartbeat fails is skipped with `RigSkipped`; a malformed
//! account list or a duplicate rig fails the transaction.

use pinocchio::{error::ProgramError, AccountView, ProgramResult};

use crate::{
    error::HdError,
    events,
    instructions::{apply_heartbeat, HeartbeatEntry, ENTRY_LEN, NO_HEARTBEAT},
    ore,
    state::{self, rig_state, Rig},
};

/// Upper bound on rigs per call.
pub const MAX_RIGS: usize = 32;
/// Shared accounts before the rigs.
pub const FIXED_ACCOUNTS: usize = 2;

/// Handler.
pub fn process(accounts: &mut [AccountView], data: &[u8]) -> ProgramResult {
    let (&n_rigs, entries) = data.split_first().ok_or(HdError::InvalidInstruction)?;
    let n = usize::from(n_rigs);
    if n == 0 || n > MAX_RIGS || entries.len() != n.saturating_mul(ENTRY_LEN) {
        return Err(HdError::InvalidInstruction.into());
    }
    if accounts.len() != FIXED_ACCOUNTS.saturating_add(n) {
        return Err(HdError::InvalidInstruction.into());
    }
    let (fixed, rigs) = accounts.split_at_mut(FIXED_ACCOUNTS);
    let [board, ix_sysvar] = fixed else {
        return Err(ProgramError::NotEnoughAccountKeys);
    };
    let board_round = ore::read_board(board)?.round_id;
    p256_introspect::check_instructions_sysvar(ix_sysvar)?;

    for i in 1..n {
        let a = rigs.get(i).ok_or(HdError::InvalidInstruction)?;
        if rigs
            .get(..i)
            .ok_or(HdError::InvalidInstruction)?
            .iter()
            .any(|b| b.address() == a.address())
        {
            return Err(HdError::DuplicateRig.into());
        }
    }

    for (i, rig) in rigs.iter_mut().enumerate() {
        let start = i.saturating_mul(ENTRY_LEN);
        let entry = HeartbeatEntry::parse(
            entries
                .get(start..start.saturating_add(ENTRY_LEN))
                .ok_or(HdError::InvalidInstruction)?,
        )?;
        let rig_address = *rig.address();
        let mut g = state::load_mut::<Rig>(rig)?;
        let outcome = match g.state {
            _ if entry.hb_ix == NO_HEARTBEAT => Err(HdError::InvalidHeartbeat.code()),
            rig_state::ARMED | rig_state::DOWN | rig_state::COOLING => {
                apply_heartbeat(&mut g, &rig_address, ix_sysvar, &entry, board_round)
            }
            rig_state::FROZEN => Err(HdError::RigFrozen.code()),
            _ => Err(HdError::RigNotArmed.code()),
        };
        drop(g);
        if let Err(code) = outcome {
            events::rig_skipped(&rig_address, board_round, code);
        }
    }
    Ok(())
}
