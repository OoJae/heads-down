//! Fork suite: the crank's verifier → planner → packer → signed transaction, executed by
//! LiteSVM 0.17 against the **live mainnet ORE binary** and real Board / Config / Treasury /
//! Var / Round accounts (`spikes/ore-executor/fetch-fixtures.sh`), with the mock
//! `heads_down` program from `test-fixtures/mock-heads-down`, or the real program with
//! `--features real-program` (`HD_PROGRAM_SO`, default `programs/heads-down/target/deploy`).
//!
//! The mock implements `dig` only; the BREAK / FREEZE landing, `record_heartbeats`,
//! permissionless `end_shift`, replay and cost tests run against the real program.
//!
//! ```sh
//! spikes/ore-executor/fetch-fixtures.sh                     # or ORE_FIXTURES_DIR=...
//! cargo build-sbf --manifest-path crank/test-fixtures/mock-heads-down/Cargo.toml
//! cargo test --features fork --test fork -- --nocapture --test-threads=1
//! bash programs/heads-down/scripts/build.sh
//! cargo test --features real-program --test fork -- --nocapture --test-threads=1
//! ```
#![cfg(feature = "fork")]

mod common;

use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use async_trait::async_trait;
use base64::Engine;
use common::Phone;
use hd_crank::account::RawAccount;
use hd_crank::alt;
use hd_crank::gate;
use hd_crank::hd::{self, HdConfig, HdEvent, HeartbeatFields, Rig, RigAccounts, RigState};
use hd_crank::heartbeat::{HeartbeatStore, HeartbeatSubmission, ParsedHeartbeat, RigCache, RigSource, Verifier};
use hd_crank::ore::{self, Automation, Board, Miner, Round, Treasury};
use hd_crank::planner::{self, Plan, Policy, Skip};
use hd_crank::tx::{self, BuildParams, CuEstimate, TxFormat};
use litesvm::LiteSVM;
use solana_account::Account;
use solana_address::Address;
use solana_instruction::{AccountMeta, Instruction};
use solana_keypair::Keypair;
use solana_message::AddressLookupTableAccount;
use solana_signer::Signer;

const NOW: i64 = 1_790_000_000;
const EXECUTOR_FEE: u64 = 10_000;
const CRANK_FEE: u64 = 7_000;
const TILE_CAP: u64 = 100_000;
const LAMPORTS_PER_SOL: u64 = 1_000_000_000;

fn fixtures() -> PathBuf {
    std::env::var_os("ORE_FIXTURES_DIR")
        .map(PathBuf::from)
        .unwrap_or_else(|| PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../spikes/ore-executor/fixtures"))
}

fn program_so() -> PathBuf {
    if let Some(p) = std::env::var_os("HD_PROGRAM_SO") {
        return PathBuf::from(p);
    }
    let base = PathBuf::from(env!("CARGO_MANIFEST_DIR"));
    if cfg!(feature = "real-program") {
        base.join("../programs/heads-down/target/deploy/heads_down.so")
    } else {
        base.join("test-fixtures/mock-heads-down/target/deploy/mock_heads_down.so")
    }
}

fn load_account(name: &str) -> Account {
    let path = fixtures().join(format!("{name}.json"));
    let raw = std::fs::read_to_string(&path)
        .unwrap_or_else(|_| panic!("missing {} — run spikes/ore-executor/fetch-fixtures.sh or set ORE_FIXTURES_DIR", path.display()));
    let v: serde_json::Value = serde_json::from_str(&raw).unwrap();
    let a = &v["account"];
    Account {
        lamports: a["lamports"].as_u64().unwrap(),
        data: base64::engine::general_purpose::STANDARD.decode(a["data"][0].as_str().unwrap()).unwrap(),
        owner: a["owner"].as_str().unwrap().parse().unwrap(),
        executable: false,
        rent_epoch: u64::MAX,
    }
}

fn read_fixture_text(name: &str) -> String {
    std::fs::read_to_string(fixtures().join(name)).unwrap().trim().to_string()
}

struct User {
    _wallet: Keypair,
    phone: Phone,
    rig: Address,
    accounts: RigAccounts,
}

struct Fork {
    svm: LiteSVM,
    cranker: Keypair,
    board: Board,
    executor: Address,
    users: Vec<User>,
    alts: Vec<AddressLookupTableAccount>,
}

impl Fork {
    fn new() -> Self {
        let mut svm = LiteSVM::new();
        let ore_so = std::fs::read(fixtures().join("ore.so")).expect("ore.so fixture");
        let entropy_so = std::fs::read(fixtures().join("entropy.so")).expect("entropy.so fixture");
        svm.add_program(ore::ORE_PROGRAM_ID, &ore_so).unwrap();
        svm.add_program(ore::ENTROPY_PROGRAM_ID, &entropy_so).unwrap();
        let so = program_so();
        let hd_so = std::fs::read(&so).unwrap_or_else(|_| {
            panic!("missing {} — cargo build-sbf --manifest-path crank/test-fixtures/mock-heads-down/Cargo.toml (or programs/heads-down/scripts/build.sh)", so.display())
        });
        svm.add_program(hd::PROGRAM_ID, &hd_so).unwrap();

        let round_addr = read_fixture_text("round_address.txt");
        for (addr, name) in [
            (ore::BOARD_ADDRESS, ore::BOARD_ADDRESS.to_string()),
            (ore::CONFIG_ADDRESS, ore::CONFIG_ADDRESS.to_string()),
            (ore::TREASURY_ADDRESS, ore::TREASURY_ADDRESS.to_string()),
            (ore::VAR_ADDRESS, ore::VAR_ADDRESS.to_string()),
            (round_addr.parse().unwrap(), round_addr.clone()),
        ] {
            svm.set_account(addr, load_account(&name)).unwrap();
        }
        let b = svm.get_account(&ore::BOARD_ADDRESS).unwrap();
        let board = Board::decode(&b.owner, &b.data).expect("fixture board matches the pins");
        assert_eq!(ore::round_pda(board.round_id).to_string(), round_addr);
        svm.warp_to_slot(board.start_slot + 10);
        let mut clock = svm.get_sysvar::<solana_clock::Clock>();
        clock.unix_timestamp = NOW;
        svm.set_sysvar(&clock);

        let cranker = Keypair::new();
        svm.airdrop(&cranker.pubkey(), 10 * LAMPORTS_PER_SOL).unwrap();
        let (executor, executor_bump) = hd::executor_pda(&hd::PROGRAM_ID);
        svm.set_account(
            executor,
            Account { lamports: LAMPORTS_PER_SOL / 20, data: vec![], owner: ore::SYSTEM_PROGRAM_ID, executable: false, rent_epoch: u64::MAX },
        )
        .unwrap();
        let (config_addr, config_bump) = hd::config_pda(&hd::PROGRAM_ID);
        let cfg = HdConfig {
            bump: config_bump,
            governance: Address::new_from_array([1; 32]),
            registrar: Address::new_from_array([2; 32]),
            crank_fee: CRANK_FEE,
            executor_fee: EXECUTOR_FEE,
            bury_bps: 0,
            paused: false,
            executor_bump,
            ore_layout_hash: [0; 32],
        };
        svm.set_account(
            config_addr,
            Account { lamports: 10_000_000, data: cfg.encode(), owner: hd::PROGRAM_ID, executable: false, rent_epoch: u64::MAX },
        )
        .unwrap();
        Fork { svm, cranker, board, executor, users: Vec::new(), alts: Vec::new() }
    }

    fn account(&self, a: &Address) -> Option<RawAccount> {
        self.svm
            .get_account(a)
            .filter(|acc| acc.lamports > 0 || !acc.data.is_empty())
            .map(|acc| RawAccount { owner: acc.owner, lamports: acc.lamports, data: acc.data })
    }

    fn rig(&self, u: usize) -> Rig {
        let a = self.account(&self.users[u].rig).unwrap();
        Rig::decode(&hd::PROGRAM_ID, &a.owner, &a.data).unwrap()
    }

    fn set_rig(&mut self, u: usize, f: impl FnOnce(&mut Rig)) {
        let mut r = self.rig(u);
        f(&mut r);
        let addr = self.users[u].rig;
        self.svm
            .set_account(addr, Account { lamports: 10_000_000, data: r.encode(), owner: hd::PROGRAM_ID, executable: false, rent_epoch: u64::MAX })
            .unwrap();
    }

    fn automation(&self, u: usize) -> Automation {
        let a = self.account(&self.users[u].accounts.automation).unwrap();
        Automation::decode(&a.owner, &a.data).unwrap()
    }

    fn miner(&self, u: usize) -> Miner {
        let a = self.account(&self.users[u].accounts.miner).unwrap();
        Miner::decode(&a.owner, &a.data).unwrap()
    }

    fn round(&self) -> Round {
        let a = self.account(&ore::round_pda(self.board.round_id)).unwrap();
        Round::decode(&a.owner, &a.data).unwrap()
    }

    fn treasury(&self) -> Treasury {
        let a = self.account(&ore::TREASURY_ADDRESS).unwrap();
        Treasury::decode(&a.owner, &a.data).unwrap()
    }

    /// A wallet runs ORE `automate` (executor = Executor PDA, Discretionary, fixed fee) and
    /// has a registered, armed Rig (written directly, with the v1.1 cached ORE bumps).
    fn add_user(&mut self, i: u32) -> usize {
        let wallet = Keypair::new();
        self.svm.airdrop(&wallet.pubkey(), 2 * LAMPORTS_PER_SOL).unwrap();
        let authority = wallet.pubkey();
        let rig_addr = hd::rig_pda(&hd::PROGRAM_ID, &authority).0;
        let accounts = RigAccounts::derive(rig_addr, authority);
        let mut data = vec![0u8];
        data.extend_from_slice(&TILE_CAP.to_le_bytes()); // amount per tile
        data.extend_from_slice(&(LAMPORTS_PER_SOL / 10).to_le_bytes()); // deposit
        data.extend_from_slice(&EXECUTOR_FEE.to_le_bytes()); // fee
        data.extend_from_slice(&0u64.to_le_bytes()); // mask
        data.push(2); // Discretionary
        data.extend_from_slice(&0u64.to_le_bytes()); // reload
        data.extend_from_slice(&u64::MAX.to_le_bytes()); // max_production_cost
        data.extend_from_slice(&0u16.to_le_bytes());
        data.extend_from_slice(&u16::MAX.to_le_bytes());
        data.extend_from_slice(&0u16.to_le_bytes());
        data.extend_from_slice(&0u16.to_le_bytes());
        data.extend_from_slice(&0u64.to_le_bytes());
        let automate = Instruction {
            program_id: ore::ORE_PROGRAM_ID,
            accounts: vec![
                AccountMeta::new(authority, true),
                AccountMeta::new(accounts.automation, false),
                AccountMeta::new(self.executor, false),
                AccountMeta::new(accounts.miner, false),
                AccountMeta::new_readonly(ore::SYSTEM_PROGRAM_ID, false),
            ],
            data,
        };
        let t = solana_transaction::Transaction::new_signed_with_payer(
            &[automate],
            Some(&authority),
            &[&wallet],
            self.svm.latest_blockhash(),
        );
        self.svm.send_transaction(t).expect("ORE automate");
        self.svm.expire_blockhash();

        let phone = Phone::new(1000 + i);
        let pda_bump = |seed: &[u8]| Address::find_program_address(&[seed, authority.as_ref()], &ore::ORE_PROGRAM_ID).1;
        let rig = Rig {
            bump: hd::rig_pda(&hd::PROGRAM_ID, &authority).1,
            authority,
            p256_pubkey: phone.pubkey(),
            attestation_level: 1,
            state: RigState::Armed,
            attestation_expiry_slot: u64::MAX,
            cap_week: LAMPORTS_PER_SOL,
            cap_shift: LAMPORTS_PER_SOL / 10,
            cap_round: 5_000_000,
            cap_max_cost: u64::MAX,
            caps_expiry_ts: NOW + 86_400,
            plan_max_ev_cost: u64::MAX,
            plan_dig_lamports: 1_000_000,
            plan_split_tiles: 15,
            plan_lease_rounds: 3,
            plan_window_start_ts: NOW - 3_600,
            plan_window_end_ts: NOW + 3_600,
            shift_id: 1,
            week_start_ts: NOW - 60,
            shift_start_round: self.board.round_id - 10,
            freezes_left: 2,
            shift_open: true,
            ore_automation_bump: pda_bump(ore::AUTOMATION_SEED),
            ore_miner_bump: pda_bump(ore::MINER_SEED),
            shift_start_ts: NOW - 3_600,
            ..Rig::default()
        };
        self.svm
            .set_account(rig_addr, Account { lamports: 10_000_000, data: rig.encode(), owner: hd::PROGRAM_ID, executable: false, rent_epoch: u64::MAX })
            .unwrap();
        self.users.push(User { _wallet: wallet, phone, rig: rig_addr, accounts });
        self.users.len() - 1
    }

    fn verifier(&self, store: &Arc<HeartbeatStore>) -> Verifier<MapSource> {
        let src = MapSource(Arc::new(Mutex::new(
            self.users.iter().enumerate().map(|(i, u)| (u.rig, self.rig(i))).collect(),
        )));
        Verifier {
            program_id: hd::PROGRAM_ID,
            rigs: RigCache::new(src, Duration::from_secs(60), Duration::from_secs(30), 100, 100.0),
            store: store.clone(),
        }
    }

    /// Phone → JSON → crank verifier → store, exactly as the WebSocket intake does.
    fn heartbeat(&self, store: &Arc<HeartbeatStore>, u: usize, counter: u64, lease: u8) -> Result<(), hd_crank::heartbeat::Reject> {
        self.heartbeat_for(store, u, counter, lease, self.board.round_id)
    }

    fn heartbeat_for(&self, store: &Arc<HeartbeatStore>, u: usize, counter: u64, lease: u8, round_id: u64) -> Result<(), hd_crank::heartbeat::Reject> {
        let user = &self.users[u];
        let rig = self.rig(u);
        let f = HeartbeatFields { counter, shift_id: rig.shift_id, round_id, lease_rounds: lease };
        let raw = user.phone.sign_raw(&hd::PROGRAM_ID, &user.rig, &f);
        let json = serde_json::json!({
            "type": "heartbeat", "rig": user.rig.to_string(), "counter": counter, "shift_id": f.shift_id,
            "round_id": f.round_id, "lease_rounds": lease, "sig64": base64::engine::general_purpose::STANDARD.encode(raw)
        });
        let sub: HeartbeatSubmission = serde_json::from_value(json).unwrap();
        let parsed = ParsedHeartbeat::parse(&sub)?;
        let rt = tokio::runtime::Builder::new_current_thread().enable_all().build().unwrap();
        rt.block_on(self.verifier(store).process(&parsed, Some(self.board.round_id))).map(|_| ())
    }

    fn plan(&self, store: &HeartbeatStore) -> Plan {
        let treasury = self.treasury();
        let round = self.round();
        let cfg_acc = self.account(&hd::config_pda(&hd::PROGRAM_ID).0).unwrap();
        let config = HdConfig::decode(&hd::PROGRAM_ID, &cfg_acc.owner, &cfg_acc.data).unwrap();
        let rigs: Vec<(Address, Rig)> = self.users.iter().enumerate().map(|(i, u)| (u.rig, self.rig(i))).collect();
        let mut automations = HashMap::new();
        let mut miners = HashMap::new();
        for u in &self.users {
            automations.insert(u.accounts.automation, self.account(&u.accounts.automation));
            miners.insert(u.accounts.miner, self.account(&u.accounts.miner));
        }
        let heartbeats = store.snapshot().into_iter().map(|h| (h.rig, h)).collect();
        let slot = self.svm.get_sysvar::<solana_clock::Clock>().slot;
        let inputs = planner::Inputs {
            program_id: &hd::PROGRAM_ID,
            now_ts: NOW,
            landing_slot: slot,
            board: &self.board,
            treasury: &treasury,
            round: Some(&round),
            config: &config,
            executor_lamports: self.svm.get_balance(&self.executor).unwrap(),
            rigs: &rigs,
            automations: &automations,
            miners: &miners,
            heartbeats: &heartbeats,
        };
        planner::plan(&inputs, &Policy::default(), &|_: &Address, _| false)
    }

    fn params(&self, format: TxFormat) -> BuildParams {
        BuildParams {
            program_id: hd::PROGRAM_ID,
            cranker: self.cranker.pubkey(),
            round_id: self.board.round_id,
            format,
            cu_price_micro_lamports: 1_000,
            cu_limit: None,
            cu_estimate: CuEstimate::default(),
            loaded_accounts_data_size_limit: 64 * 1024 * 1024,
            max_account_locks: 64,
            max_rigs_per_tx: 16,
            tip: None,
        }
    }

    /// Create the crank's lookup table through the real ALT program and let it warm up.
    fn make_alt(&mut self) {
        let slot = self.svm.get_sysvar::<solana_clock::Clock>().slot;
        let recent = self.svm.get_sysvar::<solana_slot_hashes::SlotHashes>().first().map(|(s, _)| *s).unwrap_or(slot);
        let (create, key) = alt::create_table_ix(&self.cranker.pubkey(), &self.cranker.pubkey(), recent);
        let mut wanted = alt::shared_addresses(&hd::PROGRAM_ID);
        for u in &self.users {
            wanted.extend(alt::rig_addresses(&u.accounts));
        }
        let mut ixs = vec![create];
        for chunk in wanted.chunks(alt::MAX_ADDRESSES_PER_EXTEND) {
            ixs.push(alt::extend_table_ix(&key, &self.cranker.pubkey(), &self.cranker.pubkey(), chunk));
        }
        for ix in ixs {
            let t = solana_transaction::Transaction::new_signed_with_payer(&[ix], Some(&self.cranker.pubkey()), &[&self.cranker], self.svm.latest_blockhash());
            self.svm.send_transaction(t).expect("ALT create/extend");
            self.svm.expire_blockhash();
        }
        self.svm.warp_to_slot(slot + 1);
        let mut clock = self.svm.get_sysvar::<solana_clock::Clock>();
        clock.unix_timestamp = NOW;
        self.svm.set_sysvar(&clock);
        let acc = self.svm.get_account(&key).unwrap();
        let table = alt::LookupTable::decode(key, &acc.owner, &acc.data).unwrap();
        self.alts = vec![table.usable(slot + 1)];
        assert_eq!(self.alts[0].addresses.len(), wanted.len());
    }

    fn send(&mut self, t: solana_transaction::versioned::VersionedTransaction) -> Result<litesvm::types::TransactionMetadata, String> {
        let r = self.svm.send_transaction(t).map_err(|e| format!("{:?}\n{}", e.err, e.meta.logs.join("\n")));
        self.svm.expire_blockhash();
        r
    }
}

#[derive(Clone)]
struct MapSource(Arc<Mutex<HashMap<Address, Rig>>>);

#[async_trait]
impl RigSource for MapSource {
    async fn fetch_rig(&self, rig: &Address) -> anyhow::Result<Option<Rig>> {
        Ok(self.0.lock().unwrap().get(rig).cloned())
    }
}

#[test]
fn crank_digs_through_live_ore() {
    let mut f = Fork::new();
    let store = Arc::new(HeartbeatStore::new(100));
    let r = f.board.round_id;
    let ema_ev = gate::ema_ev(f.board.production_cost_ema, f.treasury().motherlode).unwrap();
    println!("fixture round {r}: ema {} lamports/ORE, pot {} ORE, ema_ev {ema_ev}", f.board.production_cost_ema, f.treasury().motherlode / ore::ONE_ORE);

    let a = f.add_user(1); // fresh heartbeat
    let b = f.add_user(2); // fresh heartbeat, miner owes a checkpoint for a closed round
    let c = f.add_user(3); // on-chain lease covers this round: no heartbeat needed
    let d = f.add_user(4); // gate closed for this rig
    let e = f.add_user(5); // frozen
    assert_eq!(f.heartbeat(&store, a, 1, 2), Ok(()));
    assert_eq!(f.heartbeat(&store, b, 1, 1), Ok(()));
    assert_eq!(f.heartbeat(&store, d, 1, 1), Ok(()));
    f.set_rig(c, |rig| {
        rig.state = RigState::Down;
        rig.lease_from_round = r;
        rig.lease_to_round = r;
        rig.hb_counter = 9;
    });
    f.set_rig(d, |rig| rig.plan_max_ev_cost = ema_ev - 1);
    f.set_rig(e, |rig| rig.state = RigState::Frozen);
    // Miner B last deployed in round r-5 (account long closed) and never checkpointed it.
    let mut m = f.svm.get_account(&f.users[b].accounts.miner).unwrap();
    m.data[48..56].copy_from_slice(&(r - 6).to_le_bytes());
    m.data[664..672].copy_from_slice(&(r - 5).to_le_bytes());
    f.svm.set_account(f.users[b].accounts.miner, m).unwrap();

    // --- plan -------------------------------------------------------------------------
    let plan = f.plan(&store);
    let skip = |u: usize| plan.skips.iter().find(|(x, _)| *x == f.users[u].rig).map(|(_, s)| s.clone());
    assert_eq!(skip(d), Some(Skip::CostGate));
    assert_eq!(skip(e), Some(Skip::NotDiggable(RigState::Frozen)));
    assert_eq!(plan.digs.len(), 3);
    assert_eq!(plan.digs[0].dig.accounts.rig, f.users[c].rig, "lease reuse first");
    assert!(plan.digs[0].dig.heartbeat.is_none());
    let b_dec = plan.digs.iter().find(|x| x.dig.accounts.rig == f.users[b].rig).unwrap();
    assert_eq!(b_dec.dig.checkpoint_round, Some(r - 5));

    // --- pack into a v0 transaction with the crank's lookup table -------------------------
    f.make_alt();
    let p = f.params(TxFormat::V0);
    let rigs: Vec<_> = plan.digs.iter().map(|x| x.dig).collect();
    let (batches, rejected) = tx::pack(&p, &rigs, &f.alts);
    assert!(rejected.is_empty());
    assert_eq!(batches.len(), 1, "3 rigs (2 fresh heartbeats) fit one v0 tx");
    println!("v0 batch: {} rigs, {} bytes, {} accounts", batches[0].rigs.len(), batches[0].wire_size, batches[0].accounts);

    let before_round = f.round();
    let before_auto: Vec<u64> = (0..3).map(|u| f.automation([a, b, c][u]).balance).collect();
    let cranker_before = f.svm.get_balance(&f.cranker.pubkey()).unwrap();
    let t = tx::sign_batch(&p, &batches[0].rigs, &f.alts, f.svm.latest_blockhash(), &f.cranker).unwrap();
    let meta = f.send(t).expect("dig lands");
    println!("dig tx: {} CU for 3 rigs (1 checkpoint)", meta.compute_units_consumed);

    // --- verify on-chain effects against the plan ------------------------------------------
    let events = hd::events_from_logs(&hd::PROGRAM_ID, &meta.logs);
    let dug: Vec<_> = events
        .iter()
        .filter_map(|e| match e {
            HdEvent::RigDug { rig, round_id, lamports, mask, ema_ev } => Some((*rig, *round_id, *lamports, *mask, *ema_ev)),
            _ => None,
        })
        .collect();
    assert_eq!(dug.len(), 3, "events: {events:?}");
    let after_round = f.round();
    let mut expected_delta = [0u64; 25];
    for dec in &plan.digs {
        let (_, round_id, lamports, mask, ev) = *dug.iter().find(|x| x.0 == dec.dig.accounts.rig).unwrap();
        assert_eq!(round_id, r);
        assert_eq!(ev, ema_ev, "program and crank agree on the gate value");
        assert_eq!(Some(mask), dec.predicted_mask, "crank predicted the program's tile choice");
        assert_eq!(mask.count_ones(), 15);
        assert_eq!(u32::from(dec.tiles), mask.count_ones(), "k = popcount(mask)");
        assert_eq!(mask & ore::distribution_mask(r), 0, "split squares only");
        assert_eq!(lamports, dec.squares_lamports, "RigDug.lamports = SOL on squares, no fee (INTERFACE v1.1)");
        for (i, delta) in expected_delta.iter_mut().enumerate() {
            if mask & (1 << i) != 0 {
                *delta += dec.per_tile;
            }
        }
    }
    for (i, delta) in expected_delta.iter().enumerate() {
        assert_eq!(after_round.deployed[i] - before_round.deployed[i], *delta, "square {i}");
    }
    for (k, u) in [a, b, c].into_iter().enumerate() {
        let dec = plan.digs.iter().find(|x| x.dig.accounts.rig == f.users[u].rig).unwrap();
        assert_eq!(dec.fee_due, EXECUTOR_FEE, "first deploy of the round");
        assert_eq!(before_auto[k] - f.automation(u).balance, dec.expected_debit, "Automation debit = squares + executor_fee");
        let rig = f.rig(u);
        assert_eq!(rig.last_dug_round, r);
        assert_eq!(rig.state, RigState::Down);
        assert_eq!(rig.spent_shift, dec.expected_debit, "spent_shift counts the whole debit");
        assert_eq!(rig.lifetime_lamports_deployed, dec.squares_lamports, "lifetime deployed counts squares only");
    }
    assert_eq!(f.rig(a).hb_counter, 1);
    assert_eq!((f.rig(a).lease_from_round, f.rig(a).lease_to_round), (r, r + 1));
    assert_eq!(f.rig(c).hb_counter, 9, "lease reuse consumed no heartbeat");
    // The prepended checkpoint settled round r-5 (its account was closed, so ORE only marks
    // it), then deploy reset the miner into round r (deploy.rs:251-260).
    assert_eq!(f.miner(b).checkpoint_id, r - 5, "checkpoint settled the old round");
    assert_eq!(f.miner(b).round_id, r, "deploy moved the miner into this round");
    assert!(f.miner(b).needs_checkpoint(), "round r will need its own checkpoint after reset");
    let cranker_after = f.svm.get_balance(&f.cranker.pubkey()).unwrap();
    let fee = 3 * 5_000 + tx::priority_fee_lamports(batches[0].cu_limit, p.cu_price_micro_lamports);
    assert_eq!(cranker_after + fee, cranker_before + 3 * CRANK_FEE, "reimbursed 3 x crank_fee, paid 1 + 2 signatures");
    println!("cranker: paid {fee} lamports, reimbursed {}", 3 * CRANK_FEE);

    // --- same round again: the planner refuses, and a forced replay is skipped on-chain ----
    let again = f.plan(&store);
    assert!(again.digs.is_empty());
    assert!(again.skips.iter().any(|(x, s)| *x == f.users[a].rig && *s == Skip::AlreadyDug));
    let balance_a = f.automation(a).balance;
    // Resending the *old* instructions fails outright: the prepended checkpoint names round
    // r-5, but the miner now sits in round r, and ORE re-derives a closed round's PDA from
    // miner.round_id (checkpoint.rs:38-43). Retries must re-plan from fresh Miner state.
    let t = tx::sign_batch(&p, &batches[0].rigs, &f.alts, f.svm.latest_blockhash(), &f.cranker).unwrap();
    let stale = f.send(t).expect_err("stale checkpoint aborts the batch");
    assert!(stale.contains("InvalidSeeds"), "{stale}");
    // Without the stale checkpoint, the replay lands and the program skips every rig.
    let replay: Vec<_> = batches[0].rigs.iter().map(|r| tx::RigDig { checkpoint_round: None, ..*r }).collect();
    let t = tx::sign_batch(&p, &replay, &f.alts, f.svm.latest_blockhash(), &f.cranker).unwrap();
    let meta = f.send(t).expect("replay tx lands but digs nothing");
    let skipped: Vec<u32> = hd::events_from_logs(&hd::PROGRAM_ID, &meta.logs)
        .into_iter()
        .filter_map(|e| match e {
            HdEvent::RigSkipped { error, .. } => Some(error),
            _ => None,
        })
        .collect();
    assert_eq!(skipped.len(), 3);
    assert!(skipped.iter().all(|c| matches!(hd::error_name(*c), "StaleHeartbeat" | "AlreadyDugRound")), "{skipped:?}");
    assert_eq!(f.automation(a).balance, balance_a, "no second deploy");
}

#[test]
fn v1_and_legacy_transactions_land() {
    for format in [TxFormat::V1, TxFormat::Legacy] {
        let mut f = Fork::new();
        let store = Arc::new(HeartbeatStore::new(100));
        let n = if format == TxFormat::V1 { 6 } else { 1 };
        for i in 0..n {
            let u = f.add_user(10 + i);
            f.heartbeat(&store, u, 1, 1).unwrap();
        }
        let plan = f.plan(&store);
        assert_eq!(plan.digs.len(), n as usize);
        let p = f.params(format);
        let rigs: Vec<_> = plan.digs.iter().map(|x| x.dig).collect();
        let (batches, rejected) = tx::pack(&p, &rigs, &[]);
        assert!(rejected.is_empty());
        assert_eq!(batches.len(), 1, "{format:?}");
        let t = tx::sign_batch(&p, &batches[0].rigs, &[], f.svm.latest_blockhash(), &f.cranker).unwrap();
        let meta = f.send(t).unwrap_or_else(|e| panic!("{format:?}: {e}"));
        let dug = hd::events_from_logs(&hd::PROGRAM_ID, &meta.logs).iter().filter(|e| matches!(e, HdEvent::RigDug { .. })).count();
        assert_eq!(dug, n as usize, "{format:?}");
        println!(
            "{format:?}: {n} rigs, {} bytes, {} CU ({} per rig)",
            batches[0].wire_size,
            meta.compute_units_consumed,
            meta.compute_units_consumed / u64::from(n)
        );
    }
}

#[test]
fn heartbeat_binding_is_enforced_on_chain() {
    let mut f = Fork::new();
    let store = Arc::new(HeartbeatStore::new(100));
    let a = f.add_user(20);
    let b = f.add_user(21);
    f.heartbeat(&store, a, 1, 1).unwrap();
    f.heartbeat(&store, b, 1, 1).unwrap();
    let plan = f.plan(&store);
    let p = f.params(TxFormat::V1);
    let mut rigs: Vec<_> = plan.digs.iter().map(|x| x.dig).collect();
    assert_eq!(rigs.len(), 2);

    // (1) A heartbeat signed for another shift verifies in the precompile (it is a valid
    // signature over *its* digest), but the program rebuilds the preimage from rig.shift_id,
    // so the rig is skipped with p256-introspect MessageMismatch (0x2560000e). The crank never
    // builds this: its planner requires shift_id == rig.shift_id.
    let wrong = HeartbeatFields { counter: 1, shift_id: 99, round_id: f.board.round_id, lease_rounds: 1 };
    let ia = rigs.iter().position(|x| x.accounts.rig == f.users[a].rig).unwrap();
    rigs[ia].heartbeat = Some(f.users[a].phone.verified(&hd::PROGRAM_ID, &f.users[a].rig, wrong));
    let t = tx::sign_batch(&p, &rigs, &[], f.svm.latest_blockhash(), &f.cranker).unwrap();
    let meta = f.send(t).expect("tx lands; only rig A is skipped");
    let evs = hd::events_from_logs(&hd::PROGRAM_ID, &meta.logs);
    assert!(
        evs.iter().any(|e| matches!(e, HdEvent::RigSkipped { rig, error, .. } if *rig == f.users[a].rig && *error == 0x2560_000e)),
        "{evs:?}"
    );
    assert_eq!(hd::error_name(0x2560_000e), "P256MessageMismatch");
    assert!(evs.iter().any(|e| matches!(e, HdEvent::RigDug { rig, .. } if *rig == f.users[b].rig)));

    // (2) A signature that does not verify sinks the whole batch in the precompile: this is
    // why the crank verifies every heartbeat off-chain before paying for a transaction.
    let good = rigs[ia].heartbeat.unwrap();
    let mut forged = good;
    forged.sig = f.users[b].phone.verified(&hd::PROGRAM_ID, &f.users[a].rig, good.fields).sig; // B's key, A's rig
    assert!(hd_crank::heartbeat::verify_signature(&good.pubkey, &good.digest, &forged.sig).is_err());
    rigs[ia].heartbeat = Some(forged);
    let t = tx::sign_batch(&p, &rigs, &[], f.svm.latest_blockhash(), &f.cranker).unwrap();
    assert!(f.send(t).is_err(), "precompile rejects the forged entry");
}

#[test]
fn cooling_rig_digs_only_with_a_fresh_heartbeat() {
    let mut f = Fork::new();
    let store = Arc::new(HeartbeatStore::new(100));
    let a = f.add_user(30);
    let r = f.board.round_id;
    f.set_rig(a, |rig| {
        rig.state = RigState::Cooling;
        rig.lease_from_round = r - 1;
        rig.lease_to_round = r + 1;
        rig.hb_counter = 5;
        rig.break_reason = hd::reason::PICKUP;
    });
    // The planner refuses to reuse the lease, and so does the program.
    let plan = f.plan(&store);
    assert_eq!(plan.skips, vec![(f.users[a].rig, Skip::CoolingNeedsHeartbeat)]);
    let p = f.params(TxFormat::Legacy);
    let reuse = tx::RigDig { accounts: f.users[a].accounts, heartbeat: None, checkpoint_round: None };
    let t = tx::sign_batch(&p, &[reuse], &[], f.svm.latest_blockhash(), &f.cranker).unwrap();
    let meta = f.send(t).unwrap();
    let evs = hd::events_from_logs(&hd::PROGRAM_ID, &meta.logs);
    assert!(matches!(evs[..], [HdEvent::RigSkipped { error: 13, .. }]), "RigNotArmed: {evs:?}");
    // A fresh heartbeat (counter above the BREAK's) resumes it and digs.
    assert_eq!(f.heartbeat(&store, a, 6, 1), Ok(()));
    let plan = f.plan(&store);
    assert_eq!(plan.digs.len(), 1);
    let t = tx::sign_batch(&p, &[plan.digs[0].dig], &[], f.svm.latest_blockhash(), &f.cranker).unwrap();
    let meta = f.send(t).unwrap();
    assert!(hd::events_from_logs(&hd::PROGRAM_ID, &meta.logs).iter().any(|e| matches!(e, HdEvent::RigDug { .. })));
    assert_eq!(f.rig(a).state, RigState::Down);
}

// ---------------------------------------------------------------------------------------
// Real program only: BREAK / FREEZE landing, record_heartbeats, end_shift, replay, costs.

#[cfg(feature = "real-program")]
mod real {
    use super::*;
    use hd_crank::demo;
    use hd_crank::heartbeat::{ParsedSignal, SignalSubmission};
    use hd_crank::rpc::FetchedInstruction;
    use p256::ecdsa::{signature::Signer as _, Signature};

    impl Fork {
        fn balance(&self, a: &Address) -> u64 {
            self.svm.get_balance(a).unwrap_or(0)
        }

        /// A phone-signed BREAK / FREEZE, verified by the crank exactly as the intake does.
        fn signal(&self, u: usize, kind: hd::SignalKind, counter: u64, reason: u8) -> hd_crank::heartbeat::VerifiedSignal {
            let rig = self.rig(u);
            let digest = hd::digest(&hd::break_preimage(&hd::PROGRAM_ID, &self.users[u].rig, kind.message_kind(), counter, rig.shift_id, reason));
            let sig: Signature = self.users[u].phone.sk.sign(&digest);
            let raw: [u8; 64] = sig.to_bytes().into();
            let sub = SignalSubmission {
                rig: self.users[u].rig.to_string(),
                counter,
                shift_id: rig.shift_id,
                reason: u64::from(reason),
                sig64: base64::engine::general_purpose::STANDARD.encode(raw),
            };
            let parsed = ParsedSignal::parse(kind, &sub).unwrap();
            let store = Arc::new(HeartbeatStore::new(10));
            let rt = tokio::runtime::Builder::new_current_thread().enable_all().build().unwrap();
            rt.block_on(self.verifier(&store).process_signal(&parsed)).unwrap()
        }

        fn land_signal(&mut self, s: &hd_crank::heartbeat::VerifiedSignal) -> Result<litesvm::types::TransactionMetadata, String> {
            let c = hd_crank::config::SignalsConfig::default();
            let ixs = tx::signal_instructions(
                &hd::PROGRAM_ID,
                s.kind,
                &s.rig,
                &s.authority,
                s.reason,
                s.counter,
                s.sig,
                s.pubkey,
                s.digest,
                c.cu_limit,
                c.cu_price_micro_lamports,
            )
            .unwrap();
            let t = tx::sign_legacy(&ixs, &self.cranker, self.svm.latest_blockhash()).unwrap();
            self.send(t)
        }
    }

    #[test]
    fn crank_lands_phone_signed_break_and_freeze() {
        let mut f = Fork::new();
        let store = Arc::new(HeartbeatStore::new(100));
        let a = f.add_user(40);
        let r = f.board.round_id;
        f.set_rig(a, |rig| {
            rig.state = RigState::Down;
            rig.lease_from_round = r;
            rig.lease_to_round = r + 2;
            rig.hb_counter = 3;
        });
        // BREAK pickup (1): Down → Cooling, ShiftBroken(1), counter consumed.
        let brk = f.signal(a, hd::SignalKind::Break, 4, hd::reason::PICKUP);
        let before = f.balance(&f.cranker.pubkey());
        let meta = f.land_signal(&brk).expect("BREAK lands");
        let fee = before - f.balance(&f.cranker.pubkey());
        println!("BREAK: {} CU, fee {fee} lamports (1 tx + 1 secp256r1 signature + priority)", meta.compute_units_consumed);
        let c = hd_crank::config::SignalsConfig::default();
        assert_eq!(fee, c.est_fee(), "2 signatures + the priority fee at the default CU limit");
        assert!(meta.compute_units_consumed < u64::from(c.cu_limit), "the signal CU limit fits");
        let evs = hd::events_from_logs(&hd::PROGRAM_ID, &meta.logs);
        assert_eq!(evs, vec![HdEvent::ShiftBroken { rig: f.users[a].rig, shift_id: 1, reason: 1 }]);
        let rig = f.rig(a);
        assert_eq!((rig.state, rig.hb_counter, rig.break_reason), (RigState::Cooling, 4, 1));
        // Idempotent on-chain: the same signal again is refused (StaleHeartbeat fails the tx).
        let again = f.land_signal(&brk).expect_err("a stale counter is never accepted twice");
        assert!(again.contains("Custom(7)"), "{again}");
        // The dig planner will not reuse the lease of a Cooling rig.
        assert_eq!(f.plan(&store).skips, vec![(f.users[a].rig, Skip::CoolingNeedsHeartbeat)]);
        // BREAK unlocked (8): Cooling → Broken.
        let brk2 = f.signal(a, hd::SignalKind::Break, 5, hd::reason::UNLOCKED);
        let meta = f.land_signal(&brk2).expect("BREAK unlocked lands");
        assert!(matches!(hd::events_from_logs(&hd::PROGRAM_ID, &meta.logs)[..], [HdEvent::ShiftBroken { reason: 8, .. }]));
        assert_eq!(f.rig(a).state, RigState::Broken);
        // FREEZE (3) interrupts the open shift: Frozen, ShiftBroken(3).
        let frz = f.signal(a, hd::SignalKind::Freeze, 6, hd::reason::FREEZE);
        let meta = f.land_signal(&frz).expect("FREEZE lands");
        assert!(matches!(hd::events_from_logs(&hd::PROGRAM_ID, &meta.logs)[..], [HdEvent::ShiftBroken { reason: 3, .. }]));
        let rig = f.rig(a);
        assert_eq!((rig.state, rig.hb_counter, rig.break_reason), (RigState::Frozen, 6, 3));
        // A heartbeat below the signals' counters would be stale on-chain too.
        assert_eq!(f.heartbeat(&store, a, 6, 1), Err(hd_crank::heartbeat::Reject::NotArmed));
    }

    #[test]
    fn crank_records_heartbeats_of_focus_only_rigs() {
        let mut f = Fork::new();
        let store = Arc::new(HeartbeatStore::new(100));
        let r = f.board.round_id;
        let users: Vec<usize> = (0..9).map(|i| f.add_user(50 + i)).collect();
        for &u in &users {
            f.set_rig(u, |rig| {
                rig.plan_flags = hd::PLAN_FLAG_FOCUS_ONLY;
                rig.plan_dig_lamports = 0;
                rig.plan_split_tiles = 0;
                rig.shift_start_round = r;
            });
            f.heartbeat(&store, u, 1, 3).unwrap();
        }
        // The dig planner leaves focus-only rigs alone ...
        let plan = f.plan(&store);
        assert!(plan.digs.is_empty());
        assert!(plan.skips.iter().all(|(_, s)| *s == Skip::FocusOnly));
        // ... and the record planner takes them.
        let rigs: Vec<(Address, Rig)> = users.iter().map(|&u| (f.users[u].rig, f.rig(u))).collect();
        let heartbeats = store.snapshot().into_iter().map(|h| (h.rig, h)).collect();
        let (decisions, _) = planner::plan_records(&f.board, &f.treasury(), NOW, &rigs, &heartbeats, &planner::RecordPolicy::default());
        assert_eq!(decisions.len(), 9);
        assert!(decisions.iter().all(|d| d.grant.dark_added == 3));
        let mut p = f.params(TxFormat::Legacy);
        p.max_rigs_per_tx = 8;
        let est = hd_crank::config::RecordConfig::default().cu_estimate();
        let recs: Vec<tx::RecordRig> = decisions.iter().map(|d| tx::RecordRig { rig: d.rig, heartbeat: d.heartbeat }).collect();
        let (batches, rejected) = tx::pack_records(&p, &est, &recs, &[]);
        assert!(rejected.is_empty());
        println!("record_heartbeats, legacy: rigs per tx = {:?}", batches.iter().map(|b| b.rigs.len()).collect::<Vec<_>>());
        let mut recorded = 0;
        for b in &batches {
            let before = f.balance(&f.cranker.pubkey());
            let t = tx::sign_record_batch(&p, &est, &b.rigs, &[], f.svm.latest_blockhash(), &f.cranker).unwrap();
            let meta = f.send(t).expect("record_heartbeats lands");
            let fee = before - f.balance(&f.cranker.pubkey());
            let n = b.rigs.len() as u64;
            println!(
                "record_heartbeats: {n} rigs, {} bytes, {} CU ({} per rig), fee {fee} lamports ({} per rig)",
                b.wire_size,
                meta.compute_units_consumed,
                meta.compute_units_consumed / n,
                fee / n
            );
            assert_eq!(fee, (1 + n) * 5_000 + tx::priority_fee_lamports(b.cu_limit, p.cu_price_micro_lamports));
            assert!(meta.compute_units_consumed <= u64::from(b.cu_limit));
            for e in hd::events_from_logs(&hd::PROGRAM_ID, &meta.logs) {
                match e {
                    HdEvent::HeartbeatsRecorded { round_id, dark_rounds_added, .. } => {
                        assert_eq!((round_id, dark_rounds_added), (r, 3));
                        recorded += 1;
                    }
                    other => panic!("unexpected {other:?}"),
                }
            }
        }
        assert_eq!(recorded, 9);
        for &u in &users {
            let rig = f.rig(u);
            assert_eq!((rig.state, rig.hb_counter, rig.lease_from_round, rig.lease_to_round), (RigState::Down, 1, r, r + 2));
            assert_eq!(rig.shift_dark_rounds, 3, "the focus-only shift's dark rounds count on-chain");
            assert_eq!(rig.last_dug_round, 0, "nothing was deployed");
        }
        // Not due again until every_rounds after lease_from.
        let rigs: Vec<(Address, Rig)> = users.iter().map(|&u| (f.users[u].rig, f.rig(u))).collect();
        let (again, skips) = planner::plan_records(&f.board, &f.treasury(), NOW, &rigs, &heartbeats, &planner::RecordPolicy::default());
        assert!(again.is_empty());
        assert!(skips.iter().all(|(_, s)| *s == planner::RecordSkip::NotDue));
    }

    #[test]
    fn crank_ends_stale_shifts_permissionlessly() {
        let mut f = Fork::new();
        let a = f.add_user(60);
        let b = f.add_user(61);
        let r = f.board.round_id;
        // A: the window ended and the lease lapsed: anyone may end it.
        f.set_rig(a, |rig| {
            rig.state = RigState::Down;
            rig.plan_window_end_ts = NOW - 120;
            rig.lease_from_round = r - 3;
            rig.lease_to_round = r - 1;
            rig.shift_start_round = r - 5;
            rig.shift_dark_rounds = 3;
            rig.shift_rounds_dug = 2;
            rig.spent_shift = 2_010_000;
        });
        // B: the window ended but the lease still covers the round: the crank may not.
        f.set_rig(b, |rig| {
            rig.state = RigState::Down;
            rig.plan_window_end_ts = NOW - 120;
            rig.lease_from_round = r;
            rig.lease_to_round = r;
        });
        let cranker = f.cranker.pubkey();
        let before = f.balance(&cranker);
        let cu = hd_crank::config::EndShiftConfig::default().cu_limit;
        let ixs = tx::end_shift_instructions(&hd::PROGRAM_ID, &cranker, &f.users[a].rig, 1, cu, 1_000);
        let t = tx::sign_legacy(&ixs, &f.cranker, f.svm.latest_blockhash()).unwrap();
        let meta = f.send(t).expect("permissionless end_shift lands");
        let paid = before - f.balance(&cranker);
        let log_addr = hd::shift_log_pda(&hd::PROGRAM_ID, &f.users[a].rig, 1).0;
        let rent = f.balance(&log_addr);
        println!("end_shift: {} CU, paid {paid} lamports = ShiftLog rent {rent} + fee {}", meta.compute_units_consumed, paid - rent);
        assert_eq!(rent, 1_781_760, "128-byte ShiftLog at the default rent");
        assert!(meta.compute_units_consumed < u64::from(cu));
        let evs = hd::events_from_logs(&hd::PROGRAM_ID, &meta.logs);
        assert_eq!(evs.len(), 1, "tag 4 is dropped when tag 10 follows: {evs:?}");
        match evs[0] {
            HdEvent::ShiftEndedV2 { shift_id, dark_rounds, rounds_dug, lamports, reason, start_round, end_round, mode, .. } => {
                assert_eq!((shift_id, dark_rounds, rounds_dug, lamports, reason, mode), (1, 3, 2, 2_010_000, 0, 0));
                assert_eq!((start_round, end_round), (r - 5, r));
            }
            ref other => panic!("{other:?}"),
        }
        let log = f.account(&log_addr).unwrap();
        let sl = hd::ShiftLog::decode(&hd::PROGRAM_ID, &log.owner, &log.data).unwrap();
        assert_eq!((sl.shift_id, sl.break_reason, sl.mode, sl.end_ts), (1, 0, 0, NOW));
        let rig = f.rig(a);
        assert!(!rig.shift_open);
        assert_eq!(rig.state, RigState::Idle);
        // B is refused: its lease has not expired.
        let ixs = tx::end_shift_instructions(&hd::PROGRAM_ID, &cranker, &f.users[b].rig, 1, 30_000, 1_000);
        let t = tx::sign_legacy(&ixs, &f.cranker, f.svm.latest_blockhash()).unwrap();
        let err = f.send(t).expect_err("lease still live");
        assert!(err.contains("Custom(5)"), "Unauthorized: {err}");
    }

    #[test]
    fn replaying_a_landed_dig_is_refused_as_stale() {
        let mut f = Fork::new();
        let store = Arc::new(HeartbeatStore::new(100));
        let a = f.add_user(70);
        let b = f.add_user(71);
        f.heartbeat(&store, a, 1, 1).unwrap();
        f.heartbeat(&store, b, 1, 1).unwrap();
        // A normal dig for both lands first.
        let plan = f.plan(&store);
        let p = f.params(TxFormat::V0);
        let rigs: Vec<_> = plan.digs.iter().map(|x| x.dig).collect();
        let landed = tx::sign_batch(&p, &rigs, &[], f.svm.latest_blockhash(), &f.cranker).unwrap();
        let msg_ixs = tx::build_instructions(&p, &rigs).unwrap();
        f.send(landed).expect("the original dig lands");
        // What `getTransaction` would return for it: the top-level instructions.
        let fetched: Vec<FetchedInstruction> = msg_ixs
            .iter()
            .map(|ix| FetchedInstruction { program_id: ix.program_id, accounts: ix.accounts.iter().map(|m| m.pubkey).collect(), data: ix.data.clone() })
            .collect();
        let dig = demo::extract_dig(&hd::PROGRAM_ID, &fetched).unwrap();
        assert_eq!(dig.heartbeats.len(), 2);
        let rigs_now: Vec<(Address, Rig)> = [a, b].iter().map(|&u| (f.users[u].rig, f.rig(u))).collect();
        let picked = demo::select_stale(&dig.heartbeats, &[f.users[a].rig], &rigs_now).unwrap();
        assert_eq!(picked.len(), 1);
        // Resubmit A's signed heartbeat with a fresh blockhash (naming the live round's PDA).
        let ixs = demo::replay_instructions(&hd::PROGRAM_ID, &f.cranker.pubkey(), f.board.round_id, &picked, 400_000, 1_000).unwrap();
        let t = tx::sign_legacy(&ixs, &f.cranker, f.svm.latest_blockhash()).unwrap();
        let meta = f.send(t).expect("the replay lands");
        let evs = hd::events_from_logs(&hd::PROGRAM_ID, &meta.logs);
        assert_eq!(evs, vec![HdEvent::RigSkipped { rig: f.users[a].rig, round_id: f.board.round_id, error: 7 }]);
        println!("replay: {}", demo::caption(&evs[0]));
        assert_eq!(demo::expected_skip(&f.rig(a), &picked[0].entry, f.board.round_id), 7);
        // A heartbeat that is not stale yet is refused by the tool (a replay must never dig).
        let mut fresh = dig.heartbeats.clone();
        fresh[0].entry.counter = 99;
        assert!(matches!(demo::select_stale(&fresh, &[], &rigs_now), Err(demo::ReplayError::NotStale { .. })));
        // A lease-reuse-only dig has nothing to replay.
        let reuse = demo::LandedDig { rigs: dig.rigs.clone(), heartbeats: vec![] };
        assert!(matches!(demo::select_stale(&reuse.heartbeats, &[], &rigs_now), Err(demo::ReplayError::NothingToReplay)));
    }

    /// Cost per rig per dig with the real program: signatures, priority fee at a
    /// simulate-sized CU limit, and the crank_fee reimbursement.
    #[test]
    fn cost_per_rig_per_dig() {
        for (format, fresh, n) in [(TxFormat::V0, true, 5usize), (TxFormat::V1, true, 11), (TxFormat::V0, false, 12), (TxFormat::Legacy, true, 2)] {
            let mut f = Fork::new();
            let store = Arc::new(HeartbeatStore::new(100));
            let r = f.board.round_id;
            for i in 0..n {
                let u = f.add_user(100 + i as u32);
                if fresh {
                    f.heartbeat(&store, u, 1, 3).unwrap();
                } else {
                    f.set_rig(u, |rig| {
                        rig.state = RigState::Down;
                        rig.lease_from_round = r;
                        rig.lease_to_round = r;
                    });
                }
            }
            let plan = f.plan(&store);
            assert_eq!(plan.digs.len(), n);
            if format == TxFormat::V0 {
                f.make_alt();
            }
            let mut p = f.params(format);
            let rigs: Vec<_> = plan.digs.iter().map(|x| x.dig).collect();
            let (batches, _) = tx::pack(&p, &rigs, &f.alts);
            assert_eq!(batches.len(), 1, "{format:?} fits {n}");
            // Size the CU limit the way the crank does: simulate, +15% + 1,000.
            let sim_tx = tx::sign_batch(&p, &batches[0].rigs, &f.alts, f.svm.latest_blockhash(), &f.cranker).unwrap();
            let sim = f.svm.simulate_transaction(sim_tx).expect("simulates");
            let used = sim.meta.compute_units_consumed;
            p.cu_limit = Some(u32::try_from(used * 115 / 100 + 1_000).unwrap());
            let before = f.balance(&f.cranker.pubkey());
            let t = tx::sign_batch(&p, &batches[0].rigs, &f.alts, f.svm.latest_blockhash(), &f.cranker).unwrap();
            let meta = f.send(t).expect("dig lands");
            let net = i128::from(f.balance(&f.cranker.pubkey())) - i128::from(before);
            let fee = (1 + if fresh { n } else { 0 }) as u64 * 5_000 + tx::priority_fee_lamports(p.cu_limit.unwrap(), p.cu_price_micro_lamports);
            assert_eq!(net, i128::from(n as u64 * CRANK_FEE) - i128::from(fee));
            println!(
                "{format:?} {} x{n}: {} bytes, {} CU ({} per rig), fee {fee} ({} per rig), reimbursed {} ({} per rig), net {net:+} per tx ({:+} per rig)",
                if fresh { "fresh heartbeat" } else { "lease reuse" },
                batches[0].wire_size,
                meta.compute_units_consumed,
                meta.compute_units_consumed / n as u64,
                fee / n as u64,
                n as u64 * CRANK_FEE,
                CRANK_FEE,
                net / n as i128
            );
        }
    }
}
