//! The registrar's Ed25519 voucher over a rig key, and the Ed25519SigVerify instruction that
//! carries it into a `register_rig` / `rotate_key` transaction.
//!
//! ## `HDreg` preimage (111 bytes, signed as-is: **not** hashed first)
//!
//! | Offset | Size | Field |
//! |---:|---:|---|
//! | 0 | 5 | ASCII `"HDreg"` (`48 44 72 65 67`) |
//! | 5 | 32 | `program_id` (heads_down program address) |
//! | 37 | 32 | `authority` (the rig owner's wallet) |
//! | 69 | 33 | `p256_pubkey` (SEC1 compressed, `02`/`03` prefix) |
//! | 102 | 1 | `level` u8: 0 unattested, 1 TEE, 2 StrongBox |
//! | 103 | 8 | `expiry_slot` u64 little-endian |
//!
//! Ed25519 signs the raw 111 bytes (Ed25519 hashes internally with SHA-512). This differs on
//! purpose from the P-256 messages, which are `SHA-256(preimage)` because the secp256r1
//! precompile hashes its message once more.
//!
//! ## Instruction (223 bytes, program `Ed25519SigVerify111111111111111111111111111`, no accounts)
//!
//! Byte-identical to `solana_ed25519_program::new_ed25519_instruction_with_signature`:
//! `[1, 0]` + offsets (7 x u16 LE: sig_off=48, sig_ix=0xFFFF, pk_off=16, pk_ix=0xFFFF,
//! msg_off=112, msg_len=111, msg_ix=0xFFFF) + pubkey(32) + signature(64) + message(111).
//! `0xFFFF` means "this instruction", so the verified bytes are exactly the bytes the
//! program reads back from the same instruction through the instructions sysvar.

use std::path::Path;

use ed25519_dalek::{Signer, SigningKey, VerifyingKey};
use zeroize::Zeroizing;

use crate::util::{random_array, RandomError};

pub const HDREG_TAG: &[u8; 5] = b"HDreg";
pub const HDREG_LEN: usize = 111;
pub const HEADS_DOWN_PROGRAM_ID: &str = "HDn4vgLWFLLdexKEwfZwRHjWtizNvdqFteLbMsE67F9p";
pub const ED25519_PROGRAM_ID: &str = "Ed25519SigVerify111111111111111111111111111";

pub const IX_HEADER_LEN: usize = 16;
pub const IX_PUBKEY_OFFSET: usize = IX_HEADER_LEN;
pub const IX_SIGNATURE_OFFSET: usize = IX_PUBKEY_OFFSET + 32;
pub const IX_MESSAGE_OFFSET: usize = IX_SIGNATURE_OFFSET + 64;
pub const IX_LEN: usize = IX_MESSAGE_OFFSET + HDREG_LEN;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Voucher {
    pub program_id: [u8; 32],
    pub authority: [u8; 32],
    pub p256_pubkey: [u8; 33],
    pub level: u8,
    pub expiry_slot: u64,
}

impl Voucher {
    pub fn preimage(&self) -> [u8; HDREG_LEN] {
        let mut out = [0u8; HDREG_LEN];
        let parts: [&[u8]; 6] = [
            HDREG_TAG,
            &self.program_id,
            &self.authority,
            &self.p256_pubkey,
            &[self.level],
            &self.expiry_slot.to_le_bytes(),
        ];
        let mut at = 0usize;
        for part in parts {
            let end = at.saturating_add(part.len());
            if let Some(dst) = out.get_mut(at..end) {
                dst.copy_from_slice(part);
            }
            at = end;
        }
        out
    }

    /// Inverse of [`preimage`](Self::preimage), for audits.
    pub fn from_preimage(bytes: &[u8]) -> Option<Self> {
        if bytes.len() != HDREG_LEN || bytes.get(..5)? != HDREG_TAG {
            return None;
        }
        Some(Self {
            program_id: bytes.get(5..37)?.try_into().ok()?,
            authority: bytes.get(37..69)?.try_into().ok()?,
            p256_pubkey: bytes.get(69..102)?.try_into().ok()?,
            level: *bytes.get(102)?,
            expiry_slot: u64::from_le_bytes(bytes.get(103..111)?.try_into().ok()?),
        })
    }
}

/// Builds the Ed25519SigVerify instruction data for one signature, everything in-instruction.
pub fn ed25519_instruction_data(pubkey: &[u8; 32], signature: &[u8; 64], message: &[u8; HDREG_LEN]) -> Vec<u8> {
    let mut data = Vec::with_capacity(IX_LEN);
    data.extend_from_slice(&[1u8, 0u8]);
    let offsets: [u16; 7] = [
        IX_SIGNATURE_OFFSET as u16,
        u16::MAX,
        IX_PUBKEY_OFFSET as u16,
        u16::MAX,
        IX_MESSAGE_OFFSET as u16,
        HDREG_LEN as u16,
        u16::MAX,
    ];
    for o in offsets {
        data.extend_from_slice(&o.to_le_bytes());
    }
    data.extend_from_slice(pubkey);
    data.extend_from_slice(signature);
    data.extend_from_slice(message);
    data
}

#[derive(Clone, Debug)]
pub struct SignedVoucher {
    pub voucher: Voucher,
    pub message: [u8; HDREG_LEN],
    pub signature: [u8; 64],
    pub registrar: [u8; 32],
}

impl SignedVoucher {
    pub fn instruction_data(&self) -> Vec<u8> {
        ed25519_instruction_data(&self.registrar, &self.signature, &self.message)
    }
}

/// The registrar's Ed25519 signing key. Loaded from a file or the environment at startup,
/// zeroized on drop, never logged or serialized except by the explicit `keygen` command.
pub struct RegistrarKey {
    signing: SigningKey,
}

impl std::fmt::Debug for RegistrarKey {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "RegistrarKey({})", bs58::encode(self.pubkey()).into_string())
    }
}

#[derive(Debug, thiserror::Error)]
pub enum KeyError {
    #[error("registrar key must be a Solana keypair JSON array of 64 bytes, or base58 of a 32-byte seed / 64-byte keypair")]
    Format,
    #[error("registrar keypair's public half does not match its secret")]
    Inconsistent,
    #[error("cannot read registrar key file: {0}")]
    Io(String),
    #[error("registrar key file is writable by group or others; chmod 600 it")]
    Permissions,
    #[error(transparent)]
    Random(#[from] RandomError),
}

impl RegistrarKey {
    pub fn from_seed(seed: &[u8; 32]) -> Self {
        Self { signing: SigningKey::from_bytes(seed) }
    }

    pub fn generate() -> Result<Self, KeyError> {
        let seed: Zeroizing<[u8; 32]> = Zeroizing::new(random_array()?);
        Ok(Self::from_seed(&seed))
    }

    fn from_bytes(bytes: &[u8]) -> Result<Self, KeyError> {
        match bytes.len() {
            32 => {
                let seed: Zeroizing<[u8; 32]> = Zeroizing::new(bytes.try_into().map_err(|_| KeyError::Format)?);
                Ok(Self::from_seed(&seed))
            }
            64 => {
                let (secret, public) = bytes.split_at(32);
                let seed: Zeroizing<[u8; 32]> = Zeroizing::new(secret.try_into().map_err(|_| KeyError::Format)?);
                let key = Self::from_seed(&seed);
                if key.pubkey().as_slice() != public {
                    return Err(KeyError::Inconsistent);
                }
                Ok(key)
            }
            _ => Err(KeyError::Format),
        }
    }

    /// Solana CLI keypair format: a JSON array of 64 numbers (secret seed, then public key).
    pub fn from_keypair_json(json: &str) -> Result<Self, KeyError> {
        let bytes: Zeroizing<Vec<u8>> = Zeroizing::new(serde_json::from_str(json).map_err(|_| KeyError::Format)?);
        if bytes.len() != 64 {
            return Err(KeyError::Format);
        }
        Self::from_bytes(&bytes)
    }

    /// Base58 of a 32-byte seed or a 64-byte keypair.
    pub fn from_base58(s: &str) -> Result<Self, KeyError> {
        let bytes = Zeroizing::new(bs58::decode(s.trim()).into_vec().map_err(|_| KeyError::Format)?);
        Self::from_bytes(&bytes)
    }

    pub fn from_file(path: &Path) -> Result<Self, KeyError> {
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            let meta = std::fs::metadata(path).map_err(|e| KeyError::Io(e.kind().to_string()))?;
            let mode = meta.permissions().mode();
            if mode & 0o022 != 0 {
                return Err(KeyError::Permissions);
            }
            if mode & 0o044 != 0 {
                tracing::warn!("registrar key file is readable by group or others; prefer chmod 600");
            }
        }
        let text = Zeroizing::new(std::fs::read_to_string(path).map_err(|e| KeyError::Io(e.kind().to_string()))?);
        Self::from_keypair_json(&text)
    }

    /// Solana CLI keypair JSON, for `hd-registrar keygen` only.
    pub fn to_keypair_json(&self) -> Zeroizing<String> {
        let mut bytes = Zeroizing::new(Vec::with_capacity(64));
        bytes.extend_from_slice(self.signing.as_bytes());
        bytes.extend_from_slice(&self.pubkey());
        Zeroizing::new(serde_json::to_string(&*bytes).unwrap_or_default())
    }

    pub fn pubkey(&self) -> [u8; 32] {
        self.signing.verifying_key().to_bytes()
    }

    pub fn verifying_key(&self) -> VerifyingKey {
        self.signing.verifying_key()
    }

    pub fn sign(&self, voucher: Voucher) -> SignedVoucher {
        let message = voucher.preimage();
        let signature = self.signing.sign(&message).to_bytes();
        SignedVoucher { voucher, message, signature, registrar: self.pubkey() }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use ed25519_dalek::Signature;

    fn voucher() -> Voucher {
        Voucher {
            program_id: bs58::decode(HEADS_DOWN_PROGRAM_ID).into_vec().unwrap().try_into().unwrap(),
            authority: [0xAA; 32],
            p256_pubkey: {
                let mut k = [0x11; 33];
                k[0] = 0x03;
                k
            },
            level: 2,
            expiry_slot: 0x0102_0304_0506_0708,
        }
    }

    #[test]
    fn preimage_layout_is_exact() {
        let v = voucher();
        let p = v.preimage();
        assert_eq!(p.len(), 111);
        assert_eq!(&p[0..5], b"HDreg");
        assert_eq!(&p[5..37], &v.program_id);
        assert_eq!(&p[37..69], &[0xAA; 32]);
        assert_eq!(p[69], 0x03);
        assert_eq!(&p[70..102], &[0x11; 32]);
        assert_eq!(p[102], 2);
        assert_eq!(&p[103..111], &[8, 7, 6, 5, 4, 3, 2, 1]);
        assert_eq!(Voucher::from_preimage(&p), Some(v));
        assert_eq!(Voucher::from_preimage(&p[..110]), None);
        let mut bad = p;
        bad[0] = b'X';
        assert_eq!(Voucher::from_preimage(&bad), None);
    }

    #[test]
    fn instruction_bytes_match_the_solana_sdk_builder() {
        let key = RegistrarKey::from_seed(&[5u8; 32]);
        let signed = key.sign(voucher());
        let ours = signed.instruction_data();
        let sdk = solana_ed25519_program::new_ed25519_instruction_with_signature(
            &signed.message,
            &signed.signature,
            &signed.registrar,
        );
        assert_eq!(ours, sdk.data);
        assert_eq!(ours.len(), IX_LEN);
        assert_eq!(IX_LEN, 223);
        assert_eq!(sdk.program_id.to_string(), ED25519_PROGRAM_ID);
        assert!(sdk.accounts.is_empty());
    }

    /// Re-implements the Ed25519SigVerify precompile (agave-precompiles 4.3 `ed25519::verify`)
    /// over our bytes, then reads the pubkey and message back exactly as the program would.
    fn precompile_verify(data: &[u8]) -> Result<([u8; 32], Vec<u8>), &'static str> {
        let n = *data.first().ok_or("size")? as usize;
        if n != 1 || data.len() < 16 {
            return Err("size");
        }
        let u16_at = |i: usize| u16::from_le_bytes([data[i], data[i + 1]]) as usize;
        let (sig_off, sig_ix, pk_off, pk_ix, msg_off, msg_len, msg_ix) =
            (u16_at(2), u16_at(4), u16_at(6), u16_at(8), u16_at(10), u16_at(12), u16_at(14));
        if [sig_ix, pk_ix, msg_ix].iter().any(|ix| *ix != u16::MAX as usize) {
            return Err("foreign instruction index");
        }
        let sig: [u8; 64] = data.get(sig_off..sig_off + 64).ok_or("sig bounds")?.try_into().unwrap();
        let pk: [u8; 32] = data.get(pk_off..pk_off + 32).ok_or("pk bounds")?.try_into().unwrap();
        let msg = data.get(msg_off..msg_off + msg_len).ok_or("msg bounds")?;
        VerifyingKey::from_bytes(&pk)
            .map_err(|_| "pubkey")?
            .verify_strict(msg, &Signature::from_bytes(&sig))
            .map_err(|_| "signature")?;
        Ok((pk, msg.to_vec()))
    }

    #[test]
    fn precompile_accepts_it_and_the_program_reads_back_the_preimage() {
        let key = RegistrarKey::from_seed(&[5u8; 32]);
        let signed = key.sign(voucher());
        let data = signed.instruction_data();
        let (pk, msg) = precompile_verify(&data).unwrap();
        assert_eq!(pk, key.pubkey());
        assert_eq!(msg, voucher().preimage().to_vec());

        // Any bit flip in the message or signature makes the precompile fail the transaction.
        for i in [IX_MESSAGE_OFFSET, IX_MESSAGE_OFFSET + 102, IX_LEN - 1, IX_SIGNATURE_OFFSET + 5] {
            let mut t = data.clone();
            t[i] ^= 1;
            assert_eq!(precompile_verify(&t).unwrap_err(), "signature", "byte {i}");
        }
    }

    #[test]
    fn key_loading() {
        let key = RegistrarKey::from_seed(&[5u8; 32]);
        let json = key.to_keypair_json();
        let back = RegistrarKey::from_keypair_json(&json).unwrap();
        assert_eq!(back.pubkey(), key.pubkey());

        let b58 = bs58::encode([5u8; 32]).into_string();
        assert_eq!(RegistrarKey::from_base58(&b58).unwrap().pubkey(), key.pubkey());

        // Public half that does not match.
        let mut bytes: Vec<u8> = serde_json::from_str(&json).unwrap();
        bytes[40] ^= 1;
        assert!(matches!(
            RegistrarKey::from_keypair_json(&serde_json::to_string(&bytes).unwrap()),
            Err(KeyError::Inconsistent)
        ));
        assert!(matches!(RegistrarKey::from_keypair_json("[1,2,3]"), Err(KeyError::Format)));
        assert!(matches!(RegistrarKey::from_base58("0OIl"), Err(KeyError::Format)));
        assert!(!format!("{key:?}").contains(&hex::encode([5u8; 32])));
    }

    #[cfg(unix)]
    #[test]
    fn key_file_permissions_are_checked() {
        use std::os::unix::fs::PermissionsExt;
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("registrar-keypair.json");
        let key = RegistrarKey::generate().unwrap();
        std::fs::write(&path, key.to_keypair_json().as_bytes()).unwrap();
        std::fs::set_permissions(&path, std::fs::Permissions::from_mode(0o600)).unwrap();
        assert_eq!(RegistrarKey::from_file(&path).unwrap().pubkey(), key.pubkey());
        std::fs::set_permissions(&path, std::fs::Permissions::from_mode(0o666)).unwrap();
        assert!(matches!(RegistrarKey::from_file(&path), Err(KeyError::Permissions)));
    }
}
