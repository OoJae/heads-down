//! Hashing: `sol_sha256` / `sol_keccak256` syscalls on SBF, pure Rust on the
//! host (unit tests, the test client).

/// SHA-256 over the concatenation of `parts`.
#[inline]
pub fn sha256(parts: &[&[u8]]) -> [u8; 32] {
    solana_sha256_hasher::hashv(parts).to_bytes()
}

/// Keccak-256 over the concatenation of `parts` (what ORE's
/// `solana_program::keccak::hashv` computes).
#[inline]
pub fn keccak256(parts: &[&[u8]]) -> [u8; 32] {
    solana_keccak_hasher::hashv(parts).to_bytes()
}
