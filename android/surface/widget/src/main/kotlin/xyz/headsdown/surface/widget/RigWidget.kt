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
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.AndroidRemoteViews
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
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
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontFamily
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider

/**
 * The "Rig" home-screen widget: rig heat, a chronometer that ticks the shift without any app
 * update, the last haul and the streak. Tapping opens the app.
 */
class RigWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Responsive(setOf(COMPACT, STANDARD, WIDE))
    override val previewSizeMode = SizeMode.Responsive(setOf(STANDARD, WIDE))

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
        val COMPACT = DpSize(110.dp, 40.dp)
        val STANDARD = DpSize(180.dp, 100.dp)
        val WIDE = DpSize(260.dp, 100.dp)

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
    val heatColor = accents.forHeat(text.heat, colors.onSurfaceVariant)
    val compact = LocalSize.current.height < STANDARD_MIN_HEIGHT
    var root = GlanceModifier
        .fillMaxSize()
        .background(colors.surface)
        .cornerRadius(20.dp)
        .padding(horizontal = 14.dp, vertical = if (compact) 6.dp else 12.dp)
        .semantics { contentDescription = text.contentDescription }
    if (open != null) root = root.clickable(open)

    if (compact) {
        Row(root, verticalAlignment = Alignment.CenterVertically) {
            HeatPixels(text.heat, heatColor, colors.outline)
            Spacer(GlanceModifier.width(8.dp))
            Text(text.headline, style = TextStyle(color = heatColor, fontSize = 16.sp, fontWeight = FontWeight.Bold), maxLines = 1)
            Spacer(GlanceModifier.width(8.dp))
            val since = text.chronometerSinceWallMillis
            if (since != null) {
                ShiftChronometer(since, mode, previewNowWallMillis, textSizeSp = 16f)
            } else {
                Text(text.line, style = TextStyle(color = colors.onSurfaceVariant, fontSize = 12.sp), maxLines = 1)
            }
        }
        return
    }

    Column(root) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HeatPixels(text.heat, heatColor, colors.outline)
            Spacer(GlanceModifier.width(8.dp))
            Text(
                "HEADS DOWN",
                style = TextStyle(color = colors.onSurfaceVariant, fontSize = 11.sp, fontFamily = FontFamily.Monospace),
                maxLines = 1,
            )
        }
        Spacer(GlanceModifier.height(6.dp))
        Text(text.headline, style = TextStyle(color = heatColor, fontSize = 24.sp, fontWeight = FontWeight.Bold), maxLines = 1)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text.line, style = TextStyle(color = colors.onSurfaceVariant, fontSize = 13.sp), maxLines = 1)
            val since = text.chronometerSinceWallMillis
            if (since != null) {
                Spacer(GlanceModifier.width(6.dp))
                ShiftChronometer(since, mode, previewNowWallMillis, textSizeSp = 15f)
            }
        }
        Spacer(GlanceModifier.defaultWeight())
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(text.haul, style = TextStyle(color = accents.gold, fontSize = 13.sp, fontWeight = FontWeight.Medium), maxLines = 1)
            Spacer(GlanceModifier.width(10.dp))
            Text(text.streak, style = TextStyle(color = colors.onSurface, fontSize = 13.sp), maxLines = 1)
        }
    }
}

/** Five pixels that fill with heat: a pixel-art accent that reads at 1x1 cell size. */
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
 * start of the dark time.
 */
@Composable
private fun ShiftChronometer(sinceWallMillis: Long, mode: WidgetColorMode, previewNowWallMillis: Long?, textSizeSp: Float) {
    val context = LocalContext.current
    val elapsedNow = SystemClock.elapsedRealtime()
    val wallNow = previewNowWallMillis ?: System.currentTimeMillis()
    val views = RemoteViews(context.packageName, R.layout.hd_widget_chronometer).apply {
        val id = R.id.hd_widget_chronometer
        // A generated preview is a snapshot: it shows the elapsed time, not a running clock.
        setChronometer(id, ChronometerAnchor.base(sinceWallMillis, wallNow, elapsedNow), null, previewNowWallMillis == null)
        setTextViewTextSize(id, android.util.TypedValue.COMPLEX_UNIT_SP, textSizeSp)
        when (mode) {
            WidgetColorMode.BRAND -> setTextColor(id, WidgetPalette.ASH.toInt())
            WidgetColorMode.DYNAMIC -> setColorAttr(id, "setTextColor", android.R.attr.textColorPrimary)
        }
    }
    AndroidRemoteViews(views)
}

private val STANDARD_MIN_HEIGHT = 90.dp
private const val PIXELS = 5
