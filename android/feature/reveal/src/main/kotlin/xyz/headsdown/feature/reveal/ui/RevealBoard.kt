package xyz.headsdown.feature.reveal.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import xyz.headsdown.feature.reveal.board.BoardFrame
import xyz.headsdown.feature.reveal.board.BoardReplay
import xyz.headsdown.feature.reveal.board.FlashKind
import xyz.headsdown.feature.reveal.haul.Board

const val REVEAL_BOARD_TAG = "reveal-board"

/**
 * The night replayed on ORE's 5x5 board. Tiles the rig dug glow ember (brighter the more it dug
 * there); each round's winning tile flashes, gold when it was one of the rig's.
 *
 * Driven by `withFrameNanos`, so it runs at the display's refresh rate (120 Hz where the
 * activity asked for it). The frame is read inside the draw lambda: each tick redraws the
 * canvas without recomposing anything.
 */
@Composable
fun RevealBoard(
    replay: BoardReplay,
    animate: Boolean,
    modifier: Modifier = Modifier,
    onStarted: () -> Unit = {},
    onFinished: () -> Unit = {},
) {
    val total = replay.durationMillis + BoardReplay.FLASH_MILLIS
    val elapsed = remember(replay) { mutableLongStateOf(if (animate) 0L else total + 1) }
    var finished by remember(replay) { mutableStateOf(!animate) }
    val started by rememberUpdatedState(onStarted)
    val ended by rememberUpdatedState(onFinished)

    LaunchedEffect(replay, animate) {
        if (!animate) {
            ended()
            return@LaunchedEffect
        }
        started()
        val origin = withFrameNanos { it }
        while (elapsed.longValue <= total) {
            elapsed.longValue = (withFrameNanos { it } - origin) / 1_000_000
        }
        finished = true
        ended()
    }

    val final = remember(replay) { replay.finalFrame() }
    val rounds = final.totalRounds
    val description = if (finished) {
        val hits = replay.hitTiles.size
        "Board replay finished: $rounds rounds. Your tiles came up on $hits of 25 squares."
    } else {
        "Replaying $rounds rounds on the ORE board"
    }
    Canvas(
        modifier
            .aspectRatio(1f)
            .testTag(REVEAL_BOARD_TAG)
            .semantics { contentDescription = description },
    ) {
        val frame = if (finished) final else replay.frameAt(elapsed.longValue)
        drawBoard(frame, outlineHits = if (finished) replay.hitTiles else emptySet())
    }
}

private fun DrawScope.drawBoard(frame: BoardFrame, outlineHits: Set<Int>) {
    val gap = size.minDimension * 0.025f
    val cell = (size.minDimension - gap * (Board.SIZE - 1)) / Board.SIZE
    val radius = CornerRadius(cell * 0.12f)
    fun origin(tile: Int) = Offset((tile % Board.SIZE) * (cell + gap), (tile / Board.SIZE) * (cell + gap))
    val cellSize = Size(cell, cell)

    for (tile in 0 until Board.TILES) {
        val at = origin(tile)
        drawRoundRect(RevealColors.CharcoalRaised, at, cellSize, radius)
        val heat = frame.heat[tile]
        if (heat > 0 && frame.maxHeat > 0) {
            val alpha = 0.22f + 0.78f * heat / frame.maxHeat
            drawRoundRect(RevealColors.Ember.copy(alpha = alpha), at, cellSize, radius)
        }
        if ((frame.litNow ushr tile) and 1 == 1) {
            // The round being replayed: a bright pixel in the tile's corner.
            val px = cell * 0.22f
            drawRect(RevealColors.Ash, at + Offset(cell * 0.1f, cell * 0.1f), Size(px, px))
        }
    }
    for (flash in frame.flashes) {
        val at = origin(flash.tile)
        val color = when (flash.kind) {
            FlashKind.WINNER -> RevealColors.Ash.copy(alpha = 0.45f * flash.strength)
            FlashKind.HIT -> RevealColors.OreGold.copy(alpha = flash.strength)
            FlashKind.MOTHERLODE_HIT -> RevealColors.OreGold.copy(alpha = 1f)
        }
        drawRoundRect(color, at, cellSize, radius)
    }
    for (tile in outlineHits) {
        val stroke = cell * 0.06f
        val at = origin(tile) + Offset(stroke / 2, stroke / 2)
        drawRoundRect(RevealColors.OreGold, at, Size(cell - stroke, cell - stroke), radius, style = Stroke(stroke))
    }
}
