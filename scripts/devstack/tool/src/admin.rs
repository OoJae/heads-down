//! `fund`, `status` and `probe` (`init` lives in `ops.rs` with the other deploy operations).

use std::path::Path;

use anyhow::{anyhow, bail, Result};
use hd_crank::hd::{self, HdConfig};
use hd_crank::ore::{Board, OreConfig, Round, Treasury, BOARD_ADDRESS, CONFIG_ADDRESS, TREASURY_ADDRESS};
use serde_json::json;
use solana_address::Address;
use solana_keypair::Keypair;
use solana_signer::Signer;

use crate::hd as hdix;
use crate::ops::{program_state, ProgramState};
use crate::ore::{self, var};
use crate::phone::Phone;
use crate::genesis::{ONE_SKR, SKR_MINT};
use crate::util::{b32_at, read_keypair, u64_at, Chain, SOL};
use solana_instruction::{AccountMeta, Instruction};

/// Airdrop `sol` to `to` on the local validator.
pub async fn fund(rpc: &str, to: &Address, sol: f64) -> Result<()> {
    let chain = Chain::new(rpc)?;
    let lamports = (sol * SOL as f64) as u64;
    chain.airdrop(to, lamports).await?;
    println!("funded {to}: balance {} SOL", chain.balance(to).await? as f64 / SOL as f64);
    Ok(())
}

/// Mint `skr` test SKR to `to`'s associated token account, from the local stand-in mint
/// (`genesis --skr-authority`). `authority` is that mint's authority keypair; it pays the fee and
/// the token account's rent out of an airdrop.
pub async fn fund_skr(rpc: &str, to: &Address, skr: f64, authority: &Path) -> Result<()> {
    let chain = Chain::new(rpc)?;
    let auth = read_keypair(authority)?;
    let (owner, mint, _) = chain.data(&SKR_MINT).await?.ok_or_else(|| anyhow!("no SKR mint on this chain: start the stack with up.sh"))?;
    if owner != ore::TOKEN_PROGRAM_ID || mint.len() != 82 || mint[0..4] != [1, 0, 0, 0] || b32_at(&mint, 4) != auth.pubkey().to_bytes() {
        bail!("the SKR mint on this chain is not the local stand-in of {}", auth.pubkey());
    }
    if !(skr > 0.0 && skr <= 1_000_000.0) {
        bail!("amount must be above 0 and at most 1,000,000 SKR");
    }
    let units = (skr * ONE_SKR as f64).round() as u64;
    if chain.balance(&auth.pubkey()).await? < SOL / 10 {
        chain.airdrop(&auth.pubkey(), SOL).await?;
    }
    let account = Address::find_program_address(&[to.as_ref(), ore::TOKEN_PROGRAM_ID.as_ref(), SKR_MINT.as_ref()], &ore::ATA_PROGRAM_ID).0;
    // Associated Token Account `CreateIdempotent` (1), then SPL Token `MintTo` (7).
    let create = Instruction {
        program_id: ore::ATA_PROGRAM_ID,
        accounts: vec![
            AccountMeta::new(auth.pubkey(), true),
            AccountMeta::new(account, false),
            AccountMeta::new_readonly(*to, false),
            AccountMeta::new_readonly(SKR_MINT, false),
            AccountMeta::new_readonly(hd_crank::ore::SYSTEM_PROGRAM_ID, false),
            AccountMeta::new_readonly(ore::TOKEN_PROGRAM_ID, false),
        ],
        data: vec![1],
    };
    let mut data = vec![7u8];
    data.extend_from_slice(&units.to_le_bytes());
    let mint_to = Instruction {
        program_id: ore::TOKEN_PROGRAM_ID,
        accounts: vec![AccountMeta::new(SKR_MINT, false), AccountMeta::new(account, false), AccountMeta::new_readonly(auth.pubkey(), true)],
        data,
    };
    chain.send(&auth, &[create, mint_to]).await?;
    let (_, token, _) = chain.data(&account).await?.ok_or_else(|| anyhow!("the SKR account was not created"))?;
    println!("minted {skr} test SKR to {to}: account {account} holds {} SKR", u64_at(&token, 64) as f64 / ONE_SKR as f64);
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
    match program_state(&chain, &hd::PROGRAM_ID).await? {
        ProgramState::Deployed { programdata, slot, authority, programdata_len, .. } => println!(
            "program         {} ProgramData {programdata} ({programdata_len} bytes), last deploy slot {slot}, upgrade authority {}",
            hd::PROGRAM_ID,
            authority.map_or_else(|| "none (immutable)".to_string(), |a| a.to_string())
        ),
        ProgramState::Absent => println!("program         {} not deployed", hd::PROGRAM_ID),
        ProgramState::Other(why) => println!("program         {}: {why}", hd::PROGRAM_ID),
    }
    match chain.data(&hdix::config()).await? {
        Some((o, d, _)) => {
            let hc = HdConfig::decode(&hd::PROGRAM_ID, &o, &d).map_err(|e| anyhow!("Config: {e}"))?;
            println!("heads_down      Config {} executor_fee {} crank_fee {} paused {}", hdix::config(), hc.executor_fee, hc.crank_fee, hc.paused);
            println!("governance      {} registrar {} bury_bps {}", hc.governance, hc.registrar, hc.bury_bps);
            if let Some(p) = hdix::pending(&d).filter(|p| p.exists) {
                println!(
                    "pending         registrar {} crank_fee {} bury_bps {} paused {}; apply_config from slot {} ({} slots to go)",
                    p.registrar,
                    p.crank_fee,
                    p.bury_bps,
                    p.paused,
                    p.eta_slot,
                    p.eta_slot.saturating_sub(slot)
                );
            }
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
