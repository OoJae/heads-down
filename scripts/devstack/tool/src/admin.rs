//! `init` (heads_down `initialize_config` + Executor float), `fund`, `status` and `probe`.

use std::path::PathBuf;

use anyhow::{anyhow, bail, Result};
use hd_crank::hd::{self, HdConfig};
use hd_crank::ore::{Board, OreConfig, Round, Treasury, BOARD_ADDRESS, CONFIG_ADDRESS, TREASURY_ADDRESS};
use serde_json::json;
use solana_address::Address;
use solana_keypair::Keypair;
use solana_signer::Signer;

use crate::hd as hdix;
use crate::ore::{self, var};
use crate::phone::Phone;
use crate::util::{b32_at, read_keypair, u64_at, Chain, SOL};

/// Options for [`init`].
pub struct InitOpts {
    /// JSON-RPC URL.
    pub rpc: String,
    /// heads_down upgrade authority (the local dev key that deployed it).
    pub authority: PathBuf,
    /// Governance address stored in Config.
    pub governance: Address,
    /// Registrar (Ed25519 attestation key) address stored in Config.
    pub registrar: Address,
    /// `Config.executor_fee`: the Discretionary fee every rig's Automation must use.
    pub executor_fee: u64,
    /// `Config.crank_fee`: reimbursed per real dig (≤ executor_fee).
    pub crank_fee: u64,
    /// Target Executor PDA float in lamports.
    pub executor_float: u64,
}

/// Create the heads_down Config (idempotent) and fund the Executor PDA float.
pub async fn init(o: InitOpts) -> Result<()> {
    let chain = Chain::new(&o.rpc)?;
    let auth = read_keypair(&o.authority)?;
    if o.crank_fee > o.executor_fee {
        bail!("crank_fee {} > executor_fee {}: the program refuses it", o.crank_fee, o.executor_fee);
    }
    match chain.data(&hdix::config()).await? {
        Some((owner, data, _)) => {
            let c = HdConfig::decode(&hd::PROGRAM_ID, &owner, &data).map_err(|e| anyhow!("existing Config: {e}"))?;
            println!("init: Config {} already exists (executor_fee {}, crank_fee {})", hdix::config(), c.executor_fee, c.crank_fee);
        }
        None => {
            let ix = hdix::initialize_config_ix(&auth.pubkey(), &o.governance, &o.registrar, o.crank_fee, o.executor_fee, 0);
            let l = chain.send(&auth, &[ix]).await?;
            println!(
                "init: initialize_config tx {} -> Config {} (executor_fee {}, crank_fee {}, governance {}, registrar {})",
                l.signature,
                hdix::config(),
                o.executor_fee,
                o.crank_fee,
                o.governance,
                o.registrar
            );
        }
    }
    let ex = hdix::executor();
    let have = chain.balance(&ex).await?;
    if have < o.executor_float {
        let ix = hd_crank::tx::system_transfer(&auth.pubkey(), &ex, o.executor_float - have);
        let l = chain.send(&auth, &[ix]).await?;
        println!("init: funded the Executor PDA {ex} to {} lamports (tx {})", o.executor_float, l.signature);
    } else {
        println!("init: Executor PDA {ex} holds {have} lamports");
    }
    Ok(())
}

/// Airdrop `sol` to `to` on the local validator.
pub async fn fund(rpc: &str, to: &Address, sol: f64) -> Result<()> {
    let chain = Chain::new(rpc)?;
    let lamports = (sol * SOL as f64) as u64;
    chain.airdrop(to, lamports).await?;
    println!("funded {to}: balance {} SOL", chain.balance(to).await? as f64 / SOL as f64);
    Ok(())
}

/// Print the local fork's ORE and heads_down state.
pub async fn status(rpc: &str) -> Result<()> {
    let chain = Chain::new(rpc)?;
    let slot = chain.slot().await?;
    let (bo, bd, _) = chain.data(&BOARD_ADDRESS).await?.ok_or_else(|| anyhow!("no ORE Board: stack not up?"))?;
    let b = Board::decode(&bo, &bd).map_err(|e| anyhow!("Board: {e}"))?;
    let (co, cd, _) = chain.data(&CONFIG_ADDRESS).await?.ok_or_else(|| anyhow!("ORE Config"))?;
    let c = OreConfig::decode(&co, &cd).map_err(|e| anyhow!("ORE Config: {e}"))?;
    let (to, td, _) = chain.data(&TREASURY_ADDRESS).await?.ok_or_else(|| anyhow!("Treasury"))?;
    let t = Treasury::decode(&to, &td).map_err(|e| anyhow!("Treasury: {e}"))?;
    println!("slot            {slot}");
    let window = if b.started() {
        format!("[{}, {}) ({} slots left, reset from {})", b.start_slot, b.end_slot, b.end_slot.saturating_sub(slot), b.end_slot + c.intermission_slots)
    } else {
        "waiting for its first deploy".into()
    };
    println!("ORE round       {} {window}", b.round_id);
    if let Some((ro, rd, _)) = chain.data(&hd_crank::ore::round_pda(b.round_id)).await? {
        let r = Round::decode(&ro, &rd).map_err(|e| anyhow!("Round: {e}"))?;
        println!("round deployed  {} lamports by {} miners", r.total_deployed(), r.total_miners);
    }
    println!("ema / pot       {} lamports/ORE, motherlode {:.2} ORE", b.production_cost_ema, t.motherlode as f64 / 1e11);
    println!("gate ema_ev     {:?} lamports/ORE", hd_crank::gate::ema_ev(b.production_cost_ema, t.motherlode));
    if let Some((_, vd, _)) = chain.data(&ore::VAR_ADDRESS).await? {
        let z = |off| b32_at(&vd, off) == [0; 32];
        println!(
            "entropy Var     end_at {} sampled {} revealed {} samples left {}",
            u64_at(&vd, var::END_AT),
            !z(var::SLOT_HASH),
            !z(var::VALUE),
            u64_at(&vd, var::SAMPLES)
        );
    }
    match chain.data(&hdix::config()).await? {
        Some((o, d, _)) => {
            let hc = HdConfig::decode(&hd::PROGRAM_ID, &o, &d).map_err(|e| anyhow!("Config: {e}"))?;
            println!("heads_down      Config {} executor_fee {} crank_fee {} paused {}", hdix::config(), hc.executor_fee, hc.crank_fee, hc.paused);
        }
        None => println!("heads_down      Config not initialized"),
    }
    println!("Executor PDA    {} {} lamports", hdix::executor(), chain.balance(&hdix::executor()).await.unwrap_or(0));
    Ok(())
}

/// Check that the engine behaves like a real cluster where Heads Down depends on it:
/// the secp256r1 precompile really verifies (a valid signature passes, a tampered one fails),
/// `getTransaction` accepts `maxSupportedTransactionVersion: 1`, and the SPL Token program
/// ORE's `reset` mints through is present.
pub async fn probe(rpc: &str) -> Result<()> {
    let chain = Chain::new(rpc)?;
    let payer = Keypair::new();
    chain.airdrop(&payer.pubkey(), SOL).await?;
    let phone = Phone::random()?;
    let rig = Address::new_from_array([7; 32]);
    let f = hd::HeartbeatFields { counter: 1, shift_id: 1, round_id: 1, lease_rounds: 1 };
    let (digest, sig) = phone.sign_heartbeat(&rig, &f)?;
    let ok = chain.send_opts(&payer, &[phone.precompile_ix(&digest, &sig)?], false).await;
    let ok_line = match &ok {
        Ok(l) => format!("PASS valid signature landed (tx {})", l.signature),
        Err(e) => format!("FAIL valid signature: {e}"),
    };
    let mut bad = sig;
    bad[40] ^= 1;
    let tampered = chain.send_opts(&payer, &[phone.precompile_ix(&digest, &bad)?], false).await;
    let bad_line = match &tampered {
        Ok(l) => format!("FAIL tampered signature LANDED (tx {}): the precompile is not verifying", l.signature),
        Err(_) => "PASS tampered signature rejected".to_string(),
    };
    println!("secp256r1       {ok_line}; {bad_line}");
    if let Ok(l) = &ok {
        let v1 = chain
            .call("getTransaction", json!([l.signature, { "encoding": "json", "commitment": "confirmed", "maxSupportedTransactionVersion": 1 }]))
            .await;
        println!("getTransaction  maxSupportedTransactionVersion=1: {}", if v1.is_ok() { "PASS" } else { "FAIL" });
    }
    let token = chain.data(&ore::TOKEN_PROGRAM_ID).await?.is_some();
    println!("SPL Token       {}", if token { "PASS present" } else { "FAIL missing (ORE reset mints through it)" });
    if ok.is_err() || tampered.is_ok() || !token {
        bail!("engine probe failed");
    }
    Ok(())
}
