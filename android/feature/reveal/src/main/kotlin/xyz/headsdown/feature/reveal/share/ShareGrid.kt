package xyz.headsdown.feature.reveal.share

import xyz.headsdown.feature.reveal.haul.Board
import xyz.headsdown.feature.reveal.haul.HaulFormat
import xyz.headsdown.feature.reveal.haul.HaulSummary

/**
 * The spoiler-free share grid: where the rig dug last night and how long it stayed dark, and
 * nothing else. No ORE amount, no SOL, no price, no winning tiles, no Motherlode, no wallet.
 * A room's Muster reveal stays a surprise, and the image leaks nothing about the user's funds.
 */
data class ShareGrid(
    /** 0 (no digs) .. 3 (the rig's busiest tiles), row-major 5x5. */
    val levels: List<Int>,
    val roundsDark: Int,
    val streak: Int,
    val darkMillis: Long,
) {
    init {
        require(levels.size == Board.TILES && levels.all { it in 0..MAX_LEVEL })
    }

    /** The emoji version for the share text (X and Telegram render it as a grid). */
    fun emojiRows(): List<String> = levels.chunked(Board.SIZE).map { row -> row.joinToString("") { EMOJI[it] } }

    fun caption(): String = "${HaulFormat.count(roundsDark)} rounds dark · ${HaulFormat.duration(darkMillis)} · streak $streak"

    fun shareText(): String = buildString {
        append("Heads Down · night shift\n")
        emojiRows().forEach { append(it).append('\n') }
        append(caption()).append('\n')
        append("Powered by ORE")
    }

    companion object {
        const val MAX_LEVEL = 3
        private val EMOJI = listOf("⬛", "🟫", "🟧", "🟨")

        /** Only the dug masks and counts are read: outcomes cannot leak into the grid. */
        fun from(h: HaulSummary): ShareGrid {
            val perTile = IntArray(Board.TILES)
            h.rounds.forEach { r -> for (t in 0 until Board.TILES) if (r.dugTile(t)) perTile[t]++ }
            val max = perTile.max()
            val levels = perTile.map { c ->
                when {
                    c == 0 || max == 0 -> 0
                    // Ceil into 1..3 so a single dig still shows.
                    else -> ((c * MAX_LEVEL + max - 1) / max).coerceIn(1, MAX_LEVEL)
                }
            }
            return ShareGrid(levels, h.roundsDark, h.streak.after, h.darkMillis)
        }
    }
}
