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

/// How long the running crank waits for one RPC answer.
pub const RPC_TIMEOUT: Duration = Duration::from_secs(10);

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
    /// The answer is as of a slot before the one the read asked for (`minContextSlot`).
    #[error("answered as of slot {slot}, before slot {min} that the read asked for")]
    Behind {
        /// The slot in the answer's context.
        slot: u64,
        /// The `minContextSlot` of the request.
        min: u64,
    },
}

/// Is `result` (an answer with a `context`) as of `min_context_slot` or later? A node that
/// honors `minContextSlot` never answers from before it. This is for one that does not: its
/// answer carries the slot it is as of, and an older one is refused here.
fn check_context_slot(result: &Value, min_context_slot: u64) -> Result<(), RpcError> {
    if min_context_slot == 0 {
        return Ok(());
    }
    match result["context"]["slot"].as_u64() {
        Some(slot) if slot >= min_context_slot => Ok(()),
        Some(slot) => Err(RpcError::Behind { slot, min: min_context_slot }),
        None => Err(RpcError::Decode("context slot".into())),
    }
}

pub use crate::redact::{redact_url, scrub};

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

/// One compiled instruction of a fetched transaction, resolved to addresses.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct FetchedInstruction {
    /// Program id.
    pub program_id: Address,
    /// Account addresses in instruction order.
    pub accounts: Vec<Address>,
    /// Instruction data.
    pub data: Vec<u8>,
}

/// A fetched transaction: status, cost, logs and the top-level instructions.
#[derive(Clone, Debug, Default)]
pub struct FullTransaction {
    /// Slot.
    pub slot: u64,
    /// Block time, if the node has it.
    pub block_time: Option<i64>,
    /// Error, if any.
    pub err: Option<Value>,
    /// Fee paid.
    pub fee: u64,
    /// CU consumed.
    pub compute_units: Option<u64>,
    /// Logs.
    pub logs: Vec<String>,
    /// Fee payer (the first account key).
    pub fee_payer: Option<Address>,
    /// Top-level instructions in order.
    pub instructions: Vec<FetchedInstruction>,
}

impl FullTransaction {
    /// Parse the `getTransaction` (`encoding: json`) result.
    pub fn from_json(r: &Value) -> Result<Self, RpcError> {
        let dec = |m: &str| RpcError::Decode(m.to_string());
        let meta = &r["meta"];
        let msg = &r["transaction"]["message"];
        let parse_keys = |v: &Value| -> Result<Vec<Address>, RpcError> {
            v.as_array()
                .map(|a| a.iter().map(|k| k.as_str().and_then(|s| s.parse().ok()).ok_or_else(|| dec("account key"))).collect())
                .unwrap_or_else(|| Ok(Vec::new()))
        };
        let mut keys = parse_keys(&msg["accountKeys"])?;
        keys.extend(parse_keys(&meta["loadedAddresses"]["writable"])?);
        keys.extend(parse_keys(&meta["loadedAddresses"]["readonly"])?);
        let key_at = |i: &Value| -> Result<Address, RpcError> {
            let i = usize::try_from(i.as_u64().ok_or_else(|| dec("index"))?).map_err(|_| dec("index"))?;
            keys.get(i).copied().ok_or_else(|| dec("index out of range"))
        };
        let mut instructions = Vec::new();
        for ix in msg["instructions"].as_array().into_iter().flatten() {
            let program_id = key_at(&ix["programIdIndex"])?;
            let accounts = ix["accounts"].as_array().into_iter().flatten().map(&key_at).collect::<Result<Vec<_>, _>>()?;
            let data = bs58::decode(ix["data"].as_str().unwrap_or("")).into_vec().map_err(|_| dec("instruction data"))?;
            instructions.push(FetchedInstruction { program_id, accounts, data });
        }
        Ok(FullTransaction {
            slot: r["slot"].as_u64().unwrap_or(0),
            block_time: r["blockTime"].as_i64(),
            err: meta.get("err").filter(|e| !e.is_null()).cloned(),
            fee: meta["fee"].as_u64().unwrap_or(0),
            compute_units: meta["computeUnitsConsumed"].as_u64(),
            logs: meta["logMessages"]
                .as_array()
                .map(|a| a.iter().filter_map(|l| l.as_str().map(str::to_string)).collect())
                .unwrap_or_default(),
            fee_payer: keys.first().copied(),
            instructions,
        })
    }
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
            .map_err(|e| RpcError::Http(scrub(&e.without_url().to_string(), &self.url)))?;
        let status = resp.status();
        let parsed: RpcResponse = resp.json().await.map_err(|e| {
            RpcError::Decode(format!("{method}: http {status}: {}", scrub(&e.without_url().to_string(), &self.url)))
        })?;
        if let Some(e) = parsed.error {
            // A provider's error text is not trusted to leave the request URL out.
            return Err(RpcError::Rpc { code: e.code, message: scrub(&e.message, &self.url) });
        }
        parsed.result.ok_or_else(|| RpcError::Decode(format!("{method}: no result")))
    }

    /// `getMultipleAccounts`, chunked by 100, order preserved.
    pub async fn get_multiple_accounts(&self, keys: &[Address]) -> Result<Vec<Option<RawAccount>>, RpcError> {
        self.get_multiple_accounts_at(keys, 0).await
    }

    /// [`Self::get_multiple_accounts`] answered by a node that has processed `min_context_slot`
    /// (0: any node). A node that is behind answers with an error instead of older state, so
    /// an account written in that slot can never be reported as missing. A provider that
    /// drops the parameter is not trusted to: the slot in its answer's context is checked as
    /// well, and an answer from before `min_context_slot` is an error ([`RpcError::Behind`]).
    pub async fn get_multiple_accounts_at(&self, keys: &[Address], min_context_slot: u64) -> Result<Vec<Option<RawAccount>>, RpcError> {
        let mut out = Vec::with_capacity(keys.len());
        for chunk in keys.chunks(100) {
            let ks: Vec<String> = chunk.iter().map(ToString::to_string).collect();
            let mut cfg = json!({ "encoding": "base64", "commitment": self.commitment });
            if min_context_slot > 0 {
                cfg["minContextSlot"] = json!(min_context_slot);
            }
            let r = self.call("getMultipleAccounts", json!([ks, cfg])).await?;
            check_context_slot(&r, min_context_slot)?;
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

    /// `getEpochInfo`: one node's slot and block height at the same moment.
    pub async fn get_slot_and_block_height(&self) -> Result<(u64, u64), RpcError> {
        let r = self.call("getEpochInfo", json!([{ "commitment": self.commitment }])).await?;
        match (r["absoluteSlot"].as_u64(), r["blockHeight"].as_u64()) {
            (Some(slot), Some(height)) => Ok((slot, height)),
            _ => Err(RpcError::Decode("epoch info".into())),
        }
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

    /// `getTransaction` with the instructions and the full account-key list (static keys, then
    /// the lookup-table writable and readonly addresses, which is how instructions index them).
    pub async fn get_transaction_full(&self, sig: &str) -> Result<Option<FullTransaction>, RpcError> {
        let r = self
            .call(
                "getTransaction",
                json!([sig, { "encoding": "json", "commitment": "confirmed", "maxSupportedTransactionVersion": 1 }]),
            )
            .await?;
        if r.is_null() {
            return Ok(None);
        }
        FullTransaction::from_json(&r).map(Some)
    }

    /// `getMinimumBalanceForRentExemption`.
    pub async fn get_minimum_balance_for_rent_exemption(&self, len: usize) -> Result<u64, RpcError> {
        self.call("getMinimumBalanceForRentExemption", json!([len, { "commitment": self.commitment }]))
            .await?
            .as_u64()
            .ok_or_else(|| RpcError::Decode("rent".into()))
    }

    /// `Clock.unix_timestamp` at the read commitment: the time the program compares plan
    /// windows and gift expiries with.
    pub async fn get_cluster_unix_timestamp(&self) -> Result<i64, RpcError> {
        let acc = self.get_account(&crate::chain::CLOCK_SYSVAR_ID).await?.ok_or_else(|| RpcError::Decode("Clock sysvar missing".into()))?;
        crate::chain::clock_unix_timestamp(&acc.owner, &acc.data).ok_or_else(|| RpcError::Decode("Clock sysvar".into()))
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

/// Filters for the Rigs with an open shift (`shift_open == 1` at offset 336).
pub fn open_shift_filters() -> Vec<Filter> {
    vec![
        Filter::DataSize(hd::RIG_LEN as u64),
        Filter::Memcmp { offset: 0, bytes: vec![hd::TAG_RIG, hd::ACCOUNT_VERSION] },
        Filter::Memcmp { offset: hd::RIG_SHIFT_OPEN_OFFSET, bytes: vec![1] },
    ]
}

/// Filters for the open StackTables: the account tag (5) and version at offset 0, the exact
/// size, and `status == Open` (0) at offset 110.
pub fn open_stack_table_filters() -> Vec<Filter> {
    use crate::skr;
    vec![
        Filter::DataSize(skr::STACK_TABLE_LEN as u64),
        Filter::Memcmp { offset: 0, bytes: vec![skr::TAG_STACK_TABLE, hd::ACCOUNT_VERSION] },
        Filter::Memcmp { offset: skr::STACK_TABLE_STATUS_OFFSET, bytes: vec![skr::status::OPEN] },
    ]
}

/// Filters for the StackSeats that have no outcome yet: the account tag (6) and version, the
/// exact size, `outcome == pending` (0) at offset 178, and, with `table`, that table's seats
/// only (`table` at offset 8).
pub fn pending_stack_seat_filters(table: Option<&Address>) -> Vec<Filter> {
    use crate::skr;
    let mut f = vec![
        Filter::DataSize(skr::STACK_SEAT_LEN as u64),
        Filter::Memcmp { offset: 0, bytes: vec![skr::TAG_STACK_SEAT, hd::ACCOUNT_VERSION] },
        Filter::Memcmp { offset: skr::STACK_SEAT_OUTCOME_OFFSET, bytes: vec![skr::outcome::PENDING] },
    ];
    if let Some(t) = table {
        f.push(Filter::Memcmp { offset: skr::STACK_SEAT_TABLE_OFFSET, bytes: t.to_bytes().to_vec() });
    }
    f
}

/// Filters for every FocusBond (account tag 7).
pub fn focus_bond_filters() -> Vec<Filter> {
    use crate::skr;
    vec![
        Filter::DataSize(skr::FOCUS_BOND_LEN as u64),
        Filter::Memcmp { offset: 0, bytes: vec![skr::TAG_FOCUS_BOND, hd::ACCOUNT_VERSION] },
    ]
}

/// Filters for every GiftEscrow (account tag 8).
pub fn gift_escrow_filters() -> Vec<Filter> {
    use crate::skr;
    vec![
        Filter::DataSize(skr::GIFT_ESCROW_LEN as u64),
        Filter::Memcmp { offset: 0, bytes: vec![skr::TAG_GIFT_ESCROW, hd::ACCOUNT_VERSION] },
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
    fn skr_account_filters_match_on_the_account_tag() {
        // Open tables: size 208, tag 5 / version 1 at offset 0, status Open (0) at offset 110.
        assert_eq!(
            open_stack_table_filters(),
            vec![
                Filter::DataSize(208),
                Filter::Memcmp { offset: 0, bytes: vec![5, 1] },
                Filter::Memcmp { offset: 110, bytes: vec![0] },
            ]
        );
        // Pending seats: size 200, tag 6, outcome pending (0) at offset 178; optionally one table.
        let table = Address::new_from_array([9; 32]);
        assert_eq!(
            pending_stack_seat_filters(Some(&table)),
            vec![
                Filter::DataSize(200),
                Filter::Memcmp { offset: 0, bytes: vec![6, 1] },
                Filter::Memcmp { offset: 178, bytes: vec![0] },
                Filter::Memcmp { offset: 8, bytes: vec![9; 32] },
            ]
        );
        assert_eq!(pending_stack_seat_filters(None).len(), 3);
        assert_eq!(focus_bond_filters(), vec![Filter::DataSize(160), Filter::Memcmp { offset: 0, bytes: vec![7, 1] }]);
        assert_eq!(gift_escrow_filters(), vec![Filter::DataSize(128), Filter::Memcmp { offset: 0, bytes: vec![8, 1] }]);
        // The filters select exactly the accounts the decoders accept.
        let t = crate::skr::StackTable {
            bump: 1,
            host: table,
            vault: table,
            table_id: 1,
            bond: 1,
            start_round: 2,
            end_round: 3,
            grace_gaps: 0,
            flags: 0,
            max_seats: 2,
            status: crate::skr::status::OPEN,
            seat_count: 0,
            finishers: 0,
            claimed_count: 0,
            total_bonds: 0,
            finisher_bonds: 0,
            payouts_total: 0,
            bury_amount: 0,
            claimed_total: 0,
            refund_after_ts: 0,
            opened_ts: 0,
            opened_round: 1,
        };
        let matches = |filters: &[Filter], data: &[u8]| {
            filters.iter().all(|f| match f {
                Filter::DataSize(n) => data.len() as u64 == *n,
                Filter::Memcmp { offset, bytes } => data.get(*offset..*offset + bytes.len()) == Some(&bytes[..]),
            })
        };
        assert!(matches(&open_stack_table_filters(), &t.encode()));
        let settled = crate::skr::StackTable { status: crate::skr::status::SETTLED, ..t };
        assert!(!matches(&open_stack_table_filters(), &settled.encode()), "a settled table is not listed");
        assert!(!matches(&pending_stack_seat_filters(None), &t.encode()), "a table is not a seat");
    }

    #[test]
    fn priority_percentile() {
        assert_eq!(priority_fee_from_samples(vec![], 75, 100, 10_000), 100);
        assert_eq!(priority_fee_from_samples(vec![0, 10, 20, 30, 40], 50, 0, 1_000), 20);
        assert_eq!(priority_fee_from_samples(vec![5, 1_000_000], 100, 0, 50_000), 50_000);
        assert_eq!(priority_fee_from_samples(vec![0, 0, 0], 90, 1_000, 50_000), 1_000);
    }

    #[test]
    fn full_transaction_resolves_lookup_table_keys() {
        let payer = Address::new_from_array([1; 32]);
        let prog = Address::new_from_array([2; 32]);
        let looked_w = Address::new_from_array([3; 32]);
        let looked_r = Address::new_from_array([4; 32]);
        let v = json!({
            "slot": 9, "blockTime": 1_790_000_000,
            "meta": { "err": null, "fee": 10_000, "computeUnitsConsumed": 1234, "logMessages": ["a"],
                      "loadedAddresses": { "writable": [looked_w.to_string()], "readonly": [looked_r.to_string()] } },
            "transaction": { "message": {
                "accountKeys": [payer.to_string(), prog.to_string()],
                "instructions": [ { "programIdIndex": 1, "accounts": [0, 2, 3], "data": bs58::encode([6u8, 1]).into_string() } ]
            } }
        });
        let t = FullTransaction::from_json(&v).unwrap();
        assert_eq!(t.fee_payer, Some(payer));
        assert_eq!(t.instructions[0].program_id, prog);
        assert_eq!(t.instructions[0].accounts, vec![payer, looked_w, looked_r]);
        assert_eq!(t.instructions[0].data, vec![6, 1]);
        assert_eq!((t.fee, t.compute_units, t.slot, t.block_time), (10_000, Some(1234), 9, Some(1_790_000_000)));
        let mut bad = v.clone();
        bad["transaction"]["message"]["instructions"][0]["accounts"] = json!([7]);
        assert!(FullTransaction::from_json(&bad).is_err(), "index out of range");
        let f = open_shift_filters();
        assert_eq!(f[2], Filter::Memcmp { offset: 336, bytes: vec![1] });
    }

    #[test]
    fn an_answer_from_before_the_slot_asked_for_is_refused() {
        let at = |slot: u64| json!({ "context": { "apiVersion": "4.3.0", "slot": slot }, "value": [] });
        // No slot was asked for: any answer is taken, also one without a context.
        assert!(check_context_slot(&at(5), 0).is_ok());
        assert!(check_context_slot(&json!({ "value": [] }), 0).is_ok());
        // The slot asked for, or a later one.
        assert!(check_context_slot(&at(453_000_000), 453_000_000).is_ok());
        assert!(check_context_slot(&at(453_000_001), 453_000_000).is_ok());
        // One slot short: what a node that lags answers when it ignores `minContextSlot`.
        let err = check_context_slot(&at(452_999_999), 453_000_000).unwrap_err();
        assert!(matches!(err, RpcError::Behind { slot: 452_999_999, min: 453_000_000 }), "{err}");
        assert_eq!(err.to_string(), "answered as of slot 452999999, before slot 453000000 that the read asked for");
        // An answer that does not say what slot it is as of is not trusted either.
        assert!(matches!(check_context_slot(&json!({ "value": [] }), 7), Err(RpcError::Decode(_))));
        assert!(matches!(check_context_slot(&json!({ "context": { "slot": "9" }, "value": [] }), 7), Err(RpcError::Decode(_))));
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
