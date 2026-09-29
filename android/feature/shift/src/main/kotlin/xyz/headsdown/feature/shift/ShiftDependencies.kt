package xyz.headsdown.feature.shift

import xyz.headsdown.core.keys.HeartbeatSigner
import xyz.headsdown.surface.notification.RigNotificationState
import xyz.headsdown.surface.notification.RigPhase

/** Supplies the Keystore-backed signer, or null when no rig key has been created yet. */
fun interface RigSignerProvider {
    fun heartbeatSigner(): HeartbeatSigner?
}

/** Supplies the program + Rig account binding (all-zero until `register_rig` confirms). */
fun interface RigBindingProvider {
    fun current(): RigBinding
}

/** Maps the shift snapshot onto the notification module's neutral model. */
internal fun ShiftSnapshot.toNotificationState(): RigNotificationState {
    val phase = when (state) {
        is ShiftState.Armed -> RigPhase.ARMED
        is ShiftState.Down -> RigPhase.HOT
        is ShiftState.Cooling -> RigPhase.COOLING
        is ShiftState.Frozen -> RigPhase.FROZEN
        is ShiftState.Broken, ShiftState.Idle -> RigPhase.COLD
    }
    val spec = state.spec
    return RigNotificationState(
        phase = phase,
        darkRounds = darkRounds,
        plannedRounds = spec?.plannedRounds,
        darkSinceWallMillis = darkSinceWallMillis,
        focusOnly = spec?.mode == ShiftMode.FOCUS_ONLY,
    )
}
