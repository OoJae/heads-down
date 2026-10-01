//! Measurements for INTERFACE.md §12.11: compute units of the v1.3
//! instructions and of the two existing ones whose work changed
//! (`close_rig`, `register_rig`), and what the attestation re-check adds to
//! a `stack_checkin`. Run with `--nocapture` to see the numbers; the asserts
//! are loose ceilings, not golden values (PDA bump searches vary with the
//! random test keys).

use hd::{
    instructions::{governance::TIMELOCK_SLOTS, shift_log::SHIFT_LOG_TTL_SECS},
    state::{plan_flags, stack_flags},
};
use heads_down_tests::*;

#[test]
fn governance_tombstone_and_shift_log_instructions_are_cheap() {
    let mut env = Env::new();
    let gov = env.governance.insecure_clone();
    let new = Keypair::new();
    env.svm.airdrop(&new.pubkey(), SOL).unwrap();

    let meta = ok(env.send_as(
        &gov,
        &[ix_propose_governance(&gov.pubkey(), &new.pubkey())],
        &[],
    ));
    println!("propose_governance: {} CU", meta.compute_units_consumed);
    assert!(meta.compute_units_consumed < 2_000);
    let meta = ok(env.send_as(&gov, &[ix_cancel_governance(&gov.pubkey())], &[]));
    println!("cancel_governance: {} CU", meta.compute_units_consumed);
    assert!(meta.compute_units_consumed < 2_000);
    ok(env.send_as(
        &gov,
        &[ix_propose_governance(&gov.pubkey(), &new.pubkey())],
        &[],
    ));
    env.advance_slots(TIMELOCK_SLOTS);
    let meta = ok(env.send_as(&new, &[ix_accept_governance(&new.pubkey())], &[]));
    println!("accept_governance: {} CU", meta.compute_units_consumed);
    assert!(meta.compute_units_consumed < 2_000);

    // A rig that armed a shift: close (tombstone), register again (resume).
    let u = User::new(&mut env, 1);
    let w = u.wallet.insecure_clone();
    let meta = ok(env.send_as(&w, &[ix_register_rig(&w.pubkey(), &u.p256(), None)], &[]));
    let fresh = meta.compute_units_consumed;
    println!("register_rig (fresh): {fresh} CU");
    ok(env.send_as(
        &w,
        &[
            ore_automate_default(&w.pubkey(), SOL / 20),
            ix_set_caps(&w.pubkey(), Caps::standard()),
            ix_arm_wallet(&w.pubkey(), &standard_plan()),
        ],
        &[],
    ));
    let meta = ok(env.send_as(&w, &[ix_end_shift(&w.pubkey(), &u.rig, 1)], &[]));
    println!("end_shift (authority, records the payer): {} CU", meta.compute_units_consumed);
    let meta = ok(env.send_as(&w, &[ix_close_rig(&w.pubkey(), None)], &[]));
    println!("close_rig (leaves a tombstone): {} CU", meta.compute_units_consumed);
    assert!(meta.compute_units_consumed < 3_000);
    let meta = ok(env.send_as(&w, &[ix_register_rig(&w.pubkey(), &u.p256(), None)], &[]));
    println!(
        "register_rig (resumes from the tombstone): {} CU",
        meta.compute_units_consumed
    );
    // The resume path costs about what a fresh registration does (both are
    // dominated by the two ORE bump searches).
    assert!(meta.compute_units_consumed < fresh + 5_000);

    // close_shift_log: one bump search for the bond PDA; a pre-v1.3 log adds
    // one more to prove the authority.
    env.advance_time(SHIFT_LOG_TTL_SECS);
    let log = shift_log_pda(&u.rig, 1);
    let saved = env.account(&log);
    let meta = ok(env.send(&[ix_close_shift_log(&u.rig, 1, &w.pubkey())], &[]));
    println!("close_shift_log: {} CU", meta.compute_units_consumed);
    assert!(meta.compute_units_consumed < 30_000);
    let mut legacy = saved;
    legacy.data[112..128].fill(0);
    env.svm.set_account(log, legacy).unwrap();
    let meta = ok(env.send(&[ix_close_shift_log(&u.rig, 1, &w.pubkey())], &[]));
    println!("close_shift_log (pre-v1.3 log): {} CU", meta.compute_units_consumed);
    assert!(meta.compute_units_consumed < 60_000);
}

/// A four-seat verify-mode check-in (the most that fits a 1,232-byte packet)
/// at a plain table and at an attested-only one.
#[test]
fn the_attestation_recheck_adds_little_to_a_check_in() {
    let mut cu = vec![];
    for flags in [0, stack_flags::ATTESTED_ONLY] {
        let mut env = Env::new();
        let mut plan = standard_plan();
        plan.lease = 1;
        plan.flags = plan_flags::FOCUS_ONLY;
        let mut users = vec![];
        for i in 0..4u8 {
            let u = User::new(&mut env, i + 1);
            ok(env.onboard(&u, SOL / 20, Caps::standard(), &plan));
            env.fund_skr(&u.pubkey(), 1_000 * ONE_SKR);
            env.set_attestation(&u.rig, 1, env.slot + 1_000_000);
            users.push(u);
        }
        let host = users[0].wallet.insecure_clone();
        let round = env.board_round + 1;
        let p = StackParams {
            table_id: 1,
            bond: 100 * ONE_SKR,
            start_round: round,
            end_round: round,
            grace_gaps: 0,
            flags,
            max_seats: 8,
        };
        let table = table_pda(&host.pubkey(), 1);
        ok(env.send_as(
            &host,
            &[
                ix_create_ata(&host.pubkey(), &table, &SKR_MINT),
                ix_open_stack(&host.pubkey(), &p),
            ],
            &[],
        ));
        for u in &users {
            let w = u.wallet.insecure_clone();
            ok(env.send_as(
                &w,
                &[ix_join_stack(&w.pubkey(), &table, &u.rig, None)],
                &[],
            ));
        }
        env.set_board_round(round);
        let mut hbs = vec![];
        let mut seats = vec![];
        for (i, u) in users.iter_mut().enumerate() {
            let hb = u.heartbeat(1, round, 1);
            seats.push((stack_seat_pda(&table, &u.rig), u.rig, entry_for(&hb, 0, i as u8)));
            hbs.push(hb);
        }
        let meta = ok(env.send(&[secp_ix_for(&hbs), ix_stack_checkin(&table, &seats)], &[]));
        assert_eq!(
            events(&meta.logs)
                .iter()
                .filter(|e| matches!(e, Event::StackCheckin { result: 0, .. }))
                .count(),
            4
        );
        cu.push(meta.compute_units_consumed);
    }
    println!(
        "stack_checkin verify mode, 4 seats: {} CU at a plain table, {} CU at an attested-only table",
        cu[0], cu[1]
    );
    assert!(cu[1] < cu[0] + 1_000);
}
