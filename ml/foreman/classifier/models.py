"""Pickup models: a logistic baseline, a small gradient-boosted tree model, and a tiny 1D-CNN.

Each exports to a JSON document that android/ml (xyz.headsdown.ml.pickup) evaluates in pure
Kotlin. `predict_json` below is the loop-faithful reference for that Kotlin code: the same
operation order, in float64. The CNN is also exported to a .tflite flatbuffer (see export.py).

Every model outputs a logit z. The calibrated logit is l = a * clip(z, +-30) + b with (a, b)
fitted on the validation split (Platt scaling), P(pickup) = sigmoid(l), and the verdict is PICKUP
iff l >= threshold_logit (compared in logit space: probabilities saturate at 1.0).
"""
from __future__ import annotations

import math
from typing import Dict, List, Optional, Sequence, Tuple

import numpy as np

from . import features as fx

LOGIT_CLIP = 30.0
STD_CLIP = 10.0


def sigmoid(z: np.ndarray) -> np.ndarray:
    return 1.0 / (1.0 + np.exp(-z))


# ----------------------------------------------------------------------------- logistic


def fit_logistic(X: np.ndarray, y: np.ndarray, Xv: np.ndarray, yv: np.ndarray, seed: int = 0) -> Dict:
    from sklearn.linear_model import LogisticRegression

    mean = X.mean(axis=0)
    scale = X.std(axis=0)
    scale = np.where(scale < 1e-9, 1.0, scale)
    Z = np.clip((X - mean) / scale, -STD_CLIP, STD_CLIP)
    Zv = np.clip((Xv - mean) / scale, -STD_CLIP, STD_CLIP)
    best = None
    for C in (0.01, 0.03, 0.1, 0.3, 1.0, 3.0):
        m = LogisticRegression(C=C, class_weight="balanced", max_iter=5000, random_state=seed)
        m.fit(Z, y)
        pv = np.clip(m.predict_proba(Zv)[:, 1], 1e-12, 1 - 1e-12)
        ll = -np.mean(yv * np.log(pv) + (1 - yv) * np.log(1 - pv))
        if best is None or ll < best[0]:
            best = (ll, C, m)
    val_ll, C, m = best
    return {
        "type": "logistic",
        "features": list(fx.FEATURE_NAMES),
        "mean": mean.tolist(),
        "scale": scale.tolist(),
        "std_clip": STD_CLIP,
        "coef": m.coef_[0].tolist(),
        "intercept": float(m.intercept_[0]),
        "hyper": {"C": C, "class_weight": "balanced", "val_log_loss": round(float(val_ll), 6)},
    }


def logit_logistic(model: Dict, X: np.ndarray) -> np.ndarray:
    """Vectorized (fast) logit; `predict_json` has the loop-faithful form."""
    Z = np.clip((X - np.asarray(model["mean"])) / np.asarray(model["scale"]), -model["std_clip"], model["std_clip"])
    return Z @ np.asarray(model["coef"]) + model["intercept"]


# ----------------------------------------------------------------------------- gbdt


def fit_gbdt(X: np.ndarray, y: np.ndarray, seed: int = 0) -> Tuple[Dict, object]:
    from sklearn.ensemble import HistGradientBoostingClassifier

    m = HistGradientBoostingClassifier(
        max_depth=3,
        max_iter=150,
        learning_rate=0.1,
        min_samples_leaf=40,
        l2_regularization=1.0,
        early_stopping=False,
        class_weight="balanced",
        random_state=seed,
    )
    m.fit(X, y)
    trees = []
    for per_iter in m._predictors:  # noqa: SLF001 (sklearn internals, pinned; checked by tests)
        nodes = per_iter[0].nodes
        trees.append(
            [
                [
                    int(n["feature_idx"]),
                    float(n["num_threshold"]),
                    int(n["left"]),
                    int(n["right"]),
                    int(n["missing_go_to_left"]),
                    int(n["is_leaf"]),
                    float(n["value"]),
                ]
                for n in nodes
            ]
        )
    baseline = float(np.asarray(m._baseline_prediction).ravel()[0])  # noqa: SLF001
    doc = {
        "type": "gbdt",
        "features": list(fx.FEATURE_NAMES),
        "node_layout": ["feature", "threshold", "left", "right", "missing_left", "is_leaf", "value"],
        "baseline": baseline,
        "trees": trees,
        "hyper": {"max_depth": 3, "max_iter": 150, "learning_rate": 0.1, "min_samples_leaf": 40, "l2": 1.0},
    }
    return doc, m


def logit_gbdt(model: Dict, X: np.ndarray) -> np.ndarray:
    out = np.full(len(X), model["baseline"], dtype=np.float64)
    for tree in model["trees"]:
        out += np.array([_walk(tree, x) for x in X])
    return out


def _walk(tree: List[List[float]], x: np.ndarray) -> float:
    i = 0
    while True:
        f, thr, left, right, miss_left, is_leaf, value = tree[i]
        if is_leaf:
            return value
        v = x[int(f)]
        if math.isnan(v):
            i = int(left) if miss_left else int(right)
        else:
            i = int(left) if v <= thr else int(right)


# ----------------------------------------------------------------------------- cnn

CNN_LAYERS = ((8, 7, 2, 3), (12, 5, 2, 2), (16, 5, 2, 2))  # (filters, kernel, stride, pad)


def build_cnn(seed: int):
    import tensorflow as tf

    tf.keras.utils.set_random_seed(seed)
    inp = tf.keras.Input((fx.N, len(fx.CHANNELS)))
    x = inp
    for filters, k, s, p in CNN_LAYERS:
        x = tf.keras.layers.ZeroPadding1D(p)(x)
        x = tf.keras.layers.Conv1D(filters, k, strides=s, activation="relu")(x)
    x = tf.keras.layers.Concatenate()([tf.keras.layers.GlobalAveragePooling1D()(x), tf.keras.layers.GlobalMaxPooling1D()(x)])
    out = tf.keras.layers.Dense(1)(x)
    return tf.keras.Model(inp, out)


def fit_cnn(C: np.ndarray, y: np.ndarray, Cv: np.ndarray, yv: np.ndarray, seed: int = 0, epochs: int = 60) -> Tuple[Dict, object, Dict]:
    import tensorflow as tf

    tf.config.experimental.enable_op_determinism()
    model = build_cnn(seed)
    pos = y.mean()
    cw = {0: 0.5 / (1 - pos), 1: 0.5 / pos}
    model.compile(
        optimizer=tf.keras.optimizers.Adam(2e-3),
        loss=tf.keras.losses.BinaryCrossentropy(from_logits=True),
    )
    stop = tf.keras.callbacks.EarlyStopping(monitor="val_loss", patience=8, restore_best_weights=True)
    hist = model.fit(
        C.astype(np.float32), y.astype(np.float32), validation_data=(Cv.astype(np.float32), yv.astype(np.float32)),
        epochs=epochs, batch_size=64, class_weight=cw, callbacks=[stop], verbose=0, shuffle=True,
    )
    convs = [l for l in model.layers if isinstance(l, tf.keras.layers.Conv1D)]
    dense = [l for l in model.layers if isinstance(l, tf.keras.layers.Dense)][0]
    layers = []
    for (filters, k, s, p), conv in zip(CNN_LAYERS, convs):
        W, b = conv.get_weights()
        layers.append({"kind": "conv1d_relu", "kernel": k, "stride": s, "pad": p, "in": int(W.shape[1]),
                       "out": filters, "w": _f32list(W), "b": _f32list(b)})
    Wd, bd = dense.get_weights()
    layers.append({"kind": "avg_max_pool"})
    layers.append({"kind": "dense", "in": int(Wd.shape[0]), "w": _f32list(Wd[:, 0]), "b": float(np.float32(bd[0]))})
    doc = {"type": "cnn", "channels": list(fx.CHANNELS), "input_length": fx.N, "layers": layers,
           "params": int(model.count_params())}
    info = {"epochs_run": len(hist.history["loss"]), "best_val_loss": float(min(hist.history["val_loss"]))}
    return doc, model, info


def _f32list(a: np.ndarray) -> list:
    """Float32 weights, flattened C-order, written with the shortest repr that round-trips to the
    same float64 (the float64 value of the float32)."""
    return [float(v) for v in np.asarray(a, dtype=np.float32).astype(np.float64).ravel()]


def logit_cnn(model: Dict, C: np.ndarray) -> np.ndarray:
    """Vectorized float64 CNN forward pass (fast; summation order differs from predict_json)."""
    out = []
    for x in C:
        h = x.astype(np.float64)
        for layer in model["layers"]:
            if layer["kind"] == "conv1d_relu":
                k, s, p, cin, cout = layer["kernel"], layer["stride"], layer["pad"], layer["in"], layer["out"]
                W = np.asarray(layer["w"]).reshape(k, cin, cout)
                b = np.asarray(layer["b"])
                hp = np.pad(h, ((p, p), (0, 0)))
                L = (len(hp) - k) // s + 1
                idx = np.arange(L)[:, None] * s + np.arange(k)[None, :]
                h = np.maximum(np.einsum("lkc,kco->lo", hp[idx], W) + b[None], 0.0)
            elif layer["kind"] == "avg_max_pool":
                h = np.concatenate([h.mean(axis=0), h.max(axis=0)])
            else:
                h = float(h @ np.asarray(layer["w"]) + layer["b"])
        out.append(h)
    return np.asarray(out)


# ----------------------------------------------------------------------------- shared


def fit_platt(z: np.ndarray, y: np.ndarray) -> Tuple[float, float]:
    """p = sigmoid(a z + b) by Newton on the log loss, with Platt's smoothed targets
    ((n+ + 1) / (n+ + 2) and 1 / (n- + 2)) so separable validation data cannot drive a to infinity."""
    zc = np.clip(z, -LOGIT_CLIP, LOGIT_CLIP)
    n_pos, n_neg = float(np.sum(y == 1)), float(np.sum(y == 0))
    t = np.where(y == 1, (n_pos + 1.0) / (n_pos + 2.0), 1.0 / (n_neg + 2.0))

    def loss(ab: np.ndarray) -> float:
        l = ab[0] * zc + ab[1]
        # log(1 + e^l) - t l, computed stably
        return float(np.sum(np.logaddexp(0.0, l) - t * l))

    ab = np.array([1.0, 0.0])
    f = loss(ab)
    for _ in range(200):
        p = sigmoid(ab[0] * zc + ab[1])
        g = np.array([np.sum((p - t) * zc), np.sum(p - t)])
        w = p * (1 - p) + 1e-12
        H = np.array([[np.sum(w * zc * zc), np.sum(w * zc)], [np.sum(w * zc), np.sum(w)]]) + 1e-9 * np.eye(2)
        step = np.linalg.solve(H, g)
        s = 1.0
        while s > 1e-8:  # backtracking: never accept a step that increases the loss
            cand = ab - s * step
            fc = loss(cand)
            if fc <= f:
                break
            s *= 0.5
        if s <= 1e-8:
            break
        ab, f = cand, fc
        if np.max(np.abs(s * step)) < 1e-12:
            break
    return float(ab[0]), float(ab[1])


def calibrated_logit(z: np.ndarray, platt: Tuple[float, float]) -> np.ndarray:
    """The decision score: l = a * clip(z, +-30) + b. The verdict compares l with threshold_logit
    (never the probability, which saturates at 1.0 in floating point)."""
    a, b = platt
    return a * np.clip(z, -LOGIT_CLIP, LOGIT_CLIP) + b


def calibrated(z: np.ndarray, platt: Tuple[float, float]) -> np.ndarray:
    return sigmoid(calibrated_logit(z, platt))


def raw_logit(model: Dict, X: np.ndarray, C: np.ndarray) -> np.ndarray:
    if model["type"] == "logistic":
        return logit_logistic(model, X)
    if model["type"] == "gbdt":
        return logit_gbdt(model, X)
    if model["type"] == "cnn":
        return logit_cnn(model, C)
    raise ValueError(model["type"])


# ----------------------------------------------------------------------------- loop-faithful reference


def predict_json(doc: Dict, feats: Sequence[float], chans: Optional[np.ndarray]) -> Tuple[float, float, float]:
    """(raw logit z, calibrated logit l, p) with the exact loop order xyz.headsdown.ml.pickup uses."""
    m = doc["model"]
    if m["type"] == "logistic":
        z = m["intercept"]
        for j in range(len(m["coef"])):
            v = (feats[j] - m["mean"][j]) / m["scale"][j]
            v = min(m["std_clip"], max(-m["std_clip"], v))
            z += m["coef"][j] * v
    elif m["type"] == "gbdt":
        z = m["baseline"]
        for tree in m["trees"]:
            z += _walk(tree, np.asarray(feats, dtype=np.float64))
    elif m["type"] == "cnn":
        z = _cnn_loops(m, chans)
    else:
        raise ValueError(m["type"])
    a, b = doc["calibration"]["a"], doc["calibration"]["b"]
    zc = min(LOGIT_CLIP, max(-LOGIT_CLIP, z))
    lg = a * zc + b
    return float(z), float(lg), 1.0 / (1.0 + math.exp(-lg))


def _cnn_loops(m: Dict, x: np.ndarray) -> float:
    h = [list(map(float, row)) for row in x]
    for layer in m["layers"]:
        if layer["kind"] == "conv1d_relu":
            k, s, p, cin, cout = layer["kernel"], layer["stride"], layer["pad"], layer["in"], layer["out"]
            W, b = layer["w"], layer["b"]
            L_in = len(h)
            L = (L_in + 2 * p - k) // s + 1
            out = []
            for t in range(L):
                row = []
                for o in range(cout):
                    acc = 0.0
                    for j in range(k):
                        src = t * s + j - p
                        if src < 0 or src >= L_in:
                            continue
                        xs = h[src]
                        base = (j * cin) * cout + o
                        for c in range(cin):
                            acc += xs[c] * W[base + c * cout]
                    v = acc + b[o]
                    row.append(v if v > 0.0 else 0.0)
                out.append(row)
            h = out
        elif layer["kind"] == "avg_max_pool":
            n, ch = len(h), len(h[0])
            avg = []
            mx = []
            for c in range(ch):
                sm = 0.0
                best = h[0][c]
                for t in range(n):
                    sm += h[t][c]
                    if h[t][c] > best:
                        best = h[t][c]
                avg.append(sm / n)
                mx.append(best)
            h = [avg + mx]
        else:
            v = h[0]
            acc = 0.0
            for i in range(layer["in"]):
                acc += v[i] * layer["w"][i]
            return acc + layer["b"]
    raise ValueError("cnn without a dense head")
