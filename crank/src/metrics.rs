//! Prometheus text-format metrics with bounded cardinality.
//!
//! Labels are only ever `&'static str` reason codes (heartbeat rejects, planner skips,
//! on-chain error names), never rig addresses or IPs, so the series count is fixed at
//! compile time.

use std::collections::BTreeMap;
use std::fmt::Write as _;
use std::sync::atomic::{AtomicI64, AtomicU64, Ordering};
use std::sync::Mutex;

/// Monotonic counter.
#[derive(Debug, Default)]
pub struct Counter(AtomicU64);

impl Counter {
    /// Add `n`.
    pub fn add(&self, n: u64) {
        self.0.fetch_add(n, Ordering::Relaxed);
    }
    /// Add 1.
    pub fn inc(&self) {
        self.add(1);
    }
    /// Current value.
    pub fn get(&self) -> u64 {
        self.0.load(Ordering::Relaxed)
    }
}

/// Gauge.
#[derive(Debug, Default)]
pub struct Gauge(AtomicI64);

impl Gauge {
    /// Set.
    pub fn set(&self, v: i64) {
        self.0.store(v, Ordering::Relaxed);
    }
    /// Set from u64 (saturating).
    pub fn set_u64(&self, v: u64) {
        self.set(i64::try_from(v).unwrap_or(i64::MAX));
    }
    /// Add (may be negative).
    pub fn add(&self, d: i64) {
        self.0.fetch_add(d, Ordering::Relaxed);
    }
    /// Current value.
    pub fn get(&self) -> i64 {
        self.0.load(Ordering::Relaxed)
    }
}

/// Counter family with one static-string label.
#[derive(Debug, Default)]
pub struct LabeledCounter(Mutex<BTreeMap<&'static str, u64>>);

impl LabeledCounter {
    /// Add 1 to `label`.
    pub fn inc(&self, label: &'static str) {
        self.add(label, 1);
    }
    /// Add `n` to `label`.
    pub fn add(&self, label: &'static str, n: u64) {
        let mut m = match self.0.lock() {
            Ok(m) => m,
            Err(p) => p.into_inner(),
        };
        let v = m.entry(label).or_insert(0);
        *v = v.saturating_add(n);
    }
    /// Value for `label`.
    pub fn get(&self, label: &str) -> u64 {
        self.0.lock().map(|m| m.get(label).copied().unwrap_or(0)).unwrap_or(0)
    }
    fn snapshot(&self) -> Vec<(&'static str, u64)> {
        self.0.lock().map(|m| m.iter().map(|(k, v)| (*k, *v)).collect()).unwrap_or_default()
    }
}

/// Every metric the crank exports.
#[derive(Debug, Default)]
#[allow(missing_docs)]
pub struct Metrics {
    pub rigs_seen: Gauge,
    pub rigs_eligible: Gauge,
    pub heartbeats_accepted: Counter,
    pub heartbeats_rejected: LabeledCounter,
    pub heartbeats_held: Gauge,
    pub intake_connections: Gauge,
    pub digs_submitted: Counter,
    pub digs_landed: Counter,
    pub digs_skipped: LabeledCounter,
    pub digs_skipped_onchain: LabeledCounter,
    pub txs_sent: Counter,
    pub txs_confirmed: Counter,
    pub txs_failed: LabeledCounter,
    pub checkpoints_sent: Counter,
    pub compute_units: Counter,
    pub fees_lamports: Counter,
    pub reimbursed_lamports: Counter,
    pub board_round_id: Gauge,
    pub slot: Gauge,
    pub ema_ev_lamports: Gauge,
    pub motherlode: Gauge,
    pub executor_lamports: Gauge,
    pub cranker_lamports: Gauge,
    pub breaker_tripped: Gauge,
    pub chain_updates: LabeledCounter,
    pub chain_reconnects: Counter,
    /// SOL placed on squares, summed over `RigDug.lamports` (no Automation fee).
    pub squares_lamports: Counter,
    /// Automation debit the planner expects for landed digs (squares + fee on first deploy).
    pub automation_debit_lamports: Counter,
    pub signals_accepted: LabeledCounter,
    pub signals_rejected: LabeledCounter,
    pub signals_landed: LabeledCounter,
    pub signals_failed: LabeledCounter,
    pub signal_fees_lamports: Counter,
    pub record_txs_sent: Counter,
    pub heartbeats_recorded: Counter,
    pub record_dark_rounds: Counter,
    pub record_skipped: LabeledCounter,
    pub record_fees_lamports: Counter,
}

fn counter(out: &mut String, name: &str, help: &str, v: u64) {
    let _ = writeln!(out, "# HELP {name} {help}\n# TYPE {name} counter\n{name} {v}");
}

fn gauge(out: &mut String, name: &str, help: &str, v: i64) {
    let _ = writeln!(out, "# HELP {name} {help}\n# TYPE {name} gauge\n{name} {v}");
}

fn labeled(out: &mut String, name: &str, help: &str, label: &str, c: &LabeledCounter) {
    let _ = writeln!(out, "# HELP {name} {help}\n# TYPE {name} counter");
    for (k, v) in c.snapshot() {
        let _ = writeln!(out, "{name}{{{label}=\"{k}\"}} {v}");
    }
}

impl Metrics {
    /// Prometheus text exposition.
    pub fn render(&self) -> String {
        let mut o = String::with_capacity(4096);
        gauge(&mut o, "hd_crank_rigs_seen", "Armed or Down rigs read this round", self.rigs_seen.get());
        gauge(&mut o, "hd_crank_rigs_eligible", "Rigs the planner chose to dig this round", self.rigs_eligible.get());
        counter(&mut o, "hd_crank_heartbeats_accepted_total", "Heartbeats that verified and were stored", self.heartbeats_accepted.get());
        labeled(&mut o, "hd_crank_heartbeats_rejected_total", "Heartbeats refused, by reason", "reason", &self.heartbeats_rejected);
        gauge(&mut o, "hd_crank_heartbeats_held", "Rigs with a held heartbeat", self.heartbeats_held.get());
        gauge(&mut o, "hd_crank_intake_connections", "Open intake WebSocket connections", self.intake_connections.get());
        counter(&mut o, "hd_crank_digs_submitted_total", "Rig digs put in a sent transaction", self.digs_submitted.get());
        counter(&mut o, "hd_crank_digs_landed_total", "RigDug events observed", self.digs_landed.get());
        labeled(&mut o, "hd_crank_digs_skipped_total", "Rigs the planner did not dig, by reason", "reason", &self.digs_skipped);
        labeled(&mut o, "hd_crank_digs_skipped_onchain_total", "RigSkipped events, by program error", "error", &self.digs_skipped_onchain);
        counter(&mut o, "hd_crank_txs_sent_total", "Transactions sent", self.txs_sent.get());
        counter(&mut o, "hd_crank_txs_confirmed_total", "Transactions confirmed without error", self.txs_confirmed.get());
        labeled(&mut o, "hd_crank_txs_failed_total", "Transactions that failed or expired", "kind", &self.txs_failed);
        counter(&mut o, "hd_crank_checkpoints_sent_total", "ORE checkpoint instructions sent", self.checkpoints_sent.get());
        counter(&mut o, "hd_crank_compute_units_total", "Compute units consumed by landed transactions", self.compute_units.get());
        counter(&mut o, "hd_crank_fees_lamports_total", "Fees paid by the crank (landed transactions)", self.fees_lamports.get());
        counter(&mut o, "hd_crank_reimbursed_lamports_total", "crank_fee reimbursements implied by RigDug events", self.reimbursed_lamports.get());
        gauge(&mut o, "hd_crank_board_round_id", "ORE Board.round_id", self.board_round_id.get());
        gauge(&mut o, "hd_crank_slot", "Latest slot seen", self.slot.get());
        gauge(&mut o, "hd_crank_ema_ev_lamports", "Motherlode-adjusted cost gate value (lamports per ORE)", self.ema_ev_lamports.get());
        gauge(&mut o, "hd_crank_motherlode", "Treasury.motherlode (ORE base units)", self.motherlode.get());
        gauge(&mut o, "hd_crank_executor_lamports", "Executor PDA balance", self.executor_lamports.get());
        gauge(&mut o, "hd_crank_cranker_lamports", "Crank fee-payer balance", self.cranker_lamports.get());
        gauge(&mut o, "hd_crank_circuit_breaker_tripped", "1 when digging is stopped by the ORE layout breaker", self.breaker_tripped.get());
        labeled(&mut o, "hd_crank_chain_updates_total", "Account updates received, by account", "account", &self.chain_updates);
        counter(&mut o, "hd_crank_chain_reconnects_total", "Chain stream reconnects", self.chain_reconnects.get());
        counter(&mut o, "hd_crank_squares_lamports_total", "SOL placed on squares by landed digs (RigDug.lamports, no fee)", self.squares_lamports.get());
        counter(
            &mut o,
            "hd_crank_automation_debit_lamports_total",
            "Automation debit of landed digs as planned (squares + fee on the first deploy of a round)",
            self.automation_debit_lamports.get(),
        );
        labeled(&mut o, "hd_crank_signals_accepted_total", "Phone BREAK / FREEZE accepted for landing, by kind", "kind", &self.signals_accepted);
        labeled(&mut o, "hd_crank_signals_rejected_total", "Phone BREAK / FREEZE refused at intake, by reason", "reason", &self.signals_rejected);
        labeled(&mut o, "hd_crank_signals_landed_total", "BREAK / FREEZE landed on-chain, by kind", "kind", &self.signals_landed);
        labeled(&mut o, "hd_crank_signals_failed_total", "BREAK / FREEZE not landed, by stage", "stage", &self.signals_failed);
        counter(&mut o, "hd_crank_signal_fees_lamports_total", "Fees paid landing BREAK / FREEZE", self.signal_fees_lamports.get());
        counter(&mut o, "hd_crank_record_txs_sent_total", "record_heartbeats transactions sent", self.record_txs_sent.get());
        counter(&mut o, "hd_crank_heartbeats_recorded_total", "HeartbeatsRecorded events observed", self.heartbeats_recorded.get());
        counter(&mut o, "hd_crank_record_dark_rounds_total", "Dark rounds added by recorded heartbeats", self.record_dark_rounds.get());
        labeled(&mut o, "hd_crank_record_skipped_total", "Rigs not recorded this round, by reason", "reason", &self.record_skipped);
        counter(&mut o, "hd_crank_record_fees_lamports_total", "Fees paid for record_heartbeats", self.record_fees_lamports.get());
        o
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn renders_prometheus_text() {
        let m = Metrics::default();
        m.heartbeats_accepted.add(3);
        m.heartbeats_rejected.inc("bad_signature");
        m.heartbeats_rejected.inc("bad_signature");
        m.breaker_tripped.set(1);
        let t = m.render();
        assert!(t.contains("hd_crank_heartbeats_accepted_total 3"));
        assert!(t.contains("hd_crank_heartbeats_rejected_total{reason=\"bad_signature\"} 2"));
        assert!(t.contains("# TYPE hd_crank_circuit_breaker_tripped gauge\nhd_crank_circuit_breaker_tripped 1"));
    }
}
