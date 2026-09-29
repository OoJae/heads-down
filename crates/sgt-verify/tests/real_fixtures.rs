//! Real mainnet SGTs must verify, and the crate's parser must agree with the
//! RPC's own spl-token-2022 parser on every field it reads.
//!
//! Mainnet anchors only: a `test-group` build rejects these by design (see
//! `tests/test_group.rs`).
#![cfg(not(feature = "test-group"))]

mod common;

use common::*;
use pinocchio::Address;
use sgt_verify::{
    anchors, layout,
    parse::{parse_mint, parse_token_account},
    verify_sgt, SgtError, SgtInfo,
};

#[test]
fn compiled_anchors_are_the_mainnet_values() {
    assert_eq!(anchors::SGT_GROUP, SGT_GROUP);
    assert_eq!(anchors::SGT_AUTHORITY, SGT_AUTHORITY);
    assert_eq!(anchors::TOKEN_2022_PROGRAM_ID, TOKEN_2022);
    const { assert!(!anchors::IS_TEST_GROUP_BUILD) };
    const { assert!(!anchors::USING_PUBLIC_TEST_ANCHORS) };
}

#[test]
fn every_real_sgt_verifies() {
    let sgts = real_sgts();
    assert!(sgts.len() >= 2, "expected at least two real SGT fixtures");
    for sgt in &sgts {
        let info = sgt
            .verify()
            .unwrap_or_else(|e| panic!("{} rejected: {e:?}", sgt.label));
        assert_eq!(
            info,
            SgtInfo {
                mint: sgt.mint.address,
                member_number: sgt.member_number,
                frozen: sgt.state == "frozen",
            },
            "{}",
            sgt.label
        );
    }
}

#[test]
fn account_view_entrypoint_verifies_real_sgts() {
    for sgt in real_sgts() {
        let mut token_account = HostAccount::from_fixture(&sgt.token_account);
        let mut mint = HostAccount::from_fixture(&sgt.mint);
        let info = verify_sgt(&token_account.view(), &mint.view(), &sgt.holder).unwrap();
        assert_eq!(info.member_number, sgt.member_number);
        assert_eq!(info.mint, sgt.mint.address);
    }
}

#[test]
fn account_view_entrypoint_checks_owner_before_borrowing() {
    let sgt = member_20();
    let mut token_account = HostAccount::new(
        &sgt.token_account.address,
        &SPL_TOKEN,
        sgt.token_account.lamports,
        &sgt.token_account.data,
    );
    let mut mint = HostAccount::from_fixture(&sgt.mint);
    assert_eq!(
        verify_sgt(&token_account.view(), &mint.view(), &sgt.holder),
        Err(SgtError::TokenAccountNotToken2022)
    );
}

#[test]
fn account_view_entrypoint_fails_closed_on_borrow_conflict() {
    let sgt = member_20();
    let mut token_account = HostAccount::from_fixture(&sgt.token_account);
    let mut mint = HostAccount::from_fixture(&sgt.mint);
    let mut mint_view = mint.view();
    let token_view = token_account.view();
    let guard = mint_view.try_borrow_mut().unwrap();
    let result = verify_sgt(&token_view, &mint.view(), &sgt.holder);
    drop(guard);
    assert_eq!(result, Err(SgtError::AccountBorrowFailed));
}

#[test]
fn parser_agrees_with_rpc_jsonparsed_oracle() {
    for sgt in real_sgts() {
        let mint = parse_mint(&sgt.mint.data).unwrap();
        assert_eq!(mint.mint_authority, Some(sgt.mint_authority.as_array()));
        assert_eq!(mint.freeze_authority, Some(sgt.freeze_authority.as_array()));
        assert_eq!(mint.supply, sgt.supply);
        assert_eq!(mint.decimals, sgt.decimals);
        let ours: Vec<u16> = mint.extensions.iter().map(|e| e.extension_type).collect();
        let rpc: Vec<u16> = sgt.mint_extensions.iter().map(|n| extension_number(n)).collect();
        assert_eq!(ours, rpc, "{} mint extensions", sgt.label);

        let account = parse_token_account(&sgt.token_account.data).unwrap();
        assert_eq!(account.mint, sgt.mint.address.as_array());
        assert_eq!(account.owner, sgt.holder.as_array());
        assert_eq!(account.amount, 1);
        assert_eq!(account.frozen, sgt.state == "frozen");
        assert_eq!(account.is_native, None);
        let ours: Vec<u16> = account.extensions.iter().map(|e| e.extension_type).collect();
        let rpc: Vec<u16> = sgt
            .token_account_extensions
            .iter()
            .map(|n| extension_number(n))
            .collect();
        assert_eq!(ours, rpc, "{} account extensions", sgt.label);
    }
}

/// The observed shape of every real SGT, pinned so a layout change on
/// Solana Mobile's side shows up as a clear test failure.
#[test]
fn real_sgts_have_the_documented_shape() {
    for sgt in real_sgts() {
        assert_eq!(sgt.mint.owner, TOKEN_2022);
        assert_eq!(sgt.token_account.owner, TOKEN_2022);
        assert_eq!(sgt.mint.data.len(), layout::SGT_MINT_LEN);
        assert_eq!(sgt.token_account.data.len(), layout::SGT_TOKEN_ACCOUNT_LEN);
        assert_eq!(
            sgt.mint_extensions,
            [
                "metadataPointer",
                "permanentDelegate",
                "mintCloseAuthority",
                "groupMemberPointer",
                "tokenGroupMember"
            ]
        );
        assert_eq!(sgt.token_account_extensions, ["immutableOwner"]);
        // GroupMemberPointer points at the mint itself.
        let off = value_offset(&sgt.mint.data, 22);
        assert_eq!(&sgt.mint.data[off..off + 32], SGT_AUTHORITY.as_ref());
        assert_eq!(&sgt.mint.data[off + 32..off + 64], sgt.mint.address.as_ref());
        // MetadataPointer points at the group, not at the mint.
        let off = value_offset(&sgt.mint.data, 18);
        assert_eq!(&sgt.mint.data[off + 32..off + 64], SGT_GROUP.as_ref());
    }
}

/// Solana Mobile freezes every SGT holding: issuance is
/// `... mintToChecked(1) -> freezeAccount`, and a permissioned move is
/// `thaw -> transferChecked (permanent delegate) -> freeze`. A verifier that
/// rejected frozen accounts would reject every real SGT.
#[test]
fn real_sgt_holdings_are_frozen_and_accepted() {
    for sgt in real_sgts() {
        assert_eq!(sgt.state, "frozen", "{}", sgt.label);
        assert_eq!(sgt.token_account.data[layout::account::STATE], 2);
        assert!(sgt.verify().unwrap().frozen);
    }
}

/// The premise behind the group check: GT22s89 is a Token-2022 mint whose
/// TokenGroup extension is controlled by GT2zuH, so Token-2022 will only add
/// members to it with GT2zuH's signature.
#[test]
fn group_fixture_is_a_token_group_controlled_by_the_authority() {
    let group = group_fixture();
    assert_eq!(group.address, SGT_GROUP);
    assert_eq!(group.owner, TOKEN_2022);
    let state = parse_mint(&group.data).unwrap();
    assert_eq!(state.supply, 0);
    assert_eq!(state.mint_authority, Some(SGT_AUTHORITY.as_array()));
    let token_group = state
        .extensions
        .get_sized::<80>(layout::extension_type::TOKEN_GROUP)
        .unwrap()
        .expect("TokenGroup extension");
    assert_eq!(&token_group[0..32], SGT_AUTHORITY.as_ref(), "update authority");
    assert_eq!(&token_group[32..64], SGT_GROUP.as_ref(), "group mint");
    let size = u64::from_le_bytes(token_group[64..72].try_into().unwrap());
    let max_size = u64::from_le_bytes(token_group[72..80].try_into().unwrap());
    assert!(size >= 121_035, "group size {size}");
    assert_eq!(max_size, 1_000_000);
    // The group is also a Token-2022 mint, so it must never pass as an SGT.
    assert!(state.extensions.get(layout::extension_type::TOKEN_GROUP_MEMBER).is_none());
}

#[test]
fn member_numbers_are_distinct_per_mint() {
    let infos: Vec<(Address, u64)> = real_sgts()
        .iter()
        .map(|s| {
            let i = s.verify().unwrap();
            (i.mint, i.member_number)
        })
        .collect();
    assert_ne!(infos[0].0, infos[1].0);
    assert_ne!(infos[0].1, infos[1].1);
}
