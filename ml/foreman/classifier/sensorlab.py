"""Loader (and writer) for the debug sensor lab's CSVs.

Formats (android/feature/shift/src/debug/.../devlog/SensorLabFiles.kt, SensorLogCsv v1):
- one session:  `row_type,window_id,t_ns,recv_ns,ax,ay,az,event`, `#` metadata lines first;
- the export:   `session_id,label,` + the same columns, every session concatenated.

Row types: `sample` (t_ns = SensorEvent.timestamp, recv_ns = elapsedRealtimeNanos at delivery),
`window_start` / `window_end` (t_ns = the window's first trigger), `event` (screen_on,
screen_off, unlock, session_start, session_end; t_ns = elapsedRealtimeNanos).

A session carries ONE label (what the founder did), but a session also contains the motions
around it (putting the phone down between pickups, picking it up to stop). `assign_labels`
applies the documented rules in MODEL_CARD.md ("Recording protocol") and reports every drop.
"""
from __future__ import annotations

import csv
import io
from collections import Counter, OrderedDict
from dataclasses import dataclass, field
from typing import Dict, Iterable, List, Optional, Sequence, TextIO, Tuple

import numpy as np

from . import features as fx
from .trigger import POST_NS, PRE_NS, Window

EXPORT_COLUMNS = "session_id,label,row_type,window_id,t_ns,recv_ns,ax,ay,az,event"
SESSION_COLUMNS = "row_type,window_id,t_ns,recv_ns,ax,ay,az,event"
LABELS = ("bump", "pickup", "slide", "set_down", "unlabeled")

# Posture rules (degrees, m/s^2): "face-down and still" at the start / end of a window.
FACE_DOWN_TILT = 35.0
STILL_MOTION = 0.25
USER_EVENT_WINDOW_NS = 5_000_000_000


@dataclass
class RawWindow:
    session_id: str
    session_label: str
    window_id: int
    trigger_ns: int
    t_ns: List[int] = field(default_factory=list)
    recv_ns: List[int] = field(default_factory=list)
    acc: List[Tuple[float, float, float]] = field(default_factory=list)


@dataclass
class Session:
    session_id: str
    label: str
    windows: "OrderedDict[int, RawWindow]"
    events: List[Tuple[int, str]]  # (elapsedRealtimeNanos, name)
    meta: Dict[str, str]


def _parse_meta(line: str) -> Dict[str, str]:
    out: Dict[str, str] = {}
    for tok in line.lstrip("#").split():
        if "=" in tok:
            k, v = tok.split("=", 1)
            out[k.strip()] = v.strip()
    return out


def read_sessions(fh: TextIO, default_session: str = "session") -> List[Session]:
    """Parses one session file or an export. Unknown row types and malformed rows are skipped."""
    sessions: "OrderedDict[str, Session]" = OrderedDict()
    session_meta: Dict[str, Dict[str, str]] = {}
    export = None
    single_meta: Dict[str, str] = {}
    for raw in fh:
        line = raw.rstrip("\r\n")
        if not line.strip():
            continue
        if line.startswith("#"):
            meta = _parse_meta(line)
            if "session" in meta:
                session_meta.setdefault(meta["session"], {}).update(meta)
            single_meta.update(meta)
            continue
        if line == EXPORT_COLUMNS:
            export = True
            continue
        if line == SESSION_COLUMNS:
            export = False
            continue
        cols = line.split(",")
        if export is None:
            export = len(cols) == 10
        if export:
            if len(cols) != 10:
                continue
            sid, label, row = cols[0], cols[1], cols[2:]
        else:
            if len(cols) != 8:
                continue
            sid = single_meta.get("session", default_session)
            label = single_meta.get("label", single_meta.get("intended_label", "unlabeled"))
            row = cols
        if label not in LABELS:
            label = "unlabeled"
        s = sessions.get(sid)
        if s is None:
            s = Session(sid, label, OrderedDict(), [], session_meta.get(sid, {}))
            sessions[sid] = s
        kind, wid, t_ns, recv_ns, ax, ay, az, event = row
        try:
            if kind == "window_start":
                s.windows[int(wid)] = RawWindow(sid, label, int(wid), int(t_ns))
            elif kind == "sample":
                w = s.windows.get(int(wid))
                if w is None:
                    continue
                w.t_ns.append(int(t_ns))
                w.recv_ns.append(int(recv_ns) if recv_ns else int(t_ns))
                w.acc.append((float(ax), float(ay), float(az)))
            elif kind == "event":
                s.events.append((int(t_ns), event))
        except ValueError:
            continue
    return list(sessions.values())


def load(path: str) -> List[Session]:
    with open(path, "r", encoding="utf-8") as fh:
        return read_sessions(fh)


def load_conditions(path: Optional[str]) -> Dict[str, Dict[str, str]]:
    """Optional sidecar CSV: session_id,surface,position,charger,case,notes (any subset)."""
    if not path:
        return {}
    with open(path, "r", encoding="utf-8") as fh:
        return {row["session_id"]: dict(row) for row in csv.DictReader(fh) if row.get("session_id")}


@dataclass
class LabelReport:
    kept: Counter = field(default_factory=Counter)
    dropped: Counter = field(default_factory=Counter)
    relabeled: Counter = field(default_factory=Counter)

    def as_dict(self) -> Dict[str, Dict[str, int]]:
        return {"kept": dict(self.kept), "dropped": dict(self.dropped), "relabeled": dict(self.relabeled)}


def _posture(win: Window) -> Tuple[Optional[str], Optional[np.ndarray]]:
    t, a = fx.clean(win.t_ns, win.acc)
    reason = fx.quality(t, win.trigger_ns) if len(t) else "too_few_samples"
    if reason is not None:
        return reason, None
    grid = fx.resample(t, a, win.trigger_ns)[None]
    return None, fx.features_from_grid(grid)[0]


def assign_labels(
    sessions: Sequence[Session], conditions: Optional[Dict[str, Dict[str, str]]] = None
) -> Tuple[List[Window], LabelReport]:
    """Canonical windows with cleaned labels. Rules (MODEL_CARD.md, "Recording protocol"):

    1. Unlabeled sessions are skipped. A window needs samples to trigger + 3 s and must pass
       the feature quality check (>= 100 samples, no gap > 0.5 s), else it is dropped.
    2. Posture: `start_down` = pre_tilt < 35 deg and pre_motion < 0.25; `end_down` = tilt_end <
       35 deg and tail_motion < 0.25.
    3. pickup session: start_down -> pickup; not start_down and end_down -> set_down (putting it
       back between reps); else dropped (ambiguous).
       set_down session: not start_down and end_down -> set_down; start_down and not end_down ->
       pickup (picking it up for the next rep); else dropped.
       bump / slide session: a window followed by screen_on or unlock within 5 s of its trigger
       is dropped (the founder ending the session); start_down and end_down -> the session
       label; else dropped (ambiguous, never guessed).
    """
    conditions = conditions or {}
    out: List[Window] = []
    rep = LabelReport()
    idx = {"tilt": fx.FEATURE_NAMES.index("pre_tilt"), "pre": fx.FEATURE_NAMES.index("pre_motion"),
           "end": fx.FEATURE_NAMES.index("tilt_end"), "tail": fx.FEATURE_NAMES.index("tail_motion")}
    for s in sessions:
        if s.label == "unlabeled":
            rep.dropped["unlabeled_session"] += len(s.windows)
            continue
        user_events = [t for t, e in s.events if e in ("screen_on", "unlock")]
        for w in s.windows.values():
            if not w.t_ns:
                rep.dropped["empty"] += 1
                continue
            t = np.asarray(w.t_ns, dtype=np.int64)
            a = np.asarray(w.acc, dtype=np.float32)
            if t.max() < w.trigger_ns + POST_NS - 40_000_000:
                rep.dropped["truncated"] += 1
                continue
            lo = int(np.searchsorted(t, w.trigger_ns - PRE_NS, side="left"))
            hi = int(np.searchsorted(t, w.trigger_ns + POST_NS, side="left"))
            t_c, a_c = t[lo : hi + 1], a[lo : hi + 1]
            win = Window(t_c, a_c, w.trigger_ns, s.label, {"session_id": s.session_id, "window_id": w.window_id,
                                                          "session_label": s.label, **conditions.get(s.session_id, {})})
            reason, f = _posture(win)
            if reason is not None or f is None:
                rep.dropped[f"quality_{reason}"] += 1
                continue
            start_down = f[idx["tilt"]] < FACE_DOWN_TILT and f[idx["pre"]] < STILL_MOTION
            end_down = f[idx["end"]] < FACE_DOWN_TILT and f[idx["tail"]] < STILL_MOTION
            label: Optional[str] = None
            if s.label == "pickup":
                label = "pickup" if start_down else ("set_down" if end_down else None)
            elif s.label == "set_down":
                if not start_down and end_down:
                    label = "set_down"
                elif start_down and not end_down:
                    label = "pickup"
            else:  # bump, slide
                offset = int(np.median(np.asarray(w.recv_ns[lo : hi + 1]) - t_c)) if w.recv_ns else 0
                trig_elapsed = w.trigger_ns + offset
                if any(0 <= e - trig_elapsed <= USER_EVENT_WINDOW_NS for e in user_events):
                    rep.dropped["ended_by_user"] += 1
                    continue
                label = s.label if (start_down and end_down) else None
            if label is None:
                rep.dropped[f"ambiguous_{s.label}"] += 1
                continue
            if label != s.label:
                rep.relabeled[f"{s.label}->{label}"] += 1
            win.label = label
            win.meta["rule"] = "session" if label == s.label else f"posture:{s.label}->{label}"
            out.append(win)
            rep.kept[label] += 1
    return out, rep


# ----------------------------------------------------------------------------- summary


def describe(windows: Sequence[Window], report: LabelReport) -> Dict[str, object]:
    """Counts per label (and per surface when a conditions sidecar was given)."""
    per_label = Counter(w.label for w in windows)
    per_surface = Counter((str(w.meta.get("surface", "unknown")), w.label) for w in windows)
    sessions = Counter(str(w.meta.get("session_id")) for w in windows)
    return {"windows": len(windows), "per_label": dict(per_label), "sessions": len(sessions),
            "per_surface_label": {f"{s}/{l}": n for (s, l), n in sorted(per_surface.items())}, **report.as_dict()}


# ----------------------------------------------------------------------------- writer (synthetic sample)


def _f5(v: float) -> str:
    return "%.5f" % float(v)


def write_export(sessions: Sequence[Tuple[str, str, Sequence[Window], Sequence[Tuple[int, str]]]], fh: TextIO,
                 note: str = "") -> None:
    """Writes the sensor lab export format. `sessions` = (session_id, label, windows, events)."""
    fh.write(f"# heads-down sensorlab export v1: {len(sessions)} sessions\n")
    if note:
        fh.write(f"# {note}\n")
    for sid, label, wins, events in sessions:
        fh.write(f"# session={sid} label={label} windows={len(wins)} events={len(events)}\n")
    fh.write(EXPORT_COLUMNS + "\n")
    for sid, label, wins, events in sessions:
        for i, w in enumerate(wins, start=1):
            fh.write(f"{sid},{label},window_start,{i},{w.trigger_ns},,,,,\n")
            for t, (x, y, z) in zip(w.t_ns, w.acc):
                fh.write(f"{sid},{label},sample,{i},{int(t)},{int(t) + 3_000_000},{_f5(x)},{_f5(y)},{_f5(z)},\n")
            fh.write(f"{sid},{label},window_end,{i},{int(w.t_ns[-1])},,,,,\n")
        for t, name in events:
            fh.write(f"{sid},{label},event,,{t},{t},,,,{name}\n")


def _posture_ok(w: Window, start_down: bool, end_down: bool) -> bool:
    reason, f = _posture(w)
    if reason is not None or f is None:
        return False
    names = fx.FEATURE_NAMES
    sd = f[names.index("pre_tilt")] < FACE_DOWN_TILT and f[names.index("pre_motion")] < STILL_MOTION
    ed = f[names.index("tilt_end")] < FACE_DOWN_TILT and f[names.index("tail_motion")] < STILL_MOTION
    return sd == start_down and ed == end_down


def synthetic_sessions(seed: int = 7, per_session: int = 1):
    """A small SYNTHETIC export laid out like a real recording session (for tests and the retrain
    smoke run; never mixed into real data): pickup reps with the set-downs between them, a bump
    session ended by picking the phone up and unlocking it, and an unlabeled session."""
    import numpy as np

    from . import synth

    rng = np.random.default_rng(seed)

    def draw(label: str, start_down: bool, end_down: bool) -> Window:
        while True:
            w, _ = synth.generate_window(rng, label, "train")
            if w is not None and _posture_ok(w, start_down, end_down):
                return w

    def chain(wins: List[Window]) -> List[Window]:
        """Re-times windows one after another, 20 s apart, like one continuous session."""
        out, t0 = [], 10**12
        for w in wins:
            shift = t0 - int(w.t_ns[0])
            out.append(Window(w.t_ns + shift, w.acc, w.trigger_ns + shift, w.label, dict(w.meta)))
            t0 = int(out[-1].t_ns[-1]) + 20 * 10**9
        return out

    pickup = chain([w for _ in range(per_session) for w in (draw("pickup", True, False), draw("set_down", False, True))])
    bumps = chain([draw("bump", True, True) for _ in range(per_session)] + [draw("pickup", True, False)])
    unlock_at = bumps[-1].trigger_ns + 3_000_000 + 2 * 10**9
    unlabeled = chain([draw("slide", True, True)])
    return [
        ("synthetic-pickup-01", "pickup", pickup, []),
        ("synthetic-bump-01", "bump", bumps, [(unlock_at - 1_000_000_000, "screen_on"), (unlock_at, "unlock")]),
        ("synthetic-unlabeled-01", "unlabeled", unlabeled, []),
    ]


def main(argv: Optional[Sequence[str]] = None) -> int:
    import argparse

    ap = argparse.ArgumentParser(description="Sensor lab export tools.")
    sub = ap.add_subparsers(dest="cmd", required=True)
    mk = sub.add_parser("make-sample", help="write the small SYNTHETIC sample export")
    mk.add_argument("path")
    ins = sub.add_parser("inspect", help="label a real export and print what the rules kept and dropped")
    ins.add_argument("path")
    ins.add_argument("--conditions")
    args = ap.parse_args(argv)
    if args.cmd == "make-sample":
        with open(args.path, "w", encoding="utf-8") as fh:
            write_export(synthetic_sessions(), fh, note="SYNTHETIC SAMPLE generated by ml/foreman/classifier/sensorlab.py; not a real recording")
        return 0
    wins, rep = assign_labels(load(args.path), load_conditions(args.conditions))
    import json

    print(json.dumps(describe(wins, rep), indent=1))
    return 0


if __name__ == "__main__":
    import sys

    sys.exit(main())
