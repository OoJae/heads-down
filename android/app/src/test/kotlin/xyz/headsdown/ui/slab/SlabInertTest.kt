package xyz.headsdown.ui.slab

import android.app.Application
import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RuntimeShader
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.InternalComposeApi
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowSensor
import org.robolectric.util.ReflectionHelpers

/**
 * What the slab must NOT do, counted from outside its own code: every `RuntimeShader` the
 * platform is asked for (a shadow of the class itself, not the renderer's own counter), every
 * listener the sensor service holds, every frame a coroutine is waiting for.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, shadows = [SlabInertTest.CountingRuntimeShader::class])
class SlabInertTest {

    /**
     * Counts constructions of `android.graphics.RuntimeShader`, wherever they come from, and then
     * lets the real constructor run (its native half is a stub here, so any source "compiles").
     */
    @Implements(RuntimeShader::class)
    class CountingRuntimeShader {
        @RealObject private lateinit var real: RuntimeShader

        @Implementation
        @Suppress("TestFunctionName", "unused")
        protected fun __constructor__(shader: String) {
            constructed++
            Shadow.invokeConstructor(RuntimeShader::class.java, real, ReflectionHelpers.ClassParameter.from(String::class.java, shader))
        }

        companion object {
            @JvmStatic var constructed = 0
        }
    }

    private val hostActivity = object : ExternalResource() {
        override fun before() {
            val app = ApplicationProvider.getApplicationContext<Application>()
            shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
        }
    }
    private val rule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule val rules: RuleChain = RuleChain.outerRule(hostActivity).around(rule)

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Before
    fun aPhoneWithEverySensor() {
        // Every sensor the tilt source could ask for exists, so a registration would succeed.
        val sensors = shadowOf(app.getSystemService(SensorManager::class.java))
        listOf(Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_GRAVITY, Sensor.TYPE_GYROSCOPE).forEach {
            sensors.addSensor(ShadowSensor.newInstance(it))
        }
        CountingRuntimeShader.constructed = 0
    }

    private val listeners get() = shadowOf(app.getSystemService(SensorManager::class.java)).listeners

    /** True while any coroutine of any composition waits for a frame, or anything is invalid. */
    @OptIn(InternalComposeApi::class)
    private fun pendingWork(): Boolean = Recomposer.runningRecomposers.value.any { it.hasPendingWork }

    /** Compose's own view: the one it invalidates when something it drew has changed. */
    private val composeView: View
        get() {
            var view: View = rule.activity.findViewById<ViewGroup>(android.R.id.content)
            while (view is ViewGroup && view.javaClass.simpleName != "AndroidComposeView") view = view.getChildAt(0)
            return view
        }
    private val canvas by lazy { Canvas(Bitmap.createBitmap(720, 1280, Bitmap.Config.ARGB_8888)) }

    /**
     * Robolectric has no display, so nothing draws unless asked. This is the display: it draws
     * the view if, and only if, Compose invalidated it since the last time, exactly as a frame
     * would. Returns whether it drew.
     */
    private fun drawIfInvalidated(): Boolean = rule.runOnUiThread<Boolean> {
        val view = composeView
        val shadow = shadowOf(view)
        if (!shadow.wasInvalidated()) {
            false
        } else {
            shadow.clearWasInvalidated()
            view.draw(canvas)
            true
        }
    }

    /** One frame of the test clock, then the display. */
    private fun frame(): Boolean {
        rule.mainClock.advanceTimeByFrame()
        return drawIfInvalidated()
    }

    /** [millis] of frames; returns how many of them were drawn. */
    private fun frames(millis: Int): Int {
        var drawn = 0
        repeat(millis / 16) { if (frame()) drawn++ }
        return drawn
    }

    private class FakeTilt : TiltSource {
        val listeners = ArrayList<TiltListener>()
        private var nanos = 1_000_000_000L
        override fun start(listener: TiltListener) {
            listeners += listener
        }

        override fun stop(listener: TiltListener) {
            listeners -= listener
        }

        fun sample(theta: Double) {
            val r = Math.toRadians(theta)
            nanos += 20_000_000L
            listeners.toList().forEach { it.onSample(0f, (9.81 * Math.sin(r)).toFloat(), (9.81 * Math.cos(r)).toFloat(), nanos) }
        }
    }

    @Test
    fun `with nothing provided, the hero and the glow build no shader, hear no sensor and leave nothing pending`() {
        val built = SlabProbe.shadersBuilt
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Box(Modifier.fillMaxSize()) {
                // Everything that could start something is asked for: a drag, the launch, an armed glow.
                SlabHero(
                    state = SlabState.Cooling, heat = 3, modifier = Modifier.size(320.dp).testTag("hero"),
                    interactive = true, enter = true, contentDescription = "The slab",
                )
                LayDownGlow(armed = true, modifier = Modifier.fillMaxSize())
            }
        }
        rule.waitForIdle()
        // The first frame: the slab is drawn, once.
        val before = SlabProbe.draws
        assertTrue("the first frame was never asked for", drawIfInvalidated())
        val draws = SlabProbe.draws
        assertTrue("the first frame did not draw the hero", draws > before)
        val compositions = SlabProbe.compositions
        val callbacks = SlabProbe.frameCallbacks
        assertTrue("the hero was never composed", compositions > 0)

        // Two seconds of frames on a paused clock, a tap and a drag, two seconds more: a loop, a
        // spring, a ramp or a sensor follow would ask for a frame and be drawn.
        var drawn = frames(2_000)
        rule.onNodeWithTag("hero").performTouchInput {
            down(center)
            moveBy(Offset(80f, 0f))
            moveBy(Offset(80f, -40f))
            up()
        }
        drawn += frames(2_000)
        rule.waitForIdle()

        assertEquals("a RuntimeShader was constructed", 0, CountingRuntimeShader.constructed)
        assertEquals(built, SlabProbe.shadersBuilt)
        assertTrue("a sensor listener is registered: $listeners", listeners.isEmpty())
        assertEquals("a frame callback ran", callbacks, SlabProbe.frameCallbacks)
        assertEquals("a frame was asked for with nothing moving", 0, drawn)
        assertEquals("the slab was drawn again with nothing moving", draws, SlabProbe.draws)
        assertEquals("the slab was composed again with nothing changing", compositions, SlabProbe.compositions)
        assertTrue("a coroutine is waiting for a frame, or a composition is invalid", !pendingWork())
        assertTrue("the main looper has work queued", shadowOf(Looper.getMainLooper()).isIdle)
        assertEquals(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE, rule.activity.window.attributes.screenBrightness, 0f)

        // And with the clock running the composition is idle: waitForIdle returns.
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        assertTrue(!pendingWork())
    }

    @Test
    fun `a real tilt source with nothing live provided is never started`() {
        // The Activity's source is provided, but the config is still the inert default: no listener.
        val source = SensorTiltSource(app)
        rule.setContent {
            CompositionLocalProvider(LocalTiltSource provides source) {
                Box(Modifier.fillMaxSize()) {
                    SlabHero(state = SlabState.Armed, heat = 0, modifier = Modifier.size(320.dp))
                    LayDownGlow(armed = true, modifier = Modifier.fillMaxSize())
                }
            }
        }
        rule.waitForIdle()
        assertTrue("a sensor listener is registered: $listeners", listeners.isEmpty())
        assertEquals(0, CountingRuntimeShader.constructed)
    }

    @Test
    fun `live, one sensor listener serves the hero and the glow, and both let go of it`() {
        val source = SensorTiltSource(app)
        var shown by mutableStateOf(true)
        rule.setContent {
            CompositionLocalProvider(
                LocalSlabConfig provides SlabConfig(SlabRenderer.Polygon, SlabMotion.Live),
                LocalTiltSource provides source,
            ) {
                if (shown) {
                    Box(Modifier.fillMaxSize()) {
                        SlabHero(state = SlabState.Armed, heat = 0, modifier = Modifier.size(320.dp))
                        LayDownGlow(armed = true, modifier = Modifier.fillMaxSize())
                    }
                }
            }
        }
        rule.waitForIdle()
        assertEquals("two clients, one registration", 1, listeners.size)
        rule.runOnIdle { shown = false }
        rule.waitForIdle()
        assertTrue("a listener outlived the composition", listeners.isEmpty())
        assertEquals("polygons were asked for", 0, CountingRuntimeShader.constructed)
    }

    @Test
    fun `the shader is built once for the hero, however often it is composed, drawn or moved`() {
        val tilt = FakeTilt()
        var state by mutableStateOf(SlabState.Armed)
        var palette by mutableStateOf(SlabPalette.Dark)
        var config by mutableStateOf(SlabConfig(SlabRenderer.Shader, SlabMotion.Live))
        rule.mainClock.autoAdvance = false
        rule.setContent {
            CompositionLocalProvider(LocalSlabConfig provides config, LocalTiltSource provides tilt) {
                SlabHero(state = state, heat = 5, modifier = Modifier.size(320.dp), palette = palette, interactive = true)
            }
        }
        rule.waitForIdle()
        assertEquals("the hero did not ask for its shader", 1, CountingRuntimeShader.constructed)
        val draws = SlabProbe.draws
        assertTrue(drawIfInvalidated())

        // Everything that recomposes the hero: its state, its palette, an equal config from a new onResume.
        for (next in listOf(SlabState.Hot, SlabState.Cooling, SlabState.Frozen, SlabState.Cold)) {
            rule.runOnIdle { state = next }
            frames(400)
        }
        rule.runOnIdle { palette = SlabPalette.Light }
        rule.runOnIdle { config = SlabConfig(SlabRenderer.Shader, SlabMotion.Live) }
        frames(400)
        // And everything that only draws it: the phone turning for a second.
        repeat(50) { i ->
            rule.runOnIdle { tilt.sample(45.0 + i) }
            frame()
        }
        frames(1_000)
        rule.waitForIdle()
        assertTrue("the shader drew ${SlabProbe.draws - draws} frames", SlabProbe.draws - draws >= 60)
        assertEquals("a second RuntimeShader was constructed", 1, CountingRuntimeShader.constructed)

        // Polygons are a different drawer and no shader at all; the shader again is one more, once.
        rule.runOnIdle { config = SlabConfig(SlabRenderer.Polygon, SlabMotion.Live) }
        frames(100)
        assertEquals(1, CountingRuntimeShader.constructed)
        rule.runOnIdle { config = SlabConfig(SlabRenderer.Shader, SlabMotion.Live) }
        frames(500)
        assertEquals(2, CountingRuntimeShader.constructed)
    }

    @Test
    fun `a moving slab is drawn again and never composed again`() {
        val tilt = FakeTilt()
        var scroll by mutableFloatStateOf(0f)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            CompositionLocalProvider(
                LocalSlabConfig provides SlabConfig(SlabRenderer.Polygon, SlabMotion.Live),
                LocalTiltSource provides tilt,
            ) {
                SlabHero(
                    state = SlabState.Armed, heat = 0, modifier = Modifier.size(320.dp).testTag("hero"),
                    scrollPx = { scroll }, interactive = true, enter = true,
                )
            }
        }
        rule.waitForIdle()
        assertTrue(drawIfInvalidated())
        val compositions = SlabProbe.compositions

        // The launch plays out: frames, each of them drawn, and it ends.
        val launch = frames(3_000)
        assertTrue("the launch drew $launch frames", launch >= 20)
        assertEquals("the launch recomposed the hero", compositions, SlabProbe.compositions)
        assertEquals("the launch never ended", 0, frames(500))

        // The phone turns over in a second, a frame at a time.
        var draws = SlabProbe.draws
        var drawn = 0
        repeat(60) { i ->
            rule.runOnIdle { tilt.sample(45.0 + i * 2.0) }
            if (frame()) drawn++
        }
        drawn += frames(500)
        assertTrue("the turn drew $drawn frames", drawn >= 30)
        // (This display draws a view twice: once to the canvas, once into its layer.)
        assertTrue("frames were asked for and the hero was not drawn", SlabProbe.draws - draws >= drawn)
        assertEquals("the turn recomposed the hero", compositions, SlabProbe.compositions)

        // A finger turns it and lets go: the drag and the spring back.
        rule.onNodeWithTag("hero").performTouchInput {
            down(center)
            moveBy(Offset(60f, 0f))
            repeat(6) { moveBy(Offset(20f, -10f)) }
            up()
        }
        drawn = frames(2_000)
        assertTrue("the spring back drew $drawn frames", drawn >= 10)
        assertEquals("the drag recomposed the hero", compositions, SlabProbe.compositions)

        // The page scrolls under it.
        draws = SlabProbe.draws
        drawn = 0
        repeat(20) { i ->
            rule.runOnIdle { scroll = 20f * (i + 1) }
            if (frame()) drawn++
        }
        assertEquals("a scroll step that was not drawn", 20, drawn)
        assertTrue(SlabProbe.draws - draws >= 20)
        assertEquals("the scroll recomposed the hero", compositions, SlabProbe.compositions)

        // And then nothing: the phone, the finger and the page are still.
        frames(1_000)
        assertEquals("frames with nothing moving", 0, frames(2_000))
        assertTrue(!pendingWork())
    }

    @Test
    fun `a slab scrolled out of the pose asks for no frames while the phone moves, and catches up after`() {
        val tilt = FakeTilt()
        var scroll by mutableFloatStateOf(0f)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            CompositionLocalProvider(
                LocalSlabConfig provides SlabConfig(SlabRenderer.Polygon, SlabMotion.Live),
                LocalTiltSource provides tilt,
            ) {
                SlabHero(state = SlabState.Armed, heat = 0, modifier = Modifier.size(320.dp), scrollPx = { scroll })
            }
        }
        rule.waitForIdle()
        assertTrue(drawIfInvalidated())
        frames(500)
        rule.runOnIdle { repeat(25) { tilt.sample(45.0) } }
        frames(500)

        // In sight, a turning phone is followed, frame by frame.
        val seen = SlabProbe.frameCallbacks
        repeat(30) { i ->
            rule.runOnIdle { tilt.sample(45.0 + i) }
            frame()
        }
        frames(500)
        assertTrue("the slab in sight did not follow", SlabProbe.frameCallbacks > seen)

        // The hero has scrolled away by its whole height: gravity has no weight in its pose.
        rule.runOnIdle { scroll = 10_000f }
        frames(200)
        val away = SlabProbe.frameCallbacks
        var drawn = 0
        repeat(100) { i ->
            rule.runOnIdle { tilt.sample(75.0 + 40.0 * Math.sin(i * 0.2)) }
            if (frame()) drawn++
        }
        assertEquals("frames were asked for by a slab nobody can see", away, SlabProbe.frameCallbacks)
        assertEquals("a slab nobody can see was drawn", 0, drawn)
        assertTrue(!pendingWork())

        // Back in sight, it follows to where the phone is now, and parks.
        rule.runOnIdle { scroll = 0f }
        drawn = frames(1_000)
        val back = SlabProbe.frameCallbacks
        assertTrue("the slab did not catch up", back > away && drawn > 1)
        assertEquals("the follow did not park", 0, frames(2_000))
        assertEquals(back, SlabProbe.frameCallbacks)
    }
}
