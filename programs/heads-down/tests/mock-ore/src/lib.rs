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
#![cfg_attr(target_os = "solana", no_std)]
#![allow(clippy::indexing_slicing)]

use pinocchio::{error::ProgramError, AccountView, Address, ProgramResult};
use pinocchio_system::instructions::Transfer;

#[cfg(target_os = "solana")]
mod entrypoint {
    use pinocchio::{no_allocator, nostd_panic_handler, program_entrypoint};
    program_entrypoint!(crate::process);
    no_allocator!();
    nostd_panic_handler!();
}

/// Entry.
pub fn process(_program_id: &Address, accounts: &mut [AccountView], _data: &[u8]) -> ProgramResult {
    if accounts.len() < 12 {
        return Err(ProgramError::NotEnoughAccountKeys);
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
