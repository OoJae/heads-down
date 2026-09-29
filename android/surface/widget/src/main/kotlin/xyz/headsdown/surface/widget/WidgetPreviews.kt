package xyz.headsdown.surface.widget

import android.content.Context
import android.os.Build
import androidx.core.content.edit
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import kotlin.reflect.KClass

/**
 * Generated widget-picker previews (Android 15+): the real composables, rendered once per app
 * version from [RigWidget.providePreview] / [CrewWidget.providePreview]. Older pickers use the
 * static `previewLayout`s in `res/layout`.
 */
object WidgetPreviews {
    const val GENERATED_PREVIEWS_MIN_SDK = 35

    val receivers: List<KClass<out GlanceAppWidgetReceiver>> = listOf(RigWidgetReceiver::class, CrewWidgetReceiver::class)

    /** Publishing is rate-limited by the system, so do it once per app version. */
    fun shouldPublish(sdkInt: Int, publishedVersion: Long?, currentVersion: Long): Boolean =
        sdkInt >= GENERATED_PREVIEWS_MIN_SDK && publishedVersion != currentVersion

    /** Call from a background coroutine at app start. Returns true if previews were published. */
    suspend fun publishIfNeeded(context: Context, appVersion: Long): Boolean {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val published = prefs.getLong(K_VERSION, -1).takeIf { it >= 0 }
        if (!shouldPublish(Build.VERSION.SDK_INT, published, appVersion)) return false
        if (Build.VERSION.SDK_INT < GENERATED_PREVIEWS_MIN_SDK) return false
        val manager = GlanceAppWidgetManager(context)
        val ok = receivers.all { receiver ->
            runCatching { manager.setWidgetPreviews(receiver) == GlanceAppWidgetManager.SET_WIDGET_PREVIEWS_RESULT_SUCCESS }
                .getOrDefault(false)
        }
        if (ok) prefs.edit { putLong(K_VERSION, appVersion) } // rate-limited: retry next launch
        return ok
    }

    private const val FILE = "hd_widget_previews"
    private const val K_VERSION = "published_version"
}
