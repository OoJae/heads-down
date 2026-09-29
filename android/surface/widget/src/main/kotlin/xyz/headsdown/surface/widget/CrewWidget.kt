package xyz.headsdown.surface.widget

import android.content.Context
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.cornerRadius
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
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontFamily
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle

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
        val mode = WidgetColorPolicy.mode(Build.VERSION.SDK_INT)
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
    val accents = WidgetAccents(mode)
    var root = GlanceModifier
        .fillMaxSize()
        .background(colors.surface)
        .cornerRadius(20.dp)
        .padding(horizontal = 14.dp, vertical = 12.dp)
        .semantics { contentDescription = CrewPlaceholderCopy.DESCRIPTION }
    if (open != null) root = root.clickable(open)
    Column(root) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                CrewPlaceholderCopy.TITLE,
                style = TextStyle(color = colors.onSurfaceVariant, fontSize = 11.sp, fontFamily = FontFamily.Monospace),
            )
            Spacer(GlanceModifier.width(8.dp))
            Text(
                CrewPlaceholderCopy.TAG,
                style = TextStyle(color = accents.cooling, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace),
            )
        }
        Spacer(GlanceModifier.height(8.dp))
        // Empty seats: outlined squares, never filled, because nobody is really here.
        Row(verticalAlignment = Alignment.CenterVertically) {
            repeat(CrewPlaceholderCopy.EMPTY_SEATS) { i ->
                if (i > 0) Spacer(GlanceModifier.width(8.dp))
                Box(GlanceModifier.size(18.dp).background(colors.outline).padding(2.dp)) {
                    Box(GlanceModifier.size(14.dp).background(colors.surface)) {}
                }
            }
        }
        Spacer(GlanceModifier.height(8.dp))
        Text(CrewPlaceholderCopy.LINE, style = TextStyle(color = colors.onSurfaceVariant, fontSize = 12.sp), maxLines = 2)
    }
}
