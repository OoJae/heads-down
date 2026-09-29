-- Heads Down indexer schema, v1.
--
-- Every row carries a `dataset` ('mainnet' | 'devnet' | 'localnet' | 'simulated') that is part
-- of its primary key and references `datasets`. The API process is bound to exactly one
-- dataset and every query filters on it, so simulated rows can never be mixed into real
-- metrics (and vice versa). u64 values are NUMERIC(20,0) with range checks: BIGINT is signed
-- and cannot hold u64::MAX (ORE's "manual" strategy marker).

CREATE DOMAIN u64 AS NUMERIC(20, 0)
  CHECK (VALUE >= 0 AND VALUE <= 18446744073709551615);

CREATE DOMAIN sol_address AS TEXT
  CHECK (VALUE ~ '^[1-9A-HJ-NP-Za-km-z]{32,44}$');

CREATE DOMAIN tx_signature AS TEXT
  CHECK (VALUE ~ '^[1-9A-HJ-NP-Za-km-z]{64,88}$');

CREATE TABLE datasets (
  name          TEXT PRIMARY KEY CHECK (name IN ('mainnet', 'devnet', 'localnet', 'simulated')),
  simulated     BOOLEAN GENERATED ALWAYS AS (name = 'simulated') STORED,
  program_id    sol_address NOT NULL,
  executor_pda  sol_address NOT NULL,
  -- Simulation provenance (NULL for real clusters).
  sim_seed      TEXT,
  sim_as_of     BIGINT,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  CHECK ((name = 'simulated') = (sim_seed IS NOT NULL))
);

-- One row per transaction the indexer has processed (including failed ones, which carry no
-- events). Event rows reference it.
CREATE TABLE txs (
  dataset         TEXT NOT NULL REFERENCES datasets (name),
  signature       tx_signature NOT NULL,
  slot            BIGINT NOT NULL CHECK (slot >= 0),
  block_time      BIGINT,
  -- accountKeys[0]: who paid for (and, for digs, cranked) the transaction.
  fee_payer       sol_address NOT NULL,
  failed          BOOLEAN NOT NULL,
  logs_truncated  BOOLEAN NOT NULL DEFAULT FALSE,
  source          TEXT NOT NULL,
  ingested_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (dataset, signature)
);
CREATE INDEX txs_slot ON txs (dataset, slot);

-- heads_down events (INTERFACE.md "Events"). `idx` is the event's ordinal among the
-- heads_down `Program data:` lines of the transaction; `raw` keeps the exact bytes.
CREATE TABLE ev_rig_dug (
  dataset     TEXT NOT NULL,
  signature   tx_signature NOT NULL,
  idx         INTEGER NOT NULL CHECK (idx >= 0),
  slot        BIGINT NOT NULL,
  block_time  BIGINT,
  rig         sol_address NOT NULL,
  round_id    u64 NOT NULL,
  lamports    u64 NOT NULL,
  mask        INTEGER NOT NULL CHECK (mask >= 0 AND mask < 33554432),
  ema_ev      u64 NOT NULL,
  raw         BYTEA NOT NULL,
  PRIMARY KEY (dataset, signature, idx),
  FOREIGN KEY (dataset, signature) REFERENCES txs (dataset, signature)
);
CREATE INDEX ev_rig_dug_time ON ev_rig_dug (dataset, block_time);
CREATE INDEX ev_rig_dug_rig ON ev_rig_dug (dataset, rig);
CREATE INDEX ev_rig_dug_round ON ev_rig_dug (dataset, round_id);

CREATE TABLE ev_rig_skipped (
  dataset     TEXT NOT NULL,
  signature   tx_signature NOT NULL,
  idx         INTEGER NOT NULL CHECK (idx >= 0),
  slot        BIGINT NOT NULL,
  block_time  BIGINT,
  rig         sol_address NOT NULL,
  round_id    u64 NOT NULL,
  error_code  BIGINT NOT NULL CHECK (error_code >= 0 AND error_code <= 4294967295),
  raw         BYTEA NOT NULL,
  PRIMARY KEY (dataset, signature, idx),
  FOREIGN KEY (dataset, signature) REFERENCES txs (dataset, signature)
);
CREATE INDEX ev_rig_skipped_time ON ev_rig_skipped (dataset, block_time);

CREATE TABLE ev_shift_armed (
  dataset     TEXT NOT NULL,
  signature   tx_signature NOT NULL,
  idx         INTEGER NOT NULL CHECK (idx >= 0),
  slot        BIGINT NOT NULL,
  block_time  BIGINT,
  rig         sol_address NOT NULL,
  shift_id    u64 NOT NULL,
  raw         BYTEA NOT NULL,
  PRIMARY KEY (dataset, signature, idx),
  FOREIGN KEY (dataset, signature) REFERENCES txs (dataset, signature)
);
CREATE INDEX ev_shift_armed_rig ON ev_shift_armed (dataset, rig, block_time);

CREATE TABLE ev_shift_ended (
  dataset      TEXT NOT NULL,
  signature    tx_signature NOT NULL,
  idx          INTEGER NOT NULL CHECK (idx >= 0),
  slot         BIGINT NOT NULL,
  block_time   BIGINT,
  rig          sol_address NOT NULL,
  shift_id     u64 NOT NULL,
  dark_rounds  u64 NOT NULL,
  rounds_dug   u64 NOT NULL,
  lamports     u64 NOT NULL,
  reason       SMALLINT NOT NULL CHECK (reason >= 0 AND reason <= 255),
  raw          BYTEA NOT NULL,
  PRIMARY KEY (dataset, signature, idx),
  FOREIGN KEY (dataset, signature) REFERENCES txs (dataset, signature)
);
CREATE INDEX ev_shift_ended_time ON ev_shift_ended (dataset, block_time);

CREATE TABLE ev_seeker_verified (
  dataset        TEXT NOT NULL,
  signature      tx_signature NOT NULL,
  idx            INTEGER NOT NULL CHECK (idx >= 0),
  slot           BIGINT NOT NULL,
  block_time     BIGINT,
  rig            sol_address NOT NULL,
  sgt_mint       sol_address NOT NULL,
  member_number  u64 NOT NULL,
  raw            BYTEA NOT NULL,
  PRIMARY KEY (dataset, signature, idx),
  FOREIGN KEY (dataset, signature) REFERENCES txs (dataset, signature)
);

-- ORE DeployEvents whose signer is the Heads Down Executor PDA. `idx` is the ordinal of the
-- ORE Log instruction within the transaction.
CREATE TABLE ore_deploys (
  dataset        TEXT NOT NULL,
  signature      tx_signature NOT NULL,
  idx            INTEGER NOT NULL CHECK (idx >= 0),
  slot           BIGINT NOT NULL,
  block_time     BIGINT,
  authority      sol_address NOT NULL,
  signer         sol_address NOT NULL,
  amount         u64 NOT NULL,
  mask           INTEGER NOT NULL CHECK (mask >= 0 AND mask < 33554432),
  round_id       u64 NOT NULL,
  strategy       u64 NOT NULL,
  total_squares  SMALLINT NOT NULL CHECK (total_squares >= 0 AND total_squares <= 25),
  ts             BIGINT NOT NULL,
  raw            BYTEA NOT NULL,
  PRIMARY KEY (dataset, signature, idx),
  FOREIGN KEY (dataset, signature) REFERENCES txs (dataset, signature)
);
CREATE INDEX ore_deploys_round ON ore_deploys (dataset, round_id);
CREATE INDEX ore_deploys_time ON ore_deploys (dataset, ts);

-- ORE rounds (ResetEvent), from api.ore.com (`reset_signature` = the reset tx, verifiable) or
-- decoded from chain.
CREATE TABLE ore_rounds (
  dataset                  TEXT NOT NULL REFERENCES datasets (name),
  round_id                 u64 NOT NULL,
  ts                       BIGINT NOT NULL,
  start_slot               u64 NOT NULL,
  end_slot                 u64 NOT NULL,
  winning_square           SMALLINT CHECK (winning_square IS NULL OR (winning_square >= 0 AND winning_square <= 24)),
  top_miner                sol_address NOT NULL,
  total_miners             u64 NOT NULL,
  motherlode               u64 NOT NULL,
  total_deployed           u64 NOT NULL,
  total_vaulted            u64 NOT NULL,
  total_winnings           u64 NOT NULL,
  total_minted             u64 NOT NULL,
  rng                      u64 NOT NULL,
  deployed_winning_square  u64 NOT NULL,
  reset_signature          tx_signature,
  source                   TEXT NOT NULL,
  PRIMARY KEY (dataset, round_id)
);
CREATE INDEX ore_rounds_ts ON ore_rounds (dataset, ts);

-- Latest decoded snapshots of heads_down accounts (getProgramAccounts). `data` keeps the raw
-- account bytes so anyone can re-decode; `context_slot` is the RPC context slot.
CREATE TABLE acc_rigs (
  dataset                     TEXT NOT NULL REFERENCES datasets (name),
  address                     sol_address NOT NULL,
  authority                   sol_address NOT NULL,
  tier                        SMALLINT NOT NULL CHECK (tier IN (0, 1)),
  state                       SMALLINT NOT NULL,
  attestation_level           SMALLINT NOT NULL,
  sgt_mint                    sol_address,
  shift_id                    u64 NOT NULL,
  lifetime_dark_rounds        u64 NOT NULL,
  lifetime_rounds_dug         u64 NOT NULL,
  lifetime_lamports_deployed  u64 NOT NULL,
  streak                      BIGINT NOT NULL,
  -- TRUE once a full getProgramAccounts scan no longer returns the account (close_rig).
  closed                      BOOLEAN NOT NULL DEFAULT FALSE,
  context_slot                BIGINT NOT NULL,
  data                        BYTEA NOT NULL,
  updated_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (dataset, address)
);

CREATE TABLE acc_shift_logs (
  dataset            TEXT NOT NULL REFERENCES datasets (name),
  address            sol_address NOT NULL,
  rig                sol_address NOT NULL,
  shift_id           u64 NOT NULL,
  start_round        u64 NOT NULL,
  end_round          u64 NOT NULL,
  dark_rounds        u64 NOT NULL,
  rounds_dug         u64 NOT NULL,
  lamports_deployed  u64 NOT NULL,
  break_reason       SMALLINT NOT NULL,
  mode               SMALLINT NOT NULL,
  start_ts           BIGINT NOT NULL,
  end_ts             BIGINT NOT NULL,
  closed             BOOLEAN NOT NULL DEFAULT FALSE,
  context_slot       BIGINT NOT NULL,
  data               BYTEA NOT NULL,
  PRIMARY KEY (dataset, address)
);

CREATE TABLE acc_seeker_seats (
  dataset        TEXT NOT NULL REFERENCES datasets (name),
  address        sol_address NOT NULL,
  sgt_mint       sol_address NOT NULL,
  rig            sol_address NOT NULL,
  authority      sol_address NOT NULL,
  member_number  u64 NOT NULL,
  verified_slot  u64 NOT NULL,
  closed         BOOLEAN NOT NULL DEFAULT FALSE,
  context_slot   BIGINT NOT NULL,
  data           BYTEA NOT NULL,
  PRIMARY KEY (dataset, address)
);

CREATE TABLE acc_config (
  dataset       TEXT PRIMARY KEY REFERENCES datasets (name),
  address       sol_address NOT NULL,
  paused        BOOLEAN NOT NULL,
  crank_fee     u64 NOT NULL,
  executor_fee  u64 NOT NULL,
  bury_bps      INTEGER NOT NULL,
  context_slot  BIGINT NOT NULL,
  data          BYTEA NOT NULL
);

-- Resumable polling state (e.g. newest signature seen per address).
CREATE TABLE ingest_cursors (
  dataset     TEXT NOT NULL REFERENCES datasets (name),
  source      TEXT NOT NULL,
  key         TEXT NOT NULL,
  value       TEXT NOT NULL,
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (dataset, source, key)
);

-- Decode failures and anomalies are kept, not swallowed, and surfaced by /v1/health.
CREATE TABLE ingest_problems (
  dataset    TEXT NOT NULL REFERENCES datasets (name),
  subject    TEXT NOT NULL,
  location   TEXT NOT NULL,
  code       TEXT NOT NULL,
  message    TEXT NOT NULL,
  seen_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (dataset, subject, location, code)
);
