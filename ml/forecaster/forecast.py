#!/usr/bin/env python3
"""Foreman Cost Forecaster: predict next-hours production_cost_ema vs price, walk-forward.

Target (horizon H hours, decided at the top of hour t+1 with data through hour t):
    y_H(t) = log( mean EMA over hours t+1..t+H  /  mean ORE price in SOL over t+1..t+H )
Baseline ("current EMA"): the persistence forecast y_hat = log(EMA_now / price_now).
Models predict the change from persistence, so they only win by learning real dynamics:
    ridge   standardized linear model (on-device: 20 floats + 1 dot product)
    gbm     sklearn HistGradientBoostingRegressor
Features: current ratio and its lags, EMA trend (1h/3h/24h), mean deployed SOL and its trend,
Motherlode pot size (and rounds since the last hit), hour-of-day (sin/cos), weekend flag, price
returns (1h/6h/24h).

Walk-forward: expanding window, first fit after `--min-train-days`, refit every 24 h, predict the
next 24 hourly decision points out of sample.

Decision rule evaluation (spend-matched, same accounting as backtest.py, over the nights that
have out-of-sample forecasts):
    gate_forecast_ev    hourly: calibrated small-rig cost from the FORECAST EMA + live pot < price
    gate_ev_hourly      hourly: same formula with the CURRENT EMA (apples-to-apples baseline)
    gate_ev             per round, live EMA + live pot (what the on-chain program can do)
    gate_forecast_ema / gate_ema_hourly: the same pair without the pot term
    always_buy / always_mine / oracle_hour
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import time
from typing import Dict, List, Tuple

import numpy as np
import pandas as pd
from sklearn.ensemble import HistGradientBoostingRegressor
from sklearn.linear_model import Ridge
from sklearn.pipeline import make_pipeline
from sklearn.preprocessing import StandardScaler

import backtest as BT
import orelib as L

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "out")
FIG = os.path.join(HERE, "figures")

FEATURES = [
    "lr_now", "lr_lag1", "lr_lag3", "lr_lag24",
    "ema_trend_intra", "ema_trend_3h", "ema_trend_24h",
    "log_dep", "dep_trend_6h", "miners",
    "pot_ore", "pot_frac", "hits_24h",
    "hod_sin", "hod_cos", "weekend",
    "ret_1h", "ret_6h", "ret_24h",
]


# --------------------------------------------------------------------------- hourly table

def hourly_table(rounds: pd.DataFrame, prices: pd.DataFrame, since: str) -> pd.DataFrame:
    x = rounds[rounds["ema_warm"] & ~rounds["refund"]].copy()
    x = x[x["time"] >= pd.Timestamp(since, tz="UTC") - pd.Timedelta(days=2)]
    x["hour"] = x["time"].dt.floor("h")
    h = x.groupby("hour").agg(
        ema_last=("ema_after", "last"), ema_mean=("ema_after", "mean"),
        dep=("total_deployed", "mean"), miners=("num_winners", "mean"),
        pot_last=("pot_before", "last"), hits=("motherlode_hit", "sum"), n=("round_id", "size"))
    px = prices.copy()
    px["hour"] = pd.to_datetime(px["ts"], unit="s", utc=True)
    h = h.join(px.set_index("hour")["close"], how="inner")
    full = pd.date_range(h.index.min(), h.index.max(), freq="h", tz="UTC")
    h = h.reindex(full)
    h[["close", "ema_last", "pot_last"]] = h[["close", "ema_last", "pot_last"]].ffill()
    h["ema_mean"] = h["ema_mean"].fillna(h["ema_last"])
    h["dep"] = h["dep"].ffill()
    h["miners"] = h["miners"].ffill()
    h["hits"] = h["hits"].fillna(0)
    h["n"] = h["n"].fillna(0)
    h.index.name = "hour"

    ema_sol = h["ema_last"] / L.LAMPORTS_PER_SOL
    h["lr_now"] = np.log(ema_sol / h["close"])
    h["lr_lag1"] = h["lr_now"].shift(1)
    h["lr_lag3"] = h["lr_now"].shift(3)
    h["lr_lag24"] = h["lr_now"].shift(24)
    le = np.log(h["ema_mean"])
    h["ema_trend_intra"] = np.log(h["ema_last"]) - le
    h["ema_trend_3h"] = le - le.shift(3)
    h["ema_trend_24h"] = le - le.shift(24)
    h["log_dep"] = np.log(h["dep"] / L.LAMPORTS_PER_SOL)
    h["dep_trend_6h"] = h["log_dep"] - h["log_dep"].shift(6)
    h["pot_ore"] = h["pot_last"] / L.ONE_ORE
    h["pot_frac"] = h["pot_ore"] / L.MOTHERLODE_ODDS  # extra ORE per round in expectation
    h["hits_24h"] = h["hits"].rolling(24, min_periods=1).sum()
    hod = h.index.hour
    h["hod_sin"] = np.sin(2 * np.pi * hod / 24)
    h["hod_cos"] = np.cos(2 * np.pi * hod / 24)
    h["weekend"] = (h.index.dayofweek >= 5).astype(float)
    lp = np.log(h["close"])
    h["ret_1h"] = lp - lp.shift(1)
    h["ret_6h"] = lp - lp.shift(6)
    h["ret_24h"] = lp - lp.shift(24)
    return h


def add_targets(h: pd.DataFrame, horizons: Tuple[int, ...]) -> pd.DataFrame:
    for H in horizons:
        ema_f = h["ema_mean"][::-1].rolling(H, min_periods=H).mean()[::-1].shift(-1)
        px_f = h["close"][::-1].rolling(H, min_periods=H).mean()[::-1].shift(-1)
        h[f"y_{H}"] = np.log((ema_f / L.LAMPORTS_PER_SOL) / px_f)
        h[f"ema_next_{H}"] = ema_f
    return h


# --------------------------------------------------------------------------- walk-forward

def make_models(seed: int = 0) -> Dict[str, object]:
    return {
        "ridge": make_pipeline(StandardScaler(), Ridge(alpha=10.0)),
        "gbm": HistGradientBoostingRegressor(max_depth=3, learning_rate=0.05, max_iter=300,
                                             min_samples_leaf=40, l2_regularization=1.0, random_state=seed),
    }


def walk_forward(h: pd.DataFrame, H: int, since: str, min_train_days: int = 7,
                 refit_hours: int = 24) -> pd.DataFrame:
    """Out-of-sample predictions of y_H at every hour after the warm-up; persistence included."""
    d = h[h.index >= pd.Timestamp(since, tz="UTC")].copy()
    ok = d[FEATURES].notna().all(axis=1) & d[f"y_{H}"].notna()
    d = d[ok]
    if len(d) < 48:  # e.g. the offline sample: too short for 24h lags plus a training window
        return pd.DataFrame()
    target = d[f"y_{H}"] - d["lr_now"]  # predict the change vs persistence
    start = d.index.min() + pd.Timedelta(days=min_train_days)
    cut_points = pd.date_range(start, d.index.max(), freq=f"{refit_hours}h", tz="UTC")
    rows = []
    for c in cut_points:
        # Train only on targets fully observed before the cut: y_H at t uses hours t+1..t+H.
        train = d[d.index + pd.Timedelta(hours=H) < c]
        test = d[(d.index >= c) & (d.index < c + pd.Timedelta(hours=refit_hours))]
        if len(train) < 48 or len(test) == 0:
            continue
        preds = {"persistence": np.zeros(len(test))}
        for name, m in make_models().items():
            m.fit(train[FEATURES].to_numpy(), target.loc[train.index].to_numpy())
            preds[name] = m.predict(test[FEATURES].to_numpy())
        for i, t in enumerate(test.index):
            row = {"hour": t, "y": d.at[t, f"y_{H}"], "lr_now": d.at[t, "lr_now"],
                   "close": d.at[t, "close"], "pot_ore": d.at[t, "pot_ore"], "train_rows": len(train)}
            for name, p in preds.items():
                row[f"yhat_{name}"] = d.at[t, "lr_now"] + p[i]
            rows.append(row)
    return pd.DataFrame(rows).set_index("hour")


def score(pred: pd.DataFrame) -> Dict[str, Dict[str, float]]:
    out = {}
    base_mae = float(np.mean(np.abs(pred["y"] - pred["yhat_persistence"])))
    for c in [c for c in pred.columns if c.startswith("yhat_")]:
        err = pred["y"] - pred[c]
        name = c[5:]
        mae = float(np.mean(np.abs(err)))
        # Decision agreement: is next-hours EMA below price (ratio < 1)?
        acc = float(np.mean((pred[c] < 0) == (pred["y"] < 0)))
        out[name] = {"mae_log": mae, "rmse_log": float(np.sqrt(np.mean(err ** 2))),
                     "mae_skill_vs_persistence": 1 - mae / base_mae, "sign_accuracy": acc, "n": int(len(pred))}
    return out


def diebold_mariano_block(pred: pd.DataFrame, a: str, b: str, block: int = 24, n_boot: int = 2000,
                          seed: int = 3) -> Dict[str, float]:
    """Block-bootstrap CI of MAE(a) - MAE(b); negative means `a` is more accurate."""
    rng = np.random.default_rng(seed)
    da = np.abs(pred["y"] - pred[f"yhat_{a}"]).to_numpy()
    db = np.abs(pred["y"] - pred[f"yhat_{b}"]).to_numpy()
    diff = da - db
    n = len(diff)
    nb = int(np.ceil(n / block))
    starts = rng.integers(0, max(n - block, 1), size=(n_boot, nb))
    idx = (starts[:, :, None] + np.arange(block)[None, None, :]).reshape(n_boot, -1)[:, :n]
    boots = diff[idx].mean(1)
    return {"mean_diff": float(diff.mean()), "ci95_lo": float(np.percentile(boots, 2.5)),
            "ci95_hi": float(np.percentile(boots, 97.5))}


# --------------------------------------------------------------------------- decisions

def forecast_decisions(r: pd.DataFrame, pred: pd.DataFrame, model: str, A: float, costs: L.Costs,
                       use_pot: bool) -> np.ndarray:
    """Hourly gate from a forecast: the decision for hour t+1 is made at its top."""
    yhat = pred[f"yhat_{model}"]
    ema_hat = np.exp(yhat) * pred["close"] * L.LAMPORTS_PER_SOL  # lamports per ORE
    # Forecast rows are indexed by the hour whose close is the latest known price; they gate the
    # NEXT hour.
    ema_for_hour = pd.Series(ema_hat.to_numpy(), index=pred.index + pd.Timedelta(hours=1))
    first_idx = r.groupby("hour").head(1)
    hours = first_idx["hour"]
    ema_h = hours.map(ema_for_hour)
    pot = first_idx["pot_before"].to_numpy(dtype=float) if use_pot else None
    buy = first_idx["price_known"].to_numpy() * (1 + costs.buy_cost_bps / 1e4)
    est = L.user_cost_estimate(ema_h.to_numpy(dtype=float), A, costs, pot, True)
    dec_hour = pd.Series(np.where(np.isnan(est), False, est < buy), index=hours.to_numpy())
    return r["hour"].map(dec_hour).fillna(False).to_numpy(dtype=bool)


def evaluate_rules(prep: Dict[str, object], preds: pd.DataFrame, model: str, budgets, costs: L.Costs
                   ) -> Dict[str, object]:
    r = prep["rounds"]
    n_med = prep["rounds_per_night"]
    covered = set((preds.index + pd.Timedelta(hours=1)))
    by_night = r.groupby("night")["hour"].agg(lambda s: set(s) <= covered)
    nights = by_night[by_night].index
    rr = r[r["night"].isin(nights)].reset_index(drop=True)
    out = {"nights": int(len(nights)), "rounds": int(len(rr)), "budgets": {}}
    if len(rr) == 0:
        return out
    for B in budgets:
        A = B * L.LAMPORTS_PER_SOL / n_med
        dec = BT.decisions_for(rr, A, costs)
        dec["gate_forecast_ev"] = forecast_decisions(rr, preds, model, A, costs, use_pot=True)
        dec["gate_forecast_ema"] = forecast_decisions(rr, preds, model, A, costs, use_pot=False)
        keep = ["always_buy", "always_mine", "gate_ema_hourly", "gate_forecast_ema", "gate_ev_hourly",
                "gate_forecast_ev", "gate_ev", "gate_ev_arm", "oracle_hour"]
        dec = {k: dec[k] for k in keep}
        s = BT.summarize(BT.evaluate(rr, A, costs, dec), rr, costs)
        s_real = BT.summarize(BT.evaluate(rr, A, costs, dec, motherlode="realized"), rr, costs, n_boot=500)
        # Paired night bootstrap of the forecast gate against each rule it must beat.
        per = BT.evaluate(rr, A, costs, dec)
        rng = np.random.default_rng(5)
        idx = rng.integers(0, len(per["always_buy"]), size=(2000, len(per["always_buy"])))

        def eff(name):
            p = per[name]
            return p["sol"].to_numpy()[idx].sum(1) / p["ore"].to_numpy()[idx].sum(1)

        paired = {}
        for other in ("gate_ev_hourly", "gate_ev", "gate_ev_arm", "always_buy", "always_mine"):
            diff = eff("gate_forecast_ev") / eff(other) - 1
            paired[other] = {"mean": float(100 * diff.mean()), "ci95_lo": float(100 * np.percentile(diff, 2.5)),
                             "ci95_hi": float(100 * np.percentile(diff, 97.5))}
        out["budgets"][str(B)] = {
            "per_round_lamports": A,
            "table": s.round(6).to_dict(orient="index"),
            "table_realized": s_real.round(6).to_dict(orient="index"),
            "forecast_ev_vs_pct": paired,
        }
    return out


# --------------------------------------------------------------------------- export

def fold_linear(mean: np.ndarray, scale: np.ndarray, coef: np.ndarray, intercept: float) -> Tuple[np.ndarray, float]:
    """Fold StandardScaler into ridge weights: w'x + b' == coef . ((x - mean) / scale) + intercept."""
    w = np.asarray(coef, dtype=float) / np.asarray(scale, dtype=float)
    b = float(intercept - np.sum(w * np.asarray(mean, dtype=float)))
    return w, b


def export_linear(h: pd.DataFrame, H: int, since: str, path: str) -> Dict[str, object]:
    """Fit the ridge model on all data and export it as plain JSON (scaler + coefficients)."""
    d = h[h.index >= pd.Timestamp(since, tz="UTC")]
    d = d[d[FEATURES].notna().all(axis=1) & d[f"y_{H}"].notna()]
    m = make_pipeline(StandardScaler(), Ridge(alpha=10.0))
    m.fit(d[FEATURES].to_numpy(), (d[f"y_{H}"] - d["lr_now"]).to_numpy())
    sc, rg = m.named_steps["standardscaler"], m.named_steps["ridge"]
    w, b = fold_linear(sc.mean_, sc.scale_, rg.coef_, float(rg.intercept_))
    spec = {"model": "ridge", "horizon_hours": H, "target": "log(EMA/price) change vs persistence",
            "features": FEATURES, "mean": sc.mean_.tolist(), "scale": sc.scale_.tolist(),
            "coef": rg.coef_.tolist(), "intercept": float(rg.intercept_), "train_rows": int(len(d)),
            "train_period": [str(d.index.min()), str(d.index.max())],
            "formula": "yhat = lr_now + intercept + sum(coef[i] * (x[i] - mean[i]) / scale[i])",
            # Scaler folded into the weights: one Dense(19 -> 1) layer, or 20 floats in Kotlin.
            "folded_weights": w.tolist(), "folded_bias": b,
            "folded_formula": "yhat = lr_now + folded_bias + sum(folded_weights[i] * x[i])"}
    with open(path, "w") as f:
        json.dump(spec, f, indent=1)
    return spec


# --------------------------------------------------------------------------- figure

def fig_forecast(pred: pd.DataFrame, path: str, H: int) -> None:
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    P = BT.PALETTE
    fig, ax = plt.subplots(figsize=(9, 4.2), dpi=130, facecolor=P["surface"])
    BT._style(ax)
    last = pred.iloc[-24 * 10:]
    ax.plot(last.index, np.exp(last["y"]), color=P["ink"], linewidth=1.6, label="Realized EMA / price")
    ax.plot(last.index, np.exp(last["yhat_persistence"]), color=P["blue"], linewidth=1.2,
            label="Current EMA / price (persistence)")
    ax.plot(last.index, np.exp(last["yhat_ridge"]), color=P["orange"], linewidth=1.2, label="Ridge forecast")
    ax.axhline(1.0, color=P["ink2"], linewidth=1)
    ax.set_ylabel(f"Next {H}h production-cost EMA / price", color=P["ink"])
    ax.set_title(f"Out-of-sample {H}h-ahead forecasts, last 10 days: the ratio climbs with the "
                 "Motherlode pot and drops at each hit", loc="left", color=P["ink"], fontsize=10)
    ax.legend(frameon=False, fontsize=8, loc="upper left")
    fig.autofmt_xdate()
    fig.tight_layout()
    fig.savefig(path, facecolor=P["surface"])
    plt.close(fig)


# --------------------------------------------------------------------------- main

def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--sample", action="store_true")
    ap.add_argument("--since", default=BT.REGIME_START)
    ap.add_argument("--min-train-days", type=int, default=7)
    ap.add_argument("--horizons", type=int, nargs="*", default=[1, 8])
    ap.add_argument("--no-figures", action="store_true")
    args = ap.parse_args(argv)
    t0 = time.time()
    costs = L.Costs()
    rounds = L.load_rounds(sample=args.sample)
    prices = L.load_prices(sample=args.sample)
    since = args.since if not args.sample else str(rounds["time"].min().date())
    h = add_targets(hourly_table(rounds, prices, since), tuple(args.horizons))
    res: Dict[str, object] = {"horizons": {}, "config": {"since": since, "min_train_days": args.min_train_days,
                                                          "features": FEATURES}}
    preds_by_h = {}
    for H in args.horizons:
        pred = walk_forward(h, H, since, args.min_train_days)
        if pred.empty:
            print(f"H={H}: not enough data for walk-forward")
            continue
        preds_by_h[H] = pred
        sc = score(pred)
        dm = {m: diebold_mariano_block(pred, m, "persistence") for m in ("ridge", "gbm")}
        res["horizons"][str(H)] = {"scores": sc, "mae_diff_vs_persistence": dm,
                                   "period": [str(pred.index.min()), str(pred.index.max())]}
        print(f"\n== H={H}h walk-forward ({len(pred)} hourly forecasts, {pred.index.min():%Y-%m-%d} .. "
              f"{pred.index.max():%Y-%m-%d})")
        print(pd.DataFrame(sc).T[["mae_log", "rmse_log", "mae_skill_vs_persistence", "sign_accuracy"]]
              .round(4).to_string())
        for m, v in dm.items():
            print(f"   MAE({m}) - MAE(persistence) = {v['mean_diff']:+.4f}  95% CI [{v['ci95_lo']:+.4f}, {v['ci95_hi']:+.4f}]")

    if 1 in preds_by_h:
        prep = BT.prepare(args.sample, L.ShiftWindow())
        pred1 = preds_by_h[1]
        # Pick the model with the best out-of-sample MAE for the decision test (not in-sample).
        best = min(("ridge", "gbm"), key=lambda m: res["horizons"]["1"]["scores"][m]["mae_log"])
        res["decision_model"] = best
        ev = evaluate_rules(prep, pred1, best, (0.04, 0.36, 1.0), costs)
        res["decisions"] = ev
        print(f"\n== decision rules on {ev['nights']} out-of-sample nights (model: {best})")
        for B, v in ev["budgets"].items():
            t = pd.DataFrame(v["table"]).T
            print(f"-- budget {B} SOL/night deployed")
            print(t[["eff_sol_per_ore", "vs_always_buy_pct", "ci95_lo_pct", "ci95_hi_pct", "mined_round_share"]]
                  .round(4).to_string())
            for other, fc in v["forecast_ev_vs_pct"].items():
                print(f"   gate_forecast_ev vs {other:15s}: {fc['mean']:+.2f}% "
                      f"[{fc['ci95_lo']:+.2f}, {fc['ci95_hi']:+.2f}] (negative = forecast cheaper)")

        os.makedirs(OUT, exist_ok=True)
        suffix = "_sample" if args.sample else ""
        res["export"] = export_linear(h, 1, since, os.path.join(OUT, f"forecaster_ridge_h1{suffix}.json"))
        if not args.no_figures:
            fig_dir = os.path.join(OUT, "figures_sample") if args.sample else FIG
            os.makedirs(fig_dir, exist_ok=True)
            fig_forecast(pred1, os.path.join(fig_dir, "forecast_walkforward.png"), 1)
    os.makedirs(OUT, exist_ok=True)
    res["runtime_s"] = round(time.time() - t0, 1)
    name = "forecast_results_sample.json" if args.sample else "forecast_results.json"
    with open(os.path.join(OUT, name), "w") as f:
        json.dump(res, f, indent=1, default=str)
    print(f"\nwrote {os.path.join(OUT, name)} in {res['runtime_s']}s")
    return 0


if __name__ == "__main__":
    sys.exit(main())
