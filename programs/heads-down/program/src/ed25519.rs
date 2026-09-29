//! Introspection of the `Ed25519SigVerify` precompile for the registrar's
//! key attestation, with the same rules `p256-introspect` applies to
//! secp256r1:
//!
//! 1. the instructions sysvar address is checked (spoofed-sysvar class);
//! 2. the instruction at the caller-supplied index is `Ed25519SigVerify`;
//! 3. **every** offsets record's three instruction indices are `0xFFFF` or
//!    the precompile's own index, so the bytes read here are the bytes the
//!    precompile verified (Wormhole class);
//! 4. every range is bounds-checked with checked arithmetic;
//! 5. the verified public key and message equal the expected ones.
//!
//! Wire format (`solana-ed25519-program`): `num_signatures u8, padding u8`,
//! then `num_signatures` x 14-byte records `(signature_offset,
//! signature_instruction_index, public_key_offset,
//! public_key_instruction_index, message_data_offset, message_data_size,
//! message_instruction_index)` as u16 LE; 64-byte signature, 32-byte key.

use p256_introspect::{check_instructions_sysvar, InstructionsSysvar};
use pinocchio::{AccountView, Address};

use crate::error::HdError;

/// `Ed25519SigVerify111111111111111111111111111`.
pub const ED25519_PROGRAM_ID: Address = Address::new_from_array([
    0x03, 0x7d, 0x46, 0xd6, 0x7c, 0x93, 0xfb, 0xbe, 0x12, 0xf9, 0x42, 0x8f, 0x83, 0x8d, 0x40, 0xff,
    0x05, 0x70, 0x74, 0x49, 0x27, 0xf4, 0x8a, 0x64, 0xfc, 0xca, 0x70, 0x44, 0x80, 0x00, 0x00, 0x00,
]);

const OFFSETS_START: usize = 2;
const RECORD_LEN: usize = 14;
const SIG_LEN: usize = 64;
const PUBKEY_LEN: usize = 32;
const THIS_INSTRUCTION: u16 = u16::MAX;

#[inline]
fn u16_at(d: &[u8], at: usize) -> Option<u16> {
    let b: [u8; 2] = d.get(at..at.checked_add(2)?)?.try_into().ok()?;
    Some(u16::from_le_bytes(b))
}

#[inline]
fn range(d: &[u8], off: u16, len: usize) -> Option<&[u8]> {
    let start = usize::from(off);
    d.get(start..start.checked_add(len)?)
}

/// One validated record: `(public_key, message)`.
fn record(data: &[u8], i: u8, own_index: u16) -> Option<(&[u8], &[u8])> {
    let base = usize::from(i)
        .checked_mul(RECORD_LEN)?
        .checked_add(OFFSETS_START)?;
    let f = |k: usize| u16_at(data, base.checked_add(k)?);
    let (sig_off, sig_ix) = (f(0)?, f(2)?);
    let (pk_off, pk_ix) = (f(4)?, f(6)?);
    let (msg_off, msg_len, msg_ix) = (f(8)?, f(10)?, f(12)?);
    let is_self = |ix: u16| ix == THIS_INSTRUCTION || ix == own_index;
    if !is_self(sig_ix) || !is_self(pk_ix) || !is_self(msg_ix) {
        return None;
    }
    range(data, sig_off, SIG_LEN)?;
    let pk = range(data, pk_off, PUBKEY_LEN)?;
    let msg = range(data, msg_off, usize::from(msg_len))?;
    Some((pk, msg))
}

/// Require that the transaction's instruction `ix_index` is an
/// `Ed25519SigVerify` whose entry `sig_index` verified `expected_pubkey`
/// over exactly `expected_message`. Every failure is `InvalidAttestation`.
pub fn verify_ed25519(
    instructions_sysvar: &AccountView,
    ix_index: u16,
    sig_index: u8,
    expected_pubkey: &[u8; 32],
    expected_message: &[u8],
) -> Result<(), HdError> {
    const BAD: HdError = HdError::InvalidAttestation;
    check_instructions_sysvar(instructions_sysvar).map_err(|_| BAD)?;
    let raw = instructions_sysvar.try_borrow().map_err(|_| BAD)?;
    let sysvar = InstructionsSysvar::from_bytes(&raw).map_err(|_| BAD)?;
    let ix = sysvar.instruction(ix_index).map_err(|_| BAD)?;
    if ix.program_id() != ED25519_PROGRAM_ID.as_array() {
        return Err(BAD);
    }
    let data = ix.data();
    let n = *data.first().ok_or(BAD)?;
    if n == 0 || sig_index >= n {
        return Err(BAD);
    }
    // One foreign record poisons the whole instruction.
    for i in 0..n {
        record(data, i, ix_index).ok_or(BAD)?;
    }
    let (pk, msg) = record(data, sig_index, ix_index).ok_or(BAD)?;
    if pk != expected_pubkey.as_slice() || msg != expected_message {
        return Err(BAD);
    }
    Ok(())
}
