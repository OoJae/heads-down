package xyz.headsdown.feature.shift.foreman

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Named on/off switches. SharedPreferences in the app, a map in tests. */
internal interface FlagStore {
    fun get(key: String, default: Boolean): Boolean

    fun put(key: String, value: Boolean)
}

internal class PrefsFlagStore(context: Context) : FlagStore {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun get(key: String, default: Boolean): Boolean = prefs.getBoolean(key, default)

    override fun put(key: String, value: Boolean) = prefs.edit { putBoolean(key, value) }

    private companion object {
        const val FILE = "hd_foreman"
    }
}

/**
 * The switches for what the Foreman models may do on their own.
 *
 * - [autoArmEnabled] is OFF until the user turns it on. With it off, [AutoArmPolicy] holds
 *   whatever else is true, so no shift is ever armed without a tap. It stays off by default
 *   until auto-arm has been run on a real device (FOREMAN.md, "Auto-arm").
 * - [pickupBreaksEnabled] is ON. Turning it off takes the pickup classifier out of the shift:
 *   the deterministic rules (tilt, screen-on, unlock, unplug) are all that is left, exactly as
 *   before the model existed. It is the way back if the model, which has only ever seen
 *   synthetic data, misbehaves on a real phone. Neither position can make a rig hot.
 */
@Singleton
class ForemanSettings internal constructor(private val store: FlagStore) {
    @Inject constructor(@ApplicationContext context: Context) : this(PrefsFlagStore(context))

    /** Arm a shift with no tap when the phone is laid face-down on the charger inside a confident planned window. */
    var autoArmEnabled: Boolean
        get() = store.get(KEY_AUTO_ARM, AUTO_ARM_DEFAULT)
        set(value) = store.put(KEY_AUTO_ARM, value)

    /** Let a pickup verdict of the classifier break a hot shift (BREAK reason 1). */
    var pickupBreaksEnabled: Boolean
        get() = store.get(KEY_PICKUP_BREAKS, PICKUP_BREAKS_DEFAULT)
        set(value) = store.put(KEY_PICKUP_BREAKS, value)

    companion object {
        /** Off. Changing this default needs the on-device tests in FOREMAN.md first. */
        const val AUTO_ARM_DEFAULT = false
        const val PICKUP_BREAKS_DEFAULT = true
        internal const val KEY_AUTO_ARM = "auto_arm"
        internal const val KEY_PICKUP_BREAKS = "pickup_breaks"
    }
}
