package xyz.headsdown.core.design.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import xyz.headsdown.core.design.HeadsDownTheme

/** What a screen reader and a test are told by the controls: a role, a state, and exactly one text. */
@RunWith(RobolectricTestRunner::class)
class ControlsTest {
    @get:Rule val rule = createComposeRule()

    private fun role(expected: Role) = SemanticsMatcher.expectValue(SemanticsProperties.Role, expected)

    /** The texts of every node under (and including) this one, unmerged: what is really composed. */
    private fun SemanticsNodeInteraction.textsBeneath(): List<String> {
        val out = mutableListOf<String>()
        fun walk(n: SemanticsNode) {
            n.config.getOrNull(SemanticsProperties.Text)?.forEach { out += it.text }
            n.children.forEach(::walk)
        }
        walk(fetchSemanticsNode())
        return out
    }

    // ------------------------------------------------------------------ BarButton

    @Test
    fun `a bar is a button whose text is exactly its label, in every style`() {
        val clicks = mutableListOf<BarButtonStyle>()
        rule.setContent {
            HeadsDownTheme {
                Column {
                    for (style in BarButtonStyle.entries) {
                        BarButton("Clock out", onClick = { clicks += style }, modifier = Modifier.testTag(style.name), style = style)
                    }
                }
            }
        }
        for (style in BarButtonStyle.entries) {
            val bar = rule.onNodeWithTag(style.name)
            bar.assert(role(Role.Button)).assertIsEnabled().assertHasClickAction().assertTextEquals("Clock out")
            assertEquals(listOf("Clock out"), rule.onNodeWithTag(style.name, useUnmergedTree = true).textsBeneath())
            assertTrue(bar.getUnclippedBoundsInRoot().height >= 56.dp)
            bar.performClick()
        }
        assertEquals(BarButtonStyle.entries.toList(), clicks)
    }

    @Test
    fun `a disabled bar says so and ignores taps`() {
        var clicks = 0
        rule.setContent { HeadsDownTheme { BarButton("Clock out", onClick = { clicks++ }, modifier = Modifier.testTag("bar"), enabled = false) } }
        rule.onNodeWithTag("bar").assert(role(Role.Button)).assertIsNotEnabled().assertTextEquals("Clock out").performClick()
        assertEquals(0, clicks)
    }

    @Test
    fun `a busy bar keeps its label, is not enabled, and ignores taps until it is done`() {
        var clicks = 0
        var busy by mutableStateOf(true)
        rule.setContent {
            HeadsDownTheme {
                BarButton(
                    label = if (busy) "Waiting for your wallet…" else "Clock out",
                    onClick = { clicks++ },
                    modifier = Modifier.testTag("bar"),
                    seam = true,
                    busy = busy,
                )
            }
        }
        // What ClockOutScreenTest asserts of today's button, word for word.
        rule.onNodeWithTag("bar").assert(role(Role.Button)).assertIsNotEnabled().assertTextEquals("Waiting for your wallet…").performClick()
        assertEquals(0, clicks)
        busy = false
        rule.waitForIdle()
        rule.onNodeWithTag("bar").assertIsEnabled().assertTextEquals("Clock out").performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun `a long label makes the bar taller, on as many lines as it needs`() {
        val long = "End the shift and clock out, and claim all the ORE in my Miner to my wallet"
        rule.setContent {
            HeadsDownTheme {
                Column(Modifier.width(200.dp)) {
                    BarButton("Clock out", onClick = {}, modifier = Modifier.testTag("short"))
                    BarButton(long, onClick = {}, modifier = Modifier.testTag("long"))
                }
            }
        }
        rule.onNodeWithTag("long").assertTextEquals(long)
        assertTrue(rule.onNodeWithTag("long").getUnclippedBoundsInRoot().width <= 200.dp)
        assertTrue(rule.onNodeWithTag("short").getUnclippedBoundsInRoot().height >= 56.dp)
    }

    // ------------------------------------------------------------------ Choice

    @Test
    fun `a choice is selectable - selected, role, enabled and one text`() {
        val taps = mutableListOf<String>()
        var claim by mutableStateOf(false)
        rule.setContent {
            HeadsDownTheme {
                Column {
                    Choice("Keep it in my Miner", selected = !claim, onClick = { taps += "keep"; claim = false }, modifier = Modifier.testTag("keep"))
                    Choice("Claim all to my wallet", selected = claim, onClick = { taps += "claim"; claim = true }, modifier = Modifier.testTag("claim"))
                    Choice("End the shift now", selected = false, onClick = { taps += "end" }, modifier = Modifier.testTag("end"), role = Role.Checkbox)
                    Choice("Close the rig", selected = true, onClick = { taps += "close" }, modifier = Modifier.testTag("off"), enabled = false, role = Role.Checkbox)
                }
            }
        }
        rule.onNodeWithTag("keep").assert(role(Role.RadioButton)).assertIsSelected().assertIsEnabled().assertTextEquals("Keep it in my Miner")
        rule.onNodeWithTag("claim").assert(role(Role.RadioButton)).assertIsNotSelected().assertTextEquals("Claim all to my wallet")
        rule.onNodeWithTag("end").assert(role(Role.Checkbox)).assertIsNotSelected().assertTextEquals("End the shift now")
        rule.onNodeWithTag("off").assert(role(Role.Checkbox)).assertIsSelected().assertIsNotEnabled().assertTextEquals("Close the rig")
        for (tag in listOf("keep", "claim", "end", "off")) {
            assertEquals(1, rule.onNodeWithTag(tag, useUnmergedTree = true).textsBeneath().size)
            assertTrue(rule.onNodeWithTag(tag).getUnclippedBoundsInRoot().height >= 48.dp)
        }

        rule.onNodeWithTag("claim").performClick()
        rule.onNodeWithTag("claim").assertIsSelected()
        rule.onNodeWithTag("keep").assertIsNotSelected()
        // A tap on the option already chosen still reaches the caller, who may take the choice back.
        rule.onNodeWithTag("claim").performClick()
        rule.onNodeWithTag("off").performClick()
        assertEquals(listOf("claim", "claim"), taps)
    }

    @Test
    fun `a compact choice is a 48dp chip with the same semantics`() {
        var chosen by mutableStateOf("10 SKR")
        val labels = listOf("Off", "10 SKR", "50 SKR", "100 SKR")
        rule.setContent {
            HeadsDownTheme {
                Row {
                    for (label in labels) {
                        Choice(label, selected = label == chosen, onClick = { chosen = label }, modifier = Modifier.testTag(label), compact = true)
                    }
                }
            }
        }
        for (label in labels) {
            val chip = rule.onNodeWithTag(label)
            chip.assert(role(Role.RadioButton)).assertTextEquals(label)
            val bounds = chip.getUnclippedBoundsInRoot()
            assertTrue(bounds.height >= 48.dp && bounds.width >= 48.dp)
        }
        rule.onNodeWithTag("10 SKR").assertIsSelected()
        rule.onNodeWithTag("50 SKR").assertIsNotSelected().performClick()
        rule.onNodeWithTag("50 SKR").assertIsSelected()
        rule.onNodeWithTag("10 SKR").assertIsNotSelected()
    }

    // ------------------------------------------------------------------ LinkRow, TextAction

    @Test
    fun `a link row and a text action are buttons of at least 48dp with exactly one text`() {
        val taps = mutableListOf<String>()
        rule.setContent {
            HeadsDownTheme {
                Column {
                    LinkRow("How the night shift works", onClick = { taps += "row" }, modifier = Modifier.testTag("row"))
                    TextAction("Close", onClick = { taps += "action" }, modifier = Modifier.testTag("action"))
                    TextAction("Try again", onClick = { taps += "off" }, modifier = Modifier.testTag("off"), enabled = false)
                }
            }
        }
        rule.onNodeWithTag("row").assert(role(Role.Button)).assertIsEnabled().assertTextEquals("How the night shift works").performClick()
        rule.onNodeWithTag("action").assert(role(Role.Button)).assertIsEnabled().assertTextEquals("Close").performClick()
        rule.onNodeWithTag("off").assert(role(Role.Button)).assertIsNotEnabled().assertTextEquals("Try again").performClick()
        assertEquals(listOf("row", "action"), taps)
        for (tag in listOf("row", "action", "off")) {
            assertEquals(1, rule.onNodeWithTag(tag, useUnmergedTree = true).textsBeneath().size)
            assertTrue(rule.onNodeWithTag(tag).getUnclippedBoundsInRoot().height >= 48.dp)
        }
        assertTrue(rule.onNodeWithTag("action").getUnclippedBoundsInRoot().width >= 48.dp)
    }

    // ------------------------------------------------------------------ ActionDock, PinnedBarScreen

    @Test
    fun `a button in the dock accepts performScrollTo, and the dock is one traversal group`() {
        var clicks = 0
        rule.setContent {
            HeadsDownTheme {
                ActionDock(Modifier.testTag("dock"), seam = true) {
                    BarButton("Clock in", onClick = { clicks++ }, modifier = Modifier.testTag("clock-in"), seam = true)
                    BarButton("End shift", onClick = { clicks++ }, modifier = Modifier.testTag("end"), style = BarButtonStyle.Secondary)
                    TextAction("Close", onClick = { clicks++ }, modifier = Modifier.testTag("close"))
                }
            }
        }
        // The screen tests were written when these buttons were in the scrolling page.
        for (tag in listOf("clock-in", "end", "close")) rule.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().performClick()
        assertEquals(3, clicks)

        val dock = rule.onNodeWithTag("dock").fetchSemanticsNode().config
        assertEquals(true, dock.getOrNull(SemanticsProperties.IsTraversalGroup))
        assertTrue("an enabled vertical scroll", dock.contains(SemanticsActions.ScrollBy))
        val range = dock[SemanticsProperties.VerticalScrollAxisRange]
        assertEquals("with nothing to scroll", 0f, range.maxValue(), 0f)
    }

    @Test
    fun `a pinned-bar screen scrolls its content and keeps the dock on screen`() {
        rule.setContent {
            HeadsDownTheme {
                PinnedBarScreen(
                    content = { repeat(60) { BarButton("Row $it", onClick = {}, modifier = Modifier.testTag("row-$it")) } },
                    dock = { ActionDock { BarButton("Clock out", onClick = {}, modifier = Modifier.testTag("confirm")) } },
                )
            }
        }
        rule.onNodeWithTag("confirm").assertIsDisplayed()
        rule.onNodeWithTag("row-59").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("confirm").performScrollTo().assertIsDisplayed()
    }
}
