package xyz.headsdown.core.design

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * One palette and one mark, in one module. No file that the app module, the reveal or a surface
 * ships writes a colour of its own, and the icons that live in other modules carry the canonical
 * glyph. (Not scanned: `:feature:shift`, whose debug-only sensor lab still has four literals.)
 */
class SingleSourceTest {

    /** `0xFF121314`, `0x66FF6A1A`: an ARGB integer. `#121314`, `#FF121314`: a colour in XML or a string. */
    private val literal = Regex("""0x[0-9A-Fa-f]{8}\b|#[0-9A-Fa-f]{6}(?:[0-9A-Fa-f]{2})?\b""")

    /**
     * What may still be written outside the design module, and why:
     * - opaque white: the Quick Settings tile icon and the notification's small icon are alpha
     *   masks the system tints, and white is the convention for one.
     * Compatibility colours (HdCompat) are not here: they were moved into the design module too.
     */
    private val allowed = setOf("FFFFFFFF")

    @Test
    fun `no palette literal is written outside the design module`() {
        val found = mutableListOf<String>()
        var scanned = 0
        for (module in SourceTree.drawingModules) {
            for (file in SourceTree.shippedFiles(module)) {
                scanned++
                file.readLines().forEachIndexed { i, line ->
                    for (m in literal.findAll(line)) {
                        if (normalise(m.value) !in allowed) found += "${SourceTree.relative(file)}:${i + 1}: ${m.value}"
                    }
                }
            }
        }
        assertTrue("the scan found the app's, the reveal's and the surfaces' files: $scanned", scanned > 60)
        assertEquals("colours belong in :core:design (HdArgb, HdCompat, res/values/colors.xml)", emptyList<String>(), found)
    }

    @Test
    fun `the scan sees a literal when there is one`() {
        fun hits(line: String) = literal.findAll(line).map { normalise(it.value) }.toList()
        assertEquals(listOf("FF121314"), hits("val Charcoal = Color(0xFF121314)"))
        assertEquals(listOf("FFFF6A1A"), hits("""<color name="hd_ember">#FFFF6A1A</color>"""))
        assertEquals(listOf("FF9A948D"), hits("""val ASH_MUTED = "#9A948D".toColorInt()"""))
        assertEquals(listOf("66E8622A"), hits("1 -> 0x66E8622A.toInt()"))
        // Not colours: a notification id, a byte mask, an issue number.
        assertEquals(emptyList<String>(), hits("const val SHIFT_NOTIFICATION_ID = 0x4844 // see #12, mask 0xFF"))
    }

    @Test
    fun `the tile icon and the notification icon are the canonical glyph, under their own names`() {
        val icons = listOf(
            "surface/tile/src/main/res/drawable/ic_tile_rig.xml",
            "surface/notification/src/main/res/drawable/ic_stat_rig.xml",
        )
        for (path in icons) {
            val xml = File(SourceTree.android, path)
            assertTrue("$path must keep its name: the tile and the foreground service load it by it", xml.isFile)
            val text = xml.readText()
            assertEquals(path, listOf(HdGlyph.HULL, HdGlyph.SEAM), pathData(text))
            assertEquals(path, listOf("24dp", "24dp", "24", "24"), listOf("width", "height", "viewportWidth", "viewportHeight").map { attr(text, it) })
            assertEquals("$path is a white alpha mask", setOf("#FFFFFFFF"), Regex("""android:fillColor="([^"]*)"""").findAll(text).map { it.groupValues[1] }.toSet())
            assertTrue("$path: fills only", "strokeColor" !in text)
        }
        // And they are still what the code and the manifest ask for.
        val factory = File(SourceTree.android, "surface/notification/src/main/kotlin/xyz/headsdown/surface/notification/ShiftNotificationFactory.kt").readText()
        assertTrue("setSmallIcon(R.drawable.ic_stat_rig)" in factory)
        val tileManifest = File(SourceTree.android, "surface/tile/src/main/AndroidManifest.xml").readText()
        assertTrue("""android:icon="@drawable/ic_tile_rig"""" in tileManifest)
    }

    @Test
    fun `the launcher icon and the splash draw the same slab`() {
        val res = File(SourceTree.module, "src/main/res/drawable")
        val colour = listOf(HdGlyph.LAUNCHER_FACE, HdGlyph.LAUNCHER_EDGE, HdGlyph.LAUNCHER_UNDERSIDE, HdGlyph.LAUNCHER_SEAM)
        val foreground = File(res, "ic_launcher_foreground.xml").readText()
        val splash = File(res, "splash_slab.xml").readText()
        val monochrome = File(res, "ic_launcher_monochrome.xml").readText()
        assertEquals(colour, pathData(foreground))
        assertEquals(colour, pathData(splash))
        assertEquals(listOf(HdGlyph.LAUNCHER_HULL, HdGlyph.LAUNCHER_SEAM), pathData(monochrome))
        for (xml in listOf(foreground, splash, monochrome)) {
            assertEquals(listOf("108", "108"), listOf(attr(xml, "viewportWidth"), attr(xml, "viewportHeight")))
        }
        assertEquals("108dp", attr(foreground, "width"))
        // The splash canvas of an icon without a background is 288dp, of which a 192dp circle shows.
        assertEquals("288dp", attr(splash, "width"))
        assertEquals("288dp", attr(splash, "height"))
    }

    @Test
    fun `the mark stays inside the launcher's safe zone and the splash's circle`() {
        // Corners of the slab and the seam on the 108-unit grid, from the path data above.
        val corners = listOf(29f to 36f, 79f to 36f, 79f to 46f, 29f to 46f, 72f to 56f, 36f to 56f, 34f to 66f, 74f to 66f, 74f to 71f, 34f to 71f)
        val farthest = corners.maxOf { (x, y) -> kotlin.math.hypot(x - 54f, y - 54f) }
        assertTrue("launcher safe zone is a 66-unit circle: $farthest", farthest <= 33f)
        val onSplash = farthest * 288f / 108f
        assertTrue("splash shows a 192dp circle: $onSplash", onSplash <= 96f)
    }

    @Test
    fun `the app's launcher icon is made of the design module's layers`() {
        for (name in listOf("ic_launcher.xml", "ic_launcher_round.xml")) {
            val xml = File(SourceTree.android, "app/src/main/res/mipmap-anydpi/$name").readText()
            assertTrue(name, """<background android:drawable="@color/hd_icon_background"""" in xml)
            assertTrue(name, """<foreground android:drawable="@drawable/ic_launcher_foreground"""" in xml)
            assertTrue(name, """<monochrome android:drawable="@drawable/ic_launcher_monochrome"""" in xml)
        }
        // One copy of each layer: a second one in the app would silently win the resource merge.
        assertTrue(!File(SourceTree.android, "app/src/main/res/drawable/ic_launcher_foreground.xml").exists())
    }

    @Test
    fun `Theme HeadsDown is defined once, here, on a base a night variant can replace`() {
        val definitions = SourceTree.android.walkTopDown()
            .onEnter { it.name != "build" && it.name != ".gradle" }
            .filter { it.isFile && it.extension == "xml" && it.parentFile.name.startsWith("values") }
            .filter { """<style name="Theme.HeadsDown"""" in it.readText() }
            .map(SourceTree::relative)
            .toList()
        assertEquals(listOf("core/design/src/main/res/values/themes.xml"), definitions)
        val themes = File(SourceTree.module, "src/main/res/values/themes.xml").readText()
        assertTrue("""<style name="Theme.HeadsDown" parent="Base.Theme.HeadsDown" />""" in themes)
        assertTrue("""<item name="android:windowBackground">@color/hd_pit</item>""" in themes)
        assertTrue("""<item name="android:windowSplashScreenBackground">@color/hd_pit</item>""" in themes)
        assertTrue("""<item name="android:windowSplashScreenAnimatedIcon">@drawable/splash_slab</item>""" in themes)
        for (unset in listOf("windowSplashScreenIconBackgroundColor", "windowSplashScreenBrandingImage", "windowSplashScreenBehavior")) {
            assertTrue("$unset stays unset", """<item name="android:$unset"""" !in themes)
        }
    }

    @Test
    fun `the XML palette is the Kotlin palette`() {
        val xml = File(SourceTree.module, "src/main/res/values/colors.xml").readText()
        val colors = Regex("""<color name="([a-z_]+)">#([0-9A-Fa-f]{8})</color>""").findAll(xml)
            .associate { it.groupValues[1] to it.groupValues[2].toLong(16).toInt() }
        val expected = mapOf(
            "hd_pit" to HdArgb.PIT,
            "hd_slab" to HdArgb.SLAB,
            "hd_chalk" to HdArgb.CHALK,
            "hd_ash" to HdArgb.ASH,
            "hd_seam" to HdArgb.SEAM,
            "hd_ember" to HdArgb.EMBER,
            "hd_icon_background" to HdArgb.PIT,
            "hd_icon_seam" to HdArgb.SEAM_LIGHT,
            "hd_compat_cooling" to HdCompat.COOLING,
        )
        for ((name, argb) in expected) assertEquals(name, argb, colors[name])
        // Nothing else is declared but the icon's two own shades.
        assertEquals(expected.keys + setOf("hd_icon_face", "hd_icon_edge", "hd_icon_underside_shadow"), colors.keys)
    }

    private fun normalise(literal: String): String {
        val hex = literal.removePrefix("0x").removePrefix("#").uppercase()
        return if (hex.length == 6) "FF$hex" else hex
    }

    private fun pathData(xml: String): List<String> =
        Regex("""android:pathData="([^"]*)"""").findAll(xml).map { it.groupValues[1] }.toList()

    private fun attr(xml: String, name: String): String? =
        Regex("""android:$name="([^"]*)"""").find(xml)?.groupValues?.get(1)
}
