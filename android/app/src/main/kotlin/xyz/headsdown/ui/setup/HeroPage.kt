package xyz.headsdown.ui.setup

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import xyz.headsdown.core.design.Hd
import xyz.headsdown.core.design.HdDimens
import xyz.headsdown.core.design.components.SectionHeader
import kotlin.math.roundToInt

/**
 * The space a page scrolls in: how tall it is (everything above the dock, the status bar's strip
 * included) and how much of its top the status bar covers. Zero height means "not known": the
 * page is being composed where nothing bounds it (a preview without a screen).
 */
@Immutable
internal data class PageViewport(val heightPx: Int, val topInsetPx: Int)

/**
 * A page whose top is the hero: [content] scrolls under the status bar (the hero's zone runs to
 * the screen's top edge), [dock] (an `ActionDock`) stays at the bottom, and [aboveDock] floats
 * over the page's lower edge without moving anything in it.
 *
 * It is `PinnedBarScreen` with two differences a hero needs: nothing is padded for the status bar
 * (the first thing in [content] does that for itself), and [content] is told the height of the
 * space it scrolls in, in the same measure pass, so the hero can take a share of exactly that.
 * A screen reader reaches the page first, then what floats above the dock, then the dock.
 */
@Composable
internal fun HeroPage(
    scrollState: ScrollState,
    modifier: Modifier = Modifier,
    aboveDock: @Composable BoxScope.() -> Unit = {},
    dock: @Composable () -> Unit,
    content: @Composable ColumnScope.(PageViewport) -> Unit,
) {
    val density = LocalDensity.current
    val topInset = WindowInsets.safeDrawing.getTop(density)
    Column(modifier.fillMaxSize().background(Hd.colors.pit)) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val height = if (constraints.hasBoundedHeight) constraints.maxHeight else 0
            val viewport = remember(height, topInset) { PageViewport(height, topInset) }
            Column(
                Modifier
                    .fillMaxSize()
                    .semantics { isTraversalGroup = true }
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                    .verticalScroll(scrollState),
            ) { content(viewport) }
            // At the top of the page the hero's zone runs under the status bar. Once the page has
            // moved, sentences would pass under the clock and the battery: the strip closes over
            // them. It follows the scroll position, read while drawing; it is not an animation.
            if (topInset > 0) {
                val pit = Hd.colors.pit
                val closedAfter = with(density) { STATUS_STRIP_CLOSES_OVER.toPx() }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(with(density) { topInset.toDp() })
                        .graphicsLayer { alpha = (scrollState.value / closedAfter).coerceIn(0f, 1f) }
                        .background(pit),
                )
            }
            aboveDock()
        }
        dock()
    }
}

/** How far the page has to move before the strip under the status bar is fully closed. */
private val STATUS_STRIP_CLOSES_OVER = 24.dp

/**
 * The first screenful of a hero page. The hero's zone starts at the screen's top edge and takes
 * the height the rest leaves, between [minShare] and [maxShare] of the viewport:
 *
 * - [overlay] lies over the top of the zone, under the status bar (the brand row);
 * - [mark] lies over its lower left corner;
 * - [hero] is given the clear box between the two, so whatever draws it cannot run under them;
 * - [notice] (a banner about something that happened) comes directly under the zone, as long as
 *   [mustFit] still fits on the first screenful under it; when it does not (a long banner at a
 *   large font), the banner follows [mustFit] instead of pushing it off the screen;
 * - [mustFit] stays on the first screenful: when it is tall the zone shrinks for it, below
 *   [minShare] if it has to, until only the two bands are left and the slab is not drawn;
 * - [rest] follows and may fall below the fold.
 *
 * With [ruled] the zone ends on a hairline across the screen.
 *
 * With a [peek], what comes after starts at a known place: that far above the dock when
 * everything here fits with that much to spare, otherwise under the fold, never half-way through
 * a line. Without one the first screenful is as tall as what is in it.
 */
@Composable
internal fun HeroFirstScreen(
    viewport: PageViewport,
    minShare: Float,
    maxShare: Float,
    hero: @Composable () -> Unit,
    overlay: @Composable () -> Unit,
    mustFit: @Composable ColumnScope.() -> Unit,
    modifier: Modifier = Modifier,
    mark: @Composable () -> Unit = {},
    notice: @Composable ColumnScope.() -> Unit = {},
    rest: @Composable ColumnScope.() -> Unit = {},
    peek: Dp? = null,
    ruled: Boolean = false,
) {
    val hairline = Hd.colors.hairline
    Layout(
        content = {
            // Drawn first: a renderer that lets light spill past its box spills under the words.
            Box(Modifier.layoutId(FirstScreenSlot.Hero), propagateMinConstraints = true) { hero() }
            Box(Modifier.layoutId(FirstScreenSlot.Overlay)) { overlay() }
            Box(Modifier.layoutId(FirstScreenSlot.Mark)) { mark() }
            Box(Modifier.layoutId(FirstScreenSlot.Rule).background(hairline))
            Column(Modifier.layoutId(FirstScreenSlot.Notice), content = notice)
            Column(Modifier.layoutId(FirstScreenSlot.MustFit), content = mustFit)
            Column(Modifier.layoutId(FirstScreenSlot.Rest), content = rest)
        },
        modifier = modifier.fillMaxWidth(),
    ) { measurables, constraints ->
        fun slot(id: FirstScreenSlot) = measurables.first { it.layoutId == id }
        val width = constraints.maxWidth
        val across = Constraints.fixedWidth(width)
        val overlayPlaceable = slot(FirstScreenSlot.Overlay).measure(across)
        val markPlaceable = slot(FirstScreenSlot.Mark).measure(Constraints(maxWidth = width))
        val noticePlaceable = slot(FirstScreenSlot.Notice).measure(across)
        val mustPlaceable = slot(FirstScreenSlot.MustFit).measure(across)
        val restPlaceable = slot(FirstScreenSlot.Rest).measure(across)
        val rulePlaceable = slot(FirstScreenSlot.Rule).measure(Constraints.fixed(width, if (ruled) HdDimens.Hairline.roundToPx() else 0))

        val top = viewport.topInsetPx
        val bands = top + overlayPlaceable.height + markPlaceable.height
        // The first screenful, less what is left for the next thing on the page to show itself in.
        val screenful = (viewport.heightPx - (peek?.roundToPx() ?: 0)).coerceAtLeast(0)
        // The notice goes first while the zone, shrunk to its bands, still leaves room for both.
        val noticeFirst = viewport.heightPx <= 0 || bands + noticePlaceable.height + mustPlaceable.height <= screenful
        val must = mustPlaceable.height + if (noticeFirst) noticePlaceable.height else 0
        val after = restPlaceable.height + if (noticeFirst) 0 else noticePlaceable.height
        val zone = if (viewport.heightPx <= 0) {
            bands + UNBOUNDED_SLAB.roundToPx()
        } else {
            val soft = (screenful * minShare).roundToInt()
            val most = (screenful * maxShare).roundToInt()
            // The floor is the share asked for (and room for a small slab, where a large font has
            // made the bands taller than that share), unless that pushes mustFit off the first
            // screenful: then the zone gives way, down to its two bands.
            val floor = maxOf(bands, minOf(maxOf(soft, bands + SMALLEST_SLAB.roundToPx()), screenful - must))
            val wanted = (screenful - must - after).coerceIn(floor, maxOf(floor, most))
            // A slab with less than this to be drawn in is a smudge: the bands close over it.
            if (wanted - bands < SMALLEST_SLAB.roundToPx()) bands else wanted
        }
        val heroPlaceable = slot(FirstScreenSlot.Hero).measure(Constraints.fixed(width, (zone - bands).coerceAtLeast(0)))
        // All of the peek or none of it: what does not leave room for the whole peek fills the
        // viewport, and the next thing starts under the fold instead of half-way into it.
        val content = zone + must + after
        val height = when {
            peek == null -> content
            content <= screenful -> screenful
            else -> maxOf(content, viewport.heightPx)
        }

        layout(width, height) {
            heroPlaceable.place(0, top + overlayPlaceable.height)
            overlayPlaceable.place(0, top)
            markPlaceable.place(0, zone - markPlaceable.height)
            rulePlaceable.place(0, zone)
            if (noticeFirst) {
                noticePlaceable.place(0, zone)
                mustPlaceable.place(0, zone + noticePlaceable.height)
                restPlaceable.place(0, zone + must)
            } else {
                mustPlaceable.place(0, zone)
                noticePlaceable.place(0, zone + mustPlaceable.height)
                restPlaceable.place(0, zone + mustPlaceable.height + noticePlaceable.height)
            }
        }
    }
}

private enum class FirstScreenSlot { Hero, Overlay, Mark, Rule, Notice, MustFit, Rest }

/** The least the slab is drawn in. With less room than this (a very large font) it is not drawn at all. */
private val SMALLEST_SLAB = 56.dp

/** The slab's box where no viewport is known. */
private val UNBOUNDED_SLAB = 200.dp

/** A section of a page: its name over a hairline, then its content on the bare page, inside the margins. */
@Composable
internal fun PageSection(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = HdDimens.Margin),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionHeader(title)
        content()
    }
}

/** A sentence: the platform face at 16sp on a 24sp line. Chalk says it, ash adds to it. */
@Composable
internal fun Body(text: String, modifier: Modifier = Modifier, color: Color = Hd.colors.chalk) {
    BasicText(text, modifier, style = Hd.type.body.copy(color = color))
}
