//! `test-group` builds swap the anchors; they do not add a second set. Run
//! with `--features test-group,std`, with or without SGT_VERIFY_TEST_*.
#![cfg(all(feature = "test-group", feature = "std"))]

mod common;

use common::*;
use pinocchio::Address;
use sgt_verify::{
    anchors::{self, PUBLIC_TEST_AUTHORITY, PUBLIC_TEST_GROUP},
    testkit::{sgt_mint_bytes, sgt_token_account_bytes},
    verify_sgt_raw, RawAccount, SgtError,
};

/// The anchors this build was compiled with.
const GROUP: Address = anchors::SGT_GROUP;
const AUTHORITY: Address = anchors::SGT_AUTHORITY;

const MEMBER_MINT: Address = Address::new_from_array([7; 32]);
const HOLDER: Address = Address::new_from_array([8; 32]);
const TOKEN_ACCOUNT: Address = Address::new_from_array([9; 32]);

fn verify(mint_data: &[u8], ta_data: &[u8]) -> Result<sgt_verify::SgtInfo, SgtError> {
    verify_sgt_raw(
        RawAccount {
            address: &TOKEN_ACCOUNT,
            owner: &TOKEN_2022,
            data: ta_data,
        },
        RawAccount {
            address: &MEMBER_MINT,
            owner: &TOKEN_2022,
            data: mint_data,
        },
        &HOLDER,
    )
}

#[test]
fn anchors_come_from_the_environment_or_the_public_test_keys() {
    assert!(anchors::IS_TEST_GROUP_BUILD);
    match (
        option_env!("SGT_VERIFY_TEST_GROUP"),
        option_env!("SGT_VERIFY_TEST_AUTHORITY"),
    ) {
        (None, None) => {
            assert!(anchors::USING_PUBLIC_TEST_ANCHORS);
            assert_eq!(GROUP, PUBLIC_TEST_GROUP);
            assert_eq!(AUTHORITY, PUBLIC_TEST_AUTHORITY);
        }
        (Some(group), Some(authority)) => {
            assert!(!anchors::USING_PUBLIC_TEST_ANCHORS);
            assert_eq!(GROUP, group.parse::<Address>().unwrap());
            assert_eq!(AUTHORITY, authority.parse::<Address>().unwrap());
        }
        // Only one overridden: the other falls back, and the build is still
        // flagged as using public keys.
        _ => assert!(anchors::USING_PUBLIC_TEST_ANCHORS),
    }
    assert_ne!(GROUP, SGT_GROUP, "a test build must not verify the mainnet group");
    assert_ne!(AUTHORITY, SGT_AUTHORITY, "a test build must not verify the mainnet authority");
}

#[test]
fn synthetic_test_sgt_verifies() {
    let mint = sgt_mint_bytes(&MEMBER_MINT, &GROUP, &AUTHORITY, 1);
    let ta = sgt_token_account_bytes(&MEMBER_MINT, &HOLDER, true);
    let info = verify(&mint, &ta).unwrap();
    assert_eq!(info.member_number, 1);
    assert_eq!(info.mint, MEMBER_MINT);
    assert!(info.frozen);
}

#[test]
fn real_mainnet_sgts_are_rejected_by_a_test_build() {
    for sgt in real_sgts() {
        assert_eq!(sgt.verify(), Err(SgtError::MintAuthorityMismatch), "{}", sgt.label);
    }
}

#[test]
fn test_authority_with_mainnet_group_is_rejected() {
    let mint = sgt_mint_bytes(&MEMBER_MINT, &SGT_GROUP, &AUTHORITY, 1);
    let ta = sgt_token_account_bytes(&MEMBER_MINT, &HOLDER, true);
    assert_eq!(verify(&mint, &ta), Err(SgtError::GroupMismatch));
}

#[test]
fn mainnet_authority_with_test_group_is_rejected() {
    let mint = sgt_mint_bytes(&MEMBER_MINT, &GROUP, &SGT_AUTHORITY, 1);
    let ta = sgt_token_account_bytes(&MEMBER_MINT, &HOLDER, true);
    assert_eq!(verify(&mint, &ta), Err(SgtError::MintAuthorityMismatch));
}
