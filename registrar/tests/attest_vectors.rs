//! End-to-end verification of **real** Android Key Attestation chains (from Google's
//! `android/keyattestation` test data, see `testdata/chains/SOURCES.md`) against Google's real
//! root keys, including KeyDescription parsing checked field by field against Google's own
//! decoding (`*.json`), plus the negative cases on genuine chains.
//!
//! RKP intermediates live for weeks, so RKP vectors are verified at a fixed instant inside
//! their validity window; factory chains are verified at "today" (2026-09-29) to prove that
//! expired factory intermediates are still accepted, as Google prescribes.

use base64::Engine as _;
use hd_registrar::attest::chain::{pem_bundle_to_der, verify_chain, ChainError, Provisioning};
use hd_registrar::attest::keydesc::{SecurityLevel, VerifiedBootState};
use hd_registrar::attest::{
    AttestError, AttestationPolicy, DowngradePolicy, PolicyError, RevocationList, TrustAnchors, Verifier,
};
use serde_json::Value;

const T_2026_09_10: i64 = 1_788_998_400; // inside frankel's RKP window
const T_2026_03_01: i64 = 1_772_323_200; // inside tegu's RKP window
const T_2025_09_28: i64 = 1_759_017_600; // inside caiman's RKP windows
const T_2024_09_20: i64 = 1_726_790_400; // inside akita's RKP window
const T_2020_01_01: i64 = 1_577_836_800;
const T_TODAY: i64 = 1_790_640_000; // 2026-09-29

fn read(path: &str) -> String {
    std::fs::read_to_string(format!("{}/testdata/{path}", env!("CARGO_MANIFEST_DIR"))).unwrap()
}

struct Vector {
    chain: Vec<Vec<u8>>,
    json: Value,
}

fn vector(name: &str) -> Vector {
    let chain = pem_bundle_to_der(&read(&format!("chains/{name}.pem"))).unwrap();
    // Upstream JSON may start with `//` comment lines.
    let raw = read(&format!("chains/{name}.json"));
    let body: String = raw.lines().filter(|l| !l.trim_start().starts_with("//")).collect::<Vec<_>>().join("\n");
    Vector { chain, json: serde_json::from_str(&body).unwrap() }
}

fn b64(s: &str) -> Vec<u8> {
    base64::engine::general_purpose::STANDARD.decode(s).unwrap()
}

impl Vector {
    fn challenge(&self) -> Vec<u8> {
        b64(self.json["attestationChallenge"].as_str().unwrap())
    }
    fn package(&self) -> String {
        self.json["softwareEnforced"]["attestationApplicationId"]["packages"][0]["name"].as_str().unwrap().to_owned()
    }
    fn digest(&self) -> [u8; 32] {
        b64(self.json["softwareEnforced"]["attestationApplicationId"]["signatures"][0].as_str().unwrap())
            .try_into()
            .unwrap()
    }
    fn policy(&self) -> AttestationPolicy {
        AttestationPolicy {
            package_name: self.package(),
            release_digests: vec![self.digest()],
            debug_digests: vec![],
            software_keys: DowngradePolicy::Reject,
            unlocked_devices: DowngradePolicy::Reject,
        }
    }
    fn leaf_key(&self, now: i64) -> [u8; 33] {
        verify_chain(&self.chain, &google(), &no_revocations(), now).unwrap().leaf_p256_compressed.unwrap()
    }
}

fn google() -> TrustAnchors {
    TrustAnchors::google().unwrap()
}

fn no_revocations() -> RevocationList {
    RevocationList::parse(read("status/status_live_sample.json").as_bytes(), 0, "sample").unwrap()
}

fn revoked_fixture() -> RevocationList {
    RevocationList::parse(read("status/status_revoked_fixture.json").as_bytes(), 0, "fixture").unwrap()
}

fn verifier(policy: AttestationPolicy) -> Verifier {
    Verifier { anchors: google(), policy }
}

fn verify(v: &Vector, now: i64) -> Result<hd_registrar::attest::AttestationSummary, AttestError> {
    verifier(v.policy()).verify(&v.chain, &v.leaf_key(now), &v.challenge(), &no_revocations(), now)
}

// ---------------------------------------------------------------------------------------------
// Positive: real chains, real roots.
// ---------------------------------------------------------------------------------------------

#[test]
fn tee_key_under_new_p384_root_rkp() {
    let v = vector("frankel_sdk37_TEE_EC_2026");
    let s = verify(&v, T_2026_09_10).unwrap();
    assert_eq!(s.level, 1);
    assert_eq!(s.provisioning, Provisioning::RemoteKeyProvisioning);
    assert_eq!(s.anchor, "google-key-attestation-ca1-p384");
    assert_eq!(s.attestation_version, 500);
    assert_eq!(s.verified_boot_state, Some(VerifiedBootState::Verified));
    assert_eq!(s.device_locked, Some(true));
    assert_eq!(s.os_patch_level, Some(202609));
    assert!(s.downgrades.is_empty());
}

#[test]
fn strongbox_key_under_new_p384_root_rkp() {
    let v = vector("tegu_sdk36_SB_EC_2026_ROOT");
    let s = verify(&v, T_2026_03_01).unwrap();
    assert_eq!(s.level, 2);
    assert_eq!(s.anchor, "google-key-attestation-ca1-p384");
}

#[test]
fn tee_and_strongbox_keys_under_rsa_root_rkp() {
    let tee = verify(&vector("caiman_sdk36_TEE_EC_RKP"), T_2025_09_28).unwrap();
    assert_eq!((tee.level, tee.provisioning), (1, Provisioning::RemoteKeyProvisioning));
    assert_eq!(tee.anchor, "google-rsa4096-2022");
    let sb = verify(&vector("caiman_sdk36_SB_EC_RKP"), T_2025_09_28).unwrap();
    assert_eq!(sb.level, 2);
    assert_eq!(sb.keymint_security_level, SecurityLevel::StrongBox);
}

#[test]
fn factory_chain_with_expired_2016_root_and_intermediates_verifies_today() {
    // Sony Xperia 10 III: the batch intermediates and the 2016 root certificate expired in
    // May 2026. Google: keep trusting factory chains to the RSA root regardless of validity.
    let v = vector("sony_xperia10iii_sdk33_TEE_EC");
    let s = verify(&v, T_TODAY).unwrap();
    assert_eq!((s.level, s.provisioning), (1, Provisioning::Factory));
    assert_eq!(s.serials[1], "16580768335559031605");
}

#[test]
fn key_description_matches_googles_decoding() {
    let cases = [
        ("frankel_sdk37_TEE_EC_2026", T_2026_09_10),
        ("tegu_sdk36_SB_EC_2026_ROOT", T_2026_03_01),
        ("caiman_sdk36_TEE_EC_RKP", T_2025_09_28),
        ("caiman_sdk36_SB_EC_RKP", T_2025_09_28),
        ("sony_xperia10iii_sdk33_TEE_EC", T_TODAY),
        ("blueline_sdk28_TEE_EC_NONE", T_TODAY),
        ("akita_sdk34_SB_RSA_NONE", T_2024_09_20),
    ];
    for (name, now) in cases {
        let v = vector(name);
        let kd = verify_chain(&v.chain, &google(), &no_revocations(), now).unwrap().key_description;
        let j = &v.json;
        let level = |s: &str| match s {
            "SOFTWARE" => SecurityLevel::Software,
            "TRUSTED_ENVIRONMENT" => SecurityLevel::TrustedEnvironment,
            "STRONG_BOX" => SecurityLevel::StrongBox,
            other => panic!("{other}"),
        };
        let num = |v: &Value| v.as_str().unwrap().parse::<i64>().unwrap();
        assert_eq!(kd.attestation_version, num(&j["attestationVersion"]), "{name}");
        assert_eq!(kd.keymint_version, num(&j["keyMintVersion"]), "{name}");
        assert_eq!(kd.attestation_security_level, level(j["attestationSecurityLevel"].as_str().unwrap()), "{name}");
        assert_eq!(kd.keymint_security_level, level(j["keyMintSecurityLevel"].as_str().unwrap()), "{name}");
        assert_eq!(kd.attestation_challenge, v.challenge(), "{name}");
        let hw = &kd.hardware_enforced;
        let hwj = &j["hardwareEnforced"];
        let purposes: Vec<i64> = hwj["purposes"].as_array().unwrap().iter().map(num).collect();
        assert_eq!(hw.purposes.as_ref().unwrap(), &purposes, "{name}");
        assert_eq!(hw.algorithm, Some(num(&hwj["algorithms"])), "{name}");
        assert_eq!(hw.key_size, Some(num(&hwj["keySize"])), "{name}");
        if let Some(curve) = hwj.get("ecCurve") {
            assert_eq!(hw.ec_curve, Some(num(curve)), "{name}");
        }
        assert_eq!(hw.no_auth_required, hwj["noAuthRequired"].as_bool().unwrap_or(false), "{name}");
        assert_eq!(hw.origin, Some(0), "{name}");
        assert_eq!(hw.os_patch_level, Some(num(&hwj["osPatchLevel"])), "{name}");
        let rot = hw.root_of_trust.as_ref().unwrap();
        let rotj = &hwj["rootOfTrust"];
        assert_eq!(rot.device_locked, rotj["deviceLocked"].as_bool().unwrap(), "{name}");
        let state = match rotj["verifiedBootState"].as_str().unwrap() {
            "VERIFIED" => VerifiedBootState::Verified,
            "UNVERIFIED" => VerifiedBootState::Unverified,
            "SELF_SIGNED" => VerifiedBootState::SelfSigned,
            _ => VerifiedBootState::Failed,
        };
        assert_eq!(rot.verified_boot_state, state, "{name}");
        assert_eq!(rot.verified_boot_key, b64(rotj["verifiedBootKey"].as_str().unwrap()), "{name}");
        let app = kd.application_id().unwrap().unwrap();
        assert_eq!(app.packages.len(), 1, "{name}");
        assert_eq!(app.packages[0].name, v.package(), "{name}");
        assert_eq!(app.signature_digests, vec![v.digest().to_vec()], "{name}");
        // Device IDs only where the upstream collector requested them.
        assert_eq!(hw.has_device_ids, hwj.get("attestationIdBrand").is_some(), "{name}");
    }
}

#[test]
fn edited_leaf_fails_signature_but_its_key_description_still_parses() {
    // Google's own CLI test expects this chain to fail verification: the leaf's tags were
    // reordered after signing.
    let chain = pem_bundle_to_der(&read("chains/edited_tags_not_in_ascending_order.pem")).unwrap();
    assert_eq!(verify_chain(&chain, &google(), &no_revocations(), T_TODAY).unwrap_err(), ChainError::BadSignature(0));

    use x509_parser::prelude::*;
    let (_, leaf) = X509Certificate::from_der(&chain[0]).unwrap();
    let ext = leaf.extensions().iter().find(|e| e.oid.to_id_string() == "1.3.6.1.4.1.11129.2.1.17").unwrap();
    let kd = hd_registrar::attest::keydesc::parse(ext.value).unwrap();
    assert!(!kd.hardware_enforced.tags_ordered || !kd.software_enforced.tags_ordered);
}

#[test]
fn real_device_non_der_boolean_is_tolerated_like_googles_verifier() {
    let chain = pem_bundle_to_der(&read("chains/quirk_non_der_bool_device_locked.pem")).unwrap();
    let vc = verify_chain(&chain, &google(), &no_revocations(), T_TODAY).unwrap();
    let rot = vc.key_description.hardware_enforced.root_of_trust.unwrap();
    assert!(rot.device_locked && rot.device_locked_non_der);
}

// ---------------------------------------------------------------------------------------------
// Negative: chain.
// ---------------------------------------------------------------------------------------------

fn chain_err(r: Result<hd_registrar::attest::AttestationSummary, AttestError>) -> ChainError {
    match r {
        Err(AttestError::Chain(e)) => e,
        other => panic!("expected a chain error, got {other:?}"),
    }
}

fn policy_err(r: Result<hd_registrar::attest::AttestationSummary, AttestError>) -> PolicyError {
    match r {
        Err(AttestError::Policy(e)) => e,
        other => panic!("expected a policy error, got {other:?}"),
    }
}

/// Byte offset of `inner` inside `outer` (both borrowed from the same buffer).
fn offset_in(outer: &[u8], inner: &[u8]) -> usize {
    inner.as_ptr() as usize - outer.as_ptr() as usize
}

#[test]
fn tampered_certificates_are_rejected() {
    let v = vector("frankel_sdk37_TEE_EC_2026");
    let key = v.leaf_key(T_2026_09_10);
    let run = |chain: &[Vec<u8>]| verifier(v.policy()).verify(chain, &key, &v.challenge(), &no_revocations(), T_2026_09_10);

    // Flip the last byte of the leaf (inside its signature).
    let mut chain = v.chain.clone();
    let last = chain[0].len() - 1;
    chain[0][last] ^= 0x01;
    assert!(matches!(chain_err(run(&chain)), ChainError::BadSignature(0) | ChainError::Parse(0)));

    // Flip a byte of Droid CA3's public key (inside its TBS): Droid CA2's signature breaks.
    let mut chain = v.chain.clone();
    let off = {
        use x509_parser::prelude::*;
        let (_, cert) = X509Certificate::from_der(&v.chain[2]).unwrap();
        let spki = cert.public_key().raw;
        offset_in(&v.chain[2], spki) + spki.len() - 3
    };
    chain[2][off] ^= 0x01;
    assert_eq!(chain_err(run(&chain)), ChainError::BadSignature(2));

    // Flip a byte inside the KeyDescription (inside the leaf's TBS).
    let mut chain = v.chain.clone();
    let off = {
        use x509_parser::prelude::*;
        let (_, cert) = X509Certificate::from_der(&v.chain[0]).unwrap();
        let ext = cert.extensions().iter().find(|e| e.oid.to_id_string() == "1.3.6.1.4.1.11129.2.1.17").unwrap();
        // Inside the attestationChallenge bytes: a structurally harmless edit.
        let challenge = v.challenge();
        let pos = ext.value.windows(challenge.len()).position(|w| w == challenge.as_slice()).unwrap();
        offset_in(&v.chain[0], ext.value) + pos
    };
    chain[0][off] ^= 0x01;
    assert_eq!(chain_err(run(&chain)), ChainError::BadSignature(0));
}

#[test]
fn chain_to_unknown_root_is_rejected() {
    // Real chain from the AOSP software attestation root.
    let v = vector("marlin_sdk29_SOFTWARE_EC");
    let r = verifier(v.policy()).verify(&v.chain, &[2; 33], &v.challenge(), &no_revocations(), T_2020_01_01);
    assert_eq!(chain_err(r), ChainError::UnknownRoot);

    // A genuine chain whose root certificate had its key altered.
    let v = vector("caiman_sdk36_TEE_EC_RKP");
    let mut chain = v.chain.clone();
    let root = chain.len() - 1;
    let off = {
        use x509_parser::prelude::*;
        let (_, cert) = X509Certificate::from_der(&v.chain[root]).unwrap();
        let spki = cert.public_key().raw;
        offset_in(&v.chain[root], spki) + spki.len() - 8
    };
    chain[root][off] ^= 0x01;
    let r = verifier(v.policy()).verify(&chain, &[2; 33], &v.challenge(), &no_revocations(), T_2025_09_28);
    assert_eq!(chain_err(r), ChainError::UnknownRoot);
}

#[test]
fn malformed_chain_shapes_are_rejected() {
    let v = vector("frankel_sdk37_TEE_EC_2026");
    let key = v.leaf_key(T_2026_09_10);
    let run = |chain: &[Vec<u8>]| verifier(v.policy()).verify(chain, &key, &v.challenge(), &no_revocations(), T_2026_09_10);

    // Root omitted.
    assert_eq!(chain_err(run(&v.chain[..4])), ChainError::RootNotSelfIssued);
    // Root first (wrong order).
    let reversed: Vec<Vec<u8>> = v.chain.iter().rev().cloned().collect();
    assert_eq!(chain_err(run(&reversed)), ChainError::RootNotSelfIssued);
    // RKP chain missing Droid CA3.
    let mut missing = v.chain.clone();
    missing.remove(2);
    assert!(matches!(chain_err(run(&missing)), ChainError::Shape { expected: 5, got: 4, .. }));
    // Extra certificate spliced in (leaf duplicated at the front).
    let mut extended = v.chain.clone();
    extended.insert(0, v.chain[0].clone());
    assert!(matches!(chain_err(run(&extended)), ChainError::Shape { expected: 5, got: 6, .. }));
    // Empty / single / garbage.
    assert_eq!(chain_err(run(&[])), ChainError::Length);
    assert_eq!(chain_err(run(&v.chain[..1])), ChainError::Length);
    let mut garbage = v.chain.clone();
    garbage[1] = vec![0x30, 0x03, 0x02, 0x01, 0x01];
    assert_eq!(chain_err(run(&garbage)), ChainError::Parse(1));
    let mut trailing = v.chain.clone();
    trailing[1].push(0);
    assert_eq!(chain_err(run(&trailing)), ChainError::TrailingData(1));
}

#[test]
fn rkp_validity_is_enforced() {
    let v = vector("frankel_sdk37_TEE_EC_2026");
    let key = v.leaf_key(T_2026_09_10);
    let at = |now| verifier(v.policy()).verify(&v.chain, &key, &v.challenge(), &no_revocations(), now);
    // The RKP attestation key expired on 2026-09-15.
    assert_eq!(chain_err(at(T_TODAY)), ChainError::Expired(1));
    // Before Droid CA3 existed.
    assert!(matches!(chain_err(at(T_2026_03_01)), ChainError::NotYetValid(_)));
}

#[test]
fn revoked_or_suspended_serials_are_rejected() {
    let v = vector("sony_xperia10iii_sdk33_TEE_EC");
    let r = verifier(v.policy()).verify(&v.chain, &v.leaf_key(T_TODAY), &v.challenge(), &revoked_fixture(), T_TODAY);
    assert_eq!(chain_err(r), ChainError::Revoked { index: 1, status: "REVOKED".into() });

    let v = vector("frankel_sdk37_TEE_EC_2026");
    let r = verifier(v.policy()).verify(
        &v.chain,
        &v.leaf_key(T_2026_09_10),
        &v.challenge(),
        &revoked_fixture(),
        T_2026_09_10,
    );
    assert_eq!(chain_err(r), ChainError::Revoked { index: 1, status: "SUSPENDED".into() });
}

// ---------------------------------------------------------------------------------------------
// Negative: policy.
// ---------------------------------------------------------------------------------------------

#[test]
fn wrong_challenge_is_rejected() {
    let v = vector("caiman_sdk36_TEE_EC_RKP");
    let mut challenge = v.challenge();
    challenge[0] ^= 1;
    let r = verifier(v.policy()).verify(&v.chain, &v.leaf_key(T_2025_09_28), &challenge, &no_revocations(), T_2025_09_28);
    assert_eq!(policy_err(r), PolicyError::ChallengeMismatch);
    let r = verifier(v.policy()).verify(&v.chain, &v.leaf_key(T_2025_09_28), &[], &no_revocations(), T_2025_09_28);
    assert_eq!(policy_err(r), PolicyError::ChallengeMismatch);
}

#[test]
fn wrong_package_is_rejected() {
    let v = vector("caiman_sdk36_TEE_EC_RKP");
    let mut policy = v.policy();
    policy.package_name = "xyz.headsdown".into();
    let r = verifier(policy).verify(&v.chain, &v.leaf_key(T_2025_09_28), &v.challenge(), &no_revocations(), T_2025_09_28);
    assert_eq!(policy_err(r), PolicyError::WrongPackage);
}

#[test]
fn wrong_signing_certificate_is_rejected_and_debug_digest_is_labelled() {
    let v = vector("caiman_sdk36_TEE_EC_RKP");
    let key = v.leaf_key(T_2025_09_28);
    let mut policy = v.policy();
    policy.release_digests = vec![[0xAB; 32]];
    let r = verifier(policy.clone()).verify(&v.chain, &key, &v.challenge(), &no_revocations(), T_2025_09_28);
    assert_eq!(policy_err(r), PolicyError::WrongSigner);

    policy.debug_digests = vec![v.digest()];
    let s = verifier(policy).verify(&v.chain, &key, &v.challenge(), &no_revocations(), T_2025_09_28).unwrap();
    assert_eq!(s.signer, hd_registrar::attest::SignerKind::Debug);
}

#[test]
fn submitted_pubkey_must_equal_the_leaf() {
    let v = vector("caiman_sdk36_TEE_EC_RKP");
    let mut key = v.leaf_key(T_2025_09_28);
    key[0] ^= 0x01; // 02 <-> 03: the other point with the same x
    let r = verifier(v.policy()).verify(&v.chain, &key, &v.challenge(), &no_revocations(), T_2025_09_28);
    assert_eq!(policy_err(r), PolicyError::PubkeyMismatch);

    let other = vector("caiman_sdk36_SB_EC_RKP").leaf_key(T_2025_09_28);
    let r = verifier(v.policy()).verify(&v.chain, &other, &v.challenge(), &no_revocations(), T_2025_09_28);
    assert_eq!(policy_err(r), PolicyError::PubkeyMismatch);
}

#[test]
fn software_level_key_is_rejected_or_level_0_per_policy() {
    // A real software-attested chain. Only to reach the security-level check, this test trusts
    // the AOSP software root (production never does: see chain_to_unknown_root_is_rejected).
    let v = vector("marlin_sdk29_SOFTWARE_EC");
    let mut anchors = TrustAnchors::default();
    anchors.add_pem("aosp-software-root", &read("chains/android_software_attestation_roots.pem")).unwrap();
    let key = verify_chain(&v.chain, &anchors, &no_revocations(), T_2020_01_01).unwrap().leaf_p256_compressed.unwrap();

    let mut policy = v.policy();
    let reject = Verifier { anchors: anchors.clone(), policy: policy.clone() };
    let r = reject.verify(&v.chain, &key, &v.challenge(), &no_revocations(), T_2020_01_01);
    assert_eq!(policy_err(r), PolicyError::SoftwareKey);

    policy.software_keys = DowngradePolicy::Level0;
    let level0 = Verifier { anchors, policy };
    let s = level0.verify(&v.chain, &key, &v.challenge(), &no_revocations(), T_2020_01_01).unwrap();
    assert_eq!(s.level, 0);
    assert_eq!(s.downgrades, vec!["software_key"]);
}

#[test]
fn unlocked_bootloader_is_rejected_or_level_0_per_policy() {
    let v = vector("blueline_sdk28_TEE_EC_NONE");
    let key = v.leaf_key(T_TODAY);
    let r = verifier(v.policy()).verify(&v.chain, &key, &v.challenge(), &no_revocations(), T_TODAY);
    assert_eq!(policy_err(r), PolicyError::DeviceNotLocked);

    let mut policy = v.policy();
    policy.unlocked_devices = DowngradePolicy::Level0;
    let s = verifier(policy).verify(&v.chain, &key, &v.challenge(), &no_revocations(), T_TODAY).unwrap();
    assert_eq!((s.level, s.device_locked), (0, Some(false)));
    assert_eq!(s.downgrades, vec!["device_not_locked"]);
}

#[test]
fn non_p256_leaf_is_rejected() {
    let v = vector("akita_sdk34_SB_RSA_NONE");
    let vc = verify_chain(&v.chain, &google(), &no_revocations(), T_2024_09_20).unwrap();
    assert!(vc.leaf_p256_compressed.is_none());
    let r = verifier(v.policy()).verify(&v.chain, &[2; 33], &v.challenge(), &no_revocations(), T_2024_09_20);
    assert_eq!(policy_err(r), PolicyError::LeafNotP256);
}
