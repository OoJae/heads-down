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
import xyz.headsdown.rig.ShiftSurfaces
import xyz.headsdown.surface.haptics.HapticMoment
import xyz.headsdown.surface.haptics.Haptics
import xyz.headsdown.surface.notification.NotificationChannels
import xyz.headsdown.surface.tile.HeadsDownTileService
import xyz.headsdown.surface.widget.RigWidgetUpdates
import xyz.headsdown.surface.widget.WidgetPreviews
import javax.inject.Inject

@HiltAndroidApp
class HeadsDownApplication : Application() {

    @Inject lateinit var shiftStatus: ShiftStatusRepository
    @Inject lateinit var revealScheduler: RevealScheduler
    @Inject lateinit var widgetUpdates: RigWidgetUpdates
    @Inject lateinit var haptics: Haptics

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        NotificationChannels.ensure(this)
        appScope.launch {
            var previous: ShiftState = ShiftState.Idle
            // The first snapshot of a fresh process is IDLE, which is also the truth: if the OS
            // killed a shift, the widget turns cold here (the widget's 30-minute update starts
            // this process if nothing else does).
            shiftStatus.snapshot.collect { snapshot ->
                // Keep the Quick Settings tile and the home-screen widgets in sync with the shift.
                HeadsDownTileService.requestRefresh(this@HeadsDownApplication)
                widgetUpdates.onRig(ShiftSurfaces.widgetRig(snapshot))
                val state = snapshot.state
                if (state is ShiftState.Armed && previous !is ShiftState.Armed) {
                    haptics.prepare() // basic motors: load the audible thunk before the rig goes hot
                    // A Night Shift that just armed gets its morning reveal, aligned to the user's alarm.
                    if (state.spec.mode == ShiftMode.NIGHT) revealScheduler.scheduleNext()
                }
                ShiftSurfaces.cueFor(previous, state)?.let { haptics.play(it, HapticMoment.SHIFT) }
                previous = state
            }
        }
        // Android 15+: generated widget-picker previews, once per app version (rate-limited).
        appScope.launch(Dispatchers.Default) {
            runCatching { WidgetPreviews.publishIfNeeded(this@HeadsDownApplication, BuildConfig.VERSION_CODE.toLong()) }
        }
    }
}
