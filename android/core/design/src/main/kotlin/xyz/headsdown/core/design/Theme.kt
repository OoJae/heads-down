package xyz.headsdown.core.design

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The tokens in the composition. Read them through [Hd]; provide one to pin it in a test or a preview. */
val LocalHdColors = staticCompositionLocalOf { HdPalette.Dark }
val LocalHdType = staticCompositionLocalOf { HdType.Default }
val LocalHdShapes = staticCompositionLocalOf { HdShapes.Default }
val LocalHdMotion = staticCompositionLocalOf { HdMotion(HdMotion.systemReduced()) }

/** The design tokens: `Hd.colors.pit`, `Hd.type.body`, `Hd.shapes.plate`, `Hd.motion.settle()`. */
object Hd {
    val colors: HdColorScheme
        @Composable @ReadOnlyComposable get() = LocalHdColors.current

    val type: HdType
        @Composable @ReadOnlyComposable get() = LocalHdType.current

    val shapes: HdShapes
        @Composable @ReadOnlyComposable get() = LocalHdShapes.current

    val motion: HdMotion
        @Composable @ReadOnlyComposable get() = LocalHdMotion.current
}

/**
 * Provides the tokens, and wraps [MaterialTheme] with a colour scheme, a typography and shapes
 * made from them, so a screen that still reads `MaterialTheme` wears the same palette and faces.
 *
 * [darkTheme] stays true until a later stage switches the light palette on; both are complete.
 */
@Composable
fun HeadsDownTheme(darkTheme: Boolean = true, content: @Composable () -> Unit) {
    val colors = if (darkTheme) HdPalette.Dark else HdPalette.Light
    CompositionLocalProvider(
        LocalHdColors provides colors,
        LocalHdType provides HdType.Default,
        LocalHdShapes provides HdShapes.Default,
        LocalHdMotion provides rememberHdMotion(),
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) HdMaterial.DarkColors else HdMaterial.LightColors,
            typography = HdMaterial.Typography,
            shapes = HdMaterial.Shapes,
        ) {
            // MaterialTheme leaves the content colour black; text outside a Surface is ink.
            CompositionLocalProvider(LocalContentColor provides colors.chalk, content = content)
        }
    }
}

/**
 * The tokens as Material 3 reads them. Public so a surface that needs a `ColorScheme` (the Glance
 * widget) builds from the same mapping.
 *
 * Primary and secondary are chalk, not gold: a text button, a switch or a progress bar that asks
 * Material for "primary" must not turn into ORE. Gold is tertiary, ember is the error role. The
 * outline of a control is ash; the hairline is the outline variant.
 */
object HdMaterial {
    fun colorScheme(colors: HdColorScheme): ColorScheme {
        val base = if (colors.isDark) darkColorScheme() else lightColorScheme()
        return base.copy(
            primary = colors.chalk,
            onPrimary = colors.pit,
            primaryContainer = colors.slab,
            onPrimaryContainer = colors.chalk,
            inversePrimary = colors.pit,
            secondary = colors.chalk,
            onSecondary = colors.pit,
            secondaryContainer = colors.slab,
            onSecondaryContainer = colors.chalk,
            tertiary = colors.seam,
            onTertiary = colors.pit,
            tertiaryContainer = colors.slab,
            onTertiaryContainer = colors.seam,
            background = colors.pit,
            onBackground = colors.chalk,
            surface = colors.slab,
            onSurface = colors.chalk,
            surfaceVariant = colors.slab,
            onSurfaceVariant = colors.ash,
            // Surfaces rise by lightness, never by a tint or a shadow.
            surfaceTint = colors.slab,
            inverseSurface = colors.chalk,
            inverseOnSurface = colors.pit,
            error = colors.ember,
            onError = colors.pit,
            errorContainer = colors.slab,
            onErrorContainer = colors.ember,
            outline = colors.ash,
            outlineVariant = colors.hairline,
            scrim = colors.scrim,
            surfaceBright = colors.slab,
            surfaceDim = colors.pit,
            surfaceContainer = colors.slab,
            surfaceContainerHigh = colors.slab,
            surfaceContainerHighest = colors.slab,
            surfaceContainerLow = colors.slab,
            surfaceContainerLowest = colors.slab,
        )
    }

    val DarkColors: ColorScheme = colorScheme(HdPalette.Dark)
    val LightColors: ColorScheme = colorScheme(HdPalette.Light)

    /**
     * The border of a Material control that is only an outline. Material 3 draws an
     * `OutlinedButton`'s border with `outlineVariant`, which here is the hairline: decoration,
     * about 1.3:1, so the button would be a word with no edge. Pass this as its `border` and it
     * keeps an outline that can be seen: ash, or the hairline while it cannot be used.
     *
     * For the buttons from before the redesign; it leaves with the last of them (`BarButton`
     * with `BarButtonStyle.Secondary` draws the same outline itself).
     */
    @Composable
    @ReadOnlyComposable
    fun controlBorder(enabled: Boolean = true): BorderStroke =
        BorderStroke(HdDimens.Hairline, if (enabled) Hd.colors.ash else Hd.colors.hairline)

    /**
     * Body text is 16sp on a 24sp line in all three Material sizes ("body never below 16").
     * `displaySmall` keeps the 40sp the old layouts were drawn for; it leaves with them.
     */
    val Typography: Typography = HdType.Default.let { type ->
        Typography(
            displayLarge = type.display,
            displayMedium = type.display,
            displaySmall = type.display.copy(fontSize = 40.sp),
            headlineLarge = type.title,
            headlineMedium = type.title,
            headlineSmall = type.title,
            titleLarge = type.headline,
            titleMedium = type.headline,
            titleSmall = type.button,
            bodyLarge = type.body,
            bodyMedium = type.body,
            bodySmall = type.body,
            labelLarge = type.button,
            labelMedium = type.label,
            labelSmall = type.caption,
        )
    }

    /** 4dp chips, 6dp plates and bars, 14dp sheets. */
    val Shapes: Shapes = HdShapes.Default.let { shapes ->
        Shapes(
            extraSmall = shapes.chip,
            small = shapes.plate,
            medium = shapes.plate,
            large = shapes.plate,
            extraLarge = RoundedCornerShape(14.dp),
        )
    }
}
