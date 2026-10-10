package xyz.headsdown.ui.slab

import android.app.Application
import android.content.ComponentName
import android.hardware.Sensor
import android.hardware.SensorManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
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
import org.robolectric.shadows.ShadowSensor

/**
 * The hero as a screen composes it: inert by default, one content description and no text, and,
 * when an Activity makes it live, frames only while something is moving.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SlabHeroTest {
    private val rule = createComposeRule()

    /** See HomeAndIntroTest: the app's manifest has no test activity, so Robolectric is told of one. */
    private val hostActivity = object : ExternalResource() {
        override fun before() {
            val app = ApplicationProvider.getApplicationContext<Application>()
            shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
        }
    }

    @get:Rule val rules: RuleChain = RuleChain.outerRule(hostActivity).around(rule)

    private class FakeTilt : TiltSource {
        val listeners = ArrayList<TiltListener>()
        var starts = 0
        var stops = 0
        override fun start(listener: TiltListener) {
            starts++
            listeners += listener
        }

        override fun stop(listener: TiltListener) {
            stops++
            listeners -= listener
        }

        private var nanos = 1_000_000_000L

        /** One second of 50 Hz samples with the phone's screen normal [theta] degrees from up. */
        fun hold(theta: Double, samples: Int = 50) {
            val r = Math.toRadians(theta)
            repeat(samples) {
                nanos += 20_000_000L
                listeners.toList().forEach { it.onSample(0f, (9.81 * Math.sin(r)).toFloat(), (9.81 * Math.cos(r)).toFloat(), nanos) }
            }
        }
    }

    private fun allNodes(): List<SemanticsNode> {
        fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
        return walk(rule.onRoot(useUnmergedTree = true).fetchSemanticsNode())
    }

    @Test
    fun `the default composition builds no shader, registers no sensor and asks for no frame`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val sensors = shadowOf(app.getSystemService(SensorManager::class.java))
        sensors.addSensor(ShadowSensor.newInstance(Sensor.TYPE_ACCELEROMETER))
        val shaders = SlabProbe.shadersBuilt
        val frames = SlabProbe.frameCallbacks

        rule.mainClock.autoAdvance = false
        rule.setContent {
            SlabHero(state = SlabState.Armed, heat = 3, modifier = Modifier.size(320.dp), interactive = true, enter = true)
        }
        rule.waitForIdle()
        val draws = SlabProbe.draws

        // Two seconds on a paused clock: an ambient loop, a spring or a sensor follow would show here.
        rule.mainClock.advanceTimeBy(2_000)
        rule.waitForIdle()
        assertEquals("a RuntimeShader was constructed", shaders, SlabProbe.shadersBuilt)
        assertEquals("a frame callback ran", frames, SlabProbe.frameCallbacks)
        assertEquals("the slab was drawn again with nothing moving", draws, SlabProbe.draws)
        assertTrue("a sensor listener was registered", sensors.listeners.isEmpty())

        // And with the clock running the composition is idle: waitForIdle returns.
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        assertEquals(frames, SlabProbe.frameCallbacks)
    }

    @Test
    fun `the defaults are the inert ones`() {
        var config: SlabConfig? = null
        var tilt: TiltSource? = null
        rule.setContent {
            config = LocalSlabConfig.current
            tilt = LocalTiltSource.current
        }
        rule.waitForIdle()
        assertEquals(SlabConfig(SlabRenderer.Polygon, SlabMotion.Static), config)
        assertTrue(tilt === NoTilt)
    }

    @Test
    fun `it exposes one content description, the role of an image, and no text`() {
        rule.setContent {
            SlabHero(
                state = SlabState.Hot, heat = 5, modifier = Modifier.size(320.dp).testTag("hero"),
                contentDescription = "The rig is hot",
            )
        }
        val nodes = allNodes()
        val described = nodes.filter { it.config.getOrNull(SemanticsProperties.ContentDescription) != null }
        assertEquals(1, described.size)
        assertEquals(listOf("The rig is hot"), described.single().config[SemanticsProperties.ContentDescription])
        assertEquals(Role.Image, described.single().config[SemanticsProperties.Role])
        assertTrue(nodes.none { it.config.getOrNull(SemanticsProperties.Text) != null })
        assertTrue(nodes.none { it.config.getOrNull(SemanticsProperties.EditableText) != null })
    }

    @Test
    fun `with no content description it is not there for accessibility at all`() {
        rule.setContent { SlabHero(state = SlabState.Cold, heat = 0, modifier = Modifier.size(320.dp)) }
        val nodes = allNodes()
        assertTrue(nodes.none { it.config.getOrNull(SemanticsProperties.ContentDescription) != null })
        assertTrue(nodes.none { it.config.getOrNull(SemanticsProperties.Text) != null })
        assertTrue(nodes.none { it.config.getOrNull(SemanticsProperties.Role) != null })
    }

    @Test
    fun `live, the sensor runs while the hero is composed and frames stop when the phone is still`() {
        val tilt = FakeTilt()
        var shown by mutableStateOf(true)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            CompositionLocalProvider(
                LocalSlabConfig provides SlabConfig(SlabRenderer.Polygon, SlabMotion.Live),
                LocalTiltSource provides tilt,
            ) {
                if (shown) SlabHero(state = SlabState.Armed, heat = 0, modifier = Modifier.size(320.dp))
            }
        }
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()
        assertEquals(1, tilt.listeners.size)

        // A still phone: samples arrive, the pose does not move, no frame is asked for.
        rule.runOnIdle { tilt.hold(45.0) }
        val still = SlabProbe.frameCallbacks
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        assertEquals("frames were drawn for a phone that did not move", still, SlabProbe.frameCallbacks)

        // The phone turns upright: the slab follows for a few frames...
        rule.runOnIdle { tilt.hold(90.0, samples = 10) }
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        val moved = SlabProbe.frameCallbacks
        assertTrue("the slab did not follow the turn", moved > still)
        assertTrue("it never converged: ${moved - still} frames", moved - still < 40)

        // ...and parks: with the phone still again, another two seconds draw nothing.
        rule.mainClock.advanceTimeBy(2_000)
        rule.waitForIdle()
        assertEquals("the follow did not park", moved, SlabProbe.frameCallbacks)

        // Leaving the composition lets the sensor go.
        rule.mainClock.autoAdvance = true
        rule.runOnIdle { shown = false }
        rule.waitForIdle()
        assertTrue(tilt.listeners.isEmpty())
        assertEquals(tilt.starts, tilt.stops)
    }

    @Test
    fun `a tap is a knock, a horizontal drag turns the slab, a vertical drag scrolls the page`() {
        var knocks = 0
        var flips = 0
        var scrolled = 0
        rule.setContent {
            CompositionLocalProvider(LocalSlabConfig provides SlabConfig(SlabRenderer.Polygon, SlabMotion.Live)) {
                val scroll = rememberScrollState()
                scrolled = scroll.value
                Box(Modifier.size(320.dp, 400.dp).verticalScroll(scroll)) {
                    Box(Modifier.size(320.dp, 1200.dp)) {
                        SlabHero(
                            state = SlabState.Armed, heat = 0, modifier = Modifier.size(320.dp).testTag("hero"),
                            scrollPx = { scroll.value.toFloat() }, interactive = true,
                            onFlipped = { flips++ }, onKnock = { knocks++ },
                        )
                    }
                }
            }
        }
        rule.waitForIdle()

        rule.onNodeWithTag("hero").performTouchInput {
            down(center)
            up()
        }
        rule.waitForIdle()
        assertEquals(1, knocks)
        assertEquals(0, flips)

        // Horizontal first, then far up: the slab turns over, and the page stays where it is.
        rule.onNodeWithTag("hero").performTouchInput {
            down(center)
            moveBy(Offset(60f, 0f))
            repeat(12) { moveBy(Offset(0f, -40f)) }
            up()
        }
        rule.waitForIdle()
        assertEquals("a drag is not a knock", 1, knocks)
        assertEquals("the drag showed the underside once", 1, flips)
        assertEquals("a horizontal-first drag moved the page", 0, scrolled)

        // Vertical first: the page's, and the slab reports nothing.
        rule.onNodeWithTag("hero").performTouchInput {
            down(center)
            repeat(8) { moveBy(Offset(0f, -30f)) }
            up()
        }
        rule.waitForIdle()
        assertTrue("a vertical drag did not scroll the page", scrolled > 0)
        assertEquals(1, knocks)
        assertEquals(1, flips)
    }

    @Test
    fun `static, a tap does nothing and nothing is listening`() {
        var knocks = 0
        rule.setContent {
            SlabHero(
                state = SlabState.Armed, heat = 0, modifier = Modifier.size(320.dp).testTag("hero"),
                interactive = true, onKnock = { knocks++ },
            )
        }
        rule.onNodeWithTag("hero").performTouchInput {
            down(center)
            up()
        }
        rule.waitForIdle()
        assertEquals(0, knocks)
    }
}
