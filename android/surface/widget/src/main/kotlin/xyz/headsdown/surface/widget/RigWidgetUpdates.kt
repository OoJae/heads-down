package xyz.headsdown.surface.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The update hooks the shift service (through the app's shift observer), the reveal and the
 * clock-in call. Neutral types only, so no module below the app depends on this one.
 *
 * Every call is non-blocking and safe from any thread: the state is persisted at once, and a
 * render is scheduled only when something visible changed ([WidgetUpdatePolicy]). A per-round
 * dark-rounds increment is stored but not rendered: the RemoteViews chronometer ticks on its own.
 */
interface RigWidgetUpdates {
    fun onRig(rig: WidgetRig)

    /** A real, on-chain haul. Never pass sample data here. */
    fun onHaul(haul: WidgetHaul)

    /** Nights in a row, as read from the on-chain Rig. */
    fun onStreak(nights: Int)
}

/** [RigWidgetUpdates] that writes [WidgetStateStore] and asks Glance to re-render. */
class GlanceRigWidgetUpdates(
    context: Context,
    private val scope: CoroutineScope,
    private val store: WidgetStateStore = WidgetStateStore.get(context),
    private val bootCount: () -> Int? = { BootCount.read(context) },
    private val render: suspend (Context) -> Unit = { RigWidget().updateAll(it) },
) : RigWidgetUpdates {
    private val appContext = context.applicationContext

    override fun onRig(rig: WidgetRig) = apply { WidgetStateReducer.onRig(it, rig, bootCount()) }

    override fun onHaul(haul: WidgetHaul) = apply { WidgetStateReducer.onHaul(it, haul) }

    override fun onStreak(nights: Int) = apply { WidgetStateReducer.onStreak(it, nights) }

    private fun apply(transform: (RigWidgetState) -> RigWidgetState) {
        val (before, after) = store.update(transform)
        if (WidgetUpdatePolicy.needsRender(before, after)) {
            // No widget placed means no ids: updateAll is then a cheap no-op.
            scope.launch { runCatching { render(appContext) } }
        }
    }
}
