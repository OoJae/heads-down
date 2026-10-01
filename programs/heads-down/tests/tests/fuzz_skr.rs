//! No attacker input may make the v1.2 SKR instructions panic or fault:
//! random bytes for tags 15..=27 with random account lists drawn from the
//! SKR accounts in play, and byte / account mutations of valid SKR
//! instructions, all executed by the real SBF binary on the fork. Every
//! failure must be a clean error, never `ProgramFailedToComplete`.

use hd::state::{gift_kind, plan_flags};
use heads_down_tests::*;

struct Rng(u64);

impl Rng {
    fn next(&mut self) -> u64 {
        self.0 ^= self.0 >> 12;
        self.0 ^= self.0 << 25;
        self.0 ^= self.0 >> 27;
        self.0.wrapping_mul(0x2545_F491_4F6C_DD1D)
    }
    fn below(&mut self, n: usize) -> usize {
        (self.next() % n as u64) as usize
    }
    fn bytes(&mut self, n: usize) -> Vec<u8> {
        (0..n).map(|_| self.next() as u8).collect()
    }
}

#[track_caller]
fn assert_clean(res: &TxResult, what: &str) {
    if let Err(f) = res {
        let aborted = matches!(
            f.err,
            TransactionError::InstructionError(_, InstructionError::ProgramFailedToComplete)
        );
        let faulted = f.logs.iter().any(|l| {
            l.contains("panicked") || l.contains("Access violation") || l.contains("abort")
        });
        assert!(
            !aborted && !faulted,
            "{what}: program aborted: {:?}\n{}",
            f.err,
            f.logs.join("\n")
        );
    }
}

/// A fork with every SKR account in play: a Stack table with a seat, a
/// Focus Bond, a gift, and a Bury lot.
struct World {
    env: Env,
    u: User,
    table: Address,
    pool: Vec<Address>,
}

fn world() -> World {
    let mut env = Env::new();
    env.init_bury_vault();
    let u = User::new(&mut env, 1);
    let mut plan = standard_plan();
    plan.lease = 1;
    plan.flags = plan_flags::FOCUS_ONLY;
    ok(env.onboard(&u, SOL / 20, Caps::standard(), &plan));
    env.fund_skr(&u.pubkey(), 100_000 * ONE_SKR);
    env.fund_ore(&u.pubkey(), 10 * ONE_ORE);
    let w = u.wallet.insecure_clone();
    let p = StackParams {
        table_id: 1,
        bond: 100 * ONE_SKR,
        start_round: env.board_round + 1,
        end_round: env.board_round + 3,
        grace_gaps: 1,
        flags: 0,
        max_seats: 8,
    };
    let table = table_pda(&w.pubkey(), 1);
    let bond = bond_pda(&u.rig, 1);
    ok(env.send_as(
        &w,
        &[
            ix_create_ata(&w.pubkey(), &table, &SKR_MINT),
            ix_open_stack(&w.pubkey(), &p),
            ix_join_stack(&w.pubkey(), &table, &u.rig, None),
            ix_create_ata(&w.pubkey(), &bond, &SKR_MINT),
            ix_lock_focus_bond(&w.pubkey(), 1, 100 * ONE_SKR),
            ix_create_gift(&w.pubkey(), 1, gift_kind::WALLET, &env.cranker.pubkey(), SOL / 100),
        ],
        &[],
    ));
    // A Bury lot by surgery (the auction itself is covered in skr_bury).
    env.set_token_account(&ata(&BURY, &SKR_MINT), &SKR_MINT, &BURY, 1_000 * ONE_SKR);
    env.poke_u64(&BURY, 72, 1_000 * ONE_SKR);
    let pool = vec![
        u.rig,
        u.pubkey(),
        env.cranker.pubkey(),
        table,
        stack_seat_pda(&table, &u.rig),
        ata(&table, &SKR_MINT),
        bond,
        ata(&bond, &SKR_MINT),
        shift_log_pda(&u.rig, 1),
        gift_pda(&w.pubkey(), 1),
        BURY,
        ata(&BURY, &SKR_MINT),
        ata(&BURY, &ORE_MINT),
        ata(&u.pubkey(), &SKR_MINT),
        ata(&u.pubkey(), &ORE_MINT),
        SKR_MINT,
        ORE_MINT,
        SPL_TOKEN,
        token_2022_id(),
        BOARD,
        TREASURY,
        treasury_ore(),
        stake_treasury(),
        stake_treasury_ore(),
        stake_vesting(),
        ORE,
        ore_stake_id(),
        SYSTEM,
        ix_sysvar_id(),
        CONFIG,
        HD,
    ];
    World {
        env,
        u,
        table,
        pool,
    }
}

fn send(env: &mut Env, u: &User, pre: &[Instruction], ix: Instruction) -> TxResult {
    let wallet_signs = ix
        .accounts
        .iter()
        .any(|m| m.is_signer && m.pubkey == u.pubkey());
    let mut ixs = pre.to_vec();
    ixs.push(ix);
    if wallet_signs {
        let w = u.wallet.insecure_clone();
        env.send(&ixs, &[&w])
    } else {
        env.send(&ixs, &[])
    }
}

#[test]
fn random_skr_instructions_never_abort() {
    let World {
        mut env, u, pool, ..
    } = world();
    let signers = [u.pubkey(), env.cranker.pubkey()];
    let mut rng = Rng(0x5EED_5C0F_FEE0_0042);
    let mut failures = 0usize;
    const N: usize = 2_000;
    for i in 0..N {
        let tag = 15 + rng.below(13) as u8;
        let len = rng.below(120);
        let mut data = vec![tag];
        data.extend(rng.bytes(len));
        let n_acc = rng.below(20);
        let accounts = (0..n_acc)
            .map(|_| {
                let a = pool[rng.below(pool.len())];
                AccountMeta {
                    pubkey: a,
                    is_signer: signers.contains(&a) && rng.below(4) != 0,
                    is_writable: rng.below(2) == 0,
                }
            })
            .collect();
        let res = send(
            &mut env,
            &u,
            &[],
            Instruction {
                program_id: HD,
                accounts,
                data,
            },
        );
        assert_clean(&res, &format!("random #{i} tag {tag}"));
        failures += usize::from(res.is_err());
    }
    println!("{N} random SKR instructions: {failures} rejected cleanly, 0 aborts");
}

#[test]
fn mutated_skr_instructions_never_abort() {
    let World {
        mut env,
        mut u,
        table,
        pool,
    } = world();
    let w = u.pubkey();
    let c = env.cranker.pubkey();
    let mut rng = Rng(0xB0D1_E5C0_FFEE_1234);
    let start = env.board_round + 1;
    const N: usize = 2_000;
    let mut failures = 0usize;
    for i in 0..N {
        // Walk the Board through the window so every phase is reachable.
        let r = start - 1 + rng.below(5) as u64;
        env.set_board_round(r);
        let hb = u.heartbeat(1, r, 1);
        let pre = vec![secp_ix_for(&[hb])];
        let seat = stack_seat_pda(&table, &u.rig);
        let p = StackParams {
            table_id: 2 + rng.below(3) as u64,
            bond: ONE_SKR,
            start_round: r + 1,
            end_round: r + 2,
            grace_gaps: 0,
            flags: 0,
            max_seats: 2,
        };
        let templates = [
            ix_open_stack(&w, &p),
            ix_join_stack(&w, &table, &u.rig, None),
            ix_stack_checkin(&table, &[(seat, u.rig, entry_for(&hb, 0, 0))]),
            ix_stack_checkin(&table, &[(seat, u.rig, reuse_lease())]),
            ix_settle_stack(&table, &[seat]),
            ix_claim_stack(&table, &seat, &w),
            ix_lock_focus_bond(&w, 1, ONE_SKR),
            ix_release_focus_bond(&w, 1),
            ix_forfeit_focus_bond(&w, 1),
            ix_create_gift(&w, 2, gift_kind::SGT_MINT, &c, 1),
            ix_claim_gift(&c, &gift_pda(&w, 1), &w, None),
            ix_refund_gift(&gift_pda(&w, 1), &w),
            ix_init_bury_vault(&c),
            ix_bury_auction_buy(&w, ONE_SKR, u64::MAX),
        ];
        let mut ix = templates[rng.below(templates.len())].clone();
        match rng.below(5) {
            0 => {
                for _ in 0..1 + rng.below(8) {
                    let at = rng.below(ix.data.len());
                    if at != 0 || rng.below(2) == 0 {
                        ix.data[at] ^= 1 << rng.below(8);
                    }
                }
            }
            1 => {
                let keep = rng.below(ix.data.len() + 1);
                ix.data.truncate(keep);
            }
            2 => {
                let n = 1 + rng.below(40);
                let extra = rng.bytes(n);
                ix.data.extend(extra);
            }
            3 => {
                if ix.accounts.len() > 1 {
                    let a = rng.below(ix.accounts.len());
                    let b = rng.below(ix.accounts.len());
                    ix.accounts.swap(a, b);
                }
            }
            _ => {
                if !ix.accounts.is_empty() {
                    let at = rng.below(ix.accounts.len());
                    ix.accounts[at].pubkey = pool[rng.below(pool.len())];
                    ix.accounts[at].is_signer = false;
                }
            }
        }
        for m in ix.accounts.iter_mut() {
            if m.is_signer && m.pubkey != u.pubkey() && m.pubkey != c {
                m.is_signer = false;
            }
        }
        let res = send(&mut env, &u, &pre, ix);
        assert_clean(&res, &format!("mutation #{i}"));
        failures += usize::from(res.is_err());
        if env.svm.get_account(&u.rig).is_none() {
            break;
        }
        u.counter = env.rig(&u.rig).hb_counter.get();
    }
    println!("{N} mutated SKR instructions: {failures} rejected cleanly, 0 aborts");
}
