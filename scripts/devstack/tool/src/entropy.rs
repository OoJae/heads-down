//! A local stand-in for ORE's entropy *provider* (not the entropy program, which runs
//! unmodified from its mainnet bytecode).
//!
//! The entropy program is a commit-reveal hash chain: `reveal(seed)` requires
//! `keccak(seed) == var.commit`, and `next` (CPI'd by ORE `deploy` on a round's first deploy)
//! sets `commit = seed`. Mainnet's provider holds the secret chain, so on a fork nobody can
//! reveal and `reset` can never run. The dev stack therefore rewrites the Var once at genesis
//! so that its last revealed `seed` is the head of a chain *we* generated:
//!
//! ```text
//! x_0 = keccak(secret | "hd-devstack-entropy-v1"),  x_{i+1} = keccak(x_i),  head = x_N
//! genesis Var.seed = x_N  →  next: commit = x_N  →  reveal x_{N-1}  →  next: commit = x_{N-1} ...
//! ```
//!
//! The secret lives outside the repo (`~/.config/heads-down/devstack/entropy-secret`).

use std::collections::HashMap;
use std::path::Path;

use anyhow::{anyhow, Context, Result};

use crate::ore::keccak;

/// Rounds the chain supports (~130 days of 115 s rounds).
pub const CHAIN_LEN: usize = 100_000;

/// The provider's hash chain.
pub struct HashChain {
    xs: Vec<[u8; 32]>,
    index: HashMap<[u8; 32], usize>,
}

impl HashChain {
    /// Build from a 32-byte secret.
    pub fn new(secret: &[u8; 32]) -> Self {
        let mut xs = Vec::with_capacity(CHAIN_LEN + 1);
        let mut x = keccak(&[secret, b"hd-devstack-entropy-v1"]);
        xs.push(x);
        for _ in 0..CHAIN_LEN {
            x = keccak(&[&x]);
            xs.push(x);
        }
        let index = xs.iter().enumerate().map(|(i, h)| (*h, i)).collect();
        HashChain { xs, index }
    }

    /// Load the secret file (hex) and build the chain.
    pub fn load(path: &Path) -> Result<Self> {
        let raw = std::fs::read_to_string(path).with_context(|| format!("entropy secret {}", path.display()))?;
        let bytes = hex::decode(raw.trim()).map_err(|_| anyhow!("entropy secret {} is not hex", path.display()))?;
        let secret: [u8; 32] = bytes.try_into().map_err(|_| anyhow!("entropy secret must be 32 bytes"))?;
        Ok(Self::new(&secret))
    }

    /// Create the secret file (mode 600) if missing.
    pub fn ensure_secret(path: &Path) -> Result<()> {
        if path.exists() {
            return Ok(());
        }
        if let Some(d) = path.parent() {
            std::fs::create_dir_all(d)?;
        }
        std::fs::write(path, hex::encode(crate::util::random32()?))?;
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            std::fs::set_permissions(path, std::fs::Permissions::from_mode(0o600))?;
        }
        Ok(())
    }

    /// The chain head placed in the genesis Var as the last revealed seed.
    pub fn head(&self) -> [u8; 32] {
        self.xs.last().copied().unwrap_or([0; 32])
    }

    /// The seed that reveals `commit` (its keccak preimage), if `commit` is on our chain.
    pub fn preimage_of(&self, commit: &[u8; 32]) -> Option<[u8; 32]> {
        let j = *self.index.get(commit)?;
        j.checked_sub(1).and_then(|i| self.xs.get(i)).copied()
    }

    /// How many reveals are left after `commit`.
    pub fn remaining(&self, commit: &[u8; 32]) -> Option<usize> {
        self.index.get(commit).copied()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn chain_reveals_in_reverse_order() {
        let c = HashChain::new(&[7; 32]);
        let head = c.head();
        // next(): commit = head. The reveal must hash to it.
        let s1 = c.preimage_of(&head).unwrap();
        assert_eq!(keccak(&[&s1]), head);
        // next(): commit = s1.
        let s2 = c.preimage_of(&s1).unwrap();
        assert_eq!(keccak(&[&s2]), s1);
        assert_eq!(c.remaining(&head), Some(CHAIN_LEN));
        assert!(c.preimage_of(&[1; 32]).is_none());
    }
}
