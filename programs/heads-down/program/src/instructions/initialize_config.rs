//! `initialize_config` (tag 0).
//!
//! Accounts:
//! 0. `[signer, writable]` upgrade authority (pays rent)
//! 1. `[writable]` Config PDA `[b"config"]`
//! 2. `[]` this program's ProgramData (BPF upgradeable loader)
//! 3. `[]` System program
//!
//! Data (114 bytes): `governance [32] | registrar [32] | crank_fee u64 |
//! executor_fee u64 | bury_bps u16 | ore_layout_hash [32]`.
//!
//! Only the program's upgrade authority, as recorded in its ProgramData
//! account, may create the Config (no front-running of initialization).
//! The canonical Executor bump is found here and stored; it is never taken
//! from instruction data afterwards.

use pinocchio::{error::ProgramError, instruction::seeds, AccountView, Address, ProgramResult};

use crate::{
    error::HdError,
    ore, pda,
    state::{self, Config, Header},
    util::{require_signer, Reader},
    CONFIG_BUMP, CONFIG_ID, CONFIG_SEED, EXECUTOR_BUMP, EXECUTOR_ID, EXECUTOR_SEED, ID,
};

/// `BPFLoaderUpgradeab1e11111111111111111111111`.
pub const BPF_LOADER_UPGRADEABLE_ID: Address = Address::new_from_array([
    0x02, 0xa8, 0xf6, 0x91, 0x4e, 0x88, 0xa1, 0xb0, 0xe2, 0x10, 0x15, 0x3e, 0xf7, 0x63, 0xae, 0x2b,
    0x00, 0xc2, 0xb9, 0x3d, 0x16, 0xc1, 0x24, 0xd2, 0xc0, 0x53, 0x7a, 0x10, 0x04, 0x80, 0x00, 0x00,
]);

/// `UpgradeableLoaderState::ProgramData` (bincode): `u32 tag = 3 | u64 slot
/// | u8 option (1 = Some) | [u8; 32] authority`.
const PROGRAMDATA_TAG: u32 = 3;
const PROGRAMDATA_AUTHORITY_OPTION: usize = 12;
const PROGRAMDATA_AUTHORITY: usize = 13;
const PROGRAMDATA_HEADER_LEN: usize = 45;

/// Maximum `bury_bps`.
pub const MAX_BPS: u16 = 10_000;

/// Read the upgrade authority recorded in `program_data` (None if the
/// program is immutable), after checking the account is this program's
/// ProgramData.
pub fn upgrade_authority(program_data: &AccountView) -> Result<Option<[u8; 32]>, ProgramError> {
    let (expected, _) = pda::find(&[ID.as_ref()], &BPF_LOADER_UPGRADEABLE_ID);
    if program_data.address() != &expected || !program_data.owned_by(&BPF_LOADER_UPGRADEABLE_ID) {
        return Err(HdError::Unauthorized.into());
    }
    let d = program_data.try_borrow()?;
    if d.len() < PROGRAMDATA_HEADER_LEN {
        return Err(HdError::Unauthorized.into());
    }
    let tag = d
        .get(0..4)
        .and_then(|s| s.try_into().ok())
        .map(u32::from_le_bytes)
        .ok_or(HdError::Unauthorized)?;
    if tag != PROGRAMDATA_TAG {
        return Err(HdError::Unauthorized.into());
    }
    match d.get(PROGRAMDATA_AUTHORITY_OPTION) {
        Some(1) => {
            let a: [u8; 32] = d
                .get(PROGRAMDATA_AUTHORITY..PROGRAMDATA_HEADER_LEN)
                .and_then(|s| s.try_into().ok())
                .ok_or(HdError::Unauthorized)?;
            Ok(Some(a))
        }
        Some(0) => Ok(None),
        _ => Err(HdError::Unauthorized.into()),
    }
}

/// Handler.
pub fn process(accounts: &mut [AccountView], data: &[u8]) -> ProgramResult {
    let [payer, config, program_data, system_program, ..] = accounts else {
        return Err(ProgramError::NotEnoughAccountKeys);
    };

    let mut r = Reader::new(data);
    let governance = r.array::<32>()?;
    let registrar = r.array::<32>()?;
    let crank_fee = r.u64()?;
    let executor_fee = r.u64()?;
    let bury_bps = r.u16()?;
    let layout_hash = r.array::<32>()?;
    r.finish()?;

    // The signer must be the upgrade authority in ProgramData.
    require_signer(payer)?;
    match upgrade_authority(program_data)? {
        Some(a) if &a == payer.address().as_array() => {}
        _ => return Err(HdError::Unauthorized.into()),
    }

    // Canonical PDAs (belt and braces: the constants are compile-time).
    let (config_pda, config_bump) = pda::find(&[CONFIG_SEED], &ID);
    let (executor_pda, executor_bump) = pda::find(&[EXECUTOR_SEED], &ID);
    if config_pda != CONFIG_ID
        || config_bump != CONFIG_BUMP
        || executor_pda != EXECUTOR_ID
        || executor_bump != EXECUTOR_BUMP
    {
        return Err(HdError::InvalidExecutor.into());
    }
    if config.address() != &CONFIG_ID {
        return Err(ProgramError::InvalidSeeds);
    }

    // Economic sanity: reimbursement never exceeds what each dig pays in.
    if crank_fee > executor_fee || bury_bps > MAX_BPS {
        return Err(HdError::InvalidInstruction.into());
    }
    // The ORE layout this binary was built against.
    if layout_hash != ore::layout_hash() {
        return Err(HdError::InvalidOreAccount.into());
    }

    let bump = [config_bump];
    let signer_seeds = seeds!(CONFIG_SEED, &bump);
    pda::create_pda_account(
        payer,
        config,
        system_program,
        core::mem::size_of::<Config>(),
        &signer_seeds,
    )?;

    let mut c = state::load_uninit_mut::<Config>(config)?;
    c.header = Header::new(state::tag::CONFIG, config_bump);
    c.governance = governance;
    c.registrar = registrar;
    c.crank_fee.set(crank_fee);
    c.executor_fee.set(executor_fee);
    c.bury_bps.set(bury_bps);
    c.paused = 0;
    c.executor_bump = executor_bump;
    c.ore_layout_hash = layout_hash;
    Ok(())
}
