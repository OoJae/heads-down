package xyz.headsdown.feature.shift.devlog

import android.content.Context
import android.content.Intent

/**
 * Entry point to the DEV-ONLY sensor lab. This is the debug-variant implementation; the release
 * variant (src/release) has the same API and always answers "not available", and none of the
 * lab's classes, services or manifest entries exist in a release build.
 */
object SensorLab {
    const val AVAILABLE: Boolean = true

    /** The lab screen, or null where the lab does not exist (release builds). */
    fun intent(context: Context): Intent? = Intent(context, SensorLabActivity::class.java)
}
