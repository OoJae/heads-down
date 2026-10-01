"""Feature spec v1: cleaning, the fail-closed quality check, resampling, invariances, determinism."""
import json
import math
import os

import numpy as np
import pytest

from classifier import features as fx
from classifier import synth
from classifier.trigger import Window

MODEL_DIR = os.path.join(os.path.dirname(__file__), "..", "model")


def rest_window(n=260, rate_hz=50.0, trigger_ns=10_000_000_000, z=-9.80665):
    t = trigger_ns - 2_000_000_000 + (np.arange(n) * (1e9 / rate_hz)).astype(np.int64)
    acc = np.tile(np.array([0.0, 0.0, z], dtype=np.float32), (n, 1))
    return Window(t, acc, trigger_ns, "bump")


def test_clean_drops_nan_duplicates_and_out_of_order():
    w = rest_window()
    t, a = w.t_ns.copy(), w.acc.copy()
    t[5] = t[4]
    t[9] = t[7]
    a[12, 0] = np.nan
    tc, ac = fx.clean(t, a)
    assert len(tc) == len(t) - 3
    assert np.all(np.diff(tc) > 0) and np.isfinite(ac).all()


def test_quality_rules():
    w = rest_window()
    assert fx.quality(w.t_ns, w.trigger_ns) is None
    assert fx.quality(w.t_ns[::3], w.trigger_ns) == "too_few_samples"
    hole = (w.t_ns > w.trigger_ns) & (w.t_ns < w.trigger_ns + 600_000_000)
    assert fx.quality(w.t_ns[~hole], w.trigger_ns) == "gap"
    late = w.t_ns >= w.trigger_ns - 1_000_000_000
    assert fx.quality(w.t_ns[late], w.trigger_ns) == "gap", "less than 2 s of history"


def test_resample_is_exact_on_a_linear_signal_and_holds_edges():
    t = np.arange(0, 6_000_000_000, 33_000_000, dtype=np.int64) + 1_000_000_000
    x = (t - t[0]).astype(np.float64) / 1e9
    acc = np.stack([x, 2 * x, -x], axis=1).astype(np.float32)
    trig = 3_100_000_000
    g = fx.resample(t, acc, trig)
    grid = trig + (np.arange(fx.N) - fx.T0) * fx.STEP_NS
    inside = (grid >= t[0]) & (grid <= t[-1])
    want = (grid - t[0]) / 1e9
    assert np.max(np.abs(g[inside, 0] - want[inside])) < 1e-6  # float32 inputs
    assert np.all(g[~inside & (grid < t[0]), 0] == acc[0, 0])


def test_rest_window_features_are_quiet():
    w = rest_window()
    F = fx.features_from_grid(fx.resample(w.t_ns, w.acc, w.trigger_ns)[None])[0]
    f = dict(zip(fx.FEATURE_NAMES, F))
    assert f["pre_tilt"] == pytest.approx(0.0, abs=1e-9)
    assert f["tilt_end"] == pytest.approx(0.0, abs=1e-9)
    assert f["late_motion"] == pytest.approx(0.0, abs=1e-9)
    assert f["pre_mag"] == pytest.approx(1.0, abs=1e-6)
    assert np.isfinite(F).all()


def test_features_do_not_depend_on_how_the_phone_is_turned_on_the_table():
    """Yaw (rotation about the screen normal) must not change any feature or channel."""
    ws, _ = synth.generate_split(24, "test_iid", seed=11)
    A, reasons = fx.prepare(ws)
    ok = np.array([r is None for r in reasons])
    A = A[ok]
    for ang in (0.7, 2.1, -1.3):
        c, s = math.cos(ang), math.sin(ang)
        R = np.array([[c, -s, 0], [s, c, 0], [0, 0, 1]])
        B = A @ R.T
        assert np.max(np.abs(fx.features_from_grid(A) - fx.features_from_grid(B))) < 1e-7
        assert np.max(np.abs(fx.channels_from_grid(A) - fx.channels_from_grid(B))) < 1e-9


def test_batched_equals_one_by_one():
    ws, _ = synth.generate_split(12, "test_iid", seed=12)
    A, _ = fx.prepare(ws)
    F = fx.features_from_grid(A)
    for i in range(len(ws)):
        assert np.array_equal(F[i], fx.features_from_grid(A[i : i + 1])[0])


def test_feature_names_and_channels_match_the_kotlin_spec_list():
    assert len(fx.FEATURE_NAMES) == 39 and len(set(fx.FEATURE_NAMES)) == 39
    assert fx.CHANNELS == ("mag_dev", "up_dyn", "horiz", "tilt", "rot")


def test_committed_vectors_still_match_the_reference():
    """Drift guard: the vectors the Kotlin tests use are what this code computes today."""
    from classifier import export as ex

    vec = json.load(open(os.path.join(MODEL_DIR, "pickup_vectors.json")))
    assert vec["feature_names"] == list(fx.FEATURE_NAMES)
    for w in vec["windows"]:
        win = ex._decode_window(w["input"], w["label"])  # noqa: SLF001
        t, a = fx.clean(win.t_ns, win.acc)
        assert len(t) == w["clean_count"]
        assert (fx.quality(t, win.trigger_ns) if len(t) else "too_few_samples") == w["quality"]
        if w["quality"] is None:
            F = fx.features_from_grid(fx.resample(t, a, win.trigger_ns)[None])[0]
            assert np.max(np.abs(F - np.asarray(w["features"]))) == 0.0
