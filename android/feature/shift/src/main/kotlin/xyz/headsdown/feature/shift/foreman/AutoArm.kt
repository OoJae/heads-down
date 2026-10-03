package xyz.headsdown.feature.shift.foreman

import xyz.headsdown.feature.shift.ShiftSpec
import xyz.headsdown.feature.shift.ShiftState
import xyz.headsdown.feature.shift.Signals

/*
 * Auto-arm groundwork: when may a phone laid face-down on its charger arm a shift by itself?
 *
 * Nothing in the app calls this yet, and the switch is off by default (ForemanSettings). What is
 * here is the decision, as a pure function with tests, and the seam the chain client will
 * implement. FOREMAN.md ("Auto-arm") has the safety argument and what is still missing.
 */

/** Everything the decision reads. The caller gathers it; the policy trusts none of it to be favourable. */
data class AutoArmSituation(
    /** [ForemanSettings.autoArmEnabled]: off unless the user turned it on. */
    val enabled: Boolean,
    val nowWallMillis: Long,
    /** The phone's own shift state. Only Idle may arm. */
    val state: ShiftState,
    /** Face-down (the deterministic detector), screen, charger. */
    val signals: Signals,
    /** How long face-down, screen off and charging have all held without a break. */
    val darkForMillis: Long,
    /** A rig key exists in this phone's Keystore. */
    val rigKeyReady: Boolean,
    /** The Rig is registered on-chain for this key (a clock-in has confirmed before). */
    val rigRegistered: Boolean,
    /** `Rig.shift_open` as read from chain: `arm_shift` requires the last shift to be ended. */
    val chainShiftOpen: Boolean,
    /** The planner's current answer ([PlannerRepository.tonight]). */
    val plan: TonightPlan?,
    /** The wallet's limits as read from the Rig now, not the ones the plan was made with. */
    val limits: WalletLimits?,
    /** When any shift, armed by hand or not, last started on this phone ([xyz.headsdown.feature.shift.ShiftJournal]). */
    val lastShiftArmedWallMillis: Long?,
)

/** Why the phone does not arm by itself right now. The first reason that applies, in this order. */
enum class AutoArmHold {
    /** The user has not turned auto-arm on (the default). */
    SWITCHED_OFF,

    /** The rig is frozen: only the wallet unfreezes it. */
    FROZEN,

    /** A shift is running, or one broke and was not cleared: only Idle arms. */
    NOT_IDLE,
    NO_RIG_KEY,
    RIG_NOT_REGISTERED,

    /** The last shift is still open on-chain; `arm_shift` would be refused. */
    CHAIN_SHIFT_OPEN,
    SCREEN_ON,
    NOT_FACE_DOWN,

    /** Auto-arm is for the Night Shift: on the charger, always. */
    NOT_CHARGING,

    /** Laid down a moment ago: wait until it has stayed there. */
    NOT_SETTLED,
    NO_PLAN,

    /** The plan is too old (or from a clock that has since moved back) to act on. */
    STALE_PLAN,
    NO_WINDOW,

    /** The planner is not confident the phone stays idle for the whole window. */
    WINDOW_NOT_CONFIDENT,
    BEFORE_WINDOW,

    /** Too little of the window is left to be worth a shift. */
    WINDOW_ENDING,

    /** A shift already started on this phone tonight: one night, one shift, and never over a shift the user ended. */
    ALREADY_RAN_TONIGHT,

    /** The wallet's limits are not known right now. */
    NO_LIMITS,

    /** The limits refuse the plan as they stand now (expired, lowered to nothing, window over). */
    REFUSED_BY_LIMITS,

    /** The plan is a Day Shift, which does not require the charger. */
    NOT_A_NIGHT_PLAN,

    /** Tonight's share of the week's budget, or what is left of the week, does not cover one dig. */
    NO_BUDGET,
}

sealed interface AutoArmDecision {
    /**
     * Every condition holds. [plan] has just been re-checked against the wallet's current limits
     * and may be signed with the rig key (PLAN) for `arm_shift`. [nightLamports] is the planner's
     * share for tonight (advisory: on-chain the shift is bounded by `cap_shift` and `cap_week`).
     */
    data class Arm(val plan: SignablePlan, val window: PlannedWindow, val nightLamports: Long?) : AutoArmDecision

    data class Hold(val reason: AutoArmHold) : AutoArmDecision
}

/**
 * Decides whether a phone lying face-down on its charger may arm a shift with the rig key.
 * A pure function of [AutoArmSituation]: no clock, no storage, no chain access of its own.
 *
 * It can only say yes to a plan that is a [SignablePlan] (so it went through
 * `ForemanGate.tighten`), re-checked here against the limits as they are now. The answer is
 * "hold" unless every single condition holds; there is no condition that makes arming more
 * likely by being unknown.
 */
object AutoArmPolicy {
    /** Face-down, screen off and charging must have held this long. */
    const val SETTLE_MILLIS = 60_000L

    /** A window with less than this left is not armed. */
    const val MIN_REMAINING_MILLIS = 30 * 60_000L

    /** A plan older than this is not acted on (the planner looks 24 h ahead). */
    const val MAX_PLAN_AGE_MILLIS = 12 * 60 * 60_000L

    /** A shift that started this long before the window, or any time after, counts as tonight's. */
    const val TONIGHT_LOOKBACK_MILLIS = 4 * 60 * 60_000L

    fun decide(s: AutoArmSituation): AutoArmDecision {
        hold(s)?.let { return AutoArmDecision.Hold(it) }
        // hold() returned null: the plan, its window and the limits are all present.
        val plan = s.plan!!
        val window = plan.window!!
        val limits = s.limits!!
        val now = Math.floorDiv(s.nowWallMillis, 1000L)
        val signable = plan.signable?.retighten(limits, now) ?: return AutoArmDecision.Hold(AutoArmHold.REFUSED_BY_LIMITS)
        if (signable.day) return AutoArmDecision.Hold(AutoArmHold.NOT_A_NIGHT_PLAN)
        val night = plan.tonightLamports
        if (!signable.focusOnly) {
            val dig = signable.digLamports
            val covered = night != null && night >= dig && limits.remainingWeekLamports >= dig && limits.capShiftLamports >= dig
            if (!covered) return AutoArmDecision.Hold(AutoArmHold.NO_BUDGET)
        }
        return AutoArmDecision.Arm(signable, window, night)
    }

    /** The first reason to hold that needs no limit arithmetic, or null. */
    private fun hold(s: AutoArmSituation): AutoArmHold? {
        if (!s.enabled) return AutoArmHold.SWITCHED_OFF
        if (s.state is ShiftState.Frozen) return AutoArmHold.FROZEN
        if (s.state != ShiftState.Idle) return AutoArmHold.NOT_IDLE
        if (!s.rigKeyReady) return AutoArmHold.NO_RIG_KEY
        if (!s.rigRegistered) return AutoArmHold.RIG_NOT_REGISTERED
        if (s.chainShiftOpen) return AutoArmHold.CHAIN_SHIFT_OPEN
        if (s.signals.screenOn) return AutoArmHold.SCREEN_ON
        if (!s.signals.faceDown) return AutoArmHold.NOT_FACE_DOWN
        if (!s.signals.charging) return AutoArmHold.NOT_CHARGING
        if (s.darkForMillis < SETTLE_MILLIS) return AutoArmHold.NOT_SETTLED
        val plan = s.plan ?: return AutoArmHold.NO_PLAN
        val age = s.nowWallMillis - plan.plannedAtWallMillis
        if (age < 0 || age > MAX_PLAN_AGE_MILLIS) return AutoArmHold.STALE_PLAN
        val window = plan.window ?: return AutoArmHold.NO_WINDOW
        if (!window.confident) return AutoArmHold.WINDOW_NOT_CONFIDENT
        if (s.nowWallMillis < window.startWallMillis) return AutoArmHold.BEFORE_WINDOW
        if (s.nowWallMillis > window.endWallMillis - MIN_REMAINING_MILLIS) return AutoArmHold.WINDOW_ENDING
        val last = s.lastShiftArmedWallMillis
        if (last != null && last >= window.startWallMillis - TONIGHT_LOOKBACK_MILLIS) return AutoArmHold.ALREADY_RAN_TONIGHT
        if (s.limits == null) return AutoArmHold.NO_LIMITS
        if (plan.signable == null) return if (plan.gate == PlanGate.NO_LIMITS) AutoArmHold.NO_LIMITS else AutoArmHold.REFUSED_BY_LIMITS
        return null
    }
}

/**
 * Arms a shift with the rig key instead of the wallet: signs the PLAN for [SignablePlan.toShiftPlan]
 * and lands `arm_shift` mode 1 (INTERFACE.md §5). The seam for the chain client; nothing
 * implements it yet (FOREMAN.md, "Auto-arm: what is missing").
 *
 * An implementation must report [PlanArmResult.Armed] only for a transaction confirmed with
 * `err == null`, with the Rig's `shift_id` as read back from chain, and must never retry with a
 * plan other than the one it was given.
 */
fun interface PlanArmer {
    suspend fun arm(plan: SignablePlan): PlanArmResult
}

sealed interface PlanArmResult {
    /** Confirmed on-chain. [spec] is what the shift service is started with. */
    data class Armed(val spec: ShiftSpec) : PlanArmResult

    /** Nothing was armed. [why] is for the debug log (a code, never a key or a signature). */
    data class Refused(val why: String) : PlanArmResult
}
