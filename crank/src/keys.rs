//! The crank's fee-payer key: a Solana CLI JSON keypair file whose path comes from the CLI
//! or the environment. It is never committed, never logged, never sent anywhere; only its
//! public key is printed.

use std::path::Path;

use solana_keypair::Keypair;
use solana_signer::Signer;

/// A loaded keypair with a redacting `Debug`.
pub struct CrankKey(Keypair);

impl CrankKey {
    /// The keypair (for signing).
    pub fn keypair(&self) -> &Keypair {
        &self.0
    }
}

impl std::fmt::Debug for CrankKey {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "CrankKey({})", self.0.pubkey())
    }
}

/// Load a JSON-array keypair. Refuses files that are not 64-byte keypairs, and on Unix
/// refuses group- or world-readable files (like `ssh` does with private keys).
pub fn load_keypair(path: &Path) -> anyhow::Result<CrankKey> {
    let meta = std::fs::metadata(path).map_err(|e| anyhow::anyhow!("keypair {}: {e}", path.display()))?;
    if meta.len() > 4096 {
        anyhow::bail!("keypair {} is too large to be a keypair file", path.display());
    }
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let mode = meta.permissions().mode();
        if mode & 0o077 != 0 {
            anyhow::bail!(
                "keypair {} is accessible by group/others (mode {:o}); run: chmod 600 {}",
                path.display(),
                mode & 0o777,
                path.display()
            );
        }
    }
    let mut f = std::fs::File::open(path)?;
    let kp = solana_keypair::read_keypair(&mut f)
        .map_err(|_| anyhow::anyhow!("keypair {} is not a Solana CLI JSON keypair", path.display()))?;
    Ok(CrankKey(kp))
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;

    #[test]
    fn loads_and_redacts() {
        let kp = Keypair::new();
        let dir = tempfile::tempdir().unwrap();
        let p = dir.path().join("crank.json");
        let mut f = std::fs::File::create(&p).unwrap();
        let bytes: Vec<String> = kp.to_bytes().iter().map(u8::to_string).collect();
        write!(f, "[{}]", bytes.join(",")).unwrap();
        drop(f);
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            std::fs::set_permissions(&p, std::fs::Permissions::from_mode(0o644)).unwrap();
            assert!(load_keypair(&p).unwrap_err().to_string().contains("chmod 600"));
            std::fs::set_permissions(&p, std::fs::Permissions::from_mode(0o600)).unwrap();
        }
        let k = load_keypair(&p).unwrap();
        assert_eq!(k.keypair().pubkey(), kp.pubkey());
        let dbg = format!("{k:?}");
        assert_eq!(dbg, format!("CrankKey({})", kp.pubkey()));
        assert!(!dbg.contains(&bytes[0..8].join(",")));

        let bad = dir.path().join("bad.json");
        std::fs::write(&bad, "[1,2,3]").unwrap();
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            std::fs::set_permissions(&bad, std::fs::Permissions::from_mode(0o600)).unwrap();
        }
        assert!(load_keypair(&bad).is_err());
    }
}
