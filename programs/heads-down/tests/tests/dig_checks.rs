//! `dig` preconditions (skips) and account validation (transaction
//! failures): gate, caps, window, budget, idempotency, strategy/fee, rig
//! state, spoofed ORE accounts, another user's Automation, the executor,
//! an uninitialized or paused Config.

use hd::{error::HdError, state::break_reason};
use heads_down_tests::*;

fn setup_with(seed: u8, caps: Caps, plan: hd::message::Plan) -> (Env, User) {
    let mut env = Env::new();
    let user = User::new(&mut env, seed);
    ok(env.onboard(&user, SOL / 20, caps, &plan));
    (env, user)
}

fn setup(seed: u8) -> (Env, User) {
    setup_with(seed, Caps::standard(), standard_plan())
}

/// Dig `user` with a fresh heartbeat; return its RigSkipped code or None if dug.
fn dig_once(env: &mut Env, user: &mut User) -> Option<u32> {
    let meta = ok(env.dig_fresh(&mut [user]));
    let evs = events(&meta.logs);
    skipped_code(&evs, &user.rig)
}

/// A standard dig transaction for `user` (fresh heartbeat), to be mutated.
fn dig_ixs(env: &Env, user: &mut User) -> Vec<Instruction> {
    let hb = user.heartbeat(1, env.board_round, 3);
    vec![
        compute_limit(1_400_000),
        secp_ix_for(&[hb]),
        ix_dig(
            &env.cranker.pubkey(),
            &env.round,
            &[DigRig::new(user, entry_for(&hb, 1, 0))],
        ),
    ]
}

// ---- skips ------------------------------------------------------------------

#[test]
fn gate_closed_by_plan_or_by_wallet_cap() {
    let mut env = Env::new();
    let ev = env.ema_ev();

    // Plan threshold one lamport below the live pot-adjusted cost.
    let mut plan = standard_plan();
    plan.max_ev_cost = ev - 1;
    let mut a = User::new(&mut env, 1);
    ok(env.onboard(&a, SOL / 20, Caps::standard(), &plan));
    assert_eq!(dig_once(&mut env, &mut a), Some(HdError::CostGate.code()));

    // Exactly at the threshold the gate opens (<=).
    plan.max_ev_cost = ev;
    let mut b = User::new(&mut env, 2);
    ok(env.onboard(&b, SOL / 20, Caps::standard(), &plan));
    assert_eq!(dig_once(&mut env, &mut b), None);

    // The wallet's cap_max_cost binds even if the plan is looser: set_caps
    // clamps the plan down.
    let mut c = User::new(&mut env, 3);
    env.onboard_standard(&c);
    let mut caps = Caps::standard();
    caps.max_cost = ev - 1;
    let w = c.wallet.insecure_clone();
    ok(env.send_as(&w, &[ix_set_caps(&w.pubkey(), caps)], &[]));
    assert_eq!(env.rig(&c.rig).plan_max_ev_cost.get(), ev - 1);
    assert_eq!(dig_once(&mut env, &mut c), Some(HdError::CostGate.code()));
}

#[test]
fn motherlode_pot_lowers_the_gate_cost() {
    // Same EMA, bigger pot => cheaper expected ORE => a gate that was closed opens.
    let mut env = Env::new();
    let ev_live = env.ema_ev();
    let mut plan = standard_plan();
    plan.max_ev_cost = ev_live - 1;
    let mut u = User::new(&mut env, 4);
    ok(env.onboard(&u, SOL / 20, Caps::standard(), &plan));
    assert_eq!(dig_once(&mut env, &mut u), Some(HdError::CostGate.code()));
    // Double the pot in the Treasury: ema_ev falls below the plan threshold.
    env.poke_u64(&TREASURY, 8, env.pot * 2);
    assert!(hd::logic::ema_ev(env.ema, env.pot * 2) < (ev_live - 1) as u128);
    assert_eq!(dig_once(&mut env, &mut u), None);
}

#[test]
fn caps_expired_and_outside_window() {
    let mut caps = Caps::standard();
    caps.expiry = T0 + 60;
    let (mut env, mut u) = setup_with(5, caps, standard_plan());
    env.advance_time(61);
    assert_eq!(
        dig_once(&mut env, &mut u),
        Some(HdError::CapsExpired.code())
    );

    let (mut env, mut u) = setup(6);
    env.advance_time(8 * 3_600 + 1); // past window_end (caps still valid)
    assert_eq!(
        dig_once(&mut env, &mut u),
        Some(HdError::OutsideWindow.code())
    );

    let mut plan = standard_plan();
    plan.window_start = T0 + 600;
    let (mut env, mut u) = setup_with(7, Caps::standard(), plan);
    assert_eq!(
        dig_once(&mut env, &mut u),
        Some(HdError::OutsideWindow.code())
    );
}

#[test]
fn budget_exhausted_by_shift_or_week_cap() {
    // Shift headroom does not even cover the Automation fee.
    let mut caps = Caps::standard();
    caps.shift = EXECUTOR_FEE + 9; // budget = 9 lamports < 10 tiles
    let (mut env, mut u) = setup_with(8, caps, standard_plan());
    assert_eq!(
        dig_once(&mut env, &mut u),
        Some(HdError::BudgetExhausted.code())
    );

    // Week cap: 1 lamport of headroom left after the fee.
    let mut caps = Caps::standard();
    caps.week = EXECUTOR_FEE + 1;
    let (mut env, mut u) = setup_with(9, caps, standard_plan());
    assert_eq!(
        dig_once(&mut env, &mut u),
        Some(HdError::BudgetExhausted.code())
    );

    // A tight but sufficient round cap shrinks the per-tile amount so the
    // whole debit (tiles + fee) stays inside the cap.
    let mut caps = Caps::standard();
    caps.round = 505_000; // 500_000 for tiles after the 5_000 fee
    let mut plan = standard_plan();
    plan.dig_lamports = 500_000;
    let (mut env, mut u) = setup_with(10, caps, plan);
    let meta = ok(env.dig_fresh(&mut [&mut u]));
    let (lamports, mask) = dug(&events(&meta.logs), &u.rig).expect("dug");
    assert_eq!((lamports, mask.count_ones()), (500_000, 10));
    assert_eq!(env.rig(&u.rig).spent_shift.get(), 505_000);
}

#[test]
fn strategy_or_fee_mismatch_is_skipped() {
    let mut env = Env::new();
    // Fee 1 lamport above config.executor_fee.
    let mut a = User::new(&mut env, 11);
    let w = a.wallet.insecure_clone();
    ok(env.send_as(
        &w,
        &[
            ore_automate(
                &w.pubkey(),
                &EXECUTOR,
                TILE_CAP,
                SOL / 20,
                EXECUTOR_FEE + 1,
                2,
                0,
                u16::MAX,
            ),
            ix_register_rig(&w.pubkey(), &a.p256(), None),
            ix_set_caps(&w.pubkey(), Caps::standard()),
            ix_arm_wallet(&w.pubkey(), &standard_plan()),
        ],
        &[],
    ));
    assert_eq!(
        dig_once(&mut env, &mut a),
        Some(HdError::StrategyMismatch.code())
    );
    // A zero fee (would drain the pool through reimbursements) is refused too.
    ok(env.send_as(
        &w,
        &[ore_automate(
            &w.pubkey(),
            &EXECUTOR,
            TILE_CAP,
            0,
            0,
            2,
            0,
            u16::MAX,
        )],
        &[],
    ));
    assert_eq!(
        dig_once(&mut env, &mut a),
        Some(HdError::StrategyMismatch.code())
    );
    // Strategy Preferred (1) instead of Discretionary (2).
    ok(env.send_as(
        &w,
        &[ore_automate(
            &w.pubkey(),
            &EXECUTOR,
            TILE_CAP,
            0,
            EXECUTOR_FEE,
            1,
            0,
            u16::MAX,
        )],
        &[],
    ));
    assert_eq!(
        dig_once(&mut env, &mut a),
        Some(HdError::StrategyMismatch.code())
    );
    // Nothing was ever deployed.
    assert_eq!(env.rig(&a.rig).spent_shift.get(), 0);
}

#[test]
fn motherlode_conditions_would_no_op_so_they_are_skipped() {
    let mut env = Env::new();
    let mut a = User::new(&mut env, 12);
    let w = a.wallet.insecure_clone();
    // min_motherlode far above the live pot: ORE would return Ok without deploying.
    ok(env.send_as(
        &w,
        &[
            ore_automate(
                &w.pubkey(),
                &EXECUTOR,
                TILE_CAP,
                SOL / 20,
                EXECUTOR_FEE,
                2,
                60_000,
                u16::MAX,
            ),
            ix_register_rig(&w.pubkey(), &a.p256(), None),
            ix_set_caps(&w.pubkey(), Caps::standard()),
            ix_arm_wallet(&w.pubkey(), &standard_plan()),
        ],
        &[],
    ));
    assert_eq!(
        dig_once(&mut env, &mut a),
        Some(HdError::MotherlodeCondition.code())
    );
}

#[test]
fn round_window_closed_is_skipped() {
    let (mut env, mut u) = setup(13);
    let end = u64_at(&env.account(&BOARD).data, 24);
    env.set_clock(end, T0); // slot == end_slot: ORE's deploy window is closed
    assert_eq!(
        dig_once(&mut env, &mut u),
        Some(HdError::RoundNotActive.code())
    );
}

#[test]
fn executor_underfunded_for_a_checkpoint_top_up_is_skipped() {
    let (mut env, mut u) = setup(14);
    // Miner spent its checkpoint reserve; ORE would pull 10_000 from the executor.
    env.poke_u64(&u.miner(), 56, 0);
    // Drain the executor float to exactly rent-exemption.
    let rent = env.svm.minimum_balance_for_rent_exemption(0);
    let mut ex = env.account(&EXECUTOR);
    ex.lamports = rent + CHECKPOINT_FEE - 1;
    env.svm.set_account(EXECUTOR, ex).unwrap();
    assert_eq!(
        dig_once(&mut env, &mut u),
        Some(HdError::ExecutorUnderfunded.code())
    );
}

#[test]
fn frozen_broken_and_idle_rigs_are_skipped() {
    let (mut env, mut u) = setup(15);
    let w = u.wallet.insecure_clone();
    ok(env.send_as(&w, &[ix_freeze_wallet(&w.pubkey())], &[]));
    assert_eq!(dig_once(&mut env, &mut u), Some(HdError::RigFrozen.code()));

    let (mut env, mut u) = setup(16);
    let w = u.wallet.insecure_clone();
    ok(env.send_as(
        &w,
        &[ix_break_wallet(&w.pubkey(), break_reason::MANUAL)],
        &[],
    ));
    assert_eq!(
        dig_once(&mut env, &mut u),
        Some(HdError::RigNotArmed.code())
    );

    // Registered but never armed.
    let mut env = Env::new();
    let mut u = User::new(&mut env, 17);
    let w = u.wallet.insecure_clone();
    ok(env.send_as(
        &w,
        &[
            ore_automate_default(&w.pubkey(), SOL / 20),
            ix_register_rig(&w.pubkey(), &u.p256(), None),
        ],
        &[],
    ));
    assert_eq!(
        dig_once(&mut env, &mut u),
        Some(HdError::RigNotArmed.code())
    );
}

#[test]
fn focus_only_shift_never_deploys() {
    let mut plan = standard_plan();
    plan.flags = hd::state::plan_flags::FOCUS_ONLY;
    let (mut env, mut u) = setup_with(18, Caps::standard(), plan);
    assert_eq!(dig_once(&mut env, &mut u), Some(HdError::FocusOnly.code()));
    // The heartbeat still counted.
    assert_eq!(env.rig(&u.rig).shift_dark_rounds.get(), 3);
}

// ---- transaction failures (cranker-chosen accounts) ----------------------------

#[test]
fn non_ore_owned_board_or_treasury_fails() {
    // A System-owned copy of the Board at the real address, carrying a fake
    // low EMA, is refused before any byte is used.
    let (mut env, mut u) = setup(19);
    let mut board = env.account(&BOARD);
    board.data[32..40].copy_from_slice(&1u64.to_le_bytes());
    board.owner = SYSTEM;
    env.svm.set_account(BOARD, board).unwrap();
    let ixs = dig_ixs(&env, &mut u);
    assert_hd(&env.send(&ixs, &[]), 2, HdError::InvalidOreAccount);

    let (mut env, mut u) = setup(20);
    let mut t = env.account(&TREASURY);
    t.owner = SYSTEM;
    env.svm.set_account(TREASURY, t).unwrap();
    let ixs = dig_ixs(&env, &mut u);
    assert_hd(&env.send(&ixs, &[]), 2, HdError::InvalidOreAccount);

    // A look-alike board at another address (ORE-owned bytes copied).
    let (mut env, mut u) = setup(21);
    let fake = Keypair::new().pubkey();
    let mut b = env.account(&BOARD);
    b.data[32..40].copy_from_slice(&1u64.to_le_bytes());
    env.svm.set_account(fake, b).unwrap();
    let mut ixs = dig_ixs(&env, &mut u);
    ixs[2].accounts[3] = AccountMeta::new(fake, false);
    assert_hd(&env.send(&ixs, &[]), 2, HdError::InvalidOreAccount);
}

#[test]
fn spoofed_round_or_ore_program_or_entropy_fails() {
    let (mut env, mut u) = setup(22);
    // Another ORE-owned account in the Round slot (a Miner).
    let mut ixs = dig_ixs(&env, &mut u);
    ixs[2].accounts[5] = AccountMeta::new(u.miner(), false);
    assert_hd(&env.send(&ixs, &[]), 2, HdError::InvalidOreAccount);
    // A fake program id in the ORE slot.
    let mut ixs = dig_ixs(&env, &mut u);
    ixs[2].accounts[8] = AccountMeta::new_readonly(HD, false);
    assert_hd(&env.send(&ixs, &[]), 2, HdError::InvalidOreAccount);
    // Wrong entropy program.
    let mut ixs = dig_ixs(&env, &mut u);
    ixs[2].accounts[10] = AccountMeta::new_readonly(ORE, false);
    assert_hd(&env.send(&ixs, &[]), 2, HdError::InvalidOreAccount);
}

#[test]
fn another_users_automation_fails() {
    let mut env = Env::new();
    let mut victim = User::new(&mut env, 23);
    let attacker = User::new(&mut env, 24);
    env.onboard_standard(&victim);
    env.onboard_standard(&attacker);
    let hb = victim.heartbeat(1, env.board_round, 3);
    // Victim's rig and heartbeat, but the attacker's Automation / Miner.
    let mut r = DigRig::new(&victim, entry_for(&hb, 1, 0));
    r.automation = attacker.automation();
    let res = env.dig_with(&[hb], &[r]);
    assert_hd(&res, 2, HdError::InvalidOreAccount);
    let mut r = DigRig::new(&victim, entry_for(&hb, 1, 0));
    r.miner = attacker.miner();
    assert_hd(&env.dig_with(&[hb], &[r]), 2, HdError::InvalidOreAccount);
}

#[test]
fn authority_is_never_taken_from_the_caller() {
    // Passing the Executor PDA (or anyone else) as `authority` would make
    // ORE treat the deploy as a manual one paid by the signer: refused.
    let mut env = Env::new();
    let mut u = User::new(&mut env, 25);
    env.onboard_standard(&u);
    let hb = u.heartbeat(1, env.board_round, 3);
    for fake_authority in [EXECUTOR, Keypair::new().pubkey()] {
        let mut r = DigRig::new(&u, entry_for(&hb, 1, 0));
        r.authority = fake_authority;
        r.automation = automation_pda(&fake_authority);
        r.miner = miner_pda(&fake_authority);
        assert_hd(&env.dig_with(&[hb], &[r]), 2, HdError::Unauthorized);
    }
}

#[test]
fn wrong_executor_account_fails() {
    let (mut env, mut u) = setup(26);
    // Another PDA of heads_down (the Config) in the executor slot.
    let mut ixs = dig_ixs(&env, &mut u);
    ixs[2].accounts[2] = AccountMeta::new(CONFIG, false);
    assert_hd(&env.send(&ixs, &[]), 2, HdError::InvalidExecutor);
    // A lookalike keypair account.
    let mut ixs = dig_ixs(&env, &mut u);
    ixs[2].accounts[2] = AccountMeta::new(env.cranker.pubkey(), false);
    assert_hd(&env.send(&ixs, &[]), 2, HdError::InvalidExecutor);
}

#[test]
fn executor_not_configured_on_the_automation_is_skipped() {
    let mut env = Env::new();
    let mut u = User::new(&mut env, 27);
    let w = u.wallet.insecure_clone();
    let someone = Keypair::new().pubkey();
    ok(env.send_as(
        &w,
        &[
            ore_automate(
                &w.pubkey(),
                &someone,
                TILE_CAP,
                SOL / 20,
                EXECUTOR_FEE,
                2,
                0,
                u16::MAX,
            ),
            ix_register_rig(&w.pubkey(), &u.p256(), None),
            ix_set_caps(&w.pubkey(), Caps::standard()),
            ix_arm_wallet(&w.pubkey(), &standard_plan()),
        ],
        &[],
    ));
    assert_eq!(
        dig_once(&mut env, &mut u),
        Some(HdError::InvalidExecutor.code())
    );

    // No Automation at all.
    let mut v = User::new(&mut env, 28);
    let wv = v.wallet.insecure_clone();
    ok(env.send_as(
        &wv,
        &[
            ix_register_rig(&wv.pubkey(), &v.p256(), None),
            ix_set_caps(&wv.pubkey(), Caps::standard()),
            ix_arm_wallet(&wv.pubkey(), &standard_plan()),
        ],
        &[],
    ));
    assert_eq!(
        dig_once(&mut env, &mut v),
        Some(HdError::InvalidExecutor.code())
    );
}

#[test]
fn uninitialized_config_fails() {
    let mut env = Env::build(Build::Mainnet, true, false);
    let mut u = User::new(&mut env, 29);
    // register_rig needs the Config too.
    let w = u.wallet.insecure_clone();
    let res = env.send_as(&w, &[ix_register_rig(&w.pubkey(), &u.p256(), None)], &[]);
    assert_hd(&res, 0, HdError::InvalidAccountTag);
    let ixs = dig_ixs(&env, &mut u);
    assert_hd(&env.send(&ixs, &[]), 2, HdError::InvalidAccountTag);
}

#[test]
fn paused_config_fails_dig() {
    let (mut env, mut u) = setup(30);
    let gov = env.governance.insecure_clone();
    let registrar = env.registrar.pubkey();
    ok(env.send_as(
        &gov,
        &[ix_propose(&gov.pubkey(), &registrar, CRANK_FEE, 0, 1)],
        &[],
    ));
    assert_eq!(env.config().paused, 1, "pausing is immediate");
    let ixs = dig_ixs(&env, &mut u);
    assert_hd(&env.send(&ixs, &[]), 2, HdError::Paused);
}

#[test]
fn malformed_dig_data_and_account_counts_fail() {
    let (mut env, mut u) = setup(31);
    let mut ixs = dig_ixs(&env, &mut u);
    ixs[2].data.push(0); // trailing byte
    assert_hd(&env.send(&ixs, &[]), 2, HdError::InvalidInstruction);
    let mut ixs = dig_ixs(&env, &mut u);
    ixs[2].data[1] = 2; // claims 2 rigs
    assert_hd(&env.send(&ixs, &[]), 2, HdError::InvalidInstruction);
    let mut ixs = dig_ixs(&env, &mut u);
    ixs[2].accounts.pop(); // missing the miner
    assert_hd(&env.send(&ixs, &[]), 2, HdError::InvalidInstruction);
    let mut ixs = dig_ixs(&env, &mut u);
    ixs[2].data[1] = 0; // zero rigs
    ixs[2].data.truncate(2);
    ixs[2].accounts.truncate(12);
    assert_hd(&env.send(&ixs, &[]), 2, HdError::InvalidInstruction);
    // The cranker must sign.
    let mut ixs = dig_ixs(&env, &mut u);
    let stranger = Keypair::new().pubkey();
    ixs[2].accounts[0] = AccountMeta::new(stranger, false);
    assert_ix_err(
        &env.send(&ixs, &[]),
        2,
        InstructionError::MissingRequiredSignature,
    );
}
