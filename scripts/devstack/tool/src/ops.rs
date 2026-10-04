//! Deployment operations shared by `scripts/mainnet/*.sh` (and the local dry run):
//!
//! | Command | What | Signs |
//! |---|---|---|
//! | `init` | `initialize_config` + the Executor PDA float (idempotent: re-running tops the float up) | upgrade authority |
//! | `preflight` | read-only go/no-go before a deploy; counts what a half-written buffer already holds | nothing |
//! | `verify-deploy` | checks the deployed bytes against the local `.so` and writes the public receipt | nothing |
//! | `fees` | the transactions an address paid for after a slot, and their fees | nothing |
//! | `propose-config` / `apply-config` | governance; `--paused 1` pauses `dig` immediately | governance / anyone |
//!
//! (`write-buffer` and `buffer-status` are in `buffer.rs`.)
//!
//! Every command runs the cluster guard first (`cluster::connect`). On `--cluster mainnet`
//! nothing is signed without `--yes`: the scripts show the plan and ask before passing it.
//! The RPC URL (it may carry a provider key) is never printed or written to a receipt.

use std::path::PathBuf;
use std::str::FromStr;
use std::time::Duration;

use anyhow::{anyhow, bail, Context, Result};
use hd_crank::hd::{self, HdConfig};
use hd_crank::ore::{
    check_layout, round_pda, Board, OreConfig, OreKind, Round, Treasury, BOARD_ADDRESS, BPF_UPGRADEABLE_LOADER_ID,
    CONFIG_ADDRESS, ORE_PROGRAM_ID, PINNED_PROGRAMDATA_SLOT, PROGRAMDATA_ADDRESS, TREASURY_ADDRESS,
};
use hd_crank::rpc::redact_url;
use serde_json::{json, Map, Value};
use sha2::{Digest, Sha256};
use solana_address::Address;
use solana_keypair::Keypair;
use solana_signer::Signer;

use crate::buffer::{buffer_rent_len, chunk_count, chunk_len, differing_chunks, judge, read_held, resume_json, resume_line, Resume};
use crate::cluster::{self, Cluster};
use crate::hd as hdix;
use crate::util::{patiently, read_keypair, rfc3339, sol, unix_now, wait_for, Chain, Landed, Patience};

/// SIMD-0500: "Disable deployment of SBPF v0, v1 and v2 programs".
pub const SIMD_0500: Address = Address::from_str_const("B8JJXCy5amZyWG9r7EnUYLwzXSXTxG7GZ1qZ1qggo83g");
/// SIMD-0166: deployment and execution of SBPFv1.
pub const FEATURE_SBPF_V1: Address = Address::from_str_const("JE86WkYvTrzW8HgNmrHY7dFYpCmSptUpKupbo2AdQ9cG");
/// SIMD-0173/0174: deployment and execution of SBPFv2.
pub const FEATURE_SBPF_V2: Address = Address::from_str_const("F6UVKh1ujTEFK3en2SyAL3cdVnqko1FVEXWhmdLRu6WP");
/// SIMD-0178/0189/0377: deployment and execution of SBPFv3.
pub const FEATURE_SBPF_V3: Address = Address::from_str_const("5cC3foj77CWun58pC51ebHFUWavHWKarWyR5UUik7dnC");
/// Owner of feature accounts.
pub const FEATURE_PROGRAM_ID: Address = Address::from_str_const("Feature111111111111111111111111111111111111");
/// sha256 of ORE's deployed program bytes, trailing zeros stripped (verify.osec.io's "on-chain
/// hash" for commit `48c203bd`, docs/ORE.md §1). Pinned together with the ProgramData slot.
pub const ORE_PROGRAM_HASH: &str = "9dbd2e0d232563f0e2b3eae89bf7d6f55d483c464863adb4f46d117f427ca695";
/// Upgradeable-loader Program account (`u32 tag | programdata`).
pub const PROGRAM_ACCOUNT_LEN: u64 = 36;
/// Upgradeable-loader ProgramData header (`u32 tag | u64 slot | Option<Pubkey>`).
pub const PROGRAMDATA_HEADER_LEN: u64 = 45;
/// Upgradeable-loader Buffer header (`u32 tag | Option<Pubkey>`).
pub const BUFFER_HEADER_LEN: u64 = 37;
/// Runtime cap on account data (10 MiB).
pub const MAX_PROGRAM_LEN: u64 = 10 * 1024 * 1024;
/// The loader's smallest `ExtendProgram` (SIMD-0431; feature
/// `YbbRLkvenrocjGPGyoQE4wjnvYzTgfsk38NFmcYK7a5`, active on mainnet since slot 432,864,000).
pub const MIN_EXTEND_BYTES: u64 = 10_240;
/// Transactions the fee budget keeps in hand on top of the writes: the buffer's creation, the
/// final deploy or upgrade, an extension or a hand-over, and one to spare (the `+ 4` of
/// `deploy_fee_budget` in `scripts/mainnet/lib.sh`).
pub const FEE_BUDGET_SPARE_TXS: u64 = 4;
/// heads_down Config size (`programs/heads-down/program/src/state.rs` asserts 256).
pub const CONFIG_LEN: u64 = std::mem::size_of::<heads_down::state::Config>() as u64;
/// What the program keeps in the Executor above rent for later `CHECKPOINT_FEE` top-ups
/// (`dig.rs`: `10 * CHECKPOINT_FEE`); reimbursements never dip into it.
pub const EXECUTOR_RESERVE: u64 = heads_down::instructions::dig::EXECUTOR_RESERVE;
/// `propose_config` → `apply_config` delay in slots.
pub const TIMELOCK_SLOTS: u64 = heads_down::instructions::governance::TIMELOCK_SLOTS;
/// Lamports budgeted for the `initialize_config` + float transactions.
pub const INIT_FEE_BUDGET: u64 = 100_000;

// ---- small helpers --------------------------------------------------------------------------

fn sha256_hex(b: &[u8]) -> String {
    hex::encode(Sha256::digest(b))
}

fn strip_trailing_zeros(b: &[u8]) -> &[u8] {
    let end = b.iter().rposition(|&x| x != 0).map_or(0, |i| i + 1);
    &b[..end]
}

/// What we know about a built `.so`.
#[derive(Clone, Debug)]
pub struct SoInfo {
    /// File length.
    pub len: u64,
    /// sha256 of the file.
    pub sha256: String,
    /// sha256 with trailing zero bytes stripped: what `solana-verify get-program-hash` prints
    /// for the deployed program (ProgramData pads the program with zeros up to max-len).
    pub program_hash: String,
    /// SBPF version from the ELF `e_flags` (0 = v0 … 4 = v4), `None` if unknown.
    pub sbpf: Option<u8>,
}

/// Parse the parts of an SBF ELF the deploy checks rely on.
pub fn so_info(bytes: &[u8]) -> Result<SoInfo> {
    if bytes.len() < 64 || bytes.get(0..4) != Some(b"\x7fELF".as_slice()) {
        bail!("not an ELF file");
    }
    if bytes.get(4) != Some(&2) || bytes.get(5) != Some(&1) {
        bail!("not a 64-bit little-endian ELF");
    }
    let e_flags = bytes.get(48..52).and_then(|s| s.try_into().ok()).map(u32::from_le_bytes).unwrap_or(u32::MAX);
    let sbpf = u8::try_from(e_flags).ok().filter(|v| *v <= 4);
    Ok(SoInfo {
        len: bytes.len() as u64,
        sha256: sha256_hex(bytes),
        program_hash: sha256_hex(strip_trailing_zeros(bytes)),
        sbpf,
    })
}

fn sbpf_name(v: Option<u8>) -> String {
    v.map_or_else(|| "unknown".to_string(), |v| format!("v{v}"))
}

/// A feature gate's state.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum FeatureState {
    /// No feature account: not scheduled.
    Inactive,
    /// The account exists but is not activated yet: it activates at the next epoch boundary.
    Pending,
    /// Activated at this slot.
    Active(u64),
}

impl FeatureState {
    fn label(self) -> String {
        match self {
            FeatureState::Inactive => "inactive".into(),
            FeatureState::Pending => "PENDING (activates at the next epoch boundary)".into(),
            FeatureState::Active(s) => format!("active since slot {s}"),
        }
    }
    fn is_active(self) -> bool {
        matches!(self, FeatureState::Active(_))
    }
}

/// Read a feature account (`Feature { activated_at: Option<u64> }`, bincode).
pub async fn feature_state(chain: &Chain, id: &Address) -> Result<FeatureState> {
    match chain.data(id).await? {
        None => Ok(FeatureState::Inactive),
        Some((owner, data, _)) => {
            if owner != FEATURE_PROGRAM_ID {
                bail!("feature account {id} is owned by {owner}, not the feature program");
            }
            match data.first() {
                Some(1) => {
                    let slot = data.get(1..9).and_then(|s| s.try_into().ok()).map(u64::from_le_bytes).unwrap_or(0);
                    Ok(FeatureState::Active(slot))
                }
                _ => Ok(FeatureState::Pending),
            }
        }
    }
}

/// (owner, the first bytes, lamports, full data length) of an account.
type AccountHead = (Address, Vec<u8>, u64, u64);

/// Owner, first bytes, lamports and full length of an account, without downloading it.
async fn account_head(chain: &Chain, key: &Address, len: usize) -> Result<Option<AccountHead>> {
    let v = chain
        .call(
            "getAccountInfo",
            json!([key.to_string(), { "encoding": "base64", "commitment": "confirmed", "dataSlice": { "offset": 0, "length": len } }]),
        )
        .await?;
    head_from_json(&v["value"])
}

fn head_from_json(v: &Value) -> Result<Option<AccountHead>> {
    use base64::Engine;
    if v.is_null() {
        return Ok(None);
    }
    let owner: Address = v["owner"].as_str().and_then(|s| s.parse().ok()).ok_or_else(|| anyhow!("account owner"))?;
    let lamports = v["lamports"].as_u64().ok_or_else(|| anyhow!("account lamports"))?;
    let data = base64::engine::general_purpose::STANDARD.decode(v["data"][0].as_str().unwrap_or(""))?;
    let space = v["space"].as_u64().ok_or_else(|| anyhow!("the RPC did not report the account's space"))?;
    Ok(Some((owner, data, lamports, space)))
}

/// An upgradeable program as the loader sees it.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum ProgramState {
    /// Nothing at the program id: a fresh deploy.
    Absent,
    /// Deployed and executable.
    Deployed {
        /// ProgramData address.
        programdata: Address,
        /// Slot of the last deploy or upgrade.
        slot: u64,
        /// Upgrade authority (`None` = immutable).
        authority: Option<Address>,
        /// ProgramData account length (header + max-len).
        programdata_len: u64,
        /// ProgramData lamports.
        programdata_lamports: u64,
    },
    /// Something else lives there (not an upgradeable program, or a closed one).
    Other(String),
}

/// Inspect `program`.
pub async fn program_state(chain: &Chain, program: &Address) -> Result<ProgramState> {
    let Some((owner, data, _, _)) = account_head(chain, program, PROGRAM_ACCOUNT_LEN as usize).await? else {
        return Ok(ProgramState::Absent);
    };
    if owner != BPF_UPGRADEABLE_LOADER_ID {
        return Ok(ProgramState::Other(format!("account owned by {owner}")));
    }
    if data.get(0..4) != Some([2u8, 0, 0, 0].as_slice()) {
        return Ok(ProgramState::Other("not an upgradeable Program account".into()));
    }
    let pd_bytes: [u8; 32] = data.get(4..36).and_then(|s| s.try_into().ok()).ok_or_else(|| anyhow!("short Program account"))?;
    let programdata = Address::new_from_array(pd_bytes);
    let Some((pd_owner, head, lamports, space)) = account_head(chain, &programdata, PROGRAMDATA_HEADER_LEN as usize).await? else {
        return Ok(ProgramState::Other(format!("ProgramData {programdata} is missing (the program was closed)")));
    };
    if pd_owner != BPF_UPGRADEABLE_LOADER_ID || head.get(0..4) != Some([3u8, 0, 0, 0].as_slice()) {
        return Ok(ProgramState::Other(format!("{programdata} is not a ProgramData account")));
    }
    let slot = head.get(4..12).and_then(|s| s.try_into().ok()).map(u64::from_le_bytes).unwrap_or(0);
    let authority = match head.get(12) {
        Some(1) => head.get(13..45).and_then(|s| <[u8; 32]>::try_from(s).ok()).map(Address::new_from_array),
        _ => None,
    };
    Ok(ProgramState::Deployed { programdata, slot, authority, programdata_len: space, programdata_lamports: lamports })
}

/// The heads_down Config, raw and decoded.
async fn read_config(chain: &Chain) -> Result<Option<(HdConfig, hdix::Pending)>> {
    match chain.data(&hdix::config()).await? {
        None => Ok(None),
        Some((owner, data, _)) => {
            let c = HdConfig::decode(&hd::PROGRAM_ID, &owner, &data).map_err(|e| anyhow!("Config: {e}"))?;
            let p = hdix::pending(&data).ok_or_else(|| anyhow!("Config: pending fields"))?;
            Ok(Some((c, p)))
        }
    }
}

/// Default Executor float: rent-exempt(0) + the program's reserve (10 × CHECKPOINT_FEE)
/// + `reserve_digs` crank reimbursements.
pub fn default_float(rent0: u64, crank_fee: u64, reserve_digs: u64) -> u64 {
    rent0.saturating_add(EXECUTOR_RESERVE).saturating_add(crank_fee.saturating_mul(reserve_digs))
}

/// Bytes an upgrade adds to a ProgramData of `current_len` so that it holds `needed_len`: none
/// when it fits, otherwise the shortfall or the loader's minimum of 10,240, whichever is larger.
/// Within 10,240 bytes of the 10 MiB account limit the loader takes exactly the room that is
/// left. This is what the CLI's `extend_program_data_if_needed` asks for.
pub fn extend_bytes(current_len: u64, needed_len: u64) -> u64 {
    if needed_len <= current_len {
        return 0;
    }
    let headroom = MAX_PROGRAM_LEN.saturating_sub(current_len);
    (needed_len - current_len).max(MIN_EXTEND_BYTES.min(headroom))
}

/// The fee budget of one deploy. `full` is what the Solana CLI wants to see in the payer for
/// every write it would send itself (`deploy_fee_budget` in `scripts/mainnet/lib.sh`). A
/// buffer that exists already needs the budget of the chunks still to write only, at `per_tx`
/// each, and never more than `full`.
pub fn fee_budget_for(full: u64, per_tx: Option<u64>, buffer_exists: bool, to_write: u64) -> u64 {
    match per_tx {
        Some(per_tx) if buffer_exists => full.min(per_tx.saturating_mul(to_write.saturating_add(FEE_BUDGET_SPARE_TXS))),
        _ => full,
    }
}

/// Lamports a deploy takes out of the deployer, initialization aside. `new_buffer` is what a
/// new buffer is created with in this mode and `held` what the deploy's buffer holds if it
/// exists already; that is never asked for a second time.
///
/// * fresh: the buffer is created holding the ProgramData rent, and the final transaction
///   drains it into the payer, who pays for the ProgramData with it. The rent leaves the
///   deployer once; an existing buffer leaves only what it lacks to add.
/// * upgrade and buffer: a new buffer's lamports, or nothing for one that exists.
pub fn deploy_lamports(mode: DeployMode, programdata: u64, program: u64, new_buffer: u64, held: Option<u64>, grow: u64, fee_budget: u64) -> u64 {
    let buffer = if held.is_some() { 0 } else { new_buffer };
    match mode {
        DeployMode::Fresh => programdata.saturating_sub(held.unwrap_or(0)).saturating_add(program).saturating_add(fee_budget),
        DeployMode::Upgrade => buffer.saturating_add(grow).saturating_add(fee_budget),
        DeployMode::Buffer => buffer.saturating_add(fee_budget),
    }
}

pub(crate) fn write_json(path: &PathBuf, v: &Value) -> Result<()> {
    if let Some(dir) = path.parent() {
        if !dir.as_os_str().is_empty() {
            std::fs::create_dir_all(dir)?;
        }
    }
    let mut s = serde_json::to_string_pretty(v)?;
    s.push('\n');
    std::fs::write(path, s).with_context(|| format!("write {}", path.display()))
}

pub(crate) fn require_yes(cluster: Cluster, yes: bool, what: &str) -> Result<()> {
    if cluster.is_mainnet() && !yes {
        bail!("mainnet: {what} was NOT sent. Review the plan above, then re-run with --yes (the scripts ask first)");
    }
    Ok(())
}

// ---- init ----------------------------------------------------------------------------------

/// Options for [`init`].
pub struct InitOpts {
    /// Target cluster.
    pub cluster: Cluster,
    /// JSON-RPC URL.
    pub rpc: String,
    /// heads_down upgrade authority keypair (it signs and pays rent).
    pub authority: PathBuf,
    /// `Config.governance`.
    pub governance: Address,
    /// `Config.registrar`.
    pub registrar: Address,
    /// `Config.executor_fee` (immutable once written).
    pub executor_fee: u64,
    /// `Config.crank_fee` (≤ executor_fee).
    pub crank_fee: u64,
    /// `Config.bury_bps`.
    pub bury_bps: u16,
    /// Executor float target in lamports (default: [`default_float`]).
    pub executor_float: Option<u64>,
    /// Reimbursements the default float covers on top of the program's reserve.
    pub crank_reserve_digs: u64,
    /// Priority fee, micro-lamports per CU.
    pub cu_price: u64,
    /// Mainnet: actually send.
    pub yes: bool,
    /// Write a JSON receipt here.
    pub receipt: Option<PathBuf>,
}

/// Create the heads_down Config (idempotent) and fund the Executor PDA float up to its target.
pub async fn init(o: InitOpts) -> Result<()> {
    let (chain, genesis) = cluster::connect(o.cluster, &o.rpc).await?;
    let auth = read_keypair(&o.authority)?;
    if o.executor_fee == 0 {
        bail!("executor_fee must be > 0: dig skips every Automation whose fee is 0 (StrategyMismatch)");
    }
    if o.crank_fee > o.executor_fee {
        bail!("crank_fee {} > executor_fee {}: the program refuses it", o.crank_fee, o.executor_fee);
    }
    if o.bury_bps > 10_000 {
        bail!("bury_bps {} > 10000", o.bury_bps);
    }
    // The signer must be the upgrade authority recorded in ProgramData (the program checks it
    // too; checking first gives a clear message instead of `Unauthorized`).
    let deploy_slot = match program_state(&chain, &hd::PROGRAM_ID).await? {
        ProgramState::Deployed { authority: Some(a), slot, .. } if a == auth.pubkey() => slot,
        ProgramState::Deployed { authority: Some(a), .. } => {
            bail!("the upgrade authority is {a}, not the signer {}: only it can initialize the Config", auth.pubkey())
        }
        ProgramState::Deployed { authority: None, .. } => bail!("heads_down is immutable: initialize_config cannot run"),
        ProgramState::Absent => bail!("heads_down is not deployed at {} on this cluster", hd::PROGRAM_ID),
        ProgramState::Other(why) => bail!("{}: {why}", hd::PROGRAM_ID),
    };
    // A program becomes callable in the slot after its deploy; right after deploy.sh the
    // cluster (or a load-balanced RPC node behind it) may not be there yet.
    let callable = deploy_slot.saturating_add(2);
    wait_for("heads_down to become callable", Duration::from_secs(60), Duration::from_millis(400), || async {
        Ok((chain.slot().await? >= callable).then_some(()))
    })
    .await?;
    let rent0 = chain.rent(0).await?;
    let float_target = o.executor_float.unwrap_or_else(|| default_float(rent0, o.crank_fee, o.crank_reserve_digs));
    let layout = heads_down::ore::layout_hash();
    let mut receipt = json!({
        "schema": "heads-down/init-receipt/v1",
        "cluster": o.cluster.name(),
        "genesis_hash": genesis,
        "program_id": hd::PROGRAM_ID.to_string(),
        "config": hdix::config().to_string(),
        "executor_pda": hdix::executor().to_string(),
        "upgrade_authority": auth.pubkey().to_string(),
        "params": {
            "governance": o.governance.to_string(),
            "registrar": o.registrar.to_string(),
            "executor_fee": o.executor_fee,
            "crank_fee": o.crank_fee,
            "bury_bps": o.bury_bps,
            "ore_layout_hash": hex::encode(layout),
        },
    });

    match read_config(&chain).await? {
        Some((c, _)) => {
            println!("init: Config {} already exists (executor_fee {}, crank_fee {})", hdix::config(), c.executor_fee, c.crank_fee);
            let mismatches: Vec<String> = [
                (c.governance != o.governance).then(|| format!("governance {} (asked {})", c.governance, o.governance)),
                (c.registrar != o.registrar).then(|| format!("registrar {} (asked {})", c.registrar, o.registrar)),
                (c.executor_fee != o.executor_fee).then(|| format!("executor_fee {} (asked {})", c.executor_fee, o.executor_fee)),
                (c.crank_fee != o.crank_fee).then(|| format!("crank_fee {} (asked {})", c.crank_fee, o.crank_fee)),
                (c.bury_bps != o.bury_bps).then(|| format!("bury_bps {} (asked {})", c.bury_bps, o.bury_bps)),
            ]
            .into_iter()
            .flatten()
            .collect();
            for m in &mismatches {
                println!("init: WARNING the existing Config differs: {m} (change it with propose-config; executor_fee is immutable)");
            }
            receipt["config_existed"] = json!(true);
            receipt["mismatches"] = json!(mismatches);
        }
        None => {
            let cfg_rent = chain.rent(CONFIG_LEN).await?;
            println!("init plan ({}):", o.cluster.name());
            println!("  initialize_config  Config {} (rent {} SOL, paid by {})", hdix::config(), sol(cfg_rent), auth.pubkey());
            println!("  governance         {}", o.governance);
            println!("  registrar          {}", o.registrar);
            println!("  executor_fee       {} lamports (immutable; every rig's Automation must use exactly this fee)", o.executor_fee);
            println!("  crank_fee          {} lamports (reimbursed per real dig; <= executor_fee)", o.crank_fee);
            println!("  bury_bps           {}", o.bury_bps);
            println!("  ore_layout_hash    {}", hex::encode(layout));
            require_yes(o.cluster, o.yes, "initialize_config")?;
            let ix = hdix::initialize_config_ix(&auth.pubkey(), &o.governance, &o.registrar, o.crank_fee, o.executor_fee, o.bury_bps);
            let l = send_when_deployed(&chain, &auth, &ix, o.cu_price).await?;
            let (c, _) = read_config(&chain).await?.ok_or_else(|| anyhow!("Config missing after initialize_config"))?;
            let ok = c.governance == o.governance
                && c.registrar == o.registrar
                && c.executor_fee == o.executor_fee
                && c.crank_fee == o.crank_fee
                && c.bury_bps == o.bury_bps
                && !c.paused
                && c.ore_layout_hash == layout
                && c.executor_bump == 249;
            if !ok {
                bail!("Config after initialize_config does not match what was sent: {c:?}");
            }
            println!(
                "init: initialize_config tx {} -> Config {} (executor_fee {}, crank_fee {}, governance {}, registrar {}); read back and verified",
                l.signature,
                hdix::config(),
                o.executor_fee,
                o.crank_fee,
                o.governance,
                o.registrar
            );
            receipt["config_existed"] = json!(false);
            receipt["initialize_config"] = landed_json(&chain, &l).await;
        }
    }

    let ex = hdix::executor();
    let have = chain.balance(&ex).await?;
    receipt["executor_float_target"] = json!(float_target);
    if have < float_target {
        let top_up = float_target - have;
        let why = if o.executor_float.is_none() {
            format!("= rent-exempt(0) {rent0} + reserve {EXECUTOR_RESERVE} + {} x crank_fee", o.crank_reserve_digs)
        } else {
            "(--executor-float)".to_string()
        };
        println!("init: Executor PDA {ex} holds {have} lamports; target {float_target} {why}");
        require_yes(o.cluster, o.yes, "the Executor float transfer")?;
        let ix = hd_crank::tx::system_transfer(&auth.pubkey(), &ex, top_up);
        let l = chain.send_budgeted(&auth, &[ix], o.cu_price).await?;
        println!("init: funded the Executor PDA {ex} with {top_up} lamports to {float_target} (tx {})", l.signature);
        receipt["executor_float_transfer"] = landed_json(&chain, &l).await;
    } else {
        println!("init: Executor PDA {ex} holds {have} lamports (target {float_target})");
    }
    receipt["executor_balance"] = json!(chain.balance(&ex).await?);
    receipt["authority_balance_after"] = json!(chain.balance(&auth.pubkey()).await?);
    let now = unix_now();
    receipt["unix_time"] = json!(now);
    receipt["recorded_at"] = json!(rfc3339(now));
    if let Some(p) = &o.receipt {
        write_json(p, &receipt)?;
        println!("init: receipt {}", p.display());
    }
    Ok(())
}

/// `send_budgeted`, retried while a simulation still reports the program as not deployed (an
/// RPC node that has not reached the slot after the deploy yet). Any other error is final.
async fn send_when_deployed(chain: &Chain, payer: &Keypair, ix: &solana_instruction::Instruction, cu_price: u64) -> Result<Landed> {
    let mut attempt = 0;
    loop {
        match chain.send_budgeted(payer, std::slice::from_ref(ix), cu_price).await {
            Err(e) if attempt < 10 && e.to_string().contains("Program is not deployed") => {
                attempt += 1;
                tokio::time::sleep(Duration::from_secs(2)).await;
            }
            other => return other,
        }
    }
}

async fn landed_json(chain: &Chain, l: &Landed) -> Value {
    let fee = chain.tx_fee(&l.signature).await.map(|(_, f, _)| f).ok();
    json!({ "signature": l.signature, "slot": l.slot, "fee_lamports": fee })
}

// ---- preflight -----------------------------------------------------------------------------

/// What `deploy.sh` is about to do.
#[derive(Clone, Copy, Debug, PartialEq, Eq, clap::ValueEnum)]
pub enum DeployMode {
    /// First deploy: nothing may exist at the program id.
    Fresh,
    /// Upgrade in place, signed by the deployer as upgrade authority.
    Upgrade,
    /// Write a buffer only (for a Squads upgrade proposal).
    Buffer,
}

/// A key the operator must fund: `label=PUBKEY[:MIN_LAMPORTS]`.
#[derive(Clone, Debug)]
pub struct KeyNeed {
    /// Role.
    pub label: String,
    /// Address.
    pub pubkey: Address,
    /// Recommended minimum balance.
    pub min: u64,
}

impl FromStr for KeyNeed {
    type Err = String;
    fn from_str(s: &str) -> Result<Self, String> {
        let (label, rest) = s.split_once('=').ok_or("expected label=PUBKEY[:MIN_LAMPORTS]")?;
        let (pk, min) = match rest.split_once(':') {
            Some((pk, m)) => (pk, m.parse::<u64>().map_err(|_| "MIN_LAMPORTS must be an integer")?),
            None => (rest, 0),
        };
        let pubkey = pk.parse::<Address>().map_err(|_| format!("{pk} is not a base58 address"))?;
        Ok(KeyNeed { label: label.to_string(), pubkey, min })
    }
}

/// Options for [`preflight`].
pub struct PreflightOpts {
    /// Target cluster.
    pub cluster: Cluster,
    /// JSON-RPC URL.
    pub rpc: String,
    /// The built program.
    pub so: PathBuf,
    /// `--max-len` for the deploy.
    pub max_len: u64,
    /// Pubkey of the program keypair.
    pub program_id: Address,
    /// Deployer (fee payer and upgrade authority).
    pub deployer: Address,
    /// Fresh deploy, upgrade or buffer.
    pub mode: DeployMode,
    /// `Config.executor_fee` init-config will use.
    pub executor_fee: u64,
    /// `Config.crank_fee` init-config will use.
    pub crank_fee: u64,
    /// Executor float target (default: [`default_float`]).
    pub executor_float: Option<u64>,
    /// Reimbursements the default float covers.
    pub crank_reserve_digs: u64,
    /// Lamports budgeted for deploy transaction fees.
    pub fee_budget: u64,
    /// The fee one transaction is budgeted at. With it, a deploy that continues an existing
    /// buffer is budgeted for the chunks still to write only.
    pub fee_per_tx: Option<u64>,
    /// The deploy's per-commit buffer address. When that account exists it is checked, and
    /// the lamports and chunks it already holds are counted.
    pub buffer: Option<Address>,
    /// Other keys to report (crank payer, governance, …).
    pub keys: Vec<KeyNeed>,
    /// Accept a scheduled (not yet active) SIMD-0500 for a v0 build.
    pub allow_simd0500_pending: bool,
    /// Recent ORE transactions to sample Automation / Miner layouts from.
    pub sample_txs: usize,
    /// Write every measured value here.
    pub json: Option<PathBuf>,
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum St {
    Pass,
    Warn,
    Fail,
    Info,
}

struct Report {
    fails: usize,
    warns: usize,
    checks: Vec<Value>,
}

impl Report {
    fn add(&mut self, st: St, check: &str, detail: impl Into<String>) {
        let detail = detail.into();
        let tag = match st {
            St::Pass => "PASS",
            St::Warn => {
                self.warns += 1;
                "WARN"
            }
            St::Fail => {
                self.fails += 1;
                "FAIL"
            }
            St::Info => "INFO",
        };
        println!("{tag}  {check:<22} {detail}");
        self.checks.push(json!({ "status": tag, "check": check, "detail": detail }));
    }
}

/// Read-only go/no-go before a deploy. Returns `true` for GO (no FAIL).
pub async fn preflight(o: PreflightOpts) -> Result<bool> {
    let mut r = Report { fails: 0, warns: 0, checks: vec![] };
    let mut out = Map::new();
    out.insert("schema".into(), json!("heads-down/preflight/v1"));
    out.insert("cluster".into(), json!(o.cluster.name()));
    out.insert("mode".into(), json!(format!("{:?}", o.mode).to_lowercase()));

    // 1. The cluster itself.
    let (chain, genesis) = match cluster::connect(o.cluster, &o.rpc).await {
        Ok(c) => c,
        Err(e) => {
            r.add(St::Fail, "cluster", format!("{e:#}"));
            println!("\nNO-GO: cannot verify the cluster");
            return Ok(false);
        }
    };
    r.add(St::Pass, "cluster", format!("{} via {} (genesis {genesis})", o.cluster.name(), redact_url(&o.rpc)));
    out.insert("genesis_hash".into(), json!(genesis));
    let epoch = chain.call("getEpochInfo", json!([{ "commitment": "confirmed" }])).await?;
    let slot = epoch["absoluteSlot"].as_u64().unwrap_or(0);
    let slots_to_epoch = epoch["slotsInEpoch"].as_u64().unwrap_or(0).saturating_sub(epoch["slotIndex"].as_u64().unwrap_or(0));
    out.insert("slot".into(), json!(slot));
    out.insert("epoch".into(), json!(epoch["epoch"]));

    // 2. Program id: the keypair, the crank's constant and the program crate agree.
    let crate_id = Address::new_from_array(*heads_down::ID.as_array());
    if o.program_id == crate_id && o.program_id == hd::PROGRAM_ID {
        r.add(St::Pass, "program id", format!("{} (keypair = program crate = crank)", o.program_id));
    } else {
        r.add(St::Fail, "program id", format!("keypair {} but the program is built for {crate_id}", o.program_id));
    }
    out.insert("program_id".into(), json!(o.program_id.to_string()));

    // 3. The build.
    let bytes = std::fs::read(&o.so).with_context(|| format!("read {}", o.so.display()))?;
    let so = match so_info(&bytes) {
        Ok(s) => s,
        Err(e) => {
            r.add(St::Fail, "program .so", format!("{}: {e}", o.so.display()));
            println!("\nNO-GO");
            return Ok(false);
        }
    };
    r.add(
        St::Pass,
        "program .so",
        format!("{} bytes, sha256 {}, program hash {}, SBPF {}", so.len, so.sha256, so.program_hash, sbpf_name(so.sbpf)),
    );
    out.insert(
        "so".into(),
        json!({ "path": o.so.display().to_string(), "len": so.len, "sha256": so.sha256, "program_hash": so.program_hash, "sbpf": sbpf_name(so.sbpf) }),
    );
    if so.sbpf.is_none() {
        r.add(St::Fail, "SBPF version", "unknown e_flags: not a Solana SBF build");
    }

    // 4. max-len of a fresh deploy (an upgrade is measured against the deployed ProgramData in 6).
    if o.mode == DeployMode::Fresh {
        if o.max_len < so.len {
            r.add(St::Fail, "max-len", format!("{} < program size {}", o.max_len, so.len));
        } else if o.max_len > MAX_PROGRAM_LEN {
            r.add(St::Fail, "max-len", format!("{} > the 10 MiB account limit", o.max_len));
        } else {
            let head = o.max_len - so.len;
            r.add(
                St::Pass,
                "max-len",
                format!("{} bytes: {} bytes ({}%) of headroom over this build", o.max_len, head, head * 100 / so.len.max(1)),
            );
        }
    }
    out.insert("max_len".into(), json!(o.max_len));

    // 5. SIMD-0500 (no SBPF v0/v1/v2 deployments) against the build's SBPF version.
    let simd = feature_state(&chain, &SIMD_0500).await?;
    let v3 = feature_state(&chain, &FEATURE_SBPF_V3).await?;
    out.insert("features".into(), json!({ "simd_0500": simd.label(), "sbpf_v3": v3.label() }));
    match so.sbpf {
        Some(v) if v >= 3 => {
            if v3.is_active() {
                r.add(St::Pass, "SIMD-0500", format!("{} ; build is SBPF v{v} and SBPFv3 deployment is {}", simd.label(), v3.label()));
            } else {
                r.add(St::Fail, "SIMD-0500", format!("build is SBPF v{v} but SBPFv3 deployment is {}", v3.label()));
            }
        }
        Some(v) => {
            let need = match v {
                1 => Some(FEATURE_SBPF_V1),
                2 => Some(FEATURE_SBPF_V2),
                _ => None,
            };
            if let Some(f) = need {
                let st = feature_state(&chain, &f).await?;
                if !st.is_active() {
                    r.add(St::Fail, "SBPF version", format!("build is SBPF v{v} but its feature {f} is {}", st.label()));
                }
            }
            match simd {
                FeatureState::Inactive => {
                    r.add(St::Pass, "SIMD-0500", format!("inactive: SBPF v{v} deployments are still accepted"))
                }
                FeatureState::Pending if o.allow_simd0500_pending => r.add(
                    St::Warn,
                    "SIMD-0500",
                    format!("PENDING: it activates in ~{slots_to_epoch} slots; after that an SBPF v{v} build can no longer be deployed or upgraded"),
                ),
                FeatureState::Pending => r.add(
                    St::Fail,
                    "SIMD-0500",
                    format!("PENDING: it activates in ~{slots_to_epoch} slots and would refuse this SBPF v{v} build; build --arch v3 (programs/heads-down/scripts/build.sh) or pass --allow-simd0500-pending"),
                ),
                FeatureState::Active(s) => r.add(
                    St::Fail,
                    "SIMD-0500",
                    format!("active since slot {s}: SBPF v{v} deployments are refused; the build must be --arch v3"),
                ),
            }
        }
        None => {}
    }

    // 6. What is at the program id, against the mode.
    let state = program_state(&chain, &o.program_id).await?;
    let (existing_len, existing_lamports) = match &state {
        ProgramState::Deployed { programdata_len, programdata_lamports, .. } => (*programdata_len, *programdata_lamports),
        _ => (0, 0),
    };
    match (&state, o.mode) {
        (ProgramState::Absent, DeployMode::Fresh) => r.add(St::Pass, "program account", "nothing deployed yet: fresh deploy"),
        (ProgramState::Absent, DeployMode::Upgrade) => r.add(St::Fail, "program account", "nothing deployed: use --mode fresh"),
        (ProgramState::Absent, DeployMode::Buffer) => r.add(St::Info, "program account", "nothing deployed yet"),
        (ProgramState::Deployed { slot, authority, programdata_len, .. }, mode) => {
            let who = authority.map_or_else(|| "none (immutable)".to_string(), |a| a.to_string());
            let d = format!("deployed (last deploy slot {slot}, upgrade authority {who}, ProgramData {programdata_len} bytes)");
            match mode {
                DeployMode::Fresh => r.add(St::Fail, "program account", format!("{d}: a fresh deploy is impossible; use --mode upgrade")),
                DeployMode::Upgrade if *authority == Some(o.deployer) => {
                    r.add(St::Pass, "program account", d);
                    let room = programdata_len.saturating_sub(PROGRAMDATA_HEADER_LEN);
                    let add = extend_bytes(*programdata_len, PROGRAMDATA_HEADER_LEN + so.len);
                    if PROGRAMDATA_HEADER_LEN + so.len > MAX_PROGRAM_LEN {
                        r.add(St::Fail, "max-len", format!("the build ({} bytes) is above the 10 MiB account limit", so.len));
                    } else if add == 0 {
                        r.add(
                            St::Pass,
                            "max-len",
                            format!("the deployed ProgramData holds up to {room} bytes: {} bytes ({}%) of headroom over this build", room - so.len, (room - so.len) * 100 / so.len.max(1)),
                        );
                    } else {
                        r.add(
                            St::Warn,
                            "max-len",
                            format!(
                                "the build is {} bytes larger than the {room} the deployed ProgramData holds: the upgrade extends it by {add} bytes (the loader's minimum is {MIN_EXTEND_BYTES}), and their rent stays locked like the rest",
                                so.len - room
                            ),
                        );
                    }
                }
                DeployMode::Upgrade => r.add(
                    St::Fail,
                    "program account",
                    format!("{d}: the deployer {} is not the upgrade authority (a Squads vault upgrades with --mode buffer)", o.deployer),
                ),
                DeployMode::Buffer => r.add(St::Info, "program account", d),
            }
        }
        (ProgramState::Other(why), _) => r.add(St::Fail, "program account", why.clone()),
    }

    // 7. The deploy's buffer. A deploy that stopped part way left one behind, holding the rent
    // that was put into it and the chunks that landed: neither is needed a second time.
    let chunk = chunk_len(&o.deployer)?;
    let chunks_total = chunk_count(bytes.len(), chunk);
    let plan = match &o.buffer {
        Some(addr) => {
            let held = read_held(&chain, addr, Patience::default()).await?;
            let plan = judge(&held, addr, &o.deployer, &bytes, chunk);
            let st = match &plan {
                Resume::Create { .. } => St::Info,
                Resume::Continue { .. } => St::Pass,
                Resume::Refused(_) => St::Fail,
            };
            r.add(st, "buffer", resume_line(addr, &plan, chunks_total));
            out.insert("buffer".into(), resume_json(addr, &held, &plan, chunks_total, chunk));
            plan
        }
        None => {
            let plan = Resume::Create { to_write: differing_chunks(&bytes, &vec![0; bytes.len()], chunk) };
            let n = plan.to_write().map_or(0, <[usize]>::len);
            r.add(St::Info, "buffer", format!("no buffer for this commit yet: the deploy creates one ({n} of {chunks_total} chunks to write)"));
            plan
        }
    };
    let (held_lamports, to_write) = match &plan {
        Resume::Continue { lamports, to_write } => (Some(*lamports), to_write.len() as u64),
        other => (None, other.to_write().map_or(0, <[usize]>::len) as u64),
    };
    let fee_budget = fee_budget_for(o.fee_budget, o.fee_per_tx, held_lamports.is_some(), to_write);
    let fees_for = match held_lamports {
        Some(_) => format!("fees {} for the {to_write} chunks still to write", sol(fee_budget)),
        None => format!("fees {}", sol(fee_budget)),
    };

    // 8. Rent and the deployer's balance, from the cluster's own rent figures.
    let r_pd = chain.rent(PROGRAMDATA_HEADER_LEN + o.max_len).await?;
    let r_prog = chain.rent(PROGRAM_ACCOUNT_LEN).await?;
    let new_buffer_len = buffer_rent_len(o.mode, so.len, o.max_len);
    let r_buf = chain.rent(new_buffer_len).await?;
    let r_cfg = chain.rent(CONFIG_LEN).await?;
    let r0 = chain.rent(0).await?;
    let cfg = read_config(&chain).await?;
    let exec_bal = chain.balance(&hdix::executor()).await?;
    let float = o.executor_float.unwrap_or_else(|| default_float(r0, o.crank_fee, o.crank_reserve_digs));
    let cfg_need = if cfg.is_some() { 0 } else { r_cfg };
    let float_need = float.saturating_sub(exec_bal);
    let init_need = cfg_need.saturating_add(float_need).saturating_add(if cfg.is_some() && float_need == 0 { 0 } else { INIT_FEE_BUDGET });
    // The loader extends a ProgramData by at least MIN_EXTEND_BYTES, and the CLI leaves that
    // payment out of its own balance check: budget the rent of what it will really add.
    let add = if o.mode == DeployMode::Upgrade && existing_len > 0 { extend_bytes(existing_len, PROGRAMDATA_HEADER_LEN + so.len) } else { 0 };
    let grow = if add == 0 { 0 } else { chain.rent(existing_len + add).await?.saturating_sub(existing_lamports) };
    let deploy_need = deploy_lamports(o.mode, r_pd, r_prog, r_buf, held_lamports, grow, fee_budget);
    let peak_note = match o.mode {
        DeployMode::Fresh => {
            let pd = match held_lamports {
                Some(l) => format!(
                    "ProgramData {} still to add ({} of its {} is in the buffer already)",
                    sol(r_pd.saturating_sub(l)),
                    sol(l.min(r_pd)),
                    sol(r_pd)
                ),
                None => format!("ProgramData {}", sol(r_pd)),
            };
            format!(
                "{pd} + Program {} + {fees_for} (the buffer holds the ProgramData rent while the deploy runs; the final transaction moves it into the ProgramData)",
                sol(r_prog)
            )
        }
        DeployMode::Upgrade => {
            let buf = match held_lamports {
                Some(l) => format!("the buffer holds {} already (it returns to the deployer when the upgrade lands)", sol(l)),
                None => format!("buffer {} (the rent of 45 + {} bytes; it returns to the deployer when the upgrade lands)", sol(r_buf), so.len),
            };
            format!("{buf} + ProgramData growth {} ({add} bytes) + {fees_for}", sol(grow))
        }
        DeployMode::Buffer => {
            let buf = match held_lamports {
                Some(l) => format!("the buffer holds {} already", sol(l)),
                None => format!("buffer {} (the rent of 37 + {} bytes)", sol(r_buf), so.len),
            };
            format!("{buf} + {fees_for} (the rent stays in the buffer that is handed over, and goes to the spill account when that upgrade executes)")
        }
    };
    let need = deploy_need.saturating_add(if o.mode == DeployMode::Fresh { init_need } else { 0 });
    let bal = chain.balance(&o.deployer).await?;
    r.add(St::Info, "rent", format!(
        "ProgramData({}) {} SOL, Program {} SOL, a new buffer {} SOL (the rent of {} bytes, what the Solana CLI puts in for this mode), Config {} SOL, Executor rent-exempt(0) {} SOL",
        PROGRAMDATA_HEADER_LEN + o.max_len, sol(r_pd), sol(r_prog), sol(r_buf), new_buffer_len, sol(r_cfg), sol(r0)
    ));
    r.add(St::Info, "deploy cost", format!("{} SOL: {peak_note}", sol(deploy_need)));
    if o.mode == DeployMode::Fresh {
        r.add(
            St::Info,
            "init cost",
            format!(
                "{} SOL: Config {} + Executor float {} (target {} = rent {} + reserve {EXECUTOR_RESERVE} + {} x crank_fee {}; holds {}) + fees {}",
                sol(init_need),
                sol(cfg_need),
                sol(float_need),
                float,
                r0,
                o.crank_reserve_digs,
                o.crank_fee,
                exec_bal,
                sol(INIT_FEE_BUDGET)
            ),
        );
    }
    // The need above already leaves out what an existing buffer holds, so a shortfall never
    // asks for SOL that is in the buffer.
    let counted = held_lamports.map_or_else(String::new, |l| format!(" (the {} SOL in the buffer is counted, not asked for again)", sol(l)));
    if bal >= need {
        r.add(St::Pass, "deployer balance", format!("{} holds {} SOL >= {} SOL needed{counted}", o.deployer, sol(bal), sol(need)));
    } else {
        r.add(
            St::Fail,
            "deployer balance",
            format!("{} holds {} SOL < {} SOL needed{counted}: send at least {} SOL", o.deployer, sol(bal), sol(need), sol(need - bal)),
        );
    }
    out.insert(
        "funding".into(),
        json!({
            "rent": { "programdata": r_pd, "program": r_prog, "buffer": r_buf, "buffer_len": new_buffer_len, "config": r_cfg, "executor_rent_exempt": r0 },
            "deploy_need": deploy_need, "init_need": init_need, "executor_float_target": float, "executor_balance": exec_bal,
            "fee_budget": fee_budget, "fee_budget_full": o.fee_budget, "buffer_lamports": held_lamports,
            "chunks_total": chunks_total, "chunks_to_write": to_write,
            "deployer": o.deployer.to_string(), "deployer_balance": bal, "deployer_need": need,
        }),
    );

    // 9. ORE: the upgrade pin and the exact bytes.
    let pd_head = chain.rpc.get_account_slice(&PROGRAMDATA_ADDRESS, 0, 45).await.map_err(|e| anyhow!("ORE ProgramData: {e}"))?;
    let ore_slot = pd_head.as_ref().and_then(|a| hd_crank::ore::programdata_slot(&a.owner, &a.data));
    let ore_hash = match chain.data(&PROGRAMDATA_ADDRESS).await? {
        Some((_, d, _)) => d.get(PROGRAMDATA_HEADER_LEN as usize..).map(|b| sha256_hex(strip_trailing_zeros(b))),
        None => None,
    };
    out.insert("ore".into(), json!({ "programdata_slot": ore_slot, "program_hash": ore_hash }));
    if o.cluster.is_mainnet() {
        match ore_slot {
            Some(s) if s == PINNED_PROGRAMDATA_SLOT => {
                r.add(St::Pass, "ORE upgrade slot", format!("{s} = pin {PINNED_PROGRAMDATA_SLOT}"))
            }
            other => r.add(
                St::Fail,
                "ORE upgrade slot",
                format!("{other:?} != pin {PINNED_PROGRAMDATA_SLOT}: ORE was upgraded; re-run the fork suites before deploying"),
            ),
        }
        match &ore_hash {
            Some(h) if h == ORE_PROGRAM_HASH => r.add(St::Pass, "ORE program hash", format!("{h} = verify.osec.io 48c203bd")),
            other => r.add(St::Fail, "ORE program hash", format!("{other:?} != pinned {ORE_PROGRAM_HASH}")),
        }
    } else {
        r.add(St::Info, "ORE upgrade slot", format!("{ore_slot:?} (the pin {PINNED_PROGRAMDATA_SLOT} applies to mainnet only)"));
        match &ore_hash {
            Some(h) if h == ORE_PROGRAM_HASH => r.add(St::Pass, "ORE program hash", format!("{h}: the fork runs mainnet's ORE bytes")),
            other => r.add(St::Warn, "ORE program hash", format!("{other:?} != mainnet's {ORE_PROGRAM_HASH}")),
        }
    }

    // 10. ORE account layouts (owner, exact size, discriminator, sanity).
    let mut layout_fail = false;
    let board = match chain.data(&BOARD_ADDRESS).await? {
        Some((ow, d, _)) => match Board::decode(&ow, &d) {
            Ok(b) => Some(b),
            Err(e) => {
                layout_fail = true;
                r.add(St::Fail, "ORE Board", e.to_string());
                None
            }
        },
        None => {
            layout_fail = true;
            r.add(St::Fail, "ORE Board", "missing");
            None
        }
    };
    let treasury = chain.data(&TREASURY_ADDRESS).await?.map(|(ow, d, _)| Treasury::decode(&ow, &d));
    let ore_cfg = chain.data(&CONFIG_ADDRESS).await?.map(|(ow, d, _)| OreConfig::decode(&ow, &d));
    for (name, res) in [("ORE Treasury", treasury.map(|t| t.map(|_| ()))), ("ORE Config", ore_cfg.map(|c| c.map(|_| ())))] {
        match res {
            Some(Ok(())) => {}
            Some(Err(e)) => {
                layout_fail = true;
                r.add(St::Fail, name, e.to_string());
            }
            None => {
                layout_fail = true;
                r.add(St::Fail, name, "missing");
            }
        }
    }
    if let Some(b) = board {
        match chain.data(&round_pda(b.round_id)).await? {
            Some((ow, d, _)) => {
                if let Err(e) = Round::decode(&ow, &d) {
                    layout_fail = true;
                    r.add(St::Fail, "ORE Round", format!("round {}: {e}", b.round_id));
                }
            }
            None => r.add(St::Warn, "ORE Round", format!("round {} account not found yet", b.round_id)),
        }
    }
    if !layout_fail {
        r.add(
            St::Pass,
            "ORE singletons",
            format!(
                "Board 40/105, Treasury 48/104, Config 232/101, Round 952/109 (round {})",
                board.map_or_else(|| "?".into(), |b| b.round_id.to_string())
            ),
        );
    }
    let (autos, miners, bad, sample_err) = match sample_ore_accounts(&chain, o.sample_txs).await {
        Ok((a, m, b)) => (a, m, b, None),
        Err(e) => (0, 0, vec![], Some(format!("{e:#}"))),
    };
    out.insert("ore_sample".into(), json!({ "automations": autos, "miners": miners, "problems": bad, "error": sample_err }));
    if !bad.is_empty() {
        r.add(St::Fail, "ORE user accounts", bad.join("; "));
    } else if let Some(e) = sample_err {
        r.add(St::Warn, "ORE user accounts", format!("could not sample recent ORE transactions ({e}); re-run with a keyed RPC"));
    } else if autos > 0 && miners > 0 {
        r.add(St::Pass, "ORE user accounts", format!("{autos} Automations (160/100) and {miners} Miners (752/103) from recent ORE transactions match"));
    } else {
        r.add(
            St::Warn,
            "ORE user accounts",
            format!("sampled {autos} Automations and {miners} Miners from recent ORE transactions (need at least one of each to check their pins)"),
        );
    }

    // 11. heads_down itself.
    if o.executor_fee == 0 || o.crank_fee > o.executor_fee {
        r.add(
            St::Fail,
            "init params",
            format!("executor_fee {} / crank_fee {}: need executor_fee > 0 and crank_fee <= executor_fee", o.executor_fee, o.crank_fee),
        );
    } else {
        r.add(
            St::Pass,
            "init params",
            format!("executor_fee {} (immutable), crank_fee {} (<= executor_fee; +{} per dig accrues to the Executor)", o.executor_fee, o.crank_fee, o.executor_fee - o.crank_fee),
        );
    }
    r.add(St::Info, "ore_layout_hash", format!("{} = sha256(heads_down::ore::LAYOUT_PREIMAGE)", hex::encode(heads_down::ore::layout_hash())));
    match &cfg {
        Some((c, p)) => r.add(
            St::Info,
            "heads_down Config",
            format!(
                "exists: governance {} registrar {} executor_fee {} crank_fee {} bury_bps {} paused {}{}",
                c.governance,
                c.registrar,
                c.executor_fee,
                c.crank_fee,
                c.bury_bps,
                c.paused,
                if p.exists { format!(" (proposal pending until slot {})", p.eta_slot) } else { String::new() }
            ),
        ),
        None => r.add(St::Info, "heads_down Config", "not initialized (init-config.sh creates it)"),
    }
    r.add(St::Info, "Executor PDA", format!("{} holds {} lamports", hdix::executor(), exec_bal));

    // 12. The other keys the operator funds.
    for k in &o.keys {
        let b = chain.balance(&k.pubkey).await?;
        let st = if b >= k.min { St::Pass } else { St::Warn };
        let tail = if b >= k.min { String::new() } else { format!(": fund {} SOL before starting it", sol(k.min - b)) };
        r.add(st, &k.label, format!("{} holds {} SOL (recommended {}){tail}", k.pubkey, sol(b), sol(k.min)));
    }

    let go = r.fails == 0;
    out.insert("checks".into(), json!(r.checks));
    out.insert("fails".into(), json!(r.fails));
    out.insert("warnings".into(), json!(r.warns));
    out.insert("verdict".into(), json!(if go { "GO" } else { "NO-GO" }));
    let now = unix_now();
    out.insert("recorded_at".into(), json!(rfc3339(now)));
    if let Some(p) = &o.json {
        write_json(p, &Value::Object(out))?;
    }
    Ok(go)
}

/// A raw call retried with backoff when the provider rate-limits (HTTP 429): public RPCs
/// throttle `getTransaction` hard.
async fn call_patiently(chain: &Chain, method: &str, params: Value) -> Result<Value> {
    let mut wait = std::time::Duration::from_millis(500);
    for attempt in 0..6 {
        match chain.call(method, params.clone()).await {
            Ok(v) => return Ok(v),
            Err(e) if attempt < 5 && e.to_string().contains("429") => {
                tokio::time::sleep(wait).await;
                wait *= 2;
            }
            Err(e) => return Err(e),
        }
    }
    bail!("{method}: rate-limited")
}

/// Sample Automation and Miner accounts from recent successful transactions that touched the
/// ORE Board and check their (discriminator, size) pins: transaction by transaction, up to
/// `max_txs`, stopping once both kinds were seen. Returns (automations, miners, problems).
async fn sample_ore_accounts(chain: &Chain, max_txs: usize) -> Result<(usize, usize, Vec<String>)> {
    let sigs = call_patiently(
        chain,
        "getSignaturesForAddress",
        json!([BOARD_ADDRESS.to_string(), { "limit": 100, "commitment": "confirmed" }]),
    )
    .await?;
    let (mut autos, mut miners, mut bad) = (0usize, 0usize, vec![]);
    let mut seen: Vec<String> = vec![];
    let mut txs = 0usize;
    for s in sigs.as_array().into_iter().flatten() {
        if txs >= max_txs || (autos > 0 && miners > 0) {
            break;
        }
        if !s["err"].is_null() {
            continue;
        }
        let Some(sig) = s["signature"].as_str() else { continue };
        let t = call_patiently(
            chain,
            "getTransaction",
            json!([sig, { "encoding": "json", "commitment": "confirmed", "maxSupportedTransactionVersion": 1 }]),
        )
        .await?;
        if t.is_null() {
            continue;
        }
        txs += 1;
        let mut keys: Vec<String> = vec![];
        for v in [&t["transaction"]["message"]["accountKeys"], &t["meta"]["loadedAddresses"]["writable"], &t["meta"]["loadedAddresses"]["readonly"]] {
            for k in v.as_array().into_iter().flatten().filter_map(Value::as_str) {
                if !seen.iter().any(|x| x == k) {
                    seen.push(k.to_string());
                    keys.push(k.to_string());
                }
            }
        }
        for chunk in keys.chunks(100) {
            let v = call_patiently(
                chain,
                "getMultipleAccounts",
                json!([chunk, { "encoding": "base64", "commitment": "confirmed", "dataSlice": { "offset": 0, "length": 8 } }]),
            )
            .await?;
            for (k, a) in chunk.iter().zip(v["value"].as_array().into_iter().flatten()) {
                let Ok(Some((owner, head, _, space))) = head_from_json(a) else { continue };
                if owner != ORE_PROGRAM_ID {
                    continue;
                }
                let kind = match head.first() {
                    Some(100) => OreKind::Automation,
                    Some(103) => OreKind::Miner,
                    Some(105) => OreKind::Board,
                    Some(104) => OreKind::Treasury,
                    Some(101) => OreKind::Config,
                    Some(109) => OreKind::Round,
                    _ => continue,
                };
                // Same owner + size + discriminator rule the crank and the program apply.
                let pseudo = vec![head.first().copied().unwrap_or(0); usize::try_from(space).unwrap_or(0)];
                match check_layout(kind, &owner, &pseudo) {
                    Ok(()) => match kind {
                        OreKind::Automation => autos += 1,
                        OreKind::Miner => miners += 1,
                        _ => {}
                    },
                    Err(e) => bad.push(format!("{k}: {e}")),
                }
            }
        }
    }
    Ok((autos, miners, bad))
}

// ---- funding (what keys.sh prints) ----------------------------------------------------------

/// Options for [`funding`].
pub struct FundingOpts {
    /// Target cluster.
    pub cluster: Cluster,
    /// JSON-RPC URL.
    pub rpc: String,
    /// Deployer pubkey.
    pub deployer: Address,
    /// Program size in bytes (the `.so` length).
    pub so_len: u64,
    /// `--max-len` for the deploy.
    pub max_len: u64,
    /// `Config.crank_fee`.
    pub crank_fee: u64,
    /// Executor float target (default: [`default_float`]).
    pub executor_float: Option<u64>,
    /// Reimbursements the default float covers.
    pub crank_reserve_digs: u64,
    /// Lamports budgeted for deploy transaction fees.
    pub fee_budget: u64,
    /// The other keys and their recommended balances.
    pub keys: Vec<KeyNeed>,
    /// Write the table here (JSON).
    pub json: Option<PathBuf>,
}

/// The exact funding each key needs for the first mainnet deploy, from the cluster's own rent
/// figures (never a hard-coded lamports-per-byte).
pub async fn funding(o: FundingOpts) -> Result<()> {
    let (chain, _) = cluster::connect(o.cluster, &o.rpc).await?;
    let r_pd = chain.rent(PROGRAMDATA_HEADER_LEN + o.max_len).await?;
    let r_prog = chain.rent(PROGRAM_ACCOUNT_LEN).await?;
    // What the Solana CLI puts into the buffer of a later upgrade, and of a buffer written for
    // a multisig proposal (`buffer_rent_len`); the first deploy's buffer holds `r_pd`.
    let r_up = chain.rent(buffer_rent_len(DeployMode::Upgrade, o.so_len, o.max_len)).await?;
    let r_buf = chain.rent(buffer_rent_len(DeployMode::Buffer, o.so_len, o.max_len)).await?;
    let r_cfg = chain.rent(CONFIG_LEN).await?;
    let r0 = chain.rent(0).await?;
    let float = o.executor_float.unwrap_or_else(|| default_float(r0, o.crank_fee, o.crank_reserve_digs));
    let deployer_need = r_pd + r_prog + o.fee_budget + r_cfg + float + INIT_FEE_BUDGET;
    let mut rows = vec![(
        "deployer".to_string(),
        o.deployer,
        deployer_need,
        format!(
            "ProgramData(max-len {}) {} + Program {} + Config {} + Executor float {} + fees {}",
            o.max_len,
            sol(r_pd),
            sol(r_prog),
            sol(r_cfg),
            sol(float),
            sol(o.fee_budget + INIT_FEE_BUDGET)
        ),
    )];
    for k in &o.keys {
        rows.push((k.label.clone(), k.pubkey, k.min, String::new()));
    }
    println!("{:<14} {:<44} {:>14} {:>14}  for", "key", "pubkey", "balance SOL", "fund SOL");
    let mut total_need = 0u64;
    let mut table = vec![];
    for (label, pk, need, why) in &rows {
        let bal = chain.balance(pk).await?;
        let short = need.saturating_sub(bal);
        total_need = total_need.saturating_add(short);
        println!("{label:<14} {:<44} {:>14} {:>14}  {why}", pk.to_string(), sol(bal), sol(short));
        table.push(json!({ "key": label, "pubkey": pk.to_string(), "balance": bal, "need": need, "shortfall": short, "for": why }));
    }
    println!("{:<14} {:<44} {:>14} {:>14}", "TOTAL", "", "", sol(total_need));
    println!(
        "(rent from this cluster: ProgramData {} SOL, which the buffer holds while the first deploy runs; \
         the buffer of a later upgrade of the {}-byte build {} SOL, back in the deployer when the upgrade lands; \
         Config {}; rent-exempt(0) {})",
        sol(r_pd),
        o.so_len,
        sol(r_up),
        sol(r_cfg),
        sol(r0)
    );
    if let Some(p) = &o.json {
        write_json(
            p,
            &json!({
                "schema": "heads-down/funding/v1", "cluster": o.cluster.name(), "rows": table, "total_shortfall": total_need,
                "rent": { "programdata": r_pd, "program": r_prog, "upgrade_buffer": r_up, "buffer": r_buf, "config": r_cfg, "executor_rent_exempt": r0 },
                "max_len": o.max_len, "so_len": o.so_len, "executor_float_target": float, "recorded_at": rfc3339(unix_now()),
            }),
        )?;
    }
    Ok(())
}

// ---- verify-deploy (the public receipt) -----------------------------------------------------

/// Options for [`verify_deploy`].
pub struct VerifyOpts {
    /// Target cluster.
    pub cluster: Cluster,
    /// JSON-RPC URL.
    pub rpc: String,
    /// What was done.
    pub mode: DeployMode,
    /// The local build that was deployed.
    pub so: PathBuf,
    /// Program id.
    pub program_id: Address,
    /// Fee payer.
    pub deployer: Address,
    /// Expected upgrade (or buffer) authority (default: the deployer).
    pub authority: Option<Address>,
    /// Buffer address (buffer mode).
    pub buffer: Option<Address>,
    /// The deploy transaction signature (from `solana program deploy --output json`).
    pub signature: Option<String>,
    /// `--max-len` that was used (fresh deploys).
    pub max_len: Option<u64>,
    /// Deployer balance before, for the cost line.
    pub balance_before: Option<u64>,
    /// What `write-buffer` wrote down about its run (its `--json` file), copied into the receipt.
    pub buffer_write: Option<PathBuf>,
    /// Slot at which the Solana CLI was started: the fee payer's transactions after it are
    /// counted into the receipt (`cli_phase`).
    pub cli_since_slot: Option<u64>,
    /// `key=value` build facts (commit, toolchain, …).
    pub meta: Vec<(String, String)>,
    /// Receipt path.
    pub out: PathBuf,
}

/// Check the deployed (or buffered) bytes against the local build and write the receipt.
pub async fn verify_deploy(o: VerifyOpts) -> Result<()> {
    let (chain, genesis) = cluster::connect(o.cluster, &o.rpc).await?;
    let bytes = std::fs::read(&o.so).with_context(|| format!("read {}", o.so.display()))?;
    let so = so_info(&bytes)?;
    let want_auth = o.authority.unwrap_or(o.deployer);
    let mut rec = json!({
        "schema": "heads-down/deploy-receipt/v1",
        "kind": format!("{:?}", o.mode).to_lowercase(),
        "cluster": o.cluster.name(),
        "genesis_hash": genesis,
        "program_id": o.program_id.to_string(),
        "fee_payer": o.deployer.to_string(),
        "so": { "path": o.so.display().to_string(), "len": so.len, "sha256": so.sha256, "program_hash": so.program_hash, "sbpf": sbpf_name(so.sbpf) },
        "build": o.meta.iter().map(|(k, v)| (k.clone(), json!(v))).collect::<Map<String, Value>>(),
    });
    let (region, holder): (Vec<u8>, String) = if o.mode == DeployMode::Buffer {
        let buf = o.buffer.ok_or_else(|| anyhow!("--buffer is required in buffer mode"))?;
        let (owner, data, lamports) = chain.data(&buf).await?.ok_or_else(|| anyhow!("buffer {buf} not found"))?;
        if owner != BPF_UPGRADEABLE_LOADER_ID || data.get(0..4) != Some([1u8, 0, 0, 0].as_slice()) {
            bail!("{buf} is not an upgradeable-loader Buffer");
        }
        let auth = match data.get(4) {
            Some(1) => data.get(5..37).and_then(|s| <[u8; 32]>::try_from(s).ok()).map(Address::new_from_array),
            _ => None,
        };
        if auth != Some(want_auth) {
            bail!("buffer authority is {auth:?}, expected {want_auth}");
        }
        rec["buffer"] = json!({ "address": buf.to_string(), "authority": want_auth.to_string(), "lamports": lamports, "len": data.len() });
        (data.get(BUFFER_HEADER_LEN as usize..).unwrap_or_default().to_vec(), format!("buffer {buf}"))
    } else {
        let ProgramState::Deployed { programdata, slot, authority, programdata_len, programdata_lamports } =
            program_state(&chain, &o.program_id).await?
        else {
            bail!("{} is not a deployed upgradeable program", o.program_id);
        };
        if authority != Some(want_auth) {
            bail!("upgrade authority is {authority:?}, expected {want_auth}");
        }
        if let Some(m) = o.max_len {
            if o.mode == DeployMode::Fresh && programdata_len != PROGRAMDATA_HEADER_LEN + m {
                bail!("ProgramData is {programdata_len} bytes, expected {} (max-len {m})", PROGRAMDATA_HEADER_LEN + m);
            }
        }
        let (_, data, _) = chain.data(&programdata).await?.ok_or_else(|| anyhow!("ProgramData {programdata} vanished"))?;
        rec["programdata"] = json!(programdata.to_string());
        rec["upgrade_authority"] = json!(want_auth.to_string());
        rec["last_deploy_slot"] = json!(slot);
        rec["programdata_len"] = json!(programdata_len);
        rec["max_len"] = json!(programdata_len.saturating_sub(PROGRAMDATA_HEADER_LEN));
        rec["programdata_rent_lamports"] = json!(programdata_lamports);
        (data.get(PROGRAMDATA_HEADER_LEN as usize..).unwrap_or_default().to_vec(), format!("ProgramData {programdata}"))
    };
    let n = bytes.len();
    let prefix_ok = region.get(..n) == Some(bytes.as_slice());
    let rest_zero = region.get(n..).is_some_and(|r| r.iter().all(|&b| b == 0));
    let onchain_hash = sha256_hex(strip_trailing_zeros(&region));
    if !(prefix_ok && rest_zero) || onchain_hash != so.program_hash {
        bail!("the bytes in {holder} do NOT match {} (on-chain program hash {onchain_hash}, local {})", o.so.display(), so.program_hash);
    }
    rec["onchain"] = json!({ "matches_local_so": true, "program_hash": onchain_hash, "region_len": region.len() });
    if let Some(sig) = &o.signature {
        let (tx_slot, fee, err) = chain.tx_fee(sig).await?;
        if let Some(e) = err {
            bail!("the deploy transaction {sig} failed on-chain: {e}");
        }
        rec["signature"] = json!(sig);
        rec["tx_slot"] = json!(tx_slot);
        rec["tx_fee_lamports"] = json!(fee);
    }
    let after = chain.balance(&o.deployer).await?;
    rec["fee_payer_balance_after"] = json!(after);
    if let Some(before) = o.balance_before {
        rec["fee_payer_spent_lamports"] = json!(before.saturating_sub(after));
    }
    // How the bytes got there: what the paced writer did, and how many transactions the Solana
    // CLI sent after it. Neither is needed for the checks above, so a failure here is reported
    // and the receipt is still written.
    if let Some(path) = &o.buffer_write {
        match std::fs::read_to_string(path).map_err(anyhow::Error::from).and_then(|s| Ok(serde_json::from_str::<Value>(&s)?)) {
            Ok(v) => rec["buffer_write"] = v,
            Err(e) => println!("verify: could not read {} ({e:#}): the receipt has no buffer_write", path.display()),
        }
    }
    if let Some(since) = o.cli_since_slot {
        let since = since.max(rec["buffer_write"]["last_slot"].as_u64().unwrap_or(0));
        match paid_settled(&chain, &o.deployer, since, o.signature.as_deref()).await {
            Ok(paid) => {
                println!("verify: after slot {since} the Solana CLI sent {} transaction(s), {} lamports of fees", paid.len(), fees_of(&paid));
                rec["cli_phase"] = paid_json(since, &paid);
            }
            Err(e) => println!("verify: could not count the Solana CLI's transactions ({e:#}): the receipt has no cli_phase"),
        }
    }
    let now = unix_now();
    rec["unix_time"] = json!(now);
    rec["recorded_at"] = json!(rfc3339(now));
    write_json(&o.out, &rec)?;
    println!("verify: {holder} holds exactly {} ({} bytes, program hash {})", o.so.display(), n, so.program_hash);
    println!("verify: receipt {}", o.out.display());
    Ok(())
}

// ---- fees (what an address paid for) ---------------------------------------------------------

/// One transaction a fee payer paid for.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct PaidTx {
    /// Base58 signature.
    pub signature: String,
    /// Slot it landed in.
    pub slot: u64,
    /// Fee in lamports.
    pub fee: u64,
    /// It failed on-chain (the fee is paid all the same).
    pub failed: bool,
}

fn fees_of(paid: &[PaidTx]) -> u64 {
    paid.iter().map(|t| t.fee).sum()
}

fn paid_json(since_slot: u64, paid: &[PaidTx]) -> Value {
    json!({
        "since_slot": since_slot,
        "transactions": paid.len(),
        "failed": paid.iter().filter(|t| t.failed).count(),
        "fees_lamports": fees_of(paid),
    })
}

/// The transactions `payer` paid for in slots after `since_slot`, newest first (confirmed).
/// Transactions that only mention the address (an airdrop to it) are left out.
pub async fn paid_since(chain: &Chain, payer: &Address, since_slot: u64) -> Result<Vec<PaidTx>> {
    let p = Patience::default();
    let mut out = vec![];
    let mut before: Option<String> = None;
    loop {
        let mut cfg = json!({ "limit": 1000, "commitment": "confirmed" });
        if let Some(b) = &before {
            cfg["before"] = json!(b);
        }
        let params = json!([payer.to_string(), cfg]);
        let page = patiently(p, "getSignaturesForAddress", || chain.rpc.call("getSignaturesForAddress", params.clone())).await?;
        let rows = page.as_array().cloned().unwrap_or_default();
        let mut reached = rows.len() < 1000;
        for row in &rows {
            let slot = row["slot"].as_u64().unwrap_or(0);
            if slot <= since_slot {
                reached = true;
                break;
            }
            let Some(sig) = row["signature"].as_str() else { continue };
            let t = wait_for("getTransaction", Duration::from_secs(20), Duration::from_millis(300), || async {
                chain.rpc.get_transaction_full(sig).await.map_err(|e| anyhow!("{sig}: {e}"))
            })
            .await?;
            if t.fee_payer == Some(*payer) {
                out.push(PaidTx { signature: sig.to_string(), slot, fee: t.fee, failed: t.err.is_some() });
            }
        }
        if reached {
            return Ok(out);
        }
        before = rows.last().and_then(|r| r["signature"].as_str()).map(str::to_string);
    }
}

/// [`paid_since`], read until two reads half a second apart agree and `must_include` (a
/// signature known to have landed) is among them: an RPC lists a transaction under its
/// addresses a moment after it confirms it.
pub async fn paid_settled(chain: &Chain, payer: &Address, since_slot: u64, must_include: Option<&str>) -> Result<Vec<PaidTx>> {
    let mut last = paid_since(chain, payer, since_slot).await?;
    for _ in 0..20 {
        tokio::time::sleep(Duration::from_millis(500)).await;
        let now = paid_since(chain, payer, since_slot).await?;
        let settled = now.len() == last.len() && must_include.is_none_or(|s| now.iter().any(|t| t.signature == s));
        last = now;
        if settled {
            break;
        }
    }
    Ok(last)
}

/// Options for [`fees`].
pub struct FeesOpts {
    /// Target cluster.
    pub cluster: Cluster,
    /// JSON-RPC URL.
    pub rpc: String,
    /// The fee payer.
    pub payer: Address,
    /// Count transactions in slots after this one.
    pub since_slot: u64,
    /// Write the count here (JSON).
    pub json: Option<PathBuf>,
}

/// Read-only: how many transactions an address paid for after a slot, and their fees.
pub async fn fees(o: FeesOpts) -> Result<()> {
    let (chain, _) = cluster::connect(o.cluster, &o.rpc).await?;
    let paid = paid_settled(&chain, &o.payer, o.since_slot, None).await?;
    println!(
        "fees: {} paid for {} transaction(s) after slot {} ({} failed): {} lamports",
        o.payer,
        paid.len(),
        o.since_slot,
        paid.iter().filter(|t| t.failed).count(),
        fees_of(&paid)
    );
    if let Some(p) = &o.json {
        let mut v = paid_json(o.since_slot, &paid);
        v["schema"] = json!("heads-down/fees/v1");
        v["cluster"] = json!(o.cluster.name());
        v["payer"] = json!(o.payer.to_string());
        v["balance"] = json!(chain.balance(&o.payer).await?);
        write_json(p, &v)?;
    }
    Ok(())
}

// ---- governance ----------------------------------------------------------------------------

/// Options for [`propose`].
pub struct ProposeOpts {
    /// Target cluster.
    pub cluster: Cluster,
    /// JSON-RPC URL.
    pub rpc: String,
    /// `Config.governance` keypair (signs and pays).
    pub governance: PathBuf,
    /// New registrar (default: unchanged).
    pub registrar: Option<Address>,
    /// New crank fee (default: unchanged).
    pub crank_fee: Option<u64>,
    /// New bury share (default: unchanged).
    pub bury_bps: Option<u16>,
    /// New pause flag (default: unchanged). `1` takes effect immediately.
    pub paused: Option<u8>,
    /// Priority fee, micro-lamports per CU.
    pub cu_price: u64,
    /// Mainnet: actually send.
    pub yes: bool,
}

/// `propose_config`: every field defaults to its current value, so `--paused 1` alone is the
/// emergency pause.
pub async fn propose(o: ProposeOpts) -> Result<()> {
    let (chain, _) = cluster::connect(o.cluster, &o.rpc).await?;
    let gov = read_keypair(&o.governance)?;
    let (c, p) = read_config(&chain).await?.ok_or_else(|| anyhow!("heads_down Config is not initialized"))?;
    if c.governance != gov.pubkey() {
        bail!("Config.governance is {}, not {}", c.governance, gov.pubkey());
    }
    let registrar = o.registrar.unwrap_or(c.registrar);
    let crank_fee = o.crank_fee.unwrap_or(c.crank_fee);
    let bury_bps = o.bury_bps.unwrap_or(c.bury_bps);
    let paused = o.paused.unwrap_or(u8::from(c.paused));
    if crank_fee > c.executor_fee || bury_bps > 10_000 || paused > 1 {
        bail!("invalid proposal: crank_fee <= executor_fee ({}), bury_bps <= 10000, paused 0 or 1", c.executor_fee);
    }
    let slot = chain.slot().await?;
    println!("propose_config ({}): Config {}", o.cluster.name(), hdix::config());
    println!("  registrar  {} -> {registrar}", c.registrar);
    println!("  crank_fee  {} -> {crank_fee}", c.crank_fee);
    println!("  bury_bps   {} -> {bury_bps}", c.bury_bps);
    println!("  paused     {} -> {paused}{}", u8::from(c.paused), if paused == 1 { "  (takes effect IMMEDIATELY: dig fails with Paused)" } else { "" });
    println!(
        "  eta        slot {} (~{} h at 400 ms; at least 72 h): then anyone may apply_config",
        slot + TIMELOCK_SLOTS,
        TIMELOCK_SLOTS * 4 / 10 / 3600
    );
    if p.exists {
        println!("  NOTE       this replaces the pending proposal (eta slot {}) and restarts the clock", p.eta_slot);
    }
    require_yes(o.cluster, o.yes, "propose_config")?;
    let ix = hdix::propose_config_ix(&gov.pubkey(), &registrar, crank_fee, bury_bps, paused);
    let l = chain.send_budgeted(&gov, &[ix], o.cu_price).await?;
    let (c2, p2) = read_config(&chain).await?.ok_or_else(|| anyhow!("Config vanished"))?;
    println!(
        "propose_config tx {}: pending until slot {} (paused now {}, pending paused {})",
        l.signature, p2.eta_slot, c2.paused, p2.paused
    );
    Ok(())
}

/// Options for [`apply`].
pub struct ApplyOpts {
    /// Target cluster.
    pub cluster: Cluster,
    /// JSON-RPC URL.
    pub rpc: String,
    /// Any funded keypair (pays the fee).
    pub payer: PathBuf,
    /// Priority fee, micro-lamports per CU.
    pub cu_price: u64,
    /// Mainnet: actually send.
    pub yes: bool,
}

/// `apply_config` once the timelock has elapsed.
pub async fn apply(o: ApplyOpts) -> Result<()> {
    let (chain, _) = cluster::connect(o.cluster, &o.rpc).await?;
    let payer: Keypair = read_keypair(&o.payer)?;
    let (_, p) = read_config(&chain).await?.ok_or_else(|| anyhow!("heads_down Config is not initialized"))?;
    if !p.exists {
        bail!("no pending proposal");
    }
    let slot = chain.slot().await?;
    if slot < p.eta_slot {
        bail!("timelock: {} slots to go (eta slot {}, now {slot})", p.eta_slot - slot, p.eta_slot);
    }
    println!(
        "apply_config ({}): registrar {} crank_fee {} bury_bps {} paused {}",
        o.cluster.name(),
        p.registrar,
        p.crank_fee,
        p.bury_bps,
        p.paused
    );
    require_yes(o.cluster, o.yes, "apply_config")?;
    let l = chain.send_budgeted(&payer, &[hdix::apply_config_ix()], o.cu_price).await?;
    println!("apply_config tx {}", l.signature);
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn so_info_reads_the_sbpf_version_and_hashes() {
        let mut elf = vec![0u8; 128];
        elf[0..4].copy_from_slice(b"\x7fELF");
        elf[4] = 2;
        elf[5] = 1;
        elf[48..52].copy_from_slice(&3u32.to_le_bytes());
        elf[100] = 9; // trailing zeros after this byte
        let i = so_info(&elf).unwrap();
        assert_eq!(i.sbpf, Some(3));
        assert_eq!(i.len, 128);
        assert_eq!(i.program_hash, sha256_hex(&elf[..101]));
        assert_ne!(i.program_hash, i.sha256);
        elf[48..52].copy_from_slice(&0x20u32.to_le_bytes());
        assert_eq!(so_info(&elf).unwrap().sbpf, None);
        assert!(so_info(b"not an elf").is_err());
    }

    #[test]
    fn the_built_program_is_sbpf_v0_or_newer_when_present() {
        let p = concat!(env!("CARGO_MANIFEST_DIR"), "/../../../programs/heads-down/target/deploy/heads_down.so");
        if let Ok(b) = std::fs::read(p) {
            let i = so_info(&b).unwrap();
            assert!(i.sbpf.is_some(), "e_flags of {p}");
        }
    }

    #[test]
    fn default_float_is_rent_plus_reserve_plus_reimbursements() {
        assert_eq!(EXECUTOR_RESERVE, 100_000);
        assert_eq!(default_float(890_880, 7_000, 100), 890_880 + 100_000 + 700_000);
        assert_eq!(CONFIG_LEN, 256);
        assert_eq!(TIMELOCK_SLOTS, 864_000);
    }

    #[test]
    fn a_growing_upgrade_extends_by_the_loaders_minimum() {
        let deployed = PROGRAMDATA_HEADER_LEN + 196_608;
        // The build fits: nothing is added.
        assert_eq!(extend_bytes(deployed, PROGRAMDATA_HEADER_LEN + 190_048), 0);
        assert_eq!(extend_bytes(deployed, deployed), 0);
        // One byte too large: the loader adds 10,240 bytes, not one.
        assert_eq!(extend_bytes(deployed, deployed + 1), 10_240);
        assert_eq!(extend_bytes(deployed, deployed + 10_240), 10_240);
        assert_eq!(extend_bytes(deployed, deployed + 20_000), 20_000);
        // At 5,080 lamports a byte (mainnet's rent on 2026-10-04) the smallest growth locks
        // 52,019,200 lamports, where one byte's rent would be 5,080.
        assert_eq!(extend_bytes(deployed, deployed + 1) * 5_080, 52_019_200);
        // Within 10,240 bytes of the 10 MiB limit the loader takes exactly the room left.
        assert_eq!(extend_bytes(MAX_PROGRAM_LEN - 100, MAX_PROGRAM_LEN - 50), 100);
        assert_eq!(extend_bytes(MAX_PROGRAM_LEN - 100, MAX_PROGRAM_LEN), 100);
    }

    #[test]
    fn a_buffer_that_exists_is_not_paid_for_twice() {
        // Mainnet's figures for today's 190,048-byte build and --max-len 196,608: ProgramData,
        // Program account, an upgrade's buffer (45 + build), a proposal's buffer (37 + build),
        // the fee budget for max-len and the 145,000 lamports one transaction is budgeted at.
        let (pd, prog, up, buf, full, per_tx) = (999_647_480, 833_120, 966_322_680, 966_282_040, 32_335_000, 145_000);
        use DeployMode::{Buffer, Fresh, Upgrade};

        // A first run needs the whole rent and the whole fee budget.
        assert_eq!(fee_budget_for(full, Some(per_tx), false, 198), full);
        assert_eq!(deploy_lamports(Fresh, pd, prog, pd, None, 0, full), 1_032_815_600);

        // It stopped after 62 of 198 chunks: the buffer holds the rent and 136 chunks are left.
        let fees = fee_budget_for(full, Some(per_tx), true, 136);
        assert_eq!(fees, (136 + 4) * 145_000);
        let resume = deploy_lamports(Fresh, pd, prog, pd, Some(pd), 0, fees);
        assert_eq!(resume, 833_120 + 20_300_000);
        // Of the 1.04 SOL sent, the buffer took the rent: what is left (less a million lamports
        // of fees, far more than 63 transactions cost) covers the rest and init-config.
        assert!(resume + 3_500_960 < 1_040_000_000 - pd - 1_000_000);
        // A buffer that holds less than the ProgramData rent leaves the difference to add.
        assert_eq!(deploy_lamports(Fresh, pd, prog, pd, Some(up), 0, fees), (pd - up) + 833_120 + fees);
        assert_eq!(deploy_lamports(Fresh, pd, prog, pd, Some(pd + 5), 0, fees), 833_120 + fees);

        // Nothing left to write: the four spare transactions. Never more than the full budget,
        // and the full budget when no per-transaction figure is given.
        assert_eq!(fee_budget_for(full, Some(per_tx), true, 0), 580_000);
        assert_eq!(fee_budget_for(full, Some(per_tx), true, 5_000), full);
        assert_eq!(fee_budget_for(full, None, true, 10), full);

        // An upgrade: its buffer, then the same with a ProgramData that has to grow.
        assert_eq!(deploy_lamports(Upgrade, pd, prog, up, None, 0, full), 998_657_680);
        assert_eq!(deploy_lamports(Upgrade, pd, prog, up, None, 52_019_200, full), 1_050_676_880);
        assert_eq!(deploy_lamports(Upgrade, pd, prog, up, Some(up), 0, fees), fees);
        // A buffer for a multisig proposal.
        assert_eq!(deploy_lamports(Buffer, pd, prog, buf, None, 0, full), 998_617_040);
        assert_eq!(deploy_lamports(Buffer, pd, prog, buf, Some(buf), 0, 580_000), 580_000);
    }

    #[test]
    fn key_need_parsing() {
        let k: KeyNeed = "crank-payer=HDn4vgLWFLLdexKEwfZwRHjWtizNvdqFteLbMsE67F9p:50000000".parse().unwrap();
        assert_eq!((k.label.as_str(), k.min), ("crank-payer", 50_000_000));
        assert_eq!(k.pubkey, hd::PROGRAM_ID);
        let k: KeyNeed = "registrar=HDn4vgLWFLLdexKEwfZwRHjWtizNvdqFteLbMsE67F9p".parse().unwrap();
        assert_eq!(k.min, 0);
        assert!("nolabel".parse::<KeyNeed>().is_err());
        assert!("x=notakey:1".parse::<KeyNeed>().is_err());
    }

    #[test]
    fn strip_trailing_zeros_edges() {
        assert_eq!(strip_trailing_zeros(&[0, 0]), &[] as &[u8]);
        assert_eq!(strip_trailing_zeros(&[1, 0, 2, 0, 0]), &[1, 0, 2]);
    }
}
