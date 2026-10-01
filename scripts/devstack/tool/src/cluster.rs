//! Which cluster a command talks to, and the guard every chain command runs first.
//!
//! `--cluster` (env `HD_CLUSTER`) picks the default RPC and, more importantly, what the RPC
//! must turn out to be before anything is read or signed:
//!
//! | Cluster | RPC host | `getGenesisHash` |
//! |---|---|---|
//! | `localnet` (default) | loopback only (`127.0.0.0/8`, `::1`, `localhost`) | anything (a Surfpool fork reports mainnet's) |
//! | `devnet` | not loopback | devnet's |
//! | `mainnet` | not loopback | mainnet's |
//!
//! So a localnet command can never reach a public cluster, and a mainnet command can never
//! be pointed at a local fork that merely *reports* mainnet's genesis hash.
//!
//! The RPC URL may carry a provider key (`?api-key=`): it is taken from `HD_DEVSTACK_RPC`
//! (hidden from `--help`), and only its scheme and host are ever printed.

use anyhow::{bail, Result};
use clap::ValueEnum;
use hd_crank::rpc::redact_url;

use crate::util::Chain;

/// Mainnet-beta genesis hash.
pub const MAINNET_GENESIS: &str = "5eykt4UsFv8P8NJdTREpY1vzqKqZKvdpKuc147dw2N9d";
/// Devnet genesis hash.
pub const DEVNET_GENESIS: &str = "EtWTRABZaYq6iMfeYKouRu166VL8Lv3NEMP2SLgEcMdE";

/// The cluster a command targets.
#[derive(Clone, Copy, Debug, PartialEq, Eq, ValueEnum)]
pub enum Cluster {
    /// A local validator or fork (the dev stack).
    Localnet,
    /// Solana devnet.
    Devnet,
    /// Solana mainnet-beta.
    Mainnet,
}

impl Cluster {
    /// Lower-case name.
    pub fn name(self) -> &'static str {
        match self {
            Cluster::Localnet => "localnet",
            Cluster::Devnet => "devnet",
            Cluster::Mainnet => "mainnet",
        }
    }

    /// RPC used when neither `--rpc` nor `HD_DEVSTACK_RPC` is set.
    pub fn default_rpc(self) -> &'static str {
        match self {
            Cluster::Localnet => "http://127.0.0.1:8899",
            Cluster::Devnet => "https://api.devnet.solana.com",
            Cluster::Mainnet => "https://api.mainnet-beta.solana.com",
        }
    }

    /// True for mainnet.
    pub fn is_mainnet(self) -> bool {
        self == Cluster::Mainnet
    }

    /// Check an RPC URL and the genesis hash it reported against this cluster.
    pub fn check(self, rpc_url: &str, genesis: &str) -> Result<()> {
        let local = is_loopback_url(rpc_url);
        let host = redact_url(rpc_url);
        match self {
            Cluster::Localnet => {
                if !local {
                    bail!("--cluster localnet needs a loopback RPC (127.0.0.1, ::1 or localhost), got {host}");
                }
            }
            Cluster::Devnet | Cluster::Mainnet => {
                if local {
                    bail!("--cluster {} refuses a loopback RPC ({host}): a local fork is --cluster localnet", self.name());
                }
                let want = if self == Cluster::Mainnet { MAINNET_GENESIS } else { DEVNET_GENESIS };
                if genesis != want {
                    bail!("--cluster {} but {host} reports genesis {genesis} (expected {want}); refusing", self.name());
                }
            }
        }
        Ok(())
    }
}

/// `http(s)://127.x.y.z…`, `http(s)://[::1]…` or `http(s)://localhost…`.
pub fn is_loopback_url(url: &str) -> bool {
    let Some((_, rest)) = url.split_once("://") else {
        return false;
    };
    let authority = rest.split(['/', '?', '#']).next().unwrap_or("");
    let host_port = authority.rsplit_once('@').map_or(authority, |(_, h)| h);
    let host = if let Some(v6) = host_port.strip_prefix('[') {
        v6.split(']').next().unwrap_or("")
    } else {
        host_port.split(':').next().unwrap_or("")
    };
    if host.eq_ignore_ascii_case("localhost") {
        return true;
    }
    match host.parse::<std::net::IpAddr>() {
        Ok(ip) => ip.is_loopback(),
        Err(_) => false,
    }
}

/// Connect to `rpc`, read its genesis hash and refuse a cluster mismatch. Non-local
/// clusters get one line on stderr (host only, never the key).
pub async fn connect(cluster: Cluster, rpc: &str) -> Result<(Chain, String)> {
    let chain = Chain::new(rpc)?;
    let genesis = chain.genesis_hash().await?;
    cluster.check(rpc, &genesis)?;
    if cluster != Cluster::Localnet {
        eprintln!("[hd-devstack] cluster {} via {} (genesis {genesis})", cluster.name(), redact_url(rpc));
    }
    Ok((chain, genesis))
}

/// Refuse commands that only make sense on a local fork (airdrops, the round driver, …).
pub fn require_local(cluster: Cluster, what: &str) -> Result<()> {
    if cluster != Cluster::Localnet {
        bail!("`{what}` is a local dev-stack command; it refuses --cluster {}", cluster.name());
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn loopback_detection() {
        for u in ["http://127.0.0.1:8899", "http://127.0.0.2:18899/", "http://localhost:8899", "http://[::1]:8899", "http://u:p@127.0.0.1:1"] {
            assert!(is_loopback_url(u), "{u}");
        }
        for u in [
            "https://api.mainnet-beta.solana.com",
            "https://mainnet.helius-rpc.com/?api-key=abc",
            "http://10.0.0.1:8899",
            "http://127.0.0.1.evil.com",
            "http://localhost.evil.com",
            "not a url",
        ] {
            assert!(!is_loopback_url(u), "{u}");
        }
    }

    #[test]
    fn cluster_guard() {
        let local = "http://127.0.0.1:8899";
        let helius = "https://mainnet.helius-rpc.com/?api-key=secret-key";
        // localnet: loopback only, any genesis (a Surfpool fork reports mainnet's).
        assert!(Cluster::Localnet.check(local, "RandomLocalGenesis1111111111111111111111111").is_ok());
        assert!(Cluster::Localnet.check(local, MAINNET_GENESIS).is_ok());
        assert!(Cluster::Localnet.check(helius, MAINNET_GENESIS).is_err());
        // mainnet: never loopback, exact genesis.
        assert!(Cluster::Mainnet.check(helius, MAINNET_GENESIS).is_ok());
        assert!(Cluster::Mainnet.check(helius, DEVNET_GENESIS).is_err());
        assert!(Cluster::Mainnet.check(local, MAINNET_GENESIS).is_err());
        assert!(Cluster::Devnet.check("https://api.devnet.solana.com", DEVNET_GENESIS).is_ok());
        assert!(Cluster::Devnet.check("https://api.devnet.solana.com", MAINNET_GENESIS).is_err());
        // The key never appears in an error.
        let e = Cluster::Mainnet.check(helius, DEVNET_GENESIS).unwrap_err().to_string();
        assert!(!e.contains("secret-key"), "{e}");
        let e = Cluster::Localnet.check(helius, MAINNET_GENESIS).unwrap_err().to_string();
        assert!(!e.contains("secret-key"), "{e}");
    }
}
