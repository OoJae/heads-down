//! Host-side helpers (feature `client`). Never compiled into an SBF program.
//!
//! These turn what Android Keystore produces into what the SIMD-0075
//! precompile accepts:
//!
//! | Android Keystore output                      | Precompile input             | Helper |
//! |----------------------------------------------|------------------------------|--------|
//! | `Signature("SHA256withECDSA").sign()`: ASN.1 DER `ECDSA-Sig-Value`, `s` may be high | raw 64-byte `r \|\| s`, `s <= n/2` | [`der_to_low_s_raw`] |
//! | `PublicKey.getEncoded()`: X.509 `SubjectPublicKeyInfo` DER (91 bytes) | 33-byte SEC1 compressed | [`spki_der_to_compressed`] |
//! | `ECPublicKey.getW()` as `0x04 \|\| x \|\| y`  | 33-byte SEC1 compressed      | [`compress_public_key`] |
//!
//! The message is **not** pre-hashed by the caller: Keystore's
//! `SHA256withECDSA` hashes it with SHA-256, and so does the precompile.

extern crate std;

use std::vec::Vec;

use p256::{
    ecdsa::{signature::Verifier, Signature, VerifyingKey},
    elliptic_curve::sec1::ToSec1Point,
    pkcs8::DecodePublicKey,
    PublicKey,
};

use crate::{
    constants::{
        COMPRESSED_PUBKEY_SERIALIZED_SIZE, CURRENT_INSTRUCTION, MAX_SIGNATURES_PER_INSTRUCTION,
        SIGNATURE_OFFSETS_SERIALIZED_SIZE, SIGNATURE_OFFSETS_START, SIGNATURE_SERIALIZED_SIZE,
    },
    precompile::{check_signature_scalars, Secp256r1SignatureOffsets},
};

/// Errors from the host helpers.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ClientError {
    /// Not a valid strict-DER ECDSA P-256 signature (or `r`/`s` out of range).
    InvalidDerSignature,
    /// Not a valid raw `r || s` signature (zero or `>= n` component).
    InvalidRawSignature,
    /// Not a valid P-256 public key encoding, or not a point on the curve.
    InvalidPublicKey,
    /// Zero entries, or more than [`MAX_SIGNATURES_PER_INSTRUCTION`].
    InvalidSignatureCount,
    /// The instruction data would exceed what `u16` offsets can address.
    InstructionTooLarge,
    /// Signature does not verify (host mirror of the precompile check).
    VerificationFailed,
    /// Signature is high-S; the precompile will reject it.
    HighS,
}

impl core::fmt::Display for ClientError {
    fn fmt(&self, f: &mut core::fmt::Formatter<'_>) -> core::fmt::Result {
        core::fmt::Debug::fmt(self, f)
    }
}

impl std::error::Error for ClientError {}

/// Parse a strict ASN.1 DER `ECDSA-Sig-Value` (what Android's
/// `Signature.getInstance("SHA256withECDSA").sign()` returns: 70 to 72 bytes
/// typically) into raw 64-byte `r || s`, left-padding each component to 32
/// bytes. `s` is returned as-is; see [`normalize_low_s`].
pub fn der_to_raw(der: &[u8]) -> Result<[u8; SIGNATURE_SERIALIZED_SIZE], ClientError> {
    let sig = Signature::from_der(der).map_err(|_| ClientError::InvalidDerSignature)?;
    Ok(sig.to_bytes().into())
}

/// Low-S normalization: if `s > n/2`, replace it with `n - s`.
///
/// `(r, s)` and `(r, n - s)` are both valid ECDSA signatures of the same
/// message under the same key, so this needs no private key and can be done
/// on the phone, by a relayer or by the crank. The precompile accepts only
/// the low form (SIMD-0075 "Signature Malleability").
pub fn normalize_low_s(
    raw: &[u8; SIGNATURE_SERIALIZED_SIZE],
) -> Result<[u8; SIGNATURE_SERIALIZED_SIZE], ClientError> {
    let sig = Signature::from_slice(raw).map_err(|_| ClientError::InvalidRawSignature)?;
    Ok(sig.normalize_s().to_bytes().into())
}

/// [`der_to_raw`] then [`normalize_low_s`]: Keystore signature in,
/// precompile-ready signature out.
pub fn der_to_low_s_raw(der: &[u8]) -> Result<[u8; SIGNATURE_SERIALIZED_SIZE], ClientError> {
    let sig = Signature::from_der(der).map_err(|_| ClientError::InvalidDerSignature)?;
    Ok(sig.normalize_s().to_bytes().into())
}

/// SEC1 public key (65-byte uncompressed `0x04 || x || y`, or already
/// compressed) to 33-byte compressed form. Rejects points not on the curve.
pub fn compress_public_key(
    sec1: &[u8],
) -> Result<[u8; COMPRESSED_PUBKEY_SERIALIZED_SIZE], ClientError> {
    let pk = PublicKey::from_sec1_bytes(sec1).map_err(|_| ClientError::InvalidPublicKey)?;
    to_compressed(&pk)
}

/// X.509 `SubjectPublicKeyInfo` DER (Android `PublicKey.getEncoded()`) to
/// 33-byte compressed form. Requires the `id-ecPublicKey` / `prime256v1`
/// algorithm identifiers.
pub fn spki_der_to_compressed(
    spki_der: &[u8],
) -> Result<[u8; COMPRESSED_PUBKEY_SERIALIZED_SIZE], ClientError> {
    let pk = PublicKey::from_public_key_der(spki_der).map_err(|_| ClientError::InvalidPublicKey)?;
    to_compressed(&pk)
}

fn to_compressed(pk: &PublicKey) -> Result<[u8; COMPRESSED_PUBKEY_SERIALIZED_SIZE], ClientError> {
    pk.to_sec1_point(true)
        .as_bytes()
        .try_into()
        .map_err(|_| ClientError::InvalidPublicKey)
}

/// Off-chain mirror of what the precompile checks for one entry: range and
/// low-S, then ECDSA-P256 over `SHA-256(message)`. Use it to pre-flight a
/// heartbeat before paying for a transaction.
pub fn verify_like_precompile(
    public_key: &[u8; COMPRESSED_PUBKEY_SERIALIZED_SIZE],
    message: &[u8],
    signature: &[u8; SIGNATURE_SERIALIZED_SIZE],
) -> Result<(), ClientError> {
    check_signature_scalars(signature).map_err(|e| match e {
        crate::IntrospectError::HighS => ClientError::HighS,
        _ => ClientError::InvalidRawSignature,
    })?;
    let vk = VerifyingKey::from_sec1_bytes(public_key).map_err(|_| ClientError::InvalidPublicKey)?;
    let sig = Signature::from_slice(signature).map_err(|_| ClientError::InvalidRawSignature)?;
    vk.verify(message, &sig)
        .map_err(|_| ClientError::VerificationFailed)
}

/// One signature to place in a precompile instruction.
#[derive(Clone, Copy, Debug)]
pub struct SignatureInput<'a> {
    /// Raw low-S `r || s`.
    pub signature: [u8; SIGNATURE_SERIALIZED_SIZE],
    /// Compressed public key.
    pub public_key: [u8; COMPRESSED_PUBKEY_SERIALIZED_SIZE],
    /// Message bytes (the precompile hashes them with SHA-256).
    pub message: &'a [u8],
}

/// Build self-contained `Secp256r1SigVerify` instruction data for 1..=8
/// signatures, with every instruction index set to `0xFFFF` ("this
/// instruction"), so the data is valid wherever the instruction is placed.
///
/// Layout: `[n, 0, offsets x n, (pubkey, signature, message) x n]`, the same
/// per-entry order the SDK's `new_secp256r1_instruction_with_signature` uses.
/// Program id: [`crate::SECP256R1_PROGRAM_ID`]; the instruction takes no
/// accounts.
pub fn build_instruction_data(entries: &[SignatureInput<'_>]) -> Result<Vec<u8>, ClientError> {
    build_instruction_data_with_index(entries, CURRENT_INSTRUCTION)
}

/// Like [`build_instruction_data`] but writes `instruction_index` into every
/// `*_instruction_index` field (use the precompile's own top-level index).
/// Any other value produces data this crate's on-chain check rejects.
pub fn build_instruction_data_with_index(
    entries: &[SignatureInput<'_>],
    instruction_index: u16,
) -> Result<Vec<u8>, ClientError> {
    const TOO_LARGE: ClientError = ClientError::InstructionTooLarge;
    let n = u8::try_from(entries.len()).map_err(|_| ClientError::InvalidSignatureCount)?;
    if n == 0 || n > MAX_SIGNATURES_PER_INSTRUCTION {
        return Err(ClientError::InvalidSignatureCount);
    }

    // Offsets records first (filled in below), then the payload.
    let mut records: Vec<u8> = Vec::with_capacity(
        usize::from(n).saturating_mul(SIGNATURE_OFFSETS_SERIALIZED_SIZE),
    );
    let mut payload: Vec<u8> = Vec::new();
    let header_len = usize::from(n)
        .checked_mul(SIGNATURE_OFFSETS_SERIALIZED_SIZE)
        .and_then(|v| v.checked_add(SIGNATURE_OFFSETS_START))
        .ok_or(TOO_LARGE)?;
    // Absolute offset of the next payload byte, as a u16 (the wire width).
    let pos = |payload: &Vec<u8>| -> Result<u16, ClientError> {
        header_len
            .checked_add(payload.len())
            .and_then(|v| u16::try_from(v).ok())
            .ok_or(TOO_LARGE)
    };

    for e in entries {
        let public_key_offset = pos(&payload)?;
        payload.extend_from_slice(&e.public_key);
        let signature_offset = pos(&payload)?;
        payload.extend_from_slice(&e.signature);
        let message_data_offset = pos(&payload)?;
        payload.extend_from_slice(e.message);
        // The whole message must also be addressable.
        pos(&payload)?;

        let offsets = Secp256r1SignatureOffsets {
            signature_offset,
            signature_instruction_index: instruction_index,
            public_key_offset,
            public_key_instruction_index: instruction_index,
            message_data_offset,
            message_data_size: u16::try_from(e.message.len()).map_err(|_| TOO_LARGE)?,
            message_instruction_index: instruction_index,
        };
        records.extend_from_slice(&offsets.to_bytes());
    }

    let mut data = Vec::with_capacity(header_len.saturating_add(payload.len()));
    data.push(n);
    data.push(0); // padding byte, ignored by the precompile
    data.extend_from_slice(&records);
    data.extend_from_slice(&payload);
    Ok(data)
}

/// Instruction-data size for `n` signatures over messages of `message_len`
/// bytes each: `2 + n * (14 + 33 + 64 + message_len)`. Saturates instead of
/// overflowing.
pub const fn instruction_data_len(n: usize, message_len: usize) -> usize {
    let per_entry = (SIGNATURE_OFFSETS_SERIALIZED_SIZE
        + COMPRESSED_PUBKEY_SERIALIZED_SIZE
        + SIGNATURE_SERIALIZED_SIZE)
        .saturating_add(message_len);
    SIGNATURE_OFFSETS_START.saturating_add(n.saturating_mul(per_entry))
}
