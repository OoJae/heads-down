//! X.509 chain verification for Android Key Attestation.
//!
//! Mirrors Google's reference validator (`android/keyattestation`,
//! `provider/KeyAttestationCertPathValidator.kt` and `KeyAttestationCertPath.kt`), which is
//! the behaviour Google recommends over generic PKIX:
//!
//! 1. The chain is **leaf first**, the last certificate is self-issued, and its **public key**
//!    must equal a trust anchor (Google's RSA or P-384 root key). Anchors are keys, not
//!    certificates, because the 2016 RSA root certificate expired but its key is still valid.
//! 2. The shape is fixed by the provisioning method, detected from the certificate directly
//!    under the root: factory (its subject has a `serialNumber`) = 4 certificates; Remote Key
//!    Provisioning (`CN=Droid CA2, O=Google LLC`) = 5; anything else = 3. A chain of any other
//!    length is rejected, which blocks chain-extension attacks (an attested key that signs a
//!    forged "leaf" of its own).
//! 3. Every certificate is signed by the next one's key (RSA PKCS#1 v1.5 with SHA-256/384/512,
//!    ECDSA P-256/P-384 with SHA-256/384, Ed25519; nothing weaker), the outer and TBS signature
//!    algorithms agree, and issuer/subject names chain byte for byte.
//! 4. Validity: never checked on the leaf (its dates come from the device clock) or on the
//!    anchor. Intermediates must not be "not yet valid"; an expired intermediate fails, except
//!    in factory-provisioned chains, whose keys cannot be rotated (Google's rule). RKP chains
//!    are short-lived by design, so their expiry is enforced.
//! 5. The KeyDescription extension appears exactly once, in the leaf, and in no other
//!    certificate.
//! 6. No serial number in the chain is on Google's status list.

use std::fmt;

use ring::signature::{self, UnparsedPublicKey, VerificationAlgorithm};
use x509_parser::prelude::*;

use super::keydesc::{self, KeyDescription, KeyDescriptionError, SecurityLevel};
use super::revocation::RevocationList;
use super::roots::TrustAnchors;

pub const MAX_CHAIN_LEN: usize = 6;
pub const MAX_CERT_BYTES: usize = 16 * 1024;

const OID_SERIAL_NUMBER: &str = "2.5.4.5";
const OID_EC_PUBLIC_KEY: &str = "1.2.840.10045.2.1";
const OID_RSA_ENCRYPTION: &str = "1.2.840.113549.1.1.1";
const OID_ED25519: &str = "1.3.101.112";
const OID_P256: &str = "1.2.840.10045.3.1.7";
const OID_P384: &str = "1.3.132.0.34";
const OID_SHA256_RSA: &str = "1.2.840.113549.1.1.11";
const OID_SHA384_RSA: &str = "1.2.840.113549.1.1.12";
const OID_SHA512_RSA: &str = "1.2.840.113549.1.1.13";
const OID_ECDSA_SHA256: &str = "1.2.840.10045.4.3.2";
const OID_ECDSA_SHA384: &str = "1.2.840.10045.4.3.3";

#[derive(Clone, Copy, Debug, PartialEq, Eq, serde::Serialize)]
#[serde(rename_all = "snake_case")]
pub enum Provisioning {
    Factory,
    RemoteKeyProvisioning,
    Unknown,
}

impl fmt::Display for Provisioning {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(match self {
            Self::Factory => "factory",
            Self::RemoteKeyProvisioning => "rkp",
            Self::Unknown => "unknown",
        })
    }
}

#[derive(Clone, Debug)]
pub struct VerifiedChain {
    pub key_description: KeyDescription,
    /// The leaf key in SEC1 compressed form, when it is an EC P-256 key.
    pub leaf_p256_compressed: Option<[u8; 33]>,
    pub provisioning: Provisioning,
    /// The security level the chain's certificate names claim: RKP attestation certificates
    /// carry `O=TEE` or `O=StrongBox`; factory StrongBox chains contain "StrongBox" (CTS rule).
    /// `None` when the chain does not say (factory TEE chains).
    pub chain_security_level: Option<SecurityLevel>,
    pub anchor_name: String,
    /// Serial numbers, leaf first, unpadded lowercase hex.
    pub serials: Vec<String>,
}

/// `index` is the position in the submitted chain (0 = leaf).
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum ChainError {
    #[error("chain must have 2..={MAX_CHAIN_LEN} certificates")]
    Length,
    #[error("certificate {0} is too large")]
    CertTooLarge(usize),
    #[error("certificate {0} does not parse")]
    Parse(usize),
    #[error("trailing bytes after certificate {0}")]
    TrailingData(usize),
    #[error("last certificate is not self-issued")]
    RootNotSelfIssued,
    #[error("chain does not end in a trusted Google root key")]
    UnknownRoot,
    #[error("{provisioning} chain must have {expected} certificates, got {got}")]
    Shape { provisioning: Provisioning, expected: usize, got: usize },
    #[error("issuer of certificate {0} does not match the next subject")]
    NameChaining(usize),
    #[error("certificate {0}: outer and TBS signature algorithms differ")]
    AlgorithmMismatch(usize),
    #[error("certificate {0}: unsupported signature algorithm or key")]
    UnsupportedAlgorithm(usize),
    #[error("certificate {0}: signature does not verify")]
    BadSignature(usize),
    #[error("certificate {0} is not yet valid")]
    NotYetValid(usize),
    #[error("certificate {0} has expired")]
    Expired(usize),
    #[error("certificate {index} is revoked ({status})")]
    Revoked { index: usize, status: String },
    #[error("leaf has no KeyDescription extension")]
    MissingKeyDescription,
    #[error("leaf has more than one KeyDescription extension")]
    DuplicateKeyDescription,
    #[error("certificate {0} is not the leaf but carries a KeyDescription extension")]
    KeyDescriptionOutsideLeaf(usize),
    #[error("KeyDescription malformed: {0}")]
    KeyDescription(#[from] KeyDescriptionError),
}

impl ChainError {
    pub fn code(&self) -> &'static str {
        match self {
            Self::Length => "chain_length",
            Self::CertTooLarge(_) => "cert_too_large",
            Self::Parse(_) | Self::TrailingData(_) => "cert_malformed",
            Self::RootNotSelfIssued | Self::UnknownRoot => "unknown_root",
            Self::Shape { .. } => "chain_shape",
            Self::NameChaining(_) => "name_chaining",
            Self::AlgorithmMismatch(_) | Self::UnsupportedAlgorithm(_) => "unsupported_algorithm",
            Self::BadSignature(_) => "bad_signature",
            Self::NotYetValid(_) => "cert_not_yet_valid",
            Self::Expired(_) => "cert_expired",
            Self::Revoked { .. } => "revoked",
            Self::MissingKeyDescription | Self::DuplicateKeyDescription => "key_description_missing",
            Self::KeyDescriptionOutsideLeaf(_) => "key_description_outside_leaf",
            Self::KeyDescription(_) => "key_description_malformed",
        }
    }
}

fn attr_str<'a>(name: &'a X509Name<'_>, oid: &str) -> Option<&'a str> {
    name.iter_attributes().find(|a| a.attr_type().to_id_string() == oid).and_then(|a| a.as_str().ok())
}

fn provisioning_of(under_root: &X509Certificate<'_>) -> Provisioning {
    let subject = under_root.subject();
    if attr_str(subject, OID_SERIAL_NUMBER).is_some() {
        Provisioning::Factory
    } else if attr_str(subject, "2.5.4.3") == Some("Droid CA2") && attr_str(subject, "2.5.4.10") == Some("Google LLC") {
        Provisioning::RemoteKeyProvisioning
    } else {
        Provisioning::Unknown
    }
}

/// `certs` leaf first. Mirrors `KeyAttestationCertPath.securityLevel()`.
fn chain_security_level(certs: &[X509Certificate<'_>], provisioning: Provisioning) -> Option<SecurityLevel> {
    let attestation = certs.get(1)?;
    match provisioning {
        Provisioning::RemoteKeyProvisioning => match attr_str(attestation.subject(), "2.5.4.10") {
            Some("TEE") => Some(SecurityLevel::TrustedEnvironment),
            Some("StrongBox") => Some(SecurityLevel::StrongBox),
            _ => Some(SecurityLevel::Software),
        },
        Provisioning::Factory => {
            let intermediate = certs.get(certs.len().checked_sub(2)?)?;
            [attestation, intermediate]
                .iter()
                .any(|c| c.subject().to_string().to_ascii_lowercase().contains("strongbox"))
                .then_some(SecurityLevel::StrongBox)
        }
        Provisioning::Unknown => None,
    }
}

fn ec_curve(spki: &SubjectPublicKeyInfo<'_>) -> Option<String> {
    spki.algorithm.parameters.as_ref().and_then(|p| p.as_oid().ok()).map(|o| o.to_id_string())
}

/// Picks the ring algorithm for (signature algorithm, issuer key). Anything not listed,
/// including SHA-1 and RSA-PSS, is unsupported.
fn verification_algorithm(
    sig_oid: &str,
    issuer: &SubjectPublicKeyInfo<'_>,
) -> Option<&'static dyn VerificationAlgorithm> {
    let key_oid = issuer.algorithm.algorithm.to_id_string();
    let curve = ec_curve(issuer);
    Some(match (sig_oid, key_oid.as_str(), curve.as_deref()) {
        (OID_SHA256_RSA, OID_RSA_ENCRYPTION, _) => &signature::RSA_PKCS1_2048_8192_SHA256,
        (OID_SHA384_RSA, OID_RSA_ENCRYPTION, _) => &signature::RSA_PKCS1_2048_8192_SHA384,
        (OID_SHA512_RSA, OID_RSA_ENCRYPTION, _) => &signature::RSA_PKCS1_2048_8192_SHA512,
        (OID_ECDSA_SHA256, OID_EC_PUBLIC_KEY, Some(OID_P256)) => &signature::ECDSA_P256_SHA256_ASN1,
        (OID_ECDSA_SHA384, OID_EC_PUBLIC_KEY, Some(OID_P256)) => &signature::ECDSA_P256_SHA384_ASN1,
        (OID_ECDSA_SHA256, OID_EC_PUBLIC_KEY, Some(OID_P384)) => &signature::ECDSA_P384_SHA256_ASN1,
        (OID_ECDSA_SHA384, OID_EC_PUBLIC_KEY, Some(OID_P384)) => &signature::ECDSA_P384_SHA384_ASN1,
        (OID_ED25519, OID_ED25519, _) => &signature::ED25519,
        _ => return None,
    })
}

fn verify_signature(
    index: usize,
    cert: &X509Certificate<'_>,
    issuer: &SubjectPublicKeyInfo<'_>,
) -> Result<(), ChainError> {
    if cert.signature_algorithm != cert.tbs_certificate.signature {
        return Err(ChainError::AlgorithmMismatch(index));
    }
    let sig_oid = cert.signature_algorithm.algorithm.to_id_string();
    let alg = verification_algorithm(&sig_oid, issuer).ok_or(ChainError::UnsupportedAlgorithm(index))?;
    if cert.signature_value.unused_bits != 0 {
        return Err(ChainError::BadSignature(index));
    }
    UnparsedPublicKey::new(alg, issuer.subject_public_key.data.as_ref())
        .verify(cert.tbs_certificate.as_ref(), cert.signature_value.data.as_ref())
        .map_err(|_| ChainError::BadSignature(index))
}

fn leaf_p256(cert: &X509Certificate<'_>) -> Option<[u8; 33]> {
    let spki = cert.public_key();
    if spki.algorithm.algorithm.to_id_string() != OID_EC_PUBLIC_KEY || ec_curve(spki).as_deref() != Some(OID_P256) {
        return None;
    }
    let key = p256::PublicKey::from_sec1_bytes(spki.subject_public_key.data.as_ref()).ok()?;
    let point = p256::elliptic_curve::sec1::ToEncodedPoint::to_encoded_point(&key, true);
    point.as_bytes().try_into().ok()
}

fn serial_hex(cert: &X509Certificate<'_>) -> String {
    cert.tbs_certificate.serial.to_str_radix(16)
}

fn key_description_count(cert: &X509Certificate<'_>) -> usize {
    cert.extensions().iter().filter(|e| e.oid.to_id_string() == keydesc::KEY_DESCRIPTION_OID).count()
}

/// Verifies a leaf-first DER chain. `now` is Unix seconds.
pub fn verify_chain(
    chain_der: &[Vec<u8>],
    anchors: &TrustAnchors,
    revocations: &RevocationList,
    now: i64,
) -> Result<VerifiedChain, ChainError> {
    if chain_der.len() < 2 || chain_der.len() > MAX_CHAIN_LEN {
        return Err(ChainError::Length);
    }
    let mut certs = Vec::with_capacity(chain_der.len());
    for (i, der) in chain_der.iter().enumerate() {
        if der.len() > MAX_CERT_BYTES {
            return Err(ChainError::CertTooLarge(i));
        }
        let (rest, cert) = X509Certificate::from_der(der).map_err(|_| ChainError::Parse(i))?;
        if !rest.is_empty() {
            return Err(ChainError::TrailingData(i));
        }
        certs.push(cert);
    }
    let n = certs.len();
    let root_index = n.checked_sub(1).ok_or(ChainError::Length)?;
    let root = certs.get(root_index).ok_or(ChainError::Length)?;

    // 1. Anchor.
    if root.issuer().as_raw() != root.subject().as_raw() {
        return Err(ChainError::RootNotSelfIssued);
    }
    let anchor = anchors.find(root.public_key().raw).ok_or(ChainError::UnknownRoot)?;

    // 2. Shape.
    let under_root_index = n.checked_sub(2).ok_or(ChainError::Length)?;
    let under_root = certs.get(under_root_index).ok_or(ChainError::Length)?;
    let provisioning = provisioning_of(under_root);
    let expected = match provisioning {
        Provisioning::RemoteKeyProvisioning => 5,
        Provisioning::Factory => 4,
        Provisioning::Unknown if n == 4 => 4, // Google: "certificatesWithAnchor.size == 4" => factory steps
        Provisioning::Unknown => 3,
    };
    if n != expected {
        return Err(ChainError::Shape { provisioning, expected, got: n });
    }

    // 3 + 4. Signatures, names, validity, walking from the anchor down to the leaf.
    for i in (0..root_index).rev() {
        let cert = certs.get(i).ok_or(ChainError::Length)?;
        let issuer = certs.get(i.checked_add(1).ok_or(ChainError::Length)?).ok_or(ChainError::Length)?;
        if cert.issuer().as_raw() != issuer.subject().as_raw() {
            return Err(ChainError::NameChaining(i));
        }
        // The certificate directly under the root is verified with the anchor key (equal to
        // the root certificate's key by construction above).
        verify_signature(i, cert, issuer.public_key())?;
        if i != 0 {
            let validity = cert.validity();
            if now < validity.not_before.timestamp() {
                return Err(ChainError::NotYetValid(i));
            }
            if now > validity.not_after.timestamp() && provisioning != Provisioning::Factory {
                return Err(ChainError::Expired(i));
            }
        }
    }

    // 5. KeyDescription only in the leaf.
    for (i, cert) in certs.iter().enumerate().skip(1) {
        if key_description_count(cert) != 0 {
            return Err(ChainError::KeyDescriptionOutsideLeaf(i));
        }
    }
    let leaf = certs.first().ok_or(ChainError::Length)?;
    match key_description_count(leaf) {
        0 => return Err(ChainError::MissingKeyDescription),
        1 => {}
        _ => return Err(ChainError::DuplicateKeyDescription),
    }

    // 6. Revocation (every certificate, anchor included).
    let serials: Vec<String> = certs.iter().map(serial_hex).collect();
    for (index, serial) in serials.iter().enumerate() {
        if let Some(entry) = revocations.status_of(serial) {
            return Err(ChainError::Revoked { index, status: entry.status.clone() });
        }
    }

    let ext = leaf
        .extensions()
        .iter()
        .find(|e| e.oid.to_id_string() == keydesc::KEY_DESCRIPTION_OID)
        .ok_or(ChainError::MissingKeyDescription)?;
    let key_description = keydesc::parse(ext.value)?;

    Ok(VerifiedChain {
        key_description,
        leaf_p256_compressed: leaf_p256(leaf),
        chain_security_level: chain_security_level(&certs, provisioning),
        provisioning,
        anchor_name: anchor.name.clone(),
        serials,
    })
}

/// Splits a PEM bundle into DER certificates (test vectors and CLI tooling).
pub fn pem_bundle_to_der(pem: &str) -> Result<Vec<Vec<u8>>, ChainError> {
    let mut out = Vec::new();
    for (i, p) in x509_parser::pem::Pem::iter_from_buffer(pem.as_bytes()).enumerate() {
        out.push(p.map_err(|_| ChainError::Parse(i))?.contents);
    }
    Ok(out)
}
