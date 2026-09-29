//! In-process nonce store. Suitable for a single registrar instance; nonces do not survive a
//! restart (which only forces clients to fetch a new one).

use std::collections::HashMap;
use std::sync::Mutex;

use super::{NonceRecord, NonceStore, StoreError};

pub struct MemoryNonceStore {
    inner: Mutex<HashMap<String, NonceRecord>>,
    max_outstanding: usize,
}

impl MemoryNonceStore {
    /// `max_outstanding` bounds memory under a nonce-request flood (rate limiting is the first
    /// line; this is the backstop).
    pub fn new(max_outstanding: usize) -> Self {
        Self { inner: Mutex::new(HashMap::new()), max_outstanding }
    }

    fn lock(&self) -> Result<std::sync::MutexGuard<'_, HashMap<String, NonceRecord>>, StoreError> {
        self.inner.lock().map_err(|_| StoreError::Backend("memory store lock poisoned".into()))
    }
}

impl NonceStore for MemoryNonceStore {
    fn insert(&self, nonce: &str, record: &NonceRecord, now: i64) -> Result<(), StoreError> {
        let mut map = self.lock()?;
        if map.contains_key(nonce) {
            return Err(StoreError::Collision);
        }
        if map.len() >= self.max_outstanding {
            map.retain(|_, r| r.expires_at > now);
            if map.len() >= self.max_outstanding {
                return Err(StoreError::Full);
            }
        }
        map.insert(nonce.to_owned(), record.clone());
        Ok(())
    }

    fn take(&self, nonce: &str) -> Result<Option<NonceRecord>, StoreError> {
        // Remove-and-return under one lock acquisition: atomic with respect to other takers.
        Ok(self.lock()?.remove(nonce))
    }

    fn purge_expired(&self, now: i64) -> Result<usize, StoreError> {
        let mut map = self.lock()?;
        let before = map.len();
        map.retain(|_, r| r.expires_at > now);
        Ok(before.saturating_sub(map.len()))
    }

    fn len(&self) -> Result<usize, StoreError> {
        Ok(self.lock()?.len())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Arc;

    #[test]
    fn conforms() {
        crate::nonce::conformance::run_all(&|cap| Arc::new(MemoryNonceStore::new(cap)));
    }
}
