//! Full lifecycle on a mainnet fork of the live ORE program:
//! one-transaction onboarding (ORE automate + register + caps + arm), a real
//! Keystore-style P-256 heartbeat through the gate, the dig CPI into ORE,
//! spend accounting, crank reimbursement, events, ShiftLog and close.

use heads_down_tests::*;
use hd::state::{break_reason, rig_state};

#[test]
fn onboarding_is_one_wallet_transaction() {
    let mut env = Env::new();
    let user = User::new(&mut env, 1);
    let meta = ok(env.onboard(&user, SOL / 20, Caps::standard(), &standard_plan()));

    // ORE Automation: Discretionary, fee = executor_fee, executor = our PDA.
    let a = env.automation(&user.automation()).expect("automation created");
    assert_eq!(a.strategy, 2);
    assert_eq!(a.fee, EXECUTOR_FEE);
    assert_eq!(a.executor, EXECUTOR);
    assert_eq!(a.balance, SOL / 20);

    let rig = env.rig(&user.rig);
    assert_eq!(rig.header.tag, 2);
    assert_eq!(rig.authority, user.pubkey().to_bytes());
    assert_eq!(rig.p256_pubkey, user.p256());
    assert_eq!(rig.state, rig_state::ARMED);
    assert_eq!(rig.shift_id.get(), 1);
    assert_eq!(rig.shift_start_round.get(), env.board_round);
    assert_eq!(rig.cap_round.get(), Caps::standard().round);
    assert_eq!(rig.plan_split_tiles, 10);
    assert_eq!(rig.freezes_left, 2);
    assert_eq!(rig.shift_open, 1);
    let evs = events(&meta.logs);
    assert_eq!(evs, vec![Event::ShiftArmed { rig: user.rig, shift_id: 1 }]);
}

#[test]
fn heartbeat_gated_dig_deploys_through_ore() {
    let mut env = Env::new();
    let mut user = User::new(&mut env, 2);
    env.onboard_standard(&user);

    let round_before = env.round_deployed();
    let auto_before = env.automation(&user.automation()).unwrap().balance;
    let exec_before = env.lamports(&EXECUTOR);
    let crank_before = env.lamports(&env.cranker.pubkey());

    let meta = ok(env.dig_fresh(&mut [&mut user]));
    println!("dig, 1 rig, 10 tiles: {} CU", meta.compute_units_consumed);

    // Expected tiles: the 10 least-crowded split tiles of the live round.
    let solo = hd::ore::distribution_mask(env.board_round);
    let expected_mask = hd::logic::select_tiles(&round_before, solo, 0, 10, 0);
    assert_eq!(expected_mask.count_ones(), 10);
    assert_eq!(expected_mask & solo, 0, "split tiles only");
    // Independently: every chosen tile is no more crowded than any unchosen split tile.
    let max_chosen = (0..25).filter(|i| expected_mask & (1 << i) != 0).map(|i| round_before[i]).max().unwrap();
    let min_unchosen = (0..25)
        .filter(|i| expected_mask & (1 << i) == 0 && solo & (1 << i) == 0)
        .map(|i| round_before[i])
        .min()
        .unwrap();
    assert!(max_chosen <= min_unchosen);

    let per_tile = 100_000; // min(1_000_000 / 10, TILE_CAP)
    let round_after = env.round_deployed();
    for i in 0..25 {
        let delta = round_after[i] - round_before[i];
        if expected_mask & (1 << i) != 0 {
            assert_eq!(delta, per_tile, "tile {i} credited");
        } else {
            assert_eq!(delta, 0, "tile {i} untouched");
        }
    }
    let (miner_deployed, miner_round) = env.miner_deployed(&user.miner());
    assert_eq!(miner_round, env.board_round);
    assert_eq!(miner_deployed.iter().sum::<u64>(), 10 * per_tile);

    // Automation debited tiles + the fixed fee, which went to the Executor.
    let auto_after = env.automation(&user.automation()).unwrap().balance;
    assert_eq!(auto_before - auto_after, 10 * per_tile + EXECUTOR_FEE);
    // Executor: +fee from ORE, -crank_fee reimbursement.
    assert_eq!(env.lamports(&EXECUTOR), exec_before + EXECUTOR_FEE - CRANK_FEE);
    // Cranker: reimbursed minus the tx fee (1 tx signature + 1 secp256r1 signature).
    assert_eq!(meta.fee, 10_000);
    assert_eq!(env.lamports(&env.cranker.pubkey()), crank_before - meta.fee + CRANK_FEE);
    // Executor stays a data-less System account.
    let ex = env.account(&EXECUTOR);
    assert_eq!(ex.owner, SYSTEM);
    assert!(ex.data.is_empty());

    // Rig accounting.
    let rig = env.rig(&user.rig);
    assert_eq!(rig.state, rig_state::DOWN);
    assert_eq!(rig.hb_counter.get(), 1);
    assert_eq!(rig.lease_from_round.get(), env.board_round);
    assert_eq!(rig.lease_to_round.get(), env.board_round + 2);
    assert_eq!(rig.last_dug_round.get(), env.board_round);
    assert_eq!(rig.spent_shift.get(), 10 * per_tile + EXECUTOR_FEE);
    assert_eq!(rig.spent_week.get(), 10 * per_tile + EXECUTOR_FEE);
    assert_eq!(rig.shift_rounds_dug.get(), 1);
    assert_eq!(rig.lifetime_rounds_dug.get(), 1);
    assert_eq!(rig.lifetime_lamports_deployed.get(), 10 * per_tile);
    assert_eq!(rig.shift_dark_rounds.get(), 3);

    // Event.
    let evs = events(&meta.logs);
    assert_eq!(
        evs,
        vec![Event::RigDug {
            rig: user.rig,
            round_id: env.board_round,
            lamports: 10 * per_tile,
            mask: expected_mask,
            ema_ev: env.ema_ev(),
        }]
    );
    // ORE saw the Executor PDA as the signer (its own DeployEvent is emitted
    // through a self-CPI into ORE's Log instruction).
    assert!(meta.logs.iter().any(|l| l.contains("deploying 0.0001 SOL to 10 squares")));
}

#[test]
fn shift_ends_into_a_shift_log_and_the_rig_closes() {
    let mut env = Env::new();
    let mut user = User::new(&mut env, 3);
    env.onboard_standard(&user);
    ok(env.dig_fresh(&mut [&mut user]));

    // The wallet ends the shift inside the window: reason manual.
    let log = shift_log_pda(&user.rig, 1);
    let w = user.wallet.insecure_clone();
    let meta = ok(env.send_as(&w, &[ix_end_shift(&w.pubkey(), &user.rig, 1)], &[]));
    let l = env.shift_log(&log);
    assert_eq!(l.header.tag, 4);
    assert_eq!(l.rig, user.rig.to_bytes());
    assert_eq!(l.shift_id.get(), 1);
    assert_eq!(l.start_round.get(), env.board_round);
    assert_eq!(l.end_round.get(), env.board_round);
    // Lease covered rounds r..r+2 but the shift ended at r: 1 dark round.
    assert_eq!(l.dark_rounds.get(), 1);
    assert_eq!(l.rounds_dug.get(), 1);
    assert_eq!(l.lamports_deployed.get(), 1_000_000 + EXECUTOR_FEE);
    assert_eq!(l.break_reason, break_reason::MANUAL);
    assert_eq!(l.mode, 0);
    assert_eq!(l.start_ts.get(), T0);
    assert_eq!(l.end_ts.get(), T0);
    assert_eq!(
        events(&meta.logs),
        vec![Event::ShiftEnded {
            rig: user.rig,
            shift_id: 1,
            dark_rounds: 1,
            rounds_dug: 1,
            lamports: 1_000_000 + EXECUTOR_FEE,
            reason: break_reason::MANUAL,
        }]
    );
    let rig = env.rig(&user.rig);
    assert_eq!(rig.state, rig_state::IDLE);
    assert_eq!(rig.shift_open, 0);
    assert_eq!(rig.lifetime_dark_rounds.get(), 1);
    // A manual end does not extend the streak.
    assert_eq!(rig.streak.get(), 0);

    // Ending twice fails (no open shift), and the log cannot be re-created.
    let res = env.send_as(&w, &[ix_end_shift(&w.pubkey(), &user.rig, 1)], &[]);
    assert_hd(&res, 0, hd::error::HdError::InvalidRigState);

    // close_rig returns every lamport to the stored authority.
    let rent = env.lamports(&user.rig);
    let before = env.lamports(&w.pubkey());
    let meta = ok(env.send_as(&w, &[ix_close_rig(&w.pubkey(), None)], &[]));
    assert_eq!(env.lamports(&w.pubkey()), before + rent - meta.fee);
    assert!(env.svm.get_account(&user.rig).is_none(), "rig closed");
}
