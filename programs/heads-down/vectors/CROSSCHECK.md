# Cross-check: consumer assumptions vs the real `heads_down` program

Every row below was **executed**, not just read. Consumer vectors ran on the
same LiteSVM fork of live mainnet ORE that produced `vectors/*.json`. The
consumers' own decoders and builders (the indexer's TypeScript codec, the
crank's `hd.rs` / `gate.rs`, the registrar's `voucher.rs`) were run on the
golden bytes. None of the consumer directories was edited.

Reproduce everything with `bash programs/heads-down/vectors/crosscheck/run.sh`.

| Step | What runs |
|---|---|
| 1 | `tests/tests/crosscheck.rs`: the Android and crank vectors plus the event semantics, on the fork (`--ignored`) |
| 2 | `crosscheck/indexer_events.mjs`: `services/indexer/src/codec` on `events.json`, and on real transaction logs from step 1 |
| 3 | `crosscheck/crank_golden_xcheck.rs`: the crank's own code, built from a temporary copy of `crank/`, on the golden vectors |
| 4 | `crosscheck/registrar_voucher_vector.rs`: the registrar's own `voucher.rs`, built from a temporary copy, on `registrar.json` |

The snapshot below is from the consumers as of the base of this branch. Step 1
printed `MATCH 96 / MISMATCH 34 / INFO 18`.

The Android signing layer, the crank's message, `dig` and gate code, and the
registrar voucher are **byte-exact**. The mismatches are these:

* **Android instruction layouts** (the P-256 tail order, the attestation
  encoding, and the account order and count).
* **Crank semantics.** `RigDug.lamports`, the fee reserved inside the caps,
  and the skip codes.
* **Indexer names** for the new codes, reasons and events.
* **Registrar level 0**, which the program refuses.

---

## 1. `android/core/chain/src/test/resources/ix_vectors.json`

The Android authority `7xKXtg2C…` has no private key here, so this environment
ran with signature verification **off**. A guard asserts that the secp256r1 and
ed25519 precompiles still verify, and that a tampered signature fails.

### PDAs

All **MATCH**: `rig` `2gQpad6B…`, `config`, `executor`, ORE `automation` and
`miner`, `shift_log_7` and `seeker_seat`.

### Instruction data and accounts

| Vector | Data | Accounts | Executed as-is on the fork |
|---|---|---|---|
| `set_caps` | MATCH (41 B) | MATCH | success |
| `close_rig_guest` | MATCH (`0e`) | MATCH | success, `RigClosed` |
| `close_rig_seeker` | MATCH | MATCH (seat = `seeker_seat` PDA) | success, `RigClosed` |
| `secp256r1_heartbeat` | MATCH (145 B, identical) | none | used as-is in `record_heartbeats`: success |
| `ore_automate_heads_down` | MATCH (ORE AutomateV2, 66 B) | MATCH | success on live ORE (see the fee note) |
| `ore_revoke` | (ORE's layout) | | success on live ORE |
| `register_rig_guest` | **MISMATCH** | **MISMATCH** | `Custom(0)` InvalidInstruction |
| `register_rig_attested` | **MISMATCH** | **MISMATCH** | `Custom(0)` InvalidInstruction |
| `rotate_key` | **MISMATCH** | **MISMATCH** | `Custom(0)` InvalidInstruction |
| `arm_shift_wallet` | MATCH (38 B) | **MISMATCH** | `NotEnoughAccountKeys` |
| `arm_shift_p256` | **MISMATCH** (48 B, field order) | **MISMATCH** | `Custom(3)` InvalidOreAccount |
| `break_shift_wallet` | MATCH (`080006`) | **MISMATCH** | `InvalidAccountData` |
| `break_shift_p256` | **MISMATCH** (13 B, field order) | **MISMATCH** | `Custom(4)` InvalidAccountTag at ix 1 |
| `freeze_rig_wallet` | MATCH (`090003`) | **MISMATCH** | `InvalidAccountData` |
| `freeze_rig_p256` | **MISMATCH** (13 B, field order) | **MISMATCH** | `Custom(4)` InvalidAccountTag at ix 1 |
| `unfreeze_rig` | MATCH (`0a`) | **MISMATCH** | `InvalidAccountData` |
| `end_shift` | MATCH (`0b`) | **MISMATCH** (the ORE Board is missing) | `NotEnoughAccountKeys` |

The exact differences follow. In each pair, "consumer" is the Android vector
and "program" is what the program accepts. Offsets count from `data[0]`, the
tag.

* **`register_rig_guest`** (44 B vs 35 B).
  * consumer `01 | p256[33] | ff | 00 | 00000000 00000000`: `attestation_ix`
    0xFF means none, then `level`, then `expiry`.
  * program `01 | p256[33] | 00`: `has_attestation` = 0 and nothing after it.
  * Bytes 34..44 are `ff000000000000000000` vs `00`.
  * `rotate_key` has the same difference (tag `04`).
* **`register_rig_attested`** (44 B vs 46 B).
  * consumer `… | 00 01 c0ebed1a00000000`: `attestation_ix` 0, `level` 1,
    `expiry` 451,800,000.
  * program `… | 01 00 00 01 c0ebed1a00000000`: `has_attestation` = 1,
    `ed25519_ix`, `ed25519_sig_index`, `level`, `expiry_slot`.
  * So insert `has_attestation` (01) before the index and `ed25519_sig_index`
    after it.
* **`register_rig` / `rotate_key` accounts.**
  * consumer `authority(s,w), config, rig(w), system_program, instructions_sysvar`.
  * program `authority(s,w), rig(w), config, system_program`, plus
    `instructions_sysvar` only with an attestation.
  * `rotate_key`: consumer `authority(s), config, rig(w), sysvar`, program
    `authority(s), rig(w), config [, sysvar]`.
  * So `rig` and `config` are swapped.
* **`arm_shift_p256`** (48 B, bytes 38..48).
  * consumer `00 00 2900000000000000`: `precompile_ix`, `sig_index`, `counter`.
  * program `2900000000000000 00 00`: `counter` u64, `p256_ix`, `p256_sig_index`.
* **`break_shift_p256`** (13 B, bytes 3..13).
  * consumer `01 00 2c00000000000000`: `precompile_ix` 1, `sig_index` 0,
    `counter` 44.
  * program `2c00000000000000 01 00`.
* **`freeze_rig_p256`** (13 B, bytes 3..13).
  * consumer `00 02 2e00000000000000`.
  * program `2e00000000000000 00 02`.
* **P-256 path accounts** (`arm_shift_p256`, `break_shift_p256`, `freeze_rig_p256`).
  * consumer `payer(s,w), rig(w), instructions_sysvar`.
  * program `rig(w), authority (= rig.authority, NOT a signer), [ore_board for
    arm_shift], instructions_sysvar`.
  * The fee payer is not an instruction account.
* **Wallet path accounts** (`arm_shift_wallet`, `break_shift_wallet`,
  `freeze_rig_wallet`, `unfreeze_rig`).
  * consumer `authority(s), rig(w)`.
  * program `rig(w), authority(s)`, plus `ore_board` last for `arm_shift`.
* **`end_shift` accounts.**
  * consumer `caller(s,w), rig(w), shift_log(w), system_program`.
  * program `caller(s,w), rig(w), shift_log(w), ore_board, system_program`.

**Proof that only the layouts are wrong.** I rebuilt each Android P-256
message in the **program's** layout, with Android's own signatures, counters
and fields from `core/keys vectors.json`. Every one was accepted:

| Message | Result |
|---|---|
| `plan_steady` (counter 41) | `arm_shift`: `ShiftArmed` shift 7 |
| `heartbeat_lease_1` (42) | `record_heartbeats` with Android's own precompile bytes: `HeartbeatsRecorded` |
| `heartbeat_lease_3` (43) | `record_heartbeats`: `HeartbeatsRecorded` |
| `break_pickup` (44) | `break_shift`: rig becomes Cooling |
| `break_screen_on` (45) | `break_shift`: rig becomes Cooling |
| `freeze` (46) | `freeze_rig`: rig becomes Frozen |
| `plan_focus_only` (47) | `arm_shift`: shift 8 |

**Fee note.** `ore_automate_heads_down` uses `fee = 10000`. A rig digs only if
`automation.fee == Config.executor_fee`, read at offset 80 of the Config
account. Any other fee is a `StrategyMismatch` (23) skip. In the golden fork
`executor_fee` is 5000.

## 2. `android/core/keys/src/test/resources/vectors.json`

**All MATCH** (8 vectors: `heartbeat_lease_1`, `heartbeat_lease_3`,
`heartbeat_u64_max`, `break_pickup`, `break_screen_on`, `freeze`,
`plan_steady`, `plan_focus_only`). For each, these checks passed:

* The preimage equals `message::*_preimage` byte for byte (94 / 86 / 113 B).
* `message_hex` equals `SHA-256(preimage)`.
* `signature_rfc6979_hex` equals the program side's p256 RFC 6979 signature,
  and `signature_hex` equals its low-S form.
* The low-S signature verifies in the real secp256r1 precompile.
* Wherever the RFC 6979 form is high-S (`heartbeat_lease_3`, `break_pickup`,
  `freeze`), that form is **rejected** by the precompile, as required.
* The rig equals `rig_pda(7xKX…)`, and the key equals the RFC 6979 A.2.5 test key.

The crank's note A7 ("Android still signs the old 101-byte message") is
**stale**: Android now signs the 94-byte HDv1 digest.

## 3. `crank/test-fixtures/vectors/interface.json`

**All MATCH.**

* **Preimages and digests.** HEARTBEAT, BREAK and PLAN preimages and digests
  equal the program's builders.
* **Signature.** The `(r, s_low)` pair is verified by the precompile over the
  HEARTBEAT digest, and `(r, s_high)` is rejected.
* **Heartbeat executed.** `record_heartbeats` on a Rig account placed at the
  vector's rig bytes emitted `HeartbeatsRecorded`, adding 2 dark rounds.
  (`record_heartbeats` does not re-derive the rig PDA.)
* **Gate.** All 10 `ema_ev` rows equal the program's integer
  `logic::ema_ev`, including (u64::MAX, 10^13) → 18446744073709551615. That
  value is exact, not saturated.

The crank's own code agreed as well, run on the golden files in step 3:

* the preimages and digests for all 5 messages;
* the data and all metas of `dig_fresh_heartbeat`, `dig_reuse_lease` and
  `dig_batch_two_rigs`;
* `parse_event` for tags 1, 2 and 3;
* `gate::ema_ev` = 653,163,071, and the gate opens at exactly `ema_ev`
  (inclusive).

## 4. Crank `INTERFACE-NOTES.md` assumptions

| # | Assumption | Verdict | Program behaviour (executed) |
|---|---|---|---|
| A1 | Event bytes for RigDug / RigSkipped / ShiftArmed | MATCH | 61 / 45 / 41 B |
| A2 | `RigDug.lamports` = the Automation debit (tiles + fee) | **MISMATCH** | `RigDug.lamports` = 1,000,000 (SOL on squares only). The Automation debit was 1,005,000 = lamports + `executor_fee`, charged on the rig's first deploy in the round |
| A3 | An ORE no-op is reported as `RigSkipped(12)` | **MISMATCH** | `RigSkipped(29 OreNoOp)`: no spend counters move, but the debit is counted, and there is no reimbursement |
| A4 | `hb_ix` is the absolute top-level index | MATCH | The vectors put ComputeBudget at 0 and the precompile at 1 |
| A5 | A lease-reuse entry ignores its other fields; `_pad` is 0 | MATCH | The fields after `hb_ix = 0xFF` are ignored, and `_pad` is not checked |
| B8 | The gate is inclusive | MATCH | `ema_ev <= min(plan_max_ev_cost, cap_max_cost)` |
| B9 | `ema_ev` overflow closes the gate | MATCH (in effect) | The comparison is in u128, so a value above u64::MAX skips with CostGate. `RigDug.ema_ev` saturates at u64::MAX |
| B10 | Caps: valid iff `now <= caps_expiry_ts`. Window: `start <= now <= end` | MATCH | |
| B11 | `spent_week` counts as 0 once `now >= week_start + 7 d` | MATCH | It rolls at `dig`, `set_caps` and `arm_shift` |
| B12 | `amount = min(plan_dig, caps…) / (split + solo)` | **MISMATCH** | The fee is reserved inside the caps: `budget = min(plan_dig, min(cap_round, cap_shift − spent_shift, cap_week − spent_week) − fee)`. `k = popcount(mask)` counts only the squares actually chosen (it excludes squares the Miner already holds this round). Executed with `cap_round = plan_dig = 1,000,000` on 10 squares: 99,500 per square = **995,000**, not 1,000,000 |
| B13 | `lease_rounds = 0` is rejected | MATCH | InvalidHeartbeat (6) |
| B14 | `balance < need` → 12 | **MISMATCH** | **28** InsufficientAutomationBalance |
| B14 | Motherlode conditions fail → 1 | **MISMATCH** | **27** MotherlodeCondition |
| B14 | ORE round window closed → 11 | **MISMATCH** | **25** RoundNotActive |
| B14 | Miner not checkpointed → 3 | **MISMATCH** | **26** MinerNotCheckpointed |
| B14 | Automation authority wrong → 2 | **MISMATCH** | The transaction **fails** with 3 InvalidOreAccount (it is not a skip) |
| B14 | `per_tile == 0` → 12 | MATCH | |
| B14 | Miner missing → 3 | MATCH | |
| B14 | Executor wrong or Automation revoked → 2 | MATCH | |
| B15 | Executor reserve | compatible | The pre-flight needs `rent(0) + 10,000` only when the Miner's checkpoint reserve is spent (else it skips with 31). Reimbursement needs `rent(0) + 100,000 + crank_fee`. The crank's precondition is at least as strict |
| B16 | `automation.fee == executor_fee` | MATCH | |
| C18 | Checkpoint condition | MATCH | Pre-flight: `miner.round_id == board.round_id || miner.checkpoint_id == miner.round_id` (INTERFACE v1.1 uses this wording) |
| C19 | ORE Config bytes 168..232 are not pinned | MATCH | The program pins only the discriminator and length, plus `round_slots` at 160 inside the layout hash |
| C20 | 5 rigs per v0 + ALT, 11 per v1 | MATCH | Measured with the real program (`tests/tests/capacity.rs`) |
| C21 | A duplicate rig fails the transaction | MATCH | DuplicateRig (22) |
| – | `hd::error_name` knows codes 0..=23 | **MISMATCH** | Codes 25..31 print `Unknown` (the crank code was run in step 3) |

## 5. Indexer `INTERFACE-NOTES.md` and `src/codec`

| # | Assumption | Verdict | Program behaviour (executed) |
|---|---|---|---|
| N1 | Tags 1..5: layouts and lengths | MATCH | The indexer's own `decodeHdEvent` read every captured sample with every field equal |
| – | Tags 6..10 | **MISSING** | `decodeHdEvent` returns `Unknown` (no error) for RigRegistered 67 B, RigClosed 33 B, HeartbeatsRecorded 49 B, ShiftBroken 42 B and ShiftEndedV2 83 B |
| N3 | Append `start_round, end_round, mode` to ShiftEnded | done **as tag 10** | Appending would break the indexer: `decodeHdEvent` on an 83-byte tag 4 throws `BAD_LENGTH: event tag 4 must be 66 bytes, got 83` (executed). So tag 4 is unchanged and ShiftEndedV2 (tag 10) follows it in the same instruction |
| N3 | `RigRegistered{rig, authority, attestation_level}` | **differs** | It is `{rig, authority, tier u8, attestation_level u8}`, 67 B |
| N3 | `RigClosed{rig}` | MATCH | 33 B |
| N3 | `HeartbeatsRecorded{rig, round_id, lease_to}` | **differs** | It is `{rig, round_id = Board.round_id, dark_rounds_added u64}`, 49 B, emitted by `record_heartbeats` only |
| N3 | `ShiftBroken{rig, shift_id, reason}` | MATCH | 42 B. Emitted by `break_shift`, and by a `freeze_rig` that interrupts an open shift (reason 3) |
| N2 | `RigDug.lamports` = SOL on squares, excluding the fee | MATCH | 1,000,000, while the debit was 1,005,000 |
| N2 | `RigDug.mask` = the squares requested | MATCH | Squares the Miner already holds are excluded before the CPI, so requested equals credited |
| N4 | 24 OreWindowClosed, 25 MinerNotCheckpointed, 26 AutomationUnderfunded, 27 OreNoOp | **MISMATCH** | See the list below |
| – | `hdErrorName` knows 0..23 | **MISMATCH** | 25..31 and `0x2560000e` print `unknown(n)` (run in step 2) |
| – | `breakReasonName` knows 0..6 | **MISMATCH** | 7 and 8 print `unknown` |
| – | `parseProgramData` attribution | MATCH | Real logs from `register`+`arm`, `dig`, `break`, `end_shift`, `close`, `record` and a focus-only skip: every heads_down line was attributed, with 0 anomalies. ORE's `DeployEvent` is not a `Program data:` line |

The actual codes are:

* 24 InvalidRigState (a transaction error, never a skip)
* 25 RoundNotActive
* 26 MinerNotCheckpointed
* 27 MotherlodeCondition
* 28 InsufficientAutomationBalance
* 29 OreNoOp
* 30 FocusOnly
* 31 ExecutorUnderfunded

## 6. Registrar (`registrar/INTERFACE-NOTES.md` N2, N3, N4)

| # | Item | Verdict | Evidence |
|---|---|---|---|
| N2 | 111-byte `HDreg` preimage, Ed25519 over the raw bytes | MATCH | The registrar's own `Voucher::preimage` (step 4) is byte-identical to `registrar.json` |
| N3 | 223-byte Ed25519SigVerify, `IX_HEADER` `01003000ffff1000ffff70006f00ffff` | MATCH | `RegistrarKey::from_seed([5;32]).sign(..).instruction_data()` is byte-identical, including the signature |
| N3 | The program accepts that instruction | MATCH | `register_rig` with the voucher at ix 0 set `attestation_level` 2. `rotate_key` with a level-1 voucher at ix 1 set level 1 (`tests/tests/registrar_voucher.rs`) |
| N3 | Suggested on-chain check: fixed header, `len == 223` | not adopted, compatible | The program checks the general rule instead: every offsets record may point only at its own instruction, and the voucher is located by `(ed25519_ix, ed25519_sig_index)`. The registrar format passes |
| N4 | "Treat level 0 like no voucher" | **MISMATCH** | A level-0 voucher makes `register_rig` / `rotate_key` **fail** with InvalidAttestation (21). Level must be 1 or 2. A level-0 rig must register with `has_attestation = 0` |
| – | Rejections | MATCH | InvalidAttestation for all of these: level 3; `expiry_slot <= Clock.slot`; a voucher for another wallet; a voucher signed by a non-registrar key; a level in the data that differs from the signed level. A tampered message fails in the precompile |

## 7. Consumer-to-consumer finding (not the program contract)

The Android uplink and the crank intake do not speak the same protocol.

* **Heartbeat frames.** Android (`core/chain/uplink/HeartbeatJson.kt`) sends
  `{"rig","counter","shift_id","round_id","lease_rounds","sig"}`. The crank
  (`crank/src/intake.rs`) requires `{"type":"heartbeat", …, "sig64", "pubkey"?}`
  (a serde tag of `type`). Every Android heartbeat would be refused as
  malformed.
* **BREAK and FREEZE frames.** Android sends
  `{"kind":"break"|"freeze", …}`. The crank has no handler for these, so
  nothing lands a phone-signed BREAK or FREEZE on-chain.

## 8. v1.2 (SKR, additive): no consumer implements it yet

INTERFACE.md §11 adds instruction tags 15 to 27, accounts 5 to 9, events 11 to
23 and errors 32 to 48. Nothing in v1.1 changed, so every row above still
stands. `vectors/instructions.json` (16 new executed vectors) and
`vectors/events.json` (tags 11 to 23) are the reference for these, from the
same pinned fork.

| Consumer | State | What it needs for SKR |
|---|---|---|
| Indexer (`services/indexer/src/codec`) | **SAFE, NOT DECODED.** `indexer_events.mjs` shows tags 11 to 23 come back as `Unknown` with their exact length, so nothing breaks (tags 6 to 10 likewise) | Decoders for tags 11 to 23 by exact length, names for errors 32 to 48, and the StackCheckin `result` code |
| Crank | **NOT IMPLEMENTED** | Each round, for every seated rig: land the heartbeat, then `stack_checkin` (observe mode `hb_ix = 0xFF` after its `dig` or `record_heartbeats`, or verify mode with the precompile; at most 4 verified seats per 1,232-byte packet, 8 in observe mode); `settle_stack` after `end_round`; optionally the claims (permissionless) |
| Android | **NOT IMPLEMENTED** | Builders for tags 15 to 27 as in `instructions.json`, including the ATA `CreateIdempotent` companions for the table, bond and Bury vaults; a one-round-lease plan (`lease = 1`) for any shift seated at a table |

This section has no MISMATCH rows: there is no consumer code to compare yet.
