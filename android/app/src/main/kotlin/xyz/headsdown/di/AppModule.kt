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
import xyz.headsdown.BuildConfig
import xyz.headsdown.core.keys.RigKeyManager
import xyz.headsdown.core.keys.SignedHeartbeat
import xyz.headsdown.core.wallet.AuthTokenVault
import xyz.headsdown.core.wallet.ConfirmationPoller
import xyz.headsdown.core.wallet.HeadsDownIdentity
import xyz.headsdown.core.wallet.HeadsDownWallet
import xyz.headsdown.core.wallet.KeystoreAesGcmCipher
import xyz.headsdown.core.wallet.SharedPreferencesSecretStore
import xyz.headsdown.core.wallet.UnconfiguredSolanaRpc
import xyz.headsdown.feature.oemkeepalive.KeepAlive
import xyz.headsdown.feature.reveal.RevealScheduler
import xyz.headsdown.feature.shift.HeartbeatCounter
import xyz.headsdown.feature.shift.HeartbeatSink
import xyz.headsdown.feature.shift.OreRoundSource
import xyz.headsdown.feature.shift.PrefsHeartbeatCounter
import xyz.headsdown.feature.shift.RigBinding
import xyz.headsdown.feature.shift.RigBindingProvider
import xyz.headsdown.feature.shift.RigSignerProvider
import xyz.headsdown.feature.shift.ShiftJournal
import xyz.headsdown.feature.shift.StubOreRoundSource
import xyz.headsdown.rig.RigKeyRepository
import xyz.headsdown.surface.tile.ClockInTransactions
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides @Singleton
    fun rigKeyManager(@ApplicationContext context: Context) = RigKeyManager(context)

    @Provides @Singleton
    fun wallet(@ApplicationContext context: Context): HeadsDownWallet = HeadsDownWallet(
        adapter = MobileWalletAdapter(HeadsDownIdentity.connectionIdentity),
        vault = AuthTokenVault(KeystoreAesGcmCipher(), SharedPreferencesSecretStore(context)),
        // STUB: no RPC endpoint ships in the APK; the poller fails closed (never "success").
        poller = ConfirmationPoller(UnconfiguredSolanaRpc),
        chain = if (BuildConfig.SOLANA_CHAIN == "solana:mainnet") Solana.Mainnet else Solana.Devnet,
    )

    /** STUB: the refuel + arm_shift transaction builder arrives with hd-client. */
    @Provides @Singleton
    fun clockInTransactions(): ClockInTransactions = ClockInTransactions { null }

    /** STUB: synthetic ~78 s rounds until the Board.round_id feed is wired. */
    @Provides @Singleton
    fun oreRounds(): OreRoundSource = StubOreRoundSource(clock = { SystemClock.elapsedRealtime() })

    /** Unregistered until register_rig confirms: heartbeats bind to an all-zero rig. */
    @Provides @Singleton
    fun rigBinding(): RigBindingProvider = RigBindingProvider { RigBinding.UNREGISTERED }

    @Provides @Singleton
    fun heartbeatCounter(@ApplicationContext context: Context): HeartbeatCounter = PrefsHeartbeatCounter(context)

    @Provides @Singleton
    fun shiftJournal(@ApplicationContext context: Context) = ShiftJournal(context)

    @Provides @Singleton
    fun revealScheduler(@ApplicationContext context: Context) = RevealScheduler(context)

    @Provides @Singleton
    fun keepAlive(@ApplicationContext context: Context) = KeepAlive(context)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class BindingsModule {
    @Binds abstract fun rigSigner(impl: RigKeyRepository): RigSignerProvider
    @Binds abstract fun heartbeatSink(impl: LocalHeartbeatSink): HeartbeatSink
}

/**
 * STUB heartbeat intake: keeps heartbeats on the device (count + last one). The production
 * sink is the SIWS-authenticated WebSocket intake mirrored to Nostr, so any cranker can dig.
 */
@Singleton
class LocalHeartbeatSink @Inject constructor() : HeartbeatSink {
    private val count = AtomicInteger()

    @Volatile
    var last: SignedHeartbeat? = null
        private set

    val delivered: Int get() = count.get()

    override suspend fun deliver(heartbeat: SignedHeartbeat) {
        last = heartbeat
        count.incrementAndGet()
    }
}
