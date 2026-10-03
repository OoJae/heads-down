package xyz.headsdown.rig

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import xyz.headsdown.core.chain.Skr
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Focus Bond the user chose for their next shifts, in SKR base units (0 = none). Stored on
 * this phone only.
 *
 * A Focus Bond is SKR locked on a shift at clock-in (INTERFACE §11.6). It comes back when the
 * shift seals `completed`; after a break it goes to the Bury lot, where it is sold for ORE that
 * ORE's own `bury` burns. It never goes to the team. The amounts on offer are small and fixed:
 * this is a promise to oneself, not a position.
 */
@Singleton
class FocusBondSetting @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val _amount = MutableStateFlow(load())

    /** SKR base units locked at the next clock-in; always one of [CHOICES]. */
    val amount: StateFlow<ULong> = _amount.asStateFlow()

    fun set(skr: ULong) {
        require(skr in CHOICES) { "not an offered Focus Bond" }
        prefs.edit { putLong(KEY, skr.toLong()) }
        _amount.value = skr
    }

    private fun load(): ULong = prefs.getLong(KEY, 0L).toULong().takeIf { it in CHOICES } ?: 0uL

    companion object {
        /** None, 10, 50 or 100 SKR: all far below the program's 5,000 SKR cap. */
        val CHOICES: List<ULong> = listOf(0uL, 10uL * Skr.ONE_SKR, 50uL * Skr.ONE_SKR, 100uL * Skr.ONE_SKR)

        /** "10 SKR" for a whole number of SKR (every choice is one). */
        fun label(skr: ULong): String = if (skr == 0uL) "Off" else "${skr / Skr.ONE_SKR} SKR"

        private const val FILE = "hd_focus_bond"
        private const val KEY = "amount"
    }
}
