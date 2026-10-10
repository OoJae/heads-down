//! A dig that has not landed is sent again only while its ORE round still takes deploys.
//!
//! The crank rebroadcasts the same signed bytes every 2 s until they confirm. Its time limit
//! for that counts 400 ms a slot. Mainnet's slots took 0.22 s on 10 October 2026, so by the
//! clock alone a dig would still go out after its round had ended, and a dig that lands
//! there is a `RoundNotActive` skip the crank pays for (the first dig on mainnet was one:
//! docs/MAINNET.md). The watcher's view now decides before the clock does.
//!
//! This runs a real dig pass against the JSON-RPC stub of `tests/common/rpc_stub.rs`, which
//! takes the transaction and never lands it, and counts the `sendTransaction` calls while the
//! view the watcher would publish changes. The rig is the one of that first shift: 0.001 SOL
//! on 4 squares, a lease of one round.
//!
//! On the way it checks the other thing that evening resized, the retry gap: a dig that is
//! still unconfirmed is signed again by the round's second pass, 40 slots after the first with
//! the default settings, and not a slot earlier.

mod common;
#[path = "common/rpc_stub.rs"]
mod rpc_stub;

use std::time::{Duration, Instant};

use common::Phone;
use hd_crank::chain::ChainView;
use hd_crank::crank::round_takes_digs;
use hd_crank::hd::{self, HdConfig, HeartbeatFields, Rig, RigAccounts, RigState};
use hd_crank::heartbeat::Offer;
use hd_crank::idle::Pass;
use hd_crank::ore::{self, Board, OreKind};
use rpc_stub::{view, Bench, SendMode};
use solana_address::Address;

const ROUND: u64 = 6_100;
/// The crank's rebroadcast interval (`ConfirmPolicy::default().rebroadcast_every`).
const REBROADCAST_EVERY: Duration = Duration::from_secs(2);

/// An ORE Automation of `authority` that names `executor`: Discretionary, the 10,000 fee.
fn automation_bytes(authority: &Address, executor: &Address, per_square: u64, balance: u64) -> Vec<u8> {
    let mut d = vec![0u8; OreKind::Automation.size()];
    d[0] = OreKind::Automation.discriminator();
    d[8..16].copy_from_slice(&per_square.to_le_bytes());
    d[16..48].copy_from_slice(authority.as_ref());
    d[48..56].copy_from_slice(&balance.to_le_bytes());
    d[56..88].copy_from_slice(executor.as_ref());
    d[88..96].copy_from_slice(&10_000u64.to_le_bytes());
    d[96..104].copy_from_slice(&ore::STRATEGY_DISCRETIONARY.to_le_bytes());
    d[136..144].copy_from_slice(&u64::MAX.to_le_bytes());
    d[146..148].copy_from_slice(&u16::MAX.to_le_bytes());
    d
}

/// An ORE Miner of `authority` whose last round, `round_id`, is settled.
fn miner_bytes(authority: &Address, round_id: u64) -> Vec<u8> {
    let mut d = vec![0u8; OreKind::Miner.size()];
    d[0] = OreKind::Miner.discriminator();
    d[8..40].copy_from_slice(authority.as_ref());
    d[48..56].copy_from_slice(&round_id.to_le_bytes());
    d[56..64].copy_from_slice(&ore::CHECKPOINT_FEE.to_le_bytes());
    d[664..672].copy_from_slice(&round_id.to_le_bytes());
    d
}

fn sends(b: &Bench) -> u64 {
    b.stub.lock().count("sendTransaction")
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_dig_is_sent_again_only_while_its_round_takes_deploys() {
    // ---- the rule, at its edges ------------------------------------------------------------
    let min = 3; // the default `dig.min_slots_left`
    assert!(round_takes_digs(&view(ROUND, 80), ROUND, min));
    assert!(round_takes_digs(&view(ROUND, 3), ROUND, min), "the fewest slots a dig pass still starts with");
    assert!(!round_takes_digs(&view(ROUND, 2), ROUND, min));
    assert!(!round_takes_digs(&view(ROUND, 0), ROUND, min), "the round has ended and the Board is not reset yet");
    assert!(!round_takes_digs(&view(ROUND + 1, 200), ROUND, min), "the Board has moved on to the next round");
    assert!(round_takes_digs(&view(ROUND, 1), ROUND, 0) && !round_takes_digs(&view(ROUND, 0), ROUND, 0), "even with min_slots_left = 0 a slot must be left");
    let mut not_started = view(ROUND, 80);
    not_started.board = not_started.board.map(|b| Board { end_slot: u64::MAX, ..b });
    assert!(round_takes_digs(&not_started, ROUND, min), "a round that has not started has no end yet");
    assert!(round_takes_digs(&ChainView::default(), ROUND, min), "without a Board there is nothing to go by");

    // ---- a rig the planner digs --------------------------------------------------------------
    let b = Bench::new().await;
    let (config, bump) = hd::config_pda(&hd::PROGRAM_ID);
    let (executor, executor_bump) = hd::executor_pda(&hd::PROGRAM_ID);
    let hd_config = HdConfig {
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
    let phone = Phone::new(9);
    let authority = Address::new_from_array([0xC9; 32]);
    let (rig_addr, rig_bump) = hd::rig_pda(&hd::PROGRAM_ID, &authority);
    let rig = Rig {
        bump: rig_bump,
        authority,
        p256_pubkey: phone.pubkey(),
        attestation_level: 1,
        state: RigState::Armed,
        cap_week: 35_350_000,
        cap_shift: 5_050_000,
        cap_round: 1_010_000,
        cap_max_cost: 1_200_000_000,
        caps_expiry_ts: 1_790_086_400,
        plan_max_ev_cost: 1_000_000_000,
        plan_dig_lamports: 1_000_000,
        plan_split_tiles: 4,
        plan_lease_rounds: 1,
        plan_window_start_ts: 1_789_990_000,
        plan_window_end_ts: 1_790_086_400,
        shift_id: 1,
        shift_open: true,
        ..Rig::default()
    };
    let ore_accounts = RigAccounts::derive(rig_addr, authority);
    {
        let mut s = b.stub.lock();
        s.set(config, hd::PROGRAM_ID, 1_950_720, hd_config.encode());
        s.set(executor, ore::SYSTEM_PROGRAM_ID, 1_450_240, Vec::new());
        s.set(rig_addr, hd::PROGRAM_ID, 2_600_960, rig.encode());
        s.set(ore_accounts.automation, ore::ORE_PROGRAM_ID, 6_513_040, automation_bytes(&authority, &executor, 250_000, 5_050_000));
        s.set(ore_accounts.miner, ore::ORE_PROGRAM_ID, 4_480_400, miner_bytes(&authority, ROUND - 1));
        // The node takes every transaction and lands none: what a dig that misses its round
        // looks like from the crank.
        s.send_mode = SendMode::Drop;
    }
    let mut cfg = b.config();
    cfg.alt.enabled = false; // one rig fits a transaction without a lookup table
    let min_slots_left = cfg.dig.min_slots_left;
    let p = b.start(cfg);
    let hb = phone.verified(&hd::PROGRAM_ID, &rig_addr, HeartbeatFields { counter: 1, shift_id: 1, round_id: ROUND, lease_rounds: 1 });
    assert_eq!(p.store.offer(hb), Offer::Stored);

    // ---- the window opens: the dig is planned, simulated and sent once -------------------------
    let opening = view(ROUND, 80);
    p.chain.send_replace(opening.clone());
    assert_eq!(p.crank.dig_tick(&opening).await.unwrap(), Pass::First);
    let sent_at = Instant::now();
    assert_eq!((p.metrics.digs_submitted.get(), p.metrics.txs_sent.get(), sends(&b)), (1, 1, 1), "the dig was planned and sent");

    // While the round is live the same bytes go out again, 2 s after the last send.
    while sends(&b) < 2 {
        assert!(sent_at.elapsed() < Duration::from_secs(10), "no rebroadcast while the round is live");
        tokio::time::sleep(Duration::from_millis(20)).await;
    }
    assert!(sent_at.elapsed() >= REBROADCAST_EVERY);
    assert_eq!(p.metrics.txs_sent.get(), 1, "a rebroadcast is not a new transaction");

    // ---- the round's second pass -----------------------------------------------------------------
    // 39 slots after the first pass the dig is unconfirmed and still inside the default
    // `dig.retry_after_slots` of 40: a pass reads, finds the rig's dig pending, and signs nothing.
    let early = view(ROUND, 41);
    p.chain.send_replace(early.clone());
    p.crank.dig_tick(&early).await.unwrap();
    assert_eq!((p.metrics.digs_submitted.get(), p.metrics.txs_sent.get()), (1, 1), "a second attempt inside the retry gap");
    // 40 slots after it, where the running loop makes the round's second pass, the dig is
    // planned from fresh chain state and signed again: a second transaction, the last one
    // `dig.max_attempts_per_round` allows. It is what still digs the round when the first
    // attempt is lost for good, and what was a paid duplicate on mainnet when the gap was 6.
    let second = view(ROUND, 40);
    p.chain.send_replace(second.clone());
    p.crank.dig_tick(&second).await.unwrap();
    assert_eq!((p.metrics.digs_submitted.get(), p.metrics.txs_sent.get()), (2, 2), "no second attempt after the retry gap");

    // ---- the round ends --------------------------------------------------------------------------
    // The watcher shows the slot at the round's end. The Board still names the round: ORE
    // resets it 48 slots later. A send that was decided a moment ago may still go out.
    p.chain.send_replace(view(ROUND, 0));
    assert!(!round_takes_digs(&p.chain.borrow(), ROUND, min_slots_left));
    tokio::time::sleep(Duration::from_millis(600)).await;
    let (at_the_end, polls) = (sends(&b), b.stub.lock().count("getSignatureStatuses"));
    assert!((3..=5).contains(&at_the_end), "{at_the_end} sends");
    // Two more rebroadcast intervals pass, and neither transaction is sent again. The clock
    // would have allowed it: the limits set when they went out were 80 and 40 slots at 400 ms,
    // 32 s and 16 s.
    tokio::time::sleep(REBROADCAST_EVERY * 2 + Duration::from_millis(600)).await;
    assert_eq!(sends(&b), at_the_end, "a dig was sent again after its round had ended");
    assert!(sent_at.elapsed() < Duration::from_millis(40 * 400), "neither time limit had run out");
    // The transaction is still watched: bytes sent earlier can land late, and that outcome
    // (a skip the crank paid for) belongs in the ledger and the metrics.
    assert!(b.stub.lock().count("getSignatureStatuses") > polls, "the status is still asked for");
}
