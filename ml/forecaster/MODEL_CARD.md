# Model card: Foreman Cost Forecaster (v0, advisory only)

| | |
|---|---|
| **Status** | **Advisory.** It never gates a dig. It failed the plan's promotion test (see Evaluation). |
| **What ships instead** | The Motherlode-aware on-chain rule `ema_ev < plan.max_ev_cost` (see "The rule that ships"). |
| **Artifact** | `model/forecaster_ridge_h1.json` (2.5 KB): ridge regression, 19 features, with the scaler folded into the weights. |
| **Owner / code** | `ml/forecaster/forecast.py`; tests in `tests/test_forecast.py` |
| **Trained on** | 1,144 hourly rows, 2026-08-13 01:00 to 2026-09-29 16:00 UTC |

## Intended use

The model gives a one-hour-ahead forecast of the ratio `production_cost_ema / ORE price` (in SOL), which says whether mining is likely to stay cheaper than buying. Heads Down uses it only for:

- the plain-language plan line ("cheaper to mine than buy until about 04:00");
- choosing which nights of a weekly budget to spend on;
- the morning accountability card ("Foreman forecast 0.71 SOL/ORE, realized 0.69").

**It is not used for** the dig decision, deploy sizes or caps. Even if it were, the Foreman can only *tighten* the limits the wallet signed, and ORE's native `max_production_cost` stays the outer guardrail.

## Data

- **Source:** every ORE round's `ResetEvent` from api.ore.com: total deployed, deployed on the winning tile, protocol fee, ORE minted, Motherlode payout, and split or solo. Price is hourly ORE/SOL from the Orca pool via GeckoTerminal. The `production_cost_ema` is **replayed exactly** from `reset.rs`'s integer recursion. It matches the live Board account to the lamport and api.ore.com's `production_cost` to a median ratio of 0.9998 (see `validate.py` and RESULTS §1).
- **Window:** 2026-08-13 to 2026-09-29, the fee regime after ORE ended parimutuel payouts. Earlier rounds follow different rules and are excluded.
- **Aggregation:** hourly bars. The EMA uses its last and mean values in the hour, deployed SOL and miner count are means, and the Motherlode pot is the value at the end of the hour. The price is the candle close; missing candles are forward-filled.
- **Privacy:** only public chain and market data. No user or sensor data is involved.

## Target and features

**Target:** `y_H(t) = log(mean EMA over hours t+1..t+H / mean price over t+1..t+H)`, for H = 1 (the decision model) and H = 8 (night-level, reported only). The models predict `y − lr_now`, the change relative to persistence.

**Features (19):**

| Group | Features |
|---|---|
| Current ratio and lags | `lr_now`, `lr_lag1`, `lr_lag3`, `lr_lag24` |
| EMA trend | within the hour, over 3h, over 24h |
| Crowding | log mean SOL deployed, its 6h trend, mean miners per round |
| Motherlode | pot in ORE, pot/500 (expected extra ORE per round), hits in the last 24h |
| Calendar | hour of day (sin, cos), weekend flag |
| Price | 1h, 6h and 24h log returns |

**Largest standardized ridge coefficients:**

- `lr_now` −0.033: mean reversion of the ratio.
- `pot_ore` / `pot_frac` +0.020 each: the ratio drifts up while the pot grows.
- `ema_trend_intra` +0.010 and `ema_trend_24h` −0.008.

Hour of day (|coef| ≈ 0.001), weekend (0.000) and price returns (≤ 0.0035) carry almost no weight: there is no useful time-of-night seasonality in this window. This matches the sawtooth in `figures/forecast_walkforward.png`. The ratio climbs as the pot grows and crowding follows it (corr(deployed, pot) = 0.71), then collapses at each hit.

## Training and validation

- **Models:** ridge regression (`StandardScaler` + `Ridge(alpha=10)`) and `HistGradientBoostingRegressor` (depth 3, 300 iterations, learning rate 0.05, `min_samples_leaf` 40, L2 1.0).
- **Walk-forward:** the window expands with a 7-day warm-up and a refit every 24 hours, predicting the next 24 hourly points out of sample. A fit at cut `c` only uses rows whose H-hour target was complete before `c`; `test_walk_forward_trains_only_on_resolved_targets` checks this.
- **Uncertainty:** a 24-hour block bootstrap of the MAE difference, and a paired night bootstrap for the decision metrics.
- The decision test used the model with the best out-of-sample MAE, chosen before the decision test, not in-sample.

## Evaluation

### As a forecast

976 out-of-sample hours, 2026-08-20 to 2026-09-29.

| H | Model | MAE (log) | Skill vs persistence | 95% CI of ΔMAE | Sign accuracy (EMA < price) |
|---|---|---|---|---|---|
| 1h | persistence | 0.0259 | | | 97.1% |
| 1h | **ridge** | **0.0235** | **+9.2%** | [−0.0035, −0.0012] | 98.0% |
| 1h | gradient boosting | 0.0242 | +6.6% | [−0.0027, −0.0007] | 97.5% |
| 8h | persistence | 0.0735 | | | 91.6% |
| 8h | ridge | 0.0740 | −0.6% | [−0.0071, +0.0080] | 91.3% |
| 8h | gradient boosting | 0.0765 | −4.0% | [−0.0052, +0.0110] | 90.8% |

### As a gate

Tested on 40 out-of-sample nights with spend-matched accounting (RESULTS §3). The gate is ridge + pot term, decided hourly. Each figure is its paired difference in effective SOL per ORE; negative means the forecast gate is cheaper.

| Nightly budget | vs always buy | vs hourly current-EMA gate | vs live per-round rule (ships) | vs arm-time on-chain rule |
|---|---|---|---|---|
| 0.04 SOL (target size) | 0.00%: no gate ever opens | 0.00% | 0.00% | 0.00% |
| 0.36 SOL | −0.27% [−0.56, −0.06] | −0.23% [−0.42, −0.05] | **+0.03% [−0.13, +0.18]** | −0.12% [−0.32, +0.06] |
| 1 SOL | −0.80% [−1.49, −0.25] | −0.23% [−0.45, −0.06] | **+0.13% [−0.09, +0.32]** | +0.02% [−0.28, +0.30] |

**The promotion rule was:** beat "always buy", "always mine" and the simple current-EMA gate. The forecaster beats the *hourly* current-EMA gate by about 0.2%. It does not beat the gate the program actually runs, which reads the live EMA and live pot on every dig at no cost. At the product's real budget it has nothing to act on. **Result: demoted to advisory**, as the plan requires.

## The rule that ships

The rule reads only `Board.production_cost_ema` and `Treasury.motherlode` (layout checked against live accounts):

```
ema_ev = ema * 6 * 500 * 10^11 / (5 * (500 * 10^11 + pot))       // u128, checked; = ema * 1.2 / (1 + pot/500)
dig iff ema_ev < plan.max_ev_cost
```

At arm time the phone signs:

```
max_ev_cost = price_at_arm * (1 + buy_cost) * 0.09504 / k
k = (0.10504 * A + crank_fee) / (A * (1 - refining))
```

`A` is the per-dig deposit (at least 0.001 SOL recommended). The phone key can only lower `max_ev_cost` below the wallet-signed ceiling.

Python reference implementations are `orelib.ema_ev_lamports` and `orelib.ev_threshold_lamports`. They agree with the float gate on more than 99.5% of random states and differ only within 0.2% of the boundary (`test_on_chain_threshold_matches_float_gate`).

In the backtest, freezing the price at arm time cost between 0% and 0.15% against hourly price updates.

## On-device export

- **What exists:** `model/forecaster_ridge_h1.json` holds the raw scaler and coefficients plus `folded_weights` (19) and `folded_bias`, with `yhat = lr_now + folded_bias + Σ wᵢ·xᵢ`. `test_folded_weights_reproduce_pipeline` checks the folded form against the sklearn pipeline to 1e-10. On Android that is 20 floats and one dot product in Kotlin, fed by hourly aggregates served by api.ore.com or the planned Heads Down indexer.
- **LiteRT:** the folded model is exactly one `Dense(19 → 1)` layer. To put it through the LiteRT pipeline alongside the other Foreman models, build `keras.Sequential([Dense(1, input_shape=(19,))])` with these weights, convert it with `tf.lite.TFLiteConverter.from_keras_model`, sign it, and ship it in the app release (no remote code). **No `.tflite` was produced here.** TensorFlow is not installed in this python3.9 environment, and an advisory 20-float linear model does not justify a runtime dependency. The gradient-boosting model is not exported at all: it was worse than ridge.
- **Inputs on device:** the hourly bar features come from api.ore.com or the planned Heads Down indexer. The phone does not compute them from sensors. If the inputs are more than 2 hours old, the card shows "no forecast" rather than a stale number.

## Limits and failure modes

- **Short history:** 47 days in one fee regime. ORE changes parameters often (Reserve share, round length, split and solo rules). Every such change invalidates the model. The program-side circuit breaker on ORE layout hashes should also blank the forecast.
- **Price:** one pool and hourly candles. The model has no real price-forecasting skill (the price-return features carry little weight). Its skill is in the crowding and pot dynamics.
- **Regime of the pot:** Motherlode hits are 1-in-500 random draws. The model can learn the drift between hits, never when a hit will come.
- **Eight-hour horizon:** no skill. Do not present night-level forecasts as more than persistence.
- **Feedback:** if many Heads Down rigs follow the same rule, they add crowding when the rule opens. The model is trained on a world without them.

## Monitoring and path back to the decision loop

- Every morning, log the forecast and the realized ratio for the shift (the accountability card) and keep rolling MAE against persistence. If 14-day MAE skill turns negative, show "Foreman forecast unavailable".
- Retrain weekly on the archived history (`fetch.py` merges `/stats/history` and resets on every run).
- **Promotion criterion (unchanged):** on at least 60 days out of sample, the forecast gate must beat the live on-chain rule, with a paired-bootstrap 95% CI entirely below zero, at the budgets users actually run.
