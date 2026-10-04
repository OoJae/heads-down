//! A JSON-RPC endpoint on loopback for tests that drive a whole [`hd_crank::crank::Crank`].
//!
//! Behind it is a LiteSVM, so transactions are executed by the real programs (the Address
//! Lookup Table program for the crank's maintenance transactions) and every read is the
//! state those left. The stub counts calls by method, can be told to fail the next calls of
//! a method, and can lose a transaction or hide its status, which is what a real node does
//! under load. Included with `#[path]` by the tests that need it.
#![allow(dead_code)]

use std::collections::{BTreeMap, HashMap, HashSet, VecDeque};
use std::net::SocketAddr;
use std::path::PathBuf;
use std::sync::atomic::{AtomicI64, Ordering};
use std::sync::{Arc, Mutex, MutexGuard};
use std::time::{Duration, Instant};

use axum::extract::State;
use axum::routing::post;
use axum::{Json, Router};
use base64::Engine;
use hd_crank::alt::{self, LookupTable};
use hd_crank::breaker::Breaker;
use hd_crank::chain::ChainView;
use hd_crank::config::Config;
use hd_crank::crank::{Crank, CrankWiring};
use hd_crank::hd::Rig;
use hd_crank::heartbeat::HeartbeatStore;
use hd_crank::metrics::Metrics;
use hd_crank::ore::{self, Board, OreConfig, Treasury};
use hd_crank::rpc::RpcClient;
use hd_crank::sender::Submitter;
use hd_crank::signal::SignalHub;
use litesvm::LiteSVM;
use serde_json::{json, Value};
use solana_account::Account;
use solana_address::Address;
use solana_clock::Clock;
use solana_keypair::Keypair;
use solana_signer::Signer;
use solana_slot_hashes::SlotHashes;
use solana_transaction::versioned::VersionedTransaction;
use tokio::sync::watch;

/// What the stub does with a `sendTransaction`.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum SendMode {
    /// Execute it; its status is reported.
    Execute,
    /// Accept it and lose it: it never lands.
    Drop,
    /// Execute it, but never report its status (the node that lands it is not the one asked).
    LandUnseen,
    /// Answer the call with a JSON-RPC error.
    Reject,
}

/// The stub's state. Tests reach it through [`Stub::lock`].
pub struct Inner {
    /// The chain.
    pub svm: LiteSVM,
    /// The newest slot in SlotHashes.
    finalized: u64,
    /// What `getBlockHeight` answers next.
    pub height: u64,
    /// Added to `height` after every `getBlockHeight` (time passing between two polls).
    pub height_step: u64,
    /// Calls received, by method (failed ones included).
    pub calls: BTreeMap<String, u64>,
    /// Fail the next `n` calls of a method with a JSON-RPC error.
    pub failing: HashMap<String, u32>,
    /// What happens to a `sendTransaction`.
    pub send_mode: SendMode,
    /// What happens to the next new transactions, one entry each, before `send_mode` applies.
    pub send_script: VecDeque<SendMode>,
    /// Statuses `getSignatureStatuses` reports.
    pub statuses: HashMap<String, Value>,
    /// Every distinct transaction that carried a `CreateLookupTable`: `(signature, table)`.
    pub creates: Vec<(String, Address)>,
    /// Every distinct transaction that carried an `ExtendLookupTable`.
    pub extends: Vec<String>,
    /// Why the chain refused a transaction outright (it never landed, with or without error).
    pub refused: Vec<String>,
    /// Signatures already seen (a rebroadcast is not a new transaction).
    seen: HashSet<String>,
    /// While set, account reads are answered from this older state, as a node that lags
    /// would. A read that asks for a newer `minContextSlot` gets the node's error instead.
    pub lagging: Option<Box<LiteSVM>>,
    /// The last slot the lagging node has processed.
    lag_slot: u64,
    /// Account reads the lagging state still answers before the node has caught up.
    pub lag_reads_left: u32,
    /// Make the node lag for this many account reads after the next executed transaction.
    pub lag_after_next_tx: u32,
    /// The largest `minContextSlot` a read carried.
    pub min_context_slot_seen: u64,
    /// The node drops `minContextSlot`: while it lags it answers with older state, and only
    /// the slot in the answer's context says so.
    pub ignores_min_context_slot: bool,
    /// Runs when a `sendTransaction` arrives, before it is handled.
    pub on_send: Option<Box<dyn FnMut() + Send>>,
}

impl Inner {
    /// The current slot.
    pub fn slot(&self) -> u64 {
        self.svm.get_sysvar::<Clock>().slot
    }

    /// The chain moves on by one slot: a new blockhash, and the slot just left is recorded in
    /// SlotHashes (what `CreateLookupTable` derives a table's address from) and becomes the
    /// slot `getSlot` reports as finalized.
    pub fn next_slot(&mut self) {
        let slot = self.slot();
        let mut hashes = self.svm.get_sysvar::<SlotHashes>();
        hashes.add(slot, Default::default());
        self.svm.set_sysvar(&hashes);
        self.finalized = slot;
        self.svm.warp_to_slot(slot + 1);
        self.svm.expire_blockhash();
    }

    /// Calls of `method` so far.
    pub fn count(&self, method: &str) -> u64 {
        self.calls.get(method).copied().unwrap_or(0)
    }

    /// All calls so far.
    pub fn total(&self) -> u64 {
        self.calls.values().sum()
    }

    /// Helius' price for what was called: `getProgramAccounts` 10 credits, anything else 1.
    pub fn credits(&self) -> u64 {
        self.calls.iter().map(|(m, n)| n * if m == "getProgramAccounts" { 10 } else { 1 }).sum()
    }

    /// Fail the next `n` calls of `method`.
    pub fn fail(&mut self, method: &str, n: u32) {
        self.failing.insert(method.to_string(), n);
    }

    /// Put an account on chain.
    pub fn set(&mut self, address: Address, owner: Address, lamports: u64, data: Vec<u8>) {
        self.svm.set_account(address, Account { lamports, data, owner, executable: false, rent_epoch: u64::MAX }).unwrap();
    }

    /// Every lookup table on chain whose authority is `authority`.
    pub fn tables_of(&self, authority: &Address) -> Vec<LookupTable> {
        self.svm
            .get_program_accounts(&alt::ALT_PROGRAM_ID)
            .into_iter()
            .filter_map(|(k, a)| LookupTable::decode(k, &a.owner, &a.data))
            .filter(|t| t.authority == Some(*authority))
            .collect()
    }

    fn reads(&self) -> &LiteSVM {
        self.lagging.as_deref().unwrap_or(&self.svm)
    }

    fn account_json(a: Option<Account>) -> Value {
        match a {
            // A closed account reads as missing.
            Some(a) if a.lamports > 0 || !a.data.is_empty() => json!({
                "lamports": a.lamports,
                "owner": a.owner.to_string(),
                "data": [base64::engine::general_purpose::STANDARD.encode(&a.data), "base64"],
                "executable": a.executable,
                "rentEpoch": 0,
                "space": a.data.len(),
            }),
            _ => Value::Null,
        }
    }

    /// One account read by a node that may lag: it catches up after `lag_reads_left` reads,
    /// and until then a read that asks for a `minContextSlot` it has not reached gets the
    /// node's error instead of older state (unless the node ignores the parameter). Returns
    /// the slot the answer is as of: what a node puts in the answer's context.
    fn account_read(&mut self, cfg: &Value) -> Result<u64, (i64, String)> {
        if self.lagging.is_some() {
            if self.lag_reads_left == 0 {
                self.lagging = None;
            } else {
                self.lag_reads_left -= 1;
            }
        }
        let at = if self.lagging.is_some() { self.lag_slot } else { self.slot() };
        if let Some(min) = cfg["minContextSlot"].as_u64() {
            self.min_context_slot_seen = self.min_context_slot_seen.max(min);
            if min > at && !self.ignores_min_context_slot {
                return Err((-32016, "Minimum context slot has not been reached".into()));
            }
        }
        Ok(at)
    }

    fn send(&mut self, params: &Value) -> Result<Value, (i64, String)> {
        if let Some(mut hook) = self.on_send.take() {
            hook();
            self.on_send = Some(hook);
        }
        let wire = base64::engine::general_purpose::STANDARD
            .decode(params[0].as_str().unwrap_or(""))
            .map_err(|_| (-32602, "not base64".to_string()))?;
        let tx: VersionedTransaction = wincode::deserialize(&wire).map_err(|e| (-32602, format!("not a transaction: {e}")))?;
        let sig = tx.signatures[0].to_string();
        if self.seen.contains(&sig) {
            return Ok(json!(sig)); // a rebroadcast of bytes already handled
        }
        let mode = self.send_script.pop_front().unwrap_or(self.send_mode);
        self.seen.insert(sig.clone());
        let keys = tx.message.static_account_keys().to_vec();
        for ix in tx.message.instructions() {
            if keys.get(usize::from(ix.program_id_index)) != Some(&alt::ALT_PROGRAM_ID) {
                continue;
            }
            match ix.data.get(..4) {
                Some([0, 0, 0, 0]) => self.creates.push((sig.clone(), keys[usize::from(ix.accounts[0])])),
                Some([2, 0, 0, 0]) => self.extends.push(sig.clone()),
                _ => {}
            }
        }
        // Whatever becomes of the transaction, the chain moves on.
        match mode {
            SendMode::Reject => {
                self.next_slot();
                return Err((-32005, "node is unhealthy".into()));
            }
            SendMode::Drop => {
                self.next_slot();
                return Ok(json!(sig));
            }
            SendMode::Execute | SendMode::LandUnseen => {}
        }
        if self.lag_after_next_tx > 0 {
            // The state before this transaction is the state after the slot before it.
            self.lagging = Some(Box::new(self.svm.clone()));
            self.lag_slot = self.slot().saturating_sub(1);
            self.lag_reads_left = std::mem::take(&mut self.lag_after_next_tx);
        }
        let slot = self.slot();
        let status = match self.svm.send_transaction(tx) {
            Ok(_) => Some(Value::Null),
            // An instruction that failed is a transaction that landed with an error. Anything
            // else (no funds for the fee, an unknown blockhash) never lands at all.
            Err(e) if format!("{:?}", e.err).starts_with("InstructionError") => Some(json!(format!("{:?}", e.err))),
            Err(e) => {
                self.refused.push(format!("{:?}", e.err));
                None
            }
        };
        if let (Some(err), SendMode::Execute) = (status, mode) {
            self.statuses.insert(sig.clone(), json!({ "slot": slot, "confirmations": null, "err": err, "confirmationStatus": "confirmed" }));
        }
        self.next_slot();
        Ok(json!(sig))
    }

    fn call(&mut self, method: &str, params: &Value) -> Result<Value, (i64, String)> {
        *self.calls.entry(method.to_string()).or_insert(0) += 1;
        if let Some(n) = self.failing.get_mut(method).filter(|n| **n > 0) {
            *n -= 1;
            return Err((-32000, format!("stub: {method} fails")));
        }
        let address = |v: &Value| v.as_str().and_then(|s| s.parse::<Address>().ok()).ok_or((-32602, "not an address".to_string()));
        let context = json!({ "slot": self.slot() });
        Ok(match method {
            "getSlot" => {
                if params[0]["commitment"] == "finalized" {
                    json!(self.finalized)
                } else {
                    json!(self.slot())
                }
            }
            "getBlockHeight" => {
                self.height += self.height_step;
                json!(self.height)
            }
            "getEpochInfo" => {
                self.height += self.height_step;
                json!({ "absoluteSlot": self.slot(), "blockHeight": self.height, "epoch": 0, "slotIndex": self.slot(), "slotsInEpoch": 432_000 })
            }
            "getLatestBlockhash" => json!({
                "context": context,
                "value": { "blockhash": self.svm.latest_blockhash().to_string(), "lastValidBlockHeight": self.height + 150 },
            }),
            "getBalance" => json!({ "context": context, "value": self.reads().get_balance(&address(&params[0])?).unwrap_or(0) }),
            "getMinimumBalanceForRentExemption" => {
                json!(self.svm.minimum_balance_for_rent_exemption(params[0].as_u64().unwrap_or(0) as usize))
            }
            "getAccountInfo" => {
                let at = self.account_read(&params[1])?;
                json!({ "context": { "slot": at }, "value": Self::account_json(self.reads().get_account(&address(&params[0])?)) })
            }
            "getMultipleAccounts" => {
                let at = self.account_read(&params[1])?;
                let mut out = Vec::new();
                for k in params[0].as_array().into_iter().flatten() {
                    out.push(Self::account_json(self.reads().get_account(&address(k)?)));
                }
                json!({ "context": { "slot": at }, "value": out })
            }
            "getProgramAccounts" => {
                let program = address(&params[0])?;
                let matches = |data: &[u8]| {
                    params[1]["filters"].as_array().into_iter().flatten().all(|f| {
                        if let Some(n) = f["dataSize"].as_u64() {
                            return data.len() as u64 == n;
                        }
                        let offset = f["memcmp"]["offset"].as_u64().unwrap_or(0) as usize;
                        let bytes = bs58::decode(f["memcmp"]["bytes"].as_str().unwrap_or("")).into_vec().unwrap_or_default();
                        data.get(offset..offset + bytes.len()) == Some(&bytes[..])
                    })
                };
                let list: Vec<Value> = self
                    .reads()
                    .get_program_accounts(&program)
                    .into_iter()
                    .filter(|(_, a)| matches(&a.data))
                    .map(|(k, a)| json!({ "pubkey": k.to_string(), "account": Self::account_json(Some(a)) }))
                    .collect();
                json!(list)
            }
            "sendTransaction" => self.send(params)?,
            "getSignatureStatuses" => {
                let list: Vec<Value> = params[0]
                    .as_array()
                    .into_iter()
                    .flatten()
                    .map(|s| self.statuses.get(s.as_str().unwrap_or("")).cloned().unwrap_or(Value::Null))
                    .collect();
                json!({ "context": context, "value": list })
            }
            "simulateTransaction" => json!({ "context": context, "value": { "err": null, "logs": [], "unitsConsumed": 0 } }),
            "getRecentPrioritizationFees" => json!([]),
            "getTransaction" => Value::Null,
            other => return Err((-32601, format!("stub: no {other}"))),
        })
    }
}

/// The running stub.
#[derive(Clone)]
pub struct Stub {
    /// `http://127.0.0.1:<port>`.
    pub url: String,
    inner: Arc<Mutex<Inner>>,
}

impl Stub {
    /// Start on a free loopback port, with an empty chain at slot 100.
    pub async fn start() -> Stub {
        let mut svm = LiteSVM::new();
        svm.warp_to_slot(100);
        let mut inner = Inner {
            svm,
            finalized: 0,
            height: 1_000,
            height_step: 0,
            calls: BTreeMap::new(),
            failing: HashMap::new(),
            send_mode: SendMode::Execute,
            send_script: VecDeque::new(),
            statuses: HashMap::new(),
            creates: Vec::new(),
            extends: Vec::new(),
            refused: Vec::new(),
            seen: HashSet::new(),
            lagging: None,
            lag_slot: 0,
            lag_reads_left: 0,
            lag_after_next_tx: 0,
            min_context_slot_seen: 0,
            ignores_min_context_slot: false,
            on_send: None,
        };
        inner.next_slot();
        let inner = Arc::new(Mutex::new(inner));
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr: SocketAddr = listener.local_addr().unwrap();
        let router = Router::new().route("/", post(handle)).with_state(inner.clone());
        tokio::spawn(async move {
            let _ = axum::serve(listener, router).await;
        });
        Stub { url: format!("http://{addr}"), inner }
    }

    /// The state (never held across an await).
    pub fn lock(&self) -> MutexGuard<'_, Inner> {
        match self.inner.lock() {
            Ok(g) => g,
            Err(p) => p.into_inner(),
        }
    }
}

/// What a test needs to run cranks against the stub: a fee-payer key, a state directory
/// that outlives each crank (a restart keeps it), and a clock the test moves.
pub struct Bench {
    /// The RPC.
    pub stub: Stub,
    /// Holds the key file and the state directory.
    pub dir: tempfile::TempDir,
    /// The crank's fee payer.
    pub cranker: Address,
    key_path: PathBuf,
    /// Unix seconds as the crank's lookup-table backoff reads them.
    pub clock: Arc<AtomicI64>,
}

/// One crank process: everything in memory is new, the state directory is the bench's.
pub struct Process {
    /// The crank.
    pub crank: Arc<Crank>,
    /// Its metrics.
    pub metrics: Arc<Metrics>,
    /// Its heartbeat store (what the intake would fill).
    pub store: Arc<HeartbeatStore>,
    /// Its chain view (what the watcher would publish).
    pub chain: watch::Sender<ChainView>,
    /// Every rig handed to the intake's rig cache.
    pub seeded: Arc<Mutex<Vec<Address>>>,
}

impl Bench {
    /// A stub, a fresh unfunded fee payer and an empty state directory. With `HD_TEST_LOG`
    /// set, the crank's log lines go to the test's output.
    pub async fn new() -> Bench {
        if std::env::var_os("HD_TEST_LOG").is_some() {
            let _ = tracing_subscriber::fmt().with_env_filter("hd_crank=debug").with_test_writer().try_init();
        }
        let stub = Stub::start().await;
        let dir = tempfile::tempdir().unwrap();
        let kp = Keypair::new();
        let key_path = dir.path().join("crank.json");
        let bytes: Vec<String> = kp.to_bytes().iter().map(u8::to_string).collect();
        std::fs::write(&key_path, format!("[{}]", bytes.join(","))).unwrap();
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            std::fs::set_permissions(&key_path, std::fs::Permissions::from_mode(0o600)).unwrap();
        }
        Bench { stub, dir, cranker: kp.pubkey(), key_path, clock: Arc::new(AtomicI64::new(1_790_000_000)) }
    }

    /// Where the cranks of this bench keep their state.
    pub fn state_dir(&self) -> PathBuf {
        self.dir.path().join("state")
    }

    /// The lookup-table state file.
    pub fn state_file(&self) -> PathBuf {
        self.state_dir().join("lookup_tables.json")
    }

    /// The defaults, pointed at the stub and at the bench's state directory.
    pub fn config(&self) -> Config {
        let toml = format!("rpc_url = \"{}\"\nstate_dir = \"{}\"\n[dig]\nore_programdata_slot = 0\n", self.stub.url, self.state_dir().display());
        Config::from_toml(&toml).unwrap().finalize(&|_| None).unwrap()
    }

    /// Start a crank (nothing runs by itself: the test calls its passes).
    pub fn start(&self, cfg: Config) -> Process {
        let metrics = Arc::new(Metrics::default());
        let breaker = Arc::new(Breaker::new(metrics.clone()));
        let rpc = RpcClient::new(cfg.rpc_url.clone(), cfg.commitment.clone(), Duration::from_secs(5)).unwrap();
        let store = Arc::new(HeartbeatStore::new(1_000));
        let signals = Arc::new(SignalHub::new(cfg.signals.hub()));
        let (chain, chain_rx) = watch::channel(ChainView::default());
        let seeded = Arc::new(Mutex::new(Vec::new()));
        let (seed, clock) = (seeded.clone(), self.clock.clone());
        let crank = Crank::new(
            cfg,
            rpc.clone(),
            Submitter::rpc(rpc),
            hd_crank::keys::load_keypair(&self.key_path).unwrap(),
            metrics.clone(),
            breaker,
            store.clone(),
            signals,
            chain_rx,
            Box::new(move |a: Address, _: Rig| seed.lock().unwrap().push(a)),
            CrankWiring { clock: Some(Arc::new(move || clock.load(Ordering::SeqCst))), ..CrankWiring::default() },
        );
        Process { crank, metrics, store, chain, seeded }
    }

    /// Move the crank's clock forward, and the chain with it (about 2.5 blocks a second).
    pub fn pass(&self, secs: i64) {
        self.clock.fetch_add(secs, Ordering::SeqCst);
        let mut s = self.stub.lock();
        s.height += (secs as u64) * 5 / 2;
        s.next_slot();
    }

    /// Set the fee payer's balance on chain.
    pub fn fund(&self, lamports: u64) {
        self.stub.lock().set(self.cranker, ore::SYSTEM_PROGRAM_ID, lamports, Vec::new());
    }

    /// The fee payer's balance on chain.
    pub fn balance(&self) -> u64 {
        self.stub.lock().svm.get_balance(&self.cranker).unwrap_or(0)
    }
}

/// The chain as the watcher would show it in `round`, `slots_left` slots before its end.
pub fn view(round: u64, slots_left: u64) -> ChainView {
    let end_slot = 1_000 + round * 240;
    ChainView {
        slot: end_slot - slots_left,
        board: Some(Board { round_id: round, start_slot: end_slot - 240, end_slot, production_cost_ema: 600_000_000 }),
        treasury: Some(Treasury { motherlode: 0 }),
        round: None,
        ore_config: Some(OreConfig { intermission_slots: 48, round_slots: 240 }),
        last_account_update: Some(Instant::now()),
        last_slot_update: Some(Instant::now()),
        cluster_time: Some((1_790_000_000, Instant::now())),
    }
}

async fn handle(State(inner): State<Arc<Mutex<Inner>>>, Json(req): Json<Value>) -> Json<Value> {
    let mut g = match inner.lock() {
        Ok(g) => g,
        Err(p) => p.into_inner(),
    };
    let method = req["method"].as_str().unwrap_or("?").to_string();
    Json(match g.call(&method, &req["params"]) {
        Ok(result) => json!({ "jsonrpc": "2.0", "id": req["id"], "result": result }),
        Err((code, message)) => json!({ "jsonrpc": "2.0", "id": req["id"], "error": { "code": code, "message": message } }),
    })
}
