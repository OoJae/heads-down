package xyz.headsdown.feature.reveal.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.toSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.headsdown.core.design.HdPalette
import xyz.headsdown.feature.reveal.board.BoardReplay
import xyz.headsdown.feature.reveal.haul.Board
import xyz.headsdown.feature.reveal.haul.FakeHaulRepository
import xyz.headsdown.feature.reveal.haul.HaulProvenance
import xyz.headsdown.feature.reveal.haul.RoundReplay
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.abs

/**
 * The reveal on the phone it ships to: a 360dp by 800dp screen, measured and drawn with real
 * fonts (Robolectric's native graphics), at the normal font scale and up to 2.0. No figure is cut
 * or ends in an ellipsis, nothing is wider than the screen, and the dock's bar is whole.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h800dp-xhdpi")
class RevealLayoutTest {
    @get:Rule val rule = createComposeRule()

    private val zone = ZoneId.of("Africa/Lagos")
    private val now = LocalDateTime.of(2026, 9, 30, 7, 5).atZone(zone).toInstant().toEpochMilli()
    private val sample = FakeHaulRepository.sampleNight(now, zone)
    private val mine = sample.copy(provenance = HaulProvenance.ON_CHAIN, explorerUrl = "https://explorer.solana.com/address/x")

    /** A night whose every figure is as long as the formats allow in practice. */
    private val heavy = mine.copy(
        rounds = mine.rounds + RoundReplay(999_999, dugMask = 1, winningTile = 24, motherlode = true, endedAtWallMillis = mine.endedAtWallMillis - 60_000),
        darkRoundsTotal = 12_345,
        digsTotal = 1_234,
        solPlacedLamports = 123_456_700_000L,
        oreMinedAtoms = 123_456_700_000_000L,
        effectiveLamportsPerOre = 123_456_000_000L,
        marketLamportsPerOre = 98_765_000_000L,
        marketSource = "jupiter",
        nightlyTargetOreAtoms = 999_999_900_000_000L,
        feesLamports = 5_000_000L,
    )
    private val closed = FakeHaulRepository.sampleGateClosed(now, zone)

    private var fontScale by mutableFloatStateOf(1f)
    private var state by mutableStateOf<RevealUiState>(RevealUiState.Loading)

    private var renderMode: String? = null

    @Before
    fun drawForReal() {
        // captureToImage copies the pixels a hardware renderer drew, layers included.
        renderMode = System.setProperty(RENDER_MODE, "hardware")
    }

    @After
    fun restoreRenderMode() {
        renderMode?.let { System.setProperty(RENDER_MODE, it) } ?: System.clearProperty(RENDER_MODE)
    }

    private fun show(clockOut: Boolean = true, animate: Boolean = false) = rule.setContent {
        val device = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(device.density, fontScale)) {
            RevealRoute(
                state = state, zone = zone, animate = animate,
                onReplayStarted = {}, onReplayFinished = {}, onBuyRest = {}, onShare = {}, onDone = {},
                onShowSample = {}, onClockOut = if (clockOut) ({}) else null,
            )
        }
    }

    private fun textNodes(): List<SemanticsNode> {
        val out = mutableListOf<SemanticsNode>()
        fun walk(n: SemanticsNode) {
            if (n.config.getOrNull(SemanticsProperties.Text) != null) out += n
            n.children.forEach(::walk)
        }
        walk(rule.onRoot(useUnmergedTree = true).fetchSemanticsNode())
        return out
    }

    private fun SemanticsNode.words(): String = config[SemanticsProperties.Text].joinToString(" ") { it.text }

    /** Where the node is, whether or not the page has scrolled it out of sight. */
    private fun SemanticsNode.unclipped(): Rect = Rect(positionInRoot, size.toSize())

    /**
     * No line of the text is cut, ends in an ellipsis or is wider than the text's own box, and
     * every character is laid out. (A plain-string text reports a layout re-made at the width it
     * was offered, so its "overflowed width" flag says nothing; the lines themselves are measured.)
     */
    private fun assertWhole(what: String, layout: TextLayoutResult) {
        assertFalse("$what is taller than its box", layout.didOverflowHeight)
        for (line in 0 until layout.lineCount) {
            assertFalse("$what ends in an ellipsis", layout.isLineEllipsized(line))
            // To the pixel and one more: the box is rounded to whole pixels, a line is not.
            val width = layout.getLineRight(line) - layout.getLineLeft(line)
            assertTrue("$what: line $line is $width px wide in a box of ${layout.size.width}", width <= layout.size.width + 2f)
        }
        assertEquals("$what: every character is laid out", layout.layoutInput.text.length, layout.getLineEnd(layout.lineCount - 1, visibleEnd = false))
    }

    private fun SemanticsNode.textLayout(): TextLayoutResult? {
        val results = mutableListOf<TextLayoutResult>()
        config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
        return results.firstOrNull()
    }

    /** Nothing on screen is wider than the screen or cut, and the dock's bar is whole and at the bottom. */
    private fun assertNothingIsCut(what: String, dockTag: String, widthPx: Int = 720, heightPx: Int = 1600) {
        val screen = rule.onRoot().fetchSemanticsNode().size
        assertEquals("the screen this test was written for, at 2 px to the dp", widthPx to heightPx, screen.width to screen.height)
        val nodes = textNodes()
        assertTrue("$what: the screen has its words (${nodes.size})", nodes.size >= 3)
        for (node in nodes) {
            val words = node.words()
            val box = node.unclipped()
            assertTrue("$what: \"$words\" is inside the screen's width: $box", box.left >= 0f && box.right <= screen.width + 0.5f)
            assertTrue("$what: \"$words\" has a size", box.width > 0f && box.height > 0f)
            node.textLayout()?.let { assertWhole("$what: \"$words\"", it) }
        }
        // The one bar of the dock: all of it on screen, under everything, at least 56dp tall.
        val bar = rule.onNodeWithTag(dockTag).assertIsDisplayed().fetchSemanticsNode()
        assertEquals("$what: the dock's bar is not clipped", bar.unclipped(), bar.boundsInRoot)
        assertTrue("$what: the bar is on screen: ${bar.boundsInRoot}", bar.boundsInRoot.bottom <= screen.height && bar.boundsInRoot.top > screen.height / 2f)
        assertTrue("$what: the bar is ${bar.size.height} px tall", bar.size.height >= 112)
        assertEquals("$what: the bar spans the margins", 40f to widthPx - 40f, bar.boundsInRoot.left to bar.boundsInRoot.right)
    }

    private val everyState
        get() = listOf(
            "loading" to RevealUiState.Loading,
            "no haul" to RevealUiState.NoHaul,
            "a sample night" to RevealUiState.Ready(sample),
            "a haul of the user's own" to RevealUiState.Ready(mine),
            "a night that placed nothing" to RevealUiState.Ready(closed),
            "a night of long figures" to RevealUiState.Ready(heavy),
        )

    private fun dockTag(shown: RevealUiState): String =
        if (shown is RevealUiState.Ready && shown.summary.provenance != HaulProvenance.SAMPLE) RevealTags.CLOCK_OUT else RevealTags.DONE

    private fun figure(text: String): SemanticsNode = rule.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode()

    @Test
    fun `every state fits a 360dp screen from font scale 1 to 2, and no figure is cut`() {
        show()
        for (scale in listOf(1f, 1.3f, 1.5f, 2f)) {
            for ((name, shown) in everyState) {
                fontScale = scale
                state = shown
                rule.waitForIdle()
                assertNothingIsCut("$name at $scale", dockTag(shown))
            }
        }
    }

    @Test
    @Config(qualifiers = "w320dp-h568dp-xhdpi")
    fun `every state fits a small phone too, 320dp by 568dp, from font scale 1 to 2`() {
        show()
        for (scale in listOf(1f, 2f)) {
            for ((name, shown) in everyState) {
                fontScale = scale
                state = shown
                rule.waitForIdle()
                assertNothingIsCut("$name at $scale on a small phone", dockTag(shown), widthPx = 640, heightPx = 1136)
            }
        }
        // And the amounts of the longest night are still whole lines there.
        fontScale = 2f
        state = RevealUiState.Ready(heavy)
        rule.waitForIdle()
        for (text in listOf("1234.567 ORE", "12,345", "1,234", "123.456 SOL")) {
            val layout = figure(text).textLayout()!!
            assertEquals("\"$text\" is one line", 1, layout.lineCount)
            assertWhole("\"$text\"", layout)
        }
    }

    /** Every node a finger can press, in the order a screen reader meets them. */
    private fun actions(): List<SemanticsNode> {
        val out = mutableListOf<SemanticsNode>()
        fun walk(n: SemanticsNode) {
            if (n.config.getOrNull(SemanticsActions.OnClick) != null) out += n
            n.children.forEach(::walk)
        }
        walk(rule.onRoot().fetchSemanticsNode())
        return out
    }

    @Test
    fun `everything that can be pressed is at least 48dp by 48dp, at both font scales`() {
        show()
        for (scale in listOf(1f, 2f)) {
            for ((name, shown) in everyState) {
                fontScale = scale
                state = shown
                rule.waitForIdle()
                val pressable = actions()
                assertTrue("$name: there is a way out", pressable.isNotEmpty())
                for (node in pressable) {
                    val words = node.words()
                    assertTrue("$name at $scale: \"$words\" is ${node.size} px", node.size.width >= 96 && node.size.height >= 96)
                    assertTrue("$name at $scale: \"$words\" is on the screen's width", node.unclipped().left >= 0f && node.unclipped().right <= 720f)
                }
                // The dock's bar is the last of them: a screen reader reaches it after the page.
                assertEquals("$name at $scale", dockTag(shown), pressable.last().config[SemanticsProperties.TestTag])
            }
        }
    }

    @Test
    fun `the figures of a night are whole at font scale 2, each on one line where it is a number`() {
        fontScale = 2f
        state = RevealUiState.Ready(heavy)
        show()
        // The amounts as the formats write them: the longest this screen is asked to draw.
        for (text in listOf("1234.567 ORE", "12,345", "1,234", "123.456 SOL")) {
            val layout = figure(text).textLayout()!!
            assertEquals("\"$text\" is one line", 1, layout.lineCount)
            assertWhole("\"$text\"", layout)
            val box = figure(text).unclipped()
            assertTrue("\"$text\" is inside the page's margins: $box", box.left >= 40f && box.right <= 680.5f)
        }
        // The price and the market's are drawn in the display face and in mono, and wrap before they are cut.
        for (text in listOf("123.456 SOL per ORE", "Market 98.765 (jupiter)", "Buy the rest at market · 8765.432 ORE")) {
            val box = figure(text).unclipped()
            assertTrue("\"$text\" is inside the page's margins: $box", box.left >= 40f && box.right <= 680.5f)
        }
        // Above font scale 1.5 the three counts are stacked: one under another, on the margin.
        val counts = listOf("Rounds dark", "Digs", "SOL placed").map { figure(it).unclipped() }
        assertTrue(counts.all { it.left == counts.first().left })
        assertTrue(counts[0].bottom <= counts[1].top && counts[1].bottom <= counts[2].top)
    }

    @Test
    fun `the amount of ORE is the one number on the page, the same size for a night that mined nothing`() {
        show()
        fun lineHeight(text: String): Float = figure(text).textLayout()!!.let { it.getLineBottom(0) - it.getLineTop(0) }
        val heights = mutableMapOf<String, Float>()
        for (scale in listOf(1f, 2f)) {
            for ((shown, text) in listOf(sample to "0.0058 ORE", closed to "0 ORE", heavy to "1234.567 ORE")) {
                fontScale = scale
                state = RevealUiState.Ready(shown)
                rule.waitForIdle()
                heights["$text at $scale"] = lineHeight(text)
                assertFalse(figure(text).textLayout()!!.isLineEllipsized(0))
            }
        }
        // 80dp of type at the most (a line of about 1.2 em), never more for "0 ORE", and the same at 2.0.
        val largest = heights.getValue("0 ORE at 1.0")
        assertTrue("\"0 ORE\" is drawn at 80dp, not at the full hero size: $largest px", largest in 170f..200f)
        assertTrue("a night that mined something is not drawn smaller than one that did not by much", heights.getValue("0.0058 ORE at 1.0") >= largest * 0.9f)
        for (text in listOf("0.0058 ORE", "0 ORE", "1234.567 ORE")) {
            assertEquals("\"$text\" keeps its size at font scale 2", heights.getValue("$text at 1.0"), heights.getValue("$text at 2.0"), 1f)
            assertTrue("\"$text\" is at least 40dp of type", heights.getValue("$text at 1.0") >= 80f)
        }
        // It is the largest text on the page: larger than the title and the counts.
        fontScale = 1f
        state = RevealUiState.Ready(sample)
        rule.waitForIdle()
        val number = figure("0.0058 ORE").size.height
        assertTrue(number > rule.onNodeWithText("Morning haul").fetchSemanticsNode().size.height * 2)
        assertTrue(number > figure("312").size.height * 2)
    }

    @Test
    fun `at the normal size the board, the amount and the dock share the first screen`() {
        state = RevealUiState.Ready(mine)
        show()
        // Nothing has been scrolled: the title, the whole board and the amount are above the dock.
        val dock = rule.onNodeWithTag(RevealTags.CLOCK_OUT).fetchSemanticsNode().boundsInRoot.top
        rule.onNodeWithText("Morning haul").assertIsDisplayed()
        val board = rule.onNodeWithTag(REVEAL_BOARD_TAG).assertIsDisplayed().fetchSemanticsNode()
        assertEquals("the board is square", board.size.width, board.size.height)
        assertEquals("the whole board is on screen", board.unclipped(), board.boundsInRoot)
        val amount = figure("0.0058 ORE")
        assertTrue("the amount is above the dock: ${amount.unclipped()} against $dock", amount.unclipped().bottom < dock)
        // "Done" ends on the page's margin, level with the board's frame, and is a full touch target.
        val done = rule.onNodeWithTag(RevealTags.DONE).assertIsDisplayed().fetchSemanticsNode()
        val word = rule.onNodeWithText("Done", useUnmergedTree = true).fetchSemanticsNode().unclipped()
        assertEquals(680f, word.right, 1f)
        assertTrue("48dp by 48dp at least: ${done.size}", done.size.width >= 96 && done.size.height >= 96)
        assertTrue(done.unclipped().right <= 720f)
        // The featured line shares the label's row without touching it.
        val label = figure("ORE mined").unclipped()
        val featured = figure("Powered by ORE").unclipped()
        assertEquals(label.top, featured.top, 0.5f)
        assertTrue(featured.left - label.right >= 16f)
        fontScale = 1.3f
        rule.waitForIdle()
        assertEquals(figure("ORE mined").unclipped().top, figure("Powered by ORE").unclipped().top, 0.5f)
        assertTrue(figure("Powered by ORE").unclipped().left - figure("ORE mined").unclipped().right >= 16f)
        // Above 1.3 it has a line of its own, under the amount.
        fontScale = 1.5f
        rule.waitForIdle()
        assertTrue(figure("Powered by ORE").unclipped().top >= figure("0.0058 ORE").unclipped().bottom)
    }

    @Test
    fun `the notice that nothing was bought comes into view by itself`() {
        state = RevealUiState.Ready(sample)
        show()
        rule.onNodeWithTag(RevealTags.BUY).performScrollTo().performClick()
        rule.waitForIdle()
        // No scrolling by the test: the notice appears under the finger and the page follows it.
        val notice = rule.onNodeWithTag(RevealTags.BUY_STUB).assertIsDisplayed().fetchSemanticsNode()
        assertEquals("all of the notice is on screen", notice.unclipped(), notice.boundsInRoot)
        // With the page's 16dp under it, not flush against the dock's hairline (12dp above the bar).
        val dock = rule.onNodeWithTag(RevealTags.DONE).fetchSemanticsNode().boundsInRoot.top - 24f
        assertTrue("the notice ends ${dock - notice.boundsInRoot.bottom} px above the dock", dock - notice.boundsInRoot.bottom >= 30f)
        // A second tap changes nothing on the page, and the notice is still where it was.
        rule.onNodeWithTag(RevealTags.BUY).performClick()
        rule.waitForIdle()
        assertEquals(notice.boundsInRoot, rule.onNodeWithTag(RevealTags.BUY_STUB).fetchSemanticsNode().boundsInRoot)
    }

    // ------------------------------------------------------------------ the hours, the amount's ink, the page's width

    @Test
    fun `the hours are one line while they fit, and break at the dot when they do not`() {
        state = RevealUiState.Ready(sample)
        show()
        val hours = "23:10 – 06:50 · 7 h 40 m shift"
        // Read and matched as written, however it is drawn: exactly one node, at every size.
        fun box() = rule.onNodeWithText(hours).assertIsDisplayed().fetchSemanticsNode().size
        // The face is monospaced, and "First pickup 06:52" is set in the same style on one line at
        // every size: it gives the width of a letter (the system does not enlarge type in proportion).
        val pickup = "First pickup 06:52"
        fun letter() = figure(pickup).size.let { size -> size.width / pickup.length.toFloat() }
        fun line() = figure(pickup).size.height
        val oneLine = box()
        assertEquals("one line", line(), oneLine.height)
        assertEquals("the whole sentence on it", hours.length * letter(), oneLine.width.toFloat(), 1f)
        for (scale in listOf(1.3f, 2f)) {
            fontScale = scale
            rule.waitForIdle()
            val broken = box()
            assertEquals("two lines at $scale", 2 * line(), broken.height)
            // Each half on its own line, so the block is as wide as the longer half, "7 h 40 m shift".
            // Left to the line breaker it is "… · 7 h 40 m" over "shift" at 1.3, and "… ·" over the rest at 2.
            assertEquals("broken at the dot at $scale", "7 h 40 m shift".length * letter(), broken.width.toFloat(), 1f)
        }
        assertEquals("23:10 – 06:50\n7 h 40 m shift", brokenAtTheDot(hours))
        assertEquals(null, brokenAtTheDot("All night"))
        assertEquals(null, brokenAtTheDot("23:10 · 06:50 · 7 h"))
        assertEquals(null, brokenAtTheDot("23:10 – 06:50 · "))
    }

    private fun isGold(c: Color): Boolean {
        val argb = c.toArgb()
        return abs(((argb shr 16) and 0xFF) - 0xF2) <= 4 && abs(((argb shr 8) and 0xFF) - 0xB2) <= 4 && abs((argb and 0xFF) - 0x33) <= 4
    }

    private fun isChalk(c: Color): Boolean {
        val argb = c.toArgb()
        return abs(((argb shr 16) and 0xFF) - 0xEF) <= 4 && abs(((argb shr 8) and 0xFF) - 0xEA) <= 4 && abs((argb and 0xFF) - 0xE0) <= 4
    }

    private fun PixelMap.count(fromY: Int = 0, untilY: Int = height, test: (Color) -> Boolean): Int =
        (fromY until untilY).sumOf { y -> (0 until width).count { x -> test(this[x, y]) } }

    @Test
    fun `gold on the amount's plate is ORE that exists, and the featured line is never gold`() {
        show()
        // The label's row is the plate's first 16dp of padding and 18dp of type: 68 px.
        val labels = 68
        state = RevealUiState.Ready(sample)
        rule.waitForIdle()
        val mined = rule.onNodeWithText("0.0058 ORE").captureToImage().toPixelMap()
        assertTrue("the amount is gold", mined.count(fromY = labels, test = ::isGold) > 5_000)
        assertEquals("the featured line is not: gold as ink is for amounts of ORE", 0, mined.count(untilY = labels, test = ::isGold))
        assertTrue("it is chalk", mined.count(untilY = labels, test = ::isChalk) > 100)

        state = RevealUiState.Ready(closed)
        rule.waitForIdle()
        val nothing = rule.onNodeWithText("0 ORE").captureToImage().toPixelMap()
        assertEquals("a night that mined nothing has no gold on its plate", 0, nothing.count(test = ::isGold))
        assertTrue("\"0 ORE\" is said in chalk, as large", nothing.count(fromY = labels, test = ::isChalk) > 5_000)
    }

    @Test
    @Config(qualifiers = "w800dp-h360dp-land-xhdpi")
    fun `on a phone held on its side the page stays a column and the board a board`() {
        state = RevealUiState.Ready(mine)
        show()
        val screen = rule.onRoot().fetchSemanticsNode().size
        assertEquals(1600 to 720, screen.width to screen.height)
        // A column of 420dp in the middle: the board in it is 420dp less the plate's padding, not 736dp.
        val board = rule.onNodeWithTag(REVEAL_BOARD_TAG).fetchSemanticsNode().unclipped()
        assertEquals("the board is square", board.width, board.height)
        assertEquals("396dp wide", 792f, board.width, 1f)
        assertEquals("in the middle of the screen", 800f, board.center.x, 1f)
        val amount = rule.onNodeWithText("0.0058 ORE").fetchSemanticsNode().unclipped()
        assertEquals("the amount's plate is the column's width", 840f, amount.width, 1f)
        assertEquals(800f, amount.center.x, 1f)
        // The dock's bar is the same column, whatever the state.
        for ((name, shown) in everyState) {
            state = shown
            rule.waitForIdle()
            val bar = rule.onNodeWithTag(dockTag(shown)).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertEquals("$name: the bar is 420dp wide", 840f, bar.width, 1f)
            assertEquals("$name: the bar is in the middle", 800f, bar.center.x, 1f)
            assertTrue("$name: the bar is 56dp tall and on screen", bar.height >= 112f && bar.bottom <= 720f)
        }
    }

    @Test
    fun `no haul stands on the dock and is a plain column when there is no room to spare`() {
        state = RevealUiState.NoHaul
        show()
        val bar = rule.onNodeWithTag(RevealTags.DONE).fetchSemanticsNode().boundsInRoot
        val offer = rule.onNodeWithTag(RevealTags.SHOW_SAMPLE).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val statement = rule.onNodeWithText("No haul yet").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        // The offer ends the page: 24dp of page under it, then the dock's 12dp above its bar.
        assertEquals("the offer stands on the dock", bar.top - 24f - 48f, offer.bottom, 1f)
        assertTrue("the words are by the thumb, in the lower half: $statement", statement.top > 800f)
        assertTrue(statement.bottom < offer.top)
        // Nothing to scroll: all of it is on the one screen.
        assertEquals(offer, rule.onNodeWithTag(RevealTags.SHOW_SAMPLE).fetchSemanticsNode().unclipped())
    }

    @Test
    @Config(qualifiers = "w320dp-h470dp-xhdpi")
    fun `on a short screen no haul keeps its order and its gaps, and scrolls`() {
        state = RevealUiState.NoHaul
        fontScale = 2f
        show()
        // No room to spare: the spaces around the slab are closed, and the heading follows the
        // brand row (48dp, 4dp from the top) and the slab (112dp) at the page's 16dp.
        val statement = rule.onNodeWithText("No haul yet").assertIsDisplayed().fetchSemanticsNode().unclipped()
        assertEquals("16dp under the slab", (4 + 48 + 16 + 112 + 16) * 2f, statement.top, 2f)
        val offer = rule.onNodeWithTag(RevealTags.SHOW_SAMPLE).fetchSemanticsNode()
        assertTrue("the offer is further down the page than the screen is tall", offer.unclipped().bottom > rule.onNodeWithTag(RevealTags.DONE).fetchSemanticsNode().boundsInRoot.top)
        rule.onNodeWithTag(RevealTags.SHOW_SAMPLE).performScrollTo().assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w360dp-h742dp-xhdpi")
    fun `with the status bar and three-button navigation off the Redmi's 820dp, the amount is still on the first screen`() {
        // 360dp by 820dp, less about 30dp of status bar and 48dp of navigation bar: 742dp for the page and its dock.
        state = RevealUiState.Ready(mine)
        show()
        val dock = rule.onNodeWithTag(RevealTags.CLOCK_OUT).assertIsDisplayed().fetchSemanticsNode().boundsInRoot.top - 24f
        val board = rule.onNodeWithTag(REVEAL_BOARD_TAG).assertIsDisplayed().fetchSemanticsNode()
        assertEquals("the whole board is on screen", board.unclipped(), board.boundsInRoot)
        // The digits stand on their baseline above the dock (the line's empty descent may be under it).
        val amount = figure("0.0058 ORE")
        val baseline = amount.unclipped().top + amount.textLayout()!!.firstBaseline
        assertTrue("the amount's digits end at $baseline, the dock begins at $dock", baseline + 16f <= dock)
    }

    @Test
    fun `the frame is seen turning over while the replay is already running`() {
        rule.mainClock.autoAdvance = false
        state = RevealUiState.Ready(mine)
        show(animate = true)
        rule.mainClock.advanceTimeBy(32)
        val board = rule.onNodeWithTag(REVEAL_BOARD_TAG).fetchSemanticsNode()
        assertTrue(board.config[SemanticsProperties.ContentDescription].single().startsWith("Replaying 312 rounds"))
        // A point of the plate's own padding, left of the board near its top: slab when the plate is flat.
        val x = (board.unclipped().left - 12f).toInt()
        val y = (board.unclipped().top + 40f).toInt()
        val turning = rule.onRoot().captureToImage().toPixelMap()
        assertColor("32 ms in, the plate lies back: the page shows through where its top will be", HdPalette.Dark.pit, turning[x, y])
        rule.mainClock.advanceTimeBy(600)
        val flat = rule.onRoot().captureToImage().toPixelMap()
        assertColor("after the entrance the plate is flat", HdPalette.Dark.slab, flat[x, y])
        val later = rule.onNodeWithTag(REVEAL_BOARD_TAG).fetchSemanticsNode().config[SemanticsProperties.ContentDescription].single()
        assertTrue("and the replay is still running: $later", later.startsWith("Replaying"))
    }

    // ------------------------------------------------------------------ what the board is drawn in

    private companion object {
        /** Robolectric's switch for PixelCopy: "hardware" draws through a real renderer. */
        const val RENDER_MODE = "robolectric.pixelCopyRenderMode"
    }

    private fun PixelMap.at(x: Float, y: Float): Color = this[x.toInt(), y.toInt()]

    private fun assertColor(what: String, expected: Color, actual: Color, tolerance: Int = 3) {
        val e = expected.toArgb()
        val a = actual.toArgb()
        val far = listOf(16, 8, 0).maxOf { shift -> abs(((e shr shift) and 0xFF) - ((a shr shift) and 0xFF)) }
        assertTrue("$what: expected ${Integer.toHexString(e)}, drawn ${Integer.toHexString(a)}", far <= tolerance)
    }

    private fun light(c: Color): Int = c.toArgb().let { ((it shr 16) and 0xFF) + ((it shr 8) and 0xFF) + (it and 0xFF) }

    @Test
    fun `the final board is gold light from underneath on dark wells, a hit solid gold in a chalk outline, and no ember`() {
        state = RevealUiState.Ready(sample)
        show()
        val colors = HdPalette.Dark
        val replay = BoardReplay(sample.rounds)
        val final = replay.finalFrame()
        val pixels = rule.onNodeWithTag(REVEAL_BOARD_TAG).captureToImage().toPixelMap()
        assertEquals(pixels.width, pixels.height)
        val gap = 8f // 4dp
        val cell = (pixels.width - gap * (Board.SIZE - 1)) / Board.SIZE
        var hits = 0
        var dug = 0
        var empty = 0
        for (tile in 0 until Board.TILES) {
            val left = (tile % Board.SIZE) * (cell + gap)
            val top = (tile / Board.SIZE) * (cell + gap)
            val centre = pixels.at(left + cell / 2, top + cell / 2)
            val heat = final.heat[tile]
            when {
                tile in replay.hitTiles -> {
                    hits++
                    assertColor("tile $tile came up: solid gold", colors.seamLight, centre)
                    // The outline is 2dp of chalk just inside the tile's edge.
                    assertColor("tile $tile: a chalk outline", colors.chalk, pixels.at(left + 2f, top + cell / 2))
                    assertColor("tile $tile: a chalk outline", colors.chalk, pixels.at(left + cell / 2, top + cell - 2f))
                }
                heat > 0 -> {
                    dug++
                    // Lit from its lower edge: from 22% of the gold for one dig to 90% for the busiest
                    // tile there, and 28% of that at the upper edge. Sampled a pixel inside each edge.
                    val strength = 0.22f + 0.68f * heat / final.maxHeat
                    fun lightAt(fromTop: Float) = colors.seamLight.copy(alpha = strength * (0.28f + 0.72f * fromTop / cell)).compositeOver(colors.pit)
                    val upper = pixels.at(left + cell / 2, top + 1f)
                    val lower = pixels.at(left + cell / 2, top + cell - 2f)
                    assertColor("tile $tile was dug $heat times: its lower edge", lightAt(cell - 1.5f), lower, tolerance = 5)
                    assertColor("tile $tile was dug $heat times: its upper edge", lightAt(1.5f), upper, tolerance = 5)
                    assertColor("tile $tile was dug $heat times: its middle", lightAt(cell / 2 + 0.5f), centre, tolerance = 5)
                    assertTrue("tile $tile: the light comes from underneath", light(lower) > light(centre) && light(centre) > light(upper))
                    assertTrue("never a solid tile: solid is a hit", strength <= 0.9f)
                    assertFalse("tile $tile is not mistaken for a hit", isGold(centre))
                }
                else -> {
                    empty++
                    assertColor("tile $tile was never dug: a dark well", colors.pit, centre)
                }
            }
            // Ember means a problem now: no tile is drawn in it.
            val argb = centre.toArgb()
            assertFalse("tile $tile is ember", abs(((argb shr 16) and 0xFF) - 0xE8) <= 3 && abs(((argb shr 8) and 0xFF) - 0x62) <= 3 && abs((argb and 0xFF) - 0x2A) <= 3)
        }
        assertTrue("the sample night has all three kinds of tile: $hits hits, $dug dug, $empty empty", hits >= 1 && dug >= 1 && empty >= 1)
        // Between the tiles is the slab the board is set in.
        assertColor("the frame is the slab", colors.slab, pixels.at(cell + gap / 2, cell / 2))
    }
}
