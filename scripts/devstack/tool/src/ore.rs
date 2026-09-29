//! The ORE, entropy and ORE-mint pieces the dev stack drives that the crank never touches:
//! `reset`, a manual `deploy` (the background miner), `automate`, and the entropy
//! `sample` / `reveal`. Layouts and account orders are taken from ORE's source at the pinned
//! commit `b92c5043` (`api/src/sdk.rs`, `program/src/reset.rs`) and entropy-api 0.1.4
//! (`regolith-labs/entropy`, `program/src/{next,reveal,sample}.rs`).

use hd_crank::ore::{self as core, BOARD_ADDRESS, CONFIG_ADDRESS, ORE_PROGRAM_ID, SYSTEM_PROGRAM_ID, TREASURY_ADDRESS};
use sha3::{Digest, Keccak256};
use solana_address::Address;
use solana_instruction::{AccountMeta, Instruction};

pub use hd_crank::ore::{ENTROPY_PROGRAM_ID, VAR_ADDRESS};

/// ORE mint (`consts.rs:71`).
pub const MINT_ADDRESS: Address = Address::from_str_const("oreoU2P8bN6jkk3jbaiVxYnG1dCXcYxwhwyK9jSybcp");
/// ORE admin fee collector (`consts.rs:98`); `reset` requires exactly this address.
pub const ADMIN_FEE_COLLECTOR: Address = Address::from_str_const("DyB4Kv6V613gp2LWQTq1dwDYHGKuUEoDHnCouGUtxFiX");
/// ORE mint program (ore-mint-api 0.1.3).
pub const MINT_PROGRAM_ID: Address = Address::from_str_const("mintzxW6Kckmeyh1h6Zfdj9QcYgCzhPSGiC8ChZ6fCx");
/// SPL Token.
pub const TOKEN_PROGRAM_ID: Address = Address::from_str_const("TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA");
/// Associated Token Account program.
pub const ATA_PROGRAM_ID: Address = Address::from_str_const("ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL");
/// SlotHashes sysvar.
pub const SLOT_HASHES_SYSVAR: Address = Address::from_str_const("SysvarS1otHashes111111111111111111111111111");

/// Entropy `Var` (240 bytes: 8-byte Steel header + struct). Offsets of the fields we touch.
pub mod var {
    /// `authority` (must be the Board).
    pub const AUTHORITY: usize = 8;
    /// `commit`.
    pub const COMMIT: usize = 80;
    /// `seed` (revealed preimage of `commit`).
    pub const SEED: usize = 112;
    /// `slot_hash` (sampled at `end_at`).
    pub const SLOT_HASH: usize = 144;
    /// `value` = keccak(slot_hash | seed | samples).
    pub const VALUE: usize = 176;
    /// `samples` remaining.
    pub const SAMPLES: usize = 208;
    /// `is_auto`.
    pub const IS_AUTO: usize = 216;
    /// `start_at`.
    pub const START_AT: usize = 224;
    /// `end_at`.
    pub const END_AT: usize = 232;
    /// Account size.
    pub const LEN: usize = 240;
}

/// Keccak-256 (Solana's `keccak::hashv`).
pub fn keccak(parts: &[&[u8]]) -> [u8; 32] {
    let mut h = Keccak256::new();
    for p in parts {
        h.update(p);
    }
    h.finalize().into()
}

/// ORE mint authority PDA `["authority"]` under the mint program.
pub fn mint_authority_pda() -> Address {
    Address::find_program_address(&[b"authority"], &MINT_PROGRAM_ID).0
}

/// The Treasury's ORE associated token account.
pub fn treasury_tokens() -> Address {
    Address::find_program_address(
        &[TREASURY_ADDRESS.as_ref(), TOKEN_PROGRAM_ID.as_ref(), MINT_ADDRESS.as_ref()],
        &ATA_PROGRAM_ID,
    )
    .0
}

/// ORE `reset` (tag 9), accounts exactly as `sdk.rs:268-310`. `top_miner` is the Miner
/// *account* (not its authority); it is read only when a solo square won with SOL on it.
pub fn reset_ix(signer: &Address, round_id: u64, top_miner: &Address) -> Instruction {
    Instruction {
        program_id: ORE_PROGRAM_ID,
        accounts: vec![
            AccountMeta::new(*signer, true),
            AccountMeta::new(BOARD_ADDRESS, false),
            AccountMeta::new(CONFIG_ADDRESS, false),
            AccountMeta::new(ADMIN_FEE_COLLECTOR, false),
            AccountMeta::new(MINT_ADDRESS, false),
            AccountMeta::new(core::round_pda(round_id), false),
            AccountMeta::new(core::round_pda(round_id + 1), false),
            AccountMeta::new(*top_miner, false),
            AccountMeta::new(TREASURY_ADDRESS, false),
            AccountMeta::new(treasury_tokens(), false),
            AccountMeta::new_readonly(SYSTEM_PROGRAM_ID, false),
            AccountMeta::new_readonly(TOKEN_PROGRAM_ID, false),
            AccountMeta::new_readonly(ORE_PROGRAM_ID, false),
            AccountMeta::new_readonly(SLOT_HASHES_SYSVAR, false),
            AccountMeta::new(VAR_ADDRESS, false),
            AccountMeta::new_readonly(ENTROPY_PROGRAM_ID, false),
            AccountMeta::new(mint_authority_pda(), false),
            AccountMeta::new_readonly(MINT_PROGRAM_ID, false),
        ],
        data: vec![9],
    }
}

/// ORE `deploy` (tag 6) as a manual (non-automated) miner: `signer == authority`.
pub fn manual_deploy_ix(authority: &Address, round_id: u64, amount: u64, mask: u32) -> Instruction {
    let mut data = vec![6u8];
    data.extend_from_slice(&amount.to_le_bytes());
    data.extend_from_slice(&mask.to_le_bytes());
    Instruction {
        program_id: ORE_PROGRAM_ID,
        accounts: vec![
            AccountMeta::new(*authority, true),
            AccountMeta::new(*authority, false),
            AccountMeta::new(core::automation_pda(authority), false),
            AccountMeta::new(BOARD_ADDRESS, false),
            AccountMeta::new(CONFIG_ADDRESS, false),
            AccountMeta::new(core::miner_pda(authority), false),
            AccountMeta::new(core::round_pda(round_id), false),
            AccountMeta::new(TREASURY_ADDRESS, false),
            AccountMeta::new_readonly(SYSTEM_PROGRAM_ID, false),
            AccountMeta::new_readonly(ORE_PROGRAM_ID, false),
            AccountMeta::new(VAR_ADDRESS, false),
            AccountMeta::new_readonly(ENTROPY_PROGRAM_ID, false),
        ],
        data,
    }
}

/// ORE `AutomateV2` (tag 0, 66 bytes): the canonical Heads Down Automation, i.e. strategy
/// Discretionary (2), `executor` = the heads_down Executor PDA, fixed `fee` =
/// `Config.executor_fee`, per-square cap `amount`. Same bytes as
/// `programs/heads-down/tests/src/lib.rs::ore_automate`.
pub fn automate_ix(wallet: &Address, executor: &Address, amount: u64, deposit: u64, fee: u64) -> Instruction {
    let mut data = vec![0u8];
    data.extend_from_slice(&amount.to_le_bytes());
    data.extend_from_slice(&deposit.to_le_bytes());
    data.extend_from_slice(&fee.to_le_bytes());
    data.extend_from_slice(&0u64.to_le_bytes()); // mask (unused by Discretionary)
    data.push(2); // strategy = Discretionary
    data.extend_from_slice(&0u64.to_le_bytes()); // reload
    data.extend_from_slice(&u64::MAX.to_le_bytes()); // max_production_cost (ORE ignores it)
    data.extend_from_slice(&0u16.to_le_bytes()); // min_motherlode
    data.extend_from_slice(&u16::MAX.to_le_bytes()); // max_motherlode
    data.extend_from_slice(&0u16.to_le_bytes()); // split_tiles
    data.extend_from_slice(&0u16.to_le_bytes()); // solo_tiles
    data.extend_from_slice(&0u64.to_le_bytes()); // buffer
    debug_assert_eq!(data.len(), 66);
    Instruction {
        program_id: ORE_PROGRAM_ID,
        accounts: vec![
            AccountMeta::new(*wallet, true),
            AccountMeta::new(core::automation_pda(wallet), false),
            AccountMeta::new(*executor, false),
            AccountMeta::new(core::miner_pda(wallet), false),
            AccountMeta::new_readonly(SYSTEM_PROGRAM_ID, false),
        ],
        data,
    }
}

/// Entropy `sample` (tag 5): permissionless once `slot >= end_at`; records the slot hash.
pub fn entropy_sample_ix(signer: &Address) -> Instruction {
    Instruction {
        program_id: ENTROPY_PROGRAM_ID,
        accounts: vec![
            AccountMeta::new(*signer, true),
            AccountMeta::new(VAR_ADDRESS, false),
            AccountMeta::new_readonly(SLOT_HASHES_SYSVAR, false),
        ],
        data: vec![5],
    }
}

/// Entropy `reveal` (tag 4): anyone holding the preimage of `commit` may reveal it.
pub fn entropy_reveal_ix(signer: &Address, seed: &[u8; 32]) -> Instruction {
    let mut data = vec![4u8];
    data.extend_from_slice(seed);
    Instruction {
        program_id: ENTROPY_PROGRAM_ID,
        accounts: vec![AccountMeta::new(*signer, true), AccountMeta::new(VAR_ADDRESS, false)],
        data,
    }
}

/// ORE's `Round::rng` over the entropy value (`state/round.rs:63-73`).
pub fn rng(value: &[u8; 32]) -> Option<u64> {
    if value == &[0u8; 32] || value == &[u8::MAX; 32] {
        return None;
    }
    let w = |i: usize| u64::from_le_bytes(value[i..i + 8].try_into().unwrap_or([0; 8]));
    Some(w(0) ^ w(8) ^ w(16) ^ w(24))
}

/// Whether the winning square's +1 ORE is split (`state/round.rs:103-105`).
pub fn is_split(round_id: u64, square: usize) -> bool {
    core::distribution_mask(round_id) & (1u32 << square) == 0
}
