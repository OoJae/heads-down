//! Trust anchors: Google's hardware attestation root **keys**.
//!
//! The PEMs in `registrar/roots/` are compiled in (no runtime download, no filesystem trust
//! store). They reduce to two public keys, whose SPKI SHA-256 digests are pinned below: an
//! edit to a PEM that changes a key fails [`TrustAnchors::google`] at startup and the tests.
//! See `roots/SOURCES.md` for provenance.

use sha2::{Digest, Sha256};
use x509_parser::pem::Pem;
use x509_parser::prelude::*;

/// SPKI SHA-256 of the RSA-4096 "Google Hardware Attestation Root" key
/// (subject `serialNumber=f92009e853b6b045`; certificates issued 2016, 2019, 2021, 2022).
pub const GOOGLE_RSA_ROOT_SPKI_SHA256: &str = "feb2ea7551ee316ed4bb443c8293b884dbfdea40b603ee3e4f4a897e4580fbae";
/// SPKI SHA-256 of the ECDSA P-384 "Key Attestation CA1" key (signing RKP chains from 2026-02-01).
pub const GOOGLE_P384_ROOT_SPKI_SHA256: &str = "3ee44512a1af2beb39c889490c60ea3f82e43f5d5a5532f5ab9419f676cd07ec";

const GOOGLE_ROOT_PEMS: &[(&str, &str)] = &[
    (
        "google-rsa4096-2022",
        include_str!("../../roots/google_hardware_attestation_root_rsa4096_2022.pem"),
    ),
    ("google-key-attestation-ca1-p384", include_str!("../../roots/google_key_attestation_ca1_p384_2025.pem")),
    (
        "google-rsa4096-2016",
        include_str!("../../roots/google_hardware_attestation_root_rsa4096_2016.pem"),
    ),
    (
        "google-rsa4096-2019",
        include_str!("../../roots/google_hardware_attestation_root_rsa4096_2019.pem"),
    ),
    (
        "google-rsa4096-2021",
        include_str!("../../roots/google_hardware_attestation_root_rsa4096_2021.pem"),
    ),
];

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct TrustAnchor {
    pub name: String,
    /// DER `SubjectPublicKeyInfo`.
    pub spki_der: Vec<u8>,
    pub spki_sha256: String,
}

#[derive(Clone, Debug, Default)]
pub struct TrustAnchors {
    anchors: Vec<TrustAnchor>,
}

#[derive(Debug, thiserror::Error)]
pub enum RootsError {
    #[error("invalid PEM for trust anchor {0}")]
    Pem(String),
    #[error("invalid certificate for trust anchor {0}")]
    Cert(String),
    #[error("trust anchor key set does not match the pinned Google root keys")]
    PinMismatch,
}

impl TrustAnchors {
    /// Google's production roots, checked against the pinned SPKI digests.
    pub fn google() -> Result<Self, RootsError> {
        let mut anchors = Self::default();
        for (name, pem) in GOOGLE_ROOT_PEMS {
            anchors.add_pem(name, pem)?;
        }
        let mut digests: Vec<&str> = anchors.anchors.iter().map(|a| a.spki_sha256.as_str()).collect();
        digests.sort_unstable();
        let mut pinned = vec![GOOGLE_RSA_ROOT_SPKI_SHA256, GOOGLE_P384_ROOT_SPKI_SHA256];
        pinned.sort_unstable();
        if digests != pinned {
            return Err(RootsError::PinMismatch);
        }
        Ok(anchors)
    }

    /// Adds every certificate in `pem` as an anchor (deduplicated by key). Used for Google's
    /// roots and, in tests only, for synthetic roots.
    pub fn add_pem(&mut self, name: &str, pem: &str) -> Result<(), RootsError> {
        let mut found = false;
        for p in Pem::iter_from_buffer(pem.as_bytes()) {
            let p = p.map_err(|_| RootsError::Pem(name.to_owned()))?;
            self.add_der(name, &p.contents)?;
            found = true;
        }
        if found {
            Ok(())
        } else {
            Err(RootsError::Pem(name.to_owned()))
        }
    }

    pub fn add_der(&mut self, name: &str, der: &[u8]) -> Result<(), RootsError> {
        let (rest, cert) = X509Certificate::from_der(der).map_err(|_| RootsError::Cert(name.to_owned()))?;
        if !rest.is_empty() {
            return Err(RootsError::Cert(name.to_owned()));
        }
        let spki = cert.public_key().raw.to_vec();
        if self.anchors.iter().any(|a| a.spki_der == spki) {
            return Ok(());
        }
        let spki_sha256 = hex::encode(Sha256::digest(&spki));
        self.anchors.push(TrustAnchor { name: name.to_owned(), spki_der: spki, spki_sha256 });
        Ok(())
    }

    /// The anchor whose key equals `spki_der`, if any.
    pub fn find(&self, spki_der: &[u8]) -> Option<&TrustAnchor> {
        self.anchors.iter().find(|a| a.spki_der == spki_der)
    }

    pub fn iter(&self) -> impl Iterator<Item = &TrustAnchor> {
        self.anchors.iter()
    }

    pub fn len(&self) -> usize {
        self.anchors.len()
    }

    pub fn is_empty(&self) -> bool {
        self.anchors.is_empty()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn five_google_certificates_reduce_to_two_pinned_keys() {
        let a = TrustAnchors::google().unwrap();
        assert_eq!(a.len(), 2);
        let digests: Vec<_> = a.iter().map(|x| x.spki_sha256.clone()).collect();
        assert!(digests.contains(&GOOGLE_RSA_ROOT_SPKI_SHA256.to_string()));
        assert!(digests.contains(&GOOGLE_P384_ROOT_SPKI_SHA256.to_string()));
    }

    #[test]
    fn software_root_is_not_a_google_anchor() {
        let google = TrustAnchors::google().unwrap();
        let mut sw = TrustAnchors::default();
        sw.add_pem("sw", include_str!("../../testdata/chains/android_software_attestation_roots.pem")).unwrap();
        assert_eq!(sw.len(), 2);
        for anchor in sw.iter() {
            assert!(google.find(&anchor.spki_der).is_none());
        }
    }

    #[test]
    fn garbage_is_rejected() {
        let mut a = TrustAnchors::default();
        assert!(a.add_pem("x", "not a pem").is_err());
        assert!(a.add_der("x", &[0x30, 0x03, 0x02, 0x01, 0x01]).is_err());
    }
}
