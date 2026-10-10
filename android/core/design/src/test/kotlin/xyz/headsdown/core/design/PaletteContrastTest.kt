package xyz.headsdown.core.design

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/** "The Underside" in numbers: every ink on every surface it may sit on, in both palettes. */
class PaletteContrastTest {

    private val palettes = mapOf("dark" to HdPalette.Dark, "light" to HdPalette.Light)

    @Test
    fun `every ink is at least 4_5 to 1 on pit and on slab, in both palettes`() {
        for ((name, p) in palettes) {
            val inks = mapOf("chalk" to p.chalk, "ash" to p.ash, "seam" to p.seam, "ember" to p.ember)
            val surfaces = mapOf("pit" to p.pit, "slab" to p.slab)
            for ((ink, fg) in inks) for ((surface, bg) in surfaces) assertAa(fg, bg, "$name: $ink on $surface")
        }
    }

    @Test
    fun `ink on a fill is pit, and reads on chalk, on ember and on seam in both palettes`() {
        for ((name, p) in palettes) {
            assertEquals(p.pit, p.onChalk)
            assertEquals(p.pit, p.onAccent)
            assertAa(p.onChalk, p.chalk, "$name: a primary bar")
            assertAa(p.onAccent, p.ember, "$name: a danger bar")
            assertAa(p.onAccent, p.seam, "$name: ink on a seam fill")
            // The reason for the rule: chalk on ember does not reach even 3:1.
            assertTrue("$name: chalk on ember", contrast(p.chalk, p.ember) < 3.0)
        }
    }

    @Test
    fun `the gold light is one colour in both palettes, and only the dark pit reads on it`() {
        assertEquals(HdPalette.Dark.seamLight, HdPalette.Light.seamLight)
        assertEquals(Color(HdArgb.SEAM_LIGHT), HdPalette.Light.seamLight)
        for ((name, p) in palettes) assertAa(p.onSeamLight, p.seamLight, "$name: ink on the gold light")
        // In the light palette the gold light is not an ink: that is what seam (darkened) is for.
        assertTrue(contrast(HdPalette.Light.seamLight, HdPalette.Light.pit) < 3.0)
        assertFalse(HdPalette.Light.seam == HdPalette.Light.seamLight)
        assertEquals(HdPalette.Dark.seam, HdPalette.Dark.seamLight)
    }

    @Test
    fun `the outline of a control is ash, which is at least 3 to 1, and the hairline is not`() {
        for ((name, p) in palettes) {
            for (bg in listOf(p.pit, p.slab)) {
                assertTrue("$name: ash as an outline", contrast(p.ash, bg) >= 3.0)
                assertTrue("$name: the hairline is decoration", contrast(p.hairline.compositeOver(bg), bg) < 3.0)
            }
        }
    }

    @Test
    fun `the hairline is chalk at 12 percent, and HdArgb has it flattened`() {
        for (p in palettes.values) assertEquals(p.chalk.copy(alpha = 0.12f), p.hairline)
        assertEquals(HdArgb.HAIRLINE_ON_PIT, HdPalette.Dark.hairline.compositeOver(HdPalette.Dark.pit).toArgb())
        assertEquals(HdArgb.HAIRLINE_ON_SLAB, HdPalette.Dark.hairline.compositeOver(HdPalette.Dark.slab).toArgb())
    }

    @Test
    fun `the palettes are the values of the design, and HdArgb is the same palette as integers`() {
        fun hex(c: Color) = "#%06X".format(c.toArgb() and 0xFFFFFF)
        val d = HdPalette.Dark
        assertEquals(listOf("#0C0C0D", "#18181A", "#EFEAE0", "#98938A", "#F2B233", "#E8622A"), listOf(d.pit, d.slab, d.chalk, d.ash, d.seam, d.ember).map(::hex))
        val l = HdPalette.Light
        assertEquals(listOf("#F1ECE2", "#E4DDD0", "#151413", "#625C54", "#7D5100", "#A83A0C"), listOf(l.pit, l.slab, l.chalk, l.ash, l.seam, l.ember).map(::hex))
        assertEquals("#F2B233", hex(l.seamLight))
        assertTrue(d.isDark)
        assertFalse(l.isDark)

        assertEquals(
            listOf(HdArgb.PIT, HdArgb.SLAB, HdArgb.CHALK, HdArgb.ASH, HdArgb.SEAM, HdArgb.EMBER),
            listOf(d.pit, d.slab, d.chalk, d.ash, d.seam, d.ember).map { it.toArgb() },
        )
        assertEquals(
            listOf(HdArgb.Day.PIT, HdArgb.Day.SLAB, HdArgb.Day.CHALK, HdArgb.Day.ASH, HdArgb.Day.SEAM, HdArgb.Day.EMBER),
            listOf(l.pit, l.slab, l.chalk, l.ash, l.seam, l.ember).map { it.toArgb() },
        )
        assertEquals(HdArgb.PIT.toLong() and 0xFFFFFFFFL, HdArgb.PREVIEW_PIT)
    }

    @Test
    fun `the compatibility colours still read as text on pit and slab`() {
        val d = HdPalette.Dark
        for (c in listOf(HdCompat.FROST, HdCompat.COOLING)) {
            assertAa(Color(c), d.pit, "compat %08X on pit".format(c))
            assertAa(Color(c), d.slab, "compat %08X on slab".format(c))
        }
        // The sample badge and the unfreeze button put pit ink on them.
        assertAa(d.pit, Color(HdCompat.COOLING), "pit on cooling")
        assertAa(d.pit, Color(HdCompat.FROST), "pit on frost")
    }

    @Test
    fun `Material reads chalk as primary, gold as tertiary, ember as error and ash as the outline`() {
        for (p in palettes.values) {
            val m = HdMaterial.colorScheme(p)
            // Not gold: a text button or a progress bar that asks for "primary" must not turn into ORE.
            assertEquals(p.chalk, m.primary)
            assertEquals(p.chalk, m.secondary)
            assertEquals(p.pit, m.onPrimary)
            assertEquals(p.pit, m.onSecondary)
            assertEquals(p.seam, m.tertiary)
            assertEquals(p.ember, m.error)
            assertEquals(p.pit, m.onError)
            assertEquals(p.pit, m.background)
            assertEquals(p.chalk, m.onBackground)
            assertEquals(p.chalk, m.onSurface)
            assertEquals(p.ash, m.onSurfaceVariant)
            assertEquals(p.ash, m.outline)
            assertEquals(p.hairline, m.outlineVariant)
            val surfaces = listOf(
                m.surface, m.surfaceVariant, m.surfaceContainer, m.surfaceContainerHigh, m.surfaceContainerHighest,
                m.surfaceContainerLow, m.surfaceContainerLowest,
            )
            surfaces.forEach { assertEquals(p.slab, it) }
        }
        assertEquals(HdMaterial.colorScheme(HdPalette.Dark).primary, HdMaterial.DarkColors.primary)
        assertEquals(HdMaterial.colorScheme(HdPalette.Light).primary, HdMaterial.LightColors.primary)
    }

    @Test
    fun `the type scale is the design's, and Material body text is never below 16sp`() {
        val t = HdType.Default
        assertEquals(listOf(112.sp, 56.sp, 30.sp, 19.sp, 16.sp, 16.sp, 13.sp, 12.sp, 16.sp),
            listOf(t.hero, t.display, t.title, t.headline, t.body, t.button, t.label, t.caption, t.figure).map { it.fontSize })
        assertEquals(24.sp, t.body.lineHeight)
        for (style in listOf(t.hero, t.display, t.title)) {
            assertEquals(HdFonts.Display, style.fontFamily)
            assertEquals((-0.02f), style.letterSpacing.value, 1e-6f)
            assertTrue(style.lineHeight.value in 1.0f..1.1f)
        }
        for (style in listOf(t.label, t.caption, t.figure)) assertEquals(HdFonts.Mono, style.fontFamily)
        assertEquals(0.08f, t.label.letterSpacing.value, 1e-6f)

        val m = HdMaterial.Typography
        for (style in listOf(m.bodyLarge, m.bodyMedium, m.bodySmall)) {
            assertEquals(16.sp, style.fontSize)
            assertEquals(24.sp, style.lineHeight)
        }
        assertEquals(HdFonts.Display, m.displaySmall.fontFamily)
        assertEquals(HdFonts.Display, m.headlineSmall.fontFamily)
        assertEquals(HdFonts.Mono, m.labelMedium.fontFamily)
    }

    @Test
    fun `shapes are slabs and squares`() {
        assertEquals(HdShapes.Default.chip, HdMaterial.Shapes.extraSmall)
        assertEquals(HdShapes.Default.plate, HdMaterial.Shapes.small)
        assertEquals(HdShapes.Default.plate, HdMaterial.Shapes.medium)
        // In dp: 4 on chips, 6 on plates and bars, 14 on a sheet's top corners and on Material's largest shape.
        assertEquals(RoundedCornerShape(4.dp), HdShapes.Default.chip)
        assertEquals(RoundedCornerShape(6.dp), HdShapes.Default.plate)
        assertEquals(RoundedCornerShape(6.dp), HdShapes.Default.bar)
        assertEquals(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp), HdShapes.Default.sheet)
        assertEquals(RoundedCornerShape(14.dp), HdMaterial.Shapes.extraLarge)
        assertEquals(1.dp, HdDimens.Hairline)
        assertEquals(56.dp, HdDimens.Bar)
        assertEquals(48.dp, HdDimens.Touch)
        assertEquals(20.dp, HdDimens.Margin)
    }

    @Test
    fun `three springs, and one that arrives at once when motion is reduced`() {
        val motion = HdMotion(reduced = false)
        assertEquals(0.86f to 380f, motion.settle<Float>().let { it.dampingRatio to it.stiffness })
        assertEquals(0.9f to 1500f, motion.press<Float>().let { it.dampingRatio to it.stiffness })
        assertEquals(0.62f to 220f, motion.thud<Float>().let { it.dampingRatio to it.stiffness })
        // Only thud bounces enough to be seen.
        assertTrue(motion.thud<Float>().dampingRatio < 0.7f && motion.settle<Float>().dampingRatio > 0.8f)

        val reduced = HdMotion(reduced = true)
        val specs = listOf(reduced.settle<Float>(), reduced.press(), reduced.thud())
        assertEquals(1, specs.toSet().size)
        assertEquals(1f, specs.first().dampingRatio, 0f)
        assertTrue(specs.first().stiffness >= 1_000_000f)
        assertEquals(HdMotion(true), reduced)
        assertFalse(HdMotion(false) == reduced)
    }

    private fun assertAa(fg: Color, bg: Color, name: String) {
        val r = contrast(fg, bg)
        assertTrue("$name: %.2f:1".format(r), r >= 4.5)
    }

    private fun contrast(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    /** WCAG 2.x relative luminance of an opaque colour. */
    private fun luminance(c: Color): Double {
        fun ch(v: Float) = if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        return 0.2126 * ch(c.red) + 0.7152 * ch(c.green) + 0.0722 * ch(c.blue)
    }
}
