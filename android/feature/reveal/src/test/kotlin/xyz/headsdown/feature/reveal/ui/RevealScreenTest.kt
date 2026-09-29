package xyz.headsdown.feature.reveal.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import xyz.headsdown.feature.reveal.haul.FakeHaulRepository
import xyz.headsdown.feature.reveal.haul.HaulProvenance
import xyz.headsdown.feature.reveal.haul.HaulSummary
import xyz.headsdown.feature.reveal.haul.HonestCopy
import xyz.headsdown.feature.reveal.haul.RoundReplay
import java.time.LocalDateTime
import java.time.ZoneId

/** Compose UI tests of the reveal, on Robolectric (JVM, no device). */
@RunWith(RobolectricTestRunner::class)
class RevealScreenTest {
    @get:Rule val rule = createComposeRule()

    private val zone = ZoneId.of("Africa/Lagos")
    private val now = LocalDateTime.of(2026, 9, 30, 7, 5).atZone(zone).toInstant().toEpochMilli()
    private val night = FakeHaulRepository.sampleNight(now, zone)

    private fun show(
        summary: HaulSummary,
        animate: Boolean = false,
        onStarted: () -> Unit = {},
        onFinished: () -> Unit = {},
        onBuy: () -> Unit = {},
        onShare: () -> Unit = {},
    ) = rule.setContent {
        RevealTheme {
            RevealScreen(summary, zone, animate, onStarted, onFinished, onBuy, onShare, onDone = {})
        }
    }

    @Test
    fun `shows the counts, the price against market and the streak`() {
        show(night)
        rule.onNodeWithText("Morning haul").assertIsDisplayed()
        rule.onNodeWithText("312").assertExists()
        rule.onNodeWithText("18").assertExists()
        rule.onNodeWithText("0.0180 SOL").assertExists()
        rule.onNodeWithText("0.0058 ORE").assertExists()
        rule.onNodeWithText("0.680 SOL per ORE").assertExists()
        rule.onNodeWithText("Market 0.758").assertExists()
        rule.onNodeWithTag(RevealTags.VERDICT).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Mining was the cheaper route last night: 10% below market.").assertExists()
        rule.onNodeWithTag(RevealTags.STREAK).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Streak 22 → 23").assertExists()
    }

    @Test
    fun `sample data is labelled, on-chain data is not`() {
        show(night)
        rule.onNodeWithTag(RevealTags.SAMPLE_BADGE).assertIsDisplayed()
    }

    @Test
    fun `on-chain haul has no sample badge`() {
        show(night.copy(provenance = HaulProvenance.ON_CHAIN))
        rule.onAllNodesWithTag(RevealTags.SAMPLE_BADGE).assertCountEquals(0)
    }

    @Test
    fun `buy the rest is a stub that says nothing was bought`() {
        var taps = 0
        show(night, onBuy = { taps++ })
        rule.onAllNodesWithTag(RevealTags.BUY_STUB).assertCountEquals(0)
        rule.onNodeWithTag(RevealTags.BUY).performScrollTo().performClick()
        assertEquals(1, taps)
        rule.onNodeWithTag(RevealTags.BUY_STUB).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("The market buy leg is not connected yet. Nothing was bought.").assertExists()
    }

    @Test
    fun `no target means no buy button`() {
        show(night.copy(nightlyTargetOreAtoms = null))
        rule.onAllNodesWithTag(RevealTags.BUY).assertCountEquals(0)
    }

    @Test
    fun `share goes to the caller`() {
        var shared = 0
        show(night, onShare = { shared++ })
        rule.onNodeWithTag(RevealTags.SHARE).performScrollTo().performClick()
        assertEquals(1, shared)
    }

    @Test
    fun `the board replays, then reports how it ended`() {
        val events = mutableListOf<String>()
        rule.mainClock.autoAdvance = false
        show(night, animate = true, onStarted = { events += "start" }, onFinished = { events += "end" })
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithTag(REVEAL_BOARD_TAG).assertExists()
        assertTrue(boardDescription().startsWith("Replaying 312 rounds"))
        rule.mainClock.advanceTimeBy(3_500)
        rule.waitForIdle()
        assertEquals(listOf("start", "end"), events)
        val hits = night.rounds.filter { it.hit }.map { it.winningTile }.toSet().size
        assertEquals("Board replay finished: 312 rounds. Your tiles came up on $hits of 25 squares.", boardDescription())
    }

    @Test
    fun `reduced motion shows the final board at once`() {
        val events = mutableListOf<String>()
        show(night, animate = false, onStarted = { events += "start" }, onFinished = { events += "end" })
        rule.waitForIdle()
        assertEquals(listOf("end"), events)
        assertTrue(boardDescription().startsWith("Board replay finished"))
    }

    @Test
    fun `motherlode near miss is shown plainly`() {
        val miss = night.copy(
            rounds = night.rounds + RoundReplay(999_999, dugMask = 1, winningTile = 24, motherlode = true, endedAtWallMillis = night.endedAtWallMillis - 60_000),
        )
        show(miss)
        rule.onNodeWithText("The Motherlode hit the board at 06:49, on a tile your rig did not dig.").assertExists()
    }

    @Test
    fun `nothing on screen breaks the honesty rules`() {
        var shown by mutableStateOf(night)
        rule.setContent { RevealTheme { RevealScreen(shown, zone, animate = false) } }
        listOf(night, FakeHaulRepository.sampleGateClosed(now, zone), night.copy(effectiveLamportsPerOre = 1_190_000_000L))
            .forEach { h ->
                shown = h
                rule.waitForIdle()
                val texts = screenTexts()
                assertTrue(texts.size > 10)
                texts.forEach { s -> assertTrue("banned words in: $s", HonestCopy.violations(s).isEmpty()) }
            }
    }

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

    private fun boardDescription(): String =
        rule.onNodeWithTag(REVEAL_BOARD_TAG).fetchSemanticsNode().config[SemanticsProperties.ContentDescription].single()
}
