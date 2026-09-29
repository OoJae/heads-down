package xyz.headsdown.surface.widget

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.glance.color.ColorProviders
import androidx.glance.color.DynamicThemeColorProviders
import androidx.glance.material3.ColorProviders
import androidx.glance.unit.ColorProvider
import androidx.glance.color.ColorProvider as DayNightColorProvider

enum class WidgetColorMode {
    /** Material You: surfaces follow the wallpaper, day and night. */
    DYNAMIC,

    /** The Heads Down look: charcoal in both day and night. */
    BRAND,
}

/** Dynamic colour where the platform provides it; the Heads Down palette as the fallback. */
object WidgetColorPolicy {
    const val DYNAMIC_COLOR_MIN_SDK = 31

    fun mode(sdkInt: Int, preferBrand: Boolean = false): WidgetColorMode =
        if (sdkInt >= DYNAMIC_COLOR_MIN_SDK && !preferBrand) WidgetColorMode.DYNAMIC else WidgetColorMode.BRAND
}

/**
 * The widget palette, ARGB. Mirrors the app's `HdColors` (an app unit test keeps them equal).
 * The `*_ON_LIGHT` variants are the same hues darkened to at least 4.5:1 on light Material
 * surfaces, for dynamic colour in day mode.
 */
object WidgetPalette {
    const val CHARCOAL = 0xFF121314L
    const val CHARCOAL_RAISED = 0xFF1C1D20L
    const val CHARCOAL_OUTLINE = 0xFF2E2F33L
    const val EMBER = 0xFFFF6A1AL
    const val ORE_GOLD = 0xFFF2B233L
    const val ASH = 0xFFECE7E1L
    const val ASH_MUTED = 0xFF9A948DL
    const val FROST = 0xFF8FB8DEL
    const val COOLING = 0xFFFFB067L

    const val EMBER_ON_LIGHT = 0xFFA33700L
    const val ORE_GOLD_ON_LIGHT = 0xFF7A5200L
    const val COOLING_ON_LIGHT = 0xFF8F4A00L
    const val FROST_ON_LIGHT = 0xFF2F5F8AL
}

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
