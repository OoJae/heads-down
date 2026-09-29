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
}

data class RevealPlan(val triggerAtWallMillis: Long, val source: RevealSource)

/**
 * Chooses when the morning haul reveal fires. Pure (wall-clock millis + [ZoneId]).
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

    fun plan(
        nowWallMillis: Long,
        nextSystemAlarmWallMillis: Long?,
        zone: ZoneId,
        fallback: LocalTime = DEFAULT_FALLBACK,
        offsetAfterAlarmMillis: Long = DEFAULT_OFFSET_AFTER_ALARM_MILLIS,
        minLeadMillis: Long = DEFAULT_MIN_LEAD_MILLIS,
        maxHorizonMillis: Long = DEFAULT_MAX_HORIZON_MILLIS,
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
