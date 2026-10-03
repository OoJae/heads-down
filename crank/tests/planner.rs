//! Planner decisions: one happy path per lease mode, every skip reason at its boundary, the
//! v1.1 amount rule (the fee reserved inside every cap, `k = popcount(mask)` excluding the
//! squares the Miner holds, the fee only on the round's first deploy), Cooling, and the
//! `record_heartbeats` planner.

mod common;

use std::collections::HashMap;

use common::Phone;
use hd_crank::account::RawAccount;
use hd_crank::gate;
use hd_crank::hd::{self, HdConfig, HeartbeatFields, Rig, RigAccounts, RigState};
use hd_crank::heartbeat::VerifiedHeartbeat;
use hd_crank::ore::{self, Board, OreKind, Round, Treasury, ONE_ORE};
use hd_crank::planner::{plan, plan_records, Inputs, Plan, Policy, RecordPolicy, RecordSkip, Skip, RENT_EXEMPT_ZERO_BYTES, WEEK_SECS};
use solana_address::Address;

const ROUND: u64 = 422_601;
const NOW: i64 = 1_790_000_000;
const EMA: u64 = 918_782_720;
const POT: u64 = 344 * ONE_ORE;
const FEE: u64 = 10_000;
const TILE_CAP: u64 = 100_000;

fn automation_bytes(authority: &Address, executor: &Address, amount: u64, balance: u64, fee: u64, strategy: u64) -> Vec<u8> {
    let mut d = vec![0u8; OreKind::Automation.size()];
    d[0] = OreKind::Automation.discriminator();
    d[8..16].copy_from_slice(&amount.to_le_bytes());
    d[16..48].copy_from_slice(authority.as_ref());
    d[48..56].copy_from_slice(&balance.to_le_bytes());
    d[56..88].copy_from_slice(executor.as_ref());
    d[88..96].copy_from_slice(&fee.to_le_bytes());
    d[96..104].copy_from_slice(&strategy.to_le_bytes());
    d[136..144].copy_from_slice(&u64::MAX.to_le_bytes());
    d[144..146].copy_from_slice(&0u16.to_le_bytes());
    d[146..148].copy_from_slice(&u16::MAX.to_le_bytes());
    d
}

fn miner_bytes(authority: &Address, round_id: u64, checkpoint_id: u64, deployed: &[(usize, u64)]) -> Vec<u8> {
    let mut d = vec![0u8; OreKind::Miner.size()];
    d[0] = OreKind::Miner.discriminator();
    d[8..40].copy_from_slice(authority.as_ref());
    d[48..56].copy_from_slice(&checkpoint_id.to_le_bytes());
    d[56..64].copy_from_slice(&ore::CHECKPOINT_FEE.to_le_bytes());
    for (i, v) in deployed {
        d[64 + 8 * i..72 + 8 * i].copy_from_slice(&v.to_le_bytes());
    }
    d[664..672].copy_from_slice(&round_id.to_le_bytes());
    d
}

fn ore_acct(data: Vec<u8>) -> Option<RawAccount> {
    Some(RawAccount { owner: ore::ORE_PROGRAM_ID, lamports: 1_000_000, data })
}

struct World {
    board: Board,
    treasury: Treasury,
    config: HdConfig,
    executor_lamports: u64,
    rigs: Vec<(Address, Rig)>,
    automations: HashMap<Address, Option<RawAccount>>,
    miners: HashMap<Address, Option<RawAccount>>,
    heartbeats: HashMap<Address, VerifiedHeartbeat>,
    phones: HashMap<Address, Phone>,
    round: Option<Round>,
    now: i64,
    slot: u64,
}

fn base_rig(authority: Address, pubkey: [u8; 33]) -> Rig {
    Rig {
        bump: 255,
        authority,
        p256_pubkey: pubkey,
        attestation_level: 1,
        state: RigState::Armed,
        cap_week: 50_000_000,
        cap_shift: 20_000_000,
        cap_round: 2_000_000,
        cap_max_cost: 900_000_000,
        caps_expiry_ts: NOW + 86_400,
        plan_max_ev_cost: 700_000_000,
        plan_dig_lamports: 1_000_000,
        plan_split_tiles: 15,
        plan_solo_tiles: 0,
        plan_lease_rounds: 3,
        plan_window_start_ts: NOW - 3_600,
        plan_window_end_ts: NOW + 3_600,
        shift_id: 7,
        hb_counter: 50,
        week_start_ts: NOW - 3_600,
        last_dug_round: ROUND - 1,
        shift_start_round: ROUND - 100,
        freezes_left: 2,
        shift_open: true,
        ..Rig::default()
    }
}

impl World {
    fn new() -> Self {
        let (_, bump) = hd::executor_pda(&hd::PROGRAM_ID);
        World {
            board: Board { round_id: ROUND, start_slot: 1_000, end_slot: 1_240, production_cost_ema: EMA },
            treasury: Treasury { motherlode: POT },
            config: HdConfig {
                bump: 255,
                governance: Address::default(),
                registrar: Address::default(),
                crank_fee: 7_000,
                executor_fee: FEE,
                bury_bps: 0,
                paused: false,
                executor_bump: bump,
                ore_layout_hash: [0; 32],
            },
            executor_lamports: 10_000_000,
            rigs: vec![],
            automations: HashMap::new(),
            miners: HashMap::new(),
            heartbeats: HashMap::new(),
            phones: HashMap::new(),
            round: None,
            now: NOW,
            slot: 1_220,
        }
    }

    /// Add a healthy rig; `fresh` gives it a held heartbeat, otherwise an on-chain lease.
    fn add(&mut self, i: u32, fresh: bool) -> Address {
        let phone = Phone::new(i);
        let authority = common::addr(i, 0xB2);
        let rig_addr = hd::rig_pda(&hd::PROGRAM_ID, &authority).0;
        let mut rig = base_rig(authority, phone.pubkey());
        if fresh {
            let f = HeartbeatFields { counter: 51, shift_id: 7, round_id: ROUND, lease_rounds: 2 };
            self.heartbeats.insert(rig_addr, phone.verified(&hd::PROGRAM_ID, &rig_addr, f));
        } else {
            rig.lease_from_round = ROUND - 1;
            rig.lease_to_round = ROUND + 1;
            rig.state = RigState::Down;
        }
        let acc = RigAccounts::derive(rig_addr, authority);
        let executor = hd::executor_pda(&hd::PROGRAM_ID).0;
        self.automations.insert(
            acc.automation,
            ore_acct(automation_bytes(&authority, &executor, TILE_CAP, 1_000_000_000, FEE, 2)),
        );
        self.miners.insert(acc.miner, ore_acct(miner_bytes(&authority, ROUND - 1, ROUND - 1, &[])));
        self.rigs.push((rig_addr, rig));
        self.phones.insert(rig_addr, phone);
        rig_addr
    }

    fn rig_mut(&mut self, a: &Address) -> &mut Rig {
        &mut self.rigs.iter_mut().find(|(x, _)| x == a).unwrap().1
    }

    fn accounts(&self, a: &Address) -> RigAccounts {
        let rig = &self.rigs.iter().find(|(x, _)| x == a).unwrap().1;
        RigAccounts::derive(*a, rig.authority)
    }

    fn run_with(&self, policy: Policy, submitted: &dyn Fn(&Address, u64) -> bool) -> Plan {
        let inp = Inputs {
            program_id: &hd::PROGRAM_ID,
            now_ts: self.now,
            landing_slot: self.slot,
            board: &self.board,
            treasury: &self.treasury,
            round: self.round.as_ref(),
            config: &self.config,
            executor_lamports: self.executor_lamports,
            rigs: &self.rigs,
            automations: &self.automations,
            miners: &self.miners,
            heartbeats: &self.heartbeats,
        };
        plan(&inp, &policy, &submitted)
    }

    fn run(&self) -> Plan {
        self.run_with(Policy::default(), &|_, _| false)
    }

    fn skip_of(&self, a: &Address) -> Option<Skip> {
        self.run().skips.into_iter().find(|(x, _)| x == a).map(|(_, s)| s)
    }
}

#[test]
fn happy_paths_reuse_lease_first_then_fresh_heartbeat() {
    let mut w = World::new();
    let fresh = w.add(1, true);
    let leased = w.add(2, false);
    let p = w.run();
    assert!(p.skips.is_empty(), "{:?}", p.skips);
    assert_eq!(p.digs.len(), 2);
    assert_eq!(p.digs[0].dig.accounts.rig, leased, "lease reuse ordered first");
    assert!(p.digs[0].dig.heartbeat.is_none());
    assert_eq!(p.digs[1].dig.accounts.rig, fresh);
    let hb = p.digs[1].dig.heartbeat.unwrap();
    assert_eq!(hb.fields.counter, 51);
    // budget = min(plan 1_000_000, min(cap_round 2e6, shift 20e6, week 50e6) - fee 10_000) = 1_000_000;
    // k = 15 (nothing held); per_tile = 66_666 <= 100_000.
    let d = p.digs[1];
    assert_eq!(d.per_tile, 1_000_000 / 15);
    assert_eq!(d.tiles, 15);
    assert_eq!(d.squares_lamports, (1_000_000 / 15) * 15, "RigDug.lamports: squares only");
    assert_eq!(d.fee_due, FEE, "the rig's first deploy this round pays the Automation fee");
    assert_eq!(d.expected_debit, (1_000_000 / 15) * 15 + FEE, "Automation debit = squares + fee");
    assert_eq!(d.dig.checkpoint_round, None);
    assert_eq!(p.ema_ev, gate::ema_ev(EMA, POT));
}

#[test]
fn the_fee_is_reserved_inside_every_cap() {
    // INTERFACE §6.4: cap_round = plan_dig on 10 squares; the whole debit is exactly cap_round.
    let mut w = World::new();
    let a = w.add(1, true);
    {
        let r = w.rig_mut(&a);
        r.cap_round = 1_000_000;
        r.plan_dig_lamports = 1_000_000;
        r.plan_split_tiles = 10;
    }
    let d = w.run().digs[0];
    assert_eq!(d.per_tile, 99_000, "(1_000_000 - 10_000) / 10");
    assert_eq!(d.squares_lamports, 990_000);
    assert_eq!(d.expected_debit, 1_000_000, "debit == cap_round, never above");
    // The shift cap binds the same way.
    w.rig_mut(&a).cap_shift = 510_000;
    let d = w.run().digs[0];
    assert_eq!(d.per_tile, 50_000);
    assert_eq!(d.expected_debit, 510_000);
    // ... and the week cap, counting what this week already spent.
    w.rig_mut(&a).cap_shift = 20_000_000;
    w.rig_mut(&a).cap_week = 1_000_000;
    w.rig_mut(&a).spent_week = 800_000;
    assert_eq!(w.run().digs[0].expected_debit, 200_000 - 10_000 + 10_000);
}

#[test]
fn held_squares_shrink_k_and_waive_the_fee() {
    let mut w = World::new();
    let a = w.add(1, true);
    let auth = w.rigs[0].1.authority;
    let acc = w.accounts(&a);
    let solo = ore::distribution_mask(ROUND);
    let split: Vec<usize> = (0..25).filter(|i| solo & (1 << i) == 0).collect();
    // The Miner already deployed on 3 split squares this round (another executor's deploy).
    let held: Vec<(usize, u64)> = split[..3].iter().map(|&i| (i, 1_000)).collect();
    w.miners.insert(acc.miner, ore_acct(miner_bytes(&auth, ROUND, ROUND - 1, &held)));
    // No fee from ORE means no reimbursement from the program (INTERFACE §12.13): by default
    // the crank leaves such a rig alone, so a wallet deploying a lamport by hand every round
    // cannot make the crank pay for its digs.
    assert_eq!(w.skip_of(&a), Some(Skip::MinerAlreadyDeployed));
    assert!(w.run().digs.is_empty());
    let unpaid = Policy { dig_unpaid: true, ..Policy::default() };
    let d = w.run_with(unpaid, &|_, _| false).digs[0];
    assert_eq!(d.tiles, 12, "k = popcount(mask): 15 split squares minus 3 held");
    assert_eq!(d.per_tile, 1_000_000 / 12);
    assert_eq!(d.fee_due, 0, "not the first deploy of the round: ORE charges no fee");
    assert_eq!(d.expected_debit, (1_000_000 / 12) * 12);
    assert_eq!(d.dig.checkpoint_round, None, "same round: no checkpoint needed");
    // The predicted mask never includes a held square.
    let mut deployed = [1_000u64; 25];
    deployed[split[0]] = 0;
    w.round = Some(Round { id: ROUND, deployed, count: [0; 25], expires_at: 0, total_miners: 0 });
    let m = w.run_with(unpaid, &|_, _| false).digs[0].predicted_mask.unwrap();
    assert_eq!(m.count_ones(), 12);
    for (i, _) in &held {
        assert_eq!(m & (1 << i), 0);
    }
    // Every split square held: nothing to choose.
    let all: Vec<(usize, u64)> = split.iter().map(|&i| (i, 1)).collect();
    w.miners.insert(acc.miner, ore_acct(miner_bytes(&auth, ROUND, ROUND - 1, &all)));
    assert_eq!(w.skip_of(&a), Some(Skip::NoTiles));
}

#[test]
fn batch_wide_blocks() {
    let mut w = World::new();
    let a = w.add(1, true);
    w.config.paused = true;
    assert_eq!(w.skip_of(&a), Some(Skip::Paused));
    w.config.paused = false;
    w.board.end_slot = u64::MAX;
    assert_eq!(w.skip_of(&a), Some(Skip::RoundNotStarted));
    let started = w.run_with(Policy { start_rounds: true, ..Policy::default() }, &|_, _| false);
    assert_eq!(started.digs.len(), 1, "policy may start a round");
    w.board.end_slot = 1_240;
    w.slot = 1_240;
    assert_eq!(w.skip_of(&a), Some(Skip::OutsideRoundWindow), "slot == end_slot is outside");
    w.slot = 999;
    assert_eq!(w.skip_of(&a), Some(Skip::OutsideRoundWindow));
    w.slot = 1_239;
    assert!(w.skip_of(&a).is_none());
    w.executor_lamports = RENT_EXEMPT_ZERO_BYTES + ore::CHECKPOINT_FEE - 1;
    assert_eq!(w.skip_of(&a), Some(Skip::ExecutorFloatLow));
    w.executor_lamports += 1;
    assert!(w.skip_of(&a).is_none());
}

#[test]
fn state_plan_and_idempotency() {
    let mut w = World::new();
    let a = w.add(1, true);
    for s in [RigState::Idle, RigState::Broken, RigState::Frozen, RigState::Unknown(9)] {
        w.rig_mut(&a).state = s;
        assert_eq!(w.skip_of(&a), Some(Skip::NotDiggable(s)));
    }
    w.rig_mut(&a).state = RigState::Armed;
    w.rig_mut(&a).plan_flags = hd::PLAN_FLAG_FOCUS_ONLY;
    assert_eq!(w.skip_of(&a), Some(Skip::FocusOnly));
    w.rig_mut(&a).plan_flags = hd::PLAN_FLAG_DAY;
    assert!(w.skip_of(&a).is_none(), "a day shift digs");
    w.rig_mut(&a).plan_flags = 0;
    w.rig_mut(&a).last_dug_round = ROUND;
    assert_eq!(w.skip_of(&a), Some(Skip::AlreadyDug));
    w.rig_mut(&a).last_dug_round = ROUND - 1;
    let p = w.run_with(Policy::default(), &|r: &Address, round| *r == a && round == ROUND);
    assert_eq!(p.skips, vec![(a, Skip::AlreadySubmitted)]);
}

#[test]
fn cooling_digs_only_with_a_fresh_heartbeat() {
    let mut w = World::new();
    let a = w.add(1, false); // Down with a lease [ROUND-1, ROUND+1]
    w.rig_mut(&a).state = RigState::Cooling;
    assert_eq!(w.skip_of(&a), Some(Skip::CoolingNeedsHeartbeat), "a lease cannot be reused while Cooling");
    // A fresh heartbeat (counter above the BREAK's) resumes it, even inside the old lease.
    let f = HeartbeatFields { counter: 60, shift_id: 7, round_id: ROUND, lease_rounds: 1 };
    let hb = w.phones[&a].verified(&hd::PROGRAM_ID, &a, f);
    w.heartbeats.insert(a, hb);
    let p = w.run();
    assert_eq!(p.digs.len(), 1);
    assert_eq!(p.digs[0].dig.heartbeat.unwrap().fields.counter, 60, "Cooling always carries the heartbeat");
    // A heartbeat at or below the BREAK's counter does not.
    w.rig_mut(&a).hb_counter = 60;
    assert_eq!(w.skip_of(&a), Some(Skip::CoolingNeedsHeartbeat));
}

#[test]
fn heartbeat_must_grant_a_lease_for_this_round() {
    let mut w = World::new();
    let a = w.add(1, true);
    let good = *w.heartbeats.get(&a).unwrap();
    let with = |w: &mut World, f: HeartbeatFields| {
        let hb = w.phones[&a].verified(&hd::PROGRAM_ID, &a, f);
        w.heartbeats.insert(a, hb);
    };
    // No heartbeat and no lease.
    w.heartbeats.clear();
    assert_eq!(w.skip_of(&a), Some(Skip::NoLease));
    // Wrong shift, stale counter, future round, expired lease.
    with(&mut w, HeartbeatFields { shift_id: 6, ..good.fields });
    assert_eq!(w.skip_of(&a), Some(Skip::NoLease));
    with(&mut w, HeartbeatFields { counter: 50, ..good.fields });
    assert_eq!(w.skip_of(&a), Some(Skip::NoLease));
    with(&mut w, HeartbeatFields { round_id: ROUND + 1, ..good.fields });
    assert_eq!(w.skip_of(&a), Some(Skip::NoLease));
    with(&mut w, HeartbeatFields { round_id: ROUND - 2, lease_rounds: 2, ..good.fields });
    assert_eq!(w.skip_of(&a), Some(Skip::NoLease));
    // Lease 3 requested but plan allows 2: [ROUND-2, ROUND-1] does not cover ROUND.
    w.rig_mut(&a).plan_lease_rounds = 2;
    with(&mut w, HeartbeatFields { round_id: ROUND - 2, lease_rounds: 3, ..good.fields });
    assert_eq!(w.skip_of(&a), Some(Skip::NoLease));
    w.rig_mut(&a).plan_lease_rounds = 3;
    assert!(w.skip_of(&a).is_none(), "[ROUND-2, ROUND] covers");
    // Leases only move forward: a heartbeat that does not extend an older lease changes nothing.
    w.rig_mut(&a).lease_from_round = ROUND - 5;
    w.rig_mut(&a).lease_to_round = ROUND + 5;
    w.rig_mut(&a).state = RigState::Cooling;
    assert!(w.skip_of(&a).is_none(), "Cooling: the fresh heartbeat is consumed, the old lease still covers");
    w.rig_mut(&a).state = RigState::Armed;
    w.rig_mut(&a).lease_from_round = 0;
    w.rig_mut(&a).lease_to_round = 0;
    // Key rotated after the heartbeat was verified.
    w.rig_mut(&a).p256_pubkey = Phone::new(999).pubkey();
    assert_eq!(w.skip_of(&a), Some(Skip::NoLease));
    // An on-chain lease that covers the round needs no heartbeat at all.
    w.heartbeats.clear();
    w.rig_mut(&a).lease_from_round = ROUND;
    w.rig_mut(&a).lease_to_round = ROUND;
    let p = w.run();
    assert!(p.digs[0].dig.heartbeat.is_none());
    w.rig_mut(&a).lease_to_round = ROUND - 1;
    assert_eq!(w.skip_of(&a), Some(Skip::NoLease));
}

#[test]
fn caps_window_and_cost_gate_boundaries() {
    let mut w = World::new();
    let a = w.add(1, true);
    let m = Policy::default().clock_margin_secs;
    w.rig_mut(&a).caps_expiry_ts = NOW + m - 1;
    assert_eq!(w.skip_of(&a), Some(Skip::CapsExpired));
    w.rig_mut(&a).caps_expiry_ts = NOW + m;
    assert!(w.skip_of(&a).is_none());
    w.rig_mut(&a).plan_window_start_ts = NOW - m + 1;
    assert_eq!(w.skip_of(&a), Some(Skip::OutsideWindow));
    w.rig_mut(&a).plan_window_start_ts = NOW - 3_600;
    w.rig_mut(&a).plan_window_end_ts = NOW + m - 1;
    assert_eq!(w.skip_of(&a), Some(Skip::OutsideWindow));
    w.rig_mut(&a).plan_window_end_ts = NOW + 3_600;

    // Gate: inclusive at ema_ev, uses the tighter of plan and cap.
    let ev = gate::ema_ev(EMA, POT).unwrap();
    w.rig_mut(&a).plan_max_ev_cost = ev;
    w.rig_mut(&a).cap_max_cost = ev;
    assert!(w.skip_of(&a).is_none());
    w.rig_mut(&a).plan_max_ev_cost = ev - 1;
    assert_eq!(w.skip_of(&a), Some(Skip::CostGate));
    w.rig_mut(&a).plan_max_ev_cost = u64::MAX;
    w.rig_mut(&a).cap_max_cost = ev - 1;
    assert_eq!(w.skip_of(&a), Some(Skip::CostGate));
    // A bigger pot opens it (pot-adjusted cost falls).
    w.treasury.motherlode = 600 * ONE_ORE;
    assert!(w.skip_of(&a).is_none());
    // EMA-only (pot 0) with the same caps: closed.
    w.treasury.motherlode = 0;
    assert_eq!(w.skip_of(&a), Some(Skip::CostGate));
    // Overflowing ema_ev closes the gate even with u64::MAX caps.
    w.rig_mut(&a).cap_max_cost = u64::MAX;
    w.board.production_cost_ema = u64::MAX;
    w.treasury.motherlode = 0;
    assert_eq!(w.skip_of(&a), Some(Skip::CostGate));
}

#[test]
fn amount_budget_and_week_rollover() {
    let mut w = World::new();
    let a = w.add(1, true);
    w.rig_mut(&a).spent_shift = 20_000_000; // cap_shift fully used
    assert_eq!(w.skip_of(&a), Some(Skip::BudgetExhausted));
    w.rig_mut(&a).spent_shift = 20_000_000 - 30; // 30 lamports left: below the fee itself
    assert_eq!(w.skip_of(&a), Some(Skip::BudgetExhausted), "the fee is reserved first");
    w.rig_mut(&a).spent_shift = 20_000_000 - FEE - 30; // 30 after the fee / 15 tiles = 2
    let p = w.run();
    assert_eq!(p.digs[0].per_tile, 2);
    assert_eq!(p.digs[0].expected_debit, 30 + FEE, "the debit fits the shift cap exactly");
    w.rig_mut(&a).spent_shift = 20_000_000 - FEE - 14; // 14 / 15 = 0
    assert_eq!(w.skip_of(&a), Some(Skip::BudgetExhausted));
    w.rig_mut(&a).spent_shift = 0;
    // Week budget used, but the week has rolled over: spent_week counts as 0.
    w.rig_mut(&a).spent_week = 50_000_000;
    assert_eq!(w.skip_of(&a), Some(Skip::BudgetExhausted));
    w.rig_mut(&a).week_start_ts = NOW - WEEK_SECS;
    assert!(w.skip_of(&a).is_none());
    w.rig_mut(&a).week_start_ts = 0;
    assert!(w.skip_of(&a).is_none(), "week_start 0 rolls too");
    // No tiles.
    w.rig_mut(&a).plan_split_tiles = 0;
    assert_eq!(w.skip_of(&a), Some(Skip::NoTiles));
    // ORE's per-tile cap binds: 5 solo tiles * min(1e6/5, 100_000).
    w.rig_mut(&a).plan_solo_tiles = 5;
    let p = w.run();
    assert_eq!(p.digs[0].per_tile, TILE_CAP);
    assert_eq!(p.digs[0].squares_lamports, 5 * TILE_CAP);
    assert_eq!(p.digs[0].expected_debit, 5 * TILE_CAP + FEE);
}

#[test]
fn automation_preflight() {
    let mut w = World::new();
    let a = w.add(1, true);
    let acc = w.accounts(&a);
    let rig = w.rigs[0].1.clone();
    let executor = hd::executor_pda(&hd::PROGRAM_ID).0;
    let set = |w: &mut World, bytes: Vec<u8>| {
        w.automations.insert(acc.automation, ore_acct(bytes));
    };
    let per_tile = 1_000_000 / 15;
    let need = per_tile * 15 + FEE;
    set(&mut w, automation_bytes(&rig.authority, &executor, TILE_CAP, need - 1, FEE, 2));
    assert_eq!(w.skip_of(&a), Some(Skip::InsufficientBalance), "on-chain: InsufficientAutomationBalance (28)");
    set(&mut w, automation_bytes(&rig.authority, &executor, TILE_CAP, need, FEE, 2));
    assert!(w.skip_of(&a).is_none(), "exact balance is enough");
    set(&mut w, automation_bytes(&rig.authority, &common::addr(5, 5), TILE_CAP, need, FEE, 2));
    assert_eq!(w.skip_of(&a), Some(Skip::ExecutorMismatch), "revoked / re-pointed executor");
    set(&mut w, automation_bytes(&common::addr(6, 6), &executor, TILE_CAP, need, FEE, 2));
    assert_eq!(w.skip_of(&a), Some(Skip::AuthorityMismatch), "would fail the whole transaction on-chain");
    set(&mut w, automation_bytes(&rig.authority, &executor, TILE_CAP, need, FEE, 3));
    assert_eq!(w.skip_of(&a), Some(Skip::StrategyMismatch), "DiscretionaryBps");
    set(&mut w, automation_bytes(&rig.authority, &executor, TILE_CAP, need, FEE - 1, 2));
    assert_eq!(w.skip_of(&a), Some(Skip::StrategyMismatch), "fee != executor_fee");
    set(&mut w, automation_bytes(&rig.authority, &executor, 0, need, FEE, 2));
    assert_eq!(w.skip_of(&a), Some(Skip::BudgetExhausted), "automation.amount = 0");
    // Motherlode conditions: pot 344 ORE, require at least 400.
    let mut bytes = automation_bytes(&rig.authority, &executor, TILE_CAP, need, FEE, 2);
    bytes[144..146].copy_from_slice(&400u16.to_le_bytes());
    set(&mut w, bytes.clone());
    assert_eq!(w.skip_of(&a), Some(Skip::MotherlodeCondition));
    bytes[144..146].copy_from_slice(&0u16.to_le_bytes());
    bytes[146..148].copy_from_slice(&343u16.to_le_bytes());
    set(&mut w, bytes.clone());
    assert_eq!(w.skip_of(&a), Some(Skip::MotherlodeCondition));
    bytes[146..148].copy_from_slice(&344u16.to_le_bytes());
    set(&mut w, bytes.clone());
    assert!(w.skip_of(&a).is_none(), "pot == max is allowed");
    // Layout drift / closed / missing.
    let mut wrong = bytes.clone();
    wrong[0] = 99;
    set(&mut w, wrong);
    assert_eq!(w.skip_of(&a), Some(Skip::AutomationLayout));
    w.automations.insert(acc.automation, Some(RawAccount { owner: ore::SYSTEM_PROGRAM_ID, lamports: 0, data: vec![] }));
    assert_eq!(w.skip_of(&a), Some(Skip::NoAutomation), "closed by ORE");
    w.automations.remove(&acc.automation);
    assert_eq!(w.skip_of(&a), Some(Skip::NoAutomation));
}

#[test]
fn miner_preflight_and_checkpoint() {
    let mut w = World::new();
    let a = w.add(1, true);
    let acc = w.accounts(&a);
    let auth = w.rigs[0].1.authority;
    // Miner last deployed in ROUND-3 and never checkpointed it: prepend a checkpoint.
    w.miners.insert(acc.miner, ore_acct(miner_bytes(&auth, ROUND - 3, ROUND - 4, &[])));
    let p = w.run();
    assert_eq!(p.digs[0].dig.checkpoint_round, Some(ROUND - 3));
    // Already in this round (nothing deployed yet): ORE skips the assert.
    w.miners.insert(acc.miner, ore_acct(miner_bytes(&auth, ROUND, ROUND - 4, &[])));
    let d = w.run().digs[0];
    assert_eq!(d.dig.checkpoint_round, None);
    assert_eq!(d.fee_due, FEE, "same round but no SOL deployed yet: still the first deploy");
    // Someone else's miner, missing miner, wrong layout.
    w.miners.insert(acc.miner, ore_acct(miner_bytes(&common::addr(3, 3), ROUND - 1, ROUND - 1, &[])));
    assert_eq!(w.skip_of(&a), Some(Skip::MinerInvalid));
    w.miners.insert(acc.miner, None);
    assert_eq!(w.skip_of(&a), Some(Skip::NoMiner));
    let mut bad = miner_bytes(&auth, ROUND - 1, ROUND - 1, &[]);
    bad.pop();
    w.miners.insert(acc.miner, ore_acct(bad));
    assert_eq!(w.skip_of(&a), Some(Skip::MinerInvalid));
}

#[test]
fn predicted_tiles_follow_the_round() {
    let mut w = World::new();
    let a = w.add(1, true);
    let mut deployed = [1_000u64; 25];
    deployed[3] = 1;
    w.round = Some(Round { id: ROUND, deployed, count: [0; 25], expires_at: 0, total_miners: 0 });
    w.rig_mut(&a).plan_split_tiles = 1;
    w.rig_mut(&a).plan_solo_tiles = 1;
    let p = w.run();
    let mask = p.digs[0].predicted_mask.unwrap();
    assert_eq!(mask, ore::select_tiles(ROUND, &deployed, 1, 1));
    assert_eq!(mask.count_ones(), 2);
    assert_eq!(u32::from(p.digs[0].tiles), mask.count_ones(), "k = popcount(mask)");
    // A stale Round (different id) predicts nothing.
    w.round.as_mut().unwrap().id = ROUND - 1;
    assert_eq!(w.run().digs[0].predicted_mask, None);
}

// ---- record_heartbeats ------------------------------------------------------------------

fn records(w: &World, policy: RecordPolicy) -> (Vec<hd_crank::planner::RecordDecision>, Vec<(Address, RecordSkip)>) {
    plan_records(&w.board, &w.treasury, w.now, &w.rigs, &w.heartbeats, &policy)
}

/// The record budget cannot always pay for every rig, so the order decides who is served. It
/// used to be address order: a few dozen rigs with low-sorting addresses took the whole budget
/// every round and the rest never had a dark round recorded.
#[test]
fn records_go_to_the_longest_waiting_rig_first_whatever_its_address() {
    let mut w = World::new();
    let rigs: Vec<Address> = (1..=12).map(|i| w.add(i, true)).collect();
    for r in &rigs {
        let rig = w.rig_mut(r);
        rig.plan_flags = hd::PLAN_FLAG_FOCUS_ONLY;
        rig.shift_start_round = ROUND - 9;
        // Recorded three rounds ago: due again.
        rig.lease_from_round = ROUND - 3;
        rig.lease_to_round = ROUND - 2;
    }
    let policy = RecordPolicy { every_rounds: 3, gate_closed_rigs: false, clock_margin_secs: 5 };
    // Two rigs have waited longer than the others (one never had a lease in this shift).
    let mut by_address = rigs.clone();
    by_address.sort_by_key(|a| a.to_bytes());
    let (never, oldest) = (by_address[11], by_address[10]); // the two that address order served last
    w.rig_mut(&never).lease_from_round = 0;
    w.rig_mut(&never).lease_to_round = 0;
    w.rig_mut(&oldest).lease_from_round = ROUND - 8;
    w.rig_mut(&oldest).lease_to_round = ROUND - 7;
    let order: Vec<Address> = records(&w, policy).0.iter().map(|d| d.rig).collect();
    assert_eq!(order.len(), 12);
    assert_eq!(&order[..2], &[never, oldest], "longest-waiting first");
    // Among equals the order is not address order, and it changes from round to round, so no
    // address is always first.
    assert_ne!(&order[2..], &by_address[..10]);
    let mut next = World::new();
    for i in 1..=12 {
        let r = next.add(i, true);
        let rig = next.rig_mut(&r);
        rig.plan_flags = hd::PLAN_FLAG_FOCUS_ONLY;
        rig.shift_start_round = ROUND - 9;
        rig.lease_from_round = ROUND - 3;
        rig.lease_to_round = ROUND - 2;
    }
    let a: Vec<Address> = records(&next, policy).0.iter().map(|d| d.rig).collect();
    next.board.round_id += 1;
    let f = HeartbeatFields { counter: 52, shift_id: 7, round_id: ROUND + 1, lease_rounds: 2 };
    let fresh: Vec<_> = next.phones.iter().map(|(addr, phone)| (*addr, phone.verified(&hd::PROGRAM_ID, addr, f))).collect();
    next.heartbeats.extend(fresh);
    let b: Vec<Address> = records(&next, policy).0.iter().map(|d| d.rig).collect();
    assert_eq!((a.len(), b.len()), (12, 12));
    assert_ne!(a, b, "the tie-break order differs between rounds");
}

#[test]
fn focus_only_rigs_get_their_heartbeats_recorded_every_n_rounds() {
    let mut w = World::new();
    let focus = w.add(1, true);
    let night = w.add(2, true);
    w.rig_mut(&focus).plan_flags = hd::PLAN_FLAG_FOCUS_ONLY;
    w.rig_mut(&focus).shift_start_round = ROUND;
    let policy = RecordPolicy { every_rounds: 3, gate_closed_rigs: false, clock_margin_secs: 5 };
    let (d, skips) = records(&w, policy);
    assert_eq!(d.len(), 1);
    assert_eq!(d[0].rig, focus);
    // Heartbeat for ROUND with lease 2, shift started at ROUND: 2 dark rounds.
    assert_eq!((d[0].grant.from, d[0].grant.to, d[0].grant.dark_added), (ROUND, ROUND + 1, 2));
    assert!(skips.contains(&(night, RecordSkip::NotEligible)), "night rigs dig instead");
    // The lease landed: nothing to do until every_rounds have passed since lease_from.
    w.rig_mut(&focus).lease_from_round = ROUND - 2;
    w.rig_mut(&focus).lease_to_round = ROUND;
    assert_eq!(records(&w, policy).1.iter().find(|(a, _)| *a == focus).unwrap().1, RecordSkip::NotDue);
    w.rig_mut(&focus).lease_from_round = ROUND - 3;
    assert!(records(&w, policy).0.iter().any(|d| d.rig == focus), "due again after 3 rounds");
    // A heartbeat that would not extend the lease changes nothing on-chain: not sent.
    w.rig_mut(&focus).lease_to_round = ROUND + 1;
    assert_eq!(records(&w, policy).1.iter().find(|(a, _)| *a == focus).unwrap().1, RecordSkip::NoExtension);
    w.rig_mut(&focus).lease_to_round = ROUND - 1;
    // Stale heartbeat, outside the window, wrong state.
    w.rig_mut(&focus).hb_counter = 51;
    assert_eq!(records(&w, policy).1.iter().find(|(a, _)| *a == focus).unwrap().1, RecordSkip::NoHeartbeat);
    w.rig_mut(&focus).hb_counter = 50;
    w.rig_mut(&focus).plan_window_end_ts = NOW;
    assert_eq!(records(&w, policy).1.iter().find(|(a, _)| *a == focus).unwrap().1, RecordSkip::OutsideWindow);
    w.rig_mut(&focus).plan_window_end_ts = NOW + 3_600;
    for s in [RigState::Idle, RigState::Broken, RigState::Frozen] {
        w.rig_mut(&focus).state = s;
        assert_eq!(records(&w, policy).1.iter().find(|(a, _)| *a == focus).unwrap().1, RecordSkip::State);
    }
    w.rig_mut(&focus).state = RigState::Cooling;
    assert!(records(&w, policy).0.iter().any(|d| d.rig == focus), "a Cooling rig's fresh heartbeat is recorded");
}

#[test]
fn gate_closed_rigs_are_recorded_only_when_asked() {
    let mut w = World::new();
    let night = w.add(2, true);
    w.rig_mut(&night).plan_max_ev_cost = 1; // gate closed
    let off = RecordPolicy { every_rounds: 3, gate_closed_rigs: false, clock_margin_secs: 5 };
    assert!(records(&w, off).0.is_empty());
    let on = RecordPolicy { gate_closed_rigs: true, ..off };
    assert_eq!(records(&w, on).0.len(), 1);
    // Gate open: the dig carries the heartbeat instead.
    w.rig_mut(&night).plan_max_ev_cost = u64::MAX;
    w.rig_mut(&night).cap_max_cost = u64::MAX;
    assert!(records(&w, on).0.is_empty());
}
