//! Wall-clock abstraction so every time-dependent check (nonce TTL, SIWS window, session
//! expiry, certificate validity) can be tested deterministically.

use std::sync::atomic::{AtomicI64, Ordering};

/// Source of the current Unix time in seconds.
pub trait Clock: Send + Sync + 'static {
    fn now_unix(&self) -> i64;
}

/// The system clock (UTC).
#[derive(Debug, Default, Clone, Copy)]
pub struct SystemClock;

impl Clock for SystemClock {
    fn now_unix(&self) -> i64 {
        time::OffsetDateTime::now_utc().unix_timestamp()
    }
}

/// A clock that only moves when told to. Test helper, also usable for offline tooling.
#[derive(Debug)]
pub struct ManualClock(AtomicI64);

impl ManualClock {
    pub fn new(now: i64) -> Self {
        Self(AtomicI64::new(now))
    }

    pub fn set(&self, now: i64) {
        self.0.store(now, Ordering::SeqCst);
    }

    pub fn advance(&self, secs: i64) {
        let _ = self
            .0
            .fetch_update(Ordering::SeqCst, Ordering::SeqCst, |t| Some(t.saturating_add(secs)));
    }
}

impl Clock for ManualClock {
    fn now_unix(&self) -> i64 {
        self.0.load(Ordering::SeqCst)
    }
}

/// Formats a Unix timestamp as RFC 3339 (UTC, `Z`). Out-of-range values fall back to the epoch.
pub fn rfc3339(ts: i64) -> String {
    let dt = time::OffsetDateTime::from_unix_timestamp(ts).unwrap_or(time::OffsetDateTime::UNIX_EPOCH);
    dt.format(&time::format_description::well_known::Rfc3339)
        .unwrap_or_else(|_| String::from("1970-01-01T00:00:00Z"))
}

/// Parses an RFC 3339 / ISO 8601 date-time with offset (as produced by JavaScript's
/// `Date.toISOString()` and by the MWA clients) into Unix seconds.
pub fn parse_rfc3339(s: &str) -> Option<i64> {
    time::OffsetDateTime::parse(s, &time::format_description::well_known::Rfc3339)
        .ok()
        .map(|dt| dt.unix_timestamp())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rfc3339_round_trip() {
        assert_eq!(rfc3339(0), "1970-01-01T00:00:00Z");
        assert_eq!(parse_rfc3339("2026-09-29T12:00:00Z"), Some(1_790_683_200));
        assert_eq!(parse_rfc3339("2026-09-29T12:00:00.123Z"), Some(1_790_683_200));
        assert_eq!(parse_rfc3339("2026-09-29T13:00:00+01:00"), Some(1_790_683_200));
        assert_eq!(parse_rfc3339(&rfc3339(1_790_683_200)), Some(1_790_683_200));
        assert_eq!(parse_rfc3339("2026-09-29 12:00:00"), None);
        assert_eq!(parse_rfc3339("yesterday"), None);
    }

    #[test]
    fn manual_clock_moves_only_when_told() {
        let c = ManualClock::new(100);
        assert_eq!(c.now_unix(), 100);
        c.advance(5);
        assert_eq!(c.now_unix(), 105);
        c.set(7);
        assert_eq!(c.now_unix(), 7);
        c.set(i64::MAX);
        c.advance(1);
        assert_eq!(c.now_unix(), i64::MAX);
    }
}
