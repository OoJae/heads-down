package xyz.headsdown.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.headsdown.core.design.HdArgb
import xyz.headsdown.core.design.HdCompat
import xyz.headsdown.core.design.HdFonts
import xyz.headsdown.core.design.HdPalette
import xyz.headsdown.core.design.HdType
import xyz.headsdown.feature.reveal.ui.RevealColors
import xyz.headsdown.surface.notification.ShiftNotificationFactory
import xyz.headsdown.surface.widget.WidgetPalette
import xyz.headsdown.ui.theme.HdColors
import xyz.headsdown.ui.theme.PixelLabel
import kotlin.math.pow

/** Accessible contrast for the Heads Down look, and one palette across app, widget and reveal. */
class ThemeContrastTest {

    private fun luminance(c: Color): Double {
        fun ch(v: Float) = if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        return 0.2126 * ch(c.red) + 0.7152 * ch(c.green) + 0.0722 * ch(c.blue)
    }

    private fun contrast(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private fun assertAa(fg: Color, bg: Color, name: String) {
        val r = contrast(fg, bg)
        assertTrue("$name: %.2f:1".format(r), r >= 4.5)
    }

    @Test
    fun `every text colour meets WCAG AA on charcoal surfaces`() {
        val texts = mapOf(
            "Ash" to HdColors.Ash, "AshMuted" to HdColors.AshMuted, "Ember" to HdColors.Ember,
            "OreGold" to HdColors.OreGold, "Cooling" to HdColors.Cooling, "Frost" to HdColors.Frost,
        )
        listOf(HdColors.Charcoal, HdColors.CharcoalRaised).forEach { bg ->
            texts.forEach { (name, fg) -> assertAa(fg, bg, name) }
        }
    }

    @Test
    fun `button text meets WCAG AA on ember and gold`() {
        assertAa(HdColors.Charcoal, HdColors.Ember, "Clock in")
        assertAa(HdColors.Charcoal, HdColors.OreGold, "Buy the rest")
    }

    @Test
    fun `widget and reveal palettes mirror the app`() {
        fun argb(c: Color) = c.toArgb()
        assertEquals(argb(HdColors.Charcoal), WidgetPalette.CHARCOAL.toInt())
        assertEquals(argb(HdColors.CharcoalRaised), WidgetPalette.CHARCOAL_RAISED.toInt())
        assertEquals(argb(HdColors.CharcoalOutline), WidgetPalette.CHARCOAL_OUTLINE.toInt())
        assertEquals(argb(HdColors.Ember), WidgetPalette.EMBER.toInt())
        assertEquals(argb(HdColors.OreGold), WidgetPalette.ORE_GOLD.toInt())
        assertEquals(argb(HdColors.Ash), WidgetPalette.ASH.toInt())
        assertEquals(argb(HdColors.AshMuted), WidgetPalette.ASH_MUTED.toInt())
        assertEquals(argb(HdColors.Frost), WidgetPalette.FROST.toInt())
        assertEquals(argb(HdColors.Cooling), WidgetPalette.COOLING.toInt())

        assertEquals(HdColors.Charcoal, RevealColors.Charcoal)
        assertEquals(HdColors.CharcoalRaised, RevealColors.CharcoalRaised)
        assertEquals(HdColors.CharcoalOutline, RevealColors.CharcoalOutline)
        assertEquals(HdColors.Ember, RevealColors.Ember)
        assertEquals(HdColors.OreGold, RevealColors.OreGold)
        assertEquals(HdColors.Ash, RevealColors.Ash)
        assertEquals(HdColors.AshMuted, RevealColors.AshMuted)
        assertEquals(HdColors.Cooling, RevealColors.Cooling)
    }

    @Test
    fun `the old names hold the design module's palette, and nothing of their own`() {
        val underside = HdPalette.Dark
        assertEquals(underside.pit, HdColors.Charcoal)
        assertEquals(underside.slab, HdColors.CharcoalRaised)
        assertEquals(underside.ember, HdColors.Ember)
        assertEquals(underside.seam, HdColors.OreGold)
        assertEquals(underside.chalk, HdColors.Ash)
        assertEquals(underside.ash, HdColors.AshMuted)
        // The outline the old layouts draw opaque is the hairline as it looks on the pit.
        assertEquals(underside.hairline.compositeOver(underside.pit).toArgb(), HdColors.CharcoalOutline.toArgb())
        assertEquals(HdArgb.HAIRLINE_ON_PIT, HdColors.CharcoalOutline.toArgb())
        // What the new system has no role for is still named, from the one place that may hold it.
        assertEquals(HdCompat.FROST, HdColors.Frost.toArgb())
        assertEquals(HdCompat.COOLING, HdColors.Cooling.toArgb())
        assertEquals(HdCompat.EMBER_DIM, HdColors.EmberDim.toArgb())
        // The share image and the notification draw with the same integers.
        assertEquals(HdArgb.EMBER, ShiftNotificationFactory.EMBER)
        assertEquals(HdColors.Ember.toArgb(), ShiftNotificationFactory.EMBER)
    }

    @Test
    fun `labels are set in the mono face`() {
        assertEquals(HdFonts.Mono, PixelLabel.fontFamily)
        assertEquals(HdType.Default.label, PixelLabel)
    }

    @Test
    fun `ink on the old filled buttons is still the pit, also on the compatibility colours`() {
        // "Unfreeze and clock in" is pit on Frost; the sample badge is pit on Cooling.
        assertAa(HdColors.Charcoal, HdColors.Frost, "Unfreeze and clock in")
        assertAa(HdColors.Charcoal, HdColors.Cooling, "Sample badge")
        // An Onboarding button with Material's defaults is pit on chalk.
        assertAa(HdColors.Charcoal, HdColors.Ash, "Allow notifications")
    }
}
