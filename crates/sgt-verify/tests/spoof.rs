//! Spoof suite: every test starts from the real member-20 SGT bytes, changes
//! one thing, and asserts the exact `SgtError` that stops it.
#![cfg(not(feature = "test-group"))]

mod common;

use common::*;
use pinocchio::Address;
use sgt_verify::{
    layout::{account, account_state, extension_type as ext, mint},
    SgtError,
};

const ATTACKER: Address = Address::from_str_const("Attacker11111111111111111111111111111111111");
const ATTACKER_PROGRAM: Address =
    Address::from_str_const("AttackerProgram1111111111111111111111111111");

struct Case {
    sgt: RealSgt,
    mint: Fixture,
    token_account: Fixture,
}

impl Case {
    fn new() -> Self {
        let sgt = member_20();
        Self {
            mint: sgt.mint.clone(),
            token_account: sgt.token_account.clone(),
            sgt,
        }
    }

    fn mint(mut self, f: impl FnOnce(&mut Vec<u8>)) -> Self {
        f(&mut self.mint.data);
        self
    }

    fn token_account(mut self, f: impl FnOnce(&mut Vec<u8>)) -> Self {
        f(&mut self.token_account.data);
        self
    }

    fn result(&self) -> Result<sgt_verify::SgtInfo, SgtError> {
        self.sgt.verify_with(&self.token_account, &self.mint)
    }

    #[track_caller]
    fn rejects(&self, expected: SgtError) {
        assert_eq!(self.result(), Err(expected));
    }
}

fn set(data: &mut [u8], offset: usize, bytes: &[u8]) {
    data[offset..offset + bytes.len()].copy_from_slice(bytes);
}

fn set_ext(data: &mut [u8], ty: u16, offset_in_value: usize, bytes: &[u8]) {
    let off = value_offset(data, ty) + offset_in_value;
    set(data, off, bytes);
}

fn drop_ext(data: &mut Vec<u8>, ty: u16) {
    let entries: Vec<_> = split_tlv(data).into_iter().filter(|(t, _)| *t != ty).collect();
    *data = with_tlv(data, &entries);
}

#[test]
fn baseline_is_valid() {
    assert!(Case::new().result().is_ok());
}

// ---- the requested spoof list ------------------------------------------------

#[test]
fn wrong_group() {
    Case::new()
        .mint(|d| set_ext(d, ext::TOKEN_GROUP_MEMBER, 32, ATTACKER.as_ref()))
        .rejects(SgtError::GroupMismatch);
}

#[test]
fn wrong_mint_authority() {
    Case::new()
        .mint(|d| set(d, mint::MINT_AUTHORITY, ATTACKER.as_ref()))
        .rejects(SgtError::MintAuthorityMismatch);
}

#[test]
fn mint_authority_none() {
    Case::new()
        .mint(|d| {
            set(d, mint::MINT_AUTHORITY_TAG, &[0, 0, 0, 0]);
            set(d, mint::MINT_AUTHORITY, &[0; 32]);
        })
        .rejects(SgtError::MintAuthorityMismatch);
    // `None` tag with the right key still left in the bytes.
    Case::new()
        .mint(|d| set(d, mint::MINT_AUTHORITY_TAG, &[0, 0, 0, 0]))
        .rejects(SgtError::MintAuthorityMismatch);
}

#[test]
fn amount_zero() {
    Case::new()
        .token_account(|d| set(d, account::AMOUNT, &0u64.to_le_bytes()))
        .rejects(SgtError::AmountNotOne);
}

#[test]
fn amount_two() {
    Case::new()
        .token_account(|d| set(d, account::AMOUNT, &2u64.to_le_bytes()))
        .rejects(SgtError::AmountNotOne);
    Case::new()
        .token_account(|d| set(d, account::AMOUNT, &u64::MAX.to_le_bytes()))
        .rejects(SgtError::AmountNotOne);
}

/// The brief listed "frozen account" as a spoof. On mainnet every SGT holding
/// is frozen (see real_fixtures.rs), so frozen must pass; what must fail is an
/// account that is not initialized or has an undefined state byte.
#[test]
fn account_state() {
    let frozen = Case::new();
    assert_eq!(frozen.token_account.data[account::STATE], account_state::FROZEN);
    assert!(frozen.result().unwrap().frozen);

    let thawed = Case::new().token_account(|d| d[account::STATE] = account_state::INITIALIZED);
    assert!(!thawed.result().unwrap().frozen);

    Case::new()
        .token_account(|d| d[account::STATE] = account_state::UNINITIALIZED)
        .rejects(SgtError::TokenAccountNotInitialized);
    for bad in [3u8, 4, 0x80, 0xff] {
        Case::new()
            .token_account(|d| d[account::STATE] = bad)
            .rejects(SgtError::TokenAccountInvalidState);
    }
}

#[test]
fn token_account_for_a_different_mint() {
    Case::new()
        .token_account(|d| set(d, account::MINT, ATTACKER.as_ref()))
        .rejects(SgtError::TokenAccountMintMismatch);

    // Cross-pairing two genuine SGTs: holder of #121035's account, #20's mint.
    let sgts = real_sgts();
    let (a, b) = (&sgts[0], &sgts[1]);
    assert_eq!(
        sgt_verify::verify_sgt_raw(b.token_account.raw(), a.mint.raw(), &b.holder),
        Err(SgtError::TokenAccountMintMismatch)
    );
}

#[test]
fn legacy_spl_token_owned_token_account() {
    let mut case = Case::new();
    case.token_account.owner = SPL_TOKEN;
    case.rejects(SgtError::TokenAccountNotToken2022);
}

#[test]
fn mint_not_owned_by_token_2022() {
    for owner in [SPL_TOKEN, SYSTEM_PROGRAM, ATTACKER_PROGRAM] {
        let mut case = Case::new();
        case.mint.owner = owner;
        case.rejects(SgtError::MintNotToken2022);
    }
}

#[test]
fn tlv_length_overflow() {
    // Last entry claims one byte more than the account has.
    Case::new()
        .mint(|d| {
            let h = header_offset(d, ext::TOKEN_GROUP_MEMBER);
            set(d, h + 2, &73u16.to_le_bytes());
        })
        .rejects(SgtError::MalformedTlv);
    // First entry claims u16::MAX.
    Case::new()
        .mint(|d| set(d, 168, &u16::MAX.to_le_bytes()))
        .rejects(SgtError::MalformedTlv);
    // A middle entry's length is shortened, so the walk lands mid-value and
    // reads garbage headers: must fail, whichever way.
    assert!(Case::new()
        .mint(|d| {
            let h = header_offset(d, ext::PERMANENT_DELEGATE);
            set(d, h + 2, &20u16.to_le_bytes());
        })
        .result()
        .is_err());
}

#[test]
fn truncated_tlv_at_every_offset() {
    let full = member_20().mint.data;
    let member_header = header_offset(&full, ext::TOKEN_GROUP_MEMBER);
    for len in 0..full.len() {
        let case = Case::new().mint(|d| d.truncate(len));
        let err = case.result().expect_err(&format!("truncated to {len} verified"));
        let expected = match len {
            // 82 is a legal *base* mint, just not an SGT.
            82 | 166 => Some(SgtError::MintMissingExtensions),
            0..=165 | 355 => Some(SgtError::MintInvalidLength),
            // Whole member entry gone, or only 1 trailing byte (slack).
            l if l == member_header || l == member_header + 1 => Some(SgtError::MissingGroupMember),
            l if l > member_header => Some(SgtError::MalformedTlv),
            _ => None, // earlier cuts: malformed or a missing extension
        };
        if let Some(expected) = expected {
            assert_eq!(err, expected, "len {len}");
        }
    }
    let full_ta = member_20().token_account.data;
    for len in 0..full_ta.len() {
        let result = Case::new().token_account(|d| d.truncate(len)).result();
        match len {
            0..=164 => assert_eq!(result, Err(SgtError::TokenAccountInvalidLength), "len {len}"),
            // 165: a legal account without extensions; 166: account-type byte
            // only; 167: one byte of realloc slack. Token-2022 accepts all
            // three layouts, so they are not spoofs (see next test).
            165..=167 => assert!(result.is_ok(), "len {len}"),
            // 168/169: the ImmutableOwner type without its full length.
            _ => assert_eq!(result, Err(SgtError::MalformedTlv), "len {len}"),
        }
    }
}

#[test]
fn unextended_token_account_is_still_a_valid_holding() {
    // Token-2022 accounts need no extensions; only the account-type byte
    // must say "Account" when the data is longer than 165 bytes.
    for len in [165, 166, 167] {
        let info = Case::new().token_account(|d| d.truncate(len)).result().unwrap();
        assert_eq!(info.member_number, 20, "len {len}");
    }
}

#[test]
fn missing_group_member_extension() {
    Case::new()
        .mint(|d| drop_ext(d, ext::TOKEN_GROUP_MEMBER))
        .rejects(SgtError::MissingGroupMember);
}

#[test]
fn group_member_present_but_pointer_absent() {
    Case::new()
        .mint(|d| drop_ext(d, ext::GROUP_MEMBER_POINTER))
        .rejects(SgtError::MissingGroupMemberPointer);
}

#[test]
fn decimals_nonzero() {
    for decimals in [1u8, 9, 255] {
        Case::new()
            .mint(|d| d[mint::DECIMALS] = decimals)
            .rejects(SgtError::DecimalsNotZero);
    }
}

#[test]
fn wrong_expected_owner() {
    let case = Case::new();
    assert_eq!(
        sgt_verify::verify_sgt_raw(case.token_account.raw(), case.mint.raw(), &ATTACKER),
        Err(SgtError::TokenAccountOwnerMismatch)
    );
    // The SGT authority itself is not the holder either.
    assert_eq!(
        sgt_verify::verify_sgt_raw(case.token_account.raw(), case.mint.raw(), &SGT_AUTHORITY),
        Err(SgtError::TokenAccountOwnerMismatch)
    );
}

// ---- the rest of the mint --------------------------------------------------

#[test]
fn supply_not_one() {
    for supply in [0u64, 2, u64::MAX] {
        Case::new()
            .mint(|d| set(d, mint::SUPPLY, &supply.to_le_bytes()))
            .rejects(SgtError::SupplyNotOne);
    }
}

#[test]
fn freeze_authority() {
    Case::new()
        .mint(|d| set(d, mint::FREEZE_AUTHORITY_TAG, &[0, 0, 0, 0]))
        .rejects(SgtError::FreezeAuthorityMismatch);
    Case::new()
        .mint(|d| set(d, mint::FREEZE_AUTHORITY, ATTACKER.as_ref()))
        .rejects(SgtError::FreezeAuthorityMismatch);
}

#[test]
fn mint_not_initialized() {
    for flag in [0u8, 2, 0xff] {
        Case::new()
            .mint(|d| d[mint::IS_INITIALIZED] = flag)
            .rejects(SgtError::MintNotInitialized);
    }
}

#[test]
fn undefined_option_tags() {
    for tag in [[2u8, 0, 0, 0], [1, 0, 0, 1], [0xff; 4]] {
        Case::new()
            .mint(|d| set(d, mint::MINT_AUTHORITY_TAG, &tag))
            .rejects(SgtError::MintInvalidOption);
        Case::new()
            .token_account(|d| set(d, account::DELEGATE_TAG, &tag))
            .rejects(SgtError::TokenAccountInvalidOption);
    }
}

#[test]
fn group_member_describes_another_mint() {
    Case::new()
        .mint(|d| set_ext(d, ext::TOKEN_GROUP_MEMBER, 0, ATTACKER.as_ref()))
        .rejects(SgtError::GroupMemberMintMismatch);
}

#[test]
fn member_number_zero() {
    Case::new()
        .mint(|d| set_ext(d, ext::TOKEN_GROUP_MEMBER, 64, &0u64.to_le_bytes()))
        .rejects(SgtError::InvalidMemberNumber);
}

#[test]
fn member_number_is_read_not_assumed() {
    let info = Case::new()
        .mint(|d| set_ext(d, ext::TOKEN_GROUP_MEMBER, 64, &999_999u64.to_le_bytes()))
        .result()
        .unwrap();
    assert_eq!(info.member_number, 999_999);
}

#[test]
fn group_member_pointer_elsewhere() {
    Case::new()
        .mint(|d| set_ext(d, ext::GROUP_MEMBER_POINTER, 32, ATTACKER.as_ref()))
        .rejects(SgtError::GroupMemberPointerMismatch);
    Case::new()
        .mint(|d| set_ext(d, ext::GROUP_MEMBER_POINTER, 32, SGT_GROUP.as_ref()))
        .rejects(SgtError::GroupMemberPointerMismatch);
    Case::new()
        .mint(|d| set_ext(d, ext::GROUP_MEMBER_POINTER, 0, ATTACKER.as_ref()))
        .rejects(SgtError::GroupMemberPointerMismatch);
}

#[test]
fn permanent_delegate() {
    Case::new()
        .mint(|d| set_ext(d, ext::PERMANENT_DELEGATE, 0, ATTACKER.as_ref()))
        .rejects(SgtError::PermanentDelegateMismatch);
    Case::new()
        .mint(|d| set_ext(d, ext::PERMANENT_DELEGATE, 0, &[0; 32]))
        .rejects(SgtError::PermanentDelegateMismatch);
    Case::new()
        .mint(|d| drop_ext(d, ext::PERMANENT_DELEGATE))
        .rejects(SgtError::MissingPermanentDelegate);
}

#[test]
fn metadata_pointer() {
    Case::new()
        .mint(|d| set_ext(d, ext::METADATA_POINTER, 32, ATTACKER.as_ref()))
        .rejects(SgtError::MetadataPointerMismatch);
    Case::new()
        .mint(|d| set_ext(d, ext::METADATA_POINTER, 0, ATTACKER.as_ref()))
        .rejects(SgtError::MetadataPointerMismatch);
    Case::new()
        .mint(|d| drop_ext(d, ext::METADATA_POINTER))
        .rejects(SgtError::MissingMetadataPointer);
}

#[test]
fn mint_close_authority() {
    Case::new()
        .mint(|d| set_ext(d, ext::MINT_CLOSE_AUTHORITY, 0, ATTACKER.as_ref()))
        .rejects(SgtError::MintCloseAuthorityMismatch);
    Case::new()
        .mint(|d| drop_ext(d, ext::MINT_CLOSE_AUTHORITY))
        .rejects(SgtError::MissingMintCloseAuthority);
}

#[test]
fn extension_with_wrong_length() {
    for (ty, short) in [
        (ext::TOKEN_GROUP_MEMBER, 71usize),
        (ext::PERMANENT_DELEGATE, 31),
        (ext::GROUP_MEMBER_POINTER, 65),
    ] {
        Case::new()
            .mint(|d| {
                let entries: Vec<_> = split_tlv(d)
                    .into_iter()
                    .map(|(t, mut v)| {
                        if t == ty {
                            v.resize(short, 0);
                        }
                        (t, v)
                    })
                    .collect();
                *d = with_tlv(d, &entries);
            })
            .rejects(SgtError::InvalidExtensionLength);
    }
}

#[test]
fn duplicate_extension() {
    // Retype MintCloseAuthority (32 bytes) as a second PermanentDelegate.
    Case::new()
        .mint(|d| {
            let h = header_offset(d, ext::MINT_CLOSE_AUTHORITY);
            set(d, h, &ext::PERMANENT_DELEGATE.to_le_bytes());
        })
        .rejects(SgtError::DuplicateExtension);
    // A second TokenGroupMember naming the attacker's group, appended.
    Case::new()
        .mint(|d| {
            let mut entries = split_tlv(d);
            let mut fake = entries.last().unwrap().1.clone();
            fake[32..64].copy_from_slice(ATTACKER.as_ref());
            entries.push((ext::TOKEN_GROUP_MEMBER, fake));
            *d = with_tlv(d, &entries);
        })
        .rejects(SgtError::DuplicateExtension);
}

#[test]
fn uninitialized_type_terminates_like_token_2022() {
    // Zeroing the GroupMemberPointer's type hides it and everything after,
    // exactly as Token-2022's own lookup would.
    Case::new()
        .mint(|d| {
            let h = header_offset(d, ext::GROUP_MEMBER_POINTER);
            set(d, h, &0u16.to_le_bytes());
        })
        .rejects(SgtError::MissingGroupMember);
    // Bytes after a terminator are invisible to both Token-2022 and us, so a
    // forged member there changes nothing: the real one is still reported.
    let info = Case::new()
        .mint(|d| {
            let mut fake = d[d.len() - 72..].to_vec();
            fake[32..64].copy_from_slice(ATTACKER.as_ref());
            d.extend_from_slice(&[0, 0, 0, 0]);
            d.extend_from_slice(&ext::TOKEN_GROUP_MEMBER.to_le_bytes());
            d.extend_from_slice(&72u16.to_le_bytes());
            d.extend_from_slice(&fake);
        })
        .result()
        .unwrap();
    assert_eq!(info.member_number, 20);
}

#[test]
fn realloc_slack_is_tolerated_but_garbage_is_not() {
    assert!(Case::new().mint(|d| d.push(0xaa)).result().is_ok());
    assert!(Case::new().mint(|d| d.extend_from_slice(&[0; 64])).result().is_ok());
    Case::new()
        .mint(|d| d.extend_from_slice(&[5, 0, 9]))
        .rejects(SgtError::MalformedTlv);
}

#[test]
fn unrelated_extensions_do_not_matter() {
    // Only the anchored facts decide; an extra benign entry is not a spoof.
    assert!(Case::new()
        .mint(|d| {
            let mut entries = split_tlv(d);
            entries.push((200, vec![]));
            *d = with_tlv(d, &entries);
        })
        .result()
        .is_ok());
}

#[test]
fn too_many_extensions() {
    Case::new()
        .mint(|d| {
            let mut entries = split_tlv(d);
            entries.extend((100u16..140).map(|t| (t, vec![])));
            *d = with_tlv(d, &entries);
        })
        .rejects(SgtError::TooManyExtensions);
}

// ---- layout / type confusion ---------------------------------------------

#[test]
fn mint_layout() {
    Case::new()
        .mint(|d| d[165] = 2)
        .rejects(SgtError::MintAccountTypeMismatch);
    Case::new()
        .mint(|d| d[165] = 0)
        .rejects(SgtError::MintAccountTypeMismatch);
    Case::new()
        .mint(|d| d[100] = 1)
        .rejects(SgtError::MintPaddingNotZero);
    Case::new()
        .mint(|d| d.resize(355, 0))
        .rejects(SgtError::MintInvalidLength);
    Case::new()
        .mint(|d| d.truncate(82))
        .rejects(SgtError::MintMissingExtensions);
}

#[test]
fn token_account_layout() {
    Case::new()
        .token_account(|d| d[165] = 1)
        .rejects(SgtError::TokenAccountTypeMismatch);
    Case::new()
        .token_account(|d| d.resize(355, 0))
        .rejects(SgtError::TokenAccountInvalidLength);
    Case::new()
        .token_account(|d| set(d, 168, &1u16.to_le_bytes()))
        .rejects(SgtError::MalformedTlv);
    Case::new()
        .token_account(|d| d.extend_from_slice(&[7, 0, 0, 0]))
        .rejects(SgtError::DuplicateExtension);
}

#[test]
fn native_token_account() {
    Case::new()
        .token_account(|d| {
            set(d, account::IS_NATIVE_TAG, &[1, 0, 0, 0]);
            set(d, account::IS_NATIVE, &2_039_280u64.to_le_bytes());
        })
        .rejects(SgtError::NativeTokenAccount);
}

#[test]
fn same_account_for_both_roles() {
    let case = Case::new();
    let mut ta = case.token_account.clone();
    ta.address = case.mint.address;
    assert_eq!(
        case.sgt.verify_with(&ta, &case.mint),
        Err(SgtError::DuplicateAccount)
    );
}

#[test]
fn type_cosplay_mint_as_token_account() {
    let case = Case::new();
    let mut fake_ta = case.mint.clone();
    fake_ta.address = ATTACKER;
    assert_eq!(
        case.sgt.verify_with(&fake_ta, &case.mint),
        Err(SgtError::TokenAccountTypeMismatch)
    );
}

#[test]
fn type_cosplay_token_account_as_mint() {
    // A token account passed as the mint. Bytes 82..165 of an extended mint
    // must be zero, and an initialized account's state byte (offset 108) sits
    // inside that range, so no live token account can parse as a mint. With
    // the state byte zeroed, the account-type byte (2) gives it away.
    let case = Case::new();
    let mut fake_mint = case.token_account.clone();
    fake_mint.address = ATTACKER;
    let mut ta = case.token_account.clone();
    set(&mut ta.data, account::MINT, ATTACKER.as_ref());
    assert_eq!(
        case.sgt.verify_with(&ta, &fake_mint),
        Err(SgtError::MintPaddingNotZero)
    );
    fake_mint.data[82..165].fill(0);
    assert_eq!(
        case.sgt.verify_with(&ta, &fake_mint),
        Err(SgtError::MintAccountTypeMismatch)
    );
}

#[test]
fn group_mint_passed_as_sgt_mint() {
    // GT22s89 is itself a Token-2022 mint with the right authorities.
    let case = Case::new();
    let group = group_fixture();
    let mut ta = case.token_account.clone();
    set(&mut ta.data, account::MINT, group.address.as_ref());
    assert_eq!(
        case.sgt.verify_with(&ta, &group),
        Err(SgtError::SupplyNotOne)
    );
    let mut group_supply_1 = group.clone();
    set(&mut group_supply_1.data, mint::SUPPLY, &1u64.to_le_bytes());
    assert_eq!(
        case.sgt.verify_with(&ta, &group_supply_1),
        Err(SgtError::MissingGroupMember)
    );
}

/// ORE's removed `claim_seeker` accepted a mint if it was owned by
/// Token-2022, had mint authority GT2zuH, a MetadataPointer
/// {GT2zuH -> GT22s89}, and the caller's ATA held 1. Every one of those
/// fields can be written by anyone: InitializeMetadataPointer takes the
/// authority as data, and SetAuthority can hand the mint authority to GT2zuH
/// after the attacker mints their 1 token. Only group membership needs
/// GT2zuH's signature. (The LiteSVM suite performs this forgery with real
/// Token-2022 instructions.)
#[test]
fn ore_claim_seeker_style_forgery_is_rejected() {
    let forged = Case::new().mint(|d| drop_ext(d, ext::TOKEN_GROUP_MEMBER));
    assert!(ore_claim_seeker_accepts(&forged.mint.data));
    forged.rejects(SgtError::MissingGroupMember);

    let forged = Case::new().mint(|d| set_ext(d, ext::TOKEN_GROUP_MEMBER, 32, ATTACKER.as_ref()));
    assert!(ore_claim_seeker_accepts(&forged.mint.data));
    forged.rejects(SgtError::GroupMismatch);
}

/// The mint-side checks of ORE's claim_seeker (commit 037aa5e480).
fn ore_claim_seeker_accepts(mint_data: &[u8]) -> bool {
    let mint_auth_ok = mint_data[0..4] == [1, 0, 0, 0] && mint_data[4..36] == *SGT_AUTHORITY.as_ref();
    let mp = value_offset(mint_data, ext::METADATA_POINTER);
    mint_auth_ok
        && mint_data[mp..mp + 32] == *SGT_AUTHORITY.as_ref()
        && mint_data[mp + 32..mp + 64] == *SGT_GROUP.as_ref()
}

// ---- exhaustive and randomized mutation -------------------------------------

/// Flip every bit of the real mint, one at a time. The only bytes whose
/// change may still verify are the 8 member-number bytes: every other byte of
/// the mint is pinned by some check.
#[test]
fn every_mint_bit_is_pinned_except_the_member_number() {
    let base = Case::new();
    let member_number = value_offset(&base.mint.data, ext::TOKEN_GROUP_MEMBER) + 64;
    for i in 0..base.mint.data.len() {
        for bit in 0..8 {
            let case = Case::new().mint(|d| d[i] ^= 1 << bit);
            if case.result().is_ok() {
                assert!(
                    (member_number..member_number + 8).contains(&i),
                    "flipping mint byte {i} bit {bit} still verified"
                );
            }
        }
    }
}

/// Flip every bit of the real token account. Mint, owner, amount, state,
/// account type and TLV lengths are pinned; the only bytes that may change
/// are fields irrelevant to holding (delegate, close authority, native
/// reserve value, delegated amount) and the ImmutableOwner type field.
#[test]
fn every_token_account_bit_that_matters_is_pinned() {
    let base = Case::new();
    let allowed = |i: usize| {
        matches!(i,
            72..=107      // delegate COption (a delegate cannot move a frozen SGT)
            | 113..=128   // is_native value (tag is pinned) and delegated_amount
            | 129..=164   // close_authority COption (closing needs amount 0)
            | 166..=167   // the lone account extension's type
        )
    };
    for i in 0..base.token_account.data.len() {
        for bit in 0..8 {
            let case = Case::new().token_account(|d| d[i] ^= 1 << bit);
            if case.result().is_ok() {
                assert!(allowed(i), "flipping token-account byte {i} bit {bit} still verified");
            }
        }
    }
}

/// Random multi-byte corruption, truncation and extension of both accounts.
/// Nothing may panic, and anything that verifies must still name the real
/// mint, the real holder, amount 1 and the real group.
#[test]
fn randomized_corruption_never_panics_or_verifies_garbage() {
    let base = Case::new();
    let mut rng = XorShift(0x5347_5645_5249_4659);
    for _ in 0..40_000 {
        let mut case = Case::new();
        let target = rng.below(2);
        let data = if target == 0 {
            &mut case.mint.data
        } else {
            &mut case.token_account.data
        };
        match rng.below(4) {
            0 => {
                for _ in 0..=rng.below(4) {
                    let i = rng.below(data.len());
                    data[i] = rng.next_u64() as u8;
                }
            }
            1 => {
                let len = rng.below(data.len() + 1);
                data.truncate(len);
            }
            2 => {
                for _ in 0..=rng.below(80) {
                    data.push(rng.next_u64() as u8);
                }
            }
            _ => {
                // Overwrite a TLV length with an arbitrary u16.
                let i = TLV_START + 2 + rng.below(data.len().saturating_sub(TLV_START + 2).max(1));
                if i + 2 <= data.len() {
                    let v = rng.next_u64() as u16;
                    data[i..i + 2].copy_from_slice(&v.to_le_bytes());
                }
            }
        }
        if let Ok(info) = case.result() {
            assert_eq!(info.mint, base.mint.address);
            let ta = &case.token_account.data;
            assert_eq!(ta[0..72], base.token_account.data[0..72]);
            let m = &case.mint.data;
            let off = value_offset(m, ext::TOKEN_GROUP_MEMBER);
            assert_eq!(&m[off + 32..off + 64], SGT_GROUP.as_ref());
        }
    }
}
