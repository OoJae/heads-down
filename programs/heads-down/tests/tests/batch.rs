//! Batched digs: rigs that fail the gate, the lease or an ORE pre-flight
//! are skipped with an event while the others deploy; account-level
//! problems (duplicate rig) fail the whole transaction.

use hd::{error::HdError, state::rig_state};
use heads_down_tests::*;

#[test]
fn failing_rigs_are_skipped_and_the_rest_deploy() {
    let mut env = Env::new();
    let mut good = User::new(&mut env, 1);
    let mut gated = User::new(&mut env, 2);
    let no_lease = User::new(&mut env, 3);
    let mut unchecked = User::new(&mut env, 4);
    let mut broke = User::new(&mut env, 5);
    let mut good2 = User::new(&mut env, 6);

    env.onboard_standard(&good);
    env.onboard_standard(&good2);
    // Gate closed: the rig's plan threshold is below the pinned ema_ev (~0.65 SOL/ORE).
    let mut tight = standard_plan();
    tight.max_ev_cost = env.ema_ev() - 1;
    ok(env.onboard(&gated, SOL / 20, Caps::standard(), &tight));
    env.onboard_standard(&no_lease);
    env.onboard_standard(&unchecked);
    // Balance cannot cover 10 x 100_000 + fee.
    ok(env.onboard(&broke, 600_000, Caps::standard(), &standard_plan()));
    // ORE pre-flight: the Miner still holds an un-checkpointed older round
    // (ORE would assert!-panic and abort the whole transaction).
    let m = unchecked.miner();
    env.poke_u64(&m, 664, env.board_round - 5); // miner.round_id
    env.poke_u64(&m, 48, env.board_round - 6); // miner.checkpoint_id

    let r = env.board_round;
    let hbs: Vec<Heartbeat> = [
        &mut good,
        &mut gated,
        &mut unchecked,
        &mut broke,
        &mut good2,
    ]
    .into_iter()
    .map(|u| u.heartbeat(1, r, 3))
    .collect();
    let rigs = vec![
        DigRig::new(&good, entry_for(&hbs[0], 1, 0)),
        DigRig::new(&gated, entry_for(&hbs[1], 1, 1)),
        DigRig::new(&no_lease, reuse_lease()),
        DigRig::new(&unchecked, entry_for(&hbs[2], 1, 2)),
        DigRig::new(&broke, entry_for(&hbs[3], 1, 3)),
        DigRig::new(&good2, entry_for(&hbs[4], 1, 4)),
    ];
    let exec_before = env.lamports(&EXECUTOR);
    let crank_before = env.lamports(&env.cranker.pubkey());
    let meta = ok(env.dig_with(&hbs, &rigs));
    println!(
        "batch of 6 (2 dug, 4 skipped): {} CU",
        meta.compute_units_consumed
    );
    let evs = events(&meta.logs);

    assert!(dug(&evs, &good.rig).is_some());
    assert!(dug(&evs, &good2.rig).is_some());
    assert_eq!(
        skipped_code(&evs, &gated.rig),
        Some(HdError::CostGate.code())
    );
    assert_eq!(
        skipped_code(&evs, &no_lease.rig),
        Some(HdError::LeaseExpired.code())
    );
    assert_eq!(
        skipped_code(&evs, &unchecked.rig),
        Some(HdError::MinerNotCheckpointed.code())
    );
    assert_eq!(
        skipped_code(&evs, &broke.rig),
        Some(HdError::InsufficientAutomationBalance.code())
    );
    assert_eq!(evs.len(), 6, "one event per rig");

    // Skipped rigs spent nothing and their Automations are untouched...
    for u in [&gated, &no_lease, &unchecked, &broke] {
        let rig = env.rig(&u.rig);
        assert_eq!(rig.spent_shift.get(), 0);
        assert_eq!(rig.last_dug_round.get(), 0);
    }
    assert_eq!(
        env.automation(&broke.automation()).unwrap().balance,
        600_000
    );
    // ...but a verified heartbeat is still consumed and grants its lease.
    let g = env.rig(&gated.rig);
    assert_eq!(g.hb_counter.get(), 1);
    assert_eq!(g.state, rig_state::DOWN);
    assert_eq!(g.shift_dark_rounds.get(), 3);

    // Exactly two reimbursements; the executor gained two fees.
    assert_eq!(
        env.lamports(&env.cranker.pubkey()),
        crank_before - meta.fee + 2 * CRANK_FEE
    );
    assert_eq!(
        env.lamports(&EXECUTOR),
        exec_before + 2 * (EXECUTOR_FEE - CRANK_FEE)
    );
}

#[test]
fn a_rig_twice_in_one_batch_fails_the_transaction() {
    let mut env = Env::new();
    let mut user = User::new(&mut env, 7);
    env.onboard_standard(&user);
    let r = env.board_round;
    let a = user.heartbeat(1, r, 3);
    let b = user.heartbeat(1, r, 3);
    let rigs = vec![
        DigRig::new(&user, entry_for(&a, 1, 0)),
        DigRig::new(&user, entry_for(&b, 1, 1)),
    ];
    let res = env.dig_with(&[a, b], &rigs);
    assert_hd(&res, 2, HdError::DuplicateRig);
    // Nothing happened.
    assert_eq!(env.rig(&user.rig).hb_counter.get(), 0);
}

#[test]
fn a_rig_revoking_its_executor_mid_flight_only_skips_itself() {
    let mut env = Env::new();
    let mut a = User::new(&mut env, 8);
    let mut b = User::new(&mut env, 9);
    env.onboard_standard(&a);
    env.onboard_standard(&b);
    // User b re-points its ORE executor away from heads_down (as a user can
    // at any time between the crank's simulation and landing).
    let other = Keypair::new().pubkey();
    let w = b.wallet.insecure_clone();
    ok(env.send_as(
        &w,
        &[ore_automate(
            &w.pubkey(),
            &other,
            TILE_CAP,
            0,
            EXECUTOR_FEE,
            2,
            0,
            u16::MAX,
        )],
        &[],
    ));
    let meta = ok(env.dig_fresh(&mut [&mut a, &mut b]));
    let evs = events(&meta.logs);
    assert!(dug(&evs, &a.rig).is_some());
    assert_eq!(
        skipped_code(&evs, &b.rig),
        Some(HdError::InvalidExecutor.code())
    );

    // Fully revoked (ORE closes the Automation back to the user): also a skip.
    let mut c = User::new(&mut env, 10);
    env.onboard_standard(&c);
    let wc = c.wallet.insecure_clone();
    ok(env.send_as(
        &wc,
        &[ore_automate(
            &wc.pubkey(),
            &Address::default(),
            0,
            0,
            0,
            2,
            0,
            u16::MAX,
        )],
        &[],
    ));
    assert!(env.automation(&c.automation()).is_none());
    let meta = ok(env.dig_fresh(&mut [&mut c]));
    assert_eq!(
        skipped_code(&events(&meta.logs), &c.rig),
        Some(HdError::InvalidExecutor.code())
    );
}

/// A rig closed after the crank planned its batch (a tombstone, or nothing at
/// the PDA any more) only skips itself. As a failure it let one wallet, by
/// closing its rig just before the batch landed, make every other rig in it
/// miss the round.
#[test]
fn a_rig_closed_mid_flight_only_skips_itself() {
    let mut env = Env::new();
    let mut honest = User::new(&mut env, 11);
    let mut closed = User::new(&mut env, 12); // armed a shift: closing leaves a tombstone
    let mut gone = User::new(&mut env, 13); // never armed nor signed: closing leaves nothing
    env.onboard_standard(&honest);
    env.onboard_standard(&closed);
    let wg = gone.wallet.insecure_clone();
    ok(env.send_as(
        &wg,
        &[ix_register_rig(&wg.pubkey(), &gone.p256(), None)],
        &[],
    ));

    // The crank planned all three and holds their heartbeats; then two wallets
    // close their rigs before the batch lands.
    let r = env.board_round;
    let hbs = [
        honest.heartbeat(1, r, 3),
        closed.heartbeat(1, r, 3),
        gone.heartbeat(0, r, 3),
    ];
    let wc = closed.wallet.insecure_clone();
    ok(env.send_as(
        &wc,
        &[
            ix_end_shift(&wc.pubkey(), &closed.rig, 1),
            ix_close_rig(&wc.pubkey(), None),
        ],
        &[],
    ));
    ok(env.send_as(&wg, &[ix_close_rig(&wg.pubkey(), None)], &[]));
    assert_eq!(env.rig_slot(&closed.rig), RigSlot::Tombstone);
    assert_eq!(env.rig_slot(&gone.rig), RigSlot::Empty);

    let rigs = vec![
        DigRig::new(&closed, entry_for(&hbs[1], 1, 1)),
        DigRig::new(&honest, entry_for(&hbs[0], 1, 0)),
        DigRig::new(&gone, entry_for(&hbs[2], 1, 2)),
    ];
    let meta = ok(env.dig_with(&hbs, &rigs));
    let evs = events(&meta.logs);
    assert!(dug(&evs, &honest.rig).is_some(), "the honest rig still digs");
    for u in [&closed, &gone] {
        assert_eq!(
            skipped_code(&evs, &u.rig),
            Some(HdError::InvalidAccountTag.code())
        );
    }
    assert_eq!(evs.len(), 3, "one event per rig");
    // A skip changes nothing: the tombstone is as the close left it.
    assert_eq!(env.rig_slot(&closed.rig), RigSlot::Tombstone);
    assert_eq!(env.tombstone(&closed.rig).shift_id.get(), 1);
}
