//! `ore-round-driver`: keeps ORE rounds advancing on the local fork.
//!
//! On mainnet three parties keep ORE moving, and none of them runs on a fork:
//! 1. ~170 miners, the first of whom starts each round (`deploy` sets `end_slot`);
//! 2. ORE's entropy provider, which `reveal`s the committed seed after `end_at`;
//! 3. ORE's own crank, which calls the permissionless `reset` after the intermission.
//!
//! The driver plays all three, using only public instructions of the unmodified mainnet
//! programs:
//! * **background miner** (optional, default on): a manual ORE `deploy` of a few lamports on
//!   a pseudo-random set of squares shortly after each reset, so the round starts and the
//!   crank can dig late in the window exactly as on mainnet;
//! * **entropy provider**: `sample` + `reveal` from the local hash chain (`entropy.rs`) once
//!   `slot >= end_slot`;
//! * **reset**: once `slot >= end_slot + intermission_slots`, with the correct top miner
//!   (`reset.rs:190-211` panics on a wrong one), found by replaying ORE's own sampling.

use std::path::PathBuf;
use std::time::Duration;

use anyhow::{anyhow, bail, Result};
use hd_crank::ore::{self as core, Board, Miner, OreConfig, Round, BOARD_ADDRESS, CONFIG_ADDRESS, ORE_PROGRAM_ID};
use hd_crank::rpc::Filter;
use solana_address::Address;
use solana_instruction::Instruction;
use solana_keypair::Keypair;
use solana_signer::Signer;

use crate::entropy::HashChain;
use crate::ore::{self, var};
use crate::util::{b32_at, read_keypair, u64_at, Chain, SOL};

/// Options.
pub struct DriverOpts {
    /// JSON-RPC URL.
    pub rpc: String,
    /// Pays for `reset` (rent of the next Round account) and the entropy transactions.
    pub payer: PathBuf,
    /// Entropy secret (hex, 32 bytes).
    pub entropy_secret: PathBuf,
    /// Background miner keypair (None = do not start rounds).
    pub background: Option<PathBuf>,
    /// Lamports per square for the background miner.
    pub background_lamports: u64,
    /// Slots to wait after a reset before the background miner starts the round.
    pub start_delay_slots: u64,
    /// Stop after this many resets (None = forever).
    pub rounds: Option<u64>,
}

fn now() -> String {
    let t = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap_or_default();
    let s = t.as_secs() % 86_400;
    format!("{:02}:{:02}:{:02}", s / 3600, (s / 60) % 60, s % 60)
}

macro_rules! say {
    ($($t:tt)*) => { println!("[{}] {}", now(), format!($($t)*)) };
}

struct Snapshot {
    slot: u64,
    board: Board,
    cfg: OreConfig,
    var: Vec<u8>,
}

async fn snapshot(chain: &Chain) -> Result<Snapshot> {
    let slot = chain.slot().await?;
    let accs = chain
        .rpc
        .get_multiple_accounts(&[BOARD_ADDRESS, CONFIG_ADDRESS, ore::VAR_ADDRESS])
        .await
        .map_err(|e| anyhow!("getMultipleAccounts: {e}"))?;
    let [b, c, v]: [Option<hd_crank::account::RawAccount>; 3] =
        accs.try_into().map_err(|_| anyhow!("getMultipleAccounts: wrong length"))?;
    let b = b.ok_or_else(|| anyhow!("ORE Board missing: is this the dev stack?"))?;
    let c = c.ok_or_else(|| anyhow!("ORE Config missing"))?;
    let v = v.ok_or_else(|| anyhow!("entropy Var missing"))?;
    Ok(Snapshot {
        slot,
        board: Board::decode(&b.owner, &b.data).map_err(|e| anyhow!("Board: {e}"))?,
        cfg: OreConfig::decode(&c.owner, &c.data).map_err(|e| anyhow!("ORE Config: {e}"))?,
        var: v.data,
    })
}

/// The Miner account `reset` must be given for round `round_id` whose entropy value is
/// `value`: the holder of the sampled lamport on a winning *solo* square (`reset.rs:188-214`,
/// `state/round.rs:79-84`). Any account works for a split square or an empty one.
///
/// Candidates are the known local miners first (the background miner and every heads_down
/// rig's authority), then, only if none matches, a filtered `getProgramAccounts` over ORE.
/// The first path never asks a lazily-forking engine (Surfpool) to scan mainnet ORE.
pub async fn top_miner(chain: &Chain, round_id: u64, value: &[u8; 32], known: &[Address]) -> Result<(Address, String)> {
    let placeholder = core::miner_pda(&Address::default());
    let Some(r) = ore::rng(value) else {
        return Ok((placeholder, "no rng: ORE refunds the round".into()));
    };
    let ws = (r % 25) as usize;
    let (o, d, _) = chain.data(&core::round_pda(round_id)).await?.ok_or_else(|| anyhow!("Round {round_id} missing"))?;
    let round = Round::decode(&o, &d).map_err(|e| anyhow!("Round: {e}"))?;
    let on_square = round.deployed[ws];
    if ore::is_split(round_id, ws) || on_square == 0 {
        let kind = if on_square == 0 { "empty" } else { "split" };
        return Ok((placeholder, format!("winning square {ws} ({kind}, {on_square} lamports)")));
    }
    let sample = r.reverse_bits() % on_square;
    let holds = |data: &[u8]| {
        let cum = u64_at(data, 464 + 8 * ws);
        let dep = u64_at(data, 64 + 8 * ws);
        u64_at(data, 664) == round_id && dep > 0 && sample >= cum && sample < cum.saturating_add(dep)
    };
    let found = |addr: Address, data: &[u8]| {
        let auth = Address::new_from_array(b32_at(data, 8));
        (addr, format!("winning square {ws} (solo, {on_square} lamports), top miner {auth}"))
    };

    let mut authorities: Vec<Address> = known.to_vec();
    let rigs = chain
        .rpc
        .get_program_accounts(&hd_crank::hd::PROGRAM_ID, &[Filter::DataSize(384), Filter::Memcmp { offset: 0, bytes: vec![2, 1] }])
        .await
        .unwrap_or_default();
    authorities.extend(rigs.iter().map(|(_, a)| Address::new_from_array(b32_at(&a.data, 8))));
    let pdas: Vec<Address> = authorities.iter().map(core::miner_pda).collect();
    for chunk in pdas.chunks(100) {
        let accs = chain.rpc.get_multiple_accounts(chunk).await.map_err(|e| anyhow!("getMultipleAccounts: {e}"))?;
        for (addr, acc) in chunk.iter().zip(accs) {
            if let Some(acc) = acc {
                if acc.owner == ORE_PROGRAM_ID && acc.data.len() == 752 && holds(&acc.data) {
                    return Ok(found(*addr, &acc.data));
                }
            }
        }
    }
    let miners = chain
        .rpc
        .get_program_accounts(
            &ORE_PROGRAM_ID,
            &[
                Filter::DataSize(752),
                Filter::Memcmp { offset: 0, bytes: vec![103, 0, 0, 0, 0, 0, 0, 0] },
                Filter::Memcmp { offset: 664, bytes: round_id.to_le_bytes().to_vec() },
            ],
        )
        .await
        .map_err(|e| anyhow!("getProgramAccounts(Miner): {e}"))?;
    for (addr, acc) in miners {
        if holds(&acc.data) {
            return Ok(found(addr, &acc.data));
        }
    }
    bail!("round {round_id}: no Miner holds sample {sample} of solo square {ws}")
}

fn bg_mask(round_id: u64) -> u32 {
    let h = ore::keccak(&[b"hd-devstack background", &round_id.to_le_bytes()]);
    let m = u32::from_le_bytes([h[0], h[1], h[2], h[3]]) & 0x01FF_FFFF;
    if m == 0 {
        1
    } else {
        m
    }
}

async fn background_deploy(chain: &Chain, bg: &Keypair, round_id: u64, lamports: u64) -> Result<String> {
    let me = bg.pubkey();
    let mut ixs: Vec<Instruction> = Vec::new();
    if let Some((o, d, _)) = chain.data(&core::miner_pda(&me)).await? {
        let m = Miner::decode(&o, &d).map_err(|e| anyhow!("background Miner: {e}"))?;
        if m.round_id != round_id && m.needs_checkpoint() {
            ixs.push(core::checkpoint_ix(&me, &me, m.round_id));
        }
    }
    let mask = bg_mask(round_id);
    ixs.push(ore::manual_deploy_ix(&me, round_id, lamports, mask));
    let l = chain.send(bg, &ixs).await?;
    Ok(format!("{} squares x {lamports} lamports (tx {})", mask.count_ones(), l.signature))
}

/// Run the driver loop.
pub async fn run(o: DriverOpts) -> Result<()> {
    let chain = Chain::new(&o.rpc)?;
    let payer = read_keypair(&o.payer)?;
    let bg = o.background.as_ref().map(|p| read_keypair(p)).transpose()?;
    let hash_chain = HashChain::load(&o.entropy_secret)?;
    say!(
        "ore-round-driver: payer {} background miner {}",
        payer.pubkey(),
        bg.as_ref().map_or("off".to_string(), |k| k.pubkey().to_string())
    );
    let mut started_round: Option<u64> = None;
    let mut resets = 0u64;
    let mut last_err = String::new();
    loop {
        let step = async {
            // Keep the driver's own keys funded (local airdrops; retried like everything else).
            for k in std::iter::once(&payer).chain(bg.iter()) {
                if chain.balance(&k.pubkey()).await? < SOL {
                    chain.airdrop(&k.pubkey(), 100 * SOL).await?;
                    say!("funded {} with 100 SOL (local airdrop)", k.pubkey());
                }
            }
            let s = snapshot(&chain).await?;
            let b = s.board;
            if !b.started() {
                if let Some(bg) = &bg {
                    if started_round != Some(b.round_id) && s.slot >= b.start_slot.saturating_add(o.start_delay_slots) {
                        let what = background_deploy(&chain, bg, b.round_id, o.background_lamports).await?;
                        started_round = Some(b.round_id);
                        say!("round {} started by the background miner: {what}", b.round_id);
                    }
                }
                return Ok::<bool, anyhow::Error>(false);
            }
            if s.slot < b.end_slot {
                return Ok(false);
            }
            let slot_hash = b32_at(&s.var, var::SLOT_HASH);
            let value = b32_at(&s.var, var::VALUE);
            if value == [0; 32] {
                let commit = b32_at(&s.var, var::COMMIT);
                let seed = hash_chain.preimage_of(&commit).ok_or_else(|| {
                    anyhow!("Var commit {} is not on the local entropy chain (was genesis built with another secret?)", hex::encode(commit))
                })?;
                let mut ixs = Vec::new();
                if slot_hash == [0; 32] {
                    ixs.push(ore::entropy_sample_ix(&payer.pubkey()));
                }
                ixs.push(ore::entropy_reveal_ix(&payer.pubkey(), &seed));
                let l = chain.send(&payer, &ixs).await?;
                say!(
                    "round {} ended at slot {}: entropy sampled + revealed (tx {}, {} reveals left)",
                    b.round_id,
                    b.end_slot,
                    l.signature,
                    hash_chain.remaining(&seed).unwrap_or(0)
                );
                return Ok(false);
            }
            let reset_at = b.end_slot.saturating_add(s.cfg.intermission_slots);
            if s.slot < reset_at {
                return Ok(false);
            }
            let known: Vec<Address> = bg.iter().map(|k| k.pubkey()).collect();
            let (top, why) = top_miner(&chain, b.round_id, &value, &known).await?;
            let l = chain.send(&payer, &[ore::reset_ix(&payer.pubkey(), b.round_id, &top)]).await?;
            let (o2, d2, _) = chain.data(&BOARD_ADDRESS).await?.ok_or_else(|| anyhow!("Board"))?;
            let nb = Board::decode(&o2, &d2).map_err(|e| anyhow!("Board: {e}"))?;
            say!(
                "round {} reset at slot {}: {why}; next round {} (ema {} lamports/ORE) tx {}",
                b.round_id,
                l.slot,
                nb.round_id,
                nb.production_cost_ema,
                l.signature
            );
            Ok(true)
        };
        match step.await {
            Ok(true) => {
                resets += 1;
                last_err.clear();
                if o.rounds.is_some_and(|n| resets >= n) {
                    return Ok(());
                }
            }
            Ok(false) => {}
            Err(e) => {
                let msg = format!("{e:#}");
                if msg != last_err {
                    say!("driver: {msg}");
                    last_err = msg;
                }
                tokio::time::sleep(Duration::from_secs(2)).await;
            }
        }
        tokio::time::sleep(Duration::from_millis(400)).await;
    }
}
