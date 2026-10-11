package xyz.headsdown.surface.widget

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.glance.unit.ColorProvider
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import xyz.headsdown.core.design.HdArgb
import xyz.headsdown.core.design.HdCompat
import xyz.headsdown.core.design.HdPalette
import java.io.File

/**
 * The widgets wear "The Underside" and nothing else: every colour they can draw resolves to a
 * value of `HdArgb` in the design module, by day and by night, and the two colours the old
 * widget had of its own (a frozen blue, a cooling orange) are not drawn any more.
 */
@RunWith(RobolectricTestRunner::class)
class WidgetBrandTest {

    /** What a provider gives a launcher in day mode and in night mode. */
    private fun dayAndNight(provider: ColorProvider): Pair<Int, Int> {
        fun resolve(qualifier: String): Int {
            RuntimeEnvironment.setQualifiers(qualifier)
            val context: Context = ApplicationProvider.getApplicationContext()
            return provider.getColor(context).toArgb()
        }
        return resolve("+notnight") to resolve("+night")
    }

    private fun assertBoth(expected: Int, provider: ColorProvider, what: String) {
        val (day, night) = dayAndNight(provider)
        assertEquals("$what by day", hex(expected), hex(day))
        assertEquals("$what by night", hex(expected), hex(night))
    }

    private fun hex(argb: Int) = "%08X".format(argb)

    @Test
    fun `the brand widget is a slab of the pit by day and by night`() {
        val c = WidgetColors.brand
        assertBoth(HdArgb.PIT, c.surface, "the slab")
        assertBoth(HdArgb.PIT, c.background, "the background")
        assertBoth(HdArgb.CHALK, c.onSurface, "ink")
        assertBoth(HdArgb.ASH, c.onSurfaceVariant, "muted ink")
        assertBoth(HdArgb.HAIRLINE_ON_PIT, c.outline, "the hairline, the unlit pixel and the empty seat")
        assertTrue(WidgetColors.providers(WidgetColorMode.BRAND) === c)
    }

    @Test
    fun `the brand accents are the design module's ember and gold`() {
        val a = WidgetAccents(WidgetColorMode.BRAND)
        assertBoth(HdArgb.EMBER, a.ember, "ember")
        assertBoth(HdArgb.SEAM, a.gold, "gold as ink")
        assertBoth(HdArgb.SEAM_LIGHT, a.light, "gold as light")
    }

    @Test
    fun `a widget that follows the wallpaper takes the light palette's accents by day`() {
        val a = WidgetAccents(WidgetColorMode.DYNAMIC)
        assertEquals(hex(HdArgb.Day.EMBER) to hex(HdArgb.EMBER), dayAndNight(a.ember).let { hex(it.first) to hex(it.second) })
        assertEquals(hex(HdArgb.Day.SEAM) to hex(HdArgb.SEAM), dayAndNight(a.gold).let { hex(it.first) to hex(it.second) })
        // The gold light would vanish on a pale surface, so by day it is the gold that reads as ink.
        assertEquals(hex(HdArgb.Day.SEAM) to hex(HdArgb.SEAM_LIGHT), dayAndNight(a.light).let { hex(it.first) to hex(it.second) })
    }

    @Test
    fun `each rig state wears the accent the design system gives it`() {
        val a = WidgetAccents(WidgetColorMode.BRAND)
        val ink = WidgetColors.brand.onSurface
        val muted = WidgetColors.brand.onSurfaceVariant
        fun word(heat: RigHeat) = a.word(heat, ink, muted)
        fun lights(heat: RigHeat) = a.lights(heat, muted)

        // Ember says armed and cooling: the word and the lights.
        for (heat in listOf(RigHeat.ARMED, RigHeat.COOLING)) {
            assertBoth(HdArgb.EMBER, word(heat), "$heat word")
            assertBoth(HdArgb.EMBER, lights(heat), "$heat lights")
        }
        // Hot: the word is plain ink and the gold is light, never the ink of a word.
        assertBoth(HdArgb.CHALK, word(RigHeat.HOT), "hot word")
        assertBoth(HdArgb.SEAM_LIGHT, lights(RigHeat.HOT), "hot lights")
        // Frozen has no colour of its own: chalk, beside its word, and ash lights.
        assertBoth(HdArgb.CHALK, word(RigHeat.FROZEN), "frozen word")
        assertBoth(HdArgb.ASH, lights(RigHeat.FROZEN), "frozen lights")
        assertBoth(HdArgb.ASH, word(RigHeat.COLD), "cold word")
        assertEquals(0, litPixels(RigHeat.COLD))
        assertEquals(5, litPixels(RigHeat.FROZEN))
    }

    @Test
    fun `no state is drawn in the old frozen blue or the old cooling orange`() {
        val old = setOf(HdCompat.FROST, HdCompat.COOLING, HdCompat.FROST_DAY, HdCompat.COOLING_DAY, HdCompat.EMBER_DIM)
        for (mode in WidgetColorMode.entries) {
            val a = WidgetAccents(mode)
            val drawn = RigHeat.entries.flatMap { heat ->
                listOf(a.word(heat, WidgetColors.brand.onSurface, WidgetColors.brand.onSurfaceVariant), a.lights(heat, WidgetColors.brand.onSurfaceVariant))
            } + listOf(a.ember, a.gold, a.light)
            for (provider in drawn) {
                val (day, night) = dayAndNight(provider)
                assertFalse("$mode draws ${hex(day)} by day", day in old)
                assertFalse("$mode draws ${hex(night)} by night", night in old)
            }
        }
    }

    @Test
    fun `only the palette shim names the compatibility colours, and nothing reads them`() {
        // Gradle runs unit tests in the module directory (as the design module's SourceTree relies on).
        val main = File(checkNotNull(System.getProperty("user.dir")), "src/main")
        assertTrue("not the widget module: $main", File(main, "kotlin/xyz/headsdown/surface/widget/WidgetColors.kt").isFile)
        val files = main.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "xml") }.toList()
        assertTrue("the scan saw the sources and the resources: ${files.size}", files.size > 15)

        val shim = Regex("""^\s*const val (FROST|COOLING|COOLING_ON_LIGHT|FROST_ON_LIGHT) = HdCompat\.\w+\.toLong\(\) and OPAQUE$""")
        val offenders = mutableListOf<String>()
        for (file in files) {
            file.readLines().forEachIndexed { i, raw ->
                val line = raw.substringBefore("//").trim()
                if (line.startsWith("*") || line.startsWith("/*") || line.startsWith("<!--")) return@forEachIndexed
                val where = "${file.name}:${i + 1}: $line"
                if ("import xyz.headsdown.core.design.HdCompat" in line) return@forEachIndexed
                if ("HdCompat" in line && !shim.matches(raw.substringBefore("//").trimEnd())) offenders += where
                if ("hd_compat" in line) offenders += where
                if (Regex("""WidgetPalette\.(FROST|COOLING)""").containsMatchIn(line)) offenders += where
            }
        }
        assertEquals(emptyList<String>(), offenders)
    }

    @Test
    fun `the static layouts take their colours from the design module's resources`() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val expected = mapOf(
            R.color.hd_widget_charcoal to HdArgb.PIT,
            R.color.hd_widget_ember to HdArgb.EMBER,
            R.color.hd_widget_gold to HdArgb.SEAM,
            R.color.hd_widget_ash to HdArgb.CHALK,
            R.color.hd_widget_ash_muted to HdArgb.ASH,
        )
        for ((id, argb) in expected) assertEquals(context.resources.getResourceEntryName(id), hex(argb), hex(context.getColor(id)))

        // The hairline has no value of its own: it is the chalk at 12%, which on the pit is the
        // colour the real widget draws its frame in.
        val hairline = Color(context.getColorStateList(R.color.hd_widget_hairline).defaultColor)
        assertEquals(hex(Color(HdArgb.CHALK).copy(alpha = HdPalette.HAIRLINE_ALPHA).toArgb()), hex(hairline.toArgb()))
        assertEquals(hex(HdArgb.HAIRLINE_ON_PIT), hex(hairline.compositeOver(Color(HdArgb.PIT)).toArgb()))
    }

    @Test
    fun `every string of the module passes the banned-word rule`() {
        // The pattern of HonestCopy (feature/reveal), which this module does not depend on.
        val banned = Regex(
            "\\b(earn\\w*|yield\\w*|stak(e|es|ed|ing)|passive income|proof of focus|focus mining|profit\\w*|guarantee\\w*|risk-free|free ore|" +
                "lotter(y|ies)|jackpot\\w*|gambl\\w*|bet|bets|betting|apy|apr|returns|interest)\\b",
            RegexOption.IGNORE_CASE,
        )
        assertEquals(listOf("stake", "bet"), banned.findAll("Stake it and bet").map { it.value.lowercase() }.toList())
        val context: Context = ApplicationProvider.getApplicationContext()
        // The picker's labels and descriptions and the static previews: every string resource of this module.
        val resources = R.string::class.java.fields.map { it.name to context.getString(it.getInt(null)) }
        assertTrue("the scan saw the string resources: ${resources.map { it.first }}", resources.size >= 13)
        assertTrue(resources.map { it.first }.containsAll(listOf("hd_widget_rig_label", "hd_widget_rig_description", "hd_widget_crew_label", "hd_widget_crew_description", "hd_widget_preview_crew_line")))
        val runtime = listOf(CrewPlaceholderCopy.TITLE, CrewPlaceholderCopy.TAG, CrewPlaceholderCopy.LINE, CrewPlaceholderCopy.DESCRIPTION).map { "CrewPlaceholderCopy" to it }
        for ((name, value) in resources + runtime) {
            assertEquals("$name: $value", emptyList<String>(), banned.findAll(value).map { it.value }.toList())
            // No price and no ticker on a home screen: an amount is ORE and nothing else.
            assertFalse("$name: $value", value.contains('$') || Regex("\\b(SOL|USD|USDC)\\b").containsMatchIn(value))
        }
    }

    @Test
    fun `widget text follows the system font scale up to the cap and no further`() {
        /** The type at a system font scale, and how many dp tall it has the system draw [sp]. */
        class At(system: Float, max: Float = WidgetType.MAX_SCALE) {
            private val resources = run {
                RuntimeEnvironment.setFontScale(system)
                ApplicationProvider.getApplicationContext<Context>().resources
            }
            val type = WidgetType(resources, max)
            fun drawn(sp: Float): Float = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, type.spValue(sp), resources.displayMetrics) / resources.displayMetrics.density
            fun system(sp: Float): Float = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, sp, resources.displayMetrics) / resources.displayMetrics.density
        }
        val sizes = listOf(10f, 11f, 12f, 13f, 17f, 20f, 30f)

        At(1f).let { at ->
            assertEquals(1f, at.type.scale, 0f)
            for (sp in sizes) assertEquals(sp, at.drawn(sp), 0.01f)
        }
        At(0.85f).let { at ->
            assertEquals(0.85f, at.type.scale, 0f)
            for (sp in sizes) assertEquals("a smaller font is honoured", at.system(sp), at.drawn(sp), 0.01f)
        }
        for (system in listOf(1.15f, 1.3f, 1.5f, 1.8f, 2f)) {
            val at = At(system)
            assertEquals(minOf(system, WidgetType.MAX_SCALE), at.type.scale, 0f)
            for (sp in sizes) {
                val drawn = at.drawn(sp)
                assertTrue("$sp sp at $system is drawn $drawn dp: at most ${WidgetType.MAX_SCALE} times its size", drawn <= sp * WidgetType.MAX_SCALE + 0.3f)
                assertTrue("$sp sp at $system is drawn $drawn dp: never smaller than at 1.0", drawn >= sp - 0.01f)
                assertTrue("$sp sp at $system is drawn $drawn dp: never larger than the system asks", drawn <= at.system(sp) + 0.01f)
            }
        }
        // At 2.0 the system would draw 10sp at 20dp; the widget draws it at 13.
        At(2f).let { at ->
            assertEquals(20f, at.system(10f), 0.01f)
            assertEquals(13f, at.drawn(10f), 0.3f)
        }
        // A layout that is full at 1.0 holds its text at 1.0, and a small font is still honoured.
        At(2f, max = 1f).let { at ->
            assertEquals(1f, at.type.scale, 0f)
            for (sp in sizes) assertEquals(sp, at.drawn(sp), 0.3f)
        }
        assertEquals(1f, At(2f, max = 0.5f).type.scale, 0f)
        assertEquals(1.2f, At(2f, max = 1.2f).type.scale, 0f)
        assertEquals(0.85f, At(0.85f, max = 1f).type.scale, 0f)
        // The platform's condensed sans stands in for the display face, as FONTS.md says it may.
        assertEquals("sans-serif-condensed", WidgetType.CONDENSED.family)
    }
}
