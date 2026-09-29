//! The crank's hand-built lookup-table instructions against the real Address Lookup Table
//! program (LiteSVM loads the same core-BPF build mainnet runs), plus the warm-up rule.

use hd_crank::alt::{self, LookupTable};
use hd_crank::tx;
use litesvm::LiteSVM;
use solana_address::Address;
use solana_instruction::{AccountMeta, Instruction};
use solana_keypair::Keypair;
use solana_message::{v0, VersionedMessage};
use solana_signer::Signer;
use solana_transaction::versioned::VersionedTransaction;

fn send(svm: &mut LiteSVM, payer: &Keypair, ixs: &[Instruction], alts: &[solana_message::AddressLookupTableAccount]) -> Result<(), String> {
    let msg = v0::Message::try_compile(&payer.pubkey(), ixs, alts, svm.latest_blockhash()).map_err(|e| e.to_string())?;
    let t = VersionedTransaction::try_new(VersionedMessage::V0(msg), &[payer]).map_err(|e| e.to_string())?;
    let r = svm.send_transaction(t).map(|_| ()).map_err(|e| format!("{:?} {:?}", e.err, e.meta.logs));
    svm.expire_blockhash();
    r
}

fn table(svm: &LiteSVM, key: Address) -> LookupTable {
    let a = svm.get_account(&key).expect("table exists");
    LookupTable::decode(key, &a.owner, &a.data).expect("decodes")
}

#[test]
fn create_extend_decode_and_use_a_table() {
    let mut svm = LiteSVM::new();
    let payer = Keypair::new();
    svm.airdrop(&payer.pubkey(), 10_000_000_000).unwrap();
    svm.warp_to_slot(100);

    // Create: recent_slot must be in SlotHashes; LiteSVM records the slots it warped through.
    let recent = svm.get_sysvar::<solana_slot_hashes::SlotHashes>().first().map(|(s, _)| *s).unwrap_or(0);
    let (create, key) = alt::create_table_ix(&payer.pubkey(), &payer.pubkey(), recent);
    send(&mut svm, &payer, &[create], &[]).expect("create table");
    let t = table(&svm, key);
    assert!(t.addresses.is_empty());
    assert_eq!(t.authority, Some(payer.pubkey()));
    assert_eq!(t.deactivation_slot, u64::MAX);

    // Extend with the shared dig accounts plus one recipient.
    let recipient = Address::new_from_array([42; 32]);
    let mut addrs = alt::shared_addresses(&hd_crank::hd::PROGRAM_ID);
    addrs.push(recipient);
    let ext = alt::extend_table_ix(&key, &payer.pubkey(), &payer.pubkey(), &addrs);
    send(&mut svm, &payer, &[ext], &[]).expect("extend table");
    let t = table(&svm, key);
    assert_eq!(t.addresses, addrs);
    assert_eq!(alt::missing(std::slice::from_ref(&t), &addrs), Vec::<Address>::new());

    // Warm-up: in the extend slot nothing new is usable; one slot later everything is.
    let slot = svm.get_sysvar::<solana_clock::Clock>().slot;
    assert!(t.usable(slot).addresses.is_empty());
    svm.warp_to_slot(slot + 1);
    let usable = t.usable(slot + 1);
    assert_eq!(usable.addresses.len(), addrs.len());

    // A v0 transaction that loads the recipient from the table.
    let transfer = Instruction {
        program_id: hd_crank::ore::SYSTEM_PROGRAM_ID,
        accounts: vec![AccountMeta::new(payer.pubkey(), true), AccountMeta::new(recipient, false)],
        data: {
            let mut d = 2u32.to_le_bytes().to_vec();
            d.extend_from_slice(&1_000_000u64.to_le_bytes());
            d
        },
    };
    let msg = v0::Message::try_compile(&payer.pubkey(), std::slice::from_ref(&transfer), std::slice::from_ref(&usable), svm.latest_blockhash()).unwrap();
    assert_eq!(msg.address_table_lookups.len(), 1, "recipient loaded from the table");
    send(&mut svm, &payer, std::slice::from_ref(&transfer), std::slice::from_ref(&usable)).expect("v0 tx through the table");
    assert_eq!(svm.get_balance(&recipient), Some(1_000_000));

    // tx::account_count counts looked-up accounts as locks too.
    let msg = VersionedMessage::V0(
        v0::Message::try_compile(&payer.pubkey(), &[transfer], &[usable], svm.latest_blockhash()).unwrap(),
    );
    assert_eq!(tx::account_count(&msg), 3, "payer + system program + looked-up recipient");
}

#[test]
fn only_the_authority_can_extend() {
    let mut svm = LiteSVM::new();
    let payer = Keypair::new();
    let mallory = Keypair::new();
    svm.airdrop(&payer.pubkey(), 10_000_000_000).unwrap();
    svm.airdrop(&mallory.pubkey(), 10_000_000_000).unwrap();
    svm.warp_to_slot(50);
    let recent = svm.get_sysvar::<solana_slot_hashes::SlotHashes>().first().map(|(s, _)| *s).unwrap_or(0);
    let (create, key) = alt::create_table_ix(&payer.pubkey(), &payer.pubkey(), recent);
    send(&mut svm, &payer, &[create], &[]).unwrap();
    let ext = alt::extend_table_ix(&key, &mallory.pubkey(), &mallory.pubkey(), &[Address::new_from_array([1; 32])]);
    assert!(send(&mut svm, &mallory, &[ext], &[]).is_err());
}
