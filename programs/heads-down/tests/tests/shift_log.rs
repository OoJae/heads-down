//! v1.3 `close_shift_log`: a sealed ShiftLog's rent goes back to whoever paid
//! it, 30 days after the shift ended, and never while a Focus Bond still
//! resolves from that log.

use hd::{
    error::HdError, instructions::shift_log::SHIFT_LOG_TTL_SECS, state::break_reason,
};
use heads_down_tests::*;

/// Rent-exempt lamports of a 128-byte ShiftLog.
const LOG_RENT: u64 = 1_781_760;
const AMOUNT: u64 = 100 * ONE_SKR;

fn record(env: &mut Env, u: &mut User) {
    let shift = env.rig(&u.rig).shift_id.get();
    let hb = u.heartbeat(shift, env.board_round, 3);
    ok(env.send(
        &[
            secp_ix_for(&[hb]),
            ix_record(&[(u.rig, entry_for(&hb, 0, 0))]),
        ],
        &[],
    ));
}

/// After the plan window, and once the lease has been expired for the grace,
/// the cranker seals the shift (and pays the log's rent).
fn crank_ends(env: &mut Env, u: &User, shift_id: u64) {
    let end = standard_plan().window_end + 1;
    env.set_clock(env.slot, end);
    let lease_to = env.rig(&u.rig).lease_to_round.get();
    env.set_board_round(lease_to.max(env.board_round) + 1 + hd::logic::PERMISSIONLESS_END_GRACE_ROUNDS);
    let c = env.cranker.pubkey();
    ok(env.send(&[ix_end_shift(&c, &u.rig, shift_id)], &[]));
}

fn prefix(a: &Address) -> [u8; 16] {
    a.to_bytes()[..16].try_into().unwrap()
}

#[test]
fn a_shift_logs_rent_returns_to_its_payer_after_30_days() {
    let mut env = Env::new();
    assert_eq!(env.svm.minimum_balance_for_rent_exemption(128), LOG_RENT);
    assert_eq!(SHIFT_LOG_TTL_SECS, 30 * 86_400);
    let u = User::new(&mut env, 1);
    env.onboard_standard(&u);
    let w = u.wallet.insecure_clone();
    let cranker = env.cranker.pubkey();

    // The wallet ends its own shift and pays the log's rent.
    ok(env.send_as(&w, &[ix_end_shift(&w.pubkey(), &u.rig, 1)], &[]));
    let log = shift_log_pda(&u.rig, 1);
    let l = env.shift_log(&log);
    assert_eq!(l.payer_prefix, prefix(&w.pubkey()));
    assert_eq!(env.lamports(&log), LOG_RENT);
    let end_ts = l.end_ts.get();

    // Too early, by a day and by a second.
    let close = ix_close_shift_log(&u.rig, 1, &w.pubkey());
    assert_hd(
        &env.send(std::slice::from_ref(&close), &[]),
        0,
        HdError::ShiftLogNotExpired,
    );
    env.set_clock(env.slot, end_ts + SHIFT_LOG_TTL_SECS - 1);
    assert_hd(
        &env.send(std::slice::from_ref(&close), &[]),
        0,
        HdError::ShiftLogNotExpired,
    );
    env.set_clock(env.slot, end_ts + SHIFT_LOG_TTL_SECS);

    // Only the payer the log names gets the rent: not the cranker who sends
    // the transaction, not a stranger.
    let stranger = Keypair::new().pubkey();
    for wrong in [cranker, stranger] {
        assert_hd(
            &env.send(&[ix_close_shift_log(&u.rig, 1, &wrong)], &[]),
            0,
            HdError::Unauthorized,
        );
    }
    // The bond slot must be this shift's.
    let mut ix = close.clone();
    ix.accounts[2].pubkey = bond_pda(&u.rig, 2);
    assert_ix_err(&env.send(&[ix], &[]), 0, InstructionError::InvalidSeeds);
    // The recipient must be writable; exact data length; three accounts.
    let mut ix = close.clone();
    ix.accounts[1].is_writable = false;
    assert_ix_err(&env.send(&[ix], &[]), 0, InstructionError::InvalidAccountData);
    let mut ix = close.clone();
    ix.data.push(0);
    assert_hd(&env.send(&[ix], &[]), 0, HdError::InvalidInstruction);
    let mut ix = close.clone();
    ix.accounts.pop();
    #[allow(deprecated)] // the runtime still reports ProgramError::NotEnoughAccountKeys this way
    let missing = InstructionError::NotEnoughAccountKeys;
    assert_ix_err(&env.send(&[ix], &[]), 0, missing);
    // Not a ShiftLog: the rig itself, in the log's slot.
    let mut ix = close.clone();
    ix.accounts[0].pubkey = u.rig;
    assert_hd(&env.send(&[ix], &[]), 0, HdError::InvalidAccountTag);

    // Anyone may send it (the cranker pays the fee); the wallet gets the rent.
    let before = env.lamports(&w.pubkey());
    let meta = ok(env.send(std::slice::from_ref(&close), &[]));
    assert_eq!(
        events(&meta.logs),
        vec![Event::ShiftLogClosed {
            shift_log: log,
            rig: u.rig,
            shift_id: 1,
            lamports: LOG_RENT,
        }]
    );
    assert_eq!(env.lamports(&w.pubkey()), before + LOG_RENT);
    assert!(env.is_closed(&log));
    // Only once.
    assert_hd(&env.send(&[close], &[]), 0, HdError::InvalidAccountTag);
    // The rig carries on: its next shift is 2, so the freed address is never
    // written again.
    let mut caps = Caps::standard();
    caps.expiry = env.now + 86_400;
    ok(env.send_as(
        &w,
        &[
            ix_set_caps(&w.pubkey(), caps),
            ix_arm_wallet(&w.pubkey(), &plan_at(&env)),
        ],
        &[],
    ));
    assert_eq!(env.rig(&u.rig).shift_id.get(), 2);
}

/// The standard plan with a window around the fork's current time.
fn plan_at(env: &Env) -> hd::message::Plan {
    let mut p = standard_plan();
    p.window_start = env.now - 3_600;
    p.window_end = env.now + 8 * 3_600;
    p
}

#[test]
fn a_crank_that_sealed_a_shift_gets_its_rent_back_not_the_rigs_wallet() {
    let mut env = Env::new();
    let mut u = User::new(&mut env, 2);
    env.onboard_standard(&u);
    let w = u.wallet.insecure_clone();
    let cranker = env.cranker.pubkey();
    record(&mut env, &mut u);
    crank_ends(&mut env, &u, 1);
    let log = shift_log_pda(&u.rig, 1);
    assert_eq!(env.shift_log(&log).payer_prefix, prefix(&cranker));
    env.advance_time(SHIFT_LOG_TTL_SECS);

    // The rig's wallet cannot collect rent the crank paid (otherwise every
    // shift a crank seals would hand 0.0018 SOL to the rig's owner).
    assert_hd(
        &env.send_as(&w, &[ix_close_shift_log(&u.rig, 1, &w.pubkey())], &[]),
        0,
        HdError::Unauthorized,
    );
    // The wallet may trigger it, but the lamports go to the crank.
    let before = env.lamports(&cranker);
    ok(env.send_as(&w, &[ix_close_shift_log(&u.rig, 1, &cranker)], &[]));
    assert_eq!(env.lamports(&cranker), before + LOG_RENT);
    assert!(env.is_closed(&log));
}

#[test]
fn a_log_stays_while_a_focus_bond_still_resolves_from_it() {
    let mut env = Env::new();
    env.init_bury_vault();
    // One shift that completes (the bond is owed back) and one that breaks.
    let mut done = User::new(&mut env, 3);
    let broke = User::new(&mut env, 4);
    for u in [&done, &broke] {
        env.onboard_standard(u);
        env.fund_skr(&u.pubkey(), 1_000 * ONE_SKR);
        let w = u.wallet.insecure_clone();
        ok(env.send_as(
            &w,
            &[
                ix_create_ata(&w.pubkey(), &bond_pda(&u.rig, 1), &SKR_MINT),
                ix_lock_focus_bond(&w.pubkey(), 1, AMOUNT),
            ],
            &[],
        ));
    }
    let wb = broke.wallet.insecure_clone();
    ok(env.send_as(
        &wb,
        &[
            ix_break_wallet(&wb.pubkey(), break_reason::MANUAL),
            ix_end_shift(&wb.pubkey(), &broke.rig, 1),
        ],
        &[],
    ));
    record(&mut env, &mut done);
    crank_ends(&mut env, &done, 1);
    assert_eq!(env.shift_log(&shift_log_pda(&done.rig, 1)).break_reason, 0);
    env.advance_time(SHIFT_LOG_TTL_SECS + 86_400);

    let cranker = env.cranker.pubkey();
    // 31 days on, both bonds are still unresolved: neither log may go. If the
    // completed one went, its bond would look abandoned and anyone could
    // forfeit 100 SKR that are owed back to their owner.
    assert_hd(
        &env.send(&[ix_close_shift_log(&done.rig, 1, &cranker)], &[]),
        0,
        HdError::ShiftLogInUse,
    );
    assert_hd(
        &env.send(&[ix_close_shift_log(&broke.rig, 1, &wb.pubkey())], &[]),
        0,
        HdError::ShiftLogInUse,
    );
    assert_hd(
        &env.send(&[ix_forfeit_focus_bond(&done.pubkey(), 1)], &[]),
        0,
        HdError::BondNotResolvable,
    );

    // Resolve them (both are permissionless), then the logs can go.
    ok(env.send(&[ix_release_focus_bond(&done.pubkey(), 1)], &[]));
    assert_eq!(
        env.token_balance(&ata(&done.pubkey(), &SKR_MINT)),
        1_000 * ONE_SKR
    );
    ok(env.send(&[ix_forfeit_focus_bond(&broke.pubkey(), 1)], &[]));
    assert_eq!(env.bury_vault().lot_skr.get(), AMOUNT);
    ok(env.send(&[ix_close_shift_log(&done.rig, 1, &cranker)], &[]));
    ok(env.send(&[ix_close_shift_log(&broke.rig, 1, &wb.pubkey())], &[]));
    assert!(env.is_closed(&shift_log_pda(&done.rig, 1)));
    assert!(env.is_closed(&shift_log_pda(&broke.rig, 1)));
}

#[test]
fn a_log_sealed_before_v1_3_refunds_the_rigs_authority() {
    let mut env = Env::new();
    let u = User::new(&mut env, 5);
    env.onboard_standard(&u);
    let w = u.wallet.insecure_clone();
    ok(env.send_as(&w, &[ix_end_shift(&w.pubkey(), &u.rig, 1)], &[]));
    // A v1.2 log: bytes 112..128 were reserved and zero.
    let log = shift_log_pda(&u.rig, 1);
    let mut acc = env.account(&log);
    acc.data[112..128].fill(0);
    env.svm.set_account(log, acc).unwrap();
    env.advance_time(SHIFT_LOG_TTL_SECS);

    // No payer on record: only the wallet whose Rig PDA the log names.
    let cranker = env.cranker.pubkey();
    assert_hd(
        &env.send(&[ix_close_shift_log(&u.rig, 1, &cranker)], &[]),
        0,
        HdError::Unauthorized,
    );
    // It works with the rig closed, too: the authority is proven by the PDA
    // derivation, not read from the rig.
    ok(env.send_as(&w, &[ix_close_rig(&w.pubkey(), None)], &[]));
    assert_eq!(env.rig_slot(&u.rig), RigSlot::Tombstone);
    let before = env.lamports(&w.pubkey());
    ok(env.send(&[ix_close_shift_log(&u.rig, 1, &w.pubkey())], &[]));
    assert_eq!(env.lamports(&w.pubkey()), before + LOG_RENT);
    assert!(env.is_closed(&log));
}
