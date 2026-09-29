package xyz.headsdown.surface.notification

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

/** What the onboarding screen should do next about POST_NOTIFICATIONS. */
enum class NotificationPermissionStep {
    /** Already allowed (or not a runtime permission below Android 13). */
    GRANTED,

    /** Ask with the system dialog. */
    REQUEST,

    /** The user declined once: explain why the shift needs it, then ask again. */
    EXPLAIN_THEN_REQUEST,

    /** The system will not show the dialog again: send the user to app notification settings. */
    OPEN_SETTINGS,
}

/**
 * POST_NOTIFICATIONS runtime flow (Android 13+). The foreground service must be able to show
 * its notification, and the morning reveal needs one, so the shift is gated on this.
 */
object NotificationPermissionPolicy {
    const val PERMISSION = Manifest.permission.POST_NOTIFICATIONS

    /**
     * @param shouldShowRationale `ActivityCompat.shouldShowRequestPermissionRationale`
     * @param askedBefore whether we have launched the request at least once
     */
    fun nextStep(
        sdkInt: Int,
        granted: Boolean,
        shouldShowRationale: Boolean,
        askedBefore: Boolean,
    ): NotificationPermissionStep = when {
        granted -> NotificationPermissionStep.GRANTED
        sdkInt < Build.VERSION_CODES.TIRAMISU -> NotificationPermissionStep.OPEN_SETTINGS // app-level toggle only
        shouldShowRationale -> NotificationPermissionStep.EXPLAIN_THEN_REQUEST
        askedBefore -> NotificationPermissionStep.OPEN_SETTINGS // denied twice / "don't ask again"
        else -> NotificationPermissionStep.REQUEST
    }
}

/** Runtime checks and settings deep links for notification capabilities. */
object NotificationAccess {
    fun areEnabled(context: Context): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** Android 16+: whether the user lets us promote the shift to a Live Update. */
    fun canPostPromoted(context: Context): Boolean =
        NotificationManagerCompat.from(context).canPostPromotedNotifications()

    /** Android 14+: full-screen intents need this app-op (granted by default outside Play). */
    fun canUseFullScreenIntent(context: Context): Boolean =
        NotificationManagerCompat.from(context).canUseFullScreenIntent()

    fun appNotificationSettings(context: Context): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

    fun promotionSettings(context: Context): Intent =
        if (Build.VERSION.SDK_INT >= 36) {
            Intent(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        } else {
            appNotificationSettings(context)
        }

    fun fullScreenIntentSettings(context: Context): Intent =
        if (Build.VERSION.SDK_INT >= 34) {
            Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
                .setData(android.net.Uri.parse("package:${context.packageName}"))
        } else {
            appNotificationSettings(context)
        }
}
