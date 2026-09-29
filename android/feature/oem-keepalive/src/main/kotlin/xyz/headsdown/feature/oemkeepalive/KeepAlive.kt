package xyz.headsdown.feature.oemkeepalive

import android.app.Activity
import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import java.util.concurrent.TimeUnit

sealed interface LaunchResult {
    /** [index] into the candidate list; [fallback] when only the App info page was reachable. */
    data class Launched(val index: Int, val fallback: Boolean) : LaunchResult
    data object NothingResolved : LaunchResult
}

/** Android-side helpers for the keep-alive onboarding. */
class KeepAlive(private val context: Context) {

    val profile: OemProfile by lazy {
        OemDetector.detect(Build.MANUFACTURER.orEmpty(), Build.BRAND.orEmpty(), Build.VERSION.INCREMENTAL.orEmpty(), ::getprop)
    }

    fun isIgnoringBatteryOptimizations(): Boolean =
        context.getSystemService<PowerManager>()?.isIgnoringBatteryOptimizations(context.packageName) == true

    /** Tries each candidate for [step] in order; never throws. */
    fun open(step: KeepAliveStep, appLabel: String): LaunchResult {
        val candidates = KeepAliveIntents.candidates(step, profile, context.packageName, appLabel)
        candidates.forEachIndexed { index, spec ->
            val intent = spec.toIntent()
            if (!isLaunchable(intent)) return@forEachIndexed
            try {
                if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return LaunchResult.Launched(index, spec.isAppDetailsFallback)
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
        return LaunchResult.NothingResolved
    }

    /** Process deaths Android recorded for this app (Android 11+). */
    fun recentExits(max: Int = 10): List<ProcessExit> {
        val am = context.getSystemService<ActivityManager>() ?: return emptyList()
        return am.getHistoricalProcessExitReasons(context.packageName, 0, max).map {
            ProcessExit(it.timestamp, ShiftHealthCheck.causeOf(it.reason), it.description)
        }
    }

    private fun isLaunchable(intent: Intent): Boolean {
        val pm = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= 33) {
            pm.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        } ?: return false
        // Another app's non-exported activity would throw SecurityException on launch.
        return info.activityInfo?.exported == true || info.activityInfo?.packageName == context.packageName
    }

    private fun IntentSpec.toIntent(): Intent = Intent().also { intent ->
        action?.let(intent::setAction)
        if (packageName != null && className != null) intent.component = ComponentName(packageName, className)
        dataUri?.let { intent.data = it.toUri() }
        extras.forEach { (k, v) -> intent.putExtra(k, v) }
    }

    private companion object {
        /** Reads a system property via `getprop`, avoiding hidden-API reflection. */
        fun getprop(key: String): String? = try {
            val process = ProcessBuilder("getprop", key).redirectErrorStream(true).start()
            val finished = process.waitFor(500, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroy()
                null
            } else {
                process.inputStream.bufferedReader().use { it.readText().trim() }.ifEmpty { null }
            }
        } catch (_: Exception) {
            null
        }
    }
}
