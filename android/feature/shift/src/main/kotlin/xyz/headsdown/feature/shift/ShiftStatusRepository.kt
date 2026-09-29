package xyz.headsdown.feature.shift

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/** What every surface (home screen, tile, notification) renders. */
data class ShiftSnapshot(
    val state: ShiftState,
    /** Heartbeats signed and handed to the intake this shift. */
    val darkRounds: Int = 0,
    /** Wall-clock time the rig first went hot this shift (count-up display only). */
    val darkSinceWallMillis: Long? = null,
    val lastHeartbeatWallMillis: Long? = null,
    /** False when no rig key exists yet: the shift runs, but no heartbeats are signed. */
    val signing: Boolean = false,
    /** True while heartbeats are bound to the all-zero (unregistered) rig: they can never dig. */
    val localOnly: Boolean = true,
) {
    companion object {
        val IDLE = ShiftSnapshot(ShiftState.Idle)
    }
}

/**
 * In-process source of truth for the shift, written only by [ShiftForegroundService].
 * If the process dies, this resets to [ShiftSnapshot.IDLE], which is also the truth: no
 * process, no heartbeats, no digs.
 */
@Singleton
class ShiftStatusRepository @Inject constructor() {
    private val _snapshot = MutableStateFlow(ShiftSnapshot.IDLE)
    val snapshot: StateFlow<ShiftSnapshot> = _snapshot.asStateFlow()

    private val pendingArm = AtomicReference<ShiftSpec?>(null)

    /**
     * The service reads the spec from here instead of from Intent extras, so no caller can
     * inject a shift spec through an Intent.
     */
    internal fun requestArm(spec: ShiftSpec) = pendingArm.set(spec)

    internal fun takePendingArm(): ShiftSpec? = pendingArm.getAndSet(null)

    internal fun publish(snapshot: ShiftSnapshot) {
        _snapshot.value = snapshot
    }
}
