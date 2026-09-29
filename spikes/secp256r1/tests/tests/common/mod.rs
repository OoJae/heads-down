//! Shared LiteSVM harness for spike 1(b).
#![allow(dead_code)]

use litesvm::{types::TransactionResult, LiteSVM};
use p256::ecdsa::{signature::Signer as _, DerSignature, SigningKey};
use p256_introspect::client::{der_to_low_s_raw, der_to_raw, SignatureInput};
use solana_address::Address;
use solana_instruction::{AccountMeta, Instruction};
use solana_instruction_error::InstructionError;
use solana_keypair::Keypair;
use solana_message::Message;
use solana_signer::Signer;
use solana_transaction::Transaction;
use solana_transaction_error::TransactionError;

/// Arbitrary fixed id for the spike program.
pub const PROGRAM_ID: Address = Address::new_from_array([0x5B; 32]);
pub const VERIFY_HEARTBEAT: u8 = 0;
pub const VERIFY_BATCH: u8 = 1;

pub fn secp256r1_program_id() -> Address {
    Address::new_from_array(p256_introspect::SECP256R1_PROGRAM_ID.to_bytes())
}

pub fn instructions_sysvar_id() -> Address {
    Address::new_from_array(p256_introspect::INSTRUCTIONS_SYSVAR_ID.to_bytes())
}

pub fn program_so_path() -> std::path::PathBuf {
    std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../target/deploy/p256_spike.so")
}

/// A fresh VM with mainnet features, sigverify, and the precompiles loaded
/// (litesvm feature `precompiles`), plus the spike program and a funded payer.
pub fn setup() -> (LiteSVM, Keypair) {
    let mut svm = LiteSVM::new();
    let so = program_so_path();
    let bytes = std::fs::read(&so).unwrap_or_else(|e| {
        panic!(
            "{}: {e}. Build it first: cargo-build-sbf --manifest-path spikes/secp256r1/program/Cargo.toml",
            so.display()
        )
    });
    svm.add_program(PROGRAM_ID, &bytes).unwrap();
    let payer = Keypair::new();
    svm.airdrop(&payer.pubkey(), 10_000_000_000).unwrap();
    (svm, payer)
}

// ------------------------------------------------------------- signing ---

/// Deterministic test key (stand-in for a Keystore key that never leaves
/// the TEE; only the public half matters on-chain).
pub fn key(seed: u8) -> SigningKey {
    SigningKey::from_slice(&[seed.max(1); 32]).unwrap()
}

pub fn compressed(sk: &SigningKey) -> [u8; 33] {
    sk.verifying_key()
        .to_sec1_point(true)
        .as_bytes()
        .try_into()
        .unwrap()
}

/// Exactly what Android `Signature.getInstance("SHA256withECDSA")` returns:
/// ASN.1 DER, SHA-256 applied internally, S not normalized.
pub fn keystore_sign_der(sk: &SigningKey, msg: &[u8]) -> Vec<u8> {
    let der: DerSignature = sk.sign(msg);
    der.as_bytes().to_vec()
}

/// The full phone-to-chain pipeline: Keystore DER -> raw r||s -> low-S.
pub fn keystore_sign(sk: &SigningKey, msg: &[u8]) -> [u8; 64] {
    der_to_low_s_raw(&keystore_sign_der(sk, msg)).unwrap()
}

/// The raw signature before low-S normalization (may be high-S).
pub fn keystore_sign_unnormalized(sk: &SigningKey, msg: &[u8]) -> [u8; 64] {
    der_to_raw(&keystore_sign_der(sk, msg)).unwrap()
}

/// A realistic heartbeat preimage, laid out as the spec's
/// `'HDv1' || program_id || rig || ore_round_id || counter || state || shift_id || lease_end`
/// (4 + 32 + 32 + 8 + 8 + 1 + 8 + 8 = 101 bytes).
pub fn heartbeat_message(rig: &[u8; 32], round: u64, counter: u64) -> Vec<u8> {
    let mut m = Vec::with_capacity(101);
    m.extend_from_slice(b"HDv1");
    m.extend_from_slice(PROGRAM_ID.as_ref());
    m.extend_from_slice(rig);
    m.extend_from_slice(&round.to_le_bytes());
    m.extend_from_slice(&counter.to_le_bytes());
    m.push(1); // state = DOWN
    m.extend_from_slice(&7u64.to_le_bytes()); // shift_id
    m.extend_from_slice(&(round + 3).to_le_bytes()); // lease_end
    assert_eq!(m.len(), 101);
    m
}

// -------------------------------------------------------- instructions ---

pub fn precompile_ix_from_data(data: Vec<u8>) -> Instruction {
    Instruction {
        program_id: secp256r1_program_id(),
        accounts: vec![],
        data,
    }
}

pub fn precompile_ix(entries: &[(&[u8; 64], &[u8; 33], &[u8])]) -> Instruction {
    let inputs: Vec<SignatureInput> = entries
        .iter()
        .map(|(sig, pk, msg)| SignatureInput {
            signature: **sig,
            public_key: **pk,
            message: msg,
        })
        .collect();
    precompile_ix_from_data(p256_introspect::client::build_instruction_data(&inputs).unwrap())
}

pub fn verify_ix_with_sysvar(
    sysvar: Address,
    precompile_index: u16,
    signature_index: u8,
    pubkey: &[u8; 33],
    msg: &[u8],
) -> Instruction {
    let mut data = vec![VERIFY_HEARTBEAT];
    data.extend_from_slice(&precompile_index.to_le_bytes());
    data.push(signature_index);
    data.extend_from_slice(pubkey);
    data.extend_from_slice(msg);
    Instruction {
        program_id: PROGRAM_ID,
        accounts: vec![AccountMeta::new_readonly(sysvar, false)],
        data,
    }
}

pub fn verify_ix(precompile_index: u16, signature_index: u8, pubkey: &[u8; 33], msg: &[u8]) -> Instruction {
    verify_ix_with_sysvar(instructions_sysvar_id(), precompile_index, signature_index, pubkey, msg)
}

pub fn verify_batch_ix(precompile_index: u16, items: &[(&[u8; 33], &[u8])]) -> Instruction {
    let mut data = vec![VERIFY_BATCH];
    data.extend_from_slice(&precompile_index.to_le_bytes());
    data.push(items.len() as u8);
    for (pk, msg) in items {
        data.extend_from_slice(*pk);
        data.extend_from_slice(&(msg.len() as u16).to_le_bytes());
        data.extend_from_slice(msg);
    }
    Instruction {
        program_id: PROGRAM_ID,
        accounts: vec![AccountMeta::new_readonly(instructions_sysvar_id(), false)],
        data,
    }
}

pub fn legacy_tx(svm: &LiteSVM, payer: &Keypair, ixs: &[Instruction]) -> Transaction {
    Transaction::new(
        &[payer],
        Message::new(ixs, Some(&payer.pubkey())),
        svm.latest_blockhash(),
    )
}

pub fn send(svm: &mut LiteSVM, payer: &Keypair, ixs: &[Instruction]) -> TransactionResult {
    let tx = legacy_tx(svm, payer, ixs);
    svm.send_transaction(tx)
}

// ------------------------------------------------------------ asserts ---

/// The transaction must fail in instruction `ix_index` with exactly `err`.
#[track_caller]
pub fn assert_ix_err(res: TransactionResult, ix_index: u8, err: InstructionError) {
    match res {
        Ok(meta) => panic!("expected failure, got success; logs:\n{}", meta.pretty_logs()),
        Err(f) => assert_eq!(
            f.err,
            TransactionError::InstructionError(ix_index, err.clone()),
            "logs:\n{}",
            f.meta.pretty_logs()
        ),
    }
}

/// Failure raised by this crate's check, in instruction `ix_index`.
#[track_caller]
pub fn assert_introspect_err(res: TransactionResult, ix_index: u8, e: p256_introspect::IntrospectError) {
    assert_ix_err(res, ix_index, InstructionError::Custom(e.code()));
}

/// `PrecompileError::InvalidSignature` as surfaced by the runtime.
pub const PRECOMPILE_INVALID_SIGNATURE: InstructionError = InstructionError::Custom(2);
/// `PrecompileError::InvalidDataOffsets`.
pub const PRECOMPILE_INVALID_DATA_OFFSETS: InstructionError = InstructionError::Custom(3);

/// Byte-for-byte `solana-instructions-sysvar` layout, for forging a sysvar.
pub fn forge_instructions_sysvar(ixs: &[(Address, Vec<u8>)], current: u16) -> Vec<u8> {
    let mut data = Vec::new();
    data.extend_from_slice(&(ixs.len() as u16).to_le_bytes());
    data.resize(2 + 2 * ixs.len(), 0);
    for (i, (program_id, ix_data)) in ixs.iter().enumerate() {
        let start = data.len() as u16;
        data[2 + 2 * i..4 + 2 * i].copy_from_slice(&start.to_le_bytes());
        data.extend_from_slice(&0u16.to_le_bytes()); // no accounts
        data.extend_from_slice(program_id.as_ref());
        data.extend_from_slice(&(ix_data.len() as u16).to_le_bytes());
        data.extend_from_slice(ix_data);
    }
    data.extend_from_slice(&current.to_le_bytes());
    data
}
