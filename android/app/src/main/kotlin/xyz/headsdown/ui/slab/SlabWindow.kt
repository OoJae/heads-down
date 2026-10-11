package xyz.headsdown.ui.slab

import android.hardware.display.DisplayManager
import android.view.Display
import android.view.Window
import kotlin.math.abs

/**
 * The window's side of a live slab. The shader is budgeted for 60 frames a second: on a panel
 * that can also run at 90 or 120 Hz (the Redmi 14C can), the activity that shows a live slab
 * asks for the 60 Hz mode while it is on screen and lets go when it is not.
 */
object SlabWindow {
    const val TARGET_HZ = 60f

    /**
     * Asks for the display mode nearest [TARGET_HZ] at the resolution the display is in now.
     * Returns the mode's id, or 0 if there was nothing to choose (one mode, or no display).
     * Call it from `onResume` when the config's motion is Live.
     */
    fun pinSixtyHertz(window: Window): Int {
        val display = try {
            window.context.display
        } catch (_: UnsupportedOperationException) {
            null // a window whose context is not a visual one: the phone's own display, then
        } ?: window.context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
            ?: return 0
        val modes = display.supportedModes
        if (modes.size < 2) return 0
        val current = display.mode
        val mode = modes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .minByOrNull { abs(it.refreshRate - TARGET_HZ) }
            ?: return 0
        val params = window.attributes
        if (params.preferredDisplayModeId != mode.modeId) {
            params.preferredDisplayModeId = mode.modeId
            window.attributes = params
        }
        return mode.modeId
    }

    /** Gives the choice of display mode back to the system. Call it from `onPause`. */
    fun unpin(window: Window) {
        val params = window.attributes
        if (params.preferredDisplayModeId != 0) {
            params.preferredDisplayModeId = 0
            window.attributes = params
        }
    }
}
