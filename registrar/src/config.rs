//! Configuration from environment variables (`HD_*`). Secrets are loaded separately into
//! [`Secrets`], which has no `Debug`/`Display` that could print them.
//!
//! Every variable, its default and its meaning is listed in `README.md` and `.env.example`.

use std::net::SocketAddr;
use std::path::PathBuf;

use crate::attest::DowngradePolicy;
use crate::session::SessionKey;
use crate::util::{decode_address, decode_hex_exact};
use crate::voucher::{RegistrarKey, HEADS_DOWN_PROGRAM_ID};

pub const DEFAULT_STATUS_URL: &str = crate::attest::revocation::GOOGLE_STATUS_URL;
/// ~30 days at 400 ms per slot.
pub const DEFAULT_VOUCHER_TTL_SLOTS: u64 = 6_480_000;

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum NonceStoreConfig {
    Memory,
    Sqlite(PathBuf),
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum StatusListConfig {
    Url(String),
    /// Development only: a fixed local file.
    File(PathBuf),
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum SlotConfig {
    Rpc(String),
    /// Development only.
    Fixed(u64),
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum LogFormat {
    Json,
    Pretty,
}

#[derive(Clone, Debug)]
pub struct Config {
    pub bind: SocketAddr,
    pub siws_domain: String,
    pub siws_uri: String,
    pub siws_chains: Vec<String>,
    pub siws_statement: String,
    pub session_ttl_secs: i64,
    pub nonce_ttl_secs: i64,
    pub nonce_store: NonceStoreConfig,
    pub max_outstanding_nonces: usize,
    pub program_id: [u8; 32],
    pub app_package: String,
    pub release_digests: Vec<[u8; 32]>,
    pub debug_digests: Vec<[u8; 32]>,
    pub software_keys: DowngradePolicy,
    pub unlocked_devices: DowngradePolicy,
    pub voucher_ttl_slots: u64,
    pub slot: SlotConfig,
    pub status_list: StatusListConfig,
    pub status_ttl_secs: i64,
    pub status_max_stale_secs: i64,
    pub transparency_log: PathBuf,
    pub rate_per_min: u32,
    pub rate_burst: u32,
    pub attest_rate_per_min: u32,
    pub attest_rate_burst: u32,
    pub trusted_proxy_hops: usize,
    pub max_body_bytes: usize,
    pub max_concurrent_attest: usize,
    pub request_timeout_secs: u64,
    pub log_format: LogFormat,
}

pub struct Secrets {
    pub session_key: SessionKey,
    pub registrar_key: RegistrarKey,
}

#[derive(Debug, thiserror::Error)]
pub enum ConfigError {
    #[error("{0} is required")]
    Missing(&'static str),
    #[error("{0} is invalid")]
    Invalid(&'static str),
    #[error("{0}")]
    Key(String),
}

fn parse<T: std::str::FromStr>(
    get: &dyn Fn(&str) -> Option<String>,
    key: &'static str,
    default: T,
) -> Result<T, ConfigError> {
    match get(key) {
        None => Ok(default),
        Some(v) => v.trim().parse().map_err(|_| ConfigError::Invalid(key)),
    }
}

fn digests(get: &dyn Fn(&str) -> Option<String>, key: &'static str) -> Result<Vec<[u8; 32]>, ConfigError> {
    get(key)
        .unwrap_or_default()
        .split(',')
        .map(|s| s.trim().replace(':', ""))
        .filter(|s| !s.is_empty())
        .map(|s| decode_hex_exact::<32>(&s).ok_or(ConfigError::Invalid(key)))
        .collect()
}

fn policy(get: &dyn Fn(&str) -> Option<String>, key: &'static str) -> Result<DowngradePolicy, ConfigError> {
    match get(key).as_deref().map(str::trim) {
        None | Some("reject") => Ok(DowngradePolicy::Reject),
        Some("level0") => Ok(DowngradePolicy::Level0),
        Some(_) => Err(ConfigError::Invalid(key)),
    }
}

impl Config {
    pub fn from_env() -> Result<Self, ConfigError> {
        Self::from_lookup(&|k| std::env::var(k).ok().filter(|v| !v.is_empty()))
    }

    pub fn from_lookup(get: &dyn Fn(&str) -> Option<String>) -> Result<Self, ConfigError> {
        let siws_domain = get("HD_SIWS_DOMAIN").unwrap_or_else(|| "headsdown.xyz".into());
        if siws_domain.is_empty() || siws_domain.chars().any(|c| c.is_whitespace() || c == '/') {
            return Err(ConfigError::Invalid("HD_SIWS_DOMAIN"));
        }
        let siws_uri = get("HD_SIWS_URI").unwrap_or_else(|| format!("https://{siws_domain}"));
        let siws_chains: Vec<String> = get("HD_SIWS_CHAINS")
            .unwrap_or_else(|| "solana:mainnet".into())
            .split(',')
            .map(|s| s.trim().to_owned())
            .filter(|s| !s.is_empty())
            .collect();
        if siws_chains.is_empty() || !siws_chains.iter().all(|c| c.starts_with("solana:")) {
            return Err(ConfigError::Invalid("HD_SIWS_CHAINS"));
        }

        let nonce_store = match get("HD_NONCE_STORE").as_deref() {
            None | Some("memory") => NonceStoreConfig::Memory,
            Some(s) => match s.strip_prefix("sqlite:") {
                Some(p) if !p.is_empty() => NonceStoreConfig::Sqlite(PathBuf::from(p)),
                _ => return Err(ConfigError::Invalid("HD_NONCE_STORE")),
            },
        };

        let program_id = decode_address(&get("HD_PROGRAM_ID").unwrap_or_else(|| HEADS_DOWN_PROGRAM_ID.into()))
            .ok_or(ConfigError::Invalid("HD_PROGRAM_ID"))?;

        let release_digests = digests(get, "HD_APP_RELEASE_CERT_SHA256")?;
        let debug_digests = digests(get, "HD_APP_DEBUG_CERT_SHA256")?;
        if release_digests.is_empty() && debug_digests.is_empty() {
            return Err(ConfigError::Missing("HD_APP_RELEASE_CERT_SHA256"));
        }

        let slot = match (get("HD_FIXED_SLOT"), get("HD_RPC_URL")) {
            (Some(s), _) => SlotConfig::Fixed(s.trim().parse().map_err(|_| ConfigError::Invalid("HD_FIXED_SLOT"))?),
            (None, Some(url)) => SlotConfig::Rpc(url),
            (None, None) => SlotConfig::Rpc("https://api.mainnet-beta.solana.com".into()),
        };
        let status_list = match get("HD_STATUS_LIST_FILE") {
            Some(p) => StatusListConfig::File(PathBuf::from(p)),
            None => StatusListConfig::Url(get("HD_STATUS_LIST_URL").unwrap_or_else(|| DEFAULT_STATUS_URL.into())),
        };

        let log_format = match get("HD_LOG_FORMAT").as_deref() {
            None | Some("json") => LogFormat::Json,
            Some("pretty") => LogFormat::Pretty,
            Some(_) => return Err(ConfigError::Invalid("HD_LOG_FORMAT")),
        };

        let cfg = Self {
            bind: parse(get, "HD_BIND", SocketAddr::from(([0, 0, 0, 0], 8080)))?,
            siws_domain,
            siws_uri,
            siws_chains,
            siws_statement: get("HD_SIWS_STATEMENT").unwrap_or_else(|| "Sign in to Heads Down.".into()),
            session_ttl_secs: parse(get, "HD_SESSION_TTL_SECS", 3600)?,
            nonce_ttl_secs: parse(get, "HD_NONCE_TTL_SECS", 600)?,
            nonce_store,
            max_outstanding_nonces: parse(get, "HD_MAX_OUTSTANDING_NONCES", 100_000)?,
            program_id,
            app_package: get("HD_APP_PACKAGE").unwrap_or_else(|| "xyz.headsdown".into()),
            release_digests,
            debug_digests,
            software_keys: policy(get, "HD_SOFTWARE_KEY_POLICY")?,
            unlocked_devices: policy(get, "HD_UNLOCKED_DEVICE_POLICY")?,
            voucher_ttl_slots: parse(get, "HD_VOUCHER_TTL_SLOTS", DEFAULT_VOUCHER_TTL_SLOTS)?,
            slot,
            status_list,
            status_ttl_secs: parse(get, "HD_STATUS_TTL_SECS", 3600)?,
            status_max_stale_secs: parse(get, "HD_STATUS_MAX_STALE_SECS", 48 * 3600)?,
            transparency_log: PathBuf::from(
                get("HD_TRANSPARENCY_LOG").unwrap_or_else(|| "./data/attestations.jsonl".into()),
            ),
            rate_per_min: parse(get, "HD_RATE_LIMIT_PER_MIN", 60)?,
            rate_burst: parse(get, "HD_RATE_LIMIT_BURST", 20)?,
            attest_rate_per_min: parse(get, "HD_ATTEST_RATE_LIMIT_PER_MIN", 6)?,
            attest_rate_burst: parse(get, "HD_ATTEST_RATE_LIMIT_BURST", 3)?,
            trusted_proxy_hops: parse(get, "HD_TRUSTED_PROXY_HOPS", 0)?,
            max_body_bytes: parse(get, "HD_MAX_BODY_BYTES", 64 * 1024)?,
            max_concurrent_attest: parse(get, "HD_MAX_CONCURRENT_ATTEST", 4)?,
            request_timeout_secs: parse(get, "HD_REQUEST_TIMEOUT_SECS", 20)?,
            log_format,
        };
        cfg.validate()?;
        Ok(cfg)
    }

    fn validate(&self) -> Result<(), ConfigError> {
        let checks: [(bool, &'static str); 10] = [
            ((60..=crate::session::MAX_TTL_SECS).contains(&self.session_ttl_secs), "HD_SESSION_TTL_SECS"),
            ((60..=3600).contains(&self.nonce_ttl_secs), "HD_NONCE_TTL_SECS"),
            (self.max_outstanding_nonces > 0, "HD_MAX_OUTSTANDING_NONCES"),
            ((1..=400_000_000).contains(&self.voucher_ttl_slots), "HD_VOUCHER_TTL_SLOTS"),
            (
                self.status_ttl_secs > 0 && self.status_max_stale_secs >= self.status_ttl_secs,
                "HD_STATUS_MAX_STALE_SECS",
            ),
            (self.rate_per_min > 0 && self.rate_burst > 0, "HD_RATE_LIMIT_PER_MIN"),
            (self.attest_rate_per_min > 0 && self.attest_rate_burst > 0, "HD_ATTEST_RATE_LIMIT_PER_MIN"),
            ((1024..=1024 * 1024).contains(&self.max_body_bytes), "HD_MAX_BODY_BYTES"),
            ((1..=64).contains(&self.max_concurrent_attest), "HD_MAX_CONCURRENT_ATTEST"),
            (!self.app_package.is_empty(), "HD_APP_PACKAGE"),
        ];
        match checks.iter().find(|(ok, _)| !ok) {
            Some((_, key)) => Err(ConfigError::Invalid(key)),
            None => Ok(()),
        }
    }
}

impl Secrets {
    pub fn from_env() -> Result<Self, ConfigError> {
        Self::from_lookup(&|k| std::env::var(k).ok().filter(|v| !v.is_empty()))
    }

    pub fn from_lookup(get: &dyn Fn(&str) -> Option<String>) -> Result<Self, ConfigError> {
        let session_secret = get("HD_SESSION_SECRET").ok_or(ConfigError::Missing("HD_SESSION_SECRET"))?;
        let session_key = SessionKey::from_encoded(&session_secret)
            .map_err(|_| ConfigError::Key("HD_SESSION_SECRET must be >= 32 bytes, hex or base64".into()))?;
        let registrar_key = match (get("HD_REGISTRAR_KEYPAIR"), get("HD_REGISTRAR_SECRET_B58")) {
            (Some(path), None) => RegistrarKey::from_file(std::path::Path::new(&path)),
            (None, Some(b58)) => RegistrarKey::from_base58(&b58),
            (Some(_), Some(_)) => {
                return Err(ConfigError::Key("set only one of HD_REGISTRAR_KEYPAIR / HD_REGISTRAR_SECRET_B58".into()))
            }
            (None, None) => return Err(ConfigError::Missing("HD_REGISTRAR_KEYPAIR")),
        }
        .map_err(|e| ConfigError::Key(e.to_string()))?;
        Ok(Self { session_key, registrar_key })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::HashMap;

    fn lookup(pairs: &[(&str, &str)]) -> impl Fn(&str) -> Option<String> {
        let map: HashMap<String, String> = pairs.iter().map(|(k, v)| ((*k).into(), (*v).into())).collect();
        move |k| map.get(k).cloned()
    }

    const DIGEST: &str = "1039388ee545377e59a8ee7292f6545053eb846f8ac6b4d0bbc4417fc339fcfc";

    #[test]
    fn defaults() {
        let c = Config::from_lookup(&lookup(&[("HD_APP_RELEASE_CERT_SHA256", DIGEST)])).unwrap();
        assert_eq!(c.siws_domain, "headsdown.xyz");
        assert_eq!(c.siws_uri, "https://headsdown.xyz");
        assert_eq!(c.siws_chains, vec!["solana:mainnet".to_string()]);
        assert_eq!(c.app_package, "xyz.headsdown");
        assert_eq!(c.software_keys, DowngradePolicy::Reject);
        assert_eq!(c.unlocked_devices, DowngradePolicy::Reject);
        assert_eq!(c.nonce_store, NonceStoreConfig::Memory);
        assert_eq!(crate::util::encode_address(&c.program_id), HEADS_DOWN_PROGRAM_ID);
        assert_eq!(c.status_list, StatusListConfig::Url(DEFAULT_STATUS_URL.into()));
        assert!(c.debug_digests.is_empty());
    }

    #[test]
    fn release_digest_required_and_parsed() {
        assert!(matches!(Config::from_lookup(&lookup(&[])), Err(ConfigError::Missing(_))));
        let colon = DIGEST.as_bytes().chunks(2).map(|c| std::str::from_utf8(c).unwrap()).collect::<Vec<_>>().join(":");
        let c = Config::from_lookup(&lookup(&[("HD_APP_RELEASE_CERT_SHA256", &format!("{colon}, {DIGEST}"))])).unwrap();
        assert_eq!(c.release_digests.len(), 2);
        assert!(Config::from_lookup(&lookup(&[("HD_APP_RELEASE_CERT_SHA256", "abcd")])).is_err());
    }

    #[test]
    fn invalid_values_rejected() {
        for (k, v) in [
            ("HD_SOFTWARE_KEY_POLICY", "allow"),
            ("HD_NONCE_STORE", "redis"),
            ("HD_SIWS_CHAINS", "ethereum:1"),
            ("HD_NONCE_TTL_SECS", "86400"),
            ("HD_MAX_BODY_BYTES", "10"),
            ("HD_PROGRAM_ID", "nope"),
            ("HD_SIWS_DOMAIN", "a b"),
        ] {
            let r = Config::from_lookup(&lookup(&[("HD_APP_RELEASE_CERT_SHA256", DIGEST), (k, v)]));
            assert!(r.is_err(), "{k}={v}");
        }
        let c = Config::from_lookup(&lookup(&[
            ("HD_APP_RELEASE_CERT_SHA256", DIGEST),
            ("HD_NONCE_STORE", "sqlite:/data/n.db"),
            ("HD_SOFTWARE_KEY_POLICY", "level0"),
            ("HD_FIXED_SLOT", "7"),
        ]))
        .unwrap();
        assert_eq!(c.nonce_store, NonceStoreConfig::Sqlite("/data/n.db".into()));
        assert_eq!(c.software_keys, DowngradePolicy::Level0);
        assert_eq!(c.slot, SlotConfig::Fixed(7));
    }

    #[test]
    fn secrets() {
        let seed = bs58::encode([4u8; 32]).into_string();
        let s = Secrets::from_lookup(&lookup(&[
            ("HD_SESSION_SECRET", &"ab".repeat(32)),
            ("HD_REGISTRAR_SECRET_B58", &seed),
        ]))
        .unwrap();
        assert_eq!(s.registrar_key.pubkey(), RegistrarKey::from_seed(&[4; 32]).pubkey());
        assert!(Secrets::from_lookup(&lookup(&[("HD_REGISTRAR_SECRET_B58", &seed)])).is_err());
        assert!(Secrets::from_lookup(&lookup(&[("HD_SESSION_SECRET", "short"), ("HD_REGISTRAR_SECRET_B58", &seed)]))
            .is_err());
        assert!(Secrets::from_lookup(&lookup(&[("HD_SESSION_SECRET", &"ab".repeat(32))])).is_err());
    }
}
