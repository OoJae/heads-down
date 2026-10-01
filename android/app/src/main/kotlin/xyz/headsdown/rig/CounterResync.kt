package xyz.headsdown.rig

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import xyz.headsdown.core.chain.Pubkey
import xyz.headsdown.core.chain.accounts.HeadsDownAccounts
import xyz.headsdown.core.chain.rpc.SolanaJsonRpc
import xyz.headsdown.core.keys.RigCounter
import xyz.headsdown.feature.shift.RigBindingProvider

/**
 * Re-reads the bound Rig's on-chain `hb_counter` and raises the local message counter above it.
 * Triggered when the crank acks a frame with `stale_counter` (contract A): the next message then
 * carries a counter the program accepts. Raising a floor never lowers it, so a lying crank can at
 * most make the phone skip counter values. Rate-limited to one read per [minIntervalMillis].
 */
class CounterResync(
    private val rpc: SolanaJsonRpc,
    private val binding: RigBindingProvider,
    private val counter: RigCounter,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val minIntervalMillis: Long = MIN_INTERVAL_MILLIS,
) {
    @Volatile private var lastAt: Long? = null

    /** Schedules a resync unless one ran within [minIntervalMillis]. Never blocks or throws. */
    fun request() {
        val now = clock()
        synchronized(this) {
            val last = lastAt
            if (last != null && now - last < minIntervalMillis) return
            lastAt = now
        }
        scope.launch { runCatching { resync() } }
    }

    /** @return the on-chain counter the local one now sits at or above, or null when unknown. */
    suspend fun resync(): ULong? {
        val bound = binding.current()
        if (!bound.isRegistered) return null
        val address = Pubkey(bound.rigAddress)
        val rig = rpc.getAccountInfo(address)?.let { HeadsDownAccounts.rig(address, it) } ?: return null
        counter.raiseFloor(rig.hbCounter)
        return rig.hbCounter
    }

    companion object {
        const val MIN_INTERVAL_MILLIS = 60_000L
    }
}
