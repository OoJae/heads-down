//! Parser tests: instructions sysvar + SIMD-0075 layout, positive and negative.

mod common;

use common::*;
use p256_introspect::{
    load_secp256r1_instruction, IntrospectError as E, InstructionsSysvar, Secp256r1Instruction,
    CURRENT_INSTRUCTION, SECP256R1_HALF_ORDER, SECP256R1_ORDER, SECP256R1_ORDER_MINUS_ONE,
};

const OTHER_PROGRAM: [u8; 32] = [9u8; 32];
const MSG: &[u8] = b"HDv1 heartbeat round=42 counter=7";

/// (message, public key, signature) of entry 0.
type Loaded = (Vec<u8>, [u8; 33], [u8; 64]);
type Patch = Box<dyn Fn(&mut p256_introspect::Secp256r1SignatureOffsets)>;

fn load(ixs: &[Ix], current: u16, at: u16) -> Result<Loaded, E> {
    let bytes = sysvar_bytes(ixs, current);
    let sysvar = InstructionsSysvar::from_bytes(&bytes)?;
    let ix = load_secp256r1_instruction(&sysvar, at)?;
    let e = ix.entry(0)?;
    Ok((e.message.to_vec(), *e.public_key, *e.signature))
}

// ---------------------------------------------------------------- sysvar ---

#[test]
fn sysvar_roundtrip_reads_every_field() {
    let mut ix0 = Ix::new(OTHER_PROGRAM, vec![1, 2, 3]);
    ix0.accounts = vec![(true, true, [1u8; 32]), (false, true, [2u8; 32]), (false, false, [3u8; 32])];
    let ixs = [ix0, Ix::secp(one_entry(MSG)), Ix::new(OTHER_PROGRAM, vec![])];
    let bytes = sysvar_bytes(&ixs, 2);
    let s = InstructionsSysvar::from_bytes(&bytes).unwrap();
    assert_eq!(s.num_instructions(), 3);
    assert_eq!(s.current_index(), 2);
    let i0 = s.instruction(0).unwrap();
    assert_eq!(i0.program_id(), &OTHER_PROGRAM);
    assert_eq!(i0.data(), &[1, 2, 3]);
    assert_eq!(i0.num_accounts(), 3);
    assert_eq!(i0.account(0).unwrap(), (true, true, &[1u8; 32]));
    assert_eq!(i0.account(1).unwrap(), (false, true, &[2u8; 32]));
    assert_eq!(i0.account(2).unwrap(), (false, false, &[3u8; 32]));
    assert_eq!(i0.account(3).unwrap_err(), E::InstructionIndexOutOfBounds);
    assert_eq!(s.instruction(2).unwrap().data(), &[] as &[u8]);
    assert_eq!(s.instruction(3).unwrap_err(), E::InstructionIndexOutOfBounds);
}

#[test]
fn sysvar_rejects_truncated_and_inconsistent_data() {
    // Too short for num_instructions + current index.
    for len in 0..4 {
        assert_eq!(
            InstructionsSysvar::from_bytes(&vec![0u8; len]).unwrap_err(),
            E::MalformedInstructionsSysvar
        );
    }
    // Claims 5 instructions but has no offsets table.
    assert_eq!(
        InstructionsSysvar::from_bytes(&[5, 0, 0, 0]).unwrap_err(),
        E::MalformedInstructionsSysvar
    );
    // Current index out of range.
    let bytes = sysvar_bytes(&[Ix::secp(one_entry(MSG))], 1);
    assert_eq!(
        InstructionsSysvar::from_bytes(&bytes).unwrap_err(),
        E::MalformedInstructionsSysvar
    );
    // Instruction offset pointing past the end.
    let mut bytes = sysvar_bytes(&[Ix::secp(one_entry(MSG))], 0);
    bytes[2..4].copy_from_slice(&u16::MAX.to_le_bytes());
    let s = InstructionsSysvar::from_bytes(&bytes).unwrap();
    assert_eq!(s.instruction(0).unwrap_err(), E::MalformedInstructionsSysvar);
    // data_len larger than what remains (must not read into the trailer).
    let mut bytes = sysvar_bytes(&[Ix::secp(one_entry(MSG))], 0);
    let data_len_at = 4 + 2 + 32; // header(2+2) + num_accounts(2) + program_id(32)
    let real = u16::from_le_bytes([bytes[data_len_at], bytes[data_len_at + 1]]);
    bytes[data_len_at..data_len_at + 2].copy_from_slice(&(real + 1).to_le_bytes());
    let s = InstructionsSysvar::from_bytes(&bytes).unwrap();
    assert_eq!(s.instruction(0).unwrap_err(), E::MalformedInstructionsSysvar);
    // num_accounts huge.
    let mut bytes = sysvar_bytes(&[Ix::secp(one_entry(MSG))], 0);
    bytes[4..6].copy_from_slice(&u16::MAX.to_le_bytes());
    let s = InstructionsSysvar::from_bytes(&bytes).unwrap();
    assert_eq!(s.instruction(0).unwrap_err(), E::MalformedInstructionsSysvar);
}

// ------------------------------------------------------------- positive ---

#[test]
fn locates_precompile_by_index_and_returns_its_bytes() {
    let ixs = [
        Ix::new(OTHER_PROGRAM, vec![0xAA; 40]),
        Ix::secp(one_entry(MSG)),
        Ix::new(OTHER_PROGRAM, vec![]),
    ];
    let (msg, pk, sig) = load(&ixs, 2, 1).unwrap();
    assert_eq!(msg, MSG);
    assert_eq!(pk, fake_pubkey(7));
    assert_eq!(sig, fake_sig(1));
}

#[test]
fn accepts_explicit_own_index_as_well_as_u16_max() {
    let data = precompile_data(&[(fake_sig(1), fake_pubkey(7), MSG)], 1);
    let ixs = [Ix::new(OTHER_PROGRAM, vec![]), Ix::secp(data)];
    assert_eq!(load(&ixs, 0, 1).unwrap().0, MSG);
}

#[test]
fn multi_signature_instruction_entries_and_find() {
    let m = [b"m0".as_slice(), b"m1".as_slice(), b"m2".as_slice()];
    let data = precompile_data(
        &[
            (fake_sig(1), fake_pubkey(1), m[0]),
            (fake_sig(2), fake_pubkey(2), m[1]),
            (fake_sig(3), fake_pubkey(3), m[2]),
        ],
        CURRENT_INSTRUCTION,
    );
    let ix = Secp256r1Instruction::parse(&data, 0).unwrap();
    assert_eq!(ix.num_signatures(), 3);
    for (i, e) in ix.entries().enumerate() {
        let e = e.unwrap();
        assert_eq!(e.message, m[i]);
        assert_eq!(e.public_key, &fake_pubkey(i as u8 + 1));
    }
    assert_eq!(ix.find(&fake_pubkey(2), b"m1").unwrap(), 1);
    assert_eq!(ix.find(&fake_pubkey(2), b"m0").unwrap_err(), E::MessageMismatch);
    assert_eq!(ix.find(&fake_pubkey(9), b"m0").unwrap_err(), E::PublicKeyMismatch);
    assert!(ix.expect_entry(2, &fake_pubkey(3), b"m2").is_ok());
    assert_eq!(ix.expect_entry(2, &fake_pubkey(1), b"m2").unwrap_err(), E::PublicKeyMismatch);
    assert_eq!(ix.expect_entry(2, &fake_pubkey(3), b"m1").unwrap_err(), E::MessageMismatch);
    assert_eq!(ix.entry(3).unwrap_err(), E::SignatureIndexOutOfBounds);
}

#[test]
fn eight_signatures_is_the_maximum() {
    let entries: Vec<_> = (0..8u8).map(|i| (fake_sig(i), fake_pubkey(i), MSG)).collect();
    let data = precompile_data(&entries, CURRENT_INSTRUCTION);
    assert_eq!(Secp256r1Instruction::parse(&data, 0).unwrap().num_signatures(), 8);

    let entries: Vec<_> = (0..9u8).map(|i| (fake_sig(i), fake_pubkey(i), MSG)).collect();
    let data = precompile_data(&entries, CURRENT_INSTRUCTION);
    assert_eq!(Secp256r1Instruction::parse(&data, 0).unwrap_err(), E::InvalidSignatureCount);
}

#[test]
fn empty_message_is_allowed() {
    let data = one_entry(b"");
    let ix = Secp256r1Instruction::parse(&data, 0).unwrap();
    assert_eq!(ix.entry(0).unwrap().message, b"");
}

// ------------------------------------------------------------- negative ---

#[test]
fn rejects_wrong_program_id_and_missing_instruction() {
    // The instruction at the index is not the precompile.
    let ixs = [Ix::new(OTHER_PROGRAM, one_entry(MSG))];
    assert_eq!(load(&ixs, 0, 0).unwrap_err(), E::NotSecp256r1Instruction);
    // The index does not exist.
    let ixs = [Ix::new(OTHER_PROGRAM, vec![])];
    assert_eq!(load(&ixs, 0, 1).unwrap_err(), E::InstructionIndexOutOfBounds);
    assert_eq!(load(&ixs, 0, u16::MAX).unwrap_err(), E::InstructionIndexOutOfBounds);
}

#[test]
fn rejects_zero_signatures_and_short_data() {
    assert_eq!(Secp256r1Instruction::parse(&[], 0).unwrap_err(), E::TruncatedOffsets);
    assert_eq!(Secp256r1Instruction::parse(&[1], 0).unwrap_err(), E::TruncatedOffsets);
    // SIMD-0075 pseudocode would accept [0]; Agave rejects num_signatures == 0.
    assert_eq!(Secp256r1Instruction::parse(&[0, 0], 0).unwrap_err(), E::InvalidSignatureCount);
    // One signature declared but offsets record cut short.
    let data = one_entry(MSG);
    for cut in 2..16 {
        assert_eq!(
            Secp256r1Instruction::parse(&data[..cut], 0).unwrap_err(),
            E::TruncatedOffsets,
            "cut at {cut}"
        );
    }
    // Two declared, only one record.
    let mut data = one_entry(MSG);
    data[0] = 2;
    assert!(Secp256r1Instruction::parse(&data, 0).is_err());
}

#[test]
fn rejects_offsets_pointing_into_another_instruction() {
    // Each of the three index fields, individually, pointing at instruction 0
    // while the precompile sits at index 1.
    for field in 0..3 {
        let mut data = one_entry(MSG);
        patch_offsets(&mut data, 0, |o| match field {
            0 => o.signature_instruction_index = 0,
            1 => o.public_key_instruction_index = 0,
            _ => o.message_instruction_index = 0,
        });
        let ixs = [Ix::new(OTHER_PROGRAM, vec![0u8; 200]), Ix::secp(data)];
        assert_eq!(load(&ixs, 0, 1).unwrap_err(), E::ForeignInstructionIndex, "field {field}");
    }
    // An index that is merely out of range is also foreign.
    let mut data = one_entry(MSG);
    patch_offsets(&mut data, 0, |o| o.message_instruction_index = 5);
    assert_eq!(Secp256r1Instruction::parse(&data, 1).unwrap_err(), E::ForeignInstructionIndex);
}

#[test]
fn a_foreign_record_anywhere_poisons_the_whole_instruction() {
    // Entry 0 is fine, entry 1 points elsewhere: parse must fail even if the
    // caller only ever asks for entry 0.
    let mut data = precompile_data(
        &[(fake_sig(1), fake_pubkey(1), MSG), (fake_sig(2), fake_pubkey(2), MSG)],
        CURRENT_INSTRUCTION,
    );
    patch_offsets(&mut data, 1, |o| o.public_key_instruction_index = 0);
    assert_eq!(Secp256r1Instruction::parse(&data, 1).unwrap_err(), E::ForeignInstructionIndex);
}

#[test]
fn rejects_out_of_bounds_ranges_without_overflow() {
    let len = one_entry(MSG).len() as u16;
    let cases: Vec<Patch> = vec![
        Box::new(|o| o.signature_offset = u16::MAX),
        Box::new(move |o| o.signature_offset = len - 63),
        Box::new(|o| o.public_key_offset = u16::MAX),
        Box::new(move |o| o.public_key_offset = len - 32),
        Box::new(|o| o.message_data_offset = u16::MAX),
        Box::new(|o| o.message_data_size = u16::MAX),
        Box::new(|o| o.message_data_size += 1),
    ];
    for (i, patch) in cases.iter().enumerate() {
        let mut data = one_entry(MSG);
        patch_offsets(&mut data, 0, |o| patch(o));
        assert_eq!(
            Secp256r1Instruction::parse(&data, 0).unwrap_err(),
            E::OffsetOutOfBounds,
            "case {i}"
        );
    }
}

#[test]
fn rejects_high_s_and_out_of_range_scalars() {
    let with = |r: [u8; 32], s: [u8; 32]| {
        let mut sig = [0u8; 64];
        sig[..32].copy_from_slice(&r);
        sig[32..].copy_from_slice(&s);
        precompile_data(&[(sig, fake_pubkey(1), MSG)], CURRENT_INSTRUCTION)
    };
    let r_ok = fake_sig(1)[..32].try_into().unwrap();
    let parse = |d: Vec<u8>| Secp256r1Instruction::parse(&d, 0).map(|_| ());

    // Boundary: s == n/2 is accepted, s == n/2 + 1 is not.
    assert!(parse(with(r_ok, SECP256R1_HALF_ORDER)).is_ok());
    assert_eq!(parse(with(r_ok, smallest_high_s())).unwrap_err(), E::HighS);
    assert_eq!(parse(with(r_ok, SECP256R1_ORDER_MINUS_ONE)).unwrap_err(), E::HighS);
    assert_eq!(parse(with(r_ok, [0xFF; 32])).unwrap_err(), E::HighS);
    // Zero components.
    assert_eq!(parse(with([0; 32], [1; 32])).unwrap_err(), E::ScalarOutOfRange);
    assert_eq!(parse(with(r_ok, [0; 32])).unwrap_err(), E::ScalarOutOfRange);
    // r == n - 1 is the largest valid r; r == n is not.
    assert!(parse(with(SECP256R1_ORDER_MINUS_ONE, [1; 32])).is_ok());
    assert_eq!(parse(with(SECP256R1_ORDER, [1; 32])).unwrap_err(), E::ScalarOutOfRange);
}

#[test]
fn rejects_uncompressed_or_garbage_key_prefix() {
    for prefix in [0x00u8, 0x01, 0x04, 0x05, 0x06, 0x07, 0xFF] {
        let mut pk = fake_pubkey(1);
        pk[0] = prefix;
        let data = precompile_data(&[(fake_sig(1), pk, MSG)], CURRENT_INSTRUCTION);
        assert_eq!(
            Secp256r1Instruction::parse(&data, 0).unwrap_err(),
            E::InvalidPublicKeyEncoding,
            "prefix {prefix:#x}"
        );
    }
    let mut pk = fake_pubkey(1);
    pk[0] = 0x03;
    let data = precompile_data(&[(fake_sig(1), pk, MSG)], CURRENT_INSTRUCTION);
    assert!(Secp256r1Instruction::parse(&data, 0).is_ok());
}

#[test]
fn padding_byte_is_ignored_like_the_precompile() {
    let mut data = one_entry(MSG);
    data[1] = 0xEE;
    assert!(Secp256r1Instruction::parse(&data, 0).is_ok());
}

#[test]
fn error_codes_are_stable_and_decodable() {
    use pinocchio::error::ProgramError;
    for e in E::ALL {
        assert_eq!(E::from_code(e.code()), Some(e));
        assert_eq!(ProgramError::from(e), ProgramError::Custom(0x2560_0000 | e as u32));
    }
    assert_eq!(E::ForeignInstructionIndex.code(), 0x2560_0007);
    assert_eq!(E::from_code(0), None);
}
