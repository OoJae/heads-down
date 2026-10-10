package xyz.headsdown.core.design.components

import android.animation.ValueAnimator
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import xyz.headsdown.core.design.BrandGlyph
import xyz.headsdown.core.design.Hd
import xyz.headsdown.core.design.HdMotion
import xyz.headsdown.core.design.HeadsDownTheme
import xyz.headsdown.core.design.LocalHdMotion
import xyz.headsdown.core.design.Wordmark

/**
 * A still screen draws nothing: with every component on screen and nobody touching it, no frame
 * is being waited for and no state is written. The test clock is driven by hand, because with
 * `autoAdvance` on the test rule quietly cancels endless animations instead of running them.
 */
@RunWith(RobolectricTestRunner::class)
class AtRestTest {
    @get:Rule val rule = createComposeRule()

    @After
    fun animatorsBackOn() = setAnimatorScale(1f)

    private fun setAnimatorScale(scale: Float) {
        ValueAnimator::class.java.getMethod("setDurationScale", Float::class.javaPrimitiveType).invoke(null, scale)
    }

    /** Whether any composition is waiting for a frame or has something left to recompose. */
    private fun pendingWork(): Boolean = Recomposer.runningRecomposers.value.any { it.hasPendingWork }

    private fun advance(millis: Long) {
        rule.mainClock.advanceTimeBy(millis)
        rule.waitForIdle()
    }

    /**
     * A touch on the node tagged [tag]. With the clock held, the state a touch writes reaches the
     * composition when the main thread next idles, so that happens here and not after the frame.
     */
    private fun touch(tag: String, gesture: TouchInjectionScope.() -> Unit) {
        rule.onNodeWithTag(tag).performTouchInput(gesture)
        rule.waitForIdle()
    }

    /** How many snapshot states are written while the clock runs for [millis]. */
    private fun writesDuring(millis: Long): Int {
        var writes = 0
        val handle = Snapshot.registerGlobalWriteObserver { writes++ }
        try {
            advance(millis)
        } finally {
            handle.dispose()
        }
        return writes
    }

    /** Every component of the module, in one screen. */
    @Composable
    private fun Everything() {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            Row { BrandGlyph(Modifier.size(24.dp)); Wordmark() }
            Hero(HeroSpec(SlabState.Hot, heat = 4), contentDescription = "The rig is hot")
            Hero(HeroSpec(SlabState.Cooling, heat = 2, enter = true, interactive = true))
            HeroNumerals("0.0194")
            DisplayText("Rig hot")
            Label("Powered by ORE")
            StatRow("Rounds dark", "55")
            StatBlock("Dark for", "1:12")
            HeatPixels(3, description = "Heat 3 of 5")
            SectionHeader("Clock out")
            Plate(tone = PlateTone.Slab, onClick = {}) {
                LedgerRow("Rent for the shift log", figure = "0.00130048 SOL")
                LedgerRow("Focus Bond", figure = "100 SKR") { BasicText("Comes back to your wallet.") }
            }
            Notice("Nothing was spent.")
            Notice("The chain could not be read.", tone = NoticeTone.Problem) { TextAction("Try again", onClick = {}) }
            StepRow(1, "Notifications", status = StepStatus.Done)
            StepRow(2, "Morning alarm", status = StepStatus.Current, onClick = {}) { BarButton("Allow exact alarm", onClick = {}) }
            SkeletonLine(widthFraction = 0.6f)
            SkeletonBlock()
            Choice("Keep it in my Miner", selected = true, onClick = {})
            Choice("End the shift now", selected = false, onClick = {}, role = Role.Checkbox)
            Choice("10 SKR", selected = true, onClick = {}, compact = true)
            LinkRow("How the night shift works", onClick = {})
            ActionDock(seam = true) {
                BarButton("Clock in", onClick = {}, modifier = Modifier.testTag("bar"), seam = true)
                BarButton("Waiting for your wallet…", onClick = {}, busy = true)
                BarButton("End shift", onClick = {}, style = BarButtonStyle.Secondary, enabled = false)
            }
        }
    }

    @Test
    fun `with every component on screen, nothing is pending after two seconds and nothing is written`() {
        var dark by mutableStateOf(true)
        rule.mainClock.autoAdvance = false
        rule.setContent { HeadsDownTheme(darkTheme = dark) { Everything() } }
        for (palette in listOf(true, false)) {
            dark = palette
            // The clock is held: let the change of palette reach the composition before the frames run.
            rule.waitForIdle()
            advance(2_000)
            assertFalse("dark=$palette: something is still waiting for a frame", pendingWork())
            assertEquals("dark=$palette: state written at rest", 0, writesDuring(2_000))
            assertFalse(pendingWork())
        }
    }

    @Test
    fun `the same check does see an endless animation, also Material's own spinner`() {
        // The detector's positive control. An infinite transition keeps a frame awaited for ever.
        rule.mainClock.autoAdvance = false
        rule.setContent {
            HeadsDownTheme {
                Column {
                    val breath by rememberInfiniteTransition(label = "control").animateFloat(
                        initialValue = 0.5f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(tween(1_600), RepeatMode.Reverse),
                        label = "control-alpha",
                    )
                    Box(Modifier.size(10.dp).graphicsLayer { alpha = breath })
                }
            }
        }
        advance(2_000)
        assertTrue("an infinite transition is pending work", pendingWork())
        assertTrue(writesDuring(500) > 0)
    }

    @Test
    fun `an indeterminate Material spinner never comes to rest, so no new screen may use one`() {
        // StillnessTest finds rememberInfiniteTransition in a source file; this one hides inside
        // Material, which is why StillnessTest also names the progress indicators.
        rule.mainClock.autoAdvance = false
        rule.setContent { HeadsDownTheme { CircularProgressIndicator() } }
        advance(2_000)
        assertTrue(pendingWork())
    }

    @Test
    fun `a press gives on touch-down, comes to rest while held, and springs back on release`() {
        lateinit var state: PressState
        var motion: HdMotion? = null
        rule.mainClock.autoAdvance = false
        rule.setContent {
            CompositionLocalProvider(LocalHdMotion provides HdMotion(reduced = false)) {
                motion = Hd.motion
                val press = rememberPressState(enabled = true).also { state = it }
                Box(Modifier.testTag("pad").size(120.dp).pressFeedback(press))
            }
        }
        advance(100)
        assertFalse(pendingWork())
        assertEquals(1f, state.scale.value, 0f)

        touch("pad") { down(center) }
        assertTrue(state.pressed)
        // On touch-down, not after the 40 ms a scrolling parent holds a tap back: the spring is
        // started by the first frame and the control has visibly given by the third.
        advance(16)
        assertTrue("the press spring is running on the first frame", state.scale.isRunning && pendingWork())
        advance(32)
        assertTrue("the control has given by the third frame: ${state.scale.value}", state.scale.value < 1f)
        advance(2_000)
        assertEquals(0.97f, state.scale.value, 0.001f)
        assertFalse("a held control is still", pendingWork())
        assertEquals(0, writesDuring(1_000))

        touch("pad") { up() }
        advance(2_000)
        assertFalse(state.pressed)
        assertEquals(1f, state.scale.value, 0.001f)
        assertFalse(pendingWork())
        assertEquals(false, motion?.reduced)
    }

    @Test
    fun `with motion reduced a press moves nothing and starts no animation`() {
        lateinit var state: PressState
        rule.mainClock.autoAdvance = false
        rule.setContent {
            CompositionLocalProvider(LocalHdMotion provides HdMotion(reduced = true)) {
                val press = rememberPressState(enabled = true).also { state = it }
                Box(Modifier.testTag("pad").size(120.dp).pressFeedback(press))
            }
        }
        advance(100)
        touch("pad") { down(center) }
        // One frame for the recomposition that sees the press; nothing may be left after it.
        advance(16)
        assertTrue(state.pressed && state.reduced)
        assertEquals("nothing moves", 1f, state.scale.value, 0f)
        assertFalse("no animation was started", state.scale.isRunning)
        assertFalse(pendingWork())
        assertEquals(0, writesDuring(1_000))
        assertEquals(1f, state.scale.value, 0f)

        touch("pad") { up() }
        advance(16)
        assertFalse(state.pressed)
        assertFalse(pendingWork())
        assertEquals(1f, state.scale.value, 0f)
    }

    @Test
    fun `with motion reduced, a spring a caller still asks for has arrived by the next frame`() {
        // The three springs collapse into one that is critically damped and very stiff. It must
        // really end, in a frame, whatever distance it is asked to cover.
        val reduced = HdMotion(reduced = true)
        val moves = listOf(Animatable(0f), Animatable(0f), Animatable(1_000f))
        val targets = listOf(1f, 2_000f, 0f)
        val specs = listOf(reduced.settle<Float>(), reduced.press(), reduced.thud())
        // Beside them, the real thud over 100 units: it takes a visible while and bounces once.
        val thud = Animatable(0f)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            for (i in moves.indices) LaunchedEffect(Unit) { moves[i].animateTo(targets[i], specs[i]) }
            LaunchedEffect(Unit) { thud.animateTo(100f, HdMotion(reduced = false).thud()) }
        }
        // One frame in which the animations start, one in which the collapsed ones arrive.
        advance(32)
        for (i in moves.indices) {
            assertEquals("spring $i", targets[i], moves[i].value, 0.01f)
            assertFalse("spring $i has ended", moves[i].isRunning)
        }
        assertTrue("the real thud is still on its way: ${thud.value}", thud.isRunning && thud.value < 50f)

        var peak = 0f
        var frames = 0
        while (thud.isRunning && frames < 300) {
            rule.mainClock.advanceTimeByFrame()
            peak = maxOf(peak, thud.value)
            frames++
        }
        rule.waitForIdle()
        // A damping ratio of 0.62 overshoots by about 8%, once; the second swing is under 1%.
        assertTrue("one visible bounce: the peak was $peak", peak in 105f..112f)
        assertTrue("it comes to rest, after $frames frames", frames in 20..120)
        assertEquals(100f, thud.value, 0.5f)
        assertFalse(pendingWork())
    }

    @Test
    fun `with the system's animations off, pressing a real bar starts no animation and the click lands`() {
        setAnimatorScale(0f)
        var clicks = 0
        var reduced: Boolean? = null
        rule.mainClock.autoAdvance = false
        rule.setContent {
            HeadsDownTheme {
                reduced = Hd.motion.reduced
                Column {
                    BarButton("Clock in", onClick = { clicks++ }, modifier = Modifier.testTag("bar"), seam = true)
                    Choice("10 SKR", selected = false, onClick = { clicks++ }, modifier = Modifier.testTag("chip"), compact = true)
                    LinkRow("How the night shift works", onClick = { clicks++ }, modifier = Modifier.testTag("row"))
                }
            }
        }
        advance(100)
        assertEquals(true, reduced)
        for (tag in listOf("bar", "chip", "row")) {
            touch(tag) { down(center) }
            advance(16)
            assertFalse("$tag: no animation while held", pendingWork())
            assertEquals("$tag: nothing written while held", 0, writesDuring(500))
            touch(tag) { up() }
            advance(16)
            assertFalse("$tag: no animation after release", pendingWork())
        }
        assertEquals(3, clicks)
    }
}
