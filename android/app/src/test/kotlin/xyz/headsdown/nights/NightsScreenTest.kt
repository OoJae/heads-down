package xyz.headsdown.nights

import android.app.Application
import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import xyz.headsdown.config.ExplorerLinks
import xyz.headsdown.core.chain.indexer.HaulMode
import xyz.headsdown.core.chain.indexer.IndexerShift
import xyz.headsdown.ui.theme.HeadsDownTheme
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Compose UI tests for the night log (Robolectric, no Hilt app). */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class NightsScreenTest {
    private val rule = createComposeRule()

    /** Same reason as HomeAndIntroTest: the app's manifest has no ui-test host activity. */
    private val hostActivity = object : ExternalResource() {
        override fun before() {
            val app = ApplicationProvider.getApplicationContext<Application>()
            shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
        }
    }

    @get:Rule val rules: RuleChain = RuleChain.outerRule(hostActivity).around(rule)

    private companion object {
        const val SIG = "5VERv8NMvzbJMEkV8xnrLkEaWRtSz9CosKDYjCJjBRnbJLgp8uirBgmQpjKhoR4tjF3ZpRzrFmBV6UjKdiSZkQUW"
    }

    private val tokyo = ZoneId.of("Asia/Tokyo")
    private val today = LocalDate.of(2026, 10, 5)

    private fun unix(iso: String) = Instant.parse(iso).epochSecond

    private val rig = RigNights(streak = 3, freezesLeft = 1, lastShiftDay = 20_730, lifetimeDarkRounds = 1_240uL, lifetimeRoundsDug = 318uL)

    /** Newest first, as the model hands them over: sealed on 5 October, 1 October and 30 September, Tokyo time. */
    private val shifts = listOf(
        IndexerShift(7uL, unix("2026-10-04T21:28:00Z"), darkRounds = 5uL, roundsDug = 5uL, reason = 1, mode = HaulMode.NIGHT, signature = SIG),
        IndexerShift(6uL, unix("2026-09-30T22:30:00Z"), darkRounds = 412uL, roundsDug = 1uL, reason = 0, mode = HaulMode.NIGHT, signature = null),
        IndexerShift(5uL, unix("2026-09-29T20:00:00Z"), darkRounds = 3uL, roundsDug = 0uL, reason = 8, mode = HaulMode.DAY, signature = SIG),
    )

    private fun facts(
        rig: RigNights? = this.rig,
        shifts: List<IndexerShift>? = this.shifts,
        rigProblem: String? = null,
        shiftsProblem: String? = null,
        incomplete: Boolean = false,
    ) = NightsFacts(rig, shifts, today, tokyo, todayUtcDay = 20_730, rigProblem = rigProblem, shiftsProblem = shiftsProblem, incomplete = incomplete)

    private var retries = 0
    private var exits = 0
    private val opened = mutableListOf<String>()

    private fun show(state: NightsState, cluster: String = ExplorerLinks.MAINNET) = rule.setContent {
        HeadsDownTheme {
            NightsScreen(state = state, onRetry = { retries++ }, onClose = { exits++ }, cluster = cluster, onOpenTransaction = { opened += it })
        }
    }

    /** Every text and description on the page, on screen or below it. */
    private fun spoken(): List<String> {
        val out = mutableListOf<String>()
        fun walk(node: SemanticsNode) {
            node.config.getOrNull(SemanticsProperties.Text)?.forEach { out += it.text }
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.let { out += it }
            node.children.forEach(::walk)
        }
        walk(rule.onRoot(useUnmergedTree = true).fetchSemanticsNode())
        return out
    }

    @Test
    fun `it shows the streak, the rig's counters, the last 28 days and every finished shift, newest first`() {
        show(NightsState.Ready(facts()))
        rule.onNodeWithText("Nights").assertExists()
        rule.onNodeWithTag(NightsTags.STREAK).assertTextEquals("Streak", "3 nights")
        rule.onNodeWithTag(NightsTags.NIGHTS_OFF).assertTextEquals("Nights off left: 1")
        rule.onNodeWithText(NightsCopy.STREAK_RULE).assertExists()
        rule.onNodeWithTag(NightsTags.LIFETIME_DARK).assertTextEquals("Rounds dark", "1240")
        rule.onNodeWithTag(NightsTags.LIFETIME_DIGS).assertTextEquals("Digs", "318")
        rule.onNodeWithTag(NightsTags.STRIP).assertContentDescriptionEquals("Last 28 days: 1 completed, 2 ended early, 25 with no shift.")
        rule.onNodeWithText(NightsCopy.LEGEND).assertExists()
        // Each half says where it was read: the account by the phone itself, the list by the indexer.
        rule.onNodeWithText("The streak and these counts are read from your rig's account on-chain.").assertExists()
        rule.onNodeWithText("The days and the list come from the Heads Down indexer, which reads the sealed shifts.").assertExists()

        rule.onNodeWithTag(NightsTags.NIGHT + "7").assertTextEquals("Sealed Mon 5 Oct 2026, 06:28", "Ended early: the phone was picked up", "5 rounds dark · 5 digs")
        rule.onNodeWithTag(NightsTags.NIGHT + "6").assertTextEquals("Sealed Thu 1 Oct 2026, 07:30", "Completed", "412 rounds dark · 1 dig")
        rule.onNodeWithTag(NightsTags.NIGHT + "5").assertTextEquals("Sealed Wed 30 Sep 2026, 05:00", "Ended early: the phone was unlocked", "3 rounds dark · 0 digs")
        val tops = listOf("7", "6", "5").map { rule.onNodeWithTag(NightsTags.NIGHT + it).fetchSemanticsNode().positionInRoot.y }
        assertEquals("newest first, top to bottom", tops.sorted(), tops)
        rule.onAllNodesWithTag(NightsTags.EMPTY).assertCountEquals(0)
        rule.onAllNodesWithTag(NightsTags.OLDER).assertCountEquals(0)

        // Nothing went wrong: the dock closes the page, and there is nothing to try again.
        rule.onAllNodesWithTag(NightsTags.RETRY).assertCountEquals(0)
        rule.onNodeWithTag(NightsTags.CLOSE).assertTextEquals("Close").performClick()
        assertEquals(1, exits)
    }

    @Test
    fun `a shift's link opens the transaction that sealed it, and there is none without a signature or a public explorer`() {
        show(NightsState.Ready(facts()))
        rule.onNodeWithTag(NightsTags.TRANSACTION + "7").performScrollTo().assertTextEquals("See this transaction on-chain").assertHasClickAction().performClick()
        assertEquals(listOf("https://solscan.io/tx/$SIG"), opened)
        // The indexer sent no signature for shift 6: no link is made up for it.
        rule.onAllNodesWithTag(NightsTags.TRANSACTION + "6").assertCountEquals(0)
        rule.onNodeWithTag(NightsTags.TRANSACTION + "5").performScrollTo().performClick()
        assertEquals(2, opened.size)
        rule.onAllNodesWithText("See this transaction on-chain").assertCountEquals(2)
    }

    @Test
    fun `a local validator has no public explorer, so no shift has a link`() {
        show(NightsState.Ready(facts()), cluster = "solana:localnet")
        rule.onNodeWithTag(NightsTags.NIGHT + "7").assertExists()
        rule.onAllNodesWithText("See this transaction on-chain").assertCountEquals(0)
    }

    @Test
    fun `no amount of SOL or ORE is on the page, and no banned word`() {
        show(NightsState.Ready(facts()))
        val all = spoken()
        assertTrue("the page was read: $all", all.size >= 15)
        for (text in all) {
            assertFalse("an amount's unit in: $text", Regex("\\b(SOL|ORE|SKR|lamports?)\\b").containsMatchIn(text))
            assertTrue("banned words in: $text", xyz.headsdown.feature.reveal.haul.HonestCopy.violations(text).isEmpty())
        }
    }

    @Test
    fun `a rig with no finished shift says so under an empty strip`() {
        show(NightsState.Ready(facts(shifts = emptyList())))
        rule.onNodeWithTag(NightsTags.STREAK).assertTextEquals("Streak", "3 nights")
        rule.onNodeWithTag(NightsTags.STRIP).assertContentDescriptionEquals("Last 28 days: 0 completed, 0 ended early, 28 with no shift.")
        rule.onNodeWithTag(NightsTags.EMPTY).assertTextEquals("No finished shift yet.")
        rule.onAllNodesWithText("See this transaction on-chain").assertCountEquals(0)
        rule.onNodeWithTag(NightsTags.CLOSE).assertTextEquals("Close")
    }

    @Test
    fun `more shifts than the page lists are counted, not dropped in silence`() {
        val many = (0 until 93).map { IndexerShift((200 - it).toULong(), unix("2026-10-04T21:28:00Z") - it * 86_400L, 1uL, 0uL, 0, HaulMode.NIGHT, null) }
        show(NightsState.Ready(facts(shifts = many)))
        rule.onNodeWithTag(NightsTags.NIGHT + "200").assertExists()
        rule.onNodeWithTag(NightsTags.NIGHT + "111").assertExists()
        rule.onAllNodesWithTag(NightsTags.NIGHT + "110").assertCountEquals(0)
        rule.onNodeWithTag(NightsTags.OLDER).assertTextEquals("3 older shifts are not listed here.")
    }

    @Test
    fun `while it reads it says so, and can be closed`() {
        show(NightsState.Loading)
        rule.onNodeWithTag(NightsTags.MESSAGE).assertTextEquals("Reading your rig and its finished shifts…")
        rule.onAllNodesWithTag(NightsTags.STREAK).assertCountEquals(0)
        rule.onNodeWithTag(NightsTags.CLOSE).assertTextEquals("Close").performClick()
        assertEquals(1, exits)
    }

    @Test
    fun `a phone with no rig bound says there is no night to list`() {
        show(NightsState.NoRig)
        rule.onNodeWithTag(NightsTags.MESSAGE).assertTextEquals(NightsCopy.NO_RIG)
        rule.onAllNodesWithTag(NightsTags.RETRY).assertCountEquals(0)
        rule.onNodeWithTag(NightsTags.CLOSE).assertTextEquals("Close")
    }

    @Test
    fun `when nothing could be read it says so, offers another try, and can still be closed`() {
        show(NightsState.Unavailable(NightsCopy.UNREADABLE))
        rule.onNodeWithTag(NightsTags.MESSAGE).assertTextEquals(NightsCopy.UNREADABLE)
        rule.onNodeWithTag(NightsTags.RETRY).assertTextEquals("Try again").performClick()
        assertEquals(1, retries)
        rule.onNodeWithTag(NightsTags.CLOSE).assertTextEquals("Close").performClick()
        assertEquals(1, exits)
    }

    @Test
    fun `with the chain unread the list is still there, and the bar tries again`() {
        show(NightsState.Ready(facts(rig = null, rigProblem = NightsCopy.RIG_UNREADABLE, incomplete = true)))
        rule.onNodeWithTag(NightsTags.RIG_PROBLEM).assertTextEquals(NightsCopy.RIG_UNREADABLE)
        rule.onAllNodesWithTag(NightsTags.STREAK).assertCountEquals(0)
        rule.onAllNodesWithTag(NightsTags.NIGHTS_OFF).assertCountEquals(0)
        rule.onNodeWithTag(NightsTags.NIGHT + "7").assertExists()
        rule.onNodeWithTag(NightsTags.RETRY).assertTextEquals("Try again").performClick()
        assertEquals(1, retries)
        rule.onNodeWithTag(NightsTags.CLOSE).assertTextEquals("Close")
    }

    @Test
    fun `with the list unread the streak is still there, and a build with no indexer is not asked to try again`() {
        show(NightsState.Ready(facts(shifts = null, shiftsProblem = NightsCopy.NO_INDEXER)))
        rule.onNodeWithTag(NightsTags.STREAK).assertTextEquals("Streak", "3 nights")
        rule.onNodeWithTag(NightsTags.LIST_PROBLEM).assertTextEquals(NightsCopy.NO_INDEXER)
        rule.onAllNodesWithTag(NightsTags.STRIP).assertCountEquals(0)
        rule.onAllNodesWithTag(NightsTags.EMPTY).assertCountEquals(0)
        rule.onAllNodesWithTag(NightsTags.RETRY).assertCountEquals(0)
        rule.onNodeWithTag(NightsTags.CLOSE).assertTextEquals("Close")
    }
}
