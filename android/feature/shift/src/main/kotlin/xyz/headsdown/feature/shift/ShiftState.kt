package xyz.headsdown.feature.shift

import xyz.headsdown.core.keys.RigSignalState

enum class ShiftMode {
    /** Charger + planned window + exact-alarm clock-out. Unplugging cools the rig. */
    NIGHT,

    /** 25/50/90-minute desk rig; no charger requirement. */
    DAY,

    /** Zero SOL deployed: still counts for streaks, rooms and Stack. */
    FOCUS_ONLY,
}

/** What the user armed. [shiftId] is the on-chain shift id (or a local id for focus-only). */
data class ShiftSpec(
    val shiftId: Long,
    val mode: ShiftMode,
    val requiresCharger: Boolean = mode == ShiftMode.NIGHT,
    /** Planned length in ORE rounds (Day Shift / planned Night window), for progress only. */
    val plannedRounds: Int? = null,
) {
    init {
        require(shiftId >= 0) { "shift id is a u64 on-chain" }
        require(plannedRounds == null || plannedRounds > 0)
    }

    companion object {
        /** Day Shift presets (25/50/90 min) expressed in ~78 s ORE rounds, rounded up. */
        fun roundsForMinutes(minutes: Int): Int = ((minutes * 60L + 77) / 78).toInt()
    }
}

/** Why a shift left DOWN. */
enum class CoolReason { LIFTED, SCREEN_ON, UNPLUGGED }

enum class BreakReason { LIFTED, SCREEN_ON, UNPLUGGED, UNLOCKED }

/** Device facts the machine tracks independently of its state. */
data class Signals(
    val faceDown: Boolean = false,
    val screenOn: Boolean = true,
    val charging: Boolean = false,
) {
    /** "Dark": face-down, screen off, and on the charger if the shift demands it. */
    fun isDark(spec: ShiftSpec): Boolean = faceDown && !screenOn && (charging || !spec.requiresCharger)

    /** Why this signal set is not dark (first failing condition), for cooling bookkeeping. */
    fun coolReason(spec: ShiftSpec): CoolReason? = when {
        screenOn -> CoolReason.SCREEN_ON
        !faceDown -> CoolReason.LIFTED
        spec.requiresCharger && !charging -> CoolReason.UNPLUGGED
        else -> null
    }
}

/**
 * Rig lifecycle. Times are monotonic milliseconds (`SystemClock.elapsedRealtime`), never wall
 * clock, so user clock changes cannot stretch the grace period.
 */
sealed interface ShiftState {
    /** The wire state a heartbeat/BREAK/FREEZE for this state would carry. */
    val wire: RigSignalState

    data object Idle : ShiftState {
        override val wire get() = RigSignalState.IDLE
    }

    data class Armed(val spec: ShiftSpec, val since: Long) : ShiftState {
        override val wire get() = RigSignalState.ARMED
    }

    /** Rig hot: the only state in which heartbeats (and therefore digs) happen. */
    data class Down(val spec: ShiftSpec, val since: Long, val firstDownAt: Long) : ShiftState {
        override val wire get() = RigSignalState.DOWN
    }

    /** Grace window after leaving DOWN. Returns to [Down] if dark again before [deadline]. */
    data class Cooling(
        val spec: ShiftSpec,
        val since: Long,
        val deadline: Long,
        val reason: CoolReason,
        val firstDownAt: Long,
    ) : ShiftState {
        override val wire get() = RigSignalState.COOLING
    }

    data class Broken(val spec: ShiftSpec, val at: Long, val reason: BreakReason) : ShiftState {
        override val wire get() = RigSignalState.BROKEN
    }

    /** Device-key freeze: no digs until the wallet unfreezes. Absorbs every other event. */
    data class Frozen(val shiftId: Long?, val at: Long) : ShiftState {
        override val wire get() = RigSignalState.FROZEN
    }
}

val ShiftState.spec: ShiftSpec?
    get() = when (this) {
        is ShiftState.Armed -> spec
        is ShiftState.Down -> spec
        is ShiftState.Cooling -> spec
        is ShiftState.Broken -> spec
        ShiftState.Idle, is ShiftState.Frozen -> null
    }

val ShiftState.isHot: Boolean get() = this is ShiftState.Down
