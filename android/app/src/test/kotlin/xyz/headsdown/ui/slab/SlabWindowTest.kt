package xyz.headsdown.ui.slab

import android.app.Application
import android.content.ComponentName
import android.hardware.display.DisplayManager
import android.view.Display
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDisplayManager

/** The 60 Hz pin: which display mode a live slab's window asks for, and that it lets go. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SlabWindowTest {

    private fun activity(): ComponentActivity {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
        return Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
    }

    private fun mode(id: Int, width: Int, height: Int, hz: Float): Display.Mode =
        Display.Mode::class.java
            .getConstructor(Int::class.java, Int::class.java, Int::class.java, Float::class.java)
            .newInstance(id, width, height, hz)

    @Test
    fun `with one display mode there is nothing to pin`() {
        val activity = activity()
        assertEquals(0, SlabWindow.pinSixtyHertz(activity.window))
        assertEquals(0, activity.window.attributes.preferredDisplayModeId)
        SlabWindow.unpin(activity.window)
        assertEquals(0, activity.window.attributes.preferredDisplayModeId)
    }

    @Test
    fun `on a 60, 90 and 120 Hz panel it asks for the 60 Hz mode at the current resolution, then lets go`() {
        val activity = activity()
        val display = activity.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val current = display.mode
        val w = current.physicalWidth
        val h = current.physicalHeight
        // The current mode keeps its id; 90 and 120 Hz at this resolution and a 60 Hz mode at another one.
        val modes = arrayOf(
            mode(current.modeId, w, h, 120f),
            mode(current.modeId + 1, w, h, 90f),
            mode(current.modeId + 2, w, h, 60f),
            mode(current.modeId + 3, w / 2, h / 2, 60f),
        )
        ShadowDisplayManager.setSupportedModes(display.displayId, *modes)
        assertTrue(display.supportedModes.size == 4)

        assertEquals(current.modeId + 2, SlabWindow.pinSixtyHertz(activity.window))
        assertEquals(current.modeId + 2, activity.window.attributes.preferredDisplayModeId)
        // Again changes nothing.
        assertEquals(current.modeId + 2, SlabWindow.pinSixtyHertz(activity.window))

        SlabWindow.unpin(activity.window)
        assertEquals(0, activity.window.attributes.preferredDisplayModeId)
    }
}
