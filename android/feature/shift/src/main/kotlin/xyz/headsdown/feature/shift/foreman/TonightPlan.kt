package xyz.headsdown.feature.shift.foreman

import xyz.headsdown.core.keys.ShiftPlan
import xyz.headsdown.ml.ForemanGate
import xyz.headsdown.ml.PlanProposal
import xyz.headsdown.ml.WalletCaps

/*
 * What the Shift Planner proposes, in types the app can show without depending on :ml.
 * Times are wall-clock epoch milliseconds unless a name says "Unix" (seconds, as on-chain).
 */

/**
 * The limits the wallet signed for the bound Rig (`set_caps`, INTERFACE.md §3.2), as the app
 * last read them from chain. Lamports and unix seconds. Only the wallet can raise them; nothing
 * in this package can.
 */
data class WalletLimits(
    val capWeekLamports: Long,
    val capShiftLamports: Long,
    val capRoundLamports: Long,
    /** Ceiling on the pot-adjusted cost `ema_ev`, lamports per ORE. */
    val capMaxCost: Long,
    val capsExpiryUnix: Long,
    val spentWeekLamports: Long = 0,
) {
    init {
        require(capWeekLamports >= 0 && capShiftLamports >= 0 && capRoundLamports >= 0 && capMaxCost >= 0 && spentWeekLamports >= 0) {
            "limits are unsigned on-chain"
        }
    }

    val remainingWeekLamports: Long get() = (capWeekLamports - spentWeekLamports).coerceAtLeast(0L)

    internal fun toCaps() = WalletCaps(capWeekLamports, capShiftLamports, capRoundLamports, capMaxCost, capsExpiryUnix, spentWeekLamports)

    companion object {
        /** From the Rig account's u64 fields. A value beyond `Long.MAX_VALUE` is clamped down to it. */
        fun fromChain(capWeek: ULong, capShift: ULong, capRound: ULong, capMaxCost: ULong, capsExpiryTs: Long, spentWeek: ULong) =
            WalletLimits(capWeek.clamped(), capShift.clamped(), capRound.clamped(), capMaxCost.clamped(), capsExpiryTs, spentWeek.clamped())

        private fun ULong.clamped(): Long = if (this > Long.MAX_VALUE.toULong()) Long.MAX_VALUE else toLong()
    }
}

/** What the user arms with: every PLAN field except the window (INTERFACE.md §5 `arm_shift`). */
data class PlanTemplate(
    val maxEvCost: Long,
    val digLamports: Long,
    val splitTiles: Int,
    val soloTiles: Int,
    val leaseRounds: Int = 1,
    val focusOnly: Boolean = false,
    val day: Boolean = false,
)

/** The two things the planner needs to split a week and to build a plan that could be signed. */
data class PlanningBudget(val limits: WalletLimits, val template: PlanTemplate)

/** How sure the planner is that the phone stays idle for a whole window. */
enum class WindowConfidence {
    /** Enough nights of history, and every slot's cautious estimate clears the bar. */
    CONFIDENT,

    /** Not enough nights observed yet. */
    NEEDS_HISTORY,

    /** Some slot of the window is not reliably idle. */
    NOT_CONFIDENT,
}

/** A window the planner proposes for a shift. A proposal: nothing is armed by it. */
data class PlannedWindow(
    val startWallMillis: Long,
    val endWallMillis: Long,
    /** 15-minute slots in the window. */
    val slots: Int,
    /** Mean and lowest P(idle) over those slots, 0..1. */
    val meanIdle: Double,
    val minIdle: Double,
    val expectedIdleHours: Double,
    val confidence: WindowConfidence,
) {
    val confident: Boolean get() = confidence == WindowConfidence.CONFIDENT
}

/** One of the next seven nights and the share of the week's budget the planner would give it. */
data class NightShare(
    val window: PlannedWindow?,
    /** ORE rounds (about 78 s each) the phone is expected to sit idle in the window. */
    val expectedIdleRounds: Double,
    /** Null when no limits were known. Never more than the per-shift cap or the week's remainder. */
    val lamports: Long?,
)

/** Why [TonightPlan.signable] is or is not there. */
enum class PlanGate {
    /** The planner proposes no window (no stretch of the next 24 h is reliably idle). */
    NO_WINDOW,

    /** A window, but the wallet's limits are not known here: nothing could be signed. */
    NO_LIMITS,

    /** The limits refuse it: expired, the window is over, or the template deploys nothing. */
    REFUSED_BY_LIMITS,

    /** [TonightPlan.signable] is the proposal clamped inside the limits. */
    WITHIN_LIMITS,
}

/**
 * The planner's answer for tonight: the proposed window, the week's budget split, and (when the
 * wallet's limits are known) the plan that could be signed for it.
 */
data class TonightPlan(
    val plannedAtWallMillis: Long,
    /** UTC offset the plan was made in, minutes. */
    val tzMinutes: Int,
    /** Local days the log has observations for (a night across midnight counts on both of its days). */
    val nightsObserved: Int,
    /** The best window starting within the next 24 hours, or null. */
    val window: PlannedWindow?,
    /** The next seven nights; index 0 is [window]. */
    val week: List<NightShare>,
    val signable: SignablePlan?,
    val gate: PlanGate,
) {
    /** Tonight's share of the week's budget, lamports; null when no limits were known. */
    val tonightLamports: Long? get() = week.firstOrNull()?.lamports
}

/**
 * A plan the rig key may sign for `arm_shift` (the P-256 PLAN path): the only form in which a
 * planner proposal can reach a signature.
 *
 * It has no public constructor and no `copy`. The one way to get an instance is
 * `ForemanGate.tighten` over the wallet's limits ([tighten], [retighten]), which clamps every
 * field down into them and drops what cannot be made valid. So holding a `SignablePlan` means:
 * `maxEvCost <= cap_max_cost`, `digLamports <= cap_round`, the window ends by `caps_expiry_ts`,
 * and the field ranges of INTERFACE.md §5 hold, for the limits it was checked against. The
 * program checks all of it again (`PlanExceedsCaps`, `CapsExpired`, `OutsideWindow`).
 */
class SignablePlan private constructor(
    val maxEvCost: Long,
    val digLamports: Long,
    val splitTiles: Int,
    val soloTiles: Int,
    val leaseRounds: Int,
    val focusOnly: Boolean,
    val day: Boolean,
    val windowStartUnix: Long,
    val windowEndUnix: Long,
) {
    /** The same plan as core/keys encodes it into the PLAN preimage and the `arm_shift` data. */
    fun toShiftPlan(): ShiftPlan = ShiftPlan(
        maxEvCost = maxEvCost.toULong(),
        digLamports = digLamports.toULong(),
        splitTiles = splitTiles,
        soloTiles = soloTiles,
        leaseRounds = leaseRounds,
        flags = (if (focusOnly) ShiftPlan.FLAG_FOCUS_ONLY else 0) or (if (day) ShiftPlan.FLAG_DAY else 0),
        windowStartTs = windowStartUnix,
        windowEndTs = windowEndUnix,
    )

    /**
     * This plan checked again, against [limits] as they are at [nowUnix] (they may have been
     * lowered, or have expired, since it was planned). Null when it is no longer valid. It can
     * only come back equal or tighter.
     */
    fun retighten(limits: WalletLimits, nowUnix: Long): SignablePlan? = through(limits, proposal(), nowUnix)

    private fun proposal() = PlanProposal(maxEvCost, digLamports, splitTiles, soloTiles, leaseRounds, focusOnly, day, windowStartUnix, windowEndUnix)

    override fun equals(other: Any?): Boolean = other is SignablePlan && other.proposal() == proposal()

    override fun hashCode(): Int = proposal().hashCode()

    override fun toString(): String =
        "SignablePlan(maxEvCost=$maxEvCost, digLamports=$digLamports, split=$splitTiles, solo=$soloTiles, lease=$leaseRounds, " +
            "focusOnly=$focusOnly, day=$day, window=$windowStartUnix..$windowEndUnix)"

    internal companion object {
        /** [template] for the window [windowStartUnix]..[windowEndUnix], clamped into [limits]; null if nothing valid remains. */
        fun tighten(limits: WalletLimits, template: PlanTemplate, windowStartUnix: Long, windowEndUnix: Long, nowUnix: Long): SignablePlan? =
            through(
                limits,
                PlanProposal(
                    template.maxEvCost, template.digLamports, template.splitTiles, template.soloTiles, template.leaseRounds,
                    template.focusOnly, template.day, windowStartUnix, windowEndUnix,
                ),
                nowUnix,
            )

        /** Every instance is made here, from what [ForemanGate.tighten] returned. */
        private fun through(limits: WalletLimits, proposal: PlanProposal, nowUnix: Long): SignablePlan? {
            val t = ForemanGate.tighten(limits.toCaps(), proposal, nowUnix) ?: return null
            return SignablePlan(t.maxEvCost, t.digLamports, t.splitTiles, t.soloTiles, t.leaseRounds, t.focusOnly, t.day, t.windowStartTs, t.windowEndTs)
        }
    }
}
