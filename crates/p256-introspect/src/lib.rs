//! # p256-introspect
//!
//! Verify, from inside a Solana program, that the **current transaction**
//! carries a `Secp256r1SigVerify` precompile instruction that verified a given
//! `(compressed P-256 public key, message)` pair, for example a heartbeat
//! signed by an Android Keystore key.
//!
//! `no_std`, no `unsafe`, no allocation, no panics on attacker input, and
//! compatible with Pinocchio 0.11 (`AccountView`, `ProgramError`).
//!
//! ## What is checked
//!
//! 1. The sysvar account's address is `Sysvar1nstructions1111111111111111111111111`
//!    ([`check_instructions_sysvar`]); otherwise its bytes could be forged
//!    (the Wormhole bug class).
//! 2. The instruction at the caller-supplied index exists and its program id
//!    is `Secp256r1SigVerify1111111111111111111111111`.
//! 3. The SIMD-0075 layout is parsed exactly: `num_signatures` (1..=8, as
//!    Agave enforces), one padding byte, then 14-byte offset records.
//! 4. **Every** record's `signature_instruction_index`,
//!    `public_key_instruction_index` and `message_instruction_index` equals
//!    `0xFFFF` or the precompile's own index, so the bytes this crate returns
//!    are the bytes the precompile verified.
//! 5. Every range is bounds-checked with checked arithmetic.
//! 6. `1 <= r <= n - 1` and `1 <= s <= n/2` (low-S), mirroring the precompile.
//! 7. The key is SEC1-compressed and the caller's expected key and message
//!    match byte for byte.
//!
//! ## Sources
//!
//! * SIMD-0075, "Precompile for verifying secp256r1 sig."
//!   <https://github.com/solana-foundation/solana-improvement-documents/blob/main/proposals/0075-precompile-for-secp256r1-sigverify.md>
//! * Agave precompile, `agave-precompiles` 4.3.0 `src/secp256r1.rs`
//!   (<https://github.com/anza-xyz/agave/blob/master/precompiles/src/secp256r1.rs>).
//!   Where the SIMD pseudocode and Agave differ (SIMD tolerates
//!   `num_signatures == 0` with 1-byte data; Agave rejects any 0 and caps at
//!   8), this crate follows the stricter Agave behaviour.
//! * `solana-secp256r1-program` 3.0.0 (constants, offsets struct).
//! * `solana-instructions-sysvar` 3.0.1 (sysvar serialization layout).
//!
//! ## Example (Pinocchio)
//!
//! ```ignore
//! use p256_introspect::verify_secp256r1_signature;
//!
//! // accounts[0] = instructions sysvar
//! verify_secp256r1_signature(
//!     &accounts[0],
//!     precompile_ix_index,   // where the client put the precompile ix
//!     0,                     // entry within that ix
//!     &rig.p256_pubkey,      // [u8; 33] registered earlier
//!     &expected_message,     // rebuilt on-chain from program state
//! )?;
//! ```
//!
//! Host-side helpers (DER -> raw, low-S, key compression, instruction
//! builder) live in [`client`] behind the `client` feature.

#![cfg_attr(not(any(test, feature = "std")), no_std)]
#![forbid(unsafe_code)]
#![deny(missing_docs)]
#![cfg_attr(
    not(test),
    deny(
        clippy::arithmetic_side_effects,
        clippy::indexing_slicing,
        clippy::unwrap_used,
        clippy::expect_used,
        clippy::panic,
        clippy::cast_possible_truncation
    )
)]

pub mod constants;
pub mod error;
pub mod precompile;
pub mod sysvar;
mod verify;


pub use constants::*;
pub use error::{IntrospectError, ERROR_CODE_BASE};
pub use precompile::{
    check_public_key_encoding, check_signature_scalars, is_low_s, split_signature,
    Secp256r1Entry, Secp256r1Instruction, Secp256r1SignatureOffsets,
};
pub use sysvar::{InstructionsSysvar, IntrospectedInstruction};
pub use verify::{
    check_instructions_sysvar, load_secp256r1_instruction, verify_secp256r1_signature,
    with_secp256r1_instruction,
};
