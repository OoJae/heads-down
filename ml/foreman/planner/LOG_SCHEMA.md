# Shift Planner log schema (v1)

The planner learns from one thing: a log the app writes about **its own phone**. The log stays
on the device. It is never uploaded, never sent to the crank, and is deleted with the app's
data. It needs no UsageStats permission.

The format is JSON Lines: one object per line, appended. Each object has three required fields:

| Field | Type | Meaning |
|---|---|---|
| `ts` | integer | epoch milliseconds, UTC |
| `tz` | integer | the UTC offset in minutes at that instant: `TimeZone.getDefault().getOffset(ts) / 60000` (60 in Lagos), from −840 to 840 |
| `type` | string | one of the event types below |

Readers skip any line they cannot parse, any unknown `type` and any unknown field. Both the
Python reference (`logs.py`) and Kotlin (`PlannerEvent.parse`) do this, so the schema can grow
without breaking older apps.

## Event types

| `type` | Write it when | Extra fields |
|---|---|---|
| `monitor_start` | the app starts observing (the shift service starts, or a lightweight rhythm observer is alive). **Write the current screen state right after it** (`screen_on` or `screen_off` from `PowerManager.isInteractive`) | |
| `monitor_stop` | the app stops observing (service stopped, or killed: write it on the next start, stamped with the last known alive time) | |
| `screen_on` / `screen_off` | `ACTION_SCREEN_ON` / `ACTION_SCREEN_OFF` | |
| `user_present` | `ACTION_USER_PRESENT` (unlock) | |
| `power_connected` / `power_disconnected` | the charger state changes while observing | |
| `face_down_start` / `face_down_end` | FaceDownDetector verdict changes | |
| `alarm_next` | when planning, and on `ACTION_NEXT_ALARM_CLOCK_CHANGED` | `alarm_ts` (epoch ms) from `AlarmManager.getNextAlarmClock()`; omit it when no alarm is set |
| `shift_armed` | a shift is armed | `shift_id` |
| `shift_ended` | a shift ends | `shift_id`, `reason` (INTERFACE.md `break_reason`: 0 completed, 1 pickup, 2 screen_on, 3 freeze, 4 lease_lapse, 5 budget, 6 manual, 7 unplugged, 8 unlocked) |

Example:

```json
{"ts":1790006400000,"tz":60,"type":"monitor_start"}
{"ts":1790006400010,"tz":60,"type":"screen_off"}
{"ts":1790010000000,"tz":60,"type":"alarm_next","alarm_ts":1790034300000}
{"ts":1790034320000,"tz":60,"type":"screen_on"}
{"ts":1790034321000,"tz":60,"type":"user_present"}
{"ts":1790034400000,"tz":60,"type":"shift_ended","shift_id":7,"reason":0}
```

## From events to slot labels

Events are ordered by `ts`, then by log order. Between two consecutive events, the state after
the earlier one holds, in that event's local time (`ts + tz` minutes). The planner works in
15-minute local slots (96 a day; the day of the week is computed from the local day, with
Monday = 0).

* **Observed time** is time inside `monitor_start`…`monitor_stop` with a known screen state. A
  log with no monitor events counts as observed from its first event. Without a screen state
  there is no observed time.
* **IDLE**: at least 7.5 minutes observed, the screen off for at least 90% of them, and no
  unlock in the slot. A notification that lights the screen for a few seconds does not make a
  slot busy. An unlock always does.
* **BUSY**: observed for 7.5 minutes or more, but not idle.
* **MISSING**: observed for less than 7.5 minutes. The model skips these slots; it does not
  count them as busy. That is why an app that only watches during shifts still learns
  correctly (planner/RESULTS.md, "Robustness").

`power_*`, `face_down_*` and `shift_*` events are kept for the Sunday rhythm report and future
models. Planner v1 does not use them. `alarm_next` ends the proposed window at the alarm.

## Size and retention

A typical day is about 150–250 events, roughly 12 KB. With the shipped 56-day half-life, a day
gets half the weight after 8 weeks and a quarter after 16. Everything older than 26 weeks holds
about a tenth of the total weight. Keeping **26 weeks** (about 2 MB) and pruning older lines
changes no proposal visibly.
