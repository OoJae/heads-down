//! The crank against the frozen contract: `programs/heads-down/vectors/*.json` (INTERFACE v1.1),
//! generated from real LiteSVM runs of the program on a mainnet-ORE fork. Every builder the
//! crank sends with (`dig`, `record_heartbeats`, `break_shift` / `freeze_rig` on the P-256
//! path, `end_shift`, the Secp256r1SigVerify data), every preimage, every event decoder and
//! every skip-code name must match those bytes exactly.

use hd_crank::hd::{self, DigEntry, HdEvent, HeartbeatFields, PlanFields, RigAccounts, SignalKind};
use hd_crank::heartbeat::verify_signature;
use hd_crank::tx;
use serde_json::Value;
use solana_address::Address;
use solana_instruction::Instruction;

fn load(name: &str) -> Value {
    let raw = match name {
        "instructions" => include_str!("../../programs/heads-down/vectors/instructions.json"),
        "messages" => include_str!("../../programs/heads-down/vectors/messages.json"),
        "events" => include_str!("../../programs/heads-down/vectors/events.json"),
        _ => unreachable!(),
    };
    serde_json::from_str(raw).unwrap()
}

fn addr(v: &Value) -> Address {
    v.as_str().unwrap().parse().unwrap()
}

fn u64s(v: &Value) -> u64 {
    match v {
        Value::String(s) => s.parse().unwrap(),
        other => other.as_u64().unwrap(),
    }
}

fn hexb(v: &Value) -> Vec<u8> {
    hex::decode(v.as_str().unwrap()).unwrap()
}

fn vector<'a>(all: &'a Value, name: &str) -> &'a Value {
    all["instructions"].as_array().unwrap().iter().find(|i| i["name"] == name).unwrap_or_else(|| panic!("vector {name}"))
}

fn account(v: &Value, role: &str) -> Address {
    addr(&v["accounts"].as_array().unwrap().iter().find(|a| a["role"] == role).unwrap_or_else(|| panic!("role {role}"))["pubkey"])
}

fn entries(v: &Value) -> Vec<DigEntry> {
    v["args"]["entries"]
        .as_array()
        .unwrap()
        .iter()
        .map(|e| DigEntry {
            hb_ix: e["hb_ix"].as_u64().unwrap() as u8,
            hb_sig_index: e["hb_sig_index"].as_u64().unwrap() as u8,
            counter: u64s(&e["counter"]),
            round_id: u64s(&e["round_id"]),
            lease_rounds: e["lease_rounds"].as_u64().unwrap() as u8,
        })
        .collect()
}

/// Data bytes and every account meta (address, signer, writable) must equal the vector's.
fn assert_matches(v: &Value, ix: &Instruction) {
    let name = v["name"].as_str().unwrap();
    assert_eq!(ix.program_id, hd::PROGRAM_ID, "{name}");
    assert_eq!(hex::encode(&ix.data), v["data_hex"].as_str().unwrap(), "{name}: data");
    let want: Vec<(Address, bool, bool)> = v["accounts"]
        .as_array()
        .unwrap()
        .iter()
        .map(|a| (addr(&a["pubkey"]), a["is_signer"].as_bool().unwrap(), a["is_writable"].as_bool().unwrap()))
        .collect();
    let got: Vec<(Address, bool, bool)> = ix.accounts.iter().map(|m| (m.pubkey, m.is_signer, m.is_writable)).collect();
    assert_eq!(got, want, "{name}: account metas");
}

/// Rebuild each Secp256r1SigVerify instruction of the vector's transaction from the
/// signatures inside it with the crank's builder: byte-identical.
fn assert_precompiles_rebuild(v: &Value) {
    for (i, ix) in v["transaction"]["instructions"].as_array().unwrap().iter().enumerate() {
        if ix["program_id"] != "Secp256r1SigVerify1111111111111111111111111" {
            continue;
        }
        let data = hexb(&ix["data_hex"]);
        let parsed = p256_introspect::Secp256r1Instruction::parse(&data, i as u16).unwrap();
        let sigs: Vec<([u8; 64], [u8; 33], [u8; 32])> = (0..parsed.num_signatures())
            .map(|k| {
                let e = parsed.entry(k).unwrap();
                (*e.signature, *e.public_key, e.message.try_into().unwrap())
            })
            .collect();
        let rebuilt = tx::precompile_ix(&sigs).unwrap();
        assert_eq!(rebuilt.program_id, hd::SECP256R1_PROGRAM_ID);
        assert_eq!(hex::encode(rebuilt.data), ix["data_hex"].as_str().unwrap(), "{}: precompile ix {i}", v["name"]);
    }
}

/// The events LiteSVM captured decode with the crank's decoder, field for field.
fn assert_events_decode(v: &Value) {
    for e in v["litesvm"]["events"].as_array().unwrap() {
        let ev = hd::parse_event(&hexb(&e["hex"])).unwrap_or_else(|| panic!("{}: {}", v["name"], e["event"]));
        check_event(&ev, e);
    }
}

fn check_event(ev: &HdEvent, e: &Value) {
    let f = &e["fields"];
    assert_eq!(ev.name(), e["event"].as_str().unwrap());
    assert_eq!(ev.rig(), Some(addr(&f["rig"])));
    match ev {
        HdEvent::RigDug { round_id, lamports, mask, ema_ev, .. } => {
            assert_eq!((*round_id, *lamports, u64::from(*mask), *ema_ev), (u64s(&f["round_id"]), u64s(&f["lamports"]), u64s(&f["mask"]), u64s(&f["ema_ev"])));
        }
        HdEvent::RigSkipped { round_id, error, .. } => {
            assert_eq!((*round_id, u64::from(*error)), (u64s(&f["round_id"]), u64s(&f["error"])));
        }
        HdEvent::ShiftArmed { shift_id, .. } => assert_eq!(*shift_id, u64s(&f["shift_id"])),
        HdEvent::ShiftEnded { shift_id, dark_rounds, rounds_dug, lamports, reason, .. } => {
            assert_eq!(
                (*shift_id, *dark_rounds, *rounds_dug, *lamports, u64::from(*reason)),
                (u64s(&f["shift_id"]), u64s(&f["dark_rounds"]), u64s(&f["rounds_dug"]), u64s(&f["lamports"]), u64s(&f["reason"]))
            );
        }
        HdEvent::ShiftEndedV2 { shift_id, dark_rounds, rounds_dug, lamports, reason, start_round, end_round, mode, .. } => {
            assert_eq!(
                (*shift_id, *dark_rounds, *rounds_dug, *lamports, u64::from(*reason), *start_round, *end_round, u64::from(*mode)),
                (
                    u64s(&f["shift_id"]),
                    u64s(&f["dark_rounds"]),
                    u64s(&f["rounds_dug"]),
                    u64s(&f["lamports"]),
                    u64s(&f["reason"]),
                    u64s(&f["start_round"]),
                    u64s(&f["end_round"]),
                    u64s(&f["mode"])
                )
            );
        }
        HdEvent::SeekerVerified { sgt_mint, member_number, .. } => {
            assert_eq!((*sgt_mint, *member_number), (addr(&f["sgt_mint"]), u64s(&f["member_number"])));
        }
        HdEvent::RigRegistered { authority, tier, attestation_level, .. } => {
            assert_eq!(
                (*authority, u64::from(*tier), u64::from(*attestation_level)),
                (addr(&f["authority"]), u64s(&f["tier"]), u64s(&f["attestation_level"]))
            );
        }
        HdEvent::RigClosed { .. } => {}
        HdEvent::HeartbeatsRecorded { round_id, dark_rounds_added, .. } => {
            assert_eq!((*round_id, *dark_rounds_added), (u64s(&f["round_id"]), u64s(&f["dark_rounds_added"])));
        }
        HdEvent::ShiftBroken { shift_id, reason, .. } => {
            assert_eq!((*shift_id, u64::from(*reason)), (u64s(&f["shift_id"]), u64s(&f["reason"])));
        }
        HdEvent::Other { tag, .. } => panic!("unknown tag {tag}"),
    }
}

#[test]
fn dig_vectors_match_the_crank_builder() {
    let all = load("instructions");
    for name in ["dig_fresh_heartbeat", "dig_reuse_lease", "dig_batch_two_rigs"] {
        let v = vector(&all, name);
        let es = entries(v);
        let rigs: Vec<(RigAccounts, DigEntry)> = es
            .iter()
            .enumerate()
            .map(|(i, e)| {
                let rig = account(v, &format!("rig[{i}]"));
                let authority = account(v, &format!("authority[{i}]"));
                let derived = RigAccounts::derive(rig, authority);
                assert_eq!(derived.automation, account(v, &format!("ore_automation[{i}]")), "{name}: Automation PDA");
                assert_eq!(derived.miner, account(v, &format!("ore_miner[{i}]")), "{name}: Miner PDA");
                assert_eq!(rig, hd::rig_pda(&hd::PROGRAM_ID, &authority).0, "{name}: Rig PDA");
                (derived, *e)
            })
            .collect();
        let ix = hd::dig_ix(&hd::PROGRAM_ID, &account(v, "cranker"), &account(v, "ore_round"), &rigs).unwrap();
        assert_matches(v, &ix);
        assert_precompiles_rebuild(v);
        assert_events_decode(v);
        // RigDug.lamports is SOL on squares: 10 squares x 100,000 (the Automation cap).
        for e in v["litesvm"]["events"].as_array().unwrap() {
            assert_eq!(u64s(&e["fields"]["lamports"]), 1_000_000, "{name}: no fee in RigDug.lamports");
        }
    }
}

#[test]
fn record_heartbeats_vector_matches() {
    let all = load("instructions");
    let v = vector(&all, "record_heartbeats");
    let es = entries(v);
    let rigs: Vec<(Address, DigEntry)> = es.iter().enumerate().map(|(i, e)| (account(v, &format!("rig[{i}]")), *e)).collect();
    let ix = hd::record_heartbeats_ix(&hd::PROGRAM_ID, &rigs).unwrap();
    assert_matches(v, &ix);
    assert_precompiles_rebuild(v);
    assert_events_decode(v);
}

#[test]
fn p256_break_and_freeze_vectors_match() {
    let all = load("instructions");
    for (name, kind) in [("break_shift_p256", SignalKind::Break), ("freeze_rig_p256", SignalKind::Freeze)] {
        let v = vector(&all, name);
        let a = &v["args"];
        assert_eq!(a["mode"], 1);
        let ix = hd::signal_p256_ix(
            &hd::PROGRAM_ID,
            kind,
            &account(v, "rig"),
            &account(v, "authority"),
            a["reason"].as_u64().unwrap() as u8,
            u64s(&a["counter"]),
            a["p256_ix"].as_u64().unwrap() as u8,
            a["p256_sig_index"].as_u64().unwrap() as u8,
        );
        assert_matches(v, &ix);
        assert_precompiles_rebuild(v);
        assert_events_decode(v);
        // The crank's own signal transaction puts the precompile at index 2 and names it.
        let tx_ixs = v["transaction"]["instructions"].as_array().unwrap();
        let pre = hexb(&tx_ixs[0]["data_hex"]);
        let parsed = p256_introspect::Secp256r1Instruction::parse(&pre, 0).unwrap();
        let e = parsed.entry(0).unwrap();
        let ixs = tx::signal_instructions(
            &hd::PROGRAM_ID,
            kind,
            &account(v, "rig"),
            &account(v, "authority"),
            a["reason"].as_u64().unwrap() as u8,
            u64s(&a["counter"]),
            *e.signature,
            *e.public_key,
            e.message.try_into().unwrap(),
            5_000,
            1,
        )
        .unwrap();
        assert_eq!(ixs[2].data, pre, "{name}: same precompile bytes at index 2");
        assert_eq!(ixs[3].data[11], tx::SIGNAL_P256_IX);
        assert_eq!(&ixs[3].data[..11], &ix.data[..11]);
    }
}

#[test]
fn end_shift_vectors_match() {
    let all = load("instructions");
    for name in ["end_shift_permissionless", "end_shift_authority"] {
        let v = vector(&all, name);
        let a = &v["args"];
        let ix = hd::end_shift_ix(&hd::PROGRAM_ID, &addr(&a["caller"]), &addr(&a["rig"]), u64s(&a["shift_id"]));
        assert_matches(v, &ix);
        assert_events_decode(v);
        // The decoder keeps only the V2 of the pair (INTERFACE §7: do not count tag 4 twice).
        use base64::Engine;
        let pid = hd::PROGRAM_ID.to_string();
        let mut logs = vec![format!("Program {pid} invoke [1]")];
        for e in v["litesvm"]["events"].as_array().unwrap() {
            logs.push(format!("Program data: {}", base64::engine::general_purpose::STANDARD.encode(hexb(&e["hex"]))));
        }
        logs.push(format!("Program {pid} success"));
        let evs = hd::events_from_logs(&hd::PROGRAM_ID, &logs);
        assert_eq!(evs.len(), 1, "{name}");
        assert_eq!(evs[0].name(), "ShiftEndedV2");
    }
}

#[test]
fn messages_match_preimages_digests_and_precompile_data() {
    let m = load("messages");
    let rig = addr(&m["rig"]);
    assert_eq!(hd::PROGRAM_ID, addr(&m["program_id"]));
    let pk: [u8; 33] = hexb(&m["p256_key"]["public_key_compressed_hex"]).try_into().unwrap();
    for msg in m["messages"].as_array().unwrap() {
        let name = msg["name"].as_str().unwrap();
        let f = &msg["fields"];
        let pre: Vec<u8> = match msg["kind"].as_u64().unwrap() {
            1 => hd::heartbeat_preimage(
                &hd::PROGRAM_ID,
                &rig,
                &HeartbeatFields {
                    counter: u64s(&f["counter"]),
                    shift_id: u64s(&f["shift_id"]),
                    round_id: u64s(&f["round_id"]),
                    lease_rounds: f["lease_rounds"].as_u64().unwrap() as u8,
                },
            )
            .to_vec(),
            k @ (2 | 3) => {
                let kind = if k == 2 { SignalKind::Break } else { SignalKind::Freeze };
                let reason = f["reason"].as_u64().unwrap() as u8;
                assert!(kind.reason_ok(reason), "{name}: the frame reason the intake accepts");
                hd::break_preimage(&hd::PROGRAM_ID, &rig, kind.message_kind(), u64s(&f["counter"]), u64s(&f["shift_id"]), reason).to_vec()
            }
            4 => {
                let p = &f["plan"];
                hd::plan_preimage(
                    &hd::PROGRAM_ID,
                    &rig,
                    &PlanFields {
                        counter: u64s(&f["counter"]),
                        max_ev_cost: u64s(&p["max_ev_cost"]),
                        dig_lamports: u64s(&p["dig_lamports"]),
                        split: p["split"].as_u64().unwrap() as u8,
                        solo: p["solo"].as_u64().unwrap() as u8,
                        lease: p["lease"].as_u64().unwrap() as u8,
                        flags: p["flags"].as_u64().unwrap() as u8,
                        window_start: u64s(&p["window_start"]) as i64,
                        window_end: u64s(&p["window_end"]) as i64,
                    },
                )
                .to_vec()
            }
            k => panic!("kind {k}"),
        };
        assert_eq!(pre.len() as u64, msg["preimage_len"].as_u64().unwrap(), "{name}");
        assert_eq!(hex::encode(&pre), msg["preimage_hex"].as_str().unwrap(), "{name}: preimage");
        let digest = hd::digest(&pre);
        assert_eq!(hex::encode(digest), msg["message_sha256_hex"].as_str().unwrap(), "{name}: digest");
        // The crank normalizes the RFC 6979 signature to the low-S form the precompile accepts.
        let rfc: [u8; 64] = hexb(&msg["signature_rfc6979_hex"]).try_into().unwrap();
        let low = verify_signature(&pk, &digest, &rfc).unwrap();
        assert_eq!(hex::encode(low), msg["signature_low_s_hex"].as_str().unwrap(), "{name}: low-S");
        let ix = tx::precompile_ix(&[(low, pk, digest)]).unwrap();
        assert_eq!(hex::encode(ix.data), msg["precompile_instruction"]["data_hex"].as_str().unwrap(), "{name}: precompile data");
    }
}

#[test]
fn every_event_sample_and_skip_code_decodes() {
    let e = load("events");
    for ev in e["events"].as_array().unwrap() {
        let tag = ev["tag"].as_u64().unwrap() as usize;
        assert_eq!(hd::EVENT_LEN[tag] as u64, ev["length"].as_u64().unwrap(), "tag {tag} length");
        let s = &ev["sample"];
        let parsed = hd::parse_event(&hexb(&s["hex"])).unwrap_or_else(|| panic!("tag {tag}"));
        check_event(&parsed, &serde_json::json!({ "event": ev["event"], "fields": s["fields"] }));
    }
    for sc in e["skip_codes"].as_array().unwrap() {
        let code = sc["error"].as_u64().unwrap() as u32;
        let parsed = hd::parse_event(&hexb(&sc["hex"])).unwrap();
        assert!(matches!(parsed, HdEvent::RigSkipped { error, .. } if error == code));
        let want = sc["name"].as_str().unwrap();
        let got = hd::error_name(code);
        if code < 32 {
            assert_eq!(got, want, "code {code}");
        } else {
            // "p256-introspect MessageMismatch (0x2560000e)"
            let variant = want.split_whitespace().nth(1).unwrap();
            assert_eq!(got, format!("P256{variant}"), "code {code:#x}");
        }
    }
}
