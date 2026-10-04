//! The dig pass while nothing is happening, against a JSON-RPC stub that counts calls
//! (`tests/common/rpc_stub.rs`).
//!
//! A pass that reads the chain costs three `getProgramAccounts` scans, a `getMultipleAccounts`
//! and a `getBlockHeight`, and there are up to three passes per ORE round. These tests count
//! what an idle crank asks, and check that it is wide awake whenever a rig could be dug: a
//! heartbeat is held, a known rig has a lease for the round, or something happened in the
//! last few rounds. They also check what a pass leaves behind for the rest of the crank.
//!
//! No dig lands here (the stub's chain has no ORE program): the planner's skips are enough
//! to see which passes read. The dig itself is the fork suite's subject.

mod common;
#[path = "common/rpc_stub.rs"]
mod rpc_stub;

use common::Phone;
use hd_crank::alt;
use hd_crank::hd::{self, HdConfig, HeartbeatFields, Rig, RigAccounts, RigState};
use hd_crank::heartbeat::{ParsedHeartbeat, Reject, RigCache, Verifier};
use hd_crank::idle::{Pass, AWAKE_ROUNDS};
use hd_crank::ore;
use hd_crank::rpc::{RpcClient, RpcRigSource};
use rpc_stub::{view, Bench, Process};
use solana_address::Address;

/// The passes of one round as the running loop makes them: 20, 14 and 8 slots before the end.
const PASSES: [u64; 3] = [20, 14, 8];
/// What one pass that reads costs at Helius' prices once the program is initialized: three
/// scans at 10 credits, `getMultipleAccounts` and `getBlockHeight` at 1.
const FULL_PASS_CREDITS: u64 = 32;

/// Put the heads_down Config and a funded Executor on chain.
fn initialize(b: &Bench) {
    let (config, bump) = hd::config_pda(&hd::PROGRAM_ID);
    let (executor, executor_bump) = hd::executor_pda(&hd::PROGRAM_ID);
    let cfg = HdConfig {
        bump,
        governance: Address::new_from_array([1; 32]),
        registrar: Address::new_from_array([2; 32]),
        crank_fee: 7_000,
        executor_fee: 10_000,
        bury_bps: 0,
        paused: false,
        executor_bump,
        ore_layout_hash: [0; 32],
    };
    let mut s = b.stub.lock();
    s.set(config, hd::PROGRAM_ID, 1_950_720, cfg.encode());
    s.set(executor, ore::SYSTEM_PROGRAM_ID, 1_450_240, Vec::new());
}

/// An armed rig with no lease yet, and the phone that holds its key.
fn arm_rig(b: &Bench, i: u8) -> (Address, Rig, Phone) {
    let phone = Phone::new(u32::from(i));
    let authority = Address::new_from_array([0xA0 + i; 32]);
    let (address, bump) = hd::rig_pda(&hd::PROGRAM_ID, &authority);
    let rig = Rig {
        bump,
        authority,
        p256_pubkey: phone.pubkey(),
        attestation_level: 1,
        state: RigState::Armed,
        cap_week: 1_000_000_000,
        cap_shift: 100_000_000,
        cap_round: 5_000_000,
        cap_max_cost: u64::MAX,
        caps_expiry_ts: 1_790_086_400,
        plan_max_ev_cost: u64::MAX,
        plan_dig_lamports: 1_000_000,
        plan_split_tiles: 15,
        plan_lease_rounds: 3,
        plan_window_start_ts: 1_789_990_000,
        plan_window_end_ts: 1_790_086_400,
        shift_id: 1,
        shift_open: true,
        ..Rig::default()
    };
    b.stub.lock().set(address, hd::PROGRAM_ID, 2_600_960, rig.encode());
    (address, rig, phone)
}

/// Run the three passes of `round` and say what each did (`Err` when the pass read the chain
/// and then stopped, as it does while the program has no Config).
async fn round_of(p: &Process, round: u64) -> Vec<Result<Pass, String>> {
    let mut out = Vec::new();
    for slots_left in PASSES {
        out.push(p.crank.dig_tick(&view(round, slots_left)).await.map_err(|e| e.to_string()));
    }
    out
}

fn kinds(passes: &[Result<Pass, String>]) -> Vec<Pass> {
    passes.iter().map(|r| *r.as_ref().expect("the pass ran")).collect()
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn before_the_program_exists_a_pass_reads_once_and_then_every_tenth_round() {
    let b = Bench::new().await;
    let p = b.start(b.config());
    let mut read_in = Vec::new();
    for round in 5_000..5_026 {
        for (i, pass) in round_of(&p, round).await.into_iter().enumerate() {
            match pass {
                Ok(Pass::Skip) => {}
                // A pass that reads finds no Config and says so: once per read, not thrice a round.
                Err(e) => {
                    assert!(e.contains("Config unavailable"), "{e}");
                    read_in.push((round, PASSES[i]));
                }
                Ok(other) => panic!("round {round}: {other:?}"),
            }
        }
    }
    assert_eq!(read_in, vec![(5_000, 20), (5_010, 20), (5_020, 20)], "the first pass after start, then one per 10 rounds");
    {
        let s = b.stub.lock();
        assert_eq!((s.count("getProgramAccounts"), s.count("getMultipleAccounts"), s.total()), (9, 3, 12), "{:?}", s.calls);
        assert_eq!(s.credits(), 93);
    }
    let m = &p.metrics.dig_passes;
    assert_eq!((m.get("first"), m.get("periodic"), m.get("skipped")), (1, 2, 75));

    // The same 26 rounds with skipping off (`idle_full_read_rounds = 0`): what every pass cost before.
    let b = Bench::new().await;
    let mut cfg = b.config();
    cfg.dig.idle_full_read_rounds = 0;
    let p = b.start(cfg);
    for round in 5_000..5_026 {
        assert!(round_of(&p, round).await.iter().all(|r| r.is_err()));
    }
    let s = b.stub.lock();
    assert_eq!((s.count("getProgramAccounts"), s.count("getMultipleAccounts"), s.total()), (234, 78, 312));
    assert_eq!(s.credits(), 2_418, "26 times what the idle crank asks now");
    assert_eq!(p.metrics.dig_passes.get("always"), 78);
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn with_the_program_initialized_and_no_rig_an_idle_round_costs_nothing() {
    let b = Bench::new().await;
    initialize(&b);
    let mut cfg = b.config();
    cfg.dig.idle_full_read_rounds = 4;
    let p = b.start(cfg);
    let mut reads = 0;
    for round in 7_000..7_040 {
        let passes = kinds(&round_of(&p, round).await);
        let expect = match round - 7_000 {
            0 => [Pass::First, Pass::Skip, Pass::Skip],
            n if n % 4 == 0 => [Pass::Periodic, Pass::Skip, Pass::Skip],
            _ => [Pass::Skip; 3],
        };
        assert_eq!(passes, expect, "round {round}");
        reads += u64::from(passes[0].reads());
    }
    assert_eq!(reads, 10);
    let s = b.stub.lock();
    assert_eq!((s.count("getProgramAccounts"), s.count("getMultipleAccounts"), s.count("getBlockHeight"), s.total()), (30, 10, 10, 50));
    assert_eq!(s.credits(), reads * FULL_PASS_CREDITS);
    assert_eq!(p.metrics.rigs_seen.get(), 0);
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_held_heartbeat_wakes_every_pass_and_the_lease_it_leaves_is_not_missed() {
    let b = Bench::new().await;
    initialize(&b);
    let (rig_addr, rig, phone) = arm_rig(&b, 1);
    let p = b.start(b.config());
    let r = 6_000;
    // Armed, no heartbeat yet: one read, then idle.
    assert_eq!(kinds(&round_of(&p, r).await), [Pass::First, Pass::Skip, Pass::Skip]);
    assert_eq!(kinds(&round_of(&p, r + 1).await), [Pass::Skip; 3]);
    assert_eq!(p.metrics.rigs_seen.get(), 1);

    // The phone's heartbeat arrives: every pass of the round reads and plans.
    let hb = phone.verified(&hd::PROGRAM_ID, &rig_addr, HeartbeatFields { counter: 1, shift_id: 1, round_id: r + 2, lease_rounds: 3 });
    assert_eq!(p.store.offer(hb), hd_crank::heartbeat::Offer::Stored);
    let scans = b.stub.lock().count("getProgramAccounts");
    assert_eq!(kinds(&round_of(&p, r + 2).await), [Pass::Heartbeat; 3]);
    assert_eq!(b.stub.lock().count("getProgramAccounts"), scans + 9);
    assert_eq!(p.metrics.digs_skipped.get("no_automation"), 3, "the rig reached the planner in each pass");

    // The dig lands after the round's last pass: the heartbeat is consumed, and the rig now
    // holds a lease for rounds r+2..r+4 that the crank's cached rig list does not show.
    p.store.remove_if_counter_at_most(&rig_addr, 1);
    let dug = Rig { state: RigState::Down, hb_counter: 1, lease_from_round: r + 2, lease_to_round: r + 4, last_dug_round: r + 2, ..rig.clone() };
    b.stub.lock().set(rig_addr, hd::PROGRAM_ID, 2_600_960, dug.encode());
    assert!(p.store.is_empty());

    // No heartbeat, no lease it knows of: the crank still reads, because of the round before.
    // That read finds the lease, and the rig is a dig candidate on it for as long as it runs.
    assert_eq!(kinds(&round_of(&p, r + 3).await), [Pass::Recent, Pass::Lease, Pass::Lease]);
    assert_eq!(kinds(&round_of(&p, r + 4).await), [Pass::Lease; 3]);
    assert_eq!(p.metrics.digs_skipped.get("no_automation"), 9, "three more passes in each of the two lease rounds");
    // The lease is over. The crank stays awake for a few rounds after the last candidate.
    for round in r + 5..=r + 4 + AWAKE_ROUNDS {
        assert_eq!(kinds(&round_of(&p, round).await), [Pass::Recent; 3], "round {round}");
    }
    // Then it is idle again, until the periodic read (every 10 rounds from the last read).
    let last_read = r + 4 + AWAKE_ROUNDS;
    for round in last_read + 1..last_read + 10 {
        assert_eq!(kinds(&round_of(&p, round).await), [Pass::Skip; 3], "round {round}");
    }
    assert_eq!(kinds(&round_of(&p, last_read + 10).await), [Pass::Periodic, Pass::Skip, Pass::Skip]);
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_lease_another_crank_created_is_seen_only_by_a_periodic_read() {
    let b = Bench::new().await;
    initialize(&b);
    let (rig_addr, rig, _) = arm_rig(&b, 2);
    let mut cfg = b.config();
    cfg.dig.idle_full_read_rounds = 5;
    let p = b.start(cfg);
    let r = 8_000;
    assert_eq!(kinds(&round_of(&p, r).await), [Pass::First, Pass::Skip, Pass::Skip]);
    let lease = |from: u64, to: u64| Rig { state: RigState::Down, hb_counter: to, lease_from_round: from, lease_to_round: to, ..rig.clone() };

    // Another crank applies a heartbeat this crank never held: a lease for rounds r+1..r+3.
    // It starts and ends between two periodic reads (r and r+5), so it is not seen.
    b.stub.lock().set(rig_addr, hd::PROGRAM_ID, 2_600_960, lease(r + 1, r + 3).encode());
    for round in r + 1..r + 5 {
        assert_eq!(kinds(&round_of(&p, round).await), [Pass::Skip; 3], "round {round}");
    }
    // The periodic read happens while a later lease runs: the rig is a candidate at once, and
    // every pass reads until the lease is over and a few rounds more.
    b.stub.lock().set(rig_addr, hd::PROGRAM_ID, 2_600_960, lease(r + 4, r + 6).encode());
    assert_eq!(kinds(&round_of(&p, r + 5).await), [Pass::Periodic, Pass::Lease, Pass::Lease]);
    assert_eq!(kinds(&round_of(&p, r + 6).await), [Pass::Lease; 3]);
    assert_eq!(kinds(&round_of(&p, r + 7).await), [Pass::Recent; 3]);
    // With a read every round nothing can be missed, and an idle round costs one pass.
    let mut cfg = b.config();
    cfg.dig.idle_full_read_rounds = 1;
    let p = b.start(cfg);
    b.stub.lock().set(rig_addr, hd::PROGRAM_ID, 2_600_960, rig.encode());
    assert_eq!(kinds(&round_of(&p, r + 20).await), [Pass::First, Pass::Skip, Pass::Skip]);
    assert_eq!(kinds(&round_of(&p, r + 21).await), [Pass::Periodic, Pass::Skip, Pass::Skip]);
    b.stub.lock().set(rig_addr, hd::PROGRAM_ID, 2_600_960, lease(r + 22, r + 22).encode());
    assert_eq!(kinds(&round_of(&p, r + 22).await), [Pass::Periodic, Pass::Lease, Pass::Lease]);
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn what_a_pass_leaves_behind_is_kept_whether_or_not_the_program_has_a_config() {
    for initialized in [false, true] {
        let b = Bench::new().await;
        if initialized {
            initialize(&b);
        }
        b.fund(1_000_000_000);
        let (rig_a, rig, phone) = arm_rig(&b, 3);
        let (rig_b, _, _) = arm_rig(&b, 4);
        let p = b.start(b.config());
        p.chain.send_replace(view(9_000, 20));
        let first = p.crank.dig_tick(&view(9_000, 20)).await;
        assert_eq!(first.is_ok(), initialized, "{first:?}");
        // The intake's rig cache is seeded with every rig read, and the gauge says how many.
        let mut seeded = p.seeded.lock().unwrap().clone();
        seeded.sort_by_key(|a| a.to_bytes());
        let mut both = vec![rig_a, rig_b];
        both.sort_by_key(|a| a.to_bytes());
        assert_eq!(seeded, both, "initialized: {initialized}");
        assert_eq!(p.metrics.rigs_seen.get(), 2);
        // The Config the pass read is the one the crank reports reimbursements with.
        assert_eq!(p.crank.hd_config().await.map(|c| c.crank_fee), initialized.then_some(7_000));
        // Passes that read nothing seed nothing and change nothing.
        let calls = b.stub.lock().total();
        assert_eq!(p.crank.dig_tick(&view(9_000, 14)).await.unwrap(), Pass::Skip);
        assert_eq!(p.crank.dig_tick(&view(9_001, 20)).await.unwrap(), Pass::Skip);
        assert_eq!(b.stub.lock().total(), calls);
        assert_eq!(p.seeded.lock().unwrap().len(), 2);
        assert_eq!(p.metrics.rigs_seen.get(), 2);
        // The rig list the pass kept is what the lookup-table sync registers rigs from: a rig
        // whose heartbeat is held gets its four accounts into the table, the other does not.
        let hb = phone.verified(&hd::PROGRAM_ID, &rig_a, HeartbeatFields { counter: 1, shift_id: 1, round_id: 9_001, lease_rounds: 1 });
        p.store.offer(hb);
        p.crank.sync_alts().await.unwrap();
        let tables = p.crank.lookup_tables();
        assert_eq!(tables.len(), 1);
        let mut want = alt::shared_addresses(&hd::PROGRAM_ID);
        want.extend(alt::rig_addresses(&RigAccounts::derive(rig_a, rig.authority)));
        let (mut have, mut expected) = (tables[0].addresses.clone(), want);
        have.sort_by_key(|a| a.to_bytes());
        expected.sort_by_key(|a| a.to_bytes());
        assert_eq!(have, expected, "initialized: {initialized}");
        // The record pass reads the rigs itself, and only when a heartbeat is held.
        p.store.remove_if_counter_at_most(&rig_a, 1);
        let calls = b.stub.lock().total();
        p.crank.record_pass(&view(9_001, 200)).await.unwrap();
        assert_eq!(b.stub.lock().total(), calls, "no heartbeat held: the record pass asks nothing");
    }
}

/// The intake's checks as the binary wires them (a rig cache in front of the RPC), with
/// `fetches_per_second` rig lookups a second at most.
fn intake_verifier(b: &Bench, p: &Process, fetches_per_second: f64) -> Verifier<RpcRigSource> {
    let rpc = RpcClient::new(b.stub.url.clone(), "confirmed", std::time::Duration::from_secs(5)).unwrap();
    Verifier {
        program_id: hd::PROGRAM_ID,
        rigs: RigCache::new(
            RpcRigSource { rpc, program_id: hd::PROGRAM_ID },
            std::time::Duration::from_secs(60),
            std::time::Duration::from_secs(30),
            1_000,
            fetches_per_second,
        ),
        store: p.store.clone(),
    }
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn the_first_heartbeat_after_an_idle_stretch_costs_one_rig_lookup_and_is_planned_in_its_round() {
    let b = Bench::new().await;
    initialize(&b);
    let p = b.start(b.config());
    let r = 6_500;
    // Idle since start: one pass read (there was no rig), and nothing was read after it.
    assert_eq!(kinds(&round_of(&p, r).await), [Pass::First, Pass::Skip, Pass::Skip]);
    assert_eq!(kinds(&round_of(&p, r + 1).await), [Pass::Skip; 3]);
    assert!(p.seeded.lock().unwrap().is_empty(), "no pass has handed the intake a rig");

    // A phone arms its rig and sends its first heartbeat. The idle crank has not read the
    // rig, so the intake looks it up: one getAccountInfo, and the heartbeat is held.
    let (rig_addr, _, phone) = arm_rig(&b, 7);
    let fields = HeartbeatFields { counter: 1, shift_id: 1, round_id: r + 2, lease_rounds: 1 };
    let frame = ParsedHeartbeat { rig: rig_addr, fields, sig: phone.sign_raw(&hd::PROGRAM_ID, &rig_addr, &fields) };
    let intake = intake_verifier(&b, &p, 50.0);
    let lookups = b.stub.lock().count("getAccountInfo");
    intake.process(&frame, Some(r + 2)).await.expect("the heartbeat verifies against the rig just read");
    assert_eq!(b.stub.lock().count("getAccountInfo"), lookups + 1);
    // Every pass of the round it arrived in reads, and the rig reaches the planner: no round
    // is lost to the idle stretch before it.
    assert_eq!(kinds(&round_of(&p, r + 2).await), [Pass::Heartbeat; 3]);
    assert_eq!(p.metrics.digs_skipped.get("no_automation"), 3);
    assert!(p.seeded.lock().unwrap().contains(&rig_addr), "and those passes hand the rig to the intake's cache");

    // With one lookup a second (the one-phone overrides) a frame that names a made-up rig
    // can take the lookup first: the phone's frame is then refused as busy, which it is told
    // as rate_limited, and no call is made for it.
    let (other_addr, other_rig, other_phone) = arm_rig(&b, 8);
    let other_fields = HeartbeatFields { counter: 1, shift_id: 1, round_id: r + 2, lease_rounds: 1 };
    let other = ParsedHeartbeat { rig: other_addr, fields: other_fields, sig: other_phone.sign_raw(&hd::PROGRAM_ID, &other_addr, &other_fields) };
    let slow = intake_verifier(&b, &p, 1.0);
    let made_up = ParsedHeartbeat { rig: Address::new_from_array([0x5A; 32]), ..frame };
    assert_eq!(slow.process(&made_up, Some(r + 2)).await, Err(Reject::UnknownRig));
    let lookups = b.stub.lock().count("getAccountInfo");
    assert_eq!(slow.process(&other, Some(r + 2)).await, Err(Reject::Busy));
    assert_eq!(Reject::Busy.ack_code(), "rate_limited");
    assert_eq!(b.stub.lock().count("getAccountInfo"), lookups);
    // Once a pass that read has put the rig in the cache (what the crank's passes do while a
    // heartbeat is held), the same frame needs no lookup.
    slow.rigs.insert(other_addr, other_rig);
    slow.process(&other, Some(r + 2)).await.expect("accepted from the cache");
    assert_eq!(b.stub.lock().count("getAccountInfo"), lookups);
}

/// Dig passes the running loop has made so far, whatever each of them did.
fn passes_made(p: &Process) -> u64 {
    Pass::ALL.iter().map(|k| p.metrics.dig_passes.get(k.label())).sum()
}

/// Show the running loop the chain at `round`, `slots_left` before its end, and wait until it
/// has made `passes` dig passes and asked the stub `calls` calls in all (both since start).
async fn shown(b: &Bench, p: &Process, round: u64, slots_left: u64, passes: u64, calls: u64) {
    p.chain.send_replace(view(round, slots_left));
    let t0 = std::time::Instant::now();
    loop {
        let (made, asked) = (passes_made(p), b.stub.lock().total());
        if (made, asked) == (passes, calls) {
            break;
        }
        assert!(
            made <= passes && asked <= calls && t0.elapsed() < std::time::Duration::from_secs(10),
            "round {round}, {slots_left} slots left: {made} passes and {asked} calls, expected {passes} and {calls}"
        );
        tokio::time::sleep(std::time::Duration::from_millis(5)).await;
    }
    // Nothing more follows: the loop wakes again within 400 ms and must find nothing to do.
    tokio::time::sleep(std::time::Duration::from_millis(450)).await;
    assert_eq!((passes_made(p), b.stub.lock().total()), (passes, calls), "round {round}, {slots_left} slots left");
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn the_running_loop_reads_at_start_rests_while_idle_and_wakes_for_a_heartbeat() {
    // The loop itself (`Crank::run`), not a pass called by hand. Only the dig loop and the
    // poller's first turn ask the stub anything: every other duty is off.
    let b = Bench::new().await;
    initialize(&b);
    let (rig_addr, rig, phone) = arm_rig(&b, 6);
    let mut cfg = b.config();
    cfg.alt.enabled = false;
    cfg.stack.enabled = false;
    cfg.end_shift.enabled = false;
    cfg.cleanup.enabled = false;
    cfg.record.enabled = false;
    cfg.dig.checkpoint_sweep = false;
    cfg.dig.config_poll_secs = 3_600;
    let p = b.start(cfg);
    let running = tokio::spawn(p.crank.clone().run());
    let r = 7_500;
    // A pass that reads asks five calls: three scans, the ORE accounts, the block height.
    // The poller's first turn asks four (the Config, ORE's upgrade pin, two balances).
    const POLL: u64 = 4;
    const READ: u64 = 5;
    // Outside the dig window nothing is planned.
    shown(&b, &p, r, 100, 0, POLL).await;
    // The first pass after start reads, although nothing is held. The round's other two do not.
    shown(&b, &p, r, 20, 1, POLL + READ).await;
    shown(&b, &p, r, 14, 2, POLL + READ).await;
    shown(&b, &p, r, 8, 3, POLL + READ).await;
    assert_eq!((p.metrics.dig_passes.get("first"), p.metrics.dig_passes.get("skipped")), (1, 2));
    assert_eq!(p.seeded.lock().unwrap().as_slice(), [rig_addr], "the intake's rig cache was seeded by the pass that read");

    // The next round. A heartbeat arrives and is applied before the dig window opens (as a
    // record or a Stack check-in does): the store is empty again, and the rig holds a lease
    // that the crank's rig list, read a round ago, does not show.
    shown(&b, &p, r + 1, 100, 3, POLL + READ).await;
    let hb = phone.verified(&hd::PROGRAM_ID, &rig_addr, HeartbeatFields { counter: 1, shift_id: 1, round_id: r + 1, lease_rounds: 1 });
    assert_eq!(p.store.offer(hb), hd_crank::heartbeat::Offer::Stored);
    p.store.remove_if_counter_at_most(&rig_addr, 1);
    let leased = Rig { state: RigState::Down, hb_counter: 1, lease_from_round: r + 1, lease_to_round: r + 1, ..rig.clone() };
    b.stub.lock().set(rig_addr, hd::PROGRAM_ID, 2_600_960, leased.encode());
    // Every pass of that round reads: the first because a heartbeat was held in the round,
    // the others because the list now shows the lease.
    shown(&b, &p, r + 1, 20, 4, POLL + 2 * READ).await;
    shown(&b, &p, r + 1, 14, 5, POLL + 3 * READ).await;
    shown(&b, &p, r + 1, 8, 6, POLL + 4 * READ).await;
    assert_eq!((p.metrics.dig_passes.get("recent"), p.metrics.dig_passes.get("lease")), (1, 2));
    assert_eq!(p.metrics.digs_skipped.get("no_automation"), 3, "the rig reached the planner in all three");
    // The lease is over, and the crank stays awake for the rounds after it.
    shown(&b, &p, r + 2, 20, 7, POLL + 5 * READ).await;
    shown(&b, &p, r + 2, 14, 8, POLL + 6 * READ).await;
    shown(&b, &p, r + 2, 8, 9, POLL + 7 * READ).await;
    assert_eq!(p.metrics.dig_passes.get("recent"), 4);
    // Past the awake rounds it rests again, until the periodic read (10 rounds after the last).
    shown(&b, &p, r + 1 + AWAKE_ROUNDS + 1, 20, 10, POLL + 7 * READ).await;
    shown(&b, &p, r + 1 + AWAKE_ROUNDS + 1, 14, 11, POLL + 7 * READ).await;
    shown(&b, &p, r + 11, 20, 12, POLL + 7 * READ).await;
    shown(&b, &p, r + 12, 20, 13, POLL + 8 * READ).await;
    shown(&b, &p, r + 12, 14, 14, POLL + 8 * READ).await;
    assert_eq!((p.metrics.dig_passes.get("periodic"), p.metrics.dig_passes.get("skipped")), (1, 6));
    running.abort();
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn the_pollers_rig_scan_stops_while_it_can_change_nothing() {
    let b = Bench::new().await;
    initialize(&b);
    let p = b.start(b.config());
    // Far from the dig window, where the poller may scan.
    p.chain.send_replace(view(9_100, 200));
    let scans = |b: &Bench| b.stub.lock().count("getProgramAccounts");
    // The first turn: the poller's own four calls, the scan (nothing was read yet), and the
    // lookup-table sync, which finds the fee payer empty (two rents and a balance).
    p.crank.poll_once().await;
    assert_eq!((scans(&b), b.stub.lock().total()), (3, 4 + 3 + 3));
    assert_eq!(p.metrics.lookup_tables.get("low_balance"), 1);
    // While the fee payer is short the sync can send nothing: the scan is left out.
    for _ in 0..20 {
        p.crank.poll_once().await;
    }
    assert_eq!((scans(&b), b.stub.lock().total()), (3, 10 + 20 * 4), "four calls a turn, no scan");
    // Funded: this turn reads the balance, scans, and the sync creates and fills the table.
    b.fund(1_000_000_000);
    p.crank.poll_once().await;
    assert_eq!(scans(&b), 6);
    assert_eq!(p.crank.lookup_tables().len(), 1);
    // A dig pass reads and finds no rig: the crank is idle, and the poller's scan stops too.
    assert_eq!(p.crank.dig_tick(&view(9_100, 20)).await.unwrap(), Pass::First);
    p.chain.send_replace(view(9_101, 200));
    let calls = b.stub.lock().total();
    for _ in 0..20 {
        p.crank.poll_once().await;
    }
    assert_eq!((scans(&b), b.stub.lock().total()), (9, calls + 20 * 4));
    // A phone arms a rig and its heartbeat arrives: the next turn scans, and the rig's four
    // accounts go into the table.
    let (rig_addr, rig, phone) = arm_rig(&b, 5);
    p.store.offer(phone.verified(&hd::PROGRAM_ID, &rig_addr, HeartbeatFields { counter: 1, shift_id: 1, round_id: 9_101, lease_rounds: 1 }));
    p.crank.poll_once().await;
    assert_eq!(scans(&b), 12);
    let table = &p.crank.lookup_tables()[0];
    for a in alt::rig_addresses(&RigAccounts::derive(rig_addr, rig.authority)) {
        assert!(table.addresses.contains(&a));
    }
    assert_eq!(table.addresses.len(), 14);
    // Inside the dig window the poller never scans (as before).
    p.chain.send_replace(view(9_101, 25));
    p.crank.poll_once().await;
    assert_eq!(scans(&b), 12);
    // With skipping off the scan runs on every turn outside the window, as it used to.
    let mut cfg = b.config();
    cfg.dig.idle_full_read_rounds = 0;
    let p = b.start(cfg);
    p.chain.send_replace(view(9_102, 200));
    p.store.prune(u64::MAX, std::time::Duration::ZERO);
    for _ in 0..5 {
        p.crank.poll_once().await;
    }
    assert_eq!(scans(&b), 12 + 15);
}
