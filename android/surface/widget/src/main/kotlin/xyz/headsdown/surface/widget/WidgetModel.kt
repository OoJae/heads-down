package xyz.headsdown.surface.widget

/** Rig heat as the widget shows it. Deliberately independent of feature:shift's types. */
enum class RigHeat {
    COLD, ARMED, HOT, COOLING, FROZEN;

    /** A shift is in progress: only these can be stale after a reboot or an OS kill. */
    val running: Boolean get() = this == ARMED || this == HOT || this == COOLING
}

/** The live rig, pushed through [RigWidgetUpdates.onRig]. */
data class WidgetRig(
    val heat: RigHeat = RigHeat.COLD,
    /** Wall-clock start of this shift's dark time, for the count-up chronometer. */
    val darkSinceWallMillis: Long? = null,
    /** Heartbeats signed this shift (a count, never a price). */
    val darkRounds: Int = 0,
    /** Zero-SOL shift: counts for the streak, never digs. */
    val focusOnly: Boolean = false,
    /** False while heartbeats cannot dig (no rig key, or the rig is not registered on-chain). */
    val canDig: Boolean = true,
) {
    init {
        require(darkRounds >= 0)
    }
}

/** The most recent haul, pushed through [RigWidgetUpdates.onHaul]. Only real, on-chain hauls. */
data class WidgetHaul(
    /** ORE base units (11 decimals). */
    val oreAtoms: Long,
    val endedAtWallMillis: Long,
) {
    init {
        require(oreAtoms >= 0)
    }
}

/** Everything a Rig widget renders, as persisted by [WidgetStateStore]. */
data class RigWidgetState(
    val rig: WidgetRig = WidgetRig(),
    /** Dark rounds of the last shift that ended, for the cold widget. */
    val lastShiftRounds: Int? = null,
    val haul: WidgetHaul? = null,
    /** Nights in a row, from the on-chain Rig; null until known. */
    val streakNights: Int? = null,
    /** `Settings.Global.BOOT_COUNT` when [rig] was written. A later boot means no shift survived. */
    val bootCount: Int? = null,
)

/**
 * Pure state transitions for the widget store.
 *
 * - A running shift that stops (cold, frozen) remembers its dark rounds as the last shift.
 * - A running rig recorded in an earlier boot is shown cold: the shift service never survives a
 *   reboot, and a widget must not keep a "rig hot" chronometer ticking for a dead shift.
 */
object WidgetStateReducer {

    fun onRig(old: RigWidgetState, rig: WidgetRig, bootCount: Int?): RigWidgetState {
        val ended = old.rig.heat.running && !rig.heat.running
        val rounds = maxOf(old.rig.darkRounds, rig.darkRounds)
        return old.copy(
            rig = rig,
            lastShiftRounds = if (ended && rounds > 0) rounds else old.lastShiftRounds,
            bootCount = bootCount,
        )
    }

    fun onHaul(old: RigWidgetState, haul: WidgetHaul): RigWidgetState = old.copy(haul = haul)

    fun onStreak(old: RigWidgetState, nights: Int): RigWidgetState = old.copy(streakNights = nights.coerceAtLeast(0))

    /** What to display now: a running rig from another boot is cold. */
    fun forDisplay(state: RigWidgetState, currentBootCount: Int?): RigWidgetState {
        val staleBoot = state.bootCount != null && currentBootCount != null && state.bootCount != currentBootCount
        if (!state.rig.heat.running || !staleBoot) return state
        return onRig(state, WidgetRig(heat = RigHeat.COLD), currentBootCount)
    }
}

/**
 * Whether a state change is worth a widget render. The chronometer ticks on its own, so the
 * per-round `darkRounds` increment is persisted but never pushed: at most a few renders a shift.
 */
object WidgetUpdatePolicy {
    fun needsRender(old: RigWidgetState, new: RigWidgetState): Boolean {
        if (old == new) return false
        val o = old.rig
        val n = new.rig
        return o.heat != n.heat ||
            o.darkSinceWallMillis != n.darkSinceWallMillis ||
            o.focusOnly != n.focusOnly ||
            o.canDig != n.canDig ||
            old.lastShiftRounds != new.lastShiftRounds ||
            old.haul != new.haul ||
            old.streakNights != new.streakNights
    }
}

/**
 * Anchors a RemoteViews `Chronometer` (elapsedRealtime base) to a wall-clock start, so it ticks
 * on the home screen with no further updates. A start in the future shows 0:00.
 */
object ChronometerAnchor {
    fun base(darkSinceWallMillis: Long, nowWallMillis: Long, nowElapsedMillis: Long): Long =
        nowElapsedMillis - (nowWallMillis - darkSinceWallMillis).coerceAtLeast(0)
}
