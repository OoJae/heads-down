# hd-crank

The permissionless crank for Heads Down. Phones send their signed heartbeats to it every ORE round; late in
each round it submits batched `heads_down::dig` transactions that carry those heartbeats to the secp256r1
precompile, and the program deploys each rig's own ORE Automation through the Executor PDA. It also lands the
phones' signed BREAK and FREEZE messages, records the heartbeats of focus-only rigs, and seals shifts that are
past their window.

**The crank has liveness only.** It chooses *whether* and *when* to submit a phone-signed message. The program
chooses the amount, the squares, the caps and the cost gate, and verifies every message on-chain. A malicious
crank can make rigs miss rounds; it cannot move a lamport anywhere ORE and the program would not (see
[Threat model](#threat-model)). Anyone can run one, and the fixed per-dig reimbursement pays for the digs.

- Built against the frozen contract [`programs/heads-down/INTERFACE.md`](../programs/heads-down/INTERFACE.md)
  **v1.1** and its machine-checked vectors (`programs/heads-down/vectors/`). `tests/golden.rs` rebuilds every
  instruction the crank sends from those vectors and must match them byte for byte. The older
  [`INTERFACE-NOTES.md`](INTERFACE-NOTES.md) is superseded.
- Tested against the **real `heads_down` program** and the **live mainnet ORE binary** (LiteSVM fork suite,
  `--features real-program`), end to end on `solana-test-validator` with the real process wiring, and
  read-checked against mainnet itself (`hd-crank check`).

## Contents

- [Run it](#run-it)
- [How it works](#how-it-works)
- [Phone intake (contract A)](#phone-intake-contract-a)
- [Landing BREAK and FREEZE](#landing-break-and-freeze)
- [Recording focus-only heartbeats](#recording-focus-only-heartbeats)
- [Permissionless end_shift](#permissionless-end_shift)
- [Demo tools: replay and decode](#demo-tools-replay-and-decode)
- [Transactions, packing and measured sizes](#transactions-packing-and-measured-sizes)
- [Operating costs](#operating-costs)
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

# Read-only: chain view, ORE pins, gate value, heads_down Config, a dry-run plan, open shifts.
./target/release/hd-crank --config crank.toml check

# Watcher + intake (/ws, /v1/heartbeats, /healthz, /metrics on `listen`) + digs, BREAK / FREEZE landing,
# record_heartbeats and the end_shift sweep.
./target/release/hd-crank --config crank.toml --keypair ~/.config/hd-crank/id.json run

# Captions for a demo video: the heads_down events of any transaction.
./target/release/hd-crank --config crank.toml decode <signature> [--json]

# Replay a landed dig's signed heartbeat with a fresh blockhash: RigSkipped(StaleHeartbeat).
./target/release/hd-crank --config crank.toml --keypair ~/.config/hd-crank/id.json replay --signature <dig tx> [--dry-run]
```

`hd-crank check` against mainnet on 2026-09-29 (read-only, public RPC):

```text
slot           451741378
board          round 422724 slots [451741342, 451741582) ema 952436545 lamports/ORE
motherlode     368.6 ORE
ema_ev         Some(657911497) lamports/ORE (gate value)
ore upgrade    slot Some(452682055) (pinned 452682055)
breaker        closed (all ORE pins match)
heads_down     Config not found at inzDn4ogmXbx9YDAKDHkfwJHy1jhsaWxGQvricDAEmW (program not initialized on this cluster)
```

Environment overrides: `HD_CRANK_RPC_URL`, `HD_CRANK_WS_URL`, `HD_CRANK_KEYPAIR`, `HD_CRANK_LISTEN`,
`HD_CRANK_TX_FORMAT` (`legacy|v0|v1`), `HD_CRANK_CONFIG`, `RUST_LOG`. A config file that embeds a literal
`api-key=` value is refused. The keypair file must be mode 600. Logs go to stderr, so `decode --json` output
on stdout stays clean.

## How it works

```text
 phones ──WS /ws──► intake ─ rate limits ─► verifier (P-256 vs Rig.p256_pubkey) ─┬─► latest heartbeat per rig
                                                     ▲ Rig cache (getAccountInfo) └─► SignalHub (BREAK / FREEZE)
 ORE ──WS accountSubscribe + HTTP poll──► watcher (Board, Treasury, ORE Config, current Round; pins → breaker)
                                              │
     ┌─────────────────────────────┬──────────┴────────────────────────┬───────────────────────────────┐
     ▼ slots_left ≤ deploy_margin  ▼ delay_secs after a new round      ▼ every end_shift.poll_secs     ▼ queue
 dig pass                       record pass                       end_shift sweep                 signal lander
 Rigs (Armed, Down, Cooling)    focus-only rigs with a fresh      Rigs with shift_open = 1,       [CU, Secp256r1,
 → Automations, Miners          heartbeat, due every N rounds     window over, lease expired      break_shift |
 → planner (v1.1 §6 off-chain)  → record_heartbeats batches       → end_shift (crank pays rent)   freeze_rig]
 → pack → simulate → send       (budgeted)                        (capped per day)                simulate → send
 → confirm → RigDug/RigSkipped
```

| Module | What it does |
|---|---|
| `ore` | ORE layouts at the pinned commit; owner + exact size + discriminator checked before any read; Level-1 sanity pins; PDAs; `checkpoint`; bit-exact `distribution_mask` and the program's tile choice with the Miner's held squares excluded |
| `hd` | INTERFACE v1.1 layouts (Config, Rig with the §3.3 fields, ShiftLog), HEARTBEAT / BREAK / FREEZE / PLAN preimages + SHA-256, `grant_lease`, the 20-byte entry, builders for `dig`, `record_heartbeats`, `break_shift` / `freeze_rig` (P-256 path) and `end_shift`, error names 0..=31 and the p256 codes, every event (tags 1..=10) attributed to the right program |
| `gate` | `ema_ev = ema·6·500·10¹¹ / (5·(500·10¹¹ + pot))`, checked u128, one floor division, inclusive `≤ min(plan_max_ev_cost, cap_max_cost)`, closed on overflow |
| `heartbeat` | contract-A submissions (numbers or decimal strings), freshness, low-S normalization and `verify_like_precompile` from `crates/p256-introspect`, rig cache, latest-per-rig store, BREAK / FREEZE verification |
| `intake` | axum WebSocket server (contract A), connection caps, size caps, token buckets, bounded verification pool, `/healthz`, `/metrics` |
| `signal` | BREAK / FREEZE idempotency (per-rig counter + digest), per-rig limits, lamport budget, landing queue, "pending" for the dig planner |
| `chain` | `ChainSource` trait (WebSocket implementation; LaserStream stub), pure `Watcher` state machine, HTTP polling fallback |
| `planner` | pure functions: the dig plan (state, Cooling, lease, idempotency, caps, window, gate, v1.1 amount rule, strategy / fee / executor / authority, balance, Motherlode, Miner checkpoint, executor float) and the record plan |
| `tx`, `alt` | ComputeBudget, checkpoints, precompile instructions (8 per ix), dig / record batches; legacy / v0 + lookup tables / v1; exact packing; signal and end_shift transactions; lookup-table create/extend/decode |
| `sender`, `ledger` | send (`maxRetries: 0`), rebroadcast, confirm; per-(rig, round) attempts with bounded retries |
| `breaker`, `metrics` | latched circuit breaker; Prometheus text with bounded label sets |
| `crank`, `app` | the loop and the process wiring |
| `demo` | `replay` and `decode` |

**The dig amount** is the program's rule, integer for integer (INTERFACE v1.1 §6.4):

```text
budget   = min(plan_dig, min(cap_round, cap_shift − spent_shift, cap_week − spent_week) − automation.fee)   (saturating)
mask     = plan_split / plan_solo least-crowded squares, excluding squares the Miner already holds this round
k        = popcount(mask)
per_tile = min(budget / k, automation.amount)
fee_due  = automation.fee if this is the rig's first deploy of the round, else 0
debit    = per_tile·k + fee_due        (what spent_shift / spent_week count)
RigDug.lamports = per_tile·k           (SOL on squares only)
```

With `cap_round = plan_dig = 1,000,000` on 10 squares and a 10,000 fee, the program deploys 99,000 per square and
the whole debit is exactly 1,000,000. The planner reports `squares_lamports`, `fee_due` and `expected_debit`
separately; the fork suite asserts all three against the real program.

**States.** Armed and Down dig on a covering lease (`hb_ix = 0xFF`, no precompile signature to pay for). A
**Cooling** rig (after a BREAK pickup, screen-on or unplugged) digs only with a fresh heartbeat in the same entry;
the planner never reuses its lease. Idle, Broken and Frozen never dig. Focus-only rigs never dig; their heartbeats
are recorded instead.

**Deploy timing.** The loop waits until `end_slot − slot ≤ deploy_margin_slots` (20 ≈ 8 s) and stops at
`min_slots_left` (3). Deploying late means less SOL piles onto the chosen squares after the dig (THREAT_MODEL K3).
Rounds that have not started (`end_slot == u64::MAX`) are left to ORE's own miners unless `start_rounds = true`.

**Idempotency and retries.** The program already makes a second dig in a round a skip (`last_dug_round`,
strictly increasing counters). The ledger keeps the crank from paying for duplicates: a rig goes into a new
transaction for a round only after its previous one expired, failed, or stayed unconfirmed for
`retry_after_slots`, at most `max_attempts_per_round` times. Rebroadcasts reuse the same signed bytes. **Every
attempt is re-planned from fresh chain state**: an old ORE `checkpoint` instruction, resent after the miner moved
rounds, aborts the whole batch (`tests/fork.rs`).

**Lookup tables.** The crank creates its own table on first run (remembered in `state_dir`), adds the 10
accounts every dig repeats, and adds each heartbeating rig's `rig, authority, automation, miner` as rigs appear
(never inside the dig window). Only tables it is the authority of are extended.

**Checkpoint sweep.** Miners of idle rigs that owe a checkpoint for a round older than 400 rounds (~8.7 h) are
checkpointed in batches of 8, before ORE's 12-hour bot window.

**Circuit breaker.** Trips, and latches until an operator restart, on: a Board/Treasury/ORE Config/Round that
fails its owner, size, discriminator or sanity pin; any ORE-owned Automation or Miner failing its pin; a
heads_down Config that no longer decodes; ORE's ProgramData upgrade slot moving off `ore_programdata_slot`.
While tripped nothing is submitted and `/healthz` returns 503.

## Phone intake (contract A)

WebSocket `GET /ws` (alias `GET /v1/heartbeats`), one JSON text frame per message, 2 KiB cap. No auth tokens:
the messages authenticate themselves. Integers are JSON numbers or decimal strings; unknown fields are ignored.

```json
→ {"type":"heartbeat","rig":"<base58>","counter":7,"shift_id":3,"round_id":422601,"lease_rounds":2,"sig64":"<b64>"}
→ {"type":"break","rig":"<base58>","counter":8,"shift_id":3,"reason":1,"sig64":"<b64>"}      reason ∈ {1,2,4,5,6,7,8}
→ {"type":"freeze","rig":"<base58>","counter":9,"shift_id":3,"reason":3,"sig64":"<b64>"}
← {"type":"ack","counter":7,"ok":true,"reason":"accepted"}
← {"type":"ack","counter":7,"ok":false,"reason":"stale_counter"}
→ {"type":"status"}
← {"type":"status","round_id":422601,"start_slot":...,"end_slot":...,"slot":...,"ema_ev":653163071}
```

`sig64` is standard base64 of the 64-byte `r‖s` (hex is also accepted; high-S is normalized). The phone signs
`SHA-256(preimage)` with `SHA256withECDSA`; the preimages are INTERFACE v1.1 §4.1 (HEARTBEAT 94 bytes,
BREAK / FREEZE 86 bytes), checked against `programs/heads-down/vectors/messages.json` in `tests/golden.rs`.
Legacy spellings are accepted: `kind` for `type`, `sig` for `sig64`, and a frame with neither `type` nor `kind`
is a heartbeat. `status` is a convenience only: phones should read `Board.round_id` themselves.

Ack codes (exactly these): `accepted, bad_signature, stale_counter, unknown_rig, rate_limited, malformed,
lease_invalid`. Internal reasons map onto them, and the metrics keep the internal reason:

| Ack | Internal reasons | What the phone should do |
|---|---|---|
| `accepted` | heartbeat stored; BREAK / FREEZE queued for landing (or the same message again, or a FREEZE for a rig already Frozen) | nothing |
| `malformed` | bad JSON, a missing listed field, bad base58 / base64, a BREAK reason outside {1,2,4,5,6,7,8}, a FREEZE reason ≠ 3, an unknown `type` | fix the client |
| `lease_invalid` | `lease_rounds` ∉ 1..=3, `round_id` more than one round ahead, a lease that already ended, a `shift_id` that is not `rig.shift_id`, a heartbeat for a rig not Armed / Down / Cooling, a BREAK for a rig not Armed / Down / Cooling | re-read `Board.round_id` / the Rig and re-sign |
| `stale_counter` | a counter at or below `rig.hb_counter`, the held heartbeat's, or an accepted BREAK / FREEZE's | move the counter past the chain's |
| `unknown_rig` | no heads_down Rig at the address | register |
| `bad_signature` | ECDSA does not verify against `Rig.p256_pubkey` | check the key (rotation?) |
| `rate_limited` | per-IP or per-rig limit, verification pool or queue full, RPC unavailable, signal fee budget spent, signal landing disabled | back off and retry; keep the local record |

The crank checks, cheapest first: IP rate limit → JSON → encodings and static rules → per-rig rate limit → held
counters → Rig account (state, `shift_id`, `counter > hb_counter`) → ECDSA. Only the highest-counter heartbeat per
rig is kept.

## Landing BREAK and FREEZE

A verified BREAK / FREEZE goes to the `SignalHub`, which decides before the phone gets its ack:

- **Idempotent.** Per rig it remembers the highest accepted counter and that message's digest. The same message
  again is acknowledged and not queued twice; any other message at or below that counter is `stale_counter`. A
  signal the program refused (in simulation or on-chain) is never resubmitted; one that failed for a transient
  reason (blockhash expiry, RPC) is queued again if the phone sends it again.
- **Rate-limited.** A per-rig token bucket (`signals.rig_burst`, `signals.rig_per_minute`) and a lamport budget
  (`signals.max_lamports_per_hour`): the crank pays these fees and nothing reimburses them.
- **Prompt.** The lander sends `[SetComputeUnitLimit(5,000), SetComputeUnitPrice(20,000 µL), Secp256r1SigVerify
  (the phone's signature over the 32-byte digest), break_shift | freeze_rig (mode 1, p256_ix = 2)]` right away,
  simulated first (a signal the program would refuse is not sent), rebroadcast until confirmed, and retried with a
  fresh blockhash if it expires. The accounts are `rig (w), rig.authority (not a signer), instructions sysvar`;
  the crank is only the fee payer (vectors `break_shift_p256` / `freeze_rig_p256`).
- **Respected by the planner.** While a signal is queued or in flight the rig is left out of the dig
  (`signal_pending`), so a phone that was just picked up is not dug on the lease it held a moment earlier. Once it
  lands, the chain says Cooling / Broken / Frozen and the planner follows that.

A FREEZE for a rig that is already Frozen is acknowledged and not landed (nothing changes on-chain). If the crank
is unreachable the phone keeps its local record and the rig simply stops digging once its lease runs out.

## Recording focus-only heartbeats

Focus-only rigs (`plan_flags` bit 0) never deploy, so no dig carries their heartbeats. Once per round,
`record.delay_secs` after the round is first seen, the crank records the latest held heartbeat of every
focus-only rig that is due with `record_heartbeats` (tag 7, no CPI), so its dark rounds count on-chain:

- **Due** when the rig has no lease yet in this shift, or `Board.round_id ≥ lease_from_round + every_rounds`
  (default 3). `lease_from_round` is the round of the last heartbeat that landed, so the rule survives restarts
  and holds across cranks. With `every_rounds` no larger than the phone's lease (1..=3), every round of the shift
  is dark.
- **Only useful heartbeats**: same shift, counter above `hb_counter`, the rig's key, not from the future, inside
  the plan window, and a lease that ends after the current `lease_to_round` (otherwise it would change nothing).
- **Budgeted**: `record.max_lamports_per_hour`, at most 8 transactions per round. Each recorded rig costs one
  secp256r1 signature (5,000 lamports) plus its share of the transaction signature; nothing reimburses it.
- Opt-in `record.gate_closed_rigs = true` also records night / day rigs whose cost gate is closed this round, so
  a night where the gate never opens still ends with its dark rounds counted.

## Permissionless end_shift

Every `end_shift.poll_secs` the crank lists Rigs with `shift_open = 1` (memcmp at offset 336) and seals those
whose window ended more than `grace_secs` ago and whose lease has been expired for more than three rounds
(`lease_to_round + 3 < Board.round_id`), exactly the program's condition for a caller that is not the
authority. The three rounds are the program's grace: a rig whose next heartbeat is still on its way cannot have
its shift ended by someone else between two heartbeats. The crank pays the ShiftLog rent
(1,781,760 lamports for 128 bytes) plus the fee, so it is capped: at most `max_per_pass` per pass and
`max_lamports_per_day` (default 0.05 SOL, about 27 shifts). Oldest window first; a (rig, shift) that fails is
left alone for 10 minutes. The program emits `ShiftEnded` (tag 4) and then `ShiftEndedV2` (tag 10); the crank's
decoder keeps only the V2, so a shift is never counted twice.

## Demo tools: replay and decode

`hd-crank replay --signature <landed dig tx>` fetches the transaction, finds the `dig`, resolves every
fresh-heartbeat entry to its Secp256r1SigVerify entry (the phone's exact signature, key and digest), and resubmits
them with a fresh blockhash in the current round: `[ComputeBudget, the same signatures, dig (same rigs and
entries, the live Round PDA)]`. The program refuses the old heartbeat with `RigSkipped(StaleHeartbeat)`. The tool
refuses to send anything that is not stale (every counter must already be at or below the rig's on-chain
`hb_counter`), never replays lease reuses or ORE checkpoints, simulates first, and refuses to send if the
simulation shows a `RigDug`. `--rig <address>` picks rigs, `--dry-run` only simulates. Real output on the
local devstack (`scripts/devstack`, real program), replaying the crank's own v0 + lookup-table dig of the smoke
rig one round later:

```text
$ hd-crank replay --signature wU2poXVq…AY31NjL5
replaying dig wU2poXVq…AY31NjL5 (slot 551): 1 signed heartbeat(s), 0 lease reuse(s) left out
  rig GSmTD1S7…LNxPJpv  heartbeat #2 signed for round 422772 (lease 1)  on-chain hb_counter 2  state down  expect RigSkipped(StaleHeartbeat)
resubmitting the same signed bytes with a fresh blockhash, in ORE round 422773
tx dSAxwhM1…Cpp9phbw landed in slot 799
  RigSkipped          rig GSmT…PJpv  round 422773  StaleHeartbeat (0x7)
```

`hd-crank decode <signature>` prints one caption line per heads_down event with error, reason and mode names
(`--json` for one JSON object; u64 values as decimal strings). Same devstack run:

```text
$ hd-crank decode wU2poXVq…AY31NjL5
tx wU2poXVq…AY31NjL5  slot 551  ok  fee 10,047 lamports  39,740 CU
  RigDug              rig GSmT…PJpv  round 422772  1,000,000 lamports on 10 squares (mask 0x080e42f)  ema_ev 599,631,045 lamports/ORE
$ hd-crank decode 4yBRaoj6…w84bhKaB --json
{"compute_units":14721,"err":null,"events":[{"error":8,"error_name":"LeaseExpired","event":"RigSkipped","rig":"GSmTD1S76jJfDN1ueoycZxoW4Gw5ah7rmM9i4LNxPJpv","round_id":"422773"}],"fee":5000,"ok":true,"signature":"4yBRaoj6…","slot":623}
```

(Signatures shortened here; the tools print them in full.)

## Transactions, packing and measured sizes

```text
[ComputeBudget limit, price]        legacy / v0 only (v1 puts them in the message config)
[ORE checkpoint ...]                 miners whose last round is unsettled (fresh Miner state, every attempt)
[Secp256r1SigVerify ...]             ≤ 8 heartbeats each; 32-byte digest messages; p256-introspect's builder
heads_down::dig                      [6, n, (hb_ix, hb_sig_index, counter, round_id, lease_rounds, 0) × n]
[System transfer tip]                Helius Sender only; last, so no precompile index moves
```

Packing compiles and serializes every candidate batch (`wincode`), so limits are measured, not estimated.
Executed with the **real program** on live ORE (`cargo test --features real-program --test fork`, three runs;
CU varies with ORE's bump search on each wallet's PDAs, so treat the ranges as samples, not bounds):

| Format | Fresh heartbeats per tx | Lease reuses per tx | Bytes | CU per rig |
|---|---|---|---|---|
| v0 + crank lookup table | **5** | **12** | 1,206 (5 fresh) / 650 (12 reuses) | 32.7k-34.5k / 32.3k-33.3k |
| v1 | **11** | not measured | 3,845 | 34.1k-35.8k |
| legacy / v0 without table | 2 | | 1,215 | 32.8k-35.1k |

`record_heartbeats` packs 4 rigs per legacy transaction (1,111 bytes, ~1,545 CU per rig); a BREAK / FREEZE is
1,429 CU; `end_shift` is 5,195 CU.

## Operating costs

Fees are 5,000 lamports per transaction signature **and per secp256r1 signature** (re-asserted in
`tests/packing.rs` and the fork suite), plus the priority fee (`CU limit × price / 10⁶`). Measured with the real
program, CU limit sized by simulation (+15% + 1,000), 1,000 micro-lamports/CU, `crank_fee = 7,000`:

| Dig mode | Rigs per tx | Fee per rig | Reimbursed per rig | **Net per rig-dig** |
|---|---|---|---|---|
| v0 + table, fresh heartbeat | 5 | 6,037-6,040 | 7,000 | **+960 to +962** |
| v1, fresh heartbeat | 11 | 5,493-5,495 | 7,000 | **+1,504 to +1,506** |
| v0 + table, lease reuse (`hb_ix = 0xFF`) | 12 | 453-455 | 7,000 | **+6,545 to +6,546** |
| legacy, fresh heartbeat | 2 | 7,538-7,541 | 7,000 | −538 to −541 |

- **Reimbursement** is `Config.crank_fee`, paid by the program from the Executor PDA only after a real deploy
  that brought the Executor at least `crank_fee` in the same dig, and only while the Executor keeps
  `rent(0) + 100,000 + crank_fee`. ORE charges the Automation fee on a Miner's first deploy of a round only, so
  a rig whose Miner already deployed this round (its owner deployed by hand) would be dug at the crank's own
  cost: the planner skips it (`miner_already_deployed`) unless `dig_unpaid = true`. A `crank_fee` around 6,100 covers
  fresh-heartbeat digs at normal priority; the devstack uses 7,000 (ORE's own executor charges 7,000).
- **The user's side** (not the crank's): the Automation pays `per_tile·k` on squares plus `executor_fee` on the
  rig's first deploy of each round, all inside the wallet-signed caps.
- **Not reimbursed, budgeted by the crank:**

  | Duty | Cost | Default cap |
  |---|---|---|
  | BREAK / FREEZE | 10,100 lamports each (2 signatures + 5,000 CU × 20,000 µL) | 2,000,000 / hour |
  | `record_heartbeats` | 6,254 per rig at 4 per legacy tx (5,000 + 5,000/4 + priority); 10,007 alone | 2,000,000 / hour |
  | `end_shift` | 1,781,760 ShiftLog rent + 5,015 fee | 50,000,000 / day |

  A focus-only rig recorded every 3 rounds costs about 520,000 lamports per 8-hour night (~250 ORE rounds).
- **One-time per heartbeating rig:** 4 lookup-table entries = 890,880 lamports of table rent (recoverable by
  closing the table) + ~1,000 lamports of extend fees.
- **Idle miners:** the checkpoint sweep costs at most 5,000 lamports per 8 miners, once per ~8.7 h of idleness.

## Threat model

Consistent with [`docs/THREAT_MODEL.md`](../docs/THREAT_MODEL.md) K3 (crank and relayer).

**The crank can:** decide whether and when to submit each heartbeat, BREAK, FREEZE or record; choose batch
composition, priority fee and timing within the round; checkpoint miners; end shifts that are past their window
and lease; see the phones' messages (they are public anyway).

**The crank cannot:**
- forge a message: each needs a P-256 signature by the key in the Rig account over a preimage the program
  rebuilds from its own state (program id, rig, `shift_id`, `counter > hb_counter`, `round_id` or `reason`);
- choose amounts or squares: `per_tile`, `k` and the mask are computed on-chain;
- choose the authority: the program reads `rig.authority` and re-derives the Automation and Miner from it;
- dig without a lease covering the current ORE round, twice in a round, above any cap (the fee included),
  outside the plan window, after caps expire, when the gate is closed, or a Cooling rig without a fresh heartbeat;
- end a shift early: before the window ends and the lease expires, only the wallet can;
- be reimbursed without a real ORE deploy for that rig in that transaction.

**Worst cases:** withhold every dig (nothing mines, nothing is lost); withhold a BREAK (the rig can still be dug
until its lease, at most 3 rounds, runs out: the lease was the phone's own promise); deploy early instead of late
(raises the user's realized cost, still inside gate and caps). Mitigations: competing cranks (anyone can run this
binary), the Nostr mirror hook, phones posting their own messages.

**The crank's own attack surface:**
- *Intake DoS:* global and per-IP connection caps (IPv6 per /64) before the upgrade; 2 KiB frame cap; a per-IP
  token bucket before any RPC read or ECDSA verify; bounded verification pool; send timeout; bounded key tables;
  unknown rigs cost at most one RPC read per rig per 30 s.
  - *A rig's own allowance is spent only by messages that verified under its key.* The per-rig bucket is looked
    at before the signature check and charged after it, so frames that merely name a rig cannot silence it.
  - *A connection must talk, and must prove itself.* The idle timeout counts text frames only (a Ping does not
    extend it), and a connection that has not delivered a verified message within `unverified_timeout_secs`
    (180) is closed. A host with many addresses can still open connections as fast as they are closed; the caps
    bound how many at once.
  - *The client address is one the client cannot choose:* the socket peer, or, behind a proxy, the last line of
    the header that proxy writes (`trust_real_ip` for `X-Real-IP`, which Railway documents as the client's
    address; `trust_forwarded_for` for the last `X-Forwarded-For` hop). `GET /whoami` returns the address the
    limits are keyed on, to check after a deploy that a header sent by the caller does not change it.
- *Fee draining:* BREAK / FREEZE, records and end_shift are paid by the crank, so each has a per-rig limit and a
  lamport budget; a signal is simulated before it is paid for. Records are served longest-waiting rig first, so
  a budget that cannot pay for everyone goes round the rigs. What a budget does not prevent: rigs that each stay
  inside their own limit can together use up the hourly BREAK / FREEZE budget, and then the team crank lands no
  more signals that hour. A rig whose signal is not landed stops being dug when its lease runs out (at most 3
  rounds), and FREEZE can always be sent by the wallet itself.
- *Batch griefing:* one bad signature fails a whole transaction in the precompile, so every message is verified
  off-chain first; simulation bisects any batch that still fails.
- *Secrets:* the fee-payer key is loaded from a mode-600 path and never logged; the RPC/WebSocket URL is a secret
  (only scheme and host are ever logged); the Helius key comes only from the environment.
- *No panics on untrusted input:* no `unwrap`/`expect`/`panic!` outside tests, bounds-checked decoders, checked
  or saturating arithmetic (overflow checks are on in every profile).

## Observability

- **Logs:** `tracing` to stderr, human or JSON (`log_json = true`), `RUST_LOG` filter.
- **`GET /healthz`:** 200 `{"status":"ok",...}` when the chain view is fresh and the breaker is closed;
  503 `degraded` otherwise. Includes `signals_enabled` and the remaining signal fee budget.
- **`GET /metrics`** (Prometheus text; labels are fixed codes, never rig addresses or IPs): digs
  (`hd_crank_digs_submitted_total`, `_landed_total`, `_skipped_total{reason}`, `_skipped_onchain_total{error}`
  with the v1.1 names incl. 24..31 and the p256 codes), `hd_crank_squares_lamports_total` (sum of
  `RigDug.lamports`, no fee), `hd_crank_automation_debit_lamports_total` (squares + fee as planned), fees,
  reimbursements and CU; the intake (`hd_crank_heartbeats_accepted_total`, `_rejected_total{reason}`,
  `hd_crank_signals_accepted_total{kind}`, `_rejected_total{reason}`); signal landing
  (`hd_crank_signals_landed_total{kind}`, `_failed_total{stage}`, `hd_crank_signal_fees_lamports_total`);
  records (`hd_crank_record_txs_sent_total`, `hd_crank_heartbeats_recorded_total`,
  `hd_crank_record_dark_rounds_total`, `hd_crank_record_skipped_total{reason}`, `hd_crank_record_fees_lamports_total`);
  end_shift (`hd_crank_shifts_ended_total`, `hd_crank_end_shift_failed_total{stage}`,
  `hd_crank_end_shift_lamports_total`); chain, breaker and balances as before.

## Tests

```sh
cd crank
cargo test                                    # unit + integration, no network, no fixtures
cargo clippy --all-targets --all-features     # clean

# Fork suite: live mainnet ORE binary + accounts in LiteSVM.
../spikes/ore-executor/fetch-fixtures.sh      # or ORE_FIXTURES_DIR=/path/to/fixtures
cargo build-sbf --manifest-path test-fixtures/mock-heads-down/Cargo.toml
cargo test --features fork --test fork -- --nocapture --test-threads=1          # mock heads_down

bash ../programs/heads-down/scripts/build.sh                                    # the real program
HD_PROGRAM_SO=../programs/heads-down/target/deploy/heads_down.so \
  cargo test --features real-program --test fork -- --nocapture --test-threads=1

# End to end on solana-test-validator (~1-2 min): the real app::run wiring.
cargo test --features e2e --test e2e_validator -- --nocapture                   # mock
cargo test --features e2e,real-program --test e2e_validator -- --nocapture      # real program
```

| Suite | Tests | What it proves |
|---|---|---|
| unit (`src/**`) | 65 | layouts and pins, the v1.1 Rig fields, lease grants equal to the program's, events 1..=10 and the tag-4/10 dedupe, error names, budget and week roll equal to the program's, held squares and `k`, ack-code mapping, signal hub idempotency / budget / queue, config, rate limiter, ALT, breaker, watcher, RPC transaction parsing |
| `tests/golden.rs` | 6 | every builder against `programs/heads-down/vectors/`: `dig` (3 vectors, PDAs re-derived), `record_heartbeats`, `break_shift` / `freeze_rig` P-256, `end_shift` (both callers), all precompile data rebuilt byte for byte, all 5 messages, every event sample and skip-code name |
| `tests/vectors.rs` | 6 | preimages, digests and `ema_ev` against an independent Python implementation |
| `tests/p256_verify.rs` | 7 | OpenSSL-made signatures (low and high S), wrong key, wrong message, r/s = 0 or n, tampering, replay, unknown rig fetched once, cache refresh after re-arm and key rotation, contract-A JSON |
| `tests/packing.rs` | 7 | every batch under its limit and maximal; entries point at the right precompile entry; the real Agave precompile verifies crank-built data; one tampered signature sinks the tx |
| `tests/planner.rs` | 14 | every skip reason at its boundary; the fee inside every cap; held squares and the first-deploy fee; Cooling; week rollover; checkpoint prepending; tile prediction; the record planner (due, extension, window, state, gate-closed opt-in) |
| `tests/intake.rs` | 9 | real sockets: contract-A acks for heartbeats, BREAK and FREEZE (idempotent, stale, reasons, shift, state), decimal strings, unknown fields, legacy spellings, `/v1/heartbeats`, signal budget and disable, per-IP / per-rig limits, size and connection caps, `/healthz`, `/metrics` |
| `tests/litesvm_alt.rs` | 2 | ALT create/extend against the real ALT program |
| `tests/fork.rs` (`fork`, mock) | 4 | live ORE: plan → pack → v0 + table → land; masks equal predictions; squares and debits exact; reimbursement; replay skipped; stale checkpoint aborts; wrong-shift heartbeat skipped with the p256 code; forged signature sinks the batch; Cooling; v1 and legacy land |
| `tests/fork.rs` (`real-program`) | 9 | the 4 above against the real program, plus BREAK / FREEZE landed (Cooling → Broken → Frozen, stale resubmission refused), focus-only `record_heartbeats` (9 rigs, 3 batches, dark rounds on-chain), permissionless `end_shift` (rent, V2 event, refused while the lease lives), `replay` → `StaleHeartbeat`, and the cost table above |
| `tests/e2e_validator.rs` (`e2e`) | 1 | validator + live ORE + the binary's wiring: WS watcher, contract-A intake, table creation, simulate-sized CU, 3 rigs dug in one tx; with `real-program` also a BREAK and a FREEZE landed through the intake, a focus-only heartbeat recorded and a stale shift sealed |

## Features and stubs

| Feature | Status |
|---|---|
| `fork` | LiteSVM fork suite (needs fixtures + the mock `.so`) |
| `real-program` | the fork suite (and `e2e`) against the real `programs/heads-down` build (`HD_PROGRAM_SO` overrides the path) |
| `e2e` | `solana-test-validator` end-to-end run |
| `helius-sender` | sends through Helius Sender with a rotating tip transfer; statuses from RPC. Tip accounts must be configured explicitly. Not exercised against the live endpoint |
| `laserstream` | **stub**: `LaserStreamSource` implements `ChainSource` and returns an error; the WebSocket source is the working path |
| `nostr` | **stub**: builds NIP-01 kind-30078 templates of verified heartbeats and queues them; no signing or publishing |

## Limits and open issues

- **An ack is not a landing.** `accepted` for a BREAK / FREEZE means verified and queued; contract A has no frame
  for the landing result. The outcome is in the crank's logs and metrics, and on-chain (`ShiftBroken`).
- **`scripts/devstack` smoke** reads the old ack field (`ack["status"] == "accepted"`); with contract A it must
  read `ack["ok"] == true` (owned by the devstack workstream).
- The mock implements `dig` only; BREAK / FREEZE, `record_heartbeats` and `end_shift` are tested against the real
  program.
- `record.gate_closed_rigs` is off by default: a night whose gate never opens ends without dark rounds unless a
  crank operator opts in (and pays for it).
- Heartbeats, signal tracking and the ledger live in memory: a restart loses held heartbeats until phones send the
  next one (one round); on-chain counters keep everything idempotent.
- `hd_crank_fees_lamports_total`, `hd_crank_signal_fees_lamports_total` and `hd_crank_record_fees_lamports_total`
  read the fee from `getTransaction` after the landing (5 tries, about 4 s), so they can lag the landed counters
  and miss a transaction the RPC never returns. The lamport budgets are unaffected: they are debited with the
  computed fee before each transaction is sent (for a BREAK / FREEZE, when the phone's message is accepted).
