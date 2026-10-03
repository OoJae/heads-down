package xyz.headsdown.core.keys

import android.annotation.SuppressLint
import android.content.Context

/** Durable storage for [RigCounter]. [store] must be synchronous and durable when it returns true. */
interface CounterStore {
    fun load(): ULong
    fun store(value: ULong): Boolean
}

/**
 * The rig's single message counter, shared by HEARTBEAT, BREAK, FREEZE and PLAN: the program
 * requires `counter > rig.hb_counter` for every kind, so one sequence serves them all.
 *
 * **Write-ahead.** [next] persists the new value *before* returning it, so it is durable before
 * any signature over it can exist. A crash can skip a value but never reuse one.
 *
 * **Floor from chain.** [raiseFloor] lifts the local value to the on-chain `Rig.hb_counter`
 * (e.g. after app data was cleared, or a second install signed with the same key), so the next
 * message is strictly above what the program last accepted.
 *
 * **The floor is read from an RPC, so it is bounded.** One raise moves the local value by at most
 * [MAX_FLOOR_STEP]. The program refuses a message whose counter is more than 2^32 above its own
 * (INTERFACE §4.1), so a forged `hb_counter` that lifted the local value past that could leave the
 * phone signing messages the program never accepts again, and `u64::MAX` would end the sequence
 * for good. A million per raise is far more than a rig uses in years (one message per ORE round),
 * and a real gap larger than that closes over the next raises.
 */
class RigCounter(private val store: CounterStore) {

    /** The last value handed out (or the floor), i.e. the next message uses `current() + 1`. */
    @Synchronized
    fun current(): ULong = store.load()

    @Synchronized
    fun next(): ULong {
        val next = store.load() + 1uL
        check(next != 0uL) { "counter exhausted" } // a u64 wrap would reuse values
        check(store.store(next)) { "counter not persisted" }
        return next
    }

    /**
     * Lifts the local value towards [onChainHbCounter], by at most [MAX_FLOOR_STEP].
     *
     * @return true when [next] now yields a value strictly greater than [onChainHbCounter]; false
     *   when the gap was larger than one step (the value moved one step closer).
     */
    @Synchronized
    fun raiseFloor(onChainHbCounter: ULong): Boolean {
        val local = store.load()
        if (onChainHbCounter <= local) return true
        val reached = onChainHbCounter - local <= MAX_FLOOR_STEP
        val target = if (reached) onChainHbCounter else local + MAX_FLOOR_STEP
        check(store.store(target)) { "counter floor not persisted" }
        return reached
    }

    companion object {
        /** The furthest one chain read may move the counter: 2^20, well inside the program's 2^32 step bound. */
        const val MAX_FLOOR_STEP: ULong = 1_048_576uL
    }
}

/**
 * [CounterStore] in private SharedPreferences, written with `commit()` (synchronous): the
 * result must be known before a signature exists. Same file and key as the wave-1 heartbeat
 * counter, so an upgraded install continues its sequence.
 */
// commit() is deliberate: see the class KDoc.
@SuppressLint("ApplySharedPref", "UseKtx")
class PrefsCounterStore(context: Context) : CounterStore {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun load(): ULong = prefs.getLong(KEY, 0L).toULong()

    override fun store(value: ULong): Boolean = prefs.edit().putLong(KEY, value.toLong()).commit()

    private companion object {
        const val FILE = "hd_heartbeat_counter"
        const val KEY = "counter"
    }
}
