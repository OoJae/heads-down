//! Fork suite: the crank's verifier → planner → packer → signed transaction, executed by
//! LiteSVM 0.17 against the **live mainnet ORE binary** and real Board / Config / Treasury /
//! Var / Round accounts (`spikes/ore-executor/fetch-fixtures.sh`), with the mock
//! `heads_down` program from `test-fixtures/mock-heads-down` (or the real program with
//! `--features real-program` / `HD_PROGRAM_SO`).
//!
//! ```sh
//! spikes/ore-executor/fetch-fixtures.sh                     # or ORE_FIXTURES_DIR=...
//! cargo build-sbf --manifest-path crank/test-fixtures/mock-heads-down/Cargo.toml
//! cargo test --features fork --test fork -- --nocapture --test-threads=1
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
            panic!("missing {} — cargo build-sbf --manifest-path crank/test-fixtures/mock-heads-down/Cargo.toml", so.display())
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
    /// has a registered, armed Rig.
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
        let rig = Rig {
            bump: hd::rig_pda(&hd::PROGRAM_ID, &authority).1,
            authority,
            p256_pubkey: phone.pubkey(),
            attestation_level: 1,
            tier: 0,
            state: RigState::Armed,
            sgt_mint: Address::default(),
            attestation_expiry_slot: u64::MAX,
            cap_week: LAMPORTS_PER_SOL,
            cap_shift: LAMPORTS_PER_SOL / 10,
            cap_round: 5_000_000,
            cap_max_cost: u64::MAX,
            caps_expiry_ts: NOW + 86_400,
            plan_max_ev_cost: u64::MAX,
            plan_dig_lamports: 1_000_000,
            plan_split_tiles: 15,
            plan_solo_tiles: 0,
            plan_lease_rounds: 3,
            plan_flags: 0,
            plan_window_start_ts: NOW - 3_600,
            plan_window_end_ts: NOW + 3_600,
            shift_id: 1,
            hb_counter: 0,
            lease_from_round: 0,
            lease_to_round: 0,
            gap_count: 0,
            spent_shift: 0,
            spent_week: 0,
            week_start_ts: NOW - 60,
            last_dug_round: 0,
            shift_start_round: self.board.round_id - 10,
            shift_dark_rounds: 0,
            shift_rounds_dug: 0,
            lifetime_dark_rounds: 0,
            lifetime_rounds_dug: 0,
            lifetime_lamports_deployed: 0,
            streak: 0,
            freezes_left: 2,
            last_shift_day: 0,
        };
        self.svm
            .set_account(rig_addr, Account { lamports: 10_000_000, data: rig.encode(), owner: hd::PROGRAM_ID, executable: false, rent_epoch: u64::MAX })
            .unwrap();
        self.users.push(User { _wallet: wallet, phone, rig: rig_addr, accounts });
        self.users.len() - 1
    }

    /// Phone → JSON → crank verifier → store, exactly as the WebSocket intake does.
    fn heartbeat(&self, store: &Arc<HeartbeatStore>, u: usize, counter: u64, lease: u8) -> Result<(), hd_crank::heartbeat::Reject> {
        let user = &self.users[u];
        let rig = self.rig(u);
        let f = HeartbeatFields { counter, shift_id: rig.shift_id, round_id: self.board.round_id, lease_rounds: lease };
        let raw = user.phone.sign_raw(&hd::PROGRAM_ID, &user.rig, &f);
        let sub = HeartbeatSubmission {
            rig: user.rig.to_string(),
            counter,
            shift_id: f.shift_id,
            round_id: f.round_id,
            lease_rounds: lease,
            sig64: base64::engine::general_purpose::STANDARD.encode(raw),
            pubkey: Some(hex::encode(user.phone.pubkey())),
        };
        let src = MapSource(Arc::new(Mutex::new(
            self.users.iter().enumerate().map(|(i, u)| (u.rig, self.rig(i))).collect(),
        )));
        let v = Verifier {
            program_id: hd::PROGRAM_ID,
            rigs: RigCache::new(src, Duration::from_secs(60), Duration::from_secs(30), 100, 100.0),
            store: store.clone(),
        };
        let parsed = ParsedHeartbeat::parse(&sub)?;
        let rt = tokio::runtime::Builder::new_current_thread().enable_all().build().unwrap();
        rt.block_on(v.process(&parsed, Some(self.board.round_id))).map(|_| ())
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
        assert_eq!(mask & ore::distribution_mask(r), 0, "split squares only");
        assert_eq!(lamports, dec.expected_debit, "debit = per_tile * k + fee");
        for (i, delta) in expected_delta.iter_mut().enumerate() {
            if mask & (1 << i) != 0 {
                *delta += dec.per_tile;
            }
        }
    }
    for i in 0..25 {
        assert_eq!(after_round.deployed[i] - before_round.deployed[i], expected_delta[i], "square {i}");
    }
    for (k, u) in [a, b, c].into_iter().enumerate() {
        let dec = plan.digs.iter().find(|x| x.dig.accounts.rig == f.users[u].rig).unwrap();
        assert_eq!(before_auto[k] - f.automation(u).balance, dec.expected_debit);
        let rig = f.rig(u);
        assert_eq!(rig.last_dug_round, r);
        assert_eq!(rig.state, RigState::Down);
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
    // so the rig is skipped with InvalidHeartbeat. The crank never builds this: its planner
    // requires shift_id == rig.shift_id.
    let wrong = HeartbeatFields { counter: 1, shift_id: 99, round_id: f.board.round_id, lease_rounds: 1 };
    let ia = rigs.iter().position(|x| x.accounts.rig == f.users[a].rig).unwrap();
    rigs[ia].heartbeat = Some(f.users[a].phone.verified(&hd::PROGRAM_ID, &f.users[a].rig, wrong));
    let t = tx::sign_batch(&p, &rigs, &[], f.svm.latest_blockhash(), &f.cranker).unwrap();
    let meta = f.send(t).expect("tx lands; only rig A is skipped");
    let evs = hd::events_from_logs(&hd::PROGRAM_ID, &meta.logs);
    assert!(evs.iter().any(|e| matches!(e, HdEvent::RigSkipped { rig, error, .. } if *rig == f.users[a].rig && hd::error_name(*error) == "InvalidHeartbeat")), "{evs:?}");
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
