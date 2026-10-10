package xyz.headsdown.feature.reveal.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import xyz.headsdown.core.design.BrandGlyph
import xyz.headsdown.core.design.HdArgb
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
    Box(Modifier.fillMaxSize().background(RevealColors.Charcoal)) {
        when (state) {
            RevealUiState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = RevealColors.OreGold)
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
}

/**
 * The morning haul reveal: the night replayed on the board, the counts, the effective price
 * against market with an honest verdict, the "buy the rest at market" leg (stubbed until the
 * Jupiter leg lands), the streak and a spoiler-free share grid.
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

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Header(copy)
        RevealBoard(
            replay = replay,
            animate = animate,
            modifier = Modifier.fillMaxWidth().widthIn(max = 420.dp).align(Alignment.CenterHorizontally),
            onStarted = onReplayStarted,
            onFinished = onReplayFinished,
        )
        Text(
            "Ember: where your rig dug. Flashes: each round's winning tile. Gold: your tile came up.",
            color = RevealColors.AshMuted,
            style = MaterialTheme.typography.bodySmall,
        )
        StatsGrid(copy.stats)
        PriceCard(copy)
        copy.nearMiss?.let { Text(it, color = RevealColors.Cooling, style = MaterialTheme.typography.bodyMedium) }
        copy.firstPickup?.let { Text(it, color = RevealColors.AshMuted, style = MaterialTheme.typography.bodyMedium) }

        if (copy.buyButton != null) {
            Button(
                onClick = {
                    buyTapped = true
                    onBuyRest()
                },
                modifier = Modifier.fillMaxWidth().testTag(RevealTags.BUY),
                colors = ButtonDefaults.buttonColors(containerColor = RevealColors.OreGold, contentColor = RevealColors.Charcoal),
            ) { Text(copy.buyButton) }
            copy.buyDetail?.let { Text(it, color = RevealColors.AshMuted, style = MaterialTheme.typography.bodySmall) }
            if (buyTapped) {
                Text(
                    copy.buyStubMessage,
                    color = RevealColors.Cooling,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag(RevealTags.BUY_STUB),
                )
            }
        }

        Text(
            copy.streak,
            color = RevealColors.Ember,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.testTag(RevealTags.STREAK),
        )

        // A sample night is nobody's shift: there is nothing of the user's to clock out of.
        if (onClockOut != null && summary.provenance != HaulProvenance.SAMPLE) {
            Button(
                onClick = onClockOut,
                modifier = Modifier.fillMaxWidth().testTag(RevealTags.CLOCK_OUT),
                colors = ButtonDefaults.buttonColors(containerColor = RevealColors.Ember, contentColor = RevealColors.Charcoal),
            ) { Text(RevealCopyBuilder.CLOCK_OUT_BUTTON) }
            Text(RevealCopyBuilder.CLOCK_OUT_DETAIL, color = RevealColors.AshMuted, style = MaterialTheme.typography.bodySmall)
        }

        OutlinedButton(
            onClick = onShare,
            modifier = Modifier.fillMaxWidth().testTag(RevealTags.SHARE),
            border = BorderStroke(1.dp, RevealColors.CharcoalOutline),
        ) { Text(copy.shareButton, color = RevealColors.Ash) }

        Text(copy.solPlacedNote, color = RevealColors.AshMuted, style = MaterialTheme.typography.bodySmall)
        Text(copy.footnote, color = RevealColors.AshMuted, style = MaterialTheme.typography.bodySmall)
        summary.explorerUrl?.let { url ->
            TextButton(onClick = { onOpenExplorer(url) }, modifier = Modifier.fillMaxWidth().testTag(RevealTags.EXPLORER)) {
                Text("See this shift on-chain", color = RevealColors.Ash)
            }
        }

        TextButton(onClick = onDone, modifier = Modifier.fillMaxWidth().testTag(RevealTags.DONE)) {
            Text("Done", color = RevealColors.Ash)
        }
    }
}

@Composable
private fun Header(copy: RevealCopy) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // The mark, at the width the three heat pixels had here.
            BrandGlyph(Modifier.size(22.dp))
            Spacer(Modifier.size(8.dp))
            Text("HEADS DOWN", style = PixelLabel, color = RevealColors.AshMuted)
        }
        Text(
            copy.title,
            style = MaterialTheme.typography.displaySmall,
            color = RevealColors.OreGold,
            modifier = Modifier.semantics { heading() },
        )
        Text(copy.subtitle, color = RevealColors.AshMuted, style = MaterialTheme.typography.bodyMedium)
        copy.sampleBadge?.let {
            Text(
                it,
                style = PixelLabel.copy(fontWeight = FontWeight.Bold),
                color = RevealColors.Charcoal,
                modifier = Modifier
                    .padding(top = 6.dp)
                    .background(RevealColors.Cooling, RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .testTag(RevealTags.SAMPLE_BADGE),
            )
        }
    }
}

@Composable
private fun StatsGrid(stats: List<RevealStat>) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        stats.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { stat -> StatTile(stat, Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun StatTile(stat: RevealStat, modifier: Modifier) {
    Card(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = RevealColors.CharcoalRaised),
        border = BorderStroke(1.dp, RevealColors.CharcoalOutline),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(stat.label, color = RevealColors.AshMuted, style = MaterialTheme.typography.bodySmall)
            Text(
                stat.value,
                color = if (stat.label == "ORE mined") RevealColors.OreGold else RevealColors.Ash,
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

@Composable
private fun PriceCard(copy: RevealCopy) {
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = RevealColors.CharcoalRaised),
        border = BorderStroke(1.dp, RevealColors.OreGold.copy(alpha = 0.5f)),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Effective price per ORE", color = RevealColors.AshMuted, style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(copy.effectivePrice ?: "—", color = RevealColors.OreGold, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.size(12.dp))
                copy.marketPrice?.let { Text(it, color = RevealColors.Ash, style = MaterialTheme.typography.bodyMedium) }
            }
            Text(
                copy.verdict,
                color = RevealColors.Ash,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag(RevealTags.VERDICT),
            )
        }
    }
}

@Composable
private fun NoHaul(onDone: () -> Unit, onShowSample: (() -> Unit)?) {
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("No haul yet", style = MaterialTheme.typography.headlineSmall, color = RevealColors.OreGold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Your first haul appears after your first mined shift.",
            color = RevealColors.AshMuted,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        if (onShowSample != null) {
            TextButton(onClick = onShowSample, modifier = Modifier.testTag(RevealTags.SHOW_SAMPLE)) {
                Text("See a sample night (not your data)", color = RevealColors.OreGold)
            }
        }
        TextButton(onClick = onDone) { Text("Done", color = RevealColors.Ash) }
    }
}

private val PREVIEW_NOW = 1_790_000_000_000L
private val PREVIEW_ZONE: ZoneId = ZoneId.of("Africa/Lagos")

@Preview(name = "Mined night", widthDp = 400, heightDp = 1400, backgroundColor = HdArgb.PREVIEW_PIT, showBackground = true)
@Composable
private fun RevealMinedPreview() = RevealTheme {
    RevealScreen(FakeHaulRepository.sampleNight(PREVIEW_NOW, PREVIEW_ZONE), PREVIEW_ZONE, animate = false)
}

@Preview(name = "Gate closed", widthDp = 400, heightDp = 1400, backgroundColor = HdArgb.PREVIEW_PIT, showBackground = true)
@Composable
private fun RevealGateClosedPreview() = RevealTheme {
    RevealScreen(FakeHaulRepository.sampleGateClosed(PREVIEW_NOW, PREVIEW_ZONE), PREVIEW_ZONE, animate = false)
}
