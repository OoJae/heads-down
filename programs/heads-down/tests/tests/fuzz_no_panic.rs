//! No attacker input may make heads_down panic or fault: random bytes for
//! every tag with random account lists, and byte-level mutations (flips,
//! truncation, extension, account swaps) of valid instructions, all executed
//! by the real SBF binary. Every failure must be a clean error, never
//! `ProgramFailedToComplete` (abort / panic / access violation).

use heads_down_tests::*;

struct Rng(u64);

impl Rng {
    fn next(&mut self) -> u64 {
        // xorshift64*
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

fn pool(env: &Env, u: &User) -> Vec<Address> {
    vec![
        u.rig,
        CONFIG,
        EXECUTOR,
        BOARD,
        ORE_CONFIG,
        env.round,
        TREASURY,
        SYSTEM,
        ORE,
        VAR,
        ENTROPY,
        ix_sysvar_id(),
        u.pubkey(),
        env.cranker.pubkey(),
        u.automation(),
        u.miner(),
        Env::program_data(),
        shift_log_pda(&u.rig, 1),
        seat_pda(&u.rig),
        secp256r1_id(),
        HD,
    ]
}

/// Send `ix`; the user's wallet signs iff some meta marks it as a signer.
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
fn random_instructions_never_abort() {
    let mut env = Env::new();
    let u = User::new(&mut env, 1);
    env.onboard_standard(&u);
    let pool = pool(&env, &u);
    let signers = [u.pubkey(), env.cranker.pubkey()];
    let mut rng = Rng(0x9E37_79B9_7F4A_7C15);
    let mut failures = 0usize;
    const N: usize = 3_000;
    for i in 0..N {
        let tag = rng.below(17) as u8;
        let len = rng.below(200);
        let mut data = vec![tag];
        data.extend(rng.bytes(len));
        let n_acc = rng.below(24);
        let accounts = (0..n_acc)
            .map(|_| {
                let a = pool[rng.below(pool.len())];
                let signer = signers.contains(&a) && rng.below(4) != 0;
                AccountMeta {
                    pubkey: a,
                    is_signer: signer,
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
    println!("{N} random instructions: {failures} rejected cleanly, 0 aborts");
}

#[test]
fn mutated_valid_instructions_never_abort() {
    let mut env = Env::new();
    let mut u = User::new(&mut env, 2);
    env.onboard_standard(&u);
    let r = env.board_round;
    let w = u.pubkey();
    let mut rng = Rng(0xD1B5_4A32_D192_ED03);

    const N: usize = 2_000;
    let mut failures = 0usize;
    for i in 0..N {
        // A fresh, valid heartbeat and precompile for the templates that use one.
        let hb = u.heartbeat(1, r, 3);
        let pre = vec![compute_limit(1_400_000), secp_ix_for(&[hb])];
        let templates = [
            ix_dig(
                &env.cranker.pubkey(),
                &env.round,
                &[DigRig::new(&u, entry_for(&hb, 1, 0))],
            ),
            ix_record(&[(u.rig, entry_for(&hb, 1, 0))]),
            ix_set_caps(&w, Caps::standard()),
            ix_arm_wallet(&w, &standard_plan()),
            ix_arm_p256(&w, &standard_plan(), hb.counter, 1, 0),
            ix_break_p256(&w, 1, hb.counter, 1, 0),
            ix_freeze_p256(&w, 3, hb.counter, 1, 0),
            ix_end_shift(&env.cranker.pubkey(), &u.rig, 1),
            ix_rotate_key(
                &w,
                &u.p256(),
                Some(AttestationArg {
                    ix: 1,
                    sig: 0,
                    level: 1,
                    expiry_slot: u64::MAX,
                }),
            ),
            ix_propose(&env.governance.pubkey(), &w, 1, 2, 0),
            ix_verify_seeker(&w, &u.automation(), &u.miner(), Some(u.rig)),
            ix_close_rig(&w, Some(u.rig)),
            ix_register_rig(&w, &u.p256(), None),
        ];
        let mut ix = templates[rng.below(templates.len())].clone();
        match rng.below(5) {
            0 => {
                // flip 1..8 random bytes (keep the tag half the time)
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
                    ix.accounts[at].pubkey = pool(&env, &u)[rng.below(21)];
                    ix.accounts[at].is_signer = false;
                }
            }
        }
        // The governance key is not the user; never mark it as a signer here.
        for m in ix.accounts.iter_mut() {
            if m.is_signer && m.pubkey != u.pubkey() && m.pubkey != env.cranker.pubkey() {
                m.is_signer = false;
            }
        }
        let res = send(&mut env, &u, &pre, ix);
        assert_clean(&res, &format!("mutation #{i}"));
        failures += usize::from(res.is_err());
        // Keep the rig usable: if a mutation froze/broke/closed it, reset.
        if env.svm.get_account(&u.rig).is_none() {
            let wk = u.wallet.insecure_clone();
            ok(env.send_as(
                &wk,
                &[
                    ix_register_rig(&w, &u.p256(), None),
                    ix_set_caps(&w, Caps::standard()),
                ],
                &[],
            ));
            u.counter = 0;
        }
    }
    println!("{N} mutated instructions: {failures} rejected cleanly, 0 aborts");
}
