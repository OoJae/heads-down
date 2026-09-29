#!/usr/bin/env python3
"""Mine-or-buy backtest for a small Heads Down rig, over real ORE rounds.

Question: with a small nightly budget, does "mine only when the gate says mining is cheaper than
buying, otherwise buy the rest at market" beat "always buy" and "always mine" on effective SOL
per ORE, and how much night-to-night variance does a small budget face?

Budget. B = SOL deployed per night (the shift cap funded into the user's ORE Automation, no
reload). Per-round deposit A = B / median rounds per night. Every strategy spends the same SOL:
each round is a "slot" worth c = E[loss rate] * A + crank fee. A mined slot pays the real
(admin + protocol) fee on A plus the crank fee and earns the real pro-rata ORE; a bought slot
spends c on ORE at the morning clock-out price (plus swap costs). So the effective price
sum(SOL) / sum(ORE) compares strategies directly.

Strategies (decisions use only data visible at deploy time; see orelib.attach_known_price):
  always_buy         buy every slot at clock-out
  always_mine        mine every round
  gate_naive         mine iff Board.production_cost_ema < market price (ORE-native
                     max_production_cost set to the price; ignores admin/refining/crank fees)
  gate_ema           mine iff the calibrated small-miner cost estimate from the live EMA is below
                     the buy price (the "current-EMA gate")
  gate_ev            gate_ema plus the live Motherlode pot: E[ORE/round] = 1 + pot/500
  oracle_hour        hindsight upper bound: per hour, mine iff that hour's real expected cost
                     (real crowding, pot EV, not the RNG) beat the real clock-out price

Outputs: out/backtest_results.json, figures/*.png, and a printed summary.
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import time
from dataclasses import asdict
from typing import Dict, List, Optional

import numpy as np
import pandas as pd

import orelib as L

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "out")
FIG = os.path.join(HERE, "figures")

REGIME_START = "2026-08-13"  # first full day after ORE removed parimutuel payouts (Aug 12)
HEADLINE_BUDGETS = (0.02, 0.04, 0.05)
SWEEP_BUDGETS = (0.01, 0.02, 0.04, 0.05, 0.1, 0.2, 0.36, 0.5, 1.0, 2.0, 5.0, 10.0)
BASE_STRATEGIES = ("always_buy", "always_mine", "gate_naive", "gate_ema", "gate_ema_hourly", "gate_ev",
                   "gate_ev_hourly", "gate_ev_arm", "oracle_hour")


# --------------------------------------------------------------------------- data prep

def prepare(sample: bool, window: L.ShiftWindow, since: str = REGIME_START,
            min_frac: float = 0.9) -> Dict[str, object]:
    rounds = L.load_rounds(sample=sample)
    prices = L.load_prices(sample=sample)
    r = L.attach_known_price(rounds, prices)
    r = r[r["ema_warm"] & r["price_known"].notna() & ~r["refund"]]
    if not sample:
        r = r[r["time"] >= pd.Timestamp(since, tz="UTC")]
    r = r.copy()
    r["night"] = window.assign(r)
    r = r[r["night"].notna()]
    counts = r.groupby("night").size()
    if len(counts) == 0:
        raise SystemExit("no complete nights in the data window")
    full = counts[counts >= min_frac * counts.median()].index
    r = r[r["night"].isin(full)].copy()

    # Clock-out: the moment the shift ends; the morning buy leg executes at that hour's open.
    night_end = {}
    last_px_ts = int(prices["ts"].max()) + 3600
    for n in full:
        end_local = pd.Timestamp(n) + pd.Timedelta(hours=window.end_hour_local)
        if window.start_hour_local > window.end_hour_local:
            end_local += pd.Timedelta(days=1)
        end_utc = int((end_local - pd.Timedelta(hours=window.utc_offset_hours)).timestamp())
        if end_utc < last_px_ts:
            night_end[n] = end_utc
    r = r[r["night"].isin(list(night_end))].copy()
    r["clockout_ts"] = r["night"].map(night_end).astype("int64")
    r["clockout_price"] = r["clockout_ts"].map({t: L.price_at(prices, t) for t in set(night_end.values())})
    r["hour"] = r["time"].dt.floor("h")
    r = r.reset_index(drop=True)
    n_med = int(r.groupby("night").size().median())
    return {"rounds": r, "prices": prices, "all_rounds": rounds, "rounds_per_night": n_med}


# --------------------------------------------------------------------------- decisions

def decisions_for(r: pd.DataFrame, A: float, costs: L.Costs, claim: bool = True,
                  policy: str = "all25") -> Dict[str, np.ndarray]:
    buy_px = r["price_known"].to_numpy() * (1 + costs.buy_cost_bps / 1e4)
    ema = r["ema_before"].to_numpy(dtype=float)
    est_ema = L.user_cost_estimate(ema, A, costs, None, claim)
    est_ev = L.user_cost_estimate(ema, A, costs, r["pot_before"].to_numpy(dtype=float), claim)
    d = {
        "always_buy": np.zeros(len(r), dtype=bool),
        "always_mine": np.ones(len(r), dtype=bool),
        "gate_naive": ema / L.LAMPORTS_PER_SOL < r["price_known"].to_numpy(),
        "gate_ema": est_ema < buy_px,
        "gate_ev": est_ev < buy_px,
    }
    d["gate_ema_hourly"] = hourly_decision(r, d["gate_ema"])
    d["gate_ev_hourly"] = hourly_decision(r, d["gate_ev"])
    # On-chain-feasible variant: the price is frozen into the plan when the shift is armed; the
    # program then compares live EMA and live Motherlode pot against that fixed threshold.
    arm_px = r.groupby("night")["price_known"].transform("first").to_numpy() * (1 + costs.buy_cost_bps / 1e4)
    d["gate_ev_arm"] = est_ev < arm_px
    # Hindsight: per hour, real expected cost of mining (real crowding, pot EV) vs real buy price.
    net = (1 - costs.refining_fee) if claim else 1.0
    e_ore = L.expected_round_ore(r, policy, A, motherlode="ev") * net / L.ONE_ORE
    e_sol = (L.expected_round_loss(r, policy, A) + costs.crank_fee_lamports) / L.LAMPORTS_PER_SOL
    g = pd.DataFrame({"hour": r["hour"], "ore": e_ore, "sol": e_sol,
                      "px": r["clockout_price"] * (1 + costs.buy_cost_bps / 1e4)})
    hourly = g.groupby("hour").agg(ore=("ore", "sum"), sol=("sol", "sum"), px=("px", "first"))
    good = (hourly["sol"] / hourly["ore"] < hourly["px"])
    d["oracle_hour"] = r["hour"].map(good).fillna(False).to_numpy(dtype=bool)
    return d


def hourly_decision(r: pd.DataFrame, per_round: np.ndarray) -> np.ndarray:
    """Freeze a per-round rule at each hour's first in-shift round (decide once per hour)."""
    first = pd.Series(per_round, index=r.index).groupby(r["hour"]).transform("first")
    return first.to_numpy(dtype=bool)


# --------------------------------------------------------------------------- evaluation

def evaluate(r: pd.DataFrame, A: float, costs: L.Costs, decisions: Dict[str, np.ndarray],
             policy: str = "all25", claim: bool = True, motherlode: str = "ev") -> Dict[str, pd.DataFrame]:
    """Expected SOL and ORE per night per strategy, given the real rounds.

    The solo lottery and tile choice are integrated out. motherlode="ev" also integrates out the
    Motherlode trigger (rng % 500, independent of every deployment), paying pot/500 per round:
    an unbiased, much lower-variance estimate of each strategy's expected price. "realized" pays
    the real hits instead (what actually happened over this window, luck included).
    """
    net = (1 - costs.refining_fee) if claim else 1.0
    slot = (L.expected_loss_rate(policy) * A + costs.crank_fee_lamports) / L.LAMPORTS_PER_SOL
    e_ore = L.expected_round_ore(r, policy, A, motherlode=motherlode) * net / L.ONE_ORE
    e_loss = (L.expected_round_loss(r, policy, A) + costs.crank_fee_lamports) / L.LAMPORTS_PER_SOL
    buy_px = r["clockout_price"].to_numpy() * (1 + costs.buy_cost_bps / 1e4)
    night = r["night"].to_numpy()
    out = {}
    for name, d in decisions.items():
        df = pd.DataFrame({
            "night": night,
            "sol_mine": np.where(d, e_loss, 0.0),
            "ore_mine": np.where(d, e_ore, 0.0),
            "sol_buy": np.where(d, 0.0, slot),
            "ore_buy": np.where(d, 0.0, slot / buy_px),
            "mined": d.astype(int),
        })
        per = df.groupby("night").sum()
        per["sol"] = per["sol_mine"] + per["sol_buy"] + costs.tx_lamports_per_night / L.LAMPORTS_PER_SOL
        per["ore"] = per["ore_mine"] + per["ore_buy"]
        per["rounds"] = df.groupby("night").size()
        out[name] = per
    return out


def summarize(per_night: Dict[str, pd.DataFrame], r: pd.DataFrame, costs: L.Costs,
              n_boot: int = 2000, seed: int = 7) -> pd.DataFrame:
    """Pooled effective price per strategy + night-block bootstrap CI of the gap to always_buy."""
    rng = np.random.default_rng(seed)
    nights = per_night["always_buy"].index.to_numpy()
    idx = rng.integers(0, len(nights), size=(n_boot, len(nights)))
    mkt = (r.groupby("night")["clockout_price"].first() * (1 + costs.buy_cost_bps / 1e4))
    base_sol = per_night["always_buy"]["sol"].to_numpy()
    base_ore = per_night["always_buy"]["ore"].to_numpy()
    rows = []
    for name, per in per_night.items():
        sol, ore = per["sol"].to_numpy(), per["ore"].to_numpy()
        eff = sol.sum() / ore.sum()
        base = base_sol.sum() / base_ore.sum()
        boot = (sol[idx].sum(1) / ore[idx].sum(1)) / (base_sol[idx].sum(1) / base_ore[idx].sum(1)) - 1
        rows.append({
            "strategy": name,
            "eff_sol_per_ore": eff,
            "vs_always_buy_pct": 100 * (eff / base - 1),
            "ci95_lo_pct": 100 * np.percentile(boot, 2.5),
            "ci95_hi_pct": 100 * np.percentile(boot, 97.5),
            "mined_round_share": per["mined"].sum() / per["rounds"].sum(),
            "sol_per_night": per["sol"].mean(),
            "ore_per_night": per["ore"].mean(),
            "nights_beating_buy": float(np.mean(per["sol"] / per["ore"] < base_sol / base_ore - 1e-15)),
        })
    df = pd.DataFrame(rows).set_index("strategy")
    df.attrs["mean_market_buy_px"] = float(mkt.mean())
    return df


def night_distribution(r: pd.DataFrame, A: float, costs: L.Costs, policy: str, n_sims: int,
                       seed: int = 11, claim: bool = True, decisions: Optional[np.ndarray] = None
                       ) -> Dict[str, object]:
    """Monte Carlo distribution of ORE MINED per night (net of refining) for one policy."""
    rng = np.random.default_rng(seed)
    net = (1 - costs.refining_fee) if claim else 1.0
    samples = []
    night_ids = []
    for n, g in r.groupby("night"):
        mc = L.realized_round_arrays(g, policy, A, rng, n_sims)
        ore = mc["ore"]
        if decisions is not None:
            ore = ore[:, decisions[g.index.to_numpy()]]
        samples.append(ore.sum(1) * net / L.ONE_ORE)
        night_ids.append(n)
    s = np.stack(samples)  # nights x sims
    flat = s.ravel()
    # Mean from the closed form (the MC mean of rare 1-ORE solo wins is noisy); MC for the shape.
    e = L.expected_round_ore(r, policy, A) * net / L.ONE_ORE
    if decisions is not None:
        e = e * decisions
    exp_night = float(pd.Series(e, index=r.index).groupby(r["night"]).sum().mean())
    q = np.percentile(flat, [5, 25, 50, 75, 95])
    return {
        "policy": policy,
        "per_round_lamports": A,
        "mean_ore": exp_night,
        "p5": q[0], "p25": q[1], "median": q[2], "p75": q[3], "p95": q[4],
        "mc_mean_ore": float(flat.mean()),
        "cv": float(flat.std() / flat.mean()) if flat.mean() > 0 else float("nan"),
        "p_zero": float(np.mean(flat <= 0)),
        "p_below_half_mean": float(np.mean(flat < 0.5 * exp_night)),
        "p_motherlode_night": float(np.mean(r.groupby("night")["motherlode_hit"].any())),
        "samples": flat,
    }


# --------------------------------------------------------------------------- figures

PALETTE = {"blue": "#2a78d6", "orange": "#eb6834", "aqua": "#1baf7a", "yellow": "#eda100",
           "surface": "#fcfcfb", "ink": "#0b0b0b", "ink2": "#52514e", "grid": "#e4e3df"}


def _style(ax):
    ax.set_facecolor(PALETTE["surface"])
    for s in ("top", "right"):
        ax.spines[s].set_visible(False)
    for s in ("left", "bottom"):
        ax.spines[s].set_color(PALETTE["grid"])
    ax.tick_params(colors=PALETTE["ink2"], labelsize=9)
    ax.grid(True, color=PALETTE["grid"], linewidth=0.6)
    ax.set_axisbelow(True)


def fig_eff_vs_budget(sweep: pd.DataFrame, path: str) -> None:
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    fig, ax = plt.subplots(figsize=(8, 4.6), dpi=130, facecolor=PALETTE["surface"])
    _style(ax)
    series = [("always_mine", "Always mine (claim nightly)", PALETTE["blue"]),
              ("always_mine_hold", "Always mine (hold unrefined)", PALETTE["aqua"]),
              ("gate_ev", "Gate: EMA + Motherlode pot", PALETTE["orange"])]
    for key, label, color in series:
        if key in sweep.columns:
            ax.plot(sweep.index, sweep[key], color=color, linewidth=2, marker="o", markersize=4, label=label)
    ax.axhline(1.0, color=PALETTE["ink2"], linewidth=1)
    ax.text(sweep.index.min(), 1.0, " always buy = 1.0", va="bottom", ha="left", color=PALETTE["ink2"], fontsize=9)
    for b in HEADLINE_BUDGETS:
        ax.axvline(b, color=PALETTE["grid"], linewidth=1)
    ax.set_xscale("log")
    ax.set_xlabel("Nightly budget, SOL deployed (log scale)", color=PALETTE["ink"])
    ax.set_ylabel("Effective price / always-buy price", color=PALETTE["ink"])
    ax.set_title("Small budgets pay the fixed crank fee on every round", loc="left", color=PALETTE["ink"], fontsize=11)
    ax.legend(frameon=False, fontsize=9, loc="upper right")
    fig.tight_layout()
    fig.savefig(path, facecolor=PALETTE["surface"])
    plt.close(fig)


def fig_night_hist(dists: List[Dict[str, object]], path: str) -> None:
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    budgets = sorted({d["budget"] for d in dists})
    fig, axes = plt.subplots(1, len(budgets), figsize=(4.2 * len(budgets), 3.8), dpi=130,
                             facecolor=PALETTE["surface"], sharey=True)
    axes = np.atleast_1d(axes)
    colors = {"all25": PALETTE["blue"], "rand5": PALETTE["orange"]}
    labels = {"all25": "All 25 tiles", "rand5": "5 random tiles"}
    for ax, b in zip(axes, budgets):
        _style(ax)
        for d in dists:
            if d["budget"] != b or d["policy"] not in colors:
                continue
            s = d["samples"]
            hi = np.percentile(s, 99)
            bins = np.linspace(0, max(hi, 1e-9), 50)
            ax.hist(np.clip(s, 0, hi), bins=bins, histtype="step", linewidth=2, color=colors[d["policy"]],
                    density=True, label=f"{labels[d['policy']]}  (P0={100 * d['p_zero']:.1f}%)")
        ax.set_title(f"{b:g} SOL/night deployed", loc="left", color=PALETTE["ink"], fontsize=10)
        ax.set_xlabel("ORE mined per night (after 10% refining)", color=PALETTE["ink"], fontsize=9)
        ax.legend(frameon=False, fontsize=8)
    axes[0].set_ylabel("Density", color=PALETTE["ink"])
    fig.suptitle("ORE per night for a small rig (Monte Carlo over real rounds)", x=0.01, ha="left",
                 color=PALETTE["ink"], fontsize=11)
    fig.tight_layout()
    fig.savefig(path, facecolor=PALETTE["surface"])
    plt.close(fig)


def fig_cost_vs_price(r_all: pd.DataFrame, prices: pd.DataFrame, A: float, costs: L.Costs, path: str,
                      since: str = REGIME_START) -> None:
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    x = r_all[(r_all["time"] >= pd.Timestamp(since, tz="UTC")) & r_all["ema_warm"]].copy()
    x["hour"] = x["time"].dt.floor("h")
    h = x.groupby("hour").agg(ema=("ema_after", "mean"), pot=("pot_before", "mean"))
    h["ema_sol"] = h["ema"] / L.LAMPORTS_PER_SOL
    h["user"] = L.user_cost_estimate(h["ema"].to_numpy(), A, costs, None, True)
    px = prices.copy()
    px["hour"] = pd.to_datetime(px["ts"], unit="s", utc=True)
    h = h.join(px.set_index("hour")["close"], how="inner")
    roll = lambda s: s.rolling(6, min_periods=1).median()  # noqa: E731
    fig, ax = plt.subplots(figsize=(9, 4.4), dpi=130, facecolor=PALETTE["surface"])
    _style(ax)
    ax.plot(h.index, h["close"], color=PALETTE["ink"], linewidth=1.5, label="Market price (ORE/SOL pool)")
    ax.plot(h.index, roll(h["ema_sol"]), color=PALETTE["blue"], linewidth=1.5,
            label="Protocol production_cost_ema (6h median)")
    ax.plot(h.index, roll(h["user"]), color=PALETTE["orange"], linewidth=1.5,
            label=f"Small-rig all-in cost at {A * 1e-9 * 1e6:.0f} µSOL/round (6h median)")
    ax.set_ylabel("SOL per ORE", color=PALETTE["ink"])
    ax.set_title("Mining cost vs market, reconstructed from every round since Aug 13", loc="left",
                 color=PALETTE["ink"], fontsize=11)
    ax.legend(frameon=False, fontsize=8, loc="upper left")
    fig.autofmt_xdate()
    fig.tight_layout()
    fig.savefig(path, facecolor=PALETTE["surface"])
    plt.close(fig)


# --------------------------------------------------------------------------- main

def run(args) -> Dict[str, object]:
    t0 = time.time()
    window = L.ShiftWindow(args.start_hour, args.end_hour, args.utc_offset)
    costs = L.Costs(crank_fee_lamports=args.crank_fee, buy_cost_bps=args.buy_bps,
                    refining_fee=args.refining_fee, tx_lamports_per_night=args.tx_lamports)
    prep = prepare(args.sample, window)
    r = prep["rounds"]
    n_med = prep["rounds_per_night"]
    nights = r["night"].nunique()
    print(f"data: {len(r)} in-shift rounds over {nights} nights "
          f"({r['time'].min():%Y-%m-%d} .. {r['time'].max():%Y-%m-%d}), median {n_med} rounds/night")
    res: Dict[str, object] = {
        "config": {"window": asdict(window), "costs": asdict(costs), "rounds_per_night_median": n_med,
                   "nights": int(nights), "rounds": int(len(r)),
                   "first_round": int(r["round_id"].min()), "last_round": int(r["round_id"].max()),
                   "period": [str(r["time"].min()), str(r["time"].max())]},
        "headline": {}, "hold_unrefined": {}, "sweep": {}, "variance": [], "sensitivity": {},
    }

    # Market context over the backtest nights.
    mk = r.groupby("night").agg(px=("clockout_price", "first"), ema=("ema_before", "mean"))
    res["market"] = {"mean_clockout_price_sol": float(mk["px"].mean()),
                     "mean_in_shift_ema_sol": float(mk["ema"].mean() / L.LAMPORTS_PER_SOL),
                     "share_rounds_ema_below_price": float(np.mean(r["ema_before"] / L.LAMPORTS_PER_SOL < r["price_known"]))}

    for B in HEADLINE_BUDGETS:
        A = B * L.LAMPORTS_PER_SOL / n_med
        dec = decisions_for(r, A, costs)
        summ = summarize(evaluate(r, A, costs, dec), r, costs)
        summ_real = summarize(evaluate(r, A, costs, dec, motherlode="realized"), r, costs)
        res["headline"][str(B)] = {"per_round_lamports": A, "table": summ.round(6).to_dict(orient="index"),
                                   "table_realized": summ_real.round(6).to_dict(orient="index")}
        print(f"\n== budget {B} SOL/night deployed  (A = {A:,.0f} lamports/round, claim nightly)")
        show = summ[["eff_sol_per_ore", "vs_always_buy_pct", "ci95_lo_pct", "ci95_hi_pct",
                     "mined_round_share", "ore_per_night"]].copy()
        show["realized_vs_buy_pct"] = summ_real["vs_always_buy_pct"]
        print(show.round(4).to_string())
        # Hold unrefined (no 10% refining fee, refining yield NOT credited: conservative).
        dec_h = decisions_for(r, A, costs, claim=False)
        summ_h = summarize(evaluate(r, A, costs, dec_h, claim=False), r, costs)
        res["hold_unrefined"][str(B)] = summ_h.round(6).to_dict(orient="index")

    # Budget sweep: always_mine and gates relative to always_buy.
    rows = {}
    for B in SWEEP_BUDGETS:
        A = B * L.LAMPORTS_PER_SOL / n_med
        dec = decisions_for(r, A, costs)
        s = summarize(evaluate(r, A, costs, dec), r, costs, n_boot=200)
        dec_h = decisions_for(r, A, costs, claim=False)
        s_h = summarize(evaluate(r, A, costs, dec_h, claim=False), r, costs, n_boot=200)
        s_r = summarize(evaluate(r, A, costs, dec, motherlode="realized"), r, costs, n_boot=200)
        rel = (s["eff_sol_per_ore"] / s.loc["always_buy", "eff_sol_per_ore"]).to_dict()
        rel["always_mine_realized"] = float(s_r.loc["always_mine", "eff_sol_per_ore"] / s_r.loc["always_buy", "eff_sol_per_ore"])
        rel["gate_ev_realized"] = float(s_r.loc["gate_ev", "eff_sol_per_ore"] / s_r.loc["always_buy", "eff_sol_per_ore"])
        rel["always_mine_hold"] = float(s_h.loc["always_mine", "eff_sol_per_ore"] / s.loc["always_buy", "eff_sol_per_ore"])
        rel["gate_ev_hold"] = float(s_h.loc["gate_ev", "eff_sol_per_ore"] / s.loc["always_buy", "eff_sol_per_ore"])
        rel["gate_ev_mined_share"] = float(s.loc["gate_ev", "mined_round_share"])
        rows[B] = rel
    sweep = pd.DataFrame(rows).T
    sweep.index.name = "budget_sol_per_night"
    res["sweep"] = sweep.round(5).to_dict(orient="index")
    print("\n== effective price relative to always-buy, by nightly budget (claim nightly unless _hold)")
    print(sweep[["always_mine", "always_mine_hold", "gate_naive", "gate_ema", "gate_ema_hourly", "gate_ev",
                 "gate_ev_hold", "oracle_hour", "gate_ev_mined_share", "always_mine_realized",
                 "gate_ev_realized"]].round(3).to_string())

    # Crank-fee and buy-cost sensitivity at 0.04 SOL/night.
    A04 = 0.04 * L.LAMPORTS_PER_SOL / n_med
    for fee in (0, 2_000, 5_000, 7_000):
        c2 = L.Costs(crank_fee_lamports=fee, buy_cost_bps=costs.buy_cost_bps, refining_fee=costs.refining_fee,
                     tx_lamports_per_night=costs.tx_lamports_per_night)
        s = summarize(evaluate(r, A04, c2, decisions_for(r, A04, c2)), r, c2, n_boot=200)
        res["sensitivity"][f"crank_fee_{fee}"] = s["vs_always_buy_pct"].round(3).to_dict()

    # Variance of ORE mined per night.
    dists = []
    for B in (0.02, 0.04):
        A = B * L.LAMPORTS_PER_SOL / n_med
        for pol in L.TILE_POLICIES:
            d = night_distribution(r, A, costs, pol, n_sims=args.sims)
            d["budget"] = B
            dists.append(d)
            res["variance"].append({k: (float(v) if isinstance(v, (float, np.floating)) else v)
                                    for k, v in d.items() if k != "samples"})
    print("\n== ORE mined per night, always_mine, net of 10% refining (Monte Carlo over real nights)")
    vt = pd.DataFrame(res["variance"])[["budget", "policy", "mean_ore", "p5", "median", "p95", "cv",
                                        "p_zero", "p_below_half_mean"]]
    print(vt.to_string(index=False, float_format=lambda v: f"{v:.6g}"))
    print(f"P(night contains a Motherlode hit) = {dists[0]['p_motherlode_night']:.2f}")

    if not args.no_figures:
        os.makedirs(FIG, exist_ok=True)
        fig_eff_vs_budget(sweep, os.path.join(FIG, "eff_price_vs_budget.png"))
        fig_night_hist(dists, os.path.join(FIG, "ore_per_night.png"))
        fig_cost_vs_price(prep["all_rounds"], prep["prices"], 1.0 * L.LAMPORTS_PER_SOL / n_med, costs,
                          os.path.join(FIG, "cost_vs_price.png"))
    res["runtime_s"] = round(time.time() - t0, 1)
    return res


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--sample", action="store_true", help="run on the committed data/sample/ only")
    ap.add_argument("--crank-fee", type=int, default=5_000, help="Heads Down fixed fee, lamports/round/rig")
    ap.add_argument("--buy-bps", type=float, default=50.0, help="swap fee + slippage on the buy leg")
    ap.add_argument("--refining-fee", type=float, default=L.REFINING_FEE)
    ap.add_argument("--tx-lamports", type=int, default=5_000, help="clock-out tx cost per night")
    ap.add_argument("--start-hour", type=float, default=23.0, help="shift start, local hour")
    ap.add_argument("--end-hour", type=float, default=7.0, help="shift end, local hour")
    ap.add_argument("--utc-offset", type=float, default=1.0, help="user's UTC offset (WAT = +1)")
    ap.add_argument("--sims", type=int, default=4000, help="Monte Carlo draws per night")
    ap.add_argument("--no-figures", action="store_true")
    args = ap.parse_args(argv)
    res = run(args)
    os.makedirs(OUT, exist_ok=True)
    with open(os.path.join(OUT, "backtest_results.json"), "w") as f:
        json.dump(res, f, indent=1, default=str)
    print(f"\nwrote {os.path.join(OUT, 'backtest_results.json')} in {res['runtime_s']}s")
    return 0


if __name__ == "__main__":
    sys.exit(main())
