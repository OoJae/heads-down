package xyz.headsdown.rig

import xyz.headsdown.feature.shift.CoolReason
import xyz.headsdown.feature.shift.ShiftMode
import xyz.headsdown.feature.shift.ShiftSnapshot
import xyz.headsdown.feature.shift.ShiftState
import xyz.headsdown.feature.shift.spec
import xyz.headsdown.surface.haptics.HapticCue
import xyz.headsdown.surface.widget.RigHeat
import xyz.headsdown.surface.widget.WidgetRig

/**
 * Maps the shift onto the surfaces that do not depend on feature/shift: the widget's neutral
 * model and the haptic cues. Pure, so the "never buzz per round at night" rule is testable here.
 */
object ShiftSurfaces {

    fun widgetRig(snapshot: ShiftSnapshot): WidgetRig {
        val state = snapshot.state
        return WidgetRig(
            heat = when (state) {
                ShiftState.Idle, is ShiftState.Broken -> RigHeat.COLD
                is ShiftState.Armed -> RigHeat.ARMED
                is ShiftState.Down -> RigHeat.HOT
                is ShiftState.Cooling -> RigHeat.COOLING
                is ShiftState.Frozen -> RigHeat.FROZEN
            },
            darkSinceWallMillis = snapshot.darkSinceWallMillis,
            darkRounds = snapshot.darkRounds.coerceAtLeast(0),
            focusOnly = state.spec?.mode == ShiftMode.FOCUS_ONLY,
            // Unsigned or unregistered heartbeats can never verify on-chain: no digs.
            canDig = snapshot.signing && !snapshot.localOnly,
        )
    }

    /**
     * The only two shift-time cues, both caused by the user:
     * - the arm thunk, once, when the rig first goes hot after arming (the phone was just laid down);
     * - the cooling tick when the user lifts the phone or wakes the screen.
     * Re-going dark after cooling is silent (3 am), a charger that slips is silent, and nothing
     * is ever tied to a round.
     */
    fun cueFor(previous: ShiftState, next: ShiftState): HapticCue? = when {
        previous is ShiftState.Armed && next is ShiftState.Down -> HapticCue.ARM_THUNK
        previous is ShiftState.Down && next is ShiftState.Cooling &&
            (next.reason == CoolReason.LIFTED || next.reason == CoolReason.SCREEN_ON) -> HapticCue.COOLING_TICK
        else -> null
    }
}
