package xyz.headsdown

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import xyz.headsdown.clockout.ClockOutActivity
import xyz.headsdown.core.design.Hd
import xyz.headsdown.core.wallet.HeadsDownWallet
import xyz.headsdown.core.wallet.WalletResult
import xyz.headsdown.devtools.RigDebug
import xyz.headsdown.feature.reveal.RevealActivity
import xyz.headsdown.feature.shift.devlog.SensorLab
import xyz.headsdown.rig.ClockInPolicy
import xyz.headsdown.surface.tile.TrampolineActivity
import xyz.headsdown.surface.widget.RigWidgetCopy
import xyz.headsdown.ui.HomeCopy
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
        // The app is dark until the light palette is switched on: both bars are told so, and the
        // status-bar icons stay light on a phone that is itself set to light.
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        sender = ActivityResultSender(this)
        setContent {
            HeadsDownTheme {
                AppLocals {
                    HeadsDownRoot(
                        vm = vm,
                        onClockIn = { startActivity(Intent(this, TrampolineActivity::class.java)) },
                        // The reveal shows the latest real haul, or says there is none yet.
                        onPreviewReveal = { launch(Intent(this, RevealActivity::class.java)) },
                        onClockOut = { launch(Intent(this, ClockOutActivity::class.java)) },
                        onWithdraw = { launch(Intent(this, WithdrawActivity::class.java)) },
                        // Debug and localdev builds only: release has no sensor lab at all. A build
                        // made with -Pheadsdown.devEntries=false hides the entry there too.
                        onOpenSensorLab = SensorLab.intent(this)?.takeIf { BuildConfig.SHOW_DEV_ENTRIES }?.let { intent -> { launch(intent) } },
                        // Debug and localdev builds only: release has no rig debug screen at all.
                        onOpenRigDebug = RigDebug.intent(this)?.takeIf { BuildConfig.SHOW_DEV_ENTRIES }?.let { intent -> { launch(intent) } },
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

/**
 * THE ONE PLACE where the app provides CompositionLocals to every screen under the theme. Nothing
 * is provided yet: the hero draws the design module's still slab and the haptics are silent.
 * The live hero (`LocalHeroRenderer provides …`, with whatever it needs to be live: its config,
 * its tilt source) and the motor (`LocalHdHaptics provides …`) are wired here, and nowhere else.
 * The screens give the hero the clear box between the brand row and the line under it, the
 * screen's full width; nothing but the page's own scroller clips what is drawn past that box.
 */
@Composable
private fun AppLocals(content: @Composable () -> Unit) {
    CompositionLocalProvider(content = content)
}

/** What fills the screen. */
private enum class RootScreen { Intro, Setup, Home }

/** How long nothing can be tapped after one screen replaced another under the same thumb. */
private const val REST_AFTER_SWITCH_MILLIS = 400L

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
    val loaded by vm.loaded.collectAsStateWithLifecycle()
    val widget by vm.widget.collectAsStateWithLifecycle()
    // What a clock-in can move, from the build's policy alone: on screen before the wallet opens.
    val policy = remember { runCatching { ClockInPolicy.fromBuildConfig() }.getOrNull() }
    val clockInAmounts = remember(policy, bond) { policy?.let { runCatching { it.disclosure(bond) }.getOrNull() } }
    val clockInCap = remember(policy, clockInAmounts) { policy?.takeIf { clockInAmounts != null }?.let(HomeCopy::clockInCap) }
    // The home screen prints the haul and the streak in the widget's words, from the widget's store.
    val lastHaul = widget.haul?.let { RigWidgetCopy.ore(it.oreAtoms) }
    val streak = widget.streakNights?.let { RigWidgetCopy.render(widget).streak }
    // null = decide from onboarding progress; true/false = the user chose.
    var setupChoice by rememberSaveable { mutableStateOf<Boolean?>(null) }
    // Re-reading the ritual, from the home screen or from setup.
    var rereadIntro by rememberSaveable { mutableStateOf(false) }
    // Until the phone's settings are read, only one thing is known: whether the introduction was
    // read. Someone who read it is most likely set up, and sees Home (its own loading state);
    // someone who did not sees the first-launch screen. Neither sees the other flash by.
    val setupDue = if (loaded) !onboarding.allDone else !onboarding.introSeen
    val showSetup = setupChoice ?: setupDue
    val screen = when {
        rereadIntro -> RootScreen.Intro
        showSetup -> RootScreen.Setup
        else -> RootScreen.Home
    }
    // The hero's entrance belongs to the screen the app opened on, and is not played again once
    // another screen has been in its place.
    val openedOn = rememberSaveable { screen }
    var movedOn by rememberSaveable { mutableStateOf(false) }

    BackHandler(enabled = rereadIntro) { rereadIntro = false }
    BackHandler(enabled = !rereadIntro && showSetup && setupChoice == true) { setupChoice = false }

    // One screen takes another's place under the same thumb, and each has its bar in the same
    // spot: for a moment after a switch nothing takes a tap, so the second half of a double tap
    // on "Start using Heads Down" cannot land on "Clock in".
    var resting by remember { mutableStateOf(false) }
    var shown by remember { mutableStateOf(screen) }
    LaunchedEffect(screen) {
        if (screen != shown) {
            shown = screen
            movedOn = true
            resting = true
            delay(REST_AFTER_SWITCH_MILLIS)
            resting = false
        }
    }

    val motion = Hd.motion
    Box(Modifier.fillMaxSize().background(Hd.colors.pit)) {
        AnimatedContent(
            targetState = screen,
            modifier = Modifier.fillMaxSize(),
            // A fade through the pit on the settle spring; with motion reduced, a cut.
            transitionSpec = {
                if (motion.reduced) {
                    ContentTransform(EnterTransition.None, ExitTransition.None, sizeTransform = null)
                } else {
                    ContentTransform(fadeIn(motion.settle()), fadeOut(motion.settle()), sizeTransform = null)
                }
            },
            label = "root",
        ) { target ->
            when (target) {
                RootScreen.Intro -> NightShiftIntro(onContinue = { rereadIntro = false }, continueLabel = "Back")
                RootScreen.Setup -> OnboardingScreen(
                    onboarding,
                    vm,
                    onContinue = { setupChoice = false },
                    onCreateRigKey = onCreateRigKey,
                    onHowItWorks = { rereadIntro = true },
                )
                RootScreen.Home -> HomeScreen(
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
                    lastHaul = lastHaul,
                    streak = streak,
                    clockInCap = clockInCap,
                    loading = !loaded,
                    heroEnter = openedOn == RootScreen.Home && !movedOn,
                )
            }
        }
        if (resting) {
            Box(
                Modifier.fillMaxSize().pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                    }
                },
            )
        }
    }
}
