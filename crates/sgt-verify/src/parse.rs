//! Policy-free parsing of Token-2022 mint and token-account bytes.
//!
//! These functions answer "what does this account say?" and reject only data
//! that Token-2022 itself could not have written (bad lengths, non-zero
//! padding, wrong account-type byte, undefined option tags or states,
//! malformed TLV). Whether the account is an *SGT* is decided in
//! [`crate::verify_sgt`].

use crate::{
    bytes::{array, byte, u64_le},
    error::SgtError,
    layout::{
        account, account_state, account_type, coption, mint, ACCOUNT_BASE_LEN,
        ACCOUNT_TYPE_OFFSET, MINT_BASE_LEN, MULTISIG_LEN, TLV_START,
    },
    tlv::Extensions,
};

/// A 32-byte public key as stored in account data.
pub type Key = [u8; 32];

/// A parsed Token-2022 mint.
#[derive(Clone, Copy, Debug)]
pub struct MintState<'a> {
    /// `mint_authority`.
    pub mint_authority: Option<&'a Key>,
    /// `supply`.
    pub supply: u64,
    /// `decimals`.
    pub decimals: u8,
    /// `freeze_authority`.
    pub freeze_authority: Option<&'a Key>,
    /// The validated extension region (empty for an 82-byte mint).
    pub extensions: Extensions<'a>,
}

/// A parsed Token-2022 token account.
#[derive(Clone, Copy, Debug)]
pub struct TokenAccountState<'a> {
    /// `mint`.
    pub mint: &'a Key,
    /// `owner` (the wallet that controls the tokens).
    pub owner: &'a Key,
    /// `amount`.
    pub amount: u64,
    /// `delegate`.
    pub delegate: Option<&'a Key>,
    /// `true` if `state == Frozen`. `Uninitialized` is rejected while parsing.
    pub frozen: bool,
    /// `is_native` (`Some(rent_reserve)` for wrapped SOL).
    pub is_native: Option<u64>,
    /// `delegated_amount`.
    pub delegated_amount: u64,
    /// `close_authority`.
    pub close_authority: Option<&'a Key>,
    /// The validated extension region (empty for a 165-byte account).
    pub extensions: Extensions<'a>,
}

/// Decode a `PodCOption` tag. `err` for anything but exactly None or Some.
fn coption_tag(data: &[u8], offset: usize, err: SgtError) -> Result<bool, SgtError> {
    match array::<{ coption::TAG_LEN }>(data, offset) {
        Some(&coption::NONE) => Ok(false),
        Some(&coption::SOME) => Ok(true),
        _ => Err(err),
    }
}

fn coption_key(
    data: &[u8],
    tag_offset: usize,
    key_offset: usize,
    err: SgtError,
) -> Result<Option<&Key>, SgtError> {
    let key = array::<32>(data, key_offset).ok_or(err)?;
    Ok(coption_tag(data, tag_offset, err)?.then_some(key))
}

/// Split an extended account into its TLV region after checking the
/// account-type byte. `base_len` is 82 for mints and 165 for accounts; the
/// bytes between `base_len` and 165 must be zero (they are only non-empty for
/// mints).
fn extended_tlv(
    data: &[u8],
    base_len: usize,
    expected_type: u8,
    padding_err: SgtError,
    type_err: SgtError,
) -> Result<&[u8], SgtError> {
    let padding = data.get(base_len..ACCOUNT_TYPE_OFFSET).ok_or(type_err)?;
    if padding.iter().any(|b| *b != 0) {
        return Err(padding_err);
    }
    if byte(data, ACCOUNT_TYPE_OFFSET) != Some(expected_type) {
        return Err(type_err);
    }
    data.get(TLV_START..).ok_or(type_err)
}

/// Parse a Token-2022 mint.
///
/// Accepts an 82-byte base mint or an extended mint (>= 166 bytes, not 355).
/// Rejects anything Token-2022 would refuse to unpack, and additionally
/// requires `is_initialized == 1` exactly.
pub fn parse_mint(data: &[u8]) -> Result<MintState<'_>, SgtError> {
    let len = data.len();
    let tlv: &[u8] = if len == MINT_BASE_LEN {
        &[]
    } else if len > ACCOUNT_BASE_LEN && len != MULTISIG_LEN {
        extended_tlv(
            data,
            MINT_BASE_LEN,
            account_type::MINT,
            SgtError::MintPaddingNotZero,
            SgtError::MintAccountTypeMismatch,
        )?
    } else {
        return Err(SgtError::MintInvalidLength);
    };

    if byte(data, mint::IS_INITIALIZED) != Some(1) {
        return Err(SgtError::MintNotInitialized);
    }
    let mint_authority = coption_key(
        data,
        mint::MINT_AUTHORITY_TAG,
        mint::MINT_AUTHORITY,
        SgtError::MintInvalidOption,
    )?;
    let freeze_authority = coption_key(
        data,
        mint::FREEZE_AUTHORITY_TAG,
        mint::FREEZE_AUTHORITY,
        SgtError::MintInvalidOption,
    )?;
    let supply = u64_le(data, mint::SUPPLY).ok_or(SgtError::MintInvalidLength)?;
    let decimals = byte(data, mint::DECIMALS).ok_or(SgtError::MintInvalidLength)?;
    let extensions = Extensions::parse(tlv)?;

    Ok(MintState {
        mint_authority,
        supply,
        decimals,
        freeze_authority,
        extensions,
    })
}

/// Parse a Token-2022 token account.
///
/// Accepts a 165-byte base account or an extended account (>= 166 bytes, not
/// 355). Rejects `Uninitialized` and undefined state bytes. A `Frozen` account
/// is valid and reported through [`TokenAccountState::frozen`].
pub fn parse_token_account(data: &[u8]) -> Result<TokenAccountState<'_>, SgtError> {
    let len = data.len();
    let tlv: &[u8] = if len == ACCOUNT_BASE_LEN {
        &[]
    } else if len > ACCOUNT_BASE_LEN && len != MULTISIG_LEN {
        extended_tlv(
            data,
            ACCOUNT_BASE_LEN,
            account_type::ACCOUNT,
            // There is no padding for accounts; the range is empty.
            SgtError::TokenAccountTypeMismatch,
            SgtError::TokenAccountTypeMismatch,
        )?
    } else {
        return Err(SgtError::TokenAccountInvalidLength);
    };

    let frozen = match byte(data, account::STATE) {
        Some(account_state::INITIALIZED) => false,
        Some(account_state::FROZEN) => true,
        Some(account_state::UNINITIALIZED) => return Err(SgtError::TokenAccountNotInitialized),
        _ => return Err(SgtError::TokenAccountInvalidState),
    };

    let short = SgtError::TokenAccountInvalidLength;
    let bad_option = SgtError::TokenAccountInvalidOption;
    let mint = array::<32>(data, account::MINT).ok_or(short)?;
    let owner = array::<32>(data, account::OWNER).ok_or(short)?;
    let amount = u64_le(data, account::AMOUNT).ok_or(short)?;
    let delegate = coption_key(data, account::DELEGATE_TAG, account::DELEGATE, bad_option)?;
    let is_native = if coption_tag(data, account::IS_NATIVE_TAG, bad_option)? {
        Some(u64_le(data, account::IS_NATIVE).ok_or(short)?)
    } else {
        None
    };
    let delegated_amount = u64_le(data, account::DELEGATED_AMOUNT).ok_or(short)?;
    let close_authority = coption_key(
        data,
        account::CLOSE_AUTHORITY_TAG,
        account::CLOSE_AUTHORITY,
        bad_option,
    )?;
    let extensions = Extensions::parse(tlv)?;

    Ok(TokenAccountState {
        mint,
        owner,
        amount,
        delegate,
        frozen,
        is_native,
        delegated_amount,
        close_authority,
        extensions,
    })
}
