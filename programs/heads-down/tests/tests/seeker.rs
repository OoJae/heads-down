//! verify_seeker: in-program SGT verification, one seat per SGT mint,
//! re-pointing after a move and downgrading the previous rig.
//!
//! * `--features devnet` build: test SGTs issued and moved through the real
//!   Token-2022 program by `sgt_verify::testkit` (public test anchors).
//! * `--features mainnet` build: the real mainnet SGT fixtures from
//!   `crates/sgt-verify/fixtures` (real holders cannot sign here, so that SVM
//!   runs with sigverify off; the program still sees `is_signer`).

use heads_down_tests::*;
use hd::error::HdError;
use sgt_verify::{
    anchors::{PUBLIC_TEST_AUTHORITY, PUBLIC_TEST_GROUP},
    layout::SGT_MINT_LEN,
    testkit::{
        self, associated_token_address, create_test_group, issue_test_sgt, move_test_sgt,
        sgt_mint_bytes, sgt_token_account_bytes, TEST_GROUP_MINT_LEN,
    },
    SgtError,
};
use solana_message::Message;
use std::str::FromStr;

fn sgt_err(e: SgtError) -> u32 {
    e.program_error_code()
}

fn register(env: &mut Env, user: &User) {
    let w = user.wallet.insecure_clone();
    ok(env.send_as(&w, &[ix_register_rig(&w.pubkey(), &user.p256(), None)], &[]));
}

// ---- devnet build: test SGTs through real Token-2022 ------------------------------

struct Issuer {
    authority: Keypair,
}

impl Issuer {
    fn new(env: &mut Env) -> Self {
        let authority = Keypair::new_from_array(testkit::PUBLIC_TEST_AUTHORITY_SEED);
        let group = Keypair::new_from_array(testkit::PUBLIC_TEST_GROUP_SEED);
        assert_eq!(authority.pubkey(), PUBLIC_TEST_AUTHORITY);
        assert_eq!(group.pubkey(), PUBLIC_TEST_GROUP);
        env.svm.airdrop(&authority.pubkey(), SOL).unwrap();
        let rent = env.svm.minimum_balance_for_rent_exemption(TEST_GROUP_MINT_LEN);
        let payer = env.cranker.pubkey();
        let ixs = create_test_group(&payer, &group.pubkey(), &authority.pubkey(), 1_000_000, rent);
        ok(env.send(&ixs, &[&group, &authority]));
        Self { authority }
    }

    fn issue(&self, env: &mut Env, holder: &Address) -> Address {
        let member = Keypair::new();
        let rent = env.svm.minimum_balance_for_rent_exemption(SGT_MINT_LEN);
        let payer = env.cranker.pubkey();
        let ixs = issue_test_sgt(&payer, &PUBLIC_TEST_GROUP, &self.authority.pubkey(), &member.pubkey(), holder, rent);
        let a = self.authority.insecure_clone();
        ok(env.send(&ixs, &[&member, &a]));
        member.pubkey()
    }

    fn move_sgt(&self, env: &mut Env, mint: &Address, from: &Address, to: &Address) {
        let payer = env.cranker.pubkey();
        let ixs = move_test_sgt(&payer, &self.authority.pubkey(), mint, from, to);
        let a = self.authority.insecure_clone();
        ok(env.send(&ixs, &[&a]));
    }
}

#[test]
fn devnet_build_verifies_a_test_sgt_and_repoints_the_seat_after_a_move() {
    let mut env = Env::build(Build::Devnet, true, true);
    let issuer = Issuer::new(&mut env);
    let a = User::new(&mut env, 1);
    let b = User::new(&mut env, 2);
    register(&mut env, &a);
    let mint = issuer.issue(&mut env, &a.pubkey());
    let ata_a = associated_token_address(&a.pubkey(), &mint);

    // A verifies: seat created, rig upgraded to tier 1.
    let wa = a.wallet.insecure_clone();
    let meta = ok(env.send_as(&wa, &[ix_verify_seeker(&wa.pubkey(), &ata_a, &mint, None)], &[]));
    println!("verify_seeker (create seat): {} CU", meta.compute_units_consumed);
    assert_eq!(
        events(&meta.logs),
        vec![Event::SeekerVerified { rig: a.rig, sgt_mint: mint, member_number: 1 }]
    );
    let seat = env.seat(&seat_pda(&mint));
    assert_eq!(seat.header.tag, 3);
    assert_eq!(seat.rig, a.rig.to_bytes());
    assert_eq!(seat.authority, a.pubkey().to_bytes());
    assert_eq!(seat.member_number.get(), 1);
    let rig_a = env.rig(&a.rig);
    assert_eq!((rig_a.tier, rig_a.sgt_mint), (1, mint.to_bytes()));

    // Re-verifying is idempotent.
    ok(env.send_as(&wa, &[ix_verify_seeker(&wa.pubkey(), &ata_a, &mint, None)], &[]));

    // Someone who does not hold it cannot claim it (owner field mismatch).
    register(&mut env, &b);
    let wb = b.wallet.insecure_clone();
    let res = env.send_as(&wb, &[ix_verify_seeker(&wb.pubkey(), &ata_a, &mint, Some(a.rig))], &[]);
    assert_custom(&res, 0, sgt_err(SgtError::TokenAccountOwnerMismatch));

    // Solana Mobile moves the SGT to B's wallet (thaw, delegate transfer, freeze).
    issuer.move_sgt(&mut env, &mint, &a.pubkey(), &b.pubkey());
    let ata_b = associated_token_address(&b.pubkey(), &mint);
    // A's old holding no longer verifies.
    let res = env.send_as(&wa, &[ix_verify_seeker(&wa.pubkey(), &ata_a, &mint, None)], &[]);
    assert_custom(&res, 0, sgt_err(SgtError::AmountNotOne));
    // B must name the rig the seat points at.
    let res = env.send_as(&wb, &[ix_verify_seeker(&wb.pubkey(), &ata_b, &mint, None)], &[]);
    assert_hd(&res, 0, HdError::SeatTaken);
    let res = env.send_as(&wb, &[ix_verify_seeker(&wb.pubkey(), &ata_b, &mint, Some(Keypair::new().pubkey()))], &[]);
    assert_hd(&res, 0, HdError::SeatTaken);
    // With A's rig: the seat moves, A is downgraded, B is Seeker tier.
    ok(env.send_as(&wb, &[ix_verify_seeker(&wb.pubkey(), &ata_b, &mint, Some(a.rig))], &[]));
    let seat = env.seat(&seat_pda(&mint));
    assert_eq!(seat.rig, b.rig.to_bytes());
    assert_eq!(seat.authority, b.pubkey().to_bytes());
    let rig_a = env.rig(&a.rig);
    assert_eq!((rig_a.tier, rig_a.sgt_mint), (0, [0; 32]));
    let rig_b = env.rig(&b.rig);
    assert_eq!((rig_b.tier, rig_b.sgt_mint), (1, mint.to_bytes()));

    // Closing B's rig closes the seat with it; A's (guest again) closes alone.
    let res = env.send_as(&wb, &[ix_close_rig(&wb.pubkey(), None)], &[]);
    #[allow(deprecated)] // the runtime still reports ProgramError::NotEnoughAccountKeys this way
    let missing = InstructionError::NotEnoughAccountKeys;
    assert_ix_err(&res, 0, missing);
    let seat_rent = env.lamports(&seat_pda(&mint));
    let rig_rent = env.lamports(&b.rig);
    let before = env.lamports(&wb.pubkey());
    let meta = ok(env.send_as(&wb, &[ix_close_rig(&wb.pubkey(), Some(seat_pda(&mint)))], &[]));
    assert_eq!(env.lamports(&wb.pubkey()), before + seat_rent + rig_rent - meta.fee);
    assert!(env.svm.get_account(&seat_pda(&mint)).is_none());
    ok(env.send_as(&wa, &[ix_close_rig(&wa.pubkey(), None)], &[]));
}

#[test]
fn devnet_build_rejects_real_and_forged_sgts() {
    let mut env = Env::build(Build::Devnet, true, true);
    let u = User::new(&mut env, 3);
    register(&mut env, &u);
    let w = u.wallet.insecure_clone();

    // A real mainnet SGT, re-owned to this wallet: wrong anchors for this build.
    let real_mint = load_fixture_account(&sgt_fixtures().join("member-20/mint.json"));
    let mint = Address::from_str("5mXbkqKz883aufhAsx3p5Z1NcvD2ppZbdTTznM6oUKLj").unwrap();
    env.svm.set_account(mint, real_mint).unwrap();
    let ta = Keypair::new().pubkey();
    let mut ta_acc = load_fixture_account(&sgt_fixtures().join("member-20/token_account.json"));
    ta_acc.data[32..64].copy_from_slice(w.pubkey().as_ref());
    env.svm.set_account(ta, ta_acc).unwrap();
    let res = env.send_as(&w, &[ix_verify_seeker(&w.pubkey(), &ta, &mint, None)], &[]);
    assert_custom(&res, 0, sgt_err(SgtError::MintAuthorityMismatch));

    // A mint with every SGT field right except group membership in a group
    // the forger made (they can set authorities but not join PUBLIC_TEST_GROUP).
    let fake_group = Keypair::new().pubkey();
    let forged = Keypair::new().pubkey();
    let data = sgt_mint_bytes(&forged, &fake_group, &PUBLIC_TEST_AUTHORITY, 7);
    env.svm
        .set_account(forged, Account { lamports: SOL, data, owner: token_2022_id(), executable: false, rent_epoch: 0 })
        .unwrap();
    let fta = Keypair::new().pubkey();
    env.svm
        .set_account(
            fta,
            Account {
                lamports: SOL,
                data: sgt_token_account_bytes(&forged, &w.pubkey(), true),
                owner: token_2022_id(),
                executable: false,
                rent_epoch: 0,
            },
        )
        .unwrap();
    let res = env.send_as(&w, &[ix_verify_seeker(&w.pubkey(), &fta, &forged, None)], &[]);
    assert_custom(&res, 0, sgt_err(SgtError::GroupMismatch));
    // The same bytes owned by the legacy token program.
    let mut acc = env.account(&forged);
    acc.owner = Address::from_str("TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA").unwrap();
    env.svm.set_account(forged, acc).unwrap();
    let res = env.send_as(&w, &[ix_verify_seeker(&w.pubkey(), &fta, &forged, None)], &[]);
    assert_custom(&res, 0, sgt_err(SgtError::MintNotToken2022));
    assert_eq!(env.rig(&u.rig).tier, 0);
}

// ---- mainnet build: real SGT fixtures ------------------------------------------------

struct RealSgt {
    mint: Address,
    token_account: Address,
    holder: Address,
    member: u64,
}

fn real_sgts(env: &mut Env) -> Vec<RealSgt> {
    let manifest: serde_json::Value =
        serde_json::from_str(&std::fs::read_to_string(sgt_fixtures().join("manifest.json")).unwrap()).unwrap();
    manifest["sgts"]
        .as_array()
        .unwrap()
        .iter()
        .map(|s| {
            let label = s["label"].as_str().unwrap();
            let sgt = RealSgt {
                mint: Address::from_str(s["mint"].as_str().unwrap()).unwrap(),
                token_account: Address::from_str(s["token_account"].as_str().unwrap()).unwrap(),
                holder: Address::from_str(s["holder"].as_str().unwrap()).unwrap(),
                member: s["member_number"].as_u64().unwrap(),
            };
            let mut mint = load_fixture_account(&sgt_fixtures().join(format!("{label}/mint.json")));
            mint.rent_epoch = 0;
            env.svm.set_account(sgt.mint, mint).unwrap();
            env.svm
                .set_account(sgt.token_account, load_fixture_account(&sgt_fixtures().join(format!("{label}/token_account.json"))))
                .unwrap();
            env.svm.airdrop(&sgt.holder, SOL).unwrap();
            sgt
        })
        .collect()
}

/// Send with `holder` marked as signer but not signing (sigverify is off).
fn send_as_holder(env: &mut Env, holder: &Address, ixs: &[Instruction]) -> TxResult {
    let payer = env.cranker.insecure_clone();
    let _ = holder;
    let message = Message::new(ixs, Some(&payer.pubkey()));
    let mut tx = solana_transaction::Transaction::new_unsigned(message);
    tx.partial_sign(&[&payer], env.svm.latest_blockhash());
    let res = env
        .svm
        .send_transaction(tx)
        .map_err(|f| Failure { err: f.err, logs: f.meta.logs });
    env.svm.expire_blockhash();
    res
}

#[test]
fn mainnet_build_verifies_real_sgts_and_repoints_after_a_move() {
    let mut env = Env::build(Build::Mainnet, false, true);
    let sgts = real_sgts(&mut env);
    for sgt in &sgts {
        let p256 = User::from_wallet(Keypair::new(), 5).p256();
        ok(send_as_holder(&mut env, &sgt.holder, &[ix_register_rig(&sgt.holder, &p256, None)]));
        let meta = ok(send_as_holder(
            &mut env,
            &sgt.holder,
            &[ix_verify_seeker(&sgt.holder, &sgt.token_account, &sgt.mint, None)],
        ));
        let rig = rig_pda(&sgt.holder);
        assert_eq!(
            events(&meta.logs),
            vec![Event::SeekerVerified { rig, sgt_mint: sgt.mint, member_number: sgt.member }]
        );
        assert_eq!(env.rig(&rig).tier, 1);
        assert_eq!(env.seat(&seat_pda(&sgt.mint)).member_number.get(), sgt.member);
    }

    // Move member #20 to a new wallet (bytes as Token-2022 leaves them after
    // Solana Mobile's thaw / delegate-transfer / freeze).
    let sgt = &sgts[0];
    let new_holder = User::new(&mut env, 6);
    let new_ata = associated_token_address(&new_holder.pubkey(), &sgt.mint);
    env.svm
        .set_account(
            new_ata,
            Account {
                lamports: SOL / 100,
                data: sgt_token_account_bytes(&sgt.mint, &new_holder.pubkey(), true),
                owner: token_2022_id(),
                executable: false,
                rent_epoch: 0,
            },
        )
        .unwrap();
    let mut old = env.account(&sgt.token_account);
    old.data[64..72].copy_from_slice(&0u64.to_le_bytes()); // amount 0
    env.svm.set_account(sgt.token_account, old).unwrap();

    register(&mut env, &new_holder);
    let w = new_holder.wallet.insecure_clone();
    let old_rig = rig_pda(&sgt.holder);
    ok(env.send_as(&w, &[ix_verify_seeker(&w.pubkey(), &new_ata, &sgt.mint, Some(old_rig))], &[]));
    assert_eq!(env.seat(&seat_pda(&sgt.mint)).rig, new_holder.rig.to_bytes());
    assert_eq!(env.rig(&old_rig).tier, 0);
    assert_eq!(env.rig(&new_holder.rig).tier, 1);
    // The previous holder can no longer verify.
    let res = send_as_holder(&mut env, &sgt.holder, &[ix_verify_seeker(&sgt.holder, &sgt.token_account, &sgt.mint, None)]);
    assert_custom(&res, 0, sgt_err(SgtError::AmountNotOne));
}

#[test]
fn mainnet_build_rejects_test_group_sgts() {
    let mut env = Env::new();
    let u = User::new(&mut env, 7);
    register(&mut env, &u);
    let w = u.wallet.insecure_clone();
    // Byte-perfect SGT shape, but issued in the public test group.
    let mint = Keypair::new().pubkey();
    env.svm
        .set_account(
            mint,
            Account {
                lamports: SOL,
                data: sgt_mint_bytes(&mint, &PUBLIC_TEST_GROUP, &PUBLIC_TEST_AUTHORITY, 1),
                owner: token_2022_id(),
                executable: false,
                rent_epoch: 0,
            },
        )
        .unwrap();
    let ta = associated_token_address(&w.pubkey(), &mint);
    env.svm
        .set_account(
            ta,
            Account {
                lamports: SOL,
                data: sgt_token_account_bytes(&mint, &w.pubkey(), true),
                owner: token_2022_id(),
                executable: false,
                rent_epoch: 0,
            },
        )
        .unwrap();
    let res = env.send_as(&w, &[ix_verify_seeker(&w.pubkey(), &ta, &mint, None)], &[]);
    assert_custom(&res, 0, sgt_err(SgtError::MintAuthorityMismatch));
    // The seat address must be the mint's canonical seat PDA.
    let mut ix = ix_verify_seeker(&w.pubkey(), &ta, &mint, None);
    ix.accounts[2] = AccountMeta::new(seat_pda(&Keypair::new().pubkey()), false);
    let res = env.send_as(&w, &[ix], &[]);
    assert!(res.is_err());
    // The wallet must sign, and must own the rig.
    let mut ix = ix_verify_seeker(&w.pubkey(), &ta, &mint, None);
    ix.accounts[0].is_signer = false;
    assert_ix_err(&env.send(&[ix], &[]), 0, InstructionError::MissingRequiredSignature);
}
