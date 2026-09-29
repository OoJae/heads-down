//! The SGT policy: which parsed facts make a (token account, mint) pair a
//! genuine Seeker Genesis Token held by a given wallet.

use pinocchio::{AccountView, Address};

use crate::{
    anchors::{SGT_AUTHORITY, SGT_GROUP, TOKEN_2022_PROGRAM_ID},
    bytes::{array, u64_le},
    error::SgtError,
    layout::{extension_len, extension_type},
    parse::{parse_mint, parse_token_account, Key},
};

/// A verified SGT holding.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct SgtInfo {
    /// The SGT mint. One per Seeker device: key seats and rigs by this, never
    /// by the holder wallet (SGTs can be moved between a user's Seed Vault
    /// wallets by Solana Mobile).
    pub mint: Address,
    /// `TokenGroupMember.member_number`, the device's issue number (1-based).
    pub member_number: u64,
    /// Whether the holder's token account is frozen. Real SGT holdings are
    /// always frozen at rest (Solana Mobile freezes after mint and after
    /// every permissioned move); a thawed one is still authentic.
    pub frozen: bool,
}

/// Account fields the verifier needs, for callers that do not have an
/// [`AccountView`] (off-chain indexers and cranks working from RPC data,
/// tests). On-chain, prefer [`verify_sgt`], which reads these from the
/// runtime so they cannot be misreported.
#[derive(Clone, Copy, Debug)]
pub struct RawAccount<'a> {
    /// The account's address.
    pub address: &'a Address,
    /// The program that owns the account.
    pub owner: &'a Address,
    /// The account data.
    pub data: &'a [u8],
}

/// Verify that `token_account` holds exactly one genuine SGT of `mint` and is
/// owned by `expected_owner`.
///
/// `expected_owner` should be a key the caller has authenticated, normally a
/// transaction signer. The trust anchors are the compile-time
/// [`SGT_GROUP`] and [`SGT_AUTHORITY`] (mainnet unless built with
/// `test-group`).
///
/// Checks, in order (see README for the reasoning behind each):
/// 1. both accounts are owned by Token-2022 and are distinct;
/// 2. token account: well-formed layout and TLV, initialized (`Frozen` is
///    allowed and reported), `mint` field is `mint`, `owner` field is
///    `expected_owner`, not native, `amount == 1`;
/// 3. mint: well-formed layout and TLV, initialized, has extensions,
///    `mint_authority == freeze_authority == SGT_AUTHORITY`, `decimals == 0`,
///    `supply == 1`;
/// 4. mint extensions: `TokenGroupMember { mint: self, group: SGT_GROUP,
///    member_number >= 1 }`, `GroupMemberPointer { SGT_AUTHORITY -> self }`,
///    `PermanentDelegate == SGT_AUTHORITY`,
///    `MetadataPointer { SGT_AUTHORITY -> SGT_GROUP }`,
///    `MintCloseAuthority == SGT_AUTHORITY`.
pub fn verify_sgt(
    token_account: &AccountView,
    mint: &AccountView,
    expected_owner: &Address,
) -> Result<SgtInfo, SgtError> {
    // Ownership is checked before any byte is interpreted.
    if !token_account.owned_by(&TOKEN_2022_PROGRAM_ID) {
        return Err(SgtError::TokenAccountNotToken2022);
    }
    if !mint.owned_by(&TOKEN_2022_PROGRAM_ID) {
        return Err(SgtError::MintNotToken2022);
    }
    let token_data = token_account
        .try_borrow()
        .map_err(|_| SgtError::AccountBorrowFailed)?;
    let mint_data = mint
        .try_borrow()
        .map_err(|_| SgtError::AccountBorrowFailed)?;
    verify_sgt_raw(
        RawAccount {
            address: token_account.address(),
            owner: token_account.owner(),
            data: &token_data,
        },
        RawAccount {
            address: mint.address(),
            owner: mint.owner(),
            data: &mint_data,
        },
        expected_owner,
    )
}

/// [`verify_sgt`] over plain account fields. Same checks, same anchors.
pub fn verify_sgt_raw(
    token_account: RawAccount<'_>,
    mint: RawAccount<'_>,
    expected_owner: &Address,
) -> Result<SgtInfo, SgtError> {
    if token_account.owner != &TOKEN_2022_PROGRAM_ID {
        return Err(SgtError::TokenAccountNotToken2022);
    }
    if mint.owner != &TOKEN_2022_PROGRAM_ID {
        return Err(SgtError::MintNotToken2022);
    }
    if token_account.address == mint.address {
        return Err(SgtError::DuplicateAccount);
    }

    let frozen = check_token_account(token_account.data, mint.address, expected_owner)?;
    let member_number = check_mint(
        mint.data,
        mint.address.as_array(),
        SGT_GROUP.as_array(),
        SGT_AUTHORITY.as_array(),
    )?;

    Ok(SgtInfo {
        mint: mint.address.clone(),
        member_number,
        frozen,
    })
}

/// Token-account half of the policy. Returns the frozen flag.
fn check_token_account(
    data: &[u8],
    mint: &Address,
    expected_owner: &Address,
) -> Result<bool, SgtError> {
    let account = parse_token_account(data)?;
    if account.mint != mint.as_array() {
        return Err(SgtError::TokenAccountMintMismatch);
    }
    if account.owner != expected_owner.as_array() {
        return Err(SgtError::TokenAccountOwnerMismatch);
    }
    if account.is_native.is_some() {
        return Err(SgtError::NativeTokenAccount);
    }
    if account.amount != 1 {
        return Err(SgtError::AmountNotOne);
    }
    Ok(account.frozen)
}

/// Mint half of the policy. Returns the member number.
fn check_mint(data: &[u8], mint: &Key, group: &Key, authority: &Key) -> Result<u64, SgtError> {
    let state = parse_mint(data)?;
    let ext = state.extensions;
    if ext.is_empty() {
        return Err(SgtError::MintMissingExtensions);
    }

    if state.mint_authority != Some(authority) {
        return Err(SgtError::MintAuthorityMismatch);
    }
    if state.freeze_authority != Some(authority) {
        return Err(SgtError::FreezeAuthorityMismatch);
    }
    if state.decimals != 0 {
        return Err(SgtError::DecimalsNotZero);
    }
    if state.supply != 1 {
        return Err(SgtError::SupplyNotOne);
    }

    // The anchor. Token-2022 writes TokenGroupMember only inside
    // InitializeMember, which requires the *group's* update authority to sign
    // and mutates the group account (so the group must be a Token-2022 mint
    // with a TokenGroup extension). Nothing else in this function is
    // unforgeable on its own.
    let member = ext
        .get_sized::<{ extension_len::TOKEN_GROUP_MEMBER }>(extension_type::TOKEN_GROUP_MEMBER)?
        .ok_or(SgtError::MissingGroupMember)?;
    let (member_mint, member_group, member_number) = (
        array::<32>(member, 0).ok_or(SgtError::InvalidExtensionLength)?,
        array::<32>(member, 32).ok_or(SgtError::InvalidExtensionLength)?,
        u64_le(member, 64).ok_or(SgtError::InvalidExtensionLength)?,
    );
    if member_group != group {
        return Err(SgtError::GroupMismatch);
    }
    if member_mint != mint {
        return Err(SgtError::GroupMemberMintMismatch);
    }
    if member_number == 0 {
        return Err(SgtError::InvalidMemberNumber);
    }

    // Consistency with how every real SGT is issued. Token-2022 refuses
    // InitializeMember without a GroupMemberPointer; SGTs point it at
    // themselves.
    let pointer = ext
        .get_sized::<{ extension_len::GROUP_MEMBER_POINTER }>(
            extension_type::GROUP_MEMBER_POINTER,
        )?
        .ok_or(SgtError::MissingGroupMemberPointer)?;
    if array::<32>(pointer, 0) != Some(authority) || array::<32>(pointer, 32) != Some(mint) {
        return Err(SgtError::GroupMemberPointerMismatch);
    }

    let delegate = ext
        .get_sized::<{ extension_len::PERMANENT_DELEGATE }>(extension_type::PERMANENT_DELEGATE)?
        .ok_or(SgtError::MissingPermanentDelegate)?;
    if delegate != authority {
        return Err(SgtError::PermanentDelegateMismatch);
    }

    let metadata = ext
        .get_sized::<{ extension_len::METADATA_POINTER }>(extension_type::METADATA_POINTER)?
        .ok_or(SgtError::MissingMetadataPointer)?;
    if array::<32>(metadata, 0) != Some(authority) || array::<32>(metadata, 32) != Some(group) {
        return Err(SgtError::MetadataPointerMismatch);
    }

    let close = ext
        .get_sized::<{ extension_len::MINT_CLOSE_AUTHORITY }>(
            extension_type::MINT_CLOSE_AUTHORITY,
        )?
        .ok_or(SgtError::MissingMintCloseAuthority)?;
    if close != authority {
        return Err(SgtError::MintCloseAuthorityMismatch);
    }

    Ok(member_number)
}
