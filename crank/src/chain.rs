//! Chain watcher: ORE Board, Treasury, ORE Config and the current Round.
//!
//! Transport sits behind [`ChainSource`]: [`WsChainSource`] uses Solana RPC WebSocket
//! `accountSubscribe` + `slotSubscribe`; a Helius LaserStream gRPC source can implement
//! the same trait (feature `laserstream`, stub for now). The state machine ([`Watcher`])
//! is pure: it decodes every update through the pinned ORE decoders (a deviation trips the
//! breaker), follows `Board.round_id` to re-subscribe to the new Round PDA, and exposes a
//! [`ChainView`] with the round window and the gate value.
//!
//! [`run_watcher`] also polls the same accounts over HTTP on a timer and after every
//! reconnect, so a silently stalled WebSocket cannot freeze the crank's view.

use std::collections::{BTreeSet, HashMap};
use std::sync::Arc;
use std::time::{Duration, Instant};

use async_trait::async_trait;
use futures_util::{SinkExt, StreamExt};
use serde_json::{json, Value};
use solana_address::Address;
use tokio::sync::{mpsc, watch};
use tokio_tungstenite::tungstenite::{protocol::WebSocketConfig, Message};

use crate::account::RawAccount;
use crate::breaker::Breaker;
use crate::gate;
use crate::metrics::Metrics;
use crate::ore::{self, Board, OreConfig, Round, Treasury};
use crate::rpc::{self, redact_url, RpcClient};

/// Something that happened on chain.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum ChainEvent {
    /// Account update (`None` = the account no longer exists).
    Account {
        /// Which account.
        address: Address,
        /// Context slot.
        slot: u64,
        /// New state.
        account: Option<RawAccount>,
    },
    /// New slot.
    Slot(u64),
    /// The stream (re)connected; state may have changed while it was down.
    Reconnected,
}

/// Subscription changes the watcher asks the source for.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum SubCommand {
    /// Start streaming an account.
    Subscribe(Address),
    /// Stop streaming an account.
    Unsubscribe(Address),
}

/// A stream of account and slot updates.
#[async_trait]
pub trait ChainSource: Send + Sync + 'static {
    /// Run until `commands` closes; reconnect internally on errors.
    async fn run(&self, commands: mpsc::Receiver<SubCommand>, events: mpsc::Sender<ChainEvent>) -> anyhow::Result<()>;
}

/// The crank's view of ORE.
#[derive(Clone, Debug, Default)]
pub struct ChainView {
    /// Latest slot.
    pub slot: u64,
    /// Board.
    pub board: Option<Board>,
    /// Treasury.
    pub treasury: Option<Treasury>,
    /// Current Round (id == board.round_id).
    pub round: Option<Round>,
    /// ORE Config.
    pub ore_config: Option<OreConfig>,
    /// When any account last changed.
    pub last_account_update: Option<Instant>,
    /// When the slot last advanced.
    pub last_slot_update: Option<Instant>,
}

impl ChainView {
    /// Gate value for this round (`None` until Board and Treasury are known, or on overflow).
    pub fn ema_ev(&self) -> Option<u64> {
        let (b, t) = (self.board?, self.treasury?);
        gate::ema_ev(b.production_cost_ema, t.motherlode)
    }

    /// Board, Treasury and ORE Config all known.
    pub fn ready(&self) -> bool {
        self.board.is_some() && self.treasury.is_some() && self.ore_config.is_some()
    }

    /// Slots until `end_slot` (`None` if the round has not started).
    pub fn slots_left(&self) -> Option<u64> {
        let b = self.board?;
        b.started().then(|| b.end_slot.saturating_sub(self.slot))
    }

    /// Seconds since the slot last advanced.
    pub fn slot_age(&self, now: Instant) -> Option<Duration> {
        self.last_slot_update.map(|t| now.saturating_duration_since(t))
    }
}

/// Pure watcher state machine.
pub struct Watcher {
    /// Current view.
    pub view: ChainView,
    round_sub: Option<(u64, Address)>,
    breaker: Arc<Breaker>,
    metrics: Arc<Metrics>,
}

impl Watcher {
    /// New watcher.
    pub fn new(breaker: Arc<Breaker>, metrics: Arc<Metrics>) -> Self {
        Watcher { view: ChainView::default(), round_sub: None, breaker, metrics }
    }

    /// Accounts to subscribe to at start.
    pub fn initial_subscriptions() -> Vec<Address> {
        vec![ore::BOARD_ADDRESS, ore::TREASURY_ADDRESS, ore::CONFIG_ADDRESS]
    }

    /// The Round PDA currently followed.
    pub fn round_address(&self) -> Option<Address> {
        self.round_sub.map(|(_, a)| a)
    }

    /// Apply one event; returns subscription changes.
    pub fn apply(&mut self, ev: ChainEvent, now: Instant) -> Vec<SubCommand> {
        let mut cmds = Vec::new();
        match ev {
            ChainEvent::Slot(s) => {
                if s > self.view.slot {
                    self.view.slot = s;
                    self.view.last_slot_update = Some(now);
                    self.metrics.slot.set_u64(s);
                }
            }
            ChainEvent::Reconnected => self.metrics.chain_reconnects.inc(),
            ChainEvent::Account { address, slot, account } => {
                if slot > self.view.slot {
                    self.view.slot = slot;
                    self.view.last_slot_update = Some(now);
                }
                self.apply_account(address, account, now, &mut cmds);
            }
        }
        cmds
    }

    fn apply_account(&mut self, address: Address, account: Option<RawAccount>, now: Instant, cmds: &mut Vec<SubCommand>) {
        let singleton = |a: &Address| {
            if a == &ore::BOARD_ADDRESS {
                Some("board")
            } else if a == &ore::TREASURY_ADDRESS {
                Some("treasury")
            } else if a == &ore::CONFIG_ADDRESS {
                Some("ore_config")
            } else {
                None
            }
        };
        if let Some(label) = singleton(&address) {
            self.metrics.chain_updates.inc(label);
            let Some(acc) = account else {
                self.breaker.trip(format!("{label} account missing"));
                return;
            };
            if address == ore::BOARD_ADDRESS {
                if let Some(b) = self.breaker.observe("board", Board::decode(&acc.owner, &acc.data)) {
                    self.metrics.board_round_id.set_u64(b.round_id);
                    if self.round_sub.map(|(id, _)| id) != Some(b.round_id) {
                        if let Some((_, old)) = self.round_sub.take() {
                            cmds.push(SubCommand::Unsubscribe(old));
                        }
                        let pda = ore::round_pda(b.round_id);
                        self.round_sub = Some((b.round_id, pda));
                        cmds.push(SubCommand::Subscribe(pda));
                        if self.view.round.as_ref().is_some_and(|r| r.id != b.round_id) {
                            self.view.round = None;
                        }
                    }
                    self.view.board = Some(b);
                }
            } else if address == ore::TREASURY_ADDRESS {
                if let Some(t) = self.breaker.observe("treasury", Treasury::decode(&acc.owner, &acc.data)) {
                    self.metrics.motherlode.set_u64(t.motherlode);
                    self.view.treasury = Some(t);
                }
            } else if let Some(c) = self.breaker.observe("ore_config", OreConfig::decode(&acc.owner, &acc.data)) {
                self.view.ore_config = Some(c);
            }
            if let Some(ev) = self.view.ema_ev() {
                self.metrics.ema_ev_lamports.set_u64(ev);
            }
            self.view.last_account_update = Some(now);
            return;
        }
        // The followed Round.
        if let Some((id, pda)) = self.round_sub {
            if address == pda {
                self.metrics.chain_updates.inc("round");
                // A Round that does not exist yet (before reset creates it) is not an error.
                if let Some(acc) = account.filter(|a| !a.is_closed()) {
                    if let Some(r) = self.breaker.observe("round", Round::decode(&acc.owner, &acc.data)) {
                        if r.id == id {
                            self.view.round = Some(r);
                            self.view.last_account_update = Some(now);
                        } else {
                            self.breaker.trip(format!("round PDA for {id} holds id {}", r.id));
                        }
                    }
                }
            }
        }
    }
}

/// Solana RPC WebSocket source.
pub struct WsChainSource {
    url: String,
    commitment: String,
    /// Reconnect if nothing arrives for this long.
    pub idle_timeout: Duration,
}

impl WsChainSource {
    /// New source for `url` (treated as a secret).
    pub fn new(url: impl Into<String>, commitment: impl Into<String>) -> Self {
        WsChainSource { url: url.into(), commitment: commitment.into(), idle_timeout: Duration::from_secs(30) }
    }
}

fn account_subscribe(id: u64, a: &Address, commitment: &str) -> String {
    json!({ "jsonrpc": "2.0", "id": id, "method": "accountSubscribe",
            "params": [a.to_string(), { "encoding": "base64", "commitment": commitment }] })
    .to_string()
}

/// What an in-flight subscribe request was for.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Pending {
    Slot,
    Account(Address),
}

/// Parse one WebSocket text message into events, tracking subscription ids.
fn handle_ws_text(
    text: &str,
    pending: &mut HashMap<u64, Pending>,
    subs: &mut HashMap<u64, Address>,
) -> Vec<ChainEvent> {
    let Ok(v) = serde_json::from_str::<Value>(text) else {
        return vec![];
    };
    if let (Some(id), Some(result)) = (v["id"].as_u64(), v.get("result")) {
        if let (Some(Pending::Account(a)), Some(sub)) = (pending.remove(&id), result.as_u64()) {
            subs.insert(sub, a);
        }
        return vec![];
    }
    match v["method"].as_str() {
        Some("slotNotification") => v["params"]["result"]["slot"].as_u64().map(ChainEvent::Slot).into_iter().collect(),
        Some("accountNotification") => {
            let p = &v["params"];
            let Some(address) = p["subscription"].as_u64().and_then(|s| subs.get(&s)).copied() else {
                return vec![];
            };
            let slot = p["result"]["context"]["slot"].as_u64().unwrap_or(0);
            match rpc::decode_account(&p["result"]["value"]) {
                Ok(account) => vec![ChainEvent::Account { address, slot, account }],
                Err(_) => vec![],
            }
        }
        _ => vec![],
    }
}

#[async_trait]
impl ChainSource for WsChainSource {
    async fn run(&self, mut commands: mpsc::Receiver<SubCommand>, events: mpsc::Sender<ChainEvent>) -> anyhow::Result<()> {
        let mut wanted: BTreeSet<Address> = BTreeSet::new();
        let mut backoff = Duration::from_millis(500);
        let cfg = WebSocketConfig::default().max_message_size(Some(1 << 20)).max_frame_size(Some(1 << 20));
        loop {
            let conn = tokio_tungstenite::connect_async_with_config(self.url.as_str(), Some(cfg), true).await;
            let (ws, _) = match conn {
                Ok(c) => c,
                Err(e) => {
                    tracing::warn!(url = %redact_url(&self.url), error = %rpc::scrub(&e.to_string(), &self.url), "ws connect failed");
                    tokio::time::sleep(backoff).await;
                    backoff = (backoff * 2).min(Duration::from_secs(30));
                    // Keep draining commands while disconnected.
                    while let Ok(c) = commands.try_recv() {
                        match c {
                            SubCommand::Subscribe(a) => wanted.insert(a),
                            SubCommand::Unsubscribe(a) => wanted.remove(&a),
                        };
                    }
                    continue;
                }
            };
            backoff = Duration::from_millis(500);
            if events.send(ChainEvent::Reconnected).await.is_err() {
                return Ok(());
            }
            let (mut sink, mut stream) = ws.split();
            let mut next_id = 1u64;
            let mut pending: HashMap<u64, Pending> = HashMap::new();
            let mut subs: HashMap<u64, Address> = HashMap::new();
            let sub = json!({ "jsonrpc": "2.0", "id": next_id, "method": "slotSubscribe" }).to_string();
            pending.insert(next_id, Pending::Slot);
            next_id += 1;
            let mut ok = sink.send(Message::text(sub)).await.is_ok();
            for a in &wanted {
                pending.insert(next_id, Pending::Account(*a));
                ok &= sink.send(Message::text(account_subscribe(next_id, a, &self.commitment))).await.is_ok();
                next_id += 1;
            }
            let mut ping = tokio::time::interval(Duration::from_secs(15));
            while ok {
                tokio::select! {
                    cmd = commands.recv() => match cmd {
                        None => return Ok(()),
                        Some(SubCommand::Subscribe(a)) => {
                            if wanted.insert(a) {
                                pending.insert(next_id, Pending::Account(a));
                                ok = sink.send(Message::text(account_subscribe(next_id, &a, &self.commitment))).await.is_ok();
                                next_id += 1;
                            }
                        }
                        Some(SubCommand::Unsubscribe(a)) => {
                            wanted.remove(&a);
                            if let Some(id) = subs.iter().find(|(_, x)| **x == a).map(|(id, _)| *id) {
                                subs.remove(&id);
                                let m = json!({ "jsonrpc": "2.0", "id": next_id, "method": "accountUnsubscribe", "params": [id] });
                                next_id += 1;
                                ok = sink.send(Message::text(m.to_string())).await.is_ok();
                            }
                        }
                    },
                    msg = tokio::time::timeout(self.idle_timeout, stream.next()) => match msg {
                        Ok(Some(Ok(Message::Text(t)))) => {
                            for ev in handle_ws_text(t.as_str(), &mut pending, &mut subs) {
                                if events.send(ev).await.is_err() {
                                    return Ok(());
                                }
                            }
                        }
                        Ok(Some(Ok(Message::Ping(p)))) => ok = sink.send(Message::Pong(p)).await.is_ok(),
                        Ok(Some(Ok(Message::Close(_)))) | Ok(None) | Ok(Some(Err(_))) => ok = false,
                        Ok(Some(Ok(_))) => {}
                        Err(_) => {
                            tracing::warn!("ws idle for {:?}; reconnecting", self.idle_timeout);
                            ok = false;
                        }
                    },
                    _ = ping.tick() => ok = sink.send(Message::Ping(Vec::new().into())).await.is_ok(),
                }
            }
            tracing::warn!(url = %redact_url(&self.url), "ws disconnected; reconnecting");
            tokio::time::sleep(backoff).await;
        }
    }
}

/// Helius LaserStream (gRPC) source. **Stub**: the trait is the integration point; the
/// Yellowstone gRPC client is not vendored in this build. See README.
#[cfg(feature = "laserstream")]
pub struct LaserStreamSource {
    /// gRPC endpoint.
    pub endpoint: String,
}

#[cfg(feature = "laserstream")]
#[async_trait]
impl ChainSource for LaserStreamSource {
    async fn run(&self, _commands: mpsc::Receiver<SubCommand>, _events: mpsc::Sender<ChainEvent>) -> anyhow::Result<()> {
        anyhow::bail!("LaserStream source is a stub in this build; use the WebSocket source")
    }
}

/// Fetch Board, Treasury, ORE Config, the followed Round and the slot over HTTP, as events.
pub async fn poll_events(rpc: &RpcClient, round: Option<Address>) -> anyhow::Result<Vec<ChainEvent>> {
    let mut keys = Watcher::initial_subscriptions();
    keys.extend(round);
    let slot = rpc.get_slot(rpc.commitment()).await?;
    let accs = rpc.get_multiple_accounts(&keys).await?;
    let mut evs = vec![ChainEvent::Slot(slot)];
    evs.extend(keys.into_iter().zip(accs).map(|(address, account)| ChainEvent::Account { address, slot, account }));
    Ok(evs)
}

/// Drive a [`Watcher`] from a [`ChainSource`] plus HTTP polling, publishing views.
pub async fn run_watcher(
    source: Arc<dyn ChainSource>,
    rpc: RpcClient,
    out: watch::Sender<ChainView>,
    breaker: Arc<Breaker>,
    metrics: Arc<Metrics>,
    poll_every: Duration,
) -> anyhow::Result<()> {
    let (cmd_tx, cmd_rx) = mpsc::channel(64);
    let (ev_tx, mut ev_rx) = mpsc::channel(1024);
    for a in Watcher::initial_subscriptions() {
        cmd_tx.send(SubCommand::Subscribe(a)).await?;
    }
    let src = source.clone();
    tokio::spawn(async move {
        if let Err(e) = src.run(cmd_rx, ev_tx).await {
            tracing::error!(error = %e, "chain source stopped");
        }
    });
    let mut w = Watcher::new(breaker, metrics);
    let mut poll = tokio::time::interval(poll_every);
    loop {
        let evs: Vec<ChainEvent> = tokio::select! {
            ev = ev_rx.recv() => match ev {
                Some(ChainEvent::Reconnected) => {
                    // Anything may have changed while disconnected: re-read over HTTP.
                    let mut v = vec![ChainEvent::Reconnected];
                    v.extend(poll_events(&rpc, w.round_address()).await.unwrap_or_default());
                    v
                }
                Some(e) => vec![e],
                None => anyhow::bail!("chain source ended"),
            },
            _ = poll.tick() => match poll_events(&rpc, w.round_address()).await {
                Ok(v) => v,
                Err(e) => {
                    tracing::warn!(error = %e, "chain poll failed");
                    vec![]
                }
            },
        };
        for ev in evs {
            for c in w.apply(ev, Instant::now()) {
                // The round PDA changed: also fetch it right away.
                if let SubCommand::Subscribe(a) = &c {
                    if let Ok(acc) = rpc.get_account(a).await {
                        let slot = w.view.slot;
                        let _ = w.apply(ChainEvent::Account { address: *a, slot, account: acc }, Instant::now());
                    }
                }
                let _ = cmd_tx.send(c).await;
            }
        }
        out.send_replace(w.view.clone());
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn board_bytes(round_id: u64, start: u64, end: u64, ema: u64) -> Vec<u8> {
        let mut d = vec![0u8; 40];
        d[0] = 105;
        d[8..16].copy_from_slice(&round_id.to_le_bytes());
        d[16..24].copy_from_slice(&start.to_le_bytes());
        d[24..32].copy_from_slice(&end.to_le_bytes());
        d[32..40].copy_from_slice(&ema.to_le_bytes());
        d
    }

    fn acc(data: Vec<u8>) -> Option<RawAccount> {
        Some(RawAccount { owner: ore::ORE_PROGRAM_ID, lamports: 1, data })
    }

    fn watcher() -> (Watcher, Arc<Breaker>) {
        let m = Arc::new(Metrics::default());
        let b = Arc::new(Breaker::new(m.clone()));
        (Watcher::new(b.clone(), m), b)
    }

    #[test]
    fn follows_the_round_and_computes_the_gate() {
        let (mut w, b) = watcher();
        let now = Instant::now();
        let cmds = w.apply(
            ChainEvent::Account { address: ore::BOARD_ADDRESS, slot: 10, account: acc(board_bytes(7, 100, 340, 900_000_000)) },
            now,
        );
        assert_eq!(cmds, vec![SubCommand::Subscribe(ore::round_pda(7))]);
        let mut t = vec![0u8; 48];
        t[0] = 104;
        t[8..16].copy_from_slice(&(344 * ore::ONE_ORE).to_le_bytes());
        w.apply(ChainEvent::Account { address: ore::TREASURY_ADDRESS, slot: 11, account: acc(t) }, now);
        assert_eq!(w.view.ema_ev(), gate::ema_ev(900_000_000, 344 * ore::ONE_ORE));
        w.apply(ChainEvent::Slot(300), now);
        assert_eq!(w.view.slots_left(), Some(40));
        // Same round: no new subscription. Next round: swap.
        assert!(w.apply(ChainEvent::Account { address: ore::BOARD_ADDRESS, slot: 301, account: acc(board_bytes(7, 100, 340, 1)) }, now).is_empty());
        let cmds = w.apply(
            ChainEvent::Account { address: ore::BOARD_ADDRESS, slot: 400, account: acc(board_bytes(8, 0, u64::MAX, 1)) },
            now,
        );
        assert_eq!(cmds, vec![SubCommand::Unsubscribe(ore::round_pda(7)), SubCommand::Subscribe(ore::round_pda(8))]);
        assert_eq!(w.view.slots_left(), None, "round 8 not started");
        // A Round update for the followed PDA.
        let mut r = vec![0u8; 952];
        r[0] = 109;
        r[8..16].copy_from_slice(&8u64.to_le_bytes());
        r[16..24].copy_from_slice(&55u64.to_le_bytes());
        w.apply(ChainEvent::Account { address: ore::round_pda(8), slot: 401, account: acc(r) }, now);
        assert_eq!(w.view.round.as_ref().unwrap().deployed[0], 55);
        assert!(!b.is_tripped());
    }

    #[test]
    fn layout_drift_trips_the_breaker() {
        let (mut w, b) = watcher();
        let mut bad = board_bytes(7, 100, 340, 1);
        bad.push(0); // 41 bytes
        w.apply(ChainEvent::Account { address: ore::BOARD_ADDRESS, slot: 1, account: acc(bad) }, Instant::now());
        assert!(b.is_tripped());
        assert!(w.view.board.is_none());
        let (mut w, b) = watcher();
        w.apply(ChainEvent::Account { address: ore::TREASURY_ADDRESS, slot: 1, account: None }, Instant::now());
        assert!(b.is_tripped(), "a pinned singleton vanished");
        let (mut w, b) = watcher();
        let mut t = vec![0u8; 48];
        t[0] = 105; // wrong discriminator
        w.apply(ChainEvent::Account { address: ore::TREASURY_ADDRESS, slot: 1, account: acc(t) }, Instant::now());
        assert!(b.is_tripped());
        let (mut w, b) = watcher();
        let mut fake = board_bytes(7, 100, 340, 1);
        fake[0] = 105;
        w.apply(
            ChainEvent::Account { address: ore::BOARD_ADDRESS, slot: 1, account: Some(RawAccount { owner: ore::SYSTEM_PROGRAM_ID, lamports: 1, data: fake }) },
            Instant::now(),
        );
        assert!(b.is_tripped(), "not owned by ORE");
    }

    #[test]
    fn ws_messages_map_to_events() {
        let mut pending = HashMap::from([(2u64, Pending::Account(ore::BOARD_ADDRESS)), (1, Pending::Slot)]);
        let mut subs = HashMap::new();
        assert!(handle_ws_text(r#"{"jsonrpc":"2.0","result":900,"id":2}"#, &mut pending, &mut subs).is_empty());
        assert_eq!(subs.get(&900), Some(&ore::BOARD_ADDRESS));
        let n = r#"{"jsonrpc":"2.0","method":"accountNotification","params":{"result":{"context":{"slot":5},"value":{"data":["aQ==","base64"],"executable":false,"lamports":3,"owner":"oreV3EG1i9BEgiAJ8b177Z2S2rMarzak4NMv1kULvWv","rentEpoch":0,"space":1}},"subscription":900}}"#;
        let evs = handle_ws_text(n, &mut pending, &mut subs);
        assert_eq!(
            evs,
            vec![ChainEvent::Account {
                address: ore::BOARD_ADDRESS,
                slot: 5,
                account: Some(RawAccount { owner: ore::ORE_PROGRAM_ID, lamports: 3, data: vec![105] })
            }]
        );
        let s = r#"{"jsonrpc":"2.0","method":"slotNotification","params":{"result":{"parent":9,"root":1,"slot":10},"subscription":1}}"#;
        assert_eq!(handle_ws_text(s, &mut pending, &mut subs), vec![ChainEvent::Slot(10)]);
        // Unknown subscription, garbage, and a hostile huge number are all ignored.
        assert!(handle_ws_text(&n.replace("900}", "901}"), &mut pending, &mut subs).is_empty());
        assert!(handle_ws_text("not json", &mut pending, &mut subs).is_empty());
        assert!(handle_ws_text(r#"{"method":"slotNotification","params":{"result":{"slot":-1}}}"#, &mut pending, &mut subs).is_empty());
    }
}
