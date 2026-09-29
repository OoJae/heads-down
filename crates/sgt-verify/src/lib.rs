//! # sgt-verify
//!
//! In-program verification of the **Seeker Genesis Token (SGT)**, the
//! soulbound-by-freeze Token-2022 NFT that Solana Mobile mints once per Seeker
//! device, for `no_std` Pinocchio programs.
//!
//! ```ignore
//! use sgt_verify::{verify_sgt, SgtInfo};
//!
//! let SgtInfo { mint, member_number, .. } =
//!     verify_sgt(token_account, sgt_mint, signer.address())?;
//! // Key the device seat by `mint`, never by wallet.
//! ```
//!
//! What is checked, why, and the threat model are documented in `README.md`.
//! The short version: the one fact an attacker cannot forge is the mint's
//! `TokenGroupMember.group == GT22s89…`, because Token-2022 only writes that
//! extension after the group's update authority (Solana Mobile, `GT2zuH…`)
//! signs. Every other field of a Token-2022 mint — including
//! `mint_authority`, which ORE's removed `claim_seeker` relied on — can be set
//! to `GT2zuH…` by anyone. See `tests/spoof.rs` for the forgery that defeats a
//! mint-authority-only check.
//!
//! ## Features
//! - `test-group`: swap the hardcoded mainnet anchors for a devnet/test group
//!   (see [`anchors`]). Never enable it in a mainnet build.
//! - `mainnet`: a guard; combining it with `test-group` is a compile error.
//! - `std`: host-side [`testkit`] (instruction builders for a test SGT group,
//!   byte-exact synthetic SGT accounts, an `AccountView` harness).

#![no_std]
#![deny(unsafe_code)]
#![deny(missing_docs)]
#![cfg_attr(
    not(test),
    deny(
        clippy::arithmetic_side_effects,
        clippy::indexing_slicing,
        clippy::panic,
        clippy::unwrap_used,
        clippy::expect_used,
        clippy::cast_possible_truncation
    )
)]

#[cfg(feature = "std")]
extern crate std;

#[cfg(all(feature = "mainnet", feature = "test-group"))]
compile_error!(
    "sgt-verify: the `mainnet` and `test-group` features are mutually exclusive; \
     a mainnet build must verify against the real SGT group and authority"
);

pub mod anchors;
#[allow(dead_code)]
mod bytes;
pub mod error;
pub mod layout;
pub mod tlv;

pub use anchors::{SGT_AUTHORITY, SGT_GROUP, TOKEN_2022_PROGRAM_ID};
pub use error::SgtError;
