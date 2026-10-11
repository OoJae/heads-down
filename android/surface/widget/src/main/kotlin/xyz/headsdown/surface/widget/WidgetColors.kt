package xyz.headsdown.surface.widget

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.glance.color.ColorProviders
import androidx.glance.color.DynamicThemeColorProviders
import androidx.glance.material3.ColorProviders
import androidx.glance.unit.ColorProvider
import xyz.headsdown.core.design.HdArgb
import xyz.headsdown.core.design.HdCompat
import androidx.glance.color.ColorProvider as DayNightColorProvider

enum class WidgetColorMode {
    /** Material You: surfaces follow the wallpaper, day and night. */
    DYNAMIC,

    /** The Heads Down look: the pit in both day and night. The default (WidgetStateStore). */
    BRAND,
}

/** Dynamic colour where the platform provides it; the Heads Down palette as the fallback. */
object WidgetColorPolicy {
    const val DYNAMIC_COLOR_MIN_SDK = 31

    fun mode(sdkInt: Int, preferBrand: Boolean = false): WidgetColorMode =
        if (sdkInt >= DYNAMIC_COLOR_MIN_SDK && !preferBrand) WidgetColorMode.DYNAMIC else WidgetColorMode.BRAND
}

/**
 * The widget's colours, ARGB as `Long`, with the values of "The Underside" (`:core:design`,
 * [HdArgb]): the pit, the slab, the hairline on the pit, chalk, ash, ember and the ORE gold. The
 * names are the ones from before the redesign, kept because tests in this module and in the app
 * compare them by name. The `*_ON_LIGHT` variants are the light palette's ember and gold, for a
 * widget that follows the wallpaper in day mode: at least 4.5:1 on light Material surfaces.
 */
object WidgetPalette {
    const val CHARCOAL = HdArgb.PIT.toLong() and OPAQUE
    const val CHARCOAL_RAISED = HdArgb.SLAB.toLong() and OPAQUE
    const val CHARCOAL_OUTLINE = HdArgb.HAIRLINE_ON_PIT.toLong() and OPAQUE
    const val EMBER = HdArgb.EMBER.toLong() and OPAQUE
    const val ORE_GOLD = HdArgb.SEAM.toLong() and OPAQUE
    const val ASH = HdArgb.CHALK.toLong() and OPAQUE
    const val ASH_MUTED = HdArgb.ASH.toLong() and OPAQUE

    /** The gold as light: the lit pixels and the seam line of a hot rig. */
    const val SEAM_LIGHT = HdArgb.SEAM_LIGHT.toLong() and OPAQUE

    const val EMBER_ON_LIGHT = HdArgb.Day.EMBER.toLong() and OPAQUE
    const val ORE_GOLD_ON_LIGHT = HdArgb.Day.SEAM.toLong() and OPAQUE

    // NOT DRAWN ANY MORE. The frozen blue and the cooling orange of the old widget (HdCompat):
    // a cooling rig is ember now and a frozen one is chalk, each beside its word. The four names
    // stay only because WidgetColorsTest here and ThemeContrastTest in the app still compare
    // them with HdCompat; they go when those comparisons go. WidgetBrandTest fails if anything
    // in this module reads them again.
    const val FROST = HdCompat.FROST.toLong() and OPAQUE
    const val COOLING = HdCompat.COOLING.toLong() and OPAQUE
    const val COOLING_ON_LIGHT = HdCompat.COOLING_DAY.toLong() and OPAQUE
    const val FROST_ON_LIGHT = HdCompat.FROST_DAY.toLong() and OPAQUE
}

/** An ARGB `Int` widened to the unsigned `Long` Compose's `Color(Long)` takes. */
private const val OPAQUE = 0xFFFFFFFFL

/**
 * The accents of one [WidgetColorMode], and which of them a rig state wears.
 *
 * The rules are the design system's: ember says armed and cooling, never alone (the word is
 * beside it); gold is ink only for an amount of ORE, and otherwise it is light, the lit pixels
 * and the line under a hot rig; a frozen rig has no colour of its own, so its word is plain ink.
 */
class WidgetAccents(private val mode: WidgetColorMode) {
    private fun accent(onDark: Long, onLight: Long): ColorProvider = when (mode) {
        WidgetColorMode.BRAND -> ColorProvider(Color(onDark))
        WidgetColorMode.DYNAMIC -> DayNightColorProvider(day = Color(onLight), night = Color(onDark))
    }

    val ember = accent(WidgetPalette.EMBER, WidgetPalette.EMBER_ON_LIGHT)

    /** ORE gold as ink: an amount of ORE and nothing else. */
    val gold = accent(WidgetPalette.ORE_GOLD, WidgetPalette.ORE_GOLD_ON_LIGHT)

    /**
     * The gold as light. On a light Material You surface the light itself would be under 2:1
     * (1.8:1 on Material's baseline surface) and vanish, so there it is drawn in the gold that
     * reads as ink.
     */
    val light = accent(WidgetPalette.SEAM_LIGHT, WidgetPalette.ORE_GOLD_ON_LIGHT)

    /** The ink of the rig word ("Rig hot", "Cooling", ...). [ink] is the surface's text colour. */
    fun word(heat: RigHeat, ink: ColorProvider, muted: ColorProvider): ColorProvider = when (heat) {
        RigHeat.ARMED, RigHeat.COOLING -> ember
        RigHeat.HOT, RigHeat.FROZEN -> ink
        RigHeat.COLD -> muted
    }

    /** The colour of the lit heat pixels, as on the app's slab: gold when hot, ember before and after, ash when frozen. */
    fun lights(heat: RigHeat, muted: ColorProvider): ColorProvider = when (heat) {
        RigHeat.HOT -> light
        RigHeat.ARMED, RigHeat.COOLING -> ember
        RigHeat.FROZEN, RigHeat.COLD -> muted
    }
}

object WidgetColors {
    private val brandScheme = darkColorScheme(
        primary = Color(WidgetPalette.EMBER),
        onPrimary = Color(WidgetPalette.CHARCOAL),
        secondary = Color(WidgetPalette.ORE_GOLD),
        onSecondary = Color(WidgetPalette.CHARCOAL),
        background = Color(WidgetPalette.CHARCOAL),
        onBackground = Color(WidgetPalette.ASH),
        surface = Color(WidgetPalette.CHARCOAL),
        onSurface = Color(WidgetPalette.ASH),
        surfaceVariant = Color(WidgetPalette.CHARCOAL_RAISED),
        onSurfaceVariant = Color(WidgetPalette.ASH_MUTED),
        outline = Color(WidgetPalette.CHARCOAL_OUTLINE),
        secondaryContainer = Color(WidgetPalette.CHARCOAL),
    )

    /** The pit in day and night alike: this rig lives on a nightstand. */
    val brand: ColorProviders = ColorProviders(light = brandScheme, dark = brandScheme)

    fun providers(mode: WidgetColorMode): ColorProviders = when (mode) {
        WidgetColorMode.DYNAMIC -> DynamicThemeColorProviders
        WidgetColorMode.BRAND -> brand
    }
}
