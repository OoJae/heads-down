//! Per-(rig, round) idempotency.
//!
//! The program already refuses a second dig in the same ORE round (`last_dug_round`) and a
//! reused heartbeat counter, so a duplicate submission can never double-deploy. The ledger
//! exists so the crank does not **pay** for duplicates: a rig is offered to a new
//! transaction for a round only if nothing that includes it for that round can still land.

use std::collections::HashMap;

use solana_address::Address;

/// State of one (rig, round).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum DigStatus {
    /// In a transaction that may still land until `last_valid_block_height`.
    Pending {
        /// Signature (first) of the transaction.
        signature: [u8; 64],
        /// The blockhash expiry.
        last_valid_block_height: u64,
    },
    /// The program emitted RigDug for it.
    Landed,
    /// The program emitted RigSkipped (or the tx landed without an event for it).
    SkippedOnChain(u32),
    /// The transaction failed or expired; may be retried in the same round.
    Failed,
}

/// Bounded map of (rig, round) → status.
#[derive(Debug, Default)]
pub struct Ledger {
    entries: HashMap<(Address, u64), DigStatus>,
}

impl Ledger {
    /// Empty ledger.
    pub fn new() -> Self {
        Self::default()
    }

    /// Current status.
    pub fn status(&self, rig: &Address, round: u64) -> Option<DigStatus> {
        self.entries.get(&(*rig, round)).copied()
    }

    /// May the crank put `rig` in a new transaction for `round`, given the current block
    /// height? Yes if never tried, if the last attempt failed, or if the pending attempt's
    /// blockhash has expired (it can no longer land).
    pub fn can_submit(&self, rig: &Address, round: u64, block_height: u64) -> bool {
        match self.status(rig, round) {
            None | Some(DigStatus::Failed) => true,
            Some(DigStatus::Pending { last_valid_block_height, .. }) => block_height > last_valid_block_height,
            Some(DigStatus::Landed) | Some(DigStatus::SkippedOnChain(_)) => false,
        }
    }

    /// Record a submission.
    pub fn mark_pending(&mut self, rigs: &[Address], round: u64, signature: [u8; 64], last_valid_block_height: u64) {
        for r in rigs {
            self.entries.insert((*r, round), DigStatus::Pending { signature, last_valid_block_height });
        }
    }

    /// Record an outcome.
    pub fn mark(&mut self, rig: &Address, round: u64, status: DigStatus) {
        self.entries.insert((*rig, round), status);
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
    fn idempotent_per_rig_and_round() {
        let mut l = Ledger::new();
        let a = Address::new_from_array([1; 32]);
        let b = Address::new_from_array([2; 32]);
        assert!(l.can_submit(&a, 10, 0));
        l.mark_pending(&[a, b], 10, [9; 64], 1_000);
        assert!(!l.can_submit(&a, 10, 1_000), "may still land");
        assert!(l.can_submit(&a, 10, 1_001), "blockhash expired: retry allowed");
        assert!(l.can_submit(&a, 11, 0), "other round");
        l.mark(&a, 10, DigStatus::Landed);
        assert!(!l.can_submit(&a, 10, 5_000));
        l.mark(&b, 10, DigStatus::Failed);
        assert!(l.can_submit(&b, 10, 0));
        l.mark(&b, 10, DigStatus::SkippedOnChain(9));
        assert!(!l.can_submit(&b, 10, 0));
        l.prune(11);
        assert!(l.is_empty());
    }
}
