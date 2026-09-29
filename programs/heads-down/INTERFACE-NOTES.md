# INTERFACE-NOTES.md: superseded

**Superseded by [`INTERFACE.md`](INTERFACE.md) v1.1**, which was frozen from
the implementation. Its machine-checked form is [`vectors/`](vectors/).

This file used to list every place where the program extended, filled a gap
in, or deviated from INTERFACE v1. All of it is now part of the contract. The
full text remains in git history (the commit before this one).

| Old section | Now in INTERFACE.md v1.1 |
|---|---|
| 1. Errors 24..31; precise `RigSkipped.error` codes | §8 Errors; §6.2 (per-rig skip table) |
| 2. Rig `reserved[48]` extensions (`shift_open`, `break_reason`, ORE bumps, `shift_start_ts`) | §3.3 |
| 3. Plan flags (bit1 `DAY`) | §3.2 `plan_flags`; §3.5 `mode` |
| 4. Account lists and data layouts for every instruction | §5, and `vectors/instructions.json` (executed) |
| 5. `dig` semantics: fee inside the caps, tiles, check order, gate, post-CPI accounting, Executor invariant, reimbursement | §6.1, §6.2, §6.4, §6.5 |
| 6. Heartbeats and leases | §4.1, §6.3 |
| 7. State machine (Cooling, FREEZE, `unfreeze_rig` → Broken, `arm_shift`, `end_shift`, streak) | §6.7, §6.8, §5 |
| 8. Config and governance (layout hash, fee checks, timelock, immediate pause) | §3.1, §5 (tags 0, 12, 13), §6.6 |
| 9. Seats | §5 (tags 2, 14) |
| 10. Not implemented / capacity | §10, §9 |

These are new in v1.1 and were never in this file:

* the events RigRegistered (6), RigClosed (7), HeartbeatsRecorded (8),
  ShiftBroken (9) and ShiftEndedV2 (10);
* the BREAK reasons 7 `unplugged` and 8 `unlocked`.
