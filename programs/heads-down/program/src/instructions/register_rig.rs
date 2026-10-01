//! `register_rig` (tag 1).
//!
//! Accounts:
//! 0. `[signer, writable]` authority (the wallet that owns the ORE Automation; pays rent)
//! 1. `[writable]` Rig PDA `[b"rig", authority]`
//! 2. `[]` Config
//! 3. `[]` System program
//! 4. `[]` Instructions sysvar (only when an attestation is supplied)
//!
//! Data: `p256_pubkey [33] | has_attestation u8` then, if 1,
//! `ed25519_ix u8 | ed25519_sig_index u8 | level u8 | expiry_slot u64`
//! (35 or 46 bytes).
//!
//! The optional attestation is an Ed25519 signature by `config.registrar`
//! over `"HDreg" | program | authority | p256 | level | expiry_slot`, carried
//! by an `Ed25519SigVerify` instruction in the same transaction.
//!
//! Emits `RigRegistered{rig, authority, tier = 0, attestation_level}`.
//!
//! **v1.3: resuming from a tombstone.** If the Rig PDA holds the
//! [`RigTombstone`] an earlier `close_rig` left, the same instruction (same
//! accounts, same data) grows it back into a Rig whose `shift_id` and
//! `hb_counter` continue from the tombstone; the authority pays only the
//! rent difference. Everything else starts fresh. A rig address therefore
//! never reuses a shift id and never accepts a P-256 message twice.

use core::mem::size_of;

use pinocchio::{error::ProgramError, instruction::seeds, AccountView, ProgramResult};

use crate::{
    ed25519,
    error::HdError,
    events, message, ore, pda,
    state::{self, rig_state, Config, Header, Rig, RigTombstone},
    util::{clock, load_config, require_signer, Reader},
    ID, RIG_SEED,
};

/// A registrar attestation carried in instruction data.
#[derive(Clone, Copy, Debug)]
pub struct Attestation {
    /// Top-level index of the Ed25519SigVerify instruction.
    pub ix_index: u8,
    /// Entry within it.
    pub sig_index: u8,
    /// 1 TEE, 2 StrongBox.
    pub level: u8,
    /// Slot after which the attestation is stale.
    pub expiry_slot: u64,
}

/// Parse `has_attestation u8` and the optional tail.
pub fn read_attestation(r: &mut Reader<'_>) -> Result<Option<Attestation>, HdError> {
    match r.u8()? {
        0 => Ok(None),
        1 => Ok(Some(Attestation {
            ix_index: r.u8()?,
            sig_index: r.u8()?,
            level: r.u8()?,
            expiry_slot: r.u64()?,
        })),
        _ => Err(HdError::InvalidInstruction),
    }
}

/// Verify `att` for `(authority, p256)` against `config.registrar` and
/// return `(level, expiry_slot)`; `(0, 0)` when there is none.
pub fn check_attestation(
    att: Option<Attestation>,
    config: &Config,
    authority: &pinocchio::Address,
    p256: &[u8; 33],
    instructions_sysvar: Option<&AccountView>,
) -> Result<(u8, u64), ProgramError> {
    let Some(att) = att else {
        return Ok((0, 0));
    };
    if att.level != 1 && att.level != 2 {
        return Err(HdError::InvalidAttestation.into());
    }
    if att.expiry_slot <= clock()?.slot {
        return Err(HdError::InvalidAttestation.into());
    }
    let sysvar = instructions_sysvar.ok_or(HdError::InvalidAttestation)?;
    let msg = message::registrar_message(authority, p256, att.level, att.expiry_slot);
    ed25519::verify_ed25519(
        sysvar,
        u16::from(att.ix_index),
        att.sig_index,
        &config.registrar,
        &msg,
    )?;
    Ok((att.level, att.expiry_slot))
}

/// Handler.
pub fn process(accounts: &mut [AccountView], data: &[u8]) -> ProgramResult {
    let [authority, rig, config, system_program, rest @ ..] = accounts else {
        return Err(ProgramError::NotEnoughAccountKeys);
    };
    let mut r = Reader::new(data);
    let p256 = r.array::<33>()?;
    let att = read_attestation(&mut r)?;
    r.finish()?;

    require_signer(authority)?;
    p256_introspect::check_public_key_encoding(&p256)?;

    let (rig_pda, bump) = pda::find(&[RIG_SEED, authority.address().as_ref()], &ID);
    if rig.address() != &rig_pda {
        return Err(ProgramError::InvalidSeeds);
    }

    let (level, expiry) = {
        let cfg = load_config(config)?;
        check_attestation(att, &cfg, authority.address(), &p256, rest.first())?
    };

    // What an earlier rig at this address left behind (v1.3), if anything.
    let resumed = if rig.owned_by(&ID) && rig.data_len() == size_of::<RigTombstone>() {
        let t = state::load::<RigTombstone>(rig)?;
        Some((t.shift_id.get(), t.hb_counter.get()))
    } else {
        None
    };
    if resumed.is_some() {
        pda::regrow_account(authority, rig, system_program, size_of::<Rig>())?;
    } else {
        let bump_seed = [bump];
        let signer_seeds = seeds!(RIG_SEED, authority.address().as_ref(), &bump_seed);
        pda::create_pda_account(
            authority,
            rig,
            system_program,
            size_of::<Rig>(),
            &signer_seeds,
        )?;
    }

    // Canonical bumps of the user's ORE Automation / Miner PDAs, found once
    // here so every dig re-derives them with a single hash.
    let (_, auto_bump) = pda::find(
        &[ore::AUTOMATION_SEED, authority.address().as_ref()],
        &ore::ORE_PROGRAM_ID,
    );
    let (_, miner_bump) = pda::find(
        &[ore::MINER_SEED, authority.address().as_ref()],
        &ore::ORE_PROGRAM_ID,
    );

    let now = clock()?.unix_timestamp;
    let rig_address = *rig.address();
    let mut g = state::load_uninit_mut::<Rig>(rig)?;
    g.ore_automation_bump = auto_bump;
    g.ore_miner_bump = miner_bump;
    g.header = Header::new(state::tag::RIG, bump);
    g.authority = *authority.address().as_array();
    g.p256_pubkey = p256;
    g.attestation_level = level;
    g.attestation_expiry_slot.set(expiry);
    g.tier = 0;
    g.state = rig_state::IDLE;
    g.freezes_left = crate::logic::FREEZES_PER_PERIOD;
    g.week_start_ts.set(now);
    if let Some((shift_id, hb_counter)) = resumed {
        g.shift_id.set(shift_id);
        g.hb_counter.set(hb_counter);
    }
    drop(g);
    events::rig_registered(&rig_address, authority.address(), 0, level);
    Ok(())
}
