//! A simulated phone: a P-256 key that signs exactly like Android Keystore
//! (`SHA256withECDSA` over the 32-byte `SHA-256(HEARTBEAT preimage)`, DER, then low-S r||s).

use anyhow::{anyhow, Result};
use hd_crank::hd::{self, HeartbeatFields};
use p256::ecdsa::{signature::Signer as _, DerSignature, SigningKey};
use p256_introspect::client::{build_instruction_data, der_to_low_s_raw, SignatureInput};
use solana_address::Address;
use solana_instruction::Instruction;

/// The phone's rig key.
pub struct Phone {
    sk: SigningKey,
}

impl Phone {
    /// A fresh random key (OS CSPRNG).
    pub fn random() -> Result<Self> {
        for _ in 0..8 {
            if let Ok(sk) = SigningKey::from_slice(&crate::util::random32()?) {
                return Ok(Phone { sk });
            }
        }
        Err(anyhow!("could not draw a P-256 key"))
    }

    /// SEC1 compressed public key (what `register_rig` stores).
    pub fn pubkey(&self) -> [u8; 33] {
        let p = self.sk.verifying_key().to_sec1_point(true);
        let mut out = [0u8; 33];
        out.copy_from_slice(p.as_bytes());
        out
    }

    /// Sign a 32-byte digest the Keystore way; returns (digest, low-S r||s).
    pub fn sign_heartbeat(&self, rig: &Address, f: &HeartbeatFields) -> Result<([u8; 32], [u8; 64])> {
        let digest = hd::digest(&hd::heartbeat_preimage(&hd::PROGRAM_ID, rig, f));
        let der: DerSignature = self.sk.sign(&digest);
        let sig = der_to_low_s_raw(der.as_bytes()).map_err(|e| anyhow!("low-S: {e:?}"))?;
        Ok((digest, sig))
    }

    /// A Secp256r1SigVerify instruction carrying one heartbeat (offsets inside itself).
    pub fn precompile_ix(&self, digest: &[u8; 32], sig: &[u8; 64]) -> Result<Instruction> {
        let data = build_instruction_data(&[SignatureInput { signature: *sig, public_key: self.pubkey(), message: digest }])
            .map_err(|e| anyhow!("precompile data: {e:?}"))?;
        Ok(Instruction { program_id: hd::SECP256R1_PROGRAM_ID, accounts: vec![], data })
    }
}
