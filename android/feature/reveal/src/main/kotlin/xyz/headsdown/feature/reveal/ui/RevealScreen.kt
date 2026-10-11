package xyz.headsdown.feature.reveal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import xyz.headsdown.core.design.BrandGlyph
import xyz.headsdown.core.design.Hd
import xyz.headsdown.core.design.HdArgb
import xyz.headsdown.core.design.HdDimens
import xyz.headsdown.core.design.components.ActionDock
import xyz.headsdown.core.design.components.BarButton
import xyz.headsdown.core.design.components.BarButtonStyle
import xyz.headsdown.core.design.components.DisplayText
import xyz.headsdown.core.design.components.Hero
import xyz.headsdown.core.design.components.HeroNumerals
import xyz.headsdown.core.design.components.HeroSpec
import xyz.headsdown.core.design.components.Label
import xyz.headsdown.core.design.components.LinkRow
import xyz.headsdown.core.design.components.Notice
import xyz.headsdown.core.design.components.NoticeTone
import xyz.headsdown.core.design.components.PinnedBarScreen
import xyz.headsdown.core.design.components.Plate
import xyz.headsdown.core.design.components.PlateTone
import xyz.headsdown.core.design.components.SkeletonBlock
import xyz.headsdown.core.design.components.SkeletonLine
import xyz.headsdown.core.design.components.SlabState
import xyz.headsdown.core.design.components.StatBlock
import xyz.headsdown.core.design.components.TextAction
import xyz.headsdown.feature.reveal.board.BoardReplay
import xyz.headsdown.feature.reveal.haul.FakeHaulRepository
import xyz.headsdown.feature.reveal.haul.HaulProvenance
import xyz.headsdown.feature.reveal.haul.HaulSummary
import xyz.headsdown.feature.reveal.haul.RevealCopy
import xyz.headsdown.feature.reveal.haul.RevealCopyBuilder
import xyz.headsdown.feature.reveal.haul.RevealStat
import java.time.ZoneId

object RevealTags {
    const val SAMPLE_BADGE = "reveal-sample-badge"
    const val BUY = "reveal-buy"
    const val BUY_STUB = "reveal-buy-stub"
    const val CLOCK_OUT = "reveal-clock-out"
    const val SHARE = "reveal-share"
    const val DONE = "reveal-done"
    const val VERDICT = "reveal-verdict"
    const val STREAK = "reveal-streak"
    const val EXPLORER = "reveal-explorer"
    const val SHOW_SAMPLE = "reveal-show-sample"
}

/** The sentences this screen writes itself (the haul's own are in `RevealCopy`). All pass `HonestCopy`. */
internal object RevealWords {
    /** Under the board: what its three kinds of light mean. */
    const val LEGEND = "Gold glow: where your rig dug. Flashes: each round's winning tile. Solid gold: your tile came up."

    /** While the haul is being read. Said once by a screen reader. */
    const val LOADING = "Reading last night's haul…"

    /** The featured line, on the plate that carries the amount of ORE. */
    const val POWERED_BY = "Powered by ORE"
}

/** What the reveal screen can be showing. */
sealed interface RevealUiState {
    data object Loading : RevealUiState
    data object NoHaul : RevealUiState
    data class Ready(val summary: HaulSummary) : RevealUiState
}

@Composable
fun RevealRoute(
    state: RevealUiState,
    zone: ZoneId,
    animate: Boolean,
    onReplayStarted: () -> Unit,
    onReplayFinished: (HaulSummary) -> Unit,
    onBuyRest: () -> Unit,
    onShare: (HaulSummary) -> Unit,
    onDone: () -> Unit,
    /** Opens an https explorer link (the haul's on-chain record). */
    onOpenExplorer: (String) -> Unit = {},
    /** Shows the labelled sample night when there is no real haul yet; null hides the offer. */
    onShowSample: (() -> Unit)? = null,
    /** Opens the clock-out screen; null hides the button. Never offered under a sample night. */
    onClockOut: (() -> Unit)? = null,
) = RevealTheme {
    when (state) {
        RevealUiState.Loading -> Loading(onDone)
        RevealUiState.NoHaul -> NoHaul(onDone, onShowSample)
        is RevealUiState.Ready -> RevealScreen(
            summary = state.summary,
            zone = zone,
            animate = animate,
            onReplayStarted = onReplayStarted,
            onReplayFinished = { onReplayFinished(state.summary) },
            onBuyRest = onBuyRest,
            onShare = { onShare(state.summary) },
            onDone = onDone,
            onOpenExplorer = onOpenExplorer,
            onClockOut = onClockOut,
        )
    }
}

/**
 * The morning haul reveal: the night replayed on the board, the ORE that came of it, the counts,
 * the effective price against market with an honest verdict, the "buy the rest at market" leg
 * (stubbed until the Jupiter leg lands), the streak and a spoiler-free share grid.
 *
 * A scrolling page over a dock. The dock holds the one thing to do next: "Clock out" for a haul
 * of the user's own (then "Done" is a text action at the top), "Done" for everything else.
 */
@Composable
fun RevealScreen(
    summary: HaulSummary,
    zone: ZoneId,
    animate: Boolean,
    onReplayStarted: () -> Unit = {},
    onReplayFinished: () -> Unit = {},
    onBuyRest: () -> Unit = {},
    onShare: () -> Unit = {},
    onDone: () -> Unit = {},
    onOpenExplorer: (String) -> Unit = {},
    onClockOut: (() -> Unit)? = null,
) {
    val copy = remember(summary, zone) { RevealCopyBuilder.build(summary, zone) }
    val replay = remember(summary) { BoardReplay(summary.rounds) }
    var buyTapped by rememberSaveable(summary.shiftId) { mutableStateOf(false) }
    // A sample night is nobody's shift: there is nothing of the user's to clock out of.
    val clockOut = onClockOut?.takeIf { summary.provenance != HaulProvenance.SAMPLE }
    // The amount of ORE is the page's one number; the other counts are a row under it.
    val (amounts, counts) = remember(copy) { copy.stats.partition { it.label == ORE_MINED } }

    PinnedBarScreen(
        contentPadding = PagePadding,
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(HdDimens.Unit)) {
                BrandRow(onDone = onDone.takeIf { clockOut != null })
                Title(copy)
            }
            Underside(
                replay = replay,
                animate = animate,
                modifier = Modifier.fillMaxWidth().widthIn(max = 420.dp).align(Alignment.CenterHorizontally),
                onStarted = onReplayStarted,
                onFinished = onReplayFinished,
            )
            amounts.forEach { OreMined(it, lit = summary.oreMinedAtoms > 0) }
            Counts(counts)
            Price(copy)
            copy.nearMiss?.let { Sentence(it, muted = false) }
            copy.firstPickup?.let { BasicText(it, style = Hd.type.figure.copy(color = Hd.colors.ash)) }

            if (copy.buyButton != null) {
                Rule()
                BuyTheRest(copy, tapped = buyTapped) {
                    buyTapped = true
                    onBuyRest()
                }
            }

            Rule()
            DisplayText(copy.streak, Modifier.testTag(RevealTags.STREAK), style = Hd.type.title)
            BarButton(
                copy.shareButton,
                onClick = onShare,
                modifier = Modifier.fillMaxWidth().testTag(RevealTags.SHARE),
                style = BarButtonStyle.Secondary,
            )

            Rule()
            Sentence(copy.solPlacedNote)
            Sentence(copy.footnote)
            val explorer = summary.explorerUrl
            if (explorer != null) {
                LinkRow("See this shift on-chain", onClick = { onOpenExplorer(explorer) }, modifier = Modifier.testTag(RevealTags.EXPLORER))
            }
            if (clockOut != null) {
                // What the bar in the dock does, said last: on the page it sits right above that
                // bar, and a screen reader says it right before "Clock out". (The link row ends
                // in a hairline of its own.)
                if (explorer == null) Rule()
                Sentence(RevealCopyBuilder.CLOCK_OUT_DETAIL)
            }
        },
        dock = {
            ActionDock {
                if (clockOut != null) {
                    BarButton(
                        RevealCopyBuilder.CLOCK_OUT_BUTTON,
                        onClick = clockOut,
                        modifier = Modifier.fillMaxWidth().testTag(RevealTags.CLOCK_OUT),
                    )
                } else {
                    DoneBar(onDone)
                }
            }
        },
    )
}

private const val ORE_MINED = "ORE mined"
private const val DONE = "Done"

/** The share of the hero size (112sp) the ORE figure may reach: 80dp, in every night and at every font scale. */
private const val ORE_FIGURE_SHARE = 80f / 112f

/** The page's own margins: the brand row is 48dp tall (it may hold "Done"), so less is needed above it. */
private val PagePadding = PaddingValues(start = HdDimens.Margin, end = HdDimens.Margin, top = 4.dp, bottom = 24.dp)

/** The mark and the name; with [onDone], the way out at the far end (when the dock is taken by "Clock out"). */
@Composable
private fun BrandRow(onDone: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = HdDimens.Touch),
        horizontalArrangement = Arrangement.spacedBy(HdDimens.Rhythm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BrandGlyph(Modifier.size(24.dp))
        Label("HEADS DOWN", Modifier.weight(1f))
        if (onDone != null) DoneAction(onDone)
    }
}

/**
 * "Done" as a text action whose word ends on the page's margin. A text action is at least 48dp
 * wide and starts its label at its leading edge, so a short word would stop short of the margin:
 * it is moved over by what is left, and its touch target reaches into the margin instead.
 */
@Composable
private fun DoneAction(onDone: () -> Unit) {
    val style = Hd.type.button
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val slack = remember(style, density, measurer) {
        val word = measurer.measure(DONE, style, softWrap = false, maxLines = 1, density = density).size.width
        with(density) { (HdDimens.Touch - word.toDp()).coerceAtLeast(0.dp) }
    }
    TextAction(DONE, onClick = onDone, modifier = Modifier.offset(x = slack).testTag(RevealTags.DONE))
}

/** The dock's bar when there is nothing to clock out of. */
@Composable
private fun DoneBar(onDone: () -> Unit) {
    BarButton(DONE, onClick = onDone, modifier = Modifier.fillMaxWidth().testTag(RevealTags.DONE))
}

@Composable
private fun Title(copy: RevealCopy) {
    Column(verticalArrangement = Arrangement.spacedBy(HdDimens.Unit)) {
        DisplayText(copy.title, Modifier.semantics { heading() }, style = Hd.type.title)
        BasicText(copy.subtitle, style = Hd.type.figure.copy(color = Hd.colors.ash))
        copy.sampleBadge?.let { SampleBadge(it) }
    }
}

/** Not the user's own night: said in words, in ember, right under the title and above everything the night shows. */
@Composable
private fun SampleBadge(text: String) {
    val colors = Hd.colors
    Label(
        text,
        Modifier
            .padding(top = HdDimens.Rhythm)
            .drawBehind { drawRect(colors.ember, Offset.Zero, Size(3.dp.toPx(), size.height)) }
            .padding(start = 11.dp)
            .testTag(RevealTags.SAMPLE_BADGE),
        color = colors.ember,
    )
}

/**
 * The board, framed as the underside of the slab: a slab plate with the 5x5 board in it and, under
 * the board, what its light means. The frame is turned over once as it enters.
 */
@Composable
private fun Underside(
    replay: BoardReplay,
    animate: Boolean,
    modifier: Modifier,
    onStarted: () -> Unit,
    onFinished: () -> Unit,
) {
    val turn = rememberTurnOver(replay, animate)
    Plate(
        modifier.turnedOver(turn),
        tone = PlateTone.Slab,
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RevealBoard(replay, animate, Modifier.fillMaxWidth(), onStarted, onFinished)
        BasicText(RevealWords.LEGEND, style = Hd.type.caption.copy(color = Hd.colors.ash))
    }
}

/**
 * What the night brought: the one number on the page, in gold because it is an amount of ORE,
 * on a slab plate that also carries the featured line. One node to a screen reader.
 *
 * [lit]: ORE was mined, so the slab has its line of light under it. A night that mined nothing
 * has none.
 */
@Composable
private fun OreMined(stat: RevealStat, lit: Boolean) {
    val colors = Hd.colors
    // On the pale pit of the light palette the gold light cannot be seen: there the line is gold ink.
    val seam = if (colors.isDark) colors.seamLight else colors.seam
    Plate(
        Modifier
            .fillMaxWidth()
            .drawBehind {
                if (lit) {
                    // As in the mark: the line is narrower than the slab above it.
                    drawRect(seam, Offset(size.width * 0.1f, size.height + 3.dp.toPx()), Size(size.width * 0.8f, HdDimens.SeamLine.toPx()))
                }
            }
            .semantics(mergeDescendants = true) {},
        tone = PlateTone.Slab,
    ) {
        // The featured line sits at the far end of the label's row while both fit, and under the
        // amount above font scale 1.3. It is composed after the amount either way, so a screen
        // reader says "ORE mined, 0.0058 ORE, Powered by ORE".
        val oneRow = LocalDensity.current.fontScale <= 1.3f
        Box(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(HdDimens.Unit)) {
                Label(stat.label)
                OreFigure(stat.value)
                if (!oneRow) Label(RevealWords.POWERED_BY, color = colors.seam)
            }
            if (oneRow) Label(RevealWords.POWERED_BY, Modifier.align(Alignment.TopEnd), color = colors.seam)
        }
    }
}

/**
 * The amount as hero numerals, drawn between 40dp and 80dp whatever the system font scale: its
 * own font scale is fixed. Two reasons. A night that mined nothing ("0 ORE") must not be drawn
 * larger than a night that mined something, which the full hero size would do. And left to grow
 * with the system font, the smallest size is 73dp at scale 2.0, where an amount with its unit no
 * longer fits the plate and would end in an ellipsis; an amount is never cut. At its smallest,
 * 40dp, it is still larger than body text at font scale 2.0 (28dp).
 */
@Composable
private fun OreFigure(value: String) {
    val device = LocalDensity.current
    val figure = remember(device) { Density(device.density, fontScale = ORE_FIGURE_SHARE) }
    CompositionLocalProvider(LocalDensity provides figure) {
        HeroNumerals(value, color = Hd.colors.seam)
    }
}

/**
 * The other counts of the night, side by side while they fit and one under another when they do
 * not (always above font scale 1.5).
 */
@Composable
private fun Counts(stats: List<RevealStat>) {
    if (LocalDensity.current.fontScale > 1.5f) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            stats.forEach { StatBlock(it.label, it.value) }
        }
    } else {
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            stats.forEach { StatBlock(it.label, it.value) }
        }
    }
}

/** What an ORE cost by mining, what it costs at market, and which route was cheaper, said plainly. */
@Composable
private fun Price(copy: RevealCopy) {
    Plate(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(HdDimens.Unit)) {
            Label("Effective price per ORE")
            DisplayText(copy.effectivePrice ?: "—", style = Hd.type.title)
            copy.marketPrice?.let { BasicText(it, style = Hd.type.figure.copy(color = Hd.colors.ash)) }
        }
        BasicText(
            copy.verdict,
            Modifier.testTag(RevealTags.VERDICT),
            style = Hd.type.body.copy(color = Hd.colors.chalk),
        )
    }
}

/**
 * The market leg: a bar, what it would cost, and, once tapped, the notice that nothing was bought
 * (a polite live region, scrolled into view because it appears under the finger).
 */
@Composable
private fun BuyTheRest(copy: RevealCopy, tapped: Boolean, onBuy: () -> Unit) {
    val label = copy.buyButton ?: return
    val stub = remember { BringIntoViewRequester() }
    // Counted here and not saved: the page follows the notice when the bar is tapped, every time,
    // and not when the screen comes back from a rotation with the notice already on it.
    var taps by remember { mutableIntStateOf(0) }
    LaunchedEffect(taps) { if (taps > 0) stub.bringIntoView() }
    Column(verticalArrangement = Arrangement.spacedBy(HdDimens.Rhythm)) {
        BarButton(
            label,
            onClick = {
                taps++
                onBuy()
            },
            modifier = Modifier.fillMaxWidth().testTag(RevealTags.BUY),
            style = BarButtonStyle.Secondary,
        )
        copy.buyDetail?.let { Sentence(it) }
        if (tapped) {
            Notice(
                copy.buyStubMessage,
                Modifier.bringIntoViewRequester(stub).testTag(RevealTags.BUY_STUB),
                tone = NoticeTone.Problem,
            )
        }
    }
}

/** A hairline across the page, between its groups. Decoration. */
@Composable
private fun Rule() {
    Box(Modifier.fillMaxWidth().height(HdDimens.Hairline).background(Hd.colors.hairline))
}

/** A sentence of the page: body text, muted unless it is news. */
@Composable
private fun Sentence(text: String, muted: Boolean = true) {
    BasicText(text, style = Hd.type.body.copy(color = if (muted) Hd.colors.ash else Hd.colors.chalk))
}

/**
 * The haul is being read. A still outline of the page that is coming, piece for piece and in the
 * same places, so that the board does not move when the night arrives: nothing turns and nothing
 * shimmers. The one sentence stands where the night's hours will, and is what a screen reader says.
 */
@Composable
private fun Loading(onDone: () -> Unit) {
    PinnedBarScreen(
        contentPadding = PagePadding,
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(HdDimens.Unit)) {
                BrandRow(onDone = null)
                // The title's line.
                Box(Modifier.height(36.dp), contentAlignment = Alignment.CenterStart) {
                    SkeletonLine(widthFraction = 0.46f, height = 26.dp)
                }
                BasicText(
                    RevealWords.LOADING,
                    Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    style = Hd.type.figure.copy(color = Hd.colors.ash),
                )
            }
            // The board's frame, with the three lines under the board as two.
            Plate(
                Modifier.fillMaxWidth().widthIn(max = 420.dp).align(Alignment.CenterHorizontally),
                tone = PlateTone.Slab,
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                EmptyBoard(Modifier.fillMaxWidth())
                Column(Modifier.height(48.dp), verticalArrangement = Arrangement.SpaceEvenly) {
                    SkeletonLine(widthFraction = 0.92f, height = 8.dp)
                    SkeletonLine(widthFraction = 0.58f, height = 8.dp)
                }
            }
            // The amount's plate: its label and its numerals.
            Plate(Modifier.fillMaxWidth(), tone = PlateTone.Slab, verticalArrangement = Arrangement.spacedBy(HdDimens.Unit)) {
                Box(Modifier.height(18.dp), contentAlignment = Alignment.CenterStart) {
                    SkeletonLine(widthFraction = 0.28f, height = 10.dp)
                }
                Box(Modifier.height(86.dp), contentAlignment = Alignment.CenterStart) {
                    SkeletonBlock(Modifier.fillMaxWidth(0.82f), height = 58.dp)
                }
            }
            // The three counts.
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                repeat(3) { SkeletonBlock(Modifier.weight(1f), height = 56.dp) }
            }
        },
        dock = { ActionDock { DoneBar(onDone) } },
    )
}

/** No finished shift, no indexer or no network: there is nothing to reveal, and the page says so. */
@Composable
private fun NoHaul(onDone: () -> Unit, onShowSample: (() -> Unit)?) {
    val cold = remember { HeroSpec(SlabState.Cold, heat = 0) }
    PinnedBarScreen(
        contentPadding = PagePadding,
        content = {
            BrandRow(onDone = null)
            Hero(cold, Modifier.height(112.dp))
            DisplayText("No haul yet", Modifier.semantics { heading() })
            Sentence("Your first haul appears after your first mined shift.")
            if (onShowSample != null) {
                BarButton(
                    "See a sample night (not your data)",
                    onClick = onShowSample,
                    modifier = Modifier.fillMaxWidth().testTag(RevealTags.SHOW_SAMPLE),
                    style = BarButtonStyle.Secondary,
                )
            }
        },
        dock = { ActionDock { DoneBar(onDone) } },
    )
}

private val PREVIEW_NOW = 1_790_000_000_000L
private val PREVIEW_ZONE: ZoneId = ZoneId.of("Africa/Lagos")

@Preview(name = "Mined night", widthDp = 360, heightDp = 800, backgroundColor = HdArgb.PREVIEW_PIT, showBackground = true)
@Composable
private fun RevealMinedPreview() = RevealTheme {
    RevealScreen(FakeHaulRepository.sampleNight(PREVIEW_NOW, PREVIEW_ZONE), PREVIEW_ZONE, animate = false)
}

@Preview(name = "Gate closed", widthDp = 360, heightDp = 800, backgroundColor = HdArgb.PREVIEW_PIT, showBackground = true)
@Composable
private fun RevealGateClosedPreview() = RevealTheme {
    RevealScreen(FakeHaulRepository.sampleGateClosed(PREVIEW_NOW, PREVIEW_ZONE), PREVIEW_ZONE, animate = false)
}

@Preview(name = "Your haul, with clock out", widthDp = 360, heightDp = 800, backgroundColor = HdArgb.PREVIEW_PIT, showBackground = true)
@Composable
private fun RevealOnChainPreview() = RevealTheme {
    RevealScreen(
        FakeHaulRepository.sampleNight(PREVIEW_NOW, PREVIEW_ZONE).copy(provenance = HaulProvenance.ON_CHAIN),
        PREVIEW_ZONE,
        animate = false,
        onClockOut = {},
    )
}

@Preview(name = "Reading the haul", widthDp = 360, heightDp = 800, backgroundColor = HdArgb.PREVIEW_PIT, showBackground = true)
@Composable
private fun RevealLoadingPreview() = RevealTheme { Loading(onDone = {}) }

@Preview(name = "No haul yet", widthDp = 360, heightDp = 800, backgroundColor = HdArgb.PREVIEW_PIT, showBackground = true)
@Composable
private fun RevealNoHaulPreview() = RevealTheme { NoHaul(onDone = {}, onShowSample = {}) }
