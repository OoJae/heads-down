//! Sign-In-With-Solana verification.
//!
//! [`verify_signed_message`] does every **stateless** check: size, UTF-8, canonical parse,
//! domain and URI binding, version, chain id, nonce shape, the time window, the address and the
//! Ed25519 signature over the exact message bytes. Only after all of that passes does the HTTP
//! layer consume the nonce (atomically), so a forged or malformed request can never burn a
//! legitimate user's nonce, and a replay of a valid one always fails at the store.

pub mod message;

use ed25519_dalek::{Signature, VerifyingKey};

use crate::clock::parse_rfc3339;
use crate::util::decode_address;
pub use message::{ParseError, SiwsMessage};

#[derive(Clone, Debug)]
pub struct SiwsPolicy {
    /// The only accepted `domain` (the app's SIWS domain, e.g. `headsdown.xyz`).
    pub domain: String,
    /// The only accepted `URI` value, e.g. `https://headsdown.xyz`.
    pub uri: String,
    /// Accepted CAIP-2 chain ids, e.g. `["solana:mainnet"]`.
    pub chains: Vec<String>,
    pub max_message_bytes: usize,
    /// How long a nonce lives; also the maximum age of `Issued At`.
    pub nonce_ttl_secs: i64,
    /// Tolerated client clock skew for `Issued At` / `Not Before`.
    pub clock_skew_secs: i64,
}

impl SiwsPolicy {
    pub fn new(domain: impl Into<String>, chains: Vec<String>) -> Self {
        let domain = domain.into();
        Self {
            uri: format!("https://{domain}"),
            domain,
            chains,
            max_message_bytes: 2048,
            nonce_ttl_secs: 600,
            clock_skew_secs: 60,
        }
    }

    /// Longest accepted `Expiration Time - Issued At`.
    pub fn max_validity_secs(&self) -> i64 {
        self.nonce_ttl_secs.saturating_add(self.clock_skew_secs)
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct VerifiedSignIn {
    pub address: String,
    pub address_bytes: [u8; 32],
    pub chain_id: String,
    pub nonce: String,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, thiserror::Error)]
pub enum SiwsError {
    #[error("sign-in message too large")]
    TooLarge,
    #[error("sign-in message is not UTF-8")]
    NotUtf8,
    #[error("sign-in message malformed: {0}")]
    Parse(#[from] ParseError),
    #[error("domain does not match")]
    DomainMismatch,
    #[error("URI does not match")]
    UriMismatch,
    #[error("unsupported SIWS version")]
    UnsupportedVersion,
    #[error("chain id not allowed")]
    ChainNotAllowed,
    #[error("nonce missing")]
    MissingNonce,
    #[error("nonce malformed")]
    BadNonce,
    #[error("issuedAt / expirationTime missing")]
    MissingTimestamps,
    #[error("timestamp is not RFC 3339")]
    BadTimestamp,
    #[error("issuedAt is in the future")]
    IssuedInFuture,
    #[error("issuedAt is too old")]
    IssuedTooLongAgo,
    #[error("sign-in message expired")]
    Expired,
    #[error("sign-in message not yet valid")]
    NotYetValid,
    #[error("sign-in validity window too long")]
    ValidityTooLong,
    #[error("address is not a valid Solana public key")]
    InvalidAddress,
    #[error("address does not match the message")]
    AddressMismatch,
    #[error("signature must be 64 bytes")]
    BadSignatureEncoding,
    #[error("signature verification failed")]
    InvalidSignature,
}

impl SiwsError {
    /// Stable machine-readable code for API responses and logs.
    pub fn code(&self) -> &'static str {
        match self {
            Self::TooLarge => "message_too_large",
            Self::NotUtf8 => "message_not_utf8",
            Self::Parse(_) => "message_malformed",
            Self::DomainMismatch => "domain_mismatch",
            Self::UriMismatch => "uri_mismatch",
            Self::UnsupportedVersion => "unsupported_version",
            Self::ChainNotAllowed => "chain_not_allowed",
            Self::MissingNonce => "nonce_missing",
            Self::BadNonce => "nonce_malformed",
            Self::MissingTimestamps => "timestamps_missing",
            Self::BadTimestamp => "timestamp_malformed",
            Self::IssuedInFuture => "issued_in_future",
            Self::IssuedTooLongAgo => "issued_too_long_ago",
            Self::Expired => "message_expired",
            Self::NotYetValid => "message_not_yet_valid",
            Self::ValidityTooLong => "validity_too_long",
            Self::InvalidAddress => "address_invalid",
            Self::AddressMismatch => "address_mismatch",
            Self::BadSignatureEncoding => "signature_malformed",
            Self::InvalidSignature => "signature_invalid",
        }
    }
}

/// Verifies a SIWS proof without touching server state. On success, the caller must still
/// consume `nonce` from the store (and must treat failure there as a failed sign-in).
pub fn verify_signed_message(
    policy: &SiwsPolicy,
    message: &[u8],
    signature: &[u8],
    claimed_address: Option<&str>,
    now: i64,
) -> Result<VerifiedSignIn, SiwsError> {
    if message.len() > policy.max_message_bytes {
        return Err(SiwsError::TooLarge);
    }
    let text = std::str::from_utf8(message).map_err(|_| SiwsError::NotUtf8)?;
    let msg = SiwsMessage::parse(text)?;

    if msg.domain != policy.domain {
        return Err(SiwsError::DomainMismatch);
    }
    if msg.uri.as_deref() != Some(policy.uri.as_str()) {
        return Err(SiwsError::UriMismatch);
    }
    if msg.version.as_deref() != Some("1") {
        return Err(SiwsError::UnsupportedVersion);
    }
    let chain_id = msg.chain_id.clone().ok_or(SiwsError::ChainNotAllowed)?;
    if !policy.chains.iter().any(|c| *c == chain_id) {
        return Err(SiwsError::ChainNotAllowed);
    }
    let nonce = msg.nonce.clone().ok_or(SiwsError::MissingNonce)?;
    if !crate::nonce::is_well_formed(&nonce) {
        return Err(SiwsError::BadNonce);
    }

    check_time_window(policy, &msg, now)?;

    let address_bytes = decode_address(&msg.address).ok_or(SiwsError::InvalidAddress)?;
    if let Some(claimed) = claimed_address {
        if claimed != msg.address {
            return Err(SiwsError::AddressMismatch);
        }
    }
    let key = VerifyingKey::from_bytes(&address_bytes).map_err(|_| SiwsError::InvalidAddress)?;
    let sig_bytes: [u8; 64] = signature.try_into().map_err(|_| SiwsError::BadSignatureEncoding)?;
    let sig = Signature::from_bytes(&sig_bytes);
    // verify_strict: rejects small-order keys / R and non-canonical S (the same rule the
    // Ed25519SigVerify precompile applies), over the exact bytes the wallet signed.
    key.verify_strict(message, &sig).map_err(|_| SiwsError::InvalidSignature)?;

    Ok(VerifiedSignIn { address: msg.address, address_bytes, chain_id, nonce })
}

fn check_time_window(policy: &SiwsPolicy, msg: &SiwsMessage, now: i64) -> Result<(), SiwsError> {
    let (Some(iat), Some(exp)) = (msg.issued_at.as_deref(), msg.expiration_time.as_deref()) else {
        return Err(SiwsError::MissingTimestamps);
    };
    let iat = parse_rfc3339(iat).ok_or(SiwsError::BadTimestamp)?;
    let exp = parse_rfc3339(exp).ok_or(SiwsError::BadTimestamp)?;
    let skew = policy.clock_skew_secs;

    if iat > now.saturating_add(skew) {
        return Err(SiwsError::IssuedInFuture);
    }
    if now.saturating_sub(iat) > policy.nonce_ttl_secs.saturating_add(skew) {
        return Err(SiwsError::IssuedTooLongAgo);
    }
    if exp <= iat || exp.saturating_sub(iat) > policy.max_validity_secs() {
        return Err(SiwsError::ValidityTooLong);
    }
    if now >= exp {
        return Err(SiwsError::Expired);
    }
    if let Some(nbf) = msg.not_before.as_deref() {
        let nbf = parse_rfc3339(nbf).ok_or(SiwsError::BadTimestamp)?;
        if nbf > now.saturating_add(skew) {
            return Err(SiwsError::NotYetValid);
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::clock::rfc3339;
    use ed25519_dalek::{Signer, SigningKey};

    const NOW: i64 = 1_790_683_200; // 2026-09-29T12:00:00Z
    const NONCE: &str = "0123456789abcdef0123456789abcdef";

    fn policy() -> SiwsPolicy {
        SiwsPolicy::new("headsdown.xyz", vec!["solana:mainnet".into()])
    }

    fn signer() -> SigningKey {
        SigningKey::from_bytes(&[9u8; 32])
    }

    fn message(key: &SigningKey) -> SiwsMessage {
        SiwsMessage {
            domain: "headsdown.xyz".into(),
            address: bs58::encode(key.verifying_key().as_bytes()).into_string(),
            statement: Some("Clock in to Heads Down.".into()),
            uri: Some("https://headsdown.xyz".into()),
            version: Some("1".into()),
            chain_id: Some("solana:mainnet".into()),
            nonce: Some(NONCE.into()),
            issued_at: Some(rfc3339(NOW)),
            expiration_time: Some(rfc3339(NOW + 600)),
            ..Default::default()
        }
    }

    fn signed(msg: &SiwsMessage, key: &SigningKey) -> (Vec<u8>, Vec<u8>) {
        let bytes = msg.to_text().into_bytes();
        let sig = key.sign(&bytes).to_bytes().to_vec();
        (bytes, sig)
    }

    fn check(msg: &SiwsMessage, now: i64) -> Result<VerifiedSignIn, SiwsError> {
        let (m, s) = signed(msg, &signer());
        verify_signed_message(&policy(), &m, &s, None, now)
    }

    #[test]
    fn valid_sign_in() {
        let v = check(&message(&signer()), NOW + 5).unwrap();
        assert_eq!(v.address, message(&signer()).address);
        assert_eq!(v.address_bytes, *signer().verifying_key().as_bytes());
        assert_eq!(v.nonce, NONCE);
        assert_eq!(v.chain_id, "solana:mainnet");
    }

    #[test]
    fn binding_checks() {
        let base = message(&signer());
        let with = |f: &dyn Fn(&mut SiwsMessage)| {
            let mut m = base.clone();
            f(&mut m);
            m
        };
        let cases: Vec<(SiwsMessage, SiwsError)> = vec![
            (with(&|m| m.domain = "evil.xyz".into()), SiwsError::DomainMismatch),
            (with(&|m| m.domain = "headsdown.xyz.evil.xyz".into()), SiwsError::DomainMismatch),
            (with(&|m| m.uri = Some("https://evil.xyz".into())), SiwsError::UriMismatch),
            (with(&|m| m.uri = None), SiwsError::UriMismatch),
            (with(&|m| m.version = Some("2".into())), SiwsError::UnsupportedVersion),
            (with(&|m| m.chain_id = Some("solana:devnet".into())), SiwsError::ChainNotAllowed),
            (with(&|m| m.chain_id = Some("mainnet".into())), SiwsError::ChainNotAllowed),
            (with(&|m| m.chain_id = None), SiwsError::ChainNotAllowed),
            (with(&|m| m.nonce = None), SiwsError::MissingNonce),
            (with(&|m| m.nonce = Some("short".into())), SiwsError::BadNonce),
            (with(&|m| m.issued_at = None), SiwsError::MissingTimestamps),
            (with(&|m| m.expiration_time = None), SiwsError::MissingTimestamps),
            (with(&|m| m.issued_at = Some("yesterday".into())), SiwsError::BadTimestamp),
            (with(&|m| m.issued_at = Some(rfc3339(NOW + 120))), SiwsError::IssuedInFuture),
            (with(&|m| m.issued_at = Some(rfc3339(NOW - 700))), SiwsError::IssuedTooLongAgo),
            (with(&|m| m.expiration_time = Some(rfc3339(NOW + 3600))), SiwsError::ValidityTooLong),
            (with(&|m| m.expiration_time = Some(rfc3339(NOW))), SiwsError::ValidityTooLong),
            (with(&|m| m.not_before = Some(rfc3339(NOW + 300))), SiwsError::NotYetValid),
        ];
        for (m, err) in cases {
            assert_eq!(check(&m, NOW + 5), Err(err), "{}", m.to_text());
        }
    }

    #[test]
    fn expiry() {
        let m = message(&signer());
        assert!(check(&m, NOW + 599).is_ok());
        assert_eq!(check(&m, NOW + 600), Err(SiwsError::Expired));
    }

    #[test]
    fn devnet_allowed_when_configured() {
        let mut p = policy();
        p.chains.push("solana:devnet".into());
        let mut m = message(&signer());
        m.chain_id = Some("solana:devnet".into());
        let (bytes, sig) = signed(&m, &signer());
        assert!(verify_signed_message(&p, &bytes, &sig, None, NOW).is_ok());
    }

    #[test]
    fn signature_by_another_key_fails() {
        let m = message(&signer()); // claims signer()'s address
        let other = SigningKey::from_bytes(&[10u8; 32]);
        let (bytes, sig) = signed(&m, &other);
        assert_eq!(verify_signed_message(&policy(), &bytes, &sig, None, NOW), Err(SiwsError::InvalidSignature));
    }

    #[test]
    fn tampered_message_fails() {
        let (bytes, sig) = signed(&message(&signer()), &signer());
        let tampered = String::from_utf8(bytes).unwrap().replace("Clock in", "Clock out").into_bytes();
        assert_eq!(verify_signed_message(&policy(), &tampered, &sig, None, NOW), Err(SiwsError::InvalidSignature));
        let mut bad_sig = sig.clone();
        bad_sig[0] ^= 1;
        let (bytes, _) = signed(&message(&signer()), &signer());
        assert_eq!(verify_signed_message(&policy(), &bytes, &bad_sig, None, NOW), Err(SiwsError::InvalidSignature));
        assert_eq!(
            verify_signed_message(&policy(), &bytes, &sig[..63], None, NOW),
            Err(SiwsError::BadSignatureEncoding)
        );
    }

    #[test]
    fn claimed_address_and_encoding() {
        let (bytes, sig) = signed(&message(&signer()), &signer());
        let other = bs58::encode([1u8; 32]).into_string();
        assert_eq!(
            verify_signed_message(&policy(), &bytes, &sig, Some(&other), NOW),
            Err(SiwsError::AddressMismatch)
        );
        assert_eq!(verify_signed_message(&policy(), &[0xff, 0xfe], &sig, None, NOW), Err(SiwsError::NotUtf8));
        assert_eq!(
            verify_signed_message(&policy(), &vec![b'a'; 4096], &sig, None, NOW),
            Err(SiwsError::TooLarge)
        );
        let mut m = message(&signer());
        m.address = "not-base58-0OIl".into();
        assert_eq!(check(&m, NOW), Err(SiwsError::InvalidAddress));
    }
}
