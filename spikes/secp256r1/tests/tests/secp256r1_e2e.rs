//! Spike 1(b): secp256r1 end to end in LiteSVM 0.17 with the real Agave
//! secp256r1 precompile (agave-precompiles 4.3.0, OpenSSL) and the spike SBF
//! program built with `p256-introspect`.
//!
//! Every negative case asserts the exact instruction index and error code, so
//! it is clear *which* layer (precompile or introspection) rejected it.

mod common;

use common::*;
use p256_introspect::{IntrospectError as E, SECP256R1_ORDER};
use solana_account::Account;
use solana_address::Address;
use solana_signer::Signer;

const RIG: [u8; 32] = [0xA1; 32];

// ------------------------------------------------------------ positive ---

#[test]
fn keystore_heartbeat_verifies_end_to_end() {
    let (mut svm, payer) = setup();
    let sk = key(1);
    let pk = compressed(&sk);
    let msg = heartbeat_message(&RIG, 42, 1);
    let sig = keystore_sign(&sk, &msg);

    let meta = send(
        &mut svm,
        &payer,
        &[precompile_ix(&[(&sig, &pk, &msg)]), verify_ix(0, 0, &pk, &msg)],
    )
    .unwrap_or_else(|f| panic!("{:?}\n{}", f.err, f.meta.pretty_logs()));
    println!(
        "verify_heartbeat: {} CU total for the tx, fee {} lamports",
        meta.compute_units_consumed, meta.fee
    );
    // Fee = 5000 per tx signature + 5000 per secp256r1 signature.
    assert_eq!(meta.fee, 10_000);
}

#[test]
fn originally_high_s_keystore_signature_passes_after_normalization() {
    let (mut svm, payer) = setup();
    let sk = key(2);
    let pk = compressed(&sk);
    // Find a heartbeat whose raw Keystore signature is high-S.
    let (msg, raw) = (0u64..)
        .map(|c| {
            let m = heartbeat_message(&RIG, 42, c);
            let raw = keystore_sign_unnormalized(&sk, &m);
            (m, raw)
        })
        .find(|(_, raw)| !p256_introspect::is_low_s(raw[32..].try_into().unwrap()))
        .unwrap();
    let low = p256_introspect::client::normalize_low_s(&raw).unwrap();
    assert_ne!(low, raw);
    send(
        &mut svm,
        &payer,
        &[precompile_ix(&[(&low, &pk, &msg)]), verify_ix(0, 0, &pk, &msg)],
    )
    .unwrap();
}

#[test]
fn precompile_may_come_after_the_verifying_instruction() {
    // The sysvar lists every top-level instruction, and a failing precompile
    // fails the whole transaction, so order is irrelevant to soundness.
    let (mut svm, payer) = setup();
    let sk = key(3);
    let pk = compressed(&sk);
    let msg = heartbeat_message(&RIG, 42, 1);
    let sig = keystore_sign(&sk, &msg);
    send(
        &mut svm,
        &payer,
        &[verify_ix(1, 0, &pk, &msg), precompile_ix(&[(&sig, &pk, &msg)])],
    )
    .unwrap();

    // ...and a bad precompile after it still sinks the transaction.
    let mut bad = sig;
    bad[5] ^= 1;
    assert_ix_err(
        send(
            &mut svm,
            &payer,
            &[verify_ix(1, 0, &pk, &msg), precompile_ix(&[(&bad, &pk, &msg)])],
        ),
        1,
        PRECOMPILE_INVALID_SIGNATURE,
    );
}

#[test]
fn explicit_own_index_in_offsets_is_accepted() {
    let (mut svm, payer) = setup();
    let sk = key(4);
    let pk = compressed(&sk);
    let msg = heartbeat_message(&RIG, 1, 1);
    let sig = keystore_sign(&sk, &msg);
    let data = p256_introspect::client::build_instruction_data_with_index(
        &[p256_introspect::client::SignatureInput {
            signature: sig,
            public_key: pk,
            message: &msg,
        }],
        1,
    )
    .unwrap();
    send(
        &mut svm,
        &payer,
        &[
            verify_ix(1, 0, &pk, &msg),
            precompile_ix_from_data(data),
        ],
    )
    .unwrap();
}

#[test]
fn rfc6979_vector_verifies_in_the_agave_precompile() {
    // Cross-implementation check: RustCrypto p256 (signing, normalization)
    // vs OpenSSL inside the Agave precompile (verification).
    let (mut svm, payer) = setup();
    let x = "C9AFA9D845BA75166B5C215767B1D6934E50C3DB36E89B127B8A622B120F6721";
    let x: Vec<u8> = (0..64).step_by(2).map(|i| u8::from_str_radix(&x[i..i + 2], 16).unwrap()).collect();
    let sk = p256::ecdsa::SigningKey::from_slice(&x).unwrap();
    let pk = compressed(&sk);
    let sig = keystore_sign(&sk, b"sample");
    send(
        &mut svm,
        &payer,
        &[precompile_ix(&[(&sig, &pk, b"sample")]), verify_ix(0, 0, &pk, b"sample")],
    )
    .unwrap();
}

// ------------------------------------------------------------ negative ---

#[test]
fn wrong_message_is_rejected() {
    let (mut svm, payer) = setup();
    let sk = key(10);
    let pk = compressed(&sk);
    let signed = heartbeat_message(&RIG, 42, 1);
    let expected = heartbeat_message(&RIG, 42, 2);
    let sig = keystore_sign(&sk, &signed);
    assert_introspect_err(
        send(
            &mut svm,
            &payer,
            &[precompile_ix(&[(&sig, &pk, &signed)]), verify_ix(0, 0, &pk, &expected)],
        ),
        1,
        E::MessageMismatch,
    );
}

#[test]
fn wrong_pubkey_is_rejected() {
    let (mut svm, payer) = setup();
    let attacker = key(11);
    let victim_pk = compressed(&key(12));
    let attacker_pk = compressed(&attacker);
    let msg = heartbeat_message(&RIG, 42, 1);
    let sig = keystore_sign(&attacker, &msg);
    assert_introspect_err(
        send(
            &mut svm,
            &payer,
            &[precompile_ix(&[(&sig, &attacker_pk, &msg)]), verify_ix(0, 0, &victim_pk, &msg)],
        ),
        1,
        E::PublicKeyMismatch,
    );
    // Claiming the victim's key inside the precompile fails the precompile.
    assert_ix_err(
        send(
            &mut svm,
            &payer,
            &[precompile_ix(&[(&sig, &victim_pk, &msg)]), verify_ix(0, 0, &victim_pk, &msg)],
        ),
        0,
        PRECOMPILE_INVALID_SIGNATURE,
    );
}

#[test]
fn high_s_signature_is_rejected_by_the_precompile() {
    let (mut svm, payer) = setup();
    let sk = key(13);
    let pk = compressed(&sk);
    let msg = heartbeat_message(&RIG, 42, 1);
    let low = keystore_sign(&sk, &msg);
    // The malleable twin (r, n - s): mathematically valid ECDSA, high-S.
    let mut high = low;
    let s: [u8; 32] = low[32..].try_into().unwrap();
    high[32..].copy_from_slice(&sub_be(&SECP256R1_ORDER, &s));
    assert!(!p256_introspect::is_low_s(high[32..].try_into().unwrap()));
    // It really is a valid signature (p256 does not enforce low-S)...
    {
        use p256::ecdsa::signature::Verifier;
        let vk = p256::ecdsa::VerifyingKey::from_sec1_bytes(&pk).unwrap();
        let sig = p256::ecdsa::Signature::from_slice(&high).unwrap();
        vk.verify(&msg, &sig).expect("high-S twin is valid ECDSA");
    }
    // ...but the precompile refuses it, so the transaction fails at ix 0.
    assert_ix_err(
        send(
            &mut svm,
            &payer,
            &[precompile_ix(&[(&high, &pk, &msg)]), verify_ix(0, 0, &pk, &msg)],
        ),
        0,
        PRECOMPILE_INVALID_SIGNATURE,
    );
    // The introspection layer rejects it independently (tested at unit level
    // in crates/p256-introspect/tests/parser.rs::rejects_high_s_and_out_of_range_scalars,
    // because a high-S entry can never get past the precompile here).
    let data = p256_introspect::client::build_instruction_data(&[
        p256_introspect::client::SignatureInput {
            signature: high,
            public_key: pk,
            message: &msg,
        },
    ])
    .unwrap();
    assert_eq!(
        p256_introspect::Secp256r1Instruction::parse(&data, 0).unwrap_err(),
        E::HighS
    );
}

#[test]
fn offsets_pointing_into_another_instruction_are_rejected() {
    // Wormhole-class substitution. The attacker has a genuinely valid
    // signature by *their* key over *their* message in instruction 0. In
    // instruction 1 (the precompile the program is told to look at) the
    // offsets name instruction 0, so the precompile verifies instruction 0's
    // bytes and passes, while instruction 1's own bytes at the same offsets
    // hold the victim's key and the message the program expects.
    let (mut svm, payer) = setup();
    let attacker = key(20);
    let attacker_pk = compressed(&attacker);
    let attacker_msg = heartbeat_message(&[0xEE; 32], 42, 1);
    let attacker_sig = keystore_sign(&attacker, &attacker_msg);

    let victim_pk = compressed(&key(21));
    let victim_msg = heartbeat_message(&RIG, 42, 1);
    assert_eq!(victim_msg.len(), attacker_msg.len());

    let carrier = precompile_ix(&[(&attacker_sig, &attacker_pk, &attacker_msg)]);
    let mut forged = carrier.data.clone();
    // Same layout: pubkey at 16, signature at 49, message at 113.
    forged[16..49].copy_from_slice(&victim_pk);
    forged[49..113].fill(0x42); // no valid signature for the victim exists
    forged[113..].copy_from_slice(&victim_msg);
    for idx_field in [4usize, 8, 14] {
        forged[idx_field..idx_field + 2].copy_from_slice(&0u16.to_le_bytes());
    }
    // A naive parser that ignores the index fields would read exactly what
    // the program expects from instruction 1:
    assert_eq!(&forged[16..49], &victim_pk);
    assert_eq!(&forged[113..], victim_msg.as_slice());

    let res = send(
        &mut svm,
        &payer,
        &[
            carrier,
            precompile_ix_from_data(forged),
            verify_ix(1, 0, &victim_pk, &victim_msg),
        ],
    );
    // Instruction 0 and 1 (both precompiles) passed; our check at ix 2 fired.
    assert_introspect_err(res, 2, E::ForeignInstructionIndex);
}

#[test]
fn spoofed_instructions_sysvar_is_rejected() {
    // A fake account whose data is a perfect instructions-sysvar image
    // claiming ix 0 is a secp256r1 precompile that verified the victim's key
    // over the expected message. No precompile is in the real transaction.
    let (mut svm, payer) = setup();
    let victim_pk = compressed(&key(30));
    let msg = heartbeat_message(&RIG, 42, 1);
    let fake_sig = keystore_sign(&key(31), &msg); // any in-range low-S bytes
    let forged_precompile = precompile_ix(&[(&fake_sig, &victim_pk, &msg)]).data;
    let forged = forge_instructions_sysvar(
        &[
            (secp256r1_program_id(), forged_precompile),
            (PROGRAM_ID, vec![]),
        ],
        1,
    );
    // The forged image is well-formed enough to pass the parser...
    let s = p256_introspect::InstructionsSysvar::from_bytes(&forged).unwrap();
    p256_introspect::load_secp256r1_instruction(&s, 0)
        .unwrap()
        .expect_entry(0, &victim_pk, &msg)
        .unwrap();

    // ...so only the address check stands between it and acceptance. Try it
    // both with a plain system-owned account and one that even claims the
    // Sysvar owner.
    let sysvar_owner = Address::from_str_const("Sysvar1111111111111111111111111111111111111");
    for owner in [Address::default(), sysvar_owner] {
        let fake = Address::new_from_array([0xF4; 32]);
        svm.set_account(
            fake,
            Account {
                lamports: 1_000_000_000,
                data: forged.clone(),
                owner,
                executable: false,
                rent_epoch: 0,
            },
        )
        .unwrap();
        svm.expire_blockhash();
        assert_introspect_err(
            send(
                &mut svm,
                &payer,
                &[verify_ix_with_sysvar(fake, 0, 0, &victim_pk, &msg)],
            ),
            0,
            E::InvalidInstructionsSysvar,
        );
    }
}

#[test]
fn missing_precompile_instruction_is_rejected() {
    let (mut svm, payer) = setup();
    let pk = compressed(&key(40));
    let msg = heartbeat_message(&RIG, 42, 1);
    // Index 0 is the verifying instruction itself, not a precompile.
    assert_introspect_err(
        send(&mut svm, &payer, &[verify_ix(0, 0, &pk, &msg)]),
        0,
        E::NotSecp256r1Instruction,
    );
    // Index past the end of the transaction.
    assert_introspect_err(
        send(&mut svm, &payer, &[verify_ix(1, 0, &pk, &msg)]),
        0,
        E::InstructionIndexOutOfBounds,
    );
    assert_introspect_err(
        send(&mut svm, &payer, &[verify_ix(u16::MAX, 0, &pk, &msg)]),
        0,
        E::InstructionIndexOutOfBounds,
    );
}

#[test]
fn replayed_signature_for_a_different_message_is_rejected() {
    let (mut svm, payer) = setup();
    let sk = key(50);
    let pk = compressed(&sk);
    let round_42 = heartbeat_message(&RIG, 42, 1);
    let round_43 = heartbeat_message(&RIG, 43, 2);
    let sig_42 = keystore_sign(&sk, &round_42);

    // (a) Re-use round 42's signature over round 43's message: precompile fails.
    assert_ix_err(
        send(
            &mut svm,
            &payer,
            &[precompile_ix(&[(&sig_42, &pk, &round_43)]), verify_ix(0, 0, &pk, &round_43)],
        ),
        0,
        PRECOMPILE_INVALID_SIGNATURE,
    );
    // (b) Replay round 42's valid (signature, message) while the program
    // expects round 43: introspection fails on the message.
    assert_introspect_err(
        send(
            &mut svm,
            &payer,
            &[precompile_ix(&[(&sig_42, &pk, &round_42)]), verify_ix(0, 0, &pk, &round_43)],
        ),
        1,
        E::MessageMismatch,
    );
    // (c) The malleated twin of round 42's signature cannot be used either.
    let mut twin = sig_42;
    let s: [u8; 32] = sig_42[32..].try_into().unwrap();
    twin[32..].copy_from_slice(&sub_be(&SECP256R1_ORDER, &s));
    assert_ix_err(
        send(
            &mut svm,
            &payer,
            &[precompile_ix(&[(&twin, &pk, &round_42)]), verify_ix(0, 0, &pk, &round_42)],
        ),
        0,
        PRECOMPILE_INVALID_SIGNATURE,
    );
}

#[test]
fn stateless_spike_accepts_an_identical_replay_so_the_program_must_bind_a_counter() {
    // Documenting the boundary: the precompile + introspection prove "this key
    // signed this message". They do NOT prove freshness. Re-sending the same
    // heartbeat in a new transaction passes this stateless spike; heads_down
    // rejects it because the message binds ore_round_id and a strictly
    // increasing counter stored in the Rig account.
    let (mut svm, payer) = setup();
    let sk = key(51);
    let pk = compressed(&sk);
    let msg = heartbeat_message(&RIG, 42, 1);
    let sig = keystore_sign(&sk, &msg);
    let ixs = [precompile_ix(&[(&sig, &pk, &msg)]), verify_ix(0, 0, &pk, &msg)];
    send(&mut svm, &payer, &ixs).unwrap();
    // Same transaction bytes again: rejected by the runtime's signature dedup.
    assert_eq!(
        send(&mut svm, &payer, &ixs).unwrap_err().err,
        solana_transaction_error::TransactionError::AlreadyProcessed
    );
    // Same heartbeat, new blockhash: accepted by the stateless spike.
    svm.expire_blockhash();
    send(&mut svm, &payer, &ixs).unwrap();
}

#[test]
fn signature_index_beyond_count_is_rejected() {
    let (mut svm, payer) = setup();
    let sk = key(60);
    let pk = compressed(&sk);
    let msg = heartbeat_message(&RIG, 42, 1);
    let sig = keystore_sign(&sk, &msg);
    assert_introspect_err(
        send(
            &mut svm,
            &payer,
            &[precompile_ix(&[(&sig, &pk, &msg)]), verify_ix(0, 1, &pk, &msg)],
        ),
        1,
        E::SignatureIndexOutOfBounds,
    );
}

#[test]
fn precompile_itself_rejects_offsets_past_its_data() {
    // Sanity check that the precompile and this crate agree on bounds.
    let (mut svm, payer) = setup();
    let sk = key(61);
    let pk = compressed(&sk);
    let msg = heartbeat_message(&RIG, 42, 1);
    let sig = keystore_sign(&sk, &msg);
    let mut data = precompile_ix(&[(&sig, &pk, &msg)]).data;
    // message_data_size += 1
    let size = u16::from_le_bytes([data[12], data[13]]) + 1;
    data[12..14].copy_from_slice(&size.to_le_bytes());
    assert_ix_err(
        send(
            &mut svm,
            &payer,
            &[precompile_ix_from_data(data.clone()), verify_ix(0, 0, &pk, &msg)],
        ),
        0,
        PRECOMPILE_INVALID_DATA_OFFSETS,
    );
    assert_eq!(
        p256_introspect::Secp256r1Instruction::parse(&data, 0).unwrap_err(),
        E::OffsetOutOfBounds
    );
}

/// Big-endian a - b for 32-byte integers with a >= b.
fn sub_be(a: &[u8; 32], b: &[u8; 32]) -> [u8; 32] {
    let mut out = [0u8; 32];
    let mut borrow = 0i16;
    for i in (0..32).rev() {
        let mut d = a[i] as i16 - b[i] as i16 - borrow;
        borrow = if d < 0 {
            d += 256;
            1
        } else {
            0
        };
        out[i] = d as u8;
    }
    assert_eq!(borrow, 0);
    out
}

#[test]
fn payer_is_charged_one_signature_fee_per_p256_signature() {
    let (mut svm, payer) = setup();
    let keys: Vec<_> = (0..8u8).map(|i| key(70 + i)).collect();
    let msgs: Vec<Vec<u8>> = (0..8u64).map(|i| heartbeat_message(&RIG, 42, i)).collect();
    let sigs: Vec<[u8; 64]> = keys.iter().zip(&msgs).map(|(k, m)| keystore_sign(k, m)).collect();
    let pks: Vec<[u8; 33]> = keys.iter().map(compressed).collect();
    let entries: Vec<(&[u8; 64], &[u8; 33], &[u8])> = (0..8)
        .map(|i| (&sigs[i], &pks[i], msgs[i].as_slice()))
        .collect();
    let before = svm.get_balance(&payer.pubkey()).unwrap();
    let meta = send(&mut svm, &payer, &[precompile_ix(&entries)]).unwrap();
    assert_eq!(meta.fee, 5_000 * (1 + 8));
    assert_eq!(before - svm.get_balance(&payer.pubkey()).unwrap(), 45_000);
}
