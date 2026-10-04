//! `GET /attest/challenge` + `POST /attest` over HTTP, end to end: SIWS session, challenge
//! derivation, chain verification (synthetic RKP-shaped CA as the only anchor), policy,
//! Ed25519 voucher, precompile instruction bytes and the transparency log.

mod common;

use axum::http::{Method, StatusCode};
use common::*;
use ed25519_dalek::{Signature, VerifyingKey};
use hd_registrar::attest::DowngradePolicy;
use hd_registrar::voucher::{
    Voucher, HEADS_DOWN_PROGRAM_ID, IX_LEN, IX_MESSAGE_OFFSET, IX_PUBKEY_OFFSET, IX_SIGNATURE_OFFSET,
};
use serde_json::{json, Value};

async fn post_attest(h: &Harness, body: Value) -> (StatusCode, Value) {
    let (s, b, _) = h.call(Method::POST, "/attest", Some(body), None).await;
    (s, b)
}

fn error(b: &Value) -> &str {
    b["error"].as_str().unwrap_or("<none>")
}

/// Signs in, fetches a challenge, mints a chain with `edit(Kd)` applied. Returns
/// (authority, token, nonce, chain).
async fn setup(h: &Harness, seed: u8, org: &str, edit: impl FnOnce(&mut Kd)) -> (String, String, String, MintedChain) {
    let w = wallet(seed);
    let token = h.sign_in(&w).await;
    let (nonce, challenge) = h.challenge(&token).await;
    let mut kd = Kd::tee(&challenge);
    edit(&mut kd);
    let minted = h.ca.mint(&kd, org, &[0x0a, 0xbc], false);
    (address(&w), token, nonce, minted)
}

#[tokio::test]
async fn happy_path_tee_voucher_instruction_and_log() {
    let h = Harness::new(Opts::default());
    let (authority, token, nonce, m) = setup(&h, 1, "TEE", |_| {}).await;
    let (s, b) = post_attest(&h, attest_body(&authority, &m.pubkey, &m.chain, &nonce, &token)).await;
    assert_eq!(s, StatusCode::OK, "{b}");
    assert_eq!(b["level"], 1);
    assert_eq!(b["issued_slot"], SLOT);
    assert_eq!(b["expiry_slot"], SLOT + 6_480_000);
    assert_eq!(b["program_id"], HEADS_DOWN_PROGRAM_ID);
    assert_eq!(b["authority"], authority);
    assert_eq!(b["p256_pubkey"], hex::encode(m.pubkey));
    assert_eq!(b["attestation"]["provisioning"], "remote_key_provisioning");

    // The signed message is exactly the INTERFACE preimage.
    let message = hex::decode(b["message"].as_str().unwrap()).unwrap();
    let v = Voucher::from_preimage(&message).unwrap();
    assert_eq!(bs58::encode(v.program_id).into_string(), HEADS_DOWN_PROGRAM_ID);
    assert_eq!(bs58::encode(v.authority).into_string(), authority);
    assert_eq!(v.p256_pubkey, m.pubkey);
    assert_eq!((v.level, v.expiry_slot), (1, SLOT + 6_480_000));

    // The registrar key signed it, and the instruction carries it the way the precompile reads it.
    let registrar = hd_registrar::voucher::RegistrarKey::from_seed(&h.registrar_seed).pubkey();
    assert_eq!(b["registrar"], bs58::encode(registrar).into_string());
    let ix = &b["ed25519_instruction"];
    assert_eq!(ix["program_id"], "Ed25519SigVerify111111111111111111111111111");
    assert_eq!(ix["accounts"], json!([]));
    let data = hex::decode(ix["data_hex"].as_str().unwrap()).unwrap();
    assert_eq!(data.len(), IX_LEN);
    assert_eq!(&data[IX_PUBKEY_OFFSET..IX_PUBKEY_OFFSET + 32], &registrar);
    assert_eq!(&data[IX_MESSAGE_OFFSET..], &message[..]);
    let sig: [u8; 64] = data[IX_SIGNATURE_OFFSET..IX_SIGNATURE_OFFSET + 64].try_into().unwrap();
    VerifyingKey::from_bytes(&registrar).unwrap().verify_strict(&message, &Signature::from_bytes(&sig)).unwrap();
    assert_eq!(b64(&data), ix["data_base64"].as_str().unwrap());

    // Published in the transparency log, findable, and auditable offline.
    assert_eq!(b["log_index"], 0);
    let (s, page, _) = h.call(Method::GET, "/attest/log?from=0&limit=10", None, None).await;
    assert_eq!(s, StatusCode::OK);
    assert_eq!(page["size"], 1);
    assert_eq!(page["entries"][0]["entry_hash"], b["entry_hash"]);
    assert_eq!(page["entries"][0]["nonce"], nonce);
    assert_eq!(page["entries"][0]["chain"].as_array().unwrap().len(), 5);
    let path = format!("/attest/voucher?authority={authority}&p256={}", hex::encode(m.pubkey));
    let (s, found, _) = h.call(Method::GET, &path, None, None).await;
    assert_eq!((s, &found["signature"]), (StatusCode::OK, &b["signature"]));
    let entries = hd_registrar::translog::audit(&h.dir.path().join("attestations.jsonl")).unwrap();
    assert_eq!(entries.len(), 1);
}

#[tokio::test]
async fn strongbox_key_gets_level_2_and_session_may_be_a_header() {
    let h = Harness::new(Opts::default());
    let (authority, token, nonce, m) = setup(&h, 1, "StrongBox", |kd| {
        kd.attestation_level = 2;
        kd.keymint_level = 2;
    })
    .await;
    let mut body = attest_body(&authority, &m.pubkey, &m.chain, &nonce, &token);
    body.as_object_mut().unwrap().remove("session_token");
    let (s, b, _) = h.call(Method::POST, "/attest", Some(body), Some(&token)).await;
    assert_eq!(s, StatusCode::OK, "{b}");
    assert_eq!(b["level"], 2);
}

#[tokio::test]
async fn nonce_is_single_use() {
    let h = Harness::new(Opts::default());
    let (authority, token, nonce, m) = setup(&h, 1, "TEE", |_| {}).await;
    let body = attest_body(&authority, &m.pubkey, &m.chain, &nonce, &token);
    assert_eq!(post_attest(&h, body.clone()).await.0, StatusCode::OK);
    let (s, b) = post_attest(&h, body).await;
    assert_eq!((s, error(&b)), (StatusCode::UNAUTHORIZED, "nonce_unknown_or_used"));
}

#[tokio::test]
async fn expired_challenge_nonce_is_rejected() {
    let h = Harness::new(Opts::default());
    let (authority, _, nonce, m) = setup(&h, 1, "TEE", |_| {}).await;
    h.clock.advance(601);
    // A fresh session (the old one is still valid, but make the point explicit).
    let token = h.sign_in(&wallet(1)).await;
    let (s, b) = post_attest(&h, attest_body(&authority, &m.pubkey, &m.chain, &nonce, &token)).await;
    assert_eq!((s, error(&b)), (StatusCode::UNAUTHORIZED, "nonce_expired"));
}

#[tokio::test]
async fn wrong_challenge_is_rejected_without_consuming_the_nonce() {
    let h = Harness::new(Opts::default());
    let w = wallet(1);
    let token = h.sign_in(&w).await;
    let (nonce, challenge) = h.challenge(&token).await;
    let mut wrong = challenge.clone();
    wrong[31] ^= 1;
    let bad = h.ca.mint(&Kd::tee(&wrong), "TEE", &[0x0a, 0xbc], false);
    let (s, b) = post_attest(&h, attest_body(&address(&w), &bad.pubkey, &bad.chain, &nonce, &token)).await;
    assert_eq!((s, error(&b)), (StatusCode::UNPROCESSABLE_ENTITY, "challenge_mismatch"));

    // The genuine attempt still succeeds: verification failures never spend the nonce.
    let good = h.ca.mint(&Kd::tee(&challenge), "TEE", &[0x0a, 0xbc], false);
    let (s, b) = post_attest(&h, attest_body(&address(&w), &good.pubkey, &good.chain, &nonce, &token)).await;
    assert_eq!(s, StatusCode::OK, "{b}");
}

#[tokio::test]
async fn session_and_authority_binding() {
    let h = Harness::new(Opts::default());
    let (authority, token, nonce, m) = setup(&h, 1, "TEE", |_| {}).await;

    // No session.
    let mut body = attest_body(&authority, &m.pubkey, &m.chain, &nonce, &token);
    body.as_object_mut().unwrap().remove("session_token");
    let (s, b) = post_attest(&h, body).await;
    assert_eq!((s, error(&b)), (StatusCode::UNAUTHORIZED, "session_missing"));

    // Forged session.
    let (s, b) = post_attest(&h, attest_body(&authority, &m.pubkey, &m.chain, &nonce, "hds1.eyJ9.AAAA")).await;
    assert_eq!((s, error(&b)), (StatusCode::UNAUTHORIZED, "session_invalid"));

    // Mallory's valid session cannot attest for Alice's authority.
    let mallory = h.sign_in(&wallet(2)).await;
    let (s, b) = post_attest(&h, attest_body(&authority, &m.pubkey, &m.chain, &nonce, &mallory)).await;
    assert_eq!((s, error(&b)), (StatusCode::FORBIDDEN, "authority_mismatch"));

    // Header and body disagree.
    let (s, b, _) = h
        .call(
            Method::POST,
            "/attest",
            Some(attest_body(&authority, &m.pubkey, &m.chain, &nonce, &token)),
            Some(&mallory),
        )
        .await;
    assert_eq!((s, error(&b)), (StatusCode::BAD_REQUEST, "session_conflict"));

    // Expired session.
    h.clock.advance(3600);
    let (s, b) = post_attest(&h, attest_body(&authority, &m.pubkey, &m.chain, &nonce, &token)).await;
    assert_eq!((s, error(&b)), (StatusCode::UNAUTHORIZED, "session_expired"));
    let (s, b, _) = h.call(Method::GET, "/attest/challenge", None, Some(&token)).await;
    assert_eq!((s, error(&b)), (StatusCode::UNAUTHORIZED, "session_expired"));
}

#[tokio::test]
async fn nonce_issued_to_another_authority_is_refused() {
    let h = Harness::new(Opts::default());
    let (alice, mallory) = (wallet(1), wallet(2));
    let alice_token = h.sign_in(&alice).await;
    let (alice_nonce, _) = h.challenge(&alice_token).await;
    // Mallory builds a chain whose challenge matches (mallory, alice's nonce).
    let mallory_token = h.sign_in(&mallory).await;
    let mallory_bytes: [u8; 32] = *mallory.verifying_key().as_bytes();
    let nonce_bytes: [u8; 16] = hex::decode(&alice_nonce).unwrap().try_into().unwrap();
    let c = hd_registrar::attest::challenge(&mallory_bytes, &nonce_bytes);
    let m = h.ca.mint(&Kd::tee(&c), "TEE", &[0x0a, 0xbc], false);
    let (s, b) =
        post_attest(&h, attest_body(&address(&mallory), &m.pubkey, &m.chain, &alice_nonce, &mallory_token)).await;
    assert_eq!((s, error(&b)), (StatusCode::FORBIDDEN, "nonce_wrong_subject"));
}

#[tokio::test]
async fn wrong_package_and_wrong_signer_are_rejected() {
    let h = Harness::new(Opts::default());
    let (authority, token, nonce, m) = setup(&h, 1, "TEE", |kd| kd.package = "xyz.headsdown.evil".into()).await;
    let (s, b) = post_attest(&h, attest_body(&authority, &m.pubkey, &m.chain, &nonce, &token)).await;
    assert_eq!((s, error(&b)), (StatusCode::UNPROCESSABLE_ENTITY, "wrong_package"));

    let (authority, token, nonce, m) = setup(&h, 2, "TEE", |kd| kd.digests = vec![[0x77; 32]]).await;
    let (s, b) = post_attest(&h, attest_body(&authority, &m.pubkey, &m.chain, &nonce, &token)).await;
    assert_eq!((s, error(&b)), (StatusCode::UNPROCESSABLE_ENTITY, "wrong_signer"));

    // Our release key plus a foreign co-signer is not good enough either.
    let (authority, token, nonce, m) = setup(&h, 3, "TEE", |kd| kd.digests = vec![RELEASE_DIGEST, [0x77; 32]]).await;
    let (s, b) = post_attest(&h, attest_body(&authority, &m.pubkey, &m.chain, &nonce, &token)).await;
    assert_eq!((s, error(&b)), (StatusCode::UNPROCESSABLE_ENTITY, "wrong_signer"));

    // Debug-signed builds: rejected unless a debug digest is configured.
    let (authority, token, nonce, m) = setup(&h, 4, "TEE", |kd| kd.digests = vec![DEBUG_DIGEST]).await;
    let (s, b) = post_attest(&h, attest_body(&authority, &m.pubkey, &m.chain, &nonce, &token)).await;
    assert_eq!((s, error(&b)), (StatusCode::UNPROCESSABLE_ENTITY, "wrong_signer"));
    let dev = Harness::new(Opts { debug_digest: true, ..Opts::default() });
    let (authority, token, nonce, m) = setup(&dev, 4, "TEE", |kd| kd.digests = vec![DEBUG_DIGEST]).await;
    let (s, b) = post_attest(&dev, attest_body(&authority, &m.pubkey, &m.chain, &nonce, &token)).await;
    assert_eq!((s, &b["attestation"]["signer"]), (StatusCode::OK, &json!("debug")), "{b}");
}

#[tokio::test]
async fn software_level_key_is_rejected_or_level_0_per_policy() {
    let h = Harness::new(Opts::default());
    let (authority, token, nonce, m) = setup(&h, 1, "TEE", |kd| {
        kd.attestation_level = 0;
        kd.keymint_level = 0;
    })
    .await;
    let (s, b) = post_attest(&h, attest_body(&authority, &m.pubkey, &m.chain, &nonce, &token)).await;
    assert_eq!((s, error(&b)), (StatusCode::UNPROCESSABLE_ENTITY, "software_key"));

    let lenient = Harness::new(Opts { software_keys: DowngradePolicy::Level0, ..Opts::default() });
    let (authority, token, nonce, m) = setup(&lenient, 1, "TEE", |kd| {
        kd.attestation_level = 0;
        kd.keymint_level = 0;
    })
    .await;
    let (s, b) = post_attest(&lenient, attest_body(&authority, &m.pubkey, &m.chain, &nonce, &token)).await;
    assert_eq!(s, StatusCode::OK, "{b}");
    assert_eq!(b["level"], 0);
    assert_eq!(b["attestation"]["downgrades"], json!(["software_key"]));
    let message = hex::decode(b["message"].as_str().unwrap()).unwrap();
    assert_eq!(message[102], 0);
}

#[tokio::test]
async fn unlocked_device_is_rejected_or_level_0_per_policy() {
    let h = Harness::new(Opts::default());
    let (authority, token, nonce, m) = setup(&h, 1, "TEE", |kd| {
        kd.device_locked = false;
        kd.boot_state = 2;
    })
    .await;
    let (s, b) = post_attest(&h, attest_body(&authority, &m.pubkey, &m.chain, &nonce, &token)).await;
    assert_eq!((s, error(&b)), (StatusCode::UNPROCESSABLE_ENTITY, "device_not_locked"));

    let lenient = Harness::new(Opts { unlocked_devices: DowngradePolicy::Level0, ..Opts::default() });
    let (authority, token, nonce, m) = setup(&lenient, 1, "TEE", |kd| kd.device_locked = false).await;
    let (s, b) = post_attest(&lenient, attest_body(&authority, &m.pubkey, &m.chain, &nonce, &token)).await;
    assert_eq!((s, &b["level"]), (StatusCode::OK, &json!(0)), "{b}");
}

type KdEdit = Box<dyn Fn(&mut Kd)>;

#[tokio::test]
async fn key_property_violations_are_rejected() {
    let h = Harness::new(Opts::default());
    let cases: Vec<(KdEdit, &str, &str)> = vec![
        (Box::new(|kd| kd.origin = 2), "TEE", "imported_key"),
        (Box::new(|kd| kd.purposes = vec![2, 7]), "TEE", "wrong_key_parameters"),
        (Box::new(|kd| kd.purposes = vec![3]), "TEE", "wrong_key_parameters"),
        (Box::new(|kd| kd.ec_curve = 2), "TEE", "wrong_key_parameters"),
        (Box::new(|kd| kd.algorithm = 1), "TEE", "wrong_key_parameters"),
        // KeyDescription claims StrongBox, but the RKP attestation key is a TEE key.
        (
            Box::new(|kd| {
                kd.attestation_level = 2;
                kd.keymint_level = 2;
            }),
            "TEE",
            "security_level_mismatch",
        ),
        (Box::new(|kd| kd.keymint_level = 2), "StrongBox", "security_level_mismatch"),
    ];
    for (i, (edit, org, code)) in cases.into_iter().enumerate() {
        let (authority, token, nonce, m) = setup(&h, 10 + i as u8, org, |kd| edit(kd)).await;
        let (s, b) = post_attest(&h, attest_body(&authority, &m.pubkey, &m.chain, &nonce, &token)).await;
        assert_eq!((s, error(&b)), (StatusCode::UNPROCESSABLE_ENTITY, code), "case {i}");
    }
}

#[tokio::test]
async fn revoked_serial_is_rejected() {
    // Serial 0x0abc (the synthetic attestation key) is on the fixture status list.
    let h = Harness::new(Opts { revoked_serials: vec!["abc"], ..Opts::default() });
    let (authority, token, nonce, m) = setup(&h, 1, "TEE", |_| {}).await;
    let (s, b) = post_attest(&h, attest_body(&authority, &m.pubkey, &m.chain, &nonce, &token)).await;
    assert_eq!((s, error(&b)), (StatusCode::UNPROCESSABLE_ENTITY, "revoked"));
}

#[tokio::test]
async fn submitted_pubkey_must_equal_the_leaf() {
    let h = Harness::new(Opts::default());
    let (authority, token, nonce, m) = setup(&h, 1, "TEE", |_| {}).await;
    let other = h.ca.mint(&Kd::tee(&[0; 32]), "TEE", &[1], false).pubkey;
    let (s, b) = post_attest(&h, attest_body(&authority, &other, &m.chain, &nonce, &token)).await;
    assert_eq!((s, error(&b)), (StatusCode::UNPROCESSABLE_ENTITY, "pubkey_mismatch"));
    // Not even a point on the curve.
    let mut junk = m.pubkey;
    junk[1..].fill(0xff);
    let (s, b) = post_attest(&h, attest_body(&authority, &junk, &m.chain, &nonce, &token)).await;
    assert_eq!((s, error(&b)), (StatusCode::BAD_REQUEST, "pubkey_malformed"));
}

#[tokio::test]
async fn tampered_foreign_and_extended_chains_are_rejected() {
    let h = Harness::new(Opts::default());
    let (authority, token, nonce, m) = setup(&h, 1, "TEE", |_| {}).await;
    let run = |chain: Vec<Vec<u8>>, key: [u8; 33]| {
        let body = attest_body(&authority, &key, &chain, &nonce, &token);
        async { post_attest(&h, body).await }
    };

    // Tampered: flip the last byte of the attestation key certificate (its signature).
    let mut tampered = m.chain.clone();
    let last = tampered[1].len() - 1;
    tampered[1][last] ^= 1;
    let (s, b) = run(tampered, m.pubkey).await;
    assert_eq!((s, error(&b)), (StatusCode::UNPROCESSABLE_ENTITY, "bad_signature"));

    // Same shape, same names, different (untrusted) root.
    let foreign = foreign_ca(T0).mint(&Kd::tee(&[0; 32]), "TEE", &[1], false);
    let (s, b) = run(foreign.chain, foreign.pubkey).await;
    assert_eq!((s, error(&b)), (StatusCode::UNPROCESSABLE_ENTITY, "unknown_root"));

    // Chain extension: the genuinely attested key signs a forged leaf for a software key.
    let mut forged_kd = Kd::tee(&[0; 32]);
    forged_kd.attestation_level = 2;
    forged_kd.keymint_level = 2;
    let extended = h.ca.extend_with_forged_leaf(&m, &forged_kd);
    let (s, b) = run(extended.chain, extended.pubkey).await;
    assert_eq!((s, error(&b)), (StatusCode::UNPROCESSABLE_ENTITY, "chain_shape"));

    // KeyDescription planted on the attestation key certificate.
    let challenge = hex::decode(
        h.call(Method::GET, "/attest/challenge", None, Some(&token)).await.1["challenge"].as_str().unwrap(),
    )
    .unwrap();
    let planted = h.ca.mint(&Kd::tee(&challenge), "TEE", &[1], true);
    let (s, b) = run(planted.chain, planted.pubkey).await;
    assert_eq!((s, error(&b)), (StatusCode::UNPROCESSABLE_ENTITY, "key_description_outside_leaf"));

    // Garbage.
    let (s, b) = run(vec![vec![1, 2, 3], m.chain[4].clone()], m.pubkey).await;
    assert_eq!((s, error(&b)), (StatusCode::UNPROCESSABLE_ENTITY, "cert_malformed"));
    let (s, b) = run(vec![], m.pubkey).await;
    assert_eq!((s, error(&b)), (StatusCode::BAD_REQUEST, "chain_length"));

    // None of that spent the nonce.
    let (s, b) = run(m.chain.clone(), m.pubkey).await;
    assert_eq!(s, StatusCode::OK, "{b}");
}

#[tokio::test]
async fn status_list_unavailable_fails_closed_and_keeps_the_nonce() {
    let h = Harness::new(Opts { status_unavailable: true, ..Opts::default() });
    let (authority, token, nonce, m) = setup(&h, 1, "TEE", |_| {}).await;
    let (s, b) = post_attest(&h, attest_body(&authority, &m.pubkey, &m.chain, &nonce, &token)).await;
    assert_eq!((s, error(&b)), (StatusCode::SERVICE_UNAVAILABLE, "status_list_unavailable"));
    assert!(h.app.nonces.take(&nonce).unwrap().is_some(), "nonce must survive a 503");
}

#[tokio::test]
async fn attest_routes_are_rate_limited() {
    let h = Harness::new(Opts { attest_burst: 3, ..Opts::default() });
    let token = h.sign_in(&wallet(1)).await;
    for _ in 0..3 {
        assert_eq!(h.call(Method::GET, "/attest/challenge", None, Some(&token)).await.0, StatusCode::OK);
    }
    let (s, b, headers) = h.call(Method::GET, "/attest/challenge", None, Some(&token)).await;
    assert_eq!((s, error(&b)), (StatusCode::TOO_MANY_REQUESTS, "rate_limited"));
    assert!(headers.get("retry-after").is_some());
    // SIWS is on a separate, larger bucket.
    assert_eq!(h.call(Method::POST, "/siws/nonce", None, None).await.0, StatusCode::OK);
}

#[tokio::test]
async fn limits_key_on_x_real_ip_only_when_it_is_trusted() {
    // The default: the header is ignored, so a caller cannot pick a bucket by sending it.
    let h = Harness::new(Opts { attest_burst: 1, ..Opts::default() });
    let token = h.sign_in(&wallet(1)).await;
    assert_eq!(h.get_from("203.0.113.1", "/attest/challenge", &token).await, StatusCode::OK);
    assert_eq!(h.get_from("203.0.113.2", "/attest/challenge", &token).await, StatusCode::TOO_MANY_REQUESTS);

    // HD_TRUST_REAL_IP=true: one bucket per address the proxy reports.
    let h = Harness::new(Opts { attest_burst: 1, trust_real_ip: true, ..Opts::default() });
    let token = h.sign_in(&wallet(1)).await;
    assert_eq!(h.get_from("203.0.113.1", "/attest/challenge", &token).await, StatusCode::OK);
    assert_eq!(h.get_from("203.0.113.1", "/attest/challenge", &token).await, StatusCode::TOO_MANY_REQUESTS);
    assert_eq!(h.get_from("203.0.113.2", "/attest/challenge", &token).await, StatusCode::OK);
}

#[tokio::test]
async fn healthz_registrar_info_and_hygiene() {
    let h = Harness::new(Opts { max_body_bytes: 4096, ..Opts::default() });
    let (s, b, headers) = h.call(Method::GET, "/healthz", None, None).await;
    assert_eq!((s, &b["status"]), (StatusCode::OK, &json!("ok")));
    assert_eq!(headers.get("cache-control").unwrap(), "no-store");
    assert_eq!(headers.get("x-content-type-options").unwrap(), "nosniff");
    assert!(headers.get("x-request-id").is_some());

    let (s, info, _) = h.call(Method::GET, "/registrar", None, None).await;
    assert_eq!(s, StatusCode::OK);
    let registrar = hd_registrar::voucher::RegistrarKey::from_seed(&h.registrar_seed).pubkey();
    assert_eq!(info["registrar"], bs58::encode(registrar).into_string());
    assert_eq!(info["app_package"], PACKAGE);
    assert_eq!(info["program_id"], HEADS_DOWN_PROGRAM_ID);
    assert_eq!(info["debug_signers_accepted"], false);
    assert!(!info.to_string().contains(&hex::encode(h.registrar_seed)));

    let (s, b, _) = h.call(Method::GET, "/nope", None, None).await;
    assert_eq!((s, error(&b)), (StatusCode::NOT_FOUND, "not_found"));

    // Body cap applies to /attest too.
    let token = h.sign_in(&wallet(1)).await;
    let big = json!({"authority": "x", "p256_pubkey_compressed": "00", "attestation_chain": ["A".repeat(8000)], "nonce": "0", "session_token": token});
    let (s, _, _) = h.call(Method::POST, "/attest", Some(big), None).await;
    assert_eq!(s, StatusCode::PAYLOAD_TOO_LARGE);
}
