package xyz.headsdown.clockout

import android.app.Application
import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.robolectric.Shadows.shadowOf
import xyz.headsdown.core.design.HeadsDownTheme
import xyz.headsdown.core.design.components.HdHaptics
import xyz.headsdown.core.design.components.LocalHdHaptics
import xyz.headsdown.feature.reveal.haul.HonestCopy

/** What a sheet asked of the phone's motor, in order. */
class RecordedHaptics : HdHaptics {
    val cues = mutableListOf<String>()

    override fun knock() { cues += "knock" }
    override fun snap() { cues += "snap" }
    override fun confirm() { cues += "confirm" }
    override fun refuse() { cues += "refuse" }
}

/**
 * What the tests of the two sheets share: a 360 x 800 dp phone (the Redmi 14C's width) drawn with
 * the real fonts, at a font scale the test sets the way the system does, with the motor recorded.
 *
 * The concrete classes carry the Robolectric annotations: `@GraphicsMode(NATIVE)` and
 * `@Config(application = Application::class, qualifiers = SCREEN)`.
 */
abstract class SheetTestBase {
    protected val rule = createComposeRule()

    /** Same reason as ClockOutScreenTest: the app's manifest has no ui-test host activity. */
    private val hostActivity = object : ExternalResource() {
        override fun before() {
            val app = ApplicationProvider.getApplicationContext<Application>()
            shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
        }
    }

    @get:Rule val rules: RuleChain = RuleChain.outerRule(hostActivity).around(rule)

    protected var fontScale by mutableFloatStateOf(1f)
    protected val haptics = RecordedHaptics()

    /** What is on screen. Each new value is a new screen: nothing is remembered from the one before. */
    private var screen by mutableStateOf<Pair<Int, @Composable () -> Unit>?>(null)
    private var screens = 0
    private var composed = false

    /** Puts [content] on the phone, in place of whatever was there. */
    protected fun show(content: @Composable () -> Unit) {
        screen = ++screens to content
        if (!composed) {
            composed = true
            rule.setContent {
                val device = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(device.density, fontScale),
                    LocalHdHaptics provides haptics,
                ) {
                    HeadsDownTheme { screen?.let { (id, draw) -> key(id, fontScale) { draw() } } }
                }
            }
        }
        rule.waitForIdle()
    }

    /**
     * The app's window between a phone's system bars: nothing of a sheet is drawn under them, so
     * the page is as short as it is on the phone, while the screen the script measures its
     * swipes on is the whole glass.
     */
    @Composable
    protected fun BetweenTheBars(status: Dp = 32.dp, navigation: Dp = 48.dp, content: @Composable () -> Unit) {
        Box(Modifier.fillMaxSize().padding(top = status, bottom = navigation)) { content() }
    }

    protected fun bounds(tag: String): DpRect = rule.onNodeWithTag(tag).getUnclippedBoundsInRoot()

    protected fun root(): DpRect = rule.onRoot().getUnclippedBoundsInRoot()

    /** Every node there is, parents before children and in the order a screen reader meets them. */
    protected fun unmergedNodes(): List<SemanticsNode> {
        val out = mutableListOf<SemanticsNode>()
        fun walk(node: SemanticsNode) {
            out += node
            node.children.forEach(::walk)
        }
        walk(rule.onRoot(useUnmergedTree = true).fetchSemanticsNode())
        return out
    }

    /** The texts on screen as the emulator script meets them: one entry for each text node, top to bottom. */
    protected fun textsInOrder(): List<String> =
        unmergedNodes().flatMap { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }

    protected fun tagsInOrder(): List<String> = unmergedNodes().mapNotNull { it.config.getOrNull(SemanticsProperties.TestTag) }

    /** The tags of what can be tapped, in the order a screen reader reaches them. */
    protected fun actionsInOrder(): List<String> =
        unmergedNodes().filter { SemanticsActions.OnClick in it.config }.mapNotNull { it.config.getOrNull(SemanticsProperties.TestTag) }

    /**
     * Every text that reports how it was laid out is whole: it is not cut off at the bottom, no
     * line ends in an ellipsis, every character has a place, no line is wider than the width it
     * was offered, and the text's box is inside the screen from side to side. Returns the texts
     * it checked.
     *
     * (The layout a plain-string text reports is made again at the full width it was offered, so
     * its lines are compared with that width; its box hugs the widest line. The capitals drawn by
     * `Label` and `DisplayText` do not report a layout; no amount is one.)
     */
    protected fun assertEveryTextIsWhole(): List<String> {
        val screenWidth = root().right
        val checked = mutableListOf<String>()
        for (node in unmergedNodes()) {
            val action = node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action ?: continue
            val results = mutableListOf<TextLayoutResult>()
            action(results)
            val layout = results.singleOrNull() ?: continue
            val text = layout.layoutInput.text.text
            val lines = 0 until layout.lineCount
            assertFalse("\"$text\" is cut off at the bottom", layout.didOverflowHeight)
            assertFalse("\"$text\" ends in an ellipsis", lines.any(layout::isLineEllipsized))
            assertEquals("\"$text\": every character is laid out", text.length, layout.getLineEnd(layout.lineCount - 1, visibleEnd = false))
            val offered = layout.layoutInput.constraints.maxWidth
            for (line in lines) {
                val width = layout.getLineRight(line) - layout.getLineLeft(line)
                assertTrue("\"$text\" line $line is $width wide where $offered was offered", width <= offered + 0.5f)
            }
            val box = with(node.layoutInfo.density) { node.boundsInRoot.left.toDp() to node.boundsInRoot.right.toDp() }
            assertTrue("\"$text\" is inside the screen: $box", box.first >= 0.dp && box.second <= screenWidth)
            checked += text
        }
        return checked
    }

    /** The control tagged [tag] is whole inside the screen: the primary action is never clipped. */
    protected fun assertInsideTheScreen(tag: String) {
        val screen = root()
        val control = bounds(tag)
        assertTrue(
            "$tag is inside the screen: $control in $screen",
            control.left >= screen.left && control.right <= screen.right && control.top >= screen.top && control.bottom <= screen.bottom,
        )
    }

    // ------------------------------------------------------------------ what a screen reader meets

    /** Every node a screen reader stops on: the merged tree, top to bottom. */
    protected fun spokenNodes(): List<SemanticsNode> {
        val out = mutableListOf<SemanticsNode>()
        fun walk(node: SemanticsNode) {
            out += node
            node.children.forEach(::walk)
        }
        walk(rule.onRoot().fetchSemanticsNode())
        return out
    }

    /**
     * Whatever a screen reader can stop on or act on has words, and nothing else is offered to it:
     * - every node with a role, an action, a chosen state, a heading or a live region has a text;
     * - nothing has an empty text or a description (the sheets draw no picture that means anything);
     * - every control is a full touch target, 48dp both ways, and says what kind of control it is;
     * - a choice says whether it is chosen;
     * - what is only drawn (rules, skeleton lines, the marks inside a choice and a notice) is silent.
     */
    protected fun assertSpeakable(name: String) {
        val nodes = spokenNodes()
        for (node in nodes) {
            val config = node.config
            val words = config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
            assertEquals("$name: no node carries a description", null, config.getOrNull(SemanticsProperties.ContentDescription))
            assertTrue("$name: no text is blank: $words", words.none { it.isBlank() })
            val control = SemanticsActions.OnClick in config
            val meansSomething = control ||
                SemanticsProperties.Role in config ||
                SemanticsProperties.Selected in config ||
                SemanticsProperties.Heading in config ||
                SemanticsProperties.LiveRegion in config ||
                SemanticsProperties.Focused in config
            if (meansSomething) assertTrue("$name: a node with a meaning has words: $config", words.isNotEmpty())
            if (control || SemanticsProperties.Role in config) {
                val role = config.getOrNull(SemanticsProperties.Role)
                assertTrue("$name: \"${words.firstOrNull()}\" says what it is: $role", role == Role.Button || role == Role.Checkbox || role == Role.RadioButton)
                val (width, height) = with(node.layoutInfo.density) { node.size.width.toDp() to node.size.height.toDp() }
                assertTrue("$name: \"${words.firstOrNull()}\" is a full touch target: $width x $height", width >= 48.dp && height >= 48.dp)
                assertEquals("$name: \"${words.firstOrNull()}\" is one label", 1, words.size)
                if (role != Role.Button) assertTrue("$name: a choice says whether it is chosen", SemanticsProperties.Selected in config)
            }
        }
        assertEquals("$name: one heading", 1, nodes.count { SemanticsProperties.Heading in it.config })
    }

    // ------------------------------------------------------------------ the emulator script's eyes and finger

    /**
     * One line of `ui.py texts`: a text that shows, and the middle of the part of it that shows.
     * [whole] is what the script cannot know and a reader can: none of it is cut by an edge.
     */
    protected data class Seen(val text: String, val at: Offset, val whole: Boolean)

    /** How often the script had to swipe to find what it was looking for. */
    protected var swipes = 0

    /**
     * The screen as `scripts/devstack/emulator/ui.py` reads it from a uiautomator dump: every node
     * that has a text (or a description) and shows at least in part, in layout order, with the
     * middle of what shows of it. A node scrolled out of the page is not in a dump at all, and one
     * that is cut by the dock's edge is tapped in the middle of what is left of it.
     */
    protected fun dump(): List<Seen> {
        rule.waitForIdle()
        return unmergedNodes().mapNotNull { node ->
            val words = node.config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text
                ?: node.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()
            // Clipped by everything that clips it: empty for what is outside the page's viewport.
            val shows = node.boundsInRoot
            if (words.isNullOrEmpty() || shows.width <= 0f || shows.height <= 0f) {
                null
            } else {
                Seen(words, shows.center, whole = shows.width >= node.size.width - 0.5f && shows.height >= node.size.height - 0.5f)
            }
        }
    }

    /** `ui.py`'s matching: the whole text, case and all, or a part of it whatever the case. */
    protected fun matches(text: String, exact: Boolean): List<Seen> =
        dump().filter { if (exact) it.text == text else text.lowercase() in it.text.lowercase() }

    /** `ui.py has`: on screen right now, without scrolling. */
    protected fun has(text: String, exact: Boolean = false): Boolean = matches(text, exact).isNotEmpty()

    /** `ui.py swipe up`: from 78% of the screen's height to 33%, in a quarter of a second, and whatever fling follows. */
    protected fun swipeUp() {
        swipes++
        rule.onRoot().performTouchInput {
            swipe(Offset(width / 2f, height * 0.78f), Offset(width / 2f, height * 0.33f), durationMillis = 250)
        }
        rule.waitForIdle()
    }

    /** `ui.py find`: the first match in layout order, swiping up to look for it, never back down. */
    protected fun find(text: String, exact: Boolean = false): Seen {
        repeat(6) {
            matches(text, exact).firstOrNull()?.let { return it }
            swipeUp()
        }
        throw AssertionError("'$text' is not on screen: ${dump().map { it.text }}")
    }

    /** `ui.py tap`: a finger on the middle of the first match, wherever on the glass that is. */
    protected fun tap(text: String, exact: Boolean = false): String {
        val hit = find(text, exact)
        rule.onRoot().performTouchInput { click(hit.at) }
        rule.waitForIdle()
        return hit.text
    }

    // ------------------------------------------------------------------ stillness

    /** Whether any composition is waiting for a frame or has something left to recompose. */
    protected fun pendingWork(): Boolean = Recomposer.runningRecomposers.value.any { it.hasPendingWork }

    protected fun advance(millis: Long) {
        rule.mainClock.advanceTimeBy(millis)
        rule.waitForIdle()
    }

    /** How many snapshot states are written while the clock runs for [millis]. */
    protected fun writesDuring(millis: Long): Int {
        var writes = 0
        val handle = Snapshot.registerGlobalWriteObserver { writes++ }
        try {
            advance(millis)
        } finally {
            handle.dispose()
        }
        return writes
    }

    /**
     * A still screen draws nothing: two seconds after [name] was shown no frame is being waited
     * for, and in the two seconds after that no state is written. The clock must be held
     * (`mainClock.autoAdvance = false`), or the test rule cancels an endless animation by itself.
     */
    protected fun assertAtRest(name: String) {
        advance(2_000)
        assertFalse("$name: something is still waiting for a frame", pendingWork())
        assertEquals("$name: state written at rest", 0, writesDuring(2_000))
        assertFalse("$name: something started while nothing happened", pendingWork())
    }

    // ------------------------------------------------------------------ words

    /**
     * A label the redesign added: none of the words the app never uses, sentence case (it starts
     * with a capital or a figure, and no word is typed in capitals but the three tickers), no
     * trailing full stop unless it is a sentence, and nothing left blank.
     */
    protected fun assertHonestLabel(label: String) {
        assertTrue("a label is not blank", label.isNotBlank())
        assertEquals("\"$label\"", emptyList<String>(), HonestCopy.violations(label))
        assertTrue("\"$label\" starts with a capital", label.first().isUpperCase() || label.first().isDigit())
        val shouted = label.split(' ', ',', '.', ':').filter { it.length > 1 && it.all(Char::isUpperCase) }
        assertEquals("\"$label\": capitals are drawn, not typed", emptyList<String>(), shouted - TICKERS)
    }

    companion object {
        /** The Redmi 14C's width, a common height, at a density that keeps the test images small. */
        const val SCREEN = "w360dp-h800dp-xhdpi"

        /** A short screen: what a split window or a small phone leaves. */
        const val SHORT_SCREEN = "w360dp-h480dp-xhdpi"

        /** A small phone, and a phone on its side. */
        const val SMALL_SCREEN = "w320dp-h568dp-xhdpi"
        const val LANDSCAPE = "w800dp-h360dp-xhdpi"

        val TICKERS = setOf("SOL", "ORE", "SKR")

        /** A real transaction signature has this shape: 64 bytes in base58. */
        const val SIGNATURE = "5VERv8NMvzbJMEkV8xnrLkEaWRtSz9CosKDYjCJjBRnbJLgp8uirBgmQpjKhoR4tjF3ZpRzrFmBV6UjKdiSZkQUW"
    }
}
