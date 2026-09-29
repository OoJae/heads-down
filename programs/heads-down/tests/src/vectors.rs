//! Golden vectors: the machine-checked `heads_down` contract
//! (`programs/heads-down/vectors/`).
//!
//! [`generate`] runs one deterministic scenario on the pinned LiteSVM fork
//! ([`crate::Env::golden`]: the live ORE bytecode, fixed keys, fixed Board /
//! Treasury / Round values) and records, for every instruction and both
//! authorization paths:
//!
//! * the exact instruction data with a per-field layout,
//! * the ordered account metas with role names, signer / writable flags and
//!   the PDA seeds each address is derived from,
//! * the whole transaction it ran in (precompile and compute-budget
//!   companions, so `hb_ix` / `ed25519_ix` indices are meaningful),
//! * the LiteSVM outcome and the raw events it emitted.
//!
//! It also writes the signed-message preimages (`messages.json`), every
//! event layout with bytes captured from real runs (`events.json`), and the
//! registrar voucher format (`registrar.json`). Nothing here is random:
//! every key is a fixed public test key, so the output is byte-stable and
//! `tests/tests/vectors.rs` fails if a committed file drifts.

use std::{collections::BTreeMap, str::FromStr};

use hd::{
    error::HdError,
    events as ev,
    instructions::HeartbeatEntry,
    message::{self, kind, Plan},
    state::{break_reason, plan_flags},
};
use p256::ecdsa::{signature::Signer as _, DerSignature};
use p256_introspect::client::der_to_low_s_raw;
use serde_json::{json, Value};

use crate::*;

// ---- small helpers ----------------------------------------------------------

/// Lower-case hex.
pub fn hex(b: &[u8]) -> String {
    b.iter().map(|x| format!("{x:02x}")).collect()
}

fn s(v: impl ToString) -> Value {
    Value::String(v.to_string())
}

/// RFC 6979 A.2.5 P-256 test key: public test material (the same key the
/// Android and crank vectors use).
pub const RFC6979_P256_SCALAR: [u8; 32] = [
    0xc9, 0xaf, 0xa9, 0xd8, 0x45, 0xba, 0x75, 0x16, 0x6b, 0x5c, 0x21, 0x57, 0x67, 0xb1, 0xd6, 0x93,
    0x4e, 0x50, 0xc3, 0xdb, 0x36, 0xe8, 0x9b, 0x12, 0x7b, 0x8a, 0x62, 0x2b, 0x12, 0x0f, 0x67, 0x21,
];

/// The Ed25519SigVerify header every registrar voucher instruction starts
/// with (`registrar/src/voucher.rs` `IX_HEADER`): 1 signature, padding 0,
/// sig_off 48, sig_ix 0xFFFF, pk_off 16, pk_ix 0xFFFF, msg_off 112,
/// msg_len 111, msg_ix 0xFFFF.
pub const REGISTRAR_IX_HEADER: [u8; 16] = [
    0x01, 0x00, 0x30, 0x00, 0xff, 0xff, 0x10, 0x00, 0xff, 0xff, 0x70, 0x00, 0x6f, 0x00, 0xff, 0xff,
];

/// Build the 223-byte Ed25519SigVerify instruction data exactly as
/// `registrar/src/voucher.rs::ed25519_instruction_data` does:
/// `[1, 0]`, the 7 offsets, pubkey(32), signature(64), message(111).
pub fn registrar_ix_data(pubkey: &[u8; 32], sig: &[u8; 64], msg: &[u8; 111]) -> Vec<u8> {
    let mut d = vec![1u8, 0];
    for o in [48u16, u16::MAX, 16, u16::MAX, 112, 111, u16::MAX] {
        d.extend_from_slice(&o.to_le_bytes());
    }
    d.extend_from_slice(pubkey);
    d.extend_from_slice(sig);
    d.extend_from_slice(msg);
    d
}

/// A registrar voucher signed by `registrar` (Ed25519 over the raw 111-byte
/// `HDreg` preimage) and its Ed25519SigVerify instruction.
pub fn registrar_voucher(
    registrar: &Keypair,
    authority: &Address,
    p256: &[u8; 33],
    level: u8,
    expiry_slot: u64,
) -> ([u8; 111], [u8; 64], Instruction) {
    let msg = message::registrar_message(authority, p256, level, expiry_slot);
    let sig: [u8; 64] = registrar.sign_message(&msg).into();
    let data = registrar_ix_data(&registrar.pubkey().to_bytes(), &sig, &msg);
    let ix = Instruction {
        program_id: ED25519,
        accounts: vec![],
        data,
    };
    (msg, sig, ix)
}

/// Instruction data written field by field, recording the layout.
pub struct Fields {
    /// The bytes.
    pub data: Vec<u8>,
    layout: Vec<Value>,
}

impl Fields {
    /// Start with the tag byte.
    pub fn new(tag: u8) -> Self {
        let mut f = Self {
            data: vec![],
            layout: vec![],
        };
        f.push("tag", "u8", &[tag], json!(tag));
        f
    }
    fn push(&mut self, name: &str, ty: &str, bytes: &[u8], value: Value) {
        self.layout.push(json!({
            "name": name,
            "type": ty,
            "offset": self.data.len(),
            "size": bytes.len(),
            "value": value,
        }));
        self.data.extend_from_slice(bytes);
    }
    fn u8(mut self, name: &str, v: u8) -> Self {
        self.push(name, "u8", &[v], json!(v));
        self
    }
    fn u16(mut self, name: &str, v: u16) -> Self {
        self.push(name, "u16", &v.to_le_bytes(), json!(v));
        self
    }
    fn u64(mut self, name: &str, v: u64) -> Self {
        self.push(name, "u64", &v.to_le_bytes(), s(v));
        self
    }
    fn i64(mut self, name: &str, v: i64) -> Self {
        self.push(name, "i64", &v.to_le_bytes(), s(v));
        self
    }
    fn key(mut self, name: &str, v: &Address) -> Self {
        self.push(name, "pubkey", v.as_ref(), s(v));
        self
    }
    fn bytes(mut self, name: &str, v: &[u8]) -> Self {
        let ty = format!("[u8;{}]", v.len());
        self.push(name, &ty, v, s(hex(v)));
        self
    }
    fn plan(self, p: &Plan) -> Self {
        self.u64("max_ev_cost", p.max_ev_cost)
            .u64("dig_lamports", p.dig_lamports)
            .u8("split", p.split)
            .u8("solo", p.solo)
            .u8("lease", p.lease)
            .u8("flags", p.flags)
            .i64("window_start", p.window_start)
            .i64("window_end", p.window_end)
    }
    fn entry(self, i: usize, e: &HeartbeatEntry) -> Self {
        self.u8(&format!("entry[{i}].hb_ix"), e.hb_ix)
            .u8(&format!("entry[{i}].hb_sig_index"), e.hb_sig_index)
            .u64(&format!("entry[{i}].counter"), e.counter)
            .u64(&format!("entry[{i}].round_id"), e.round_id)
            .u8(&format!("entry[{i}].lease_rounds"), e.lease_rounds)
            .u8(&format!("entry[{i}]._pad"), 0)
    }
}

/// A PDA derivation.
#[derive(Clone)]
pub struct Pda {
    program: Address,
    program_name: &'static str,
    seeds: Vec<(String, Vec<u8>)>,
}

impl Pda {
    fn new(program: Address, program_name: &'static str) -> Self {
        Self {
            program,
            program_name,
            seeds: vec![],
        }
    }
    fn lit(mut self, v: &str) -> Self {
        self.seeds
            .push((format!("utf8 \"{v}\""), v.as_bytes().to_vec()));
        self
    }
    fn key(mut self, label: &str, v: &Address) -> Self {
        self.seeds
            .push((format!("{label} {v}"), v.as_ref().to_vec()));
        self
    }
    fn u64(mut self, label: &str, v: u64) -> Self {
        self.seeds
            .push((format!("{label} {v} u64 LE"), v.to_le_bytes().to_vec()));
        self
    }
    fn json(&self, expect: &Address) -> Value {
        let seeds: Vec<&[u8]> = self.seeds.iter().map(|(_, b)| b.as_slice()).collect();
        let (a, bump) = Address::find_program_address(&seeds, &self.program);
        assert_eq!(&a, expect, "PDA drift for {:?}", self.seeds);
        json!({
            "program": format!("{} ({})", self.program_name, self.program),
            "seeds": self.seeds.iter().map(|(l, b)| json!({"seed": l, "hex": hex(b)})).collect::<Vec<_>>(),
            "bump": bump,
        })
    }
}

fn hd_pda() -> Pda {
    Pda::new(HD, "heads_down")
}
fn ore_pda() -> Pda {
    Pda::new(ORE, "ORE")
}
fn pda_config() -> Pda {
    hd_pda().lit("config")
}
fn pda_executor() -> Pda {
    hd_pda().lit("executor")
}
fn pda_rig(authority: &Address) -> Pda {
    hd_pda().lit("rig").key("authority", authority)
}
fn pda_seat(mint: &Address) -> Pda {
    hd_pda().lit("seeker").key("sgt_mint", mint)
}
fn pda_shift_log(rig: &Address, shift_id: u64) -> Pda {
    hd_pda()
        .lit("shift")
        .key("rig", rig)
        .u64("shift_id", shift_id)
}
fn pda_program_data() -> Pda {
    Pda::new(LOADER_V3, "BPFLoaderUpgradeable").key("program", &HD)
}
fn pda_automation(authority: &Address) -> Pda {
    ore_pda().lit("automation").key("authority", authority)
}
fn pda_miner(authority: &Address) -> Pda {
    ore_pda().lit("miner").key("authority", authority)
}
fn pda_round(id: u64) -> Pda {
    ore_pda().lit("round").u64("round_id", id)
}
fn pda_ata(owner: &Address, mint: &Address) -> Pda {
    let ata_program = Address::from_str("ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL").unwrap();
    Pda::new(ata_program, "AssociatedToken")
        .key("owner", owner)
        .key("token_program", &token_2022_id())
        .key("mint", mint)
}

/// An account slot of a vector: role name and optional derivation.
struct Slot {
    role: String,
    pda: Option<Pda>,
}

fn slot(role: &str, pda: Option<Pda>) -> Slot {
    Slot {
        role: role.to_string(),
        pda,
    }
}

/// One vector about to be executed.
struct Spec {
    name: &'static str,
    instruction: &'static str,
    auth: &'static str,
    description: &'static str,
    args: Value,
    fields: Fields,
    metas: Vec<AccountMeta>,
    slots: Vec<Slot>,
    /// Companions before the vector instruction: `(role, instruction)`.
    before: Vec<(&'static str, Instruction)>,
    /// What the harness builder produces for the same call (must match).
    harness: Option<Instruction>,
}

fn event_name(tag: u8) -> &'static str {
    match tag {
        ev::tag::RIG_DUG => "RigDug",
        ev::tag::RIG_SKIPPED => "RigSkipped",
        ev::tag::SHIFT_ARMED => "ShiftArmed",
        ev::tag::SHIFT_ENDED => "ShiftEnded",
        ev::tag::SEEKER_VERIFIED => "SeekerVerified",
        ev::tag::RIG_REGISTERED => "RigRegistered",
        ev::tag::RIG_CLOSED => "RigClosed",
        ev::tag::HEARTBEATS_RECORDED => "HeartbeatsRecorded",
        ev::tag::SHIFT_BROKEN => "ShiftBroken",
        ev::tag::SHIFT_ENDED_V2 => "ShiftEndedV2",
        _ => "Unknown",
    }
}

/// `(name, type, offset, size)` for every field of event `tag`, tag byte included.
pub fn event_layout(tag: u8) -> Vec<(&'static str, &'static str, usize, usize)> {
    let mut out = vec![("tag", "u8", 0, 1)];
    let fields: &[(&'static str, &'static str, usize)] = match tag {
        ev::tag::RIG_DUG => &[
            ("rig", "pubkey", 32),
            ("round_id", "u64", 8),
            ("lamports", "u64", 8),
            ("mask", "u32", 4),
            ("ema_ev", "u64", 8),
        ],
        ev::tag::RIG_SKIPPED => &[
            ("rig", "pubkey", 32),
            ("round_id", "u64", 8),
            ("error", "u32", 4),
        ],
        ev::tag::SHIFT_ARMED => &[("rig", "pubkey", 32), ("shift_id", "u64", 8)],
        ev::tag::SHIFT_ENDED => &[
            ("rig", "pubkey", 32),
            ("shift_id", "u64", 8),
            ("dark_rounds", "u64", 8),
            ("rounds_dug", "u64", 8),
            ("lamports", "u64", 8),
            ("reason", "u8", 1),
        ],
        ev::tag::SEEKER_VERIFIED => &[
            ("rig", "pubkey", 32),
            ("sgt_mint", "pubkey", 32),
            ("member_number", "u64", 8),
        ],
        ev::tag::RIG_REGISTERED => &[
            ("rig", "pubkey", 32),
            ("authority", "pubkey", 32),
            ("tier", "u8", 1),
            ("attestation_level", "u8", 1),
        ],
        ev::tag::RIG_CLOSED => &[("rig", "pubkey", 32)],
        ev::tag::HEARTBEATS_RECORDED => &[
            ("rig", "pubkey", 32),
            ("round_id", "u64", 8),
            ("dark_rounds_added", "u64", 8),
        ],
        ev::tag::SHIFT_BROKEN => &[
            ("rig", "pubkey", 32),
            ("shift_id", "u64", 8),
            ("reason", "u8", 1),
        ],
        ev::tag::SHIFT_ENDED_V2 => &[
            ("rig", "pubkey", 32),
            ("shift_id", "u64", 8),
            ("dark_rounds", "u64", 8),
            ("rounds_dug", "u64", 8),
            ("lamports", "u64", 8),
            ("reason", "u8", 1),
            ("start_round", "u64", 8),
            ("end_round", "u64", 8),
            ("mode", "u8", 1),
        ],
        _ => &[],
    };
    let mut off = 1;
    for (n, t, size) in fields {
        out.push((n, t, off, *size));
        off += size;
    }
    out
}

/// Decode `bytes` with [`event_layout`] into `{field: value}`.
pub fn decode_with_layout(bytes: &[u8]) -> Value {
    let tag = bytes[0];
    let layout = event_layout(tag);
    let (_, _, last_off, last_size) = *layout.last().unwrap();
    assert_eq!(last_off + last_size, bytes.len(), "event {tag} length");
    assert_eq!(bytes.len(), ev::LEN[usize::from(tag)], "event {tag} LEN");
    let mut m = serde_json::Map::new();
    for (n, t, off, size) in layout {
        let b = &bytes[off..off + size];
        let v = match t {
            "u8" => json!(b[0]),
            "u32" => json!(u32::from_le_bytes(b.try_into().unwrap())),
            "u64" => s(u64::from_le_bytes(b.try_into().unwrap())),
            "pubkey" => s(Address::new_from_array(b.try_into().unwrap())),
            _ => unreachable!(),
        };
        m.insert(n.to_string(), v);
    }
    Value::Object(m)
}

fn events_json(raw: &[Vec<u8>]) -> Vec<Value> {
    raw.iter()
        .map(|b| {
            json!({
                "tag": b[0],
                "event": event_name(b[0]),
                "hex": hex(b),
                "fields": decode_with_layout(b),
            })
        })
        .collect()
}

fn metas_json(ix: &Instruction, slots: &[Slot]) -> Vec<Value> {
    assert_eq!(ix.accounts.len(), slots.len(), "role count");
    ix.accounts
        .iter()
        .zip(slots)
        .enumerate()
        .map(|(i, (m, sl))| {
            let mut v = json!({
                "index": i,
                "role": sl.role,
                "pubkey": s(m.pubkey),
                "is_signer": m.is_signer,
                "is_writable": m.is_writable,
            });
            if let Some(p) = &sl.pda {
                v["pda"] = p.json(&m.pubkey);
            }
            v
        })
        .collect()
}

fn companion_json(index: usize, role: &str, ix: &Instruction) -> Value {
    json!({
        "index": index,
        "role": role,
        "program_id": s(ix.program_id),
        "data_len": ix.data.len(),
        "data_hex": hex(&ix.data),
    })
}

// ---- the recorder -----------------------------------------------------------

struct Recorder {
    env: Env,
    step: usize,
    scenario: Vec<Value>,
    vectors: Vec<Value>,
    /// First captured sample per event tag: (vector name, bytes).
    samples: BTreeMap<u8, (String, Vec<u8>)>,
}

impl Recorder {
    fn setup(&mut self, what: &str) {
        self.step += 1;
        self.scenario
            .push(json!({"step": self.step, "kind": "setup", "action": what}));
    }

    fn send_setup(&mut self, what: &str, payer: &Keypair, ixs: &[Instruction]) {
        self.setup(what);
        let p = payer.insecure_clone();
        ok(self.env.send_as(&p, ixs, &[]));
    }

    /// Execute `spec` (the vector must succeed) and record it.
    fn vector(&mut self, spec: Spec, payer: &Keypair, signers: &[&Keypair]) -> Vec<Vec<u8>> {
        let ix = Instruction {
            program_id: HD,
            accounts: spec.metas.clone(),
            data: spec.fields.data.clone(),
        };
        if let Some(h) = &spec.harness {
            assert_eq!(
                h.data, ix.data,
                "{}: data differs from the harness",
                spec.name
            );
            assert_eq!(
                h.accounts, ix.accounts,
                "{}: metas differ from the harness",
                spec.name
            );
        }
        let mut tx: Vec<Instruction> = spec.before.iter().map(|(_, i)| i.clone()).collect();
        tx.push(ix.clone());
        let p = payer.insecure_clone();
        let meta = match self.env.send_as(&p, &tx, signers) {
            Ok(m) => m,
            Err(f) => panic!(
                "golden vector {} failed: {:?}\n{}",
                spec.name,
                f.err,
                f.logs.join("\n")
            ),
        };
        let raw = raw_events(&meta.logs);
        for b in &raw {
            self.samples
                .entry(b[0])
                .or_insert_with(|| (spec.name.to_string(), b.clone()));
        }
        self.step += 1;
        self.scenario
            .push(json!({"step": self.step, "kind": "vector", "name": spec.name}));
        let mut transaction: Vec<Value> = spec
            .before
            .iter()
            .enumerate()
            .map(|(i, (role, c))| companion_json(i, role, c))
            .collect();
        transaction.push(json!({
            "index": spec.before.len(),
            "role": "this instruction",
            "program_id": s(HD),
        }));
        self.vectors.push(json!({
            "name": spec.name,
            "tag": spec.fields.data[0],
            "instruction": spec.instruction,
            "auth": spec.auth,
            "description": spec.description,
            "step": self.step,
            "args": spec.args,
            "program_id": s(HD),
            "data_len": ix.data.len(),
            "data_hex": hex(&ix.data),
            "data_layout": spec.fields.layout,
            "accounts": metas_json(&ix, &spec.slots),
            "transaction": {
                "fee_payer": s(payer.pubkey()),
                "instructions": transaction,
            },
            "litesvm": {
                "executed": true,
                "result": "success",
                "events": events_json(&raw),
            },
        }));
        raw
    }
}

// ---- common slots -----------------------------------------------------------

fn dig_slots(n: usize, users: &[&User], round_id: u64) -> Vec<Slot> {
    let mut v = vec![
        slot("cranker", None),
        slot("config", Some(pda_config())),
        slot("executor", Some(pda_executor())),
        slot("ore_board", None),
        slot("ore_config", None),
        slot("ore_round", Some(pda_round(round_id))),
        slot("ore_treasury", None),
        slot("system_program", None),
        slot("ore_program", None),
        slot("ore_entropy_var", None),
        slot("entropy_program", None),
        slot("instructions_sysvar", None),
    ];
    for (i, u) in users.iter().enumerate().take(n) {
        let a = u.pubkey();
        v.push(slot(&format!("rig[{i}]"), Some(pda_rig(&a))));
        v.push(slot(&format!("authority[{i}]"), None));
        v.push(slot(
            &format!("ore_automation[{i}]"),
            Some(pda_automation(&a)),
        ));
        v.push(slot(&format!("ore_miner[{i}]"), Some(pda_miner(&a))));
    }
    v
}

fn user_json(name: &str, u: &User, wallet_seed: u8, p256_note: &str) -> Value {
    json!({
        "name": name,
        "wallet": s(u.pubkey()),
        "wallet_seed": format!("[0x{wallet_seed:02x}; 32] (public test seed)"),
        "p256_private_scalar": p256_note,
        "p256_pubkey_hex": hex(&u.p256()),
        "rig": s(u.rig),
        "ore_automation": s(u.automation()),
        "ore_miner": s(u.miner()),
    })
}

fn heartbeat_args(e: &HeartbeatEntry) -> Value {
    json!({
        "hb_ix": e.hb_ix,
        "hb_sig_index": e.hb_sig_index,
        "counter": s(e.counter),
        "round_id": s(e.round_id),
        "lease_rounds": e.lease_rounds,
    })
}

fn plan_args(p: &Plan) -> Value {
    json!({
        "max_ev_cost": s(p.max_ev_cost),
        "dig_lamports": s(p.dig_lamports),
        "split": p.split,
        "solo": p.solo,
        "lease": p.lease,
        "flags": p.flags,
        "window_start": s(p.window_start),
        "window_end": s(p.window_end),
    })
}

fn onboard_ixs(u: &User, deposit: u64, caps: Caps, plan: &Plan) -> Vec<Instruction> {
    let w = u.pubkey();
    vec![
        ore_automate_default(&w, deposit),
        ix_register_rig(&w, &u.p256(), None),
        ix_set_caps(&w, caps),
        ix_arm_wallet(&w, plan),
    ]
}

/// Keystore-style signature details: (raw RFC 6979 r||s, low-S r||s).
pub fn p256_sign_details(key: &p256::ecdsa::SigningKey, msg: &[u8]) -> ([u8; 64], [u8; 64]) {
    let der: DerSignature = key.sign(msg);
    let sig = p256::ecdsa::Signature::from_der(der.as_bytes()).unwrap();
    let raw: [u8; 64] = sig.to_bytes().as_slice().try_into().unwrap();
    (raw, der_to_low_s_raw(der.as_bytes()).unwrap())
}

// ---- the files ----------------------------------------------------------------

/// Pretty JSON of every golden file, `(file name, contents)`.
pub struct Golden {
    /// Files in `programs/heads-down/vectors/`.
    pub files: Vec<(&'static str, String)>,
}

/// Run the scenarios and render every file.
pub fn generate() -> Golden {
    let (instructions, samples) = instructions_and_samples();
    let events = events_file(samples);
    let messages = messages_file();
    let registrar = registrar_file();
    let render = |v: &Value| serde_json::to_string_pretty(v).unwrap() + "\n";
    Golden {
        files: vec![
            ("instructions.json", render(&instructions)),
            ("messages.json", render(&messages)),
            ("events.json", render(&events)),
            ("registrar.json", render(&registrar)),
        ],
    }
}

fn pinned_json(env: &Env) -> Value {
    json!({
        "ore_program_sha256": "57503f435dac3888571c1b21330994a821d5f891790bde5af93888b7ba9eb3a7 (live mainnet ORE, tests/fixtures/ore.so)",
        "board": {
            "round_id": s(golden::ROUND_ID),
            "start_slot": s(golden::START_SLOT),
            "end_slot": s(golden::END_SLOT),
            "production_cost_ema": s(golden::EMA),
        },
        "treasury_motherlode": s(golden::MOTHERLODE),
        "ema_ev": s(golden::EMA_EV),
        "round": {
            "address": s(env.round),
            "id": s(golden::ROUND_ID),
            "deployed": golden::round_deployed().iter().map(|v| s(*v)).collect::<Vec<_>>(),
        },
        "solo_mask": format!("0x{:07x}", hd::ore::distribution_mask(golden::ROUND_ID)),
        "clock": {"slot": s(golden::START_SLOT + 10), "unix_timestamp": s(T0)},
        "config": {
            "crank_fee": s(CRANK_FEE),
            "executor_fee": s(EXECUTOR_FEE),
            "bury_bps": 0,
        },
        "automation": {
            "strategy": 2,
            "fee": s(EXECUTOR_FEE),
            "amount_per_square": s(TILE_CAP),
            "executor": s(EXECUTOR),
        },
    })
}

#[allow(clippy::too_many_lines)]
fn instructions_and_samples() -> (Value, BTreeMap<u8, (String, Vec<u8>)>) {
    let mut env = Env::build_with(Build::Mainnet, true, false, EnvKeys::golden(), true);
    assert_eq!(env.board_round, golden::ROUND_ID);
    assert_eq!(env.ema_ev(), golden::EMA_EV);
    let round = golden::ROUND_ID;
    let alice = User::with_keys(&mut env, [0xA1; 32], RFC6979_P256_SCALAR);
    let bob = User::with_keys(&mut env, [0xB2; 32], [0x22; 32]);
    let mut carol = User::with_keys(&mut env, [0xC3; 32], [0x33; 32]);
    let dave = User::with_keys(&mut env, [0xD4; 32], [0x44; 32]);
    let erin = User::with_keys(&mut env, [0xE5; 32], [0x55; 32]);
    let frank = User::with_keys(&mut env, [0xF6; 32], [0x66; 32]);
    let grace = User::with_keys(&mut env, [0x97; 32], [0x77; 32]);
    let users = json!([
        user_json(
            "alice",
            &alice,
            0xA1,
            "RFC 6979 A.2.5 test key c9afa9d8...0f6721"
        ),
        user_json("bob", &bob, 0xB2, "[0x22; 32]"),
        user_json(
            "carol",
            &carol,
            0xC3,
            "[0x33; 32] (rotated to [0x34; 32], then [0x35; 32])"
        ),
        user_json("dave", &dave, 0xD4, "[0x44; 32]"),
        user_json("erin", &erin, 0xE5, "[0x55; 32]"),
        user_json("frank", &frank, 0xF6, "[0x66; 32]"),
        user_json("grace", &grace, 0x97, "[0x77; 32]"),
    ]);
    let keys = json!({
        "upgrade_authority": {"pubkey": s(env.upgrade_authority.pubkey()), "seed": "[0x0a; 32] (public test seed)"},
        "cranker": {"pubkey": s(env.cranker.pubkey()), "seed": "[0x0c; 32] (public test seed)"},
        "governance": {"pubkey": s(env.governance.pubkey()), "seed": "[0x06; 32] (public test seed)"},
        "registrar": {"pubkey": s(env.registrar.pubkey()), "seed": "[0x05; 32] (public test seed, the key registrar/src/voucher.rs tests use)"},
    });
    let pinned = pinned_json(&env);
    let mut rec = Recorder {
        env,
        step: 0,
        scenario: vec![],
        vectors: vec![],
        samples: BTreeMap::new(),
    };
    rec.setup("Env::golden: live ORE + entropy bytecode, heads_down at its program id with the upgrade authority written into ProgramData, pinned Board/Treasury/Round, clock (slot 451,700,010, unix 1,790,640,000), Executor PDA funded with 0.01 SOL");

    let ua = rec.env.upgrade_authority.insecure_clone();
    let gov = rec.env.governance.pubkey();
    let registrar = rec.env.registrar.insecure_clone();
    let cranker = rec.env.cranker.insecure_clone();
    let layout_hash = hd::ore::layout_hash();

    // ---- 0 initialize_config ------------------------------------------------
    let f = Fields::new(hd::tag::INITIALIZE_CONFIG)
        .key("governance", &gov)
        .key("registrar", &registrar.pubkey())
        .u64("crank_fee", CRANK_FEE)
        .u64("executor_fee", EXECUTOR_FEE)
        .u16("bury_bps", 0)
        .bytes("ore_layout_hash", &layout_hash);
    let h = ix_initialize_config(
        &ua.pubkey(),
        &gov,
        &registrar.pubkey(),
        CRANK_FEE,
        EXECUTOR_FEE,
        0,
        layout_hash,
    );
    rec.vector(
        Spec {
            name: "initialize_config",
            instruction: "initialize_config",
            auth: "upgrade_authority",
            description: "Create the Config PDA. Signer must be the upgrade authority recorded in this program's ProgramData; ore_layout_hash must equal sha256(ore::LAYOUT_PREIMAGE); crank_fee <= executor_fee; bury_bps <= 10000.",
            args: json!({"governance": s(gov), "registrar": s(registrar.pubkey()), "crank_fee": s(CRANK_FEE), "executor_fee": s(EXECUTOR_FEE), "bury_bps": 0, "ore_layout_hash": hex(&layout_hash)}),
            fields: f,
            metas: h.accounts.clone(),
            slots: vec![
                slot("upgrade_authority", None),
                slot("config", Some(pda_config())),
                slot("program_data", Some(pda_program_data())),
                slot("system_program", None),
            ],
            before: vec![],
            harness: Some(h),
        },
        &ua,
        &[],
    );

    // ---- alice: guest registration, caps, wallet arm, fresh-heartbeat dig ----
    let wa = alice.wallet.insecure_clone();
    rec.send_setup(
        "alice: ORE automate (Discretionary, fee = executor_fee 5000, executor = Executor PDA, amount 100000/square, deposit 0.05 SOL)",
        &wa,
        &[ore_automate_default(&alice.pubkey(), SOL / 20)],
    );
    let f = Fields::new(hd::tag::REGISTER_RIG)
        .bytes("p256_pubkey", &alice.p256())
        .u8("has_attestation", 0);
    let h = ix_register_rig(&alice.pubkey(), &alice.p256(), None);
    rec.vector(
        Spec {
            name: "register_rig_guest",
            instruction: "register_rig",
            auth: "wallet, no attestation",
            description: "Create the Rig PDA with the Keystore P-256 key (SEC1 compressed); attestation_level 0. Emits RigRegistered.",
            args: json!({"authority": s(alice.pubkey()), "p256_pubkey_hex": hex(&alice.p256()), "has_attestation": 0}),
            fields: f,
            metas: h.accounts.clone(),
            slots: vec![
                slot("authority", None),
                slot("rig", Some(pda_rig(&alice.pubkey()))),
                slot("config", Some(pda_config())),
                slot("system_program", None),
            ],
            before: vec![],
            harness: Some(h),
        },
        &wa,
        &[],
    );
    let caps = Caps::standard();
    let f = Fields::new(hd::tag::SET_CAPS)
        .u64("cap_week", caps.week)
        .u64("cap_shift", caps.shift)
        .u64("cap_round", caps.round)
        .u64("cap_max_cost", caps.max_cost)
        .i64("caps_expiry_ts", caps.expiry);
    let h = ix_set_caps(&alice.pubkey(), caps);
    rec.vector(
        Spec {
            name: "set_caps",
            instruction: "set_caps",
            auth: "wallet",
            description: "Wallet-signed spend caps (the only instruction that can raise limits); clamps plan_max_ev_cost and plan_dig_lamports down.",
            args: json!({"cap_week": s(caps.week), "cap_shift": s(caps.shift), "cap_round": s(caps.round), "cap_max_cost": s(caps.max_cost), "caps_expiry_ts": s(caps.expiry)}),
            fields: f,
            metas: h.accounts.clone(),
            slots: vec![slot("authority", None), slot("rig", Some(pda_rig(&alice.pubkey())))],
            before: vec![],
            harness: Some(h),
        },
        &wa,
        &[],
    );
    let plan = standard_plan();
    let f = Fields::new(hd::tag::ARM_SHIFT).u8("mode", 0).plan(&plan);
    let h = ix_arm_wallet(&alice.pubkey(), &plan);
    rec.vector(
        Spec {
            name: "arm_shift_wallet",
            instruction: "arm_shift",
            auth: "wallet (mode 0)",
            description: "Arm shift 1 with the wallet's signature. Emits ShiftArmed.",
            args: json!({"mode": 0, "plan": plan_args(&plan)}),
            fields: f,
            metas: h.accounts.clone(),
            slots: vec![
                slot("rig", Some(pda_rig(&alice.pubkey()))),
                slot("authority", None),
                slot("ore_board", None),
            ],
            before: vec![],
            harness: Some(h),
        },
        &wa,
        &[],
    );
    // Fresh-heartbeat dig.
    let mut a_counter = 0u64;
    a_counter += 1;
    let hb = alice.heartbeat_with(a_counter, 1, round, 3);
    let e = entry_for(&hb, 1, 0);
    let f = Fields::new(hd::tag::DIG).u8("n", 1).entry(0, &e);
    let h = ix_dig(&cranker.pubkey(), &rec.env.round, &[DigRig::new(&alice, e)]);
    rec.vector(
        Spec {
            name: "dig_fresh_heartbeat",
            instruction: "dig",
            auth: "cranker + P-256 HEARTBEAT (hb_ix = 1)",
            description: "One rig, fresh HEARTBEAT (counter 1, shift 1, round 422700, lease 3) verified by the secp256r1 precompile at top-level index 1. Gate open (ema_ev 653163071 <= 700000000), 10 least-crowded split squares x 100000 lamports, Automation debited tiles + 5000 fee, cranker reimbursed 4000. Emits RigDug.",
            args: json!({"n": 1, "entries": [heartbeat_args(&e)]}),
            fields: f,
            metas: h.accounts.clone(),
            slots: dig_slots(1, &[&alice], round),
            before: vec![
                ("compute_budget: SetComputeUnitLimit(1400000)", compute_limit(1_400_000)),
                ("secp256r1: HEARTBEAT(alice)", secp_ix_for(&[hb])),
            ],
            harness: Some(h),
        },
        &cranker,
        &[],
    );
    // Wallet BREAK (manual) and the authority's end_shift.
    let f = Fields::new(hd::tag::BREAK_SHIFT)
        .u8("mode", 0)
        .u8("reason", break_reason::MANUAL);
    let h = ix_break_wallet(&alice.pubkey(), break_reason::MANUAL);
    rec.vector(
        Spec {
            name: "break_shift_wallet",
            instruction: "break_shift",
            auth: "wallet (mode 0)",
            description: "Wallet BREAK with reason 6 (manual): Down -> Broken. Emits ShiftBroken.",
            args: json!({"mode": 0, "reason": break_reason::MANUAL}),
            fields: f,
            metas: h.accounts.clone(),
            slots: vec![
                slot("rig", Some(pda_rig(&alice.pubkey()))),
                slot("authority", None),
            ],
            before: vec![],
            harness: Some(h),
        },
        &wa,
        &[],
    );
    let h = ix_end_shift(&alice.pubkey(), &alice.rig, 1);
    rec.vector(
        Spec {
            name: "end_shift_authority",
            instruction: "end_shift",
            auth: "rig authority (any time)",
            description: "The authority seals shift 1 into ShiftLog [\"shift\", rig, 1] (reason = the stored BREAK reason 6) and pays its rent. Emits ShiftEnded then ShiftEndedV2.",
            args: json!({"caller": s(alice.pubkey()), "rig": s(alice.rig), "shift_id": "1"}),
            fields: Fields::new(hd::tag::END_SHIFT),
            metas: h.accounts.clone(),
            slots: vec![
                slot("caller", None),
                slot("rig", Some(pda_rig(&alice.pubkey()))),
                slot("shift_log", Some(pda_shift_log(&alice.rig, 1))),
                slot("ore_board", None),
                slot("system_program", None),
            ],
            before: vec![],
            harness: Some(h),
        },
        &wa,
        &[],
    );
    // P-256 PLAN arm (shift 2).
    a_counter += 1;
    let (d, sg) = plan_signature(&alice, a_counter, &plan);
    let f = Fields::new(hd::tag::ARM_SHIFT)
        .u8("mode", 1)
        .plan(&plan)
        .u64("counter", a_counter)
        .u8("p256_ix", 0)
        .u8("p256_sig_index", 0);
    let h = ix_arm_p256(&alice.pubkey(), &plan, a_counter, 0, 0);
    rec.vector(
        Spec {
            name: "arm_shift_p256",
            instruction: "arm_shift",
            auth: "P-256 PLAN (mode 1), no wallet signature",
            description: "The phone key arms shift 2 inside the wallet's caps; the PLAN (counter 2) is verified by the secp256r1 precompile at index 0. The authority account is passed unsigned (it must still equal rig.authority). Fee payer = cranker. Emits ShiftArmed.",
            args: json!({"mode": 1, "plan": plan_args(&plan), "counter": s(a_counter), "p256_ix": 0, "p256_sig_index": 0}),
            fields: f,
            metas: h.accounts.clone(),
            slots: vec![
                slot("rig", Some(pda_rig(&alice.pubkey()))),
                slot("authority", None),
                slot("ore_board", None),
                slot("instructions_sysvar", None),
            ],
            before: vec![("secp256r1: PLAN(alice)", secp_ix(&[(sg, alice.p256(), d.to_vec())]))],
            harness: Some(h),
        },
        &cranker,
        &[],
    );
    // record_heartbeats (lease 1).
    a_counter += 1;
    let hb = alice.heartbeat_with(a_counter, 2, round, 1);
    let e = entry_for(&hb, 0, 0);
    let f = Fields::new(hd::tag::RECORD_HEARTBEATS)
        .u8("n", 1)
        .entry(0, &e);
    let h = ix_record(&[(alice.rig, e)]);
    rec.vector(
        Spec {
            name: "record_heartbeats",
            instruction: "record_heartbeats",
            auth: "anyone + P-256 HEARTBEAT (hb_ix = 0)",
            description: "Record a HEARTBEAT (counter 3, shift 2, round 422700, lease 1) without deploying: Armed -> Down, lease [422700, 422700], 1 dark round. Emits HeartbeatsRecorded.",
            args: json!({"n": 1, "entries": [heartbeat_args(&e)]}),
            fields: f,
            metas: h.accounts.clone(),
            slots: vec![
                slot("ore_board", None),
                slot("instructions_sysvar", None),
                slot("rig[0]", Some(pda_rig(&alice.pubkey()))),
            ],
            before: vec![("secp256r1: HEARTBEAT(alice)", secp_ix_for(&[hb]))],
            harness: Some(h),
        },
        &cranker,
        &[],
    );
    // P-256 BREAK (pickup) -> Cooling.
    a_counter += 1;
    let (d, sg) = signal_signature(&alice, kind::BREAK, a_counter, 2, break_reason::PICKUP);
    let f = Fields::new(hd::tag::BREAK_SHIFT)
        .u8("mode", 1)
        .u8("reason", break_reason::PICKUP)
        .u64("counter", a_counter)
        .u8("p256_ix", 0)
        .u8("p256_sig_index", 0);
    let h = ix_break_p256(&alice.pubkey(), break_reason::PICKUP, a_counter, 0, 0);
    rec.vector(
        Spec {
            name: "break_shift_p256",
            instruction: "break_shift",
            auth: "P-256 BREAK (mode 1)",
            description: "Phone BREAK with reason 1 (pickup, counter 4, shift 2): Down -> Cooling (a fresh heartbeat would resume). Emits ShiftBroken.",
            args: json!({"mode": 1, "reason": break_reason::PICKUP, "counter": s(a_counter), "p256_ix": 0, "p256_sig_index": 0}),
            fields: f,
            metas: h.accounts.clone(),
            slots: vec![
                slot("rig", Some(pda_rig(&alice.pubkey()))),
                slot("authority", None),
                slot("instructions_sysvar", None),
            ],
            before: vec![("secp256r1: BREAK(alice)", secp_ix(&[(sg, alice.p256(), d.to_vec())]))],
            harness: Some(h),
        },
        &cranker,
        &[],
    );
    // P-256 FREEZE -> Frozen (interrupts the open shift).
    a_counter += 1;
    let (d, sg) = signal_signature(&alice, kind::FREEZE, a_counter, 2, break_reason::FREEZE);
    let f = Fields::new(hd::tag::FREEZE_RIG)
        .u8("mode", 1)
        .u8("reason", break_reason::FREEZE)
        .u64("counter", a_counter)
        .u8("p256_ix", 0)
        .u8("p256_sig_index", 0);
    let h = ix_freeze_p256(&alice.pubkey(), break_reason::FREEZE, a_counter, 0, 0);
    rec.vector(
        Spec {
            name: "freeze_rig_p256",
            instruction: "freeze_rig",
            auth: "P-256 FREEZE (mode 1)",
            description: "Phone FREEZE (counter 5, shift 2, reason 3): Cooling -> Frozen; the open shift records break_reason 3. Emits ShiftBroken(3).",
            args: json!({"mode": 1, "reason": break_reason::FREEZE, "counter": s(a_counter), "p256_ix": 0, "p256_sig_index": 0}),
            fields: f,
            metas: h.accounts.clone(),
            slots: vec![
                slot("rig", Some(pda_rig(&alice.pubkey()))),
                slot("authority", None),
                slot("instructions_sysvar", None),
            ],
            before: vec![("secp256r1: FREEZE(alice)", secp_ix(&[(sg, alice.p256(), d.to_vec())]))],
            harness: Some(h),
        },
        &cranker,
        &[],
    );
    let h = ix_unfreeze(&alice.pubkey(), true);
    rec.vector(
        Spec {
            name: "unfreeze_rig",
            instruction: "unfreeze_rig",
            auth: "wallet only",
            description: "Frozen -> Broken because shift 2 is still open (Frozen -> Idle when no shift is open).",
            args: json!({}),
            fields: Fields::new(hd::tag::UNFREEZE_RIG),
            metas: h.accounts.clone(),
            slots: vec![slot("rig", Some(pda_rig(&alice.pubkey()))), slot("authority", None)],
            before: vec![],
            harness: Some(h),
        },
        &wa,
        &[],
    );

    // ---- bob: lease reuse ----------------------------------------------------
    let wb = bob.wallet.insecure_clone();
    rec.send_setup(
        "bob: ORE automate + register_rig + set_caps + arm_shift (wallet), one transaction",
        &wb,
        &onboard_ixs(&bob, SOL / 20, caps, &plan),
    );
    let hb_b = bob.heartbeat_with(1, 1, round, 3);
    rec.send_setup(
        "bob: record_heartbeats (counter 1, shift 1, round 422700, lease 3) -> lease [422700, 422702]",
        &cranker,
        &[secp_ix_for(&[hb_b]), ix_record(&[(bob.rig, entry_for(&hb_b, 0, 0))])],
    );
    let e = reuse_lease();
    let f = Fields::new(hd::tag::DIG).u8("n", 1).entry(0, &e);
    let h = ix_dig(&cranker.pubkey(), &rec.env.round, &[DigRig::new(&bob, e)]);
    rec.vector(
        Spec {
            name: "dig_reuse_lease",
            instruction: "dig",
            auth: "cranker, lease reuse (hb_ix = 0xFF)",
            description: "Dig bob on the lease granted by the earlier record_heartbeats; no precompile in the transaction. Entry fields after hb_ix are ignored (written as 0). Emits RigDug.",
            args: json!({"n": 1, "entries": [heartbeat_args(&e)]}),
            fields: f,
            metas: h.accounts.clone(),
            slots: dig_slots(1, &[&bob], round),
            before: vec![("compute_budget: SetComputeUnitLimit(1400000)", compute_limit(1_400_000))],
            harness: Some(h),
        },
        &cranker,
        &[],
    );

    // ---- dave + erin: a two-rig batch sharing one precompile instruction ----
    for u in [&dave, &erin] {
        let w = u.wallet.insecure_clone();
        rec.send_setup(
            "dave / erin: ORE automate + register_rig + set_caps + arm_shift (wallet)",
            &w,
            &onboard_ixs(u, SOL / 20, caps, &plan),
        );
    }
    let hd_ = dave.heartbeat_with(1, 1, round, 2);
    let he = erin.heartbeat_with(1, 1, round, 3);
    let (e0, e1) = (entry_for(&hd_, 1, 0), entry_for(&he, 1, 1));
    let f = Fields::new(hd::tag::DIG)
        .u8("n", 2)
        .entry(0, &e0)
        .entry(1, &e1);
    let h = ix_dig(
        &cranker.pubkey(),
        &rec.env.round,
        &[DigRig::new(&dave, e0), DigRig::new(&erin, e1)],
    );
    rec.vector(
        Spec {
            name: "dig_batch_two_rigs",
            instruction: "dig",
            auth: "cranker + two P-256 HEARTBEATs in one precompile instruction (hb_ix = 1, hb_sig_index 0 and 1)",
            description: "Two rigs, 12 + 4n = 20 accounts; one Secp256r1SigVerify instruction with 2 signatures. Each rig digs 10 split squares. Emits one RigDug per rig, in account order.",
            args: json!({"n": 2, "entries": [heartbeat_args(&e0), heartbeat_args(&e1)]}),
            fields: f,
            metas: h.accounts.clone(),
            slots: dig_slots(2, &[&dave, &erin], round),
            before: vec![
                ("compute_budget: SetComputeUnitLimit(1400000)", compute_limit(1_400_000)),
                ("secp256r1: HEARTBEAT(dave), HEARTBEAT(erin)", secp_ix_for(&[hd_, he])),
            ],
            harness: Some(h),
        },
        &cranker,
        &[],
    );

    // ---- carol: registrar-attested registration and key rotation -------------
    let wc = carol.wallet.insecure_clone();
    let expiry = golden::START_SLOT + 6_480_000;
    let (_, _, ed) = registrar_voucher(&registrar, &carol.pubkey(), &carol.p256(), 2, expiry);
    let att = AttestationArg {
        ix: 0,
        sig: 0,
        level: 2,
        expiry_slot: expiry,
    };
    let f = Fields::new(hd::tag::REGISTER_RIG)
        .bytes("p256_pubkey", &carol.p256())
        .u8("has_attestation", 1)
        .u8("ed25519_ix", 0)
        .u8("ed25519_sig_index", 0)
        .u8("level", 2)
        .u64("expiry_slot", expiry);
    let h = ix_register_rig(&carol.pubkey(), &carol.p256(), Some(att));
    rec.vector(
        Spec {
            name: "register_rig_attested",
            instruction: "register_rig",
            auth: "wallet + registrar Ed25519 voucher (level 2 StrongBox)",
            description: "Register with the registrar's Ed25519 voucher over the 111-byte HDreg preimage, carried by the 223-byte Ed25519SigVerify instruction at index 0 (registrar/src/voucher.rs format). Sets attestation_level 2 and attestation_expiry_slot. Emits RigRegistered(level 2).",
            args: json!({"authority": s(carol.pubkey()), "p256_pubkey_hex": hex(&carol.p256()), "has_attestation": 1, "ed25519_ix": 0, "ed25519_sig_index": 0, "level": 2, "expiry_slot": s(expiry)}),
            fields: f,
            metas: h.accounts.clone(),
            slots: vec![
                slot("authority", None),
                slot("rig", Some(pda_rig(&carol.pubkey()))),
                slot("config", Some(pda_config())),
                slot("system_program", None),
                slot("instructions_sysvar", None),
            ],
            before: vec![("ed25519: registrar voucher(carol, level 2)", ed)],
            harness: Some(h),
        },
        &wc,
        &[],
    );
    carol.key = p256::ecdsa::SigningKey::from_slice(&[0x34; 32]).unwrap();
    let f = Fields::new(hd::tag::ROTATE_KEY)
        .bytes("p256_pubkey", &carol.p256())
        .u8("has_attestation", 0);
    let h = ix_rotate_key(&carol.pubkey(), &carol.p256(), None);
    rec.vector(
        Spec {
            name: "rotate_key_unattested",
            instruction: "rotate_key",
            auth: "wallet, no attestation",
            description: "Replace the P-256 key (reinstall / new phone); without a voucher attestation_level resets to 0. hb_counter is kept.",
            args: json!({"authority": s(carol.pubkey()), "p256_pubkey_hex": hex(&carol.p256()), "has_attestation": 0}),
            fields: f,
            metas: h.accounts.clone(),
            slots: vec![
                slot("authority", None),
                slot("rig", Some(pda_rig(&carol.pubkey()))),
                slot("config", Some(pda_config())),
            ],
            before: vec![],
            harness: Some(h),
        },
        &wc,
        &[],
    );
    carol.key = p256::ecdsa::SigningKey::from_slice(&[0x35; 32]).unwrap();
    let (_, _, ed) = registrar_voucher(&registrar, &carol.pubkey(), &carol.p256(), 1, expiry);
    let att = AttestationArg {
        ix: 0,
        sig: 0,
        level: 1,
        expiry_slot: expiry,
    };
    let f = Fields::new(hd::tag::ROTATE_KEY)
        .bytes("p256_pubkey", &carol.p256())
        .u8("has_attestation", 1)
        .u8("ed25519_ix", 0)
        .u8("ed25519_sig_index", 0)
        .u8("level", 1)
        .u64("expiry_slot", expiry);
    let h = ix_rotate_key(&carol.pubkey(), &carol.p256(), Some(att));
    rec.vector(
        Spec {
            name: "rotate_key_attested",
            instruction: "rotate_key",
            auth: "wallet + registrar Ed25519 voucher (level 1 TEE)",
            description: "Rotate to a new key with a fresh level-1 voucher (Ed25519SigVerify at index 0): attestation_level 1.",
            args: json!({"authority": s(carol.pubkey()), "p256_pubkey_hex": hex(&carol.p256()), "has_attestation": 1, "ed25519_ix": 0, "ed25519_sig_index": 0, "level": 1, "expiry_slot": s(expiry)}),
            fields: f,
            metas: h.accounts.clone(),
            slots: vec![
                slot("authority", None),
                slot("rig", Some(pda_rig(&carol.pubkey()))),
                slot("config", Some(pda_config())),
                slot("instructions_sysvar", None),
            ],
            before: vec![("ed25519: registrar voucher(carol, level 1)", ed)],
            harness: Some(h),
        },
        &wc,
        &[],
    );

    // ---- frank / grace: Seeker verification with a real mainnet SGT ---------
    let manifest: Value = serde_json::from_str(
        &std::fs::read_to_string(sgt_fixtures().join("manifest.json")).unwrap(),
    )
    .unwrap();
    let sgt = &manifest["sgts"][0];
    assert_eq!(sgt["label"], "member-20");
    let mint = Address::from_str(sgt["mint"].as_str().unwrap()).unwrap();
    let mut mint_acc = load_fixture_account(&sgt_fixtures().join("member-20/mint.json"));
    mint_acc.rent_epoch = 0;
    rec.env.svm.set_account(mint, mint_acc).unwrap();
    let wf = frank.wallet.insecure_clone();
    let ata_f = sgt_verify::testkit::associated_token_address(&frank.pubkey(), &mint);
    rec.setup("frank: mainnet SGT member #20 mint (crates/sgt-verify/fixtures) + a frozen Token-2022 ATA holding it for frank (bytes as Solana Mobile leaves them after a move)");
    rec.env
        .svm
        .set_account(
            ata_f,
            Account {
                lamports: SOL / 100,
                data: sgt_verify::testkit::sgt_token_account_bytes(&mint, &frank.pubkey(), true),
                owner: token_2022_id(),
                executable: false,
                rent_epoch: 0,
            },
        )
        .unwrap();
    rec.send_setup(
        "frank: register_rig (guest)",
        &wf,
        &[ix_register_rig(&frank.pubkey(), &frank.p256(), None)],
    );
    let seeker_slots = |u: &User, previous: Option<&User>| {
        let mut v = vec![
            slot("authority", None),
            slot("rig", Some(pda_rig(&u.pubkey()))),
            slot("seeker_seat", Some(pda_seat(&mint))),
            slot("sgt_token_account", Some(pda_ata(&u.pubkey(), &mint))),
            slot("sgt_mint", None),
            slot("system_program", None),
        ];
        if let Some(p) = previous {
            v.push(slot("previous_rig", Some(pda_rig(&p.pubkey()))));
        }
        v
    };
    let h = ix_verify_seeker(&frank.pubkey(), &ata_f, &mint, None);
    rec.vector(
        Spec {
            name: "verify_seeker",
            instruction: "verify_seeker",
            auth: "wallet (SGT holder)",
            description: "In-program SGT verification (mainnet anchors) of member #20: creates SeekerSeat [\"seeker\", mint] -> this rig, rig.tier = 1. Emits SeekerVerified.",
            args: json!({"authority": s(frank.pubkey()), "sgt_mint": s(mint), "sgt_token_account": s(ata_f)}),
            fields: Fields::new(hd::tag::VERIFY_SEEKER),
            metas: h.accounts.clone(),
            slots: seeker_slots(&frank, None),
            before: vec![],
            harness: Some(h),
        },
        &wf,
        &[],
    );
    let wg = grace.wallet.insecure_clone();
    let ata_g = sgt_verify::testkit::associated_token_address(&grace.pubkey(), &mint);
    rec.setup("SGT #20 moves frank -> grace: frank's token account amount set to 0, grace gets a frozen ATA holding it");
    let mut old = rec.env.account(&ata_f);
    old.data[64..72].copy_from_slice(&0u64.to_le_bytes());
    rec.env.svm.set_account(ata_f, old).unwrap();
    rec.env
        .svm
        .set_account(
            ata_g,
            Account {
                lamports: SOL / 100,
                data: sgt_verify::testkit::sgt_token_account_bytes(&mint, &grace.pubkey(), true),
                owner: token_2022_id(),
                executable: false,
                rent_epoch: 0,
            },
        )
        .unwrap();
    rec.send_setup(
        "grace: register_rig (guest)",
        &wg,
        &[ix_register_rig(&grace.pubkey(), &grace.p256(), None)],
    );
    let h = ix_verify_seeker(&grace.pubkey(), &ata_g, &mint, Some(frank.rig));
    rec.vector(
        Spec {
            name: "verify_seeker_repoint",
            instruction: "verify_seeker",
            auth: "wallet (new SGT holder) + previous rig",
            description: "The seat points at frank's rig, so grace must pass it (writable) as account 6: the seat is re-pointed to grace's rig and frank's rig drops to tier 0. Emits SeekerVerified.",
            args: json!({"authority": s(grace.pubkey()), "sgt_mint": s(mint), "sgt_token_account": s(ata_g), "previous_rig": s(frank.rig)}),
            fields: Fields::new(hd::tag::VERIFY_SEEKER),
            metas: h.accounts.clone(),
            slots: seeker_slots(&grace, Some(&frank)),
            before: vec![],
            harness: Some(h),
        },
        &wg,
        &[],
    );
    let h = ix_close_rig(&grace.pubkey(), Some(seat_pda(&mint)));
    rec.vector(
        Spec {
            name: "close_rig_seeker",
            instruction: "close_rig",
            auth: "wallet",
            description: "Close a Seeker-tier Idle rig together with its seat (the seat still points here); all rent to the authority. Emits RigClosed.",
            args: json!({"authority": s(grace.pubkey()), "seeker_seat": s(seat_pda(&mint))}),
            fields: Fields::new(hd::tag::CLOSE_RIG),
            metas: h.accounts.clone(),
            slots: vec![
                slot("authority", None),
                slot("rig", Some(pda_rig(&grace.pubkey()))),
                slot("seeker_seat", Some(pda_seat(&mint))),
            ],
            before: vec![],
            harness: Some(h),
        },
        &wg,
        &[],
    );

    // ---- alice: permissionless end_shift, wallet freeze, close ----------------
    rec.setup("clock -> window_end + 1 (unix 1,790,668,801); Board.round_id -> 422701 so alice's lease [422700, 422700] has expired");
    let end = plan.window_end + 1;
    let slot_now = rec.env.slot;
    rec.env.set_clock(slot_now, end);
    rec.env.poke_u64(&BOARD, 8, round + 1);
    let h = ix_end_shift(&cranker.pubkey(), &alice.rig, 2);
    rec.vector(
        Spec {
            name: "end_shift_permissionless",
            instruction: "end_shift",
            auth: "anyone, after plan_window_end_ts and lease_to_round < Board.round_id",
            description: "The cranker seals alice's shift 2 (Broken, stored reason 3 freeze) and pays the ShiftLog rent. Emits ShiftEnded then ShiftEndedV2.",
            args: json!({"caller": s(cranker.pubkey()), "rig": s(alice.rig), "shift_id": "2"}),
            fields: Fields::new(hd::tag::END_SHIFT),
            metas: h.accounts.clone(),
            slots: vec![
                slot("caller", None),
                slot("rig", Some(pda_rig(&alice.pubkey()))),
                slot("shift_log", Some(pda_shift_log(&alice.rig, 2))),
                slot("ore_board", None),
                slot("system_program", None),
            ],
            before: vec![],
            harness: Some(h),
        },
        &cranker,
        &[],
    );
    let f = Fields::new(hd::tag::FREEZE_RIG)
        .u8("mode", 0)
        .u8("reason", break_reason::FREEZE);
    let h = ix_freeze_wallet(&alice.pubkey());
    rec.vector(
        Spec {
            name: "freeze_rig_wallet",
            instruction: "freeze_rig",
            auth: "wallet (mode 0)",
            description: "Wallet FREEZE of an Idle rig: -> Frozen; no shift is open, so no event.",
            args: json!({"mode": 0, "reason": break_reason::FREEZE}),
            fields: f,
            metas: h.accounts.clone(),
            slots: vec![
                slot("rig", Some(pda_rig(&alice.pubkey()))),
                slot("authority", None),
            ],
            before: vec![],
            harness: Some(h),
        },
        &wa,
        &[],
    );
    let h = ix_close_rig(&alice.pubkey(), None);
    rec.vector(
        Spec {
            name: "close_rig_guest",
            instruction: "close_rig",
            auth: "wallet",
            description: "Close a guest rig (state Idle or Frozen; here Frozen); rent to the authority. Emits RigClosed.",
            args: json!({"authority": s(alice.pubkey())}),
            fields: Fields::new(hd::tag::CLOSE_RIG),
            metas: h.accounts.clone(),
            slots: vec![slot("authority", None), slot("rig", Some(pda_rig(&alice.pubkey())))],
            before: vec![],
            harness: Some(h),
        },
        &wa,
        &[],
    );

    // ---- governance ------------------------------------------------------------
    let govk = rec.env.governance.insecure_clone();
    let f = Fields::new(hd::tag::PROPOSE_CONFIG)
        .key("registrar", &registrar.pubkey())
        .u64("crank_fee", 4_500)
        .u16("bury_bps", 250)
        .u8("paused", 0);
    let h = ix_propose(&gov, &registrar.pubkey(), 4_500, 250, 0);
    rec.vector(
        Spec {
            name: "propose_config",
            instruction: "propose_config",
            auth: "governance",
            description: "Propose crank_fee 4500 (<= executor_fee), bury_bps 250, paused 0: pending_* with pending_eta_slot = slot + 864000. (paused = 1 would also pause immediately.)",
            args: json!({"registrar": s(registrar.pubkey()), "crank_fee": "4500", "bury_bps": 250, "paused": 0}),
            fields: f,
            metas: h.accounts.clone(),
            slots: vec![slot("governance", None), slot("config", Some(pda_config()))],
            before: vec![],
            harness: Some(h),
        },
        &govk,
        &[],
    );
    rec.setup("clock slot += 864000 (TIMELOCK_SLOTS)");
    let (slot_now, now) = (rec.env.slot, rec.env.now);
    rec.env
        .set_clock(slot_now + hd::instructions::governance::TIMELOCK_SLOTS, now);
    let h = ix_apply();
    rec.vector(
        Spec {
            name: "apply_config",
            instruction: "apply_config",
            auth: "anyone, once slot >= pending_eta_slot",
            description: "Apply the pending proposal after the timelock.",
            args: json!({}),
            fields: Fields::new(hd::tag::APPLY_CONFIG),
            metas: h.accounts.clone(),
            slots: vec![slot("config", Some(pda_config()))],
            before: vec![],
            harness: Some(h),
        },
        &cranker,
        &[],
    );
    let c = rec.env.config();
    assert_eq!((c.crank_fee.get(), c.bury_bps.get()), (4_500, 250));

    // Every tag appears.
    let tags: std::collections::BTreeSet<u8> = rec
        .vectors
        .iter()
        .map(|v| v["tag"].as_u64().unwrap() as u8)
        .collect();
    assert_eq!(tags.len(), 15, "every instruction tag has a vector");

    let doc = json!({
        "format": "heads-down/golden-instructions",
        "interface_version": "1.1",
        "generated_by": "programs/heads-down/tests/src/vectors.rs (HD_WRITE_VECTORS=1 cargo +1.97.1 test -p heads-down-tests --test vectors)",
        "notes": [
            "Every vector below was executed, in the order of `scenario`, on the LiteSVM fork of live mainnet ORE with the mainnet heads_down build and signature verification on; `litesvm.result` is what happened.",
            "All integers little-endian. u64/i64 values are decimal strings. `data_layout` gives every field's offset and size inside the instruction data (tag at offset 0).",
            "`accounts` is the exact ordered AccountMeta list the program accepted; `pda` gives the seeds and program each derived address comes from (bump = canonical find_program_address bump).",
            "`transaction.instructions` is the whole transaction: companion instructions (compute budget, Secp256r1SigVerify, Ed25519SigVerify) are given in full so hb_ix / p256_ix / ed25519_ix are real top-level indices.",
            "Keys are fixed public test seeds (never real keys). The ORE Board/Treasury/Round are pinned (see `pinned_fork`) so the output is independent of when the fixtures were fetched."
        ],
        "program_id": s(HD),
        "program_id_hex": hex(HD.as_ref()),
        "constants": {
            "config": s(CONFIG),
            "executor": s(EXECUTOR),
            "program_data": s(Env::program_data()),
            "ore_program": s(ORE),
            "ore_board": s(BOARD),
            "ore_config": s(ORE_CONFIG),
            "ore_treasury": s(TREASURY),
            "ore_entropy_var": s(VAR),
            "entropy_program": s(ENTROPY),
            "system_program": s(SYSTEM),
            "instructions_sysvar": s(ix_sysvar_id()),
            "secp256r1_program": s(secp256r1_id()),
            "ed25519_program": s(ED25519),
            "compute_budget_program": s(compute_budget_id()),
            "token_2022_program": s(token_2022_id()),
        },
        "pinned_fork": pinned,
        "keys": keys,
        "users": users,
        "scenario": rec.scenario,
        "instructions": rec.vectors,
    });
    (doc, rec.samples)
}

// ---- events.json ----------------------------------------------------------------

/// A fresh pinned fork with one onboarded rig (wallet seed `seed`).
fn skip_env(seed: u8, deposit: u64, caps: Caps, plan: &Plan) -> (Env, User) {
    let mut env = Env::golden(Build::Mainnet);
    let u = User::with_keys(&mut env, [seed; 32], RFC6979_P256_SCALAR);
    let w = u.wallet.insecure_clone();
    ok(env.send_as(&w, &onboard_ixs(&u, deposit, caps, plan), &[]));
    (env, u)
}

/// Dig `u` once with a fresh heartbeat; the RigSkipped bytes for it.
fn skip_bytes(env: &mut Env, u: &mut User, round: u64) -> Vec<u8> {
    let shift = env.rig(&u.rig).shift_id.get();
    let hb = u.heartbeat(shift, round, 3);
    let meta = ok(env.dig_with(&[hb], &[DigRig::new(u, entry_for(&hb, 1, 0))]));
    let raw = raw_events(&meta.logs);
    assert_eq!(raw.len(), 1);
    assert_eq!(raw[0][0], ev::tag::RIG_SKIPPED, "{:?}", events(&meta.logs));
    raw[0].clone()
}

#[allow(clippy::too_many_lines)]
fn skip_samples() -> Vec<(u32, &'static str, &'static str, Vec<u8>)> {
    let caps = Caps::standard();
    let plan = standard_plan();
    let round = golden::ROUND_ID;
    let mut out = vec![];
    let mut push = |code: u32, name: &'static str, how: &'static str, b: Vec<u8>| {
        assert_eq!(
            u32::from_le_bytes(b[41..45].try_into().unwrap()),
            code,
            "{name}"
        );
        out.push((code, name, how, b));
    };

    // 1 CostGate
    let mut p = plan;
    p.max_ev_cost = golden::EMA_EV - 1;
    let (mut env, mut u) = skip_env(0x31, SOL / 20, caps, &p);
    push(
        1,
        "CostGate",
        "plan max_ev_cost = ema_ev - 1",
        skip_bytes(&mut env, &mut u, round),
    );
    // 2 InvalidExecutor (Automation revoked)
    let (mut env, mut u) = skip_env(0x32, SOL / 20, caps, &plan);
    let w = u.wallet.insecure_clone();
    ok(env.send_as(
        &w,
        &[ore_automate(&w.pubkey(), &SYSTEM, 0, 0, 0, 0, 0, 0)],
        &[],
    ));
    push(
        2,
        "InvalidExecutor",
        "the user revoked the Automation (ORE automate with executor = default)",
        skip_bytes(&mut env, &mut u, round),
    );
    // 6 InvalidHeartbeat (future round)
    let (mut env, mut u) = skip_env(0x33, SOL / 20, caps, &plan);
    push(
        6,
        "InvalidHeartbeat",
        "heartbeat round_id = Board.round_id + 1",
        skip_bytes(&mut env, &mut u, round + 1),
    );
    // 7 StaleHeartbeat
    let (mut env, mut u) = skip_env(0x34, SOL / 20, caps, &plan);
    let hb = u.heartbeat(1, round, 1);
    ok(env.send(
        &[
            secp_ix_for(&[hb]),
            ix_record(&[(u.rig, entry_for(&hb, 0, 0))]),
        ],
        &[],
    ));
    let hb2 = u.heartbeat_with(1, 1, round, 3); // counter reused
    let meta = ok(env.dig_with(&[hb2], &[DigRig::new(&u, entry_for(&hb2, 1, 0))]));
    push(
        7,
        "StaleHeartbeat",
        "counter not above rig.hb_counter",
        raw_events(&meta.logs)[0].clone(),
    );
    // 8 LeaseExpired
    let (mut env, u) = skip_env(0x35, SOL / 20, caps, &plan);
    let meta = ok(env.dig_with(&[], &[DigRig::new(&u, reuse_lease())]));
    push(
        8,
        "LeaseExpired",
        "hb_ix = 0xFF with no lease in this shift",
        raw_events(&meta.logs)[0].clone(),
    );
    // 9 AlreadyDugRound
    let (mut env, mut u) = skip_env(0x36, SOL / 20, caps, &plan);
    ok(env.dig_fresh(&mut [&mut u]));
    push(
        9,
        "AlreadyDugRound",
        "second dig in the same ORE round",
        skip_bytes(&mut env, &mut u, round),
    );
    // 10 CapsExpired
    let mut c = caps;
    c.expiry = T0 + 60;
    let (mut env, mut u) = skip_env(0x37, SOL / 20, c, &plan);
    env.advance_time(61);
    push(
        10,
        "CapsExpired",
        "now > caps_expiry_ts",
        skip_bytes(&mut env, &mut u, round),
    );
    // 11 OutsideWindow
    let mut p = plan;
    p.window_start = T0 + 600;
    let (mut env, mut u) = skip_env(0x38, SOL / 20, caps, &p);
    push(
        11,
        "OutsideWindow",
        "now < plan_window_start_ts",
        skip_bytes(&mut env, &mut u, round),
    );
    // 12 BudgetExhausted
    let mut c = caps;
    c.shift = EXECUTOR_FEE + 9;
    let (mut env, mut u) = skip_env(0x39, SOL / 20, c, &plan);
    push(
        12,
        "BudgetExhausted",
        "cap_shift - spent_shift - fee = 9 lamports < 10 squares",
        skip_bytes(&mut env, &mut u, round),
    );
    // 13 RigNotArmed
    let (mut env, mut u) = skip_env(0x3A, SOL / 20, caps, &plan);
    let w = u.wallet.insecure_clone();
    ok(env.send_as(
        &w,
        &[ix_break_wallet(&w.pubkey(), break_reason::MANUAL)],
        &[],
    ));
    push(
        13,
        "RigNotArmed",
        "rig Broken (wallet BREAK reason 6)",
        skip_bytes(&mut env, &mut u, round),
    );
    // 14 RigFrozen
    let (mut env, mut u) = skip_env(0x3B, SOL / 20, caps, &plan);
    let w = u.wallet.insecure_clone();
    ok(env.send_as(&w, &[ix_freeze_wallet(&w.pubkey())], &[]));
    push(
        14,
        "RigFrozen",
        "rig Frozen",
        skip_bytes(&mut env, &mut u, round),
    );
    // 23 StrategyMismatch
    let mut env = Env::golden(Build::Mainnet);
    let mut u = User::with_keys(&mut env, [0x3C; 32], RFC6979_P256_SCALAR);
    let w = u.wallet.insecure_clone();
    ok(env.send_as(
        &w,
        &[
            ore_automate(
                &w.pubkey(),
                &EXECUTOR,
                TILE_CAP,
                SOL / 20,
                EXECUTOR_FEE + 1,
                2,
                0,
                u16::MAX,
            ),
            ix_register_rig(&w.pubkey(), &u.p256(), None),
            ix_set_caps(&w.pubkey(), caps),
            ix_arm_wallet(&w.pubkey(), &plan),
        ],
        &[],
    ));
    push(
        23,
        "StrategyMismatch",
        "automation.fee = executor_fee + 1",
        skip_bytes(&mut env, &mut u, round),
    );
    // 25 RoundNotActive
    let (mut env, mut u) = skip_env(0x3D, SOL / 20, caps, &plan);
    env.set_clock(golden::END_SLOT, T0);
    push(
        25,
        "RoundNotActive",
        "clock slot = Board.end_slot",
        skip_bytes(&mut env, &mut u, round),
    );
    // 26 MinerNotCheckpointed
    let (mut env, mut u) = skip_env(0x3E, SOL / 20, caps, &plan);
    let m = u.miner();
    env.poke_u64(&m, 664, round - 5);
    env.poke_u64(&m, 48, round - 6);
    push(
        26,
        "MinerNotCheckpointed",
        "miner.round_id = r-5, checkpoint_id = r-6",
        skip_bytes(&mut env, &mut u, round),
    );
    // 27 MotherlodeCondition
    let mut env = Env::golden(Build::Mainnet);
    let mut u = User::with_keys(&mut env, [0x3F; 32], RFC6979_P256_SCALAR);
    let w = u.wallet.insecure_clone();
    ok(env.send_as(
        &w,
        &[
            ore_automate(
                &w.pubkey(),
                &EXECUTOR,
                TILE_CAP,
                SOL / 20,
                EXECUTOR_FEE,
                2,
                60_000,
                u16::MAX,
            ),
            ix_register_rig(&w.pubkey(), &u.p256(), None),
            ix_set_caps(&w.pubkey(), caps),
            ix_arm_wallet(&w.pubkey(), &plan),
        ],
        &[],
    ));
    push(
        27,
        "MotherlodeCondition",
        "automation min_motherlode 60000 ORE > pot",
        skip_bytes(&mut env, &mut u, round),
    );
    // 28 InsufficientAutomationBalance
    let (mut env, mut u) = skip_env(0x40, 600_000, caps, &plan);
    push(
        28,
        "InsufficientAutomationBalance",
        "balance 600000 < 10 x 100000 + 5000",
        skip_bytes(&mut env, &mut u, round),
    );
    // 29 OreNoOp (TEST-ONLY mock ORE that returns Ok without deploying)
    let (mut env, mut u) = skip_env(0x41, SOL / 20, caps, &plan);
    let mock = std::fs::read(root().join("target/deploy-mock/mock_ore.so"))
        .expect("target/deploy-mock/mock_ore.so: run scripts/build.sh");
    env.svm.add_program(ORE, &mock).unwrap();
    let mut board = env.account(&BOARD);
    board.data[1] = 2; // mock mode NO_OP
    env.svm.set_account(BOARD, board).unwrap();
    push(
        29,
        "OreNoOp",
        "TEST-ONLY mock ORE returns Ok without deploying",
        skip_bytes(&mut env, &mut u, round),
    );
    // 30 FocusOnly
    let mut p = plan;
    p.flags = plan_flags::FOCUS_ONLY;
    let (mut env, mut u) = skip_env(0x42, SOL / 20, caps, &p);
    push(
        30,
        "FocusOnly",
        "plan_flags bit0",
        skip_bytes(&mut env, &mut u, round),
    );
    // 31 ExecutorUnderfunded
    let (mut env, mut u) = skip_env(0x43, SOL / 20, caps, &plan);
    env.poke_u64(&u.miner(), 56, 0);
    let rent = env.svm.minimum_balance_for_rent_exemption(0);
    let mut ex = env.account(&EXECUTOR);
    ex.lamports = rent + CHECKPOINT_FEE - 1;
    env.svm.set_account(EXECUTOR, ex).unwrap();
    push(
        31,
        "ExecutorUnderfunded",
        "miner.checkpoint_fee = 0 and Executor < rent + 10000",
        skip_bytes(&mut env, &mut u, round),
    );
    // p256-introspect codes pass through unchanged.
    let (mut env, mut u) = skip_env(0x44, SOL / 20, caps, &plan);
    let hb = u.heartbeat(7, round, 3); // wrong shift_id in the signed preimage
    let meta = ok(env.dig_with(&[hb], &[DigRig::new(&u, entry_for(&hb, 1, 0))]));
    push(
        p256_introspect::IntrospectError::MessageMismatch.code(),
        "p256-introspect MessageMismatch (0x2560000e)",
        "heartbeat signed over shift_id 7 while rig.shift_id = 1",
        raw_events(&meta.logs)[0].clone(),
    );
    out
}

fn events_file(samples: BTreeMap<u8, (String, Vec<u8>)>) -> Value {
    let skips = skip_samples();
    let mut all = samples;
    let (_, _, _, first_skip) = &skips[0];
    all.entry(ev::tag::RIG_SKIPPED)
        .or_insert_with(|| ("skip_codes[0]".to_string(), first_skip.clone()));
    assert_eq!(all.len(), 10, "every event tag captured: {:?}", all.keys());
    let evs: Vec<Value> = all
        .iter()
        .map(|(tag, (source, bytes))| {
            json!({
                "tag": tag,
                "event": event_name(*tag),
                "length": ev::LEN[usize::from(*tag)],
                "layout": event_layout(*tag).iter().map(|(n, t, o, z)| json!({"name": n, "type": t, "offset": o, "size": z})).collect::<Vec<_>>(),
                "sample": {
                    "captured_from": source,
                    "hex": hex(bytes),
                    "base64": base64::Engine::encode(&base64::engine::general_purpose::STANDARD, bytes),
                    "fields": decode_with_layout(bytes),
                },
            })
        })
        .collect();
    let skip_json: Vec<Value> = skips
        .iter()
        .map(|(code, name, how, bytes)| {
            json!({
                "error": code,
                "error_hex": format!("0x{code:08x}"),
                "name": name,
                "trigger": how,
                "hex": hex(bytes),
                "fields": decode_with_layout(bytes),
            })
        })
        .collect();
    json!({
        "format": "heads-down/golden-events",
        "interface_version": "1.1",
        "generated_by": "programs/heads-down/tests/src/vectors.rs",
        "notes": [
            "One `Program data: <base64>` log line per event: a single sol_log_data slice, byte 0 = tag, fields little-endian, no padding. Only lines emitted while heads_down is the innermost executing program are heads_down events.",
            "Every sample was captured from a real transaction on the pinned LiteSVM fork (samples come from instructions.json vectors unless noted).",
            "Lengths are exact and never change for a tag; new fields get a new tag. end_shift emits ShiftEnded (4) and then ShiftEndedV2 (10), its superset: a consumer that knows tag 10 should ignore tag 4.",
            "RigSkipped.error is the precise code: heads_down 0..=31, or the shared crates' 0x2560_00xx (p256-introspect) / 0x5347_00xx (sgt-verify) codes unchanged. `skip_codes` shows each dig skip code captured from a run that triggers it."
        ],
        "events": evs,
        "skip_codes": skip_json,
    })
}

// ---- messages.json -----------------------------------------------------------------

fn preimage_layout(kind_byte: u8) -> Vec<Value> {
    let common: [(&str, &str, usize); 4] = [
        ("domain \"HDv1\"", "[u8;4]", 4),
        ("program_id", "pubkey", 32),
        ("rig", "pubkey", 32),
        ("kind", "u8", 1),
    ];
    let rest: &[(&str, &str, usize)] = match kind_byte {
        kind::HEARTBEAT => &[
            ("counter", "u64", 8),
            ("shift_id", "u64", 8),
            ("round_id", "u64", 8),
            ("lease_rounds", "u8", 1),
        ],
        kind::BREAK | kind::FREEZE => &[
            ("counter", "u64", 8),
            ("shift_id", "u64", 8),
            ("reason", "u8", 1),
        ],
        kind::PLAN => &[
            ("counter", "u64", 8),
            ("max_ev_cost", "u64", 8),
            ("dig_lamports", "u64", 8),
            ("split", "u8", 1),
            ("solo", "u8", 1),
            ("lease", "u8", 1),
            ("flags", "u8", 1),
            ("window_start", "i64", 8),
            ("window_end", "i64", 8),
        ],
        _ => &[],
    };
    let mut off = 0;
    common
        .iter()
        .chain(rest.iter())
        .map(|(n, t, z)| {
            let v = json!({"name": n, "type": t, "offset": off, "size": z});
            off += z;
            v
        })
        .collect()
}

fn precompile_layout(n: usize, msg_len: usize) -> Value {
    let mut recs = vec![];
    let header = 2 + 14 * n;
    for i in 0..n {
        let base = header + i * (33 + 64 + msg_len);
        recs.push(json!({
            "record": i,
            "record_offset": 2 + 14 * i,
            "signature_offset": base + 33,
            "signature_instruction_index": "0xffff (this instruction)",
            "public_key_offset": base,
            "public_key_instruction_index": "0xffff",
            "message_data_offset": base + 33 + 64,
            "message_data_size": msg_len,
            "message_instruction_index": "0xffff",
        }));
    }
    json!({
        "format": "num_signatures u8 | padding u8 | num_signatures x 14-byte records (signature_offset u16, signature_instruction_index u16, public_key_offset u16, public_key_instruction_index u16, message_data_offset u16, message_data_size u16, message_instruction_index u16) | per signature: public_key[33] | signature[64] (r||s, low-S) | message[32]",
        "records": recs,
    })
}

#[allow(clippy::too_many_lines)]
fn messages_file() -> Value {
    let mut env = Env::golden(Build::Mainnet);
    let u = User::with_keys(&mut env, [0xA1; 32], RFC6979_P256_SCALAR);
    let rig = u.rig;
    let plan = standard_plan();
    let cases: Vec<(&str, u8, Value, Vec<u8>)> = vec![
        (
            "heartbeat",
            kind::HEARTBEAT,
            json!({"counter": "1", "shift_id": "1", "round_id": s(golden::ROUND_ID), "lease_rounds": 3}),
            message::heartbeat_preimage(&rig, 1, 1, golden::ROUND_ID, 3).to_vec(),
        ),
        (
            "break_pickup",
            kind::BREAK,
            json!({"counter": "4", "shift_id": "2", "reason": break_reason::PICKUP}),
            message::signal_preimage(kind::BREAK, &rig, 4, 2, break_reason::PICKUP).to_vec(),
        ),
        (
            "break_unlocked",
            kind::BREAK,
            json!({"counter": "6", "shift_id": "2", "reason": break_reason::UNLOCKED}),
            message::signal_preimage(kind::BREAK, &rig, 6, 2, break_reason::UNLOCKED).to_vec(),
        ),
        (
            "freeze",
            kind::FREEZE,
            json!({"counter": "5", "shift_id": "2", "reason": break_reason::FREEZE}),
            message::signal_preimage(kind::FREEZE, &rig, 5, 2, break_reason::FREEZE).to_vec(),
        ),
        (
            "plan",
            kind::PLAN,
            json!({"counter": "2", "plan": plan_args(&plan)}),
            message::plan_preimage(&rig, 2, &plan).to_vec(),
        ),
    ];
    let mut msgs = vec![];
    for (name, k, fields, pre) in cases {
        let digest = message::digest(&pre);
        let (raw, low) = p256_sign_details(&u.key, &digest);
        assert_eq!(low, u.sign(&digest));
        let ix = secp_ix(&[(low, u.p256(), digest.to_vec())]);
        // Verified by the real Agave secp256r1 precompile (a transaction with
        // only this instruction succeeds iff the signature verifies).
        ok(env.send(std::slice::from_ref(&ix), &[]));
        let mut bad = ix.clone();
        let last = bad.data.len() - 1;
        bad.data[last] ^= 1;
        assert!(
            env.send(&[bad], &[]).is_err(),
            "{name}: tampered digest must fail"
        );
        msgs.push(json!({
            "name": name,
            "kind": k,
            "fields": fields,
            "preimage_len": pre.len(),
            "preimage_layout": preimage_layout(k),
            "preimage_hex": hex(&pre),
            "message_sha256_hex": hex(&digest),
            "signature_rfc6979_hex": hex(&raw),
            "signature_low_s_hex": hex(&low),
            "normalized": raw != low,
            "precompile_instruction": {
                "program_id": s(secp256r1_id()),
                "data_len": ix.data.len(),
                "data_hex": hex(&ix.data),
                "layout": precompile_layout(1, 32),
                "verified_in_litesvm": true,
            },
        }));
    }
    assert_eq!(msgs[0]["preimage_len"], 94);
    assert_eq!(msgs[1]["preimage_len"], 86);
    assert_eq!(msgs[4]["preimage_len"], 113);
    json!({
        "format": "heads-down/golden-messages",
        "interface_version": "1.1",
        "generated_by": "programs/heads-down/tests/src/vectors.rs",
        "notes": [
            "The P-256 key signs the 32-byte message = SHA-256(preimage) with SHA256withECDSA (Android Keystore); the secp256r1 precompile verifies ECDSA-P256 over SHA-256 of those 32 bytes. The program rebuilds the preimage from its own state + instruction data and requires byte-equality with the precompile's message.",
            "signature_rfc6979_hex is the deterministic RFC 6979 (HMAC-SHA256) r||s; signature_low_s_hex is the same signature with s replaced by n - s when s > n/2, the only form the precompile accepts. `normalized` says whether that happened.",
            "The key is the RFC 6979 A.2.5 P-256 test key (public test material, the same key android/core/keys vectors.json uses); the rig is alice's rig from instructions.json.",
            "Every precompile instruction below was sent alone in a LiteSVM transaction and succeeded; flipping one message byte made it fail.",
            "BREAK reasons: 1 pickup, 2 screen_on, 7 unplugged -> Cooling; 4 lease_lapse, 5 budget, 6 manual, 8 unlocked -> Broken. FREEZE binds any reason byte (the app sends 3)."
        ],
        "program_id": s(HD),
        "program_id_hex": hex(HD.as_ref()),
        "rig": s(rig),
        "rig_hex": hex(rig.as_ref()),
        "rig_authority": s(u.pubkey()),
        "p256_key": {
            "private_scalar_hex": hex(&RFC6979_P256_SCALAR),
            "public_key_compressed_hex": hex(&u.p256()),
            "note": "RFC 6979 A.2.5 test key: public test material only",
        },
        "messages": msgs,
    })
}

// ---- registrar.json -------------------------------------------------------------------

fn registrar_file() -> Value {
    let mut env = Env::golden(Build::Mainnet);
    let carol = User::with_keys(&mut env, [0xC3; 32], [0x33; 32]);
    let registrar = env.registrar.insecure_clone();
    let expiry = golden::START_SLOT + 6_480_000;
    let (msg, sig, ed) = registrar_voucher(&registrar, &carol.pubkey(), &carol.p256(), 2, expiry);
    assert_eq!(msg.len(), 111);
    assert_eq!(ed.data.len(), 223);
    assert_eq!(ed.data[..16], REGISTRAR_IX_HEADER);
    let w = carol.wallet.insecure_clone();
    let att = AttestationArg {
        ix: 0,
        sig: 0,
        level: 2,
        expiry_slot: expiry,
    };
    let reg = ix_register_rig(&carol.pubkey(), &carol.p256(), Some(att));
    let meta = ok(env.send_as(&w, &[ed.clone(), reg.clone()], &[]));
    let rig = env.rig(&carol.rig);
    assert_eq!(rig.attestation_level, 2);
    assert_eq!(rig.attestation_expiry_slot.get(), expiry);

    // Level 0 vouchers (registrar HD_*_POLICY=level0) are refused by the
    // program: register without an attestation instead.
    let mut env0 = Env::golden(Build::Mainnet);
    let dan = User::with_keys(&mut env0, [0xC4; 32], [0x36; 32]);
    let (_, _, ed0) = registrar_voucher(
        &env0.registrar.insecure_clone(),
        &dan.pubkey(),
        &dan.p256(),
        0,
        expiry,
    );
    let att0 = AttestationArg {
        ix: 0,
        sig: 0,
        level: 0,
        expiry_slot: expiry,
    };
    let wd = dan.wallet.insecure_clone();
    let res = env0.send_as(
        &wd,
        &[ed0, ix_register_rig(&dan.pubkey(), &dan.p256(), Some(att0))],
        &[],
    );
    assert_hd(&res, 1, HdError::InvalidAttestation);
    // An expired voucher (expiry_slot <= clock slot) is refused.
    let (_, _, ed_old) = registrar_voucher(
        &env0.registrar.insecure_clone(),
        &dan.pubkey(),
        &dan.p256(),
        2,
        golden::START_SLOT + 10,
    );
    let att_old = AttestationArg {
        ix: 0,
        sig: 0,
        level: 2,
        expiry_slot: golden::START_SLOT + 10,
    };
    let res = env0.send_as(
        &wd,
        &[
            ed_old,
            ix_register_rig(&dan.pubkey(), &dan.p256(), Some(att_old)),
        ],
        &[],
    );
    assert_hd(&res, 1, HdError::InvalidAttestation);

    let preimage_layout = json!([
        {"name": "domain \"HDreg\"", "type": "[u8;5]", "offset": 0, "size": 5},
        {"name": "program_id", "type": "pubkey", "offset": 5, "size": 32},
        {"name": "authority", "type": "pubkey", "offset": 37, "size": 32},
        {"name": "p256_pubkey", "type": "[u8;33] SEC1 compressed", "offset": 69, "size": 33},
        {"name": "level", "type": "u8 (1 TEE, 2 StrongBox; 0 is refused on-chain)", "offset": 102, "size": 1},
        {"name": "expiry_slot", "type": "u64", "offset": 103, "size": 8},
    ]);
    json!({
        "format": "heads-down/golden-registrar",
        "interface_version": "1.1",
        "generated_by": "programs/heads-down/tests/src/vectors.rs",
        "notes": [
            "The registrar signs the raw 111-byte HDreg preimage with Ed25519 (not SHA-256 first). This matches registrar/INTERFACE-NOTES.md N2 byte for byte.",
            "The Ed25519SigVerify instruction is 223 bytes and starts with the constant 16-byte header (registrar/INTERFACE-NOTES.md N3, `hd_registrar::voucher::IX_HEADER`); it is byte-identical to solana_ed25519_program::new_ed25519_instruction_with_signature.",
            "The program does not require that fixed header: it accepts any Ed25519SigVerify layout whose every offsets record points only into the precompile's own instruction (0xFFFF or its own index), located by (ed25519_ix, ed25519_sig_index) from the register_rig / rotate_key data. The registrar's format is one such layout.",
            "On-chain checks: config.registrar == the voucher pubkey; the verified message == the preimage rebuilt from (crate::ID, the signing authority, the p256 key in the instruction data, level, expiry_slot); level in {1, 2}; expiry_slot > Clock.slot. Every failure is InvalidAttestation (21).",
            "Verified in LiteSVM: the voucher below is ACCEPTED by register_rig (attestation_level 2); the same format with level 0, or with expiry_slot <= the current slot, is REJECTED with InvalidAttestation."
        ],
        "program_id": s(HD),
        "registrar_key": {
            "pubkey": s(registrar.pubkey()),
            "pubkey_hex": hex(registrar.pubkey().as_ref()),
            "seed": "[0x05; 32] (public test seed; RegistrarKey::from_seed(&[5u8; 32]) in registrar/src/voucher.rs tests)",
        },
        "voucher": {
            "authority": s(carol.pubkey()),
            "p256_pubkey_hex": hex(&carol.p256()),
            "level": 2,
            "expiry_slot": s(expiry),
            "preimage_len": 111,
            "preimage_layout": preimage_layout,
            "preimage_hex": hex(&msg),
            "ed25519_signature_hex": hex(&sig),
        },
        "ed25519_instruction": {
            "program_id": s(ED25519),
            "accounts": [],
            "data_len": ed.data.len(),
            "header_hex": hex(&ed.data[..16]),
            "layout": "[1, 0] | sig_off 48 | sig_ix 0xFFFF | pk_off 16 | pk_ix 0xFFFF | msg_off 112 | msg_len 111 | msg_ix 0xFFFF | pubkey[32] @16 | signature[64] @48 | message[111] @112",
            "data_hex": hex(&ed.data),
        },
        "register_rig": {
            "transaction": [
                {"index": 0, "role": "ed25519 voucher", "program_id": s(ED25519)},
                {"index": 1, "role": "register_rig", "program_id": s(HD), "data_hex": hex(&reg.data), "data_len": reg.data.len()},
            ],
            "litesvm": {
                "result": "success",
                "attestation_level": 2,
                "attestation_expiry_slot": s(expiry),
                "events": events_json(&raw_events(&meta.logs)),
            },
        },
        "rejections": [
            {"case": "level 0 voucher", "result": "InvalidAttestation (21) at instruction 1"},
            {"case": "expiry_slot <= Clock.slot", "result": "InvalidAttestation (21) at instruction 1"},
        ],
    })
}
