package xyz.headsdown.surface.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.headsdown.core.design.HdArgb
import xyz.headsdown.core.design.HdCompat
import kotlin.math.pow

class WidgetColorsTest {

    @Test
    fun `dynamic colour where available, brand palette as the fallback`() {
        assertEquals(WidgetColorMode.BRAND, WidgetColorPolicy.mode(sdkInt = 30))
        assertEquals(WidgetColorMode.DYNAMIC, WidgetColorPolicy.mode(sdkInt = 31))
        assertEquals(WidgetColorMode.DYNAMIC, WidgetColorPolicy.mode(sdkInt = 36))
        assertEquals(WidgetColorMode.BRAND, WidgetColorPolicy.mode(sdkInt = 36, preferBrand = true))
    }

    @Test
    fun `brand text colours meet WCAG AA on charcoal`() {
        val surfaces = listOf(WidgetPalette.CHARCOAL, WidgetPalette.CHARCOAL_RAISED)
        val texts = listOf(
            WidgetPalette.ASH, WidgetPalette.ASH_MUTED, WidgetPalette.EMBER, WidgetPalette.ORE_GOLD,
            WidgetPalette.COOLING, WidgetPalette.FROST,
        )
        surfaces.forEach { bg -> texts.forEach { fg -> assertAa(fg, bg) } }
    }

    @Test
    fun `day variants meet WCAG AA on light Material surfaces`() {
        // Material 3 baseline light surface, surface-container and pure white.
        val lightSurfaces = listOf(0xFFFEF7FFL, 0xFFF3EDF7L, 0xFFECE6F0L, 0xFFFFFFFFL)
        val accents = listOf(
            WidgetPalette.EMBER_ON_LIGHT, WidgetPalette.ORE_GOLD_ON_LIGHT,
            WidgetPalette.COOLING_ON_LIGHT, WidgetPalette.FROST_ON_LIGHT,
        )
        lightSurfaces.forEach { bg -> accents.forEach { fg -> assertAa(fg, bg) } }
        // And the night variants on Material 3 baseline dark surfaces.
        val darkSurfaces = listOf(0xFF141218L, 0xFF211F26L)
        listOf(WidgetPalette.EMBER, WidgetPalette.ORE_GOLD, WidgetPalette.COOLING, WidgetPalette.FROST)
            .forEach { fg -> darkSurfaces.forEach { bg -> assertAa(fg, bg) } }
    }

    @Test
    fun `the widget palette is the design module's, as unsigned longs`() {
        fun argb(value: Long): Int {
            assertTrue("%X is an opaque ARGB value".format(value), value in 0xFF000000L..0xFFFFFFFFL)
            return value.toInt()
        }
        assertEquals(HdArgb.PIT, argb(WidgetPalette.CHARCOAL))
        assertEquals(HdArgb.SLAB, argb(WidgetPalette.CHARCOAL_RAISED))
        assertEquals(HdArgb.HAIRLINE_ON_PIT, argb(WidgetPalette.CHARCOAL_OUTLINE))
        assertEquals(HdArgb.EMBER, argb(WidgetPalette.EMBER))
        assertEquals(HdArgb.SEAM, argb(WidgetPalette.ORE_GOLD))
        assertEquals(HdArgb.CHALK, argb(WidgetPalette.ASH))
        assertEquals(HdArgb.ASH, argb(WidgetPalette.ASH_MUTED))
        // In day mode a Material You widget takes the light palette's ember and gold.
        assertEquals(HdArgb.Day.EMBER, argb(WidgetPalette.EMBER_ON_LIGHT))
        assertEquals(HdArgb.Day.SEAM, argb(WidgetPalette.ORE_GOLD_ON_LIGHT))
        // And the colours the new system has no role for come from the same module.
        assertEquals(HdCompat.FROST, argb(WidgetPalette.FROST))
        assertEquals(HdCompat.COOLING, argb(WidgetPalette.COOLING))
        assertEquals(HdCompat.FROST_DAY, argb(WidgetPalette.FROST_ON_LIGHT))
        assertEquals(HdCompat.COOLING_DAY, argb(WidgetPalette.COOLING_ON_LIGHT))
    }

    @Test
    fun `the unlit pixel and the empty seat stay quieter than any text`() {
        // The outline is the hairline: decoration, well under 3:1, so it never reads as a state.
        assertTrue(contrast(WidgetPalette.CHARCOAL_OUTLINE, WidgetPalette.CHARCOAL) < 3.0)
        assertTrue(contrast(WidgetPalette.CHARCOAL_OUTLINE, WidgetPalette.CHARCOAL) > 1.0)
    }

    @Test
    fun `heat pixels fill with heat`() {
        assertEquals(0, litPixels(RigHeat.COLD))
        assertEquals(1, litPixels(RigHeat.ARMED))
        assertEquals(3, litPixels(RigHeat.COOLING))
        assertEquals(5, litPixels(RigHeat.HOT))
    }

    @Test
    fun `generated previews publish once per version on Android 15+`() {
        assertFalse(WidgetPreviews.shouldPublish(sdkInt = 34, publishedVersion = null, currentVersion = 1))
        assertTrue(WidgetPreviews.shouldPublish(sdkInt = 35, publishedVersion = null, currentVersion = 1))
        assertFalse(WidgetPreviews.shouldPublish(sdkInt = 35, publishedVersion = 1, currentVersion = 1))
        assertTrue(WidgetPreviews.shouldPublish(sdkInt = 36, publishedVersion = 1, currentVersion = 2))
    }

    private fun assertAa(fg: Long, bg: Long) {
        val ratio = contrast(fg, bg)
        assertTrue("%08X on %08X is %.2f:1".format(fg, bg, ratio), ratio >= 4.5)
    }

    companion object {
        /** WCAG 2.x contrast ratio of two opaque ARGB colours. */
        fun contrast(a: Long, b: Long): Double {
            val la = luminance(a)
            val lb = luminance(b)
            return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
        }

        private fun luminance(argb: Long): Double {
            fun channel(shift: Int): Double {
                val c = ((argb shr shift) and 0xFF) / 255.0
                return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
            }
            return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
        }
    }
}
