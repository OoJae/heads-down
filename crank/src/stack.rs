//! Stack check-ins (INTERFACE v1.2 §11.5): which seats to check in this round, in which mode,
//! and when to send. Pure functions of chain state, the held heartbeats and the crank's own
//! ledger: no I/O, fully unit-tested, and run against the real program in the fork suite.
//!
//! **Stack is fail-closed.** A seat counts ORE round `r` only if a `stack_checkin` lands
//! *during* round `r` and finds a heartbeat for `r` applied in `r` (the rig's one-round lease
//! is `[r, r]`). A round nobody checks in for is a gap, even if the phone was face-down the
//! whole time; a seat that misses `end_round`, or more than the table's grace, forfeits its
//! bond. So the crank treats a seated rig's heartbeat as the most urgent thing it holds:
//!
//! * As soon as a seat's heartbeat for the live round is held, it goes out in a
//!   **verify-mode** check-in (`[Secp256r1SigVerify, stack_checkin]`): the check-in applies the
//!   heartbeat exactly as `record_heartbeats` does, and counts the round, in one instruction.
//!   The rig's later `dig` in the same round then reuses that lease (`hb_ix = 0xFF`), so the
//!   heartbeat is paid for once.
//! * When the rig's lease already is `[r, r]` (a `dig`, a `record_heartbeats` or a check-in at
//!   another table applied this round's heartbeat, whoever sent it), the seat is counted in
//!   **observe mode** (`hb_ix = 0xFF`, no signature).
//! * While a seat is waiting, its rig is **owned** by the Stack loop for the round: the dig
//!   and record passes leave its fresh heartbeat alone, so two transactions never race for one
//!   counter (the loser would be `StaleHeartbeat`, and for a check-in that is a lost round).
//!
//! [`plan_seat`] is the program's per-seat rule (§11.5 rows 1 to 9) evaluated off-chain, so
//! the crank only pays for check-ins that can count, and can say why a round was missed.

use std::collections::{HashMap, HashSet};
use std::time::Duration;

use solana_address::Address;

use crate::hd::{self, Rig, RigState};
use crate::heartbeat::VerifiedHeartbeat;
use crate::skr::{StackSeat, StackTable};
use crate::tx::CheckinSeat;

/// Why a seat cannot count this round whatever the crank sends. [`SeatSkip::label`] is the
/// metric label; [`SeatSkip::result`] is the `StackCheckin.result` the program would report.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum SeatSkip {
    /// The seat already has an outcome (the table was settled).
    Settled,
    /// The rig account is no longer a Rig (its wallet closed it). Row 1.
    RigClosed,
    /// A check-in already marked the seat broken. Row 2.
    SeatBroken,
    /// The seat is bound to another shift than the one the rig is in. Row 3.
    ShiftMismatch,
    /// The rig has no open shift. Row 4.
    ShiftNotOpen,
    /// The seat is unbound and the rig's shift already recorded a BREAK / FREEZE (or is not
    /// Armed / Down): a seat never binds to it. The player ends that shift and arms a fresh
    /// one. Row 5a.
    ShiftAlreadyBroken,
    /// The rig's plan allows leases of more than one round. Row 6.
    LeaseTooLong,
}

impl SeatSkip {
    /// Stable snake_case label.
    pub fn label(self) -> &'static str {
        match self {
            SeatSkip::Settled => "settled",
            SeatSkip::RigClosed => "rig_closed",
            SeatSkip::SeatBroken => "seat_broken",
            SeatSkip::ShiftMismatch => "shift_mismatch",
            SeatSkip::ShiftNotOpen => "shift_not_open",
            SeatSkip::ShiftAlreadyBroken => "shift_already_broken",
            SeatSkip::LeaseTooLong => "lease_too_long",
        }
    }

    /// The `StackCheckin.result` a check-in for this seat would carry (`None` for a settled
    /// table: the whole transaction fails with `InvalidStackState`).
    pub fn result(self) -> Option<u32> {
        match self {
            SeatSkip::Settled => None,
            SeatSkip::RigClosed | SeatSkip::ShiftNotOpen => Some(hd::code::RIG_NOT_ARMED),
            SeatSkip::SeatBroken => Some(hd::code::STACK_SEAT_BROKEN),
            SeatSkip::ShiftMismatch => Some(hd::code::STACK_SHIFT_MISMATCH),
            SeatSkip::ShiftAlreadyBroken => Some(hd::code::INVALID_RIG_STATE),
            SeatSkip::LeaseTooLong => Some(hd::code::STACK_LEASE_TOO_LONG),
        }
    }

    /// Every variant.
    pub const ALL: [SeatSkip; 7] = [
        SeatSkip::Settled,
        SeatSkip::RigClosed,
        SeatSkip::SeatBroken,
        SeatSkip::ShiftMismatch,
        SeatSkip::ShiftNotOpen,
        SeatSkip::ShiftAlreadyBroken,
        SeatSkip::LeaseTooLong,
    ];
}

/// Why a seat that can still count this round has nothing to send yet.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum Wait {
    /// No heartbeat for this round is held (the phone has not sent one, or it is not usable:
    /// another round, another shift, a stale counter, another key).
    NoHeartbeat,
    /// A transaction that carries this rig's heartbeat (a dig, a record, or a check-in at
    /// another table) is still in flight: its lease will be observed once it lands.
    InFlight,
}

/// What to do for one seat this round.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum SeatPlan {
    /// The seat already counted this round (`last_round == round`).
    Done,
    /// The rig's lease is `[round, round]`: count it without a signature (result 0).
    Observe,
    /// Verify and apply this heartbeat in the check-in, and count the round (result 0).
    Verify(VerifiedHeartbeat),
    /// The seat is bound and its shift shows a BREAK / FREEZE: a check-in records
    /// `broken = 1` for good (result 42, row 5b). `decisive` when the seat would otherwise
    /// finish (it already counted `end_round` within grace): only then is it worth a
    /// transaction of its own; otherwise it rides along with another seat's check-in.
    MarkBroken {
        /// The mark changes the settle outcome.
        decisive: bool,
    },
    /// Nothing to send yet.
    Wait(Wait),
    /// Cannot count this round.
    Skip(SeatSkip),
}

impl SeatPlan {
    /// The `StackCheckin.result` the program should report if this plan is sent (`None`: nothing
    /// is sent). The fork suite checks these against the real program.
    pub fn expected_result(&self) -> Option<u32> {
        match self {
            SeatPlan::Observe | SeatPlan::Verify(_) => Some(0),
            SeatPlan::MarkBroken { .. } => Some(hd::code::STACK_SEAT_BROKEN),
            SeatPlan::Done | SeatPlan::Wait(_) | SeatPlan::Skip(_) => None,
        }
    }

    /// The rig's fresh heartbeat belongs to the Stack loop this round: the dig and record
    /// passes must not put it in a transaction of their own.
    pub fn owns_heartbeat(&self) -> bool {
        matches!(self, SeatPlan::Done | SeatPlan::Observe | SeatPlan::Verify(_) | SeatPlan::Wait(_))
    }

    /// A stable label for logs and the missed-round accounting.
    pub fn label(&self) -> &'static str {
        match self {
            SeatPlan::Done => "done",
            SeatPlan::Observe => "observe",
            SeatPlan::Verify(_) => "verify",
            SeatPlan::MarkBroken { .. } => "mark_broken",
            SeatPlan::Wait(Wait::NoHeartbeat) => "no_heartbeat",
            SeatPlan::Wait(Wait::InFlight) => "heartbeat_in_flight",
            SeatPlan::Skip(s) => s.label(),
        }
    }
}

/// A heartbeat a check-in in `round` would verify and count: signed for exactly this round
/// (with one-round leases, any other round leaves a lease that is not `[round, round]`), for
/// the rig's current shift, above its counter, by its current key.
pub fn usable_for_checkin(hb: &VerifiedHeartbeat, rig: &Rig, round: u64) -> bool {
    hb.fields.round_id == round
        && hb.fields.shift_id == rig.shift_id
        && hb.fields.counter > rig.hb_counter
        && hb.pubkey == rig.p256_pubkey
        && hb.fields.lease_rounds > 0
}

/// Would a check-in now mark the seat broken (row 5b)? `Some(decisive)` when the seat is bound
/// to the rig's open shift and that shift shows a BREAK / FREEZE (`break_reason != 0`, or a
/// state other than Armed / Down); `decisive` when the seat would otherwise finish. `None`
/// when an earlier row applies (closed rig, already broken, unbound, another shift, no open
/// shift) or the shift is clean.
pub fn plan_break(table: &StackTable, seat: &StackSeat, rig: Option<&Rig>) -> Option<bool> {
    let rig = rig?;
    if seat.broken || seat.shift_id == 0 || rig.shift_id != seat.shift_id || !rig.shift_open {
        return None;
    }
    let dirty = rig.break_reason != 0 || !matches!(rig.state, RigState::Armed | RigState::Down);
    dirty.then(|| seat.finishes(table))
}

/// The program's per-seat rule (INTERFACE §11.5), in its order, for the live round `round`.
/// `rig` is `None` when the account at `seat.rig` is not a Rig; `in_flight` is true while
/// another transaction of this crank carries the rig's heartbeat.
pub fn plan_seat(
    round: u64,
    table: &StackTable,
    seat: &StackSeat,
    rig: Option<&Rig>,
    hb: Option<&VerifiedHeartbeat>,
    in_flight: bool,
) -> SeatPlan {
    if seat.outcome != crate::skr::outcome::PENDING {
        return SeatPlan::Skip(SeatSkip::Settled);
    }
    if seat.last_round == round {
        // The round is counted. A BREAK that landed after that count still breaks the seat,
        // but only if another check-in sees it (INTERFACE §11.5, "Limits").
        return match plan_break(table, seat, rig) {
            Some(decisive) => SeatPlan::MarkBroken { decisive },
            None => SeatPlan::Done,
        };
    }
    // Row 1.
    let Some(rig) = rig else {
        return SeatPlan::Skip(SeatSkip::RigClosed);
    };
    // Row 2.
    if seat.broken {
        return SeatPlan::Skip(SeatSkip::SeatBroken);
    }
    // Row 3.
    let bound = seat.shift_id != 0;
    if bound && rig.shift_id != seat.shift_id {
        return SeatPlan::Skip(SeatSkip::ShiftMismatch);
    }
    // Row 4.
    if !rig.shift_open {
        return SeatPlan::Skip(SeatSkip::ShiftNotOpen);
    }
    // Row 5.
    if rig.break_reason != 0 || !matches!(rig.state, RigState::Armed | RigState::Down) {
        return if bound {
            SeatPlan::MarkBroken { decisive: seat.finishes(table) }
        } else {
            SeatPlan::Skip(SeatSkip::ShiftAlreadyBroken)
        };
    }
    // Row 6.
    if rig.plan_lease_rounds != 1 {
        return SeatPlan::Skip(SeatSkip::LeaseTooLong);
    }
    // Rows 8 and 9: a heartbeat for this round was already applied in this round.
    if rig.lease_from_round == round && rig.lease_to_round == round {
        return SeatPlan::Observe;
    }
    if in_flight {
        return SeatPlan::Wait(Wait::InFlight);
    }
    // Row 7: verify one here.
    match hb.filter(|h| usable_for_checkin(h, rig, round)) {
        Some(h) => SeatPlan::Verify(*h),
        None => SeatPlan::Wait(Wait::NoHeartbeat),
    }
}

/// One seat as read from the chain this pass.
#[derive(Clone, Debug)]
pub struct SeatState {
    /// StackSeat PDA.
    pub address: Address,
    /// Decoded seat.
    pub seat: StackSeat,
    /// The Rig at `seat.rig`, if it still is one.
    pub rig: Option<Rig>,
}

/// One table's plan for the live round.
#[derive(Clone, Debug, Default)]
pub struct TablePlan {
    /// `(seat address, rig address, plan)` in seat-index order.
    pub seats: Vec<(Address, Address, SeatPlan)>,
}

impl TablePlan {
    /// Seats whose check-in would count now (verify or observe).
    pub fn countable(&self) -> usize {
        self.seats.iter().filter(|(_, _, p)| matches!(p, SeatPlan::Observe | SeatPlan::Verify(_))).count()
    }

    /// Seats still waiting for their phone's heartbeat (or for a transaction in flight).
    pub fn waiting(&self) -> usize {
        self.seats.iter().filter(|(_, _, p)| matches!(p, SeatPlan::Wait(_))).count()
    }

    /// A seat whose break must be recorded for the settle to be right.
    pub fn has_decisive_mark(&self) -> bool {
        self.seats.iter().any(|(_, _, p)| matches!(p, SeatPlan::MarkBroken { decisive: true }))
    }

    /// Something worth a transaction of its own.
    pub fn has_work(&self) -> bool {
        self.countable() > 0 || self.has_decisive_mark()
    }

    /// The seats to put in this round's check-in, verify-mode first (they need the bytes),
    /// then observe-mode, then the seats to mark broken (observe-mode entries that ride
    /// along). `mark_broken = false` leaves the non-decisive marks out.
    pub fn entries(&self, mark_broken: bool) -> Vec<CheckinSeat> {
        let mut verify = Vec::new();
        let mut observe = Vec::new();
        let mut marks = Vec::new();
        for (seat, rig, plan) in &self.seats {
            match plan {
                SeatPlan::Verify(h) => verify.push(CheckinSeat { seat: *seat, rig: *rig, heartbeat: Some(*h) }),
                SeatPlan::Observe => observe.push(CheckinSeat { seat: *seat, rig: *rig, heartbeat: None }),
                SeatPlan::MarkBroken { decisive } if *decisive || mark_broken => {
                    marks.push(CheckinSeat { seat: *seat, rig: *rig, heartbeat: None })
                }
                _ => {}
            }
        }
        verify.extend(observe);
        verify.extend(marks);
        verify
    }

    /// Rigs whose fresh heartbeat the Stack loop owns this round.
    pub fn owned_rigs(&self) -> impl Iterator<Item = Address> + '_ {
        self.seats.iter().filter(|(_, _, p)| p.owns_heartbeat()).map(|(_, rig, _)| *rig)
    }

    /// The plan of the seat at `address`.
    pub fn plan_of(&self, address: &Address) -> Option<&SeatPlan> {
        self.seats.iter().find(|(a, _, _)| a == address).map(|(_, _, p)| p)
    }
}

/// Plan every seat of one table. A rig's heartbeat goes into exactly one verify-mode entry
/// per pass: `used` collects the rigs already given one (a rig seated at several tables is
/// verified at the first and observed at the others on the next pass).
pub fn plan_table(
    round: u64,
    table: &StackTable,
    seats: &[SeatState],
    heartbeats: &HashMap<Address, VerifiedHeartbeat>,
    in_flight: &dyn Fn(&Address) -> bool,
    used: &mut HashSet<Address>,
) -> TablePlan {
    let mut ordered: Vec<&SeatState> = seats.iter().collect();
    ordered.sort_by_key(|s| (s.seat.seat_index, s.address.to_bytes()));
    let mut out = TablePlan::default();
    if !table.in_window(round) {
        return out;
    }
    for s in ordered {
        let rig = s.seat.rig;
        let busy = in_flight(&rig) || used.contains(&rig);
        let plan = plan_seat(round, table, &s.seat, s.rig.as_ref(), heartbeats.get(&rig), busy);
        if matches!(plan, SeatPlan::Verify(_)) {
            used.insert(rig);
        }
        out.seats.push((s.address, rig, plan));
    }
    out
}

/// When to send a table's check-in.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct SendPolicy {
    /// After a round is first seen, wait this long for every seat's heartbeat before sending a
    /// partial batch (one transaction for four seats costs a quarter of four transactions).
    pub batch_wait: Duration,
    /// Minimum gap between two partial batches of one table.
    pub straggler_wait: Duration,
    /// With this few slots left before `end_slot`, send whatever is ready at once.
    pub late_slots: u64,
}

impl Default for SendPolicy {
    fn default() -> Self {
        SendPolicy { batch_wait: Duration::from_secs(6), straggler_wait: Duration::from_secs(3), late_slots: 40 }
    }
}

impl SendPolicy {
    /// Never hold: whatever is ready goes out at once. Used by the last pass of a process
    /// that is shutting down, where a held heartbeat would be lost with the process.
    pub const NOW: SendPolicy = SendPolicy { batch_wait: Duration::ZERO, straggler_wait: Duration::ZERO, late_slots: u64::MAX };
}

/// Send now, or hold for a fuller batch? Never holds when nothing more can arrive, when the
/// round is nearly over, or when a break must be recorded.
pub fn should_send(
    plan: &TablePlan,
    round_age: Duration,
    since_last_send: Option<Duration>,
    slots_left: Option<u64>,
    policy: &SendPolicy,
) -> bool {
    if !plan.has_work() {
        return false;
    }
    if plan.waiting() == 0 || plan.has_decisive_mark() {
        return true;
    }
    if slots_left.is_some_and(|l| l <= policy.late_slots) {
        return true;
    }
    if round_age < policy.batch_wait {
        return false;
    }
    since_last_send.is_none_or(|d| d >= policy.straggler_wait)
}

/// State of one `(seat, round)` in the crank's own ledger.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum CheckinStatus {
    /// In a transaction that may still land.
    Pending {
        /// Blockhash expiry.
        last_valid_block_height: u64,
        /// Slot when it was sent.
        sent_slot: u64,
    },
    /// `StackCheckin.result == 0` seen.
    Counted,
    /// Landed with this non-zero result (re-planned from fresh state: a stale heartbeat may
    /// have been applied by a dig, in which case the next pass observes its lease).
    NotCounted(u32),
    /// The transaction failed, expired or did not simulate.
    Failed,
}

/// Retry knobs for check-ins.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct CheckinRetry {
    /// Re-plan and re-send if not confirmed after this many slots.
    pub retry_after_slots: u64,
    /// Transactions per `(seat, round)` at most.
    pub max_attempts: u32,
}

impl Default for CheckinRetry {
    fn default() -> Self {
        CheckinRetry { retry_after_slots: 8, max_attempts: 4 }
    }
}

#[derive(Clone, Copy, Debug)]
struct LedgerEntry {
    status: CheckinStatus,
    attempts: u32,
}

/// Per-`(seat, round)` attempts, so a check-in is retried inside its round but never paid for
/// without bound.
#[derive(Debug, Default)]
pub struct CheckinLedger {
    entries: HashMap<(Address, u64), LedgerEntry>,
}

impl CheckinLedger {
    /// Empty ledger.
    pub fn new() -> Self {
        Self::default()
    }

    /// Current status.
    pub fn status(&self, seat: &Address, round: u64) -> Option<CheckinStatus> {
        self.entries.get(&(*seat, round)).map(|e| e.status)
    }

    /// Transactions sent for `(seat, round)`.
    pub fn attempts(&self, seat: &Address, round: u64) -> u32 {
        self.entries.get(&(*seat, round)).map_or(0, |e| e.attempts)
    }

    /// May `seat` go into a new transaction for `round`?
    pub fn can_send(&self, seat: &Address, round: u64, block_height: u64, slot: u64, p: CheckinRetry) -> bool {
        let Some(e) = self.entries.get(&(*seat, round)) else {
            return true;
        };
        if e.attempts >= p.max_attempts {
            return false;
        }
        match e.status {
            CheckinStatus::Pending { last_valid_block_height, sent_slot } => {
                block_height > last_valid_block_height || slot >= sent_slot.saturating_add(p.retry_after_slots)
            }
            CheckinStatus::Counted => false,
            CheckinStatus::NotCounted(_) | CheckinStatus::Failed => true,
        }
    }

    /// True while a transaction for `(seat, round)` may still land.
    pub fn is_pending(&self, seat: &Address, round: u64) -> bool {
        matches!(self.status(seat, round), Some(CheckinStatus::Pending { .. }))
    }

    /// Record a submission (one more attempt for each seat).
    pub fn mark_pending(&mut self, seats: &[Address], round: u64, last_valid_block_height: u64, sent_slot: u64) {
        for s in seats {
            let e = self.entries.entry((*s, round)).or_insert(LedgerEntry { status: CheckinStatus::Failed, attempts: 0 });
            e.attempts = e.attempts.saturating_add(1);
            if e.status != CheckinStatus::Counted {
                e.status = CheckinStatus::Pending { last_valid_block_height, sent_slot };
            }
        }
    }

    /// An attempt that never left (simulation refused it): counts toward the limit.
    pub fn record_failed_attempt(&mut self, seat: &Address, round: u64) {
        let e = self.entries.entry((*seat, round)).or_insert(LedgerEntry { status: CheckinStatus::Failed, attempts: 0 });
        e.attempts = e.attempts.saturating_add(1);
        if e.status != CheckinStatus::Counted {
            e.status = CheckinStatus::Failed;
        }
    }

    /// Record an outcome. `Counted` is never overwritten (a retry may fail after the first
    /// attempt counted).
    pub fn mark(&mut self, seat: &Address, round: u64, status: CheckinStatus) {
        let e = self.entries.entry((*seat, round)).or_insert(LedgerEntry { status, attempts: 0 });
        if e.status == CheckinStatus::Counted {
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

/// Should the held heartbeat be forgotten after a check-in reported `result` for its seat?
/// Yes when the program consumed its counter (the round counted, or the lease it left was not
/// this round's) or will never accept it (stale, or a precompile / key mismatch). No when the
/// rule stopped before the heartbeat (broken seat, other shift, long lease): a dig or a record
/// may still use it.
pub fn heartbeat_consumed_by(result: u32) -> bool {
    result == 0
        || result == hd::code::STALE_HEARTBEAT
        || result == hd::code::LEASE_EXPIRED
        || result & 0xFFFF_0000 == 0x2560_0000
}

/// Why a seat that could have counted round `r` did not: the label of the
/// `hd_crank_stack_checkins_missed_total` metric and of the alert log line.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum Missed {
    /// No usable heartbeat for the round ever reached the crank (the phone's side).
    NoHeartbeat,
    /// A check-in was sent and did not land in the round (the crank's or the chain's side).
    NotLanded,
    /// A check-in landed in the round with a non-zero result.
    Refused(u32),
    /// The table's or the hourly fee budget was spent.
    Budget,
    /// The per-round attempt cap was reached.
    Attempts,
    /// Ready, but never sent (the pass did not run, or the round ended first).
    NotSent,
}

impl Missed {
    /// Stable label.
    pub fn label(self) -> &'static str {
        match self {
            Missed::NoHeartbeat => "no_heartbeat",
            Missed::NotLanded => "not_landed",
            Missed::Refused(code) => hd::error_name(code),
            Missed::Budget => "budget",
            Missed::Attempts => "attempts",
            Missed::NotSent => "not_sent",
        }
    }
}

/// What the crank noted about one seat during a round (kept until the round is accounted for).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct RoundNote {
    /// The label of the last plan for the seat ([`SeatPlan::label`]).
    pub last_plan: &'static str,
    /// The seat could count this round (its last plan was not a skip).
    pub eligible: bool,
    /// The seat was ready (verify or observe) at some point.
    pub was_ready: bool,
    /// A fee budget stopped a check-in for it.
    pub budget_blocked: bool,
}

/// Classify a missed round from the notes and the ledger (`None`: the seat could not count
/// this round anyway, so nothing was missed).
pub fn missed_reason(note: Option<&RoundNote>, status: Option<CheckinStatus>, attempts: u32, retry: CheckinRetry) -> Option<Missed> {
    let note = note?;
    if !note.eligible {
        return None;
    }
    Some(match status {
        Some(CheckinStatus::NotCounted(code)) => Missed::Refused(code),
        Some(CheckinStatus::Pending { .. }) | Some(CheckinStatus::Failed) if attempts >= retry.max_attempts => Missed::Attempts,
        Some(CheckinStatus::Pending { .. }) | Some(CheckinStatus::Failed) => Missed::NotLanded,
        Some(CheckinStatus::Counted) => return None,
        None if note.budget_blocked => Missed::Budget,
        None if note.was_ready => Missed::NotSent,
        None => Missed::NoHeartbeat,
    })
}

/// After round `round` of the window, can the seat still finish? It cannot once it is broken,
/// once it missed `end_round`, or once its gaps so far exceed the table's grace.
pub fn can_still_finish(table: &StackTable, seat: &StackSeat, round: u64) -> bool {
    if seat.broken {
        return false;
    }
    if round >= table.end_round {
        return seat.finishes(table);
    }
    let elapsed = crate::skr::window_len(table.start_round, round.max(table.start_round));
    let elapsed = if round < table.start_round { 0 } else { elapsed };
    elapsed.saturating_sub(seat.checked_rounds) <= u64::from(table.grace_gaps)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::hd::HeartbeatFields;
    use crate::skr::{outcome, status};

    const ROUND: u64 = 500;

    fn table(start: u64, end: u64, grace: u32) -> StackTable {
        StackTable {
            bump: 255,
            host: Address::new_from_array([1; 32]),
            vault: Address::new_from_array([2; 32]),
            table_id: 1,
            bond: 100,
            start_round: start,
            end_round: end,
            grace_gaps: grace,
            flags: 0,
            max_seats: 8,
            status: status::OPEN,
            seat_count: 3,
            finishers: 0,
            claimed_count: 0,
            total_bonds: 300,
            finisher_bonds: 0,
            payouts_total: 0,
            bury_amount: 0,
            claimed_total: 0,
            refund_after_ts: 0,
            opened_ts: 0,
            opened_round: start - 1,
        }
    }

    fn rig_addr(i: u8) -> Address {
        Address::new_from_array([0x40 + i; 32])
    }

    fn seat(i: u8) -> StackSeat {
        StackSeat {
            bump: 255,
            table: Address::new_from_array([9; 32]),
            rig: rig_addr(i),
            authority: Address::new_from_array([0x60 + i; 32]),
            sgt_mint: Address::default(),
            bond: 100,
            shift_id: 0,
            checked_rounds: 0,
            last_round: 0,
            payout: 0,
            seat_index: i,
            broken: false,
            outcome: outcome::PENDING,
            sgt_verified: false,
        }
    }

    fn rig() -> Rig {
        Rig {
            p256_pubkey: [2; 33],
            state: RigState::Down,
            plan_lease_rounds: 1,
            shift_id: 7,
            hb_counter: 10,
            shift_open: true,
            lease_from_round: ROUND - 1,
            lease_to_round: ROUND - 1,
            ..Rig::default()
        }
    }

    fn hb(i: u8, counter: u64, round: u64) -> VerifiedHeartbeat {
        VerifiedHeartbeat {
            rig: rig_addr(i),
            fields: HeartbeatFields { counter, shift_id: 7, round_id: round, lease_rounds: 1 },
            sig: [0; 64],
            pubkey: [2; 33],
            digest: [0; 32],
        }
    }

    #[test]
    fn the_rule_follows_the_programs_order() {
        let t = table(ROUND - 2, ROUND + 5, 1);
        let h = hb(0, 11, ROUND);
        let plan = |s: &StackSeat, r: Option<&Rig>, h: Option<&VerifiedHeartbeat>| plan_seat(ROUND, &t, s, r, h, false);
        // Row 9: a fresh heartbeat for this round is verified here.
        assert_eq!(plan(&seat(0), Some(&rig()), Some(&h)), SeatPlan::Verify(h));
        // Rows 8/9: the lease already is [round, round]: observe, with or without a heartbeat.
        let leased = Rig { lease_from_round: ROUND, lease_to_round: ROUND, ..rig() };
        assert_eq!(plan(&seat(0), Some(&leased), None), SeatPlan::Observe);
        assert_eq!(plan(&seat(0), Some(&leased), Some(&h)), SeatPlan::Observe, "never pay for a signature the chain already has");
        // Already counted.
        let counted = StackSeat { last_round: ROUND, checked_rounds: 3, shift_id: 7, ..seat(0) };
        assert_eq!(plan(&counted, Some(&leased), Some(&h)), SeatPlan::Done);
        // Row 1 before everything else.
        assert_eq!(plan(&seat(0), None, Some(&h)), SeatPlan::Skip(SeatSkip::RigClosed));
        // Row 2 before row 3.
        let broken = StackSeat { broken: true, shift_id: 3, ..seat(0) };
        assert_eq!(plan(&broken, Some(&rig()), Some(&h)), SeatPlan::Skip(SeatSkip::SeatBroken));
        // Row 3: bound to another shift (before row 4: the rig re-armed or even ended it).
        let bound_other = StackSeat { shift_id: 6, ..seat(0) };
        assert_eq!(plan(&bound_other, Some(&rig()), Some(&h)), SeatPlan::Skip(SeatSkip::ShiftMismatch));
        let ended = Rig { shift_open: false, state: RigState::Idle, ..rig() };
        assert_eq!(plan(&bound_other, Some(&ended), Some(&h)), SeatPlan::Skip(SeatSkip::ShiftMismatch));
        // Row 4.
        assert_eq!(plan(&seat(0), Some(&ended), Some(&h)), SeatPlan::Skip(SeatSkip::ShiftNotOpen));
        // Row 5a: an unbound seat never binds to a shift that already broke.
        let picked_up = Rig { state: RigState::Cooling, break_reason: 1, ..rig() };
        assert_eq!(plan(&seat(0), Some(&picked_up), Some(&h)), SeatPlan::Skip(SeatSkip::ShiftAlreadyBroken));
        // A soft break that resumed is still a break: break_reason stays set for the shift.
        let resumed = Rig { state: RigState::Down, break_reason: 1, ..rig() };
        assert_eq!(plan(&seat(0), Some(&resumed), Some(&h)), SeatPlan::Skip(SeatSkip::ShiftAlreadyBroken));
        // Row 5b: a bound seat is broken for good.
        let bound = StackSeat { shift_id: 7, checked_rounds: 2, last_round: ROUND - 1, ..seat(0) };
        assert_eq!(plan(&bound, Some(&resumed), Some(&h)), SeatPlan::MarkBroken { decisive: false });
        let frozen = Rig { state: RigState::Frozen, break_reason: 3, ..rig() };
        assert_eq!(plan(&bound, Some(&frozen), None), SeatPlan::MarkBroken { decisive: false });
        // Row 6 (after row 5: a long-lease rig that broke is still marked).
        let long = Rig { plan_lease_rounds: 3, ..rig() };
        assert_eq!(plan(&seat(0), Some(&long), Some(&h)), SeatPlan::Skip(SeatSkip::LeaseTooLong));
        // A settled seat is never checked in.
        let settled = StackSeat { outcome: outcome::FORFEITED, ..seat(0) };
        assert_eq!(plan(&settled, Some(&rig()), Some(&h)), SeatPlan::Skip(SeatSkip::Settled));
        // The expected results are the program's codes.
        assert_eq!(SeatSkip::RigClosed.result(), Some(13));
        assert_eq!(SeatSkip::SeatBroken.result(), Some(42));
        assert_eq!(SeatSkip::ShiftMismatch.result(), Some(40));
        assert_eq!(SeatSkip::ShiftNotOpen.result(), Some(13));
        assert_eq!(SeatSkip::ShiftAlreadyBroken.result(), Some(24));
        assert_eq!(SeatSkip::LeaseTooLong.result(), Some(41));
        assert_eq!(SeatSkip::Settled.result(), None);
        assert_eq!(SeatPlan::MarkBroken { decisive: true }.expected_result(), Some(42));
        assert_eq!(SeatPlan::Observe.expected_result(), Some(0));
        let labels: HashSet<_> = SeatSkip::ALL.iter().map(|s| s.label()).collect();
        assert_eq!(labels.len(), SeatSkip::ALL.len());
    }

    #[test]
    fn only_a_heartbeat_for_this_very_round_is_verified() {
        let t = table(ROUND - 2, ROUND + 5, 1);
        let r = rig();
        let plan = |h: &VerifiedHeartbeat| plan_seat(ROUND, &t, &seat(0), Some(&r), Some(h), false);
        assert!(matches!(plan(&hb(0, 11, ROUND)), SeatPlan::Verify(_)));
        // The previous round's heartbeat would leave the lease [r-1, r-1]: LeaseExpired, a gap.
        assert_eq!(plan(&hb(0, 11, ROUND - 1)), SeatPlan::Wait(Wait::NoHeartbeat));
        // One round ahead (the phone saw the reset first): not valid on-chain yet.
        assert_eq!(plan(&hb(0, 11, ROUND + 1)), SeatPlan::Wait(Wait::NoHeartbeat));
        // Stale counter, another shift, another key.
        assert_eq!(plan(&hb(0, 10, ROUND)), SeatPlan::Wait(Wait::NoHeartbeat));
        let mut other_shift = hb(0, 11, ROUND);
        other_shift.fields.shift_id = 8;
        assert_eq!(plan(&other_shift), SeatPlan::Wait(Wait::NoHeartbeat));
        let mut other_key = hb(0, 11, ROUND);
        other_key.pubkey = [3; 33];
        assert_eq!(plan(&other_key), SeatPlan::Wait(Wait::NoHeartbeat));
        // Another transaction carries the heartbeat: wait, then observe.
        assert_eq!(plan_seat(ROUND, &t, &seat(0), Some(&r), Some(&hb(0, 11, ROUND)), true), SeatPlan::Wait(Wait::InFlight));
        // An Armed rig (first heartbeat of the shift) is fine too.
        let armed = Rig { state: RigState::Armed, lease_from_round: 0, lease_to_round: 0, hb_counter: 0, ..rig() };
        assert!(matches!(plan_seat(ROUND, &t, &seat(0), Some(&armed), Some(&hb(0, 1, ROUND)), false), SeatPlan::Verify(_)));
    }

    #[test]
    fn a_break_after_the_last_checkin_of_end_round_is_decisive() {
        // Window [498, 500], grace 0: the seat counted all three rounds, then its phone was
        // picked up in end_round. Unless a check-in records it, the seat finishes.
        let t = table(ROUND - 2, ROUND, 0);
        let s = StackSeat { shift_id: 7, checked_rounds: 3, last_round: ROUND, ..seat(0) };
        assert!(s.finishes(&t));
        let picked_up = Rig { state: RigState::Cooling, break_reason: 1, lease_from_round: ROUND, lease_to_round: ROUND, ..rig() };
        // `Done` must not hide it: the seat counted this round, and a BREAK followed.
        assert_eq!(plan_seat(ROUND, &t, &s, Some(&picked_up), None, false), SeatPlan::MarkBroken { decisive: true });
        assert_eq!(plan_seat(ROUND, &t, &s, Some(&Rig { lease_from_round: ROUND, lease_to_round: ROUND, ..rig() }), None, false), SeatPlan::Done);
        assert_eq!(plan_break(&t, &s, Some(&picked_up)), Some(true));
        let not_last = StackSeat { last_round: ROUND - 1, checked_rounds: 2, ..s };
        assert_eq!(plan_break(&t, &not_last, Some(&picked_up)), Some(false));
        assert_eq!(plan_break(&t, &s, Some(&rig())), None, "a clean shift");
        assert_eq!(plan_break(&t, &StackSeat { broken: true, ..s }, Some(&picked_up)), None, "already marked");
        assert_eq!(plan_break(&t, &StackSeat { shift_id: 0, ..s }, Some(&picked_up)), None, "unbound");
        assert_eq!(plan_break(&t, &s, None), None, "a closed rig cannot be marked (row 1)");
        let rearmed = Rig { shift_id: 8, ..picked_up.clone() };
        assert_eq!(plan_break(&t, &s, Some(&rearmed)), None, "another shift: row 3 comes first");
        let ended = Rig { shift_open: false, ..picked_up };
        assert_eq!(plan_break(&t, &s, Some(&ended)), None, "the shift was ended: row 4 comes first");
    }

    fn states(seats: Vec<(StackSeat, Option<Rig>)>) -> Vec<SeatState> {
        seats
            .into_iter()
            .enumerate()
            .map(|(i, (seat, rig))| SeatState { address: Address::new_from_array([0x80 + i as u8; 32]), seat, rig })
            .collect()
    }

    #[test]
    fn a_table_plan_orders_entries_and_owns_the_waiting_rigs() {
        let t = table(ROUND, ROUND + 9, 2);
        let leased = Rig { lease_from_round: ROUND, lease_to_round: ROUND, ..rig() };
        let broke = Rig { state: RigState::Cooling, break_reason: 2, ..rig() };
        let seats = states(vec![
            (seat(0), Some(leased)),                                     // observe
            (seat(1), Some(rig())),                                      // verify
            (seat(2), Some(rig())),                                      // waiting for its phone
            (StackSeat { shift_id: 7, ..seat(3) }, Some(broke)),         // mark broken (rides along)
            (seat(4), Some(Rig { plan_lease_rounds: 2, ..rig() })),      // cannot count
        ]);
        let hbs: HashMap<Address, VerifiedHeartbeat> = [(rig_addr(1), hb(1, 11, ROUND)), (rig_addr(4), hb(4, 11, ROUND))].into();
        let mut used = HashSet::new();
        let plan = plan_table(ROUND, &t, &seats, &hbs, &|_| false, &mut used);
        assert_eq!(plan.seats.iter().map(|(_, _, p)| p.label()).collect::<Vec<_>>(), vec![
            "observe",
            "verify",
            "no_heartbeat",
            "mark_broken",
            "lease_too_long"
        ]);
        assert_eq!((plan.countable(), plan.waiting()), (2, 1));
        assert!(plan.has_work() && !plan.has_decisive_mark());
        // Verify first, then observe, then the mark.
        let entries = plan.entries(true);
        assert_eq!(entries.iter().map(|e| (e.rig, e.heartbeat.is_some())).collect::<Vec<_>>(), vec![
            (rig_addr(1), true),
            (rig_addr(0), false),
            (rig_addr(3), false)
        ]);
        assert_eq!(plan.entries(false).len(), 2, "a non-decisive mark is optional");
        // The dig / record passes must leave the heartbeats of seats 0..=2 alone; 3 and 4 are theirs.
        let owned: HashSet<Address> = plan.owned_rigs().collect();
        assert_eq!(owned, [rig_addr(0), rig_addr(1), rig_addr(2)].into());
        assert_eq!(used, [rig_addr(1)].into());
        assert_eq!(plan.plan_of(&seats[4].address), Some(&SeatPlan::Skip(SeatSkip::LeaseTooLong)));
        // Outside the window nothing is planned (the transaction would fail: InvalidStackState).
        assert!(plan_table(ROUND - 1, &t, &seats, &hbs, &|_| false, &mut HashSet::new()).seats.is_empty());
        assert!(plan_table(ROUND + 10, &t, &seats, &hbs, &|_| false, &mut HashSet::new()).seats.is_empty());
        let settled = StackTable { status: status::SETTLED, ..t };
        assert!(plan_table(ROUND, &settled, &seats, &hbs, &|_| false, &mut HashSet::new()).seats.is_empty());
    }

    #[test]
    fn one_heartbeat_is_verified_once_per_pass() {
        // The same rig sits at two tables: verified at the first, observed at the second on
        // the next pass (one heartbeat counts at every table the rig sits at).
        let t = table(ROUND, ROUND + 3, 0);
        let a = states(vec![(seat(0), Some(rig()))]);
        let b = states(vec![(StackSeat { table: Address::new_from_array([8; 32]), ..seat(0) }, Some(rig()))]);
        let hbs: HashMap<Address, VerifiedHeartbeat> = [(rig_addr(0), hb(0, 11, ROUND))].into();
        let mut used = HashSet::new();
        let pa = plan_table(ROUND, &t, &a, &hbs, &|_| false, &mut used);
        let pb = plan_table(ROUND, &t, &b, &hbs, &|_| false, &mut used);
        assert!(matches!(pa.seats[0].2, SeatPlan::Verify(_)));
        assert_eq!(pb.seats[0].2, SeatPlan::Wait(Wait::InFlight));
        // A dig in flight for the rig holds the check-in back as well.
        let busy = plan_table(ROUND, &t, &a, &hbs, &|r| *r == rig_addr(0), &mut HashSet::new());
        assert_eq!(busy.seats[0].2, SeatPlan::Wait(Wait::InFlight));
        assert!(busy.seats[0].2.owns_heartbeat());
    }

    #[test]
    fn sending_waits_for_a_full_batch_but_never_too_long() {
        let p = SendPolicy::default();
        let t = table(ROUND, ROUND + 9, 0);
        let hbs: HashMap<Address, VerifiedHeartbeat> = [(rig_addr(0), hb(0, 11, ROUND))].into();
        let two = states(vec![(seat(0), Some(rig())), (seat(1), Some(rig()))]);
        let partial = plan_table(ROUND, &t, &two, &hbs, &|_| false, &mut HashSet::new());
        assert_eq!((partial.countable(), partial.waiting()), (1, 1));
        let secs = Duration::from_secs;
        // Early in the round: hold for the second phone.
        assert!(!should_send(&partial, secs(2), None, Some(200), &p));
        // After the batch wait: send what is ready.
        assert!(should_send(&partial, secs(6), None, Some(200), &p));
        // A straggler batch right after another one waits a little.
        assert!(!should_send(&partial, secs(8), Some(secs(1)), Some(200), &p));
        assert!(should_send(&partial, secs(8), Some(secs(3)), Some(200), &p));
        // Late in the round: never hold.
        assert!(should_send(&partial, secs(1), Some(secs(0)), Some(40), &p));
        assert!(should_send(&partial, secs(1), None, Some(0), &p), "during the intermission the round is still live");
        // The round has not started (no deploy yet): there is no deadline to be late for.
        assert!(!should_send(&partial, secs(1), None, None, &p));
        // Everyone ready: send at once.
        let both: HashMap<Address, VerifiedHeartbeat> = [(rig_addr(0), hb(0, 11, ROUND)), (rig_addr(1), hb(1, 11, ROUND))].into();
        let full = plan_table(ROUND, &t, &two, &both, &|_| false, &mut HashSet::new());
        assert!(should_send(&full, secs(0), None, Some(200), &p));
        // Nothing ready: nothing to send, however late.
        let none = plan_table(ROUND, &t, &two, &HashMap::new(), &|_| false, &mut HashSet::new());
        assert!(!should_send(&none, secs(60), None, Some(0), &p));
        // A process that is shutting down never holds a ready seat (the heartbeat would be
        // lost with it), whatever the round's age, and still sends nothing when nothing is ready.
        assert!(should_send(&partial, secs(0), Some(secs(0)), Some(200), &SendPolicy::NOW));
        assert!(should_send(&partial, secs(0), Some(secs(0)), None, &SendPolicy::NOW));
        assert!(!should_send(&none, secs(0), None, Some(200), &SendPolicy::NOW));
        // A decisive mark goes out at once, even with a seat still waiting.
        let end = table(ROUND - 1, ROUND, 0);
        let done_then_broke = StackSeat { shift_id: 7, checked_rounds: 2, last_round: ROUND, ..seat(0) };
        let mut plan = plan_table(ROUND, &end, &states(vec![(seat(1), Some(rig()))]), &HashMap::new(), &|_| false, &mut HashSet::new());
        plan.seats.push((Address::new_from_array([0x90; 32]), done_then_broke.rig, SeatPlan::MarkBroken { decisive: true }));
        assert!(should_send(&plan, secs(0), None, Some(200), &p));
        assert_eq!(plan.entries(false).len(), 1);
    }

    #[test]
    fn the_ledger_retries_inside_the_round_with_a_cap() {
        let p = CheckinRetry { retry_after_slots: 8, max_attempts: 3 };
        let mut l = CheckinLedger::new();
        let (a, b) = (Address::new_from_array([1; 32]), Address::new_from_array([2; 32]));
        assert!(l.can_send(&a, ROUND, 0, 100, p));
        l.mark_pending(&[a, b], ROUND, 1_000, 100);
        assert!(l.is_pending(&a, ROUND));
        assert!(!l.can_send(&a, ROUND, 500, 107, p), "in flight, not stale yet");
        assert!(l.can_send(&a, ROUND, 500, 108, p), "unconfirmed for 8 slots");
        assert!(l.can_send(&a, ROUND, 1_001, 101, p), "blockhash expired");
        assert!(l.can_send(&a, ROUND + 1, 0, 0, p), "another round");
        // A non-zero result is re-planned (the next pass may observe a dig's lease).
        l.mark(&a, ROUND, CheckinStatus::NotCounted(7));
        assert!(l.can_send(&a, ROUND, 0, 0, p));
        l.mark_pending(&[a], ROUND, 2_000, 110);
        l.mark(&a, ROUND, CheckinStatus::Counted);
        assert!(!l.can_send(&a, ROUND, 9_999, 999, p), "counted: done");
        l.mark(&a, ROUND, CheckinStatus::Failed);
        assert_eq!(l.status(&a, ROUND), Some(CheckinStatus::Counted), "a failed retry does not undo a count");
        // The cap.
        l.mark(&b, ROUND, CheckinStatus::Failed);
        l.mark_pending(&[b], ROUND, 2_000, 110);
        l.record_failed_attempt(&b, ROUND);
        assert_eq!(l.attempts(&b, ROUND), 3);
        assert!(!l.can_send(&b, ROUND, 9_999, 999, p), "attempts exhausted");
        l.prune(ROUND + 1);
        assert!(l.is_empty());
    }

    #[test]
    fn consumed_heartbeats_are_forgotten_and_untouched_ones_kept() {
        for consumed in [0u32, 7, 8, 0x2560_000d, 0x2560_000e] {
            assert!(heartbeat_consumed_by(consumed), "{consumed:#x}");
        }
        for kept in [6u32, 13, 24, 40, 41, 42] {
            assert!(!heartbeat_consumed_by(kept), "{kept}");
        }
    }

    #[test]
    fn missed_rounds_are_classified() {
        let retry = CheckinRetry { retry_after_slots: 8, max_attempts: 3 };
        let eligible = |was_ready, budget_blocked| RoundNote { last_plan: "verify", eligible: true, was_ready, budget_blocked };
        assert_eq!(missed_reason(Some(&eligible(false, false)), None, 0, retry), Some(Missed::NoHeartbeat));
        assert_eq!(missed_reason(Some(&eligible(true, false)), None, 0, retry), Some(Missed::NotSent));
        assert_eq!(missed_reason(Some(&eligible(true, true)), None, 0, retry), Some(Missed::Budget));
        let pending = CheckinStatus::Pending { last_valid_block_height: 1, sent_slot: 1 };
        assert_eq!(missed_reason(Some(&eligible(true, false)), Some(pending), 1, retry), Some(Missed::NotLanded));
        assert_eq!(missed_reason(Some(&eligible(true, false)), Some(CheckinStatus::Failed), 3, retry), Some(Missed::Attempts));
        assert_eq!(missed_reason(Some(&eligible(true, false)), Some(CheckinStatus::NotCounted(8)), 1, retry), Some(Missed::Refused(8)));
        assert_eq!(Missed::Refused(8).label(), "LeaseExpired");
        assert_eq!(missed_reason(Some(&eligible(true, false)), Some(CheckinStatus::Counted), 1, retry), None);
        // A seat that could not count anyway missed nothing, and neither did an unknown seat.
        let skip = RoundNote { last_plan: "seat_broken", eligible: false, was_ready: false, budget_blocked: false };
        assert_eq!(missed_reason(Some(&skip), None, 0, retry), None);
        assert_eq!(missed_reason(None, None, 0, retry), None);
        let labels: HashSet<_> = [Missed::NoHeartbeat, Missed::NotLanded, Missed::Budget, Missed::Attempts, Missed::NotSent].iter().map(|m| m.label()).collect();
        assert_eq!(labels.len(), 5);
    }

    #[test]
    fn a_seat_is_lost_once_its_gaps_exceed_grace_or_it_misses_the_end() {
        // Window [500, 509], grace 2.
        let t = table(ROUND, ROUND + 9, 2);
        let s = |checked, last| StackSeat { shift_id: 7, checked_rounds: checked, last_round: last, ..seat(0) };
        // After round 502 (3 rounds elapsed): 1 counted = 2 gaps: still inside grace.
        assert!(can_still_finish(&t, &s(1, ROUND), ROUND + 2));
        // After round 503: 3 gaps: lost.
        assert!(!can_still_finish(&t, &s(1, ROUND), ROUND + 3));
        // After end_round: only the program's finish rule counts.
        assert!(can_still_finish(&t, &s(8, ROUND + 9), ROUND + 9));
        assert!(!can_still_finish(&t, &s(9, ROUND + 8), ROUND + 9), "missed end_round");
        assert!(!can_still_finish(&t, &StackSeat { broken: true, ..s(10, ROUND + 9) }, ROUND + 9));
        // Before the window starts nothing is lost.
        assert!(can_still_finish(&t, &seat(0), ROUND - 1));
    }
}
