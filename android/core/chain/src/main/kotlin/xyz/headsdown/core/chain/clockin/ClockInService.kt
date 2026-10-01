package xyz.headsdown.core.chain.clockin

import xyz.headsdown.core.chain.HeadsDownProgram
import xyz.headsdown.core.chain.Ore
import xyz.headsdown.core.chain.Pubkey
import xyz.headsdown.core.chain.accounts.HeadsDownAccounts
import xyz.headsdown.core.chain.accounts.OreAccounts
import xyz.headsdown.core.chain.accounts.RigAccount
import xyz.headsdown.core.chain.registrar.RegistrarVoucher
import xyz.headsdown.core.chain.rpc.SolanaJsonRpc
import xyz.headsdown.core.chain.tx.TransactionBuilder
import xyz.headsdown.core.chain.tx.TxVersion
import xyz.headsdown.core.wallet.PreparedTransactions
import xyz.headsdown.core.wallet.WalletCapabilities

/** The clock-in transaction, serialized for MWA, plus what it will do once confirmed. */
class PreparedClockIn(
    transaction: ByteArray,
    lastValidBlockHeight: Long,
    val authority: Pubkey,
    val plan: ClockInPlan,
    val version: TxVersion,
) : PreparedTransactions(listOf(transaction), lastValidBlockHeight)

/**
 * Reads the chain state a clock-in depends on, composes the single transaction
 * ([ClockInComposer]) and serializes it for the wallet's `signAndSendTransactions`.
 *
 * Every account is decoded through the owner/size/PDA-checked decoders; anything unexpected
 * throws, which the wallet session turns into "nothing was sent". The transaction version
 * follows the wallet's capabilities (v0 when supported, else legacy).
 */
class ClockInService(
    private val rpc: SolanaJsonRpc,
    private val nowUnix: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    /**
     * @param voucher a registrar voucher for this wallet and key, if one is held. The composer
     *   includes it only when the program will accept it; otherwise the rig is a guest.
     * @return null when the heads_down Config does not exist on this cluster (program not
     *   deployed or not initialized): there is nothing to arm on-chain.
     */
    suspend fun prepare(
        authority: Pubkey,
        rigKey: ByteArray,
        request: ClockInRequest,
        capabilities: WalletCapabilities,
        voucher: RegistrarVoucher? = null,
    ): PreparedClockIn? {
        val configAddress = HeadsDownProgram.config.address
        val rigAddress = HeadsDownProgram.rig(authority).address
        val automationAddress = Ore.automation(authority).address
        val (configInfo, rigInfo, automationInfo) = rpc.getMultipleAccounts(listOf(configAddress, rigAddress, automationAddress))
        if (configInfo == null) return null
        // The blockhash's context slot dates the voucher expiry check (expiry_slot > Clock.slot).
        val blockhash = rpc.getLatestBlockhash()
        val state = ClockInChainState(
            config = HeadsDownAccounts.config(configAddress, configInfo),
            rig = rigInfo?.let { HeadsDownAccounts.rig(rigAddress, it) },
            automation = automationInfo?.let { OreAccounts.automation(automationAddress, it) },
            slot = blockhash.contextSlot.takeIf { it >= 0 }?.toULong(),
        )
        val plan = ClockInComposer.compose(authority, rigKey, request, state, nowUnix(), voucher)
        val version = if (capabilities.supportsV0) TxVersion.V0 else TxVersion.LEGACY
        val message = TransactionBuilder.compile(authority, plan.instructions, blockhash.blockhash, version)
        return PreparedClockIn(TransactionBuilder.unsignedTransaction(message), blockhash.lastValidBlockHeight, authority, plan, version)
    }

    /** The authority's Rig (checked decode), or null if it does not exist. */
    suspend fun readRig(authority: Pubkey): RigAccount? {
        val address = HeadsDownProgram.rig(authority).address
        return rpc.getAccountInfo(address)?.let { HeadsDownAccounts.rig(address, it) }
    }
}
