package xyz.headsdown.nights

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import xyz.headsdown.BuildConfig
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
import xyz.headsdown.core.chain.indexer.IndexerShift
import xyz.headsdown.core.design.Hd
import xyz.headsdown.core.design.HdDimens
import xyz.headsdown.core.design.HeadsDownTheme
import xyz.headsdown.core.design.components.BarButton
import xyz.headsdown.core.design.components.BarButtonStyle
import xyz.headsdown.core.design.components.Label
import xyz.headsdown.core.design.components.LocalHdHaptics
import xyz.headsdown.core.design.components.NoticeTone
import xyz.headsdown.core.design.components.StatBlock
import xyz.headsdown.core.design.components.StatRow
import xyz.headsdown.core.design.components.TextAction
import xyz.headsdown.feature.shift.ShiftStatusRepository
import xyz.headsdown.feature.shift.isRunning
import xyz.headsdown.surface.haptics.Haptics
import xyz.headsdown.ui.AppHaptics
import xyz.headsdown.ui.launch
import java.time.ZoneId
import javax.inject.Inject
import android.graphics.Color as AndroidColor

/**
 * The night log: the streak, the last 28 days, and every finished shift of this phone's rig.
 * **Not exported**, reads nothing from its Intent, and only reads: there is no wallet here and
 * nothing to sign. It names no amount of SOL or ORE.
 */
@AndroidEntryPoint
class NightsActivity : ComponentActivity() {

    private val vm: NightsViewModel by viewModels()

    @Inject lateinit var haptics: Haptics

    @Inject lateinit var shiftStatus: ShiftStatusRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        // The app is dark whatever the phone is set to: the icons in both bars stay light.
        enableEdgeToEdge(SystemBarStyle.dark(AndroidColor.TRANSPARENT), SystemBarStyle.dark(AndroidColor.TRANSPARENT))
        super.onCreate(savedInstanceState)
        riseAsSheet()
        // What a screen reader says when this window opens, in place of the app's name.
        title = NightsCopy.TITLE
        val model = vm.model
        // No cue reaches the motor while a shift is running: the governor refuses a shift moment.
        val appHaptics = AppHaptics(haptics) { shiftStatus.snapshot.value.state.isRunning }
        setContent {
            HeadsDownTheme {
                CompositionLocalProvider(LocalHdHaptics provides appHaptics) {
                    val state by model.state.collectAsStateWithLifecycle()
                    NightsScreen(
                        state = state,
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

object NightsTags {
    /** The link on the home screen that opens this one. */
    const val HOME_LINK = "home-all-nights"
    const val MESSAGE = "nights-message"
    const val STREAK = "nights-streak"
    const val NIGHTS_OFF = "nights-off-left"
    const val LIFETIME_DARK = "nights-lifetime-dark"
    const val LIFETIME_DIGS = "nights-lifetime-digs"
    const val RIG_PROBLEM = "nights-rig-problem"
    const val STRIP = "nights-strip"
    const val LIST_PROBLEM = "nights-list-problem"
    const val EMPTY = "nights-empty"

    /** Followed by the shift's id: the row, and the link to the transaction that sealed it. */
    const val NIGHT = "nights-night-"
    const val TRANSACTION = "nights-transaction-"
    const val OLDER = "nights-older"
    const val RETRY = "nights-retry"
    const val CLOSE = "nights-close"
}

/**
 * The home screen's one way here: a text link under the last haul. It opens the night log with
 * an explicit intent and no extras.
 */
@Composable
fun AllNightsLink(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    TextAction(
        NightsCopy.LINK,
        onClick = { context.launch(Intent(context, NightsActivity::class.java)) },
        modifier = modifier.testTag(NightsTags.HOME_LINK),
    )
}

@Composable
fun NightsScreen(
    state: NightsState,
    onRetry: () -> Unit,
    onClose: () -> Unit,
    cluster: String = BuildConfig.SOLANA_CHAIN,
    onOpenTransaction: (url: String) -> Unit = {},
) {
    // One control closes the sheet: the bar in the dock, or the header's when the dock offers another try.
    val retry = state is NightsState.Unavailable || (state is NightsState.Ready && state.facts.incomplete)
    Sheet(
        confirmed = false,
        problem = (state as? NightsState.Unavailable)?.message,
        content = {
            SheetHeader(
                eyebrow = NightsCopy.EYEBROW,
                title = NightsCopy.TITLE,
                closeTag = NightsTags.CLOSE.takeIf { retry },
                onClose = onClose,
            )
            when (state) {
                NightsState.Loading -> {
                    SheetMessage(NightsCopy.LOADING, NightsTags.MESSAGE, quiet = true)
                    SkeletonLedger(rows = 2)
                }
                NightsState.NoRig -> SheetMessage(NightsCopy.NO_RIG, NightsTags.MESSAGE)
                is NightsState.Unavailable -> SheetNotice(state.message, NightsTags.MESSAGE, NoticeTone.Problem)
                is NightsState.Ready -> Ready(state.facts, cluster, onOpenTransaction)
            }
        },
        dock = {
            val bar = Modifier.fillMaxWidth()
            if (retry) {
                BarButton(SheetWords.RETRY, onRetry, bar.testTag(NightsTags.RETRY))
            } else {
                BarButton(SheetWords.CLOSE, onClose, bar.testTag(NightsTags.CLOSE), style = BarButtonStyle.Secondary)
            }
        },
    )
}

/** Top to bottom: the streak, the rig's counters over all its shifts, the last 28 days, then every finished shift. */
@Composable
private fun ColumnScope.Ready(facts: NightsFacts, cluster: String, onOpenTransaction: (url: String) -> Unit) {
    SheetRule()
    val rig = facts.rig
    if (rig != null) {
        StatBlock(NightsCopy.STREAK, NightsCopy.streak(rig.streak), Modifier.testTag(NightsTags.STREAK))
        SheetText(NightsCopy.nightsOff(rig.nightsOffLeft(facts.todayUtcDay)), Modifier.testTag(NightsTags.NIGHTS_OFF))
        SheetText(NightsCopy.STREAK_RULE, color = Hd.colors.ash)
        SheetRule()
        Label(NightsCopy.LIFETIME)
        StatRow(NightsCopy.ROUNDS_DARK, rig.lifetimeDarkRounds.toString(), Modifier.testTag(NightsTags.LIFETIME_DARK))
        StatRow(NightsCopy.DIGS, rig.lifetimeRoundsDug.toString(), Modifier.testTag(NightsTags.LIFETIME_DIGS))
        SheetText(NightsCopy.RIG_SOURCE, color = Hd.colors.ash)
    } else {
        facts.rigProblem?.let { Problem(it, NightsTags.RIG_PROBLEM, failed = it == NightsCopy.RIG_UNREADABLE) }
    }

    SheetRule()
    val days = facts.days
    if (days == null) {
        facts.shiftsProblem?.let { Problem(it, NightsTags.LIST_PROBLEM, failed = it == NightsCopy.LIST_UNREADABLE) }
    } else {
        Label(NightsCopy.LAST_DAYS)
        DayStrip(days)
        SheetText(NightsCopy.LEGEND, color = Hd.colors.ash)
        SheetText(NightsCopy.LIST_SOURCE, color = Hd.colors.ash)

        SheetRule()
        if (facts.listed.isEmpty()) {
            SheetText(NightsCopy.EMPTY, Modifier.testTag(NightsTags.EMPTY), color = Hd.colors.ash)
        } else {
            facts.listed.forEachIndexed { index, shift ->
                if (index > 0) SheetRule()
                Night(shift, facts.zone, cluster, onOpenTransaction)
            }
            if (facts.older > 0) SheetText(NightsCopy.older(facts.older), Modifier.testTag(NightsTags.OLDER), color = Hd.colors.ash)
        }
    }
}

/** A half that is missing: a notice when a read failed, a quiet sentence when there is nothing to read. */
@Composable
private fun Problem(text: String, tag: String, failed: Boolean) {
    if (failed) SheetNotice(text, tag, NoticeTone.Problem) else SheetText(text, Modifier.testTag(tag), color = Hd.colors.ash)
}

/**
 * The last 28 local days, the oldest at the start and today at the end. A day is told from its
 * shape, not from a colour: filled for a completed shift, an outline for one that ended early, a
 * line for no shift. To a screen reader the strip is one sentence with the three counts.
 */
@Composable
private fun DayStrip(days: List<DayMark>) {
    val colors = Hd.colors
    val inWords = NightsCopy.strip(days)
    Row(
        Modifier.fillMaxWidth().testTag(NightsTags.STRIP).semantics { contentDescription = inWords },
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (day in days) {
            val cell = Modifier.weight(1f).height(16.dp)
            when (day) {
                DayMark.COMPLETED -> Box(cell.background(colors.chalk, Hd.shapes.chip))
                DayMark.ENDED_EARLY -> Box(cell.border(HdDimens.Hairline, colors.ash, Hd.shapes.chip))
                DayMark.NO_SHIFT -> Box(cell, contentAlignment = Alignment.Center) {
                    Box(Modifier.fillMaxWidth().height(HdDimens.Hairline).background(colors.hairline))
                }
            }
        }
    }
}

/**
 * One finished shift: when it was sealed, how it ended, and its two counts, as one node to a
 * screen reader; under it the way to the transaction that sealed it, where a public explorer can
 * show it (not on a local validator, and not when the indexer sent no signature).
 */
@Composable
private fun Night(shift: IndexerShift, zone: ZoneId, cluster: String, onOpenTransaction: (url: String) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().testTag(NightsTags.NIGHT + shift.shiftId).semantics(mergeDescendants = true) {},
            verticalArrangement = Arrangement.spacedBy(HdDimens.Unit),
        ) {
            Label(NightsCopy.sealed(shift.endedAt, zone))
            SheetText(NightsCopy.howItEnded(shift.reason))
            SheetText(NightsCopy.counts(shift.darkRounds, shift.roundsDug), color = Hd.colors.ash)
        }
        ExplorerLinks.tx(shift.signature, cluster)?.let { url ->
            TextAction(
                SheetWords.SEE_TRANSACTION,
                onClick = { onOpenTransaction(url) },
                modifier = Modifier.testTag(NightsTags.TRANSACTION + shift.shiftId),
            )
        }
    }
}
