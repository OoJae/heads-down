"""Exports: the model JSON documents, the .tflite flatbuffer, and the cross-language test vectors."""
from __future__ import annotations

import json
import math
import os
import shutil
from typing import Dict, List, Optional, Sequence, Tuple

import numpy as np

from . import features as fx
from . import models as md
from .trigger import MotionTrigger, Window

FORMAT = "headsdown.foreman.pickup"
HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", "..", ".."))
ANDROID_ML = os.path.join(REPO, "android", "ml")
ASSET_DIR = os.path.join(ANDROID_ML, "src", "main", "assets", "foreman")
TEST_RES_DIR = os.path.join(ANDROID_ML, "src", "test", "resources", "foreman")


def model_document(model: Dict, platt: Tuple[float, float], threshold_logit: float, meta: Dict) -> Dict:
    return {
        "format": FORMAT,
        "version": 1,
        "feature_spec": fx.SPEC_VERSION,
        "model": model,
        "calibration": {"a": platt[0], "b": platt[1], "logit_clip": md.LOGIT_CLIP},
        "threshold_logit": threshold_logit,
        "threshold": 1.0 / (1.0 + math.exp(-threshold_logit)),
        "fail_closed": "verdict PICKUP when the window fails the quality check",
        **meta,
    }


def dump(doc: object, path: str, compact: bool = False) -> None:
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as fh:
        if compact:
            json.dump(doc, fh, separators=(",", ":"), allow_nan=False)
        else:
            json.dump(doc, fh, indent=1, allow_nan=False)
        fh.write("\n")


# ----------------------------------------------------------------------------- tflite


def cnn_tf_function(model: Dict):
    """The CNN as a plain tf.function (fixed [1, 250, 5] float32 input, logit output)."""
    import tensorflow as tf

    consts = []
    for layer in model["layers"]:
        if layer["kind"] == "conv1d_relu":
            W = np.asarray(layer["w"], dtype=np.float32).reshape(layer["kernel"], layer["in"], layer["out"])
            consts.append((layer, tf.constant(W), tf.constant(np.asarray(layer["b"], dtype=np.float32))))
        elif layer["kind"] == "dense":
            consts.append((layer, tf.constant(np.asarray(layer["w"], dtype=np.float32).reshape(-1, 1)),
                           tf.constant(np.asarray([layer["b"]], dtype=np.float32))))
        else:
            consts.append((layer, None, None))

    @tf.function(input_signature=[tf.TensorSpec([1, fx.N, len(fx.CHANNELS)], tf.float32, name="channels")])
    def forward(x):
        h = x
        for layer, W, b in consts:
            if layer["kind"] == "conv1d_relu":
                p = layer["pad"]
                h = tf.pad(h, [[0, 0], [p, p], [0, 0]])
                h = tf.nn.relu(tf.nn.conv1d(h, W, stride=layer["stride"], padding="VALID") + b)
            elif layer["kind"] == "avg_max_pool":
                h = tf.concat([tf.reduce_mean(h, axis=1), tf.reduce_max(h, axis=1)], axis=1)
            else:
                h = tf.matmul(h, W) + b
        return {"logit": h}

    return forward


def logistic_tf_function(model: Dict):
    """The logistic model as a tf.function (fixed [1, 39] float32 features, raw logit output)."""
    import tensorflow as tf

    mean = tf.constant(np.asarray(model["mean"], dtype=np.float32)[None])
    scale = tf.constant(np.asarray(model["scale"], dtype=np.float32)[None])
    coef = tf.constant(np.asarray(model["coef"], dtype=np.float32).reshape(-1, 1))
    icpt = tf.constant(np.asarray([[model["intercept"]]], dtype=np.float32))
    clip = float(model["std_clip"])

    @tf.function(input_signature=[tf.TensorSpec([1, len(fx.FEATURE_NAMES)], tf.float32, name="features")])
    def forward(x):
        z = tf.clip_by_value((x - mean) / scale, -clip, clip)
        return {"logit": tf.matmul(z, coef) + icpt}

    return forward


def tflite_supported(model: Dict) -> bool:
    return model["type"] in ("cnn", "logistic")


def export_tflite(model: Dict, path: str) -> bytes:
    """Float32 LiteRT flatbuffer: `cnn` takes [1, 250, 5] channels, `logistic` [1, 39] features;
    both return the raw logit (calibration and threshold stay in the model JSON)."""
    import tensorflow as tf

    fn = cnn_tf_function(model) if model["type"] == "cnn" else logistic_tf_function(model)
    conv = tf.lite.TFLiteConverter.from_concrete_functions([fn.get_concrete_function()], fn)
    conv.optimizations = []
    blob = conv.convert()
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as fh:
        fh.write(blob)
    return blob


def tflite_logits(blob: bytes, inputs: np.ndarray) -> np.ndarray:
    """Runs the flatbuffer in the LiteRT interpreter (ai_edge_litert if installed, else TF's)."""
    try:
        from ai_edge_litert.interpreter import Interpreter  # type: ignore
    except ImportError:
        import warnings

        import tensorflow as tf

        warnings.filterwarnings("ignore", message=".*tf.lite.Interpreter is deprecated.*")
        Interpreter = tf.lite.Interpreter
    interp = Interpreter(model_content=blob)
    interp.allocate_tensors()
    inp = interp.get_input_details()[0]
    out = interp.get_output_details()[0]
    res = []
    for x in inputs:
        interp.set_tensor(inp["index"], x[None].astype(np.float32))
        interp.invoke()
        res.append(float(interp.get_tensor(out["index"]).ravel()[0]))
    return np.asarray(res)


# ----------------------------------------------------------------------------- vectors


def _f32(v: float) -> Optional[float]:
    """A float32 as its shortest decimal (NaN, which JSON cannot hold, as null). Both sides then
    read the input the same way, double parse then round to float32 (np.float32 / toFloat())."""
    f = np.float32(v)
    if np.isnan(f):
        return None
    return float(np.format_float_positional(f, unique=True, trim="-"))


def _decode_f32(v: Optional[float]) -> np.float32:
    return np.float32(np.nan) if v is None else np.float32(float(v))


def _window_json(w: Window) -> Dict:
    return {
        "trigger_ns": int(w.trigger_ns),
        "t_ns": [int(t) for t in w.t_ns],
        "xyz": [[_f32(a[0]), _f32(a[1]), _f32(a[2])] for a in np.asarray(w.acc, dtype=np.float32)],
    }


def edge_cases(base: Window) -> List[Tuple[str, Window]]:
    """Inputs the Kotlin port must treat exactly like the reference (cleaning + fail-closed)."""
    t, a = np.asarray(base.t_ns), np.asarray(base.acc, dtype=np.float32)
    out = []
    # Duplicate and out-of-order timestamps, and a NaN sample: dropped by clean().
    t2, a2 = t.copy(), a.copy()
    t2[10] = t2[9]
    t2[20] = t2[18]
    a2[30, 1] = np.nan
    out.append(("dup_out_of_order_nan", Window(t2, a2, base.trigger_ns, base.label, {})))
    # A 600 ms hole after the trigger: fail-closed ("gap").
    hole = (t > base.trigger_ns + 400_000_000) & (t < base.trigger_ns + 1_000_000_000)
    out.append(("gap_600ms", Window(t[~hole], a[~hole], base.trigger_ns, base.label, {})))
    # Every third sample only (~17 Hz): fail-closed ("too_few_samples").
    out.append(("sparse_17hz", Window(t[::3], a[::3], base.trigger_ns, base.label, {})))
    # History starts at the trigger (collector just started): fail-closed ("gap").
    late = t >= base.trigger_ns
    out.append(("no_history", Window(t[late], a[late], base.trigger_ns, base.label, {})))
    return out


def _decode_window(inp: Dict, label: str) -> Window:
    """The window exactly as a JSON reader rebuilds it (double parse, then float32)."""
    acc = np.array([[_decode_f32(v) for v in row] for row in inp["xyz"]], dtype=np.float32).reshape(-1, 3)
    return Window(np.asarray(inp["t_ns"], dtype=np.int64), acc, int(inp["trigger_ns"]), label)


def build_vectors(docs: Dict[str, Dict], windows: Sequence[Window], stream: Tuple[np.ndarray, np.ndarray],
                  tflite: Dict[str, Tuple[str, bytes]]) -> Dict:
    """Cross-language vectors: trigger, cleaning, quality, resampling, features, channels, logits.

    `tflite` maps a name to (model type, flatbuffer); their logits are recorded too (float32)."""
    t_s, a_s = stream
    stream_json = {"t_ns": [int(t) for t in t_s], "xyz": [[_f32(v) for v in row] for row in np.asarray(a_s, dtype=np.float32)]}
    dec = _decode_window({**stream_json, "trigger_ns": 0}, "")
    trig = MotionTrigger()
    fired = []
    for i in range(len(dec.t_ns)):
        if trig.on_sample(int(dec.t_ns[i]), float(dec.acc[i, 0]), float(dec.acc[i, 1]), float(dec.acc[i, 2])):
            fired.append(int(dec.t_ns[i]))
    vec: Dict[str, object] = {
        "format": "headsdown.foreman.pickup.vectors",
        "feature_spec": fx.SPEC_VERSION,
        "tolerance": 1e-6,
        "feature_names": list(fx.FEATURE_NAMES),
        "channels": list(fx.CHANNELS),
        "trigger": {**stream_json, "fired_ns": fired},
        "windows": [],
    }
    cases: List[Tuple[str, Window]] = [(f"{w.label}/{w.meta.get('variant')}/{i}", w) for i, w in enumerate(windows)]
    cases += edge_cases(windows[0])
    for name, w0 in cases:
        inp = _window_json(w0)
        w = _decode_window(inp, w0.label)
        t, a = fx.clean(w.t_ns, w.acc)
        reason = fx.quality(t, w.trigger_ns) if len(t) else "too_few_samples"
        entry: Dict[str, object] = {"name": name, "label": w.label, "input": inp,
                                    "clean_count": int(len(t)), "quality": reason}
        if reason is None:
            grid = fx.resample(t, a, w.trigger_ns)
            F = fx.features_from_grid(grid[None])[0]
            C = fx.channels_from_grid(grid[None])[0]
            entry["grid_rows"] = {str(i): grid[i].tolist() for i in (0, 1, 99, 100, 101, 150, 249)}
            entry["features"] = F.tolist()
            entry["channels_rows"] = {str(i): C[i].tolist() for i in (0, 100, 175, 249)}
            entry["channels_sum"] = C.sum(axis=0).tolist()
            outs = {}
            for key, doc in docs.items():
                z, lg, p = md.predict_json(doc, F, C)
                outs[key] = {"logit": z, "calibrated_logit": lg, "p": p, "pickup": bool(lg >= doc["threshold_logit"])}
            for key, (kind, blob) in tflite.items():
                x = C if kind == "cnn" else F
                outs[f"tflite_{key}_logit"] = float(tflite_logits(blob, x[None])[0])
            entry["outputs"] = outs
        vec["windows"].append(entry)
    return vec


def sync_android(files: Dict[str, str]) -> List[str]:
    """Copies {src: dst} into android/ml, returning the destinations written."""
    written = []
    for src, dst in files.items():
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        shutil.copyfile(src, dst)
        written.append(os.path.relpath(dst, REPO))
    return written
