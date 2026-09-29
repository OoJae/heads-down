//! heads_down instruction builders the dev stack needs (wallet side). Byte layouts are the
//! program's own (`programs/heads-down/INTERFACE-NOTES.md` §4) and match the reference client
//! in `programs/heads-down/tests/src/lib.rs`.

use hd_crank::hd::{config_pda, executor_pda, rig_pda, PROGRAM_ID};
use hd_crank::ore::{BOARD_ADDRESS, BPF_UPGRADEABLE_LOADER_ID, SYSTEM_PROGRAM_ID};
use solana_address::Address;
use solana_instruction::{AccountMeta, Instruction};

/// heads_down's ProgramData account (upgradeable loader).
pub fn program_data() -> Address {
    Address::find_program_address(&[PROGRAM_ID.as_ref()], &BPF_UPGRADEABLE_LOADER_ID).0
}

/// Config PDA.
pub fn config() -> Address {
    config_pda(&PROGRAM_ID).0
}

/// Executor PDA.
pub fn executor() -> Address {
    executor_pda(&PROGRAM_ID).0
}

/// Rig PDA of `authority`.
pub fn rig(authority: &Address) -> Address {
    rig_pda(&PROGRAM_ID, authority).0
}

/// `initialize_config` (tag 0), signed by the program's upgrade authority.
pub fn initialize_config_ix(
    upgrade_authority: &Address,
    governance: &Address,
    registrar: &Address,
    crank_fee: u64,
    executor_fee: u64,
    bury_bps: u16,
) -> Instruction {
    let mut data = vec![0u8];
    data.extend_from_slice(governance.as_ref());
    data.extend_from_slice(registrar.as_ref());
    data.extend_from_slice(&crank_fee.to_le_bytes());
    data.extend_from_slice(&executor_fee.to_le_bytes());
    data.extend_from_slice(&bury_bps.to_le_bytes());
    data.extend_from_slice(&heads_down::ore::layout_hash());
    Instruction {
        program_id: PROGRAM_ID,
        accounts: vec![
            AccountMeta::new(*upgrade_authority, true),
            AccountMeta::new(config(), false),
            AccountMeta::new_readonly(program_data(), false),
            AccountMeta::new_readonly(SYSTEM_PROGRAM_ID, false),
        ],
        data,
    }
}

/// `register_rig` (tag 1) without a registrar attestation (attestation_level 0).
pub fn register_rig_ix(authority: &Address, p256: &[u8; 33]) -> Instruction {
    let mut data = vec![1u8];
    data.extend_from_slice(p256);
    data.push(0);
    Instruction {
        program_id: PROGRAM_ID,
        accounts: vec![
            AccountMeta::new(*authority, true),
            AccountMeta::new(rig(authority), false),
            AccountMeta::new_readonly(config(), false),
            AccountMeta::new_readonly(SYSTEM_PROGRAM_ID, false),
        ],
        data,
    }
}

/// Wallet-signed caps.
#[derive(Clone, Copy, Debug)]
pub struct Caps {
    /// lamports / week.
    pub week: u64,
    /// lamports / shift.
    pub shift: u64,
    /// lamports / round.
    pub round: u64,
    /// ceiling on the pot-adjusted cost `ema_ev` (lamports per ORE).
    pub max_cost: u64,
    /// unix seconds.
    pub expiry: i64,
}

/// `set_caps` (tag 3).
pub fn set_caps_ix(authority: &Address, c: &Caps) -> Instruction {
    let mut data = vec![3u8];
    for v in [c.week, c.shift, c.round, c.max_cost] {
        data.extend_from_slice(&v.to_le_bytes());
    }
    data.extend_from_slice(&c.expiry.to_le_bytes());
    Instruction {
        program_id: PROGRAM_ID,
        accounts: vec![AccountMeta::new_readonly(*authority, true), AccountMeta::new(rig(authority), false)],
        data,
    }
}

/// A shift plan.
#[derive(Clone, Copy, Debug)]
pub struct Plan {
    /// Gate threshold (≤ cap_max_cost).
    pub max_ev_cost: u64,
    /// SOL per dig.
    pub dig_lamports: u64,
    /// Split tiles.
    pub split: u8,
    /// Solo tiles.
    pub solo: u8,
    /// Lease rounds per heartbeat (1..=3).
    pub lease: u8,
    /// Flags (bit0 focus-only, bit1 day).
    pub flags: u8,
    /// Window start (unix s).
    pub window_start: i64,
    /// Window end (unix s).
    pub window_end: i64,
}

/// `arm_shift` (tag 5), mode 0 (wallet-signed).
pub fn arm_wallet_ix(authority: &Address, p: &Plan) -> Instruction {
    let mut data = vec![5u8, 0];
    data.extend_from_slice(&p.max_ev_cost.to_le_bytes());
    data.extend_from_slice(&p.dig_lamports.to_le_bytes());
    data.extend_from_slice(&[p.split, p.solo, p.lease, p.flags]);
    data.extend_from_slice(&p.window_start.to_le_bytes());
    data.extend_from_slice(&p.window_end.to_le_bytes());
    Instruction {
        program_id: PROGRAM_ID,
        accounts: vec![
            AccountMeta::new(rig(authority), false),
            AccountMeta::new_readonly(*authority, true),
            AccountMeta::new_readonly(BOARD_ADDRESS, false),
        ],
        data,
    }
}
