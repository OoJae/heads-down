//! The test kit's synthetic accounts are byte-for-byte what Token-2022 wrote
//! on mainnet, and its ATA derivation finds the real holder accounts.
#![cfg(feature = "std")]

mod common;

use common::*;
use sgt_verify::{
    anchors::{MAINNET_SGT_AUTHORITY, MAINNET_SGT_GROUP},
    layout::{SGT_MINT_LEN, SGT_TOKEN_ACCOUNT_LEN},
    testkit::{
        associated_token_address, create_test_group, issue_test_sgt, sgt_mint_bytes,
        sgt_token_account_bytes, SGT_MINT_INITIAL_LEN, TEST_GROUP_MINT_INITIAL_LEN,
        TEST_GROUP_MINT_LEN,
    },
};

#[test]
fn synthetic_mint_is_byte_identical_to_mainnet() {
    for sgt in real_sgts() {
        let synth = sgt_mint_bytes(
            &sgt.mint.address,
            &MAINNET_SGT_GROUP,
            &MAINNET_SGT_AUTHORITY,
            sgt.member_number,
        );
        assert_eq!(synth.len(), SGT_MINT_LEN);
        assert_eq!(synth, sgt.mint.data, "{}", sgt.label);
    }
}

#[test]
fn synthetic_token_account_is_byte_identical_to_mainnet() {
    for sgt in real_sgts() {
        let synth = sgt_token_account_bytes(&sgt.mint.address, &sgt.holder, true);
        assert_eq!(synth.len(), SGT_TOKEN_ACCOUNT_LEN);
        assert_eq!(synth, sgt.token_account.data, "{}", sgt.label);
    }
}

#[test]
fn real_holdings_live_at_the_token_2022_ata() {
    for sgt in real_sgts() {
        assert_eq!(
            associated_token_address(&sgt.holder, &sgt.mint.address),
            sgt.token_account.address,
            "{}",
            sgt.label
        );
    }
}

#[test]
fn account_sizes_match_mainnet_issuance() {
    // Solana Mobile's issuance creates the mint with `space: 374`; Token-2022
    // then reallocs by one TokenGroupMember entry (4 + 72) to 450.
    assert_eq!(SGT_MINT_INITIAL_LEN, 374);
    assert_eq!(SGT_MINT_LEN, 450);
    assert_eq!(TEST_GROUP_MINT_INITIAL_LEN, 270);
    assert_eq!(TEST_GROUP_MINT_LEN, 354);
    assert_ne!(TEST_GROUP_MINT_LEN, 355, "must never equal Multisig::LEN");
}

#[test]
fn instruction_sequences_have_the_documented_shape() {
    let (payer, group, authority, member, holder) = (
        pinocchio::Address::new_from_array([1; 32]),
        pinocchio::Address::new_from_array([2; 32]),
        pinocchio::Address::new_from_array([3; 32]),
        pinocchio::Address::new_from_array([4; 32]),
        pinocchio::Address::new_from_array([5; 32]),
    );
    let g = create_test_group(&payer, &group, &authority, 10, 1);
    assert_eq!(g.len(), 5);
    let s = issue_test_sgt(&payer, &group, &authority, &member, &holder, 1);
    // create, 4 pointer/authority extensions, init mint, member, ATA, mint, freeze
    assert_eq!(s.len(), 10);
    assert_eq!(s.last().unwrap().data, vec![10], "ends with FreezeAccount");
}
