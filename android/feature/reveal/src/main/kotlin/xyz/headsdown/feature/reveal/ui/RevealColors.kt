package xyz.headsdown.feature.reveal.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** The night-shift palette. Mirrors the app's `HdColors` (an app unit test keeps them equal). */
object RevealColors {
    val Charcoal = Color(0xFF121314)
    val CharcoalRaised = Color(0xFF1C1D20)
    val CharcoalOutline = Color(0xFF2E2F33)
    val Ember = Color(0xFFFF6A1A)
    val OreGold = Color(0xFFF2B233)
    val Ash = Color(0xFFECE7E1)
    val AshMuted = Color(0xFF9A948D)
    val Cooling = Color(0xFFFFB067)
}

private val scheme = darkColorScheme(
    primary = RevealColors.OreGold,
    onPrimary = RevealColors.Charcoal,
    secondary = RevealColors.Ember,
    onSecondary = RevealColors.Charcoal,
    background = RevealColors.Charcoal,
    onBackground = RevealColors.Ash,
    surface = RevealColors.CharcoalRaised,
    onSurface = RevealColors.Ash,
    onSurfaceVariant = RevealColors.AshMuted,
    outline = RevealColors.CharcoalOutline,
)

internal val PixelLabel = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, letterSpacing = 2.sp)

private val typography = Typography(
    displaySmall = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Black, letterSpacing = (-0.5).sp),
    headlineSmall = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
    bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
)

@Composable
fun RevealTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = scheme, typography = typography, content = content)
