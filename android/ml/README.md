# `:ml`: Foreman on-device models

Package `xyz.headsdown.ml`, pure Kotlin and JVM-tested. This module is the on-device half of
`ml/foreman` (python): the pickup-vs-bump classifier, the Shift Planner, and the bounds that keep
both on the "only tighten" side. Its dependencies are kotlinx-serialization-json (already in the
app) and LiteRT's 23 KB Java API.

```sh
./gradlew :ml:test        # 54 tests: cross-language vectors, bounds, fallbacks, the live sample path
```

## API

| Type | What |
|---|---|
| `PickupClassifier` | `classify(MotionWindow): PickupDecision`: P(pickup), the calibrated logit, `PICKUP` / `NOT_PICKUP`, and the fail-closed reason when the window was unusable |
| `MotionTrigger` | Flags the start of a motion. The plain trigger runs the arithmetic the vectors pin; **`MotionTrigger.live()` is the one to run on a phone** (see below). `isMoving` tells a fresh motion from a re-fire |
| `RestTracker` | "The phone has come to rest", without a model: half-second block means steady within 2° and 0.3 m/s² for 2.5 s |
| `PickupWindowCollector` | Feed it every accelerometer sample (`onSample(tNanos, x, y, z)` allocates nothing); it calls back with a `MotionWindow` 3 s after each trigger (2 s of history plus 3 s after), and says whether that trigger was an onset |
| `ShiftPlanner` | `plan(List<PlannerEvent>, PlanRequest): ShiftPlan`: 96 slots of P(idle), the next window (`autoArm`, `reason`), and 7 nights with the weekly split |
| `PlannerEvent` | One line of the planner log ([LOG_SCHEMA.md](../../ml/foreman/planner/LOG_SCHEMA.md)): `toJsonLine()` / `parse()` |
| `ForemanGate` | `heartbeatAllowed(dark, decision)` (the classifier can only veto), `breakReason(decision)` (1 = pickup), `tighten(caps, proposal, now)` (clamps a plan into the wallet caps or drops it) |
| `ForemanModels` | Loads the shipped assets. `pickupClassifier(assets)` falls back to `FailClosedPickupClassifier` (every motion breaks) and `shiftPlanner(assets)` to conservative defaults (auto-arm off) |

Assets (`src/main/assets/foreman`, produced by ml/foreman, never edited by hand):
* `pickup_model.json`: the selected model (logistic), its calibration and threshold;
* `pickup_model.tflite`: the same model as a float32 LiteRT flatbuffer;
* `planner_params.json`: the tuned rhythm-model parameters and the population prior.

## The trigger on a live stream

Two properties that the vectors (one stream, one window each) do not show:

* **The plain trigger never comes back to rest.** Its resting reference follows the phone only
  while the phone is still relative to that reference. Once the phone rests in a new posture
  (laid down after arming in the hand, tipped on a pillow) it is "moving" for good and fires
  every 3 s. `PickupStreamTest` replays it: 18 or more triggers in a minute of stillness.
* **The shipped model calls a window with no motion in it a PICKUP** (it was trained on motion
  onsets only). On a still phone lying flat with a quiet sensor: PICKUP for 200 of 200 windows,
  and for 183 of 200 at 0.02 m/s² of noise. `PickupStreamTest` prints the table (it reports,
  it does not require: a retrained model may answer differently).

So on a phone:

* run **`MotionTrigger.live()`**. It adds a `RestTracker`: a trigger that stayed "moving"
  through 2.5 s the tracker calls rest takes that posture as its new reference. The arithmetic
  of `onSample` is untouched, and the first trigger of a stream that starts at rest fires on
  the same sample as the plain trigger (checked on every vector window). The shift service and
  the debug sensor lab both use it;
* **classify onsets only** (`PickupWindowCollector.Listener.onTrigger(…, onset)`), never a
  re-fire.

The Python reference runs the plain trigger and takes the first trigger of a stream that starts
with the phone lying there, which is the situation `live()` restores. If `ml/foreman` gives the
trigger a rest rule of its own, `live()` should become that rule and the vectors should cover it.

## Wiring (done in feature/shift)

[`feature/shift/FOREMAN.md`](../feature/shift/FOREMAN.md) describes what is wired and what is
not. In short:

1. **Collect.** The shift service's sensor listener feeds every accelerometer sample (50 Hz) to
   `PickupWatch`, which owns the collector, for the whole life of the service.
2. **Classify.** A window is classified, off the main thread, only if its trigger was an onset
   and the rig was DOWN with the screen off from the trigger to the window's close.
   `ForemanGate.breakReason(decision) == 1` dispatches `ShiftEvent.PickupDetected`: a DOWN rig
   breaks as `BreakReason.LIFTED`, which signs BREAK reason 1.
3. **Never** does a `NOT_PICKUP` re-arm, un-cool or keep heartbeats flowing: it has no event.
   `ForemanGate.heartbeatAllowed(dark, decision)` is the only combination rule, and `dark` stays
   the state machine's own verdict.
4. **Plan.** `RhythmRecorder` appends `PlannerEvent`s from the service's receivers to a private,
   size-capped JSONL file. `PlannerRepository` calls `planner.plan(events, PlanRequest(now, tz,
   caps, digLamports))`, and a window can be signed only after `ForemanGate.tighten`.

## Cost

`onSample` of the trigger and the collector allocates nothing while no window closes
(`PickupStreamTest` measures 0 bytes over 60,000 samples; unit tests run without HotSpot's
escape analysis so a short-lived object cannot hide). Classifying one window takes a median of
22 µs on the JVM (p95 54 µs, one laptop core), nearly all of it the feature pipeline. The
on-device number is not measured yet: the debug sensor lab has a benchmark button for it.

## LiteRT

`ForemanModels.pickupClassifier(json, tflite, preferLiteRt = true)` runs the `.tflite` through
`LiteRtPickupBackend` when a LiteRT runtime is present. The app must add
`com.google.ai.edge.litert:litert`, or use Google Play services. With no runtime, or if the
backend throws, the identical pure-Kotlin evaluation answers. A broken backend that returns NaN
gives a fail-closed PICKUP, never a bump. The pure-Kotlin path is the default.

`litert-api` is pinned to 1.4.1 on purpose: it is the last API artifact with no native code.
The 2.x API AAR bundles JNI libraries.
