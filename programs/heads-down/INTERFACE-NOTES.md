# INTERFACE.md: implementation notes, extensions and deviations

`INTERFACE.md` is unchanged. This file lists every place where the program
(`programs/heads-down/program`) extends it, fills a gap it left open, or
deviates from it, so the lead can reconcile the contract. Items marked
**DEVIATION** change behaviour a consumer might have assumed. The others fill
gaps or extend the contract without changing anything it specified.

## 1. Errors

* **Extension codes 24 to 31** (`src/error.rs`). Codes 0 to 23 are exactly as specified.

  | Code | Name | Raised by |
  |---|---|---|
  | 24 | `InvalidRigState` | an instruction that is invalid in the rig's state: arm while a shift is open, break a Broken or Idle rig, unfreeze a rig that is not frozen, end a shift that is not open, close a live rig |
  | 25 | `RoundNotActive` | dig pre-flight: `!(board.start_slot <= slot < board.end_slot)` |
  | 26 | `MinerNotCheckpointed` | dig pre-flight: `miner.round_id != board.round_id && miner.checkpoint_id != miner.round_id` |
  | 27 | `MotherlodeCondition` | dig pre-flight: `pot > max_motherlode*ONE_ORE \|\| pot < min_motherlode*ONE_ORE` (ORE would return Ok without deploying) |
  | 28 | `InsufficientAutomationBalance` | dig pre-flight: `automation.balance < per_tile*k + fee_due` |
  | 29 | `OreNoOp` | dig post-CPI: ORE returned Ok but no SOL landed on a tile |
  | 30 | `FocusOnly` | dig: the plan has `FOCUS_ONLY` set |
  | 31 | `ExecutorUnderfunded` | dig pre-flight: `miner.checkpoint_fee == 0` and the Executor cannot pay `CHECKPOINT_FEE` and stay rent-exempt |

* **DEVIATION: `RigSkipped.error` carries the precise code.** Heartbeat verification
  failures are not collapsed into `InvalidHeartbeat (6)`. The event carries the
  `p256-introspect` code (`0x2560_00xx`, for example `MessageMismatch 0x2560000e` for a
  wrong round or shift_id, `PublicKeyMismatch 0x2560000d` for another rig's key, and
  `ForeignInstructionIndex 0x25600007`). Codes 6 and 7 are still used for
  future-round or zero-lease heartbeats and for stale counters. Transaction-level
  errors from the shared crates also keep their namespaces: `0x2560_00xx` and
  `sgt-verify`'s `0x5347_00xx`. A builtin `ProgramError` inside a skip maps to
  `u32::MAX - k`, which should not happen in practice.
* `InvalidSgt (16)` is used for a seat or mint mismatch (`close_rig` with the wrong
  seat, or a seat whose stored mint differs). The SGT checks themselves return
  `sgt-verify`'s own codes.

## 2. Rig layout: uses of `reserved[48]` @336 (no existing offset moved)

| Off | Field | Type | Why |
|---|---|---|---|
| 336 | `shift_open` | u8 | 1 from `arm_shift` to `end_shift`. A Frozen rig can be in or out of a shift; `end_shift` and `unfreeze_rig` need to know which |
| 337 | `break_reason` | u8 | reason recorded by BREAK / FREEZE, which `end_shift` writes into the ShiftLog |
| 338 | `ore_automation_bump` | u8 | canonical bump of ORE `["automation", authority]`, found once in `register_rig`. `dig` re-derives with one `sol_sha256` instead of a bump search. The bump never comes from instruction data |
| 339 | `ore_miner_bump` | u8 | the same for `["miner", authority]` |
| 340 | pad | [u8;4] | |
| 344 | `shift_start_ts` | i64 | unix time of arm, which becomes `ShiftLog.start_ts` |
| 352 | reserved | [u8;32] | zero |

All offsets are pinned by const assertions in `src/state.rs`.

## 3. Plan flags

`plan_flags` bit0 = `FOCUS_ONLY` (as specified). **Extension:** bit1 = `DAY`, which
becomes ShiftLog `mode` 1 (a focus-only plan is mode 2, otherwise mode 0 = night).
Any other bit makes `arm_shift` fail with `InvalidInstruction`.

## 4. Account lists and data layouts (INTERFACE specified these for `dig` only)

Data is shown after the tag byte. All integers are little-endian, and every
instruction requires its exact length.

| Tag | Accounts | Data |
|---|---|---|
| 0 `initialize_config` | `[s,w]` upgrade authority (payer), `[w]` Config, `[]` this program's ProgramData, `[]` System | `governance[32] registrar[32] crank_fee u64 executor_fee u64 bury_bps u16 ore_layout_hash[32]` (114 B) |
| 1 `register_rig` | `[s,w]` authority (payer), `[w]` Rig PDA, `[]` Config, `[]` System, `[]` Instructions sysvar (only with an attestation) | `p256[33] has_att u8` and, if 1, `ed_ix u8 ed_sig u8 level u8 expiry_slot u64` (35 / 46 B) |
| 2 `verify_seeker` | `[s,w]` authority, `[w]` Rig, `[w]` SeekerSeat PDA, `[]` SGT token account, `[]` SGT mint, `[]` System, `[w]` previous rig (only when re-pointing) | empty |
| 3 `set_caps` | `[s]` authority, `[w]` Rig | `cap_week u64 cap_shift u64 cap_round u64 cap_max_cost u64 caps_expiry_ts i64` (40 B) |
| 4 `rotate_key` | `[s]` authority, `[w]` Rig, `[]` Config, `[]` Instructions sysvar (with an attestation) | same as `register_rig` |
| 5 `arm_shift` | `[w]` Rig, `[s if mode 0]` authority (must equal `rig.authority` in both modes), `[]` ORE Board, `[]` Instructions sysvar (mode 1) | `mode u8 max_ev_cost u64 dig_lamports u64 split u8 solo u8 lease u8 flags u8 window_start i64 window_end i64`, then for mode 1 `counter u64 p256_ix u8 p256_sig u8` (37 / 47 B) |
| 6 `dig` | as INTERFACE | as INTERFACE, `1 <= n <= 32`, exactly `12 + 4n` accounts |
| 7 `record_heartbeats` | `[]` ORE Board, `[]` Instructions sysvar, `[w]` rig_0..rig_{n-1} | `n u8` + n × the 20-byte dig entry |
| 8 `break_shift` / 9 `freeze_rig` | `[w]` Rig, `[s if mode 0]` authority, `[]` Instructions sysvar (mode 1) | `mode u8 reason u8`, then for mode 1 `counter u64 p256_ix u8 p256_sig u8` (2 / 12 B) |
| 10 `unfreeze_rig` | `[w]` Rig, `[s]` authority | empty |
| 11 `end_shift` | `[s,w]` caller (pays ShiftLog rent), `[w]` Rig, `[w]` ShiftLog PDA, `[]` ORE Board, `[]` System | empty |
| 12 `propose_config` | `[s]` governance, `[w]` Config | `registrar[32] crank_fee u64 bury_bps u16 paused u8` (43 B) |
| 13 `apply_config` | `[w]` Config | empty |
| 14 `close_rig` | `[s,w]` authority (receives rent), `[w]` Rig, `[w]` SeekerSeat (required when `tier == 1`) | empty |

The registrar attestation is located by an explicit `(ed_ix, ed_sig)` pair, the same
way the P-256 entries are. Every record of that Ed25519 instruction must reference only
itself (`0xFFFF` or its own index). Level must be 1 or 2, and `expiry_slot` must be
greater than the current slot. Any failure is `InvalidAttestation`.

## 5. `dig` semantics that INTERFACE left open

* **DEVIATION: the fee is reserved inside the caps.** `spent_*` count the whole debit
  from the Automation (tiles plus the fixed Automation fee). To keep that debit within
  every wallet-signed cap:
  `dig_lamports = min(plan_dig_lamports, min(cap_round, cap_shift − spent_shift, cap_week − spent_week) − automation.fee)`
  (saturating). INTERFACE's formula omitted the fee. After the CPI the program asserts
  `debit <= cap_round`, `spent_shift + debit <= cap_shift` and
  `spent_week + debit <= cap_week`, and fails the transaction otherwise.
* **Tiles.** Squares the Miner already holds in this round are excluded, because ORE
  would skip them. `k` is the number of tiles actually selected and
  `per_tile = min(dig_lamports / k, automation.amount)`.
* **Order of checks.**
  1. Account validation, which fails the transaction.
  2. User-controlled Automation state, which is a skip: closed or revoked →
     `InvalidExecutor`; executor ≠ PDA → `InvalidExecutor`; strategy or fee →
     `StrategyMismatch`; Miner missing → `InvalidOreAccount`.
  3. Rig state.
  4. Heartbeat.
  5. Lease.
  6. `AlreadyDugRound`.
  7. Caps expiry, window, focus-only and the gate.
  8. Amount and balance.
  9. ORE pre-flight.

  A heartbeat that verifies is consumed (its counter and lease persist) even if a
  later step skips the rig.
* **Gate.** `ema_ev <= min(plan_max_ev_cost, cap_max_cost)` (`<=`, as INTERFACE says;
  `ml/forecaster/RESULTS.md` writes `<`). It is computed once per transaction.
  `Board.production_cost_ema == 0`, or `end_slot <= start_slot` with
  `end_slot != u64::MAX`, fails the transaction (`InvalidOreAccount`).
* **The Round is re-derived** once per transaction with `find_program_address(["round", board.round_id])`
  and must have `id == board.round_id`. `deployed[25]` and the Board's slots are
  re-read before every rig.
* **Post-CPI accounting.**
  * `fee_received = executor_after + checkpoint_paid − executor_before`.
  * `tiles = Δ sum(miner.deployed)`, which is 0 when ORE left the Miner untouched,
    for example on its Motherlode no-op.
  * If the Automation survives: `balance_before − balance_after` must equal
    `tiles + fee_received`, or the transaction fails with `InvalidOreAccount`.
  * If ORE closed it (owner or length changed): `debit = tiles + fee_received`.
  * `tiles == 0` → skip with `OreNoOp`. Any debit is still counted, there is no
    reimbursement, and no dig counters move.
* **Executor invariant (stricter than INTERFACE).**
  * `executor_after + CHECKPOINT_FEE >= executor_before` always.
  * If the Miner still had its checkpoint reserve before the CPI, then
    `executor_after >= executor_before`.
  * The Executor must stay System-owned and data-less.
  * Any violation fails the transaction with `InvalidExecutor`.
* **Reimbursement.** It is paid only after a real deploy, and only if the Executor stays
  at or above `rent_exempt(0) + EXECUTOR_RESERVE (10 × CHECKPOINT_FEE = 100,000) + crank_fee`.
  Otherwise it is silently skipped and the dig still succeeds.
* **`RigDug.lamports`** is the SOL placed on tiles, without the fee.
  `ShiftEnded.lamports` and `ShiftLog.lamports_deployed` are `spent_shift`, which
  includes the fees. `RigDug.ema_ev` saturates at `u64::MAX`.
* **`authority` must be writable** because ORE requires it. The cranker must be a signer
  and writable, since it receives the reimbursement.

## 6. Heartbeats and leases

* A heartbeat with `round_id > board.round_id`, or with `lease_rounds == 0`, is
  `InvalidHeartbeat`.
* The effective lease is `min(lease_rounds, plan_lease_rounds)`, while the preimage
  binds the signed `lease_rounds`.
* **Leases only move forward.** A verified heartbeat whose lease ends at or before the
  current `lease_to_round` consumes its counter but leaves the lease unchanged.
  `lease_to_round == 0` means no lease in this shift, and `arm_shift` resets it.
* **Dark rounds** are counted when a lease is granted: rounds newly covered, counted
  from `shift_start_round`. Skipped-over rounds are added to `gap_count`.
  `end_shift` subtracts leased rounds after `end_round` and adds trailing gaps.

## 7. State machine

* **Cooling.** BREAK reason 1 (pickup) or 2 (screen_on) → Cooling. A fresh heartbeat
  (counter > the BREAK's) moves the rig back to Down, in `dig` and in
  `record_heartbeats`. Reusing a lease (`0xFF`) is refused while Cooling. Reasons 4, 5
  and 6 → Broken. Reasons 0 and 3 are invalid for BREAK. BREAK is allowed from Armed,
  Down or Cooling.
* **FREEZE.** It is allowed from any state, and freezing a frozen rig is idempotent,
  although a P-256 FREEZE still consumes its counter. If a shift is open, it records
  `break_reason = 3`. The signed reason byte is bound into the preimage.
* **DEVIATION: `unfreeze_rig`.** Frozen → Idle only when no shift is open. Otherwise
  Frozen → Broken, so that `end_shift` can seal the open shift with reason `freeze`
  instead of losing it.
* **`arm_shift`** requires Idle; from Frozen it fails with `RigFrozen`. The authority
  is checked before anything else. The caps must be unexpired
  (`now <= caps_expiry_ts`), otherwise `CapsExpired`. The plan must satisfy:
  * `lease` in 1..=3, `split <= 15`, `solo <= 10`;
  * unless focus-only, `split + solo >= 1` and `dig_lamports > 0`;
  * `window_start < window_end` and `now <= window_end`;
  * `max_ev_cost <= cap_max_cost` and `dig_lamports <= cap_round`, else `PlanExceedsCaps`.
* **`end_shift`** works from any open-shift state, including Frozen, in which case the
  rig stays Frozen. The reason is:
  * the stored reason for Cooling or Broken;
  * `freeze` for Frozen;
  * for Armed or Down: `lease_lapse` if no dark round, `manual` if the authority ends
    it inside the window, otherwise `completed`.

  The permissionless path needs `now > plan_window_end_ts` and
  `lease_to_round < board.round_id`. The caller pays the ShiftLog rent.
* **Streak.**
  * A shift qualifies if `reason == completed` and `dark_rounds >= 1`.
  * Days are UTC unix days of `end_ts`. Freezes refill to 2 when `day / 30` changes.
  * Next day → +1. Missed `m` days with `m <= freezes` → +1 and `freezes -= m`.
    Otherwise the streak restarts at 1. Same day → unchanged.
  * A non-qualifying shift neither extends nor breaks the streak.

## 8. Config and governance

* **Layout hash check.** `initialize_config` requires
  `ore_layout_hash == sha256(ore::LAYOUT_PREIMAGE)`: the pinned ORE discriminators,
  sizes and every field offset read (see `src/ore.rs`). Anything else fails with
  `InvalidOreAccount`.
* **Fee checks.** `crank_fee <= executor_fee` is enforced at init, propose and apply,
  so reimbursement never exceeds what a dig pays in. `bury_bps <= 10,000`.
  `executor_fee` is immutable because it is not in the pending set, as INTERFACE has it.
* **Timelock length.** `TIMELOCK_SLOTS = 864,000`, which is 72 h at 300 ms slots and
  about 96 h at the nominal 400 ms. That guarantees at least 72 h of wall time unless
  slots get faster.
* **Replacing a proposal.** A new proposal replaces the pending one and restarts the
  clock.
* **DEVIATION: pausing is immediate.** `propose_config` with `paused = 1` also sets
  `config.paused = 1` at once, as a circuit breaker, because pausing only reduces risk.
  Un-pausing and every other field wait for `apply_config`.

## 9. Seats

* `verify_seeker`: if the seat points at another rig, that rig must be supplied (it is
  checked against `seat.rig`), otherwise `SeatTaken`. It is downgraded only if it still
  claims this mint (`tier == 1 && sgt_mint == mint`). It may already be closed.
* `close_rig` for a Seeker-tier rig needs the seat account. The seat is closed only if
  it still points at this rig.

## 10. Not implemented in v1 core (open)

* The THREAT_MODEL's recommended P-256 key-uniqueness marker (§6.8).
* ORE's Level-2 ProgramData version pin, which ORE.md drops for an immutable v1.
* `bury_bps` is stored but no bury path exists yet.
* ShiftLog closing after 30 days.
* INTERFACE's "27 heartbeats per v1" is a precompile-only figure. A full `dig` fits
  **11 rigs** per v1 transaction: the 4096-byte size binds before the 64-address limit.
  It fits 5 per v0 transaction with an ALT, or 2 without one (measured, see README).
