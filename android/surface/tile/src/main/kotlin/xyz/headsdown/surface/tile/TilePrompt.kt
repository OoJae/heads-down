package xyz.headsdown.surface.tile

import android.app.StatusBarManager
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.RequiresApi

enum class TileAddOutcome { ADDED, ALREADY_ADDED, NOT_ADDED, UNSUPPORTED, ERROR }

object TileAddResult {
    /** `StatusBarManager.TILE_ADD_REQUEST_*` -> outcome. */
    fun map(code: Int): TileAddOutcome = when (code) {
        StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> TileAddOutcome.ADDED
        StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> TileAddOutcome.ALREADY_ADDED
        StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED -> TileAddOutcome.NOT_ADDED
        else -> TileAddOutcome.ERROR
    }
}

/** Onboarding helper: the system "Add Heads Down to Quick Settings?" prompt (Android 13+). */
object TilePrompt {
    @get:ChecksSdkIntAtLeast(api = Build.VERSION_CODES.TIRAMISU)
    val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    /** Must be called while our app is in the foreground. Android 12 users add the tile by hand. */
    fun request(context: Context, onResult: (TileAddOutcome) -> Unit) {
        if (Build.VERSION.SDK_INT < 33) return onResult(TileAddOutcome.UNSUPPORTED)
        requestApi33(context, onResult)
    }

    @RequiresApi(33)
    private fun requestApi33(context: Context, onResult: (TileAddOutcome) -> Unit) {
        val statusBar = context.getSystemService(StatusBarManager::class.java) ?: return onResult(TileAddOutcome.ERROR)
        statusBar.requestAddTileService(
            HeadsDownTileService.component(context),
            TileRenderer.LABEL,
            Icon.createWithResource(context, R.drawable.ic_tile_rig),
            context.mainExecutor,
        ) { code -> onResult(TileAddResult.map(code)) }
    }
}
