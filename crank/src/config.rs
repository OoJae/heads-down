//! Configuration: a TOML file, then environment overrides.
//!
//! Secrets never live in the TOML: the fee-payer keypair is a **path** (CLI or env), and
//! the Helius API key is read **only** from `HELIUS_API_KEY` and substituted into URLs that
//! contain the `{HELIUS_API_KEY}` placeholder. A TOML URL that embeds an `api-key=` value
//! is refused.
//!
//! | Env | Overrides |
//! |---|---|
//! | `HD_CRANK_RPC_URL` / `HD_CRANK_WS_URL` | `rpc_url` / `ws_url` |
//! | `HD_CRANK_KEYPAIR` | `keypair_path` |
//! | `HD_CRANK_LISTEN` | `listen` |
//! | `HD_CRANK_TX_FORMAT` | `dig.tx_format` (`legacy`/`v0`/`v1`) |
//! | `HELIUS_API_KEY` | fills `{HELIUS_API_KEY}` in any URL |

use std::path::PathBuf;
use std::time::Duration;

use serde::Deserialize;
use solana_address::Address;

use crate::hd;
use crate::intake::IntakeConfig;
use crate::ore;
use crate::planner::Policy;
use crate::ratelimit::Quota;
use crate::tx::{CuEstimate, TxFormat};

/// Placeholder substituted from `HELIUS_API_KEY`.
pub const HELIUS_PLACEHOLDER: &str = "{HELIUS_API_KEY}";

/// Top-level config.
#[derive(Clone, Debug, Deserialize)]
#[serde(deny_unknown_fields, default)]
pub struct Config {
    /// JSON-RPC HTTP endpoint (may contain `{HELIUS_API_KEY}`).
    pub rpc_url: String,
    /// WebSocket endpoint; derived from `rpc_url` when absent.
    pub ws_url: Option<String>,
    /// Read commitment.
    pub commitment: String,
    /// Fee-payer keypair path.
    pub keypair_path: Option<PathBuf>,
    /// heads_down program id.
    pub program_id: String,
    /// Intake / health / metrics listen address.
    pub listen: String,
    /// Where the crank keeps its lookup-table list.
    pub state_dir: PathBuf,
    /// JSON logs.
    pub log_json: bool,
    /// Digging.
    pub dig: DigConfig,
    /// Heartbeat intake.
    pub intake: IntakeToml,
    /// Lookup tables.
    pub alt: AltConfig,
    /// Submit path.
    pub sender: SenderConfig,
}

impl Default for Config {
    fn default() -> Self {
        Config {
            rpc_url: "https://api.mainnet-beta.solana.com".into(),
            ws_url: None,
            commitment: "confirmed".into(),
            keypair_path: None,
            program_id: hd::PROGRAM_ID.to_string(),
            listen: "0.0.0.0:8787".into(),
            state_dir: PathBuf::from(".hd-crank"),
            log_json: false,
            dig: DigConfig::default(),
            intake: IntakeToml::default(),
            alt: AltConfig::default(),
            sender: SenderConfig::default(),
        }
    }
}

/// Digging policy.
#[derive(Clone, Debug, Deserialize)]
#[serde(deny_unknown_fields, default)]
pub struct DigConfig {
    /// Master switch (false = intake only).
    pub enabled: bool,
    /// Message format.
    pub tx_format: TxFormat,
    /// Start submitting when `end_slot - slot <= deploy_margin_slots` (deploy late).
    pub deploy_margin_slots: u64,
    /// Stop submitting when fewer slots than this remain.
    pub min_slots_left: u64,
    /// Re-plan and re-send (fresh blockhash) if not confirmed after this many slots.
    pub retry_after_slots: u64,
    /// Transactions per (rig, round) at most.
    pub max_attempts_per_round: u32,
    /// Rigs per transaction at most.
    pub max_rigs_per_tx: usize,
    /// Account-lock limit.
    pub max_account_locks: usize,
    /// Static priority fee (micro-lamports per CU).
    pub cu_price_micro_lamports: u64,
    /// Use `getRecentPrioritizationFees` instead of the static price.
    pub dynamic_priority_fee: bool,
    /// Percentile for the dynamic fee.
    pub priority_fee_percentile: u8,
    /// Cap for the dynamic fee.
    pub max_cu_price_micro_lamports: u64,
    /// Simulate first (size the CU limit, catch failures).
    pub simulate: bool,
    /// Headroom over simulated CU, percent.
    pub cu_margin_percent: u32,
    /// Estimate when not simulating.
    pub cu_estimate: CuEstimate,
    /// v1 loaded-accounts data size limit (bytes).
    pub loaded_accounts_data_size_limit: u32,
    /// Clock skew allowance for caps expiry and plan windows.
    pub clock_margin_secs: i64,
    /// Dig into rounds that have not started.
    pub start_rounds: bool,
    /// Extra lamports the Executor PDA must keep.
    pub executor_reserve_lamports: u64,
    /// Checkpoint idle miners before their unsettled round expires.
    pub checkpoint_sweep: bool,
    /// Sweep miners whose unsettled round is this many rounds old.
    pub checkpoint_sweep_after_rounds: u64,
    /// Run the sweep every N rounds.
    pub checkpoint_sweep_interval_rounds: u64,
    /// Checkpoints per sweep transaction.
    pub checkpoints_per_tx: usize,
    /// ORE ProgramData upgrade slot pin (0 disables).
    pub ore_programdata_slot: u64,
    /// How often to re-read ORE's ProgramData header and the heads_down Config.
    pub config_poll_secs: u64,
}

impl Default for DigConfig {
    fn default() -> Self {
        DigConfig {
            enabled: true,
            tx_format: TxFormat::V0,
            deploy_margin_slots: 20,
            min_slots_left: 3,
            retry_after_slots: 6,
            max_attempts_per_round: 2,
            max_rigs_per_tx: 16,
            max_account_locks: crate::tx::DEFAULT_MAX_ACCOUNT_LOCKS,
            cu_price_micro_lamports: 1_000,
            dynamic_priority_fee: false,
            priority_fee_percentile: 75,
            max_cu_price_micro_lamports: 200_000,
            simulate: true,
            cu_margin_percent: 15,
            cu_estimate: CuEstimate::default(),
            loaded_accounts_data_size_limit: 4 * 1024 * 1024,
            clock_margin_secs: 5,
            start_rounds: false,
            executor_reserve_lamports: 0,
            checkpoint_sweep: true,
            checkpoint_sweep_after_rounds: 400,
            checkpoint_sweep_interval_rounds: 20,
            checkpoints_per_tx: 8,
            ore_programdata_slot: ore::PINNED_PROGRAMDATA_SLOT,
            config_poll_secs: 30,
        }
    }
}

impl DigConfig {
    /// Planner policy.
    pub fn policy(&self) -> Policy {
        Policy {
            clock_margin_secs: self.clock_margin_secs,
            start_rounds: self.start_rounds,
            executor_reserve: self.executor_reserve_lamports,
        }
    }
}

/// Intake limits (TOML form).
#[derive(Clone, Debug, Deserialize)]
#[serde(deny_unknown_fields, default)]
#[allow(missing_docs)]
pub struct IntakeToml {
    pub max_message_bytes: usize,
    pub max_connections: usize,
    pub max_connections_per_ip: u32,
    pub ip_burst: u32,
    pub ip_per_second: f64,
    pub rig_burst: u32,
    pub rig_per_second: f64,
    pub max_concurrent_verifications: usize,
    pub idle_timeout_secs: u64,
    pub trust_forwarded_for: bool,
    pub max_heartbeat_rigs: usize,
    pub rig_cache_ttl_secs: u64,
    pub rig_fetches_per_second: f64,
}

impl Default for IntakeToml {
    fn default() -> Self {
        let d = IntakeConfig::default();
        IntakeToml {
            max_message_bytes: d.max_message_bytes,
            max_connections: d.max_connections,
            max_connections_per_ip: d.max_connections_per_ip,
            ip_burst: d.ip_quota.burst,
            ip_per_second: d.ip_quota.per_second,
            rig_burst: d.rig_quota.burst,
            rig_per_second: d.rig_quota.per_second,
            max_concurrent_verifications: d.max_concurrent_verifications,
            idle_timeout_secs: d.idle_timeout.as_secs(),
            trust_forwarded_for: d.trust_forwarded_for,
            max_heartbeat_rigs: 100_000,
            rig_cache_ttl_secs: 60,
            rig_fetches_per_second: 50.0,
        }
    }
}

impl IntakeToml {
    /// Runtime form.
    pub fn to_runtime(&self) -> IntakeConfig {
        IntakeConfig {
            max_message_bytes: self.max_message_bytes,
            max_connections: self.max_connections,
            max_connections_per_ip: self.max_connections_per_ip,
            ip_quota: Quota::new(self.ip_burst, self.ip_per_second),
            rig_quota: Quota::new(self.rig_burst, self.rig_per_second),
            max_tracked_keys: IntakeConfig::default().max_tracked_keys,
            max_concurrent_verifications: self.max_concurrent_verifications,
            idle_timeout: Duration::from_secs(self.idle_timeout_secs),
            send_timeout: IntakeConfig::default().send_timeout,
            trust_forwarded_for: self.trust_forwarded_for,
            stale_chain_after: IntakeConfig::default().stale_chain_after,
        }
    }
}

/// Lookup tables.
#[derive(Clone, Debug, Deserialize)]
#[serde(deny_unknown_fields, default)]
pub struct AltConfig {
    /// Use lookup tables for v0 digs.
    pub enabled: bool,
    /// Tables to use (in addition to the ones in `state_dir`).
    pub tables: Vec<String>,
    /// Create a table when none is known.
    pub auto_create: bool,
    /// Extend tables with shared and per-rig accounts as rigs appear.
    pub auto_extend: bool,
    /// Tables the crank will create at most (256 addresses each; the operator pays the rent).
    pub max_tables: usize,
}

impl Default for AltConfig {
    fn default() -> Self {
        AltConfig { enabled: true, tables: vec![], auto_create: true, auto_extend: true, max_tables: 8 }
    }
}

/// Submit path.
#[derive(Clone, Debug, Deserialize, Default)]
#[serde(deny_unknown_fields, default)]
pub struct SenderConfig {
    /// Helius Sender endpoint (feature `helius-sender`), e.g.
    /// `https://sender.helius-rpc.com/fast`. Requires `tip_accounts` and `tip_lamports`.
    pub helius_sender_url: Option<String>,
    /// Tip recipients (from Helius' docs; one is picked per tx). No defaults on purpose.
    pub tip_accounts: Vec<String>,
    /// Tip per transaction.
    pub tip_lamports: u64,
}

/// Errors building the config.
#[derive(Debug, thiserror::Error)]
pub enum ConfigError {
    /// TOML problem.
    #[error("config file: {0}")]
    File(String),
    /// Invalid value.
    #[error("config: {0}")]
    Invalid(String),
}

fn embeds_api_key(url: &str) -> bool {
    url.split(['?', '&'])
        .any(|kv| kv.to_ascii_lowercase().starts_with("api-key=") && !kv.contains(HELIUS_PLACEHOLDER))
}

/// Replace `{HELIUS_API_KEY}` from the environment value `key`.
pub fn substitute_key(url: &str, key: Option<&str>) -> Result<String, ConfigError> {
    if !url.contains(HELIUS_PLACEHOLDER) {
        return Ok(url.to_string());
    }
    match key {
        Some(k) if !k.is_empty() && k.chars().all(|c| c.is_ascii_alphanumeric() || c == '-' || c == '_') => {
            Ok(url.replace(HELIUS_PLACEHOLDER, k))
        }
        Some(_) => Err(ConfigError::Invalid("HELIUS_API_KEY has unexpected characters".into())),
        None => Err(ConfigError::Invalid("URL uses {HELIUS_API_KEY} but HELIUS_API_KEY is not set".into())),
    }
}

/// `https://…` → `wss://…`, `http://…` → `ws://…`.
pub fn derive_ws_url(rpc: &str) -> String {
    if let Some(rest) = rpc.strip_prefix("https://") {
        format!("wss://{rest}")
    } else if let Some(rest) = rpc.strip_prefix("http://") {
        // Local validators serve WebSocket on RPC port + 1.
        match rest.split_once(':').and_then(|(h, p)| {
            let (port, tail) = p.split_once('/').map(|(a, b)| (a, format!("/{b}"))).unwrap_or((p, String::new()));
            port.parse::<u16>().ok().map(|n| format!("ws://{h}:{}{tail}", n.saturating_add(1)))
        }) {
            Some(u) => u,
            None => format!("ws://{rest}"),
        }
    } else {
        rpc.to_string()
    }
}

impl Config {
    /// Parse TOML (refusing embedded API keys).
    pub fn from_toml(text: &str) -> Result<Self, ConfigError> {
        let c: Config = toml::from_str(text).map_err(|e| ConfigError::File(e.message().to_string()))?;
        for u in std::iter::once(&c.rpc_url).chain(c.ws_url.as_ref()).chain(c.sender.helius_sender_url.as_ref()) {
            if embeds_api_key(u) {
                return Err(ConfigError::Invalid(
                    "an API key is embedded in a URL in the config file; use {HELIUS_API_KEY} and the HELIUS_API_KEY env var".into(),
                ));
            }
        }
        Ok(c)
    }

    /// Apply environment overrides and key substitution, then validate.
    pub fn finalize(mut self, env: &dyn Fn(&str) -> Option<String>) -> Result<Self, ConfigError> {
        if let Some(v) = env("HD_CRANK_RPC_URL") {
            self.rpc_url = v;
        }
        if let Some(v) = env("HD_CRANK_WS_URL") {
            self.ws_url = Some(v);
        }
        if let Some(v) = env("HD_CRANK_KEYPAIR") {
            self.keypair_path = Some(PathBuf::from(v));
        }
        if let Some(v) = env("HD_CRANK_LISTEN") {
            self.listen = v;
        }
        if let Some(v) = env("HD_CRANK_TX_FORMAT") {
            self.dig.tx_format = match v.as_str() {
                "legacy" => TxFormat::Legacy,
                "v0" => TxFormat::V0,
                "v1" => TxFormat::V1,
                other => return Err(ConfigError::Invalid(format!("HD_CRANK_TX_FORMAT={other}"))),
            };
        }
        let key = env("HELIUS_API_KEY");
        self.rpc_url = substitute_key(&self.rpc_url, key.as_deref())?;
        let ws = self.ws_url.clone().unwrap_or_else(|| derive_ws_url(&self.rpc_url));
        self.ws_url = Some(substitute_key(&ws, key.as_deref())?);
        if let Some(s) = &self.sender.helius_sender_url {
            self.sender.helius_sender_url = Some(substitute_key(s, key.as_deref())?);
        }
        self.validate()?;
        Ok(self)
    }

    fn validate(&self) -> Result<(), ConfigError> {
        let bad = |m: &str| Err(ConfigError::Invalid(m.to_string()));
        if self.program_id.parse::<Address>().is_err() {
            return bad("program_id is not a base58 address");
        }
        if !matches!(self.commitment.as_str(), "processed" | "confirmed" | "finalized") {
            return bad("commitment must be processed, confirmed or finalized");
        }
        let d = &self.dig;
        if d.min_slots_left >= d.deploy_margin_slots {
            return bad("dig.min_slots_left must be < dig.deploy_margin_slots");
        }
        if d.max_rigs_per_tx == 0 || d.max_rigs_per_tx > 255 {
            return bad("dig.max_rigs_per_tx must be 1..=255");
        }
        if d.cu_price_micro_lamports > d.max_cu_price_micro_lamports {
            return bad("dig.cu_price_micro_lamports exceeds dig.max_cu_price_micro_lamports");
        }
        if d.max_attempts_per_round == 0 {
            return bad("dig.max_attempts_per_round must be >= 1");
        }
        if d.priority_fee_percentile > 100 {
            return bad("dig.priority_fee_percentile must be <= 100");
        }
        if self.intake.max_message_bytes < 256 || self.intake.max_message_bytes > 64 * 1024 {
            return bad("intake.max_message_bytes must be 256..=65536");
        }
        if self.sender.helius_sender_url.is_some() && (self.sender.tip_accounts.is_empty() || self.sender.tip_lamports == 0) {
            return bad("sender.helius_sender_url needs sender.tip_accounts and sender.tip_lamports");
        }
        for t in &self.sender.tip_accounts {
            if t.parse::<Address>().is_err() {
                return bad("sender.tip_accounts has an invalid address");
            }
        }
        for t in &self.alt.tables {
            if t.parse::<Address>().is_err() {
                return bad("alt.tables has an invalid address");
            }
        }
        Ok(())
    }

    /// Program id as an address (validated).
    pub fn program_id(&self) -> Address {
        self.program_id.parse().unwrap_or(hd::PROGRAM_ID)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn no_env(_: &str) -> Option<String> {
        None
    }

    #[test]
    fn defaults_are_valid() {
        let c = Config::from_toml("").unwrap().finalize(&no_env).unwrap();
        assert_eq!(c.ws_url.as_deref(), Some("wss://api.mainnet-beta.solana.com"));
        assert_eq!(c.program_id(), hd::PROGRAM_ID);
    }

    #[test]
    fn helius_key_only_from_env() {
        let toml = r#"rpc_url = "https://mainnet.helius-rpc.com/?api-key={HELIUS_API_KEY}""#;
        let c = Config::from_toml(toml).unwrap();
        assert!(c.clone().finalize(&no_env).is_err(), "placeholder without env");
        let env = |k: &str| (k == "HELIUS_API_KEY").then(|| "abc-123".to_string());
        let c = c.finalize(&env).unwrap();
        assert_eq!(c.rpc_url, "https://mainnet.helius-rpc.com/?api-key=abc-123");
        assert_eq!(c.ws_url.as_deref(), Some("wss://mainnet.helius-rpc.com/?api-key=abc-123"));
        let embedded = r#"rpc_url = "https://mainnet.helius-rpc.com/?api-key=deadbeef""#;
        assert!(Config::from_toml(embedded).unwrap_err().to_string().contains("HELIUS_API_KEY"));
        let evil = |k: &str| (k == "HELIUS_API_KEY").then(|| "a&b=c".to_string());
        assert!(Config::from_toml(toml).unwrap().finalize(&evil).is_err());
    }

    #[test]
    fn env_overrides_and_validation() {
        let env = |k: &str| match k {
            "HD_CRANK_RPC_URL" => Some("http://127.0.0.1:8899".to_string()),
            "HD_CRANK_TX_FORMAT" => Some("v1".to_string()),
            "HD_CRANK_KEYPAIR" => Some("/tmp/k.json".to_string()),
            _ => None,
        };
        let c = Config::from_toml("").unwrap().finalize(&env).unwrap();
        assert_eq!(c.ws_url.as_deref(), Some("ws://127.0.0.1:8900"));
        assert_eq!(c.dig.tx_format, TxFormat::V1);
        assert_eq!(c.keypair_path, Some(PathBuf::from("/tmp/k.json")));
        assert!(Config::from_toml("unknown_key = 1").is_err());
        assert!(Config::from_toml("[dig]\nmin_slots_left = 30").unwrap().finalize(&no_env).is_err());
        assert!(Config::from_toml("[dig]\ntx_format = \"v2\"").is_err());
        assert!(Config::from_toml("[sender]\nhelius_sender_url = \"https://sender.helius-rpc.com/fast\"")
            .unwrap()
            .finalize(&no_env)
            .is_err(), "sender without tip accounts");
        assert!(Config::from_toml("program_id = \"nope\"").unwrap().finalize(&no_env).is_err());
    }
}
