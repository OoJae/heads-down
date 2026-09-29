//! Send, rebroadcast and confirm.
//!
//! Transactions go out with `maxRetries: 0` and `skipPreflight: true` (the crank already
//! simulated them), and the crank rebroadcasts the *same signed bytes* until they confirm
//! or their blockhash expires, so a rebroadcast can never double-land. Re-signing with a
//! fresh blockhash is a separate, bounded decision taken by the crank loop after
//! re-planning from fresh chain state (see [`crate::ledger`]).
//!
//! With the `helius-sender` feature and `sender.helius_sender_url`, sends go to Helius
//! Sender (which requires `skipPreflight` and a tip transfer, added by the builder);
//! statuses are still read from the regular RPC.

use std::time::{Duration, Instant};

use serde_json::Value;
use solana_address::Address;

use crate::rpc::{RpcClient, RpcError};

/// How a submission ended.
#[derive(Clone, Debug, PartialEq)]
pub enum Outcome {
    /// Confirmed (with `err` if it failed on-chain).
    Landed {
        /// Slot.
        slot: u64,
        /// Transaction error, if any.
        err: Option<Value>,
    },
    /// The blockhash expired before it landed: it never will.
    Expired,
    /// Gave up waiting (the blockhash may still be valid; the ledger keeps it pending).
    Unknown,
}

/// Timing knobs.
#[derive(Clone, Copy, Debug)]
pub struct ConfirmPolicy {
    /// Status poll interval.
    pub poll_every: Duration,
    /// Rebroadcast interval.
    pub rebroadcast_every: Duration,
    /// Stop rebroadcasting after this long (e.g. the round window closed).
    pub rebroadcast_for: Duration,
    /// Give up waiting after this long.
    pub max_wait: Duration,
}

impl Default for ConfirmPolicy {
    fn default() -> Self {
        ConfirmPolicy {
            poll_every: Duration::from_millis(400),
            rebroadcast_every: Duration::from_secs(2),
            rebroadcast_for: Duration::from_secs(20),
            max_wait: Duration::from_secs(90),
        }
    }
}

/// Where transactions are sent.
pub struct Submitter {
    rpc: RpcClient,
    send_via: Option<RpcClient>,
    tip_accounts: Vec<Address>,
    tip_lamports: u64,
}

impl Submitter {
    /// Plain RPC submitter.
    pub fn rpc(rpc: RpcClient) -> Self {
        Submitter { rpc, send_via: None, tip_accounts: vec![], tip_lamports: 0 }
    }

    /// Submit through Helius Sender; statuses still come from `rpc`.
    #[cfg(feature = "helius-sender")]
    pub fn helius_sender(rpc: RpcClient, sender: RpcClient, tip_accounts: Vec<Address>, tip_lamports: u64) -> Self {
        Submitter { rpc, send_via: Some(sender), tip_accounts, tip_lamports }
    }

    /// The tip the builder must append for this submitter (`None` for plain RPC). The
    /// recipient rotates with `nonce` so tips spread over the configured accounts.
    pub fn tip_for(&self, nonce: u64) -> Option<(Address, u64)> {
        if self.send_via.is_none() || self.tip_accounts.is_empty() || self.tip_lamports == 0 {
            return None;
        }
        let i = usize::try_from(nonce % self.tip_accounts.len() as u64).unwrap_or(0);
        self.tip_accounts.get(i).map(|a| (*a, self.tip_lamports))
    }

    /// Send once. Returns the signature string.
    pub async fn send(&self, wire: &[u8]) -> Result<String, RpcError> {
        match &self.send_via {
            Some(s) => s.send_transaction(wire, true).await,
            None => self.rpc.send_transaction(wire, true).await,
        }
    }

    /// Rebroadcast `wire` and poll until it confirms, its blockhash expires, or `max_wait`.
    pub async fn confirm(&self, signature: &str, wire: &[u8], last_valid_block_height: u64, p: ConfirmPolicy) -> Outcome {
        let start = Instant::now();
        let mut last_send = Instant::now();
        let sigs = [signature.to_string()];
        loop {
            tokio::time::sleep(p.poll_every).await;
            if let Ok(st) = self.rpc.get_signature_statuses(&sigs).await {
                if let Some(Some(s)) = st.into_iter().next() {
                    if s.is_confirmed() {
                        return Outcome::Landed { slot: s.slot, err: s.err };
                    }
                }
            }
            if let Ok(h) = self.rpc.get_block_height().await {
                if h > last_valid_block_height {
                    // One last status check: it may have landed right at the edge.
                    if let Ok(st) = self.rpc.get_signature_statuses(&sigs).await {
                        if let Some(Some(s)) = st.into_iter().next() {
                            return Outcome::Landed { slot: s.slot, err: s.err };
                        }
                    }
                    return Outcome::Expired;
                }
            }
            if start.elapsed() >= p.max_wait {
                return Outcome::Unknown;
            }
            if start.elapsed() < p.rebroadcast_for && last_send.elapsed() >= p.rebroadcast_every {
                let _ = self.send(wire).await;
                last_send = Instant::now();
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn plain_rpc_has_no_tip() {
        let rpc = RpcClient::new("http://127.0.0.1:1", "confirmed", Duration::from_secs(1)).unwrap();
        let s = Submitter::rpc(rpc);
        assert_eq!(s.tip_for(0), None);
    }
}
