//! Per-(rig, round) idempotency and retry accounting.
//!
//! The program already refuses a second dig in the same ORE round (`last_dug_round`) and a
//! reused heartbeat counter, so a duplicate submission can never double-deploy. The ledger
//! exists so the crank does not **pay** for duplicates: a rig goes into a new transaction
//! for a round only if its previous one can no longer land (blockhash expired), failed,
//! or has gone unconfirmed for `retry_after_slots` (a deliberate retry, bounded by
//! `max_attempts`; if both land, the program skips the second).

use std::collections::HashMap;

use solana_address::Address;

/// State of one (rig, round).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum DigStatus {
    /// In a transaction that may still land until `last_valid_block_height`.
    Pending {
        /// First signature of the transaction.
        signature: [u8; 64],
        /// The blockhash expiry.
        last_valid_block_height: u64,
        /// Slot when it was sent.
        sent_slot: u64,
    },
    /// The program emitted RigDug for it.
    Landed,
    /// The program emitted RigSkipped (the rig is not retried this round).
    SkippedOnChain(u32),
    /// The transaction failed, expired or never simulated cleanly.
    Failed,
}

/// Retry knobs.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct RetryPolicy {
    /// Re-send (fresh blockhash, re-planned) after this many unconfirmed slots.
    pub retry_after_slots: u64,
    /// Attempts per (rig, round).
    pub max_attempts: u32,
}

impl Default for RetryPolicy {
    fn default() -> Self {
        RetryPolicy { retry_after_slots: 6, max_attempts: 2 }
    }
}

#[derive(Clone, Copy, Debug)]
struct Entry {
    status: DigStatus,
    attempts: u32,
}

/// Map of (rig, round) → status.
#[derive(Debug, Default)]
pub struct Ledger {
    entries: HashMap<(Address, u64), Entry>,
}

impl Ledger {
    /// Empty ledger.
    pub fn new() -> Self {
        Self::default()
    }

    /// Current status.
    pub fn status(&self, rig: &Address, round: u64) -> Option<DigStatus> {
        self.entries.get(&(*rig, round)).map(|e| e.status)
    }

    /// Attempts made.
    pub fn attempts(&self, rig: &Address, round: u64) -> u32 {
        self.entries.get(&(*rig, round)).map_or(0, |e| e.attempts)
    }

    /// May `rig` go into a new transaction for `round`?
    pub fn can_submit(&self, rig: &Address, round: u64, block_height: u64, slot: u64, p: RetryPolicy) -> bool {
        let Some(e) = self.entries.get(&(*rig, round)) else {
            return true;
        };
        if e.attempts >= p.max_attempts {
            return false;
        }
        match e.status {
            DigStatus::Failed => true,
            DigStatus::Pending { last_valid_block_height, sent_slot, .. } => {
                block_height > last_valid_block_height || slot >= sent_slot.saturating_add(p.retry_after_slots)
            }
            DigStatus::Landed | DigStatus::SkippedOnChain(_) => false,
        }
    }

    /// Record a submission (one more attempt for each rig).
    pub fn mark_pending(&mut self, rigs: &[Address], round: u64, signature: [u8; 64], last_valid_block_height: u64, sent_slot: u64) {
        for r in rigs {
            let e = self.entries.entry((*r, round)).or_insert(Entry { status: DigStatus::Failed, attempts: 0 });
            e.attempts = e.attempts.saturating_add(1);
            e.status = DigStatus::Pending { signature, last_valid_block_height, sent_slot };
        }
    }

    /// An attempt that failed before it was sent (e.g. simulation): counts toward the limit.
    pub fn record_failed_attempt(&mut self, rig: &Address, round: u64) {
        let e = self.entries.entry((*rig, round)).or_insert(Entry { status: DigStatus::Failed, attempts: 0 });
        e.attempts = e.attempts.saturating_add(1);
        if e.status != DigStatus::Landed {
            e.status = DigStatus::Failed;
        }
    }

    /// Record an outcome. A late `Failed`/`Pending` never overwrites `Landed` (a retry may
    /// fail on-chain after the first attempt landed).
    pub fn mark(&mut self, rig: &Address, round: u64, status: DigStatus) {
        let e = self.entries.entry((*rig, round)).or_insert(Entry { status, attempts: 0 });
        if e.status == DigStatus::Landed && status != DigStatus::Landed {
            return;
        }
        e.status = status;
    }

    /// Forget rounds older than `keep_from`.
    pub fn prune(&mut self, keep_from: u64) {
        self.entries.retain(|(_, round), _| *round >= keep_from);
    }

    /// Entries held.
    pub fn len(&self) -> usize {
        self.entries.len()
    }

    /// No entries.
    pub fn is_empty(&self) -> bool {
        self.entries.is_empty()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn idempotent_per_rig_and_round_with_bounded_retries() {
        let p = RetryPolicy { retry_after_slots: 6, max_attempts: 2 };
        let mut l = Ledger::new();
        let a = Address::new_from_array([1; 32]);
        let b = Address::new_from_array([2; 32]);
        assert!(l.can_submit(&a, 10, 0, 100, p));
        l.mark_pending(&[a, b], 10, [9; 64], 1_000, 100);
        assert!(!l.can_submit(&a, 10, 500, 105, p), "pending, not yet stale");
        assert!(l.can_submit(&a, 10, 500, 106, p), "unconfirmed for 6 slots: one retry");
        assert!(l.can_submit(&a, 10, 1_001, 101, p), "blockhash expired");
        assert!(l.can_submit(&a, 11, 0, 0, p), "other round");
        l.mark_pending(&[a], 10, [8; 64], 2_000, 106);
        assert_eq!(l.attempts(&a, 10), 2);
        assert!(!l.can_submit(&a, 10, 9_999, 999, p), "attempts exhausted");
        l.mark(&a, 10, DigStatus::Landed);
        l.mark(&a, 10, DigStatus::Failed);
        assert_eq!(l.status(&a, 10), Some(DigStatus::Landed), "a failed retry does not undo a landing");
        l.mark(&b, 10, DigStatus::SkippedOnChain(9));
        assert!(!l.can_submit(&b, 10, 0, 0, p));
        l.prune(11);
        assert!(l.is_empty());
    }
}
