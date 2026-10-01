//! `close_shift_log` (tag 31, v1.3): return a sealed ShiftLog's rent 30 days
//! after its shift ended. `INTERFACE.md` §12.6 is the contract.
//!
//! Accounts:
//! 0. `[writable]` ShiftLog (closed)
//! 1. `[writable]` rent recipient: the address whose first 16 bytes the log
//!    stores (`payer_prefix`, the `end_shift` caller that paid the rent). For
//!    a log sealed before v1.3 (prefix all-zero): the rig's authority.
//! 2. `[]` FocusBond PDA `["bond", log.rig, log.shift_id]` (must not hold a
//!    bond)
//!
//! Data: empty.
//!
//! Permissionless: the lamports go only to the recipient the log itself
//! names, so anyone (the payer, a crank) may trigger it. Two things keep it
//! safe:
//!
//! * a Focus Bond resolves from this log (`release_focus_bond`,
//!   `forfeit_focus_bond`), so the log cannot go while a bond on its shift is
//!   still there. Without that check, closing the log of a completed shift
//!   would make its bond look abandoned and let anyone forfeit it;
//! * a rig address never reuses a shift id (the v1.3 tombstone), so the freed
//!   address is never written again and no later shift can be confused with
//!   this one.
//!
//! Emits `ShiftLogClosed{shift_log, rig, shift_id, lamports}`.

use pinocchio::{error::ProgramError, AccountView, ProgramResult};

use crate::{
    error::HdError,
    events::{self, log_data},
    instructions::end_shift::payer_prefix,
    pda,
    state::{self, FocusBond, ShiftLog},
    util::{clock, Reader},
    BOND_SEED, ID, RIG_SEED,
};

/// A ShiftLog may be closed from `end_ts + SHIFT_LOG_TTL_SECS` (30 days).
pub const SHIFT_LOG_TTL_SECS: i64 = 30 * 86_400;

/// Handler.
pub fn process_close(accounts: &mut [AccountView], data: &[u8]) -> ProgramResult {
    let [shift_log, recipient, bond, ..] = accounts else {
        return Err(ProgramError::NotEnoughAccountKeys);
    };
    Reader::new(data).finish()?;
    let now = clock()?.unix_timestamp;
    let (rig, shift_id, prefix) = {
        let l = state::load::<ShiftLog>(shift_log)?;
        let unlock = l
            .end_ts
            .get()
            .checked_add(SHIFT_LOG_TTL_SECS)
            .ok_or(HdError::MathOverflow)?;
        if now < unlock {
            return Err(HdError::ShiftLogNotExpired.into());
        }
        (l.rig, l.shift_id.get(), l.payer_prefix)
    };

    // The shift's Focus Bond, if one is still there, needs this log.
    let id = shift_id.to_le_bytes();
    let (bond_pda, _) = pda::find(&[BOND_SEED, &rig, &id], &ID);
    if bond.address() != &bond_pda {
        return Err(ProgramError::InvalidSeeds);
    }
    if state::is_initialized::<FocusBond>(bond) {
        return Err(HdError::ShiftLogInUse.into());
    }

    // The rent goes back to whoever paid it.
    if prefix != [0u8; 16] {
        if payer_prefix(recipient.address().as_array()) != prefix {
            return Err(HdError::Unauthorized.into());
        }
    } else {
        // Sealed before v1.3: no payer on record. The rig's authority is the
        // wallet whose Rig PDA this log names.
        let (rig_pda, _) = pda::find(&[RIG_SEED, recipient.address().as_ref()], &ID);
        if rig_pda.as_array() != &rig {
            return Err(HdError::Unauthorized.into());
        }
    }

    let log_address = *shift_log.address();
    let lamports = shift_log.lamports();
    pda::close_account(shift_log, recipient)?;
    log_data(&events::shift_log_closed_bytes(
        &log_address,
        &rig,
        shift_id,
        lamports,
    ));
    Ok(())
}
