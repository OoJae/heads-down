#!/usr/bin/env python3
"""Check the round reconstruction against independent sources before trusting the backtest.

1. Fee identities on every round (post-Aug-12 rules): admin = 1%, protocol = 10% of losing tiles.
2. Motherlode pot reconstruction (0.2 ORE/round since the last hit) vs every real payout.
3. Replayed production_cost_ema vs api.ore.com /stats/history production_cost (USD, converted
   with the ORE/SOL and ORE/USD candles).
4. Replayed EMA and pot vs the live Board / Treasury accounts read over RPC (chain_snapshots.csv).
5. Market structure: tile uniformity, split vs solo crowding, crowding vs Motherlode pot.
Writes out/validation.json.
"""
from __future__ import annotations

import json
import os
import sys

import numpy as np
import pandas as pd

import orelib as L
from backtest import REGIME_START

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "out")


def run(sample: bool = False) -> dict:
    r = L.load_rounds(sample=sample)
    res: dict = {}
    ids = r["round_id"].to_numpy()
    res["coverage"] = {"rounds": int(len(r)), "first_round": int(ids.min()), "last_round": int(ids.max()),
                       "first_time": str(r["time"].min()), "last_time": str(r["time"].max()),
                       "missing_rounds": int((ids.max() - ids.min() + 1) - len(ids)),
                       "refund_rounds": int(r["refund"].sum()),
                       "mean_round_seconds": float(np.diff(r["ts"].to_numpy()).mean())}

    ids_all = r["round_id"].to_numpy()
    gaps = np.diff(ids_all)
    res["coverage"]["gaps"] = int((gaps != 1).sum())
    res["coverage"]["largest_gap_rounds"] = int(gaps.max() - 1) if len(gaps) else 0

    x = r[~r["refund"]]
    admin_ok = np.abs(x["admin_fee"] - x["total_deployed"] / 100) <= 25
    exp_prot = (x["total_deployed"] - x["deployed_winning_square"]) * 0.99 / 10
    prot_ok = np.abs(x["total_vaulted"] / exp_prot - 1) < 1e-3
    new_rule = admin_ok & prot_ok
    # Before the Aug 12 change, SOL moved parimutuel-style and total - returned - vaulted was ~5%.
    last_old = x.loc[~new_rule, "time"].max() if (~new_rule).any() else None
    in_regime = x["time"] >= pd.Timestamp(REGIME_START, tz="UTC")
    res["fee_identities"] = {
        "regime_start_used": REGIME_START,
        "last_round_on_old_rules": str(last_old),
        "admin_1pct_share_in_regime": float(admin_ok[in_regime].mean()),
        "protocol_10pct_losing_share_in_regime": float(prot_ok[in_regime].mean()),
        "minted_1p2_share_in_regime": float((x.loc[in_regime, "total_minted"] == 120_000_000_000).mean()),
        "rounds_in_regime": int(in_regime.sum()),
    }

    h = r[r["motherlode_hit"] & r["pot_before"].notna()]
    res["motherlode"] = {"hits": int(len(h)), "max_abs_error_ore": float((h["motherlode"] - h["pot_before"]).abs().max() / L.ONE_ORE)
                         if len(h) else None, "mean_payout_ore": float(h["motherlode"].mean() / L.ONE_ORE) if len(h) else None,
                         "hit_rate": float(r["motherlode_hit"].mean()), "expected_hit_rate": 1 / L.MOTHERLODE_ODDS}

    if not sample:
        snaps_p = os.path.join(L.DATA, "stats_snapshots.csv")
        if os.path.exists(snaps_p):
            s = pd.read_csv(snaps_p)
            s["t"] = pd.to_datetime(s["ts"], utc=True, format="ISO8601")
            usd = pd.read_csv(os.path.join(L.DATA, "ore_usd_1h.csv"))
            sol = pd.read_csv(os.path.join(L.DATA, "ore_sol_1h.csv"))
            m = usd.merge(sol, on="ts", suffixes=("_usd", "_sol"))
            m["sol_usd"] = m["close_usd"] / m["close_sol"]
            m["t"] = pd.to_datetime(m["ts"], unit="s", utc=True) + pd.Timedelta(hours=1)  # candle close
            s = s[(s["t"] > r["time"].min() + pd.Timedelta(hours=3)) & (s["t"] <= r["time"].max())]
            idx = np.searchsorted(r["ts"].to_numpy(), (s["t"].astype("int64") // 10**9).to_numpy(), side="right") - 1
            s = s.assign(ema_sol=r["ema_after"].to_numpy()[idx] / L.LAMPORTS_PER_SOL)
            s = pd.merge_asof(s.sort_values("t"), m[["t", "sol_usd"]].sort_values("t"), on="t")
            ratio = (s["production_cost"] / s["sol_usd"]) / s["ema_sol"]
            res["ema_vs_api"] = {"snapshots": int(ratio.notna().sum()), "median_ratio": float(ratio.median()),
                                 "p05": float(ratio.quantile(0.05)), "p95": float(ratio.quantile(0.95)),
                                 "note": "api.ore.com production_cost (USD) / SOL-USD from pool candles vs replayed EMA"}
            below = (s["production_cost"] < s["price"])
            res["api_cost_below_price"] = {"below": int(below.sum()), "of": int(len(s))}

        chain_p = os.path.join(L.DATA, "chain_snapshots.csv")
        if os.path.exists(chain_p):
            rows = []
            by_id = r.set_index("round_id")
            for _, c in pd.read_csv(chain_p).iterrows():
                prev = int(c["round_id"]) - 1  # the Board EMA includes resets through round_id - 1
                if prev not in by_id.index:
                    continue
                ema_rep = int(by_id.at[prev, "ema_after"])
                hits = r.loc[r["motherlode_hit"] & (r["round_id"] < c["round_id"]), "round_id"]
                pot_rep = (int(c["round_id"]) - int(hits.max())) * L.MOTHERLODE_PER_ROUND if len(hits) else None
                rows.append({"round_id": int(c["round_id"]), "chain_ema": int(c["production_cost_ema"]), "replayed_ema": ema_rep,
                             "ema_abs_diff_lamports": abs(int(c["production_cost_ema"]) - ema_rep),
                             "chain_pot": int(c["motherlode"]), "replayed_pot": pot_rep})
            res["chain_check"] = rows

    # Market structure: in-regime rounds only.
    x = x[in_regime]
    u = x["deployed_winning_square"] * L.N_TILES / x["total_deployed"]
    res["tiles"] = {"winning_tile_vs_uniform_median": float(u.median()), "p05": float(u.quantile(0.05)),
                    "p95": float(u.quantile(0.95)),
                    "split_tile_crowding": float(u[x["is_split"] == 1].mean()),
                    "solo_tile_crowding": float(u[x["is_split"] == 0].mean()),
                    "split_round_share": float(x["is_split"].mean())}
    w = r[r["ema_warm"] & r["pot_before"].notna() & (r["time"] >= pd.Timestamp(REGIME_START, tz="UTC"))]
    bins = [0, 25, 50, 100, 150, 200, 300, 10_000]
    w = w.assign(pot_bin=pd.cut(w["pot_before_ore"], bins))
    g = w.groupby("pot_bin", observed=True).agg(rounds=("round_id", "size"), sol_deployed=("total_deployed", "mean"),
                                                ema=("ema_before", "mean"), pot=("pot_before_ore", "mean"))
    g["sol_deployed"] /= L.LAMPORTS_PER_SOL
    g["ema_sol"] = g.pop("ema") / L.LAMPORTS_PER_SOL
    g["ev_cost_sol"] = g["ema_sol"] * 1.2 / (1 + g["pot"] / L.MOTHERLODE_ODDS)
    res["crowding_vs_pot"] = {"corr_deployed_pot": float(np.corrcoef(w["total_deployed"], w["pot_before"])[0, 1]),
                              "by_pot": {str(k): {kk: float(vv) for kk, vv in v.items()} for k, v in g.round(4).to_dict(orient="index").items()}}
    return res


def main() -> int:
    sample = "--sample" in sys.argv
    res = run(sample)
    os.makedirs(OUT, exist_ok=True)
    with open(os.path.join(OUT, "validation.json"), "w") as f:
        json.dump(res, f, indent=1, default=str)
    print(json.dumps(res, indent=1, default=str))
    return 0


if __name__ == "__main__":
    sys.exit(main())
