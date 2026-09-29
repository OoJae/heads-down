package xyz.headsdown.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Night-shift palette: charcoal surfaces, ember for "rig hot", ORE gold for hauls. */
object HdColors {
    val Charcoal = Color(0xFF121314)
    val CharcoalRaised = Color(0xFF1C1D20)
    val CharcoalOutline = Color(0xFF2E2F33)
    val Ember = Color(0xFFFF6A1A)
    val EmberDim = Color(0xFF7A3514)
    val OreGold = Color(0xFFF2B233)
    val Ash = Color(0xFFECE7E1)
    val AshMuted = Color(0xFF9A948D)
    val Frost = Color(0xFF8FB8DE)
    val Cooling = Color(0xFFFFB067)
}

private val scheme = darkColorScheme(
    primary = HdColors.Ember,
    onPrimary = HdColors.Charcoal,
    secondary = HdColors.OreGold,
    onSecondary = HdColors.Charcoal,
    background = HdColors.Charcoal,
    onBackground = HdColors.Ash,
    surface = HdColors.CharcoalRaised,
    onSurface = HdColors.Ash,
    surfaceVariant = HdColors.CharcoalRaised,
    onSurfaceVariant = HdColors.AshMuted,
    outline = HdColors.CharcoalOutline,
)

private val typography = Typography(
    displaySmall = TextStyle(fontSize = 40.sp, fontWeight = FontWeight.Black, letterSpacing = (-0.5).sp),
    headlineSmall = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
    bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 21.sp),
    labelLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
)

/** Always dark: this app lives on a nightstand. */
@Composable
fun HeadsDownTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}
