//! Trust anchors: which Token-2022 group an SGT must belong to and which key
//! must be its mint authority.
//!
//! A mainnet build (no `test-group` feature) hardcodes Solana Mobile's real
//! values and has no code path that accepts anything else.
//!
//! A `test-group` build replaces both with values read at compile time from
//! the `SGT_VERIFY_TEST_GROUP` and `SGT_VERIFY_TEST_AUTHORITY` environment
//! variables (base58). If they are unset it falls back to
//! [`PUBLIC_TEST_GROUP`] and [`PUBLIC_TEST_AUTHORITY`], whose private keys are
//! derived from **public** seeds (see `testkit`) so local LiteSVM tests work
//! out of the box. Anyone can recreate those keys, so a devnet deployment
//! should always set the variables to keys it controls; assert
//! `!USING_PUBLIC_TEST_ANCHORS` in such builds.

use pinocchio::Address;

/// SPL Token-2022 program (`TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb`).
pub const TOKEN_2022_PROGRAM_ID: Address =
    Address::from_str_const("TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb");

/// Mainnet SGT collection: a Token-2022 mint carrying the `TokenGroup`
/// extension ("Seeker Genesis Token", max size 1,000,000). Every SGT mint's
/// `TokenGroupMember.group` and `MetadataPointer.metadata_address` are this.
pub const MAINNET_SGT_GROUP: Address =
    Address::from_str_const("GT22s89nU4iWFkNXj1Bw6uYhJJWDRPpShHt4Bk8f99Te");

/// Mainnet SGT authority: mint, freeze, close and pointer authority, permanent
/// delegate of every SGT, and update authority of the group.
pub const MAINNET_SGT_AUTHORITY: Address =
    Address::from_str_const("GT2zuHVaZQYZSyQMgJPLzvkmyztfyXg2NJunqFp4p3A4");

/// Test-only group whose keypair comes from the public seed
/// `sha256("sgt-verify/public-test-group/v1")`. Never trust it on a real cluster.
pub const PUBLIC_TEST_GROUP: Address =
    Address::from_str_const("8zzemreeALfyxLYje3GRzuiVETUfNXK3tDcpJgVVpY8X");

/// Test-only authority whose keypair comes from the public seed
/// `sha256("sgt-verify/public-test-authority/v1")`. Never trust it on a real cluster.
pub const PUBLIC_TEST_AUTHORITY: Address =
    Address::from_str_const("18h7xbzxhau9SNa4uA8r1ypBWBdHwh2RjGtd7HwAE6L");

/// The group this build verifies against.
#[cfg(not(feature = "test-group"))]
pub const SGT_GROUP: Address = MAINNET_SGT_GROUP;

/// The authority this build verifies against.
#[cfg(not(feature = "test-group"))]
pub const SGT_AUTHORITY: Address = MAINNET_SGT_AUTHORITY;

/// The group this build verifies against (`test-group` build).
#[cfg(feature = "test-group")]
pub const SGT_GROUP: Address = match option_env!("SGT_VERIFY_TEST_GROUP") {
    Some(group) => Address::from_str_const(group),
    None => PUBLIC_TEST_GROUP,
};

/// The authority this build verifies against (`test-group` build).
#[cfg(feature = "test-group")]
pub const SGT_AUTHORITY: Address = match option_env!("SGT_VERIFY_TEST_AUTHORITY") {
    Some(authority) => Address::from_str_const(authority),
    None => PUBLIC_TEST_AUTHORITY,
};

/// `true` when this build verifies against a test group instead of mainnet.
pub const IS_TEST_GROUP_BUILD: bool = cfg!(feature = "test-group");

/// `true` when this is a `test-group` build that fell back to the public test
/// keys (anyone can mint "SGTs" for those). Devnet deployments should assert
/// this is `false`.
pub const USING_PUBLIC_TEST_ANCHORS: bool = IS_TEST_GROUP_BUILD
    && (option_env!("SGT_VERIFY_TEST_GROUP").is_none()
        || option_env!("SGT_VERIFY_TEST_AUTHORITY").is_none());
