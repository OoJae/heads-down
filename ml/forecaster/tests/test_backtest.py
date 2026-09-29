"""Invariants of the mine-or-buy backtest: equal spend, hindsight bound, causality, budget cap."""
import os
import sys

import numpy as np
import pandas as pd
import pytest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import backtest as BT  # noqa: E402
import orelib as L  # noqa: E402


def synthetic_market(n_days=3, seed=0):
    """~70 s rounds with crowding that follows the Motherlode pot, and a drifting price."""
    rng = np.random.default_rng(seed)
    t0 = int(pd.Timestamp("2026-09-01T00:00:00Z").timestamp())
    n = int(n_days * 86400 / 70)
    rows, last_hit = [], -40
    for i in range(n):
        pot = 0.2 * (i - last_hit)
        D = int((5 + 4 * min(pot, 300) / 300 + rng.normal(0, 0.5)) * 1e9)
        W = int(D / 25 * rng.normal(1, 0.03))
        hit = rng.random() < 1 / 500
        ml = int(round(pot * L.ONE_ORE)) if hit else 0
        if hit:
            last_hit = i
        admin = D // 100
        vaulted = (D - W) * 99 // 1000
        rows.append(dict(round_id=10_000 + i, ts=t0 + 70 * i, start_slot=0, end_slot=0,
                         winning_square=int(rng.integers(0, 25)), is_split=int(rng.random() < 0.6),
                         num_winners=150, motherlode=ml, total_deployed=D, total_vaulted=vaulted,
                         total_winnings=D - admin - vaulted, total_minted=120_000_000_000,
                         deployed_winning_square=W))
    rounds = L.enrich_rounds(pd.DataFrame(rows), pd.DataFrame({"round_id": [10_000 - 40], "motherlode": [1]}))
    hours = np.arange(t0 - 7200, t0 + n_days * 86400 + 7200, 3600)
    px = 0.6 * np.exp(np.cumsum(rng.normal(0, 0.01, len(hours))))
    prices = pd.DataFrame({"ts": hours, "open": px, "close": px * np.exp(rng.normal(0, 0.003, len(hours)))})
    return rounds, prices


@pytest.fixture(scope="module")
def prep():
    rounds, prices = synthetic_market()
    return BT.prepare_frames(rounds, prices, L.ShiftWindow(23, 7, 1), since=None)


def test_every_strategy_spends_the_same(prep):
    r = prep["rounds"]
    A = 0.5 * L.LAMPORTS_PER_SOL / prep["rounds_per_night"]
    costs = L.Costs()
    per = BT.evaluate(r, A, costs, BT.decisions_for(r, A, costs))
    base = per["always_buy"]["sol"].to_numpy()
    for name, p in per.items():
        # all-25 losses are deterministic, so a mined slot costs exactly what a bought slot spends
        assert np.allclose(p["sol"].to_numpy(), base, rtol=1e-12), name


def test_always_buy_price_is_market_plus_costs(prep):
    r = prep["rounds"]
    costs = L.Costs(tx_lamports_per_night=0)
    A = 1e5
    per = BT.evaluate(r, A, costs, {"always_buy": np.zeros(len(r), dtype=bool)})["always_buy"]
    px = r.groupby("night")["clockout_price"].first() * (1 + costs.buy_cost_bps / 1e4)
    assert np.allclose(per["sol"] / per["ore"], px.loc[per.index])


def test_hourly_oracle_bounds_both_pure_strategies(prep):
    r = prep["rounds"]
    costs = L.Costs()
    for B in (0.04, 1.0, 5.0):
        A = B * L.LAMPORTS_PER_SOL / prep["rounds_per_night"]
        s = BT.summarize(BT.evaluate(r, A, costs, BT.decisions_for(r, A, costs)), r, costs, n_boot=10)
        eff = s["eff_sol_per_ore"]
        assert eff["oracle_hour"] <= eff["always_buy"] + 1e-12
        assert eff["oracle_hour"] <= eff["always_mine"] + 1e-12


def test_decisions_are_causal(prep):
    """Rewriting everything after round k (rounds and price candles) must not change decisions <= k."""
    rounds, prices = synthetic_market()
    window = L.ShiftWindow(23, 7, 1)
    p1 = BT.prepare_frames(rounds, prices, window, since=None)
    r1 = p1["rounds"]
    k = len(r1) // 2
    cut_ts = int(r1["ts"].iloc[k])
    fut = rounds["ts"] > cut_ts
    rounds2 = rounds.copy()
    rounds2.loc[fut, "total_deployed"] = rounds2.loc[fut, "total_deployed"] * 3
    rounds2.loc[fut, "total_vaulted"] = rounds2.loc[fut, "total_vaulted"] * 3
    rounds2.loc[fut, "deployed_winning_square"] = rounds2.loc[fut, "deployed_winning_square"] * 3
    rounds2 = L.enrich_rounds(rounds2[[c for c in rounds.columns if c in (
        "round_id ts start_slot end_slot winning_square is_split num_winners motherlode total_deployed "
        "total_vaulted total_winnings total_minted deployed_winning_square").split()]],
        pd.DataFrame({"round_id": [10_000 - 40], "motherlode": [1]}))
    prices2 = prices.copy()
    prices2.loc[prices2["ts"] + 3600 > cut_ts, ["open", "close"]] *= 5
    r2 = BT.prepare_frames(rounds2, prices2, window, since=None)["rounds"]
    A = 1.0 * L.LAMPORTS_PER_SOL / p1["rounds_per_night"]
    costs = L.Costs()
    d1 = BT.decisions_for(r1, A, costs)
    d2 = BT.decisions_for(r2, A, costs)
    same = r1["ts"].to_numpy()[: k + 1]
    assert (r2["ts"].to_numpy()[: k + 1] == same).all()
    for name in ("gate_naive", "gate_ema", "gate_ev", "gate_ema_hourly", "gate_ev_hourly"):
        assert (d1[name][: k + 1] == d2[name][: k + 1]).all(), name
    # The hindsight oracle is allowed to (and does) peek.
    assert "oracle_hour" in d1


def test_chunked_rig_never_exceeds_budget(prep):
    r = prep["rounds"]
    costs = L.Costs()
    for B, chunk in ((0.02, 1e6), (0.04, 2e6), (0.05, 1e6)):
        for rule in ("gate_ev", "spread"):
            m = BT.chunk_mask(r, B, chunk, costs, rule)
            per_night = pd.Series(m).groupby(r["night"].to_numpy()).sum()
            assert (per_night * chunk <= B * L.LAMPORTS_PER_SOL + 1e-6).all()


def test_chunked_rig_is_spend_matched(prep):
    r = prep["rounds"]
    costs = L.Costs()
    B = 0.04
    A = B * L.LAMPORTS_PER_SOL / prep["rounds_per_night"]
    flat = BT.evaluate(r, A, costs, {"always_buy": np.zeros(len(r), dtype=bool)})["always_buy"]
    ch = BT.evaluate_chunked(r, B, 1e6, costs, "spread")
    assert np.allclose(ch["sol"].to_numpy(), flat["sol"].to_numpy())


def test_small_budget_mining_is_dominated_by_crank_fee(prep):
    r = prep["rounds"]
    costs = L.Costs()
    n = prep["rounds_per_night"]
    small = BT.summarize(BT.evaluate(r, 0.02e9 / n, costs, BT.decisions_for(r, 0.02e9 / n, costs)), r, costs, n_boot=10)
    free = L.Costs(crank_fee_lamports=0)
    small_free = BT.summarize(BT.evaluate(r, 0.02e9 / n, free, BT.decisions_for(r, 0.02e9 / n, free)), r, free,
                              n_boot=10)
    assert small.loc["always_mine", "eff_sol_per_ore"] > 1.3 * small_free.loc["always_mine", "eff_sol_per_ore"]
