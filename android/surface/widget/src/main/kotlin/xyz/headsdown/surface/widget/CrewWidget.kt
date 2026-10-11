package xyz.headsdown.surface.widget

import android.content.Context
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.Text

/**
 * PLACEHOLDER. The "Crew" dark-presence row (who in your room is dark right now) needs rooms,
 * which do not exist yet. Until then this widget says exactly that: empty seats, a "preview"
 * tag, and no invented people or numbers.
 */
class CrewWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Single
    override val previewSizeMode = SizeMode.Single

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val mode = WidgetColorPolicy.mode(Build.VERSION.SDK_INT, WidgetStateStore.get(context).preferBrandColors)
        val open = RigWidget.openAppAction(context)
        provideContent {
            GlanceTheme(colors = WidgetColors.providers(mode)) { CrewPlaceholderContent(mode, open) }
        }
    }

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        val mode = WidgetColorPolicy.mode(Build.VERSION.SDK_INT, WidgetStateStore.get(context).preferBrandColors)
        provideContent {
            GlanceTheme(colors = WidgetColors.providers(mode)) { CrewPlaceholderContent(mode, open = null) }
        }
    }

    companion object {
        val SIZE = DpSize(250.dp, 90.dp)
    }
}

class CrewWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = CrewWidget()
}

/** Copy for the placeholder, in one place for the tests. */
object CrewPlaceholderCopy {
    const val TITLE = "CREW"
    const val TAG = "PREVIEW · NOT LIVE"
    const val LINE = "Rooms are not live yet. When they are, this row shows who in your room is dark."
    const val EMPTY_SEATS = 5
    const val DESCRIPTION = "Heads Down crew widget, placeholder. Rooms are not live yet, so no one is shown."
}

@Composable
internal fun CrewPlaceholderContent(mode: WidgetColorMode, open: Action?) {
    val colors = GlanceTheme.colors
    // One row of the home screen, full at 1.0: the text grows only as far as the box is taller than 90dp.
    val type = WidgetType(LocalContext.current.resources, max = LocalSize.current.height.value / CrewWidget.SIZE.height.value)
    WidgetSlab(mode, CrewPlaceholderCopy.DESCRIPTION, open) {
        Column(GlanceModifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(CrewPlaceholderCopy.TITLE, style = type.label(11f, colors.onSurfaceVariant), maxLines = 1)
                Spacer(GlanceModifier.width(8.dp))
                // Plain ink, not an accent: "not live" is a fact about this widget, not a problem with the rig.
                Text(CrewPlaceholderCopy.TAG, style = type.label(11f, colors.onSurface), maxLines = 1)
            }
            Spacer(GlanceModifier.height(5.dp))
            // Empty seats: outlined squares, never filled, because nobody is really here.
            Row(verticalAlignment = Alignment.CenterVertically) {
                repeat(CrewPlaceholderCopy.EMPTY_SEATS) { i ->
                    if (i > 0) Spacer(GlanceModifier.width(8.dp))
                    Box(GlanceModifier.size(SEAT).background(colors.outline).padding(SEAT_EDGE)) {
                        Box(GlanceModifier.fillMaxSize().background(colors.surface)) {}
                    }
                }
            }
            Spacer(GlanceModifier.height(5.dp))
            Text(CrewPlaceholderCopy.LINE, style = type.body(12f, colors.onSurfaceVariant), maxLines = 2)
        }
    }
}

private val SEAT = 14.dp
private val SEAT_EDGE = 2.dp
