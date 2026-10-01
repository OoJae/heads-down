"""The rhythm model: a per-slot Beta-Bernoulli posterior with day-of-week effects.

For each 15-minute local slot s and day of week d, past observations (IDLE = 1, BUSY = 0) are
weighted by recency, w = 0.5 ** (age_days / half_life_days), optionally smoothed over
neighbouring slots, and pooled in three levels that shrink into each other:

    all days:  m0[s]    = (k0 * prior[s]   + A_all[s])  / (k0 + N_all[s])
    day type:  m1[t][s] = (k1 * m0[s]      + A_t[t][s]) / (k1 + N_t[t][s])     t = weekday / weekend
    weekday:   m2[d][s] = (k2 * m1[t(d)][s] + A_d[d][s]) / (k2 + N_d[d][s])

P(idle) = m2. With no history it is the population prior; with weeks of history it is the
user's own rate for that weekday and slot. Every number is a weighted count, so it explains
itself ("idle on 9 of your last 10 Tuesdays at 23:15").

The conservative lower bound used for auto-arm counts only the user's own evidence:
    lb = m2 - z * sqrt(m2 (1 - m2) / (N_t[t][s] + 1)).

android/ml (xyz.headsdown.ml.planner) implements the same arithmetic; planner_vectors.json pins it.
"""
from __future__ import annotations

import json
import math
from dataclasses import asdict, dataclass, field
from typing import Dict, List, Optional, Sequence, Tuple

from .logs import DAY_MS, SLOT_MS, SLOTS_PER_DAY, IDLE, day_of_week

FORMAT = "headsdown.foreman.planner"
ROUND_SECONDS = 78.0


@dataclass
class Params:
    half_life_days: float = 21.0
    k0: float = 2.0
    k1: float = 4.0
    k2: float = 4.0
    kernel: Tuple[float, ...] = (0.5, 1.0, 0.5)  # weights for slot offsets -1, 0, +1 (circular)
    weekend_days: Tuple[int, ...] = (5, 6)  # Saturday, Sunday (Monday = 0)
    window_rule: str = "survival"  # "survival" (length x prod p^gamma) or "kadane" (max sum of p - tau)
    window_threshold: float = 0.6  # tau: kadane's per-slot bar; survival's bar for a run's first and last slot
    window_gamma: float = 0.5
    min_window_slots: int = 8  # 2 h
    auto_arm_threshold: float = 0.8
    auto_arm_z: float = 1.0
    auto_arm_min_nights: int = 7
    horizon_slots: int = 96
    round_seconds: float = ROUND_SECONDS
    prior: Tuple[float, ...] = field(default_factory=lambda: tuple([0.5] * SLOTS_PER_DAY))

    def to_json(self) -> Dict:
        d = asdict(self)
        d["kernel"] = list(self.kernel)
        d["weekend_days"] = list(self.weekend_days)
        d["prior"] = list(self.prior)
        return d

    @staticmethod
    def from_json(d: Dict) -> "Params":
        p = Params(**{k: v for k, v in d.items() if k in Params.__dataclass_fields__})
        p.kernel = tuple(p.kernel)
        p.weekend_days = tuple(p.weekend_days)
        p.prior = tuple(p.prior)
        return p


def is_weekend(day: int, params: Params) -> int:
    return 1 if day_of_week(day) in params.weekend_days else 0


@dataclass
class Posterior:
    mean: List[List[float]]  # [dow][slot]
    lower: List[List[float]]
    evidence: List[List[float]]  # weighted observations at the day-type level
    nights: int  # distinct local days with any observation


def fit(labels: Dict[Tuple[int, int], int], now_day: int, params: Params) -> Posterior:
    """Weighted counts per level, then the three-level shrinkage. Order of summation: labels
    sorted by (day, slot), weights accumulated in that order (Kotlin does the same)."""
    A_all = [0.0] * SLOTS_PER_DAY
    N_all = [0.0] * SLOTS_PER_DAY
    A_t = [[0.0] * SLOTS_PER_DAY for _ in range(2)]
    N_t = [[0.0] * SLOTS_PER_DAY for _ in range(2)]
    A_d = [[0.0] * SLOTS_PER_DAY for _ in range(7)]
    N_d = [[0.0] * SLOTS_PER_DAY for _ in range(7)]
    days = set()
    for (day, slot) in sorted(labels):
        if day > now_day:
            continue
        y = labels[(day, slot)]
        w = math.pow(0.5, (now_day - day) / params.half_life_days)
        days.add(day)
        d = day_of_week(day)
        t = is_weekend(day, params)
        hit = w if y == IDLE else 0.0
        A_all[slot] += hit
        N_all[slot] += w
        A_t[t][slot] += hit
        N_t[t][slot] += w
        A_d[d][slot] += hit
        N_d[d][slot] += w
    A_all, N_all = _smooth(A_all, params.kernel), _smooth(N_all, params.kernel)
    A_t = [_smooth(x, params.kernel) for x in A_t]
    N_t = [_smooth(x, params.kernel) for x in N_t]
    A_d = [_smooth(x, params.kernel) for x in A_d]
    N_d = [_smooth(x, params.kernel) for x in N_d]
    m0 = [(params.k0 * params.prior[s] + A_all[s]) / (params.k0 + N_all[s]) for s in range(SLOTS_PER_DAY)]
    m1 = [[(params.k1 * m0[s] + A_t[t][s]) / (params.k1 + N_t[t][s]) for s in range(SLOTS_PER_DAY)] for t in range(2)]
    mean, lower, evid = [], [], []
    for d in range(7):
        t = 1 if d in params.weekend_days else 0
        row, lrow, erow = [], [], []
        for s in range(SLOTS_PER_DAY):
            m = (params.k2 * m1[t][s] + A_d[d][s]) / (params.k2 + N_d[d][s])
            sd = math.sqrt(m * (1.0 - m) / (N_t[t][s] + 1.0))
            row.append(m)
            lrow.append(max(0.0, m - params.auto_arm_z * sd))
            erow.append(N_t[t][s])
        mean.append(row)
        lower.append(lrow)
        evid.append(erow)
    return Posterior(mean, lower, evid, len(days))


def _smooth(x: List[float], kernel: Sequence[float]) -> List[float]:
    """Circular smoothing: y[s] = sum_j kernel[j] * x[(s + j - h) mod 96], j in order."""
    if len(kernel) == 1:
        return [kernel[0] * v for v in x]
    h = len(kernel) // 2
    n = len(x)
    out = []
    for s in range(n):
        acc = 0.0
        for j, k in enumerate(kernel):
            acc += k * x[(s + j - h) % n]
        out.append(acc)
    return out


@dataclass
class Slot:
    start_ts: int
    p_idle: float
    lower: float
    evidence: float


def forecast(post: Posterior, now_ts: int, tz: int, params: Params) -> List[Slot]:
    """P(idle) for the next `horizon_slots` slots, from the slot containing now (local tz)."""
    local = now_ts + tz * 60_000
    first = (local // SLOT_MS) * SLOT_MS
    out = []
    for i in range(params.horizon_slots):
        ls = first + i * SLOT_MS
        day, slot = ls // DAY_MS, (ls % DAY_MS) // SLOT_MS
        d = day_of_week(day)
        out.append(Slot(ls - tz * 60_000, post.mean[d][slot], post.lower[d][slot], post.evidence[d][slot]))
    return out


@dataclass
class Window:
    start_ts: int
    end_ts: int
    slots: int
    mean_p: float
    min_p: float
    expected_idle_hours: float
    auto_arm: bool
    reason: str
    reason_code: str = ""  # "ok" | "history" | "confidence" (stable across languages)


def best_run_kadane(slots: Sequence[Slot], params: Params) -> Optional[Tuple[int, int]]:
    """The contiguous run maximizing sum(p - tau) (the earliest run wins ties)."""
    best_sum, best = 0.0, None
    cur_sum, cur_start = 0.0, 0
    for i, s in enumerate(slots):
        v = s.p_idle - params.window_threshold
        if cur_sum <= 0.0:
            cur_sum, cur_start = v, i
        else:
            cur_sum += v
        if cur_sum > best_sum:
            best_sum, best = cur_sum, (cur_start, i)
    if best is None or best[1] - best[0] + 1 < params.min_window_slots:
        return None
    return best


def best_run_survival(slots: Sequence[Slot], params: Params) -> Optional[Tuple[int, int]]:
    """The run (at least min_window_slots long) maximizing expected completed dark time,
    length x prod(p)^gamma: every extra slot adds time but must also stay idle, or the shift
    breaks. gamma < 1 discounts the independence assumption (nights are correlated). Slots with
    p < tau never start or end a run. Earliest start, then shortest, wins ties."""
    n = len(slots)
    logs = [math.log(max(s.p_idle, 1e-12)) for s in slots]
    prefix = [0.0]
    for v in logs:
        prefix.append(prefix[-1] + v)
    best_score, best = 0.0, None
    for i0 in range(n):
        if slots[i0].p_idle < params.window_threshold:
            continue
        for i1 in range(i0 + params.min_window_slots - 1, n):
            if slots[i1].p_idle < params.window_threshold:
                continue
            score = (i1 - i0 + 1) * math.exp(params.window_gamma * (prefix[i1 + 1] - prefix[i0]))
            if score > best_score:
                best_score, best = score, (i0, i1)
    return best


def best_run_survival_fast(slots: Sequence[Slot], params: Params) -> Optional[Tuple[int, int]]:
    """numpy form of best_run_survival for the evaluation loops (tests check they agree)."""
    import numpy as np

    p = np.array([s.p_idle for s in slots])
    n = len(p)
    prefix = np.concatenate([[0.0], np.cumsum(np.log(np.maximum(p, 1e-12)))])
    i0 = np.arange(n)[:, None]
    i1 = np.arange(n)[None, :]
    length = i1 - i0 + 1
    ok = (length >= params.min_window_slots) & (p[:, None] >= params.window_threshold) & (p[None, :] >= params.window_threshold)
    score = np.where(ok, length * np.exp(params.window_gamma * (prefix[1:][None, :] - prefix[:-1][:, None])), 0.0)
    k = int(np.argmax(score))
    if score.flat[k] <= 0.0:
        return None
    return divmod(k, n)


def propose_window(slots: Sequence[Slot], now_ts: int, alarm_ts: Optional[int], nights: int, params: Params,
                   fast: bool = False) -> Optional[Window]:
    """The best run under `params.window_rule` ("survival" or "kadane"), its end clamped to the
    next alarm. None if no run qualifies."""
    if params.window_rule == "survival":
        best = best_run_survival_fast(slots, params) if fast else best_run_survival(slots, params)
    else:
        best = best_run_kadane(slots, params)
    if best is None:
        return None
    i0, i1 = best
    start = max(slots[i0].start_ts, now_ts)
    end = slots[i1].start_ts + SLOT_MS
    if alarm_ts is not None and start < alarm_ts < end:
        end = alarm_ts
    if end <= start:
        return None
    run = slots[i0 : i1 + 1]
    ps = [s.p_idle for s in run]
    mean_p = sum(ps) / len(ps)
    exp_hours = 0.0
    for s in run:
        overlap = max(0, min(end, s.start_ts + SLOT_MS) - max(start, s.start_ts))
        exp_hours += s.p_idle * overlap / 3_600_000.0
    lowest = min(s.lower for s in run)
    if nights < params.auto_arm_min_nights:
        auto, why, code = False, f"needs {params.auto_arm_min_nights} nights of history (has {nights})", "history"
    elif lowest < params.auto_arm_threshold:
        auto, why, code = False, f"lowest confident P(idle) {lowest:.2f} < {params.auto_arm_threshold:.2f}", "confidence"
    else:
        auto, why, code = True, "every slot confidently idle", "ok"
    return Window(start, end, len(run), mean_p, min(ps), exp_hours, auto, why, code)


@dataclass
class NightBudget:
    start_ts: int
    end_ts: int
    expected_idle_rounds: float
    lamports: int


def expected_rounds(w: Optional[Window], round_seconds: float) -> float:
    return 0.0 if w is None else w.expected_idle_hours * 3600.0 / round_seconds


def split_week(windows: Sequence[Optional[Window]], remaining_week: int, cap_shift: int, chunk: int,
               round_seconds: float = ROUND_SECONDS) -> List[int]:
    """D'Hondt allocation of whole dig chunks across nights, in proportion to expected idle
    rounds: each night <= cap_shift and <= one chunk per expected idle round; the total <=
    remaining_week. Ties go to the earlier night."""
    if chunk <= 0:
        return [0] * len(windows)
    weights = []
    caps = []
    for w in windows:
        rounds = expected_rounds(w, round_seconds)
        weights.append(rounds)
        caps.append(min(max(0, cap_shift) // chunk, int(math.floor(rounds))))
    alloc = [0] * len(windows)
    left = max(0, remaining_week) // chunk
    while left > 0:
        best, best_q = -1, 0.0
        for i, wgt in enumerate(weights):
            if alloc[i] >= caps[i] or wgt <= 0.0:
                continue
            q = wgt / (alloc[i] + 1)
            if best < 0 or q > best_q:
                best, best_q = i, q
        if best < 0:
            break
        alloc[best] += 1
        left -= 1
    return [a * chunk for a in alloc]


def plan(events, now_ts: int, tz: int, params: Params, remaining_week: Optional[int] = None,
         cap_shift: Optional[int] = None, chunk: Optional[int] = None) -> Dict:
    """The full planner output, exactly what xyz.headsdown.ml.planner.RhythmShiftPlanner returns:
    the next 24 h of slots, the next window, and the next 7 nights with an optional budget split."""
    from .logs import last_alarm, slot_labels

    labels = slot_labels(events, until_ts=now_ts)
    now_day = (now_ts + tz * 60_000) // DAY_MS
    post = fit(labels, now_day, params)
    slots = forecast(post, now_ts, tz, params)
    window = propose_window(slots, now_ts, last_alarm(events, now_ts), post.nights, params)
    week: List[Optional[Window]] = [window]
    for n in range(1, 7):
        t = now_ts + n * DAY_MS
        week.append(propose_window(forecast(post, t, tz, params), t, None, post.nights, params))
    budget = None
    if remaining_week is not None and cap_shift is not None and chunk is not None:
        budget = split_week(week, remaining_week, cap_shift, chunk, params.round_seconds)
    return {"labels": labels, "posterior": post, "slots": slots, "window": window, "week": week, "budget": budget}


def save_params(params: Params, path: str, meta: Dict) -> None:
    doc = {"format": FORMAT, "version": 1, "params": params.to_json(), **meta}
    with open(path, "w", encoding="utf-8") as fh:
        json.dump(doc, fh, indent=1)
        fh.write("\n")


def load_params(path: str) -> Params:
    with open(path, "r", encoding="utf-8") as fh:
        doc = json.load(fh)
    assert doc["format"] == FORMAT and doc["version"] == 1
    return Params.from_json(doc["params"])
