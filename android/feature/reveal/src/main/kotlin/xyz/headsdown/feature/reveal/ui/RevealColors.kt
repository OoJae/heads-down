package xyz.headsdown.feature.reveal.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import xyz.headsdown.core.design.HdArgb
import xyz.headsdown.core.design.HdCompat
import xyz.headsdown.core.design.HdPalette
import xyz.headsdown.core.design.HdType
import xyz.headsdown.core.design.HeadsDownTheme

/**
 * COMPATIBILITY SHIM: the reveal's old colour names with the values of "The Underside"
 * (`:core:design`). The same shim as the app's `HdColors` (an app unit test keeps them equal).
 */
object RevealColors {
    val Charcoal: Color = HdPalette.Dark.pit
    val CharcoalRaised: Color = HdPalette.Dark.slab
    val CharcoalOutline: Color = Color(HdArgb.HAIRLINE_ON_PIT)
    val Ember: Color = HdPalette.Dark.ember
    val OreGold: Color = HdPalette.Dark.seam
    val Ash: Color = HdPalette.Dark.chalk
    val AshMuted: Color = HdPalette.Dark.ash

    /** A colour the new system has no role for (HdCompat): it leaves with the layout that names it. */
    val Cooling: Color = Color(HdCompat.COOLING)
}

internal val PixelLabel: TextStyle = HdType.Default.label

/**
 * The reveal's theme: the design module's, dark. The reveal used to swap Material's primary and
 * secondary (gold first); every colour on this screen is named explicitly, so nothing read them.
 */
@Composable
fun RevealTheme(content: @Composable () -> Unit) = HeadsDownTheme(darkTheme = true, content = content)
