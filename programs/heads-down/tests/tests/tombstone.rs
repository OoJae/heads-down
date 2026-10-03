//! v1.3: a closed rig leaves a 32-byte tombstone at its PDA, and
//! `register_rig` resumes from it. A rig address therefore never reuses a
//! shift id (the INTERFACE §10 bug: a re-registered rig restarted at shift 1
//! and `end_shift` hit the ShiftLog of its earlier life) and never accepts an
//! old P-256 message again.

use hd::{
    error::HdError,
    message::kind,
    state::{break_reason, plan_flags, rig_state},
};
use heads_down_tests::*;
use p256_introspect::IntrospectError;

fn record(env: &mut Env, u: &mut User, lease: u8) {
    let shift = env.rig(&u.rig).shift_id.get();
    let hb = u.heartbeat(shift, env.board_round, lease);
    ok(env.send(
        &[
            secp_ix_for(&[hb]),
            ix_record(&[(u.rig, entry_for(&hb, 0, 0))]),
        ],
        &[],
    ));
}

fn re_register(env: &mut Env, u: &User, plan: &hd::message::Plan) -> TxResult {
    let w = u.wallet.insecure_clone();
    env.send_as(
        &w,
        &[
            ix_register_rig(&w.pubkey(), &u.p256(), None),
            ix_set_caps(&w.pubkey(), Caps::standard()),
            ix_arm_wallet(&w.pubkey(), plan),
        ],
        &[],
    )
}

#[test]
fn close_re_register_arm_end_shift_never_reuses_a_shift_id() {
    let mut env = Env::new();
    assert_eq!(env.svm.minimum_balance_for_rent_exemption(32), TOMBSTONE_RENT);
    assert_eq!(env.svm.minimum_balance_for_rent_exemption(384), RIG_RENT);
    let mut u = User::new(&mut env, 1);
    env.onboard_standard(&u);
    let w = u.wallet.insecure_clone();
    record(&mut env, &mut u, 1);
    ok(env.send_as(&w, &[ix_end_shift(&w.pubkey(), &u.rig, 1)], &[]));
    let log1 = shift_log_pda(&u.rig, 1);
    let log1_before = env.account(&log1);
    let bump = env.rig(&u.rig).header.bump;

    // Close: the rig shrinks to a tombstone; the wallet gets the rest.
    let before = env.lamports(&w.pubkey());
    let meta = ok(env.send_as(&w, &[ix_close_rig(&w.pubkey(), None)], &[]));
    assert_eq!(events(&meta.logs), vec![Event::RigClosed { rig: u.rig }]);
    assert_eq!(env.rig_slot(&u.rig), RigSlot::Tombstone);
    let acc = env.account(&u.rig);
    assert_eq!((acc.owner, acc.data.len(), acc.lamports), (HD, 32, TOMBSTONE_RENT));
    let t = env.tombstone(&u.rig);
    assert_eq!((t.header.tag, t.header.version, t.header.bump), (10, 1, bump));
    assert_eq!((t.shift_id.get(), t.hb_counter.get()), (1, 1));
    assert_eq!(t.last_dug_round.get(), 0, "this rig never dug");
    assert_eq!(
        env.lamports(&w.pubkey()),
        before + RIG_RENT - TOMBSTONE_RENT - meta.fee
    );

    // Re-register: the SAME instruction a first registration uses. The rig
    // grows back and resumes; the wallet pays only the rent difference.
    env.advance_time(600);
    let before = env.lamports(&w.pubkey());
    let ix = ix_register_rig(&w.pubkey(), &u.p256(), None);
    let meta = ok(env.send_as(&w, std::slice::from_ref(&ix), &[]));
    assert_eq!(
        events(&meta.logs),
        vec![Event::RigRegistered {
            rig: u.rig,
            authority: w.pubkey(),
            tier: 0,
            attestation_level: 0,
        }]
    );
    assert_eq!(
        env.lamports(&w.pubkey()),
        before - (RIG_RENT - TOMBSTONE_RENT) - meta.fee
    );
    assert_eq!(env.rig_slot(&u.rig), RigSlot::Rig);
    assert_eq!(env.lamports(&u.rig), RIG_RENT);
    let rig = env.rig(&u.rig);
    assert_eq!((rig.shift_id.get(), rig.hb_counter.get()), (1, 1));
    // Everything else starts fresh, exactly as a first registration.
    assert_eq!(rig.header.bump, bump);
    assert_eq!(rig.authority, w.pubkey().to_bytes());
    assert_eq!(rig.p256_pubkey, u.p256());
    assert_eq!((rig.state, rig.tier, rig.shift_open), (rig_state::IDLE, 0, 0));
    assert_eq!(rig.freezes_left, 2);
    assert_eq!(rig.week_start_ts.get(), env.now);
    assert_eq!(
        (rig.cap_week.get(), rig.caps_expiry_ts.get(), rig.streak.get()),
        (0, 0, 0)
    );
    assert_eq!(
        (rig.lifetime_dark_rounds.get(), rig.lease_to_round.get()),
        (0, 0)
    );
    assert_eq!(rig.reserved, [0; 32]);
    // Registering again is still refused.
    assert_ix_err(
        &env.send_as(&w, &[ix], &[]),
        0,
        InstructionError::AccountAlreadyInitialized,
    );

    // Arm: shift 2, not shift 1 again. Under v1.2 the id restarted at 1 and
    // `end_shift` failed on the ShiftLog the first life left there.
    let meta = ok(env.send_as(
        &w,
        &[
            ix_set_caps(&w.pubkey(), Caps::standard()),
            ix_arm_wallet(&w.pubkey(), &standard_plan()),
        ],
        &[],
    ));
    assert_eq!(
        events(&meta.logs),
        vec![Event::ShiftArmed {
            rig: u.rig,
            shift_id: 2
        }]
    );
    record(&mut env, &mut u, 1);
    assert_eq!(env.rig(&u.rig).hb_counter.get(), 2);
    let meta = ok(env.send_as(&w, &[ix_end_shift(&w.pubkey(), &u.rig, 2)], &[]));
    assert!(events(&meta.logs)
        .iter()
        .any(|e| matches!(e, Event::ShiftEndedV2 { shift_id: 2, .. })));
    let log2 = env.shift_log(&shift_log_pda(&u.rig, 2));
    assert_eq!((log2.shift_id.get(), log2.rig), (2, u.rig.to_bytes()));
    assert_eq!(log2.start_ts.get(), env.now);
    // The first life's log is untouched.
    assert_eq!(env.account(&log1), log1_before);

    // Closing again updates the tombstone; a third life resumes from it.
    ok(env.send_as(&w, &[ix_close_rig(&w.pubkey(), None)], &[]));
    let t = env.tombstone(&u.rig);
    assert_eq!((t.shift_id.get(), t.hb_counter.get()), (2, 2));
    ok(re_register(&mut env, &u, &standard_plan()));
    assert_eq!(env.rig(&u.rig).shift_id.get(), 3);
}

/// The tombstone also keeps `last_dug_round`. Without it, ending a shift,
/// closing and re-registering inside one ORE round reset the once-per-round
/// rule, so one wallet's Automation could be dug twice in a round (past the
/// wallet-signed per-round cap, and paying the executor fee twice).
#[test]
fn closing_and_re_registering_inside_a_round_cannot_dig_twice() {
    let mut env = Env::new();
    let mut u = User::new(&mut env, 7);
    env.onboard_standard(&u);
    let w = u.wallet.insecure_clone();
    let meta = ok(env.dig_fresh(&mut [&mut u]));
    assert!(dug(&events(&meta.logs), &u.rig).is_some());
    let round = env.board_round;
    assert_eq!(env.rig(&u.rig).last_dug_round.get(), round);

    ok(env.send_as(&w, &[ix_end_shift(&w.pubkey(), &u.rig, 1)], &[]));
    ok(env.send_as(&w, &[ix_close_rig(&w.pubkey(), None)], &[]));
    assert_eq!(env.tombstone(&u.rig).last_dug_round.get(), round);
    ok(re_register(&mut env, &u, &standard_plan()));
    let rig = env.rig(&u.rig);
    assert_eq!((rig.shift_id.get(), rig.last_dug_round.get()), (2, round));

    // Same round, new life: the dig is skipped, not paid for a second time.
    let meta = ok(env.dig_fresh(&mut [&mut u]));
    let evs = events(&meta.logs);
    assert_eq!(
        skipped_code(&evs, &u.rig),
        Some(HdError::AlreadyDugRound.code())
    );
    assert!(dug(&evs, &u.rig).is_none());
}

#[test]
fn a_rig_that_never_armed_nor_signed_closes_completely() {
    let mut env = Env::new();
    let mut u = User::new(&mut env, 2);
    let w = u.wallet.insecure_clone();
    ok(env.send_as(
        &w,
        &[
            ix_register_rig(&w.pubkey(), &u.p256(), None),
            ix_set_caps(&w.pubkey(), Caps::standard()),
        ],
        &[],
    ));
    let rent = env.lamports(&u.rig);
    let before = env.lamports(&w.pubkey());
    let meta = ok(env.send_as(&w, &[ix_close_rig(&w.pubkey(), None)], &[]));
    // Nothing to remember: the account is gone and every lamport is back.
    assert_eq!(env.rig_slot(&u.rig), RigSlot::Empty);
    assert_eq!(env.lamports(&w.pubkey()), before + rent - meta.fee);
    ok(env.send_as(&w, &[ix_register_rig(&w.pubkey(), &u.p256(), None)], &[]));
    let rig = env.rig(&u.rig);
    assert_eq!((rig.shift_id.get(), rig.hb_counter.get()), (0, 0));

    // A phone FREEZE on a rig that never armed still consumes a counter, so
    // that rig does leave a tombstone (shift id 0, counter 1).
    let c = u.next_counter();
    let (d, s) = signal_signature(&u, kind::FREEZE, c, 0, break_reason::FREEZE);
    ok(env.send(
        &[
            secp_ix(&[(s, u.p256(), d.to_vec())]),
            ix_freeze_p256(&w.pubkey(), break_reason::FREEZE, c, 0, 0),
        ],
        &[],
    ));
    ok(env.send_as(&w, &[ix_close_rig(&w.pubkey(), None)], &[]));
    assert_eq!(env.rig_slot(&u.rig), RigSlot::Tombstone);
    let t = env.tombstone(&u.rig);
    assert_eq!((t.shift_id.get(), t.hb_counter.get()), (0, 1));
    ok(env.send_as(&w, &[ix_register_rig(&w.pubkey(), &u.p256(), None)], &[]));
    // The same FREEZE cannot be replayed on the new rig.
    let res = env.send(
        &[
            secp_ix(&[(s, u.p256(), d.to_vec())]),
            ix_freeze_p256(&w.pubkey(), break_reason::FREEZE, c, 0, 0),
        ],
        &[],
    );
    assert_hd(&res, 1, HdError::StaleHeartbeat);
    assert_eq!(env.rig(&u.rig).state, rig_state::IDLE);
}

#[test]
fn old_p256_messages_cannot_be_replayed_on_a_re_registered_rig() {
    let mut env = Env::new();
    let mut u = User::new(&mut env, 3);
    let w = u.wallet.insecure_clone();
    let plan = standard_plan();
    // First life: the phone arms shift 1 (PLAN, counter 1), heartbeats
    // (counter 2) and signs a pickup BREAK (counter 3) and a FREEZE (counter 4).
    ok(env.send_as(
        &w,
        &[
            ore_automate_default(&w.pubkey(), SOL / 20),
            ix_register_rig(&w.pubkey(), &u.p256(), None),
            ix_set_caps(&w.pubkey(), Caps::standard()),
        ],
        &[],
    ));
    let c_plan = u.next_counter();
    let (d_plan, s_plan) = plan_signature(&u, c_plan, &plan);
    let arm = [
        secp_ix(&[(s_plan, u.p256(), d_plan.to_vec())]),
        ix_arm_p256(&w.pubkey(), &plan, c_plan, 0, 0),
    ];
    ok(env.send(&arm, &[]));
    let hb = u.heartbeat(1, env.board_round, 1);
    let beat = [
        secp_ix_for(&[hb]),
        ix_record(&[(u.rig, entry_for(&hb, 0, 0))]),
    ];
    ok(env.send(&beat, &[]));
    let c_break = u.next_counter();
    let (d, s) = signal_signature(&u, kind::BREAK, c_break, 1, break_reason::PICKUP);
    let brk = [
        secp_ix(&[(s, u.p256(), d.to_vec())]),
        ix_break_p256(&w.pubkey(), break_reason::PICKUP, c_break, 0, 0),
    ];
    ok(env.send(&brk, &[]));
    let c_freeze = u.next_counter();
    let (d, s) = signal_signature(&u, kind::FREEZE, c_freeze, 1, break_reason::FREEZE);
    let frz = [
        secp_ix(&[(s, u.p256(), d.to_vec())]),
        ix_freeze_p256(&w.pubkey(), break_reason::FREEZE, c_freeze, 0, 0),
    ];
    ok(env.send(&frz, &[]));
    assert_eq!(env.rig(&u.rig).hb_counter.get(), 4);

    // Close (Frozen, shift 1 still open) and re-register with the same key.
    ok(env.send_as(&w, &[ix_close_rig(&w.pubkey(), None)], &[]));
    ok(env.send_as(
        &w,
        &[
            ix_register_rig(&w.pubkey(), &u.p256(), None),
            ix_set_caps(&w.pubkey(), Caps::standard()),
        ],
        &[],
    ));
    let rig = env.rig(&u.rig);
    assert_eq!((rig.shift_id.get(), rig.hb_counter.get()), (1, 4));

    // The old PLAN (whose preimage carries no shift id) cannot arm the new
    // rig: its counter is not above the one the tombstone kept.
    assert_hd(&env.send(&arm, &[]), 1, HdError::StaleHeartbeat);
    assert_eq!(env.rig(&u.rig).state, rig_state::IDLE);
    // The wallet arms: shift 2. The old shift-1 messages are stale by counter
    // and, were the counter ever reset, would still name the wrong shift.
    ok(env.send_as(&w, &[ix_arm_wallet(&w.pubkey(), &plan)], &[]));
    assert_eq!(env.rig(&u.rig).shift_id.get(), 2);
    let meta = ok(env.send(&beat, &[]));
    assert_eq!(
        skipped_code(&events(&meta.logs), &u.rig),
        Some(HdError::StaleHeartbeat.code())
    );
    assert_hd(&env.send(&brk, &[]), 1, HdError::StaleHeartbeat);
    assert_hd(&env.send(&frz, &[]), 1, HdError::StaleHeartbeat);
    let rig = env.rig(&u.rig);
    assert_eq!(
        (rig.state, rig.break_reason, rig.hb_counter.get()),
        (rig_state::ARMED, 0, 4)
    );
    // A BREAK re-signed for shift 1 with a fresh counter names the wrong shift.
    let c = u.next_counter();
    let (d, s) = signal_signature(&u, kind::BREAK, c, 1, break_reason::PICKUP);
    let res = env.send(
        &[
            secp_ix(&[(s, u.p256(), d.to_vec())]),
            ix_break_p256(&w.pubkey(), break_reason::PICKUP, c, 0, 0),
        ],
        &[],
    );
    assert_custom(&res, 1, IntrospectError::MessageMismatch.code());
    // The phone simply carries on from its own counter.
    record(&mut env, &mut u, 1);
    let rig = env.rig(&u.rig);
    assert_eq!((rig.state, rig.hb_counter.get()), (rig_state::DOWN, 6));
}

#[test]
fn a_stack_seat_cannot_wash_a_break_by_re_registering_its_rig() {
    let mut env = Env::new();
    env.init_bury_vault();
    let mut plan = standard_plan();
    plan.lease = 1;
    plan.flags = plan_flags::FOCUS_ONLY;
    let mut cheat = User::new(&mut env, 4);
    let mut honest = User::new(&mut env, 5);
    for u in [&cheat, &honest] {
        ok(env.onboard(u, SOL / 20, Caps::standard(), &plan));
        env.fund_skr(&u.pubkey(), 1_000 * ONE_SKR);
    }
    let r0 = env.board_round;
    let p = StackParams {
        table_id: 1,
        bond: 100 * ONE_SKR,
        start_round: r0 + 1,
        end_round: r0 + 3,
        grace_gaps: 1,
        flags: 0,
        max_seats: 4,
    };
    let table = table_pda(&honest.pubkey(), 1);
    let wh = honest.wallet.insecure_clone();
    ok(env.send_as(
        &wh,
        &[
            ix_create_ata(&wh.pubkey(), &table, &SKR_MINT),
            ix_open_stack(&wh.pubkey(), &p),
        ],
        &[],
    ));
    for u in [&cheat, &honest] {
        let w = u.wallet.insecure_clone();
        ok(env.send_as(
            &w,
            &[ix_join_stack(&w.pubkey(), &table, &u.rig, None)],
            &[],
        ));
    }
    let seat = |u: &User| stack_seat_pda(&table, &u.rig);
    let checkin = |env: &mut Env, us: &mut [&mut User]| {
        let round = env.board_round;
        let mut hbs = vec![];
        let mut seats = vec![];
        for (i, u) in us.iter_mut().enumerate() {
            let shift = env.rig(&u.rig).shift_id.get();
            let hb = u.heartbeat(shift, round, 1);
            seats.push((stack_seat_pda(&table, &u.rig), u.rig, entry_for(&hb, 0, i as u8)));
            hbs.push(hb);
        }
        let meta = ok(env.send(&[secp_ix_for(&hbs), ix_stack_checkin(&table, &seats)], &[]));
        events(&meta.logs)
            .into_iter()
            .filter_map(|e| match e {
                Event::StackCheckin { rig, result, .. } => Some((rig, result)),
                _ => None,
            })
            .collect::<Vec<_>>()
    };

    // Round 1: both seats bind to shift 1.
    env.set_board_round(r0 + 1);
    let res = checkin(&mut env, &mut [&mut cheat, &mut honest]);
    assert_eq!(res, vec![(cheat.rig, 0), (honest.rig, 0)]);
    assert_eq!(env.stack_seat(&seat(&cheat)).shift_id.get(), 1);

    // The cheat picks the phone up (a BREAK lands), then tries to wash it:
    // freeze, close, re-register, arm a clean shift, phone back down.
    let c = cheat.next_counter();
    let (d, s) = signal_signature(&cheat, kind::BREAK, c, 1, break_reason::PICKUP);
    let wc = cheat.wallet.insecure_clone();
    ok(env.send(
        &[
            secp_ix(&[(s, cheat.p256(), d.to_vec())]),
            ix_break_p256(&wc.pubkey(), break_reason::PICKUP, c, 0, 0),
        ],
        &[],
    ));
    ok(env.send_as(
        &wc,
        &[
            ix_freeze_wallet(&wc.pubkey()),
            ix_close_rig(&wc.pubkey(), None),
            ix_register_rig(&wc.pubkey(), &cheat.p256(), None),
            ix_set_caps(&wc.pubkey(), Caps::standard()),
            ix_arm_wallet(&wc.pubkey(), &plan),
        ],
        &[],
    ));
    let rig = env.rig(&cheat.rig);
    // A clean-looking shift, but not the one the seat is bound to.
    assert_eq!((rig.break_reason, rig.state, rig.shift_id.get()), (0, rig_state::ARMED, 2));

    for round in [r0 + 2, r0 + 3] {
        env.set_board_round(round);
        let res = checkin(&mut env, &mut [&mut cheat, &mut honest]);
        assert_eq!(
            res,
            vec![
                (cheat.rig, HdError::StackShiftMismatch.code()),
                (honest.rig, 0)
            ]
        );
    }
    let s = env.stack_seat(&seat(&cheat));
    assert_eq!((s.checked_rounds.get(), s.last_round.get()), (1, r0 + 1));

    env.set_board_round(r0 + 4);
    let meta = ok(env.send(
        &[ix_settle_stack(&table, &[seat(&cheat), seat(&honest)])],
        &[],
    ));
    assert!(events(&meta.logs).iter().any(|e| matches!(
        e,
        Event::StackSettled {
            finishers: 1,
            seats: 2,
            ..
        }
    )));
    assert_eq!(env.stack_seat(&seat(&cheat)).payout.get(), 0);
    assert_eq!(
        env.stack_seat(&seat(&honest)).payout.get(),
        100 * ONE_SKR + 80 * ONE_SKR
    );
}

#[test]
fn a_tombstone_is_not_a_rig_and_only_register_rig_can_use_it() {
    let mut env = Env::new();
    env.init_bury_vault();
    let mut u = User::new(&mut env, 6);
    env.onboard_standard(&u);
    let w = u.wallet.insecure_clone();
    ok(env.send_as(
        &w,
        &[
            ix_end_shift(&w.pubkey(), &u.rig, 1),
            ix_close_rig(&w.pubkey(), None),
        ],
        &[],
    ));
    assert_eq!(env.rig_slot(&u.rig), RigSlot::Tombstone);

    // Every instruction that takes a Rig refuses the tombstone.
    for ix in [
        ix_set_caps(&w.pubkey(), Caps::standard()),
        ix_arm_wallet(&w.pubkey(), &standard_plan()),
        ix_freeze_wallet(&w.pubkey()),
        ix_unfreeze(&w.pubkey(), true),
        ix_break_wallet(&w.pubkey(), break_reason::MANUAL),
        ix_close_rig(&w.pubkey(), None),
        ix_rotate_key(&w.pubkey(), &u.p256(), None),
        ix_end_shift(&w.pubkey(), &u.rig, 1),
    ] {
        assert_hd(&env.send_as(&w, &[ix], &[]), 0, HdError::InvalidAccountTag);
    }
    let hb = u.heartbeat(1, env.board_round, 1);
    let res = env.send(
        &[
            secp_ix_for(&[hb]),
            ix_record(&[(u.rig, entry_for(&hb, 0, 0))]),
        ],
        &[],
    );
    assert_hd(&res, 1, HdError::InvalidAccountTag);
    let res = env.dig_with(&[hb], &[DigRig::new(&u, entry_for(&hb, 1, 0))]);
    assert_hd(&res, 2, HdError::InvalidAccountTag);
    env.fund_skr(&w.pubkey(), 10 * ONE_SKR);
    let res = env.send_as(
        &w,
        &[
            ix_create_ata(&w.pubkey(), &bond_pda(&u.rig, 1), &SKR_MINT),
            ix_lock_focus_bond(&w.pubkey(), 1, ONE_SKR),
        ],
        &[],
    );
    assert_hd(&res, 1, HdError::InvalidAccountTag);

    // Only the rig's own wallet can bring it back, at its own PDA.
    let mallory = Keypair::new();
    env.svm.airdrop(&mallory.pubkey(), SOL).unwrap();
    let mut ix = ix_register_rig(&mallory.pubkey(), &u.p256(), None);
    ix.accounts[1] = AccountMeta::new(u.rig, false);
    assert_ix_err(
        &env.send_as(&mallory, &[ix], &[]),
        0,
        InstructionError::InvalidSeeds,
    );
    let mut ix = ix_register_rig(&w.pubkey(), &u.p256(), None);
    ix.accounts[0].is_signer = false;
    assert_ix_err(
        &env.send(&[ix], &[]),
        0,
        InstructionError::MissingRequiredSignature,
    );
    // The System program slot is checked on this path too.
    let mut ix = ix_register_rig(&w.pubkey(), &u.p256(), None);
    ix.accounts[3] = AccountMeta::new_readonly(ORE, false);
    assert_ix_err(
        &env.send_as(&w, &[ix], &[]),
        0,
        InstructionError::IncorrectProgramId,
    );
    assert_eq!(env.rig_slot(&u.rig), RigSlot::Tombstone);

    // Lamports sent to a tombstone count towards the next rig's rent and
    // come back at the next close (no lamport is ever stranded).
    ok(env.send(
        &[system_transfer(&env.cranker.pubkey(), &u.rig, RIG_RENT)],
        &[],
    ));
    let before = env.lamports(&w.pubkey());
    let meta = ok(env.send_as(&w, &[ix_register_rig(&w.pubkey(), &u.p256(), None)], &[]));
    assert_eq!(env.lamports(&w.pubkey()), before - meta.fee, "no top-up needed");
    assert_eq!(env.lamports(&u.rig), RIG_RENT + TOMBSTONE_RENT);
    let before = env.lamports(&w.pubkey());
    let meta = ok(env.send_as(&w, &[ix_close_rig(&w.pubkey(), None)], &[]));
    assert_eq!(env.lamports(&w.pubkey()), before + RIG_RENT - meta.fee);
    assert_eq!(env.lamports(&u.rig), TOMBSTONE_RENT);
}

#[test]
fn re_registering_with_a_voucher_and_a_new_phone_key_resumes_too() {
    let mut env = Env::new();
    let u = User::new(&mut env, 7);
    env.onboard_standard(&u);
    let w = u.wallet.insecure_clone();
    ok(env.send_as(
        &w,
        &[
            ix_end_shift(&w.pubkey(), &u.rig, 1),
            ix_close_rig(&w.pubkey(), None),
        ],
        &[],
    ));
    // A new phone: another Keystore key, vouched by the registrar.
    let phone2 = User::from_wallet(w.insecure_clone(), 77);
    let registrar = env.registrar.insecure_clone();
    let expiry = env.slot + 1_000;
    let (_, _, voucher) =
        vectors::registrar_voucher(&registrar, &w.pubkey(), &phone2.p256(), 2, expiry);
    ok(env.send_as(
        &w,
        &[
            voucher,
            ix_register_rig(
                &w.pubkey(),
                &phone2.p256(),
                Some(AttestationArg {
                    ix: 0,
                    sig: 0,
                    level: 2,
                    expiry_slot: expiry,
                }),
            ),
        ],
        &[],
    ));
    let rig = env.rig(&u.rig);
    assert_eq!(rig.p256_pubkey, phone2.p256());
    assert_eq!(
        (rig.attestation_level, rig.attestation_expiry_slot.get()),
        (2, expiry)
    );
    assert_eq!(rig.shift_id.get(), 1, "the shift id survives a new key");
    // A bad voucher still fails, and leaves the tombstone as it was.
    ok(env.send_as(&w, &[ix_close_rig(&w.pubkey(), None)], &[]));
    let (_, _, stale) =
        vectors::registrar_voucher(&registrar, &w.pubkey(), &phone2.p256(), 2, env.slot);
    let res = env.send_as(
        &w,
        &[
            stale,
            ix_register_rig(
                &w.pubkey(),
                &phone2.p256(),
                Some(AttestationArg {
                    ix: 0,
                    sig: 0,
                    level: 2,
                    expiry_slot: env.slot,
                }),
            ),
        ],
        &[],
    );
    assert_hd(&res, 1, HdError::InvalidAttestation);
    assert_eq!(env.rig_slot(&u.rig), RigSlot::Tombstone);
    assert_eq!(env.tombstone(&u.rig).shift_id.get(), 1);
}
