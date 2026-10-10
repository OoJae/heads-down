package xyz.headsdown.core.design.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import xyz.headsdown.core.design.Hd
import xyz.headsdown.core.design.HdDimens

/**
 * A row that goes somewhere: the [label] across the width, a chevron at the end, a hairline
 * under it. At least 48dp tall. A button to a screen reader, with [label] as its only text.
 */
@Composable
fun LinkRow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = Hd.colors
    val interaction = remember { MutableInteractionSource() }
    val ink = if (enabled) colors.chalk else colors.disabled
    val chevron = if (enabled) colors.ash else colors.disabled
    val hairline = colors.hairline
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val press = rememberPressState(enabled)
    Row(
        modifier
            .pressFeedback(press)
            .fillMaxWidth()
            .defaultMinSize(minHeight = HdDimens.Touch)
            .drawBehind {
                val h = HdDimens.Hairline.toPx()
                drawRect(hairline, Offset(0f, size.height - h), Size(size.width, h))
            }
            .focusRing(interaction.isFocused(), colors.chalk)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(label, Modifier.weight(1f), style = Hd.type.button.copy(color = ink))
        Canvas(Modifier.size(24.dp)) {
            // Glyph style: 2dp stroke on the 24dp grid, square caps, no fill.
            val unit = size.minDimension / 24f
            val from = if (rtl) 15f else 9f
            val to = if (rtl) 9f else 15f
            val path = Path().apply {
                moveTo(from * unit, 6f * unit)
                lineTo(to * unit, 12f * unit)
                lineTo(from * unit, 18f * unit)
            }
            drawPath(path, chevron, style = Stroke(2f * unit, cap = StrokeCap.Square, join = StrokeJoin.Miter))
        }
    }
}

/**
 * An action as a line of text: underlined, at least 48dp in both directions. A button to a screen
 * reader, with [label] as its only text. For what a screen offers besides its bars ("Close",
 * "Try again", "See this shift on-chain").
 *
 * The ink is chalk. Pass [color] only to mark ORE itself in gold.
 */
@Composable
fun TextAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = Hd.colors.chalk,
) {
    val interaction = remember { MutableInteractionSource() }
    val ink = if (enabled) color else Hd.colors.disabled
    val press = rememberPressState(enabled)
    Box(
        modifier
            .pressFeedback(press)
            .defaultMinSize(minWidth = HdDimens.Touch, minHeight = HdDimens.Touch)
            .focusRing(interaction.isFocused(), Hd.colors.chalk)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicText(label, style = Hd.type.button.copy(color = ink, textDecoration = TextDecoration.Underline))
    }
}
