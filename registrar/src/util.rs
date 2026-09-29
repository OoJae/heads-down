//! Small shared helpers: CSPRNG output and strict encodings.

use base64::Engine as _;
use ring::rand::{SecureRandom, SystemRandom};

/// Fills an array from the OS CSPRNG (`getrandom`). Fails closed: callers turn an error into
/// a 5xx rather than ever using predictable bytes.
pub fn random_array<const N: usize>() -> Result<[u8; N], RandomError> {
    let mut out = [0u8; N];
    SystemRandom::new().fill(&mut out).map_err(|_| RandomError)?;
    Ok(out)
}

#[derive(Debug, Clone, Copy, thiserror::Error)]
#[error("system randomness unavailable")]
pub struct RandomError;

/// Standard base64 with padding, strict (no whitespace, canonical trailing bits).
pub fn b64_decode(s: &str) -> Option<Vec<u8>> {
    base64::engine::general_purpose::STANDARD.decode(s).ok()
}

pub fn b64_encode(bytes: &[u8]) -> String {
    base64::engine::general_purpose::STANDARD.encode(bytes)
}

/// Decodes a base58 string that must hold exactly 32 bytes (a Solana address), and
/// requires the canonical encoding (so two spellings of one key cannot both appear).
pub fn decode_address(s: &str) -> Option<[u8; 32]> {
    if s.is_empty() || s.len() > 44 {
        return None;
    }
    let bytes = bs58::decode(s).into_vec().ok()?;
    let arr: [u8; 32] = bytes.try_into().ok()?;
    (bs58::encode(arr).into_string() == s).then_some(arr)
}

pub fn encode_address(bytes: &[u8; 32]) -> String {
    bs58::encode(bytes).into_string()
}

/// Hex (either case) of exactly `N` bytes.
pub fn decode_hex_exact<const N: usize>(s: &str) -> Option<[u8; N]> {
    if s.len() != N.checked_mul(2)? {
        return None;
    }
    let mut out = [0u8; N];
    hex::decode_to_slice(s, &mut out).ok()?;
    Some(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn address_must_be_canonical_32_bytes() {
        let key = [7u8; 32];
        let s = encode_address(&key);
        assert_eq!(decode_address(&s), Some(key));
        // Leading '1' encodes an extra zero byte: 33 bytes, rejected.
        assert_eq!(decode_address(&format!("1{s}")), None);
        assert_eq!(decode_address(""), None);
        assert_eq!(decode_address("0OIl"), None);
        assert_eq!(decode_address(&"1".repeat(32)), Some([0u8; 32]));
        assert_eq!(decode_address(&"1".repeat(33)), None);
    }

    #[test]
    fn hex_exact() {
        assert_eq!(decode_hex_exact::<2>("abcd"), Some([0xab, 0xcd]));
        assert_eq!(decode_hex_exact::<2>("ABCD"), Some([0xab, 0xcd]));
        assert_eq!(decode_hex_exact::<2>("abc"), None);
        assert_eq!(decode_hex_exact::<2>("abcdef"), None);
        assert_eq!(decode_hex_exact::<2>("zzzz"), None);
    }

    #[test]
    fn randomness_is_not_constant() {
        let a: [u8; 16] = random_array().unwrap();
        let b: [u8; 16] = random_array().unwrap();
        assert_ne!(a, b);
    }
}
