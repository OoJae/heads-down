package xyz.headsdown.surface.tile

import xyz.headsdown.feature.shift.ShiftSnapshot
import xyz.headsdown.feature.shift.ShiftState

enum class TileVisualState { ACTIVE, INACTIVE }

data class TileModel(val state: TileVisualState, val label: String, val subtitle: String)

/**
 * Pure mapping from the shift snapshot to the Quick Settings tile. The subtitle only ever
 * counts *up* (time dark), never down, and never shows prices.
 */
object TileRenderer {
    const val LABEL = "Heads Down"

    fun render(snapshot: ShiftSnapshot, nowWallMillis: Long): TileModel = when (val s = snapshot.state) {
        ShiftState.Idle -> TileModel(TileVisualState.INACTIVE, LABEL, "rig cold")
        is ShiftState.Armed -> TileModel(TileVisualState.ACTIVE, LABEL, "armed · lay face-down")
        is ShiftState.Down -> {
            val since = snapshot.darkSinceWallMillis
            val subtitle = if (since == null) "rig hot" else "rig hot · ${elapsed(nowWallMillis - since)}"
            TileModel(TileVisualState.ACTIVE, LABEL, subtitle)
        }
        is ShiftState.Cooling -> TileModel(TileVisualState.ACTIVE, LABEL, "cooling")
        is ShiftState.Broken -> TileModel(TileVisualState.INACTIVE, LABEL, "cold · tap to re-arm")
        is ShiftState.Frozen -> TileModel(TileVisualState.INACTIVE, LABEL, if (s.shiftId == null) "frozen" else "frozen · open app")
    }

    /** `h:mm`, clamped at zero (clock skew must not print a negative time). */
    fun elapsed(millis: Long): String {
        val minutes = (millis.coerceAtLeast(0) / 60_000)
        return "%d:%02d".format(minutes / 60, minutes % 60)
    }
}
