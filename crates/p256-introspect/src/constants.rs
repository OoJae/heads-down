//! Program ids, wire-format sizes and curve constants.
//!
//! Every value here is copied from, and unit-tested against, a primary source:
//!
//! * Wire format: SIMD-0075 "Precompile for verifying secp256r1 sig.", section
//!   *Detailed Design / Program*
//!   (<https://github.com/solana-foundation/solana-improvement-documents/blob/main/proposals/0075-precompile-for-secp256r1-sigverify.md>),
//!   and `solana-secp256r1-program` 3.0.0 `src/lib.rs`
//!   (`SIGNATURE_OFFSETS_START`, `SIGNATURE_OFFSETS_SERIALIZED_SIZE`, `DATA_START`,
//!   `COMPRESSED_PUBKEY_SERIALIZED_SIZE`, `SIGNATURE_SERIALIZED_SIZE`, `FIELD_SIZE`).
//! * Limits: `agave-precompiles` 4.3.0 `src/secp256r1.rs::verify`
//!   (`num_signatures == 0` and `num_signatures > 8` are both rejected).
//! * Curve order: SEC 2 v1.0 section 2.7.2 (secp256r1 `n`), identical to
//!   `SECP256R1_ORDER` in `solana-secp256r1-program` 3.0.0.

use pinocchio::Address;

/// `Secp256r1SigVerify1111111111111111111111111`, the SIMD-0075 precompile.
///
/// Stored as raw bytes (not `Address::from_str_const`) because the base58
/// `decode` feature of `solana-address` is not enabled by pinocchio on SBF.
/// `tests/constants.rs` checks these bytes against the base58 string.
pub const SECP256R1_PROGRAM_ID: Address = Address::new_from_array([
    0x06, 0x92, 0x0d, 0xec, 0x2f, 0xea, 0x71, 0xb5, 0xb7, 0x23, 0x81, 0x4d, 0x74, 0x2d, 0xa9, 0x03,
    0x1c, 0x83, 0xe7, 0x5f, 0xdb, 0x79, 0x5d, 0x56, 0x8e, 0x75, 0x47, 0x80, 0x20, 0x00, 0x00, 0x00,
]);

/// `Sysvar1nstructions1111111111111111111111111`, the instructions sysvar.
///
/// A program that reads "the instructions sysvar" from an account whose
/// address it did not compare against this constant can be fed a forged
/// sysvar. That is the bug class behind the February 2022 Wormhole exploit
/// (a deprecated `load_instruction_at` that skipped this check).
pub const INSTRUCTIONS_SYSVAR_ID: Address = Address::new_from_array([
    0x06, 0xa7, 0xd5, 0x17, 0x18, 0x7b, 0xd1, 0x66, 0x35, 0xda, 0xd4, 0x04, 0x55, 0xfd, 0xc2, 0xc0,
    0xc1, 0x24, 0xc6, 0x8f, 0x21, 0x56, 0x75, 0xa5, 0xdb, 0xba, 0xcb, 0x5f, 0x08, 0x00, 0x00, 0x00,
]);

/// Byte offset of the first `Secp256r1SignatureOffsets` record:
/// `num_signatures: u8` followed by one byte of padding.
pub const SIGNATURE_OFFSETS_START: usize = 2;

/// Serialized size of one `Secp256r1SignatureOffsets` record (7 x `u16` LE,
/// no padding between records).
pub const SIGNATURE_OFFSETS_SERIALIZED_SIZE: usize = 14;

/// First byte after a single offsets record (what the SDK builder uses as the
/// start of the payload for a one-signature instruction).
pub const DATA_START: usize = SIGNATURE_OFFSETS_START + SIGNATURE_OFFSETS_SERIALIZED_SIZE;

/// SEC1 compressed point: `0x02 | 0x03` followed by the 32-byte x coordinate.
/// SIMD-0075 accepts compressed keys only.
pub const COMPRESSED_PUBKEY_SERIALIZED_SIZE: usize = 33;

/// Raw `r || s`, each a 32-byte big-endian integer (IEEE P1363 form).
pub const SIGNATURE_SERIALIZED_SIZE: usize = 64;

/// Size in bytes of a P-256 scalar / field element.
pub const FIELD_SIZE: usize = 32;

/// Maximum number of signatures one precompile instruction may carry
/// (`agave-precompiles` 4.3.0 `secp256r1::verify`: `if num_signatures > 8`).
pub const MAX_SIGNATURES_PER_INSTRUCTION: u8 = 8;

/// Special `*_instruction_index` value meaning "the precompile instruction
/// itself" (SIMD-0075 `get_data_slice`: "The special value `0xFFFF` means
/// current instruction").
pub const CURRENT_INSTRUCTION: u16 = u16::MAX;

/// secp256r1 group order `n` (SEC 2 section 2.7.2), big-endian.
pub const SECP256R1_ORDER: [u8; FIELD_SIZE] = [
    0xFF, 0xFF, 0xFF, 0xFF, 0x00, 0x00, 0x00, 0x00, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF,
    0xBC, 0xE6, 0xFA, 0xAD, 0xA7, 0x17, 0x9E, 0x84, 0xF3, 0xB9, 0xCA, 0xC2, 0xFC, 0x63, 0x25, 0x51,
];

/// `n - 1`, the largest valid `r`, big-endian.
pub const SECP256R1_ORDER_MINUS_ONE: [u8; FIELD_SIZE] = [
    0xFF, 0xFF, 0xFF, 0xFF, 0x00, 0x00, 0x00, 0x00, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF,
    0xBC, 0xE6, 0xFA, 0xAD, 0xA7, 0x17, 0x9E, 0x84, 0xF3, 0xB9, 0xCA, 0xC2, 0xFC, 0x63, 0x25, 0x50,
];

/// `floor(n / 2)`, the largest `s` the precompile accepts ("low-S"), big-endian.
pub const SECP256R1_HALF_ORDER: [u8; FIELD_SIZE] = [
    0x7F, 0xFF, 0xFF, 0xFF, 0x80, 0x00, 0x00, 0x00, 0x7F, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF,
    0xDE, 0x73, 0x7D, 0x56, 0xD3, 0x8B, 0xCF, 0x42, 0x79, 0xDC, 0xE5, 0x61, 0x7E, 0x31, 0x92, 0xA8,
];
