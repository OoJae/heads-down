//! Append-only transparency log of every voucher the registrar issued (JSON Lines).
//!
//! Each line carries the full transcript: the voucher preimage and signature, the attestation
//! chain (base64 DER), the SIWS-bound authority and the challenge nonce. So anyone can re-verify
//! any voucher against Google's roots without trusting the registrar (THREAT_MODEL.md, K4).
//!
//! Lines are **hash-chained**, so rewriting or deleting history is detectable:
//!
//! ```text
//! entry_hash = SHA-256( "HDlog1" || prev_hash(32) || index u64 LE || message(111)
//!                      || signature(64) || nonce(16) || chain_hash(32) )
//! chain_hash = SHA-256( for each cert, leaf first: len u32 LE || DER )
//! prev_hash of entry 0 = 32 zero bytes
//! ```
//!
//! A line is written and `fsync`ed before the voucher is returned to the client, so every
//! voucher in circulation is in the log. On startup the whole log is re-verified; a broken
//! chain stops the registrar.

use std::collections::HashMap;
use std::fs::{File, OpenOptions};
use std::io::{BufRead, BufReader, Write};
use std::path::{Path, PathBuf};

use ed25519_dalek::{Signature, VerifyingKey};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use tokio::sync::Mutex;

use crate::util::{b64_decode, decode_hex_exact};
use crate::voucher::{Voucher, HDREG_LEN};

const DOMAIN: &[u8] = b"HDlog1";
/// Largest line accepted when reading back (a full chain is ~6 KB base64).
const MAX_LINE_BYTES: usize = 256 * 1024;

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct LogEntry {
    pub v: u8,
    pub index: u64,
    pub issued_at: String,
    pub issued_slot: u64,
    pub program_id: String,
    pub authority: String,
    pub p256_pubkey: String,
    pub level: u8,
    pub expiry_slot: u64,
    pub registrar: String,
    /// Hex of the 111-byte HDreg preimage.
    pub message: String,
    /// Hex of the 64-byte Ed25519 signature.
    pub signature: String,
    /// Hex of the 16-byte attestation nonce; `challenge = SHA-256("HDattest"||authority||nonce)`.
    pub nonce: String,
    /// Registrar's summary of the attestation (derivable from `chain`).
    pub attestation: serde_json::Value,
    /// Base64 DER certificates, leaf first.
    pub chain: Vec<String>,
    pub prev_hash: String,
    pub entry_hash: String,
}

/// What the handler supplies; the log fills in index and hashes.
pub struct NewEntry {
    pub issued_at: String,
    pub issued_slot: u64,
    pub message: [u8; HDREG_LEN],
    pub signature: [u8; 64],
    pub registrar: [u8; 32],
    pub nonce: [u8; 16],
    pub attestation: serde_json::Value,
    pub chain_der: Vec<Vec<u8>>,
}

#[derive(Debug, thiserror::Error)]
pub enum LogError {
    #[error("transparency log I/O error: {0}")]
    Io(String),
    #[error("transparency log line {line} is invalid: {reason}")]
    Invalid { line: u64, reason: &'static str },
}

fn io(e: std::io::Error) -> LogError {
    LogError::Io(e.kind().to_string())
}

pub fn chain_hash(chain_der: &[Vec<u8>]) -> [u8; 32] {
    let mut h = Sha256::new();
    for der in chain_der {
        h.update(u32::try_from(der.len()).unwrap_or(u32::MAX).to_le_bytes());
        h.update(der);
    }
    h.finalize().into()
}

pub fn entry_hash(
    prev: &[u8; 32],
    index: u64,
    message: &[u8; HDREG_LEN],
    signature: &[u8; 64],
    nonce: &[u8; 16],
    chain_hash: &[u8; 32],
) -> [u8; 32] {
    let mut h = Sha256::new();
    h.update(DOMAIN);
    h.update(prev);
    h.update(index.to_le_bytes());
    h.update(message);
    h.update(signature);
    h.update(nonce);
    h.update(chain_hash);
    h.finalize().into()
}

/// Checks one entry in isolation (everything except the link to its predecessor): hash,
/// signature, and that the JSON fields agree with the signed preimage.
fn check_entry(e: &LogEntry, line: u64) -> Result<[u8; 32], LogError> {
    let bad = |reason| LogError::Invalid { line, reason };
    if e.v != 1 || e.index != line {
        return Err(bad("version or index"));
    }
    let message: [u8; HDREG_LEN] = decode_hex_exact(&e.message).ok_or(bad("message encoding"))?;
    let signature: [u8; 64] = decode_hex_exact(&e.signature).ok_or(bad("signature encoding"))?;
    let nonce: [u8; 16] = decode_hex_exact(&e.nonce).ok_or(bad("nonce encoding"))?;
    let prev: [u8; 32] = decode_hex_exact(&e.prev_hash).ok_or(bad("prev_hash encoding"))?;
    let registrar = crate::util::decode_address(&e.registrar).ok_or(bad("registrar encoding"))?;
    let chain: Vec<Vec<u8>> =
        e.chain.iter().map(|c| b64_decode(c)).collect::<Option<_>>().ok_or(bad("chain encoding"))?;

    let v = Voucher::from_preimage(&message).ok_or(bad("preimage"))?;
    if crate::util::encode_address(&v.program_id) != e.program_id
        || crate::util::encode_address(&v.authority) != e.authority
        || hex::encode(v.p256_pubkey) != e.p256_pubkey
        || v.level != e.level
        || v.expiry_slot != e.expiry_slot
    {
        return Err(bad("fields disagree with the signed preimage"));
    }
    VerifyingKey::from_bytes(&registrar)
        .map_err(|_| bad("registrar key"))?
        .verify_strict(&message, &Signature::from_bytes(&signature))
        .map_err(|_| bad("voucher signature"))?;
    let hash = entry_hash(&prev, e.index, &message, &signature, &nonce, &chain_hash(&chain));
    if hex::encode(hash) != e.entry_hash {
        return Err(bad("entry_hash"));
    }
    Ok(hash)
}

struct Inner {
    file: File,
    next_index: u64,
    prev_hash: [u8; 32],
    /// Byte offset of each line, for paging reads.
    offsets: Vec<u64>,
    /// (authority, p256 hex) -> latest index, for voucher recovery.
    by_key: HashMap<(String, String), u64>,
    len_bytes: u64,
}

pub struct TransparencyLog {
    path: PathBuf,
    inner: Mutex<Inner>,
}

/// State recovered by replaying a log file.
struct Replayed {
    offsets: Vec<u64>,
    by_key: HashMap<(String, String), u64>,
    head: [u8; 32],
    len_bytes: u64,
}

/// Reads and fully verifies a log file. Returns the entries' offsets and the chain head.
fn replay(path: &Path) -> Result<Replayed, LogError> {
    let file = match File::open(path) {
        Ok(f) => f,
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => {
            return Ok(Replayed { offsets: Vec::new(), by_key: HashMap::new(), head: [0; 32], len_bytes: 0 })
        }
        Err(e) => return Err(io(e)),
    };
    let mut reader = BufReader::new(file);
    let mut offsets = Vec::new();
    let mut by_key = HashMap::new();
    let mut prev = [0u8; 32];
    let mut pos = 0u64;
    let mut line = String::new();
    let mut index = 0u64;
    loop {
        line.clear();
        let n = reader.read_line(&mut line).map_err(io)?;
        if n == 0 {
            break;
        }
        if n > MAX_LINE_BYTES || !line.ends_with('\n') {
            return Err(LogError::Invalid { line: index, reason: "truncated or oversized line" });
        }
        let e: LogEntry =
            serde_json::from_str(line.trim_end()).map_err(|_| LogError::Invalid { line: index, reason: "json" })?;
        if e.prev_hash != hex::encode(prev) {
            return Err(LogError::Invalid { line: index, reason: "hash chain broken" });
        }
        prev = check_entry(&e, index)?;
        offsets.push(pos);
        by_key.insert((e.authority.clone(), e.p256_pubkey.clone()), index);
        pos = pos.saturating_add(n as u64);
        index = index.saturating_add(1);
    }
    Ok(Replayed { offsets, by_key, head: prev, len_bytes: pos })
}

impl TransparencyLog {
    /// Opens (creating if needed) and verifies the whole existing log.
    pub fn open(path: &Path) -> Result<Self, LogError> {
        if let Some(parent) = path.parent() {
            if !parent.as_os_str().is_empty() {
                std::fs::create_dir_all(parent).map_err(io)?;
            }
        }
        let r = replay(path)?;
        let file = OpenOptions::new().create(true).append(true).open(path).map_err(io)?;
        Ok(Self {
            path: path.to_owned(),
            inner: Mutex::new(Inner {
                file,
                next_index: r.offsets.len() as u64,
                prev_hash: r.head,
                offsets: r.offsets,
                by_key: r.by_key,
                len_bytes: r.len_bytes,
            }),
        })
    }

    /// Appends one entry and fsyncs it. Returns the written entry.
    pub async fn append(&self, new: NewEntry) -> Result<LogEntry, LogError> {
        let mut inner = self.inner.lock().await;
        let index = inner.next_index;
        let v = Voucher::from_preimage(&new.message).ok_or(LogError::Invalid { line: index, reason: "preimage" })?;
        let hash =
            entry_hash(&inner.prev_hash, index, &new.message, &new.signature, &new.nonce, &chain_hash(&new.chain_der));
        let entry = LogEntry {
            v: 1,
            index,
            issued_at: new.issued_at,
            issued_slot: new.issued_slot,
            program_id: crate::util::encode_address(&v.program_id),
            authority: crate::util::encode_address(&v.authority),
            p256_pubkey: hex::encode(v.p256_pubkey),
            level: v.level,
            expiry_slot: v.expiry_slot,
            registrar: crate::util::encode_address(&new.registrar),
            message: hex::encode(new.message),
            signature: hex::encode(new.signature),
            nonce: hex::encode(new.nonce),
            attestation: new.attestation,
            chain: new.chain_der.iter().map(|c| crate::util::b64_encode(c)).collect(),
            prev_hash: hex::encode(inner.prev_hash),
            entry_hash: hex::encode(hash),
        };
        let mut line = serde_json::to_vec(&entry).map_err(|_| LogError::Io("serialize".into()))?;
        line.push(b'\n');
        if let Err(e) = inner.file.write_all(&line).and_then(|()| inner.file.sync_data()) {
            // Never leave a torn line behind: later appends would follow it and the replay at
            // the next start would (rightly) refuse the whole log.
            let _ = inner.file.set_len(inner.len_bytes);
            return Err(io(e));
        }
        let offset = inner.len_bytes;
        inner.offsets.push(offset);
        inner.len_bytes = offset.saturating_add(line.len() as u64);
        inner.by_key.insert((entry.authority.clone(), entry.p256_pubkey.clone()), index);
        inner.prev_hash = hash;
        inner.next_index = index.saturating_add(1);
        Ok(entry)
    }

    pub async fn len(&self) -> u64 {
        self.inner.lock().await.next_index
    }

    pub async fn is_empty(&self) -> bool {
        self.len().await == 0
    }

    pub async fn head(&self) -> (u64, String) {
        let inner = self.inner.lock().await;
        (inner.next_index, hex::encode(inner.prev_hash))
    }

    /// Entries `[from, from + limit)`. Reads only the byte range of lines that were fully
    /// written and synced when the call started, so a concurrent append is never half-read.
    pub async fn read_range(&self, from: u64, limit: usize) -> Result<Vec<LogEntry>, LogError> {
        let (start, end) = {
            let inner = self.inner.lock().await;
            let first = usize::try_from(from).unwrap_or(usize::MAX);
            let Some(start) = inner.offsets.get(first).copied() else {
                return Ok(Vec::new());
            };
            let end = first.checked_add(limit).and_then(|i| inner.offsets.get(i)).copied().unwrap_or(inner.len_bytes);
            (start, end)
        };
        let path = self.path.clone();
        tokio::task::spawn_blocking(move || -> Result<Vec<LogEntry>, LogError> {
            use std::io::{Read, Seek, SeekFrom};
            let mut file = File::open(&path).map_err(io)?;
            file.seek(SeekFrom::Start(start)).map_err(io)?;
            let reader = BufReader::new(file.take(end.saturating_sub(start)));
            let mut out = Vec::new();
            for (i, line) in reader.lines().take(limit).enumerate() {
                let line = line.map_err(io)?;
                let e: LogEntry = serde_json::from_str(&line)
                    .map_err(|_| LogError::Invalid { line: from.saturating_add(i as u64), reason: "json" })?;
                out.push(e);
            }
            Ok(out)
        })
        .await
        .map_err(|_| LogError::Io("join".into()))?
    }

    /// The latest voucher issued for (authority, p256), for clients that lost a response.
    pub async fn find(&self, authority: &str, p256_hex: &str) -> Result<Option<LogEntry>, LogError> {
        let index = {
            let inner = self.inner.lock().await;
            inner.by_key.get(&(authority.to_owned(), p256_hex.to_owned())).copied()
        };
        match index {
            Some(i) => Ok(self.read_range(i, 1).await?.into_iter().next()),
            None => Ok(None),
        }
    }
}

/// Offline audit: re-verifies the hash chain and every voucher signature, and returns the
/// entries for further checks (for example re-verifying each chain against Google's roots).
pub fn audit(path: &Path) -> Result<Vec<LogEntry>, LogError> {
    replay(path)?;
    let file = File::open(path).map_err(io)?;
    BufReader::new(file)
        .lines()
        .enumerate()
        .map(|(i, l)| {
            let l = l.map_err(io)?;
            serde_json::from_str(&l).map_err(|_| LogError::Invalid { line: i as u64, reason: "json" })
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::voucher::RegistrarKey;

    fn new_entry(key: &RegistrarKey, authority: u8, slot: u64) -> NewEntry {
        let signed = key.sign(Voucher {
            program_id: [1; 32],
            authority: [authority; 32],
            p256_pubkey: [2; 33],
            level: 1,
            expiry_slot: slot + 1000,
        });
        NewEntry {
            issued_at: "2026-09-29T12:00:00Z".into(),
            issued_slot: slot,
            message: signed.message,
            signature: signed.signature,
            registrar: signed.registrar,
            nonce: [9; 16],
            attestation: serde_json::json!({"level": 1}),
            chain_der: vec![vec![0x30, 0x00], vec![0x30, 0x01, 0x00]],
        }
    }

    #[tokio::test]
    async fn append_reopen_verify_and_page() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("log/attestations.jsonl");
        let key = RegistrarKey::from_seed(&[3; 32]);
        {
            let log = TransparencyLog::open(&path).unwrap();
            for i in 0..5u8 {
                let e = log.append(new_entry(&key, i, 100 + u64::from(i))).await.unwrap();
                assert_eq!(e.index, u64::from(i));
            }
        }
        let log = TransparencyLog::open(&path).unwrap();
        assert_eq!(log.len().await, 5);
        let e = log.append(new_entry(&key, 7, 200)).await.unwrap();
        assert_eq!(e.index, 5);
        let page = log.read_range(2, 3).await.unwrap();
        assert_eq!(page.iter().map(|e| e.index).collect::<Vec<_>>(), vec![2, 3, 4]);
        assert!(log.read_range(99, 3).await.unwrap().is_empty());
        let found = log.find(&crate::util::encode_address(&[7; 32]), &hex::encode([2u8; 33])).await.unwrap();
        assert_eq!(found.unwrap().index, 5);
        assert_eq!(audit(&path).unwrap().len(), 6);
        assert_eq!(page[0].prev_hash, log.read_range(1, 1).await.unwrap()[0].entry_hash);
    }

    #[tokio::test]
    async fn tampering_is_detected() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("attestations.jsonl");
        let key = RegistrarKey::from_seed(&[3; 32]);
        {
            let log = TransparencyLog::open(&path).unwrap();
            for i in 0..3u8 {
                log.append(new_entry(&key, i, 100)).await.unwrap();
            }
        }
        let original = std::fs::read_to_string(&path).unwrap();
        let lines: Vec<&str> = original.lines().collect();

        // Delete a middle line.
        std::fs::write(&path, format!("{}\n{}\n", lines[0], lines[2])).unwrap();
        assert!(TransparencyLog::open(&path).is_err());

        // Raise a level in the JSON (not in the signed preimage).
        std::fs::write(&path, original.replacen("\"level\":1,\"expiry_slot\"", "\"level\":2,\"expiry_slot\"", 1))
            .unwrap();
        assert!(matches!(
            TransparencyLog::open(&path),
            Err(LogError::Invalid { reason: "fields disagree with the signed preimage", .. })
        ));

        // Swap a certificate in the published chain.
        std::fs::write(&path, original.replacen("MAA=", "MAE=", 1)).unwrap();
        assert!(matches!(TransparencyLog::open(&path), Err(LogError::Invalid { reason: "entry_hash", .. })));

        // Truncated final line (crash mid-write).
        std::fs::write(&path, &original[..original.len() - 10]).unwrap();
        assert!(TransparencyLog::open(&path).is_err());

        std::fs::write(&path, &original).unwrap();
        assert!(TransparencyLog::open(&path).is_ok());
    }
}
