package xyz.headsdown.ui.slab

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import xyz.headsdown.core.design.HdPalette

/** What the rig is doing, as far as the slab shows it. */
enum class SlabState {
    /** No shift: no light, unlit bronze relief on the underside. */
    Cold,

    /** Clocked in, not yet face-down: a thin ember line on the lower rim. */
    Armed,

    /** Mining: every lit row in ORE gold. Rarely seen, because the screen is off when hot. */
    Hot,

    /** Lifted: gold falling toward ember over the grace. */
    Cooling,

    /** The shift broke: no light, like [Cold]. */
    Broken,

    /** Frozen by the user or the program: a pale chalk rim, no gold. */
    Frozen,
}

/**
 * The slab's colours. [pit] is the page behind the hero (the hero never paints it: it is here so
 * the lab and previews can); [face] is the stone; [faceEdge] the lit bevel where there is no
 * shader to compute one; [rim] the chalk of highlights and of the frozen rim; [ink] the colour of
 * marks on the pit, which in the light theme is also the shadow under the slab.
 */
@Immutable
data class SlabPalette(
    val pit: Color,
    val face: Color,
    val faceEdge: Color,
    val emitGold: Color,
    val emitEmber: Color,
    val rim: Color,
    val ink: Color,
    val isLight: Boolean,
) {
    companion object {
        // The values are the design module's: this file writes no colour of its own. The edge is the
        // face lifted a little toward chalk; the light under the slab is the same in both palettes.
        val Dark = SlabPalette(
            pit = HdPalette.Dark.pit,
            face = HdPalette.Dark.slab,
            faceEdge = lerp(HdPalette.Dark.slab, HdPalette.Dark.chalk, 0.13f),
            emitGold = HdPalette.Dark.seamLight,
            emitEmber = HdPalette.Dark.ember,
            rim = HdPalette.Dark.chalk,
            ink = HdPalette.Dark.chalk,
            isLight = false,
        )
        val Light = SlabPalette(
            pit = HdPalette.Light.pit,
            face = HdPalette.Light.chalk,
            faceEdge = lerp(HdPalette.Light.chalk, HdPalette.Dark.chalk, 0.17f),
            emitGold = HdPalette.Light.seamLight,
            emitEmber = HdPalette.Dark.ember,
            rim = HdPalette.Dark.chalk,
            ink = HdPalette.Light.chalk,
            isLight = true,
        )
    }
}

/**
 * Which light a state emits, before a palette gives it a colour. [Cooling] is gold while the heat
 * is 1 and ember where the grace ends.
 */
enum class SlabEmission { Gold, Ember, Cooling, Chalk }

/**
 * Where a state settles: the numbers both renderers consume (the shader's `uLit`, `uHeat`,
 * `uSeam` and `uRim`), without motion.
 */
data class SlabTarget(
    /** Rows lit, 0..5. */
    val lit: Float,
    /** 0..1: how hot the light under the slab is. Cooling starts at 1 and falls to [COOLED]. */
    val heat: Float,
    /** 0..1: the ember line on the lower rim. */
    val seam: Float,
    /** 0..1: the chalk rim light. */
    val rim: Float,
    val emission: SlabEmission,
) {
    companion object {
        /** Where a cooling rig's heat ends. */
        const val COOLED = 0.35f

        /** An armed rig shows at most this many rows, and never in gold. */
        const val ARMED_ROWS = 3
        const val COOLING_ROWS = 3

        /**
         * [heat] is the number of rows the caller wants lit; the state caps it. Cold, Broken and
         * Frozen show none whatever is asked, Armed and Cooling at most three, Hot up to five.
         */
        fun of(state: SlabState, heat: Int): SlabTarget {
            val rows = heat.coerceIn(0, SlabGeometry.TILES)
            return when (state) {
                SlabState.Cold, SlabState.Broken -> SlabTarget(0f, 0f, 0f, 0.15f, SlabEmission.Gold)
                SlabState.Armed -> SlabTarget(minOf(rows, ARMED_ROWS).toFloat(), 0f, 1f, 0.15f, SlabEmission.Ember)
                SlabState.Hot -> SlabTarget(rows.toFloat(), 1f, 1f, 0.2f, SlabEmission.Gold)
                SlabState.Cooling -> SlabTarget(minOf(rows, COOLING_ROWS).toFloat(), 1f, 1f, 0.2f, SlabEmission.Cooling)
                SlabState.Frozen -> SlabTarget(0f, 0f, 0f, 1f, SlabEmission.Chalk)
            }
        }

        /**
         * Rows an ARMED slab lights as the phone turns over, from the pose's lean in degrees:
         * none until the underside starts to show, all three by the time it is presented. Ember
         * only: a rig that is not hot never shows gold.
         */
        fun armedFlipRows(leanDegrees: Float): Float {
            val t = ((leanDegrees - FLIP_FROM_DEGREES) / (FLIP_TO_DEGREES - FLIP_FROM_DEGREES)).coerceIn(0f, 1f)
            return ARMED_ROWS * t
        }

        const val FLIP_FROM_DEGREES = 96f
        const val FLIP_TO_DEGREES = 146f
    }
}

/**
 * What a renderer draws this frame besides the pose. Mutable and reused, like [SlabFrame]: the
 * hero fills it in the draw phase.
 */
class SlabLook {
    /** Rows lit, 0..5, fractional while a row is lighting. */
    var lit = 0f
    var heat = 0f
    var seam = 0f
    var rim = 0.15f

    /** The light's colour this frame. Unlit, it tints the relief of the underside. */
    var emit = Color.Black

    /** The halo's reach beyond the silhouette, px. Zero draws no halo. */
    var glowPx = 0f

    /** Lab switches. The hero leaves them at these values. */
    var grain = 1f
    var emboss = 1f
    var squared = true
    var passes = 1

    fun set(target: SlabTarget, palette: SlabPalette) {
        lit = target.lit
        heat = target.heat
        seam = target.seam
        rim = target.rim
        emit = emission(target.emission, target.heat, palette)
    }

    companion object {
        /**
         * The halo reaches this fraction of the slab's width past the silhouette. With the slab at
         * 0.62 of the hero's width, an upright slab and its halo just fit inside the hero.
         */
        const val GLOW_FRACTION = 0.22f

        /** The light's colour: a cooling slab's falls from gold toward ember with its [heat]. */
        fun emission(emission: SlabEmission, heat: Float, palette: SlabPalette): Color = when (emission) {
            SlabEmission.Gold -> palette.emitGold
            SlabEmission.Ember -> palette.emitEmber
            SlabEmission.Chalk -> palette.rim
            SlabEmission.Cooling -> {
                val cooled = ((1f - heat) / (1f - SlabTarget.COOLED)).coerceIn(0f, 1f)
                lerp(palette.emitGold, palette.emitEmber, cooled)
            }
        }
    }
}

/** One of the two renderers. Called from the draw phase with the frame and look already set. */
interface SlabDrawer {
    fun DrawScope.draw(frame: SlabFrame, look: SlabLook, palette: SlabPalette)
}
