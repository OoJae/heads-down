package xyz.headsdown.surface.notification

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import xyz.headsdown.core.design.HdArgb

/**
 * Builds the ongoing shift notification.
 *
 * One code path for every OS version:
 * - Android 16+ (Seeker if updated, stock Android 16): `NotificationCompat.ProgressStyle` +
 *   `setRequestPromotedOngoing(true)` + `setShortCriticalText` make it a Live Update with a
 *   status-bar chip and AOD presence (user must not have revoked promotion).
 * - Android 14/15: androidx.core falls back to a standard ongoing notification with a
 *   classic progress bar; the promotion extra is ignored.
 *
 * The Redmi 14C this was built on runs Android 16 under HyperOS 3, so it takes the first
 * path; what HyperOS shows for a promoted notification there has not been recorded.
 */
class ShiftNotificationFactory(private val context: Context) {

    fun build(state: RigNotificationState, contentIntent: PendingIntent?): Notification {
        val copy = RigNotificationCopy.from(state)
        val builder = NotificationCompat.Builder(context, NotificationChannels.SHIFT)
            .setSmallIcon(R.drawable.ic_stat_rig)
            .setContentTitle(copy.title)
            .setContentText(copy.text)
            .setColor(EMBER)
            .setOngoing(copy.ongoing)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            // Contains no personal or financial data, so it may show in full on the lock screen.
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setRequestPromotedOngoing(copy.promote)
            .setShortCriticalText(copy.chip)

        val since = state.darkSinceWallMillis
        if (copy.countUpChronometer && since != null) {
            builder.setWhen(since).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(false)
        } else {
            builder.setShowWhen(false)
        }

        val max = copy.progressMax
        if (max != null) {
            builder.setStyle(
                NotificationCompat.ProgressStyle()
                    .setStyledByProgress(true)
                    .setProgressSegments(listOf(NotificationCompat.ProgressStyle.Segment(max).setColor(EMBER)))
                    .setProgress(copy.progress ?: 0),
            )
        } else {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(copy.text))
        }

        contentIntent?.let(builder::setContentIntent)
        return builder.build()
    }

    companion object {
        const val SHIFT_NOTIFICATION_ID = 0x4844 // "HD"

        /** The notification's accent: the design system's ember (:core:design). */
        const val EMBER: Int = HdArgb.EMBER
    }
}
