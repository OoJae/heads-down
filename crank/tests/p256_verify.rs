//! Off-chain P-256 verification of heartbeats: OpenSSL-made vectors, high-S normalization,
//! wrong keys, wrong messages, out-of-range scalars, and the full intake verifier against an
//! in-memory Rig source.

use std::collections::HashMap;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use async_trait::async_trait;
use hd_crank::hd::{self, HeartbeatFields, Rig, RigState};
use hd_crank::heartbeat::{
    verify_signature, HeartbeatStore, HeartbeatSubmission, ParsedHeartbeat, Reject, RigCache, RigSource, Verifier,
};
use p256::ecdsa::{signature::Signer, Signature, SigningKey};
use serde_json::Value;
use solana_address::Address;

const N: [u8; 32] = p256_introspect::SECP256R1_ORDER;

/// `n - s` on big-endian 32-byte integers (the malleable twin of a signature).
fn negate_s(sig: &[u8; 64]) -> [u8; 64] {
    let mut out = *sig;
    let mut borrow = 0i16;
    for i in (0..32).rev() {
        let mut d = i16::from(N[i]) - i16::from(sig[32 + i]) - borrow;
        borrow = if d < 0 {
            d += 256;
            1
        } else {
            0
        };
        out[32 + i] = d as u8;
    }
    out
}

fn vectors() -> Value {
    serde_json::from_str(include_str!("../test-fixtures/vectors/interface.json")).unwrap()
}

fn h<const L: usize>(v: &Value) -> [u8; L] {
    hex::decode(v.as_str().unwrap()).unwrap().try_into().unwrap()
}

#[test]
fn openssl_signature_verifies_low_and_high_s() {
    let v = vectors();
    let pk: [u8; 33] = h(&v["p256"]["pubkey_hex"]);
    let digest: [u8; 32] = h(&v["heartbeat"]["digest_hex"]);
    let r: [u8; 32] = h(&v["p256"]["r_hex"]);
    let s_low: [u8; 32] = h(&v["p256"]["s_low_hex"]);
    let s_high: [u8; 32] = h(&v["p256"]["s_high_hex"]);
    let mut low = [0u8; 64];
    low[..32].copy_from_slice(&r);
    low[32..].copy_from_slice(&s_low);
    let mut high = low;
    high[32..].copy_from_slice(&s_high);
    assert_eq!(negate_s(&low), high);

    assert_eq!(verify_signature(&pk, &digest, &low), Ok(low));
    // High-S is the valid malleable twin: normalized, then accepted.
    assert_eq!(verify_signature(&pk, &digest, &high), Ok(low));
    assert!(!p256_introspect::is_low_s(high[32..].try_into().unwrap()));
}

#[test]
fn wrong_key_wrong_message_and_bad_scalars_are_rejected() {
    let v = vectors();
    let pk: [u8; 33] = h(&v["p256"]["pubkey_hex"]);
    let digest: [u8; 32] = h(&v["heartbeat"]["digest_hex"]);
    let mut sig = [0u8; 64];
    sig[..32].copy_from_slice(&h::<32>(&v["p256"]["r_hex"]));
    sig[32..].copy_from_slice(&h::<32>(&v["p256"]["s_low_hex"]));

    // Another key.
    let other = SigningKey::from_slice(&[0x42; 32]).unwrap();
    let other_pk: [u8; 33] = other.verifying_key().to_sec1_point(true).as_bytes().try_into().unwrap();
    assert_eq!(verify_signature(&other_pk, &digest, &sig), Err(Reject::BadSignature));
    // Same key, the key's parity flipped (a different point or none at all).
    let mut flipped = pk;
    flipped[0] ^= 1;
    assert_eq!(verify_signature(&flipped, &digest, &sig), Err(Reject::BadSignature));
    // Uncompressed-prefix / garbage key.
    let mut junk = pk;
    junk[0] = 0x04;
    assert_eq!(verify_signature(&junk, &digest, &sig), Err(Reject::BadSignature));
    // A different message: the BREAK digest.
    let other_digest: [u8; 32] = h(&v["break"]["digest_hex"]);
    assert_eq!(verify_signature(&pk, &other_digest, &sig), Err(Reject::BadSignature));
    // r = 0, s = 0, r = n, s = n.
    let mut z = sig;
    z[..32].fill(0);
    assert_eq!(verify_signature(&pk, &digest, &z), Err(Reject::BadSignature));
    let mut z = sig;
    z[32..].fill(0);
    assert_eq!(verify_signature(&pk, &digest, &z), Err(Reject::BadSignature));
    let mut rn = sig;
    rn[..32].copy_from_slice(&N);
    assert_eq!(verify_signature(&pk, &digest, &rn), Err(Reject::BadSignature));
    let mut sn = sig;
    sn[32..].copy_from_slice(&N);
    assert_eq!(verify_signature(&pk, &digest, &sn), Err(Reject::BadSignature));
}

#[test]
fn random_keys_roundtrip_and_half_are_high_s() {
    let mut high_seen = 0;
    for i in 0u8..64 {
        let sk = SigningKey::from_slice(&[i.wrapping_add(1); 32]).unwrap();
        let pk: [u8; 33] = sk.verifying_key().to_sec1_point(true).as_bytes().try_into().unwrap();
        let rig = Address::new_from_array([i; 32]);
        let f = HeartbeatFields { counter: u64::from(i) + 1, shift_id: 2, round_id: 1_000 + u64::from(i), lease_rounds: 1 };
        let digest = hd::digest(&hd::heartbeat_preimage(&hd::PROGRAM_ID, &rig, &f));
        // SHA256withECDSA over the 32-byte digest, like Android Keystore.
        let sig: Signature = sk.sign(&digest);
        let raw: [u8; 64] = sig.to_bytes().into();
        let low = verify_signature(&pk, &digest, &raw).unwrap();
        assert!(p256_introspect::is_low_s(low[32..].try_into().unwrap()));
        if low != raw {
            high_seen += 1;
        }
        assert_eq!(verify_signature(&pk, &digest, &negate_s(&low)), Ok(low));
    }
    assert!(high_seen > 0, "RFC 6979 signatures are high-S about half the time");
}

// ---------------------------------------------------------------------------------------
// Full verifier with an in-memory rig source.

#[derive(Clone, Default)]
struct MapSource {
    rigs: Arc<Mutex<HashMap<Address, Rig>>>,
    fetches: Arc<Mutex<u32>>,
}

#[async_trait]
impl RigSource for MapSource {
    async fn fetch_rig(&self, rig: &Address) -> anyhow::Result<Option<Rig>> {
        *self.fetches.lock().unwrap() += 1;
        Ok(self.rigs.lock().unwrap().get(rig).cloned())
    }
}

fn rig_account(pk: [u8; 33]) -> Rig {
    Rig {
        bump: 255,
        authority: Address::new_from_array([9; 32]),
        p256_pubkey: pk,
        attestation_level: 1,
        state: RigState::Armed,
        plan_split_tiles: 15,
        plan_lease_rounds: 3,
        shift_id: 4,
        hb_counter: 10,
        freezes_left: 2,
        shift_open: true,
        ..Rig::default()
    }
}

struct Phone {
    sk: SigningKey,
    rig: Address,
}

impl Phone {
    fn new(seed: u8) -> Self {
        Phone { sk: SigningKey::from_slice(&[seed; 32]).unwrap(), rig: Address::new_from_array([seed.wrapping_add(100); 32]) }
    }
    fn pk(&self) -> [u8; 33] {
        self.sk.verifying_key().to_sec1_point(true).as_bytes().try_into().unwrap()
    }
    fn submit(&self, counter: u64, shift_id: u64, round_id: u64, lease: u8) -> HeartbeatSubmission {
        let f = HeartbeatFields { counter, shift_id, round_id, lease_rounds: lease };
        let digest = hd::digest(&hd::heartbeat_preimage(&hd::PROGRAM_ID, &self.rig, &f));
        let sig: Signature = self.sk.sign(&digest);
        let raw: [u8; 64] = sig.to_bytes().into();
        HeartbeatSubmission {
            rig: self.rig.to_string(),
            counter,
            shift_id,
            round_id,
            lease_rounds: u64::from(lease),
            sig64: hex::encode(raw),
        }
    }
}

fn verifier(src: MapSource) -> Verifier<MapSource> {
    Verifier {
        program_id: hd::PROGRAM_ID,
        rigs: RigCache::new(src, Duration::from_secs(60), Duration::from_secs(30), 1000, 100.0),
        store: Arc::new(HeartbeatStore::new(1000)),
    }
}

async fn run(v: &Verifier<MapSource>, s: &HeartbeatSubmission, round: u64) -> Result<(), Reject> {
    let p = ParsedHeartbeat::parse(s)?;
    v.process(&p, Some(round)).await.map(|_| ())
}

#[tokio::test]
async fn verifier_accepts_then_rejects_replays_and_mismatches() {
    let phone = Phone::new(1);
    let src = MapSource::default();
    src.rigs.lock().unwrap().insert(phone.rig, rig_account(phone.pk()));
    let v = verifier(src.clone());

    assert_eq!(run(&v, &phone.submit(11, 4, 500, 2), 500).await, Ok(()));
    assert_eq!(v.store.get(&phone.rig).unwrap().fields.counter, 11);
    // Replay of the same heartbeat, and an older counter.
    assert_eq!(run(&v, &phone.submit(11, 4, 500, 2), 500).await, Err(Reject::StaleCounter));
    assert_eq!(run(&v, &phone.submit(10, 4, 500, 2), 500).await, Err(Reject::StaleCounter));
    // Wrong shift.
    assert_eq!(run(&v, &phone.submit(12, 5, 500, 2), 500).await, Err(Reject::ShiftMismatch));
    // Expired lease and future round.
    assert_eq!(run(&v, &phone.submit(13, 4, 497, 3), 500).await, Err(Reject::Expired));
    assert_eq!(run(&v, &phone.submit(13, 4, 502, 1), 500).await, Err(Reject::RoundInFuture));
    // Lease of 0 or 4.
    assert_eq!(run(&v, &phone.submit(13, 4, 500, 0), 500).await, Err(Reject::BadLease));
    assert_eq!(run(&v, &phone.submit(13, 4, 500, 4), 500).await, Err(Reject::BadLease));
    // A newer valid one replaces the held heartbeat, high-S or not.
    assert_eq!(run(&v, &phone.submit(14, 4, 500, 1), 500).await, Ok(()));
    assert_eq!(v.store.get(&phone.rig).unwrap().fields.counter, 14);
}

#[tokio::test]
async fn verifier_rejects_wrong_key_unknown_rig_and_not_armed() {
    let phone = Phone::new(2);
    let thief = Phone { sk: SigningKey::from_slice(&[77; 32]).unwrap(), rig: phone.rig };
    let src = MapSource::default();
    src.rigs.lock().unwrap().insert(phone.rig, rig_account(phone.pk()));
    let v = verifier(src.clone());

    // Signed by a key that is not the rig's registered key.
    assert_eq!(run(&v, &thief.submit(11, 4, 500, 1), 500).await, Err(Reject::BadSignature));
    assert_eq!(Reject::BadSignature.ack_code(), "bad_signature");
    // Tampered field after signing.
    let mut s = phone.submit(11, 4, 500, 1);
    s.lease_rounds = 3;
    assert_eq!(run(&v, &s, 500).await, Err(Reject::BadSignature));
    // Unknown rig; the negative cache stops a second fetch.
    let ghost = Phone::new(3);
    let before = *src.fetches.lock().unwrap();
    assert_eq!(run(&v, &ghost.submit(1, 1, 500, 1), 500).await, Err(Reject::UnknownRig));
    assert_eq!(run(&v, &ghost.submit(2, 1, 500, 1), 500).await, Err(Reject::UnknownRig));
    assert_eq!(*src.fetches.lock().unwrap(), before + 1, "negative cache");
    // Not armed.
    src.rigs.lock().unwrap().get_mut(&phone.rig).unwrap().state = RigState::Frozen;
    let v2 = verifier(src.clone());
    assert_eq!(run(&v2, &phone.submit(11, 4, 500, 1), 500).await, Err(Reject::NotArmed));
    // Garbage encodings.
    let mut s = phone.submit(11, 4, 500, 1);
    s.rig = "0OIl".into();
    assert_eq!(run(&v, &s, 500).await, Err(Reject::BadEncoding));
    let mut s = phone.submit(11, 4, 500, 1);
    s.sig64 = "abcd".into();
    assert_eq!(run(&v, &s, 500).await, Err(Reject::BadEncoding));
}

#[tokio::test]
async fn cache_refreshes_once_after_arm_or_rotation() {
    let phone = Phone::new(4);
    let src = MapSource::default();
    src.rigs.lock().unwrap().insert(phone.rig, rig_account(phone.pk()));
    let v = verifier(src.clone());
    assert_eq!(run(&v, &phone.submit(11, 4, 500, 1), 500).await, Ok(()));
    // The phone re-arms (shift 5) and rotates its key; the cached copy is stale.
    let rotated = Phone { sk: SigningKey::from_slice(&[99; 32]).unwrap(), rig: phone.rig };
    {
        let mut m = src.rigs.lock().unwrap();
        let r = m.get_mut(&phone.rig).unwrap();
        r.shift_id = 5;
        r.p256_pubkey = rotated.pk();
    }
    let s = rotated.submit(12, 5, 500, 1);
    assert_eq!(run(&v, &s, 500).await, Ok(()), "refreshed on shift mismatch, verified with the rotated key");
    // The old key no longer works.
    assert_eq!(run(&v, &phone.submit(13, 5, 500, 1), 500).await, Err(Reject::BadSignature));
}

#[test]
fn submission_json_follows_contract_a() {
    let ok = r#"{"rig":"11111111111111111111111111111111","counter":1,"shift_id":1,"round_id":1,"lease_rounds":1,"sig64":"00"}"#;
    assert!(serde_json::from_str::<HeartbeatSubmission>(ok).is_ok());
    // Unknown fields are ignored, not rejected.
    let extra = r#"{"type":"heartbeat","rig":"x","counter":1,"shift_id":1,"round_id":1,"lease_rounds":1,"sig64":"00","evil":1}"#;
    assert!(serde_json::from_str::<HeartbeatSubmission>(extra).is_ok());
    // Integers may be decimal strings; never negative, fractional or out of range.
    let strings = r#"{"rig":"x","counter":"18446744073709551615","shift_id":"1","round_id":"1","lease_rounds":"1","sig64":"00"}"#;
    assert_eq!(serde_json::from_str::<HeartbeatSubmission>(strings).unwrap().counter, u64::MAX);
    let neg = r#"{"rig":"x","counter":-1,"shift_id":1,"round_id":1,"lease_rounds":1,"sig64":"00"}"#;
    assert!(serde_json::from_str::<HeartbeatSubmission>(neg).is_err());
    let frac = r#"{"rig":"x","counter":1.5,"shift_id":1,"round_id":1,"lease_rounds":1,"sig64":"00"}"#;
    assert!(serde_json::from_str::<HeartbeatSubmission>(frac).is_err());
    // A lease of 300 parses and is refused by the static rule (lease_invalid, not malformed).
    use base64::Engine;
    let sig = base64::engine::general_purpose::STANDARD.encode([1u8; 64]);
    let big = format!(r#"{{"rig":"11111111111111111111111111111111","counter":1,"shift_id":1,"round_id":1,"lease_rounds":300,"sig64":"{sig}"}}"#);
    let s = serde_json::from_str::<HeartbeatSubmission>(&big).unwrap();
    assert_eq!(ParsedHeartbeat::parse(&s).map(|_| ()), Err(Reject::BadLease));
    // Missing a listed field: malformed.
    let missing = r#"{"rig":"x","counter":1,"shift_id":1,"round_id":1,"sig64":"00"}"#;
    assert!(serde_json::from_str::<HeartbeatSubmission>(missing).is_err());
}
