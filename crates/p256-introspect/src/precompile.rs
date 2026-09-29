//! Parser for `Secp256r1SigVerify` instruction data.
//!
//! Wire format (SIMD-0075, "Detailed Design / Program"):
//!
//! ```text
//! struct Secp256r1SigVerifyInstruction {
//!     num_signatures: u8,                         // 1..=8 (Agave rejects 0 and > 8)
//!     padding: u8,                                // ignored by the precompile
//!     offsets: [Secp256r1SignatureOffsets; num_signatures],  // no length prefix, no padding
//!     additional_data: [u8],                      // signatures, keys, messages
//! }
//! struct Secp256r1SignatureOffsets {              // 7 x u16 LE = 14 bytes
//!     signature_offset: u16,
//!     signature_instruction_index: u16,
//!     public_key_offset: u16,
//!     public_key_instruction_index: u16,
//!     message_data_offset: u16,
//!     message_data_size: u16,
//!     message_instruction_index: u16,
//! }
//! ```
//!
//! How the precompile resolves each range (`agave-precompiles` 4.3.0,
//! `secp256r1.rs::get_data_slice`): an `*_instruction_index` of `0xFFFF` means
//! the precompile's own data; any other value selects that top-level
//! instruction's data; `offset + size` must not exceed that data's length.
//! The signature is 64 bytes, the public key 33 bytes, the message
//! `message_data_size` bytes. The precompile then requires
//! `1 <= r <= n - 1` and `1 <= s <= n/2`, decodes the key with OpenSSL
//! `EcPoint::from_bytes`, and verifies ECDSA over `SHA-256(message)`.
//!
//! ## Why every index must point at the precompile itself
//!
//! The precompile honours whatever instruction index an offsets record names.
//! If a program reads the public key and message out of the precompile
//! instruction's own bytes but the record points into *another* instruction,
//! the bytes the program reads were never verified: an attacker signs their
//! own message with their own key in instruction A, points the precompile at
//! A, and fills the precompile's own bytes at the same offsets with the
//! victim's key and message. [`Secp256r1Instruction::parse`] therefore
//! rejects any record whose three indices are not `0xFFFF` or the
//! precompile's own index.

use crate::{
    constants::{
        COMPRESSED_PUBKEY_SERIALIZED_SIZE, CURRENT_INSTRUCTION, FIELD_SIZE,
        MAX_SIGNATURES_PER_INSTRUCTION, SECP256R1_HALF_ORDER, SECP256R1_ORDER_MINUS_ONE,
        SIGNATURE_OFFSETS_SERIALIZED_SIZE, SIGNATURE_OFFSETS_START, SIGNATURE_SERIALIZED_SIZE,
    },
    error::IntrospectError,
    sysvar::read_u16,
};

/// One SIMD-0075 offsets record.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct Secp256r1SignatureOffsets {
    /// Offset of the 64-byte `r || s` signature.
    pub signature_offset: u16,
    /// Instruction holding the signature (`0xFFFF` = the precompile itself).
    pub signature_instruction_index: u16,
    /// Offset of the 33-byte compressed public key.
    pub public_key_offset: u16,
    /// Instruction holding the public key.
    pub public_key_instruction_index: u16,
    /// Offset of the message.
    pub message_data_offset: u16,
    /// Message length in bytes.
    pub message_data_size: u16,
    /// Instruction holding the message.
    pub message_instruction_index: u16,
}

impl Secp256r1SignatureOffsets {
    /// Decode from the 14-byte little-endian wire form.
    pub fn from_bytes(b: &[u8; SIGNATURE_OFFSETS_SERIALIZED_SIZE]) -> Self {
        let f = |i: usize| read_u16(b, i).unwrap_or_default();
        // `b` is exactly 14 bytes, so every read at 0, 2, .., 12 succeeds;
        // `unwrap_or_default` is only there to avoid a panic path.
        Self {
            signature_offset: f(0),
            signature_instruction_index: f(2),
            public_key_offset: f(4),
            public_key_instruction_index: f(6),
            message_data_offset: f(8),
            message_data_size: f(10),
            message_instruction_index: f(12),
        }
    }

    /// Encode to the 14-byte little-endian wire form.
    pub fn to_bytes(&self) -> [u8; SIGNATURE_OFFSETS_SERIALIZED_SIZE] {
        let fields = [
            self.signature_offset,
            self.signature_instruction_index,
            self.public_key_offset,
            self.public_key_instruction_index,
            self.message_data_offset,
            self.message_data_size,
            self.message_instruction_index,
        ];
        let mut out = [0u8; SIGNATURE_OFFSETS_SERIALIZED_SIZE];
        for (chunk, v) in out.chunks_exact_mut(2).zip(fields) {
            chunk.copy_from_slice(&v.to_le_bytes());
        }
        out
    }
}

/// One (signature, public key, message) triple that the precompile verified,
/// with all three ranges proven to lie inside the precompile instruction.
#[derive(Clone, Copy, Debug)]
pub struct Secp256r1Entry<'a> {
    /// Raw `r || s`, low-S.
    pub signature: &'a [u8; SIGNATURE_SERIALIZED_SIZE],
    /// SEC1-compressed public key.
    pub public_key: &'a [u8; COMPRESSED_PUBKEY_SERIALIZED_SIZE],
    /// The exact bytes that were hashed with SHA-256 and verified.
    pub message: &'a [u8],
    /// The record these ranges came from.
    pub offsets: Secp256r1SignatureOffsets,
}

/// Parsed and validated `Secp256r1SigVerify` instruction data.
#[derive(Clone, Copy, Debug)]
pub struct Secp256r1Instruction<'a> {
    data: &'a [u8],
    num_signatures: u8,
    own_index: u16,
}

impl<'a> Secp256r1Instruction<'a> {
    /// Parse precompile instruction `data` that sits at top-level index
    /// `own_index` in the transaction, validating **every** record:
    ///
    /// * `1 <= num_signatures <= 8`, with all records present;
    /// * each of the three instruction indices is `0xFFFF` or `own_index`;
    /// * each range is in bounds (checked arithmetic, no wrap-around);
    /// * the key starts with `0x02`/`0x03`;
    /// * `1 <= r <= n - 1` and `1 <= s <= n/2`.
    pub fn parse(data: &'a [u8], own_index: u16) -> Result<Self, IntrospectError> {
        if data.len() < SIGNATURE_OFFSETS_START {
            return Err(IntrospectError::TruncatedOffsets);
        }
        let num_signatures = *data.first().ok_or(IntrospectError::TruncatedOffsets)?;
        if num_signatures == 0 || num_signatures > MAX_SIGNATURES_PER_INSTRUCTION {
            return Err(IntrospectError::InvalidSignatureCount);
        }
        let this = Self {
            data,
            num_signatures,
            own_index,
        };
        for i in 0..num_signatures {
            this.entry(i)?;
        }
        Ok(this)
    }

    /// Number of signatures in the instruction.
    #[inline]
    pub fn num_signatures(&self) -> u8 {
        self.num_signatures
    }

    /// The top-level index this instruction was parsed at.
    #[inline]
    pub fn own_index(&self) -> u16 {
        self.own_index
    }

    /// Signature entry `i`, fully validated.
    pub fn entry(&self, i: u8) -> Result<Secp256r1Entry<'a>, IntrospectError> {
        if i >= self.num_signatures {
            return Err(IntrospectError::SignatureIndexOutOfBounds);
        }
        let start = usize::from(i)
            .checked_mul(SIGNATURE_OFFSETS_SERIALIZED_SIZE)
            .and_then(|o| o.checked_add(SIGNATURE_OFFSETS_START))
            .ok_or(IntrospectError::TruncatedOffsets)?;
        let end = start
            .checked_add(SIGNATURE_OFFSETS_SERIALIZED_SIZE)
            .ok_or(IntrospectError::TruncatedOffsets)?;
        let record: &[u8; SIGNATURE_OFFSETS_SERIALIZED_SIZE] = self
            .data
            .get(start..end)
            .and_then(|s| s.try_into().ok())
            .ok_or(IntrospectError::TruncatedOffsets)?;
        let offsets = Secp256r1SignatureOffsets::from_bytes(record);

        let own = self.own_index;
        if !is_self(offsets.signature_instruction_index, own)
            || !is_self(offsets.public_key_instruction_index, own)
            || !is_self(offsets.message_instruction_index, own)
        {
            return Err(IntrospectError::ForeignInstructionIndex);
        }

        let signature: &'a [u8; SIGNATURE_SERIALIZED_SIZE] =
            fixed(self.data, offsets.signature_offset)?;
        let public_key: &'a [u8; COMPRESSED_PUBKEY_SERIALIZED_SIZE] =
            fixed(self.data, offsets.public_key_offset)?;
        let message = range(
            self.data,
            offsets.message_data_offset,
            usize::from(offsets.message_data_size),
        )?;

        check_public_key_encoding(public_key)?;
        check_signature_scalars(signature)?;

        Ok(Secp256r1Entry {
            signature,
            public_key,
            message,
            offsets,
        })
    }

    /// Iterate over all entries. Each was already validated by
    /// [`Secp256r1Instruction::parse`], so the items are always `Ok`; they are
    /// still returned as `Result` so no error can be silently dropped.
    pub fn entries(
        &self,
    ) -> impl Iterator<Item = Result<Secp256r1Entry<'a>, IntrospectError>> + '_ {
        (0..self.num_signatures).map(move |i| self.entry(i))
    }

    /// Require that entry `i` verified exactly `expected_public_key` over
    /// exactly `expected_message`.
    pub fn expect_entry(
        &self,
        i: u8,
        expected_public_key: &[u8; COMPRESSED_PUBKEY_SERIALIZED_SIZE],
        expected_message: &[u8],
    ) -> Result<Secp256r1Entry<'a>, IntrospectError> {
        let entry = self.entry(i)?;
        if entry.public_key != expected_public_key {
            return Err(IntrospectError::PublicKeyMismatch);
        }
        if entry.message != expected_message {
            return Err(IntrospectError::MessageMismatch);
        }
        Ok(entry)
    }

    /// Find the first entry that verified `expected_public_key` over
    /// `expected_message`, returning its index. Fails with
    /// [`IntrospectError::MessageMismatch`] if some entry has the key but none
    /// has the message too, else [`IntrospectError::PublicKeyMismatch`].
    pub fn find(
        &self,
        expected_public_key: &[u8; COMPRESSED_PUBKEY_SERIALIZED_SIZE],
        expected_message: &[u8],
    ) -> Result<u8, IntrospectError> {
        let mut key_seen = false;
        for i in 0..self.num_signatures {
            let entry = self.entry(i)?;
            if entry.public_key == expected_public_key {
                if entry.message == expected_message {
                    return Ok(i);
                }
                key_seen = true;
            }
        }
        Err(if key_seen {
            IntrospectError::MessageMismatch
        } else {
            IntrospectError::PublicKeyMismatch
        })
    }
}

/// `true` if `index` refers to the precompile instruction itself.
#[inline]
fn is_self(index: u16, own_index: u16) -> bool {
    index == CURRENT_INSTRUCTION || index == own_index
}

#[inline]
fn range(data: &[u8], offset: u16, len: usize) -> Result<&[u8], IntrospectError> {
    let start = usize::from(offset);
    let end = start
        .checked_add(len)
        .ok_or(IntrospectError::OffsetOutOfBounds)?;
    data.get(start..end).ok_or(IntrospectError::OffsetOutOfBounds)
}

#[inline]
fn fixed<const N: usize>(data: &[u8], offset: u16) -> Result<&[u8; N], IntrospectError> {
    range(data, offset, N)?
        .try_into()
        .map_err(|_| IntrospectError::OffsetOutOfBounds)
}

/// Require a SEC1 compressed-point prefix. The precompile's OpenSSL decode
/// also rejects off-curve x values; that is not re-done here.
#[inline]
pub fn check_public_key_encoding(
    public_key: &[u8; COMPRESSED_PUBKEY_SERIALIZED_SIZE],
) -> Result<(), IntrospectError> {
    match public_key.first() {
        Some(0x02) | Some(0x03) => Ok(()),
        _ => Err(IntrospectError::InvalidPublicKeyEncoding),
    }
}

/// `true` if the big-endian scalar `s` satisfies `s <= n/2`.
///
/// Byte arrays compare lexicographically, which for equal-length big-endian
/// integers is numeric order.
#[inline]
pub fn is_low_s(s: &[u8; FIELD_SIZE]) -> bool {
    s <= &SECP256R1_HALF_ORDER
}

/// The precompile's range check, reproduced exactly:
/// `1 <= r <= n - 1` and `1 <= s <= n/2`
/// (`agave-precompiles` 4.3.0 `secp256r1.rs`, "Check that the signature is
/// generally in range").
pub fn check_signature_scalars(
    signature: &[u8; SIGNATURE_SERIALIZED_SIZE],
) -> Result<(), IntrospectError> {
    const ZERO: [u8; FIELD_SIZE] = [0u8; FIELD_SIZE];
    let (r, s) = split_signature(signature);
    if r == &ZERO || r > &SECP256R1_ORDER_MINUS_ONE || s == &ZERO {
        return Err(IntrospectError::ScalarOutOfRange);
    }
    if !is_low_s(s) {
        return Err(IntrospectError::HighS);
    }
    Ok(())
}

/// Split raw `r || s` into its two 32-byte halves without indexing.
#[inline]
pub fn split_signature(
    signature: &[u8; SIGNATURE_SERIALIZED_SIZE],
) -> (&[u8; FIELD_SIZE], &[u8; FIELD_SIZE]) {
    // Both halves exist by construction (64 = 2 x 32). The zero fallback is
    // unreachable, keeps the function panic-free, and would be rejected by
    // `check_signature_scalars` anyway.
    const ZERO: [u8; FIELD_SIZE] = [0u8; FIELD_SIZE];
    (
        signature.first_chunk::<FIELD_SIZE>().unwrap_or(&ZERO),
        signature.last_chunk::<FIELD_SIZE>().unwrap_or(&ZERO),
    )
}
