//! No attacker input may make the v1.3 paths panic or fault, and none may
//! break what v1.3 promises:
//!
//! * governance only ever moves to a key that accepted the rotation itself,
//!   after both halves of the timelock (the clock moves by slots only, by
//!   time only, or by both), and a rotation is only ever proposed or
//!   cancelled by the governance of that moment;
//! * a Rig PDA never loses its shift id or its P-256 counter: across any mix
//!   of `close_rig` and `register_rig` they never go backwards, so an armed
//!   shift can always be ended (its ShiftLog address is always free);
//! * a ShiftLog's rent only ever goes to the payer the log names, and a log
//!   whose Focus Bond is still there never closes.
//!
//! Random bytes for tags 28..=31 and for the two rig instructions whose work
//! changed (`register_rig`, `close_rig`), with random account lists drawn
//! from the accounts in play (a Rig PDA holding a tombstone, sealed ShiftLogs
//! past their 30 days, one with its Focus Bond still locked, both governance
//! keys), and byte / account mutations of valid instructions, all executed by
//! the real SBF binary on the fork. Every failure must be a clean error,
//! never `ProgramFailedToComplete`, and the invariants are checked after
//! every single transaction.

use hd::{
    instructions::{
        governance::{TIMELOCK_SECS, TIMELOCK_SLOTS},
        shift_log::SHIFT_LOG_TTL_SECS,
    },
    tag,
};
use heads_down_tests::*;

/// Rent-exempt lamports of a 128-byte ShiftLog.
const LOG_RENT: u64 = 1_781_760;

struct Rng(u64);

impl Rng {
    fn next(&mut self) -> u64 {
        self.0 ^= self.0 >> 12;
        self.0 ^= self.0 << 25;
        self.0 ^= self.0 >> 27;
        self.0.wrapping_mul(0x2545_F491_4F6C_DD1D)
    }
    fn below(&mut self, n: usize) -> usize {
        (self.next() % n as u64) as usize
    }
    fn bytes(&mut self, n: usize) -> Vec<u8> {
        (0..n).map(|_| self.next() as u8).collect()
    }
}

#[track_caller]
fn assert_clean(res: &TxResult, what: &str) {
    if let Err(f) = res {
        let aborted = matches!(
            f.err,
            TransactionError::InstructionError(_, InstructionError::ProgramFailedToComplete)
        );
        let faulted = f.logs.iter().any(|l| {
            l.contains("panicked") || l.contains("Access violation") || l.contains("abort")
        });
        assert!(
            !aborted && !faulted,
            "{what}: program aborted: {:?}\n{}",
            f.err,
            f.logs.join("\n")
        );
    }
}

/// A sealed ShiftLog past its 30 days.
struct Log {
    address: Address,
    /// The account as sealed (put back after a close, so the path stays
    /// reachable for the whole run).
    sealed: Account,
    /// The address its `payer_prefix` names.
    payer: Address,
    /// Its Focus Bond is still locked: it must never close.
    bonded: bool,
}

/// The fork with everything v1.3 touches in play.
struct World {
    env: Env,
    /// A registered, idle rig with two sealed logs it paid for (shift 1 with
    /// a Focus Bond that nobody resolved, shift 2 free).
    live: User,
    /// A closed rig: a tombstone at its Rig PDA and one log the crank paid.
    gone: User,
    /// The governance successor.
    next: Keypair,
    /// Every key the harness can sign with besides the crank.
    keys: Vec<Keypair>,
    logs: Vec<Log>,
    pool: Vec<Address>,
}

fn world() -> World {
    let mut env = Env::new();
    env.init_bury_vault();
    let live = User::new(&mut env, 1);
    let mut gone = User::new(&mut env, 2);
    env.onboard_standard(&live);
    env.onboard_standard(&gone);
    let cranker = env.cranker.pubkey();

    let w = live.wallet.insecure_clone();
    env.fund_skr(&w.pubkey(), 1_000 * ONE_SKR);
    ok(env.send_as(
        &w,
        &[
            ix_create_ata(&w.pubkey(), &bond_pda(&live.rig, 1), &SKR_MINT),
            ix_lock_focus_bond(&w.pubkey(), 1, 100 * ONE_SKR),
            ix_end_shift(&w.pubkey(), &live.rig, 1),
            ix_arm_wallet(&w.pubkey(), &standard_plan()),
            ix_end_shift(&w.pubkey(), &live.rig, 2),
        ],
        &[],
    ));

    // One heartbeat, the crank seals the shift (and pays its log), the wallet
    // closes the rig: a tombstone with shift_id 1 and hb_counter 1.
    let hb = gone.heartbeat(1, env.board_round, 3);
    ok(env.send(
        &[
            secp_ix_for(&[hb]),
            ix_record(&[(gone.rig, entry_for(&hb, 0, 0))]),
        ],
        &[],
    ));
    env.set_clock(env.slot, standard_plan().window_end + 1);
    let lease_to = env.rig(&gone.rig).lease_to_round.get();
    env.set_board_round(lease_to.max(env.board_round) + 1);
    ok(env.send(&[ix_end_shift(&cranker, &gone.rig, 1)], &[]));
    let g = gone.wallet.insecure_clone();
    ok(env.send_as(&g, &[ix_close_rig(&g.pubkey(), None)], &[]));
    assert_eq!(env.rig_slot(&gone.rig), RigSlot::Tombstone);
    env.advance_time(SHIFT_LOG_TTL_SECS);

    let next = Keypair::new();
    env.svm.airdrop(&next.pubkey(), SOL).unwrap();

    let logs: Vec<Log> = [
        (live.rig, 1, w.pubkey(), true),
        (live.rig, 2, w.pubkey(), false),
        (gone.rig, 1, cranker, false),
    ]
    .into_iter()
    .map(|(rig, shift, payer, bonded)| {
        let address = shift_log_pda(&rig, shift);
        Log {
            address,
            sealed: env.account(&address),
            payer,
            bonded,
        }
    })
    .collect();

    let pool = vec![
        live.rig,
        gone.rig,
        live.pubkey(),
        gone.pubkey(),
        cranker,
        env.governance.pubkey(),
        next.pubkey(),
        CONFIG,
        EXECUTOR,
        SYSTEM,
        HD,
        logs[0].address,
        logs[1].address,
        logs[2].address,
        bond_pda(&live.rig, 1),
        bond_pda(&live.rig, 2),
        bond_pda(&gone.rig, 1),
        ata(&bond_pda(&live.rig, 1), &SKR_MINT),
        BURY,
        Env::program_data(),
        ix_sysvar_id(),
        live.automation(),
        live.miner(),
        seat_pda(&live.rig),
        shift_log_pda(&live.rig, 3),
        shift_log_pda(&gone.rig, 2),
    ];
    let keys = vec![
        live.wallet.insecure_clone(),
        gone.wallet.insecure_clone(),
        env.governance.insecure_clone(),
        next.insecure_clone(),
    ];
    World {
        env,
        live,
        gone,
        next,
        keys,
        logs,
        pool,
    }
}

/// What the invariants compare against: the state after the last
/// transaction.
struct Watch {
    governance: [u8; 32],
    pending: [u8; 32],
    eta: u64,
    eta_ts: i64,
    /// `(shift_id, hb_counter)` at each Rig PDA (`live`, `gone`).
    counters: [(u64, u64); 2],
}

/// `(shift_id, hb_counter)` at a Rig PDA, from the rig or from its tombstone.
#[track_caller]
fn counters(env: &Env, rig: &Address, what: &str) -> (u64, u64) {
    match env.rig_slot(rig) {
        RigSlot::Rig => {
            let r = env.rig(rig);
            (r.shift_id.get(), r.hb_counter.get())
        }
        RigSlot::Tombstone => {
            let t = env.tombstone(rig);
            (t.shift_id.get(), t.hb_counter.get())
        }
        other => panic!("{what}: the Rig PDA lost its counters ({other:?})"),
    }
}

impl Watch {
    fn new(w: &World) -> Self {
        let c = w.env.config();
        Self {
            governance: c.governance,
            pending: c.pending_governance,
            eta: c.pending_governance_eta_slot.get(),
            eta_ts: c.pending_governance_eta_ts.get(),
            counters: [
                counters(&w.env, &w.live.rig, "setup"),
                counters(&w.env, &w.gone.rig, "setup"),
            ],
        }
    }
}

/// What one step did (for the reach counters).
#[derive(Default)]
struct Reach {
    proposed: usize,
    accepted: usize,
    cancelled: usize,
    logs_closed: usize,
    tombstones_left: usize,
    tombstones_resumed: usize,
    shifts_armed: usize,
    rejected: usize,
}

/// Send `pre` then `ix` (the crank pays; every known key that a meta marks
/// as a signer signs), demand a clean result, check every invariant, then
/// put the world back in a state where every path stays reachable.
fn step(
    w: &mut World,
    watch: &mut Watch,
    reach: &mut Reach,
    pre: &[Instruction],
    mut ix: Instruction,
    what: &str,
) {
    let cranker = w.env.cranker.pubkey();
    // Nobody can sign for a key the harness does not hold.
    for m in ix.accounts.iter_mut() {
        if m.is_signer && m.pubkey != cranker && !w.keys.iter().any(|k| k.pubkey() == m.pubkey) {
            m.is_signer = false;
        }
    }
    let mut ixs = pre.to_vec();
    ixs.push(ix.clone());
    let signs = |a: &Address| {
        ixs.iter()
            .any(|i| i.accounts.iter().any(|m| m.is_signer && m.pubkey == *a))
    };
    let signers: Vec<&Keypair> = w.keys.iter().filter(|k| signs(&k.pubkey())).collect();
    let fee = 5_000 * (1 + signers.len() as u64);
    let signed_by = |a: &[u8; 32]| *a == cranker.to_bytes() || signers.iter().any(|k| k.pubkey().to_bytes() == *a);

    let (slot, now) = (w.env.slot, w.env.now);
    let payers_before: Vec<u64> = w.logs.iter().map(|l| w.env.lamports(&l.payer)).collect();
    let slots_before = [w.env.rig_slot(&w.live.rig), w.env.rig_slot(&w.gone.rig)];
    let res = w.env.send(&ixs, &signers);
    assert_clean(&res, what);
    reach.rejected += usize::from(res.is_err());

    // ---- governance -------------------------------------------------------
    let c = w.env.config();
    let (pending, eta) = (c.pending_governance, c.pending_governance_eta_slot.get());
    let eta_ts = c.pending_governance_eta_ts.get();
    if c.governance != watch.governance {
        assert!(res.is_ok(), "{what}: governance moved in a failed transaction");
        assert_eq!(ix.data, [tag::ACCEPT_GOVERNANCE], "{what}: governance moved");
        assert_ne!(watch.pending, [0u8; 32], "{what}: no rotation was pending");
        assert_eq!(c.governance, watch.pending, "{what}: not the proposed key");
        assert!(signed_by(&c.governance), "{what}: the successor did not sign");
        assert!(slot >= watch.eta, "{what}: accepted before the slot timelock");
        assert!(now >= watch.eta_ts, "{what}: accepted before the 72 hours");
        assert_eq!(
            (pending, eta, eta_ts),
            ([0u8; 32], 0, 0),
            "{what}: rotation not cleared"
        );
        reach.accepted += 1;
    } else if (pending, eta, eta_ts) != (watch.pending, watch.eta, watch.eta_ts) {
        assert!(res.is_ok(), "{what}: rotation changed in a failed transaction");
        assert!(signed_by(&watch.governance), "{what}: governance did not sign");
        if ix.data.first() == Some(&tag::PROPOSE_GOVERNANCE) {
            assert_eq!(ix.data.len(), 33, "{what}");
            assert_eq!(pending[..], ix.data[1..], "{what}: not the key in the data");
            assert_ne!(pending, [0u8; 32], "{what}: proposed the zero key");
            assert_ne!(pending, c.governance, "{what}: proposed itself");
            assert_eq!(eta, slot + TIMELOCK_SLOTS, "{what}: wrong slot timelock");
            assert_eq!(eta_ts, now + TIMELOCK_SECS, "{what}: wrong time timelock");
            reach.proposed += 1;
        } else {
            assert_eq!(ix.data, [tag::CANCEL_GOVERNANCE], "{what}: rotation changed");
            assert_eq!(
                (pending, eta, eta_ts),
                ([0u8; 32], 0, 0),
                "{what}: not cleared"
            );
            reach.cancelled += 1;
        }
    }
    watch.governance = c.governance;
    watch.pending = pending;
    watch.eta = eta;
    watch.eta_ts = eta_ts;

    // ---- Rig PDAs: the counters never go backwards --------------------------
    let rigs = [w.live.rig, w.gone.rig];
    for (i, rig) in rigs.iter().enumerate() {
        let now = counters(&w.env, rig, what);
        let was = watch.counters[i];
        assert!(
            now.0 >= was.0 && now.1 >= was.1,
            "{what}: counters went backwards at a Rig PDA: {was:?} -> {now:?}"
        );
        if now.0 > was.0 {
            reach.shifts_armed += 1;
        }
        watch.counters[i] = now;
        match (slots_before[i], w.env.rig_slot(rig)) {
            (RigSlot::Rig, RigSlot::Tombstone) => reach.tombstones_left += 1,
            (RigSlot::Tombstone, RigSlot::Rig) => reach.tombstones_resumed += 1,
            _ => {}
        }
    }

    // ---- ShiftLogs: the rent goes to the payer the log names ----------------
    for (l, before) in w.logs.iter().zip(payers_before) {
        if !w.env.is_closed(&l.address) {
            continue;
        }
        assert!(res.is_ok(), "{what}: a log closed in a failed transaction");
        assert!(!l.bonded, "{what}: a log closed under its Focus Bond");
        assert_eq!(ix.data, [tag::CLOSE_SHIFT_LOG], "{what}: a log closed");
        let paid_fee = if l.payer == cranker { fee } else { 0 };
        assert_eq!(
            w.env.lamports(&l.payer) + paid_fee,
            before + LOG_RENT,
            "{what}: the rent did not go to the log's payer"
        );
        reach.logs_closed += 1;
        w.env.svm.set_account(l.address, l.sealed.clone()).unwrap();
    }

    // ---- an armed shift can always be ended ---------------------------------
    // (INTERFACE §10: before v1.3 a re-registered rig armed shift 1 again and
    // `end_shift` could not create the ShiftLog that already existed.)
    for u in [&w.live, &w.gone] {
        if w.env.rig_slot(&u.rig) != RigSlot::Rig {
            continue;
        }
        let r = w.env.rig(&u.rig);
        if r.shift_open == 1 {
            let wallet = u.wallet.insecure_clone();
            ok(w.env.send_as(
                &wallet,
                &[ix_end_shift(&wallet.pubkey(), &u.rig, r.shift_id.get())],
                &[],
            ));
        }
    }
}

/// Now and then move the clock by one half of the timelock, or by both: an
/// `accept_governance` that goes through on one half alone trips the
/// invariant in `step`.
fn tick(env: &mut Env, rng: &mut Rng) {
    match rng.below(12) {
        0 => env.advance_slots(TIMELOCK_SLOTS),
        1 => env.advance_time(TIMELOCK_SECS),
        2 | 3 => env.pass_timelock(),
        _ => {}
    }
}

#[test]
fn random_v13_instructions_never_abort() {
    let mut w = world();
    let mut watch = Watch::new(&w);
    let mut reach = Reach::default();
    let signers: Vec<Address> = w
        .keys
        .iter()
        .map(|k| k.pubkey())
        .chain([w.env.cranker.pubkey()])
        .collect();
    let exact = |t: u8| match t {
        tag::PROPOSE_GOVERNANCE => 32,
        tag::REGISTER_RIG => ix_register_rig(&SYSTEM, &[2u8; 33], None).data.len() - 1,
        _ => 0,
    };
    const TAGS: [u8; 6] = [
        tag::PROPOSE_GOVERNANCE,
        tag::ACCEPT_GOVERNANCE,
        tag::CANCEL_GOVERNANCE,
        tag::CLOSE_SHIFT_LOG,
        tag::REGISTER_RIG,
        tag::CLOSE_RIG,
    ];
    let mut rng = Rng(0x13C0_FFEE_2026_1001);
    const N: usize = 3_000;
    for i in 0..N {
        let t = TAGS[rng.below(TAGS.len())];
        // Half the time the exact length the tag takes, so the account checks
        // behind the length check are reached.
        let len = if rng.below(2) == 0 { exact(t) } else { rng.below(80) };
        let mut data = vec![t];
        data.extend(rng.bytes(len));
        let n_acc = rng.below(9);
        let accounts = (0..n_acc)
            .map(|_| {
                let a = w.pool[rng.below(w.pool.len())];
                AccountMeta {
                    pubkey: a,
                    is_signer: signers.contains(&a) && rng.below(4) != 0,
                    is_writable: rng.below(4) != 0,
                }
            })
            .collect();
        let ix = Instruction {
            program_id: HD,
            accounts,
            data,
        };
        step(&mut w, &mut watch, &mut reach, &[], ix, &format!("random #{i} tag {t}"));
        tick(&mut w.env, &mut rng);
    }
    println!(
        "{N} random v1.3 instructions: {} rejected cleanly, 0 aborts",
        reach.rejected
    );
}

#[test]
fn mutated_v13_instructions_never_abort_and_keep_the_invariants() {
    let mut w = world();
    let mut watch = Watch::new(&w);
    let mut reach = Reach::default();
    let cranker = w.env.cranker.pubkey();
    let gov0 = w.env.governance.pubkey();
    let next = w.next.pubkey();
    let mut rng = Rng(0x7033_B570_4E5E_ED13);
    const N: usize = 4_000;
    for i in 0..N {
        let now = w.env.now;
        let governance = Address::from(w.env.config().governance);
        assert!(governance == gov0 || governance == next);
        let other = if governance == gov0 { next } else { gov0 };
        let u = if rng.below(2) == 0 { &w.live } else { &w.gone };
        let wallet = u.pubkey();
        let mut caps = Caps::standard();
        caps.expiry = now + 86_400;
        let mut plan = standard_plan();
        plan.window_start = now - 3_600;
        plan.window_end = now + 8 * 3_600;
        let shift = match w.env.rig_slot(&u.rig) {
            RigSlot::Rig => w.env.rig(&u.rig).shift_id.get(),
            _ => 1,
        };
        let set_caps = ix_set_caps(&wallet, caps);
        let templates: [(&[Instruction], Instruction); 10] = [
            (&[], ix_propose_governance(&governance, &other)),
            (&[], ix_accept_governance(&other)),
            (&[], ix_cancel_governance(&governance)),
            (&[], ix_close_shift_log(&w.live.rig, 1, &w.live.pubkey())),
            (&[], ix_close_shift_log(&w.live.rig, 2, &w.live.pubkey())),
            (&[], ix_close_shift_log(&w.gone.rig, 1, &cranker)),
            (&[], ix_register_rig(&wallet, &u.p256(), None)),
            (&[], ix_close_rig(&wallet, None)),
            (std::slice::from_ref(&set_caps), ix_arm_wallet(&wallet, &plan)),
            (&[], ix_end_shift(&wallet, &u.rig, shift)),
        ];
        let (pre, template) = &templates[rng.below(templates.len())];
        let pre = pre.to_vec();
        let mut ix = template.clone();
        match rng.below(8) {
            0 => {
                // flip 1..8 random bits (keep the tag half the time)
                for _ in 0..1 + rng.below(8) {
                    let at = rng.below(ix.data.len());
                    if at != 0 || rng.below(2) == 0 {
                        ix.data[at] ^= 1 << rng.below(8);
                    }
                }
            }
            1 => {
                let keep = rng.below(ix.data.len() + 1);
                ix.data.truncate(keep);
            }
            2 => {
                let n = 1 + rng.below(40);
                let extra = rng.bytes(n);
                ix.data.extend(extra);
            }
            3 => {
                let a = rng.below(ix.accounts.len());
                let b = rng.below(ix.accounts.len());
                ix.accounts.swap(a, b);
            }
            4 => {
                let at = rng.below(ix.accounts.len());
                ix.accounts[at].pubkey = w.pool[rng.below(w.pool.len())];
            }
            5 => {
                // drop a signature or a writable flag
                let at = rng.below(ix.accounts.len());
                if rng.below(2) == 0 {
                    ix.accounts[at].is_signer = false;
                } else {
                    ix.accounts[at].is_writable = false;
                }
            }
            // The valid instruction as it is: the state moves on.
            _ => {}
        }
        step(&mut w, &mut watch, &mut reach, &pre, ix, &format!("mutation #{i}"));
        tick(&mut w.env, &mut rng);
    }
    println!(
        "{N} mutated v1.3 instructions: {} rejected cleanly, 0 aborts; {} rotations proposed, {} \
         accepted, {} cancelled; {} ShiftLogs closed; {} tombstones left, {} resumed; {} shifts \
         armed and ended",
        reach.rejected,
        reach.proposed,
        reach.accepted,
        reach.cancelled,
        reach.logs_closed,
        reach.tombstones_left,
        reach.tombstones_resumed,
        reach.shifts_armed
    );
    // Every path was really walked, many times over.
    for (n, name) in [
        (reach.proposed, "propose_governance"),
        (reach.accepted, "accept_governance"),
        (reach.cancelled, "cancel_governance"),
        (reach.logs_closed, "close_shift_log"),
        (reach.tombstones_left, "close_rig leaving a tombstone"),
        (reach.tombstones_resumed, "register_rig over a tombstone"),
        (reach.shifts_armed, "arm_shift after a re-registration"),
    ] {
        assert!(n >= 10, "{name} succeeded only {n} times: the fuzz lost its reach");
    }
}
