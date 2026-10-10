package xyz.headsdown.core.design.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import xyz.headsdown.core.design.Hd

/** What the slab is doing. The hero shows it; the words beside the hero say it. */
enum class SlabState { Cold, Armed, Hot, Cooling, Broken, Frozen }

/**
 * What a hero renderer is asked to show.
 *
 * @param heat how much of the rig is lit, 0..5 (clamped).
 * @param scrollPx how far the screen under the hero has scrolled, read at draw time so a scroll
 *   moves the hero without recomposing it.
 * @param interactive whether the hero may be touched (flipped, knocked) at all.
 * @param enter whether to play the hero's entrance once. A renderer ignores it when motion is reduced.
 * @param onFlipped called when the person has turned the slab over.
 * @param onKnock called when the person has knocked on it.
 */
@Immutable
class HeroSpec(
    val state: SlabState,
    heat: Int,
    val scrollPx: () -> Float = { 0f },
    val interactive: Boolean = false,
    val enter: Boolean = false,
    val onFlipped: () -> Unit = {},
    val onKnock: () -> Unit = {},
) {
    val heat: Int = heat.coerceIn(0, MAX_HEAT)

    companion object {
        const val MAX_HEAT = 5
    }
}

/**
 * Who draws the hero. The default is [StaticSlab]: a plain Canvas, so a screen can be built,
 * previewed and unit-tested before the 3D hero exists and wherever it cannot run. The app
 * provides the real renderer at its root.
 *
 * A renderer applies the [Modifier] it is given to its root and draws only: [Hero] has already
 * replaced its semantics.
 */
val LocalHeroRenderer = staticCompositionLocalOf<@Composable (HeroSpec, Modifier) -> Unit> {
    { spec, modifier -> StaticSlab(spec, modifier) }
}

/**
 * The hero slot. Draws [spec] with whatever renderer is provided, and hides the drawing from
 * accessibility except for the one [contentDescription] given here (null: the hero is decoration
 * and the screen's own words describe the rig).
 */
@Composable
fun Hero(
    spec: HeroSpec,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    val described = modifier.clearAndSetSemantics {
        if (contentDescription != null) this.contentDescription = contentDescription
    }
    LocalHeroRenderer.current(spec, described)
}

/**
 * The stand-in hero: the slab from a low angle, a highlight along its top edge, and under it up
 * to five square lights. No text, no animation, no sensor, no shader: safe in a preview and in a
 * Robolectric composition.
 *
 * Hot is gold; armed and cooling are ember; a frozen rig's lights are ash; a cold or broken one
 * has none. The colour is never the only signal: a screen says the state in words.
 */
@Composable
fun StaticSlab(spec: HeroSpec, modifier: Modifier = Modifier) {
    val colors = Hd.colors
    // The slab is a dark object in both palettes. On the dark pit it has to be lifted to be seen.
    val face = if (colors.isDark) colors.chalk.copy(alpha = 0.16f).compositeOver(colors.slab) else colors.chalk
    val underside = if (colors.isDark) colors.slab else colors.chalk.copy(alpha = 0.78f).compositeOver(colors.pit)
    val edge = (if (colors.isDark) colors.chalk else colors.pit).copy(alpha = 0.45f)
    val unlit = colors.hairline
    val lit = when (spec.state) {
        SlabState.Cold, SlabState.Broken -> 0
        SlabState.Armed -> spec.heat.coerceAtLeast(1)
        SlabState.Hot, SlabState.Cooling, SlabState.Frozen -> spec.heat
    }
    val light = when (spec.state) {
        SlabState.Hot -> colors.seamLight
        SlabState.Frozen -> colors.ash
        else -> colors.ember
    }
    Canvas(modifier.fillMaxWidth().height(160.dp)) {
        // The mark's proportions (a 20 by 14 box: hull 20x8, a gap, the seam), scaled to fit.
        val unit = minOf(size.width / 26f, size.height / 18f)
        val left = (size.width - 20f * unit) / 2f
        val top = (size.height - 14f * unit) / 2f
        fun at(x: Float, y: Float) = Offset(left + x * unit, top + y * unit)

        drawRect(face, at(0f, 0f), Size(20f * unit, 4f * unit))
        val under = Path().apply {
            moveTo(left, top + 4f * unit)
            lineTo(left + 20f * unit, top + 4f * unit)
            lineTo(left + 17f * unit, top + 8f * unit)
            lineTo(left + 3f * unit, top + 8f * unit)
            close()
        }
        drawPath(under, underside)
        drawRect(edge, at(0f, 0f), Size(20f * unit, 1.dp.toPx()))

        // Five square lights where the mark has its seam.
        val square = 2f * unit
        val gap = (16f * unit - HeroSpec.MAX_HEAT * square) / (HeroSpec.MAX_HEAT - 1)
        repeat(HeroSpec.MAX_HEAT) { i ->
            drawRect(
                color = if (i < lit) light else unlit,
                topLeft = Offset(left + 2f * unit + i * (square + gap), top + 12f * unit),
                size = Size(square, square),
            )
        }
    }
}
