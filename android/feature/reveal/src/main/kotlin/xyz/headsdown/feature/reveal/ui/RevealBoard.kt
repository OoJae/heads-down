package xyz.headsdown.feature.reveal.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import xyz.headsdown.core.design.Hd
import xyz.headsdown.feature.reveal.board.BoardFrame
import xyz.headsdown.feature.reveal.board.BoardReplay
import xyz.headsdown.feature.reveal.board.FlashKind
import xyz.headsdown.feature.reveal.haul.Board

const val REVEAL_BOARD_TAG = "reveal-board"

/**
 * The night replayed on ORE's 5x5 board, drawn as the underside of the slab: dark wells, and gold
 * light where the rig dug. The light comes from underneath, as everywhere in this design: a dug
 * well is lit from its lower edge and nearly dark at its upper, brighter the more the rig dug
 * there. (One flat tint of gold over the pit is brown: it reads as a heat map, not as light.)
 * Each round's winning tile flashes in chalk, in gold when it was one of the rig's; when the
 * replay is over, every tile of the rig that came up is solid gold inside a chalk outline. Ember
 * is not used here: it means a problem.
 *
 * Driven by `withFrameNanos`, so it runs at the display's refresh rate (120 Hz where the
 * activity asked for it). The frame is read inside the draw lambda: each tick redraws the
 * canvas without recomposing anything, with the grid and its brushes kept from frame to frame.
 * The loop ends with the replay; after it nothing is drawn.
 *
 * A screen reader hears how the replay ended once, when it ends (a polite live region).
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
    val ink = boardInk()
    Spacer(
        modifier
            .aspectRatio(1f)
            .testTag(REVEAL_BOARD_TAG)
            .semantics {
                contentDescription = description
                liveRegion = LiveRegionMode.Polite
            }
            .drawWithCache {
                // Built when the board is sized, not on every frame of the replay.
                val grid = BoardGrid(size, this, ink)
                onDrawBehind {
                    val frame = if (finished) final else replay.frameAt(elapsed.longValue)
                    drawBoard(grid, frame, ink, hits = if (finished) replay.hitTiles else emptySet())
                }
            },
    )
}

/** The board before the night has arrived: twenty-five dark wells. Still, and nothing to a screen reader. */
@Composable
internal fun EmptyBoard(modifier: Modifier = Modifier) {
    val ink = boardInk()
    Canvas(modifier.aspectRatio(1f).clearAndSetSemantics { }) {
        val grid = BoardGrid(size, this, ink)
        for (tile in 0 until Board.TILES) drawRoundRect(ink.well, grid.origin(tile), grid.cellSize, grid.radius)
    }
}

/**
 * What the board is drawn with. The slab is a dark object in both palettes, so its wells are the
 * darkest colour there is and the marks on them the lightest: the gold light reads on both.
 */
@Immutable
internal class BoardInk(val well: Color, val light: Color, val mark: Color)

@Composable
@ReadOnlyComposable
internal fun boardInk(): BoardInk {
    val colors = Hd.colors
    return if (colors.isDark) {
        BoardInk(well = colors.pit, light = colors.seamLight, mark = colors.chalk)
    } else {
        BoardInk(well = colors.chalk, light = colors.seamLight, mark = colors.pit)
    }
}

/**
 * Where the tiles are: square wells with 4dp between them. And the light of each row: a gradient
 * is laid out in the board's own coordinates, so each of the five rows has its own.
 */
private class BoardGrid(size: Size, density: Density, ink: BoardInk) {
    private val gap = with(density) { 4.dp.toPx() }
    val cell = (size.minDimension - gap * (Board.SIZE - 1)) / Board.SIZE
    val cellSize = Size(cell, cell)
    val radius = with(density) { CornerRadius(2.dp.toPx()) }

    /** A hit's outline: 2dp of chalk just inside the tile's edge, its corners following the tile's. */
    val outline = with(density) { 2.dp.toPx() }
    val outlineSize = Size(cell - outline, cell - outline)
    val outlineRadius = CornerRadius((radius.x - outline / 2).coerceAtLeast(0f))

    private val rowGlow: List<Brush> = List(Board.SIZE) { row ->
        val top = row * (cell + gap)
        Brush.verticalGradient(0f to ink.light.copy(alpha = GLOW_AT_TOP), 1f to ink.light, startY = top, endY = top + cell)
    }

    fun origin(tile: Int) = Offset((tile % Board.SIZE) * (cell + gap), (tile / Board.SIZE) * (cell + gap))

    /** A dug tile's light at full strength: all of it at the lower edge, [GLOW_AT_TOP] of it at the upper. */
    fun glow(tile: Int): Brush = rowGlow[tile / Board.SIZE]
}

/**
 * The gold light at the lower edge of a tile the rig dug: from one dig in a night to the busiest
 * tile. Never a solid tile: solid is a hit.
 */
internal const val GLOW_FAINTEST = 0.22f
internal const val GLOW_BRIGHTEST = 0.9f

/** How much of that light reaches the tile's upper edge. */
internal const val GLOW_AT_TOP = 0.28f

private fun DrawScope.drawBoard(grid: BoardGrid, frame: BoardFrame, ink: BoardInk, hits: Set<Int>) {
    val cell = grid.cell
    for (tile in 0 until Board.TILES) {
        val at = grid.origin(tile)
        drawRoundRect(ink.well, at, grid.cellSize, grid.radius)
        val heat = frame.heat[tile]
        if (tile in hits) {
            drawRoundRect(ink.light, at, grid.cellSize, grid.radius)
        } else if (heat > 0 && frame.maxHeat > 0) {
            val strength = GLOW_FAINTEST + (GLOW_BRIGHTEST - GLOW_FAINTEST) * heat / frame.maxHeat
            drawRoundRect(grid.glow(tile), at, grid.cellSize, grid.radius, alpha = strength)
        }
        if ((frame.litNow ushr tile) and 1 == 1) {
            // The round being replayed: a bright pixel in the tile's corner.
            val px = cell * 0.22f
            drawRect(ink.mark, at + Offset(cell * 0.1f, cell * 0.1f), Size(px, px))
        }
    }
    for (flash in frame.flashes) {
        val color = when (flash.kind) {
            FlashKind.WINNER -> ink.mark.copy(alpha = 0.45f * flash.strength)
            FlashKind.HIT -> ink.light.copy(alpha = flash.strength)
            FlashKind.MOTHERLODE_HIT -> ink.light
        }
        drawRoundRect(color, grid.origin(flash.tile), grid.cellSize, grid.radius)
    }
    for (tile in hits) {
        val at = grid.origin(tile) + Offset(grid.outline / 2, grid.outline / 2)
        drawRoundRect(ink.mark, at, grid.outlineSize, grid.outlineRadius, style = Stroke(grid.outline))
    }
}

/** Where the board's frame starts its entrance: nearly edge-on, like a slab lying face-down. */
internal const val TURN_OVER_FROM_DEGREES = 80f

/** The settle spring is told it has arrived half a degree from flat, so it is over in under 400 ms. */
private const val TURN_OVER_ARRIVED_DEGREES = 0.5f

/**
 * The entrance of the board's frame: the slab is turned over to show its underside, once, on the
 * settle spring. It runs beside the replay and never holds it back (the replay has its own clock).
 * Without [animate], or with the system's animations removed, the frame is flat from the start.
 *
 * Keyed on [replay]: another night turns over again.
 */
@Composable
internal fun rememberTurnOver(replay: Any?, animate: Boolean): Animatable<Float, AnimationVector1D> {
    val motion = Hd.motion
    val turn = remember(replay) { Animatable(if (animate && !motion.reduced) TURN_OVER_FROM_DEGREES else 0f) }
    LaunchedEffect(turn) {
        if (turn.value != 0f) turn.animateTo(0f, motion.settle(visibilityThreshold = TURN_OVER_ARRIVED_DEGREES))
    }
    return turn
}

/**
 * Draws what it is applied to turned by [turn] degrees about its bottom edge: the near edge stays
 * where it is and the rest lies back, so the frame is never drawn wider than its place on the
 * page. Read at draw time: no recomposition.
 */
internal fun Modifier.turnedOver(turn: Animatable<Float, AnimationVector1D>): Modifier = graphicsLayer {
    rotationX = turn.value
    cameraDistance = 16f * density
    transformOrigin = TransformOrigin(0.5f, 1f)
}
