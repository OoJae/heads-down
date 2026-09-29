//! `sgt-verify-probe`: the smallest realistic caller of `verify_sgt`.
//!
//! Accounts: `[token_account, sgt_mint, holder (signer)]`. No instruction
//! data. On success the return data is
//! `mint (32) || member_number (u64 LE) || frozen (u8)`; on failure the
//! transaction fails with `Custom(SgtError::program_error_code())`.
//!
//! This is how a Heads Down style `verify_seeker` should use the crate: the
//! expected owner is a key the transaction authenticated (a signer), never a
//! value from instruction data.
#![no_std]

use pinocchio::{
    cpi::set_return_data, error::ProgramError, no_allocator, nostd_panic_handler,
    program_entrypoint, AccountView, Address, ProgramResult,
};
use sgt_verify::verify_sgt;

program_entrypoint!(process_instruction);
no_allocator!();
nostd_panic_handler!();

/// Length of the return data.
pub const RETURN_DATA_LEN: usize = 41;

/// Program entrypoint.
pub fn process_instruction(
    _program_id: &Address,
    accounts: &mut [AccountView],
    data: &[u8],
) -> ProgramResult {
    if !data.is_empty() {
        return Err(ProgramError::InvalidInstructionData);
    }
    let [token_account, mint, holder, ..] = accounts else {
        return Err(ProgramError::NotEnoughAccountKeys);
    };
    if !holder.is_signer() {
        return Err(ProgramError::MissingRequiredSignature);
    }

    let info = verify_sgt(token_account, mint, holder.address())?;

    let mut out = [0u8; RETURN_DATA_LEN];
    let (mint_out, rest) = out.split_at_mut(32);
    let (number_out, frozen_out) = rest.split_at_mut(8);
    mint_out.copy_from_slice(info.mint.as_ref());
    number_out.copy_from_slice(&info.member_number.to_le_bytes());
    frozen_out.copy_from_slice(&[u8::from(info.frozen)]);
    set_return_data(&out);
    Ok(())
}
