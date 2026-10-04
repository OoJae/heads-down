//! Current Solana slot, used to compute each voucher's `expiry_slot`.
//!
//! The registrar asks an RPC node for the **finalized** slot (the lowest, most conservative
//! of the commitment levels), caches it for a few seconds, and never guesses: if no RPC answer
//! is available, `/attest` fails with 503 before the nonce is consumed.
//!
//! It also asks once at start and says in the log whether a slot came
//! ([`SlotSource::report_at_start`]): `/healthz` makes no network call, so without that line an
//! RPC that refuses this host would only show as 503 on the first attestation.

use std::time::{Duration, Instant};

use serde::Deserialize;
use tokio::sync::Mutex;

const CACHE_FOR: Duration = Duration::from_secs(5);

pub enum SlotSource {
    Rpc {
        url: String,
        client: reqwest::Client,
        cache: Mutex<Option<(u64, Instant)>>,
    },
    /// Tests and offline development.
    Fixed(u64),
}

#[derive(Debug, thiserror::Error)]
#[error("current slot unavailable")]
pub struct SlotError;

/// Why no slot came. It goes into the log, so it holds neither the URL nor anything the RPC
/// sent back: only which step failed, and the HTTP status when there was one.
#[derive(Debug, thiserror::Error)]
enum NoSlot {
    #[error("no HTTP answer (no connection, TLS failure or timeout)")]
    NoAnswer,
    #[error("HTTP status {0}")]
    Status(u16),
    #[error("the answer was not a getSlot result")]
    NotASlot,
}

#[derive(Deserialize)]
struct RpcResponse {
    result: Option<u64>,
}

impl SlotSource {
    pub fn rpc(url: String) -> Result<Self, SlotError> {
        let client = reqwest::Client::builder()
            .timeout(Duration::from_secs(10))
            .user_agent(concat!("hd-registrar/", env!("CARGO_PKG_VERSION")))
            .build()
            .map_err(|_| SlotError)?;
        Ok(Self::Rpc { url, client, cache: Mutex::new(None) })
    }

    pub async fn current_slot(&self) -> Result<u64, SlotError> {
        self.ask().await.map_err(|_| SlotError)
    }

    /// Asks for the slot once and writes one log line: the slot, or a warning with the step
    /// that failed. Returns whether a slot came. Meant for the start of the service, where the
    /// line lands in the deploy log; a failure here stops nothing.
    pub async fn report_at_start(&self) -> bool {
        match self.ask().await {
            Ok(slot) => {
                tracing::info!(slot, "slot source answered");
                true
            }
            Err(why) => {
                tracing::warn!(
                    reason = %why,
                    "slot source gave no slot: POST /attest answers 503 slot_unavailable until it does (check HD_RPC_URL)"
                );
                false
            }
        }
    }

    async fn ask(&self) -> Result<u64, NoSlot> {
        match self {
            Self::Fixed(slot) => Ok(*slot),
            Self::Rpc { url, client, cache } => {
                let mut cache = cache.lock().await;
                if let Some((slot, at)) = *cache {
                    if at.elapsed() < CACHE_FOR {
                        return Ok(slot);
                    }
                }
                let body = serde_json::json!({
                    "jsonrpc": "2.0", "id": 1, "method": "getSlot",
                    "params": [{ "commitment": "finalized" }]
                });
                let resp = client.post(url).json(&body).send().await.map_err(|_| NoSlot::NoAnswer)?;
                if !resp.status().is_success() {
                    return Err(NoSlot::Status(resp.status().as_u16()));
                }
                let parsed: RpcResponse = resp.json().await.map_err(|_| NoSlot::NotASlot)?;
                let slot = parsed.result.ok_or(NoSlot::NotASlot)?;
                *cache = Some((slot, Instant::now()));
                Ok(slot)
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::{Arc, Mutex as StdMutex};

    use tokio::io::{AsyncReadExt, AsyncWriteExt};

    /// A made-up key, put in the userinfo, the path and the query string of the RPC URL.
    const KEY: &str = "SECRET-KEY-123";

    #[tokio::test]
    async fn fixed_and_unreachable() {
        assert_eq!(SlotSource::Fixed(42).current_slot().await.unwrap(), 42);
        let rpc = SlotSource::rpc("http://127.0.0.1:9".into()).unwrap();
        assert!(rpc.current_slot().await.is_err());
    }

    /// Everything logged on this thread, down to TRACE, until the guard is dropped.
    fn capture_log() -> (Arc<StdMutex<Vec<u8>>>, tracing::subscriber::DefaultGuard) {
        #[derive(Clone)]
        struct Sink(Arc<StdMutex<Vec<u8>>>);
        impl std::io::Write for Sink {
            fn write(&mut self, buf: &[u8]) -> std::io::Result<usize> {
                self.0.lock().unwrap().extend_from_slice(buf);
                Ok(buf.len())
            }
            fn flush(&mut self) -> std::io::Result<()> {
                Ok(())
            }
        }
        let bytes = Arc::new(StdMutex::new(Vec::new()));
        let sink = Sink(Arc::clone(&bytes));
        let subscriber = tracing_subscriber::fmt()
            .with_max_level(tracing::Level::TRACE)
            .with_ansi(false)
            .with_writer(move || sink.clone())
            .finish();
        (bytes, tracing::subscriber::set_default(subscriber))
    }

    fn logged(bytes: &Arc<StdMutex<Vec<u8>>>) -> String {
        String::from_utf8_lossy(&bytes.lock().unwrap()).into_owned()
    }

    /// A stand-in RPC on loopback that answers one request with `status` and `body`, and
    /// hands back the request it was sent.
    async fn stand_in_rpc(status: &'static str, body: &'static str) -> (u16, tokio::task::JoinHandle<String>) {
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let port = listener.local_addr().unwrap().port();
        let served = tokio::spawn(async move {
            let (mut stream, _) = listener.accept().await.unwrap();
            let mut request = Vec::new();
            let mut buf = [0u8; 4096];
            // Read the head, then as many body bytes as its content-length says.
            loop {
                let text = String::from_utf8_lossy(&request).to_ascii_lowercase();
                if let Some((head, body)) = text.split_once("\r\n\r\n") {
                    let length = head.lines().find_map(|l| l.strip_prefix("content-length:")).unwrap();
                    if body.len() >= length.trim().parse::<usize>().unwrap() {
                        break;
                    }
                }
                let n = stream.read(&mut buf).await.unwrap();
                assert!(n > 0, "the request ended early");
                request.extend_from_slice(&buf[..n]);
            }
            let answer = format!(
                "HTTP/1.1 {status}\r\ncontent-type: application/json\r\ncontent-length: {}\r\nconnection: close\r\n\r\n{body}",
                body.len()
            );
            stream.write_all(answer.as_bytes()).await.unwrap();
            String::from_utf8_lossy(&request).into_owned()
        });
        (port, served)
    }

    fn keyed_url(port: u16) -> String {
        format!("http://user:{KEY}@127.0.0.1:{port}/v2/{KEY}/?api-key={KEY}")
    }

    #[tokio::test]
    async fn the_start_report_gives_the_slot_and_the_rpc_gets_the_whole_url() {
        let (log, _guard) = capture_log();
        let (port, served) = stand_in_rpc("200 OK", r#"{"jsonrpc":"2.0","id":1,"result":453000000}"#).await;
        let rpc = SlotSource::rpc(keyed_url(port)).unwrap();
        assert!(rpc.report_at_start().await);
        let request = served.await.unwrap();
        // The URL is used whole: the key in the path and the query reach the RPC.
        assert!(request.starts_with(&format!("POST /v2/{KEY}/?api-key={KEY} HTTP/1.1\r\n")), "{request}");
        assert!(
            request.contains(r#""method":"getSlot""#) && request.contains(r#""commitment":"finalized""#),
            "{request}"
        );
        // The slot is cached for the calls of the next seconds. (Looked at directly, so that the
        // test does not depend on being quicker than the cache's five seconds.)
        let SlotSource::Rpc { cache, .. } = &rpc else { unreachable!() };
        let cached = *cache.lock().await;
        assert_eq!(cached.map(|(slot, _)| slot), Some(453_000_000));
        let log = logged(&log);
        assert!(log.contains("slot source answered") && log.contains("453000000"), "{log}");
        assert!(!log.contains(KEY), "{log}");

        let (fixed_log, _guard) = capture_log();
        assert!(SlotSource::Fixed(42).report_at_start().await);
        assert!(logged(&fixed_log).contains("slot source answered slot=42"));
    }

    /// The three ways no slot comes: each warns with its reason, and none shows the URL, at
    /// any log level.
    #[tokio::test]
    async fn the_start_report_warns_with_a_reason_and_never_shows_the_url() {
        for (status, body, reason) in [
            ("403 Forbidden", r#"{"error":"your address is not allowed"}"#, "HTTP status 403"),
            ("429 Too Many Requests", "", "HTTP status 429"),
            ("200 OK", r#"{"jsonrpc":"2.0","id":1,"error":{"code":-32000,"message":"no"}}"#, "not a getSlot result"),
            ("200 OK", "not json", "not a getSlot result"),
        ] {
            let (log, _guard) = capture_log();
            let (port, served) = stand_in_rpc(status, body).await;
            let rpc = SlotSource::rpc(keyed_url(port)).unwrap();
            assert!(!rpc.report_at_start().await, "{status} {body}");
            served.await.unwrap();
            // A failure is not cached. (Looked at directly: the stand-in's port is free again
            // and may by now belong to another test.)
            let SlotSource::Rpc { cache, .. } = &rpc else { unreachable!() };
            assert!(cache.lock().await.is_none());
            let log = logged(&log);
            assert!(log.contains("WARN") && log.contains("slot source gave no slot"), "{log}");
            assert!(
                log.contains(reason) && log.contains("503 slot_unavailable") && log.contains("HD_RPC_URL"),
                "{log}"
            );
            assert!(!log.contains(KEY) && !log.contains("not allowed"), "{log}");
        }
        // Nothing listens on port 9.
        let (log, _guard) = capture_log();
        let rpc = SlotSource::rpc(keyed_url(9)).unwrap();
        assert!(!rpc.report_at_start().await);
        let log = logged(&log);
        assert!(log.contains("WARN") && log.contains("no HTTP answer"), "{log}");
        assert!(!log.contains(KEY), "{log}");
    }
}
