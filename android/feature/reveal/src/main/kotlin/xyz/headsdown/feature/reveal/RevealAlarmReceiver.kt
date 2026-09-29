package xyz.headsdown.feature.reveal

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import xyz.headsdown.surface.notification.NotificationChannels
import xyz.headsdown.surface.notification.R as NotificationR

/**
 * Fires at the planned reveal time and posts a full-screen-intent notification that opens
 * [RevealActivity] over the lock screen. Non-exported: only our own PendingIntent reaches it,
 * and it reads nothing from the Intent beyond the action.
 *
 * If the full-screen-intent app-op is revoked (Android 14+), the same notification still
 * arrives as a heads-up: graceful, not broken.
 */
class RevealAlarmReceiver : BroadcastReceiver() {
    // POST_NOTIFICATIONS is checked explicitly below for API 33+ (it does not exist on 31/32,
    // where an unconditional check would always fail) and notify() is guarded against
    // SecurityException. Lint cannot model the SDK-gated check, hence the suppression.
    @SuppressLint("MissingPermission")
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_REVEAL) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        NotificationChannels.ensure(context)
        val open = PendingIntent.getActivity(
            context,
            REQUEST_CODE,
            Intent(context, RevealActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, NotificationChannels.REVEAL)
            .setSmallIcon(NotificationR.drawable.ic_stat_rig)
            .setContentTitle("Your haul is ready")
            .setContentText("See how last night's shift went")
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(open)
            .setFullScreenIntent(open, true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // Permission revoked between the check and the post: no reveal tonight, no crash.
        }
    }

    companion object {
        const val ACTION_REVEAL = "xyz.headsdown.reveal.SHOW"
        const val NOTIFICATION_ID = 0x5256 // "RV"
        private const val REQUEST_CODE = 0x5256
    }
}
