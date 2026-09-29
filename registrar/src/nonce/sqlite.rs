//! SQLite-backed nonce store. Survives restarts and can be shared by several registrar
//! processes on one host (SQLite serializes writers; `DELETE ... RETURNING` is a single
//! atomic statement, so two processes cannot both consume one nonce).

use std::path::Path;
use std::sync::Mutex;
use std::time::Duration;

use rusqlite::{params, Connection, OptionalExtension};

use super::{NoncePurpose, NonceRecord, NonceStore, StoreError};

pub struct SqliteNonceStore {
    conn: Mutex<Connection>,
    max_outstanding: usize,
}

fn backend(e: rusqlite::Error) -> StoreError {
    // The message carries no nonce values: rusqlite errors do not echo bound parameters.
    StoreError::Backend(e.to_string())
}

impl SqliteNonceStore {
    pub fn open(path: &Path, max_outstanding: usize) -> Result<Self, StoreError> {
        let conn = Connection::open(path).map_err(backend)?;
        Self::init(conn, max_outstanding)
    }

    pub fn open_in_memory(max_outstanding: usize) -> Result<Self, StoreError> {
        let conn = Connection::open_in_memory().map_err(backend)?;
        Self::init(conn, max_outstanding)
    }

    fn init(conn: Connection, max_outstanding: usize) -> Result<Self, StoreError> {
        conn.busy_timeout(Duration::from_secs(5)).map_err(backend)?;
        // WAL lets readers and the single writer proceed concurrently; FULL sync so an issued
        // nonce is never "forgotten" in a way that could matter (a lost nonce only fails closed).
        conn.pragma_update(None, "journal_mode", "WAL").map_err(backend)?;
        conn.pragma_update(None, "synchronous", "FULL").map_err(backend)?;
        conn.execute_batch(
            "CREATE TABLE IF NOT EXISTS nonces (
                 nonce      TEXT    PRIMARY KEY NOT NULL,
                 purpose    INTEGER NOT NULL,
                 subject    TEXT,
                 expires_at INTEGER NOT NULL
             ) WITHOUT ROWID;
             CREATE INDEX IF NOT EXISTS nonces_expires_at ON nonces (expires_at);",
        )
        .map_err(backend)?;
        Ok(Self { conn: Mutex::new(conn), max_outstanding })
    }

    fn lock(&self) -> Result<std::sync::MutexGuard<'_, Connection>, StoreError> {
        self.conn.lock().map_err(|_| StoreError::Backend("sqlite store lock poisoned".into()))
    }

    fn count(conn: &Connection) -> Result<usize, StoreError> {
        let n: i64 = conn.query_row("SELECT COUNT(*) FROM nonces", [], |r| r.get(0)).map_err(backend)?;
        usize::try_from(n).map_err(|_| StoreError::Backend("negative count".into()))
    }
}

impl NonceStore for SqliteNonceStore {
    fn insert(&self, nonce: &str, record: &NonceRecord, now: i64) -> Result<(), StoreError> {
        let conn = self.lock()?;
        if Self::count(&conn)? >= self.max_outstanding {
            conn.execute("DELETE FROM nonces WHERE expires_at <= ?1", params![now]).map_err(backend)?;
            if Self::count(&conn)? >= self.max_outstanding {
                return Err(StoreError::Full);
            }
        }
        let res = conn.execute(
            "INSERT INTO nonces (nonce, purpose, subject, expires_at) VALUES (?1, ?2, ?3, ?4)",
            params![nonce, record.purpose.as_i64(), record.subject, record.expires_at],
        );
        match res {
            Ok(_) => Ok(()),
            Err(rusqlite::Error::SqliteFailure(e, _)) if e.code == rusqlite::ErrorCode::ConstraintViolation => {
                Err(StoreError::Collision)
            }
            Err(e) => Err(backend(e)),
        }
    }

    fn take(&self, nonce: &str) -> Result<Option<NonceRecord>, StoreError> {
        let conn = self.lock()?;
        let row = conn
            .query_row(
                "DELETE FROM nonces WHERE nonce = ?1 RETURNING purpose, subject, expires_at",
                params![nonce],
                |r| Ok((r.get::<_, i64>(0)?, r.get::<_, Option<String>>(1)?, r.get::<_, i64>(2)?)),
            )
            .optional()
            .map_err(backend)?;
        match row {
            None => Ok(None),
            Some((purpose, subject, expires_at)) => {
                let purpose = NoncePurpose::from_i64(purpose)
                    .ok_or_else(|| StoreError::Backend("unknown nonce purpose in database".into()))?;
                Ok(Some(NonceRecord { purpose, subject, expires_at }))
            }
        }
    }

    fn purge_expired(&self, now: i64) -> Result<usize, StoreError> {
        let conn = self.lock()?;
        conn.execute("DELETE FROM nonces WHERE expires_at <= ?1", params![now]).map_err(backend)
    }

    fn len(&self) -> Result<usize, StoreError> {
        Self::count(&*self.lock()?)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::nonce::{consume, issue, ConsumeError};
    use std::sync::Arc;

    #[test]
    fn conforms_in_memory() {
        crate::nonce::conformance::run_all(&|cap| Arc::new(SqliteNonceStore::open_in_memory(cap).unwrap()));
    }

    #[test]
    fn conforms_on_disk() {
        let dir = tempfile::tempdir().unwrap();
        let counter = std::sync::atomic::AtomicUsize::new(0);
        crate::nonce::conformance::run_all(&|cap| {
            let i = counter.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
            Arc::new(SqliteNonceStore::open(&dir.path().join(format!("n{i}.db")), cap).unwrap())
        });
    }

    #[test]
    fn nonce_survives_restart_and_stays_single_use() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("nonces.db");
        let n = {
            let s = SqliteNonceStore::open(&path, 10).unwrap();
            issue(&s, NoncePurpose::Siws, None, 0, 600).unwrap().0
        };
        let s = SqliteNonceStore::open(&path, 10).unwrap();
        consume(&s, &n, NoncePurpose::Siws, None, 1).unwrap();
        assert!(matches!(consume(&s, &n, NoncePurpose::Siws, None, 1), Err(ConsumeError::NotFound)));
    }

    #[test]
    fn two_connections_one_winner() {
        // Two independent connections (as two registrar processes would have) race to consume.
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("shared.db");
        let a = Arc::new(SqliteNonceStore::open(&path, 1000).unwrap());
        let b = Arc::new(SqliteNonceStore::open(&path, 1000).unwrap());
        for _ in 0..20 {
            let (n, _) = issue(a.as_ref(), NoncePurpose::Siws, None, 0, 600).unwrap();
            let (n1, n2) = (n.clone(), n);
            let (a2, b2) = (Arc::clone(&a), Arc::clone(&b));
            let t1 = std::thread::spawn(move || consume(a2.as_ref(), &n1, NoncePurpose::Siws, None, 1).is_ok());
            let t2 = std::thread::spawn(move || consume(b2.as_ref(), &n2, NoncePurpose::Siws, None, 1).is_ok());
            let wins = [t1.join().unwrap(), t2.join().unwrap()].iter().filter(|w| **w).count();
            assert_eq!(wins, 1);
        }
    }
}
