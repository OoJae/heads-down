package xyz.headsdown.surface.tile

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.service.quicksettings.PendingIntentActivityWrapper
import androidx.core.service.quicksettings.TileServiceCompat
import dagger.hilt.android.AndroidEntryPoint
import xyz.headsdown.feature.shift.ShiftStatusRepository
import javax.inject.Inject

/**
 * "Heads Down" Quick Settings tile, next to Do Not Disturb.
 *
 * Active tile (`ACTIVE_TILE` meta-data): the system binds it only when we call
 * [requestRefresh] or the user opens the shade. A tap never signs anything here; it opens
 * the non-exported [TrampolineActivity], which hosts the single MWA wallet confirmation.
 * On a locked device the tap first goes through `unlockAndRun` (the wallet and the token
 * vault both need an unlocked device).
 */
@AndroidEntryPoint
class HeadsDownTileService : TileService() {

    @Inject lateinit var repository: ShiftStatusRepository

    override fun onStartListening() {
        super.onStartListening()
        render()
    }

    override fun onClick() {
        super.onClick()
        if (isLocked) unlockAndRun(::openTrampoline) else openTrampoline()
    }

    private fun openTrampoline() {
        val intent = Intent(this, TrampolineActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val wrapper = PendingIntentActivityWrapper(this, REQUEST_CODE, intent, PendingIntent.FLAG_UPDATE_CURRENT, false)
        TileServiceCompat.startActivityAndCollapse(this, wrapper)
    }

    private fun render() {
        val tile = qsTile ?: return
        val model = TileRenderer.render(repository.snapshot.value, System.currentTimeMillis())
        tile.state = when (model.state) {
            TileVisualState.ACTIVE -> Tile.STATE_ACTIVE
            TileVisualState.INACTIVE -> Tile.STATE_INACTIVE
        }
        tile.label = model.label
        tile.subtitle = model.subtitle
        tile.contentDescription = "${model.label}, ${model.subtitle}"
        tile.stateDescription = model.subtitle
        tile.updateTile()
    }

    companion object {
        private const val REQUEST_CODE = 0x5449 // "TI"

        fun component(context: Context) = ComponentName(context, HeadsDownTileService::class.java)

        /** Ask the system to re-bind the tile so it re-renders the current shift state. */
        fun requestRefresh(context: Context) = requestListeningState(context, component(context))
    }
}
