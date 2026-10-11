package xyz.headsdown.clockout

import android.animation.ValueAnimator
import android.app.Application
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
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
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.headsdown.core.chain.accounts.OreClaimEstimate
import xyz.headsdown.core.chain.clockout.BondOutcome
import xyz.headsdown.core.chain.clockout.ShiftOutcome
import xyz.headsdown.core.design.HdType
import xyz.headsdown.core.design.HeadsDownTheme
import xyz.headsdown.core.design.components.DisplayText
import xyz.headsdown.core.design.components.LocalHdHaptics
import xyz.headsdown.core.keys.ShiftEndReason
import xyz.headsdown.feature.reveal.haul.HonestCopy

/**
 * The clock-out sheet as rebuilt on the design system: what the emulator script relies on (the
 * order of what it reads, the warning that comes into view, the one confirmed node), every state
 * at the phone's width and at the largest system font, the one way out, stillness, and the words
 * the rebuild added. ClockOutScreenTest, unchanged, holds the screen's own contract.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = SheetTestBase.SCREEN)
class ClockOutSheetTest : SheetTestBase() {

    private val completed = ClockOutFacts(
        shift = ShiftOutcome.Ends(ShiftEndReason.COMPLETED),
        bond = BondOutcome.Released(100_000_000uL),
        returnedSolLamports = 3_200_000uL,
        nothingByDefault = false,
        refinedOre = 1_000uL,
        unrefinedOre = 20_000_000uL,
        fullClaim = OreClaimEstimate(1_000uL, 20_000_000uL, 2_000_000uL),
        shiftLogRent = 1_300_480uL,
    )

    /** The state the emulator run reaches: cooled by a pickup, inside its window, a bond on it. */
    private val insideWindow = completed.copy(
        shift = ShiftOutcome.LeftOpen(ShiftEndReason.PICKUP, 1_790_028_800L),
        bond = BondOutcome.StaysLocked(100_000_000uL),
        returnedSolLamports = 0uL,
        nothingByDefault = true,
    )
    private val nothingAtAll = ClockOutFacts(ShiftOutcome.NoOpenShift, BondOutcome.None, 0uL, nothingByDefault = true, 0uL, 0uL, fullClaim = null)

    /** "Buy ORE" as a mainnet build offers it, and with Jupiter's quote for 0.001 SOL (11 October 2026). */
    private val offered = BuyChoice(offers = BuyChoice.OFFERS)
    private val marketQuote = BuyQuote(spendLamports = 1_000_000uL, oreAtoms = 96_488_910uL, minOreAtoms = 95_524_020uL, slippageBps = 100, route = listOf("Meteora DLMM"))
    private val quoted = offered.copy(spendLamports = 1_000_000uL, quote = marketQuote)

    private val confirmed =
        "Confirmed on-chain. Shift sealed as completed. Focus Bond back in your wallet: 100 SKR. " +
            "0.0032 SOL back in your wallet. About 0.00018001 ORE claimed to your wallet."

    /** Every state the sheet has, with the tag of the control its dock holds. */
    private val states: List<Triple<String, ClockOutState, String>> = listOf(
        Triple("loading", ClockOutState.Loading, ClockOutTags.CLOSE),
        Triple("no rig", ClockOutState.NoRig, ClockOutTags.CLOSE),
        Triple("unavailable", ClockOutState.Unavailable(ClockOutModel.UNREADABLE), ClockOutTags.RETRY),
        Triple("ready, a finished shift", ClockOutState.Ready(completed), ClockOutTags.CONFIRM),
        Triple("ready, claiming", ClockOutState.Ready(completed, claimAll = true), ClockOutTags.CONFIRM),
        Triple("ready, inside the window", ClockOutState.Ready(insideWindow), ClockOutTags.CONFIRM),
        Triple("ready, ending early", ClockOutState.Ready(insideWindow, endEarly = true), ClockOutTags.CONFIRM),
        Triple("ready, the wallet is open", ClockOutState.Ready(insideWindow, endEarly = true, working = true), ClockOutTags.CONFIRM),
        Triple("ready, a problem", ClockOutState.Ready(completed, problem = ClockOutModel.NOT_CONFIRMED), ClockOutTags.CONFIRM),
        Triple("ready, nothing at all", ClockOutState.Ready(nothingAtAll), ClockOutTags.CONFIRM),
        Triple("ready, a buy on offer", ClockOutState.Ready(completed, buy = offered), ClockOutTags.CONFIRM),
        Triple("ready, a buy being quoted", ClockOutState.Ready(completed, buy = offered.copy(spendLamports = 1_000_000uL, quoting = true)), ClockOutTags.CONFIRM),
        Triple("ready, a buy quoted", ClockOutState.Ready(completed, buy = quoted), ClockOutTags.CONFIRM),
        Triple("ready, no quote for the buy", ClockOutState.Ready(completed, buy = offered.copy(spendLamports = 5_000_000uL, noQuote = true)), ClockOutTags.CONFIRM),
        Triple("ready, a buy and nothing else", ClockOutState.Ready(nothingAtAll, buy = quoted), ClockOutTags.CONFIRM),
        Triple("done", ClockOutState.Done(confirmed, SIGNATURE), ClockOutTags.CLOSE),
        Triple("done, no explorer", ClockOutState.Done(confirmed), ClockOutTags.CLOSE),
    )

    private val opened = mutableListOf<String>()
    private var closes = 0

    private fun show(state: ClockOutState, cluster: String = "solana:mainnet") = show {
        ClockOutScreen(state, {}, {}, {}, {}, { closes++ }, cluster = cluster, onOpenTransaction = { opened += it })
    }

    /** The sheet with the model's part played by the test: a tap changes the state, as on the phone. */
    private fun showLive(initial: ClockOutState.Ready): () -> ClockOutState {
        var state by mutableStateOf<ClockOutState>(initial)
        show {
            ClockOutScreen(
                state = state,
                onClaimAll = { claim -> state = (state as ClockOutState.Ready).copy(claimAll = claim) },
                onEndEarly = { end -> state = (state as ClockOutState.Ready).copy(endEarly = end) },
                onConfirm = {},
                onRetry = {},
                onClose = {},
            )
        }
        return { state }
    }

    // ------------------------------------------------------------------ what the emulator script relies on

    @Test
    fun `the script reads the sheet top to bottom - the shift, its choice, the warning, the bar`() {
        show(ClockOutState.Ready(insideWindow, endEarly = true))
        val texts = textsInOrder()
        val shift = texts.indexOfFirst { it.startsWith("Clocking out seals your shift as ended early") }
        val choice = texts.indexOf("End the shift now")
        val warning = texts.indexOfFirst { "This cannot be undone" in it }
        val bar = texts.indexOf("End the shift and clock out")
        assertTrue("all four are on the sheet: $texts", shift >= 0 && choice >= 0 && warning >= 0 && bar >= 0)
        assertTrue("in the order they are read: $shift, $choice, $warning, $bar", shift < choice && choice < warning && warning < bar)
        // And on the glass, not only in the tree: each is under the one before, the bar in the dock under them all.
        assertTrue(bounds(ClockOutTags.SHIFT).bottom <= bounds(ClockOutTags.END_EARLY).top)
        assertTrue(bounds(ClockOutTags.END_EARLY).bottom <= bounds(ClockOutTags.END_EARLY_WARNING).top)
        // The script taps by exact text: one node each.
        rule.onAllNodesWithText("End the shift now").assertCountEquals(1)
        rule.onAllNodesWithText("End the shift and clock out").assertCountEquals(1)
        // The keep-or-claim pair keeps its order too.
        assertTrue(bounds(ClockOutTags.KEEP_CHOICE).bottom <= bounds(ClockOutTags.CLAIM_CHOICE).top)
    }

    private fun assertTheWarningComesIntoView() {
        val state = showLive(ClockOutState.Ready(insideWindow))
        rule.onAllNodesWithTag(ClockOutTags.END_EARLY_WARNING).assertCountEquals(0)
        // The worst case: the choice is at the very edge of what is visible when it is tapped.
        rule.onNodeWithTag(ClockOutTags.END_EARLY).performScrollTo().performClick()
        rule.waitForIdle()
        assertTrue((state() as ClockOutState.Ready).endEarly)
        // No scrolling by the test from here on.
        val warning = rule.onNodeWithTag(ClockOutTags.END_EARLY_WARNING)
        warning.assertIsDisplayed()
        assertTrue(warning.fetchSemanticsNode().config[SemanticsProperties.Text].single().text.endsWith("This cannot be undone."))
        val box = bounds(ClockOutTags.END_EARLY_WARNING)
        val dockTop = bounds(ClockOutTags.CONFIRM).top
        assertTrue("the warning starts on screen: $box, dock from $dockTop", box.top >= 0.dp && box.top < dockTop)
        // All of it, whenever the space above the dock can hold all of it.
        if (box.height < dockTop - 24.dp) assertTrue("the whole warning is above the dock: $box, dock from $dockTop", box.bottom <= dockTop)
    }

    @Test
    fun `the end-early warning comes into view when it appears, without anybody scrolling`() = assertTheWarningComesIntoView()

    @Test
    fun `at font scale 2 the end-early warning still comes into view`() {
        fontScale = 2f
        assertTheWarningComesIntoView()
    }

    @Test
    @Config(qualifiers = SHORT_SCREEN)
    fun `on a short screen the end-early warning is scrolled to, not left under the edge`() = assertTheWarningComesIntoView()

    @Test
    fun `what claiming costs comes into view with the choice that causes it`() {
        fontScale = 2f
        showLive(ClockOutState.Ready(completed))
        rule.onNodeWithTag(ClockOutTags.CLAIM_CHOICE).performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(ClockOutTags.CLAIM).assertIsDisplayed()
    }

    @Test
    fun `a problem comes into view when it appears`() {
        var state by mutableStateOf<ClockOutState>(ClockOutState.Ready(completed))
        show { ClockOutScreen(state, {}, {}, {}, {}, {}) }
        rule.onAllNodesWithTag(ClockOutTags.PROBLEM).assertCountEquals(0)
        state = ClockOutState.Ready(completed, problem = ClockOutModel.NOT_LANDED)
        rule.waitForIdle()
        rule.onNodeWithTag(ClockOutTags.PROBLEM).assertIsDisplayed().assertTextEquals(ClockOutModel.NOT_LANDED)
        assertTrue(bounds(ClockOutTags.PROBLEM).bottom <= bounds(ClockOutTags.CONFIRM).top)
    }

    @Test
    fun `a confirmed clock-out is one node that says so, one Done, and nothing else`() {
        show(ClockOutState.Done(confirmed, SIGNATURE))
        // The script takes the first node containing the words and reads the amounts out of it.
        val saying = textsInOrder().filter { "Confirmed on-chain" in it }
        assertEquals(listOf(confirmed), saying)
        assertEquals(emptyList<String>(), unmergedNodes().mapNotNull { it.config.getOrNull(SemanticsProperties.ContentDescription) }.flatten().filter { "Confirmed" in it })
        rule.onNodeWithTag(ClockOutTags.MESSAGE).assertTextEquals(confirmed).assertIsDisplayed()
        // Exactly one node reads "Done", it is the way out, and the ledger is gone.
        assertEquals(1, textsInOrder().count { it == "Done" })
        rule.onAllNodesWithText("Done").assertCountEquals(1)
        rule.onNodeWithTag(ClockOutTags.CLOSE).assertTextEquals("Done").performClick()
        assertEquals(1, closes)
        for (tag in listOf(ClockOutTags.SHIFT, ClockOutTags.ORE, ClockOutTags.CONFIRM, ClockOutTags.KEEP_CHOICE)) {
            rule.onAllNodesWithTag(tag).assertCountEquals(0)
        }
    }

    @Test
    fun `the confirmed message is drawn in two paragraphs and loses no character`() {
        fun drawn(message: String) = confirmedText(message, HdType.Default.display, statementEm = 3.5f, gapEm = 0.75f, ore = Color.Yellow)
        val text = drawn(confirmed)
        assertEquals(confirmed, text.text)
        // The statement, then the space after it as one empty line; the rest is the text's own paragraph.
        assertEquals(listOf(0 to 19, 19 to 20), text.paragraphStyles.map { it.start to it.end })
        assertEquals("Confirmed on-chain.", confirmed.substring(0, 19))
        // The statement is in the display face at the size it was given, on a line in proportion to it.
        val statement = text.spanStyles.single { it.start == 0 && it.end == 19 }.item
        assertEquals(HdType.Default.display.fontFamily, statement.fontFamily)
        assertEquals(3.5f, statement.fontSize.value, 0f)
        assertTrue(statement.fontSize.isEm && text.paragraphStyles.first().item.lineHeight.isEm)
        assertEquals(3.5f * 1.05f, text.paragraphStyles.first().item.lineHeight.value, 0.001f)
        // The one amount of ORE is in gold; SOL and SKR are not.
        assertEquals(listOf("0.00018001 ORE"), text.spanStyles.filter { it.item.color == Color.Yellow }.map { confirmed.substring(it.start, it.end) })
        // A message that does not open with the statement is plain: nothing is set large by accident.
        val plain = drawn("Shift sealed as completed.")
        assertTrue(plain.paragraphStyles.isEmpty() && plain.spanStyles.isEmpty())
        // The statement alone is one paragraph, and dust is still an amount.
        assertEquals(1, drawn("Confirmed on-chain.").paragraphStyles.size)
        val dust = drawn("Confirmed on-chain. About <0.00000001 ORE claimed to your wallet.")
        assertTrue(dust.spanStyles.any { dust.text.substring(it.start, it.end) == "<0.00000001 ORE" })
        // "ORE" without an amount before it is a name, not an amount.
        assertTrue(drawn("Confirmed on-chain. 0.02 SOL back in your wallet from the ORE Automation.").spanStyles.none { it.item.color == Color.Yellow })
    }

    @Test
    fun `the confirmed statement stands where the title stood, at every font size`() {
        for (scale in listOf(1f, 1.3f, 2f)) {
            fontScale = scale
            // The header is as tall as its tallest part: compared with a state whose way out is in the dock, as Done's is.
            show(ClockOutState.Loading)
            val title = rule.onNode(isHeading()).getUnclippedBoundsInRoot()
            if (scale == 1f) {
                // At the normal font size "Close" beside the name changes nothing: the ledger's title is on the same line.
                show(ClockOutState.Ready(completed))
                assertEquals(title, rule.onNode(isHeading()).getUnclippedBoundsInRoot())
            }
            show(ClockOutState.Done(confirmed, SIGNATURE))
            val message = bounds(ClockOutTags.MESSAGE)
            // Nothing between the sheet's name and the result: the result starts on the title's line.
            assertEquals("at $scale the result starts where the title did", title.top.value, message.top.value, 0.5f)
            assertEquals("at $scale", title.left.value, message.left.value, 0.5f)
            // And its statement is set as a title is set: the same words given to DisplayText, in
            // the same width, take the same lines and the same height (the display face stops
            // growing with the system font where a title's does).
            val lines = mutableListOf<TextLayoutResult>()
            rule.onNodeWithTag(ClockOutTags.MESSAGE).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!(lines)
            val layout = lines.single()
            val statementLines = (0 until layout.lineCount).count { layout.getLineStart(it) < "Confirmed on-chain.".length }
            val statementHeight = with(rule.density) { layout.getLineBottom(statementLines - 1).toDp() }
            show { DisplayText("Confirmed on-chain.", Modifier.padding(horizontal = 20.dp).testTag("as a title"), caps = false) }
            assertEquals("at $scale: $statementLines lines of the statement", bounds("as a title").height.value, statementHeight.value, 1f)
            assertEquals("at $scale", 2, statementLines)
        }
    }

    @Test
    fun `turning the phone asks nothing of the motor again and leaves the page where it was`() {
        var state by mutableStateOf<ClockOutState>(ClockOutState.Ready(completed))
        val restoration = StateRestorationTester(rule)
        restoration.setContent {
            CompositionLocalProvider(LocalHdHaptics provides haptics) {
                HeadsDownTheme { ClockOutScreen(state, {}, {}, {}, {}, {}, cluster = "solana:mainnet") }
            }
        }
        fun page() = rule.onAllNodes(hasScrollAction()).onFirst()
        fun scrolled() = page().fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()

        // A problem arrives: one cue, and the page goes to it.
        state = ClockOutState.Ready(completed, problem = ClockOutModel.NOT_LANDED)
        rule.waitForIdle()
        assertEquals(listOf("refuse"), haptics.cues)
        rule.onNodeWithTag(ClockOutTags.PROBLEM).assertIsDisplayed()
        assertTrue(scrolled() > 0f)
        // The reader goes back to the top, then turns the phone: the activity is made again around the same model.
        page().performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, -100_000f) }
        rule.waitForIdle()
        assertEquals(0f, scrolled(), 0f)
        restoration.emulateSavedInstanceStateRestore()
        rule.waitForIdle()
        assertEquals("the same problem is not a new refusal", listOf("refuse"), haptics.cues)
        assertEquals("a notice that was already there does not pull the page to itself", 0f, scrolled(), 0f)
        rule.onNodeWithTag(ClockOutTags.PROBLEM).assertTextEquals(ClockOutModel.NOT_LANDED)

        // Confirmed: one cue, also after another turn, and the result is where the focus is.
        state = ClockOutState.Done(confirmed, SIGNATURE)
        rule.waitForIdle()
        assertEquals(listOf("refuse", "confirm"), haptics.cues)
        restoration.emulateSavedInstanceStateRestore()
        rule.waitForIdle()
        assertEquals(listOf("refuse", "confirm"), haptics.cues)
        rule.onNodeWithTag(ClockOutTags.MESSAGE).assertTextEquals(confirmed).assertIsDisplayed().assertIsFocused()
        rule.onAllNodesWithTag(ClockOutTags.CLOSE).assertCountEquals(1)
    }

    @Test
    fun `focus moves to the confirmed message, which a screen reader is told about`() {
        show(ClockOutState.Done(confirmed, SIGNATURE))
        val message = rule.onNodeWithTag(ClockOutTags.MESSAGE)
        message.assertIsFocused()
        assertEquals(LiveRegionMode.Polite, message.fetchSemanticsNode().config.getOrNull(SemanticsProperties.LiveRegion))
    }

    @Test
    fun `the link to the transaction opens the explorer of the build's cluster, and is absent where there is none`() {
        show(ClockOutState.Done(confirmed, SIGNATURE), cluster = "solana:mainnet")
        rule.onNodeWithTag(ClockOutTags.TRANSACTION).assertTextEquals("See this transaction on-chain").performClick()
        assertEquals(listOf("https://solscan.io/tx/$SIGNATURE"), opened)

        show(ClockOutState.Done(confirmed, SIGNATURE), cluster = "solana:devnet")
        rule.onNodeWithTag(ClockOutTags.TRANSACTION).performClick()
        assertEquals("https://explorer.solana.com/tx/$SIGNATURE?cluster=devnet", opened.last())

        // A local validator has no public explorer; a result without a signature has nothing to link to.
        show(ClockOutState.Done(confirmed, SIGNATURE), cluster = "solana:localnet")
        rule.onAllNodesWithTag(ClockOutTags.TRANSACTION).assertCountEquals(0)
        show(ClockOutState.Done(confirmed), cluster = "solana:mainnet")
        rule.onAllNodesWithTag(ClockOutTags.TRANSACTION).assertCountEquals(0)
        show(ClockOutState.Done(confirmed, "not a signature"), cluster = "solana:mainnet")
        rule.onAllNodesWithTag(ClockOutTags.TRANSACTION).assertCountEquals(0)
        assertEquals(2, opened.size)
    }

    // ------------------------------------------------------------------ every state

    @Test
    fun `every state has exactly one control that closes the sheet`() {
        for ((name, state, _) in states) {
            show(state)
            rule.onAllNodesWithTag(ClockOutTags.CLOSE).assertCountEquals(1)
            val before = closes
            rule.onNodeWithTag(ClockOutTags.CLOSE).assertIsEnabled().assertTextEquals(if (state is ClockOutState.Done) "Done" else "Close").performClick()
            assertEquals(name, before + 1, closes)
            // It is the bar in the dock unless the dock is taken by what the state is for.
            val inDock = state is ClockOutState.Loading || state is ClockOutState.NoRig || state is ClockOutState.Done
            assertEquals(name, inDock, bounds(ClockOutTags.CLOSE).height >= 56.dp && bounds(ClockOutTags.CLOSE).width >= 300.dp)
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

    /** The figures the ledger must show for [state], as the existing formatters write them. */
    private fun amountsOf(state: ClockOutState): List<String> {
        val ready = state as? ClockOutState.Ready ?: return emptyList()
        val shown = ready.shown
        return buildList {
            add(ClockOutLedger.shift(shown.shift))
            if (shown.shift is ShiftOutcome.Ends && shown.shiftLogRent > 0uL) add("0.00130048 SOL")
            if (shown.bond != BondOutcome.None) add("100 SKR")
            if (shown.returnedSolLamports > 0uL) add("0.0032 SOL")
            add(if (shown.oreInMiner > 0uL) "0.00020001 ORE" else "0 ORE")
        }
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
    fun `on a phone on its side the end-early warning still comes into view`() = assertTheWarningComesIntoView()

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
    fun `the stillness check does see an animation that never ends`() {
        // The positive control: what every state above is shown not to do.
        rule.mainClock.autoAdvance = false
        show {
            val breath by rememberInfiniteTransition(label = "control").animateFloat(
                initialValue = 0.5f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(1_600), RepeatMode.Reverse),
                label = "control-alpha",
            )
            Box(Modifier.size(10.dp).graphicsLayer { alpha = breath })
        }
        advance(2_000)
        assertTrue("an infinite transition is pending work", pendingWork())
        assertTrue(writesDuring(500) > 0)
    }

    @Test
    fun `a warning arrives on the settle spring and the sheet is still again`() {
        rule.mainClock.autoAdvance = false
        showLive(ClockOutState.Ready(insideWindow))
        assertAtRest("before the choice")
        rule.onNodeWithTag(ClockOutTags.END_EARLY).performClick()
        rule.waitForIdle()
        // One frame for the recomposition that adds the warning; it is then on its way in.
        advance(16)
        assertTrue("the warning is arriving", pendingWork())
        assertAtRest("after the choice")
        rule.onNodeWithTag(ClockOutTags.END_EARLY_WARNING).assertIsDisplayed()
        // Taking the choice back removes it at once, and nothing is left running.
        rule.onNodeWithTag(ClockOutTags.END_EARLY).performClick()
        rule.waitForIdle()
        assertAtRest("after taking it back")
        rule.onAllNodesWithTag(ClockOutTags.END_EARLY_WARNING).assertCountEquals(0)
    }

    @Test
    fun `with the system's animations off the warning is simply there`() {
        ValueAnimator::class.java.getMethod("setDurationScale", Float::class.javaPrimitiveType).invoke(null, 0f)
        try {
            rule.mainClock.autoAdvance = false
            showLive(ClockOutState.Ready(insideWindow))
            advance(100)
            rule.onNodeWithTag(ClockOutTags.END_EARLY).performClick()
            rule.waitForIdle()
            // A frame to add it and a frame to place it: no spring is left running after that.
            advance(32)
            assertFalse("nothing animates when animations are removed", pendingWork())
            assertEquals(0, writesDuring(1_000))
            rule.onNodeWithTag(ClockOutTags.END_EARLY_WARNING).assertIsDisplayed()
        } finally {
            ValueAnimator::class.java.getMethod("setDurationScale", Float::class.javaPrimitiveType).invoke(null, 1f)
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
    fun `the choices say what they are - a checkbox, two radio buttons, chosen or not, usable or not`() {
        fun role(expected: Role) = SemanticsMatcher.expectValue(SemanticsProperties.Role, expected)
        show(ClockOutState.Ready(insideWindow))
        rule.onNodeWithTag(ClockOutTags.END_EARLY).assert(role(Role.Checkbox)).assertIsNotSelected().assertIsEnabled().assertTextEquals("End the shift now")
        rule.onNodeWithTag(ClockOutTags.KEEP_CHOICE).assert(role(Role.RadioButton)).assertIsSelected().assertTextEquals("Keep it in my Miner")
        rule.onNodeWithTag(ClockOutTags.CLAIM_CHOICE).assert(role(Role.RadioButton)).assertIsNotSelected().assertTextEquals("Claim all to my wallet")
        rule.onNodeWithTag(ClockOutTags.CONFIRM).assert(role(Role.Button)).assertIsNotEnabled().assertTextEquals("Clock out")
        // The two radio buttons are one group to a screen reader.
        val group = unmergedNodes().single { it.config.getOrNull(SemanticsProperties.SelectableGroup) != null }
        assertEquals(listOf(ClockOutTags.KEEP_CHOICE, ClockOutTags.CLAIM_CHOICE), group.children.mapNotNull { it.config.getOrNull(SemanticsProperties.TestTag) })
        for (tag in listOf(ClockOutTags.END_EARLY, ClockOutTags.KEEP_CHOICE, ClockOutTags.CLAIM_CHOICE)) {
            assertTrue("$tag is at least 48dp tall", bounds(tag).height >= 48.dp)
        }

        show(ClockOutState.Ready(insideWindow, claimAll = true, endEarly = true, working = true))
        rule.onNodeWithTag(ClockOutTags.END_EARLY).assertIsSelected().assertIsNotEnabled()
        rule.onNodeWithTag(ClockOutTags.KEEP_CHOICE).assertIsNotSelected().assertIsNotEnabled()
        rule.onNodeWithTag(ClockOutTags.CLAIM_CHOICE).assertIsSelected().assertIsNotEnabled()
        rule.onNodeWithTag(ClockOutTags.CONFIRM).assertIsNotEnabled().assertTextEquals("Waiting for your wallet…")
        // The way out stays usable while the wallet is open, and is a full touch target.
        rule.onNodeWithTag(ClockOutTags.CLOSE).assertIsEnabled()
        assertTrue(bounds(ClockOutTags.CLOSE).height >= 48.dp && bounds(ClockOutTags.CLOSE).width >= 48.dp)
    }

    @Test
    fun `a screen reader gets a heading, each row as one line, and the dock last`() {
        show(ClockOutState.Ready(completed, claimAll = true))
        rule.onNode(isHeading()).assertTextEquals("Clock out").assertIsDisplayed()
        // The name of a row and its amount are one node; the sentence under them is another.
        rule.onNodeWithText("Log rent").assertTextEquals("Log rent", "0.00130048 SOL")
        rule.onNodeWithText("In your Miner").assertTextEquals("In your Miner", "0.00020001 ORE")
        rule.onNodeWithTag(ClockOutTags.BOND).assertTextEquals("Your Focus Bond comes back to your wallet: 100 SKR.")
        // Capitals are drawn: what is read out is the word as written.
        assertTrue("Shift" in textsInOrder() && "SHIFT" !in textsInOrder())
        // Top to bottom: the way out, the choices, and the bar in the dock after everything else.
        assertEquals(listOf(ClockOutTags.CLOSE, ClockOutTags.KEEP_CHOICE, ClockOutTags.CLAIM_CHOICE, ClockOutTags.CONFIRM), actionsInOrder())
        assertEquals(ClockOutTags.CONFIRM, tagsInOrder().last())
        // Consequences and problems are said aloud when they appear, once the current sentence is over.
        for (state in listOf(ClockOutState.Ready(insideWindow, endEarly = true), ClockOutState.Ready(completed, problem = ClockOutModel.NOT_LANDED))) {
            show(state)
            val tag = if (state.problem != null) ClockOutTags.PROBLEM else ClockOutTags.END_EARLY_WARNING
            assertEquals(LiveRegionMode.Polite, rule.onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.LiveRegion))
        }
        // Once confirmed there is no title: the sheet's name is the heading over the result.
        show(ClockOutState.Done(confirmed))
        rule.onNode(isHeading()).assertTextEquals("Clock out")
    }

    @Test
    fun `the motor is asked once for a confirmed result and once for each problem, never otherwise`() {
        var state by mutableStateOf<ClockOutState>(ClockOutState.Loading)
        show { ClockOutScreen(state, {}, {}, {}, {}, {}) }
        fun becomes(next: ClockOutState) {
            state = next
            rule.waitForIdle()
        }
        becomes(ClockOutState.Ready(completed))
        becomes(ClockOutState.Ready(completed, claimAll = true))
        becomes(ClockOutState.Ready(completed, claimAll = true, working = true))
        assertEquals(emptyList<String>(), haptics.cues)

        becomes(ClockOutState.Ready(completed, claimAll = true, problem = ClockOutModel.NOT_LANDED))
        assertEquals(listOf("refuse"), haptics.cues)
        // Choosing again clears the problem; trying again and failing the same way is a new refusal.
        becomes(ClockOutState.Ready(completed, claimAll = true, working = true))
        becomes(ClockOutState.Ready(completed, claimAll = true, problem = ClockOutModel.NOT_LANDED))
        assertEquals(listOf("refuse", "refuse"), haptics.cues)

        becomes(ClockOutState.Ready(completed, claimAll = true, working = true))
        becomes(ClockOutState.Done(confirmed, SIGNATURE))
        assertEquals(listOf("refuse", "refuse", "confirm"), haptics.cues)
        // Nothing more however long the result stays on screen.
        rule.mainClock.advanceTimeBy(5_000)
        rule.waitForIdle()
        assertEquals(3, haptics.cues.size)

        // An unreadable chain is a problem too.
        haptics.cues.clear()
        show(ClockOutState.Unavailable(ClockOutModel.UNREADABLE))
        assertEquals(listOf("refuse"), haptics.cues)
    }

    // ------------------------------------------------------------------ words

    @Test
    fun `the words the rebuild added use none the app never uses, and are written the way they are read`() {
        assertTrue(ClockOutLedger.all.size >= 15)
        for (label in ClockOutLedger.all) assertHonestLabel(label)
        // The same check sees a banned word and typed capitals when there is one.
        assertFalse(runCatching { assertHonestLabel("Staked SKR") }.isSuccess)
        assertFalse(runCatching { assertHonestLabel("CLOCK OUT") }.isSuccess)
        // What the shift row says at a glance, for every outcome, in the words of the sentence under it.
        assertEquals("None open", ClockOutLedger.shift(ShiftOutcome.NoOpenShift))
        assertEquals("Completed", ClockOutLedger.shift(ShiftOutcome.Ends(ShiftEndReason.COMPLETED)))
        assertEquals("Ended early", ClockOutLedger.shift(ShiftOutcome.Ends(ShiftEndReason.PICKUP)))
        assertEquals("Stays open", ClockOutLedger.shift(ShiftOutcome.LeftOpen(ShiftEndReason.MANUAL, 0L)))
        // Amounts go through the formatters the sentences use: dust never reads as zero.
        assertEquals("<0.00000001 ORE", ClockOutLedger.ore(999uL))
        assertEquals("0.00130048 SOL", ClockOutLedger.sol(1_300_480uL))
        assertEquals("0.5 SKR", ClockOutLedger.bond(BondOutcome.Forfeit(500_000uL, ShiftEndReason.PICKUP)))
        assertNull(ClockOutLedger.bond(BondOutcome.None))
    }

    @Test
    fun `everything drawn on every state passes the honesty scan`() {
        for ((name, state, _) in states) {
            show(state)
            for (text in textsInOrder()) assertEquals("$name: \"$text\"", emptyList<String>(), HonestCopy.violations(text))
        }
    }

    // --- "Buy ORE" ---

    @Test
    fun `a build that cannot buy has no buy row, and one that can starts with it off`() {
        var state by mutableStateOf<ClockOutState>(ClockOutState.Ready(completed))
        show { ClockOutScreen(state, {}, {}, {}, {}, {}) }
        rule.onAllNodesWithTag(ClockOutTags.BUY).assertCountEquals(0)
        rule.onAllNodesWithText(ClockOutLedger.BUY).assertCountEquals(0)

        state = ClockOutState.Ready(completed, buy = offered)
        rule.waitForIdle()
        rule.onNodeWithTag(ClockOutTags.BUY).performScrollTo().assertIsDisplayed().assertTextEquals(ClockOutCopy.BUY_OFFER)
        rule.onNodeWithTag(ClockOutTags.BUY_CHOICE + "0").assertIsSelected().assertTextEquals("Off")
        for (spend in BuyChoice.OFFERS) rule.onNodeWithTag(ClockOutTags.BUY_CHOICE + spend).assertIsNotSelected()
        rule.onAllNodesWithTag(ClockOutTags.BUY_QUOTE).assertCountEquals(0)
        rule.onNodeWithTag(ClockOutTags.CONFIRM).assertIsEnabled().assertTextEquals("Clock out")
    }

    @Test
    fun `choosing an amount asks once, states the quote before the wallet, and the bar names the buy`() {
        val asked = mutableListOf<ULong>()
        var state by mutableStateOf<ClockOutState>(ClockOutState.Ready(completed, buy = offered))
        show { ClockOutScreen(state, {}, {}, {}, {}, {}, onBuy = { asked += it }) }
        rule.onNodeWithTag(ClockOutTags.BUY_CHOICE + "1000000").performScrollTo().assertTextEquals("0.001 SOL").performClick()
        assertEquals(listOf(1_000_000uL), asked)

        // The market is being asked: the bar waits, and the sheet does not say there is nothing to sign.
        state = ClockOutState.Ready(completed, buy = offered.copy(spendLamports = 1_000_000uL, quoting = true))
        rule.waitForIdle()
        rule.onNodeWithTag(ClockOutTags.BUY_QUOTE).assertTextEquals(ClockOutCopy.BUY_QUOTING)
        rule.onNodeWithTag(ClockOutTags.CONFIRM).assertIsNotEnabled()
        rule.onAllNodesWithTag(ClockOutTags.NOTHING).assertCountEquals(0)

        state = ClockOutState.Ready(completed, buy = quoted)
        rule.waitForIdle()
        rule.onNodeWithTag(ClockOutTags.BUY_CHOICE + "1000000").assertIsSelected()
        rule.onNodeWithTag(ClockOutTags.BUY_QUOTE).assertTextEquals(ClockOutCopy.buyQuote(marketQuote))
        rule.onNodeWithText("about 0.00096488 ORE").assertExists()
        rule.onNodeWithText(ClockOutCopy.BUY_FIRST_ORE).assertExists()
        rule.onNodeWithTag(ClockOutTags.CONFIRM).assertIsEnabled().assertTextEquals("Clock out and buy ORE")
        // Tapping the amount already chosen does not ask the market again; "Off" does turn it off.
        rule.onNodeWithTag(ClockOutTags.BUY_CHOICE + "1000000").performScrollTo().performClick()
        rule.onNodeWithTag(ClockOutTags.BUY_CHOICE + "0").performScrollTo().performClick()
        assertEquals(listOf(1_000_000uL, 0uL), asked)

        // Nothing else to sign: the bar says what the approval is for.
        state = ClockOutState.Ready(nothingAtAll, buy = quoted)
        rule.waitForIdle()
        rule.onNodeWithTag(ClockOutTags.CONFIRM).assertIsEnabled().assertTextEquals("Buy ORE")
        rule.onAllNodesWithTag(ClockOutTags.NOTHING).assertCountEquals(0)

        // No quote: nothing will be bought, and the bar is the plain clock-out again.
        state = ClockOutState.Ready(completed, buy = offered.copy(spendLamports = 5_000_000uL, noQuote = true))
        rule.waitForIdle()
        rule.onNodeWithTag(ClockOutTags.BUY_QUOTE).assertTextEquals(ClockOutCopy.BUY_NO_QUOTE)
        rule.onNodeWithTag(ClockOutTags.CONFIRM).assertIsEnabled().assertTextEquals("Clock out")

        // Ending early with a buy: the bar names both things the approval covers.
        state = ClockOutState.Ready(insideWindow, endEarly = true, buy = quoted)
        rule.waitForIdle()
        rule.onNodeWithTag(ClockOutTags.CONFIRM).assertIsEnabled().assertTextEquals("End the shift and buy ORE")

        // The wallet is open: the choice cannot be changed under it.
        state = ClockOutState.Ready(completed, buy = quoted, working = true)
        rule.waitForIdle()
        for (spend in listOf(0uL) + BuyChoice.OFFERS) rule.onNodeWithTag(ClockOutTags.BUY_CHOICE + spend).assertIsNotEnabled()
    }
}
