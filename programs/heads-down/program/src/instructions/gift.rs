//! Gift a Rig (v1.2): tag 23 `create_gift`, 24 `claim_gift`, 25
//! `refund_gift`; `INTERFACE.md` §11.4 is the contract.
//!
//! The program escrows **lamports** only. A sender who pays in SKR puts a
//! Jupiter SKR→SOL swap in the same transaction as a separate, sender-signed
//! top-level instruction; heads_down never CPIs Jupiter and never holds SKR
//! for a gift. The escrow names a recipient **wallet** (that wallet claims)
//! or a recipient **SGT mint** (whoever holds that SGT when claiming claims,
//! verified in-program with `sgt-verify`). A claim pays the claimer's wallet,
//! so the claimer's own transaction can continue with ORE `automate` and
//! `register_rig` and arrive as a funded rig. Unclaimed gifts refund to the
//! sender from `created_ts + 30 days`; claims are accepted only before that.

use pinocchio::{error::ProgramError, instruction::seeds, AccountView, ProgramResult};
use pinocchio_system::instructions::Transfer;

use crate::{
    error::HdError,
    events::{self, log_data},
    pda,
    skr::{GIFT_EXPIRY_SECS, MAX_GIFT_LAMPORTS},
    state::{self, gift_kind, GiftEscrow, Header, U64},
    util::{clock, require_signer, Reader},
    GIFT_SEED, ID,
};

/// `create_gift` accounts:
/// 0. `[signer, writable]` sender (pays the rent and the gift)
/// 1. `[writable]` GiftEscrow PDA `["gift", sender, nonce u64 LE]`
/// 2. `[]` System program
///
/// Data: `nonce u64 | recipient_kind u8 (0 wallet, 1 SGT mint) |
/// recipient [32] | lamports u64` (49 bytes after the tag).
pub fn process_create(accounts: &mut [AccountView], data: &[u8]) -> ProgramResult {
    let [sender, gift, system_program, ..] = accounts else {
        return Err(ProgramError::NotEnoughAccountKeys);
    };
    let mut r = Reader::new(data);
    let nonce = r.u64()?;
    let kind = r.u8()?;
    let recipient = r.array::<32>()?;
    let lamports = r.u64()?;
    r.finish()?;
    require_signer(sender)?;
    if (kind != gift_kind::WALLET && kind != gift_kind::SGT_MINT) || recipient == [0u8; 32] {
        return Err(HdError::InvalidInstruction.into());
    }
    if lamports == 0 || lamports > MAX_GIFT_LAMPORTS {
        return Err(HdError::AmountOutOfRange.into());
    }
    let n = nonce.to_le_bytes();
    let (gift_pda, bump) = pda::find(&[GIFT_SEED, sender.address().as_ref(), &n], &ID);
    if gift.address() != &gift_pda {
        return Err(ProgramError::InvalidSeeds);
    }
    let bump_seed = [bump];
    let signer_seeds = seeds!(GIFT_SEED, sender.address().as_ref(), &n, &bump_seed);
    pda::create_pda_account(
        sender,
        gift,
        system_program,
        core::mem::size_of::<GiftEscrow>(),
        &signer_seeds,
    )?;
    Transfer {
        from: sender,
        to: gift,
        lamports,
    }
    .invoke()?;
    let now = clock()?.unix_timestamp;
    let expiry = now
        .checked_add(GIFT_EXPIRY_SECS)
        .ok_or(HdError::MathOverflow)?;
    {
        let mut g = state::load_uninit_mut::<GiftEscrow>(gift)?;
        g.header = Header::new(state::tag::GIFT_ESCROW, bump);
        g.sender = *sender.address().as_array();
        g.recipient = recipient;
        g.nonce = U64::new(nonce);
        g.lamports = U64::new(lamports);
        g.created_ts.set(now);
        g.expiry_ts.set(expiry);
        g.recipient_kind = kind;
    }
    log_data(&events::gift_created_bytes(
        &gift_pda,
        sender.address(),
        &recipient,
        lamports,
        expiry,
        kind,
    ));
    Ok(())
}

/// `claim_gift` accounts:
/// 0. `[signer, writable]` claimer (receives the lamports)
/// 1. `[writable]` GiftEscrow (closed)
/// 2. `[writable]` sender (receives the escrow rent; `== gift.sender`)
/// 3. `[]` SGT token account (Token-2022), 4. `[]` SGT mint: only for an
///    SGT-mint gift
///
/// Data: empty. Before `expiry_ts` only.
pub fn process_claim(accounts: &mut [AccountView], data: &[u8]) -> ProgramResult {
    let [claimer, gift, sender, rest @ ..] = accounts else {
        return Err(ProgramError::NotEnoughAccountKeys);
    };
    Reader::new(data).finish()?;
    require_signer(claimer)?;
    let now = clock()?.unix_timestamp;
    let (kind, recipient, lamports, stored_sender) = {
        let g = state::load::<GiftEscrow>(gift)?;
        if now >= g.expiry_ts.get() {
            return Err(HdError::GiftExpiry.into());
        }
        (g.recipient_kind, g.recipient, g.lamports.get(), g.sender)
    };
    if sender.address().as_array() != &stored_sender {
        return Err(HdError::Unauthorized.into());
    }
    match kind {
        gift_kind::WALLET => {
            if claimer.address().as_array() != &recipient {
                return Err(HdError::GiftNotClaimable.into());
            }
        }
        gift_kind::SGT_MINT => {
            let [sgt_account, sgt_mint, ..] = rest else {
                return Err(ProgramError::NotEnoughAccountKeys);
            };
            // The claimer must hold that SGT right now.
            let info = sgt_verify::verify_sgt(sgt_account, sgt_mint, claimer.address())?;
            if info.mint.as_array() != &recipient {
                return Err(HdError::GiftNotClaimable.into());
            }
        }
        _ => return Err(HdError::InvalidAccountTag.into()),
    }
    if !claimer.is_writable() {
        return Err(ProgramError::InvalidAccountData);
    }
    // The escrow is heads_down-owned: move the gift, then close it (the rent,
    // and anything else it holds, back to the sender).
    let left = gift
        .lamports()
        .checked_sub(lamports)
        .ok_or(HdError::MathOverflow)?;
    let credited = claimer
        .lamports()
        .checked_add(lamports)
        .ok_or(HdError::MathOverflow)?;
    gift.set_lamports(left);
    claimer.set_lamports(credited);
    let gift_address = *gift.address();
    pda::close_account(gift, sender)?;
    log_data(&events::gift_claimed_bytes(
        &gift_address,
        claimer.address(),
        lamports,
        kind,
    ));
    Ok(())
}

/// `refund_gift` accounts:
/// 0. `[writable]` GiftEscrow (closed)
/// 1. `[writable]` sender (`== gift.sender`; receives everything)
///
/// Data: empty. Permissionless from `expiry_ts`: the lamports go only to the
/// stored sender.
pub fn process_refund(accounts: &mut [AccountView], data: &[u8]) -> ProgramResult {
    let [gift, sender, ..] = accounts else {
        return Err(ProgramError::NotEnoughAccountKeys);
    };
    Reader::new(data).finish()?;
    let now = clock()?.unix_timestamp;
    let lamports = {
        let g = state::load::<GiftEscrow>(gift)?;
        if now < g.expiry_ts.get() {
            return Err(HdError::GiftExpiry.into());
        }
        if sender.address().as_array() != &g.sender {
            return Err(HdError::Unauthorized.into());
        }
        g.lamports.get()
    };
    let gift_address = *gift.address();
    pda::close_account(gift, sender)?;
    log_data(&events::gift_refunded_bytes(
        &gift_address,
        sender.address(),
        lamports,
    ));
    Ok(())
}
