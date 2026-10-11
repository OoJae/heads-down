package xyz.headsdown.ui.slab

import android.app.Application
import android.content.ComponentName
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The lay-down glow in a window: what it asks of the brightness, and that it never takes a touch. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class LayDownGlowComposeTest {
    private val hostActivity = object : ExternalResource() {
        override fun before() {
            val app = ApplicationProvider.getApplicationContext<Application>()
            shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
        }
    }
    private val rule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule val rules: RuleChain = RuleChain.outerRule(hostActivity).around(rule)

    private class FakeTilt : TiltSource {
        val listeners = ArrayList<TiltListener>()
        private var nanos = 5_000_000_000L
        override fun start(listener: TiltListener) {
            listeners += listener
        }

        override fun stop(listener: TiltListener) {
            listeners -= listener
        }

        /** [millis] of 50 Hz samples with the screen normal [theta] degrees from straight up. */
        fun hold(theta: Double, millis: Int) {
            val r = Math.toRadians(theta)
            repeat(millis / 20) {
                nanos += 20_000_000L
                listeners.toList().forEach { it.onSample(0f, (9.81 * Math.sin(r)).toFloat(), (9.81 * Math.cos(r)).toFloat(), nanos) }
            }
        }
    }

    private val brightness: Float get() = rule.activity.window.attributes.screenBrightness
    private val live = SlabConfig(SlabRenderer.Polygon, SlabMotion.Live)

    @Test
    fun `face-down it asks for minimum brightness, and gives the user's back the moment it is lifted`() {
        val tilt = FakeTilt()
        rule.setContent {
            CompositionLocalProvider(LocalSlabConfig provides live, LocalTiltSource provides tilt) {
                LayDownGlow(armed = true, modifier = Modifier.fillMaxSize())
            }
        }
        rule.waitForIdle()
        assertEquals(1, tilt.listeners.size)
        assertEquals(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE, brightness, 0f)

        rule.runOnIdle {
            tilt.hold(45.0, 500)
            // Down, and still warming, holding, fading: the brightness is the user's throughout.
            tilt.hold(180.0, 2_000)
        }
        assertEquals(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE, brightness, 0f)
        rule.runOnIdle { tilt.hold(180.0, 1_500) }
        assertEquals(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF, brightness, 0f)

        // The first samples of the lift.
        rule.runOnIdle { tilt.hold(120.0, 100) }
        assertEquals(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE, brightness, 0f)
        rule.waitForIdle()
    }

    @Test
    fun `leaving the composition while dark gives the brightness back`() {
        val tilt = FakeTilt()
        var shown by mutableStateOf(true)
        rule.setContent {
            CompositionLocalProvider(LocalSlabConfig provides live, LocalTiltSource provides tilt) {
                if (shown) LayDownGlow(armed = true, modifier = Modifier.fillMaxSize())
            }
        }
        rule.runOnIdle { tilt.hold(180.0, 4_000) }
        assertEquals(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF, brightness, 0f)
        rule.runOnIdle { shown = false }
        rule.waitForIdle()
        assertEquals(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE, brightness, 0f)
        assertTrue(tilt.listeners.isEmpty())
    }

    @Test
    fun `disarming while dark gives the brightness back at once, without waiting for a sample`() {
        val tilt = FakeTilt()
        var armed by mutableStateOf(true)
        rule.setContent {
            CompositionLocalProvider(LocalSlabConfig provides live, LocalTiltSource provides tilt) {
                LayDownGlow(armed = armed, modifier = Modifier.fillMaxSize())
            }
        }
        rule.runOnIdle {
            tilt.hold(45.0, 200)
            tilt.hold(180.0, 4_000)
        }
        assertEquals(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF, brightness, 0f)
        // No sample follows: the sensor could be silent from here on.
        rule.runOnIdle { armed = false }
        rule.waitForIdle()
        assertEquals(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE, brightness, 0f)
        // Armed again with the phone still lying there: dark again, and the brightness follows.
        rule.runOnIdle { armed = true }
        rule.runOnIdle { tilt.hold(180.0, 2_000) }
        assertEquals(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF, brightness, 0f)
    }

    @Test
    fun `pausing the activity while dark gives the brightness back and stops listening`() {
        val tilt = FakeTilt()
        rule.setContent {
            CompositionLocalProvider(LocalSlabConfig provides live, LocalTiltSource provides tilt) {
                LayDownGlow(armed = true, modifier = Modifier.fillMaxSize())
            }
        }
        rule.runOnIdle {
            tilt.hold(45.0, 200)
            tilt.hold(180.0, 4_000)
        }
        assertEquals(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF, brightness, 0f)

        // The screen timed out, or another app came to the front.
        rule.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        rule.waitForIdle()
        assertEquals(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE, brightness, 0f)
        assertTrue("still listening while paused", tilt.listeners.isEmpty())

        // Back, with the phone still face-down: it listens again and goes dark again.
        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        rule.waitForIdle()
        assertEquals(1, tilt.listeners.size)
        assertEquals(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE, brightness, 0f)
        rule.runOnIdle { tilt.hold(180.0, 2_000) }
        assertEquals(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF, brightness, 0f)
    }

    @Test
    fun `a rig that is not armed, or a slab that is static, never touches the brightness`() {
        val tilt = FakeTilt()
        var config by mutableStateOf(live)
        rule.setContent {
            CompositionLocalProvider(LocalSlabConfig provides config, LocalTiltSource provides tilt) {
                LayDownGlow(armed = false, modifier = Modifier.fillMaxSize())
            }
        }
        rule.runOnIdle { tilt.hold(180.0, 5_000) }
        assertEquals(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE, brightness, 0f)

        // Static: it does not even listen.
        rule.runOnIdle { config = SlabConfig.Inert }
        rule.waitForIdle()
        assertTrue(tilt.listeners.isEmpty())
    }

    @Test
    fun `with nothing provided it listens to nothing`() {
        rule.setContent { LayDownGlow(armed = true, modifier = Modifier.fillMaxSize()) }
        rule.waitForIdle()
        assertEquals(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE, brightness, 0f)
    }

    @Test
    fun `it never takes a touch, even when it is black`() {
        val tilt = FakeTilt()
        var clicks = 0
        rule.setContent {
            CompositionLocalProvider(LocalSlabConfig provides live, LocalTiltSource provides tilt) {
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().testTag("page").clickable { clicks++ })
                    LayDownGlow(armed = true, modifier = Modifier.fillMaxSize())
                }
            }
        }
        rule.onNodeWithTag("page").performClick()
        rule.runOnIdle { tilt.hold(180.0, 4_000) }
        assertEquals(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF, brightness, 0f)
        rule.onNodeWithTag("page").performClick()
        rule.waitForIdle()
        assertEquals(2, clicks)
    }
}
