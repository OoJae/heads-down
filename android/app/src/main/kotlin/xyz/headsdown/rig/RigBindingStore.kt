package xyz.headsdown.rig

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import xyz.headsdown.core.chain.HeadsDownProgram
import xyz.headsdown.core.chain.Pubkey
import xyz.headsdown.feature.shift.RigBinding
import xyz.headsdown.feature.shift.RigBindingProvider
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The program + Rig account heartbeats bind to. Stored only after a clock-in confirmed on-chain
 * with `err == null` (so the Rig exists), as the wallet authority; the Rig address is always
 * re-derived as `[b"rig", authority]` under the pinned program id, never stored or trusted.
 * Before that it is [RigBinding.UNREGISTERED] (all zeros), which can never verify on-chain.
 */
@Singleton
class RigBindingStore @Inject constructor(
    @ApplicationContext context: Context,
) : RigBindingProvider {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    @Volatile private var cached: RigBinding? = null

    override fun current(): RigBinding {
        cached?.let { return it }
        val authority = authority() ?: return RigBinding.UNREGISTERED
        return bind(authority).also { cached = it }
    }

    /** The bound wallet, or null before the first confirmed clock-in. */
    fun authority(): Pubkey? = prefs.getString(KEY_AUTHORITY, null)?.let { runCatching { Pubkey.fromBase58(it) }.getOrNull() }

    fun save(authority: Pubkey) {
        prefs.edit(commit = true) { putString(KEY_AUTHORITY, authority.toBase58()) }
        cached = bind(authority)
    }

    fun clear() {
        prefs.edit(commit = true) { remove(KEY_AUTHORITY) }
        cached = null
    }

    private fun bind(authority: Pubkey) =
        RigBinding(HeadsDownProgram.ID.bytes, HeadsDownProgram.rig(authority).address.bytes)

    private companion object {
        const val FILE = "hd_rig_binding"
        const val KEY_AUTHORITY = "authority"
    }
}
