//! Minimal Solana JSON-RPC client (HTTP), just the calls the crank makes.
//!
//! Written against the public JSON-RPC API rather than pulling `solana-rpc-client`, so the
//! dependency tree stays small and every response field the crank trusts is parsed
//! explicitly. **The RPC URL is treated as a secret** (Helius keys live in its query
//! string): it is never logged, and `reqwest` errors are stripped of their URL.

use std::time::Duration;

use base64::Engine;
use serde::Deserialize;
use serde_json::{json, Value};
use solana_address::Address;
use solana_hash::Hash;

use crate::account::RawAccount;
use crate::hd::{self, Rig};
use crate::heartbeat::RigSource;

/// RPC failures.
#[derive(Debug, thiserror::Error)]
pub enum RpcError {
    /// Transport error (URL stripped).
    #[error("http: {0}")]
    Http(String),
    /// JSON-RPC error object.
    #[error("rpc error {code}: {message}")]
    Rpc {
        /// Code.
        code: i64,
        /// Message.
        message: String,
    },
    /// Unexpected response shape.
    #[error("decode: {0}")]
    Decode(String),
}

/// Keep only scheme and host: providers put keys in the query (`?api-key=`), the path
/// (`/<token>/`) or userinfo (`user:pass@`), so everything else is replaced.
pub fn redact_url(url: &str) -> String {
    let Some((scheme, rest)) = url.split_once("://") else {
        return "<redacted>".to_string();
    };
    let end = rest.find(['/', '?', '#']).unwrap_or(rest.len());
    let (authority, tail) = rest.split_at(end);
    let host = match authority.rsplit_once('@') {
        Some((_, h)) => format!("<redacted>@{h}"),
        None => authority.to_string(),
    };
    if tail.is_empty() || tail == "/" {
        format!("{scheme}://{host}{tail}")
    } else {
        format!("{scheme}://{host}/<redacted>")
    }
}

/// Remove `secret_url` (and any `api-key=` value) from an error message before logging it:
/// some transport errors echo the request URL.
pub fn scrub(message: &str, secret_url: &str) -> String {
    let mut s = if secret_url.is_empty() { message.to_string() } else { message.replace(secret_url, &redact_url(secret_url)) };
    while let Some(i) = s.to_ascii_lowercase().find("api-key=") {
        let start = i + "api-key=".len();
        let end = s[start..].find(|c: char| !(c.is_ascii_alphanumeric() || c == '-' || c == '_')).map_or(s.len(), |j| start + j);
        s.replace_range(i..end, "<redacted>");
    }
    s
}

/// A getProgramAccounts filter.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Filter {
    /// `dataSize`.
    DataSize(u64),
    /// `memcmp` at `offset` against `bytes`.
    Memcmp {
        /// Offset.
        offset: usize,
        /// Bytes to match.
        bytes: Vec<u8>,
    },
}

impl Filter {
    fn to_json(&self) -> Value {
        match self {
            Filter::DataSize(n) => json!({ "dataSize": n }),
            Filter::Memcmp { offset, bytes } => {
                json!({ "memcmp": { "offset": offset, "bytes": bs58::encode(bytes).into_string(), "encoding": "base58" } })
            }
        }
    }
}

/// `simulateTransaction` result.
#[derive(Clone, Debug, Default)]
pub struct Simulation {
    /// Transaction error, if any.
    pub err: Option<Value>,
    /// Logs.
    pub logs: Vec<String>,
    /// Compute units consumed.
    pub units_consumed: Option<u64>,
}

/// One `getSignatureStatuses` entry.
#[derive(Clone, Debug, PartialEq)]
pub struct SignatureStatus {
    /// Slot it landed in.
    pub slot: u64,
    /// `processed` / `confirmed` / `finalized`.
    pub confirmation_status: Option<String>,
    /// Transaction error, if any.
    pub err: Option<Value>,
}

impl SignatureStatus {
    /// Confirmed or finalized.
    pub fn is_confirmed(&self) -> bool {
        matches!(self.confirmation_status.as_deref(), Some("confirmed") | Some("finalized"))
    }
}

/// `getTransaction` essentials.
#[derive(Clone, Debug, Default)]
pub struct TransactionInfo {
    /// Slot.
    pub slot: u64,
    /// Error, if any.
    pub err: Option<Value>,
    /// Fee paid.
    pub fee: u64,
    /// CU consumed.
    pub compute_units: Option<u64>,
    /// Logs.
    pub logs: Vec<String>,
}

#[derive(Deserialize)]
struct RpcResponse {
    result: Option<Value>,
    error: Option<RpcErrorObj>,
}

#[derive(Deserialize)]
struct RpcErrorObj {
    code: i64,
    message: String,
}

/// JSON-RPC client.
#[derive(Clone)]
pub struct RpcClient {
    http: reqwest::Client,
    url: String,
    commitment: String,
}

impl std::fmt::Debug for RpcClient {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("RpcClient").field("url", &redact_url(&self.url)).field("commitment", &self.commitment).finish()
    }
}

pub(crate) fn decode_account(v: &Value) -> Result<Option<RawAccount>, RpcError> {
    if v.is_null() {
        return Ok(None);
    }
    let owner: Address = v["owner"]
        .as_str()
        .and_then(|s| s.parse().ok())
        .ok_or_else(|| RpcError::Decode("owner".into()))?;
    let lamports = v["lamports"].as_u64().ok_or_else(|| RpcError::Decode("lamports".into()))?;
    let data = match &v["data"] {
        Value::Array(a) if a.get(1).and_then(Value::as_str) == Some("base64") => base64::engine::general_purpose::STANDARD
            .decode(a.first().and_then(Value::as_str).unwrap_or(""))
            .map_err(|e| RpcError::Decode(format!("data: {e}")))?,
        _ => return Err(RpcError::Decode("data encoding".into())),
    };
    Ok(Some(RawAccount { owner, lamports, data }))
}

impl RpcClient {
    /// New client. `commitment` is used for reads and preflight.
    pub fn new(url: impl Into<String>, commitment: impl Into<String>, timeout: Duration) -> Result<Self, RpcError> {
        let http = reqwest::Client::builder()
            .timeout(timeout)
            .connect_timeout(Duration::from_secs(5))
            .build()
            .map_err(|e| RpcError::Http(e.without_url().to_string()))?;
        Ok(RpcClient { http, url: url.into(), commitment: commitment.into() })
    }

    /// Commitment used for reads.
    pub fn commitment(&self) -> &str {
        &self.commitment
    }

    /// Raw call.
    pub async fn call(&self, method: &str, params: Value) -> Result<Value, RpcError> {
        let body = json!({ "jsonrpc": "2.0", "id": 1, "method": method, "params": params });
        let resp = self
            .http
            .post(&self.url)
            .json(&body)
            .send()
            .await
            .map_err(|e| RpcError::Http(e.without_url().to_string()))?;
        let status = resp.status();
        let parsed: RpcResponse = resp.json().await.map_err(|e| {
            RpcError::Decode(format!("{method}: http {status}: {}", e.without_url()))
        })?;
        if let Some(e) = parsed.error {
            return Err(RpcError::Rpc { code: e.code, message: e.message });
        }
        parsed.result.ok_or_else(|| RpcError::Decode(format!("{method}: no result")))
    }

    /// `getMultipleAccounts`, chunked by 100, order preserved.
    pub async fn get_multiple_accounts(&self, keys: &[Address]) -> Result<Vec<Option<RawAccount>>, RpcError> {
        let mut out = Vec::with_capacity(keys.len());
        for chunk in keys.chunks(100) {
            let ks: Vec<String> = chunk.iter().map(ToString::to_string).collect();
            let r = self
                .call("getMultipleAccounts", json!([ks, { "encoding": "base64", "commitment": self.commitment }]))
                .await?;
            let arr = r["value"].as_array().ok_or_else(|| RpcError::Decode("value".into()))?;
            if arr.len() != chunk.len() {
                return Err(RpcError::Decode("getMultipleAccounts length".into()));
            }
            for v in arr {
                out.push(decode_account(v)?);
            }
        }
        Ok(out)
    }

    /// `getAccountInfo`.
    pub async fn get_account(&self, key: &Address) -> Result<Option<RawAccount>, RpcError> {
        let r = self
            .call("getAccountInfo", json!([key.to_string(), { "encoding": "base64", "commitment": self.commitment }]))
            .await?;
        decode_account(&r["value"])
    }

    /// `getAccountInfo` with a `dataSlice` (e.g. ORE's 400 KB ProgramData header only).
    pub async fn get_account_slice(&self, key: &Address, offset: usize, length: usize) -> Result<Option<RawAccount>, RpcError> {
        let r = self
            .call(
                "getAccountInfo",
                json!([key.to_string(), { "encoding": "base64", "commitment": self.commitment, "dataSlice": { "offset": offset, "length": length } }]),
            )
            .await?;
        decode_account(&r["value"])
    }

    /// `getProgramAccounts` with filters.
    pub async fn get_program_accounts(&self, program: &Address, filters: &[Filter]) -> Result<Vec<(Address, RawAccount)>, RpcError> {
        let f: Vec<Value> = filters.iter().map(Filter::to_json).collect();
        let r = self
            .call(
                "getProgramAccounts",
                json!([program.to_string(), { "encoding": "base64", "commitment": self.commitment, "filters": f }]),
            )
            .await?;
        let arr = r.as_array().ok_or_else(|| RpcError::Decode("getProgramAccounts".into()))?;
        let mut out = Vec::with_capacity(arr.len());
        for item in arr {
            let key: Address = item["pubkey"]
                .as_str()
                .and_then(|s| s.parse().ok())
                .ok_or_else(|| RpcError::Decode("pubkey".into()))?;
            if let Some(acc) = decode_account(&item["account"])? {
                out.push((key, acc));
            }
        }
        Ok(out)
    }

    /// `getLatestBlockhash` → (blockhash, last valid block height).
    pub async fn get_latest_blockhash(&self) -> Result<(Hash, u64), RpcError> {
        let r = self.call("getLatestBlockhash", json!([{ "commitment": self.commitment }])).await?;
        let bh: Hash = r["value"]["blockhash"]
            .as_str()
            .and_then(|s| s.parse().ok())
            .ok_or_else(|| RpcError::Decode("blockhash".into()))?;
        let lvbh = r["value"]["lastValidBlockHeight"].as_u64().ok_or_else(|| RpcError::Decode("lastValidBlockHeight".into()))?;
        Ok((bh, lvbh))
    }

    /// `getSlot` at `commitment`.
    pub async fn get_slot(&self, commitment: &str) -> Result<u64, RpcError> {
        self.call("getSlot", json!([{ "commitment": commitment }]))
            .await?
            .as_u64()
            .ok_or_else(|| RpcError::Decode("slot".into()))
    }

    /// `getBlockHeight`.
    pub async fn get_block_height(&self) -> Result<u64, RpcError> {
        self.call("getBlockHeight", json!([{ "commitment": self.commitment }]))
            .await?
            .as_u64()
            .ok_or_else(|| RpcError::Decode("blockHeight".into()))
    }

    /// `getBalance`.
    pub async fn get_balance(&self, key: &Address) -> Result<u64, RpcError> {
        self.call("getBalance", json!([key.to_string(), { "commitment": self.commitment }]))
            .await?["value"]
            .as_u64()
            .ok_or_else(|| RpcError::Decode("balance".into()))
    }

    /// `sendTransaction` (base64, `maxRetries: 0`: the crank rebroadcasts itself).
    pub async fn send_transaction(&self, wire: &[u8], skip_preflight: bool) -> Result<String, RpcError> {
        let b64 = base64::engine::general_purpose::STANDARD.encode(wire);
        let r = self
            .call(
                "sendTransaction",
                json!([b64, { "encoding": "base64", "skipPreflight": skip_preflight, "maxRetries": 0, "preflightCommitment": self.commitment }]),
            )
            .await?;
        r.as_str().map(str::to_string).ok_or_else(|| RpcError::Decode("signature".into()))
    }

    /// `simulateTransaction` (no sig verify, keeps the blockhash).
    pub async fn simulate_transaction(&self, wire: &[u8]) -> Result<Simulation, RpcError> {
        let b64 = base64::engine::general_purpose::STANDARD.encode(wire);
        let r = self
            .call(
                "simulateTransaction",
                json!([b64, { "encoding": "base64", "sigVerify": false, "replaceRecentBlockhash": false, "commitment": self.commitment }]),
            )
            .await?;
        let v = &r["value"];
        Ok(Simulation {
            err: v.get("err").filter(|e| !e.is_null()).cloned(),
            logs: v["logs"].as_array().map(|a| a.iter().filter_map(|l| l.as_str().map(str::to_string)).collect()).unwrap_or_default(),
            units_consumed: v["unitsConsumed"].as_u64(),
        })
    }

    /// `getSignatureStatuses` (recent cache only).
    pub async fn get_signature_statuses(&self, sigs: &[String]) -> Result<Vec<Option<SignatureStatus>>, RpcError> {
        let r = self.call("getSignatureStatuses", json!([sigs, { "searchTransactionHistory": false }])).await?;
        let arr = r["value"].as_array().ok_or_else(|| RpcError::Decode("statuses".into()))?;
        Ok(arr
            .iter()
            .map(|v| {
                if v.is_null() {
                    None
                } else {
                    Some(SignatureStatus {
                        slot: v["slot"].as_u64().unwrap_or(0),
                        confirmation_status: v["confirmationStatus"].as_str().map(str::to_string),
                        err: v.get("err").filter(|e| !e.is_null()).cloned(),
                    })
                }
            })
            .collect())
    }

    /// `getTransaction` (json, confirmed).
    pub async fn get_transaction(&self, sig: &str, max_version: u8) -> Result<Option<TransactionInfo>, RpcError> {
        let r = self
            .call(
                "getTransaction",
                json!([sig, { "encoding": "json", "commitment": "confirmed", "maxSupportedTransactionVersion": max_version }]),
            )
            .await?;
        if r.is_null() {
            return Ok(None);
        }
        let meta = &r["meta"];
        Ok(Some(TransactionInfo {
            slot: r["slot"].as_u64().unwrap_or(0),
            err: meta.get("err").filter(|e| !e.is_null()).cloned(),
            fee: meta["fee"].as_u64().unwrap_or(0),
            compute_units: meta["computeUnitsConsumed"].as_u64(),
            logs: meta["logMessages"]
                .as_array()
                .map(|a| a.iter().filter_map(|l| l.as_str().map(str::to_string)).collect())
                .unwrap_or_default(),
        }))
    }

    /// `getRecentPrioritizationFees` for the accounts a dig write-locks.
    pub async fn get_recent_prioritization_fees(&self, accounts: &[Address]) -> Result<Vec<u64>, RpcError> {
        let ks: Vec<String> = accounts.iter().map(ToString::to_string).collect();
        let r = self.call("getRecentPrioritizationFees", json!([ks])).await?;
        Ok(r.as_array()
            .map(|a| a.iter().filter_map(|v| v["prioritizationFee"].as_u64()).collect())
            .unwrap_or_default())
    }
}

/// The intake's rig lookups go straight to RPC (behind the intake's cache).
pub struct RpcRigSource {
    /// Client.
    pub rpc: RpcClient,
    /// heads_down program id.
    pub program_id: Address,
}

#[async_trait::async_trait]
impl RigSource for RpcRigSource {
    async fn fetch_rig(&self, rig: &Address) -> anyhow::Result<Option<Rig>> {
        Ok(self
            .rpc
            .get_account(rig)
            .await?
            .and_then(|a| Rig::decode(&self.program_id, &a.owner, &a.data).ok()))
    }
}

/// `p`-th percentile (0..=100) of recent priority fees, clamped to `[floor, cap]`.
pub fn priority_fee_from_samples(mut samples: Vec<u64>, percentile: u8, floor: u64, cap: u64) -> u64 {
    if samples.is_empty() {
        return floor.min(cap);
    }
    samples.sort_unstable();
    let idx = (usize::from(percentile.min(100)) * (samples.len() - 1)) / 100;
    samples[idx].clamp(floor, cap.max(floor))
}

/// Filters for the Rig accounts in `state` (tag, size and the state byte).
pub fn rig_filters(state: hd::RigState) -> Vec<Filter> {
    vec![
        Filter::DataSize(hd::RIG_LEN as u64),
        Filter::Memcmp { offset: 0, bytes: vec![hd::TAG_RIG, hd::ACCOUNT_VERSION] },
        Filter::Memcmp { offset: hd::RIG_STATE_OFFSET, bytes: vec![state.as_u8()] },
    ]
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn redaction_hides_keys() {
        assert_eq!(
            redact_url("https://mainnet.helius-rpc.com/?api-key=SECRET"),
            "https://mainnet.helius-rpc.com/<redacted>"
        );
        assert_eq!(redact_url("https://x.quiknode.pro/SECRET/"), "https://x.quiknode.pro/<redacted>");
        assert_eq!(redact_url("wss://user:pw@host.example/ws"), "wss://<redacted>@host.example/<redacted>");
        assert_eq!(redact_url("http://127.0.0.1:8899"), "http://127.0.0.1:8899");
        assert_eq!(redact_url("https://api.mainnet-beta.solana.com/"), "https://api.mainnet-beta.solana.com/");
        assert_eq!(redact_url("garbage"), "<redacted>");
        let url = "wss://mainnet.helius-rpc.com/?api-key=abc-123";
        let msg = format!("connect to {url} failed; retry api-key=abc-123&x=1");
        let s = scrub(&msg, url);
        assert!(!s.contains("abc-123"), "{s}");
        assert!(s.contains("mainnet.helius-rpc.com"));
        assert!(!format!("{:?}", RpcClient::new("https://x/?api-key=S", "confirmed", Duration::from_secs(1)).unwrap()).contains('S'));
    }

    #[test]
    fn filters_encode_base58() {
        let f = rig_filters(hd::RigState::Down);
        let j: Vec<Value> = f.iter().map(Filter::to_json).collect();
        assert_eq!(j[0], json!({ "dataSize": 384 }));
        assert_eq!(j[1]["memcmp"]["offset"], 0);
        assert_eq!(j[1]["memcmp"]["bytes"], bs58::encode([2u8, 1]).into_string());
        assert_eq!(j[2]["memcmp"]["offset"], 75);
        assert_eq!(j[2]["memcmp"]["bytes"], "3");
    }

    #[test]
    fn priority_percentile() {
        assert_eq!(priority_fee_from_samples(vec![], 75, 100, 10_000), 100);
        assert_eq!(priority_fee_from_samples(vec![0, 10, 20, 30, 40], 50, 0, 1_000), 20);
        assert_eq!(priority_fee_from_samples(vec![5, 1_000_000], 100, 0, 50_000), 50_000);
        assert_eq!(priority_fee_from_samples(vec![0, 0, 0], 90, 1_000, 50_000), 1_000);
    }

    #[test]
    fn account_decoding() {
        let v = json!({ "owner": "11111111111111111111111111111111", "lamports": 5, "data": ["AQID", "base64"], "executable": false });
        let a = decode_account(&v).unwrap().unwrap();
        assert_eq!(a.data, vec![1, 2, 3]);
        assert_eq!(decode_account(&Value::Null).unwrap(), None);
        assert!(decode_account(&json!({ "owner": "11111111111111111111111111111111", "lamports": 5, "data": "AQID" })).is_err());
        assert!(decode_account(&json!({ "owner": "not-base58!", "lamports": 5, "data": ["", "base64"] })).is_err());
    }
}
