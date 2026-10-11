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

    private fun show(clockOut: Boolean = true) = rule.setContent {
        val device = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(device.density, fontScale)) {
            RevealRoute(
                state = state, zone = zone, animate = false,
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
    private fun assertNothingIsCut(what: String, dockTag: String) {
        val screen = rule.onRoot().fetchSemanticsNode().size
        assertEquals("a 360dp by 800dp screen at 2 px to the dp", 720 to 1600, screen.width to screen.height)
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
        assertEquals("$what: the bar spans the margins", 40f to 680f, bar.boundsInRoot.left to bar.boundsInRoot.right)
    }

    private fun figure(text: String): SemanticsNode = rule.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode()

    @Test
    fun `every state fits a 360dp screen from font scale 1 to 2, and no figure is cut`() {
        show()
        val states = listOf(
            "loading" to RevealUiState.Loading,
            "no haul" to RevealUiState.NoHaul,
            "a sample night" to RevealUiState.Ready(sample),
            "a haul of the user's own" to RevealUiState.Ready(mine),
            "a night that placed nothing" to RevealUiState.Ready(closed),
            "a night of long figures" to RevealUiState.Ready(heavy),
        )
        for (scale in listOf(1f, 1.3f, 1.5f, 2f)) {
            for ((name, shown) in states) {
                fontScale = scale
                state = shown
                rule.waitForIdle()
                val clockOut = shown is RevealUiState.Ready && shown.summary.provenance != HaulProvenance.SAMPLE
                assertNothingIsCut("$name at $scale", dockTag = if (clockOut) RevealTags.CLOCK_OUT else RevealTags.DONE)
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

    @Test
    fun `the final board is gold light on dark wells, a hit solid gold in a chalk outline, and no ember`() {
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
                    val alpha = 0.16f + 0.44f * heat / final.maxHeat
                    assertColor("tile $tile was dug $heat times: gold light", colors.seamLight.copy(alpha = alpha).compositeOver(colors.pit), centre)
                    assertTrue("never solid: solid is a hit", alpha <= 0.6f)
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
