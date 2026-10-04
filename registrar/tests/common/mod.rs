//! Test harness: the real router over a real `App`, with a manual clock, a temp directory, a
//! fixed slot, and a synthetic Key Attestation CA shaped like Google's RKP hierarchy
//! (`root(P-384) -> Droid CA2 -> Droid CA3 -> attestation key (O=TEE|StrongBox) -> leaf`), so
//! the Heads Down specific paths (challenge = f(authority, server nonce), package pinning,
//! policy, HTTP flow) can be exercised with chains we control. Real Google chains are covered
//! in `attest_vectors.rs`.

#![allow(dead_code)]

pub mod der;

use std::collections::HashMap;
use std::sync::Arc;

use axum::body::Body;
use axum::http::{HeaderMap, Method, Request, StatusCode};
use axum::Router;
use ed25519_dalek::{Signer, SigningKey};
use hd_registrar::attest::revocation::{RevocationList, StatusListProvider};
use hd_registrar::attest::{DowngradePolicy, TrustAnchors};
use hd_registrar::clock::{rfc3339, ManualClock};
use hd_registrar::config::{Config, Secrets};
use hd_registrar::http::{router, App, Overrides};
use hd_registrar::nonce::MemoryNonceStore;
use hd_registrar::siws::SiwsMessage;
use hd_registrar::slot::SlotSource;
use http_body_util::BodyExt;
use rcgen::{
    BasicConstraints, CertificateParams, CustomExtension, DistinguishedName, DnType, IsCa, Issuer, KeyPair,
    SerialNumber, PKCS_ECDSA_P256_SHA256, PKCS_ECDSA_P384_SHA384,
};
use serde_json::{json, Value};
use tower::ServiceExt;

pub const T0: i64 = 1_790_683_200; // 2026-09-29T12:00:00Z
pub const SLOT: u64 = 400_000_000;
pub const RELEASE_DIGEST: [u8; 32] = [0x5a; 32];
pub const DEBUG_DIGEST: [u8; 32] = [0xdb; 32];
pub const PACKAGE: &str = "xyz.headsdown";
pub const KEY_DESCRIPTION_OID: &[u64] = &[1, 3, 6, 1, 4, 1, 11129, 2, 1, 17];

pub struct Opts {
    pub software_keys: DowngradePolicy,
    pub unlocked_devices: DowngradePolicy,
    pub revoked_serials: Vec<&'static str>,
    pub status_unavailable: bool,
    pub attest_burst: u32,
    pub debug_digest: bool,
    pub max_body_bytes: usize,
    /// `HD_TRUST_REAL_IP=true`: key the rate limits on `X-Real-IP`.
    pub trust_real_ip: bool,
    /// `HD_SIWS_DOMAIN`, and `HD_SIWS_URI` when it is not the default `https://<domain>`.
    pub siws_domain: &'static str,
    pub siws_uri: Option<&'static str>,
}

impl Default for Opts {
    fn default() -> Self {
        Self {
            software_keys: DowngradePolicy::Reject,
            unlocked_devices: DowngradePolicy::Reject,
            revoked_serials: vec![],
            status_unavailable: false,
            attest_burst: 1000,
            debug_digest: false,
            max_body_bytes: 64 * 1024,
            trust_real_ip: false,
            siws_domain: "headsdown.example",
            siws_uri: None,
        }
    }
}

pub struct Harness {
    pub app: Arc<App>,
    pub router: Router,
    pub clock: Arc<ManualClock>,
    pub dir: tempfile::TempDir,
    pub ca: TestCa,
    pub registrar_seed: [u8; 32],
}

impl Harness {
    pub fn new(opts: Opts) -> Self {
        let dir = tempfile::tempdir().unwrap();
        let log_path = dir.path().join("attestations.jsonl");
        let mut env: HashMap<&str, String> = HashMap::from([
            ("HD_APP_RELEASE_CERT_SHA256", hex::encode(RELEASE_DIGEST)),
            ("HD_SIWS_DOMAIN", opts.siws_domain.into()),
            ("HD_TRANSPARENCY_LOG", log_path.display().to_string()),
            ("HD_RATE_LIMIT_PER_MIN", "100000".into()),
            ("HD_RATE_LIMIT_BURST", "100000".into()),
            ("HD_ATTEST_RATE_LIMIT_PER_MIN", "1".into()),
            ("HD_ATTEST_RATE_LIMIT_BURST", opts.attest_burst.to_string()),
            ("HD_MAX_BODY_BYTES", opts.max_body_bytes.to_string()),
            (
                "HD_SOFTWARE_KEY_POLICY",
                if opts.software_keys == DowngradePolicy::Level0 { "level0" } else { "reject" }.into(),
            ),
            (
                "HD_UNLOCKED_DEVICE_POLICY",
                if opts.unlocked_devices == DowngradePolicy::Level0 { "level0" } else { "reject" }.into(),
            ),
        ]);
        if opts.debug_digest {
            env.insert("HD_APP_DEBUG_CERT_SHA256", hex::encode(DEBUG_DIGEST));
        }
        if opts.trust_real_ip {
            env.insert("HD_TRUST_REAL_IP", "true".into());
        }
        if let Some(uri) = opts.siws_uri {
            env.insert("HD_SIWS_URI", uri.into());
        }
        let config = Config::from_lookup(&|k| env.get(k).cloned()).unwrap();
        let registrar_seed = [0x42; 32];
        let secrets = Secrets::from_lookup(&|k| match k {
            "HD_SESSION_SECRET" => Some("11".repeat(32)),
            "HD_REGISTRAR_SECRET_B58" => Some(bs58::encode(registrar_seed).into_string()),
            _ => None,
        })
        .unwrap();

        let ca = TestCa::new(T0);
        let mut anchors = TrustAnchors::default();
        anchors.add_der("test-root", &ca.root_der).unwrap();

        let status = if opts.status_unavailable {
            StatusListProvider::http("http://127.0.0.1:9/status".into(), 3600, 7200).unwrap()
        } else {
            let entries: serde_json::Map<String, Value> =
                opts.revoked_serials.iter().map(|s| ((*s).to_owned(), json!({"status": "REVOKED"}))).collect();
            let list =
                RevocationList::parse(json!({ "entries": entries }).to_string().as_bytes(), T0, "fixture").unwrap();
            StatusListProvider::fixed(list)
        };
        let clock = Arc::new(ManualClock::new(T0));
        let app = App::build(
            config,
            secrets,
            Arc::clone(&clock) as Arc<dyn hd_registrar::clock::Clock>,
            Overrides {
                anchors: Some(anchors),
                status: Some(status),
                slots: Some(SlotSource::Fixed(SLOT)),
                nonces: Some(Arc::new(MemoryNonceStore::new(10_000))),
            },
        )
        .unwrap();
        let app = Arc::new(app);
        Self { router: router(Arc::clone(&app)), app, clock, dir, ca, registrar_seed }
    }

    pub async fn call(
        &self,
        method: Method,
        path: &str,
        body: Option<Value>,
        bearer: Option<&str>,
    ) -> (StatusCode, Value, HeaderMap) {
        let bytes = body.map(|b| b.to_string().into_bytes()).unwrap_or_default();
        self.raw(method, path, bytes, bearer).await
    }

    pub async fn raw(
        &self,
        method: Method,
        path: &str,
        body: Vec<u8>,
        bearer: Option<&str>,
    ) -> (StatusCode, Value, HeaderMap) {
        let mut req = Request::builder().method(method).uri(path);
        if !body.is_empty() {
            req = req.header("content-type", "application/json");
        }
        if let Some(t) = bearer {
            req = req.header("authorization", format!("Bearer {t}"));
        }
        let resp = self.router.clone().oneshot(req.body(Body::from(body)).unwrap()).await.unwrap();
        let status = resp.status();
        let headers = resp.headers().clone();
        let bytes = resp.into_body().collect().await.unwrap().to_bytes();
        let json = serde_json::from_slice(&bytes).unwrap_or(Value::Null);
        (status, json, headers)
    }

    /// `GET path` with a session, carrying the `X-Real-IP` line a proxy would write for a
    /// client at `real_ip`. Returns the status only.
    pub async fn get_from(&self, real_ip: &str, path: &str, bearer: &str) -> StatusCode {
        let req = Request::builder()
            .method(Method::GET)
            .uri(path)
            .header("x-real-ip", real_ip)
            .header("authorization", format!("Bearer {bearer}"))
            .body(Body::empty())
            .unwrap();
        self.router.clone().oneshot(req).await.unwrap().status()
    }

    /// Builds the SIWS message the wallet would sign for a `/siws/nonce` response.
    pub fn siws_message(&self, nonce: &Value, wallet: &SigningKey) -> SiwsMessage {
        SiwsMessage {
            domain: nonce["domain"].as_str().unwrap().into(),
            address: bs58::encode(wallet.verifying_key().as_bytes()).into_string(),
            statement: Some(nonce["statement"].as_str().unwrap().into()),
            uri: Some(nonce["uri"].as_str().unwrap().into()),
            version: Some("1".into()),
            chain_id: Some(nonce["chain_ids"][0].as_str().unwrap().into()),
            nonce: Some(nonce["nonce"].as_str().unwrap().into()),
            issued_at: Some(nonce["issued_at"].as_str().unwrap().into()),
            expiration_time: Some(nonce["expiration_time"].as_str().unwrap().into()),
            ..Default::default()
        }
    }

    pub fn signed_body(message: &SiwsMessage, signer: &SigningKey) -> Value {
        let bytes = message.to_text().into_bytes();
        let sig = signer.sign(&bytes).to_bytes();
        json!({ "signed_message": b64(&bytes), "signature": b64(&sig) })
    }

    pub async fn new_nonce(&self) -> Value {
        let (s, body, _) = self.call(Method::POST, "/siws/nonce", None, None).await;
        assert_eq!(s, StatusCode::OK, "{body}");
        body
    }

    /// Full SIWS flow; returns the session token.
    pub async fn sign_in(&self, wallet: &SigningKey) -> String {
        let nonce = self.new_nonce().await;
        let msg = self.siws_message(&nonce, wallet);
        let (s, body, _) = self.call(Method::POST, "/siws/verify", Some(Self::signed_body(&msg, wallet)), None).await;
        assert_eq!(s, StatusCode::OK, "{body}");
        body["session_token"].as_str().unwrap().to_owned()
    }

    /// `GET /attest/challenge`: returns (nonce hex, challenge bytes).
    pub async fn challenge(&self, token: &str) -> (String, Vec<u8>) {
        let (s, body, _) = self.call(Method::GET, "/attest/challenge", None, Some(token)).await;
        assert_eq!(s, StatusCode::OK, "{body}");
        (body["nonce"].as_str().unwrap().into(), hex::decode(body["challenge"].as_str().unwrap()).unwrap())
    }
}

pub fn b64(bytes: &[u8]) -> String {
    use base64::Engine as _;
    base64::engine::general_purpose::STANDARD.encode(bytes)
}

pub fn wallet(seed: u8) -> SigningKey {
    SigningKey::from_bytes(&[seed; 32])
}

pub fn address(w: &SigningKey) -> String {
    bs58::encode(w.verifying_key().as_bytes()).into_string()
}

pub fn attest_body(authority: &str, pubkey: &[u8; 33], chain: &[Vec<u8>], nonce: &str, token: &str) -> Value {
    json!({
        "authority": authority,
        "p256_pubkey_compressed": hex::encode(pubkey),
        "attestation_chain": chain.iter().map(|c| b64(c)).collect::<Vec<_>>(),
        "nonce": nonce,
        "session_token": token,
    })
}

// -----------------------------------------------------------------------------------------------
// Synthetic attestation CA.
// -----------------------------------------------------------------------------------------------

fn dn(parts: &[(DnType, &str)]) -> DistinguishedName {
    let mut d = DistinguishedName::new();
    for (t, v) in parts {
        d.push(t.clone(), *v);
    }
    d
}

fn at(ts: i64) -> time::OffsetDateTime {
    time::OffsetDateTime::from_unix_timestamp(ts).unwrap()
}

fn ca_params(name: DistinguishedName, now: i64, serial: &[u8]) -> CertificateParams {
    let mut p = CertificateParams::default();
    p.distinguished_name = name;
    p.is_ca = IsCa::Ca(BasicConstraints::Unconstrained);
    p.not_before = at(now - 86_400);
    p.not_after = at(now + 30 * 86_400);
    p.serial_number = Some(SerialNumber::from_slice(serial));
    p
}

/// KeyDescription contents for a synthetic leaf.
#[derive(Clone)]
pub struct Kd {
    pub challenge: Vec<u8>,
    pub attestation_level: i64,
    pub keymint_level: i64,
    pub package: String,
    pub digests: Vec<[u8; 32]>,
    pub origin: i64,
    pub device_locked: bool,
    pub boot_state: i64,
    pub purposes: Vec<i64>,
    pub algorithm: i64,
    pub ec_curve: i64,
}

impl Kd {
    pub fn tee(challenge: &[u8]) -> Self {
        Self {
            challenge: challenge.to_vec(),
            attestation_level: 1,
            keymint_level: 1,
            package: PACKAGE.into(),
            digests: vec![RELEASE_DIGEST],
            origin: 0,
            device_locked: true,
            boot_state: 0,
            purposes: vec![2],
            algorithm: 3,
            ec_curve: 1,
        }
    }

    pub fn encode(&self) -> Vec<u8> {
        use der::*;
        let aaid = seq(&[
            set(&[seq(&[octets(self.package.as_bytes()), int(1)])]),
            set(&self.digests.iter().map(|d| octets(d)).collect::<Vec<_>>()),
        ]);
        let sw = seq(&[explicit(701, &int(1_790_000_000_000)), explicit(709, &octets(&aaid))]);
        let hw = seq(&[
            explicit(1, &set(&self.purposes.iter().map(|p| int(*p)).collect::<Vec<_>>())),
            explicit(2, &int(self.algorithm)),
            explicit(3, &int(256)),
            explicit(5, &set(&[int(4)])),
            explicit(10, &int(self.ec_curve)),
            explicit(503, &null()),
            explicit(702, &int(self.origin)),
            explicit(
                704,
                &seq(&[octets(&[7; 32]), boolean(self.device_locked), enumerated(self.boot_state), octets(&[8; 32])]),
            ),
            explicit(705, &int(160000)),
            explicit(706, &int(202609)),
        ]);
        seq(&[
            int(400),
            enumerated(self.attestation_level),
            int(400),
            enumerated(self.keymint_level),
            octets(&self.challenge),
            octets(b""),
            sw,
            hw,
        ])
    }
}

pub struct TestCa {
    pub now: i64,
    pub root_der: Vec<u8>,
    root: Issuer<'static, KeyPair>,
    pub ca2_der: Vec<u8>,
    pub ca3_der: Vec<u8>,
    ca3: Issuer<'static, KeyPair>,
}

pub struct MintedChain {
    pub chain: Vec<Vec<u8>>,
    pub pubkey: [u8; 33],
    pub leaf_key: KeyPair,
}

pub fn compress(raw: &[u8]) -> [u8; 33] {
    assert_eq!(raw.len(), 65, "expected an uncompressed SEC1 point");
    let mut out = [0u8; 33];
    out[0] = 0x02 | (raw[64] & 1);
    out[1..].copy_from_slice(&raw[1..33]);
    out
}

impl TestCa {
    pub fn new(now: i64) -> Self {
        let root_key = KeyPair::generate_for(&PKCS_ECDSA_P384_SHA384).unwrap();
        let root_params = ca_params(
            dn(&[(DnType::CommonName, "Test Key Attestation Root"), (DnType::OrganizationName, "Heads Down Tests")]),
            now,
            &[0x01, 0x11],
        );
        let root_der = root_params.self_signed(&root_key).unwrap().der().to_vec();
        let root = Issuer::new(root_params, root_key);

        let ca2_key = KeyPair::generate_for(&PKCS_ECDSA_P256_SHA256).unwrap();
        let ca2_params = ca_params(
            dn(&[(DnType::CommonName, "Droid CA2"), (DnType::OrganizationName, "Google LLC")]),
            now,
            &[0x02, 0x22],
        );
        let ca2_der = ca2_params.signed_by(&ca2_key, &root).unwrap().der().to_vec();
        let ca2 = Issuer::new(ca2_params, ca2_key);

        let ca3_key = KeyPair::generate_for(&PKCS_ECDSA_P256_SHA256).unwrap();
        let ca3_params = ca_params(
            dn(&[(DnType::CommonName, "Droid CA3"), (DnType::OrganizationName, "Google LLC")]),
            now,
            &[0x03, 0x33],
        );
        let ca3_der = ca3_params.signed_by(&ca3_key, &ca2).unwrap().der().to_vec();
        let ca3 = Issuer::new(ca3_params, ca3_key);

        Self { now, root_der, root, ca2_der, ca3_der, ca3 }
    }

    /// Mints `[leaf, attestation key, Droid CA3, Droid CA2, root]`.
    /// `org` is the attestation key's O= ("TEE" or "StrongBox"); `attestation_serial` lets a
    /// test revoke it; `kd_on_attestation_cert` plants a KeyDescription outside the leaf.
    pub fn mint(&self, kd: &Kd, org: &str, attestation_serial: &[u8], kd_on_attestation_cert: bool) -> MintedChain {
        let att_key = KeyPair::generate_for(&PKCS_ECDSA_P256_SHA256).unwrap();
        let mut att_params = ca_params(
            dn(&[(DnType::CommonName, "f165849ef08b4658dd0a8ab95be53006"), (DnType::OrganizationName, org)]),
            self.now,
            attestation_serial,
        );
        if kd_on_attestation_cert {
            att_params.custom_extensions.push(CustomExtension::from_oid_content(KEY_DESCRIPTION_OID, kd.encode()));
        }
        let att_der = att_params.signed_by(&att_key, &self.ca3).unwrap().der().to_vec();
        let att = Issuer::new(att_params, att_key);

        let leaf_key = KeyPair::generate_for(&PKCS_ECDSA_P256_SHA256).unwrap();
        let mut leaf_params = CertificateParams::default();
        leaf_params.distinguished_name = dn(&[(DnType::CommonName, "Android Keystore Key")]);
        leaf_params.not_before = at(0);
        leaf_params.not_after = at(2_461_449_600); // 2048-01-01, like real leaves
        leaf_params.serial_number = Some(SerialNumber::from_slice(&[1]));
        leaf_params.custom_extensions.push(CustomExtension::from_oid_content(KEY_DESCRIPTION_OID, kd.encode()));
        let leaf_der = leaf_params.signed_by(&leaf_key, &att).unwrap().der().to_vec();
        let pubkey = compress(leaf_key.public_key_raw());
        MintedChain {
            chain: vec![leaf_der, att_der, self.ca3_der.clone(), self.ca2_der.clone(), self.root_der.clone()],
            pubkey,
            leaf_key,
        }
    }

    /// The chain-extension attack: the holder of a genuinely attested key uses it (it has
    /// PURPOSE_SIGN) to sign a forged "leaf" certifying a software key with a fake
    /// KeyDescription, and prepends it to the genuine chain.
    pub fn extend_with_forged_leaf(&self, genuine: &MintedChain, forged_kd: &Kd) -> MintedChain {
        let mut genuine_leaf_params = CertificateParams::default();
        genuine_leaf_params.distinguished_name = dn(&[(DnType::CommonName, "Android Keystore Key")]);
        let genuine_leaf_der = genuine.chain[0].clone();
        let genuine_key = KeyPair::from_pem(&genuine.leaf_key.serialize_pem()).unwrap();
        let issuer = Issuer::new(genuine_leaf_params, genuine_key);
        let forged_key = KeyPair::generate_for(&PKCS_ECDSA_P256_SHA256).unwrap();
        let mut p = CertificateParams::default();
        p.distinguished_name = dn(&[(DnType::CommonName, "Android Keystore Key")]);
        p.custom_extensions.push(CustomExtension::from_oid_content(KEY_DESCRIPTION_OID, forged_kd.encode()));
        let forged_der = p.signed_by(&forged_key, &issuer).unwrap().der().to_vec();
        let mut chain = vec![forged_der, genuine_leaf_der];
        chain.extend(genuine.chain[1..].iter().cloned());
        MintedChain { chain, pubkey: compress(forged_key.public_key_raw()), leaf_key: forged_key }
    }
}

/// A different CA with the same shape: its chains end in an untrusted root.
pub fn foreign_ca(now: i64) -> TestCa {
    TestCa::new(now)
}

pub fn siws_rfc3339(ts: i64) -> String {
    rfc3339(ts)
}
