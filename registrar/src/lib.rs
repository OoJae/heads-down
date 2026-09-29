//! Heads Down registrar.
//!
//! Two jobs, both off-chain and both **without custody of anything**:
//! 1. Sign-In-With-Solana: single-use nonces and short-lived HMAC session tokens.
//! 2. Android Key Attestation: verify that a rig's P-256 key lives in the TEE/StrongBox of a
//!    genuine, locked device running the Heads Down app, then sign an Ed25519 voucher that the
//!    `heads_down` program checks through the Ed25519SigVerify precompile.
//!
//! See `README.md` for the protocol, the exact voucher preimage and the threat model.

#![forbid(unsafe_code)]
#![cfg_attr(
    not(test),
    deny(
        clippy::unwrap_used,
        clippy::expect_used,
        clippy::panic,
        clippy::indexing_slicing,
        clippy::arithmetic_side_effects
    )
)]

pub mod attest;
pub mod clock;
pub mod nonce;
pub mod session;
pub mod siws;
pub mod util;
pub mod voucher;
