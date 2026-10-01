"""Feature pipeline v1: the reference implementation of FEATURE_SPEC.md.

Every step is written so a scalar Kotlin loop reproduces it: integer-nanosecond resampling,
sequential (left-to-right) sums via cumsum, the same operation order, the same constants.
android/ml's `PickupFeatures` is checked against `model/pickup_vectors.json` at 1e-6 (the
observed difference is ~1e-12; transcendental functions may differ by an ulp between libms).
"""
from __future__ import annotations

import math
from typing import Dict, List, Optional, Sequence, Tuple

import numpy as np

from .trigger import Window

SPEC_VERSION = 1
G = 9.80665
N = 250  # grid points
T0 = 100  # grid index of the trigger
STEP_NS = 20_000_000  # 50 Hz grid
DT = 0.02
TAU = 0.4
ALPHA = DT / (TAU + DT)
RAD_TO_DEG = 57.29577951308232
PRE_NS = 2_000_000_000
POST_NS = 3_000_000_000
MIN_SAMPLES = 100
MAX_GAP_NS = 500_000_000
N_FFT = 150  # POST segment length
CHANNELS = ("mag_dev", "up_dyn", "horiz", "tilt", "rot")

FEATURE_NAMES: Tuple[str, ...] = (
    "pre_tilt",
    "pre_motion",
    "pre_mag",
    "tilt_end",
    "tilt_max",
    "tilt_min",
    "tilt_path",
    "rot_end",
    "rot_max",
    "rot_1s",
    "rot_2s",
    "rot_path",
    "late_motion",
    "tail_motion",
    "late_mag_std",
    "early_dyn",
    "mid_dyn",
    "late_dyn",
    "dyn_peak",
    "mag_dev_peak",
    "settle",
    "jerk_post",
    "jerk_late",
    "jerk_peak",
    "up_peak",
    "down_peak",
    "vel_up_max",
    "vel_up_min",
    "disp_up_max",
    "horiz_rms",
    "horiz_peak",
    "vel_h_max",
    "spec_lo",
    "spec_mid",
    "spec_hi",
    "spec_log_energy",
    "spec_centroid",
    "rot_late_std",
    "late_mag_dev",
)


# ----------------------------------------------------------------------------- cleaning + quality


def clean(t_ns: np.ndarray, acc: np.ndarray) -> Tuple[np.ndarray, np.ndarray]:
    """Drops non-finite samples and any sample whose timestamp does not increase (keep-first)."""
    t_ns = np.asarray(t_ns, dtype=np.int64)
    acc = np.asarray(acc, dtype=np.float32)
    keep = np.zeros(len(t_ns), dtype=bool)
    last: Optional[int] = None
    finite = np.isfinite(acc).all(axis=1)
    for i in range(len(t_ns)):
        if not finite[i]:
            continue
        if last is not None and t_ns[i] <= last:
            continue
        keep[i] = True
        last = int(t_ns[i])
    return t_ns[keep], acc[keep]


def quality(t_ns: np.ndarray, trigger_ns: int) -> Optional[str]:
    """None if the window is usable, else the fail-closed reason (the verdict is then PICKUP)."""
    lo, hi = trigger_ns - PRE_NS, trigger_ns + POST_NS
    inside = t_ns[(t_ns >= lo) & (t_ns <= hi)]
    if len(inside) < MIN_SAMPLES:
        return "too_few_samples"
    edges = np.concatenate([[lo], inside, [hi]])
    if int(np.max(np.diff(edges))) > MAX_GAP_NS:
        return "gap"
    return None


# ----------------------------------------------------------------------------- resampling


def resample(t_ns: np.ndarray, acc: np.ndarray, trigger_ns: int) -> np.ndarray:
    """Linear interpolation onto grid t_i = trigger + (i - 100) * 20 ms, i = 0..249.

    Integer-ns bracketing; value = y_j + (y_{j+1} - y_j) * ((g - t_j) / (t_{j+1} - t_j)) with
    the ns differences converted to double; grid points outside the samples hold the edge value.
    """
    y = acc.astype(np.float64)
    grid = trigger_ns + (np.arange(N, dtype=np.int64) - T0) * STEP_NS
    j = np.searchsorted(t_ns, grid, side="right") - 1
    out = np.empty((N, 3), dtype=np.float64)
    before = j < 0
    after = j >= len(t_ns) - 1
    mid = ~(before | after)
    out[before] = y[0]
    out[after] = y[-1]
    jm = j[mid]
    num = (grid[mid] - t_ns[jm]).astype(np.float64)
    den = (t_ns[jm + 1] - t_ns[jm]).astype(np.float64)
    frac = (num / den)[:, None]
    out[mid] = y[jm] + (y[jm + 1] - y[jm]) * frac
    return out


# ----------------------------------------------------------------------------- helpers (batched)


def _ssum(x: np.ndarray, axis: int) -> np.ndarray:
    """Sequential left-to-right sum (what a Kotlin loop does)."""
    return np.take(np.cumsum(x, axis=axis), -1, axis=axis)


def _norm3(v: np.ndarray) -> np.ndarray:
    return np.sqrt(v[..., 0] * v[..., 0] + v[..., 1] * v[..., 1] + v[..., 2] * v[..., 2])


def _dot3(a: np.ndarray, b: np.ndarray) -> np.ndarray:
    return a[..., 0] * b[..., 0] + a[..., 1] * b[..., 1] + a[..., 2] * b[..., 2]


def _cross_norm(a: np.ndarray, b: np.ndarray) -> np.ndarray:
    cx = a[..., 1] * b[..., 2] - a[..., 2] * b[..., 1]
    cy = a[..., 2] * b[..., 0] - a[..., 0] * b[..., 2]
    cz = a[..., 0] * b[..., 1] - a[..., 1] * b[..., 0]
    return np.sqrt(cx * cx + cy * cy + cz * cz)


def _angle_deg(a: np.ndarray, b: np.ndarray) -> np.ndarray:
    """Angle between vectors, degrees, via atan2(|a x b|, a . b) (well conditioned near 0)."""
    return np.arctan2(_cross_norm(a, b), _dot3(a, b)) * RAD_TO_DEG


def _tilt_deg(g: np.ndarray) -> np.ndarray:
    """Angle from screen-straight-down: atan2(sqrt(gx^2 + gy^2), -gz)."""
    return np.arctan2(np.sqrt(g[..., 0] * g[..., 0] + g[..., 1] * g[..., 1]), -g[..., 2]) * RAD_TO_DEG


def _mean_seg(x: np.ndarray, lo: int, hi: int) -> np.ndarray:
    return _ssum(x[:, lo:hi], axis=1) / float(hi - lo)


def _rms_seg(x: np.ndarray, lo: int, hi: int) -> np.ndarray:
    return np.sqrt(_ssum(x[:, lo:hi] * x[:, lo:hi], axis=1) / float(hi - lo))


def _vec_motion(a: np.ndarray, lo: int, hi: int) -> np.ndarray:
    """sqrt(mean |a_i - mean(a)|^2) over [lo, hi)."""
    mu = _ssum(a[:, lo:hi, :], axis=1) / float(hi - lo)  # (B,3)
    d = a[:, lo:hi, :] - mu[:, None, :]
    sq = d[..., 0] * d[..., 0] + d[..., 1] * d[..., 1] + d[..., 2] * d[..., 2]
    return np.sqrt(_ssum(sq, axis=1) / float(hi - lo))


def _pop_std(x: np.ndarray, lo: int, hi: int) -> np.ndarray:
    mu = _mean_seg(x, lo, hi)
    d = x[:, lo:hi] - mu[:, None]
    return np.sqrt(_ssum(d * d, axis=1) / float(hi - lo))


_J = np.arange(N_FFT)
COS_TABLE = np.cos(2.0 * math.pi * _J / N_FFT)
SIN_TABLE = np.sin(2.0 * math.pi * _J / N_FFT)
HANN = 0.5 - 0.5 * np.cos(2.0 * math.pi * _J / (N_FFT - 1))
_KN = (np.arange(1, 76)[:, None] * _J[None, :]) % N_FFT  # (75, 150)


# ----------------------------------------------------------------------------- signals + features


def signals(A: np.ndarray) -> Dict[str, np.ndarray]:
    """Derived per-sample signals for a batch of resampled windows A (B, 250, 3)."""
    B = A.shape[0]
    r = _ssum(A[:, 0:90, :], axis=1) / 90.0  # (B,3)
    rm = _norm3(r)
    safe = rm >= 1e-6
    u = np.where(safe[:, None], r / np.where(safe, rm, 1.0)[:, None], np.array([0.0, 0.0, -1.0])[None])
    g = np.empty_like(A)
    cur = r.copy()
    for i in range(N):
        cur = cur + ALPHA * (A[:, i, :] - cur)
        g[:, i, :] = cur
    m = _norm3(A)
    theta = _tilt_deg(g)
    phi = _angle_deg(g, r[:, None, :])
    d = A - g
    dm = _norm3(d)
    p = _dot3(A, u[:, None, :])
    w = p - rm[:, None]
    hvec = A - p[..., None] * u[:, None, :]
    h = _norm3(hvec)
    return {"r": r, "rm": rm, "u": u, "g": g, "m": m, "theta": theta, "phi": phi, "dm": dm, "w": w, "hvec": hvec, "h": h}


def features_from_grid(A: np.ndarray) -> np.ndarray:
    """(B, 250, 3) resampled windows -> (B, 39) features in FEATURE_NAMES order."""
    S = signals(A)
    r, rm, g, m, theta, phi, dm, w, hvec, h = (S[k] for k in ("r", "rm", "g", "m", "theta", "phi", "dm", "w", "hvec", "h"))
    B = A.shape[0]
    F: List[np.ndarray] = []
    F.append(_tilt_deg(r))  # pre_tilt
    dpre = A[:, 0:90, :] - r[:, None, :]
    F.append(np.sqrt(_ssum(dpre[..., 0] * dpre[..., 0] + dpre[..., 1] * dpre[..., 1] + dpre[..., 2] * dpre[..., 2], axis=1) / 90.0))
    F.append(rm / G)  # pre_mag
    F.append(_mean_seg(theta, 225, 250))  # tilt_end
    F.append(np.max(theta[:, 100:250], axis=1))  # tilt_max
    F.append(np.min(theta[:, 100:250], axis=1))  # tilt_min
    F.append(_ssum(np.abs(theta[:, 100:250] - theta[:, 99:249]), axis=1))  # tilt_path
    tail_mean = _ssum(A[:, 225:250, :], axis=1) / 25.0
    F.append(_angle_deg(tail_mean, r))  # rot_end
    F.append(np.max(phi[:, 100:250], axis=1))  # rot_max
    F.append(phi[:, 150])  # rot_1s
    F.append(phi[:, 200])  # rot_2s
    F.append(_ssum(_angle_deg(g[:, 100:250, :], g[:, 99:249, :]), axis=1))  # rot_path
    F.append(_vec_motion(A, 200, 250))  # late_motion
    F.append(_vec_motion(A, 225, 250))  # tail_motion
    F.append(_pop_std(m, 200, 250))  # late_mag_std
    early = _rms_seg(dm, 100, 150)
    mid = _rms_seg(dm, 150, 200)
    late = _rms_seg(dm, 200, 250)
    F += [early, mid, late]
    F.append(np.max(dm[:, 100:250], axis=1))  # dyn_peak
    F.append(np.max(np.abs(m[:, 100:250] - rm[:, None]), axis=1))  # mag_dev_peak
    F.append(np.log((late + 0.01) / (early + 0.01)))  # settle
    dA = A[:, 1:, :] - A[:, :-1, :]
    J = _norm3(dA) / DT  # J_i for i = 0..248
    F.append(np.log(1.0 + np.sqrt(_ssum(J[:, 100:249] * J[:, 100:249], axis=1) / 149.0)))  # jerk_post
    F.append(np.log(1.0 + np.sqrt(_ssum(J[:, 200:249] * J[:, 200:249], axis=1) / 49.0)))  # jerk_late
    F.append(np.log(1.0 + np.max(J[:, 100:249], axis=1)))  # jerk_peak
    F.append(np.max(w[:, 100:250], axis=1))  # up_peak
    F.append(np.min(w[:, 100:250], axis=1))  # down_peak
    V = np.cumsum(w[:, 100:200] * DT, axis=1)
    F.append(np.max(V, axis=1))  # vel_up_max
    F.append(np.min(V, axis=1))  # vel_up_min
    D = np.cumsum(V * DT, axis=1)
    F.append(np.max(D, axis=1))  # disp_up_max
    F.append(_rms_seg(h, 100, 250))  # horiz_rms
    F.append(np.max(h[:, 100:250], axis=1))  # horiz_peak
    VH = np.cumsum(hvec[:, 100:200, :] * DT, axis=1)
    F.append(np.max(_norm3(VH), axis=1))  # vel_h_max
    # Spectrum of |a| over POST (mean removed, Hann), bins k = 1..75 at k/3 Hz.
    mpost = m[:, 100:250]
    mu = _ssum(mpost, axis=1) / 150.0
    x = (mpost - mu[:, None]) * HANN[None, :]
    P = np.empty((B, 75))
    for k in range(75):
        idx = _KN[k]
        re = _ssum(x * COS_TABLE[idx][None, :], axis=1)
        im = _ssum(x * SIN_TABLE[idx][None, :], axis=1)
        P[:, k] = re * re + im * im
    E = _ssum(P, axis=1)
    F.append(_ssum(P[:, 0:5], axis=1) / (E + 1e-9))  # spec_lo  k=1..5   (0.33-1.67 Hz)
    F.append(_ssum(P[:, 5:14], axis=1) / (E + 1e-9))  # spec_mid k=6..14  (2-4.67 Hz)
    F.append(_ssum(P[:, 14:29], axis=1) / (E + 1e-9))  # spec_hi  k=15..29 (5-9.67 Hz)
    F.append(np.log(E / 150.0 + 1e-6))  # spec_log_energy
    freqs = np.arange(1, 76) / 3.0
    F.append(_ssum(P * freqs[None, :], axis=1) / (E + 1e-9))  # spec_centroid
    F.append(_pop_std(phi, 200, 250))  # rot_late_std
    F.append(np.abs(_mean_seg(m, 200, 250) - rm))  # late_mag_dev
    out = np.stack(F, axis=1)
    assert out.shape[1] == len(FEATURE_NAMES)
    return out


def channels_from_grid(A: np.ndarray) -> np.ndarray:
    """(B, 250, 3) -> CNN input (B, 250, 5): |a| deviation, up dynamic, horizontal, tilt, rotation."""
    S = signals(A)
    c0 = (S["m"] - S["rm"][:, None]) / G
    c1 = S["w"] / G
    c2 = S["h"] / G
    c3 = S["theta"] / 180.0
    c4 = S["phi"] / 180.0
    return np.stack([c0, c1, c2, c3, c4], axis=2)


def prepare(windows: Sequence[Window]) -> Tuple[np.ndarray, List[Optional[str]]]:
    """Cleans, checks quality and resamples a list of windows. Returns (B,250,3) and reasons."""
    grids = np.zeros((len(windows), N, 3))
    reasons: List[Optional[str]] = []
    for i, w in enumerate(windows):
        t, a = clean(w.t_ns, w.acc)
        reason = quality(t, w.trigger_ns) if len(t) else "too_few_samples"
        reasons.append(reason)
        if reason is None:
            grids[i] = resample(t, a, w.trigger_ns)
    return grids, reasons
