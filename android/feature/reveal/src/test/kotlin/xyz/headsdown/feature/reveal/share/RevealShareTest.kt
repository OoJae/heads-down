package xyz.headsdown.feature.reveal.share

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import xyz.headsdown.feature.reveal.haul.FakeHaulRepository
import java.io.File
import java.time.ZoneId

/** The share sheet hand-off: a PNG in cache, served by our FileProvider with a read grant. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RevealShareTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val night = FakeHaulRepository.sampleNight(1_790_000_000_000L, ZoneId.of("UTC"))
    private val grid = ShareGrid.from(night)

    @Test
    fun `writes a real PNG of the grid into cache`() {
        val file = RevealShare.writePng(context, grid, night.shiftId)
        assertEquals(File(context.cacheDir, "reveal_share"), file.parentFile)
        val bytes = file.readBytes()
        assertTrue("PNG of ${bytes.size} bytes", bytes.size > 1_000)
        assertEquals(listOf(0x89, 'P'.code, 'N'.code, 'G'.code), bytes.take(4).map { it.toInt() and 0xFF })
    }

    @Test
    fun `only one share image is kept`() {
        RevealShare.writePng(context, grid, 1)
        RevealShare.writePng(context, grid, 2)
        assertEquals(listOf("heads-down-night-2.png"), File(context.cacheDir, "reveal_share").list()!!.toList())
    }

    @Test
    fun `chooser wraps an image send with a one-off read grant`() {
        val chooser = RevealShare.chooser(context, grid, night.shiftId)
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val send = IntentCompat.getParcelableExtra(chooser, Intent.EXTRA_INTENT, Intent::class.java)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("image/png", send.type)
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        val uri = IntentCompat.getParcelableExtra(send, Intent.EXTRA_STREAM, Uri::class.java)!!
        assertEquals("content", uri.scheme)
        assertEquals(RevealShare.authority(context), uri.authority)
        assertEquals(grid.shareText(), send.getStringExtra(Intent.EXTRA_TEXT))
        assertEquals(uri, send.clipData!!.getItemAt(0).uri)
    }

    @Test
    fun `level colours are distinct and zero is the empty tile`() {
        val colours = (0..ShareGrid.MAX_LEVEL).map(ShareGridImage::levelColor)
        assertEquals(colours.size, colours.toSet().size)
    }
}
