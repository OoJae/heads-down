# Foreman in the shift service

How the on-device models of [`:ml`](../../ml/README.md) are wired into `feature/shift`, what
they are allowed to do, and what is still untested. Package
`xyz.headsdown.feature.shift.foreman`.

| Piece | What it does | State |
|---|---|---|
| `PickupWatch` | Feeds the accelerometer to the motion-window collector and asks the pickup classifier about a window | **Live in the shift service.** Never run on a real phone yet |
| `ShiftEvent.PickupDetected` | The classifier's one effect: a DOWN rig breaks as LIFTED (BREAK reason 1) | Live |
| `RhythmRecorder`, `PlannerLog` | Write the Shift Planner's log from the service's receivers, in app-private storage | Live |
| `PlannerRepository` | Runs the planner, publishes `TonightPlan` (window, week's budget split, signable plan) as a `StateFlow` | Live; nothing shows it yet |
| `AutoArmPolicy`, `PlanArmer`, `ForemanSettings.autoArmEnabled` | Decide when a phone on its charger may arm a shift by itself | **Groundwork only. Off by default, and nothing calls it** |
| `PickupBenchmark` (debug) | Times the classifier on the device | Debug and localdev builds only |

Everything below was verified with JVM unit tests only. The Redmi 14C was not connected, so no
part of this has run on a phone: see [the device checklist](#device-checklist).

## 1. Pickup classifier

```
accelerometer 50 Hz, batched 1 s          sensor thread (allocates nothing per sample)
   ├─ FaceDownDetector ── verdict changed ─────────────────────────▶ main: ShiftEvent.Posture
   └─ PickupWatch ─ PickupWindowCollector(MotionTrigger.live())
          │  a window closed, and it is one the model may judge
          ▼
      hd-foreman thread: classifier.classify(window)
          │  ForemanGate.breakReason(decision) == 1
          ▼
      main: still DOWN?  ──yes──▶ ShiftEvent.PickupDetected ─▶ Broken(LIFTED), sign BREAK reason 1
```

### Which windows the model is asked about

All three must hold. Otherwise the window is dropped unread.

1. **A fresh motion.** The trigger fired on the sample the phone left its resting posture.
2. **Hot when it began.** The rig was DOWN with the screen off on the trigger sample.
3. **Hot until it closed**, with no break in between.

The task said "classify each completed motion window while Down with the screen off". These
three rules are narrower, for reasons found while wiring it:

* **The trigger never comes back to rest by itself.** Its resting reference only follows the
  phone while the phone is still relative to that reference. After the phone is laid down (it
  was armed in the hand) or tipped more than 12°, it stays "moving" and fires every 3 s for the
  rest of the night (`:ml` `PickupStreamTest`: 18 or more triggers in a minute of a phone
  lying still).
* **The shipped model calls a window with no motion in it a PICKUP.** On a still phone at 0°
  with a quiet sensor it said PICKUP for 200 of 200 windows (calibrated logit up to +4.9), and
  for 183 of 200 at 0.02 m/s² of sensor noise (`:ml` `PickupStreamTest` prints the table). It
  was trained on motion onsets only, so a still window is outside what it knows.
* Together: classifying every window would break every shift a few seconds after it went hot.

So `MotionTrigger.live()` adds a model-free rest test (`RestTracker`: half-second block means
steady within 2° and 0.3 m/s² for 2.5 s) that gives a stuck trigger its new resting posture,
and `PickupWatch` judges onsets only. Rule 2 keeps the set-down that starts a shift from being
judged (set-downs have the model's highest false-break rate, 0.4% to 2.5% on synthetic data).
Rule 3 leaves a rig that is already cooling to the rules that cooled it and to their grace
window.

What this costs: a pickup is not judged by the model if it begins within about 3 s of the phone
being laid down or tipped, during the 3 s after another trigger, or while the rig is cooling.
The tilt rule, screen-on, unlock and unplugging cover those exactly as they did before.

### What a verdict can do

* **PICKUP** → `ShiftEvent.PickupDetected` → a DOWN rig becomes `Broken(LIFTED)` at once, the
  phone signs BREAK reason 1, and the journal records the first pickup. In every other state
  the event does nothing.
* **NOT_PICKUP** → nothing. There is no event for it, no state is written and the trigger is
  not touched.
* **Fail-closed.** A window with too few samples or a gap over 0.5 s, a classifier that
  throws, and a model that could not be loaded (for any reason) are all PICKUP.
* Heartbeats go through `ForemanGate.heartbeatAllowed(dark, verdict)`: `dark` comes from the
  state machine and the detector, and a verdict can only veto.

The bounds are tests, not comments:

| Bound | Test |
|---|---|
| The event breaks DOWN and does nothing else, for all 160 state × signal × mode combinations (also unreachable ones) | `PickupBoundsTest` |
| It never heats, re-arms or un-cools; grace windows keep their deadline; a 40,000-event storm | `PickupBoundsTest` |
| Screen-on, unlock, unplugging and the tilt rule behave as before | `PickupBoundsTest`, the unchanged `ShiftStateMachineTest` |
| Which windows are judged (rules 1 to 3), NOT_PICKUP is a no-op, fail-closed cases, the switch | `PickupWatchTest` |
| The recorded synthetic windows of `:ml`'s vectors, through detector + watch + machine | `PickupWatchTest` |

On those 25 recorded windows: the 3 flat-carry pickups (the phone never tilts past 19°) are
broken by the classifier alone, the other 10 pickups are cooled by the detector's tilt rule,
and none of the 12 bumps and slides breaks anything.

### The switch

`ForemanSettings.pickupBreaksEnabled` (default **on**) takes the classifier out of a shift: no
window is judged, and the deterministic rules are all that is left. It exists because the model
has only ever seen synthetic data. Debug and localdev builds have it on the sensor lab screen;
release builds have no screen for it yet. It cannot make a rig hot in either position.

### CPU and battery

* **Sampling.** 50 Hz instead of 5 Hz, because the feature spec resamples to 50 Hz and a
  window needs at least 100 samples in 5 s. Delivery is still batched (1 s), so the sensor
  thread wakes about once a second. The face-down detector sees the same samples; it is
  time-based, so its 1.5 s and 0.3 s dwell times are unchanged.
* **Per sample.** The detector, the trigger, the rest test and the ring buffer: a few dozen
  arithmetic operations and **no allocation**. `PickupWatchTest` and `PickupStreamTest`
  measure 0 to 40 bytes over 60,000 samples (they allow 2 KB for the JVM's own one-off
  allocations); the trigger as first written allocated 2.4 MB over the same samples.
* **Inference.** Only when a window passes the three rules, on the `hd-foreman` background
  thread. A still night runs none (`PickupWatchTest`: an hour of samples, zero windows judged).
* **Measured on the JVM** (a rough proxy: one laptop core, HotSpot without escape analysis):
  **median 22 µs, p95 54 µs per window** over `:ml`'s 27 recorded windows, nearly all of it the
  feature pipeline. On the Redmi's Cortex-A75 under ART, expect something from a few hundred
  microseconds to a few milliseconds. That is an estimate, not a measurement.
* **On the device:** sensor lab screen → "Benchmark the classifier on this phone". It runs
  `PickupBenchmark` at the service's thread priority and prints the median, p95 and maximum.

## 2. Planner log

`RhythmRecorder` writes [LOG_SCHEMA.md](../../../ml/foreman/planner/LOG_SCHEMA.md) lines from
what the service already receives:

| When | Lines |
|---|---|
| Service start | `monitor_stop` for a session the OS killed (stamped with its last alive time), `monitor_start`, the screen state, the charger state, `alarm_next` |
| `SCREEN_ON` / `SCREEN_OFF` / `USER_PRESENT` | `screen_on` / `screen_off` / `user_present` |
| Charger | `power_connected` / `power_disconnected` |
| Face-down verdict changes | `face_down_start` / `face_down_end` |
| `ACTION_NEXT_ALARM_CLOCK_CHANGED`, and when planning | `alarm_next` (without `alarm_ts` when no alarm is set) |
| A shift starts or stops running | `shift_armed`, `shift_ended` with the on-chain reason code |
| Service stop | `monitor_stop` |

* **Where.** `filesDir/foreman/planner.jsonl` plus up to three older segments.
* **Size.** Four segments of 768 KiB: never more than 3 MiB, never less than 2.25 MiB kept
  after a rotation (26 weeks at the schema's busiest 12 KB a day).
* **It never leaves the phone.** No code path sends it anywhere. The app excludes all of its
  data from backup and device transfer, and `PlannerLogTest` scans every FileProvider path
  file in the app to prove none can serve `filesDir/foreman`. `PlannerLog.clear()` deletes it.
* **One addition to the schema.** The charger state is written at start as well as the screen
  state. Without it a phone plugged in before the shift would show no charger event at all.
  Planner v1 ignores charger lines either way.
* The app observes only while the shift service runs, so daytime slots stay MISSING, which
  the planner treats as unknown rather than busy.

## 3. Tonight's plan

```kotlin
@Inject lateinit var planner: PlannerRepository

planner.setBudget(PlanningBudget(WalletLimits.fromChain(rig.capWeek, …), PlanTemplate(…)))
planner.refresh()                       // suspend; or requestRefresh()
planner.tonight.collect { plan -> … }   // StateFlow<TonightPlan?>
```

* `TonightPlan` holds the proposed window, the next seven nights with the week's budget split,
  and `signable`. None of these types comes from `:ml`, so the app can show them while `:ml`
  stays an `implementation` dependency of this module.
* **A proposal can be signed only as a `SignablePlan`.** That class has a private constructor
  and no `copy`; the only way to an instance is `ForemanGate.tighten` against the wallet's
  limits. `retighten` checks it again against the limits as they are when it is about to be
  used. The program checks once more (`PlanExceedsCaps`, `CapsExpired`, `OutsideWindow`).
* Without limits (`setBudget` never called) there is a window to show and nothing to sign.
* The service refreshes the plan when it starts and stops. The limits and the template must
  come from the app, which reads the Rig account; this module has no chain client.
* Checked against the Python reference: the five logs of `planner_vectors.json`, read back
  through the log file, give the reference windows and budget splits (`PlannerRepositoryTest`).

## 4. Auto-arm (off by default)

**Nothing arms by itself today.** `AutoArmPolicy` is a pure function with tests, `PlanArmer` is
an interface nobody implements, and `ForemanSettings.autoArmEnabled` is `false` until the user
turns it on. This section is the safety argument for when it is wired.

### When the policy says "arm"

Every line must hold. Anything unknown is a reason to hold.

| Condition | Why |
|---|---|
| The user's switch is on | Default off |
| The rig is Idle, not frozen, and no shift is open on-chain | `arm_shift` needs Idle; only the wallet unfreezes |
| A rig key exists and the Rig is registered | Otherwise nothing could heartbeat |
| Face-down, screen off, on the charger, for at least 60 s | The same deterministic signals a shift needs; Night Shift only |
| The plan is at most 12 h old | The planner looks 24 h ahead |
| The window is confident | At least 7 observed days, and every slot's cautious estimate over the planner's bar |
| Now is inside the window with at least 30 min left | |
| No shift started on this phone tonight (from 4 h before the window) | One shift a night; never re-arm over a shift the user ended |
| The plan passes `ForemanGate.tighten` against the wallet's limits **as they are now** | Not the limits the plan was made with |
| Tonight's share and the week's remainder cover one dig | Unless the plan is focus-only |

### Why this stays inside what the wallet signed

1. **No new authority.** The rig key may already arm a shift within the wallet's caps and
   expiry (THREAT_MODEL.md, K2). The program checks `max_ev_cost ≤ cap_max_cost`,
   `dig_lamports ≤ cap_round`, `now ≤ caps_expiry_ts` and `now ≤ window_end` whatever the phone
   signs, and each dig is checked against `cap_shift` and `cap_week`.
2. **The model cannot raise a number.** The planner proposes a window and a split. The plan's
   cost ceiling, SOL per dig and tiles come from the template the user chose, and
   `ForemanGate.tighten` can only lower them.
3. **Arming is not digging.** A dig still needs a fresh heartbeat, which needs the phone
   face-down, screen off and on the charger, and stops on a pickup, screen-on, unlock or unplug.
4. **The worst case is bounded and known.** A shift armed on a night the user did not want
   places at most `min(cap_shift, cap_week − spent_week)` at or under the cost ceiling the
   wallet signed. That is the same bound as a shift armed by hand.
5. **The planner's nightly share is advisory.** The PLAN cannot carry a per-night budget, so
   on-chain the bound is `cap_shift`, not the share. Until the phone stops heartbeating at the
   share, the honest statement is "bounded by the per-shift cap".

### What is missing before the switch can be offered

* **A `PlanArmer`.** Signing the PLAN is in core/keys; landing `arm_shift` mode 1 needs a fee
  payer. The crank intake (contract A) takes heartbeat, break and freeze frames only.
* **Ending the previous shift.** `arm_shift` needs Idle, and only the wallet can end a shift
  inside its window. After the window and the lease, anyone can.
* **An observer.** The shift service runs only during a shift. Something must watch for
  "face-down on the charger" while idle, and Android 12+ limits starting a foreground service
  from the background.
* **On the device:** a week of real logs to see the windows it proposes, HyperOS keeping the
  observer alive, the notification telling the user a shift armed itself, and one run of each
  hold reason.

## Device checklist

Not done: the Redmi 14C was not connected.

1. **Delivered rate.** Sensor lab → record 30 s face-down with the screen off: about 50
   samples a second. Under about 20 Hz every window is unusable, so every motion of a hot rig
   breaks the shift (fail-closed). If that happens, switch pickup breaks off and report it.
2. **Benchmark.** Sensor lab → "Benchmark the classifier on this phone".
3. **A quiet night.** Arm, lay the phone down: the shift must still be hot after 10 minutes.
4. **Bumps.** Knock the nightstand: no break.
5. **A flat carry.** Lift the phone without tilting it and walk away with the screen off: the
   shift breaks about 3 s later as "the phone was picked up".
6. **The planner log.** `run-as xyz.headsdown cat files/foreman/planner.jsonl` after a shift.
7. **Battery.** One night at 50 Hz against one at the old 5 Hz build.
