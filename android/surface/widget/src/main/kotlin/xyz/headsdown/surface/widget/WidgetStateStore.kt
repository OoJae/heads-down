package xyz.headsdown.surface.widget

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The one copy of what every Rig widget shows: SharedPreferences (so it survives the process)
 * plus a [StateFlow] the Glance composition collects (so an open session recomposes at once).
 * The file sits in the `sharedpref` domain, which the app's backup rules exclude.
 */
class WidgetStateStore internal constructor(private val prefs: SharedPreferences) {

    private val _state = MutableStateFlow(read())
    val state: StateFlow<RigWidgetState> = _state.asStateFlow()

    /** Widget colours follow the wallpaper unless the user picked the Heads Down palette. */
    var preferBrandColors: Boolean
        get() = prefs.getBoolean(K_BRAND, false)
        set(value) = prefs.edit { putBoolean(K_BRAND, value) }

    /** Applies [transform] atomically; returns (before, after). */
    @Synchronized
    fun update(transform: (RigWidgetState) -> RigWidgetState): Pair<RigWidgetState, RigWidgetState> {
        val before = _state.value
        val after = transform(before)
        if (after != before) {
            write(after)
            _state.value = after
        }
        return before to after
    }

    private fun read(): RigWidgetState {
        val heat = prefs.getString(K_HEAT, null)?.let { name -> RigHeat.entries.firstOrNull { it.name == name } }
            ?: RigHeat.COLD
        val haulAtoms = prefs.getLong(K_HAUL_ATOMS, -1)
        return RigWidgetState(
            rig = WidgetRig(
                heat = heat,
                darkSinceWallMillis = prefs.getLong(K_SINCE, -1).takeIf { it >= 0 },
                darkRounds = prefs.getInt(K_ROUNDS, 0).coerceAtLeast(0),
                focusOnly = prefs.getBoolean(K_FOCUS, false),
                canDig = prefs.getBoolean(K_CAN_DIG, true),
            ),
            lastShiftRounds = prefs.getInt(K_LAST_ROUNDS, -1).takeIf { it >= 0 },
            haul = if (haulAtoms < 0) null else WidgetHaul(haulAtoms, prefs.getLong(K_HAUL_ENDED, 0)),
            streakNights = prefs.getInt(K_STREAK, -1).takeIf { it >= 0 },
            bootCount = prefs.getInt(K_BOOT, -1).takeIf { it >= 0 },
        )
    }

    private fun write(s: RigWidgetState) = prefs.edit {
        putString(K_HEAT, s.rig.heat.name)
        putLong(K_SINCE, s.rig.darkSinceWallMillis ?: -1)
        putInt(K_ROUNDS, s.rig.darkRounds)
        putBoolean(K_FOCUS, s.rig.focusOnly)
        putBoolean(K_CAN_DIG, s.rig.canDig)
        putInt(K_LAST_ROUNDS, s.lastShiftRounds ?: -1)
        putLong(K_HAUL_ATOMS, s.haul?.oreAtoms ?: -1)
        putLong(K_HAUL_ENDED, s.haul?.endedAtWallMillis ?: 0)
        putInt(K_STREAK, s.streakNights ?: -1)
        putInt(K_BOOT, s.bootCount ?: -1)
    }

    companion object {
        private const val FILE = "hd_widget_state"
        private const val K_HEAT = "heat"
        private const val K_SINCE = "dark_since"
        private const val K_ROUNDS = "dark_rounds"
        private const val K_FOCUS = "focus_only"
        private const val K_CAN_DIG = "can_dig"
        private const val K_LAST_ROUNDS = "last_shift_rounds"
        private const val K_HAUL_ATOMS = "haul_ore_atoms"
        private const val K_HAUL_ENDED = "haul_ended_at"
        private const val K_STREAK = "streak_nights"
        private const val K_BOOT = "boot_count"
        private const val K_BRAND = "prefer_brand_colors"

        @Volatile private var instance: WidgetStateStore? = null

        fun get(context: Context): WidgetStateStore = instance ?: synchronized(this) {
            instance ?: WidgetStateStore(
                context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE),
            ).also { instance = it }
        }
    }
}

/** `Settings.Global.BOOT_COUNT`, or null where the OEM hides it. */
object BootCount {
    fun read(context: Context): Int? =
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1).takeIf { it >= 0 }
}
