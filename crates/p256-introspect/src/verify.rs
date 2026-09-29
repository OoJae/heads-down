//! Account-level entry points for Pinocchio programs.

use pinocchio::{error::ProgramError, AccountView, ProgramResult};

use crate::{
    constants::{COMPRESSED_PUBKEY_SERIALIZED_SIZE, INSTRUCTIONS_SYSVAR_ID, SECP256R1_PROGRAM_ID},
    error::IntrospectError,
    precompile::Secp256r1Instruction,
    sysvar::InstructionsSysvar,
};

/// Require that `account` is the real instructions sysvar.
///
/// The runtime owns the data at this address, so after this check the bytes
/// cannot be attacker-forged. Skipping it is the Wormhole bug class.
#[inline]
pub fn check_instructions_sysvar(account: &AccountView) -> Result<(), IntrospectError> {
    if account.address() != &INSTRUCTIONS_SYSVAR_ID {
        return Err(IntrospectError::InvalidInstructionsSysvar);
    }
    Ok(())
}

/// Locate the `Secp256r1SigVerify` instruction at top-level index
/// `precompile_ix_index` and parse it with [`Secp256r1Instruction::parse`].
///
/// The index is untrusted caller input; that is fine, because this checks the
/// program id at that index and confines every offset to that instruction.
///
/// Ordering does not matter for soundness: if the precompile instruction
/// fails, the whole transaction fails atomically, whether it comes before or
/// after the instruction doing the introspection.
pub fn load_secp256r1_instruction<'a>(
    sysvar: &InstructionsSysvar<'a>,
    precompile_ix_index: u16,
) -> Result<Secp256r1Instruction<'a>, IntrospectError> {
    let ix = sysvar.instruction(precompile_ix_index)?;
    if ix.program_id() != SECP256R1_PROGRAM_ID.as_array() {
        return Err(IntrospectError::NotSecp256r1Instruction);
    }
    Secp256r1Instruction::parse(ix.data(), precompile_ix_index)
}

/// Check the sysvar address, borrow its data, locate and validate the
/// precompile instruction, and hand it to `f`.
///
/// Use this when the caller needs the raw entries (for example to hash the
/// message, read a counter out of it, or iterate a batch).
pub fn with_secp256r1_instruction<R, F>(
    instructions_sysvar: &AccountView,
    precompile_ix_index: u16,
    f: F,
) -> Result<R, ProgramError>
where
    F: FnOnce(&Secp256r1Instruction<'_>) -> Result<R, ProgramError>,
{
    check_instructions_sysvar(instructions_sysvar)?;
    let data = instructions_sysvar.try_borrow()?;
    let sysvar = InstructionsSysvar::from_bytes(&data)?;
    let ix = load_secp256r1_instruction(&sysvar, precompile_ix_index)?;
    f(&ix)
}

/// One-call check: the current transaction contains, at top-level index
/// `precompile_ix_index`, a secp256r1 precompile instruction whose entry
/// `signature_index` verified `expected_public_key` over exactly
/// `expected_message`.
///
/// The precompile has already verified the signature by the time any program
/// runs (or will fail the transaction). What this adds is the binding: the
/// verified key and message are the ones this program expects, and they
/// were read from the same bytes the precompile checked.
///
/// Replay protection is the caller's job: bind a nonce, counter or slot into
/// `expected_message`. Never rely on signature uniqueness; ECDSA signatures
/// are malleable (`(r, n - s)`) and a relayer can re-normalize them.
pub fn verify_secp256r1_signature(
    instructions_sysvar: &AccountView,
    precompile_ix_index: u16,
    signature_index: u8,
    expected_public_key: &[u8; COMPRESSED_PUBKEY_SERIALIZED_SIZE],
    expected_message: &[u8],
) -> ProgramResult {
    with_secp256r1_instruction(instructions_sysvar, precompile_ix_index, |ix| {
        ix.expect_entry(signature_index, expected_public_key, expected_message)?;
        Ok(())
    })
}
