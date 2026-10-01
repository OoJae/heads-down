"""Synthetic users for the planner evaluation: a minute-level 'in use' timeline per archetype,
turned into exactly the log events the app writes (LOG_SCHEMA.md).

Archetypes:
- regular: weekday bed ~23:15, alarm 06:45 (plus snooze); weekends later and no alarm; an
  occasional late night out; a night check now and then.
- shift_worker: 4 nights on / 4 off (an 8-day cycle that does NOT line up with the week): on
  nights works 21:00-07:00 and sleeps ~08:00-15:30; off days sleeps ~00:00-08:30.
- irregular: bedtime anywhere 22:00-03:30, 5-9 h of sleep, split nights, naps, all-nighters.
- second_phone: a drawer phone (the idle second Seeker): a few short uses a day, idle otherwise.
- night_checker: a regular sleeper who checks the phone 1-3 times every night between 01:00 and
  05:00.
"""
from __future__ import annotations

import math
from dataclasses import dataclass
from typing import Dict, List, Optional, Tuple

import numpy as np

from .logs import DAY_MS, Event

MIN_MS = 60_000
ARCHETYPES = ("regular", "shift_worker", "irregular", "second_phone", "night_checker")


@dataclass
class SimUser:
    archetype: str
    seed: int
    days: int
    tz: int
    start_day: int  # local day index of day 0
    events: List[Event]
    in_use: np.ndarray  # bool per minute, local time from start_day 00:00
    alarms: Dict[int, int]  # sim day -> alarm minute (local, from that day's 00:00)


def _clock(rng: np.random.Generator, mean_min: float, sd_min: float) -> float:
    return float(rng.normal(mean_min, sd_min))


def _awake_usage(rng: np.random.Generator, use: np.ndarray, a: int, b: int, mean_use: float, mean_gap: float,
                 focus_p: float = 0.15) -> None:
    """Alternating use / idle episodes while awake, with occasional long focus blocks (no use)."""
    t = a + int(rng.exponential(mean_gap / 2))
    while t < b:
        if rng.uniform() < focus_p:
            t += int(rng.uniform(30, 120))
            continue
        dur = max(1, int(rng.exponential(mean_use)))
        use[t : min(b, t + dur)] = True
        t += dur + max(1, int(rng.exponential(mean_gap)))


def simulate(archetype: str, seed: int, days: int = 70, tz: int = 60) -> SimUser:
    rng = np.random.default_rng(seed)
    start_day = 20_000 + int(rng.integers(0, 7))  # local day index (around 2024-10)
    total = (days + 1) * 1440
    use = np.zeros(total, dtype=bool)
    sleep = np.zeros(total, dtype=bool)
    alarms: Dict[int, int] = {}
    phase = int(rng.integers(0, 8))
    for d in range(days + 1):
        base = d * 1440
        dow = (start_day + d + 3) % 7
        weekend = dow >= 5
        if archetype in ("regular", "night_checker"):
            school_night = (dow + 1) % 7 < 5  # tomorrow is a workday
            bed = _clock(rng, 23 * 60 + 15, 20) if school_night else _clock(rng, 24 * 60 + 30, 40)
            if archetype == "regular" and not school_night and rng.uniform() < 0.15:
                bed = _clock(rng, 26 * 60, 30)  # late night out
            if school_night:
                alarm = 6 * 60 + 45
                alarms[d + 1] = alarm
                wake = alarm + rng.uniform(0, 15)
            else:
                wake = _clock(rng, 8 * 60 + 45, 45)
            spans = [(bed, 1440 + wake)]
            checks = int(rng.poisson(0.25)) if archetype == "regular" else int(rng.integers(1, 4))
            lo, hi = (1440 + 60, 1440 + 300) if archetype == "night_checker" else (bed + 30, 1440 + wake - 30)
            for _ in range(checks):
                if hi > lo:
                    c = rng.uniform(lo, hi)
                    use_span = (c, c + rng.uniform(2, 8))
                    spans.append(("check",) + use_span)  # type: ignore
        elif archetype == "shift_worker":
            on = ((d + phase) % 8) < 4
            if on:
                spans = [(1440 + _clock(rng, 8 * 60, 25), 1440 + _clock(rng, 15 * 60 + 30, 30))]
            else:
                spans = [(_clock(rng, 24 * 60, 40), 1440 + _clock(rng, 8 * 60 + 30, 45))]
        elif archetype == "irregular":
            if rng.uniform() < 0.05:
                spans = []
            else:
                bed = rng.uniform(22 * 60, 27 * 60 + 30)
                dur = rng.uniform(5, 9) * 60
                spans = [(bed, bed + dur)]
                if rng.uniform() < 0.25:
                    mid = bed + dur * rng.uniform(0.3, 0.7)
                    spans.append(("check", mid, mid + rng.uniform(30, 90)))  # type: ignore
            if rng.uniform() < 0.3:
                nap = rng.uniform(14 * 60, 17 * 60)
                spans.append((nap, nap + rng.uniform(30, 90)))
        elif archetype == "second_phone":
            spans = [(0.0, 1440.0)]
            for _ in range(int(rng.integers(1, 5))):
                c = rng.uniform(8 * 60, 23 * 60)
                spans.append(("check", c, c + rng.uniform(2, 10)))  # type: ignore
        else:
            raise ValueError(archetype)
        for sp in spans:
            if sp and sp[0] == "check":
                a, b = int(base + sp[1]), int(base + sp[2])
                use[max(0, a) : min(total, b)] = True
            else:
                a, b = int(base + sp[0]), int(base + sp[1])
                sleep[max(0, a) : min(total, b)] = True
    # Awake usage everywhere that is not sleep (checks already marked).
    awake = ~sleep
    t = 0
    while t < total:
        if awake[t]:
            e = t
            while e < total and awake[e]:
                e += 1
            if archetype == "second_phone":
                pass  # the drawer phone is only touched for the explicit checks
            else:
                _awake_usage(rng, use, t, e, mean_use=6.0, mean_gap=14.0)
            t = e
        else:
            t += 1
    events = _events(rng, archetype, start_day, tz, use, sleep, alarms, days)
    return SimUser(archetype, seed, days, tz, start_day, events, use[: days * 1440], alarms)


def _events(rng, archetype, start_day, tz, use, sleep, alarms, days) -> List[Event]:
    t0 = start_day * DAY_MS - tz * MIN_MS  # UTC ms of local day 0, 00:00
    ev: List[Tuple[int, str, Tuple]] = []

    def at(minute: float, typ: str, data: Tuple = ()):
        ev.append((int(t0 + minute * MIN_MS), typ, data))

    n = days * 1440
    at(0, "monitor_start")
    at(0.01, "screen_on" if use[0] else "screen_off")
    # Use episodes: screen on, unlock, screen off.
    m = 1
    prev = bool(use[0])
    while m < n:
        cur = bool(use[m])
        if cur and not prev:
            at(m + rng.uniform(0, 0.5), "screen_on")
            at(m + rng.uniform(0.55, 0.9), "user_present")
        elif prev and not cur:
            at(m + rng.uniform(0, 0.5), "screen_off")
        prev = cur
        m += 1
    # Notification wake-ups while not in use (a few seconds of screen, no unlock).
    for d in range(days):
        k = int(rng.poisson(8))
        for _ in range(k):
            mm = d * 1440 + rng.uniform(0, 1440)
            if not use[int(mm)] and (int(mm) + 1 >= n or not use[int(mm) + 1]):
                at(mm, "screen_on")
                at(mm + rng.uniform(5, 15) / 60.0, "screen_off")
    # Charging and face-down around the main sleep, the next alarm in the evening.
    s = 0
    while s < n:
        if sleep[s]:
            e = s
            while e < n and sleep[e]:
                e += 1
            if e - s >= 180:
                if rng.uniform() < 0.85:
                    at(s - rng.uniform(1, 10), "power_connected")
                    at(e + rng.uniform(1, 20), "power_disconnected")
                if rng.uniform() < 0.6:
                    at(s - rng.uniform(0, 5), "face_down_start")
                    at(e + rng.uniform(0, 2), "face_down_end")
            s = e
        else:
            s += 1
    # The app reads AlarmManager.getNextAlarmClock() when it plans (before 18:00) and logs it.
    for d, alarm in alarms.items():
        if 1 <= d < days:
            at((d - 1) * 1440 + 17 * 60 + rng.uniform(0, 59), "alarm_next", (("alarm_ts", int(t0 + (d * 1440 + alarm) * MIN_MS)),))
    ev.sort(key=lambda x: x[0])
    return [Event(ts, tz, typ, data) for ts, typ, data in ev]


def population(seed0: int, per_archetype: int, days: int = 70) -> List[SimUser]:
    users = []
    for i, arch in enumerate(ARCHETYPES):
        for j in range(per_archetype):
            users.append(simulate(arch, seed0 + 1000 * i + j, days))
    return users
