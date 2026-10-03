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
pnpm test            # 347 tests: golden bytes, metrics math, cohorts, haul, store, sources, API
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
  (finalized, v1)  │  getProgramAccounts (Rig/SeekerSeat/ShiftLog/Config, tag+size filters)
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
| A mainnet dataset cannot be fed from a devnet RPC | `main.ts` genesis-hash check | (startup check) |
| Only `finalized` commitment, so a fork cannot leave phantom digs | `sources/rpc.ts` | fake-RPC tests |
| api.ore.com is spot-checked against the chain, and the chain wins | `sources/oreApi.ts` | "chain wins when api.ore.com disagrees" |
| Webhook: constant-time secret check, 5 MB cap, payload re-fetched from RPC | `sources/helius.ts`, `api/server.ts` | `sources.test.ts`, `api.test.ts` |
| Secrets (RPC key, DB password) never logged or returned | `config.ts` `describeConfig`, `RpcError` | "never leaks the URL's API key" |
| CSV formula injection is neutralised | `api/csv.ts` | `api.test.ts` |
| u64 stays exact end to end (BigInt → NUMERIC(20,0) → decimal strings) | everywhere | `store.test.ts` domain test |

## API

The spec is at `GET /openapi.json` (OpenAPI 3.1), and the tests validate every JSON response
against it. Every response has the envelope `{ dataset: {name, simulated, programId, executorPda,
simSeed}, asOf, generatedAt, data }` and the header `X-HeadsDown-Dataset`. u64 values are decimal
strings.

| Route | What |
|---|---|
| `GET /v1/health` | tx counts, failed/truncated txs, decode problems by code |
| `GET /v1/summary?tz=` | headline tiles, each with `evidence[]` (`{label, kind, id, url}`) |
| `GET /v1/cohorts?tz=` | D1/D7/D14 retention by first-shift night |
| `GET /v1/share-by-hour?tz=&days=` | Heads Down share of unique ORE miners per round, by hour, each with its peak round and its reset and dig signatures |
| `GET /v1/digs/recent?limit=` | latest digs (RigDug paired with its DeployEvent), with explorer links |
| `GET /v1/milestones?tz=` | ORE milestone targets (docs/ORE.md §9) against measured values |
| `GET /v1/skr/summary` | Stack, Focus Bond, Gift a Rig and Bury auction totals: counts and sums of the program's own events, in base units |
| `GET /v1/export/rounds.csv?days=` | per ORE round: total miners, Heads Down miners, share, lamports, reset and dig signatures |
| `GET /v1/export/digs.csv?days=` | every dig with its signature |
| `GET /v1/export/monthly.csv?tz=` | monthly milestone report |

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
- `sources.test.ts`: RPC retry and secret redaction, polling cursors, api.ore.com parsing and chain override, webhook.
- `simulate.test.ts`: determinism, and a full ingest with zero problems and consistent metrics.
- `api.test.ts`: OpenAPI completeness, schema validation of live responses, parameter validation, CSV, links.

## Known limits

- Metrics are recomputed in memory from the dataset's rows, with a 30 s cache. This is fine for
  hackathon scale (tens of thousands of digs). Past that, move the aggregates into materialized views.
- The webhook path re-fetches at `finalized`, so a transaction the webhook announces before
  finalization is left for the RPC poller, which backstops completeness.
- The contract gaps and the choices made for them are in [INTERFACE-NOTES.md](INTERFACE-NOTES.md).
