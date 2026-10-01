"""Planner log schema (LOG_SCHEMA.md) and the slot labeling both Python and Kotlin implement.

One JSON object per line (JSONL), written by the app on the phone and never uploaded:

    {"ts": 1790000000000, "tz": 60, "type": "screen_off"}
    {"ts": 1790000123000, "tz": 60, "type": "alarm_next", "alarm_ts": 1790025000000}
    {"ts": 1790030000000, "tz": 60, "type": "shift_ended", "shift_id": 7, "reason": 0}

`ts` is epoch milliseconds (UTC), `tz` the UTC offset in minutes at that instant (60 for WAT).
Unknown types and unknown fields are ignored, so the schema can grow.

Slot labeling (15-minute local slots): a slot is OBSERVED for the time the app was watching
(between monitor_start and monitor_stop, and after the first screen event). It is IDLE when at
least 7.5 minutes were observed, the screen was off for at least 90% of the observed time and
there was no unlock; BUSY when observed but not idle; MISSING when observed < 7.5 minutes.
A notification lighting the screen for a few seconds does not make a slot busy; an unlock does.
"""
from __future__ import annotations

import json
from dataclasses import dataclass, field
from typing import Dict, Iterable, List, Optional, Sequence, TextIO, Tuple

SLOT_MS = 15 * 60 * 1000
DAY_MS = 24 * 60 * 60 * 1000
SLOTS_PER_DAY = 96
MIN_OBSERVED_MS = 450_000  # 7.5 minutes
IDLE_SCREEN_OFF_FRACTION = 0.9

IDLE, BUSY, MISSING = 1, 0, -1

TYPES = (
    "monitor_start",
    "monitor_stop",
    "screen_on",
    "screen_off",
    "user_present",
    "power_connected",
    "power_disconnected",
    "face_down_start",
    "face_down_end",
    "alarm_next",
    "shift_armed",
    "shift_ended",
)


@dataclass(frozen=True)
class Event:
    ts: int
    tz: int
    type: str
    data: Tuple[Tuple[str, object], ...] = ()

    def get(self, key: str, default=None):
        for k, v in self.data:
            if k == key:
                return v
        return default

    @property
    def local_ms(self) -> int:
        return self.ts + self.tz * 60_000

    def to_json(self) -> str:
        obj = {"ts": self.ts, "tz": self.tz, "type": self.type}
        obj.update(dict(self.data))
        return json.dumps(obj, separators=(",", ":"))


def parse_line(line: str) -> Optional[Event]:
    line = line.strip()
    if not line:
        return None
    try:
        obj = json.loads(line)
    except json.JSONDecodeError:
        return None
    if not isinstance(obj, dict):
        return None
    ts, tz, typ = obj.get("ts"), obj.get("tz", 0), obj.get("type")
    if not isinstance(ts, int) or isinstance(ts, bool) or not isinstance(tz, int) or isinstance(tz, bool):
        return None
    if typ not in TYPES or not -14 * 60 <= tz <= 14 * 60:
        return None
    extra = tuple(sorted((k, v) for k, v in obj.items() if k not in ("ts", "tz", "type")))
    return Event(ts, tz, typ, extra)


def read_jsonl(fh: TextIO) -> List[Event]:
    return [e for e in (parse_line(l) for l in fh) if e is not None]


def write_jsonl(events: Iterable[Event], fh: TextIO) -> None:
    for e in events:
        fh.write(e.to_json() + "\n")


def local_day(local_ms: int) -> int:
    return local_ms // DAY_MS


def day_of_week(day: int) -> int:
    """Monday = 0. Day 0 (1970-01-01) was a Thursday."""
    return (day + 3) % 7


@dataclass
class SlotStats:
    observed_ms: int = 0
    screen_off_ms: int = 0
    unlocks: int = 0


def slot_stats(events: Sequence[Event], until_ts: Optional[int] = None) -> Dict[Tuple[int, int], SlotStats]:
    """(local day, slot) -> observed / screen-off milliseconds and unlock count.

    Events are ordered by (ts, original order). Between consecutive events the state after the
    earlier event holds; each interval uses the earlier event's `tz`. Intervals past `until_ts`
    are cut there. If the log has no monitor events, the app counts as watching from the first
    event on.
    """
    evs = sorted(enumerate(events), key=lambda p: (p[1].ts, p[0]))
    evs = [e for _, e in evs if until_ts is None or e.ts <= until_ts]
    has_monitor = any(e.type in ("monitor_start", "monitor_stop") for e in evs)
    monitoring = not has_monitor
    screen: Optional[bool] = None  # True = on, None = unknown
    out: Dict[Tuple[int, int], SlotStats] = {}
    for i, e in enumerate(evs):
        if e.type == "monitor_start":
            monitoring = True
        elif e.type == "monitor_stop":
            monitoring = False
            screen = None
        elif e.type == "screen_on":
            screen = True
        elif e.type == "screen_off":
            screen = False
        elif e.type == "user_present":
            screen = True
            st = out.setdefault(_slot_key(e.local_ms), SlotStats())
            if monitoring:
                st.unlocks += 1
        end_ts = evs[i + 1].ts if i + 1 < len(evs) else (until_ts if until_ts is not None else e.ts)
        if not monitoring or screen is None or end_ts <= e.ts:
            continue
        _accumulate(out, e.local_ms, e.local_ms + (end_ts - e.ts), screen)
    return out


def _slot_key(local_ms: int) -> Tuple[int, int]:
    return local_ms // DAY_MS, (local_ms % DAY_MS) // SLOT_MS


def _accumulate(out: Dict[Tuple[int, int], SlotStats], start: int, end: int, screen_on: bool) -> None:
    t = start
    while t < end:
        slot_start = (t // SLOT_MS) * SLOT_MS
        stop = min(end, slot_start + SLOT_MS)
        st = out.setdefault(_slot_key(t), SlotStats())
        st.observed_ms += stop - t
        if not screen_on:
            st.screen_off_ms += stop - t
        t = stop


def label(st: SlotStats) -> int:
    if st.observed_ms < MIN_OBSERVED_MS:
        return MISSING
    if st.unlocks == 0 and st.screen_off_ms >= IDLE_SCREEN_OFF_FRACTION * st.observed_ms:
        return IDLE
    return BUSY


def slot_labels(events: Sequence[Event], until_ts: Optional[int] = None) -> Dict[Tuple[int, int], int]:
    """(local day, slot) -> IDLE / BUSY for every slot with enough observation (MISSING omitted)."""
    out = {}
    for key, st in slot_stats(events, until_ts).items():
        lab = label(st)
        if lab != MISSING:
            out[key] = lab
    return out


def last_alarm(events: Sequence[Event], now_ts: int) -> Optional[int]:
    """The most recent alarm_next report at or before now: its alarm_ts if still in the future."""
    best: Optional[Event] = None
    for e in events:
        if e.type == "alarm_next" and e.ts <= now_ts and (best is None or e.ts >= best.ts):
            best = e
    if best is None:
        return None
    a = best.get("alarm_ts")
    if isinstance(a, int) and not isinstance(a, bool) and a > now_ts:
        return a
    return None
