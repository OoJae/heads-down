package xyz.headsdown.withdraw

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
import xyz.headsdown.core.wallet.HeadsDownWallet
import xyz.headsdown.ui.theme.HdColors
import xyz.headsdown.ui.theme.HeadsDownTheme
import xyz.headsdown.ui.theme.PixelLabel
import javax.inject.Inject

/**
 * The way out: take the SOL back out of the ORE Automation (Revoke), and close the rig. **Not
 * exported**, reads nothing from its Intent, and signs nothing by itself: the screen says what
 * each choice does, the user chooses and taps, the wallet approves.
 */
@AndroidEntryPoint
class WithdrawActivity : ComponentActivity() {

    private val vm: WithdrawViewModel by viewModels()

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
                    WithdrawScreen(
                        state = state,
                        onConnect = { model.connect { wallet.connect(sender) } },
                        onRevoke = model::setRevoke,
                        onCloseRig = model::setCloseRig,
                        onConfirm = { model.confirm { prepare -> wallet.signAndSendInSession(sender, prepare) } },
                        onRetry = model::load,
                        onClose = ::finish,
                    )
                }
            }
        }
    }
}

object WithdrawTags {
    const val AUTOMATION = "withdraw-automation"
    const val REVOKE = "withdraw-revoke"
    const val REVOKE_DETAIL = "withdraw-revoke-detail"
    const val RIG = "withdraw-rig"
    const val CLOSE_RIG = "withdraw-close-rig"
    const val CLOSE_DETAIL = "withdraw-close-detail"
    const val CONFIRM = "withdraw-confirm"
    const val NOTHING = "withdraw-nothing"
    const val PROBLEM = "withdraw-problem"
    const val MESSAGE = "withdraw-message"
    const val CONNECT = "withdraw-connect"
    const val RETRY = "withdraw-retry"
    const val CLOSE = "withdraw-close"
}

@Composable
fun WithdrawScreen(
    state: WithdrawState,
    onConnect: () -> Unit,
    onRevoke: (Boolean) -> Unit,
    onCloseRig: (Boolean) -> Unit,
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
        Text("YOUR SOL, YOUR RIG", style = PixelLabel, color = HdColors.AshMuted)
        Text("Take it back", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
        when (state) {
            WithdrawState.Loading -> Text("Reading the chain…", color = HdColors.AshMuted, modifier = Modifier.testTag(WithdrawTags.MESSAGE))
            is WithdrawState.NeedsWallet -> {
                Text(WithdrawCopy.NEEDS_WALLET, color = HdColors.Ash, modifier = Modifier.testTag(WithdrawTags.MESSAGE))
                state.problem?.let { Text(it, color = HdColors.Cooling, modifier = Modifier.testTag(WithdrawTags.PROBLEM)) }
                Button(
                    onClick = onConnect,
                    enabled = !state.working,
                    modifier = Modifier.fillMaxWidth().testTag(WithdrawTags.CONNECT),
                    colors = ButtonDefaults.buttonColors(containerColor = HdColors.Ember, contentColor = HdColors.Charcoal),
                ) { Text(if (state.working) "Waiting for your wallet…" else "Connect wallet") }
            }
            is WithdrawState.Unavailable -> {
                Text(state.message, color = HdColors.Cooling, modifier = Modifier.testTag(WithdrawTags.MESSAGE))
                OutlinedButton(onClick = onRetry, modifier = Modifier.testTag(WithdrawTags.RETRY)) { Text("Try again") }
            }
            is WithdrawState.Done -> Text(state.message, color = HdColors.OreGold, modifier = Modifier.testTag(WithdrawTags.MESSAGE))
            is WithdrawState.Ready -> Ready(state, onRevoke, onCloseRig, onConfirm)
        }
        TextButton(onClick = onClose, modifier = Modifier.testTag(WithdrawTags.CLOSE)) { Text(if (state is WithdrawState.Done) "Done" else "Close") }
    }
}

@Composable
private fun Ready(state: WithdrawState.Ready, onRevoke: (Boolean) -> Unit, onCloseRig: (Boolean) -> Unit, onConfirm: () -> Unit) {
    val facts = state.facts
    Text(WithdrawCopy.automation(facts), color = HdColors.Ash, modifier = Modifier.testTag(WithdrawTags.AUTOMATION))
    if (facts.canRevoke) {
        Choice(WithdrawCopy.REVOKE_CHOICE, state.revoke, WithdrawTags.REVOKE, enabled = !state.working) { onRevoke(!state.revoke) }
        if (state.revoke) {
            WithdrawCopy.revokeDetail(facts)?.let { Text(it, color = HdColors.Ash, modifier = Modifier.testTag(WithdrawTags.REVOKE_DETAIL)) }
        }
    }
    Text(WithdrawCopy.rig(facts), color = HdColors.Ash, modifier = Modifier.testTag(WithdrawTags.RIG))
    if (facts.canCloseRig) {
        Choice(WithdrawCopy.CLOSE_CHOICE, state.closeRig, WithdrawTags.CLOSE_RIG, enabled = !state.working) { onCloseRig(!state.closeRig) }
        if (state.closeRig) {
            WithdrawCopy.closeDetail(facts)?.let { Text(it, color = HdColors.Cooling, modifier = Modifier.testTag(WithdrawTags.CLOSE_DETAIL)) }
        }
    }
    state.problem?.let { Text(it, color = HdColors.Cooling, modifier = Modifier.testTag(WithdrawTags.PROBLEM)) }
    Button(
        onClick = onConfirm,
        enabled = state.canSign && !state.working,
        modifier = Modifier.fillMaxWidth().testTag(WithdrawTags.CONFIRM),
        colors = ButtonDefaults.buttonColors(containerColor = HdColors.Ember, contentColor = HdColors.Charcoal),
    ) { Text(if (state.working) "Waiting for your wallet…" else WithdrawCopy.button(state.revoke, state.closeRig)) }
    if (!state.canSign) {
        Text(WithdrawCopy.NOTHING_CHOSEN, color = HdColors.AshMuted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag(WithdrawTags.NOTHING))
    }
}

/** An on/off option: filled when chosen, outlined when not. Tapping it again takes the choice back. */
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
        OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier) { Text(label) }
    }
}
