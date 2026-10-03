//! The crank's SKR duties against the real `heads_down` program on the fork of live ORE
//! (`--features real-program`): a full Stack table (open, join, check-ins every round in
//! verify and observe mode, a seat that breaks, settle, claims), the check-in capacity and
//! its measured cost, a missed round against the table's grace, and the two permissionless
//! cleanups (`forfeit_focus_bond`, `refund_gift`).
//!
//! The fork cannot mint SKR, so balances are written into SPL Token accounts directly (the
//! mint itself is the live mainnet account, from `fetch-fixtures.sh`). Everything else goes
//! through the program: the table, the seats, the bonds and the gifts are created by real
//! instructions, and every transaction the crank would send is built by the crank's own
//! planner and builders.

use std::collections::HashSet;

use crate::common::skr as wallet_ix;
use hd_crank::skr::{self, BondResolution, FocusBond, GiftEscrow, StackSeat, StackTable, ONE_SKR, SKR_MINT};
use hd_crank::stack::{self, CheckinLedger, CheckinRetry, CheckinStatus, Missed, RoundNote, SeatPlan, SeatSkip, SeatState, SendPolicy, TablePlan, Wait};
use hd_crank::tx::{CheckinCu, CheckinSeat};

use super::*;

const BOND: u64 = 200 * ONE_SKR;
const FUNDED: u64 = 1_000 * ONE_SKR;

impl Fork {
    /// The live SKR mint (classic SPL Token, 6 decimals).
    fn load_skr(&mut self) {
        let path = fixtures().join(format!("{SKR_MINT}.json"));
        assert!(
            path.exists(),
            "missing {} — run programs/heads-down/tests/fixtures/fetch-fixtures.sh (the Stack tests need the SKR mint)",
            path.display()
        );
        self.svm.set_account(SKR_MINT, load_account(&SKR_MINT.to_string())).unwrap();
    }

    /// Give `owner` an SKR ATA holding `amount` base units (fixture surgery).
    fn fund_skr(&mut self, owner: &Address, amount: u64) -> Address {
        let a = skr::ata(owner, &SKR_MINT);
        self.svm
            .set_account(
                a,
                Account {
                    lamports: wallet_ix::TOKEN_ACCOUNT_RENT,
                    data: wallet_ix::token_account_bytes(&SKR_MINT, owner, amount),
                    owner: skr::SPL_TOKEN_PROGRAM_ID,
                    executable: false,
                    rent_epoch: u64::MAX,
                },
            )
            .unwrap();
        a
    }

    fn skr_balance(&self, token_account: &Address) -> u64 {
        self.account(token_account).and_then(|a| skr::token_amount(&a.owner, &a.data)).unwrap_or(0)
    }

    /// Move the ORE Board to `round` (fixture surgery: only `round_id` changes).
    fn set_board_round(&mut self, round: u64) {
        let mut acc = self.svm.get_account(&ore::BOARD_ADDRESS).unwrap();
        acc.data[8..16].copy_from_slice(&round.to_le_bytes());
        self.svm.set_account(ore::BOARD_ADDRESS, acc).unwrap();
        self.board.round_id = round;
    }

    fn set_unix_time(&mut self, ts: i64) {
        let mut clock = self.svm.get_sysvar::<solana_clock::Clock>();
        clock.unix_timestamp = ts;
        self.svm.set_sysvar(&clock);
    }

    /// A transaction signed and paid by user `u`'s wallet.
    fn send_wallet(&mut self, u: usize, ixs: &[Instruction]) -> Result<litesvm::types::TransactionMetadata, String> {
        let payer = self.users[u].wallet.pubkey();
        let t = solana_transaction::Transaction::new_signed_with_payer(ixs, Some(&payer), &[&self.users[u].wallet], self.svm.latest_blockhash());
        let r = self.svm.send_transaction(t).map_err(|e| format!("{:?}\n{}", e.err, e.meta.logs.join("\n")));
        self.svm.expire_blockhash();
        r
    }

    /// A legacy transaction paid by the crank (what `send_signed` builds).
    fn send_crank(&mut self, ixs: &[Instruction]) -> Result<litesvm::types::TransactionMetadata, String> {
        let t = tx::sign_legacy(ixs, &self.cranker, self.svm.latest_blockhash()).unwrap();
        self.send(t)
    }

    fn stack_table(&self, a: &Address) -> StackTable {
        let acc = self.account(a).expect("StackTable");
        StackTable::decode(&hd::PROGRAM_ID, &acc.owner, &acc.data).unwrap()
    }

    fn stack_seat(&self, a: &Address) -> StackSeat {
        let acc = self.account(a).expect("StackSeat");
        StackSeat::decode(&hd::PROGRAM_ID, &acc.owner, &acc.data).unwrap()
    }

    /// A rig as a Stack seat arms it: one-round leases, a shift armed in `round - 1` with no
    /// break; `focus` makes it a focus-only plan (what the app arms for a table by default).
    fn arm_for_stack(&mut self, u: usize, focus: bool) {
        let start = self.board.round_id;
        self.set_rig(u, |rig| {
            rig.plan_lease_rounds = 1;
            rig.shift_start_round = start;
            if focus {
                rig.plan_flags = hd::PLAN_FLAG_FOCUS_ONLY;
                rig.plan_dig_lamports = 0;
                rig.plan_split_tiles = 0;
            }
        });
    }

    /// `[ATA CreateIdempotent(table, SKR), open_stack]` by user `host`, then one `join_stack`
    /// per user in `seats`. Returns the table and the seat addresses in join order.
    fn open_table(&mut self, host: usize, p: &wallet_ix::StackParams, seats: &[usize]) -> (Address, Vec<Address>) {
        let host_wallet = self.users[host].wallet.pubkey();
        let table = skr::stack_table_pda(&hd::PROGRAM_ID, &host_wallet, p.table_id).0;
        let meta = self
            .send_wallet(
                host,
                &[skr::create_ata_idempotent_ix(&host_wallet, &table, &SKR_MINT), wallet_ix::open_stack_ix(&hd::PROGRAM_ID, &host_wallet, p)],
            )
            .expect("open_stack");
        let evs = hd::events_from_logs(&hd::PROGRAM_ID, &meta.logs);
        assert!(matches!(evs[..], [HdEvent::StackOpened { table: t, bond, start_round, end_round, .. }] if t == table && bond == p.bond && start_round == p.start_round && end_round == p.end_round), "{evs:?}");
        let mut out = Vec::new();
        for (i, &u) in seats.iter().enumerate() {
            let wallet = self.users[u].wallet.pubkey();
            self.fund_skr(&wallet, FUNDED);
            let meta = self.send_wallet(u, &[wallet_ix::join_stack_ix(&hd::PROGRAM_ID, &wallet, &table)]).expect("join_stack");
            let seat = skr::stack_seat_pda(&hd::PROGRAM_ID, &table, &self.users[u].rig).0;
            let evs = hd::events_from_logs(&hd::PROGRAM_ID, &meta.logs);
            assert!(
                matches!(evs[..], [HdEvent::StackJoined { table: t, rig, seat_index, bond, .. }] if t == table && rig == self.users[u].rig && usize::from(seat_index) == i && bond == p.bond),
                "{evs:?}"
            );
            out.push(seat);
        }
        (table, out)
    }

    /// What the crank reads each pass: every seat of the table and its rig.
    fn seat_states(&self, seats: &[Address]) -> Vec<SeatState> {
        seats
            .iter()
            .map(|a| {
                let seat = self.stack_seat(a);
                let rig = self.account(&seat.rig).and_then(|acc| Rig::decode(&hd::PROGRAM_ID, &acc.owner, &acc.data).ok());
                SeatState { address: *a, seat, rig }
            })
            .collect()
    }

    /// One pass of the Stack planner for `table` in the live round.
    fn plan_checkins(&self, table: &Address, seats: &[Address], store: &HeartbeatStore) -> TablePlan {
        let heartbeats = store.snapshot().into_iter().map(|h| (h.rig, h)).collect();
        stack::plan_table(self.board.round_id, &self.stack_table(table), &self.seat_states(seats), &heartbeats, &|_| false, &mut HashSet::new())
    }

    /// Pack, sign and land one table's check-ins; returns `(rig, result, checked_rounds)` per
    /// `StackCheckin` event and the fee the crank paid, and removes consumed heartbeats.
    fn land_checkins(
        &mut self,
        table: &Address,
        entries: &[CheckinSeat],
        format: TxFormat,
        store: &HeartbeatStore,
        label: &str,
    ) -> (Vec<(Address, u32, u64)>, u64) {
        let mut p = self.params(format);
        p.cu_price_micro_lamports = hd_crank::config::StackConfig::default().cu_price_micro_lamports;
        p.max_rigs_per_tx = skr::MAX_SEATS;
        let alts = if format == TxFormat::V0 { self.alts.clone() } else { vec![] };
        let (batches, rejected) = tx::pack_checkins(&p, &CheckinCu::default(), table, entries, &alts);
        assert!(rejected.is_empty(), "{rejected:?}");
        let mut results = Vec::new();
        let mut paid = 0u64;
        for b in &batches {
            // Size the CU limit the way the crank does: simulate, +15% + 1,000.
            let sim_tx = tx::sign_checkin_batch(&p, b.cu_limit, table, &b.seats, &alts, self.svm.latest_blockhash(), &self.cranker).unwrap();
            let used = self.svm.simulate_transaction(sim_tx).expect("check-in simulates").meta.compute_units_consumed;
            assert!(used <= u64::from(b.cu_limit), "the estimate {} covers the {used} CU used", b.cu_limit);
            let cu_limit = u32::try_from(used * 115 / 100 + 1_000).unwrap();
            let before = self.balance(&self.cranker.pubkey());
            let t = tx::sign_checkin_batch(&p, cu_limit, table, &b.seats, &alts, self.svm.latest_blockhash(), &self.cranker).unwrap();
            let meta = self.send(t).expect("stack_checkin lands");
            let fee = before - self.balance(&self.cranker.pubkey());
            assert_eq!(fee, tx::fee_for(b.precompile_signatures, cu_limit, p.cu_price_micro_lamports, tx::LAMPORTS_PER_SIGNATURE));
            paid += fee;
            let n = b.seats.len() as u64;
            println!(
                "stack_checkin {label} ({format:?}): {n} seats ({} verified), {} bytes, {} CU, fee {fee} lamports ({} per seat)",
                b.precompile_signatures,
                b.wire_size,
                meta.compute_units_consumed,
                fee / n
            );
            for e in hd::events_from_logs(&hd::PROGRAM_ID, &meta.logs) {
                match e {
                    HdEvent::StackCheckin { table: t, rig, round_id, checked_rounds, result } => {
                        assert_eq!((t, round_id), (*table, self.board.round_id));
                        if stack::heartbeat_consumed_by(result) {
                            if let Some(h) = b.seats.iter().find(|s| s.rig == rig).and_then(|s| s.heartbeat) {
                                store.remove_if_counter_at_most(&rig, h.fields.counter);
                            }
                        }
                        results.push((rig, result, checked_rounds));
                    }
                    HdEvent::HeartbeatsRecorded { rig, round_id, .. } => {
                        assert_eq!(round_id, self.board.round_id);
                        assert!(b.seats.iter().any(|s| s.rig == rig && s.heartbeat.is_some()), "only verify-mode seats record a heartbeat");
                    }
                    other => panic!("unexpected {other:?}"),
                }
            }
        }
        (results, paid)
    }

    /// The crank's one-time Bury setup: the BuryVault and the lot's SKR ATA.
    fn init_bury(&mut self) -> u64 {
        let cranker = self.cranker.pubkey();
        let before = self.balance(&cranker);
        let ixs = tx::init_bury_vault_instructions(&hd::PROGRAM_ID, &cranker, true, true, 60_000, 1_000);
        let meta = self.send_crank(&ixs).expect("init_bury_vault");
        let paid = before - self.balance(&cranker);
        let bury = skr::bury_vault_pda(&hd::PROGRAM_ID).0;
        let acc = self.account(&bury).unwrap();
        let v = skr::BuryVault::decode(&hd::PROGRAM_ID, &acc.owner, &acc.data).unwrap();
        assert_eq!(v.skr_vault, skr::ata(&bury, &SKR_MINT));
        assert_eq!(self.skr_balance(&v.skr_vault), 0);
        println!(
            "init_bury_vault + the lot's ATA: {} CU, paid {paid} lamports (BuryVault rent {} + ATA rent {} + fee)",
            meta.compute_units_consumed,
            acc.lamports,
            self.balance(&v.skr_vault)
        );
        paid
    }
}

fn result_of(results: &[(Address, u32, u64)], rig: &Address) -> (u32, u64) {
    results.iter().find(|(r, _, _)| r == rig).map(|(_, res, checked)| (*res, *checked)).unwrap_or_else(|| panic!("no StackCheckin for {rig}"))
}

#[test]
fn crank_runs_a_full_stack_table() {
    let mut f = Fork::new();
    f.load_skr();
    let store = Arc::new(HeartbeatStore::new(100));
    let r = f.board.round_id; // the live fixture round: the table's first round, with a real dig
    let a = f.add_user(200); // focus-only: checks in every round, finishes
    let b = f.add_user(201); // a digging rig: checks in every round, digs in round r, finishes
    let c = f.add_user(202); // focus-only: picks the phone up in round r+1, forfeits
    // The table opens one round before its window.
    f.set_board_round(r - 1);
    f.arm_for_stack(a, true);
    f.arm_for_stack(b, false);
    f.arm_for_stack(c, true);
    let params = wallet_ix::StackParams { table_id: 1, bond: BOND, start_round: r, end_round: r + 2, grace_gaps: 0, flags: 0, max_seats: 4 };
    let (table, seats) = f.open_table(a, &params, &[a, b, c]);
    let t = f.stack_table(&table);
    assert_eq!((t.seat_count, t.total_bonds, t.status, t.vault), (3, 3 * BOND, skr::status::OPEN, skr::ata(&table, &SKR_MINT)));
    assert_eq!(f.skr_balance(&t.vault), 3 * BOND, "the bonds sit in the table's vault");
    let rig = |f: &Fork, u: usize| f.users[u].rig;
    let (rig_a, rig_b, rig_c) = (rig(&f, a), rig(&f, b), rig(&f, c));

    // Before the window nothing is planned (a check-in would fail: InvalidStackState).
    f.heartbeat(&store, a, 1, 1).unwrap();
    assert!(f.plan_checkins(&table, &seats, &store).seats.is_empty());
    store.remove_if_counter_at_most(&rig_a, 1);
    let bury_cost = f.init_bury();

    // ---- round r: every seat's heartbeat is verified by the check-in itself ---------------
    f.set_board_round(r);
    for (u, counter) in [(a, 1), (b, 1), (c, 1)] {
        f.heartbeat(&store, u, counter, 1).unwrap();
    }
    let plan = f.plan_checkins(&table, &seats, &store);
    assert_eq!(plan.seats.iter().map(|(_, _, p)| p.label()).collect::<Vec<_>>(), vec!["verify"; 3]);
    assert!(stack::should_send(&plan, Duration::ZERO, None, Some(200), &SendPolicy::default()), "every seat is ready: no reason to wait");
    let owned: HashSet<Address> = plan.owned_rigs().collect();
    assert_eq!(owned, [rig_a, rig_b, rig_c].into(), "the dig and record passes leave these heartbeats to the check-in");
    let (results, fee_r0) = f.land_checkins(&table, &plan.entries(true), TxFormat::Legacy, &store, "round 1, verify");
    for rg in [rig_a, rig_b, rig_c] {
        assert_eq!(result_of(&results, &rg), (0, 1), "counted, 1 round");
    }
    assert!(store.is_empty(), "the check-in consumed the heartbeats");
    for (u, s) in [(a, seats[0]), (b, seats[1]), (c, seats[2])] {
        let seat = f.stack_seat(&s);
        assert_eq!((seat.shift_id, seat.checked_rounds, seat.last_round, seat.broken), (1, 1, r, false), "bound to shift 1");
        let rg = f.rig(u);
        assert_eq!((rg.state, rg.hb_counter, rg.lease_from_round, rg.lease_to_round), (RigState::Down, 1, r, r), "applied exactly as record_heartbeats does");
        assert_eq!(rg.shift_dark_rounds, 1);
    }
    // A second pass has nothing to do.
    let again = f.plan_checkins(&table, &seats, &store);
    assert!(again.seats.iter().all(|(_, _, p)| *p == SeatPlan::Done));
    assert!(!again.has_work());
    // The digging seat's dig reuses the lease the check-in left: no second signature.
    let plan_dig = f.plan(&store);
    assert_eq!(plan_dig.digs.len(), 1, "skips: {:?}", plan_dig.skips);
    assert_eq!(plan_dig.digs[0].dig.accounts.rig, rig_b);
    assert!(plan_dig.digs[0].dig.heartbeat.is_none(), "lease reuse (hb_ix = 0xFF)");
    let p = f.params(TxFormat::Legacy);
    let cranker_before = f.balance(&f.cranker.pubkey());
    let dig = tx::sign_batch(&p, &[plan_dig.digs[0].dig], &[], f.svm.latest_blockhash(), &f.cranker).unwrap();
    let meta = f.send(dig).expect("the seat's dig lands");
    assert!(matches!(hd::events_from_logs(&hd::PROGRAM_ID, &meta.logs)[..], [HdEvent::RigDug { rig, .. }] if rig == rig_b));
    let dig_net = i128::from(f.balance(&f.cranker.pubkey())) - i128::from(cranker_before);
    println!("the digging seat's lease-reuse dig: net {dig_net:+} lamports for the crank (crank_fee {CRANK_FEE} reimbursed)");
    assert_eq!(f.stack_seat(&seats[1]).last_round, r, "a dig does not disturb the seat");

    // ---- round r+1: verify, observe, and a seat that breaks --------------------------------
    f.set_board_round(r + 1);
    // A: a fresh heartbeat for this round.
    f.heartbeat(&store, a, 2, 1).unwrap();
    // B: its heartbeat was already put on-chain this round by record_heartbeats (any crank
    // may do that): the check-in only has to observe the lease.
    f.heartbeat(&store, b, 2, 1).unwrap();
    let hb_b = store.get(&rig_b).unwrap();
    let mut rp = f.params(TxFormat::Legacy);
    rp.max_rigs_per_tx = 8;
    let est = hd_crank::config::RecordConfig::default().cu_estimate();
    let rec = tx::sign_record_batch(&rp, &est, &[tx::RecordRig { rig: rig_b, heartbeat: hb_b }], &[], f.svm.latest_blockhash(), &f.cranker).unwrap();
    f.send(rec).expect("record_heartbeats lands");
    store.remove_if_counter_at_most(&rig_b, 2);
    // C: the phone is picked up. The crank lands its BREAK (pickup): Cooling, break_reason 1.
    let brk = f.signal(c, hd::SignalKind::Break, 2, hd::reason::PICKUP);
    f.land_signal(&brk).expect("BREAK lands");
    assert_eq!((f.rig(c).state, f.rig(c).break_reason), (RigState::Cooling, 1));

    let plan = f.plan_checkins(&table, &seats, &store);
    assert_eq!(plan.seats.iter().map(|(_, _, p)| p.label()).collect::<Vec<_>>(), vec!["verify", "observe", "mark_broken"]);
    assert_eq!(plan.seats[2].2, SeatPlan::MarkBroken { decisive: false }, "C cannot reach end_round any more");
    let owned: HashSet<Address> = plan.owned_rigs().collect();
    assert_eq!(owned, [rig_a, rig_b].into());
    // The planner predicts every result the program reports.
    let predicted: Vec<(Address, Option<u32>)> = plan.seats.iter().map(|(_, rg, p)| (*rg, p.expected_result())).collect();
    let (results, fee_r1) = f.land_checkins(&table, &plan.entries(true), TxFormat::Legacy, &store, "round 2, verify + observe + mark");
    for (rg, want) in predicted {
        assert_eq!(Some(result_of(&results, &rg).0), want, "prediction for {rg}");
    }
    assert_eq!(result_of(&results, &rig_a), (0, 2));
    assert_eq!(result_of(&results, &rig_b), (0, 2));
    assert_eq!(result_of(&results, &rig_c), (hd::code::STACK_SEAT_BROKEN, 1), "StackSeatBroken, for good");
    assert!(f.stack_seat(&seats[2]).broken);
    assert_eq!(f.rig(b).hb_counter, 2, "observe mode consumed no heartbeat");
    // From now on C is skipped without a transaction.
    let plan = f.plan_checkins(&table, &seats, &store);
    assert_eq!(plan.seats[2].2, SeatPlan::Skip(SeatSkip::SeatBroken));
    assert!(!plan.has_work());

    // ---- round r+2 (end_round) -----------------------------------------------------------
    f.set_board_round(r + 2);
    // Settle is refused while the window is live, and the crank does not try.
    assert!(!f.stack_table(&table).settleable(f.board.round_id));
    let early = tx::settle_stack_instructions(&hd::PROGRAM_ID, &table, &t.vault, &seats, 30_000, 1_000);
    let err = f.send_crank(&early).expect_err("StackNotEnded");
    assert!(err.contains(&format!("Custom({})", hd::code::STACK_NOT_ENDED)), "{err}");
    f.heartbeat(&store, a, 3, 1).unwrap();
    // B's phone is slow: only A is ready. Early in the round the crank holds the batch ...
    let partial = f.plan_checkins(&table, &seats, &store);
    assert_eq!(partial.seats[1].2, SeatPlan::Wait(Wait::NoHeartbeat));
    assert!(!stack::should_send(&partial, Duration::from_secs(1), None, Some(200), &SendPolicy::default()));
    // ... and once B's heartbeat is in, both go out in one transaction.
    f.heartbeat(&store, b, 3, 1).unwrap();
    let plan = f.plan_checkins(&table, &seats, &store);
    assert_eq!(plan.seats.iter().map(|(_, _, p)| p.label()).collect::<Vec<_>>(), vec!["verify", "verify", "seat_broken"]);
    let (results, fee_r2) = f.land_checkins(&table, &plan.entries(true), TxFormat::Legacy, &store, "round 3, verify");
    assert_eq!(result_of(&results, &rig_a), (0, 3));
    assert_eq!(result_of(&results, &rig_b), (0, 3));
    assert_eq!(results.len(), 2, "the broken seat is not paid for");

    // ---- settle: permissionless once Board.round_id > end_round ---------------------------
    f.set_board_round(r + 3);
    let t = f.stack_table(&table);
    assert!(t.settleable(f.board.round_id));
    let seat_accounts: Vec<StackSeat> = seats.iter().map(|s| f.stack_seat(s)).collect();
    assert_eq!(seat_accounts.iter().map(|s| s.finishes(&t)).collect::<Vec<_>>(), vec![true, true, false]);
    let preview = skr::preview_settle(&table, &t, &seat_accounts).expect("every seat is known");
    // B = 600 SKR, W = 400, F = 200: each finisher gets 200 + 80; 40 SKR (20%) goes to the Bury lot.
    assert_eq!(preview.payouts, vec![280 * ONE_SKR, 280 * ONE_SKR, 0]);
    assert_eq!((preview.bury, preview.finishers, preview.total_bonds), (40 * ONE_SKR, 2, 600 * ONE_SKR));
    let cu = hd_crank::config::StackConfig::default().settle_cu_limit;
    let before = f.balance(&f.cranker.pubkey());
    let ixs = tx::settle_stack_instructions(&hd::PROGRAM_ID, &table, &t.vault, &seats, cu, 1_000);
    let meta = f.send_crank(&ixs).expect("settle_stack lands");
    let settle_fee = before - f.balance(&f.cranker.pubkey());
    println!("settle_stack: 3 seats, {} CU, fee {settle_fee} lamports", meta.compute_units_consumed);
    assert!(meta.compute_units_consumed < u64::from(cu));
    let evs = hd::events_from_logs(&hd::PROGRAM_ID, &meta.logs);
    assert_eq!(evs.len(), 2, "{evs:?}");
    assert!(matches!(evs[0], HdEvent::BuryLotAdded { source, amount, lot_skr, source_kind: hd::LOT_FROM_STACK, .. } if source == table && amount == preview.bury && lot_skr == preview.bury));
    assert_eq!(
        evs[1],
        HdEvent::StackSettled {
            table,
            total_bonds: preview.total_bonds,
            finisher_bonds: preview.finisher_bonds,
            payouts_total: preview.payouts_total,
            bury_amount: preview.bury,
            seats: 3,
            finishers: 2,
        },
        "the program settled exactly what the crank predicted"
    );
    let t = f.stack_table(&table);
    assert_eq!((t.status, t.finishers, t.bury_amount, t.payouts_total), (skr::status::SETTLED, 2, 40 * ONE_SKR, 560 * ONE_SKR));
    for (s, want) in seats.iter().zip(&preview.payouts) {
        assert_eq!(f.stack_seat(s).payout, *want);
    }
    let bury = skr::bury_vault_pda(&hd::PROGRAM_ID).0;
    assert_eq!(f.skr_balance(&skr::ata(&bury, &SKR_MINT)), 40 * ONE_SKR, "the forfeit's 20% is in the Bury lot");
    assert_eq!(f.skr_balance(&t.vault), 560 * ONE_SKR, "the payouts wait in the table vault");
    // A second settle is refused.
    let err = f.send_crank(&ixs).expect_err("already settled");
    assert!(err.contains(&format!("Custom({})", hd::code::INVALID_STACK_STATE)), "{err}");

    // ---- claims (permissionless; they pay only the stored seat authority) -----------------
    for (u, s, want) in [(a, seats[0], 280 * ONE_SKR), (b, seats[1], 280 * ONE_SKR), (c, seats[2], 0)] {
        let wallet = f.users[u].wallet.pubkey();
        let meta = f.send_crank(&[wallet_ix::claim_stack_ix(&hd::PROGRAM_ID, &table, &s, &wallet)]).expect("claim_stack");
        let evs = hd::events_from_logs(&hd::PROGRAM_ID, &meta.logs);
        assert!(matches!(evs[..], [HdEvent::StackClaimed { amount, kind: hd::CLAIM_PAYOUT, authority, .. }] if amount == want && authority == wallet), "{evs:?}");
        assert_eq!(f.skr_balance(&skr::ata(&wallet, &SKR_MINT)), FUNDED - BOND + want);
        assert!(f.account(&s).is_none(), "the seat is closed");
    }
    assert_eq!(f.skr_balance(&t.vault), 0, "conservation: 280 + 280 + 0 + 40 = 600");

    // ---- what the table cost the crank ---------------------------------------------------
    let checkins = fee_r0 + fee_r1 + fee_r2;
    println!(
        "table total: check-ins {checkins} lamports over 3 rounds for 3 seats ({} per counted seat-round), settle {settle_fee}, one-time Bury setup {bury_cost}",
        checkins / 7
    );
}

#[test]
fn a_missed_round_is_a_gap_and_grace_absorbs_only_so_many() {
    let mut f = Fork::new();
    f.load_skr();
    let store = Arc::new(HeartbeatStore::new(100));
    let r = f.board.round_id;
    let a = f.add_user(210); // misses one round: inside grace, finishes
    let b = f.add_user(211); // misses two rounds: forfeits
    let c = f.add_user(212); // arms a three-round lease: never counts (StackLeaseTooLong)
    f.set_board_round(r - 1);
    f.arm_for_stack(a, true);
    f.arm_for_stack(b, true);
    f.arm_for_stack(c, true);
    f.set_rig(c, |rig| rig.plan_lease_rounds = 3);
    // Window [r, r+3], one gap allowed, every forfeit to the Bury lot (a bury-only table).
    let params = wallet_ix::StackParams { table_id: 7, bond: BOND, start_round: r, end_round: r + 3, grace_gaps: 1, flags: skr::FLAG_BURY_ONLY, max_seats: 3 };
    let (table, seats) = f.open_table(b, &params, &[a, b, c]);
    f.init_bury();
    let (rig_a, rig_b, rig_c) = (f.users[a].rig, f.users[b].rig, f.users[c].rig);
    let retry = CheckinRetry::default();
    let mut ledger = CheckinLedger::new();
    let mut counters = [0u64; 3];
    let mut missed: Vec<(u64, Address, Missed)> = Vec::new();
    // Which phones heartbeat in which round of the window.
    let schedule: [[bool; 3]; 4] = [[true, true, true], [false, true, true], [true, false, true], [true, true, true]];
    for (i, alive) in schedule.iter().enumerate() {
        let round = r + i as u64;
        f.set_board_round(round);
        for (k, u) in [a, b, c].into_iter().enumerate() {
            if alive[k] {
                counters[k] += 1;
                f.heartbeat(&store, u, counters[k], 1).unwrap();
            }
        }
        let plan = f.plan_checkins(&table, &seats, &store);
        let notes: Vec<RoundNote> = plan
            .seats
            .iter()
            .map(|(_, _, p)| RoundNote {
                last_plan: p.label(),
                eligible: !matches!(p, SeatPlan::Skip(_) | SeatPlan::MarkBroken { .. }),
                was_ready: matches!(p, SeatPlan::Verify(_) | SeatPlan::Observe),
                budget_blocked: false,
            })
            .collect();
        // C's long lease never counts, and the crank does not pay to find that out.
        assert_eq!(plan.seats[2].2, SeatPlan::Skip(SeatSkip::LeaseTooLong));
        let entries = plan.entries(true);
        assert!(entries.iter().all(|e| e.rig != rig_c));
        if !entries.is_empty() {
            let sent: Vec<Address> = entries.iter().map(|e| e.seat).collect();
            ledger.mark_pending(&sent, round, u64::MAX, 0);
            let (results, _) = f.land_checkins(&table, &entries, TxFormat::Legacy, &store, &format!("round {}", i + 1));
            for (rg, result, _) in results {
                let seat = seats[[rig_a, rig_b, rig_c].iter().position(|x| *x == rg).unwrap()];
                ledger.mark(&seat, round, if result == 0 { CheckinStatus::Counted } else { CheckinStatus::NotCounted(result) });
            }
        }
        // End of the round: account for every seat that did not count it.
        for (k, s) in seats.iter().enumerate() {
            if f.stack_seat(s).last_round != round {
                if let Some(m) = stack::missed_reason(Some(&notes[k]), ledger.status(s, round), ledger.attempts(s, round), retry) {
                    missed.push((round, *s, m));
                }
            }
        }
        // C's heartbeat stays with the record path (three-round leases are its business).
        store.remove_if_counter_at_most(&rig_c, u64::MAX);
    }
    assert_eq!(
        missed,
        vec![(r + 1, seats[0], Missed::NoHeartbeat), (r + 2, seats[1], Missed::NoHeartbeat)],
        "the phones' silent rounds are the only misses, and they are named"
    );
    let t = f.stack_table(&table);
    let (sa, sb, sc) = (f.stack_seat(&seats[0]), f.stack_seat(&seats[1]), f.stack_seat(&seats[2]));
    assert_eq!((sa.checked_rounds, sa.last_round, sa.gaps(&t)), (3, r + 3, 1));
    assert_eq!((sb.checked_rounds, sb.last_round, sb.gaps(&t)), (3, r + 3, 1));
    assert_eq!((sc.checked_rounds, sc.shift_id), (0, 0), "never bound");
    assert!(stack::can_still_finish(&t, &sa, r + 3) && stack::can_still_finish(&t, &sb, r + 3) && !stack::can_still_finish(&t, &sc, r + 3));
    // Both A and B finish with one gap each (grace 1); C forfeits. Bury-only: finishers get
    // their own bond back and the whole forfeit goes to the Bury lot.
    f.set_board_round(r + 4);
    let seat_accounts = [sa, sb, sc];
    let preview = skr::preview_settle(&table, &t, &seat_accounts).unwrap();
    assert_eq!(preview.payouts, vec![BOND, BOND, 0]);
    assert_eq!(preview.bury, BOND);
    let ixs = tx::settle_stack_instructions(&hd::PROGRAM_ID, &table, &t.vault, &seats, 30_000, 1_000);
    let meta = f.send_crank(&ixs).expect("settle_stack");
    let evs = hd::events_from_logs(&hd::PROGRAM_ID, &meta.logs);
    assert!(matches!(evs[1], HdEvent::StackSettled { finishers: 2, bury_amount, payouts_total, .. } if bury_amount == BOND && payouts_total == 2 * BOND), "{evs:?}");

    // One gap more than grace loses the seat: the same table shape with grace 0.
    let mut g = Fork::new();
    g.load_skr();
    let store = Arc::new(HeartbeatStore::new(100));
    let r = g.board.round_id;
    let (x, y) = (g.add_user(220), g.add_user(221));
    g.set_board_round(r - 1);
    g.arm_for_stack(x, true);
    g.arm_for_stack(y, true);
    let params = wallet_ix::StackParams { table_id: 8, bond: BOND, start_round: r, end_round: r + 1, grace_gaps: 0, flags: 0, max_seats: 2 };
    let (table, seats) = g.open_table(x, &params, &[x, y]);
    g.set_board_round(r);
    g.heartbeat(&store, x, 1, 1).unwrap(); // y's phone is silent in the first round
    let plan = g.plan_checkins(&table, &seats, &store);
    g.land_checkins(&table, &plan.entries(true), TxFormat::Legacy, &store, "grace 0, round 1");
    g.set_board_round(r + 1);
    let t = g.stack_table(&table);
    assert!(!stack::can_still_finish(&t, &g.stack_seat(&seats[1]), r), "y is already lost: 1 gap > grace 0");
    assert!(stack::can_still_finish(&t, &g.stack_seat(&seats[0]), r));
    for (u, counter) in [(x, 2), (y, 1)] {
        g.heartbeat(&store, u, counter, 1).unwrap();
    }
    let plan = g.plan_checkins(&table, &seats, &store);
    g.land_checkins(&table, &plan.entries(true), TxFormat::Legacy, &store, "grace 0, round 2");
    g.set_board_round(r + 2);
    let t = g.stack_table(&table);
    let seat_accounts: Vec<StackSeat> = seats.iter().map(|s| g.stack_seat(s)).collect();
    assert_eq!(seat_accounts.iter().map(|s| s.finishes(&t)).collect::<Vec<_>>(), vec![true, false]);
    // No Bury vault yet and something to bury: the settle cannot land until it exists.
    let ixs = tx::settle_stack_instructions(&hd::PROGRAM_ID, &table, &t.vault, &seats, 30_000, 1_000);
    let err = g.send_crank(&ixs).expect_err("the BuryVault does not exist yet");
    assert!(err.contains("Custom(4)"), "InvalidAccountTag: {err}");
    g.init_bury();
    let preview = skr::preview_settle(&table, &t, &seat_accounts).unwrap();
    // F = 200: the finisher gets 200 + 160, the Bury lot 40.
    assert_eq!((preview.payouts.clone(), preview.bury), (vec![360 * ONE_SKR, 0], 40 * ONE_SKR));
    let meta = g.send_crank(&ixs).expect("settle_stack");
    let evs = hd::events_from_logs(&hd::PROGRAM_ID, &meta.logs);
    assert!(matches!(evs[1], HdEvent::StackSettled { finishers: 1, bury_amount, payouts_total, .. } if bury_amount == preview.bury && payouts_total == preview.payouts_total));
}

#[test]
fn a_break_after_the_last_checkin_is_recorded_before_the_settle() {
    // Window of one round, grace 0. The seat counts end_round, then its phone is picked up in
    // the same round. Unless another check-in lands in that round, the seat finishes
    // (INTERFACE §11.5, "Limits"): the crank sends that check-in at once.
    let mut f = Fork::new();
    f.load_skr();
    let store = Arc::new(HeartbeatStore::new(100));
    let r = f.board.round_id;
    let (a, b) = (f.add_user(230), f.add_user(231));
    f.set_board_round(r - 1);
    f.arm_for_stack(a, true);
    f.arm_for_stack(b, true);
    let params = wallet_ix::StackParams { table_id: 3, bond: BOND, start_round: r, end_round: r, grace_gaps: 0, flags: 0, max_seats: 2 };
    let (table, seats) = f.open_table(a, &params, &[a, b]);
    f.init_bury();
    f.set_board_round(r);
    f.heartbeat(&store, a, 1, 1).unwrap();
    f.heartbeat(&store, b, 1, 1).unwrap();
    let plan = f.plan_checkins(&table, &seats, &store);
    f.land_checkins(&table, &plan.entries(true), TxFormat::Legacy, &store, "the only round");
    let t = f.stack_table(&table);
    assert!(f.stack_seat(&seats[1]).finishes(&t), "B counted the end round");
    // B's phone is picked up; the crank lands the BREAK.
    let brk = f.signal(b, hd::SignalKind::Break, 2, hd::reason::PICKUP);
    f.land_signal(&brk).expect("BREAK lands");
    // The next pass sees a counted seat whose bound shift broke: decisive, sent on its own.
    let plan = f.plan_checkins(&table, &seats, &store);
    assert_eq!(plan.seats.iter().map(|(_, _, p)| *p).collect::<Vec<_>>(), vec![SeatPlan::Done, SeatPlan::MarkBroken { decisive: true }]);
    assert!(plan.has_decisive_mark());
    assert!(stack::should_send(&plan, Duration::ZERO, None, Some(200), &SendPolicy::default()));
    let entries = plan.entries(false);
    assert_eq!(entries.len(), 1);
    let (results, fee) = f.land_checkins(&table, &entries, TxFormat::Legacy, &store, "mark broken");
    assert_eq!(results, vec![(f.users[b].rig, hd::code::STACK_SEAT_BROKEN, 1)]);
    println!("recording a break at the table: {fee} lamports (observe mode, no signature)");
    let seat_b = f.stack_seat(&seats[1]);
    assert!(seat_b.broken && !seat_b.finishes(&t));
    f.set_board_round(r + 1);
    let seat_accounts: Vec<StackSeat> = seats.iter().map(|s| f.stack_seat(s)).collect();
    let preview = skr::preview_settle(&table, &t, &seat_accounts).unwrap();
    assert_eq!(preview.payouts, vec![BOND + 160 * ONE_SKR, 0], "the seat that held out takes 80% of the forfeit");
    let ixs = tx::settle_stack_instructions(&hd::PROGRAM_ID, &table, &t.vault, &seats, 30_000, 1_000);
    f.send_crank(&ixs).expect("settle_stack");
    assert_eq!(f.stack_seat(&seats[0]).payout, BOND + 160 * ONE_SKR);
    assert_eq!(f.stack_seat(&seats[1]).outcome, skr::outcome::FORFEITED);
}

/// The check-in capacity and what a seat costs the crank per round, with the real program.
#[test]
fn stack_checkin_capacity_and_cost_per_seat() {
    let mut f = Fork::new();
    f.load_skr();
    let store = Arc::new(HeartbeatStore::new(100));
    let r = f.board.round_id;
    let users: Vec<usize> = (0..8).map(|i| f.add_user(300 + i)).collect();
    f.set_board_round(r - 1);
    for &u in &users {
        f.arm_for_stack(u, true);
    }
    let params = wallet_ix::StackParams { table_id: 9, bond: BOND, start_round: r, end_round: r + 3, grace_gaps: 0, flags: 0, max_seats: 8 };
    let (table, seats) = f.open_table(users[0], &params, &users);
    f.make_alt(); // the crank's lookup table already holds the Board, the sysvar and every rig
    let price = hd_crank::config::StackConfig::default().cu_price_micro_lamports;
    let mut p = f.params(TxFormat::Legacy);
    p.cu_price_micro_lamports = price;
    p.max_rigs_per_tx = skr::MAX_SEATS;
    let cu = CheckinCu::default();

    // ---- round r: verify mode, legacy ---------------------------------------------------
    f.set_board_round(r);
    for &u in &users {
        f.heartbeat(&store, u, 1, 1).unwrap();
    }
    let plan = f.plan_checkins(&table, &seats, &store);
    let entries = plan.entries(true);
    assert_eq!(entries.len(), 8);
    let (legacy, _) = tx::pack_checkins(&p, &cu, &table, &entries, &[]);
    let legacy_sizes: Vec<(usize, usize)> = legacy.iter().map(|b| (b.seats.len(), b.wire_size)).collect();
    let mut v0 = p.clone();
    v0.format = TxFormat::V0;
    let (with_alt, _) = tx::pack_checkins(&v0, &cu, &table, &entries, &f.alts);
    let v0_sizes: Vec<(usize, usize)> = with_alt.iter().map(|b| (b.seats.len(), b.wire_size)).collect();
    // Without the priority-fee instructions four verified seats are 1,192 bytes; with them the
    // packet takes three legacy, and more with the crank's lookup table.
    let mut bare = p.clone();
    bare.format = TxFormat::V1; // only to leave the two ComputeBudget instructions out of the count
    let ixs = tx::build_checkin_instructions(&bare, 20_000, &table, &entries[..4]).unwrap();
    let msg = solana_message::VersionedMessage::Legacy(solana_message::Message::new_with_blockhash(&ixs, Some(&f.cranker.pubkey()), &solana_hash::Hash::default()));
    let bare_size = tx::serialize(&tx::make_transaction(msg, None).unwrap()).unwrap().len();
    println!("verify mode, seats per packet (seats, bytes): legacy {legacy_sizes:?}, v0 + lookup table {v0_sizes:?}; 4 seats without ComputeBudget instructions: {bare_size} bytes");
    assert!(bare_size <= tx::PACKET_DATA_SIZE, "INTERFACE §11.11: 4 verified seats fit one packet");
    assert!(legacy.iter().all(|b| b.wire_size <= tx::PACKET_DATA_SIZE) && with_alt.iter().all(|b| b.wire_size <= tx::PACKET_DATA_SIZE));
    assert_eq!(legacy_sizes.iter().map(|(n, _)| *n).sum::<usize>(), 8);
    assert!(legacy_sizes[0].0 >= 3 && v0_sizes[0].0 >= 4, "legacy {legacy_sizes:?}, v0 {v0_sizes:?}");
    let (results, fee_legacy) = f.land_checkins(&table, &entries, TxFormat::Legacy, &store, "verify");
    assert!(results.iter().all(|(_, res, checked)| (*res, *checked) == (0, 1)), "{results:?}");
    println!("verify mode, legacy: 8 seats cost {fee_legacy} lamports in {} transactions = {} per seat-round", legacy.len(), fee_legacy / 8);

    // ---- round r+1: verify mode, v0 + the crank's lookup table --------------------------
    f.set_board_round(r + 1);
    for &u in &users {
        f.heartbeat(&store, u, 2, 1).unwrap();
    }
    let plan = f.plan_checkins(&table, &seats, &store);
    let (results, fee_v0) = f.land_checkins(&table, &plan.entries(true), TxFormat::V0, &store, "verify");
    assert!(results.iter().all(|(_, res, checked)| (*res, *checked) == (0, 2)), "{results:?}");
    println!("verify mode, v0 + lookup table: 8 seats cost {fee_v0} lamports in {} transactions = {} per seat-round", with_alt.len(), fee_v0 / 8);

    // ---- round r+2: observe mode (the heartbeats were recorded by record_heartbeats) -----
    f.set_board_round(r + 2);
    for &u in &users {
        f.heartbeat(&store, u, 3, 1).unwrap();
    }
    let recs: Vec<tx::RecordRig> = users.iter().map(|&u| tx::RecordRig { rig: f.users[u].rig, heartbeat: store.get(&f.users[u].rig).unwrap() }).collect();
    let est = hd_crank::config::RecordConfig::default().cu_estimate();
    let mut rp = f.params(TxFormat::Legacy);
    rp.max_rigs_per_tx = 8;
    let (rec_batches, _) = tx::pack_records(&rp, &est, &recs, &[]);
    for b in &rec_batches {
        let t = tx::sign_record_batch(&rp, &est, &b.rigs, &[], f.svm.latest_blockhash(), &f.cranker).unwrap();
        f.send(t).expect("record_heartbeats");
        for rr in &b.rigs {
            store.remove_if_counter_at_most(&rr.rig, rr.heartbeat.fields.counter);
        }
    }
    let plan = f.plan_checkins(&table, &seats, &store);
    assert!(plan.seats.iter().all(|(_, _, p)| *p == SeatPlan::Observe));
    let entries = plan.entries(true);
    let (observe, rejected) = tx::pack_checkins(&p, &cu, &table, &entries, &[]);
    assert!(rejected.is_empty());
    assert_eq!(observe.len(), 1, "8 observed seats fit one legacy packet");
    assert_eq!(observe[0].precompile_signatures, 0);
    let (results, fee_observe) = f.land_checkins(&table, &entries, TxFormat::Legacy, &store, "observe");
    assert!(results.iter().all(|(_, res, checked)| (*res, *checked) == (0, 3)), "{results:?}");
    println!("observe mode, legacy: 8 seats cost {fee_observe} lamports in 1 transaction = {} per seat-round", fee_observe / 8);

    // ---- round r+3: a duplicate check-in counts nothing twice, and settle with 8 seats ---
    f.set_board_round(r + 3);
    for &u in &users {
        f.heartbeat(&store, u, 4, 1).unwrap();
    }
    let plan = f.plan_checkins(&table, &seats, &store);
    let entries = plan.entries(true);
    f.land_checkins(&table, &entries, TxFormat::V0, &store, "verify");
    // The same seats again, observed: results 0, no double count (last_round == round).
    let observed: Vec<CheckinSeat> = entries.iter().map(|e| CheckinSeat { heartbeat: None, ..*e }).collect();
    let (results, _) = f.land_checkins(&table, &observed, TxFormat::Legacy, &store, "observe again");
    assert!(results.iter().all(|(_, res, checked)| (*res, *checked) == (0, 4)), "{results:?}");
    f.init_bury();
    f.set_board_round(r + 4);
    let t = f.stack_table(&table);
    let cu_limit = hd_crank::config::StackConfig::default().settle_cu_limit;
    let ixs = tx::settle_stack_instructions(&hd::PROGRAM_ID, &table, &t.vault, &seats, cu_limit, 1_000);
    let wire = tx::serialize(&tx::sign_legacy(&ixs, &f.cranker, f.svm.latest_blockhash()).unwrap()).unwrap().len();
    let before = f.balance(&f.cranker.pubkey());
    let meta = f.send_crank(&ixs).expect("settle_stack with 8 seats");
    println!(
        "settle_stack: 8 seats, {wire} bytes, {} CU, fee {} lamports",
        meta.compute_units_consumed,
        before - f.balance(&f.cranker.pubkey())
    );
    assert!(meta.compute_units_consumed < u64::from(cu_limit));
    let evs = hd::events_from_logs(&hd::PROGRAM_ID, &meta.logs);
    assert!(matches!(evs[..], [HdEvent::StackSettled { finishers: 8, seats: 8, bury_amount: 0, .. }]), "everyone finished: nothing to bury: {evs:?}");
    // Fees per seat-round: verify mode is one signature fee per seat plus a share of the
    // transaction's; observe mode is only the share.
    assert!(fee_observe / 8 < 1_000 && fee_legacy / 8 > 5_000 && fee_v0 <= fee_legacy);
}

#[test]
fn crank_forfeits_broken_focus_bonds_and_refunds_expired_gifts() {
    let mut f = Fork::new();
    f.load_skr();
    let r = f.board.round_id;
    let cranker = f.cranker.pubkey();
    let kai = f.add_user(400); // completes its shift: the bond is the owner's to release
    let lena = f.add_user(401); // ends the shift by hand inside the window: reason 6, forfeit
    let mia = f.add_user(402); // closes the rig while the bonded shift is open: abandoned
    f.init_bury();
    let bury_skr = skr::ata(&skr::bury_vault_pda(&hd::PROGRAM_ID).0, &SKR_MINT);
    let mut bonds = Vec::new();
    for (u, amount) in [(kai, 500 * ONE_SKR), (lena, 300 * ONE_SKR), (mia, 100 * ONE_SKR)] {
        let wallet = f.users[u].wallet.pubkey();
        f.fund_skr(&wallet, FUNDED);
        f.set_rig(u, |rig| {
            rig.state = RigState::Down;
            // The lease lapsed more than the program's 3-round grace ago (INTERFACE §12.13),
            // so that the crank may seal the shift once the window is over.
            rig.shift_start_round = r - 6;
            rig.lease_from_round = r - 5;
            rig.lease_to_round = r - 4;
            rig.shift_dark_rounds = 2;
        });
        let rig = f.users[u].rig;
        let bond = skr::focus_bond_pda(&hd::PROGRAM_ID, &rig, 1).0;
        let meta = f
            .send_wallet(u, &[skr::create_ata_idempotent_ix(&wallet, &bond, &SKR_MINT), wallet_ix::lock_focus_bond_ix(&hd::PROGRAM_ID, &wallet, 1, amount)])
            .expect("lock_focus_bond");
        assert!(matches!(hd::events_from_logs(&hd::PROGRAM_ID, &meta.logs)[..], [HdEvent::FocusBondLocked { bond: b, amount: x, .. }] if b == bond && x == amount));
        bonds.push(bond);
    }
    let read_bond = |f: &Fork, a: &Address| -> Option<FocusBond> { f.account(a).and_then(|acc| FocusBond::decode(&hd::PROGRAM_ID, &acc.owner, &acc.data).ok()) };
    let resolve = |f: &Fork, a: &Address| -> BondResolution {
        let b = read_bond(f, a).unwrap();
        let log = f.account(&hd::shift_log_pda(&hd::PROGRAM_ID, &b.rig, b.shift_id).0).and_then(|acc| hd::ShiftLog::decode(&hd::PROGRAM_ID, &acc.owner, &acc.data).ok());
        let rig = f.account(&b.rig).and_then(|acc| Rig::decode(&hd::PROGRAM_ID, &acc.owner, &acc.data).ok());
        skr::resolve_bond(&b, log.as_ref(), rig.as_ref())
    };
    // While the shifts are open nothing resolves, and the program agrees.
    for b in &bonds {
        assert_eq!(resolve(&f, b), BondResolution::NotYet);
    }
    let early = skr::forfeit_focus_bond_ix(&hd::PROGRAM_ID, &bonds[1], &read_bond(&f, &bonds[1]).unwrap());
    let err = f.send_crank(&[early]).expect_err("the bonded shift is still open");
    assert!(err.contains(&format!("Custom({})", hd::code::BOND_NOT_RESOLVABLE)), "{err}");

    // kai: the window ends (the lease lapsed long ago), the crank seals the shift: completed.
    f.set_rig(kai, |rig| rig.plan_window_end_ts = NOW - 120);
    let ixs = tx::end_shift_instructions(&hd::PROGRAM_ID, &cranker, &f.users[kai].rig, 1, 15_000, 1_000);
    f.send_crank(&ixs).expect("permissionless end_shift");
    assert_eq!(resolve(&f, &bonds[0]), BondResolution::Release, "completed: the owner's to release, never forfeited");
    let wrong = skr::forfeit_focus_bond_ix(&hd::PROGRAM_ID, &bonds[0], &read_bond(&f, &bonds[0]).unwrap());
    let err = f.send_crank(&[wrong]).expect_err("a completed bond cannot be forfeited");
    assert!(err.contains(&format!("Custom({})", hd::code::BOND_NOT_RESOLVABLE)), "{err}");

    // lena: ends her shift by hand inside the window (reason 6 manual).
    let lena_wallet = f.users[lena].wallet.pubkey();
    f.send_wallet(lena, &[hd::end_shift_ix(&hd::PROGRAM_ID, &lena_wallet, &f.users[lena].rig, 1)]).expect("end_shift by the authority");
    assert_eq!(resolve(&f, &bonds[1]), BondResolution::Forfeit(hd::reason::MANUAL));
    // mia: closes her rig while the bonded shift is open (state Frozen allows close_rig).
    f.set_rig(mia, |rig| rig.state = RigState::Frozen);
    let mia_wallet = f.users[mia].wallet.pubkey();
    let close = Instruction {
        program_id: hd::PROGRAM_ID,
        accounts: vec![AccountMeta::new(mia_wallet, true), AccountMeta::new(f.users[mia].rig, false)],
        data: vec![14],
    };
    f.send_wallet(mia, &[close]).expect("close_rig");
    assert_eq!(resolve(&f, &bonds[2]), BondResolution::Forfeit(hd::BOND_ABANDONED));

    // The crank forfeits both: the SKR goes to the Bury lot, both rents to the owner.
    let cfg = hd_crank::config::CleanupConfig::default();
    for (bond_addr, u, amount, reason) in [(bonds[1], lena, 300 * ONE_SKR, hd::reason::MANUAL), (bonds[2], mia, 100 * ONE_SKR, hd::BOND_ABANDONED)] {
        let bond = read_bond(&f, &bond_addr).unwrap();
        let owner = f.users[u].wallet.pubkey();
        let (owner_before, lot_before, crank_before) = (f.balance(&owner), f.skr_balance(&bury_skr), f.balance(&cranker));
        let ixs = tx::with_compute_budget(cfg.forfeit_cu_limit, 1_000, [skr::forfeit_focus_bond_ix(&hd::PROGRAM_ID, &bond_addr, &bond)]);
        let meta = f.send_crank(&ixs).expect("forfeit_focus_bond lands");
        let fee = crank_before - f.balance(&cranker);
        println!("forfeit_focus_bond ({}): {} CU, fee {fee} lamports; rents returned to the owner: {}", hd::bond_reason_name(reason), meta.compute_units_consumed, f.balance(&owner) - owner_before);
        assert!(meta.compute_units_consumed < u64::from(cfg.forfeit_cu_limit));
        assert_eq!(fee, tx::fee_for(0, cfg.forfeit_cu_limit, 1_000, tx::LAMPORTS_PER_SIGNATURE), "the crank pays only the fee");
        let evs = hd::events_from_logs(&hd::PROGRAM_ID, &meta.logs);
        assert!(matches!(evs[0], HdEvent::BuryLotAdded { source, amount: x, source_kind: hd::LOT_FROM_BOND, .. } if source == bond_addr && x == amount), "{evs:?}");
        assert_eq!(evs[1], HdEvent::FocusBondForfeited { bond: bond_addr, rig: bond.rig, shift_id: 1, amount, reason });
        assert_eq!(f.skr_balance(&bury_skr), lot_before + amount);
        assert!(f.account(&bond_addr).is_none() && f.account(&bond.vault).is_none(), "the bond and its vault are closed");
        assert!(f.balance(&owner) > owner_before, "both rents went to the owner, not to the crank");
    }
    assert_eq!(f.skr_balance(&bury_skr), 400 * ONE_SKR);
    // kai's bond is untouched, and anyone may hand it back to him.
    let kai_wallet = f.users[kai].wallet.pubkey();
    f.send_crank(&[wallet_ix::release_focus_bond_ix(&hd::PROGRAM_ID, &kai_wallet, 1)]).expect("release_focus_bond");
    assert_eq!(f.skr_balance(&skr::ata(&kai_wallet, &SKR_MINT)), FUNDED);

    // ---- gifts: refund_gift is permissionless from expiry_ts, and pays only the sender ----
    let olga = f.add_user(410);
    let sender = f.users[olga].wallet.pubkey();
    let recipient = Address::new_from_array([0x77; 32]);
    let lamports = 500_000_000u64;
    f.send_wallet(olga, &[wallet_ix::create_gift_ix(&hd::PROGRAM_ID, &sender, 3, 0, &recipient, lamports)]).expect("create_gift");
    let gift_addr = skr::gift_pda(&hd::PROGRAM_ID, &sender, 3).0;
    let acc = f.account(&gift_addr).unwrap();
    let gift = GiftEscrow::decode(&hd::PROGRAM_ID, &acc.owner, &acc.data).unwrap();
    assert_eq!((gift.sender, gift.recipient, gift.lamports, gift.expiry_ts), (sender, recipient, lamports, NOW + 30 * 86_400));
    assert!(!gift.refundable_at(NOW));
    let refund = tx::with_compute_budget(cfg.refund_cu_limit, 1_000, [skr::refund_gift_ix(&hd::PROGRAM_ID, &gift_addr, &sender)]);
    let err = f.send_crank(&refund).expect_err("not expired yet");
    assert!(err.contains(&format!("Custom({})", hd::code::GIFT_EXPIRY)), "{err}");
    // One second before expiry: still refused. At expiry_ts: accepted.
    f.set_unix_time(gift.expiry_ts - 1);
    assert!(f.send_crank(&refund).is_err());
    f.set_unix_time(gift.expiry_ts);
    assert!(gift.refundable_at(gift.expiry_ts));
    let (sender_before, crank_before, escrow) = (f.balance(&sender), f.balance(&cranker), f.balance(&gift_addr));
    let meta = f.send_crank(&refund).expect("refund_gift lands");
    let fee = crank_before - f.balance(&cranker);
    println!("refund_gift: {} CU, fee {fee} lamports; {} lamports back to the sender (gift + escrow rent)", meta.compute_units_consumed, f.balance(&sender) - sender_before);
    assert!(meta.compute_units_consumed < u64::from(cfg.refund_cu_limit));
    assert_eq!(hd::events_from_logs(&hd::PROGRAM_ID, &meta.logs), vec![HdEvent::GiftRefunded { gift: gift_addr, sender, lamports }]);
    assert_eq!(f.balance(&sender), sender_before + escrow, "every lamport of the escrow went to the stored sender");
    assert!(f.account(&gift_addr).is_none());
    assert_eq!(fee, tx::fee_for(0, cfg.refund_cu_limit, 1_000, tx::LAMPORTS_PER_SIGNATURE), "the crank gains nothing and pays the fee");
    // A refund that names another recipient is refused.
    let olga2 = f.add_user(411);
    let sender2 = f.users[olga2].wallet.pubkey();
    f.set_unix_time(NOW);
    f.send_wallet(olga2, &[wallet_ix::create_gift_ix(&hd::PROGRAM_ID, &sender2, 1, 0, &recipient, lamports)]).expect("create_gift");
    let gift2 = skr::gift_pda(&hd::PROGRAM_ID, &sender2, 1).0;
    f.set_unix_time(NOW + 31 * 86_400);
    let steal = skr::refund_gift_ix(&hd::PROGRAM_ID, &gift2, &cranker);
    let err = f.send_crank(&[steal]).expect_err("the lamports can only go to the stored sender");
    assert!(err.contains("Custom(5)"), "Unauthorized: {err}");
}
