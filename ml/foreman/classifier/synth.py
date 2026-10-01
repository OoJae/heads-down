"""Physics-grounded synthetic accelerometer windows for a phone lying face-down.

Everything is simulated at 1 kHz in the world frame (z up) and then measured the way Android
reports it: specific force in the device frame, f_D = R(t)^T (a_W(t) + [0, 0, g]), so a phone
lying screen-down reads about (0, 0, -9.81) m/s^2 (the convention FaceDownDetector relies on).

Scenario physics (label -> variants):
- bump: a knock on the furniture excites the top's vibration modes (damped modes written in
  displacement, so the table returns to rest with zero net velocity), the phone rocks on its
  edges and settles; gravity stays near -z. Variants: knock (1-3 impacts), object placed nearby,
  nudge (a hand brushes the phone), thud_hop (a heavy impact makes the phone leave the surface:
  free fall reads ~0 g, then a landing spike), bounce (soft surfaces: a mattress dips and
  rebounds at 1.5-6 Hz and may stay a few degrees tilted), buzz (the phone's own vibration motor,
  150-240 Hz, aliased by the sensor's filter and 50 Hz sampling), footsteps (floor-borne),
  topple (no hand: the phone tips 8-35 deg off a pillow slope, or 3-15 deg off a cable or book
  edge, and stays tilted: the hardest negative, a pickup's rotation without the lift or the hand).
- pickup: a grasp jolt, then a minimum-jerk lift (h / T^2 sets the peak acceleration), a sustained
  rotation of gravity away from -z (flip to view), and hand tremor / sway once in the hand.
  Variants: flip, carry (lifted face-down, < 25 deg), peek (flip and put back within ~2 s),
  slow (1-2.5 s lift and rotation), slide_lift (slid to the edge first), walk (carried away).
- slide: low-frequency lateral motion with friction (stick-slip) vibration and yaw, gravity
  unchanged. Variants: push, drag (cable / slow drag), spin (rotated in place).
- set_down: the reverse of a pickup: in-hand motion, rotation back to face-down while lowering,
  a contact clack or a short drop with free fall, then rest. Variants: place, carry_place, drop.

Nuisance variables drawn per window: surface (6 kinds, hard and soft), phone mass 0.15-0.26 kg
(rocking amplitude, hop propensity, contact ringing), rest tilt and yaw, and a MEMS sensor
profile: output data rate and low-pass cutoff, delivery rate and clock skew, timestamp jitter,
dropped samples and gaps, white noise, per-axis bias, scale and cross-axis error, quantization
step and range saturation. Then the real MotionTrigger decides whether and where a window is
cut, exactly as on the phone.
"""
from __future__ import annotations

import math
from dataclasses import dataclass
from typing import Dict, List, Optional, Sequence, Tuple

import numpy as np
from scipy.signal import butter, sosfilt, sosfilt_zi

from .trigger import Window, cut_window, trigger_times

G = 9.80665
FS = 1000.0
DEG = math.pi / 180.0
LABELS = ("pickup", "bump", "slide", "set_down")

VARIANTS: Dict[str, Tuple[Tuple[str, float], ...]] = {
    "pickup": (("flip", 0.36), ("carry", 0.14), ("peek", 0.15), ("slow", 0.12), ("slide_lift", 0.11), ("walk", 0.12)),
    "bump": (("knock", 0.30), ("object", 0.11), ("nudge", 0.14), ("thud_hop", 0.09), ("buzz", 0.12), ("footsteps", 0.12),
             ("topple", 0.12)),
    "slide": (("push", 0.5), ("drag", 0.25), ("spin", 0.25)),
    "set_down": (("place", 0.55), ("carry_place", 0.2), ("drop", 0.25)),
}


@dataclass(frozen=True)
class Surface:
    name: str
    soft: bool
    knock_f: Tuple[float, float]  # vertical mode frequency range, Hz
    knock_zeta: Tuple[float, float]  # damping ratio range
    knock_gain: Tuple[float, float]  # peak acceleration at the phone for a unit knock, m/s^2
    horiz_frac: Tuple[float, float]  # horizontal / vertical amplitude
    rock_deg: Tuple[float, float]  # rocking amplitude for a unit knock, degrees
    rock_f: Tuple[float, float]
    rest_tilt_max: float  # resting tilt from flat, degrees
    friction_rms: Tuple[float, float]  # slide vibration rms, m/s^2
    friction_band: Tuple[float, float]  # Hz
    settle_tilt_max: float  # persistent tilt change after a bounce, degrees
    clack_gain: Tuple[float, float]  # set-down contact clack, m/s^2
    impact_s: Tuple[float, float]  # impact pulse duration, s


SURFACES: Dict[str, Surface] = {
    s.name: s
    for s in (
        Surface("nightstand_wood", False, (18, 60), (0.03, 0.12), (1.5, 9.0), (0.1, 0.5), (0.2, 2.0), (8, 25), 3.0,
                (0.08, 0.5), (6, 30), 0.3, (0.8, 9.0), (0.006, 0.02)),
        Surface("desk_laminate", False, (25, 90), (0.04, 0.15), (1.0, 7.0), (0.1, 0.4), (0.2, 1.5), (10, 30), 2.0,
                (0.06, 0.4), (8, 35), 0.2, (0.8, 8.0), (0.005, 0.018)),
        Surface("table_wood", False, (12, 45), (0.02, 0.10), (1.0, 8.0), (0.1, 0.6), (0.2, 2.0), (6, 22), 2.5,
                (0.08, 0.6), (5, 30), 0.3, (0.8, 9.0), (0.006, 0.02)),
        Surface("table_glass", False, (40, 140), (0.01, 0.06), (1.0, 10.0), (0.05, 0.4), (0.1, 1.2), (12, 35), 1.5,
                (0.03, 0.2), (10, 45), 0.1, (1.0, 12.0), (0.004, 0.012)),
        Surface("bed_mattress", True, (1.5, 5.0), (0.12, 0.40), (0.5, 5.0), (0.1, 0.5), (1.0, 8.0), (1.5, 5.0), 8.0,
                (0.05, 0.3), (2, 12), 4.0, (0.2, 2.0), (0.03, 0.12)),
        Surface("sofa_cushion", True, (2.0, 6.0), (0.15, 0.45), (0.5, 4.0), (0.1, 0.5), (1.0, 6.0), (2.0, 6.0), 10.0,
                (0.05, 0.3), (2, 12), 5.0, (0.2, 2.0), (0.04, 0.15)),
    )
}

TRAIN_SURFACES = ("nightstand_wood", "desk_laminate", "table_wood", "bed_mattress")
SHIFT_SURFACES = ("table_glass", "sofa_cushion")


@dataclass
class SensorProfile:
    odr_hz: float
    lpf_hz: float
    rate_hz: float
    jitter_ms: float
    drop_frac: float
    gap: bool
    noise_rms: float
    bias: np.ndarray
    misalign: np.ndarray
    quant: float
    range_ms2: float

    def summary(self) -> Dict[str, float]:
        return {
            "odr_hz": self.odr_hz,
            "lpf_hz": round(self.lpf_hz, 2),
            "rate_hz": round(self.rate_hz, 2),
            "jitter_ms": round(self.jitter_ms, 3),
            "noise_rms": round(self.noise_rms, 4),
            "quant": self.quant,
            "range_g": round(self.range_ms2 / G, 1),
        }


def draw_sensor(rng: np.random.Generator, shift: bool = False) -> SensorProfile:
    """A MEMS accelerometer + Android HAL profile. `shift` draws a harsher, held-out profile."""
    odr = float(rng.choice([50.0, 100.0, 200.0], p=[0.35, 0.4, 0.25]))
    lpf = rng.uniform(0.2, 0.45) * odr
    if shift:
        rate = float(rng.choice([100.0, 50.0 * rng.uniform(0.9, 1.1), 40.0]))
        noise = float(np.exp(rng.uniform(np.log(0.02), np.log(0.06))))
        quant = float(rng.choice([0.0192, 0.0383, 0.0766]))
        rng_g = float(rng.choice([2.0, 4.0], p=[0.5, 0.5]))
        bias_sd = rng.uniform(0.1, 0.35)
        jitter = rng.uniform(0.5, 3.0)
        drop = rng.uniform(0.01, 0.05)
    else:
        u = rng.uniform()
        rate = 50.0 * rng.uniform(0.95, 1.05) if u < 0.8 else (100.0 if u < 0.9 else 62.5)
        noise = float(np.exp(rng.uniform(np.log(0.004), np.log(0.04))))
        quant = float(rng.choice([0.0012, 0.0024, 0.0048, 0.0096, 0.0192, 0.0383]))
        rng_g = float(rng.choice([2.0, 4.0, 8.0, 16.0], p=[0.1, 0.45, 0.35, 0.1]))
        bias_sd = rng.uniform(0.02, 0.2)
        jitter = rng.uniform(0.0, 1.5)
        drop = rng.uniform(0.0, 0.02)
    return SensorProfile(
        odr_hz=odr,
        lpf_hz=lpf,
        rate_hz=rate,
        jitter_ms=jitter,
        drop_frac=drop,
        gap=bool(rng.uniform() < 0.03),
        noise_rms=noise,
        bias=rng.normal(0.0, bias_sd, 3),
        misalign=rng.normal(0.0, 0.01, (3, 3)),
        quant=quant,
        range_ms2=rng_g * G,
    )


# ----------------------------------------------------------------------------- helpers


def mj(t: np.ndarray, t0: float, T: float) -> np.ndarray:
    """Minimum-jerk position profile from 0 to 1 over [t0, t0 + T]."""
    s = np.clip((t - t0) / T, 0.0, 1.0)
    return s * s * s * (10.0 - 15.0 * s + 6.0 * s * s)


def mj_acc(t: np.ndarray, t0: float, T: float) -> np.ndarray:
    """Second time derivative of `mj` (zero outside the move). Peak |a| = 5.77 / T^2."""
    s = (t - t0) / T
    a = (60.0 * s - 180.0 * s * s + 120.0 * s * s * s) / (T * T)
    return np.where((s >= 0.0) & (s <= 1.0), a, 0.0)


def smoothstep(t: np.ndarray, t0: float, T: float) -> np.ndarray:
    return mj(t, t0, max(T, 1e-3))


def damped_disp_acc(t: np.ndarray, t0: float, f: float, zeta: float, peak: float, phase: float = 0.0) -> np.ndarray:
    """Acceleration of a damped mode written in displacement, x = X e^(-s t) sin(wd t + phase),
    scaled so the peak acceleration is about `peak`. Velocity and displacement decay to zero."""
    w = 2.0 * math.pi * f
    s = zeta * w
    wd = w * math.sqrt(max(1.0 - zeta * zeta, 1e-6))
    X = peak / (w * w)
    tau = t - t0
    on = tau >= 0.0
    tau = np.where(on, tau, 0.0)
    e = np.exp(-s * tau)
    ph = wd * tau + phase
    acc = X * e * ((s * s - wd * wd) * np.sin(ph) - 2.0 * s * wd * np.cos(ph))
    return np.where(on, acc, 0.0)


def damped_angle(t: np.ndarray, t0: float, f: float, zeta: float, amp_rad: float) -> np.ndarray:
    w = 2.0 * math.pi * f
    tau = t - t0
    on = tau >= 0.0
    tau = np.where(on, tau, 0.0)
    return np.where(on, amp_rad * np.exp(-zeta * w * tau) * np.sin(w * math.sqrt(max(1 - zeta * zeta, 1e-6)) * tau), 0.0)


def half_sine(t: np.ndarray, t0: float, dur: float, dv: float) -> np.ndarray:
    """A half-sine pulse whose integral (velocity change) is `dv`."""
    amp = math.pi * dv / (2.0 * dur)
    tau = t - t0
    return np.where((tau >= 0.0) & (tau <= dur), amp * np.sin(math.pi * np.clip(tau, 0.0, dur) / dur), 0.0)


def full_sine(t: np.ndarray, t0: float, dur: float, amp: float) -> np.ndarray:
    """One full sine period: a push and its stop, zero net velocity."""
    tau = t - t0
    return np.where((tau >= 0.0) & (tau <= dur), amp * np.sin(2.0 * math.pi * np.clip(tau, 0.0, dur) / dur), 0.0)


def band_noise(rng: np.random.Generator, n: int, lo: float, hi: float, rms: float, shape: Tuple[int, ...] = ()) -> np.ndarray:
    """Zero-mean band-limited Gaussian noise at FS with the requested rms (per column)."""
    pad = 2000
    cols = int(np.prod(shape)) if shape else 1
    x = rng.standard_normal((n + pad, cols))
    hi = min(hi, 0.45 * FS)
    if lo <= 0.0:
        sos = butter(2, hi, btype="low", fs=FS, output="sos")
    else:
        sos = butter(2, [lo, hi], btype="band", fs=FS, output="sos")
    y = sosfilt(sos, x, axis=0)[pad:]
    y = y / (np.std(y, axis=0, keepdims=True) + 1e-12) * rms
    return y.reshape((n,) + shape) if shape else y[:, 0]


def horiz(rng: np.random.Generator) -> np.ndarray:
    a = rng.uniform(0.0, 2.0 * math.pi)
    return np.array([math.cos(a), math.sin(a), 0.0])


def axis_angle(axis: np.ndarray, angles: np.ndarray) -> np.ndarray:
    """(n,3,3) rotations about a fixed unit axis."""
    k = axis / np.linalg.norm(axis)
    K = np.array([[0.0, -k[2], k[1]], [k[2], 0.0, -k[0]], [-k[1], k[0], 0.0]])
    s = np.sin(angles)[:, None, None]
    c = np.cos(angles)[:, None, None]
    return np.eye(3)[None] + s * K[None] + (1.0 - c) * (K @ K)[None]


def rodrigues(rv: np.ndarray) -> np.ndarray:
    """(n,3) rotation vectors -> (n,3,3)."""
    th = np.linalg.norm(rv, axis=1)
    k = rv / np.maximum(th, 1e-12)[:, None]
    K = np.zeros((len(rv), 3, 3))
    K[:, 0, 1] = -k[:, 2]
    K[:, 0, 2] = k[:, 1]
    K[:, 1, 0] = k[:, 2]
    K[:, 1, 2] = -k[:, 0]
    K[:, 2, 0] = -k[:, 1]
    K[:, 2, 1] = k[:, 0]
    s = np.sin(th)[:, None, None]
    c = np.cos(th)[:, None, None]
    return np.eye(3)[None] + s * K + (1.0 - c) * (K @ K)


def rest_pose(rng: np.random.Generator, surface: Surface) -> np.ndarray:
    """Screen-down rest orientation (device -> world): flip about device y, random yaw, small tilt."""
    flip = np.diag([-1.0, 1.0, -1.0])  # Rot_y(180 deg): device z -> world -z
    yaw = axis_angle(np.array([0.0, 0.0, 1.0]), np.array([rng.uniform(0, 2 * math.pi)]))[0]
    tilt = axis_angle(horiz(rng), np.array([rng.uniform(0.0, surface.rest_tilt_max) * DEG]))[0]
    return tilt @ yaw @ flip


@dataclass
class Motion:
    a_w: np.ndarray  # (n,3) world linear acceleration
    rot: np.ndarray  # (n,3,3) world-frame rotation applied on top of the rest pose
    a_dev: np.ndarray  # (n,3) device-frame additive specific force (vibration motor)
    info: Dict[str, object]


def _hand_motion(rng: np.random.Generator, n: int, env: np.ndarray, walk: Optional[Tuple[np.ndarray, float]] = None):
    """In-hand linear sway + physiological tremor (world) and rotational jitter (rotation vector)."""
    sway = band_noise(rng, n, 0.3, 3.0, rng.uniform(0.05, 0.4), (3,))
    tremor = band_noise(rng, n, 6.0, 12.0, rng.uniform(0.01, 0.08), (3,))
    rotj = band_noise(rng, n, 0.0, 2.0, rng.uniform(0.5, 4.0) * DEG, (3,))
    a = (sway + tremor) * env[:, None]
    rv = rotj * env[:, None]
    return a, rv


def _compose(n: int, *rots: np.ndarray) -> np.ndarray:
    out = np.broadcast_to(np.eye(3), (n, 3, 3)).copy()
    for r in rots:
        out = out @ r
    return out


# ----------------------------------------------------------------------------- scenarios


def _knock(rng, t, t0, surf: Surface, strength: float, mass: float, a: np.ndarray, rock: np.ndarray) -> None:
    peak = strength * rng.uniform(*surf.knock_gain)
    for _ in range(int(rng.integers(1, 3))):
        a[:, 2] += damped_disp_acc(t, t0, rng.uniform(*surf.knock_f), rng.uniform(*surf.knock_zeta),
                                   peak * rng.uniform(0.5, 1.0), rng.uniform(0, 2 * math.pi))
    hdir = horiz(rng)
    a += hdir[None] * damped_disp_acc(t, t0, rng.uniform(*surf.knock_f) * rng.uniform(0.5, 1.5),
                                      rng.uniform(*surf.knock_zeta), peak * rng.uniform(*surf.horiz_frac))[:, None]
    amp = min(strength * rng.uniform(*surf.rock_deg) * math.sqrt(0.2 / mass), 12.0 if surf.soft else 6.0) * DEG
    rock += horiz(rng)[None] * damped_angle(t, t0, rng.uniform(*surf.rock_f), rng.uniform(0.1, 0.4), amp)[:, None]


def scenario_bump(rng, t, t_ev, surf: Surface, mass: float, variant: str) -> Motion:
    n = len(t)
    a = np.zeros((n, 3))
    rock = np.zeros((n, 3))  # small-angle rotation vector (world)
    a_dev = np.zeros((n, 3))
    info: Dict[str, object] = {}
    if variant == "knock" and surf.soft:
        variant = "bounce"
    info["variant"] = variant
    if variant == "knock":
        tk = t_ev
        for _ in range(int(rng.integers(1, 4))):
            _knock(rng, t, tk, surf, float(np.exp(rng.uniform(np.log(0.3), np.log(3.0)))), mass, a, rock)
            tk += rng.uniform(0.1, 0.8)
    elif variant == "object":
        _knock(rng, t, t_ev, surf, rng.uniform(0.15, 1.0), mass, a, rock)
    elif variant == "nudge":
        d = rng.uniform(0.004, 0.03)
        T = rng.uniform(0.06, 0.3)
        a += horiz(rng)[None] * (d * mj_acc(t, t_ev, T))[:, None]
        yaw = rng.uniform(-30, 30) * DEG * mj(t, t_ev, T)
        rock[:, 2] += yaw
        lift_deg = rng.uniform(0.0, 10.0)
        up = rng.uniform(0.05, 0.2)
        tilt = lift_deg * DEG * (mj(t, t_ev, up) - mj(t, t_ev + up, rng.uniform(0.05, 0.25)))
        rock += horiz(rng)[None] * tilt[:, None]
        drop_t = t_ev + 2 * up
        _knock(rng, t, drop_t, surf, rng.uniform(0.1, 0.8) * (lift_deg / 10.0 + 0.2), mass, a, rock)
        info["lift_deg"] = round(lift_deg, 2)
    elif variant == "thud_hop":
        _knock(rng, t, t_ev, surf, rng.uniform(1.5, 4.0), mass, a, rock)
        hop_p = min(1.0, 0.2 * (0.2 / mass) ** 2 * (0.3 if surf.soft else 1.0) * 3.0)
        if rng.uniform() < hop_p:
            v_up = rng.uniform(0.05, 0.35)
            push = rng.uniform(0.008, 0.025)
            t1 = t_ev + 0.002
            a[:, 2] += half_sine(t, t1, push, v_up)
            flight = 2.0 * v_up / G
            t_air = t1 + push
            in_air = (t >= t_air) & (t < t_air + flight)
            a[in_air, :] = 0.0
            a[in_air, 2] = -G
            land = rng.uniform(*surf.impact_s)
            a[:, 2] += half_sine(t, t_air + flight, land, v_up)
            _knock(rng, t, t_air + flight, surf, rng.uniform(0.3, 1.5), mass, a, rock)
            tumble = rng.uniform(0, 8) * DEG
            rock += horiz(rng)[None] * (tumble * (mj(t, t_air, flight / 2) - mj(t, t_air + flight / 2, flight / 2)))[:, None]
            info["hop"] = True
    elif variant == "bounce":
        tk = t_ev
        residual = 0.0
        for _ in range(int(rng.integers(1, 4))):
            amax = rng.uniform(1.0, 8.0)
            d = rng.uniform(0.005, 0.06)
            T = max(math.sqrt(5.77 * d / amax), 0.12)
            a[:, 2] += -d * mj_acc(t, tk, T) + d * mj_acc(t, tk + T, T * rng.uniform(1.0, 2.0))
            a[:, 2] += damped_disp_acc(t, tk + T, rng.uniform(*surf.knock_f), rng.uniform(*surf.knock_zeta), amax * rng.uniform(0.2, 0.7))
            ax = horiz(rng)
            th = rng.uniform(1.0, 8.0) * DEG
            res = rng.uniform(0.0, surf.settle_tilt_max) * DEG if rng.uniform() < 0.5 else 0.0
            residual += res
            prof = th * (mj(t, tk, T) - mj(t, tk + T, T)) + res * mj(t, tk, 2 * T)
            rock += ax[None] * prof[:, None]
            rock += horiz(rng)[None] * damped_angle(t, tk + T, rng.uniform(*surf.rock_f), rng.uniform(0.15, 0.4),
                                                    rng.uniform(0.5, 3.0) * DEG)[:, None]
            tk += T * 2 + rng.uniform(0.2, 1.0)
        info["residual_deg"] = round(residual / DEG, 2)
    elif variant == "buzz":
        tk = t_ev
        f_m = rng.uniform(150.0, 240.0)
        axis = np.array([1.0, 0.0, 0.0]) if rng.uniform() < 0.5 else np.array([0.0, 1.0, 0.0])
        amp = rng.uniform(1.0, 6.0)
        for _ in range(int(rng.integers(1, 4))):
            dur = rng.uniform(0.15, 0.6)
            env = smoothstep(t, tk, 0.02) * (1.0 - smoothstep(t, tk + dur, 0.03))
            a_dev += axis[None] * (amp * np.sin(2 * math.pi * f_m * t) * env)[:, None]
            if not surf.soft:
                a_dev += band_noise(rng, len(t), 20.0, 80.0, rng.uniform(0.1, 1.0), (3,)) * env[:, None]
            rock[:, 2] += rng.uniform(-5, 5) * DEG * mj(t, tk, dur)
            tk += dur + rng.uniform(0.1, 0.5)
    elif variant == "footsteps":
        tk = t_ev
        for _ in range(int(rng.integers(2, 7))):
            a[:, 2] += damped_disp_acc(t, tk, rng.uniform(8.0, 30.0), rng.uniform(0.05, 0.3), rng.uniform(0.1, 1.8))
            tk += rng.uniform(0.45, 0.7)
    elif variant == "topple":
        # No hand: the phone tips off a pillow slope / duvet fold (soft) or a cable or book edge
        # (hard) and stays tilted. Gravity changes for good, which is exactly what a pickup's
        # first half looks like; only the absence of a lift and of hand motion tells them apart.
        tilt = rng.uniform(8.0, 35.0) if surf.soft else rng.uniform(3.0, 15.0)
        T = rng.uniform(0.15, 0.8)
        ax = horiz(rng)
        rock += ax[None] * (tilt * DEG * mj(t, t_ev, T))[:, None]
        drop = rng.uniform(0.002, 0.03)  # the high edge comes down a little
        a[:, 2] += -drop * mj_acc(t, t_ev, T)
        a += horiz(rng)[None] * (rng.uniform(0.0, 0.05) * mj_acc(t, t_ev, T))[:, None]
        _knock(rng, t, t_ev + T, surf, rng.uniform(0.1, 0.8), mass, a, rock)
        info["topple_deg"] = round(tilt, 1)
    else:
        raise ValueError(variant)
    rot = rodrigues(rock)
    return Motion(a, rot, a_dev, info)


def scenario_pickup(rng, t, t_ev, surf: Surface, mass: float, variant: str) -> Motion:
    n = len(t)
    a = np.zeros((n, 3))
    rock = np.zeros((n, 3))
    info: Dict[str, object] = {"variant": variant}
    tg = t_ev
    # Grasp: fingers meet the phone (small surface jolt plus a sideways push-and-stop).
    _knock(rng, t, tg, surf, rng.uniform(0.02, 0.25), mass, a, rock)
    a += horiz(rng)[None] * full_sine(t, tg, rng.uniform(0.03, 0.1), rng.uniform(0.1, 1.5) * (0.5 if surf.soft else 1.0))[:, None]
    if variant == "slide_lift":
        d = rng.uniform(0.04, 0.2)
        T = rng.uniform(0.4, 1.0)
        a += horiz(rng)[None] * (d * mj_acc(t, tg + 0.05, T))[:, None]
        env = smoothstep(t, tg + 0.05, 0.05) * (1.0 - smoothstep(t, tg + 0.05 + T, 0.05))
        a += band_noise(rng, n, *surf.friction_band, rng.uniform(*surf.friction_rms), (3,)) * env[:, None]
        tl = tg + 0.05 + T + rng.uniform(0.0, 0.3)
    else:
        tl = tg + rng.uniform(0.05, 0.4)
    if variant == "slow":
        T_l, h = rng.uniform(1.2, 2.5), rng.uniform(0.08, 0.35)
    else:
        T_l, h = rng.uniform(0.35, 1.4), rng.uniform(0.08, 0.5)
    a[:, 2] += h * mj_acc(t, tl, T_l)
    d_h, T_h = rng.uniform(0.0, 0.35), rng.uniform(0.5, 1.8)
    a += horiz(rng)[None] * (d_h * mj_acc(t, tl + rng.uniform(0.0, 0.3), T_h))[:, None]
    flip_axis = horiz(rng)
    if variant == "carry":
        phi, T_r, tr = rng.uniform(0.0, 25.0), rng.uniform(0.3, 1.5), tl + rng.uniform(0.0, 0.5)
    elif variant == "slow":
        phi, T_r, tr = rng.uniform(60.0, 170.0), rng.uniform(1.0, 2.5), tl + rng.uniform(0.0, 0.8)
    elif variant == "walk":
        phi, T_r, tr = rng.uniform(30.0, 175.0), rng.uniform(0.3, 1.2), tl + rng.uniform(-0.1, 0.5)
    else:  # flip, peek, slide_lift
        phi = rng.uniform(60.0, 170.0) if variant == "peek" else rng.uniform(100.0, 175.0)
        T_r, tr = (rng.uniform(0.3, 0.9) if variant == "peek" else rng.uniform(0.3, 1.2)), tl + rng.uniform(-0.1, 0.5)
    tr = max(tr, tl - 0.1)
    angle = phi * DEG * mj(t, tr, T_r)
    hold_end = None
    if variant == "peek":
        hold = rng.uniform(0.3, 1.2)
        tb = max(tr + T_r, tl + T_l) + hold
        T_b = rng.uniform(0.3, 0.9)
        angle = angle - phi * DEG * mj(t, tb, T_b)
        T_down = rng.uniform(0.35, 1.0)
        a[:, 2] += -h * mj_acc(t, tb, T_down)
        a += horiz(rng)[None] * (d_h * 0.5 * mj_acc(t, tb, T_down))[:, None]
        hold_end = max(tb + T_b, tb + T_down) + rng.uniform(0.0, 0.1)
        a[:, 2] += damped_disp_acc(t, hold_end, rng.uniform(*surf.knock_f), rng.uniform(*surf.knock_zeta),
                                   rng.uniform(*surf.clack_gain) * 0.5)
        info["hold_s"] = round(hold, 3)
    yaw_amt = rng.uniform(-90.0, 90.0) * DEG * mj(t, tl, T_l)
    env = smoothstep(t, tl + 0.05, 0.3)
    if hold_end is not None:
        env = env * (1.0 - smoothstep(t, hold_end - 0.1, 0.1))
    a_hand, rv_hand = _hand_motion(rng, n, env)
    a += a_hand
    if variant == "walk":
        tw = tl + T_l + rng.uniform(0.2, 1.0)
        f_step = rng.uniform(1.6, 2.2)
        A = rng.uniform(0.8, 3.0)
        wenv = smoothstep(t, tw, 0.4)
        a[:, 2] += wenv * (A * np.sin(2 * math.pi * f_step * (t - tw)) + 0.3 * A * np.sin(4 * math.pi * f_step * (t - tw)))
        a += horiz(rng)[None] * (wenv * 0.4 * A * np.sin(math.pi * f_step * (t - tw)))[:, None]
    info.update({"lift_m": round(h, 3), "lift_s": round(T_l, 3), "flip_deg": round(phi, 1), "flip_s": round(T_r, 3)})
    rot = _compose(n, rodrigues(rv_hand + rock), axis_angle(np.array([0.0, 0.0, 1.0]), yaw_amt), axis_angle(flip_axis, angle))
    return Motion(a, rot, np.zeros((n, 3)), info)


def scenario_slide(rng, t, t_ev, surf: Surface, mass: float, variant: str) -> Motion:
    n = len(t)
    a = np.zeros((n, 3))
    rock = np.zeros((n, 3))
    info: Dict[str, object] = {"variant": variant}
    hdir = horiz(rng)
    if variant == "push":
        D = rng.uniform(0.03, 0.4)
        T = max(rng.uniform(0.3, 1.5), math.sqrt(5.77 * D / 6.0))
        fr = rng.uniform(*surf.friction_rms)
        band = surf.friction_band
    elif variant == "drag":
        D = rng.uniform(0.05, 0.3)
        T = rng.uniform(0.8, 2.2)
        fr = rng.uniform(0.2, 1.0) * (0.5 if surf.soft else 1.0)
        band = (2.0, 8.0)
    else:  # spin
        D = rng.uniform(0.0, 0.03)
        T = rng.uniform(0.4, 1.2)
        fr = rng.uniform(*surf.friction_rms)
        band = surf.friction_band
    a += hdir[None] * (D * mj_acc(t, t_ev, T))[:, None]
    env = smoothstep(t, t_ev, 0.05) * (1.0 - smoothstep(t, t_ev + T, 0.08))
    a += band_noise(rng, n, band[0], band[1], fr, (3,)) * env[:, None] * np.array([1.0, 1.0, 0.6])[None]
    # Static-friction break-away and the stop.
    a[:, 2] += damped_disp_acc(t, t_ev, rng.uniform(*surf.knock_f), rng.uniform(*surf.knock_zeta), rng.uniform(0.1, 0.8))
    a += hdir[None] * damped_disp_acc(t, t_ev + T, rng.uniform(5.0, 20.0), rng.uniform(0.2, 0.5), rng.uniform(0.1, 0.8))[:, None]
    yaw_total = rng.uniform(45.0, 180.0) * rng.choice([-1.0, 1.0]) if variant == "spin" else rng.uniform(-45.0, 45.0)
    yaw = yaw_total * DEG * mj(t, t_ev, T)
    wobble = rng.uniform(0.0, 2.0) * DEG * np.sin(math.pi * np.clip((t - t_ev) / T, 0.0, 1.0))
    rock += horiz(rng)[None] * wobble[:, None]
    a_dev = np.zeros((n, 3))
    if variant == "spin":
        # The sensor sits a few cm from the spin centre: tangential + centripetal acceleration.
        r_s = rng.uniform(0.01, 0.05)
        omega = np.gradient(yaw, 1.0 / FS)
        alpha = np.gradient(omega, 1.0 / FS)
        a_dev[:, 0] += -omega * omega * r_s
        a_dev[:, 1] += alpha * r_s
    info.update({"dist_m": round(D, 3), "move_s": round(T, 3), "yaw_deg": round(yaw_total, 1)})
    rot = _compose(n, rodrigues(rock), axis_angle(np.array([0.0, 0.0, 1.0]), yaw))
    return Motion(a, rot, a_dev, info)


def scenario_set_down(rng, t, t_ev, surf: Surface, mass: float, variant: str) -> Motion:
    n = len(t)
    a = np.zeros((n, 3))
    rock = np.zeros((n, 3))
    info: Dict[str, object] = {"variant": variant}
    flip_axis = horiz(rng)
    phi0 = rng.uniform(100.0, 175.0) if variant == "place" else rng.uniform(0.0, 25.0)
    T_l = rng.uniform(0.4, 1.4)
    h = rng.uniform(0.05, 0.4) if variant != "drop" else rng.uniform(0.05, 0.3)
    T_r = rng.uniform(0.3, 1.2)
    tr = t_ev + rng.uniform(-0.2, 0.4)
    a[:, 2] += -h * mj_acc(t, t_ev, T_l)
    a += horiz(rng)[None] * (rng.uniform(0.0, 0.3) * mj_acc(t, t_ev, T_l))[:, None]
    angle = phi0 * DEG * (1.0 - mj(t, tr, T_r))
    t_contact = max(t_ev + T_l, tr + T_r) + rng.uniform(0.0, 0.1)
    if variant == "drop":
        h_d = rng.uniform(0.005, 0.05)
        t_rel = t_contact + rng.uniform(0.1, 0.5)
        fall = math.sqrt(2 * h_d / G)
        env = 1.0 - smoothstep(t, t_rel - 0.03, 0.03)
        in_air = (t >= t_rel) & (t < t_rel + fall)
        t_contact = t_rel + fall
        a[:, 2] += half_sine(t, t_contact, rng.uniform(*surf.impact_s), math.sqrt(2 * G * h_d))
        a[in_air, :] = 0.0
        a[in_air, 2] = -G
        info["drop_m"] = round(h_d, 4)
    else:
        env = 1.0 - smoothstep(t, t_contact - 0.05, 0.05)
    a[:, 2] += damped_disp_acc(t, t_contact, rng.uniform(*surf.knock_f), rng.uniform(*surf.knock_zeta), rng.uniform(*surf.clack_gain))
    amp = rng.uniform(*surf.rock_deg) * 0.5 * math.sqrt(0.2 / mass) * DEG
    rock += horiz(rng)[None] * damped_angle(t, t_contact, rng.uniform(*surf.rock_f), rng.uniform(0.1, 0.4), amp)[:, None]
    a_hand, rv_hand = _hand_motion(rng, n, env)
    a += a_hand
    info.update({"from_deg": round(phi0, 1), "lower_m": round(h, 3)})
    rot = _compose(n, rodrigues(rv_hand + rock), axis_angle(flip_axis, angle))
    return Motion(a, rot, np.zeros((n, 3)), info)


SCENARIOS = {"pickup": scenario_pickup, "bump": scenario_bump, "slide": scenario_slide, "set_down": scenario_set_down}


# ----------------------------------------------------------------------------- sensor


def measure(motion: Motion, R_rest: np.ndarray) -> np.ndarray:
    """Specific force in the device frame: R^T (a_W + g_up) + device-frame terms."""
    R = motion.rot @ R_rest[None]
    f_world = motion.a_w + np.array([0.0, 0.0, G])[None]
    return np.einsum("nji,nj->ni", R, f_world) + motion.a_dev


def apply_sensor(rng: np.random.Generator, t: np.ndarray, f_true: np.ndarray, prof: SensorProfile, t0_ns: int):
    """MEMS + HAL: misalignment/scale, bias, low-pass at the ODR, delivery at `rate_hz` with jitter,
    drops and gaps, white noise, quantization, saturation, float32."""
    f1 = f_true @ (np.eye(3) + prof.misalign).T + prof.bias[None]
    sos = butter(2, min(prof.lpf_hz, 0.45 * FS), btype="low", fs=FS, output="sos")
    zi = sosfilt_zi(sos)[:, :, None] * f1[0][None, None, :]
    f2, _ = sosfilt(sos, f1, axis=0, zi=zi)
    period = 1.0 / prof.rate_hz
    start = rng.uniform(0.0, period)
    s = np.arange(start, t[-1] - 1e-3, period)
    s = s + rng.normal(0.0, 0.0001, len(s))
    keep = rng.uniform(size=len(s)) >= prof.drop_frac
    if prof.gap:
        g0 = rng.uniform(1.0, t[-1] - 1.0)
        keep &= ~((s >= g0) & (s < g0 + rng.uniform(0.1, 0.4)))
    s = np.sort(s[keep])
    vals = np.stack([np.interp(s, t, f2[:, k]) for k in range(3)], axis=1)
    vals = vals + rng.normal(0.0, prof.noise_rms, vals.shape)
    vals = np.round(vals / prof.quant) * prof.quant
    vals = np.clip(vals, -prof.range_ms2, prof.range_ms2)
    t_ns = t0_ns + np.round((s + rng.normal(0.0, prof.jitter_ms * 1e-3, len(s))) * 1e9).astype(np.int64)
    order = np.argsort(t_ns, kind="stable")
    t_ns, vals = t_ns[order], vals[order]
    uniq = np.concatenate([[True], np.diff(t_ns) > 0])
    return t_ns[uniq], vals[uniq].astype(np.float32)


# ----------------------------------------------------------------------------- dataset


@dataclass
class Condition:
    surface: str
    mass_kg: float
    sensor: SensorProfile
    split: str


def simulate_stream(rng: np.random.Generator, label: str, variant: str, cond: Condition):
    """One simulated stream (rest or in-hand lead-in, the event, a 4 s tail)."""
    surf = SURFACES[cond.surface]
    t_ev = rng.uniform(3.0, 5.0)
    t = np.arange(int((t_ev + 4.5 + 4.0) * FS)) / FS
    motion = SCENARIOS[label](rng, t, t_ev, surf, cond.mass_kg, variant)
    R_rest = rest_pose(rng, surf)
    f_true = measure(motion, R_rest)
    t0_ns = int(rng.integers(10**11, 10**13))
    t_ns, acc = apply_sensor(rng, t, f_true, cond.sensor, t0_ns)
    return t_ns, acc, t0_ns + int(round(t_ev * 1e9)), motion.info


def draw_condition(rng: np.random.Generator, split: str) -> Condition:
    shift = split == "test_shift"
    surfaces = SHIFT_SURFACES if shift else TRAIN_SURFACES
    surface = str(rng.choice(surfaces))
    if shift:
        mass = rng.uniform(0.15, 0.17) if rng.uniform() < 0.5 else rng.uniform(0.24, 0.26)
    else:
        mass = rng.uniform(0.17, 0.24)
    return Condition(surface, float(mass), draw_sensor(rng, shift=shift), split)


def draw_variant(rng: np.random.Generator, label: str) -> str:
    names, probs = zip(*VARIANTS[label])
    return str(rng.choice(names, p=np.array(probs) / sum(probs)))


def generate_window(rng: np.random.Generator, label: str, split: str, max_tries: int = 1):
    """Simulates until the MotionTrigger fires (or `max_tries` runs out). Returns (Window | None, meta)."""
    variant = draw_variant(rng, label)
    cond = draw_condition(rng, split)
    t_ns, acc, event_ns, info = simulate_stream(rng, label, variant, cond)
    meta = {
        "split": split,
        "surface": cond.surface,
        "mass_kg": round(cond.mass_kg, 3),
        "variant": str(info.get("variant", variant)),
        **{k: v for k, v in info.items() if k != "variant"},
        **cond.sensor.summary(),
    }
    # The collector needs 2 s of history before a trigger (the phone was already lying there).
    # Set-downs start in the hand, so any trigger after that counts; the others must not fire on
    # the lead-in rest (they do not: the lead-in is still), only around the event.
    start_ok = int(t_ns[0]) + 2_200_000_000
    earliest = start_ok if label == "set_down" else max(start_ok, event_ns - 500_000_000)
    trig = [tt for tt in trigger_times(t_ns, acc) if tt >= earliest]
    if not trig:
        meta["triggered"] = False
        return None, meta
    cut = cut_window(t_ns, acc, trig[0])
    if cut is None:
        meta["triggered"] = False
        return None, meta
    wt, wa = cut
    meta["triggered"] = True
    meta["trigger_delay_ms"] = round((trig[0] - event_ns) / 1e6, 1)
    return Window(wt, wa, trig[0], label, meta), meta


DEFAULT_MIX = {"pickup": 0.5, "bump": 0.25, "slide": 0.125, "set_down": 0.125}


def generate_split(n: int, split: str, seed: int, mix: Dict[str, float] = DEFAULT_MIX):
    """`n` triggered windows with the label mix; also returns per-label/variant trigger stats."""
    rng = np.random.default_rng(seed)
    targets = {k: int(round(n * v)) for k, v in mix.items()}
    windows: List[Window] = []
    stats: Dict[str, Dict[str, List[int]]] = {}
    for label, want in targets.items():
        got = 0
        while got < want:
            w, meta = generate_window(rng, label, split)
            st = stats.setdefault(label, {}).setdefault(str(meta["variant"]), [0, 0])
            st[1] += 1
            if w is not None:
                st[0] += 1
                windows.append(w)
                got += 1
    order = rng.permutation(len(windows))
    return [windows[i] for i in order], stats
