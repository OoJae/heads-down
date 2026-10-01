# INTERFACE notes from the crank workstream (SUPERSEDED)

> **Superseded by [`programs/heads-down/INTERFACE.md`](../programs/heads-down/INTERFACE.md) v1.1** and its
> machine-checked vectors (`programs/heads-down/vectors/`). Where these notes and v1.1 differ, v1.1 governs.
> The crank now follows v1.1 (see the table below and `crank/README.md`), and `crank/tests/golden.rs` checks
> every builder and decoder against the vectors byte for byte. The pre-v1.1 notes are kept underneath for
> history only.

| # | Pre-v1.1 assumption | v1.1 outcome | Crank today |
|---|---|---|---|
| A1 | Event bytes for RigDug / RigSkipped / ShiftArmed | pinned (§7), plus tags 4..10 | decodes tags 1..=10 by exact length; drops tag 4 when tag 10 follows in the same instruction |
| A2 | `RigDug.lamports` = the Automation debit | **changed**: SOL on squares only; the debit adds `executor_fee` on the rig's first deploy of the round | planner reports `squares_lamports`, `fee_due`, `expected_debit`; metrics `hd_crank_squares_lamports_total` and `hd_crank_automation_debit_lamports_total` |
| A3 | ORE no-op → `RigSkipped(12)` | **changed**: `RigSkipped(29 OreNoOp)` | named in metrics; any RigSkipped is final for the round |
| A4 | `hb_ix` = absolute top-level index | confirmed | unchanged |
| A5 | lease-reuse entry fields ignored, `_pad` 0 | confirmed | unchanged |
| A6 | intake protocol (`status` acks, strict fields) | **replaced by contract A** (wave 3b): `ok` / `reason` acks, numbers or decimal strings, unknown fields ignored, BREAK / FREEZE frames | implemented (`src/intake.rs`) |
| A7 | Android signs the old 101-byte message | stale: Android signs the 94-byte HDv1 digest | the crank verifies only the v1.1 preimages |
| B8 | gate inclusive | confirmed | unchanged |
| B9 | `ema_ev` overflow closes the gate | confirmed (u128 compare) | unchanged |
| B10 | caps / window inclusivity | confirmed | unchanged (+ clock margin) |
| B11 | week rollover | confirmed; also rolls when `week_start_ts == 0` | mirrors `roll_week` exactly |
| B12 | `amount = min(plan_dig, caps) / (split + solo)` | **changed**: fee reserved inside every cap; `k = popcount(mask)` excluding squares the Miner holds | mirrors `dig_budget` and `select_tiles` exactly |
| B13 | `lease_rounds = 0` rejected | confirmed (InvalidHeartbeat) | intake answers `lease_invalid` |
| B14 | mock skip codes | **changed**: 25 RoundNotActive, 26 MinerNotCheckpointed, 27 MotherlodeCondition, 28 InsufficientAutomationBalance, 29 OreNoOp, 30 FocusOnly, 31 ExecutorUnderfunded; Automation authority mismatch fails the tx | `error_name` knows 0..=31 and the p256 codes; the mock emits the real codes |
| B15 | executor reserve | reimbursement needs `rent(0) + 100,000 + crank_fee` | planner precondition stays at least as strict |
| B16 | `automation.fee == executor_fee` | confirmed | unchanged |
| B17 | heartbeat round vs intermission | unchanged advice: phones sign `lease_rounds = plan_lease_rounds` | unchanged |
| C18 | stale checkpoint aborts a batch | confirmed | every attempt re-planned from fresh Miner state |
| C19 | ORE Config bytes 168..232 not pinned | confirmed | unchanged |
| C20 | 5 per v0 + ALT, 11 per v1 | confirmed with the real program (32-35k CU per rig) | README cost table re-measured |
| C21 | duplicate rig fails the tx | confirmed | deduplicated before building |
| C22 | checkpoint sweep and the Executor float | unchanged | unchanged |
| new | Cooling digs only with a fresh heartbeat | §6.7 | planner never reuses a Cooling rig's lease |
| new | `record_heartbeats`, `break_shift` / `freeze_rig` P-256 path, `end_shift` layouts | §5 + vectors | built and landed by the crank (golden + fork suite) |

---

## Historical notes (pre-v1.1, superseded)

The crank was built strictly against `programs/heads-down/INTERFACE.md` (commit `942d0d7`). These were the
places where the contract was silent, ambiguous or contradicted by another doc or by mainnet, what the crank
did then, and what the lead was asked to pin. Nothing here edited INTERFACE.md.

### A. Wire formats the contract did not fix

1. **Event encoding.** INTERFACE listed `RigDug{rig, round_id, lamports, mask u32, ema_ev}` and
   `RigSkipped{rig, round_id, error u32}` but not their bytes. The crank assumed **one** `sol_log_data` field:
   the tag byte, then the fields packed little-endian in declaration order, no padding:
   - `RigDug` = 61 bytes: `1 | rig[32] | round_id u64 | lamports u64 | mask u32 | ema_ev u64`
   - `RigSkipped` = 45 bytes: `2 | rig[32] | round_id u64 | error u32`
   - `ShiftArmed` = 41 bytes: `3 | rig[32] | shift_id u64`

   It only accepted `Program data:` lines emitted while `heads_down` is the innermost program
   (`hd::events_from_logs`), so ORE's or the entropy program's logs cannot spoof an event.
2. **`RigDug.lamports`** was assumed to be the actual Automation debit (`per_tile * k + fee`), i.e. what went
   into `spent_*`. *(v1.1: squares only.)*
3. **ORE no-op after the CPI** (balance unchanged): the mock emitted `RigSkipped(BudgetExhausted)`. The crank
   treats *any* `RigSkipped` as final for that (rig, round) and never retries it. *(v1.1: 29 OreNoOp.)*
4. **`hb_ix`** is the **absolute top-level instruction index** in the transaction (u8), counting ComputeBudget
   and ORE checkpoint instructions that precede it. `0xFF` is reserved, so the crank refuses to build a
   transaction where a precompile would land at index 255.
5. **Lease-reuse entries (`hb_ix = 0xFF`).** The crank writes `hb_sig_index = 0, counter = 0, round_id = 0,
   lease_rounds = 0` and `_pad = 0`.
6. **Heartbeat intake protocol.** JSON text frames over WebSocket `GET /ws` with `"status":"accepted"` acks and
   strict fields. *(Replaced by contract A.)*
7. **Android signed the OLD 101-byte raw message.** *(Stale: Android signs the 94-byte HDv1 digest.)*

### B. Semantics the crank had to guess

8. **Gate inequality**: inclusive, as INTERFACE said.
9. **`ema_ev` overflow** closes the gate.
10. **Caps expiry and plan window inclusivity**: valid iff `now ≤ caps_expiry_ts` and
    `plan_window_start_ts ≤ now ≤ plan_window_end_ts`, with a 5-second safety margin.
11. **Week rollover**: `spent_week` counts as 0 once `now ≥ week_start_ts + 7 days`.
12. **`k = plan_split_tiles + plan_solo_tiles`** was used literally. *(v1.1: `k = popcount(mask)`.)*
13. **`lease_rounds = 0`** refused at intake.
14. **Skip codes not named by the contract** were guessed by the mock. *(v1.1 §8 names them.)*
15. **Executor reserve**: the planner required `executor ≥ 890,880 + 10,000 + executor_reserve_lamports`.
16. **Which Config field the Automation fee must equal**: `executor_fee`.
17. **Heartbeat `round_id` vs. intermission**: phones should sign `lease_rounds = plan_lease_rounds` (≥ 2).

### C. Facts found while testing against live ORE

18. **A stale checkpoint aborts the whole batch** (`checkpoint.rs:38-43` re-derives a closed round's PDA from
    `miner.round_id`). Every attempt is rebuilt from fresh Miner state.
19. **ORE Config bytes 168..232 are not the entropy addresses**; only size 232, discriminator 101 and
    `round_slots @160` are pinned.
20. **Measured on the mock**: 5 fresh heartbeats per v0 + ALT tx, 12 lease reuses, 11 per v1.
21. **Duplicate rigs** fail the transaction (`DuplicateRig`).
22. **Checkpoint sweep and the Executor float**: idle miners are swept after 400 rounds, before ORE's 12-hour
    bot window.
