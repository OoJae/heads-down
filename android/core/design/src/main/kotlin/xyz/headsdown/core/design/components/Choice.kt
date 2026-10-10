package xyz.headsdown.core.design.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import xyz.headsdown.core.design.Hd
import xyz.headsdown.core.design.HdDimens

/**
 * One option. With [role] `RadioButton` it is one of a few that exclude each other; with
 * `Checkbox` it is on or off by itself. Built on `Modifier.selectable`, so a screen reader and a
 * test read Selected and Disabled from it, and its only text is [label].
 *
 * The choice is shown by shape as well as tone: a filled square with a mark in it (a tick for a
 * checkbox, a smaller square for a radio), a heavier outline around the row.
 *
 * [compact] draws a 48dp chip for a row of short options ("Off", "10 SKR"): filled when chosen,
 * outlined when not, the label on one line.
 *
 * [onClick] is called on every tap, including a tap on the option already chosen: the caller
 * decides whether that takes the choice back.
 */
@Composable
fun Choice(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    role: Role = Role.RadioButton,
    compact: Boolean = false,
) {
    val colors = Hd.colors
    val interaction = remember { MutableInteractionSource() }
    val press = rememberPressState(enabled)
    val focused = interaction.isFocused()
    val ink = when {
        !enabled && !(compact && selected) -> colors.disabled
        compact && selected -> colors.onChalk
        else -> colors.chalk
    }
    val pick = Modifier.selectable(
        selected = selected,
        interactionSource = interaction,
        indication = null,
        enabled = enabled,
        role = role,
        onClick = onClick,
    )

    if (compact) {
        val shape = Hd.shapes.chip
        val fill = when {
            !selected -> Color.Transparent
            enabled -> colors.chalk
            else -> colors.disabled
        }
        Row(
            modifier
                .pressFeedback(press)
                .defaultMinSize(minWidth = HdDimens.Touch, minHeight = HdDimens.Touch)
                .clip(shape)
                .background(fill)
                .then(if (selected) Modifier else Modifier.border(HdDimens.Hairline, if (enabled) colors.ash else colors.hairline, shape))
                .focusRing(focused, ink)
                .then(pick)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(label, style = Hd.type.button.copy(color = ink), maxLines = 1, softWrap = false)
        }
        return
    }

    val shape = Hd.shapes.plate
    val outline = when {
        !enabled -> colors.hairline
        selected -> colors.chalk
        else -> colors.ash
    }
    Row(
        modifier
            .pressFeedback(press)
            .fillMaxWidth()
            .defaultMinSize(minHeight = HdDimens.Bar)
            .clip(shape)
            .border(if (selected) 2.dp else HdDimens.Hairline, outline, shape)
            .focusRing(focused, colors.chalk)
            .then(pick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val mark = if (enabled) colors.chalk else colors.disabled
        val markInk = colors.onChalk
        val box = if (enabled) colors.ash else colors.hairline
        val checkbox = role == Role.Checkbox
        Canvas(Modifier.size(20.dp)) { drawChoiceMark(selected, checkbox, box, mark, markInk) }
        BasicText(label, Modifier.weight(1f), style = Hd.type.button.copy(color = ink))
    }
}

/** A 20dp square: empty with an outline, or filled with a tick (checkbox) or an inner square (radio). */
private fun DrawScope.drawChoiceMark(selected: Boolean, checkbox: Boolean, box: Color, fill: Color, ink: Color) {
    val radius = CornerRadius(2.dp.toPx())
    if (!selected) {
        val stroke = 2.dp.toPx()
        drawRoundRect(box, Offset(stroke / 2, stroke / 2), Size(size.width - stroke, size.height - stroke), radius, Stroke(stroke))
        return
    }
    drawRoundRect(fill, Offset.Zero, size, radius)
    if (checkbox) {
        drawTick(ink)
    } else {
        val inner = size.width * 0.4f
        drawRect(ink, Offset((size.width - inner) / 2, (size.height - inner) / 2), Size(inner, inner))
    }
}

/**
 * A tick in the glyph style (2dp stroke at 24dp, square caps, no fill), fitted to the canvas.
 * Drawn, not typed: the display face has no check mark.
 */
internal fun DrawScope.drawTick(color: Color) {
    val unit = size.minDimension / 24f
    val path = Path().apply {
        moveTo(6f * unit, 12.5f * unit)
        lineTo(10.5f * unit, 17f * unit)
        lineTo(18f * unit, 7.5f * unit)
    }
    drawPath(path, color, style = Stroke(width = 2.5f * unit, cap = StrokeCap.Square, join = StrokeJoin.Miter))
}
