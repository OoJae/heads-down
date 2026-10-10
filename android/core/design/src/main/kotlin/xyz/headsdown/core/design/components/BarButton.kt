package xyz.headsdown.core.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import xyz.headsdown.core.design.Hd
import xyz.headsdown.core.design.HdColorScheme
import xyz.headsdown.core.design.HdDimens

enum class BarButtonStyle {
    /** The one thing to do: chalk fill, pit ink. Gold never fills a button. */
    Primary,

    /** Another thing that can be done: an ash outline, chalk ink. */
    Secondary,

    /** Something that cannot be taken back: ember fill, pit ink. The label says what it is. */
    Danger,
}

/**
 * A bar: 56dp at least, 6dp corners, the label centred. Write labels to fit three lines at the
 * largest font scale; a longer one makes the bar taller, it is never ellipsized or clipped.
 *
 * - Its semantics are a button whose text is exactly [label].
 * - It gives under the finger on touch-down (the `press` spring); with motion reduced it dims.
 * - [enabled] false: it cannot be used and looks it.
 * - [busy]: it cannot be used right now, and the label says why ("Waiting for your wallet…"). The
 *   label stays at full contrast; taps are ignored and a screen reader hears a disabled button.
 * - [seam]: a line of gold light under the bar, for the action a lit rig is waiting on. It goes
 *   out while the bar is disabled or busy. It is drawn 3dp below the bar's own bounds, so leave
 *   the 8dp rhythm under it.
 */
@Composable
fun BarButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: BarButtonStyle = BarButtonStyle.Primary,
    enabled: Boolean = true,
    seam: Boolean = false,
    busy: Boolean = false,
) {
    val colors = Hd.colors
    val shape = Hd.shapes.bar
    val live = enabled && !busy
    val look = barLook(style, enabled, busy, colors)
    val interaction = remember { MutableInteractionSource() }
    val press = rememberPressState(live)
    val seamLight = if (seam && live) colors.seamLight else Color.Unspecified

    Box(
        modifier
            .pressFeedback(press)
            .defaultMinSize(minWidth = HdDimens.Touch, minHeight = HdDimens.Bar)
            .drawBehind {
                if (seamLight != Color.Unspecified) {
                    // As in the mark: the line is narrower than the slab above it.
                    drawRect(
                        color = seamLight,
                        topLeft = Offset(size.width * 0.1f, size.height + 3.dp.toPx()),
                        size = Size(size.width * 0.8f, HdDimens.SeamLine.toPx()),
                    )
                }
            }
            .clip(shape)
            .background(look.fill)
            .then(if (look.outline != Color.Unspecified) Modifier.border(HdDimens.Hairline, look.outline, shape) else Modifier)
            .focusRing(interaction.isFocused(), look.ink)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = live,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 20.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = label,
            // No line limit and no ellipsis: the bar grows before a word of the label is lost.
            style = Hd.type.button.copy(color = look.ink, textAlign = TextAlign.Center),
        )
    }
}

private class BarLook(val fill: Color, val ink: Color, val outline: Color = Color.Unspecified)

private fun barLook(style: BarButtonStyle, enabled: Boolean, busy: Boolean, colors: HdColorScheme): BarLook = when {
    // Disabled wins over busy: a bar that cannot be used at all does not promise it soon will.
    !enabled -> BarLook(fill = Color.Transparent, ink = colors.disabled, outline = colors.hairline)
    // Busy looks the same in every style: a quiet plate whose label is still easy to read.
    busy -> BarLook(fill = colors.slab, ink = colors.chalk, outline = colors.ash)
    else -> when (style) {
        BarButtonStyle.Primary -> BarLook(fill = colors.chalk, ink = colors.onChalk)
        BarButtonStyle.Secondary -> BarLook(fill = Color.Transparent, ink = colors.chalk, outline = colors.ash)
        BarButtonStyle.Danger -> BarLook(fill = colors.ember, ink = colors.onAccent)
    }
}
