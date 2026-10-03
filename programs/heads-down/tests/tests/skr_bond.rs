//! v1.2 Focus Bond on the live-ORE fork: SKR locked on an open shift is
//! released to its owner when the shift's ShiftLog says `completed`, and is
//! forfeited (permissionlessly) into the Bury lot when it says anything
//! else, or when the shift can never be sealed.

use hd::{error::HdError, events as ev, message::kind, skr, state::break_reason};
use heads_down_tests::*;

const AMOUNT: u64 = 500 * ONE_SKR;

fn bonded_user(env: &mut Env, seed: u8) -> User {
    let u = User::new(env, seed);
    env.onboard_standard(&u);
    env.fund_skr(&u.pubkey(), 1_000 * ONE_SKR);
    u
}

fn lock(env: &mut Env, u: &User, shift_id: u64, amount: u64) -> TxResult {
    let w = u.wallet.insecure_clone();
    let bond = bond_pda(&u.rig, shift_id);
    env.send_as(
        &w,
        &[
            ix_create_ata(&w.pubkey(), &bond, &SKR_MINT),
            ix_lock_focus_bond(&w.pubkey(), shift_id, amount),
        ],
        &[],
    )
}

fn record(env: &mut Env, u: &mut User, round: u64) {
    let shift = env.rig(&u.rig).shift_id.get();
    let hb = u.heartbeat(shift, round, 3);
    ok(env.send(
        &[secp_ix_for(&[hb]), ix_record(&[(u.rig, entry_for(&hb, 0, 0))])],
        &[],
    ));
}

fn break_p256(env: &mut Env, u: &mut User, reason: u8) {
    let counter = u.next_counter();
    let shift = env.rig(&u.rig).shift_id.get();
    let (d, s) = signal_signature(u, kind::BREAK, counter, shift, reason);
    ok(env.send(
        &[
            secp_ix(&[(s, u.p256(), d.to_vec())]),
            ix_break_p256(&u.pubkey(), reason, counter, 0, 0),
        ],
        &[],
    ));
}

/// After the plan window and the lease: anyone may seal the shift.
fn end_after_window(env: &mut Env, u: &User, shift_id: u64) -> TxResult {
    let end = standard_plan().window_end + 1;
    let slot = env.slot;
    env.set_clock(slot, end);
    let lease_to = env.rig(&u.rig).lease_to_round.get();
    env.set_board_round(lease_to.max(env.board_round) + 1);
    let c = env.cranker.pubkey();
    env.send(&[ix_end_shift(&c, &u.rig, shift_id)], &[])
}

#[test]
fn a_completed_shift_releases_the_bond_to_its_owner_only() {
    let mut env = Env::new();
    env.init_bury_vault();
    let mut u = bonded_user(&mut env, 1);
    let r = env.board_round;
    let bond = bond_pda(&u.rig, 1);
    let vault = ata(&bond, &SKR_MINT);
    let meta = ok(lock(&mut env, &u, 1, AMOUNT));
    assert_eq!(
        events(&meta.logs),
        vec![Event::FocusBondLocked {
            bond,
            rig: u.rig,
            authority: u.pubkey(),
            shift_id: 1,
            amount: AMOUNT,
        }]
    );
    assert_eq!(env.token_balance(&vault), AMOUNT);
    let b = env.focus_bond(&bond);
    assert_eq!((b.shift_id.get(), b.amount.get()), (1, AMOUNT));
    assert_eq!(b.shift_start_round.get(), r);
    assert_eq!(b.shift_start_ts.get(), T0);

    record(&mut env, &mut u, r);
    // Neither outcome while the shift is open.
    assert_hd(
        &env.send(&[ix_release_focus_bond(&u.pubkey(), 1)], &[]),
        0,
        HdError::InvalidAccountTag, // no ShiftLog yet
    );
    assert_hd(
        &env.send(&[ix_forfeit_focus_bond(&u.pubkey(), 1)], &[]),
        0,
        HdError::BondNotResolvable,
    );
    ok(end_after_window(&mut env, &u, 1));
    assert_eq!(env.shift_log(&shift_log_pda(&u.rig, 1)).break_reason, 0);

    // Completed: forfeit is refused, release pays the owner only.
    assert_hd(
        &env.send(&[ix_forfeit_focus_bond(&u.pubkey(), 1)], &[]),
        0,
        HdError::BondNotResolvable,
    );
    let other = Keypair::new().pubkey();
    env.fund_skr(&other, 0);
    let mut ix = ix_release_focus_bond(&u.pubkey(), 1);
    ix.accounts[3].pubkey = ata(&other, &SKR_MINT);
    assert_hd(&env.send(&[ix], &[]), 0, HdError::InvalidTokenAccount);
    let mut ix = ix_release_focus_bond(&u.pubkey(), 1);
    ix.accounts[4].pubkey = other;
    assert_hd(&env.send(&[ix], &[]), 0, HdError::Unauthorized);

    let before = env.token_balance(&ata(&u.pubkey(), &SKR_MINT));
    let lamports = env.lamports(&u.pubkey());
    let rents = env.lamports(&bond) + env.lamports(&vault);
    let meta = ok(env.send(&[ix_release_focus_bond(&u.pubkey(), 1)], &[]));
    assert_eq!(
        events(&meta.logs),
        vec![Event::FocusBondReleased {
            bond,
            rig: u.rig,
            shift_id: 1,
            amount: AMOUNT,
        }]
    );
    assert_eq!(env.token_balance(&ata(&u.pubkey(), &SKR_MINT)), before + AMOUNT);
    // Bond account and vault ATA are closed; both rents go back to the owner.
    assert!(env.is_closed(&bond) && env.is_closed(&vault));
    assert_eq!(env.lamports(&u.pubkey()), lamports + rents);
    // Only once.
    assert_hd(
        &env.send(&[ix_release_focus_bond(&u.pubkey(), 1)], &[]),
        0,
        HdError::InvalidAccountTag,
    );
}

#[test]
fn a_hard_break_forfeits_the_bond_into_the_bury_lot() {
    let mut env = Env::new();
    env.init_bury_vault();
    let mut u = bonded_user(&mut env, 2);
    let r = env.board_round;
    ok(lock(&mut env, &u, 1, AMOUNT));
    record(&mut env, &mut u, r);
    break_p256(&mut env, &mut u, break_reason::UNLOCKED);
    let w = u.wallet.insecure_clone();
    ok(env.send_as(&w, &[ix_end_shift(&w.pubkey(), &u.rig, 1)], &[]));
    assert_eq!(
        env.shift_log(&shift_log_pda(&u.rig, 1)).break_reason,
        break_reason::UNLOCKED
    );
    assert_hd(
        &env.send(&[ix_release_focus_bond(&u.pubkey(), 1)], &[]),
        0,
        HdError::BondNotResolvable,
    );
    // Anyone may forfeit it (the cranker pays the fee); the SKR becomes a lot.
    let bond = bond_pda(&u.rig, 1);
    let lamports = env.lamports(&u.pubkey());
    let rents = env.lamports(&bond) + env.lamports(&ata(&bond, &SKR_MINT));
    let meta = ok(env.send(&[ix_forfeit_focus_bond(&u.pubkey(), 1)], &[]));
    assert_eq!(
        events(&meta.logs),
        vec![
            Event::BuryLotAdded {
                source: bond,
                amount: AMOUNT,
                lot_skr: AMOUNT,
                start_price: skr::INITIAL_START_PRICE,
                start_slot: env.slot,
                source_kind: ev::LOT_FROM_BOND,
            },
            Event::FocusBondForfeited {
                bond,
                rig: u.rig,
                shift_id: 1,
                amount: AMOUNT,
                reason: break_reason::UNLOCKED,
            },
        ]
    );
    assert_eq!(env.token_balance(&ata(&BURY, &SKR_MINT)), AMOUNT);
    assert_eq!(env.bury_vault().lot_skr.get(), AMOUNT);
    // The SKR went to Bury; the rents (the owner's SOL) went back to the owner.
    assert_eq!(env.lamports(&u.pubkey()), lamports + rents);
    assert!(env.is_closed(&bond));
}

#[test]
fn a_resumed_pickup_seals_completed_and_keeps_the_bond() {
    let mut env = Env::new();
    let mut u = bonded_user(&mut env, 3);
    let r = env.board_round;
    ok(lock(&mut env, &u, 1, AMOUNT));
    record(&mut env, &mut u, r);
    break_p256(&mut env, &mut u, break_reason::PICKUP);
    // Back face-down: a fresh heartbeat resumes the shift (v1.1 Cooling).
    record(&mut env, &mut u, r);
    ok(end_after_window(&mut env, &u, 1));
    assert_eq!(env.shift_log(&shift_log_pda(&u.rig, 1)).break_reason, 0);
    ok(env.send(&[ix_release_focus_bond(&u.pubkey(), 1)], &[]));
    assert_eq!(env.token_balance(&ata(&u.pubkey(), &SKR_MINT)), 1_000 * ONE_SKR);
}

#[test]
fn a_bond_on_a_rig_closed_mid_shift_is_abandoned_and_forfeits() {
    let mut env = Env::new();
    env.init_bury_vault();
    let u = bonded_user(&mut env, 4);
    ok(lock(&mut env, &u, 1, AMOUNT));
    // Frozen with the shift open, then closed: the shift can never be sealed.
    let w = u.wallet.insecure_clone();
    ok(env.send_as(&w, &[ix_freeze_wallet(&w.pubkey())], &[]));
    assert_hd(
        &env.send(&[ix_forfeit_focus_bond(&u.pubkey(), 1)], &[]),
        0,
        HdError::BondNotResolvable, // still sealable (end_shift)
    );
    ok(env.send_as(&w, &[ix_close_rig(&w.pubkey(), None)], &[]));
    let meta = ok(env.send(&[ix_forfeit_focus_bond(&u.pubkey(), 1)], &[]));
    assert!(events(&meta.logs).contains(&Event::FocusBondForfeited {
        bond: bond_pda(&u.rig, 1),
        rig: u.rig,
        shift_id: 1,
        amount: AMOUNT,
        reason: ev::BOND_ABANDONED,
    }));
}

#[test]
fn a_rig_re_registered_after_closing_cannot_revive_an_abandoned_bond() {
    let mut env = Env::new();
    env.init_bury_vault();
    let u = bonded_user(&mut env, 5);
    ok(lock(&mut env, &u, 1, AMOUNT));
    let w = u.wallet.insecure_clone();
    ok(env.send_as(&w, &[ix_freeze_wallet(&w.pubkey())], &[]));
    ok(env.send_as(&w, &[ix_close_rig(&w.pubkey(), None)], &[]));
    // Re-register and arm again. v1.3: the rig resumes from its tombstone,
    // so the new shift is 2 (under v1.2 the id restarted at 1).
    env.advance_time(60);
    let plan = standard_plan();
    ok(env.send_as(
        &w,
        &[
            ix_register_rig(&w.pubkey(), &u.p256(), None),
            ix_set_caps(&w.pubkey(), Caps::standard()),
            ix_arm_wallet(&w.pubkey(), &plan),
        ],
        &[],
    ));
    assert_eq!(env.rig(&u.rig).shift_id.get(), 2);
    // The bonded shift 1 is gone for good: forfeit, reason abandoned.
    assert_hd(
        &env.send(&[ix_release_focus_bond(&u.pubkey(), 1)], &[]),
        0,
        HdError::InvalidAccountTag, // shift 1 was never sealed: no ShiftLog
    );
    let meta = ok(env.send(&[ix_forfeit_focus_bond(&u.pubkey(), 1)], &[]));
    assert!(events(&meta.logs).iter().any(|e| matches!(
        e,
        Event::FocusBondForfeited { reason: ev::BOND_ABANDONED, .. }
    )));
}

#[test]
fn lock_needs_the_wallet_an_open_clean_shift_and_real_skr() {
    let mut env = Env::new();
    let mut u = bonded_user(&mut env, 6);
    let w = u.wallet.insecure_clone();
    let v = bond_pda(&u.rig, 1);
    ok(env.send_as(&w, &[ix_create_ata(&w.pubkey(), &v, &SKR_MINT)], &[]));
    let send = |env: &mut Env, ix: Instruction| env.send_as(&w, &[ix], &[]);

    assert_hd(
        &send(&mut env, ix_lock_focus_bond(&u.pubkey(), 1, 0)),
        0,
        HdError::AmountOutOfRange,
    );
    assert_hd(
        &send(&mut env, ix_lock_focus_bond(&u.pubkey(), 1, skr::FOCUS_BOND_CAP + 1)),
        0,
        HdError::AmountOutOfRange,
    );
    // The declared shift must be the open one.
    assert_hd(
        &send(&mut env, ix_lock_focus_bond(&u.pubkey(), 2, AMOUNT)),
        0,
        HdError::InvalidRigState,
    );
    // Unsigned: refused.
    let mut ix = ix_lock_focus_bond(&u.pubkey(), 1, AMOUNT);
    ix.accounts[0].is_signer = false;
    assert_ix_err(&env.send(&[ix], &[]), 0, InstructionError::MissingRequiredSignature);
    // Another wallet's rig: refused.
    let stranger = bonded_user(&mut env, 7);
    let mut ix = ix_lock_focus_bond(&stranger.pubkey(), 1, AMOUNT);
    ix.accounts[1].pubkey = u.rig;
    let ws = stranger.wallet.insecure_clone();
    assert_hd(&env.send_as(&ws, &[ix], &[]), 0, HdError::Unauthorized);
    // A Token-2022 look-alike source.
    let src = ata(&u.pubkey(), &SKR_MINT);
    let good = env.account(&src);
    let mut fake = good.clone();
    fake.owner = token_2022_id();
    env.svm.set_account(src, fake).unwrap();
    assert_hd(
        &send(&mut env, ix_lock_focus_bond(&u.pubkey(), 1, AMOUNT)),
        0,
        HdError::InvalidTokenAccount,
    );
    env.svm.set_account(src, good).unwrap();
    // A vault that is not the bond's ATA.
    let mut ix = ix_lock_focus_bond(&u.pubkey(), 1, AMOUNT);
    ix.accounts[4].pubkey = ata(&stranger.pubkey(), &SKR_MINT);
    assert_hd(&send(&mut env, ix), 0, HdError::InvalidTokenAccount);
    // A bond address that is not ["bond", rig, shift_id].
    let mut ix = ix_lock_focus_bond(&u.pubkey(), 1, AMOUNT);
    ix.accounts[2].pubkey = bond_pda(&u.rig, 2);
    assert_ix_err(&send(&mut env, ix), 0, InstructionError::InvalidSeeds);
    // A shift that already broke cannot be bonded.
    break_p256(&mut env, &mut u, break_reason::SCREEN_ON);
    assert_hd(
        &send(&mut env, ix_lock_focus_bond(&u.pubkey(), 1, AMOUNT)),
        0,
        HdError::InvalidRigState,
    );
    // A fresh clean shift can, once.
    ok(env.send_as(&w, &[ix_end_shift(&w.pubkey(), &u.rig, 1)], &[]));
    ok(env.send_as(&w, &[ix_arm_wallet(&w.pubkey(), &standard_plan())], &[]));
    ok(lock(&mut env, &u, 2, AMOUNT));
    assert_ix_err(
        &lock(&mut env, &u, 2, AMOUNT),
        1,
        InstructionError::AccountAlreadyInitialized,
    );
    // An idle rig (no open shift) cannot be bonded.
    ok(env.send_as(&w, &[ix_end_shift(&w.pubkey(), &u.rig, 2)], &[]));
    assert_hd(
        &send(&mut env, ix_lock_focus_bond(&u.pubkey(), 2, AMOUNT)),
        0,
        HdError::InvalidRigState,
    );
}
