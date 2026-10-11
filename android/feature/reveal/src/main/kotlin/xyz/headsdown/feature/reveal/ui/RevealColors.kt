package xyz.headsdown.feature.reveal.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import xyz.headsdown.core.design.HdArgb
import xyz.headsdown.core.design.HdCompat
import xyz.headsdown.core.design.HdPalette
import xyz.headsdown.core.design.HeadsDownTheme

/**
 * COMPATIBILITY SHIM, read by nothing in this module any more: the reveal is drawn with the
 * tokens of "The Underside" (`Hd.colors`), never with these names. It stays for one reader, the
 * app's `ThemeContrastTest`, which still compares it with the app's own shim (`HdColors`); it
 * leaves, [Cooling] and its `HdCompat` value included, when that comparison does.
 */
object RevealColors {
    val Charcoal: Color = HdPalette.Dark.pit
    val CharcoalRaised: Color = HdPalette.Dark.slab
    val CharcoalOutline: Color = Color(HdArgb.HAIRLINE_ON_PIT)
    val Ember: Color = HdPalette.Dark.ember
    val OreGold: Color = HdPalette.Dark.seam
    val Ash: Color = HdPalette.Dark.chalk
    val AshMuted: Color = HdPalette.Dark.ash

    /** A colour the new system has no role for (HdCompat). No screen of the reveal draws with it. */
    val Cooling: Color = Color(HdCompat.COOLING)
}

/**
 * The reveal's theme: the design module's, in the dark palette (the app is dark until a later
 * stage switches the light one on; every colour here is a token, so the screen follows it then).
 */
@Composable
fun RevealTheme(content: @Composable () -> Unit) = HeadsDownTheme(darkTheme = true, content = content)
