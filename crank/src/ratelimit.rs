//! Keyed token buckets with bounded memory.
//!
//! Used by the heartbeat intake per IP (IPv6 folded to its /64, so one host cannot mint a
//! fresh key per address) and per rig. The key map is capped: when it is full, idle buckets
//! are evicted first, and if nothing is idle the new key is refused, so a key-spraying
//! client can never grow memory without bound.

use std::collections::HashMap;
use std::hash::Hash;
use std::net::IpAddr;
use std::sync::Mutex;
use std::time::{Duration, Instant};

/// Rate-limit parameters.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Quota {
    /// Bucket size (burst).
    pub burst: u32,
    /// Tokens added per second.
    pub per_second: f64,
}

impl Quota {
    /// `burst` tokens, refilled at `per_second`.
    pub const fn new(burst: u32, per_second: f64) -> Self {
        Quota { burst, per_second }
    }
}

#[derive(Clone, Copy, Debug)]
struct Bucket {
    tokens: f64,
    last: Instant,
}

/// A map of token buckets keyed by `K`.
#[derive(Debug)]
pub struct KeyedLimiter<K> {
    quota: Quota,
    max_keys: usize,
    buckets: Mutex<HashMap<K, Bucket>>,
}

impl<K: Eq + Hash + Clone> KeyedLimiter<K> {
    /// New limiter tracking at most `max_keys` keys.
    pub fn new(quota: Quota, max_keys: usize) -> Self {
        KeyedLimiter { quota, max_keys: max_keys.max(1), buckets: Mutex::new(HashMap::new()) }
    }

    /// Take one token for `key` at `now`. `false` means rate limited (or the key table is
    /// full of active keys).
    pub fn check_at(&self, key: &K, now: Instant) -> bool {
        let mut map = match self.buckets.lock() {
            Ok(m) => m,
            Err(poisoned) => poisoned.into_inner(),
        };
        let burst = f64::from(self.quota.burst);
        if !map.contains_key(key) && map.len() >= self.max_keys {
            // Evict buckets that have refilled completely: they carry no state.
            let rate = self.quota.per_second;
            map.retain(|_, b| {
                let idle = now.saturating_duration_since(b.last).as_secs_f64();
                b.tokens + idle * rate < burst
            });
            if map.len() >= self.max_keys {
                return false;
            }
        }
        let b = map.entry(key.clone()).or_insert(Bucket { tokens: burst, last: now });
        let elapsed = now.saturating_duration_since(b.last).as_secs_f64();
        b.tokens = (b.tokens + elapsed * self.quota.per_second).min(burst);
        b.last = now;
        if b.tokens >= 1.0 {
            b.tokens -= 1.0;
            true
        } else {
            false
        }
    }

    /// [`Self::check_at`] with `Instant::now()`.
    pub fn check(&self, key: &K) -> bool {
        self.check_at(key, Instant::now())
    }

    /// Number of tracked keys.
    pub fn len(&self) -> usize {
        self.buckets.lock().map(|m| m.len()).unwrap_or(0)
    }

    /// No keys tracked.
    pub fn is_empty(&self) -> bool {
        self.len() == 0
    }

    /// Drop buckets untouched for longer than `idle`.
    pub fn prune(&self, idle: Duration) {
        let now = Instant::now();
        if let Ok(mut m) = self.buckets.lock() {
            m.retain(|_, b| now.saturating_duration_since(b.last) < idle);
        }
    }
}

/// The rate-limit key for a client IP: IPv4 as is, IPv6 truncated to its /64 (one
/// residential or cloud allocation), IPv4-mapped IPv6 unwrapped.
pub fn ip_key(ip: IpAddr) -> IpAddr {
    match ip {
        IpAddr::V4(_) => ip,
        IpAddr::V6(v6) => {
            if let Some(v4) = v6.to_ipv4_mapped() {
                return IpAddr::V4(v4);
            }
            let s = v6.segments();
            IpAddr::V6(std::net::Ipv6Addr::new(s[0], s[1], s[2], s[3], 0, 0, 0, 0))
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn burst_then_refill() {
        let l = KeyedLimiter::new(Quota::new(3, 1.0), 10);
        let t0 = Instant::now();
        assert!(l.check_at(&1, t0));
        assert!(l.check_at(&1, t0));
        assert!(l.check_at(&1, t0));
        assert!(!l.check_at(&1, t0), "burst exhausted");
        assert!(l.check_at(&2, t0), "keys are independent");
        assert!(!l.check_at(&1, t0 + Duration::from_millis(500)));
        assert!(l.check_at(&1, t0 + Duration::from_millis(1600)), "refilled");
    }

    #[test]
    fn key_table_is_bounded() {
        let l = KeyedLimiter::new(Quota::new(1, 0.001), 2);
        let t0 = Instant::now();
        assert!(l.check_at(&1, t0));
        assert!(l.check_at(&2, t0));
        assert!(!l.check_at(&3, t0), "table full of active keys");
        assert_eq!(l.len(), 2);
        // Once keys 1 and 2 have fully refilled (1000 s at 0.001/s) they carry no state and
        // are evicted to make room.
        assert!(l.check_at(&3, t0 + Duration::from_secs(1001)));
        assert_eq!(l.len(), 1);
    }

    #[test]
    fn ipv6_is_folded_to_slash_64() {
        let a: IpAddr = "2001:db8:1:2:aaaa::1".parse().unwrap();
        let b: IpAddr = "2001:db8:1:2:bbbb::9".parse().unwrap();
        let c: IpAddr = "2001:db8:1:3::1".parse().unwrap();
        assert_eq!(ip_key(a), ip_key(b));
        assert_ne!(ip_key(a), ip_key(c));
        let mapped: IpAddr = "::ffff:192.0.2.1".parse().unwrap();
        assert_eq!(ip_key(mapped), "192.0.2.1".parse::<IpAddr>().unwrap());
    }
}
