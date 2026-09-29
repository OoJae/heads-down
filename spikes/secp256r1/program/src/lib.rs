//! Spike 1(b): secp256r1 end to end.
//!
//! A minimal Pinocchio program that proves a transaction carries a P-256
//! signature (Android Keystore `SHA256withECDSA`, converted to raw low-S)
//! over an expected message by an expected key, using `p256-introspect`.
//!
//! Instructions (first byte is the tag):
//!
//! * `0` `verify_heartbeat(expected_pubkey, expected_message)`
//!   `data = [0, precompile_ix_index: u16 LE, signature_index: u8,
//!            expected_pubkey: [u8; 33], expected_message: [u8]]`
//!   accounts: `[instructions_sysvar]`
//!
//! * `1` `verify_batch([(expected_pubkey, expected_message)])`: entry `i` of
//!   the precompile instruction must match item `i`. Used to measure the
//!   program-side compute cost of N verifications in one instruction.
//!   `data = [1, precompile_ix_index: u16 LE, count: u8,
//!            count x (pubkey: [u8; 33], msg_len: u16 LE, msg: [u8; msg_len])]`
//!   accounts: `[instructions_sysvar]`
//!
//! In the real `heads_down::dig`, `expected_message` is rebuilt on-chain from
//! program state (program id, rig, ORE round id, counter, shift id, lease),
//! never taken from the caller. Here it comes from instruction data because
//! the spike tests the verification mechanism, not the binding.
#![no_std]

use p256_introspect::{
    verify_secp256r1_signature, with_secp256r1_instruction, IntrospectError,
    COMPRESSED_PUBKEY_SERIALIZED_SIZE,
};
use pinocchio::{
    error::ProgramError, no_allocator, nostd_panic_handler, program_entrypoint, AccountView,
    Address, ProgramResult,
};

program_entrypoint!(process_instruction);
no_allocator!();
nostd_panic_handler!();

/// Tags.
pub const VERIFY_HEARTBEAT: u8 = 0;
/// Tags.
pub const VERIFY_BATCH: u8 = 1;

pub fn process_instruction(
    _program_id: &Address,
    accounts: &mut [AccountView],
    data: &[u8],
) -> ProgramResult {
    let (tag, args) = data
        .split_first()
        .ok_or(ProgramError::InvalidInstructionData)?;
    let [instructions_sysvar, ..] = accounts else {
        return Err(ProgramError::NotEnoughAccountKeys);
    };
    match *tag {
        VERIFY_HEARTBEAT => verify_heartbeat(instructions_sysvar, args),
        VERIFY_BATCH => verify_batch(instructions_sysvar, args),
        _ => Err(ProgramError::InvalidInstructionData),
    }
}

/// Little cursor over instruction data; every read is bounds-checked.
struct Reader<'a>(&'a [u8]);

impl<'a> Reader<'a> {
    fn take(&mut self, n: usize) -> Result<&'a [u8], ProgramError> {
        if self.0.len() < n {
            return Err(ProgramError::InvalidInstructionData);
        }
        let (head, tail) = self.0.split_at(n);
        self.0 = tail;
        Ok(head)
    }
    fn u8(&mut self) -> Result<u8, ProgramError> {
        let b = self.take(1)?;
        b.first().copied().ok_or(ProgramError::InvalidInstructionData)
    }
    fn u16(&mut self) -> Result<u16, ProgramError> {
        let b = self.take(2)?;
        let arr: [u8; 2] = b
            .try_into()
            .map_err(|_| ProgramError::InvalidInstructionData)?;
        Ok(u16::from_le_bytes(arr))
    }
    fn pubkey(&mut self) -> Result<&'a [u8; COMPRESSED_PUBKEY_SERIALIZED_SIZE], ProgramError> {
        self.take(COMPRESSED_PUBKEY_SERIALIZED_SIZE)?
            .try_into()
            .map_err(|_| ProgramError::InvalidInstructionData)
    }
    fn rest(self) -> &'a [u8] {
        self.0
    }
}

fn verify_heartbeat(instructions_sysvar: &AccountView, args: &[u8]) -> ProgramResult {
    let mut r = Reader(args);
    let precompile_ix_index = r.u16()?;
    let signature_index = r.u8()?;
    let expected_pubkey = r.pubkey()?;
    let expected_message = r.rest();
    verify_secp256r1_signature(
        instructions_sysvar,
        precompile_ix_index,
        signature_index,
        expected_pubkey,
        expected_message,
    )
}

fn verify_batch(instructions_sysvar: &AccountView, args: &[u8]) -> ProgramResult {
    let mut r = Reader(args);
    let precompile_ix_index = r.u16()?;
    let count = r.u8()?;
    with_secp256r1_instruction(instructions_sysvar, precompile_ix_index, |ix| {
        if count != ix.num_signatures() {
            return Err(IntrospectError::SignatureIndexOutOfBounds.into());
        }
        for i in 0..count {
            let pubkey = r.pubkey()?;
            let len = usize::from(r.u16()?);
            let msg = r.take(len)?;
            ix.expect_entry(i, pubkey, msg)?;
        }
        if !r.0.is_empty() {
            return Err(ProgramError::InvalidInstructionData);
        }
        Ok(())
    })
}
