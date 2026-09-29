package xyz.headsdown.surface.tile

import android.app.StatusBarManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import xyz.headsdown.feature.shift.BreakReason
import xyz.headsdown.feature.shift.CoolReason
import xyz.headsdown.feature.shift.ShiftMode
import xyz.headsdown.feature.shift.ShiftSnapshot
import xyz.headsdown.feature.shift.ShiftSpec
import xyz.headsdown.feature.shift.ShiftState

class TileRendererTest {
    private val spec = ShiftSpec(1, ShiftMode.NIGHT)
    private val now = 10_000_000L

    private fun render(state: ShiftState, since: Long? = null) =
        TileRenderer.render(ShiftSnapshot(state, darkSinceWallMillis = since), now)

    @Test
    fun `every state renders with the Heads Down label`() {
        val models = listOf(
            render(ShiftState.Idle),
            render(ShiftState.Armed(spec, 0)),
            render(ShiftState.Down(spec, 0, 0), since = now - 72 * 60_000),
            render(ShiftState.Cooling(spec, 0, 1, CoolReason.LIFTED, 0)),
            render(ShiftState.Broken(spec, 0, BreakReason.UNLOCKED)),
            render(ShiftState.Frozen(1, 0)),
        )
        models.forEach { assertEquals("Heads Down", it.label) }
        assertEquals(
            listOf("rig cold", "armed · lay face-down", "rig hot · 1:12", "cooling", "cold · tap to re-arm", "frozen · open app"),
            models.map { it.subtitle },
        )
        assertEquals(
            listOf(TileVisualState.INACTIVE, TileVisualState.ACTIVE, TileVisualState.ACTIVE, TileVisualState.ACTIVE, TileVisualState.INACTIVE, TileVisualState.INACTIVE),
            models.map { it.state },
        )
    }

    @Test
    fun `elapsed counts up and never goes negative`() {
        assertEquals("0:00", TileRenderer.elapsed(-5_000))
        assertEquals("0:59", TileRenderer.elapsed(59 * 60_000 + 59_999))
        assertEquals("8:05", TileRenderer.elapsed((8 * 60 + 5) * 60_000L))
        assertEquals("rig hot", render(ShiftState.Down(spec, 0, 0), since = null).subtitle)
    }

    @Test
    fun `cooling subtitle is not a countdown`() {
        assertFalse(render(ShiftState.Cooling(spec, 0, 10_000, CoolReason.SCREEN_ON, 0)).subtitle.any(Char::isDigit))
    }

    @Test
    fun `tile add results map from StatusBarManager codes`() {
        assertEquals(TileAddOutcome.ADDED, TileAddResult.map(StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED))
        assertEquals(TileAddOutcome.ALREADY_ADDED, TileAddResult.map(StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED))
        assertEquals(TileAddOutcome.NOT_ADDED, TileAddResult.map(StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED))
        assertEquals(TileAddOutcome.ERROR, TileAddResult.map(StatusBarManager.TILE_ADD_REQUEST_ERROR_APP_NOT_IN_FOREGROUND))
    }
}
