package xyz.headsdown.withdraw

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.headsdown.clockout.SheetTestBase
import xyz.headsdown.core.chain.withdraw.CloseBlock
import xyz.headsdown.core.chain.withdraw.Revoke
import xyz.headsdown.core.chain.withdraw.RigClose
import xyz.headsdown.feature.reveal.haul.HonestCopy

/**
 * The withdraw sheet as rebuilt on the design system: what the emulator script relies on (the
 * order of the two choices, the consequences that come into view, the one confirmed node whose
 * amounts it sums), every state at the phone's width and at the largest system font, the one way
 * out, stillness, and the words the rebuild added. WithdrawScreenTest, unchanged, holds the
 * screen's own contract.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = SheetTestBase.SCREEN)
class WithdrawSheetTest : SheetTestBase() {

    private val both = WithdrawFacts(
        revoke = Revoke(lamports = 20_004_480uL, balance = 18_000_000uL, headsDown = true),
        hasRig = true,
        shiftOpen = false,
        rigClose = RigClose(rentBackLamports = 2_449_920uL, leavesTombstone = true, closesSeekerSeat = true),
        closeBlock = null,
    )
    private val shiftOpen = both.copy(shiftOpen = true, rigClose = null, closeBlock = CloseBlock.SHIFT_OPEN.message)
    private val nothing = WithdrawFacts(null, hasRig = false, shiftOpen = false, rigClose = null, closeBlock = null)

    /** What the emulator run ends on: both amounts in the one closing line. */
    private val confirmed =
        "Confirmed on-chain. 0.02000448 SOL back in your wallet from the ORE Automation. Rig closed: 0.00244992 SOL of rent back in your wallet."

    /** Every state the sheet has, with the tag of the control its dock holds. */
    private val states: List<Triple<String, WithdrawState, String>> = listOf(
        Triple("loading", WithdrawState.Loading, WithdrawTags.CLOSE),
        Triple("needs a wallet", WithdrawState.NeedsWallet(), WithdrawTags.CONNECT),
        Triple("needs a wallet, waiting", WithdrawState.NeedsWallet(working = true), WithdrawTags.CONNECT),
        Triple("needs a wallet, a problem", WithdrawState.NeedsWallet(problem = WithdrawModel.NO_WALLET), WithdrawTags.CONNECT),
        Triple("unavailable", WithdrawState.Unavailable(WithdrawModel.UNREADABLE), WithdrawTags.RETRY),
        Triple("ready, nothing chosen", WithdrawState.Ready(both), WithdrawTags.CONFIRM),
        Triple("ready, both chosen", WithdrawState.Ready(both, revoke = true, closeRig = true), WithdrawTags.CONFIRM),
        Triple("ready, the rig cannot be closed", WithdrawState.Ready(shiftOpen, revoke = true), WithdrawTags.CONFIRM),
        Triple("ready, nothing to take", WithdrawState.Ready(nothing), WithdrawTags.CONFIRM),
        Triple("ready, the wallet is open", WithdrawState.Ready(both, revoke = true, closeRig = true, working = true), WithdrawTags.CONFIRM),
        Triple("ready, a problem", WithdrawState.Ready(both, revoke = true, problem = WithdrawModel.NOT_CONFIRMED), WithdrawTags.CONFIRM),
        Triple("done", WithdrawState.Done(confirmed, SIGNATURE), WithdrawTags.CLOSE),
        Triple("done, no explorer", WithdrawState.Done(confirmed), WithdrawTags.CLOSE),
    )

    private val opened = mutableListOf<String>()
    private var closes = 0

    private fun show(state: WithdrawState, cluster: String = "solana:mainnet") = show {
        WithdrawScreen(state, {}, {}, {}, {}, {}, { closes++ }, cluster = cluster, onOpenTransaction = { opened += it })
    }

    /** The sheet with the model's part played by the test: a tap changes the state, as on the phone. */
    private fun showLive(initial: WithdrawState.Ready): () -> WithdrawState {
        var state by mutableStateOf<WithdrawState>(initial)
        show {
            WithdrawScreen(
                state = state,
                onConnect = {},
                onRevoke = { revoke -> state = (state as WithdrawState.Ready).copy(revoke = revoke) },
                onCloseRig = { close -> state = (state as WithdrawState.Ready).copy(closeRig = close) },
                onConfirm = {},
                onRetry = {},
                onClose = {},
            )
        }
        return { state }
    }

    // ------------------------------------------------------------------ what the emulator script relies on

    @Test
    fun `the script reads the sheet top to bottom - what the Automation holds, take it back, close my rig, the bar`() {
        show(WithdrawState.Ready(both, revoke = true, closeRig = true))
        val texts = textsInOrder()
        val holds = texts.indexOfFirst { it.startsWith("Your ORE Automation holds") }
        val takeBack = texts.indexOf("Take it back to my wallet")
        val closeRig = texts.indexOf("Close my rig")
        val bar = texts.indexOf("Take SOL back and close the rig")
        assertTrue("all four are on the sheet: $texts", holds >= 0 && takeBack >= 0 && closeRig >= 0 && bar >= 0)
        assertTrue("in the order they are read: $holds, $takeBack, $closeRig, $bar", holds < takeBack && takeBack < closeRig && closeRig < bar)
        // And on the glass: "Take it back to my wallet" is above "Close my rig", with and without the details between them.
        assertTrue(bounds(WithdrawTags.AUTOMATION).bottom <= bounds(WithdrawTags.REVOKE).top)
        assertTrue(bounds(WithdrawTags.REVOKE).bottom <= bounds(WithdrawTags.CLOSE_RIG).top)
        show(WithdrawState.Ready(both))
        assertTrue(bounds(WithdrawTags.REVOKE).bottom <= bounds(WithdrawTags.CLOSE_RIG).top)
        // The script finds what the Automation holds first, and taps the rest by exact text: one node each.
        assertEquals(WithdrawCopy.automation(both), textsInOrder().first { "Your ORE Automation holds" in it })
        for (label in listOf("Take it back to my wallet", "Close my rig", "Nothing chosen")) rule.onAllNodesWithText(label).assertCountEquals(1)
        // The title is not the choice: an exact match for the choice finds the choice alone.
        assertEquals(1, textsInOrder().count { it == "Take it back to my wallet" })
    }

    private fun assertTheConsequencesComeIntoView() {
        val state = showLive(WithdrawState.Ready(both))
        // As the script does it: one choice, then the other, never scrolling back up.
        rule.onNodeWithTag(WithdrawTags.REVOKE).performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(WithdrawTags.REVOKE_DETAIL).assertIsDisplayed()
        rule.onNodeWithTag(WithdrawTags.CLOSE_RIG).performScrollTo().performClick()
        rule.waitForIdle()
        assertTrue((state() as WithdrawState.Ready).let { it.revoke && it.closeRig })
        val detail = rule.onNodeWithTag(WithdrawTags.CLOSE_DETAIL)
        detail.assertIsDisplayed()
        assertTrue(detail.fetchSemanticsNode().config[SemanticsProperties.Text].single().text.endsWith("This cannot be undone."))
        val box = bounds(WithdrawTags.CLOSE_DETAIL)
        val dockTop = bounds(WithdrawTags.CONFIRM).top
        assertTrue("what closing costs starts on screen: $box, dock from $dockTop", box.top >= 0.dp && box.top < dockTop)
        if (box.height < dockTop - 24.dp) assertTrue("all of it is above the dock: $box, dock from $dockTop", box.bottom <= dockTop)
        rule.onNodeWithTag(WithdrawTags.CONFIRM).assertIsDisplayed().assertIsEnabled().assertTextEquals("Take SOL back and close the rig")
    }

    @Test
    fun `what each choice costs comes into view when it is chosen, without anybody scrolling`() = assertTheConsequencesComeIntoView()

    @Test
    fun `at font scale 2 what each choice costs still comes into view`() {
        fontScale = 2f
        assertTheConsequencesComeIntoView()
    }

    @Test
    @Config(qualifiers = SHORT_SCREEN)
    fun `on a short screen what closing the rig costs is scrolled to, not left under the edge`() = assertTheConsequencesComeIntoView()

    @Test
    fun `a problem comes into view when it appears`() {
        var state by mutableStateOf<WithdrawState>(WithdrawState.Ready(both, revoke = true, closeRig = true))
        show { WithdrawScreen(state, {}, {}, {}, {}, {}, {}) }
        rule.onAllNodesWithTag(WithdrawTags.PROBLEM).assertCountEquals(0)
        state = WithdrawState.Ready(both, revoke = true, closeRig = true, problem = WithdrawModel.NOT_LANDED)
        rule.waitForIdle()
        rule.onNodeWithTag(WithdrawTags.PROBLEM).assertIsDisplayed().assertTextEquals(WithdrawModel.NOT_LANDED)
        assertTrue(bounds(WithdrawTags.PROBLEM).bottom <= bounds(WithdrawTags.CONFIRM).top)
    }

    @Test
    fun `a confirmed withdrawal is one node that says so with both amounts in it, one Done, and nothing else`() {
        show(WithdrawState.Done(confirmed, SIGNATURE))
        val saying = textsInOrder().filter { "Confirmed on-chain" in it }
        assertEquals(listOf(confirmed), saying)
        // What the script does with that node: every "N.N SOL" in it, summed, is what the wallet must gain before the fee.
        val lamports = Regex("""([0-9]+\.[0-9]+) SOL""").findAll(saying.single()).sumOf { Math.round(it.groupValues[1].toDouble() * 1e9) }
        assertEquals(20_004_480L + 2_449_920L, lamports)
        rule.onNodeWithTag(WithdrawTags.MESSAGE).assertTextEquals(confirmed).assertIsDisplayed().assertIsFocused()
        assertEquals(LiveRegionMode.Polite, rule.onNodeWithTag(WithdrawTags.MESSAGE).fetchSemanticsNode().config.getOrNull(SemanticsProperties.LiveRegion))
        // Exactly one node reads "Done", it is the way out, and the ledger is gone.
        assertEquals(1, textsInOrder().count { it == "Done" })
        rule.onAllNodesWithText("Done").assertCountEquals(1)
        rule.onNodeWithTag(WithdrawTags.CLOSE).assertTextEquals("Done").performClick()
        assertEquals(1, closes)
        for (tag in listOf(WithdrawTags.AUTOMATION, WithdrawTags.RIG, WithdrawTags.CONFIRM, WithdrawTags.REVOKE)) {
            rule.onAllNodesWithTag(tag).assertCountEquals(0)
        }
        // Once confirmed there is no title: the sheet's name is the heading over the result.
        rule.onNode(isHeading()).assertTextEquals("Your SOL, your rig")
    }

    @Test
    fun `the link to the transaction opens the explorer of the build's cluster, and is absent where there is none`() {
        show(WithdrawState.Done(confirmed, SIGNATURE), cluster = "solana:mainnet")
        rule.onNodeWithTag(WithdrawTags.TRANSACTION).assertTextEquals("See this transaction on-chain").performClick()
        assertEquals(listOf("https://solscan.io/tx/$SIGNATURE"), opened)

        show(WithdrawState.Done(confirmed, SIGNATURE), cluster = "solana:devnet")
        rule.onNodeWithTag(WithdrawTags.TRANSACTION).performClick()
        assertEquals("https://explorer.solana.com/tx/$SIGNATURE?cluster=devnet", opened.last())

        show(WithdrawState.Done(confirmed, SIGNATURE), cluster = "solana:localnet")
        rule.onAllNodesWithTag(WithdrawTags.TRANSACTION).assertCountEquals(0)
        show(WithdrawState.Done(confirmed), cluster = "solana:mainnet")
        rule.onAllNodesWithTag(WithdrawTags.TRANSACTION).assertCountEquals(0)
        assertEquals(2, opened.size)
    }

    // ------------------------------------------------------------------ every state

    @Test
    fun `every state has exactly one control that closes the sheet`() {
        for ((name, state, _) in states) {
            show(state)
            rule.onAllNodesWithTag(WithdrawTags.CLOSE).assertCountEquals(1)
            val before = closes
            rule.onNodeWithTag(WithdrawTags.CLOSE).assertIsEnabled().assertTextEquals(if (state is WithdrawState.Done) "Done" else "Close").performClick()
            assertEquals(name, before + 1, closes)
            // It is the bar in the dock unless the dock is taken by what the state is for.
            val inDock = state is WithdrawState.Loading || state is WithdrawState.Done
            assertEquals(name, inDock, bounds(WithdrawTags.CLOSE).height >= 56.dp && bounds(WithdrawTags.CLOSE).width >= 300.dp)
        }
    }

    private fun assertEveryStateFits(scale: Float) {
        fontScale = scale
        for ((name, state, dock) in states) {
            show(state)
            // The primary action is whole inside the screen, at least a bar tall, and as wide as the margins leave.
            assertInsideTheScreen(dock)
            assertTrue("$name at $scale: the dock's bar is ${bounds(dock).height}", bounds(dock).height >= 56.dp)
            assertEquals("$name at $scale", root().width.value - 40f, bounds(dock).width.value, 0.5f)
            rule.onNodeWithTag(dock).assertIsDisplayed()
            // The dock holds that one control and nothing else.
            assertEquals("$name at $scale", dock, actionsInOrder().last())
            // No text is cut, squeezed or ellipsized; in particular no amount.
            val whole = assertEveryTextIsWhole()
            for (amount in amountsOf(state)) assertTrue("$name at $scale: \"$amount\" is drawn whole: $whole", amount in whole)
            // A screen reader finds one heading in every state.
            assertEquals("$name at $scale", 1, unmergedNodes().count { it.config.getOrNull(SemanticsProperties.Heading) != null })
        }
    }

    /** The figures the ledger must show for [state], as the existing formatter writes them. */
    private fun amountsOf(state: WithdrawState): List<String> {
        val facts = (state as? WithdrawState.Ready)?.facts ?: return emptyList()
        return listOfNotNull("0.02000448 SOL".takeIf { facts.revoke != null }, "0.00244992 SOL".takeIf { facts.rigClose != null })
    }

    @Test
    fun `every state fits a 360dp phone - the bar inside the screen, no amount cut`() = assertEveryStateFits(1f)

    @Test
    fun `every state reflows at font scale 2 - the bar inside the screen, no amount cut`() = assertEveryStateFits(2f)

    @Test
    @Config(qualifiers = LANDSCAPE)
    fun `every state fits a phone on its side, at the normal and at the largest font`() {
        assertEveryStateFits(1f)
        assertEveryStateFits(2f)
    }

    @Test
    @Config(qualifiers = SMALL_SCREEN)
    fun `every state fits a small phone, at the normal and at the largest font`() {
        assertEveryStateFits(1f)
        assertEveryStateFits(2f)
    }

    @Test
    @Config(qualifiers = LANDSCAPE)
    fun `on a phone on its side what each choice costs still comes into view`() = assertTheConsequencesComeIntoView()

    @Test
    fun `the confirmed statement stands where the title stood`() {
        show(WithdrawState.Ready(both))
        val title = rule.onNode(isHeading()).getUnclippedBoundsInRoot()
        show(WithdrawState.Done(confirmed, SIGNATURE))
        assertEquals(title.top.value, bounds(WithdrawTags.MESSAGE).top.value, 0.5f)
        assertEquals(title.left.value, bounds(WithdrawTags.MESSAGE).left.value, 0.5f)
    }

    @Test
    fun `at the normal font size the dock is one bar, low enough for a swipe above it to scroll the page`() {
        // The emulator script swipes from 78% of the screen's height: that point must be in the page.
        for ((name, state, dock) in states) {
            show(state)
            val bar = bounds(dock)
            assertEquals("$name: one line of label, one 56dp bar", 56f, bar.height.value, 0.5f)
            assertTrue("$name: the bar starts at ${bar.top} of ${root().height}", bar.top.value > root().height.value * 0.78f + 12f)
        }
    }

    @Test
    fun `every state is still - no frame awaited and no state written once it has settled`() {
        rule.mainClock.autoAdvance = false
        for (scale in listOf(1f, 2f)) {
            fontScale = scale
            for ((name, state, _) in states) {
                show(state)
                assertAtRest("$name at $scale")
            }
        }
    }

    @Test
    fun `in every state a screen reader meets words, full touch targets and nothing that is only drawn`() {
        for (scale in listOf(1f, 2f)) {
            fontScale = scale
            for ((name, state, dock) in states) {
                show(state)
                assertSpeakable("$name at $scale")
                // The dock is the last thing reached, and before it comes everything else there is to read.
                assertEquals("$name at $scale", dock, spokenNodes().last().config.getOrNull(SemanticsProperties.TestTag))
            }
        }
    }

    // ------------------------------------------------------------------ choices, roles, cues

    @Test
    fun `the two choices are checkboxes - chosen or not, usable or not - and the bar says what will be signed`() {
        fun role(expected: Role) = SemanticsMatcher.expectValue(SemanticsProperties.Role, expected)
        show(WithdrawState.Ready(both))
        rule.onNodeWithTag(WithdrawTags.REVOKE).assert(role(Role.Checkbox)).assertIsNotSelected().assertIsEnabled().assertTextEquals("Take it back to my wallet")
        rule.onNodeWithTag(WithdrawTags.CLOSE_RIG).assert(role(Role.Checkbox)).assertIsNotSelected().assertIsEnabled().assertTextEquals("Close my rig")
        rule.onNodeWithTag(WithdrawTags.CONFIRM).assert(role(Role.Button)).assertIsNotEnabled().assertTextEquals("Nothing chosen")
        for (tag in listOf(WithdrawTags.REVOKE, WithdrawTags.CLOSE_RIG)) assertTrue("$tag is at least 48dp tall", bounds(tag).height >= 48.dp)

        show(WithdrawState.Ready(both, revoke = true))
        rule.onNodeWithTag(WithdrawTags.REVOKE).assertIsSelected()
        rule.onNodeWithTag(WithdrawTags.CONFIRM).assertIsEnabled().assertTextEquals("Take SOL back")
        show(WithdrawState.Ready(both, closeRig = true))
        rule.onNodeWithTag(WithdrawTags.CONFIRM).assertIsEnabled().assertTextEquals("Close the rig")

        show(WithdrawState.Ready(both, revoke = true, closeRig = true, working = true))
        rule.onNodeWithTag(WithdrawTags.REVOKE).assertIsSelected().assertIsNotEnabled()
        rule.onNodeWithTag(WithdrawTags.CLOSE_RIG).assertIsSelected().assertIsNotEnabled()
        rule.onNodeWithTag(WithdrawTags.CONFIRM).assertIsNotEnabled().assertTextEquals("Waiting for your wallet…")
        // The way out stays usable while the wallet is open, and is a full touch target.
        rule.onNodeWithTag(WithdrawTags.CLOSE).assertIsEnabled()
        assertTrue(bounds(WithdrawTags.CLOSE).height >= 48.dp && bounds(WithdrawTags.CLOSE).width >= 48.dp)
    }

    @Test
    fun `a screen reader gets a heading, each row as one line, and the dock last`() {
        show(WithdrawState.Ready(both, revoke = true, closeRig = true))
        rule.onNode(isHeading()).assertTextEquals("Take it back").assertIsDisplayed()
        // The name of a row and its amount are one node; the sentence under them is another.
        rule.onNodeWithText("ORE Automation").assertTextEquals("ORE Automation", "0.02000448 SOL")
        rule.onNodeWithText("Rig rent").assertTextEquals("Rig rent", "0.00244992 SOL")
        rule.onNodeWithTag(WithdrawTags.RIG).assertTextEquals(WithdrawCopy.rig(both))
        // Capitals are drawn: what is read out is the words as written.
        assertTrue("Your SOL, your rig" in textsInOrder() && "YOUR SOL, YOUR RIG" !in textsInOrder())
        // Top to bottom: the way out, the two choices, and the bar in the dock after everything else.
        assertEquals(listOf(WithdrawTags.CLOSE, WithdrawTags.REVOKE, WithdrawTags.CLOSE_RIG, WithdrawTags.CONFIRM), actionsInOrder())
        assertEquals(WithdrawTags.CONFIRM, tagsInOrder().last())
        // What a choice costs is said aloud when it appears, once the current sentence is over.
        for (tag in listOf(WithdrawTags.REVOKE_DETAIL, WithdrawTags.CLOSE_DETAIL)) {
            assertEquals(LiveRegionMode.Polite, rule.onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.LiveRegion))
        }
        // A rig that cannot be closed has no amount to name, and the row does not pretend to one.
        show(WithdrawState.Ready(shiftOpen))
        rule.onNodeWithText("Rig").assertTextEquals("Rig")
        rule.onAllNodesWithText("Rig rent").assertCountEquals(0)
    }

    @Test
    fun `the motor is asked once for a confirmed result and once for each problem, never otherwise`() {
        var state by mutableStateOf<WithdrawState>(WithdrawState.NeedsWallet())
        show { WithdrawScreen(state, {}, {}, {}, {}, {}, {}) }
        fun becomes(next: WithdrawState) {
            state = next
            rule.waitForIdle()
        }
        becomes(WithdrawState.NeedsWallet(working = true))
        assertEquals(emptyList<String>(), haptics.cues)
        becomes(WithdrawState.NeedsWallet(problem = WithdrawModel.NO_WALLET))
        assertEquals(listOf("refuse"), haptics.cues)

        becomes(WithdrawState.Loading)
        becomes(WithdrawState.Ready(both))
        becomes(WithdrawState.Ready(both, revoke = true))
        becomes(WithdrawState.Ready(both, revoke = true, working = true))
        assertEquals(listOf("refuse"), haptics.cues)
        becomes(WithdrawState.Ready(both, revoke = true, problem = WithdrawModel.NOT_LANDED))
        assertEquals(listOf("refuse", "refuse"), haptics.cues)

        becomes(WithdrawState.Ready(both, revoke = true, working = true))
        becomes(WithdrawState.Done(confirmed, SIGNATURE))
        assertEquals(listOf("refuse", "refuse", "confirm"), haptics.cues)
        rule.mainClock.advanceTimeBy(5_000)
        rule.waitForIdle()
        assertEquals(3, haptics.cues.size)

        // An unreadable chain is a problem too.
        haptics.cues.clear()
        show(WithdrawState.Unavailable(WithdrawModel.UNREADABLE))
        assertEquals(listOf("refuse"), haptics.cues)
    }

    // ------------------------------------------------------------------ words

    @Test
    fun `the words the rebuild added use none the app never uses, and are written the way they are read`() {
        assertTrue(WithdrawLedger.all.size >= 10)
        for (label in WithdrawLedger.all) assertHonestLabel(label)
        assertFalse(runCatching { assertHonestLabel("YOUR SOL, YOUR RIG") }.isSuccess)
        assertEquals("0.02000448 SOL", WithdrawLedger.sol(20_004_480uL))
    }

    @Test
    fun `everything drawn on every state passes the honesty scan`() {
        for ((name, state, _) in states) {
            show(state)
            for (text in textsInOrder()) assertEquals("$name: \"$text\"", emptyList<String>(), HonestCopy.violations(text))
        }
    }
}
