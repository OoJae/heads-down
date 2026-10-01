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
//!
//! The Android scenario makes no assumption about which consumer vector
//! succeeds: after every vector it executes as-is, it brings the rig back
//! to the state the next step needs ([`ensure_rig`], [`ensure_idle`]), so a
//! vector that starts or stops matching changes its own verdict and nothing
//! else. A vector executed as-is is a MATCH when the program accepts it with
//! the effect the contract gives it.

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

/// Register the rig in the program's own format if nothing is registered at
/// its PDA (a consumer vector may or may not have done it).
fn ensure_rig(env: &mut Env, authority: &Address, p256: &[u8; 33]) {
    let rig = rig_pda(authority);
    if env.rig_slot(&rig) != RigSlot::Rig {
        let res = send_unsigned(env, &[ix_register_rig(authority, p256, None)]);
        assert!(res.is_ok(), "canonical register_rig: {res:?}");
    }
}

/// Bring the rig to Idle with no open shift, whatever state the consumer
/// vectors left it in: unfreeze if Frozen, then seal an open shift.
fn ensure_idle(env: &mut Env, authority: &Address) {
    let rig = rig_pda(authority);
    if env.rig(&rig).state == rig_state::FROZEN {
        let res = send_unsigned(env, &[ix_unfreeze(authority, true)]);
        assert!(res.is_ok(), "unfreeze_rig: {res:?}");
    }
    let g = env.rig(&rig);
    if g.shift_open == 1 {
        let res = send_unsigned(env, &[ix_end_shift(authority, &rig, g.shift_id.get())]);
        assert!(res.is_ok(), "end_shift: {res:?}");
    }
    let g = env.rig(&rig);
    assert_eq!((g.state, g.shift_open), (rig_state::IDLE, 0));
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
    // A consumer vector against the program-format instruction for the same
    // arguments: data and ordered account metas. Returns the consumer's own
    // instruction.
    let compare = |rep: &mut Report, name: &str, program: &Instruction| -> Option<Instruction> {
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
        Some(theirs)
    };
    // The consumer's instruction executed as-is (after `before`, the
    // companions its indices point at): a MATCH when the program accepts it.
    let execute = |rep: &mut Report,
                   env: &mut Env,
                   name: &str,
                   before: &[Instruction],
                   theirs: &Instruction,
                   note: &str|
     -> bool {
        let mut tx = before.to_vec();
        tx.push(theirs.clone());
        let res = send_unsigned(env, &tx);
        let state = match env.rig_slot(&rig) {
            RigSlot::Rig => format!("rig state {}", env.rig(&rig).state),
            other => format!("rig PDA {other:?}"),
        };
        rep.add(
            SRC,
            &format!("{name}: executed as-is{note}"),
            verdict(res.is_ok()),
            format!("{} -> {state}", outcome(&res)),
        );
        res.is_ok()
    };

    // ---- registration ---------------------------------------------------------
    let reg = by_name("register_rig_guest").unwrap();
    let p256: [u8; 33] = unhex(reg["args"]["p256_pubkey_hex"].as_str().unwrap())
        .try_into()
        .unwrap();
    if let Some(ix) = compare(rep, "register_rig_guest", &ix_register_rig(&authority, &p256, None)) {
        execute(rep, &mut env, "register_rig_guest", &[], &ix, "");
    }
    if let Some(att) = by_name("register_rig_attested") {
        let a = &att["args"];
        let (att_ix, level, expiry) = (
            num(&a["attestation_ix"]) as u8,
            num(&a["attestation_level"]) as u8,
            num(&a["attestation_expiry_slot"]),
        );
        let sig = a.get("ed25519_sig_index").map_or(0, |v| num(v) as u8);
        let program = ix_register_rig(
            &authority,
            &p256,
            Some(AttestationArg {
                ix: att_ix,
                sig,
                level,
                expiry_slot: expiry,
            }),
        );
        if let Some(ix) = compare(rep, "register_rig_attested", &program) {
            // Free the address if the guest vector registered it (a rig that
            // never armed closes completely), then run the attested vector
            // with a real voucher from this fork's registrar at the
            // top-level index the vector names.
            if env.rig_slot(&rig) == RigSlot::Rig {
                let res = send_unsigned(&mut env, &[ix_close_rig(&authority, None)]);
                assert!(res.is_ok(), "close_rig between the two registrations: {res:?}");
            }
            if att_ix == 0 && expiry > env.slot {
                let registrar = env.registrar.insecure_clone();
                let (_, _, voucher) =
                    vectors::registrar_voucher(&registrar, &authority, &p256, level, expiry);
                let note = format!(
                    " with a level-{level} registrar voucher (Ed25519SigVerify) at index 0"
                );
                if execute(rep, &mut env, "register_rig_attested", &[voucher], &ix, &note) {
                    let g = env.rig(&rig);
                    rep.add(
                        SRC,
                        "register_rig_attested: attestation stored",
                        verdict(
                            g.attestation_level == level
                                && g.attestation_expiry_slot.get() == expiry,
                        ),
                        format!(
                            "rig.attestation_level {} expiry_slot {}",
                            g.attestation_level,
                            g.attestation_expiry_slot.get()
                        ),
                    );
                }
            } else {
                rep.add(
                    SRC,
                    "register_rig_attested: executed as-is",
                    "INFO",
                    format!("not executed: attestation_ix {att_ix}, expiry_slot {expiry} at slot {}", env.slot),
                );
            }
        }
    }
    // The canonical registration, only if no vector left a rig behind.
    ensure_rig(&mut env, &authority, &p256);
    if let Some(ix) = compare(rep, "rotate_key", &ix_rotate_key(&authority, &p256, None)) {
        execute(rep, &mut env, "rotate_key", &[], &ix, "");
    }

    // ---- caps and the wallet arm ------------------------------------------------
    let sc = by_name("set_caps").unwrap();
    let a = &sc["args"];
    let caps = Caps {
        week: num(&a["cap_week"]),
        shift: num(&a["cap_shift"]),
        round: num(&a["cap_round"]),
        max_cost: num(&a["cap_max_cost"]),
        expiry: num(&a["caps_expiry_ts"]) as i64,
    };
    let caps_set = compare(rep, "set_caps", &ix_set_caps(&authority, caps))
        .is_some_and(|ix| execute(rep, &mut env, "set_caps", &[], &ix, ""));
    if !caps_set {
        let res = send_unsigned(&mut env, &[ix_set_caps(&authority, caps)]);
        assert!(res.is_ok(), "canonical set_caps: {res:?}");
    }
    assert_eq!(env.rig(&rig).cap_round.get(), caps.round, "set_caps applied");

    let arm_w = by_name("arm_shift_wallet").unwrap();
    let plan = plan_of(&arm_w["args"]);
    if let Some(ix) = compare(rep, "arm_shift_wallet", &ix_arm_wallet(&authority, &plan)) {
        execute(rep, &mut env, "arm_shift_wallet", &[], &ix, "");
    }
    // End the shift that vector armed (if it did), so the P-256 scenario below
    // starts from an Idle rig either way.
    ensure_idle(&mut env, &authority);

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

    // ---- the Android-signed messages, in Android's own instructions --------------
    // Rig at shift 6, counter 40, so plan_steady (counter 41) arms shift 7.
    poke_rig(&mut env, &rig, |r| {
        r.shift_id.set(6);
        r.hb_counter.set(40);
    });
    env.poke_u64(&BOARD, 8, 422_593);
    // One signed message through the program: Android's own instruction for
    // it when `ix_vectors.json` has one (compared, then executed as-is with
    // the matching precompile at index 0), else the program-format builder.
    let signed_step = |rep: &mut Report,
                       env: &mut Env,
                       key_vector: &str,
                       ix_vector: Option<&str>,
                       fallback: Instruction,
                       what: &str|
     -> bool {
        let (s, m) = &sig_of[key_vector];
        let pre = precompile(s, &key_pk, m);
        let theirs = ix_vector.and_then(|n| {
            let v = by_name(n)?;
            let a = &v["args"];
            if a.get("precompile_ix").map(num) != Some(0) {
                return None;
            }
            let c = num(&a["counter"]);
            let sig = num(&a["sig_index"]) as u8;
            let program = match n {
                "arm_shift_p256" => ix_arm_p256(&authority, &plan_of(a), c, 0, sig),
                "break_shift_p256" => ix_break_p256(&authority, num(&a["reason"]) as u8, c, 0, sig),
                _ => ix_freeze_p256(&authority, num(&a["reason"]) as u8, c, 0, sig),
            };
            compare(rep, n, &program)
        });
        let (ix, via) = match (&theirs, ix_vector) {
            (Some(t), Some(n)) => (t.clone(), format!("Android's {n} as-is")),
            _ => (fallback, "program-format instruction".to_string()),
        };
        let res = send_unsigned(env, &[pre, ix]);
        if let (Some(_), Some(n)) = (&theirs, ix_vector) {
            rep.add(
                SRC,
                &format!("{n}: executed as-is with the {key_vector} signature at index 0"),
                verdict(res.is_ok()),
                format!("{} -> rig state {}", outcome(&res), env.rig(&rig).state),
            );
        }
        rep.add(
            KSRC,
            &format!("{key_vector} signature -> {what}"),
            verdict(res.is_ok()),
            format!("{via}: {} -> rig state {}", outcome(&res), env.rig(&rig).state),
        );
        res.is_ok()
    };

    let steady = kv.iter().find(|v| v["name"] == "plan_steady").unwrap();
    let armed = signed_step(
        rep,
        &mut env,
        "plan_steady",
        Some("arm_shift_p256"),
        ix_arm_p256(
            &authority,
            &plan_of(&steady["fields"]),
            num(&steady["fields"]["counter"]),
            0,
            0,
        ),
        "arm_shift",
    );
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
        let field = |name: &str, f: &str| {
            let v = kv.iter().find(|v| v["name"] == name).unwrap();
            num(&v["fields"][f])
        };
        // Shift 7: pickup, screen-on, FREEZE from the phone key.
        signed_step(
            rep,
            &mut env,
            "break_pickup",
            Some("break_shift_p256"),
            ix_break_p256(
                &authority,
                field("break_pickup", "reason") as u8,
                field("break_pickup", "counter"),
                0,
                0,
            ),
            "break_shift",
        );
        signed_step(
            rep,
            &mut env,
            "break_screen_on",
            None,
            ix_break_p256(
                &authority,
                field("break_screen_on", "reason") as u8,
                field("break_screen_on", "counter"),
                0,
                0,
            ),
            "break_shift",
        );
        signed_step(
            rep,
            &mut env,
            "freeze",
            Some("freeze_rig_p256"),
            ix_freeze_p256(
                &authority,
                field("freeze", "reason") as u8,
                field("freeze", "counter"),
                0,
                0,
            ),
            "freeze_rig",
        );
        // The wallet unfreezes (Frozen -> Broken: shift 7 is still open) and
        // seals shift 7, both in Android's own instructions.
        for (name, program) in [
            ("unfreeze_rig", ix_unfreeze(&authority, true)),
            ("end_shift", ix_end_shift(&authority, &rig, 7)),
        ] {
            if let Some(ix) = compare(rep, name, &program) {
                execute(rep, &mut env, name, &[], &ix, "");
            }
        }
        // Whatever those two did, continue from Idle (no-ops if they worked).
        ensure_idle(&mut env, &authority);

        // Shift 8: plan_focus_only (counter 47), then the wallet BREAK and
        // FREEZE vectors on it.
        let focus = kv.iter().find(|v| v["name"] == "plan_focus_only").unwrap();
        let focus_armed = signed_step(
            rep,
            &mut env,
            "plan_focus_only",
            None,
            ix_arm_p256(
                &authority,
                &plan_of(&focus["fields"]),
                num(&focus["fields"]["counter"]),
                0,
                0,
            ),
            "arm_shift",
        );
        if focus_armed {
            for (name, program) in [
                ("break_shift_wallet", ix_break_wallet(&authority, 6)),
                ("freeze_rig_wallet", ix_freeze_wallet(&authority)),
            ] {
                if let Some(ix) = compare(rep, name, &program) {
                    execute(rep, &mut env, name, &[], &ix, "");
                }
            }
        }
    }
    // Program-format unfreeze / end_shift, only as far as the rig still needs
    // them (nothing if it is already Idle).
    ensure_idle(&mut env, &authority);

    // close_rig: guest as-is on the Idle rig. v1.3: a rig that armed a shift
    // leaves a 32-byte tombstone at its PDA.
    if let Some(ix) = compare(rep, "close_rig_guest", &ix_close_rig(&authority, None)) {
        execute(rep, &mut env, "close_rig_guest", &[], &ix, "");
    }
    // close_rig_seeker as-is on a re-registered rig made Seeker-tier by surgery
    // (the Android mint is not a real SGT; the seat is not initialized, so
    // only the rig closes).
    if let Some(v) = by_name("close_rig_seeker") {
        let mint = addr(&v["args"]["sgt_mint"]);
        ensure_rig(&mut env, &authority, &p256);
        ensure_idle(&mut env, &authority);
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
        if let Some(ix) = compare(
            rep,
            "close_rig_seeker",
            &ix_close_rig(&authority, Some(seat_pda(&mint))),
        ) {
            execute(rep, &mut env, "close_rig_seeker", &[], &ix, "");
        }
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

// ---- 3. program facts the consumers rely on, measured on the fork ---------------------
//
// These rows are INFO: they state what the program does, with numbers from a
// real run. Whether each consumer agrees is measured by running the
// consumer's own code (`vectors/crosscheck/run.sh` steps 2 to 4), not by
// comparing with a note about it.

#[allow(clippy::too_many_lines)]
fn program_facts(rep: &mut Report, logs_out: &mut Vec<Value>) {
    const SRC: &str = "program (LiteSVM fork)";
    for (tag, len) in ev::LEN.iter().enumerate().skip(1) {
        rep.add(
            SRC,
            &format!("event tag {tag} ({})", heads_down_tests::vectors::event_name(tag as u8)),
            "INFO",
            format!("{len} bytes"),
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
    assert_eq!(lamports + EXECUTOR_FEE, debit);
    rep.add(
        SRC,
        "RigDug.lamports = SOL on squares, excluding the Automation fee",
        "INFO",
        format!("RigDug.lamports {lamports}; Automation debit {debit} = lamports + executor_fee {EXECUTOR_FEE} on the rig's first deploy of the round"),
    );
    rep.add(
        SRC,
        "RigDug.mask = squares requested = squares credited",
        "INFO",
        format!("mask 0x{mask:07x} ({} squares); squares the Miner already holds are excluded before the CPI", mask.count_ones()),
    );

    // The fee is reserved inside every cap.
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
    assert_eq!(lamports, 995_000);
    rep.add(
        SRC,
        "the fee is reserved inside the caps",
        "INFO",
        format!("cap_round = plan_dig = 1,000,000 on 10 split squares: {} per square = {lamports} on squares, debit 1,000,000 (k = popcount(mask) = {})", lamports / u64::from(mask.count_ones()), mask.count_ones()),
    );


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
    program_facts(&mut rep, &mut logs);
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
    // Totals per consumer file, then overall.
    let mut sources: Vec<String> = vec![];
    for r in &rep.rows {
        let s = r["source"].as_str().unwrap().to_string();
        if !sources.contains(&s) {
            sources.push(s);
        }
    }
    let count = |src: Option<&str>, v: &str| {
        rep.rows
            .iter()
            .filter(|r| r["verdict"] == v && src.is_none_or(|s| r["source"] == s))
            .count()
    };
    println!();
    for s in &sources {
        println!(
            "TOTAL {s}: MATCH {} / MISMATCH {} / INFO {}",
            count(Some(s), "MATCH"),
            count(Some(s), "MISMATCH"),
            count(Some(s), "INFO")
        );
    }
    println!(
        "\nMATCH {} / MISMATCH {} / INFO {}",
        count(None, "MATCH"),
        count(None, "MISMATCH"),
        count(None, "INFO")
    );
}
