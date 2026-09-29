package xyz.headsdown.feature.reveal.share

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.content.FileProvider
import androidx.core.graphics.createBitmap
import xyz.headsdown.feature.reveal.haul.Board
import java.io.File

/** Draws a [ShareGrid] as a 1080x1350 PNG (4:5, what X and Telegram show uncropped). */
object ShareGridImage {
    const val WIDTH = 1080
    const val HEIGHT = 1350

    private const val CHARCOAL = 0xFF121314.toInt()
    private const val CHARCOAL_RAISED = 0xFF1C1D20.toInt()
    private const val EMBER = 0xFFFF6A1A.toInt()
    private const val GOLD = 0xFFF2B233.toInt()
    private const val ASH = 0xFFECE7E1.toInt()
    private const val ASH_MUTED = 0xFF9A948D.toInt()

    fun levelColor(level: Int): Int = when (level) {
        0 -> CHARCOAL_RAISED
        1 -> (0x66 shl 24) or (EMBER and 0xFFFFFF)
        2 -> EMBER
        else -> GOLD
    }

    fun render(grid: ShareGrid): Bitmap {
        val bitmap = createBitmap(WIDTH, HEIGHT)
        val canvas = Canvas(bitmap)
        canvas.drawColor(CHARCOAL)
        val mono = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.MONOSPACE
            color = ASH_MUTED
            textSize = 40f
            letterSpacing = 0.2f
        }
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            color = ASH
            textSize = 64f
        }
        canvas.drawText("HEADS DOWN", 96f, 150f, mono)
        canvas.drawText("Night shift", 96f, 235f, title)

        val cell = 150f
        val gap = 18f
        val boardSize = Board.SIZE * cell + (Board.SIZE - 1) * gap
        val left = (WIDTH - boardSize) / 2f
        val top = 300f
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        grid.levels.forEachIndexed { i, level ->
            val x = left + (i % Board.SIZE) * (cell + gap)
            val y = top + (i / Board.SIZE) * (cell + gap)
            fill.color = levelColor(level)
            canvas.drawRoundRect(RectF(x, y, x + cell, y + cell), 14f, 14f, fill)
        }

        val caption = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ASH
            textSize = 44f
        }
        canvas.drawText(grid.caption(), 96f, top + boardSize + 110f, caption)
        mono.textSize = 34f
        canvas.drawText("POWERED BY ORE", 96f, HEIGHT - 90f, mono)
        return bitmap
    }
}

/** Builds the share-sheet Intent for the grid image (plus the emoji grid as text). */
object RevealShare {
    private const val DIR = "reveal_share"

    fun authority(context: Context): String = "${context.packageName}.reveal.share"

    /** Writes the PNG to app cache (served only through our non-exported FileProvider). */
    fun writePng(context: Context, grid: ShareGrid, shiftId: Long): File {
        val dir = File(context.cacheDir, DIR).apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() } // keep one image at a time
        val file = File(dir, "heads-down-night-$shiftId.png")
        val bitmap = ShareGridImage.render(grid)
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
