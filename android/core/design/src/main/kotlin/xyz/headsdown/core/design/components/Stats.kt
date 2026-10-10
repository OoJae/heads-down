package xyz.headsdown.core.design.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import xyz.headsdown.core.design.Hd

/**
 * A fact as a block: its [label] in mono capitals, its [value] under it in the display face.
 * One node to a screen reader ("Rounds dark, 55").
 */
@Composable
fun StatBlock(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = Hd.colors.chalk,
) {
    Column(modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Label(label)
        DisplayText(value, style = Hd.type.title, color = valueColor, caps = false)
    }
}

/**
 * A fact as a row: its [label] on the left in body text, its [value] on the right in mono.
 * One node to a screen reader.
 *
 * It is a [LedgerRow] without a note, and lays out the same way: a short value keeps its width
 * and a long label wraps beside it; a value too long to share the line (an address) drops under
 * the label instead of squeezing it to nothing.
 */
@Composable
fun StatRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = Hd.colors.chalk,
) {
    LedgerRow(label, modifier, figure = value, figureColor = valueColor)
}

/**
 * A line of a ledger: what the amount is for, and the amount.
 *
 * The [figure] is never truncated and never squeezed: while it takes no more than 60% of the row
 * it sits on the right at its full width and the [label] wraps beside it; a longer one drops to a
 * line of its own under the label, where it has the whole width (and wraps by character if even
 * that is too little). [note] goes under both, for the sentence that explains the amount.
 *
 * Label and figure are one node to a screen reader.
 */
@Composable
fun LedgerRow(
    label: String,
    modifier: Modifier = Modifier,
    figure: String? = null,
    figureColor: Color = Hd.colors.chalk,
    note: (@Composable () -> Unit)? = null,
) {
    val type = Hd.type
    val ash = Hd.colors.ash
    Layout(
        content = {
            BasicText(label, Modifier.layoutId(LedgerSlot.Label), style = type.body.copy(color = ash))
            if (figure != null) {
                BasicText(figure, Modifier.layoutId(LedgerSlot.Figure), style = type.figure.copy(color = figureColor))
            }
            if (note != null) Box(Modifier.layoutId(LedgerSlot.Note)) { note() }
        },
        modifier = modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
    ) { measurables, constraints ->
        val gap = 16.dp.roundToPx()
        val noteGap = 4.dp.roundToPx()
        val labelMeasurable = measurables.first { it.layoutId == LedgerSlot.Label }
        val figureMeasurable = measurables.firstOrNull { it.layoutId == LedgerSlot.Figure }
        val noteMeasurable = measurables.firstOrNull { it.layoutId == LedgerSlot.Note }

        val bounded = constraints.hasBoundedWidth
        val width = if (bounded) constraints.maxWidth else Constraints.Infinity
        val loose = Constraints(maxWidth = width)

        val figureWidth = figureMeasurable?.maxIntrinsicWidth(Constraints.Infinity) ?: 0
        val sideBySide = figureMeasurable == null || !bounded || figureWidth + gap <= width * SIDE_BY_SIDE_SHARE

        val label: Placeable
        val figurePlaceable: Placeable?
        if (sideBySide) {
            figurePlaceable = figureMeasurable?.measure(Constraints())
            val taken = figurePlaceable?.let { it.width + gap } ?: 0
            label = labelMeasurable.measure(if (bounded) Constraints(maxWidth = (width - taken).coerceAtLeast(0)) else loose)
        } else {
            label = labelMeasurable.measure(loose)
            figurePlaceable = figureMeasurable?.measure(loose)
        }
        val notePlaceable = noteMeasurable?.measure(loose)

        // Side by side the two share a baseline; a font without one (a test) falls back to the top.
        val labelBase = label[FirstBaseline].takeIf { it >= 0 } ?: 0
        val figureBase = figurePlaceable?.get(FirstBaseline)?.takeIf { it >= 0 } ?: 0
        val base = maxOf(labelBase, figureBase)
        val labelY = if (sideBySide) base - labelBase else 0
        val figureY = if (sideBySide) base - figureBase else label.height
        val rowHeight = maxOf(labelY + label.height, figureY + (figurePlaceable?.height ?: 0))
        val noteY = rowHeight + if (notePlaceable != null) noteGap else 0
        val height = noteY + (notePlaceable?.height ?: 0)
        val layoutWidth = if (bounded) width else maxOf(label.width + (figurePlaceable?.let { it.width + gap } ?: 0), notePlaceable?.width ?: 0)

        layout(layoutWidth, height.coerceIn(constraints.minHeight, constraints.maxHeight)) {
            label.placeRelative(0, labelY)
            figurePlaceable?.placeRelative(if (sideBySide) layoutWidth - figurePlaceable.width else 0, figureY)
            notePlaceable?.placeRelative(0, noteY)
        }
    }
}

private enum class LedgerSlot { Label, Figure, Note }

/** The most of a row an amount may take and still share the line with its label. */
private const val SIDE_BY_SIDE_SHARE = 0.6f
