package xyz.headsdown.clockout

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import dagger.hilt.android.AndroidEntryPoint
import xyz.headsdown.core.design.HdMaterial
import xyz.headsdown.core.wallet.HeadsDownWallet
import xyz.headsdown.ui.theme.HdColors
import xyz.headsdown.ui.theme.HeadsDownTheme
import xyz.headsdown.ui.theme.PixelLabel
import javax.inject.Inject

/**
 * Clock out: seal the finished shift, take back a Focus Bond, and decide about the ORE. **Not
 * exported**, reads nothing from its Intent, and signs nothing by itself: the screen says what
 * the transaction will do, the user taps, the wallet approves.
 */
@AndroidEntryPoint
class ClockOutActivity : ComponentActivity() {

    private val vm: ClockOutViewModel by viewModels()

    @Inject lateinit var wallet: HeadsDownWallet

    /** Registered before the Activity is STARTED, as MWA requires. */
    private lateinit var sender: ActivityResultSender

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        sender = ActivityResultSender(this)
        val model = vm.model
        setContent {
            HeadsDownTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val state by model.state.collectAsStateWithLifecycle()
                    ClockOutScreen(
                        state = state,
                        onClaimAll = model::setClaimAll,
                        onEndEarly = model::setEndEarly,
                        onConfirm = { model.confirm { prepare -> wallet.signAndSendInSession(sender, prepare) } },
                        onRetry = model::load,
                        onClose = ::finish,
                    )
                }
            }
        }
    }
}

object ClockOutTags {
    const val SHIFT = "clock-out-shift"
    const val RENT = "clock-out-rent"
    const val BOND = "clock-out-bond"
    const val SOL = "clock-out-sol"
    const val ORE = "clock-out-ore"
    const val CLAIM = "clock-out-claim"
    const val KEEP_CHOICE = "clock-out-keep"
    const val CLAIM_CHOICE = "clock-out-claim-all"
    const val END_EARLY = "clock-out-end-early"
    const val END_EARLY_WARNING = "clock-out-end-early-warning"
    const val CONFIRM = "clock-out-confirm"
    const val NOTHING = "clock-out-nothing"
    const val PROBLEM = "clock-out-problem"
    const val MESSAGE = "clock-out-message"
    const val RETRY = "clock-out-retry"
    const val CLOSE = "clock-out-close"
}

@Composable
fun ClockOutScreen(
    state: ClockOutState,
    onClaimAll: (Boolean) -> Unit,
    onEndEarly: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onRetry: () -> Unit,
    onClose: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("CLOCK OUT", style = PixelLabel, color = HdColors.AshMuted)
        Text("Clock out", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
        when (state) {
            ClockOutState.Loading -> Text("Reading your rig from the chain…", color = HdColors.AshMuted, modifier = Modifier.testTag(ClockOutTags.MESSAGE))
            ClockOutState.NoRig -> Text(
                "No rig on this phone yet. Clock in once, and your shifts and your ORE show up here.",
                color = HdColors.Ash,
                modifier = Modifier.testTag(ClockOutTags.MESSAGE),
            )
            is ClockOutState.Unavailable -> {
                Text(state.message, color = HdColors.Cooling, modifier = Modifier.testTag(ClockOutTags.MESSAGE))
                OutlinedButton(onClick = onRetry, modifier = Modifier.testTag(ClockOutTags.RETRY), border = HdMaterial.controlBorder()) { Text("Try again") }
            }
            is ClockOutState.Done -> Text(state.message, color = HdColors.OreGold, modifier = Modifier.testTag(ClockOutTags.MESSAGE))
            is ClockOutState.Ready -> Ready(state, onClaimAll, onEndEarly, onConfirm)
        }
        TextButton(onClick = onClose, modifier = Modifier.testTag(ClockOutTags.CLOSE)) { Text(if (state is ClockOutState.Done) "Done" else "Close") }
    }
}

@Composable
private fun Ready(state: ClockOutState.Ready, onClaimAll: (Boolean) -> Unit, onEndEarly: (Boolean) -> Unit, onConfirm: () -> Unit) {
    val lines = ClockOutCopy.lines(state.shown)
    Text(lines.shift, color = HdColors.Ash, modifier = Modifier.testTag(ClockOutTags.SHIFT))
    if (state.facts.canEndEarly) {
        Choice(
            label = "End the shift now",
            selected = state.endEarly,
            tag = ClockOutTags.END_EARLY,
            enabled = !state.working,
            onClick = { onEndEarly(!state.endEarly) },
        )
        if (state.endEarly) {
            ClockOutCopy.endEarlyWarning(state.facts)?.let {
                Text(it, color = HdColors.Cooling, modifier = Modifier.testTag(ClockOutTags.END_EARLY_WARNING))
            }
        }
    }
    lines.rent?.let {
        Text(it, color = HdColors.AshMuted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag(ClockOutTags.RENT))
    }
    lines.bond?.let { Text(it, color = HdColors.Ash, modifier = Modifier.testTag(ClockOutTags.BOND)) }
    lines.sol?.let { Text(it, color = HdColors.Ash, modifier = Modifier.testTag(ClockOutTags.SOL)) }
    Text(lines.ore, color = HdColors.OreGold, modifier = Modifier.testTag(ClockOutTags.ORE))
    if (lines.claim != null) {
        Text(ClockOutCopy.ORE_KEPT, color = HdColors.AshMuted, style = MaterialTheme.typography.bodyMedium)
        Choice("Keep it in my Miner", selected = !state.claimAll, tag = ClockOutTags.KEEP_CHOICE, enabled = !state.working) { onClaimAll(false) }
        Choice("Claim all to my wallet", selected = state.claimAll, tag = ClockOutTags.CLAIM_CHOICE, enabled = !state.working) { onClaimAll(true) }
        if (state.claimAll) Text(lines.claim, color = HdColors.Ash, modifier = Modifier.testTag(ClockOutTags.CLAIM))
    }
    state.problem?.let { Text(it, color = HdColors.Cooling, modifier = Modifier.testTag(ClockOutTags.PROBLEM)) }
    Button(
        onClick = onConfirm,
        enabled = state.canSign && !state.working,
        modifier = Modifier.fillMaxWidth().testTag(ClockOutTags.CONFIRM),
        colors = ButtonDefaults.buttonColors(containerColor = HdColors.Ember, contentColor = HdColors.Charcoal),
    ) {
        Text(
            when {
                state.working -> "Waiting for your wallet…"
                state.endEarly -> "End the shift and clock out"
                else -> "Clock out"
            },
        )
    }
    if (!state.canSign) {
        Text(ClockOutCopy.NOTHING_TO_SIGN, color = HdColors.AshMuted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag(ClockOutTags.NOTHING))
    }
}

/** One of a few exclusive options, or a single on/off one: filled when chosen, outlined when not. */
@Composable
private fun Choice(label: String, selected: Boolean, tag: String, enabled: Boolean, onClick: () -> Unit) {
    val modifier = Modifier.fillMaxWidth().testTag(tag).semantics { this.selected = selected }
    if (selected) {
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier,
            colors = ButtonDefaults.buttonColors(containerColor = HdColors.OreGold, contentColor = HdColors.Charcoal),
        ) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier, border = HdMaterial.controlBorder(enabled)) { Text(label) }
    }
}
