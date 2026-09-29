//! The shift state machine: arming by wallet or P-256 PLAN, BREAK / FREEZE
//! from the phone key, wallet-only unfreeze, record_heartbeats, the
//! permissionless end_shift rules, ShiftLog and streak accounting.

use hd::{
    error::HdError,
    message::kind,
    state::{break_reason, plan_flags, rig_state},
};
use heads_down_tests::*;
use p256_introspect::IntrospectError;

/// automate + register + caps, but not armed.
fn registered(env: &mut Env, seed: u8) -> User {
    let user = User::new(env, seed);
    let w = user.wallet.insecure_clone();
    ok(env.send_as(
        &w,
        &[
            ore_automate_default(&w.pubkey(), SOL / 20),
            ix_register_rig(&w.pubkey(), &user.p256(), None),
            ix_set_caps(&w.pubkey(), Caps::standard()),
        ],
        &[],
    ));
    user
}

fn arm_by_plan(env: &mut Env, user: &mut User, plan: &hd::message::Plan) -> TxResult {
    let counter = user.next_counter();
    let (d, s) = plan_signature(user, counter, plan);
    let ixs = [
        secp_ix(&[(s, user.p256(), d.to_vec())]),
        ix_arm_p256(&user.pubkey(), plan, counter, 0, 0),
    ];
    env.send(&ixs, &[])
}

fn signal_by_p256(env: &mut Env, user: &mut User, freeze: bool, reason: u8) -> TxResult {
    let counter = user.next_counter();
    let shift = env.rig(&user.rig).shift_id.get();
    let k = if freeze { kind::FREEZE } else { kind::BREAK };
    let (d, s) = signal_signature(user, k, counter, shift, reason);
    let ix = if freeze {
        ix_freeze_p256(&user.pubkey(), reason, counter, 0, 0)
    } else {
        ix_break_p256(&user.pubkey(), reason, counter, 0, 0)
    };
    env.send(&[secp_ix(&[(s, user.p256(), d.to_vec())]), ix], &[])
}

fn record(env: &mut Env, user: &mut User, round: u64, lease: u8) -> TxResult {
    let shift = env.rig(&user.rig).shift_id.get();
    let hb = user.heartbeat(shift, round, lease);
    env.send(
        &[
            secp_ix_for(&[hb]),
            ix_record(&[(user.rig, entry_for(&hb, 0, 0))]),
        ],
        &[],
    )
}

fn set_board_round(env: &mut Env, round: u64) {
    env.poke_u64(&BOARD, 8, round);
    env.board_round = round;
}

#[test]
fn phone_key_arms_a_shift_within_wallet_caps() {
    let mut env = Env::new();
    let mut u = registered(&mut env, 1);
    let plan = standard_plan();
    let meta = ok(arm_by_plan(&mut env, &mut u, &plan));
    let rig = env.rig(&u.rig);
    assert_eq!(rig.state, rig_state::ARMED);
    assert_eq!(rig.shift_id.get(), 1);
    assert_eq!(rig.hb_counter.get(), 1);
    assert_eq!(rig.plan_dig_lamports.get(), plan.dig_lamports);
    assert_eq!(
        events(&meta.logs),
        vec![Event::ShiftArmed {
            rig: u.rig,
            shift_id: 1
        }]
    );

    // The same signed PLAN replayed after the shift ends: stale counter.
    let w = u.wallet.insecure_clone();
    ok(env.send_as(&w, &[ix_end_shift(&w.pubkey(), &u.rig, 1)], &[]));
    let (d, s) = plan_signature(&u, 1, &plan);
    let res = env.send(
        &[
            secp_ix(&[(s, u.p256(), d.to_vec())]),
            ix_arm_p256(&u.pubkey(), &plan, 1, 0, 0),
        ],
        &[],
    );
    assert_hd(&res, 1, HdError::StaleHeartbeat);

    // A plan above the wallet's caps cannot be armed by the phone key.
    let mut greedy = plan;
    greedy.dig_lamports = Caps::standard().round + 1;
    assert_hd(
        &arm_by_plan(&mut env, &mut u, &greedy),
        1,
        HdError::PlanExceedsCaps,
    );
    let mut greedy = plan;
    greedy.max_ev_cost = Caps::standard().max_cost + 1;
    assert_hd(
        &arm_by_plan(&mut env, &mut u, &greedy),
        1,
        HdError::PlanExceedsCaps,
    );

    // A PLAN signed by another key.
    let other = User::new(&mut env, 50);
    let counter = 99;
    let d = hd::message::digest(&hd::message::plan_preimage(&u.rig, counter, &plan));
    let s = other.sign(&d);
    let res = env.send(
        &[
            secp_ix(&[(s, other.p256(), d.to_vec())]),
            ix_arm_p256(&u.pubkey(), &plan, counter, 0, 0),
        ],
        &[],
    );
    assert_custom(&res, 1, IntrospectError::PublicKeyMismatch.code());

    // Wallet mode without the wallet's signature.
    let mut ix = ix_arm_wallet(&u.pubkey(), &plan);
    ix.accounts[1].is_signer = false;
    assert_ix_err(
        &env.send(&[ix], &[]),
        0,
        InstructionError::MissingRequiredSignature,
    );
}

#[test]
fn arm_validates_state_caps_and_plan_fields() {
    let mut env = Env::new();
    // Caps never set: expired.
    let u = User::new(&mut env, 2);
    let w = u.wallet.insecure_clone();
    ok(env.send_as(&w, &[ix_register_rig(&w.pubkey(), &u.p256(), None)], &[]));
    let res = env.send_as(&w, &[ix_arm_wallet(&w.pubkey(), &standard_plan())], &[]);
    assert_hd(&res, 0, HdError::CapsExpired);

    let u = registered(&mut env, 3);
    let w = u.wallet.insecure_clone();
    let bad = |f: &dyn Fn(&mut hd::message::Plan)| {
        let mut p = standard_plan();
        f(&mut p);
        p
    };
    for p in [
        bad(&|p| p.lease = 0),
        bad(&|p| p.lease = 4),
        bad(&|p| p.split = 16),
        bad(&|p| p.solo = 11),
        bad(&|p| {
            p.split = 0;
            p.solo = 0
        }),
        bad(&|p| p.dig_lamports = 0),
        bad(&|p| p.flags = 0b100),
        bad(&|p| p.window_start = p.window_end),
    ] {
        let res = env.send_as(&w, &[ix_arm_wallet(&w.pubkey(), &p)], &[]);
        assert_hd(&res, 0, HdError::InvalidInstruction);
    }
    let past = bad(&|p| {
        p.window_start = T0 - 7_200;
        p.window_end = T0 - 1
    });
    assert_hd(
        &env.send_as(&w, &[ix_arm_wallet(&w.pubkey(), &past)], &[]),
        0,
        HdError::OutsideWindow,
    );
    // Arm once; arming again while the shift is open fails.
    ok(env.send_as(&w, &[ix_arm_wallet(&w.pubkey(), &standard_plan())], &[]));
    assert_hd(
        &env.send_as(&w, &[ix_arm_wallet(&w.pubkey(), &standard_plan())], &[]),
        0,
        HdError::InvalidRigState,
    );
    // Another wallet cannot arm this rig.
    let mallory = Keypair::new();
    env.svm.airdrop(&mallory.pubkey(), SOL).unwrap();
    let mut ix = ix_arm_wallet(&w.pubkey(), &standard_plan());
    ix.accounts[1] = AccountMeta::new_readonly(mallory.pubkey(), true);
    assert_hd(&env.send_as(&mallory, &[ix], &[]), 0, HdError::Unauthorized);
}

#[test]
fn pickup_cools_the_rig_and_a_fresh_heartbeat_resumes_it() {
    let mut env = Env::new();
    let mut u = User::new(&mut env, 4);
    env.onboard_standard(&u);
    let r = env.board_round;
    ok(record(&mut env, &mut u, r, 1));
    assert_eq!(env.rig(&u.rig).state, rig_state::DOWN);

    ok(signal_by_p256(
        &mut env,
        &mut u,
        false,
        break_reason::PICKUP,
    ));
    let rig = env.rig(&u.rig);
    assert_eq!(rig.state, rig_state::COOLING);
    assert_eq!(rig.break_reason, break_reason::PICKUP);
    // The outstanding lease cannot be reused while cooling.
    let meta = ok(env.dig_with(&[], &[DigRig::new(&u, reuse_lease())]));
    assert_eq!(
        skipped_code(&events(&meta.logs), &u.rig),
        Some(HdError::RigNotArmed.code())
    );
    // Phone back down: a fresh heartbeat resumes the shift (and can dig).
    let meta = ok(env.dig_fresh(&mut [&mut u]));
    assert!(dug(&events(&meta.logs), &u.rig).is_some());
    assert_eq!(env.rig(&u.rig).state, rig_state::DOWN);

    // A BREAK must also carry a fresh counter.
    let shift = env.rig(&u.rig).shift_id.get();
    let stale = env.rig(&u.rig).hb_counter.get();
    let (d, s) = signal_signature(&u, kind::BREAK, stale, shift, break_reason::MANUAL);
    let res = env.send(
        &[
            secp_ix(&[(s, u.p256(), d.to_vec())]),
            ix_break_p256(&u.pubkey(), break_reason::MANUAL, stale, 0, 0),
        ],
        &[],
    );
    assert_hd(&res, 1, HdError::StaleHeartbeat);
    // A BREAK message cannot be replayed as a FREEZE (domain-separated kind).
    let c = u.next_counter();
    let (d, s) = signal_signature(&u, kind::BREAK, c, shift, 3);
    let res = env.send(
        &[
            secp_ix(&[(s, u.p256(), d.to_vec())]),
            ix_freeze_p256(&u.pubkey(), 3, c, 0, 0),
        ],
        &[],
    );
    assert_custom(&res, 1, IntrospectError::MessageMismatch.code());

    // Manual break: Broken; heartbeats no longer revive it.
    ok(signal_by_p256(
        &mut env,
        &mut u,
        false,
        break_reason::MANUAL,
    ));
    assert_eq!(env.rig(&u.rig).state, rig_state::BROKEN);
    let meta = ok(record(&mut env, &mut u, r, 1));
    assert_eq!(
        skipped_code(&events(&meta.logs), &u.rig),
        Some(HdError::RigNotArmed.code())
    );
    // Invalid reasons are malformed data (checked before state).
    let w = u.wallet.insecure_clone();
    for reason in [0, 3, 9, 255] {
        assert_hd(
            &env.send_as(&w, &[ix_break_wallet(&w.pubkey(), reason)], &[]),
            0,
            HdError::InvalidInstruction,
        );
    }
    // A broken rig cannot be broken again.
    assert_hd(
        &env.send_as(
            &w,
            &[ix_break_wallet(&w.pubkey(), break_reason::MANUAL)],
            &[],
        ),
        0,
        HdError::InvalidRigState,
    );
}

#[test]
fn phone_can_freeze_only_the_wallet_can_unfreeze() {
    let mut env = Env::new();
    let mut u = User::new(&mut env, 5);
    env.onboard_standard(&u);
    let r = env.board_round;
    ok(record(&mut env, &mut u, r, 1));
    ok(signal_by_p256(&mut env, &mut u, true, 3));
    let rig = env.rig(&u.rig);
    assert_eq!(rig.state, rig_state::FROZEN);
    assert_eq!(rig.break_reason, break_reason::FREEZE);

    // No wallet signature: refused. Another wallet: refused.
    assert_ix_err(
        &env.send(&[ix_unfreeze(&u.pubkey(), false)], &[]),
        0,
        InstructionError::MissingRequiredSignature,
    );
    let mallory = Keypair::new();
    env.svm.airdrop(&mallory.pubkey(), SOL).unwrap();
    let mut ix = ix_unfreeze(&u.pubkey(), true);
    ix.accounts[1] = AccountMeta::new_readonly(mallory.pubkey(), true);
    assert_hd(&env.send_as(&mallory, &[ix], &[]), 0, HdError::Unauthorized);
    // A frozen rig cannot be re-armed or closed by the phone, and cannot dig.
    let meta = ok(env.dig_fresh(&mut [&mut u]));
    assert_eq!(
        skipped_code(&events(&meta.logs), &u.rig),
        Some(HdError::RigFrozen.code())
    );

    // The wallet unfreezes: the open shift becomes Broken, end_shift seals it
    // with reason freeze.
    let w = u.wallet.insecure_clone();
    ok(env.send_as(&w, &[ix_unfreeze(&w.pubkey(), true)], &[]));
    assert_eq!(env.rig(&u.rig).state, rig_state::BROKEN);
    ok(env.send_as(&w, &[ix_end_shift(&w.pubkey(), &u.rig, 1)], &[]));
    let log = env.shift_log(&shift_log_pda(&u.rig, 1));
    assert_eq!(log.break_reason, break_reason::FREEZE);
    assert_eq!(env.rig(&u.rig).state, rig_state::IDLE);
    // Unfreezing a live rig is an error.
    assert_hd(
        &env.send_as(&w, &[ix_unfreeze(&w.pubkey(), true)], &[]),
        0,
        HdError::InvalidRigState,
    );
    // Freeze while idle, unfreeze straight back to idle.
    ok(env.send_as(&w, &[ix_freeze_wallet(&w.pubkey())], &[]));
    ok(env.send_as(&w, &[ix_unfreeze(&w.pubkey(), true)], &[]));
    assert_eq!(env.rig(&u.rig).state, rig_state::IDLE);
}

#[test]
fn anyone_may_end_a_shift_only_after_the_window_and_the_lease() {
    let mut env = Env::new();
    let mut u = User::new(&mut env, 6);
    env.onboard_standard(&u);
    let r = env.board_round;
    ok(record(&mut env, &mut u, r, 3)); // lease r..=r+2
    let cranker = env.cranker.pubkey();

    // Inside the window: only the authority.
    assert_hd(
        &env.send(&[ix_end_shift(&cranker, &u.rig, 1)], &[]),
        0,
        HdError::Unauthorized,
    );
    // Window over but the lease still covers the current round.
    env.advance_time(8 * 3_600 + 1);
    set_board_round(&mut env, r + 2);
    assert_hd(
        &env.send(&[ix_end_shift(&cranker, &u.rig, 1)], &[]),
        0,
        HdError::Unauthorized,
    );
    // Lease expired too: the crank may seal it (and pays the rent).
    set_board_round(&mut env, r + 4);
    // Wrong ShiftLog address is refused.
    let mut ix = ix_end_shift(&cranker, &u.rig, 1);
    ix.accounts[2] = AccountMeta::new(shift_log_pda(&u.rig, 2), false);
    assert_ix_err(&env.send(&[ix], &[]), 0, InstructionError::InvalidSeeds);
    let meta = ok(env.send(&[ix_end_shift(&cranker, &u.rig, 1)], &[]));
    let log = env.shift_log(&shift_log_pda(&u.rig, 1));
    assert_eq!(log.break_reason, break_reason::COMPLETED);
    assert_eq!(log.start_round.get(), r);
    assert_eq!(log.end_round.get(), r + 4);
    assert_eq!(log.dark_rounds.get(), 3); // r, r+1, r+2
    let rig = env.rig(&u.rig);
    assert_eq!(rig.gap_count.get(), 2); // r+3, r+4
    assert_eq!(rig.streak.get(), 1);
    assert_eq!(rig.state, rig_state::IDLE);
    assert!(matches!(
        events(&meta.logs)[..],
        [
            Event::ShiftEnded {
                reason: 0,
                dark_rounds: 3,
                ..
            },
            Event::ShiftEndedV2 {
                reason: 0,
                dark_rounds: 3,
                mode: 0,
                ..
            }
        ]
    ));
}

#[test]
fn streak_and_freezes_across_nights() {
    let mut env = Env::new();
    let mut u = registered(&mut env, 7);
    let w = u.wallet.insecure_clone();
    let mut caps = Caps::standard();
    caps.expiry = T0 + 60 * 86_400;
    ok(env.send_as(&w, &[ix_set_caps(&w.pubkey(), caps)], &[]));
    let base_round = env.board_round;
    let night = |env: &mut Env, u: &mut User, day: i64, shift: u64| {
        let start = T0 + day * 86_400 + 20 * 3_600;
        env.set_clock(env.slot, start);
        let round = base_round + day as u64 * 1_000;
        set_board_round(env, round);
        let mut plan = standard_plan();
        plan.window_start = start;
        plan.window_end = start + 8 * 3_600;
        ok(env.send_as(&w, &[ix_arm_wallet(&w.pubkey(), &plan)], &[]));
        ok(record(env, u, round, 3));
        env.set_clock(env.slot, plan.window_end + 1);
        set_board_round(env, round + 400);
        let cranker = env.cranker.pubkey();
        ok(env.send(&[ix_end_shift(&cranker, &u.rig, shift)], &[]));
        let r = env.rig(&u.rig);
        (r.streak.get(), r.freezes_left)
    };
    assert_eq!(night(&mut env, &mut u, 0, 1), (1, 2));
    assert_eq!(night(&mut env, &mut u, 1, 2), (2, 2));
    assert_eq!(night(&mut env, &mut u, 2, 3), (3, 2));
    // Skip one night: a freeze covers it.
    assert_eq!(night(&mut env, &mut u, 4, 4), (4, 1));
    // Skip three nights: more than the freezes left, the streak restarts.
    assert_eq!(night(&mut env, &mut u, 8, 5), (1, 1));
    assert_eq!(env.rig(&u.rig).lifetime_dark_rounds.get(), 5 * 3);
}

#[test]
fn record_heartbeats_counts_dark_rounds_without_deploying() {
    let mut env = Env::new();
    let mut plan = standard_plan();
    plan.flags = plan_flags::FOCUS_ONLY | plan_flags::DAY;
    let mut a = User::new(&mut env, 8);
    let mut b = User::new(&mut env, 9);
    ok(env.onboard(&a, SOL / 20, Caps::standard(), &plan));
    env.onboard_standard(&b);
    let r = env.board_round;
    let ha = a.heartbeat(1, r, 2);
    let hb = b.heartbeat(1, r, 3);
    let meta = ok(env.send(
        &[
            secp_ix_for(&[ha, hb]),
            ix_record(&[(a.rig, entry_for(&ha, 0, 0)), (b.rig, entry_for(&hb, 0, 1))]),
        ],
        &[],
    ));
    assert_eq!(
        events(&meta.logs),
        vec![
            Event::HeartbeatsRecorded {
                rig: a.rig,
                round_id: r,
                dark_rounds_added: 2
            },
            Event::HeartbeatsRecorded {
                rig: b.rig,
                round_id: r,
                dark_rounds_added: 3
            }
        ]
    );
    // Focus-only plans are still limited by their own lease cap (2 here).
    assert_eq!(env.rig(&a.rig).shift_dark_rounds.get(), 2);
    assert_eq!(env.rig(&b.rig).shift_dark_rounds.get(), 3);
    assert_eq!(env.automation(&a.automation()).unwrap().balance, SOL / 20);

    // Duplicate rig fails; an entry without a heartbeat is skipped.
    let h1 = a.heartbeat(1, r, 1);
    let h2 = a.heartbeat(1, r, 1);
    let res = env.send(
        &[
            secp_ix_for(&[h1, h2]),
            ix_record(&[(a.rig, entry_for(&h1, 0, 0)), (a.rig, entry_for(&h2, 0, 1))]),
        ],
        &[],
    );
    assert_hd(&res, 1, HdError::DuplicateRig);
    let meta = ok(env.send(&[ix_record(&[(a.rig, reuse_lease())])], &[]));
    assert_eq!(
        skipped_code(&events(&meta.logs), &a.rig),
        Some(HdError::InvalidHeartbeat.code())
    );

    // A focus-only day shift ends with mode 2.
    let w = a.wallet.insecure_clone();
    ok(env.send_as(&w, &[ix_end_shift(&w.pubkey(), &a.rig, 1)], &[]));
    assert_eq!(env.shift_log(&shift_log_pda(&a.rig, 1)).mode, 2);
}

#[test]
fn close_rig_requires_an_idle_rig_and_its_authority() {
    let mut env = Env::new();
    let u = User::new(&mut env, 10);
    env.onboard_standard(&u);
    let w = u.wallet.insecure_clone();
    assert_hd(
        &env.send_as(&w, &[ix_close_rig(&w.pubkey(), None)], &[]),
        0,
        HdError::InvalidRigState,
    );
    ok(env.send_as(&w, &[ix_end_shift(&w.pubkey(), &u.rig, 1)], &[]));
    let mallory = Keypair::new();
    env.svm.airdrop(&mallory.pubkey(), SOL).unwrap();
    let mut ix = ix_close_rig(&w.pubkey(), None);
    ix.accounts[0] = AccountMeta::new(mallory.pubkey(), true);
    assert_hd(&env.send_as(&mallory, &[ix], &[]), 0, HdError::Unauthorized);
    // Close, then try to use the rig in the same transaction: gone.
    let res = env.send_as(
        &w,
        &[
            ix_close_rig(&w.pubkey(), None),
            ix_set_caps(&w.pubkey(), Caps::standard()),
        ],
        &[],
    );
    assert_hd(&res, 1, HdError::InvalidAccountTag);
    ok(env.send_as(&w, &[ix_close_rig(&w.pubkey(), None)], &[]));
    assert!(env.svm.get_account(&u.rig).is_none());
    // The address can be registered again from scratch.
    ok(env.send_as(&w, &[ix_register_rig(&w.pubkey(), &u.p256(), None)], &[]));
    assert_eq!(env.rig(&u.rig).shift_id.get(), 0);
}
