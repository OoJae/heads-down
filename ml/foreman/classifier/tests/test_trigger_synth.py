"""The motion trigger port and the physics of the synthetic generator."""
import math

import numpy as np
import pytest

from classifier import features as fx
from classifier import synth
from classifier.trigger import MotionTrigger, cut_window, trigger_times


def _stream(n=500, z=-9.80665, noise=0.0, seed=0):
    rng = np.random.default_rng(seed)
    t = (np.arange(n) * 20_000_000).astype(np.int64) + 1_000_000_000
    acc = np.tile([0.0, 0.0, z], (n, 1)) + rng.normal(0, noise, (n, 3))
    return t, acc.astype(np.float32)


def test_rest_never_triggers_and_a_knock_does_once_per_refractory_period():
    t, a = _stream(noise=0.02)
    assert trigger_times(t, a) == []
    a2 = a.copy()
    a2[100, 2] += 5.0
    a2[120, 2] += 5.0  # 0.4 s later: inside the 3 s refractory period
    a2[300, 2] += 5.0  # 4 s later: a new event
    assert trigger_times(t, a2) == [int(t[100]), int(t[300])]


def test_duplicate_and_out_of_order_timestamps_are_ignored():
    trig = MotionTrigger()
    assert trig.on_sample(1_000, 0, 0, -9.8) is False
    assert trig.on_sample(1_000, 0, 0, 30.0) is False
    assert trig.on_sample(999, 0, 0, 30.0) is False
    assert trig.on_sample(21_000_000, 0, 0, 30.0) is True


def test_a_slow_tilt_triggers_on_rotation():
    t, a = _stream(n=400)
    ang = np.clip((np.arange(400) - 100) / 100.0, 0, 1) * math.radians(40)
    a[:, 0] = 9.80665 * np.sin(ang)
    a[:, 2] = -9.80665 * np.cos(ang)
    fired = trigger_times(t, a)
    assert len(fired) >= 1
    first = (fired[0] - t[0]) / 20_000_000
    # 12 degrees is a third of the way into the ramp (sample 130); the slow resting reference
    # follows the phone until it fires, which adds a little lag.
    assert 125 < first < 160


def test_cut_window_is_ring_then_first_sample_after_plus_3s():
    t, a = _stream(n=500)
    trig = int(t[200])
    wt, wa = cut_window(t, a, trig)
    assert wt[0] == t[100] and wt[-1] == t[350]
    assert cut_window(t, a, int(t[-10])) is None


@pytest.mark.parametrize("surface", list(synth.SURFACES))
def test_rest_reads_one_g_screen_down(surface):
    rng = np.random.default_rng(3)
    R = synth.rest_pose(rng, synth.SURFACES[surface])
    f = R.T @ np.array([0.0, 0.0, synth.G])
    assert np.linalg.norm(f) == pytest.approx(synth.G)
    tilt = math.degrees(math.atan2(math.hypot(f[0], f[1]), -f[2]))
    assert tilt <= synth.SURFACES[surface].rest_tilt_max + 1e-9


def _event(label, variant, seed, surface="nightstand_wood"):
    rng = np.random.default_rng(seed)
    t = np.arange(int(12 * synth.FS)) / synth.FS
    motion = synth.SCENARIOS[label](rng, t, 4.0, synth.SURFACES[surface], 0.2, variant)
    R_rest = synth.rest_pose(rng, synth.SURFACES[surface])
    return t, synth.measure(motion, R_rest), motion


def _tilt(f):
    return np.degrees(np.arctan2(np.hypot(f[..., 0], f[..., 1]), -f[..., 2]))


@pytest.mark.parametrize("seed", range(5))
def test_physics_bumps_settle_back_and_flips_end_screen_up(seed):
    t, f, _ = _event("bump", "knock", seed)
    tail = f[t > 10.0].mean(axis=0)
    assert abs(np.linalg.norm(tail) - synth.G) < 0.05, "a bump ends at rest: 1 g"
    assert _tilt(tail) < 8.0, "and still screen-down"
    t, f, motion = _event("pickup", "flip", seed)
    assert _tilt(f[t > 10.0].mean(axis=0)) > 60.0, "a flip to view ends well away from screen-down"
    t, f, _ = _event("slide", "push", seed)
    assert _tilt(f[t > 10.0].mean(axis=0)) < 8.0, "a slide never rotates gravity much"


def test_physics_a_hop_reads_free_fall():
    for seed in range(40):
        t, f, motion = _event("bump", "thud_hop", seed, surface="table_wood")
        if motion.info.get("hop"):
            assert np.min(np.linalg.norm(f, axis=1)) < 0.5, "free fall reads ~0 g"
            return
    pytest.skip("no hop drawn in 40 seeds")


def test_lift_profile_integrates_to_zero_velocity():
    t = np.arange(0, 3, 1e-3)
    a = 0.3 * synth.mj_acc(t, 0.5, 0.8)
    v = np.cumsum(a) * 1e-3
    assert abs(v[-1]) < 1e-3 and np.sum(v) * 1e-3 == pytest.approx(0.3, abs=2e-3)


def test_sensor_model_quantizes_saturates_and_is_deterministic():
    rng = np.random.default_rng(5)
    prof = synth.draw_sensor(rng)
    t = np.arange(int(4 * synth.FS)) / synth.FS
    f = np.tile([0.0, 0.0, -synth.G], (len(t), 1))
    f[2000, 2] = 500.0
    t1, a1 = synth.apply_sensor(np.random.default_rng(9), t, f, prof, 10**12)
    t2, a2 = synth.apply_sensor(np.random.default_rng(9), t, f, prof, 10**12)
    assert np.array_equal(t1, t2) and np.array_equal(a1, a2)
    assert np.all(np.diff(t1) > 0)
    assert np.max(np.abs(a1)) <= prof.range_ms2 + 1e-4
    unclipped = np.abs(a1) < prof.range_ms2 - 1e-3
    q = a1.astype(np.float64)[unclipped] / prof.quant
    assert np.max(np.abs(q - np.round(q))) < 1e-3


def test_generated_windows_are_valid_and_reproducible():
    w1, s1 = synth.generate_split(40, "train", seed=21)
    w2, _ = synth.generate_split(40, "train", seed=21)
    assert [w.label for w in w1] == [w.label for w in w2]
    assert all(np.array_equal(a.acc, b.acc) for a, b in zip(w1, w2))
    _, reasons = fx.prepare(w1)
    assert sum(r is not None for r in reasons) <= 2
    labels = {w.label for w in w1}
    assert labels == set(synth.LABELS)
    assert sum(w.label == "pickup" for w in w1) == 20
