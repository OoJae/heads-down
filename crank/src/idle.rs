//! When a dig pass may skip its chain reads.
//!
//! A dig pass reads every Armed, Down and Cooling rig (three `getProgramAccounts` scans), then
//! their ORE accounts and the block height, up to three times per ORE round. While no phone
//! is heartbeating that finds nothing, round after round. [`IdleTracker`] decides from what
//! the crank already knows whether a pass can leave the chain alone. It can when all of
//! these hold:
//!
//! - no heartbeat is held;
//! - no rig of the last full read has a lease that covers this round;
//! - nothing was held, and no rig was a dig candidate, in the last [`AWAKE_ROUNDS`] rounds.
//!
//! The third rule exists because the rig list is read *before* a dig lands: a rig that was
//! just dug holds a new lease of up to three rounds that the cached list does not show yet.
//!
//! An idle crank still makes one full pass every `dig.idle_full_read_rounds` rounds, so that
//! a rig whose heartbeat another crank applied is seen while its lease runs. A lease lasts at
//! most three rounds, so only a value of 3 or less can never miss one; larger values are
//! cheaper. The first pass after start is always a full one, and `0` turns skipping off.
//!
//! No I/O here: the crank feeds the tracker and asks it ([`crate::crank::Crank::dig_tick`]).

/// Rounds the crank stays fully awake after the last round in which a heartbeat was held or
/// a rig was a dig candidate. A lease covers at most [`crate::hd::MAX_LEASE_ROUNDS`] (3)
/// rounds counting the one it was granted in, so 2 would be the minimum; the rest is margin
/// for a dig that lands late and for a round the crank did not see.
pub const AWAKE_ROUNDS: u64 = 4;

/// What a dig pass does, and why.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum Pass {
    /// `dig.idle_full_read_rounds = 0`: every pass reads the chain.
    Always,
    /// No full read has succeeded since the process started.
    First,
    /// A heartbeat is held.
    Heartbeat,
    /// A rig of the last full read has a lease that covers this round.
    Lease,
    /// A heartbeat was held, or a rig was a dig candidate, within the last [`AWAKE_ROUNDS`].
    Recent,
    /// Idle, and the periodic full read is due.
    Periodic,
    /// Idle: the pass reads nothing.
    Skip,
}

impl Pass {
    /// Does the pass read the chain?
    pub fn reads(self) -> bool {
        self != Pass::Skip
    }

    /// Stable snake_case label (the `why` of `hd_crank_dig_passes_total`).
    pub fn label(self) -> &'static str {
        match self {
            Pass::Always => "always",
            Pass::First => "first",
            Pass::Heartbeat => "heartbeat",
            Pass::Lease => "lease",
            Pass::Recent => "recent",
            Pass::Periodic => "periodic",
            Pass::Skip => "skipped",
        }
    }

    /// Every variant.
    pub const ALL: [Pass; 7] = [Pass::Always, Pass::First, Pass::Heartbeat, Pass::Lease, Pass::Recent, Pass::Periodic, Pass::Skip];
}

/// What the crank remembers between dig passes to tell an idle round from a busy one.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct IdleTracker {
    /// The last round in which a heartbeat was held or a rig was a dig candidate.
    last_active_round: Option<u64>,
    /// The round of the last pass that read the rigs.
    last_full_read_round: Option<u64>,
    /// The heartbeat store's accepted count when it was last looked at.
    heartbeats_seen: u64,
}

impl IdleTracker {
    /// A heartbeat was held, or a rig was a dig candidate, in `round`.
    pub fn note_active(&mut self, round: u64) {
        self.last_active_round = Some(self.last_active_round.map_or(round, |r| r.max(round)));
    }

    /// A pass read the rigs in `round`.
    pub fn note_full_read(&mut self, round: u64) {
        self.last_full_read_round = Some(round);
    }

    /// Look at the heartbeat store during `round`: `stored_total` counts every heartbeat it
    /// ever accepted, `held` says whether it holds one now. A heartbeat that came and went
    /// between two looks (applied by a Stack check-in or a record) still marks the round.
    pub fn note_store(&mut self, round: u64, stored_total: u64, held: bool) {
        if held || stored_total != self.heartbeats_seen {
            self.note_active(round);
        }
        self.heartbeats_seen = stored_total;
    }

    /// The last round something was held or was a candidate.
    pub fn last_active_round(&self) -> Option<u64> {
        self.last_active_round
    }

    /// The round of the last full read.
    pub fn last_full_read_round(&self) -> Option<u64> {
        self.last_full_read_round
    }

    /// What the pass of `round` does. `held`: the store holds a heartbeat now. `lease`: a rig
    /// of the last full read has a lease covering `round`. `full_read_every`: the
    /// `dig.idle_full_read_rounds` setting.
    pub fn decide(&self, round: u64, held: bool, lease: bool, full_read_every: u64) -> Pass {
        if full_read_every == 0 {
            return Pass::Always;
        }
        let Some(last_read) = self.last_full_read_round else {
            return Pass::First;
        };
        if held {
            return Pass::Heartbeat;
        }
        if lease {
            return Pass::Lease;
        }
        // A round id that went backwards (a test cluster that was reset) counts as recent.
        if self.last_active_round.is_some_and(|a| round <= a.saturating_add(AWAKE_ROUNDS)) {
            return Pass::Recent;
        }
        if round < last_read || round - last_read >= full_read_every {
            return Pass::Periodic;
        }
        Pass::Skip
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_first_pass_reads_and_an_idle_crank_then_reads_every_nth_round() {
        let mut t = IdleTracker::default();
        assert_eq!(t.decide(100, false, false, 10), Pass::First, "never skipped after start");
        // A full read that failed leaves no trace: the next pass tries again.
        assert_eq!(t.decide(100, false, false, 10), Pass::First);
        t.note_full_read(100);
        // The other passes of the round, and the nine rounds after it, read nothing.
        for round in 100..110 {
            assert_eq!(t.decide(round, false, false, 10), Pass::Skip, "round {round}");
        }
        assert_eq!(t.decide(110, false, false, 10), Pass::Periodic);
        t.note_full_read(110);
        assert_eq!(t.decide(110, false, false, 10), Pass::Skip, "one full read per period, not one per pass");
        assert_eq!(t.decide(119, false, false, 10), Pass::Skip);
        assert_eq!(t.decide(120, false, false, 10), Pass::Periodic);
        // Rounds the crank did not see (it was disconnected) do not delay the read.
        assert_eq!(t.decide(500, false, false, 10), Pass::Periodic);
        // Every round, and never.
        assert_eq!(t.decide(111, false, false, 1), Pass::Periodic);
        assert_eq!(t.decide(110, false, false, 1), Pass::Skip, "still once per round");
        assert_eq!(t.decide(110, false, false, 0), Pass::Always, "0 turns skipping off");
        assert_eq!(IdleTracker::default().decide(1, false, false, 0), Pass::Always);
    }

    #[test]
    fn a_held_heartbeat_or_a_known_lease_keeps_every_pass_full() {
        let mut t = IdleTracker::default();
        t.note_full_read(100);
        assert_eq!(t.decide(101, true, false, 10), Pass::Heartbeat);
        assert_eq!(t.decide(101, false, true, 10), Pass::Lease);
        assert_eq!(t.decide(101, true, true, 10), Pass::Heartbeat);
        assert_eq!(t.decide(101, false, false, 10), Pass::Skip);
    }

    #[test]
    fn the_crank_stays_awake_for_a_few_rounds_after_the_last_activity() {
        let mut t = IdleTracker::default();
        t.note_full_read(100);
        t.note_active(100);
        // The dig of round 100 landed after the rig list was read: the list shows no lease,
        // and the store is empty again. The rounds the new lease can cover are still read.
        let lease_end = 100 + u64::from(crate::hd::MAX_LEASE_ROUNDS) - 1;
        assert!(lease_end < 100 + AWAKE_ROUNDS, "the awake window is longer than any lease");
        for round in 100..=100 + AWAKE_ROUNDS {
            assert_eq!(t.decide(round, false, false, 50), Pass::Recent, "round {round}");
        }
        assert_eq!(t.decide(100 + AWAKE_ROUNDS + 1, false, false, 50), Pass::Skip);
        // Activity in a later round moves the window; an older note never moves it back.
        t.note_active(103);
        t.note_active(101);
        assert_eq!(t.last_active_round(), Some(103));
        assert_eq!(t.decide(107, false, false, 50), Pass::Recent);
        assert_eq!(t.decide(108, false, false, 50), Pass::Skip);
        // Once idle, the periodic read counts from the last full read.
        t.note_full_read(107);
        assert_eq!(t.last_full_read_round(), Some(107));
        assert_eq!(t.decide(156, false, false, 50), Pass::Skip);
        assert_eq!(t.decide(157, false, false, 50), Pass::Periodic);
    }

    #[test]
    fn a_heartbeat_that_came_and_went_between_two_looks_still_marks_the_round() {
        let mut t = IdleTracker::default();
        t.note_full_read(100);
        t.note_store(100, 0, false);
        assert_eq!(t.last_active_round(), None);
        assert_eq!(t.decide(101, false, false, 10), Pass::Skip);
        // Accepted and already applied by a Stack check-in: the store is empty again.
        t.note_store(101, 1, false);
        assert_eq!(t.last_active_round(), Some(101));
        assert_eq!(t.decide(101, false, false, 10), Pass::Recent);
        // Nothing new: the mark does not move.
        t.note_store(104, 1, false);
        assert_eq!(t.last_active_round(), Some(101));
        // A heartbeat that is still held marks every round it is seen in.
        t.note_store(104, 2, true);
        t.note_store(105, 2, true);
        assert_eq!(t.last_active_round(), Some(105));
    }

    #[test]
    fn a_round_id_that_went_backwards_is_read() {
        let mut t = IdleTracker::default();
        t.note_full_read(100);
        assert_eq!(t.decide(40, false, false, 10), Pass::Periodic);
        t.note_active(100);
        assert_eq!(t.decide(40, false, false, 10), Pass::Recent);
        assert_eq!(IdleTracker { last_active_round: Some(u64::MAX), ..t }.decide(u64::MAX, false, false, 10), Pass::Recent, "no overflow");
    }

    #[test]
    fn labels_are_unique_and_only_a_skip_reads_nothing() {
        let labels: std::collections::HashSet<_> = Pass::ALL.iter().map(|p| p.label()).collect();
        assert_eq!(labels.len(), Pass::ALL.len());
        assert_eq!(Pass::ALL.iter().filter(|p| !p.reads()).count(), 1);
        assert!(!Pass::Skip.reads());
    }
}
