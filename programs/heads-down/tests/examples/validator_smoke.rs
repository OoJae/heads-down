//! Smoke test of the deployed artifact on a **real validator**
//! (`scripts/smoke-validator.sh` starts one with mainnet's feature set and
//! runs this). LiteSVM runs the same SVM, but with every feature active and
//! no loader in front of it; this is the check that the SBPFv3 build really
//! deploys and executes where it will live, and that the v1.3 account resize
//! (the Rig tombstone) and the rotation instructions behave there too.
//!
//! It needs no ORE program: only the ORE Board account (a fixture), which
//! `arm_shift`, `record_heartbeats` and `end_shift` read. JSON-RPC goes
//! through `curl`, so the suite gains no client dependency.
//!
//! ```text
//! cargo +1.97.1 run -p heads-down-tests --example validator_smoke -- <rpc url> <upgrade authority keypair>
//! ```

use std::{process::Command, str::FromStr, thread::sleep, time::Duration};

use base64::Engine;
use hd::{
    error::HdError,
    instructions::governance::{TIMELOCK_SECS, TIMELOCK_SLOTS},
    state,
};
use heads_down_tests::*;
use serde_json::{json, Value};
use solana_transaction::Transaction;

const CLOCK: &str = "SysvarC1ock11111111111111111111111111111111";

struct Rpc {
    url: String,
}

/// A confirmed transaction.
struct Sent {
    logs: Vec<String>,
    cu: u64,
}

/// A transaction the validator refused (in simulation or on chain).
#[derive(Debug)]
struct Refused {
    err: Value,
    logs: Vec<String>,
}

fn b64(s: &str) -> Vec<u8> {
    base64::engine::general_purpose::STANDARD.decode(s).unwrap()
}

fn strings(v: &Value) -> Vec<String> {
    v.as_array()
        .map(|a| a.iter().filter_map(|l| l.as_str().map(String::from)).collect())
        .unwrap_or_default()
}

impl Rpc {
    fn call(&self, method: &str, params: Value) -> Value {
        let body = json!({"jsonrpc": "2.0", "id": 1, "method": method, "params": params}).to_string();
        let out = Command::new("curl")
            .args(["-s", "-m", "30", &self.url, "-X", "POST"])
            .args(["-H", "Content-Type: application/json", "-d", &body])
            .output()
            .expect("curl is installed");
        serde_json::from_slice(&out.stdout).unwrap_or_else(|e| {
            panic!("{method}: {e}: {}", String::from_utf8_lossy(&out.stdout))
        })
    }

    fn account(&self, a: &Address) -> Option<Account> {
        let v = self.call(
            "getAccountInfo",
            json!([a.to_string(), {"encoding": "base64", "commitment": "confirmed"}]),
        );
        let a = &v["result"]["value"];
        if a.is_null() {
            return None;
        }
        Some(Account {
            lamports: a["lamports"].as_u64().unwrap(),
            data: b64(a["data"][0].as_str().unwrap()),
            owner: Address::from_str(a["owner"].as_str().unwrap()).unwrap(),
            executable: a["executable"].as_bool().unwrap(),
            rent_epoch: u64::MAX,
        })
    }

    /// `(slot, unix_timestamp)` from the Clock sysvar.
    fn clock(&self) -> (u64, i64) {
        let d = self.account(&Address::from_str(CLOCK).unwrap()).unwrap().data;
        (
            u64_at(&d, 0),
            i64::from_le_bytes(d[32..40].try_into().unwrap()),
        )
    }

    fn rent(&self, len: usize) -> u64 {
        self.call("getMinimumBalanceForRentExemption", json!([len]))["result"]
            .as_u64()
            .unwrap()
    }

    /// Sign with `payer` + `signers`, send (with preflight), wait for
    /// confirmation.
    fn send(&self, payer: &Keypair, ixs: &[Instruction], signers: &[&Keypair]) -> Result<Sent, Refused> {
        let hash = self.call("getLatestBlockhash", json!([{"commitment": "confirmed"}]));
        let hash = Address::from_str(hash["result"]["value"]["blockhash"].as_str().unwrap())
            .unwrap()
            .to_bytes();
        let mut all: Vec<&Keypair> = vec![payer];
        for s in signers {
            if s.pubkey() != payer.pubkey() {
                all.push(s);
            }
        }
        let tx = Transaction::new_signed_with_payer(ixs, Some(&payer.pubkey()), &all, hash.into());
        let wire = base64::engine::general_purpose::STANDARD.encode(wincode::serialize(&tx).unwrap());
        let v = self.call(
            "sendTransaction",
            json!([wire, {"encoding": "base64", "preflightCommitment": "confirmed"}]),
        );
        if !v["error"].is_null() {
            return Err(Refused {
                err: v["error"]["data"]["err"].clone(),
                logs: strings(&v["error"]["data"]["logs"]),
            });
        }
        let sig = v["result"].as_str().expect("a signature").to_string();
        for _ in 0..120 {
            let st = self.call("getSignatureStatuses", json!([[sig]]));
            let s = &st["result"]["value"][0];
            if !s.is_null() && s["confirmationStatus"] != "processed" {
                let t = self.transaction(&sig);
                let logs = strings(&t["meta"]["logMessages"]);
                if !s["err"].is_null() {
                    return Err(Refused {
                        err: s["err"].clone(),
                        logs,
                    });
                }
                return Ok(Sent {
                    logs,
                    cu: t["meta"]["computeUnitsConsumed"].as_u64().unwrap_or(0),
                });
            }
            sleep(Duration::from_millis(250));
        }
        panic!("{sig} was not confirmed in 30 s");
    }

    fn transaction(&self, sig: &str) -> Value {
        for _ in 0..40 {
            let v = self.call(
                "getTransaction",
                json!([sig, {"encoding": "json", "commitment": "confirmed", "maxSupportedTransactionVersion": 0}]),
            );
            if !v["result"].is_null() {
                return v["result"].clone();
            }
            sleep(Duration::from_millis(250));
        }
        panic!("getTransaction {sig}: not found");
    }
}

#[track_caller]
fn sent(what: &str, r: Result<Sent, Refused>) -> Sent {
    match r {
        Ok(s) => {
            println!("[OK] {what}: {} CU", s.cu);
            s
        }
        Err(f) => panic!("{what} was refused: {}\n{}", f.err, f.logs.join("\n")),
    }
}

/// The transaction must fail with heads_down error `e` in instruction `ix`.
#[track_caller]
fn refused(what: &str, r: Result<Sent, Refused>, ix: u8, e: HdError) {
    match r {
        Ok(_) => panic!("{what}: expected {e:?}, but it succeeded"),
        Err(f) => {
            assert_eq!(
                f.err,
                json!({"InstructionError": [ix, {"Custom": e.code()}]}),
                "{what}\n{}",
                f.logs.join("\n")
            );
            println!("[OK] {what}: refused with {e:?} ({})", e.code());
        }
    }
}

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let [_, url, ua_path] = args.as_slice() else {
        eprintln!("usage: validator_smoke <rpc url> <upgrade authority keypair.json>");
        std::process::exit(2);
    };
    let rpc = Rpc { url: url.clone() };
    let ua = solana_keypair::read_keypair_file(ua_path).expect("the upgrade authority keypair");
    let governance = Keypair::new();
    let registrar = Keypair::new();
    let successor = Keypair::new();
    let crank = Keypair::new();
    let mut user = User::from_wallet(Keypair::new(), 7);
    let wallet = user.wallet.insecure_clone();
    let w = wallet.pubkey();

    // ---- the artifact on chain ---------------------------------------------
    let program = rpc.account(&HD).expect("heads_down is deployed");
    assert!(program.executable && program.owner == LOADER_V3);
    let program_data = rpc.account(&Env::program_data()).expect("ProgramData");
    // ProgramData: 45-byte header, then the ELF; e_flags (u32 at ELF offset
    // 48) is the SBPF version.
    let elf = &program_data.data[45..];
    let sbpf = u32::from_le_bytes(elf[48..52].try_into().unwrap());
    println!("[OK] heads_down at {HD}: executable, upgradeable loader, SBPFv{sbpf} ELF on chain");
    let want = std::env::var("HD_SMOKE_SBPF").ok().map(|v| v.parse::<u32>().unwrap());
    if let Some(want) = want {
        assert_eq!(sbpf, want, "the deployed ELF's e_flags");
    }

    sent(
        "fund the test keys",
        rpc.send(
            &ua,
            &[
                system_transfer(&ua.pubkey(), &governance.pubkey(), SOL),
                system_transfer(&ua.pubkey(), &successor.pubkey(), SOL),
                system_transfer(&ua.pubkey(), &crank.pubkey(), SOL),
                system_transfer(&ua.pubkey(), &w, 2 * SOL),
            ],
            &[],
        ),
    );

    // ---- initialize_config ---------------------------------------------------
    if rpc.account(&CONFIG).is_none() {
        sent(
            "initialize_config (upgrade authority read from the real ProgramData)",
            rpc.send(
                &ua,
                &[ix_initialize_config(
                    &ua.pubkey(),
                    &governance.pubkey(),
                    &registrar.pubkey(),
                    CRANK_FEE,
                    EXECUTOR_FEE,
                    0,
                    hd::ore::layout_hash(),
                )],
                &[],
            ),
        );
    }
    let config = rpc.account(&CONFIG).unwrap();
    assert_eq!((config.owner, config.data.len()), (HD, 256));
    let c = *bytemuck_view::<state::Config>(&config.data);
    assert_eq!(c.governance, governance.pubkey().to_bytes());
    assert_eq!(config.data[192..256], [0u8; 64], "the v1.3 Config tail starts zeroed");

    // ---- a rig: register, arm, heartbeat, end ---------------------------------
    let (_, now) = rpc.clock();
    let board = rpc.account(&BOARD).expect("the ORE Board fixture is loaded");
    let round = u64_at(&board.data, 8);
    let mut caps = Caps::standard();
    caps.expiry = now + 7 * 86_400;
    let mut plan = standard_plan();
    plan.window_start = now - 3_600;
    plan.window_end = now + 8 * 3_600;
    sent(
        "register_rig + set_caps + arm_shift (wallet)",
        rpc.send(
            &wallet,
            &[
                ix_register_rig(&w, &user.p256(), None),
                ix_set_caps(&w, caps),
                ix_arm_wallet(&w, &plan),
            ],
            &[],
        ),
    );
    let rig_rent = rpc.rent(384);
    let a = rpc.account(&user.rig).unwrap();
    assert_eq!((a.owner, a.data.len(), a.lamports), (HD, 384, rig_rent));
    assert_eq!(bytemuck_view::<state::Rig>(&a.data).shift_id.get(), 1);

    // A P-256 heartbeat through the validator's own secp256r1 precompile.
    let hb = user.heartbeat(1, round, 3);
    let s = sent(
        "record_heartbeats with a Secp256r1SigVerify instruction",
        rpc.send(
            &crank,
            &[secp_ix_for(&[hb]), ix_record(&[(user.rig, entry_for(&hb, 0, 0))])],
            &[],
        ),
    );
    assert!(
        events(&s.logs).iter().any(|e| matches!(e, Event::HeartbeatsRecorded { .. })),
        "{:?}",
        s.logs
    );
    let r = *bytemuck_view::<state::Rig>(&rpc.account(&user.rig).unwrap().data);
    assert_eq!(r.hb_counter.get(), hb.counter);
    // The same signature again counts for nothing (the counter moved).
    let s = sent(
        "the same heartbeat replayed",
        rpc.send(
            &crank,
            &[secp_ix_for(&[hb]), ix_record(&[(user.rig, entry_for(&hb, 0, 0))])],
            &[],
        ),
    );
    assert_eq!(
        skipped_code(&events(&s.logs), &user.rig),
        Some(HdError::StaleHeartbeat.code()),
        "{:?}",
        s.logs
    );
    println!("[OK] ... skipped with StaleHeartbeat");

    sent(
        "end_shift by the wallet (ShiftLog 1, payer recorded)",
        rpc.send(&wallet, &[ix_end_shift(&w, &user.rig, 1)], &[]),
    );
    let log1 = shift_log_pda(&user.rig, 1);
    let l = *bytemuck_view::<state::ShiftLog>(&rpc.account(&log1).unwrap().data);
    assert_eq!(l.payer_prefix[..], w.to_bytes()[..16]);

    // ---- the tombstone ---------------------------------------------------------
    let before = rpc.account(&w).unwrap().lamports;
    sent(
        "close_rig (shrinks the Rig to a 32-byte tombstone)",
        rpc.send(&crank, &[ix_close_rig(&w, None)], &[&wallet]),
    );
    let t = rpc.account(&user.rig).expect("the tombstone");
    let tomb_rent = rpc.rent(32);
    assert_eq!((t.owner, t.data.len(), t.lamports), (HD, 32, tomb_rent));
    assert_eq!(t.data[0], state::tag::RIG_TOMBSTONE);
    let ts = *bytemuck_view::<state::RigTombstone>(&t.data);
    assert_eq!((ts.shift_id.get(), ts.hb_counter.get()), (1, hb.counter));
    assert_eq!(rpc.account(&w).unwrap().lamports, before + rig_rent - tomb_rent);
    println!(
        "[OK] tombstone: 32 bytes, tag 10, shift_id 1, hb_counter {}; {} of {} lamports back to the wallet",
        hb.counter,
        rig_rent - tomb_rent,
        rig_rent
    );

    let before = rpc.account(&w).unwrap().lamports;
    sent(
        "register_rig over the tombstone + set_caps + arm_shift",
        rpc.send(
            &crank,
            &[
                ix_register_rig(&w, &user.p256(), None),
                ix_set_caps(&w, caps),
                ix_arm_wallet(&w, &plan),
            ],
            &[&wallet],
        ),
    );
    let a = rpc.account(&user.rig).unwrap();
    assert_eq!((a.owner, a.data.len(), a.lamports), (HD, 384, rig_rent));
    assert_eq!(rpc.account(&w).unwrap().lamports, before - (rig_rent - tomb_rent));
    let r = *bytemuck_view::<state::Rig>(&a.data);
    assert_eq!((r.shift_id.get(), r.hb_counter.get()), (2, hb.counter));
    println!("[OK] the re-registered rig resumed: shift_id 2, hb_counter {}", hb.counter);
    // A message the first life signed cannot come back: a FREEZE for shift 2
    // with the counter the tombstone carried over is stale. (Before v1.3 the
    // re-registered rig restarted at counter 0 and would have accepted it.)
    let (digest, sig) = signal_signature(&user, hd::message::kind::FREEZE, hb.counter, 2, 3);
    refused(
        "a P-256 FREEZE with a counter from the rig's first life",
        rpc.send(
            &crank,
            &[
                secp_ix(&[(sig, user.p256(), digest.to_vec())]),
                ix_freeze_p256(&w, 3, hb.counter, 0, 0),
            ],
            &[],
        ),
        1,
        HdError::StaleHeartbeat,
    );
    sent(
        "end_shift for shift 2 (its ShiftLog address is free: INTERFACE §10 fixed)",
        rpc.send(&wallet, &[ix_end_shift(&w, &user.rig, 2)], &[]),
    );
    assert!(rpc.account(&shift_log_pda(&user.rig, 2)).is_some());
    assert!(rpc.account(&log1).is_some(), "ShiftLog 1 is still there");
    refused(
        "close_shift_log before the 30 days",
        rpc.send(&crank, &[ix_close_shift_log(&user.rig, 1, &w)], &[]),
        0,
        HdError::ShiftLogNotExpired,
    );

    // ---- governance rotation -----------------------------------------------------
    let (slot, _) = rpc.clock();
    let s = sent(
        "propose_governance",
        rpc.send(
            &governance,
            &[ix_propose_governance(&governance.pubkey(), &successor.pubkey())],
            &[],
        ),
    );
    let evs = events(&s.logs);
    let Some(Event::GovernanceProposed { pending, eta_slot, eta_ts, .. }) = evs.first() else {
        panic!("no GovernanceProposed in {:?}", s.logs);
    };
    assert_eq!(*pending, successor.pubkey());
    let c = *bytemuck_view::<state::Config>(&rpc.account(&CONFIG).unwrap().data);
    assert_eq!(c.pending_governance, successor.pubkey().to_bytes());
    assert_eq!(c.pending_governance_eta_slot.get(), *eta_slot);
    assert_eq!(c.pending_governance_eta_ts.get(), *eta_ts);
    assert!(*eta_slot >= slot + TIMELOCK_SLOTS && *eta_slot < slot + TIMELOCK_SLOTS + 200);
    let (_, now) = rpc.clock();
    assert!((*eta_ts - now - TIMELOCK_SECS).abs() < 120, "eta_ts {eta_ts} vs now {now}");
    refused(
        "accept_governance before the timelock",
        rpc.send(&successor, &[ix_accept_governance(&successor.pubkey())], &[]),
        0,
        HdError::TimelockNotElapsed,
    );
    refused(
        "accept_governance by a key that was not named",
        rpc.send(&crank, &[ix_accept_governance(&crank.pubkey())], &[]),
        0,
        HdError::Unauthorized,
    );
    // The emergency pause is immediate while the rotation is pending.
    sent(
        "propose_config paused = 1 (immediate) during the pending rotation",
        rpc.send(
            &governance,
            &[ix_propose(&governance.pubkey(), &registrar.pubkey(), CRANK_FEE, 0, 1)],
            &[],
        ),
    );
    let c = *bytemuck_view::<state::Config>(&rpc.account(&CONFIG).unwrap().data);
    assert_eq!(c.paused, 1);
    assert_eq!(c.governance, governance.pubkey().to_bytes());
    refused(
        "apply_config before the timelock",
        rpc.send(&crank, &[ix_apply()], &[]),
        0,
        HdError::TimelockNotElapsed,
    );
    refused(
        "cancel_governance by the successor",
        rpc.send(&successor, &[ix_cancel_governance(&successor.pubkey())], &[]),
        0,
        HdError::Unauthorized,
    );
    let s = sent(
        "cancel_governance",
        rpc.send(&governance, &[ix_cancel_governance(&governance.pubkey())], &[]),
    );
    assert!(matches!(
        events(&s.logs).first(),
        Some(Event::GovernanceCancelled { .. })
    ));
    let c = *bytemuck_view::<state::Config>(&rpc.account(&CONFIG).unwrap().data);
    assert_eq!(c.pending_governance, [0u8; 32]);
    assert_eq!(c.pending_governance_eta_slot.get(), 0);
    assert_eq!(c.pending_governance_eta_ts.get(), 0);
    println!("SMOKE PASSED: SBPFv{sbpf} heads_down on a real validator");
}
