//! Intake logic for phone-signed messages: parse, check, verify P-256 off-chain, keep the
//! latest heartbeat per rig, and verify BREAK / FREEZE signals before they are landed.
//!
//! Messages are **self-authenticating**. The crank needs no session or auth token: a
//! message is only useful if it verifies against the P-256 key registered in the rig's
//! on-chain `Rig` account, over `SHA-256(preimage)` exactly as the program will rebuild it
//! (`hd::heartbeat_preimage`, `hd::break_preimage`). The crank verifies every signature
//! before it spends a lamport on a transaction, because one bad signature in a
//! Secp256r1SigVerify instruction fails the whole transaction.
//!
//! Verification uses the `p256` crate through `p256_introspect::client`: `normalize_low_s`
//! (the precompile rejects high-S; normalizing needs no private key) and
//! `verify_like_precompile` (range and low-S checks, then ECDSA-P256 over SHA-256 of the
//! 32-byte digest, which is what the precompile does).
//!
//! Checks run cheapest first; the RPC fetch and the ECDSA verify come last, after the
//! intake's per-IP and per-rig rate limits.

use std::collections::HashMap;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use async_trait::async_trait;
use base64::Engine;
use serde::{Deserialize, Serialize};
use solana_address::Address;
use tokio::sync::Semaphore;

use crate::hd::{self, HeartbeatFields, Rig, RigState, SignalKind, MAX_LEASE_ROUNDS};
use crate::ratelimit::{KeyedLimiter, Quota};

/// An unsigned integer sent as a JSON number or as a decimal string (contract A: "Integers are
/// JSON numbers. Accept them also as decimal strings"). At most 20 digits, no sign, no
/// exponent, no fraction.
pub fn de_u64_or_string<'de, D: serde::Deserializer<'de>>(d: D) -> Result<u64, D::Error> {
    struct V;
    impl serde::de::Visitor<'_> for V {
        type Value = u64;
        fn expecting(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
            f.write_str("an unsigned integer as a JSON number or a decimal string")
        }
        fn visit_u64<E: serde::de::Error>(self, v: u64) -> Result<u64, E> {
            Ok(v)
        }
        fn visit_i64<E: serde::de::Error>(self, v: i64) -> Result<u64, E> {
            u64::try_from(v).map_err(|_| E::custom("negative"))
        }
        fn visit_str<E: serde::de::Error>(self, v: &str) -> Result<u64, E> {
            if v.is_empty() || v.len() > 20 || !v.bytes().all(|c| c.is_ascii_digit()) {
                return Err(E::custom("not a decimal string"));
            }
            v.parse().map_err(|_| E::custom("out of range"))
        }
    }
    d.deserialize_any(V)
}

/// What a phone sends for a HEARTBEAT (contract A), one JSON text frame:
///
/// ```json
/// {"type":"heartbeat","rig":"<base58>","counter":7,"shift_id":3,"round_id":422601,
///  "lease_rounds":2,"sig64":"<standard base64 of r||s>"}
/// ```
///
/// Integers may also be decimal strings. Unknown fields are ignored. The legacy field name
/// `sig` is accepted for `sig64`.
#[derive(Clone, Debug, Deserialize, Serialize, PartialEq, Eq)]
pub struct HeartbeatSubmission {
    /// Rig PDA, base58.
    pub rig: String,
    /// Strictly increasing per rig across all kinds.
    #[serde(deserialize_with = "de_u64_or_string")]
    pub counter: u64,
    /// `rig.shift_id` the phone armed.
    #[serde(deserialize_with = "de_u64_or_string")]
    pub shift_id: u64,
    /// `Board.round_id` at signing.
    #[serde(deserialize_with = "de_u64_or_string")]
    pub round_id: u64,
    /// Requested lease, 1..=3 (anything else is `lease_invalid`).
    #[serde(deserialize_with = "de_u64_or_string")]
    pub lease_rounds: u64,
    /// 64-byte raw `r || s` (low-S; high-S is normalized), standard base64 (hex also accepted).
    #[serde(alias = "sig")]
    pub sig64: String,
}

/// What a phone sends for a BREAK or a FREEZE (contract A):
///
/// ```json
/// {"type":"break","rig":"<base58>","counter":N,"shift_id":N,"reason":R,"sig64":"<b64>"}   R ∈ {1,2,4,5,6,7,8}
/// {"type":"freeze","rig":"<base58>","counter":N,"shift_id":N,"reason":3,"sig64":"<b64>"}
/// ```
#[derive(Clone, Debug, Deserialize, Serialize, PartialEq, Eq)]
pub struct SignalSubmission {
    /// Rig PDA, base58.
    pub rig: String,
    /// Strictly increasing per rig across all kinds.
    #[serde(deserialize_with = "de_u64_or_string")]
    pub counter: u64,
    /// `rig.shift_id` (0 before the first arm).
    #[serde(deserialize_with = "de_u64_or_string")]
    pub shift_id: u64,
    /// The reason byte the phone signed.
    #[serde(deserialize_with = "de_u64_or_string")]
    pub reason: u64,
    /// 64-byte raw `r || s`, standard base64 (hex also accepted).
    #[serde(alias = "sig")]
    pub sig64: String,
}

/// Why a message was refused. [`Reject::reason`] is the (bounded) metric label;
/// [`Reject::ack_code`] is the contract-A code the phone sees.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum Reject {
    /// Not valid JSON / missing fields / wrong message type.
    Malformed,
    /// Bad base58 / base64 / hex or wrong byte length.
    BadEncoding,
    /// A BREAK reason outside {1,2,4,5,6,7,8}, or a FREEZE reason other than 3.
    BadReason,
    /// `lease_rounds` outside 1..=3.
    BadLease,
    /// `round_id` ahead of the chain by more than one round.
    RoundInFuture,
    /// The lease this heartbeat could grant has already ended.
    Expired,
    /// A message with this or a higher counter is already held, landed or on-chain.
    StaleCounter,
    /// No Rig account at that address (or not a heads_down Rig).
    UnknownRig,
    /// `shift_id` differs from the rig's current shift.
    ShiftMismatch,
    /// The rig's state cannot take this message (a heartbeat or a BREAK outside Armed /
    /// Down / Cooling).
    NotArmed,
    /// ECDSA verification failed.
    BadSignature,
    /// Per-IP rate limit.
    RateLimitedIp,
    /// Per-rig rate limit.
    RateLimitedRig,
    /// Verification capacity or a queue is full; retry later.
    Busy,
    /// Message larger than the cap.
    TooLarge,
    /// Could not read the chain to check the rig; retry later.
    Unavailable,
    /// The crank's fee budget for landing BREAK / FREEZE is spent for now.
    SignalBudget,
    /// This crank does not land BREAK / FREEZE (`signals.enabled = false`).
    SignalsDisabled,
    /// A BREAK for a rig whose plan window has already ended (or ends within the landing
    /// margin). It is not landed: nothing is dug after the window anyway, and the BREAK would
    /// make `end_shift` seal a completed night as a break (streak protection).
    WindowEnded,
}

impl Reject {
    /// Stable snake_case label (metrics).
    pub fn reason(self) -> &'static str {
        match self {
            Reject::Malformed => "malformed",
            Reject::BadEncoding => "bad_encoding",
            Reject::BadReason => "bad_reason",
            Reject::BadLease => "bad_lease",
            Reject::RoundInFuture => "round_in_future",
            Reject::Expired => "expired",
            Reject::StaleCounter => "stale_counter",
            Reject::UnknownRig => "unknown_rig",
            Reject::ShiftMismatch => "shift_mismatch",
            Reject::NotArmed => "not_armed",
            Reject::BadSignature => "bad_signature",
            Reject::RateLimitedIp => "rate_limited_ip",
            Reject::RateLimitedRig => "rate_limited_rig",
            Reject::Busy => "busy",
            Reject::TooLarge => "too_large",
            Reject::Unavailable => "unavailable",
            Reject::SignalBudget => "signal_budget",
            Reject::SignalsDisabled => "signals_disabled",
            Reject::WindowEnded => "window_ended",
        }
    }

    /// The contract-A ack code: one of `bad_signature, stale_counter, unknown_rig,
    /// rate_limited, malformed, lease_invalid`. Transient conditions (limits, capacity, RPC,
    /// budget) are `rate_limited`: retry later. A message that cannot apply to the rig's
    /// current round, shift, state or plan window is `lease_invalid`: re-read the chain and
    /// re-sign. Contract A has no "accepted but ignored" code, so a BREAK after the plan
    /// window ([`Reject::WindowEnded`]) is `ok: false, reason: lease_invalid` as well.
    pub fn ack_code(self) -> &'static str {
        match self {
            Reject::Malformed | Reject::BadEncoding | Reject::BadReason | Reject::TooLarge => "malformed",
            Reject::BadLease
            | Reject::RoundInFuture
            | Reject::Expired
            | Reject::ShiftMismatch
            | Reject::NotArmed
            | Reject::WindowEnded => "lease_invalid",
            Reject::StaleCounter => "stale_counter",
            Reject::UnknownRig => "unknown_rig",
            Reject::BadSignature => "bad_signature",
            Reject::RateLimitedIp
            | Reject::RateLimitedRig
            | Reject::Busy
            | Reject::Unavailable
            | Reject::SignalBudget
            | Reject::SignalsDisabled => "rate_limited",
        }
    }

    /// Every variant.
    pub const ALL: [Reject; 19] = [
        Reject::WindowEnded,
        Reject::Malformed,
        Reject::BadEncoding,
        Reject::BadReason,
        Reject::BadLease,
        Reject::RoundInFuture,
        Reject::Expired,
        Reject::StaleCounter,
        Reject::UnknownRig,
        Reject::ShiftMismatch,
        Reject::NotArmed,
        Reject::BadSignature,
        Reject::RateLimitedIp,
        Reject::RateLimitedRig,
        Reject::Busy,
        Reject::TooLarge,
        Reject::Unavailable,
        Reject::SignalBudget,
        Reject::SignalsDisabled,
    ];
}

/// The contract-A ack codes: `accepted` and the six refusal codes.
pub const ACK_CODES: [&str; 7] =
    ["accepted", "bad_signature", "stale_counter", "unknown_rig", "rate_limited", "malformed", "lease_invalid"];

/// Decode `N` bytes given as standard base64 (contract A) or hex (`2N` chars).
pub fn decode_fixed<const N: usize>(s: &str) -> Option<[u8; N]> {
    if s.len() > 4 * N {
        return None; // never decode oversized input
    }
    let bytes = if s.len() == 2 * N && s.bytes().all(|c| c.is_ascii_hexdigit()) {
        hex::decode(s).ok()?
    } else {
        base64::engine::general_purpose::STANDARD.decode(s).ok()?
    };
    bytes.try_into().ok()
}

/// Base58 address with a length cap.
pub fn decode_address(s: &str) -> Option<Address> {
    if s.is_empty() || s.len() > 44 {
        return None;
    }
    let v = bs58::decode(s).into_vec().ok()?;
    let arr: [u8; 32] = v.try_into().ok()?;
    Some(Address::new_from_array(arr))
}

/// A syntactically valid heartbeat, before any chain or signature check.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct ParsedHeartbeat {
    /// Rig PDA.
    pub rig: Address,
    /// Signed fields.
    pub fields: HeartbeatFields,
    /// As received (may be high-S).
    pub sig: [u8; 64],
}

impl ParsedHeartbeat {
    /// Decode encodings and check the static rules.
    pub fn parse(s: &HeartbeatSubmission) -> Result<Self, Reject> {
        let rig = decode_address(&s.rig).ok_or(Reject::BadEncoding)?;
        let sig = decode_fixed::<64>(&s.sig64).ok_or(Reject::BadEncoding)?;
        let lease_rounds = u8::try_from(s.lease_rounds).map_err(|_| Reject::BadLease)?;
        if lease_rounds == 0 || lease_rounds > MAX_LEASE_ROUNDS {
            return Err(Reject::BadLease);
        }
        Ok(ParsedHeartbeat {
            rig,
            fields: HeartbeatFields { counter: s.counter, shift_id: s.shift_id, round_id: s.round_id, lease_rounds },
            sig,
        })
    }
}

/// A syntactically valid BREAK / FREEZE, before any chain or signature check.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct ParsedSignal {
    /// BREAK or FREEZE.
    pub kind: SignalKind,
    /// Rig PDA.
    pub rig: Address,
    /// Counter.
    pub counter: u64,
    /// `rig.shift_id` the phone signed.
    pub shift_id: u64,
    /// Reason byte.
    pub reason: u8,
    /// As received (may be high-S).
    pub sig: [u8; 64],
}

impl ParsedSignal {
    /// Decode encodings and check the reason for `kind`.
    pub fn parse(kind: SignalKind, s: &SignalSubmission) -> Result<Self, Reject> {
        let rig = decode_address(&s.rig).ok_or(Reject::BadEncoding)?;
        let sig = decode_fixed::<64>(&s.sig64).ok_or(Reject::BadEncoding)?;
        let reason = u8::try_from(s.reason).map_err(|_| Reject::BadReason)?;
        if !kind.reason_ok(reason) {
            return Err(Reject::BadReason);
        }
        Ok(ParsedSignal { kind, rig, counter: s.counter, shift_id: s.shift_id, reason, sig })
    }

    /// `SHA-256` of the 86-byte BREAK / FREEZE preimage (what the phone signed).
    pub fn digest(&self, program_id: &Address) -> [u8; 32] {
        hd::digest(&hd::break_preimage(program_id, &self.rig, self.kind.message_kind(), self.counter, self.shift_id, self.reason))
    }
}

/// Freshness against the crank's view of `Board.round_id`: at most one round ahead (the
/// phone may see a reset first), and the longest lease it could grant must still cover the
/// current round.
pub fn check_freshness(fields: &HeartbeatFields, current_round: u64) -> Result<(), Reject> {
    if fields.round_id > current_round.saturating_add(1) {
        return Err(Reject::RoundInFuture);
    }
    let last = fields
        .round_id
        .saturating_add(u64::from(fields.lease_rounds).saturating_sub(1));
    if last < current_round {
        return Err(Reject::Expired);
    }
    Ok(())
}

/// Verify a raw signature against `pubkey` over the 32-byte `digest`, the way the precompile
/// will: normalize to low-S first, then range + low-S + ECDSA-P256-SHA256. Returns the
/// low-S signature to put in the transaction.
pub fn verify_signature(pubkey: &[u8; 33], digest: &[u8; 32], sig: &[u8; 64]) -> Result<[u8; 64], Reject> {
    if !matches!(pubkey[0], 0x02 | 0x03) {
        return Err(Reject::BadSignature);
    }
    let low = p256_introspect::client::normalize_low_s(sig).map_err(|_| Reject::BadSignature)?;
    p256_introspect::client::verify_like_precompile(pubkey, digest, &low)
        .map_err(|_| Reject::BadSignature)?;
    Ok(low)
}

/// A heartbeat that verified; everything the tx builder needs.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct VerifiedHeartbeat {
    /// Rig PDA.
    pub rig: Address,
    /// Signed fields.
    pub fields: HeartbeatFields,
    /// Low-S signature.
    pub sig: [u8; 64],
    /// The rig's registered key at verification time.
    pub pubkey: [u8; 33],
    /// `SHA-256(preimage)`: the precompile message.
    pub digest: [u8; 32],
}

/// A BREAK / FREEZE that verified against the rig; everything the lander needs.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct VerifiedSignal {
    /// BREAK or FREEZE.
    pub kind: SignalKind,
    /// Rig PDA.
    pub rig: Address,
    /// `rig.authority` (passed, not signing, on the P-256 path).
    pub authority: Address,
    /// Counter.
    pub counter: u64,
    /// Shift id the phone signed (= `rig.shift_id` at verification).
    pub shift_id: u64,
    /// Reason byte.
    pub reason: u8,
    /// Low-S signature.
    pub sig: [u8; 64],
    /// The rig's registered key.
    pub pubkey: [u8; 33],
    /// `SHA-256(86-byte preimage)`.
    pub digest: [u8; 32],
    /// The rig's state when the signal was verified.
    pub rig_state: RigState,
    /// The rig's `plan_window_end_ts` (fixed for the shift the signal is bound to).
    pub plan_window_end_ts: i64,
}

/// Streak protection for phone-signed BREAKs (see `signals.streak_protection`): a BREAK is
/// landed only while the rig's plan window is open, with a margin for the landing itself.
///
/// After `plan_window_end_ts` the program digs nothing (`OutsideWindow`), so a BREAK protects
/// no SOL; but it still sets `break_reason`, and `end_shift` would then seal the night as a
/// pickup instead of `completed`, which the streak does not count. The phone already stops
/// sending BREAKs when its window ends; this is the crank's side of the same rule, for clock
/// skew and old clients. A FREEZE is never held back: it is a safety action.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct WindowRule {
    /// Apply the rule at all.
    pub enabled: bool,
    /// Seconds before `plan_window_end_ts` from which a BREAK is no longer landed.
    pub margin_secs: i64,
}

impl Default for WindowRule {
    fn default() -> Self {
        WindowRule { enabled: true, margin_secs: 5 }
    }
}

impl WindowRule {
    /// May this signal be landed at cluster time `now_ts`? Always for a FREEZE; for a BREAK
    /// only while `now_ts + margin <= plan_window_end_ts`.
    pub fn allows(&self, kind: SignalKind, plan_window_end_ts: i64, now_ts: i64) -> bool {
        !self.enabled || kind != SignalKind::Break || now_ts.saturating_add(self.margin_secs) <= plan_window_end_ts
    }

    /// [`Self::allows`] for a verified signal.
    pub fn check(&self, s: &VerifiedSignal, now_ts: i64) -> Result<(), Reject> {
        if self.allows(s.kind, s.plan_window_end_ts, now_ts) {
            Ok(())
        } else {
            Err(Reject::WindowEnded)
        }
    }
}

/// Latest verified heartbeat per rig, bounded.
#[derive(Debug)]
pub struct HeartbeatStore {
    max_rigs: usize,
    inner: Mutex<HashMap<Address, (VerifiedHeartbeat, Instant)>>,
}

/// Result of [`HeartbeatStore::offer`].
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Offer {
    /// Now the latest for its rig.
    Stored,
    /// Not newer than what is held.
    Stale,
    /// Store full of other rigs.
    Full,
}

impl HeartbeatStore {
    /// Store at most `max_rigs` rigs.
    pub fn new(max_rigs: usize) -> Self {
        HeartbeatStore { max_rigs: max_rigs.max(1), inner: Mutex::new(HashMap::new()) }
    }

    fn lock(&self) -> std::sync::MutexGuard<'_, HashMap<Address, (VerifiedHeartbeat, Instant)>> {
        match self.inner.lock() {
            Ok(g) => g,
            Err(p) => p.into_inner(),
        }
    }

    /// Highest counter held for `rig`.
    pub fn counter(&self, rig: &Address) -> Option<u64> {
        self.lock().get(rig).map(|(h, _)| h.fields.counter)
    }

    /// Keep `hb` only if its counter is higher than what is held.
    pub fn offer(&self, hb: VerifiedHeartbeat) -> Offer {
        let mut m = self.lock();
        match m.get(&hb.rig) {
            Some((held, _)) if held.fields.counter >= hb.fields.counter => return Offer::Stale,
            None if m.len() >= self.max_rigs => return Offer::Full,
            _ => {}
        }
        m.insert(hb.rig, (hb, Instant::now()));
        Offer::Stored
    }

    /// Latest heartbeat for `rig`.
    pub fn get(&self, rig: &Address) -> Option<VerifiedHeartbeat> {
        self.lock().get(rig).map(|(h, _)| *h)
    }

    /// All held heartbeats.
    pub fn snapshot(&self) -> Vec<VerifiedHeartbeat> {
        self.lock().values().map(|(h, _)| *h).collect()
    }

    /// Number of rigs with a heartbeat.
    pub fn len(&self) -> usize {
        self.lock().len()
    }

    /// Empty.
    pub fn is_empty(&self) -> bool {
        self.len() == 0
    }

    /// Drop heartbeats whose longest possible lease ended before `current_round`, and
    /// anything older than `max_age`.
    pub fn prune(&self, current_round: u64, max_age: Duration) {
        let now = Instant::now();
        self.lock().retain(|_, (h, at)| {
            check_freshness(&h.fields, current_round) != Err(Reject::Expired)
                && now.saturating_duration_since(*at) < max_age
        });
    }

    /// Forget a rig's heartbeat once its counter is consumed on-chain (a dig, a record, or a
    /// BREAK / FREEZE with a counter at least as high).
    pub fn remove_if_counter_at_most(&self, rig: &Address, counter: u64) {
        let mut m = self.lock();
        if m.get(rig).is_some_and(|(h, _)| h.fields.counter <= counter) {
            m.remove(rig);
        }
    }
}

/// Where the intake reads Rig accounts from (RPC in production, a map in tests).
#[async_trait]
pub trait RigSource: Send + Sync + 'static {
    /// The decoded Rig at `rig`, `None` if absent or not a heads_down Rig.
    async fn fetch_rig(&self, rig: &Address) -> anyhow::Result<Option<Rig>>;
}

#[derive(Clone, Debug)]
enum Cached {
    Present(Box<Rig>, Instant),
    Absent(Instant),
}

/// Rig cache in front of a [`RigSource`]: positive TTL, negative TTL (so a stream of
/// heartbeats for made-up rigs costs at most one fetch per rig per negative TTL), a global
/// fetch rate limit, bounded concurrency and a bounded size.
pub struct RigCache<S: RigSource> {
    source: S,
    ttl: Duration,
    negative_ttl: Duration,
    max_entries: usize,
    fetch_permits: Semaphore,
    fetch_rate: KeyedLimiter<()>,
    entries: Mutex<HashMap<Address, Cached>>,
}

impl<S: RigSource> RigCache<S> {
    /// New cache.
    pub fn new(source: S, ttl: Duration, negative_ttl: Duration, max_entries: usize, fetches_per_second: f64) -> Self {
        RigCache {
            source,
            ttl,
            negative_ttl,
            max_entries: max_entries.max(1),
            fetch_permits: Semaphore::new(8),
            fetch_rate: KeyedLimiter::new(Quota::new(fetches_per_second.ceil().max(1.0) as u32, fetches_per_second), 1),
            entries: Mutex::new(HashMap::new()),
        }
    }

    fn lock(&self) -> std::sync::MutexGuard<'_, HashMap<Address, Cached>> {
        match self.entries.lock() {
            Ok(g) => g,
            Err(p) => p.into_inner(),
        }
    }

    /// Seed from a bulk read (the planner's getProgramAccounts).
    pub fn insert(&self, addr: Address, rig: Rig) {
        let mut m = self.lock();
        if m.len() >= self.max_entries && !m.contains_key(&addr) {
            let now = Instant::now();
            let (ttl, nttl) = (self.ttl, self.negative_ttl);
            m.retain(|_, c| match c {
                Cached::Present(_, at) => now.saturating_duration_since(*at) < ttl,
                Cached::Absent(at) => now.saturating_duration_since(*at) < nttl,
            });
            if m.len() >= self.max_entries {
                return;
            }
        }
        m.insert(addr, Cached::Present(Box::new(rig), Instant::now()));
    }

    /// The rig, from cache when fresh (unless `refresh`), else from the source.
    /// `Err(Reject::Unavailable)` when the source fails, `Err(Reject::Busy)` when the fetch
    /// budget is spent.
    pub async fn get(&self, addr: &Address, refresh: bool) -> Result<Option<Rig>, Reject> {
        let now = Instant::now();
        if let Some(c) = self.lock().get(addr).cloned() {
            match c {
                Cached::Present(r, at) if !refresh && now.saturating_duration_since(at) < self.ttl => {
                    return Ok(Some(*r))
                }
                // Negative entries are honored even on refresh: that is the anti-spray bound.
                Cached::Absent(at) if now.saturating_duration_since(at) < self.negative_ttl => return Ok(None),
                _ => {}
            }
        }
        if !self.fetch_rate.check(&()) {
            return Err(Reject::Busy);
        }
        let _permit = self.fetch_permits.acquire().await.map_err(|_| Reject::Unavailable)?;
        let fetched = self.source.fetch_rig(addr).await.map_err(|_| Reject::Unavailable)?;
        let entry = match &fetched {
            Some(r) => Cached::Present(Box::new(r.clone()), Instant::now()),
            None => Cached::Absent(Instant::now()),
        };
        {
            let mut m = self.lock();
            if m.len() < self.max_entries || m.contains_key(addr) {
                m.insert(*addr, entry);
            }
        }
        Ok(fetched)
    }
}

/// The full intake check for one message, minus rate limiting (done by the caller, which
/// knows the IP). `current_round` is the crank's view of `Board.round_id`, if known.
pub struct Verifier<S: RigSource> {
    /// heads_down program id (bound into the preimage).
    pub program_id: Address,
    /// Rig accounts.
    pub rigs: RigCache<S>,
    /// Where accepted heartbeats go.
    pub store: Arc<HeartbeatStore>,
}

impl<S: RigSource> Verifier<S> {
    /// Check and verify a heartbeat; on success it is the latest held for its rig.
    pub async fn process(&self, p: &ParsedHeartbeat, current_round: Option<u64>) -> Result<VerifiedHeartbeat, Reject> {
        if let Some(cur) = current_round {
            check_freshness(&p.fields, cur)?;
        }
        if self.store.counter(&p.rig).is_some_and(|c| c >= p.fields.counter) {
            return Err(Reject::StaleCounter);
        }
        let mut rig = self.rigs.get(&p.rig, false).await?.ok_or(Reject::UnknownRig)?;
        // A mismatch may just mean the cache predates arm_shift / rotate_key / a BREAK / a dig
        // that advanced hb_counter: refresh once, then decide.
        if Self::precheck(&rig, p).is_err() {
            rig = self.rigs.get(&p.rig, true).await?.ok_or(Reject::UnknownRig)?;
        }
        Self::precheck(&rig, p)?;
        let digest = hd::digest(&hd::heartbeat_preimage(&self.program_id, &p.rig, &p.fields));
        let sig = verify_signature(&rig.p256_pubkey, &digest, &p.sig)?;
        let v = VerifiedHeartbeat { rig: p.rig, fields: p.fields, sig, pubkey: rig.p256_pubkey, digest };
        match self.store.offer(v) {
            Offer::Stored => Ok(v),
            Offer::Stale => Err(Reject::StaleCounter),
            Offer::Full => Err(Reject::Busy),
        }
    }

    fn precheck(rig: &Rig, p: &ParsedHeartbeat) -> Result<(), Reject> {
        // Armed, Down, and Cooling (a fresh heartbeat is what resumes a Cooling rig).
        if !rig.state.accepts_heartbeat() {
            return Err(Reject::NotArmed);
        }
        if rig.shift_id != p.fields.shift_id {
            return Err(Reject::ShiftMismatch);
        }
        if p.fields.counter <= rig.hb_counter {
            return Err(Reject::StaleCounter);
        }
        Ok(())
    }

    /// Check and verify a BREAK / FREEZE against the rig: same shift, a counter above the
    /// rig's `hb_counter`, a state the program accepts (BREAK: Armed, Down or Cooling; FREEZE:
    /// any), and the rig's registered key.
    pub async fn process_signal(&self, p: &ParsedSignal) -> Result<VerifiedSignal, Reject> {
        let mut rig = self.rigs.get(&p.rig, false).await?.ok_or(Reject::UnknownRig)?;
        if Self::signal_precheck(&rig, p).is_err() {
            rig = self.rigs.get(&p.rig, true).await?.ok_or(Reject::UnknownRig)?;
        }
        Self::signal_precheck(&rig, p)?;
        let digest = p.digest(&self.program_id);
        let sig = verify_signature(&rig.p256_pubkey, &digest, &p.sig)?;
        Ok(VerifiedSignal {
            kind: p.kind,
            rig: p.rig,
            authority: rig.authority,
            counter: p.counter,
            shift_id: p.shift_id,
            reason: p.reason,
            sig,
            pubkey: rig.p256_pubkey,
            digest,
            rig_state: rig.state,
            plan_window_end_ts: rig.plan_window_end_ts,
        })
    }

    fn signal_precheck(rig: &Rig, p: &ParsedSignal) -> Result<(), Reject> {
        if rig.shift_id != p.shift_id {
            return Err(Reject::ShiftMismatch);
        }
        if p.counter <= rig.hb_counter {
            return Err(Reject::StaleCounter);
        }
        if p.kind == SignalKind::Break && !rig.state.breakable() {
            return Err(Reject::NotArmed);
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn decode_fixed_accepts_hex_and_base64_only_at_the_right_length() {
        let raw = [7u8; 64];
        let hexs = hex::encode(raw);
        let b64 = base64::engine::general_purpose::STANDARD.encode(raw);
        assert_eq!(decode_fixed::<64>(&hexs), Some(raw));
        assert_eq!(decode_fixed::<64>(&b64), Some(raw));
        assert_eq!(decode_fixed::<64>(&hexs[..126]), None);
        assert_eq!(decode_fixed::<33>(&hexs), None);
        assert_eq!(decode_fixed::<64>(&"A".repeat(10_000)), None);
        assert_eq!(decode_fixed::<64>("not base64!!"), None);
    }

    #[test]
    fn freshness_window() {
        let f = |round_id, lease_rounds| HeartbeatFields { counter: 1, shift_id: 1, round_id, lease_rounds };
        assert_eq!(check_freshness(&f(100, 1), 100), Ok(()));
        assert_eq!(check_freshness(&f(101, 1), 100), Ok(()), "one round ahead tolerated");
        assert_eq!(check_freshness(&f(102, 1), 100), Err(Reject::RoundInFuture));
        assert_eq!(check_freshness(&f(99, 1), 100), Err(Reject::Expired));
        assert_eq!(check_freshness(&f(98, 3), 100), Ok(()));
        assert_eq!(check_freshness(&f(97, 3), 100), Err(Reject::Expired));
        assert_eq!(check_freshness(&f(u64::MAX, 3), u64::MAX), Ok(()));
    }

    #[test]
    fn store_keeps_only_the_latest() {
        let s = HeartbeatStore::new(1);
        let mk = |rig: u8, counter| VerifiedHeartbeat {
            rig: Address::new_from_array([rig; 32]),
            fields: HeartbeatFields { counter, shift_id: 1, round_id: 10, lease_rounds: 1 },
            sig: [0; 64],
            pubkey: [2; 33],
            digest: [0; 32],
        };
        assert_eq!(s.offer(mk(1, 5)), Offer::Stored);
        assert_eq!(s.offer(mk(1, 5)), Offer::Stale);
        assert_eq!(s.offer(mk(1, 4)), Offer::Stale);
        assert_eq!(s.offer(mk(1, 6)), Offer::Stored);
        assert_eq!(s.counter(&Address::new_from_array([1; 32])), Some(6));
        assert_eq!(s.offer(mk(2, 1)), Offer::Full, "bounded");
        s.prune(11, Duration::from_secs(60));
        assert!(s.is_empty(), "lease [10,10] ended before round 11");
    }

    #[test]
    fn every_reject_maps_to_a_contract_ack_code() {
        let labels: std::collections::HashSet<_> = Reject::ALL.iter().map(|r| r.reason()).collect();
        assert_eq!(labels.len(), Reject::ALL.len(), "metric labels are unique");
        for r in Reject::ALL {
            assert!(ACK_CODES[1..].contains(&r.ack_code()), "{r:?}");
        }
        let used: std::collections::HashSet<_> = Reject::ALL.iter().map(|r| r.ack_code()).collect();
        assert_eq!(used.len(), 6, "every refusal code is reachable");
    }

    #[test]
    fn numbers_or_decimal_strings() {
        let n: HeartbeatSubmission = serde_json::from_str(
            r#"{"type":"heartbeat","rig":"x","counter":"18446744073709551615","shift_id":3,"round_id":"7","lease_rounds":"2","sig":"00","extra":{"a":1}}"#,
        )
        .unwrap();
        assert_eq!((n.counter, n.shift_id, n.round_id, n.lease_rounds, n.sig64.as_str()), (u64::MAX, 3, 7, 2, "00"));
        for bad in [r#""-1""#, r#""1e3""#, r#""0x10""#, r#""""#, "-1", "1.5", r#""18446744073709551616""#, "null", "true"] {
            let j = format!(r#"{{"rig":"x","counter":{bad},"shift_id":1,"round_id":1,"lease_rounds":1,"sig64":"00"}}"#);
            assert!(serde_json::from_str::<HeartbeatSubmission>(&j).is_err(), "{bad}");
        }
        // lease_rounds 300 parses, then fails the static rule (lease_invalid, not malformed).
        let s: HeartbeatSubmission = serde_json::from_str(
            r#"{"rig":"11111111111111111111111111111111","counter":1,"shift_id":1,"round_id":1,"lease_rounds":300,"sig64":"00"}"#,
        )
        .unwrap();
        assert_eq!(ParsedHeartbeat::parse(&s).map(|_| ()), Err(Reject::BadEncoding), "sig checked first");
        let s = HeartbeatSubmission { sig64: hex::encode([1u8; 64]), ..s };
        assert_eq!(ParsedHeartbeat::parse(&s).map(|_| ()), Err(Reject::BadLease));
        assert_eq!(Reject::BadLease.ack_code(), "lease_invalid");
    }

    #[test]
    fn signal_reasons() {
        let sub = |reason: u64| SignalSubmission {
            rig: "11111111111111111111111111111111".into(),
            counter: 1,
            shift_id: 1,
            reason,
            sig64: base64::engine::general_purpose::STANDARD.encode([1u8; 64]),
        };
        for r in [1, 2, 4, 5, 6, 7, 8] {
            assert!(ParsedSignal::parse(SignalKind::Break, &sub(r)).is_ok(), "break {r}");
        }
        for r in [0, 3, 9, 300] {
            assert_eq!(ParsedSignal::parse(SignalKind::Break, &sub(r)).map(|_| ()), Err(Reject::BadReason), "break {r}");
        }
        assert!(ParsedSignal::parse(SignalKind::Freeze, &sub(3)).is_ok());
        assert_eq!(ParsedSignal::parse(SignalKind::Freeze, &sub(1)).map(|_| ()), Err(Reject::BadReason));
        assert_eq!(Reject::BadReason.ack_code(), "malformed");
    }

    #[test]
    fn a_break_is_landed_only_while_the_plan_window_is_open() {
        let rule = WindowRule::default();
        let end = 1_790_000_000i64;
        // Inside the window, with room for the landing.
        assert!(rule.allows(SignalKind::Break, end, end - 3_600));
        assert!(rule.allows(SignalKind::Break, end, end - 5), "exactly the margin");
        // Inside the margin, at the end, and after it: not landed.
        assert!(!rule.allows(SignalKind::Break, end, end - 4));
        assert!(!rule.allows(SignalKind::Break, end, end));
        assert!(!rule.allows(SignalKind::Break, end, end + 1));
        assert!(!rule.allows(SignalKind::Break, end, i64::MAX), "no overflow");
        // A FREEZE is a safety action: always landed.
        assert!(rule.allows(SignalKind::Freeze, end, end + 86_400));
        // The rule can be switched off, and the margin set to zero.
        assert!(WindowRule { enabled: false, margin_secs: 5 }.allows(SignalKind::Break, end, end + 1));
        let tight = WindowRule { enabled: true, margin_secs: 0 };
        assert!(tight.allows(SignalKind::Break, end, end) && !tight.allows(SignalKind::Break, end, end + 1));
        // Contract A has no "accepted but ignored" code: the phone is told lease_invalid.
        assert_eq!(Reject::WindowEnded.ack_code(), "lease_invalid");
        assert_eq!(Reject::WindowEnded.reason(), "window_ended");
        assert!(ACK_CODES.contains(&Reject::WindowEnded.ack_code()));
        let s = VerifiedSignal {
            kind: SignalKind::Break,
            rig: Address::new_from_array([1; 32]),
            authority: Address::new_from_array([2; 32]),
            counter: 9,
            shift_id: 3,
            reason: 1,
            sig: [0; 64],
            pubkey: [2; 33],
            digest: [0; 32],
            rig_state: RigState::Down,
            plan_window_end_ts: end,
        };
        assert_eq!(rule.check(&s, end - 60), Ok(()));
        assert_eq!(rule.check(&s, end + 60), Err(Reject::WindowEnded));
        assert_eq!(rule.check(&VerifiedSignal { kind: SignalKind::Freeze, reason: 3, ..s }, end + 60), Ok(()));
    }
}
