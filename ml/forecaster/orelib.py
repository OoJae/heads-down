"""ORE round mechanics for a *small* Heads Down miner, reconstructed from on-chain ResetEvents.

Everything here mirrors regolith-labs/ore (master, fetched 2026-09-29):
  api/src/state/round.rs   calculate_fees, is_split_reward, did_hit_motherlode
  program/src/reset.rs     +1 ORE per round (+0.2 ORE to the Motherlode pot), production-cost EMA
  program/src/checkpoint.rs  SOL returned and ORE paid to one miner
  api/src/state/miner.rs   10% refining fee on claimed (unrefined) ORE
  api/src/state/automation.rs  Discretionary strategy: fixed executor fee once per round

Units: SOL amounts in lamports (1e9 per SOL), ORE in grams (1e11 per ORE) unless a name ends
in _sol / _ore. Prices are SOL per ORE.

A "small miner" deploys `a` lamports on each tile of a tile set. Because a << tile totals, the
other miners' behaviour is taken as given (their totals come from the real round), and our own
deposit is added counterfactually to the tile total when computing our pro-rata share.
"""
from __future__ import annotations

import math
import os
from dataclasses import dataclass, field, asdict
from typing import Dict, Optional, Tuple

import numpy as np
import pandas as pd

HERE = os.path.dirname(os.path.abspath(__file__))
DATA = os.path.join(HERE, "data")
SAMPLE = os.path.join(DATA, "sample")

LAMPORTS_PER_SOL = 1_000_000_000
ONE_ORE = 100_000_000_000  # 11 decimals ("grams")
N_TILES = 25
N_SOLO = 10  # distribution_mask selects 10 solo tiles, 15 split tiles
N_SPLIT = N_TILES - N_SOLO
ADMIN_FEE_BPS = 100  # 1% of every tile (winning tile included)
PROTOCOL_FEE_DIV = 10  # 10% of (losing tile - admin fee)
MOTHERLODE_ODDS = 500
MOTHERLODE_PER_ROUND = ONE_ORE // 5  # +0.2 ORE per round into the pot
EMA_WINDOW = 20
REFINING_FEE = 0.10  # on claimed unrefined ORE

# Expected fraction of deployed SOL lost by a miner spread over all 25 tiles:
# one tile wins (1% admin), 24 lose (1% admin + 10% of the remaining 99%).
LOSS_WIN_TILE = ADMIN_FEE_BPS / 10_000  # 0.01
LOSS_LOSE_TILE = LOSS_WIN_TILE + (1 - LOSS_WIN_TILE) / PROTOCOL_FEE_DIV  # 0.109
EXPECTED_LOSS_RATE = (LOSS_WIN_TILE + (N_TILES - 1) * LOSS_LOSE_TILE) / N_TILES  # 0.10504
# Share of all SOL lost by miners that the protocol-fee-only EMA counts (the admin fee is excluded).
PROTOCOL_SHARE_OF_LOSS = ((N_TILES - 1) * (LOSS_LOSE_TILE - LOSS_WIN_TILE) / N_TILES) / EXPECTED_LOSS_RATE


# --------------------------------------------------------------------------- exact integer math

def tile_fees(sq_total: int, is_winning: bool) -> Tuple[int, int]:
    """(admin_fee, protocol_fee) that ORE takes from one tile, exactly as Round::calculate_fees."""
    if sq_total <= 0:
        return 0, 0
    admin = max(sq_total // 100, 1)
    protocol = 0 if is_winning else max((sq_total - admin) // 10, 1)
    return admin, protocol


def returned_sol(miner_deployed: int, sq_total: int, is_winning: bool) -> int:
    """SOL (lamports) returned to one miner on one tile, as in checkpoint.rs (u128 floor)."""
    if miner_deployed <= 0:
        return 0
    admin, protocol = tile_fees(sq_total, is_winning)
    return (miner_deployed * (sq_total - admin - protocol)) // sq_total


def round_production_cost(total_vaulted: int, total_minted: int) -> Optional[int]:
    """reset.rs: lamports of protocol fee per whole ORE minted this round (None on refund rounds)."""
    if total_minted <= 0:
        return None
    return (int(total_vaulted) * ONE_ORE) // int(total_minted)


def ema_update(prev: int, value: int) -> int:
    """reset.rs integer EMA over 20 rounds; the first observation seeds the EMA."""
    if prev == 0:
        return value
    return (value + (EMA_WINDOW - 1) * prev) // EMA_WINDOW


# --------------------------------------------------------------------------- loading

def _read(name: str, sample: bool) -> pd.DataFrame:
    if sample:
        path = os.path.join(SAMPLE, name.replace(".csv", "_sample.csv"))
    else:
        path = os.path.join(DATA, name)
    if not os.path.exists(path):
        raise FileNotFoundError(f"{path} missing: run `python3 fetch.py` (or pass --sample)")
    return pd.read_csv(path)


def load_rounds(sample: bool = False, since: Optional[str] = None) -> pd.DataFrame:
    """Load cached ResetEvents (+ the Motherlode hit list) and enrich them (see enrich_rounds)."""
    df = _read("reset_events.csv", sample)
    try:
        hits = _read("motherlode_events.csv", sample)[["round_id", "motherlode"]]
    except FileNotFoundError:
        hits = None
    df = enrich_rounds(df, hits)
    if since:
        df = df[df["time"] >= pd.Timestamp(since, tz="UTC")].reset_index(drop=True)
    return df


def enrich_rounds(df: pd.DataFrame, motherlode_hits: Optional[pd.DataFrame] = None) -> pd.DataFrame:
    """Derive per-round fields used everywhere else.

    Adds: time (UTC), refund, admin_fee, miner_loss, motherlode_hit, pot_before (ORE grams in the
    Motherlode pot while the round was open; NaN before the first known hit), pc_round
    (lamports/ORE), ema_before (the Board.production_cost_ema a deploy in this round sees),
    ema_after, ema_warm (False during the first 5*EMA_WINDOW rounds of the series).
    """
    cols = ["total_deployed", "total_vaulted", "total_winnings", "total_minted", "motherlode",
            "deployed_winning_square", "round_id", "ts", "winning_square", "is_split"]
    df = df.copy()
    for c in cols:
        df[c] = pd.to_numeric(df[c], errors="coerce")
    bad = df[cols].isna().any(axis=1)
    if bad.any():  # e.g. a truncated line in a cache file: drop it rather than guess
        import warnings
        warnings.warn(f"dropping {int(bad.sum())} malformed round rows")
        df = df[~bad]
    df = df.sort_values("round_id").drop_duplicates("round_id").reset_index(drop=True)
    for c in cols:
        df[c] = df[c].astype("int64")
    df["time"] = pd.to_datetime(df["ts"], unit="s", utc=True)
    df["refund"] = (df["winning_square"] < 0) | (df["total_minted"] == 0)
    df["admin_fee"] = df["total_deployed"] - df["total_winnings"] - df["total_vaulted"]
    df["miner_loss"] = df["total_deployed"] - df["total_winnings"]
    df["motherlode_hit"] = df["motherlode"] > 0

    # Motherlode pot. After a hit in round h the pot is emptied and then credited +0.2 ORE, so a
    # deploy in round r > h sees 0.2 * (r - h) ORE, and a hit in round r pays exactly that.
    hits = df.loc[df["motherlode_hit"], ["round_id", "motherlode"]]
    if motherlode_hits is not None and len(motherlode_hits):
        hits = pd.concat([hits, motherlode_hits[["round_id", "motherlode"]]]).drop_duplicates("round_id")
    hit_ids = np.sort(hits["round_id"].to_numpy(dtype=np.int64))
    rid = df["round_id"].to_numpy(dtype=np.int64)
    idx = np.searchsorted(hit_ids, rid, side="left") - 1  # last hit strictly before r
    last_hit = hit_ids[np.clip(idx, 0, None)] if len(hit_ids) else np.full(len(rid), -1)
    pot = np.where(idx >= 0, (rid - last_hit) * MOTHERLODE_PER_ROUND, np.nan)
    df["pot_before"] = pot
    df["pot_before_ore"] = df["pot_before"] / ONE_ORE

    # Production-cost EMA, replayed with the exact integer recursion of reset.rs.
    pcs = [round_production_cost(v, m) for v, m in zip(df["total_vaulted"], df["total_minted"])]
    ema_before, ema_after, ema = [], [], 0
    for pc in pcs:
        ema_before.append(ema)
        if pc is not None:
            ema = ema_update(ema, pc)
        ema_after.append(ema)
    df["pc_round"] = [np.nan if p is None else p for p in pcs]
    df["ema_before"] = np.array(ema_before, dtype=np.int64)
    df["ema_after"] = np.array(ema_after, dtype=np.int64)
    df["ema_warm"] = np.arange(len(df)) >= 5 * EMA_WINDOW
    return df


def load_prices(sample: bool = False) -> pd.DataFrame:
    """Hourly ORE price in SOL (GeckoTerminal ORE/SOL Orca pool). `ts` is the candle START."""
    px = _read("ore_sol_1h.csv", sample).sort_values("ts").drop_duplicates("ts")
    px["ts"] = px["ts"].astype("int64")
    # GeckoTerminal omits hours with no trades; re-index to a full hourly grid and forward-fill.
    grid = np.arange(px["ts"].min(), px["ts"].max() + 3600, 3600, dtype=np.int64)
    px = px.set_index("ts").reindex(grid)
    px["close"] = px["close"].ffill()
    px["open"] = px["open"].fillna(px["close"].shift(1)).fillna(px["close"])
    px.index.name = "ts"
    return px.reset_index()[["ts", "open", "close"]]


def attach_known_price(rounds: pd.DataFrame, prices: pd.DataFrame) -> pd.DataFrame:
    """Price a decision in round r may use: close of the last hourly candle that had ENDED by ts_r.

    No look-ahead: candle [t, t+3600) is usable only when t + 3600 <= ts_r.
    """
    out = rounds.copy()
    cand_end = prices["ts"].to_numpy() + 3600
    closes = prices["close"].to_numpy()
    i = np.searchsorted(cand_end, out["ts"].to_numpy(), side="right") - 1
    ok = i >= 0
    out["price_known"] = np.where(ok, closes[np.clip(i, 0, None)], np.nan)
    out["price_known_asof"] = np.where(ok, cand_end[np.clip(i, 0, None)], -1)
    return out


def price_at(prices: pd.DataFrame, ts: int) -> float:
    """Execution price for a buy at unix time `ts`: open of the candle containing ts."""
    t = prices["ts"].to_numpy()
    i = int(np.searchsorted(t, ts, side="right") - 1)
    if i < 0:
        return float("nan")
    return float(prices["open"].iloc[i])


# --------------------------------------------------------------------------- nights

@dataclass
class ShiftWindow:
    """A nightly shift in the user's local time. Default: 23:00-07:00 WAT (UTC+1)."""
    start_hour_local: float = 23.0
    end_hour_local: float = 7.0
    utc_offset_hours: float = 1.0

    def assign(self, rounds: pd.DataFrame) -> pd.Series:
        """Night label (local calendar date the shift STARTED) or NaT if outside the window."""
        local = rounds["time"] + pd.Timedelta(hours=self.utc_offset_hours)
        h = local.dt.hour + local.dt.minute / 60.0 + local.dt.second / 3600.0
        if self.start_hour_local > self.end_hour_local:
            in_win = (h >= self.start_hour_local) | (h < self.end_hour_local)
            night = local.dt.normalize() - pd.to_timedelta((h < self.end_hour_local).astype(int), unit="D")
        else:
            in_win = (h >= self.start_hour_local) & (h < self.end_hour_local)
            night = local.dt.normalize()
        night = night.dt.tz_localize(None)
        return night.where(in_win)


# --------------------------------------------------------------------------- policy & costs

@dataclass
class Costs:
    crank_fee_lamports: int = 5_000  # Heads Down fixed Discretionary fee per rig per round
    refining_fee: float = REFINING_FEE  # applied to mined ORE if claimed each morning
    buy_cost_bps: float = 50.0  # pool fee + slippage + route for a small Jupiter buy
    tx_lamports_per_night: int = 5_000  # one clock-out tx (claim and/or buy) per night, all strategies


TILE_POLICIES = ("all25", "split15", "solo10", "rand5")


def tile_policy_count(policy: str) -> int:
    return {"all25": 25, "split15": N_SPLIT, "solo10": N_SOLO, "rand5": 5}[policy]


def expected_loss_rate(policy: str) -> float:
    """Expected fraction of deployed SOL lost (admin + protocol fee) for a policy."""
    k = tile_policy_count(policy)
    p_in = k / N_TILES  # P(winning tile is one of ours), true for every policy by symmetry
    # With prob p_in one of our k tiles wins (1% loss), the other k-1 lose 10.9%.
    exp_loss_tiles = p_in * (LOSS_WIN_TILE + (k - 1) * LOSS_LOSE_TILE) + (1 - p_in) * k * LOSS_LOSE_TILE
    return exp_loss_tiles / k


def realized_round_arrays(r: pd.DataFrame, policy: str, per_round_lamports: float,
                          rng: np.random.Generator, n_sims: int) -> Dict[str, np.ndarray]:
    """Monte Carlo of one small miner over real rounds `r` (every round deployed).

    Returns arrays shaped (n_sims, n_rounds): ore (grams, gross, before refining) and loss
    (lamports, admin + protocol fee on our deposit, excluding the crank fee).

    Randomness per simulated miner: which tiles it holds (rand5), and the solo-tile lottery
    (the whole +1 ORE goes to one miner with probability deposit / tile_total). The winning tile,
    its real total, split/solo, and the Motherlode payout come from the real round.
    """
    n = len(r)
    k = tile_policy_count(policy)
    a = per_round_lamports / k  # per tile
    W = r["deployed_winning_square"].to_numpy(dtype=float)
    is_split = r["is_split"].to_numpy(dtype=bool)
    ml = r["motherlode"].to_numpy(dtype=float)
    refund = r["refund"].to_numpy(dtype=bool)
    share = a / (W + a)

    if policy == "all25":
        on_win = np.ones((n_sims, n), dtype=bool)
    elif policy == "split15":
        on_win = np.broadcast_to(is_split, (n_sims, n)).copy()
    elif policy == "solo10":
        on_win = np.broadcast_to(~is_split, (n_sims, n)).copy()
    elif policy == "rand5":
        on_win = rng.random((n_sims, n)) < (k / N_TILES)
    else:
        raise ValueError(policy)
    on_win &= ~refund

    base = np.where(is_split, share, 0.0)  # split: pro-rata 1 ORE
    solo_win = (~is_split) & (rng.random((n_sims, n)) < share)  # solo: lottery for 1 ORE
    ore = on_win * (base * ONE_ORE + ml * share) + (on_win & solo_win) * ONE_ORE

    # SOL loss on our deposit: our winning tile pays 1%, each other tile 10.9%. Refunds lose 0.
    n_lose = np.where(on_win, k - 1, k)
    loss = (on_win * LOSS_WIN_TILE + n_lose * LOSS_LOSE_TILE) * a
    loss = np.where(refund, 0.0, loss)
    return {"ore": ore, "loss": loss}


def _p_on_winning(r: pd.DataFrame, policy: str) -> np.ndarray:
    """P(our tile set contains the real winning tile), given the real round."""
    k = tile_policy_count(policy)
    is_split = r["is_split"].to_numpy(dtype=bool)
    return {"all25": np.ones(len(r)), "split15": is_split.astype(float),
            "solo10": (~is_split).astype(float), "rand5": np.full(len(r), k / N_TILES)}[policy]


def expected_round_ore(r: pd.DataFrame, policy: str, per_round_lamports: float,
                       motherlode: str = "realized") -> np.ndarray:
    """Expected gross ORE grams per round given the real round; only the solo lottery and (for
    rand5) tile choice are integrated out.

    motherlode="realized" pays the real Motherlode payout of the round (what a backtest must use);
    motherlode="ev" replaces it by its expectation pot/500 (what a forecaster could know).
    """
    k = tile_policy_count(policy)
    a = per_round_lamports / k
    W = r["deployed_winning_square"].to_numpy(dtype=float)
    share = a / (W + a)
    if motherlode == "realized":
        ml = r["motherlode"].to_numpy(dtype=float)
    elif motherlode == "ev":
        ml = np.nan_to_num(r["pot_before"].to_numpy(dtype=float), nan=100.0 * ONE_ORE) / MOTHERLODE_ODDS
    else:
        raise ValueError(motherlode)
    val = _p_on_winning(r, policy) * share * (ONE_ORE + ml)
    return np.where(r["refund"].to_numpy(dtype=bool), 0.0, val)


def expected_round_loss(r: pd.DataFrame, policy: str, per_round_lamports: float) -> np.ndarray:
    """Expected admin+protocol fee (lamports) on our deposit, given the real round."""
    k = tile_policy_count(policy)
    a = per_round_lamports / k
    p_on = _p_on_winning(r, policy)
    loss = (p_on * (LOSS_WIN_TILE + (k - 1) * LOSS_LOSE_TILE) + (1 - p_on) * k * LOSS_LOSE_TILE) * a
    return np.where(r["refund"].to_numpy(dtype=bool), 0.0, loss)


# --------------------------------------------------------------------------- gate estimates

def user_cost_estimate(ema_lamports_per_ore: np.ndarray, per_round_lamports: float, costs: Costs,
                       pot_grams: Optional[np.ndarray] = None, claim: bool = True) -> np.ndarray:
    """A small miner's expected SOL cost per net ORE, from on-chain state visible at deploy time.

    The EMA counts only the protocol fee per 1.2 ORE minted, so:
      recent total deployed D ~= EMA * 1.2 / (protocol-fee rate)
      E[gross ORE / round]    = A / (D + A) * (1 + pot/500)      (pot unknown -> 1.2)
      E[SOL cost / round]     = loss_rate * A + crank_fee
    and the refining fee removes 10% of the ORE if it is claimed.
    """
    A = float(per_round_lamports)
    ema = np.asarray(ema_lamports_per_ore, dtype=float)
    protocol_rate = EXPECTED_LOSS_RATE * PROTOCOL_SHARE_OF_LOSS  # 0.09504 of D
    D = ema * 1.2 / protocol_rate
    if pot_grams is None:
        ore_per_round = 1.2
    else:
        ore_per_round = 1.0 + np.nan_to_num(np.asarray(pot_grams, dtype=float), nan=0.2 * ONE_ORE * 500) / ONE_ORE / MOTHERLODE_ODDS
    net = (1.0 - costs.refining_fee) if claim else 1.0
    e_ore = A / (D + A) * ore_per_round * net
    e_cost = EXPECTED_LOSS_RATE * A + costs.crank_fee_lamports
    return e_cost / e_ore / LAMPORTS_PER_SOL  # SOL per ORE


# --------------------------------------------------------------------------- on-chain form

def ema_ev_lamports(ema: int, pot_grams: int) -> int:
    """Motherlode-adjusted EMA, integer-only, as the heads_down program would compute it.

    ema_ev = ema * 1.2 / (1 + pot/500)  ==  ema * 6 * 500 * ONE_ORE / (5 * (500 * ONE_ORE + pot))
    Reads only Board.production_cost_ema and Treasury.motherlode; u128 intermediate, no overflow:
    ema < 2^64, 3000 * ONE_ORE < 2^49, so the product stays < 2^113.
    """
    if ema < 0 or pot_grams < 0:
        raise ValueError("negative input")
    num = int(ema) * 6 * MOTHERLODE_ODDS * ONE_ORE
    den = 5 * (MOTHERLODE_ODDS * ONE_ORE + int(pot_grams))
    return num // den


def ev_threshold_lamports(price_sol_per_ore: float, per_round_lamports: float, costs: "Costs",
                          claim: bool = True) -> int:
    """The plan value `max_ev_cost` the phone signs at arm time.

    Mining beats buying when  k * ema_ev / protocol_rate < price * (1 + buy_cost), where
    k = (loss_rate * A + crank_fee) / (A * net) is the small rig's SOL per protocol-fee lamport.
    (The A / (D + A) share is approximated by A / D; A / D < 1e-3 for any rig this app targets.)
    """
    A = float(per_round_lamports)
    net = (1.0 - costs.refining_fee) if claim else 1.0
    k = (EXPECTED_LOSS_RATE * A + costs.crank_fee_lamports) / (A * net)
    protocol_rate = EXPECTED_LOSS_RATE * PROTOCOL_SHARE_OF_LOSS
    thr = price_sol_per_ore * (1 + costs.buy_cost_bps / 1e4) * protocol_rate / k * LAMPORTS_PER_SOL
    return int(max(0.0, min(thr, 2**63 - 1)))


@dataclass
class StrategyResult:
    name: str
    sol_spent: float  # SOL consumed (mining losses + crank fees + buys + tx)
    ore_mined: float  # net ORE from mining (after refining fee if claimed)
    ore_bought: float
    sol_mining: float
    sol_buying: float
    rounds_mined: int
    rounds_total: int

    @property
    def ore_total(self) -> float:
        return self.ore_mined + self.ore_bought

    @property
    def eff_price(self) -> float:
        return self.sol_spent / self.ore_total if self.ore_total > 0 else float("inf")

    def to_dict(self) -> dict:
        d = asdict(self)
        d.update(ore_total=self.ore_total, eff_price=self.eff_price,
                 mined_fraction=self.rounds_mined / max(self.rounds_total, 1))
        return d
