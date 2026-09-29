//! Heartbeat intake logic: parse, check, verify P-256 off-chain, keep the latest per rig.
//!
//! Heartbeats are **self-authenticating**. The crank needs no session or auth token: a
//! heartbeat is only useful if it verifies against the P-256 key registered in the rig's
//! on-chain `Rig` account, over `SHA-256(HEARTBEAT preimage)` exactly as the program will
//! rebuild it (`hd::heartbeat_preimage`). The crank verifies every signature before it
//! spends a lamport on a transaction, because one bad signature in a Secp256r1SigVerify
//! instruction fails the whole batch.
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

use crate::hd::{self, HeartbeatFields, Rig, MAX_LEASE_ROUNDS};
use crate::ratelimit::{KeyedLimiter, Quota};

/// What a phone sends (one JSON text frame per heartbeat):
///
/// ```json
/// {"type":"heartbeat","rig":"<base58>","counter":7,"shift_id":3,"round_id":422601,
///  "lease_rounds":2,"sig64":"<base64 or hex r||s>","pubkey":"<optional hex/base64, 33 bytes>"}
/// ```
#[derive(Clone, Debug, Deserialize, Serialize, PartialEq, Eq)]
#[serde(deny_unknown_fields)]
pub struct HeartbeatSubmission {
    /// Rig PDA, base58.
    pub rig: String,
    /// Strictly increasing per rig.
    pub counter: u64,
    /// `rig.shift_id` the phone armed.
    pub shift_id: u64,
    /// `Board.round_id` at signing.
    pub round_id: u64,
    /// Requested lease, 1..=3.
    pub lease_rounds: u8,
    /// 64-byte raw `r || s` (high-S accepted and normalized), base64 or hex.
    pub sig64: String,
    /// Optional 33-byte compressed key. Only a consistency hint: the key that counts is
    /// always the one in the Rig account.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub pubkey: Option<String>,
}

/// Why a heartbeat was refused. [`Reject::reason`] is the metric label and the `reason`
/// the phone sees.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum Reject {
    /// Not valid JSON / missing or unknown fields / wrong message type.
    Malformed,
    /// Bad base58 / base64 / hex or wrong byte length.
    BadEncoding,
    /// `lease_rounds` outside 1..=3.
    BadLease,
    /// `round_id` ahead of the chain by more than one round.
    RoundInFuture,
    /// The lease this heartbeat could grant has already ended.
    Expired,
    /// A heartbeat with this or a higher counter is already held or on-chain.
    StaleCounter,
    /// No Rig account at that address (or not a heads_down Rig).
    UnknownRig,
    /// `shift_id` differs from the rig's current shift.
    ShiftMismatch,
    /// The rig is not Armed or Down.
    NotArmed,
    /// `pubkey` given and different from the registered key.
    PubkeyMismatch,
    /// ECDSA verification failed.
    BadSignature,
    /// Per-IP rate limit.
    RateLimitedIp,
    /// Per-rig rate limit.
    RateLimitedRig,
    /// Verification capacity exhausted; retry later.
    Busy,
    /// Message larger than the cap.
    TooLarge,
    /// Could not read the chain to check the rig; retry later.
    Unavailable,
}

impl Reject {
    /// Stable snake_case label.
    pub fn reason(self) -> &'static str {
        match self {
            Reject::Malformed => "malformed",
            Reject::BadEncoding => "bad_encoding",
            Reject::BadLease => "bad_lease",
            Reject::RoundInFuture => "round_in_future",
            Reject::Expired => "expired",
            Reject::StaleCounter => "stale_counter",
            Reject::UnknownRig => "unknown_rig",
            Reject::ShiftMismatch => "shift_mismatch",
            Reject::NotArmed => "not_armed",
            Reject::PubkeyMismatch => "pubkey_mismatch",
            Reject::BadSignature => "bad_signature",
            Reject::RateLimitedIp => "rate_limited_ip",
            Reject::RateLimitedRig => "rate_limited_rig",
            Reject::Busy => "busy",
            Reject::TooLarge => "too_large",
            Reject::Unavailable => "unavailable",
        }
    }

    /// Every variant (metrics pre-registration).
    pub const ALL: [Reject; 16] = [
        Reject::Malformed,
        Reject::BadEncoding,
        Reject::BadLease,
        Reject::RoundInFuture,
        Reject::Expired,
        Reject::StaleCounter,
        Reject::UnknownRig,
        Reject::ShiftMismatch,
        Reject::NotArmed,
        Reject::PubkeyMismatch,
        Reject::BadSignature,
        Reject::RateLimitedIp,
        Reject::RateLimitedRig,
        Reject::Busy,
        Reject::TooLarge,
        Reject::Unavailable,
    ];
}

/// Decode `N` bytes given as hex (`2N` chars) or standard base64.
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
    /// Optional claimed key.
    pub claimed_pubkey: Option<[u8; 33]>,
}

impl ParsedHeartbeat {
    /// Decode encodings and check the static rules.
    pub fn parse(s: &HeartbeatSubmission) -> Result<Self, Reject> {
        let rig = decode_address(&s.rig).ok_or(Reject::BadEncoding)?;
        let sig = decode_fixed::<64>(&s.sig64).ok_or(Reject::BadEncoding)?;
        let claimed_pubkey = match &s.pubkey {
            Some(p) => Some(decode_fixed::<33>(p).ok_or(Reject::BadEncoding)?),
            None => None,
        };
        if s.lease_rounds == 0 || s.lease_rounds > MAX_LEASE_ROUNDS {
            return Err(Reject::BadLease);
        }
        Ok(ParsedHeartbeat {
            rig,
            fields: HeartbeatFields {
                counter: s.counter,
                shift_id: s.shift_id,
                round_id: s.round_id,
                lease_rounds: s.lease_rounds,
            },
            sig,
            claimed_pubkey,
        })
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

    /// Forget a rig (e.g. its heartbeat landed on-chain and was consumed).
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
    /// `Err(Reject::Unavailable)` when the source fails or the fetch budget is spent.
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

/// The full intake check for one heartbeat, minus rate limiting (done by the caller, which
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
    /// Check and verify; on success the heartbeat is the latest for its rig.
    pub async fn process(&self, p: &ParsedHeartbeat, current_round: Option<u64>) -> Result<VerifiedHeartbeat, Reject> {
        if let Some(cur) = current_round {
            check_freshness(&p.fields, cur)?;
        }
        if self.store.counter(&p.rig).is_some_and(|c| c >= p.fields.counter) {
            return Err(Reject::StaleCounter);
        }
        let mut rig = self.rigs.get(&p.rig, false).await?.ok_or(Reject::UnknownRig)?;
        // A mismatch may just mean the cache predates arm_shift / rotate_key / a dig that
        // advanced hb_counter: refresh once, then decide.
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
        if !rig.state.diggable() {
            return Err(Reject::NotArmed);
        }
        if rig.shift_id != p.fields.shift_id {
            return Err(Reject::ShiftMismatch);
        }
        if p.fields.counter <= rig.hb_counter {
            return Err(Reject::StaleCounter);
        }
        if p.claimed_pubkey.is_some_and(|k| k != rig.p256_pubkey) {
            return Err(Reject::PubkeyMismatch);
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
}
