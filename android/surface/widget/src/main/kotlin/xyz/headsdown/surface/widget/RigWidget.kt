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
 */
class RigWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Responsive(setOf(COMPACT, STRIP, STANDARD, WIDE, TALL))
    override val previewSizeMode = SizeMode.Responsive(setOf(STANDARD, WIDE, TALL))

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
        /** One row of the home screen, as narrow as it goes: the rig word, then the time or the status. */
        val COMPACT = DpSize(110.dp, 40.dp)

        /** One row, three cells wide: the lights, the rig word and the time on a single line. */
        val STRIP = DpSize(190.dp, 40.dp)

        /** The smallest size that shows everything, in small type. */
        val STANDARD = DpSize(180.dp, 100.dp)

        /** The same layout with room beside it: one row of a tablet or a tall cell, four cells wide. */
        val WIDE = DpSize(260.dp, 100.dp)

        /** Two rows of the home screen, which is what the widget asks for: room for the rig word at its full size. */
        val TALL = DpSize(180.dp, 170.dp)

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
    val type = WidgetType(resources)
    val look = RigLook(
        word = accents.word(text.heat, ink = colors.onSurface, muted = colors.onSurfaceVariant),
        lights = accents.lights(text.heat, muted = colors.onSurfaceVariant),
        // The seam: gold light under a hot rig, a hairline under any other.
        seam = if (text.heat == RigHeat.HOT) accents.light else colors.outline,
        // Gold is the ink of an amount of ORE. "No haul yet" is a sentence.
        haul = if (text.hasHaul) accents.gold else colors.onSurfaceVariant,
    )
    val clock = text.chronometerSinceWallMillis?.let { ShiftClock(it, mode, previewNowWallMillis) }
    // Larger text needs a larger box: each layout is chosen for the size that holds it at this font scale.
    val size = LocalSize.current
    WidgetSlab(mode, text.contentDescription, open) {
        when {
            size.height < STANDARD_MIN_HEIGHT -> StripRig(text, look, type, clock, roomForLights = size.width.value / type.scale >= RigWidget.STRIP.width.value)
            size.height.value / type.scale >= RigWidget.TALL.height.value -> TallRig(text, look, type, clock)
            // The small layout is full at 100dp: its text grows only as far as the box is taller than that.
            else -> StandardRig(text, look, WidgetType(resources, max = size.height.value / RigWidget.STANDARD.height.value), clock)
        }
    }
}

/** What the chronometer counts from, and how it is drawn. */
private class ShiftClock(val sinceWallMillis: Long, val mode: WidgetColorMode, val previewNowWallMillis: Long?)

/** The inks of one rig state. */
private class RigLook(val word: ColorProvider, val lights: ColorProvider, val seam: ColorProvider, val haul: ColorProvider)

/**
 * One line: the lights, the rig word, then the running time or the status.
 *
 * A time is never cut short (half of "1:12:00" is another time): it is measured first and sits
 * whole at the end of the row, and what gives way is the word, with an ellipsis. Where even the
 * lights do not fit beside a running time they are left out: the word says the same thing.
 */
@Composable
private fun StripRig(text: RigWidgetText, look: RigLook, type: WidgetType, clock: ShiftClock?, roomForLights: Boolean) {
    val colors = GlanceTheme.colors
    Row(GlanceModifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (clock == null || roomForLights) {
            HeatPixels(text.heat, look.lights, colors.outline)
            Spacer(GlanceModifier.width(8.dp))
        }
        if (clock != null) {
            Text(text.headline, modifier = GlanceModifier.defaultWeight(), style = type.word(17f, look.word), maxLines = 1)
            Spacer(GlanceModifier.width(6.dp))
            ShiftChronometer(clock, type.spValue(12f))
        } else {
            Text(text.headline, style = type.word(17f, look.word), maxLines = 1)
            Spacer(GlanceModifier.width(8.dp))
            Text(text.line, modifier = GlanceModifier.defaultWeight(), style = type.body(12f, colors.onSurfaceVariant), maxLines = 1)
        }
    }
}

/**
 * Everything, in small type, for a box that is only 100dp tall.
 *
 * Seven children. A Glance container keeps ten and drops the rest without a word, so the seam is
 * one element and not a line between two spacers (WidgetRemoteViewsTest counts what arrives).
 */
@Composable
private fun StandardRig(text: RigWidgetText, look: RigLook, type: WidgetType, clock: ShiftClock?) {
    val colors = GlanceTheme.colors
    Column(GlanceModifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 6.dp)) {
        BrandRow(text.heat, look, type, labelSize = 10f)
        Text(text.headline, style = type.word(20f, look.word), maxLines = 1)
        // A ledger row: the status on the left, wrapping if it must, and the time whole on the right.
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(text.line, modifier = GlanceModifier.defaultWeight(), style = type.body(12f, colors.onSurfaceVariant), maxLines = 2)
            if (clock != null) {
                Spacer(GlanceModifier.width(6.dp))
                ShiftChronometer(clock, type.spValue(12f))
            }
        }
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

private val STANDARD_MIN_HEIGHT = 90.dp
private const val PIXELS = 5
