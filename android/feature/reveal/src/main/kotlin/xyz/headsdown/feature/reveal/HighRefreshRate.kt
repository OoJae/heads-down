package xyz.headsdown.feature.reveal

import android.app.Activity

/** A display mode, reduced to what the choice needs. */
data class DisplayModeSpec(val id: Int, val width: Int, val height: Int, val refreshRate: Float)

/**
 * Asks for the display's fastest mode at the current resolution while the reveal is on screen,
 * so the board replay runs at 120 Hz on panels that have it (many default apps to 60 Hz).
 */
object HighRefreshRate {

    /** The fastest mode with the same resolution, or null if the current one is already fastest. */
    fun pick(current: DisplayModeSpec, modes: List<DisplayModeSpec>): DisplayModeSpec? =
        modes.filter { it.width == current.width && it.height == current.height }
            .maxByOrNull { it.refreshRate }
            ?.takeIf { it.refreshRate > current.refreshRate + 0.5f }

    fun request(activity: Activity) {
        val display = activity.display ?: return
        val current = display.mode.let { DisplayModeSpec(it.modeId, it.physicalWidth, it.physicalHeight, it.refreshRate) }
        val modes = display.supportedModes.map { DisplayModeSpec(it.modeId, it.physicalWidth, it.physicalHeight, it.refreshRate) }
        val best = pick(current, modes) ?: return
        val window = activity.window
        window.attributes = window.attributes.also { it.preferredDisplayModeId = best.id }
    }
}
