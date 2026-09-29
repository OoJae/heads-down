package xyz.headsdown.feature.oemkeepalive

import android.app.ApplicationExitInfo

enum class ExitCause { LOW_MEMORY, KILLED_BY_SYSTEM_OR_OEM, CRASH, ANR, USER_STOPPED, OTHER }

/** One `ApplicationExitInfo`, reduced to what the health check needs. */
data class ProcessExit(val timestampWallMillis: Long, val cause: ExitCause, val description: String?)

/** The last shift as journaled by the shift service. */
data class LastShift(
    val armedAtWallMillis: Long,
    val lastHeartbeatWallMillis: Long?,
    val endedAtWallMillis: Long?,
    val endReason: String?,
    val darkRounds: Int,
)

sealed interface ShiftHealth {
    data object NoRecentShift : ShiftHealth
    data object StillRunning : ShiftHealth
    data class EndedNormally(val reason: String?) : ShiftHealth

    /** "Last night's shift was killed by the OS": the process died without ending the shift. */
    data class KilledByOs(val diedAtWallMillis: Long, val cause: ExitCause, val darkRounds: Int) : ShiftHealth

    /** The shift never ended and Android kept no exit record (reboot, battery died, force stop). */
    data class DiedWithoutRecord(val lastSeenWallMillis: Long, val darkRounds: Int) : ShiftHealth
}

object ShiftHealthCheck {
    const val DEFAULT_LOOKBACK_MILLIS = 36L * 60 * 60 * 1000

    fun evaluate(
        last: LastShift?,
        exits: List<ProcessExit>,
        serviceRunning: Boolean,
        nowWallMillis: Long,
        lookbackMillis: Long = DEFAULT_LOOKBACK_MILLIS,
    ): ShiftHealth {
        if (last == null || last.armedAtWallMillis < nowWallMillis - lookbackMillis) return ShiftHealth.NoRecentShift
        if (serviceRunning) return ShiftHealth.StillRunning
        if (last.endedAtWallMillis != null) return ShiftHealth.EndedNormally(last.endReason)

        val death = exits
            .filter { it.timestampWallMillis >= last.armedAtWallMillis && it.timestampWallMillis <= nowWallMillis }
            .minByOrNull { it.timestampWallMillis }
        return if (death != null) {
            ShiftHealth.KilledByOs(death.timestampWallMillis, death.cause, last.darkRounds)
        } else {
            ShiftHealth.DiedWithoutRecord(last.lastHeartbeatWallMillis ?: last.armedAtWallMillis, last.darkRounds)
        }
    }

    /** `ApplicationExitInfo.getReason()` -> [ExitCause]. */
    fun causeOf(reason: Int): ExitCause = when (reason) {
        ApplicationExitInfo.REASON_LOW_MEMORY -> ExitCause.LOW_MEMORY
        ApplicationExitInfo.REASON_SIGNALED,
        ApplicationExitInfo.REASON_OTHER,
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
        ApplicationExitInfo.REASON_FREEZER,
        -> ExitCause.KILLED_BY_SYSTEM_OR_OEM
        ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE -> ExitCause.CRASH
        ApplicationExitInfo.REASON_ANR -> ExitCause.ANR
        ApplicationExitInfo.REASON_USER_REQUESTED, ApplicationExitInfo.REASON_USER_STOPPED -> ExitCause.USER_STOPPED
        else -> ExitCause.OTHER
    }
}
