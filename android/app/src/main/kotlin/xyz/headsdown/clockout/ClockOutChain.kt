package xyz.headsdown.clockout

import xyz.headsdown.core.chain.Pubkey
import xyz.headsdown.core.chain.clockout.ClockOutRequest
import xyz.headsdown.core.chain.clockout.ClockOutService
import xyz.headsdown.core.chain.clockout.PreparedClockOut
import xyz.headsdown.core.wallet.WalletCapabilities
import javax.inject.Inject
import javax.inject.Singleton

/** The chain side of a clock-out, as the screen needs it. [ChainClockOut] is the real one. */
interface ClockOutChain {
    /** Read-only: what a clock-out for [authority] would do now. */
    suspend fun preview(authority: Pubkey): ClockOutFacts

    /** Builds what the wallet signs, inside the wallet session. Null: nothing to sign. */
    suspend fun prepare(authority: Pubkey, request: ClockOutRequest, capabilities: WalletCapabilities): PreparedClockOut?
}

@Singleton
class ChainClockOut @Inject constructor(private val service: ClockOutService) : ClockOutChain {
    override suspend fun preview(authority: Pubkey): ClockOutFacts = ClockOutFacts.from(service.preview(authority))

    override suspend fun prepare(authority: Pubkey, request: ClockOutRequest, capabilities: WalletCapabilities): PreparedClockOut? =
        service.prepare(authority, request, capabilities)
}
