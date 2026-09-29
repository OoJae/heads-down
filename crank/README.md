# hd-crank

The permissionless dig crank for Heads Down. Phones heartbeat to it every ORE round; late in each round it
submits batched `heads_down::dig` transactions that carry those heartbeats to the secp256r1 precompile, and
the program deploys each rig's own ORE Automation through the Executor PDA.

**The crank has liveness only.** It chooses *whether* and *when* to submit a heartbeat. The program chooses
the amount, the squares, the caps and the cost gate, and verifies every heartbeat on-chain. A malicious crank
can make rigs miss rounds; it cannot move a lamport anywhere ORE and the program would not (see
[Threat model](#threat-model)). Anyone can run one, and the fixed per-dig reimbursement pays for it.

- Built strictly against [`programs/heads-down/INTERFACE.md`](../programs/heads-down/INTERFACE.md); every gap
  in that contract and every choice made is in [`INTERFACE-NOTES.md`](INTERFACE-NOTES.md).
- Tested against the **live mainnet ORE binary** (LiteSVM fork suite, and a `solana-test-validator`
  end-to-end run of the real process wiring), and read-checked against mainnet itself (`hd-crank check`).

## Contents

- [Run it](#run-it)
- [How it works](#how-it-works)
- [Heartbeat intake protocol](#heartbeat-intake-protocol)
- [Transactions, packing and measured sizes](#transactions-packing-and-measured-sizes)
- [Operating costs per rig per dig](#operating-costs-per-rig-per-dig)
- [Threat model](#threat-model)
- [Observability](#observability)
- [Tests](#tests)
- [Features and stubs](#features-and-stubs)
- [Limits and open issues](#limits-and-open-issues)

## Run it

Toolchain: `rust-toolchain.toml` pins Rust 1.97.1 (litesvm 0.17, a dev-dependency, links Agave 4.3 crates
that require it). Agave CLI 4.1 lives in `~/.local/share/solana/install/active_release/bin`.

```sh
cd crank
cargo build --release

# Fee payer: a dedicated, small hot key. Never commit it (crank/.gitignore covers *keypair*.json, id.json).
solana-keygen new -o ~/.config/hd-crank/id.json && chmod 600 ~/.config/hd-crank/id.json

# Config: every field is optional. crank.toml is git-ignored.
cp crank.example.toml crank.toml
export HELIUS_API_KEY=...          # env only; fills {HELIUS_API_KEY} in rpc_url / ws_url

# Read-only: chain view, ORE pins, gate value, heads_down Config and a dry-run plan.
./target/release/hd-crank --config crank.toml check

# Watcher + intake (/ws, /healthz, /metrics on `listen`) + dig loop.
./target/release/hd-crank --config crank.toml --keypair ~/.config/hd-crank/id.json run
```

`hd-crank check` against mainnet on 2026-09-29 (read-only, public RPC):

```text
slot           451741378
board          round 422724 slots [451741342, 451741582) ema 952436545 lamports/ORE
motherlode     368.6 ORE
ema_ev         Some(657911497) lamports/ORE (gate value)
ore upgrade    slot Some(450496378) (pinned 450496378)
breaker        closed (all ORE pins match)
heads_down     Config not found at inzDn4ogmXbx9YDAKDHkfwJHy1jhsaWxGQvricDAEmW (program not initialized on this cluster)
```

Environment overrides: `HD_CRANK_RPC_URL`, `HD_CRANK_WS_URL`, `HD_CRANK_KEYPAIR`, `HD_CRANK_LISTEN`,
`HD_CRANK_TX_FORMAT` (`legacy|v0|v1`), `HD_CRANK_CONFIG`, `RUST_LOG`. A config file that embeds a literal
`api-key=` value is refused. The keypair file must be mode 600.

## How it works

```text
 phones ──WS /ws──► intake ─ rate limits ─► verifier (P-256 vs Rig.p256_pubkey) ─► latest heartbeat per rig
                                                        ▲ Rig cache (getAccountInfo, negative cache)
 ORE ──WS accountSubscribe + HTTP poll──► watcher (Board, Treasury, ORE Config, current Round; pins → breaker)
                                              │
                              slots_left ≤ deploy_margin (default 20 slots before end_slot)
                                              ▼
 getProgramAccounts(Rig, state ∈ {Armed, Down}) → keep rigs with a covering lease or a held heartbeat
 → getMultipleAccounts(Automations, Miners, Executor) → planner (every check `dig` makes, off-chain)
 → pack (exact wire size) → simulate (size CU, bisect a failing batch) → sign → send
 → ledger[(rig, round)] = pending → confirm task: rebroadcast same bytes, poll statuses
 → getTransaction → RigDug / RigSkipped events → ledger, metrics, drop consumed heartbeats
 new round → prune, lookup-table sync, checkpoint sweep of idle miners
```

| Module | What it does |
|---|---|
| `ore` | ORE layouts at the pinned commit; owner + exact size + discriminator checked before any read; Level-1 sanity pins; PDAs; `checkpoint`; bit-exact `distribution_mask` and the program's tile choice (for prediction only) |
| `hd` | INTERFACE layouts (Config, Rig), HEARTBEAT/BREAK/PLAN preimages + SHA-256, the 20-byte dig entry, the dig instruction in the fixed account order, event parsing attributed to the right program |
| `gate` | `ema_ev = ema·6·500·10¹¹ / (5·(500·10¹¹ + pot))`, checked u128, one floor division, inclusive `≤ min(plan_max_ev_cost, cap_max_cost)`, closed on overflow |
| `heartbeat` | strict JSON, hex/base64 decoding, freshness, low-S normalization and `verify_like_precompile` from `crates/p256-introspect`, rig cache, latest-per-rig store |
| `intake` | axum WebSocket server, connection caps, size caps, token buckets, bounded verification pool, `/healthz`, `/metrics` |
| `chain` | `ChainSource` trait (WebSocket implementation; LaserStream stub), pure `Watcher` state machine, HTTP polling fallback |
| `planner` | pure function: state, lease/heartbeat, idempotency, caps, window, gate, amount with week rollover, strategy/fee/executor/authority, balance, Motherlode conditions, Miner existence and checkpoint, executor float |
| `tx`, `alt` | ComputeBudget, checkpoints, precompile instructions (8 per ix), dig; legacy / v0 + lookup tables / v1; exact packing; lookup-table create/extend/decode |
| `sender`, `ledger` | send (`maxRetries: 0`), rebroadcast, confirm; per-(rig, round) attempts with bounded retries |
| `breaker`, `metrics` | latched circuit breaker; Prometheus text with bounded label sets |
| `crank`, `app` | the loop and the process wiring |

**Deploy timing.** The loop waits until `end_slot − slot ≤ deploy_margin_slots` (20 ≈ 8 s) and stops at
`min_slots_left` (3). Deploying late means less SOL piles onto the chosen squares after the dig
(THREAT_MODEL K3: a crank that deploys early only raises the user's realized cost, inside the gate and caps).
Rounds that have not started (`end_slot == u64::MAX`) are left to ORE's own miners unless `start_rounds = true`.

**Idempotency and retries.** The program already makes a second dig in a round a skip (`last_dug_round`,
strictly increasing counters). The ledger keeps the crank from paying for duplicates: a rig goes into a new
transaction for a round only after its previous one expired, failed, or stayed unconfirmed for
`retry_after_slots`, at most `max_attempts_per_round` times. Rebroadcasts reuse the same signed bytes, so they
cannot double-land. **Every attempt is re-planned from fresh chain state**: the fork suite shows that an old
ORE `checkpoint` instruction, resent after the miner moved rounds, aborts the whole batch (INTERFACE-NOTES
§18).

**Lookup tables.** The crank creates its own table on first run (remembered in `state_dir`), adds the 10
accounts every dig repeats, and adds each rig's `rig, authority, automation, miner` as rigs appear (from the
poller, never inside the dig window: an extend is usable only from the next slot). Only tables it is the
authority of are extended; others in `alt.tables` are used read-only.

**Checkpoint sweep.** Miners of idle rigs that owe a checkpoint for a round older than 400 rounds (~8.7 h) are
checkpointed in batches of 8. That is before ORE's 12-hour bot window, so no one forfeits rewards and the
Executor PDA never has to refill a Miner's 10,000-lamport reserve (docs/ORE.md F8).

**Circuit breaker.** Trips, and latches until an operator restart, on: a Board/Treasury/ORE Config/Round that
fails its owner, size, discriminator or sanity pin; any ORE-owned Automation or Miner failing its pin; a
heads_down Config that no longer decodes; ORE's ProgramData upgrade slot moving off `ore_programdata_slot`
(450,496,378). While tripped nothing is submitted and `/healthz` returns 503. Restart only after the fork
suite passes against the new ORE binary (docs/ORE.md §8, Level 3).

## Heartbeat intake protocol

WebSocket `GET /ws`, one JSON text frame per message, 2 KiB cap. No auth tokens: heartbeats authenticate
themselves.

```json
→ {"type":"heartbeat","rig":"<base58 Rig PDA>","counter":7,"shift_id":3,"round_id":422601,"lease_rounds":2,
   "sig64":"<r||s: 128 hex chars or standard base64>","pubkey":"<optional, 33-byte compressed key>"}
← {"type":"ack","rig":"...","counter":7,"status":"accepted"}
← {"type":"ack","rig":"...","counter":7,"status":"rejected","reason":"stale_counter"}
→ {"type":"status"}
← {"type":"status","round_id":422601,"start_slot":...,"end_slot":...,"slot":...,"ema_ev":653163071}
```

The phone signs the **32-byte `SHA-256(HEARTBEAT preimage)`** with `SHA256withECDSA` (so the precompile, which
hashes its message with SHA-256, verifies ECDSA over SHA-256 of those 32 bytes):

```text
"HDv1" | program_id[32] | rig[32] | kind=1 | counter u64 | shift_id u64 | round_id u64 | lease_rounds u8   (94 bytes, LE)
```

Fixed vectors made by an independent Python + OpenSSL generator: `test-fixtures/vectors/interface.json`.
The crank checks, cheapest first: IP rate limit → JSON → encodings and `lease_rounds ∈ 1..=3` → per-rig rate
limit → freshness against `Board.round_id` → held counter → Rig account (state Armed/Down, `shift_id`,
`counter > hb_counter`, optional claimed key) → ECDSA. High-S signatures are normalized, not rejected. Only the
highest-counter heartbeat per rig is kept. The status reply is a convenience; phones should read
`Board.round_id` themselves (a lying crank could only make heartbeats useless).

## Transactions, packing and measured sizes

```text
[ComputeBudget limit, price]        legacy / v0 only (v1 puts them in the message config)
[ORE checkpoint ...]                 miners whose last round is unsettled (fresh Miner state, every attempt)
[Secp256r1SigVerify ...]             ≤ 8 heartbeats each; 32-byte digest messages; p256-introspect's builder
heads_down::dig                      [6, n, (hb_ix, hb_sig_index, counter, round_id, lease_rounds, 0) × n]
[System transfer tip]                Helius Sender only; last, so no precompile index moves
```

Packing compiles and serializes every candidate batch (`wincode`), so limits are measured, not estimated:

| Format | Fresh heartbeats per tx | Lease reuses per tx | Binding limit | Source |
|---|---|---|---|---|
| v0 + crank lookup table | **5** | **12** | 1232 bytes / 64 account locks | `tests/packing.rs` |
| v0 without table, legacy | 2 | | 1232 bytes | `tests/packing.rs` |
| v1 | **11** | not measured | 64 addresses (4096 bytes not reached) | `tests/packing.rs` |

Executed on live ORE (mock program, so CU is an upper bound for the real program, which need not re-derive 4
PDAs per rig):

| Run | Result |
|---|---|
| v0 + table: 2 fresh heartbeats, 1 lease reuse, 1 checkpoint (`tests/fork.rs`) | 796 bytes, 28 accounts, 134-156k CU, fees 15,220 lamports, reimbursed 3 × 7,000 |
| v1: 6 fresh heartbeats (`tests/fork.rs`) | 2,364 bytes, 267-305k CU (44-51k per rig) |
| legacy: 1 rig | 920 bytes, 43-65k CU |
| validator end to end: 3 fresh heartbeats, v0 + table (`tests/e2e_validator.rs`) | one tx, simulated CU limit 167,168, fee 20,168 lamports, reimbursed 21,000 |
| real precompile, 9 heartbeats in 2 precompile ixs (`tests/packing.rs`) | fee = 10 × 5,000 lamports + priority: every secp256r1 signature is charged like a tx signature |

## Operating costs per rig per dig

Fees are 5,000 lamports per transaction signature **and per secp256r1 signature** (measured in
`spikes/secp256r1`, re-asserted in `tests/packing.rs`), plus the priority fee
(`CU limit × price / 10⁶`). At the default 1,000 micro-lamports/CU and ~50k CU per rig, priority is ~50
lamports per rig.

| Mode | Rigs per tx | Signature fees per rig | Priority per rig | **Crank cost per rig-dig** |
|---|---|---|---|---|
| v0 + table, fresh heartbeat | 5 | 5,000 + 5,000/5 | ~50 | **~6,050 lamports** |
| v1, fresh heartbeat | 11 | 5,000 + 5,000/11 | ~50 | **~5,500** |
| v0 + table, lease reuse (`hb_ix = 0xFF`) | 12 | 5,000/12 | ~50 | **~470** |
| legacy / v0 without table | 2 | 5,000 + 2,500 | ~50 | ~7,550 |
| congestion: 100,000 micro-lamports/CU | | | ~5,000 | add ~5,000 |

Measured end to end: 3 rigs in one v0 transaction cost 20,168 lamports (6,723 per rig) against 21,000
reimbursed at `crank_fee = 7,000`.

- **Reimbursement** is `Config.crank_fee`, paid by the program from the Executor PDA only after a real
  deploy. A `crank_fee` of about 6,100 lamports covers fresh-heartbeat digs at normal priority; ECONOMICS.md
  uses 10,000 as a placeholder and ORE's own executor charges 7,000.
- **One-time per registered rig:** 4 lookup-table entries = 128 bytes of table rent = 890,880 lamports
  (0.00089 SOL, recoverable by deactivating and closing the table) + ~1,000 lamports of extend fees (5 rigs
  per extend).
- **Idle miners:** the checkpoint sweep costs at most 5,000 lamports per 8 miners, once per ~8.7 h of
  idleness.
- **Per night:** RESULTS.md's shipped configuration digs 13-26 times per rig-night, so ~80k-160k lamports
  per rig-night of crank cost, reimbursed dig by dig.

## Threat model

Consistent with [`docs/THREAT_MODEL.md`](../docs/THREAT_MODEL.md) K3 (crank and relayer).

**The crank can:** decide whether and when to submit each heartbeat; choose batch composition, priority fee
and timing within the round; checkpoint miners; see heartbeats (they are public anyway).

**The crank cannot:**
- forge a heartbeat: each dig needs a P-256 signature by the key in the Rig account over a preimage the
  program rebuilds from its own state (program id, rig, `shift_id`, `counter > hb_counter`, `round_id`);
- choose amounts or squares: `per_tile`, `k` and the mask are computed on-chain; the crank's prediction
  (`ore::select_tiles`) is only used for logs and is checked against the program in the fork suite;
- choose the authority: the program reads `rig.authority`; the crank passes it only because the account list
  must contain it, and the program re-derives the Automation and Miner from it;
- dig without a fresh lease for the current ORE round, twice in a round, above any cap, outside the plan
  window, after caps expire, or when the gate is closed;
- be reimbursed without a real ORE deploy for that rig in that transaction.

**Worst cases:** withhold every dig (nothing mines, nothing is lost); deploy early instead of late (raises the
user's realized cost, still inside gate and caps); crowd the rig's squares with its own SOL first (costs the
crank ~10.5% of that SOL, gains nothing: ORE is not parimutuel). Mitigations: competing cranks (anyone can run
this binary), the Nostr mirror hook, phones posting their own heartbeats.

**The crank's own attack surface:**
- *Intake DoS:* global and per-IP connection caps (IPv6 per /64) before the upgrade; 2 KiB frame cap; per-IP
  and per-rig token buckets before any RPC read or ECDSA verify; bounded verification pool (`busy` instead of
  queueing); idle and send timeouts; bounded key tables; unknown rigs cost at most one RPC read per rig per 30 s
  and at most `rig_fetches_per_second` overall. Unauthenticated clients can only fill the store with heartbeats
  that verify against registered keys.
- *Batch griefing:* one bad signature fails a whole transaction in the precompile, so every heartbeat is
  verified off-chain with the same checks first; simulation bisects any batch that still fails.
- *Secrets:* the fee-payer key is loaded from a mode-600 path and never logged; the RPC/WebSocket URL is a
  secret (only scheme and host are ever logged; transport errors are scrubbed); the Helius key comes only from
  the environment.
- *Lying RPC:* can make the crank waste fees or skip rigs, never deploy wrongly: the program re-checks
  everything.
- *No panics on untrusted input:* no `unwrap`/`expect`/`panic!` outside tests, bounds-checked decoders,
  checked or saturating arithmetic (overflow checks are on in every profile).

## Observability

- **Logs:** `tracing`, human or JSON (`log_json = true`), `RUST_LOG` filter. Never the keypair, never the full
  RPC URL.
- **`GET /healthz`:** 200 `{"status":"ok",...}` when the chain view is fresh and the breaker is closed;
  503 `degraded` with the breaker reason otherwise.
- **`GET /metrics`** (Prometheus text; label values are fixed reason codes, never rig addresses or IPs):
  `hd_crank_rigs_seen`, `hd_crank_rigs_eligible`, `hd_crank_heartbeats_accepted_total`,
  `hd_crank_heartbeats_rejected_total{reason}`, `hd_crank_heartbeats_held`, `hd_crank_intake_connections`,
  `hd_crank_digs_submitted_total`, `hd_crank_digs_landed_total`, `hd_crank_digs_skipped_total{reason}`,
  `hd_crank_digs_skipped_onchain_total{error}`, `hd_crank_txs_sent_total`, `hd_crank_txs_confirmed_total`,
  `hd_crank_txs_failed_total{kind}`, `hd_crank_checkpoints_sent_total`, `hd_crank_compute_units_total`,
  `hd_crank_fees_lamports_total`, `hd_crank_reimbursed_lamports_total`, `hd_crank_board_round_id`,
  `hd_crank_slot`, `hd_crank_ema_ev_lamports`, `hd_crank_motherlode`, `hd_crank_executor_lamports`,
  `hd_crank_cranker_lamports`, `hd_crank_circuit_breaker_tripped`, `hd_crank_chain_updates_total{account}`,
  `hd_crank_chain_reconnects_total`.

## Tests

```sh
cd crank
cargo test                                    # unit + integration: 82 tests, no network, no fixtures
cargo clippy --all-targets --all-features     # clean

# Fork suite: live mainnet ORE binary + accounts in LiteSVM, mock heads_down.
../spikes/ore-executor/fetch-fixtures.sh      # or ORE_FIXTURES_DIR=/path/to/fixtures
cargo build-sbf --manifest-path test-fixtures/mock-heads-down/Cargo.toml
cargo test --features fork --test fork -- --nocapture --test-threads=1

# End to end on solana-test-validator (~1-2 min): the real app::run wiring.
cargo test --features e2e --test e2e_validator -- --nocapture

# Same fork suite against the real program once it lands:
HD_PROGRAM_SO=../programs/heads-down/target/deploy/heads_down.so cargo test --features real-program --test fork
```

| Suite | Tests | What it proves |
|---|---|---|
| unit (`src/**`) | 46 | layouts and pins, gate floors, event attribution, lease ranges, ledger, rate limiter, ALT decode and warm-up, config and key guards, URL redaction, breaker, watcher state machine and WS message parsing |
| `tests/vectors.rs` | 6 | HEARTBEAT/BREAK/PLAN preimages and digests, program id, and `ema_ev` equal an independent Python implementation (incl. pot 0, huge pot, u64::MAX) |
| `tests/p256_verify.rs` | 7 | OpenSSL-made signature (low and high S), wrong key, wrong message, flipped parity, r/s = 0 or n, tampered fields, replay, unknown rig fetched once, cache refresh after re-arm and key rotation, strict JSON |
| `tests/packing.rs` | 7 | every batch under its limit and maximal; entries point at the right precompile entry (parsed by `p256-introspect`); real Agave precompile verifies crank-built data; one tampered signature sinks the tx |
| `tests/planner.rs` | 9 | every skip reason at its boundary; amount math incl. week rollover and ORE's per-tile cap; checkpoint prepending; tile prediction |
| `tests/intake.rs` | 5 | real sockets: acks, rejects, oversize frames, per-IP and per-rig limits, connection cap and release, `/healthz`, `/metrics` |
| `tests/litesvm_alt.rs` | 2 | hand-built ALT create/extend against the real ALT program; warm-up; authority-only extend |
| `tests/fork.rs` (`fork`) | 3 | live ORE: plan → pack → v0 + table → land; masks equal predictions; debits exact; reimbursement; same-round replay skipped; stale checkpoint aborts; wrong-shift heartbeat skipped; forged signature sinks the batch; v1 and legacy land |
| `tests/e2e_validator.rs` (`e2e`) | 1 | validator + live ORE + the binary's wiring: WS watcher, intake over WebSocket, table creation and rig registration, simulate-sized CU, 3 rigs dug in one tx at the window-open slot, metrics |

## Features and stubs

| Feature | Status |
|---|---|
| `fork` | LiteSVM fork suite (needs fixtures + the mock `.so`) |
| `e2e` | `solana-test-validator` end-to-end run |
| `real-program` | fork suite against `programs/heads-down` (`HD_PROGRAM_SO` overrides the path) — **wire once the program lands** |
| `helius-sender` | sends through Helius Sender with a rotating tip transfer; statuses from RPC. Tip accounts must be configured explicitly (no defaults). Not exercised against the live endpoint |
| `laserstream` | **stub**: `LaserStreamSource` implements `ChainSource` and returns an error; the WebSocket source is the working path |
| `nostr` | **stub**: builds NIP-01 kind-30078 templates of verified heartbeats and queues them; no signing or relay publishing yet |

## Limits and open issues

- The program is being written in parallel. The mock follows INTERFACE.md; the gaps it had to fill are in
  [`INTERFACE-NOTES.md`](INTERFACE-NOTES.md). The most important for the program: event byte layout (§1),
  `≤` vs `<` in the gate (§8), and stale checkpoints aborting batches (§18).
- Android still signs the old 101-byte raw heartbeat; it must move to the 94-byte preimage + SHA-256 digest
  (INTERFACE-NOTES §7). The crank rejects the old format.
- `focus_only` rigs and `record_heartbeats` (tag 7) are not cranked: its accounts and data are not in the
  contract yet.
- BREAK/FREEZE messages are not relayed; the phone sends them on-chain itself.
- CU figures come from the mock program; re-measure with the real program before fixing `crank_fee`.
- Heartbeats and the ledger live in memory: a restart loses held heartbeats until phones send the next one
  (one round), and on-chain idempotency covers the ledger.
