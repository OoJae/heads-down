//! `POST /siws/nonce` and `POST /siws/verify`.

use std::sync::Arc;

use axum::extract::State;
use axum::Json;
use serde::{Deserialize, Serialize};

use super::error::ApiError;
use super::{blocking, App};
use crate::clock::rfc3339;
use crate::nonce::{self, NoncePurpose};
use crate::session::SessionClaims;
use crate::siws::verify_signed_message;
use crate::util::{b64_decode, random_array};

/// Everything the app puts into MWA's `SignInWithSolana.Payload`, verbatim.
#[derive(Serialize)]
pub struct NonceResponse {
    pub nonce: String,
    pub domain: String,
    pub uri: String,
    pub version: &'static str,
    pub chain_ids: Vec<String>,
    pub statement: String,
    pub issued_at: String,
    pub expiration_time: String,
}

pub async fn nonce(State(app): State<Arc<App>>) -> Result<Json<NonceResponse>, ApiError> {
    let now = app.now();
    let ttl = app.config.nonce_ttl_secs;
    let store = Arc::clone(&app.nonces);
    let (nonce, expires_at) =
        blocking(move || nonce::issue(store.as_ref(), NoncePurpose::Siws, None, now, ttl)).await??;
    Ok(Json(NonceResponse {
        nonce,
        domain: app.siws.domain.clone(),
        uri: app.siws.uri.clone(),
        version: "1",
        chain_ids: app.siws.chains.clone(),
        statement: app.config.siws_statement.clone(),
        issued_at: rfc3339(now),
        expiration_time: rfc3339(expires_at),
    }))
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct VerifyRequest {
    /// Base64 of the exact bytes the wallet signed (MWA `SignInResult.signedMessage`).
    pub signed_message: String,
    /// Base64 of the 64-byte Ed25519 signature.
    pub signature: String,
    /// Optional base58 address the client expects; must match the message.
    #[serde(default)]
    pub address: Option<String>,
}

#[derive(Serialize)]
pub struct VerifyResponse {
    pub session_token: String,
    pub address: String,
    pub chain_id: String,
    pub expires_at: String,
}

pub async fn verify(
    State(app): State<Arc<App>>,
    body: Result<Json<VerifyRequest>, axum::extract::rejection::JsonRejection>,
) -> Result<Json<VerifyResponse>, ApiError> {
    let Json(req) = body?;
    // Base64 of a 2 KiB message is < 3 KiB; anything longer is rejected before decoding.
    if req.signed_message.len() > 4096 || req.signature.len() > 128 {
        return Err(ApiError::bad_request("field_too_large", "signed_message or signature too large"));
    }
    let message = b64_decode(&req.signed_message)
        .ok_or_else(|| ApiError::bad_request("signed_message_malformed", "signed_message must be base64"))?;
    let signature = b64_decode(&req.signature)
        .ok_or_else(|| ApiError::bad_request("signature_malformed", "signature must be base64"))?;

    let now = app.now();
    // Every stateless check first: a forged request cannot burn a legitimate nonce.
    let verified = verify_signed_message(&app.siws, &message, &signature, req.address.as_deref(), now)?;

    // Then consume the nonce atomically: of two concurrent replays only one gets here.
    let store = Arc::clone(&app.nonces);
    let n = verified.nonce.clone();
    blocking(move || nonce::consume(store.as_ref(), &n, NoncePurpose::Siws, None, now)).await??;

    let jti: [u8; 8] = random_array().map_err(|_| ApiError::internal())?;
    let claims = SessionClaims {
        sub: verified.address.clone(),
        chain: verified.chain_id.clone(),
        iat: now,
        exp: now.saturating_add(app.config.session_ttl_secs),
        jti: hex::encode(jti),
    };
    let token = app.session_key.issue(&claims).map_err(|_| ApiError::internal())?;
    tracing::info!(jti = %claims.jti, "siws sign-in verified");
    Ok(Json(VerifyResponse {
        session_token: token,
        address: verified.address,
        chain_id: verified.chain_id,
        expires_at: rfc3339(claims.exp),
    }))
}
