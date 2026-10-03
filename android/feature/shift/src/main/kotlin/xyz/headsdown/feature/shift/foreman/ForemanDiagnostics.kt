package xyz.headsdown.feature.shift.foreman

import java.util.Locale

/**
 * What the pickup watch saw during the shift service's last run: counts and a sample rate, kept
 * in memory for the debug sensor lab screen. No sensor value, no timestamp of a motion, nothing
 * is written anywhere. It is how a night on a real phone can be checked without logging.
 */
internal object ForemanDiagnostics {

    data class LastRun(
        val endedWallMillis: Long,
        val model: String,
        val breaksEnabled: Boolean,
        val stats: PickupWatch.Stats,
        val samples: Long,
        val streamSeconds: Double,
    ) {
        /** The rate the accelerometer really delivered at (the classifier needs about 50 Hz, and at least 20). */
        val deliveredHz: Double get() = if (streamSeconds > 0.0) samples / streamSeconds else 0.0

        fun summary(): String = String.format(
            Locale.ROOT,
            "%s%s: %.1f Hz over %.0f min; %d triggers (%d re-fires, %d while not hot, %d interrupted), %d judged, %d pickups",
            model, if (breaksEnabled) "" else " (breaks switched off, ${stats.switchedOff} windows not judged)",
            deliveredHz, streamSeconds / 60.0, stats.triggers, stats.reFires, stats.notHot, stats.interrupted, stats.judged, stats.pickups,
        )
    }

    @Volatile var lastRun: LastRun? = null
}
