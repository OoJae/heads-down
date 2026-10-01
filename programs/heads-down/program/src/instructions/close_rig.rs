//! `close_rig` (tag 14).
//!
//! Accounts:
//! 0. `[signer, writable]` authority (receives the rent)
//! 1. `[writable]` Rig (state Idle or Frozen)
//! 2. `[writable]` SeekerSeat `[b"seeker", rig.sgt_mint]` (required for a
//!    Seeker-tier rig)
//!
//! Data: empty.
//!
//! The rig's seat (if it still points at this rig) is zeroed, drained to the
//! authority stored in state, and handed back to System. So is a rig that
//! never armed a shift and never accepted a P-256 message.
//!
//! **v1.3: the tombstone.** Any other rig is zeroed and shrunk to a 32-byte
//! [`RigTombstone`] that keeps only its `shift_id` and `hb_counter`; the
//! authority receives every lamport above that tombstone's rent. A later
//! `register_rig` for the same wallet grows it back into a Rig that resumes
//! from those two counters. Without it a re-registered rig would restart at
//! `shift_id` 1 and `hb_counter` 0, so that:
//!
//! * `end_shift` would fail on the ShiftLog its earlier life left at
//!   `["shift", rig, 1]`;
//! * old P-256 messages (same rig address, same shift id, counter above 0)
//!   would verify again: a replayed BREAK or FREEZE would break the new
//!   shift and forfeit its Focus Bond;
//! * a Stack seat bound to shift `k` would accept a brand-new shift `k`,
//!   forgetting a BREAK the old one recorded.
//!
//! Emits `RigClosed{rig}` either way.

use core::mem::size_of;

use pinocchio::{error::ProgramError, AccountView, ProgramResult};

use crate::{
    error::HdError,
    events, pda,
    state::{self, rig_state, Header, Rig, RigTombstone, SeekerSeat, U64},
    util::require_rig_authority,
    ID, SEEKER_SEED,
};

/// Handler.
pub fn process(accounts: &mut [AccountView], data: &[u8]) -> ProgramResult {
    let [authority, rig, rest @ ..] = accounts else {
        return Err(ProgramError::NotEnoughAccountKeys);
    };
    crate::util::Reader::new(data).finish()?;
    let (tier, sgt_mint, bump, shift_id, hb_counter) = {
        let g = state::load::<Rig>(rig)?;
        require_rig_authority(&g, authority)?;
        if g.state != rig_state::IDLE && g.state != rig_state::FROZEN {
            return Err(HdError::InvalidRigState.into());
        }
        (
            g.tier,
            g.sgt_mint,
            g.header.bump,
            g.shift_id.get(),
            g.hb_counter.get(),
        )
    };
    if !authority.is_writable() || !rig.is_writable() {
        return Err(ProgramError::InvalidAccountData);
    }

    if tier == 1 {
        let seat = rest.first_mut().ok_or(ProgramError::NotEnoughAccountKeys)?;
        let (seat_pda, _) = pda::find(&[SEEKER_SEED, &sgt_mint], &ID);
        if seat.address() != &seat_pda {
            return Err(HdError::InvalidSgt.into());
        }
        let points_here = state::is_initialized::<SeekerSeat>(seat) && {
            let s = state::load::<SeekerSeat>(seat)?;
            s.rig == *rig.address().as_array()
        };
        if points_here {
            pda::close_account(seat, authority)?;
        }
    }
    let rig_address = *rig.address();
    if shift_id == 0 && hb_counter == 0 {
        // Never armed, never signed for: nothing a later rig at this address
        // could collide with or replay.
        pda::close_account(rig, authority)?;
    } else {
        {
            let mut d = rig.try_borrow_mut()?;
            d.fill(0);
            let head = d
                .get_mut(..size_of::<RigTombstone>())
                .ok_or(HdError::InvalidAccountTag)?;
            let t = bytemuck::try_from_bytes_mut::<RigTombstone>(head)
                .map_err(|_| HdError::InvalidAccountTag)?;
            t.header = Header::new(state::tag::RIG_TOMBSTONE, bump);
            t.shift_id = U64::new(shift_id);
            t.hb_counter = U64::new(hb_counter);
        }
        pda::shrink_account(rig, authority, size_of::<RigTombstone>())?;
    }
    events::rig_closed(&rig_address);
    Ok(())
}
