package xyz.headsdown.core.design.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpRect
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
import xyz.headsdown.core.design.Hd
import xyz.headsdown.core.design.HdDimens
import xyz.headsdown.core.design.HeadsDownTheme

/**
 * The largest system font on a small phone: a 360dp screen at font scale 2.0, measured with real
 * fonts (Robolectric's native graphics). A control grows before it loses a word of its label, and
 * a number is never cut short without saying so.
 *
 * The font scale is set through `LocalDensity`, as the system sets it. From 1.03 up Compose scales
 * text the way Android 14 does: small sizes by the whole factor, large ones by less (at 2.0, 16sp
 * is drawn 28dp and 100sp and above not enlarged at all). Past 2.0 there is no table and the
 * factor applies to every size.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h800dp-xhdpi")
class LargeTextTest {
    @get:Rule val rule = createComposeRule()

    private var fontScale by mutableFloatStateOf(1f)

    /** A 360dp screen with the 20dp margins, at [fontScale]. */
    private fun setScreen(content: @Composable () -> Unit) {
        rule.setContent {
            val device = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(device.density, fontScale)) {
                HeadsDownTheme {
                    Column(Modifier.width(360.dp).padding(horizontal = HdDimens.Margin)) { content() }
                }
            }
        }
    }

    private fun layoutOf(text: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        val node = rule.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode()
        checkNotNull(node.config[SemanticsActions.GetTextLayoutResult].action).invoke(results)
        return results.single()
    }

    private fun bounds(tag: String): DpRect = rule.onNodeWithTag(tag).getUnclippedBoundsInRoot()

    private fun textBounds(text: String): DpRect = rule.onNodeWithText(text, useUnmergedTree = true).getUnclippedBoundsInRoot()

    /** The height of the first line: for one face it is in proportion to the size the text was drawn at. */
    private fun lineHeightPx(text: String): Float = layoutOf(text).let { it.getLineBottom(0) - it.getLineTop(0) }

    /**
     * Every character of [label] is laid out, no line is cut or ends in an ellipsis, no line is
     * wider than the width the text was offered, and the text's box is inside [inside].
     *
     * (The layout a plain-string text reports here is re-made at the full width it was offered,
     * so its lines are compared with that width and not with the box, which hugs the widest line.)
     */
    private fun assertWholeLabelIsDrawn(label: String, inside: DpRect) {
        val layout = layoutOf(label)
        val lines = 0 until layout.lineCount
        val offered = layout.layoutInput.constraints.maxWidth
        assertFalse("\"$label\" is taller than its box", layout.didOverflowHeight)
        assertFalse("\"$label\" ends in an ellipsis", lines.any(layout::isLineEllipsized))
        assertEquals("\"$label\": every character is laid out", label.length, layout.getLineEnd(layout.lineCount - 1, visibleEnd = false))
        for (line in lines) {
            val width = layout.getLineRight(line) - layout.getLineLeft(line)
            assertTrue("\"$label\" line $line is $width wide where $offered was offered", width <= offered + 0.5f)
        }
        val text = textBounds(label)
        assertTrue("\"$label\" is inside its control: $text in $inside", text.left >= inside.left && text.right <= inside.right && text.top >= inside.top && text.bottom <= inside.bottom)
    }

    // The labels the screens use today, the longest of each kind.
    private val bars = listOf(
        "Clock out: seal the shift, see your ORE" to BarButtonStyle.Primary,
        "Take SOL back or close the rig" to BarButtonStyle.Secondary,
        "Unfreeze and clock in" to BarButtonStyle.Danger,
    )
    private val waiting = "Waiting for your wallet…"

    @Test
    fun `at font scale 2 on a 360dp screen a bar grows, and no word of its label is cut or ellipsized`() {
        setScreen {
            for ((label, style) in bars) BarButton(label, onClick = {}, modifier = Modifier.fillMaxWidth().testTag(label), style = style)
            BarButton(waiting, onClick = {}, modifier = Modifier.fillMaxWidth().testTag(waiting), busy = true)
            BarButton("Clock in", onClick = {}, modifier = Modifier.testTag("short"), seam = true)
        }
        val labels = bars.map { it.first } + waiting
        for (label in labels) {
            assertWholeLabelIsDrawn(label, bounds(label))
            assertEquals("\"$label\" is one line at the normal size", 1, layoutOf(label).lineCount)
            assertEquals("a one-line bar is 56dp", HdDimens.Bar.value, bounds(label).height.value, 0.5f)
        }
        val normalLine = lineHeightPx(waiting)

        fontScale = 2f
        rule.waitForIdle()
        for (label in labels) {
            val bar = bounds(label)
            val layout = layoutOf(label)
            assertEquals(2f, layout.layoutInput.density.fontScale, 0f)
            // The label is not capped: it is drawn larger, as the person asked.
            assertTrue("\"$label\" is drawn larger", lineHeightPx(label) >= normalLine * 1.5f)
            assertWholeLabelIsDrawn(label, bar)
            assertEquals("the bar keeps the width it was given", 320.dp.value, bar.width.value, 0.5f)
            // As tall as its label needs with 12dp above and below, and never under 56dp.
            assertTrue("\"$label\": the bar is ${bar.height}", bar.height >= HdDimens.Bar && bar.height.value >= textBounds(label).height.value + 23.5f)
            if (layout.lineCount >= 2) assertTrue("\"$label\": the bar grew to ${bar.height}", bar.height > HdDimens.Bar)
        }
        // The longest label no longer fits on a line: it wraps, and its bar grows.
        val longest = bars.first().first
        assertTrue("\"$longest\" wraps at this size", layoutOf(longest).lineCount >= 2)
        assertTrue(bounds(longest).height > HdDimens.Bar)
        // A short label still fits on one line, in a bar that is still at least 56dp by 48dp.
        assertEquals(1, layoutOf("Clock in").lineCount)
        assertWholeLabelIsDrawn("Clock in", bounds("short"))
        assertTrue(bounds("short").height >= HdDimens.Bar && bounds("short").width >= HdDimens.Touch)
    }

    @Test
    fun `a choice, a link row and a text action wrap at font scale 2 instead of losing their label`() {
        val choice = "End the shift now and claim all to my wallet"
        val link = "How the night shift works, step by step"
        val action = "See a sample night (not your data)"
        fontScale = 2f
        setScreen {
            Choice(choice, selected = true, onClick = {}, modifier = Modifier.testTag(choice), role = Role.Checkbox)
            LinkRow(link, onClick = {}, modifier = Modifier.testTag(link))
            TextAction(action, onClick = {}, modifier = Modifier.testTag(action))
        }
        for (label in listOf(choice, link, action)) {
            assertTrue("\"$label\" wraps", layoutOf(label).lineCount >= 2)
            assertWholeLabelIsDrawn(label, bounds(label))
            assertTrue(bounds(label).width <= 320.dp)
        }
    }

    @Test
    fun `a stat row with a long value keeps its label readable`() {
        // An address in the mono face is wider than the row. The label must not be squeezed away.
        val address = "7xKXtg2CW87d97TXJSDpbD5jBkheTqA83TZRuJosgAsU"
        setScreen {
            StatRow("Authority", address, Modifier.testTag("long"))
            StatRow("Rounds dark", "55", Modifier.testTag("short"))
        }
        assertEquals("the label is on one line, not a column of letters", 1, layoutOf("Authority").lineCount)
        assertWholeLabelIsDrawn("Authority", bounds("long"))
        assertWholeLabelIsDrawn(address, bounds("long"))
        assertTrue("the address is on its own line, under the label", textBounds(address).top >= textBounds("Authority").bottom)
        // A short value still shares the line with its label, flush right.
        assertTrue(textBounds("55").top < textBounds("Rounds dark").bottom)
        assertEquals(340.dp.value, textBounds("55").right.value, 0.5f)
        rule.onNodeWithTag("long").assertTextEquals("Authority", address)
    }

    @Test
    fun `one line of body, figure or label text is as tall as its line height`() {
        setScreen {
            BasicText("One line of body", style = Hd.type.body)
            BasicText("0.00130048 SOL", style = Hd.type.figure)
            BasicText("A heading", style = Hd.type.headline)
            BasicText("Clock in", style = Hd.type.button)
            BasicText("POWERED BY ORE", style = Hd.type.label)
            BasicText("of 2,000,000", style = Hd.type.caption)
        }
        // 16/24, 16/24, 19/24, 16/20, 13/18 and 12/16: the first and the last line are not trimmed
        // to the glyphs, so a block of text is a whole number of lines.
        val expected = mapOf(
            "One line of body" to 24.dp,
            "0.00130048 SOL" to 24.dp,
            "A heading" to 24.dp,
            "Clock in" to 20.dp,
            "POWERED BY ORE" to 18.dp,
            "of 2,000,000" to 16.dp,
        )
        for ((text, height) in expected) assertEquals("\"$text\"", height.value, textBounds(text).height.value, 0.5f)
    }

    @Test
    fun `hero numerals shrink to fit one line, never grow past 1_3x, and never lose a digit silently`() {
        val short = "55"
        val fits = "0.00020001"
        val tooLong = "1,234,567.00020001 ORE of 2,000,000"
        setScreen {
            Box(Modifier.testTag("short-box")) { HeroNumerals(short) }
            Box(Modifier.testTag("fits-box")) { HeroNumerals(fits) }
            Box(Modifier.testTag("long-box")) { HeroNumerals(tooLong, Modifier.testTag("long")) }
        }
        // At the normal scale: the hero size when it fits, a smaller one when it must, one line always.
        val hero = lineHeightPx(short)
        val smallest = lineHeightPx(tooLong)
        assertEquals("the smallest size is half the hero size (56 of 112)", 0.5f, smallest / hero, 0.02f)
        assertTrue("shrunk to fit: ${lineHeightPx(fits)} between $smallest and $hero", lineHeightPx(fits) < hero && lineHeightPx(fits) >= smallest)
        for (value in listOf(short, fits)) {
            val layout = layoutOf(value)
            assertEquals(1, layout.lineCount)
            assertFalse("\"$value\" is whole", layout.isLineEllipsized(0))
            assertTrue("\"$value\" is inside its line", layout.getLineRight(0) <= layout.size.width + 0.5f)
            assertTrue(textBounds(value).width <= 320.dp)
        }
        // A value that cannot fit even at the smallest size says so with an ellipsis: a number cut
        // cleanly between two digits would read as another number.
        assertEquals(1, layoutOf(tooLong).lineCount)
        assertTrue("the cut is shown", layoutOf(tooLong).isLineEllipsized(0))
        assertTrue(textBounds(tooLong).width <= 320.dp)
        // A screen reader still gets the whole value.
        rule.onNodeWithTag("long").assertTextEquals(tooLong)

        // Up to font scale 2 the platform itself no longer enlarges type this big: the number
        // stays exactly the size it was, and is never drawn smaller for a larger system font.
        for (scale in listOf(1.15f, 1.5f, 2f)) {
            fontScale = scale
            rule.waitForIdle()
            assertEquals("at $scale the hero keeps its size", hero, lineHeightPx(short), 1f)
            assertTrue("at $scale: ${lineHeightPx(tooLong)} against $smallest", lineHeightPx(tooLong) in (smallest - 1f)..(smallest * 1.3f + 1f))
            assertTrue(layoutOf(tooLong).isLineEllipsized(0))
        }

        // Past 2.0 the scale applies to every size, and here the cap is what holds the number:
        // 1.3 times the hero size, not 3 times. The scale changes under a composed screen, as it
        // does for an activity that handles the configuration change itself.
        fontScale = 3f
        rule.waitForIdle()
        val capped = lineHeightPx(short) / hero
        assertEquals("at 3.0 the hero is $capped times its size", 1.3f, capped, 0.01f)
        assertEquals(1.3f, lineHeightPx(tooLong) / smallest, 0.02f)
        assertEquals(1, layoutOf(short).lineCount)

        // And back: the range follows the scale down again.
        fontScale = 1f
        rule.waitForIdle()
        assertEquals(hero, lineHeightPx(short), 1f)
    }

    @Test
    fun `display text stops growing at 1_3x where body text keeps growing`() {
        fontScale = 3f
        setScreen {
            DisplayText("Rig hot", Modifier.testTag("display"))
            BasicText("Signing a heartbeat", Modifier.testTag("body"), style = Hd.type.body)
        }
        assertEquals("body text is never capped", 3f, layoutOf("Signing a heartbeat").layoutInput.density.fontScale, 0f)
        assertTrue("16sp body text is drawn 48dp: ${bounds("body").height}", bounds("body").height >= 60.dp)
        // 56sp at 1.3 is no more than 72.8dp of type, a line under 100dp; uncapped at 3.0 it would
        // be 168dp of type.
        assertTrue("the display face is held at 1.3: ${bounds("display").height}", bounds("display").height < 100.dp)
        rule.onNodeWithTag("display").assertTextEquals("Rig hot")
    }
}
