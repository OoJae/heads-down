//! Clock-in: the canonical one-transaction onboarding, used by the smoke and by `clock-in`
//! (a Mac-held dev wallet arming a rig for a phone's Keystore P-256 public key, so a phone
//! only has to stream heartbeats to the crank).

use std::path::Path;

use anyhow::{anyhow, bail, Result};
use hd_crank::hd::{self, HdConfig, Rig, RigState};
use solana_address::Address;
use solana_keypair::Keypair;
use solana_signer::Signer;

use crate::hd as hdix;
use crate::ore;
use crate::util::{Chain, Landed, SOL};

/// What to arm.
#[derive(Clone, Copy, Debug)]
pub struct ClockIn {
    /// ORE per-square cap (`automation.amount`).
    pub tile_cap: u64,
    /// Deposit into the Automation.
    pub deposit: u64,
    /// SOL per dig.
    pub dig_lamports: u64,
    /// Split tiles per dig.
    pub split: u8,
    /// Heartbeat lease (1..=3).
    pub lease: u8,
    /// Shift length in seconds from now.
    pub window_secs: i64,
}

impl ClockIn {
    /// 0.001 SOL digs on the 10 least-crowded split tiles, 0.05 SOL deposit.
    pub fn standard(lease: u8, window_secs: i64) -> Self {
        ClockIn { tile_cap: 100_000, deposit: SOL / 20, dig_lamports: 1_000_000, split: 10, lease, window_secs }
    }
}

/// heads_down Config (for the executor fee every Automation must use).
pub async fn config(chain: &Chain) -> Result<HdConfig> {
    let (o, d, _) = chain.data(&hdix::config()).await?.ok_or_else(|| anyhow!("heads_down Config missing: run up.sh"))?;
    HdConfig::decode(&hd::PROGRAM_ID, &o, &d).map_err(|e| anyhow!("Config: {e}"))
}

/// Read a Rig.
pub async fn rig(chain: &Chain, addr: &Address) -> Result<Option<Rig>> {
    match chain.data(addr).await? {
        Some((o, d, _)) => Ok(Some(Rig::decode(&hd::PROGRAM_ID, &o, &d).map_err(|e| anyhow!("Rig: {e}"))?)),
        None => Ok(None),
    }
}

/// ORE `automate` + `register_rig` (if new) + `set_caps` + `arm_shift` in ONE wallet transaction.
pub async fn clock_in(chain: &Chain, wallet: &Keypair, p256: &[u8; 33], c: ClockIn) -> Result<(Landed, Rig)> {
    let cfg = config(chain).await?;
    let w = wallet.pubkey();
    let rig_addr = hdix::rig(&w);
    let existing = rig(chain, &rig_addr).await?;
    if let Some(r) = &existing {
        if &r.p256_pubkey != p256 {
            bail!("rig {rig_addr} is registered with another P-256 key (rotate_key is not wired here: use a new wallet)");
        }
        if r.state != RigState::Idle {
            bail!("rig {rig_addr} already has an open shift ({:?}); end it first", r.state);
        }
    }
    let now = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH)?.as_secs() as i64;
    let caps = hdix::Caps { week: SOL, shift: SOL / 10, round: 5 * c.dig_lamports, max_cost: 2 * SOL, expiry: now + c.window_secs + 86_400 };
    let plan = hdix::Plan {
        max_ev_cost: 2 * SOL,
        dig_lamports: c.dig_lamports,
        split: c.split,
        solo: 0,
        lease: c.lease,
        flags: 0,
        window_start: now - 600,
        window_end: now + c.window_secs,
    };
    let mut ixs = vec![ore::automate_ix(&w, &hdix::executor(), c.tile_cap, c.deposit, cfg.executor_fee)];
    if existing.is_none() {
        ixs.push(hdix::register_rig_ix(&w, p256));
    }
    ixs.push(hdix::set_caps_ix(&w, &caps));
    ixs.push(hdix::arm_wallet_ix(&w, &plan));
    let l = chain.send(wallet, &ixs).await?;
    let r = rig(chain, &rig_addr).await?.ok_or_else(|| anyhow!("rig missing after clock-in"))?;
    if r.state != RigState::Armed {
        bail!("rig not Armed after clock-in: {:?}", r.state);
    }
    Ok((l, r))
}

/// `clock-in` command: arm a rig for an external phone's P-256 key with a Mac-held dev wallet.
pub async fn run(rpc: &str, wallet_path: &Path, p256_hex: &str, lease: u8, hours: f64) -> Result<()> {
    let bytes = hex::decode(p256_hex.trim()).map_err(|_| anyhow!("--p256 must be hex"))?;
    let p256: [u8; 33] = bytes.try_into().map_err(|_| anyhow!("--p256 must be a 33-byte SEC1 compressed key"))?;
    let chain = Chain::new(rpc)?;
    let wallet = crate::util::read_keypair(wallet_path)?;
    if chain.balance(&wallet.pubkey()).await? < SOL / 2 {
        chain.airdrop(&wallet.pubkey(), 2 * SOL).await?;
    }
    let (l, r) = clock_in(&chain, &wallet, &p256, ClockIn::standard(lease, (hours * 3600.0) as i64)).await?;
    let rig_addr = hdix::rig(&wallet.pubkey());
    println!("clock-in tx {}", l.signature);
    println!("rig        {rig_addr}");
    println!("authority  {}", wallet.pubkey());
    println!("shift_id   {}", r.shift_id);
    println!("hb_counter {} (the phone's next heartbeat counter must be greater)", r.hb_counter);
    println!("lease      {} round(s) per heartbeat; window ends in {hours} h", r.plan_lease_rounds);
    Ok(())
}
