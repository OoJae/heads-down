package xyz.headsdown.ml.planner

import xyz.headsdown.ml.PlannerEvent
import java.util.TreeMap

/**
 * Turns planner log events into 15-minute slot labels (port of ml/foreman/planner/logs.py).
 *
 * Between consecutive events (ordered by ts, then log order) the state after the earlier event
 * holds, in that event's local time. A slot is IDLE with >= 7.5 observed minutes, the screen off
 * for >= 90% of them and no unlock; BUSY if observed but not idle; MISSING (omitted) otherwise.
 * Observation needs the app to be watching (monitor_start..monitor_stop; the whole log if it has
 * no monitor events) and a known screen state.
 */
object SlotLabels {
    const val SLOT_MS = 15 * 60 * 1000L
    const val DAY_MS = 24 * 60 * 60 * 1000L
    const val SLOTS_PER_DAY = 96
    const val MIN_OBSERVED_MS = 450_000L
    const val IDLE_SCREEN_OFF_FRACTION = 0.9

    class Stats {
        var observedMs = 0L
        var screenOffMs = 0L
        var unlocks = 0
    }

    /** Key for (local day, slot): day * 96 + slot. Sorted keys = (day, slot) order. */
    fun key(day: Long, slot: Int): Long = day * SLOTS_PER_DAY + slot

    fun dayOf(key: Long): Long = Math.floorDiv(key, SLOTS_PER_DAY.toLong())

    fun slotOf(key: Long): Int = Math.floorMod(key, SLOTS_PER_DAY.toLong()).toInt()

    /** Monday = 0; local day 0 (1970-01-01) was a Thursday. */
    fun dayOfWeek(day: Long): Int = Math.floorMod(day + 3, 7L).toInt()

    fun stats(events: List<PlannerEvent>, untilTs: Long?): TreeMap<Long, Stats> {
        val evs = events.withIndex()
            .filter { untilTs == null || it.value.ts <= untilTs }
            .sortedWith(compareBy({ it.value.ts }, { it.index }))
            .map { it.value }
        val hasMonitor = evs.any { it.type == PlannerEvent.Type.MONITOR_START || it.type == PlannerEvent.Type.MONITOR_STOP }
        var monitoring = !hasMonitor
        var screenOn: Boolean? = null
        val out = TreeMap<Long, Stats>()
        for ((i, e) in evs.withIndex()) {
            when (e.type) {
                PlannerEvent.Type.MONITOR_START -> monitoring = true
                PlannerEvent.Type.MONITOR_STOP -> {
                    monitoring = false
                    screenOn = null
                }
                PlannerEvent.Type.SCREEN_ON -> screenOn = true
                PlannerEvent.Type.SCREEN_OFF -> screenOn = false
                PlannerEvent.Type.USER_PRESENT -> {
                    screenOn = true
                    val st = out.getOrPut(slotKey(e.localMillis)) { Stats() }
                    if (monitoring) st.unlocks++
                }
                else -> Unit
            }
            val endTs = if (i + 1 < evs.size) evs[i + 1].ts else untilTs ?: e.ts
            val on = screenOn
            if (!monitoring || on == null || endTs <= e.ts) continue
            accumulate(out, e.localMillis, e.localMillis + (endTs - e.ts), on)
        }
        return out
    }

    fun label(st: Stats): Int = when {
        st.observedMs < MIN_OBSERVED_MS -> MISSING
        st.unlocks == 0 && st.screenOffMs >= IDLE_SCREEN_OFF_FRACTION * st.observedMs -> IDLE
        else -> BUSY
    }

    /** key(day, slot) -> IDLE / BUSY for every slot with enough observation (sorted). */
    fun labels(events: List<PlannerEvent>, untilTs: Long?): TreeMap<Long, Int> {
        val out = TreeMap<Long, Int>()
        for ((k, st) in stats(events, untilTs)) {
            val l = label(st)
            if (l != MISSING) out[k] = l
        }
        return out
    }

    /** The latest alarm_next report at or before now, if its alarm is still ahead. */
    fun lastAlarm(events: List<PlannerEvent>, nowTs: Long): Long? {
        var best: PlannerEvent? = null
        for (e in events) {
            if (e.type == PlannerEvent.Type.ALARM_NEXT && e.ts <= nowTs && (best == null || e.ts >= best.ts)) best = e
        }
        val a = best?.alarmTs ?: return null
        return if (a > nowTs) a else null
    }

    private fun slotKey(localMs: Long): Long =
        key(Math.floorDiv(localMs, DAY_MS), (Math.floorMod(localMs, DAY_MS) / SLOT_MS).toInt())

    private fun accumulate(out: TreeMap<Long, Stats>, start: Long, end: Long, screenOn: Boolean) {
        var t = start
        while (t < end) {
            val slotStart = Math.floorDiv(t, SLOT_MS) * SLOT_MS
            val stop = minOf(end, slotStart + SLOT_MS)
            val st = out.getOrPut(slotKey(t)) { Stats() }
            st.observedMs += stop - t
            if (!screenOn) st.screenOffMs += stop - t
            t = stop
        }
    }

    const val IDLE = 1
    const val BUSY = 0
    const val MISSING = -1
}
