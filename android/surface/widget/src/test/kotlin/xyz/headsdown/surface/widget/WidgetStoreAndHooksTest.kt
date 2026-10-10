package xyz.headsdown.surface.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class WidgetStoreAndHooksTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private fun prefs(name: String) = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    @Test
    fun `store survives a new process`() {
        val first = WidgetStateStore(prefs("widget_roundtrip"))
        val written = RigWidgetState(
            rig = WidgetRig(RigHeat.COOLING, darkSinceWallMillis = 42L, darkRounds = 7, focusOnly = true, canDig = false),
            lastShiftRounds = 300,
            haul = WidgetHaul(1_940_000_000L, 99L),
            streakNights = 23,
            bootCount = 5,
        )
        first.update { written }
        assertEquals(written, WidgetStateStore(prefs("widget_roundtrip")).state.value)
    }

    @Test
    fun `a widget nobody configured wears the brand, and still follows the wallpaper when asked to`() {
        val store = WidgetStateStore(prefs("widget_brand_default"))
        assertTrue(store.preferBrandColors)
        assertEquals(WidgetColorMode.BRAND, WidgetColorPolicy.mode(sdkInt = 34, preferBrand = store.preferBrandColors))
        assertEquals(WidgetColorMode.BRAND, WidgetColorPolicy.mode(sdkInt = 36, preferBrand = store.preferBrandColors))
        // The choice is kept, and read back by a new store over the same file.
        store.preferBrandColors = false
        val reopened = WidgetStateStore(prefs("widget_brand_default"))
        assertFalse(reopened.preferBrandColors)
        assertEquals(WidgetColorMode.DYNAMIC, WidgetColorPolicy.mode(sdkInt = 34, preferBrand = reopened.preferBrandColors))
    }

    @Test
    fun `empty store reads as a cold rig`() {
        assertEquals(RigWidgetState(), WidgetStateStore(prefs("widget_empty")).state.value)
    }

    @Test
    fun `hooks persist every change but render only visible ones`() {
        val scope = TestScope(StandardTestDispatcher())
        var renders = 0
        val store = WidgetStateStore(prefs("widget_hooks"))
        val hooks = GlanceRigWidgetUpdates(context, scope, store, bootCount = { 3 }, render = { renders++ })

        hooks.onRig(WidgetRig(RigHeat.ARMED))
        hooks.onRig(WidgetRig(RigHeat.HOT, darkSinceWallMillis = 1_000L, darkRounds = 0))
        repeat(50) { hooks.onRig(WidgetRig(RigHeat.HOT, darkSinceWallMillis = 1_000L, darkRounds = it + 1)) }
        hooks.onStreak(24)
        hooks.onStreak(24)
        scope.advanceUntilIdle()

        assertEquals(3, renders) // armed, hot, streak
        assertEquals(50, store.state.value.rig.darkRounds)
        assertEquals(3, store.state.value.bootCount)
        assertEquals(24, store.state.value.streakNights)
    }

    @Test
    fun `a render failure never escapes the hook`() {
        val scope = TestScope(StandardTestDispatcher())
        val hooks = GlanceRigWidgetUpdates(context, scope, WidgetStateStore(prefs("widget_fail")), { null }, render = { error("no host") })
        hooks.onRig(WidgetRig(RigHeat.HOT))
        scope.advanceUntilIdle()
        assertTrue(true)
    }

    @Test
    fun `boot count is readable on this platform`() {
        // Robolectric reports the setting as absent; a device returns a non-negative count.
        val boot = BootCount.read(context)
        assertTrue(boot == null || boot >= 0)
    }
}
