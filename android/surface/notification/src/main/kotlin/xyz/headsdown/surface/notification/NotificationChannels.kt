package xyz.headsdown.surface.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.content.getSystemService

object NotificationChannels {
    /** The user-started shift. Silent; promotable (a MIN-importance channel cannot be promoted). */
    const val SHIFT = "hd.shift"

    /** The morning haul reveal: high importance so the full-screen intent can fire. */
    const val REVEAL = "hd.reveal"

    fun ensure(context: Context) {
        val nm = context.getSystemService<NotificationManager>() ?: return
        val shift = NotificationChannel(SHIFT, "Rig status", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Shows your rig while a shift is running"
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }
        val reveal = NotificationChannel(REVEAL, "Morning haul", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Your haul reveal at your alarm"
            setShowBadge(true)
        }
        nm.createNotificationChannels(listOf(shift, reveal))
    }
}
