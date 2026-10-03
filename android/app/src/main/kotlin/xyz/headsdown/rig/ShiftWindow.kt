package xyz.headsdown.rig

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * How long tonight's shift runs.
 *
 * Inside the plan window a pickup is a BREAK: the program seals the shift with that reason, it
 * does not count for the streak and a Focus Bond on it is forfeit. A fixed length from clock-in
 * would do that to every full night that ends with the user's alarm ringing before the window
 * does: the alarm turns the screen on, and the user picks the phone up. So a Night Shift ends
 * shortly before the user's own next alarm when there is one for the morning; after that the
 * phone sends no BREAK (`ShiftSpec.breakStillUseful`) and the shift seals `completed`. With no
 * usable alarm it runs for the policy's length.
 */
object ShiftWindow {
    /** The window ends this long before the alarm, which itself wakes the screen. */
    const val BEFORE_ALARM_SECONDS = 120L

    /** An alarm about to ring is not "the morning"; the policy's length applies instead. */
    const val MIN_SECONDS = 30 * 60L

    /** Nor is an alarm more than this far out (the day after tomorrow's, say). */
    const val MAX_SECONDS = 14 * 3600L

    /** The usable alarm's wall time, or null when the policy's length applies. */
    fun alarmFor(policy: ClockInPolicy, nowWallMillis: Long, nextAlarmWallMillis: Long?): Long? {
        if (policy.day || nextAlarmWallMillis == null) return null
        val window = (nextAlarmWallMillis - nowWallMillis) / 1000 - BEFORE_ALARM_SECONDS
        return nextAlarmWallMillis.takeIf { window in MIN_SECONDS..MAX_SECONDS }
    }

    /** Seconds from now the plan window lasts. */
    fun seconds(policy: ClockInPolicy, nowWallMillis: Long, nextAlarmWallMillis: Long?): Long {
        val alarm = alarmFor(policy, nowWallMillis, nextAlarmWallMillis) ?: return policy.windowSeconds
        return (alarm - nowWallMillis) / 1000 - BEFORE_ALARM_SECONDS
    }

    /** The line next to the clock-in button: until when the shift runs, and what a pickup before then does. */
    fun describe(policy: ClockInPolicy, nowWallMillis: Long, nextAlarmWallMillis: Long?, zone: ZoneId = ZoneId.systemDefault()): String {
        val seconds = seconds(policy, nowWallMillis, nextAlarmWallMillis)
        val until = TIME.format(Instant.ofEpochMilli(nowWallMillis + seconds * 1000).atZone(zone))
        val early = "Picking the phone up before then ends the shift early."
        return when {
            alarmFor(policy, nowWallMillis, nextAlarmWallMillis) != null ->
                "A shift you start now runs until $until, two minutes before your alarm. $early"
            policy.day -> "A shift you start now runs until $until. $early"
            else -> "A shift you start now runs until $until. $early Set your alarm first, and the shift ends just before it."
        }
    }

    private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
}
