//! Post-CPI invariants against an ORE that misbehaves (signer passthrough,
//! stale data after CPI). The user onboards against the real ORE; then ORE's
//! program id is re-pointed at `tests/mock-ore` (TEST ONLY), which is what a
//! malicious or broken ORE upgrade would look like to heads_down.

use hd::error::HdError;
use heads_down_tests::*;

const DRAIN: u8 = 1;
const NO_OP: u8 = 2;
const OVER_DEBIT: u8 = 3;

fn mock_ore(mode: u8) -> (Env, User) {
    let mut env = Env::new();
    let user = User::new(&mut env, 1);
    env.onboard_standard(&user);
    let so = root().join("target/deploy-mock/mock_ore.so");
    let bytes = std::fs::read(&so)
        .unwrap_or_else(|_| panic!("{} missing: run scripts/build.sh", so.display()));
    env.svm.add_program(ORE, &bytes).unwrap();
    let mut board = env.account(&BOARD);
    board.data[1] = mode;
    env.svm.set_account(BOARD, board).unwrap();
    (env, user)
}

#[test]
fn real_ore_closing_the_automation_mid_cpi_is_accounted() {
    // Live ORE: after this dig the balance (45,000) is below one more square
    // (100,000 + 5,000 fee), so ORE deploys and then closes the Automation
    // back to the authority inside the same CPI (`deploy.rs:350-352`).
    let mut env = Env::new();
    let mut u = User::new(&mut env, 2);
    ok(env.onboard(&u, 1_050_000, Caps::standard(), &standard_plan()));
    let auto_lamports = env.lamports(&u.automation());
    let wallet_before = env.lamports(&u.pubkey());
    let meta = ok(env.dig_fresh(&mut [&mut u]));
    let (lamports, _) = dug(&events(&meta.logs), &u.rig).expect("dug");
    assert_eq!(lamports, 1_000_000);
    assert!(env.automation(&u.automation()).is_none(), "closed by ORE");
    // Spend = tiles + fee, counted even though the balance field is gone.
    assert_eq!(env.rig(&u.rig).spent_shift.get(), 1_000_000 + EXECUTOR_FEE);
    // The rest of the Automation (balance + rent) went back to the wallet.
    assert_eq!(
        env.lamports(&u.pubkey()),
        wallet_before + auto_lamports - 1_000_000 - EXECUTOR_FEE
    );
    // Next dig: the revoked/closed Automation is a skip, not a failure.
    let meta = ok(env.dig_fresh(&mut [&mut u]));
    assert_eq!(
        skipped_code(&events(&meta.logs), &u.rig),
        Some(HdError::InvalidExecutor.code())
    );
}

/// Live ORE charges the Automation fee on a miner's FIRST deploy of a round
/// only (`deploy.rs:338-342`). A rig whose Miner already holds a same-round
/// deployment (its owner deployed by hand; here by fixture surgery) pays the
/// Executor nothing, so the cranker is paid nothing: a reimbursement comes
/// only out of the fee received in the same dig, and the shared float cannot
/// be drawn down by fee-less digs.
#[test]
fn a_dig_that_brought_no_fee_is_not_reimbursed() {
    let mut env = Env::new();
    let mut paying = User::new(&mut env, 8);
    let mut feeless = User::new(&mut env, 9);
    env.onboard_standard(&paying);
    env.onboard_standard(&feeless);
    let m = feeless.miner();
    env.poke_u64(&m, 664, env.board_round); // miner.round_id
    env.poke_u64(&m, 64 + 8 * 24, 1_000); // miner.deployed[24]
    let cranker = env.cranker.pubkey();

    // Control: a first deploy brings executor_fee in, and crank_fee goes out.
    let (exec, crank) = (env.lamports(&EXECUTOR), env.lamports(&cranker));
    let meta = ok(env.dig_fresh(&mut [&mut paying]));
    assert!(dug(&events(&meta.logs), &paying.rig).is_some());
    assert_eq!(env.lamports(&EXECUTOR), exec + EXECUTOR_FEE - CRANK_FEE);
    assert_eq!(env.lamports(&cranker), crank + CRANK_FEE - meta.fee);

    // No fee in: the rig is still dug, and nothing leaves the float.
    let (exec, crank) = (env.lamports(&EXECUTOR), env.lamports(&cranker));
    let balance = env.automation(&feeless.automation()).unwrap().balance;
    let meta = ok(env.dig_fresh(&mut [&mut feeless]));
    let (lamports, _) = dug(&events(&meta.logs), &feeless.rig).expect("dug");
    assert_eq!(
        env.automation(&feeless.automation()).unwrap().balance,
        balance - lamports,
        "tiles only: ORE charged no Automation fee"
    );
    assert_eq!(env.rig(&feeless.rig).spent_shift.get(), lamports);
    assert_eq!(env.lamports(&EXECUTOR), exec, "nothing in, nothing out");
    assert_eq!(env.lamports(&cranker), crank - meta.fee, "no reimbursement");
}

#[test]
fn ore_draining_the_executor_float_reverts_the_dig() {
    let (mut env, mut u) = mock_ore(DRAIN);
    let exec_before = env.lamports(&EXECUTOR);
    let res = env.dig_fresh(&mut [&mut u]);
    // The Executor PDA's signature reached "ORE", which spent 20,000 of the
    // float; heads_down's `executor_after + CHECKPOINT_FEE >= executor_before`
    // check fails the whole transaction.
    assert_hd(&res, 2, HdError::InvalidExecutor);
    assert_eq!(env.lamports(&EXECUTOR), exec_before);
    assert_eq!(env.rig(&u.rig).hb_counter.get(), 0, "reverted");
}

#[test]
fn ore_returning_ok_without_deploying_is_not_a_dig() {
    let (mut env, mut u) = mock_ore(NO_OP);
    let crank_before = env.lamports(&env.cranker.pubkey());
    let exec_before = env.lamports(&EXECUTOR);
    let meta = ok(env.dig_fresh(&mut [&mut u]));
    let evs = events(&meta.logs);
    assert_eq!(skipped_code(&evs, &u.rig), Some(HdError::OreNoOp.code()));
    // No reimbursement, no dig counters, no spend.
    assert_eq!(env.lamports(&env.cranker.pubkey()), crank_before - meta.fee);
    assert_eq!(env.lamports(&EXECUTOR), exec_before);
    let rig = env.rig(&u.rig);
    assert_eq!(rig.last_dug_round.get(), 0);
    assert_eq!(rig.shift_rounds_dug.get(), 0);
    assert_eq!(rig.spent_shift.get(), 0);
    // The heartbeat itself was valid and consumed.
    assert_eq!(rig.hb_counter.get(), 1);
}

#[test]
fn ore_debiting_more_than_it_deployed_reverts_the_dig() {
    let (mut env, mut u) = mock_ore(OVER_DEBIT);
    let before = env.automation(&u.automation()).unwrap().balance;
    let res = env.dig_fresh(&mut [&mut u]);
    // The Automation paid 1,000 lamports for nothing: debit != tiles + fee.
    assert_hd(&res, 2, HdError::InvalidOreAccount);
    assert_eq!(env.automation(&u.automation()).unwrap().balance, before);
}
