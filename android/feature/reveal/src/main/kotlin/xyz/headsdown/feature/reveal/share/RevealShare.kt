package xyz.headsdown.feature.reveal.share

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.content.FileProvider
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.createBitmap
import xyz.headsdown.core.design.HdArgb
import xyz.headsdown.feature.reveal.haul.Board
import java.io.File
import kotlin.math.roundToInt
import xyz.headsdown.core.design.R as DesignR

/**
 * Draws a [ShareGrid] as a 1080x1350 PNG (4:5, what X and Telegram show uncropped).
 *
 * Always the dark palette of "The Underside": the image leaves the device, and the brand is dark.
 * The grid is the slab's underside, as on the reveal: dark wells in a slab plate, and where the
 * rig dug, chalk (lighter the more it dug there). No tile is gold: on the reveal solid gold means
 * "your tile came up", and this grid carries no outcomes. The only gold here is the line of light
 * under the slab; the featured line is chalk, as on the reveal (gold as ink is for amounts of
 * ORE, and this image has none).
 */
object ShareGridImage {
    const val WIDTH = 1080
    const val HEIGHT = 1350

    /** How much chalk a dug tile takes, by level 1..[ShareGrid.MAX_LEVEL]. */
    private val CHALK_BY_LEVEL = floatArrayOf(0f, 0.2f, 0.48f, 1f)

    /** Opaque: level 0 is the empty well, the others chalk laid over it. All four differ. */
    fun levelColor(level: Int): Int {
        val chalk = CHALK_BY_LEVEL[level.coerceIn(0, ShareGrid.MAX_LEVEL)]
        fun channel(shift: Int): Int {
            val well = (HdArgb.PIT shr shift) and 0xFF
            return (well + (((HdArgb.CHALK shr shift) and 0xFF) - well) * chalk).roundToInt()
        }
        return Color.rgb(channel(16), channel(8), channel(0))
    }

    /**
     * [context] gives the two bundled faces (Big Shoulders Display, IBM Plex Mono). Without one,
     * or if a font cannot be read, the platform's bold and monospace faces stand in.
     */
    fun render(grid: ShareGrid, context: Context? = null): Bitmap {
        val display = face(context, DesignR.font.big_shoulders_display_extrabold) ?: Typeface.DEFAULT_BOLD
        val mono = face(context, DesignR.font.ibm_plex_mono_regular) ?: Typeface.MONOSPACE
        val monoMedium = face(context, DesignR.font.ibm_plex_mono_medium) ?: Typeface.MONOSPACE

        val bitmap = createBitmap(WIDTH, HEIGHT)
        val canvas = Canvas(bitmap)
        canvas.drawColor(HdArgb.PIT)

        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = monoMedium
            color = HdArgb.ASH
            textSize = 32f
            letterSpacing = 0.08f
        }
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = display
            color = HdArgb.CHALK
            textSize = 100f
            letterSpacing = -0.02f
        }
        canvas.drawText("HEADS DOWN", MARGIN, 110f, label)
        canvas.drawText("Night shift", MARGIN, 214f, title)

        // The plate: the slab colour inside a hairline, 6dp corners at this image's 3 px to the dp.
        val plate = RectF(MARGIN, PLATE_TOP, WIDTH - MARGIN, PLATE_TOP + (WIDTH - 2 * MARGIN))
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        fill.color = HdArgb.HAIRLINE_ON_SLAB
        canvas.drawRoundRect(plate, 18f, 18f, fill)
        fill.color = HdArgb.SLAB
        canvas.drawRoundRect(RectF(plate.left + 3f, plate.top + 3f, plate.right - 3f, plate.bottom - 3f), 15f, 15f, fill)

        val inset = 36f
        val gap = 12f
        val cell = (plate.width() - 2 * inset - (Board.SIZE - 1) * gap) / Board.SIZE
        grid.levels.forEachIndexed { i, level ->
            val x = plate.left + inset + (i % Board.SIZE) * (cell + gap)
            val y = plate.top + inset + (i / Board.SIZE) * (cell + gap)
            fill.color = levelColor(level)
            canvas.drawRoundRect(RectF(x, y, x + cell, y + cell), 6f, 6f, fill)
        }

        // The line of light under the slab: narrower than the slab, as in the mark.
        fill.color = HdArgb.SEAM_LIGHT
        canvas.drawRect(plate.left + plate.width() * 0.1f, plate.bottom + 9f, plate.right - plate.width() * 0.1f, plate.bottom + 15f, fill)

        val caption = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = mono
            color = HdArgb.CHALK
            textSize = 34f
        }
        val words = grid.caption()
        // A long night's caption is drawn smaller before it is cut by the edge.
        val wide = caption.measureText(words)
        if (wide > plate.width()) caption.textSize *= plate.width() / wide
        canvas.drawText(words, MARGIN, plate.bottom + 82f, caption)

        label.color = HdArgb.CHALK
        label.textSize = 30f
        canvas.drawText("POWERED BY ORE", MARGIN, plate.bottom + 132f, label)
        return bitmap
    }

    private const val MARGIN = 96f
    private const val PLATE_TOP = 258f

    private fun face(context: Context?, font: Int): Typeface? =
        context?.let { runCatching { ResourcesCompat.getFont(it, font) }.getOrNull() }
}

/**
 * Our own FileProvider subclass, so its manifest entry never collides with another module's
 * provider in the merged manifest.
 */
class RevealShareFileProvider : FileProvider()

/** Builds the share-sheet Intent for the grid image (plus the emoji grid as text). */
object RevealShare {
    private const val DIR = "reveal_share"

    fun authority(context: Context): String = "${context.packageName}.reveal.share"

    /** Writes the PNG to app cache (served only through our non-exported FileProvider). */
    fun writePng(context: Context, grid: ShareGrid, shiftId: Long): File {
        val dir = File(context.cacheDir, DIR).apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() } // keep one image at a time
        val file = File(dir, "heads-down-night-$shiftId.png")
        val bitmap = ShareGridImage.render(grid, context)
        try {
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            bitmap.recycle()
        }
        return file
    }

    fun chooser(context: Context, grid: ShareGrid, shiftId: Long): Intent {
        val file = writePng(context, grid, shiftId)
        val uri = FileProvider.getUriForFile(context, authority(context), file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("image/png")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_TEXT, grid.shareText())
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri("", uri)
        return Intent.createChooser(send, "Share your night")
    }
}
