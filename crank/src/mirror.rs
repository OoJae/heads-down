//! Optional public mirror of verified heartbeats.
//!
//! THREAT_MODEL K3 lists "heartbeats mirrored to a public Nostr relay" as a mitigation for a
//! crank that withholds heartbeats: anyone can pick them up and submit them through their
//! own crank. Heartbeats are self-authenticating, so mirroring them leaks nothing a
//! competing crank could abuse; it only adds liveness.
//!
//! The `nostr` feature compiles a **stub**: it builds the NIP-01 event body (kind 30078,
//! application-specific data, `d` tag = rig) and queues it, but signing with a secp256k1
//! key and publishing to a relay are not implemented yet.

use serde_json::{json, Value};

use crate::heartbeat::VerifiedHeartbeat;

/// Receives every heartbeat the intake accepts.
pub trait HeartbeatMirror: Send + Sync + 'static {
    /// Called after verification; must not block.
    fn mirror(&self, hb: &VerifiedHeartbeat);
}

/// No mirror.
#[derive(Debug, Default, Clone, Copy)]
pub struct NoMirror;

impl HeartbeatMirror for NoMirror {
    fn mirror(&self, _hb: &VerifiedHeartbeat) {}
}

/// Passes every accepted heartbeat on to `inner`, and wakes the Stack loop when the rig is
/// seated at an open table: a seat's check-in should leave within moments of its phone's
/// heartbeat for the round, not at the next timer tick.
pub struct NudgeMirror {
    /// The mirror proper.
    pub inner: std::sync::Arc<dyn HeartbeatMirror>,
    /// Shared with the crank loop.
    pub nudge: crate::crank::StackNudge,
}

impl HeartbeatMirror for NudgeMirror {
    fn mirror(&self, hb: &VerifiedHeartbeat) {
        self.inner.mirror(hb);
        self.nudge.heartbeat(&hb.rig);
    }
}

/// The unsigned NIP-01 event a mirror would publish (kind 30078, replaceable per rig).
pub fn nostr_event_template(hb: &VerifiedHeartbeat, created_at: i64) -> Value {
    json!({
        "kind": 30078,
        "created_at": created_at,
        "tags": [["d", format!("heads-down:{}", hb.rig)], ["t", "heads-down-heartbeat"]],
        "content": json!({
            "rig": hb.rig.to_string(),
            "counter": hb.fields.counter,
            "shift_id": hb.fields.shift_id,
            "round_id": hb.fields.round_id,
            "lease_rounds": hb.fields.lease_rounds,
            "sig64": hex::encode(hb.sig),
            "pubkey": hex::encode(hb.pubkey),
        }).to_string(),
    })
}

/// Nostr mirror stub: queues event templates on a bounded channel (dropping when full,
/// never blocking the intake). A consumer task logs them at debug level.
#[cfg(feature = "nostr")]
pub struct NostrMirror {
    tx: tokio::sync::mpsc::Sender<Value>,
}

#[cfg(feature = "nostr")]
impl NostrMirror {
    /// Spawn the consumer; `relay` is only logged (publishing is not implemented).
    pub fn spawn(relay: String) -> Self {
        let (tx, mut rx) = tokio::sync::mpsc::channel::<Value>(1024);
        tokio::spawn(async move {
            while let Some(ev) = rx.recv().await {
                tracing::debug!(%relay, event = %ev, "nostr mirror (stub): would sign and publish");
            }
        });
        NostrMirror { tx }
    }
}

#[cfg(feature = "nostr")]
impl HeartbeatMirror for NostrMirror {
    fn mirror(&self, hb: &VerifiedHeartbeat) {
        let now = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_secs() as i64)
            .unwrap_or(0);
        let _ = self.tx.try_send(nostr_event_template(hb, now));
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::hd::HeartbeatFields;
    use solana_address::Address;

    #[test]
    fn template_carries_everything_needed_to_resubmit() {
        let hb = VerifiedHeartbeat {
            rig: Address::new_from_array([1; 32]),
            fields: HeartbeatFields { counter: 3, shift_id: 2, round_id: 9, lease_rounds: 1 },
            sig: [7; 64],
            pubkey: [2; 33],
            digest: [0; 32],
        };
        let ev = nostr_event_template(&hb, 1_790_000_000);
        assert_eq!(ev["kind"], 30078);
        let content: Value = serde_json::from_str(ev["content"].as_str().unwrap()).unwrap();
        assert_eq!(content["counter"], 3);
        assert_eq!(content["sig64"].as_str().unwrap().len(), 128);
    }
}
