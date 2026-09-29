//! The `test-group` probe inside LiteSVM, with full signature verification:
//! a test SGT issued through the real Token-2022 program by the testkit
//! verifies exactly like a mainnet SGT, and is byte-identical to what the
//! testkit synthesizes.

use litesvm::LiteSVM;
use sgt_verify::{
    anchors::{PUBLIC_TEST_AUTHORITY, PUBLIC_TEST_GROUP},
    layout::{SGT_MINT_LEN, SGT_TOKEN_ACCOUNT_LEN},
    testkit::{
        associated_token_address, create_test_group, issue_test_sgt, move_test_sgt,
        sgt_mint_bytes, sgt_token_account_bytes, TEST_GROUP_MINT_LEN,
    },
    SgtError,
};
use sgt_verify_litesvm_tests::*;
use solana_address::Address;
use solana_keypair::Keypair;
use solana_signer::Signer;

struct Env {
    svm: LiteSVM,
    payer: Keypair,
    authority: Keypair,
}

/// Test-group probe with the public test group created on-chain.
fn env() -> Env {
    let mut svm = svm(Probe::TestGroup, true);
    let payer = payer(&mut svm);
    let (authority, group) = public_test_keys();
    assert_eq!(authority.pubkey(), PUBLIC_TEST_AUTHORITY, "seed/anchor drift");
    assert_eq!(group.pubkey(), PUBLIC_TEST_GROUP, "seed/anchor drift");

    let ixs = create_test_group(
        &payer.pubkey(),
        &group.pubkey(),
        &authority.pubkey(),
        1_000_000,
        rent(&svm, TEST_GROUP_MINT_LEN),
    );
    send(&mut svm, &ixs, &payer, &[&payer, &group, &authority]).unwrap();
    assert_eq!(
        svm.get_account(&PUBLIC_TEST_GROUP).unwrap().data.len(),
        TEST_GROUP_MINT_LEN
    );
    Env {
        svm,
        payer,
        authority,
    }
}

/// Issue a test SGT to `holder`; returns the member mint.
fn issue(env: &mut Env, holder: &Address) -> Address {
    let member = Keypair::new();
    let ixs = issue_test_sgt(
        &env.payer.pubkey(),
        &PUBLIC_TEST_GROUP,
        &env.authority.pubkey(),
        &member.pubkey(),
        holder,
        rent(&env.svm, SGT_MINT_LEN),
    );
    let (payer, authority) = (env.payer.insecure_clone(), env.authority.insecure_clone());
    send(&mut env.svm, &ixs, &payer, &[&payer, &member, &authority])
        .unwrap_or_else(|f| panic!("issue failed: {:?}\n{}", f.err, f.logs.join("\n")));
    member.pubkey()
}

#[test]
fn issued_test_sgt_verifies_with_real_signatures() {
    let mut env = env();
    let holder = Keypair::new();
    let mint = issue(&mut env, &holder.pubkey());
    let ata = associated_token_address(&holder.pubkey(), &mint);
    let payer = env.payer.insecure_clone();
    let out = run_probe_signed(&mut env.svm, &payer, &ata, &mint, &holder).unwrap();
    assert_eq!(out.mint, mint);
    assert_eq!(out.member_number, 1);
    assert!(out.frozen, "issuance ends with FreezeAccount, like mainnet");
    println!("test-group verify_sgt probe consumed {} CU", out.compute_units);
}

#[test]
fn member_numbers_increase_per_issue() {
    let mut env = env();
    let payer = env.payer.insecure_clone();
    for expected in 1..=3u64 {
        let holder = Keypair::new();
        let mint = issue(&mut env, &holder.pubkey());
        let ata = associated_token_address(&holder.pubkey(), &mint);
        let out = run_probe_signed(&mut env.svm, &payer, &ata, &mint, &holder).unwrap();
        assert_eq!(out.member_number, expected);
    }
}

#[test]
fn token_2022_output_is_byte_identical_to_the_synthetic_accounts() {
    let mut env = env();
    let holder = Address::new_unique();
    let mint = issue(&mut env, &holder);
    let on_chain_mint = env.svm.get_account(&mint).unwrap();
    let on_chain_ta = env
        .svm
        .get_account(&associated_token_address(&holder, &mint))
        .unwrap();
    assert_eq!(on_chain_mint.data.len(), SGT_MINT_LEN);
    assert_eq!(on_chain_ta.data.len(), SGT_TOKEN_ACCOUNT_LEN);
    assert_eq!(
        on_chain_mint.data,
        sgt_mint_bytes(&mint, &PUBLIC_TEST_GROUP, &PUBLIC_TEST_AUTHORITY, 1)
    );
    assert_eq!(on_chain_ta.data, sgt_token_account_bytes(&mint, &holder, true));
}

#[test]
fn synthetic_accounts_verify_like_a_surfpool_cheatcode_would() {
    let mut svm = svm(Probe::TestGroup, true);
    let payer = payer(&mut svm);
    let holder = Keypair::new();
    let mint = Address::new_unique();
    let ta = Address::new_unique();
    put_token_2022_account(
        &mut svm,
        mint,
        sgt_mint_bytes(&mint, &PUBLIC_TEST_GROUP, &PUBLIC_TEST_AUTHORITY, 77),
    );
    put_token_2022_account(&mut svm, ta, sgt_token_account_bytes(&mint, &holder.pubkey(), true));
    let out = run_probe_signed(&mut svm, &payer, &ta, &mint, &holder).unwrap();
    assert_eq!(out.member_number, 77);
}

#[test]
fn permissioned_move_transfers_the_seat_to_the_new_wallet() {
    let mut env = env();
    let (old, new) = (Keypair::new(), Keypair::new());
    let mint = issue(&mut env, &old.pubkey());
    let payer = env.payer.insecure_clone();
    let authority = env.authority.insecure_clone();
    let ixs = move_test_sgt(&payer.pubkey(), &authority.pubkey(), &mint, &old.pubkey(), &new.pubkey());
    send(&mut env.svm, &ixs, &payer, &[&payer, &authority]).unwrap();

    let old_ata = associated_token_address(&old.pubkey(), &mint);
    let new_ata = associated_token_address(&new.pubkey(), &mint);
    assert_sgt_error(
        run_probe_signed(&mut env.svm, &payer, &old_ata, &mint, &old),
        SgtError::AmountNotOne,
    );
    let out = run_probe_signed(&mut env.svm, &payer, &new_ata, &mint, &new).unwrap();
    assert_eq!(out.mint, mint, "same SGT mint, so key seats by mint");
    assert!(out.frozen);
}

#[test]
fn real_mainnet_sgts_fail_against_a_test_build() {
    let mut svm = svm(Probe::TestGroup, false);
    let payer = payer(&mut svm);
    for sgt in real_sgts() {
        load(&mut svm, &sgt.mint);
        load(&mut svm, &sgt.token_account);
        assert_sgt_error(
            run_probe_unsigned_holder(
                &mut svm,
                &payer,
                &sgt.token_account.address,
                &sgt.mint.address,
                &sgt.holder,
                true,
            ),
            SgtError::MintAuthorityMismatch,
        );
    }
}
