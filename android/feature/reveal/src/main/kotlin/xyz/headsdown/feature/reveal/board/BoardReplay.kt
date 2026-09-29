package xyz.headsdown.feature.reveal.board

import xyz.headsdown.feature.reveal.haul.Board
import xyz.headsdown.feature.reveal.haul.RoundReplay

enum class FlashKind {
    /** The winning tile of a round the rig sat out or dug elsewhere. */
    WINNER,

    /** The winning tile was one of the rig's. */
    HIT,

    /** The rig's tile won in a Motherlode round. */
    MOTHERLODE_HIT,
}

data class TileFlash(val tile: Int, val kind: FlashKind, /** 1 = just flashed, fading to 0. */ val strength: Float)

/** One animation frame of the board. */
data class BoardFrame(
    /** Rounds replayed so far, 0..total. */
    val roundsShown: Int,
    val totalRounds: Int,
    /** Digs per tile so far (the "heat" the rig laid on each tile). */
    val heat: List<Int>,
    val maxHeat: Int,
    /** Tiles that won in the rounds just replayed, newest strongest. */
    val flashes: List<TileFlash>,
    /** Tiles the rig dug in the round being replayed right now. */
    val litNow: Int,
) {
    val done: Boolean get() = roundsShown >= totalRounds
}

/**
 * Pure, frame-rate independent replay of a night: frame(t) depends only on elapsed time, so a
 * 120 Hz display simply samples it more often. Heat per tile is a prefix sum, so every frame is
 * O(25 + flash window) whatever the night's length.
 */
class BoardReplay(
    private val rounds: List<RoundReplay>,
    val durationMillis: Long = durationFor(rounds.size),
    private val flashMillis: Long = FLASH_MILLIS,
) {
    private val prefix: Array<IntArray> = Array(rounds.size + 1) { IntArray(Board.TILES) }.also { p ->
        rounds.forEachIndexed { i, r ->
            for (t in 0 until Board.TILES) p[i + 1][t] = p[i][t] + if (r.dugTile(t)) 1 else 0
        }
    }

    private val millisPerRound: Double = if (rounds.isEmpty()) 0.0 else durationMillis.toDouble() / rounds.size

    fun frameAt(elapsedMillis: Long): BoardFrame {
        val n = rounds.size
        val t = elapsedMillis.coerceIn(0, durationMillis)
        val shown = if (n == 0) 0 else if (t >= durationMillis) n else (t / millisPerRound).toInt().coerceIn(0, n)
        val heat = prefix[shown].toList()
        val flashes = ArrayList<TileFlash>()
        if (shown > 0 && elapsedMillis < durationMillis + flashMillis) {
            var i = shown - 1
            while (i >= 0) {
                val age = elapsedMillis - (i + 1) * millisPerRound
                if (age > flashMillis) break
                val r = rounds[i]
                val kind = when {
                    r.hit && r.motherlode -> FlashKind.MOTHERLODE_HIT
                    r.hit -> FlashKind.HIT
                    else -> FlashKind.WINNER
                }
                flashes += TileFlash(r.winningTile, kind, (1.0 - age.coerceAtLeast(0.0) / flashMillis).toFloat())
                i--
            }
        }
        val litNow = if (shown in 1..n && t < durationMillis) rounds[shown - 1].dugMask else 0
        return BoardFrame(shown, n, heat, heat.max(), flashes, litNow)
    }

    /** The final board: where the rig dug all night, and which of its tiles came up. */
    fun finalFrame(): BoardFrame = frameAt(durationMillis + flashMillis + 1)

    /** Tiles of the rig that won at least once (outlined on the final board). */
    val hitTiles: Set<Int> = rounds.filter { it.hit }.map { it.winningTile }.toSet()

    companion object {
        const val FLASH_MILLIS = 160L
        const val MAX_DURATION_MILLIS = 3_000L
        const val MIN_DURATION_MILLIS = 1_200L
        const val MILLIS_PER_ROUND = 40L

        /** Three seconds for a full night, a little faster per round for short shifts. */
        fun durationFor(rounds: Int): Long =
            (rounds * MILLIS_PER_ROUND).coerceIn(MIN_DURATION_MILLIS, MAX_DURATION_MILLIS)
    }
}
