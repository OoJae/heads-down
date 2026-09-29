"""Forecaster plumbing: exported weights reproduce the model, targets never leak into features."""
import os
import sys

import numpy as np
import pandas as pd

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import forecast as F  # noqa: E402
from sklearn.linear_model import Ridge  # noqa: E402
from sklearn.pipeline import make_pipeline  # noqa: E402
from sklearn.preprocessing import StandardScaler  # noqa: E402


def test_folded_weights_reproduce_pipeline():
    rng = np.random.default_rng(0)
    X = rng.normal(size=(300, len(F.FEATURES))) * rng.uniform(0.1, 50, len(F.FEATURES)) + rng.normal(size=len(F.FEATURES))
    y = X @ rng.normal(size=len(F.FEATURES)) * 0.01 + rng.normal(size=300) * 0.1
    m = make_pipeline(StandardScaler(), Ridge(alpha=10.0)).fit(X, y)
    sc, rg = m.named_steps["standardscaler"], m.named_steps["ridge"]
    w, b = F.fold_linear(sc.mean_, sc.scale_, rg.coef_, float(rg.intercept_))
    assert np.allclose(X @ w + b, m.predict(X), atol=1e-10)


def _toy_hourly(n=24 * 12, seed=1):
    rng = np.random.default_rng(seed)
    idx = pd.date_range("2026-09-01", periods=n, freq="h", tz="UTC")
    lr = np.cumsum(rng.normal(0, 0.03, n)) - 0.2
    h = pd.DataFrame(index=idx)
    h["close"] = 0.7 * np.exp(np.cumsum(rng.normal(0, 0.005, n)))
    h["ema_mean"] = np.exp(lr) * h["close"] * 1e9
    h["ema_last"] = h["ema_mean"]
    for c in F.FEATURES:
        h[c] = rng.normal(size=n)
    h["lr_now"] = np.log(h["ema_last"] / 1e9 / h["close"])
    return h


def test_targets_look_only_forward():
    h = F.add_targets(_toy_hourly(), (1, 8))
    # y_1 at hour t is the log ratio of hour t+1's means.
    t = h.index[10]
    nxt = h.index[11]
    assert np.isclose(h.at[t, "y_1"], np.log(h.at[nxt, "ema_mean"] / 1e9 / h.at[nxt, "close"]))
    # The last H rows have no target.
    assert h["y_8"].iloc[-8:].isna().all() and h["y_8"].iloc[:-8].notna().all()


def test_walk_forward_trains_only_on_resolved_targets(monkeypatch):
    h = F.add_targets(_toy_hourly(), (8,))
    seen = []
    real = F.make_models

    class Spy:
        def __init__(self, m):
            self.m = m

        def fit(self, X, y):
            seen.append(len(y))
            return self.m.fit(X, y)

        def predict(self, X):
            return self.m.predict(X)

    monkeypatch.setattr(F, "make_models", lambda seed=0: {k: Spy(v) for k, v in real(seed).items()})
    pred = F.walk_forward(h, 8, "2026-09-01", min_train_days=3)
    assert len(pred) > 0 and seen
    # Every prediction hour is after the training cut, and training rows end H hours before it.
    first_cut = pd.Timestamp("2026-09-01", tz="UTC") + pd.Timedelta(days=3)
    d = h.dropna(subset=F.FEATURES + ["y_8"])
    max_train_first = int(((d.index + pd.Timedelta(hours=8)) < first_cut).sum())
    assert seen[0] == max_train_first
    assert pred.index.min() >= first_cut
