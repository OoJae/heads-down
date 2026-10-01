package xyz.headsdown.di

import android.content.Context
import android.os.SystemClock
import com.solana.mobilewalletadapter.clientlib.MobileWalletAdapter
import com.solana.mobilewalletadapter.clientlib.Solana
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import xyz.headsdown.BuildConfig
import xyz.headsdown.DebugLog
import xyz.headsdown.config.BuildTransports
import xyz.headsdown.config.EndpointKind
import xyz.headsdown.config.EndpointPolicy
import xyz.headsdown.config.EndpointVerdict
import xyz.headsdown.core.chain.Ore
import xyz.headsdown.core.chain.accounts.OreAccounts
import xyz.headsdown.core.chain.clockin.ClockInService
import xyz.headsdown.core.chain.http.JsonHttp
import xyz.headsdown.core.chain.indexer.IndexerHaulClient
import xyz.headsdown.core.chain.registrar.RegistrarClient
import xyz.headsdown.core.chain.registrar.RigAttestationFlow
import xyz.headsdown.core.chain.rpc.OkHttpJsonRpcTransport
import xyz.headsdown.core.chain.rpc.RpcProtocolException
import xyz.headsdown.core.chain.rpc.SolanaJsonRpc
import xyz.headsdown.core.keys.PrefsCounterStore
import xyz.headsdown.core.keys.RigCounter
import xyz.headsdown.core.keys.RigKeyManager
import xyz.headsdown.core.wallet.AuthTokenVault
import xyz.headsdown.core.wallet.ConfirmationPoller
import xyz.headsdown.core.wallet.HeadsDownIdentity
import xyz.headsdown.core.wallet.HeadsDownWallet
import xyz.headsdown.core.wallet.KeystoreAesGcmCipher
import xyz.headsdown.core.wallet.SharedPreferencesSecretStore
import xyz.headsdown.core.wallet.TransactionSubmitter
import xyz.headsdown.feature.oemkeepalive.KeepAlive
import xyz.headsdown.feature.reveal.RevealScheduler
import xyz.headsdown.feature.reveal.haul.HaulRepository
import xyz.headsdown.feature.shift.BoardRoundSource
import xyz.headsdown.feature.shift.CrankHeartbeatSink
import xyz.headsdown.feature.shift.CrankLinkMonitor
import xyz.headsdown.feature.shift.HeartbeatSink
import xyz.headsdown.feature.shift.OreRoundSource
import xyz.headsdown.feature.shift.RigBindingProvider
import xyz.headsdown.feature.shift.RigSignerProvider
import xyz.headsdown.feature.shift.ShiftJournal
import xyz.headsdown.feature.shift.UplinkFactory
import xyz.headsdown.haul.IndexerHaulRepository
import xyz.headsdown.rig.ChainClockIn
import xyz.headsdown.rig.ClockInPolicy
import xyz.headsdown.rig.CounterResync
import xyz.headsdown.rig.FileHeartbeatLog
import xyz.headsdown.rig.RigBindingStore
import xyz.headsdown.rig.RigKeyRepository
import xyz.headsdown.rig.RigOnboarding
import xyz.headsdown.rig.VoucherStore
import xyz.headsdown.surface.haptics.Haptics
import xyz.headsdown.surface.tile.ClockInTransactions
import xyz.headsdown.surface.widget.GlanceRigWidgetUpdates
import xyz.headsdown.surface.widget.RigWidgetUpdates
import javax.inject.Singleton

/**
 * The production graph. Endpoints come from BuildConfig (see app/build.gradle.kts: HTTPS/WSS
 * only, no query strings or credentials, so no provider key can be baked into the APK; the
 * `localdev` build type alone may use loopback HTTP/WS for a devstack behind `adb reverse`).
 * Every endpoint is re-checked against [EndpointPolicy] before any transport exists.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides @Singleton
    fun rigKeyManager(@ApplicationContext context: Context) = RigKeyManager(context)

    /** One OkHttp stack (connection pool, timeouts, no redirects, no logging interceptors). */
    @Provides @Singleton
    fun okHttp(): OkHttpClient = OkHttpJsonRpcTransport.defaultClient()

    /** HTTPS everywhere, loopback HTTP only in the `localdev` build type. */
    @Provides @Singleton
    fun solanaRpc(client: OkHttpClient): SolanaJsonRpc {
        val url = BuildConfig.SOLANA_RPC_URL
        EndpointPolicy.require(EndpointKind.RPC, url, BuildConfig.LOOPBACK_CLEARTEXT_ALLOWED)
        return SolanaJsonRpc(BuildTransports.transports.rpc(url, client))
    }

    @Provides @Singleton
    fun wallet(@ApplicationContext context: Context, rpc: SolanaJsonRpc): HeadsDownWallet = HeadsDownWallet(
        adapter = MobileWalletAdapter(HeadsDownIdentity.connectionIdentity),
        vault = AuthTokenVault(KeystoreAesGcmCipher(), SharedPreferencesSecretStore(context)),
        // Success is only ever "confirmed with err == null" as seen by this RPC.
        poller = ConfirmationPoller(rpc),
        // MWA knows mainnet, devnet and testnet only; localnet signs as devnet (the wallet never sends there).
        chain = if (BuildConfig.SOLANA_CHAIN == "solana:mainnet") Solana.Mainnet else Solana.Devnet,
        // localdev: the wallet only signs, the app submits to the laptop's validator.
        submitter = if (BuildConfig.SUBMIT_THROUGH_APP_RPC) TransactionSubmitter { signed -> rpc.sendTransaction(signed) } else null,
    )

    @Provides @Singleton
    fun clockInService(rpc: SolanaJsonRpc): ClockInService = ClockInService(rpc)

    /** The clock-in this APK arms (`-Pheadsdown.policy.*` build properties). */
    @Provides @Singleton
    fun clockInPolicy(): ClockInPolicy = ClockInPolicy.fromBuildConfig()

    /** ORE `Board.round_id`, read through the owner/size/address-checked decoder. */
    @Provides @Singleton
    fun oreRounds(rpc: SolanaJsonRpc): OreRoundSource = BoardRoundSource(
        readRoundId = {
            val account = rpc.getAccountInfo(Ore.BOARD) ?: throw RpcProtocolException("no ORE Board on this cluster")
            OreAccounts.board(Ore.BOARD, account).roundId
        },
        clock = { SystemClock.elapsedRealtime() },
    )

    /** Re-reads `Rig.hb_counter` when the crank says a counter was already used. */
    @Provides @Singleton
    fun counterResync(rpc: SolanaJsonRpc, binding: RigBindingStore, counter: RigCounter): CounterResync =
        CounterResync(rpc, binding, counter, CoroutineScope(SupervisorJob() + Dispatchers.IO), clock = { SystemClock.elapsedRealtime() })

    /**
     * Crank uplink (contract A) with the local log as fallback. An empty CRANK_WS_URL builds a
     * local-only app: heartbeats stay on the device and are reported undelivered (no digs).
     */
    @Provides @Singleton
    fun heartbeatSink(client: OkHttpClient, log: FileHeartbeatLog, monitor: CrankLinkMonitor, resync: CounterResync): HeartbeatSink {
        // Outlives any one shift service, so the sink's graceful close can finish.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val url = BuildConfig.CRANK_WS_URL
        val verdict = EndpointPolicy.require(EndpointKind.CRANK, url, BuildConfig.LOOPBACK_CLEARTEXT_ALLOWED)
        return CrankHeartbeatSink(
            uplinkFactory = if (verdict == EndpointVerdict.Disabled) {
                null
            } else {
                UplinkFactory { onConnected, onText -> BuildTransports.transports.uplink(url, client, scope, onConnected, onText) }
            },
            log = log,
            clock = { SystemClock.elapsedRealtime() },
            scope = scope,
            monitor = monitor,
            onStaleCounter = resync::request,
            debugLog = { DebugLog.d("HeadsDown/crank", it) },
        )
    }

    /** The rig's one write-ahead message counter (HEARTBEAT, BREAK, FREEZE, PLAN). */
    @Provides @Singleton
    fun rigCounter(@ApplicationContext context: Context): RigCounter = RigCounter(PrefsCounterStore(context))

    @Provides @Singleton
    fun shiftJournal(@ApplicationContext context: Context) = ShiftJournal(context)

    @Provides @Singleton
    fun revealScheduler(@ApplicationContext context: Context) = RevealScheduler(context)

    @Provides @Singleton
    fun keepAlive(@ApplicationContext context: Context) = KeepAlive(context)

    /** Update hooks for the home-screen widgets (the app's shift observer calls them). */
    @Provides @Singleton
    fun widgetUpdates(@ApplicationContext context: Context): RigWidgetUpdates =
        GlanceRigWidgetUpdates(context, CoroutineScope(SupervisorJob() + Dispatchers.Default))

    /** One haptics engine (and at most one SoundPool) for the whole app. */
    @Provides @Singleton
    fun haptics(@ApplicationContext context: Context) = Haptics(context)

    /**
     * The rig key with registrar attestation (SIWS, challenge, voucher). An empty REGISTRAR_URL
     * builds an app whose rigs all register as guests.
     */
    @Provides @Singleton
    fun rigOnboarding(client: OkHttpClient, keys: RigKeyRepository, vouchers: VoucherStore): RigOnboarding {
        val registrar = service(EndpointKind.REGISTRAR, BuildConfig.REGISTRAR_URL, client)
        return RigOnboarding(
            attestor = registrar?.let { RigAttestationFlow(RegistrarClient(it), HeadsDownIdentity.SIWS_DOMAIN, BuildConfig.SOLANA_CHAIN) },
            keys = keys,
            vouchers = vouchers,
            debugLog = { DebugLog.d("HeadsDown/registrar", it) },
        )
    }

    /**
     * The morning haul from the indexer (contract B). An empty INDEXER_URL builds an app with no
     * haul: the reveal says so, and offers the labelled sample only on request.
     */
    @Provides @Singleton
    fun haulRepository(
        client: OkHttpClient,
        binding: RigBindingStore,
        journal: ShiftJournal,
        widgets: RigWidgetUpdates,
    ): HaulRepository = IndexerHaulRepository(
        client = service(EndpointKind.INDEXER, BuildConfig.INDEXER_URL, client)?.let(::IndexerHaulClient),
        binding = binding,
        firstPickup = journal::firstPickupFor,
        widgets = widgets,
    )

    /** An optional JSON service: null when its URL is empty, a startup failure when refused. */
    private fun service(kind: EndpointKind, url: String, client: OkHttpClient): JsonHttp? {
        val verdict = EndpointPolicy.require(kind, url, BuildConfig.LOOPBACK_CLEARTEXT_ALLOWED)
        return if (verdict == EndpointVerdict.Disabled) null else BuildTransports.transports.http(url, client)
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class BindingsModule {
    @Binds abstract fun rigSigner(impl: RigKeyRepository): RigSignerProvider
    @Binds abstract fun rigBinding(impl: RigBindingStore): RigBindingProvider
    @Binds abstract fun clockIn(impl: ChainClockIn): ClockInTransactions
}
