# Model card: Foreman Shift Planner (rhythm model, v1)

| | |
|---|---|
| **What it does** | Learns when **this phone** sits idle from the app's own log. It forecasts P(idle) for every 15-minute slot of the next 24 h, proposes the next shift window (ending by the next alarm), and splits the week's budget across the next 7 nights. |
| **Model** | A per-slot Beta-Bernoulli posterior with day-of-week effects: recency-weighted counts, shrunk all days → weekday/weekend → day of week. It is fitted on the phone from the user's own log, with no population model and no training at runtime. |
| **Shipped artifact** | `model/planner_params.json` (2.9 KB): the tuned parameters and the population prior curve. |
| **Runs on** | The phone, in pure Kotlin: `xyz.headsdown.ml.planner.RhythmShiftPlanner`, behind the `ShiftPlanner` interface. |
| **Status** | Tuned and evaluated on **synthetic users only** (five archetypes). See [RESULTS.md](RESULTS.md). |
| **Inputs** | The log in [LOG_SCHEMA.md](LOG_SCHEMA.md): screen on/off, unlocks, charging, face-down, the next alarm, shift outcomes. No UsageStats, no location, nothing from other apps. |

## How it works

1. **Slot labels.** Each 15-minute local slot is IDLE, BUSY or MISSING ([LOG_SCHEMA.md](LOG_SCHEMA.md)).
   MISSING slots (the app was not watching) are skipped, not counted as busy.
2. **Posterior.** Every past label is weighted by `0.5^(age_days / 56)`.
   * Counts are smoothed over neighbouring slots (kernel 0.25, 0.5, 1, 0.5, 0.25).
   * Three levels each shrink toward the one above:
     `all days (k0 = 2, toward the population prior) → weekday/weekend (k1 = 64) → day of week (k2 = 256)`.
   * `P(idle)` is the day-of-week level. Every value is a weighted count of the user's own
     nights, so it can be explained in a sentence ("idle on 9 of your last 10 Tuesdays at
     23:15").
3. **The next window** is the run of slots, at least 2 h long, that maximizes **expected
   completed dark time**: `length × Π p^0.5`.
   * Every extra slot adds dig time, but it must also stay idle, or the shift breaks.
   * Exponent 0.5 discounts the independence assumption: nights are correlated.
   * Runs start and end on slots with P(idle) ≥ 0.8.
   * The window ends at the next alarm when the alarm falls inside it (the morning reveal is
     the clock-out).
4. **Auto-arm** (zero taps when the phone is laid face-down on the charger inside the window)
   needs at least 7 nights of history and every slot's lower bound
   `P − 1·sd` (sd from the user's own day-type evidence) to be ≥ 0.6.
5. **The weekly split** gives whole dig chunks to the next 7 nights by the D'Hondt method, in
   proportion to each night's expected idle rounds (78 s each). No night gets more than
   `cap_shift` or more than one chunk per expected idle round. The total never exceeds the
   week's remaining cap.

## Bounds: it only proposes

* A window becomes an on-chain plan only through `ForemanGate.tighten`. That clamps
  `max_ev_cost ≤ cap_max_cost` and `dig_lamports ≤ cap_round`, ends the window by
  `caps_expiry_ts`, and drops any proposal it cannot make valid. The program would refuse it
  anyway (`PlanExceedsCaps`, `OutsideWindow`, `CapsExpired`).
* The split is advisory and bounded by the wallet caps. It never raises one.
* The planner cannot arm anything by itself. Auto-arm still needs the phone actually
  face-down, screen off and on the charger inside a confident window, and the shift then digs
  only on fresh heartbeats.

## Evaluation (synthetic archetypes; full tables in [RESULTS.md](RESULTS.md))

Five archetypes, simulated minute by minute and written out as the exact log the app records:
* a regular sleeper with weekday alarms;
* a 4-on/4-off night-shift worker, on an 8-day cycle that does not follow the week;
* an irregular sleeper;
* a drawer "second phone";
* a night checker.

Parameters are tuned on 30 users and reported on 30 different users (70 days each). Each day at
18:00 the planner sees only the log so far.

| Next 24 h | Brier | Log loss |
|---|---|---|
| population prior only | 0.1827 | 0.5433 |
| **shipped posterior** | **0.1650** | **0.4849** |
| GBDT comparator (optional) | 0.1636 | 0.4817 |

Windows are scored as the shifts they would produce. The simulated user arms at the first 2-hour
idle stretch inside the window; a pickup before the window ends breaks the shift.

| Window | Shifts completed | Dark hours a night | Completed dark hours |
|---|---|---|---|
| fixed 23:00–07:00 | 57.0% | 5.54 | 3.76 |
| yesterday's idle block | 49.7% | 6.34 | 3.38 |
| **shipped planner** | **90.6%** | 4.46 | **4.06** |
| GBDT comparator | 94.5% | 4.44 | 4.25 |

The planner's windows are shorter and end before waking (median end −90 min). It trades about
an hour of raw dark time for shifts that finish and count toward the streak.

**What the numbers say, honestly:**
* **Day-of-week effects barely help these archetypes.** The tuned shrinkage is heavy
  (k1 = 64, k2 = 256), and the no-day-of-week variant is within 0.0005 Brier. The structure is
  kept because a real user's weekend can differ more than these simulations do, and the
  shrinkage lets the data decide.
* **The optional GBDT is slightly better** (Brier −0.0014, +0.19 completed dark hours a
  night). The posterior ships anyway:
  * it needs no population-trained model on the phone;
  * it starts from the user's first night;
  * every number it shows is a count of the user's own nights.
  The GBDT stays an evaluation comparator.
* **Shift workers on non-weekly rotas are poorly served.** They get 1.4 dark hours a night and
  14% coverage: a weekly model cannot see an 8-day cycle. For irregular sleepers the windows
  cover about a third of the longest idle block (2.5 dark hours a night).
* **Night checks break shifts.** A pickup at 03:00 breaks the shift wherever the window is.
  The planner cannot predict a random check; it can only avoid windows that usually contain one.
* **Cold start** works from the population prior: completed dark hours climb from 2.8 h
  (1 night of history) to about 4 h (7 nights).
* **If the app only watches 20:00–10:00,** the night forecast holds (completed shifts 90.1%)
  and daytime falls back to the prior.

## Limitations

* Synthetic users are not real people. The archetypes are plausible rhythms, not a measured
  population. Real logs will have lost events (the OS killing the app), travel across time
  zones, and changes of habit.
* The posterior treats slots independently. The survival rule's exponent is a tuned correction,
  not a model of correlation.
* "Idle" means screen off and no unlock. It does not mean asleep, and the app makes no sleep or
  health claim.

## Retune

```sh
cd ml/foreman && .venv/bin/python -m planner.evaluate   # ~4 min: tune, evaluate, export, sync android/ml
cd android && ./gradlew :ml:test
```
