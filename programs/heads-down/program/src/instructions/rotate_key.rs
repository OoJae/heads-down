//! `rotate_key` (tag 4).
//!
//! Accounts:
//! 0. `[signer]` authority
//! 1. `[writable]` Rig
//! 2. `[]` Config
//! 3. `[]` Instructions sysvar (only when an attestation is supplied)
//!
//! Data: `p256_pubkey [33] | has_attestation u8 [| ed25519_ix u8 |
//! ed25519_sig_index u8 | level u8 | expiry_slot u64]`.
//!
//! Wallet-only (raises nothing, but replaces the key that authorizes
//! heartbeats). Without a fresh attestation the level resets to 0. The
//! counter is kept, so old signatures stay stale.

use pinocchio::{error::ProgramError, AccountView, ProgramResult};

use crate::{
    instructions::register_rig::{check_attestation, read_attestation},
    state::{self, Rig},
    util::{load_config, require_rig_authority, Reader},
};

/// Handler.
pub fn process(accounts: &mut [AccountView], data: &[u8]) -> ProgramResult {
    let [authority, rig, config, rest @ ..] = accounts else {
        return Err(ProgramError::NotEnoughAccountKeys);
    };
    let mut r = Reader::new(data);
    let p256 = r.array::<33>()?;
    let att = read_attestation(&mut r)?;
    r.finish()?;
    p256_introspect::check_public_key_encoding(&p256)?;

    let mut g = state::load_mut::<Rig>(rig)?;
    require_rig_authority(&g, authority)?;
    let (level, expiry) = {
        let cfg = load_config(config)?;
        check_attestation(att, &cfg, authority.address(), &p256, rest.first())?
    };
    g.p256_pubkey = p256;
    g.attestation_level = level;
    g.attestation_expiry_slot.set(expiry);
    Ok(())
}
