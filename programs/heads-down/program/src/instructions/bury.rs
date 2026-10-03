//! Bury auction (v1.2, SKR): tag 26 `init_bury_vault`, tag 27
//! `bury_auction_buy`, plus the lot deposit shared by `settle_stack` and
//! `forfeit_focus_bond`. `INTERFACE.md` §11.5 is the contract.
//!
//! SKR forfeits sit in one pooled lot in the BuryVault's SKR ATA. The price,
//! in ORE atoms per whole SKR, falls linearly with the slot from a start
//! price to a floor over a fixed window, and restarts whenever a new lot
//! arrives (start = 4 x the last clearing price, or the initial price before
//! any sale). No oracle is read. A buyer pays ORE into the BuryVault's ORE
//! ATA; the program immediately CPIs ORE's permissionless `bury` for exactly
//! that amount, signed by the BuryVault PDA, checks that the vault lost
//! exactly the payment and that the ORE supply fell by the burned 90%, and
//! only then sends the SKR to the buyer.

use pinocchio::{
    cpi::Signer, error::ProgramError, instruction::seeds, AccountView, Address, ProgramResult,
};

use crate::{
    error::HdError,
    events::{self, log_data},
    ore, pda,
    skr::{self, auction_price, bury_split, lot_restarts, next_start_price, purchase_cost},
    state::{self, BuryVault, Header, U64},
    token::{self, SKR_MINT},
    util::{clock, require_signer, Reader},
    BURY_BUMP, BURY_ID, BURY_SEED,
};

/// Load the BuryVault (canonical address, owner, tag) and return its SKR
/// vault address after checking `skr_vault` is it.
pub fn check_accounts(
    bury_vault: &AccountView,
    skr_vault: &AccountView,
) -> Result<[u8; 32], ProgramError> {
    if bury_vault.address() != &BURY_ID {
        return Err(ProgramError::InvalidSeeds);
    }
    let expected = state::load::<BuryVault>(bury_vault)?.skr_vault;
    if skr_vault.address().as_array() != &expected {
        return Err(HdError::InvalidTokenAccount.into());
    }
    Ok(expected)
}

/// Record `amount` SKR that just arrived in the lot from `source` (a table
/// or a bond), and restart the auction if the lot was empty or the arrival at
/// least doubles it. Emits `BuryLotAdded` with the auction's start price and
/// start slot after the deposit (unchanged when it joined a running auction).
pub fn add_lot(
    bury_vault: &mut AccountView,
    amount: u64,
    source: &Address,
    source_kind: u8,
) -> ProgramResult {
    let slot = clock()?.slot;
    let (lot, start_price, start_slot) = {
        let mut v = state::load_mut::<BuryVault>(bury_vault)?;
        let lot_before = v.lot_skr.get();
        let lot = lot_before
            .checked_add(amount)
            .ok_or(HdError::MathOverflow)?;
        v.lot_skr.set(lot);
        let total = v
            .total_skr_in
            .get()
            .checked_add(amount)
            .ok_or(HdError::MathOverflow)?;
        v.total_skr_in.set(total);
        let lots = v.lots.get().checked_add(1).ok_or(HdError::MathOverflow)?;
        v.lots.set(lots);
        // Restart only for an empty auction or an arrival that at least
        // doubles the lot; smaller arrivals join the running auction.
        let start_price = if lot_restarts(lot_before, amount) {
            let p = next_start_price(v.last_clear_price.get(), v.start_price.get());
            v.start_price.set(p);
            v.auction_start_slot.set(slot);
            p
        } else {
            v.start_price.get()
        };
        (lot, start_price, v.auction_start_slot.get())
    };
    log_data(&events::bury_lot_added_bytes(
        source,
        amount,
        lot,
        start_price,
        start_slot,
        source_kind,
    ));
    Ok(())
}

// ---- 26 init_bury_vault -----------------------------------------------------------------

/// `init_bury_vault` accounts:
/// 0. `[signer, writable]` payer (rent)
/// 1. `[writable]` BuryVault PDA `["bury"]`
/// 2. `[]` System program
///
/// Data: empty. Permissionless and init-only; there is no admin. The two
/// ATAs (`ATA(BuryVault, SKR)`, `ATA(BuryVault, ORE)`) are derived and
/// stored here; anyone may create them with the ATA program.
pub fn process_init(accounts: &mut [AccountView], data: &[u8]) -> ProgramResult {
    let [payer, bury_vault, system_program, ..] = accounts else {
        return Err(ProgramError::NotEnoughAccountKeys);
    };
    Reader::new(data).finish()?;
    require_signer(payer)?;
    if bury_vault.address() != &BURY_ID {
        return Err(ProgramError::InvalidSeeds);
    }
    let bump = [BURY_BUMP];
    let signer_seeds = seeds!(BURY_SEED, &bump);
    pda::create_pda_account(
        payer,
        bury_vault,
        system_program,
        core::mem::size_of::<BuryVault>(),
        &signer_seeds,
    )?;
    let slot = clock()?.slot;
    let skr_vault = *token::ata(&BURY_ID, &SKR_MINT).as_array();
    let ore_vault = *token::ata(&BURY_ID, &ore::MINT_ADDRESS).as_array();
    let mut v = state::load_uninit_mut::<BuryVault>(bury_vault)?;
    v.header = Header::new(state::tag::BURY_VAULT, BURY_BUMP);
    v.skr_vault = skr_vault;
    v.ore_vault = ore_vault;
    v.auction_start_slot = U64::new(slot);
    v.start_price = U64::new(skr::INITIAL_START_PRICE);
    v.floor_price = U64::new(skr::FLOOR_PRICE);
    v.window_slots = U64::new(skr::WINDOW_SLOTS);
    Ok(())
}

// ---- 27 bury_auction_buy ---------------------------------------------------------------

/// `bury_auction_buy` accounts:
/// 0. `[signer]` buyer
/// 1. `[writable]` buyer's ORE token account (source; owner field == buyer)
/// 2. `[writable]` buyer's SKR token account (destination; owner field == buyer)
/// 3. `[writable]` BuryVault PDA
/// 4. `[writable]` BuryVault ORE ATA (the `bury` sender)
/// 5. `[writable]` BuryVault SKR ATA (the lot)
/// 6. `[writable]` ORE Board
/// 7. `[writable]` ORE mint
/// 8. `[writable]` ORE Treasury
/// 9. `[writable]` ORE Treasury's ORE ATA
/// 10. `[writable]` ORE stake Treasury
/// 11. `[writable]` ORE stake Treasury's ORE ATA
/// 12. `[writable]` ORE stake Vesting
/// 13. `[]` SPL Token program
/// 14. `[]` ORE program
/// 15. `[]` ORE stake program
///
/// Data: `skr_amount u64 | max_ore u64` (16 bytes after the tag).
pub fn process_buy(accounts: &mut [AccountView], data: &[u8]) -> ProgramResult {
    let [buyer, buyer_ore, buyer_skr, bury_vault, vault_ore, vault_skr, board, ore_mint, treasury, treasury_ore, stake_treasury, stake_treasury_ore, stake_vesting, token_program, ore_program, stake_program, ..] =
        accounts
    else {
        return Err(ProgramError::NotEnoughAccountKeys);
    };
    let mut r = Reader::new(data);
    let skr_amount = r.u64()?;
    let max_ore = r.u64()?;
    r.finish()?;
    require_signer(buyer)?;
    token::check_token_program(token_program)?;
    if ore_program.address() != &ore::ORE_PROGRAM_ID
        || board.address() != &ore::BOARD_ADDRESS
        || treasury.address() != &ore::TREASURY_ADDRESS
    {
        return Err(HdError::InvalidOreAccount.into());
    }
    if bury_vault.address() != &BURY_ID {
        return Err(ProgramError::InvalidSeeds);
    }
    let slot = clock()?.slot;
    let (skr_vault_address, ore_vault_address, lot, price) = {
        let v = state::load::<BuryVault>(bury_vault)?;
        (
            v.skr_vault,
            v.ore_vault,
            v.lot_skr.get(),
            auction_price(
                v.start_price.get(),
                v.floor_price.get(),
                v.auction_start_slot.get(),
                v.window_slots.get(),
                slot,
            ),
        )
    };
    if skr_amount == 0 {
        return Err(HdError::AmountOutOfRange.into());
    }
    if skr_amount > lot {
        return Err(HdError::AuctionEmpty.into());
    }
    let cost = purchase_cost(skr_amount, price)?;
    if cost == 0 {
        return Err(HdError::AmountOutOfRange.into());
    }
    if cost > max_ore {
        return Err(HdError::PriceAboveMax.into());
    }

    token::check_user_account(buyer_ore, &ore::MINT_ADDRESS, buyer.address().as_array())?;
    token::check_user_account(buyer_skr, &SKR_MINT, buyer.address().as_array())?;
    let skr_before = token::check_vault(vault_skr, &skr_vault_address, &SKR_MINT, &BURY_ID)?.amount;
    if skr_before < skr_amount {
        return Err(HdError::AuctionEmpty.into());
    }
    let ore_before =
        token::check_vault(vault_ore, &ore_vault_address, &ore::MINT_ADDRESS, &BURY_ID)?.amount;

    // 1. The buyer pays the ORE into the vault's ORE ATA.
    token::transfer(buyer_ore, vault_ore, buyer, cost, &[])?;
    let ore_paid_in = ore_before.checked_add(cost).ok_or(HdError::MathOverflow)?;
    if token::balance(vault_ore)? != ore_paid_in {
        return Err(HdError::BuryMismatch.into());
    }
    let supply_before = token::mint_supply(ore_mint, &ore::MINT_ADDRESS)?;

    // 2. ORE `bury` for exactly that amount, signed by the BuryVault PDA.
    let bump = [BURY_BUMP];
    let signer_seeds = seeds!(BURY_SEED, &bump);
    ore::cpi_bury(
        &ore::BuryAccounts {
            signer: bury_vault,
            sender: vault_ore,
            board,
            mint: ore_mint,
            treasury,
            treasury_ore,
            stake_treasury,
            stake_treasury_ore,
            stake_vesting,
            token_program,
            ore_program,
            stake_program,
        },
        cost,
        Signer::from(&signer_seeds),
    )?;

    // 3. Re-read after the CPI: exactly `cost` left the vault, and the ORE
    //    supply fell by the 90% `bury` burns.
    if token::balance(vault_ore)? != ore_before {
        return Err(HdError::BuryMismatch.into());
    }
    let (burned, shared) = bury_split(cost);
    let supply_after = token::mint_supply(ore_mint, &ore::MINT_ADDRESS)?;
    if supply_before.checked_sub(supply_after) != Some(burned) {
        return Err(HdError::BuryMismatch.into());
    }

    // 4. Only now does the SKR leave the lot, to the buyer.
    let signer = [Signer::from(&signer_seeds)];
    token::transfer(vault_skr, buyer_skr, bury_vault, skr_amount, &signer)?;
    let skr_left = skr_before
        .checked_sub(skr_amount)
        .ok_or(HdError::MathOverflow)?;
    if token::balance(vault_skr)? != skr_left {
        return Err(HdError::InvalidTokenAccount.into());
    }

    let lot_remaining = {
        let mut v = state::load_mut::<BuryVault>(bury_vault)?;
        let lot = v
            .lot_skr
            .get()
            .checked_sub(skr_amount)
            .ok_or(HdError::MathOverflow)?;
        v.lot_skr.set(lot);
        // Only a meaningful sale moves the anchor the next lot restarts from.
        if skr_amount >= skr::MIN_ANCHOR_SKR {
            v.last_clear_price.set(price);
        }
        let add = |a: U64, b: u64| a.get().checked_add(b).ok_or(HdError::MathOverflow);
        let x = add(v.total_skr_sold, skr_amount)?;
        v.total_skr_sold.set(x);
        let x = add(v.total_ore_paid, cost)?;
        v.total_ore_paid.set(x);
        let x = add(v.total_ore_burned, burned)?;
        v.total_ore_burned.set(x);
        let x = add(v.total_ore_shared, shared)?;
        v.total_ore_shared.set(x);
        let x = add(v.sales, 1)?;
        v.sales.set(x);
        lot
    };
    log_data(&events::bury_auction_sold_bytes(
        buyer.address(),
        &events::BurySale {
            skr_amount,
            price,
            ore_paid: cost,
            ore_burned: burned,
            ore_shared: shared,
            lot_remaining,
        },
    ));
    Ok(())
}
