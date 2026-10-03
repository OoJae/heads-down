//! The program accepts a registrar voucher built exactly like
//! `registrar/src/voucher.rs`: Ed25519 over the raw 111-byte `HDreg`
//! preimage, carried by the 223-byte Ed25519SigVerify instruction whose
//! first 16 bytes are the registrar's `IX_HEADER` (registrar
//! INTERFACE-NOTES N2 / N3). Also: what it refuses.

use hd::error::HdError;
use heads_down_tests::{
    vectors::{registrar_ix_data, registrar_voucher, REGISTRAR_IX_HEADER},
    *,
};

const EXPIRY: u64 = golden::START_SLOT + 6_480_000;

fn att(level: u8, expiry_slot: u64) -> Option<AttestationArg> {
    Some(AttestationArg {
        ix: 0,
        sig: 0,
        level,
        expiry_slot,
    })
}

#[test]
fn registrar_format_matches_voucher_rs_byte_for_byte() {
    let mut env = Env::golden(Build::Mainnet);
    let u = User::with_keys(&mut env, [0xC3; 32], [0x33; 32]);
    let registrar = env.registrar.insecure_clone();
    let (msg, sig, ix) = registrar_voucher(&registrar, &u.pubkey(), &u.p256(), 2, EXPIRY);

    // HDreg preimage (N2): "HDreg" | program | authority | p256 | level | expiry.
    assert_eq!(msg.len(), 111);
    assert_eq!(&msg[0..5], b"HDreg");
    assert_eq!(&msg[5..37], HD.as_ref());
    assert_eq!(&msg[37..69], u.pubkey().as_ref());
    assert_eq!(&msg[69..102], &u.p256());
    assert_eq!(msg[102], 2);
    assert_eq!(&msg[103..111], &EXPIRY.to_le_bytes());

    // Instruction (N3): 223 bytes, constant header, pubkey @16, sig @48, msg @112.
    assert_eq!(ix.program_id, ED25519);
    assert!(ix.accounts.is_empty());
    assert_eq!(ix.data.len(), 223);
    assert_eq!(ix.data[..16], REGISTRAR_IX_HEADER);
    assert_eq!(&ix.data[16..48], registrar.pubkey().as_ref());
    assert_eq!(&ix.data[48..112], &sig);
    assert_eq!(&ix.data[112..223], &msg);
    // The harness's generic Ed25519 builder produces the same bytes.
    assert_eq!(
        ed25519_ix(&registrar.pubkey().to_bytes(), &sig, &msg, None).data,
        ix.data
    );
    assert_eq!(
        registrar_ix_data(&registrar.pubkey().to_bytes(), &sig, &msg),
        ix.data
    );
}

#[test]
fn register_rig_and_rotate_key_accept_registrar_vouchers() {
    let mut env = Env::golden(Build::Mainnet);
    let mut u = User::with_keys(&mut env, [0xC3; 32], [0x33; 32]);
    let registrar = env.registrar.insecure_clone();
    let w = u.wallet.insecure_clone();

    let (_, _, ed) = registrar_voucher(&registrar, &u.pubkey(), &u.p256(), 2, EXPIRY);
    let meta = ok(env.send_as(
        &w,
        &[ed, ix_register_rig(&u.pubkey(), &u.p256(), att(2, EXPIRY))],
        &[],
    ));
    let rig = env.rig(&u.rig);
    assert_eq!(rig.attestation_level, 2);
    assert_eq!(rig.attestation_expiry_slot.get(), EXPIRY);
    assert_eq!(
        events(&meta.logs),
        vec![Event::RigRegistered {
            rig: u.rig,
            authority: u.pubkey(),
            tier: 0,
            attestation_level: 2
        }]
    );

    // New phone: rotate with a fresh TEE (level 1) voucher at a later index.
    u.key = p256::ecdsa::SigningKey::from_slice(&[0x35; 32]).unwrap();
    let (_, _, ed) = registrar_voucher(&registrar, &u.pubkey(), &u.p256(), 1, EXPIRY);
    let mut rotate = ix_rotate_key(&u.pubkey(), &u.p256(), att(1, EXPIRY));
    rotate.data[35] = 1; // ed25519_ix: the voucher is instruction 1
    ok(env.send_as(&w, &[compute_limit(200_000), ed, rotate], &[]));
    let rig = env.rig(&u.rig);
    assert_eq!(rig.p256_pubkey, u.p256());
    assert_eq!(rig.attestation_level, 1);
}

/// A voucher may run at most `MAX_ATTESTATION_TTL_SLOTS` past the current
/// slot, so a registrar key that leaks cannot mint attestations that outlive
/// its rotation.
#[test]
fn a_voucher_lives_at_most_the_ttl_cap() {
    let mut env = Env::golden(Build::Mainnet);
    let u = User::with_keys(&mut env, [0xC6; 32], [0x38; 32]);
    let registrar = env.registrar.insecure_clone();
    let w = u.wallet.insecure_clone();
    let cap = env.slot + hd::logic::MAX_ATTESTATION_TTL_SLOTS;
    let (_, _, ed) = registrar_voucher(&registrar, &u.pubkey(), &u.p256(), 2, cap + 1);
    assert_hd(
        &env.send_as(
            &w,
            &[ed, ix_register_rig(&u.pubkey(), &u.p256(), att(2, cap + 1))],
            &[],
        ),
        1,
        HdError::InvalidAttestation,
    );
    let (_, _, ed) = registrar_voucher(&registrar, &u.pubkey(), &u.p256(), 2, cap);
    ok(env.send_as(
        &w,
        &[ed, ix_register_rig(&u.pubkey(), &u.p256(), att(2, cap))],
        &[],
    ));
    assert_eq!(env.rig(&u.rig).attestation_expiry_slot.get(), cap);
}

#[test]
fn vouchers_the_program_refuses() {
    let mut env = Env::golden(Build::Mainnet);
    let u = User::with_keys(&mut env, [0xC4; 32], [0x36; 32]);
    let other = User::with_keys(&mut env, [0xC5; 32], [0x37; 32]);
    let registrar = env.registrar.insecure_clone();
    let w = u.wallet.insecure_clone();
    let send = |env: &mut Env, ed: Instruction, a: Option<AttestationArg>| {
        env.send_as(&w, &[ed, ix_register_rig(&u.pubkey(), &u.p256(), a)], &[])
    };

    // Level 0 ("unattested but same app", registrar HD_*_POLICY=level0):
    // refused; such a rig registers without an attestation instead.
    let (_, _, ed) = registrar_voucher(&registrar, &u.pubkey(), &u.p256(), 0, EXPIRY);
    assert_hd(
        &send(&mut env, ed, att(0, EXPIRY)),
        1,
        HdError::InvalidAttestation,
    );
    // Level 3 does not exist.
    let (_, _, ed) = registrar_voucher(&registrar, &u.pubkey(), &u.p256(), 3, EXPIRY);
    assert_hd(
        &send(&mut env, ed, att(3, EXPIRY)),
        1,
        HdError::InvalidAttestation,
    );
    // Expired: expiry_slot <= Clock.slot.
    let now = golden::START_SLOT + 10;
    let (_, _, ed) = registrar_voucher(&registrar, &u.pubkey(), &u.p256(), 2, now);
    assert_hd(
        &send(&mut env, ed, att(2, now)),
        1,
        HdError::InvalidAttestation,
    );
    // Issued for another wallet.
    let (_, _, ed) = registrar_voucher(&registrar, &other.pubkey(), &u.p256(), 2, EXPIRY);
    assert_hd(
        &send(&mut env, ed, att(2, EXPIRY)),
        1,
        HdError::InvalidAttestation,
    );
    // Signed by a key that is not config.registrar.
    let impostor = Keypair::new_from_array([0x99; 32]);
    let (_, _, ed) = registrar_voucher(&impostor, &u.pubkey(), &u.p256(), 2, EXPIRY);
    assert_hd(
        &send(&mut env, ed, att(2, EXPIRY)),
        1,
        HdError::InvalidAttestation,
    );
    // Level in the instruction data differs from the signed one.
    let (_, _, ed) = registrar_voucher(&registrar, &u.pubkey(), &u.p256(), 1, EXPIRY);
    assert_hd(
        &send(&mut env, ed, att(2, EXPIRY)),
        1,
        HdError::InvalidAttestation,
    );
    // A tampered message fails in the precompile itself (instruction 0).
    let (_, _, mut ed) = registrar_voucher(&registrar, &u.pubkey(), &u.p256(), 2, EXPIRY);
    ed.data[222] ^= 1;
    assert!(matches!(
        send(&mut env, ed, att(2, EXPIRY)),
        Err(Failure {
            err: TransactionError::InstructionError(0, _),
            ..
        })
    ));
    assert!(env.svm.get_account(&u.rig).is_none());
}
