//! Phone-signed BREAK / FREEZE: accept once, land promptly, never resubmit a stale counter.
//!
//! The intake verifies a signal ([`crate::heartbeat::Verifier::process_signal`]) and offers
//! it to the [`SignalHub`]. The hub decides, before the phone gets its ack:
//!
//! - **idempotency**: per rig it remembers the highest counter it accepted and that message's
//!   digest. The same message again is acknowledged `accepted` and not queued twice; any other
//!   message with a counter at or below it is `stale_counter` and is never resubmitted;
//! - **rate limits**: a per-rig token bucket and a lamport budget for the fees the crank pays
//!   (each signal costs one transaction signature, one secp256r1 signature and the priority
//!   fee; there is no on-chain reimbursement for BREAK / FREEZE);
//! - **queueing**: a bounded channel to the lander task in [`crate::crank`].
//!
//! While a signal is queued or in flight its rig is **pending**: the dig planner leaves the
//! rig out of the round ([`crate::planner::Skip::SignalPending`]), so a phone that was just
//! picked up is not dug by a lease it held a moment earlier.

use std::collections::HashMap;
use std::sync::Mutex;
use std::time::{Duration, Instant};

use solana_address::Address;
use tokio::sync::mpsc;

use crate::hd::{RigState, SignalKind};
use crate::heartbeat::{Reject, VerifiedSignal};
use crate::ratelimit::{KeyedLimiter, Quota};

/// A lamport budget that refills continuously: `capacity` lamports, refilled over `period`.
#[derive(Debug)]
pub struct FeeBudget {
    capacity: u64,
    per_second: f64,
    state: Mutex<(f64, Instant)>,
}

impl FeeBudget {
    /// A full budget of `capacity` lamports that refills `capacity` per `period`.
    pub fn new(capacity: u64, period: Duration) -> Self {
        let secs = period.as_secs_f64().max(1.0);
        FeeBudget { capacity, per_second: capacity as f64 / secs, state: Mutex::new((capacity as f64, Instant::now())) }
    }

    fn lock(&self) -> std::sync::MutexGuard<'_, (f64, Instant)> {
        match self.state.lock() {
            Ok(g) => g,
            Err(p) => p.into_inner(),
        }
    }

    /// Take `lamports` at `now` if the budget holds them.
    pub fn try_take_at(&self, lamports: u64, now: Instant) -> bool {
        let mut s = self.lock();
        let elapsed = now.saturating_duration_since(s.1).as_secs_f64();
        s.0 = (s.0 + elapsed * self.per_second).min(self.capacity as f64);
        s.1 = now;
        if s.0 >= lamports as f64 {
            s.0 -= lamports as f64;
            true
        } else {
            false
        }
    }

    /// [`Self::try_take_at`] now.
    pub fn try_take(&self, lamports: u64) -> bool {
        self.try_take_at(lamports, Instant::now())
    }

    /// Give back lamports that were not spent (capped at the capacity).
    pub fn refund(&self, lamports: u64) {
        let mut s = self.lock();
        s.0 = (s.0 + lamports as f64).min(self.capacity as f64);
    }

    /// Lamports available now.
    pub fn available(&self) -> u64 {
        let s = self.lock();
        let elapsed = Instant::now().saturating_duration_since(s.1).as_secs_f64();
        (s.0 + elapsed * self.per_second).min(self.capacity as f64) as u64
    }
}

/// Where a tracked signal is.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum SignalState {
    /// Queued or being landed.
    Pending,
    /// Landed on-chain.
    Landed,
    /// The program refused it (in simulation or on-chain: its counter was already consumed,
    /// or the rig's state no longer allows it). Never resubmitted.
    Refused,
    /// Not landed for a transient reason (blockhash expiry, RPC). The phone may send the same
    /// message again and it is queued again (its counter is re-checked first).
    Failed,
    /// Accepted but not landed because the chain already reflects it (a FREEZE on a Frozen rig).
    Moot,
}

#[derive(Clone, Copy, Debug)]
struct Tracked {
    counter: u64,
    digest: [u8; 32],
    state: SignalState,
    at: Instant,
}

/// Result of [`SignalHub::offer`].
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Offered {
    /// Queued for landing.
    Queued,
    /// The same message was already accepted: acknowledged again, not queued again.
    Duplicate,
    /// Nothing to land: the rig is already in the state the signal asks for.
    Moot,
}

/// Hub knobs.
#[derive(Clone, Copy, Debug)]
pub struct SignalHubConfig {
    /// Land signals at all.
    pub enabled: bool,
    /// Per-rig token bucket.
    pub rig_quota: Quota,
    /// Fee budget: lamports per hour.
    pub max_lamports_per_hour: u64,
    /// Estimated lamports per landed signal (taken from the budget up front).
    pub est_fee: u64,
    /// Queue length.
    pub queue: usize,
    /// Rigs tracked at most.
    pub max_rigs: usize,
}

impl Default for SignalHubConfig {
    fn default() -> Self {
        SignalHubConfig {
            enabled: true,
            rig_quota: Quota::new(4, 1.0 / 60.0),
            max_lamports_per_hour: 2_000_000,
            est_fee: 10_100,
            queue: 256,
            max_rigs: 100_000,
        }
    }
}

/// Shared between the intake (offers) and the crank (lands).
pub struct SignalHub {
    cfg: SignalHubConfig,
    tx: mpsc::Sender<VerifiedSignal>,
    rx: Mutex<Option<mpsc::Receiver<VerifiedSignal>>>,
    tracked: Mutex<HashMap<Address, Tracked>>,
    limiter: KeyedLimiter<Address>,
    budget: FeeBudget,
}

impl SignalHub {
    /// New hub; the lander takes the receiving end with [`Self::take_receiver`].
    pub fn new(cfg: SignalHubConfig) -> Self {
        let (tx, rx) = mpsc::channel(cfg.queue.max(1));
        SignalHub {
            tx,
            rx: Mutex::new(Some(rx)),
            tracked: Mutex::new(HashMap::new()),
            limiter: KeyedLimiter::new(cfg.rig_quota, cfg.max_rigs.max(1)),
            budget: FeeBudget::new(cfg.max_lamports_per_hour, Duration::from_secs(3600)),
            cfg,
        }
    }

    fn lock(&self) -> std::sync::MutexGuard<'_, HashMap<Address, Tracked>> {
        match self.tracked.lock() {
            Ok(g) => g,
            Err(p) => p.into_inner(),
        }
    }

    /// The receiving end of the landing queue (once).
    pub fn take_receiver(&self) -> Option<mpsc::Receiver<VerifiedSignal>> {
        match self.rx.lock() {
            Ok(mut g) => g.take(),
            Err(p) => p.into_inner().take(),
        }
    }

    /// Whether signals are landed at all.
    pub fn enabled(&self) -> bool {
        self.cfg.enabled
    }

    /// Highest counter accepted for `rig` (a heartbeat at or below it is stale).
    pub fn max_counter(&self, rig: &Address) -> Option<u64> {
        self.lock().get(rig).map(|t| t.counter)
    }

    /// True while a signal for `rig` is queued or being landed.
    pub fn is_pending(&self, rig: &Address) -> bool {
        self.lock().get(rig).is_some_and(|t| t.state == SignalState::Pending)
    }

    /// State of the last accepted signal for `rig`.
    pub fn state(&self, rig: &Address) -> Option<SignalState> {
        self.lock().get(rig).map(|t| t.state)
    }

    /// Record the outcome of landing `counter` for `rig`.
    pub fn mark(&self, rig: &Address, counter: u64, state: SignalState) {
        if let Some(t) = self.lock().get_mut(rig) {
            if t.counter == counter {
                t.state = state;
                t.at = Instant::now();
            }
        }
    }

    /// Give back the fee budget of a signal that was not sent.
    pub fn refund(&self, lamports: u64) {
        self.budget.refund(lamports);
    }

    /// Lamports left in the fee budget.
    pub fn budget_available(&self) -> u64 {
        self.budget.available()
    }

    /// Per-rig rate limit, checked before the (costlier) verification.
    pub fn check_rate(&self, rig: &Address) -> Result<(), Reject> {
        if !self.cfg.enabled {
            return Err(Reject::SignalsDisabled);
        }
        if self.limiter.check(rig) {
            Ok(())
        } else {
            Err(Reject::RateLimitedRig)
        }
    }

    /// Accept a verified signal for landing (see the module docs for the rules).
    pub fn offer(&self, v: VerifiedSignal) -> Result<Offered, Reject> {
        if !self.cfg.enabled {
            return Err(Reject::SignalsDisabled);
        }
        let mut m = self.lock();
        if let Some(t) = m.get(&v.rig) {
            if v.counter < t.counter || (v.counter == t.counter && v.digest != t.digest) {
                return Err(Reject::StaleCounter);
            }
            if v.counter == t.counter {
                match t.state {
                    SignalState::Pending | SignalState::Landed | SignalState::Moot => return Ok(Offered::Duplicate),
                    SignalState::Refused => return Err(Reject::StaleCounter),
                    SignalState::Failed => {} // transient: queue the same message again
                }
            }
        } else if m.len() >= self.cfg.max_rigs {
            let now = Instant::now();
            m.retain(|_, t| t.state == SignalState::Pending || now.saturating_duration_since(t.at) < Duration::from_secs(3600));
            if m.len() >= self.cfg.max_rigs {
                return Err(Reject::Busy);
            }
        }
        let track = |state| Tracked { counter: v.counter, digest: v.digest, state, at: Instant::now() };
        // A FREEZE for a rig that is already Frozen changes nothing on-chain but its counter:
        // acknowledge it and save the fee.
        if v.kind == SignalKind::Freeze && v.rig_state == RigState::Frozen {
            m.insert(v.rig, track(SignalState::Moot));
            return Ok(Offered::Moot);
        }
        if !self.budget.try_take(self.cfg.est_fee) {
            return Err(Reject::SignalBudget);
        }
        if self.tx.try_send(v).is_err() {
            self.budget.refund(self.cfg.est_fee);
            return Err(Reject::Busy);
        }
        m.insert(v.rig, track(SignalState::Pending));
        Ok(Offered::Queued)
    }

    /// Forget settled entries older than `max_age` (pending ones are kept).
    pub fn prune(&self, max_age: Duration) {
        let now = Instant::now();
        self.lock().retain(|_, t| t.state == SignalState::Pending || now.saturating_duration_since(t.at) < max_age);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sig(rig: u8, counter: u64, digest: u8, kind: SignalKind, state: RigState) -> VerifiedSignal {
        VerifiedSignal {
            kind,
            rig: Address::new_from_array([rig; 32]),
            authority: Address::new_from_array([9; 32]),
            counter,
            shift_id: 1,
            reason: if kind == SignalKind::Break { 1 } else { 3 },
            sig: [0; 64],
            pubkey: [2; 33],
            digest: [digest; 32],
            rig_state: state,
        }
    }

    #[test]
    fn idempotent_and_never_resubmits_a_stale_counter() {
        let hub = SignalHub::new(SignalHubConfig::default());
        let mut rx = hub.take_receiver().unwrap();
        assert!(hub.take_receiver().is_none());
        let a = sig(1, 10, 1, SignalKind::Break, RigState::Down);
        assert_eq!(hub.offer(a), Ok(Offered::Queued));
        assert!(hub.is_pending(&a.rig));
        assert_eq!(hub.offer(a), Ok(Offered::Duplicate), "the same message again");
        assert_eq!(hub.offer(sig(1, 10, 2, SignalKind::Break, RigState::Down)), Err(Reject::StaleCounter), "same counter, other message");
        assert_eq!(hub.offer(sig(1, 9, 3, SignalKind::Break, RigState::Down)), Err(Reject::StaleCounter));
        assert_eq!(rx.try_recv().unwrap(), a);
        assert!(rx.try_recv().is_err(), "queued once");
        hub.mark(&a.rig, 10, SignalState::Landed);
        assert!(!hub.is_pending(&a.rig));
        assert_eq!(hub.offer(a), Ok(Offered::Duplicate), "a landed signal is acknowledged, not resubmitted");
        assert!(rx.try_recv().is_err());
        hub.mark(&a.rig, 10, SignalState::Refused);
        assert_eq!(hub.offer(a), Err(Reject::StaleCounter), "a refused counter is never resubmitted");
        assert!(rx.try_recv().is_err());
        hub.mark(&a.rig, 10, SignalState::Failed);
        assert_eq!(hub.offer(a), Ok(Offered::Queued), "a transient failure may be retried by the phone");
        assert_eq!(rx.try_recv().unwrap(), a);
        assert_eq!(hub.offer(sig(1, 11, 4, SignalKind::Freeze, RigState::Cooling)), Ok(Offered::Queued));
        assert_eq!(hub.max_counter(&a.rig), Some(11));
        // A FREEZE on a Frozen rig: acknowledged, nothing to land.
        assert_eq!(hub.offer(sig(2, 5, 5, SignalKind::Freeze, RigState::Frozen)), Ok(Offered::Moot));
        assert_eq!(hub.state(&Address::new_from_array([2; 32])), Some(SignalState::Moot));
    }

    #[test]
    fn budget_rate_limit_and_disabled() {
        let cfg = SignalHubConfig { max_lamports_per_hour: 25_000, est_fee: 10_000, rig_quota: Quota::new(1, 0.0001), ..SignalHubConfig::default() };
        let hub = SignalHub::new(cfg);
        let _rx = hub.take_receiver();
        assert_eq!(hub.check_rate(&Address::new_from_array([1; 32])), Ok(()));
        assert_eq!(hub.check_rate(&Address::new_from_array([1; 32])), Err(Reject::RateLimitedRig));
        assert_eq!(hub.offer(sig(1, 1, 1, SignalKind::Break, RigState::Down)), Ok(Offered::Queued));
        assert_eq!(hub.offer(sig(2, 1, 1, SignalKind::Break, RigState::Down)), Ok(Offered::Queued));
        assert_eq!(hub.offer(sig(3, 1, 1, SignalKind::Break, RigState::Down)), Err(Reject::SignalBudget));
        hub.refund(10_000);
        assert_eq!(hub.offer(sig(3, 1, 1, SignalKind::Break, RigState::Down)), Ok(Offered::Queued));
        let off = SignalHub::new(SignalHubConfig { enabled: false, ..SignalHubConfig::default() });
        assert_eq!(off.offer(sig(1, 1, 1, SignalKind::Break, RigState::Down)), Err(Reject::SignalsDisabled));
        assert_eq!(off.check_rate(&Address::new_from_array([1; 32])), Err(Reject::SignalsDisabled));
        assert_eq!(Reject::SignalsDisabled.ack_code(), "rate_limited");
    }

    #[test]
    fn queue_full_is_busy_and_refunds() {
        let cfg = SignalHubConfig { queue: 1, max_lamports_per_hour: 100_000, est_fee: 10_000, ..SignalHubConfig::default() };
        let hub = SignalHub::new(cfg);
        let _rx = hub.take_receiver();
        assert_eq!(hub.offer(sig(1, 1, 1, SignalKind::Break, RigState::Down)), Ok(Offered::Queued));
        let before = hub.budget_available();
        assert_eq!(hub.offer(sig(2, 1, 1, SignalKind::Break, RigState::Down)), Err(Reject::Busy));
        assert!(hub.budget_available() >= before, "refunded");
    }

    #[test]
    fn fee_budget_refills() {
        let b = FeeBudget::new(3_600, Duration::from_secs(3600));
        let t0 = Instant::now();
        assert!(b.try_take_at(3_600, t0));
        assert!(!b.try_take_at(1, t0));
        assert!(b.try_take_at(10, t0 + Duration::from_secs(10)), "1 lamport per second");
        assert!(!b.try_take_at(10, t0 + Duration::from_secs(10)));
    }
}
