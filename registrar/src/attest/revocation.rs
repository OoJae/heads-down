//! Google's attestation certificate status list ("CRL"):
//! <https://android.googleapis.com/attestation/status>
//!
//! ```json
//! { "entries": { "<serial hex>": { "status": "REVOKED" | "SUSPENDED", "reason": "...", ... } } }
//! ```
//!
//! Keys are certificate serial numbers as **unpadded lowercase hex** (Java's
//! `BigInteger.toString(16)`, which is how Google's own verifier looks them up). Many of
//! Google's intermediate serials, such as `0388266760658996860D`, have only decimal-digit
//! nibbles, which is why roughly half of the keys in the live list look like decimal numbers.
//!
//! Every certificate in a chain is looked up. `REVOKED` and `SUSPENDED` both reject (Google's
//! library only rejects `REVOKED`; a suspended key is not one to vouch for today).
//!
//! The list is cached. A refresh that fails keeps serving the cached copy until it is older
//! than `max_stale`; after that, attestation **fails closed** (HTTP 503) rather than vouching
//! without a revocation check.

use std::collections::HashMap;
use std::sync::Arc;
use std::time::Duration;

use serde::Deserialize;
use tokio::sync::{Mutex, RwLock};

pub const GOOGLE_STATUS_URL: &str = "https://android.googleapis.com/attestation/status";
/// The live list is ~180 KB; refuse anything absurd.
const MAX_BODY_BYTES: usize = 16 * 1024 * 1024;
const MAX_ENTRIES: usize = 1_000_000;

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct RevocationEntry {
    pub status: String,
    pub reason: Option<String>,
}

#[derive(Clone, Debug)]
pub struct RevocationList {
    entries: HashMap<String, RevocationEntry>,
    /// Unix seconds when this copy was obtained.
    pub fetched_at: i64,
    /// Where it came from (URL or file path), for `/healthz` and transcripts.
    pub source: String,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct StatusFile {
    entries: HashMap<String, StatusEntry>,
}

#[derive(Deserialize)]
struct StatusEntry {
    status: String,
    #[serde(default)]
    reason: Option<String>,
}

#[derive(Debug, thiserror::Error)]
pub enum RevocationError {
    #[error("status list malformed")]
    Malformed,
    #[error("status list unavailable")]
    Unavailable,
}

impl RevocationList {
    pub fn parse(json: &[u8], fetched_at: i64, source: impl Into<String>) -> Result<Self, RevocationError> {
        if json.len() > MAX_BODY_BYTES {
            return Err(RevocationError::Malformed);
        }
        let file: StatusFile = serde_json::from_slice(json).map_err(|_| RevocationError::Malformed)?;
        if file.entries.len() > MAX_ENTRIES {
            return Err(RevocationError::Malformed);
        }
        let entries = file
            .entries
            .into_iter()
            .filter(|(_, e)| e.status == "REVOKED" || e.status == "SUSPENDED")
            .map(|(k, e)| (k.trim().to_ascii_lowercase(), RevocationEntry { status: e.status, reason: e.reason }))
            .collect();
        Ok(Self { entries, fetched_at, source: source.into() })
    }

    pub fn empty(source: impl Into<String>) -> Self {
        Self { entries: HashMap::new(), fetched_at: 0, source: source.into() }
    }

    /// Status of a certificate serial, given as unpadded lowercase hex.
    pub fn status_of(&self, serial_hex: &str) -> Option<&RevocationEntry> {
        self.entries.get(serial_hex)
    }

    pub fn len(&self) -> usize {
        self.entries.len()
    }

    pub fn is_empty(&self) -> bool {
        self.entries.is_empty()
    }
}

/// Where the status list comes from.
pub enum StatusSource {
    /// Fetched over HTTPS and cached.
    Http { url: String, client: reqwest::Client },
    /// A fixed list (tests, offline development). Never refreshed.
    Fixed(Arc<RevocationList>),
}

pub struct StatusListProvider {
    source: StatusSource,
    ttl_secs: i64,
    max_stale_secs: i64,
    cache: RwLock<Option<Arc<RevocationList>>>,
    refresh_lock: Mutex<()>,
}

impl StatusListProvider {
    pub fn http(url: String, ttl_secs: i64, max_stale_secs: i64) -> Result<Self, RevocationError> {
        let client = reqwest::Client::builder()
            .timeout(Duration::from_secs(15))
            .https_only(!url.starts_with("http://127.0.0.1") && !url.starts_with("http://localhost"))
            .user_agent(concat!("hd-registrar/", env!("CARGO_PKG_VERSION")))
            .build()
            .map_err(|_| RevocationError::Unavailable)?;
        Ok(Self {
            source: StatusSource::Http { url, client },
            ttl_secs,
            max_stale_secs,
            cache: RwLock::new(None),
            refresh_lock: Mutex::new(()),
        })
    }

    pub fn fixed(list: RevocationList) -> Self {
        let list = Arc::new(list);
        Self {
            source: StatusSource::Fixed(Arc::clone(&list)),
            ttl_secs: i64::MAX,
            max_stale_secs: i64::MAX,
            cache: RwLock::new(Some(list)),
            refresh_lock: Mutex::new(()),
        }
    }

    /// The cached copy, if any, without refreshing.
    pub async fn cached(&self) -> Option<Arc<RevocationList>> {
        self.cache.read().await.clone()
    }

    /// A list that is fresh, or stale but within `max_stale`. Refreshes when older than `ttl`.
    pub async fn current(&self, now: i64) -> Result<Arc<RevocationList>, RevocationError> {
        if let StatusSource::Fixed(list) = &self.source {
            return Ok(Arc::clone(list));
        }
        if let Some(list) = self.cached().await {
            if now.saturating_sub(list.fetched_at) < self.ttl_secs {
                return Ok(list);
            }
        }
        // One refresh at a time; others wait and then re-read the cache.
        let _guard = self.refresh_lock.lock().await;
        if let Some(list) = self.cached().await {
            if now.saturating_sub(list.fetched_at) < self.ttl_secs {
                return Ok(list);
            }
        }
        match self.fetch(now).await {
            Ok(list) => {
                let list = Arc::new(list);
                *self.cache.write().await = Some(Arc::clone(&list));
                tracing::info!(entries = list.len(), "attestation status list refreshed");
                Ok(list)
            }
            Err(e) => {
                let cached = self.cached().await;
                match cached {
                    Some(list) if now.saturating_sub(list.fetched_at) < self.max_stale_secs => {
                        tracing::warn!(
                            age_secs = now.saturating_sub(list.fetched_at),
                            "status list refresh failed; serving cached copy"
                        );
                        Ok(list)
                    }
                    _ => {
                        tracing::error!(error = %e, "status list unavailable; attestation fails closed");
                        Err(RevocationError::Unavailable)
                    }
                }
            }
        }
    }

    async fn fetch(&self, now: i64) -> Result<RevocationList, RevocationError> {
        let StatusSource::Http { url, client } = &self.source else {
            return Err(RevocationError::Unavailable);
        };
        let mut resp = client.get(url).send().await.map_err(|_| RevocationError::Unavailable)?;
        if !resp.status().is_success() {
            return Err(RevocationError::Unavailable);
        }
        if resp.content_length().is_some_and(|l| l > MAX_BODY_BYTES as u64) {
            return Err(RevocationError::Malformed);
        }
        let mut body = Vec::new();
        while let Some(chunk) = resp.chunk().await.map_err(|_| RevocationError::Unavailable)? {
            if body.len().saturating_add(chunk.len()) > MAX_BODY_BYTES {
                return Err(RevocationError::Malformed);
            }
            body.extend_from_slice(&chunk);
        }
        RevocationList::parse(&body, now, url.clone())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_google_format() {
        let json = br#"{"entries":{
            "2c8cdddfd5e03bfc":{"status":"REVOKED","expires":"2020-11-13","reason":"KEY_COMPROMISE","comment":"x"},
            "3882667606589968575":{"status":"REVOKED","reason":"KEY_COMPROMISE"},
            "C35747A084470C3135AEEFE2B8D40CD6":{"status":"SUSPENDED"},
            "abc":{"status":"SOMETHING_ELSE"}
        }}"#;
        let l = RevocationList::parse(json, 5, "fixture").unwrap();
        assert_eq!(l.len(), 3);
        assert_eq!(l.status_of("2c8cdddfd5e03bfc").unwrap().reason.as_deref(), Some("KEY_COMPROMISE"));
        assert!(l.status_of("3882667606589968575").is_some());
        assert_eq!(l.status_of("c35747a084470c3135aeefe2b8d40cd6").unwrap().status, "SUSPENDED");
        assert!(l.status_of("abc").is_none());
        assert!(l.status_of("deadbeef").is_none());
    }

    #[test]
    fn rejects_malformed() {
        assert!(RevocationList::parse(b"[]", 0, "x").is_err());
        assert!(RevocationList::parse(b"{\"entries\":{\"a\":{}}}", 0, "x").is_err());
        assert!(RevocationList::parse(b"{\"entries\":{},\"extra\":1}", 0, "x").is_err());
        assert!(RevocationList::parse(b"not json", 0, "x").is_err());
    }

    #[tokio::test]
    async fn fixed_provider_never_goes_stale() {
        let p = StatusListProvider::fixed(RevocationList::empty("fixture"));
        assert!(p.current(i64::MAX).await.is_ok());
    }

    #[tokio::test]
    async fn http_provider_fails_closed_when_unreachable_and_nothing_cached() {
        // Port 9 (discard) on localhost: connection refused, no network needed.
        let p = StatusListProvider::http("http://127.0.0.1:9/status".into(), 3600, 7200).unwrap();
        assert!(matches!(p.current(1_000).await, Err(RevocationError::Unavailable)));
    }

    #[tokio::test]
    async fn http_provider_serves_stale_cache_within_bound_then_fails_closed() {
        let p = StatusListProvider::http("http://127.0.0.1:9/status".into(), 3600, 7200).unwrap();
        *p.cache.write().await = Some(Arc::new(RevocationList::parse(br#"{"entries":{}}"#, 1_000, "seed").unwrap()));
        // Fresh: served from cache without a fetch.
        assert!(p.current(1_000 + 3599).await.is_ok());
        // Stale, refresh fails, within max_stale: cached copy.
        assert!(p.current(1_000 + 5000).await.is_ok());
        // Beyond max_stale: fail closed.
        assert!(matches!(p.current(1_000 + 7200).await, Err(RevocationError::Unavailable)));
    }
}
