//! Exact Token-2022 byte layouts, transcribed from `spl-token-2022` 9.0.0
//! (`src/pod.rs`, `src/extension/mod.rs`) and `spl-token-group-interface`
//! 0.6.0, and cross-checked byte-for-byte against real mainnet SGT accounts in
//! `fixtures/`.
//!
//! ```text
//! Mint, extended:     [0..82 PodMint][82..165 zero padding][165 AccountType=1][166.. TLV]
//! Account, extended:  [0..165 PodAccount]                  [165 AccountType=2][166.. TLV]
//! TLV entry:          [type: u16 LE][length: u16 LE][value: length bytes]
//! ```
//!
//! Token-2022 writes TLV data only after byte 165 for *both* kinds so a mint
//! can never be confused with an account. The one ambiguous length, 355
//! (`Multisig::LEN`), is never extended: Token-2022 pads such accounts by two
//! bytes instead.

/// `PodMint::SIZE_OF`: the base (pre-extension) mint.
pub const MINT_BASE_LEN: usize = 82;
/// `PodAccount::SIZE_OF` (`Account::LEN`): the base token account, and the
/// offset of the account-type byte for both extended mints and accounts.
pub const ACCOUNT_BASE_LEN: usize = 165;
/// `Multisig::LEN`: data of exactly this length is a multisig, never an
/// extended mint or account.
pub const MULTISIG_LEN: usize = 355;
/// Offset of the `AccountType` byte in an extended mint or account.
pub const ACCOUNT_TYPE_OFFSET: usize = ACCOUNT_BASE_LEN;
/// First TLV byte of an extended mint or account.
pub const TLV_START: usize = ACCOUNT_TYPE_OFFSET + 1;
/// Size of a TLV header: `u16` type plus `u16` length.
pub const TLV_HEADER_LEN: usize = 4;

/// `AccountType` discriminants (`spl_token_2022::extension::AccountType`).
pub mod account_type {
    /// Marker for zeroed data.
    pub const UNINITIALIZED: u8 = 0;
    /// Extended mint.
    pub const MINT: u8 = 1;
    /// Extended token account.
    pub const ACCOUNT: u8 = 2;
}

/// `AccountState` discriminants (`spl_token_2022::state::AccountState`).
pub mod account_state {
    /// Not yet initialized.
    pub const UNINITIALIZED: u8 = 0;
    /// Initialized and not frozen.
    pub const INITIALIZED: u8 = 1;
    /// Frozen by the freeze authority. Every SGT holding is frozen at rest.
    pub const FROZEN: u8 = 2;
}

/// `COption` tags as stored in `PodCOption` (a little-endian `u32`).
pub mod coption {
    /// `None`.
    pub const NONE: [u8; 4] = [0, 0, 0, 0];
    /// `Some`.
    pub const SOME: [u8; 4] = [1, 0, 0, 0];
    /// Width of the tag.
    pub const TAG_LEN: usize = 4;
}

/// Field offsets inside `PodMint` (82 bytes).
pub mod mint {
    /// `mint_authority: PodCOption<Pubkey>` tag.
    pub const MINT_AUTHORITY_TAG: usize = 0;
    /// `mint_authority` key.
    pub const MINT_AUTHORITY: usize = 4;
    /// `supply: PodU64`.
    pub const SUPPLY: usize = 36;
    /// `decimals: u8`.
    pub const DECIMALS: usize = 44;
    /// `is_initialized: PodBool`.
    pub const IS_INITIALIZED: usize = 45;
    /// `freeze_authority: PodCOption<Pubkey>` tag.
    pub const FREEZE_AUTHORITY_TAG: usize = 46;
    /// `freeze_authority` key.
    pub const FREEZE_AUTHORITY: usize = 50;
}

/// Field offsets inside `PodAccount` (165 bytes).
pub mod account {
    /// `mint: Pubkey`.
    pub const MINT: usize = 0;
    /// `owner: Pubkey`.
    pub const OWNER: usize = 32;
    /// `amount: PodU64`.
    pub const AMOUNT: usize = 64;
    /// `delegate: PodCOption<Pubkey>` tag.
    pub const DELEGATE_TAG: usize = 72;
    /// `delegate` key.
    pub const DELEGATE: usize = 76;
    /// `state: u8` (`AccountState`).
    pub const STATE: usize = 108;
    /// `is_native: PodCOption<PodU64>` tag.
    pub const IS_NATIVE_TAG: usize = 109;
    /// `is_native` value (rent-exempt reserve of a wrapped-SOL account).
    pub const IS_NATIVE: usize = 113;
    /// `delegated_amount: PodU64`.
    pub const DELEGATED_AMOUNT: usize = 121;
    /// `close_authority: PodCOption<Pubkey>` tag.
    pub const CLOSE_AUTHORITY_TAG: usize = 129;
    /// `close_authority` key.
    pub const CLOSE_AUTHORITY: usize = 133;
}

/// `ExtensionType` discriminants (`#[repr(u16)]`, declaration order in
/// `spl_token_2022::extension::ExtensionType`). Only the ones this crate or
/// its test kit touches are listed.
pub mod extension_type {
    /// Terminates the TLV region: nothing after it is read by Token-2022.
    pub const UNINITIALIZED: u16 = 0;
    /// `MintCloseAuthority` (mint).
    pub const MINT_CLOSE_AUTHORITY: u16 = 3;
    /// `ImmutableOwner` (account). Every ATA carries it.
    pub const IMMUTABLE_OWNER: u16 = 7;
    /// `PermanentDelegate` (mint).
    pub const PERMANENT_DELEGATE: u16 = 12;
    /// `MetadataPointer` (mint).
    pub const METADATA_POINTER: u16 = 18;
    /// `TokenMetadata` (mint, variable length).
    pub const TOKEN_METADATA: u16 = 19;
    /// `GroupPointer` (mint).
    pub const GROUP_POINTER: u16 = 20;
    /// `TokenGroup` (mint).
    pub const TOKEN_GROUP: u16 = 21;
    /// `GroupMemberPointer` (mint).
    pub const GROUP_MEMBER_POINTER: u16 = 22;
    /// `TokenGroupMember` (mint).
    pub const TOKEN_GROUP_MEMBER: u16 = 23;
}

/// Value lengths of the fixed-size extensions read by this crate.
pub mod extension_len {
    /// `MintCloseAuthority { close_authority: OptionalNonZeroPubkey }`.
    pub const MINT_CLOSE_AUTHORITY: usize = 32;
    /// `PermanentDelegate { delegate: OptionalNonZeroPubkey }`.
    pub const PERMANENT_DELEGATE: usize = 32;
    /// `MetadataPointer { authority, metadata_address }`.
    pub const METADATA_POINTER: usize = 64;
    /// `GroupPointer { authority, group_address }`.
    pub const GROUP_POINTER: usize = 64;
    /// `GroupMemberPointer { authority, member_address }`.
    pub const GROUP_MEMBER_POINTER: usize = 64;
    /// `TokenGroupMember { mint, group, member_number: PodU64 }`.
    pub const TOKEN_GROUP_MEMBER: usize = 72;
    /// `TokenGroup { update_authority, mint, size: PodU64, max_size: PodU64 }`.
    pub const TOKEN_GROUP: usize = 80;
    /// `ImmutableOwner` has no value.
    pub const IMMUTABLE_OWNER: usize = 0;
}

/// Byte length of a real SGT mint: 166 + (4+64) + (4+32) + (4+32) + (4+64) + (4+72).
pub const SGT_MINT_LEN: usize = 450;
/// Byte length of a real SGT holder account (an ATA with `ImmutableOwner`).
pub const SGT_TOKEN_ACCOUNT_LEN: usize = 170;
