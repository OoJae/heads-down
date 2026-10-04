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
/// About 20 days at today's slot time (about 270 ms a slot, measured on mainnet on 2026-10-04).
/// The number was chosen as 30 days at 400 ms a slot.
pub const DEFAULT_VOUCHER_TTL_SLOTS: u64 = 6_480_000;
/// The longest voucher the program accepts (`MAX_ATTESTATION_TTL_SLOTS`, INTERFACE §4.2): a
/// longer one would be signed here and refused on-chain.
pub const MAX_VOUCHER_TTL_SLOTS: u64 = 25_920_000;

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

#[derive(Clone, PartialEq, Eq)]
pub enum SlotConfig {
    Rpc(String),
    /// Development only.
    Fixed(u64),
}

/// `HD_RPC_URL` can carry a provider's API key, in the query string, the path or the userinfo.
/// `Debug` shows the scheme, the host and the port and nothing else, so a `{:?}` of the config
/// in a log line cannot print the key.
impl std::fmt::Debug for SlotConfig {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Rpc(url) => f.debug_tuple("Rpc").field(&scheme_and_host(url)).finish(),
            Self::Fixed(slot) => f.debug_tuple("Fixed").field(slot).finish(),
        }
    }
}

/// `url` cut down to `scheme://host[:port]`. Anything that is not an http(s) URL becomes
/// `<redacted>`: this never falls back to showing its input.
fn scheme_and_host(url: &str) -> String {
    match reqwest::Url::parse(url) {
        Ok(u) if matches!(u.scheme(), "http" | "https") => match (u.host_str(), u.port()) {
            (Some(host), Some(port)) => format!("{}://{host}:{port}", u.scheme()),
            (Some(host), None) => format!("{}://{host}", u.scheme()),
            (None, _) => "<redacted>".into(),
        },
        _ => "<redacted>".into(),
    }
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
    pub trust_real_ip: bool,
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
    /// Two settings that cannot both be set.
    #[error("{0}")]
    Conflict(&'static str),
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
        // No default: the SIWS domain is the host of the site the app identifies itself with, and
        // a default would be a name somebody else can register.
        let siws_domain = get("HD_SIWS_DOMAIN").ok_or(ConfigError::Missing("HD_SIWS_DOMAIN"))?;
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
            trust_real_ip: parse(get, "HD_TRUST_REAL_IP", false)?,
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
            ((1..=MAX_VOUCHER_TTL_SLOTS).contains(&self.voucher_ttl_slots), "HD_VOUCHER_TTL_SLOTS"),
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
        if let Some((_, key)) = checks.iter().find(|(ok, _)| !ok) {
            return Err(ConfigError::Invalid(key));
        }
        if self.trust_real_ip && self.trusted_proxy_hops > 0 {
            return Err(ConfigError::Conflict(
                "HD_TRUST_REAL_IP and HD_TRUSTED_PROXY_HOPS name two different headers: set one",
            ));
        }
        Ok(())
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

    /// The two settings without a default.
    const REQUIRED: [(&str, &str); 2] =
        [("HD_APP_RELEASE_CERT_SHA256", DIGEST), ("HD_SIWS_DOMAIN", "headsdown.example")];

    fn with(extra: &[(&'static str, &'static str)]) -> Result<Config, ConfigError> {
        let pairs: Vec<(&str, &str)> = REQUIRED.iter().copied().chain(extra.iter().copied()).collect();
        Config::from_lookup(&lookup(&pairs))
    }

    #[test]
    fn defaults() {
        let c = with(&[]).unwrap();
        assert_eq!(c.siws_domain, "headsdown.example");
        assert_eq!(c.siws_uri, "https://headsdown.example");
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
        // The SIWS domain has no default either: it must be a site the team controls.
        assert!(matches!(
            Config::from_lookup(&lookup(&[("HD_APP_RELEASE_CERT_SHA256", DIGEST)])),
            Err(ConfigError::Missing("HD_SIWS_DOMAIN"))
        ));
        assert!(matches!(
            Config::from_lookup(&lookup(&[("HD_SIWS_DOMAIN", "headsdown.example")])),
            Err(ConfigError::Missing(_))
        ));
        let colon = DIGEST.as_bytes().chunks(2).map(|c| std::str::from_utf8(c).unwrap()).collect::<Vec<_>>().join(":");
        let both = format!("{colon}, {DIGEST}");
        let c = Config::from_lookup(&lookup(&[
            ("HD_SIWS_DOMAIN", "headsdown.example"),
            ("HD_APP_RELEASE_CERT_SHA256", &both),
        ]))
        .unwrap();
        assert_eq!(c.release_digests.len(), 2);
        assert!(Config::from_lookup(&lookup(&[
            ("HD_SIWS_DOMAIN", "headsdown.example"),
            ("HD_APP_RELEASE_CERT_SHA256", "abcd")
        ]))
        .is_err());
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
            // Longer than the program accepts (INTERFACE §4.2): signed here, refused on-chain.
            ("HD_VOUCHER_TTL_SLOTS", "25920001"),
        ] {
            assert!(with(&[(k, v)]).is_err(), "{k}={v}");
        }
        assert!(
            Config::from_lookup(&lookup(&[("HD_APP_RELEASE_CERT_SHA256", DIGEST), ("HD_SIWS_DOMAIN", "a b")])).is_err()
        );
        assert_eq!(with(&[("HD_VOUCHER_TTL_SLOTS", "25920000")]).unwrap().voucher_ttl_slots, MAX_VOUCHER_TTL_SLOTS);
        let c = with(&[
            ("HD_NONCE_STORE", "sqlite:/data/n.db"),
            ("HD_SOFTWARE_KEY_POLICY", "level0"),
            ("HD_FIXED_SLOT", "7"),
        ])
        .unwrap();
        assert_eq!(c.nonce_store, NonceStoreConfig::Sqlite("/data/n.db".into()));
        assert_eq!(c.software_keys, DowngradePolicy::Level0);
        assert_eq!(c.slot, SlotConfig::Fixed(7));
    }

    /// `HD_RPC_URL` can carry a provider key. No `{:?}` of the slot setting or of the whole
    /// config shows more of it than the scheme, the host and the port.
    #[test]
    fn debug_never_prints_the_rpc_url() {
        for (url, shown) in [
            // The key in the query string.
            ("https://mainnet.helius-rpc.com/?api-key=SECRET-KEY-123", "https://mainnet.helius-rpc.com"),
            // The key in the path.
            ("https://x.quiknode.pro/SECRET-KEY-123/", "https://x.quiknode.pro"),
            ("https://rpc.example.org/v2/SECRET-KEY-123", "https://rpc.example.org"),
            // The key in the userinfo, and an explicit port.
            ("http://user:SECRET-KEY-123@127.0.0.1:8899/v2#SECRET-KEY-123", "http://127.0.0.1:8899"),
            // Values the service could not use as an RPC URL: nothing of them is shown.
            ("https://rpc.example.org:SECRET-KEY-123", "<redacted>"),
            ("wss://rpc.example.org/SECRET-KEY-123", "<redacted>"),
            ("SECRET-KEY-123", "<redacted>"),
        ] {
            let c = with(&[("HD_RPC_URL", url)]).unwrap();
            assert_eq!(c.slot, SlotConfig::Rpc(url.into()), "the value itself is kept whole");
            assert_eq!(format!("{:?}", c.slot), format!("Rpc({shown:?})"));
            for text in [format!("{c:?}"), format!("{c:#?}")] {
                assert!(!text.contains("SECRET-KEY-123"), "{text}");
                assert!(text.contains(shown), "{text}");
            }
        }
        // Unset, it is the public mainnet RPC; a fixed slot prints as it is.
        assert_eq!(format!("{:?}", with(&[]).unwrap().slot), r#"Rpc("https://api.mainnet-beta.solana.com")"#);
        assert_eq!(format!("{:?}", SlotConfig::Fixed(7)), "Fixed(7)");
    }

    #[test]
    fn siws_uri_defaults_to_the_domain_and_is_kept_as_given() {
        let c = Config::from_lookup(&lookup(&[
            ("HD_APP_RELEASE_CERT_SHA256", DIGEST),
            ("HD_SIWS_DOMAIN", "oojae.github.io"),
        ]))
        .unwrap();
        assert_eq!(c.siws_uri, "https://oojae.github.io");
        // A site under a path, with its trailing slash (the value in the settings files).
        let c = Config::from_lookup(&lookup(&[
            ("HD_APP_RELEASE_CERT_SHA256", DIGEST),
            ("HD_SIWS_DOMAIN", "oojae.github.io"),
            ("HD_SIWS_URI", "https://oojae.github.io/heads-down/"),
        ]))
        .unwrap();
        assert_eq!(
            (c.siws_domain.as_str(), c.siws_uri.as_str()),
            ("oojae.github.io", "https://oojae.github.io/heads-down/")
        );
    }

    /// A debug-signed build is accepted with the debug digest alone; with neither digest the
    /// service does not start.
    #[test]
    fn a_debug_digest_alone_is_enough_and_no_digest_is_refused() {
        let debug_only = [("HD_SIWS_DOMAIN", "headsdown.example"), ("HD_APP_DEBUG_CERT_SHA256", DIGEST)];
        let c = Config::from_lookup(&lookup(&debug_only)).unwrap();
        assert!(c.release_digests.is_empty() && c.debug_digests.len() == 1);
        let none = Config::from_lookup(&lookup(&[("HD_SIWS_DOMAIN", "headsdown.example")]));
        assert_eq!(none.unwrap_err().to_string(), "HD_APP_RELEASE_CERT_SHA256 is required");
    }

    #[test]
    fn proxy_header_settings() {
        let c = with(&[]).unwrap();
        assert!(!c.trust_real_ip && c.trusted_proxy_hops == 0, "no header is trusted by default");
        assert!(with(&[("HD_TRUST_REAL_IP", "true")]).unwrap().trust_real_ip);
        let hops = with(&[("HD_TRUST_REAL_IP", "false"), ("HD_TRUSTED_PROXY_HOPS", "1")]).unwrap();
        assert!(!hops.trust_real_ip && hops.trusted_proxy_hops == 1);
        assert!(matches!(with(&[("HD_TRUST_REAL_IP", "yes")]), Err(ConfigError::Invalid("HD_TRUST_REAL_IP"))));
        // Two different headers: the registrar does not guess which one the proxy writes.
        let both = with(&[("HD_TRUST_REAL_IP", "true"), ("HD_TRUSTED_PROXY_HOPS", "1")]);
        assert!(matches!(both, Err(ConfigError::Conflict(_))));
        assert_eq!(
            both.unwrap_err().to_string(),
            "HD_TRUST_REAL_IP and HD_TRUSTED_PROXY_HOPS name two different headers: set one"
        );
    }

    /// The names a settings file gives: every `NAME=` line, commented out or not.
    fn names_in(text: &str) -> std::collections::BTreeSet<String> {
        text.lines()
            .map(|line| line.trim_start_matches(['#', ' ']))
            .filter_map(|line| line.split_once('='))
            .map(|(name, _)| name)
            .filter(|n| !n.is_empty() && n.chars().all(|c| c.is_ascii_uppercase() || c.is_ascii_digit() || c == '_'))
            .map(str::to_owned)
            .collect()
    }

    /// `.env.example` and `deploy/railway/registrar/.env.example` name every variable the code
    /// reads, and none that it does not read. The variables are found by recording what the
    /// two loaders ask for, so a new one cannot be left out of the files.
    #[test]
    fn settings_files_name_exactly_the_variables_the_code_reads() {
        use std::cell::RefCell;
        use std::collections::BTreeSet;

        let asked: RefCell<BTreeSet<String>> = RefCell::new(BTreeSet::new());
        let seed = bs58::encode([4u8; 32]).into_string();
        let record = |k: &str| {
            asked.borrow_mut().insert(k.to_owned());
            match k {
                "HD_APP_RELEASE_CERT_SHA256" => Some(DIGEST.to_owned()),
                "HD_SIWS_DOMAIN" => Some("headsdown.example".to_owned()),
                "HD_SESSION_SECRET" => Some("ab".repeat(32)),
                "HD_REGISTRAR_SECRET_B58" => Some(seed.clone()),
                _ => None,
            }
        };
        Config::from_lookup(&record).unwrap();
        Secrets::from_lookup(&record).unwrap();
        let code = asked.into_inner();
        assert!(code.contains("HD_FIXED_SLOT") && code.contains("HD_STATUS_LIST_FILE") && code.len() >= 36, "{code:?}");
        let plus = |extra: &[&str]| -> BTreeSet<String> {
            code.iter().cloned().chain(extra.iter().map(|s| (*s).to_owned())).collect()
        };

        // The log filter is read by the tracing subscriber, not by the loaders.
        let local = std::fs::read_to_string(concat!(env!("CARGO_MANIFEST_DIR"), "/.env.example")).unwrap();
        assert_eq!(names_in(&local), plus(&["RUST_LOG"]), ".env.example");

        // On Railway the entrypoint reads the key itself and PORT, and sets HD_REGISTRAR_KEYPAIR
        // (a path) for the service: the operator must not set that one.
        let railway = concat!(env!("CARGO_MANIFEST_DIR"), "/../deploy/railway/registrar/.env.example");
        let Ok(railway) = std::fs::read_to_string(railway) else {
            return; // the registrar can be built without the deploy directory
        };
        let mut want = plus(&["RUST_LOG", "PORT", "HD_REGISTRAR_KEYPAIR_JSON"]);
        want.remove("HD_REGISTRAR_KEYPAIR");
        assert_eq!(names_in(&railway), want, "deploy/railway/registrar/.env.example");
    }

    /// The config a settings file gives: its uncommented `NAME=value` lines (an empty value is
    /// unset), or only the ones in `only`, plus a certificate digest.
    fn config_shown(text: &str, only: Option<&[&str]>) -> Config {
        let shown: HashMap<String, String> = text
            .lines()
            .filter(|line| !line.trim_start().starts_with('#'))
            .filter_map(|line| line.split_once('='))
            .map(|(name, value)| (name.trim().to_owned(), value.trim().to_owned()))
            .filter(|(name, value)| !value.is_empty() && only.is_none_or(|names| names.contains(&name.as_str())))
            .collect();
        Config::from_lookup(&|k| match k {
            "HD_APP_RELEASE_CERT_SHA256" => Some(DIGEST.to_owned()),
            _ => shown.get(k).cloned(),
        })
        .unwrap()
    }

    /// The values the two files show load without an error and are the code's defaults, except
    /// the ones each file says are not: the site, where the state lives and, on Railway, the
    /// proxy header.
    #[test]
    fn settings_files_show_the_defaults() {
        let same = |text: &str, stated: &[&str], file: &str| {
            let (shown, defaults) = (config_shown(text, None), config_shown(text, Some(stated)));
            assert_eq!(shown.slot, defaults.slot, "{file}");
            assert_eq!(format!("{shown:#?}"), format!("{defaults:#?}"), "{file}");
        };
        let local = std::fs::read_to_string(concat!(env!("CARGO_MANIFEST_DIR"), "/.env.example")).unwrap();
        same(&local, &["HD_SIWS_DOMAIN", "HD_SIWS_URI", "HD_NONCE_STORE"], ".env.example");
        assert_eq!(config_shown(&local, None).siws_uri, "https://oojae.github.io/heads-down/");

        let railway = concat!(env!("CARGO_MANIFEST_DIR"), "/../deploy/railway/registrar/.env.example");
        let Ok(railway) = std::fs::read_to_string(railway) else {
            return; // the registrar can be built without the deploy directory
        };
        let stated = ["HD_SIWS_DOMAIN", "HD_SIWS_URI", "HD_NONCE_STORE", "HD_TRANSPARENCY_LOG", "HD_TRUST_REAL_IP"];
        same(&railway, &stated, "deploy/railway/registrar/.env.example");
        let c = config_shown(&railway, None);
        assert!(c.trust_real_ip && c.trusted_proxy_hops == 0, "Railway: X-Real-IP, and not X-Forwarded-For as well");
        // The file shows no RPC URL: a keyed one must never be in it, and which other one the
        // service relies on is the operator's choice (the file says why the default is not it).
        assert_eq!(
            c.slot,
            SlotConfig::Rpc("https://api.mainnet-beta.solana.com".into()),
            "no RPC URL is shown for Railway"
        );
        assert_eq!(c.siws_uri, "https://oojae.github.io/heads-down/");
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
