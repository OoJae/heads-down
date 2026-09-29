//! Short-lived session tokens bound to a wallet address, minted after a verified SIWS sign-in.
//!
//! Format: `hds1.<base64url(claims JSON)>.<base64url(HMAC-SHA256(key, "hds1." || claims_b64))>`
//!
//! - The MAC covers the version prefix, so a future format cannot be confused with this one.
//! - Verification recomputes the MAC over the exact received bytes and compares in constant
//!   time (`hmac::Mac::verify_slice`) **before** parsing any JSON.
//! - Claims are strict (`deny_unknown_fields`); `exp` is enforced, and `exp - iat` may not
//!   exceed [`MAX_TTL_SECS`] even if the key were used to mint one.
//! - The key is at least 32 bytes, from the environment, zeroized on drop, never logged.
//!
//! Tokens are bearer credentials for the heartbeat intake and the attestation endpoints only.
//! They grant no on-chain authority (see THREAT_MODEL.md, K6).

use base64::Engine as _;
use hmac::{Hmac, Mac};
use serde::{Deserialize, Serialize};
use sha2::Sha256;
use zeroize::Zeroizing;

type HmacSha256 = Hmac<Sha256>;

const PREFIX: &str = "hds1";
pub const MIN_KEY_BYTES: usize = 32;
pub const MAX_TTL_SECS: i64 = 24 * 3600;
/// Tokens longer than this are rejected before any work is done.
pub const MAX_TOKEN_LEN: usize = 1024;
const IAT_SKEW_SECS: i64 = 60;

const B64URL: base64::engine::GeneralPurpose = base64::engine::general_purpose::URL_SAFE_NO_PAD;

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct SessionClaims {
    /// Base58 wallet address proven by SIWS.
    pub sub: String,
    /// CAIP-2 chain id from the signed SIWS message.
    pub chain: String,
    pub iat: i64,
    pub exp: i64,
    /// Random token id (hex, 64 bits), for log correlation without logging the token.
    pub jti: String,
}

pub struct SessionKey {
    key: Zeroizing<Vec<u8>>,
}

impl std::fmt::Debug for SessionKey {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("SessionKey(<redacted>)")
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, thiserror::Error)]
pub enum SessionError {
    #[error("session secret must be at least 32 bytes")]
    WeakKey,
    #[error("malformed session token")]
    Malformed,
    #[error("session token signature invalid")]
    BadMac,
    #[error("session expired")]
    Expired,
    #[error("session token lifetime out of bounds")]
    BadLifetime,
}

impl SessionKey {
    pub fn new(key: Vec<u8>) -> Result<Self, SessionError> {
        let key = Zeroizing::new(key);
        if key.len() < MIN_KEY_BYTES {
            return Err(SessionError::WeakKey);
        }
        Ok(Self { key })
    }

    /// Accepts the secret as hex (64+ hex chars) or standard base64 (44+ chars).
    pub fn from_encoded(s: &str) -> Result<Self, SessionError> {
        let s = s.trim();
        let bytes = hex::decode(s)
            .ok()
            .or_else(|| base64::engine::general_purpose::STANDARD.decode(s).ok())
            .ok_or(SessionError::WeakKey)?;
        Self::new(bytes)
    }

    fn mac(&self) -> Result<HmacSha256, SessionError> {
        HmacSha256::new_from_slice(&self.key).map_err(|_| SessionError::WeakKey)
    }

    pub fn issue(&self, claims: &SessionClaims) -> Result<String, SessionError> {
        if claims.exp <= claims.iat || claims.exp.saturating_sub(claims.iat) > MAX_TTL_SECS {
            return Err(SessionError::BadLifetime);
        }
        let json = serde_json::to_vec(claims).map_err(|_| SessionError::Malformed)?;
        let payload = B64URL.encode(json);
        let signing_input = format!("{PREFIX}.{payload}");
        let mut mac = self.mac()?;
        mac.update(signing_input.as_bytes());
        let tag = B64URL.encode(mac.finalize().into_bytes());
        Ok(format!("{signing_input}.{tag}"))
    }

    pub fn verify(&self, token: &str, now: i64) -> Result<SessionClaims, SessionError> {
        if token.len() > MAX_TOKEN_LEN {
            return Err(SessionError::Malformed);
        }
        let mut parts = token.split('.');
        let (Some(prefix), Some(payload), Some(tag), None) = (parts.next(), parts.next(), parts.next(), parts.next())
        else {
            return Err(SessionError::Malformed);
        };
        if prefix != PREFIX {
            return Err(SessionError::Malformed);
        }
        let tag = B64URL.decode(tag).map_err(|_| SessionError::Malformed)?;
        let mut mac = self.mac()?;
        mac.update(PREFIX.as_bytes());
        mac.update(b".");
        mac.update(payload.as_bytes());
        mac.verify_slice(&tag).map_err(|_| SessionError::BadMac)?;

        let json = B64URL.decode(payload).map_err(|_| SessionError::Malformed)?;
        let claims: SessionClaims = serde_json::from_slice(&json).map_err(|_| SessionError::Malformed)?;
        if claims.exp <= claims.iat
            || claims.exp.saturating_sub(claims.iat) > MAX_TTL_SECS
            || claims.iat > now.saturating_add(IAT_SKEW_SECS)
        {
            return Err(SessionError::BadLifetime);
        }
        if now >= claims.exp {
            return Err(SessionError::Expired);
        }
        Ok(claims)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn key() -> SessionKey {
        SessionKey::new(vec![42u8; 32]).unwrap()
    }

    fn claims() -> SessionClaims {
        SessionClaims {
            sub: "9aE476sH92Vz7DMPyq5WLPkrKWivxeuTKEFKd2sZZcde".into(),
            chain: "solana:mainnet".into(),
            iat: 1_000,
            exp: 4_600,
            jti: "00112233aabbccdd".into(),
        }
    }

    #[test]
    fn issue_and_verify() {
        let t = key().issue(&claims()).unwrap();
        assert!(t.starts_with("hds1."));
        assert_eq!(key().verify(&t, 1_001).unwrap(), claims());
    }

    #[test]
    fn expiry_is_enforced() {
        let t = key().issue(&claims()).unwrap();
        assert_eq!(key().verify(&t, 4_599).map(|c| c.sub), Ok(claims().sub));
        assert_eq!(key().verify(&t, 4_600), Err(SessionError::Expired));
    }

    #[test]
    fn wrong_key_and_tampering_fail() {
        let t = key().issue(&claims()).unwrap();
        let other = SessionKey::new(vec![43u8; 32]).unwrap();
        assert_eq!(other.verify(&t, 1_001), Err(SessionError::BadMac));

        // Swap in a payload for another address, keep the old MAC.
        let mut forged = claims();
        forged.sub = "11111111111111111111111111111111".into();
        let forged_payload = B64URL.encode(serde_json::to_vec(&forged).unwrap());
        let parts: Vec<&str> = t.split('.').collect();
        let tampered = format!("hds1.{forged_payload}.{}", parts[2]);
        assert_eq!(key().verify(&tampered, 1_001), Err(SessionError::BadMac));

        // Flip one character of the MAC.
        let mut chars: Vec<char> = t.chars().collect();
        let last = chars.len() - 1;
        chars[last] = if chars[last] == 'A' { 'B' } else { 'A' };
        let flipped: String = chars.into_iter().collect();
        assert!(key().verify(&flipped, 1_001).is_err());
    }

    #[test]
    fn malformed_tokens_rejected() {
        for bad in ["", "hds1", "hds1..", "hds2.a.b", "hds1.a.b.c", "x".repeat(2000).as_str()] {
            assert!(key().verify(bad, 0).is_err(), "{bad}");
        }
    }

    #[test]
    fn lifetime_bounds() {
        let mut c = claims();
        c.exp = c.iat + MAX_TTL_SECS + 1;
        assert_eq!(key().issue(&c), Err(SessionError::BadLifetime));
        c.exp = c.iat;
        assert_eq!(key().issue(&c), Err(SessionError::BadLifetime));
        // Issued in the future (beyond skew).
        let t = key().issue(&claims()).unwrap();
        assert_eq!(key().verify(&t, 1_000 - 61), Err(SessionError::BadLifetime));
    }

    #[test]
    fn key_requirements() {
        assert_eq!(SessionKey::new(vec![1u8; 31]).map(|_| ()), Err(SessionError::WeakKey));
        assert!(SessionKey::from_encoded(&"ab".repeat(32)).is_ok());
        assert!(SessionKey::from_encoded(&"ab".repeat(16)).is_err());
        assert!(SessionKey::from_encoded("not a secret").is_err());
        assert_eq!(format!("{:?}", key()), "SessionKey(<redacted>)");
    }
}
