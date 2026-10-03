//! End to end on a real validator: `solana-test-validator` loaded with the live mainnet ORE
//! binary and accounts (fixtures), heads_down (the mock, or the real program with
//! `--features real-program`), and the crank's own `app::run` wiring (WebSocket watcher,
//! intake server, planner, lookup-table management, simulate → send → confirm → events). A
//! "phone" talks to the intake over WebSocket with contract-A frames, as the Android app will.
//!
//! With the real program it also covers the v1.1 duties through the same process: a
//! phone-signed BREAK and FREEZE landed by the crank, a focus-only rig's heartbeat recorded
//! with `record_heartbeats`, and a stale shift sealed by the permissionless `end_shift` sweep.
//!
//! The fixture Board is rewritten to a window `[0, END)` so the round is open on a fresh
//! ledger; everything else is byte-for-byte mainnet.
//!
//! ```sh
//! cargo build-sbf --manifest-path test-fixtures/mock-heads-down/Cargo.toml
//! ORE_FIXTURES_DIR=... cargo test --features e2e --test e2e_validator -- --nocapture
//! ORE_FIXTURES_DIR=... HD_PROGRAM_SO=... cargo test --features e2e,real-program --test e2e_validator -- --nocapture
//! ```
#![cfg(feature = "e2e")]

mod common;

use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::time::{Duration, Instant};

use base64::Engine;
use common::Phone;
use futures_util::{SinkExt, StreamExt};
use hd_crank::config::Config;
use hd_crank::hd::{self, HdConfig, HeartbeatFields, Rig, RigAccounts, RigState, SignalKind};
use hd_crank::ore;
use hd_crank::rpc::RpcClient;
use hd_crank::sender::{ConfirmPolicy, Submitter};
use hd_crank::tx;
use p256::ecdsa::{signature::Signer as _, Signature};
use serde_json::{json, Value};
use solana_address::Address;
use solana_instruction::{AccountMeta, Instruction};
use solana_keypair::Keypair;
use solana_signer::Signer;
use tokio_tungstenite::tungstenite::Message;

const END_SLOT: u64 = 260;
const DEPLOY_MARGIN: u64 = 160;
const EXECUTOR_FEE: u64 = 10_000;
const CRANK_FEE: u64 = 7_000;
const RIGS: usize = 3;
const REAL: bool = cfg!(feature = "real-program");

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
    if REAL {
        base.join("../programs/heads-down/target/deploy/heads_down.so")
    } else {
        base.join("test-fixtures/mock-heads-down/target/deploy/mock_heads_down.so")
    }
}

fn agave_bin() -> PathBuf {
    let home = std::env::var_os("HOME").unwrap_or_default();
    PathBuf::from(home).join(".local/share/solana/install/active_release/bin")
}

struct Validator(Child);
impl Drop for Validator {
    fn drop(&mut self) {
        let _ = self.0.kill();
        let _ = self.0.wait();
    }
}

fn account_json(dir: &Path, addr: &Address, lamports: u64, owner: &Address, data: &[u8]) -> PathBuf {
    let p = dir.join(format!("{addr}.json"));
    let v = json!({
        "pubkey": addr.to_string(),
        "account": {
            "lamports": lamports,
            "data": [base64::engine::general_purpose::STANDARD.encode(data), "base64"],
            "owner": owner.to_string(),
            "executable": false,
            "rentEpoch": 0,
            "space": data.len(),
        }
    });
    std::fs::write(&p, v.to_string()).unwrap();
    p
}

fn fixture_data(name: &str) -> (u64, Vec<u8>) {
    let raw = std::fs::read_to_string(fixtures().join(format!("{name}.json")))
        .unwrap_or_else(|_| panic!("missing fixture {name} — run spikes/ore-executor/fetch-fixtures.sh"));
    let v: Value = serde_json::from_str(&raw).unwrap();
    let a = &v["account"];
    (a["lamports"].as_u64().unwrap(), base64::engine::general_purpose::STANDARD.decode(a["data"][0].as_str().unwrap()).unwrap())
}

fn pick_ports() -> u16 {
    // rpc = base, ws = base + 1, faucet = base + 2, gossip = base + 3, dynamic range after.
    for base in (21_000u16..40_000).step_by(97) {
        let ok = (0..48).all(|i| std::net::TcpListener::bind(("127.0.0.1", base + i)).is_ok());
        if ok {
            return base;
        }
    }
    panic!("no free port range");
}

fn rig_account(authority: Address, pubkey: [u8; 33], now: i64) -> Rig {
    let bump = |seed: &[u8]| Address::find_program_address(&[seed, authority.as_ref()], &ore::ORE_PROGRAM_ID).1;
    Rig {
        bump: hd::rig_pda(&hd::PROGRAM_ID, &authority).1,
        authority,
        p256_pubkey: pubkey,
        attestation_level: 1,
        state: RigState::Armed,
        attestation_expiry_slot: u64::MAX,
        cap_week: 1_000_000_000,
        cap_shift: 100_000_000,
        cap_round: 5_000_000,
        cap_max_cost: u64::MAX,
        caps_expiry_ts: now + 86_400,
        plan_max_ev_cost: u64::MAX,
        plan_dig_lamports: 1_000_000,
        plan_split_tiles: 15,
        plan_lease_rounds: 3,
        plan_window_start_ts: now - 3_600,
        plan_window_end_ts: now + 86_400,
        shift_id: 1,
        week_start_ts: now - 60,
        freezes_left: 2,
        shift_open: true,
        ore_automation_bump: bump(ore::AUTOMATION_SEED),
        ore_miner_bump: bump(ore::MINER_SEED),
        shift_start_ts: now - 3_600,
        ..Rig::default()
    }
}

fn automate_ix(wallet: &Address, executor: &Address) -> Instruction {
    let acc = RigAccounts::derive(hd::rig_pda(&hd::PROGRAM_ID, wallet).0, *wallet);
    let mut data = vec![0u8];
    data.extend_from_slice(&100_000u64.to_le_bytes());
    data.extend_from_slice(&100_000_000u64.to_le_bytes());
    data.extend_from_slice(&EXECUTOR_FEE.to_le_bytes());
    data.extend_from_slice(&0u64.to_le_bytes());
    data.push(2);
    data.extend_from_slice(&0u64.to_le_bytes());
    data.extend_from_slice(&u64::MAX.to_le_bytes());
    data.extend_from_slice(&0u16.to_le_bytes());
    data.extend_from_slice(&u16::MAX.to_le_bytes());
    data.extend_from_slice(&[0u8; 12]);
    Instruction {
        program_id: ore::ORE_PROGRAM_ID,
        accounts: vec![
            AccountMeta::new(*wallet, true),
            AccountMeta::new(acc.automation, false),
            AccountMeta::new(*executor, false),
            AccountMeta::new(acc.miner, false),
            AccountMeta::new_readonly(ore::SYSTEM_PROGRAM_ID, false),
        ],
        data,
    }
}

async fn send_legacy(rpc: &RpcClient, payer: &Keypair, ixs: &[Instruction]) -> anyhow::Result<()> {
    let (bh, lvbh) = rpc.get_latest_blockhash().await?;
    let msg = solana_message::VersionedMessage::Legacy(solana_message::Message::new_with_blockhash(ixs, Some(&payer.pubkey()), &bh));
    let t = tx::make_transaction(msg, Some(payer))?;
    let wire = tx::serialize(&t)?;
    let sig = t.signatures[0].to_string();
    let s = Submitter::rpc(rpc.clone());
    s.send(&wire).await?;
    match s.confirm(&sig, &wire, lvbh, ConfirmPolicy::default()).await {
        hd_crank::sender::Outcome::Landed { err: None, .. } => Ok(()),
        other => anyhow::bail!("tx {sig}: {other:?}"),
    }
}

type Ws = tokio_tungstenite::WebSocketStream<tokio_tungstenite::MaybeTlsStream<tokio::net::TcpStream>>;

async fn ask(ws: &mut Ws, v: Value) -> Value {
    ws.send(Message::text(v.to_string())).await.unwrap();
    loop {
        if let Some(Ok(Message::Text(t))) = ws.next().await {
            return serde_json::from_str(t.as_str()).unwrap();
        }
    }
}

fn heartbeat_frame(phone: &Phone, rig: &Address, counter: u64, round_id: u64) -> Value {
    let f = HeartbeatFields { counter, shift_id: 1, round_id, lease_rounds: 3 };
    let sig = phone.sign_raw(&hd::PROGRAM_ID, rig, &f);
    json!({ "type": "heartbeat", "rig": rig.to_string(), "counter": counter, "shift_id": 1, "round_id": round_id,
            "lease_rounds": 3, "sig64": base64::engine::general_purpose::STANDARD.encode(sig) })
}

fn signal_frame(phone: &Phone, rig: &Address, kind: SignalKind, counter: u64, reason: u8) -> Value {
    let digest = hd::digest(&hd::break_preimage(&hd::PROGRAM_ID, rig, kind.message_kind(), counter, 1, reason));
    let sig: Signature = phone.sk.sign(&digest);
    let raw: [u8; 64] = sig.to_bytes().into();
    json!({ "type": kind.name(), "rig": rig.to_string(), "counter": counter, "shift_id": 1, "reason": reason,
            "sig64": base64::engine::general_purpose::STANDARD.encode(raw) })
}

async fn read_rig(rpc: &RpcClient, a: &Address) -> Rig {
    let acc = rpc.get_account(a).await.unwrap().unwrap();
    Rig::decode(&hd::PROGRAM_ID, &acc.owner, &acc.data).unwrap()
}

async fn wait_rig(rpc: &RpcClient, a: &Address, what: &str, deadline: Instant, ok: impl Fn(&Rig) -> bool) -> Rig {
    loop {
        let r = read_rig(rpc, a).await;
        if ok(&r) {
            return r;
        }
        assert!(Instant::now() < deadline, "{what}: rig {a} state {:?} counter {}", r.state, r.hb_counter);
        tokio::time::sleep(Duration::from_millis(400)).await;
    }
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn crank_digs_on_a_local_validator() {
    let _ = rustls::crypto::ring::default_provider().install_default();
    let _ = tracing_subscriber::fmt().with_env_filter("hd_crank=info,e2e_validator=info").with_test_writer().try_init();
    let so = program_so();
    assert!(so.exists(), "missing {} (build the mock or the real program first)", so.display());
    println!("heads_down under test: {} ({})", so.display(), if REAL { "real program" } else { "mock" });
    let dir = tempfile::tempdir().unwrap();
    let now = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_secs() as i64;

    // ---- genesis accounts ------------------------------------------------------------------
    let mut args: Vec<String> = Vec::new();
    let add = |args: &mut Vec<String>, addr: &Address, path: PathBuf| {
        args.extend(["--account".into(), addr.to_string(), path.display().to_string()]);
    };
    let (bl, mut board) = fixture_data(&ore::BOARD_ADDRESS.to_string());
    board[16..24].copy_from_slice(&0u64.to_le_bytes());
    board[24..32].copy_from_slice(&END_SLOT.to_le_bytes());
    let round_id = u64::from_le_bytes(board[8..16].try_into().unwrap());
    add(&mut args, &ore::BOARD_ADDRESS, account_json(dir.path(), &ore::BOARD_ADDRESS, bl, &ore::ORE_PROGRAM_ID, &board));
    for a in [ore::CONFIG_ADDRESS, ore::TREASURY_ADDRESS] {
        let (l, d) = fixture_data(&a.to_string());
        add(&mut args, &a, account_json(dir.path(), &a, l, &ore::ORE_PROGRAM_ID, &d));
    }
    let (l, d) = fixture_data(&ore::VAR_ADDRESS.to_string());
    add(&mut args, &ore::VAR_ADDRESS, account_json(dir.path(), &ore::VAR_ADDRESS, l, &ore::ENTROPY_PROGRAM_ID, &d));
    let round = ore::round_pda(round_id);
    let (l, d) = fixture_data(&round.to_string());
    add(&mut args, &round, account_json(dir.path(), &round, l, &ore::ORE_PROGRAM_ID, &d));

    let (executor, executor_bump) = hd::executor_pda(&hd::PROGRAM_ID);
    add(&mut args, &executor, account_json(dir.path(), &executor, 50_000_000, &ore::SYSTEM_PROGRAM_ID, &[]));
    let (config_addr, config_bump) = hd::config_pda(&hd::PROGRAM_ID);
    let cfg_bytes = HdConfig {
        bump: config_bump,
        governance: Address::new_from_array([1; 32]),
        registrar: Address::new_from_array([2; 32]),
        crank_fee: CRANK_FEE,
        executor_fee: EXECUTOR_FEE,
        bury_bps: 0,
        paused: false,
        executor_bump,
        ore_layout_hash: [0; 32],
    }
    .encode();
    add(&mut args, &config_addr, account_json(dir.path(), &config_addr, 10_000_000, &hd::PROGRAM_ID, &cfg_bytes));

    let cranker = Keypair::new();
    add(&mut args, &cranker.pubkey(), account_json(dir.path(), &cranker.pubkey(), 10_000_000_000, &ore::SYSTEM_PROGRAM_ID, &[]));
    let mut users = Vec::new();
    for i in 0..RIGS {
        let wallet = Keypair::new();
        let phone = Phone::new(7_000 + i as u32);
        let rig_addr = hd::rig_pda(&hd::PROGRAM_ID, &wallet.pubkey()).0;
        add(&mut args, &wallet.pubkey(), account_json(dir.path(), &wallet.pubkey(), 2_000_000_000, &ore::SYSTEM_PROGRAM_ID, &[]));
        let rig = rig_account(wallet.pubkey(), phone.pubkey(), now);
        add(&mut args, &rig_addr, account_json(dir.path(), &rig_addr, 10_000_000, &hd::PROGRAM_ID, &rig.encode()));
        users.push((wallet, phone, rig_addr));
    }
    // A focus-only rig (record_heartbeats) and a rig whose shift is past its window (end_shift).
    let focus_phone = Phone::new(7_100);
    let focus_wallet = Address::new_from_array([0xF0; 32]);
    let focus = hd::rig_pda(&hd::PROGRAM_ID, &focus_wallet).0;
    let mut focus_rig = rig_account(focus_wallet, focus_phone.pubkey(), now);
    focus_rig.plan_flags = hd::PLAN_FLAG_FOCUS_ONLY;
    focus_rig.plan_split_tiles = 0;
    focus_rig.plan_dig_lamports = 0;
    focus_rig.shift_start_round = round_id;
    add(&mut args, &focus, account_json(dir.path(), &focus, 10_000_000, &hd::PROGRAM_ID, &focus_rig.encode()));
    let stale_wallet = Address::new_from_array([0xF1; 32]);
    let stale = hd::rig_pda(&hd::PROGRAM_ID, &stale_wallet).0;
    let mut stale_rig = rig_account(stale_wallet, Phone::new(7_101).pubkey(), now);
    stale_rig.state = RigState::Down;
    stale_rig.plan_window_end_ts = now - 600;
    // The lease lapsed more than the program's 3-round grace ago (INTERFACE §12.13).
    stale_rig.shift_start_round = round_id - 8;
    stale_rig.shift_dark_rounds = 2;
    stale_rig.lease_from_round = round_id - 6;
    stale_rig.lease_to_round = round_id - 5;
    add(&mut args, &stale, account_json(dir.path(), &stale, 10_000_000, &hd::PROGRAM_ID, &stale_rig.encode()));

    // ---- validator ------------------------------------------------------------------------------
    let base = pick_ports();
    let rpc_url = format!("http://127.0.0.1:{base}");
    let log = std::fs::File::create(dir.path().join("validator.log")).unwrap();
    let child = Command::new(agave_bin().join("solana-test-validator"))
        .args(["--reset", "--quiet", "--ledger"])
        .arg(dir.path().join("ledger"))
        .args(["--bind-address", "127.0.0.1", "--rpc-port", &base.to_string()])
        .args(["--faucet-port", &(base + 2).to_string(), "--gossip-port", &(base + 3).to_string()])
        .args(["--dynamic-port-range", &format!("{}-{}", base + 4, base + 44)])
        .args(["--bpf-program", &ore::ORE_PROGRAM_ID.to_string()])
        .arg(fixtures().join("ore.so"))
        .args(["--bpf-program", &ore::ENTROPY_PROGRAM_ID.to_string()])
        .arg(fixtures().join("entropy.so"))
        .args(["--bpf-program", &hd::PROGRAM_ID.to_string()])
        .arg(&so)
        .args(&args)
        .stdout(Stdio::from(log.try_clone().unwrap()))
        .stderr(Stdio::from(log))
        .spawn()
        .expect("solana-test-validator on PATH or in the Agave install dir");
    let mut validator = Validator(child);
    let rpc = RpcClient::new(rpc_url.clone(), "confirmed", Duration::from_secs(5)).unwrap();
    let t0 = Instant::now();
    loop {
        if rpc.get_slot("confirmed").await.is_ok() {
            break;
        }
        if t0.elapsed() > Duration::from_secs(90) || matches!(validator.0.try_wait(), Ok(Some(_))) {
            let log = std::fs::read_to_string(dir.path().join("validator.log")).unwrap_or_default();
            let tail: Vec<&str> = log.lines().rev().take(30).collect::<Vec<_>>().into_iter().rev().collect();
            panic!("validator did not start:\n{}", tail.join("\n"));
        }
        tokio::time::sleep(Duration::from_millis(500)).await;
    }
    println!("validator up at slot {} after {:?}", rpc.get_slot("confirmed").await.unwrap(), t0.elapsed());

    // ---- users run ORE automate (executor = Executor PDA) -----------------------------------
    for (wallet, _, _) in &users {
        send_legacy(&rpc, wallet, &[automate_ix(&wallet.pubkey(), &executor)]).await.expect("automate");
    }

    // ---- the crank, wired exactly like the binary --------------------------------------------
    let key_path = dir.path().join("crank.json");
    let bytes: Vec<String> = cranker.to_bytes().iter().map(u8::to_string).collect();
    std::fs::write(&key_path, format!("[{}]", bytes.join(","))).unwrap();
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        std::fs::set_permissions(&key_path, std::fs::Permissions::from_mode(0o600)).unwrap();
    }
    let listen_port = std::net::TcpListener::bind("127.0.0.1:0").unwrap().local_addr().unwrap().port();
    let toml = format!(
        r#"
rpc_url = "{rpc_url}"
ws_url = "ws://127.0.0.1:{ws}"
listen = "127.0.0.1:{listen_port}"
state_dir = "{state}"
[dig]
deploy_margin_slots = {DEPLOY_MARGIN}
min_slots_left = 3
ore_programdata_slot = 0
checkpoint_sweep = false
config_poll_secs = 5
[record]
delay_secs = 12
[end_shift]
poll_secs = 3
grace_secs = 0
enabled = {REAL}
"#,
        ws = base + 1,
        state = dir.path().join("state").display(),
    );
    let mut cfg = Config::from_toml(&toml).unwrap().finalize(&|_| None).unwrap();
    cfg.keypair_path = Some(key_path);
    let crank_task = tokio::spawn(hd_crank::app::run(cfg));

    // ---- the phone -----------------------------------------------------------------------------
    let ws_url = format!("ws://127.0.0.1:{listen_port}/ws");
    let mut ws = loop {
        match tokio_tungstenite::connect_async(&ws_url).await {
            Ok((ws, _)) => break ws,
            Err(_) if t0.elapsed() < Duration::from_secs(120) => tokio::time::sleep(Duration::from_millis(200)).await,
            Err(e) => panic!("intake never came up: {e}"),
        }
    };
    let status = loop {
        let s = ask(&mut ws, json!({ "type": "status" })).await;
        if s["round_id"].is_u64() {
            break s;
        }
        tokio::time::sleep(Duration::from_millis(300)).await;
    };
    assert_eq!(status["round_id"], round_id, "the phone learns Board.round_id from the crank");
    for (_, phone, rig) in &users {
        let ack = ask(&mut ws, heartbeat_frame(phone, rig, 1, round_id)).await;
        assert_eq!(ack, json!({ "type": "ack", "counter": 1, "ok": true, "reason": "accepted" }), "contract A ack");
    }
    if REAL {
        let ack = ask(&mut ws, heartbeat_frame(&focus_phone, &focus, 1, round_id)).await;
        assert_eq!(ack["ok"], true, "{ack}");
    }
    println!("heartbeats accepted at slot {}", rpc.get_slot("confirmed").await.unwrap());

    // ---- wait for the crank to dig every rig late in the round -------------------------------
    let deadline = Instant::now() + Duration::from_secs(240);
    let dug_slot = loop {
        let accs = rpc.get_multiple_accounts(&users.iter().map(|u| u.2).collect::<Vec<_>>()).await.unwrap();
        let rigs: Vec<Rig> = accs.into_iter().map(|a| {
            let a = a.unwrap();
            Rig::decode(&hd::PROGRAM_ID, &a.owner, &a.data).unwrap()
        }).collect();
        if rigs.iter().all(|r| r.last_dug_round == round_id) {
            assert!(rigs.iter().all(|r| r.hb_counter == 1 && r.state == RigState::Down));
            break rpc.get_slot("confirmed").await.unwrap();
        }
        assert!(!crank_task.is_finished(), "crank exited early");
        assert!(Instant::now() < deadline, "rigs not dug before the deadline");
        tokio::time::sleep(Duration::from_millis(500)).await;
    };
    println!("all {RIGS} rigs dug by slot {dug_slot} (window opened at slot {})", END_SLOT - DEPLOY_MARGIN);
    assert!(dug_slot >= END_SLOT - DEPLOY_MARGIN, "digs happen late in the round, not before the window");
    assert!(dug_slot < END_SLOT + 32);

    // ORE state moved: each Automation paid per_tile * 15 on squares + the executor fee.
    for (wallet, _, _) in &users {
        let a = rpc.get_account(&ore::automation_pda(&wallet.pubkey())).await.unwrap().unwrap();
        let au = ore::Automation::decode(&a.owner, &a.data).unwrap();
        assert_eq!(au.balance, 100_000_000 - (1_000_000 / 15) * 15 - EXECUTOR_FEE);
    }

    if REAL {
        // ---- a phone-signed BREAK and FREEZE, landed by the crank ------------------------------
        let (_, p0, r0) = &users[0];
        let ack = ask(&mut ws, signal_frame(p0, r0, SignalKind::Break, 2, hd::reason::PICKUP)).await;
        assert_eq!(ack, json!({ "type": "ack", "counter": 2, "ok": true, "reason": "accepted" }));
        let t_break = Instant::now();
        let r = wait_rig(&rpc, r0, "BREAK landed", Instant::now() + Duration::from_secs(60), |r| r.state == RigState::Cooling).await;
        println!("BREAK pickup landed {:?} after the ack: rig Cooling, hb_counter {}", t_break.elapsed(), r.hb_counter);
        assert_eq!((r.hb_counter, r.break_reason), (2, hd::reason::PICKUP));
        // The same frame again: acknowledged, never resubmitted.
        let again = ask(&mut ws, signal_frame(p0, r0, SignalKind::Break, 2, hd::reason::PICKUP)).await;
        assert_eq!(again["ok"], true);
        let (_, p1, r1) = &users[1];
        let ack = ask(&mut ws, signal_frame(p1, r1, SignalKind::Freeze, 2, hd::reason::FREEZE)).await;
        assert_eq!(ack["ok"], true, "{ack}");
        let r = wait_rig(&rpc, r1, "FREEZE landed", Instant::now() + Duration::from_secs(60), |r| r.state == RigState::Frozen).await;
        assert_eq!((r.hb_counter, r.break_reason), (2, hd::reason::FREEZE));

        // ---- the focus-only rig's heartbeat, recorded without a deploy -------------------------
        let r = wait_rig(&rpc, &focus, "record_heartbeats", Instant::now() + Duration::from_secs(90), |r| r.hb_counter == 1).await;
        println!("focus-only rig recorded: state {:?}, lease [{}, {}], dark rounds {}", r.state, r.lease_from_round, r.lease_to_round, r.shift_dark_rounds);
        assert_eq!((r.state, r.lease_from_round, r.lease_to_round, r.shift_dark_rounds, r.last_dug_round), (RigState::Down, round_id, round_id + 2, 3, 0));

        // ---- the stale shift, sealed by the permissionless end_shift sweep --------------------
        let r = wait_rig(&rpc, &stale, "end_shift", Instant::now() + Duration::from_secs(60), |r| !r.shift_open).await;
        assert_eq!(r.state, RigState::Idle);
        let log_addr = hd::shift_log_pda(&hd::PROGRAM_ID, &stale, 1).0;
        let log = rpc.get_account(&log_addr).await.unwrap().expect("ShiftLog created by the crank");
        let sl = hd::ShiftLog::decode(&hd::PROGRAM_ID, &log.owner, &log.data).unwrap();
        println!("stale shift sealed: ShiftLog {log_addr} reason {} dark {} rounds {}..{}", hd::reason_name(sl.break_reason), sl.dark_rounds, sl.start_round, sl.end_round);
        assert_eq!((sl.shift_id, sl.dark_rounds, sl.break_reason, sl.start_round, sl.end_round), (1, 2, 0, round_id - 5, round_id));
        assert_eq!(log.lamports, 1_781_760, "the crank paid the ShiftLog rent");
    }

    // Metrics and health from the running binary wiring.
    let metrics_url = format!("127.0.0.1:{listen_port}");
    let want_landed = format!("hd_crank_digs_landed_total {RIGS}");
    let body = loop {
        let b = http_get(&metrics_url, "/metrics").await;
        let complete = b.contains(&want_landed)
            && (!REAL
                || (b.contains("hd_crank_signals_landed_total{kind=\"break\"} 1")
                    && b.contains("hd_crank_signals_landed_total{kind=\"freeze\"} 1")
                    && b.contains("hd_crank_heartbeats_recorded_total 1")
                    && b.contains("hd_crank_shifts_ended_total 1")));
        if complete || Instant::now() > deadline {
            break b;
        }
        tokio::time::sleep(Duration::from_millis(500)).await;
    };
    for line in body.lines().filter(|l| l.starts_with("hd_crank_") && !l.starts_with("hd_crank_heartbeats_rejected")) {
        println!("  {line}");
    }
    assert!(body.contains(&want_landed), "{body}");
    assert!(body.contains(&format!("hd_crank_squares_lamports_total {}", RIGS as u64 * (1_000_000 / 15) * 15)), "RigDug.lamports summed");
    assert!(body.contains(&format!("hd_crank_heartbeats_accepted_total {}", if REAL { RIGS + 1 } else { RIGS })));
    assert!(body.contains("hd_crank_circuit_breaker_tripped 0"));
    if REAL {
        assert!(body.contains("hd_crank_signals_landed_total{kind=\"break\"} 1"), "{body}");
        assert!(body.contains("hd_crank_signals_landed_total{kind=\"freeze\"} 1"), "{body}");
        assert!(body.contains("hd_crank_heartbeats_recorded_total 1"), "{body}");
        assert!(body.contains("hd_crank_shifts_ended_total 1"), "{body}");
    }
    let state = std::fs::read_to_string(dir.path().join("state/lookup_tables.json")).expect("crank created a lookup table");
    println!("lookup tables: {state}");
    // The crank registered every rig's four accounts in its table as they appeared.
    let v: Value = serde_json::from_str(&state).unwrap();
    let key: Address = v["tables"][0].as_str().unwrap().parse().unwrap();
    let acc = rpc.get_account(&key).await.unwrap().unwrap();
    let table = hd_crank::alt::LookupTable::decode(key, &acc.owner, &acc.data).unwrap();
    assert_eq!(table.authority, Some(cranker.pubkey()));
    for (wallet, _, rig) in &users {
        let ra = RigAccounts::derive(*rig, wallet.pubkey());
        for a in hd_crank::alt::rig_addresses(&ra) {
            assert!(table.addresses.contains(&a), "rig account {a} in the lookup table");
        }
    }
    println!("lookup table holds {} addresses", table.addresses.len());
    crank_task.abort();
}

async fn http_get(addr: &str, path: &str) -> String {
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    let mut s = tokio::net::TcpStream::connect(addr).await.unwrap();
    s.write_all(format!("GET {path} HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n").as_bytes()).await.unwrap();
    let mut buf = String::new();
    s.read_to_string(&mut buf).await.unwrap();
    buf
}
