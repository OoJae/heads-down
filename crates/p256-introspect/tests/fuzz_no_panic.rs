//! Randomized robustness: arbitrary mutations of sysvar and precompile bytes
//! must only ever produce `Ok` or an `IntrospectError`, never a panic, and
//! anything accepted must still satisfy the invariants.

mod common;

use common::*;
use p256_introspect::{
    is_low_s, load_secp256r1_instruction, InstructionsSysvar, Secp256r1Instruction,
    CURRENT_INSTRUCTION,
};

fn check_invariants(ix: &Secp256r1Instruction<'_>, data: &[u8], own: u16) {
    for e in ix.entries() {
        let e = e.expect("parse validated every entry");
        for idx in [
            e.offsets.signature_instruction_index,
            e.offsets.public_key_instruction_index,
            e.offsets.message_instruction_index,
        ] {
            assert!(idx == CURRENT_INSTRUCTION || idx == own);
        }
        assert!(is_low_s(e.signature[32..].try_into().unwrap()));
        assert!(matches!(e.public_key[0], 0x02 | 0x03));
        let end = e.offsets.message_data_offset as usize + e.offsets.message_data_size as usize;
        assert!(end <= data.len());
    }
}

#[test]
fn mutated_precompile_data_never_panics() {
    let mut rng = XorShift(0x5EED_1234_ABCD_0001);
    let base = precompile_data(
        &[
            (fake_sig(1), fake_pubkey(1), b"first message".as_slice()),
            (fake_sig(2), fake_pubkey(2), b"second".as_slice()),
        ],
        CURRENT_INSTRUCTION,
    );
    let mut accepted = 0u32;
    for _ in 0..200_000 {
        let mut data = base.clone();
        for _ in 0..=rng.below(6) {
            match rng.below(4) {
                0 => {
                    let i = rng.below(data.len());
                    data[i] = rng.next() as u8;
                }
                1 => {
                    // Mutate the header/offsets region, where parsing decisions live.
                    let i = rng.below(30.min(data.len()));
                    data[i] = rng.next() as u8;
                }
                2 => data.truncate(rng.below(data.len() + 1)),
                _ => data.extend((0..rng.below(8)).map(|_| rng.next() as u8)),
            }
            if data.is_empty() {
                break;
            }
        }
        let own = rng.below(4) as u16;
        if let Ok(ix) = Secp256r1Instruction::parse(&data, own) {
            accepted += 1;
            check_invariants(&ix, &data, own);
        }
    }
    // Sanity: the fuzzer is not rejecting everything trivially.
    assert!(accepted > 1000, "accepted only {accepted}");
}

#[test]
fn mutated_sysvar_never_panics() {
    let mut rng = XorShift(0xC0FF_EE00_1234_5678);
    let mut ix0 = Ix::new([3u8; 32], vec![7u8; 17]);
    ix0.accounts = vec![(true, false, [4u8; 32])];
    let base = sysvar_bytes(&[ix0, Ix::secp(one_entry(b"hello")), Ix::new([5u8; 32], vec![])], 2);
    for _ in 0..200_000 {
        let mut bytes = base.clone();
        for _ in 0..=rng.below(4) {
            match rng.below(3) {
                0 => {
                    let i = rng.below(bytes.len());
                    bytes[i] = rng.next() as u8;
                }
                1 => bytes.truncate(rng.below(bytes.len() + 1)),
                _ => {
                    // Target the header and first instruction's framing.
                    let i = rng.below(12.min(bytes.len().max(1)));
                    if i < bytes.len() {
                        bytes[i] = rng.next() as u8;
                    }
                }
            }
            if bytes.is_empty() {
                break;
            }
        }
        if let Ok(s) = InstructionsSysvar::from_bytes(&bytes) {
            for i in 0..s.num_instructions().min(8) {
                if let Ok(ix) = s.instruction(i) {
                    for a in 0..ix.num_accounts().min(4) {
                        let _ = ix.account(a);
                    }
                }
                if let Ok(p) = load_secp256r1_instruction(&s, i) {
                    let data = s.instruction(i).unwrap().data();
                    check_invariants(&p, data, i);
                }
            }
        }
    }
}
