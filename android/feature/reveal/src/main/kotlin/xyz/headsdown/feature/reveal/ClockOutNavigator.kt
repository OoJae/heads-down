package xyz.headsdown.feature.reveal

import android.content.Context
import android.content.Intent

/**
 * Opens the screen where the user clocks out (seals the shift, takes back a bond, claims ORE).
 * The app provides it; the reveal knows no screen but its own.
 */
fun interface ClockOutNavigator {
    fun intent(context: Context): Intent
}
