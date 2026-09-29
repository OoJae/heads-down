//! Fork surgery: the minimal account rewrites that make mainnet ORE state runnable on a
//! local fork. Applied either as genesis `--account` files (`solana-test-validator`, command
//! `genesis`) or live through Surfpool's `surfnet_setAccount` cheatcode (command `surgery`).
//! Everything else is byte-for-byte mainnet, and every program runs its mainnet bytecode.
//!
//! | Account | Rewrite | Why |
//! |---|---|---|
//! | Board | `start_slot = 0`, `end_slot = u64::MAX` (+ `round_id` moved far ahead on Surfpool) | mainnet's window is relative to mainnet's slot; `u64::MAX` = "waiting for the round's first deploy", exactly what ORE's own `reset` writes. On Surfpool the id moves 10,000,000 ahead so the lazily-cloned `["round", id + 1]` PDAs are empty (mainnet keeps creating its own) |
//! | Round `Board.round_id` | fresh (no deploys, `expires_at = u64::MAX`), rent-exempt | mainnet's in-flight round holds other miners' SOL; its top miner is unknowable locally and `reset` panics on a wrong one |
//! | entropy Var | last revealed `seed` = head of our hash chain; `end_at = start_at = 0`; `samples` large | lets the round driver `reveal` (see `entropy.rs`); nobody outside ORE holds mainnet's provider seeds |
//! | ORE-mint Authority | `last_mint_at = 0` | the mint program enforces 150 slots between mints against mainnet's slot |
//! | ORE Config (optional) | `round_slots` / `intermission_slots` | only when explicitly overridden; default keeps mainnet's 240 / 48 |

use std::collections::HashMap;
use std::path::{Path, PathBuf};

use anyhow::{anyhow, bail, Context, Result};
use base64::Engine;
use serde_json::{json, Value};
use solana_address::Address;

use crate::entropy::HashChain;
use crate::ore::{self, var};
use crate::util::{put32, put_u64, rent_exempt, u64_at, Chain};
use hd_crank::ore::{BOARD_ADDRESS, CONFIG_ADDRESS, ORE_PROGRAM_ID, TREASURY_ADDRESS};

/// Surfpool moves the local round ids this far ahead of mainnet's.
pub const SURFPOOL_ROUND_OFFSET: u64 = 10_000_000;

/// One account.
#[derive(Clone)]
pub struct Dumped {
    /// Address.
    pub address: Address,
    /// Lamports.
    pub lamports: u64,
    /// Owner.
    pub owner: Address,
    /// Data.
    pub data: Vec<u8>,
    /// Executable flag.
    pub executable: bool,
}

/// Read `<dir>/<address>.json` as written by `solana account --output json-compact`.
pub fn read_dump(dir: &Path, address: &Address) -> Result<Dumped> {
    let p = dir.join(format!("{address}.json"));
    let raw = std::fs::read_to_string(&p).with_context(|| format!("missing {} (run fetch-mainnet.sh)", p.display()))?;
    let v: Value = serde_json::from_str(&raw).with_context(|| format!("parse {}", p.display()))?;
    let a = &v["account"];
    let data_b64 = a["data"][0].as_str().ok_or_else(|| anyhow!("{}: no base64 data", p.display()))?;
    Ok(Dumped {
        address: *address,
        lamports: a["lamports"].as_u64().ok_or_else(|| anyhow!("{}: lamports", p.display()))?,
        owner: a["owner"].as_str().ok_or_else(|| anyhow!("{}: owner", p.display()))?.parse().map_err(|_| anyhow!("owner"))?,
        data: base64::engine::general_purpose::STANDARD.decode(data_b64)?,
        executable: a["executable"].as_bool().unwrap_or(false),
    })
}

fn write_account(out: &Path, d: &Dumped) -> Result<PathBuf> {
    let p = out.join(format!("{}.json", d.address));
    let v = json!({
        "pubkey": d.address.to_string(),
        "account": {
            "lamports": d.lamports,
            "data": [base64::engine::general_purpose::STANDARD.encode(&d.data), "base64"],
            "owner": d.owner.to_string(),
            "executable": d.executable,
            "rentEpoch": 0,
            "space": d.data.len(),
        }
    });
    std::fs::write(&p, v.to_string())?;
    Ok(p)
}

/// Timing overrides.
#[derive(Clone, Copy, Default)]
pub struct Timing {
    /// ORE `round_slots`.
    pub round_slots: Option<u64>,
    /// ORE `intermission_slots`.
    pub intermission_slots: Option<u64>,
}

/// The rewrites, given the source accounts (Board, Var, mint Authority, ORE Config).
/// Returns the rewritten accounts (Board, fresh Round, Var, Authority, Config) and a summary.
pub fn rewrite(src: &HashMap<Address, Dumped>, head: [u8; 32], round_offset: u64, t: Timing) -> Result<(Vec<Dumped>, String)> {
    let get = |a: &Address| src.get(a).cloned().ok_or_else(|| anyhow!("source account {a} missing"));
    let mut out = Vec::new();

    let mut board = get(&BOARD_ADDRESS)?;
    if board.owner != ORE_PROGRAM_ID || board.data.len() != 40 {
        bail!("Board is not a 40-byte ORE account");
    }
    let round_id = u64_at(&board.data, 8).checked_add(round_offset).ok_or_else(|| anyhow!("round id overflow"))?;
    put_u64(&mut board.data, 8, round_id)?;
    put_u64(&mut board.data, 16, 0)?;
    put_u64(&mut board.data, 24, u64::MAX)?;
    let ema = u64_at(&board.data, 32);
    out.push(board);

    // A fresh Round account for the Board's round (Steel discriminator 109).
    let mut round = vec![0u8; 952];
    round[0] = 109;
    put_u64(&mut round, 8, round_id)?;
    put_u64(&mut round, 648, u64::MAX)?;
    out.push(Dumped {
        address: hd_crank::ore::round_pda(round_id),
        lamports: rent_exempt(952),
        owner: ORE_PROGRAM_ID,
        data: round,
        executable: false,
    });

    let mut v = get(&ore::VAR_ADDRESS)?;
    if v.owner != ore::ENTROPY_PROGRAM_ID || v.data.len() != var::LEN {
        bail!("Var is not a {}-byte entropy account (owner {}, len {})", var::LEN, v.owner, v.data.len());
    }
    if crate::util::b32_at(&v.data, var::AUTHORITY) != BOARD_ADDRESS.to_bytes() {
        bail!("Var authority is not the ORE Board");
    }
    let slot_hash = ore::keccak(&[b"hd-devstack genesis slot hash"]);
    let samples: u64 = 1_000_000_000;
    put32(&mut v.data, var::COMMIT, &ore::keccak(&[&head]))?;
    put32(&mut v.data, var::SEED, &head)?;
    put32(&mut v.data, var::SLOT_HASH, &slot_hash)?;
    put32(&mut v.data, var::VALUE, &ore::keccak(&[&slot_hash, &head, &samples.to_le_bytes()]))?;
    put_u64(&mut v.data, var::SAMPLES, samples)?;
    put_u64(&mut v.data, var::IS_AUTO, 0)?;
    put_u64(&mut v.data, var::START_AT, 0)?;
    put_u64(&mut v.data, var::END_AT, 0)?;
    out.push(v);

    let mut auth = get(&ore::mint_authority_pda())?;
    if auth.owner != ore::MINT_PROGRAM_ID || auth.data.len() != 16 {
        bail!("mint Authority is not a 16-byte ore-mint account");
    }
    put_u64(&mut auth.data, 8, 0)?;
    out.push(auth);

    let mut cfg = get(&CONFIG_ADDRESS)?;
    if cfg.owner != ORE_PROGRAM_ID || cfg.data.len() != 232 {
        bail!("ORE Config is not a 232-byte ORE account");
    }
    if let Some(r) = t.round_slots {
        put_u64(&mut cfg.data, 160, r)?;
    }
    if let Some(i) = t.intermission_slots {
        put_u64(&mut cfg.data, 152, i)?;
    }
    let (inter, rs) = (u64_at(&cfg.data, 152), u64_at(&cfg.data, 160));
    if rs == 0 || rs + inter < 150 {
        bail!("round_slots + intermission_slots must be >= 150 (the ORE mint's minimum spacing); got {rs} + {inter}");
    }
    out.push(cfg);

    let summary = format!(
        "ORE round {round_id} (fresh, waiting for its first deploy), round_slots {rs}, intermission {inter}, \
         production_cost_ema {ema} lamports/ORE, entropy chain head {}…",
        &hex::encode(head)[..16]
    );
    Ok((out, summary))
}

/// Options for [`genesis`].
pub struct GenesisOpts {
    /// `fetch-mainnet.sh` output directory.
    pub fixtures: PathBuf,
    /// Where to write the rewritten account files.
    pub out: PathBuf,
    /// The entropy secret file.
    pub entropy_secret: PathBuf,
    /// Timing overrides.
    pub timing: Timing,
}

/// `solana-test-validator`: write the genesis account files and `accounts.args`.
pub fn genesis(o: &GenesisOpts) -> Result<()> {
    std::fs::create_dir_all(&o.out)?;
    HashChain::ensure_secret(&o.entropy_secret)?;
    let chain = HashChain::load(&o.entropy_secret)?;
    let mut src = HashMap::new();
    for a in [BOARD_ADDRESS, ore::VAR_ADDRESS, ore::mint_authority_pda(), CONFIG_ADDRESS] {
        src.insert(a, read_dump(&o.fixtures, &a)?);
    }
    let (mut accounts, summary) = rewrite(&src, chain.head(), 0, o.timing)?;
    // Unchanged mainnet accounts that ORE `deploy` / `reset` touch.
    for a in [TREASURY_ADDRESS, ore::MINT_ADDRESS, ore::treasury_tokens(), ore::ADMIN_FEE_COLLECTOR] {
        accounts.push(read_dump(&o.fixtures, &a)?);
    }
    let mut args = String::new();
    for d in &accounts {
        let p = write_account(&o.out, d)?;
        args.push_str(&format!("--account\n{}\n{}\n", d.address, p.display()));
    }
    std::fs::write(o.out.join("accounts.args"), args)?;
    let pot = u64_at(&read_dump(&o.fixtures, &TREASURY_ADDRESS)?.data, 8);
    println!("genesis: {summary}, motherlode {:.1} ORE, {} accounts", pot as f64 / 1e11, accounts.len());
    Ok(())
}

/// Surfpool: apply the same rewrites live with `surfnet_setAccount` (the accounts are first
/// read through Surfpool, which clones them lazily from mainnet).
pub async fn surgery(rpc: &str, entropy_secret: &Path, timing: Timing) -> Result<()> {
    HashChain::ensure_secret(entropy_secret)?;
    let chain_h = HashChain::load(entropy_secret)?;
    let chain = Chain::new(rpc)?;
    let mut src = HashMap::new();
    for a in [BOARD_ADDRESS, ore::VAR_ADDRESS, ore::mint_authority_pda(), CONFIG_ADDRESS] {
        let (owner, data, lamports) = chain.data(&a).await?.ok_or_else(|| anyhow!("{a} not found through the fork"))?;
        src.insert(a, Dumped { address: a, lamports, owner, data, executable: false });
    }
    // Touch the accounts ORE reset/deploy use so they are cloned now, not mid-round.
    for a in [TREASURY_ADDRESS, ore::MINT_ADDRESS, ore::treasury_tokens(), ore::ADMIN_FEE_COLLECTOR] {
        chain.data(&a).await?.ok_or_else(|| anyhow!("{a} not found through the fork"))?;
    }
    let (accounts, summary) = rewrite(&src, chain_h.head(), SURFPOOL_ROUND_OFFSET, timing)?;
    for d in &accounts {
        chain
            .call(
                "surfnet_setAccount",
                json!([d.address.to_string(), {
                    "lamports": d.lamports,
                    "data": hex::encode(&d.data),
                    "owner": d.owner.to_string(),
                    "executable": d.executable,
                }]),
            )
            .await
            .with_context(|| format!("surfnet_setAccount {}", d.address))?;
        let (_, now, _) = chain.data(&d.address).await?.ok_or_else(|| anyhow!("{} vanished", d.address))?;
        if now != d.data {
            bail!("surfnet_setAccount did not stick for {}", d.address);
        }
    }
    println!("surgery: {summary}, {} accounts rewritten via surfnet_setAccount", accounts.len());
    Ok(())
}
