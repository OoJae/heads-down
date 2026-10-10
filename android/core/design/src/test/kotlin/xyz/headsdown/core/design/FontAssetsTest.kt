package xyz.headsdown.core.design

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.ResourceFont
import androidx.core.content.res.ResourcesCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.security.MessageDigest

/**
 * The bundled fonts are the files FONTS.md describes, and they load: as resources in Robolectric's
 * default graphics mode (which is how every screen test in the app composes them), and as real
 * typefaces with real metrics in the native one.
 */
@RunWith(RobolectricTestRunner::class)
class FontAssetsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val fontsMd = File(SourceTree.module, "FONTS.md").readText()

    private val fonts = mapOf(
        "src/main/res/font/big_shoulders_display_extrabold.ttf" to R.font.big_shoulders_display_extrabold,
        "src/main/res/font/ibm_plex_mono_regular.ttf" to R.font.ibm_plex_mono_regular,
        "src/main/res/font/ibm_plex_mono_medium.ttf" to R.font.ibm_plex_mono_medium,
    )

    @Test
    fun `the shipped files are the ones FONTS_md records`() {
        val shipped = hashTable(fontsMd.substringAfter("\nShipped:\n"))
        val expected = fonts.keys + listOf("licenses/BigShouldersDisplay-OFL.txt", "licenses/IBMPlexMono-OFL.txt")
        assertEquals(expected.toSet(), shipped.keys)
        for ((path, sha) in shipped) assertEquals(path, sha, sha256(File(SourceTree.module, path)))
    }

    @Test
    fun `IBM Plex Mono and both licence texts are byte for byte the downloads`() {
        // "Plex" is a Reserved Font Name: a modified copy would have to be called something else.
        val downloads = hashTable(fontsMd.substringAfter("## Hashes").substringBefore("\nShipped:\n"))
        val same = mapOf(
            "src/main/res/font/ibm_plex_mono_regular.ttf" to "ibmplexmono/IBMPlexMono-Regular.ttf",
            "src/main/res/font/ibm_plex_mono_medium.ttf" to "ibmplexmono/IBMPlexMono-Medium.ttf",
            "licenses/IBMPlexMono-OFL.txt" to "ibmplexmono/OFL.txt",
            "licenses/BigShouldersDisplay-OFL.txt" to "bigshouldersdisplay/OFL.txt",
        )
        for ((file, download) in same) assertEquals(file, downloads.getValue(download), sha256(File(SourceTree.module, file)))
        assertTrue("Reserved Font Name \"Plex\"" in File(SourceTree.module, "licenses/IBMPlexMono-OFL.txt").readText())
        assertTrue("Reserved Font Name" !in File(SourceTree.module, "licenses/BigShouldersDisplay-OFL.txt").readText().substringBefore("PREAMBLE"))
    }

    @Test
    fun `only the three fonts are in res font, and the variable display font is not one of them`() {
        val names = checkNotNull(File(SourceTree.module, "src/main/res/font").list()).sorted()
        assertEquals(listOf("big_shoulders_display_extrabold.ttf", "ibm_plex_mono_medium.ttf", "ibm_plex_mono_regular.ttf"), names)
        // A variable font has an fvar table; the shipped display face is a static instance.
        val bytes = File(SourceTree.module, "src/main/res/font/big_shoulders_display_extrabold.ttf").readBytes()
        assertTrue("fvar" !in tableTags(bytes))
        assertTrue("glyf" in tableTags(bytes))
    }

    @Test
    fun `HdFonts names the three files with the weights they have`() {
        val display = HdFonts.Display.let { it as androidx.compose.ui.text.font.FontListFontFamily }.fonts.map { it as ResourceFont }
        assertEquals(listOf(R.font.big_shoulders_display_extrabold to FontWeight.ExtraBold), display.map { it.resId to it.weight })
        val mono = HdFonts.Mono.let { it as androidx.compose.ui.text.font.FontListFontFamily }.fonts.map { it as ResourceFont }
        assertEquals(
            listOf(R.font.ibm_plex_mono_regular to FontWeight.Normal, R.font.ibm_plex_mono_medium to FontWeight.Medium),
            mono.map { it.resId to it.weight },
        )
    }

    @Test
    fun `the fonts load as resources in the default graphics mode`() {
        for ((path, id) in fonts) assertNotNull(path, ResourcesCompat.getFont(context, id))
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `the fonts are real typefaces with the weights and widths FONTS_md states`() {
        val display = typeface(R.font.big_shoulders_display_extrabold)
        assertEquals(800, display.weight)
        assertEquals(400, typeface(R.font.ibm_plex_mono_regular).weight)
        assertEquals(500, typeface(R.font.ibm_plex_mono_medium).weight)

        // No tabular figures in the display face: a "1" is much narrower than a "0".
        val em = 1000f
        val wide = Paint().apply { typeface = display; textSize = em }
        assertEquals(0.47f, wide.measureText("0") / em, 0.02f)
        assertEquals(0.26f, wide.measureText("1") / em, 0.02f)
        // The mono face: every character 0.6em.
        val mono = Paint().apply { typeface = typeface(R.font.ibm_plex_mono_regular); textSize = em }
        for (ch in listOf("0", "1", "W", ".", " ")) assertEquals(ch, 0.6f, mono.measureText(ch) / em, 0.005f)
    }

    private fun typeface(id: Int): Typeface = checkNotNull(ResourcesCompat.getFont(context, id))

    /** Rows of the form ``| `path` | `sha256` |``. */
    private fun hashTable(markdown: String): Map<String, String> =
        Regex("""\|\s*`([^`]+)`\s*\|\s*`([0-9a-f]{64})`\s*\|""").findAll(markdown).associate { it.groupValues[1] to it.groupValues[2] }

    private fun sha256(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    /** The table directory of an sfnt file: a 12-byte header, then 16-byte records that start with a 4-letter tag. */
    private fun tableTags(font: ByteArray): Set<String> {
        val count = ((font[4].toInt() and 0xFF) shl 8) or (font[5].toInt() and 0xFF)
        return (0 until count).map { i -> String(font, 12 + 16 * i, 4, Charsets.US_ASCII) }.toSet()
    }
}
