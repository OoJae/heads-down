package xyz.headsdown.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import xyz.headsdown.core.design.HdArgb
import xyz.headsdown.core.design.HdCompat
import xyz.headsdown.core.design.HdPalette
import xyz.headsdown.core.design.HdType
import xyz.headsdown.core.design.HeadsDownTheme as UndersideTheme

/**
 * COMPATIBILITY SHIM. The names the screens from before the redesign use, with the values of
 * "The Underside" (`:core:design`): Charcoal is the pit, CharcoalRaised the slab, Ash the chalk,
 * AshMuted the ash, OreGold the seam. Nothing here holds a colour of its own; a screen that is
 * rebuilt reads `Hd.colors` instead and this object goes when the last one has been.
 *
 * Every text colour here meets WCAG AA (4.5:1) on Charcoal and CharcoalRaised (see
 * ThemeContrastTest); the widget's WidgetPalette and the reveal's RevealColors are the same shim.
 */
object HdColors {
    val Charcoal: Color = HdPalette.Dark.pit
    val CharcoalRaised: Color = HdPalette.Dark.slab

    /** The hairline flattened onto the pit: the old layouts draw their outlines opaque. */
    val CharcoalOutline: Color = Color(HdArgb.HAIRLINE_ON_PIT)
    val Ember: Color = HdPalette.Dark.ember
    val OreGold: Color = HdPalette.Dark.seam
    val Ash: Color = HdPalette.Dark.chalk
    val AshMuted: Color = HdPalette.Dark.ash

    // Colours the new system has no role for (HdCompat): they leave with the layouts that name them.
    val EmberDim: Color = Color(HdCompat.EMBER_DIM)
    val Frost: Color = Color(HdCompat.FROST)
    val Cooling: Color = Color(HdCompat.COOLING)
}

/** Mono capitals for labels ("HEADS DOWN", "RIG"): the design system's label style. */
val PixelLabel: TextStyle = HdType.Default.label

/**
 * The app's theme: the design module's, dark. Always dark for now: this app lives on a
 * nightstand, and the light palette is switched on in a later stage.
 */
@Composable
fun HeadsDownTheme(content: @Composable () -> Unit) {
    UndersideTheme(darkTheme = true, content = content)
}
