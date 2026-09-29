//! The intake server over real sockets: acks, rejects, rate limits, size cap, connection
//! cap, `/healthz` and `/metrics`.

mod common;

use std::collections::HashMap;
use std::net::SocketAddr;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use async_trait::async_trait;
use common::Phone;
use futures_util::{SinkExt, StreamExt};
use hd_crank::breaker::Breaker;
use hd_crank::chain::ChainView;
use hd_crank::hd::{self, HeartbeatFields, Rig, RigState};
use hd_crank::heartbeat::{HeartbeatStore, RigCache, RigSource, Verifier};
use hd_crank::intake::{self, Intake, IntakeConfig};
use hd_crank::metrics::Metrics;
use hd_crank::mirror::NoMirror;
use hd_crank::ore::{Board, OreConfig, Treasury};
use hd_crank::ratelimit::Quota;
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
        tier: 0,
        state: RigState::Down,
        sgt_mint: Address::default(),
        attestation_expiry_slot: 0,
        cap_week: 0,
        cap_shift: 0,
        cap_round: 0,
        cap_max_cost: 0,
        caps_expiry_ts: 0,
        plan_max_ev_cost: 0,
        plan_dig_lamports: 0,
        plan_split_tiles: 15,
        plan_solo_tiles: 0,
        plan_lease_rounds: 3,
        plan_flags: 0,
        plan_window_start_ts: 0,
        plan_window_end_ts: 0,
        shift_id: 2,
        hb_counter: 0,
        lease_from_round: 0,
        lease_to_round: 0,
        gap_count: 0,
        spent_shift: 0,
        spent_week: 0,
        week_start_ts: 0,
        last_dug_round: 0,
        shift_start_round: 0,
        shift_dark_rounds: 0,
        shift_rounds_dug: 0,
        lifetime_dark_rounds: 0,
        lifetime_rounds_dug: 0,
        lifetime_lamports_deployed: 0,
        streak: 0,
        freezes_left: 2,
        last_shift_day: 0,
    }
}

struct Server {
    addr: SocketAddr,
    metrics: Arc<Metrics>,
    store: Arc<HeartbeatStore>,
    chain: watch::Sender<ChainView>,
    breaker: Arc<Breaker>,
}

async fn start(cfg: IntakeConfig, src: MapSource) -> Server {
    let metrics = Arc::new(Metrics::default());
    let breaker = Arc::new(Breaker::new(metrics.clone()));
    let store = Arc::new(HeartbeatStore::new(1000));
    let (chain_tx, chain_rx) = watch::channel(ChainView::default());
    let verifier = Verifier {
        program_id: hd::PROGRAM_ID,
        rigs: RigCache::new(src, Duration::from_secs(60), Duration::from_secs(30), 1000, 1000.0),
        store: store.clone(),
    };
    let intake = Intake::new(cfg, verifier, metrics.clone(), breaker.clone(), chain_rx, Arc::new(NoMirror));
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = listener.local_addr().unwrap();
    tokio::spawn(intake::serve(listener, intake::router(intake)));
    Server { addr, metrics, store, chain: chain_tx, breaker }
}

fn live_view(slot: u64) -> ChainView {
    ChainView {
        slot,
        board: Some(Board { round_id: ROUND, start_slot: 100, end_slot: 340, production_cost_ema: 900_000_000 }),
        treasury: Some(Treasury { motherlode: 0 }),
        round: None,
        ore_config: Some(OreConfig {
            intermission_slots: 48,
            round_slots: 240,
            entropy_var: hd_crank::ore::VAR_ADDRESS,
            entropy_program: hd_crank::ore::ENTROPY_PROGRAM_ID,
        }),
        last_account_update: Some(Instant::now()),
        last_slot_update: Some(Instant::now()),
    }
}

fn heartbeat_json(phone: &Phone, rig: &Address, counter: u64) -> String {
    let f = HeartbeatFields { counter, shift_id: 2, round_id: ROUND, lease_rounds: 1 };
    let sig = phone.sign_raw(&hd::PROGRAM_ID, rig, &f);
    json!({
        "type": "heartbeat", "rig": rig.to_string(), "counter": counter, "shift_id": 2,
        "round_id": ROUND, "lease_rounds": 1, "sig64": hex::encode(sig)
    })
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
async fn accepts_verifies_and_rejects() {
    let phone = Phone::new(1);
    let rig = Address::new_from_array([0x51; 32]);
    let src = MapSource::default();
    src.0.lock().unwrap().insert(rig, rig_for(&phone));
    let s = start(IntakeConfig::default(), src).await;
    s.chain.send_replace(live_view(200));

    let mut ws = connect(s.addr).await;
    let r = ask(&mut ws, heartbeat_json(&phone, &rig, 1)).await;
    assert_eq!(r["status"], "accepted", "{r}");
    assert_eq!(s.store.get(&rig).unwrap().fields.counter, 1);
    let r = ask(&mut ws, heartbeat_json(&phone, &rig, 1)).await;
    assert_eq!(r["reason"], "stale_counter");
    let thief = Phone::new(2);
    let r = ask(&mut ws, heartbeat_json(&thief, &rig, 2)).await;
    assert_eq!(r["reason"], "bad_signature");
    let r = ask(&mut ws, heartbeat_json(&phone, &Address::new_from_array([0x52; 32]), 1)).await;
    assert_eq!(r["reason"], "unknown_rig");
    let r = ask(&mut ws, "{not json".into()).await;
    assert_eq!(r, json!({ "type": "error", "reason": "malformed" }));
    let r = ask(&mut ws, json!({ "type": "heartbeat", "rig": "x", "extra": 1 }).to_string()).await;
    assert_eq!(r["reason"], "malformed");
    let r = ask(&mut ws, json!({ "type": "status" }).to_string()).await;
    assert_eq!(r["round_id"], ROUND);
    assert_eq!(r["end_slot"], 340);

    assert_eq!(s.metrics.heartbeats_accepted.get(), 1);
    assert_eq!(s.metrics.heartbeats_rejected.get("bad_signature"), 1);
    let (code, body) = http_get(s.addr, "/metrics").await;
    assert_eq!(code, 200);
    assert!(body.contains("hd_crank_heartbeats_accepted_total 1"));
    assert!(body.contains("hd_crank_heartbeats_rejected_total{reason=\"stale_counter\"} 1"));
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
    assert_eq!(ask(&mut ws, heartbeat_json(&phone, &rig, 1)).await["status"], "accepted");
    assert_eq!(ask(&mut ws, heartbeat_json(&phone, &rig, 2)).await["status"], "accepted");
    assert_eq!(ask(&mut ws, heartbeat_json(&phone, &rig, 3)).await["reason"], "rate_limited_rig");
    assert_eq!(ask(&mut ws, json!({ "type": "status" }).to_string()).await["type"], "status");
    assert_eq!(ask(&mut ws, json!({ "type": "status" }).to_string()).await["type"], "status");
    // 5 messages used: the IP bucket is empty, even for a new connection.
    assert_eq!(ask(&mut ws, heartbeat_json(&phone, &rig, 4)).await["reason"], "rate_limited_ip");
    let mut ws2 = connect(s.addr).await;
    assert_eq!(ask(&mut ws2, json!({ "type": "status" }).to_string()).await["reason"], "rate_limited_ip");
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
    s.breaker.trip("test: ORE layout changed");
    let (code, body) = http_get(s.addr, "/healthz").await;
    assert_eq!(code, 503);
    assert!(body.contains("ORE layout changed"));
}
