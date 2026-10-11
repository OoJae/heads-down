package xyz.headsdown.clockout

import android.app.Activity
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import xyz.headsdown.BuildConfig
import xyz.headsdown.R
import xyz.headsdown.config.ExplorerLinks
import xyz.headsdown.config.openExplorerLink
import xyz.headsdown.core.chain.clockout.BondOutcome
import xyz.headsdown.core.chain.clockout.ShiftOutcome
import xyz.headsdown.core.design.Hd
import xyz.headsdown.core.design.HdDimens
import xyz.headsdown.core.design.HdType
import xyz.headsdown.core.design.HeadsDownTheme
import xyz.headsdown.core.design.components.ActionDock
import xyz.headsdown.core.design.components.BarButton
import xyz.headsdown.core.design.components.BarButtonStyle
import xyz.headsdown.core.design.components.Choice
import xyz.headsdown.core.design.components.DisplayText
import xyz.headsdown.core.design.components.Label
import xyz.headsdown.core.design.components.LinkRow
import xyz.headsdown.core.design.components.LocalHdHaptics
import xyz.headsdown.core.design.components.Notice
import xyz.headsdown.core.design.components.NoticeTone
import xyz.headsdown.core.design.components.PinnedBarScreen
import xyz.headsdown.core.design.components.SkeletonLine
import xyz.headsdown.core.design.components.TextAction
import xyz.headsdown.core.wallet.HeadsDownWallet
import xyz.headsdown.rig.ClockInPolicy
import javax.inject.Inject
import kotlin.math.roundToInt
import android.graphics.Color as AndroidColor

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
        // The app is dark whatever the phone is set to: the icons in both bars stay light.
        enableEdgeToEdge(SystemBarStyle.dark(AndroidColor.TRANSPARENT), SystemBarStyle.dark(AndroidColor.TRANSPARENT))
        super.onCreate(savedInstanceState)
        riseAsSheet()
        // What a screen reader says when this window opens, in place of the app's name.
        title = ClockOutLedger.TITLE
        sender = ActivityResultSender(this)
        val model = vm.model
        setContent {
            HeadsDownTheme {
                val state by model.state.collectAsStateWithLifecycle()
                ClockOutScreen(
                    state = state,
                    onClaimAll = model::setClaimAll,
                    onEndEarly = model::setEndEarly,
                    onConfirm = { model.confirm { prepare -> wallet.signAndSendInSession(sender, prepare) } },
                    onRetry = model::load,
                    onClose = ::finish,
                    onOpenTransaction = { openExplorerLink(it) },
                )
            }
        }
    }

    override fun finish() {
        super.finish()
        fallAsSheet()
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
    const val TRANSACTION = "clock-out-transaction"
}

/**
 * The short words of the clock-out sheet. Its name and title are the ones it had; the redesign
 * added the names of the ledger's rows and what the shift row says at a glance. Each row still
 * carries its full sentence from [ClockOutCopy] underneath; these only name it. Written as they
 * are read, capitals are drawn; kept together so the honesty scan reads every one.
 */
object ClockOutLedger {
    const val EYEBROW = "Clock out"
    const val TITLE = "Clock out"
    const val SHIFT = "Shift"
    const val RENT = "Log rent"
    const val BOND = "Focus Bond"
    const val SOL = "SOL back"
    const val ORE = "In your Miner"

    const val SHIFT_NONE = "None open"
    const val SHIFT_COMPLETED = "Completed"
    const val SHIFT_ENDED_EARLY = "Ended early"
    const val SHIFT_STAYS_OPEN = "Stays open"

    /** What this clock-out does to the shift, in a word or two; the sentence under it says why. */
    fun shift(outcome: ShiftOutcome): String = when (outcome) {
        ShiftOutcome.NoOpenShift -> SHIFT_NONE
        is ShiftOutcome.Ends -> if (outcome.completed) SHIFT_COMPLETED else SHIFT_ENDED_EARLY
        is ShiftOutcome.LeftOpen -> SHIFT_STAYS_OPEN
    }

    /** The bond's amount, whatever becomes of it; null without a bond. */
    fun bond(outcome: BondOutcome): String? = when (outcome) {
        BondOutcome.None -> null
        is BondOutcome.Released -> skr(outcome.amount)
        is BondOutcome.Forfeit -> skr(outcome.amount)
        is BondOutcome.StaysLocked -> skr(outcome.amount)
    }

    fun sol(lamports: ULong): String = "${ClockInPolicy.sol(lamports)} SOL"

    fun ore(atoms: ULong): String = "${ClockOutCopy.ore(atoms)} ORE"

    private fun skr(units: ULong): String = "${ClockOutCopy.skr(units)} SKR"

    val all: List<String> = listOf(
        EYEBROW, TITLE, SHIFT, RENT, BOND, SOL, ORE, SHIFT_NONE, SHIFT_COMPLETED, SHIFT_ENDED_EARLY, SHIFT_STAYS_OPEN,
    ) + SheetWords.all
}

@Composable
fun ClockOutScreen(
    state: ClockOutState,
    onClaimAll: (Boolean) -> Unit,
    onEndEarly: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onRetry: () -> Unit,
    onClose: () -> Unit,
    cluster: String = BuildConfig.SOLANA_CHAIN,
    onOpenTransaction: (url: String) -> Unit = {},
) {
    // One control closes the sheet: the bar in the dock when nothing else needs it, the header's otherwise.
    val dockIsTaken = state is ClockOutState.Ready || state is ClockOutState.Unavailable
    Sheet(
        confirmed = state is ClockOutState.Done,
        problem = (state as? ClockOutState.Ready)?.problem ?: (state as? ClockOutState.Unavailable)?.message,
        content = {
            SheetHeader(
                eyebrow = ClockOutLedger.EYEBROW,
                title = ClockOutLedger.TITLE.takeUnless { state is ClockOutState.Done },
                closeTag = ClockOutTags.CLOSE.takeIf { dockIsTaken },
                onClose = onClose,
            ) {
                // The confirmed result takes the title's place: its statement stands where the title stood.
                if (state is ClockOutState.Done) {
                    Confirmed(
                        message = state.message,
                        tag = ClockOutTags.MESSAGE,
                        transactionUrl = ExplorerLinks.tx(state.signature, cluster),
                        transactionTag = ClockOutTags.TRANSACTION,
                        onOpenTransaction = onOpenTransaction,
                    )
                }
            }
            when (state) {
                ClockOutState.Loading -> {
                    SheetMessage("Reading your rig from the chain…", ClockOutTags.MESSAGE, quiet = true)
                    SkeletonLedger()
                }
                ClockOutState.NoRig -> SheetMessage(
                    "No rig on this phone yet. Clock in once, and your shifts and your ORE show up here.",
                    ClockOutTags.MESSAGE,
                )
                is ClockOutState.Unavailable -> SheetNotice(state.message, ClockOutTags.MESSAGE, NoticeTone.Problem)
                is ClockOutState.Done -> Unit
                is ClockOutState.Ready -> Ready(state, onClaimAll, onEndEarly)
            }
        },
        dock = {
            val bar = Modifier.fillMaxWidth()
            when (state) {
                ClockOutState.Loading, ClockOutState.NoRig ->
                    BarButton(SheetWords.CLOSE, onClose, bar.testTag(ClockOutTags.CLOSE), style = BarButtonStyle.Secondary)
                is ClockOutState.Unavailable -> BarButton(SheetWords.RETRY, onRetry, bar.testTag(ClockOutTags.RETRY))
                is ClockOutState.Done -> BarButton(SheetWords.DONE, onClose, bar.testTag(ClockOutTags.CLOSE))
                is ClockOutState.Ready -> BarButton(
                    label = when {
                        state.working -> SheetWords.WAITING
                        state.endEarly -> "End the shift and clock out"
                        else -> "Clock out"
                    },
                    onClick = onConfirm,
                    modifier = bar.testTag(ClockOutTags.CONFIRM),
                    // Ending a shift inside its window cannot be taken back: the bar says so in ember.
                    style = if (state.endEarly) BarButtonStyle.Danger else BarButtonStyle.Primary,
                    enabled = state.canSign,
                    busy = state.working,
                )
            }
        },
    )
}

/** The ledger: one row for each thing the transaction does, each with its sentence underneath. */
@Composable
private fun ColumnScope.Ready(state: ClockOutState.Ready, onClaimAll: (Boolean) -> Unit, onEndEarly: (Boolean) -> Unit) {
    val colors = Hd.colors
    val shown = state.shown
    val lines = ClockOutCopy.lines(shown)

    SheetRule()
    Fact(ClockOutLedger.SHIFT, lines.shift, ClockOutTags.SHIFT, figure = ClockOutLedger.shift(shown.shift))
    if (state.facts.canEndEarly) {
        Choice(
            label = "End the shift now",
            selected = state.endEarly,
            onClick = { onEndEarly(!state.endEarly) },
            modifier = Modifier.testTag(ClockOutTags.END_EARLY),
            enabled = !state.working,
            role = Role.Checkbox,
        )
        if (state.endEarly) {
            ClockOutCopy.endEarlyWarning(state.facts)?.let { SheetNotice(it, ClockOutTags.END_EARLY_WARNING, NoticeTone.Problem) }
        }
    }
    lines.rent?.let {
        SheetRule()
        Fact(ClockOutLedger.RENT, it, ClockOutTags.RENT, figure = ClockOutLedger.sol(shown.shiftLogRent))
    }
    lines.bond?.let {
        SheetRule()
        // A forfeit is the one amount here that is lost: ember, beside the sentence that says so.
        val lost = shown.bond is BondOutcome.Forfeit
        Fact(ClockOutLedger.BOND, it, ClockOutTags.BOND, figure = ClockOutLedger.bond(shown.bond), figureColor = if (lost) colors.ember else colors.chalk)
    }
    lines.sol?.let {
        SheetRule()
        Fact(ClockOutLedger.SOL, it, ClockOutTags.SOL, figure = ClockOutLedger.sol(shown.returnedSolLamports))
    }
    SheetRule()
    Fact(
        label = ClockOutLedger.ORE,
        sentence = lines.ore,
        tag = ClockOutTags.ORE,
        figure = ClockOutLedger.ore(shown.oreInMiner),
        // Gold is for ORE that is there.
        figureColor = if (shown.oreInMiner > 0uL) colors.seam else colors.ash,
    )
    if (lines.claim != null) {
        SheetText(ClockOutCopy.ORE_KEPT, color = colors.ash)
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(HdDimens.Rhythm)) {
            Choice(
                label = "Keep it in my Miner",
                selected = !state.claimAll,
                onClick = { onClaimAll(false) },
                modifier = Modifier.testTag(ClockOutTags.KEEP_CHOICE),
                enabled = !state.working,
            )
            Choice(
                label = "Claim all to my wallet",
                selected = state.claimAll,
                onClick = { onClaimAll(true) },
                modifier = Modifier.testTag(ClockOutTags.CLAIM_CHOICE),
                enabled = !state.working,
            )
        }
        if (state.claimAll) SheetNotice(lines.claim, ClockOutTags.CLAIM)
    }
    state.problem?.let { SheetNotice(it, ClockOutTags.PROBLEM, NoticeTone.Problem) }
    if (!state.canSign) SheetText(ClockOutCopy.NOTHING_TO_SIGN, Modifier.testTag(ClockOutTags.NOTHING), color = colors.ash)
}

// ---------------------------------------------------------------------------------------------
// The parts of a sheet. The withdraw sheet (WithdrawActivity.kt) is built from the same ones.
// ---------------------------------------------------------------------------------------------

/** The words the two sheets share. "See this transaction on-chain" is the one the redesign added. */
object SheetWords {
    const val CLOSE = "Close"
    const val DONE = "Done"
    const val RETRY = "Try again"
    const val WAITING = "Waiting for your wallet…"
    const val SEE_TRANSACTION = "See this transaction on-chain"

    /** How every confirmed result begins: the statement the sheet sets in the display face. */
    const val CONFIRMED = "Confirmed on-chain."

    val all: List<String> = listOf(CLOSE, DONE, RETRY, WAITING, SEE_TRANSACTION, CONFIRMED)
}

/** The sheet comes up from the bottom edge when it opens, and is set to go back down when it closes. */
internal fun ComponentActivity.riseAsSheet() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, R.anim.sheet_rise, R.anim.sheet_stay)
        overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, R.anim.sheet_stay, R.anim.sheet_fall)
    } else {
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.sheet_rise, R.anim.sheet_stay)
    }
}

/** Before Android 14 the closing animation is named when the activity finishes, not when it is made. */
internal fun ComponentActivity.fallAsSheet() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.sheet_stay, R.anim.sheet_fall)
    }
}

/**
 * A sheet as a screen: the page that scrolls, and under it the dock with the one bar the state
 * calls for. It asks the motor for a cue when the result is [confirmed] and when a [problem]
 * appears, and it knows what was on the page from the start from what joined it later.
 */
@Composable
internal fun Sheet(
    confirmed: Boolean,
    problem: String?,
    content: @Composable ColumnScope.() -> Unit,
    dock: @Composable ColumnScope.() -> Unit,
) {
    SheetCues(confirmed, problem)
    val arrival = remember { SheetArrival() }
    SideEffect { arrival.shown = true }
    CompositionLocalProvider(LocalSheetArrival provides arrival) {
        PinnedBarScreen(content = content, dock = { ActionDock(content = dock) })
    }
}

/** Whether the sheet has been composed once. A plain field: nothing is redrawn when it changes. */
internal class SheetArrival {
    var shown = false
}

internal val LocalSheetArrival = staticCompositionLocalOf<SheetArrival?> { null }

/**
 * The top of a sheet: its name in small capitals, the way out beside it while the dock is taken
 * by another action ([closeTag] not null), and under them the [title]. Without a title the small
 * name is the screen's heading, and [inPlaceOfTitle] is drawn where the title would be: the
 * confirmed result, whose statement then starts on the line the title stood on.
 */
@Composable
internal fun SheetHeader(
    eyebrow: String,
    title: String?,
    closeTag: String?,
    onClose: () -> Unit,
    inPlaceOfTitle: @Composable () -> Unit = {},
) {
    Column {
        Row(
            Modifier.fillMaxWidth().heightIn(min = HdDimens.Touch),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Label(eyebrow, Modifier.weight(1f), heading = title == null)
            if (closeTag != null) {
                // A text action is a touch target wide at least and starts its word at its own left
                // edge, so a short word would end short of the margin. It is moved out by what is
                // left over: the word ends where the amounts under it end.
                val measurer = rememberTextMeasurer()
                val word = Hd.type.button
                val density = LocalDensity.current
                val slack = remember(measurer, word, density) {
                    with(density) { (HdDimens.Touch.toPx() - measurer.measure(SheetWords.CLOSE, word).size.width).coerceAtLeast(0f).roundToInt() }
                }
                TextAction(SheetWords.CLOSE, onClose, Modifier.offset { IntOffset(slack, 0) }.testTag(closeTag))
            }
        }
        if (title != null) DisplayText(title, Modifier.semantics { heading() }) else inPlaceOfTitle()
    }
}

/** A hairline between two rows of the ledger. Decoration: nothing to a screen reader. */
@Composable
internal fun SheetRule() {
    Box(Modifier.fillMaxWidth().height(HdDimens.Hairline).background(Hd.colors.hairline))
}

/** A sentence of the sheet, in body text. */
@Composable
internal fun SheetText(text: String, modifier: Modifier = Modifier, color: Color = Hd.colors.chalk) {
    BasicText(text, modifier, style = Hd.type.body.copy(color = color))
}

/** What a state has to say when there is no ledger: said once to a screen reader when it appears. */
@Composable
internal fun SheetMessage(text: String, tag: String, quiet: Boolean = false) {
    SheetText(
        text,
        Modifier.testTag(tag).semantics { liveRegion = LiveRegionMode.Polite },
        color = if (quiet) Hd.colors.ash else Hd.colors.chalk,
    )
}

/**
 * A row of the ledger: what it is in small capitals, the amount opposite it in the mono face, and
 * under them the sentence that says what the transaction does with it.
 *
 * - The name and the amount are one node to a screen reader ("Log rent, 0.00130048 SOL"); the
 *   sentence is a node of its own carrying [tag], so a test gets exactly that sentence.
 * - The amount is never cut and never squeezed: when the two do not fit on a line with 16dp
 *   between them (a large system font), it drops under the name, at the left edge.
 * - The amount is the brightest thing in the row. The sentence is in ash: it is all there, and the
 *   ledger stays quiet around the choices and the warnings.
 */
@Composable
internal fun Fact(label: String, sentence: String, tag: String, figure: String? = null, figureColor: Color = Hd.colors.chalk) {
    Column(verticalArrangement = Arrangement.spacedBy(HdDimens.Unit)) {
        FlowRow(
            Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(HdDimens.Unit),
        ) {
            // The padding is the least room between the two: with less, the amount takes the next line.
            Label(label, Modifier.alignByBaseline().padding(end = 16.dp))
            if (figure != null) BasicText(figure, Modifier.alignByBaseline(), style = Hd.type.figure.copy(color = figureColor))
        }
        SheetText(sentence, Modifier.testTag(tag), color = Hd.colors.ash)
    }
}

/**
 * A notice that says what a choice costs, or what went wrong. One node whose text is [text] and
 * which carries [tag]; a polite live region. It is scrolled into view when it appears and again
 * when its text changes: a consequence must not arrive below the edge of the screen.
 */
@Composable
internal fun SheetNotice(text: String, tag: String, tone: NoticeTone = NoticeTone.Info) {
    Arriving(key = text) {
        Notice(text, Modifier.testTag(tag).semantics(mergeDescendants = true) {}, tone = tone)
    }
}

/**
 * Something that joins a sheet already on screen: it comes in on the settle spring (in one step
 * with motion reduced), and with [scrollTo] the page is scrolled until all of it shows, again
 * whenever [key] changes.
 *
 * What is there when the sheet is first drawn, or drawn again after a rotation, did not arrive:
 * it is simply there, and the page opens at its top or where the reader left it.
 *
 * It only fades. Growing into place would change the page's height while the page is being
 * scrolled to it, and a scroll that cannot go as far as it was asked gives up.
 */
@Composable
internal fun Arriving(key: Any? = Unit, scrollTo: Boolean = true, content: @Composable () -> Unit) {
    val arrival = LocalSheetArrival.current
    val motion = Hd.motion
    // Plain, and read once: was this part of the sheet as it first appeared?
    val there = remember { arrival != null && !arrival.shown }
    val alpha = remember { Animatable(if (there) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (motion.reduced) alpha.snapTo(1f) else alpha.animateTo(1f, motion.settle())
    }
    val requester = remember { BringIntoViewRequester() }
    var placed by remember { mutableStateOf(false) }
    val first = remember { booleanArrayOf(true) }
    LaunchedEffect(key) {
        val arrivedWithTheSheet = first[0] && there
        first[0] = false
        if (arrivedWithTheSheet || !scrollTo) return@LaunchedEffect
        // Asked before the first layout, the request would be for a box of no size at the top.
        snapshotFlow { placed }.first { it }
        requester.bringIntoView()
    }
    Box(
        Modifier
            .bringIntoViewRequester(requester)
            .onPlaced { placed = true }
            .graphicsLayer { this.alpha = alpha.value },
    ) { content() }
}

/** Rows that have not arrived yet: still, and silent. The sentence above them says what is loading. */
@Composable
internal fun SkeletonLedger(rows: Int = 3) {
    repeat(rows) { row ->
        SheetRule()
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                SkeletonLine(widthFraction = 0.28f)
                SkeletonLine(Modifier.width(if (row == 0) 72.dp else 112.dp))
            }
            SkeletonLine()
            SkeletonLine(widthFraction = if (row % 2 == 0) 0.72f else 0.46f)
        }
    }
}

/**
 * The confirmed result, alone on the sheet: ONE text whose words are exactly [message], with its
 * opening statement set in the display face and the rest in body text, then a way to see the
 * transaction when there is a public explorer for it. Focus moves to the message when it appears.
 */
@Composable
internal fun Confirmed(
    message: String,
    tag: String,
    transactionUrl: String?,
    transactionTag: String,
    onOpenTransaction: (url: String) -> Unit,
) {
    val colors = Hd.colors
    val type = Hd.type
    val density = LocalDensity.current
    val text = remember(message, colors, type, density) {
        // The display face is sized as DisplayText sizes a title, with the system's font scale held
        // at 1.3x, so the statement is exactly as large as the title it replaces; the body grows as asked.
        val held = if (density.fontScale <= HdType.DISPLAY_SCALE_CAP) density else Density(density.density, HdType.DISPLAY_SCALE_CAP)
        val statement = with(held) { type.display.fontSize.toPx() }
        val body = with(density) { type.body.fontSize.toPx() }
        val gap = with(density) { STATEMENT_GAP.toPx() }
        confirmedText(message, type.display, statementEm = statement / body, gapEm = gap / body, ore = colors.seam)
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Arriving(scrollTo = false) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            BasicText(
                text,
                Modifier
                    .testTag(tag)
                    .focusRequester(focus)
                    .focusable()
                    .semantics { liveRegion = LiveRegionMode.Polite },
                style = type.body.copy(color = colors.chalk),
            )
            if (transactionUrl != null) {
                LinkRow(SheetWords.SEE_TRANSACTION, onClick = { onOpenTransaction(transactionUrl) }, modifier = Modifier.testTag(transactionTag))
            }
        }
    }
}

private val ORE_AMOUNT = Regex("""<?\d[\d.]* ORE\b""")

/** The room between the confirmed statement and what it did. */
private val STATEMENT_GAP = 12.dp

/**
 * [message] as it is drawn: when it opens with [SheetWords.CONFIRMED], that statement is a
 * paragraph in the display face, an empty line parts it from the rest, and the rest is a
 * paragraph in the style of the text around it. Amounts of ORE are in gold ([ore]). No character
 * is added or removed: the text a test, a screen reader and the emulator script read is [message]
 * itself.
 *
 * [statementEm] and [gapEm] are the size of the statement and the height of the empty line, in
 * ems of the text around them. One text measures a paragraph's line height on its own base size,
 * and with a large system font a size in sp no longer grows in step with another: in ems of the
 * one base size, the statement and its line stay in proportion at every font scale.
 */
internal fun confirmedText(message: String, statement: TextStyle, statementEm: Float, gapEm: Float, ore: Color): AnnotatedString =
    buildAnnotatedString {
        append(message)
        val end = SheetWords.CONFIRMED.length
        if (message.startsWith(SheetWords.CONFIRMED)) {
            val leading = if (statement.lineHeight.isEm) statement.lineHeight.value else 1f
            addStyle(statement.toParagraphStyle().copy(lineHeight = (statementEm * leading).em), 0, end)
            addStyle(statement.toSpanStyle().copy(fontSize = statementEm.em), 0, end)
            // The space after the statement is a paragraph of its own, one empty line: one text has
            // no other way to put room between two paragraphs, and neither may begin with a space.
            if (message.getOrNull(end) == ' ') {
                addStyle(ParagraphStyle(lineHeight = gapEm.em, lineHeightStyle = EXACT_LINE), end, end + 1)
                addStyle(SpanStyle(fontSize = (gapEm / 2).em), end, end + 1)
            }
        }
        for (amount in ORE_AMOUNT.findAll(message)) addStyle(SpanStyle(color = ore), amount.range.first, amount.range.last + 1)
    }

/** A line exactly as tall as its line height, whatever the font in it. */
private val EXACT_LINE = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)

/**
 * The two cues a sheet asks of the phone's motor: one when the result is confirmed, one when a
 * problem appears. Each plays once, also across a rotation.
 */
@Composable
internal fun SheetCues(confirmed: Boolean, problem: String?) {
    val haptics = LocalHdHaptics.current
    val now = if (confirmed) "confirmed" else problem?.let { "problem: $it" }
    var cued by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(now) {
        if (now == cued) return@LaunchedEffect
        cued = now
        if (confirmed) haptics.confirm() else if (problem != null) haptics.refuse()
    }
}
