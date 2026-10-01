//! v1.2 Gift a Rig on the live-ORE fork: lamports escrowed for a wallet or
//! for an SGT mint (only its current holder claims, re-verified in program),
//! claimed in the same transaction as the recipient's ORE `automate` and
//! `register_rig`, or refunded to the sender from day 30. The SKR→SOL swap
//! is a separate, client-composed Jupiter instruction and is not part of
//! the program.

use hd::{error::HdError, skr, state::gift_kind};
use heads_down_tests::*;
use sgt_verify::SgtError;

const GIFT: u64 = SOL / 2;

fn create(env: &mut Env, sender: &Keypair, nonce: u64, kind: u8, to: &Address, lamports: u64) -> TxResult {
    let s = sender.insecure_clone();
    env.send_as(&s, &[ix_create_gift(&s.pubkey(), nonce, kind, to, lamports)], &[])
}

fn funded(env: &mut Env, lamports: u64) -> Keypair {
    let k = Keypair::new();
    env.svm.airdrop(&k.pubkey(), lamports).unwrap();
    k
}

#[test]
fn a_wallet_gift_is_claimed_into_a_live_rig_in_one_transaction() {
    let mut env = Env::new();
    let sender = funded(&mut env, 2 * SOL);
    let recipient = User::from_wallet(funded(&mut env, SOL / 20), 9);
    let gift = gift_pda(&sender.pubkey(), 1);
    let meta = ok(create(&mut env, &sender, 1, gift_kind::WALLET, &recipient.pubkey(), GIFT));
    let g = env.gift(&gift);
    assert_eq!(
        events(&meta.logs),
        vec![Event::GiftCreated {
            gift,
            sender: sender.pubkey(),
            recipient: recipient.pubkey(),
            lamports: GIFT,
            expiry_ts: T0 + skr::GIFT_EXPIRY_SECS,
            recipient_kind: gift_kind::WALLET,
        }]
    );
    assert_eq!((g.lamports.get(), g.expiry_ts.get()), (GIFT, T0 + skr::GIFT_EXPIRY_SECS));
    let rent = env.lamports(&gift) - GIFT;

    // Someone else cannot claim it.
    let thief = funded(&mut env, SOL);
    let t = thief.insecure_clone();
    let res = env.send_as(&t, &[ix_claim_gift(&t.pubkey(), &gift, &sender.pubkey(), None)], &[]);
    assert_hd(&res, 0, HdError::GiftNotClaimable);
    // The rent must go back to the stored sender, nobody else.
    let res = env.send_as(&t, &[ix_claim_gift(&t.pubkey(), &gift, &t.pubkey(), None)], &[]);
    assert_hd(&res, 0, HdError::Unauthorized);

    // The recipient claims, funds its own ORE Automation (executor = the
    // Executor PDA) and registers a rig, all in one transaction.
    let w = recipient.wallet.insecure_clone();
    let sender_before = env.lamports(&sender.pubkey());
    let meta = ok(env.send_as(
        &w,
        &[
            ix_claim_gift(&w.pubkey(), &gift, &sender.pubkey(), None),
            ore_automate_default(&w.pubkey(), GIFT),
            ix_register_rig(&w.pubkey(), &recipient.p256(), None),
        ],
        &[],
    ));
    assert!(events(&meta.logs).contains(&Event::GiftClaimed {
        gift,
        claimer: w.pubkey(),
        lamports: GIFT,
        recipient_kind: gift_kind::WALLET,
    }));
    let auto = env.automation(&recipient.automation()).unwrap();
    assert_eq!((auto.balance, auto.executor), (GIFT, EXECUTOR));
    assert_eq!(env.rig(&recipient.rig).authority, w.pubkey().to_bytes());
    // The escrow is closed and its rent returned to the sender.
    assert!(env.is_closed(&gift));
    assert_eq!(env.lamports(&sender.pubkey()), sender_before + rent);
    // Claimed once.
    let res = env.send_as(&w, &[ix_claim_gift(&w.pubkey(), &gift, &sender.pubkey(), None)], &[]);
    assert_hd(&res, 0, HdError::InvalidAccountTag);
}

#[test]
fn an_sgt_gift_follows_the_sgt_to_its_current_holder() {
    let mut env = Env::new();
    let sender = funded(&mut env, 2 * SOL);
    let a = funded(&mut env, SOL / 10);
    let b = funded(&mut env, SOL / 10);
    let (mint, a_account) = env.give_real_sgt("member-20", &a.pubkey());
    let gift = gift_pda(&sender.pubkey(), 7);
    ok(create(&mut env, &sender, 7, gift_kind::SGT_MINT, &mint, GIFT));

    // b does not hold it: passing a's token account fails the owner check.
    let bk = b.insecure_clone();
    let claim = |who: &Keypair, account: Address, m: Address| {
        ix_claim_gift(&who.pubkey(), &gift, &sender.pubkey(), Some((account, m)))
    };
    let res = env.send_as(&bk, &[claim(&b, a_account, mint)], &[]);
    assert_custom(&res, 0, SgtError::TokenAccountOwnerMismatch.program_error_code());
    // Missing SGT accounts.
    let res = env.send_as(&bk, &[ix_claim_gift(&b.pubkey(), &gift, &sender.pubkey(), None)], &[]);
    #[allow(deprecated)] // the runtime still reports ProgramError::NotEnoughAccountKeys this way
    let missing = InstructionError::NotEnoughAccountKeys;
    assert_ix_err(&res, 0, missing);
    // Solana Mobile moves the SGT from a to b: a's account now holds 0.
    let mut old = env.account(&a_account);
    old.data[64..72].copy_from_slice(&0u64.to_le_bytes());
    env.svm.set_account(a_account, old).unwrap();
    let (_, b_account) = env.give_real_sgt("member-20", &b.pubkey());
    let ak = a.insecure_clone();
    let res = env.send_as(&ak, &[claim(&a, a_account, mint)], &[]);
    assert_custom(&res, 0, SgtError::AmountNotOne.program_error_code());
    // A different real SGT held by b does not match the gift's mint.
    let (other_mint, other_account) = env.give_real_sgt("member-121035", &b.pubkey());
    let res = env.send_as(&bk, &[claim(&b, other_account, other_mint)], &[]);
    assert_hd(&res, 0, HdError::GiftNotClaimable);
    // The current holder claims.
    let before = env.lamports(&b.pubkey());
    let meta = ok(env.send_as(&bk, &[claim(&b, b_account, mint)], &[]));
    assert!(events(&meta.logs).contains(&Event::GiftClaimed {
        gift,
        claimer: b.pubkey(),
        lamports: GIFT,
        recipient_kind: gift_kind::SGT_MINT,
    }));
    // b paid the transaction fee (5,000 lamports) and received the gift.
    assert_eq!(env.lamports(&b.pubkey()), before + GIFT - 5_000);
}

#[test]
fn unclaimed_gifts_refund_to_the_sender_from_day_30_only() {
    let mut env = Env::new();
    let sender = funded(&mut env, 2 * SOL);
    let r = funded(&mut env, SOL / 10);
    let gift = gift_pda(&sender.pubkey(), 3);
    ok(create(&mut env, &sender, 3, gift_kind::WALLET, &r.pubkey(), GIFT));
    let total = env.lamports(&gift);
    // Too early (anyone may call it, but not yet).
    let res = env.send(&[ix_refund_gift(&gift, &sender.pubkey())], &[]);
    assert_hd(&res, 0, HdError::GiftExpiry);
    env.set_clock(env.slot, T0 + skr::GIFT_EXPIRY_SECS);
    // From expiry, claims stop...
    let rk = r.insecure_clone();
    let res = env.send_as(&rk, &[ix_claim_gift(&r.pubkey(), &gift, &sender.pubkey(), None)], &[]);
    assert_hd(&res, 0, HdError::GiftExpiry);
    // ...and the refund goes only to the stored sender.
    let res = env.send(&[ix_refund_gift(&gift, &r.pubkey())], &[]);
    assert_hd(&res, 0, HdError::Unauthorized);
    let before = env.lamports(&sender.pubkey());
    let meta = ok(env.send(&[ix_refund_gift(&gift, &sender.pubkey())], &[]));
    assert_eq!(
        events(&meta.logs),
        vec![Event::GiftRefunded {
            gift,
            sender: sender.pubkey(),
            lamports: GIFT,
        }]
    );
    assert_eq!(env.lamports(&sender.pubkey()), before + total);
    assert!(env.is_closed(&gift));
    let res = env.send(&[ix_refund_gift(&gift, &sender.pubkey())], &[]);
    assert_hd(&res, 0, HdError::InvalidAccountTag);
}

#[test]
fn create_gift_validates_its_arguments_and_tolerates_prefunding() {
    let mut env = Env::new();
    let sender = funded(&mut env, 20 * SOL);
    let to = Keypair::new().pubkey();
    let cases: Vec<(u8, Address, u64, HdError)> = vec![
        (gift_kind::WALLET, to, 0, HdError::AmountOutOfRange),
        (gift_kind::WALLET, to, skr::MAX_GIFT_LAMPORTS + 1, HdError::AmountOutOfRange),
        (2, to, GIFT, HdError::InvalidInstruction),
        (gift_kind::WALLET, Address::default(), GIFT, HdError::InvalidInstruction),
    ];
    for (kind, recipient, lamports, e) in cases {
        assert_hd(&create(&mut env, &sender, 1, kind, &recipient, lamports), 0, e);
    }
    // The escrow address must be ["gift", sender, nonce].
    let s = sender.insecure_clone();
    let mut ix = ix_create_gift(&s.pubkey(), 1, gift_kind::WALLET, &to, GIFT);
    ix.accounts[1].pubkey = gift_pda(&s.pubkey(), 2);
    assert_ix_err(&env.send_as(&s, &[ix], &[]), 0, InstructionError::InvalidSeeds);
    // The sender must sign.
    let mut ix = ix_create_gift(&s.pubkey(), 1, gift_kind::WALLET, &to, GIFT);
    ix.accounts[0].is_signer = false;
    assert_ix_err(&env.send(&[ix], &[]), 0, InstructionError::MissingRequiredSignature);
    // Pre-funding the escrow address does not block the gift (griefing).
    let gift = gift_pda(&s.pubkey(), 1);
    let dust = env.svm.minimum_balance_for_rent_exemption(0);
    ok(env.send(&[system_transfer(&env.cranker.pubkey(), &gift, dust)], &[]));
    ok(create(&mut env, &sender, 1, gift_kind::WALLET, &to, GIFT));
    assert_eq!(env.gift(&gift).lamports.get(), GIFT);
    // One escrow per (sender, nonce).
    assert_ix_err(
        &create(&mut env, &sender, 1, gift_kind::WALLET, &to, GIFT),
        0,
        InstructionError::AccountAlreadyInitialized,
    );
    // The same nonce under another sender is a different escrow.
    let other = funded(&mut env, 2 * SOL);
    ok(create(&mut env, &other, 1, gift_kind::WALLET, &to, GIFT));
}
