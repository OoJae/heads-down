# `heads_down` program — interface contract (v1 core)

This is the **contract** that the program, the crank, the indexer and the Android transaction builders
all build against, in parallel. Change it only via a commit that updates every consumer.
All integers are **little-endian**. Byte offsets are from the start of account data.
Addresses are 32 bytes. `Option<Address>` is encoded as 32 zero bytes = None.

Program ID: **TBD** — fixed when `programs/heads-down` is created (`target/deploy/heads_down-keypair.json`);
exported as `heads_down::ID` (Rust) and `HeadsDownProgram.ID` (Kotlin).

## External programs and accounts (pinned)
| Name | Address |
|---|---|
| ORE program | `oreV3EG1i9BEgiAJ8b177Z2S2rMarzak4NMv1kULvWv` |
| ORE Board | `BrcSxdp1nXFzou1YyDnQJcPNBNHgoypZmTsyKBSLLXzi` |
| ORE Config | `9c9X7aDRAF41faiDs94ELjT19UrGnn72wBW9hPsS4Awy` |
| ORE Treasury | `45db2FSR4mcXdSVVZbKbwojU6uYDpMyhpEi7cC8nHaWG` |
| ORE entropy Var | `BWCaDY96Xe4WkFq1M7UiCCRcChsJ3p51L5KrGzhxgm2E` |
| Entropy program | `3jSkUuYBoJzQPMEzTvkDFXCZUBksPamrVhrnHR9igu2X` |
| ORE PDAs | Automation `[b"automation", authority]`, Miner `[b"miner", authority]`, Round `[b"round", round_id u64 LE]` (all under ORE) |
| Secp256r1SigVerify | `Secp256r1SigVerify1111111111111111111111111` |
| Ed25519SigVerify | `Ed25519SigVerify111111111111111111111111111` |
| Instructions sysvar | `Sysvar1nstructions1111111111111111111111111` |
| Token-2022 | `TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb` |
| SGT group / mint authority | `GT22s89nU4iWFkNXj1Bw6uYhJJWDRPpShHt4Bk8f99Te` / `GT2zuHVaZQYZSyQMgJPLzvkmyztfyXg2NJunqFp4p3A4` |

ORE facts relied on (see `spikes/ore-executor/README.md`): the user's ORE Automation uses **strategy
Discretionary (2)**, `executor = Executor PDA`, a fixed `fee`, and the per-tile cap `automation.amount`.
ORE does **not** enforce `max_production_cost`, so this program enforces the cost gate itself.

## PDAs
| Account | Seeds (under `heads_down`) | Owner |
|---|---|---|
| Config | `[b"config"]` | heads_down |
| Executor | `[b"executor"]` | **System** (data-less; holds lamports) |
| Rig | `[b"rig", authority]` | heads_down |
| SeekerSeat | `[b"seeker", sgt_mint]` | heads_down |
| ShiftLog | `[b"shift", rig, shift_id u64 LE]` | heads_down |

`authority` = the user's wallet that owns the ORE Automation (Seed Vault / Solflare / Phantom account).

## Account header (all heads_down-owned accounts)
`[0] tag u8 | [1] version u8 (=1) | [2] bump u8 | [3..8] reserved (0)`
Tags: Config=1, Rig=2, SeekerSeat=3, ShiftLog=4. A handler MUST check tag + owner before reading.

## Config (256 bytes)
| Off | Field | Type |
|---|---|---|
| 8 | governance | Address (Squads vault; proposes changes) |
| 40 | registrar | Address (Ed25519 key attesting hardware-backed P-256 keys) |
| 72 | crank_fee | u64 lamports reimbursed to the cranker per rig-round dug |
| 80 | executor_fee | u64 lamports — the Discretionary `fee` every rig's Automation MUST use (checked in `dig`) |
| 88 | bury_bps | u16 |
| 90 | paused | u8 (1 = `dig` disabled; circuit breaker) |
| 91 | executor_bump | u8 (canonical bump of `[b"executor"]`, stored at init; never taken from ix data) |
| 92 | _pad | [u8;4] |
| 96 | ore_layout_hash | [u8;32] sha256 of pinned ORE account sizes + discriminators |
| 128 | pending_exists | u8 |
| 129 | _pad | [u8;7] |
| 136 | pending_eta_slot | u64 (≥ proposal slot + 72h of slots) |
| 144 | pending_registrar | Address |
| 176 | pending_crank_fee | u64 |
| 184 | pending_bury_bps | u16 |
| 186 | pending_paused | u8 |
| 187 | _pad | [u8;69] → 256 |

No withdraw path. The executor PDA's lamports can flow only to: ORE (checkpoint fee), crank reimbursement, bury.

## Rig (384 bytes)
| Off | Field | Type | Notes |
|---|---|---|---|
| 8 | authority | Address | ORE Automation authority |
| 40 | p256_pubkey | [u8;33] | SEC1 compressed Keystore key |
| 73 | attestation_level | u8 | 0 none, 1 TEE, 2 StrongBox (registrar-attested) |
| 74 | tier | u8 | 0 guest, 1 seeker |
| 75 | state | u8 | 0 Idle, 1 Armed, 2 Down, 3 Cooling, 4 Broken, 5 Frozen |
| 76 | _pad | [u8;4] | |
| 80 | sgt_mint | Address | zero if guest |
| 112 | attestation_expiry_slot | u64 | |
| 120 | cap_week | u64 | lamports/week (wallet-signed) |
| 128 | cap_shift | u64 | lamports/shift |
| 136 | cap_round | u64 | lamports/round (all tiles) |
| 144 | cap_max_cost | u64 | wallet-signed ceiling (lamports per ORE) on the **pot-adjusted** cost `ema_ev` (see Gate) |
| 152 | caps_expiry_ts | i64 | unix seconds; caps invalid after |
| 160 | plan_max_ev_cost | u64 | ≤ cap_max_cost; client computes it at arm from the market price |
| 168 | plan_dig_lamports | u64 | SOL per dig (a concentrated chunk, e.g. 1_000_000); ≤ cap_round |
| 176 | plan_split_tiles | u8 | 0..=15 |
| 177 | plan_solo_tiles | u8 | 0..=10 (plan_split+plan_solo ≥ 1 unless focus-only) |
| 178 | plan_lease_rounds | u8 | 1..=3 |
| 179 | plan_flags | u8 | bit0 focus_only (never deploys) |
| 180 | _pad | [u8;4] | |
| 184 | plan_window_start_ts | i64 | |
| 192 | plan_window_end_ts | i64 | |
| 200 | shift_id | u64 | increments on arm |
| 208 | hb_counter | u64 | highest accepted P-256 counter (strictly increasing) |
| 216 | lease_from_round | u64 | first ORE round the current heartbeat lease covers |
| 224 | lease_to_round | u64 | last ORE round covered (≤ from + plan_lease_rounds − 1) |
| 232 | gap_count | u32 | rounds in shift with no valid lease |
| 236 | _pad | u32 | |
| 240 | spent_shift | u64 | |
| 248 | spent_week | u64 | |
| 256 | week_start_ts | i64 | rolls every 7 days |
| 264 | last_dug_round | u64 | idempotency: ≤ 1 dig per ORE round |
| 272 | shift_start_round | u64 | |
| 280 | shift_dark_rounds | u64 | rounds with a valid lease in this shift |
| 288 | shift_rounds_dug | u64 | |
| 296 | lifetime_dark_rounds | u64 | |
| 304 | lifetime_rounds_dug | u64 | |
| 312 | lifetime_lamports_deployed | u64 | |
| 320 | streak | u32 | |
| 324 | freezes_left | u8 | refills to 2 monthly |
| 325 | _pad | [u8;3] | |
| 328 | last_shift_day | i64 | unix day of last completed shift |
| 336 | reserved | [u8;48] | → 384 |

## SeekerSeat (128 bytes)
`8 sgt_mint | 40 rig | 72 authority | 104 member_number u64 | 112 verified_slot u64 | 120 reserved[8]`

## ShiftLog (128 bytes)
`8 rig | 40 shift_id u64 | 48 start_round u64 | 56 end_round u64 | 64 dark_rounds u64 | 72 rounds_dug u64 |
80 lamports_deployed u64 | 88 break_reason u8 (0 completed,1 pickup,2 screen_on,3 freeze,4 lease_lapse,5 budget,6 manual) |
89 mode u8 (0 night,1 day,2 focus_only) | 90 _pad[6] | 96 start_ts i64 | 104 end_ts i64 | 112 reserved[16]`

## Signed P-256 messages (verified via the secp256r1 precompile, same transaction)
**The signed message is the 32-byte `SHA-256(preimage)`.** Android signs those 32 bytes with
`SHA256withECDSA`; the precompile verifies ECDSA-P256 over SHA-256 of the same 32 bytes. The program
**recomputes the preimage from its own state plus the fields carried in the instruction data**
(`sol_sha256`) and requires byte-equality with the precompile entry's message. 32-byte messages fit
7 heartbeats per legacy/v0 tx and 27 per v1 (measured, `spikes/secp256r1`). Signatures are raw r‖s,
**low-S** (the crank or client normalizes; no private key needed). Pubkeys are 33-byte compressed.
Preimages:

```
HEARTBEAT (94 bytes):
  "HDv1"(4) | program_id(32) | rig(32) | kind u8 = 1 | counter u64 | shift_id u64 | round_id u64 | lease_rounds u8
BREAK / FREEZE (86 bytes):
  "HDv1"(4) | program_id(32) | rig(32) | kind u8 (2 BREAK, 3 FREEZE) | counter u64 | shift_id u64 | reason u8
PLAN (arm_shift without wallet; 113 bytes):
  "HDv1"(4) | program_id(32) | rig(32) | kind u8 = 4 | counter u64 | max_ev_cost u64 | dig_lamports u64 |
  split u8 | solo u8 | lease u8 | flags u8 | window_start i64 | window_end i64
```
Byte offsets inside each message are the running sums of the field sizes above (no padding).
Rules: `counter` strictly increases per rig across ALL kinds. `round_id` = ORE `Board.round_id` at signing.
A heartbeat grants a lease over rounds `[round_id, round_id + min(lease_rounds, plan_lease_rounds) − 1]`.

## Instructions (data[0] = tag)
| Tag | Name | Signers | Summary |
|---|---|---|---|
| 0 | `initialize_config` | upgrade authority | create Config; args: governance, registrar, crank_fee, recommended_executor_fee, bury_bps, ore_layout_hash |
| 1 | `register_rig` | authority | create Rig with `p256_pubkey[33]`; optional Ed25519 registrar attestation over `("HDreg"|program|authority|p256|level u8|expiry_slot u64)` via instructions sysvar sets attestation_level |
| 2 | `verify_seeker` | authority | sgt-verify(token acct, mint, authority) → create/re-point SeekerSeat, set rig.tier=1; if seat pointed to another rig, that rig (passed writable) drops to tier 0 |
| 3 | `set_caps` | authority | cap_week, cap_shift, cap_round, cap_max_cost, caps_expiry_ts; clamps current plan down |
| 4 | `rotate_key` | authority | new p256_pubkey (+ optional new attestation) |
| 5 | `arm_shift` | authority **or** P-256 PLAN | validates plan ≤ caps; shift_id += 1; state = Armed; resets shift counters |
| 6 | `dig` | cranker (any) | batched; see below |
| 7 | `record_heartbeats` | cranker (any) | same verification as dig, updates leases/dark rounds, no CPI (focus-only, Stack) |
| 8 | `break_shift` | authority **or** P-256 BREAK | state = Broken (or Cooling if reason allows re-arm) |
| 9 | `freeze_rig` | authority **or** P-256 FREEZE | state = Frozen (only authority can unfreeze) |
| 10 | `unfreeze_rig` | authority | Frozen → Idle |
| 11 | `end_shift` | authority, **or** anyone once `now > plan_window_end_ts` and lease expired | writes ShiftLog, streak/freeze accounting, state = Idle |
| 12 | `propose_config` | governance | sets pending_* with eta = now + 72h |
| 13 | `apply_config` | anyone after eta | applies pending |
| 14 | `close_rig` | authority | requires state Idle/Frozen; closes Rig (+SeekerSeat) to authority |

### `dig` (tag 6)
Data: `[6, n u8, per-rig entries...]`, entry (20 bytes) = `hb_ix u8` (index of the Secp256r1SigVerify
instruction carrying this rig's HEARTBEAT, or `0xFF` = reuse the rig's current lease), `hb_sig_index u8`,
`counter u64`, `round_id u64`, `lease_rounds u8`, `_pad u8`. With these plus `rig.shift_id` and the rig
address the program rebuilds the HEARTBEAT preimage, hashes it and compares to the precompile message.

**Cadence (economics, `ml/forecaster/RESULTS.md`):** phones heartbeat every round **off-chain** to the
crank; the crank submits an on-chain heartbeat **only when it digs**. Digs are concentrated chunks
(`plan_dig_lamports`, ≥ 0.001 SOL) on the least-crowded **split** tiles, only when the gate opens. Per-round
flat deploys are uneconomic at nightly budgets (fixed fees dominate).

Accounts (fixed order):
```
0 cranker (signer, writable)     4 ore_config (w)     8 ore_program       12.. per rig i (4 each):
1 config                         5 round (w)          9 entropy_var (w)        rig (w), authority (w),
2 executor PDA (w)               6 treasury (w)      10 entropy_program        automation (w), miner (w)
3 board (w)                      7 system_program    11 instructions sysvar
```
The ORE-facing accounts are passed once and reused for every rig's CPI. A rig appearing twice → `DuplicateRig`.

Per rig, in order; any failure **skips that rig** (logged via an event) rather than failing the whole batch,
except account-validation failures, which fail the transaction:
1. Validate PDAs/owners: rig (tag, owner), automation/miner re-derived from `rig.authority` under ORE,
   automation.executor == Executor PDA and strategy == Discretionary, authority matches.
2. Lease: if `hb_ix != 0xFF`, verify HEARTBEAT via p256-introspect (checked sysvar address; offsets in-instruction),
   `counter > rig.hb_counter`, `shift_id == rig.shift_id`, `round_id ≤ board.round_id`; set lease.
   Require `lease_from ≤ board.round_id ≤ lease_to` and state ∈ {Armed, Down} (Armed → Down).
3. Idempotency: `rig.last_dug_round != board.round_id`.
4. Gate (Motherlode-aware, integer, lamports per ORE):
   `ema_ev = ema · 6 · 500 · 10^11 / (5 · (500 · 10^11 + pot))` where `ema = Board.production_cost_ema`
   and `pot = Treasury.motherlode` (ORE base units, 11 decimals); dig iff `ema_ev ≤ min(plan_max_ev_cost, cap_max_cost)`.
   Caps not expired; within plan window; `config.paused == 0`.
5. Amount: `dig_lamports = min(plan_dig_lamports, cap_round, cap_shift − spent_shift, cap_week − spent_week)`;
   `k = split + solo`; `per_tile = min(dig_lamports / k, automation.amount)`; skip if 0 or if
   `automation.balance < per_tile·k + automation.fee` (ORE would close the automation and no-op).
   Require `automation.strategy == 2` and `automation.fee == config.executor_fee` (else `StrategyMismatch`).
   **Pre-flight every ORE abort condition** and skip the rig instead of failing the batch: board window
   (`start_slot ≤ slot < end_slot`), miner checkpointed (`miner.round_id == board.round_id` or
   `miner.checkpoint_id == miner.round_id`; ORE `assert!`-panics otherwise), Motherlode conditions on the
   Automation (ORE silently returns Ok without deploying if they fail).
6. Tiles: compute ORE's `distribution_mask(round_id)` on-chain (keccak), pick `split` least-crowded split tiles and
   `solo` least-crowded solo tiles by `round.deployed` (ties → lowest index).
7. CPI ORE `deploy(per_tile, mask)` signed by the Executor PDA (`authority` ALWAYS = `rig.authority`,
   never from ix data — if it were the Executor PDA, ORE would treat it as a manual deploy of the pool's
   own lamports). **Reload** automation/round after CPI and **detect no-ops/closures** by the automation
   balance delta and owner/length: count only the actual debit into spent_*; no crank reimbursement for a no-op.
   Assert `executor_after + CHECKPOINT_FEE ≥ executor_before` (ORE may pull the checkpoint fee from the signer).
   Update counters; `last_dug_round = board.round_id`.
8. Reimburse cranker `config.crank_fee` from the Executor PDA only after a real deploy, and only if the
   PDA stays ≥ rent-exempt + reserve.

The crank prepends ORE `checkpoint` instructions (permissionless) for miners whose `checkpoint_id != round_id`.
Always pass all 12 ORE-facing accounts (ORE `deploy` does `accounts.split_at(10)` and panics on fewer).

## Errors (`ProgramError::Custom`)
```
0 InvalidInstruction     1 CostGate           2 InvalidExecutor     3 InvalidOreAccount
4 InvalidAccountTag      5 Unauthorized       6 InvalidHeartbeat    7 StaleHeartbeat
8 LeaseExpired           9 AlreadyDugRound   10 CapsExpired         11 OutsideWindow
12 BudgetExhausted      13 RigNotArmed       14 RigFrozen           15 PlanExceedsCaps
16 InvalidSgt           17 SeatTaken         18 Paused              19 TimelockNotElapsed
20 MathOverflow         21 InvalidAttestation 22 DuplicateRig       23 StrategyMismatch
```

## Events (logged via `sol_log_data`, first byte = event tag)
`1 RigDug{rig, round_id, lamports, mask u32, ema_ev}` · `2 RigSkipped{rig, round_id, error u32}` ·
`3 ShiftArmed{rig, shift_id}` · `4 ShiftEnded{rig, shift_id, dark_rounds, rounds_dug, lamports, reason}` ·
`5 SeekerVerified{rig, sgt_mint, member_number}`
