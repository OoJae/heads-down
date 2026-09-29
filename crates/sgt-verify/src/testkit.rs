//! Host-side test kit (feature `std`): build SGT-shaped accounts without a
//! Seeker.
//!
//! Two ways in:
//!
//! 1. **Real Token-2022 instructions** ([`create_test_group`],
//!    [`issue_test_sgt`], [`move_test_sgt`]). They replay Solana Mobile's
//!    mainnet issuance transaction step for step (same extensions, same order,
//!    same freeze) against a group mint *you* control. Send them to LiteSVM, a
//!    local validator or devnet, then verify with a `test-group` build.
//! 2. **Byte-exact synthetic accounts** ([`sgt_mint_bytes`],
//!    [`sgt_token_account_bytes`]) for `LiteSVM::set_account` or a Surfpool
//!    `surfnet_setAccount` cheatcode. With the mainnet anchors and a real
//!    mint's address and member number they reproduce the on-chain bytes
//!    exactly (see `tests/testkit.rs`).
//!
//! Instruction encodings are transcribed from spl-token-2022 9.0.0 and
//! spl-token-group-interface 0.6.0 and exercised against the Token-2022 ELF
//! that ships with LiteSVM.

// Host-only tooling that writes into buffers of known, constant length; the
// on-chain lints of the crate root are relaxed here on purpose.
#![allow(
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects,
    clippy::cast_possible_truncation
)]

use std::{vec, vec::Vec};

use pinocchio::Address;
use solana_instruction::{AccountMeta, Instruction};

use crate::{
    anchors::TOKEN_2022_PROGRAM_ID,
    layout::{
        account, account_state, account_type, coption, extension_len, extension_type, mint,
        ACCOUNT_BASE_LEN, SGT_MINT_LEN, SGT_TOKEN_ACCOUNT_LEN, TLV_START,
    },
};

/// System program.
pub const SYSTEM_PROGRAM_ID: Address = Address::from_str_const("11111111111111111111111111111111");
/// SPL Associated Token Account program.
pub const ASSOCIATED_TOKEN_PROGRAM_ID: Address =
    Address::from_str_const("ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL");

/// **Public** ed25519 seed of [`crate::anchors::PUBLIC_TEST_AUTHORITY`]:
/// `sha256("sgt-verify/public-test-authority/v1")`. It is published on
/// purpose so tests can sign as the default test authority; it guards
/// nothing. Use `solana_keypair::Keypair::new_from_array(seed)`.
pub const PUBLIC_TEST_AUTHORITY_SEED: [u8; 32] = [
    97, 108, 25, 249, 89, 42, 98, 111, 126, 198, 50, 208, 70, 211, 161, 112, 21, 71, 214, 82, 27,
    49, 126, 15, 91, 52, 246, 42, 216, 186, 203, 149,
];

/// **Public** ed25519 seed of [`crate::anchors::PUBLIC_TEST_GROUP`]:
/// `sha256("sgt-verify/public-test-group/v1")`. Published on purpose; see
/// [`PUBLIC_TEST_AUTHORITY_SEED`].
pub const PUBLIC_TEST_GROUP_SEED: [u8; 32] = [
    126, 238, 164, 7, 63, 171, 241, 15, 123, 91, 24, 160, 75, 195, 222, 50, 92, 173, 30, 56, 229,
    77, 91, 125, 119, 67, 56, 201, 107, 94, 101, 200,
];

/// Size of a test group mint when created: GroupPointer + MintCloseAuthority.
pub const TEST_GROUP_MINT_INITIAL_LEN: usize =
    TLV_START + 4 + extension_len::GROUP_POINTER + 4 + extension_len::MINT_CLOSE_AUTHORITY;
/// Size after `InitializeGroup` reallocates it to add the TokenGroup entry.
/// Fund the account for this size up front.
pub const TEST_GROUP_MINT_LEN: usize = TEST_GROUP_MINT_INITIAL_LEN + 4 + extension_len::TOKEN_GROUP;
/// Size of an SGT mint when created, before `InitializeMember` reallocates it
/// to [`SGT_MINT_LEN`]. Matches the `space: 374` of Solana Mobile's issuance.
pub const SGT_MINT_INITIAL_LEN: usize = SGT_MINT_LEN - 4 - extension_len::TOKEN_GROUP_MEMBER;

/// Discriminator of `spl_token_group_interface:initialize_token_group`
/// (first 8 bytes of its sha256).
pub const INITIALIZE_GROUP_DISCRIMINATOR: [u8; 8] = [121, 113, 108, 39, 54, 51, 0, 4];
/// Discriminator of `spl_token_group_interface:initialize_member`.
pub const INITIALIZE_MEMBER_DISCRIMINATOR: [u8; 8] = [152, 32, 222, 176, 223, 237, 116, 134];

/// Raw Token-2022 and System instruction encoders.
pub mod ix {
    use super::*;

    fn token_ix(accounts: Vec<AccountMeta>, data: Vec<u8>) -> Instruction {
        Instruction {
            program_id: TOKEN_2022_PROGRAM_ID,
            accounts,
            data,
        }
    }

    fn optional_key(key: Option<&Address>) -> [u8; 32] {
        // OptionalNonZeroPubkey: all zeros means None.
        key.map_or([0; 32], Address::to_bytes)
    }

    fn push_coption(data: &mut Vec<u8>, key: Option<&Address>) {
        match key {
            Some(k) => {
                data.push(1);
                data.extend_from_slice(k.as_ref());
            }
            None => data.push(0),
        }
    }

    /// `SystemInstruction::CreateAccount`. Signers: `payer`, `new_account`.
    pub fn create_account(
        payer: &Address,
        new_account: &Address,
        lamports: u64,
        space: u64,
        owner: &Address,
    ) -> Instruction {
        let mut data = Vec::with_capacity(52);
        data.extend_from_slice(&0u32.to_le_bytes());
        data.extend_from_slice(&lamports.to_le_bytes());
        data.extend_from_slice(&space.to_le_bytes());
        data.extend_from_slice(owner.as_ref());
        Instruction {
            program_id: SYSTEM_PROGRAM_ID,
            accounts: vec![
                AccountMeta::new(*payer, true),
                AccountMeta::new(*new_account, true),
            ],
            data,
        }
    }

    /// `InitializeMint2` (tag 20).
    pub fn initialize_mint2(
        mint: &Address,
        decimals: u8,
        mint_authority: &Address,
        freeze_authority: Option<&Address>,
    ) -> Instruction {
        let mut data = vec![20, decimals];
        data.extend_from_slice(mint_authority.as_ref());
        push_coption(&mut data, freeze_authority);
        token_ix(vec![AccountMeta::new(*mint, false)], data)
    }

    /// `InitializeMintCloseAuthority` (tag 25).
    pub fn initialize_mint_close_authority(mint: &Address, close: Option<&Address>) -> Instruction {
        let mut data = vec![25];
        push_coption(&mut data, close);
        token_ix(vec![AccountMeta::new(*mint, false)], data)
    }

    /// `InitializePermanentDelegate` (tag 35).
    pub fn initialize_permanent_delegate(mint: &Address, delegate: &Address) -> Instruction {
        let mut data = vec![35];
        data.extend_from_slice(delegate.as_ref());
        token_ix(vec![AccountMeta::new(*mint, false)], data)
    }

    fn pointer_initialize(tag: u8, mint: &Address, authority: Option<&Address>, target: Option<&Address>) -> Instruction {
        let mut data = vec![tag, 0];
        data.extend_from_slice(&optional_key(authority));
        data.extend_from_slice(&optional_key(target));
        token_ix(vec![AccountMeta::new(*mint, false)], data)
    }

    /// `MetadataPointerExtension::Initialize` (tag 39, 0).
    pub fn initialize_metadata_pointer(
        mint: &Address,
        authority: Option<&Address>,
        metadata_address: Option<&Address>,
    ) -> Instruction {
        pointer_initialize(39, mint, authority, metadata_address)
    }

    /// `GroupPointerExtension::Initialize` (tag 40, 0).
    pub fn initialize_group_pointer(
        mint: &Address,
        authority: Option<&Address>,
        group_address: Option<&Address>,
    ) -> Instruction {
        pointer_initialize(40, mint, authority, group_address)
    }

    /// `GroupMemberPointerExtension::Initialize` (tag 41, 0).
    pub fn initialize_group_member_pointer(
        mint: &Address,
        authority: Option<&Address>,
        member_address: Option<&Address>,
    ) -> Instruction {
        pointer_initialize(41, mint, authority, member_address)
    }

    /// token-group `InitializeGroup`, embedded in the mint. Signer:
    /// `mint_authority`.
    pub fn initialize_group(
        group_mint: &Address,
        mint_authority: &Address,
        update_authority: Option<&Address>,
        max_size: u64,
    ) -> Instruction {
        let mut data = INITIALIZE_GROUP_DISCRIMINATOR.to_vec();
        data.extend_from_slice(&optional_key(update_authority));
        data.extend_from_slice(&max_size.to_le_bytes());
        token_ix(
            vec![
                AccountMeta::new(*group_mint, false),
                AccountMeta::new_readonly(*group_mint, false),
                AccountMeta::new_readonly(*mint_authority, true),
            ],
            data,
        )
    }

    /// token-group `InitializeMember`, embedded in the member mint. Signers:
    /// `member_mint_authority`, `group_update_authority`.
    pub fn initialize_member(
        member_mint: &Address,
        member_mint_authority: &Address,
        group: &Address,
        group_update_authority: &Address,
    ) -> Instruction {
        token_ix(
            vec![
                AccountMeta::new(*member_mint, false),
                AccountMeta::new_readonly(*member_mint, false),
                AccountMeta::new_readonly(*member_mint_authority, true),
                AccountMeta::new(*group, false),
                AccountMeta::new_readonly(*group_update_authority, true),
            ],
            INITIALIZE_MEMBER_DISCRIMINATOR.to_vec(),
        )
    }

    /// `MintToChecked` (tag 14). Signer: `authority`.
    pub fn mint_to_checked(
        mint: &Address,
        destination: &Address,
        authority: &Address,
        amount: u64,
        decimals: u8,
    ) -> Instruction {
        let mut data = vec![14];
        data.extend_from_slice(&amount.to_le_bytes());
        data.push(decimals);
        token_ix(
            vec![
                AccountMeta::new(*mint, false),
                AccountMeta::new(*destination, false),
                AccountMeta::new_readonly(*authority, true),
            ],
            data,
        )
    }

    /// `TransferChecked` (tag 12). Signer: `authority` (owner, delegate or
    /// permanent delegate).
    pub fn transfer_checked(
        source: &Address,
        mint: &Address,
        destination: &Address,
        authority: &Address,
        amount: u64,
        decimals: u8,
    ) -> Instruction {
        let mut data = vec![12];
        data.extend_from_slice(&amount.to_le_bytes());
        data.push(decimals);
        token_ix(
            vec![
                AccountMeta::new(*source, false),
                AccountMeta::new_readonly(*mint, false),
                AccountMeta::new(*destination, false),
                AccountMeta::new_readonly(*authority, true),
            ],
            data,
        )
    }

    fn freeze_or_thaw(tag: u8, account: &Address, mint: &Address, authority: &Address) -> Instruction {
        token_ix(
            vec![
                AccountMeta::new(*account, false),
                AccountMeta::new_readonly(*mint, false),
                AccountMeta::new_readonly(*authority, true),
            ],
            vec![tag],
        )
    }

    /// `FreezeAccount` (tag 10). Signer: `freeze_authority`.
    pub fn freeze_account(account: &Address, mint: &Address, freeze_authority: &Address) -> Instruction {
        freeze_or_thaw(10, account, mint, freeze_authority)
    }

    /// `ThawAccount` (tag 11). Signer: `freeze_authority`.
    pub fn thaw_account(account: &Address, mint: &Address, freeze_authority: &Address) -> Instruction {
        freeze_or_thaw(11, account, mint, freeze_authority)
    }

    /// `SetAuthority` (tag 6) for `AuthorityType::MintTokens` (0). Signer:
    /// `current`.
    pub fn set_mint_authority(mint: &Address, current: &Address, new: Option<&Address>) -> Instruction {
        let mut data = vec![6, 0];
        push_coption(&mut data, new);
        token_ix(
            vec![
                AccountMeta::new(*mint, false),
                AccountMeta::new_readonly(*current, true),
            ],
            data,
        )
    }

    /// Associated Token Account `CreateIdempotent` for a Token-2022 mint.
    /// Signer: `payer`.
    pub fn create_associated_token_account_idempotent(
        payer: &Address,
        wallet: &Address,
        mint: &Address,
    ) -> Instruction {
        Instruction {
            program_id: ASSOCIATED_TOKEN_PROGRAM_ID,
            accounts: vec![
                AccountMeta::new(*payer, true),
                AccountMeta::new(associated_token_address(wallet, mint), false),
                AccountMeta::new_readonly(*wallet, false),
                AccountMeta::new_readonly(*mint, false),
                AccountMeta::new_readonly(SYSTEM_PROGRAM_ID, false),
                AccountMeta::new_readonly(TOKEN_2022_PROGRAM_ID, false),
            ],
            data: vec![1],
        }
    }
}

/// The Token-2022 associated token account of `wallet` for `mint`.
pub fn associated_token_address(wallet: &Address, mint: &Address) -> Address {
    Address::find_program_address(
        &[wallet.as_ref(), TOKEN_2022_PROGRAM_ID.as_ref(), mint.as_ref()],
        &ASSOCIATED_TOKEN_PROGRAM_ID,
    )
    .0
}

/// Create a Token-2022 **group mint you control**, shaped like the SGT group:
/// GroupPointer to itself, MintCloseAuthority, mint and freeze authority
/// `authority`, decimals 0, and a TokenGroup with update authority
/// `authority`.
///
/// `lamports` must cover rent for [`TEST_GROUP_MINT_LEN`].
/// Signers: `payer`, `group_mint`, `authority`.
pub fn create_test_group(
    payer: &Address,
    group_mint: &Address,
    authority: &Address,
    max_size: u64,
    lamports: u64,
) -> Vec<Instruction> {
    vec![
        ix::create_account(
            payer,
            group_mint,
            lamports,
            TEST_GROUP_MINT_INITIAL_LEN as u64,
            &TOKEN_2022_PROGRAM_ID,
        ),
        ix::initialize_group_pointer(group_mint, Some(authority), Some(group_mint)),
        ix::initialize_mint_close_authority(group_mint, Some(authority)),
        ix::initialize_mint2(group_mint, 0, authority, Some(authority)),
        ix::initialize_group(group_mint, authority, Some(authority), max_size),
    ]
}

/// Issue one test SGT to `holder`, replaying Solana Mobile's mainnet issuance
/// transaction: create the mint (374 bytes), MetadataPointer -> group,
/// PermanentDelegate, MintCloseAuthority, GroupMemberPointer -> self,
/// initialize the mint (decimals 0, mint + freeze authority), InitializeMember
/// into the group, create the holder's ATA, mint 1, freeze it.
///
/// `lamports` must cover rent for [`SGT_MINT_LEN`] (the size after
/// `InitializeMember`). Signers: `payer`, `member_mint`, `authority`.
pub fn issue_test_sgt(
    payer: &Address,
    group_mint: &Address,
    authority: &Address,
    member_mint: &Address,
    holder: &Address,
    lamports: u64,
) -> Vec<Instruction> {
    let holder_ata = associated_token_address(holder, member_mint);
    vec![
        ix::create_account(
            payer,
            member_mint,
            lamports,
            SGT_MINT_INITIAL_LEN as u64,
            &TOKEN_2022_PROGRAM_ID,
        ),
        ix::initialize_metadata_pointer(member_mint, Some(authority), Some(group_mint)),
        ix::initialize_permanent_delegate(member_mint, authority),
        ix::initialize_mint_close_authority(member_mint, Some(authority)),
        ix::initialize_group_member_pointer(member_mint, Some(authority), Some(member_mint)),
        ix::initialize_mint2(member_mint, 0, authority, Some(authority)),
        ix::initialize_member(member_mint, authority, group_mint, authority),
        ix::create_associated_token_account_idempotent(payer, holder, member_mint),
        ix::mint_to_checked(member_mint, &holder_ata, authority, 1, 0),
        ix::freeze_account(&holder_ata, member_mint, authority),
    ]
}

/// Move a test SGT between wallets the way Solana Mobile moves real ones
/// between a user's Seed Vault wallets: thaw the source, transfer with the
/// permanent delegate, freeze the destination. Signers: `payer`, `authority`.
pub fn move_test_sgt(
    payer: &Address,
    authority: &Address,
    member_mint: &Address,
    from: &Address,
    to: &Address,
) -> Vec<Instruction> {
    let source = associated_token_address(from, member_mint);
    let destination = associated_token_address(to, member_mint);
    vec![
        ix::create_associated_token_account_idempotent(payer, to, member_mint),
        ix::thaw_account(&source, member_mint, authority),
        ix::transfer_checked(&source, member_mint, &destination, authority, 1, 0),
        ix::freeze_account(&destination, member_mint, authority),
    ]
}

fn push_tlv(out: &mut Vec<u8>, ty: u16, value: &[&[u8]]) {
    let len: usize = value.iter().map(|v| v.len()).sum();
    out.extend_from_slice(&ty.to_le_bytes());
    out.extend_from_slice(&u16::try_from(len).unwrap_or(u16::MAX).to_le_bytes());
    for v in value {
        out.extend_from_slice(v);
    }
}

/// The exact 450 bytes of an SGT mint issued by `authority` into `group` with
/// `member_number`, as Token-2022 lays them out.
pub fn sgt_mint_bytes(
    mint_address: &Address,
    group: &Address,
    authority: &Address,
    member_number: u64,
) -> Vec<u8> {
    let mut out = vec![0u8; TLV_START];
    out[mint::MINT_AUTHORITY_TAG..mint::MINT_AUTHORITY].copy_from_slice(&coption::SOME);
    out[mint::MINT_AUTHORITY..mint::SUPPLY].copy_from_slice(authority.as_ref());
    out[mint::SUPPLY..mint::DECIMALS].copy_from_slice(&1u64.to_le_bytes());
    out[mint::DECIMALS] = 0;
    out[mint::IS_INITIALIZED] = 1;
    out[mint::FREEZE_AUTHORITY_TAG..mint::FREEZE_AUTHORITY].copy_from_slice(&coption::SOME);
    out[mint::FREEZE_AUTHORITY..mint::FREEZE_AUTHORITY + 32].copy_from_slice(authority.as_ref());
    out[ACCOUNT_BASE_LEN] = account_type::MINT;
    let (a, g, m) = (authority.as_ref(), group.as_ref(), mint_address.as_ref());
    push_tlv(&mut out, extension_type::METADATA_POINTER, &[a, g]);
    push_tlv(&mut out, extension_type::PERMANENT_DELEGATE, &[a]);
    push_tlv(&mut out, extension_type::MINT_CLOSE_AUTHORITY, &[a]);
    push_tlv(&mut out, extension_type::GROUP_MEMBER_POINTER, &[a, m]);
    push_tlv(
        &mut out,
        extension_type::TOKEN_GROUP_MEMBER,
        &[m, g, &member_number.to_le_bytes()],
    );
    debug_assert_eq!(out.len(), SGT_MINT_LEN);
    out
}

/// The exact 170 bytes of an SGT holder's ATA (ImmutableOwner extension).
/// Real holdings are `frozen`.
pub fn sgt_token_account_bytes(mint_address: &Address, holder: &Address, frozen: bool) -> Vec<u8> {
    let mut out = vec![0u8; TLV_START];
    out[account::MINT..account::OWNER].copy_from_slice(mint_address.as_ref());
    out[account::OWNER..account::AMOUNT].copy_from_slice(holder.as_ref());
    out[account::AMOUNT..account::DELEGATE_TAG].copy_from_slice(&1u64.to_le_bytes());
    out[account::STATE] = if frozen {
        account_state::FROZEN
    } else {
        account_state::INITIALIZED
    };
    out[ACCOUNT_BASE_LEN] = account_type::ACCOUNT;
    push_tlv(&mut out, extension_type::IMMUTABLE_OWNER, &[]);
    debug_assert_eq!(out.len(), SGT_TOKEN_ACCOUNT_LEN);
    out
}
