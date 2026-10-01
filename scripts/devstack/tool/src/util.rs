//! RPC, transaction and keypair helpers shared by every subcommand.

use std::path::Path;
use std::time::Duration;

use anyhow::{anyhow, bail, Result};
use hd_crank::rpc::RpcClient;
use hd_crank::sender::{ConfirmPolicy, Outcome, Submitter};
use hd_crank::tx;
use serde_json::{json, Value};
use solana_address::Address;
use solana_instruction::Instruction;
use solana_keypair::Keypair;
use solana_message::VersionedMessage;
use solana_signer::Signer;

/// Lamports per SOL.
pub const SOL: u64 = 1_000_000_000;

/// A confirmed transaction.
#[derive(Debug, Clone)]
pub struct Landed {
    /// Base58 signature.
    pub signature: String,
    /// Slot it landed in.
    pub slot: u64,
    /// Program logs (`getTransaction`).
    pub logs: Vec<String>,
}

/// JSON-RPC access to the local cluster.
#[derive(Clone)]
pub struct Chain {
    /// The crank's JSON-RPC client (confirmed commitment).
    pub rpc: RpcClient,
}

impl Chain {
    /// Connect (lazily) to `url`.
    pub fn new(url: &str) -> Result<Self> {
        let rpc = RpcClient::new(url.to_string(), "confirmed", Duration::from_secs(15))
            .map_err(|e| anyhow!("rpc client: {e}"))?;
        Ok(Chain { rpc })
    }

    /// Raw account bytes, or `None` if the account does not exist.
    pub async fn data(&self, key: &Address) -> Result<Option<(Address, Vec<u8>, u64)>> {
        let a = self.rpc.get_account(key).await.map_err(|e| anyhow!("getAccountInfo {key}: {e}"))?;
        Ok(a.map(|a| (a.owner, a.data, a.lamports)))
    }

    /// Current slot at `confirmed`.
    pub async fn slot(&self) -> Result<u64> {
        self.rpc.get_slot("confirmed").await.map_err(|e| anyhow!("getSlot: {e}"))
    }

    /// Lamport balance.
    pub async fn balance(&self, key: &Address) -> Result<u64> {
        self.rpc.get_balance(key).await.map_err(|e| anyhow!("getBalance: {e}"))
    }

    /// `requestAirdrop` and wait until the balance shows it.
    pub async fn airdrop(&self, to: &Address, lamports: u64) -> Result<()> {
        let before = self.balance(to).await.unwrap_or(0);
        let sig = self
            .rpc
            .call("requestAirdrop", json!([to.to_string(), lamports, { "commitment": "confirmed" }]))
            .await
            .map_err(|e| anyhow!("requestAirdrop {to}: {e}"))?;
        let sig = sig.as_str().unwrap_or_default().to_string();
        for _ in 0..120 {
            tokio::time::sleep(Duration::from_millis(500)).await;
            if self.balance(to).await.unwrap_or(0) >= before.saturating_add(lamports) {
                return Ok(());
            }
        }
        bail!("airdrop {sig} to {to} did not confirm")
    }

    /// Sign with `payer` (the only signer), simulate, send, confirm, and return the logs.
    /// A failing simulation is reported with its logs instead of being sent.
    pub async fn send(&self, payer: &Keypair, ixs: &[Instruction]) -> Result<Landed> {
        self.send_opts(payer, ixs, true).await
    }

    /// Like [`Chain::send`]; `preflight = false` skips the simulation (used to land a
    /// transaction whose on-chain outcome is itself the thing being tested).
    pub async fn send_opts(&self, payer: &Keypair, ixs: &[Instruction], preflight: bool) -> Result<Landed> {
        let (bh, lvbh) = self.rpc.get_latest_blockhash().await.map_err(|e| anyhow!("getLatestBlockhash: {e}"))?;
        let msg = VersionedMessage::Legacy(solana_message::Message::new_with_blockhash(ixs, Some(&payer.pubkey()), &bh));
        let t = tx::make_transaction(msg, Some(payer)).map_err(|e| anyhow!("sign: {e}"))?;
        let wire = tx::serialize(&t).map_err(|e| anyhow!("serialize: {e}"))?;
        let sig = t.signatures.first().map(ToString::to_string).unwrap_or_default();
        if preflight {
            let sim = self.rpc.simulate_transaction(&wire).await.map_err(|e| anyhow!("simulate: {e}"))?;
            if let Some(err) = sim.err {
                bail!("simulation failed: {err}\n  {}", tail(&sim.logs, 25).join("\n  "));
            }
        }
        let s = Submitter::rpc(self.rpc.clone());
        s.send(&wire).await.map_err(|e| anyhow!("sendTransaction: {e}"))?;
        let policy = ConfirmPolicy { max_wait: Duration::from_secs(60), ..ConfirmPolicy::default() };
        match s.confirm(&sig, &wire, lvbh, policy).await {
            Outcome::Landed { slot, err } => {
                let logs = self.logs(&sig).await.unwrap_or_default();
                if let Some(err) = err {
                    bail!("tx {sig} failed on-chain: {err}\n  {}", tail(&logs, 25).join("\n  "));
                }
                Ok(Landed { signature: sig, slot, logs })
            }
            other => bail!("tx {sig}: {other:?}"),
        }
    }

    /// Logs of a confirmed transaction (retries while the RPC catches up).
    pub async fn logs(&self, sig: &str) -> Result<Vec<String>> {
        for _ in 0..20 {
            if let Ok(Some(info)) = self.rpc.get_transaction(sig, 0).await {
                return Ok(info.logs);
            }
            tokio::time::sleep(Duration::from_millis(300)).await;
        }
        bail!("getTransaction {sig}: not found")
    }

    /// Raw JSON-RPC call.
    pub async fn call(&self, method: &str, params: Value) -> Result<Value> {
        self.rpc.call(method, params).await.map_err(|e| anyhow!("{method}: {e}"))
    }

    /// `getGenesisHash`.
    pub async fn genesis_hash(&self) -> Result<String> {
        let v = self.call("getGenesisHash", json!([])).await?;
        v.as_str().map(str::to_string).ok_or_else(|| anyhow!("getGenesisHash: not a string"))
    }

    /// `getMinimumBalanceForRentExemption(len)`: the cluster's own figure, not a formula.
    pub async fn rent(&self, len: u64) -> Result<u64> {
        self.call("getMinimumBalanceForRentExemption", json!([len]))
            .await?
            .as_u64()
            .ok_or_else(|| anyhow!("getMinimumBalanceForRentExemption({len}): not a number"))
    }

    /// Like [`Chain::send`], with a compute-unit limit sized from a simulation (+20%) and,
    /// when `cu_price > 0`, a priority fee of `cu_price` micro-lamports per CU. Mainnet
    /// admin transactions use this so they land under load.
    pub async fn send_budgeted(&self, payer: &Keypair, ixs: &[Instruction], cu_price: u64) -> Result<Landed> {
        let mut probe = vec![tx::set_compute_unit_limit(tx::MAX_COMPUTE_UNITS)];
        probe.extend_from_slice(ixs);
        let (bh, _) = self.rpc.get_latest_blockhash().await.map_err(|e| anyhow!("getLatestBlockhash: {e}"))?;
        let msg = VersionedMessage::Legacy(solana_message::Message::new_with_blockhash(&probe, Some(&payer.pubkey()), &bh));
        let t = tx::make_transaction(msg, Some(payer)).map_err(|e| anyhow!("sign: {e}"))?;
        let wire = tx::serialize(&t).map_err(|e| anyhow!("serialize: {e}"))?;
        let sim = self.rpc.simulate_transaction(&wire).await.map_err(|e| anyhow!("simulate: {e}"))?;
        if let Some(err) = sim.err {
            bail!("simulation failed: {err}\n  {}", tail(&sim.logs, 25).join("\n  "));
        }
        let used = sim.units_consumed.unwrap_or(200_000);
        let limit = u32::try_from(used.saturating_mul(12) / 10 + 1_000).unwrap_or(tx::MAX_COMPUTE_UNITS).min(tx::MAX_COMPUTE_UNITS);
        let mut out = vec![tx::set_compute_unit_limit(limit)];
        if cu_price > 0 {
            out.push(tx::set_compute_unit_price(cu_price));
        }
        out.extend_from_slice(ixs);
        self.send(payer, &out).await
    }

    /// Fee and error of a landed transaction (`getTransaction`, confirmed).
    pub async fn tx_fee(&self, sig: &str) -> Result<(u64, u64, Option<Value>)> {
        for _ in 0..20 {
            if let Ok(Some(info)) = self.rpc.get_transaction(sig, 0).await {
                return Ok((info.slot, info.fee, info.err));
            }
            tokio::time::sleep(Duration::from_millis(500)).await;
        }
        bail!("getTransaction {sig}: not found")
    }
}

/// Seconds since the Unix epoch.
pub fn unix_now() -> i64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_secs() as i64).unwrap_or(0)
}

/// `YYYY-MM-DDTHH:MM:SSZ` for a Unix time (UTC; civil-from-days, no time-zone database).
pub fn rfc3339(unix: i64) -> String {
    let days = unix.div_euclid(86_400);
    let secs = unix.rem_euclid(86_400);
    let z = days + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z - era * 146_097;
    let yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = doy - (153 * mp + 2) / 5 + 1;
    let m = if mp < 10 { mp + 3 } else { mp - 9 };
    let y = yoe + era * 400 + i64::from(m <= 2);
    format!("{y:04}-{m:02}-{d:02}T{:02}:{:02}:{:02}Z", secs / 3600, (secs / 60) % 60, secs % 60)
}

/// Lamports as SOL with 9 decimals (exact; no float).
pub fn sol(lamports: u64) -> String {
    format!("{}.{:09}", lamports / SOL, lamports % SOL)
}

/// The last `n` lines.
pub fn tail(lines: &[String], n: usize) -> Vec<String> {
    lines.iter().skip(lines.len().saturating_sub(n)).cloned().collect()
}

/// Read a Solana CLI JSON keypair (must be mode 600, like the crank requires).
pub fn read_keypair(path: &Path) -> Result<Keypair> {
    let k = hd_crank::keys::load_keypair(path)?;
    Ok(k.keypair().insecure_clone())
}

/// 32 bytes from the OS CSPRNG.
pub fn random32() -> Result<[u8; 32]> {
    use std::io::Read;
    let mut b = [0u8; 32];
    std::fs::File::open("/dev/urandom")?.read_exact(&mut b)?;
    Ok(b)
}

/// Little-endian u64 at `off` (0 when out of range).
pub fn u64_at(d: &[u8], off: usize) -> u64 {
    d.get(off..off + 8).and_then(|s| s.try_into().ok()).map(u64::from_le_bytes).unwrap_or(0)
}

/// Overwrite a little-endian u64 at `off`.
pub fn put_u64(d: &mut [u8], off: usize, v: u64) -> Result<()> {
    d.get_mut(off..off + 8).ok_or_else(|| anyhow!("offset {off} out of range"))?.copy_from_slice(&v.to_le_bytes());
    Ok(())
}

/// Overwrite 32 bytes at `off`.
pub fn put32(d: &mut [u8], off: usize, v: &[u8; 32]) -> Result<()> {
    d.get_mut(off..off + 32).ok_or_else(|| anyhow!("offset {off} out of range"))?.copy_from_slice(v);
    Ok(())
}

/// 32 bytes at `off`.
pub fn b32_at(d: &[u8], off: usize) -> [u8; 32] {
    d.get(off..off + 32).and_then(|s| s.try_into().ok()).unwrap_or([0; 32])
}

/// Rent-exempt minimum for `len` bytes at the default rent (3,480 lamports/byte-year, 2 years).
pub fn rent_exempt(len: usize) -> u64 {
    (len as u64 + 128) * 3_480 * 2
}

/// Poll `f` until it returns `Some`, or fail after `timeout`.
pub async fn wait_for<T, F, Fut>(what: &str, timeout: Duration, every: Duration, mut f: F) -> Result<T>
where
    F: FnMut() -> Fut,
    Fut: std::future::Future<Output = Result<Option<T>>>,
{
    let start = std::time::Instant::now();
    loop {
        match f().await {
            Ok(Some(v)) => return Ok(v),
            Ok(None) => {}
            Err(e) if start.elapsed() >= timeout => return Err(e.context(format!("waiting for {what}"))),
            Err(_) => {}
        }
        if start.elapsed() >= timeout {
            bail!("timed out after {:?} waiting for {what}", timeout);
        }
        tokio::time::sleep(every).await;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rfc3339_known_dates() {
        assert_eq!(rfc3339(0), "1970-01-01T00:00:00Z");
        assert_eq!(rfc3339(951_782_400), "2000-02-29T00:00:00Z");
        assert_eq!(rfc3339(1_790_812_800), "2026-10-01T00:00:00Z");
        assert_eq!(rfc3339(1_790_899_199), "2026-10-01T23:59:59Z");
    }

    #[test]
    fn sol_formatting_is_exact() {
        assert_eq!(sol(0), "0.000000000");
        assert_eq!(sol(1_369_595_760), "1.369595760");
        assert_eq!(sol(u64::MAX), "18446744073.709551615");
    }

    #[test]
    fn rent_formula_matches_known_values() {
        assert_eq!(rent_exempt(0), 890_880);
        assert_eq!(rent_exempt(256), 2_672_640);
    }
}
