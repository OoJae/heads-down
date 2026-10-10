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
 * COMPATIBILITY SHIM: the widget's old colour names, ARGB as `Long`, with the values of "The
 * Underside" (`:core:design`). The same shim as the app's `HdColors` (an app unit test keeps them
 * equal). The `*_ON_LIGHT` variants are for dynamic colour in day mode: the light palette's ember
 * and gold, at least 4.5:1 on light Material surfaces.
 */
object WidgetPalette {
    const val CHARCOAL = HdArgb.PIT.toLong() and OPAQUE
    const val CHARCOAL_RAISED = HdArgb.SLAB.toLong() and OPAQUE
    const val CHARCOAL_OUTLINE = HdArgb.HAIRLINE_ON_PIT.toLong() and OPAQUE
    const val EMBER = HdArgb.EMBER.toLong() and OPAQUE
    const val ORE_GOLD = HdArgb.SEAM.toLong() and OPAQUE
    const val ASH = HdArgb.CHALK.toLong() and OPAQUE
    const val ASH_MUTED = HdArgb.ASH.toLong() and OPAQUE

    const val EMBER_ON_LIGHT = HdArgb.Day.EMBER.toLong() and OPAQUE
    const val ORE_GOLD_ON_LIGHT = HdArgb.Day.SEAM.toLong() and OPAQUE

    // Colours the new system has no role for (HdCompat): they leave with the layouts that name them.
    const val FROST = HdCompat.FROST.toLong() and OPAQUE
    const val COOLING = HdCompat.COOLING.toLong() and OPAQUE
    const val COOLING_ON_LIGHT = HdCompat.COOLING_DAY.toLong() and OPAQUE
    const val FROST_ON_LIGHT = HdCompat.FROST_DAY.toLong() and OPAQUE
}

/** An ARGB `Int` widened to the unsigned `Long` Compose's `Color(Long)` takes. */
private const val OPAQUE = 0xFFFFFFFFL

/** Accent colours for one [WidgetColorMode]. */
class WidgetAccents(private val mode: WidgetColorMode) {
    private fun accent(onDark: Long, onLight: Long): ColorProvider = when (mode) {
        WidgetColorMode.BRAND -> ColorProvider(Color(onDark))
        WidgetColorMode.DYNAMIC -> DayNightColorProvider(day = Color(onLight), night = Color(onDark))
    }

    val ember = accent(WidgetPalette.EMBER, WidgetPalette.EMBER_ON_LIGHT)
    val gold = accent(WidgetPalette.ORE_GOLD, WidgetPalette.ORE_GOLD_ON_LIGHT)
    val cooling = accent(WidgetPalette.COOLING, WidgetPalette.COOLING_ON_LIGHT)
    val frost = accent(WidgetPalette.FROST, WidgetPalette.FROST_ON_LIGHT)

    fun forHeat(heat: RigHeat, muted: ColorProvider): ColorProvider = when (heat) {
        RigHeat.HOT, RigHeat.ARMED -> ember
        RigHeat.COOLING -> cooling
        RigHeat.FROZEN -> frost
        RigHeat.COLD -> muted
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

    /** Charcoal in day and night alike: this rig lives on a nightstand. */
    val brand: ColorProviders = ColorProviders(light = brandScheme, dark = brandScheme)

    fun providers(mode: WidgetColorMode): ColorProviders = when (mode) {
        WidgetColorMode.DYNAMIC -> DynamicThemeColorProviders
        WidgetColorMode.BRAND -> brand
    }
}
