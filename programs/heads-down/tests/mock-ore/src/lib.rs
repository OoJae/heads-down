//! TEST ONLY. A stand-in for ORE `deploy`, loaded at ORE's program id in
//! LiteSVM, that misbehaves in the ways an upgraded or broken ORE could. The
//! behaviour is selected by byte 1 of the Board account (Steel header
//! padding, which heads_down does not read):
//!
//! * `1` DRAIN: use the Executor PDA's propagated signer privilege to move
//!   20,000 lamports of the float to the authority (more than CHECKPOINT_FEE).
//! * `2` NO-OP: return Ok without deploying (like ORE's Motherlode no-op).
//! * `3` OVER-DEBIT: take 1,000 lamports out of the Automation (balance field
//!   and lamports) without crediting any tile.
//!
//! Accounts are ORE deploy's 12 (signer, authority, automation, board, ...).
//!
//! ORE `bury` (tag 24, v1.2 Bury auction), mode from byte 1 of the Board
//! (account 2 of bury's 12):
//!
//! * `2` NO-OP: return Ok without taking any ORE.
//! * `4` TAKE-NO-BURN: take the ORE to the Treasury's ORE ATA with the
//!   BuryVault's propagated signer privilege, but burn nothing.
#![cfg_attr(target_os = "solana", no_std)]
#![allow(clippy::indexing_slicing)]

use pinocchio::{
    cpi::invoke,
    error::ProgramError,
    instruction::{InstructionAccount, InstructionView},
    AccountView, Address, ProgramResult,
};
use pinocchio_system::instructions::Transfer;

/// SPL Token `TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA`.
const SPL_TOKEN: Address = Address::new_from_array([
    0x06, 0xdd, 0xf6, 0xe1, 0xd7, 0x65, 0xa1, 0x93, 0xd9, 0xcb, 0xe1, 0x46, 0xce, 0xeb, 0x79, 0xac,
    0x1c, 0xb4, 0x85, 0xed, 0x5f, 0x5b, 0x37, 0x91, 0x3a, 0x8c, 0xf5, 0x85, 0x7e, 0xff, 0x00, 0xa9,
]);

fn bury(accounts: &[AccountView], data: &[u8]) -> ProgramResult {
    let mode = accounts[2].try_borrow()?[1];
    match mode {
        2 => Ok(()),
        4 => {
            let mut ix_data = [3u8; 9];
            ix_data[1..9].copy_from_slice(data.get(1..9).ok_or(ProgramError::InvalidInstructionData)?);
            let metas = [
                InstructionAccount::writable(accounts[1].address()),
                InstructionAccount::writable(accounts[5].address()),
                InstructionAccount::readonly_signer(accounts[0].address()),
            ];
            invoke(
                &InstructionView {
                    program_id: &SPL_TOKEN,
                    data: &ix_data,
                    accounts: &metas,
                },
                &[&accounts[1], &accounts[5], &accounts[0]],
            )
        }
        _ => Err(ProgramError::InvalidInstructionData),
    }
}

#[cfg(target_os = "solana")]
mod entrypoint {
    use pinocchio::{no_allocator, nostd_panic_handler, program_entrypoint};
    program_entrypoint!(crate::process);
    no_allocator!();
    nostd_panic_handler!();
}

/// Entry.
pub fn process(_program_id: &Address, accounts: &mut [AccountView], data: &[u8]) -> ProgramResult {
    if accounts.len() < 12 {
        return Err(ProgramError::NotEnoughAccountKeys);
    }
    if data.first() == Some(&24) {
        return bury(accounts, data);
    }
    let mode = accounts[3].try_borrow()?[1];
    match mode {
        1 => Transfer {
            from: &accounts[0],
            to: &accounts[1],
            lamports: 20_000,
        }
        .invoke(),
        2 => Ok(()),
        3 => {
            let (automation, rest) = accounts[2..]
                .split_first_mut()
                .ok_or(ProgramError::NotEnoughAccountKeys)?;
            let round = &mut rest[3]; // accounts[6]
            {
                let mut d = automation.try_borrow_mut()?;
                let bal = u64::from_le_bytes(d[48..56].try_into().unwrap_or([0; 8]));
                d[48..56].copy_from_slice(&bal.saturating_sub(1_000).to_le_bytes());
            }
            automation.set_lamports(automation.lamports().saturating_sub(1_000));
            round.set_lamports(round.lamports().saturating_add(1_000));
            Ok(())
        }
        _ => Err(ProgramError::InvalidInstructionData),
    }
}
