//! v1.3 governance rotation on the fork: `propose_governance` (current
//! governance, 72 h timelock), `accept_governance` (the successor itself,
//! after the timelock) and `cancel_governance`; the emergency pause stays
//! immediate for whoever is governance at that moment.
//!
//! The timelock has two halves, 864,000 slots and 72 hours of cluster time,
//! and both must pass: at mainnet's 268 ms slots (2026-10-01) the slots
//! alone are only about 64 hours.

use hd::{
    error::HdError,
    instructions::governance::{TIMELOCK_SECS, TIMELOCK_SLOTS},
};
use heads_down_tests::*;

fn funded(env: &mut Env) -> Keypair {
    let k = Keypair::new();
    env.svm.airdrop(&k.pubkey(), SOL).unwrap();
    k
}

fn propose(env: &mut Env, gov: &Keypair, new: &Address) -> TxResult {
    env.send_as(gov, &[ix_propose_governance(&gov.pubkey(), new)], &[])
}

fn accept(env: &mut Env, new: &Keypair) -> TxResult {
    env.send_as(new, &[ix_accept_governance(&new.pubkey())], &[])
}

fn cancel(env: &mut Env, gov: &Keypair) -> TxResult {
    env.send_as(gov, &[ix_cancel_governance(&gov.pubkey())], &[])
}

fn pause(env: &mut Env, signer: &Keypair) -> TxResult {
    let reg = env.registrar.pubkey();
    env.send_as(
        signer,
        &[ix_propose(&signer.pubkey(), &reg, CRANK_FEE, 0, 1)],
        &[],
    )
}

#[test]
fn governance_rotates_after_72_hours_when_the_successor_itself_accepts() {
    let mut env = Env::new();
    let gov = env.governance.insecure_clone();
    let new = funded(&mut env);
    let before = env.account(&CONFIG).data;

    let (s0, t0) = (env.slot, env.now);
    let meta = ok(propose(&mut env, &gov, &new.pubkey()));
    assert_eq!(
        events(&meta.logs),
        vec![Event::GovernanceProposed {
            governance: gov.pubkey(),
            pending: new.pubkey(),
            eta_slot: s0 + TIMELOCK_SLOTS,
            eta_ts: t0 + TIMELOCK_SECS,
        }]
    );
    let c = env.config();
    assert_eq!(c.governance, gov.pubkey().to_bytes(), "not rotated yet");
    assert_eq!(c.pending_governance, new.pubkey().to_bytes());
    assert_eq!(c.pending_governance_eta_slot.get(), s0 + TIMELOCK_SLOTS);
    assert_eq!(c.pending_governance_eta_ts.get(), t0 + TIMELOCK_SECS);
    assert_eq!((TIMELOCK_SLOTS, TIMELOCK_SECS), (864_000, 72 * 3_600));
    // Only bytes 192..240 of the Config moved: every v1.1 offset is intact.
    let after = env.account(&CONFIG).data;
    assert_eq!(after.len(), 256);
    assert_eq!(after[..192], before[..192]);
    assert_eq!(after[240..], before[240..]);
    assert_eq!(after[192..224], new.pubkey().to_bytes());
    assert_eq!(after[224..232], (s0 + TIMELOCK_SLOTS).to_le_bytes());
    assert_eq!(after[232..240], (t0 + TIMELOCK_SECS).to_le_bytes());

    // The successor cannot accept before the timelock, and both of its
    // halves must pass. Every slot but one, with all the time:
    assert_hd(&accept(&mut env, &new), 0, HdError::TimelockNotElapsed);
    env.set_clock(s0 + TIMELOCK_SLOTS - 1, t0 + TIMELOCK_SECS);
    assert_hd(&accept(&mut env, &new), 0, HdError::TimelockNotElapsed);
    // all the slots, one second short:
    env.set_clock(s0 + TIMELOCK_SLOTS, t0 + TIMELOCK_SECS - 1);
    assert_hd(&accept(&mut env, &new), 0, HdError::TimelockNotElapsed);
    // and slots alone never do it: five times as many, after 64 hours (what
    // 864,000 slots take at mainnet's 268 ms).
    env.set_clock(s0 + 5 * TIMELOCK_SLOTS, t0 + 64 * 3_600);
    assert_hd(&accept(&mut env, &new), 0, HdError::TimelockNotElapsed);
    // Until it accepts, the successor has no power at all.
    assert_hd(&pause(&mut env, &new), 0, HdError::Unauthorized);

    env.set_clock(s0 + 5 * TIMELOCK_SLOTS, t0 + TIMELOCK_SECS);
    let meta = ok(accept(&mut env, &new));
    assert_eq!(
        events(&meta.logs),
        vec![Event::GovernanceAccepted {
            governance: new.pubkey(),
            previous: gov.pubkey(),
        }]
    );
    let c = env.config();
    assert_eq!(c.governance, new.pubkey().to_bytes());
    assert_eq!(c.pending_governance, [0; 32]);
    assert_eq!(c.pending_governance_eta_slot.get(), 0);
    assert_eq!(c.pending_governance_eta_ts.get(), 0);
    // Nothing else in the Config changed, and the tail is back to zero.
    let after = env.account(&CONFIG).data;
    assert_eq!(after[..8], before[..8]);
    assert_eq!(after[40..192], before[40..192]);
    assert_eq!(after[192..], [0u8; 64]);

    // The old governance is out: no config proposal, no pause, no rotation.
    assert_hd(&pause(&mut env, &gov), 0, HdError::Unauthorized);
    assert_hd(
        &propose(&mut env, &gov, &gov.pubkey()),
        0,
        HdError::Unauthorized,
    );
    // The new one pauses at once.
    ok(pause(&mut env, &new));
    assert_eq!(env.config().paused, 1);
    // Accepting twice: nothing is pending.
    assert_hd(&accept(&mut env, &new), 0, HdError::InvalidInstruction);
}

#[test]
fn only_the_named_successor_can_accept_and_only_governance_can_propose_or_cancel() {
    let mut env = Env::new();
    let gov = env.governance.insecure_clone();
    let new = funded(&mut env);
    let mallory = funded(&mut env);
    // A heads_down account that is not the Config (used further down).
    let u = User::new(&mut env, 1);
    env.onboard_standard(&u);

    // Nothing pending: accept and cancel are refused.
    assert_hd(&accept(&mut env, &new), 0, HdError::InvalidInstruction);
    assert_hd(&cancel(&mut env, &gov), 0, HdError::InvalidInstruction);

    // A stranger cannot propose (for itself or anyone); governance must sign.
    assert_hd(
        &propose(&mut env, &mallory, &mallory.pubkey()),
        0,
        HdError::Unauthorized,
    );
    let mut ix = ix_propose_governance(&gov.pubkey(), &mallory.pubkey());
    ix.accounts[0].is_signer = false;
    assert_ix_err(
        &env.send_as(&mallory, &[ix], &[]),
        0,
        InstructionError::MissingRequiredSignature,
    );
    // The zero address and the current governance are not successors.
    assert_hd(
        &propose(&mut env, &gov, &Address::default()),
        0,
        HdError::InvalidInstruction,
    );
    assert_hd(
        &propose(&mut env, &gov, &gov.pubkey()),
        0,
        HdError::InvalidInstruction,
    );
    assert_eq!(env.config().pending_governance, [0; 32]);

    ok(propose(&mut env, &gov, &new.pubkey()));
    env.pass_timelock();

    // After the timelock: a stranger, and the outgoing governance, cannot
    // accept in the successor's place.
    assert_hd(&accept(&mut env, &mallory), 0, HdError::Unauthorized);
    assert_hd(&accept(&mut env, &gov), 0, HdError::Unauthorized);
    // The successor's address without its signature is not enough.
    let mut ix = ix_accept_governance(&new.pubkey());
    ix.accounts[0].is_signer = false;
    assert_ix_err(
        &env.send_as(&mallory, &[ix], &[]),
        0,
        InstructionError::MissingRequiredSignature,
    );
    // Neither a stranger nor the successor can cancel; unsigned is refused.
    assert_hd(&cancel(&mut env, &mallory), 0, HdError::Unauthorized);
    assert_hd(&cancel(&mut env, &new), 0, HdError::Unauthorized);
    let mut ix = ix_cancel_governance(&gov.pubkey());
    ix.accounts[0].is_signer = false;
    assert_ix_err(
        &env.send_as(&mallory, &[ix], &[]),
        0,
        InstructionError::MissingRequiredSignature,
    );
    assert_eq!(env.config().governance, gov.pubkey().to_bytes());

    // Exact data lengths, and only the canonical Config.
    let mut ix = ix_propose_governance(&gov.pubkey(), &new.pubkey());
    ix.data.push(0);
    assert_hd(
        &env.send_as(&gov, &[ix], &[]),
        0,
        HdError::InvalidInstruction,
    );
    let mut ix = ix_propose_governance(&gov.pubkey(), &new.pubkey());
    ix.data.pop();
    assert_hd(
        &env.send_as(&gov, &[ix], &[]),
        0,
        HdError::InvalidInstruction,
    );
    for mut ix in [
        ix_accept_governance(&new.pubkey()),
        ix_cancel_governance(&gov.pubkey()),
    ] {
        ix.data.push(0);
        let signer = if ix.accounts[0].pubkey == new.pubkey() {
            &new
        } else {
            &gov
        };
        assert_hd(
            &env.send_as(signer, &[ix], &[]),
            0,
            HdError::InvalidInstruction,
        );
    }
    let mut ix = ix_accept_governance(&new.pubkey());
    ix.accounts[1] = AccountMeta::new(u.rig, false);
    assert_hd(
        &env.send_as(&new, &[ix], &[]),
        0,
        HdError::InvalidAccountTag,
    );
    let mut ix = ix_propose_governance(&gov.pubkey(), &new.pubkey());
    ix.accounts[1] = AccountMeta::new(u.rig, false);
    assert_hd(
        &env.send_as(&gov, &[ix], &[]),
        0,
        HdError::InvalidAccountTag,
    );
    ok(accept(&mut env, &new));
}

#[test]
fn the_current_governance_can_cancel_or_replace_a_pending_rotation() {
    let mut env = Env::new();
    let gov = env.governance.insecure_clone();
    let first = funded(&mut env);
    let second = funded(&mut env);

    ok(propose(&mut env, &gov, &first.pubkey()));
    let meta = ok(cancel(&mut env, &gov));
    assert_eq!(
        events(&meta.logs),
        vec![Event::GovernanceCancelled {
            governance: gov.pubkey(),
            cancelled: first.pubkey(),
        }]
    );
    let c = env.config();
    assert_eq!(c.pending_governance, [0; 32]);
    assert_eq!(c.pending_governance_eta_slot.get(), 0);
    assert_eq!(c.pending_governance_eta_ts.get(), 0);
    // A cancelled successor can never accept, however long it waits.
    env.pass_timelock();
    env.pass_timelock();
    assert_hd(&accept(&mut env, &first), 0, HdError::InvalidInstruction);

    // A new proposal replaces the pending one and restarts the clock (both
    // halves).
    let (s1, t1) = (env.slot, env.now);
    ok(propose(&mut env, &gov, &first.pubkey()));
    env.set_clock(s1 + TIMELOCK_SLOTS - 10, t1 + TIMELOCK_SECS - 10);
    ok(propose(&mut env, &gov, &second.pubkey()));
    let (s2, t2) = (env.slot, env.now);
    let c = env.config();
    assert_eq!(c.pending_governance_eta_slot.get(), s2 + TIMELOCK_SLOTS);
    assert_eq!(c.pending_governance_eta_ts.get(), t2 + TIMELOCK_SECS);
    // The first proposal's eta has passed, but it was replaced.
    env.set_clock(s1 + TIMELOCK_SLOTS, t1 + TIMELOCK_SECS);
    assert_hd(&accept(&mut env, &first), 0, HdError::Unauthorized);
    assert_hd(&accept(&mut env, &second), 0, HdError::TimelockNotElapsed);
    env.set_clock(s2 + TIMELOCK_SLOTS, t2 + TIMELOCK_SECS);
    ok(accept(&mut env, &second));
    assert_eq!(env.config().governance, second.pubkey().to_bytes());
}

#[test]
fn a_mistyped_successor_cannot_brick_governance() {
    let mut env = Env::new();
    let gov = env.governance.insecure_clone();
    // An address nobody holds the key of (here: a program-derived address).
    let typo = rig_pda(&gov.pubkey());
    let (s0, t0) = (env.slot, env.now);
    ok(propose(&mut env, &gov, &typo));
    env.set_clock(s0 + 10 * TIMELOCK_SLOTS, t0 + 10 * TIMELOCK_SECS);
    // Nobody can sign for it, so the rotation never completes ...
    let mut ix = ix_accept_governance(&typo);
    ix.accounts[0].is_signer = false;
    assert_ix_err(
        &env.send(&[ix], &[]),
        0,
        InstructionError::MissingRequiredSignature,
    );
    // ... and the current governance keeps every power.
    assert_eq!(env.config().governance, gov.pubkey().to_bytes());
    ok(pause(&mut env, &gov));
    assert_eq!(env.config().paused, 1);
    ok(cancel(&mut env, &gov));
    let good = funded(&mut env);
    ok(propose(&mut env, &gov, &good.pubkey()));
    env.pass_timelock();
    ok(accept(&mut env, &good));
    assert_eq!(env.config().governance, good.pubkey().to_bytes());
}

#[test]
fn the_pause_stays_immediate_for_the_current_governance_during_a_rotation() {
    let mut env = Env::new();
    let gov = env.governance.insecure_clone();
    let new = funded(&mut env);
    let mut u = User::new(&mut env, 1);
    env.onboard_standard(&u);

    ok(propose(&mut env, &gov, &new.pubkey()));
    // A rotation in flight does not slow the circuit breaker: the current
    // governance pauses in the same slot, and `dig` fails from then on.
    assert_eq!(env.config().paused, 0);
    ok(pause(&mut env, &gov));
    assert_eq!(env.config().paused, 1);
    assert_hd(&env.dig_fresh(&mut [&mut u]), 2, HdError::Paused);
    // The pause did not touch the rotation.
    let c = env.config();
    assert_eq!(c.pending_governance, new.pubkey().to_bytes());
    // The successor still cannot act before it accepts.
    assert_hd(&pause(&mut env, &new), 0, HdError::Unauthorized);
}

#[test]
fn accepting_voids_the_outgoing_governances_pending_config_but_not_a_pause() {
    let mut env = Env::new();
    let gov = env.governance.insecure_clone();
    let new = funded(&mut env);
    let (s0, t0) = (env.slot, env.now);
    ok(propose(&mut env, &gov, &new.pubkey()));
    // The outgoing governance pauses, then slips in a registrar change and an
    // un-pause just before the handover.
    ok(pause(&mut env, &gov));
    env.set_clock(s0 + TIMELOCK_SLOTS - 1, t0 + TIMELOCK_SECS - 1);
    let rogue_registrar = Keypair::new().pubkey();
    ok(env.send_as(
        &gov,
        &[ix_propose(&gov.pubkey(), &rogue_registrar, CRANK_FEE, 0, 0)],
        &[],
    ));
    assert_eq!(env.config().pending_exists, 1);
    env.set_clock(s0 + TIMELOCK_SLOTS, t0 + TIMELOCK_SECS);
    ok(accept(&mut env, &new));

    let c = env.config();
    assert_eq!(c.pending_exists, 0, "the old proposal died with the handover");
    assert_eq!(c.pending_registrar, [0; 32]);
    assert_eq!(c.pending_eta_slot.get(), 0);
    assert_eq!(c.pending_eta_ts.get(), 0);
    assert_eq!(c.registrar, env.registrar.pubkey().to_bytes());
    assert_eq!(c.paused, 1, "the pause itself is kept");
    env.pass_timelock();
    env.pass_timelock();
    assert_hd(
        &env.send(&[ix_apply()], &[]),
        0,
        HdError::InvalidInstruction,
    );
    // The new governance un-pauses through its own timelocked proposal.
    let reg = env.registrar.pubkey();
    ok(env.send_as(
        &new,
        &[ix_propose(&new.pubkey(), &reg, CRANK_FEE, 0, 0)],
        &[],
    ));
    env.pass_timelock();
    ok(env.send(&[ix_apply()], &[]));
    assert_eq!(env.config().paused, 0);
}
