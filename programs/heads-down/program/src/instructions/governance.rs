//! `propose_config` (tag 12) and `apply_config` (tag 13): a 72-hour
//! timelock on every Config change, and (v1.3) the timelocked governance
//! rotation: `propose_governance` (tag 28), `accept_governance` (tag 29) and
//! `cancel_governance` (tag 30).
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
//!
//! **Governance rotation (v1.3).** `Config.governance` moves in two steps so
//! that neither a stolen key nor a typo can take or lose it at once:
//!
//! 1. the current governance names its successor (`propose_governance`); the
//!    name and the first slot it may take over are public in the Config;
//! 2. after the same 72-hour timelock, the **successor itself** signs
//!    `accept_governance`. An address nobody controls can never accept, so a
//!    mistyped successor leaves the current governance in place.
//!
//! Until the successor accepts, the current governance keeps every power,
//! including the immediate pause, and may drop the rotation
//! (`cancel_governance`) or name another successor (which restarts the
//! clock).

use pinocchio::{error::ProgramError, AccountView, ProgramResult};

use crate::{
    error::HdError,
    events::{self, log_data},
    instructions::initialize_config::MAX_BPS,
    state::{self, Config, U16, U64},
    util::{clock, require_signer, Reader},
    CONFIG_ID,
};

/// 72 hours of slots at the fastest slot time we assume (300 ms): the delay
/// is at least 72 h of wall time as long as slots are not faster than that
/// (at the nominal 400 ms it is 96 h).
pub const TIMELOCK_SLOTS: u64 = 72 * 60 * 60 * 1000 / 300;

/// "No address": the value of `pending_governance` when no rotation is
/// pending.
const NONE: [u8; 32] = [0; 32];

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

/// Forget the pending config proposal.
fn clear_pending_config(c: &mut Config) {
    c.pending_exists = 0;
    c.pending_eta_slot = U64::new(0);
    c.pending_registrar = [0; 32];
    c.pending_crank_fee = U64::new(0);
    c.pending_bury_bps = U16::new(0);
    c.pending_paused = 0;
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
    clear_pending_config(&mut c);
    Ok(())
}

// ---- v1.3: governance rotation --------------------------------------------------

/// Handler for `propose_governance` (tag 28).
///
/// Accounts: 0 `[signer]` governance (the current one), 1 `[writable]` Config.
/// Data (32 bytes after the tag): `new_governance [32]`.
///
/// Sets `pending_governance` and `pending_governance_eta_slot = slot +
/// TIMELOCK_SLOTS`. A new proposal replaces a pending one and restarts the
/// clock. The zero address and the current governance are refused. Emits
/// `GovernanceProposed`.
pub fn process_propose_governance(accounts: &mut [AccountView], data: &[u8]) -> ProgramResult {
    let [governance, config, ..] = accounts else {
        return Err(ProgramError::NotEnoughAccountKeys);
    };
    let mut r = Reader::new(data);
    let new_governance = r.array::<32>()?;
    r.finish()?;

    require_signer(governance)?;
    if config.address() != &CONFIG_ID {
        return Err(HdError::InvalidAccountTag.into());
    }
    let mut c = state::load_mut::<Config>(config)?;
    if governance.address().as_array() != &c.governance {
        return Err(HdError::Unauthorized.into());
    }
    if new_governance == NONE || new_governance == c.governance {
        return Err(HdError::InvalidInstruction.into());
    }
    let eta = clock()?
        .slot
        .checked_add(TIMELOCK_SLOTS)
        .ok_or(HdError::MathOverflow)?;
    c.pending_governance = new_governance;
    c.pending_governance_eta_slot = U64::new(eta);
    drop(c);
    log_data(&events::governance_proposed_bytes(
        governance.address(),
        &new_governance,
        eta,
    ));
    Ok(())
}

/// Handler for `accept_governance` (tag 29).
///
/// Accounts: 0 `[signer]` the pending governance, 1 `[writable]` Config.
/// Data: empty.
///
/// Only the pending governance itself may accept, and only once `slot >=
/// pending_governance_eta_slot`. It becomes `Config.governance`; the
/// rotation is cleared, and so is any pending config proposal (it was the
/// outgoing governance's; the new one proposes its own). `paused` is not
/// touched. Emits `GovernanceAccepted`.
pub fn process_accept_governance(accounts: &mut [AccountView], data: &[u8]) -> ProgramResult {
    let [new_governance, config, ..] = accounts else {
        return Err(ProgramError::NotEnoughAccountKeys);
    };
    Reader::new(data).finish()?;

    require_signer(new_governance)?;
    if config.address() != &CONFIG_ID {
        return Err(HdError::InvalidAccountTag.into());
    }
    let mut c = state::load_mut::<Config>(config)?;
    if c.pending_governance == NONE {
        return Err(HdError::InvalidInstruction.into());
    }
    if new_governance.address().as_array() != &c.pending_governance {
        return Err(HdError::Unauthorized.into());
    }
    if clock()?.slot < c.pending_governance_eta_slot.get() {
        return Err(HdError::TimelockNotElapsed.into());
    }
    let previous = c.governance;
    c.governance = c.pending_governance;
    c.pending_governance = NONE;
    c.pending_governance_eta_slot = U64::new(0);
    clear_pending_config(&mut c);
    drop(c);
    log_data(&events::governance_accepted_bytes(
        new_governance.address(),
        &previous,
    ));
    Ok(())
}

/// Handler for `cancel_governance` (tag 30).
///
/// Accounts: 0 `[signer]` governance (the current one), 1 `[writable]` Config.
/// Data: empty.
///
/// Drops the pending rotation (there must be one). Emits
/// `GovernanceCancelled`.
pub fn process_cancel_governance(accounts: &mut [AccountView], data: &[u8]) -> ProgramResult {
    let [governance, config, ..] = accounts else {
        return Err(ProgramError::NotEnoughAccountKeys);
    };
    Reader::new(data).finish()?;

    require_signer(governance)?;
    if config.address() != &CONFIG_ID {
        return Err(HdError::InvalidAccountTag.into());
    }
    let mut c = state::load_mut::<Config>(config)?;
    if governance.address().as_array() != &c.governance {
        return Err(HdError::Unauthorized.into());
    }
    if c.pending_governance == NONE {
        return Err(HdError::InvalidInstruction.into());
    }
    let cancelled = c.pending_governance;
    c.pending_governance = NONE;
    c.pending_governance_eta_slot = U64::new(0);
    drop(c);
    log_data(&events::governance_cancelled_bytes(
        governance.address(),
        &cancelled,
    ));
    Ok(())
}
