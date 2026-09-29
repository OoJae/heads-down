package xyz.headsdown.feature.shift

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Write-ahead heartbeat counter. `commit()` (synchronous) runs before the signature exists,
 * so a crash can skip a value but can never reuse one: the on-chain rule is
 * `counter > rig.counter`.
 */
// commit() is deliberate throughout: the result must be known before a signature exists.
@SuppressLint("ApplySharedPref", "UseKtx")
class PrefsHeartbeatCounter(context: Context) : HeartbeatCounter {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    @Synchronized
    override fun next(): ULong {
        val next = current() + 1uL
        check(next != 0uL) { "counter exhausted" } // u64 wrap would reuse values
        check(prefs.edit().putLong(KEY, next.toLong()).commit()) { "counter not persisted" }
        return next
    }

    /** Raise the floor after reading `Rig.counter` from chain (e.g. after app data was cleared). */
    @Synchronized
    fun ensureAtLeast(floor: ULong) {
        if (current() < floor) check(prefs.edit().putLong(KEY, floor.toLong()).commit())
    }

    private fun current(): ULong = prefs.getLong(KEY, 0L).toULong()

    private companion object {
        const val FILE = "hd_heartbeat_counter"
        const val KEY = "counter"
    }
}

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
