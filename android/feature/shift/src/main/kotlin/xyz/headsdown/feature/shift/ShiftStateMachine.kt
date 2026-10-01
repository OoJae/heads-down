package xyz.headsdown.feature.shift

/** Monotonic milliseconds. Production: `SystemClock.elapsedRealtime()`; tests: a fake. */
fun interface MonotonicClock {
    fun nowMillis(): Long
}

sealed interface ShiftEvent {
    data class Arm(val spec: ShiftSpec) : ShiftEvent

    /** Debounced accelerometer verdict from [FaceDownDetector]. Soft signal. */
    data class Posture(val faceDown: Boolean) : ShiftEvent

    /** `ACTION_SCREEN_ON`. Hard signal: cools immediately, no sensor debounce. */
    data object ScreenOn : ShiftEvent

    /** `ACTION_SCREEN_OFF`. */
    data object ScreenOff : ShiftEvent

    /** `ACTION_USER_PRESENT` (device unlocked). Hard signal: breaks immediately, no grace. */
    data object UserPresent : ShiftEvent

    /** Charger plugged/unplugged (`BatteryManager.EXTRA_PLUGGED != 0`). Hard signal. */
    data class Power(val charging: Boolean) : ShiftEvent

    /** Time passing; used to expire the cooling grace window. */
    data object Tick : ShiftEvent

    /** Device-key freeze (P-256-signed FREEZE). */
    data object Freeze : ShiftEvent

    /** Only dispatched after the wallet-signed unfreeze is confirmed on-chain. */
    data object Unfreeze : ShiftEvent

    /** User ends the shift gracefully (clock-out / tile). */
    data object End : ShiftEvent
}

sealed interface ShiftEffect {
    /** Sign and relay a P-256 BREAK (kind 2) so the chain stops digging now. */
    data class SignBreak(val spec: ShiftSpec, val reason: BreakReason) : ShiftEffect

    /** Sign and relay a P-256 FREEZE. */
    data class SignFreeze(val shiftId: Long?) : ShiftEffect

    /** Deliver a [ShiftEvent.Tick] at [atMillis] (the grace deadline). */
    data class ScheduleTick(val atMillis: Long) : ShiftEffect

    /** Rig just went hot (arm thunk haptic). */
    data object WentDark : ShiftEffect

    /** Rig left DOWN and is in its grace window (cooling tick haptic). */
    data class CoolingStarted(val reason: CoolReason) : ShiftEffect

    data object ShiftEnded : ShiftEffect
}

data class Transition(
    val from: ShiftState,
    val to: ShiftState,
    val signals: Signals,
    val effects: List<ShiftEffect>,
) {
    /**
     * The phone was picked up after going dark: lifted out of Down, unlocked, or the shift was
     * ended by hand while running. Screen-on alone (a notification) is not a pickup.
     */
    val isPickup: Boolean
        get() = effects.any { it is ShiftEffect.CoolingStarted && it.reason == CoolReason.LIFTED } ||
            (to is ShiftState.Broken && from !is ShiftState.Broken && (to.reason == BreakReason.LIFTED || to.reason == BreakReason.UNLOCKED)) ||
            (effects.any { it == ShiftEffect.ShiftEnded } && (from is ShiftState.Down || from is ShiftState.Cooling))
}

/**
 * The Heads Down shift lifecycle, as a pure, clock-injected state machine:
 *
 * ```
 *   Idle --Arm--> Armed --dark--> Down --not dark--> Cooling --dark again < 10 s--> Down
 *                                   |                   |
 *                                   |                   +--grace expired--> Broken --Arm--> Armed
 *                                   +--unlock (hard)-----------------------> Broken
 *   any --Freeze--> Frozen --Unfreeze (wallet)--> Idle        any --End--> Idle
 * ```
 *
 * "Dark" = face-down (accelerometer) AND screen off AND (charging, if the mode requires it).
 *
 * Signal policy:
 * - Accelerometer posture is a *soft* signal: bumps are filtered upstream by
 *   [FaceDownDetector]'s hysteresis, and anything that still leaks through only cools.
 * - `SCREEN_ON` and unplugging are *hard* signals: they cool instantly without waiting for a
 *   sensor debounce. The grace window then absorbs a notification waking the screen of a
 *   phone that never moved.
 * - `USER_PRESENT` (unlock) cannot happen by accident: it breaks immediately.
 * - Late events cannot resurrect an expired grace window: every event first applies expiry,
 *   so a posture sample that arrives after the deadline (CPU was asleep) still breaks.
 *
 * Gyroscope and proximity are deliberately not inputs: the Redmi 14C has no gyroscope and a
 * virtual proximity sensor. They may be added as optional corroboration where real.
 */
class ShiftStateMachine(
    private val clock: MonotonicClock,
    private val graceMillis: Long = DEFAULT_GRACE_MILLIS,
    initialSignals: Signals = Signals(),
) {
    init {
        require(graceMillis > 0)
    }

    var state: ShiftState = ShiftState.Idle
        private set

    var signals: Signals = initialSignals
        private set

    fun dispatch(event: ShiftEvent): Transition {
        val from = state
        val (to, newSignals, effects) = reduce(from, signals, event, clock.nowMillis(), graceMillis)
        state = to
        signals = newSignals
        return Transition(from, to, newSignals, effects)
    }

    companion object {
        const val DEFAULT_GRACE_MILLIS = 10_000L

        internal fun reduce(
            state: ShiftState,
            signals: Signals,
            event: ShiftEvent,
            now: Long,
            grace: Long,
        ): Triple<ShiftState, Signals, List<ShiftEffect>> {
            val sig = updateSignals(signals, event)
            val effects = mutableListOf<ShiftEffect>()

            if (state is ShiftState.Frozen) {
                val next = if (event == ShiftEvent.Unfreeze) ShiftState.Idle else state
                return Triple(next, sig, effects)
            }
            if (event == ShiftEvent.Freeze) {
                effects += ShiftEffect.SignFreeze(state.spec?.shiftId)
                return Triple(ShiftState.Frozen(state.spec?.shiftId, now), sig, effects)
            }

            // Expire the grace window before looking at the event.
            var current = state
            if (current is ShiftState.Cooling && now >= current.deadline) {
                val reason = current.reason.toBreakReason()
                effects += ShiftEffect.SignBreak(current.spec, reason)
                current = ShiftState.Broken(current.spec, current.deadline, reason)
            }

            val next: ShiftState = when (event) {
                ShiftEvent.End -> when (current) {
                    ShiftState.Idle -> current
                    else -> {
                        effects += ShiftEffect.ShiftEnded
                        ShiftState.Idle
                    }
                }

                is ShiftEvent.Arm -> when (current) {
                    ShiftState.Idle, is ShiftState.Broken ->
                        settle(ShiftState.Armed(event.spec, now), sig, now, grace, effects)
                    else -> current // already in a shift: arming again is a no-op
                }

                ShiftEvent.UserPresent -> when (current) {
                    is ShiftState.Down -> breakNow(current.spec, BreakReason.UNLOCKED, now, effects)
                    is ShiftState.Cooling -> breakNow(current.spec, BreakReason.UNLOCKED, now, effects)
                    else -> current
                }

                else -> settle(current, sig, now, grace, effects)
            }
            return Triple(next, sig, effects)
        }

        private fun updateSignals(s: Signals, event: ShiftEvent): Signals = when (event) {
            is ShiftEvent.Posture -> s.copy(faceDown = event.faceDown)
            ShiftEvent.ScreenOn, ShiftEvent.UserPresent -> s.copy(screenOn = true)
            ShiftEvent.ScreenOff -> s.copy(screenOn = false)
            is ShiftEvent.Power -> s.copy(charging = event.charging)
            else -> s
        }

        /** Moves between Armed / Down / Cooling according to the current signals. */
        private fun settle(
            state: ShiftState,
            sig: Signals,
            now: Long,
            grace: Long,
            effects: MutableList<ShiftEffect>,
        ): ShiftState = when (state) {
            is ShiftState.Armed -> if (sig.isDark(state.spec)) {
                effects += ShiftEffect.WentDark
                ShiftState.Down(state.spec, since = now, firstDownAt = now)
            } else state

            is ShiftState.Down -> {
                val reason = sig.coolReason(state.spec)
                if (reason == null) state else {
                    val deadline = now + grace
                    effects += ShiftEffect.CoolingStarted(reason)
                    effects += ShiftEffect.ScheduleTick(deadline)
                    ShiftState.Cooling(state.spec, since = now, deadline = deadline, reason = reason, firstDownAt = state.firstDownAt)
                }
            }

            is ShiftState.Cooling -> if (sig.isDark(state.spec)) {
                effects += ShiftEffect.WentDark
                ShiftState.Down(state.spec, since = now, firstDownAt = state.firstDownAt)
            } else state

            ShiftState.Idle, is ShiftState.Broken, is ShiftState.Frozen -> state
        }

        private fun breakNow(
            spec: ShiftSpec,
            reason: BreakReason,
            now: Long,
            effects: MutableList<ShiftEffect>,
        ): ShiftState {
            effects += ShiftEffect.SignBreak(spec, reason)
            return ShiftState.Broken(spec, now, reason)
        }

        private fun CoolReason.toBreakReason(): BreakReason = when (this) {
            CoolReason.LIFTED -> BreakReason.LIFTED
            CoolReason.SCREEN_ON -> BreakReason.SCREEN_ON
            CoolReason.UNPLUGGED -> BreakReason.UNPLUGGED
        }
    }
}
