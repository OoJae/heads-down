//! Per-client-IP rate limiting (GCRA via `governor`).
//!
//! Two buckets: a general one for every API route, and a much tighter one for the routes that
//! allocate attestation nonces or run chain verification (RSA-4096 and P-384 checks).
//!
//! The client IP is the TCP peer address. Behind a reverse proxy, set
//! `HD_TRUSTED_PROXY_HOPS=n` to take the n-th address from the right of `X-Forwarded-For`
//! (the one appended by the outermost trusted proxy). With the default of 0 the header is
//! ignored, so a client cannot choose its own bucket by forging it.
//!
//! IPs are held only in the limiter's memory and are never logged.

use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use std::num::NonZeroU32;
use std::sync::Arc;
use std::time::Duration;

use axum::extract::{ConnectInfo, Request, State};
use axum::http::{HeaderMap, HeaderValue, StatusCode};
use axum::middleware::Next;
use axum::response::{IntoResponse, Response};
use governor::clock::{Clock, DefaultClock};
use governor::{DefaultKeyedRateLimiter, Quota, RateLimiter};

use super::error::ApiError;
use super::App;

pub struct RateLimiters {
    general: DefaultKeyedRateLimiter<IpAddr>,
    attest: DefaultKeyedRateLimiter<IpAddr>,
    clock: DefaultClock,
    trusted_proxy_hops: usize,
}

fn quota(per_min: u32, burst: u32) -> Quota {
    let per_min = NonZeroU32::new(per_min).unwrap_or(NonZeroU32::MIN);
    let burst = NonZeroU32::new(burst).unwrap_or(NonZeroU32::MIN);
    Quota::per_minute(per_min).allow_burst(burst)
}

impl RateLimiters {
    pub fn new(per_min: u32, burst: u32, attest_per_min: u32, attest_burst: u32, trusted_proxy_hops: usize) -> Self {
        Self {
            general: RateLimiter::keyed(quota(per_min, burst)),
            attest: RateLimiter::keyed(quota(attest_per_min, attest_burst)),
            clock: DefaultClock::default(),
            trusted_proxy_hops,
        }
    }

    fn check(&self, limiter: &DefaultKeyedRateLimiter<IpAddr>, ip: IpAddr) -> Result<(), Duration> {
        limiter.check_key(&ip).map_err(|not_until| not_until.wait_time_from(self.clock.now()))
    }

    /// Drops state for idle clients. Called periodically by the maintenance task.
    pub fn housekeeping(&self) {
        self.general.retain_recent();
        self.attest.retain_recent();
        self.general.shrink_to_fit();
        self.attest.shrink_to_fit();
    }

    pub fn client_ip(&self, peer: Option<SocketAddr>, headers: &HeaderMap) -> IpAddr {
        let peer_ip = peer.map(|p| p.ip()).unwrap_or(IpAddr::V4(Ipv4Addr::UNSPECIFIED));
        if self.trusted_proxy_hops == 0 {
            return peer_ip;
        }
        let Some(xff) = headers.get("x-forwarded-for").and_then(|v| v.to_str().ok()) else {
            return peer_ip;
        };
        let hops: Vec<&str> = xff.split(',').map(str::trim).collect();
        hops.len()
            .checked_sub(self.trusted_proxy_hops)
            .and_then(|i| hops.get(i))
            .and_then(|s| s.parse::<IpAddr>().ok())
            .unwrap_or(peer_ip)
    }
}

fn too_many(wait: Duration) -> Response {
    let mut resp = ApiError::new(StatusCode::TOO_MANY_REQUESTS, "rate_limited", "too many requests").into_response();
    let secs = wait.as_secs().saturating_add(1);
    if let Ok(v) = HeaderValue::from_str(&secs.to_string()) {
        resp.headers_mut().insert("retry-after", v);
    }
    resp
}

async fn limit(
    app: &App,
    which: fn(&RateLimiters) -> &DefaultKeyedRateLimiter<IpAddr>,
    req: Request,
    next: Next,
) -> Response {
    let peer = req.extensions().get::<ConnectInfo<SocketAddr>>().map(|c| c.0);
    let ip = app.limits.client_ip(peer, req.headers());
    match app.limits.check(which(&app.limits), ip) {
        Ok(()) => next.run(req).await,
        Err(wait) => too_many(wait),
    }
}

pub async fn general_limit(State(app): State<Arc<App>>, req: Request, next: Next) -> Response {
    limit(&app, |l| &l.general, req, next).await
}

pub async fn attest_limit(State(app): State<Arc<App>>, req: Request, next: Next) -> Response {
    limit(&app, |l| &l.attest, req, next).await
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn burst_then_block_per_ip() {
        let l = RateLimiters::new(60, 2, 1, 1, 0);
        let a: IpAddr = "10.0.0.1".parse().unwrap();
        let b: IpAddr = "10.0.0.2".parse().unwrap();
        assert!(l.check(&l.general, a).is_ok());
        assert!(l.check(&l.general, a).is_ok());
        let wait = l.check(&l.general, a).unwrap_err();
        assert!(wait <= Duration::from_secs(1));
        assert!(l.check(&l.general, b).is_ok());
        assert!(l.check(&l.attest, a).is_ok());
        assert!(l.check(&l.attest, a).is_err());
    }

    #[test]
    fn forwarded_for_only_when_trusted() {
        let mut h = HeaderMap::new();
        h.insert("x-forwarded-for", HeaderValue::from_static("1.1.1.1, 2.2.2.2, 3.3.3.3"));
        let peer: SocketAddr = "9.9.9.9:1234".parse().unwrap();
        let untrusted = RateLimiters::new(1, 1, 1, 1, 0);
        assert_eq!(untrusted.client_ip(Some(peer), &h), "9.9.9.9".parse::<IpAddr>().unwrap());
        let one_hop = RateLimiters::new(1, 1, 1, 1, 1);
        assert_eq!(one_hop.client_ip(Some(peer), &h), "3.3.3.3".parse::<IpAddr>().unwrap());
        let two_hops = RateLimiters::new(1, 1, 1, 1, 2);
        assert_eq!(two_hops.client_ip(Some(peer), &h), "2.2.2.2".parse::<IpAddr>().unwrap());
        let too_many_hops = RateLimiters::new(1, 1, 1, 1, 9);
        assert_eq!(too_many_hops.client_ip(Some(peer), &h), "9.9.9.9".parse::<IpAddr>().unwrap());
        h.insert("x-forwarded-for", HeaderValue::from_static("garbage"));
        assert_eq!(one_hop.client_ip(Some(peer), &h), "9.9.9.9".parse::<IpAddr>().unwrap());
    }
}
