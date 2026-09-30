//! v1.2 Stack on the live-ORE fork: open, join (SKR bond through SPL Token),
//! per-round check-ins proven by P-256 heartbeats, permissionless settle,
//! pull claims, timeout refunds, and the security properties (signers,
//! owners, PDAs, conservation, double claims, early settles, fake mints,
//! Token-2022 look-alikes, wrong ATA owners).

use hd::{
    error::HdError,
    events as ev,
    message::{kind, Plan},
    skr,
    state::{break_reason, plan_flags, seat_outcome, stack_flags, stack_status},
};
use heads_down_tests::*;

/// 200 SKR plus one base unit, so the 80/20 split leaves rounding dust.
const BOND: u64 = 200 * ONE_SKR + 1;

/// What a Stack seat arms: one-round leases (focus-only here, so no dig is
/// needed; mining seats work the same, see `observe_mode_counts_a_dig`).
fn stack_plan() -> Plan {
    let mut p = standard_plan();
    p.lease = 1;
    p.flags = plan_flags::FOCUS_ONLY;
    p
}

fn player(env: &mut Env, seed: u8, plan: &Plan) -> User {
    let u = User::new(env, seed);
    ok(env.onboard(&u, SOL / 20, Caps::standard(), plan));
    env.fund_skr(&u.pubkey(), 1_000 * ONE_SKR);
    u
}

fn params(env: &Env, table_id: u64, rounds: u64, grace: u32) -> StackParams {
    StackParams {
        table_id,
        bond: BOND,
        start_round: env.board_round + 1,
        end_round: env.board_round + rounds,
        grace_gaps: grace,
        flags: 0,
        max_seats: 8,
    }
}

fn open(env: &mut Env, host: &User, p: &StackParams) -> TxResult {
    let table = table_pda(&host.pubkey(), p.table_id);
    let w = host.wallet.insecure_clone();
    env.send_as(
        &w,
        &[
            ix_create_ata(&w.pubkey(), &table, &SKR_MINT),
            ix_open_stack(&w.pubkey(), p),
        ],
        &[],
    )
}

fn join(env: &mut Env, u: &User, table: &Address) -> TxResult {
    let w = u.wallet.insecure_clone();
    env.send_as(&w, &[ix_join_stack(&w.pubkey(), table, &u.rig, None)], &[])
}

/// Verify-mode check-in of `users` in the live round: one precompile
/// instruction (index 0) with one HEARTBEAT (lease 1) per seat.
fn checkin(env: &mut Env, table: &Address, users: &mut [&mut User]) -> TxResult {
    let round = env.board_round;
    let mut hbs = vec![];
    let mut seats = vec![];
    for (i, u) in users.iter_mut().enumerate() {
        let shift = env.rig(&u.rig).shift_id.get();
        let hb = u.heartbeat(shift, round, 1);
        seats.push((stack_seat_pda(table, &u.rig), u.rig, entry_for(&hb, 0, i as u8)));
        hbs.push(hb);
    }
    env.send(&[secp_ix_for(&hbs), ix_stack_checkin(table, &seats)], &[])
}

/// `(rig, result)` of every StackCheckin in `logs`.
fn results(logs: &[String]) -> Vec<(Address, u32)> {
    events(logs)
        .into_iter()
        .filter_map(|e| match e {
            Event::StackCheckin { rig, result, .. } => Some((rig, result)),
            _ => None,
        })
        .collect()
}

fn break_p256(env: &mut Env, u: &mut User, reason: u8) {
    let counter = u.next_counter();
    let shift = env.rig(&u.rig).shift_id.get();
    let (d, s) = signal_signature(u, kind::BREAK, counter, shift, reason);
    ok(env.send(
        &[
            secp_ix(&[(s, u.p256(), d.to_vec())]),
            ix_break_p256(&u.pubkey(), reason, counter, 0, 0),
        ],
        &[],
    ));
}

fn seat(table: &Address, u: &User) -> Address {
    stack_seat_pda(table, &u.rig)
}

#[test]
fn a_table_settles_from_heartbeats_and_conserves_every_bond() {
    let mut env = Env::new();
    env.init_bury_vault();
    let plan = stack_plan();
    let mut alice = player(&mut env, 1, &plan);
    let mut bob = player(&mut env, 2, &plan);
    let mut carol = player(&mut env, 3, &plan);
    let mut dave = player(&mut env, 4, &plan);
    let mut erin = player(&mut env, 5, &plan);
    let mut frank = player(&mut env, 6, &plan);
    let r0 = env.board_round;
    // Window: 4 rounds, grace 1.
    let p = params(&env, 7, 4, 1);
    let table = table_pda(&alice.pubkey(), 7);
    let meta = ok(open(&mut env, &alice, &p));
    assert_eq!(
        events(&meta.logs),
        vec![Event::StackOpened {
            table,
            host: alice.pubkey(),
            table_id: 7,
            bond: BOND,
            start_round: r0 + 1,
            end_round: r0 + 4,
            grace_gaps: 1,
            flags: 0,
            max_seats: 8,
        }]
    );
    for (i, u) in [&alice, &bob, &carol, &dave, &erin, &frank].iter().enumerate() {
        let meta = ok(join(&mut env, u, &table));
        assert_eq!(
            events(&meta.logs),
            vec![Event::StackJoined {
                table,
                rig: u.rig,
                authority: u.pubkey(),
                sgt_mint: Address::default(),
                bond: BOND,
                seat_index: i as u8,
            }]
        );
        assert_eq!(
            env.token_balance(&ata(&u.pubkey(), &SKR_MINT)),
            1_000 * ONE_SKR - BOND
        );
    }
    let vault = ata(&table, &SKR_MINT);
    assert_eq!(env.token_balance(&vault), 6 * BOND);
    let t = env.stack_table(&table);
    assert_eq!((t.seat_count, t.total_bonds.get()), (6, 6 * BOND));

    // Round 1: everyone but dave.
    env.set_board_round(r0 + 1);
    let meta = ok(checkin(
        &mut env,
        &table,
        &mut [&mut alice, &mut bob, &mut carol, &mut erin, &mut frank],
    ));
    assert!(results(&meta.logs).iter().all(|(_, r)| *r == 0));
    // HeartbeatsRecorded is emitted for every heartbeat verified here.
    assert_eq!(
        events(&meta.logs)
            .iter()
            .filter(|e| matches!(e, Event::HeartbeatsRecorded { .. }))
            .count(),
        5
    );
    // Round 2: dave and erin miss it; carol picks her phone up afterwards.
    env.set_board_round(r0 + 2);
    ok(checkin(&mut env, &table, &mut [&mut alice, &mut bob, &mut carol, &mut frank]));
    break_p256(&mut env, &mut carol, break_reason::PICKUP);
    // Round 3: carol is broken for good (her BREAK is on-chain).
    env.set_board_round(r0 + 3);
    let meta = ok(checkin(
        &mut env,
        &table,
        &mut [&mut alice, &mut bob, &mut carol, &mut erin, &mut frank],
    ));
    assert!(results(&meta.logs).contains(&(carol.rig, HdError::StackSeatBroken.code())));
    assert_eq!(env.stack_seat(&seat(&table, &carol)).broken, 1);
    // Round 4 (the end round): frank misses it; dave shows up with 3 gaps.
    env.set_board_round(r0 + 4);
    ok(checkin(
        &mut env,
        &table,
        &mut [&mut alice, &mut bob, &mut dave, &mut erin],
    ));
    // Carol's phone is back down: her seat stays broken.
    let meta = ok(checkin(&mut env, &table, &mut [&mut carol]));
    assert_eq!(results(&meta.logs), vec![(carol.rig, HdError::StackSeatBroken.code())]);

    // Settling inside the window is refused; one round later anyone may settle.
    let seats: Vec<Address> = [&alice, &bob, &carol, &dave, &erin, &frank]
        .iter()
        .map(|u| seat(&table, u))
        .collect();
    let res = env.send(&[ix_settle_stack(&table, &seats)], &[]);
    assert_hd(&res, 0, HdError::StackNotEnded);
    env.set_board_round(r0 + 5);
    let meta = ok(env.send(&[ix_settle_stack(&table, &seats)], &[]));

    // alice, bob, erin finish (erin within grace); carol broke, dave had 3
    // gaps, frank missed the end round. F = 3 bonds; 80% split 3 ways.
    let forfeits = 3 * BOND;
    let share = (u128::from(forfeits) * 8_000 * u128::from(BOND)
        / (10_000 * u128::from(3 * BOND))) as u64;
    let payout = BOND + share;
    let bury = 6 * BOND - 3 * payout;
    assert_eq!(share, 160_000_000);
    assert_eq!(bury, 120_000_003); // 20% plus 2.4 units of rounding dust
    let evs = events(&meta.logs);
    assert_eq!(
        evs,
        vec![
            Event::BuryLotAdded {
                source: table,
                amount: bury,
                lot_skr: bury,
                start_price: skr::INITIAL_START_PRICE,
                start_slot: env.slot,
                source_kind: ev::LOT_FROM_STACK,
            },
            Event::StackSettled {
                table,
                total_bonds: 6 * BOND,
                finisher_bonds: 3 * BOND,
                payouts_total: 3 * payout,
                bury_amount: bury,
                seats: 6,
                finishers: 3,
            },
        ]
    );
    let outcome = |env: &Env, u: &User| {
        let s = env.stack_seat(&seat(&table, u));
        (s.outcome, s.payout.get(), s.checked_rounds.get())
    };
    assert_eq!(outcome(&env, &alice), (seat_outcome::FINISHED, payout, 4));
    assert_eq!(outcome(&env, &erin), (seat_outcome::FINISHED, payout, 3));
    assert_eq!(outcome(&env, &carol), (seat_outcome::FORFEITED, 0, 2));
    assert_eq!(outcome(&env, &dave), (seat_outcome::FORFEITED, 0, 1));
    assert_eq!(outcome(&env, &frank), (seat_outcome::FORFEITED, 0, 3));
    assert_eq!(env.token_balance(&ata(&BURY, &SKR_MINT)), bury);
    assert_eq!(env.bury_vault().lot_skr.get(), bury);
    assert_eq!(env.stack_table(&table).status, stack_status::SETTLED);
    // Settling twice is refused.
    let res = env.send(&[ix_settle_stack(&table, &seats)], &[]);
    assert_hd(&res, 0, HdError::InvalidStackState);

    // A claim pays only the seat's own wallet: another wallet's ATA is refused.
    let mut wrong = ix_claim_stack(&table, &seat(&table, &alice), &alice.pubkey());
    wrong.accounts[3].pubkey = ata(&bob.pubkey(), &SKR_MINT);
    assert_hd(&env.send(&[wrong], &[]), 0, HdError::InvalidTokenAccount);
    // A claim for another wallet is refused.
    let wrong = ix_claim_stack(&table, &seat(&table, &alice), &bob.pubkey());
    assert_hd(&env.send(&[wrong], &[]), 0, HdError::Unauthorized);

    // Claims are permissionless (the cranker pays the fee) and pull-based.
    let mut paid = 0;
    for u in [&alice, &bob, &carol, &dave, &erin, &frank] {
        let before = env.token_balance(&ata(&u.pubkey(), &SKR_MINT));
        let lamports = env.lamports(&u.pubkey());
        let s = seat(&table, u);
        let rent = env.lamports(&s);
        let meta = ok(env.send(&[ix_claim_stack(&table, &s, &u.pubkey())], &[]));
        let got = env.token_balance(&ata(&u.pubkey(), &SKR_MINT)) - before;
        paid += got;
        assert_eq!(
            events(&meta.logs),
            vec![Event::StackClaimed {
                table,
                rig: u.rig,
                authority: u.pubkey(),
                amount: got,
                kind: ev::CLAIM_PAYOUT,
            }]
        );
        // The seat is closed and its rent returned to its wallet.
        assert!(env.is_closed(&s));
        assert_eq!(env.lamports(&u.pubkey()), lamports + rent);
    }
    // Conservation: payouts + bury == total bonds, and the vault is empty.
    assert_eq!(paid, 3 * payout);
    assert_eq!(paid + bury, 6 * BOND);
    assert_eq!(env.token_balance(&vault), 0);
    let t = env.stack_table(&table);
    assert_eq!((t.claimed_count, t.claimed_total.get()), (6, paid));
    // Each seat claims once: the closed seat cannot claim again.
    let again = env.send(&[ix_claim_stack(&table, &seat(&table, &alice), &alice.pubkey())], &[]);
    assert_hd(&again, 0, HdError::InvalidAccountTag);
}

#[test]
fn nobody_finishing_sends_every_bond_to_bury_and_bury_only_tables_share_nothing() {
    let mut env = Env::new();
    env.init_bury_vault();
    let plan = stack_plan();
    let mut a = player(&mut env, 1, &plan);
    let mut b = player(&mut env, 2, &plan);
    let r0 = env.board_round;
    // Table 1 (80/20): nobody checks in at the end round, so nobody finishes.
    let p1 = params(&env, 1, 2, 0);
    let t1 = table_pda(&a.pubkey(), 1);
    ok(open(&mut env, &a, &p1));
    // Table 2 (bury-only): `a` finishes and gets exactly its bond back; `b`
    // misses the end round and its whole bond is buried.
    let mut p2 = params(&env, 2, 2, 0);
    p2.flags = stack_flags::BURY_ONLY;
    let t2 = table_pda(&a.pubkey(), 2);
    ok(open(&mut env, &a, &p2));
    for t in [&t1, &t2] {
        ok(join(&mut env, &a, t));
        ok(join(&mut env, &b, t));
    }
    // Round 1: the table-1 check-in verifies both heartbeats; table 2
    // observes the leases they granted (one heartbeat serves both tables).
    env.set_board_round(r0 + 1);
    ok(checkin(&mut env, &t1, &mut [&mut a, &mut b]));
    ok(env.send(
        &[ix_stack_checkin(
            &t2,
            &[
                (seat(&t2, &a), a.rig, reuse_lease()),
                (seat(&t2, &b), b.rig, reuse_lease()),
            ],
        )],
        &[],
    ));
    // Round 2 (the end round): only `a`'s heartbeat lands (through
    // record_heartbeats), and only table 2 observes it.
    env.set_board_round(r0 + 2);
    let hb = a.heartbeat(1, r0 + 2, 1);
    ok(env.send(
        &[
            secp_ix_for(&[hb]),
            ix_record(&[(a.rig, entry_for(&hb, 0, 0))]),
            ix_stack_checkin(&t2, &[(seat(&t2, &a), a.rig, reuse_lease())]),
        ],
        &[],
    ));
    env.set_board_round(r0 + 3);
    let meta = ok(env.send(&[ix_settle_stack(&t1, &[seat(&t1, &a), seat(&t1, &b)])], &[]));
    assert!(events(&meta.logs).contains(&Event::StackSettled {
        table: t1,
        total_bonds: 2 * BOND,
        finisher_bonds: 0,
        payouts_total: 0,
        bury_amount: 2 * BOND,
        seats: 2,
        finishers: 0,
    }));
    let meta = ok(env.send(&[ix_settle_stack(&t2, &[seat(&t2, &b), seat(&t2, &a)])], &[]));
    assert!(events(&meta.logs).contains(&Event::StackSettled {
        table: t2,
        total_bonds: 2 * BOND,
        finisher_bonds: BOND,
        payouts_total: BOND,
        bury_amount: BOND,
        seats: 2,
        finishers: 1,
    }));
    // The second lot restarted the auction and the lot holds both.
    assert_eq!(env.bury_vault().lot_skr.get(), 3 * BOND);
    assert_eq!(env.bury_vault().lots.get(), 2);
    assert_eq!(env.token_balance(&ata(&BURY, &SKR_MINT)), 3 * BOND);
}

#[test]
fn an_unsettled_table_refunds_every_bond_after_the_timeout() {
    let mut env = Env::new();
    let plan = stack_plan();
    let a = player(&mut env, 1, &plan);
    let b = player(&mut env, 2, &plan);
    let p = params(&env, 9, 3, 0);
    let table = table_pda(&a.pubkey(), 9);
    ok(open(&mut env, &a, &p));
    ok(join(&mut env, &a, &table));
    ok(join(&mut env, &b, &table));
    let refund_after = env.stack_table(&table).refund_after_ts.get();
    assert_eq!(
        refund_after,
        skr::refund_after(T0, p.start_round - 1, p.end_round).unwrap()
    );
    // Before the timeout an open table pays nothing.
    let res = env.send(&[ix_claim_stack(&table, &seat(&table, &a), &a.pubkey())], &[]);
    assert_hd(&res, 0, HdError::InvalidStackState);
    env.set_clock(env.slot, refund_after + 1);
    for u in [&a, &b] {
        let before = env.token_balance(&ata(&u.pubkey(), &SKR_MINT));
        let meta = ok(env.send(&[ix_claim_stack(&table, &seat(&table, u), &u.pubkey())], &[]));
        assert_eq!(env.token_balance(&ata(&u.pubkey(), &SKR_MINT)), before + BOND);
        assert!(events(&meta.logs).contains(&Event::StackClaimed {
            table,
            rig: u.rig,
            authority: u.pubkey(),
            amount: BOND,
            kind: ev::CLAIM_REFUND,
        }));
    }
    assert_eq!(env.stack_table(&table).status, stack_status::REFUNDING);
    assert_eq!(env.token_balance(&ata(&table, &SKR_MINT)), 0);
    // A refunding table can no longer be settled.
    env.set_board_round(p.end_round + 1);
    env.init_bury_vault();
    let res = env.send(&[ix_settle_stack(&table, &[])], &[]);
    assert_hd(&res, 0, HdError::InvalidStackState);
}

#[test]
fn joins_are_gated_by_time_room_signature_and_uniqueness() {
    let mut env = Env::new();
    let plan = stack_plan();
    let host = player(&mut env, 1, &plan);
    let mut p = params(&env, 3, 3, 0);
    p.max_seats = 2;
    let table = table_pda(&host.pubkey(), 3);
    ok(open(&mut env, &host, &p));
    ok(join(&mut env, &host, &table));
    // The same rig cannot take a second seat (init-only seat PDA).
    let res = join(&mut env, &host, &table);
    assert_ix_err(&res, 0, InstructionError::AccountAlreadyInitialized);
    // Without the wallet's signature: refused.
    let b = player(&mut env, 2, &plan);
    let mut ix = ix_join_stack(&b.pubkey(), &table, &b.rig, None);
    ix.accounts[0].is_signer = false;
    assert_ix_err(
        &env.send(&[ix], &[]),
        0,
        InstructionError::MissingRequiredSignature,
    );
    // Someone else's rig: refused.
    let mut ix = ix_join_stack(&b.pubkey(), &table, &host.rig, None);
    ix.accounts[1].pubkey = host.rig;
    let wb = b.wallet.insecure_clone();
    assert_hd(&env.send_as(&wb, &[ix], &[]), 0, HdError::Unauthorized);
    // A non-canonical seat address: refused.
    let mut ix = ix_join_stack(&b.pubkey(), &table, &b.rig, None);
    ix.accounts[3].pubkey = stack_seat_pda(&table, &host.rig);
    assert_ix_err(
        &env.send_as(&wb, &[ix], &[]),
        0,
        InstructionError::InvalidSeeds,
    );
    ok(join(&mut env, &b, &table));
    // Full.
    let c = player(&mut env, 3, &plan);
    assert_hd(&join(&mut env, &c, &table), 0, HdError::StackJoinClosed);
    // After the window starts, nobody joins.
    let mut p2 = params(&env, 4, 3, 0);
    p2.max_seats = 8;
    let t2 = table_pda(&host.pubkey(), 4);
    ok(open(&mut env, &host, &p2));
    env.set_board_round(p2.start_round);
    assert_hd(&join(&mut env, &c, &t2), 0, HdError::StackJoinClosed);
}

#[test]
fn token_accounts_must_be_classic_spl_skr_owned_by_the_right_wallet() {
    let mut env = Env::new();
    let plan = stack_plan();
    let host = player(&mut env, 1, &plan);
    let b = player(&mut env, 2, &plan);
    let p = params(&env, 5, 3, 0);
    let table = table_pda(&host.pubkey(), 5);
    ok(open(&mut env, &host, &p));
    let src = ata(&b.pubkey(), &SKR_MINT);
    let wb = b.wallet.insecure_clone();

    // Token-2022 look-alike: same bytes, owned by Token-2022.
    let mut acc = env.account(&src);
    acc.owner = token_2022_id();
    env.svm.set_account(src, acc).unwrap();
    assert_hd(&join(&mut env, &b, &table), 0, HdError::InvalidTokenAccount);
    // A fake mint (an SPL account for another mint).
    let fake_mint = Keypair::new().pubkey();
    env.set_token_account(&src, &fake_mint, &b.pubkey(), 1_000 * ONE_SKR);
    assert_hd(&join(&mut env, &b, &table), 0, HdError::InvalidTokenAccount);
    // SKR, but the owner field is another wallet.
    env.set_token_account(&src, &SKR_MINT, &host.pubkey(), 1_000 * ONE_SKR);
    assert_hd(&join(&mut env, &b, &table), 0, HdError::InvalidTokenAccount);
    // The table vault swapped for another SKR account: refused.
    env.set_token_account(&src, &SKR_MINT, &b.pubkey(), 1_000 * ONE_SKR);
    let mut ix = ix_join_stack(&b.pubkey(), &table, &b.rig, None);
    ix.accounts[5].pubkey = ata(&host.pubkey(), &SKR_MINT);
    assert_hd(&env.send_as(&wb, &[ix], &[]), 0, HdError::InvalidTokenAccount);
    // The token program slot must be SPL Token (not Token-2022).
    let mut ix = ix_join_stack(&b.pubkey(), &table, &b.rig, None);
    ix.accounts[7].pubkey = token_2022_id();
    assert_hd(&env.send_as(&wb, &[ix], &[]), 0, HdError::InvalidTokenAccount);
    ok(join(&mut env, &b, &table));

    // open_stack: the vault must be the table's canonical SKR ATA under SPL
    // Token; a Token-2022 account at that address is refused.
    let p2 = params(&env, 6, 3, 0);
    let t2 = table_pda(&host.pubkey(), 6);
    let v2 = ata(&t2, &SKR_MINT);
    env.set_token_account(&v2, &SKR_MINT, &t2, 0);
    let mut acc = env.account(&v2);
    acc.owner = token_2022_id();
    env.svm.set_account(v2, acc).unwrap();
    let wh = host.wallet.insecure_clone();
    assert_hd(
        &env.send_as(&wh, &[ix_open_stack(&host.pubkey(), &p2)], &[]),
        0,
        HdError::InvalidTokenAccount,
    );
    // ... and an SPL account for the right mint but owned by the host.
    env.set_token_account(&v2, &SKR_MINT, &host.pubkey(), 0);
    assert_hd(
        &env.send_as(&wh, &[ix_open_stack(&host.pubkey(), &p2)], &[]),
        0,
        HdError::InvalidTokenAccount,
    );
}

#[test]
fn open_stack_validates_every_parameter() {
    let mut env = Env::new();
    let host = player(&mut env, 1, &stack_plan());
    let w = host.wallet.insecure_clone();
    let base = params(&env, 11, 10, 2);
    let bad: Vec<(StackParams, HdError)> = vec![
        (StackParams { bond: 0, ..base }, HdError::AmountOutOfRange),
        (
            StackParams {
                bond: skr::STACK_BOND_CAP + 1,
                ..base
            },
            HdError::AmountOutOfRange,
        ),
        (
            StackParams {
                flags: stack_flags::REMOTE,
                bond: skr::REMOTE_BOND_CAP + 1,
                ..base
            },
            HdError::AmountOutOfRange,
        ),
        (
            StackParams {
                start_round: env.board_round,
                ..base
            },
            HdError::InvalidStackParams,
        ),
        (
            StackParams {
                end_round: base.start_round - 1,
                ..base
            },
            HdError::InvalidStackParams,
        ),
        (
            StackParams {
                end_round: base.start_round + skr::MAX_STACK_ROUNDS,
                ..base
            },
            HdError::InvalidStackParams,
        ),
        (
            StackParams {
                start_round: env.board_round + skr::MAX_STACK_LEAD_ROUNDS + 1,
                end_round: env.board_round + skr::MAX_STACK_LEAD_ROUNDS + 2,
                ..base
            },
            HdError::InvalidStackParams,
        ),
        (StackParams { grace_gaps: 10, ..base }, HdError::InvalidStackParams),
        (StackParams { max_seats: 1, ..base }, HdError::InvalidStackParams),
        (StackParams { max_seats: 9, ..base }, HdError::InvalidStackParams),
        (StackParams { flags: 0b1000, ..base }, HdError::InvalidStackParams),
    ];
    for (p, e) in bad {
        let table = table_pda(&host.pubkey(), p.table_id);
        let res = env.send_as(
            &w,
            &[
                ix_create_ata(&w.pubkey(), &table, &SKR_MINT),
                ix_open_stack(&w.pubkey(), &p),
            ],
            &[],
        );
        assert_hd(&res, 1, e);
    }
    // A table address that is not ["stack", host, table_id].
    let mut ix = ix_open_stack(&w.pubkey(), &base);
    ix.data[1] ^= 1;
    let res = env.send_as(
        &w,
        &[ix_create_ata(&w.pubkey(), &table_pda(&host.pubkey(), 11), &SKR_MINT), ix],
        &[],
    );
    assert_ix_err(&res, 1, InstructionError::InvalidSeeds);
    // The host must sign.
    let mut ix = ix_open_stack(&w.pubkey(), &base);
    ix.accounts[0].is_signer = false;
    ix.accounts[0].is_writable = false;
    let res = env.send(
        &[ix_create_ata(&env.cranker.pubkey(), &table_pda(&host.pubkey(), 11), &SKR_MINT), ix],
        &[],
    );
    assert_ix_err(&res, 1, InstructionError::MissingRequiredSignature);
    // Remote tables are always attested-only.
    let remote = StackParams {
        flags: stack_flags::REMOTE,
        bond: skr::REMOTE_BOND_CAP,
        ..base
    };
    ok(open(&mut env, &host, &remote));
    let t = env.stack_table(&table_pda(&host.pubkey(), 11));
    assert_eq!(t.flags, stack_flags::REMOTE | stack_flags::ATTESTED_ONLY);
}

#[test]
fn check_ins_prove_each_round_and_nothing_else() {
    let mut env = Env::new();
    let plan = stack_plan();
    let mut a = player(&mut env, 1, &plan);
    let mut b = player(&mut env, 2, &plan);
    // `c` arms with 3-round leases: one heartbeat could cover 3 rounds.
    let mut lazy = stack_plan();
    lazy.lease = 3;
    let mut c = player(&mut env, 3, &lazy);
    let r0 = env.board_round;
    let p = params(&env, 1, 3, 1);
    let table = table_pda(&a.pubkey(), 1);
    ok(open(&mut env, &a, &p));
    for u in [&a, &b, &c] {
        ok(join(&mut env, u, &table));
    }
    // Before the window: the whole check-in is refused.
    let res = checkin(&mut env, &table, &mut [&mut a]);
    assert_hd(&res, 1, HdError::InvalidStackState);
    env.set_board_round(r0 + 1);
    // Observe mode with no heartbeat this round: LeaseExpired, nothing counted.
    let meta = ok(env.send(
        &[ix_stack_checkin(&table, &[(seat(&table, &b), b.rig, reuse_lease())])],
        &[],
    ));
    assert_eq!(results(&meta.logs), vec![(b.rig, HdError::LeaseExpired.code())]);
    assert_eq!(env.stack_seat(&seat(&table, &b)).checked_rounds.get(), 0);
    // A 3-round lease plan is refused per seat, without consuming the heartbeat.
    let counter_before = env.rig(&c.rig).hb_counter.get();
    let meta = ok(checkin(&mut env, &table, &mut [&mut c]));
    assert_eq!(results(&meta.logs), vec![(c.rig, HdError::StackLeaseTooLong.code())]);
    assert_eq!(env.rig(&c.rig).hb_counter.get(), counter_before);
    // A heartbeat signed for the previous round lands late: consumed by the
    // rig, but it does not cover this round, so the seat does not count it.
    let hb = a.heartbeat(1, r0, 1);
    let meta = ok(env.send(
        &[
            secp_ix_for(&[hb]),
            ix_stack_checkin(&table, &[(seat(&table, &a), a.rig, entry_for(&hb, 0, 0))]),
        ],
        &[],
    ));
    assert_eq!(results(&meta.logs), vec![(a.rig, HdError::LeaseExpired.code())]);
    // A replayed heartbeat is stale.
    let old = a.heartbeat_with(1, 1, r0 + 1, 1);
    let meta = ok(env.send(
        &[
            secp_ix_for(&[old]),
            ix_stack_checkin(&table, &[(seat(&table, &a), a.rig, entry_for(&old, 0, 0))]),
        ],
        &[],
    ));
    assert_eq!(results(&meta.logs), vec![(a.rig, HdError::StaleHeartbeat.code())]);
    // A good one counts, and repeating the check-in in the same round is a no-op.
    ok(checkin(&mut env, &table, &mut [&mut a]));
    let meta = ok(env.send(
        &[ix_stack_checkin(&table, &[(seat(&table, &a), a.rig, reuse_lease())])],
        &[],
    ));
    assert_eq!(results(&meta.logs), vec![(a.rig, 0)]);
    let s = env.stack_seat(&seat(&table, &a));
    assert_eq!((s.checked_rounds.get(), s.last_round.get(), s.shift_id.get()), (1, r0 + 1, 1));

    // Account mix-ups fail the whole transaction: another seat for this rig,
    // a duplicated seat.
    let res = env.send(
        &[ix_stack_checkin(&table, &[(seat(&table, &b), a.rig, reuse_lease())])],
        &[],
    );
    assert_hd(&res, 0, HdError::StackSeatMismatch);
    let res = env.send(
        &[ix_stack_checkin(
            &table,
            &[
                (seat(&table, &a), a.rig, reuse_lease()),
                (seat(&table, &a), a.rig, reuse_lease()),
            ],
        )],
        &[],
    );
    assert_hd(&res, 0, HdError::DuplicateRig);
    // A seat of another table.
    let p2 = params(&env, 2, 3, 1);
    let t2 = table_pda(&a.pubkey(), 2);
    env.set_board_round(r0);
    ok(open(&mut env, &a, &p2));
    ok(join(&mut env, &b, &t2));
    env.set_board_round(r0 + 1);
    let res = env.send(
        &[ix_stack_checkin(&table, &[(seat(&t2, &b), b.rig, reuse_lease())])],
        &[],
    );
    assert_hd(&res, 0, HdError::StackSeatMismatch);

    // A seat bound to shift 1 does not count a later shift.
    env.set_board_round(r0 + 2);
    let w = b.wallet.insecure_clone();
    ok(checkin(&mut env, &table, &mut [&mut b]));
    ok(env.send_as(&w, &[ix_end_shift(&w.pubkey(), &b.rig, 1)], &[]));
    ok(env.send_as(&w, &[ix_arm_wallet(&w.pubkey(), &plan)], &[]));
    let meta = ok(checkin(&mut env, &table, &mut [&mut b]));
    assert_eq!(results(&meta.logs), vec![(b.rig, HdError::StackShiftMismatch.code())]);
}

#[test]
fn a_break_before_the_seat_binds_only_delays_it() {
    let mut env = Env::new();
    let plan = stack_plan();
    let mut a = player(&mut env, 1, &plan);
    let r0 = env.board_round;
    let p = params(&env, 1, 3, 1);
    let table = table_pda(&a.pubkey(), 1);
    ok(open(&mut env, &a, &p));
    ok(join(&mut env, &a, &table));
    // A pickup at dinner, before the window, while the shift was armed.
    break_p256(&mut env, &mut a, break_reason::PICKUP);
    env.set_board_round(r0 + 1);
    let counter = env.rig(&a.rig).hb_counter.get();
    let meta = ok(checkin(&mut env, &table, &mut [&mut a]));
    // Refused (not bound, not broken) and the heartbeat is not consumed.
    assert_eq!(results(&meta.logs), vec![(a.rig, HdError::InvalidRigState.code())]);
    assert_eq!(env.rig(&a.rig).hb_counter.get(), counter);
    let s = env.stack_seat(&seat(&table, &a));
    assert_eq!((s.broken, s.shift_id.get(), s.checked_rounds.get()), (0, 0, 0));
    // End that shift and arm a fresh one: the seat binds to shift 2.
    let w = a.wallet.insecure_clone();
    ok(env.send_as(&w, &[ix_end_shift(&w.pubkey(), &a.rig, 1)], &[]));
    ok(env.send_as(&w, &[ix_arm_wallet(&w.pubkey(), &plan)], &[]));
    for r in [r0 + 2, r0 + 3] {
        env.set_board_round(r);
        let meta = ok(checkin(&mut env, &table, &mut [&mut a]));
        assert_eq!(results(&meta.logs), vec![(a.rig, 0)]);
    }
    let s = env.stack_seat(&seat(&table, &a));
    assert_eq!((s.shift_id.get(), s.checked_rounds.get()), (2, 2));
    // One gap (round 1) within grace 1: a lone finisher gets its bond back
    // and nothing goes to Bury (no BuryVault needed).
    env.set_board_round(r0 + 4);
    let meta = ok(env.send(&[ix_settle_stack(&table, &[seat(&table, &a)])], &[]));
    assert!(events(&meta.logs).contains(&Event::StackSettled {
        table,
        total_bonds: BOND,
        finisher_bonds: BOND,
        payouts_total: BOND,
        bury_amount: 0,
        seats: 1,
        finishers: 1,
    }));
    // After binding, a break is final: a new table, a pickup mid-window.
    let r1 = env.board_round;
    let p2 = params(&env, 2, 3, 3 - 1);
    let t2 = table_pda(&a.pubkey(), 2);
    ok(open(&mut env, &a, &p2));
    ok(join(&mut env, &a, &t2));
    env.set_board_round(r1 + 1);
    ok(checkin(&mut env, &t2, &mut [&mut a]));
    break_p256(&mut env, &mut a, break_reason::SCREEN_ON);
    env.set_board_round(r1 + 2);
    let meta = ok(checkin(&mut env, &t2, &mut [&mut a]));
    assert_eq!(results(&meta.logs), vec![(a.rig, HdError::StackSeatBroken.code())]);
    assert_eq!(env.stack_seat(&seat(&t2, &a)).broken, 1);
}

#[test]
fn observe_mode_counts_a_dig_and_a_freeze_breaks_the_seat() {
    let mut env = Env::new();
    // A mining seat: the normal plan with one-round leases.
    let mut plan = standard_plan();
    plan.lease = 1;
    let mut a = player(&mut env, 1, &plan);
    let mut b = player(&mut env, 2, &plan);
    let r0 = env.board_round;
    let p = params(&env, 1, 1, 0);
    let table = table_pda(&a.pubkey(), 1);
    ok(open(&mut env, &a, &p));
    ok(join(&mut env, &a, &table));
    ok(join(&mut env, &b, &table));
    // The one window round is the live ORE round the fixture's Round PDA
    // belongs to, so move the Board back to it for the dig.
    env.set_board_round(r0 + 1);
    let live_round_pda = env.round;
    let live = u64_at(&env.account(&live_round_pda).data, 8);
    assert_eq!(live, r0);
    // Re-point the fixture Round to r0 + 1 so `dig` accepts it.
    let mut round = env.account(&live_round_pda);
    round.data[8..16].copy_from_slice(&(r0 + 1).to_le_bytes());
    let new_round = round_pda(r0 + 1);
    env.svm.set_account(new_round, round).unwrap();
    env.round = new_round;
    // [secp(hb), dig(fresh hb), stack_checkin(observe)] in one transaction.
    let hb = a.heartbeat(1, r0 + 1, 1);
    let meta = ok(env.send(
        &[
            compute_limit(1_400_000),
            secp_ix_for(&[hb]),
            ix_dig(&env.cranker.pubkey(), &env.round, &[DigRig::new(&a, entry_for(&hb, 1, 0))]),
            ix_stack_checkin(&table, &[(seat(&table, &a), a.rig, reuse_lease())]),
        ],
        &[],
    ));
    assert!(dug(&events(&meta.logs), &a.rig).is_some());
    assert_eq!(results(&meta.logs), vec![(a.rig, 0)]);
    // b checks in (bound, counted), then freezes in the same round (phone
    // FREEZE is tighten-only): a later check-in that round breaks the seat.
    let meta = ok(checkin(&mut env, &table, &mut [&mut b]));
    assert_eq!(results(&meta.logs), vec![(b.rig, 0)]);
    let counter = b.next_counter();
    let (d, s) = signal_signature(&b, kind::FREEZE, counter, 1, break_reason::FREEZE);
    ok(env.send(
        &[
            secp_ix(&[(s, b.p256(), d.to_vec())]),
            ix_freeze_p256(&b.pubkey(), break_reason::FREEZE, counter, 0, 0),
        ],
        &[],
    ));
    let meta = ok(env.send(
        &[ix_stack_checkin(&table, &[(seat(&table, &b), b.rig, reuse_lease())])],
        &[],
    ));
    assert_eq!(results(&meta.logs), vec![(b.rig, HdError::StackSeatBroken.code())]);
    env.init_bury_vault();
    env.set_board_round(r0 + 2);
    let meta = ok(env.send(
        &[ix_settle_stack(&table, &[seat(&table, &a), seat(&table, &b)])],
        &[],
    ));
    assert!(events(&meta.logs).iter().any(|e| matches!(
        e,
        Event::StackSettled { finishers: 1, .. }
    )));
}

#[test]
fn settle_needs_every_seat_exactly_once() {
    let mut env = Env::new();
    env.init_bury_vault();
    let plan = stack_plan();
    let a = player(&mut env, 1, &plan);
    let b = player(&mut env, 2, &plan);
    let r0 = env.board_round;
    let p = params(&env, 1, 1, 0);
    let table = table_pda(&a.pubkey(), 1);
    ok(open(&mut env, &a, &p));
    let p2 = params(&env, 2, 1, 0);
    let other = table_pda(&a.pubkey(), 2);
    ok(open(&mut env, &a, &p2));
    for t in [&table, &other] {
        ok(join(&mut env, &a, t));
        ok(join(&mut env, &b, t));
    }
    env.set_board_round(r0 + 2);
    let (sa, sb) = (seat(&table, &a), seat(&table, &b));
    for seats in [
        vec![sa],
        vec![sa, sa],
        vec![sa, seat(&other, &b)],
        vec![sa, sb, sb],
    ] {
        let res = env.send(&[ix_settle_stack(&table, &seats)], &[]);
        assert_hd(&res, 0, HdError::StackSeatMismatch);
    }
    // A seat passed read-only is refused (it must record its outcome).
    let mut ix = ix_settle_stack(&table, &[sa, sb]);
    ix.accounts[7].is_writable = false;
    assert_ix_err(&env.send(&[ix], &[]), 0, InstructionError::InvalidAccountData);
    // The Bury ATA must be the BuryVault's.
    let mut ix = ix_settle_stack(&table, &[sa, sb]);
    ix.accounts[4].pubkey = ata(&a.pubkey(), &SKR_MINT);
    assert_hd(&env.send(&[ix], &[]), 0, HdError::InvalidTokenAccount);
    // A fake BuryVault is refused.
    let mut ix = ix_settle_stack(&table, &[sa, sb]);
    ix.accounts[3].pubkey = table;
    assert_ix_err(&env.send(&[ix], &[]), 0, InstructionError::InvalidSeeds);
    ok(env.send(&[ix_settle_stack(&table, &[sb, sa])], &[]));
}

#[test]
fn remote_tables_take_only_attested_seekers_one_seat_per_sgt() {
    let mut env = Env::new();
    let plan = stack_plan();
    let host = player(&mut env, 1, &plan);
    let guest = player(&mut env, 2, &plan);
    let seeker = player(&mut env, 3, &plan);
    let mut p = params(&env, 1, 3, 0);
    p.flags = stack_flags::REMOTE;
    p.bond = skr::REMOTE_BOND_CAP;
    let table = table_pda(&host.pubkey(), 1);
    ok(open(&mut env, &host, &p));

    // The seeker holds real SGT #20 and verifies it (tier 1).
    let (mint, sgt_account) = env.give_real_sgt("member-20", &seeker.pubkey());
    let ws = seeker.wallet.insecure_clone();
    ok(env.send_as(
        &ws,
        &[ix_verify_seeker(&ws.pubkey(), &sgt_account, &mint, None)],
        &[],
    ));
    let join_remote = |u: &User| ix_join_stack(&u.pubkey(), &table, &mint, Some((sgt_account, mint)));

    // Not attested yet: refused.
    assert_hd(
        &env.send_as(&ws, &[join_remote(&seeker)], &[]),
        0,
        HdError::StackIneligible,
    );
    // An expired attestation: refused.
    env.set_attestation(&seeker.rig, 1, env.slot);
    assert_hd(
        &env.send_as(&ws, &[join_remote(&seeker)], &[]),
        0,
        HdError::StackIneligible,
    );
    env.set_attestation(&seeker.rig, 1, env.slot + 1_000_000);
    // A guest (tier 0), even attested: refused.
    env.set_attestation(&guest.rig, 2, env.slot + 1_000_000);
    let wg = guest.wallet.insecure_clone();
    let (mint2, sgt2) = env.give_real_sgt("member-121035", &guest.pubkey());
    let ix = ix_join_stack(&guest.pubkey(), &table, &mint2, Some((sgt2, mint2)));
    assert_hd(&env.send_as(&wg, &[ix], &[]), 0, HdError::StackIneligible);
    // Without the SGT accounts: refused.
    let ix = ix_join_stack(&seeker.pubkey(), &table, &mint, None);
    #[allow(deprecated)] // the runtime still reports ProgramError::NotEnoughAccountKeys this way
    let missing = InstructionError::NotEnoughAccountKeys;
    assert_ix_err(&env.send_as(&ws, &[ix], &[]), 0, missing);
    // The SGT moved away (the rig still says tier 1): re-verification fails.
    let mut moved = env.account(&sgt_account);
    moved.data[64..72].copy_from_slice(&0u64.to_le_bytes());
    let original = env.account(&sgt_account);
    env.svm.set_account(sgt_account, moved).unwrap();
    let res = env.send_as(&ws, &[join_remote(&seeker)], &[]);
    assert_custom(&res, 0, sgt_verify::SgtError::AmountNotOne.program_error_code());
    env.svm.set_account(sgt_account, original).unwrap();
    // Attested Seeker with a live SGT: seated, keyed by the SGT mint.
    let meta = ok(env.send_as(&ws, &[join_remote(&seeker)], &[]));
    assert!(events(&meta.logs).contains(&Event::StackJoined {
        table,
        rig: seeker.rig,
        authority: seeker.pubkey(),
        sgt_mint: mint,
        bond: skr::REMOTE_BOND_CAP,
        seat_index: 0,
    }));
    let s = env.stack_seat(&stack_seat_pda(&table, &mint));
    assert_eq!((s.sgt_verified, s.sgt_mint), (1, mint.to_bytes()));
    // The same SGT cannot take a second seat.
    assert_ix_err(
        &env.send_as(&ws, &[join_remote(&seeker)], &[]),
        0,
        InstructionError::AccountAlreadyInitialized,
    );
}

#[test]
fn guests_join_in_person_tables_only_under_the_guest_cap() {
    let mut env = Env::new();
    let plan = stack_plan();
    let host = player(&mut env, 1, &plan);
    let guest = player(&mut env, 2, &plan);
    let mut p = params(&env, 1, 3, 0);
    p.bond = skr::GUEST_BOND_CAP + 1;
    let table = table_pda(&host.pubkey(), 1);
    ok(open(&mut env, &host, &p));
    assert_hd(&join(&mut env, &guest, &table), 0, HdError::StackIneligible);
    // At the guest cap, guests are welcome.
    let mut p2 = params(&env, 2, 3, 0);
    p2.bond = skr::GUEST_BOND_CAP;
    let t2 = table_pda(&host.pubkey(), 2);
    ok(open(&mut env, &host, &p2));
    ok(join(&mut env, &guest, &t2));
    // An attested-only in-person table refuses an unattested guest.
    let mut p3 = params(&env, 3, 3, 0);
    p3.bond = skr::GUEST_BOND_CAP;
    p3.flags = stack_flags::ATTESTED_ONLY;
    let t3 = table_pda(&host.pubkey(), 3);
    ok(open(&mut env, &host, &p3));
    assert_hd(&join(&mut env, &guest, &t3), 0, HdError::StackIneligible);
    env.set_attestation(&guest.rig, 1, env.slot + 1_000);
    ok(join(&mut env, &guest, &t3));
}
