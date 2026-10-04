# Heads Down indexer

Indexes Heads Down activity from chain data only, stores it in Postgres, and serves the public,
verifiable traction API behind the [dashboard](../../dashboard).

- **heads_down events**, all 27 tags of INTERFACE v1.3, read from `Program data:` lines that the
  heads_down program itself wrote. The v1.1 core (`RigDug`, `RigSkipped`, `ShiftArmed`,
  `ShiftEnded` / `ShiftEndedV2`, `SeekerVerified`, `RigRegistered`, `RigClosed`,
  `HeartbeatsRecorded`, `ShiftBroken`) has a table each. The SKR events of v1.2 (Stack, Focus Bond,
  Gift a Rig, Bury auction) and the v1.3 events (governance rotation, `ShiftLogClosed`) share one
  table, `ev_ext`, with their decoded fields as JSON.
- **heads_down instructions**, all 32 tags: heartbeat leases come from `dig`,
  `record_heartbeats` and `stack_checkin` entries (a heartbeat verified at a Stack table is
  applied exactly as `record_heartbeats` applies it).
- **ORE `DeployEvent`s whose signer is the Heads Down Executor PDA**
  (`By3vJvQUsCLexnv7VqHuEhtZZCmpmjZjfhxvqCnWPkge` = `[b"executor"]` under
  `HDn4vgLWFLLdexKEwfZwRHjWtizNvdqFteLbMsE67F9p`, bump 249). ORE can recompute these from its own logs.
- **heads_down accounts** (Rig, SeekerSeat, ShiftLog, Config) from `getProgramAccounts`. Each one is
  owner-checked and its address re-derived from its own contents with the canonical bump.
- **ORE rounds** (`ResetEvent`) from api.ore.com. Each row keeps its reset transaction signature,
  and a sample of rows is re-read from chain on every poll.

Because heads_down is not deployed yet, a **deterministic simulation mode** generates realistic
data for development and demos. That data is labelled and cannot mix with real data (see
[Simulation](#simulation-mode)).

## Quick start

```bash
cd services/indexer
pnpm install
pnpm test            # 385 tests: golden bytes, metrics math, cohorts, haul, store, sources, ingest loop, API
pnpm typecheck
pnpm demo            # in-memory Postgres + simulated dataset + API on http://127.0.0.1:8787
curl -s localhost:8787/v1/summary | jq .data.rigs
```

`pnpm demo` needs no Docker: it uses [PGlite](https://pglite.dev), real Postgres compiled to WASM.
It runs the same migration as production.

Against a real Postgres:

```bash
docker compose up -d                        # postgres:17 on 127.0.0.1:54329 (dev credentials)
export DATABASE_URL=postgres://headsdown:headsdown-dev@127.0.0.1:54329/headsdown
pnpm migrate
pnpm simulate -- --seed demo --rigs 120 --nights 28     # fills ONLY the simulated dataset
INDEXER_DATASET=simulated pnpm start

# Real data (once the program is deployed)
INDEXER_DATASET=mainnet RPC_URL=https://... pnpm ingest -- --once   # or `pnpm start` (API + background polling)
```

All settings are listed in [.env.example](.env.example).

## Why TypeScript (and not Rust)

- **The work is I/O and aggregation, not hot compute.** The indexer polls RPC, parses JSON and runs
  metrics over tens of thousands of rows. Node is fast enough: the simulator plus a full ingest of
  10k transactions and 31k rounds takes about 20 s, most of it Postgres inserts.
- **Same language as the dashboard.** The API's JSON shapes, the OpenAPI document and the
  dashboard's types are all TypeScript, and the schema tests run against live responses.
- **Node 26 runs `.ts` directly** (type stripping), so there is no build step and nothing to drift
  from the source. `tsc --noEmit` type-checks in strict mode with `erasableSyntaxOnly`.
- **Few, pinned dependencies:** `pg`, `@electric-sql/pglite`, and `@solana/addresses` (used only for
  the off-curve check in PDA derivation). The HTTP server is `node:http`, and base58 and all byte
  codecs are in-repo and tested.

The on-chain byte formats are decoded independently of the Rust crates, so the golden tests are a
second implementation of the contract.

## Architecture

```
  RPC poller ──────┐  getSignaturesForAddress(program id, Executor PDA) → getTransaction
  (finalized, v1)  │  getProgramAccounts (Rig/SeekerSeat/ShiftLog/Config, tag+size filters),
                   │  only when a new transaction asks for it (see "Ingest loop and RPC use")
                   │
  Helius webhook ──┤  raw webhook = hint → signatures re-fetched from RPC (a leaked secret
                   │  cannot inject digs)
                   │
  Simulator ───────┤  deterministic raw txs/accounts, dataset = simulated
                   ▼
            codec/tx.ts  extractTransaction
              ├─ meta.err != null → no events (failed txs never count)
              ├─ logs.ts: replay the invoke stack; accept `Program data:` only while
              │  heads_down is executing (forged emitters are ignored)
              └─ inner ixs: ORE Log (tag 8) whose only account is the Board
                 → DeployEvent (kept iff signer == Executor PDA) / ResetEvent
                   ▼
            store/store.ts  bound to ONE dataset; idempotent; problems are recorded
                   ▼
            Postgres (migrations/001_init.sql: u64 / address / signature domains)
                   ▼
            metrics/*.ts (pure) → api/server.ts (GET only) → dashboard
```

### Trust and security properties (each has a negative test)

| Property | Where | Test |
|---|---|---|
| Events count only from the heads_down program's own invocations | `codec/logs.ts` | `tx.test.ts` "ignores heads_down-shaped events emitted by another program", "a forged 'invoke' inside a Program log…" |
| Failed transactions contribute nothing | `codec/tx.ts` | "a failed transaction yields no events" |
| ORE events only from a Board-signed ORE `Log`; Heads Down only when signer == Executor PDA | `codec/tx.ts` | "rejects an ORE Log whose account is not the Board", "ignores ORE deploys signed by anyone but the Executor PDA" |
| Account decoders check length, tag and version; the address must be the canonical PDA; the owner must be the program | `codec/accounts.ts` | `accounts.test.ts` (foreign owner, swapped authority, non-canonical bump) |
| No decoder throws anything but `DecodeError` on attacker bytes; lengths are bounded before base58/base64 | `codec/*` | fuzz loops in `events.test.ts`, `codec.test.ts` |
| Simulated and real data never mix | `store/store.ts` binds one dataset; `(dataset, …)` primary keys; `runSimulation` refuses real datasets; the API is bound to one dataset | `store.test.ts` "isolates datasets", `simulate.test.ts` "refuses to write into a real dataset" |
| A mainnet dataset cannot be fed from a devnet RPC | `loop.ts` genesis-hash check: before the first poll, and before the webhook stores anything | `loop.test.ts` "never ingests from an RPC on another cluster", `api.test.ts` "webhook stores nothing until the RPC's cluster is verified", `serve.test.ts` "stores nothing, by the poller or by the webhook, until the RPC shows the right cluster" (the real command) |
| Only `finalized` commitment, so a fork cannot leave phantom digs | `sources/rpc.ts` | fake-RPC tests |
| api.ore.com is spot-checked against the chain, and the chain wins | `sources/oreApi.ts` | "chain wins when api.ore.com disagrees" |
| Webhook: constant-time secret check, 5 MB cap, payload re-fetched from RPC | `sources/helius.ts`, `api/server.ts` | `sources.test.ts`, `api.test.ts` |
| Secrets (RPC key, DB password) never logged or returned | `config.ts` `describeConfig`, `RpcError`; a provider's own error text is scrubbed of the URL, of any `api-key=` value and of the URL's keys (`sources/rpc.ts` `scrubRpcText`) | "never leaks the URL's API key", "scrubs a provider's error text…", "scrubs a key that sits in the URL's path" |
| CSV formula injection is neutralised | `api/csv.ts` | `api.test.ts` |
| u64 stays exact end to end (BigInt → NUMERIC(20,0) → decimal strings) | everywhere | `store.test.ts` domain test |

### Ingest loop and RPC use

`serve` and `ingest` run one pass every `INGEST_INTERVAL_S` (`src/loop.ts`):

1. **Cluster check.** With `RPC_URL` set and a `mainnet` or `devnet` dataset, the RPC's genesis hash
   must match the dataset's cluster before anything is ingested, by the poller or by the webhook
   (`localnet` has no pinned hash and is not checked). A check that fails (wrong cluster, RPC down,
   credits used up) is logged as `ingest error` and tried again at the next interval. Once it has
   passed it is not repeated.
2. **Signatures.** `getSignaturesForAddress` for the program id and for the Executor PDA (two
   calls), then one `getTransaction` per new signature whose transaction succeeded.
3. **Account snapshot.** Four `getProgramAccounts` scans. Taken on the first poll after start, on
   every poll that finds a new transaction that succeeded, and otherwise every
   `SNAPSHOT_EVERY_N_POLLS` polls (default 20; 1 = every poll).
4. **ORE rounds.** The resolver reads the Round account of each round a rig dug in (and its reset
   transaction where no ResetEvent has arrived), and calls nothing while no round is waiting.
   api.ore.com needs no RPC; up to `ORE_API_VERIFY_SAMPLE` of its new rounds per pass are re-read
   from chain with `getTransaction`.

A pass that fails, at any step, is logged and tried again at the next interval. It does not end the
process: the API keeps serving what is stored (`serve.test.ts` runs the real command against an RPC
that refuses connections). `ingest --once` still exits with a non-zero code when its pass fails
(`serve.test.ts` runs that command too). With `RPC_URL` unset there is no chain ingestion and no RPC
call; ORE rounds still come from api.ore.com.

**Why a new signature is enough to ask for the snapshot.** The scans read accounts owned by the
heads_down program. Only the owning program can change an account's data, give a new account its
tag, or close it, and a transaction that runs the program names the program id among its accounts,
so it is in the program id's signature list. SOL sent to an account from outside changes no data,
and a failed transaction changes no account data. Three things are outside that reasoning, and the
periodic snapshot is what bounds them:

- an RPC whose signature list misses a transaction (its events are then missing as well; only the
  accounts are repaired);
- a second process on the same database (`ingest` next to `serve`) that moved the cursor and
  stopped before its own snapshot;
- accounts written straight into a local validator (`--account` files, cheat codes).

A new signature is noted before anything is fetched, and only a snapshot that succeeded clears the
note, so a poll that fails half way still owes the snapshot. When the second address cannot be
read, or the scans fail, the next poll takes it although it sees no new signature; a transaction
the RPC does not serve yet is asked for again and the snapshot follows it (`sources.test.ts`,
"stays owed …"). The note lives in memory: a restart starts with a snapshot.

A scan answered from a slot older than the newest signature (a lagging node behind a load
balancer) is noticed from its context slot and repeated on the next poll. An Agave node lists a
transaction under every address it names, lookup-table addresses included
(`rpc/src/transaction_status_service.rs`, read at v4.1.2). Two checks on real nodes, 2026-10-04:

- The public mainnet RPC, one block: 4 of 4 addresses that a transaction loaded from a lookup table,
  and 3 of 3 programs reached only by CPI through such an address, listed that transaction in
  `getSignaturesForAddress`.
- A local validator (solana-test-validator 4.1.2) with the indexer polling every 2 s: an idle poll
  made the two signature calls and nothing else; a transfer to the program id's address was found
  once finalized, fetched, and followed by one snapshot in the same poll, answered from a slot past
  the transaction's.

Helius' own signature index was not tested here.

**Cost at Helius' prices** (`getProgramAccounts` 10 credits, every other call 1; their billing
page, read 2026-10-04). Computed from the code, not measured against Helius:

| | calls | credits |
|---|---|---|
| pass with nothing new | 2 `getSignaturesForAddress` | 2 |
| ORE spot-check | up to `ORE_API_VERIFY_SAMPLE` `getTransaction` per pass with new ORE rounds | up to 3 |
| round resolver | the ORE Board and the Round accounts, only while a dug round waits for its outcome | 2 or more |
| snapshot | 4 `getProgramAccounts` | 40 |
| before `SNAPSHOT_EVERY_N_POLLS` existed | every pass: the two signature calls, the snapshot, the ORE Board | 43 |

With no rig active that is about 12,600 credits a day at `INGEST_INTERVAL_S=30` and about 2,000 at
300: the signature calls, a snapshot every 20th poll, and the ORE spot-check (about 1,100 rounds a
day at 78 s a round; at 300 s the cap of 3 per pass makes it 864). Before, the same two cases
came to about 125,000 and 13,200.

While a rig digs, the poll that finds a round's dig also takes the snapshot. At a 30 s interval
each dug round adds about 45 credits (the snapshot, the dig transaction, the Board and the Round
account, the reset lookup): about 17,000 for an 8-hour night at 78 s a round. At 300 s every poll
finds new digs and takes one snapshot: about 5,000 for the same night. Both are computed from the
code, not measured.

The call counts themselves were measured on 2026-10-04 through a counting proxy in front of the
public mainnet RPC (program not deployed yet, api.ore.com off, 5 s interval, 63 s): 10 passes and
the start of an 11th made 21 `getSignaturesForAddress` calls, the 4 scans of the first poll and
one `getGenesisHash`. The code before made 18 `getSignaturesForAddress`, 36 `getProgramAccounts`
and 9 `getMultipleAccounts` calls in 9 passes.

**Postgres.** A lost connection (Postgres restarts) is logged as `pg pool error` or
`pg client error` and the query that hit it fails; the process stays up and the pool connects again
for the next query. Only the error's message is logged: the error object node-postgres hands over
carries the client, and with it the database password. `db.test.ts` checks this with the real `pg`
driver against a stand-in server that speaks the wire protocol and drops its connections the way a
shutdown does.

The node-postgres path as a whole was run by hand on 2026-10-04 against PGlite behind the Postgres
wire protocol (`@electric-sql/pglite-socket` 0.2.11, which is not a dependency of this package).
The migrations, the simulator (4,702 transactions), every read route, and a real dataset fed by a
stand-in RPC gave the same API answers as the embedded engine, apart from the times of the run. A
`serve` whose database was stopped and started again twice stayed up, answered `/v1/health` with
500 while it was away, and went on ingesting when it was back. That is the engine the tests use,
reached through the driver. A Postgres server, with its authentication, its own concurrency and
Railway's version, has still not been used: none is available where the suite runs, so the first
deployment is the first run against one.

## API

The spec is at `GET /openapi.json` (OpenAPI 3.1), and the tests validate every JSON response
against it. Every response has the envelope `{ dataset: {name, simulated, programId, executorPda,
simSeed}, asOf, generatedAt, data }` and the header `X-HeadsDown-Dataset`. u64 values are decimal
strings.

| Route | What |
|---|---|
| `GET /v1/health` | tx counts, failed/truncated txs, decode problems by code, and when the last ingest pass finished and whether it succeeded (`lastPollAt`, `lastPollOk`, `lastOkPollAt`) |
| `GET /v1/summary?tz=` | headline tiles, each with `evidence[]` (`{label, kind, id, url}`) |
| `GET /v1/cohorts?tz=` | D1/D7/D14 retention by first-shift night |
| `GET /v1/share-by-hour?tz=&days=` | Heads Down share of unique ORE miners per round, by hour, each with its peak round and its reset and dig signatures |
| `GET /v1/digs/recent?limit=` | latest digs (RigDug paired with its DeployEvent), with explorer links |
| `GET /v1/milestones?tz=` | ORE milestone targets (docs/ORE.md §9) against measured values |
| `GET /v1/skr/summary` | Stack, Focus Bond, Gift a Rig and Bury auction totals: counts and sums of the program's own events, in base units |
| `GET /v1/export/rounds.csv?days=` | per ORE round: total miners, Heads Down miners, share, lamports, reset and dig signatures |
| `GET /v1/export/digs.csv?days=` | every dig with its signature |
| `GET /v1/export/monthly.csv?tz=` | monthly milestone report |

**Watching ingestion.** `/v1/health` answers 200 with `status: "ok"` whenever the database answers,
so it shows that the API is up, not that data is arriving. For that, read the poll fields (times
are unix seconds, like the envelope's `asOf`):

- `lastPollAt` and `lastPollOk`: when the last ingest pass finished, and whether it succeeded. A
  pass covers the sources that are configured (the RPC poll only when `RPC_URL` is set).
  `asOf - lastPollAt` growing past a few intervals means the loop has stopped or a pass is stuck;
  `lastPollOk: false` means passes run and fail (RPC down, credits used up, wrong cluster). The
  reason is in the log line `ingest error`, not in the response.
- `lastOkPollAt`: when the last successful pass finished.
- All three are null until a pass has finished for the dataset, and always for `simulated`. They
  are stored with the cursors, so they survive a restart and a separate `ingest` process fills them too.
- What they do not show: a pass that stops early because the RPC lists a transaction it does not
  serve yet counts as succeeded (log line `rpc: transaction not yet available, will retry`). If the
  RPC never served it, `lastPollOk` would stay true while nothing new was stored.

`lastSlot` and `lastBlockTime` belong to the newest stored transaction. They are null before the
first one and stand still whenever nothing lands on chain, so they say nothing about a stalled
ingest.

## Metric definitions

All metrics come from on-chain rows. **Night** = the calendar date on which a night starts, in one
fixed offset (`NIGHT_TZ_OFFSET_MINUTES`, default WAT +01:00), with the boundary at local noon. A
rig's own time zone is not on chain (by design, docs/PRIVACY.md).

| Metric | Definition | How to verify |
|---|---|---|
| Rigs, Seeker-verified, guest | open Rig accounts, with `tier` 1 or 0. Without an account snapshot, distinct rigs in events. | `getProgramAccounts` with size 384, tag 2 |
| Nightly active rigs | distinct rigs with a ShiftArmed, a RigDug, or a ShiftEnded with `dark_rounds > 0` that night | sample dig txs from last night |
| D1/D7/D14 retention | cohort = night of the rig's first ShiftArmed; Dk = share of the cohort active on night cohort+k exactly; cells are null until night cohort+k is complete | ShiftArmed txs |
| Dark hours | Σ `ShiftEnded.dark_rounds` × median ORE round length (from consecutive ResetEvent timestamps; 78 s fallback, labelled) | ShiftEnded txs |
| Rounds dug | RigDug count (rig-rounds) and distinct ORE rounds | the Executor PDA's transaction history |
| SOL deployed | Σ `RigDug.lamports`, cross-checked against Σ `DeployEvent.amount × total_squares` (`consistent` flag) | ORE's own DeployEvents |
| ORE mined | per Heads Down DeployEvent, ORE's `checkpoint.rs` rule applied to that round's ResetEvent: split → `min(total_minted, 1 ORE) × amount / deployed_winning_square`; solo → all of it iff `top_miner == authority`; plus Motherlode pro rata. Unrefined, before the 10% refining fee. | the reset tx of the round |
| ORE bought / buried | **placeholder `null`, status `not_shipped`**: the clock-out buy leg and the Bury auction are not built yet. Shown as "not shipped", never as 0. | n/a |
| Share of ORE miners | per round: distinct Heads Down authorities with `total_squares > 0` / `ResetEvent.total_miners`; averaged per local hour | rounds.csv has both signatures per round |
| Gate-open rate | `RigDug / (RigDug + RigSkipped{CostGate})`, biased high because the crank pre-filters; also Σ `rounds_dug` / Σ `dark_rounds` (INTERFACE-NOTES N5) | sample RigDug and RigSkipped txs |
| Crankers | distinct fee payers of dig txs; third-party = not in `TEAM_CRANKERS` (milestone M3) | dig txs |
| Consistency | digs without a DeployEvent, the reverse, lamport mismatches, rounds_dug > dark_rounds, Seeker tier without a SeekerSeat, Heads Down miners > total miners. All should be 0 and are shown publicly. | |

**Rigs by region** is not computed. The chain has no location data and no opt-in region data
exists, so the dashboard leaves that view out.

## Simulation mode

`pnpm simulate -- --seed <s> --rigs <n> --nights <n> --start YYYY-MM-DD` (defaults:
`heads-down-demo-v1`, 120 rigs, 28 nights, 2026-09-10).

- **Deterministic.** Seeded sfc32 over SHA-256, pure 32-bit math: the same seed gives byte-identical
  output (tested). `asOf` is frozen at generation time, so the dashboard renders the same every time.
- **Same code path as real data.** The simulator emits raw `getTransaction` JSON (Program data
  lines, Board-signed ORE Log inner instructions, v0 envelopes) and raw account bytes at real PDAs.
  They go through `extractTransaction`, owner and PDA verification, and the store. The demo ingest
  records **zero** decode problems and zero consistency violations (tested).
- **Isolated and labelled.** It writes only the `simulated` dataset, which is replaced wholesale on
  each run and carries its seed. Every API response says `simulated: true`; CSV files are named
  `SIMULATED-…` and every row starts with `simulated`. No explorer links are produced, because the
  random addresses and signatures exist on no chain.
- **Model.** Rigs join over time (27% Seeker tier), mostly in WAT with EAT, PHT, KST, BRT, IST and UTC
  minorities. Each arms a shift at about 22:30 local for about 8 h. Habit decays toward a per-rig
  plateau, 15% of rigs churn, and 8% of shifts end in a pickup. ORE rounds are about 78 s, with a
  Motherlode pot (1/500 hit rate) and a production-cost EMA whose crowding follows the pot
  (ml/forecaster/RESULTS.md §2). Digs are 0.001 SOL on the 15 split tiles, only when the
  Motherlode-aware gate (INTERFACE "Gate", same integer formula) opens, until the shift budget is spent.

## Tests

```
pnpm test        # vitest; PGlite gives every store/API test a real Postgres
pnpm typecheck   # tsc --noEmit, strict
```

- `codec.test.ts`: LE reader bounds, base58 fuzz-equivalence with `@solana/codecs-strings`, and PDAs
  matching `solana find-program-derived-address`.
- `events.test.ts`: golden bytes for all 5 events (independent Python oracle), plus negative and fuzz cases.
- `ore.test.ts`: real mainnet DeployEvent and ResetEvent bytes, cross-checked with api.ore.com.
- `tx.test.ts`: log attribution attacks, failed txs, forged ORE logs, lookup-table keys, real fixtures.
- `accounts.test.ts`: Rig, ShiftLog and SeekerSeat oracle bytes; owner and canonical-PDA checks.
- `store.test.ts`: idempotent ingest, dataset isolation, DB-level domains, closed-account tracking.
- `metrics.test.ts`: night boundaries, cohort maths, ORE-mined formula, share by hour, pairing, summary.
- `sources.test.ts`: RPC retry and secret redaction (a provider's error text included), polling cursors, when the account
  snapshot is taken and when it is skipped, that it stays owed after a poll that failed half way, api.ore.com parsing and
  chain override, webhook.
- `loop.test.ts`: the ingest loop against a fake RPC: the genesis-hash check is retried, nothing is ingested before it
  passes, a wrong cluster never ingests, a failed pass does not end the loop, `--once` still fails.
- `serve.test.ts`: the real commands as processes. `serve` with an RPC that refuses connections stays up and `/v1/health`
  reports the failed pass; `serve` with an RPC on another cluster stores nothing, by the poller or by the webhook, until the
  RPC shows the right one; `ingest --once` exits with code 1 when its pass fails and 0 when it succeeds.
- `db.test.ts`: a lost Postgres connection is logged and does not end the process (the real `pg` driver against a
  stand-in server; no Postgres involved).
- `simulate.test.ts`: determinism, and a full ingest with zero problems and consistent metrics.
- `api.test.ts`: OpenAPI completeness, schema validation of live responses, parameter validation, CSV, links, the poll
  fields of `/v1/health` and that its schema names every field it returns.
- `config.test.ts`: settings, and that both `.env.example` files list every variable the code reads.

## Known limits

- Metrics are recomputed in memory from the dataset's rows, with a 30 s cache. This is fine for
  hackathon scale (tens of thousands of digs). Past that, move the aggregates into materialized views.
- The webhook path re-fetches at `finalized`, so a transaction the webhook announces before
  finalization is left for the RPC poller, which backstops completeness.
- Between new transactions the account snapshot is refreshed every `SNAPSHOT_EVERY_N_POLLS` polls:
  with the default of 20 that is 10 minutes at `INGEST_INTERVAL_S=30` and 100 minutes at 300. That
  is how old the accounts can get in the three cases listed under "Ingest loop and RPC use".
- The contract gaps and the choices made for them are in [INTERFACE-NOTES.md](INTERFACE-NOTES.md).
