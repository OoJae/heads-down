package xyz.headsdown.devtools

import android.content.Context
import android.content.Intent

/**
 * Release variant: the rig debug screen does not exist. Same API as the debug and localdev
 * variant (src/devtools), so app code can ask without build-type branches.
 */
object RigDebug {
    @Suppress("UNUSED_PARAMETER", "FunctionOnlyReturningConstant")
    fun intent(context: Context): Intent? = null
}
