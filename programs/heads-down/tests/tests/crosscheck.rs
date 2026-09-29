//! Cross-check of the other teams' assumptions against the real program, by
//! executing them on the LiteSVM fork. Source of `vectors/CROSSCHECK.md`.
//!
//! `cargo +1.97.1 test -p heads-down-tests --test crosscheck -- --ignored --nocapture`
//!
//! Ignored by default: it reads files other teams own and change in
//! parallel (android/, crank/), so it must never break this suite. It
//! asserts only program-side facts; everything about a consumer file is
//! reported (MATCH / MISMATCH / INFO), printed, and written to
//! `target/crosscheck-report.json`. It also writes real transaction logs to
//! `target/crosscheck-logs.json` for `vectors/crosscheck/indexer_events.mjs`.

use std::str::FromStr;

use hd::{
    events as ev,
    message::{self, kind, Plan},
    state::rig_state,
};
use heads_down_tests::{
    vectors::{hex, p256_sign_details},
    *,
};
use p256_introspect::client::{build_instruction_data, SignatureInput};
use serde_json::{json, Value};
use solana_message::Message;

// ---- report -----------------------------------------------------------------------

#[derive(Default)]
struct Report {
    rows: Vec<Value>,
}

impl Report {
    fn add(&mut self, source: &str, item: &str, verdict: &str, detail: impl Into<String>) {
        let detail = detail.into();
        println!("[{verdict}] {source} :: {item} :: {detail}");
        self.rows
            .push(json!({"source": source, "item": item, "verdict": verdict, "detail": detail}));
    }
}

fn verdict(ok: bool) -> &'static str {
    if ok {
        "MATCH"
    } else {
        "MISMATCH"
    }
}

fn unhex(s: &str) -> Vec<u8> {
    (0..s.len())
        .step_by(2)
        .map(|i| u8::from_str_radix(&s[i..i + 2], 16).unwrap())
        .collect()
}

fn addr(v: &Value) -> Address {
    Address::from_str(v.as_str().unwrap()).unwrap()
}

fn num(v: &Value) -> u64 {
    match v {
        Value::String(s) => s.parse().unwrap(),
        Value::Number(n) => n.as_u64().unwrap(),
        Value::Bool(b) => u64::from(*b),
        _ => panic!("not a number: {v}"),
    }
}

/// Differing byte ranges, `a` = consumer, `b` = program.
fn byte_diff(a: &[u8], b: &[u8]) -> String {
    if a == b {
        return "identical".into();
    }
    let mut out = vec![];
    if a.len() != b.len() {
        out.push(format!("length {} vs program {}", a.len(), b.len()));
    }
    let n = a.len().max(b.len());
    let mut i = 0;
    while i < n {
        if a.get(i) != b.get(i) {
            let start = i;
            while i < n && a.get(i) != b.get(i) {
                i += 1;
            }
            let side = |x: &[u8]| hex(x.get(start..i.min(x.len())).unwrap_or(&[]));
            out.push(format!(
                "bytes {start}..{i}: consumer {} / program {}",
                side(a),
                side(b)
            ));
        } else {
            i += 1;
        }
    }
    out.join("; ")
}

fn metas_str(metas: &[AccountMeta], names: &dyn Fn(&Address) -> String) -> String {
    metas
        .iter()
        .map(|m| {
            let mut f = String::new();
            if m.is_signer {
                f.push('s');
            }
            if m.is_writable {
                f.push('w');
            }
            if f.is_empty() {
                names(&m.pubkey)
            } else {
                format!("{}({f})", names(&m.pubkey))
            }
        })
        .collect::<Vec<_>>()
        .join(", ")
}

fn outcome(res: &TxResult) -> String {
    match res {
        Ok(m) => {
            let evs: Vec<String> = raw_events(&m.logs)
                .iter()
                .map(|b| format!("{}#{}", b[0], hex(b)))
                .collect();
            if evs.is_empty() {
                "success".into()
            } else {
                format!("success, events [{}]", evs.join(", "))
            }
        }
        Err(f) => format!("FAILED {:?}", f.err),
    }
}

/// Send with only the fee payer signing (sigverify is off in these envs, so
/// other accounts marked signer need no real signature).
fn send_unsigned(env: &mut Env, ixs: &[Instruction]) -> TxResult {
    let payer = env.cranker.insecure_clone();
    let message = Message::new(ixs, Some(&payer.pubkey()));
    let mut tx = solana_transaction::Transaction::new_unsigned(message);
    tx.partial_sign(&[&payer], env.svm.latest_blockhash());
    let res = env.svm.send_transaction(tx).map_err(|f| Failure {
        err: f.err,
        logs: f.meta.logs,
    });
    env.svm.expire_blockhash();
    res
}

fn poke_rig(env: &mut Env, rig: &Address, f: impl FnOnce(&mut hd::state::Rig)) {
    let mut acc = env.account(rig);
    f(bytemuck::from_bytes_mut(&mut acc.data));
    env.svm.set_account(*rig, acc).unwrap();
}

/// Signature verification is off in these envs (foreign wallets cannot
/// sign), but the precompiles still run as programs: a tampered signature
/// must fail and a good one must pass.
fn assert_precompiles_verify(env: &mut Env) {
    let u = User::from_wallet(Keypair::new_from_array([0x5A; 32]), 9);
    let d = [7u8; 32];
    let sig = u.sign(&d);
    assert!(env.send(&[precompile(&sig, &u.p256(), &d)], &[]).is_ok());
    let mut bad = sig;
    bad[5] ^= 1;
    assert!(
        env.send(&[precompile(&bad, &u.p256(), &d)], &[]).is_err(),
        "precompile verification must stay on with sigverify off"
    );
}

fn precompile(sig: &[u8], pk: &[u8], msg: &[u8]) -> Instruction {
    let input = SignatureInput {
        signature: sig.try_into().unwrap(),
        public_key: pk.try_into().unwrap(),
        message: msg,
    };
    Instruction {
        program_id: secp256r1_id(),
        accounts: vec![],
        data: build_instruction_data(&[input]).unwrap(),
    }
}

fn repo() -> std::path::PathBuf {
    root().join("../..")
}

fn read(rel: &str) -> Option<Value> {
    let p = repo().join(rel);
    std::fs::read_to_string(&p)
        .ok()
        .map(|s| serde_json::from_str(&s).unwrap())
}

// ---- 1. android/core/chain ix_vectors.json + android/core/keys vectors.json ----------

#[allow(clippy::too_many_lines)]
fn android(rep: &mut Report) {
    const SRC: &str = "android/core/chain ix_vectors.json";
    const KSRC: &str = "android/core/keys vectors.json";
    let (Some(ixv), Some(keys)) = (
        read("android/core/chain/src/test/resources/ix_vectors.json"),
        read("android/core/keys/src/test/resources/vectors.json"),
    ) else {
        rep.add(
            SRC,
            "files",
            "INFO",
            "android vector files not found; skipped",
        );
        return;
    };
    let list = ixv["instructions"].as_array().unwrap();
    let by_name = |n: &str| list.iter().find(|v| v["name"] == n).cloned();
    let authority = addr(&by_name("register_rig_guest").unwrap()["args"]["authority"]);
    let rig = rig_pda(&authority);
    rep.add(
        SRC,
        "pdas.rig",
        verdict(rig == addr(&ixv["pdas"]["rig"])),
        format!("rig_pda({authority}) = {rig}"),
    );
    for (k, want) in [
        ("config", CONFIG),
        ("executor", EXECUTOR),
        ("automation", automation_pda(&authority)),
        ("miner", miner_pda(&authority)),
        ("shift_log_7", shift_log_pda(&rig, 7)),
    ] {
        rep.add(
            SRC,
            &format!("pdas.{k}"),
            verdict(addr(&ixv["pdas"][k]) == want),
            format!("program derives {want}"),
        );
    }
    let key_pk: [u8; 33] = unhex(keys["key"]["public_key_compressed_hex"].as_str().unwrap())
        .try_into()
        .unwrap();
    let names = |a: &Address| -> String {
        let payer = by_name("arm_shift_p256")
            .map(|v| addr(&v["args"]["payer"]))
            .unwrap_or_default();
        match *a {
            x if x == authority => "authority".into(),
            x if x == rig => "rig".into(),
            x if x == CONFIG => "config".into(),
            x if x == SYSTEM => "system_program".into(),
            x if x == BOARD => "ore_board".into(),
            x if x == ix_sysvar_id() => "instructions_sysvar".into(),
            x if x == payer => "payer".into(),
            x if x == shift_log_pda(&rig, 7) => "shift_log".into(),
            x => format!("{x}"),
        }
    };

    // Environment: pinned fork, golden keys, signatures NOT verified (the
    // Android wallet 7xKX... has no private key here), config initialized.
    let mut env = Env::build_with(Build::Mainnet, false, true, EnvKeys::golden(), true);
    env.svm.airdrop(&authority, 10 * SOL).unwrap();
    assert_precompiles_verify(&mut env);
    // Their plan window is [1_790_000_000, 1_790_028_800] and caps expire at
    // 1_790_600_000: run at the window start.
    env.set_clock(env.slot, 1_790_000_000);

    let plan_of = |a: &Value| Plan {
        max_ev_cost: num(&a["max_ev_cost"]),
        dig_lamports: num(&a["dig_lamports"]),
        split: num(&a["split"]) as u8,
        solo: num(&a["solo"]) as u8,
        lease: num(&a["lease"]) as u8,
        flags: num(&a["flags"]) as u8,
        window_start: num(&a["window_start"]) as i64,
        window_end: num(&a["window_end"]) as i64,
    };
    let as_is = |v: &Value| Instruction {
        program_id: addr(&v["program_id"]),
        accounts: v["accounts"]
            .as_array()
            .unwrap()
            .iter()
            .map(|a| AccountMeta {
                pubkey: addr(&a["pubkey"]),
                is_signer: a["signer"].as_bool().unwrap(),
                is_writable: a["writable"].as_bool().unwrap(),
            })
            .collect(),
        data: unhex(v["data_hex"].as_str().unwrap()),
    };
    let compare =
        |rep: &mut Report, env: &mut Env, name: &str, program: &Instruction, run: bool| {
            let Some(v) = by_name(name) else {
                rep.add(SRC, name, "INFO", "vector not present");
                return None;
            };
            let theirs = as_is(&v);
            rep.add(
                SRC,
                &format!("{name}: data"),
                verdict(theirs.data == program.data),
                format!(
                    "consumer {} / program {} ({})",
                    hex(&theirs.data),
                    hex(&program.data),
                    byte_diff(&theirs.data, &program.data)
                ),
            );
            rep.add(
                SRC,
                &format!("{name}: accounts"),
                verdict(theirs.accounts == program.accounts),
                format!(
                    "consumer [{}] / program [{}]",
                    metas_str(&theirs.accounts, &names),
                    metas_str(&program.accounts, &names)
                ),
            );
            if run {
                let res = send_unsigned(env, std::slice::from_ref(&theirs));
                rep.add(
                    SRC,
                    &format!("{name}: executed as-is"),
                    "INFO",
                    outcome(&res),
                );
            }
            Some(v)
        };

    // register_rig / rotate_key as-is (before a rig exists).
    let reg = by_name("register_rig_guest").unwrap();
    let p256: [u8; 33] = unhex(reg["args"]["p256_pubkey_hex"].as_str().unwrap())
        .try_into()
        .unwrap();
    compare(
        rep,
        &mut env,
        "register_rig_guest",
        &ix_register_rig(&authority, &p256, None),
        true,
    );
    if let Some(att) = by_name("register_rig_attested") {
        let a = &att["args"];
        let program = ix_register_rig(
            &authority,
            &p256,
            Some(AttestationArg {
                ix: num(&a["attestation_ix"]) as u8,
                sig: 0,
                level: num(&a["attestation_level"]) as u8,
                expiry_slot: num(&a["attestation_expiry_slot"]),
            }),
        );
        compare(rep, &mut env, "register_rig_attested", &program, true);
    }
    // The canonical registration for the rest.
    let res = send_unsigned(&mut env, &[ix_register_rig(&authority, &p256, None)]);
    assert!(res.is_ok(), "canonical register_rig: {res:?}");
    rep.add(
        SRC,
        "register_rig (program format, same args)",
        "INFO",
        outcome(&res),
    );
    compare(
        rep,
        &mut env,
        "rotate_key",
        &ix_rotate_key(&authority, &p256, None),
        true,
    );

    // set_caps as-is (expected to match and succeed).
    let sc = by_name("set_caps").unwrap();
    let a = &sc["args"];
    let caps = Caps {
        week: num(&a["cap_week"]),
        shift: num(&a["cap_shift"]),
        round: num(&a["cap_round"]),
        max_cost: num(&a["cap_max_cost"]),
        expiry: num(&a["caps_expiry_ts"]) as i64,
    };
    compare(
        rep,
        &mut env,
        "set_caps",
        &ix_set_caps(&authority, caps),
        true,
    );
    assert_eq!(
        env.rig(&rig).cap_round.get(),
        caps.round,
        "set_caps as-is applied"
    );

    // arm_shift: both paths as-is, then the program format with the Android
    // PLAN signature (keys vectors plan_steady).
    let arm_w = by_name("arm_shift_wallet").unwrap();
    let plan = plan_of(&arm_w["args"]);
    compare(
        rep,
        &mut env,
        "arm_shift_wallet",
        &ix_arm_wallet(&authority, &plan),
        true,
    );
    let arm_p = by_name("arm_shift_p256").unwrap();
    let ap = &arm_p["args"];
    let counter = num(&ap["counter"]);
    compare(
        rep,
        &mut env,
        "arm_shift_p256",
        &ix_arm_p256(
            &authority,
            &plan_of(ap),
            counter,
            num(&ap["precompile_ix"]) as u8,
            num(&ap["sig_index"]) as u8,
        ),
        true,
    );

    // ---- keys vectors: preimage, digest, signatures -------------------------
    let kv = keys["vectors"].as_array().unwrap().clone();
    let krig = addr(&keys["rig"]);
    rep.add(
        KSRC,
        "program_id",
        verdict(addr(&keys["program_id"]) == HD),
        "",
    );
    rep.add(
        KSRC,
        "rig",
        verdict(krig == rig),
        format!("= rig_pda({authority})"),
    );
    let sk = p256::ecdsa::SigningKey::from_slice(&unhex(
        keys["key"]["private_scalar_hex"].as_str().unwrap(),
    ))
    .unwrap();
    rep.add(
        KSRC,
        "public_key_compressed_hex",
        verdict(compressed(&sk) == key_pk),
        hex(&key_pk),
    );
    let mut sig_of = std::collections::BTreeMap::new();
    for v in &kv {
        let name = v["name"].as_str().unwrap().to_string();
        let f = &v["fields"];
        let pre: Vec<u8> = match num(&v["kind"]) as u8 {
            kind::HEARTBEAT => message::heartbeat_preimage(
                &krig,
                num(&f["counter"]),
                num(&f["shift_id"]),
                num(&f["round_id"]),
                num(&f["lease_rounds"]) as u8,
            )
            .to_vec(),
            k @ (kind::BREAK | kind::FREEZE) => message::signal_preimage(
                k,
                &krig,
                num(&f["counter"]),
                num(&f["shift_id"]),
                num(&f["reason"]) as u8,
            )
            .to_vec(),
            kind::PLAN => message::plan_preimage(&krig, num(&f["counter"]), &plan_of(f)).to_vec(),
            k => panic!("kind {k}"),
        };
        let theirs = unhex(v["preimage_hex"].as_str().unwrap());
        rep.add(
            KSRC,
            &format!("{name}: preimage ({} B)", theirs.len()),
            verdict(theirs == pre),
            byte_diff(&theirs, &pre),
        );
        let digest = message::digest(&pre);
        let their_msg = unhex(v["message_hex"].as_str().unwrap());
        rep.add(
            KSRC,
            &format!("{name}: message = SHA-256(preimage)"),
            verdict(their_msg == digest),
            hex(&digest),
        );
        let (raw, low) = p256_sign_details(&sk, &digest);
        let their_raw = unhex(v["signature_rfc6979_hex"].as_str().unwrap());
        let their_low = unhex(v["signature_hex"].as_str().unwrap());
        rep.add(
            KSRC,
            &format!("{name}: signature_rfc6979 / low-S"),
            verdict(their_raw == raw && their_low == low),
            format!(
                "program-side p256 RFC 6979 raw {} low-S {}",
                hex(&raw),
                hex(&low)
            ),
        );
        let ok_low = env
            .send(&[precompile(&their_low, &key_pk, &their_msg)], &[])
            .is_ok();
        let high = their_raw != their_low;
        let rejected_high = !high
            || env
                .send(&[precompile(&their_raw, &key_pk, &their_msg)], &[])
                .is_err();
        rep.add(
            KSRC,
            &format!("{name}: secp256r1 precompile"),
            verdict(ok_low && rejected_high),
            format!(
                "low-S signature verified: {ok_low}; high-S form {}",
                if high {
                    if rejected_high {
                        "rejected (as required)"
                    } else {
                        "ACCEPTED"
                    }
                } else {
                    "n/a (already low-S)"
                }
            ),
        );
        sig_of.insert(name, (their_low.clone(), their_msg.clone()));
    }

    // Android's secp256r1_heartbeat instruction bytes vs the program-side builder.
    if let Some(v) = by_name("secp256r1_heartbeat") {
        let (s, m) = &sig_of["heartbeat_lease_1"];
        let program = precompile(s, &key_pk, m);
        let theirs = unhex(v["data_hex"].as_str().unwrap());
        rep.add(
            SRC,
            "secp256r1_heartbeat: data",
            verdict(theirs == program.data),
            byte_diff(&theirs, &program.data),
        );
    }

    // ---- execute the Android-signed messages with the PROGRAM's layouts -----
    // Rig at shift 6, counter 40, so plan_steady (counter 41) arms shift 7.
    poke_rig(&mut env, &rig, |r| {
        r.shift_id.set(6);
        r.hb_counter.set(40);
    });
    env.poke_u64(&BOARD, 8, 422_593);
    let steady = kv.iter().find(|v| v["name"] == "plan_steady").unwrap();
    let (s, m) = &sig_of["plan_steady"];
    let res = send_unsigned(
        &mut env,
        &[
            precompile(s, &key_pk, m),
            ix_arm_p256(
                &authority,
                &plan_of(&steady["fields"]),
                num(&steady["fields"]["counter"]),
                0,
                0,
            ),
        ],
    );
    rep.add(
        KSRC,
        "plan_steady signature -> arm_shift (program layout)",
        if res.is_ok() { "MATCH" } else { "MISMATCH" },
        outcome(&res),
    );
    let armed = res.is_ok();
    if armed {
        for (hb, round_ok) in [
            ("heartbeat_lease_1", 422_593u64),
            ("heartbeat_lease_3", 422_594),
        ] {
            let v = kv.iter().find(|v| v["name"] == hb).unwrap();
            let f = &v["fields"];
            let (s, m) = &sig_of[hb];
            let entry = hd::instructions::HeartbeatEntry {
                hb_ix: 0,
                hb_sig_index: 0,
                counter: num(&f["counter"]),
                round_id: num(&f["round_id"]),
                lease_rounds: num(&f["lease_rounds"]) as u8,
            };
            assert_eq!(entry.round_id, round_ok);
            // For heartbeat_lease_1, use Android's own precompile bytes as-is.
            let pre_ix = if hb == "heartbeat_lease_1" {
                by_name("secp256r1_heartbeat")
                    .map(|v| as_is(&v))
                    .unwrap_or_else(|| precompile(s, &key_pk, m))
            } else {
                precompile(s, &key_pk, m)
            };
            env.poke_u64(&BOARD, 8, round_ok);
            let res = env.send(&[pre_ix, ix_record(&[(rig, entry)])], &[]);
            let recorded = matches!(&res, Ok(meta) if raw_events(&meta.logs).first().map(|b| b[0]) == Some(ev::tag::HEARTBEATS_RECORDED));
            rep.add(
                KSRC,
                &format!("{hb} signature -> record_heartbeats"),
                verdict(recorded),
                outcome(&res),
            );
        }
        for (name, freeze) in [
            ("break_pickup", false),
            ("break_screen_on", false),
            ("freeze", true),
        ] {
            let v = kv.iter().find(|v| v["name"] == name).unwrap();
            let f = &v["fields"];
            let (s, m) = &sig_of[name];
            let c = num(&f["counter"]);
            let reason = num(&f["reason"]) as u8;
            let ix = if freeze {
                ix_freeze_p256(&authority, reason, c, 0, 0)
            } else {
                ix_break_p256(&authority, reason, c, 0, 0)
            };
            let res = env.send(&[precompile(s, &key_pk, m), ix], &[]);
            rep.add(
                KSRC,
                &format!(
                    "{name} signature -> {} (program layout)",
                    if freeze { "freeze_rig" } else { "break_shift" }
                ),
                verdict(res.is_ok()),
                format!("{} -> state {}", outcome(&res), env.rig(&rig).state),
            );
            // The same message in Android's instruction layout.
            let their_name = if freeze {
                "freeze_rig_p256"
            } else {
                "break_shift_p256"
            };
            if name != "break_screen_on" {
                if let Some(tv) = by_name(their_name) {
                    let a = &tv["args"];
                    let prog = if freeze {
                        ix_freeze_p256(
                            &authority,
                            num(&a["reason"]) as u8,
                            num(&a["counter"]),
                            num(&a["precompile_ix"]) as u8,
                            num(&a["sig_index"]) as u8,
                        )
                    } else {
                        ix_break_p256(
                            &authority,
                            num(&a["reason"]) as u8,
                            num(&a["counter"]),
                            num(&a["precompile_ix"]) as u8,
                            num(&a["sig_index"]) as u8,
                        )
                    };
                    let theirs = as_is(&tv);
                    rep.add(
                        SRC,
                        &format!("{their_name}: data"),
                        verdict(theirs.data == prog.data),
                        format!(
                            "consumer {} / program {} ({})",
                            hex(&theirs.data),
                            hex(&prog.data),
                            byte_diff(&theirs.data, &prog.data)
                        ),
                    );
                    rep.add(
                        SRC,
                        &format!("{their_name}: accounts"),
                        verdict(theirs.accounts == prog.accounts),
                        format!(
                            "consumer [{}] / program [{}]",
                            metas_str(&theirs.accounts, &names),
                            metas_str(&prog.accounts, &names)
                        ),
                    );
                    let res = send_unsigned(&mut env, &[precompile(s, &key_pk, m), theirs]);
                    rep.add(SRC, &format!("{their_name}: executed as-is (with the matching precompile at index 0)"), "INFO", outcome(&res));
                }
            }
        }
        assert_eq!(env.rig(&rig).state, rig_state::FROZEN);
        // Wallet paths as-is.
        for (name, program) in [
            ("break_shift_wallet", ix_break_wallet(&authority, 6)),
            ("freeze_rig_wallet", ix_freeze_wallet(&authority)),
            ("unfreeze_rig", ix_unfreeze(&authority, true)),
            ("end_shift", ix_end_shift(&authority, &rig, 7)),
        ] {
            compare(rep, &mut env, name, &program, true);
        }
        // Program-format end + unfreeze, then plan_focus_only (counter 47).
        let res = send_unsigned(
            &mut env,
            &[
                ix_end_shift(&authority, &rig, 7),
                ix_unfreeze(&authority, true),
            ],
        );
        assert!(res.is_ok(), "{res:?}");
        let focus = kv.iter().find(|v| v["name"] == "plan_focus_only").unwrap();
        let (s, m) = &sig_of["plan_focus_only"];
        let res = send_unsigned(
            &mut env,
            &[
                precompile(s, &key_pk, m),
                ix_arm_p256(
                    &authority,
                    &plan_of(&focus["fields"]),
                    num(&focus["fields"]["counter"]),
                    0,
                    0,
                ),
            ],
        );
        rep.add(
            KSRC,
            "plan_focus_only signature -> arm_shift (program layout)",
            verdict(res.is_ok()),
            outcome(&res),
        );
        let res = send_unsigned(&mut env, &[ix_end_shift(&authority, &rig, 8)]);
        assert!(res.is_ok(), "{res:?}");
    }

    // close_rig: guest as-is on the Idle rig.
    compare(
        rep,
        &mut env,
        "close_rig_guest",
        &ix_close_rig(&authority, None),
        true,
    );
    // close_rig_seeker as-is on a re-registered rig made Seeker-tier by surgery
    // (the Android mint is not a real SGT; the seat is not initialized, so
    // only the rig closes).
    if let Some(v) = by_name("close_rig_seeker") {
        let mint = addr(&v["args"]["sgt_mint"]);
        assert!(send_unsigned(&mut env, &[ix_register_rig(&authority, &p256, None)]).is_ok());
        poke_rig(&mut env, &rig, |r| {
            r.tier = 1;
            r.sgt_mint = mint.to_bytes();
        });
        rep.add(
            SRC,
            "pdas.seeker_seat",
            verdict(addr(&ixv["pdas"]["seeker_seat"]) == seat_pda(&mint)),
            format!("seat_pda({mint}) = {}", seat_pda(&mint)),
        );
        compare(
            rep,
            &mut env,
            "close_rig_seeker",
            &ix_close_rig(&authority, Some(seat_pda(&mint))),
            true,
        );
    }

    // ORE automate / revoke as the Android client builds them (ORE's layout).
    if let Some(v) = by_name("ore_automate_heads_down") {
        let a = &v["args"];
        let mut expect = ore_automate(
            &authority,
            &EXECUTOR,
            num(&a["amount_per_tile"]),
            num(&a["deposit"]),
            num(&a["executor_fee"]),
            num(&a["strategy"]) as u8,
            0,
            u16::MAX,
        );
        expect.data[34..42].copy_from_slice(&num(&a["reload"]).to_le_bytes()); // reload u64 @34
        let theirs = as_is(&v);
        rep.add(
            SRC,
            "ore_automate_heads_down: data (ORE AutomateV2, 66 B)",
            verdict(theirs.data == expect.data),
            byte_diff(&theirs.data, &expect.data),
        );
        rep.add(
            SRC,
            "ore_automate_heads_down: accounts",
            verdict(theirs.accounts == expect.accounts),
            metas_str(&theirs.accounts, &names),
        );
        let res = send_unsigned(&mut env, &[theirs]);
        rep.add(
            SRC,
            "ore_automate_heads_down: executed as-is on live ORE",
            "INFO",
            outcome(&res),
        );
        let fee = num(&a["executor_fee"]);
        rep.add(
            SRC,
            "ore_automate_heads_down: fee",
            "INFO",
            format!("vector uses fee {fee}; dig skips the rig with StrategyMismatch (23) unless automation.fee == Config.executor_fee (read @80 from the Config account)"),
        );
        if let Some(r) = by_name("ore_revoke") {
            let res = send_unsigned(&mut env, &[as_is(&r)]);
            rep.add(
                SRC,
                "ore_revoke: executed as-is on live ORE",
                "INFO",
                outcome(&res),
            );
        }
    }
}

// ---- 2. crank/test-fixtures/vectors/interface.json ------------------------------------

fn crank(rep: &mut Report) {
    const SRC: &str = "crank/test-fixtures/vectors/interface.json";
    let Some(v) = read("crank/test-fixtures/vectors/interface.json") else {
        rep.add(SRC, "file", "INFO", "not found; skipped");
        return;
    };
    rep.add(
        SRC,
        "program_id_hex",
        verdict(unhex(v["program_id_hex"].as_str().unwrap()) == HD.as_ref()),
        "",
    );
    let rig = Address::new_from_array(unhex(v["rig_hex"].as_str().unwrap()).try_into().unwrap());
    let h = &v["heartbeat"];
    let hb_pre = message::heartbeat_preimage(
        &rig,
        num(&h["counter"]),
        num(&h["shift_id"]),
        num(&h["round_id"]),
        num(&h["lease_rounds"]) as u8,
    );
    let b = &v["break"];
    let br_pre = message::signal_preimage(
        num(&b["kind"]) as u8,
        &rig,
        num(&b["counter"]),
        num(&b["shift_id"]),
        num(&b["reason"]) as u8,
    );
    let p = &v["plan"];
    let plan = Plan {
        max_ev_cost: num(&p["max_ev_cost"]),
        dig_lamports: num(&p["dig_lamports"]),
        split: num(&p["split"]) as u8,
        solo: num(&p["solo"]) as u8,
        lease: num(&p["lease"]) as u8,
        flags: num(&p["flags"]) as u8,
        window_start: num(&p["window_start"]) as i64,
        window_end: num(&p["window_end"]) as i64,
    };
    let pl_pre = message::plan_preimage(&rig, num(&p["counter"]), &plan);
    let mut digests = vec![];
    for (name, obj, pre) in [
        ("heartbeat", h, hb_pre.to_vec()),
        ("break", b, br_pre.to_vec()),
        ("plan", p, pl_pre.to_vec()),
    ] {
        let theirs = unhex(obj["preimage_hex"].as_str().unwrap());
        rep.add(
            SRC,
            &format!("{name}: preimage ({} B)", theirs.len()),
            verdict(theirs == pre),
            byte_diff(&theirs, &pre),
        );
        let d = message::digest(&pre);
        rep.add(
            SRC,
            &format!("{name}: digest"),
            verdict(unhex(obj["digest_hex"].as_str().unwrap()) == d),
            hex(&d),
        );
        digests.push((name, d));
    }
    // Which digest does the (r, s) pair sign? Ask the real precompile.
    let pk: [u8; 33] = unhex(v["p256"]["pubkey_hex"].as_str().unwrap())
        .try_into()
        .unwrap();
    let r = unhex(v["p256"]["r_hex"].as_str().unwrap());
    let s_low = unhex(v["p256"]["s_low_hex"].as_str().unwrap());
    let s_high = unhex(v["p256"]["s_high_hex"].as_str().unwrap());
    let sig_low: Vec<u8> = [r.clone(), s_low].concat();
    let sig_high: Vec<u8> = [r, s_high].concat();
    let mut env = Env::build_with(Build::Mainnet, false, true, EnvKeys::golden(), true);
    assert_precompiles_verify(&mut env);
    let mut signed = None;
    for (name, d) in &digests {
        if env.send(&[precompile(&sig_low, &pk, d)], &[]).is_ok() {
            signed = Some((*name, *d));
        }
    }
    rep.add(
        SRC,
        "p256 (r, s_low)",
        if signed.is_some() {
            "MATCH"
        } else {
            "MISMATCH"
        },
        format!(
            "secp256r1 precompile verifies it over the {} digest",
            signed.map_or("<none>", |x| x.0)
        ),
    );
    if let Some((_, d)) = signed {
        let high_rejected = env.send(&[precompile(&sig_high, &pk, &d)], &[]).is_err();
        rep.add(
            SRC,
            "p256 (r, s_high)",
            verdict(high_rejected),
            "high-S form rejected by the precompile",
        );
    }
    // Execute the crank heartbeat through record_heartbeats on a rig account
    // at the vector's (non-PDA) address: record_heartbeats does not re-derive
    // the rig, so the vector's rig bytes can be used verbatim.
    if signed.map(|x| x.0) == Some("heartbeat") {
        let mut data = vec![0u8; 384];
        {
            let g: &mut hd::state::Rig = bytemuck::from_bytes_mut(&mut data);
            g.header = hd::state::Header::new(hd::state::tag::RIG, 255);
            g.p256_pubkey = pk;
            g.state = rig_state::ARMED;
            g.shift_id.set(num(&h["shift_id"]));
            g.hb_counter.set(num(&h["counter"]) - 1);
            g.plan_lease_rounds = 3;
            g.shift_open = 1;
            g.shift_start_round.set(num(&h["round_id"]));
        }
        env.svm
            .set_account(
                rig,
                Account {
                    lamports: SOL,
                    data,
                    owner: HD,
                    executable: false,
                    rent_epoch: 0,
                },
            )
            .unwrap();
        env.poke_u64(&BOARD, 8, num(&h["round_id"]));
        let entry = hd::instructions::HeartbeatEntry {
            hb_ix: 0,
            hb_sig_index: 0,
            counter: num(&h["counter"]),
            round_id: num(&h["round_id"]),
            lease_rounds: num(&h["lease_rounds"]) as u8,
        };
        let res = env.send(
            &[
                precompile(&sig_low, &pk, &digests[0].1),
                ix_record(&[(rig, entry)]),
            ],
            &[],
        );
        let ok_rec = matches!(&res, Ok(m) if raw_events(&m.logs).first().map(|b| b[0]) == Some(ev::tag::HEARTBEATS_RECORDED));
        rep.add(
            SRC,
            "heartbeat executed via record_heartbeats",
            verdict(ok_rec),
            outcome(&res),
        );
    }
    // ema_ev table vs the program's integer gate.
    for row in v["ema_ev"].as_array().unwrap() {
        let (ema, pot, want) = (num(&row["ema"]), num(&row["pot"]), num(&row["ema_ev"]));
        let got = hd::logic::ema_ev(ema, pot);
        let as_u64 = u64::try_from(got).ok();
        rep.add(
            SRC,
            &format!("ema_ev(ema {ema}, pot {pot})"),
            verdict(as_u64 == Some(want)),
            format!("program u128 {got}; crank {want}"),
        );
    }
}

// ---- 3. event layouts and semantics (indexer N1-N4, crank A1-A3, B12, B14) -----

#[allow(clippy::too_many_lines)]
fn events_and_semantics(rep: &mut Report, logs_out: &mut Vec<Value>) {
    const IDX: &str = "services/indexer INTERFACE-NOTES + src/codec/events.ts";
    const CRK: &str = "crank INTERFACE-NOTES + src/hd.rs";
    // Assumed layouts (field sizes in order after the tag), from the notes.
    let assumed: [(u8, &str, &[usize]); 5] = [
        (1, "RigDug", &[32, 8, 8, 4, 8]),
        (2, "RigSkipped", &[32, 8, 4]),
        (3, "ShiftArmed", &[32, 8]),
        (4, "ShiftEnded", &[32, 8, 8, 8, 8, 1]),
        (5, "SeekerVerified", &[32, 32, 8]),
    ];
    for (tag, name, sizes) in assumed {
        let program: Vec<usize> = heads_down_tests::vectors::event_layout(tag)
            .iter()
            .skip(1)
            .map(|x| x.3)
            .collect();
        let total = 1 + sizes.iter().sum::<usize>();
        let ok = program == sizes && total == ev::LEN[usize::from(tag)];
        rep.add(
            IDX,
            &format!("N1 {name} (tag {tag}) layout"),
            verdict(ok),
            format!(
                "{total} B; program {} B, fields {:?}",
                ev::LEN[usize::from(tag)],
                program
            ),
        );
        if tag <= 3 {
            rep.add(
                CRK,
                &format!("A1 {name} (tag {tag}) layout"),
                verdict(ok),
                format!("{total} B"),
            );
        }
    }
    for tag in 6..=10u8 {
        rep.add(
            IDX,
            &format!("tag {tag} ({} B)", ev::LEN[usize::from(tag)]),
            "MISMATCH",
            "not in HD_EVENT_SIZE: decoded as Unknown (new in v1.1)",
        );
    }

    // A real dig: RigDug.lamports vs the Automation debit.
    let mut env = Env::golden(Build::Mainnet);
    let mut u = User::with_keys(&mut env, [0x51; 32], [0x52; 32]);
    let w = u.wallet.insecure_clone();
    let res = env.send_as(
        &w,
        &[
            ore_automate_default(&w.pubkey(), SOL / 20),
            ix_register_rig(&w.pubkey(), &u.p256(), None),
            ix_set_caps(&w.pubkey(), Caps::standard()),
            ix_arm_wallet(&w.pubkey(), &standard_plan()),
        ],
        &[],
    );
    logs_out.push(
        json!({"name": "onboard (register_rig + arm_shift)", "logs": res.as_ref().unwrap().logs}),
    );
    let before = env.automation(&u.automation()).unwrap().balance;
    let meta = ok(env.dig_fresh(&mut [&mut u]));
    logs_out.push(json!({"name": "dig (1 rig, fresh heartbeat)", "logs": meta.logs}));
    let debit = before - env.automation(&u.automation()).unwrap().balance;
    let (lamports, mask) = dug(&events(&meta.logs), &u.rig).unwrap();
    rep.add(CRK, "A2 RigDug.lamports = Automation debit (tiles + fee)", verdict(lamports == debit), format!("program RigDug.lamports = {lamports} (SOL on squares only); Automation debit = {debit} = lamports + executor_fee {EXECUTOR_FEE} on the rig's first deploy of the round"));
    rep.add(
        IDX,
        "N2 RigDug.lamports = SOL on squares, excluding the fee",
        verdict(lamports + EXECUTOR_FEE == debit),
        format!("{lamports}"),
    );
    rep.add(IDX, "N2 RigDug.mask = squares requested", "MATCH", format!("mask 0x{mask:07x} ({} squares); squares the Miner already holds are excluded before the CPI, so requested == credited", mask.count_ones()));

    // Fee inside caps (crank B12 / Android clock-in cap_round = dig_lamports).
    let mut env = Env::golden(Build::Mainnet);
    let mut u = User::with_keys(&mut env, [0x53; 32], [0x54; 32]);
    let w = u.wallet.insecure_clone();
    let mut caps = Caps::standard();
    caps.round = 1_000_000;
    ok(env.send_as(
        &w,
        &[
            ore_automate_default(&w.pubkey(), SOL / 20),
            ix_register_rig(&w.pubkey(), &u.p256(), None),
            ix_set_caps(&w.pubkey(), caps),
            ix_arm_wallet(&w.pubkey(), &standard_plan()),
        ],
        &[],
    ));
    let meta = ok(env.dig_fresh(&mut [&mut u]));
    let (lamports, mask) = dug(&events(&meta.logs), &u.rig).unwrap();
    let naive_per_tile = 1_000_000 / 10;
    rep.add(CRK, "B12 amount = min(plan_dig, cap_round, ...) / (split + solo)", verdict(lamports == naive_per_tile * 10), format!("cap_round = plan_dig = 1,000,000, 10 split squares: INTERFACE v1 formula predicts {naive_per_tile}/square = 1,000,000; program deploys {}/square = {lamports} (fee {EXECUTOR_FEE} reserved inside cap_round), k = popcount(mask) = {}", lamports / u64::from(mask.count_ones()), mask.count_ones()));

    // Skip codes the crank's mock and the indexer proposal assumed.
    for (item, assumed, actual) in [
        (
            "A3 ORE no-op after CPI",
            "RigSkipped(12 BudgetExhausted) (mock)",
            "RigSkipped(29 OreNoOp)",
        ),
        (
            "B14 per_tile == 0",
            "12 BudgetExhausted",
            "12 BudgetExhausted",
        ),
        (
            "B14 balance < per_tile*k + fee",
            "12 BudgetExhausted",
            "28 InsufficientAutomationBalance",
        ),
        (
            "B14 Motherlode conditions fail",
            "1 CostGate",
            "27 MotherlodeCondition",
        ),
        (
            "B14 ORE round window closed",
            "11 OutsideWindow",
            "25 RoundNotActive",
        ),
        (
            "B14 Miner not checkpointed",
            "3 InvalidOreAccount",
            "26 MinerNotCheckpointed",
        ),
        (
            "B14 Miner missing",
            "3 InvalidOreAccount",
            "3 InvalidOreAccount",
        ),
        (
            "B14 Automation executor wrong / revoked",
            "2 InvalidExecutor",
            "2 InvalidExecutor",
        ),
        (
            "B14 Automation authority wrong",
            "2 InvalidExecutor",
            "transaction fails with 3 InvalidOreAccount (not a skip)",
        ),
    ] {
        let first = |s: &str| {
            s.split(' ')
                .next()
                .unwrap()
                .trim_start_matches("RigSkipped(")
                .to_string()
        };
        rep.add(
            CRK,
            item,
            verdict(first(assumed) == first(actual)),
            format!("crank assumes {assumed}; program: {actual} (vectors/events.json skip_codes)"),
        );
    }
    for (code, proposed, actual) in [
        (24, "OreWindowClosed", "InvalidRigState (not a dig skip)"),
        (25, "MinerNotCheckpointed", "RoundNotActive"),
        (26, "AutomationUnderfunded", "MinerNotCheckpointed"),
        (27, "OreNoOp", "MotherlodeCondition"),
    ] {
        rep.add(
            IDX,
            &format!("N4 code {code}"),
            "MISMATCH",
            format!("proposed {proposed}; program {actual}"),
        );
    }

    // More real logs for the indexer's parser.
    let w = u.wallet.insecure_clone();
    let m = ok(env.send_as(
        &w,
        &[ix_break_wallet(
            &w.pubkey(),
            hd::state::break_reason::UNLOCKED,
        )],
        &[],
    ));
    logs_out.push(json!({"name": "break_shift (reason 8 unlocked)", "logs": m.logs}));
    let m = ok(env.send_as(&w, &[ix_end_shift(&w.pubkey(), &u.rig, 1)], &[]));
    logs_out.push(json!({"name": "end_shift", "logs": m.logs}));
    let m = ok(env.send_as(&w, &[ix_close_rig(&w.pubkey(), None)], &[]));
    logs_out.push(json!({"name": "close_rig", "logs": m.logs}));
    let mut v = User::with_keys(&mut env, [0x55; 32], [0x56; 32]);
    let wv = v.wallet.insecure_clone();
    let mut plan = standard_plan();
    plan.flags = hd::state::plan_flags::FOCUS_ONLY;
    ok(env.send_as(
        &wv,
        &[
            ore_automate_default(&wv.pubkey(), SOL / 20),
            ix_register_rig(&wv.pubkey(), &v.p256(), None),
            ix_set_caps(&wv.pubkey(), Caps::standard()),
            ix_arm_wallet(&wv.pubkey(), &plan),
        ],
        &[],
    ));
    let hb = v.heartbeat(1, env.board_round, 3);
    let m = ok(env.send(
        &[
            secp_ix_for(&[hb]),
            ix_record(&[(v.rig, entry_for(&hb, 0, 0))]),
        ],
        &[],
    ));
    logs_out.push(json!({"name": "record_heartbeats", "logs": m.logs}));
    let m = ok(env.dig_fresh(&mut [&mut v]));
    logs_out.push(json!({"name": "dig (focus-only rig: RigSkipped 30)", "logs": m.logs}));
}

#[test]
#[ignore = "reads other teams' vector files; run explicitly for vectors/CROSSCHECK.md"]
fn crosscheck_consumer_assumptions() {
    let mut rep = Report::default();
    let mut logs = vec![];
    android(&mut rep);
    crank(&mut rep);
    events_and_semantics(&mut rep, &mut logs);
    let target = root().join("target");
    std::fs::create_dir_all(&target).unwrap();
    std::fs::write(
        target.join("crosscheck-report.json"),
        serde_json::to_string_pretty(&rep.rows).unwrap(),
    )
    .unwrap();
    std::fs::write(
        target.join("crosscheck-logs.json"),
        serde_json::to_string_pretty(&logs).unwrap(),
    )
    .unwrap();
    let count = |v: &str| rep.rows.iter().filter(|r| r["verdict"] == v).count();
    println!(
        "\nMATCH {} / MISMATCH {} / INFO {}",
        count("MATCH"),
        count("MISMATCH"),
        count("INFO")
    );
}
