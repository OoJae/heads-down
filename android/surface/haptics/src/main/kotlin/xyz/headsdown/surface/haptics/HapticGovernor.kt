package xyz.headsdown.surface.haptics

/** When a cue is requested. */
enum class HapticMoment {
    /** A shift is running (usually at night, phone face-down on a nightstand). */
    SHIFT,

    /** The morning reveal is on screen. */
    REVEAL,

    /** The app is open and the user is looking at it. */
    FOREGROUND,
}

/**
 * Defence in depth for "never buzz per round at night".
 *
 * The structural guarantee is that [HapticCue] has no per-round cue. On top of that:
 * - the reveal cues ([HapticCue.REVEAL_DRUMROLL], [HapticCue.MOTHERLODE_FLOURISH]) are refused
 *   unless the reveal is on screen, so nothing celebratory can fire during a shift;
 * - the UI cues ([HapticCue.isUi]) are refused unless the app is open with no shift running, so a
 *   finger on the screen can never shake the accelerometer of a rig that is working;
 * - every cue has a minimum interval;
 * - during a shift the whole vocabulary shares a small rolling budget, so even a caller bug that
 *   asks for a cue every ~78 s round is cut to a handful of buzzes a night;
 * - the UI cues share a rolling budget of their own, so a finger drumming on the slab is a few
 *   ticks, not a motor that never stops.
 *
 * Pure: the clock is monotonic milliseconds (`SystemClock.elapsedRealtime` on a device).
 */
class HapticGovernor(
    private val clock: () -> Long,
    private val policy: Policy = Policy(),
) {
    data class Policy(
        val minIntervalMillis: Map<HapticCue, Long> = mapOf(
            HapticCue.ARM_THUNK to 5 * MINUTE,
            HapticCue.COOLING_TICK to MINUTE,
            HapticCue.REVEAL_DRUMROLL to 30_000L,
            HapticCue.MOTHERLODE_FLOURISH to 30_000L,
            HapticCue.UI_KNOCK to 180L,
            HapticCue.UI_SNAP to 400L,
            HapticCue.UI_CONFIRM to 1_500L,
        ),
        /** At most this many cues per [shiftWindowMillis] while a shift runs. */
        val shiftBudget: Int = 2,
        val shiftWindowMillis: Long = 60 * MINUTE,
        /** At most this many UI cues, of any kind, per [uiWindowMillis]. */
        val uiBudget: Int = 8,
        val uiWindowMillis: Long = 10_000L,
    ) {
        init {
            require(shiftBudget >= 1 && shiftWindowMillis > 0)
            require(uiBudget >= 1 && uiWindowMillis > 0)
        }
    }

    private val lastPlayed = HashMap<HapticCue, Long>()
    private val shiftPlays = ArrayDeque<Long>()
    private val uiPlays = ArrayDeque<Long>()

    /** True if [cue] may play now; if so, it is recorded as played. */
    @Synchronized
    fun tryAcquire(cue: HapticCue, moment: HapticMoment): Boolean {
        if (!allowedAt(cue, moment)) return false
        val now = clock()
        val last = lastPlayed[cue]
        val minInterval = policy.minIntervalMillis[cue] ?: 0L
        if (last != null && now - last < minInterval) return false
        if (cue.isUi) {
            while (uiPlays.isNotEmpty() && now - uiPlays.first() >= policy.uiWindowMillis) uiPlays.removeFirst()
            if (uiPlays.size >= policy.uiBudget) return false
        }
        if (moment == HapticMoment.SHIFT) {
            while (shiftPlays.isNotEmpty() && now - shiftPlays.first() >= policy.shiftWindowMillis) shiftPlays.removeFirst()
            if (shiftPlays.size >= policy.shiftBudget) return false
            shiftPlays.addLast(now)
        }
        if (cue.isUi) uiPlays.addLast(now)
        lastPlayed[cue] = now
        return true
    }

    companion object {
        private const val MINUTE = 60_000L

        /**
         * Which cues exist at which moment. Reveal cues never fire outside the reveal, and UI cues
         * never while a shift runs or over the reveal.
         */
        fun allowedAt(cue: HapticCue, moment: HapticMoment): Boolean = when (cue) {
            HapticCue.ARM_THUNK, HapticCue.COOLING_TICK -> moment != HapticMoment.REVEAL
            HapticCue.REVEAL_DRUMROLL, HapticCue.MOTHERLODE_FLOURISH -> moment == HapticMoment.REVEAL
            HapticCue.UI_KNOCK, HapticCue.UI_SNAP, HapticCue.UI_CONFIRM -> moment == HapticMoment.FOREGROUND
        }
    }
}
