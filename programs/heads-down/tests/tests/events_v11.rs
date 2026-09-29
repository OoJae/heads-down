//! INTERFACE v1.1 additions: the events RigRegistered (6), RigClosed (7),
//! HeartbeatsRecorded (8), ShiftBroken (9) and ShiftEndedV2 (10), and the
//! BREAK reasons 7 unplugged (soft, → Cooling) and 8 unlocked (hard,
//! → Broken) used by the Android shift state machine.

use hd::{
    error::HdError,
    message::kind,
    state::{break_reason, plan_flags, rig_state},
};
use heads_down_tests::*;

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

fn break_p256(env: &mut Env, user: &mut User, reason: u8) -> TxResult {
    let counter = user.next_counter();
    let shift = env.rig(&user.rig).shift_id.get();
    let (d, s) = signal_signature(user, kind::BREAK, counter, shift, reason);
    env.send(
        &[
            secp_ix(&[(s, user.p256(), d.to_vec())]),
            ix_break_p256(&user.pubkey(), reason, counter, 0, 0),
        ],
        &[],
    )
}

#[test]
fn unplugged_cools_and_a_fresh_heartbeat_resumes() {
    let mut env = Env::new();
    let mut u = User::new(&mut env, 1);
    env.onboard_standard(&u);
    let r = env.board_round;
    ok(record(&mut env, &mut u, r, 1));

    let meta = ok(break_p256(&mut env, &mut u, break_reason::UNPLUGGED));
    assert_eq!(
        events(&meta.logs),
        vec![Event::ShiftBroken {
            rig: u.rig,
            shift_id: 1,
            reason: break_reason::UNPLUGGED
        }]
    );
    let rig = env.rig(&u.rig);
    assert_eq!(rig.state, rig_state::COOLING);
    assert_eq!(rig.break_reason, break_reason::UNPLUGGED);

    // Plugged back in and dark: a fresh heartbeat resumes the shift.
    let meta = ok(env.dig_fresh(&mut [&mut u]));
    assert!(dug(&events(&meta.logs), &u.rig).is_some());
    assert_eq!(env.rig(&u.rig).state, rig_state::DOWN);
}

#[test]
fn unlocked_breaks_hard_and_seals_with_reason_8() {
    let mut env = Env::new();
    let mut u = User::new(&mut env, 2);
    env.onboard_standard(&u);
    let r = env.board_round;
    ok(record(&mut env, &mut u, r, 3));

    // The wallet path carries the same reason byte.
    let w = u.wallet.insecure_clone();
    let meta = ok(env.send_as(
        &w,
        &[ix_break_wallet(&w.pubkey(), break_reason::UNLOCKED)],
        &[],
    ));
    assert_eq!(
        events(&meta.logs),
        vec![Event::ShiftBroken {
            rig: u.rig,
            shift_id: 1,
            reason: break_reason::UNLOCKED
        }]
    );
    assert_eq!(env.rig(&u.rig).state, rig_state::BROKEN);
    // Broken is final for the shift: a fresh heartbeat does not revive it.
    let meta = ok(record(&mut env, &mut u, r, 1));
    assert_eq!(
        skipped_code(&events(&meta.logs), &u.rig),
        Some(HdError::RigNotArmed.code())
    );

    let meta = ok(env.send_as(&w, &[ix_end_shift(&w.pubkey(), &u.rig, 1)], &[]));
    let log = env.shift_log(&shift_log_pda(&u.rig, 1));
    assert_eq!(log.break_reason, break_reason::UNLOCKED);
    let evs = events(&meta.logs);
    assert!(matches!(
        evs[..],
        [
            Event::ShiftEnded { reason: 8, .. },
            Event::ShiftEndedV2 {
                reason: 8,
                mode: 0,
                ..
            }
        ]
    ));
    // Not a completed shift: the streak does not move.
    assert_eq!(env.rig(&u.rig).streak.get(), 0);
}

#[test]
fn freeze_emits_shift_broken_only_when_it_interrupts_an_open_shift() {
    let mut env = Env::new();
    let mut u = User::new(&mut env, 3);
    env.onboard_standard(&u);
    let w = u.wallet.insecure_clone();

    // Open shift: FREEZE (P-256) breaks it with reason 3.
    let counter = u.next_counter();
    let (d, s) = signal_signature(&u, kind::FREEZE, counter, 1, 3);
    let meta = ok(env.send(
        &[
            secp_ix(&[(s, u.p256(), d.to_vec())]),
            ix_freeze_p256(&u.pubkey(), 3, counter, 0, 0),
        ],
        &[],
    ));
    assert_eq!(
        events(&meta.logs),
        vec![Event::ShiftBroken {
            rig: u.rig,
            shift_id: 1,
            reason: break_reason::FREEZE
        }]
    );
    // Freezing a frozen rig is idempotent and silent.
    let meta = ok(env.send_as(&w, &[ix_freeze_wallet(&w.pubkey())], &[]));
    assert!(events(&meta.logs).is_empty());

    // Unfreeze with the shift still open → Broken; seal it; the log and
    // ShiftEndedV2 carry reason freeze and the shift's rounds.
    ok(env.send_as(&w, &[ix_unfreeze(&w.pubkey(), true)], &[]));
    assert_eq!(env.rig(&u.rig).state, rig_state::BROKEN);
    let meta = ok(env.send_as(&w, &[ix_end_shift(&w.pubkey(), &u.rig, 1)], &[]));
    let r = env.board_round;
    assert_eq!(
        events(&meta.logs)[1],
        Event::ShiftEndedV2 {
            rig: u.rig,
            shift_id: 1,
            dark_rounds: 0,
            rounds_dug: 0,
            lamports: 0,
            reason: break_reason::FREEZE,
            start_round: r,
            end_round: r,
            mode: 0,
        }
    );

    // Idle: a FREEZE interrupts nothing, so no ShiftBroken.
    let meta = ok(env.send_as(&w, &[ix_freeze_wallet(&w.pubkey())], &[]));
    assert!(events(&meta.logs).is_empty());
}

#[test]
fn heartbeats_recorded_reports_only_the_rounds_it_added() {
    let mut env = Env::new();
    let mut plan = standard_plan();
    plan.flags = plan_flags::FOCUS_ONLY | plan_flags::DAY;
    let mut u = User::new(&mut env, 4);
    ok(env.onboard(&u, SOL / 20, Caps::standard(), &plan));
    let r = env.board_round;
    let meta = ok(record(&mut env, &mut u, r, 3));
    assert_eq!(
        events(&meta.logs),
        vec![Event::HeartbeatsRecorded {
            rig: u.rig,
            round_id: r,
            dark_rounds_added: 3
        }]
    );
    // A later heartbeat whose lease ends inside the current one: accepted
    // (counter consumed) but adds nothing.
    let meta = ok(record(&mut env, &mut u, r, 1));
    assert_eq!(
        events(&meta.logs),
        vec![Event::HeartbeatsRecorded {
            rig: u.rig,
            round_id: r,
            dark_rounds_added: 0
        }]
    );
    assert_eq!(env.rig(&u.rig).hb_counter.get(), 2);

    // The day focus-only shift ends with mode 2 in ShiftEndedV2.
    let w = u.wallet.insecure_clone();
    let meta = ok(env.send_as(&w, &[ix_end_shift(&w.pubkey(), &u.rig, 1)], &[]));
    assert!(matches!(
        events(&meta.logs)[..],
        [
            Event::ShiftEnded { .. },
            Event::ShiftEndedV2 {
                mode: 2,
                dark_rounds: 1,
                ..
            }
        ]
    ));
}

#[test]
fn break_reason_codes_map_to_the_documented_states() {
    // reason -> state after BREAK (from Down).
    let cases = [
        (break_reason::PICKUP, Some(rig_state::COOLING)),
        (break_reason::SCREEN_ON, Some(rig_state::COOLING)),
        (break_reason::UNPLUGGED, Some(rig_state::COOLING)),
        (break_reason::LEASE_LAPSE, Some(rig_state::BROKEN)),
        (break_reason::BUDGET, Some(rig_state::BROKEN)),
        (break_reason::MANUAL, Some(rig_state::BROKEN)),
        (break_reason::UNLOCKED, Some(rig_state::BROKEN)),
        (break_reason::COMPLETED, None),
        (break_reason::FREEZE, None),
        (9, None),
    ];
    let mut env = Env::new();
    for (i, (reason, want)) in cases.into_iter().enumerate() {
        let mut u = User::new(&mut env, 10 + i as u8);
        env.onboard_standard(&u);
        let r = env.board_round;
        ok(record(&mut env, &mut u, r, 1));
        let res = break_p256(&mut env, &mut u, reason);
        match want {
            Some(state) => {
                ok(res);
                assert_eq!(env.rig(&u.rig).state, state, "reason {reason}");
            }
            None => assert_hd(&res, 1, HdError::InvalidInstruction),
        }
    }
}
