//! HTTP API (axum).
//!
//! | Route | Auth | Purpose |
//! |---|---|---|
//! | `GET /healthz` | none | liveness + dependency status (not rate limited) |
//! | `GET /registrar` | none | public parameters: registrar key, program, policy, anchors |
//! | `POST /siws/nonce` | none | single-use SIWS nonce + the exact fields to sign |
//! | `POST /siws/verify` | none | verify a signed SIWS message, return a session token |
//! | `GET /attest/challenge` | session | nonce bound to the session's authority + challenge |
//! | `POST /attest` | session | verify a Key Attestation chain, return the Ed25519 voucher |
//! | `GET /attest/log` | none | the public transparency log, paged |
//! | `GET /attest/voucher` | none | latest logged voucher for (authority, p256) |

mod attest;
pub mod error;
mod meta;
pub mod ratelimit;
mod siws;

use std::sync::Arc;
use std::time::Duration;

use axum::extract::{MatchedPath, Request};
use axum::http::header::{AUTHORIZATION, CACHE_CONTROL, X_CONTENT_TYPE_OPTIONS};
use axum::http::{HeaderValue, StatusCode};
use axum::routing::{get, post};
use axum::{middleware, Router};
use tokio::sync::Semaphore;
use tower::ServiceBuilder;
use tower_http::catch_panic::CatchPanicLayer;
use tower_http::limit::RequestBodyLimitLayer;
use tower_http::request_id::{MakeRequestUuid, PropagateRequestIdLayer, RequestId, SetRequestIdLayer};
use tower_http::sensitive_headers::SetSensitiveRequestHeadersLayer;
use tower_http::set_header::SetResponseHeaderLayer;
use tower_http::timeout::TimeoutLayer;
use tower_http::trace::{DefaultOnResponse, TraceLayer};
use tower_http::LatencyUnit;

use crate::attest::revocation::{RevocationList, StatusListProvider};
use crate::attest::{AttestationPolicy, TrustAnchors, Verifier};
use crate::clock::Clock;
use crate::config::{Config, NonceStoreConfig, Secrets, SlotConfig, StatusListConfig};
use crate::nonce::{MemoryNonceStore, NonceStore, SqliteNonceStore};
use crate::session::SessionKey;
use crate::siws::SiwsPolicy;
use crate::slot::SlotSource;
use crate::translog::TransparencyLog;
use crate::voucher::RegistrarKey;
use ratelimit::RateLimiters;

/// Everything a request handler needs. Built once at startup.
pub struct App {
    pub config: Config,
    pub clock: Arc<dyn Clock>,
    pub nonces: Arc<dyn NonceStore>,
    pub session_key: SessionKey,
    pub siws: SiwsPolicy,
    pub verifier: Verifier,
    pub status: StatusListProvider,
    pub slots: SlotSource,
    pub registrar: RegistrarKey,
    pub log: TransparencyLog,
    pub attest_permits: Semaphore,
    pub limits: RateLimiters,
}

#[derive(Debug, thiserror::Error)]
pub enum BuildError {
    #[error("nonce store: {0}")]
    Nonces(String),
    #[error("trust anchors: {0}")]
    Roots(String),
    #[error("status list: {0}")]
    Status(String),
    #[error("slot source: {0}")]
    Slots(String),
    #[error("transparency log: {0}")]
    Log(String),
}

/// Dependencies that production derives from the config and tests replace.
pub struct Overrides {
    pub anchors: Option<TrustAnchors>,
    pub status: Option<StatusListProvider>,
    pub slots: Option<SlotSource>,
    pub nonces: Option<Arc<dyn NonceStore>>,
}

impl App {
    pub fn build(config: Config, secrets: Secrets, clock: Arc<dyn Clock>, o: Overrides) -> Result<Self, BuildError> {
        let nonces: Arc<dyn NonceStore> = match o.nonces {
            Some(n) => n,
            None => match &config.nonce_store {
                NonceStoreConfig::Memory => Arc::new(MemoryNonceStore::new(config.max_outstanding_nonces)),
                NonceStoreConfig::Sqlite(path) => {
                    if let Some(parent) = path.parent().filter(|p| !p.as_os_str().is_empty()) {
                        std::fs::create_dir_all(parent).map_err(|e| BuildError::Nonces(e.to_string()))?;
                    }
                    Arc::new(
                        SqliteNonceStore::open(path, config.max_outstanding_nonces)
                            .map_err(|e| BuildError::Nonces(e.to_string()))?,
                    )
                }
            },
        };
        let anchors = match o.anchors {
            Some(a) => a,
            None => TrustAnchors::google().map_err(|e| BuildError::Roots(e.to_string()))?,
        };
        let status = match o.status {
            Some(s) => s,
            None => match &config.status_list {
                StatusListConfig::Url(url) => {
                    StatusListProvider::http(url.clone(), config.status_ttl_secs, config.status_max_stale_secs)
                        .map_err(|e| BuildError::Status(e.to_string()))?
                }
                StatusListConfig::File(path) => {
                    let bytes = std::fs::read(path).map_err(|e| BuildError::Status(e.to_string()))?;
                    let list = RevocationList::parse(&bytes, clock.now_unix(), path.display().to_string())
                        .map_err(|e| BuildError::Status(e.to_string()))?;
                    StatusListProvider::fixed(list)
                }
            },
        };
        let slots = match o.slots {
            Some(s) => s,
            None => match &config.slot {
                SlotConfig::Rpc(url) => SlotSource::rpc(url.clone()).map_err(|e| BuildError::Slots(e.to_string()))?,
                SlotConfig::Fixed(s) => SlotSource::Fixed(*s),
            },
        };
        let log = TransparencyLog::open(&config.transparency_log).map_err(|e| BuildError::Log(e.to_string()))?;

        let mut siws = SiwsPolicy::new(config.siws_domain.clone(), config.siws_chains.clone());
        siws.uri = config.siws_uri.clone();
        siws.nonce_ttl_secs = config.nonce_ttl_secs;

        let verifier = Verifier {
            anchors,
            policy: AttestationPolicy {
                package_name: config.app_package.clone(),
                release_digests: config.release_digests.clone(),
                debug_digests: config.debug_digests.clone(),
                software_keys: config.software_keys,
                unlocked_devices: config.unlocked_devices,
            },
        };
        let limits = RateLimiters::new(
            config.rate_per_min,
            config.rate_burst,
            config.attest_rate_per_min,
            config.attest_rate_burst,
            config.trusted_proxy_hops,
            config.trust_real_ip,
        );
        Ok(Self {
            attest_permits: Semaphore::new(config.max_concurrent_attest),
            clock,
            nonces,
            session_key: secrets.session_key,
            siws,
            verifier,
            status,
            slots,
            registrar: secrets.registrar_key,
            log,
            limits,
            config,
        })
    }

    pub fn now(&self) -> i64 {
        self.clock.now_unix()
    }
}

/// Runs a blocking closure (SQLite, chain verification) off the async executor.
pub(crate) async fn blocking<T: Send + 'static>(f: impl FnOnce() -> T + Send + 'static) -> Result<T, error::ApiError> {
    tokio::task::spawn_blocking(f).await.map_err(|_| error::ApiError::internal())
}

pub fn router(app: Arc<App>) -> Router {
    let body_limit = app.config.max_body_bytes;
    let timeout = Duration::from_secs(app.config.request_timeout_secs);

    let attest_routes = Router::new()
        .route("/attest/challenge", get(attest::challenge))
        .route("/attest", post(attest::attest))
        .route_layer(middleware::from_fn_with_state(Arc::clone(&app), ratelimit::attest_limit));

    Router::new()
        .route("/registrar", get(meta::info))
        .route("/siws/nonce", post(siws::nonce))
        .route("/siws/verify", post(siws::verify))
        .route("/attest/log", get(attest::log))
        .route("/attest/voucher", get(attest::voucher))
        .merge(attest_routes)
        .route_layer(middleware::from_fn_with_state(Arc::clone(&app), ratelimit::general_limit))
        .route("/healthz", get(meta::healthz))
        .fallback(meta::not_found)
        .layer(
            ServiceBuilder::new()
                .layer(SetSensitiveRequestHeadersLayer::new([AUTHORIZATION]))
                .layer(SetRequestIdLayer::x_request_id(MakeRequestUuid))
                .layer(
                    TraceLayer::new_for_http()
                        .make_span_with(|req: &Request| {
                            // Only the route template and a request id: no query string, no
                            // headers, no client address.
                            let route = req.extensions().get::<MatchedPath>().map_or("unmatched", |m| m.as_str());
                            let id = req
                                .extensions()
                                .get::<RequestId>()
                                .and_then(|r| r.header_value().to_str().ok())
                                .unwrap_or("-");
                            tracing::info_span!("request", method = %req.method(), route, request_id = id)
                        })
                        .on_request(())
                        .on_response(
                            DefaultOnResponse::new().level(tracing::Level::INFO).latency_unit(LatencyUnit::Millis),
                        ),
                )
                .layer(PropagateRequestIdLayer::x_request_id())
                .layer(SetResponseHeaderLayer::overriding(CACHE_CONTROL, HeaderValue::from_static("no-store")))
                .layer(SetResponseHeaderLayer::overriding(X_CONTENT_TYPE_OPTIONS, HeaderValue::from_static("nosniff")))
                .layer(CatchPanicLayer::custom(|_| error::ApiError::internal().into_response_with_log()))
                .layer(RequestBodyLimitLayer::new(body_limit))
                .layer(TimeoutLayer::with_status_code(StatusCode::REQUEST_TIMEOUT, timeout))
                .layer(axum::extract::DefaultBodyLimit::max(body_limit)),
        )
        .with_state(app)
}

impl error::ApiError {
    fn into_response_with_log(self) -> axum::response::Response {
        tracing::error!("handler panicked");
        axum::response::IntoResponse::into_response(self)
    }
}

/// Periodic housekeeping: purge expired nonces, trim rate-limiter state, keep the status list
/// warm so no request pays for a refresh.
pub fn spawn_maintenance(app: Arc<App>) -> tokio::task::JoinHandle<()> {
    tokio::spawn(async move {
        let mut tick = tokio::time::interval(Duration::from_secs(60));
        loop {
            tick.tick().await;
            let now = app.now();
            let nonces = Arc::clone(&app.nonces);
            match tokio::task::spawn_blocking(move || nonces.purge_expired(now)).await {
                Ok(Ok(n)) if n > 0 => tracing::debug!(purged = n, "expired nonces purged"),
                Ok(Err(e)) => tracing::warn!(error = %e, "nonce purge failed"),
                _ => {}
            }
            app.limits.housekeeping();
            if let Err(e) = app.status.current(now).await {
                tracing::warn!(error = %e, "status list refresh failed");
            }
        }
    })
}
