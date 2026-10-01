//! Classic SPL Token for the v1.2 SKR paths (`INTERFACE.md` §11): the pinned
//! program ids and mints, a strict token-account reader, and the two CPIs
//! heads_down makes (`Transfer`, `CloseAccount`).
//!
//! SKR and ORE are classic SPL Token mints. Every SKR / ORE token account is
//! checked, before any byte is read, to be owned by the SPL Token program
//! (so a Token-2022 look-alike, whose transfer fees or hooks would break
//! conservation, is refused) and to be exactly 165 bytes. The CPI program id
//! is always the pinned constant, never an account the caller chose.

use pinocchio::{
    cpi::{invoke_signed, Signer},
    error::ProgramError,
    instruction::{InstructionAccount, InstructionView},
    AccountView, Address, ProgramResult,
};

use crate::error::HdError;

/// SPL Token `TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA`.
pub const SPL_TOKEN_PROGRAM_ID: Address = Address::new_from_array([
    0x06, 0xdd, 0xf6, 0xe1, 0xd7, 0x65, 0xa1, 0x93, 0xd9, 0xcb, 0xe1, 0x46, 0xce, 0xeb, 0x79, 0xac,
    0x1c, 0xb4, 0x85, 0xed, 0x5f, 0x5b, 0x37, 0x91, 0x3a, 0x8c, 0xf5, 0x85, 0x7e, 0xff, 0x00, 0xa9,
]);
/// Associated Token Account program `ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL`
/// (used only to derive canonical vault addresses; never invoked).
pub const ATA_PROGRAM_ID: Address = Address::new_from_array([
    0x8c, 0x97, 0x25, 0x8f, 0x4e, 0x24, 0x89, 0xf1, 0xbb, 0x3d, 0x10, 0x29, 0x14, 0x8e, 0x0d, 0x83,
    0x0b, 0x5a, 0x13, 0x99, 0xda, 0xff, 0x10, 0x84, 0x04, 0x8e, 0x7b, 0xd8, 0xdb, 0xe9, 0xf8, 0x59,
]);
/// SKR mint `SKRbvo6Gf7GondiT3BbTfuRDPqLWei4j2Qy2NPGZhW3` (classic SPL Token,
/// 6 decimals, no freeze authority).
pub const SKR_MINT: Address = Address::new_from_array([
    0x06, 0x7c, 0x5a, 0x3e, 0x05, 0xfe, 0x41, 0x47, 0x12, 0xa7, 0xa2, 0xea, 0xfe, 0x42, 0xbe, 0x76,
    0x10, 0xbc, 0xd9, 0x0c, 0xbf, 0x57, 0x16, 0x27, 0x75, 0x83, 0x73, 0xcb, 0x8a, 0xd0, 0xd8, 0xa4,
]);
/// SKR decimals.
pub const SKR_DECIMALS: u8 = 6;
/// One whole SKR in base units.
pub const ONE_SKR: u64 = 1_000_000;

/// SPL Token account length.
pub const TOKEN_ACCOUNT_LEN: usize = 165;
/// SPL Token mint length.
pub const MINT_LEN: usize = 82;

/// Token-account field offsets (`spl_token::state::Account`).
mod off {
    pub const MINT: usize = 0;
    pub const OWNER: usize = 32;
    pub const AMOUNT: usize = 64;
    pub const DELEGATE_TAG: usize = 72;
    pub const STATE: usize = 108;
    pub const NATIVE_TAG: usize = 109;
    pub const CLOSE_AUTHORITY_TAG: usize = 129;
    /// Mint supply (`spl_token::state::Mint`).
    pub const MINT_SUPPLY: usize = 36;
    /// Mint `is_initialized`.
    pub const MINT_INITIALIZED: usize = 45;
}

/// `AccountState::Initialized`.
const STATE_INITIALIZED: u8 = 1;

/// SPL Token instruction tags.
mod ix {
    pub const TRANSFER: u8 = 3;
    pub const CLOSE_ACCOUNT: u8 = 9;
}

/// The token-account fields heads_down reads.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct TokenAccount {
    /// Mint.
    pub mint: [u8; 32],
    /// Owner field (the wallet or PDA that may move the tokens).
    pub owner: [u8; 32],
    /// Balance.
    pub amount: u64,
    /// A delegate is set.
    pub has_delegate: bool,
    /// A close authority is set.
    pub has_close_authority: bool,
}

fn u64_at(d: &[u8], at: usize) -> Result<u64, HdError> {
    let b: [u8; 8] = d
        .get(at..at.checked_add(8).ok_or(HdError::InvalidTokenAccount)?)
        .and_then(|s| s.try_into().ok())
        .ok_or(HdError::InvalidTokenAccount)?;
    Ok(u64::from_le_bytes(b))
}

fn key_at(d: &[u8], at: usize) -> Result<[u8; 32], HdError> {
    d.get(at..at.checked_add(32).ok_or(HdError::InvalidTokenAccount)?)
        .and_then(|s| s.try_into().ok())
        .ok_or(HdError::InvalidTokenAccount)
}

fn u32_at(d: &[u8], at: usize) -> Result<u32, HdError> {
    let b: [u8; 4] = d
        .get(at..at.checked_add(4).ok_or(HdError::InvalidTokenAccount)?)
        .and_then(|s| s.try_into().ok())
        .ok_or(HdError::InvalidTokenAccount)?;
    Ok(u32::from_le_bytes(b))
}

/// Parse raw token-account bytes (exact length, initialized, not native).
pub fn parse_token_account(d: &[u8]) -> Result<TokenAccount, HdError> {
    if d.len() != TOKEN_ACCOUNT_LEN {
        return Err(HdError::InvalidTokenAccount);
    }
    if d.get(off::STATE) != Some(&STATE_INITIALIZED) {
        // Uninitialized, or Frozen (neither SKR nor ORE has a freeze authority).
        return Err(HdError::InvalidTokenAccount);
    }
    if u32_at(d, off::NATIVE_TAG)? != 0 {
        return Err(HdError::InvalidTokenAccount);
    }
    Ok(TokenAccount {
        mint: key_at(d, off::MINT)?,
        owner: key_at(d, off::OWNER)?,
        amount: u64_at(d, off::AMOUNT)?,
        has_delegate: u32_at(d, off::DELEGATE_TAG)? != 0,
        has_close_authority: u32_at(d, off::CLOSE_AUTHORITY_TAG)? != 0,
    })
}

/// Read `account` as a classic SPL Token account: owner == SPL Token (a
/// Token-2022 account fails here), 165 bytes, initialized, not native.
pub fn read_token_account(account: &AccountView) -> Result<TokenAccount, HdError> {
    if !account.owned_by(&SPL_TOKEN_PROGRAM_ID) {
        return Err(HdError::InvalidTokenAccount);
    }
    let d = account
        .try_borrow()
        .map_err(|_| HdError::InvalidTokenAccount)?;
    parse_token_account(&d)
}

/// A user's token account: classic SPL Token, `mint`, owner field `owner`.
/// Used for bond sources (the owner signs the transfer) and for payout
/// destinations (the owner is fixed by state, never by the caller).
pub fn check_user_account(
    account: &AccountView,
    mint: &Address,
    owner: &[u8; 32],
) -> Result<TokenAccount, HdError> {
    let t = read_token_account(account)?;
    if t.mint != *mint.as_array() || t.owner != *owner {
        return Err(HdError::InvalidTokenAccount);
    }
    Ok(t)
}

/// `ATA(owner, mint)` under classic SPL Token: `find_program_address([owner,
/// SPL Token, mint], ATA program)`.
pub fn ata(owner: &Address, mint: &Address) -> Address {
    crate::pda::find(
        &[owner.as_ref(), SPL_TOKEN_PROGRAM_ID.as_ref(), mint.as_ref()],
        &ATA_PROGRAM_ID,
    )
    .0
}

/// A program vault: `account` is exactly `expected` (the canonical ATA of
/// `owner_pda` for `mint`, derived once and stored), classic SPL Token,
/// `mint`, owner field `owner_pda`, no delegate, no close authority.
pub fn check_vault(
    account: &AccountView,
    expected: &[u8; 32],
    mint: &Address,
    owner_pda: &Address,
) -> Result<TokenAccount, HdError> {
    if account.address().as_array() != expected {
        return Err(HdError::InvalidTokenAccount);
    }
    let t = read_token_account(account)?;
    if t.mint != *mint.as_array()
        || t.owner != *owner_pda.as_array()
        || t.has_delegate
        || t.has_close_authority
    {
        return Err(HdError::InvalidTokenAccount);
    }
    Ok(t)
}

/// A new vault: derive `ATA(owner_pda, mint)`, require `account` to be it
/// (created beforehand by the client with the ATA program), then
/// [`check_vault`]. Returns the address to store.
pub fn check_new_vault(
    account: &AccountView,
    mint: &Address,
    owner_pda: &Address,
) -> Result<[u8; 32], HdError> {
    let expected = *ata(owner_pda, mint).as_array();
    check_vault(account, &expected, mint, owner_pda)?;
    Ok(expected)
}

/// The token balance of `account` (already validated by the caller).
pub fn balance(account: &AccountView) -> Result<u64, HdError> {
    Ok(read_token_account(account)?.amount)
}

/// Supply of the classic SPL Token mint `expected` (address, owner, length
/// and initialization checked).
pub fn mint_supply(account: &AccountView, expected: &Address) -> Result<u64, HdError> {
    if account.address() != expected
        || !account.owned_by(&SPL_TOKEN_PROGRAM_ID)
        || account.data_len() != MINT_LEN
    {
        return Err(HdError::InvalidTokenAccount);
    }
    let d = account
        .try_borrow()
        .map_err(|_| HdError::InvalidTokenAccount)?;
    if d.get(off::MINT_INITIALIZED) != Some(&1) {
        return Err(HdError::InvalidTokenAccount);
    }
    u64_at(&d, off::MINT_SUPPLY)
}

/// The token program slot must be classic SPL Token.
pub fn check_token_program(account: &AccountView) -> Result<(), HdError> {
    if account.address() != &SPL_TOKEN_PROGRAM_ID {
        return Err(HdError::InvalidTokenAccount);
    }
    Ok(())
}

/// CPI SPL Token `Transfer(amount)` from `source` to `destination`,
/// authorized by `authority` (a transaction signer, or a heads_down PDA
/// signing through `signers`).
pub fn transfer(
    source: &AccountView,
    destination: &AccountView,
    authority: &AccountView,
    amount: u64,
    signers: &[Signer],
) -> ProgramResult {
    if source.address() == destination.address() {
        return Err(ProgramError::InvalidArgument);
    }
    let mut data = [0u8; 9];
    let (tag, rest) = data.split_at_mut(1);
    tag.copy_from_slice(&[ix::TRANSFER]);
    rest.copy_from_slice(&amount.to_le_bytes());
    let metas = [
        InstructionAccount::writable(source.address()),
        InstructionAccount::writable(destination.address()),
        InstructionAccount::readonly_signer(authority.address()),
    ];
    let ix = InstructionView {
        program_id: &SPL_TOKEN_PROGRAM_ID,
        data: &data,
        accounts: &metas,
    };
    invoke_signed(&ix, &[source, destination, authority], signers)
}

/// CPI SPL Token `CloseAccount`: the (empty) `account`'s rent goes to
/// `destination`, authorized by the PDA `authority` through `signers`.
pub fn close_account(
    account: &AccountView,
    destination: &AccountView,
    authority: &AccountView,
    signers: &[Signer],
) -> ProgramResult {
    let data = [ix::CLOSE_ACCOUNT];
    let metas = [
        InstructionAccount::writable(account.address()),
        InstructionAccount::writable(destination.address()),
        InstructionAccount::readonly_signer(authority.address()),
    ];
    let ix = InstructionView {
        program_id: &SPL_TOKEN_PROGRAM_ID,
        data: &data,
        accounts: &metas,
    };
    invoke_signed(&ix, &[account, destination, authority], signers)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn account_bytes(state: u8, native: bool, delegate: bool, close: bool) -> [u8; 165] {
        let mut d = [0u8; 165];
        d[..32].copy_from_slice(SKR_MINT.as_ref());
        d[32..64].copy_from_slice(&[7; 32]);
        d[64..72].copy_from_slice(&5u64.to_le_bytes());
        if delegate {
            d[72] = 1;
        }
        d[108] = state;
        if native {
            d[109] = 1;
        }
        if close {
            d[129] = 1;
        }
        d
    }

    #[test]
    fn token_accounts_parse_strictly() {
        let t = parse_token_account(&account_bytes(1, false, false, false)).unwrap();
        assert_eq!(t.mint, *SKR_MINT.as_array());
        assert_eq!(t.owner, [7; 32]);
        assert_eq!(t.amount, 5);
        assert!(!t.has_delegate && !t.has_close_authority);
        let t = parse_token_account(&account_bytes(1, false, true, true)).unwrap();
        assert!(t.has_delegate && t.has_close_authority);
        // Uninitialized, frozen, native, wrong length.
        for bad in [
            account_bytes(0, false, false, false),
            account_bytes(2, false, false, false),
            account_bytes(1, true, false, false),
        ] {
            assert_eq!(parse_token_account(&bad), Err(HdError::InvalidTokenAccount));
        }
        assert!(parse_token_account(&[0u8; 164]).is_err());
        assert!(parse_token_account(&[0u8; 166]).is_err());
        assert!(parse_token_account(&[]).is_err());
    }
}
