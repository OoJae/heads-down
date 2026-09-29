//! # heads_down
//!
//! A phone-gated ORE rig. A user's own ORE `Automation` (strategy
//! Discretionary, fixed fee) names this program's **Executor PDA** as its
//! executor. The program signs ORE `deploy` for that Automation only when
//! the transaction carries a fresh Android Keystore P-256 heartbeat from the
//! rig's phone, verified by the secp256r1 precompile, and only within the
//! wallet-signed caps and the Motherlode-aware production-cost gate.
//!
//! The contract (PDAs, byte layouts, preimages, instruction formats, dig
//! semantics, errors, events) is `INTERFACE.md`; deviations and extensions
//! are listed in `INTERFACE-NOTES.md`; the audit checklist is in `README.md`.
//!
//! Pinocchio 0.11, `no_std` on SBF, no allocator, one audited `unsafe`
//! block (`events::log_data`, the `sol_log_data` syscall).

#![cfg_attr(target_os = "solana", no_std)]
#![deny(unsafe_code)]
#![deny(missing_docs)]
#![cfg_attr(
    not(test),
    deny(
        clippy::arithmetic_side_effects,
        clippy::indexing_slicing,
        clippy::unwrap_used,
        clippy::expect_used,
        clippy::panic,
        clippy::cast_possible_truncation
    )
)]

use pinocchio::{error::ProgramError, AccountView, Address, ProgramResult};

pub mod ed25519;
pub mod error;
pub mod events;
pub mod hash;
pub mod instructions;
pub mod logic;
pub mod message;
pub mod ore;
pub mod pda;
pub mod state;
pub mod util;

/// Program id `HDn4vgLWFLLdexKEwfZwRHjWtizNvdqFteLbMsE67F9p`.
pub const ID: Address = Address::new_from_array([
    0xf1, 0x00, 0xeb, 0x31, 0x36, 0x75, 0x3b, 0xd6, 0x5e, 0x86, 0x1b, 0x52, 0x9d, 0x37, 0x24, 0x6a,
    0xab, 0x55, 0x46, 0x04, 0xf2, 0x3f, 0x78, 0xcd, 0xe6, 0x05, 0x26, 0xd1, 0x42, 0x33, 0xf7, 0xd7,
]);

/// Config PDA seed.
pub const CONFIG_SEED: &[u8] = b"config";
/// Executor PDA seed.
pub const EXECUTOR_SEED: &[u8] = b"executor";
/// Rig PDA seed.
pub const RIG_SEED: &[u8] = b"rig";
/// SeekerSeat PDA seed.
pub const SEEKER_SEED: &[u8] = b"seeker";
/// ShiftLog PDA seed.
pub const SHIFT_SEED: &[u8] = b"shift";

/// Canonical bump of `[b"config"]` (asserted against `find_program_address`
/// by a unit test and again on-chain by `initialize_config`).
pub const CONFIG_BUMP: u8 = 253;
/// Config PDA `inzDn4ogmXbx9YDAKDHkfwJHy1jhsaWxGQvricDAEmW`.
pub const CONFIG_ID: Address =
    Address::derive_address_const(&[CONFIG_SEED], Some(CONFIG_BUMP), &ID);
/// Canonical bump of `[b"executor"]`.
pub const EXECUTOR_BUMP: u8 = 249;
/// Executor PDA `By3vJvQUsCLexnv7VqHuEhtZZCmpmjZjfhxvqCnWPkge`: a data-less,
/// System-owned account holding the crank float. It signs only ORE `deploy`
/// and its own crank-reimbursement transfers.
pub const EXECUTOR_ID: Address =
    Address::derive_address_const(&[EXECUTOR_SEED], Some(EXECUTOR_BUMP), &ID);

/// Instruction tags (`data[0]`).
pub mod tag {
    /// initialize_config.
    pub const INITIALIZE_CONFIG: u8 = 0;
    /// register_rig.
    pub const REGISTER_RIG: u8 = 1;
    /// verify_seeker.
    pub const VERIFY_SEEKER: u8 = 2;
    /// set_caps.
    pub const SET_CAPS: u8 = 3;
    /// rotate_key.
    pub const ROTATE_KEY: u8 = 4;
    /// arm_shift.
    pub const ARM_SHIFT: u8 = 5;
    /// dig.
    pub const DIG: u8 = 6;
    /// record_heartbeats.
    pub const RECORD_HEARTBEATS: u8 = 7;
    /// break_shift.
    pub const BREAK_SHIFT: u8 = 8;
    /// freeze_rig.
    pub const FREEZE_RIG: u8 = 9;
    /// unfreeze_rig.
    pub const UNFREEZE_RIG: u8 = 10;
    /// end_shift.
    pub const END_SHIFT: u8 = 11;
    /// propose_config.
    pub const PROPOSE_CONFIG: u8 = 12;
    /// apply_config.
    pub const APPLY_CONFIG: u8 = 13;
    /// close_rig.
    pub const CLOSE_RIG: u8 = 14;
}

#[cfg(all(target_os = "solana", not(feature = "no-entrypoint")))]
mod entrypoint {
    use pinocchio::{no_allocator, nostd_panic_handler, program_entrypoint};

    program_entrypoint!(crate::process_instruction);
    no_allocator!();
    nostd_panic_handler!();
}

/// Dispatch on `data[0]`.
pub fn process_instruction(
    program_id: &Address,
    accounts: &mut [AccountView],
    data: &[u8],
) -> ProgramResult {
    if program_id != &ID {
        return Err(ProgramError::IncorrectProgramId);
    }
    let (tag, rest) = data
        .split_first()
        .ok_or(error::HdError::InvalidInstruction)?;
    use instructions::*;
    match *tag {
        tag::INITIALIZE_CONFIG => initialize_config::process(accounts, rest),
        tag::REGISTER_RIG => register_rig::process(accounts, rest),
        tag::VERIFY_SEEKER => verify_seeker::process(accounts, rest),
        tag::SET_CAPS => set_caps::process(accounts, rest),
        tag::ROTATE_KEY => rotate_key::process(accounts, rest),
        tag::ARM_SHIFT => arm_shift::process(accounts, rest),
        tag::DIG => dig::process(accounts, rest),
        tag::RECORD_HEARTBEATS => record_heartbeats::process(accounts, rest),
        tag::BREAK_SHIFT => signals::process_break(accounts, rest),
        tag::FREEZE_RIG => signals::process_freeze(accounts, rest),
        tag::UNFREEZE_RIG => signals::process_unfreeze(accounts, rest),
        tag::END_SHIFT => end_shift::process(accounts, rest),
        tag::PROPOSE_CONFIG => governance::process_propose(accounts, rest),
        tag::APPLY_CONFIG => governance::process_apply(accounts, rest),
        tag::CLOSE_RIG => close_rig::process(accounts, rest),
        _ => Err(error::HdError::InvalidInstruction.into()),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use core::str::FromStr;

    #[test]
    fn program_id_matches_base58() {
        assert_eq!(
            ID,
            Address::from_str("HDn4vgLWFLLdexKEwfZwRHjWtizNvdqFteLbMsE67F9p").unwrap()
        );
    }

    #[test]
    fn pdas_are_canonical_and_match_the_constants() {
        let (config, bump) = Address::find_program_address(&[CONFIG_SEED], &ID);
        assert_eq!((config, bump), (CONFIG_ID, CONFIG_BUMP));
        assert_eq!(
            config,
            Address::from_str("inzDn4ogmXbx9YDAKDHkfwJHy1jhsaWxGQvricDAEmW").unwrap()
        );
        let (executor, bump) = Address::find_program_address(&[EXECUTOR_SEED], &ID);
        assert_eq!((executor, bump), (EXECUTOR_ID, EXECUTOR_BUMP));
        assert_eq!(
            executor,
            Address::from_str("By3vJvQUsCLexnv7VqHuEhtZZCmpmjZjfhxvqCnWPkge").unwrap()
        );
    }

    #[test]
    fn pinned_ore_and_precompile_ids_match_base58() {
        let cases = [
            (
                ore::ORE_PROGRAM_ID,
                "oreV3EG1i9BEgiAJ8b177Z2S2rMarzak4NMv1kULvWv",
            ),
            (
                ore::BOARD_ADDRESS,
                "BrcSxdp1nXFzou1YyDnQJcPNBNHgoypZmTsyKBSLLXzi",
            ),
            (
                ore::CONFIG_ADDRESS,
                "9c9X7aDRAF41faiDs94ELjT19UrGnn72wBW9hPsS4Awy",
            ),
            (
                ore::TREASURY_ADDRESS,
                "45db2FSR4mcXdSVVZbKbwojU6uYDpMyhpEi7cC8nHaWG",
            ),
            (
                ore::VAR_ADDRESS,
                "BWCaDY96Xe4WkFq1M7UiCCRcChsJ3p51L5KrGzhxgm2E",
            ),
            (
                ore::ENTROPY_PROGRAM_ID,
                "3jSkUuYBoJzQPMEzTvkDFXCZUBksPamrVhrnHR9igu2X",
            ),
            (ore::SYSTEM_PROGRAM_ID, "11111111111111111111111111111111"),
            (
                ed25519::ED25519_PROGRAM_ID,
                "Ed25519SigVerify111111111111111111111111111",
            ),
            (
                instructions::initialize_config::BPF_LOADER_UPGRADEABLE_ID,
                "BPFLoaderUpgradeab1e11111111111111111111111",
            ),
        ];
        for (a, s) in cases {
            assert_eq!(a, Address::from_str(s).unwrap(), "{s}");
        }
    }

    #[test]
    fn ore_layout_hash_is_stable() {
        // Changing any pinned ORE size, discriminator or offset changes this
        // hash, which `initialize_config` checks against its argument.
        let h = ore::layout_hash();
        assert_eq!(ore::LAYOUT_PREIMAGE.len(), 78);
        assert_eq!(&ore::LAYOUT_PREIMAGE[..24], b"heads_down/ore-layout/v1");
        assert_eq!(h, hash::sha256(&[&ore::LAYOUT_PREIMAGE]));
    }
}
