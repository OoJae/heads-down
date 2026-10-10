package xyz.headsdown.core.design.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import xyz.headsdown.core.design.BrandGlyph
import xyz.headsdown.core.design.HeadsDownTheme
import xyz.headsdown.core.design.Wordmark

/** What the text, the plates and the hero slot tell a screen reader and a test. */
@RunWith(RobolectricTestRunner::class)
class TextAndPlatesTest {
    @get:Rule val rule = createComposeRule()

    /** Every text and description composed, node by node (the unmerged tree, as the screen tests walk it). */
    private fun allTexts(): List<String> = texts(merged = false)

    /**
     * What accessibility is given (the merged tree): a node that clears the semantics of what it
     * contains shows only what it set itself.
     */
    private fun spokenTexts(): List<String> = texts(merged = true)

    private fun texts(merged: Boolean): List<String> {
        val out = mutableListOf<String>()
        fun walk(n: SemanticsNode) {
            n.config.getOrNull(SemanticsProperties.Text)?.forEach { out += it.text }
            n.config.getOrNull(SemanticsProperties.ContentDescription)?.let { out += it }
            n.children.forEach(::walk)
        }
        walk(rule.onRoot(useUnmergedTree = !merged).fetchSemanticsNode())
        return out
    }

    // ------------------------------------------------------------------ DisplayText, Label, HeroNumerals

    @Test
    fun `display text is drawn in capitals and found as it was written, under the caller's tag`() {
        rule.setContent {
            HeadsDownTheme {
                Column {
                    DisplayText("Rig cold", Modifier.testTag("word"))
                    DisplayText("Armed", Modifier.testTag("as-written"), caps = false)
                }
            }
        }
        // What the screen tests and the emulator script match.
        rule.onNodeWithTag("word").assertTextEquals("Rig cold").assertIsDisplayed()
        rule.onNodeWithText("Rig cold").assertExists()
        rule.onAllNodesWithText("RIG COLD").assertCountEquals(0)
        rule.onNodeWithTag("as-written").assertTextEquals("Armed")
        assertEquals(listOf("Rig cold", "Armed"), allTexts())
    }

    @Test
    fun `display text follows a change of its text`() {
        var word by mutableStateOf("Rig cold")
        rule.setContent { HeadsDownTheme { DisplayText(word, Modifier.testTag("word")) } }
        rule.onNodeWithTag("word").assertTextEquals("Rig cold")
        word = "Rig hot"
        rule.waitForIdle()
        rule.onNodeWithTag("word").assertTextEquals("Rig hot")
    }

    @Test
    fun `a label is found as written, and can be a heading`() {
        rule.setContent {
            HeadsDownTheme {
                Column {
                    Label("Focus Bond", Modifier.testTag("plain"))
                    Label("Rig", Modifier.testTag("heading"), heading = true)
                    SectionHeader("Haul", Modifier.testTag("section"))
                }
            }
        }
        rule.onNodeWithTag("plain").assertTextEquals("Focus Bond")
        assertNull(rule.onNodeWithTag("plain").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Heading))
        rule.onNodeWithTag("heading").assertTextEquals("Rig").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        rule.onNodeWithText("Haul").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        rule.onAllNodesWithText("FOCUS BOND").assertCountEquals(0)
    }

    @Test
    fun `hero numerals are one text, the number as given`() {
        rule.setContent { HeadsDownTheme { Box(Modifier.width(200.dp)) { HeroNumerals("0.0194", Modifier.testTag("hero")) } } }
        rule.onNodeWithTag("hero").assertTextEquals("0.0194").assertIsDisplayed()
        assertTrue(rule.onNodeWithTag("hero").getUnclippedBoundsInRoot().width <= 200.dp)
    }

    @Test
    fun `a capped font scale never passes its cap and never changes the density`() {
        val seen = mutableListOf<Density>()
        rule.setContent {
            val outer = LocalDensity.current
            Column {
                for (scale in listOf(0.85f, 1f, 1.3f, 1.5f, 2f)) {
                    CompositionLocalProvider(LocalDensity provides Density(outer.density, scale)) {
                        ProvideCappedFontScale(1.3f) { seen += LocalDensity.current }
                    }
                }
            }
        }
        rule.waitForIdle()
        assertEquals(listOf(0.85f, 1f, 1.3f, 1.3f, 1.3f), seen.map { it.fontScale })
        assertEquals(1, seen.map { it.density }.toSet().size)
    }

    // ------------------------------------------------------------------ StatRow, StatBlock, LedgerRow

    @Test
    fun `a stat is one node that reads label then value`() {
        rule.setContent {
            HeadsDownTheme {
                Column {
                    StatRow("Rounds dark", "55", Modifier.testTag("row"))
                    StatBlock("Dark for", "1:12", Modifier.testTag("block"))
                }
            }
        }
        rule.onNodeWithTag("row").assertTextEquals("Rounds dark", "55")
        rule.onNodeWithTag("block").assertTextEquals("Dark for", "1:12")
        // HomeAndIntroTest finds the count by its text alone.
        rule.onNodeWithText("55").assertExists()
    }

    @Test
    fun `a ledger row is one node - label, amount, and its note under them`() {
        rule.setContent {
            HeadsDownTheme {
                Column(Modifier.width(320.dp)) {
                    LedgerRow("Rent for the shift log", Modifier.testTag("rent"), figure = "0.00130048 SOL")
                    LedgerRow("Focus Bond", Modifier.testTag("bond"), figure = "100 SKR") {
                        BasicText("Comes back to your wallet.")
                    }
                    LedgerRow("Nothing to pay", Modifier.testTag("bare"))
                }
            }
        }
        rule.onNodeWithTag("rent").assertTextEquals("Rent for the shift log", "0.00130048 SOL")
        rule.onNodeWithTag("bond").assertTextEquals("Focus Bond", "100 SKR", "Comes back to your wallet.")
        rule.onNodeWithTag("bare").assertTextEquals("Nothing to pay")
        for (tag in listOf("rent", "bond", "bare")) assertEquals(320.dp, rule.onNodeWithTag(tag).getUnclippedBoundsInRoot().width)
    }

    /** Real text measurement: Robolectric's default makes every string a few pixels wide, so nothing would ever stack. */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `an amount shares the line while it fits, takes its own line when it does not, and is never cut`() {
        val short = "0.00130048 SOL"
        val long = "1,234,567.00130048 SOL in all"
        val huge = "1,234,567.00130048 SOL of 2,000,000.00000000 SOL placed tonight in total"
        rule.setContent {
            HeadsDownTheme {
                Column(Modifier.width(320.dp)) {
                    LedgerRow("Rent", figure = short)
                    LedgerRow("Placed", figure = long)
                    LedgerRow("Total", figure = huge)
                }
            }
        }
        fun bounds(text: String) = rule.onNodeWithText(text, useUnmergedTree = true).getUnclippedBoundsInRoot()
        val oneLine = bounds(short).height

        // Fits: on the label's line, flush right, at its full width.
        assertTrue(bounds(short).top < bounds("Rent").bottom)
        assertEquals(320.dp.value, bounds(short).right.value, 0.5f)
        assertTrue(bounds(short).left > bounds("Rent").right)

        // Does not fit beside the label: under it, from the left edge, still on one line.
        assertTrue(bounds(long).top >= bounds("Placed").bottom)
        assertEquals(0f, bounds(long).left.value, 0.5f)
        assertEquals(oneLine.value, bounds(long).height.value, 0.5f)
        assertTrue(bounds(long).width <= 320.dp)

        // Wider than the row: it wraps. Every character is on screen; nothing is ellipsized.
        assertTrue(bounds(huge).top >= bounds("Total").bottom)
        assertTrue(bounds(huge).width <= 320.dp)
        assertTrue(bounds(huge).height >= oneLine * 2)
    }

    // ------------------------------------------------------------------ Notice, Plate, StepRow

    @Test
    fun `a notice is a polite live region, and a problem carries its text and its action`() {
        var retries = 0
        rule.setContent {
            HeadsDownTheme {
                Column {
                    Notice("Nothing was spent.", Modifier.testTag("info"))
                    Notice("The chain could not be read.", Modifier.testTag("problem"), tone = NoticeTone.Problem) {
                        TextAction("Try again", onClick = { retries++ })
                    }
                }
            }
        }
        for (text in listOf("Nothing was spent.", "The chain could not be read.")) {
            rule.onNodeWithText(text).assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        }
        rule.onNodeWithText("Try again").assertHasClickAction().performClick()
        assertEquals(1, retries)
        // The problem mark is decoration: the sentence is what is read.
        assertEquals(listOf("Nothing was spent.", "The chain could not be read.", "Try again"), allTexts())
    }

    @Test
    fun `a plate is a button only when it can be tapped`() {
        var taps = 0
        rule.setContent {
            HeadsDownTheme {
                Column {
                    Plate(Modifier.testTag("still")) { BasicText("Haul") }
                    Plate(Modifier.testTag("tap"), tone = PlateTone.Slab, onClick = { taps++ }) { BasicText("Open the morning reveal") }
                }
            }
        }
        rule.onNodeWithTag("still").assertHasNoClickAction()
        assertNull(rule.onNodeWithTag("still").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Role))
        rule.onNodeWithTag("tap").assertHasClickAction().assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assertTextEquals("Open the morning reveal").performClick()
        assertEquals(1, taps)
    }

    @Test
    fun `a step says its state in words, and its number is only a drawing`() {
        var opened = 0
        rule.setContent {
            HeadsDownTheme {
                Column {
                    StepRow(1, "Notifications", Modifier.testTag("done"), subtitle = "Your shift shows as an ongoing notification.", status = StepStatus.Done)
                    StepRow(2, "Morning alarm", Modifier.testTag("current"), status = StepStatus.Current, onClick = { opened++ }) {
                        BarButton("Allow exact alarm", onClick = {}, modifier = Modifier.testTag("action"))
                    }
                    StepRow(3, "Create your rig key", Modifier.testTag("todo"))
                }
            }
        }
        fun state(text: String) = rule.onNodeWithText(text).fetchSemanticsNode().config[SemanticsProperties.StateDescription]
        assertEquals("Done", state("Notifications"))
        assertEquals("Current step", state("Morning alarm"))
        assertEquals("Not done yet", state("Create your rig key"))
        rule.onNodeWithText("Notifications").assertTextEquals("Notifications", "Your shift shows as an ongoing notification.")
        rule.onNodeWithText("Morning alarm").assertHasClickAction().performClick()
        assertEquals(1, opened)
        rule.onNodeWithText("Create your rig key").assertHasNoClickAction()
        rule.onNodeWithTag("action").assertTextEquals("Allow exact alarm").assertHasClickAction()
        assertTrue("no step number is read out", allTexts().none { it in setOf("1", "2", "3") })
    }

    // ------------------------------------------------------------------ HeatPixels, skeletons, the marks

    @Test
    fun `heat pixels say what they were told to, or nothing`() {
        rule.setContent {
            HeadsDownTheme {
                Column {
                    HeatPixels(3, Modifier.testTag("said"), description = "Heat 3 of 5")
                    HeatPixels(5, Modifier.testTag("silent"))
                    SkeletonLine(Modifier.testTag("line"), widthFraction = 0.6f)
                    SkeletonBlock(Modifier.testTag("block"))
                    BrandGlyph(Modifier.size(28.dp).testTag("glyph"))
                    Wordmark(Modifier.testTag("wordmark"))
                }
            }
        }
        rule.onNodeWithContentDescription("Heat 3 of 5").assertExists()
        // The size the home screen's own pixels always had.
        val said = rule.onNodeWithTag("said").getUnclippedBoundsInRoot()
        assertEquals(38.dp, said.width)
        assertEquals(6.dp, said.height)
        rule.onNodeWithContentDescription("Heads Down").assertExists()
        // The wordmark is its name once, not two words in capitals.
        assertEquals(listOf("Heat 3 of 5", "Heads Down"), spokenTexts())
        rule.onAllNodesWithText("HEADS").assertCountEquals(0)
        assertEquals(28.dp, rule.onNodeWithTag("glyph").getUnclippedBoundsInRoot().width)
    }

    // ------------------------------------------------------------------ Hero

    @Test
    fun `the hero is one description and no more, whatever draws it`() {
        val specs = mutableListOf<HeroSpec>()
        val chatty: @androidx.compose.runtime.Composable (HeroSpec, Modifier) -> Unit = { spec, modifier ->
            specs += spec
            Box(modifier.size(100.dp)) { BasicText("a renderer's own text") }
        }
        rule.setContent {
            HeadsDownTheme {
                Column {
                    Hero(HeroSpec(SlabState.Hot, heat = 5), Modifier.testTag("default"), contentDescription = "The rig is hot")
                    Hero(HeroSpec(SlabState.Cold, heat = 0), Modifier.testTag("decoration"))
                    CompositionLocalProvider(LocalHeroRenderer provides chatty) {
                        Hero(HeroSpec(SlabState.Armed, heat = 9), Modifier.testTag("provided"), contentDescription = "The rig is armed")
                    }
                }
            }
        }
        rule.onNodeWithTag("default").assertIsDisplayed()
        assertTrue(rule.onNodeWithTag("default").getUnclippedBoundsInRoot().height > 0.dp)
        // Whatever a renderer composes inside, accessibility gets the one description.
        assertEquals(listOf("The rig is hot", "The rig is armed"), spokenTexts())
        rule.onAllNodesWithText("a renderer's own text").assertCountEquals(0)
        assertEquals(listOf(SlabState.Armed to 5), specs.map { it.state to it.heat }.distinct())
        val node = rule.onNodeWithTag("default").fetchSemanticsNode().config
        assertTrue(!node.contains(SemanticsActions.OnClick))
    }

    @Test
    fun `the default hero is a drawing - no text, no node under it, no action, in every state`() {
        rule.setContent {
            HeadsDownTheme {
                Column {
                    for (state in SlabState.entries) {
                        Hero(HeroSpec(state, heat = 3, interactive = true, enter = true), Modifier.testTag(state.name), contentDescription = "Slab ${state.name}")
                    }
                    Hero(HeroSpec(SlabState.Hot, heat = 5), Modifier.testTag("decoration"))
                }
            }
        }
        // The unmerged tree, as the screen tests walk it: the stand-in composes nothing but a canvas.
        for (state in SlabState.entries) {
            val node = rule.onNodeWithTag(state.name, useUnmergedTree = true).fetchSemanticsNode()
            assertTrue("${state.name}: no node under the hero", node.children.isEmpty())
            assertEquals(setOf(SemanticsProperties.ContentDescription, SemanticsProperties.TestTag), node.config.map { it.key }.toSet())
        }
        val decoration = rule.onNodeWithTag("decoration", useUnmergedTree = true).fetchSemanticsNode()
        assertEquals(setOf<Any>(SemanticsProperties.TestTag), decoration.config.map { it.key }.toSet())
        assertEquals(SlabState.entries.map { "Slab ${it.name}" }, allTexts())
    }

    @Test
    fun `a hero spec clamps heat and defaults to a still, untouchable slab`() {
        val spec = HeroSpec(SlabState.Cooling, heat = -3)
        assertEquals(0, spec.heat)
        assertEquals(5, HeroSpec(SlabState.Hot, heat = 12).heat)
        assertEquals(0f, spec.scrollPx(), 0f)
        assertTrue(!spec.interactive && !spec.enter)
        spec.onFlipped()
        spec.onKnock()
        assertEquals(listOf("Cold", "Armed", "Hot", "Cooling", "Broken", "Frozen"), SlabState.entries.map { it.name })
    }

    @Test
    fun `the default haptics do nothing`() {
        var seen: HdHaptics? = null
        rule.setContent { seen = LocalHdHaptics.current }
        rule.waitForIdle()
        assertTrue(seen === HdHaptics.None)
        seen!!.apply { knock(); snap(); confirm(); refuse() }
    }
}
