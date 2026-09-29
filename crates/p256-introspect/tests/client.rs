//! Host helper tests (feature `client`): Android Keystore format conversions
//! and the instruction-data builder.

use p256::ecdsa::{signature::Signer, DerSignature, SigningKey, VerifyingKey};
use p256_introspect::{client::*, is_low_s, Secp256r1Instruction};

fn hex(s: &str) -> Vec<u8> {
    (0..s.len())
        .step_by(2)
        .map(|i| u8::from_str_radix(&s[i..i + 2], 16).unwrap())
        .collect()
}

fn key(seed: u8) -> SigningKey {
    SigningKey::from_slice(&[seed.max(1); 32]).unwrap()
}

/// What Android's `Signature.getInstance("SHA256withECDSA").sign()` returns:
/// SHA-256 over the message, ECDSA, ASN.1 DER, S not normalized.
fn keystore_sign(sk: &SigningKey, msg: &[u8]) -> Vec<u8> {
    let der: DerSignature = sk.sign(msg);
    der.as_bytes().to_vec()
}

fn compressed(vk: &VerifyingKey) -> [u8; 33] {
    vk.to_sec1_point(true).as_bytes().try_into().unwrap()
}

/// DER component lengths (r_len, s_len) of an ECDSA-Sig-Value.
fn der_component_lens(der: &[u8]) -> (usize, usize) {
    assert_eq!(der[0], 0x30);
    assert_eq!(der[2], 0x02);
    let r_len = der[3] as usize;
    assert_eq!(der[4 + r_len], 0x02);
    (r_len, der[5 + r_len] as usize)
}

// RFC 6979 appendix A.2.5, ECDSA P-256 with SHA-256.
const RFC6979_X: &str = "C9AFA9D845BA75166B5C215767B1D6934E50C3DB36E89B127B8A622B120F6721";
const RFC6979_UX: &str = "60FED4BA255A9D31C961EB74C6356D68C049B8923B61FA6CE669622E60F29FB6";
const RFC6979_UY: &str = "7903FE1008B8BC99A41AE9E95628BC64F2F1B20C2D7E9F5177A3C294D4462299";
const RFC6979_SAMPLE_R: &str = "EFD48B2AACB6A8FD1140DD9CD45E81D69D2C877B56AAF991C34D0EA84EAF3716";
const RFC6979_SAMPLE_S: &str = "F7CB1C942D657C41D436C7A1B6E29F65F3E900DBB9AFF4064DC4AB2F843ACDA8";
const RFC6979_TEST_R: &str = "F1ABB023518351CD71D881567B1EA663ED3EFCF6C5132B354F28D3B0B7D38367";
const RFC6979_TEST_S: &str = "019F4113742A2B14BD25926B49C649155F267E60D3814B4C0CC84250E46F0083";

#[test]
fn rfc6979_known_answer_high_s_vector_normalizes_and_verifies() {
    let sk = SigningKey::from_slice(&hex(RFC6979_X)).unwrap();
    let mut uncompressed = vec![0x04];
    uncompressed.extend(hex(RFC6979_UX));
    uncompressed.extend(hex(RFC6979_UY));
    let pk = compress_public_key(&uncompressed).unwrap();
    assert_eq!(pk, compressed(sk.verifying_key()));
    // Uy ends in 0x99: odd, so prefix 0x03.
    assert_eq!(pk[0], 0x03);

    let der = keystore_sign(&sk, b"sample");
    let raw = der_to_raw(&der).unwrap();
    assert_eq!(&raw[..32], hex(RFC6979_SAMPLE_R).as_slice());
    assert_eq!(&raw[32..], hex(RFC6979_SAMPLE_S).as_slice());
    // The published vector is high-S: the precompile would reject it as-is.
    assert!(!is_low_s(raw[32..].try_into().unwrap()));
    assert_eq!(verify_like_precompile(&pk, b"sample", &raw), Err(ClientError::HighS));

    let low = der_to_low_s_raw(&der).unwrap();
    assert_eq!(low[..32], raw[..32], "r is unchanged");
    assert!(is_low_s(low[32..].try_into().unwrap()));
    verify_like_precompile(&pk, b"sample", &low).unwrap();
    assert_eq!(normalize_low_s(&raw).unwrap(), low);
    assert_eq!(normalize_low_s(&low).unwrap(), low, "idempotent");

    // Second vector ("test") is already low-S and passes through unchanged.
    let der = keystore_sign(&sk, b"test");
    let raw = der_to_low_s_raw(&der).unwrap();
    assert_eq!(&raw[..32], hex(RFC6979_TEST_R).as_slice());
    assert_eq!(&raw[32..], hex(RFC6979_TEST_S).as_slice());
    verify_like_precompile(&pk, b"test", &raw).unwrap();
}

#[test]
fn keystore_style_signatures_roundtrip_and_about_half_need_normalizing() {
    let mut high = 0;
    for i in 0..128u32 {
        let sk = key((i % 250) as u8 + 1);
        let msg = format!("heartbeat #{i}");
        let der = keystore_sign(&sk, msg.as_bytes());
        let raw = der_to_raw(&der).unwrap();
        if !is_low_s(raw[32..].try_into().unwrap()) {
            high += 1;
        }
        let low = der_to_low_s_raw(&der).unwrap();
        let pk = compressed(sk.verifying_key());
        verify_like_precompile(&pk, msg.as_bytes(), &low).unwrap();
        // Bound to the message: any other message fails.
        assert_eq!(
            verify_like_precompile(&pk, b"another message", &low),
            Err(ClientError::VerificationFailed)
        );
        // Bound to the key.
        let other = compressed(key((i % 250) as u8 + 2).verifying_key());
        assert_eq!(
            verify_like_precompile(&other, msg.as_bytes(), &low),
            Err(ClientError::VerificationFailed)
        );
    }
    // p256 (like Android Keystore) does not normalize S; expect roughly 64.
    assert!((20..=108).contains(&high), "high-S count {high} of 128");
}

#[test]
fn short_der_components_are_left_padded() {
    // Search for signatures whose r or s has fewer than 32 significant bytes
    // (DER drops leading zeros), which the raw form must left-pad.
    let sk = key(42);
    let mut found_short_r = false;
    let mut found_short_s = false;
    for i in 0..20_000u32 {
        let msg = i.to_le_bytes();
        let der = keystore_sign(&sk, &msg);
        let (r_len, s_len) = der_component_lens(&der);
        let short_r = r_len < 32;
        let short_s = s_len < 32;
        if !(short_r || short_s) {
            continue;
        }
        let raw = der_to_raw(&der).unwrap();
        if short_r {
            assert_eq!(raw[0], 0);
            found_short_r = true;
        }
        if short_s {
            assert_eq!(raw[32], 0);
            found_short_s = true;
        }
        let low = normalize_low_s(&raw).unwrap();
        verify_like_precompile(&compressed(sk.verifying_key()), &msg, &low).unwrap();
        if found_short_r && found_short_s {
            return;
        }
    }
    panic!("no short components found (r: {found_short_r}, s: {found_short_s})");
}

#[test]
fn der_parsing_is_strict() {
    let sk = key(3);
    let der = keystore_sign(&sk, b"x");
    assert!(der_to_raw(&der).is_ok());

    // Trailing garbage.
    let mut bad = der.clone();
    bad.push(0);
    assert_eq!(der_to_raw(&bad), Err(ClientError::InvalidDerSignature));
    // Truncated.
    assert_eq!(der_to_raw(&der[..der.len() - 1]), Err(ClientError::InvalidDerSignature));
    // Wrong outer tag.
    let mut bad = der.clone();
    bad[0] = 0x31;
    assert_eq!(der_to_raw(&bad), Err(ClientError::InvalidDerSignature));
    // Raw r||s is not DER.
    let raw = der_to_raw(&der).unwrap();
    assert_eq!(der_to_raw(&raw), Err(ClientError::InvalidDerSignature));
    // Non-minimal integer (extra leading zero).
    let (r_len, _) = der_component_lens(&der);
    let mut bad = vec![0x30, der[1] + 1, 0x02, r_len as u8 + 1, 0x00];
    bad.extend_from_slice(&der[4..]);
    assert_eq!(der_to_raw(&bad), Err(ClientError::InvalidDerSignature));
    // r = 0.
    let zero_r = [0x30, 0x06, 0x02, 0x01, 0x00, 0x02, 0x01, 0x01];
    assert_eq!(der_to_raw(&zero_r), Err(ClientError::InvalidDerSignature));
    // Empty.
    assert_eq!(der_to_raw(&[]), Err(ClientError::InvalidDerSignature));

    // Raw signatures with out-of-range scalars.
    assert_eq!(normalize_low_s(&[0u8; 64]), Err(ClientError::InvalidRawSignature));
    assert_eq!(normalize_low_s(&[0xFFu8; 64]), Err(ClientError::InvalidRawSignature));
}

/// X.509 SubjectPublicKeyInfo prefix for an uncompressed P-256 key, exactly
/// what Android `PublicKey.getEncoded()` returns before the 65-byte point:
/// SEQUENCE { SEQUENCE { id-ecPublicKey, prime256v1 }, BIT STRING { 04 x y } }.
const SPKI_P256_PREFIX: &str = "3059301306072a8648ce3d020106082a8648ce3d030107034200";

#[test]
fn public_key_conversions() {
    let sk = key(9);
    let vk = sk.verifying_key();
    let expect = compressed(vk);
    let uncompressed = vk.to_sec1_point(false);
    assert_eq!(uncompressed.as_bytes().len(), 65);

    assert_eq!(compress_public_key(uncompressed.as_bytes()).unwrap(), expect);
    assert_eq!(compress_public_key(&expect).unwrap(), expect, "already compressed");

    let mut spki = hex(SPKI_P256_PREFIX);
    spki.extend_from_slice(uncompressed.as_bytes());
    assert_eq!(spki.len(), 91);
    assert_eq!(spki_der_to_compressed(&spki).unwrap(), expect);

    // Off-curve point.
    let mut off = uncompressed.as_bytes().to_vec();
    off[64] ^= 1;
    assert_eq!(compress_public_key(&off), Err(ClientError::InvalidPublicKey));
    let mut spki_off = hex(SPKI_P256_PREFIX);
    spki_off.extend_from_slice(&off);
    assert_eq!(spki_der_to_compressed(&spki_off), Err(ClientError::InvalidPublicKey));
    // Wrong curve OID (secp256k1, 1.3.132.0.10) with the same point bytes.
    let mut k1 = hex("3056301006072a8648ce3d020106052b8104000a034200");
    k1.extend_from_slice(uncompressed.as_bytes());
    assert_eq!(spki_der_to_compressed(&k1), Err(ClientError::InvalidPublicKey));
    // Garbage / truncated.
    assert_eq!(compress_public_key(&[0x04; 10]), Err(ClientError::InvalidPublicKey));
    assert_eq!(spki_der_to_compressed(&spki[..90]), Err(ClientError::InvalidPublicKey));
    assert_eq!(compress_public_key(&[]), Err(ClientError::InvalidPublicKey));
}

#[test]
fn builder_matches_sdk_layout_for_one_signature() {
    let sk = key(5);
    let msg = b"hello precompile";
    let sig = der_to_low_s_raw(&keystore_sign(&sk, msg)).unwrap();
    let pk = compressed(sk.verifying_key());
    let data = build_instruction_data(&[SignatureInput {
        signature: sig,
        public_key: pk,
        message: msg,
    }])
    .unwrap();

    // Same layout as solana-secp256r1-program 3.0.0
    // new_secp256r1_instruction_with_signature: pubkey at 16, sig at 49,
    // message at 113, all indices 0xFFFF.
    assert_eq!(data[0], 1);
    assert_eq!(data[1], 0);
    let rd = |i: usize| u16::from_le_bytes([data[i], data[i + 1]]);
    assert_eq!(rd(2), 49); // signature_offset
    assert_eq!(rd(4), 0xFFFF);
    assert_eq!(rd(6), 16); // public_key_offset
    assert_eq!(rd(8), 0xFFFF);
    assert_eq!(rd(10), 113); // message_data_offset
    assert_eq!(rd(12), msg.len() as u16);
    assert_eq!(rd(14), 0xFFFF);
    assert_eq!(&data[16..49], &pk);
    assert_eq!(&data[49..113], &sig);
    assert_eq!(&data[113..], msg);
    assert_eq!(data.len(), instruction_data_len(1, msg.len()));
}

#[test]
fn builder_output_parses_on_chain_for_1_to_8_signatures() {
    for n in 1..=8usize {
        let msgs: Vec<Vec<u8>> = (0..n).map(|i| format!("rig {i} round 99").into_bytes()).collect();
        let keys: Vec<SigningKey> = (0..n).map(|i| key(i as u8 + 10)).collect();
        let inputs: Vec<SignatureInput> = (0..n)
            .map(|i| SignatureInput {
                signature: der_to_low_s_raw(&keystore_sign(&keys[i], &msgs[i])).unwrap(),
                public_key: compressed(keys[i].verifying_key()),
                message: &msgs[i],
            })
            .collect();
        for (data, own) in [
            (build_instruction_data(&inputs).unwrap(), 3u16),
            (build_instruction_data_with_index(&inputs, 3).unwrap(), 3u16),
        ] {
            let ix = Secp256r1Instruction::parse(&data, own).unwrap();
            assert_eq!(ix.num_signatures() as usize, n);
            for (i, input) in inputs.iter().enumerate() {
                let e = ix.expect_entry(i as u8, &input.public_key, input.message).unwrap();
                assert_eq!(e.signature, &input.signature);
                verify_like_precompile(e.public_key, e.message, e.signature).unwrap();
            }
        }
        // Same data parsed as if it sat at another index: only 0xFFFF data survives.
        let fixed = build_instruction_data_with_index(&inputs, 3).unwrap();
        assert!(Secp256r1Instruction::parse(&fixed, 2).is_err());
        assert!(Secp256r1Instruction::parse(&build_instruction_data(&inputs).unwrap(), 2).is_ok());
    }
}

#[test]
fn builder_rejects_bad_counts_and_oversize() {
    let input = SignatureInput {
        signature: [1u8; 64],
        public_key: [2u8; 33],
        message: b"m",
    };
    assert_eq!(build_instruction_data(&[]), Err(ClientError::InvalidSignatureCount));
    assert_eq!(build_instruction_data(&[input; 9]), Err(ClientError::InvalidSignatureCount));
    let big = vec![0u8; 70_000];
    let huge = SignatureInput {
        message: &big,
        ..input
    };
    assert_eq!(build_instruction_data(&[huge]), Err(ClientError::InstructionTooLarge));
    // Largest message that still fits in u16-addressable data.
    let max_msg = u16::MAX as usize - instruction_data_len(1, 0);
    let fits = vec![0u8; max_msg];
    let ok = SignatureInput {
        message: &fits,
        ..input
    };
    assert_eq!(build_instruction_data(&[ok]).unwrap().len(), u16::MAX as usize);
    let over = vec![0u8; max_msg + 1];
    let over = SignatureInput {
        message: &over,
        ..input
    };
    assert_eq!(build_instruction_data(&[over]), Err(ClientError::InstructionTooLarge));
}
