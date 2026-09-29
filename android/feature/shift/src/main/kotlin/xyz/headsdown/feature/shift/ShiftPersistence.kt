package xyz.headsdown.feature.shift

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

// The write-ahead message counter lives in core/keys (RigCounter + PrefsCounterStore): one
// sequence shared by HEARTBEAT, BREAK, FREEZE and PLAN, with the same prefs file as before.

/** Last shift, as recorded by the service. Read by the "killed by the OS" health check. */
data class ShiftRecord(
    val shiftId: Long,
    val mode: ShiftMode,
    val armedAtWallMillis: Long,
    val lastHeartbeatWallMillis: Long?,
    val darkRounds: Int,
    /** Set when the shift left the running states through our own code path. */
    val endedAtWallMillis: Long?,
    val endReason: String?,
)

/** Tiny SharedPreferences journal of the most recent shift. */
class ShiftJournal(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun onArmed(spec: ShiftSpec, nowWall: Long) {
        prefs.edit {
            clear()
            putLong(K_SHIFT, spec.shiftId)
            putString(K_MODE, spec.mode.name)
            putLong(K_ARMED, nowWall)
            putInt(K_ROUNDS, 0)
        }
    }

    fun onHeartbeat(nowWall: Long, darkRounds: Int) {
        prefs.edit { putLong(K_LAST_HB, nowWall).putInt(K_ROUNDS, darkRounds) }
    }

    /** Graceful or deliberate end (user end, break, freeze): anything but an OS kill. */
    fun onEnded(nowWall: Long, reason: String) {
        // commit = true: this record is what distinguishes "we stopped" from "we were killed",
        // and the process may be torn down right after stopSelf().
        prefs.edit(commit = true) { putLong(K_ENDED, nowWall).putString(K_REASON, reason) }
    }

    fun last(): ShiftRecord? {
        if (!prefs.contains(K_SHIFT)) return null
        return ShiftRecord(
            shiftId = prefs.getLong(K_SHIFT, 0),
            mode = runCatching { ShiftMode.valueOf(prefs.getString(K_MODE, null).orEmpty()) }.getOrDefault(ShiftMode.NIGHT),
            armedAtWallMillis = prefs.getLong(K_ARMED, 0),
            lastHeartbeatWallMillis = prefs.getLong(K_LAST_HB, -1).takeIf { it >= 0 },
            darkRounds = prefs.getInt(K_ROUNDS, 0),
            endedAtWallMillis = prefs.getLong(K_ENDED, -1).takeIf { it >= 0 },
            endReason = prefs.getString(K_REASON, null),
        )
    }

    private companion object {
        const val FILE = "hd_shift_journal"
        const val K_SHIFT = "shift_id"
        const val K_MODE = "mode"
        const val K_ARMED = "armed_at"
        const val K_LAST_HB = "last_hb"
        const val K_ROUNDS = "dark_rounds"
        const val K_ENDED = "ended_at"
        const val K_REASON = "end_reason"
    }
}
