//! Error type. Every failure path in this crate returns one of these; nothing
//! in the on-chain path panics on attacker-controlled input.

use pinocchio::error::ProgramError;

/// Base of the [`ProgramError::Custom`] codes produced by this crate.
///
/// Codes are `ERROR_CODE_BASE | variant`, so they read as "P256" in the hex
/// form Solana logs use (`custom program error: 0x25600007`) and stay clear
/// of the small codes a host program assigns to its own errors.
pub const ERROR_CODE_BASE: u32 = 0x2560_0000;

/// Why a secp256r1 introspection check failed.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
#[repr(u32)]
pub enum IntrospectError {
    /// The account passed as the instructions sysvar is not
    /// `Sysvar1nstructions1111111111111111111111111` (spoofed sysvar).
    InvalidInstructionsSysvar = 1,
    /// The instructions sysvar data does not follow the runtime layout
    /// (truncated header, offset past the end, length overflow).
    MalformedInstructionsSysvar = 2,
    /// The requested instruction index is `>= num_instructions`.
    InstructionIndexOutOfBounds = 3,
    /// The instruction at the requested index is not the secp256r1 precompile.
    NotSecp256r1Instruction = 4,
    /// `num_signatures` is 0 or greater than 8.
    InvalidSignatureCount = 5,
    /// The instruction data is too short for `num_signatures` offset records.
    TruncatedOffsets = 6,
    /// An offsets record points into a different instruction than the
    /// precompile instruction itself (Wormhole-class substitution).
    ForeignInstructionIndex = 7,
    /// A signature, public key or message range lies outside the data.
    OffsetOutOfBounds = 8,
    /// The caller asked for a signature entry `>= num_signatures`.
    SignatureIndexOutOfBounds = 9,
    /// `s > n/2`. The precompile rejects this too; this crate re-checks.
    HighS = 10,
    /// `r` or `s` is zero, or `r >= n`.
    ScalarOutOfRange = 11,
    /// The public key does not start with `0x02` or `0x03`.
    InvalidPublicKeyEncoding = 12,
    /// The verified public key differs from the one the caller expected.
    PublicKeyMismatch = 13,
    /// The verified message differs from the one the caller expected.
    MessageMismatch = 14,
}

impl IntrospectError {
    /// Every variant, in code order. Used to decode logged codes.
    pub const ALL: [IntrospectError; 14] = [
        Self::InvalidInstructionsSysvar,
        Self::MalformedInstructionsSysvar,
        Self::InstructionIndexOutOfBounds,
        Self::NotSecp256r1Instruction,
        Self::InvalidSignatureCount,
        Self::TruncatedOffsets,
        Self::ForeignInstructionIndex,
        Self::OffsetOutOfBounds,
        Self::SignatureIndexOutOfBounds,
        Self::HighS,
        Self::ScalarOutOfRange,
        Self::InvalidPublicKeyEncoding,
        Self::PublicKeyMismatch,
        Self::MessageMismatch,
    ];

    /// The `ProgramError::Custom` code for this error.
    #[inline]
    pub const fn code(self) -> u32 {
        // Bitwise OR: the base has its low 16 bits clear, so this cannot
        // overflow or collide.
        ERROR_CODE_BASE | self as u32
    }

    /// Inverse of [`IntrospectError::code`].
    pub fn from_code(code: u32) -> Option<Self> {
        Self::ALL.iter().copied().find(|e| e.code() == code)
    }
}

impl From<IntrospectError> for ProgramError {
    #[inline]
    fn from(e: IntrospectError) -> Self {
        ProgramError::Custom(e.code())
    }
}

impl core::fmt::Display for IntrospectError {
    fn fmt(&self, f: &mut core::fmt::Formatter<'_>) -> core::fmt::Result {
        let s = match self {
            Self::InvalidInstructionsSysvar => "account is not the instructions sysvar",
            Self::MalformedInstructionsSysvar => "instructions sysvar data is malformed",
            Self::InstructionIndexOutOfBounds => "instruction index out of bounds",
            Self::NotSecp256r1Instruction => "instruction is not the secp256r1 precompile",
            Self::InvalidSignatureCount => "num_signatures must be 1..=8",
            Self::TruncatedOffsets => "instruction data too short for its offsets",
            Self::ForeignInstructionIndex => "offsets point into another instruction",
            Self::OffsetOutOfBounds => "offset range outside instruction data",
            Self::SignatureIndexOutOfBounds => "signature index out of bounds",
            Self::HighS => "signature s is above n/2 (high-S)",
            Self::ScalarOutOfRange => "signature r or s out of range",
            Self::InvalidPublicKeyEncoding => "public key is not SEC1-compressed",
            Self::PublicKeyMismatch => "public key mismatch",
            Self::MessageMismatch => "message mismatch",
        };
        f.write_str(s)
    }
}

#[cfg(feature = "std")]
impl std::error::Error for IntrospectError {}
