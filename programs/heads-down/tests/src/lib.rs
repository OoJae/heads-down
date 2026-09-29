//! LiteSVM harness for the heads_down fork suite.
//!
//! * The **live mainnet ORE program** and entropy program plus the real
//!   Board, Config, Treasury, Var and current Round (tests/fixtures, from
//!   `fetch-fixtures.sh`) make a local mainnet fork.
//! * heads_down is loaded at its real program id through the upgradeable
//!   loader, with a test key written into its ProgramData as upgrade authority.
//! * The secp256r1 and ed25519 precompiles are the real Agave ones
//!   (`litesvm` feature `precompiles`).
//! * Heartbeats are signed the way Android Keystore signs
//!   (`SHA256withECDSA` → DER → low-S raw), with p256.
//!
//! This file is also the reference transaction builder ("client") for every
//! instruction, built on the program crate's own preimage builders.
#![allow(clippy::too_many_arguments)]

use std::{
    path::{Path, PathBuf},
    str::FromStr,
};

use base64::Engine;
pub use heads_down as hd;
use heads_down::{
    instructions::{HeartbeatEntry, NO_HEARTBEAT},
    message::{self, Plan},
    state,
};
use litesvm::{types::TransactionMetadata, LiteSVM};
use p256::ecdsa::{signature::Signer as _, DerSignature, SigningKey};
use p256_introspect::client::{build_instruction_data, der_to_low_s_raw, SignatureInput};
pub use solana_account::Account;
pub use solana_address::Address;
pub use solana_instruction::{AccountMeta, Instruction};
pub use solana_instruction_error::InstructionError;
pub use solana_keypair::Keypair;
pub use solana_signer::Signer;
use solana_transaction::Transaction;
pub use solana_transaction_error::TransactionError;

// ---- ids --------------------------------------------------------------------

/// heads_down program id.
pub const HD: Address = hd::ID;
/// ORE program.
pub const ORE: Address = hd::ore::ORE_PROGRAM_ID;
/// ORE Board.
pub const BOARD: Address = hd::ore::BOARD_ADDRESS;
/// ORE Config.
pub const ORE_CONFIG: Address = hd::ore::CONFIG_ADDRESS;
/// ORE Treasury.
pub const TREASURY: Address = hd::ore::TREASURY_ADDRESS;
/// ORE entropy Var.
pub const VAR: Address = hd::ore::VAR_ADDRESS;
/// Entropy program.
pub const ENTROPY: Address = hd::ore::ENTROPY_PROGRAM_ID;
/// System program.
pub const SYSTEM: Address = hd::ore::SYSTEM_PROGRAM_ID;
/// Config PDA.
pub const CONFIG: Address = hd::CONFIG_ID;
/// Executor PDA.
pub const EXECUTOR: Address = hd::EXECUTOR_ID;
/// Ed25519SigVerify.
pub const ED25519: Address = hd::ed25519::ED25519_PROGRAM_ID;
/// BPF upgradeable loader.
pub const LOADER_V3: Address = hd::instructions::initialize_config::BPF_LOADER_UPGRADEABLE_ID;

/// Secp256r1SigVerify.
pub fn secp256r1_id() -> Address {
    Address::new_from_array(p256_introspect::SECP256R1_PROGRAM_ID.to_bytes())
}
/// Instructions sysvar.
pub fn ix_sysvar_id() -> Address {
    Address::new_from_array(p256_introspect::INSTRUCTIONS_SYSVAR_ID.to_bytes())
}
/// ComputeBudget program.
pub fn compute_budget_id() -> Address {
    Address::from_str("ComputeBudget111111111111111111111111111111").unwrap()
}
/// Token-2022.
pub fn token_2022_id() -> Address {
    Address::new_from_array(sgt_verify::TOKEN_2022_PROGRAM_ID.to_bytes())
}

// ---- economics used by most tests -------------------------------------------

/// Lamports per SOL.
pub const SOL: u64 = 1_000_000_000;
/// Discretionary fee every Automation must use (`config.executor_fee`).
pub const EXECUTOR_FEE: u64 = 5_000;
/// Reimbursed to the cranker per real dig (`config.crank_fee`).
pub const CRANK_FEE: u64 = 4_000;
/// ORE per-square cap set by the app (`automation.amount`).
pub const TILE_CAP: u64 = 100_000;
/// Fixed unix time the suite starts at (2026-09-29T00:00:00Z).
pub const T0: i64 = 1_790_640_000;
/// ORE CHECKPOINT_FEE.
pub const CHECKPOINT_FEE: u64 = hd::ore::CHECKPOINT_FEE;

// ---- paths ------------------------------------------------------------------

/// `programs/heads-down`.
pub fn root() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("..")
}
/// `tests/fixtures`.
pub fn fixtures() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("fixtures")
}
/// `crates/sgt-verify/fixtures`.
pub fn sgt_fixtures() -> PathBuf {
    root().join("../../crates/sgt-verify/fixtures")
}

/// Which heads_down binary.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Build {
    /// `--features mainnet` (real SGT anchors).
    Mainnet,
    /// `--features devnet` (public test SGT anchors).
    Devnet,
}

/// Path of the built `.so`.
pub fn so_path(build: Build) -> PathBuf {
    let dir = match build {
        Build::Mainnet => "deploy",
        Build::Devnet => "deploy-devnet",
    };
    root().join("target").join(dir).join("heads_down.so")
}

fn read_json(path: &Path) -> serde_json::Value {
    let raw = std::fs::read_to_string(path).unwrap_or_else(|_| {
        panic!(
            "missing {} — run tests/fixtures/fetch-fixtures.sh",
            path.display()
        )
    });
    serde_json::from_str(&raw).unwrap()
}

/// Load `<address>.json` (solana CLI `--output json-compact`).
pub fn load_fixture_account(path: &Path) -> Account {
    let v = read_json(path);
    let a = &v["account"];
    Account {
        lamports: a["lamports"].as_u64().unwrap(),
        data: base64::engine::general_purpose::STANDARD
            .decode(a["data"][0].as_str().unwrap())
            .unwrap(),
        owner: Address::from_str(a["owner"].as_str().unwrap()).unwrap(),
        executable: false,
        rent_epoch: u64::MAX,
    }
}

/// Read a little-endian u64.
pub fn u64_at(d: &[u8], off: usize) -> u64 {
    u64::from_le_bytes(d[off..off + 8].try_into().unwrap())
}

// ---- results ------------------------------------------------------------------

/// A failed transaction.
#[derive(Debug, Clone)]
pub struct Failure {
    /// Error.
    pub err: TransactionError,
    /// Logs.
    pub logs: Vec<String>,
}

/// Result of `send`.
pub type TxResult = Result<TransactionMetadata, Failure>;

/// The transaction must fail at instruction `ix` with `Custom(code)`.
#[track_caller]
pub fn assert_custom(res: &TxResult, ix: u8, code: u32) {
    match res {
        Ok(m) => panic!(
            "expected Custom({code:#x}) at ix {ix}, got success\n{}",
            m.pretty_logs()
        ),
        Err(f) => assert_eq!(
            f.err,
            TransactionError::InstructionError(ix, InstructionError::Custom(code)),
            "logs:\n{}",
            f.logs.join("\n")
        ),
    }
}

/// The transaction must fail at instruction `ix` with heads_down error `e`.
#[track_caller]
pub fn assert_hd(res: &TxResult, ix: u8, e: hd::error::HdError) {
    assert_custom(res, ix, e.code());
}

/// The transaction must fail at instruction `ix` with `err`.
#[track_caller]
pub fn assert_ix_err(res: &TxResult, ix: u8, err: InstructionError) {
    match res {
        Ok(m) => panic!(
            "expected {err:?} at ix {ix}, got success\n{}",
            m.pretty_logs()
        ),
        Err(f) => assert_eq!(
            f.err,
            TransactionError::InstructionError(ix, err),
            "logs:\n{}",
            f.logs.join("\n")
        ),
    }
}

/// Unwrap a success, printing logs on failure.
#[track_caller]
pub fn ok(res: TxResult) -> TransactionMetadata {
    match res {
        Ok(m) => m,
        Err(f) => panic!("transaction failed: {:?}\n{}", f.err, f.logs.join("\n")),
    }
}

// ---- events -------------------------------------------------------------------

/// A decoded heads_down event.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Event {
    /// RigDug.
    RigDug {
        /// Rig.
        rig: Address,
        /// Round.
        round_id: u64,
        /// Lamports on tiles.
        lamports: u64,
        /// Tile mask.
        mask: u32,
        /// Pot-adjusted cost.
        ema_ev: u64,
    },
    /// RigSkipped.
    RigSkipped {
        /// Rig.
        rig: Address,
        /// Round.
        round_id: u64,
        /// Error code.
        error: u32,
    },
    /// ShiftArmed.
    ShiftArmed {
        /// Rig.
        rig: Address,
        /// Shift.
        shift_id: u64,
    },
    /// ShiftEnded.
    ShiftEnded {
        /// Rig.
        rig: Address,
        /// Shift.
        shift_id: u64,
        /// Dark rounds.
        dark_rounds: u64,
        /// Rounds dug.
        rounds_dug: u64,
        /// Lamports.
        lamports: u64,
        /// Reason.
        reason: u8,
    },
    /// SeekerVerified.
    SeekerVerified {
        /// Rig.
        rig: Address,
        /// Mint.
        sgt_mint: Address,
        /// Member number.
        member_number: u64,
    },
}

fn addr_at(d: &[u8], off: usize) -> Address {
    Address::new_from_array(d[off..off + 32].try_into().unwrap())
}

fn decode_event(d: &[u8]) -> Option<Event> {
    use hd::events::tag;
    Some(match (d.first()?, d.len()) {
        (&tag::RIG_DUG, 61) => Event::RigDug {
            rig: addr_at(d, 1),
            round_id: u64_at(d, 33),
            lamports: u64_at(d, 41),
            mask: u32::from_le_bytes(d[49..53].try_into().unwrap()),
            ema_ev: u64_at(d, 53),
        },
        (&tag::RIG_SKIPPED, 45) => Event::RigSkipped {
            rig: addr_at(d, 1),
            round_id: u64_at(d, 33),
            error: u32::from_le_bytes(d[41..45].try_into().unwrap()),
        },
        (&tag::SHIFT_ARMED, 41) => Event::ShiftArmed {
            rig: addr_at(d, 1),
            shift_id: u64_at(d, 33),
        },
        (&tag::SHIFT_ENDED, 66) => Event::ShiftEnded {
            rig: addr_at(d, 1),
            shift_id: u64_at(d, 33),
            dark_rounds: u64_at(d, 41),
            rounds_dug: u64_at(d, 49),
            lamports: u64_at(d, 57),
            reason: d[65],
        },
        (&tag::SEEKER_VERIFIED, 73) => Event::SeekerVerified {
            rig: addr_at(d, 1),
            sgt_mint: addr_at(d, 33),
            member_number: u64_at(d, 65),
        },
        _ => return None,
    })
}

/// heads_down events in `logs`, attributing `Program data:` lines to the
/// innermost executing program so ORE's or anyone else's are ignored.
pub fn events(logs: &[String]) -> Vec<Event> {
    let hd_id = HD.to_string();
    let mut stack: Vec<String> = Vec::new();
    let mut out = Vec::new();
    for line in logs {
        if let Some(rest) = line.strip_prefix("Program ") {
            if let Some(idx) = rest.find(" invoke [") {
                stack.push(rest[..idx].to_string());
                continue;
            }
            if rest.ends_with(" success") || rest.contains(" failed") {
                stack.pop();
                continue;
            }
            if let Some(b64) = rest.strip_prefix("data: ") {
                if stack.last() == Some(&hd_id) {
                    for part in b64.split(' ') {
                        let d = base64::engine::general_purpose::STANDARD
                            .decode(part)
                            .unwrap();
                        if let Some(e) = decode_event(&d) {
                            out.push(e);
                        }
                    }
                }
            }
        }
    }
    out
}

/// `RigSkipped` error code for `rig`, if any.
pub fn skipped_code(evs: &[Event], rig: &Address) -> Option<u32> {
    evs.iter().find_map(|e| match e {
        Event::RigSkipped { rig: r, error, .. } if r == rig => Some(*error),
        _ => None,
    })
}

/// `RigDug` for `rig`, if any.
pub fn dug(evs: &[Event], rig: &Address) -> Option<(u64, u32)> {
    evs.iter().find_map(|e| match e {
        Event::RigDug {
            rig: r,
            lamports,
            mask,
            ..
        } if r == rig => Some((*lamports, *mask)),
        _ => None,
    })
}

// ---- the fork -------------------------------------------------------------------

/// A mainnet fork with heads_down loaded.
pub struct Env {
    /// The VM.
    pub svm: LiteSVM,
    /// Crank key (also the default fee payer).
    pub cranker: Keypair,
    /// heads_down upgrade authority.
    pub upgrade_authority: Keypair,
    /// Config governance.
    pub governance: Keypair,
    /// Registrar (Ed25519 attestation key).
    pub registrar: Keypair,
    /// Current ORE Round PDA (fixture).
    pub round: Address,
    /// `Board.round_id`.
    pub board_round: u64,
    /// `Board.start_slot`.
    pub start_slot: u64,
    /// `Board.production_cost_ema`.
    pub ema: u64,
    /// `Treasury.motherlode`.
    pub pot: u64,
    /// Current unix time.
    pub now: i64,
    /// Current slot.
    pub slot: u64,
}

impl Env {
    /// Mainnet build, sigverify on, config initialized.
    pub fn new() -> Self {
        Self::build(Build::Mainnet, true, true)
    }

    /// Full control.
    pub fn build(build: Build, sigverify: bool, init: bool) -> Self {
        let mut svm = LiteSVM::new().with_sigverify(sigverify);
        let f = fixtures();
        svm.add_program(
            ORE,
            &std::fs::read(f.join("ore.so")).expect("run fetch-fixtures.sh"),
        )
        .unwrap();
        svm.add_program(ENTROPY, &std::fs::read(f.join("entropy.so")).unwrap())
            .unwrap();
        let so = so_path(build);
        svm.add_program(
            HD,
            &std::fs::read(&so)
                .unwrap_or_else(|_| panic!("{} missing: run scripts/build.sh", so.display())),
        )
        .unwrap();

        let round_addr = std::fs::read_to_string(f.join("round_address.txt")).unwrap();
        let round = Address::from_str(round_addr.trim()).unwrap();
        for a in [BOARD, ORE_CONFIG, TREASURY, VAR, round] {
            svm.set_account(a, load_fixture_account(&f.join(format!("{a}.json"))))
                .unwrap();
        }
        let board = svm.get_account(&BOARD).unwrap().data;
        let treasury = svm.get_account(&TREASURY).unwrap().data;

        let upgrade_authority = Keypair::new();
        let cranker = Keypair::new();
        let governance = Keypair::new();
        let registrar = Keypair::new();
        for k in [&upgrade_authority, &cranker, &governance] {
            svm.airdrop(&k.pubkey(), 100 * SOL).unwrap();
        }

        let mut env = Env {
            svm,
            cranker,
            upgrade_authority,
            governance,
            registrar,
            round,
            board_round: u64_at(&board, 8),
            start_slot: u64_at(&board, 16),
            ema: u64_at(&board, 32),
            pot: u64_at(&treasury, 8),
            now: T0,
            slot: 0,
        };
        env.set_program_upgrade_authority(Some(env.upgrade_authority.pubkey()));
        // Inside the live round window captured in the fixture.
        env.set_clock(env.start_slot + 10, T0);
        // Fund the Executor PDA float (data-less, System-owned).
        let ix = system_transfer(&env.cranker.pubkey(), &EXECUTOR, SOL / 100);
        ok(env.send(&[ix], &[]));
        if init {
            ok(env.initialize_config());
        }
        env
    }

    /// Rewrite heads_down's ProgramData upgrade authority.
    pub fn set_program_upgrade_authority(&mut self, authority: Option<Address>) {
        let (pd, _) = Address::find_program_address(&[HD.as_ref()], &LOADER_V3);
        let mut acc = self.svm.get_account(&pd).unwrap();
        match authority {
            Some(a) => {
                acc.data[12] = 1;
                acc.data[13..45].copy_from_slice(a.as_ref());
            }
            None => {
                acc.data[12] = 0;
                acc.data[13..45].fill(0);
            }
        }
        self.svm.set_account(pd, acc).unwrap();
    }

    /// heads_down's ProgramData address.
    pub fn program_data() -> Address {
        Address::find_program_address(&[HD.as_ref()], &LOADER_V3).0
    }

    /// Set the Clock sysvar.
    pub fn set_clock(&mut self, slot: u64, unix_timestamp: i64) {
        let mut c: solana_clock::Clock = self.svm.get_sysvar();
        c.slot = slot;
        c.unix_timestamp = unix_timestamp;
        self.svm.set_sysvar(&c);
        self.slot = slot;
        self.now = unix_timestamp;
    }

    /// Move the wall clock (same slot).
    pub fn advance_time(&mut self, secs: i64) {
        self.set_clock(self.slot, self.now + secs);
    }

    /// Send `ixs` with the cranker as fee payer plus `signers`.
    pub fn send(&mut self, ixs: &[Instruction], signers: &[&Keypair]) -> TxResult {
        let cranker = self.cranker.insecure_clone();
        self.send_as(&cranker, ixs, signers)
    }

    /// Send with an explicit fee payer.
    pub fn send_as(
        &mut self,
        payer: &Keypair,
        ixs: &[Instruction],
        signers: &[&Keypair],
    ) -> TxResult {
        let mut all: Vec<&Keypair> = vec![payer];
        for s in signers {
            if s.pubkey() != payer.pubkey() {
                all.push(s);
            }
        }
        let tx = Transaction::new_signed_with_payer(
            ixs,
            Some(&payer.pubkey()),
            &all,
            self.svm.latest_blockhash(),
        );
        let res = self.svm.send_transaction(tx).map_err(|f| Failure {
            err: f.err,
            logs: f.meta.logs,
        });
        self.svm.expire_blockhash();
        res
    }

    /// Account (panics if missing).
    pub fn account(&self, a: &Address) -> Account {
        self.svm
            .get_account(a)
            .unwrap_or_else(|| panic!("account {a} missing"))
    }

    /// Lamports (0 if missing).
    pub fn lamports(&self, a: &Address) -> u64 {
        self.svm.get_account(a).map(|x| x.lamports).unwrap_or(0)
    }

    /// Decode a Rig.
    pub fn rig(&self, a: &Address) -> state::Rig {
        *bytemuck_view::<state::Rig>(&self.account(a).data)
    }

    /// Decode the Config.
    pub fn config(&self) -> state::Config {
        *bytemuck_view::<state::Config>(&self.account(&CONFIG).data)
    }

    /// Decode a SeekerSeat.
    pub fn seat(&self, a: &Address) -> state::SeekerSeat {
        *bytemuck_view::<state::SeekerSeat>(&self.account(a).data)
    }

    /// Decode a ShiftLog.
    pub fn shift_log(&self, a: &Address) -> state::ShiftLog {
        *bytemuck_view::<state::ShiftLog>(&self.account(a).data)
    }

    /// `initialize_config` signed by the upgrade authority with the suite's
    /// fees and the correct layout hash.
    pub fn initialize_config(&mut self) -> TxResult {
        let ua = self.upgrade_authority.insecure_clone();
        let ix = ix_initialize_config(
            &ua.pubkey(),
            &self.governance.pubkey(),
            &self.registrar.pubkey(),
            CRANK_FEE,
            EXECUTOR_FEE,
            0,
            hd::ore::layout_hash(),
        );
        self.send_as(&ua, &[ix], &[])
    }

    /// Live `Round.deployed[25]`.
    pub fn round_deployed(&self) -> [u64; 25] {
        let d = self.account(&self.round).data;
        std::array::from_fn(|i| u64_at(&d, 16 + 8 * i))
    }

    /// The Motherlode-aware cost for the fixture.
    pub fn ema_ev(&self) -> u64 {
        hd::logic::ema_ev(self.ema, self.pot) as u64
    }
}

impl Default for Env {
    fn default() -> Self {
        Self::new()
    }
}

/// View raw account bytes as a heads_down struct (exact length).
pub fn bytemuck_view<T: hd::state::Account>(data: &[u8]) -> &T {
    hd::state::view::<T>(data).expect("account length")
}

// ---- users / rigs ------------------------------------------------------------

/// A user: wallet + Keystore-like P-256 key + rig PDA.
pub struct User {
    /// Wallet (ORE Automation authority).
    pub wallet: Keypair,
    /// "Keystore" key.
    pub key: SigningKey,
    /// Rig PDA.
    pub rig: Address,
    /// Next P-256 counter to use.
    pub counter: u64,
}

impl User {
    /// A funded user with a deterministic P-256 key from `seed`.
    pub fn new(env: &mut Env, seed: u8) -> Self {
        let wallet = Keypair::new();
        env.svm.airdrop(&wallet.pubkey(), 10 * SOL).unwrap();
        Self::from_wallet(wallet, seed)
    }

    /// Wrap an existing wallet.
    pub fn from_wallet(wallet: Keypair, seed: u8) -> Self {
        let mut sk = [0x11u8; 32];
        sk[0] = seed.max(1);
        let key = SigningKey::from_slice(&sk).unwrap();
        let rig = rig_pda(&wallet.pubkey());
        Self {
            wallet,
            key,
            rig,
            counter: 0,
        }
    }

    /// Wallet address.
    pub fn pubkey(&self) -> Address {
        self.wallet.pubkey()
    }

    /// 33-byte compressed P-256 key.
    pub fn p256(&self) -> [u8; 33] {
        compressed(&self.key)
    }

    /// ORE Automation PDA.
    pub fn automation(&self) -> Address {
        automation_pda(&self.pubkey())
    }

    /// ORE Miner PDA.
    pub fn miner(&self) -> Address {
        miner_pda(&self.pubkey())
    }

    /// Next counter.
    pub fn next_counter(&mut self) -> u64 {
        self.counter += 1;
        self.counter
    }

    /// Sign `digest` the Keystore way (SHA256withECDSA, DER, low-S).
    pub fn sign(&self, digest: &[u8; 32]) -> [u8; 64] {
        keystore_sign(&self.key, digest)
    }

    /// A signed heartbeat for `(shift_id, round, lease)` with the next counter.
    pub fn heartbeat(&mut self, shift_id: u64, round: u64, lease: u8) -> Heartbeat {
        let counter = self.next_counter();
        self.heartbeat_with(counter, shift_id, round, lease)
    }

    /// A signed heartbeat with an explicit counter.
    pub fn heartbeat_with(&self, counter: u64, shift_id: u64, round: u64, lease: u8) -> Heartbeat {
        let pre = message::heartbeat_preimage(&self.rig, counter, shift_id, round, lease);
        let digest = message::digest(&pre);
        Heartbeat {
            counter,
            round,
            lease,
            pubkey: self.p256(),
            digest,
            sig: self.sign(&digest),
        }
    }
}

/// A signed heartbeat ready for the precompile.
#[derive(Clone, Copy, Debug)]
pub struct Heartbeat {
    /// Counter.
    pub counter: u64,
    /// Round signed for.
    pub round: u64,
    /// Lease requested.
    pub lease: u8,
    /// Signing key (compressed).
    pub pubkey: [u8; 33],
    /// The 32-byte message.
    pub digest: [u8; 32],
    /// Low-S r||s.
    pub sig: [u8; 64],
}

/// Deterministic Keystore-style signing.
pub fn keystore_sign(sk: &SigningKey, msg: &[u8]) -> [u8; 64] {
    let der: DerSignature = sk.sign(msg);
    der_to_low_s_raw(der.as_bytes()).unwrap()
}

/// Compressed SEC1 key.
pub fn compressed(sk: &SigningKey) -> [u8; 33] {
    sk.verifying_key()
        .to_sec1_point(true)
        .as_bytes()
        .try_into()
        .unwrap()
}

/// Rig PDA.
pub fn rig_pda(authority: &Address) -> Address {
    Address::find_program_address(&[hd::RIG_SEED, authority.as_ref()], &HD).0
}
/// Seat PDA.
pub fn seat_pda(mint: &Address) -> Address {
    Address::find_program_address(&[hd::SEEKER_SEED, mint.as_ref()], &HD).0
}
/// ShiftLog PDA.
pub fn shift_log_pda(rig: &Address, shift_id: u64) -> Address {
    Address::find_program_address(
        &[hd::SHIFT_SEED, rig.as_ref(), &shift_id.to_le_bytes()],
        &HD,
    )
    .0
}
/// ORE Automation PDA.
pub fn automation_pda(authority: &Address) -> Address {
    Address::find_program_address(&[b"automation", authority.as_ref()], &ORE).0
}
/// ORE Miner PDA.
pub fn miner_pda(authority: &Address) -> Address {
    Address::find_program_address(&[b"miner", authority.as_ref()], &ORE).0
}
/// ORE Round PDA.
pub fn round_pda(id: u64) -> Address {
    Address::find_program_address(&[b"round", &id.to_le_bytes()], &ORE).0
}

// ---- generic instruction builders --------------------------------------------

/// System transfer.
pub fn system_transfer(from: &Address, to: &Address, lamports: u64) -> Instruction {
    let mut data = 2u32.to_le_bytes().to_vec();
    data.extend_from_slice(&lamports.to_le_bytes());
    Instruction {
        program_id: SYSTEM,
        accounts: vec![AccountMeta::new(*from, true), AccountMeta::new(*to, false)],
        data,
    }
}

/// ComputeBudget SetComputeUnitLimit.
pub fn compute_limit(units: u32) -> Instruction {
    let mut data = vec![2u8];
    data.extend_from_slice(&units.to_le_bytes());
    Instruction {
        program_id: compute_budget_id(),
        accounts: vec![],
        data,
    }
}

/// Secp256r1SigVerify over `(sig, pubkey, msg)` entries (indices = 0xFFFF).
pub fn secp_ix(entries: &[([u8; 64], [u8; 33], Vec<u8>)]) -> Instruction {
    let inputs: Vec<SignatureInput> = entries
        .iter()
        .map(|(s, p, m)| SignatureInput {
            signature: *s,
            public_key: *p,
            message: m,
        })
        .collect();
    Instruction {
        program_id: secp256r1_id(),
        accounts: vec![],
        data: build_instruction_data(&inputs).unwrap(),
    }
}

/// Secp256r1SigVerify for heartbeats.
pub fn secp_ix_for(hbs: &[Heartbeat]) -> Instruction {
    let e: Vec<_> = hbs
        .iter()
        .map(|h| (h.sig, h.pubkey, h.digest.to_vec()))
        .collect();
    secp_ix(&e)
}

/// Ed25519SigVerify for one `(pubkey, sig, msg)` with every index 0xFFFF
/// (or `index` when given).
pub fn ed25519_ix(
    pubkey: &[u8; 32],
    sig: &[u8; 64],
    msg: &[u8],
    index: Option<u16>,
) -> Instruction {
    let ix = index.unwrap_or(u16::MAX);
    let (pk_off, sig_off, msg_off) = (16u16, 48u16, 112u16);
    let mut data = vec![1u8, 0];
    for v in [sig_off, ix, pk_off, ix, msg_off, msg.len() as u16, ix] {
        data.extend_from_slice(&v.to_le_bytes());
    }
    data.extend_from_slice(pubkey);
    data.extend_from_slice(sig);
    data.extend_from_slice(msg);
    Instruction {
        program_id: ED25519,
        accounts: vec![],
        data,
    }
}

// ---- ORE instruction builders --------------------------------------------------

/// ORE AutomateV2 (tag 0): Discretionary executor with a fixed fee.
pub fn ore_automate(
    wallet: &Address,
    executor: &Address,
    amount: u64,
    deposit: u64,
    fee: u64,
    strategy: u8,
    min_motherlode: u16,
    max_motherlode: u16,
) -> Instruction {
    let mut data = vec![0u8];
    data.extend_from_slice(&amount.to_le_bytes());
    data.extend_from_slice(&deposit.to_le_bytes());
    data.extend_from_slice(&fee.to_le_bytes());
    data.extend_from_slice(&0u64.to_le_bytes()); // mask
    data.push(strategy);
    data.extend_from_slice(&0u64.to_le_bytes()); // reload
    data.extend_from_slice(&u64::MAX.to_le_bytes()); // max_production_cost (not enforced by ORE)
    data.extend_from_slice(&min_motherlode.to_le_bytes());
    data.extend_from_slice(&max_motherlode.to_le_bytes());
    data.extend_from_slice(&0u16.to_le_bytes()); // split_tiles
    data.extend_from_slice(&0u16.to_le_bytes()); // solo_tiles
    data.extend_from_slice(&0u64.to_le_bytes()); // buffer
    assert_eq!(data.len(), 66);
    Instruction {
        program_id: ORE,
        accounts: vec![
            AccountMeta::new(*wallet, true),
            AccountMeta::new(automation_pda(wallet), false),
            AccountMeta::new(*executor, false),
            AccountMeta::new(miner_pda(wallet), false),
            AccountMeta::new_readonly(SYSTEM, false),
        ],
        data,
    }
}

/// The standard Automation for the suite: Discretionary, fee = EXECUTOR_FEE,
/// executor = Executor PDA, per-square cap TILE_CAP.
pub fn ore_automate_default(wallet: &Address, deposit: u64) -> Instruction {
    ore_automate(
        wallet,
        &EXECUTOR,
        TILE_CAP,
        deposit,
        EXECUTOR_FEE,
        2,
        0,
        u16::MAX,
    )
}

/// Automation fields.
#[derive(Clone, Copy, Debug)]
pub struct AutomationView {
    /// amount.
    pub amount: u64,
    /// balance.
    pub balance: u64,
    /// executor.
    pub executor: Address,
    /// fee.
    pub fee: u64,
    /// strategy.
    pub strategy: u64,
}

impl Env {
    /// Read an Automation (None if closed).
    pub fn automation(&self, a: &Address) -> Option<AutomationView> {
        let acc = self.svm.get_account(a)?;
        if acc.owner != ORE || acc.data.len() != 160 {
            return None;
        }
        let d = acc.data;
        Some(AutomationView {
            amount: u64_at(&d, 8),
            balance: u64_at(&d, 48),
            executor: addr_at(&d, 56),
            fee: u64_at(&d, 88),
            strategy: u64_at(&d, 96),
        })
    }

    /// Miner `deployed[25]` and `round_id`.
    pub fn miner_deployed(&self, a: &Address) -> ([u64; 25], u64) {
        let d = self.account(a).data;
        (
            std::array::from_fn(|i| u64_at(&d, 64 + 8 * i)),
            u64_at(&d, 664),
        )
    }

    /// Overwrite a u64 inside an account (fixture surgery).
    pub fn poke_u64(&mut self, a: &Address, off: usize, v: u64) {
        let mut acc = self.account(a);
        acc.data[off..off + 8].copy_from_slice(&v.to_le_bytes());
        self.svm.set_account(*a, acc).unwrap();
    }
}

// ---- heads_down instruction builders ---------------------------------------------

/// Caps for `set_caps`.
#[derive(Clone, Copy, Debug)]
pub struct Caps {
    /// cap_week.
    pub week: u64,
    /// cap_shift.
    pub shift: u64,
    /// cap_round.
    pub round: u64,
    /// cap_max_cost.
    pub max_cost: u64,
    /// caps_expiry_ts.
    pub expiry: i64,
}

impl Caps {
    /// Generous defaults for the suite.
    pub fn standard() -> Self {
        Self {
            week: SOL / 10,
            shift: SOL / 50,
            round: 2_000_000,
            max_cost: SOL,
            expiry: T0 + 7 * 86_400,
        }
    }
}

/// A plan the gate opens for (fixture ema_ev ≈ 0.63 SOL/ORE): 0.001 SOL on
/// the 10 least-crowded split tiles, lease 3, window around T0.
pub fn standard_plan() -> Plan {
    Plan {
        max_ev_cost: 700_000_000,
        dig_lamports: 1_000_000,
        split: 10,
        solo: 0,
        lease: 3,
        flags: 0,
        window_start: T0 - 3_600,
        window_end: T0 + 8 * 3_600,
    }
}

/// `initialize_config`.
pub fn ix_initialize_config(
    authority: &Address,
    governance: &Address,
    registrar: &Address,
    crank_fee: u64,
    executor_fee: u64,
    bury_bps: u16,
    layout_hash: [u8; 32],
) -> Instruction {
    let mut data = vec![hd::tag::INITIALIZE_CONFIG];
    data.extend_from_slice(governance.as_ref());
    data.extend_from_slice(registrar.as_ref());
    data.extend_from_slice(&crank_fee.to_le_bytes());
    data.extend_from_slice(&executor_fee.to_le_bytes());
    data.extend_from_slice(&bury_bps.to_le_bytes());
    data.extend_from_slice(&layout_hash);
    Instruction {
        program_id: HD,
        accounts: vec![
            AccountMeta::new(*authority, true),
            AccountMeta::new(CONFIG, false),
            AccountMeta::new_readonly(Env::program_data(), false),
            AccountMeta::new_readonly(SYSTEM, false),
        ],
        data,
    }
}

/// Registrar attestation carried in `register_rig` / `rotate_key`.
#[derive(Clone, Copy, Debug)]
pub struct AttestationArg {
    /// Index of the Ed25519SigVerify instruction.
    pub ix: u8,
    /// Entry.
    pub sig: u8,
    /// Level.
    pub level: u8,
    /// Expiry slot.
    pub expiry_slot: u64,
}

fn push_attestation(data: &mut Vec<u8>, att: Option<AttestationArg>) {
    match att {
        None => data.push(0),
        Some(a) => {
            data.push(1);
            data.push(a.ix);
            data.push(a.sig);
            data.push(a.level);
            data.extend_from_slice(&a.expiry_slot.to_le_bytes());
        }
    }
}

/// `register_rig`.
pub fn ix_register_rig(
    authority: &Address,
    p256: &[u8; 33],
    att: Option<AttestationArg>,
) -> Instruction {
    let mut data = vec![hd::tag::REGISTER_RIG];
    data.extend_from_slice(p256);
    push_attestation(&mut data, att);
    let mut accounts = vec![
        AccountMeta::new(*authority, true),
        AccountMeta::new(rig_pda(authority), false),
        AccountMeta::new_readonly(CONFIG, false),
        AccountMeta::new_readonly(SYSTEM, false),
    ];
    if att.is_some() {
        accounts.push(AccountMeta::new_readonly(ix_sysvar_id(), false));
    }
    Instruction {
        program_id: HD,
        accounts,
        data,
    }
}

/// `rotate_key`.
pub fn ix_rotate_key(
    authority: &Address,
    p256: &[u8; 33],
    att: Option<AttestationArg>,
) -> Instruction {
    let mut data = vec![hd::tag::ROTATE_KEY];
    data.extend_from_slice(p256);
    push_attestation(&mut data, att);
    let mut accounts = vec![
        AccountMeta::new_readonly(*authority, true),
        AccountMeta::new(rig_pda(authority), false),
        AccountMeta::new_readonly(CONFIG, false),
    ];
    if att.is_some() {
        accounts.push(AccountMeta::new_readonly(ix_sysvar_id(), false));
    }
    Instruction {
        program_id: HD,
        accounts,
        data,
    }
}

/// `set_caps`.
pub fn ix_set_caps(authority: &Address, caps: Caps) -> Instruction {
    let mut data = vec![hd::tag::SET_CAPS];
    for v in [caps.week, caps.shift, caps.round, caps.max_cost] {
        data.extend_from_slice(&v.to_le_bytes());
    }
    data.extend_from_slice(&caps.expiry.to_le_bytes());
    Instruction {
        program_id: HD,
        accounts: vec![
            AccountMeta::new_readonly(*authority, true),
            AccountMeta::new(rig_pda(authority), false),
        ],
        data,
    }
}

fn push_plan(data: &mut Vec<u8>, p: &Plan) {
    data.extend_from_slice(&p.max_ev_cost.to_le_bytes());
    data.extend_from_slice(&p.dig_lamports.to_le_bytes());
    data.extend_from_slice(&[p.split, p.solo, p.lease, p.flags]);
    data.extend_from_slice(&p.window_start.to_le_bytes());
    data.extend_from_slice(&p.window_end.to_le_bytes());
}

/// `arm_shift` signed by the wallet.
pub fn ix_arm_wallet(authority: &Address, plan: &Plan) -> Instruction {
    let mut data = vec![hd::tag::ARM_SHIFT, 0];
    push_plan(&mut data, plan);
    Instruction {
        program_id: HD,
        accounts: vec![
            AccountMeta::new(rig_pda(authority), false),
            AccountMeta::new_readonly(*authority, true),
            AccountMeta::new_readonly(BOARD, false),
        ],
        data,
    }
}

/// `arm_shift` authorized by a P-256 PLAN at `(ix, sig)`.
pub fn ix_arm_p256(authority: &Address, plan: &Plan, counter: u64, ix: u8, sig: u8) -> Instruction {
    let mut data = vec![hd::tag::ARM_SHIFT, 1];
    push_plan(&mut data, plan);
    data.extend_from_slice(&counter.to_le_bytes());
    data.push(ix);
    data.push(sig);
    Instruction {
        program_id: HD,
        accounts: vec![
            AccountMeta::new(rig_pda(authority), false),
            AccountMeta::new_readonly(*authority, false),
            AccountMeta::new_readonly(BOARD, false),
            AccountMeta::new_readonly(ix_sysvar_id(), false),
        ],
        data,
    }
}

/// The signed PLAN for `user` (digest, sig) with `counter`.
pub fn plan_signature(user: &User, counter: u64, plan: &Plan) -> ([u8; 32], [u8; 64]) {
    let d = message::digest(&message::plan_preimage(&user.rig, counter, plan));
    (d, user.sign(&d))
}

/// BREAK / FREEZE signature.
pub fn signal_signature(
    user: &User,
    kind: u8,
    counter: u64,
    shift_id: u64,
    reason: u8,
) -> ([u8; 32], [u8; 64]) {
    let d = message::digest(&message::signal_preimage(
        kind, &user.rig, counter, shift_id, reason,
    ));
    (d, user.sign(&d))
}

fn signal_ix(
    tag: u8,
    authority: &Address,
    wallet_signs: bool,
    reason: u8,
    p256: Option<(u64, u8, u8)>,
) -> Instruction {
    let mut data = vec![tag, u8::from(p256.is_some()), reason];
    let mut accounts = vec![
        AccountMeta::new(rig_pda(authority), false),
        AccountMeta::new_readonly(*authority, wallet_signs),
    ];
    if let Some((counter, ix, sig)) = p256 {
        data.extend_from_slice(&counter.to_le_bytes());
        data.push(ix);
        data.push(sig);
        accounts.push(AccountMeta::new_readonly(ix_sysvar_id(), false));
    }
    Instruction {
        program_id: HD,
        accounts,
        data,
    }
}

/// `break_shift` by the wallet.
pub fn ix_break_wallet(authority: &Address, reason: u8) -> Instruction {
    signal_ix(hd::tag::BREAK_SHIFT, authority, true, reason, None)
}
/// `break_shift` by a P-256 BREAK.
pub fn ix_break_p256(
    authority: &Address,
    reason: u8,
    counter: u64,
    ix: u8,
    sig: u8,
) -> Instruction {
    signal_ix(
        hd::tag::BREAK_SHIFT,
        authority,
        false,
        reason,
        Some((counter, ix, sig)),
    )
}
/// `freeze_rig` by the wallet.
pub fn ix_freeze_wallet(authority: &Address) -> Instruction {
    signal_ix(hd::tag::FREEZE_RIG, authority, true, 3, None)
}
/// `freeze_rig` by a P-256 FREEZE.
pub fn ix_freeze_p256(
    authority: &Address,
    reason: u8,
    counter: u64,
    ix: u8,
    sig: u8,
) -> Instruction {
    signal_ix(
        hd::tag::FREEZE_RIG,
        authority,
        false,
        reason,
        Some((counter, ix, sig)),
    )
}
/// `unfreeze_rig`.
pub fn ix_unfreeze(authority: &Address, signs: bool) -> Instruction {
    Instruction {
        program_id: HD,
        accounts: vec![
            AccountMeta::new(rig_pda(authority), false),
            AccountMeta::new_readonly(*authority, signs),
        ],
        data: vec![hd::tag::UNFREEZE_RIG],
    }
}

/// `end_shift` by `caller` for `rig` / `shift_id`.
pub fn ix_end_shift(caller: &Address, rig: &Address, shift_id: u64) -> Instruction {
    Instruction {
        program_id: HD,
        accounts: vec![
            AccountMeta::new(*caller, true),
            AccountMeta::new(*rig, false),
            AccountMeta::new(shift_log_pda(rig, shift_id), false),
            AccountMeta::new_readonly(BOARD, false),
            AccountMeta::new_readonly(SYSTEM, false),
        ],
        data: vec![hd::tag::END_SHIFT],
    }
}

/// `propose_config`.
pub fn ix_propose(
    governance: &Address,
    registrar: &Address,
    crank_fee: u64,
    bury_bps: u16,
    paused: u8,
) -> Instruction {
    let mut data = vec![hd::tag::PROPOSE_CONFIG];
    data.extend_from_slice(registrar.as_ref());
    data.extend_from_slice(&crank_fee.to_le_bytes());
    data.extend_from_slice(&bury_bps.to_le_bytes());
    data.push(paused);
    Instruction {
        program_id: HD,
        accounts: vec![
            AccountMeta::new_readonly(*governance, true),
            AccountMeta::new(CONFIG, false),
        ],
        data,
    }
}

/// `apply_config`.
pub fn ix_apply() -> Instruction {
    Instruction {
        program_id: HD,
        accounts: vec![AccountMeta::new(CONFIG, false)],
        data: vec![hd::tag::APPLY_CONFIG],
    }
}

/// `close_rig` (with the seat when given).
pub fn ix_close_rig(authority: &Address, seat: Option<Address>) -> Instruction {
    let mut accounts = vec![
        AccountMeta::new(*authority, true),
        AccountMeta::new(rig_pda(authority), false),
    ];
    if let Some(s) = seat {
        accounts.push(AccountMeta::new(s, false));
    }
    Instruction {
        program_id: HD,
        accounts,
        data: vec![hd::tag::CLOSE_RIG],
    }
}

/// `verify_seeker`.
pub fn ix_verify_seeker(
    authority: &Address,
    token_account: &Address,
    mint: &Address,
    previous_rig: Option<Address>,
) -> Instruction {
    let mut accounts = vec![
        AccountMeta::new(*authority, true),
        AccountMeta::new(rig_pda(authority), false),
        AccountMeta::new(seat_pda(mint), false),
        AccountMeta::new_readonly(*token_account, false),
        AccountMeta::new_readonly(*mint, false),
        AccountMeta::new_readonly(SYSTEM, false),
    ];
    if let Some(p) = previous_rig {
        accounts.push(AccountMeta::new(p, false));
    }
    Instruction {
        program_id: HD,
        accounts,
        data: vec![hd::tag::VERIFY_SEEKER],
    }
}

/// One rig in a `dig` batch.
#[derive(Clone, Copy, Debug)]
pub struct DigRig {
    /// Rig.
    pub rig: Address,
    /// Authority.
    pub authority: Address,
    /// Automation (normally the PDA of authority).
    pub automation: Address,
    /// Miner.
    pub miner: Address,
    /// Heartbeat entry.
    pub entry: HeartbeatEntry,
}

impl DigRig {
    /// Standard accounts for `user` with `entry`.
    pub fn new(user: &User, entry: HeartbeatEntry) -> Self {
        Self {
            rig: user.rig,
            authority: user.pubkey(),
            automation: user.automation(),
            miner: user.miner(),
            entry,
        }
    }
}

/// An entry pointing at precompile `ix`, entry `sig`.
pub fn entry_for(hb: &Heartbeat, ix: u8, sig: u8) -> HeartbeatEntry {
    HeartbeatEntry {
        hb_ix: ix,
        hb_sig_index: sig,
        counter: hb.counter,
        round_id: hb.round,
        lease_rounds: hb.lease,
    }
}

/// An entry that reuses the current lease.
pub fn reuse_lease() -> HeartbeatEntry {
    HeartbeatEntry {
        hb_ix: NO_HEARTBEAT,
        hb_sig_index: 0,
        counter: 0,
        round_id: 0,
        lease_rounds: 0,
    }
}

fn push_entry(data: &mut Vec<u8>, e: &HeartbeatEntry) {
    data.push(e.hb_ix);
    data.push(e.hb_sig_index);
    data.extend_from_slice(&e.counter.to_le_bytes());
    data.extend_from_slice(&e.round_id.to_le_bytes());
    data.push(e.lease_rounds);
    data.push(0);
}

/// The 12 shared `dig` accounts.
pub fn dig_fixed_accounts(cranker: &Address, round: &Address) -> Vec<AccountMeta> {
    vec![
        AccountMeta::new(*cranker, true),
        AccountMeta::new_readonly(CONFIG, false),
        AccountMeta::new(EXECUTOR, false),
        AccountMeta::new(BOARD, false),
        AccountMeta::new(ORE_CONFIG, false),
        AccountMeta::new(*round, false),
        AccountMeta::new(TREASURY, false),
        AccountMeta::new_readonly(SYSTEM, false),
        AccountMeta::new_readonly(ORE, false),
        AccountMeta::new(VAR, false),
        AccountMeta::new_readonly(ENTROPY, false),
        AccountMeta::new_readonly(ix_sysvar_id(), false),
    ]
}

/// `dig`.
pub fn ix_dig(cranker: &Address, round: &Address, rigs: &[DigRig]) -> Instruction {
    let mut data = vec![hd::tag::DIG, rigs.len() as u8];
    let mut accounts = dig_fixed_accounts(cranker, round);
    for r in rigs {
        push_entry(&mut data, &r.entry);
        accounts.push(AccountMeta::new(r.rig, false));
        accounts.push(AccountMeta::new(r.authority, false));
        accounts.push(AccountMeta::new(r.automation, false));
        accounts.push(AccountMeta::new(r.miner, false));
    }
    Instruction {
        program_id: HD,
        accounts,
        data,
    }
}

/// `record_heartbeats`.
pub fn ix_record(rigs: &[(Address, HeartbeatEntry)]) -> Instruction {
    let mut data = vec![hd::tag::RECORD_HEARTBEATS, rigs.len() as u8];
    let mut accounts = vec![
        AccountMeta::new_readonly(BOARD, false),
        AccountMeta::new_readonly(ix_sysvar_id(), false),
    ];
    for (rig, e) in rigs {
        push_entry(&mut data, e);
        accounts.push(AccountMeta::new(*rig, false));
    }
    Instruction {
        program_id: HD,
        accounts,
        data,
    }
}

// ---- high-level flows -----------------------------------------------------------

impl Env {
    /// The canonical onboarding in ONE wallet transaction: ORE `automate`
    /// (Discretionary, fee = executor_fee, executor = Executor PDA) +
    /// `register_rig` + `set_caps` + `arm_shift`.
    pub fn onboard(&mut self, user: &User, deposit: u64, caps: Caps, plan: &Plan) -> TxResult {
        let w = user.pubkey();
        let ixs = [
            ore_automate_default(&w, deposit),
            ix_register_rig(&w, &user.p256(), None),
            ix_set_caps(&w, caps),
            ix_arm_wallet(&w, plan),
        ];
        let wallet = user.wallet.insecure_clone();
        self.send_as(&wallet, &ixs, &[])
    }

    /// Onboard with the standard caps and plan; panics on failure.
    pub fn onboard_standard(&mut self, user: &User) {
        ok(self.onboard(user, SOL / 20, Caps::standard(), &standard_plan()));
    }

    /// Dig `rigs` with fresh heartbeats (current round, lease 3) signed by
    /// `users`, all in one precompile instruction at index 1 (after the
    /// compute-budget instruction). Returns the tx result.
    pub fn dig_fresh(&mut self, users: &mut [&mut User]) -> TxResult {
        let round = self.board_round;
        let mut hbs = Vec::new();
        let mut rigs = Vec::new();
        for (i, u) in users.iter_mut().enumerate() {
            let shift = self.rig(&u.rig).shift_id.get();
            let hb = u.heartbeat(shift, round, 3);
            rigs.push(DigRig::new(u, entry_for(&hb, 1, i as u8)));
            hbs.push(hb);
        }
        self.dig_with(&hbs, &rigs)
    }

    /// `[compute limit, secp256r1(hbs) (if any), dig(rigs)]`.
    pub fn dig_with(&mut self, hbs: &[Heartbeat], rigs: &[DigRig]) -> TxResult {
        let mut ixs = vec![compute_limit(1_400_000)];
        for chunk in hbs.chunks(8) {
            ixs.push(secp_ix_for(chunk));
        }
        ixs.push(ix_dig(&self.cranker.pubkey(), &self.round, rigs));
        self.send(&ixs, &[])
    }
}
