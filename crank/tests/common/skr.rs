//! The wallet-signed v1.2 instructions (INTERFACE §11.4) the crank never sends but the tests
//! need to set a table, a bond or a gift up: `open_stack`, `join_stack`, `claim_stack`,
//! `lock_focus_bond`, `release_focus_bond`, `create_gift`. `tests/golden.rs` checks each one
//! against `programs/heads-down/vectors/instructions.json`, so the fork suite drives the real
//! program with the contract's own bytes.
#![allow(dead_code)]

use hd_crank::skr::{self, SKR_MINT, SPL_TOKEN_PROGRAM_ID};
use hd_crank::{hd, ore};
use solana_address::Address;
use solana_instruction::{AccountMeta, Instruction};

/// `open_stack` parameters.
#[derive(Clone, Copy, Debug)]
pub struct StackParams {
    pub table_id: u64,
    pub bond: u64,
    pub start_round: u64,
    pub end_round: u64,
    pub grace_gaps: u32,
    pub flags: u8,
    pub max_seats: u8,
}

/// `open_stack` (tag 15): `host (s,w), table (w), ATA(table, SKR), ORE Board, System`.
pub fn open_stack_ix(program_id: &Address, host: &Address, p: &StackParams) -> Instruction {
    let table = skr::stack_table_pda(program_id, host, p.table_id).0;
    let mut data = vec![15u8];
    data.extend_from_slice(&p.table_id.to_le_bytes());
    data.extend_from_slice(&p.bond.to_le_bytes());
    data.extend_from_slice(&p.start_round.to_le_bytes());
    data.extend_from_slice(&p.end_round.to_le_bytes());
    data.extend_from_slice(&p.grace_gaps.to_le_bytes());
    data.push(p.flags);
    data.push(p.max_seats);
    Instruction {
        program_id: *program_id,
        accounts: vec![
            AccountMeta::new(*host, true),
            AccountMeta::new(table, false),
            AccountMeta::new_readonly(skr::ata(&table, &SKR_MINT), false),
            AccountMeta::new_readonly(ore::BOARD_ADDRESS, false),
            AccountMeta::new_readonly(ore::SYSTEM_PROGRAM_ID, false),
        ],
        data,
    }
}

/// `join_stack` (tag 16) at an in-person table within the guest cap: the seat is keyed by the
/// rig, and no SGT accounts follow.
pub fn join_stack_ix(program_id: &Address, authority: &Address, table: &Address) -> Instruction {
    let rig = hd::rig_pda(program_id, authority).0;
    Instruction {
        program_id: *program_id,
        accounts: vec![
            AccountMeta::new(*authority, true),
            AccountMeta::new_readonly(rig, false),
            AccountMeta::new(*table, false),
            AccountMeta::new(skr::stack_seat_pda(program_id, table, &rig).0, false),
            AccountMeta::new(skr::ata(authority, &SKR_MINT), false),
            AccountMeta::new(skr::ata(table, &SKR_MINT), false),
            AccountMeta::new_readonly(ore::BOARD_ADDRESS, false),
            AccountMeta::new_readonly(SPL_TOKEN_PROGRAM_ID, false),
            AccountMeta::new_readonly(ore::SYSTEM_PROGRAM_ID, false),
        ],
        data: vec![16],
    }
}

/// `claim_stack` (tag 19): `table (w), seat (w), seat authority (w), its SKR ATA (w), table
/// vault (w), SPL Token`.
pub fn claim_stack_ix(program_id: &Address, table: &Address, seat: &Address, authority: &Address) -> Instruction {
    Instruction {
        program_id: *program_id,
        accounts: vec![
            AccountMeta::new(*table, false),
            AccountMeta::new(*seat, false),
            AccountMeta::new(*authority, false),
            AccountMeta::new(skr::ata(authority, &SKR_MINT), false),
            AccountMeta::new(skr::ata(table, &SKR_MINT), false),
            AccountMeta::new_readonly(SPL_TOKEN_PROGRAM_ID, false),
        ],
        data: vec![19],
    }
}

/// `lock_focus_bond` (tag 20): `authority (s,w), rig, bond (w), authority SKR ATA (w), bond
/// vault (w), ShiftLog slot, SPL Token, System`.
pub fn lock_focus_bond_ix(program_id: &Address, authority: &Address, shift_id: u64, amount: u64) -> Instruction {
    let rig = hd::rig_pda(program_id, authority).0;
    let bond = skr::focus_bond_pda(program_id, &rig, shift_id).0;
    let mut data = vec![20u8];
    data.extend_from_slice(&shift_id.to_le_bytes());
    data.extend_from_slice(&amount.to_le_bytes());
    Instruction {
        program_id: *program_id,
        accounts: vec![
            AccountMeta::new(*authority, true),
            AccountMeta::new_readonly(rig, false),
            AccountMeta::new(bond, false),
            AccountMeta::new(skr::ata(authority, &SKR_MINT), false),
            AccountMeta::new(skr::ata(&bond, &SKR_MINT), false),
            AccountMeta::new_readonly(hd::shift_log_pda(program_id, &rig, shift_id).0, false),
            AccountMeta::new_readonly(SPL_TOKEN_PROGRAM_ID, false),
            AccountMeta::new_readonly(ore::SYSTEM_PROGRAM_ID, false),
        ],
        data,
    }
}

/// `release_focus_bond` (tag 21): `bond (w), ShiftLog, bond vault (w), owner SKR ATA (w),
/// owner (w), SPL Token`.
pub fn release_focus_bond_ix(program_id: &Address, authority: &Address, shift_id: u64) -> Instruction {
    let rig = hd::rig_pda(program_id, authority).0;
    let bond = skr::focus_bond_pda(program_id, &rig, shift_id).0;
    Instruction {
        program_id: *program_id,
        accounts: vec![
            AccountMeta::new(bond, false),
            AccountMeta::new_readonly(hd::shift_log_pda(program_id, &rig, shift_id).0, false),
            AccountMeta::new(skr::ata(&bond, &SKR_MINT), false),
            AccountMeta::new(skr::ata(authority, &SKR_MINT), false),
            AccountMeta::new(*authority, false),
            AccountMeta::new_readonly(SPL_TOKEN_PROGRAM_ID, false),
        ],
        data: vec![21],
    }
}

/// `create_gift` (tag 23): `sender (s,w), GiftEscrow (w), System`.
pub fn create_gift_ix(program_id: &Address, sender: &Address, nonce: u64, recipient_kind: u8, recipient: &Address, lamports: u64) -> Instruction {
    let mut data = vec![23u8];
    data.extend_from_slice(&nonce.to_le_bytes());
    data.push(recipient_kind);
    data.extend_from_slice(recipient.as_ref());
    data.extend_from_slice(&lamports.to_le_bytes());
    Instruction {
        program_id: *program_id,
        accounts: vec![
            AccountMeta::new(*sender, true),
            AccountMeta::new(skr::gift_pda(program_id, sender, nonce).0, false),
            AccountMeta::new_readonly(ore::SYSTEM_PROGRAM_ID, false),
        ],
        data,
    }
}

/// A 165-byte initialized classic SPL Token account (fixture surgery: a fork cannot mint SKR).
pub fn token_account_bytes(mint: &Address, owner: &Address, amount: u64) -> Vec<u8> {
    let mut d = vec![0u8; skr::TOKEN_ACCOUNT_LEN];
    d[..32].copy_from_slice(mint.as_ref());
    d[32..64].copy_from_slice(owner.as_ref());
    d[64..72].copy_from_slice(&amount.to_le_bytes());
    d[108] = 1;
    d
}

/// Rent-exempt lamports of a 165-byte token account.
pub const TOKEN_ACCOUNT_RENT: u64 = 2_039_280;
