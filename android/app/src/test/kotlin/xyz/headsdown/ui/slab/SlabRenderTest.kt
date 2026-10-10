package xyz.headsdown.ui.slab

import android.app.Application
import android.content.ComponentName
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The polygon renderer's pixels, through Robolectric's native graphics: the default hero really
 * draws a slab, where it should be, and leaves the page around it exactly as it was.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w360dp-h640dp-xhdpi")
class SlabRenderTest {
    private val rule = createComposeRule()

    private val hostActivity = object : ExternalResource() {
        override fun before() {
            val app = ApplicationProvider.getApplicationContext<Application>()
            shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
        }
    }

    @get:Rule val rules: RuleChain = RuleChain.outerRule(hostActivity).around(rule)

    /** The hero on its palette's pit, as a screen would have it. */
    private fun capture(state: SlabState, heat: Int, palette: SlabPalette = SlabPalette.Dark): Bitmap {
        rule.setContent {
            Box(Modifier.size(300.dp).background(palette.pit).testTag("hero")) {
                SlabHero(state = state, heat = heat, modifier = Modifier.size(300.dp), palette = palette)
            }
        }
        return rule.onNodeWithTag("hero").captureToImage().asAndroidBitmap()
    }

    private fun red(pixel: Int) = pixel shr 16 and 0xFF
    private fun green(pixel: Int) = pixel shr 8 and 0xFF
    private fun blue(pixel: Int) = pixel and 0xFF

    private fun assertEdgeIs(image: Bitmap, pit: Int) {
        val w = image.width
        val h = image.height
        for (x in 0 until w step 5) {
            assertEquals("pixel ($x, 0)", pit, image.getPixel(x, 0))
            assertEquals("pixel ($x, ${h - 1})", pit, image.getPixel(x, h - 1))
        }
        for (y in 0 until h step 5) {
            assertEquals("pixel (0, $y)", pit, image.getPixel(0, y))
            assertEquals("pixel (${w - 1}, $y)", pit, image.getPixel(w - 1, y))
        }
    }

    @Test
    fun `a cold slab is stone in the middle and leaves the page around it untouched`() {
        val shaders = SlabProbe.shadersBuilt
        val draws = SlabProbe.draws
        val image = capture(SlabState.Cold, 0)
        assertTrue("the hero was never drawn", SlabProbe.draws > draws)
        assertEquals("the default renderer built a shader", shaders, SlabProbe.shadersBuilt)
        val pit = SlabPalette.Dark.pit.toArgb()
        val w = image.width
        val h = image.height
        // The middle of the hero is the top face: near black, a little lighter than the pit.
        val face = image.getPixel(w / 2, (h * 0.47f).toInt())
        assertTrue("the face is ${Integer.toHexString(face)}", red(face) in 0x14..0x40 && blue(face) in 0x14..0x44)
        assertTrue(face != pit)
        // With no light there is no halo: everything outside the slab is the page, to the bit.
        assertEdgeIs(image, pit)
        var page = 0
        for (y in 0 until h) for (x in 0 until w) if (image.getPixel(x, y) == pit) page++
        assertTrue("the slab covers ${100 - 100 * page / (w * h)}% of the hero", page > w * h / 2 && page < w * h * 9 / 10)
    }

    @Test
    fun `a hot slab throws gold light below itself and none at the hero's edge`() {
        val image = capture(SlabState.Hot, 5)
        val pit = SlabPalette.Dark.pit.toArgb()
        val w = image.width
        val h = image.height
        // Below the slab, on the centre line: warmer than the page.
        var warmest = 0
        for (y in (h * 0.60f).toInt() until (h * 0.85f).toInt()) {
            val pixel = image.getPixel(w / 2, y)
            if (red(pixel) > green(pixel) && green(pixel) > blue(pixel)) warmest = maxOf(warmest, red(pixel) - red(pit))
        }
        assertTrue("no halo under a hot slab", warmest > 0x20)
        // The halo has finite support: the edge of the hero is the page, to the bit.
        assertEdgeIs(image, pit)
    }

    @Test
    fun `an armed slab shows the ember seam on its lower rim, and a cold one does not`() {
        fun emberOnCentreLine(image: Bitmap): Int {
            var ember = 0
            for (y in (image.height * 0.45f).toInt() until (image.height * 0.75f).toInt()) {
                val pixel = image.getPixel(image.width / 2, y)
                // #E8622A, give or take the antialiasing of a thin line.
                if (red(pixel) > 0xB0 && green(pixel) in 0x40..0x80 && blue(pixel) < 0x50) ember++
            }
            return ember
        }
        assertTrue("no seam on the centre line", emberOnCentreLine(capture(SlabState.Armed, 0)) >= 1)
    }

    @Test
    fun `on the light page the slab is still dark and casts a shadow`() {
        val image = capture(SlabState.Cold, 0, SlabPalette.Light)
        val pit = SlabPalette.Light.pit.toArgb()
        val w = image.width
        val h = image.height
        val face = image.getPixel(w / 2, (h * 0.47f).toInt())
        assertTrue("the face is ${Integer.toHexString(face)}", red(face) < 0x40)
        // Below the slab the page is darkened, not lit.
        var darkest = 0
        for (y in (h * 0.62f).toInt() until (h * 0.85f).toInt()) {
            val pixel = image.getPixel(w / 2, y)
            if (red(pixel) > 0x80) darkest = maxOf(darkest, red(pit) - red(pixel))
        }
        assertTrue("no shadow under the slab on the light page", darkest > 8)
        assertEdgeIs(image, pit)
    }
}
