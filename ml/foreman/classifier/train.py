"""Train, evaluate, select and export the pickup classifier.

    python -m classifier.train                    # synthetic data: writes model/, RESULTS.md, android/ml assets
    python -m classifier.train --quick            # tiny sizes into out/quick (smoke test; no android sync)
    python -m classifier.train --real EXPORT.csv [--conditions CONDITIONS.csv]
                                                  # add real sensor-lab sessions (see retrain.sh)

Selection rule (fixed before any result was seen, see MODEL_CARD.md):
1. Each model's probability is Platt-calibrated on the validation split, and its threshold is the
   largest one that keeps validation pickup recall >= 99.5% (the 99% target plus a margin).
2. Among the three, take the lowest validation all-negatives false-break rate `best`. Walk the
   preference order logistic -> gbdt -> cnn (simplest, most robust to the synthetic-to-real gap
   first) and pick the first model within max(1.25 x best, best + 0.5 pp).
Test splits are only reported, never used to choose.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import sys
import time
from typing import Dict, List, Optional, Tuple

import numpy as np

from . import evaluate as ev
from . import export as ex
from . import features as fx
from . import models as md
from . import report
from . import sensorlab
from . import synth
from .trigger import Window

HERE = os.path.dirname(os.path.abspath(__file__))
MODEL_DIR = os.path.join(HERE, "model")
OUT_DIR = os.path.join(HERE, "..", "out", "classifier")

GENERATOR_VERSION = 1
SIZES = {"train": 10000, "val": 4000, "test_iid": 4000, "test_shift": 4000}
QUICK_SIZES = {"train": 400, "val": 200, "test_iid": 200, "test_shift": 200}
SEEDS = {"train": 101, "val": 202, "test_iid": 303, "test_shift": 404}
VECTOR_SEED = 505
TARGET_RECALL = 0.99
VAL_RECALL = 0.995
PREFERENCE = ("logistic", "gbdt", "cnn")


def log(msg: str) -> None:
    print(f"[{time.strftime('%H:%M:%S')}] {msg}", flush=True)


def build_split(name: str, n: int, seed: int) -> Dict:
    windows, stats = synth.generate_split(n, name, seed)
    return pack(windows, stats)


def pack(windows: List[Window], stats: Optional[Dict] = None) -> Dict:
    grids, reasons = fx.prepare(windows)
    ok = np.array([r is None for r in reasons])
    feats = np.zeros((len(windows), len(fx.FEATURE_NAMES)))
    chans = np.zeros((len(windows), fx.N, len(fx.CHANNELS)))
    if ok.any():
        feats[ok] = fx.features_from_grid(grids[ok])
        chans[ok] = fx.channels_from_grid(grids[ok])
    return {
        "labels": np.array([w.label for w in windows]),
        "metas": [dict(w.meta) for w in windows],
        "reasons": reasons,
        "ok": ok,
        "X": feats,
        "C": chans,
        "stats": stats or {},
        "windows": windows,
    }


def concat(a: Dict, b: Dict, repeat_b: int = 1) -> Dict:
    idx_b = np.tile(np.arange(len(b["labels"])), repeat_b)
    return {
        "labels": np.concatenate([a["labels"], b["labels"][idx_b]]),
        "metas": a["metas"] + [b["metas"][i] for i in idx_b],
        "reasons": a["reasons"] + [b["reasons"][i] for i in idx_b],
        "ok": np.concatenate([a["ok"], b["ok"][idx_b]]),
        "X": np.concatenate([a["X"], b["X"][idx_b]]),
        "C": np.concatenate([a["C"], b["C"][idx_b]]),
        "stats": a["stats"],
        "windows": a["windows"] + [b["windows"][i] for i in idx_b],
    }


def session_split(windows: List[Window]) -> Dict[str, List[Window]]:
    """Deterministic 60/20/20 split by session id (all windows of a session stay together)."""
    out: Dict[str, List[Window]] = {"train": [], "val": [], "test": []}
    for w in windows:
        h = int(hashlib.sha256(str(w.meta.get("session_id")).encode()).hexdigest()[:8], 16) % 10
        out["train" if h < 6 else ("val" if h < 8 else "test")].append(w)
    return out


def scores(model: Dict, platt: Tuple[float, float], d: Dict) -> np.ndarray:
    """Calibrated logits; windows failing the quality check are fail-closed (+inf: always PICKUP)."""
    s = np.full(len(d["labels"]), np.inf)
    ok = d["ok"]
    if ok.any():
        s[ok] = md.calibrated_logit(md.raw_logit(model, d["X"][ok], d["C"][ok]), platt)
    return s


def select(val_fb: Dict[str, float]) -> str:
    best = min(val_fb.values())
    for name in PREFERENCE:
        if name in val_fb and val_fb[name] <= max(1.25 * best, best + 0.005):
            return name
    return min(val_fb, key=val_fb.get)


def main(argv: Optional[List[str]] = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--quick", action="store_true", help="tiny sizes into out/quick; no android sync")
    ap.add_argument("--real", help="sensor lab export CSV to add (real Redmi sessions)")
    ap.add_argument("--conditions", help="optional sidecar CSV: session_id,surface,position,charger,case")
    ap.add_argument("--real-repeat", type=int, default=3, help="weight of real training windows (duplication)")
    ap.add_argument("--no-sync", action="store_true", help="do not copy the exports into android/ml")
    ap.add_argument("--epochs", type=int, default=60)
    args = ap.parse_args(argv)

    sizes = QUICK_SIZES if args.quick else SIZES
    model_dir = os.path.join(OUT_DIR, "quick", "model") if args.quick else MODEL_DIR
    results_md = os.path.join(OUT_DIR, "quick", "RESULTS.md") if args.quick else os.path.join(HERE, "RESULTS.md")
    os.makedirs(model_dir, exist_ok=True)

    data: Dict[str, Dict] = {}
    for split, n in sizes.items():
        log(f"generating {split}: {n} triggered windows")
        data[split] = build_split(split, n, SEEDS[split])

    real_info: Dict[str, object] = {}
    if args.real:
        sessions = sensorlab.load(args.real)
        wins, rep = sensorlab.assign_labels(sessions, sensorlab.load_conditions(args.conditions))
        parts = session_split(wins)
        real_info = {"file": os.path.basename(args.real), "sessions": len(sessions), "labels": rep.as_dict(),
                     "split_counts": {k: len(v) for k, v in parts.items()}}
        log(f"real data: {real_info}")
        for k in ("train", "val", "test"):
            if parts[k]:
                data[f"real_{k}"] = pack(parts[k])
        if "real_train" in data:
            data["train"] = concat(data["train"], data["real_train"], args.real_repeat)

    tr, va = data["train"], data["val"]
    # Threshold/calibration split: real validation windows when there are enough real pickups.
    cal = va
    if "real_val" in data and int((data["real_val"]["labels"] == "pickup").sum()) >= 100:
        cal = data["real_val"]
        real_info["threshold_split"] = "real_val"
    elif args.real:
        real_info["threshold_split"] = "synthetic val (fewer than 100 real validation pickups)"

    ok = tr["ok"]
    Xtr, Ctr, ytr = tr["X"][ok], tr["C"][ok], (tr["labels"][ok] == "pickup").astype(int)
    okv = cal["ok"]
    Xva, Cva, yva = cal["X"][okv], cal["C"][okv], (cal["labels"][okv] == "pickup").astype(int)

    log("fitting logistic")
    logistic = md.fit_logistic(Xtr, ytr, Xva, yva)
    log("fitting gbdt")
    gbdt, _ = md.fit_gbdt(Xtr, ytr)
    log("fitting cnn")
    cnn, keras_model, cnn_info = md.fit_cnn(Ctr, ytr, Cva, yva, epochs=3 if args.quick else args.epochs)
    log(f"cnn: {cnn_info}")
    models = {"logistic": logistic, "gbdt": gbdt, "cnn": cnn}

    results: Dict[str, Dict] = {}
    docs: Dict[str, Dict] = {}
    for name, m in models.items():
        z = md.raw_logit(m, Xva, Cva)
        platt = md.fit_platt(z, yva)
        lg = md.calibrated_logit(z, platt)
        t, rule, gap = ev.choose_threshold(lg[yva == 1], lg[yva == 0], VAL_RECALL)
        res: Dict[str, object] = {"platt": platt, "threshold_logit": t, "threshold": float(md.sigmoid(t)),
                                  "threshold_rule": rule, "val_gap_logit": gap}
        for split, d in data.items():
            if split == "train" or not len(d["labels"]):
                continue
            res[split] = ev.evaluate_split(scores(m, platt, d), d["labels"], d["metas"], t)
        results[name] = res
        docs[name] = ex.model_document(m, platt, t, {
            "name": f"pickup_{name}",
            "target_recall": TARGET_RECALL,
            "threshold_rule": f"{rule}; validation recall bound {VAL_RECALL}, non-pickup quantile 0.995",
            "trained_on": ("synthetic" if not args.real else "synthetic + real sensor lab") +
                          f" (generator v{GENERATOR_VERSION}, seeds {SEEDS})",
        })
        at = res.get("val", res.get("real_val"))["at_threshold"]
        log(f"{name}: t_logit={t:.3f} ({rule}, gap {gap:.2f}) val recall={at['pickup_recall']['rate']:.4f} "
            f"fb(all)={at['all_negatives_false_break']['rate']:.4f} bump={at['bump_false_break']['rate']:.4f}")

    val_key = "real_val" if real_info.get("threshold_split") == "real_val" else "val"
    val_fb = {k: r[val_key]["at_threshold"]["all_negatives_false_break"]["rate"] for k, r in results.items()}
    chosen = select(val_fb)
    log(f"selected: {chosen} (val false-break {val_fb})")

    # ---- exports: the CNN flatbuffer always; the selected model's too when it has a TF form.
    tflite: Dict[str, Tuple[str, bytes]] = {"cnn": ("cnn", ex.export_tflite(cnn, os.path.join(model_dir, "pickup_cnn.tflite")))}
    selected_tflite = os.path.join(model_dir, "pickup_model.tflite")
    if ex.tflite_supported(models[chosen]):
        tflite["selected"] = (chosen, ex.export_tflite(models[chosen], selected_tflite))
    elif os.path.exists(selected_tflite):
        os.remove(selected_tflite)
    vec_windows, _ = synth.generate_split(20, "test_iid", VECTOR_SEED)
    vec_windows += synth.generate_split(6, "test_shift", VECTOR_SEED + 1)[0]
    base_i = next(i for i, w in enumerate(vec_windows) if float(w.meta.get("rate_hz", 50)) < 55)
    vec_windows.insert(0, vec_windows.pop(base_i))
    vectors = ex.build_vectors(docs, vec_windows, _trigger_stream(), tflite)
    checks: Dict[str, object] = {}
    for key, (kind, blob) in tflite.items():
        ref = "cnn" if key == "cnn" else chosen
        checks[f"tflite_{key}_vs_float64_max_abs_logit"] = max(
            abs(w["outputs"][f"tflite_{key}_logit"] - w["outputs"][ref]["logit"]) for w in vectors["windows"] if "outputs" in w)
        checks[f"tflite_{key}_bytes"] = len(blob)
        checks[f"tflite_{key}_model"] = kind
    # Vectorized evaluation path vs the loop-faithful reference on the same windows.
    ref_diff = 0.0
    for w in vectors["windows"]:
        if "outputs" not in w:
            continue
        ww = ex._decode_window(w["input"], "")  # noqa: SLF001
        t_c, a_c = fx.clean(ww.t_ns, ww.acc)
        grid = fx.resample(t_c, a_c, ww.trigger_ns)[None]
        F, C = fx.features_from_grid(grid), fx.channels_from_grid(grid)
        for name, m in models.items():
            ref_diff = max(ref_diff, abs(float(md.raw_logit(m, F, C)[0]) - w["outputs"][name]["logit"]))
    checks["vectorized_vs_loop_max_abs_logit"] = ref_diff
    log(f"checks: {checks}")

    for name, doc in docs.items():
        ex.dump(doc, os.path.join(model_dir, f"pickup_{name}.json"), compact=True)
    selected_doc = dict(docs[chosen])
    selected_doc["selected_from"] = {"candidates": list(models), "rule": "see classifier/train.py docstring",
                                     "val_false_break": val_fb}
    ex.dump(selected_doc, os.path.join(model_dir, "pickup_model.json"), compact=True)
    ex.dump(vectors, os.path.join(model_dir, "pickup_vectors.json"), compact=True)
    trig_stats = {k: d["stats"] for k, d in data.items() if d.get("stats")}
    summary = report.summary(results, chosen, trig_stats, checks, cnn_info, sizes, real_info)
    ex.dump(summary, os.path.join(model_dir, "pickup_metrics.json"))
    with open(results_md, "w", encoding="utf-8") as fh:
        fh.write(report.render(summary))
    log(f"wrote {model_dir} and {results_md}")

    if not args.quick and not args.no_sync:
        files = {
            os.path.join(model_dir, "pickup_model.json"): os.path.join(ex.ASSET_DIR, "pickup_model.json"),
            os.path.join(model_dir, "pickup_vectors.json"): os.path.join(ex.TEST_RES_DIR, "pickup_vectors.json"),
        }
        for name in models:
            files[os.path.join(model_dir, f"pickup_{name}.json")] = os.path.join(ex.TEST_RES_DIR, f"pickup_{name}.json")
        stale = os.path.join(ex.ASSET_DIR, "pickup_model.tflite")
        if "selected" in tflite:
            files[selected_tflite] = stale
        elif os.path.exists(stale):
            os.remove(stale)
        for dst in ex.sync_android(files):
            log(f"synced {dst}")
    return 0


def _trigger_stream() -> Tuple[np.ndarray, np.ndarray]:
    """A deterministic stream with rest, a knock, a pickup and noise for the trigger vectors."""
    rng = np.random.default_rng(VECTOR_SEED)
    t = np.arange(0, 12_000_000_000, 20_000_000, dtype=np.int64) + 5_000_000_000
    n = len(t)
    ramp = np.clip((np.arange(n) - 300) / 50.0, 0, 1)  # pickup from 6 s: rotate 150 deg over 1 s
    ang = ramp * math.radians(150)
    acc = np.zeros((n, 3))
    acc[:, 0] = synth.G * np.sin(ang)
    acc[:, 2] = -synth.G * np.cos(ang)
    acc += rng.normal(0, 0.02, (n, 3))
    acc[150, 2] += 6.0  # knock at 3 s
    acc[151, 2] -= 4.0
    t[400] = t[399]  # duplicate timestamp: ignored by the trigger
    return t, acc.astype(np.float32)


if __name__ == "__main__":
    sys.exit(main())
