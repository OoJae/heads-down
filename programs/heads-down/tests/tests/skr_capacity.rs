//! Measurements for INTERFACE.md §11.11: compute units of every v1.2 SKR
//! instruction on the fork, and the largest `stack_checkin` that fits one
//! 1,232-byte v0 transaction without a lookup table in each mode (verify
//! mode carries a 143-byte secp256r1 entry per seat), executed at that size.
//! Run with `--nocapture` to see the numbers.

use hd::state::{gift_kind, plan_flags};
use heads_down_tests::*;
use solana_message::{v0, VersionedMessage};
use solana_transaction::versioned::VersionedTransaction;

const PACKET: usize = 1232;

fn size_signed(env: &Env, ixs: &[Instruction], extra: &[&Keypair]) -> usize {
    let payer = env.cranker.insecure_clone();
    let msg = VersionedMessage::V0(
        v0::Message::try_compile(&payer.pubkey(), ixs, &[], env.svm.latest_blockhash()).unwrap(),
    );
    let mut signers: Vec<&Keypair> = vec![&payer];
    signers.extend_from_slice(extra);
    let tx = VersionedTransaction::try_new(msg, &signers).unwrap();
    wincode::serialize(&tx).unwrap().len()
}

fn size(env: &Env, ixs: &[Instruction]) -> usize {
    size_signed(env, ixs, &[])
}

struct Table {
    env: Env,
    users: Vec<User>,
    table: Address,
}

fn table(n: usize) -> Table {
    let mut env = Env::new();
    env.init_bury_vault();
    let mut plan = standard_plan();
    plan.lease = 1;
    plan.flags = plan_flags::FOCUS_ONLY;
    let mut users = vec![];
    for i in 0..n {
        let u = User::new(&mut env, (i + 1) as u8);
        ok(env.onboard(&u, SOL / 20, Caps::standard(), &plan));
        env.fund_skr(&u.pubkey(), 1_000 * ONE_SKR);
        users.push(u);
    }
    let host = users[0].wallet.insecure_clone();
    let p = StackParams {
        table_id: 1,
        bond: 100 * ONE_SKR,
        start_round: env.board_round + 1,
        end_round: env.board_round + 1,
        grace_gaps: 0,
        flags: 0,
        max_seats: 8,
    };
    let table = table_pda(&host.pubkey(), 1);
    let meta = ok(env.send_as(
        &host,
        &[
            ix_create_ata(&host.pubkey(), &table, &SKR_MINT),
            ix_open_stack(&host.pubkey(), &p),
        ],
        &[],
    ));
    println!("open_stack (+ ATA create): {} CU", meta.compute_units_consumed);
    for (i, u) in users.iter().enumerate() {
        let w = u.wallet.insecure_clone();
        let meta = ok(env.send_as(&w, &[ix_join_stack(&w.pubkey(), &table, &u.rig, None)], &[]));
        if i == 0 {
            println!("join_stack: {} CU", meta.compute_units_consumed);
        }
    }
    let r = env.board_round + 1;
    env.set_board_round(r);
    Table { env, users, table }
}

fn verify_ixs(t: &mut Table, n: usize) -> Vec<Instruction> {
    let r = t.env.board_round;
    let mut hbs = vec![];
    let mut seats = vec![];
    for (i, u) in t.users.iter_mut().take(n).enumerate() {
        let hb = u.heartbeat(1, r, 1);
        seats.push((stack_seat_pda(&t.table, &u.rig), u.rig, entry_for(&hb, 0, i as u8)));
        hbs.push(hb);
    }
    vec![secp_ix_for(&hbs), ix_stack_checkin(&t.table, &seats)]
}

#[test]
fn stack_check_ins_fit_and_settle_with_eight_seats() {
    // Verify mode: find the largest n that fits, then run it.
    let mut t = table(8);
    let mut fit = 0;
    for n in 1..=8 {
        let ixs = verify_ixs(&mut t, n);
        let sz = size(&t.env, &ixs);
        println!("stack_checkin verify mode, {n} seats: {sz} B");
        if sz <= PACKET {
            fit = n;
        }
        for u in t.users.iter_mut().take(n) {
            u.counter -= 1; // these were only measured, never sent
        }
    }
    assert!(fit >= 3, "at least 3 verified seats per packet");
    let ixs = verify_ixs(&mut t, fit);
    let meta = ok(t.env.send(&ixs, &[]));
    println!(
        "stack_checkin verify mode, {fit} seats (max per legacy/v0 packet): {} CU",
        meta.compute_units_consumed
    );
    // Observe mode for all 8 (the heartbeats landed through record_heartbeats).
    let r = t.env.board_round;
    let rest: Vec<(Address, Heartbeat)> = t
        .users
        .iter_mut()
        .skip(fit)
        .map(|u| (u.rig, u.heartbeat(1, r, 1)))
        .collect();
    for chunk in rest.chunks(4) {
        let hbs: Vec<Heartbeat> = chunk.iter().map(|(_, h)| *h).collect();
        let entries: Vec<(Address, hd::instructions::HeartbeatEntry)> = chunk
            .iter()
            .enumerate()
            .map(|(i, (rig, h))| (*rig, entry_for(h, 0, i as u8)))
            .collect();
        ok(t.env.send(&[secp_ix_for(&hbs), ix_record(&entries)], &[]));
    }
    let seats: Vec<(Address, Address, hd::instructions::HeartbeatEntry)> = t
        .users
        .iter()
        .map(|u| (stack_seat_pda(&t.table, &u.rig), u.rig, reuse_lease()))
        .collect();
    let ix = ix_stack_checkin(&t.table, &seats);
    let sz = size(&t.env, std::slice::from_ref(&ix));
    let meta = ok(t.env.send(&[ix], &[]));
    println!(
        "stack_checkin observe mode, 8 seats: {sz} B, {} CU",
        meta.compute_units_consumed
    );
    assert!(sz <= PACKET);
    // Settle all 8 seats in one instruction.
    t.env.set_board_round(t.env.board_round + 1);
    let all: Vec<Address> = t
        .users
        .iter()
        .map(|u| stack_seat_pda(&t.table, &u.rig))
        .collect();
    let ix = ix_settle_stack(&t.table, &all);
    let sz = size(&t.env, std::slice::from_ref(&ix));
    let meta = ok(t.env.send(&[ix], &[]));
    println!("settle_stack, 8 seats: {sz} B, {} CU", meta.compute_units_consumed);
    assert!(sz <= PACKET);
    let u = &t.users[0];
    let meta = ok(t.env.send(
        &[ix_claim_stack(&t.table, &stack_seat_pda(&t.table, &u.rig), &u.pubkey())],
        &[],
    ));
    println!("claim_stack: {} CU", meta.compute_units_consumed);
}

#[test]
fn every_other_skr_instruction_is_cheap() {
    let mut env = Env::new();
    let meta = ok(env.send(&[ix_init_bury_vault(&env.cranker.pubkey())], &[]));
    println!("init_bury_vault: {} CU", meta.compute_units_consumed);
    let c = env.cranker.pubkey();
    ok(env.send(
        &[
            ix_create_ata(&c, &BURY, &SKR_MINT),
            ix_create_ata(&c, &BURY, &ORE_MINT),
        ],
        &[],
    ));
    let u = User::new(&mut env, 1);
    env.onboard_standard(&u);
    env.fund_skr(&u.pubkey(), 1_000 * ONE_SKR);
    let w = u.wallet.insecure_clone();
    let bond = bond_pda(&u.rig, 1);
    ok(env.send_as(&w, &[ix_create_ata(&w.pubkey(), &bond, &SKR_MINT)], &[]));
    let meta = ok(env.send_as(&w, &[ix_lock_focus_bond(&w.pubkey(), 1, 100 * ONE_SKR)], &[]));
    println!("lock_focus_bond: {} CU", meta.compute_units_consumed);
    ok(env.send_as(
        &w,
        &[
            ix_break_wallet(&w.pubkey(), hd::state::break_reason::MANUAL),
            ix_end_shift(&w.pubkey(), &u.rig, 1),
        ],
        &[],
    ));
    let meta = ok(env.send(&[ix_forfeit_focus_bond(&w.pubkey(), 1)], &[]));
    println!("forfeit_focus_bond: {} CU", meta.compute_units_consumed);

    let meta = ok(env.send_as(
        &w,
        &[ix_create_gift(&w.pubkey(), 1, gift_kind::WALLET, &c, SOL / 10)],
        &[],
    ));
    println!("create_gift: {} CU", meta.compute_units_consumed);
    let meta = ok(env.send(&[ix_claim_gift(&c, &gift_pda(&w.pubkey(), 1), &w.pubkey(), None)], &[]));
    println!("claim_gift (wallet): {} CU", meta.compute_units_consumed);
    let holder = Keypair::new();
    env.svm.airdrop(&holder.pubkey(), SOL).unwrap();
    let (mint, account) = env.give_real_sgt("member-20", &holder.pubkey());
    ok(env.send_as(
        &w,
        &[ix_create_gift(&w.pubkey(), 2, gift_kind::SGT_MINT, &mint, SOL / 10)],
        &[],
    ));
    let h = holder.insecure_clone();
    let meta = ok(env.send_as(
        &h,
        &[ix_claim_gift(&h.pubkey(), &gift_pda(&w.pubkey(), 2), &w.pubkey(), Some((account, mint)))],
        &[],
    ));
    println!("claim_gift (SGT re-verified): {} CU", meta.compute_units_consumed);
    ok(env.send_as(
        &w,
        &[ix_create_gift(&w.pubkey(), 3, gift_kind::WALLET, &c, SOL / 10)],
        &[],
    ));
    env.advance_time(hd::skr::GIFT_EXPIRY_SECS);
    let meta = ok(env.send(&[ix_refund_gift(&gift_pda(&w.pubkey(), 3), &w.pubkey())], &[]));
    println!("refund_gift: {} CU", meta.compute_units_consumed);

    let b = Keypair::new();
    env.svm.airdrop(&b.pubkey(), SOL).unwrap();
    env.fund_ore(&b.pubkey(), ONE_ORE);
    env.fund_skr(&b.pubkey(), 0);
    let k = b.insecure_clone();
    let ix = ix_bury_auction_buy(&k.pubkey(), 10 * ONE_SKR, u64::MAX);
    let sz = size_signed(&env, std::slice::from_ref(&ix), &[&k]);
    let meta = ok(env.send(&[ix], &[&k]));
    println!(
        "bury_auction_buy (with ORE bury + stake distribute CPIs): {sz} B, {} CU",
        meta.compute_units_consumed
    );
    assert!(meta.compute_units_consumed < 200_000);

    // release path on a fresh shift (a window around the current clock; caps
    // renewed because the suite's caps expire after 7 days).
    let mut plan = standard_plan();
    plan.window_start = env.now - 3_600;
    plan.window_end = env.now + 8 * 3_600;
    let mut caps = Caps::standard();
    caps.expiry = plan.window_end + 3_600;
    ok(env.send_as(
        &w,
        &[ix_set_caps(&w.pubkey(), caps), ix_arm_wallet(&w.pubkey(), &plan)],
        &[],
    ));
    let bond2 = bond_pda(&u.rig, 2);
    ok(env.send_as(
        &w,
        &[
            ix_create_ata(&w.pubkey(), &bond2, &SKR_MINT),
            ix_lock_focus_bond(&w.pubkey(), 2, 100 * ONE_SKR),
        ],
        &[],
    ));
    let mut u = u;
    let r = env.board_round;
    let hb = u.heartbeat(2, r, 3);
    ok(env.send(&[secp_ix_for(&[hb]), ix_record(&[(u.rig, entry_for(&hb, 0, 0))])], &[]));
    env.set_clock(env.slot, plan.window_end + 1);
    // Lease r..=r+2, then the 3-round grace of a permissionless end.
    env.set_board_round(r + 6);
    ok(env.send(&[ix_end_shift(&c, &u.rig, 2)], &[]));
    let meta = ok(env.send(&[ix_release_focus_bond(&u.pubkey(), 2)], &[]));
    println!("release_focus_bond: {} CU", meta.compute_units_consumed);
}
