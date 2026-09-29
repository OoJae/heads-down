# INTERFACE notes from the crank workstream

The crank is built strictly against `programs/heads-down/INTERFACE.md` (commit `942d0d7`). These are the
places where the contract is silent, ambiguous or contradicted by another doc or by mainnet, what the crank
does today, and what the lead should pin. Nothing here edits INTERFACE.md.

Each item says **what the crank assumes** so a program that differs can be found quickly: the fork suite
(`cargo test --features fork`) and the end-to-end run (`--features e2e`) exercise every one of them against the
mock program in `test-fixtures/mock-heads-down`, and `--features real-program` runs the same suite against the
real build.

## A. Wire formats the contract does not fix

1. **Event encoding.** INTERFACE lists `RigDug{rig, round_id, lamports, mask u32, ema_ev}` and
   `RigSkipped{rig, round_id, error u32}` but not their bytes. The crank assumes **one** `sol_log_data` field:
   the tag byte, then the fields packed little-endian in declaration order, no padding:
   - `RigDug` = 61 bytes: `1 | rig[32] | round_id u64 | lamports u64 | mask u32 | ema_ev u64`
   - `RigSkipped` = 45 bytes: `2 | rig[32] | round_id u64 | error u32`
   - `ShiftArmed` = 41 bytes: `3 | rig[32] | shift_id u64`

   It only accepts `Program data:` lines emitted while `heads_down` is the innermost program
   (`hd::events_from_logs`), so ORE's or the entropy program's logs cannot spoof an event.
   **Please pin this layout in INTERFACE.md.**
2. **`RigDug.lamports`** is assumed to be the actual Automation debit (`per_tile * k + fee`), i.e. what went
   into `spent_*`. The fork test asserts it equals the planner's `expected_debit`.
3. **ORE no-op after the CPI** (balance unchanged): the contract says "no crank reimbursement for a no-op" but
   not which event is emitted. The mock emits `RigSkipped(BudgetExhausted)`. The crank treats *any*
   `RigSkipped` as final for that (rig, round) and never retries it.
4. **`hb_ix`** is taken to be the **absolute top-level instruction index** in the transaction (u8), counting
   ComputeBudget and ORE checkpoint instructions that precede it. `0xFF` is reserved, so the crank refuses to
   build a transaction where a precompile would land at index 255.
5. **Lease-reuse entries (`hb_ix = 0xFF`).** The crank writes `hb_sig_index = 0, counter = 0, round_id = 0,
   lease_rounds = 0`. The program should ignore those fields. The crank always writes `_pad = 0`; the program
   may require it.
6. **Heartbeat intake protocol (new contract for Android).** JSON text frames over WebSocket `GET /ws`:
   ```json
   {"type":"heartbeat","rig":"<base58 Rig PDA>","counter":7,"shift_id":3,"round_id":422601,
    "lease_rounds":2,"sig64":"<64-byte r||s as hex (128 chars) or standard base64>","pubkey":"<optional 33-byte key, hex or base64>"}
   ```
   Reply: `{"type":"ack","rig":..,"counter":..,"status":"accepted"}` or `"status":"rejected","reason":"<code>"`.
   `{"type":"status"}` returns the crank's `round_id`, `start_slot`, `end_slot`, `slot`, `ema_ev`. Unknown
   fields are refused; messages are capped at 2 KiB. High-S signatures are accepted and normalized.
   Reason codes: `malformed, bad_encoding, bad_lease, round_in_future, expired, stale_counter, unknown_rig,
   shift_mismatch, not_armed, pubkey_mismatch, bad_signature, rate_limited_ip, rate_limited_rig, busy,
   too_large, unavailable`.
7. **Android still signs the OLD 101-byte raw message** (`android/core/keys/HeartbeatMessage.kt`:
   `HDv1 | program | rig | round | counter | state | shift_id | lease_end`, signed raw). The crank verifies the
   INTERFACE format: the **94-byte HEARTBEAT preimage**, and the phone signs `SHA-256(preimage)` (32 bytes)
   with `SHA256withECDSA`. Old-format signatures are rejected as `bad_signature`. Shared vectors for all three
   codebases: `crank/test-fixtures/vectors/interface.json` (made by an independent Python + OpenSSL script).

## B. Semantics the crank had to guess (it mirrors them in the planner)

8. **Gate inequality.** INTERFACE: `ema_ev ≤ min(plan_max_ev_cost, cap_max_cost)`.
   `ml/forecaster/RESULTS.md` §6: `ema_ev < plan.max_ev_cost`. The crank follows INTERFACE (inclusive);
   the fork test asserts the gate opens at exactly `ema_ev`. **Contradiction to resolve.**
9. **`ema_ev` overflow.** `ema · 6 · 500 · 10^11` always fits u128, but the quotient exceeds u64 when
   `ema > ~u64::MAX · 5/6` with a small pot. The crank treats that as "gate closed" (`gate::ema_ev` returns
   `None`). The program should skip the rig (CostGate or MathOverflow); either way nothing digs.
10. **Caps expiry and plan window inclusivity.** Assumed valid iff `now ≤ caps_expiry_ts` and
    `plan_window_start_ts ≤ now ≤ plan_window_end_ts` (cluster clock). The crank applies a configurable
    5-second margin on the safe side because it only estimates cluster time.
11. **Week rollover.** `week_start_ts` "rolls every 7 days" but the contract does not say when `spent_week`
    resets. The crank assumes that when `now ≥ week_start_ts + 7 days`, `spent_week` counts as 0 for the
    amount computation (the mock resets it on the dig).
12. **`k = plan_split_tiles + plan_solo_tiles`** is used literally for `per_tile` and the balance check, even
    though only 15 split and 10 solo squares exist. `arm_shift` should reject `split > 15` or `solo > 10`,
    otherwise the mask has fewer squares than `k` and the balance check over-reserves.
13. **`lease_rounds = 0`** in a HEARTBEAT grants an empty lease. The crank refuses it at intake; the program
    should reject it (`InvalidHeartbeat`).
14. **Skip codes not named by the contract.** The mock uses: `per_tile == 0` or `balance < per_tile·k + fee`
    → `BudgetExhausted (12)`; Automation Motherlode conditions fail → `CostGate (1)`; ORE round window closed
    → `OutsideWindow (11)`; Miner missing or not checkpointed → `InvalidOreAccount (3)`; Automation executor
    or authority wrong → `InvalidExecutor (2)`. The crank uses codes only as metric labels.
15. **Executor reserve.** Step 8 says reimburse "only if the PDA stays ≥ rent-exempt + reserve" without
    fixing the reserve. The planner requires `executor ≥ 890,880 (rent-exempt, 0 bytes) + 10,000
    (CHECKPOINT_FEE) + dig.executor_reserve_lamports` before digging at all (ORE F7).
16. **Which Config field the Automation fee must equal.** INTERFACE: `automation.fee == config.executor_fee`
    and `crank_fee` is the reimbursement. `docs/ORE.md` §3/§4 and THREAT_MODEL row 3 say
    `automation.fee == Config.crank_fee`. The crank follows INTERFACE.
17. **Heartbeat `round_id` vs. intermission.** A phone that signs during ORE's intermission (Board still at
    round r) with `lease_rounds = 1` produces a lease that only covers r, which has ended. Recommend phones
    sign `lease_rounds = plan_lease_rounds` (≥ 2). The intake tolerates `round_id = board + 1` (clock skew),
    but the crank only puts a heartbeat on-chain once `round_id ≤ Board.round_id`.

## C. Facts found while testing against live ORE (for the program and docs)

18. **A stale checkpoint aborts the whole batch.** ORE `checkpoint` (`checkpoint.rs:38-43`) re-derives a
    *closed* round's PDA from `miner.round_id`; if the instruction names another round it fails with
    `InvalidSeeds` instead of no-oping. Reproduced in `tests/fork.rs` (after a dig moved the miner into round
    r, resending the old `checkpoint(r-5)` failed the transaction). The crank rebuilds every attempt from
    fresh Miner state and never resends old instructions. INTERFACE's "prepends ORE checkpoint instructions
    for miners whose `checkpoint_id != round_id`" should read: *for miners with
    `miner.round_id != board.round_id && miner.checkpoint_id != miner.round_id`, naming `miner.round_id` as
    read in the same slot range*.
19. **ORE Config bytes 168..232 are not the entropy addresses.** The pinned `config.rs` declares
    `entropy_var_address @168` and `entropy_program_id @200`, but the live account holds `64` and zeros
    there (mainnet, slot 451,741,057). `deploy` uses the compile-time `VAR_ADDRESS` and `entropy_api::ID`.
    Do not pin those fields; the crank pins only size 232, discriminator 101 and `round_slots @160`.
20. **Measured on the mock (for sizing `crank_fee`)**: one v0 transaction with a lookup table carries 5
    fresh heartbeats (1232-byte limit) or 12 lease reuses (64 account locks); v1 carries 11 (64 addresses).
    Compute with the mock program: ~42-65k CU per rig. The real program should be cheaper (the mock re-derives
    4 PDAs per rig with `find_program_address`).
21. **Duplicate rigs** fail the transaction (`DuplicateRig`), so the crank deduplicates before building.
22. **Checkpoint sweep and the Executor float.** ORE pays a Miner's 10,000-lamport checkpoint reserve to
    whoever checkpoints in the last 12 h before the round expires, and the Executor PDA must then refill it on
    the next deploy. The crank sweeps idle miners after 400 rounds (~8.7 h), before that window, so the pool
    never pays for it. Only matters for the Executor-float accounting in ECONOMICS.md.
