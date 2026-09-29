//! Cross-check example, compiled inside a temporary copy of `crank/` by
//! `run.sh` (the crank directory itself is never edited): the crank's own
//! heads_down client code (src/hd.rs, src/gate.rs) against
//! programs/heads-down/vectors.
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
fn verdict(ok: bool) -> &'static str {
    if ok { "MATCH" } else { "MISMATCH" }
}

fn main() {
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
        let pre_ok = hex::encode(&pre) == msg["preimage_hex"].as_str().unwrap();
        let dig_ok = hex::encode(hd::digest(&pre)) == msg["message_sha256_hex"].as_str().unwrap();
        println!("message {name}: preimage {} digest {}", verdict(pre_ok), verdict(dig_ok));
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
        let data_ok = hex::encode(&ix.data) == v["data_hex"].as_str().unwrap();
        let metas_ok = ix.accounts.len() == accts.len()
            && ix.accounts.iter().zip(accts).all(|(m, a)| {
                m.pubkey == addr(&a["pubkey"])
                    && m.is_signer == a["is_signer"].as_bool().unwrap()
                    && m.is_writable == a["is_writable"].as_bool().unwrap()
            });
        println!(
            "dig {}: data {} metas {}",
            v["name"].as_str().unwrap(),
            verdict(data_ok),
            verdict(metas_ok)
        );
    }

    // ---- events -------------------------------------------------------------------
    let evs = load("events.json");
    for e in evs["events"].as_array().unwrap() {
        let bytes = hex::decode(e["sample"]["hex"].as_str().unwrap()).unwrap();
        println!(
            "event {} ({} B): crank parse_event = {:?}",
            e["event"].as_str().unwrap(),
            bytes.len(),
            hd::parse_event(&bytes)
        );
    }
    for s in evs["skip_codes"].as_array().unwrap() {
        let code = s["error"].as_u64().unwrap() as u32;
        println!("skip code {code}: crank error_name = {}", hd::error_name(code));
    }

    // ---- gate --------------------------------------------------------------------------
    let pf = &ixs["pinned_fork"];
    let ema = u64s(&pf["board"]["production_cost_ema"]);
    let pot = u64s(&pf["treasury_motherlode"]);
    let want = u64s(&pf["ema_ev"]);
    let got = gate::ema_ev(ema, pot);
    println!("gate ema_ev({ema}, {pot}) = {got:?}, program {want}: {}", verdict(got == Some(want)));
    // The program's gate is inclusive (<=).
    println!(
        "gate_open at exactly ema_ev: {} (program: true)",
        gate::gate_open(ema, pot, want, u64::MAX)
    );
}
