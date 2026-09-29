//! `GET /attest/challenge`, `POST /attest`, and the public log endpoints.

use std::sync::Arc;

use axum::extract::rejection::JsonRejection;
use axum::extract::{Query, State};
use axum::http::{HeaderMap, StatusCode};
use axum::Json;
use serde::{Deserialize, Serialize};

use super::error::ApiError;
use super::{blocking, App};
use crate::attest::chain::{MAX_CERT_BYTES, MAX_CHAIN_LEN};
use crate::attest::{challenge as derive_challenge, AttestationSummary};
use crate::clock::rfc3339;
use crate::nonce::{self, NoncePurpose, NonceRecord, NONCE_BYTES};
use crate::session::SessionClaims;
use crate::translog::{LogEntry, NewEntry};
use crate::util::{b64_decode, b64_encode, decode_address, decode_hex_exact, encode_address};
use crate::voucher::{Voucher, ED25519_PROGRAM_ID};

fn bearer(headers: &HeaderMap) -> Option<&str> {
    let v = headers.get(axum::http::header::AUTHORIZATION)?.to_str().ok()?;
    v.strip_prefix("Bearer ").map(str::trim)
}

fn session(app: &App, token: Option<&str>) -> Result<SessionClaims, ApiError> {
    let token =
        token.ok_or_else(|| ApiError::new(StatusCode::UNAUTHORIZED, "session_missing", "session token required"))?;
    Ok(app.session_key.verify(token, app.now())?)
}

#[derive(Serialize)]
pub struct ChallengeResponse {
    pub authority: String,
    /// Hex, 16 bytes. Send it back in `POST /attest`.
    pub nonce: String,
    /// Hex, 32 bytes: pass these bytes to `KeyGenParameterSpec.Builder.setAttestationChallenge`.
    pub challenge: String,
    pub challenge_base64: String,
    pub expires_at: String,
}

pub async fn challenge(State(app): State<Arc<App>>, headers: HeaderMap) -> Result<Json<ChallengeResponse>, ApiError> {
    let claims = session(&app, bearer(&headers))?;
    let authority = decode_address(&claims.sub).ok_or_else(ApiError::internal)?;
    let now = app.now();
    let ttl = app.config.nonce_ttl_secs;
    let store = Arc::clone(&app.nonces);
    let subject = claims.sub.clone();
    let (nonce_hex, expires_at) =
        blocking(move || nonce::issue(store.as_ref(), NoncePurpose::Attest, Some(subject), now, ttl)).await??;
    let nonce_bytes: [u8; NONCE_BYTES] = decode_hex_exact(&nonce_hex).ok_or_else(ApiError::internal)?;
    let c = derive_challenge(&authority, &nonce_bytes);
    Ok(Json(ChallengeResponse {
        authority: claims.sub,
        nonce: nonce_hex,
        challenge: hex::encode(c),
        challenge_base64: b64_encode(&c),
        expires_at: rfc3339(expires_at),
    }))
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct AttestRequest {
    /// Base58 wallet address; must equal the session's subject.
    pub authority: String,
    /// Hex of the 33-byte SEC1 compressed P-256 key.
    pub p256_pubkey_compressed: String,
    /// Base64 DER certificates, leaf first (`KeyStore.getCertificateChain`).
    pub attestation_chain: Vec<String>,
    /// Hex nonce from `GET /attest/challenge`.
    pub nonce: String,
    /// Session token (may instead be sent as `Authorization: Bearer`).
    #[serde(default)]
    pub session_token: Option<String>,
}

#[derive(Serialize)]
pub struct Ed25519Instruction {
    pub program_id: &'static str,
    pub accounts: Vec<()>,
    pub data_base64: String,
    pub data_hex: String,
}

#[derive(Serialize)]
pub struct AttestResponse {
    pub level: u8,
    pub expiry_slot: u64,
    pub issued_slot: u64,
    pub program_id: String,
    pub authority: String,
    pub p256_pubkey: String,
    pub registrar: String,
    /// Hex of the 111-byte HDreg preimage (the Ed25519-signed message).
    pub message: String,
    pub signature: String,
    pub ed25519_instruction: Ed25519Instruction,
    pub attestation: AttestationSummary,
    pub log_index: u64,
    pub entry_hash: String,
}

pub async fn attest(
    State(app): State<Arc<App>>,
    headers: HeaderMap,
    body: Result<Json<AttestRequest>, JsonRejection>,
) -> Result<Json<AttestResponse>, ApiError> {
    let Json(req) = body?;

    // 1. Session, bound to the authority.
    let token = match (req.session_token.as_deref(), bearer(&headers)) {
        (Some(a), Some(b)) if a != b => {
            return Err(ApiError::bad_request("session_conflict", "body and header carry different session tokens"))
        }
        (Some(t), _) | (None, Some(t)) => Some(t),
        (None, None) => None,
    };
    let claims = session(&app, token)?;
    let authority = decode_address(&req.authority)
        .ok_or_else(|| ApiError::bad_request("authority_malformed", "authority must be a base58 Solana address"))?;
    if claims.sub != req.authority {
        return Err(ApiError::new(StatusCode::FORBIDDEN, "authority_mismatch", "session is for another authority"));
    }

    // 2. Input shapes.
    let pubkey: [u8; 33] = decode_hex_exact(&req.p256_pubkey_compressed)
        .filter(|k| matches!(k[0], 0x02 | 0x03))
        .filter(|k| p256::PublicKey::from_sec1_bytes(k).is_ok())
        .ok_or_else(|| {
            ApiError::bad_request(
                "pubkey_malformed",
                "p256_pubkey_compressed must be a 33-byte SEC1 compressed P-256 point",
            )
        })?;
    let nonce_bytes: [u8; NONCE_BYTES] =
        decode_hex_exact(&req.nonce).filter(|_| nonce::is_well_formed(&req.nonce)).ok_or_else(|| {
            ApiError::bad_request("nonce_malformed", "nonce must be the 32-hex-char value from /attest/challenge")
        })?;
    if req.attestation_chain.is_empty() || req.attestation_chain.len() > MAX_CHAIN_LEN {
        return Err(ApiError::bad_request("chain_length", "attestation_chain must have 2..=6 certificates"));
    }
    let mut chain_der = Vec::with_capacity(req.attestation_chain.len());
    for c in &req.attestation_chain {
        let der = b64_decode(c).filter(|d| !d.is_empty() && d.len() <= MAX_CERT_BYTES).ok_or_else(|| {
            ApiError::bad_request("chain_malformed", "each certificate must be base64 DER, at most 16 KiB")
        })?;
        chain_der.push(der);
    }
    let expected_challenge = derive_challenge(&authority, &nonce_bytes);
    let now = app.now();

    // 3. Dependencies that can be unavailable: fail before anything is consumed.
    let revocations =
        app.status.current(now).await.map_err(|_| {
            ApiError::unavailable("status_list_unavailable", "revocation status unavailable; retry later")
        })?;
    let slot = app
        .slots
        .current_slot()
        .await
        .map_err(|_| ApiError::unavailable("slot_unavailable", "cluster slot unavailable; retry later"))?;
    let expiry_slot = slot.checked_add(app.config.voucher_ttl_slots).ok_or_else(ApiError::internal)?;

    // 4. Cryptographic verification, CPU-bound and bounded in concurrency.
    let _permit = app.attest_permits.acquire().await.map_err(|_| ApiError::internal())?;
    let app2 = Arc::clone(&app);
    let chain_for_verify = chain_der.clone();
    let summary =
        blocking(move || app2.verifier.verify(&chain_for_verify, &pubkey, &expected_challenge, &revocations, now))
            .await??;
    drop(_permit);

    // 5. Consume the nonce (atomic, bound to this authority). Only now is it spent.
    let store = Arc::clone(&app.nonces);
    let (n, subject) = (req.nonce.clone(), req.authority.clone());
    blocking(move || nonce::consume(store.as_ref(), &n, NoncePurpose::Attest, Some(&subject), now)).await??;

    // 6. Sign, log (fsync), respond.
    let signed = app.registrar.sign(Voucher {
        program_id: app.config.program_id,
        authority,
        p256_pubkey: pubkey,
        level: summary.level,
        expiry_slot,
    });
    let attestation_json = serde_json::to_value(&summary).map_err(|_| ApiError::internal())?;
    let entry = match app
        .log
        .append(NewEntry {
            issued_at: rfc3339(now),
            issued_slot: slot,
            message: signed.message,
            signature: signed.signature,
            registrar: signed.registrar,
            nonce: nonce_bytes,
            attestation: attestation_json,
            chain_der,
        })
        .await
    {
        Ok(e) => e,
        Err(e) => {
            // Nothing was handed out. Give the nonce back so the client can retry with the same
            // key (its challenge is fixed in the key's certificate).
            tracing::error!(error = %e, "transparency log append failed; voucher withheld");
            let store = Arc::clone(&app.nonces);
            let (n, subject) = (req.nonce.clone(), req.authority.clone());
            let restore = NonceRecord {
                purpose: NoncePurpose::Attest,
                subject: Some(subject),
                expires_at: now.saturating_add(app.config.nonce_ttl_secs),
            };
            let _ = blocking(move || store.insert(&n, &restore, now)).await;
            return Err(ApiError::internal());
        }
    };
    tracing::info!(
        log_index = entry.index,
        level = summary.level,
        provisioning = %summary.provisioning,
        "attestation voucher issued"
    );
    let data = signed.instruction_data();
    Ok(Json(AttestResponse {
        level: summary.level,
        expiry_slot,
        issued_slot: slot,
        program_id: encode_address(&app.config.program_id),
        authority: req.authority,
        p256_pubkey: hex::encode(pubkey),
        registrar: encode_address(&signed.registrar),
        message: hex::encode(signed.message),
        signature: hex::encode(signed.signature),
        ed25519_instruction: Ed25519Instruction {
            program_id: ED25519_PROGRAM_ID,
            accounts: Vec::new(),
            data_base64: b64_encode(&data),
            data_hex: hex::encode(&data),
        },
        attestation: summary,
        log_index: entry.index,
        entry_hash: entry.entry_hash,
    }))
}

#[derive(Deserialize)]
pub struct LogQuery {
    #[serde(default)]
    pub from: u64,
    #[serde(default)]
    pub limit: Option<usize>,
}

#[derive(Serialize)]
pub struct LogPage {
    pub size: u64,
    pub head: String,
    pub entries: Vec<LogEntry>,
}

pub async fn log(State(app): State<Arc<App>>, Query(q): Query<LogQuery>) -> Result<Json<LogPage>, ApiError> {
    let limit = q.limit.unwrap_or(50).clamp(1, 100);
    let (size, head) = app.log.head().await;
    let entries = app.log.read_range(q.from, limit).await.map_err(|_| ApiError::internal())?;
    Ok(Json(LogPage { size, head, entries }))
}

#[derive(Deserialize)]
pub struct VoucherQuery {
    pub authority: String,
    pub p256: String,
}

pub async fn voucher(State(app): State<Arc<App>>, Query(q): Query<VoucherQuery>) -> Result<Json<LogEntry>, ApiError> {
    if decode_address(&q.authority).is_none() || decode_hex_exact::<33>(&q.p256).is_none() {
        return Err(ApiError::bad_request("query_malformed", "authority (base58) and p256 (hex, 33 bytes) required"));
    }
    match app.log.find(&q.authority, &q.p256.to_ascii_lowercase()).await {
        Ok(Some(e)) => Ok(Json(e)),
        Ok(None) => Err(ApiError::new(StatusCode::NOT_FOUND, "not_found", "no voucher logged for this key")),
        Err(_) => Err(ApiError::internal()),
    }
}
