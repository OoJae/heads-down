//! Fixed vectors produced by an independent implementation
//! (`test-fixtures/vectors/gen_vectors.py`: Python `struct` + `hashlib`, arbitrary-precision
//! integers, and OpenSSL through `cryptography`). The same JSON is meant for the program
//! and Android test suites, so all three agree on the bytes a phone signs.

use hd_crank::{
    gate,
    hd::{self, HeartbeatFields, PlanFields},
};
use serde_json::Value;
use solana_address::Address;

fn vectors() -> Value {
    let raw = include_str!("../test-fixtures/vectors/interface.json");
    serde_json::from_str(raw).expect("valid json")
}

fn hex32(v: &Value) -> [u8; 32] {
    hex::decode(v.as_str().unwrap()).unwrap().try_into().unwrap()
}

fn rig(v: &Value) -> Address {
    Address::new_from_array(hex32(&v["rig_hex"]))
}

#[test]
fn program_id_matches_independent_base58_decode() {
    let v = vectors();
    assert_eq!(hd::PROGRAM_ID.as_ref(), &hex32(&v["program_id_hex"]));
}

#[test]
fn heartbeat_preimage_and_digest() {
    let v = vectors();
    let h = &v["heartbeat"];
    let fields = HeartbeatFields {
        counter: h["counter"].as_u64().unwrap(),
        shift_id: h["shift_id"].as_u64().unwrap(),
        round_id: h["round_id"].as_u64().unwrap(),
        lease_rounds: h["lease_rounds"].as_u64().unwrap() as u8,
    };
    let pre = hd::heartbeat_preimage(&hd::PROGRAM_ID, &rig(&v), &fields);
    assert_eq!(pre.len(), 94);
    assert_eq!(hex::encode(pre), h["preimage_hex"].as_str().unwrap());
    assert_eq!(hex::encode(hd::digest(&pre)), h["digest_hex"].as_str().unwrap());
    // Byte offsets are the running sums of the field sizes (no padding).
    assert_eq!(&pre[0..4], b"HDv1");
    assert_eq!(pre[68], hd::KIND_HEARTBEAT);
    assert_eq!(pre[93], 2);
}

#[test]
fn break_preimage_and_digest() {
    let v = vectors();
    let b = &v["break"];
    let pre = hd::break_preimage(
        &hd::PROGRAM_ID,
        &rig(&v),
        b["kind"].as_u64().unwrap() as u8,
        b["counter"].as_u64().unwrap(),
        b["shift_id"].as_u64().unwrap(),
        b["reason"].as_u64().unwrap() as u8,
    );
    assert_eq!(pre.len(), 86);
    assert_eq!(hex::encode(pre), b["preimage_hex"].as_str().unwrap());
    assert_eq!(hex::encode(hd::digest(&pre)), b["digest_hex"].as_str().unwrap());
}

#[test]
fn plan_preimage_and_digest() {
    let v = vectors();
    let p = &v["plan"];
    let f = PlanFields {
        counter: p["counter"].as_u64().unwrap(),
        max_ev_cost: p["max_ev_cost"].as_u64().unwrap(),
        dig_lamports: p["dig_lamports"].as_u64().unwrap(),
        split: p["split"].as_u64().unwrap() as u8,
        solo: p["solo"].as_u64().unwrap() as u8,
        lease: p["lease"].as_u64().unwrap() as u8,
        flags: p["flags"].as_u64().unwrap() as u8,
        window_start: p["window_start"].as_i64().unwrap(),
        window_end: p["window_end"].as_i64().unwrap(),
    };
    let pre = hd::plan_preimage(&hd::PROGRAM_ID, &rig(&v), &f);
    assert_eq!(pre.len(), 113);
    assert_eq!(hex::encode(pre), p["preimage_hex"].as_str().unwrap());
    assert_eq!(hex::encode(hd::digest(&pre)), p["digest_hex"].as_str().unwrap());
}

#[test]
fn ema_ev_matches_the_python_reference() {
    // ml/forecaster/orelib.py::ema_ev_lamports, evaluated with Python's big integers.
    let v = vectors();
    for case in v["ema_ev"].as_array().unwrap() {
        let ema = case["ema"].as_u64().unwrap();
        let pot = case["pot"].as_u64().unwrap();
        let want = case["ema_ev"].as_u64().unwrap();
        assert_eq!(gate::ema_ev(ema, pot), Some(want), "ema={ema} pot={pot}");
    }
    // Live values from docs/ORE.md (2026-09-29): EMA 0.919 SOL/ORE, pot 344 ORE
    // -> the pot-adjusted cost is ~0.653 SOL/ORE.
    assert_eq!(gate::ema_ev(918_782_720, 344 * 100_000_000_000), Some(653_163_071));
}

#[test]
fn changing_any_field_changes_the_digest() {
    let v = vectors();
    let base = HeartbeatFields { counter: 7, shift_id: 3, round_id: 422_601, lease_rounds: 2 };
    let d0 = hd::digest(&hd::heartbeat_preimage(&hd::PROGRAM_ID, &rig(&v), &base));
    let variants = [
        HeartbeatFields { counter: 8, ..base },
        HeartbeatFields { shift_id: 4, ..base },
        HeartbeatFields { round_id: 422_602, ..base },
        HeartbeatFields { lease_rounds: 1, ..base },
    ];
    for f in variants {
        assert_ne!(hd::digest(&hd::heartbeat_preimage(&hd::PROGRAM_ID, &rig(&v), &f)), d0);
    }
    let other_program = Address::new_from_array([0xAB; 32]);
    assert_ne!(hd::digest(&hd::heartbeat_preimage(&other_program, &rig(&v), &base)), d0);
    let other_rig = Address::new_from_array([0xCD; 32]);
    assert_ne!(hd::digest(&hd::heartbeat_preimage(&hd::PROGRAM_ID, &other_rig, &base)), d0);
}
