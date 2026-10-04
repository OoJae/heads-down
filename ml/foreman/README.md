# Foreman: the on-device AI in Heads Down

Foreman is three small models. Each has one narrow job, and each runs on the phone.

| Model | Job | Status | Card |
|---|---|---|---|
| **Pickup classifier** | Separates a pickup from a bump, slide or set-down while the screen is off | **Built and shipped as an asset; trained on physics-based synthetic data.** Waiting for real Redmi 14C recordings | [classifier/MODEL_CARD.md](classifier/MODEL_CARD.md) |
| **Shift Planner** | Learns when this phone sits idle, proposes the next shift window and splits the weekly budget | **Built and shipped; tuned and evaluated on simulated users** | [planner/MODEL_CARD.md](planner/MODEL_CARD.md) |
| **Cost Forecaster** | Forecasts mining cost against market price | **Advisory only.** It lost to the simple on-chain rule, so it explains and never decides | [../forecaster/RESULTS.md](../forecaster/RESULTS.md) |

## What the AI does

* **Ignores bumps.** While a shift runs with the screen off, every motion the accelerometer
  flags is cut into a 5-second window. A 39-feature logistic model decides whether it was a
  pickup. A pickup sends a phone-signed BREAK (reason 1, `pickup`); a bump does nothing.
  * This is what lets a knocked nightstand or a shared Stack table not end a shift.
  * It works without a gyroscope (the Redmi 14C has none).
  * On synthetic tests the selected model keeps **≥ 99.85% pickup recall** with **≤ 0.2% of
    bumps** breaking a shift, including surfaces, phone weights and sensor noise it never saw
    in training.
* **Plans the night.** From the app's own log (screen, unlocks, charging, alarms), a Bayesian
  per-slot model estimates P(idle) for every 15 minutes of the next day. It proposes the window
  that maximizes dark time the shift is expected to finish, ends it at the alarm, and spreads
  the week's budget over the nights with the most expected idle time.
  * On five simulated user types its windows finished **90.6%** of shifts, against **57%** for
    a fixed 23:00–07:00 window.
  * That is +0.3 h of completed dark time a night.
* **Explains itself.**
  * The classifier is a linear model on named features (tilt, rotation travel, settling,
    lift velocity, jerk).
  * Every planner number is a weighted count of the user's own nights.

## What the AI does not do

* **It never moves funds, signs a transaction, decides a dig or raises a limit.** Digs need a
  fresh heartbeat from the phone's Keystore key, verified on-chain, inside wallet-signed caps.
  The program enforces the cost gate itself.
* **It can only tighten.**
  * The classifier can add a break but never remove one.
  * Screen-on, unlock, unplugging and the deterministic face-down detector stay hard breaks
    outside any model (`ForemanGate.heartbeatAllowed`).
  * Planner proposals are clamped inside the wallet caps (`ForemanGate.tighten`); the program
    refuses anything above them anyway (`PlanExceedsCaps`).
  * Anything the model cannot judge is treated as a pickup (fail-closed).
* **It sends nothing anywhere.** Sensor windows and the planner log stay on the phone. Models
  ship inside the APK as files, and nothing is downloaded at runtime.
* **It does not track sleep or health.** "Idle" means screen off and no unlock.
* **No LLM is on any decision path.**
* **Synthetic results are not real-world accuracy.** No real Redmi recording exists yet. The
  classifier must be retrained with the [recording protocol](classifier/MODEL_CARD.md#recording-protocol-for-the-founder-on-the-redmi-14c)
  before its numbers mean anything about real nights.

## Wording for the deck and the demo (checked against the honesty rules)

> "On-device AI. A pickup-versus-bump classifier, accelerometer only, and a shift planner that
> learns when your phone sits idle. Both run on the phone, can only tighten the limits your
> wallet signed, and send no sensor data anywhere. They are trained on physics-based synthetic
> data and simulated users until real recordings from this phone exist. Our cost forecaster
> lost to the simple on-chain rule, so it only explains."

The banned wording in [docs/ECONOMICS.md](../../docs/ECONOMICS.md) §4 applies here too. Also never
claim sleep tracking or health outcomes.

## How it is built

* **One spec, two languages.** Python is the reference; `android/ml` (package
  `xyz.headsdown.ml`) is a line-by-line Kotlin port.
  * Committed test vectors pin them together: Kotlin matches Python to **~1e-13** on 30 raw
    accelerometer windows (trigger, cleaning, resampling, all 39 features, CNN channels and all
    three models) and on 5 planner logs (labels, forecasts, windows, budget splits). The bar is
    1e-6.
  * The feature contract is [classifier/FEATURE_SPEC.md](classifier/FEATURE_SPEC.md); the log
    contract is [planner/LOG_SCHEMA.md](planner/LOG_SCHEMA.md).
* **Physics-grounded synthetic data.**
  * The phone is simulated at 1 kHz, with a MEMS sensor and Android HAL model (rate, filter,
    jitter, gaps, noise, bias, quantization, saturation).
  * Windows are cut by the same trigger as the debug sensor lab and the app.
* **Model choice fixed before results.** Logistic, gradient-boosted trees and a 1.8k-parameter
  1D-CNN are trained; the rule picks the simplest model within tolerance of the lowest
  validation false-break rate (logistic this time).
* **LiteRT.** The selected model and the CNN are exported as float32 `.tflite` flatbuffers.
  They are verified in the LiteRT interpreter against the float64 reference (≤ 1.3e-5 in the
  logit) and wired behind the same Kotlin interface.
  * The app runs pure Kotlin by default; for a 39-weight model LiteRT adds a runtime, not
    accuracy.
  * Without a LiteRT runtime the pure-Kotlin path answers.

## Layout

| Path | What |
|---|---|
| `classifier/synth.py` | Physics-grounded synthetic windows (20 variants, 6 surfaces, sensor model) |
| `classifier/trigger.py`, `features.py` | The motion trigger port and feature spec v1 |
| `classifier/sensorlab.py` | Loader for the debug sensor lab's CSVs, label rules, a synthetic sample |
| `classifier/models.py`, `train.py`, `evaluate.py`, `export.py`, `report.py` | Training, selection, evaluation, export, RESULTS.md |
| `classifier/retrain.sh` | Retrain with real recordings |
| `classifier/model/` | `pickup_model.json` / `.tflite` (selected), the other models, vectors, metrics |
| `planner/logs.py`, `model.py` | Log schema and slot labels; the posterior, windows, weekly split |
| `planner/archetypes.py`, `evaluate.py`, `report.py` | Simulated users, tuning, evaluation, export |
| `planner/model/` | `planner_params.json` (shipped), vectors, metrics |
| `test_android_sync.py` | The Android assets and test vectors are byte-identical to these exports |

## Reproduce

```sh
cd ml/foreman
python3 -m venv .venv && .venv/bin/pip install -r requirements.txt   # python3.9; TensorFlow 2.20 for the CNN/.tflite
.venv/bin/python -m classifier.train      # ~5 min: data, three models, selection, exports, RESULTS.md, android/ml sync
.venv/bin/python -m planner.evaluate      # ~4 min: simulate, tune, evaluate, exports, RESULTS.md, android/ml sync
.venv/bin/python -m pytest -q classifier/tests planner/tests test_android_sync.py
cd ../../android && ./gradlew :ml:test     # Kotlin against the same vectors
```

`--quick` runs either pipeline in a minute or two, writing to `out/` (not committed). Both
pipelines are seeded and deterministic. On this machine, a rerun reproduced every committed model,
`.tflite`, parameter and vector file byte for byte.
