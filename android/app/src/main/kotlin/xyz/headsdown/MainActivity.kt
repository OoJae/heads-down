package xyz.headsdown

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import dagger.hilt.android.AndroidEntryPoint
import xyz.headsdown.clockout.ClockOutActivity
import xyz.headsdown.rig.ClockInPolicy
import xyz.headsdown.core.wallet.HeadsDownWallet
import xyz.headsdown.core.wallet.WalletResult
import xyz.headsdown.devtools.RigDebug
import xyz.headsdown.feature.reveal.RevealActivity
import xyz.headsdown.feature.shift.devlog.SensorLab
import xyz.headsdown.surface.tile.TrampolineActivity
import xyz.headsdown.ui.HomeScreen
import xyz.headsdown.ui.HomeViewModel
import xyz.headsdown.ui.NightShiftIntro
import xyz.headsdown.ui.OnboardingScreen
import xyz.headsdown.ui.launch
import xyz.headsdown.ui.theme.HeadsDownTheme
import xyz.headsdown.withdraw.WithdrawActivity
import javax.inject.Inject

/** The only exported Activity (launcher). Clock-in goes through the trampoline. */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val vm: HomeViewModel by viewModels()

    @Inject lateinit var wallet: HeadsDownWallet

    /** The wallet's Sign In With Solana when the rig key is created (registered before STARTED). */
    private lateinit var sender: ActivityResultSender

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        sender = ActivityResultSender(this)
        setContent {
            HeadsDownTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    HeadsDownRoot(
                        vm = vm,
                        onClockIn = { startActivity(Intent(this, TrampolineActivity::class.java)) },
                        // The reveal shows the latest real haul, or says there is none yet.
                        onPreviewReveal = { launch(Intent(this, RevealActivity::class.java)) },
                        onClockOut = { launch(Intent(this, ClockOutActivity::class.java)) },
                        onWithdraw = { launch(Intent(this, WithdrawActivity::class.java)) },
                        // Debug and localdev builds only: release has no sensor lab at all.
                        onOpenSensorLab = SensorLab.intent(this)?.let { intent -> { launch(intent) } },
                        // Debug and localdev builds only: release has no rig debug screen at all.
                        onOpenRigDebug = RigDebug.intent(this)?.let { intent -> { launch(intent) } },
                        // The registrar's SIWS request goes to the wallet; a decline leaves a guest key.
                        onCreateRigKey = {
                            vm.createRigKey { request -> (wallet.signIn(sender, request) as? WalletResult.Success)?.value }
                        },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.refresh() // permissions and OEM settings are changed outside the app
    }
}

@Composable
private fun HeadsDownRoot(
    vm: HomeViewModel,
    onClockIn: () -> Unit,
    onPreviewReveal: () -> Unit,
    onClockOut: () -> Unit,
    onWithdraw: () -> Unit,
    onOpenSensorLab: (() -> Unit)?,
    onOpenRigDebug: (() -> Unit)?,
    onCreateRigKey: () -> Unit,
) {
    val onboarding by vm.onboarding.collectAsStateWithLifecycle()
    val snapshot by vm.shift.collectAsStateWithLifecycle()
    val health by vm.health.collectAsStateWithLifecycle()
    val crank by vm.crank.collectAsStateWithLifecycle()
    val bond by vm.bond.collectAsStateWithLifecycle()
    val shiftWindow by vm.shiftWindow.collectAsStateWithLifecycle()
    // What a clock-in can move, from the build's policy alone: on screen before the wallet opens.
    val clockInAmounts = remember(bond) { runCatching { ClockInPolicy.fromBuildConfig().disclosure(bond) }.getOrNull() }
    // null = decide from onboarding progress; true/false = the user chose.
    var setupChoice by rememberSaveable { mutableStateOf<Boolean?>(null) }
    // Re-reading the ritual from the home screen.
    var rereadIntro by rememberSaveable { mutableStateOf(false) }
    val showSetup = setupChoice ?: !onboarding.allDone

    BackHandler(enabled = rereadIntro) { rereadIntro = false }
    BackHandler(enabled = !rereadIntro && showSetup && setupChoice == true) { setupChoice = false }

    when {
        rereadIntro -> NightShiftIntro(onContinue = { rereadIntro = false }, continueLabel = "Back")
        showSetup && !onboarding.introSeen -> NightShiftIntro(onContinue = vm::markIntroSeen)
        showSetup -> OnboardingScreen(onboarding, vm, onContinue = { setupChoice = false }, onCreateRigKey = onCreateRigKey)
        else -> HomeScreen(
            snapshot = snapshot,
            onboarding = onboarding,
            health = health,
            onClockIn = onClockIn,
            onEndShift = vm::endShift,
            onFreeze = vm::freeze,
            onOpenSetup = { setupChoice = true },
            onHowItWorks = { rereadIntro = true },
            onPreviewReveal = onPreviewReveal,
            onClockOut = onClockOut,
            onWithdraw = onWithdraw,
            onAddWidget = if (vm.widgetPinSupported) vm::requestRigWidget else null,
            onOpenSensorLab = onOpenSensorLab,
            onOpenRigDebug = onOpenRigDebug,
            crank = crank,
            clockInAmounts = clockInAmounts,
            shiftWindow = shiftWindow,
            bondSkr = bond,
            onBondChange = vm::setBond,
        )
    }
}
