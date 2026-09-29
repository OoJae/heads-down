//! The core gate: a dig needs a fresh P-256 heartbeat bound to this program,
//! this rig, this shift and the ORE round, verified by the real secp256r1
//! precompile and introspected through the checked instructions sysvar.
//! Every attack here must deploy nothing.

use hd::error::HdError;
use heads_down_tests::*;
use p256_introspect::IntrospectError;

fn setup(seed: u8) -> (Env, User) {
    let mut env = Env::new();
    let user = User::new(&mut env, seed);
    env.onboard_standard(&user);
    (env, user)
}

/// Dig one rig with `hb` in a precompile at index 1 and `entry`; return the
/// rig's RigSkipped code (the transaction itself must succeed).
fn skip_of(
    env: &mut Env,
    user: &User,
    hbs: &[Heartbeat],
    entry: hd::instructions::HeartbeatEntry,
) -> u32 {
    let meta = ok(env.dig_with(hbs, &[DigRig::new(user, entry)]));
    let evs = events(&meta.logs);
    assert!(dug(&evs, &user.rig).is_none(), "must not dig");
    skipped_code(&evs, &user.rig).expect("RigSkipped")
}

fn assert_nothing_spent(env: &Env, user: &User) {
    let rig = env.rig(&user.rig);
    assert_eq!(rig.spent_shift.get(), 0);
    assert_eq!(rig.last_dug_round.get(), 0);
    assert_eq!(env.miner_deployed(&user.miner()).0.iter().sum::<u64>(), 0);
}

#[test]
fn stale_counter_is_rejected() {
    let (mut env, user) = setup(1);
    let r = env.board_round;
    // Accept counter 5 via record_heartbeats (no dig).
    let hb5 = user.heartbeat_with(5, 1, r, 3);
    ok(env.send(
        &[
            secp_ix_for(&[hb5]),
            ix_record(&[(user.rig, entry_for(&hb5, 0, 0))]),
        ],
        &[],
    ));
    assert_eq!(env.rig(&user.rig).hb_counter.get(), 5);
    // A properly signed heartbeat with counter 4 (and 5) is stale.
    for c in [4, 5] {
        let hb = user.heartbeat_with(c, 1, r, 3);
        assert_eq!(
            skip_of(&mut env, &user, &[hb], entry_for(&hb, 1, 0)),
            HdError::StaleHeartbeat.code()
        );
    }
    assert_nothing_spent(&env, &user);
}

#[test]
fn replayed_heartbeat_is_rejected() {
    let (mut env, mut user) = setup(2);
    let r = env.board_round;
    let hb = user.heartbeat(1, r, 3);
    let meta = ok(env.dig_with(&[hb], &[DigRig::new(&user, entry_for(&hb, 1, 0))]));
    assert!(dug(&events(&meta.logs), &user.rig).is_some());
    // The exact same precompile + entry in a new transaction.
    let code = skip_of(&mut env, &user, &[hb], entry_for(&hb, 1, 0));
    assert_eq!(code, HdError::StaleHeartbeat.code());
    // Even a fresh heartbeat cannot dig the same ORE round twice.
    let hb2 = user.heartbeat(1, r, 3);
    let code = skip_of(&mut env, &user, &[hb2], entry_for(&hb2, 1, 0));
    assert_eq!(code, HdError::AlreadyDugRound.code());
    assert_eq!(env.rig(&user.rig).shift_rounds_dug.get(), 1);
}

#[test]
fn wrong_round_is_rejected() {
    let (mut env, mut user) = setup(3);
    let r = env.board_round;

    // Signed for a future round: invalid.
    let hb = user.heartbeat(1, r + 1, 3);
    assert_eq!(
        skip_of(&mut env, &user, &[hb], entry_for(&hb, 1, 0)),
        HdError::InvalidHeartbeat.code()
    );
    // Entry claims round r but the phone signed round r - 1: the rebuilt
    // digest differs from what the precompile verified.
    let hb = user.heartbeat(1, r - 1, 3);
    let mut e = entry_for(&hb, 1, 0);
    e.round_id = r;
    assert_eq!(
        skip_of(&mut env, &user, &[hb], e),
        IntrospectError::MessageMismatch.code()
    );
    // An old round whose lease ended before the current round: the counter
    // is consumed but no lease covers round r.
    let hb = user.heartbeat(1, r - 5, 3);
    assert_eq!(
        skip_of(&mut env, &user, &[hb], entry_for(&hb, 1, 0)),
        HdError::LeaseExpired.code()
    );
    assert_nothing_spent(&env, &user);
}

#[test]
fn wrong_shift_id_is_rejected() {
    let (mut env, mut user) = setup(4);
    let r = env.board_round;
    // The rig is in shift 1; a heartbeat signed for shift 2 (or 0) does not
    // match the preimage the program rebuilds from rig.shift_id.
    for shift in [0, 2] {
        let hb = user.heartbeat(shift, r, 3);
        assert_eq!(
            skip_of(&mut env, &user, &[hb], entry_for(&hb, 1, 0)),
            IntrospectError::MessageMismatch.code()
        );
    }
    assert_eq!(env.rig(&user.rig).hb_counter.get(), 0);
    assert_nothing_spent(&env, &user);
}

#[test]
fn heartbeat_signed_by_another_rigs_key_is_rejected() {
    let (mut env, victim) = setup(5);
    let attacker = User::new(&mut env, 99);
    let r = env.board_round;
    // The attacker's key signs a well-formed heartbeat for the victim's rig.
    let pre = hd::message::heartbeat_preimage(&victim.rig, 1, 1, r, 3);
    let digest = hd::message::digest(&pre);
    let hb = Heartbeat {
        counter: 1,
        round: r,
        lease: 3,
        pubkey: attacker.p256(),
        digest,
        sig: attacker.sign(&digest),
    };
    // The precompile accepts it (valid signature by the attacker's key)...
    assert_eq!(
        skip_of(&mut env, &victim, &[hb], entry_for(&hb, 1, 0)),
        IntrospectError::PublicKeyMismatch.code()
    );
    // ...and claiming the victim's key in the precompile fails the precompile.
    let forged = Heartbeat {
        pubkey: victim.p256(),
        ..hb
    };
    let res = env.dig_with(&[forged], &[DigRig::new(&victim, entry_for(&forged, 1, 0))]);
    assert!(matches!(
        res,
        Err(Failure {
            err: TransactionError::InstructionError(1, _),
            ..
        })
    ));
    assert_nothing_spent(&env, &victim);
}

#[test]
fn offsets_pointing_into_a_foreign_instruction_are_rejected() {
    let (mut env, victim) = setup(6);
    let mut attacker = User::new(&mut env, 98);
    let r = env.board_round;
    let victim_hb_digest =
        hd::message::digest(&hd::message::heartbeat_preimage(&victim.rig, 1, 1, r, 3));

    // ix 1: a valid precompile for the attacker's own (key, message).
    let own = attacker.heartbeat(1, r, 3);
    let honest = secp_ix_for(&[own]);
    // ix 2: a precompile whose offsets all point into ix 1 (so the precompile
    // re-verifies ix 1's bytes and passes), while its own bytes at the same
    // offsets carry the victim's key and heartbeat digest. A reader that
    // ignores the instruction indices would accept the victim's heartbeat.
    let mut data = honest.data.clone();
    for field in [2usize, 6, 12] {
        // signature / public key / message instruction index -> 1
        data[2 + field..4 + field].copy_from_slice(&1u16.to_le_bytes());
    }
    let pk_off = u16::from_le_bytes(data[6..8].try_into().unwrap()) as usize;
    let msg_off = u16::from_le_bytes(data[10..12].try_into().unwrap()) as usize;
    data[pk_off..pk_off + 33].copy_from_slice(&victim.p256());
    data[msg_off..msg_off + 32].copy_from_slice(&victim_hb_digest);
    let smuggler = Instruction {
        program_id: secp256r1_id(),
        accounts: vec![],
        data,
    };

    let entry = hd::instructions::HeartbeatEntry {
        hb_ix: 2,
        hb_sig_index: 0,
        counter: 1,
        round_id: r,
        lease_rounds: 3,
    };
    let dig = ix_dig(
        &env.cranker.pubkey(),
        &env.round,
        &[DigRig::new(&victim, entry)],
    );
    let meta = ok(env.send(&[compute_limit(1_400_000), honest, smuggler, dig], &[]));
    assert_eq!(
        skipped_code(&events(&meta.logs), &victim.rig),
        Some(IntrospectError::ForeignInstructionIndex.code())
    );
    assert_nothing_spent(&env, &victim);
}

#[test]
fn spoofed_instructions_sysvar_fails_the_transaction() {
    let (mut env, mut user) = setup(7);
    let r = env.board_round;
    let hb = user.heartbeat(1, r, 3);
    // A byte-perfect forged sysvar image, at an attacker-chosen address.
    let fake = Keypair::new().pubkey();
    let real = env.svm.get_account(&ix_sysvar_id());
    let data = real.map(|a| a.data).unwrap_or_else(|| vec![0u8; 64]);
    env.svm
        .set_account(
            fake,
            Account {
                lamports: SOL,
                data,
                owner: SYSTEM,
                executable: false,
                rent_epoch: 0,
            },
        )
        .unwrap();
    let mut dig = ix_dig(
        &env.cranker.pubkey(),
        &env.round,
        &[DigRig::new(&user, entry_for(&hb, 1, 0))],
    );
    dig.accounts[11] = AccountMeta::new_readonly(fake, false);
    let res = env.send(&[compute_limit(1_400_000), secp_ix_for(&[hb]), dig], &[]);
    assert_custom(&res, 2, IntrospectError::InvalidInstructionsSysvar.code());
}

#[test]
fn high_s_and_missing_precompiles_are_rejected() {
    let (mut env, mut user) = setup(8);
    let r = env.board_round;
    // High-S twin of a valid signature: the precompile itself rejects it.
    let mut hb = user.heartbeat(1, r, 3);
    let n = hex_to_32("FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551");
    let s: [u8; 32] = hb.sig[32..].try_into().unwrap();
    hb.sig[32..].copy_from_slice(&sub_be(&n, &s));
    let res = env.dig_with(&[hb], &[DigRig::new(&user, entry_for(&hb, 1, 0))]);
    assert_ix_err(&res, 1, InstructionError::Custom(2)); // PrecompileError::InvalidSignature

    // hb_ix pointing at a non-precompile instruction, or past the end.
    let hb = user.heartbeat(1, r, 3);
    let mut e = entry_for(&hb, 0, 0); // ix 0 = ComputeBudget
    assert_eq!(
        skip_of(&mut env, &user, &[hb], e),
        IntrospectError::NotSecp256r1Instruction.code()
    );
    e.hb_ix = 9;
    assert_eq!(
        skip_of(&mut env, &user, &[hb], e),
        IntrospectError::InstructionIndexOutOfBounds.code()
    );
    // Entry index past the precompile's signatures.
    let e = entry_for(&hb, 1, 3);
    assert_eq!(
        skip_of(&mut env, &user, &[hb], e),
        IntrospectError::SignatureIndexOutOfBounds.code()
    );
    assert_nothing_spent(&env, &user);
}

#[test]
fn a_fresh_heartbeat_later_in_the_batch_cannot_be_borrowed_by_another_rig() {
    // Two rigs, one precompile with two entries; each entry is bound to its
    // own rig, so swapping the entry indices fails both.
    let mut env = Env::new();
    let mut a = User::new(&mut env, 10);
    let mut b = User::new(&mut env, 11);
    env.onboard_standard(&a);
    env.onboard_standard(&b);
    let r = env.board_round;
    let ha = a.heartbeat(1, r, 3);
    let hb = b.heartbeat(1, r, 3);
    let rigs = vec![
        DigRig::new(&a, entry_for(&hb, 1, 1)),
        DigRig::new(&b, entry_for(&ha, 1, 0)),
    ];
    let meta = ok(env.dig_with(&[ha, hb], &rigs));
    let evs = events(&meta.logs);
    assert_eq!(
        skipped_code(&evs, &a.rig),
        Some(IntrospectError::PublicKeyMismatch.code())
    );
    assert_eq!(
        skipped_code(&evs, &b.rig),
        Some(IntrospectError::PublicKeyMismatch.code())
    );
}

fn hex_to_32(s: &str) -> [u8; 32] {
    std::array::from_fn(|i| u8::from_str_radix(&s[2 * i..2 * i + 2], 16).unwrap())
}

fn sub_be(a: &[u8; 32], b: &[u8; 32]) -> [u8; 32] {
    let mut out = [0u8; 32];
    let mut borrow = 0i16;
    for i in (0..32).rev() {
        let mut v = a[i] as i16 - b[i] as i16 - borrow;
        borrow = if v < 0 {
            v += 256;
            1
        } else {
            0
        };
        out[i] = v as u8;
    }
    out
}
