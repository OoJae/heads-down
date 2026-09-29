//! `break_shift` (tag 8), `freeze_rig` (tag 9), `unfreeze_rig` (tag 10).
//!
//! `break_shift` / `freeze_rig` accounts:
//! 0. `[writable]` Rig
//! 1. `[signer if mode 0]` authority (must equal `rig.authority`)
//! 2. `[]` Instructions sysvar (mode 1 only)
//!
//! Data: `mode u8 (0 wallet, 1 P-256) | reason u8` then, for mode 1,
//! `counter u64 | p256_ix u8 | p256_sig_index u8` (2 or 12 bytes). The
//! P-256 message is BREAK (kind 2) / FREEZE (kind 3) over
//! `(rig, counter, rig.shift_id, reason)`.
//!
//! * BREAK reasons: 1 pickup and 2 screen_on → Cooling (a fresh heartbeat
//!   resumes the shift); 4 lease_lapse, 5 budget, 6 manual → Broken (only
//!   `end_shift` moves on). Allowed from Armed / Down / Cooling.
//! * FREEZE: any state → Frozen; only the wallet can unfreeze.
//!
//! `unfreeze_rig` accounts: 0 `[writable]` Rig, 1 `[signer]` authority.
//! Data: empty. Frozen → Idle, or → Broken if a shift is still open (so
//! `end_shift` can seal it with reason `freeze`).

use pinocchio::{error::ProgramError, AccountView, ProgramResult};

use crate::{
    error::HdError,
    message::{self, kind},
    state::{self, break_reason, rig_state, Rig},
    util::{authorize_signal, require_rig_authority, Preimage, Reader, SignalAuth},
};

fn read_signal(data: &[u8]) -> Result<(SignalAuth, u8), HdError> {
    let mut r = Reader::new(data);
    let p256 = SignalAuth::parse_mode(r.u8()?)?;
    let reason = r.u8()?;
    let auth = if p256 {
        SignalAuth::read_p256(&mut r)?
    } else {
        SignalAuth::Wallet
    };
    r.finish()?;
    Ok((auth, reason))
}

/// Handler for `break_shift`.
pub fn process_break(accounts: &mut [AccountView], data: &[u8]) -> ProgramResult {
    let [rig, authority, rest @ ..] = accounts else {
        return Err(ProgramError::NotEnoughAccountKeys);
    };
    let (auth, reason) = read_signal(data)?;
    let next_state = match reason {
        break_reason::PICKUP | break_reason::SCREEN_ON => rig_state::COOLING,
        break_reason::LEASE_LAPSE | break_reason::BUDGET | break_reason::MANUAL => {
            rig_state::BROKEN
        }
        _ => return Err(HdError::InvalidInstruction.into()),
    };
    let rig_address = *rig.address();
    let mut g = state::load_mut::<Rig>(rig)?;
    match g.state {
        rig_state::ARMED | rig_state::DOWN | rig_state::COOLING => {}
        rig_state::FROZEN => return Err(HdError::RigFrozen.into()),
        _ => return Err(HdError::InvalidRigState.into()),
    }
    let shift_id = g.shift_id.get();
    authorize_signal(&mut g, authority, rest.first(), auth, |counter| {
        Preimage::Signal(message::signal_preimage(
            kind::BREAK,
            &rig_address,
            counter,
            shift_id,
            reason,
        ))
    })?;
    g.state = next_state;
    g.break_reason = reason;
    Ok(())
}

/// Handler for `freeze_rig`.
pub fn process_freeze(accounts: &mut [AccountView], data: &[u8]) -> ProgramResult {
    let [rig, authority, rest @ ..] = accounts else {
        return Err(ProgramError::NotEnoughAccountKeys);
    };
    let (auth, reason) = read_signal(data)?;
    let rig_address = *rig.address();
    let mut g = state::load_mut::<Rig>(rig)?;
    let shift_id = g.shift_id.get();
    authorize_signal(&mut g, authority, rest.first(), auth, |counter| {
        Preimage::Signal(message::signal_preimage(
            kind::FREEZE,
            &rig_address,
            counter,
            shift_id,
            reason,
        ))
    })?;
    if g.state != rig_state::FROZEN && g.shift_open == 1 {
        g.break_reason = break_reason::FREEZE;
    }
    g.state = rig_state::FROZEN;
    Ok(())
}

/// Handler for `unfreeze_rig`.
pub fn process_unfreeze(accounts: &mut [AccountView], data: &[u8]) -> ProgramResult {
    let [rig, authority, ..] = accounts else {
        return Err(ProgramError::NotEnoughAccountKeys);
    };
    Reader::new(data).finish()?;
    let mut g = state::load_mut::<Rig>(rig)?;
    require_rig_authority(&g, authority)?;
    if g.state != rig_state::FROZEN {
        return Err(HdError::InvalidRigState.into());
    }
    g.state = if g.shift_open == 1 {
        rig_state::BROKEN
    } else {
        rig_state::IDLE
    };
    Ok(())
}
