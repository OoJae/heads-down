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
//! The rig (and its seat, if the seat still points at this rig) is zeroed,
//! drained to the authority stored in state, and handed back to System.

use pinocchio::{error::ProgramError, AccountView, ProgramResult};

use crate::{
    error::HdError,
    pda,
    state::{self, rig_state, Rig, SeekerSeat},
    util::require_rig_authority,
    ID, SEEKER_SEED,
};

/// Handler.
pub fn process(accounts: &mut [AccountView], data: &[u8]) -> ProgramResult {
    let [authority, rig, rest @ ..] = accounts else {
        return Err(ProgramError::NotEnoughAccountKeys);
    };
    crate::util::Reader::new(data).finish()?;
    let (tier, sgt_mint) = {
        let g = state::load::<Rig>(rig)?;
        require_rig_authority(&g, authority)?;
        if g.state != rig_state::IDLE && g.state != rig_state::FROZEN {
            return Err(HdError::InvalidRigState.into());
        }
        (g.tier, g.sgt_mint)
    };
    if !authority.is_writable() {
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
    pda::close_account(rig, authority)
}
