//! Cross-check example, compiled inside a temporary copy of `crank/` by
//! `run.sh` (the crank directory itself is never edited): the crank's own
//! heads_down client code (src/hd.rs, src/gate.rs) against
//! programs/heads-down/vectors.
//!
//! Every line starts with a verdict: `[MATCH]`, `[MISMATCH]`, or `[UNKNOWN]`
//! (the crank does not decode it; it keeps the bytes as `Other` or prints a
//! generic name, so nothing breaks). The run ends with the totals.
use std::str::FromStr;

use hd_crank::{gate, hd};
use serde_json::Value;
use solana_address::Address;

fn load(name: &str) -> Value {
    let dir = std::env::var("VECTORS").expect("VECTORS dir");
    serde_json::from_str(&std::fs::read_to_string(format!("{dir}/{name}")).unwrap()).unwrap()
}
fn addr(v: &Value) -> Address {
    Address::from_str(v.as_str().unwrap()).unwrap()
}
fn u64s(v: &Value) -> u64 {
    v.as_str().unwrap().parse().unwrap()
}

#[derive(Default)]
struct Totals {
    matched: usize,
    mismatched: usize,
    unknown: usize,
}

impl Totals {
    fn say(&mut self, ok: bool, text: String) {
        if ok {
            self.matched += 1;
            println!("[MATCH] {text}");
        } else {
            self.mismatched += 1;
            println!("[MISMATCH] {text}");
        }
    }
    fn unknown(&mut self, text: String) {
        self.unknown += 1;
        println!("[UNKNOWN] {text}");
    }
}

fn main() {
    let mut t = Totals::default();
    let program = hd::PROGRAM_ID;
    // ---- messages ------------------------------------------------------------
    let m = load("messages.json");
    let rig = addr(&m["rig"]);
    for msg in m["messages"].as_array().unwrap() {
        let f = &msg["fields"];
        let name = msg["name"].as_str().unwrap();
        let pre: Vec<u8> = match msg["kind"].as_u64().unwrap() {
            1 => hd::heartbeat_preimage(
                &program,
                &rig,
                &hd::HeartbeatFields {
                    counter: u64s(&f["counter"]),
                    shift_id: u64s(&f["shift_id"]),
                    round_id: u64s(&f["round_id"]),
                    lease_rounds: f["lease_rounds"].as_u64().unwrap() as u8,
                },
            )
            .to_vec(),
            k @ (2 | 3) => hd::break_preimage(
                &program,
                &rig,
                k as u8,
                u64s(&f["counter"]),
                u64s(&f["shift_id"]),
                f["reason"].as_u64().unwrap() as u8,
            )
            .to_vec(),
            4 => {
                let p = &f["plan"];
                hd::plan_preimage(
                    &program,
                    &rig,
                    &hd::PlanFields {
                        counter: u64s(&f["counter"]),
                        max_ev_cost: u64s(&p["max_ev_cost"]),
                        dig_lamports: u64s(&p["dig_lamports"]),
                        split: p["split"].as_u64().unwrap() as u8,
                        solo: p["solo"].as_u64().unwrap() as u8,
                        lease: p["lease"].as_u64().unwrap() as u8,
                        flags: p["flags"].as_u64().unwrap() as u8,
                        window_start: p["window_start"].as_str().unwrap().parse().unwrap(),
                        window_end: p["window_end"].as_str().unwrap().parse().unwrap(),
                    },
                )
                .to_vec()
            }
            _ => unreachable!(),
        };
        t.say(
            hex::encode(&pre) == msg["preimage_hex"].as_str().unwrap(),
            format!("message {name}: preimage ({} B)", pre.len()),
        );
        t.say(
            hex::encode(hd::digest(&pre)) == msg["message_sha256_hex"].as_str().unwrap(),
            format!("message {name}: digest"),
        );
    }

    // ---- dig instructions ------------------------------------------------------
    let ixs = load("instructions.json");
    let round = addr(&ixs["pinned_fork"]["round"]["address"]);
    for v in ixs["instructions"].as_array().unwrap() {
        if v["tag"] != 6 {
            continue;
        }
        let accts = v["accounts"].as_array().unwrap();
        let cranker = addr(&accts[0]["pubkey"]);
        let entries = v["args"]["entries"].as_array().unwrap();
        let rigs: Vec<(hd::RigAccounts, hd::DigEntry)> = entries
            .iter()
            .enumerate()
            .map(|(i, e)| {
                let rig = addr(&accts[12 + 4 * i]["pubkey"]);
                let authority = addr(&accts[13 + 4 * i]["pubkey"]);
                (
                    hd::RigAccounts::derive(rig, authority),
                    hd::DigEntry {
                        hb_ix: e["hb_ix"].as_u64().unwrap() as u8,
                        hb_sig_index: e["hb_sig_index"].as_u64().unwrap() as u8,
                        counter: u64s(&e["counter"]),
                        round_id: u64s(&e["round_id"]),
                        lease_rounds: e["lease_rounds"].as_u64().unwrap() as u8,
                    },
                )
            })
            .collect();
        let ix = hd::dig_ix(&program, &cranker, &round, &rigs).unwrap();
        let name = v["name"].as_str().unwrap();
        t.say(
            hex::encode(&ix.data) == v["data_hex"].as_str().unwrap(),
            format!("dig {name}: data ({} B)", ix.data.len()),
        );
        let metas_ok = ix.accounts.len() == accts.len()
            && ix.accounts.iter().zip(accts).all(|(m, a)| {
                m.pubkey == addr(&a["pubkey"])
                    && m.is_signer == a["is_signer"].as_bool().unwrap()
                    && m.is_writable == a["is_writable"].as_bool().unwrap()
            });
        t.say(metas_ok, format!("dig {name}: {} account metas", accts.len()));
    }

    // ---- events -------------------------------------------------------------------
    let evs = load("events.json");
    for e in evs["events"].as_array().unwrap() {
        let bytes = hex::decode(e["sample"]["hex"].as_str().unwrap()).unwrap();
        let tag = e["tag"].as_u64().unwrap();
        let golden = e["event"].as_str().unwrap();
        let what = format!("event tag {tag} {golden} ({} B)", bytes.len());
        match hd::parse_event(&bytes) {
            None => t.say(false, format!("{what}: parse_event returned None")),
            Some(ev) if ev.name() == "Other" => {
                t.unknown(format!("{what}: kept as Other (not decoded)"));
            }
            Some(ev) => {
                // The name, and the rig every known event starts with.
                let rig_ok = ev.rig() == Some(addr(&e["sample"]["fields"]["rig"]));
                t.say(
                    ev.name() == golden && rig_ok,
                    format!("{what}: parse_event = {ev:?}"),
                );
            }
        }
    }
    for s in evs["skip_codes"].as_array().unwrap() {
        let code = s["error"].as_u64().unwrap() as u32;
        let golden = s["name"].as_str().unwrap();
        // A shared-crate code has a descriptive golden name, such as
        // "p256-introspect MessageMismatch (0x2560000e)": compare the variant.
        let variant = golden.split(' ').nth(1).unwrap_or(golden);
        let name = hd::error_name(code);
        let what = format!("skip code {code} {golden}: crank error_name = {name}");
        if name.eq_ignore_ascii_case("unknown") {
            t.unknown(what);
        } else {
            t.say(name == variant || name.ends_with(variant), what);
        }
    }

    // ---- gate --------------------------------------------------------------------------
    let pf = &ixs["pinned_fork"];
    let ema = u64s(&pf["board"]["production_cost_ema"]);
    let pot = u64s(&pf["treasury_motherlode"]);
    let want = u64s(&pf["ema_ev"]);
    let got = gate::ema_ev(ema, pot);
    t.say(got == Some(want), format!("gate ema_ev({ema}, {pot}) = {got:?}, program {want}"));
    // The program's gate is inclusive (<=).
    let open = gate::gate_open(ema, pot, want, u64::MAX);
    t.say(open, format!("gate_open at exactly ema_ev: {open} (program: true)"));

    println!(
        "\nTOTAL crank src/hd.rs + src/gate.rs: MATCH {} / MISMATCH {} / UNKNOWN {}",
        t.matched, t.mismatched, t.unknown
    );
}
