//! The Stack duty (INTERFACE v1.2 §11): discover open tables and their seats, check every
//! seat in every round of its table's window, account for rounds that were missed, and settle
//! tables whose window is over.
//!
//! ```text
//! discovery   getProgramAccounts(StackTable: tag 5, status Open) + (StackSeat: tag 6, outcome
//!             pending), cached; refreshed every `stack.discover_secs`, when a table's window
//!             opens, and when a StackOpened / StackJoined / StackSettled / StackClaimed event
//!             arrives on the program's log stream
//! every pass  getMultipleAccounts(tables, seats, rigs) → stack::plan_table → should_send →
//!             pack → [simulate] → send → ledger (seat, round) pending → confirm task →
//!             StackCheckin results → ledger / metrics / heartbeat store
//! new round   the previous round's seats that did not count it → hd_crank_stack_checkins_missed_total
//!             and an alert line; tables past end_round → settle_stack
//! ```
//!
//! The pass runs in the crank's main loop **before** the dig and record passes, and tells
//! them which rigs' fresh heartbeats it owns for the round ([`Crank::stack_owns`]).
//!
//! Who pays: the crank. `stack_checkin` has no payer account and a StackTable carries no
//! crank tip, so nothing on-chain reimburses a check-in or a settle. Fees are capped per
//! table (`stack.max_lamports_per_table`) and per hour (`stack.max_lamports_per_hour`).

use std::collections::{HashMap, HashSet};
use std::sync::atomic::Ordering;
use std::sync::Arc;
use std::time::{Duration, Instant};

use solana_address::Address;
use solana_signer::Signer;

use super::{lock, Crank};
use crate::chain::{ChainView, ProgramEvents};
use crate::hd::{self, HdEvent, Rig};
use crate::heartbeat::VerifiedHeartbeat;
use crate::rpc;
use crate::sender::{ConfirmPolicy, Outcome};
use crate::skr::{self, StackSeat, StackTable};
use crate::stack::{self, CheckinLedger, CheckinStatus, RoundNote, SeatPlan, SeatState, TablePlan};
use crate::tx::{self, CheckinBatch, TxFormat};

/// Compute limit of the one-time Bury vault setup (the ATA program's bump search varies).
pub const BURY_INIT_CU_LIMIT: u32 = 80_000;
/// While every seat of every active table has counted the live round, re-read this often
/// (a BREAK may still have to be recorded).
pub const IDLE_PASS_INTERVAL: Duration = Duration::from_secs(10);
/// File in `state_dir` that remembers what each open table has cost so far.
pub const SPEND_FILE: &str = "stack_spend.json";
/// A read that does not match the cache asks for a rediscovery, but not while the cache is
/// younger than this: a mismatch that a refresh does not fix must not turn every pass into
/// two `getProgramAccounts` calls.
pub const SOFT_REFRESH: Duration = Duration::from_secs(5);
/// Two discoveries are at least this far apart, whatever asks for them (a burst of
/// StackOpened / StackJoined events is one refresh, not one per event).
pub const MIN_DISCOVERY_GAP: Duration = Duration::from_secs(2);

/// One open table as the crank caches it.
#[derive(Clone, Debug)]
pub(crate) struct TableEntry {
    pub table: StackTable,
    /// `(seat, rig)` of every seat without an outcome, in seat-index order.
    pub seats: Vec<(Address, Address)>,
}

/// The Stack loop's state (behind a mutex that is never held across an await).
#[derive(Default)]
pub(crate) struct StackRuntime {
    /// Open tables.
    pub tables: HashMap<Address, TableEntry>,
    pub last_discovery: Option<Instant>,
    /// An event (or a seat count that does not add up) asked for a refresh.
    pub dirty: bool,
    /// The live round and when the crank first saw it.
    pub round: u64,
    pub round_seen: Option<Instant>,
    /// What was noted about each seat during `round`.
    pub notes: HashMap<Address, RoundNote>,
    /// The notes of the round before, until its misses are accounted for.
    pub prev_notes: Option<(u64, HashMap<Address, RoundNote>)>,
    /// Check-ins that count a round, per `(seat, round)`.
    pub ledger: CheckinLedger,
    /// Check-ins that record a break (result 42), per `(seat, round)`. Kept apart from
    /// `ledger`: a seat that already counted the round must still be marked when its shift
    /// breaks afterwards, and that mark has its own retries.
    pub marks: CheckinLedger,
    /// Rigs whose heartbeat is in an unconfirmed verify-mode check-in: `(counter, sent slot)`.
    pub hb_in_flight: HashMap<Address, (u64, u64)>,
    /// Lamports spent per table (estimated at send).
    pub spend: HashMap<Address, u64>,
    pub budget_alerted: HashSet<Address>,
    pub last_send: HashMap<Address, Instant>,
    /// Rigs whose fresh heartbeat the Stack loop owns in `owned_round`.
    pub owned: HashSet<Address>,
    pub owned_round: u64,
    /// Settle attempts per table, and tables with a settle in flight.
    pub settle_tries: HashMap<Address, (u32, Instant)>,
    pub settling: HashSet<Address>,
    pub lost_alerted: HashSet<Address>,
    pub last_pass: Option<Instant>,
    /// The round in which the last pass left nothing that only time can change: every seat is
    /// counted, skipped, or waiting for its phone (whose heartbeat wakes the loop).
    pub idle_round: u64,
}

impl StackRuntime {
    fn table_budget_left(&self, table: &Address, cap: u64) -> u64 {
        if cap == 0 {
            return u64::MAX;
        }
        cap.saturating_sub(self.spend.get(table).copied().unwrap_or(0))
    }

    /// The ledger an entry belongs to: `marks` for a break being recorded, `ledger` for a
    /// round being counted.
    fn ledger_of(&mut self, mark: bool) -> &mut CheckinLedger {
        if mark {
            &mut self.marks
        } else {
            &mut self.ledger
        }
    }

    /// What was read does not match the cache: refresh, unless the cache is fresh already
    /// (see [`SOFT_REFRESH`]).
    pub(crate) fn soft_dirty(&mut self) {
        if self.last_discovery.is_none_or(|t| t.elapsed() >= SOFT_REFRESH) {
            self.dirty = true;
        }
    }

    /// Is a discovery due now? `dirty` waits for [`MIN_DISCOVERY_GAP`]; the periodic refresh
    /// for `every`; a new round with a table whose window opens or whose seats do not add up
    /// asks for one as well.
    pub(crate) fn discovery_due(&self, every: Duration, new_round: bool, round: u64) -> bool {
        let age = self.last_discovery.map(|t| t.elapsed());
        if age.is_none() {
            return true;
        }
        let gap_ok = age.is_none_or(|a| a >= MIN_DISCOVERY_GAP);
        if self.dirty && gap_ok {
            return true;
        }
        if age.is_none_or(|a| a >= every) {
            return true;
        }
        new_round
            && gap_ok
            && self.tables.values().any(|e| e.table.start_round == round || e.seats.len() != usize::from(e.table.seat_count))
    }
}

/// What the fresh read of one pass found.
struct Fresh {
    tables: HashMap<Address, StackTable>,
    seats: HashMap<Address, Vec<SeatState>>,
}

fn mode_label(plan: &SeatPlan) -> &'static str {
    match plan {
        SeatPlan::Verify(_) => "verify",
        SeatPlan::MarkBroken { .. } => "mark_broken",
        _ => "observe",
    }
}

impl Crank {
    /// True while the Stack loop owns `rig`'s fresh heartbeat for `round`: a check-in will
    /// (or did) apply it, so a dig or a record must not carry it too.
    pub fn stack_owns(&self, rig: &Address, round: u64) -> bool {
        let st = lock(&self.stack);
        st.owned_round == round && st.owned.contains(rig)
    }

    /// Ask for a Stack pass now (a seated rig's heartbeat arrived, a BREAK landed, an event).
    pub fn stack_nudge(&self) {
        self.nudge.wake();
    }

    /// Is a pass due? On a new round, on a nudge, or every `stack.pass_interval_ms` (every
    /// [`IDLE_PASS_INTERVAL`] once nothing is left to do this round).
    pub(crate) fn stack_due(&self, round: u64) -> bool {
        if !self.cfg.stack.enabled {
            return false;
        }
        let st = lock(&self.stack);
        let refresh_due = st.dirty && st.last_discovery.is_none_or(|t| t.elapsed() >= MIN_DISCOVERY_GAP);
        if self.nudge.pending.load(Ordering::SeqCst) || st.round != round || refresh_due {
            return true;
        }
        let every = if st.idle_round == round { IDLE_PASS_INTERVAL } else { Duration::from_millis(self.cfg.stack.pass_interval_ms) };
        st.last_pass.is_none_or(|t| t.elapsed() >= every)
    }

    fn spend_file(&self) -> std::path::PathBuf {
        self.cfg.state_dir.join(SPEND_FILE)
    }

    /// Load what each table has cost so far (so a restart does not reset the per-table cap).
    pub(crate) fn load_stack_spend(&self) {
        let Ok(text) = std::fs::read_to_string(self.spend_file()) else { return };
        let Ok(v) = serde_json::from_str::<serde_json::Value>(&text) else { return };
        let mut st = lock(&self.stack);
        for (k, lamports) in v["tables"].as_object().into_iter().flatten() {
            if let (Ok(a), Some(l)) = (k.parse::<Address>(), lamports.as_u64()) {
                st.spend.insert(a, l);
            }
        }
    }

    fn persist_stack_spend(&self) {
        let tables: serde_json::Map<String, serde_json::Value> =
            lock(&self.stack).spend.iter().map(|(k, v)| (k.to_string(), serde_json::Value::from(*v))).collect();
        let write = || -> std::io::Result<()> {
            std::fs::create_dir_all(&self.cfg.state_dir)?;
            std::fs::write(self.spend_file(), serde_json::json!({ "tables": tables }).to_string())
        };
        if let Err(e) = write() {
            tracing::warn!(error = %e, "could not persist the Stack spend (the per-table cap resets on restart)");
        }
    }

    /// React to the program's events (a hint from the log stream; every decision is re-read
    /// from accounts).
    pub(crate) fn on_program_events(&self, ev: &ProgramEvents) {
        let mut refresh = false;
        let mut nudge = false;
        let mut bonds = false;
        for e in &ev.events {
            match e {
                HdEvent::StackOpened { .. } | HdEvent::StackJoined { .. } | HdEvent::StackSettled { .. } | HdEvent::StackClaimed { .. } => {
                    refresh = true;
                }
                // A break, a re-arm or the end of a shift changes what a seated rig's seat can
                // do: re-plan now (a decisive break must be recorded inside the round).
                HdEvent::ShiftBroken { rig, .. } | HdEvent::ShiftArmed { rig, .. } | HdEvent::RigClosed { rig } => {
                    nudge |= self.nudge.is_seated(rig);
                }
                HdEvent::ShiftEndedV2 { rig, .. } | HdEvent::ShiftEnded { rig, .. } => {
                    nudge |= self.nudge.is_seated(rig);
                    bonds = true;
                }
                // Another crank's check-in, record or dig: the seat may be done, or observable.
                HdEvent::StackCheckin { .. } | HdEvent::HeartbeatsRecorded { .. } | HdEvent::RigDug { .. } => {
                    nudge |= e.rig().is_some_and(|rig| self.nudge.is_seated(&rig));
                }
                _ => {}
            }
        }
        if refresh {
            lock(&self.stack).dirty = true;
        }
        if refresh || nudge {
            self.stack_nudge();
        }
        if bonds {
            self.cleanup_notify.notify_one();
        }
    }

    /// Refresh the cache of open tables and their pending seats.
    pub(crate) async fn stack_discover(&self) -> anyhow::Result<()> {
        let tables = self.rpc.get_program_accounts(&self.program_id, &rpc::open_stack_table_filters()).await?;
        let seats = self.rpc.get_program_accounts(&self.program_id, &rpc::pending_stack_seat_filters(None)).await?;
        let live_round = self.chain.borrow().board.map(|b| b.round_id);
        let mut map: HashMap<Address, TableEntry> = HashMap::new();
        for (addr, acc) in tables {
            if let Ok(t) = StackTable::decode(&self.program_id, &acc.owner, &acc.data) {
                // A table nobody joined whose window is over has nothing to pay out and nothing
                // to bury: it stays Open on-chain for good and is not worth tracking.
                let empty_and_over = t.seat_count == 0 && live_round.is_some_and(|r| r > t.end_round);
                if t.status == skr::status::OPEN && !empty_and_over {
                    map.insert(addr, TableEntry { table: t, seats: Vec::new() });
                }
            }
        }
        let mut indexed: HashMap<Address, Vec<(u8, Address, Address)>> = HashMap::new();
        for (addr, acc) in seats {
            if let Ok(s) = StackSeat::decode(&self.program_id, &acc.owner, &acc.data) {
                if map.contains_key(&s.table) {
                    indexed.entry(s.table).or_default().push((s.seat_index, addr, s.rig));
                }
            }
        }
        for (table, mut list) in indexed {
            list.sort_by_key(|(i, a, _)| (*i, a.to_bytes()));
            if let Some(e) = map.get_mut(&table) {
                e.seats = list.into_iter().map(|(_, a, r)| (a, r)).collect();
            }
        }
        // Bounded: the tables with the most SKR bonded first (bonds are the one thing an
        // attacker opening tables to drain the crank's budget has to pay for).
        let max = self.cfg.stack.max_tables;
        if map.len() > max {
            let mut ranked: Vec<(Address, u64)> = map.iter().map(|(a, e)| (*a, e.table.total_bonds)).collect();
            ranked.sort_by_key(|(a, bonds)| (std::cmp::Reverse(*bonds), a.to_bytes()));
            let keep: HashSet<Address> = ranked.into_iter().take(max).map(|(a, _)| a).collect();
            tracing::warn!(open = map.len(), tracked = max, "more open Stack tables than stack.max_tables; tracking the largest bonds");
            map.retain(|a, _| keep.contains(a));
        }
        let rigs: HashSet<Address> = map.values().flat_map(|e| e.seats.iter().map(|(_, r)| *r)).collect();
        if let Ok(mut set) = self.nudge.rigs.write() {
            *set = rigs;
        }
        self.metrics.stack_tables_open.set_u64(map.len() as u64);
        let mut st = lock(&self.stack);
        st.spend.retain(|a, _| map.contains_key(a));
        st.budget_alerted.retain(|a| map.contains_key(a));
        st.settle_tries.retain(|a, _| map.contains_key(a));
        st.last_send.retain(|a, _| map.contains_key(a));
        st.tables = map;
        st.last_discovery = Some(Instant::now());
        st.dirty = false;
        Ok(())
    }

    /// Read the tables, seats and rigs of `tables` in one call.
    async fn stack_read(&self, tables: &[(Address, TableEntry)]) -> anyhow::Result<Fresh> {
        let mut keys: Vec<Address> = Vec::new();
        for (addr, e) in tables {
            keys.push(*addr);
            for (seat, rig) in &e.seats {
                keys.push(*seat);
                keys.push(*rig);
            }
        }
        let accs = self.rpc.get_multiple_accounts(&keys).await?;
        let mut it = accs.into_iter();
        let mut fresh = Fresh { tables: HashMap::new(), seats: HashMap::new() };
        for (addr, e) in tables {
            if let Some(t) = it.next().flatten().and_then(|a| StackTable::decode(&self.program_id, &a.owner, &a.data).ok()) {
                fresh.tables.insert(*addr, t);
            }
            let mut list = Vec::with_capacity(e.seats.len());
            for (seat_addr, rig_addr) in &e.seats {
                let seat = it.next().flatten().and_then(|a| StackSeat::decode(&self.program_id, &a.owner, &a.data).ok());
                let rig = it.next().flatten().and_then(|a| Rig::decode(&self.program_id, &a.owner, &a.data).ok());
                // A seat that is gone was claimed (a timeout refund): nothing left to do for it.
                if let Some(seat) = seat.filter(|s| s.table == *addr && s.rig == *rig_addr) {
                    list.push(SeatState { address: *seat_addr, seat, rig });
                }
            }
            fresh.seats.insert(*addr, list);
        }
        Ok(fresh)
    }

    /// One Stack pass for the live round (see the module docs).
    pub(crate) async fn stack_tick(self: &Arc<Self>, view: &ChainView) -> anyhow::Result<()> {
        let c = &self.cfg.stack;
        let Some(board) = view.board else { return Ok(()) };
        let round = board.round_id;
        let now = Instant::now();
        // Shutting down: send what is ready now (no waiting for a table's other seats), and
        // leave discovery and settles to the next process.
        let flush = self.is_draining();

        // ---- round bookkeeping and discovery --------------------------------------------
        self.nudge.pending.store(false, Ordering::SeqCst);
        let need_discovery = {
            let mut st = lock(&self.stack);
            st.last_pass = Some(now);
            let new_round = st.round != round;
            if new_round {
                let notes = std::mem::take(&mut st.notes);
                if st.round != 0 && !notes.is_empty() {
                    st.prev_notes = Some((st.round, notes));
                }
                st.round = round;
                st.round_seen = Some(now);
                st.idle_round = 0;
                st.ledger.prune(round.saturating_sub(2));
                st.marks.prune(round.saturating_sub(2));
                st.hb_in_flight.clear();
            }
            !flush && st.discovery_due(Duration::from_secs(c.discover_secs), new_round, round)
        };
        if need_discovery {
            if let Err(e) = self.stack_discover().await {
                tracing::warn!(error = %e, "Stack discovery failed; using the cached tables");
                // Try again in a few seconds, not on every loop turn.
                let mut st = lock(&self.stack);
                st.dirty = false;
                st.last_discovery = Some(now.checked_sub(Duration::from_secs(c.discover_secs.saturating_sub(3))).unwrap_or(now));
            }
        }

        // ---- which tables matter now ------------------------------------------------------
        let (active, ended) = {
            let st = lock(&self.stack);
            let mut active: Vec<(Address, TableEntry)> =
                st.tables.iter().filter(|(_, e)| e.table.in_window(round) && !e.seats.is_empty()).map(|(a, e)| (*a, e.clone())).collect();
            active.sort_by_key(|(a, e)| (std::cmp::Reverse(e.table.total_bonds), a.to_bytes()));
            let ended: Vec<(Address, TableEntry)> = st.tables.iter().filter(|(_, e)| e.table.settleable(round)).map(|(a, e)| (*a, e.clone())).collect();
            (active, ended)
        };
        self.metrics.stack_tables_active.set_u64(active.len() as u64);
        if active.is_empty() && ended.is_empty() {
            let mut st = lock(&self.stack);
            st.owned.clear();
            st.owned_round = round;
            st.idle_round = round;
            // No table of the round before is left to compare its notes with.
            st.prev_notes = None;
            self.metrics.stack_seats_active.set(0);
            self.metrics.stack_seats_pending.set(0);
            return Ok(());
        }

        // ---- fresh chain state: tables, seats, rigs -----------------------------------------
        let mut all = active.clone();
        all.extend(ended.iter().cloned());
        let fresh = self.stack_read(&all).await?;

        // ---- the previous round: what was missed --------------------------------------------
        // Taken only now, so a failed read does not lose the notes.
        let prev = lock(&self.stack).prev_notes.take();
        if let Some((prev_round, notes)) = prev {
            self.stack_account_round(prev_round, &notes, &fresh);
        }

        // ---- plan every active table ---------------------------------------------------------
        let heartbeats: HashMap<Address, VerifiedHeartbeat> = self.store.snapshot().into_iter().map(|h| (h.rig, h)).collect();
        let retry = c.retry();
        let (round_age, block_height_needed) = {
            let st = lock(&self.stack);
            (st.round_seen.map_or(Duration::ZERO, |t| t.elapsed()), st.ledger.has_pending(round) || st.marks.has_pending(round))
        };
        // The blockhash expiry check only matters when something of this round is pending.
        let block_height = if block_height_needed { self.rpc.get_block_height().await.unwrap_or(0) } else { 0 };
        // Rigs whose fresh heartbeat is in a transaction that may still land: a dig, a record,
        // or a verify-mode check-in at another table. Snapshots, so no two locks are ever held.
        let seated: HashSet<Address> = active.iter().flat_map(|(_, e)| e.seats.iter().map(|(_, r)| *r)).collect();
        let mut busy: HashSet<Address> = {
            let ledger = lock(&self.ledger);
            let stale_after = self.cfg.dig.retry_after_slots;
            seated
                .iter()
                .filter(|r| {
                    // A dig that has been unconfirmed for longer than its own retry window no
                    // longer holds the check-in back: the seat's round matters more than the dig.
                    matches!(ledger.status(r, round), Some(crate::ledger::DigStatus::Pending { sent_slot, .. }) if view.slot < sent_slot.saturating_add(stale_after))
                })
                .copied()
                .collect()
        };
        busy.extend(lock(&self.record_in_flight).iter().copied());
        busy.extend(
            lock(&self.stack)
                .hb_in_flight
                .iter()
                .filter(|(_, (_, sent))| view.slot < sent.saturating_add(retry.retry_after_slots))
                .map(|(rig, _)| *rig),
        );
        let mut used: HashSet<Address> = HashSet::new();
        let mut owned: HashSet<Address> = HashSet::new();
        let mut to_send: Vec<(Address, StackTable, Vec<tx::CheckinSeat>, TablePlan)> = Vec::new();
        let (mut seats_active, mut seats_pending) = (0u64, 0u64);
        // Something only time changes is outstanding (a held batch, a transaction in flight):
        // keep the short pass interval. Otherwise the loop sleeps until a nudge (a seated rig's
        // heartbeat, a landing, a program event) or the idle interval.
        let mut soon = block_height_needed;
        for (addr, entry) in &active {
            // The table may have been settled or refunded since the cache was filled.
            let Some(table) = fresh.tables.get(addr).filter(|t| t.in_window(round)) else {
                lock(&self.stack).soft_dirty();
                continue;
            };
            let seats = fresh.seats.get(addr).map(Vec::as_slice).unwrap_or(&[]);
            if seats.len() != entry.seats.len() || seats.len() != usize::from(table.seat_count) {
                lock(&self.stack).soft_dirty();
            }
            let plan = stack::plan_table(round, table, seats, &heartbeats, &|rig: &Address| busy.contains(rig), &mut used);
            soon |= plan.seats.iter().any(|(_, _, p)| matches!(p, SeatPlan::Wait(stack::Wait::InFlight)));
            let budget_left = lock(&self.stack).table_budget_left(addr, c.max_lamports_per_table);
            let exhausted = budget_left < tx::LAMPORTS_PER_SIGNATURE;
            {
                let mut st = lock(&self.stack);
                for (seat, rig, p) in &plan.seats {
                    let eligible = !matches!(p, SeatPlan::Skip(_) | SeatPlan::MarkBroken { .. });
                    let ready = matches!(p, SeatPlan::Verify(_) | SeatPlan::Observe);
                    let attempts_left = st.ledger.attempts(seat, round) < retry.max_attempts || st.ledger.is_pending(seat, round);
                    let note = st.notes.entry(*seat).or_insert(RoundNote { last_plan: p.label(), eligible, was_ready: false, budget_blocked: false });
                    note.last_plan = p.label();
                    note.eligible = eligible;
                    note.was_ready |= ready;
                    note.budget_blocked |= exhausted && ready;
                    if eligible {
                        seats_active += 1;
                        if !matches!(p, SeatPlan::Done) {
                            seats_pending += 1;
                        }
                    }
                    // The heartbeat stays with the dig / record passes when this loop cannot
                    // use it: no budget, or no attempt left for the seat this round.
                    if p.owns_heartbeat() && !exhausted && attempts_left {
                        owned.insert(*rig);
                    }
                }
                if exhausted && plan.has_work() && st.budget_alerted.insert(*addr) {
                    tracing::error!(
                        alert = "stack_table_budget_spent",
                        table = %addr,
                        spent = st.spend.get(addr).copied().unwrap_or(0),
                        cap = c.max_lamports_per_table,
                        "the fee budget of this Stack table is spent: its seats are no longer checked in (raise stack.max_lamports_per_table)"
                    );
                }
            }
            if exhausted {
                if plan.has_work() {
                    self.metrics.stack_budget_blocked.add("table", plan.countable() as u64);
                }
                continue;
            }
            let since_last = lock(&self.stack).last_send.get(addr).map(|t| t.elapsed());
            let policy = if flush { stack::SendPolicy::NOW } else { c.send_policy() };
            if !stack::should_send(&plan, round_age, since_last, view.slots_left(), &policy) {
                // Held for a fuller batch: look again soon (the batch wait, or the late slots).
                soon |= plan.has_work();
                continue;
            }
            // A break is recorded through its own ledger: the seat's count for this round may
            // already be `Counted`, and that must not stop the mark.
            let entries: Vec<tx::CheckinSeat> = {
                let st = lock(&self.stack);
                let ledgers = stack::Ledgers { counts: &st.ledger, marks: &st.marks };
                stack::sendable_entries(&plan, c.mark_broken, ledgers, round, block_height, view.slot, retry)
            };
            // A check-in is worth sending for a seat that counts, or for a decisive mark.
            if stack::worth_sending(&plan, &entries) {
                to_send.push((*addr, *table, entries, plan));
            }
        }
        self.metrics.stack_seats_active.set_u64(seats_active);
        self.metrics.stack_seats_pending.set_u64(seats_pending);
        {
            let mut st = lock(&self.stack);
            st.owned = owned;
            st.owned_round = round;
            st.idle_round = if !soon && to_send.is_empty() { round } else { 0 };
        }

        // ---- send ---------------------------------------------------------------------------------
        if !to_send.is_empty() {
            let alts = if self.alts_in_use() { self.usable_alts(view.slot) } else { vec![] };
            let mut p = self.base_params(round, c.cu_price_micro_lamports);
            p.max_rigs_per_tx = c.max_seats_per_tx;
            let mut sent = 0usize;
            'tables: for (addr, _table, entries, plan) in to_send {
                let (batches, rejected) = tx::pack_checkins(&p, &c.cu(), &addr, &entries, &alts);
                for (seat, misfit) in rejected {
                    tracing::warn!(table = %addr, seat = %seat.seat, ?misfit, "a check-in does not fit a transaction alone");
                    self.metrics.stack_tx_failed.inc("oversize");
                }
                for batch in batches {
                    if sent >= c.max_txs_per_pass {
                        break 'tables;
                    }
                    match self.send_checkins(view, &addr, &plan, batch, &p, &alts).await {
                        Ok(true) => sent += 1,
                        Ok(false) => {}
                        Err(e) => tracing::warn!(table = %addr, error = %e, "sending a Stack check-in failed"),
                    }
                }
            }
            if sent > 0 {
                self.persist_stack_spend();
            }
        }

        // ---- settle tables whose window is over ---------------------------------------------------
        if c.settle && !flush {
            for (addr, _) in ended {
                let Some(table) = fresh.tables.get(&addr).copied() else {
                    lock(&self.stack).soft_dirty();
                    continue;
                };
                if !table.settleable(round) {
                    // Settled (or refunded) by someone else since the cache was filled.
                    lock(&self.stack).soft_dirty();
                    continue;
                }
                let seats: Vec<(Address, StackSeat)> = fresh.seats.get(&addr).map(|s| s.iter().map(|x| (x.address, x.seat)).collect()).unwrap_or_default();
                self.maybe_settle(addr, table, seats);
            }
        }
        Ok(())
    }

    /// Simulate, sign and send one check-in batch; spawn its confirmation. `Ok(false)` when a
    /// budget or the simulation stopped it.
    async fn send_checkins(
        self: &Arc<Self>,
        view: &ChainView,
        table: &Address,
        plan: &TablePlan,
        batch: CheckinBatch,
        base: &tx::BuildParams,
        alts: &[solana_message::AddressLookupTableAccount],
    ) -> anyhow::Result<bool> {
        let c = &self.cfg.stack;
        let round = base.round_id;
        let seats: Vec<Address> = batch.seats.iter().map(|s| s.seat).collect();
        // Entries that record a break (their own ledger) rather than count the round.
        let marks: HashSet<Address> =
            seats.iter().filter(|s| matches!(plan.plan_of(s), Some(SeatPlan::MarkBroken { .. }))).copied().collect();
        let tip = self.submitter.tip_for(self.nonce.fetch_add(1, Ordering::Relaxed));
        let est_fee = tx::fee_for(batch.precompile_signatures, batch.cu_limit, base.cu_price_micro_lamports, tx::LAMPORTS_PER_SIGNATURE)
            .saturating_add(tip.map_or(0, |(_, l)| l));
        // Budgets: the table's cap, then the hourly bucket.
        {
            let mut st = lock(&self.stack);
            if st.table_budget_left(table, c.max_lamports_per_table) < est_fee {
                self.metrics.stack_budget_blocked.add("table", seats.len() as u64);
                for s in &seats {
                    if let Some(n) = st.notes.get_mut(s) {
                        n.budget_blocked = true;
                    }
                }
                return Ok(false);
            }
        }
        if !self.stack_budget.try_take(est_fee) {
            self.metrics.stack_budget_blocked.add("hour", seats.len() as u64);
            let mut st = lock(&self.stack);
            for s in &seats {
                if let Some(n) = st.notes.get_mut(s) {
                    n.budget_blocked = true;
                }
            }
            tracing::warn!(alert = "stack_hourly_budget_spent", table = %table, fee = est_fee, "the hourly Stack fee budget is spent for now");
            return Ok(false);
        }
        let mut p = base.clone();
        p.tip = tip;
        let (blockhash, lvbh) = match self.rpc.get_latest_blockhash().await {
            Ok(x) => x,
            Err(e) => {
                self.stack_budget.refund(est_fee);
                return Err(e.into());
            }
        };
        let mut cu_limit = batch.cu_limit;
        if c.simulate {
            #[allow(clippy::clone_on_copy)] // Hash is Copy only with solana-hash's `copy` feature
            let t = tx::sign_checkin_batch(&p, cu_limit, table, &batch.seats, alts, blockhash.clone(), self.key.keypair())?;
            match self.rpc.simulate_transaction(&tx::serialize(&t)?).await {
                Ok(sim) if sim.err.is_none() => {
                    if let Some(u) = sim.units_consumed {
                        let m = u64::from(self.cfg.dig.cu_margin_percent);
                        let with_margin = (u.saturating_mul(100u64.saturating_add(m)) / 100).saturating_add(1_000);
                        cu_limit = u32::try_from(with_margin).unwrap_or(tx::MAX_COMPUTE_UNITS).min(tx::MAX_COMPUTE_UNITS);
                    }
                }
                Ok(sim) => {
                    // The whole transaction would fail (the round just changed, the table was
                    // settled): not sent, not paid for, and counted as an attempt.
                    self.stack_budget.refund(est_fee);
                    self.metrics.stack_tx_failed.inc("simulation");
                    let tail: Vec<&String> = sim.logs.iter().rev().take(4).collect();
                    tracing::warn!(table = %table, round, err = ?sim.err, logs = ?tail, "a Stack check-in failed in simulation; not sent");
                    let mut st = lock(&self.stack);
                    for s in &seats {
                        st.ledger_of(marks.contains(s)).record_failed_attempt(s, round);
                    }
                    st.soft_dirty();
                    return Ok(false);
                }
                Err(e) => tracing::warn!(error = %e, "simulateTransaction failed (check-in); sending with the estimated CU limit"),
            }
        }
        let t = tx::sign_checkin_batch(&p, cu_limit, table, &batch.seats, alts, blockhash, self.key.keypair())?;
        let wire = tx::serialize(&t)?;
        let sig = t.signatures.first().map(ToString::to_string).unwrap_or_default();
        if let Err(e) = self.submitter.send(&wire).await {
            self.stack_budget.refund(est_fee);
            self.metrics.stack_tx_failed.inc("send");
            return Err(e.into());
        }
        {
            let mut st = lock(&self.stack);
            for s in &seats {
                st.ledger_of(marks.contains(s)).mark_pending(std::slice::from_ref(s), round, lvbh, view.slot);
            }
            for s in &batch.seats {
                if let Some(h) = s.heartbeat {
                    st.hb_in_flight.insert(s.rig, (h.fields.counter, view.slot));
                }
            }
            *st.spend.entry(*table).or_insert(0) += est_fee;
            st.last_send.insert(*table, Instant::now());
        }
        self.metrics.txs_sent.inc();
        self.metrics.stack_txs_sent.inc();
        for s in &batch.seats {
            self.metrics.stack_checkins_sent.inc(plan.plan_of(&s.seat).map_or("observe", mode_label));
        }
        tracing::info!(%sig, table = %table, round, seats = seats.len(), verified = batch.precompile_signatures, bytes = wire.len(), cu_limit, fee = est_fee, "stack_checkin sent");
        let this = self.clone();
        let table = *table;
        let guard = self.in_flight.guard(&self.metrics);
        tokio::spawn(async move {
            let _guard = guard;
            let policy = ConfirmPolicy {
                poll_every: Duration::from_millis(400),
                rebroadcast_every: Duration::from_secs(1),
                rebroadcast_for: Duration::from_secs(20),
                max_wait: Duration::from_secs(60),
            };
            let outcome = this.submitter.confirm(&sig, &wire, lvbh, policy).await;
            this.on_checkin_outcome(&sig, &table, round, &batch, &marks, est_fee, outcome).await;
        });
        Ok(true)
    }

    #[allow(clippy::too_many_arguments)]
    async fn on_checkin_outcome(
        &self,
        sig: &str,
        table: &Address,
        round: u64,
        batch: &CheckinBatch,
        marks: &HashSet<Address>,
        est_fee: u64,
        outcome: Outcome,
    ) {
        let clear = |this: &Self| {
            let mut st = lock(&this.stack);
            for s in &batch.seats {
                if let Some(h) = s.heartbeat {
                    if st.hb_in_flight.get(&s.rig).is_some_and(|(c, _)| *c == h.fields.counter) {
                        st.hb_in_flight.remove(&s.rig);
                    }
                }
            }
        };
        match outcome {
            Outcome::Landed { err: None, slot } => {
                self.metrics.txs_confirmed.inc();
                let max_version = if self.cfg.dig.tx_format == TxFormat::V1 { 1 } else { 0 };
                let info = self.fetch_events(sig, max_version).await;
                let (mut counted, mut marked, mut refused) = (0u32, 0u32, 0u32);
                if let Some(i) = &info {
                    self.metrics.stack_fees_lamports.add(i.fee);
                    self.metrics.compute_units.add(i.compute_units.unwrap_or(0));
                    for ev in hd::events_from_logs(&self.program_id, &i.logs) {
                        let HdEvent::StackCheckin { table: t, rig, round_id, result, .. } = ev else { continue };
                        let Some(s) = batch.seats.iter().find(|s| s.rig == rig).filter(|_| t == *table) else { continue };
                        self.metrics.stack_checkins_landed.inc(hd::checkin_result_name(result));
                        let mark = marks.contains(&s.seat);
                        // The result is about the round the transaction landed in. A mark is done
                        // when the program reports the seat broken; a count when it reports 0.
                        let done = if mark { result == hd::code::STACK_SEAT_BROKEN } else { result == 0 && round_id == round };
                        let status = if done { CheckinStatus::Counted } else { CheckinStatus::NotCounted(result) };
                        lock(&self.stack).ledger_of(mark).mark(&s.seat, round, status);
                        if result == 0 {
                            counted += 1;
                        } else if mark && done {
                            marked += 1;
                        } else {
                            refused += 1;
                        }
                        if let Some(h) = s.heartbeat.filter(|_| stack::heartbeat_consumed_by(result)) {
                            self.store.remove_if_counter_at_most(&rig, h.fields.counter);
                        }
                    }
                }
                clear(self);
                tracing::info!(%sig, slot, table = %table, round, counted, marked_broken = marked, refused, "stack_checkin landed");
            }
            Outcome::Landed { err: Some(err), slot } => {
                self.metrics.stack_tx_failed.inc("onchain");
                tracing::warn!(%sig, slot, table = %table, round, %err, "stack_checkin failed on-chain");
                let mut st = lock(&self.stack);
                for s in &batch.seats {
                    st.ledger_of(marks.contains(&s.seat)).mark(&s.seat, round, CheckinStatus::Failed);
                }
                st.soft_dirty();
                drop(st);
                clear(self);
            }
            Outcome::Expired => {
                // Never landed: no fee was charged.
                self.metrics.stack_tx_failed.inc("expired");
                self.stack_budget.refund(est_fee);
                let mut st = lock(&self.stack);
                if let Some(v) = st.spend.get_mut(table) {
                    *v = v.saturating_sub(est_fee);
                }
                for s in &batch.seats {
                    st.ledger_of(marks.contains(&s.seat)).mark(&s.seat, round, CheckinStatus::Failed);
                }
                drop(st);
                clear(self);
            }
            Outcome::Unknown => {
                self.metrics.stack_tx_failed.inc("unconfirmed");
                clear(self);
            }
        }
        // Re-plan at once: a seat at another table can now observe, a refused one can retry.
        self.stack_nudge();
    }

    /// Count the rounds that seats could have counted and did not, with one alert line each.
    fn stack_account_round(&self, prev_round: u64, notes: &HashMap<Address, RoundNote>, fresh: &Fresh) {
        let retry = self.cfg.stack.retry();
        for (table_addr, seats) in &fresh.seats {
            let Some(table) = fresh.tables.get(table_addr) else { continue };
            if prev_round < table.start_round || prev_round > table.end_round {
                continue;
            }
            for s in seats {
                let Some(note) = notes.get(&s.address) else { continue };
                if s.seat.last_round == prev_round {
                    continue; // counted
                }
                if !note.eligible {
                    self.metrics.stack_seats_skipped.inc(note.last_plan);
                    continue;
                }
                let (status, attempts) = {
                    let st = lock(&self.stack);
                    (st.ledger.status(&s.address, prev_round), st.ledger.attempts(&s.address, prev_round))
                };
                let Some(missed) = stack::missed_reason(Some(note), status, attempts, retry) else { continue };
                let reason = missed.label();
                self.metrics.stack_checkins_missed.inc(reason);
                let gaps = skr::window_len(table.start_round, prev_round).saturating_sub(s.seat.checked_rounds);
                let lost = !stack::can_still_finish(table, &s.seat, prev_round);
                if lost && lock(&self.stack).lost_alerted.insert(s.address) {
                    self.metrics.stack_seats_lost.inc(reason);
                    tracing::error!(
                        alert = "stack_seat_lost",
                        table = %table_addr,
                        seat = %s.address,
                        rig = %s.seat.rig,
                        round = prev_round,
                        reason,
                        gaps,
                        grace = table.grace_gaps,
                        end_round = table.end_round,
                        "a Stack seat can no longer finish: a round was not counted (Stack is fail-closed)"
                    );
                } else {
                    tracing::warn!(
                        alert = "stack_checkin_missed",
                        table = %table_addr,
                        seat = %s.address,
                        rig = %s.seat.rig,
                        round = prev_round,
                        reason,
                        gaps,
                        grace = table.grace_gaps,
                        "a Stack seat did not count this round (a gap)"
                    );
                }
            }
        }
    }

    /// Start a settle for `table` unless one is in flight, was tried too recently, or cannot
    /// be built yet.
    fn maybe_settle(self: &Arc<Self>, addr: Address, table: StackTable, seats: Vec<(Address, StackSeat)>) {
        let c = &self.cfg.stack;
        {
            let mut st = lock(&self.stack);
            if st.settling.contains(&addr) {
                return;
            }
            if let Some((tries, at)) = st.settle_tries.get(&addr) {
                if *tries >= c.settle_max_attempts || at.elapsed() < Duration::from_secs(c.settle_retry_secs) {
                    return;
                }
            }
            if table.seat_count == 0 {
                // Nobody joined: there is nothing to pay out and nothing to bury.
                st.tables.remove(&addr);
                return;
            }
            if seats.len() != usize::from(table.seat_count) {
                st.soft_dirty(); // a seat is missing from the cache: refresh, then try again
                return;
            }
            st.settling.insert(addr);
        }
        let this = self.clone();
        let guard = self.in_flight.guard(&self.metrics);
        tokio::spawn(async move {
            let _guard = guard;
            let result = this.settle_table(&addr, &table, &seats).await;
            let mut st = lock(&this.stack);
            st.settling.remove(&addr);
            match result {
                Ok(true) => {
                    st.tables.remove(&addr);
                    st.settle_tries.remove(&addr);
                    st.spend.remove(&addr);
                }
                Ok(false) => {
                    let e = st.settle_tries.entry(addr).or_insert((0, Instant::now()));
                    *e = (e.0 + 1, Instant::now());
                    st.soft_dirty();
                }
                Err(e) => {
                    let n = {
                        let t = st.settle_tries.entry(addr).or_insert((0, Instant::now()));
                        *t = (t.0 + 1, Instant::now());
                        t.0
                    };
                    drop(st);
                    this.metrics.stack_tx_failed.inc("settle");
                    if n >= this.cfg.stack.settle_max_attempts {
                        tracing::error!(alert = "stack_settle_failed", table = %addr, attempts = n, error = %e, "settle_stack keeps failing; giving up (anyone can settle; after the timeout every seat can claim its bond back)");
                    } else {
                        tracing::warn!(table = %addr, attempts = n, error = %e, "settle_stack failed; will retry");
                    }
                }
            }
        });
    }

    /// `settle_stack` with every seat. `Ok(true)` once the table is settled (by this crank or
    /// by someone else in the meantime).
    async fn settle_table(&self, addr: &Address, table: &StackTable, seats: &[(Address, StackSeat)]) -> anyhow::Result<bool> {
        let c = &self.cfg.stack;
        let seat_accounts: Vec<StackSeat> = seats.iter().map(|(_, s)| *s).collect();
        let Some(preview) = skr::preview_settle(addr, table, &seat_accounts) else {
            anyhow::bail!("the seats of {addr} do not add up to its total bonds");
        };
        if preview.bury > 0 && !self.ensure_bury_vault().await? {
            anyhow::bail!("the Bury vault does not exist and stack.init_bury_vault is off");
        }
        let price = c.cu_price_micro_lamports;
        let fee = tx::fee_for(0, c.settle_cu_limit, price, tx::LAMPORTS_PER_SIGNATURE);
        if !self.stack_budget.try_take(fee) {
            self.metrics.stack_budget_blocked.inc("hour");
            return Ok(false);
        }
        let addrs: Vec<Address> = seats.iter().map(|(a, _)| *a).collect();
        let ixs = tx::settle_stack_instructions(&self.program_id, addr, &table.vault, &addrs, c.settle_cu_limit, price);
        match self.send_signed(&ixs).await {
            Ok((sig, Outcome::Landed { err: None, slot })) => {
                self.metrics.stack_settled.inc();
                self.metrics.stack_finishers.add(u64::from(preview.finishers));
                self.metrics.stack_bury_skr.add(preview.bury);
                if let Some(i) = self.fetch_events(&sig, 0).await {
                    self.metrics.stack_fees_lamports.add(i.fee);
                }
                tracing::info!(
                    %sig,
                    slot,
                    table = %addr,
                    seats = seats.len(),
                    finishers = preview.finishers,
                    total_bonds = preview.total_bonds,
                    payouts_total = preview.payouts_total,
                    bury = preview.bury,
                    "settled a Stack table"
                );
                Ok(true)
            }
            Ok((sig, other)) => {
                self.stack_budget.refund(fee);
                anyhow::bail!("settle {sig} did not land: {other:?}")
            }
            Err(e) => {
                self.stack_budget.refund(fee);
                // Someone else may have settled it first: that is a success too.
                if let Ok(Some(acc)) = self.rpc.get_account(addr).await {
                    if StackTable::decode(&self.program_id, &acc.owner, &acc.data).is_ok_and(|t| t.status != skr::status::OPEN) {
                        return Ok(true);
                    }
                }
                Err(e)
            }
        }
    }

    /// Make sure the Bury vault and the lot's SKR token account exist (a settle or a forfeit
    /// that moves SKR to the Bury lot needs both). Creates what is missing when
    /// `stack.init_bury_vault` allows; the crank pays the rent once and can never take it back.
    pub(crate) async fn ensure_bury_vault(&self) -> anyhow::Result<bool> {
        if self.bury_ready.load(Ordering::Relaxed) {
            return Ok(true);
        }
        // One caller at a time: a settle and a forfeit that both need the vault must not both
        // pay to create it (the second `init_bury_vault` would fail).
        let _one = self.bury_sync.lock().await;
        if self.bury_ready.load(Ordering::Relaxed) {
            return Ok(true);
        }
        let bury = skr::bury_vault_pda(&self.program_id).0;
        let lot = skr::ata(&bury, &skr::SKR_MINT);
        let accs = self.rpc.get_multiple_accounts(&[bury, lot]).await?;
        let vault_ok = accs.first().and_then(|a| a.as_ref()).is_some_and(|a| skr::BuryVault::decode(&self.program_id, &a.owner, &a.data).is_ok());
        let lot_ok = accs.get(1).and_then(|a| a.as_ref()).is_some_and(|a| skr::token_amount(&a.owner, &a.data).is_some());
        if vault_ok && lot_ok {
            self.bury_ready.store(true, Ordering::Relaxed);
            return Ok(true);
        }
        if !self.cfg.stack.init_bury_vault {
            tracing::error!(alert = "bury_vault_missing", vault = vault_ok, lot = lot_ok, "the Bury vault or its SKR account does not exist and stack.init_bury_vault is off");
            return Ok(false);
        }
        let cranker = self.key.keypair().pubkey();
        let before = self.rpc.get_balance(&cranker).await.unwrap_or(0);
        let ixs = tx::init_bury_vault_instructions(&self.program_id, &cranker, !vault_ok, !lot_ok, BURY_INIT_CU_LIMIT, self.cfg.dig.cu_price_micro_lamports);
        match self.send_signed(&ixs).await? {
            (sig, Outcome::Landed { err: None, slot }) => {
                let paid = before.saturating_sub(self.rpc.get_balance(&cranker).await.unwrap_or(before));
                self.metrics.bury_init_lamports.add(paid);
                self.bury_ready.store(true, Ordering::Relaxed);
                tracing::info!(%sig, slot, vault = %bury, lot = %lot, paid, "created the Bury vault / its SKR account (one time)");
                Ok(true)
            }
            (sig, other) => anyhow::bail!("init_bury_vault {sig} did not land: {other:?}"),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn table(start: u64, end: u64, seat_count: u8) -> StackTable {
        StackTable {
            bump: 255,
            host: Address::new_from_array([1; 32]),
            vault: Address::new_from_array([2; 32]),
            table_id: 1,
            bond: 100,
            start_round: start,
            end_round: end,
            grace_gaps: 0,
            flags: 0,
            max_seats: 8,
            status: skr::status::OPEN,
            seat_count,
            finishers: 0,
            claimed_count: 0,
            total_bonds: 100 * u64::from(seat_count),
            finisher_bonds: 0,
            payouts_total: 0,
            bury_amount: 0,
            claimed_total: 0,
            refund_after_ts: 0,
            opened_ts: 0,
            opened_round: start.saturating_sub(1),
        }
    }

    fn ago(d: Duration) -> Option<Instant> {
        Some(Instant::now().checked_sub(d).expect("the monotonic clock is past the test's offsets"))
    }

    #[test]
    fn a_mismatch_asks_for_a_refresh_only_when_the_cache_is_not_fresh() {
        let mut st = StackRuntime::default();
        // Never discovered: a refresh is due whatever else is true.
        assert!(st.discovery_due(Duration::from_secs(30), false, 10));
        // Just discovered: a read that disagrees with the cache does not ask again ...
        st.last_discovery = Some(Instant::now());
        st.soft_dirty();
        assert!(!st.dirty, "a mismatch a refresh did not fix must not turn every pass into a getProgramAccounts");
        assert!(!st.discovery_due(Duration::from_secs(30), false, 10));
        // ... but it does once the cache is older than SOFT_REFRESH.
        st.last_discovery = ago(SOFT_REFRESH + Duration::from_millis(50));
        st.soft_dirty();
        assert!(st.dirty);
        assert!(st.discovery_due(Duration::from_secs(30), false, 10));
    }

    #[test]
    fn discovery_is_paced() {
        let every = Duration::from_secs(30);
        let mut st = StackRuntime { last_discovery: Some(Instant::now()), ..StackRuntime::default() };
        // An event (StackOpened, StackJoined, ...) right after a discovery waits for the gap.
        st.dirty = true;
        assert!(!st.discovery_due(every, false, 10), "a burst of events is one refresh, not one per event");
        st.last_discovery = ago(MIN_DISCOVERY_GAP + Duration::from_millis(50));
        assert!(st.discovery_due(every, false, 10));
        // The periodic refresh.
        st.dirty = false;
        assert!(!st.discovery_due(every, false, 10));
        st.last_discovery = ago(every);
        assert!(st.discovery_due(every, false, 10));
        // A new round in which a cached table's window opens (its seats may have joined since
        // the last refresh), or whose seats do not add up.
        st.last_discovery = ago(MIN_DISCOVERY_GAP + Duration::from_millis(50));
        let seat = |i: u8| (Address::new_from_array([0x40 + i; 32]), Address::new_from_array([0x60 + i; 32]));
        st.tables.insert(Address::new_from_array([9; 32]), TableEntry { table: table(11, 20, 2), seats: vec![seat(0), seat(1)] });
        assert!(!st.discovery_due(every, true, 10), "the window opens next round");
        assert!(st.discovery_due(every, true, 11), "the window opens now");
        assert!(!st.discovery_due(every, false, 11), "only once, on the round change");
        assert!(!st.discovery_due(every, true, 12));
        st.tables.insert(Address::new_from_array([8; 32]), TableEntry { table: table(5, 20, 3), seats: vec![seat(2)] });
        assert!(st.discovery_due(every, true, 12), "a table with 3 seats on-chain and 1 in the cache");
    }

    #[test]
    fn the_table_budget_is_a_cap_and_zero_means_none() {
        let mut st = StackRuntime::default();
        let t = Address::new_from_array([9; 32]);
        assert_eq!(st.table_budget_left(&t, 1_000), 1_000);
        st.spend.insert(t, 400);
        assert_eq!(st.table_budget_left(&t, 1_000), 600);
        st.spend.insert(t, 5_000);
        assert_eq!(st.table_budget_left(&t, 1_000), 0, "never negative");
        assert_eq!(st.table_budget_left(&t, 0), u64::MAX, "0 = no per-table cap");
    }

    #[test]
    fn check_in_modes_have_fixed_metric_labels() {
        let hb = VerifiedHeartbeat {
            rig: Address::new_from_array([1; 32]),
            fields: hd::HeartbeatFields { counter: 1, shift_id: 1, round_id: 5, lease_rounds: 1 },
            sig: [0; 64],
            pubkey: [2; 33],
            digest: [0; 32],
        };
        assert_eq!(mode_label(&SeatPlan::Verify(hb)), "verify");
        assert_eq!(mode_label(&SeatPlan::Observe), "observe");
        assert_eq!(mode_label(&SeatPlan::MarkBroken { decisive: true }), "mark_broken");
    }
}
