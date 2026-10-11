package xyz.headsdown.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.DpRect
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
import xyz.headsdown.feature.shift.ShiftState

/**
 * The home screen's first screenful on the smallest phone it runs on (360dp wide, 800dp tall),
 * measured with the real fonts: the heading, the rig's word, "Rounds dark" and the bar are all
 * there before anything is scrolled, at the normal font and at the largest.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w360dp-h800dp-xhdpi")
class HomeFirstScreenTest : ScreenTest() {

    private fun bounds(tag: String): DpRect = rule.onNodeWithTag(tag).getUnclippedBoundsInRoot()

    /** The bar the dock offers in this state (the first of the two while a shift runs). */
    private fun barTag(case: HomeCase): String = when (case.snapshot.state) {
        ShiftState.Idle, is ShiftState.Broken -> HomeTags.CLOCK_IN
        is ShiftState.Frozen -> HomeTags.UNFREEZE
        else -> HomeTags.END_SHIFT
    }

    /** The heading, the rig word, "Rounds dark" and the dock's bar are whole and on screen, unscrolled. */
    private fun assertFirstScreenful(case: HomeCase, scale: Float, bars: Boolean = false) {
        val what = "${case.name} at font scale $scale"
        val screen = rootHeight() - if (bars) NAVIGATION_BAR else 0.dp
        val top = if (bars) STATUS_BAR else 0.dp
        val dock = bounds(HomeTags.DOCK)
        val heading = rule.onNodeWithText("Your phone's night shift").assert(isHeading()).getUnclippedBoundsInRoot()
        val word = bounds(HomeTags.RIG_WORD)
        val rounds = bounds(HomeTags.ROUNDS_DARK)
        val bar = bounds(barTag(case))

        assertEquals("$what: the dock ends the screen", screen.value, dock.bottom.value, 0.5f)
        for ((name, box) in listOf("the heading" to heading, "the rig word" to word, "Rounds dark" to rounds)) {
            assertTrue("$what: $name starts on screen: $box", box.top >= top && box.left >= 0.dp)
            assertTrue("$what: $name ends above the dock (${dock.top}): $box", box.bottom <= dock.top)
            assertTrue("$what: $name is inside the screen's width: $box", box.right <= 360.dp)
        }
        assertTrue("$what: the order is heading, word, rounds: $heading $word $rounds", heading.bottom <= word.top && word.bottom <= rounds.top)
        // The primary action is inside the root, whole, and a bar's height at least.
        assertTrue("$what: the bar is inside the dock: $bar in $dock", bar.top >= dock.top && bar.bottom <= dock.bottom && bar.left >= 0.dp && bar.right <= 360.dp)
        assertTrue("$what: the bar is 56dp at least: $bar", bar.height >= 56.dp)
        rule.onNodeWithTag(barTag(case)).assertIsDisplayed()
        rule.onNodeWithTag(HomeTags.RIG_WORD).assertIsDisplayed()
        rule.onNodeWithTag(HomeTags.ROUNDS_DARK).assertIsDisplayed()
    }

    private fun layoutOf(tag: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        val node = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()
        checkNotNull(node.config[SemanticsActions.GetTextLayoutResult].action).invoke(results)
        return results.single()
    }

    /** A plain text is drawn whole: no line cut, none ending in an ellipsis, none wider than it was offered. */
    private fun assertDrawnWhole(tag: String, text: String) {
        val layout = layoutOf(tag)
        val lines = 0 until layout.lineCount
        assertFalse("$tag is taller than its box", layout.didOverflowHeight)
        assertEquals("$tag: every character is laid out", text.length, layout.getLineEnd(layout.lineCount - 1, visibleEnd = false))
        assertFalse("$tag ends in an ellipsis", lines.any(layout::isLineEllipsized))
        val box = bounds(tag)
        assertTrue("$tag is inside the margins: $box", box.left >= 20.dp && box.right <= 340.dp)
        // No line is wider than the box the text was given.
        for (line in lines) {
            val width = (layout.getLineRight(line) - layout.getLineLeft(line)).toDp()
            assertTrue("$tag line $line is $width wide in a box of ${box.width}", width <= box.width + 0.5.dp)
        }
    }

    // ------------------------------------------------------------------ the first screenful

    @Test fun `idle - the first screenful holds the heading, the rig word, Rounds dark and the bar`() = firstScreenful(Screens.idle)

    @Test fun `armed - the first screenful holds the heading, the rig word, Rounds dark and the bars`() = firstScreenful(Screens.armed)

    @Test fun `broken - the first screenful holds the heading, the rig word, Rounds dark and the bar`() = firstScreenful(Screens.broken)

    @Test fun `frozen - the first screenful holds the heading, the rig word, Rounds dark and the bar`() = firstScreenful(Screens.frozen)

    @Test fun `hot and cooling - the first screenful holds them too`() {
        show { Screens.Home(Screens.down) }
        assertFirstScreenful(Screens.down, 1f)
        rule.onNodeWithTag(HomeTags.SHIFT_CLOCK).assertIsDisplayed()
        rule.onNodeWithText("Dark for", useUnmergedTree = true).assertExists()
    }

    @Test fun `with the health banner the first screenful still holds them, and the banner is under the hero`() {
        show { Screens.Home(Screens.banner) }
        assertFirstScreenful(Screens.banner, 1f)
        val banner = bounds(HomeTags.HEALTH)
        // The banner shrinks the hero; it does not push the rig's state down the page.
        assertTrue("under the hero, over the rig word", banner.top >= bounds(HomeTags.HERO).bottom && banner.bottom <= bounds(HomeTags.RIG_WORD).top)
        assertTrue("the banner is on the first screenful", banner.bottom <= bounds(HomeTags.DOCK).top)
        rule.onNodeWithText("Fix keep-alive settings").assertIsDisplayed()
    }

    @Test fun `with a crank refusal the first screenful still holds them, and the refusal follows Rounds dark`() {
        show { Screens.Home(Screens.refusal) }
        assertFirstScreenful(Screens.refusal, 1f)
        assertTrue(bounds(HomeTags.CRANK_REFUSAL).top >= bounds(HomeTags.ROUNDS_DARK).bottom)
    }

    @Test fun `with the room a phone's bars leave, the first screenful still holds them`() {
        show(bars = true) { Screens.Home(Screens.banner) }
        assertFirstScreenful(Screens.banner, 1f, bars = true)
    }

    private fun firstScreenful(case: HomeCase) {
        show { Screens.Home(case) }
        assertFirstScreenful(case, 1f)
    }

    // ------------------------------------------------------------------ the largest font

    @Test fun `idle at font scale 2 - the same, and no amount is cut`() {
        show(2f) { Screens.Home(Screens.idle) }
        assertFirstScreenful(Screens.idle, 2f)
        assertAmountsWhole()
        // The line under the bar states an amount: it is whole, inside the dock.
        assertDrawnWhole(HomeTags.CLOCK_IN_CAP, Screens.cap)
        assertTrue(bounds(HomeTags.CLOCK_IN_CAP).bottom <= rootHeight())
    }

    @Test fun `armed at font scale 2 - the same, with the two bars one over the other`() {
        show(2f) { Screens.Home(Screens.armed) }
        assertFirstScreenful(Screens.armed, 2f)
        assertAmountsWhole()
        val end = bounds(HomeTags.END_SHIFT)
        val freeze = bounds(HomeTags.FREEZE)
        assertTrue("stacked: $end over $freeze", end.bottom <= freeze.top && end.width == freeze.width)
        assertTrue("both bars are inside the root", freeze.bottom <= rootHeight())
    }

    @Test fun `broken at font scale 2 - the same`() {
        show(2f) { Screens.Home(Screens.broken) }
        assertFirstScreenful(Screens.broken, 2f)
        assertAmountsWhole()
    }

    @Test fun `frozen at font scale 2 - the same`() {
        show(2f) { Screens.Home(Screens.frozen) }
        assertFirstScreenful(Screens.frozen, 2f)
        assertAmountsWhole()
    }

    @Test fun `at font scale 2 a banner gives way to the rig's state instead of pushing it off the screen`() {
        show(2f) { Screens.Home(Screens.banner) }
        assertFirstScreenful(Screens.banner, 2f)
        // There is no room for both: the banner follows the state it would have pushed down.
        assertTrue(bounds(HomeTags.HEALTH).top >= bounds(HomeTags.ROUNDS_DARK).bottom)
    }

    // The same at the largest font with the room a phone's two bars leave: tighter than the
    // 800dp the screen has, and the case a person with a large font and a running shift is in.

    @Test fun `idle at font scale 2 with a phone's bars - the rig word and Rounds dark are above the dock`() = largeOnAPhone(Screens.idle)

    @Test fun `armed at font scale 2 with a phone's bars - the rig word and Rounds dark are above the dock`() = largeOnAPhone(Screens.armed)

    @Test fun `hot at font scale 2 with a phone's bars - the rig word and Rounds dark are above the dock`() = largeOnAPhone(Screens.down)

    @Test fun `cooling at font scale 2 with a phone's bars - the rig word and Rounds dark are above the dock`() = largeOnAPhone(Screens.cooling)

    @Test fun `broken at font scale 2 with a phone's bars - the rig word and Rounds dark are above the dock`() = largeOnAPhone(Screens.broken)

    @Test fun `frozen at font scale 2 with a phone's bars - the rig word and Rounds dark are above the dock`() = largeOnAPhone(Screens.frozen)

    private fun largeOnAPhone(case: HomeCase) {
        show(2f, bars = true) { Screens.Home(case) }
        assertFirstScreenful(case, 2f, bars = true)
        assertAmountsWhole()
    }

    /** The two figures of the first screenful are drawn whole, beside or under each other, never over. */
    private fun assertAmountsWhole() {
        val rounds = bounds(HomeTags.ROUNDS_DARK)
        assertTrue("Rounds dark is inside the margins: $rounds", rounds.left >= 20.dp && rounds.right <= 340.dp)
        if (rule.onAllNodesWithTag(HomeTags.SHIFT_CLOCK).fetchSemanticsNodes().isNotEmpty()) {
            val clock = bounds(HomeTags.SHIFT_CLOCK)
            assertTrue("the clock is inside the margins: $clock", clock.left >= 20.dp && clock.right <= 340.dp)
            assertTrue("the two figures do not overlap: $clock, $rounds", clock.right <= rounds.left || clock.bottom <= rounds.top)
        }
        // The figure itself (the text under the label) is inside its block.
        val figure = rule.onNodeWithTag(HomeTags.ROUNDS_DARK, useUnmergedTree = true).fetchSemanticsNode().children.map { it.boundsInRoot }
        assertEquals("a label and a figure", 2, figure.size)
        for (box in figure) assertTrue("inside the block", box.left.toDp() >= rounds.left - 0.5.dp && box.right.toDp() <= rounds.right + 0.5.dp)
    }

    @Test fun `at font scale 2 the amounts further down the page are drawn whole too`() {
        show(2f) { Screens.Home(Screens.haul) }
        rule.onNodeWithTag(HomeTags.CLOCK_IN_AMOUNTS).performScrollTo()
        assertDrawnWhole(HomeTags.CLOCK_IN_AMOUNTS, Screens.amounts)

        rule.onNodeWithTag(HomeTags.HAUL).performScrollTo()
        val plate = bounds(HomeTags.HAUL)
        val amount = rule.onNodeWithText("0.0194 ORE", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue("the haul is inside its plate: $amount in $plate", amount.left >= plate.left + 16.dp && amount.right <= plate.right - 16.dp && amount.bottom <= plate.bottom)
        assertTrue("the plate is inside the margins", plate.left >= 20.dp && plate.right <= 340.dp)

        // Four Focus Bond choices: two rows of two, every label whole.
        val chips = listOf("Off", "10 SKR", "50 SKR", "100 SKR").map { label ->
            rule.onNodeWithTag(HomeTags.BOND_CHOICE + label).performScrollTo()
            bounds(HomeTags.BOND_CHOICE + label).also { chip ->
                val text = rule.onNodeWithText(label, useUnmergedTree = true).getUnclippedBoundsInRoot()
                assertTrue("$label is whole inside its chip: $text in $chip", text.left >= chip.left + 6.dp && text.right <= chip.right - 6.dp)
                assertTrue("$label is 48dp at least and under 64dp", chip.height >= 48.dp && chip.height < 64.dp)
            }
        }
        assertEquals("two rows", 2, chips.map { it.top }.toSet().size)
        assertTrue("two in each", chips[0].top == chips[1].top && chips[2].top == chips[3].top && chips[0].right <= chips[1].left)
    }

    // ------------------------------------------------------------------ every font scale between

    /** The font scales Android's own slider stops at, and the two ends. */
    private val scales = listOf(1f, 1.15f, 1.3f, 1.5f, 1.8f, 2f)

    /** Every state in [cases] at every scale in [scales], with a phone's bars, in one composition. */
    private fun atEveryScale(cases: List<HomeCase>, onboarding: OnboardingState = Screens.setUp, check: (HomeCase, Float) -> Unit) {
        var scale by mutableFloatStateOf(scales.first())
        var shown by mutableStateOf(cases.first())
        show({ scale }, bars = true) { key(shown, scale) { Screens.Home(shown, onboarding = onboarding) } }
        for (s in scales) {
            for (case in cases) {
                scale = s
                shown = case
                rule.waitForIdle()
                check(case, s)
            }
        }
    }

    @Test fun `at every font scale from 1 to 2 the first screenful holds in every state, on a phone with its bars`() {
        atEveryScale(Screens.homeCases) { case, scale ->
            assertFirstScreenful(case, scale, bars = true)
            assertAmountsWhole()
        }
    }

    @Test
    @Config(qualifiers = "w320dp-h568dp-xhdpi")
    fun `on a small screen the first screenful holds in every state, with or without the slab`() {
        var shown by mutableStateOf(Screens.idle)
        show(bars = true) { key(shown) { Screens.Home(shown) } }
        for (case in Screens.homeCases) {
            shown = case
            rule.waitForIdle()
            val dock = bounds(HomeTags.DOCK)
            for (tag in listOf(HomeTags.RIG_WORD, HomeTags.ROUNDS_DARK)) {
                val box = bounds(tag)
                assertTrue("${case.name}: $tag is whole above the dock: $box, ${dock.top}", box.top >= STATUS_BAR && box.bottom <= dock.top && box.left >= 0.dp && box.right <= 320.dp)
                rule.onNodeWithTag(tag).assertIsDisplayed()
            }
            rule.onNodeWithText("Your phone's night shift").assert(isHeading()).assertIsDisplayed()
            rule.onNodeWithTag(barTag(case)).assertIsDisplayed()
        }
    }

    /**
     * The heading and the action at the end of the brand's line never run into each other: at the
     * normal font they share the line with a clear gap, and when the line has no room for both
     * (a larger font, the longer label) the heading has a line of its own under it.
     */
    private fun assertBrandRowClear(what: String) {
        val heading = rule.onNodeWithText("Your phone's night shift").getUnclippedBoundsInRoot()
        val name = rule.onNodeWithText("HEADS DOWN").getUnclippedBoundsInRoot()
        val action = bounds(HomeTags.SETUP)
        for ((label, box) in listOf("the heading" to heading, "the name" to name)) {
            val beside = box.top < action.bottom && action.top < box.bottom
            assertTrue("$what: $label ($box) is clear of the action ($action)", !beside || box.right + 16.dp <= action.left + 0.5.dp)
            assertTrue("$what: $label is inside the margins: $box", box.left >= 20.dp && box.right <= 340.dp)
        }
        assertTrue("$what: the action is inside the margins and a finger tall: $action", action.left >= 20.dp && action.right <= 340.dp && action.height >= 48.dp)
        assertTrue("$what: the name is over the heading", name.bottom <= heading.top + 0.5.dp)
        rule.onNodeWithTag(HomeTags.SETUP).assertIsDisplayed()
    }

    @Test fun `the brand's heading never runs into Setup, at any font scale`() {
        atEveryScale(listOf(Screens.idle)) { _, scale ->
            rule.onNodeWithTag(HomeTags.SETUP).assertTextEquals("Setup")
            assertBrandRowClear("\"Setup\" at $scale")
        }
    }

    @Test fun `nor into the longer Finish setup`() {
        // In every state: the longer label makes the band taller at a large font, and the rig's
        // state must still be whole above the dock (a running shift's two bars are the tightest).
        atEveryScale(Screens.homeCases, onboarding = OnboardingState(introSeen = true)) { case, scale ->
            // Until the phone's settings are read nothing says setup is unfinished.
            rule.onNodeWithTag(HomeTags.SETUP).assertTextEquals(if (case.loading) "Setup" else "Finish setup")
            assertBrandRowClear("${case.name}, \"Finish setup\" at $scale")
            assertFirstScreenful(case, scale, bars = true)
            assertAmountsWhole()
        }
    }

    @Test fun `at the normal font the heading sits under the name, beside either label`() {
        var onboarding by mutableStateOf(Screens.setUp)
        show(bars = true) { Screens.Home(Screens.idle, onboarding = onboarding) }
        for (state in listOf(Screens.setUp, OnboardingState(introSeen = true))) {
            onboarding = state
            rule.waitForIdle()
            val heading = rule.onNodeWithText("Your phone's night shift").getUnclippedBoundsInRoot()
            val name = rule.onNodeWithText("HEADS DOWN").getUnclippedBoundsInRoot()
            val action = bounds(HomeTags.SETUP)
            assertTrue("one line: $name over $heading, beside $action", heading.left == name.left && heading.top < action.bottom && heading.bottom > action.top)
            assertBrandRowClear(if (state.allDone) "Setup" else "Finish setup")
        }
    }

    // ------------------------------------------------------------------ the page under it

    @Test fun `at the normal font the four Focus Bond choices share one row, each a finger wide`() {
        var chosen: ULong? = null
        show {
            HomeScreen(
                snapshot = Screens.idle.snapshot, onboarding = Screens.setUp, health = Screens.idle.health,
                onClockIn = {}, onEndShift = {}, onFreeze = {}, onOpenSetup = {},
                bondSkr = 10_000_000uL, onBondChange = { chosen = it },
            )
        }
        val labels = listOf("Off", "10 SKR", "50 SKR", "100 SKR")
        val chips = labels.map { label ->
            rule.onNodeWithTag(HomeTags.BOND_CHOICE + label).performScrollTo()
            bounds(HomeTags.BOND_CHOICE + label).also { chip ->
                val text = rule.onNodeWithText(label, useUnmergedTree = true).getUnclippedBoundsInRoot()
                assertTrue("$label is whole inside its chip: $text in $chip", text.left >= chip.left + 6.dp && text.right <= chip.right - 6.dp)
                assertTrue("$label is 48dp both ways at least: $chip", chip.height >= 48.dp && chip.width >= 48.dp && chip.height < 64.dp)
            }
        }
        assertEquals("one row", 1, chips.map { it.top }.toSet().size)
        assertTrue("from margin to margin", chips.first().left == 20.dp && chips.last().right <= 340.dp)
        // One of a group: selected by semantics, not by a description.
        rule.onNodeWithTag(HomeTags.BOND_CHOICE + "10 SKR").assertIsSelected().assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
        rule.onNodeWithTag(HomeTags.BOND_CHOICE + "Off").assertIsNotSelected().performClick()
        assertEquals(0uL, chosen)
    }

    @Test fun `the hero is one image with one sentence and no text`() {
        val case = Screens.cooling
        show { Screens.Home(case) }
        val sentence = HomeCopy.hero(case.snapshot.state)
        val hero = rule.onNodeWithTag(HomeTags.HERO).fetchSemanticsNode()
        assertEquals(listOf(sentence), hero.config.getOrNull(SemanticsProperties.ContentDescription))
        assertEquals(Role.Image, hero.config.getOrNull(SemanticsProperties.Role))
        // Nothing else: no text, no action, and nothing beneath it.
        assertEquals(null, hero.config.getOrNull(SemanticsProperties.Text))
        assertEquals(null, hero.config.getOrNull(SemanticsActions.OnClick))
        fun texts(node: SemanticsNode): List<String> =
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } + node.children.flatMap(::texts)
        val unmerged = rule.onNodeWithTag(HomeTags.HERO, useUnmergedTree = true).fetchSemanticsNode()
        assertEquals(emptyList<String>(), texts(unmerged))
        assertEquals(emptyList<SemanticsNode>(), unmerged.children)
        assertEquals("one node says it", 1, rule.onAllNodes(hasContentDescription(sentence)).fetchSemanticsNodes().size)
        // The slab is drawn between the brand row and the "Powered by ORE" line, not under them.
        val heroBox = bounds(HomeTags.HERO)
        val heading = rule.onNodeWithText("Your phone's night shift").getUnclippedBoundsInRoot()
        val ore = rule.onNodeWithText("Powered by ORE").getUnclippedBoundsInRoot()
        assertTrue("$heading, $heroBox, $ore", heading.bottom <= heroBox.top && heroBox.bottom <= ore.top && heroBox.width == 360.dp)
        assertTrue("the hero has room: $heroBox", heroBox.height >= 120.dp)
    }

    @Test fun `the rig word is a polite live region and the dock's line is the only thing that changes with the light`() {
        show { Screens.Home(Screens.armed) }
        rule.onNodeWithTag(HomeTags.RIG_WORD).assertTextEquals("Armed")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion))
        rule.onNodeWithText("Heat 1 of 5", useUnmergedTree = true).assertDoesNotExist()
        rule.onNode(hasContentDescription("Heat 1 of 5")).assertExists()
        // "Powered by ORE" is one line of the first screenful, written as it always was.
        rule.onAllNodesWithText("Powered by ORE").fetchSemanticsNodes().let { assertEquals(1, it.size) }
        rule.onNodeWithText("Powered by ORE").assertIsDisplayed()
    }

    @Test fun `the next section's name shows above the dock when the first screenful has room for it`() {
        show { Screens.Home(Screens.idle) }
        val dock = bounds(HomeTags.DOCK)
        val limits = rule.onNodeWithText(HomeCopy.LIMITS).assert(isHeading()).getUnclippedBoundsInRoot()
        assertTrue("whole, just above the dock: $limits, dock at ${dock.top}", limits.bottom <= dock.top && dock.top - limits.bottom <= 12.dp)
        // And nothing of the section's body: that starts under the fold.
        assertTrue(bounds(HomeTags.CLOCK_IN_AMOUNTS).top >= dock.top)
    }

    @Test fun `setup is called unfinished only once the phone's settings were read and say so`() {
        var loading by mutableStateOf(true)
        show {
            HomeScreen(
                snapshot = Screens.idle.snapshot, onboarding = OnboardingState(introSeen = true), health = Screens.idle.health,
                onClockIn = {}, onEndShift = {}, onFreeze = {}, onOpenSetup = {}, loading = loading,
            )
        }
        // Not known yet: no "Finish setup" flashing by for someone whose rig is set up.
        rule.onNodeWithTag(HomeTags.SETUP).assertTextEquals("Setup")
        loading = false
        rule.waitForIdle()
        rule.onNodeWithTag(HomeTags.SETUP).assertTextEquals("Finish setup").assertIsDisplayed()
    }

    @Test fun `until the first refresh the shift window is two still lines, and its numerals keep their place`() {
        show { Screens.Home(Screens.loading) }
        rule.onAllNodesWithTag(HomeTags.SHIFT_WINDOW).fetchSemanticsNodes().let { assertEquals(0, it.size) }
        val waiting = bounds(HomeTags.ROUNDS_DARK)
        assertFirstScreenful(Screens.loading, 1f)
        assertTrue("Rounds dark is already in the right-hand column: $waiting", waiting.left > 180.dp)
        rule.onNodeWithText(HomeCopy.RUNS_UNTIL, useUnmergedTree = true).assertExists()
    }
}
