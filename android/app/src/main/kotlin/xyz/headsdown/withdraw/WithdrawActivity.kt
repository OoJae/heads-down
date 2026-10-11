package xyz.headsdown.withdraw

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import dagger.hilt.android.AndroidEntryPoint
import xyz.headsdown.BuildConfig
import xyz.headsdown.clockout.Confirmed
import xyz.headsdown.clockout.Fact
import xyz.headsdown.clockout.Sheet
import xyz.headsdown.clockout.SheetHeader
import xyz.headsdown.clockout.SheetMessage
import xyz.headsdown.clockout.SheetNotice
import xyz.headsdown.clockout.SheetRule
import xyz.headsdown.clockout.SheetText
import xyz.headsdown.clockout.SheetWords
import xyz.headsdown.clockout.SkeletonLedger
import xyz.headsdown.clockout.fallAsSheet
import xyz.headsdown.clockout.riseAsSheet
import xyz.headsdown.config.ExplorerLinks
import xyz.headsdown.config.openExplorerLink
import xyz.headsdown.core.design.Hd
import xyz.headsdown.core.design.HeadsDownTheme
import xyz.headsdown.core.design.components.BarButton
import xyz.headsdown.core.design.components.BarButtonStyle
import xyz.headsdown.core.design.components.Choice
import xyz.headsdown.core.design.components.LocalHdHaptics
import xyz.headsdown.core.design.components.NoticeTone
import xyz.headsdown.core.wallet.HeadsDownWallet
import xyz.headsdown.feature.shift.ShiftStatusRepository
import xyz.headsdown.feature.shift.isRunning
import xyz.headsdown.rig.ClockInPolicy
import xyz.headsdown.surface.haptics.Haptics
import xyz.headsdown.ui.AppHaptics
import javax.inject.Inject
import android.graphics.Color as AndroidColor

/**
 * The way out: take the SOL back out of the ORE Automation (Revoke), and close the rig. **Not
 * exported**, reads nothing from its Intent, and signs nothing by itself: the screen says what
 * each choice does, the user chooses and taps, the wallet approves.
 */
@AndroidEntryPoint
class WithdrawActivity : ComponentActivity() {

    private val vm: WithdrawViewModel by viewModels()

    @Inject lateinit var wallet: HeadsDownWallet

    @Inject lateinit var haptics: Haptics

    @Inject lateinit var shiftStatus: ShiftStatusRepository

    /** Registered before the Activity is STARTED, as MWA requires. */
    private lateinit var sender: ActivityResultSender

    override fun onCreate(savedInstanceState: Bundle?) {
        // The app is dark whatever the phone is set to: the icons in both bars stay light.
        enableEdgeToEdge(SystemBarStyle.dark(AndroidColor.TRANSPARENT), SystemBarStyle.dark(AndroidColor.TRANSPARENT))
        super.onCreate(savedInstanceState)
        riseAsSheet()
        // What a screen reader says when this window opens, in place of the app's name.
        title = WithdrawLedger.TITLE
        sender = ActivityResultSender(this)
        val model = vm.model
        // No cue reaches the motor while a shift is running: the governor refuses a shift moment.
        val appHaptics = AppHaptics(haptics) { shiftStatus.snapshot.value.state.isRunning }
        setContent {
            HeadsDownTheme {
                CompositionLocalProvider(LocalHdHaptics provides appHaptics) {
                    val state by model.state.collectAsStateWithLifecycle()
                    WithdrawScreen(
                        state = state,
                        onConnect = { model.connect { wallet.connect(sender) } },
                        onRevoke = model::setRevoke,
                        onCloseRig = model::setCloseRig,
                        onConfirm = { model.confirm { prepare -> wallet.signAndSendInSession(sender, prepare) } },
                        onRetry = model::load,
                        onClose = ::finish,
                        onOpenTransaction = { openExplorerLink(it) },
                    )
                }
            }
        }
    }

    override fun finish() {
        super.finish()
        fallAsSheet()
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
    const val TRANSACTION = "withdraw-transaction"
}

/**
 * The short words of the withdraw sheet. The sheet's name, its title and the wallet button are
 * the ones it had; the redesign added the names of the ledger's two rows ("ORE Automation",
 * "Rig", "Rig rent"). Each row still carries its full sentence from [WithdrawCopy] underneath.
 * Written as they are read, capitals are drawn; kept together so the honesty scan reads every one.
 */
object WithdrawLedger {
    const val EYEBROW = "Your SOL, your rig"
    const val TITLE = "Take it back"
    const val AUTOMATION = "ORE Automation"
    const val RIG = "Rig"
    const val RIG_RENT = "Rig rent"
    const val CONNECT = "Connect wallet"

    fun sol(lamports: ULong): String = "${ClockInPolicy.sol(lamports)} SOL"

    val all: List<String> = listOf(EYEBROW, TITLE, AUTOMATION, RIG, RIG_RENT, CONNECT) + SheetWords.all
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
    cluster: String = BuildConfig.SOLANA_CHAIN,
    onOpenTransaction: (url: String) -> Unit = {},
) {
    // One control closes the sheet: the bar in the dock when nothing else needs it, the header's otherwise.
    val dockIsTaken = state is WithdrawState.Ready || state is WithdrawState.NeedsWallet || state is WithdrawState.Unavailable
    Sheet(
        confirmed = state is WithdrawState.Done,
        problem = when (state) {
            is WithdrawState.Ready -> state.problem
            is WithdrawState.NeedsWallet -> state.problem
            is WithdrawState.Unavailable -> state.message
            else -> null
        },
        content = {
            SheetHeader(
                eyebrow = WithdrawLedger.EYEBROW,
                title = WithdrawLedger.TITLE.takeUnless { state is WithdrawState.Done },
                closeTag = WithdrawTags.CLOSE.takeIf { dockIsTaken },
                onClose = onClose,
            ) {
                // The confirmed result takes the title's place: its statement stands where the title stood.
                if (state is WithdrawState.Done) {
                    Confirmed(
                        message = state.message,
                        tag = WithdrawTags.MESSAGE,
                        transactionUrl = ExplorerLinks.tx(state.signature, cluster),
                        transactionTag = WithdrawTags.TRANSACTION,
                        onOpenTransaction = onOpenTransaction,
                    )
                }
            }
            when (state) {
                WithdrawState.Loading -> {
                    SheetMessage("Reading the chain…", WithdrawTags.MESSAGE, quiet = true)
                    SkeletonLedger(rows = 2)
                }
                is WithdrawState.NeedsWallet -> {
                    SheetMessage(WithdrawCopy.NEEDS_WALLET, WithdrawTags.MESSAGE)
                    state.problem?.let { SheetNotice(it, WithdrawTags.PROBLEM, NoticeTone.Problem) }
                }
                is WithdrawState.Unavailable -> SheetNotice(state.message, WithdrawTags.MESSAGE, NoticeTone.Problem)
                is WithdrawState.Done -> Unit
                is WithdrawState.Ready -> Ready(state, onRevoke, onCloseRig)
            }
        },
        dock = {
            val bar = Modifier.fillMaxWidth()
            when (state) {
                WithdrawState.Loading ->
                    BarButton(SheetWords.CLOSE, onClose, bar.testTag(WithdrawTags.CLOSE), style = BarButtonStyle.Secondary)
                is WithdrawState.NeedsWallet -> BarButton(
                    label = if (state.working) SheetWords.WAITING else WithdrawLedger.CONNECT,
                    onClick = onConnect,
                    modifier = bar.testTag(WithdrawTags.CONNECT),
                    busy = state.working,
                )
                is WithdrawState.Unavailable -> BarButton(SheetWords.RETRY, onRetry, bar.testTag(WithdrawTags.RETRY))
                is WithdrawState.Done -> BarButton(SheetWords.DONE, onClose, bar.testTag(WithdrawTags.CLOSE))
                is WithdrawState.Ready -> BarButton(
                    label = if (state.working) SheetWords.WAITING else WithdrawCopy.button(state.revoke, state.closeRig),
                    onClick = onConfirm,
                    modifier = bar.testTag(WithdrawTags.CONFIRM),
                    // Closing the rig cannot be taken back: the bar says so in ember.
                    style = if (state.closeRig) BarButtonStyle.Danger else BarButtonStyle.Primary,
                    enabled = state.canSign,
                    busy = state.working,
                )
            }
        },
    )
}

/** The ledger: what the Automation holds and what the rig's account would hand back, each with its choice. */
@Composable
private fun ColumnScope.Ready(state: WithdrawState.Ready, onRevoke: (Boolean) -> Unit, onCloseRig: (Boolean) -> Unit) {
    val facts = state.facts

    SheetRule()
    Fact(
        label = WithdrawLedger.AUTOMATION,
        sentence = WithdrawCopy.automation(facts),
        tag = WithdrawTags.AUTOMATION,
        figure = facts.revoke?.let { WithdrawLedger.sol(it.lamports) },
    )
    if (facts.canRevoke) {
        Choice(
            label = WithdrawCopy.REVOKE_CHOICE,
            selected = state.revoke,
            onClick = { onRevoke(!state.revoke) },
            modifier = Modifier.testTag(WithdrawTags.REVOKE),
            enabled = !state.working,
            role = Role.Checkbox,
        )
        if (state.revoke) WithdrawCopy.revokeDetail(facts)?.let { SheetNotice(it, WithdrawTags.REVOKE_DETAIL) }
    }

    SheetRule()
    Fact(
        label = if (facts.rigClose != null) WithdrawLedger.RIG_RENT else WithdrawLedger.RIG,
        sentence = WithdrawCopy.rig(facts),
        tag = WithdrawTags.RIG,
        figure = facts.rigClose?.let { WithdrawLedger.sol(it.rentBackLamports) },
    )
    if (facts.canCloseRig) {
        Choice(
            label = WithdrawCopy.CLOSE_CHOICE,
            selected = state.closeRig,
            onClick = { onCloseRig(!state.closeRig) },
            modifier = Modifier.testTag(WithdrawTags.CLOSE_RIG),
            enabled = !state.working,
            role = Role.Checkbox,
        )
        if (state.closeRig) WithdrawCopy.closeDetail(facts)?.let { SheetNotice(it, WithdrawTags.CLOSE_DETAIL, NoticeTone.Problem) }
    }

    state.problem?.let { SheetNotice(it, WithdrawTags.PROBLEM, NoticeTone.Problem) }
    if (!state.canSign) SheetText(WithdrawCopy.NOTHING_CHOSEN, Modifier.testTag(WithdrawTags.NOTHING), color = Hd.colors.ash)
}
