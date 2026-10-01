package xyz.headsdown

import android.util.Log

/**
 * Debug-build logging for protocol refusals (crank acks, registrar fallbacks). Callers pass
 * counters and codes only: never a key, signature, token, frame or URL. Release builds compile
 * the branch away (BuildConfig.DEBUG is false) and R8 strips android.util.Log anyway.
 */
internal object DebugLog {
    fun d(tag: String, message: String) {
        if (BuildConfig.DEBUG) Log.d(tag, message)
    }
}
