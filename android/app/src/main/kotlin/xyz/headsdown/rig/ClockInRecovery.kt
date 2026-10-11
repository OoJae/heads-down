package xyz.headsdown.rig

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import xyz.headsdown.core.chain.Pubkey
import xyz.headsdown.core.chain.accounts.RigAccount
import xyz.headsdown.core.keys.RigSignalState
import xyz.headsdown.feature.shift.ShiftMode
import xyz.headsdown.feature.shift.ShiftSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A clock-in this app built and handed to the wallet, kept until its outcome is known. On
 * mainnet the wallet sends the transaction itself, so it can land while this app is not there
 * to hear so (the session dropped, or the phone closed the app behind the wallet).
 */
class PendingClockIn(
    val authority: Pubkey,
    /** The rig key (33 bytes, compressed) the clock-in was built for. */
    val rigKey: ByteArray,
    /** The shift this phone prepared: its id is the Rig's id before the transaction, plus one. */
    val spec: ShiftSpec,
    val lastValidBlockHeight: Long,
    val preparedAtUnix: Long,
)

/** What to do about a [PendingClockIn], given the Rig as read on-chain. */
sealed interface Recovery {
    /** The clock-in landed and its shift is open: arm [spec], with the counter above [hbCounter]. */
    data class Arm(val spec: ShiftSpec, val hbCounter: ULong, val streak: Long) : Recovery

    /** It has not landed and still could. Look again. */
    data object Wait : Recovery

    /** It can no longer be armed here. Forget it. */
    data class Drop(val why: String) : Recovery
}

/**
 * Whether a clock-in the wallet was handed, and this app never heard back about, did land.
 *
 * The shift that is armed is the one THIS PHONE prepared ([PendingClockIn.spec]). The Rig read
 * can only confirm it, exactly as after a clock-in that confirmed in the normal way: a shift id
 * is never adopted from the RPC, so a node answering with a later id gets nothing signed for it.
 */
object ClockInRecovery {
    private val LIVE = setOf(RigSignalState.ARMED, RigSignalState.DOWN, RigSignalState.COOLING)

    /**
     * Blocks past `lastValidBlockHeight` before a transaction that does not show is given up:
     * the Rig may be read from a node a few blocks behind the one that gave the height.
     */
    const val EXPIRY_MARGIN_BLOCKS = 32L

    /** Given up by the clock alone, when the height cannot be read. A blockhash lives about a minute. */
    const val GIVE_UP_SECONDS = 600L

    fun decide(pending: PendingClockIn, rig: RigAccount?, myKey: ByteArray?, nowUnix: Long, blockHeight: Long?): Recovery {
        if (myKey == null || !myKey.contentEquals(pending.rigKey)) return Recovery.Drop("the rig key changed")
        pending.spec.windowEndUnix?.let { if (nowUnix >= it) return Recovery.Drop("its window has ended") }
        val expected = pending.spec.shiftId.toULong()
        val mine = rig != null && rig.p256Pubkey.toByteArray().contentEquals(myKey)
        if (rig == null || !mine || rig.shiftId < expected) {
            val expired = (blockHeight != null && blockHeight > pending.lastValidBlockHeight + EXPIRY_MARGIN_BLOCKS) ||
                nowUnix - pending.preparedAtUnix > GIVE_UP_SECONDS
            return if (expired) Recovery.Drop("it never landed") else Recovery.Wait
        }
        if (rig.shiftId > expected) return Recovery.Drop("a later shift was armed")
        if (!rig.shiftOpen || rig.state !in LIVE) return Recovery.Drop("that shift has ended")
        return Recovery.Arm(pending.spec, rig.hbCounter, rig.streak)
    }
}

/** Where the [PendingClockIn] is kept: written before the wallet is asked to sign, so it outlives this process. */
@Singleton
class PendingClockInStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(): PendingClockIn? = runCatching {
        val authority = prefs.getString(KEY_AUTHORITY, null) ?: return null
        val key = prefs.getString(KEY_RIG_KEY, null) ?: return null
        PendingClockIn(
            authority = Pubkey.fromBase58(authority),
            rigKey = key.chunked(2).map { it.toInt(16).toByte() }.toByteArray(),
            spec = ShiftSpec(
                shiftId = prefs.getLong(KEY_SHIFT_ID, -1),
                mode = ShiftMode.valueOf(prefs.getString(KEY_MODE, null) ?: return null),
                requiresCharger = prefs.getBoolean(KEY_CHARGER, false),
                plannedRounds = prefs.getInt(KEY_PLANNED, 0).takeIf { it > 0 },
                leaseRounds = prefs.getInt(KEY_LEASE, 1),
                windowEndUnix = prefs.getLong(KEY_WINDOW_END, 0).takeIf { it > 0 },
            ),
            lastValidBlockHeight = prefs.getLong(KEY_LAST_VALID, 0),
            preparedAtUnix = prefs.getLong(KEY_PREPARED_AT, 0),
        )
    }.getOrNull() // a record that does not read back is no record: nothing is armed from it

    fun save(pending: PendingClockIn) {
        // Committed, not applied: the wallet opens next and this process may not come back.
        prefs.edit(commit = true) {
            clear()
            putString(KEY_AUTHORITY, pending.authority.toBase58())
            putString(KEY_RIG_KEY, pending.rigKey.joinToString("") { "%02x".format(it) })
            putLong(KEY_SHIFT_ID, pending.spec.shiftId)
            putString(KEY_MODE, pending.spec.mode.name)
            putBoolean(KEY_CHARGER, pending.spec.requiresCharger)
            putInt(KEY_PLANNED, pending.spec.plannedRounds ?: 0)
            putInt(KEY_LEASE, pending.spec.leaseRounds)
            putLong(KEY_WINDOW_END, pending.spec.windowEndUnix ?: 0)
            putLong(KEY_LAST_VALID, pending.lastValidBlockHeight)
            putLong(KEY_PREPARED_AT, pending.preparedAtUnix)
        }
    }

    fun clear() {
        prefs.edit(commit = true) { clear() }
    }

    private companion object {
        const val FILE = "hd_pending_clock_in"
        const val KEY_AUTHORITY = "authority"
        const val KEY_RIG_KEY = "rig_key"
        const val KEY_SHIFT_ID = "shift_id"
        const val KEY_MODE = "mode"
        const val KEY_CHARGER = "requires_charger"
        const val KEY_PLANNED = "planned_rounds"
        const val KEY_LEASE = "lease_rounds"
        const val KEY_WINDOW_END = "window_end"
        const val KEY_LAST_VALID = "last_valid_block_height"
        const val KEY_PREPARED_AT = "prepared_at"
    }
}
