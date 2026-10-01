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
 * The user's switches for what the Foreman models may do on their own.
 *
 * There is one, and it is OFF until the user turns it on: [autoArmEnabled]. With it off,
 * [AutoArmPolicy] holds whatever else is true, so no shift is ever armed without a tap. It stays
 * off by default until auto-arm has been run on a real device (FOREMAN.md, "Auto-arm").
 */
@Singleton
class ForemanSettings internal constructor(private val store: FlagStore) {
    @Inject constructor(@ApplicationContext context: Context) : this(PrefsFlagStore(context))

    /** Arm a shift with no tap when the phone is laid face-down on the charger inside a confident planned window. */
    var autoArmEnabled: Boolean
        get() = store.get(KEY_AUTO_ARM, AUTO_ARM_DEFAULT)
        set(value) = store.put(KEY_AUTO_ARM, value)

    companion object {
        /** Off. Changing this default needs the on-device tests in FOREMAN.md first. */
        const val AUTO_ARM_DEFAULT = false
        internal const val KEY_AUTO_ARM = "auto_arm"
    }
}
