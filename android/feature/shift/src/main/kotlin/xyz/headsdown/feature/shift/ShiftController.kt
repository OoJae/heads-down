package xyz.headsdown.feature.shift

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The only way to start/stop a shift. Must be called while the app is in the foreground
 * (trampoline, home screen): Android 12+ forbids starting a foreground service from the
 * background, and the shift is user-initiated by definition.
 */
@Singleton
class ShiftController @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val repository: ShiftStatusRepository,
) {
    fun arm(spec: ShiftSpec) {
        repository.requestArm(spec)
        ContextCompat.startForegroundService(context, serviceIntent(ShiftForegroundService.ACTION_ARM))
    }

    fun end() {
        if (repository.snapshot.value.state == ShiftState.Idle) return
        ContextCompat.startForegroundService(context, serviceIntent(ShiftForegroundService.ACTION_END))
    }

    fun freeze() {
        if (repository.snapshot.value.state is ShiftState.Frozen) return
        ContextCompat.startForegroundService(context, serviceIntent(ShiftForegroundService.ACTION_FREEZE))
    }

    private fun serviceIntent(action: String) =
        Intent(context, ShiftForegroundService::class.java).setAction(action)
}
