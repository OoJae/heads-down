"""Motion trigger and canonical window cut, shared by the synthetic generator and the loader.

`MotionTrigger` is an exact port of the sensor lab's trigger
(android/feature/shift/src/debug/.../devlog/MotionWindows.kt) and of the production copy in
android/ml (xyz.headsdown.ml.pickup.MotionTrigger). Keeping the three identical is what makes
training windows (sensor lab), synthetic windows (this file) and on-device windows line up.
`trigger_vectors` in model/pickup_vectors.json pins the Kotlin port to this one.

Canonical classifier window: every sample with trigger - 2 s <= t (the ring buffer), up to and
including the first sample with t >= trigger + 3 s. FEATURE_SPEC.md resamples it to 250 points.
"""
from __future__ import annotations

import math
from dataclasses import dataclass, field
from typing import Dict, List, Optional, Sequence

import numpy as np

# java.lang.Math.toDegrees multiplies by this constant (CPython's math.degrees divides instead).
RAD_TO_DEG = 57.29577951308232
NS_PER_MS = 1_000_000
PRE_NS = 2_000 * NS_PER_MS
POST_NS = 3_000 * NS_PER_MS


@dataclass
class Window:
    """A motion window: sensor timestamps (ns), float32 m/s^2 samples, and the trigger time."""

    t_ns: np.ndarray  # int64, strictly increasing after load/clean
    acc: np.ndarray  # float32, shape (n, 3)
    trigger_ns: int
    label: str  # pickup | bump | slide | set_down
    meta: Dict[str, object] = field(default_factory=dict)


class MotionTrigger:
    """Flags the start of a motion event (magnitude leaves ~1 g, or gravity swings away)."""

    def __init__(
        self,
        magnitude_threshold: float = 1.2,
        tilt_threshold_degrees: float = 12.0,
        fast_tau_millis: float = 80.0,
        slow_tau_millis: float = 2_000.0,
        refractory_millis: int = 3_000,
    ) -> None:
        self.magnitude_threshold = magnitude_threshold
        self.tilt_threshold_degrees = tilt_threshold_degrees
        self.fast_tau_millis = fast_tau_millis
        self.slow_tau_millis = slow_tau_millis
        self.refractory_millis = refractory_millis
        self.fast: Optional[List[float]] = None
        self.slow: Optional[List[float]] = None
        self.rest_magnitude = 0.0
        self.last_nanos = 0
        self.last_trigger_nanos: Optional[int] = None

    def on_sample(self, t_nanos: int, x: float, y: float, z: float) -> bool:
        v = [float(x), float(y), float(z)]
        mag = _norm(v)
        if self.fast is None or self.slow is None:
            self.fast = v[:]
            self.slow = v[:]
            self.rest_magnitude = mag
            self.last_nanos = t_nanos
            return False
        if t_nanos <= self.last_nanos:
            return False  # duplicate or out-of-order timestamp
        dt_millis = (t_nanos - self.last_nanos) / 1e6
        self.last_nanos = t_nanos
        _blend(self.fast, v, dt_millis / (self.fast_tau_millis + dt_millis))
        tilt = _angle_degrees(self.fast, self.slow)
        jolt = abs(mag - self.rest_magnitude)
        moving = jolt > self.magnitude_threshold or tilt > self.tilt_threshold_degrees
        if not moving:
            a = dt_millis / (self.slow_tau_millis + dt_millis)
            _blend(self.slow, self.fast, a)
            self.rest_magnitude += a * (mag - self.rest_magnitude)
        refractory = (
            self.last_trigger_nanos is not None
            and t_nanos - self.last_trigger_nanos < self.refractory_millis * NS_PER_MS
        )
        if moving and not refractory:
            self.last_trigger_nanos = t_nanos
            return True
        return False


def _norm(v: Sequence[float]) -> float:
    return math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])


def _blend(into: List[float], target: Sequence[float], alpha: float) -> None:
    for i in range(3):
        into[i] += alpha * (target[i] - into[i])


def _angle_degrees(a: Sequence[float], b: Sequence[float]) -> float:
    na = _norm(a)
    nb = _norm(b)
    if na < 1e-6 or nb < 1e-6:
        return 0.0
    cos = (a[0] * b[0] + a[1] * b[1] + a[2] * b[2]) / (na * nb)
    cos = min(1.0, max(-1.0, cos))
    return math.acos(cos) * RAD_TO_DEG


def trigger_times(t_ns: np.ndarray, acc: np.ndarray, **kwargs) -> List[int]:
    """Runs a fresh MotionTrigger over a stream and returns every trigger timestamp."""
    trig = MotionTrigger(**kwargs)
    out = []
    xs = acc.astype(np.float32).astype(np.float64)
    for i in range(len(t_ns)):
        if trig.on_sample(int(t_ns[i]), xs[i, 0], xs[i, 1], xs[i, 2]):
            out.append(int(t_ns[i]))
    return out


def cut_window(t_ns: np.ndarray, acc: np.ndarray, trigger_ns: int) -> Optional[tuple]:
    """The canonical window around `trigger_ns`, or None if the stream ends before trigger + 3 s.

    Mirrors xyz.headsdown.ml.pickup.PickupWindowCollector: the ring buffer holds every sample with
    t >= trigger - 2 s when the trigger fires; the window closes on the first sample at or after
    trigger + 3 s (that sample is included).
    """
    start = int(np.searchsorted(t_ns, trigger_ns - PRE_NS, side="left"))
    end_idx = int(np.searchsorted(t_ns, trigger_ns + POST_NS, side="left"))
    if end_idx >= len(t_ns):
        return None
    return t_ns[start : end_idx + 1].copy(), acc[start : end_idx + 1].copy()
