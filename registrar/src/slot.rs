//! Current Solana slot, used to compute each voucher's `expiry_slot`.
//!
//! The registrar asks an RPC node for the **finalized** slot (the lowest, most conservative
//! of the commitment levels), caches it for a few seconds, and never guesses: if no RPC answer
//! is available, `/attest` fails with 503 before the nonce is consumed.

use std::time::{Duration, Instant};

use serde::Deserialize;
use tokio::sync::Mutex;

const CACHE_FOR: Duration = Duration::from_secs(5);

pub enum SlotSource {
    Rpc { url: String, client: reqwest::Client, cache: Mutex<Option<(u64, Instant)>> },
    /// Tests and offline development.
    Fixed(u64),
}

#[derive(Debug, thiserror::Error)]
#[error("current slot unavailable")]
pub struct SlotError;

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
                let resp = client.post(url).json(&body).send().await.map_err(|_| SlotError)?;
                if !resp.status().is_success() {
                    return Err(SlotError);
                }
                let parsed: RpcResponse = resp.json().await.map_err(|_| SlotError)?;
                let slot = parsed.result.ok_or(SlotError)?;
                *cache = Some((slot, Instant::now()));
                Ok(slot)
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn fixed_and_unreachable() {
        assert_eq!(SlotSource::Fixed(42).current_slot().await.unwrap(), 42);
        let rpc = SlotSource::rpc("http://127.0.0.1:9".into()).unwrap();
        assert!(rpc.current_slot().await.is_err());
    }
}
