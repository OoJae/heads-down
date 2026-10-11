"""Scoring the shipped model on an export: fail-closed windows, the rates and what is listed."""
import json
import os

import numpy as np

from classifier import score_real as sr
from classifier import sensorlab as sl
from classifier.trigger import Window

SAMPLE = os.path.join(os.path.dirname(__file__), "..", "data", "sample", "sensorlab-export-sample.csv")


def _doc():
    with open(sr.SHIPPED, encoding="utf-8") as fh:
        return json.load(fh)


def _sample_windows():
    windows, _ = sl.assign_labels(sl.load(SAMPLE), {})
    return windows


def test_the_shipped_model_is_scored_on_the_committed_sample(capsys):
    assert sr.main([SAMPLE]) == 0
    out = capsys.readouterr().out
    assert "pickup_logistic" in out and "pickups caught   1/1 = 100.0%" in out
    # One pickup caught is not "the model catches every pickup": the interval says how little that is.
    assert "95% interval 20.7% to 100.0%" in out


def test_a_window_that_fails_the_quality_check_is_a_pickup_verdict():
    windows = _sample_windows()
    bump = next(w for w in windows if w.label == "bump")
    # Twenty samples are too few for the feature grid: the phone treats that as a pickup.
    short = Window(t_ns=bump.t_ns[:20], acc=bump.acc[:20], trigger_ns=int(bump.t_ns[5]), label="bump", meta={"session_id": "short"})
    result = sr.score(_doc(), [bump, short])
    assert result["failed_quality_check"] == 1
    assert result["rates"]["bump_false_break"]["k"] == 1 and result["rates"]["bump_false_break"]["n"] == 2
    assert [f["session"] for f in result["false_breaks"]] == ["short"]
    assert result["false_breaks"][0]["logit"] is None and result["false_breaks"][0]["failed_quality_check"]


def test_a_missed_pickup_is_listed_and_counted():
    windows = _sample_windows()
    pickup = next(w for w in windows if w.label == "pickup")
    doc = dict(_doc())
    doc["threshold_logit"] = 1e9  # a threshold nothing reaches
    result = sr.score(doc, [pickup])
    assert result["rates"]["pickup_recall"]["k"] == 0 and result["rates"]["pickup_recall"]["n"] == 1
    assert len(result["missed_pickups"]) == 1 and np.isfinite(result["missed_pickups"][0]["logit"])
    assert "missed pickups:" in sr.render(result, {"sessions": 1, "windows": 1, "per_label": {"pickup": 1}})
