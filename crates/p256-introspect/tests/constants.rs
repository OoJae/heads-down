//! Cross-check every hard-coded constant against an independent source.

use p256::{
    elliptic_curve::{ff::PrimeField, scalar::IsHigh},
    Scalar,
};
use p256_introspect::*;

#[test]
fn program_ids_match_their_base58_names() {
    assert_eq!(
        SECP256R1_PROGRAM_ID,
        solana_address::Address::from_str_const("Secp256r1SigVerify1111111111111111111111111")
    );
    assert_eq!(
        INSTRUCTIONS_SYSVAR_ID,
        solana_address::Address::from_str_const("Sysvar1nstructions1111111111111111111111111")
    );
    // And pinocchio agrees on the sysvar id.
    assert_eq!(INSTRUCTIONS_SYSVAR_ID, pinocchio::sysvars::instructions::INSTRUCTIONS_ID);
}

#[test]
fn wire_sizes_match_simd_0075() {
    assert_eq!(SIGNATURE_OFFSETS_START, 2);
    assert_eq!(SIGNATURE_OFFSETS_SERIALIZED_SIZE, 7 * 2);
    assert_eq!(DATA_START, 16);
    assert_eq!(COMPRESSED_PUBKEY_SERIALIZED_SIZE, 1 + 32);
    assert_eq!(SIGNATURE_SERIALIZED_SIZE, 2 * FIELD_SIZE);
    assert_eq!(MAX_SIGNATURES_PER_INSTRUCTION, 8);
    assert_eq!(CURRENT_INSTRUCTION, 0xFFFF);
}

fn scalar(bytes: [u8; 32]) -> Scalar {
    Option::from(Scalar::from_repr(bytes.into())).expect("canonical scalar")
}

#[test]
fn order_constants_match_p256_arithmetic() {
    // n - 1 == -1 mod n.
    let minus_one: [u8; 32] = (-Scalar::ONE).to_bytes().into();
    assert_eq!(minus_one, SECP256R1_ORDER_MINUS_ONE);
    // n itself is not a canonical scalar; n - 1 is.
    assert!(bool::from(Scalar::from_repr(SECP256R1_ORDER.into()).is_none()));
    // Half order: the largest scalar p256 considers "low".
    let half = scalar(SECP256R1_HALF_ORDER);
    assert!(!bool::from(half.is_high()));
    assert!(bool::from((half + Scalar::ONE).is_high()));
    // 2 * half + 1 == n  <=>  2 * half + 1 == 0 mod n.
    assert_eq!(half + half + Scalar::ONE, Scalar::ZERO);
}

#[test]
fn is_low_s_agrees_with_p256_on_random_scalars() {
    let mut x: u64 = 0x1234_5678_9ABC_DEF1;
    for _ in 0..20_000 {
        let mut b = [0u8; 32];
        for chunk in b.chunks_mut(8) {
            x ^= x << 13;
            x ^= x >> 7;
            x ^= x << 17;
            chunk.copy_from_slice(&x.to_be_bytes());
        }
        if let Some(s) = Option::<Scalar>::from(Scalar::from_repr(b.into())) {
            assert_eq!(is_low_s(&b), !bool::from(s.is_high()), "{b:02x?}");
        }
    }
}
