//! Heads Down's policy over a cryptographically verified attestation chain.
//!
//! Output is the `Rig.attestation_level` the registrar will vouch for:
//! `2` StrongBox, `1` TEE, `0` "unattested" (only when a downgrade policy allows it).
//!
//! Checks, in order (the first failure wins):
//! 1. The leaf key is EC P-256 and equals the submitted compressed key.
//! 2. `attestationChallenge` equals the server-derived challenge (constant-time compare).
//! 3. `attestationApplicationId`: exactly one package, `xyz.headsdown` (configurable), and every
//!    signing-certificate digest is an allowed release digest (or a configured debug digest).
//! 4. Security level: `attestationSecurityLevel == keyMintSecurityLevel`; Software is rejected or
//!    downgraded to level 0 per [`DowngradePolicy`]; the level must agree with what the chain's
//!    certificate names claim (an RKP `O=TEE` attestation key cannot vouch for StrongBox).
//! 5. For hardware levels, from `hardwareEnforced`: `origin == GENERATED` (an imported key
//!    existed outside secure hardware), `algorithm == EC`, `ecCurve == P_256`, `keySize` 256 if
//!    present, purposes include SIGN and are within {SIGN, VERIFY}.
//! 6. `rootOfTrust` in `hardwareEnforced`, with `verifiedBootState == Verified` and
//!    `deviceLocked == true`; otherwise rejected or downgraded to level 0 per policy.

use subtle::ConstantTimeEq;

use super::chain::VerifiedChain;
use super::keydesc::{
    AuthorizationList, SecurityLevel, VerifiedBootState, ALGORITHM_EC, EC_CURVE_P256, ORIGIN_GENERATED, PURPOSE_SIGN,
    PURPOSE_VERIFY,
};

#[derive(Clone, Copy, Debug, PartialEq, Eq, serde::Deserialize, serde::Serialize)]
#[serde(rename_all = "snake_case")]
pub enum DowngradePolicy {
    /// Refuse to vouch at all.
    Reject,
    /// Vouch at level 0 ("unattested"): the key is bound to the app and authority, but the
    /// program must treat it like an unattested rig.
    Level0,
}

#[derive(Clone, Debug)]
pub struct AttestationPolicy {
    pub package_name: String,
    /// SHA-256 digests of the release signing certificate(s).
    pub release_digests: Vec<[u8; 32]>,
    /// Development-only digests (debug keystore). Empty in production.
    pub debug_digests: Vec<[u8; 32]>,
    pub software_keys: DowngradePolicy,
    pub unlocked_devices: DowngradePolicy,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, serde::Serialize)]
#[serde(rename_all = "snake_case")]
pub enum SignerKind {
    Release,
    Debug,
}

#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize)]
pub struct Assessment {
    /// 0 unattested, 1 TEE, 2 StrongBox.
    pub level: u8,
    pub signer: SignerKind,
    /// Why the level was lowered to 0, if it was.
    pub downgrades: Vec<&'static str>,
}

#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum PolicyError {
    #[error("leaf key is not an EC P-256 key")]
    LeafNotP256,
    #[error("submitted public key does not match the attested key")]
    PubkeyMismatch,
    #[error("attestation challenge does not match")]
    ChallengeMismatch,
    #[error("attestationApplicationId missing or inconsistent")]
    MissingApplicationId,
    #[error("wrong package")]
    WrongPackage,
    #[error("app signing certificate not allowed")]
    WrongSigner,
    #[error("software-backed key")]
    SoftwareKey,
    #[error("attestation and KeyMint security levels disagree with each other or with the chain")]
    SecurityLevelMismatch,
    #[error("key was imported, not generated in secure hardware")]
    ImportedKey,
    #[error("key is not an EC P-256 signing key")]
    WrongKeyParameters,
    #[error("rootOfTrust missing from hardware-enforced list")]
    MissingRootOfTrust,
    #[error("device bootloader unlocked or boot not verified")]
    DeviceNotLocked,
}

impl PolicyError {
    pub fn code(&self) -> &'static str {
        match self {
            Self::LeafNotP256 => "leaf_not_p256",
            Self::PubkeyMismatch => "pubkey_mismatch",
            Self::ChallengeMismatch => "challenge_mismatch",
            Self::MissingApplicationId => "application_id_missing",
            Self::WrongPackage => "wrong_package",
            Self::WrongSigner => "wrong_signer",
            Self::SoftwareKey => "software_key",
            Self::SecurityLevelMismatch => "security_level_mismatch",
            Self::ImportedKey => "imported_key",
            Self::WrongKeyParameters => "wrong_key_parameters",
            Self::MissingRootOfTrust => "root_of_trust_missing",
            Self::DeviceNotLocked => "device_not_locked",
        }
    }
}

fn key_parameters_ok(list: &AuthorizationList) -> bool {
    let purposes_ok = list
        .purposes
        .as_ref()
        .is_some_and(|p| p.contains(&PURPOSE_SIGN) && p.iter().all(|x| *x == PURPOSE_SIGN || *x == PURPOSE_VERIFY));
    purposes_ok
        && list.algorithm == Some(ALGORITHM_EC)
        && list.ec_curve == Some(EC_CURVE_P256)
        && list.key_size.is_none_or(|s| s == 256)
}

pub fn assess(
    chain: &VerifiedChain,
    submitted_pubkey: &[u8; 33],
    expected_challenge: &[u8],
    policy: &AttestationPolicy,
) -> Result<Assessment, PolicyError> {
    let kd = &chain.key_description;

    // 1. Key binding.
    let leaf = chain.leaf_p256_compressed.ok_or(PolicyError::LeafNotP256)?;
    if leaf != *submitted_pubkey {
        return Err(PolicyError::PubkeyMismatch);
    }

    // 2. Freshness / binding to (authority, nonce).
    if !bool::from(kd.attestation_challenge.as_slice().ct_eq(expected_challenge)) {
        return Err(PolicyError::ChallengeMismatch);
    }

    // 3. The app.
    let app =
        kd.application_id().map_err(|_| PolicyError::MissingApplicationId)?.ok_or(PolicyError::MissingApplicationId)?;
    match app.packages.as_slice() {
        [only] if only.name == policy.package_name => {}
        _ => return Err(PolicyError::WrongPackage),
    }
    let is_release = |d: &Vec<u8>| policy.release_digests.iter().any(|r| r.as_slice() == d.as_slice());
    let is_debug = |d: &Vec<u8>| policy.debug_digests.iter().any(|r| r.as_slice() == d.as_slice());
    if app.signature_digests.is_empty() || !app.signature_digests.iter().all(|d| is_release(d) || is_debug(d)) {
        return Err(PolicyError::WrongSigner);
    }
    let signer = if app.signature_digests.iter().all(is_release) { SignerKind::Release } else { SignerKind::Debug };

    let mut downgrades = Vec::new();

    // 4. Security level.
    let (att, km) = (kd.attestation_security_level, kd.keymint_security_level);
    if att == SecurityLevel::Software || km == SecurityLevel::Software {
        return match policy.software_keys {
            DowngradePolicy::Reject => Err(PolicyError::SoftwareKey),
            DowngradePolicy::Level0 => {
                downgrades.push("software_key");
                Ok(Assessment { level: 0, signer, downgrades })
            }
        };
    }
    if att != km {
        return Err(PolicyError::SecurityLevelMismatch);
    }
    match (km, chain.chain_security_level) {
        (_, None) if km == SecurityLevel::TrustedEnvironment => {}
        (level, Some(claimed)) if level == claimed => {}
        _ => return Err(PolicyError::SecurityLevelMismatch),
    }

    // 5. Hardware-enforced key properties.
    let hw = &kd.hardware_enforced;
    if hw.origin != Some(ORIGIN_GENERATED) {
        return Err(PolicyError::ImportedKey);
    }
    if !key_parameters_ok(hw) {
        return Err(PolicyError::WrongKeyParameters);
    }

    // 6. Device state.
    let rot = hw.root_of_trust.as_ref().ok_or(PolicyError::MissingRootOfTrust)?;
    if rot.verified_boot_state != VerifiedBootState::Verified || !rot.device_locked {
        return match policy.unlocked_devices {
            DowngradePolicy::Reject => Err(PolicyError::DeviceNotLocked),
            DowngradePolicy::Level0 => {
                downgrades.push("device_not_locked");
                Ok(Assessment { level: 0, signer, downgrades })
            }
        };
    }

    let level = match km {
        SecurityLevel::StrongBox => 2,
        _ => 1,
    };
    Ok(Assessment { level, signer, downgrades })
}
