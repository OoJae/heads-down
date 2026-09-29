package xyz.headsdown.feature.reveal

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.getSystemService
import java.time.ZoneId

data class ScheduledReveal(val plan: RevealPlan, val exact: Boolean)

/**
 * Schedules the morning reveal. Uses `USE_EXACT_ALARM` (Android 13+, granted at install;
 * the dApp Store has no Play-style restriction on it) and `SCHEDULE_EXACT_ALARM` on 12/12L,
 * always checking `canScheduleExactAlarms()` and degrading to an inexact alarm otherwise.
 */
class RevealScheduler(private val context: Context) {
    private val alarmManager: AlarmManager? get() = context.getSystemService()

    fun canScheduleExactAlarms(): Boolean = alarmManager?.canScheduleExactAlarms() == true

    /** The user's next alarm clock (from any clock app), if any. */
    fun nextSystemAlarmWallMillis(): Long? = alarmManager?.nextAlarmClock?.triggerTime

    fun scheduleNext(nowWallMillis: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): ScheduledReveal? {
        val am = alarmManager ?: return null
        val plan = RevealTimePlanner.plan(nowWallMillis, nextSystemAlarmWallMillis(), zone)
        val pi = alarmIntent()
        val exact = if (am.canScheduleExactAlarms()) {
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, plan.triggerAtWallMillis, pi)
                true
            } catch (_: SecurityException) {
                false // permission revoked between the check and the call
            }
        } else {
            false
        }
        if (!exact) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, plan.triggerAtWallMillis, pi)
        return ScheduledReveal(plan, exact)
    }

    fun cancel() {
        alarmManager?.cancel(alarmIntent())
    }

    /** Android 12/12L: lets the user grant exact alarms (no-op screen on 13+ with USE_EXACT_ALARM). */
    fun exactAlarmSettingsIntent(): Intent =
        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))

    private fun alarmIntent(): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, RevealAlarmReceiver::class.java).setAction(RevealAlarmReceiver.ACTION_REVEAL),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private companion object {
        const val REQUEST_CODE = 0x5245 // "RE"
    }
}
