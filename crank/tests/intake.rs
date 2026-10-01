//! The intake server over real sockets (contract A): acks with `ok` / `reason`, heartbeats,
//! BREAK and FREEZE frames, integers as numbers or decimal strings, unknown fields ignored,
//! the `/v1/heartbeats` alias, rate limits, the size cap, the connection cap, `/healthz` and
//! `/metrics`.

mod common;

use std::collections::HashMap;
use std::net::SocketAddr;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use async_trait::async_trait;
use base64::Engine;
use common::Phone;
use futures_util::{SinkExt, StreamExt};
use hd_crank::breaker::Breaker;
use hd_crank::chain::ChainView;
use hd_crank::hd::{self, HeartbeatFields, Rig, RigState, SignalKind};
use hd_crank::heartbeat::{HeartbeatStore, RigCache, RigSource, Verifier};
use hd_crank::intake::{self, Intake, IntakeConfig};
use hd_crank::metrics::Metrics;
use hd_crank::mirror::NoMirror;
use hd_crank::ore::{Board, OreConfig, Treasury};
use hd_crank::ratelimit::Quota;
use hd_crank::signal::{SignalHub, SignalHubConfig};
use p256::ecdsa::{signature::Signer, Signature};
use serde_json::{json, Value};
use solana_address::Address;
use tokio::sync::watch;
use tokio_tungstenite::tungstenite::Message;

const ROUND: u64 = 5_000;

#[derive(Clone, Default)]
struct MapSource(Arc<Mutex<HashMap<Address, Rig>>>);

#[async_trait]
impl RigSource for MapSource {
    async fn fetch_rig(&self, rig: &Address) -> anyhow::Result<Option<Rig>> {
        Ok(self.0.lock().unwrap().get(rig).cloned())
    }
}

fn rig_for(phone: &Phone) -> Rig {
    Rig {
        bump: 255,
        authority: Address::new_from_array([3; 32]),
        p256_pubkey: phone.pubkey(),
        attestation_level: 1,
        state: RigState::Down,
        plan_split_tiles: 15,
        plan_lease_rounds: 3,
        shift_id: 2,
        freezes_left: 2,
        shift_open: true,
        ..Rig::default()
    }
}

struct Server {
    addr: SocketAddr,
    metrics: Arc<Metrics>,
    store: Arc<HeartbeatStore>,
    signals: Arc<SignalHub>,
    chain: watch::Sender<ChainView>,
    breaker: Arc<Breaker>,
}

async fn start_with(cfg: IntakeConfig, src: MapSource, hub: SignalHubConfig) -> Server {
    let metrics = Arc::new(Metrics::default());
    let breaker = Arc::new(Breaker::new(metrics.clone()));
    let store = Arc::new(HeartbeatStore::new(1000));
    let (chain_tx, chain_rx) = watch::channel(ChainView::default());
    let verifier = Verifier {
        program_id: hd::PROGRAM_ID,
        rigs: RigCache::new(src, Duration::from_secs(60), Duration::from_secs(30), 1000, 1000.0),
        store: store.clone(),
    };
    let signals = Arc::new(SignalHub::new(hub));
    let intake = Intake::new(cfg, verifier, signals.clone(), metrics.clone(), breaker.clone(), chain_rx, Arc::new(NoMirror));
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = listener.local_addr().unwrap();
    tokio::spawn(intake::serve(listener, intake::router(intake)));
    Server { addr, metrics, store, signals, chain: chain_tx, breaker }
}

async fn start(cfg: IntakeConfig, src: MapSource) -> Server {
    start_with(cfg, src, SignalHubConfig::default()).await
}

fn live_view(slot: u64) -> ChainView {
    ChainView {
        slot,
        board: Some(Board { round_id: ROUND, start_slot: 100, end_slot: 340, production_cost_ema: 900_000_000 }),
        treasury: Some(Treasury { motherlode: 0 }),
        round: None,
        ore_config: Some(OreConfig { intermission_slots: 48, round_slots: 240 }),
        last_account_update: Some(Instant::now()),
        last_slot_update: Some(Instant::now()),
    }
}

fn b64(sig: [u8; 64]) -> String {
    base64::engine::general_purpose::STANDARD.encode(sig)
}

fn heartbeat_json(phone: &Phone, rig: &Address, counter: u64) -> String {
    let f = HeartbeatFields { counter, shift_id: 2, round_id: ROUND, lease_rounds: 1 };
    let sig = phone.sign_raw(&hd::PROGRAM_ID, rig, &f);
    json!({
        "type": "heartbeat", "rig": rig.to_string(), "counter": counter, "shift_id": 2,
        "round_id": ROUND, "lease_rounds": 1, "sig64": b64(sig)
    })
    .to_string()
}

fn signal_sig(phone: &Phone, rig: &Address, kind: SignalKind, counter: u64, shift_id: u64, reason: u8) -> [u8; 64] {
    let digest = hd::digest(&hd::break_preimage(&hd::PROGRAM_ID, rig, kind.message_kind(), counter, shift_id, reason));
    let sig: Signature = phone.sk.sign(&digest);
    sig.to_bytes().into()
}

fn signal_json(phone: &Phone, rig: &Address, kind: SignalKind, counter: u64, reason: u8) -> String {
    let sig = signal_sig(phone, rig, kind, counter, 2, reason);
    json!({ "type": kind.name(), "rig": rig.to_string(), "counter": counter, "shift_id": 2, "reason": reason, "sig64": b64(sig) })
        .to_string()
}

type Ws = tokio_tungstenite::WebSocketStream<tokio_tungstenite::MaybeTlsStream<tokio::net::TcpStream>>;

async fn connect(addr: SocketAddr) -> Ws {
    tokio_tungstenite::connect_async(format!("ws://{addr}/ws")).await.unwrap().0
}

async fn ask(ws: &mut Ws, text: String) -> Value {
    ws.send(Message::text(text)).await.unwrap();
    loop {
        match tokio::time::timeout(Duration::from_secs(5), ws.next()).await.unwrap() {
            Some(Ok(Message::Text(t))) => return serde_json::from_str(t.as_str()).unwrap(),
            Some(Ok(_)) => continue,
            other => panic!("connection ended: {other:?}"),
        }
    }
}

fn ack(counter: u64, reason: &str) -> Value {
    json!({ "type": "ack", "counter": counter, "ok": reason == "accepted", "reason": reason })
}

async fn http_get(addr: SocketAddr, path: &str) -> (u16, String) {
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    let mut s = tokio::net::TcpStream::connect(addr).await.unwrap();
    s.write_all(format!("GET {path} HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n").as_bytes()).await.unwrap();
    let mut buf = String::new();
    s.read_to_string(&mut buf).await.unwrap();
    let code = buf.split_whitespace().nth(1).unwrap().parse().unwrap();
    let body = buf.split("\r\n\r\n").nth(1).unwrap_or("").to_string();
    (code, body)
}

#[tokio::test]
async fn heartbeat_acks_follow_contract_a() {
    let phone = Phone::new(1);
    let rig = Address::new_from_array([0x51; 32]);
    let src = MapSource::default();
    src.0.lock().unwrap().insert(rig, rig_for(&phone));
    let s = start(IntakeConfig { rig_quota: Quota::new(100, 10.0), ..IntakeConfig::default() }, src).await;
    s.chain.send_replace(live_view(200));

    let mut ws = connect(s.addr).await;
    assert_eq!(ask(&mut ws, heartbeat_json(&phone, &rig, 1)).await, ack(1, "accepted"));
    assert_eq!(s.store.get(&rig).unwrap().fields.counter, 1);
    assert_eq!(ask(&mut ws, heartbeat_json(&phone, &rig, 1)).await, ack(1, "stale_counter"));
    let thief = Phone::new(2);
    assert_eq!(ask(&mut ws, heartbeat_json(&thief, &rig, 2)).await, ack(2, "bad_signature"));
    assert_eq!(ask(&mut ws, heartbeat_json(&phone, &Address::new_from_array([0x52; 32]), 1)).await, ack(1, "unknown_rig"));
    assert_eq!(ask(&mut ws, "{not json".into()).await, ack(0, "malformed"));
    assert_eq!(ask(&mut ws, "[1,2]".into()).await, ack(0, "malformed"));
    assert_eq!(ask(&mut ws, json!({ "type": "heartbeat", "rig": "x", "counter": 9 }).to_string()).await, ack(9, "malformed"));
    assert_eq!(ask(&mut ws, json!({ "type": "plan", "counter": 4 }).to_string()).await, ack(4, "malformed"));

    // Integers as decimal strings, unknown fields ignored.
    let f = HeartbeatFields { counter: 3, shift_id: 2, round_id: ROUND, lease_rounds: 2 };
    let sig = phone.sign_raw(&hd::PROGRAM_ID, &rig, &f);
    let r = ask(
        &mut ws,
        json!({ "type": "heartbeat", "rig": rig.to_string(), "counter": "3", "shift_id": "2", "round_id": ROUND.to_string(),
                "lease_rounds": "2", "sig64": b64(sig), "app_version": "1.2.3", "extra": { "nested": true } })
        .to_string(),
    )
    .await;
    assert_eq!(r, ack(3, "accepted"));
    // Legacy spellings: no `type`, `sig` instead of `sig64`.
    let f = HeartbeatFields { counter: 4, shift_id: 2, round_id: ROUND, lease_rounds: 1 };
    let sig = phone.sign_raw(&hd::PROGRAM_ID, &rig, &f);
    let legacy = json!({ "rig": rig.to_string(), "counter": 4, "shift_id": 2, "round_id": ROUND, "lease_rounds": 1, "sig": b64(sig) });
    assert_eq!(ask(&mut ws, legacy.to_string()).await, ack(4, "accepted"));

    // lease_invalid: bad lease, a round in the future, an expired lease, another shift, a rig that is not armed.
    let bad_lease = |c: u64, lease: u64, round: u64, shift: u64| {
        let f = HeartbeatFields { counter: c, shift_id: shift, round_id: round, lease_rounds: lease.min(255) as u8 };
        let sig = phone.sign_raw(&hd::PROGRAM_ID, &rig, &f);
        json!({ "type": "heartbeat", "rig": rig.to_string(), "counter": c, "shift_id": shift, "round_id": round, "lease_rounds": lease, "sig64": b64(sig) })
            .to_string()
    };
    assert_eq!(ask(&mut ws, bad_lease(5, 0, ROUND, 2)).await, ack(5, "lease_invalid"));
    assert_eq!(ask(&mut ws, bad_lease(5, 4, ROUND, 2)).await, ack(5, "lease_invalid"));
    assert_eq!(ask(&mut ws, bad_lease(5, 300, ROUND, 2)).await, ack(5, "lease_invalid"));
    assert_eq!(ask(&mut ws, bad_lease(5, 1, ROUND + 2, 2)).await, ack(5, "lease_invalid"));
    assert_eq!(ask(&mut ws, bad_lease(5, 1, ROUND - 1, 2)).await, ack(5, "lease_invalid"));
    assert_eq!(ask(&mut ws, bad_lease(5, 1, ROUND, 3)).await, ack(5, "lease_invalid"));

    let r = ask(&mut ws, json!({ "type": "status" }).to_string()).await;
    assert_eq!(r["round_id"], ROUND);
    assert_eq!(r["end_slot"], 340);

    assert_eq!(s.metrics.heartbeats_accepted.get(), 3);
    assert_eq!(s.metrics.heartbeats_rejected.get("bad_signature"), 1);
    let (code, body) = http_get(s.addr, "/metrics").await;
    assert_eq!(code, 200);
    assert!(body.contains("hd_crank_heartbeats_accepted_total 3"));
    assert!(body.contains("hd_crank_heartbeats_rejected_total{reason=\"stale_counter\"} 1"));
}

#[tokio::test]
async fn cooling_rigs_take_heartbeats_frozen_ones_do_not() {
    let phone = Phone::new(4);
    let rig = Address::new_from_array([0x53; 32]);
    let src = MapSource::default();
    src.0.lock().unwrap().insert(rig, Rig { state: RigState::Cooling, hb_counter: 10, ..rig_for(&phone) });
    let frozen = Address::new_from_array([0x58; 32]);
    src.0.lock().unwrap().insert(frozen, Rig { state: RigState::Frozen, ..rig_for(&phone) });
    let s = start(IntakeConfig::default(), src.clone()).await;
    s.chain.send_replace(live_view(200));
    let mut ws = connect(s.addr).await;
    assert_eq!(ask(&mut ws, heartbeat_json(&phone, &rig, 10)).await, ack(10, "stale_counter"), "at or below the BREAK's counter");
    assert_eq!(ask(&mut ws, heartbeat_json(&phone, &rig, 11)).await, ack(11, "accepted"), "a fresh heartbeat resumes a Cooling rig");
    assert_eq!(ask(&mut ws, heartbeat_json(&phone, &frozen, 12)).await, ack(12, "lease_invalid"));
}

#[tokio::test]
async fn break_and_freeze_frames_are_verified_and_queued_once() {
    let phone = Phone::new(5);
    let rig = Address::new_from_array([0x54; 32]);
    let src = MapSource::default();
    src.0.lock().unwrap().insert(rig, rig_for(&phone));
    let broken = Address::new_from_array([0x59; 32]);
    let frozen = Address::new_from_array([0x5a; 32]);
    src.0.lock().unwrap().insert(broken, Rig { state: RigState::Broken, ..rig_for(&phone) });
    src.0.lock().unwrap().insert(frozen, Rig { state: RigState::Frozen, ..rig_for(&phone) });
    let hub = SignalHubConfig { rig_quota: Quota::new(100, 10.0), ..SignalHubConfig::default() };
    let s = start_with(IntakeConfig::default(), src.clone(), hub).await;
    s.chain.send_replace(live_view(200));
    let mut rx = s.signals.take_receiver().unwrap();
    let mut ws = connect(s.addr).await;

    // BREAK pickup (reason 1), then the same frame again: accepted twice, queued once.
    let brk = signal_json(&phone, &rig, SignalKind::Break, 7, hd::reason::PICKUP);
    assert_eq!(ask(&mut ws, brk.clone()).await, ack(7, "accepted"));
    let queued = rx.try_recv().unwrap();
    assert_eq!((queued.kind, queued.counter, queued.reason, queued.shift_id), (SignalKind::Break, 7, 1, 2));
    assert_eq!(queued.authority, Address::new_from_array([3; 32]), "the lander passes rig.authority");
    assert!(p256_introspect::is_low_s(queued.sig[32..].try_into().unwrap()));
    assert!(s.signals.is_pending(&rig));
    assert_eq!(ask(&mut ws, brk).await, ack(7, "accepted"), "idempotent");
    assert!(rx.try_recv().is_err(), "never queued twice");
    // A different message with the same counter, and an older counter: stale.
    assert_eq!(ask(&mut ws, signal_json(&phone, &rig, SignalKind::Break, 7, hd::reason::SCREEN_ON)).await, ack(7, "stale_counter"));
    assert_eq!(ask(&mut ws, signal_json(&phone, &rig, SignalKind::Break, 6, hd::reason::PICKUP)).await, ack(6, "stale_counter"));
    // A heartbeat at or below an accepted signal's counter is stale too.
    assert_eq!(ask(&mut ws, heartbeat_json(&phone, &rig, 7)).await, ack(7, "stale_counter"));
    // Reasons: BREAK takes {1,2,4,5,6,7,8}; FREEZE takes 3.
    for r in [0u8, 3, 9] {
        assert_eq!(ask(&mut ws, signal_json(&phone, &rig, SignalKind::Break, 20, r)).await, ack(20, "malformed"), "break reason {r}");
    }
    assert_eq!(ask(&mut ws, signal_json(&phone, &rig, SignalKind::Freeze, 20, 1)).await, ack(20, "malformed"));
    // Bad signature, wrong shift, unknown rig.
    let thief = Phone::new(6);
    assert_eq!(ask(&mut ws, signal_json(&thief, &rig, SignalKind::Break, 8, hd::reason::UNPLUGGED)).await, ack(8, "bad_signature"));
    let sig = signal_sig(&phone, &rig, SignalKind::Break, 8, 9, hd::reason::UNLOCKED);
    let other_shift = json!({ "type": "break", "rig": rig.to_string(), "counter": 8, "shift_id": 9, "reason": 8, "sig64": b64(sig) });
    assert_eq!(ask(&mut ws, other_shift.to_string()).await, ack(8, "lease_invalid"));
    let ghost = Address::new_from_array([0x55; 32]);
    assert_eq!(ask(&mut ws, signal_json(&phone, &ghost, SignalKind::Freeze, 1, 3)).await, ack(1, "unknown_rig"));
    // FREEZE (legacy `kind` + `sig`, counter as a string).
    let sig = signal_sig(&phone, &rig, SignalKind::Freeze, 9, 2, 3);
    let frz = json!({ "kind": "freeze", "rig": rig.to_string(), "counter": "9", "shift_id": 2, "reason": 3, "sig": b64(sig) });
    assert_eq!(ask(&mut ws, frz.to_string()).await, ack(9, "accepted"));
    assert_eq!(rx.try_recv().unwrap().kind, SignalKind::Freeze);
    // A BREAK for a Broken rig cannot apply; a FREEZE for a Frozen rig is acknowledged, not landed.
    assert_eq!(ask(&mut ws, signal_json(&phone, &broken, SignalKind::Break, 10, 6)).await, ack(10, "lease_invalid"));
    assert_eq!(ask(&mut ws, signal_json(&phone, &frozen, SignalKind::Freeze, 11, 3)).await, ack(11, "accepted"));
    assert!(rx.try_recv().is_err(), "nothing to land for an already Frozen rig");
    assert!(!s.signals.is_pending(&frozen));
    assert_eq!(s.metrics.signals_accepted.get("break"), 1);
    assert_eq!(s.metrics.signals_accepted.get("freeze"), 1);
}

#[tokio::test]
async fn signals_disabled_or_over_budget_are_rate_limited() {
    let phone = Phone::new(7);
    let rig = Address::new_from_array([0x56; 32]);
    let src = MapSource::default();
    src.0.lock().unwrap().insert(rig, rig_for(&phone));
    let off = start_with(IntakeConfig::default(), src.clone(), SignalHubConfig { enabled: false, ..SignalHubConfig::default() }).await;
    let mut ws = connect(off.addr).await;
    assert_eq!(ask(&mut ws, signal_json(&phone, &rig, SignalKind::Break, 1, 1)).await, ack(1, "rate_limited"));
    let poor = start_with(
        IntakeConfig::default(),
        src,
        SignalHubConfig { max_lamports_per_hour: 15_000, est_fee: 10_000, rig_quota: Quota::new(2, 0.0001), ..SignalHubConfig::default() },
    )
    .await;
    let _rx = poor.signals.take_receiver();
    let mut ws = connect(poor.addr).await;
    assert_eq!(ask(&mut ws, signal_json(&phone, &rig, SignalKind::Break, 1, 1)).await, ack(1, "accepted"));
    assert_eq!(ask(&mut ws, signal_json(&phone, &rig, SignalKind::Break, 2, 2)).await, ack(2, "rate_limited"), "fee budget spent");
    assert_eq!(ask(&mut ws, signal_json(&phone, &rig, SignalKind::Break, 3, 2)).await, ack(3, "rate_limited"), "per-rig bucket spent");
    assert_eq!(poor.metrics.signals_rejected.get("signal_budget"), 1);
    assert_eq!(poor.metrics.signals_rejected.get("rate_limited_rig"), 1);
}

#[tokio::test]
async fn the_v1_heartbeats_alias_serves_the_same_intake() {
    let phone = Phone::new(8);
    let rig = Address::new_from_array([0x57; 32]);
    let src = MapSource::default();
    src.0.lock().unwrap().insert(rig, rig_for(&phone));
    let s = start(IntakeConfig::default(), src).await;
    s.chain.send_replace(live_view(200));
    let mut ws = tokio_tungstenite::connect_async(format!("ws://{}/v1/heartbeats", s.addr)).await.unwrap().0;
    assert_eq!(ask(&mut ws, heartbeat_json(&phone, &rig, 1)).await, ack(1, "accepted"));
}

#[tokio::test]
async fn oversize_messages_close_the_socket() {
    let s = start(IntakeConfig { max_message_bytes: 512, ..IntakeConfig::default() }, MapSource::default()).await;
    let mut ws = connect(s.addr).await;
    ws.send(Message::text("x".repeat(4096))).await.unwrap();
    let next = tokio::time::timeout(Duration::from_secs(5), ws.next()).await.unwrap();
    assert!(!matches!(next, Some(Ok(Message::Text(_)))), "no reply to an oversize frame: {next:?}");
}

#[tokio::test]
async fn per_ip_and_per_rig_rate_limits() {
    let phone = Phone::new(3);
    let rig = Address::new_from_array([0x61; 32]);
    let src = MapSource::default();
    src.0.lock().unwrap().insert(rig, rig_for(&phone));
    let cfg = IntakeConfig {
        ip_quota: Quota::new(5, 0.001),
        rig_quota: Quota::new(2, 0.001),
        ..IntakeConfig::default()
    };
    let s = start(cfg, src).await;
    s.chain.send_replace(live_view(200));
    let mut ws = connect(s.addr).await;
    assert_eq!(ask(&mut ws, heartbeat_json(&phone, &rig, 1)).await, ack(1, "accepted"));
    assert_eq!(ask(&mut ws, heartbeat_json(&phone, &rig, 2)).await, ack(2, "accepted"));
    assert_eq!(ask(&mut ws, heartbeat_json(&phone, &rig, 3)).await, ack(3, "rate_limited"));
    assert_eq!(s.metrics.heartbeats_rejected.get("rate_limited_rig"), 1);
    assert_eq!(ask(&mut ws, json!({ "type": "status" }).to_string()).await["type"], "status");
    assert_eq!(ask(&mut ws, json!({ "type": "status" }).to_string()).await["type"], "status");
    // 5 messages used: the IP bucket is empty, even for a new connection.
    assert_eq!(ask(&mut ws, heartbeat_json(&phone, &rig, 4)).await, ack(4, "rate_limited"));
    let mut ws2 = connect(s.addr).await;
    assert_eq!(ask(&mut ws2, json!({ "type": "status" }).to_string()).await, ack(0, "rate_limited"));
    assert_eq!(s.metrics.heartbeats_rejected.get("rate_limited_ip"), 2);
}

#[tokio::test]
async fn connection_caps() {
    let s = start(IntakeConfig { max_connections_per_ip: 2, ..IntakeConfig::default() }, MapSource::default()).await;
    let a = connect(s.addr).await;
    let _b = connect(s.addr).await;
    let third = tokio_tungstenite::connect_async(format!("ws://{}/ws", s.addr)).await;
    match third {
        Err(tokio_tungstenite::tungstenite::Error::Http(resp)) => assert_eq!(resp.status(), 429),
        other => panic!("expected 429, got {other:?}"),
    }
    drop(a);
    // The slot is released when a connection ends.
    let mut ok = false;
    for _ in 0..50 {
        if tokio_tungstenite::connect_async(format!("ws://{}/ws", s.addr)).await.is_ok() {
            ok = true;
            break;
        }
        tokio::time::sleep(Duration::from_millis(20)).await;
    }
    assert!(ok, "slot released after close");
    assert!(s.metrics.intake_connections.get() <= 2);
}

#[tokio::test]
async fn healthz_reflects_chain_and_breaker() {
    let s = start(IntakeConfig::default(), MapSource::default()).await;
    let (code, body) = http_get(s.addr, "/healthz").await;
    assert_eq!(code, 503, "no chain view yet: {body}");
    s.chain.send_replace(live_view(200));
    let (code, body) = http_get(s.addr, "/healthz").await;
    assert_eq!(code, 200, "{body}");
    let v: Value = serde_json::from_str(&body).unwrap();
    assert_eq!(v["status"], "ok");
    assert_eq!(v["round_id"], ROUND);
    assert_eq!(v["signals_enabled"], true);
    s.breaker.trip("test: ORE layout changed");
    let (code, body) = http_get(s.addr, "/healthz").await;
    assert_eq!(code, 503);
    assert!(body.contains("ORE layout changed"));
}
