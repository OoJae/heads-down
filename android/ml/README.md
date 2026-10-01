# `:ml`: Foreman on-device models

Package `xyz.headsdown.ml`, pure Kotlin and JVM-tested. This module is the on-device half of
`ml/foreman` (python): the pickup-vs-bump classifier, the Shift Planner, and the bounds that keep
both on the "only tighten" side. Its dependencies are kotlinx-serialization-json (already in the
app) and LiteRT's 23 KB Java API.

```sh
./gradlew :ml:test        # 36 tests: cross-language vectors, bounds, fallbacks
```

## API

| Type | What |
|---|---|
| `PickupClassifier` | `classify(MotionWindow): PickupDecision`: P(pickup), the calibrated logit, `PICKUP` / `NOT_PICKUP`, and the fail-closed reason when the window was unusable |
| `PickupWindowCollector` | Feed it every accelerometer sample; it calls back with a `MotionWindow` 3 s after each motion trigger (2 s of history plus 3 s after) |
| `ShiftPlanner` | `plan(List<PlannerEvent>, PlanRequest): ShiftPlan`: 96 slots of P(idle), the next window (`autoArm`, `reason`), and 7 nights with the weekly split |
| `PlannerEvent` | One line of the planner log ([LOG_SCHEMA.md](../../ml/foreman/planner/LOG_SCHEMA.md)): `toJsonLine()` / `parse()` |
| `ForemanGate` | `heartbeatAllowed(dark, decision)` (the classifier can only veto), `breakReason(decision)` (1 = pickup), `tighten(caps, proposal, now)` (clamps a plan into the wallet caps or drops it) |
| `ForemanModels` | Loads the shipped assets. `pickupClassifier(assets)` falls back to `FailClosedPickupClassifier` (every motion breaks) and `shiftPlanner(assets)` to conservative defaults (auto-arm off) |

Assets (`src/main/assets/foreman`, produced by ml/foreman, never edited by hand):
* `pickup_model.json`: the selected model (logistic), its calibration and threshold;
* `pickup_model.tflite`: the same model as a float32 LiteRT flatbuffer;
* `planner_params.json`: the tuned rhythm-model parameters and the population prior.

## Wiring (for the feature/shift wave)

1. **Collect.** In the shift service's sensor listener, call
   `collector.onSample(AccelSample(event.timestamp, x, y, z))` for every accelerometer sample,
   alongside `FaceDownDetector`. Keep it running from Armed onward, so a window always has 2 s
   of history.
2. **Classify.** On each window, while the rig is Down and the screen is off, call
   `classifier.classify(window)`, off the main thread (it is microseconds of arithmetic).
   `ForemanGate.breakReason(decision) == 1` means: dispatch a pickup break. That is a new
   `ShiftEvent` mapping to `BreakReason.LIFTED`, which signs BREAK reason 1.
3. **Never** let a `NOT_PICKUP` re-arm, un-cool or keep heartbeats flowing.
   `ForemanGate.heartbeatAllowed(dark, decision)` is the only combination rule, and `dark`
   stays the state machine's own `Signals.isDark`.
4. **Plan.** Append `PlannerEvent`s from the receivers the service already has (screen,
   `USER_PRESENT`, charging, face-down) to a private JSONL file. Write `monitor_start` and the
   current screen state when the service starts. At planning time, call
   `planner.plan(events, PlanRequest(now, tz, caps, digLamports))`, then pass the window
   through `ForemanGate.tighten` before signing any P-256 PLAN.

## LiteRT

`ForemanModels.pickupClassifier(json, tflite, preferLiteRt = true)` runs the `.tflite` through
`LiteRtPickupBackend` when a LiteRT runtime is present. The app must add
`com.google.ai.edge.litert:litert`, or use Google Play services. With no runtime, or if the
backend throws, the identical pure-Kotlin evaluation answers. A broken backend that returns NaN
gives a fail-closed PICKUP, never a bump. The pure-Kotlin path is the default.

`litert-api` is pinned to 1.4.1 on purpose: it is the last API artifact with no native code.
The 2.x API AAR bundles JNI libraries.
