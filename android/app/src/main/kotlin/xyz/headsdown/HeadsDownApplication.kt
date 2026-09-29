package xyz.headsdown

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import xyz.headsdown.feature.reveal.RevealScheduler
import xyz.headsdown.feature.shift.ShiftMode
import xyz.headsdown.feature.shift.ShiftState
import xyz.headsdown.feature.shift.ShiftStatusRepository
import xyz.headsdown.surface.notification.NotificationChannels
import xyz.headsdown.surface.tile.HeadsDownTileService
import javax.inject.Inject

@HiltAndroidApp
class HeadsDownApplication : Application() {

    @Inject lateinit var shiftStatus: ShiftStatusRepository
    @Inject lateinit var revealScheduler: RevealScheduler

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        NotificationChannels.ensure(this)
        appScope.launch {
            var previous: ShiftState = ShiftState.Idle
            shiftStatus.snapshot.collect { snapshot ->
                // Keep the Quick Settings tile in sync with the shift.
                HeadsDownTileService.requestRefresh(this@HeadsDownApplication)
                // A Night Shift that just armed gets its morning reveal, aligned to the user's alarm.
                val state = snapshot.state
                if (state is ShiftState.Armed && previous !is ShiftState.Armed && state.spec.mode == ShiftMode.NIGHT) {
                    revealScheduler.scheduleNext()
                }
                previous = state
            }
        }
    }
}
