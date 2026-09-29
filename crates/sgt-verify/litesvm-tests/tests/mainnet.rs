//! The mainnet-anchored probe inside LiteSVM: real SGT accounts verify, and
//! forgeries built with the real Token-2022 program do not.

use litesvm::LiteSVM;
use sgt_verify::{
    anchors::{MAINNET_SGT_AUTHORITY, MAINNET_SGT_GROUP},
    layout::{account, extension_type},
    testkit::{self, associated_token_address, ix},
    verify_sgt_raw, RawAccount, SgtError, TOKEN_2022_PROGRAM_ID,
};
use sgt_verify_litesvm_tests::*;
use solana_address::Address;
use solana_instruction_error::InstructionError;
use solana_keypair::Keypair;
use solana_signer::Signer;

const SPL_TOKEN: Address = Address::from_str_const("TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA");

/// `TokenGroupError::IncorrectUpdateAuthority` (spl-token-group-interface:
/// base 3_406_457_176 + 4).
const TOKEN_GROUP_INCORRECT_UPDATE_AUTHORITY: u32 = 3_406_457_180;

/// Mainnet probe, sigverify off (real holders cannot sign here), with both
/// real SGTs and the real group loaded.
fn mainnet_svm() -> (LiteSVM, Keypair, Vec<RealSgt>) {
    let mut svm = svm(Probe::Mainnet, false);
    let payer = payer(&mut svm);
    let sgts = real_sgts();
    for sgt in &sgts {
        load(&mut svm, &sgt.mint);
        load(&mut svm, &sgt.token_account);
    }
    load(&mut svm, &fixture("group.json"));
    (svm, payer, sgts)
}

#[test]
fn real_sgts_verify_on_chain_and_agree_with_the_host() {
    let (mut svm, payer, sgts) = mainnet_svm();
    for sgt in &sgts {
        let out = run_probe_unsigned_holder(
            &mut svm,
            &payer,
            &sgt.token_account.address,
            &sgt.mint.address,
            &sgt.holder,
            true,
        )
        .unwrap_or_else(|f| panic!("{}: {:?}\n{}", sgt.label, f.err, f.logs.join("\n")));
        assert_eq!(out.mint, sgt.mint.address);
        assert_eq!(out.member_number, sgt.member_number);
        assert!(out.frozen);
        println!("{}: verify_sgt probe consumed {} CU", sgt.label, out.compute_units);
        assert!(out.compute_units < 10_000, "{} CU", out.compute_units);

        let host = verify_sgt_raw(
            RawAccount {
                address: &sgt.token_account.address,
                owner: &sgt.token_account.account.owner,
                data: &sgt.token_account.account.data,
            },
            RawAccount {
                address: &sgt.mint.address,
                owner: &sgt.mint.account.owner,
                data: &sgt.mint.account.data,
            },
            &sgt.holder,
        )
        .unwrap();
        assert_eq!(host.member_number, out.member_number);
    }
}

#[test]
fn someone_else_signing_is_rejected() {
    let (mut svm, payer, sgts) = mainnet_svm();
    let impostor = Keypair::new();
    let sgt = &sgts[0];
    assert_sgt_error(
        run_probe_signed(&mut svm, &payer, &sgt.token_account.address, &sgt.mint.address, &impostor),
        SgtError::TokenAccountOwnerMismatch,
    );
}

#[test]
fn holder_must_sign() {
    let (mut svm, payer, sgts) = mainnet_svm();
    let sgt = &sgts[0];
    let failure = run_probe_unsigned_holder(
        &mut svm,
        &payer,
        &sgt.token_account.address,
        &sgt.mint.address,
        &sgt.holder,
        false,
    )
    .unwrap_err();
    assert!(failure.is_instruction_error(0, &InstructionError::MissingRequiredSignature));
}

#[test]
fn mutated_bytes_are_rejected_on_chain() {
    let (mut svm, payer, sgts) = mainnet_svm();
    let sgt = &sgts[0];

    // Swap the group in TokenGroupMember (bytes the runtime would never let
    // anyone but Token-2022 write; set_account stands in for a forged account).
    let mut forged = sgt.mint.account.clone();
    let off = forged.data.len() - 72 + 32;
    forged.data[off..off + 32].copy_from_slice(&[0x42; 32]);
    let forged_mint = Address::new_unique();
    let mut ta = sgt.token_account.account.clone();
    ta.data[account::MINT..account::MINT + 32].copy_from_slice(forged_mint.as_ref());
    let forged_ta = Address::new_unique();
    svm.set_account(forged_mint, forged).unwrap();
    svm.set_account(forged_ta, ta).unwrap();
    let out = run_probe_unsigned_holder(&mut svm, &payer, &forged_ta, &forged_mint, &sgt.holder, true);
    assert_sgt_error(out, SgtError::GroupMismatch);
}

#[test]
fn legacy_token_program_account_is_rejected_on_chain() {
    let (mut svm, payer, sgts) = mainnet_svm();
    let sgt = &sgts[0];
    let fake_ta = Address::new_unique();
    put_account(&mut svm, fake_ta, SPL_TOKEN, sgt.token_account.account.data.clone());
    assert_sgt_error(
        run_probe_unsigned_holder(&mut svm, &payer, &fake_ta, &sgt.mint.address, &sgt.holder, true),
        SgtError::TokenAccountNotToken2022,
    );
}

#[test]
fn mint_passed_twice_is_rejected_on_chain() {
    let (mut svm, payer, sgts) = mainnet_svm();
    let sgt = &sgts[0];
    assert_sgt_error(
        run_probe_unsigned_holder(&mut svm, &payer, &sgt.mint.address, &sgt.mint.address, &sgt.holder, true),
        SgtError::DuplicateAccount,
    );
}

// ---- forging an SGT with the real Token-2022 program ---------------------------

/// Everything a forger can do without GT2zuH's signature: a Token-2022 mint
/// whose every SGT field names GT2zuH / GT22s89, one token in the forger's
/// ATA, and the mint authority handed to GT2zuH afterwards.
struct Forgery {
    mint: Keypair,
    ata: Address,
}

fn forge_base(svm: &mut LiteSVM, forger: &Keypair) -> Forgery {
    let mint = Keypair::new();
    let m = mint.pubkey();
    let gt2 = MAINNET_SGT_AUTHORITY;
    let ixs = vec![
        ix::create_account(
            &forger.pubkey(),
            &m,
            rent(svm, sgt_verify::layout::SGT_MINT_LEN),
            testkit::SGT_MINT_INITIAL_LEN as u64,
            &TOKEN_2022_PROGRAM_ID,
        ),
        // Pointer authorities and targets are plain instruction data: no
        // signature from GT2zuH is needed to write them.
        ix::initialize_metadata_pointer(&m, Some(&gt2), Some(&MAINNET_SGT_GROUP)),
        ix::initialize_permanent_delegate(&m, &gt2),
        ix::initialize_mint_close_authority(&m, Some(&gt2)),
        ix::initialize_group_member_pointer(&m, Some(&gt2), Some(&m)),
        ix::initialize_mint2(&m, 0, &forger.pubkey(), Some(&gt2)),
    ];
    send(svm, &ixs, forger, &[forger, &mint]).unwrap();
    Forgery {
        ata: associated_token_address(&forger.pubkey(), &m),
        mint,
    }
}

fn mint_one_and_hand_over(svm: &mut LiteSVM, forger: &Keypair, f: &Forgery) {
    let m = f.mint.pubkey();
    let ixs = vec![
        ix::create_associated_token_account_idempotent(&forger.pubkey(), &forger.pubkey(), &m),
        ix::mint_to_checked(&m, &f.ata, &forger.pubkey(), 1, 0),
        ix::set_mint_authority(&m, &forger.pubkey(), Some(&MAINNET_SGT_AUTHORITY)),
    ];
    send(svm, &ixs, forger, &[forger]).unwrap();
}

/// The mint-side checks of ORE's removed `claim_seeker` (commit 037aa5e480):
/// Token-2022 owner, ATA of (signer, mint) holding 1, mint authority GT2zuH,
/// MetadataPointer {GT2zuH -> GT22s89}.
fn ore_claim_seeker_accepts(svm: &LiteSVM, signer: &Address, mint: &Address, token_account: &Address) -> bool {
    let mint_acc = svm.get_account(mint).unwrap();
    let ta = svm.get_account(token_account).unwrap();
    let d = &mint_acc.data;
    let mut i = 166;
    let mut metadata_ok = false;
    while i + 4 <= d.len() {
        let ty = u16::from_le_bytes([d[i], d[i + 1]]);
        let len = u16::from_le_bytes([d[i + 2], d[i + 3]]) as usize;
        if ty == extension_type::METADATA_POINTER {
            metadata_ok = d[i + 4..i + 36] == *MAINNET_SGT_AUTHORITY.as_ref()
                && d[i + 36..i + 68] == *MAINNET_SGT_GROUP.as_ref();
        }
        if ty == 0 {
            break;
        }
        i += 4 + len;
    }
    mint_acc.owner == TOKEN_2022_PROGRAM_ID
        && *token_account == associated_token_address(signer, mint)
        && u64::from_le_bytes(ta.data[64..72].try_into().unwrap()) == 1
        && d[0..4] == [1, 0, 0, 0]
        && d[4..36] == *MAINNET_SGT_AUTHORITY.as_ref()
        && metadata_ok
}

#[test]
fn forged_sgt_without_membership_fools_ore_style_check_but_not_verify_sgt() {
    let (mut svm, _, _) = mainnet_svm();
    let forger = payer(&mut svm);
    let f = forge_base(&mut svm, &forger);
    mint_one_and_hand_over(&mut svm, &forger, &f);

    assert!(ore_claim_seeker_accepts(&svm, &forger.pubkey(), &f.mint.pubkey(), &f.ata));
    let payer = payer(&mut svm);
    assert_sgt_error(
        run_probe_signed(&mut svm, &payer, &f.ata, &f.mint.pubkey(), &forger),
        SgtError::MissingGroupMember,
    );
}

#[test]
fn joining_the_real_sgt_group_needs_solana_mobiles_signature() {
    let (mut svm, _, _) = mainnet_svm();
    let forger = payer(&mut svm);
    let f = forge_base(&mut svm, &forger);
    // The forger signs as "group update authority" with their own key.
    let join = ix::initialize_member(&f.mint.pubkey(), &forger.pubkey(), &MAINNET_SGT_GROUP, &forger.pubkey());
    let failure = send(&mut svm, &[join], &forger, &[&forger]).unwrap_err();
    assert!(
        failure.is_instruction_error(0, &InstructionError::Custom(TOKEN_GROUP_INCORRECT_UPDATE_AUTHORITY)),
        "Token-2022 must refuse with IncorrectUpdateAuthority: {:?}\n{}",
        failure.err,
        failure.logs.join("\n")
    );
    // And the real group is untouched.
    let group = svm.get_account(&MAINNET_SGT_GROUP).unwrap();
    assert_eq!(group.data, fixture("group.json").account.data);
}

#[test]
fn forged_sgt_in_the_forgers_own_group_is_rejected() {
    let (mut svm, _, _) = mainnet_svm();
    let forger = payer(&mut svm);

    // The forger's own group, shaped exactly like GT22s89.
    let fake_group = Keypair::new();
    let ixs = testkit::create_test_group(
        &forger.pubkey(),
        &fake_group.pubkey(),
        &forger.pubkey(),
        1_000_000,
        rent(&svm, testkit::TEST_GROUP_MINT_LEN),
    );
    send(&mut svm, &ixs, &forger, &[&forger, &fake_group]).unwrap();

    let f = forge_base(&mut svm, &forger);
    let join = ix::initialize_member(&f.mint.pubkey(), &forger.pubkey(), &fake_group.pubkey(), &forger.pubkey());
    send(&mut svm, &[join], &forger, &[&forger]).unwrap();
    mint_one_and_hand_over(&mut svm, &forger, &f);

    // Token-2022 accepted all of it; the only tell is the group address.
    assert!(ore_claim_seeker_accepts(&svm, &forger.pubkey(), &f.mint.pubkey(), &f.ata));
    let payer = payer(&mut svm);
    assert_sgt_error(
        run_probe_signed(&mut svm, &payer, &f.ata, &f.mint.pubkey(), &forger),
        SgtError::GroupMismatch,
    );
}
