//! `propose_config` (tag 12) and `apply_config` (tag 13): a 72-hour
//! timelock on every Config change.
//!
//! `propose_config` accounts: 0 `[signer]` governance, 1 `[writable]` Config.
//! Data (43 bytes): `registrar [32] | crank_fee u64 | bury_bps u16 | paused u8`.
//! Sets `pending_*` with `pending_eta_slot = slot + TIMELOCK_SLOTS`. A new
//! proposal replaces a pending one and restarts the clock. Pausing is
//! risk-reducing, so `paused = 1` also takes effect immediately (circuit
//! breaker); un-pausing and every other field wait for the timelock.
//!
//! `apply_config` accounts: 0 `[writable]` Config. Data: empty. Anyone may
//! apply once `slot >= pending_eta_slot`.

use pinocchio::{error::ProgramError, AccountView, ProgramResult};

use crate::{
    error::HdError,
    instructions::initialize_config::MAX_BPS,
    state::{self, Config, U16, U64},
    util::{clock, require_signer, Reader},
    CONFIG_ID,
};

/// 72 hours of slots at the fastest slot time we assume (300 ms): the delay
/// is at least 72 h of wall time as long as slots are not faster than that
/// (at the nominal 400 ms it is 96 h).
pub const TIMELOCK_SLOTS: u64 = 72 * 60 * 60 * 1000 / 300;

/// Handler for `propose_config`.
pub fn process_propose(accounts: &mut [AccountView], data: &[u8]) -> ProgramResult {
    let [governance, config, ..] = accounts else {
        return Err(ProgramError::NotEnoughAccountKeys);
    };
    let mut r = Reader::new(data);
    let registrar = r.array::<32>()?;
    let crank_fee = r.u64()?;
    let bury_bps = r.u16()?;
    let paused = r.u8()?;
    r.finish()?;

    require_signer(governance)?;
    if config.address() != &CONFIG_ID {
        return Err(HdError::InvalidAccountTag.into());
    }
    let mut c = state::load_mut::<Config>(config)?;
    if governance.address().as_array() != &c.governance {
        return Err(HdError::Unauthorized.into());
    }
    if crank_fee > c.executor_fee.get() || bury_bps > MAX_BPS || paused > 1 {
        return Err(HdError::InvalidInstruction.into());
    }
    let eta = clock()?
        .slot
        .checked_add(TIMELOCK_SLOTS)
        .ok_or(HdError::MathOverflow)?;
    c.pending_exists = 1;
    c.pending_eta_slot = U64::new(eta);
    c.pending_registrar = registrar;
    c.pending_crank_fee = U64::new(crank_fee);
    c.pending_bury_bps = U16::new(bury_bps);
    c.pending_paused = paused;
    if paused == 1 {
        c.paused = 1;
    }
    Ok(())
}

/// Handler for `apply_config`.
pub fn process_apply(accounts: &mut [AccountView], data: &[u8]) -> ProgramResult {
    let [config, ..] = accounts else {
        return Err(ProgramError::NotEnoughAccountKeys);
    };
    Reader::new(data).finish()?;
    if config.address() != &CONFIG_ID {
        return Err(HdError::InvalidAccountTag.into());
    }
    let mut c = state::load_mut::<Config>(config)?;
    if c.pending_exists != 1 {
        return Err(HdError::InvalidInstruction.into());
    }
    if clock()?.slot < c.pending_eta_slot.get() {
        return Err(HdError::TimelockNotElapsed.into());
    }
    // Re-check the invariant at apply time (executor_fee is immutable, but
    // keep the check next to the write).
    if c.pending_crank_fee.get() > c.executor_fee.get() {
        return Err(HdError::InvalidInstruction.into());
    }
    c.registrar = c.pending_registrar;
    c.crank_fee = c.pending_crank_fee;
    c.bury_bps = c.pending_bury_bps;
    c.paused = c.pending_paused;
    c.pending_exists = 0;
    c.pending_eta_slot = U64::new(0);
    c.pending_registrar = [0; 32];
    c.pending_crank_fee = U64::new(0);
    c.pending_bury_bps = U16::new(0);
    c.pending_paused = 0;
    Ok(())
}
