"""Metrics for the pickup classifier: recall at a threshold, false-break rates, breakdowns, calibration."""
from __future__ import annotations

import math
from collections import OrderedDict
from typing import Dict, List, Optional, Sequence, Tuple

import numpy as np

NEGATIVES = ("bump", "slide", "set_down")


def wilson(k: int, n: int, z: float = 1.96) -> Tuple[Optional[float], Optional[float]]:
    if n == 0:
        return None, None
    p = k / n
    d = 1 + z * z / n
    c = (p + z * z / (2 * n)) / d
    h = z * math.sqrt(p * (1 - p) / n + z * z / (4 * n * n)) / d
    return max(0.0, c - h), min(1.0, c + h)


def rate(k: int, n: int) -> Dict[str, object]:
    lo, hi = wilson(k, n)
    return {"k": int(k), "n": int(n), "rate": (k / n if n else None), "ci95": [lo, hi]}


def threshold_for_recall(s_pos: np.ndarray, target: float) -> float:
    """Largest threshold t with mean(s_pos >= t) >= target."""
    s = np.sort(s_pos)
    misses = int(math.floor((1.0 - target) * len(s) + 1e-9))
    return float(s[min(misses, len(s) - 1)])


def choose_threshold(s_pos: np.ndarray, s_neg: np.ndarray, recall: float, neg_quantile: float = 0.995) -> Tuple[float, str, float]:
    """Threshold in calibrated-logit space.

    q_pos = the largest threshold keeping validation recall >= `recall`; q_neg = the score above
    which at most 0.5% of validation non-pickups sit. If q_neg < q_pos there is a gap and the
    threshold is its midpoint (robust: it does not sit on the lowest pickups); otherwise the recall
    bound q_pos wins. Returns (threshold, rule, gap = q_pos - q_neg).
    """
    q_pos = threshold_for_recall(s_pos, recall)
    s = np.sort(s_neg)[::-1]
    k = int(math.floor((1.0 - neg_quantile) * len(s) + 1e-9))
    q_neg = float(s[min(k, len(s) - 1)])
    if q_neg < q_pos:
        return 0.5 * (q_neg + q_pos), "midpoint of the validation gap", q_pos - q_neg
    return q_pos, f"recall bound ({recall:.3f})", q_pos - q_neg


def rates_at(p: np.ndarray, labels: np.ndarray, t: float) -> Dict[str, object]:
    fire = p >= t
    out: Dict[str, object] = OrderedDict()
    pos = labels == "pickup"
    out["pickup_recall"] = rate(int(fire[pos].sum()), int(pos.sum()))
    for lab in NEGATIVES:
        sel = labels == lab
        out[f"{lab}_false_break"] = rate(int(fire[sel].sum()), int(sel.sum()))
    neg = ~pos
    out["all_negatives_false_break"] = rate(int(fire[neg].sum()), int(neg.sum()))
    return out


def breakdown(p: np.ndarray, labels: np.ndarray, metas: Sequence[Dict], t: float, key: str) -> Dict[str, Dict]:
    """Per value of meta[key]: pickup recall and bump / all-negative false-break rates."""
    vals = np.array([str(m.get(key)) for m in metas])
    fire = p >= t
    out: Dict[str, Dict] = OrderedDict()
    for v in sorted(set(vals)):
        sel = vals == v
        pos = sel & (labels == "pickup")
        bump = sel & (labels == "bump")
        neg = sel & (labels != "pickup")
        out[v] = {
            "pickup_recall": rate(int(fire[pos].sum()), int(pos.sum())),
            "bump_false_break": rate(int(fire[bump].sum()), int(bump.sum())),
            "negatives_false_break": rate(int(fire[neg].sum()), int(neg.sum())),
        }
    return out


def variant_breakdown(p: np.ndarray, labels: np.ndarray, metas: Sequence[Dict], t: float) -> Dict[str, Dict]:
    fire = p >= t
    keys = np.array([f"{lab}/{m.get('variant')}" for lab, m in zip(labels, metas)])
    out: Dict[str, Dict] = OrderedDict()
    for v in sorted(set(keys)):
        sel = keys == v
        k = int(fire[sel].sum())
        # For pickups this is recall; for the negatives it is the false-break rate.
        out[v] = rate(k, int(sel.sum()))
    return out


def calibration(p: np.ndarray, y: np.ndarray, bins: int = 10) -> Dict[str, object]:
    p = np.clip(p, 1e-12, 1 - 1e-12)
    edges = np.linspace(0.0, 1.0, bins + 1)
    idx = np.clip(np.digitize(p, edges[1:-1]), 0, bins - 1)
    table = []
    ece = 0.0
    for b in range(bins):
        sel = idx == b
        n = int(sel.sum())
        if n == 0:
            table.append({"bin": [float(edges[b]), float(edges[b + 1])], "n": 0, "mean_p": None, "observed": None})
            continue
        mp, ob = float(p[sel].mean()), float(y[sel].mean())
        ece += n / len(p) * abs(mp - ob)
        table.append({"bin": [float(edges[b]), float(edges[b + 1])], "n": n, "mean_p": mp, "observed": ob})
    return {
        "ece": float(ece),
        "brier": float(np.mean((p - y) ** 2)),
        "log_loss": float(-np.mean(y * np.log(p) + (1 - y) * np.log(1 - p))),
        "reliability": table,
    }


def mass_bucket(m: Dict) -> str:
    kg = float(m.get("mass_kg", 0.0))
    return "light (<0.18 kg)" if kg < 0.18 else ("heavy (>0.23 kg)" if kg > 0.23 else "typical")


def rate_bucket(m: Dict) -> str:
    hz = float(m.get("rate_hz", 50.0))
    if hz < 45:
        return "~40 Hz"
    if hz <= 55:
        return "~50 Hz"
    return "62.5-100 Hz"


def noise_bucket(m: Dict) -> str:
    n = float(m.get("noise_rms", 0.0))
    return "noise <0.01" if n < 0.01 else ("noise 0.01-0.03" if n < 0.03 else "noise >=0.03")


def evaluate_split(score: np.ndarray, labels: np.ndarray, metas: List[Dict], t: float) -> Dict[str, object]:
    """`score` = calibrated logit (+inf for fail-closed windows); `t` = threshold_logit."""
    for m in metas:
        m["mass_bucket"] = mass_bucket(m)
        m["rate_bucket"] = rate_bucket(m)
        m["noise_bucket"] = noise_bucket(m)
    y = (labels == "pickup").astype(float)
    with np.errstate(over="ignore"):
        p = 1.0 / (1.0 + np.exp(-score))
    return {
        "at_threshold": rates_at(score, labels, t),
        "by_surface": breakdown(score, labels, metas, t, "surface"),
        "by_variant": variant_breakdown(score, labels, metas, t),
        "by_mass": breakdown(score, labels, metas, t, "mass_bucket"),
        "by_rate": breakdown(score, labels, metas, t, "rate_bucket"),
        "by_noise": breakdown(score, labels, metas, t, "noise_bucket"),
        "calibration": calibration(p, y),
        "fail_closed": int(np.sum(np.isinf(score))),
        "n": int(len(score)),
    }
