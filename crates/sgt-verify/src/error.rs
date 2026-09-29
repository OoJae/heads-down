//! Errors returned by the verifier. Every rejection has its own variant so a
//! spoof test can assert *which* check stopped it, not merely that it failed.

use pinocchio::error::ProgramError;

/// Base added to [`SgtError`] codes when converted to
/// [`ProgramError::Custom`], so they do not collide with a host program's own
/// error codes. `0x5347_xxxx` spells "SG".
pub const SGT_ERROR_BASE: u32 = 0x5347_0000;

/// Why an account pair is not a valid SGT holding.
#[repr(u32)]
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum SgtError {
    // --- account-level checks (before any data is read) -------------------
    /// The token account is not owned by the Token-2022 program (e.g. the
    /// legacy SPL Token program, or a program an attacker deployed).
    TokenAccountNotToken2022 = 1,
    /// The mint account is not owned by the Token-2022 program.
    MintNotToken2022 = 2,
    /// The same account was passed as both token account and mint.
    DuplicateAccount = 3,
    /// Account data was already mutably borrowed.
    AccountBorrowFailed = 4,

    // --- mint layout -----------------------------------------------------
    /// Mint data is too short, is the Multisig length, or sits in the
    /// invalid gap between the base mint and the extended layout.
    MintInvalidLength = 10,
    /// Bytes 82..165 of an extended mint are not all zero.
    MintPaddingNotZero = 11,
    /// The account-type byte at offset 165 is not `Mint` (1).
    MintAccountTypeMismatch = 12,
    /// `is_initialized` is not exactly 1.
    MintNotInitialized = 13,
    /// A `COption` tag in the mint is neither `None` nor `Some`.
    MintInvalidOption = 14,
    /// The mint has no extensions (a plain 82-byte mint cannot be an SGT).
    MintMissingExtensions = 15,

    // --- mint policy -----------------------------------------------------
    /// `mint_authority` is `None` or not the SGT authority.
    MintAuthorityMismatch = 20,
    /// `freeze_authority` is `None` or not the SGT authority.
    FreezeAuthorityMismatch = 21,
    /// `decimals != 0`.
    DecimalsNotZero = 22,
    /// `supply != 1`.
    SupplyNotOne = 23,

    // --- TLV / extensions --------------------------------------------------
    /// A TLV entry's header or value runs past the end of the account data.
    MalformedTlv = 30,
    /// The same extension type appears twice.
    DuplicateExtension = 31,
    /// More TLV entries than any real Token-2022 account can hold.
    TooManyExtensions = 32,
    /// An extension we read has the wrong value length for its type.
    InvalidExtensionLength = 33,
    /// No `TokenGroupMember` extension.
    MissingGroupMember = 34,
    /// `TokenGroupMember.group` is not the SGT group.
    GroupMismatch = 35,
    /// `TokenGroupMember.mint` is not this mint.
    GroupMemberMintMismatch = 36,
    /// `TokenGroupMember.member_number` is zero (Token-2022 numbers from 1).
    InvalidMemberNumber = 37,
    /// No `GroupMemberPointer` extension.
    MissingGroupMemberPointer = 38,
    /// `GroupMemberPointer` does not point at the mint itself, or its
    /// authority is not the SGT authority.
    GroupMemberPointerMismatch = 39,
    /// No `PermanentDelegate` extension.
    MissingPermanentDelegate = 40,
    /// The permanent delegate is not the SGT authority.
    PermanentDelegateMismatch = 41,
    /// No `MetadataPointer` extension.
    MissingMetadataPointer = 42,
    /// `MetadataPointer` does not point at the SGT group, or its authority is
    /// not the SGT authority.
    MetadataPointerMismatch = 43,
    /// No `MintCloseAuthority` extension.
    MissingMintCloseAuthority = 44,
    /// The mint close authority is not the SGT authority.
    MintCloseAuthorityMismatch = 45,

    // --- token account layout ---------------------------------------------
    /// Token account data is shorter than 165 bytes or is the Multisig length.
    TokenAccountInvalidLength = 50,
    /// The account-type byte at offset 165 is not `Account` (2).
    TokenAccountTypeMismatch = 51,
    /// Account state byte is not a defined `AccountState`.
    TokenAccountInvalidState = 52,
    /// Account state is `Uninitialized`.
    TokenAccountNotInitialized = 53,
    /// A `COption` tag in the token account is neither `None` nor `Some`.
    TokenAccountInvalidOption = 54,

    // --- token account policy ----------------------------------------------
    /// The token account's `mint` field is not the mint passed in.
    TokenAccountMintMismatch = 60,
    /// The token account's `owner` field is not the expected owner.
    TokenAccountOwnerMismatch = 61,
    /// `amount != 1`.
    AmountNotOne = 62,
    /// The token account is a wrapped-SOL (native) account.
    NativeTokenAccount = 63,
}

impl SgtError {
    /// The code this error carries inside [`ProgramError::Custom`].
    pub const fn program_error_code(self) -> u32 {
        // Discriminants are < 0x1_0000, so this cannot overflow.
        SGT_ERROR_BASE | self as u32
    }
}

impl From<SgtError> for ProgramError {
    fn from(e: SgtError) -> Self {
        ProgramError::Custom(e.program_error_code())
    }
}
