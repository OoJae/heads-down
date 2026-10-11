package xyz.headsdown.surface.widget

import android.content.res.Resources
import android.os.Build
import android.util.DisplayMetrics
import android.util.TypedValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Box
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontFamily
import androidx.glance.text.FontWeight
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import xyz.headsdown.core.design.HdDimens

/**
 * What both widgets are: a slab of the pit with the system's own corner over a 6dp one, and a
 * hairline around it so it keeps an edge on a dark wallpaper. Slabs, not pills: where a launcher
 * rounds its widgets further, its clip wins (the widget does not opt out of it).
 *
 * The slab is one node to a screen reader ([description]) and one target ([open]; null in a
 * generated preview, which must not start anything).
 *
 * Under Material You ([WidgetColorMode.DYNAMIC]) the surface is the wallpaper's and has no frame.
 */
@Composable
internal fun WidgetSlab(mode: WidgetColorMode, description: String, open: Action?, content: @Composable () -> Unit) {
    val colors = GlanceTheme.colors
    val edge = if (mode == WidgetColorMode.BRAND) colors.outline else colors.surface
    var frame = GlanceModifier
        .fillMaxSize()
        .background(edge)
        .cornerRadius(SLAB_CORNER)
        .padding(HAIRLINE)
        .semantics { contentDescription = description }
    if (open != null) frame = frame.clickable(open)
    Box(frame) {
        Box(GlanceModifier.fillMaxSize().background(colors.surface).cornerRadius(SLAB_CORNER - HAIRLINE)) { content() }
    }
}

/**
 * The widget's type. RemoteViews cannot carry a font file, so the three faces are the system's
 * nearest: condensed bold for the rig word (the display face's stand-in, FONTS.md), monospace for
 * labels and figures, the default face for a sentence.
 *
 * A widget is a box that does not grow, so its text follows the system font scale only up to
 * [MAX_SCALE] (the cap the app's display face has), and a layout that is already full at 1.0 passes
 * a lower `max` still. Sizes are given in sp all the same: [sp] gives back fewer of them where the
 * system would draw the size larger than the cap allows. The system's own conversion is asked both
 * ways, because from Android 14 a font scale no longer multiplies every size by the same number.
 * Where a box is too small anyway, what falls off its lower edge is its last line, never an
 * amount and never the time.
 */
internal class WidgetType(resources: Resources, max: Float = MAX_SCALE) {
    private val metrics: DisplayMetrics = resources.displayMetrics
    private val system = resources.configuration.fontScale.takeIf { it > 0f } ?: 1f

    /** The most the text grows: the system's font scale, up to [max]. Never under 1 for a larger font, never over the system's own. */
    val scale: Float = minOf(system, maxOf(1f, minOf(max, MAX_SCALE)))

    /** [size] sp, or the fewer sp that the system draws as large as [size] at [scale]. */
    fun spValue(size: Float): Float {
        val asked = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, size, metrics)
        val most = size * metrics.density * scale
        return when {
            asked <= most -> size
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> TypedValue.deriveDimension(TypedValue.COMPLEX_UNIT_SP, most, metrics)
            else -> most / (metrics.density * system)
        }
    }

    fun sp(size: Float): TextUnit = spValue(size).sp

    fun word(size: Float, color: ColorProvider) =
        TextStyle(color = color, fontSize = sp(size), fontWeight = FontWeight.Bold, fontFamily = CONDENSED)

    fun label(size: Float, color: ColorProvider) =
        TextStyle(color = color, fontSize = sp(size), fontFamily = FontFamily.Monospace)

    fun figure(size: Float, color: ColorProvider) =
        TextStyle(color = color, fontSize = sp(size), fontFamily = FontFamily.Monospace)

    fun body(size: Float, color: ColorProvider) = TextStyle(color = color, fontSize = sp(size))

    companion object {
        const val MAX_SCALE = 1.3f

        /** The platform's condensed sans, by its family name: the stand-in for Big Shoulders Display. */
        const val CONDENSED_FAMILY = "sans-serif-condensed"
        val CONDENSED = FontFamily(CONDENSED_FAMILY)
    }
}

/** 6dp, the radius of every plate and bar in the app (`HdShapes.plate`, which is a shape and not a length). */
internal val SLAB_CORNER: Dp = 6.dp

/** The width of a hairline. */
internal val HAIRLINE: Dp = HdDimens.Hairline

/** The width of the gold line under a slab. */
internal val SEAM_LINE: Dp = HdDimens.SeamLine
