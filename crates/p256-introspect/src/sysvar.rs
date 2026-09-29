//! A fully bounds-checked reader for the instructions sysvar.
//!
//! Layout, as written by the runtime (`solana-instructions-sysvar` 3.0.1,
//! `serialize_instructions` and `construct_instructions_data`):
//!
//! ```text
//! [0..2]                       num_instructions: u16 LE
//! [2..2 + 2*N]                 instruction_offsets: [u16 LE; N]
//! for each instruction, at its offset:
//!   [0..2]                     num_accounts: u16 LE
//!   [2..2 + 33*A]              accounts: [meta: u8 (bit0 signer, bit1 writable), pubkey: [u8; 32]]
//!   [2 + 33*A..34 + 33*A]      program_id: [u8; 32]
//!   [34 + 33*A..36 + 33*A]     data_len: u16 LE
//!   [36 + 33*A..36 + 33*A + D] data
//! [len - 2..len]               current_instruction_index: u16 LE
//! ```
//!
//! Unlike `pinocchio::sysvars::instructions`, which reads through raw pointers
//! and trusts the layout, every read here goes through `slice::get` with
//! checked arithmetic, so even a forged buffer can only produce an error.
//! The address check itself lives in [`crate::check_instructions_sysvar`];
//! [`InstructionsSysvar::from_bytes`] must only be fed data from an account
//! that passed it.

use crate::error::IntrospectError;

/// Size of one serialized account meta in the sysvar (flags byte + pubkey).
const ACCOUNT_META_SIZE: usize = 33;
/// Size of a `u16` field.
const U16: usize = 2;
/// Size of an address.
const ADDRESS_SIZE: usize = 32;

#[inline]
pub(crate) fn read_u16(data: &[u8], at: usize) -> Option<u16> {
    let end = at.checked_add(U16)?;
    let bytes: [u8; 2] = data.get(at..end)?.try_into().ok()?;
    Some(u16::from_le_bytes(bytes))
}

/// Read-only view over instructions sysvar data.
#[derive(Clone, Copy, Debug)]
pub struct InstructionsSysvar<'a> {
    /// Everything except the trailing current-index `u16`, so no instruction
    /// body can overlap it.
    body: &'a [u8],
    num_instructions: u16,
    current_index: u16,
}

impl<'a> InstructionsSysvar<'a> {
    /// Parse the header of raw instructions sysvar data.
    ///
    /// This does not (and cannot) know where the bytes came from. On-chain,
    /// obtain the data through [`crate::with_secp256r1_instruction`] or check
    /// the account address with [`crate::check_instructions_sysvar`] first.
    pub fn from_bytes(data: &'a [u8]) -> Result<Self, IntrospectError> {
        const MALFORMED: IntrospectError = IntrospectError::MalformedInstructionsSysvar;

        let trailer_start = data.len().checked_sub(U16).ok_or(MALFORMED)?;
        let current_index = read_u16(data, trailer_start).ok_or(MALFORMED)?;
        let body = data.get(..trailer_start).ok_or(MALFORMED)?;

        let num_instructions = read_u16(body, 0).ok_or(MALFORMED)?;
        // The whole offsets table must be present.
        let table_len = usize::from(num_instructions)
            .checked_mul(U16)
            .ok_or(MALFORMED)?;
        let table_end = U16.checked_add(table_len).ok_or(MALFORMED)?;
        if table_end > body.len() {
            return Err(MALFORMED);
        }
        if current_index >= num_instructions {
            return Err(MALFORMED);
        }

        Ok(Self {
            body,
            num_instructions,
            current_index,
        })
    }

    /// Number of top-level instructions in the transaction.
    #[inline]
    pub fn num_instructions(&self) -> u16 {
        self.num_instructions
    }

    /// Index of the top-level instruction currently executing (for a CPI, the
    /// top-level instruction that started the call chain).
    #[inline]
    pub fn current_index(&self) -> u16 {
        self.current_index
    }

    /// Deserialize the instruction at `index`.
    pub fn instruction(&self, index: u16) -> Result<IntrospectedInstruction<'a>, IntrospectError> {
        const MALFORMED: IntrospectError = IntrospectError::MalformedInstructionsSysvar;

        if index >= self.num_instructions {
            return Err(IntrospectError::InstructionIndexOutOfBounds);
        }
        let table_pos = usize::from(index)
            .checked_mul(U16)
            .and_then(|o| o.checked_add(U16))
            .ok_or(MALFORMED)?;
        let start = usize::from(read_u16(self.body, table_pos).ok_or(MALFORMED)?);
        let ix = self.body.get(start..).ok_or(MALFORMED)?;

        let num_accounts = read_u16(ix, 0).ok_or(MALFORMED)?;
        let accounts_len = usize::from(num_accounts)
            .checked_mul(ACCOUNT_META_SIZE)
            .ok_or(MALFORMED)?;
        let accounts_end = U16.checked_add(accounts_len).ok_or(MALFORMED)?;
        let accounts = ix.get(U16..accounts_end).ok_or(MALFORMED)?;

        let program_id_end = accounts_end.checked_add(ADDRESS_SIZE).ok_or(MALFORMED)?;
        let program_id: &'a [u8; 32] = ix
            .get(accounts_end..program_id_end)
            .and_then(|s| s.try_into().ok())
            .ok_or(MALFORMED)?;

        let data_len = usize::from(read_u16(ix, program_id_end).ok_or(MALFORMED)?);
        let data_start = program_id_end.checked_add(U16).ok_or(MALFORMED)?;
        let data_end = data_start.checked_add(data_len).ok_or(MALFORMED)?;
        let data = ix.get(data_start..data_end).ok_or(MALFORMED)?;

        Ok(IntrospectedInstruction {
            program_id,
            accounts,
            num_accounts,
            data,
        })
    }
}

/// One top-level instruction as recorded in the instructions sysvar.
#[derive(Clone, Copy, Debug)]
pub struct IntrospectedInstruction<'a> {
    program_id: &'a [u8; 32],
    accounts: &'a [u8],
    num_accounts: u16,
    data: &'a [u8],
}

impl<'a> IntrospectedInstruction<'a> {
    /// Program id bytes.
    #[inline]
    pub fn program_id(&self) -> &'a [u8; 32] {
        self.program_id
    }

    /// Instruction data.
    #[inline]
    pub fn data(&self) -> &'a [u8] {
        self.data
    }

    /// Number of account metas.
    #[inline]
    pub fn num_accounts(&self) -> u16 {
        self.num_accounts
    }

    /// Account meta `i` as `(is_signer, is_writable, pubkey)`.
    pub fn account(&self, i: u16) -> Result<(bool, bool, &'a [u8; 32]), IntrospectError> {
        if i >= self.num_accounts {
            return Err(IntrospectError::InstructionIndexOutOfBounds);
        }
        let start = usize::from(i)
            .checked_mul(ACCOUNT_META_SIZE)
            .ok_or(IntrospectError::MalformedInstructionsSysvar)?;
        let end = start
            .checked_add(ACCOUNT_META_SIZE)
            .ok_or(IntrospectError::MalformedInstructionsSysvar)?;
        let meta = self
            .accounts
            .get(start..end)
            .ok_or(IntrospectError::MalformedInstructionsSysvar)?;
        let (flags, key) = meta
            .split_first()
            .ok_or(IntrospectError::MalformedInstructionsSysvar)?;
        let key: &'a [u8; 32] = key
            .try_into()
            .map_err(|_| IntrospectError::MalformedInstructionsSysvar)?;
        Ok((flags & 0b01 != 0, flags & 0b10 != 0, key))
    }
}
