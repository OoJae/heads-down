//! Phone intake (contract A): an axum WebSocket server on `/ws` (alias `/v1/heartbeats`),
//! plus `/healthz` and `/metrics`.
//!
//! No auth tokens: a message is only useful if it verifies against the rig's on-chain key, so
//! the intake's job is to be cheap to reject and hard to exhaust:
//!
//! - global and per-IP connection caps (IPv6 folded to /64), checked before the upgrade;
//! - a hard message/frame size cap (the socket errors on anything bigger);
//! - per-IP and per-rig token buckets before any RPC read or signature check;
//! - a bounded verification pool: when full the phone gets `rate_limited` instead of queueing;
//! - idle and send timeouts, so slow or silent clients cannot pin connections.
//!
//! Frames (JSON text; integers as numbers or decimal strings; unknown fields ignored):
//!
//! ```text
//! {"type":"heartbeat","rig","counter","shift_id","round_id","lease_rounds","sig64"}
//! {"type":"break","rig","counter","shift_id","reason","sig64"}      reason ∈ {1,2,4,5,6,7,8}
//! {"type":"freeze","rig","counter","shift_id","reason":3,"sig64"}
//! → {"type":"ack","counter":N,"ok":true|false,"reason":"<code>"}
//!   code ∈ {accepted, bad_signature, stale_counter, unknown_rig, rate_limited, malformed, lease_invalid}
//! ```
//!
//! Legacy spellings accepted: `kind` for `type`, `sig` for `sig64`, and a frame with neither
//! `type` nor `kind` is a heartbeat. `{"type":"status"}` returns the crank's view of the round
//! (a convenience only: phones should read `Board.round_id` themselves). A verified BREAK /
//! FREEZE is handed to the [`SignalHub`], which lands it on-chain.

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
use serde_json::{json, Value};
use solana_address::Address;
use tokio::sync::{watch, OwnedSemaphorePermit, Semaphore};

use crate::breaker::Breaker;
use crate::chain::ChainView;
use crate::hd::SignalKind;
use crate::heartbeat::{HeartbeatSubmission, ParsedHeartbeat, ParsedSignal, Reject, RigSource, SignalSubmission, Verifier, WindowRule};
use crate::metrics::Metrics;
use crate::mirror::HeartbeatMirror;
use crate::ratelimit::{ip_key, KeyedLimiter, Quota};
use crate::signal::{Offered, SignalHub};

/// `/healthz` reports `degraded` when the slot has not advanced for this long. The chain
/// watcher's HTTP poll interval is bounded by it ([`crate::config::MAX_CHAIN_POLL_SECS`]).
pub const STALE_CHAIN_AFTER: Duration = Duration::from_secs(30);

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
    /// Close a connection that has sent no text frame for this long. Control frames (Ping,
    /// Pong) do not count: a silent client cannot keep its slot by pinging.
    pub idle_timeout: Duration,
    /// Close a connection that has not delivered a message that verified under a rig's key
    /// within this long of connecting. A phone signs within one ORE round (about 78 s).
    pub unverified_timeout: Duration,
    /// Close a connection that does not read its acks within this long.
    pub send_timeout: Duration,
    /// Take the client IP from the last `X-Forwarded-For` hop (only behind a trusted proxy).
    pub trust_forwarded_for: bool,
    /// Take the client IP from `X-Real-IP` (only behind a proxy that sets it on every request;
    /// Railway documents it as the client's remote address). Wins over `trust_forwarded_for`.
    pub trust_real_ip: bool,
    /// `/healthz` reports degraded when the slot has not advanced for this long.
    pub stale_chain_after: Duration,
    /// Streak protection: a BREAK is accepted only while its rig's plan window is open.
    pub window_rule: WindowRule,
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
            unverified_timeout: Duration::from_secs(180),
            send_timeout: Duration::from_secs(5),
            trust_forwarded_for: false,
            trust_real_ip: false,
            stale_chain_after: STALE_CHAIN_AFTER,
            window_rule: WindowRule::default(),
        }
    }
}

/// Shared intake state.
pub struct Intake<S: RigSource> {
    cfg: IntakeConfig,
    verifier: Verifier<S>,
    signals: Arc<SignalHub>,
    ip_limiter: KeyedLimiter<IpAddr>,
    rig_limiter: KeyedLimiter<Address>,
    verify_permits: Semaphore,
    conn_permits: Arc<Semaphore>,
    per_ip: Mutex<HashMap<IpAddr, u32>>,
    metrics: Arc<Metrics>,
    breaker: Arc<Breaker>,
    chain: watch::Receiver<ChainView>,
    mirror: Arc<dyn HeartbeatMirror>,
    draining: std::sync::atomic::AtomicBool,
}

/// The counter to echo in an ack: a JSON number or decimal string, else 0.
fn counter_hint(v: &Value) -> u64 {
    match v.get("counter") {
        Some(Value::Number(n)) => n.as_u64().unwrap_or(0),
        Some(Value::String(s)) if s.len() <= 20 && s.bytes().all(|c| c.is_ascii_digit()) => s.parse().unwrap_or(0),
        _ => 0,
    }
}

/// `{"type":"ack","counter":N,"ok":…,"reason":…}` (contract A, exactly these four fields).
pub fn ack_json(counter: u64, r: Result<(), Reject>) -> String {
    match r {
        Ok(()) => json!({ "type": "ack", "counter": counter, "ok": true, "reason": "accepted" }),
        Err(e) => json!({ "type": "ack", "counter": counter, "ok": false, "reason": e.ack_code() }),
    }
    .to_string()
}

impl<S: RigSource> Intake<S> {
    /// Assemble the intake.
    pub fn new(
        cfg: IntakeConfig,
        verifier: Verifier<S>,
        signals: Arc<SignalHub>,
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
            signals,
            metrics,
            breaker,
            chain,
            mirror,
            draining: std::sync::atomic::AtomicBool::new(false),
        })
    }

    /// The verifier (the crank loop seeds its rig cache and reads its store).
    pub fn verifier(&self) -> &Verifier<S> {
        &self.verifier
    }

    /// The process is shutting down: answer `rate_limited` to new messages (the phone keeps
    /// its record and reconnects to the next instance) and report `draining` on `/healthz`,
    /// so the platform stops routing here. Messages already accepted are still landed.
    pub fn set_draining(&self) {
        self.draining.store(true, std::sync::atomic::Ordering::SeqCst);
    }

    /// True once [`Self::set_draining`] was called.
    pub fn is_draining(&self) -> bool {
        self.draining.load(std::sync::atomic::Ordering::SeqCst)
    }

    fn reject(&self, r: Reject) -> Reject {
        self.metrics.heartbeats_rejected.inc(r.reason());
        r
    }

    fn reject_signal(&self, r: Reject) -> Reject {
        self.metrics.signals_rejected.inc(r.reason());
        r
    }

    /// Handle one text frame from `ip`; returns the JSON reply.
    pub async fn handle_text(&self, ip: IpAddr, text: &str) -> String {
        self.handle_frame(ip, text).await.0
    }

    /// [`Self::handle_text`], also telling whether the frame carried a message that verified
    /// under a rig's key and was accepted (the connection has then proven it speaks for a rig).
    pub async fn handle_frame(&self, ip: IpAddr, text: &str) -> (String, bool) {
        if text.len() > self.cfg.max_message_bytes {
            return (ack_json(0, Err(self.reject(Reject::TooLarge))), false);
        }
        let v: Value = match serde_json::from_str(text) {
            Ok(v @ Value::Object(_)) => v,
            _ => return (ack_json(0, Err(self.reject(Reject::Malformed))), false),
        };
        let counter = counter_hint(&v);
        if self.is_draining() {
            return (ack_json(counter, Err(self.reject(Reject::Busy))), false);
        }
        if !self.ip_limiter.check(&ip_key(ip)) {
            return (ack_json(counter, Err(self.reject(Reject::RateLimitedIp))), false);
        }
        let ty = v.get("type").or_else(|| v.get("kind")).map(|t| t.as_str().unwrap_or("?"));
        let result = match ty {
            Some("status") => return (self.status_json(), false),
            Some("heartbeat") | None => self.heartbeat(v).await,
            Some("break") => self.signal(SignalKind::Break, v).await,
            Some("freeze") => self.signal(SignalKind::Freeze, v).await,
            Some(_) => Err(self.reject(Reject::Malformed)),
        };
        let verified = result.is_ok();
        (ack_json(counter, result), verified)
    }

    /// Where this intake takes a client's address from.
    pub fn client_ip_source(&self) -> ClientIpSource {
        self.cfg.client_ip_source()
    }

    async fn heartbeat(&self, v: Value) -> Result<(), Reject> {
        let sub: HeartbeatSubmission = serde_json::from_value(v).map_err(|_| self.reject(Reject::Malformed))?;
        let parsed = ParsedHeartbeat::parse(&sub).map_err(|r| self.reject(r))?;
        // Peek only: the rig's bucket is charged below, after the signature verified. Frames
        // that merely name a rig must not be able to spend its allowance.
        if !self.rig_limiter.has_token(&parsed.rig) {
            return Err(self.reject(Reject::RateLimitedRig));
        }
        // A BREAK / FREEZE with this or a higher counter was already accepted.
        if self.signals.max_counter(&parsed.rig).is_some_and(|c| c >= parsed.fields.counter) {
            return Err(self.reject(Reject::StaleCounter));
        }
        let Ok(_permit) = self.verify_permits.try_acquire() else {
            return Err(self.reject(Reject::Busy));
        };
        let round = self.chain.borrow().board.map(|b| b.round_id);
        let v = self.verifier.process(&parsed, round).await.map_err(|r| self.reject(r))?;
        let _ = self.rig_limiter.check(&parsed.rig); // authenticated: now it counts
        self.metrics.heartbeats_accepted.inc();
        self.metrics.heartbeats_held.set_u64(self.verifier.store.len() as u64);
        self.mirror.mirror(&v);
        Ok(())
    }

    async fn signal(&self, kind: SignalKind, v: Value) -> Result<(), Reject> {
        let sub: SignalSubmission = serde_json::from_value(v).map_err(|_| self.reject_signal(Reject::Malformed))?;
        let parsed = ParsedSignal::parse(kind, &sub).map_err(|r| self.reject_signal(r))?;
        self.signals.check_rate(&parsed.rig).map_err(|r| self.reject_signal(r))?;
        let Ok(_permit) = self.verify_permits.try_acquire() else {
            return Err(self.reject_signal(Reject::Busy));
        };
        let verified = self.verifier.process_signal(&parsed).await.map_err(|r| self.reject_signal(r))?;
        self.signals.charge_rate(&parsed.rig); // authenticated: now it counts
        // Streak protection: a BREAK whose rig's plan window has ended is never landed (it
        // would turn a completed night into a break). Contract A has no "accepted but
        // ignored" code, so the phone gets `ok: false, reason: lease_invalid`.
        let now = self.chain.borrow().unix_now();
        if let Err(r) = self.cfg.window_rule.check(&verified, now) {
            tracing::info!(
                rig = %parsed.rig,
                counter = parsed.counter,
                reason = parsed.reason,
                window_end = verified.plan_window_end_ts,
                now,
                "BREAK after the plan window: not landed (streak protection)"
            );
            return Err(self.reject_signal(r));
        }
        match self.signals.offer(verified).map_err(|r| self.reject_signal(r))? {
            Offered::Queued => {
                self.metrics.signals_accepted.inc(kind.name());
                tracing::info!(rig = %parsed.rig, kind = kind.name(), counter = parsed.counter, reason = parsed.reason, "signal accepted for landing");
            }
            Offered::Duplicate | Offered::Moot => {}
        }
        Ok(())
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

/// Where a client's address is read from. Every per-IP limit is keyed on it, and those limits
/// are the intake's only protection before a signature has been verified, so it must be a value
/// the client cannot choose.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ClientIpSource {
    /// The socket peer: right when clients connect directly.
    Peer,
    /// `X-Real-IP`: right behind an edge that sets it on every request (Railway documents it
    /// as the client's remote address).
    RealIp,
    /// The last hop of `X-Forwarded-For`: right behind a proxy that appends the address it saw.
    ForwardedFor,
}

impl IntakeConfig {
    /// `X-Real-IP` when trusted, else `X-Forwarded-For` when trusted, else the socket peer.
    pub fn client_ip_source(&self) -> ClientIpSource {
        if self.trust_real_ip {
            ClientIpSource::RealIp
        } else if self.trust_forwarded_for {
            ClientIpSource::ForwardedFor
        } else {
            ClientIpSource::Peer
        }
    }
}

/// Client IP: the socket peer, or the value the trusted proxy wrote.
///
/// A header can arrive as several lines. The proxy's own line is the **last** one (a client can
/// only add lines before it), and within `X-Forwarded-For` its hop is the last element. Reading
/// the first line, as an earlier version did, let a client that sent its own `X-Forwarded-For`
/// choose the address its limits were keyed on. A missing or malformed header falls back to the
/// socket peer.
pub fn client_ip(peer: SocketAddr, headers: &HeaderMap, source: ClientIpSource) -> IpAddr {
    let last_line = |name: &str| headers.get_all(name).iter().next_back().and_then(|v| v.to_str().ok());
    let parsed = match source {
        ClientIpSource::Peer => None,
        ClientIpSource::RealIp => last_line("x-real-ip").and_then(|s| s.trim().parse::<IpAddr>().ok()),
        ClientIpSource::ForwardedFor => {
            last_line("x-forwarded-for").and_then(|v| v.rsplit(',').next()).and_then(|s| s.trim().parse::<IpAddr>().ok())
        }
    };
    parsed.unwrap_or_else(|| peer.ip())
}

async fn ws_handler<S: RigSource>(
    ws: WebSocketUpgrade,
    ConnectInfo(peer): ConnectInfo<SocketAddr>,
    headers: HeaderMap,
    State(st): State<Arc<Intake<S>>>,
) -> Response {
    let ip = client_ip(peer, &headers, st.cfg.client_ip_source());
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
    let opened = tokio::time::Instant::now();
    // Moved only by a text frame: a client that only pings is idle.
    let mut last_text = opened;
    // Set by the first message that verified under a rig's key.
    let mut verified = false;
    loop {
        let idle_at = last_text + st.cfg.idle_timeout;
        let deadline = if verified { idle_at } else { idle_at.min(opened + st.cfg.unverified_timeout) };
        let msg = match tokio::time::timeout_at(deadline, socket.recv()).await {
            Ok(Some(Ok(m))) => m,
            _ => break, // idle, never verified, closed, oversize frame, or protocol error
        };
        let reply = match msg {
            Message::Text(t) => {
                last_text = tokio::time::Instant::now();
                let (reply, ok) = st.handle_frame(ip, t.as_str()).await;
                verified |= ok;
                reply
            }
            Message::Binary(_) => ack_json(0, Err(st.reject(Reject::Malformed))),
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
    let draining = st.is_draining();
    let ok = !tripped && !stale && v.ready() && !draining;
    let m = &st.metrics;
    let body = json!({
        "status": if draining { "draining" } else if ok { "ok" } else { "degraded" },
        "version": env!("CARGO_PKG_VERSION"),
        "breaker": st.breaker.reason(),
        "chain_ready": v.ready(),
        "round_id": v.board.map(|b| b.round_id),
        "slot": v.slot,
        "slot_age_ms": age.map(|a| a.as_millis() as u64),
        "cluster_unix_ts": v.cluster_now(Instant::now()),
        "ema_ev": v.ema_ev(),
        "heartbeats_held": st.verifier.store.len(),
        "signals_enabled": st.signals.enabled(),
        "signal_budget_lamports": st.signals.budget_available(),
        "stack": {
            "tables_open": m.stack_tables_open.get(),
            "tables_active": m.stack_tables_active.get(),
            "seats_active": m.stack_seats_active.get(),
            "seats_pending": m.stack_seats_pending.get(),
        },
        "in_flight": m.in_flight.get(),
    });
    (if ok { StatusCode::OK } else { StatusCode::SERVICE_UNAVAILABLE }, Json(body))
}

/// The address the intake keys this caller's limits on. For the deployment check: behind the
/// right proxy setting it is the caller's own address and does not change when the caller sends
/// `X-Real-IP` or `X-Forwarded-For` headers of its own (docs/DEPLOY.md).
async fn whoami<S: RigSource>(ConnectInfo(peer): ConnectInfo<SocketAddr>, headers: HeaderMap, State(st): State<Arc<Intake<S>>>) -> Json<Value> {
    let ip = ip_key(client_ip(peer, &headers, st.cfg.client_ip_source()));
    Json(json!({ "ip": ip.to_string() }))
}

async fn metrics<S: RigSource>(State(st): State<Arc<Intake<S>>>) -> impl IntoResponse {
    ([(header::CONTENT_TYPE, "text/plain; version=0.0.4")], st.metrics.render())
}

/// `/ws` (and its alias `/v1/heartbeats`), `/healthz`, `/metrics`, `/whoami`.
pub fn router<S: RigSource>(intake: Arc<Intake<S>>) -> Router {
    Router::new()
        .route("/ws", get(ws_handler::<S>))
        .route("/v1/heartbeats", get(ws_handler::<S>))
        .route("/healthz", get(healthz::<S>))
        .route("/whoami", get(whoami::<S>))
        .route("/metrics", get(metrics::<S>))
        .with_state(intake)
}

/// Serve `router` on `listener` with peer addresses available to handlers.
pub async fn serve(listener: tokio::net::TcpListener, router: Router) -> std::io::Result<()> {
    axum::serve(listener, router.into_make_service_with_connect_info::<SocketAddr>()).await
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_client_address_is_the_proxys_line_never_the_clients() {
        let peer: SocketAddr = "10.0.0.7:5000".parse().unwrap();
        let ip = |s: &str| s.parse::<IpAddr>().unwrap();
        let mut h = HeaderMap::new();
        // The client sent its own lines; the proxy's are the last ones.
        h.append("x-forwarded-for", "198.51.100.66".parse().unwrap());
        h.append("x-forwarded-for", "198.51.100.67, 203.0.113.9".parse().unwrap());
        h.append("x-real-ip", "198.51.100.66".parse().unwrap());
        h.append("x-real-ip", "203.0.113.9".parse().unwrap());
        assert_eq!(client_ip(peer, &h, ClientIpSource::Peer), ip("10.0.0.7"), "no proxy trusted: the socket peer");
        assert_eq!(client_ip(peer, &h, ClientIpSource::RealIp), ip("203.0.113.9"));
        assert_eq!(client_ip(peer, &h, ClientIpSource::ForwardedFor), ip("203.0.113.9"), "last hop of the last line");
        // A missing or malformed header falls back to the peer; it never yields a client-chosen key.
        let mut bad = HeaderMap::new();
        bad.append("x-real-ip", "not-an-address".parse().unwrap());
        bad.append("x-forwarded-for", "203.0.113.9, garbage".parse().unwrap());
        assert_eq!(client_ip(peer, &bad, ClientIpSource::RealIp), ip("10.0.0.7"));
        assert_eq!(client_ip(peer, &bad, ClientIpSource::ForwardedFor), ip("10.0.0.7"));
        assert_eq!(client_ip(peer, &HeaderMap::new(), ClientIpSource::RealIp), ip("10.0.0.7"));
        // X-Real-IP wins when both are trusted (the config refuses that combination anyway).
        let both = IntakeConfig { trust_real_ip: true, trust_forwarded_for: true, ..IntakeConfig::default() };
        assert_eq!(both.client_ip_source(), ClientIpSource::RealIp);
        assert_eq!(IntakeConfig::default().client_ip_source(), ClientIpSource::Peer);
    }

    #[test]
    fn acks_have_exactly_the_contract_fields() {
        let ok: Value = serde_json::from_str(&ack_json(7, Ok(()))).unwrap();
        assert_eq!(ok, json!({ "type": "ack", "counter": 7, "ok": true, "reason": "accepted" }));
        let no: Value = serde_json::from_str(&ack_json(u64::MAX, Err(Reject::Expired))).unwrap();
        assert_eq!(no, json!({ "type": "ack", "counter": u64::MAX, "ok": false, "reason": "lease_invalid" }));
        assert_eq!(counter_hint(&json!({ "counter": "18446744073709551615" })), u64::MAX);
        assert_eq!(counter_hint(&json!({ "counter": -3 })), 0);
        assert_eq!(counter_hint(&json!({})), 0);
    }
}
