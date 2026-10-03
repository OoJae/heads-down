//! v1.2 Bury auction on the live-ORE fork: forfeited SKR becomes a pooled
//! lot sold in a no-oracle Dutch auction; the buyer's ORE goes through ORE's
//! real permissionless `bury` (90% burned, 10% to ORE's stake program via the real
//! ORE stake program), signed by the BuryVault PDA, and is checked after
//! the CPI before the SKR is handed over.

use hd::{error::HdError, events as ev, skr, state::break_reason};
use heads_down_tests::*;

/// A real lot: a Focus Bond of `amount` SKR locked, broken (wallet BREAK
/// manual), sealed and forfeited. Returns the bond address.
fn forfeit_lot(env: &mut Env, seed: u8, amount: u64) -> Address {
    forfeit_lot_events(env, seed, amount).0
}

/// [`forfeit_lot`], also returning the events of the forfeit.
fn forfeit_lot_events(env: &mut Env, seed: u8, amount: u64) -> (Address, Vec<Event>) {
    let u = User::new(env, seed);
    env.onboard_standard(&u);
    env.fund_skr(&u.pubkey(), amount);
    let w = u.wallet.insecure_clone();
    let bond = bond_pda(&u.rig, 1);
    ok(env.send_as(
        &w,
        &[
            ix_create_ata(&w.pubkey(), &bond, &SKR_MINT),
            ix_lock_focus_bond(&w.pubkey(), 1, amount),
        ],
        &[],
    ));
    ok(env.send_as(
        &w,
        &[
            ix_break_wallet(&w.pubkey(), break_reason::MANUAL),
            ix_end_shift(&w.pubkey(), &u.rig, 1),
        ],
        &[],
    ));
    let meta = ok(env.send(&[ix_forfeit_focus_bond(&w.pubkey(), 1)], &[]));
    (bond, events(&meta.logs))
}

fn buyer(env: &mut Env, ore: u64) -> Keypair {
    let k = Keypair::new();
    env.svm.airdrop(&k.pubkey(), SOL).unwrap();
    env.fund_ore(&k.pubkey(), ore);
    env.fund_skr(&k.pubkey(), 0);
    k
}

fn buy(env: &mut Env, b: &Keypair, skr_amount: u64, max_ore: u64) -> TxResult {
    let k = b.insecure_clone();
    env.send(&[ix_bury_auction_buy(&k.pubkey(), skr_amount, max_ore)], &[&k])
}

fn set_slot(env: &mut Env, slot: u64) {
    let now = env.now;
    env.set_clock(slot, now);
}

#[test]
fn a_forfeit_becomes_a_lot_that_sells_for_ore_that_ore_buries() {
    let mut env = Env::new();
    env.init_bury_vault();
    let lot = 1_000 * ONE_SKR;
    forfeit_lot(&mut env, 1, lot);
    let s0 = env.slot;
    let v = env.bury_vault();
    assert_eq!(v.lot_skr.get(), lot);
    assert_eq!(v.auction_start_slot.get(), s0);
    assert_eq!(v.start_price.get(), skr::INITIAL_START_PRICE);
    assert_eq!(
        (v.floor_price.get(), v.window_slots.get()),
        (skr::FLOOR_PRICE, skr::WINDOW_SLOTS)
    );

    // Half-way through the window the price is half-way to the floor.
    set_slot(&mut env, s0 + skr::WINDOW_SLOTS / 2);
    let price = skr::auction_price(
        skr::INITIAL_START_PRICE,
        skr::FLOOR_PRICE,
        s0,
        skr::WINDOW_SLOTS,
        env.slot,
    );
    assert_eq!(
        price,
        skr::INITIAL_START_PRICE - (skr::INITIAL_START_PRICE - skr::FLOOR_PRICE) / 2
    );
    let first = 400 * ONE_SKR;
    let cost = skr::purchase_cost(first, price).unwrap();
    assert_eq!(cost, 400 * price);
    let b1 = buyer(&mut env, ONE_ORE);
    let supply = env.mint_supply(&ORE_MINT);
    let stake_ore = env.token_balance(&stake_treasury_ore());
    let treasury = env.token_balance(&treasury_ore());
    let meta = ok(buy(&mut env, &b1, first, cost));
    println!("bury_auction_buy: {} CU", meta.compute_units_consumed);
    let (burned, shared) = skr::bury_split(cost);
    assert_eq!(
        events(&meta.logs),
        vec![Event::BuryAuctionSold {
            buyer: b1.pubkey(),
            skr_amount: first,
            price,
            ore_paid: cost,
            ore_burned: burned,
            ore_shared: shared,
            lot_remaining: lot - first,
        }]
    );
    // ORE's own accounting: 90% burned (supply), 10% to ORE's stake program, the
    // Treasury's ORE ATA unchanged, and nothing left in the vault.
    assert_eq!(env.mint_supply(&ORE_MINT), supply - burned);
    assert_eq!(env.token_balance(&stake_treasury_ore()), stake_ore + shared);
    assert_eq!(env.token_balance(&treasury_ore()), treasury);
    assert_eq!(env.token_balance(&ata(&BURY, &ORE_MINT)), 0);
    assert_eq!(env.token_balance(&ata(&b1.pubkey(), &ORE_MINT)), ONE_ORE - cost);
    assert_eq!(env.token_balance(&ata(&b1.pubkey(), &SKR_MINT)), first);
    assert_eq!(env.token_balance(&ata(&BURY, &SKR_MINT)), lot - first);
    // ORE logged its own BuryEvent (through its Log instruction).
    assert!(meta.logs.iter().any(|l| l.contains("Buried")));

    // Later, cheaper: the rest of the lot.
    set_slot(&mut env, s0 + skr::WINDOW_SLOTS * 3 / 4);
    let later = skr::auction_price(
        skr::INITIAL_START_PRICE,
        skr::FLOOR_PRICE,
        s0,
        skr::WINDOW_SLOTS,
        env.slot,
    );
    assert!(later < price);
    let rest = lot - first;
    let cost2 = skr::purchase_cost(rest, later).unwrap();
    let b2 = buyer(&mut env, ONE_ORE);
    ok(buy(&mut env, &b2, rest, u64::MAX));
    let v = env.bury_vault();
    assert_eq!(v.lot_skr.get(), 0);
    assert_eq!(v.last_clear_price.get(), later);
    assert_eq!(v.total_skr_in.get(), lot);
    assert_eq!(v.total_skr_sold.get(), lot);
    assert_eq!(v.total_ore_paid.get(), cost + cost2);
    let (burned2, shared2) = skr::bury_split(cost2);
    assert_eq!(v.total_ore_burned.get(), burned + burned2);
    assert_eq!(v.total_ore_shared.get(), shared + shared2);
    assert_eq!((v.sales.get(), v.lots.get()), (2, 1));
    // Empty now.
    let b3 = buyer(&mut env, ONE_ORE);
    assert_hd(&buy(&mut env, &b3, 1, u64::MAX), 0, HdError::AuctionEmpty);

    // A new lot restarts the auction at 4x the last clearing price.
    let bond = forfeit_lot(&mut env, 2, 50 * ONE_SKR);
    let v = env.bury_vault();
    assert_eq!(v.start_price.get(), 4 * later);
    assert_eq!(v.auction_start_slot.get(), env.slot);
    assert_eq!(v.lot_skr.get(), 50 * ONE_SKR);
    assert_eq!(v.lots.get(), 2);
    let _ = (bond, ev::LOT_FROM_BOND);
}

/// Three rules that keep the no-oracle auction from being steered for the
/// price of dust: a sale below `MIN_ANCHOR_SKR` does not move the price the
/// next lot starts from; a lot never restarts below half the previous start;
/// and an arrival smaller than what is on offer joins the running auction
/// instead of putting its price and clock back to the start.
#[test]
fn dust_cannot_anchor_the_next_lot_or_restart_a_running_auction() {
    let mut env = Env::new();
    env.init_bury_vault();
    forfeit_lot(&mut env, 1, 100 * ONE_SKR);
    let s0 = env.slot;
    // The price has reached the floor. Dust sales there set no anchor.
    set_slot(&mut env, s0 + 2 * skr::WINDOW_SLOTS);
    let b = buyer(&mut env, ONE_ORE);
    ok(buy(&mut env, &b, 1, 1));
    assert_eq!(env.bury_vault().last_clear_price.get(), 0);
    ok(buy(&mut env, &b, skr::MIN_ANCHOR_SKR - 1, u64::MAX));
    assert_eq!(env.bury_vault().last_clear_price.get(), 0);

    // 5 SKR arriving while 90 SKR is on offer joins the running auction.
    let before = env.bury_vault();
    assert_eq!(before.lot_skr.get(), 90 * ONE_SKR);
    let (bond, evs) = forfeit_lot_events(&mut env, 2, 5 * ONE_SKR);
    let v = env.bury_vault();
    assert_eq!(v.lot_skr.get(), 95 * ONE_SKR);
    assert_eq!(v.lots.get(), 2);
    assert_eq!(
        (v.start_price.get(), v.auction_start_slot.get()),
        (skr::INITIAL_START_PRICE, s0),
        "neither the price nor the clock restarted"
    );
    // The event repeats the running auction's start, not this slot.
    assert!(env.slot > s0);
    assert!(evs.contains(&Event::BuryLotAdded {
        source: bond,
        amount: 5 * ONE_SKR,
        lot_skr: 95 * ONE_SKR,
        start_price: skr::INITIAL_START_PRICE,
        start_slot: s0,
        source_kind: ev::LOT_FROM_BOND,
    }));

    // The rest clears at the floor. That is a real sale, so it is the anchor...
    ok(buy(&mut env, &b, 95 * ONE_SKR, u64::MAX));
    assert_eq!(env.bury_vault().last_clear_price.get(), skr::FLOOR_PRICE);
    // ...yet the next lot starts at half the previous start, not at 4 x floor.
    forfeit_lot(&mut env, 3, 50 * ONE_SKR);
    let v = env.bury_vault();
    assert_eq!(v.start_price.get(), skr::INITIAL_START_PRICE / 2);
    assert_eq!(v.auction_start_slot.get(), env.slot);

    // An arrival that at least doubles the lot does restart it.
    let later = env.slot + 1_000;
    set_slot(&mut env, later);
    forfeit_lot(&mut env, 4, 50 * ONE_SKR);
    let v = env.bury_vault();
    assert_eq!(v.lot_skr.get(), 100 * ONE_SKR);
    assert_eq!(v.auction_start_slot.get(), later);
    assert_eq!(v.start_price.get(), skr::INITIAL_START_PRICE / 4);
}

#[test]
fn the_price_bottoms_out_at_the_floor_and_every_unit_costs_at_least_one_atom() {
    let mut env = Env::new();
    env.init_bury_vault();
    forfeit_lot(&mut env, 1, 10 * ONE_SKR);
    let s0 = env.slot;
    set_slot(&mut env, s0 + 10 * skr::WINDOW_SLOTS);
    let b = buyer(&mut env, ONE_ORE);
    let supply = env.mint_supply(&ORE_MINT);
    // One base unit at the floor: ceil(10_000 / 10^6) = 1 atom, all burned.
    let meta = ok(buy(&mut env, &b, 1, 1));
    assert!(events(&meta.logs).contains(&Event::BuryAuctionSold {
        buyer: b.pubkey(),
        skr_amount: 1,
        price: skr::FLOOR_PRICE,
        ore_paid: 1,
        ore_burned: 1,
        ore_shared: 0,
        lot_remaining: 10 * ONE_SKR - 1,
    }));
    assert_eq!(env.mint_supply(&ORE_MINT), supply - 1);
    // The whole remaining lot at the floor.
    let rest = 10 * ONE_SKR - 1;
    let cost = skr::purchase_cost(rest, skr::FLOOR_PRICE).unwrap();
    assert_eq!(cost, 100_000); // 10 SKR x 1e-7 ORE, rounded up
    ok(buy(&mut env, &b, rest, cost));
}

#[test]
fn slippage_and_amount_guards() {
    let mut env = Env::new();
    env.init_bury_vault();
    forfeit_lot(&mut env, 1, 100 * ONE_SKR);
    let b = buyer(&mut env, ONE_ORE);
    let cost = skr::purchase_cost(10 * ONE_SKR, skr::INITIAL_START_PRICE).unwrap();
    assert_hd(&buy(&mut env, &b, 10 * ONE_SKR, cost - 1), 0, HdError::PriceAboveMax);
    assert_hd(&buy(&mut env, &b, 0, u64::MAX), 0, HdError::AmountOutOfRange);
    assert_hd(
        &buy(&mut env, &b, 100 * ONE_SKR + 1, u64::MAX),
        0,
        HdError::AuctionEmpty,
    );
    // Not enough ORE in the buyer's account: SPL Token refuses the payment.
    let poor = buyer(&mut env, cost - 1);
    let res = buy(&mut env, &poor, 10 * ONE_SKR, cost);
    assert!(res.is_err());
    assert_eq!(env.token_balance(&ata(&poor.pubkey(), &ORE_MINT)), cost - 1);
    ok(buy(&mut env, &b, 10 * ONE_SKR, cost));
}

#[test]
fn every_account_of_a_purchase_is_pinned() {
    let mut env = Env::new();
    env.init_bury_vault();
    forfeit_lot(&mut env, 1, 100 * ONE_SKR);
    let b = buyer(&mut env, ONE_ORE);
    let k = b.insecure_clone();
    let amount = ONE_SKR;
    let base = || ix_bury_auction_buy(&k.pubkey(), amount, u64::MAX);
    let send = |env: &mut Env, ix: Instruction| env.send(&[ix], &[&k]);

    // The buyer signs.
    let mut ix = base();
    ix.accounts[0].is_signer = false;
    assert_ix_err(&env.send(&[ix], &[]), 0, InstructionError::MissingRequiredSignature);
    // A Token-2022 look-alike ORE account, or one for another mint.
    let src = ata(&b.pubkey(), &ORE_MINT);
    let good = env.account(&src);
    let mut fake = good.clone();
    fake.owner = token_2022_id();
    env.svm.set_account(src, fake).unwrap();
    assert_hd(&send(&mut env, base()), 0, HdError::InvalidTokenAccount);
    env.set_token_account(&src, &SKR_MINT, &b.pubkey(), ONE_ORE);
    assert_hd(&send(&mut env, base()), 0, HdError::InvalidTokenAccount);
    env.svm.set_account(src, good).unwrap();
    // The SKR must go to the buyer's own account.
    let other = Keypair::new().pubkey();
    env.fund_skr(&other, 0);
    let mut ix = base();
    ix.accounts[2].pubkey = ata(&other, &SKR_MINT);
    assert_hd(&send(&mut env, ix), 0, HdError::InvalidTokenAccount);
    // The vault ATAs are the BuryVault's.
    let mut ix = base();
    ix.accounts[4].pubkey = src;
    assert_hd(&send(&mut env, ix), 0, HdError::InvalidTokenAccount);
    let mut ix = base();
    ix.accounts[5].pubkey = ata(&b.pubkey(), &SKR_MINT);
    assert_hd(&send(&mut env, ix), 0, HdError::InvalidTokenAccount);
    // The BuryVault itself.
    let mut ix = base();
    ix.accounts[3].pubkey = CONFIG;
    assert_ix_err(&send(&mut env, ix), 0, InstructionError::InvalidSeeds);
    // ORE program, Board and Treasury are pinned (arbitrary CPI).
    for (i, fake) in [(14usize, SYSTEM), (6, TREASURY), (8, BOARD)] {
        let mut ix = base();
        ix.accounts[i].pubkey = fake;
        assert_hd(&send(&mut env, ix), 0, HdError::InvalidOreAccount);
    }
    // The token program slot must be SPL Token.
    let mut ix = base();
    ix.accounts[13].pubkey = token_2022_id();
    assert_hd(&send(&mut env, ix), 0, HdError::InvalidTokenAccount);
    // The ORE mint slot must be the ORE mint (read for the burn check).
    let mut ix = base();
    ix.accounts[7].pubkey = SKR_MINT;
    assert_hd(&send(&mut env, ix), 0, HdError::InvalidTokenAccount);
    // Nothing moved in any of those attempts.
    assert_eq!(env.token_balance(&src), ONE_ORE);
    assert_eq!(env.bury_vault().lot_skr.get(), 100 * ONE_SKR);
    ok(send(&mut env, base()));
}

#[test]
fn an_ore_that_skips_the_burn_cannot_buy_the_lot() {
    let mut env = Env::new();
    env.init_bury_vault();
    forfeit_lot(&mut env, 1, 100 * ONE_SKR);
    let b = buyer(&mut env, ONE_ORE);
    // TEST ONLY: a misbehaving ORE at ORE's address.
    let mock = std::fs::read(root().join("target/deploy-mock/mock_ore.so"))
        .expect("target/deploy-mock/mock_ore.so: run scripts/build.sh");
    env.svm.add_program(ORE, &mock).unwrap();
    for mode in [2u8, 4] {
        let mut board = env.account(&BOARD);
        board.data[1] = mode;
        env.svm.set_account(BOARD, board).unwrap();
        let res = buy(&mut env, &b, ONE_SKR, u64::MAX);
        assert_hd(&res, 0, HdError::BuryMismatch);
    }
    // Everything reverted: the buyer kept its ORE, the lot its SKR.
    assert_eq!(env.token_balance(&ata(&b.pubkey(), &ORE_MINT)), ONE_ORE);
    assert_eq!(env.token_balance(&ata(&BURY, &SKR_MINT)), 100 * ONE_SKR);
    assert_eq!(env.bury_vault().sales.get(), 0);
}

#[test]
fn the_bury_vault_is_a_permissionless_canonical_singleton() {
    let mut env = Env::new();
    let c = env.cranker.pubkey();
    // Only ["bury"].
    let mut ix = ix_init_bury_vault(&c);
    ix.accounts[1].pubkey = Keypair::new().pubkey();
    assert_ix_err(&env.send(&[ix], &[]), 0, InstructionError::InvalidSeeds);
    // The payer signs.
    let payer = Keypair::new();
    env.svm.airdrop(&payer.pubkey(), SOL).unwrap();
    let mut ix = ix_init_bury_vault(&payer.pubkey());
    ix.accounts[0].is_signer = false;
    assert_ix_err(&env.send(&[ix], &[]), 0, InstructionError::MissingRequiredSignature);
    ok(env.send(&[ix_init_bury_vault(&c)], &[]));
    let v = env.bury_vault();
    assert_eq!(v.skr_vault, ata(&BURY, &SKR_MINT).to_bytes());
    assert_eq!(v.ore_vault, ata(&BURY, &ORE_MINT).to_bytes());
    // Init-only.
    let p = payer.insecure_clone();
    assert_ix_err(
        &env.send_as(&p, &[ix_init_bury_vault(&p.pubkey())], &[]),
        0,
        InstructionError::AccountAlreadyInitialized,
    );
}
