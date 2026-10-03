package xyz.headsdown.withdraw

import xyz.headsdown.core.chain.Pubkey
import xyz.headsdown.core.chain.withdraw.PreparedWithdraw
import xyz.headsdown.core.chain.withdraw.WithdrawRequest
import xyz.headsdown.core.chain.withdraw.WithdrawService
import xyz.headsdown.core.wallet.WalletCapabilities
import javax.inject.Inject
import javax.inject.Singleton

/** The chain side of a withdrawal, as the screen needs it. [ChainWithdraw] is the real one. */
interface WithdrawChain {
    /** Read-only: what [authority] could take back now. */
    suspend fun preview(authority: Pubkey): WithdrawFacts

    /** Builds what the wallet signs, inside the wallet session. Null: nothing to sign. */
    suspend fun prepare(authority: Pubkey, request: WithdrawRequest, capabilities: WalletCapabilities): PreparedWithdraw?
}

@Singleton
class ChainWithdraw @Inject constructor(private val service: WithdrawService) : WithdrawChain {
    override suspend fun preview(authority: Pubkey): WithdrawFacts = WithdrawFacts.from(service.preview(authority))

    override suspend fun prepare(authority: Pubkey, request: WithdrawRequest, capabilities: WalletCapabilities): PreparedWithdraw? =
        service.prepare(authority, request, capabilities)
}
