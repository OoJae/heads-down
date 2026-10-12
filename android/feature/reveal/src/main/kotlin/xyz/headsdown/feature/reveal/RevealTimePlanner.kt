package xyz.headsdown.feature.reveal

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

enum class RevealSource {
    /** Aligned to `AlarmManager.getNextAlarmClock()`: the user's own wake-up alarm. */
    SYSTEM_ALARM,

    /** No usable system alarm: the next occurrence of a fixed local time. */
    FALLBACK,

    /** Either of the above fell inside the shift's window: just after the window's end instead. */
    WINDOW_END,
}

data class RevealPlan(val triggerAtWallMillis: Long, val source: RevealSource)

/**
 * Chooses when the morning haul reveal fires. Pure (wall-clock millis + [ZoneId]).
 *
 * The reveal turns the screen on, and inside the shift's plan window a screen that is on is a
 * BREAK: the night does not count and a Focus Bond on it is forfeit. The window is not always
 * the one this planner would guess (with no usable alarm it is the policy's length from
 * clock-in, which can run past the fallback time), so the caller passes the earliest moment
 * the reveal may fire and nothing here fires before it.
 *
 * The reveal is scheduled with `setExactAndAllowWhileIdle`, **not** `setAlarmClock`: an
 * alarm-clock alarm would itself become `getNextAlarmClock()` and the app would end up
 * aligning to its own alarm instead of the user's.
 */
object RevealTimePlanner {
    val DEFAULT_FALLBACK: LocalTime = LocalTime.of(7, 0)

    /** Fire just after the user's alarm, so the alarm UI wins and the reveal follows it. */
    const val DEFAULT_OFFSET_AFTER_ALARM_MILLIS = 60_000L

    /** Ignore alarms that are too close to be a "morning" (e.g. a nap timer about to ring). */
    const val DEFAULT_MIN_LEAD_MILLIS = 15 * 60_000L

    /** Ignore alarms too far out to belong to tonight's shift (e.g. the day after tomorrow). */
    const val DEFAULT_MAX_HORIZON_MILLIS = 20 * 60 * 60_000L

    /** Never fire sooner than this after the shift's window ends: until then a pickup still breaks it. */
    const val AFTER_WINDOW_END_MILLIS = 60_000L

    /** The earliest wall time a reveal may fire for a plan window ending at [windowEndUnix] (unix seconds). */
    fun notBefore(windowEndUnix: Long?): Long? = windowEndUnix?.let { it * 1000 + AFTER_WINDOW_END_MILLIS }

    /** [notBeforeWallMillis] is [notBefore] of the armed shift's window, or null when no window is known. */
    fun plan(
        nowWallMillis: Long,
        nextSystemAlarmWallMillis: Long?,
        zone: ZoneId,
        notBeforeWallMillis: Long? = null,
        fallback: LocalTime = DEFAULT_FALLBACK,
        offsetAfterAlarmMillis: Long = DEFAULT_OFFSET_AFTER_ALARM_MILLIS,
        minLeadMillis: Long = DEFAULT_MIN_LEAD_MILLIS,
        maxHorizonMillis: Long = DEFAULT_MAX_HORIZON_MILLIS,
    ): RevealPlan {
        val plan = alarmOrFallback(
            nowWallMillis, nextSystemAlarmWallMillis, zone, fallback, offsetAfterAlarmMillis, minLeadMillis, maxHorizonMillis,
        )
        if (notBeforeWallMillis == null || plan.triggerAtWallMillis >= notBeforeWallMillis) return plan
        return RevealPlan(notBeforeWallMillis, RevealSource.WINDOW_END)
    }

    /** The user's alarm or the fallback time, whatever the shift's window is. */
    private fun alarmOrFallback(
        nowWallMillis: Long,
        nextSystemAlarmWallMillis: Long?,
        zone: ZoneId,
        fallback: LocalTime,
        offsetAfterAlarmMillis: Long,
        minLeadMillis: Long,
        maxHorizonMillis: Long,
    ): RevealPlan {
        if (nextSystemAlarmWallMillis != null) {
            val lead = nextSystemAlarmWallMillis - nowWallMillis
            if (lead in minLeadMillis..maxHorizonMillis) {
                return RevealPlan(nextSystemAlarmWallMillis + offsetAfterAlarmMillis, RevealSource.SYSTEM_ALARM)
            }
        }
        val now = Instant.ofEpochMilli(nowWallMillis).atZone(zone)
        var candidate: ZonedDateTime = ZonedDateTime.of(now.toLocalDate(), fallback, zone)
        while (candidate.toInstant().toEpochMilli() - nowWallMillis < minLeadMillis) {
            // ZonedDateTime.of resolves DST gaps forward and overlaps to the earlier offset.
            candidate = ZonedDateTime.of(candidate.toLocalDate().plusDays(1), fallback, zone)
        }
        return RevealPlan(candidate.toInstant().toEpochMilli(), RevealSource.FALLBACK)
    }
}
