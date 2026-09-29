//! Single-use, server-issued nonces with a TTL.
//!
//! Two consumers share one store:
//! - SIWS sign-in (`POST /siws/nonce` -> `POST /siws/verify`), unbound at issue time.
//! - Key Attestation challenges (`GET /attest/challenge` -> `POST /attest`), bound at issue time
//!   to the authority of the SIWS session that asked for it.
//!
//! Replay safety rests on [`NonceStore::take`] being an **atomic remove-and-return**: of any
//! number of concurrent consumers of one nonce, exactly one observes the record. Everything
//! else (purpose, binding, expiry) is checked on the returned record, so a failed check still
//! burns the nonce.

pub mod memory;
pub mod sqlite;

use crate::util::{random_array, RandomError};

pub use memory::MemoryNonceStore;
pub use sqlite::SqliteNonceStore;

/// Nonces are 128-bit (16 random bytes), hex-encoded: 32 alphanumeric characters, which
/// satisfies SIWS/EIP-4361 (`[A-Za-z0-9]{8,}`) and MWA's `SignInWithSolana.Payload` check.
pub const NONCE_BYTES: usize = 16;
pub const NONCE_HEX_LEN: usize = NONCE_BYTES * 2;

/// What a nonce may be used for. Stored with the nonce; a nonce issued for one purpose is
/// never accepted for the other.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum NoncePurpose {
    Siws = 1,
    Attest = 2,
}

impl NoncePurpose {
    pub fn as_i64(self) -> i64 {
        self as i64
    }

    pub fn from_i64(v: i64) -> Option<Self> {
        match v {
            1 => Some(Self::Siws),
            2 => Some(Self::Attest),
            _ => None,
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct NonceRecord {
    pub purpose: NoncePurpose,
    /// For attestation nonces: the base58 authority the challenge was issued to.
    pub subject: Option<String>,
    /// Unix seconds. The nonce is dead at `now >= expires_at`.
    pub expires_at: i64,
}

#[derive(Debug, thiserror::Error)]
pub enum StoreError {
    #[error("too many outstanding nonces")]
    Full,
    #[error("nonce collision")]
    Collision,
    #[error("nonce store backend failure")]
    Backend(String),
}

/// Storage for outstanding nonces. Implementations must make [`take`](Self::take) atomic.
pub trait NonceStore: Send + Sync + 'static {
    /// Stores a fresh nonce. Fails with [`StoreError::Collision`] if it already exists and
    /// with [`StoreError::Full`] when the outstanding-nonce cap is reached (after purging
    /// expired entries).
    fn insert(&self, nonce: &str, record: &NonceRecord, now: i64) -> Result<(), StoreError>;

    /// Atomically removes the nonce and returns what was stored, or `None` if it is unknown
    /// (never issued, already consumed, or purged).
    fn take(&self, nonce: &str) -> Result<Option<NonceRecord>, StoreError>;

    /// Deletes every record with `expires_at <= now`. Returns how many were deleted.
    fn purge_expired(&self, now: i64) -> Result<usize, StoreError>;

    /// Number of stored records (including not-yet-purged expired ones).
    fn len(&self) -> Result<usize, StoreError>;

    fn is_empty(&self) -> Result<bool, StoreError> {
        Ok(self.len()? == 0)
    }
}

#[derive(Debug, thiserror::Error)]
pub enum ConsumeError {
    /// Never issued, already used, or issued for another purpose.
    #[error("nonce unknown or already used")]
    NotFound,
    #[error("nonce expired")]
    Expired,
    /// An attestation nonce presented by a different authority than it was issued to.
    #[error("nonce was issued to a different subject")]
    WrongSubject,
    #[error(transparent)]
    Store(#[from] StoreError),
}

/// Issues a fresh random nonce, stores it, and returns it with its expiry.
pub fn issue(
    store: &dyn NonceStore,
    purpose: NoncePurpose,
    subject: Option<String>,
    now: i64,
    ttl_secs: i64,
) -> Result<(String, i64), IssueError> {
    let expires_at = now.checked_add(ttl_secs).ok_or(IssueError::Clock)?;
    let record = NonceRecord { purpose, subject, expires_at };
    // A 128-bit collision is not a realistic event; retry once anyway rather than failing.
    for _ in 0..2 {
        let bytes: [u8; NONCE_BYTES] = random_array()?;
        let nonce = hex::encode(bytes);
        match store.insert(&nonce, &record, now) {
            Ok(()) => return Ok((nonce, expires_at)),
            Err(StoreError::Collision) => continue,
            Err(e) => return Err(IssueError::Store(e)),
        }
    }
    Err(IssueError::Store(StoreError::Collision))
}

#[derive(Debug, thiserror::Error)]
pub enum IssueError {
    #[error(transparent)]
    Random(#[from] RandomError),
    #[error(transparent)]
    Store(#[from] StoreError),
    #[error("clock out of range")]
    Clock,
}

/// Consumes a nonce exactly once. The record is removed before any check, so a nonce that
/// fails a check (wrong purpose, wrong subject, expired) can never be retried.
pub fn consume(
    store: &dyn NonceStore,
    nonce: &str,
    purpose: NoncePurpose,
    subject: Option<&str>,
    now: i64,
) -> Result<(), ConsumeError> {
    if !is_well_formed(nonce) {
        return Err(ConsumeError::NotFound);
    }
    let record = store.take(nonce)?.ok_or(ConsumeError::NotFound)?;
    if record.purpose != purpose {
        return Err(ConsumeError::NotFound);
    }
    if now >= record.expires_at {
        return Err(ConsumeError::Expired);
    }
    if record.subject.as_deref() != subject {
        return Err(ConsumeError::WrongSubject);
    }
    Ok(())
}

/// Our nonces are exactly 32 lowercase hex characters. Anything else is rejected before it
/// reaches the store.
pub fn is_well_formed(nonce: &str) -> bool {
    nonce.len() == NONCE_HEX_LEN && nonce.bytes().all(|b| matches!(b, b'0'..=b'9' | b'a'..=b'f'))
}

/// Shared behavioural tests, run against every backend.
#[cfg(test)]
pub(crate) mod conformance {
    use super::*;
    use std::sync::atomic::{AtomicUsize, Ordering};
    use std::sync::Arc;

    pub fn run_all(make: &dyn Fn(usize) -> Arc<dyn NonceStore>) {
        single_use(make(100).as_ref());
        expiry_and_purge(make(100).as_ref());
        purpose_and_subject_binding(make(100).as_ref());
        cap_enforced(make(3).as_ref());
        collision_rejected(make(100).as_ref());
        concurrent_take_has_one_winner(make(1000));
    }

    fn single_use(store: &dyn NonceStore) {
        let (n, exp) = issue(store, NoncePurpose::Siws, None, 1_000, 600).unwrap();
        assert!(is_well_formed(&n));
        assert_eq!(exp, 1_600);
        consume(store, &n, NoncePurpose::Siws, None, 1_001).unwrap();
        assert!(matches!(
            consume(store, &n, NoncePurpose::Siws, None, 1_002),
            Err(ConsumeError::NotFound)
        ));
        // Never issued.
        assert!(matches!(
            consume(store, &"0".repeat(32), NoncePurpose::Siws, None, 1_002),
            Err(ConsumeError::NotFound)
        ));
        // Malformed never reaches the store.
        assert!(matches!(
            consume(store, "not-a-nonce", NoncePurpose::Siws, None, 1_002),
            Err(ConsumeError::NotFound)
        ));
    }

    fn expiry_and_purge(store: &dyn NonceStore) {
        let (n, _) = issue(store, NoncePurpose::Siws, None, 1_000, 600).unwrap();
        assert!(matches!(
            consume(store, &n, NoncePurpose::Siws, None, 1_600),
            Err(ConsumeError::Expired)
        ));
        // Burned even though it failed.
        assert!(matches!(
            consume(store, &n, NoncePurpose::Siws, None, 1_001),
            Err(ConsumeError::NotFound)
        ));
        let (a, _) = issue(store, NoncePurpose::Siws, None, 1_000, 10).unwrap();
        let (b, _) = issue(store, NoncePurpose::Siws, None, 1_000, 1_000).unwrap();
        assert_eq!(store.purge_expired(1_010).unwrap(), 1);
        assert!(store.take(&a).unwrap().is_none());
        assert!(store.take(&b).unwrap().is_some());
        assert!(store.is_empty().unwrap());
    }

    fn purpose_and_subject_binding(store: &dyn NonceStore) {
        let (n, _) = issue(store, NoncePurpose::Attest, Some("alice".into()), 0, 600).unwrap();
        assert!(matches!(consume(store, &n, NoncePurpose::Siws, None, 1), Err(ConsumeError::NotFound)));
        let (n, _) = issue(store, NoncePurpose::Attest, Some("alice".into()), 0, 600).unwrap();
        assert!(matches!(
            consume(store, &n, NoncePurpose::Attest, Some("mallory"), 1),
            Err(ConsumeError::WrongSubject)
        ));
        assert!(matches!(
            consume(store, &n, NoncePurpose::Attest, Some("alice"), 1),
            Err(ConsumeError::NotFound)
        ));
        let (n, _) = issue(store, NoncePurpose::Attest, Some("alice".into()), 0, 600).unwrap();
        consume(store, &n, NoncePurpose::Attest, Some("alice"), 1).unwrap();
    }

    fn cap_enforced(store: &dyn NonceStore) {
        for _ in 0..3 {
            issue(store, NoncePurpose::Siws, None, 0, 10).unwrap();
        }
        assert!(matches!(
            issue(store, NoncePurpose::Siws, None, 0, 10),
            Err(IssueError::Store(StoreError::Full))
        ));
        // Expired entries are purged to make room.
        issue(store, NoncePurpose::Siws, None, 10, 10).unwrap();
        assert_eq!(store.len().unwrap(), 1);
    }

    fn collision_rejected(store: &dyn NonceStore) {
        let rec = NonceRecord { purpose: NoncePurpose::Siws, subject: None, expires_at: 10 };
        let n = "a".repeat(32);
        store.insert(&n, &rec, 0).unwrap();
        assert!(matches!(store.insert(&n, &rec, 0), Err(StoreError::Collision)));
        assert_eq!(store.take(&n).unwrap(), Some(rec));
    }

    fn concurrent_take_has_one_winner(store: Arc<dyn NonceStore>) {
        for _round in 0..20 {
            let (n, _) = issue(store.as_ref(), NoncePurpose::Siws, None, 0, 600).unwrap();
            let winners = Arc::new(AtomicUsize::new(0));
            let threads: Vec<_> = (0..8)
                .map(|_| {
                    let store = Arc::clone(&store);
                    let winners = Arc::clone(&winners);
                    let n = n.clone();
                    std::thread::spawn(move || {
                        if consume(store.as_ref(), &n, NoncePurpose::Siws, None, 1).is_ok() {
                            winners.fetch_add(1, Ordering::SeqCst);
                        }
                    })
                })
                .collect();
            for t in threads {
                t.join().unwrap();
            }
            assert_eq!(winners.load(Ordering::SeqCst), 1);
        }
    }
}
