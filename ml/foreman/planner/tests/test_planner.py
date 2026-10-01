"""Planner: log parsing, slot labels, the posterior, windows, the weekly split, and drift guards."""
import json
import math
import os
import random

import numpy as np
import pytest

from planner import archetypes as ar
from planner import evaluate as ev
from planner import logs
from planner import model as pm

MODEL_DIR = os.path.join(os.path.dirname(__file__), "..", "model")
TZ = 60
DAY0 = 20_000


def at(day, minute):
    return (DAY0 + day) * logs.DAY_MS + minute * 60_000 - TZ * 60_000


def ev_(t, typ, **data):
    return logs.Event(t, TZ, typ, tuple(sorted(data.items())))


def test_parse_line_accepts_the_schema_and_skips_junk():
    e = logs.parse_line('{"ts": 1790000000000, "tz": 60, "type": "alarm_next", "alarm_ts": 1790025000000}')
    assert e is not None and e.get("alarm_ts") == 1790025000000 and e.local_ms == 1790000000000 + 3_600_000
    assert logs.parse_line('{"ts": 5, "type": "screen_off"}').tz == 0
    for junk in ev.JUNK_LINES + ['{"ts": 1.5, "tz": 60, "type": "screen_on"}', '[1]', '{"ts": 1, "tz": "60", "type": "screen_on"}']:
        assert logs.parse_line(junk) is None, junk
    assert logs.parse_line(e.to_json()) == e


def test_slot_labels_idle_busy_missing():
    events = [
        ev_(at(0, 0), "monitor_start"), ev_(at(0, 0) + 1, "screen_off"),
        ev_(at(0, 20), "screen_on"), ev_(at(0, 20) + 10_000, "screen_off"),  # notification: still idle
        ev_(at(0, 35), "screen_on"), ev_(at(0, 35) + 2_000, "user_present"), ev_(at(0, 36), "screen_off"),
        ev_(at(0, 61), "screen_on"), ev_(at(0, 64), "screen_off"),  # 3 of 15 minutes on: busy
        ev_(at(0, 80), "monitor_stop"),
    ]
    lab = logs.slot_labels(events, until_ts=at(0, 120))
    assert lab[(DAY0, 0)] == logs.IDLE and lab[(DAY0, 1)] == logs.IDLE and lab[(DAY0, 2)] == logs.BUSY
    assert lab[(DAY0, 3)] == logs.IDLE and lab[(DAY0, 4)] == logs.BUSY
    assert (DAY0, 5) not in lab, "5 observed minutes before monitor_stop: missing"
    assert (DAY0, 6) not in lab


def test_last_alarm():
    events = [ev_(at(0, 17 * 60), "alarm_next", alarm_ts=at(1, 405)), ev_(at(0, 18 * 60), "alarm_next", alarm_ts=at(1, 420))]
    assert logs.last_alarm(events, at(0, 20 * 60)) == at(1, 420)
    assert logs.last_alarm(events, at(1, 480)) is None
    assert logs.last_alarm(events, at(0, 16 * 60)) is None


def test_day_of_week():
    assert logs.day_of_week(0) == 3  # 1970-01-01 was a Thursday
    assert logs.day_of_week(4) == 0


@pytest.mark.parametrize("arch", ar.ARCHETYPES)
def test_fit_fast_matches_the_reference_fit(arch):
    u = ar.simulate(arch, 3, days=30)
    M = ev.label_matrix(u)
    params = pm.Params(prior=tuple(np.linspace(0.2, 0.9, 96)), kernel=(0.25, 0.5, 1.0, 0.5, 0.25), k0=3.0, k1=5.0, k2=7.0)
    for o in (3, 17, 28):
        m2, lower, evd, nights = ev.fit_fast(M, o, u.start_day, params)
        now = ev.origin_ts(u, o)
        post = pm.fit(logs.slot_labels(u.events, until_ts=now), (now + TZ * 60_000) // logs.DAY_MS, params)
        assert np.max(np.abs(np.asarray(post.mean) - m2)) < 1e-12
        assert np.max(np.abs(np.asarray(post.lower) - lower)) < 1e-12
        assert post.nights == nights


def test_no_history_is_the_prior():
    params = pm.Params(prior=tuple(np.linspace(0.1, 0.9, 96)))
    post = pm.fit({}, DAY0, params)
    for d in range(7):
        assert np.allclose(post.mean[d], params.prior, atol=1e-12)
    assert post.nights == 0


def test_survival_rule_fast_matches_loop_and_prefers_surviving_runs():
    rnd = random.Random(1)
    params = pm.Params(window_rule="survival", window_threshold=0.7, window_gamma=0.5)
    for _ in range(200):
        slots = [pm.Slot(i, rnd.random() ** 0.3, 0.0, 0.0) for i in range(96)]
        assert pm.best_run_survival(slots, params) == pm.best_run_survival_fast(slots, params)
    # A long idle night with one risky slot in the middle: the rule cuts at the risk, Kadane bridges it.
    p = [0.2] * 20 + [0.97] * 20 + [0.3] + [0.97] * 30 + [0.2] * 25
    slots = [pm.Slot(i, v, 0.0, 0.0) for i, v in enumerate(p)]
    assert pm.best_run_survival(slots, params) == (41, 70)
    assert pm.best_run_kadane(slots, pm.Params(window_rule="kadane", window_threshold=0.6)) == (20, 70)


def test_window_ends_at_the_alarm_and_auto_arm_needs_history():
    params = pm.Params(window_rule="survival", window_threshold=0.5, window_gamma=0.5, auto_arm_threshold=0.5)
    slots = [pm.Slot(at(0, 18 * 60) + i * logs.SLOT_MS, 0.99 if 20 <= i < 52 else 0.1, 0.9, 10.0) for i in range(96)]
    alarm = slots[45].start_ts + 7 * 60_000
    w = pm.propose_window(slots, slots[0].start_ts, alarm, nights=10, params=params)
    assert w.start_ts == slots[20].start_ts and w.end_ts == alarm and w.auto_arm and w.reason_code == "ok"
    w2 = pm.propose_window(slots, slots[0].start_ts, None, nights=2, params=params)
    assert w2.end_ts == slots[51].start_ts + logs.SLOT_MS and not w2.auto_arm and w2.reason_code == "history"


def test_split_week_respects_every_cap():
    rnd = random.Random(2)
    for _ in range(2000):
        windows = [None if rnd.random() < 0.2 else pm.Window(0, 1, 1, 0.9, 0.8, rnd.uniform(0, 12), False, "", "ok")
                   for _ in range(7)]
        chunk = rnd.randint(1, 5_000_000)
        remaining = rnd.randint(-1000, 2_000_000_000)
        cap_shift = rnd.randint(-1000, 500_000_000)
        split = pm.split_week(windows, remaining, cap_shift, chunk)
        assert sum(split) <= max(0, remaining)
        for w, v in zip(windows, split):
            assert v >= 0 and v % chunk == 0 and v <= max(0, cap_shift)
            assert v // chunk <= math.floor(pm.expected_rounds(w, pm.ROUND_SECONDS))


def test_simulation_is_deterministic_and_events_are_schema_valid():
    a = ar.simulate("regular", 9, days=10)
    b = ar.simulate("regular", 9, days=10)
    assert [e.to_json() for e in a.events] == [e.to_json() for e in b.events]
    assert all(logs.parse_line(e.to_json()) == e for e in a.events[:500])
    assert all(e.type in logs.TYPES for e in a.events)


def test_shipped_params_and_vectors_are_reproducible():
    """Drift guard: re-running the reference on the committed parameters rebuilds the vectors."""
    params = pm.load_params(os.path.join(MODEL_DIR, "planner_params.json"))
    committed = json.load(open(os.path.join(MODEL_DIR, "planner_vectors.json")))
    assert committed["params"] == params.to_json()
    rebuilt = ev.to_jsonable(ev.build_vectors(params))
    assert json.dumps(rebuilt, sort_keys=True) == json.dumps(committed, sort_keys=True)


def test_report_beats_the_fixed_window_on_completed_dark_hours():
    m = json.load(open(os.path.join(MODEL_DIR, "planner_metrics.json")))
    ours = m["test"]["beta_dow"]["window"]
    fixed = m["test"]["fixed_23_07"]["window"]
    assert ours["completed_dark_h"] > fixed["completed_dark_h"]
    assert m["test"]["beta_dow"]["calibration"]["brier"] < m["test"]["prior"]["calibration"]["brier"]
