//! Spike 1(b) measurements: compute units, signatures per precompile
//! instruction, and P-256 signatures per transaction for legacy, v0 and v1.
//!
//! Run with `--nocapture` to see the table. Every number printed is also
//! asserted, and every "fits" configuration is actually executed.

mod common;

use common::*;
use litesvm::LiteSVM;
use p256::ecdsa::SigningKey;
use solana_instruction::Instruction;
use solana_instruction_error::InstructionError;
use solana_keypair::Keypair;
use solana_message::{v0, v1, VersionedMessage};
use solana_signer::Signer;
use solana_transaction::versioned::VersionedTransaction;

/// Legacy and v0 packet limit (`solana-packet` `PACKET_DATA_SIZE` = 1280 - 40 - 8).
const PACKET_DATA_SIZE: usize = 1232;
/// v1 limit (`solana-message` 4.6.0 `v1::MAX_TRANSACTION_SIZE`).
const V1_MAX: usize = v1::MAX_TRANSACTION_SIZE;

#[derive(Clone, Copy, Debug, PartialEq)]
enum Format {
    Legacy,
    V0,
    V1,
}

impl Format {
    fn limit(self) -> usize {
        match self {
            Format::Legacy | Format::V0 => PACKET_DATA_SIZE,
            Format::V1 => V1_MAX,
        }
    }
}

fn build_tx(svm: &LiteSVM, payer: &Keypair, ixs: &[Instruction], f: Format) -> VersionedTransaction {
    let bh = svm.latest_blockhash();
    let msg = match f {
        Format::Legacy => VersionedMessage::Legacy(solana_message::Message::new_with_blockhash(
            ixs,
            Some(&payer.pubkey()),
            &bh,
        )),
        Format::V0 => VersionedMessage::V0(v0::Message::try_compile(&payer.pubkey(), ixs, &[], bh).unwrap()),
        Format::V1 => VersionedMessage::V1(
            v1::Message::try_compile_with_config(
                &payer.pubkey(),
                ixs,
                bh,
                v1::TransactionConfig::empty()
                    .with_compute_unit_limit(1_400_000)
                    .with_loaded_accounts_data_size_limit(64 * 1024 * 1024),
            )
            .unwrap(),
        ),
    };
    VersionedTransaction::try_new(msg, &[payer]).unwrap()
}

fn wire_size(tx: &VersionedTransaction) -> usize {
    wincode::serialize(tx).unwrap().len()
}

struct Batch {
    pks: Vec<[u8; 33]>,
    msgs: Vec<Vec<u8>>,
    sigs: Vec<[u8; 64]>,
}

/// `n` distinct rigs, each signing a `msg_len`-byte heartbeat.
fn batch(n: usize, msg_len: usize) -> Batch {
    let keys: Vec<SigningKey> = (0..n).map(|i| key((i % 250) as u8 + 1)).collect();
    let msgs: Vec<Vec<u8>> = (0..n)
        .map(|i| {
            let full = heartbeat_message(&[i as u8; 32], 42, i as u64);
            if msg_len <= full.len() {
                // e.g. 32 bytes = a SHA-256 digest of the preimage in production
                full[..msg_len].to_vec()
            } else {
                let mut m = full;
                m.resize(msg_len, 0xAB);
                m
            }
        })
        .collect();
    let sigs = keys.iter().zip(&msgs).map(|(k, m)| keystore_sign(k, m)).collect();
    let pks = keys.iter().map(compressed).collect();
    Batch { pks, msgs, sigs }
}

/// Precompile instructions carrying signatures `range` of the batch, in
/// chunks of at most 8.
fn precompile_ixs(b: &Batch, n: usize) -> Vec<Instruction> {
    (0..n)
        .collect::<Vec<_>>()
        .chunks(8)
        .map(|chunk| {
            let entries: Vec<(&[u8; 64], &[u8; 33], &[u8])> = chunk
                .iter()
                .map(|&i| (&b.sigs[i], &b.pks[i], b.msgs[i].as_slice()))
                .collect();
            precompile_ix(&entries)
        })
        .collect()
}

/// Precompile instructions plus one `verify_batch` per precompile instruction.
fn verified_ixs(b: &Batch, n: usize) -> Vec<Instruction> {
    let pre = precompile_ixs(b, n);
    let k = pre.len();
    let mut ixs = pre;
    for (j, chunk) in (0..n).collect::<Vec<_>>().chunks(8).enumerate() {
        let items: Vec<(&[u8; 33], &[u8])> =
            chunk.iter().map(|&i| (&b.pks[i], b.msgs[i].as_slice())).collect();
        ixs.push(verify_batch_ix(j as u16, &items));
    }
    assert_eq!(ixs.len(), 2 * k);
    ixs
}

/// Largest n in 1..=cap whose transaction fits `f`'s size limit.
fn max_fitting(
    svm: &LiteSVM,
    payer: &Keypair,
    b: &Batch,
    f: Format,
    cap: usize,
    make: fn(&Batch, usize) -> Vec<Instruction>,
) -> (usize, usize) {
    let mut best = (0, 0);
    for n in 1..=cap {
        let size = wire_size(&build_tx(svm, payer, &make(b, n), f));
        if size <= f.limit() {
            best = (n, size);
        } else {
            break;
        }
    }
    best
}

#[test]
fn nine_signatures_in_one_precompile_instruction_are_rejected() {
    let (mut svm, payer) = setup();
    let b = batch(9, 32);
    // Build 9 entries by hand (the client builder refuses > 8).
    let mut data = vec![9u8, 0];
    let header = 2 + 14 * 9;
    data.resize(header, 0);
    for i in 0..9 {
        let pk_off = data.len() as u16;
        data.extend_from_slice(&b.pks[i]);
        let sig_off = data.len() as u16;
        data.extend_from_slice(&b.sigs[i]);
        let msg_off = data.len() as u16;
        data.extend_from_slice(&b.msgs[i]);
        let o = p256_introspect::Secp256r1SignatureOffsets {
            signature_offset: sig_off,
            signature_instruction_index: u16::MAX,
            public_key_offset: pk_off,
            public_key_instruction_index: u16::MAX,
            message_data_offset: msg_off,
            message_data_size: b.msgs[i].len() as u16,
            message_instruction_index: u16::MAX,
        };
        data[2 + 14 * i..16 + 14 * i].copy_from_slice(&o.to_bytes());
    }
    let tx = build_tx(&svm, &payer, &[precompile_ix_from_data(data)], Format::V1);
    let err = svm.send_transaction(tx).unwrap_err().err;
    // PrecompileError::InvalidInstructionDataSize = 4.
    assert_eq!(
        err,
        solana_transaction_error::TransactionError::InstructionError(0, InstructionError::Custom(4))
    );
    println!("max_signatures per Secp256r1SigVerify instruction: 8 (9 -> InvalidInstructionDataSize)");
}

#[test]
fn compute_units_per_verification() {
    let (mut svm, payer) = setup();
    println!("\n== Compute units (program side; the precompile itself consumes none of the tx CU budget) ==");

    // Precompile only: the transaction's CU meter stays at 0.
    let b = batch(8, 32);
    let tx = build_tx(&svm, &payer, &precompile_ixs(&b, 8), Format::V1);
    let meta = svm.send_transaction(tx).unwrap();
    println!("precompile only, 8 sigs: {} CU", meta.compute_units_consumed);
    assert_eq!(meta.compute_units_consumed, 0);

    for msg_len in [32usize, 101] {
        let b = batch(8, msg_len);
        // Single verification via verify_heartbeat.
        svm.expire_blockhash();
        let ixs = [
            precompile_ixs(&b, 1).remove(0),
            verify_ix(0, 0, &b.pks[0], &b.msgs[0]),
        ];
        let meta = svm
            .send_transaction(build_tx(&svm, &payer, &ixs, Format::V1))
            .unwrap();
        println!(
            "verify_heartbeat, msg {msg_len:>3} B: {:>5} CU",
            meta.compute_units_consumed
        );
        assert!(meta.compute_units_consumed < 5_000, "{}", meta.compute_units_consumed);

        // verify_batch for n = 1..=8 in one precompile instruction.
        let mut row = Vec::new();
        for n in 1..=8 {
            svm.expire_blockhash();
            let meta = svm
                .send_transaction(build_tx(&svm, &payer, &verified_ixs(&b, n), Format::V1))
                .unwrap_or_else(|f| panic!("{:?} {}", f.err, f.meta.pretty_logs()));
            row.push(meta.compute_units_consumed);
        }
        println!("verify_batch,     msg {msg_len:>3} B, n=1..8: {row:?} CU");
        let per_sig = (row[7] - row[0]) / 7;
        println!("  marginal cost per extra signature: ~{per_sig} CU");
        assert!(row[7] < 20_000);
    }
    println!(
        "cost-model (block packing) charge per secp256r1 signature: 4800 CU \
         (agave cost-model SECP256R1_VERIFY_COST = 30 * 160); fee: 5000 lamports/sig"
    );
}

#[test]
fn signatures_per_transaction_by_format() {
    let (mut svm, payer) = setup();
    println!("\n== P-256 signatures per transaction (payer = 1 ed25519 signature) ==");
    println!("{:<8} {:>7} {:>6} {:>12} {:>6} {:>18}", "format", "msg B", "limit", "precompile", "size", "+verify_batch ix");
    for msg_len in [32usize, 101] {
        let b = batch(40, msg_len);
        for f in [Format::Legacy, Format::V0, Format::V1] {
            let (n_pre, size_pre) = max_fitting(&svm, &payer, &b, f, 40, precompile_ixs);
            let (n_ver, size_ver) = max_fitting(&svm, &payer, &b, f, 40, verified_ixs);
            println!(
                "{:<8} {:>7} {:>6} {:>12} {:>6} {:>12} ({:>4} B)",
                format!("{f:?}"),
                msg_len,
                f.limit(),
                n_pre,
                size_pre,
                n_ver,
                size_ver
            );

            // Execute the largest fitting configurations to prove they verify.
            for (n, make) in [(n_pre, precompile_ixs as fn(&Batch, usize) -> Vec<Instruction>), (n_ver, verified_ixs)] {
                svm.expire_blockhash();
                let tx = build_tx(&svm, &payer, &make(&b, n), f);
                assert!(wire_size(&tx) <= f.limit());
                svm.send_transaction(tx)
                    .unwrap_or_else(|e| panic!("{f:?} n={n}: {:?}\n{}", e.err, e.meta.pretty_logs()));
                // And one more does not fit.
                let over = build_tx(&svm, &payer, &make(&b, n + 1), f);
                assert!(wire_size(&over) > f.limit());
            }

            // Expected values, derived by hand from the wire formats:
            //   legacy: 166 + sum_k(6) + N*(111 + L)   (<= 1232)
            //   v0:     legacy + 2 (version byte, empty ALT vector)
            //   v1:     178 + sum_k(6) + N*(111 + L)   (<= 4096, CU + LADS config)
            // with k = ceil(N / 8) precompile instructions.
            let expected_pre = match (f, msg_len) {
                (Format::Legacy, 32) => 7,
                (Format::Legacy, 101) => 5, // exactly 1232 bytes
                (Format::V0, 32) => 7,
                (Format::V0, 101) => 4, // 5 would be 1234 bytes
                (Format::V1, 32) => 27,
                (Format::V1, 101) => 18,
                _ => unreachable!(),
            };
            assert_eq!(n_pre, expected_pre, "{f:?} msg {msg_len}");
        }
    }
}
