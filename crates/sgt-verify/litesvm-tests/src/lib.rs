//! Harness for running the `sgt-verify-probe` SBF program in LiteSVM.
//!
//! Build the probes first (`scripts/build-sbf.sh`): the mainnet variant lands
//! in `target/deploy/`, the `test-group` variant in `target/deploy-test-group/`.

use std::{
    fs,
    path::{Path, PathBuf},
    str::FromStr,
};

use base64::{engine::general_purpose::STANDARD, Engine};
use litesvm::{types::TransactionMetadata, LiteSVM};
use serde_json::Value;
use sgt_verify::{testkit, SgtError, TOKEN_2022_PROGRAM_ID};
use solana_account::Account;
use solana_address::Address;
use solana_instruction::{AccountMeta, Instruction};
use solana_instruction_error::InstructionError;
use solana_keypair::Keypair;
use solana_message::Message;
use solana_signer::Signer;
use solana_transaction::Transaction;
use solana_transaction_error::TransactionError;

/// Address the probe is deployed at in every test.
pub const PROBE_ID: Address = Address::new_from_array(*b"sgt-verify/probe/program-id/v1\0\0");

/// Which build of the probe to load.
#[derive(Clone, Copy, Debug)]
pub enum Probe {
    /// Default features: the hardcoded mainnet anchors.
    Mainnet,
    /// `--features test-group` with SGT_VERIFY_TEST_* unset (public test keys).
    TestGroup,
}

fn crate_root() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("..")
}

/// Path of the probe `.so` for `probe`.
pub fn probe_so(probe: Probe) -> PathBuf {
    let dir = match probe {
        Probe::Mainnet => "deploy",
        Probe::TestGroup => "deploy-test-group",
    };
    crate_root().join("target").join(dir).join("sgt_verify_probe.so")
}

/// A LiteSVM with the chosen probe loaded. Token-2022 11.0.0 and the ATA
/// program ship with LiteSVM.
pub fn svm(probe: Probe, sigverify: bool) -> LiteSVM {
    let path = probe_so(probe);
    assert!(
        path.exists(),
        "{} missing: run crates/sgt-verify/scripts/build-sbf.sh first",
        path.display()
    );
    let mut svm = LiteSVM::new().with_sigverify(sigverify);
    svm.add_program_from_file(PROBE_ID, &path).unwrap();
    svm
}

/// A funded fee payer.
pub fn payer(svm: &mut LiteSVM) -> Keypair {
    let kp = Keypair::new();
    svm.airdrop(&kp.pubkey(), 100_000_000_000).unwrap();
    kp
}

// ---- fixtures ------------------------------------------------------------------

/// One account from `fixtures/`.
#[derive(Clone, Debug)]
pub struct FixtureAccount {
    /// Address.
    pub address: Address,
    /// Account as LiteSVM stores it.
    pub account: Account,
}

/// A real SGT from `fixtures/manifest.json`.
#[derive(Clone, Debug)]
pub struct RealSgt {
    /// `member-<n>`.
    pub label: String,
    /// The SGT mint.
    pub mint: FixtureAccount,
    /// The holder's token account.
    pub token_account: FixtureAccount,
    /// The wallet that holds it.
    pub holder: Address,
    /// TokenGroupMember.member_number.
    pub member_number: u64,
}

fn read_json(path: &Path) -> Value {
    serde_json::from_str(&fs::read_to_string(path).unwrap()).unwrap()
}

fn addr(v: &Value) -> Address {
    Address::from_str(v.as_str().unwrap()).unwrap()
}

/// Load `fixtures/<rel>`.
pub fn fixture(rel: &str) -> FixtureAccount {
    let v = read_json(&crate_root().join("fixtures").join(rel));
    let a = &v["account"];
    FixtureAccount {
        address: addr(&v["pubkey"]),
        account: Account {
            lamports: a["lamports"].as_u64().unwrap(),
            data: STANDARD.decode(a["data"][0].as_str().unwrap()).unwrap(),
            owner: addr(&a["owner"]),
            executable: false,
            rent_epoch: a["rentEpoch"].as_u64().unwrap(),
        },
    }
}

/// All real SGTs in the manifest.
pub fn real_sgts() -> Vec<RealSgt> {
    let manifest = read_json(&crate_root().join("fixtures/manifest.json"));
    manifest["sgts"]
        .as_array()
        .unwrap()
        .iter()
        .map(|s| {
            let label = s["label"].as_str().unwrap().to_owned();
            RealSgt {
                mint: fixture(&format!("{label}/mint.json")),
                token_account: fixture(&format!("{label}/token_account.json")),
                holder: addr(&s["holder"]),
                member_number: s["member_number"].as_u64().unwrap(),
                label,
            }
        })
        .collect()
}

/// Put a fixture account into the SVM.
pub fn load(svm: &mut LiteSVM, f: &FixtureAccount) {
    svm.set_account(f.address, f.account.clone()).unwrap();
}

/// Put raw Token-2022-owned bytes at `address`, rent-exempt.
pub fn put_token_2022_account(svm: &mut LiteSVM, address: Address, data: Vec<u8>) {
    put_account(svm, address, TOKEN_2022_PROGRAM_ID, data);
}

/// Put raw bytes owned by `owner` at `address`, rent-exempt.
pub fn put_account(svm: &mut LiteSVM, address: Address, owner: Address, data: Vec<u8>) {
    let lamports = svm.minimum_balance_for_rent_exemption(data.len());
    svm.set_account(
        address,
        Account {
            lamports,
            data,
            owner,
            executable: false,
            rent_epoch: 0,
        },
    )
    .unwrap();
}

// ---- running things ------------------------------------------------------------

/// What the probe returned.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ProbeOutput {
    /// Verified SGT mint.
    pub mint: Address,
    /// Member number.
    pub member_number: u64,
    /// Whether the holding is frozen.
    pub frozen: bool,
    /// Compute units the transaction consumed.
    pub compute_units: u64,
}

/// A failed transaction: the error plus its logs.
#[derive(Clone, Debug)]
pub struct Failure {
    /// Transaction error.
    pub err: TransactionError,
    /// Program logs.
    pub logs: Vec<String>,
}

impl Failure {
    /// `true` if instruction `index` failed with `err`.
    pub fn is_instruction_error(&self, index: u8, err: &InstructionError) -> bool {
        matches!(&self.err, TransactionError::InstructionError(i, e) if *i == index && e == err)
    }
}

/// Assert the probe failed with this `SgtError`.
#[track_caller]
pub fn assert_sgt_error(result: Result<ProbeOutput, Failure>, expected: SgtError) {
    let failure = result.expect_err("probe unexpectedly succeeded");
    assert!(
        failure.is_instruction_error(0, &InstructionError::Custom(expected.program_error_code())),
        "expected {expected:?} ({:#x}), got {:?}\n{}",
        expected.program_error_code(),
        failure.err,
        failure.logs.join("\n")
    );
}

/// Sign and send `instructions`. `signers` must include `payer`.
pub fn send(
    svm: &mut LiteSVM,
    instructions: &[Instruction],
    payer: &Keypair,
    signers: &[&Keypair],
) -> Result<TransactionMetadata, Failure> {
    let tx = Transaction::new_signed_with_payer(
        instructions,
        Some(&payer.pubkey()),
        signers,
        svm.latest_blockhash(),
    );
    let result = svm
        .send_transaction(tx)
        .map_err(|f| Failure { err: f.err, logs: f.meta.logs });
    svm.expire_blockhash();
    result
}

/// The probe instruction for `[token_account, mint, holder]`.
pub fn probe_ix(token_account: &Address, mint: &Address, holder: &Address, holder_signs: bool) -> Instruction {
    Instruction {
        program_id: PROBE_ID,
        accounts: vec![
            AccountMeta::new_readonly(*token_account, false),
            AccountMeta::new_readonly(*mint, false),
            AccountMeta::new_readonly(*holder, holder_signs),
        ],
        data: vec![],
    }
}

fn decode_output(meta: &TransactionMetadata) -> ProbeOutput {
    let data = &meta.return_data.data;
    assert_eq!(meta.return_data.program_id, PROBE_ID);
    assert_eq!(data.len(), 41, "probe return data");
    ProbeOutput {
        mint: Address::try_from(&data[..32]).unwrap(),
        member_number: u64::from_le_bytes(data[32..40].try_into().unwrap()),
        frozen: data[40] == 1,
        compute_units: meta.compute_units_consumed,
    }
}

/// Run the probe with `holder` as a properly signing keypair (works with
/// sigverify on).
pub fn run_probe_signed(
    svm: &mut LiteSVM,
    payer: &Keypair,
    token_account: &Address,
    mint: &Address,
    holder: &Keypair,
) -> Result<ProbeOutput, Failure> {
    let ix = probe_ix(token_account, mint, &holder.pubkey(), true);
    send(svm, &[ix], payer, &[payer, holder]).map(|m| decode_output(&m))
}

/// Run the probe for a holder whose key we do not have (a real mainnet
/// holder). The holder is marked as a signer but its signature slot is left
/// empty, so this needs an SVM built with sigverify off. The program still
/// sees `is_signer == true`, exactly as it would on mainnet.
pub fn run_probe_unsigned_holder(
    svm: &mut LiteSVM,
    payer: &Keypair,
    token_account: &Address,
    mint: &Address,
    holder: &Address,
    holder_signs: bool,
) -> Result<ProbeOutput, Failure> {
    let ix = probe_ix(token_account, mint, holder, holder_signs);
    let message = Message::new(&[ix], Some(&payer.pubkey()));
    let mut tx = Transaction::new_unsigned(message);
    tx.partial_sign(&[payer], svm.latest_blockhash());
    let result = svm
        .send_transaction(tx)
        .map(|m| decode_output(&m))
        .map_err(|f| Failure { err: f.err, logs: f.meta.logs });
    svm.expire_blockhash();
    result
}

/// Keypairs behind the public test anchors.
pub fn public_test_keys() -> (Keypair, Keypair) {
    let authority = Keypair::new_from_array(testkit::PUBLIC_TEST_AUTHORITY_SEED);
    let group = Keypair::new_from_array(testkit::PUBLIC_TEST_GROUP_SEED);
    (authority, group)
}

/// Rent-exempt lamports for `len` bytes.
pub fn rent(svm: &LiteSVM, len: usize) -> u64 {
    svm.minimum_balance_for_rent_exemption(len)
}
