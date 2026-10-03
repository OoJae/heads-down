//! The crank against the frozen contract: `programs/heads-down/vectors/*.json` (INTERFACE v1.3:
//! the v1.1 core plus the additive SKR and hardening sections), generated from real LiteSVM runs of the
//! program on a mainnet-ORE fork. Every builder the crank sends with (`dig`,
//! `record_heartbeats`, `break_shift` / `freeze_rig` on the P-256 path, `end_shift`,
//! `stack_checkin` in both modes, `settle_stack`, `forfeit_focus_bond`, `refund_gift`,
//! `init_bury_vault`, the Secp256r1SigVerify data), every preimage, every event decoder
//! (tags 1..=23) and every skip-code name must match those bytes exactly. The v1.3 events
//! (tags 24..=27: governance rotation and `close_shift_log`) are kept raw (`HdEvent::Other`):
//! the crank acts on none of them.

mod common;

use hd_crank::hd::{self, DigEntry, HdEvent, HeartbeatFields, PlanFields, RigAccounts, SignalKind};
use hd_crank::heartbeat::verify_signature;
use hd_crank::skr;
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
/// The last event tag the crank decodes field by field (INTERFACE v1.2).
const LAST_DECODED_TAG: u8 = 23;

fn assert_events_decode(v: &Value) {
    for e in v["litesvm"]["events"].as_array().unwrap() {
        let bytes = hexb(&e["hex"]);
        let ev = hd::parse_event(&bytes).unwrap_or_else(|| panic!("{}: {}", v["name"], e["event"]));
        if bytes[0] > LAST_DECODED_TAG {
            assert!(matches!(ev, HdEvent::Other { tag, .. } if tag == bytes[0]), "{}: {} is kept raw", v["name"], e["event"]);
            continue;
        }
        check_event(&ev, e);
    }
}

fn check_event(ev: &HdEvent, e: &Value) {
    let f = &e["fields"];
    assert_eq!(ev.name(), e["event"].as_str().unwrap());
    assert_eq!(u64::from(ev.tag()), f["tag"].as_u64().unwrap_or(u64::from(ev.tag())), "{}", ev.name());
    // Every v1.1 event starts with the rig; of the v1.2 events only some carry one.
    assert_eq!(ev.rig(), f.get("rig").map(addr), "{}: rig", ev.name());
    assert_eq!(ev.table(), f.get("table").map(addr), "{}: table", ev.name());
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
        HdEvent::StackOpened { table, host, table_id, bond, start_round, end_round, grace_gaps, flags, max_seats } => {
            assert_eq!((*table, *host), (addr(&f["table"]), addr(&f["host"])));
            assert_eq!(
                (*table_id, *bond, *start_round, *end_round, u64::from(*grace_gaps), u64::from(*flags), u64::from(*max_seats)),
                (
                    u64s(&f["table_id"]),
                    u64s(&f["bond"]),
                    u64s(&f["start_round"]),
                    u64s(&f["end_round"]),
                    u64s(&f["grace_gaps"]),
                    u64s(&f["flags"]),
                    u64s(&f["max_seats"])
                )
            );
        }
        HdEvent::StackJoined { table, rig, authority, sgt_mint, bond, seat_index } => {
            assert_eq!((*table, *rig, *authority, *sgt_mint), (addr(&f["table"]), addr(&f["rig"]), addr(&f["authority"]), addr(&f["sgt_mint"])));
            assert_eq!((*bond, u64::from(*seat_index)), (u64s(&f["bond"]), u64s(&f["seat_index"])));
        }
        HdEvent::StackCheckin { table, rig, round_id, checked_rounds, result } => {
            assert_eq!((*table, *rig), (addr(&f["table"]), addr(&f["rig"])));
            assert_eq!(
                (*round_id, *checked_rounds, u64::from(*result)),
                (u64s(&f["round_id"]), u64s(&f["checked_rounds"]), u64s(&f["result"]))
            );
        }
        HdEvent::StackSettled { table, total_bonds, finisher_bonds, payouts_total, bury_amount, seats, finishers } => {
            assert_eq!(*table, addr(&f["table"]));
            assert_eq!(
                (*total_bonds, *finisher_bonds, *payouts_total, *bury_amount, u64::from(*seats), u64::from(*finishers)),
                (
                    u64s(&f["total_bonds"]),
                    u64s(&f["finisher_bonds"]),
                    u64s(&f["payouts_total"]),
                    u64s(&f["bury_amount"]),
                    u64s(&f["seats"]),
                    u64s(&f["finishers"])
                )
            );
        }
        HdEvent::StackClaimed { table, rig, authority, amount, kind } => {
            assert_eq!((*table, *rig, *authority), (addr(&f["table"]), addr(&f["rig"]), addr(&f["authority"])));
            assert_eq!((*amount, u64::from(*kind)), (u64s(&f["amount"]), u64s(&f["kind"])));
        }
        HdEvent::FocusBondLocked { bond, rig, authority, shift_id, amount } => {
            assert_eq!((*bond, *rig, *authority), (addr(&f["bond"]), addr(&f["rig"]), addr(&f["authority"])));
            assert_eq!((*shift_id, *amount), (u64s(&f["shift_id"]), u64s(&f["amount"])));
        }
        HdEvent::FocusBondReleased { bond, rig, shift_id, amount } => {
            assert_eq!((*bond, *rig), (addr(&f["bond"]), addr(&f["rig"])));
            assert_eq!((*shift_id, *amount), (u64s(&f["shift_id"]), u64s(&f["amount"])));
        }
        HdEvent::FocusBondForfeited { bond, rig, shift_id, amount, reason } => {
            assert_eq!((*bond, *rig), (addr(&f["bond"]), addr(&f["rig"])));
            assert_eq!((*shift_id, *amount, u64::from(*reason)), (u64s(&f["shift_id"]), u64s(&f["amount"]), u64s(&f["reason"])));
        }
        HdEvent::GiftCreated { gift, sender, recipient, lamports, expiry_ts, recipient_kind } => {
            assert_eq!((*gift, *sender, *recipient), (addr(&f["gift"]), addr(&f["sender"]), addr(&f["recipient"])));
            assert_eq!(
                (*lamports, *expiry_ts, u64::from(*recipient_kind)),
                (u64s(&f["lamports"]), f["expiry_ts"].as_str().unwrap().parse::<i64>().unwrap(), u64s(&f["recipient_kind"]))
            );
        }
        HdEvent::GiftClaimed { gift, claimer, lamports, recipient_kind } => {
            assert_eq!((*gift, *claimer), (addr(&f["gift"]), addr(&f["claimer"])));
            assert_eq!((*lamports, u64::from(*recipient_kind)), (u64s(&f["lamports"]), u64s(&f["recipient_kind"])));
        }
        HdEvent::GiftRefunded { gift, sender, lamports } => {
            assert_eq!((*gift, *sender, *lamports), (addr(&f["gift"]), addr(&f["sender"]), u64s(&f["lamports"])));
        }
        HdEvent::BuryLotAdded { source, amount, lot_skr, start_price, start_slot, source_kind } => {
            assert_eq!(*source, addr(&f["source"]));
            assert_eq!(
                (*amount, *lot_skr, *start_price, *start_slot, u64::from(*source_kind)),
                (u64s(&f["amount"]), u64s(&f["lot_skr"]), u64s(&f["start_price"]), u64s(&f["start_slot"]), u64s(&f["source_kind"]))
            );
        }
        HdEvent::BuryAuctionSold { buyer, skr_amount, price, ore_paid, ore_burned, ore_shared, lot_remaining } => {
            assert_eq!(*buyer, addr(&f["buyer"]));
            assert_eq!(
                (*skr_amount, *price, *ore_paid, *ore_burned, *ore_shared, *lot_remaining),
                (
                    u64s(&f["skr_amount"]),
                    u64s(&f["price"]),
                    u64s(&f["ore_paid"]),
                    u64s(&f["ore_burned"]),
                    u64s(&f["ore_shared"]),
                    u64s(&f["lot_remaining"])
                )
            );
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
    assert_eq!(e["interface_version"], "1.3");
    let mut tags = Vec::new();
    for ev in e["events"].as_array().unwrap() {
        let tag = ev["tag"].as_u64().unwrap() as usize;
        tags.push(tag);
        if tag > usize::from(LAST_DECODED_TAG) {
            // v1.3 (governance rotation, close_shift_log): kept raw, never acted on.
            let bytes = hexb(&ev["sample"]["hex"]);
            assert!(matches!(hd::parse_event(&bytes), Some(HdEvent::Other { tag: t, .. }) if usize::from(t) == tag), "tag {tag}");
            continue;
        }
        // Every tag of INTERFACE v1.2 (the v1.1 core 1..=10 and the SKR events 11..=23) decodes
        // by its exact length, field for field.
        assert_eq!(hd::EVENT_LEN[tag] as u64, ev["length"].as_u64().unwrap(), "tag {tag} length");
        let s = &ev["sample"];
        let bytes = hexb(&s["hex"]);
        assert_eq!(bytes.len(), hd::EVENT_LEN[tag], "tag {tag}: the sample has the contract length");
        let parsed = hd::parse_event(&bytes).unwrap_or_else(|| panic!("tag {tag}"));
        assert_eq!(usize::from(parsed.tag()), tag);
        check_event(&parsed, &serde_json::json!({ "event": ev["event"], "fields": s["fields"] }));
        // The layout in the file is the one the decoder reads: offsets and sizes add up.
        let layout = ev["layout"].as_array().unwrap();
        let end = layout.last().map(|l| l["offset"].as_u64().unwrap() + l["size"].as_u64().unwrap()).unwrap();
        assert_eq!(end as usize, hd::EVENT_LEN[tag], "tag {tag}: layout end");
        // One byte more or less is not this event.
        let mut longer = bytes.clone();
        longer.push(0);
        assert_eq!(hd::parse_event(&longer), None, "tag {tag} + 1 byte");
        assert_eq!(hd::parse_event(&bytes[..bytes.len() - 1]), None, "tag {tag} - 1 byte");
    }
    assert_eq!(tags, (1..=27).collect::<Vec<_>>(), "the golden file holds tags 1..=27, each once");
    assert_eq!(hd::EVENT_LEN.len(), 24);
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

// ---------------------------------------------------------------------------------------
// INTERFACE v1.2 §11 (SKR): the instructions the crank sends, and the wallet-signed ones the
// fork suite sets a table, a bond or a gift up with.

#[test]
fn stack_checkin_vectors_match_in_verify_and_observe_mode() {
    let all = load("instructions");
    assert_eq!(all["interface_version"], "1.3");
    let host = account(vector(&all, "open_stack"), "host");
    for name in ["stack_checkin_heartbeat", "stack_checkin_observe"] {
        let v = vector(&all, name);
        assert_eq!(v["tag"], 17);
        let table = account(v, "stack_table");
        assert_eq!(table, skr::stack_table_pda(&hd::PROGRAM_ID, &host, 1).0, "{name}: StackTable PDA");
        let seats: Vec<(Address, Address, DigEntry)> = entries(v)
            .iter()
            .enumerate()
            .map(|(i, e)| {
                let (seat, rig) = (account(v, &format!("stack_seat[{i}]")), account(v, &format!("rig[{i}]")));
                // An in-person table keys the seat by the rig.
                assert_eq!(seat, skr::stack_seat_pda(&hd::PROGRAM_ID, &table, &rig).0, "{name}: StackSeat PDA");
                (seat, rig, *e)
            })
            .collect();
        assert_eq!(seats.len(), 3);
        let ix = skr::stack_checkin_ix(&hd::PROGRAM_ID, &table, &seats).unwrap();
        assert_matches(v, &ix);
        assert_eq!(ix.accounts.len(), skr::CHECKIN_FIXED_ACCOUNTS + 2 * seats.len());
        assert_precompiles_rebuild(v);
        assert_events_decode(v);
    }

    // Verify mode: one HeartbeatsRecorded (tag 8, the v1.1 bytes) before each StackCheckin, and
    // every seat counts round r+1 (result 0, 1 round counted).
    let v = vector(&all, "stack_checkin_heartbeat");
    let evs: Vec<HdEvent> = v["litesvm"]["events"].as_array().unwrap().iter().map(|e| hd::parse_event(&hexb(&e["hex"])).unwrap()).collect();
    assert_eq!(evs.len(), 6);
    for pair in evs.chunks(2) {
        let (HdEvent::HeartbeatsRecorded { rig: a, round_id, dark_rounds_added }, HdEvent::StackCheckin { rig: b, result, checked_rounds, round_id: r2, .. }) =
            (&pair[0], &pair[1])
        else {
            panic!("{pair:?}");
        };
        assert_eq!((a, *round_id, *dark_rounds_added), (b, 422_702, 1));
        assert_eq!((*result, *checked_rounds, *r2), (0, 1, 422_702));
    }
    // The crank's own verify-mode transaction: `[CU limit, CU price, Secp256r1SigVerify,
    // stack_checkin]`, the same precompile bytes at index 2 and every entry naming it.
    let pre = hexb(&v["transaction"]["instructions"][0]["data_hex"]);
    let parsed = p256_introspect::Secp256r1Instruction::parse(&pre, 0).unwrap();
    let table = account(v, "stack_table");
    let seats: Vec<tx::CheckinSeat> = entries(v)
        .iter()
        .enumerate()
        .map(|(i, e)| {
            let p = parsed.entry(i as u8).unwrap();
            let rig = account(v, &format!("rig[{i}]"));
            tx::CheckinSeat {
                seat: account(v, &format!("stack_seat[{i}]")),
                rig,
                heartbeat: Some(hd_crank::heartbeat::VerifiedHeartbeat {
                    rig,
                    fields: HeartbeatFields { counter: e.counter, shift_id: 1, round_id: e.round_id, lease_rounds: e.lease_rounds },
                    sig: *p.signature,
                    pubkey: *p.public_key,
                    digest: p.message.try_into().unwrap(),
                }),
            }
        })
        .collect();
    // The digest in the vector is the HEARTBEAT preimage the crank rebuilds (shift 1, lease 1).
    for s in &seats {
        let h = s.heartbeat.unwrap();
        assert_eq!(h.digest, hd::digest(&hd::heartbeat_preimage(&hd::PROGRAM_ID, &s.rig, &h.fields)));
        assert_eq!(h.fields.lease_rounds, 1, "Stack seats lease one round");
    }
    let p = common::params(tx::TxFormat::Legacy, addr(&v["transaction"]["fee_payer"]));
    let ixs = tx::build_checkin_instructions(&p, 12_000, &table, &seats).unwrap();
    assert_eq!(ixs.len(), 4);
    assert_eq!(ixs[2].data, pre, "the same precompile bytes");
    let golden = hexb(&v["data_hex"]);
    assert_eq!(ixs[3].data.len(), golden.len());
    for (i, (ours, theirs)) in ixs[3].data.iter().zip(&golden).enumerate() {
        // Only hb_ix differs: the vector's precompile is instruction 0, the crank's is 2.
        if i >= 2 && (i - 2) % hd::DIG_ENTRY_LEN == 0 {
            assert_eq!((*ours, *theirs), (2, 0), "hb_ix of entry {}", (i - 2) / hd::DIG_ENTRY_LEN);
        } else {
            assert_eq!(ours, theirs, "byte {i}");
        }
    }
    // Observe mode needs no precompile: `[CU limit, CU price, stack_checkin]`, byte-identical data.
    let o = vector(&all, "stack_checkin_observe");
    let observed: Vec<tx::CheckinSeat> = seats.iter().map(|s| tx::CheckinSeat { heartbeat: None, ..*s }).collect();
    let ixs = tx::build_checkin_instructions(&p, 12_000, &table, &observed).unwrap();
    assert_eq!(ixs.len(), 3);
    assert_eq!(hex::encode(&ixs[2].data), o["data_hex"].as_str().unwrap());
    // hana and ivan count round r+2; judy's bound shift recorded a BREAK: result 42, for good.
    let results: Vec<(u32, u64)> = o["litesvm"]["events"]
        .as_array()
        .unwrap()
        .iter()
        .map(|e| match hd::parse_event(&hexb(&e["hex"])).unwrap() {
            HdEvent::StackCheckin { result, checked_rounds, .. } => (result, checked_rounds),
            other => panic!("{other:?}"),
        })
        .collect();
    assert_eq!(results, vec![(0, 2), (0, 2), (hd::code::STACK_SEAT_BROKEN, 1)]);
    assert_eq!(hd::checkin_result_name(results[2].0), "StackSeatBroken");
}

#[test]
fn settle_stack_vector_matches_and_the_payouts_are_predicted() {
    let all = load("instructions");
    let v = vector(&all, "settle_stack");
    let table = account(v, "stack_table");
    let vault = account(v, "table_skr_vault");
    assert_eq!(vault, skr::ata(&table, &skr::SKR_MINT), "the table vault is ATA(table, SKR)");
    assert_eq!(account(v, "bury_vault"), skr::bury_vault_pda(&hd::PROGRAM_ID).0);
    assert_eq!(account(v, "bury_skr_vault"), skr::ata(&skr::bury_vault_pda(&hd::PROGRAM_ID).0, &skr::SKR_MINT));
    assert_eq!(account(v, "token_program"), skr::SPL_TOKEN_PROGRAM_ID);
    let seats: Vec<Address> = v["args"]["seats"].as_array().unwrap().iter().map(addr).collect();
    let ix = skr::settle_stack_ix(&hd::PROGRAM_ID, &table, &vault, &seats);
    assert_matches(v, &ix);
    assert_events_decode(v);
    let ixs = tx::settle_stack_instructions(&hd::PROGRAM_ID, &table, &vault, &seats, 30_000, 1_000);
    assert_eq!(ixs.len(), 3);
    assert_eq!(ixs[2], ix);

    // The crank's mirror of the finish rule and the split predicts the settle exactly: hana and
    // ivan finished (2 of 2 rounds, the end round counted), judy broke. B = 600 SKR, W = 400.
    let open = vector(&all, "open_stack");
    let a = &open["args"];
    let t = skr::StackTable {
        bump: 254,
        host: account(open, "host"),
        vault,
        table_id: u64s(&a["table_id"]),
        bond: u64s(&a["bond"]),
        start_round: u64s(&a["start_round"]),
        end_round: u64s(&a["end_round"]),
        grace_gaps: a["grace_gaps"].as_u64().unwrap() as u32,
        flags: a["flags"].as_u64().unwrap() as u8,
        max_seats: a["max_seats"].as_u64().unwrap() as u8,
        status: skr::status::OPEN,
        seat_count: 3,
        finishers: 0,
        claimed_count: 0,
        total_bonds: 3 * u64s(&a["bond"]),
        finisher_bonds: 0,
        payouts_total: 0,
        bury_amount: 0,
        claimed_total: 0,
        refund_after_ts: 0,
        opened_ts: 0,
        opened_round: 422_701,
    };
    let seat = |i: u8, checked: u64, last: u64, broken: bool| skr::StackSeat {
        bump: 255,
        table,
        rig: Address::new_from_array([i; 32]),
        authority: Address::new_from_array([i; 32]),
        sgt_mint: Address::default(),
        bond: t.bond,
        shift_id: 1,
        checked_rounds: checked,
        last_round: last,
        payout: 0,
        seat_index: i,
        broken,
        outcome: skr::outcome::PENDING,
        sgt_verified: false,
    };
    let st = skr::preview_settle(&table, &t, &[seat(0, 2, t.end_round, false), seat(1, 2, t.end_round, false), seat(2, 1, t.start_round, true)]).unwrap();
    let evs: Vec<HdEvent> = v["litesvm"]["events"].as_array().unwrap().iter().map(|e| hd::parse_event(&hexb(&e["hex"])).unwrap()).collect();
    assert_eq!(evs.len(), 2);
    assert_eq!(
        evs[1],
        HdEvent::StackSettled {
            table,
            total_bonds: st.total_bonds,
            finisher_bonds: st.finisher_bonds,
            payouts_total: st.payouts_total,
            bury_amount: st.bury,
            seats: 3,
            finishers: st.finishers,
        },
        "preview == the program's StackSettled"
    );
    assert_eq!(st.payouts, vec![280 * skr::ONE_SKR, 280 * skr::ONE_SKR, 0]);
    assert!(matches!(evs[0], HdEvent::BuryLotAdded { source, amount, source_kind: hd::LOT_FROM_STACK, .. } if source == table && amount == st.bury));
    // claim_stack paid hana exactly her predicted payout.
    let claim = vector(&all, "claim_stack");
    let ix = common::skr::claim_stack_ix(&hd::PROGRAM_ID, &table, &account(claim, "stack_seat"), &account(claim, "seat_authority"));
    assert_matches(claim, &ix);
    assert_events_decode(claim);
    assert!(matches!(
        hd::parse_event(&hexb(&claim["litesvm"]["events"][0]["hex"])).unwrap(),
        HdEvent::StackClaimed { amount, kind: hd::CLAIM_PAYOUT, .. } if amount == st.payouts[0]
    ));
}

#[test]
fn forfeit_focus_bond_and_refund_gift_vectors_match_and_need_no_signer() {
    let all = load("instructions");
    // forfeit_focus_bond: anyone, once the bonded shift sealed with a reason other than completed.
    let v = vector(&all, "forfeit_focus_bond");
    assert_eq!(v["tag"], 22);
    let shift_id = u64s(&v["args"]["shift_id"]);
    let bond_address = account(v, "focus_bond");
    let rig = account(v, "rig");
    let authority = account(v, "authority");
    assert_eq!(authority, addr(&v["args"]["authority"]));
    assert_eq!(rig, hd::rig_pda(&hd::PROGRAM_ID, &authority).0);
    assert_eq!(bond_address, skr::focus_bond_pda(&hd::PROGRAM_ID, &rig, shift_id).0, "FocusBond PDA");
    assert_eq!(account(v, "shift_log"), hd::shift_log_pda(&hd::PROGRAM_ID, &rig, shift_id).0);
    let bond = skr::FocusBond {
        bump: 0,
        rig,
        authority,
        vault: account(v, "bond_skr_vault"),
        shift_id,
        amount: 300 * skr::ONE_SKR,
        shift_start_round: 0,
        shift_start_ts: 0,
        locked_ts: 0,
    };
    assert_eq!(bond.vault, skr::ata(&bond_address, &skr::SKR_MINT), "the bond vault is ATA(bond, SKR)");
    let ix = skr::forfeit_focus_bond_ix(&hd::PROGRAM_ID, &bond_address, &bond);
    assert_matches(v, &ix);
    assert!(ix.accounts.iter().all(|m| !m.is_signer), "permissionless: no instruction account signs");
    assert_ne!(addr(&v["transaction"]["fee_payer"]), authority, "the vector's fee payer is the cranker, not the owner");
    assert_events_decode(v);
    let evs: Vec<HdEvent> = v["litesvm"]["events"].as_array().unwrap().iter().map(|e| hd::parse_event(&hexb(&e["hex"])).unwrap()).collect();
    assert!(matches!(evs[0], HdEvent::BuryLotAdded { source, amount, source_kind: hd::LOT_FROM_BOND, .. } if source == bond_address && amount == bond.amount));
    assert_eq!(evs[1], HdEvent::FocusBondForfeited { bond: bond_address, rig, shift_id, amount: bond.amount, reason: hd::reason::MANUAL });
    assert_eq!(hd::bond_reason_name(hd::reason::MANUAL), "manual");

    // refund_gift: anyone from expiry_ts; every lamport goes to the stored sender.
    let v = vector(&all, "refund_gift");
    assert_eq!(v["tag"], 25);
    let (gift, sender) = (account(v, "gift_escrow"), account(v, "sender"));
    assert_eq!(gift, addr(&v["args"]["gift"]));
    assert_eq!(gift, skr::gift_pda(&hd::PROGRAM_ID, &sender, 3).0, "GiftEscrow PDA (nonce 3)");
    let ix = skr::refund_gift_ix(&hd::PROGRAM_ID, &gift, &sender);
    assert_matches(v, &ix);
    assert_eq!(ix.accounts.len(), 2);
    assert!(ix.accounts.iter().all(|m| !m.is_signer && m.is_writable), "permissionless: the sender does not sign");
    assert_ne!(addr(&v["transaction"]["fee_payer"]), sender, "the vector's fee payer is the cranker, not the sender");
    assert!(v["auth"].as_str().unwrap().starts_with("anyone"), "{}", v["auth"]);
    assert_events_decode(v);
    assert_eq!(
        hd::parse_event(&hexb(&v["litesvm"]["events"][0]["hex"])).unwrap(),
        HdEvent::GiftRefunded { gift, sender, lamports: 500_000_000 }
    );
}

#[test]
fn init_bury_vault_vector_matches() {
    let all = load("instructions");
    let v = vector(&all, "init_bury_vault");
    let payer = account(v, "payer");
    let ix = skr::init_bury_vault_ix(&hd::PROGRAM_ID, &payer);
    assert_matches(v, &ix);
    assert_eq!(account(v, "bury_vault"), addr(&all["constants"]["bury_vault"]));
    // The companions are ATA CreateIdempotent (data `01`); the crank creates the lot's (SKR) ATA.
    let companions = v["transaction"]["instructions"].as_array().unwrap();
    assert_eq!(companions[0]["program_id"], skr::ATA_PROGRAM_ID.to_string());
    let bury = skr::bury_vault_pda(&hd::PROGRAM_ID).0;
    let ata_ix = skr::create_ata_idempotent_ix(&payer, &bury, &skr::SKR_MINT);
    assert_eq!(ata_ix.program_id, skr::ATA_PROGRAM_ID);
    assert_eq!(hex::encode(&ata_ix.data), companions[0]["data_hex"].as_str().unwrap());
    assert_eq!(ata_ix.accounts[1].pubkey, account(vector(&all, "settle_stack"), "bury_skr_vault"), "the ATA the settle vector uses");
    assert_eq!((ata_ix.accounts[2].pubkey, ata_ix.accounts[3].pubkey), (bury, skr::SKR_MINT));
    assert_eq!(addr(&all["constants"]["skr_mint"]), skr::SKR_MINT);
    assert_eq!(addr(&all["constants"]["ore_mint"]), skr::ORE_MINT);
    assert_eq!(addr(&all["constants"]["spl_token_program"]), skr::SPL_TOKEN_PROGRAM_ID);
    assert_eq!(addr(&all["constants"]["associated_token_program"]), skr::ATA_PROGRAM_ID);
    let both = tx::init_bury_vault_instructions(&hd::PROGRAM_ID, &payer, true, true, 40_000, 1_000);
    assert_eq!(both.len(), 4);
    assert_eq!((&both[2], &both[3]), (&ata_ix, &ix));
    assert_eq!(tx::init_bury_vault_instructions(&hd::PROGRAM_ID, &payer, false, true, 40_000, 1_000).len(), 3);
}

#[test]
fn wallet_signed_skr_vectors_match_the_test_builders() {
    use common::skr::{create_gift_ix, join_stack_ix, lock_focus_bond_ix, open_stack_ix, release_focus_bond_ix, StackParams};
    let all = load("instructions");
    let v = vector(&all, "open_stack");
    let a = &v["args"];
    let p = StackParams {
        table_id: u64s(&a["table_id"]),
        bond: u64s(&a["bond"]),
        start_round: u64s(&a["start_round"]),
        end_round: u64s(&a["end_round"]),
        grace_gaps: a["grace_gaps"].as_u64().unwrap() as u32,
        flags: a["flags"].as_u64().unwrap() as u8,
        max_seats: a["max_seats"].as_u64().unwrap() as u8,
    };
    assert_matches(v, &open_stack_ix(&hd::PROGRAM_ID, &account(v, "host"), &p));
    assert_events_decode(v);
    let v = vector(&all, "join_stack");
    assert_matches(v, &join_stack_ix(&hd::PROGRAM_ID, &account(v, "authority"), &account(v, "stack_table")));
    assert_events_decode(v);
    let v = vector(&all, "lock_focus_bond");
    assert_matches(v, &lock_focus_bond_ix(&hd::PROGRAM_ID, &account(v, "authority"), u64s(&v["args"]["shift_id"]), u64s(&v["args"]["amount"])));
    assert_events_decode(v);
    let v = vector(&all, "release_focus_bond");
    assert_matches(v, &release_focus_bond_ix(&hd::PROGRAM_ID, &account(v, "authority"), u64s(&v["args"]["shift_id"])));
    assert_events_decode(v);
    for name in ["create_gift_wallet", "create_gift_sgt"] {
        let v = vector(&all, name);
        let a = &v["args"];
        let ix = create_gift_ix(
            &hd::PROGRAM_ID,
            &account(v, "sender"),
            u64s(&a["nonce"]),
            a["recipient_kind"].as_u64().unwrap() as u8,
            &addr(&a["recipient"]),
            u64s(&a["lamports"]),
        );
        assert_matches(v, &ix);
        assert_events_decode(v);
    }
    // Every event of every vector in the file decodes: 47 vectors over 32 tags (v1.3 added
    // 28..=31: governance rotation and close_shift_log, none of which the crank sends).
    let vectors = all["instructions"].as_array().unwrap();
    assert_eq!(vectors.len(), 47);
    let mut tags: Vec<u64> = vectors.iter().map(|v| v["tag"].as_u64().unwrap()).collect();
    tags.sort_unstable();
    tags.dedup();
    assert_eq!(tags, (0..=31).collect::<Vec<_>>());
    for v in vectors {
        assert_events_decode(v);
    }
}
