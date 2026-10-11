package xyz.headsdown.core.design.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.headsdown.core.design.HeadsDownTheme

/**
 * A compact choice that is given a width of its own, measured with the real font: four of them
 * share the 320dp a 360dp phone leaves between its margins. A chip keeps its whole label and
 * gives up side padding instead; with room to spare it keeps the 16dp it always had.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h800dp-xhdpi")
class CompactChoiceTest {
    @get:Rule val rule = createComposeRule()

    private val labels = listOf("Off", "10 SKR", "50 SKR", "100 SKR")

    private fun layoutOf(text: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        val node = rule.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode()
        checkNotNull(node.config[SemanticsActions.GetTextLayoutResult].action).invoke(results)
        return results.single()
    }

    @Test
    fun `four chips sharing a 320dp row keep every letter and give up padding`() {
        rule.setContent {
            HeadsDownTheme {
                Row(Modifier.width(320.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (label in labels) {
                        Choice(label, selected = label == "10 SKR", onClick = {}, modifier = Modifier.weight(label.length.coerceAtLeast(4).toFloat()).testTag(label), compact = true)
                    }
                }
            }
        }
        var squeezed = 0
        for (label in labels) {
            val chip = rule.onNodeWithTag(label).getUnclippedBoundsInRoot()
            val text = rule.onNodeWithText(label, useUnmergedTree = true).getUnclippedBoundsInRoot()
            val layout = layoutOf(label)
            assertEquals("$label is one line", 1, layout.lineCount)
            assertEquals("$label: every character is laid out", label.length, layout.getLineEnd(0, visibleEnd = true))
            assertFalse("$label ends in an ellipsis", layout.isLineEllipsized(0))
            // (The layout a text reports here is re-made at the width it was offered, so the line
            // is compared with the text's own box: a line wider than its box is a cut label.)
            val line = with(rule.density) { (layout.getLineRight(0) - layout.getLineLeft(0)).toDp() }
            assertTrue("$label is cut: a line of $line in a box of ${text.width}", line <= text.width + 0.5.dp)
            val side = text.left - chip.left
            assertTrue("$label keeps at least 6dp on each side: $text in $chip", side >= 6.dp && chip.right - text.right >= 6.dp)
            assertTrue("$label is centred: $text in $chip", kotlin.math.abs((side - (chip.right - text.right)).value) <= 1f)
            assertTrue("$label is a finger wide and 48dp tall: $chip", chip.width >= 48.dp && chip.height.value == 48f)
            // The label with 16dp on each side would not have fitted this chip: it gave up some.
            if (text.width + 32.dp > chip.width) {
                squeezed++
                assertTrue("$label gave up padding, not letters: $side", side < 16.dp)
            }
        }
        assertTrue("at least one chip had to give up padding, or this test checks nothing", squeezed >= 1)
    }

    @Test
    fun `a chip with room keeps 16dp on each side, at the largest font too`() {
        rule.setContent {
            val device = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(device.density, 2f)) {
                HeadsDownTheme {
                    Column { for (label in labels) Choice(label, selected = false, onClick = {}, modifier = Modifier.testTag(label), compact = true) }
                }
            }
        }
        for (label in labels) {
            val chip = rule.onNodeWithTag(label).getUnclippedBoundsInRoot()
            val text = rule.onNodeWithText(label, useUnmergedTree = true).getUnclippedBoundsInRoot()
            assertEquals("$label: 16dp before", 16f, (text.left - chip.left).value, 0.5f)
            assertEquals("$label: 16dp after", 16f, (chip.right - text.right).value, 0.5f)
            assertTrue("$label: a finger wide", chip.width >= 48.dp)
        }
    }

    @Test
    fun `a chip narrower than a finger is still 48dp, its label in the middle`() {
        rule.setContent { HeadsDownTheme { Column { Choice("0", selected = true, onClick = {}, modifier = Modifier.testTag("chip"), compact = true) } } }
        val chip = rule.onNodeWithTag("chip").getUnclippedBoundsInRoot()
        val text = rule.onNodeWithText("0", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertEquals(48f, chip.width.value, 0.5f)
        assertEquals((text.left - chip.left).value, (chip.right - text.right).value, 1f)
        assertTrue("more than the 16dp it asks for, to fill the finger's width", text.left - chip.left > 16.dp)
    }
}
