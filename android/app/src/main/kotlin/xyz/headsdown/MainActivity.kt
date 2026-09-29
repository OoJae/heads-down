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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import xyz.headsdown.feature.reveal.RevealActivity
import xyz.headsdown.feature.shift.devlog.SensorLab
import xyz.headsdown.surface.tile.TrampolineActivity
import xyz.headsdown.ui.HomeScreen
import xyz.headsdown.ui.HomeViewModel
import xyz.headsdown.ui.NightShiftIntro
import xyz.headsdown.ui.OnboardingScreen
import xyz.headsdown.ui.launch
import xyz.headsdown.ui.theme.HeadsDownTheme

/** The only exported Activity (launcher). Everything wallet-related goes through the trampoline. */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val vm: HomeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            HeadsDownTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    HeadsDownRoot(
                        vm = vm,
                        onClockIn = { startActivity(Intent(this, TrampolineActivity::class.java)) },
                        // The reveal shows the (labelled) sample night until the indexer exists.
                        onPreviewReveal = { launch(Intent(this, RevealActivity::class.java)) },
                        // Debug and localdev builds only: release has no sensor lab at all.
                        onOpenSensorLab = SensorLab.intent(this)?.let { intent -> { launch(intent) } },
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
    onOpenSensorLab: (() -> Unit)?,
) {
    val onboarding by vm.onboarding.collectAsStateWithLifecycle()
    val snapshot by vm.shift.collectAsStateWithLifecycle()
    val health by vm.health.collectAsStateWithLifecycle()
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
        showSetup -> OnboardingScreen(onboarding, vm, onContinue = { setupChoice = false })
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
            onAddWidget = if (vm.widgetPinSupported) vm::requestRigWidget else null,
            onOpenSensorLab = onOpenSensorLab,
        )
    }
}
