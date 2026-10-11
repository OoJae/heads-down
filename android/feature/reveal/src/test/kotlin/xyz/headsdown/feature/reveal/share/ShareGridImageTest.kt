package xyz.headsdown.feature.reveal.share

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import xyz.headsdown.core.design.HdArgb
import xyz.headsdown.feature.reveal.haul.Board
import xyz.headsdown.feature.reveal.haul.FakeHaulRepository
import java.time.ZoneId

/**
 * The share image is drawn in the dark palette of "The Underside" with the two bundled faces, and
 * says nothing the grid does not know: where the rig dug is chalk, and no tile is gold, because on
 * the reveal solid gold means a tile came up and the grid carries no outcomes.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ShareGridImageTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val night = FakeHaulRepository.sampleNight(1_790_000_000_000L, ZoneId.of("UTC"))
    private val grid = ShareGrid.from(night)

    // The geometry of ShareGridImage.render: a plate inside 96 px margins, a 36 px inset, 12 px between tiles.
    private val plateLeft = 96f
    private val plateTop = 258f
    private val plateSize = ShareGridImage.WIDTH - 2 * 96f
    private val gap = 12f
    private val cell = (plateSize - 2 * 36f - (Board.SIZE - 1) * gap) / Board.SIZE

    private fun centre(tile: Int): Pair<Int, Int> {
        val x = plateLeft + 36f + (tile % Board.SIZE) * (cell + gap) + cell / 2
        val y = plateTop + 36f + (tile / Board.SIZE) * (cell + gap) + cell / 2
        return x.toInt() to y.toInt()
    }

    @Test
    fun `the levels are the empty well and three steps of chalk, none of them gold or ember`() {
        val colours = (0..ShareGrid.MAX_LEVEL).map(ShareGridImage::levelColor)
        assertEquals(HdArgb.PIT, colours.first())
        assertEquals(HdArgb.CHALK, colours.last())
        assertEquals(colours.size, colours.toSet().size)
        // Opaque (the image leaves the device), and lighter with every level.
        fun light(argb: Int) = ((argb shr 16) and 0xFF) + ((argb shr 8) and 0xFF) + (argb and 0xFF)
        assertTrue(colours.all { (it ushr 24) == 0xFF })
        assertEquals(colours.map(::light), colours.map(::light).sorted())
        assertFalse(HdArgb.SEAM in colours)
        assertFalse(HdArgb.EMBER in colours)
    }

    @Test
    fun `the image is the grid in chalk on the slab, with the gold only as the line under it`() {
        val bitmap = ShareGridImage.render(grid, context)
        assertEquals(ShareGridImage.WIDTH to ShareGridImage.HEIGHT, bitmap.width to bitmap.height)
        assertEquals("the background is the pit", HdArgb.PIT, bitmap.getPixel(10, 10))
        assertEquals("the plate is the slab", HdArgb.SLAB, bitmap.getPixel((plateLeft + 18f).toInt(), (plateTop + 18f).toInt()))
        assertTrue("the sample night has all four levels", grid.levels.toSet() == (0..ShareGrid.MAX_LEVEL).toSet())
        for (tile in 0 until Board.TILES) {
            val (x, y) = centre(tile)
            assertEquals("tile $tile, level ${grid.levels[tile]}", ShareGridImage.levelColor(grid.levels[tile]), bitmap.getPixel(x, y))
        }
        // The line of light under the slab, narrower than the slab, in the brand's gold.
        val under = (plateTop + plateSize + 12f).toInt()
        assertEquals(HdArgb.SEAM_LIGHT, bitmap.getPixel(ShareGridImage.WIDTH / 2, under))
        assertEquals("the line is narrower than the slab", HdArgb.PIT, bitmap.getPixel((plateLeft + 20f).toInt(), under))
        // Nothing inside the plate is gold: the grid has no outcomes to show.
        val gold = (plateTop.toInt() until (plateTop + plateSize).toInt() step 3).sumOf { y ->
            (plateLeft.toInt() until (plateLeft + plateSize).toInt() step 3).count { x -> bitmap.getPixel(x, y) == HdArgb.SEAM }
        }
        assertEquals(0, gold)
        bitmap.recycle()
    }

    @Test
    fun `the words are drawn in the bundled faces when a context is given`() {
        val bundled = ShareGridImage.render(grid, context)
        val platform = ShareGridImage.render(grid)
        // The same picture except for its type: the title's and the caption's pixels differ.
        fun inked(bitmap: Bitmap, top: Int, bottom: Int): Set<Int> {
            val out = HashSet<Int>()
            for (y in top until bottom) for (x in 96 until 984) if (bitmap.getPixel(x, y) != HdArgb.PIT) out += y * ShareGridImage.WIDTH + x
            return out
        }
        val title = inked(bundled, 60, 250)
        assertTrue("the title is drawn: ${title.size} px", title.size > 2_000)
        assertFalse("the title is in the display face, not the platform's bold", title == inked(platform, 60, 250))
        val below = (plateTop + plateSize + 30f).toInt()
        val caption = inked(bundled, below, ShareGridImage.HEIGHT)
        assertTrue("the caption and the featured line are drawn: ${caption.size} px", caption.size > 2_000)
        assertFalse("they are in the mono face", caption == inked(platform, below, ShareGridImage.HEIGHT))
        // The featured line is under the caption, in chalk: gold as ink is for amounts of ORE, and
        // this image has none. The one gold thing in it is the line of light under the slab.
        fun inkOf(colour: Int, top: Int, bottom: Int) = (top until bottom).sumOf { y -> (0 until ShareGridImage.WIDTH).count { x -> bundled.getPixel(x, y) == colour } }
        val featured = inkOf(HdArgb.CHALK, ShareGridImage.HEIGHT - 110, ShareGridImage.HEIGHT - 60)
        assertTrue("POWERED BY ORE is drawn in chalk: $featured px", featured > 50)
        assertEquals("no word is gold", 0, inkOf(HdArgb.SEAM, 0, plateTop.toInt()) + inkOf(HdArgb.SEAM, below, ShareGridImage.HEIGHT))
        assertTrue("the line of light is", inkOf(HdArgb.SEAM, (plateTop + plateSize).toInt(), below) > 1_000)
        // The grid itself does not depend on the faces.
        for (tile in 0 until Board.TILES) {
            val (x, y) = centre(tile)
            assertEquals(bundled.getPixel(x, y), platform.getPixel(x, y))
        }
        bundled.recycle()
        platform.recycle()
    }
}
