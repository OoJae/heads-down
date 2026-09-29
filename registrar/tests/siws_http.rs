//! Sign-In-With-Solana over HTTP, against the real router.

mod common;

use axum::http::{Method, StatusCode};
use common::*;
use hd_registrar::clock::rfc3339;
use serde_json::{json, Value};

async fn verify(h: &Harness, body: Value) -> (StatusCode, Value) {
    let (s, b, _) = h.call(Method::POST, "/siws/verify", Some(body), None).await;
    (s, b)
}

#[tokio::test]
async fn nonce_endpoint_returns_fields_to_sign() {
    let h = Harness::new(Opts::default());
    let n = h.new_nonce().await;
    assert_eq!(n["nonce"].as_str().unwrap().len(), 32);
    assert_eq!(n["domain"], "headsdown.xyz");
    assert_eq!(n["uri"], "https://headsdown.xyz");
    assert_eq!(n["version"], "1");
    assert_eq!(n["chain_ids"], json!(["solana:mainnet"]));
    assert_eq!(n["issued_at"], rfc3339(T0));
    assert_eq!(n["expiration_time"], rfc3339(T0 + 600));
    let other = h.new_nonce().await;
    assert_ne!(n["nonce"], other["nonce"]);
}

#[tokio::test]
async fn valid_sign_in_returns_a_session_bound_to_the_address() {
    let h = Harness::new(Opts::default());
    let w = wallet(1);
    let n = h.new_nonce().await;
    let (s, body) = verify(&h, Harness::signed_body(&h.siws_message(&n, &w), &w)).await;
    assert_eq!(s, StatusCode::OK, "{body}");
    assert_eq!(body["address"], address(&w));
    assert_eq!(body["chain_id"], "solana:mainnet");
    let token = body["session_token"].as_str().unwrap();
    let claims = h.app.session_key.verify(token, T0 + 1).unwrap();
    assert_eq!(claims.sub, address(&w));
    assert_eq!(claims.exp, T0 + 3600);
}

#[tokio::test]
async fn reused_nonce_is_rejected() {
    let h = Harness::new(Opts::default());
    let w = wallet(1);
    let n = h.new_nonce().await;
    let body = Harness::signed_body(&h.siws_message(&n, &w), &w);
    assert_eq!(verify(&h, body.clone()).await.0, StatusCode::OK);
    let (s, b) = verify(&h, body).await;
    assert_eq!((s, b["error"].as_str().unwrap()), (StatusCode::UNAUTHORIZED, "nonce_unknown_or_used"));
}

#[tokio::test]
async fn concurrent_replays_have_exactly_one_winner() {
    let h = std::sync::Arc::new(Harness::new(Opts::default()));
    let w = wallet(1);
    let n = h.new_nonce().await;
    let body = Harness::signed_body(&h.siws_message(&n, &w), &w);
    let tasks: Vec<_> = (0..16)
        .map(|_| {
            let (h, body) = (std::sync::Arc::clone(&h), body.clone());
            tokio::spawn(async move { verify(&h, body).await.0 })
        })
        .collect();
    let mut ok = 0;
    for t in tasks {
        if t.await.unwrap() == StatusCode::OK {
            ok += 1;
        }
    }
    assert_eq!(ok, 1);
}

#[tokio::test]
async fn expired_nonce_is_rejected() {
    let h = Harness::new(Opts::default());
    let w = wallet(1);
    let n = h.new_nonce().await;
    // A message that is itself still valid (expires at T0+650, allowed by the 60 s skew)
    // after the 600 s nonce has expired: only the nonce check can reject it.
    let mut msg = h.siws_message(&n, &w);
    msg.expiration_time = Some(rfc3339(T0 + 650));
    h.clock.set(T0 + 605);
    let (s, b) = verify(&h, Harness::signed_body(&msg, &w)).await;
    assert_eq!((s, b["error"].as_str().unwrap()), (StatusCode::UNAUTHORIZED, "nonce_expired"));
}

#[tokio::test]
async fn expired_message_is_rejected() {
    let h = Harness::new(Opts::default());
    let w = wallet(1);
    let n = h.new_nonce().await;
    h.clock.set(T0 + 600);
    let (s, b) = verify(&h, Harness::signed_body(&h.siws_message(&n, &w), &w)).await;
    assert_eq!((s, b["error"].as_str().unwrap()), (StatusCode::UNAUTHORIZED, "message_expired"));
}

#[tokio::test]
async fn wrong_domain_is_rejected() {
    let h = Harness::new(Opts::default());
    let w = wallet(1);
    let n = h.new_nonce().await;
    let mut msg = h.siws_message(&n, &w);
    msg.domain = "headsdown.xyz.evil.example".into();
    let (s, b) = verify(&h, Harness::signed_body(&msg, &w)).await;
    assert_eq!((s, b["error"].as_str().unwrap()), (StatusCode::UNAUTHORIZED, "domain_mismatch"));
    let mut msg = h.siws_message(&n, &w);
    msg.uri = Some("https://evil.example".into());
    let (s, b) = verify(&h, Harness::signed_body(&msg, &w)).await;
    assert_eq!((s, b["error"].as_str().unwrap()), (StatusCode::UNAUTHORIZED, "uri_mismatch"));
}

#[tokio::test]
async fn wrong_chain_is_rejected() {
    let h = Harness::new(Opts::default());
    let w = wallet(1);
    let n = h.new_nonce().await;
    for chain in ["solana:devnet", "solana:testnet", "mainnet", "eip155:1"] {
        let mut msg = h.siws_message(&n, &w);
        msg.chain_id = Some(chain.into());
        let (s, b) = verify(&h, Harness::signed_body(&msg, &w)).await;
        assert_eq!((s, b["error"].as_str().unwrap()), (StatusCode::UNAUTHORIZED, "chain_not_allowed"), "{chain}");
    }
}

#[tokio::test]
async fn signature_by_another_key_is_rejected() {
    let h = Harness::new(Opts::default());
    let (victim, attacker) = (wallet(1), wallet(2));
    let n = h.new_nonce().await;
    // Message claims the victim's address, attacker signs.
    let msg = h.siws_message(&n, &victim);
    let (s, b) = verify(&h, Harness::signed_body(&msg, &attacker)).await;
    assert_eq!((s, b["error"].as_str().unwrap()), (StatusCode::UNAUTHORIZED, "signature_invalid"));
}

#[tokio::test]
async fn tampered_message_is_rejected_and_does_not_burn_the_nonce() {
    let h = Harness::new(Opts::default());
    let w = wallet(1);
    let n = h.new_nonce().await;
    let msg = h.siws_message(&n, &w);
    let good = Harness::signed_body(&msg, &w);

    // Change the statement after signing, keep the signature.
    let mut tampered_msg = msg.clone();
    tampered_msg.statement = Some("Send all my SOL.".into());
    let tampered = json!({
        "signed_message": b64(tampered_msg.to_text().as_bytes()),
        "signature": good["signature"],
    });
    let (s, b) = verify(&h, tampered).await;
    assert_eq!((s, b["error"].as_str().unwrap()), (StatusCode::UNAUTHORIZED, "signature_invalid"));

    // Non-canonical spelling of the same message (trailing newline) with a valid signature.
    let mut bytes = msg.to_text().into_bytes();
    bytes.push(b'\n');
    let sig = {
        use ed25519_dalek::Signer;
        w.sign(&bytes).to_bytes()
    };
    let (s, b) = verify(&h, json!({"signed_message": b64(&bytes), "signature": b64(&sig)})).await;
    assert_eq!((s, b["error"].as_str().unwrap()), (StatusCode::BAD_REQUEST, "message_malformed"));

    // The genuine sign-in still works: failed attempts never consumed the nonce.
    assert_eq!(verify(&h, good).await.0, StatusCode::OK);
}

#[tokio::test]
async fn missing_or_unissued_nonce_is_rejected() {
    let h = Harness::new(Opts::default());
    let w = wallet(1);
    let n = h.new_nonce().await;
    let mut msg = h.siws_message(&n, &w);
    msg.nonce = None;
    let (s, b) = verify(&h, Harness::signed_body(&msg, &w)).await;
    assert_eq!((s, b["error"].as_str().unwrap()), (StatusCode::UNAUTHORIZED, "nonce_missing"));

    // Well-formed but never issued by this server.
    let mut msg = h.siws_message(&n, &w);
    msg.nonce = Some("0123456789abcdef0123456789abcdef".into());
    let (s, b) = verify(&h, Harness::signed_body(&msg, &w)).await;
    assert_eq!((s, b["error"].as_str().unwrap()), (StatusCode::UNAUTHORIZED, "nonce_unknown_or_used"));

    // An attestation nonce cannot be used to sign in.
    let token = h.sign_in(&w).await;
    let (attest_nonce, _) = h.challenge(&token).await;
    let mut msg = h.siws_message(&n, &w);
    msg.nonce = Some(attest_nonce);
    let (s, b) = verify(&h, Harness::signed_body(&msg, &w)).await;
    assert_eq!((s, b["error"].as_str().unwrap()), (StatusCode::UNAUTHORIZED, "nonce_unknown_or_used"));
}

#[tokio::test]
async fn claimed_address_must_match() {
    let h = Harness::new(Opts::default());
    let w = wallet(1);
    let n = h.new_nonce().await;
    let mut body = Harness::signed_body(&h.siws_message(&n, &w), &w);
    body["address"] = json!(address(&wallet(2)));
    let (s, b) = verify(&h, body).await;
    assert_eq!((s, b["error"].as_str().unwrap()), (StatusCode::UNAUTHORIZED, "address_mismatch"));
}

#[tokio::test]
async fn malformed_requests_are_rejected_without_echo() {
    let h = Harness::new(Opts::default());
    let (s, b, _) = h.raw(Method::POST, "/siws/verify", b"{not json".to_vec(), None).await;
    assert_eq!((s, b["error"].as_str().unwrap()), (StatusCode::BAD_REQUEST, "invalid_json"));
    let (s, b) = verify(&h, json!({"signed_message": "!!!", "signature": "AA=="})).await;
    assert_eq!((s, b["error"].as_str().unwrap()), (StatusCode::BAD_REQUEST, "signed_message_malformed"));
    let (s, b) = verify(&h, json!({"signed_message": "AA==", "signature": "AA==", "extra": 1})).await;
    assert_eq!((s, b["error"].as_str().unwrap()), (StatusCode::BAD_REQUEST, "invalid_json"));
    let huge = "A".repeat(100_000);
    let (s, b) = verify(&h, json!({"signed_message": huge, "signature": "AA=="})).await;
    assert_eq!(s, StatusCode::PAYLOAD_TOO_LARGE, "{b}");
    assert!(!b.to_string().contains("AAAA"));
}
