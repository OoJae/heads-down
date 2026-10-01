"""Exported models evaluate exactly like the libraries that trained them; thresholds and calibration."""
import json
import os

import numpy as np
import pytest

from classifier import evaluate as ev
from classifier import features as fx
from classifier import models as md
from classifier import synth

MODEL_DIR = os.path.join(os.path.dirname(__file__), "..", "model")


@pytest.fixture(scope="module")
def data():
    ws, _ = synth.generate_split(240, "train", seed=31)
    A, reasons = fx.prepare(ws)
    ok = np.array([r is None for r in reasons])
    X = fx.features_from_grid(A[ok])
    C = fx.channels_from_grid(A[ok])
    y = np.array([w.label == "pickup" for w, k in zip(ws, ok) if k]).astype(int)
    return X, C, y


def test_gbdt_export_matches_sklearn(data):
    X, _, y = data
    doc, m = md.fit_gbdt(X, y)
    assert np.max(np.abs(md.logit_gbdt(doc, X) - m.decision_function(X))) < 1e-9
    for tree in doc["trees"]:
        for i, node in enumerate(tree):
            if not node[5]:
                assert i < node[2] < len(tree) and i < node[3] < len(tree), "children follow their parent"


def test_logistic_export_matches_sklearn(data):
    X, _, y = data
    doc = md.fit_logistic(X, y, X, y)
    from sklearn.linear_model import LogisticRegression

    mean, scale = np.asarray(doc["mean"]), np.asarray(doc["scale"])
    Z = np.clip((X - mean) / scale, -doc["std_clip"], doc["std_clip"])
    m = LogisticRegression(C=doc["hyper"]["C"], class_weight="balanced", max_iter=5000, random_state=0).fit(Z, y)
    assert np.max(np.abs(md.logit_logistic(doc, X) - m.decision_function(Z))) < 1e-9


def test_loop_reference_equals_vectorized_for_every_shipped_model(data):
    X, C, _ = data
    for kind in ("logistic", "gbdt", "cnn"):
        doc = json.load(open(os.path.join(MODEL_DIR, f"pickup_{kind}.json")))
        for i in range(0, len(X), 15):
            z, lg, p = md.predict_json(doc, X[i], C[i])
            assert z == pytest.approx(float(md.raw_logit(doc["model"], X[i : i + 1], C[i : i + 1])[0]), abs=1e-9)
            assert 0.0 <= p <= 1.0 and lg == pytest.approx(doc["calibration"]["a"] * np.clip(z, -30, 30) + doc["calibration"]["b"])


def test_cnn_json_matches_keras(data):
    pytest.importorskip("tensorflow")
    _, C, y = data
    doc, keras_model, _ = md.fit_cnn(C, y, C, y, epochs=2)
    k = keras_model.predict(C[:20].astype(np.float32), verbose=0)[:, 0]
    assert np.max(np.abs(md.logit_cnn(doc, C[:20]) - k)) < 1e-4  # float32 Keras vs float64 reference


def test_platt_is_finite_on_separable_data():
    z = np.concatenate([np.linspace(5, 30, 200), np.linspace(-30, -5, 200)])
    y = np.concatenate([np.ones(200), np.zeros(200)])
    a, b = md.fit_platt(z, y)
    assert np.isfinite(a) and np.isfinite(b) and 0 < a < 5
    p = md.calibrated(z, (a, b))
    # Smoothed targets keep the boundary honest (~0.89 at the closest point), not 0/1.
    assert p[:200].min() > 0.8 and p[200:].max() < 0.2
    assert p[:200].mean() > 0.97 and p[200:].mean() < 0.03


def test_threshold_rules():
    pos = np.linspace(2.0, 10.0, 1000)
    neg = np.linspace(-10.0, -2.0, 1000)
    t, rule, gap = ev.choose_threshold(pos, neg, 0.995)
    assert rule.startswith("midpoint") and gap > 0 and -2.0 < t < 2.0
    assert np.mean(pos >= t) == 1.0
    neg2 = np.linspace(-10.0, 5.0, 1000)
    t2, rule2, gap2 = ev.choose_threshold(pos, neg2, 0.995)
    assert rule2.startswith("recall") and gap2 < 0
    assert np.mean(pos >= t2) >= 0.995


def test_wilson_interval():
    lo, hi = ev.wilson(999, 1000)
    assert lo < 0.999 < hi <= 1.0
    assert ev.wilson(0, 0) == (None, None)


def test_selection_rule_prefers_the_simplest_model_within_tolerance():
    from classifier.train import select

    assert select({"logistic": 0.004, "gbdt": 0.0, "cnn": 0.001}) == "logistic"
    assert select({"logistic": 0.05, "gbdt": 0.01, "cnn": 0.011}) == "gbdt"
    assert select({"logistic": 0.05, "gbdt": 0.04, "cnn": 0.01}) == "cnn"


def test_shipped_model_meets_the_recall_target_on_its_own_report():
    m = json.load(open(os.path.join(MODEL_DIR, "pickup_metrics.json")))
    ch = m["selected"]
    for split in ("val", "test_iid", "test_shift"):
        r = m["results"][ch][split]["at_threshold"]["pickup_recall"]
        assert r["rate"] >= 0.99, f"{split}: recall {r['rate']}"
    doc = json.load(open(os.path.join(MODEL_DIR, "pickup_model.json")))
    assert doc["model"]["type"] == ch and doc["threshold_logit"] == pytest.approx(m["threshold_logit"])
