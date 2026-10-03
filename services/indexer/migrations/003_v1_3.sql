-- Heads Down indexer schema, v1.3 (programs/heads-down/INTERFACE.md v1.2 section 11 and v1.3
-- section 12).
--
-- * The v1.2 SKR events (tags 11 to 23: Stack, Focus Bond, Gift a Rig, Bury auction) and the
--   v1.3 events (tags 24 to 27: governance rotation, ShiftLogClosed) in one table, one row per
--   event, with the decoded fields as JSON under the contract's snake_case names. u64 / i64
--   values are JSON strings (a JSON number cannot hold a u64); u8 / u32 are JSON numbers.
-- * A heartbeat applied by `stack_checkin` is stored in hd_heartbeats with kind 'record': the
--   program applies it exactly as `record_heartbeats` does.

CREATE TABLE ev_ext (
  dataset     TEXT NOT NULL,
  signature   tx_signature NOT NULL,
  idx         INTEGER NOT NULL CHECK (idx >= 0),
  slot        BIGINT NOT NULL,
  block_time  BIGINT,
  tag         SMALLINT NOT NULL CHECK (tag >= 11 AND tag <= 255),
  name        TEXT NOT NULL,
  -- The event's `rig` field, when it has one.
  rig         sol_address,
  fields      JSONB NOT NULL,
  raw         BYTEA NOT NULL,
  PRIMARY KEY (dataset, signature, idx),
  FOREIGN KEY (dataset, signature) REFERENCES txs (dataset, signature)
);
CREATE INDEX ev_ext_name ON ev_ext (dataset, name, slot);
CREATE INDEX ev_ext_rig ON ev_ext (dataset, rig, slot) WHERE rig IS NOT NULL;
