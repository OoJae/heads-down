package xyz.headsdown.surface.widget

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.widget.RemoteViews
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.appwidget.AndroidRemoteViews
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.layout.wrapContentSize
import androidx.glance.text.Text
import androidx.glance.unit.ColorProvider

/**
 * The "Rig" home-screen widget: rig heat, a chronometer that ticks the shift without any app
 * update, the last haul and the streak. Tapping opens the app.
 *
 * It is laid out as the wordmark is: what the slab is doing above a line, the ORE under it. The
 * line is the seam. It is gold while the rig is hot, light from underneath, and a hairline
 * the rest of the time. Nothing here moves but the chronometer's digits.
 *
 * SIZES. A launcher is handed one layout per declared size and, for the box it gives the widget,
 * takes the nearest of those that fit (`RemoteViews.findBestFitLayout`: least squared distance).
 * Nearest is not largest: of two sizes that both fit, a wide low one can beat a narrow tall one.
 * So [SIZES] is closed under "the wider width with the taller height" (two widths by four
 * heights, less the pairs that would draw the same thing), which makes the nearest size the
 * widest and tallest that fits, and the layout a matter of the box alone (RigWidgetSizesTest
 * asks the platform itself).
 */
class RigWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Responsive(SIZES)
    override val previewSizeMode = SizeMode.Responsive(setOf(STANDARD, TALL))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val store = WidgetStateStore.get(context)
        val boot = BootCount.read(context)
        val mode = WidgetColorPolicy.mode(Build.VERSION.SDK_INT, store.preferBrandColors)
        val open = openAppAction(context)
        provideContent {
            val state by store.state.collectAsState()
            GlanceTheme(colors = WidgetColors.providers(mode)) {
                RigWidgetContent(RigWidgetCopy.render(WidgetStateReducer.forDisplay(state, boot)), mode, open)
            }
        }
    }

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        // The picker shows the widget as it will be drawn: brand unless that was turned off.
        val mode = WidgetColorPolicy.mode(Build.VERSION.SDK_INT, WidgetStateStore.get(context).preferBrandColors)
        provideContent {
            GlanceTheme(colors = WidgetColors.providers(mode)) {
                RigWidgetContent(RigWidgetCopy.render(PREVIEW_STATE), mode, open = null, previewNowWallMillis = PREVIEW_NOW)
            }
        }
    }

    companion object {
        /** A short row two cells wide, the least a launcher may give: the rig word over the time or the status. */
        val COMPACT = DpSize(110.dp, 40.dp)

        /** A short row three cells wide or more (a home screen on its side): the same two lines, with the lights. */
        val STRIP = DpSize(180.dp, 40.dp)

        /** One row of a phone's home screen, two cells wide: the rig word, the time, the status. */
        val ROW_NARROW = DpSize(110.dp, 80.dp)

        /** One row, three cells wide or more: the name, the rig word beside the time, the status. */
        val ROW = DpSize(180.dp, 80.dp)

        /** The smallest size that shows everything, in small type. */
        val STANDARD = DpSize(180.dp, 100.dp)

        /** Two rows, two cells wide: everything, set narrow. */
        val SQUARE = DpSize(110.dp, 170.dp)

        /** Two rows of the home screen, which is what the widget asks for: room for the rig word at its full size. */
        val TALL = DpSize(180.dp, 170.dp)

        /**
         * Four cells of one tall row. It is drawn exactly as [STANDARD] is, so it is not declared:
         * declared, it was nearer to a box of four cells by two rows than [TALL] is, and put the
         * small layout there over an empty band.
         */
        val WIDE = DpSize(260.dp, 100.dp)

        /** Every size the launcher is given a layout for. */
        val SIZES: Set<DpSize> = setOf(COMPACT, STRIP, ROW_NARROW, ROW, STANDARD, SQUARE, TALL)

        private const val PREVIEW_NOW = 1_790_000_000_000L

        /** The picker preview: a hot rig 1 h 12 min into a shift, a modest haul, a streak. */
        val PREVIEW_STATE = RigWidgetState(
            rig = WidgetRig(heat = RigHeat.HOT, darkSinceWallMillis = PREVIEW_NOW - 72 * 60_000L, darkRounds = 55),
            haul = WidgetHaul(oreAtoms = 1_940_000_000L, endedAtWallMillis = PREVIEW_NOW - 16 * 3_600_000L),
            streakNights = 23,
        )

        fun openAppAction(context: Context): Action? =
            context.packageManager.getLaunchIntentForPackage(context.packageName)
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                ?.let { actionStartActivity(it) }
    }
}

class RigWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = RigWidget()
}

/** The four layouts, from the least a box can hold to the most. */
internal enum class RigLayout { STRIP, ROW, STANDARD, TALL }

/** What one declared size draws: which layout, and whether it is the two-cell (narrow) setting of it. */
internal class RigPlan private constructor(val layout: RigLayout, val narrow: Boolean) {
    companion object {
        fun of(size: DpSize): RigPlan {
            val narrow = size.width < RigWidget.STRIP.width
            val layout = when {
                size.height < RigWidget.ROW.height -> RigLayout.STRIP
                size.height < RigWidget.STANDARD.height -> RigLayout.ROW
                // Two cells cannot hold the small layout's lines: they keep the row until there are two rows.
                size.height < RigWidget.TALL.height -> if (narrow) RigLayout.ROW else RigLayout.STANDARD
                else -> RigLayout.TALL
            }
            return RigPlan(layout, narrow)
        }
    }
}

@Composable
internal fun RigWidgetContent(
    text: RigWidgetText,
    mode: WidgetColorMode,
    open: Action?,
    /** Fixed clock for generated previews; null = now. */
    previewNowWallMillis: Long? = null,
) {
    val colors = GlanceTheme.colors
    val accents = WidgetAccents(mode)
    val resources = LocalContext.current.resources
    val look = RigLook(
        word = accents.word(text.heat, ink = colors.onSurface, muted = colors.onSurfaceVariant),
        lights = accents.lights(text.heat, muted = colors.onSurfaceVariant),
        // The seam: gold light under a hot rig, a hairline under any other.
        seam = if (text.heat == RigHeat.HOT) accents.light else colors.outline,
        // Gold is the ink of an amount of ORE. "No haul yet" is a sentence.
        haul = if (text.hasHaul) accents.gold else colors.onSurfaceVariant,
    )
    val clock = text.chronometerSinceWallMillis?.let { ShiftClock(it, mode, previewNowWallMillis) }
    // The layout is chosen for the size Glance composes, which is one of [RigWidget.SIZES].
    val plan = RigPlan.of(LocalSize.current)
    // Every layout is full at the size it is declared for, so its text keeps its size under a larger system font.
    val type = WidgetType(resources, max = 1f)
    WidgetSlab(mode, text.contentDescription, open) {
        when (plan.layout) {
            RigLayout.STRIP -> StripRig(text, look, type, clock, plan.narrow)
            RigLayout.ROW -> if (plan.narrow) NarrowRowRig(text, look, type, clock) else RowRig(text, look, type, clock)
            RigLayout.STANDARD -> StandardRig(text, look, type, clock)
            RigLayout.TALL -> if (plan.narrow) SquareRig(text, look, type, clock) else TallRig(text, look, type, clock)
        }
    }
}

/** What the chronometer counts from, and how it is drawn. */
private class ShiftClock(val sinceWallMillis: Long, val mode: WidgetColorMode, val previewNowWallMillis: Long?)

/** The inks of one rig state. */
private class RigLook(val word: ColorProvider, val lights: ColorProvider, val seam: ColorProvider, val haul: ColorProvider)

/**
 * A short row: the rig word, and under it the status in small type, whole wherever the row is
 * three cells wide. A running time sits at the end of the word's line.
 *
 * Two cells are too few for the word and a time side by side (half of "1:12:00" is another
 * time, and "Ri…" is no word), so there the time takes the second line. The status and the
 * lights are then left to the widget's description: the word says what the lights say.
 */
@Composable
private fun StripRig(text: RigWidgetText, look: RigLook, type: WidgetType, clock: ShiftClock?, narrow: Boolean) {
    val colors = GlanceTheme.colors
    Column(
        GlanceModifier.fillMaxSize().padding(horizontal = if (narrow) 8.dp else 10.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (narrow) {
            Text(text.headline, style = type.word(14f, look.word), maxLines = 1)
        } else {
            Headline(text, look, type, clock, wordSize = 14f, timeSize = 11f, lights = true)
        }
        if (narrow && clock != null) {
            ShiftChronometer(clock, type.spValue(11f))
        } else {
            Text(text.line, style = type.body(11f, colors.onSurfaceVariant), maxLines = 1)
        }
    }
}

/** One row of a home screen, three cells wide or more: the name, the rig word beside the time, the status. */
@Composable
private fun RowRig(text: RigWidgetText, look: RigLook, type: WidgetType, clock: ShiftClock?) {
    val colors = GlanceTheme.colors
    Column(GlanceModifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        BrandRow(text.heat, look, type, labelSize = 10f)
        Headline(text, look, type, clock, wordSize = 20f, timeSize = 12f)
        Text(text.line, style = type.body(11f, colors.onSurfaceVariant), maxLines = 2)
    }
}

/**
 * One row, two cells wide: the rig word, the status under it, the time under that (as the
 * two-row layouts read: "Dark for", then how long). No line is shared, so none is cut.
 */
@Composable
private fun NarrowRowRig(text: RigWidgetText, look: RigLook, type: WidgetType, clock: ShiftClock?) {
    val colors = GlanceTheme.colors
    Column(GlanceModifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text.headline, style = type.word(18f, look.word), maxLines = 1)
        Text(text.line, style = type.body(11f, colors.onSurfaceVariant), maxLines = 2)
        if (clock != null) ShiftChronometer(clock, type.spValue(12f))
    }
}

/**
 * Everything, in small type, for a box that is only 100dp tall: the row above, then the seam and
 * the ORE under it.
 *
 * The status has a line to itself. Beside the time it wrapped in a box under 204dp wide, and its
 * second line pushed the streak out of the slab.
 *
 * Seven children. A Glance container keeps ten and drops the rest without a word, so the seam is
 * one element and not a line between two spacers (WidgetRemoteViewsTest counts what arrives).
 */
@Composable
private fun StandardRig(text: RigWidgetText, look: RigLook, type: WidgetType, clock: ShiftClock?) {
    val colors = GlanceTheme.colors
    Column(GlanceModifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 6.dp)) {
        BrandRow(text.heat, look, type, labelSize = 10f)
        Headline(text, look, type, clock, wordSize = 20f, timeSize = 12f)
        Text(text.line, style = type.body(11f, colors.onSurfaceVariant), maxLines = 2)
        Spacer(GlanceModifier.defaultWeight())
        Seam(look, above = 2, below = 2)
        // An amount is never cut: if it cannot fit the line it takes a second one.
        Text(text.haul, style = type.figure(10f, look.haul), maxLines = 2)
        Text(text.streak, style = type.figure(10f, colors.onSurfaceVariant), maxLines = 1)
    }
}

/**
 * Two rows of the home screen: the rig word at its size, the time as a figure of its own.
 * Eight children at most, of the ten a Glance container keeps.
 */
@Composable
private fun TallRig(text: RigWidgetText, look: RigLook, type: WidgetType, clock: ShiftClock?) {
    val colors = GlanceTheme.colors
    Column(GlanceModifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp)) {
        BrandRow(text.heat, look, type, labelSize = 11f)
        Text(text.headline, modifier = GlanceModifier.padding(top = 4.dp), style = type.word(30f, look.word), maxLines = 1)
        Text(text.line, style = type.body(13f, colors.onSurfaceVariant), maxLines = 2)
        if (clock != null) ShiftChronometer(clock, type.spValue(20f))
        Spacer(GlanceModifier.defaultWeight())
        Seam(look, above = 6, below = 6)
        Text(text.haul, style = type.figure(12f, look.haul), maxLines = 2)
        Text(text.streak, style = type.figure(12f, colors.onSurfaceVariant), maxLines = 1)
    }
}

/**
 * Two rows, two cells wide: the two-row layout set narrow. The lights stand without the name
 * (the two do not fit one line), and a line too long for two cells takes a second one. An amount
 * goes to that second line whole: its number is not parted from "ORE".
 */
@Composable
private fun SquareRig(text: RigWidgetText, look: RigLook, type: WidgetType, clock: ShiftClock?) {
    val colors = GlanceTheme.colors
    Column(GlanceModifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 8.dp)) {
        HeatPixels(text.heat, look.lights, colors.outline)
        Text(text.headline, modifier = GlanceModifier.padding(top = 4.dp), style = type.word(24f, look.word), maxLines = 1)
        Text(text.line, style = type.body(11f, colors.onSurfaceVariant), maxLines = 2)
        if (clock != null) ShiftChronometer(clock, type.spValue(16f))
        Spacer(GlanceModifier.defaultWeight())
        Seam(look, above = 4, below = 4)
        Text(if (text.hasHaul) amountKeptWhole(text.haul) else text.haul, style = type.figure(10f, look.haul), maxLines = 2)
        Text(text.streak, style = type.figure(10f, colors.onSurfaceVariant), maxLines = 2)
    }
}

/**
 * [line] with the space before its last word made a no-break space, so "0.0194 ORE" wraps as one
 * piece. The same characters to a reader; a line break is the only thing it changes.
 */
internal fun amountKeptWhole(line: String): String {
    val space = line.lastIndexOf(' ')
    return if (space < 0) line else line.replaceRange(space, space + 1, NO_BREAK_SPACE)
}

internal const val NO_BREAK_SPACE = " "

/** The five lights and the name. */
@Composable
private fun BrandRow(heat: RigHeat, look: RigLook, type: WidgetType, labelSize: Float) {
    val colors = GlanceTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        HeatPixels(heat, look.lights, colors.outline)
        Spacer(GlanceModifier.width(8.dp))
        Text("HEADS DOWN", style = type.label(labelSize, colors.onSurfaceVariant), maxLines = 1)
    }
}

/**
 * The rig word and, at the end of its line, the running time: a ledger row, the state and its
 * figure. The time is measured first and is always whole; on a line too short for both it is
 * the word that gives way.
 */
@Composable
private fun Headline(
    text: RigWidgetText,
    look: RigLook,
    type: WidgetType,
    clock: ShiftClock?,
    wordSize: Float,
    timeSize: Float,
    lights: Boolean = false,
) {
    val colors = GlanceTheme.colors
    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (lights) {
            HeatPixels(text.heat, look.lights, colors.outline)
            Spacer(GlanceModifier.width(8.dp))
        }
        Text(text.headline, modifier = GlanceModifier.defaultWeight(), style = type.word(wordSize, look.word), maxLines = 1)
        if (clock != null) {
            Spacer(GlanceModifier.width(6.dp))
            ShiftChronometer(clock, type.spValue(timeSize))
        }
    }
}

/** The line between the slab's state and its ORE. One element, with its own space above and below. */
@Composable
private fun Seam(look: RigLook, above: Int, below: Int) {
    Box(GlanceModifier.fillMaxWidth().padding(top = above.dp, bottom = below.dp)) {
        Box(GlanceModifier.fillMaxWidth().height(SEAM_LINE).background(look.seam)) {}
    }
}

/** Five squares that fill with heat: an accent that still reads on one cell of a home screen. */
@Composable
internal fun HeatPixels(heat: RigHeat, lit: ColorProvider, unlit: ColorProvider) {
    val count = litPixels(heat)
    Row(verticalAlignment = Alignment.CenterVertically) {
        repeat(PIXELS) { i ->
            if (i > 0) Spacer(GlanceModifier.width(2.dp))
            Box(GlanceModifier.size(6.dp).background(if (i < count) lit else unlit)) {}
        }
    }
}

internal fun litPixels(heat: RigHeat): Int = when (heat) {
    RigHeat.COLD -> 0
    RigHeat.ARMED -> 1
    RigHeat.COOLING -> 3
    RigHeat.HOT -> PIXELS
    RigHeat.FROZEN -> PIXELS
}

/**
 * A platform `Chronometer` inside the Glance tree: the launcher ticks it every second with no
 * app process and no widget update. Its base is `elapsedRealtime`-anchored to the wall-clock
 * start of the dark time. Its face is the system's monospace, so the digits do not shift as they
 * change.
 */
@Composable
private fun ShiftChronometer(clock: ShiftClock, textSizeSp: Float) {
    val context = LocalContext.current
    val elapsedNow = SystemClock.elapsedRealtime()
    val wallNow = clock.previewNowWallMillis ?: System.currentTimeMillis()
    val views = RemoteViews(context.packageName, R.layout.hd_widget_chronometer).apply {
        val id = R.id.hd_widget_chronometer
        // A generated preview is a snapshot: it shows the elapsed time, not a running clock.
        setChronometer(id, ChronometerAnchor.base(clock.sinceWallMillis, wallNow, elapsedNow), null, clock.previewNowWallMillis == null)
        setTextViewTextSize(id, android.util.TypedValue.COMPLEX_UNIT_SP, textSizeSp)
        when (clock.mode) {
            WidgetColorMode.BRAND -> setTextColor(id, WidgetPalette.ASH.toInt())
            WidgetColorMode.DYNAMIC -> setColorAttr(id, "setTextColor", android.R.attr.textColorPrimary)
        }
    }
    // Wrapped on purpose: without a size of its own the platform view takes all the height left in a column.
    AndroidRemoteViews(views, GlanceModifier.wrapContentSize())
}

private const val PIXELS = 5
