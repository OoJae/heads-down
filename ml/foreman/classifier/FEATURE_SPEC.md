# Pickup feature spec v1

This is the contract between the Python reference (`features.py`) and the on-device port
(`android/ml/.../pickup/PickupFeatures.kt`). Both implement exactly what is written here.
`model/pickup_vectors.json` holds 30 windows (raw samples in, every intermediate out). The
Kotlin test `PickupVectorsTest` must agree with them to **1e-6**. The observed difference is
about 1e-13: transcendental functions can differ by one ulp between libm implementations.

All arithmetic is IEEE-754 float64. Sums run left to right in index order. Constants:

| Name | Value |
|---|---|
| `G` | 9.80665 |
| `N`, `T0` | 250 grid points; index 100 is the trigger |
| `STEP_NS`, `DT` | 20,000,000 ns; 0.02 s |
| `ALPHA` | `DT / (0.4 + DT)` (gravity low-pass, τ = 0.4 s) |
| `RAD_TO_DEG` | 57.29577951308232 (`java.lang.Math.toDegrees`) |

## 1. Window and cleaning

A window is the trigger time `T` (ns) and the samples `(t_ns, x, y, z)`: float32 m/s², Android
axes, with screen-down reading about (0, 0, −9.81). On the phone,
`PickupWindowCollector` cuts it from `T − 2 s` through the first sample at or after
`T + 3 s`. `MotionTrigger` is the sensor lab's trigger, ported unchanged.

Cleaning, in input order: drop any sample with a non-finite component, and drop any sample
whose `t_ns` is not greater than the last *kept* sample's.

## 2. Quality check (fail-closed)

Let `S` be the cleaned samples with `T − 2 s ≤ t ≤ T + 3 s`.
* `|S| < 100` → **too_few_samples**.
* Otherwise, if any of these gaps exceeds 500 ms → **gap**: `[T − 2 s, S₀]`, consecutive
  samples, `[S_last, T + 3 s]`.

A window that fails either check is classified **PICKUP** without running the model.

## 3. Resampling and signals

Grid times `g_i = T + (i − 100) · STEP_NS`, for `i = 0..249`, in integer ns. Let `j` be the
largest index with `t_j ≤ g_i`.
* If `j < 0`, use the first sample. If `j` is the last index, use the last sample.
* Otherwise `a_i = y_j + (y_{j+1} − y_j) · (double(g_i − t_j) / double(t_{j+1} − t_j))`,
  per axis, with `y` the float32 values widened to float64.

Signals:
* `r = (Σ_{i<90} a_i) / 90` per axis, the rest reference over −2.00 to −0.22 s.
* `rm = |r|`. `û = r / rm`, or `(0, 0, −1)` if `rm < 1e-6`.
* Gravity: `g ← r`, then for `i = 0..249`: `g ← g + ALPHA · (a_i − g)`; `g_i = g`.
* `m_i = |a_i|`, with `|v| = sqrt(v₀·v₀ + v₁·v₁ + v₂·v₂)` in that order.
* Tilt from screen-down: `θ_i = atan2(sqrt(gx² + gy²), −gz) · RAD_TO_DEG`.
* Rotation from rest: `φ_i = angle(g_i, r)`, where
  `angle(a, b) = atan2(|a × b|, a · b) · RAD_TO_DEG` and
  `a × b = (a₁b₂ − a₂b₁, a₂b₀ − a₀b₂, a₀b₁ − a₁b₀)`.
* Dynamic magnitude: `dm_i = |a_i − g_i|`.
* Up and horizontal relative to rest: `p_i = a_i · û`, `w_i = p_i − rm`,
  `hvec_i = a_i − p_i · û`, `h_i = |hvec_i|`.
* Jerk: `J_i = |a_{i+1} − a_i| / DT`, for `i = 0..248`.

## 4. Features (39, in this order)

Segments are half-open index ranges: PRE `[0,90)`, POST `[100,250)`, EARLY `[100,150)`,
MID `[150,200)`, LATE `[200,250)`, TAIL `[225,250)`. `mean`, `rms` and `popstd` divide by
the segment length.

| # | Name | Definition |
|---|---|---|
| 0 | `pre_tilt` | tilt of `r` |
| 1 | `pre_motion` | `sqrt(Σ_PRE |a_i − r|² / 90)` |
| 2 | `pre_mag` | `rm / G` |
| 3 | `tilt_end` | mean of θ over TAIL |
| 4 | `tilt_max` | max of θ over POST |
| 5 | `tilt_min` | min of θ over POST |
| 6 | `tilt_path` | `Σ_{i=100}^{249} |θ_i − θ_{i−1}|` |
| 7 | `rot_end` | `angle(mean_TAIL(a), r)` |
| 8 | `rot_max` | max of φ over POST |
| 9 | `rot_1s` | `φ_150` |
| 10 | `rot_2s` | `φ_200` |
| 11 | `rot_path` | `Σ_{i=100}^{249} angle(g_i, g_{i−1})` |
| 12 | `late_motion` | `sqrt(mean_LATE |a_i − mean_LATE(a)|²)` |
| 13 | `tail_motion` | the same over TAIL |
| 14 | `late_mag_std` | popstd of `m` over LATE |
| 15 | `early_dyn` | rms of `dm` over EARLY |
| 16 | `mid_dyn` | rms of `dm` over MID |
| 17 | `late_dyn` | rms of `dm` over LATE |
| 18 | `dyn_peak` | max of `dm` over POST |
| 19 | `mag_dev_peak` | max of `|m_i − rm|` over POST |
| 20 | `settle` | `ln((late_dyn + 0.01) / (early_dyn + 0.01))` |
| 21 | `jerk_post` | `ln(1 + sqrt(Σ_{i=100}^{248} J_i² / 149))` |
| 22 | `jerk_late` | `ln(1 + sqrt(Σ_{i=200}^{248} J_i² / 49))` |
| 23 | `jerk_peak` | `ln(1 + max_{i=100}^{248} J_i)` |
| 24 | `up_peak` | max of `w` over POST |
| 25 | `down_peak` | min of `w` over POST |
| 26 | `vel_up_max` | `V_k = Σ_{i=100}^{k} (w_i · DT)`; max over `k = 100..199` |
| 27 | `vel_up_min` | min of `V_k` over the same range |
| 28 | `disp_up_max` | `D_k = Σ_{i=100}^{k} (V_i · DT)`; max over `k = 100..199` |
| 29 | `horiz_rms` | rms of `h` over POST |
| 30 | `horiz_peak` | max of `h` over POST |
| 31 | `vel_h_max` | `VH_k = Σ_{i=100}^{k} (hvec_i · DT)` per axis; max of `|VH_k|` over `k = 100..199` |
| 32 | `spec_lo` | `Σ_{k=1..5} P_k / (E + 1e-9)`, about 0.33–1.67 Hz |
| 33 | `spec_mid` | `Σ_{k=6..14} P_k / (E + 1e-9)`, about 2–4.67 Hz |
| 34 | `spec_hi` | `Σ_{k=15..29} P_k / (E + 1e-9)`, about 5–9.67 Hz |
| 35 | `spec_log_energy` | `ln(E / 150 + 1e-6)` |
| 36 | `spec_centroid` | `Σ_{k=1..75} P_k · (k / 3.0) / (E + 1e-9)` |
| 37 | `rot_late_std` | popstd of φ over LATE |
| 38 | `late_mag_dev` | `|mean_LATE(m) − rm|` |

The spectrum uses the 150 POST samples of `m`.
* `μ` is their mean. `x_n = (m_{100+n} − μ) · H_n`, with Hann window
  `H_n = 0.5 − 0.5 · cos(2π · n / 149)`.
* For `k = 1..75`:
  * `re_k = Σ_n x_n · C[(k·n) mod 150]`, `im_k = Σ_n x_n · S[(k·n) mod 150]`;
  * `C[j] = cos(2π · j / 150)`, `S[j] = sin(2π · j / 150)`. Each angle is computed as
    `((2.0 · π) · j) / 150`.
  * `P_k = re_k² + im_k²`.
* `E = Σ_{k=1..75} P_k`.

## 5. CNN channels (250 × 5)

For each grid index `i`: `[(m_i − rm) / G, w_i / G, h_i / G, θ_i / 180, φ_i / 180]`. All five
are invariant to how the phone is turned on the table (yaw). `test_features.py` checks that
for the 39 features too.

## 6. Models (`pickup_*.json`, format `headsdown.foreman.pickup` v1)

Every model gives a raw logit `z`.
* **logistic**: `z = intercept + Σ_j coef_j · clip((x_j − mean_j) / scale_j, ±std_clip)`,
  accumulated in feature order starting from the intercept.
* **gbdt**: `z = baseline + Σ_trees value(leaf)`. A node goes left iff `x[feature] ≤ threshold`;
  NaN follows `missing_left`. Node layout:
  `[feature, threshold, left, right, missing_left, is_leaf, value]`.
* **cnn**: three layers of zero-pad `p`, Conv1D(kernel `k`, stride `s`, Keras `(k, in, out)`
  weights in C order) and ReLU. For each output position and filter:
  `acc = Σ_j Σ_c x[t·s + j − p][c] · w[(j·in + c)·out + o]`, skipping out-of-range rows, then
  `relu(acc + b[o])`. Next comes `[mean over time ‖ max over time]` per channel, then a dense
  layer: `Σ_i v_i · w_i + b`.

Calibrated logit: `l = a · clip(z, ±30) + b`. `P(pickup) = 1 / (1 + e^−l)`.
**Verdict PICKUP iff `l ≥ threshold_logit`.** The comparison is done on logits because
probabilities saturate at 1.0 in floating point.

`pickup_model.tflite` (logistic, input `[1, 39]`) and `pickup_cnn.tflite` (input
`[1, 250, 5]`) are float32 LiteRT flatbuffers. They return the **raw** logit; calibration and
the threshold come from the JSON. They agree with the float64 path to about 1e-5 in the logit
(RESULTS.md, "Export checks").

## Versioning

Any change to sections 1–5 is a new `SPEC_VERSION`. The model files carry `feature_spec`, and
the Kotlin loader refuses a mismatch, so the app then falls back to the fail-closed
classifier.
