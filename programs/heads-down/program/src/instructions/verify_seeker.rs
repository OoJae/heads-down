//! `verify_seeker` (tag 2): in-program SGT verification → Seeker tier.
//!
//! Accounts:
//! 0. `[signer, writable]` authority (pays the seat rent on first verification)
//! 1. `[writable]` Rig (`rig.authority == authority`)
//! 2. `[writable]` SeekerSeat PDA `[b"seeker", sgt_mint]`
//! 3. `[]` SGT token account (Token-2022, owner field = authority)
//! 4. `[]` SGT mint
//! 5. `[]` System program
//! 6. `[writable]` previous rig (only when the seat points at another rig)
//!
//! Data: empty.
//!
//! `sgt_verify::verify_sgt(token_account, mint, authority)` proves the
//! signer holds a genuine SGT (its errors keep their `0x5347_xxxx` codes).
//! The seat is keyed by the **mint** (one per device) and points at exactly
//! one rig. If it already points at another rig (the SGT moved to this
//! wallet), that rig must be supplied: it is downgraded to tier 0 (if it
//! still claims this mint) and the seat is re-pointed here.

use pinocchio::{error::ProgramError, instruction::seeds, AccountView, ProgramResult};

use crate::{
    error::HdError,
    events, pda,
    state::{self, Header, Rig, SeekerSeat},
    util::{clock, require_rig_authority},
    ID, SEEKER_SEED,
};

/// Handler.
pub fn process(accounts: &mut [AccountView], data: &[u8]) -> ProgramResult {
    let [authority, rig, seat, token_account, mint, system_program, rest @ ..] = accounts else {
        return Err(ProgramError::NotEnoughAccountKeys);
    };
    crate::util::Reader::new(data).finish()?;
    {
        let g = state::load::<Rig>(rig)?;
        require_rig_authority(&g, authority)?;
    }
    if !rig.is_writable() {
        return Err(ProgramError::InvalidAccountData);
    }

    let info = sgt_verify::verify_sgt(token_account, mint, authority.address())?;
    let (seat_pda, bump) = pda::find(&[SEEKER_SEED, info.mint.as_ref()], &ID);
    if seat.address() != &seat_pda {
        return Err(ProgramError::InvalidSeeds);
    }
    let slot = clock()?.slot;
    let rig_address = *rig.address();

    if state::is_initialized::<SeekerSeat>(seat) {
        let previous = {
            let s = state::load::<SeekerSeat>(seat)?;
            if s.sgt_mint != *info.mint.as_array() {
                return Err(HdError::InvalidSgt.into());
            }
            s.rig
        };
        if previous != *rig_address.as_array() {
            // Re-point: the previous rig must be supplied and is downgraded.
            let old = rest.first_mut().ok_or(HdError::SeatTaken)?;
            if old.address().as_array() != &previous {
                return Err(HdError::SeatTaken.into());
            }
            if state::is_initialized::<Rig>(old) {
                let mut o = state::load_mut::<Rig>(old)?;
                if o.tier == 1 && o.sgt_mint == *info.mint.as_array() {
                    o.tier = 0;
                    o.sgt_mint = [0; 32];
                }
            }
        }
        let mut s = state::load_mut::<SeekerSeat>(seat)?;
        s.rig = *rig_address.as_array();
        s.authority = *authority.address().as_array();
        s.member_number.set(info.member_number);
        s.verified_slot.set(slot);
    } else {
        let bump_seed = [bump];
        let signer_seeds = seeds!(SEEKER_SEED, info.mint.as_ref(), &bump_seed);
        pda::create_pda_account(
            authority,
            seat,
            system_program,
            core::mem::size_of::<SeekerSeat>(),
            &signer_seeds,
        )?;
        let mut s = state::load_uninit_mut::<SeekerSeat>(seat)?;
        s.header = Header::new(state::tag::SEEKER_SEAT, bump);
        s.sgt_mint = *info.mint.as_array();
        s.rig = *rig_address.as_array();
        s.authority = *authority.address().as_array();
        s.member_number.set(info.member_number);
        s.verified_slot.set(slot);
    }

    {
        let mut g = state::load_mut::<Rig>(rig)?;
        g.tier = 1;
        g.sgt_mint = *info.mint.as_array();
    }
    events::seeker_verified(&rig_address, &info.mint, info.member_number);
    Ok(())
}
