package xyz.headsdown.feature.reveal.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import xyz.headsdown.core.design.HdMotion
import xyz.headsdown.core.design.LocalHdMotion
import xyz.headsdown.feature.reveal.board.BoardReplay
import xyz.headsdown.feature.reveal.haul.FakeHaulRepository
import xyz.headsdown.feature.reveal.haul.HaulProvenance
import xyz.headsdown.feature.reveal.haul.HonestCopy
import xyz.headsdown.feature.reveal.haul.RevealCopyBuilder
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * What the rebuilt reveal promises beyond RevealScreenTest: one way out in every state, a still
 * loading state that says what it is doing, an entrance that never holds the replay back, a
 * screen that draws nothing once the replay is over, and new words that pass the honesty rules.
 */
@RunWith(RobolectricTestRunner::class)
class RevealRedesignTest {
    @get:Rule val rule = createComposeRule()

    private val zone = ZoneId.of("Africa/Lagos")
    private val now = LocalDateTime.of(2026, 9, 30, 7, 5).atZone(zone).toInstant().toEpochMilli()
    private val sample = FakeHaulRepository.sampleNight(now, zone)
    private val mine = sample.copy(provenance = HaulProvenance.ON_CHAIN, explorerUrl = "https://explorer.solana.com/address/x")

    private fun route(state: RevealUiState, animate: Boolean = false, onDone: () -> Unit = {}, onClockOut: (() -> Unit)? = null) =
        rule.setContent {
            RevealRoute(
                state = state, zone = zone, animate = animate,
                onReplayStarted = {}, onReplayFinished = {}, onBuyRest = {}, onShare = {}, onDone = onDone,
                onShowSample = {}, onClockOut = onClockOut,
            )
        }

    /** Every text and description on screen, in the order a screen reader meets them. */
    private fun screenTexts(): List<String> {
        val out = mutableListOf<String>()
        fun walk(n: SemanticsNode) {
            n.config.getOrNull(SemanticsProperties.Text)?.forEach { out += it.text }
            n.config.getOrNull(SemanticsProperties.ContentDescription)?.let { out += it }
            n.children.forEach(::walk)
        }
        walk(rule.onRoot(useUnmergedTree = true).fetchSemanticsNode())
        return out
    }

    private val isLive = SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion)
    private val isPolite = SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)
    private val isHeading = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)
    private val isButton = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)

    // ------------------------------------------------------------------ loading

    @Test
    fun `loading shows a still outline, no progress indicator and exactly one status sentence`() {
        var done = 0
        route(RevealUiState.Loading, onDone = { done++ })
        rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo), useUnmergedTree = true).assertCountEquals(0)
        // One polite live region, and it is the sentence: a screen reader says it once.
        rule.onAllNodes(isLive, useUnmergedTree = true).assertCountEquals(1)
        rule.onNodeWithText(RevealWords.LOADING).assertIsDisplayed().assert(isPolite)
        // The skeleton and the empty board are nothing to a screen reader: three texts in all.
        assertEquals(listOf("HEADS DOWN", RevealWords.LOADING, "Done"), screenTexts())
        rule.onAllNodesWithTag(REVEAL_BOARD_TAG).assertCountEquals(0)
        // And there is a way out while it reads.
        rule.onNodeWithTag(RevealTags.DONE).assertTextEquals("Done").assert(isButton).performClick()
        assertEquals(1, done)
    }

    // ------------------------------------------------------------------ one way out, one thing to do

    @Test
    fun `a sample night shows exactly one Done, in the dock, and no clock-out`() {
        var done = 0
        rule.setContent { RevealTheme { RevealScreen(sample, zone, animate = false, onDone = { done++ }, onClockOut = {}) } }
        rule.onAllNodesWithText("Done").assertCountEquals(1)
        rule.onAllNodesWithTag(RevealTags.CLOCK_OUT).assertCountEquals(0)
        rule.onAllNodesWithText(RevealCopyBuilder.CLOCK_OUT_BUTTON).assertCountEquals(0)
        rule.onAllNodesWithText(RevealCopyBuilder.CLOCK_OUT_DETAIL).assertCountEquals(0)
        // The docked bar: displayed without scrolling, at the bottom of the screen.
        rule.onNodeWithTag(RevealTags.DONE).assertTextEquals("Done").assert(isButton).assertIsDisplayed().performClick()
        assertEquals(1, done)
        assertEquals("the dock is the last thing on the screen", "Done", screenTexts().last())
    }

    @Test
    fun `a haul of the user's own puts Clock out in the dock and Done at the top, once each`() {
        var done = 0
        var opened = 0
        rule.setContent { RevealTheme { RevealScreen(mine, zone, animate = false, onDone = { done++ }, onClockOut = { opened++ }) } }
        rule.onAllNodesWithText("Done").assertCountEquals(1)
        rule.onAllNodesWithText(RevealCopyBuilder.CLOCK_OUT_BUTTON).assertCountEquals(1)
        // Both are on screen before any scrolling.
        rule.onNodeWithTag(RevealTags.DONE).assertTextEquals("Done").assert(isButton).assertIsDisplayed().performClick()
        rule.onNodeWithTag(RevealTags.CLOCK_OUT).assertTextEquals("Clock out").assert(isButton).assertIsDisplayed().performClick()
        assertEquals(1 to 1, done to opened)
        // What the bar does is the last sentence of the page, said right before the bar itself.
        assertEquals(listOf(RevealCopyBuilder.CLOCK_OUT_DETAIL, "Clock out"), screenTexts().takeLast(2))
        val texts = screenTexts()
        assertTrue("\"Done\" comes before the page", texts.indexOf("Done") < texts.indexOf("Morning haul"))
    }

    @Test
    fun `without a clock-out to offer, a haul of the user's own has Done in the dock`() {
        rule.setContent { RevealTheme { RevealScreen(mine, zone, animate = false) } }
        rule.onAllNodesWithText("Done").assertCountEquals(1)
        rule.onAllNodesWithTag(RevealTags.CLOCK_OUT).assertCountEquals(0)
        assertEquals("Done", screenTexts().last())
    }

    @Test
    fun `a simulated night is labelled, and still the user's shift to clock out of`() {
        // Only a sample is nobody's shift. A night the indexer marks simulated is the user's own rig
        // on a test chain: it keeps its clock-out, as before the redesign.
        rule.setContent { RevealTheme { RevealScreen(mine.copy(provenance = HaulProvenance.SIMULATED), zone, animate = false, onClockOut = {}) } }
        rule.onNodeWithTag(RevealTags.SAMPLE_BADGE).assertIsDisplayed().assertTextEquals("SIMULATED · not on-chain data")
        rule.onAllNodesWithTag(RevealTags.CLOCK_OUT).assertCountEquals(1)
        rule.onAllNodesWithText("Done").assertCountEquals(1)
    }

    @Test
    fun `no haul and loading each show exactly one Done`() {
        var state by mutableStateOf<RevealUiState>(RevealUiState.NoHaul)
        var done = 0
        rule.setContent {
            RevealRoute(
                state = state, zone = zone, animate = false,
                onReplayStarted = {}, onReplayFinished = {}, onBuyRest = {}, onShare = {}, onDone = { done++ },
                onShowSample = {}, onClockOut = {},
            )
        }
        for (shown in listOf(RevealUiState.NoHaul, RevealUiState.Loading, RevealUiState.Ready(sample), RevealUiState.Ready(mine))) {
            state = shown
            rule.waitForIdle()
            rule.onAllNodesWithText("Done").assertCountEquals(1)
            rule.onNodeWithTag(RevealTags.DONE).assertHasClickAction().performClick()
        }
        assertEquals(4, done)
    }

    // ------------------------------------------------------------------ what a screen reader is told

    @Test
    fun `the page has one heading, and what changes is said politely`() {
        rule.setContent { RevealTheme { RevealScreen(sample, zone, animate = false) } }
        rule.onAllNodes(isHeading).assertCountEquals(1)
        rule.onNodeWithText("Morning haul").assert(isHeading).assertIsDisplayed()
        // The board says how the replay ended; nothing else on a still page is a live region.
        rule.onNodeWithTag(REVEAL_BOARD_TAG).assert(isPolite)
        rule.onAllNodes(isLive, useUnmergedTree = true).assertCountEquals(1)
        // The sample label is drawn in capitals and read as written.
        rule.onNodeWithTag(RevealTags.SAMPLE_BADGE).assertTextEquals("SAMPLE NIGHT · not your data")
        // The amount, its label and the featured line are one node, in that order.
        rule.onNodeWithText("0.0058 ORE").assertTextEquals("ORE mined", "0.0058 ORE", "Powered by ORE")
        // So are the price and the market's; the verdict is its own sentence.
        rule.onNodeWithText("0.680 SOL per ORE").assertTextEquals("Effective price per ORE", "0.680 SOL per ORE", "Market 0.758")
        rule.onNodeWithTag(RevealTags.VERDICT).assertTextEquals("Mining was the cheaper route last night: 10% below market.")
    }

    @Test
    fun `a page exposes words and actions only, in reading order, with the dock last`() {
        var shown by mutableStateOf(sample)
        rule.setContent { RevealTheme { RevealScreen(shown, zone, animate = false, onClockOut = {}) } }
        for (haul in listOf(sample, mine, FakeHaulRepository.sampleGateClosed(now, zone))) {
            shown = haul
            rule.waitForIdle()
            // Every node that has no child says something: no hairline, glyph or plate is a node of its own.
            val leaves = mutableListOf<SemanticsNode>()
            fun walk(n: SemanticsNode) {
                if (n.children.isEmpty()) leaves += n else n.children.forEach(::walk)
            }
            walk(rule.onRoot().fetchSemanticsNode())
            for (leaf in leaves) {
                val says = leaf.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text } ?: leaf.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
                assertTrue("a node of ${leaf.size} at ${leaf.positionInRoot} says nothing", !says.isNullOrBlank())
            }
            // The hours are one node, read as the sentence was written.
            rule.onAllNodesWithText("23:10 – 06:50 · 7 h 40 m shift").assertCountEquals(1)
        }
        // Top to bottom for a haul of the user's own: the way out, the title, the hours, the board and
        // its legend, the amount, the counts, the price, and the dock's bar after everything else.
        shown = mine
        rule.waitForIdle()
        val texts = screenTexts()
        val order = listOf(
            "HEADS DOWN", "Done", "Morning haul", "23:10 – 06:50 · 7 h 40 m shift",
            "Board replay finished: 312 rounds. Your tiles came up on 1 of 25 squares.", RevealWords.LEGEND,
            "ORE mined", "0.0058 ORE", "Powered by ORE", "Rounds dark", "312", "Digs", "18", "SOL placed", "0.0180 SOL",
            "Effective price per ORE", "0.680 SOL per ORE", "Market 0.758",
        )
        assertEquals(order, texts.take(order.size))
        assertEquals("Clock out", texts.last())
        // Each count is one node with its label, so it is read "Rounds dark, 312" and not as two things.
        rule.onNodeWithText("312").assertTextEquals("Rounds dark", "312")
        rule.onNodeWithText("18").assertTextEquals("Digs", "18")
        rule.onNodeWithText("0.0180 SOL").assertTextEquals("SOL placed", "0.0180 SOL")
        // The streak is drawn in capitals and read as written.
        rule.onNodeWithTag(RevealTags.STREAK).assertTextEquals("Streak 22 → 23")
    }

    @Test
    fun `the notice that nothing was bought is a polite live region`() {
        rule.setContent { RevealTheme { RevealScreen(sample, zone, animate = false) } }
        rule.onNodeWithTag(RevealTags.BUY).performScrollTo().assert(isButton).performClick()
        rule.onNodeWithText("The market buy leg is not connected yet. Nothing was bought.").assert(isPolite)
    }

    @Test
    fun `no haul is a heading, a sentence and the offer of a labelled sample`() {
        route(RevealUiState.NoHaul)
        rule.onNodeWithText("No haul yet").assert(isHeading).assertIsDisplayed()
        rule.onAllNodes(isHeading).assertCountEquals(1)
        rule.onNodeWithTag(RevealTags.SHOW_SAMPLE).assertTextEquals("See a sample night (not your data)").assert(isButton).assertIsDisplayed()
        // The slab is a drawing: the words say there is no haul.
        assertEquals(
            listOf("HEADS DOWN", "No haul yet", "Your first haul appears after your first mined shift.", "See a sample night (not your data)", "Done"),
            screenTexts(),
        )
    }

    // ------------------------------------------------------------------ the entrance and the replay

    @Test
    fun `the entrance flip does not delay the replay's start or its end`() {
        val events = mutableListOf<String>()
        // The flip does run here: this environment has not removed animations, so the theme's motion is not reduced.
        assertFalse(HdMotion.systemReduced())
        rule.mainClock.autoAdvance = false
        rule.setContent {
            RevealTheme { RevealScreen(mine, zone, animate = true, onReplayStarted = { events += "start" }, onReplayFinished = { events += "end" }) }
        }
        rule.mainClock.advanceTimeByFrame()
        // Started with the first frame, while the frame is still turning over.
        assertEquals(listOf("start"), events)
        assertTrue(boardDescription().startsWith("Replaying 312 rounds"))
        // 312 rounds replay in 3,000 ms and the last flash fades for 160 ms: not over before that...
        val total = BoardReplay.durationFor(mine.rounds.size) + BoardReplay.FLASH_MILLIS
        assertEquals(3_160L, total)
        rule.mainClock.advanceTimeBy(total - 56)
        rule.waitForIdle()
        assertEquals(listOf("start"), events)
        // ...and over within a few frames of it, exactly as without an entrance.
        rule.mainClock.advanceTimeBy(56 + 64)
        rule.waitForIdle()
        assertEquals(listOf("start", "end"), events)
        assertTrue(boardDescription().startsWith("Board replay finished: 312 rounds."))
    }

    @Test
    fun `the frame turns over from 80 degrees to flat in under 400 ms, on its own clock`() {
        lateinit var turn: Animatable<Float, AnimationVector1D>
        rule.mainClock.autoAdvance = false
        rule.setContent {
            CompositionLocalProvider(LocalHdMotion provides HdMotion(reduced = false)) {
                turn = rememberTurnOver(replay = "a night", animate = true)
                Box(Modifier.size(100.dp).turnedOver(turn))
            }
        }
        assertEquals(TURN_OVER_FROM_DEGREES, turn.value, 0f)
        assertEquals(80f, TURN_OVER_FROM_DEGREES, 0f)
        rule.mainClock.advanceTimeBy(96)
        assertTrue("on its way after 96 ms: ${turn.value}", turn.isRunning && turn.value > 5f && turn.value < 75f)
        rule.mainClock.advanceTimeBy(304)
        rule.waitForIdle()
        assertEquals("flat after 400 ms", 0f, turn.value, 0f)
        assertFalse(turn.isRunning)
        assertFalse(pendingWork())
    }

    @Test
    fun `without animation, or with the system's animations removed, the frame is flat from the start`() {
        val turns = mutableListOf<Animatable<Float, AnimationVector1D>>()
        rule.mainClock.autoAdvance = false
        rule.setContent {
            CompositionLocalProvider(LocalHdMotion provides HdMotion(reduced = false)) { turns += rememberTurnOver("a", animate = false) }
            CompositionLocalProvider(LocalHdMotion provides HdMotion(reduced = true)) { turns += rememberTurnOver("b", animate = true) }
        }
        rule.mainClock.advanceTimeByFrame()
        assertEquals(2, turns.size)
        for (turn in turns) {
            assertEquals(0f, turn.value, 0f)
            assertFalse(turn.isRunning)
        }
        assertFalse(pendingWork())
    }

    // ------------------------------------------------------------------ stillness

    /** Whether any composition is waiting for a frame or has something left to recompose. */
    private fun pendingWork(): Boolean = Recomposer.runningRecomposers.value.any { it.hasPendingWork }

    private fun advance(millis: Long) {
        rule.mainClock.advanceTimeBy(millis)
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

    private fun assertStill(what: String) {
        assertFalse("$what: something is still waiting for a frame", pendingWork())
        assertEquals("$what: state written at rest", 0, writesDuring(2_000))
        assertFalse(what, pendingWork())
    }

    @Test
    fun `loading and no haul draw nothing once they are on screen`() {
        var state by mutableStateOf<RevealUiState>(RevealUiState.Loading)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            RevealRoute(
                state = state, zone = zone, animate = true,
                onReplayStarted = {}, onReplayFinished = {}, onBuyRest = {}, onShare = {}, onDone = {}, onShowSample = {},
            )
        }
        advance(500)
        assertStill("loading")
        state = RevealUiState.NoHaul
        rule.waitForIdle()
        advance(500)
        assertStill("no haul")
    }

    @Test
    fun `the replay keeps the screen drawing, and when it is over nothing is drawn again`() {
        rule.mainClock.autoAdvance = false
        rule.setContent { RevealTheme { RevealScreen(mine, zone, animate = true, onClockOut = {}) } }
        advance(1_000)
        // The check does see the replay: a frame is awaited and the elapsed time is written.
        assertTrue("the replay is running", pendingWork())
        assertTrue(writesDuring(160) > 0)
        advance(2_400)
        assertTrue(boardDescription().startsWith("Board replay finished"))
        assertStill("after the replay")
    }

    @Test
    fun `the page is still again once the buy notice has come into view`() {
        rule.setContent { RevealTheme { RevealScreen(sample, zone, animate = false) } }
        // (The clock runs by itself for the taps: performScrollTo never returns under a paused clock.)
        // The bar gives and springs back, the notice appears and the page scrolls to it. Waiting for
        // idle would itself time out if any of that never ended.
        rule.onNodeWithTag(RevealTags.BUY).performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(RevealTags.BUY_STUB).assertExists()
        rule.mainClock.autoAdvance = false
        advance(500)
        assertStill("after the buy notice")
        // A second tap asks for the notice again and then stops too.
        rule.mainClock.autoAdvance = true
        rule.onNodeWithTag(RevealTags.BUY).performClick()
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        advance(500)
        assertStill("after a second tap")
    }

    @Test
    fun `with animation off the page is still from its first frame`() {
        rule.mainClock.autoAdvance = false
        rule.setContent { RevealTheme { RevealScreen(sample, zone, animate = false) } }
        advance(100)
        assertStill("no animation")
    }

    // ------------------------------------------------------------------ the new words

    @Test
    fun `the legend, the status sentence and the featured line pass the honesty rules and are on screen`() {
        val written = listOf(RevealWords.LEGEND, RevealWords.LOADING, RevealWords.POWERED_BY)
        for (text in written) assertEquals(text, emptyList<String>(), HonestCopy.violations(text))
        // Sentence case: a capital first, and no word in capitals but the name of what is mined.
        for (text in written) {
            assertTrue(text, text.first().isUpperCase())
            assertEquals(text, emptyList<String>(), Regex("""\b[A-Z]{2,}\b""").findAll(text).map { it.value }.filter { it != "ORE" }.toList())
        }
        // The legend names the colours the board is drawn in now: ember is for problems.
        assertTrue(RevealWords.LEGEND.startsWith("Gold glow: where your rig dug."))
        assertFalse(RevealWords.LEGEND.contains("ember", ignoreCase = true))

        var state by mutableStateOf<RevealUiState>(RevealUiState.Ready(sample))
        rule.setContent {
            RevealRoute(
                state = state, zone = zone, animate = false,
                onReplayStarted = {}, onReplayFinished = {}, onBuyRest = {}, onShare = {}, onDone = {}, onShowSample = {},
            )
        }
        rule.onAllNodesWithText(RevealWords.LEGEND).assertCountEquals(1)
        assertEquals(1, screenTexts().count { it == RevealWords.POWERED_BY })
        for (shown in listOf(RevealUiState.Ready(sample), RevealUiState.Loading, RevealUiState.NoHaul)) {
            state = shown
            rule.waitForIdle()
            screenTexts().forEach { s -> assertTrue("banned words in: $s", HonestCopy.violations(s).isEmpty()) }
        }
        rule.onAllNodesWithText(RevealWords.LOADING).assertCountEquals(0)
    }

    private fun boardDescription(): String =
        rule.onNodeWithTag(REVEAL_BOARD_TAG).fetchSemanticsNode().config[SemanticsProperties.ContentDescription].single()
}
