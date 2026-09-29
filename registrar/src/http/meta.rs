//! `/healthz`, `/registrar`, and the JSON 404.

use std::sync::Arc;

use axum::extract::State;
use axum::http::StatusCode;
use axum::Json;
use serde::Serialize;

use super::error::ApiError;
use super::App;
use crate::attest::DowngradePolicy;
use crate::util::encode_address;

#[derive(Serialize)]
pub struct StatusListHealth {
    pub source: String,
    pub entries: usize,
    pub age_secs: Option<i64>,
}

#[derive(Serialize)]
pub struct Health {
    pub status: &'static str,
    pub version: &'static str,
    pub nonce_store: &'static str,
    pub transparency_log_size: u64,
    pub status_list: Option<StatusListHealth>,
}

/// Liveness plus a cheap view of dependencies. Never triggers network calls.
pub async fn healthz(State(app): State<Arc<App>>) -> (StatusCode, Json<Health>) {
    let store = Arc::clone(&app.nonces);
    let nonce_ok = tokio::task::spawn_blocking(move || store.len().is_ok()).await.unwrap_or(false);
    let now = app.now();
    let status_list = app.status.cached().await.map(|l| StatusListHealth {
        source: l.source.clone(),
        entries: l.len(),
        age_secs: (l.fetched_at > 0).then(|| now.saturating_sub(l.fetched_at)),
    });
    let code = if nonce_ok { StatusCode::OK } else { StatusCode::SERVICE_UNAVAILABLE };
    (
        code,
        Json(Health {
            status: if nonce_ok { "ok" } else { "degraded" },
            version: env!("CARGO_PKG_VERSION"),
            nonce_store: if nonce_ok { "ok" } else { "error" },
            transparency_log_size: app.log.len().await,
            status_list,
        }),
    )
}

#[derive(Serialize)]
pub struct Anchor {
    pub name: String,
    pub spki_sha256: String,
}

#[derive(Serialize)]
pub struct Info {
    pub registrar: String,
    pub program_id: String,
    pub siws_domain: String,
    pub siws_uri: String,
    pub siws_chains: Vec<String>,
    pub app_package: String,
    pub app_release_cert_sha256: Vec<String>,
    pub debug_signers_accepted: bool,
    pub software_keys: DowngradePolicy,
    pub unlocked_devices: DowngradePolicy,
    pub voucher_ttl_slots: u64,
    pub challenge: &'static str,
    pub voucher_preimage: &'static str,
    pub trust_anchors: Vec<Anchor>,
}

pub async fn info(State(app): State<Arc<App>>) -> Json<Info> {
    let c = &app.config;
    Json(Info {
        registrar: encode_address(&app.registrar.pubkey()),
        program_id: encode_address(&c.program_id),
        siws_domain: c.siws_domain.clone(),
        siws_uri: c.siws_uri.clone(),
        siws_chains: c.siws_chains.clone(),
        app_package: c.app_package.clone(),
        app_release_cert_sha256: c.release_digests.iter().map(hex::encode).collect(),
        debug_signers_accepted: !c.debug_digests.is_empty(),
        software_keys: c.software_keys,
        unlocked_devices: c.unlocked_devices,
        voucher_ttl_slots: c.voucher_ttl_slots,
        challenge: "SHA-256(\"HDattest\" || authority[32] || nonce[16])",
        voucher_preimage:
            "\"HDreg\"[5] || program_id[32] || authority[32] || p256_compressed[33] || level u8 || expiry_slot u64 LE",
        trust_anchors: app
            .verifier
            .anchors
            .iter()
            .map(|a| Anchor { name: a.name.clone(), spki_sha256: a.spki_sha256.clone() })
            .collect(),
    })
}

pub async fn not_found() -> ApiError {
    ApiError::new(StatusCode::NOT_FOUND, "not_found", "no such route")
}
