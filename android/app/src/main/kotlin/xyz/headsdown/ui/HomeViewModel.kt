package xyz.headsdown.ui

import android.appwidget.AppWidgetManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.headsdown.core.chain.Ore
import xyz.headsdown.core.chain.Pubkey
import xyz.headsdown.core.chain.accounts.OreAccounts
import xyz.headsdown.core.chain.accounts.OreGate
import xyz.headsdown.core.chain.registrar.AttestationOutcome
import xyz.headsdown.core.chain.rpc.AccountInfo
import xyz.headsdown.core.chain.rpc.SolanaJsonRpc
import xyz.headsdown.core.wallet.SignInProof
import xyz.headsdown.core.wallet.SiwsRequest
import xyz.headsdown.feature.oemkeepalive.KeepAlive
import xyz.headsdown.feature.oemkeepalive.KeepAliveStep
import xyz.headsdown.feature.oemkeepalive.LastShift
import xyz.headsdown.feature.oemkeepalive.OemProfile
import xyz.headsdown.feature.oemkeepalive.ShiftHealth
import xyz.headsdown.feature.oemkeepalive.ShiftHealthCheck
import xyz.headsdown.feature.reveal.RevealScheduler
import xyz.headsdown.feature.shift.CrankLinkMonitor
import xyz.headsdown.feature.shift.CrankLinkStatus
import xyz.headsdown.feature.shift.ShiftController
import xyz.headsdown.feature.shift.ShiftJournal
import xyz.headsdown.feature.shift.ShiftSnapshot
import xyz.headsdown.feature.shift.ShiftStatusRepository
import xyz.headsdown.feature.shift.isRunning
import xyz.headsdown.feature.shift.justArmed
import xyz.headsdown.rig.ClockInPolicy
import xyz.headsdown.rig.FocusBondSetting
import xyz.headsdown.rig.RigBindingStore
import xyz.headsdown.rig.RigKeyRepository
import xyz.headsdown.rig.RigKeyStatus
import xyz.headsdown.rig.RigOnboarding
import xyz.headsdown.rig.ShiftWindow
import xyz.headsdown.rig.VoucherStore
import xyz.headsdown.surface.tile.ClockInTransactions
import xyz.headsdown.surface.tile.TileAddOutcome
import xyz.headsdown.surface.tile.TilePrompt
import xyz.headsdown.surface.widget.RigWidgetReceiver
import xyz.headsdown.surface.widget.RigWidgetState
import xyz.headsdown.surface.widget.WidgetStateStore
import javax.inject.Inject

data class OnboardingState(
    /** The user read "your phone's night shift" (the ritual intro) at least once. */
    val introSeen: Boolean = false,
    val notificationsGranted: Boolean = false,
    val exactAlarmsAllowed: Boolean = false,
    val oem: OemProfile? = null,
    val autostartConfirmed: Boolean = false,
    val batteryUnrestricted: Boolean = false,
    val tileAdded: Boolean = false,
    val tilePromptSupported: Boolean = true,
    val rigKey: RigKeyStatus = RigKeyStatus.Missing,
    val creatingKey: Boolean = false,
    /** How the last key creation went with the registrar (null: not this session). */
    val attestation: AttestationOutcome? = null,
    /** A clock-in confirmed on-chain and bound this phone to its Rig. */
    val rigRegistered: Boolean = false,
) {
    val keepAliveDone: Boolean
        get() = batteryUnrestricted && (oem?.needsAutostartStep != true || autostartConfirmed)

    val rigKeyReady: Boolean get() = rigKey is RigKeyStatus.Ready

    val allDone: Boolean
        get() = introSeen && notificationsGranted && exactAlarmsAllowed && keepAliveDone && tileAdded && rigKeyReady
}

/**
 * Whether a rig would dig at ORE's cost figure right now, in [HomeCopy.gate]'s words, from one
 * read of ORE's Board and Treasury through their checked decoders. It fails closed: an account
 * that is missing, foreign or misshapen, or an RPC that does not answer, is no line at all.
 */
internal suspend fun gateLine(policy: ClockInPolicy, read: suspend (List<Pubkey>) -> List<AccountInfo?>): String? = try {
    val accounts = read(listOf(Ore.BOARD, Ore.TREASURY))
    val board = OreAccounts.board(Ore.BOARD, checkNotNull(accounts[0]))
    val treasury = OreAccounts.treasury(Ore.TREASURY, checkNotNull(accounts[1]))
    HomeCopy.gate(OreGate.emaEv(board, treasury), policy)
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    null
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    shiftStatus: ShiftStatusRepository,
    private val shifts: ShiftController,
    private val rigKeys: RigKeyRepository,
    private val keepAlive: KeepAlive,
    private val reveal: RevealScheduler,
    private val journal: ShiftJournal,
    private val onboardingFlow: RigOnboarding,
    private val vouchers: VoucherStore,
    private val binding: RigBindingStore,
    crankLink: CrankLinkMonitor,
    private val focusBond: FocusBondSetting,
    private val policy: ClockInPolicy,
    private val clockIn: ClockInTransactions,
    private val rpc: SolanaJsonRpc,
) : ViewModel() {

    private val _gate = MutableStateFlow<String?>(null)

    /**
     * Whether a rig would dig at ORE's cost figure as last read ([HomeCopy.gate]), re-read on every
     * [refresh]. Null until it is read and whenever it cannot be: the screen then has no such line.
     */
    val gate: StateFlow<String?> = _gate.asStateFlow()

    private val _shiftWindow = MutableStateFlow<String?>(null)

    /** Until when a shift started now would run (ShiftWindow.describe), re-read on every resume. */
    val shiftWindow: StateFlow<String?> = _shiftWindow.asStateFlow()

    /** The Focus Bond locked at the next clock-in, SKR base units (0 = none). */
    val bond: StateFlow<ULong> = focusBond.amount

    fun setBond(skr: ULong) = focusBond.set(skr)

    private val prefs = context.getSharedPreferences("hd_onboarding", Context.MODE_PRIVATE)

    val shift: StateFlow<ShiftSnapshot> = shiftStatus.snapshot

    /** Crank intake acks (contract A): refusals are shown on the rig card. */
    val crank: StateFlow<CrankLinkStatus> = crankLink.status

    // introSeen is read synchronously so a returning user never sees the intro flash by.
    private val _onboarding = MutableStateFlow(OnboardingState(introSeen = prefs.getBoolean(K_INTRO, false)))
    val onboarding: StateFlow<OnboardingState> = _onboarding.asStateFlow()

    private val _health = MutableStateFlow<ShiftHealth>(ShiftHealth.NoRecentShift)
    val health: StateFlow<ShiftHealth> = _health.asStateFlow()

    private val _loaded = MutableStateFlow(false)

    /**
     * False until the first [refresh] has read every permission and setting. Until then a screen
     * shows still skeleton lines where those facts go, not a guess that nothing is set up.
     */
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    /**
     * What the Rig widget last stored: the last real haul and the streak read from the on-chain
     * Rig. The home screen prints the same two facts, in the widget's own words (RigWidgetCopy).
     */
    val widget: StateFlow<RigWidgetState> = WidgetStateStore.get(context).state

    val notificationAskedBefore: Boolean get() = prefs.getBoolean(K_NOTIF_ASKED, false)

    init {
        refresh()
    }

    /** Re-reads every permission / setting; call on resume (users change them in Settings). */
    /**
     * Arms the shift of a clock-in the wallet sent and this app never heard back about, once the
     * chain shows it. It returns when there is nothing left to wait for. Call it only while the
     * app is in front: a shift can only be started from the foreground.
     */
    suspend fun pickUpClockIn() {
        if (shift.value.state.isRunning) return
        val spec = clockIn.recover(PICK_UP_PATIENCE_MILLIS) ?: return
        if (shift.value.state.isRunning) return
        // Left the foreground meanwhile: nothing starts, the record stays, the next resume finds it.
        if (!shifts.arm(spec)) return
        clockIn.armed()
        refresh() // the rig is bound now
    }

    fun refresh() {
        viewModelScope.launch {
            val key = withContext(Dispatchers.Default) { rigKeys.status(vouchers.forKey(rigKeys.compressedPublicKey())?.level) }
            val oem = withContext(Dispatchers.Default) { keepAlive.profile }
            _onboarding.value = _onboarding.value.copy(
                introSeen = prefs.getBoolean(K_INTRO, false),
                notificationsGranted = NotificationManagerCompat.from(context).areNotificationsEnabled(),
                exactAlarmsAllowed = reveal.canScheduleExactAlarms(),
                oem = oem,
                autostartConfirmed = prefs.getBoolean(K_AUTOSTART, false),
                batteryUnrestricted = keepAlive.isIgnoringBatteryOptimizations(),
                tileAdded = prefs.getBoolean(K_TILE, false),
                tilePromptSupported = TilePrompt.supported,
                rigKey = key,
                rigRegistered = binding.current().isRegistered,
            )
            _health.value = withContext(Dispatchers.Default) { evaluateHealth() }
            _shiftWindow.value = runCatching {
                ShiftWindow.describe(policy, System.currentTimeMillis(), reveal.nextSystemAlarmWallMillis())
            }.getOrNull()
            _loaded.value = true
            // Last, and off the main thread: an RPC that is slow or away holds nothing else back.
            _gate.value = withContext(Dispatchers.IO) { gateLine(policy) { rpc.getMultipleAccounts(it) } }
        }
    }

    fun onNotificationPermissionAsked() = prefs.edit { putBoolean(K_NOTIF_ASKED, true) }

    fun markIntroSeen() {
        prefs.edit { putBoolean(K_INTRO, true) }
        _onboarding.value = _onboarding.value.copy(introSeen = true)
    }

    /** True where the launcher supports pinning a widget from inside the app. */
    val widgetPinSupported: Boolean
        get() = context.getSystemService(AppWidgetManager::class.java)?.isRequestPinAppWidgetSupported == true

    /** Asks the launcher to place the Rig widget (the launcher shows its own confirmation). */
    fun requestRigWidget() {
        viewModelScope.launch {
            runCatching { GlanceAppWidgetManager(context).requestPinGlanceAppWidget(RigWidgetReceiver::class.java) }
        }
    }

    fun openKeepAlive(step: KeepAliveStep) {
        keepAlive.open(step, appLabel = "Heads Down")
        if (step == KeepAliveStep.AUTOSTART) {
            // Autostart cannot be read back on HyperOS; the user confirms it in the checklist.
            prefs.edit { putBoolean(K_AUTOSTART_OPENED, true) }
        }
    }

    fun confirmAutostart() {
        prefs.edit { putBoolean(K_AUTOSTART, true) }
        _onboarding.value = _onboarding.value.copy(autostartConfirmed = true)
    }

    fun onTileResult(outcome: TileAddOutcome) {
        val added = outcome == TileAddOutcome.ADDED || outcome == TileAddOutcome.ALREADY_ADDED
        // Android 12 has no prompt: the user adds the tile by hand and confirms here.
        if (added || outcome == TileAddOutcome.UNSUPPORTED) {
            prefs.edit { putBoolean(K_TILE, true) }
            _onboarding.value = _onboarding.value.copy(tileAdded = true)
        }
    }

    /**
     * Creates the rig key, attested by the registrar when possible. [signIn] is the wallet's Sign
     * In With Solana (it needs the Activity's result sender); it is asked only after the registrar
     * answered, and a decline still leaves a working guest key.
     */
    fun createRigKey(signIn: suspend (SiwsRequest) -> SignInProof?) {
        if (_onboarding.value.creatingKey) return
        _onboarding.value = _onboarding.value.copy(creatingKey = true)
        viewModelScope.launch {
            val setup = onboardingFlow.createKey(signIn)
            _onboarding.value = _onboarding.value.copy(rigKey = setup.status, creatingKey = false, attestation = setup.attestation)
        }
    }

    fun exactAlarmSettingsIntent() = reveal.exactAlarmSettingsIntent()

    fun endShift() {
        // A shift armed under the thumb (picked up from the chain while "Clock in" stood where
        // "End shift" now is) is not ended by the tap that was on its way to "Clock in".
        if (shift.value.state.justArmed(SystemClock.elapsedRealtime())) return
        shifts.end()
    }

    fun freeze() = shifts.freeze()

    private fun evaluateHealth(): ShiftHealth {
        val record = journal.last() ?: return ShiftHealth.NoRecentShift
        val running = shift.value.state.isRunning
        val exits = runCatching { keepAlive.recentExits() }.getOrDefault(emptyList())
        return ShiftHealthCheck.evaluate(
            last = LastShift(
                armedAtWallMillis = record.armedAtWallMillis,
                lastHeartbeatWallMillis = record.lastHeartbeatWallMillis,
                endedAtWallMillis = record.endedAtWallMillis,
                endReason = record.endReason,
                darkRounds = record.darkRounds,
            ),
            exits = exits,
            serviceRunning = running,
            nowWallMillis = System.currentTimeMillis(),
        )
    }

    companion object {
        val sdkInt: Int get() = Build.VERSION.SDK_INT
        private const val K_NOTIF_ASKED = "notif_asked"
        private const val K_AUTOSTART = "autostart_confirmed"
        private const val K_AUTOSTART_OPENED = "autostart_opened"
        private const val K_TILE = "tile_added"
        private const val K_INTRO = "intro_seen"

        /** How long the home screen goes on looking for a clock-in that could still land: past a blockhash's life. */
        private const val PICK_UP_PATIENCE_MILLIS = 150_000L
    }
}
