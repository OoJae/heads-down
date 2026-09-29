//! Heartbeat intake: an axum WebSocket server, plus `/healthz` and `/metrics`.
//!
//! No auth tokens: a heartbeat is only useful if it verifies against the rig's on-chain
//! key, so the intake's job is to be cheap to reject and hard to exhaust:
//!
//! - global and per-IP connection caps (IPv6 folded to /64), checked before the upgrade;
//! - a hard message/frame size cap (the socket errors on anything bigger);
//! - per-IP and per-rig token buckets before any RPC read or signature check;
//! - a bounded verification pool: when full the phone gets `busy` instead of queueing;
//! - idle and send timeouts, so slow or silent clients cannot pin connections.
//!
//! Protocol (JSON text frames): `{"type":"heartbeat", ...HeartbeatSubmission}` →
//! `{"type":"ack","rig":..,"counter":..,"status":"accepted"|"rejected","reason"?}`;
//! `{"type":"status"}` → the crank's view of the round (a convenience only: phones should
//! read `Board.round_id` themselves, a lying crank can only make heartbeats useless).

use std::collections::HashMap;
use std::net::{IpAddr, SocketAddr};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use axum::extract::ws::{Message, WebSocket, WebSocketUpgrade};
use axum::extract::{ConnectInfo, State};
use axum::http::{header, HeaderMap, StatusCode};
use axum::response::{IntoResponse, Response};
use axum::routing::get;
use axum::{Json, Router};
use serde::Deserialize;
use serde_json::{json, Value};
use solana_address::Address;
use tokio::sync::{watch, OwnedSemaphorePermit, Semaphore};

use crate::breaker::Breaker;
use crate::chain::ChainView;
use crate::heartbeat::{HeartbeatSubmission, ParsedHeartbeat, Reject, RigSource, Verifier};
use crate::metrics::Metrics;
use crate::mirror::HeartbeatMirror;
use crate::ratelimit::{ip_key, KeyedLimiter, Quota};

/// Intake limits.
#[derive(Clone, Debug)]
pub struct IntakeConfig {
    /// Largest accepted WebSocket message / frame.
    pub max_message_bytes: usize,
    /// Open connections, total.
    pub max_connections: usize,
    /// Open connections per IP key.
    pub max_connections_per_ip: u32,
    /// Messages per IP key.
    pub ip_quota: Quota,
    /// Heartbeats per rig.
    pub rig_quota: Quota,
    /// Keys each limiter tracks at most.
    pub max_tracked_keys: usize,
    /// Signature verifications in flight.
    pub max_concurrent_verifications: usize,
    /// Close a connection silent for this long.
    pub idle_timeout: Duration,
    /// Close a connection that does not read its acks within this long.
    pub send_timeout: Duration,
    /// Take the client IP from the last `X-Forwarded-For` hop (only behind a trusted proxy).
    pub trust_forwarded_for: bool,
    /// `/healthz` reports degraded when the slot has not advanced for this long.
    pub stale_chain_after: Duration,
}

impl Default for IntakeConfig {
    fn default() -> Self {
        IntakeConfig {
            max_message_bytes: 2048,
            max_connections: 10_000,
            max_connections_per_ip: 16,
            ip_quota: Quota::new(30, 2.0),
            rig_quota: Quota::new(6, 0.2),
            max_tracked_keys: 200_000,
            max_concurrent_verifications: 64,
            idle_timeout: Duration::from_secs(300),
            send_timeout: Duration::from_secs(5),
            trust_forwarded_for: false,
            stale_chain_after: Duration::from_secs(30),
        }
    }
}

/// Shared intake state.
pub struct Intake<S: RigSource> {
    cfg: IntakeConfig,
    verifier: Verifier<S>,
    ip_limiter: KeyedLimiter<IpAddr>,
    rig_limiter: KeyedLimiter<Address>,
    verify_permits: Semaphore,
    conn_permits: Arc<Semaphore>,
    per_ip: Mutex<HashMap<IpAddr, u32>>,
    metrics: Arc<Metrics>,
    breaker: Arc<Breaker>,
    chain: watch::Receiver<ChainView>,
    mirror: Arc<dyn HeartbeatMirror>,
}

#[derive(Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
enum ClientMessage {
    Heartbeat(HeartbeatSubmission),
    Status,
}

impl<S: RigSource> Intake<S> {
    /// Assemble the intake.
    pub fn new(
        cfg: IntakeConfig,
        verifier: Verifier<S>,
        metrics: Arc<Metrics>,
        breaker: Arc<Breaker>,
        chain: watch::Receiver<ChainView>,
        mirror: Arc<dyn HeartbeatMirror>,
    ) -> Arc<Self> {
        Arc::new(Intake {
            ip_limiter: KeyedLimiter::new(cfg.ip_quota, cfg.max_tracked_keys),
            rig_limiter: KeyedLimiter::new(cfg.rig_quota, cfg.max_tracked_keys),
            verify_permits: Semaphore::new(cfg.max_concurrent_verifications.max(1)),
            conn_permits: Arc::new(Semaphore::new(cfg.max_connections.max(1))),
            per_ip: Mutex::new(HashMap::new()),
            cfg,
            verifier,
            metrics,
            breaker,
            chain,
            mirror,
        })
    }

    /// The verifier (the crank loop seeds its rig cache and reads its store).
    pub fn verifier(&self) -> &Verifier<S> {
        &self.verifier
    }

    fn reject(&self, r: Reject) -> Reject {
        self.metrics.heartbeats_rejected.inc(r.reason());
        r
    }

    /// Handle one text frame from `ip`; returns the JSON reply.
    pub async fn handle_text(&self, ip: IpAddr, text: &str) -> String {
        if text.len() > self.cfg.max_message_bytes {
            return error_json(self.reject(Reject::TooLarge));
        }
        if !self.ip_limiter.check(&ip_key(ip)) {
            return error_json(self.reject(Reject::RateLimitedIp));
        }
        let msg: ClientMessage = match serde_json::from_str(text) {
            Ok(m) => m,
            Err(_) => return error_json(self.reject(Reject::Malformed)),
        };
        let sub = match msg {
            ClientMessage::Status => return self.status_json(),
            ClientMessage::Heartbeat(s) => s,
        };
        let parsed = match ParsedHeartbeat::parse(&sub) {
            Ok(p) => p,
            Err(r) => return ack_json(&sub.rig, sub.counter, Err(self.reject(r))),
        };
        if !self.rig_limiter.check(&parsed.rig) {
            return ack_json(&sub.rig, sub.counter, Err(self.reject(Reject::RateLimitedRig)));
        }
        let Ok(_permit) = self.verify_permits.try_acquire() else {
            return ack_json(&sub.rig, sub.counter, Err(self.reject(Reject::Busy)));
        };
        let round = self.chain.borrow().board.map(|b| b.round_id);
        match self.verifier.process(&parsed, round).await {
            Ok(v) => {
                self.metrics.heartbeats_accepted.inc();
                self.metrics.heartbeats_held.set_u64(self.verifier.store.len() as u64);
                self.mirror.mirror(&v);
                ack_json(&sub.rig, sub.counter, Ok(()))
            }
            Err(r) => ack_json(&sub.rig, sub.counter, Err(self.reject(r))),
        }
    }

    fn status_json(&self) -> String {
        let v = self.chain.borrow().clone();
        json!({
            "type": "status",
            "round_id": v.board.map(|b| b.round_id),
            "start_slot": v.board.map(|b| b.start_slot),
            "end_slot": v.board.and_then(|b| b.started().then_some(b.end_slot)),
            "slot": v.slot,
            "ema_ev": v.ema_ev(),
        })
        .to_string()
    }

    fn try_open(self: &Arc<Self>, ip: IpAddr) -> Result<ConnGuard<S>, StatusCode> {
        let permit = self.conn_permits.clone().try_acquire_owned().map_err(|_| StatusCode::SERVICE_UNAVAILABLE)?;
        let key = ip_key(ip);
        {
            let mut m = match self.per_ip.lock() {
                Ok(m) => m,
                Err(p) => p.into_inner(),
            };
            let n = m.entry(key).or_insert(0);
            if *n >= self.cfg.max_connections_per_ip {
                return Err(StatusCode::TOO_MANY_REQUESTS);
            }
            *n += 1;
        }
        self.metrics.intake_connections.add(1);
        Ok(ConnGuard { intake: self.clone(), key, _permit: permit })
    }
}

/// Releases the per-IP slot and the global permit even if the upgrade never completes.
struct ConnGuard<S: RigSource> {
    intake: Arc<Intake<S>>,
    key: IpAddr,
    _permit: OwnedSemaphorePermit,
}

impl<S: RigSource> Drop for ConnGuard<S> {
    fn drop(&mut self) {
        if let Ok(mut m) = self.intake.per_ip.lock() {
            if let Some(n) = m.get_mut(&self.key) {
                *n = n.saturating_sub(1);
                if *n == 0 {
                    m.remove(&self.key);
                }
            }
        }
        self.intake.metrics.intake_connections.add(-1);
    }
}

fn error_json(r: Reject) -> String {
    json!({ "type": "error", "reason": r.reason() }).to_string()
}

fn ack_json(rig: &str, counter: u64, r: Result<(), Reject>) -> String {
    // Echo the rig only if it is plausibly an address (never reflect arbitrary input).
    let rig = if rig.len() <= 44 && rig.chars().all(|c| c.is_ascii_alphanumeric()) { rig } else { "" };
    match r {
        Ok(()) => json!({ "type": "ack", "rig": rig, "counter": counter, "status": "accepted" }),
        Err(e) => json!({ "type": "ack", "rig": rig, "counter": counter, "status": "rejected", "reason": e.reason() }),
    }
    .to_string()
}

/// Client IP: the socket peer, or the last `X-Forwarded-For` hop when configured.
pub fn client_ip(peer: SocketAddr, headers: &HeaderMap, trust_forwarded_for: bool) -> IpAddr {
    if trust_forwarded_for {
        if let Some(ip) = headers
            .get("x-forwarded-for")
            .and_then(|v| v.to_str().ok())
            .and_then(|v| v.rsplit(',').next())
            .and_then(|s| s.trim().parse::<IpAddr>().ok())
        {
            return ip;
        }
    }
    peer.ip()
}

async fn ws_handler<S: RigSource>(
    ws: WebSocketUpgrade,
    ConnectInfo(peer): ConnectInfo<SocketAddr>,
    headers: HeaderMap,
    State(st): State<Arc<Intake<S>>>,
) -> Response {
    let ip = client_ip(peer, &headers, st.cfg.trust_forwarded_for);
    let guard = match st.try_open(ip) {
        Ok(g) => g,
        Err(code) => return (code, "connection limit").into_response(),
    };
    let max = st.cfg.max_message_bytes;
    ws.max_message_size(max)
        .max_frame_size(max)
        .on_upgrade(move |socket| async move {
            serve_socket(guard.intake.clone(), socket, ip).await;
            drop(guard);
        })
}

async fn serve_socket<S: RigSource>(st: Arc<Intake<S>>, mut socket: WebSocket, ip: IpAddr) {
    loop {
        let msg = match tokio::time::timeout(st.cfg.idle_timeout, socket.recv()).await {
            Ok(Some(Ok(m))) => m,
            _ => break, // idle, closed, oversize frame, or protocol error
        };
        let reply = match msg {
            Message::Text(t) => st.handle_text(ip, t.as_str()).await,
            Message::Binary(_) => error_json(st.reject(Reject::Malformed)),
            Message::Ping(_) | Message::Pong(_) => continue,
            Message::Close(_) => break,
        };
        match tokio::time::timeout(st.cfg.send_timeout, socket.send(Message::Text(reply.into()))).await {
            Ok(Ok(())) => {}
            _ => break,
        }
    }
}

async fn healthz<S: RigSource>(State(st): State<Arc<Intake<S>>>) -> (StatusCode, Json<Value>) {
    let v = st.chain.borrow().clone();
    let age = v.slot_age(Instant::now());
    let stale = age.is_none_or(|a| a > st.cfg.stale_chain_after);
    let tripped = st.breaker.is_tripped();
    let ok = !tripped && !stale && v.ready();
    let body = json!({
        "status": if ok { "ok" } else { "degraded" },
        "breaker": st.breaker.reason(),
        "chain_ready": v.ready(),
        "round_id": v.board.map(|b| b.round_id),
        "slot": v.slot,
        "slot_age_ms": age.map(|a| a.as_millis() as u64),
        "ema_ev": v.ema_ev(),
        "heartbeats_held": st.verifier.store.len(),
    });
    (if ok { StatusCode::OK } else { StatusCode::SERVICE_UNAVAILABLE }, Json(body))
}

async fn metrics<S: RigSource>(State(st): State<Arc<Intake<S>>>) -> impl IntoResponse {
    ([(header::CONTENT_TYPE, "text/plain; version=0.0.4")], st.metrics.render())
}

/// `/ws`, `/healthz`, `/metrics`.
pub fn router<S: RigSource>(intake: Arc<Intake<S>>) -> Router {
    Router::new()
        .route("/ws", get(ws_handler::<S>))
        .route("/healthz", get(healthz::<S>))
        .route("/metrics", get(metrics::<S>))
        .with_state(intake)
}

/// Serve `router` on `listener` with peer addresses available to handlers.
pub async fn serve(listener: tokio::net::TcpListener, router: Router) -> std::io::Result<()> {
    axum::serve(listener, router.into_make_service_with_connect_info::<SocketAddr>()).await
}
