"""Planner evaluation on synthetic archetypes, parameter tuning, and the exports.

    python -m planner.evaluate            # tune on one population, report on another, export
    python -m planner.evaluate --quick    # tiny populations into out/planner/quick (smoke test)

Protocol: every simulated day at 18:00 local the planner sees only the logs written so far,
forecasts P(idle) for the next 96 slots and proposes a window. Truth is the slot labels the
same logs produce afterwards. Tuning population (seeds 1xxxx) and test population (seeds 2xxxx)
are disjoint; the population prior curve comes from the tuning population only.
"""
from __future__ import annotations

import argparse
import itertools
import json
import math
import os
import time
from dataclasses import replace
from typing import Dict, List, Optional, Sequence, Tuple

import numpy as np

from . import archetypes as ar
from . import model as pm
from .logs import DAY_MS, SLOT_MS, SLOTS_PER_DAY, slot_labels

HERE = os.path.dirname(os.path.abspath(__file__))
MODEL_DIR = os.path.join(HERE, "model")
OUT_DIR = os.path.join(HERE, "..", "out", "planner")
ORIGIN_SLOT = 72  # 18:00
WARMUP_DAYS = 14
ARM_RUN_SLOTS = 8  # the simulated user arms at the start of a 2 h idle stretch inside the window


def log(msg: str) -> None:
    print(f"[{time.strftime('%H:%M:%S')}] {msg}", flush=True)


# ----------------------------------------------------------------------------- data


def label_matrix(user: ar.SimUser) -> np.ndarray:
    M = np.full((user.days + 1, SLOTS_PER_DAY), np.nan)
    for (day, slot), v in slot_labels(user.events).items():
        i = day - user.start_day
        if 0 <= i <= user.days:
            M[i, slot] = v
    return M


def fit_fast(M: np.ndarray, o: int, start_day: int, params: pm.Params, origin_slot: int = ORIGIN_SLOT):
    """Vectorized pm.fit for an origin at sim day o, slot origin_slot (labels before it only)."""
    K = M[: o + 1].copy()
    K[o, origin_slot:] = np.nan
    days = np.arange(o + 1)
    w = np.power(0.5, (o - days) / params.half_life_days)
    dows = (start_day + days + 3) % 7
    wk = np.isin(dows, params.weekend_days).astype(int)
    obs = ~np.isnan(K)
    idle = (K == 1)
    W_obs = w[:, None] * obs
    W_idle = w[:, None] * idle

    def sm(x):
        if len(params.kernel) == 1:
            return params.kernel[0] * x
        h = len(params.kernel) // 2
        return sum(k * np.roll(x, -(j - h), axis=-1) for j, k in enumerate(params.kernel))

    A_all, N_all = sm(W_idle.sum(0)), sm(W_obs.sum(0))
    A_t = np.stack([sm(W_idle[wk == t].sum(0)) for t in (0, 1)])
    N_t = np.stack([sm(W_obs[wk == t].sum(0)) for t in (0, 1)])
    A_d = np.stack([sm(W_idle[dows == d].sum(0)) for d in range(7)])
    N_d = np.stack([sm(W_obs[dows == d].sum(0)) for d in range(7)])
    prior = np.asarray(params.prior)
    m0 = (params.k0 * prior + A_all) / (params.k0 + N_all)
    m1 = (params.k1 * m0[None] + A_t) / (params.k1 + N_t)
    tmap = np.array([1 if d in params.weekend_days else 0 for d in range(7)])
    m2 = (params.k2 * m1[tmap] + A_d) / (params.k2 + N_d)
    ev = N_t[tmap]
    lower = np.maximum(0.0, m2 - params.auto_arm_z * np.sqrt(m2 * (1 - m2) / (ev + 1.0)))
    nights = int(obs.any(axis=1).sum())
    return m2, lower, ev, nights


def horizon_index(o: int, origin_slot: int = ORIGIN_SLOT) -> Tuple[np.ndarray, np.ndarray]:
    k = np.arange(SLOTS_PER_DAY)
    day = o + (origin_slot + k) // SLOTS_PER_DAY
    slot = (origin_slot + k) % SLOTS_PER_DAY
    return day, slot


def slot_objects(user: ar.SimUser, o: int, p: np.ndarray, lower: np.ndarray, ev: np.ndarray) -> List[pm.Slot]:
    day, slot = horizon_index(o)
    out = []
    for k in range(SLOTS_PER_DAY):
        ts = (user.start_day + int(day[k])) * DAY_MS + int(slot[k]) * SLOT_MS - user.tz * 60_000
        out.append(pm.Slot(ts, float(p[k]), float(lower[k]), float(ev[k])))
    return out


def origin_ts(user: ar.SimUser, o: int) -> int:
    return (user.start_day + o) * DAY_MS + ORIGIN_SLOT * SLOT_MS - user.tz * 60_000


def alarm_at(user: ar.SimUser, o: int) -> Optional[int]:
    """The alarm the app would have logged by 18:00 on day o (tomorrow morning's, if any)."""
    a = user.alarms.get(o + 1)
    if a is None:
        return None
    return (user.start_day + o + 1) * DAY_MS + a * 60_000 - user.tz * 60_000


# ----------------------------------------------------------------------------- metrics


def calib(p: np.ndarray, y: np.ndarray, bins: int = 10) -> Dict[str, object]:
    p = np.clip(p, 1e-6, 1 - 1e-6)
    idx = np.clip((p * bins).astype(int), 0, bins - 1)
    table, ece = [], 0.0
    for b in range(bins):
        sel = idx == b
        n = int(sel.sum())
        if n:
            mp, ob = float(p[sel].mean()), float(y[sel].mean())
            ece += n / len(p) * abs(mp - ob)
            table.append({"bin": [b / bins, (b + 1) / bins], "n": n, "mean_p": mp, "observed": ob})
        else:
            table.append({"bin": [b / bins, (b + 1) / bins], "n": 0, "mean_p": None, "observed": None})
    return {
        "brier": float(np.mean((p - y) ** 2)),
        "log_loss": float(-np.mean(y * np.log(p) + (1 - y) * np.log(1 - p))),
        "ece": float(ece),
        "n": int(len(p)),
        "reliability": table,
    }


def main_block(truth: np.ndarray) -> Optional[Tuple[int, int]]:
    """Longest run of IDLE slots (inclusive indices), earliest on ties."""
    best, cur = None, None
    for k, v in enumerate(truth):
        if v == 1:
            cur = (cur[0], k) if cur else (k, k)
            if best is None or cur[1] - cur[0] > best[1] - best[0]:
                best = cur
        else:
            cur = None
    return best


def window_metrics(w_idx: Optional[Tuple[float, float]], truth: np.ndarray) -> Dict[str, float]:
    """Simulates the shift a window would have produced.

    w_idx = (start, end) in fractional slot units from the origin; truth = labels over 96 slots.
    The user lays the phone down for the night (arms) at the first slot inside the window that
    starts at least 2 idle hours (a stand-in for "on the charger at bedtime": an evening lull does
    not arm a Night Shift); the shift then runs until the window ends (COMPLETED: it counts for the
    streak) or until the first non-idle slot (a pickup: BROKEN). Dark hours = the idle time the
    rig ran. Coverage = dark hours / the next 24 h's longest idle block. End error = window end
    minus that block's end (positive = the window was still open when the user woke up).
    """
    blk = main_block(truth)
    blk_len = (blk[1] - blk[0] + 1) if blk else 0
    nan = float("nan")
    if w_idx is None:
        return {"has": 0.0, "armed": 0.0, "completed": nan, "dark_h": 0.0, "coverage": 0.0 if blk else nan,
                "end_err_min": nan, "hours": 0.0}
    a, b = w_idx
    k0 = int(math.ceil(a - 1e-9))
    k1 = min(SLOTS_PER_DAY, int(math.floor(b + 1e-9)))  # slots k0 .. k1-1 lie fully inside the window
    arm = None
    for k in range(k0, k1):
        if k + ARM_RUN_SLOTS > SLOTS_PER_DAY:
            break
        if np.all(truth[k : k + ARM_RUN_SLOTS] == 1):
            arm = k
            break
    end_err = (b - (blk[1] + 1)) * 15.0 if blk else nan
    if arm is None:
        return {"has": 1.0, "armed": 0.0, "completed": nan, "dark_h": 0.0, "coverage": 0.0 if blk else nan,
                "end_err_min": end_err, "hours": (b - a) / 4.0}
    end = arm
    while end < k1 and truth[end] == 1:
        end += 1
    completed = 1.0 if end == k1 else 0.0
    dark = (end - arm) / 4.0
    cov = (max(0, min(end, blk[1] + 1) - max(arm, blk[0])) / blk_len) if blk else nan
    return {"has": 1.0, "armed": 1.0, "completed": completed, "dark_h": dark, "coverage": cov,
            "end_err_min": end_err, "hours": (b - a) / 4.0}


def to_idx(user: ar.SimUser, o: int, w: Optional[pm.Window]) -> Optional[Tuple[float, float]]:
    if w is None:
        return None
    t0 = origin_ts(user, o)
    return ((w.start_ts - t0) / SLOT_MS, (w.end_ts - t0) / SLOT_MS)


def fixed_window(user: ar.SimUser, o: int) -> Tuple[float, float]:
    """23:00 -> 07:00 (local), the default a planner-less app would use."""
    return ((23 * 4 - ORIGIN_SLOT), (24 * 4 + 7 * 4 - ORIGIN_SLOT))


def summarize(rows: List[Dict[str, float]]) -> Dict[str, float]:
    out = {}
    for k in rows[0].keys():
        v = np.array([r[k] for r in rows], dtype=float)
        v = v[~np.isnan(v)]
        out[k] = float(v.mean()) if len(v) else float("nan")
        if k == "end_err_min":
            out["end_err_min_median"] = float(np.median(v)) if len(v) else float("nan")
    # Dark hours that count for the streak: the shift ran to the window end.
    out["completed_dark_h"] = float(np.mean([r["dark_h"] * (r["completed"] if r["completed"] == r["completed"] else 0.0)
                                             for r in rows]))
    return out


# ----------------------------------------------------------------------------- gbdt (optional comparator)


def gbdt_features(M: np.ndarray, o: int, start_day: int) -> np.ndarray:
    K = M[: o + 1].copy()
    K[o, ORIGIN_SLOT:] = np.nan
    day, slot = horizon_index(o)
    rows = []
    for k in range(SLOTS_PER_DAY):
        d, s = int(day[k]), int(slot[k])
        dow = (start_day + d + 3) % 7

        def lab(dd, ss):
            ss = ss % SLOTS_PER_DAY
            return K[dd, ss] if 0 <= dd <= o else np.nan

        lags = [lab(d - j, s) for j in (1, 2, 3, 7, 14)]
        r7 = np.nanmean([lab(d - j, s) for j in range(1, 8)]) if any(not np.isnan(lab(d - j, s)) for j in range(1, 8)) else np.nan
        r28v = [lab(d - j, s) for j in range(1, 29)]
        r28 = np.nanmean(r28v) if np.any(~np.isnan(r28v)) else np.nan
        dv = [lab(d - 7 * j, s) for j in range(1, 5)]
        dowr = np.nanmean(dv) if np.any(~np.isnan(dv)) else np.nan
        nb = [lab(d - 1, s - 1), lab(d - 1, s + 1)]
        ang = 2 * math.pi * s / SLOTS_PER_DAY
        rows.append([math.sin(ang), math.cos(ang), 1.0 if dow >= 5 else 0.0, *[1.0 if dow == i else 0.0 for i in range(7)],
                     k / SLOTS_PER_DAY, *lags, r7, r28, dowr, *nb])
    return np.asarray(rows, dtype=float)


# ----------------------------------------------------------------------------- runs


def run_population(users: Sequence[ar.SimUser], mats: Sequence[np.ndarray], params: pm.Params, gbdt=None,
                   with_windows: bool = True, only_model: bool = False) -> Dict[str, Dict]:
    """Forecasts and windows for every origin of every user, for the model and the baselines."""
    pooled = replace(params, k1=1e12, k2=1e12)
    prior_only = replace(params, k0=1e12, k1=1e12, k2=1e12)
    res: Dict[str, Dict] = {}
    methods = {"beta_dow": params} if only_model else {"prior": prior_only, "pooled": pooled, "beta_dow": params}
    for u, M in zip(users, mats):
        for o in range(WARMUP_DAYS, u.days - 1):
            day, slot = horizon_index(o)
            truth = M[day, slot]
            ok = ~np.isnan(truth)
            now = origin_ts(u, o)
            alarm = alarm_at(u, o)
            for name, prm in methods.items():
                m2, lower, ev, nights = fit_fast(M, o, u.start_day, prm)
                dows = (u.start_day + day + 3) % 7
                p = m2[dows, slot]
                r = res.setdefault(name, {"p": [], "y": [], "arch": [], "win": [], "auto": []})
                r["p"].append(p[ok])
                r["y"].append(truth[ok])
                r["arch"] += [u.archetype] * int(ok.sum())
                if with_windows:
                    slots = slot_objects(u, o, p, lower[dows, slot], ev[dows, slot])
                    w = pm.propose_window(slots, now, alarm, nights, prm, fast=True)
                    met = window_metrics(to_idx(u, o, w), truth)
                    met["archetype"] = u.archetype
                    r["win"].append(met)
                    r["auto"].append((u.archetype, w is not None and w.auto_arm, met["completed"]))
            if gbdt is not None:
                X = gbdt_features(M, o, u.start_day)
                p = gbdt.predict_proba(X)[:, 1]
                r = res.setdefault("gbdt", {"p": [], "y": [], "arch": [], "win": [], "auto": []})
                r["p"].append(p[ok])
                r["y"].append(truth[ok])
                r["arch"] += [u.archetype] * int(ok.sum())
                if with_windows:
                    slots = slot_objects(u, o, p, np.zeros(SLOTS_PER_DAY), np.zeros(SLOTS_PER_DAY))
                    w = pm.propose_window(slots, now, alarm, 0, params, fast=True)
                    met = window_metrics(to_idx(u, o, w), truth)
                    met["archetype"] = u.archetype
                    r["win"].append(met)
            if with_windows and not only_model:
                for name, wfn in (("fixed_23_07", fixed_window), ("yesterday", None)):
                    if wfn is not None:
                        wi = wfn(u, o)
                    else:
                        prev_truth = M[horizon_index(o - 1)]
                        blk = main_block(prev_truth)
                        wi = None if blk is None else (float(blk[0]), float(blk[1] + 1))
                    met = window_metrics(wi, truth)
                    met["archetype"] = u.archetype
                    res.setdefault(name, {"p": [], "y": [], "arch": [], "win": [], "auto": []})["win"].append(met)
    for r in res.values():
        if r["p"]:
            r["p"] = np.concatenate(r["p"])
            r["y"] = np.concatenate(r["y"])
            r["arch"] = np.asarray(r["arch"])
    return res


def tune(users, mats, base: pm.Params) -> Tuple[pm.Params, List[Dict]]:
    """Grid search of the posterior on the tuning population by next-24 h log loss."""
    grid = []
    for hl, k0, k1, k2, ker in itertools.product((14.0, 28.0, 56.0), (2.0, 8.0, 32.0), (4.0, 16.0, 64.0, 256.0),
                                                 (4.0, 16.0, 64.0, 256.0), ((1.0,), (0.5, 1.0, 0.5), (0.25, 0.5, 1.0, 0.5, 0.25))):
        prm = replace(base, half_life_days=hl, k0=k0, k1=k1, k2=k2, kernel=ker)
        ps, ys = [], []
        for u, M in zip(users, mats):
            for o in range(WARMUP_DAYS, u.days - 1, 2):
                day, slot = horizon_index(o)
                truth = M[day, slot]
                ok = ~np.isnan(truth)
                m2, _, _, _ = fit_fast(M, o, u.start_day, prm)
                p = m2[(u.start_day + day + 3) % 7, slot]
                ps.append(p[ok])
                ys.append(truth[ok])
        c = calib(np.concatenate(ps), np.concatenate(ys))
        grid.append({"half_life_days": hl, "k0": k0, "k1": k1, "k2": k2, "kernel": list(ker), "log_loss": c["log_loss"],
                     "brier": c["brier"]})
    best = min(grid, key=lambda g: g["log_loss"])
    tuned = replace(base, half_life_days=best["half_life_days"], k0=best["k0"], k1=best["k1"], k2=best["k2"],
                    kernel=tuple(best["kernel"]))
    return tuned, grid


AUTO_ARM_COMPLETION = 0.85


def tune_window(users, mats, params: pm.Params) -> Tuple[pm.Params, List[Dict]]:
    """tau maximizes completed dark hours per night (dark time that counts for the streak); the
    auto-arm bar is the lowest whose auto-armed shifts completed on >= 90% of tuning nights."""
    rows = []
    candidates = [("kadane", tau, 1.0) for tau in (0.6, 0.7, 0.75, 0.8, 0.85, 0.9)]
    candidates += [("survival", tau, g) for tau in (0.5, 0.7, 0.8) for g in (0.25, 0.5, 1.0)]
    for rule, tau, gamma in candidates:
        prm = replace(params, window_rule=rule, window_threshold=tau, window_gamma=gamma)
        r = run_population(users, mats, prm, only_model=True)["beta_dow"]
        s = summarize([{k: v for k, v in m.items() if k != "archetype"} for m in r["win"]])
        rows.append({"rule": rule, "tau": tau, "gamma": gamma, **s})
    best = max(rows, key=lambda x: x["completed_dark_h"])
    prm = replace(params, window_rule=best["rule"], window_threshold=best["tau"], window_gamma=best["gamma"])
    bars = []
    for bar in (0.6, 0.7, 0.8, 0.85, 0.9, 0.95):
        r = run_population(users, mats, replace(prm, auto_arm_threshold=bar), only_model=True)["beta_dow"]
        autos = [c for _, a, c in r["auto"] if a and not np.isnan(c)]
        bars.append({"bar": bar, "auto_nights": len(autos), "completed": float(np.mean(autos)) if autos else float("nan")})
    ok = [b for b in bars if b["auto_nights"] >= 10 and b["completed"] >= AUTO_ARM_COMPLETION]
    # No bar good enough: auto-arm is effectively off (a lower bound of 1.0 is never reached).
    chosen = min(ok, key=lambda b: b["bar"])["bar"] if ok else 1.0
    return replace(prm, auto_arm_threshold=chosen), rows + [{"auto_arm": bars, "chosen_bar": chosen}]


# ----------------------------------------------------------------------------- summaries


def build_prior(mats: Sequence[np.ndarray]) -> Tuple[float, ...]:
    """Population P(idle) per slot: the tuning population's mean, archetypes weighted equally."""
    stack = np.concatenate([M[:-1] for M in mats], axis=0)
    p = np.nanmean(stack, axis=0)
    return tuple(float(np.clip(v, 0.02, 0.98)) for v in p)


def night_only(M: np.ndarray) -> np.ndarray:
    """Observation only 20:00-10:00 (an app that is alive mostly around shifts)."""
    out = M.copy()
    out[:, 40:80] = np.nan
    return out


def method_summary(r: Dict) -> Dict[str, object]:
    out: Dict[str, object] = {}
    if len(r.get("p", [])):
        out["calibration"] = calib(r["p"], r["y"])
        out["by_archetype"] = {a: {k: v for k, v in calib(r["p"][r["arch"] == a], r["y"][r["arch"] == a]).items()
                                   if k != "reliability"} for a in ar.ARCHETYPES}
    if r.get("win"):
        out["window"] = summarize([{k: v for k, v in m.items() if k != "archetype"} for m in r["win"]])
        out["window_by_archetype"] = {
            a: summarize([{k: v for k, v in m.items() if k != "archetype"} for m in r["win"] if m["archetype"] == a])
            for a in ar.ARCHETYPES}
    if r.get("auto"):
        out["auto_arm"] = {}
        for a in ar.ARCHETYPES:
            rows = [(au, c) for arch, au, c in r["auto"] if arch == a]
            autos = [c for au, c in rows if au and not np.isnan(c)]
            out["auto_arm"][a] = {"nights": len(rows), "auto_armed": len(autos),
                                  "completed": float(np.mean(autos)) if autos else None}
    return out


def forecast_once(u: ar.SimUser, M_seen: np.ndarray, M_truth: np.ndarray, o: int, params: pm.Params):
    day, slot = horizon_index(o)
    truth = M_truth[day, slot]
    ok = ~np.isnan(truth)
    m2, lower, ev, nights = fit_fast(M_seen, o, u.start_day, params)
    dows = (u.start_day + day + 3) % 7
    p = m2[dows, slot]
    w = pm.propose_window(slot_objects(u, o, p, lower[dows, slot], ev[dows, slot]), origin_ts(u, o), alarm_at(u, o), nights, params, fast=True)
    return p[ok], truth[ok], window_metrics(to_idx(u, o, w), truth)


def cold_start(users, mats, params: pm.Params) -> List[Dict]:
    rows = []
    for o in (1, 3, 7, 14, 28):
        ps, ys, wins = [], [], []
        for u, M in zip(users, mats):
            if o >= u.days - 1:
                continue
            p, y, met = forecast_once(u, M, M, o, params)
            ps.append(p)
            ys.append(y)
            wins.append(met)
        if not ps:
            continue
        c = calib(np.concatenate(ps), np.concatenate(ys))
        s = summarize(wins)
        rows.append({"days_of_history": o, "brier": c["brier"], "log_loss": c["log_loss"], "has": s["has"],
                     "completed": s["completed"], "dark_h": s["dark_h"], "coverage": s["coverage"],
                     "completed_dark_h": s["completed_dark_h"]})
    return rows


def night_only_eval(users, mats, params: pm.Params) -> Dict[str, object]:
    """The model sees only 20:00-10:00; it is scored on what the phone really did all day."""
    ps, ys, wins = [], [], []
    for u, M in zip(users, mats):
        Mn = night_only(M)
        for o in range(WARMUP_DAYS, u.days - 1):
            p, y, met = forecast_once(u, Mn, M, o, params)
            ps.append(p)
            ys.append(y)
            wins.append(met)
    return {"calibration": calib(np.concatenate(ps), np.concatenate(ys)), "window": summarize(wins)}


# ----------------------------------------------------------------------------- vectors


def _window_json(w: Optional[pm.Window]) -> Optional[Dict]:
    if w is None:
        return None
    return {"start_ts": w.start_ts, "end_ts": w.end_ts, "slots": w.slots, "mean_p": w.mean_p, "min_p": w.min_p,
            "expected_idle_hours": w.expected_idle_hours, "auto_arm": w.auto_arm, "reason_code": w.reason_code}


def window_margin(slots: Sequence[pm.Slot], params: pm.Params) -> Optional[float]:
    """Relative gap between the best and the second-best window score (None if < 2 candidates)."""
    p = [s.p_idle for s in slots]
    n = len(p)
    logp = [0.0]
    lin = [0.0]
    for v in p:
        logp.append(logp[-1] + math.log(max(v, 1e-12)))
        lin.append(lin[-1] + (v - params.window_threshold))
    scores = []
    for i0 in range(n):
        for i1 in range(i0 + params.min_window_slots - 1, n):
            if params.window_rule == "survival":
                if p[i0] < params.window_threshold or p[i1] < params.window_threshold:
                    continue
                scores.append((i1 - i0 + 1) * math.exp(params.window_gamma * (logp[i1 + 1] - logp[i0])))
            else:
                scores.append(lin[i1 + 1] - lin[i0])
    scores.sort(reverse=True)
    if len(scores) < 2 or scores[0] <= 0:
        return None
    return (scores[0] - scores[1]) / abs(scores[0])


JUNK_LINES = ["not json", '{"ts":"x","tz":60,"type":"screen_on"}', '{"ts":1,"tz":60,"type":"teleport"}',
              '{"ts":2,"tz":900,"type":"screen_on"}', '{"ts":true,"tz":60,"type":"screen_on"}', ""]


def vector_case(name: str, user: ar.SimUser, day: int, minute: int, params: pm.Params, caps: Tuple[int, int, int],
                junk: bool = False) -> Dict:
    from .logs import parse_line

    now = (user.start_day + day) * DAY_MS + minute * 60_000 - user.tz * 60_000
    events = [e for e in user.events if e.ts <= now + 6 * 3_600_000]  # a little future: must be ignored
    lines = [e.to_json() for e in events]
    if junk:
        lines[5:5] = JUNK_LINES
    parsed = [e for e in (parse_line(l) for l in lines) if e is not None]
    out = pm.plan(parsed, now, user.tz, params, *caps)
    week = out["week"]
    # Cross-language vectors must not hinge on an ulp: every night's best window must beat the
    # runner-up clearly (transcendental functions may differ by an ulp between libms).
    for n in range(7):
        t = now + n * DAY_MS
        margin = window_margin(pm.forecast(out["posterior"], t, user.tz, params), params)
        assert margin is None or margin > 1e-9, f"{name}: near-tie between windows on night {n} ({margin})"
    return {
        "name": name,
        "archetype": user.archetype,
        "events_jsonl": lines,
        "parsed_events": len(parsed),
        "now_ts": now,
        "tz": user.tz,
        "caps": {"remaining_week": caps[0], "cap_shift": caps[1], "chunk": caps[2]},
        "labels": [[day * SLOTS_PER_DAY + slot, v] for (day, slot), v in sorted(out["labels"].items())],
        "nights": out["posterior"].nights,
        "slots": {"start_ts": [s.start_ts for s in out["slots"]], "p": [s.p_idle for s in out["slots"]],
                  "lower": [s.lower for s in out["slots"]], "evidence": [s.evidence for s in out["slots"]]},
        "window": _window_json(out["window"]),
        "week": [_window_json(w) for w in week],
        "expected_rounds": [pm.expected_rounds(w, params.round_seconds) for w in week],
        "budget": out["budget"],
    }


def build_vectors(params: pm.Params) -> Dict:
    reg = ar.simulate("regular", 777, days=30)
    sw = ar.simulate("shift_worker", 778, days=30)
    irr = ar.simulate("irregular", 779, days=30)
    caps = (300_000_000, 120_000_000, 1_000_000)
    cases = [
        vector_case("regular_day21_1800", reg, 21, 18 * 60, params, caps, junk=True),
        vector_case("regular_day21_0200_inside_night", reg, 21, 2 * 60, params, caps),
        vector_case("regular_day2_cold_start", reg, 2, 18 * 60, params, caps),
        vector_case("shift_worker_day19_1800", sw, 19, 18 * 60, params, (50_000_000, 30_000_000, 2_000_000)),
        vector_case("irregular_day25_2130_tight_week", irr, 25, 21 * 60 + 30, params, (7_000_000, 120_000_000, 1_000_000)),
    ]
    return {"format": "headsdown.foreman.planner.vectors", "tolerance": 1e-9, "params": params.to_json(), "cases": cases}


# ----------------------------------------------------------------------------- main


def to_jsonable(x):
    if isinstance(x, dict):
        return {str(k): to_jsonable(v) for k, v in x.items()}
    if isinstance(x, (list, tuple)):
        return [to_jsonable(v) for v in x]
    if isinstance(x, (np.floating, float)):
        v = float(x)
        return None if math.isnan(v) else v
    if isinstance(x, (np.integer,)):
        return int(x)
    if isinstance(x, np.bool_):
        return bool(x)
    return x


def fit_gbdt(users, mats):
    from sklearn.ensemble import HistGradientBoostingClassifier

    Xs, ys = [], []
    for u, M in zip(users, mats):
        for o in range(WARMUP_DAYS, u.days - 1, 2):
            day, slot = horizon_index(o)
            truth = M[day, slot]
            ok = ~np.isnan(truth)
            Xs.append(gbdt_features(M, o, u.start_day)[ok])
            ys.append(truth[ok])
    m = HistGradientBoostingClassifier(max_depth=4, max_iter=200, learning_rate=0.05, random_state=0)
    m.fit(np.concatenate(Xs), np.concatenate(ys).astype(int))
    return m


def main(argv: Optional[List[str]] = None) -> int:
    from . import report

    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--quick", action="store_true", help="tiny populations into out/planner/quick; no android sync")
    ap.add_argument("--no-sync", action="store_true", help="do not copy the exports into android/ml")
    ap.add_argument("--render", action="store_true", help="only re-render RESULTS.md from model/planner_metrics.json")
    args = ap.parse_args(argv)
    if args.render:
        with open(os.path.join(MODEL_DIR, "planner_metrics.json"), "r", encoding="utf-8") as fh:
            summary = json.load(fh)
        with open(os.path.join(HERE, "RESULTS.md"), "w", encoding="utf-8") as fh:
            fh.write(report.render(summary))
        return 0
    per = 2 if args.quick else 6
    days = 35 if args.quick else 70
    model_dir = os.path.join(OUT_DIR, "quick") if args.quick else MODEL_DIR
    results_md = os.path.join(OUT_DIR, "quick", "RESULTS.md") if args.quick else os.path.join(HERE, "RESULTS.md")
    os.makedirs(model_dir, exist_ok=True)

    log(f"simulating {per} users per archetype x 2 populations, {days} days each")
    tune_users = ar.population(10_000, per, days)
    test_users = ar.population(20_000, per, days)
    tune_m = [label_matrix(u) for u in tune_users]
    test_m = [label_matrix(u) for u in test_users]
    base = pm.Params(prior=build_prior(tune_m))
    log("tuning the posterior (log loss)")
    params, grid = tune(tune_users, tune_m, base)
    log(f"posterior: half-life {params.half_life_days}, k = ({params.k0}, {params.k1}, {params.k2}), kernel {params.kernel}")
    log("tuning the window threshold and the auto-arm bar")
    params, wgrid = tune_window(tune_users, tune_m, params)
    log(f"window tau {params.window_threshold}, auto-arm bar {params.auto_arm_threshold}")
    log("fitting the optional GBDT comparator on the tuning population")
    gbdt = fit_gbdt(tune_users, tune_m)
    log("evaluating on the test population")
    res = run_population(test_users, test_m, params, gbdt=gbdt)
    summary = {
        "sizes": {"tune": len(tune_users), "test": len(test_users), "per_archetype": per, "days": days},
        "warmup_days": WARMUP_DAYS,
        "arm_run_slots": ARM_RUN_SLOTS,
        "auto_arm_completion_target": AUTO_ARM_COMPLETION,
        "params": params.to_json(),
        "grid": grid,
        "window_grid": wgrid,
        "test": {k: method_summary(v) for k, v in res.items()},
        "night_only": night_only_eval(test_users, test_m, params),
        "cold_start": cold_start(test_users, test_m, params),
    }
    summary = to_jsonable(summary)
    with open(os.path.join(model_dir, "planner_metrics.json"), "w", encoding="utf-8") as fh:
        json.dump(summary, fh, indent=1)
        fh.write("\n")
    pm.save_params(params, os.path.join(model_dir, "planner_params.json"), {
        "name": "planner_beta_dow",
        "model": "per-slot Beta-Bernoulli posterior; shrinkage all days -> weekday/weekend -> day of week",
        "trained_on": f"synthetic archetypes {list(ar.ARCHETYPES)}: tuning seeds 10000+, {per} users each, {days} days",
    })
    with open(os.path.join(model_dir, "planner_vectors.json"), "w", encoding="utf-8") as fh:
        json.dump(to_jsonable(build_vectors(params)), fh, separators=(",", ":"))
        fh.write("\n")
    with open(results_md, "w", encoding="utf-8") as fh:
        fh.write(report.render(summary))
    log(f"wrote {model_dir} and {results_md}")
    if not args.quick and not args.no_sync:
        import shutil

        repo = os.path.abspath(os.path.join(HERE, "..", "..", ".."))
        assets = os.path.join(repo, "android", "ml", "src", "main", "assets", "foreman")
        tests = os.path.join(repo, "android", "ml", "src", "test", "resources", "foreman")
        os.makedirs(assets, exist_ok=True)
        os.makedirs(tests, exist_ok=True)
        shutil.copyfile(os.path.join(model_dir, "planner_params.json"), os.path.join(assets, "planner_params.json"))
        shutil.copyfile(os.path.join(model_dir, "planner_vectors.json"), os.path.join(tests, "planner_vectors.json"))
        log("synced planner_params.json (asset) and planner_vectors.json (test resource) into android/ml")
    return 0


if __name__ == "__main__":
    import sys

    sys.exit(main())
