"""Score the SHIPPED pickup classifier on a real sensor-lab export. Nothing is trained or written
into model/: this answers "what does the model in the app do on recordings from the phone?".

    python -m classifier.score_real EXPORT.csv [--conditions CONDITIONS.csv] [--json OUT.json]

The export is labelled by the same rules as training (`sensorlab.assign_labels`, MODEL_CARD.md
"Recording protocol"), each window goes through the same features and the same single-window
predictor the Kotlin runtime mirrors (`models.predict_json`), and a window that fails the quality
check is a PICKUP verdict, as on the phone. Rates carry 95% Wilson intervals: with 50 pickups and
none missed the interval's floor is 93%, and the report says so instead of "100%".
"""
from __future__ import annotations

import argparse
import json
import os
import sys
from typing import Dict, List, Optional, Sequence

import numpy as np

from . import evaluate as ev
from . import features as fx
from . import models as md
from . import sensorlab
from .trigger import Window

HERE = os.path.dirname(os.path.abspath(__file__))
SHIPPED = os.path.join(HERE, "model", "pickup_model.json")


def score(doc: Dict, windows: Sequence[Window]) -> Dict[str, object]:
    """Verdicts of the model document [doc] on labelled [windows], with rates per label."""
    labels = np.array([w.label for w in windows])
    grids, reasons = fx.prepare(list(windows))
    ok = np.array([r is None for r in reasons], dtype=bool)
    logits = np.full(len(windows), np.inf)  # fail-closed: an unusable window is a PICKUP
    if ok.any():
        feats = fx.features_from_grid(grids[ok])
        chans = fx.channels_from_grid(grids[ok])
        logits[ok] = [md.predict_json(doc, feats[i], chans[i])[1] for i in range(len(feats))]
    t = float(doc["threshold_logit"])
    fired = logits >= t
    misses = [
        {"session": str(windows[i].meta.get("session_id")), "label": str(labels[i]), "logit": round(float(logits[i]), 3)}
        for i in np.flatnonzero((labels == "pickup") & ~fired)
    ]
    false_breaks = [
        {"session": str(windows[i].meta.get("session_id")), "label": str(labels[i]),
         "logit": (None if not np.isfinite(logits[i]) else round(float(logits[i]), 3)),
         "failed_quality_check": (None if ok[i] else str(reasons[i]))}
        for i in np.flatnonzero((labels != "pickup") & fired)
    ]
    return {
        "model": doc.get("name"),
        "trained_on": doc.get("trained_on"),
        "threshold_logit": t,
        "windows": int(len(windows)),
        "failed_quality_check": int((~ok).sum()),
        "rates": ev.rates_at(logits, labels, t),
        "missed_pickups": misses,
        "false_breaks": false_breaks,
    }


def _pct(r: Dict[str, object]) -> str:
    if not r["n"]:
        return "no windows"
    lo, hi = r["ci95"]
    return f"{r['k']}/{r['n']} = {100 * r['rate']:.1f}%  (95% interval {100 * lo:.1f}% to {100 * hi:.1f}%)"


def render(result: Dict[str, object], labelled: Dict[str, object]) -> str:
    rates = result["rates"]
    lines = [
        f"model            {result['model']} (trained on: {result['trained_on']})",
        f"export           {labelled['sessions']} sessions, {labelled['windows']} labelled windows: {labelled['per_label']}",
        f"quality check    {result['failed_quality_check']} windows failed it (each is a PICKUP verdict, as on the phone)",
        "",
        f"pickups caught   {_pct(rates['pickup_recall'])}",
    ]
    for name in ("bump", "slide", "set_down"):
        key = f"{name}_false_break"
        if key in rates:
            lines.append(f"{name + ' taken for a pickup':<28} {_pct(rates[key])}")
    lines.append(f"{'any non-pickup taken for one':<28} {_pct(rates['all_negatives_false_break'])}")
    if result["missed_pickups"]:
        lines += ["", "missed pickups:"] + [f"  {m}" for m in result["missed_pickups"]]
    if result["false_breaks"]:
        lines += ["", "non-pickups taken for a pickup:"] + [f"  {m}" for m in result["false_breaks"]]
    return "\n".join(lines)


def main(argv: Optional[List[str]] = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("path", help="sensor lab export CSV")
    ap.add_argument("--conditions", help="optional sidecar CSV: session_id,surface,position,charger,case")
    ap.add_argument("--model", default=SHIPPED, help="model document to score (default: the shipped one)")
    ap.add_argument("--json", help="also write the full result here")
    args = ap.parse_args(argv)
    with open(args.model, encoding="utf-8") as fh:
        doc = json.load(fh)
    windows, report = sensorlab.assign_labels(sensorlab.load(args.path), sensorlab.load_conditions(args.conditions))
    labelled = sensorlab.describe(windows, report)
    if not windows:
        print("no labelled window in this export: " + json.dumps(labelled), file=sys.stderr)
        return 1
    result = score(doc, windows)
    print(render(result, labelled))
    if args.json:
        with open(args.json, "w", encoding="utf-8") as fh:
            json.dump({"labelled": labelled, **result}, fh, indent=1)
    return 0


if __name__ == "__main__":
    sys.exit(main())
