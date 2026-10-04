//! The crank's lookup-table maintenance against the real Address Lookup Table program
//! (LiteSVM behind a JSON-RPC stub, `tests/common/rpc_stub.rs`).
//!
//! A table locks rent that only comes back by deactivating and closing it by hand. So
//! whatever the RPC does, however often the crank restarts on its state file, and however
//! little its fee payer holds:
//!
//! - at most one create is sent per backoff wait, and none while the fee payer is short;
//! - a table that was created is never lost track of, and never gets a twin.
//!
//! What "the chain" says here is what LiteSVM holds after really executing the crank's
//! transactions; the stub only decides which of them arrive, and what it admits about them.

#[path = "common/rpc_stub.rs"]
mod rpc_stub;

use hd_crank::alt::{self, LookupTable, Retry, TableState};
use hd_crank::crank::ALT_FEE_MARGIN_LAMPORTS;
use rpc_stub::{Bench, Process, SendMode};
use solana_address::Address;

/// The accounts every dig repeats: what a new table is filled with first.
const SHARED: usize = 10;
const SOL: u64 = 1_000_000_000;

/// What the fee payer must hold before a create is sent: the rent of the table with the
/// shared accounts in it, room for the fees of the create and of the extend after it, and
/// what its own account must keep to stay rent-exempt.
fn create_needs(b: &Bench) -> u64 {
    let s = b.stub.lock();
    s.svm.minimum_balance_for_rent_exemption(alt::table_len(SHARED)) + 2 * ALT_FEE_MARGIN_LAMPORTS + s.svm.minimum_balance_for_rent_exemption(0)
}

fn file(b: &Bench) -> TableState {
    TableState::from_json(&std::fs::read_to_string(b.state_file()).expect("the state file exists")).expect("the state file parses")
}

/// The state file once everything is settled: the tables it lists, nothing pending, nothing
/// in doubt and no backoff running.
fn settled_file(b: &Bench) -> Vec<Address> {
    let f = file(b);
    assert_eq!((f.pending.len(), f.settle_height, f.retry), (0, 0, Retry::default()), "{f:?}");
    f.tables
}

fn on_chain(b: &Bench) -> Vec<LookupTable> {
    b.stub.lock().tables_of(&b.cranker)
}

/// The one table the crank owns on chain, filled with the shared accounts and nothing twice.
fn the_table(b: &Bench) -> LookupTable {
    let tables = on_chain(b);
    assert_eq!(tables.len(), 1, "exactly one table on chain");
    let t = tables.into_iter().next().unwrap();
    assert_eq!(t.addresses, alt::shared_addresses(&hd_crank::hd::PROGRAM_ID), "the shared accounts, each once");
    t
}

/// The distinct create and extend transactions the stub has been sent.
fn sent(b: &Bench) -> (usize, usize) {
    let s = b.stub.lock();
    (s.creates.len(), s.extends.len())
}

fn keys(p: &Process) -> Vec<Address> {
    p.crank.lookup_tables().iter().map(|t| t.key).collect()
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn an_empty_fee_payer_never_sends_a_create_and_is_asked_about_once() {
    let b = Bench::new().await;
    let p = b.start(b.config());
    // The first sync asks for the rent (of the table, and of the fee payer's own account)
    // and for the balance, warns once, and sends nothing.
    p.crank.sync_alts().await.unwrap();
    {
        let s = b.stub.lock();
        assert_eq!((s.count("getMinimumBalanceForRentExemption"), s.count("getBalance"), s.total()), (2, 1, 3), "{:?}", s.calls);
    }
    assert_eq!(p.metrics.lookup_tables.get("low_balance"), 1);
    // The running crank syncs on every poll and every round. None of those asks anything.
    for _ in 0..200 {
        p.crank.sync_alts().await.unwrap();
        b.pass(30);
    }
    assert_eq!(b.stub.lock().total(), 3, "nothing is asked while the fee payer is known to be short");
    assert_eq!(p.metrics.lookup_tables.get("low_balance"), 1, "one warning, not one per sync");
    // One lamport short, as the poller's balance reading reports it: still nothing.
    let need = create_needs(&b);
    b.fund(need - 1);
    p.crank.note_fee_payer_balance(need - 1);
    p.crank.sync_alts().await.unwrap();
    assert_eq!(b.stub.lock().total(), 3);
    // A restart looks once more (rent and balance) and sends nothing either.
    let p = b.start(b.config());
    for _ in 0..20 {
        p.crank.sync_alts().await.unwrap();
    }
    assert_eq!(b.stub.lock().total(), 6);
    assert_eq!(b.stub.lock().count("sendTransaction"), 0);
    assert!(on_chain(&b).is_empty() && !b.state_file().exists(), "nothing was created, nothing was written");
    // Funded to the lamport, and the poller sees it: the next sync creates the table and
    // fills it, and no transaction is refused for leaving the fee payer below its own rent.
    b.fund(need);
    p.crank.note_fee_payer_balance(need);
    p.crank.sync_alts().await.unwrap();
    assert_eq!(b.stub.lock().refused, Vec::<String>::new());
    let table = the_table(&b);
    assert_eq!(sent(&b), (1, 1));
    assert_eq!(keys(&p), vec![table.key]);
    assert_eq!(settled_file(&b), vec![table.key]);
    assert!(file(&b).min_slot > 100, "the slot the extend landed in is kept: the next read, also after a restart, is asked of a node that has it");
    assert_eq!((p.metrics.lookup_tables.get("created"), p.metrics.lookup_tables.get("extended")), (1, 1));
    // From then on a sync has nothing to do and asks nothing.
    let calls = b.stub.lock().total();
    for _ in 0..50 {
        p.crank.sync_alts().await.unwrap();
        b.pass(30);
    }
    assert_eq!(b.stub.lock().total(), calls);
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_create_that_does_not_land_is_retried_after_a_growing_wait_also_across_restarts() {
    let b = Bench::new().await;
    b.fund(10 * SOL);
    {
        let mut s = b.stub.lock();
        s.send_mode = SendMode::Drop; // the node takes the transaction and it never lands
        s.height_step = 151; // its blockhash has expired by the first status poll
    }
    let mut p = b.start(b.config());
    let t0 = b.clock.load(std::sync::atomic::Ordering::SeqCst);
    let mut attempts: Vec<i64> = Vec::new();
    let mut quiet_calls = 0;
    // Three hours, a sync every 10 s (the crank syncs every 30 s and at every round), and a
    // restart now and then: the wait is in the state file, not only in memory.
    for step in 0..3 * 360 {
        if step % 97 == 96 {
            p = b.start(b.config());
        }
        let (creates, calls) = {
            let s = b.stub.lock();
            (s.creates.len(), s.total())
        };
        let result = p.crank.sync_alts().await;
        let now = b.clock.load(std::sync::atomic::Ordering::SeqCst) - t0;
        if b.stub.lock().creates.len() > creates {
            assert!(result.is_err(), "a create that did not land is reported");
            attempts.push(now);
        } else {
            assert!(result.is_ok());
            assert_eq!(b.stub.lock().total(), calls, "nothing is asked during the wait (at {now} s)");
            quiet_calls += 1;
        }
        b.pass(10);
    }
    let gaps: Vec<i64> = attempts.windows(2).map(|w| w[1] - w[0]).collect();
    assert_eq!(attempts[0], 0);
    assert_eq!(gaps, vec![60, 120, 240, 480, 960, 1_920, 3_600], "one attempt per wait, and the wait doubles up to an hour");
    assert_eq!(gaps, (1..=7).map(Retry::delay_secs).collect::<Vec<_>>());
    assert!(quiet_calls > 1_000);
    assert_eq!(b.stub.lock().count("sendTransaction"), attempts.len() as u64, "one transaction per attempt");
    assert!(on_chain(&b).is_empty());
    assert_eq!(p.metrics.lookup_tables.get("created"), 0);
    let saved = file(&b);
    assert_eq!(saved.retry.failures, 8, "the count survives the restarts");
    assert!(saved.tables.is_empty());
    // The node recovers. The next attempt comes when the wait is over, not before, and lands.
    b.stub.lock().send_mode = SendMode::Execute;
    b.pass(3_600 - 10 * (3 * 360 - attempts[7] / 10) - 10);
    p.crank.sync_alts().await.unwrap();
    assert_eq!(b.stub.lock().creates.len(), 8, "not due yet");
    b.pass(10);
    p.crank.sync_alts().await.unwrap();
    let table = the_table(&b);
    assert_eq!(b.stub.lock().creates.len(), 9);
    assert_eq!(settled_file(&b), vec![table.key], "the wait is over and no create is in doubt");
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_create_is_not_repeated_while_the_last_one_may_still_land() {
    let b = Bench::new().await;
    b.fund(10 * SOL);
    {
        let mut s = b.stub.lock();
        s.send_mode = SendMode::Drop;
        s.height_step = 151;
    }
    let p = b.start(b.config());
    assert!(p.crank.sync_alts().await.is_err());
    let pending = file(&b).pending;
    assert_eq!(pending, vec![b.stub.lock().creates[0].1], "written down before it was sent");
    // The wait is over, but the chain is not far past the create's last valid block yet: a
    // node that lags could still be hiding a table that landed. No second create.
    b.stub.lock().height_step = 0;
    b.clock.fetch_add(60, std::sync::atomic::Ordering::SeqCst);
    for _ in 0..5 {
        let err = p.crank.sync_alts().await.unwrap_err().to_string();
        assert!(err.contains("could land until block height"), "{err}");
    }
    assert_eq!(b.stub.lock().creates.len(), 1);
    assert_eq!(file(&b).pending, pending);
    assert!(file(&b).settle_height > 0);
    // Well past it: the create is given up, and a new one is sent.
    b.stub.lock().height += alt::SETTLE_MARGIN_BLOCKS + 1;
    b.stub.lock().send_mode = SendMode::Execute;
    p.crank.sync_alts().await.unwrap();
    assert_eq!(b.stub.lock().creates.len(), 2);
    assert_ne!(the_table(&b).key, pending[0], "a new address: the old one never came to exist");
    assert!(file(&b).pending.is_empty());
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_failed_read_at_start_never_leads_to_a_second_table() {
    let b = Bench::new().await;
    b.fund(SOL);
    let p = b.start(b.config());
    p.crank.sync_alts().await.unwrap();
    let table = the_table(&b);
    drop(p);
    // The restarted crank cannot read its tables: the node is down, or the credits are gone.
    b.stub.lock().fail("getMultipleAccounts", 5);
    let p = b.start(b.config());
    for _ in 0..5 {
        assert!(p.crank.sync_alts().await.is_err(), "the failed read is reported, and retried at the next sync");
        assert!(keys(&p).is_empty());
        b.pass(30);
    }
    assert_eq!(b.stub.lock().creates.len(), 1, "no create while the tables are unread");
    assert_eq!(b.stub.lock().count("sendTransaction"), 2, "the create and the extend of the first run");
    // The read works: the table is found again. Nothing is created.
    p.crank.sync_alts().await.unwrap();
    assert_eq!(keys(&p), vec![table.key]);
    assert_eq!(b.stub.lock().creates.len(), 1);
    assert_eq!(the_table(&b), table);
    assert_eq!(file(&b).tables, vec![table.key], "and it was never dropped from the state file");
    // The file an older crank wrote (tables only) is read the same way.
    std::fs::write(b.state_file(), format!(r#"{{"tables":["{}"]}}"#, table.key)).unwrap();
    let p = b.start(b.config());
    p.crank.sync_alts().await.unwrap();
    assert_eq!(keys(&p), vec![table.key]);
    assert_eq!(b.stub.lock().creates.len(), 1);
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_table_pinned_in_configuration_needs_no_state_file() {
    let b = Bench::new().await;
    b.fund(SOL);
    let p = b.start(b.config());
    p.crank.sync_alts().await.unwrap();
    let table = the_table(&b);
    drop(p);
    // The state directory is lost (a redeploy without the volume). With the table named in
    // the configuration and auto_create off, the crank finds it and creates nothing.
    std::fs::remove_dir_all(b.state_dir()).unwrap();
    let mut cfg = b.config();
    cfg.alt.tables = vec![table.key.to_string()];
    cfg.alt.auto_create = false;
    let p = b.start(cfg);
    p.crank.sync_alts().await.unwrap();
    assert_eq!(keys(&p), vec![table.key]);
    assert_eq!(sent(&b), (1, 1));
    assert_eq!(on_chain(&b).len(), 1);
    drop(p);
    // Not pinned, and the state file gone: this is the one thing the crank cannot know. It
    // creates a second table, and the first is still on chain, with its rent. The state
    // file is what stands between a restart and that.
    assert!(!b.state_file().exists(), "the pinned crank had nothing to write");
    let p = b.start(b.config());
    p.crank.sync_alts().await.unwrap();
    assert_eq!(sent(&b).0, 2);
    assert_eq!(on_chain(&b).len(), 2);
    assert_ne!(keys(&p), vec![table.key]);
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_create_that_landed_without_being_seen_is_found_again() {
    for restart in [false, true] {
        let b = Bench::new().await;
        b.fund(SOL);
        {
            let mut s = b.stub.lock();
            s.send_mode = SendMode::LandUnseen; // it lands, and no status is ever reported
            s.height_step = 151;
        }
        let mut p = b.start(b.config());
        let err = p.crank.sync_alts().await.unwrap_err().to_string();
        assert!(err.contains("Expired"), "{err}");
        // It did land, and its address was written down before it was sent.
        let landed = on_chain(&b);
        assert_eq!(landed.len(), 1);
        assert_eq!(file(&b).pending, vec![landed[0].key]);
        if restart {
            p = b.start(b.config());
        }
        // During the wait nothing happens. After it the table is found, used and filled.
        p.crank.sync_alts().await.unwrap();
        assert_eq!(b.stub.lock().count("sendTransaction"), 1);
        b.stub.lock().send_mode = SendMode::Execute;
        b.pass(60);
        p.crank.sync_alts().await.unwrap();
        let table = the_table(&b);
        assert_eq!(table.key, landed[0].key);
        assert_eq!(b.stub.lock().creates.len(), 1, "no second create (restart: {restart})");
        assert_eq!(keys(&p), vec![table.key]);
        assert_eq!(settled_file(&b), vec![table.key]);
    }
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn an_extend_whose_outcome_is_unknown_is_never_sent_twice() {
    // Lost: it is sent again after the wait. Landed unseen: it is not, and the table holds
    // every address once (a second extend would pay their rent twice).
    for (second, extends) in [(SendMode::Drop, 2), (SendMode::LandUnseen, 1)] {
        let b = Bench::new().await;
        b.fund(SOL);
        {
            let mut s = b.stub.lock();
            s.send_script = [SendMode::Execute, second].into();
            s.height_step = 151;
        }
        let p = b.start(b.config());
        p.crank.sync_alts().await.unwrap();
        assert_eq!(sent(&b), (1, 1));
        assert_eq!(p.metrics.lookup_tables.get("extend_failed"), 1);
        // The wait: nothing is asked. A restart in the middle changes nothing.
        let calls = b.stub.lock().total();
        for _ in 0..5 {
            p.crank.sync_alts().await.unwrap();
            b.pass(10);
        }
        let p = b.start(b.config());
        p.crank.sync_alts().await.unwrap();
        assert_eq!(b.stub.lock().total(), calls);
        // After it the tables are read again before anything is sent.
        b.pass(10);
        p.crank.sync_alts().await.unwrap();
        the_table(&b);
        assert_eq!(sent(&b), (1, extends), "{second:?}");
        assert_eq!(p.crank.lookup_tables()[0].addresses.len(), SHARED);
        assert_eq!(settled_file(&b).len(), 1, "{second:?}: the backoff ends once everything is in place");
    }
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_node_that_lags_cannot_make_the_crank_forget_or_duplicate_a_table() {
    let b = Bench::new().await;
    b.fund(SOL);
    // After the create the node answers account reads from the state before it, for a while.
    b.stub.lock().lag_after_next_tx = 6;
    let p = b.start(b.config());
    // The create and the extend land. The read that follows names the slot of the extend, so
    // the lagging node answers with an error, not with a table-less chain.
    let err = p.crank.sync_alts().await.unwrap_err().to_string();
    assert!(err.contains("Minimum context slot"), "{err}");
    assert!(b.stub.lock().min_context_slot_seen > 100);
    assert_eq!(keys(&p).len(), 1, "the table is held in memory from the moment its create landed");
    // Until the node has caught up the crank keeps asking, and sends nothing.
    let mut failed = 1;
    while p.crank.sync_alts().await.is_err() {
        failed += 1;
        assert!(failed < 20);
    }
    assert!(failed > 1);
    let table = the_table(&b);
    assert_eq!(sent(&b), (1, 1));
    assert_eq!(p.crank.lookup_tables(), vec![table]);
}

#[cfg(unix)]
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn no_table_is_created_while_the_state_file_cannot_be_written() {
    use std::os::unix::fs::PermissionsExt;
    let b = Bench::new().await;
    b.fund(SOL);
    b.stub.lock().height_step = 151;
    std::fs::create_dir_all(b.state_dir()).unwrap();
    std::fs::set_permissions(b.state_dir(), std::fs::Permissions::from_mode(0o500)).unwrap();
    if std::fs::write(b.state_dir().join("probe"), b"x").is_ok() {
        eprintln!("skipped: this user can write to a read-only directory (root?)");
        return;
    }
    let p = b.start(b.config());
    let err = p.crank.sync_alts().await.unwrap_err().to_string();
    assert!(err.contains("cannot be written") && err.contains("no lookup table is created"), "{err}");
    assert_eq!(b.stub.lock().count("sendTransaction"), 0, "a table whose address cannot be saved is not created");
    assert_eq!(p.metrics.lookup_tables.get("state_file"), 1);
    // It is an attempt that failed: the next one waits.
    let calls = b.stub.lock().total();
    for _ in 0..5 {
        p.crank.sync_alts().await.unwrap();
        b.pass(10);
    }
    assert_eq!(b.stub.lock().total(), calls);
    assert_eq!(p.metrics.lookup_tables.get("state_file"), 1, "one error line, not one per attempt");
    // Writable again: the table is created at the next attempt.
    std::fs::set_permissions(b.state_dir(), std::fs::Permissions::from_mode(0o700)).unwrap();
    b.pass(10);
    p.crank.sync_alts().await.unwrap();
    let table = the_table(&b);
    assert_eq!(file(&b).tables, vec![table.key]);
}

#[cfg(unix)]
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_table_whose_address_cannot_be_saved_after_the_create_stays_in_memory_and_gets_no_twin() {
    use std::os::unix::fs::PermissionsExt;
    let b = Bench::new().await;
    b.fund(SOL);
    std::fs::create_dir_all(b.state_dir()).unwrap();
    std::fs::set_permissions(b.state_dir(), std::fs::Permissions::from_mode(0o500)).unwrap();
    let writable = std::fs::write(b.state_dir().join("probe"), b"x").is_ok();
    std::fs::set_permissions(b.state_dir(), std::fs::Permissions::from_mode(0o700)).unwrap();
    if writable {
        eprintln!("skipped: this user can write to a read-only directory (root?)");
        return;
    }
    // The directory turns read-only between the write that precedes the create and the write
    // that follows it (the volume fills up, say).
    let dir = b.state_dir();
    b.stub.lock().on_send = Some(Box::new(move || std::fs::set_permissions(&dir, std::fs::Permissions::from_mode(0o500)).unwrap()));
    let p = b.start(b.config());
    p.crank.sync_alts().await.unwrap();
    let table = the_table(&b);
    assert_eq!(p.metrics.lookup_tables.get("state_file"), 1, "an error line that names the table");
    assert_eq!(p.crank.lookup_tables(), vec![table.clone()], "the table is in use from memory");
    // However long it runs, it creates no other.
    for _ in 0..50 {
        p.crank.sync_alts().await.unwrap();
        b.pass(600);
    }
    assert_eq!(b.stub.lock().creates.len(), 1);
    // The write before the create left the address in the file: a restart finds the table,
    // even though the file still cannot be written.
    assert_eq!(file(&b).pending, vec![table.key]);
    let p = b.start(b.config());
    p.crank.sync_alts().await.unwrap();
    assert_eq!(keys(&p), vec![table.key]);
    assert_eq!(b.stub.lock().creates.len(), 1);
    assert_eq!(on_chain(&b).len(), 1);
    std::fs::set_permissions(b.state_dir(), std::fs::Permissions::from_mode(0o700)).unwrap();
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_state_file_that_cannot_be_understood_stops_creates_until_it_is_fixed() {
    let b = Bench::new().await;
    b.fund(SOL);
    std::fs::create_dir_all(b.state_dir()).unwrap();
    // Half a file, as a crash in the middle of a write would have left it before.
    std::fs::write(b.state_file(), r#"{"tables":["9huQHBaorG4iq3SyPrswvu"#).unwrap();
    let p = b.start(b.config());
    for _ in 0..3 {
        let err = p.crank.sync_alts().await.unwrap_err().to_string();
        assert!(err.contains("lookup_tables.json") && err.contains("fix or remove it"), "{err}");
    }
    assert_eq!(b.stub.lock().total(), 0, "a file read as empty would have meant a new table");
    // The operator removes it (having checked that no table is lost): the crank goes on.
    std::fs::remove_file(b.state_file()).unwrap();
    p.crank.sync_alts().await.unwrap();
    the_table(&b);
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn max_tables_counts_the_first_table_too() {
    let b = Bench::new().await;
    b.fund(SOL);
    let mut cfg = b.config();
    cfg.alt.max_tables = 0;
    let p = b.start(cfg);
    for _ in 0..5 {
        p.crank.sync_alts().await.unwrap();
    }
    assert_eq!(b.stub.lock().total(), 0, "max_tables = 0: no table, and nothing asked");
    // auto_create = false: the same.
    let mut cfg = b.config();
    cfg.alt.auto_create = false;
    let p = b.start(cfg);
    p.crank.sync_alts().await.unwrap();
    assert_eq!(b.stub.lock().count("sendTransaction"), 0);
    // One table allowed: one is created, and the limit then holds.
    let mut cfg = b.config();
    cfg.alt.max_tables = 1;
    let p = b.start(cfg);
    p.crank.sync_alts().await.unwrap();
    the_table(&b);
    for _ in 0..5 {
        p.crank.sync_alts().await.unwrap();
        b.pass(3_600);
    }
    assert_eq!(b.stub.lock().creates.len(), 1);
}

/// xorshift64*: the same faults in the same order on every run.
struct Rng(u64);

impl Rng {
    fn next(&mut self) -> u64 {
        self.0 ^= self.0 >> 12;
        self.0 ^= self.0 << 25;
        self.0 ^= self.0 >> 27;
        self.0.wrapping_mul(0x2545_F491_4F6C_DD1D)
    }

    fn below(&mut self, n: u64) -> u64 {
        self.next() % n
    }
}

/// Random sequences of everything that goes wrong around a create: RPC calls that fail,
/// transactions that are lost, refused, or land without a status, a node that lags, a fee
/// payer that is emptied and refilled, restarts. After every step:
///
/// - there is at most one table on chain;
/// - a table on chain is in the state file (as a table or as a create that may have landed);
/// - once a table is on chain no other create is ever sent;
/// - two creates are never closer than the wait the first one started.
async fn chaos(seed: u64) {
    let b = Bench::new().await;
    let mut rng = Rng(seed.wrapping_mul(0x9E37_79B9_7F4A_7C15) | 1);
    let modes = [SendMode::Execute, SendMode::Drop, SendMode::LandUnseen, SendMode::Reject];
    {
        let mut s = b.stub.lock();
        s.height_step = 151;
        // Most sequences start with a node that does not land what it is sent.
        s.send_mode = modes[rng.below(modes.len() as u64) as usize];
    }
    if rng.below(4) != 0 {
        b.fund(SOL);
    }
    let mut p = b.start(b.config());
    // Mostly the calls around a transaction that was sent (its status, the block height, the
    // read of the tables afterwards); now and then one of the calls before it.
    let methods = [
        "getMultipleAccounts",
        "getMultipleAccounts",
        "getSignatureStatuses",
        "getSignatureStatuses",
        "getBlockHeight",
        "getEpochInfo",
        "getBalance",
        "getLatestBlockhash",
        "getSlot",
        "getMinimumBalanceForRentExemption",
    ];
    let mut last_create: Option<i64> = None;
    let mut seen_creates = 0;
    let mut log = Vec::new();
    for step in 0..100 {
        let note = match rng.below(20) {
            0..=7 => format!("sync -> {:?}", p.crank.sync_alts().await.map_err(|e| e.to_string())),
            8..=11 => {
                // Seconds or an hour: both sides of every wait.
                let secs = if rng.below(2) == 0 { 1 + rng.below(90) } else { 60 + rng.below(4_000) } as i64;
                b.pass(secs);
                format!("{secs} s pass")
            }
            12 | 13 => {
                p = b.start(b.config());
                "restart".into()
            }
            14 | 15 => {
                let (m, n) = (methods[rng.below(methods.len() as u64) as usize], 1 + rng.below(2) as u32);
                b.stub.lock().fail(m, n);
                format!("{m} fails {n} times")
            }
            16 => {
                let m = modes[rng.below(modes.len() as u64) as usize];
                b.stub.lock().send_mode = m;
                format!("sends: {m:?}")
            }
            17 => {
                let lamports = [0, 1_000_000, SOL, SOL, SOL, SOL][rng.below(6) as usize];
                b.fund(lamports);
                format!("fee payer holds {lamports}")
            }
            18 => {
                p.crank.note_fee_payer_balance(b.balance());
                "the poller reads the balance".into()
            }
            _ => {
                b.stub.lock().lag_after_next_tx = 1 + rng.below(4) as u32;
                "the node will lag".into()
            }
        };
        let now = b.clock.load(std::sync::atomic::Ordering::SeqCst);
        let tables = on_chain(&b);
        let saved = if b.state_file().exists() { file(&b) } else { TableState::default() };
        let creates: Vec<Address> = b.stub.lock().creates.iter().map(|(_, table)| *table).collect();
        log.push(format!("{step}: {note} | creates {}, on chain {}, failures {}", creates.len(), tables.len(), saved.retry.failures));
        let story = || format!("seed {seed}\n{}", log.join("\n"));
        assert!(tables.len() <= 1, "a second table: {}", story());
        for t in &tables {
            assert!(saved.tables.contains(&t.key) || saved.pending.contains(&t.key), "table {} is on chain and not in the state file: {}", t.key, story());
        }
        assert!(creates.len() <= seen_creates + 1, "two creates in one step: {}", story());
        if creates.len() > seen_creates {
            // A create was sent in this step. None may follow one that put a table on chain,
            // and it comes no sooner than the wait the one before it started.
            assert!(!tables.iter().any(|t| creates[..seen_creates].contains(&t.key)), "a create although a table existed: {}", story());
            if let Some(last) = last_create {
                let wait = Retry::delay_secs(seen_creates as u32);
                assert!(now - last >= wait, "creates {} s apart, the wait was {wait} s: {}", now - last, story());
            }
            last_create = Some(now);
            seen_creates = creates.len();
        }
    }
    // Let the dust settle: a working node, a funded fee payer, time. One table, filled once.
    {
        let mut s = b.stub.lock();
        s.send_mode = SendMode::Execute;
        s.failing.clear();
        s.lag_after_next_tx = 0;
    }
    b.fund(SOL);
    for _ in 0..12 {
        b.pass(3_600);
        p.crank.note_fee_payer_balance(b.balance());
        let _ = p.crank.sync_alts().await;
    }
    let story = format!("seed {seed}\n{}", log.join("\n"));
    let tables = on_chain(&b);
    assert_eq!(tables.len(), 1, "exactly one table on chain: {story}");
    assert_eq!(tables[0].addresses, alt::shared_addresses(&hd_crank::hd::PROGRAM_ID), "the shared accounts, each once: {story}");
    let table = the_table(&b);
    assert_eq!(keys(&p), vec![table.key], "{story}");
    assert_eq!(settled_file(&b), vec![table.key], "{story}");
    if std::env::var_os("HD_TEST_LOG").is_some() {
        eprintln!("seed {seed}: {} creates sent in the faulty part\n{}\n", seen_creates, log.join("\n"));
    }
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn no_sequence_of_faults_makes_a_second_table_or_loses_one_a() {
    for seed in 1..=4 {
        chaos(seed).await;
    }
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn no_sequence_of_faults_makes_a_second_table_or_loses_one_b() {
    for seed in 5..=8 {
        chaos(seed).await;
    }
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn no_sequence_of_faults_makes_a_second_table_or_loses_one_c() {
    for seed in 9..=12 {
        chaos(seed).await;
    }
}
