//! Transaction packing: every batch the crank emits fits the wire limit for its format, is
//! maximal (the next rig would not fit), and carries precompile entries the dig entries
//! point at correctly. The precompile data is also executed by the real Agave secp256r1
//! precompile in LiteSVM.

mod common;

use common::*;
use hd_crank::hd::{self, DigEntry, HB_REUSE_LEASE};
use hd_crank::tx::{self, measure, pack, Misfit, TxFormat};
use hd_crank::{ore, tx::MAX_COMPUTE_UNITS};
use litesvm::LiteSVM;
use solana_hash::Hash;
use solana_keypair::Keypair;
use solana_message::AddressLookupTableAccount;
use solana_signer::Signer;

fn check_batches(format: TxFormat, rigs: &[tx::RigDig], alts: &[AddressLookupTableAccount]) -> Vec<usize> {
    let payer = Keypair::new();
    let p = params(format, payer.pubkey());
    let (batches, rejected) = pack(&p, rigs, alts);
    assert!(rejected.is_empty(), "{rejected:?}");
    let total: usize = batches.iter().map(|b| b.rigs.len()).sum();
    assert_eq!(total, rigs.len(), "every rig packed exactly once");
    // Order preserved.
    let flat: Vec<_> = batches.iter().flat_map(|b| b.rigs.iter().map(|r| r.accounts.rig)).collect();
    assert_eq!(flat, rigs.iter().map(|r| r.accounts.rig).collect::<Vec<_>>());
    for (i, b) in batches.iter().enumerate() {
        assert!(b.wire_size <= format.size_limit(), "batch {i}: {} bytes", b.wire_size);
        // The signed transaction has exactly the measured size.
        let signed = tx::sign_batch(&p, &b.rigs, alts, Hash::new_from_array([7; 32]), &payer).unwrap();
        assert_eq!(tx::serialize(&signed).unwrap().len(), b.wire_size);
        // Maximal: adding the next batch's first rig does not fit.
        if let Some(next) = batches.get(i + 1) {
            let mut more = b.rigs.clone();
            more.push(next.rigs[0]);
            assert!(measure(&p, &more, alts).is_err(), "batch {i} was not maximal");
        }
    }
    batches.iter().map(|b| b.rigs.len()).collect()
}

#[test]
fn v0_with_lookup_table_fits_1232_and_is_maximal() {
    let rigs: Vec<_> = (0..40).map(|i| rig_dig(i, true, None)).collect();
    let table = lookup_table(&rigs);
    let sizes = check_batches(TxFormat::V0, &rigs, &[table]);
    println!("v0 + ALT, fresh heartbeats: rigs per tx = {sizes:?}");
    assert!(sizes[0] >= 5, "a lookup table should fit at least 5 fresh heartbeats, got {}", sizes[0]);
}

#[test]
fn v0_lease_reuse_is_much_denser() {
    let rigs: Vec<_> = (0..60).map(|i| rig_dig(i, false, None)).collect();
    let table = lookup_table(&rigs);
    let sizes = check_batches(TxFormat::V0, &rigs, &[table]);
    println!("v0 + ALT, reused leases: rigs per tx = {sizes:?}");
    // The account-lock limit (64) or the compute ceiling binds before the byte limit.
    assert!(sizes[0] >= 10);
}

#[test]
fn legacy_and_v0_without_table_fit_fewer() {
    let rigs: Vec<_> = (0..12).map(|i| rig_dig(i, true, None)).collect();
    let legacy = check_batches(TxFormat::Legacy, &rigs, &[]);
    let v0 = check_batches(TxFormat::V0, &rigs, &[]);
    let v0_alt = check_batches(TxFormat::V0, &rigs, &[lookup_table(&rigs)]);
    println!("fresh heartbeats per tx: legacy {legacy:?}, v0 no-ALT {v0:?}, v0+ALT {v0_alt:?}");
    assert!(legacy[0] < v0_alt[0]);
    assert!(v0[0] < v0_alt[0]);
}

#[test]
fn v1_respects_4096_bytes_and_64_addresses() {
    let rigs: Vec<_> = (0..40).map(|i| rig_dig(i, true, if i % 4 == 0 { Some(422_600) } else { None })).collect();
    let payer = Keypair::new();
    let p = params(TxFormat::V1, payer.pubkey());
    let (batches, rejected) = pack(&p, &rigs, &[]);
    assert!(rejected.is_empty());
    for b in &batches {
        assert!(b.wire_size <= 4096);
        assert!(b.accounts <= 64, "{} accounts", b.accounts);
    }
    let sizes = check_batches(TxFormat::V1, &rigs, &[]);
    println!("v1, fresh heartbeats (+ some checkpoints): rigs per tx = {sizes:?}");
}

#[test]
fn compute_ceiling_and_oversized_rigs() {
    let payer = Keypair::new();
    let mut p = params(TxFormat::V1, payer.pubkey());
    p.cu_estimate.per_rig = 500_000;
    let rigs: Vec<_> = (0..5).map(|i| rig_dig(i, false, None)).collect();
    let (batches, _) = pack(&p, &rigs, &[]);
    assert!(batches.iter().all(|b| b.rigs.len() <= 2), "1.4M CU / 500k");
    assert!(batches.iter().all(|b| b.cu_limit <= MAX_COMPUTE_UNITS));
    p.cu_estimate.per_rig = 2_000_000;
    let (batches, rejected) = pack(&p, &rigs, &[]);
    assert!(batches.is_empty());
    assert_eq!(rejected.len(), 5);
    assert!(rejected.iter().all(|(_, m)| *m == Misfit::Compute));
    p.cu_estimate.per_rig = 1;
    p.max_rigs_per_tx = 0;
    let (_, rejected) = pack(&p, &rigs, &[]);
    assert!(rejected.iter().all(|(_, m)| *m == Misfit::Rigs));
}

#[test]
fn instruction_order_and_entry_indices() {
    let payer = Keypair::new();
    let p = params(TxFormat::V1, payer.pubkey());
    // 11 rigs: 9 fresh heartbeats (2 precompile ixs: 8 + 1), 2 reused leases, 3 checkpoints.
    let rigs: Vec<_> = (0..11)
        .map(|i| rig_dig(i, i != 3 && i != 7, if i % 5 == 0 { Some(422_600) } else { None }))
        .collect();
    let ixs = tx::build_instructions(&p, &rigs).unwrap();
    // v1: no ComputeBudget instructions. 3 checkpoints, 2 precompiles, 1 dig.
    assert_eq!(ixs.len(), 3 + 2 + 1);
    for ix in &ixs[..3] {
        assert_eq!(ix.program_id, ore::ORE_PROGRAM_ID);
        assert_eq!(ix.data, vec![ore::IX_CHECKPOINT]);
        assert_eq!(ix.accounts[0].pubkey, payer.pubkey());
        assert_eq!(ix.accounts[5].pubkey, ore::round_pda(422_600));
    }
    assert_eq!(ixs[3].program_id, hd::SECP256R1_PROGRAM_ID);
    assert_eq!(ixs[4].program_id, hd::SECP256R1_PROGRAM_ID);
    let dig = &ixs[5];
    assert_eq!(dig.program_id, hd::PROGRAM_ID);
    assert_eq!(dig.data[0], hd::IX_DIG);
    assert_eq!(dig.data[1], 11);
    assert_eq!(dig.accounts[5].pubkey, board_round());
    for (i, r) in rigs.iter().enumerate() {
        let e = DigEntry::decode(&dig.data[2 + 20 * i..2 + 20 * (i + 1)]).unwrap();
        assert_eq!(&dig.accounts[12 + 4 * i].pubkey, &r.accounts.rig);
        assert_eq!(&dig.accounts[13 + 4 * i].pubkey, &r.accounts.authority);
        match r.heartbeat {
            None => assert_eq!(e, DigEntry::reuse_lease()),
            Some(h) => {
                assert_ne!(e.hb_ix, HB_REUSE_LEASE);
                assert_eq!((e.counter, e.round_id, e.lease_rounds), (h.fields.counter, h.fields.round_id, h.fields.lease_rounds));
                // Parse the precompile exactly like the program will and check the entry.
                let pix = &ixs[usize::from(e.hb_ix)];
                let parsed = p256_introspect::Secp256r1Instruction::parse(&pix.data, u16::from(e.hb_ix)).unwrap();
                let entry = parsed.expect_entry(e.hb_sig_index, &h.pubkey, &h.digest).unwrap();
                assert_eq!(entry.message.len(), 32, "32-byte digest messages");
                assert_eq!(entry.signature, &h.sig);
            }
        }
    }
    // 8 + 1 entries.
    assert_eq!(ixs[3].data[0], 8);
    assert_eq!(ixs[4].data[0], 1);
}

#[test]
fn precompile_instructions_verify_in_the_real_precompile() {
    let mut svm = LiteSVM::new();
    let payer = Keypair::new();
    svm.airdrop(&payer.pubkey(), 10_000_000_000).unwrap();
    let rigs: Vec<_> = (0..9).map(|i| rig_dig(i, true, None)).collect();
    let p = params(TxFormat::V0, payer.pubkey());
    let ixs = tx::build_instructions(&p, &rigs).unwrap();
    // Everything except the dig itself (no heads_down program in this test).
    let without_dig = &ixs[..ixs.len() - 1];
    #[allow(clippy::result_large_err)]
    let run = |svm: &mut LiteSVM, ixs: &[solana_instruction::Instruction]| {
        let msg = tx::compile_message(&p, ixs, svm.latest_blockhash(), &[], 200_000).unwrap();
        let t = tx::make_transaction(msg, Some(&payer)).unwrap();
        let r = svm.send_transaction(t);
        svm.expire_blockhash();
        r
    };
    let before = svm.get_balance(&payer.pubkey()).unwrap();
    run(&mut svm, without_dig).expect("crank-built precompile data verifies");
    let fee = before - svm.get_balance(&payer.pubkey()).unwrap();
    // 1 tx signature + 9 secp256r1 signatures at 5,000 lamports, plus the priority fee
    // (limit from the estimate: 10k + 9 x 60k CU at 10,000 micro-lamports/CU).
    let prio = tx::priority_fee_lamports(tx::cu_limit_for(&p, &rigs), p.cu_price_micro_lamports);
    assert_eq!(fee, 10 * 5_000 + prio, "fee = 5,000 per signature (incl. secp256r1) + priority");
    println!("9 heartbeats: fee {fee} lamports ({} base+precompile, {prio} priority)", 10 * 5_000);

    // Tamper one signature: the whole transaction fails in the precompile.
    let mut bad = without_dig.to_vec();
    let last = bad.len() - 1;
    let n = bad[last].data.len();
    bad[last].data[n - 40] ^= 1; // inside the last entry's signature
    assert!(run(&mut svm, &bad).is_err());
}
