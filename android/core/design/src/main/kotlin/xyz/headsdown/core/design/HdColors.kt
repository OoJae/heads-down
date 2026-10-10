package xyz.headsdown.core.design

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * "The Underside": a dark slab with light underneath. Six roles, two palettes.
 *
 * Every ink ([chalk], [ash], [seam], [ember]) is at least 4.5:1 on [pit] and on [slab] in both
 * palettes (PaletteContrastTest). Surfaces rise by lightness: nothing in this system casts a shadow.
 *
 * Rules the components encode, and a screen must keep:
 * - Gold never fills a button. [seam] is for ORE amounts and for light coming from underneath.
 * - Ink on an [ember] or [seam] fill is always [pit] ([onAccent]): chalk on ember is under 3:1.
 * - [ember] never carries a meaning alone. It comes with a label or a glyph, because in the light
 *   palette it cannot be told from the gold by some colour-blind readers.
 * - [hairline] is decoration. The outline of a control is [ash].
 */
@Immutable
class HdColorScheme(
    /** The background. */
    val pit: Color,
    /** A raised surface: a plate, a sheet, a row that lifts. */
    val slab: Color,
    /** Ink. Also the fill of a primary button. */
    val chalk: Color,
    /** Muted ink, and the outline of a control. */
    val ash: Color,
    /** ORE gold as ink: amounts of ORE and nothing else. In the light palette it is darkened to read on paper. */
    val seam: Color,
    /** The cool end of the accent: armed, cooling, broken, a problem. Always beside a label or a glyph. */
    val ember: Color,
    /** The gold as light: the line under a slab. The same in both palettes; never used as ink on a light surface. */
    val seamLight: Color,
    /** A 1dp rule, [chalk] at 12%. Decoration only. */
    val hairline: Color,
    /** What dims the screen behind a sheet. */
    val scrim: Color,
    /** Ink of a control that cannot be used. Not held to 4.5:1, so never the only copy of a fact. */
    val disabled: Color,
    val isDark: Boolean,
) {
    /** Ink on an [ember] or [seam] fill. */
    val onAccent: Color get() = pit

    /** Ink on a [chalk] fill (a primary button). */
    val onChalk: Color get() = pit

    /** Ink on a [seamLight] fill: the dark pit in both palettes (10.4:1). */
    val onSeamLight: Color get() = Color(HdArgb.PIT)

    override fun equals(other: Any?): Boolean =
        other is HdColorScheme && isDark == other.isDark && pit == other.pit && slab == other.slab &&
            chalk == other.chalk && ash == other.ash && seam == other.seam && ember == other.ember &&
            seamLight == other.seamLight && hairline == other.hairline && scrim == other.scrim && disabled == other.disabled

    override fun hashCode(): Int =
        listOf(pit, slab, chalk, ash, seam, ember, seamLight, hairline, scrim, disabled, isDark).hashCode()
}

/** The two palettes. The app is dark until a later stage switches the light one on. */
object HdPalette {
    val Dark: HdColorScheme = HdColorScheme(
        pit = Color(HdArgb.PIT),
        slab = Color(HdArgb.SLAB),
        chalk = Color(HdArgb.CHALK),
        ash = Color(HdArgb.ASH),
        seam = Color(HdArgb.SEAM),
        ember = Color(HdArgb.EMBER),
        seamLight = Color(HdArgb.SEAM_LIGHT),
        hairline = Color(HdArgb.CHALK).copy(alpha = HAIRLINE_ALPHA),
        scrim = Color.Black.copy(alpha = 0.6f),
        disabled = Color(HdArgb.CHALK).copy(alpha = DISABLED_ALPHA),
        isDark = true,
    )

    val Light: HdColorScheme = HdColorScheme(
        pit = Color(HdArgb.Day.PIT),
        slab = Color(HdArgb.Day.SLAB),
        chalk = Color(HdArgb.Day.CHALK),
        ash = Color(HdArgb.Day.ASH),
        seam = Color(HdArgb.Day.SEAM),
        ember = Color(HdArgb.Day.EMBER),
        seamLight = Color(HdArgb.SEAM_LIGHT),
        hairline = Color(HdArgb.Day.CHALK).copy(alpha = HAIRLINE_ALPHA),
        scrim = Color(HdArgb.Day.CHALK).copy(alpha = 0.4f),
        disabled = Color(HdArgb.Day.CHALK).copy(alpha = DISABLED_ALPHA),
        isDark = false,
    )

    const val HAIRLINE_ALPHA = 0.12f
    const val DISABLED_ALPHA = 0.38f
}

/**
 * The palette as plain ARGB integers, for what Compose does not draw: the notification accent,
 * the share image, RemoteViews. The top level is the dark palette, which is the brand's own;
 * [Day] is the light one. `res/values/colors.xml` repeats the values for XML, and PaletteXmlTest
 * keeps the two equal.
 */
object HdArgb {
    const val PIT: Int = 0xFF0C0C0D.toInt()
    const val SLAB: Int = 0xFF18181A.toInt()
    const val CHALK: Int = 0xFFEFEAE0.toInt()
    const val ASH: Int = 0xFF98938A.toInt()
    const val SEAM: Int = 0xFFF2B233.toInt()
    const val EMBER: Int = 0xFFE8622A.toInt()

    /** The gold as light. The same value in both palettes. */
    const val SEAM_LIGHT: Int = SEAM

    /**
     * [HdColorScheme.hairline] of the dark palette flattened onto [PIT] and onto [SLAB], for
     * surfaces without alpha. These are the colours Compose really draws: it keeps alpha in 8
     * bits, so "12%" is 31/255.
     */
    const val HAIRLINE_ON_PIT: Int = 0xFF282727.toInt()
    const val HAIRLINE_ON_SLAB: Int = 0xFF323232.toInt()

    /** [PIT] as the `Long` a `@Preview(backgroundColor = …)` takes. */
    const val PREVIEW_PIT: Long = 0xFF0C0C0D

    /** The light palette. */
    object Day {
        const val PIT: Int = 0xFFF1ECE2.toInt()
        const val SLAB: Int = 0xFFE4DDD0.toInt()
        const val CHALK: Int = 0xFF151413.toInt()
        const val ASH: Int = 0xFF625C54.toInt()
        const val SEAM: Int = 0xFF7D5100.toInt()
        const val EMBER: Int = 0xFFA83A0C.toInt()
    }
}

/**
 * COMPATIBILITY VALUES. Colours the layouts from before the redesign still name and "The
 * Underside" has no role for. They are kept here, in the one module that may hold a colour
 * literal, so the old screens and the widget keep working through stage 1; each leaves with the
 * last layout that names it. New code does not use them.
 *
 * - [FROST]: the frozen rig's blue. The system gives a frozen rig no colour of its own.
 * - [COOLING]: the lighter orange of a cooling rig and of problem lines. The system says ember.
 * - [EMBER_DIM]: a dark ember for a finished step's border and the health banner's tint.
 * - [FROST_DAY], [COOLING_DAY]: the two above darkened for a light Material You widget.
 *
 * All four text colours are at least 4.5:1 on pit and slab (ThemeContrastTest, WidgetColorsTest).
 */
object HdCompat {
    const val FROST: Int = 0xFF8FB8DE.toInt()
    const val COOLING: Int = 0xFFFFB067.toInt()
    const val EMBER_DIM: Int = 0xFF7A3514.toInt()
    const val FROST_DAY: Int = 0xFF2F5F8A.toInt()
    const val COOLING_DAY: Int = 0xFF8F4A00.toInt()
}
