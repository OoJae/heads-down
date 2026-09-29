//! Config initialization (upgrade authority only), the 72-hour governance
//! timelock, rig registration with the registrar's Ed25519 attestation,
//! key rotation and caps authorization.

use heads_down_tests::*;
use hd::{error::HdError, instructions::governance::TIMELOCK_SLOTS};
use p256_introspect::IntrospectError;

fn init_ix(env: &Env, signer: &Address) -> Instruction {
    ix_initialize_config(
        signer,
        &env.governance.pubkey(),
        &env.registrar.pubkey(),
        CRANK_FEE,
        EXECUTOR_FEE,
        0,
        hd::ore::layout_hash(),
    )
}

// ---- initialize_config ---------------------------------------------------------

#[test]
fn only_the_upgrade_authority_initializes_the_config() {
    let mut env = Env::build(Build::Mainnet, true, false);
    let mallory = Keypair::new();
    env.svm.airdrop(&mallory.pubkey(), SOL).unwrap();
    let res = env.send_as(&mallory, &[init_ix(&env, &mallory.pubkey())], &[]);
    assert_hd(&res, 0, HdError::Unauthorized);

    // A forged "ProgramData" naming mallory, at another address.
    let fake_pd = Keypair::new().pubkey();
    let mut pd = env.account(&Env::program_data());
    pd.data[13..45].copy_from_slice(mallory.pubkey().as_ref());
    env.svm.set_account(fake_pd, pd).unwrap();
    let mut ix = init_ix(&env, &mallory.pubkey());
    ix.accounts[2] = AccountMeta::new_readonly(fake_pd, false);
    assert_hd(&env.send_as(&mallory, &[ix], &[]), 0, HdError::Unauthorized);

    // An immutable program (no upgrade authority) cannot be initialized.
    env.set_program_upgrade_authority(None);
    let ua = env.upgrade_authority.insecure_clone();
    assert_hd(&env.send_as(&ua, &[init_ix(&env, &ua.pubkey())], &[]), 0, HdError::Unauthorized);
    env.set_program_upgrade_authority(Some(ua.pubkey()));

    // The authority must actually sign.
    let mut ix = init_ix(&env, &ua.pubkey());
    ix.accounts[0].is_signer = false;
    assert_ix_err(&env.send(&[ix], &[]), 0, InstructionError::MissingRequiredSignature);

    // Pre-funding the Config address does not block initialization.
    ok(env.send(&[system_transfer(&env.cranker.pubkey(), &CONFIG, 1_000_000)], &[]));
    ok(env.send_as(&ua, &[init_ix(&env, &ua.pubkey())], &[]));
    let c = env.config();
    assert_eq!(c.header.tag, 1);
    assert_eq!(c.header.bump, hd::CONFIG_BUMP);
    assert_eq!(c.executor_bump, hd::EXECUTOR_BUMP);
    assert_eq!(c.governance, env.governance.pubkey().to_bytes());
    assert_eq!(c.registrar, env.registrar.pubkey().to_bytes());
    assert_eq!(c.crank_fee.get(), CRANK_FEE);
    assert_eq!(c.executor_fee.get(), EXECUTOR_FEE);
    assert_eq!(c.ore_layout_hash, hd::ore::layout_hash());
    assert_eq!(env.account(&CONFIG).owner, HD);

    // No re-initialization.
    let res = env.send_as(&ua, &[init_ix(&env, &ua.pubkey())], &[]);
    assert_ix_err(&res, 0, InstructionError::AccountAlreadyInitialized);
}

#[test]
fn config_arguments_are_validated() {
    let mut env = Env::build(Build::Mainnet, true, false);
    let ua = env.upgrade_authority.insecure_clone();
    let g = env.governance.pubkey();
    let r = env.registrar.pubkey();
    let good = hd::ore::layout_hash();
    let mut bad_hash = good;
    bad_hash[0] ^= 1;
    for (ix, err) in [
        (ix_initialize_config(&ua.pubkey(), &g, &r, CRANK_FEE, EXECUTOR_FEE, 0, bad_hash), HdError::InvalidOreAccount),
        (ix_initialize_config(&ua.pubkey(), &g, &r, EXECUTOR_FEE + 1, EXECUTOR_FEE, 0, good), HdError::InvalidInstruction),
        (ix_initialize_config(&ua.pubkey(), &g, &r, CRANK_FEE, EXECUTOR_FEE, 10_001, good), HdError::InvalidInstruction),
    ] {
        assert_hd(&env.send_as(&ua, &[ix], &[]), 0, err);
    }
    // Wrong Config address.
    let mut ix = ix_initialize_config(&ua.pubkey(), &g, &r, CRANK_FEE, EXECUTOR_FEE, 0, good);
    ix.accounts[1] = AccountMeta::new(Keypair::new().pubkey(), false);
    assert_ix_err(&env.send_as(&ua, &[ix], &[]), 0, InstructionError::InvalidSeeds);
}

// ---- governance ----------------------------------------------------------------

#[test]
fn config_changes_wait_72_hours() {
    let mut env = Env::new();
    let gov = env.governance.insecure_clone();
    let new_registrar = Keypair::new().pubkey();

    let mallory = Keypair::new();
    env.svm.airdrop(&mallory.pubkey(), SOL).unwrap();
    let res = env.send_as(&mallory, &[ix_propose(&mallory.pubkey(), &new_registrar, 1, 5, 0)], &[]);
    assert_hd(&res, 0, HdError::Unauthorized);
    // Nothing pending yet.
    assert_hd(&env.send(&[ix_apply()], &[]), 0, HdError::InvalidInstruction);
    // Reimbursement above what each dig pays in is refused.
    let res = env.send_as(&gov, &[ix_propose(&gov.pubkey(), &new_registrar, EXECUTOR_FEE + 1, 0, 0)], &[]);
    assert_hd(&res, 0, HdError::InvalidInstruction);

    let s0 = env.slot;
    ok(env.send_as(&gov, &[ix_propose(&gov.pubkey(), &new_registrar, 1_000, 250, 0)], &[]));
    let c = env.config();
    assert_eq!(c.pending_exists, 1);
    assert_eq!(c.pending_eta_slot.get(), s0 + TIMELOCK_SLOTS);
    assert_eq!(TIMELOCK_SLOTS, 864_000);
    assert_eq!(c.crank_fee.get(), CRANK_FEE, "not applied yet");

    assert_hd(&env.send(&[ix_apply()], &[]), 0, HdError::TimelockNotElapsed);
    env.set_clock(s0 + TIMELOCK_SLOTS - 1, env.now);
    assert_hd(&env.send(&[ix_apply()], &[]), 0, HdError::TimelockNotElapsed);
    env.set_clock(s0 + TIMELOCK_SLOTS, env.now);
    ok(env.send(&[ix_apply()], &[])); // anyone
    let c = env.config();
    assert_eq!(c.registrar, new_registrar.to_bytes());
    assert_eq!(c.crank_fee.get(), 1_000);
    assert_eq!(c.bury_bps.get(), 250);
    assert_eq!(c.pending_exists, 0);
    assert_eq!(c.executor_fee.get(), EXECUTOR_FEE, "executor_fee is immutable");
}

#[test]
fn pausing_is_immediate_unpausing_waits() {
    let mut env = Env::new();
    let gov = env.governance.insecure_clone();
    let reg = env.registrar.pubkey();
    ok(env.send_as(&gov, &[ix_propose(&gov.pubkey(), &reg, CRANK_FEE, 0, 1)], &[]));
    assert_eq!(env.config().paused, 1);
    // Proposing to unpause does not unpause.
    let s0 = env.slot;
    ok(env.send_as(&gov, &[ix_propose(&gov.pubkey(), &reg, CRANK_FEE, 0, 0)], &[]));
    assert_eq!(env.config().paused, 1);
    // A newer proposal restarts the clock.
    env.set_clock(s0 + 10, env.now);
    ok(env.send_as(&gov, &[ix_propose(&gov.pubkey(), &reg, CRANK_FEE, 0, 0)], &[]));
    env.set_clock(s0 + TIMELOCK_SLOTS, env.now);
    assert_hd(&env.send(&[ix_apply()], &[]), 0, HdError::TimelockNotElapsed);
    env.set_clock(s0 + 10 + TIMELOCK_SLOTS, env.now);
    ok(env.send(&[ix_apply()], &[]));
    assert_eq!(env.config().paused, 0);
}

// ---- register_rig / attestation -------------------------------------------------

fn attestation(env: &Env, signer: &Keypair, authority: &Address, p256: &[u8; 33], level: u8, expiry: u64) -> Instruction {
    let msg = hd::message::registrar_message(authority, p256, level, expiry);
    let sig: [u8; 64] = signer.sign_message(&msg).as_ref().try_into().unwrap();
    let _ = env;
    ed25519_ix(&signer.pubkey().to_bytes(), &sig, &msg, None)
}

#[test]
fn registrar_attestation_sets_the_level() {
    let mut env = Env::new();
    let u = User::new(&mut env, 1);
    let w = u.wallet.insecure_clone();
    let expiry = env.slot + 1_000;
    let registrar = env.registrar.insecure_clone();
    let att = AttestationArg { ix: 0, sig: 0, level: 2, expiry_slot: expiry };
    ok(env.send_as(
        &w,
        &[
            attestation(&env, &registrar, &w.pubkey(), &u.p256(), 2, expiry),
            ix_register_rig(&w.pubkey(), &u.p256(), Some(att)),
        ],
        &[],
    ));
    let rig = env.rig(&u.rig);
    assert_eq!(rig.attestation_level, 2);
    assert_eq!(rig.attestation_expiry_slot.get(), expiry);
    assert_eq!(rig.tier, 0);
}

#[test]
fn bad_attestations_are_rejected() {
    let mut env = Env::new();
    let u = User::new(&mut env, 2);
    let w = u.wallet.insecure_clone();
    let registrar = env.registrar.insecure_clone();
    let impostor = Keypair::new();
    let other = Keypair::new().pubkey();
    let expiry = env.slot + 1_000;
    let att = |level| AttestationArg { ix: 0, sig: 0, level, expiry_slot: expiry };
    let reg = |a| ix_register_rig(&w.pubkey(), &u.p256(), Some(a));

    let cases: Vec<(Vec<Instruction>, &str)> = vec![
        // Signed by someone other than config.registrar.
        (vec![attestation(&env, &impostor, &w.pubkey(), &u.p256(), 2, expiry), reg(att(2))], "wrong key"),
        // For another authority.
        (vec![attestation(&env, &registrar, &other, &u.p256(), 2, expiry), reg(att(2))], "other authority"),
        // Level in the message differs from the claimed one.
        (vec![attestation(&env, &registrar, &w.pubkey(), &u.p256(), 1, expiry), reg(att(2))], "level"),
        // Undefined level.
        (vec![attestation(&env, &registrar, &w.pubkey(), &u.p256(), 3, expiry), reg(att(3))], "level 3"),
        // Expired.
        (
            vec![
                attestation(&env, &registrar, &w.pubkey(), &u.p256(), 2, env.slot),
                reg(AttestationArg { ix: 0, sig: 0, level: 2, expiry_slot: env.slot }),
            ],
            "expired",
        ),
        // Index pointing at a non-Ed25519 instruction (itself).
        (vec![reg(AttestationArg { ix: 0, sig: 0, level: 2, expiry_slot: expiry })], "no precompile"),
    ];
    for (ixs, what) in cases {
        let at = (ixs.len() - 1) as u8;
        let res = env.send_as(&w, &ixs, &[]);
        match &res {
            Err(Failure { err: TransactionError::InstructionError(i, InstructionError::Custom(c)), .. })
                if *i == at && *c == HdError::InvalidAttestation.code() => {}
            other => panic!("{what}: {other:?}"),
        }
    }

    // Offsets smuggled into a foreign instruction: ix 0 verifies the
    // impostor's own message; ix 1 points all its offsets at ix 0 while its
    // own bytes carry the registrar key and the expected message.
    // Same length as the real attestation, so the only rule the smuggled
    // record breaks is "every index must be the precompile itself".
    let own_msg = vec![0x42u8; hd::message::REGISTRAR_LEN];
    let own_sig: [u8; 64] = impostor.sign_message(&own_msg).as_ref().try_into().unwrap();
    let honest = ed25519_ix(&impostor.pubkey().to_bytes(), &own_sig, &own_msg, None);
    let good_msg = hd::message::registrar_message(&w.pubkey(), &u.p256(), 2, expiry);
    let smuggle = ed25519_ix(&registrar.pubkey().to_bytes(), &[7u8; 64], &good_msg, Some(0));
    let res = env.send_as(
        &w,
        &[honest, smuggle, reg(AttestationArg { ix: 1, sig: 0, level: 2, expiry_slot: expiry })],
        &[],
    );
    assert_hd(&res, 2, HdError::InvalidAttestation);

    // Spoofed instructions sysvar.
    let fake = Keypair::new().pubkey();
    env.svm
        .set_account(fake, Account { lamports: SOL, data: vec![0; 64], owner: SYSTEM, executable: false, rent_epoch: 0 })
        .unwrap();
    let mut ix = reg(att(2));
    ix.accounts[4] = AccountMeta::new_readonly(fake, false);
    let res = env.send_as(&w, &[attestation(&env, &registrar, &w.pubkey(), &u.p256(), 2, expiry), ix], &[]);
    assert_hd(&res, 1, HdError::InvalidAttestation);
    // Nothing was created.
    assert!(env.svm.get_account(&u.rig).is_none());
}

#[test]
fn register_rig_checks() {
    let mut env = Env::new();
    let u = User::new(&mut env, 3);
    let w = u.wallet.insecure_clone();

    // The wallet must sign.
    let mut ix = ix_register_rig(&w.pubkey(), &u.p256(), None);
    ix.accounts[0].is_signer = false;
    assert_ix_err(&env.send(&[ix], &[]), 0, InstructionError::MissingRequiredSignature);
    // Rig must be the canonical [rig, authority] PDA.
    let mut ix = ix_register_rig(&w.pubkey(), &u.p256(), None);
    ix.accounts[1] = AccountMeta::new(rig_pda(&Keypair::new().pubkey()), false);
    assert_ix_err(&env.send_as(&w, &[ix], &[]), 0, InstructionError::InvalidSeeds);
    // Not a compressed P-256 key.
    let mut bad = u.p256();
    bad[0] = 0x04;
    assert_custom(
        &env.send_as(&w, &[ix_register_rig(&w.pubkey(), &bad, None)], &[]),
        0,
        IntrospectError::InvalidPublicKeyEncoding.code(),
    );
    // Pre-funded rig address still registers.
    ok(env.send(&[system_transfer(&env.cranker.pubkey(), &u.rig, 5_000_000)], &[]));
    ok(env.send_as(&w, &[ix_register_rig(&w.pubkey(), &u.p256(), None)], &[]));
    let rig = env.rig(&u.rig);
    assert_eq!(rig.authority, w.pubkey().to_bytes());
    assert_eq!(env.account(&u.rig).owner, HD);
    // Registering twice fails.
    assert_ix_err(
        &env.send_as(&w, &[ix_register_rig(&w.pubkey(), &u.p256(), None)], &[]),
        0,
        InstructionError::AccountAlreadyInitialized,
    );
}

#[test]
fn rotate_key_and_set_caps_need_the_wallet() {
    let mut env = Env::new();
    let mut u = User::new(&mut env, 4);
    env.onboard_standard(&u);
    let w = u.wallet.insecure_clone();
    let mallory = Keypair::new();
    env.svm.airdrop(&mallory.pubkey(), SOL).unwrap();

    // set_caps: stranger / no signature.
    let mut ix = ix_set_caps(&w.pubkey(), Caps::standard());
    ix.accounts[0] = AccountMeta::new_readonly(mallory.pubkey(), true);
    assert_hd(&env.send_as(&mallory, &[ix], &[]), 0, HdError::Unauthorized);
    let mut ix = ix_set_caps(&w.pubkey(), Caps::standard());
    ix.accounts[0].is_signer = false;
    assert_ix_err(&env.send(&[ix], &[]), 0, InstructionError::MissingRequiredSignature);

    // rotate_key by a stranger.
    let new_key = User::from_wallet(Keypair::new(), 77);
    let mut ix = ix_rotate_key(&w.pubkey(), &new_key.p256(), None);
    ix.accounts[0] = AccountMeta::new_readonly(mallory.pubkey(), true);
    assert_hd(&env.send_as(&mallory, &[ix], &[]), 0, HdError::Unauthorized);

    // The wallet rotates (with an attestation, then without: level resets).
    let registrar = env.registrar.insecure_clone();
    let expiry = env.slot + 500;
    ok(env.send_as(
        &w,
        &[
            attestation(&env, &registrar, &w.pubkey(), &new_key.p256(), 1, expiry),
            ix_rotate_key(&w.pubkey(), &new_key.p256(), Some(AttestationArg { ix: 0, sig: 0, level: 1, expiry_slot: expiry })),
        ],
        &[],
    ));
    assert_eq!(env.rig(&u.rig).attestation_level, 1);
    ok(env.send_as(&w, &[ix_rotate_key(&w.pubkey(), &new_key.p256(), None)], &[]));
    let rig = env.rig(&u.rig);
    assert_eq!(rig.p256_pubkey, new_key.p256());
    assert_eq!(rig.attestation_level, 0);

    // The old phone key can no longer dig.
    let meta = ok(env.dig_fresh(&mut [&mut u]));
    assert_eq!(
        skipped_code(&events(&meta.logs), &u.rig),
        Some(IntrospectError::PublicKeyMismatch.code())
    );
}
