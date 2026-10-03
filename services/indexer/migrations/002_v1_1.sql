-- Heads Down indexer schema, v1.1 (programs/heads-down/INTERFACE.md v1.1).
--
-- * Five new heads_down events (§7): RigRegistered, RigClosed, HeartbeatsRecorded, ShiftBroken,
--   and ShiftEndedV2, which is stored in ev_shift_ended itself (tag = 10, with start_round,
--   end_round and mode). The extractor keeps only tag 10 when both tags are logged for the
--   same shift, so one ended shift is always one row.
-- * Heartbeat entries of `dig` / `record_heartbeats` and the `arm_shift` plans, decoded from
--   instruction data: they are the only record of which ORE rounds each lease covered (a
--   heartbeat applied inside `dig` emits no event of its own, §10).
-- * ORE Round account snapshots taken after the round's reset: exact per-square totals for
--   ORE's checkpoint arithmetic (the SOL each square returned).

ALTER TABLE ev_shift_ended ADD COLUMN tag SMALLINT NOT NULL DEFAULT 4 CHECK (tag IN (4, 10));
ALTER TABLE ev_shift_ended ADD COLUMN start_round u64;
ALTER TABLE ev_shift_ended ADD COLUMN end_round u64;
ALTER TABLE ev_shift_ended ADD COLUMN mode SMALLINT CHECK (mode IS NULL OR (mode >= 0 AND mode <= 255));
ALTER TABLE ev_shift_ended ADD CONSTRAINT ev_shift_ended_v2_fields
  CHECK ((tag = 10) = (start_round IS NOT NULL AND end_round IS NOT NULL AND mode IS NOT NULL));
CREATE INDEX ev_shift_ended_rig ON ev_shift_ended (dataset, rig, shift_id);
CREATE INDEX ev_shift_armed_rig_shift ON ev_shift_armed (dataset, rig, shift_id);
CREATE INDEX ev_rig_skipped_rig ON ev_rig_skipped (dataset, rig, slot);
CREATE INDEX ev_rig_skipped_code ON ev_rig_skipped (dataset, error_code, slot);
CREATE INDEX ev_rig_skipped_slot ON ev_rig_skipped (dataset, slot, signature, idx);

CREATE TABLE ev_rig_registered (
  dataset            TEXT NOT NULL,
  signature          tx_signature NOT NULL,
  idx                INTEGER NOT NULL CHECK (idx >= 0),
  slot               BIGINT NOT NULL,
  block_time         BIGINT,
  rig                sol_address NOT NULL,
  authority          sol_address NOT NULL,
  tier               SMALLINT NOT NULL CHECK (tier >= 0 AND tier <= 255),
  attestation_level  SMALLINT NOT NULL CHECK (attestation_level >= 0 AND attestation_level <= 255),
  raw                BYTEA NOT NULL,
  PRIMARY KEY (dataset, signature, idx),
  FOREIGN KEY (dataset, signature) REFERENCES txs (dataset, signature)
);
CREATE INDEX ev_rig_registered_rig ON ev_rig_registered (dataset, rig, slot);

CREATE TABLE ev_rig_closed (
  dataset     TEXT NOT NULL,
  signature   tx_signature NOT NULL,
  idx         INTEGER NOT NULL CHECK (idx >= 0),
  slot        BIGINT NOT NULL,
  block_time  BIGINT,
  rig         sol_address NOT NULL,
  raw         BYTEA NOT NULL,
  PRIMARY KEY (dataset, signature, idx),
  FOREIGN KEY (dataset, signature) REFERENCES txs (dataset, signature)
);
CREATE INDEX ev_rig_closed_rig ON ev_rig_closed (dataset, rig, slot);

CREATE TABLE ev_heartbeats_recorded (
  dataset            TEXT NOT NULL,
  signature          tx_signature NOT NULL,
  idx                INTEGER NOT NULL CHECK (idx >= 0),
  slot               BIGINT NOT NULL,
  block_time         BIGINT,
  rig                sol_address NOT NULL,
  round_id           u64 NOT NULL,
  dark_rounds_added  u64 NOT NULL,
  raw                BYTEA NOT NULL,
  PRIMARY KEY (dataset, signature, idx),
  FOREIGN KEY (dataset, signature) REFERENCES txs (dataset, signature)
);
CREATE INDEX ev_heartbeats_recorded_rig ON ev_heartbeats_recorded (dataset, rig, slot);

CREATE TABLE ev_shift_broken (
  dataset     TEXT NOT NULL,
  signature   tx_signature NOT NULL,
  idx         INTEGER NOT NULL CHECK (idx >= 0),
  slot        BIGINT NOT NULL,
  block_time  BIGINT,
  rig         sol_address NOT NULL,
  shift_id    u64 NOT NULL,
  reason      SMALLINT NOT NULL CHECK (reason >= 0 AND reason <= 255),
  raw         BYTEA NOT NULL,
  PRIMARY KEY (dataset, signature, idx),
  FOREIGN KEY (dataset, signature) REFERENCES txs (dataset, signature)
);
CREATE INDEX ev_shift_broken_rig ON ev_shift_broken (dataset, rig, shift_id);

-- One row per heartbeat entry of a successful dig / record_heartbeats instruction.
-- `applied` = the heartbeat verified and its lease was granted; NULL when the logs could not be
-- matched to the instruction (e.g. truncated logs).
CREATE TABLE hd_heartbeats (
  dataset       TEXT NOT NULL,
  signature     tx_signature NOT NULL,
  ix_idx        INTEGER NOT NULL CHECK (ix_idx >= 0),
  entry_idx     INTEGER NOT NULL CHECK (entry_idx >= 0 AND entry_idx < 32),
  slot          BIGINT NOT NULL,
  block_time    BIGINT,
  rig           sol_address NOT NULL,
  authority     sol_address,
  kind          TEXT NOT NULL CHECK (kind IN ('dig', 'record')),
  fresh         BOOLEAN NOT NULL,
  counter       u64 NOT NULL,
  hb_round      u64 NOT NULL,
  lease_rounds  SMALLINT NOT NULL CHECK (lease_rounds >= 0 AND lease_rounds <= 255),
  board_round   u64,
  applied       BOOLEAN,
  PRIMARY KEY (dataset, signature, ix_idx, entry_idx),
  FOREIGN KEY (dataset, signature) REFERENCES txs (dataset, signature)
);
CREATE INDEX hd_heartbeats_rig ON hd_heartbeats (dataset, rig, slot);

-- The plan of every arm_shift (the lease cap bounds every heartbeat lease of that shift).
CREATE TABLE hd_arm_plans (
  dataset       TEXT NOT NULL,
  signature     tx_signature NOT NULL,
  ix_idx        INTEGER NOT NULL CHECK (ix_idx >= 0),
  slot          BIGINT NOT NULL,
  block_time    BIGINT,
  rig           sol_address NOT NULL,
  mode          SMALLINT NOT NULL CHECK (mode IN (0, 1)),
  max_ev_cost   u64 NOT NULL,
  dig_lamports  u64 NOT NULL,
  split_tiles   SMALLINT NOT NULL,
  solo_tiles    SMALLINT NOT NULL,
  lease_rounds  SMALLINT NOT NULL,
  flags         SMALLINT NOT NULL,
  window_start  BIGINT NOT NULL,
  window_end    BIGINT NOT NULL,
  counter       u64,
  PRIMARY KEY (dataset, signature, ix_idx),
  FOREIGN KEY (dataset, signature) REFERENCES txs (dataset, signature)
);
CREATE INDEX hd_arm_plans_rig ON hd_arm_plans (dataset, rig, slot);

-- ORE Round accounts read after their reset (slot_hash set), for rounds Heads Down touched.
-- `deployed` holds the 25 per-square totals as a comma-separated list of u64 decimals.
CREATE TABLE ore_round_state (
  dataset             TEXT NOT NULL REFERENCES datasets (name),
  round_id            u64 NOT NULL,
  address             sol_address NOT NULL,
  deployed            TEXT NOT NULL CHECK (deployed ~ '^[0-9]{1,20}(,[0-9]{1,20}){24}$'),
  slot_hash           BYTEA NOT NULL,
  expires_at          u64 NOT NULL,
  motherlode          u64 NOT NULL,
  top_miner           sol_address NOT NULL,
  rewards_total       u64 NOT NULL,
  total_vaulted       u64 NOT NULL,
  total_returned_sol  u64 NOT NULL,
  total_miners        u64 NOT NULL,
  context_slot        BIGINT NOT NULL,
  source              TEXT NOT NULL,
  data                BYTEA NOT NULL,
  PRIMARY KEY (dataset, round_id)
);

-- Rounds whose account no longer exists when the resolver asked (closed after expiry): never
-- re-requested; their outcome comes from the ResetEvent.
CREATE TABLE ore_round_missing (
  dataset     TEXT NOT NULL REFERENCES datasets (name),
  round_id    u64 NOT NULL,
  checked_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (dataset, round_id)
);
