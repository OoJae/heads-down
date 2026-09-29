//! PDA derivation and init-only account creation.

use pinocchio::{
    cpi::{Seed, Signer},
    error::ProgramError,
    sysvars::{rent::Rent, Sysvar},
    AccountView, Address, ProgramResult,
};
use pinocchio_system::instructions::{Allocate, Assign, CreateAccount, Transfer};

use crate::{error::HdError, ore::SYSTEM_PROGRAM_ID};

/// `find_program_address` (the syscall on SBF, curve25519 on the host).
#[inline]
pub fn find(seeds: &[&[u8]], program_id: &Address) -> (Address, u8) {
    Address::find_program_address(seeds, program_id)
}

/// Create the PDA `target` owned by heads_down with `space` bytes, paid by
/// `payer`, signing with `seeds` (which must include the canonical bump).
///
/// Init-only: the target must still be a System-owned account with no data,
/// so an initialized account can never be re-initialized. Pre-funding does
/// not block creation (THREAT_MODEL row 9): if lamports are already there we
/// top up to rent, then `Allocate` + `Assign` instead of `CreateAccount`.
pub fn create_pda_account(
    payer: &AccountView,
    target: &AccountView,
    system_program: &AccountView,
    space: usize,
    seeds: &[Seed],
) -> ProgramResult {
    if system_program.address() != &SYSTEM_PROGRAM_ID {
        return Err(ProgramError::IncorrectProgramId);
    }
    if !target.owned_by(&SYSTEM_PROGRAM_ID) || !target.is_data_empty() {
        return Err(ProgramError::AccountAlreadyInitialized);
    }
    if !payer.is_signer() {
        return Err(ProgramError::MissingRequiredSignature);
    }
    let required = Rent::get()?.try_minimum_balance(space)?;
    let space_u64 = u64::try_from(space).map_err(|_| HdError::MathOverflow)?;
    let signer = [Signer::from(seeds)];
    let current = target.lamports();
    if current == 0 {
        CreateAccount {
            from: payer,
            to: target,
            lamports: required,
            space: space_u64,
            owner: &crate::ID,
        }
        .invoke_signed(&signer)?;
    } else {
        if current < required {
            Transfer {
                from: payer,
                to: target,
                lamports: required.checked_sub(current).ok_or(HdError::MathOverflow)?,
            }
            .invoke()?;
        }
        Allocate {
            account: target,
            space: space_u64,
        }
        .invoke_signed(&signer)?;
        Assign {
            account: target,
            owner: &crate::ID,
        }
        .invoke_signed(&signer)?;
    }
    Ok(())
}

/// Close a heads_down account: zero its data, move every lamport to
/// `recipient` (fixed by state, never chosen by the caller), and hand it back
/// to the System program with zero length.
pub fn close_account(account: &mut AccountView, recipient: &mut AccountView) -> ProgramResult {
    if !account.owned_by(&crate::ID) || !account.is_writable() || !recipient.is_writable() {
        return Err(ProgramError::InvalidAccountData);
    }
    if account.address() == recipient.address() {
        return Err(ProgramError::InvalidArgument);
    }
    {
        let mut data = account.try_borrow_mut()?;
        data.fill(0);
    }
    let lamports = account.lamports();
    let new_recipient = recipient
        .lamports()
        .checked_add(lamports)
        .ok_or(HdError::MathOverflow)?;
    recipient.set_lamports(new_recipient);
    account.set_lamports(0);
    account.close()
}
