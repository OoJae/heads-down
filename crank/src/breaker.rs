//! Circuit breaker: stop digging the moment ORE stops looking like the ORE we pinned.
//!
//! This is Level 3 of `docs/ORE.md` section 8. The program's own Level-1 pins decide
//! on-chain; the crank's breaker keeps it from paying for transactions that the program
//! would refuse, and makes the deviation loud (log, metric, `/healthz` 503).
//!
//! Trips on: a pinned ORE account (Board, Treasury, Round, ORE Config, and any ORE-owned
//! Automation or Miner) whose size, discriminator or sanity pins deviate; a heads_down
//! Config that no longer decodes; ORE's ProgramData upgrade slot moving off the pin.
//! It **latches**: only an operator restart clears it, after re-running the fork suite
//! against the new ORE binary.

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};

use solana_address::Address;

use crate::metrics::Metrics;
use crate::ore::{self, LayoutError, OreKind};

/// Latched breaker.
#[derive(Debug)]
pub struct Breaker {
    tripped: AtomicBool,
    reason: Mutex<Option<String>>,
    metrics: Arc<Metrics>,
}

impl Breaker {
    /// Closed breaker.
    pub fn new(metrics: Arc<Metrics>) -> Self {
        Breaker { tripped: AtomicBool::new(false), reason: Mutex::new(None), metrics }
    }

    /// Trip (idempotent; the first reason is kept).
    pub fn trip(&self, reason: impl Into<String>) {
        let reason = reason.into();
        if !self.tripped.swap(true, Ordering::SeqCst) {
            tracing::error!(%reason, "circuit breaker tripped: digging stopped until restart");
            if let Ok(mut r) = self.reason.lock() {
                *r = Some(reason);
            }
            self.metrics.breaker_tripped.set(1);
        }
    }

    /// Is digging stopped?
    pub fn is_tripped(&self) -> bool {
        self.tripped.load(Ordering::SeqCst)
    }

    /// Why it tripped.
    pub fn reason(&self) -> Option<String> {
        self.reason.lock().ok().and_then(|r| r.clone())
    }

    /// Record a pinned-account decode result; trips on any layout error.
    pub fn observe<T>(&self, what: &str, r: Result<T, LayoutError>) -> Option<T> {
        match r {
            Ok(v) => Some(v),
            Err(e) => {
                self.trip(format!("{what}: {e}"));
                None
            }
        }
    }

    /// An ORE-owned per-user account (Automation / Miner) that fails its size or
    /// discriminator pin means ORE changed a layout: trip. Accounts not owned by ORE (closed
    /// or never created) are the planner's business, not the breaker's.
    pub fn observe_user_account(&self, kind: OreKind, owner: &Address, data: &[u8]) {
        if owner == &ore::ORE_PROGRAM_ID && !data.is_empty() {
            if let Err(e) = ore::check_layout(kind, owner, data) {
                self.trip(format!("user {}: {e}", kind.label()));
            }
        }
    }

    /// ORE's ProgramData upgrade slot must equal the pin (0 disables the check).
    pub fn observe_programdata_slot(&self, observed: Option<u64>, pinned: u64) {
        if pinned == 0 {
            return;
        }
        match observed {
            Some(s) if s == pinned => {}
            Some(s) => self.trip(format!("ORE upgraded: ProgramData slot {s} != pinned {pinned}")),
            None => self.trip("ORE ProgramData unreadable or not owned by the upgradeable loader"),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn latches_on_first_reason() {
        let m = Arc::new(Metrics::default());
        let b = Breaker::new(m.clone());
        assert!(!b.is_tripped());
        b.observe_programdata_slot(Some(ore::PINNED_PROGRAMDATA_SLOT), ore::PINNED_PROGRAMDATA_SLOT);
        assert!(!b.is_tripped());
        b.observe_user_account(OreKind::Miner, &ore::SYSTEM_PROGRAM_ID, &[1, 2, 3]);
        assert!(!b.is_tripped(), "non-ORE accounts are not layout evidence");
        b.observe_user_account(OreKind::Miner, &ore::ORE_PROGRAM_ID, &vec![103u8; 760]);
        assert!(b.is_tripped());
        assert!(b.reason().unwrap().contains("miner"));
        b.observe_programdata_slot(Some(1), 2);
        assert!(b.reason().unwrap().contains("miner"), "first reason kept");
        assert_eq!(m.breaker_tripped.get(), 1);
    }

    #[test]
    fn programdata_moves_trip() {
        let b = Breaker::new(Arc::new(Metrics::default()));
        b.observe_programdata_slot(Some(ore::PINNED_PROGRAMDATA_SLOT + 1), ore::PINNED_PROGRAMDATA_SLOT);
        assert!(b.is_tripped());
        let b = Breaker::new(Arc::new(Metrics::default()));
        b.observe_programdata_slot(None, ore::PINNED_PROGRAMDATA_SLOT);
        assert!(b.is_tripped());
        let b = Breaker::new(Arc::new(Metrics::default()));
        b.observe_programdata_slot(None, 0);
        assert!(!b.is_tripped(), "pin disabled");
    }
}
