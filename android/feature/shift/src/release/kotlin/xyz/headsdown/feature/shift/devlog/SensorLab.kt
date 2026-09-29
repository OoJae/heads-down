package xyz.headsdown.feature.shift.devlog

import android.content.Context
import android.content.Intent

/**
 * Release variant: the sensor lab does not exist. Same API as the debug variant, so app code
 * can ask without any build-type branches; the recorder, its service, screen, FileProvider and
 * manifest entries live only in src/debug.
 */
object SensorLab {
    const val AVAILABLE: Boolean = false

    @Suppress("UNUSED_PARAMETER", "FunctionOnlyReturningConstant")
    fun intent(context: Context): Intent? = null
}
