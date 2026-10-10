# Model card: Foreman pickup-vs-bump classifier (v1, trained on synthetic data only)

| | |
|---|---|
| **Status** | **Trained on synthetic data only.** No real Redmi 14C recording exists yet (the phone ran its first short shift on 10 October 2026, and no sensor session from it has been added here). The numbers below validate the pipeline and the physics model. They do **not** measure real-world accuracy. Retrain with the protocol at the end before relying on it. |
| **Selected model** | Logistic regression on 39 window features: `model/pickup_model.json` (3.5 KB), also exported as `model/pickup_model.tflite` (2.1 KB, float32 LiteRT). |
| **Also trained** | HistGradientBoosting (`pickup_gbdt.json`) and a 1.8k-parameter 1D-CNN (`pickup_cnn.json`, `pickup_cnn.tflite`, 11 KB). The rule below picked logistic. |
| **Runs on** | The phone, in pure Kotlin (`android/ml`, `xyz.headsdown.ml.pickup`). LiteRT is optional, behind the same interface. Accelerometer only: the Redmi 14C has no gyroscope and a virtual proximity sensor. |
| **Code** | `synth.py` (data), `trigger.py`, `features.py` ([FEATURE_SPEC.md](FEATURE_SPEC.md)), `models.py`, `train.py`, `export.py`, `sensorlab.py`; full numbers in [RESULTS.md](RESULTS.md) |

## What it decides, and what it cannot

While a shift is running and the screen is off, `PickupWindowCollector` cuts a 5-second window
around every motion the trigger flags. The classifier answers one question about it: was the
phone **picked up**? Otherwise it was a bump, a slide or a set-down.

* **PICKUP** → the app sends a phone-signed BREAK with reason 1 (`pickup`). **NOT_PICKUP** →
  nothing happens.
* **It can only add a break.** `ForemanGate.heartbeatAllowed(dark, decision)` lets the model
  veto a heartbeat but never allow one. "Dark" comes from deterministic signals only: the
  FaceDownDetector, screen-off, no unlock and the charger. **Screen-on, unlock and unplugging
  stay hard breaks outside the model.**
* **Fail-closed.** A window with too few samples, a gap over 0.5 s, less than 2 s of history,
  or a non-finite score is a PICKUP. A model file that fails to load turns every motion into a
  PICKUP.
* It never sees a motion the trigger misses. In simulation, about 7% of face-down "carry"
  pickups (lifted without turning) move too gently to fire the trigger. Pickups that turn the
  phone more than 40° are caught by the FaceDownDetector anyway, and the screen-on and unlock
  breaks still apply.

## Data (synthetic, `synth.py`)

A face-down phone simulated at 1 kHz in the world frame and measured as Android reports it.
* **Labels:** bump, pickup, slide, set_down, with 20 physical variants (synth.py docstring).
  Examples: table knocks, hops with free fall, mattress bounces, a phone toppling off a
  pillow, the phone's own vibration, flips to view, face-down carries, peeks, slow lifts,
  pushes, cable drags, drops onto the table.
* **Nuisance variables:** six surfaces, phone mass 0.15–0.26 kg, resting tilt and yaw, and a
  MEMS + HAL sensor model:
  * output rate 50–200 Hz with a low-pass at 0.2–0.45 × the rate;
  * delivery at about 50 Hz with ±5% clock skew, or 62.5 or 100 Hz;
  * 0–1.5 ms timestamp jitter, up to 2% dropped samples, occasional 0.1–0.4 s gaps;
  * 4–40 mm/s² noise, bias up to about 0.2 m/s², about 1% scale and cross-axis error;
  * 1.2–38 mm/s² quantization and a 2–16 g range.
* **Windows:** cut by the production trigger (an exact port of the sensor lab's), so they line
  up with recorded and on-device windows.
* **Splits** (triggered windows; 50% pickup, 25% bump, 12.5% slide, 12.5% set-down):
  * train 10,000, validation 4,000 and IID test 4,000, on nightstand, desk, wooden table and
    mattress;
  * **shift test** 4,000, on held-out conditions: a glass table and a sofa, phones under
    0.17 kg or over 0.24 kg, and harsher sensors (40 or 100 Hz delivery, up to 0.06 m/s²
    noise, up to 77 mm/s² quantization, 2–4 g range).

## Selection, threshold and calibration (rules fixed before any result)

1. Each model's logit is Platt-calibrated on validation, with smoothed targets so separable
   data cannot push the scale to infinity.
2. The threshold is set in calibrated-logit space. If validation has a gap between the lowest
   0.5% of pickups and the highest 0.5% of non-pickups, the threshold is the gap's midpoint.
   Otherwise it is the largest threshold that keeps validation recall at or above 99.5% (the
   99% target plus a margin).
3. Take the lowest validation false-break rate. Walk the order logistic → gbdt → cnn and pick
   the first model within 25% (or 0.5 points) of that lowest rate.

The test splits are reported, never used to choose.

## Results (synthetic; [RESULTS.md](RESULTS.md) has every breakdown)

Selected logistic model; verdict PICKUP iff the calibrated logit is at least 0.0004
(P(pickup) ≥ 0.50). 95% Wilson intervals:

| Split | Pickup recall | Bump false-break | All non-pickups false-break |
|---|---|---|---|
| validation | 99.85% [99.6, 99.9] | 0.10% | 0.05% |
| IID test | **100.00% [99.8, 100]** | 0.00% [0.0, 0.4] | 0.15% [0.1, 0.4] |
| held-out conditions | **99.90% [99.6, 100]** | 0.20% [0.1, 0.7] | 0.30% [0.1, 0.7] |

* **All three models** meet the 99% recall target on every split. GBDT: 99.85% IID and 99.60%
  held-out recall. CNN: 99.70% and 99.65%.
* **Calibration** on the IID test: ECE 0.0017, Brier 0.0009. P(pickup) is calibrated to the 50/50
  training mix. At night most motion windows are not pickups, so the threshold is what matters.
* **Hardest cases:**
  * the set-down false-break rate is 0.6% IID and 0.8% held-out;
  * the topple bump (no hand, the phone tips 8–35° on a pillow) is 0%;
  * a face-down carry with a very steady hand on a noisy sensor is the pickup the features
    find hardest.
* **Export checks:** the float32 `.tflite` files run in the LiteRT interpreter match the
  float64 reference within 1.3e-5 (CNN) and 3e-6 (logistic) in the logit. The Kotlin port
  matches Python within about 1e-13 on 30 vector windows (the bar is 1e-6).

**Why synthetic numbers are this good, and why that is not evidence:** the generator's classes
are physically distinct by construction (a hand-held phone keeps moving; a bumped phone
settles). Real windows will be messier in ways the generator cannot know:
* other hands and other furniture;
* a pet on the bed;
* pockets, laps and pillows;
* the Redmi's real sensor chip and HAL batching;
* labels that are themselves wrong.
Expect real recall and false-break rates to be worse until the model is retrained on real
sessions.

## Limitations

* **No real data.** The physics and the sensor ranges are informed estimates, not measured on
  the Redmi 14C. The sensor lab records `device` and `sensor` in its session header, so the real
  chip and rate will be known after the first session.
* **One-motion windows.** A window is 2 s before to 3 s after the first trigger. A pickup that
  starts more than 3 s after a bump that fired the trigger falls into the trigger's refractory
  period. The FaceDownDetector and the hard breaks are the backstop.
* **Not adversarial-proof.** Someone determined to move a phone without breaking can try to
  imitate a slide. The on-chain design never trusts the classifier for safety: it can only
  stop digs, and remote Stack tables are "honor-plus" (THREAT_MODEL.md).
* **No gyroscope or proximity features** in v1. Devices that have them (Seeker) could add
  optional channels in a v2 spec.

## Privacy

Classification happens on the phone. Accelerometer windows are never logged in release builds,
never uploaded and never stored, except in the **debug-only** sensor lab, which writes to
app-private storage and exports only when the founder taps Export. Exports stay on the
founder's machine: `ml/foreman/classifier/data/` is git-ignored except a small synthetic sample.

---

## Recording protocol (for the founder, on the Redmi 14C)

Goal: **at least 2,000 labelled events** (the build plan's bar), enough to measure 99% pickup
recall with a meaningful interval. With 600 real pickups and no misses, the 95% lower bound is
99.4%; with one miss it is 99.1%.

**Setup.** Install a debug or localdev build: the sensor lab does not exist in release. Open it
from the Home screen → "Sensor lab (debug)". Keep a note of every session: id, surface,
position, charger on or off, case on or off.

**Surfaces** (each label on each one):
1. nightstand
2. desk
3. dining table
4. bed: the phone on the duvet next to the pillow

Optionally a sofa or a glass table. Vary the position (centre or edge, on the charger or not)
across sessions. Use the case you normally use.

**Sessions per surface:**

| Label | Sessions per surface | Reps per session | Total over 4 surfaces | Mix within the sessions |
|---|---|---|---|---|
| `pickup` | 3 | 50 | 600 pickups, plus 600 set-downs for free (see below) | pick up to look (most); keep it face-down while lifting; peek and put back within 2 s; slow, careful lift; slide to the edge then lift; pick up and walk away |
| `bump` | 3 | 50 | 600 | knock the furniture lightly, medium and hard; put a cup or book down nearby; brush the phone with your hand; drop something heavy nearby; make it buzz (send it a notification); footsteps or a door. In bed: sit down, lie down, roll over |
| `slide` | 1 | 50 | 200 | push it across; drag it by the cable; rotate it in place |
| `set_down` | 1 | 25 | 100, plus the free ones | from in your hand, put it down face-down: gently, briskly, and from 1–3 cm |

**How to do one session:**
1. Pick the label in the lab and tap Start. Lay the phone face-down on the surface and leave it
   still for **5 seconds**.
2. Do **one** motion.
3. Pickups: hold the phone in your hand for **at least 4 seconds**, then put it back face-down
   and leave it still for **5 seconds** before the next rep. The put-back becomes its own
   window, which the loader labels `set_down` automatically.
4. Bumps and slides: leave the phone still for **5 seconds** between reps (the trigger's 3 s
   refractory period plus a clean 2 s before the next one).
5. Finish by picking the phone up and stopping the session. When a screen-on or unlock follows
   within 5 s, the loader drops that final pickup from bump and slide sessions.
6. Write the conditions into `conditions.csv`:
   `session_id,surface,position,charger,case,notes`
   (for example `20261005-231500,nightstand,edge,yes,yes,`).

**Export:** use the lab's export. It produces one labelled CSV holding every session (fix a
wrong session label in the lab first), shared through the share sheet. Get it onto the laptop,
for example by saving it to Files and running `adb pull`. **Never commit it.**

**Check and retrain:**

```sh
ml/foreman/classifier/retrain.sh ~/Downloads/sensorlab-export-*.csv conditions.csv
cd android && ./gradlew :ml:test
```

The script prints what the label rules kept, relabeled and dropped. It then retrains on
synthetic plus real windows (real ones weighted 3x). When the real validation sessions hold at
least 100 pickups, they set the threshold. Sessions are split 60/20/20 by session id. Finally
it re-runs the selection rule and rewrites `model/`, RESULTS.md and the Android asset and
vectors.

**Accept the real model only if** the "real sensor lab, test" row of RESULTS.md shows pickup
recall ≥ 99% with a 95% lower bound ≥ 98.5% on at least 200 real test pickups. Read the
per-surface bump false-break rate before shipping. Otherwise keep collecting.
