//! Android Key Attestation verification for rig keys.
//!
//! Flow: `GET /attest/challenge` issues a nonce bound to the SIWS-authenticated authority and
//! returns `challenge = SHA-256("HDattest" || authority(32) || nonce(16))`. The phone generates
//! its Keystore P-256 key with that challenge (`setAttestationChallenge`) and posts the chain.
//! [`Verifier::verify`] then checks the chain up to Google's root keys ([`chain`]) and applies
//! the Heads Down policy ([`policy`]).
//!
//! Why the challenge does not contain the P-256 key: Android fixes the attestation challenge
//! when the key is generated, before its public key exists, so a challenge cannot commit to its
//! own key. The key is bound instead by the leaf certificate itself: the leaf's
//! SubjectPublicKeyInfo *is* the attested key, and it must equal the submitted key.

pub mod chain;
pub mod keydesc;
pub mod policy;
pub mod revocation;
pub mod roots;

use serde::Serialize;
use sha2::{Digest, Sha256};

pub use chain::{ChainError, Provisioning, VerifiedChain};
pub use policy::{Assessment, AttestationPolicy, DowngradePolicy, PolicyError, SignerKind};
pub use revocation::{RevocationList, StatusListProvider};
pub use roots::TrustAnchors;

use keydesc::{SecurityLevel, VerifiedBootState};

/// Domain separator of the attestation challenge preimage.
pub const CHALLENGE_DOMAIN: &[u8; 8] = b"HDattest";

/// `SHA-256("HDattest" || authority(32) || nonce(16))`.
pub fn challenge(authority: &[u8; 32], nonce: &[u8; crate::nonce::NONCE_BYTES]) -> [u8; 32] {
    let mut h = Sha256::new();
    h.update(CHALLENGE_DOMAIN);
    h.update(authority);
    h.update(nonce);
    h.finalize().into()
}

#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum AttestError {
    #[error(transparent)]
    Chain(#[from] ChainError),
    #[error(transparent)]
    Policy(#[from] PolicyError),
}

impl AttestError {
    pub fn code(&self) -> &'static str {
        match self {
            Self::Chain(e) => e.code(),
            Self::Policy(e) => e.code(),
        }
    }
}

/// What the registrar learned, for the response and the transparency log. Contains no device
/// identifiers (Heads Down never requests device-ID attestation).
#[derive(Clone, Debug, Serialize)]
pub struct AttestationSummary {
    pub level: u8,
    pub signer: SignerKind,
    pub downgrades: Vec<&'static str>,
    pub provisioning: Provisioning,
    pub anchor: String,
    pub attestation_version: i64,
    pub attestation_security_level: SecurityLevel,
    pub keymint_security_level: SecurityLevel,
    pub verified_boot_state: Option<VerifiedBootState>,
    pub device_locked: Option<bool>,
    pub os_patch_level: Option<i64>,
    /// Device-identifier attestation tags (brand, model, IMEI, ...) were present. Heads Down never
    /// requests them; `true` means the client asked for more than it should have.
    pub device_ids_present: bool,
    pub serials: Vec<String>,
}

pub struct Verifier {
    pub anchors: TrustAnchors,
    pub policy: AttestationPolicy,
}

impl Verifier {
    pub fn verify(
        &self,
        chain_der: &[Vec<u8>],
        submitted_pubkey: &[u8; 33],
        expected_challenge: &[u8],
        revocations: &RevocationList,
        now: i64,
    ) -> Result<AttestationSummary, AttestError> {
        let chain = chain::verify_chain(chain_der, &self.anchors, revocations, now)?;
        let assessment = policy::assess(&chain, submitted_pubkey, expected_challenge, &self.policy)?;
        let kd = &chain.key_description;
        let rot = kd.hardware_enforced.root_of_trust.as_ref();
        Ok(AttestationSummary {
            level: assessment.level,
            signer: assessment.signer,
            downgrades: assessment.downgrades,
            provisioning: chain.provisioning,
            anchor: chain.anchor_name,
            attestation_version: kd.attestation_version,
            attestation_security_level: kd.attestation_security_level,
            keymint_security_level: kd.keymint_security_level,
            verified_boot_state: rot.map(|r| r.verified_boot_state),
            device_locked: rot.map(|r| r.device_locked),
            os_patch_level: kd.hardware_enforced.os_patch_level,
            device_ids_present: kd.hardware_enforced.has_device_ids || kd.software_enforced.has_device_ids,
            serials: chain.serials,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn challenge_preimage_layout() {
        let authority = [0x11u8; 32];
        let nonce = [0x22u8; 16];
        let mut preimage = Vec::new();
        preimage.extend_from_slice(b"HDattest");
        preimage.extend_from_slice(&authority);
        preimage.extend_from_slice(&nonce);
        assert_eq!(preimage.len(), 56);
        let expected: [u8; 32] = Sha256::digest(&preimage).into();
        assert_eq!(challenge(&authority, &nonce), expected);
        assert_ne!(challenge(&[0x12; 32], &nonce), expected);
        assert_ne!(challenge(&authority, &[0x23; 16]), expected);
    }
}
