//! Measurements for README.md: compute units per `dig` for 1, 4 and 7 rigs,
//! and the largest batch that fits a v0 (with and without an address lookup
//! table) and a v1 transaction, checked against every binding limit —
//! wire size, address / account-lock limits and the 1.4M CU cap — by
//! actually executing the largest fitting transaction on the fork.
//!
//! Run with `--nocapture` to see the table.

use heads_down_tests::*;
use solana_message::{v0, v1, AddressLookupTableAccount, VersionedMessage};
use solana_transaction::versioned::VersionedTransaction;

const PACKET: usize = 1232;
const MAX_CU: u64 = 1_400_000;

fn alt_program() -> Address {
    use std::str::FromStr;
    Address::from_str("AddressLookupTab1e1111111111111111111111111").unwrap()
}

/// Put an active lookup table holding `addresses` at a fresh address.
fn put_alt(env: &mut Env, addresses: &[Address]) -> AddressLookupTableAccount {
    let key = Keypair::new().pubkey();
    let mut data = Vec::with_capacity(56 + 32 * addresses.len());
    data.extend_from_slice(&1u32.to_le_bytes()); // ProgramState::LookupTable
    data.extend_from_slice(&u64::MAX.to_le_bytes()); // deactivation_slot
    data.extend_from_slice(&0u64.to_le_bytes()); // last_extended_slot
    data.push(0); // last_extended_slot_start_index
    data.push(1); // authority: Some
    data.extend_from_slice(env.cranker.pubkey().as_ref());
    data.extend_from_slice(&[0, 0]); // padding
    assert_eq!(data.len(), 56);
    for a in addresses {
        data.extend_from_slice(a.as_ref());
    }
    let lamports = env.svm.minimum_balance_for_rent_exemption(data.len());
    env.svm
        .set_account(
            key,
            Account {
                lamports,
                data,
                owner: alt_program(),
                executable: false,
                rent_epoch: 0,
            },
        )
        .unwrap();
    AddressLookupTableAccount {
        key,
        addresses: addresses.to_vec(),
    }
}

#[derive(Clone, Copy, Debug, PartialEq)]
enum Format {
    V0,
    V0Alt,
    V1,
}

struct Batch {
    env: Env,
    users: Vec<User>,
    alt: Option<AddressLookupTableAccount>,
}

impl Batch {
    fn new(n: usize) -> Self {
        let mut env = Env::new();
        let mut users = Vec::new();
        for i in 0..n {
            let u = User::new(&mut env, (i + 1) as u8);
            env.onboard_standard(&u);
            users.push(u);
        }
        Self {
            env,
            users,
            alt: None,
        }
    }

    /// Instructions for a dig of the first `n` rigs (ComputeBudget only when
    /// `with_budget_ix`; v1 carries the limit in its header instead).
    fn ixs(&mut self, n: usize, with_budget_ix: bool) -> Vec<Instruction> {
        let round = self.env.board_round;
        let mut hbs = Vec::new();
        let mut rigs = Vec::new();
        let first_precompile = u8::from(with_budget_ix);
        for (i, u) in self.users.iter_mut().take(n).enumerate() {
            let hb = u.heartbeat(1, round, 3);
            let ix = first_precompile + (i / 8) as u8;
            rigs.push(DigRig::new(u, entry_for(&hb, ix, (i % 8) as u8)));
            hbs.push(hb);
        }
        let mut ixs = Vec::new();
        if with_budget_ix {
            ixs.push(compute_limit(MAX_CU as u32));
        }
        for c in hbs.chunks(8) {
            ixs.push(secp_ix_for(c));
        }
        ixs.push(ix_dig(&self.env.cranker.pubkey(), &self.env.round, &rigs));
        ixs
    }

    fn ensure_alt(&mut self) -> AddressLookupTableAccount {
        if let Some(a) = &self.alt {
            return a.clone();
        }
        // Every non-signer, non-program account a crank would keep in its ALT.
        let mut addrs = vec![
            CONFIG,
            EXECUTOR,
            BOARD,
            ORE_CONFIG,
            self.env.round,
            TREASURY,
            SYSTEM,
            VAR,
            ENTROPY,
            ix_sysvar_id(),
            ORE,
        ];
        for u in &self.users {
            addrs.extend([u.rig, u.pubkey(), u.automation(), u.miner()]);
        }
        let alt = put_alt(&mut self.env, &addrs);
        self.alt = Some(alt.clone());
        alt
    }

    /// Build the transaction; `None` if it cannot even be compiled (v1's
    /// 64-address limit).
    fn tx(&mut self, n: usize, f: Format) -> Option<VersionedTransaction> {
        let payer = self.env.cranker.insecure_clone();
        let bh = self.env.svm.latest_blockhash();
        let msg = match f {
            Format::V0 => {
                let ixs = self.ixs(n, true);
                VersionedMessage::V0(v0::Message::try_compile(&payer.pubkey(), &ixs, &[], bh).ok()?)
            }
            Format::V0Alt => {
                let alt = self.ensure_alt();
                let ixs = self.ixs(n, true);
                VersionedMessage::V0(
                    v0::Message::try_compile(&payer.pubkey(), &ixs, &[alt], bh).ok()?,
                )
            }
            Format::V1 => {
                let ixs = self.ixs(n, false);
                let cfg = v1::TransactionConfig::empty()
                    .with_compute_unit_limit(MAX_CU as u32)
                    .with_loaded_accounts_data_size_limit(64 * 1024 * 1024);
                VersionedMessage::V1(
                    v1::Message::try_compile_with_config(&payer.pubkey(), &ixs, bh, cfg).ok()?,
                )
            }
        };
        Some(VersionedTransaction::try_new(msg, &[&payer]).unwrap())
    }
}

fn size(tx: &VersionedTransaction) -> usize {
    wincode::serialize(tx).unwrap().len()
}

fn limit(f: Format) -> usize {
    match f {
        Format::V0 | Format::V0Alt => PACKET,
        Format::V1 => v1::MAX_TRANSACTION_SIZE,
    }
}

/// One executed dig.
struct Measured {
    size: usize,
    cu: u64,
    ore_cu: u64,
    dug: usize,
}

/// CU consumed inside ORE's `deploy` CPIs (their own nested CPIs included).
fn ore_cu(logs: &[String]) -> u64 {
    let prefix = format!("Program {ORE} consumed ");
    logs.iter()
        .filter_map(|l| l.strip_prefix(&prefix))
        .filter_map(|rest| rest.split(' ').next()?.parse::<u64>().ok())
        .sum()
}

/// Execute a dig of `n` rigs in format `f` on a fresh fork, or say why it
/// does not fit.
fn run(n: usize, f: Format) -> Result<Measured, String> {
    let mut b = Batch::new(n);
    let tx = b
        .tx(n, f)
        .ok_or_else(|| "does not compile (address limit)".to_string())?;
    let size = size(&tx);
    if size > limit(f) {
        return Err(format!("{size} B > {} B", limit(f)));
    }
    let res = b.env.svm.send_transaction(tx);
    match res {
        Ok(m) => {
            let dug = events(&m.logs)
                .iter()
                .filter(|e| matches!(e, Event::RigDug { .. }))
                .count();
            Ok(Measured {
                size,
                cu: m.compute_units_consumed,
                ore_cu: ore_cu(&m.logs),
                dug,
            })
        }
        Err(e) => Err(format!("{:?}", e.err)),
    }
}

#[test]
fn compute_units_per_dig_1_4_7_rigs() {
    // Random wallets change how many bumps ORE's own `has_seeds` walks for
    // each Automation / Miner (1,500 CU per extra attempt), so sample several
    // fresh forks and report min / median / max.
    println!(
        "\n== CU per dig (live ORE fork, 10 split tiles per rig, fresh heartbeat each, v1 tx) =="
    );
    const SAMPLES: usize = 5;
    for n in [1usize, 4, 7] {
        let mut runs: Vec<Measured> = (0..SAMPLES).map(|_| run(n, Format::V1).unwrap()).collect();
        runs.sort_by_key(|m| m.cu);
        for m in &runs {
            assert_eq!(m.dug, n);
            assert!(m.cu < MAX_CU);
        }
        let med = &runs[SAMPLES / 2];
        let hd_overhead = (med.cu - med.ore_cu) / n as u64;
        println!(
            "{n} rig(s): total min {} / median {} / max {} CU; per rig median {} CU \
             (ORE deploy CPI {} + heads_down {}); v1 size {} B",
            runs[0].cu,
            med.cu,
            runs[SAMPLES - 1].cu,
            med.cu / n as u64,
            med.ore_cu / n as u64,
            hd_overhead,
            med.size,
        );
    }
}

#[test]
fn max_rigs_per_transaction() {
    println!("\n== Max rigs per dig transaction ==");
    for f in [Format::V0, Format::V0Alt, Format::V1] {
        let mut best = None;
        let mut why = String::new();
        for n in 1..=32 {
            match run(n, f) {
                Ok(m) => {
                    assert_eq!(m.dug, n, "{f:?} n={n}: every rig must dig");
                    best = Some((n, m.size, m.cu));
                }
                Err(e) => {
                    why = format!("n={n}: {e}");
                    break;
                }
            }
        }
        let (n, sz, cu) = best.expect("at least one rig fits");
        println!("{f:?}: max {n} rigs ({sz} B, {cu} CU); next fails: {why}");
        // Regression floor for the numbers documented in README.md.
        let floor = match f {
            Format::V0 => 2,
            Format::V0Alt => 5,
            Format::V1 => 11,
        };
        assert!(n >= floor, "{f:?}: {n} < {floor}");
        assert!(cu < MAX_CU);
    }
}
