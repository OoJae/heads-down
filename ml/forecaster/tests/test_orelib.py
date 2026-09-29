"""Unit tests for the ORE mechanics the backtest relies on.

Synthetic tests pin the integer math to the Rust source; the sample-backed tests check the same
identities against real on-chain ResetEvents committed in data/sample/.
"""
import os
import sys

import numpy as np
import pandas as pd
import pytest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import orelib as L  # noqa: E402

HAVE_SAMPLE = os.path.exists(os.path.join(L.SAMPLE, "reset_events_sample.csv"))


# --------------------------------------------------------------------------- integer fee math

def test_tile_fees_match_round_calculate_fees():
    # 1% admin (floor, min 1) on every tile; 10% of the remainder (floor, min 1) on losing tiles.
    assert L.tile_fees(1_000, True) == (10, 0)
    assert L.tile_fees(1_000, False) == (10, 99)
    assert L.tile_fees(50, False) == (1, 4)  # admin min 1; (50-1)//10 = 4
    assert L.tile_fees(5, False) == (1, 1)  # protocol min 1
    assert L.tile_fees(0, False) == (0, 0)


def test_returned_sol_matches_checkpoint():
    # A lone miner on a losing tile of 1 SOL gets 1 - 0.01 - 0.099 = 0.891 SOL back.
    assert L.returned_sol(10**9, 10**9, False) == 891_000_000
    # On the winning tile only the 1% admin fee is taken.
    assert L.returned_sol(10**9, 10**9, True) == 990_000_000
    # Pro-rata with u128 floor.
    assert L.returned_sol(1, 3, False) == (1 * (3 - 1 - 1)) // 3


def test_loss_rate_constants():
    assert L.LOSS_WIN_TILE == pytest.approx(0.01)
    assert L.LOSS_LOSE_TILE == pytest.approx(0.109)
    assert L.EXPECTED_LOSS_RATE == pytest.approx(0.10504)
    # The protocol-fee-only share that production_cost_ema counts.
    assert L.EXPECTED_LOSS_RATE * L.PROTOCOL_SHARE_OF_LOSS == pytest.approx(0.09504)


@pytest.mark.parametrize("policy", L.TILE_POLICIES)
def test_expected_loss_rate_is_policy_invariant(policy):
    # By symmetry every tile has the same 1/25 chance to win, so the loss rate per lamport is equal.
    assert L.expected_loss_rate(policy) == pytest.approx(L.EXPECTED_LOSS_RATE)


def test_production_cost_and_ema_recursion():
    # Real round 420881: 0.645 SOL protocol fee over 1.2 ORE minted.
    assert L.round_production_cost(645_306_284, 120_000_000_000) == 537_755_236
    assert L.round_production_cost(1, 0) is None  # refund round: EMA untouched
    assert L.ema_update(0, 500) == 500  # first observation seeds
    assert L.ema_update(500, 600) == (600 + 19 * 500) // 20
    # Integer floor semantics, not float rounding.
    assert L.ema_update(3, 0) == (0 + 19 * 3) // 20 == 2


# --------------------------------------------------------------------------- enrichment

def _synthetic_rounds(n=300, hits=(50, 120), start_id=1000, D=10 * 10**9):
    rows = []
    for i in range(n):
        rid = start_id + i
        W = D // 25
        vaulted = (D - W) * 99 // 1000
        admin = D // 100
        rows.append(dict(
            round_id=rid, ts=1_790_000_000 + 70 * i, start_slot=0, end_slot=0,
            winning_square=i % 25, is_split=int(i % 5 != 0), num_winners=150,
            motherlode=0, total_deployed=D, total_vaulted=vaulted,
            total_winnings=D - vaulted - admin, total_minted=120_000_000_000,
            deployed_winning_square=W))
    df = pd.DataFrame(rows)
    return df


def test_pot_reconstruction_from_hits():
    df = _synthetic_rounds()
    # Hits at rounds 1050 and 1120; the external list also carries an older hit at 990.
    df.loc[df.round_id == 1050, "motherlode"] = (1050 - 990) * L.MOTHERLODE_PER_ROUND
    df.loc[df.round_id == 1120, "motherlode"] = (1120 - 1050) * L.MOTHERLODE_PER_ROUND
    ext = pd.DataFrame({"round_id": [990], "motherlode": [123]})
    r = L.enrich_rounds(df, ext)
    h = r[r.motherlode_hit]
    # The pot a hit pays equals 0.2 ORE per round since the previous hit.
    assert np.allclose(h["motherlode"], h["pot_before"])
    assert r.loc[r.round_id == 1051, "pot_before_ore"].item() == pytest.approx(0.2)
    assert r.loc[r.round_id == 1000, "pot_before_ore"].item() == pytest.approx(0.2 * 10)


def test_pot_is_nan_without_prior_hit():
    r = L.enrich_rounds(_synthetic_rounds(n=10), None)
    assert r["pot_before"].isna().all()


def test_ema_before_is_previous_after():
    r = L.enrich_rounds(_synthetic_rounds(), None)
    assert (r["ema_before"].iloc[1:].to_numpy() == r["ema_after"].iloc[:-1].to_numpy()).all()
    # Constant rounds -> EMA equals the per-round cost exactly.
    assert r["ema_after"].iloc[-1] == r["pc_round"].iloc[-1]
    assert not r["ema_warm"].iloc[0] and r["ema_warm"].iloc[-1]


# --------------------------------------------------------------------------- small-miner share

@pytest.mark.parametrize("policy", L.TILE_POLICIES)
def test_monte_carlo_mean_matches_expectation(policy):
    r = L.enrich_rounds(_synthetic_rounds(n=200), None)
    r.loc[7, "motherlode"] = 50 * L.ONE_ORE
    rng = np.random.default_rng(0)
    A = 5 * 10**7  # 0.05 SOL per round, big enough that solo lotteries hit in the sample
    mc = L.realized_round_arrays(r, policy, A, rng, n_sims=20_000)
    exp_ore = L.expected_round_ore(r, policy, A)
    exp_loss = L.expected_round_loss(r, policy, A)
    assert mc["ore"].mean(0).sum() == pytest.approx(exp_ore.sum(), rel=0.03)
    assert mc["loss"].mean(0).sum() == pytest.approx(exp_loss.sum(), rel=0.01)


def test_share_uses_counterfactual_tile_total():
    r = L.enrich_rounds(_synthetic_rounds(n=1), None)
    r["is_split"] = 1
    W = int(r["deployed_winning_square"].iloc[0])
    A = 25 * 1000  # 1000 lamports per tile
    ore = L.expected_round_ore(r, "all25", A)[0]
    assert ore == pytest.approx(L.ONE_ORE * 1000 / (W + 1000))


def test_refund_round_pays_and_costs_nothing():
    r = L.enrich_rounds(_synthetic_rounds(n=3), None)
    r.loc[1, "winning_square"] = -1
    r = L.enrich_rounds(r, None)
    assert L.expected_round_ore(r, "all25", 10**6)[1] == 0
    assert L.expected_round_loss(r, "all25", 10**6)[1] == 0


def test_user_cost_estimate_limits():
    costs = L.Costs(crank_fee_lamports=0, refining_fee=0.0)
    ema = np.array([0.8e9])  # 0.8 SOL/ORE protocol-fee EMA
    # Tiny deposit, no fees: a miner's all-in loss is (admin+protocol)/protocol times the EMA.
    est = L.user_cost_estimate(ema, 1_000, costs)[0]
    assert est == pytest.approx(0.8 / L.PROTOCOL_SHARE_OF_LOSS, rel=1e-4)
    # The refining fee scales cost by 1/0.9; a fixed crank fee dominates tiny deposits.
    est_claim = L.user_cost_estimate(ema, 1_000, L.Costs(crank_fee_lamports=0))[0]
    assert est_claim == pytest.approx(est / 0.9, rel=1e-9)
    est_fee = L.user_cost_estimate(ema, 1_000, L.Costs(crank_fee_lamports=5_000, refining_fee=0.0))[0]
    assert est_fee > 40 * est
    # A 300-ORE pot raises E[ORE/round] from 1.2 to 1.6 and cuts the cost by 25%.
    est_pot = L.user_cost_estimate(ema, 1_000, costs, pot_grams=np.array([300 * L.ONE_ORE]))[0]
    assert est_pot == pytest.approx(est * 1.2 / 1.6, rel=1e-6)


# --------------------------------------------------------------------------- time handling

def test_known_price_has_no_lookahead():
    prices = pd.DataFrame({"ts": np.arange(0, 10 * 3600, 3600), "open": np.arange(10.0),
                           "close": np.arange(10.0) + 0.5})
    rounds = pd.DataFrame({"ts": [100, 3599, 3600, 7300, 36_000]})
    out = L.attach_known_price(rounds, prices)
    assert np.isnan(out["price_known"].iloc[0]) and np.isnan(out["price_known"].iloc[1])
    assert out["price_known"].iloc[2] == 0.5  # candle [0,3600) has just closed
    assert out["price_known"].iloc[3] == 1.5
    assert (out["price_known_asof"][out["price_known_asof"] >= 0] <= out["ts"][out["price_known_asof"] >= 0]).all()


def test_shift_window_assignment():
    w = L.ShiftWindow(23, 7, 1)  # WAT
    t = pd.to_datetime(["2026-09-01T22:30:00Z",  # 23:30 local, night of Sep 1
                        "2026-09-02T05:00:00Z",  # 06:00 local, still night of Sep 1
                        "2026-09-02T06:00:00Z",  # 07:00 local, shift over
                        "2026-09-02T11:00:00Z"], utc=True)
    nights = w.assign(pd.DataFrame({"time": t}))
    assert nights.iloc[0] == pd.Timestamp("2026-09-01")
    assert nights.iloc[1] == pd.Timestamp("2026-09-01")
    assert pd.isna(nights.iloc[2]) and pd.isna(nights.iloc[3])


# --------------------------------------------------------------------------- real on-chain sample

@pytest.mark.skipif(not HAVE_SAMPLE, reason="data/sample not generated")
def test_sample_rounds_obey_post_aug12_fee_rules():
    r = L.load_rounds(sample=True)
    x = r[~r.refund]
    assert len(x) > 100
    # Admin fee: 1% per tile, floored per tile -> within 25 lamports of 1% of the total.
    assert (np.abs(x.admin_fee - x.total_deployed / 100) <= 25).all()
    # Protocol fee: 10% of the (post-admin) SOL on the 24 losing tiles only (no parimutuel).
    expected = (x.total_deployed - x.deployed_winning_square) * 0.99 / 10
    assert (np.abs(x.total_vaulted / expected - 1) < 1e-3).all()
    # Every round mints 1 ORE + 0.2 ORE into the Motherlode pot.
    assert (x.total_minted == 120_000_000_000).all()
    # The winning tile holds about 1/25 of the SOL (tiles are nearly uniform).
    u = x.deployed_winning_square * 25 / x.total_deployed
    assert 0.9 < u.median() < 1.1


@pytest.mark.skipif(not HAVE_SAMPLE, reason="data/sample not generated")
def test_sample_motherlode_payouts_match_pot_reconstruction():
    r = L.load_rounds(sample=True)
    h = r[r.motherlode_hit & r.pot_before.notna()]
    assert np.allclose(h.motherlode, h.pot_before)


# --------------------------------------------------------------------------- on-chain gate form

def test_ema_ev_integer_form():
    # A 100-ORE pot is the long-run average: the adjustment is exactly 1.2 / 1.2.
    assert L.ema_ev_lamports(900_000_000, 100 * L.ONE_ORE) == 900_000_000
    # Empty pot: 1 ORE per round instead of 1.2, so the EV cost is 20% higher.
    assert L.ema_ev_lamports(900_000_000, 0) == 1_080_000_000
    # 400-ORE pot: 1.8 ORE per round.
    assert L.ema_ev_lamports(900_000_000, 400 * L.ONE_ORE) == 600_000_000
    # Extremes stay exact in Python ints (the Rust side must use u128 / checked math).
    big = L.ema_ev_lamports(2**64 - 1, 3_000_000 * L.ONE_ORE)
    assert 0 < big < 2**64
    with pytest.raises(ValueError):
        L.ema_ev_lamports(-1, 0)


def test_on_chain_threshold_matches_float_gate():
    rng = np.random.default_rng(1)
    costs = L.Costs()
    n = 20_000
    ema = rng.uniform(0.3e9, 1.5e9, n).astype(np.int64)
    pot = (rng.uniform(0, 500, n) * L.ONE_ORE).astype(np.int64)
    price = rng.uniform(0.4, 1.2, n)
    A = 2_000_000.0
    buy = price * (1 + costs.buy_cost_bps / 1e4)
    float_gate = L.user_cost_estimate(ema.astype(float), A, costs, pot.astype(float)) < buy
    thr = np.array([L.ev_threshold_lamports(p, A, costs) for p in price])
    int_gate = np.array([L.ema_ev_lamports(int(e), int(q)) for e, q in zip(ema, pot)]) < thr
    disagree = float_gate != int_gate
    # Only the A/(D+A) ~ A/D approximation separates them: rare, and only at the boundary.
    assert disagree.mean() < 0.005
    if disagree.any():
        est = L.user_cost_estimate(ema[disagree].astype(float), A, costs, pot[disagree].astype(float))
        assert np.all(np.abs(est / buy[disagree] - 1) < 2e-3)
