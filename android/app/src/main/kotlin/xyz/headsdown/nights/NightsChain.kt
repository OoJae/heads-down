package xyz.headsdown.nights

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import xyz.headsdown.BuildConfig
import xyz.headsdown.config.BuildTransports
import xyz.headsdown.config.EndpointKind
import xyz.headsdown.config.EndpointPolicy
import xyz.headsdown.config.EndpointVerdict
import xyz.headsdown.core.chain.HeadsDownProgram
import xyz.headsdown.core.chain.Pubkey
import xyz.headsdown.core.chain.accounts.HeadsDownAccounts
import xyz.headsdown.core.chain.indexer.IndexerHaulClient
import xyz.headsdown.core.chain.indexer.IndexerShift
import xyz.headsdown.core.chain.rpc.SolanaJsonRpc
import javax.inject.Inject
import javax.inject.Singleton

/** What the night log reads, as the screen needs it. Two reads, and nothing is ever sent. [ChainNights] is the real one. */
interface NightsChain {
    /**
     * What the Rig account of [authority]'s rig holds, read through the checked decoder. Null when
     * there is no rig at the address now (never registered, or closed). Throws when the chain
     * cannot be read.
     */
    suspend fun rig(authority: Pubkey): RigNights?

    /**
     * Every finished shift the indexer lists for that rig, newest first. Null when this build has
     * no indexer. Throws when the indexer cannot be read.
     */
    suspend fun shifts(authority: Pubkey): List<IndexerShift>?
}

@Singleton
class ChainNights @Inject constructor(
    private val rpc: SolanaJsonRpc,
    client: OkHttpClient,
) : NightsChain {

    /** As for the morning haul: an empty INDEXER_URL builds an app with no indexer. */
    private val indexer: IndexerHaulClient? = run {
        val url = BuildConfig.INDEXER_URL
        val verdict = EndpointPolicy.require(EndpointKind.INDEXER, url, BuildConfig.LOOPBACK_CLEARTEXT_ALLOWED)
        if (verdict == EndpointVerdict.Disabled) null else IndexerHaulClient(BuildTransports.transports.http(url, client))
    }

    override suspend fun rig(authority: Pubkey): RigNights? {
        // The address is derived, never stored: the decoder checks the account is that rig's.
        val address = HeadsDownProgram.rig(authority).address
        return HeadsDownAccounts.rigOrNull(address, rpc.getAccountInfo(address))?.let(RigNights::from)
    }

    override suspend fun shifts(authority: Pubkey): List<IndexerShift>? = indexer?.shifts(HeadsDownProgram.rig(authority).address)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class NightsModule {
    @Binds abstract fun nights(impl: ChainNights): NightsChain
}
