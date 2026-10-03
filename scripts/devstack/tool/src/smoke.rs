//! The phone-less end-to-end smoke: the "trustless beat" on a local mainnet fork.
//!
//! 1. A fresh wallet clocks in with ONE transaction: ORE `automate` (Discretionary,
//!    `fee = Config.executor_fee`, executor = Executor PDA) + `register_rig` + `set_caps` +
//!    `arm_shift`.
//! 2. A simulated phone (P-256 key, Keystore-style signatures) streams one heartbeat per ORE
//!    round to the crank's WebSocket intake (`crank/INTERFACE-NOTES.md` A6).
//! 3. The real `hd-crank` digs the rig late in the round: ORE `deploy` through the Executor
//!    PDA; the program emits `RigDug`.
//! 4. The indexer (real RPC mode) records that `RigDug`.
//! 5. The phone is lifted (heartbeats stop). The next round:
//!    * the crank does not dig the rig (no lease covers the round);
//!    * a hostile crank replaying the last signed heartbeat gets `RigSkipped(StaleHeartbeat)`;
//!    * a hostile crank reusing the old lease gets `RigSkipped(LeaseExpired)`.

use std::time::{Duration, Instant};

use anyhow::{anyhow, bail, Context, Result};
use base64::Engine;
use futures_util::{SinkExt, StreamExt};
use hd_crank::hd::{self, DigEntry, HdEvent, HeartbeatFields, Rig, RigAccounts, RigState};
use hd_crank::ore::{self as core, Automation, Board, BOARD_ADDRESS};
use serde_json::{json, Value};
use solana_address::Address;
use solana_keypair::Keypair;
use solana_signer::Signer;
use tokio_tungstenite::tungstenite::Message;

use crate::hd as hdix;
use crate::clockin;
use crate::phone::Phone;
use crate::util::{wait_for, Chain, Landed, SOL};

/// Options.
pub struct SmokeOpts {
    /// JSON-RPC URL.
    pub rpc: String,
    /// Crank intake WebSocket (`ws://127.0.0.1:8787/ws`).
    pub crank_ws: String,
    /// Crank HTTP base (`http://127.0.0.1:8787`) for `/healthz` and `/metrics`.
    pub crank_http: String,
    /// Indexer API base (`http://127.0.0.1:8788`).
    pub indexer: String,
    /// Overall deadline.
    pub timeout: Duration,
}

fn t0() -> &'static Instant {
    static T0: std::sync::OnceLock<Instant> = std::sync::OnceLock::new();
    T0.get_or_init(Instant::now)
}

macro_rules! step {
    ($($t:tt)*) => { println!("[smoke +{:>4}s] {}", t0().elapsed().as_secs(), format!($($t)*)) };
}

async fn board(chain: &Chain) -> Result<Board> {
    let (o, d, _) = chain.data(&BOARD_ADDRESS).await?.ok_or_else(|| anyhow!("ORE Board missing"))?;
    Board::decode(&o, &d).map_err(|e| anyhow!("Board: {e}"))
}

async fn rig(chain: &Chain, addr: &Address) -> Result<Rig> {
    let (o, d, _) = chain.data(addr).await?.ok_or_else(|| anyhow!("Rig {addr} missing"))?;
    Rig::decode(&hd::PROGRAM_ID, &o, &d).map_err(|e| anyhow!("Rig: {e}"))
}

async fn http_get(url: &str) -> Result<(u16, String)> {
    let r = reqwest::Client::builder().timeout(Duration::from_secs(10)).build()?.get(url).send().await?;
    let status = r.status().as_u16();
    Ok((status, r.text().await?))
}

fn metric(body: &str, name: &str) -> Option<f64> {
    body.lines().find_map(|l| {
        let (k, v) = l.split_once(' ')?;
        (k == name).then(|| v.trim().parse().ok()).flatten()
    })
}

type Ws = tokio_tungstenite::WebSocketStream<tokio_tungstenite::MaybeTlsStream<tokio::net::TcpStream>>;

async fn ask(ws: &mut Ws, v: Value) -> Result<Value> {
    ws.send(Message::text(v.to_string())).await?;
    let deadline = tokio::time::Instant::now() + Duration::from_secs(10);
    loop {
        let msg = tokio::time::timeout_at(deadline, ws.next()).await.map_err(|_| anyhow!("crank intake did not answer"))?;
        match msg {
            Some(Ok(Message::Text(t))) => return Ok(serde_json::from_str(t.as_str())?),
            Some(Ok(_)) => continue,
            Some(Err(e)) => bail!("intake socket: {e}"),
            None => bail!("intake closed the socket"),
        }
    }
}

fn skip_code(evs: &[HdEvent], rig: &Address) -> Option<(u64, u32)> {
    evs.iter().find_map(|e| match e {
        HdEvent::RigSkipped { rig: r, round_id, error } if r == rig => Some((*round_id, *error)),
        _ => None,
    })
}

/// The last heartbeat the phone signed (what a hostile crank could replay).
#[derive(Clone, Copy)]
struct Signed {
    fields: HeartbeatFields,
    digest: [u8; 32],
    sig: [u8; 64],
}

/// Run the smoke. Returns an error describing the first check that failed.
pub async fn run(o: SmokeOpts) -> Result<()> {
    let _ = t0();
    let chain = Chain::new(&o.rpc)?;

    // ---- 0. the stack is up -------------------------------------------------------------
    let (hs, hb) = http_get(&format!("{}/healthz", o.crank_http)).await.context("crank /healthz")?;
    if hs != 200 {
        bail!("crank /healthz returned {hs}: {hb}");
    }
    let (is, _) = http_get(&format!("{}/v1/health", o.indexer)).await.context("indexer /v1/health")?;
    if is != 200 {
        bail!("indexer /v1/health returned {is}");
    }
    let cfg = clockin::config(&chain).await?;
    let b0 = board(&chain).await?;
    step!(
        "stack up: crank healthy, indexer healthy; heads_down Config executor_fee {} crank_fee {}; ORE round {} (ema {} lamports/ORE)",
        cfg.executor_fee,
        cfg.crank_fee,
        b0.round_id,
        b0.production_cost_ema
    );

    // ---- 1. clock in: one wallet transaction --------------------------------------------------
    let wallet = Keypair::new();
    let phone = Phone::random()?;
    chain.airdrop(&wallet.pubkey(), SOL).await?;
    let w = wallet.pubkey();
    let rig_addr = hdix::rig(&w);
    // Lease 1: a heartbeat covers only the round it names, so lifting the phone turns the
    // very next round cold (phones in the field should sign lease 2-3, crank INTERFACE-NOTES 17).
    let (l, r0) = clockin::clock_in(&chain, &wallet, &phone.pubkey(), clockin::ClockIn::standard(1, 3 * 3600))
        .await
        .context("clock-in transaction")?;
    step!(
        "clock-in (automate + register_rig + set_caps + arm_shift, 1 tx {}): rig {rig_addr} Armed, shift {}, 0.001 SOL digs on 10 split tiles",
        l.signature,
        r0.shift_id
    );
    let accounts = RigAccounts::derive(rig_addr, w);

    // ---- 2. the phone streams heartbeats --------------------------------------------------------
    let (mut ws, _) = tokio_tungstenite::connect_async(o.crank_ws.as_str()).await.context("connect to the crank intake")?;
    let status = ask(&mut ws, json!({ "type": "status" })).await?;
    step!("crank intake status: {status}");
    let deadline = Instant::now() + o.timeout;
    let mut counter = r0.hb_counter;
    let mut last: Option<Signed> = None;
    let mut sent_for: Option<u64> = None;
    let dug_round = loop {
        if Instant::now() > deadline {
            bail!("no dig before the deadline (last heartbeat for round {sent_for:?})");
        }
        let b = board(&chain).await?;
        let r = rig(&chain, &rig_addr).await?;
        if r.last_dug_round != 0 {
            break r.last_dug_round;
        }
        if sent_for != Some(b.round_id) {
            let f = HeartbeatFields { counter: counter + 1, shift_id: r.shift_id, round_id: b.round_id, lease_rounds: 1 };
            let (digest, sig) = phone.sign_heartbeat(&rig_addr, &f)?;
            let ack = ask(
                &mut ws,
                json!({ "type": "heartbeat", "rig": rig_addr.to_string(), "counter": f.counter, "shift_id": f.shift_id,
                        "round_id": f.round_id, "lease_rounds": f.lease_rounds,
                        "sig64": base64::engine::general_purpose::STANDARD.encode(sig) }),
            )
            .await?;
            // Contract A: {"type":"ack","counter":N,"ok":true|false,"reason":"accepted"|...}.
            if ack["ok"] == true {
                counter = f.counter;
                sent_for = Some(b.round_id);
                last = Some(Signed { fields: f, digest, sig });
                let when = if b.started() { format!("{} slots left", b.end_slot.saturating_sub(chain.slot().await?)) } else { "not started".into() };
                step!("phone face-down: heartbeat #{} for round {} accepted by the crank ({when})", f.counter, b.round_id);
            } else {
                step!("heartbeat #{} for round {} not accepted yet: {ack}", f.counter, b.round_id);
                tokio::time::sleep(Duration::from_secs(2)).await;
            }
        }
        tokio::time::sleep(Duration::from_millis(700)).await;
    };
    let hb = last.ok_or_else(|| anyhow!("no heartbeat was accepted"))?;

    // ---- 3. the crank dug it ---------------------------------------------------------------------
    let dig_sig = find_dig(&chain, &rig_addr, dug_round).await?;
    let logs = chain.logs(&dig_sig).await?;
    let evs = hd::events_from_logs(&hd::PROGRAM_ID, &logs);
    let (lamports, mask) = evs
        .iter()
        .find_map(|e| match e {
            HdEvent::RigDug { rig, round_id, lamports, mask, .. } if *rig == rig_addr && *round_id == dug_round => Some((*lamports, *mask)),
            _ => None,
        })
        .ok_or_else(|| anyhow!("dig tx {dig_sig} has no RigDug for {rig_addr}"))?;
    let (ao, ad, _) = chain.data(&accounts.automation).await?.ok_or_else(|| anyhow!("Automation closed"))?;
    let au = Automation::decode(&ao, &ad).map_err(|e| anyhow!("Automation: {e}"))?;
    let r1 = rig(&chain, &rig_addr).await?;
    step!(
        "CRANK DUG round {dug_round}: tx {dig_sig}; RigDug {lamports} lamports on {} squares (mask {mask:#09x}); rig {:?}, hb_counter {}, lease [{}, {}]; Automation balance {} (fee {})",
        mask.count_ones(),
        r1.state,
        r1.hb_counter,
        r1.lease_from_round,
        r1.lease_to_round,
        au.balance,
        au.fee
    );
    if r1.hb_counter != hb.fields.counter || r1.state != RigState::Down {
        bail!("rig after the dig: counter {} state {:?}", r1.hb_counter, r1.state);
    }
    let (_, m) = http_get(&format!("{}/metrics", o.crank_http)).await?;
    let landed = metric(&m, "hd_crank_digs_landed_total").unwrap_or(0.0);
    step!("crank metrics: hd_crank_digs_landed_total {landed}, heartbeats accepted {}", metric(&m, "hd_crank_heartbeats_accepted_total").unwrap_or(0.0));

    // ---- 4. lift the phone ---------------------------------------------------------------------
    step!("PHONE LIFTED after round {dug_round}: no more heartbeats");
    let _ = ws.close(None).await;
    let next = wait_for("the next ORE round", o.timeout, Duration::from_millis(700), || async {
        let b = board(&chain).await?;
        Ok((b.round_id > dug_round).then_some(b))
    })
    .await?;
    step!("ORE round {} is now current (reset by the round driver)", next.round_id);

    // A hostile crank replays the last signed heartbeat…
    let attacker = Keypair::new();
    chain.airdrop(&attacker.pubkey(), SOL).await?;
    let round_acc = core::round_pda(next.round_id);
    let replay = [
        hd_crank::tx::set_compute_unit_limit(400_000),
        phone.precompile_ix(&hb.digest, &hb.sig)?,
        hd::dig_ix(
            &hd::PROGRAM_ID,
            &attacker.pubkey(),
            &round_acc,
            &[(accounts, DigEntry { hb_ix: 1, hb_sig_index: 0, counter: hb.fields.counter, round_id: hb.fields.round_id, lease_rounds: 1 })],
        )?,
    ];
    let l1 = expect_skip(&chain, &attacker, &replay, &rig_addr, 7).await.context("replayed heartbeat")?;
    step!("hostile crank REPLAYS heartbeat #{} in round {}: tx {} -> RigSkipped(StaleHeartbeat)", hb.fields.counter, next.round_id, l1.signature);
    // …or reuses the expired lease.
    let reuse = [
        hd_crank::tx::set_compute_unit_limit(400_000),
        hd::dig_ix(&hd::PROGRAM_ID, &attacker.pubkey(), &round_acc, &[(accounts, DigEntry::reuse_lease())])?,
    ];
    let l2 = expect_skip(&chain, &attacker, &reuse, &rig_addr, 8).await.context("lease reuse")?;
    step!("hostile crank REUSES the old lease in round {}: tx {} -> RigSkipped(LeaseExpired)", next.round_id, l2.signature);

    // The real crank stays idle for this rig through the whole round.
    let ended = wait_for("round after the lift to end", o.timeout, Duration::from_millis(700), || async {
        let b = board(&chain).await?;
        let s = chain.slot().await?;
        Ok((b.round_id > next.round_id || (b.round_id == next.round_id && b.started() && s >= b.end_slot)).then_some(b))
    })
    .await?;
    let r2 = rig(&chain, &rig_addr).await?;
    if r2.last_dug_round != dug_round {
        bail!("the rig was dug in round {} after the phone was lifted", r2.last_dug_round);
    }
    let (_, m2) = http_get(&format!("{}/metrics", o.crank_http)).await?;
    step!(
        "round {} closed (board at {}): crank did NOT dig the lifted rig (last_dug_round {}, lease_to {}); hd_crank_digs_landed_total {}",
        next.round_id,
        ended.round_id,
        r2.last_dug_round,
        r2.lease_to_round,
        metric(&m2, "hd_crank_digs_landed_total").unwrap_or(0.0)
    );

    // ---- 5. the indexer saw the dig (finalized commitment) ---------------------------------------
    let item = wait_for("the indexer to record the RigDug", o.timeout, Duration::from_secs(3), || async {
        let (s, body) = http_get(&format!("{}/v1/digs/recent?limit=200", o.indexer)).await?;
        if s != 200 {
            return Ok(None);
        }
        let v: Value = serde_json::from_str(&body)?;
        Ok(v["data"].as_array().and_then(|a| a.iter().find(|d| d["signature"] == dig_sig.as_str()).cloned()))
    })
    .await?;
    step!(
        "INDEXER recorded RigDug: rig {} round {} lamports {} squares {} (dataset {})",
        item["rig"],
        item["roundId"],
        item["lamports"],
        item["squares"],
        "localnet"
    );
    if item["rig"] != rig_addr.to_string().as_str() || item["roundId"] != dug_round.to_string().as_str() {
        bail!("indexer row does not match: {item}");
    }
    let skip_slot = l2.slot;
    let health = wait_for("the indexer to ingest the skipped digs", o.timeout, Duration::from_secs(3), || async {
        let (_, body) = http_get(&format!("{}/v1/health", o.indexer)).await?;
        let v: Value = serde_json::from_str(&body)?;
        Ok((v["data"]["lastSlot"].as_u64().unwrap_or(0) >= skip_slot).then_some(v))
    })
    .await?;
    let problems = health["data"]["problems"].as_array().map_or(0, Vec::len);
    step!("indexer health: {} txs ingested through slot {}, {} decode problem kinds", health["data"]["txs"], health["data"]["lastSlot"], problems);
    if problems != 0 {
        bail!("indexer reports decode problems: {}", health["data"]["problems"]);
    }
    println!();
    println!("SMOKE PASSED: face-down -> dug (round {dug_round}); lifted -> no dig, replay and lease reuse refused on-chain (round {}).", next.round_id);
    Ok(())
}

/// The signature of the transaction that dug `rig` in `round`.
async fn find_dig(chain: &Chain, rig: &Address, round: u64) -> Result<String> {
    let sigs = chain.call("getSignaturesForAddress", json!([rig.to_string(), { "limit": 20, "commitment": "confirmed" }])).await?;
    for s in sigs.as_array().into_iter().flatten() {
        if !s["err"].is_null() {
            continue;
        }
        let Some(sig) = s["signature"].as_str() else { continue };
        let logs = chain.logs(sig).await.unwrap_or_default();
        let dug = hd::events_from_logs(&hd::PROGRAM_ID, &logs)
            .iter()
            .any(|e| matches!(e, HdEvent::RigDug { rig: r, round_id, .. } if r == rig && *round_id == round));
        if dug {
            return Ok(sig.to_string());
        }
    }
    bail!("no RigDug transaction found for {rig} in round {round}")
}

async fn expect_skip(chain: &Chain, payer: &Keypair, ixs: &[solana_instruction::Instruction], rig: &Address, code: u32) -> Result<Landed> {
    let l = chain.send(payer, ixs).await?;
    let evs = hd::events_from_logs(&hd::PROGRAM_ID, &l.logs);
    match skip_code(&evs, rig) {
        Some((_, c)) if c == code => Ok(l),
        Some((_, c)) => bail!("expected RigSkipped({}) but got RigSkipped({c} {})", hd::error_name(code), hd::error_name(c)),
        None => bail!("no RigSkipped for {rig} in {}: {evs:?}", l.signature),
    }
}
