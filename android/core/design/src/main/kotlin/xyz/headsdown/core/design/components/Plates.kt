package xyz.headsdown.core.design.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import xyz.headsdown.core.design.Hd
import xyz.headsdown.core.design.HdDimens

enum class PlateTone {
    /** On the pit: an outline and nothing else. */
    Flat,

    /** Raised: the slab colour. It rises by lightness; nothing here has a shadow. */
    Slab,
}

/**
 * A plate: 6dp corners, a hairline around it, no shadow. Its content is a column on the 8dp
 * rhythm, 16dp in from the edge. With [onClick] the whole plate is one button and gives under the
 * finger.
 */
@Composable
fun Plate(
    modifier: Modifier = Modifier,
    tone: PlateTone = PlateTone.Flat,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(HdDimens.Rhythm),
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = Hd.colors
    val shape = Hd.shapes.plate
    val interaction = remember { MutableInteractionSource() }
    val press = rememberPressState(enabled = onClick != null)
    val click = if (onClick == null) {
        Modifier
    } else {
        Modifier
            .focusRing(interaction.isFocused(), colors.chalk)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
    }
    Column(
        modifier
            .pressFeedback(press)
            .clip(shape)
            .background(if (tone == PlateTone.Slab) colors.slab else Color.Transparent)
            .border(HdDimens.Hairline, colors.hairline, shape)
            .then(click)
            .padding(contentPadding),
        verticalArrangement = verticalArrangement,
        content = content,
    )
}

enum class NoticeTone {
    /** Something to know. An ash rule. */
    Info,

    /** Something went wrong, or will. An ember rule and a mark: ember never speaks alone. */
    Problem,
}

/**
 * A notice: a rule down its left edge, the [text], and under it an optional [action] (a
 * `TextAction`). It is a polite live region: when it appears or its text changes, a screen reader
 * says it once the current sentence is over.
 */
@Composable
fun Notice(
    text: String,
    modifier: Modifier = Modifier,
    tone: NoticeTone = NoticeTone.Info,
    action: (@Composable () -> Unit)? = null,
) {
    val colors = Hd.colors
    val rule = if (tone == NoticeTone.Problem) colors.ember else colors.ash
    Row(
        modifier
            .fillMaxWidth()
            .clip(Hd.shapes.plate)
            .background(colors.slab)
            .drawBehind { drawRect(rule, Offset.Zero, Size(NOTICE_RULE.toPx(), size.height)) }
            .padding(start = NOTICE_RULE + 12.dp, top = 12.dp, end = 16.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (tone == NoticeTone.Problem) {
            // The mark that goes with ember: an exclamation in the glyph style. Decoration to a
            // screen reader, which gets the sentence itself.
            Canvas(Modifier.padding(top = 2.dp).size(20.dp).clearAndSetSemantics { }) {
                val unit = size.minDimension / 24f
                val stroke = 2.5f * unit
                drawLine(rule, Offset(12f * unit, 5f * unit), Offset(12f * unit, 13.5f * unit), stroke, StrokeCap.Square)
                drawLine(rule, Offset(12f * unit, 18f * unit), Offset(12f * unit, 18.5f * unit), stroke, StrokeCap.Square)
            }
        }
        Column(Modifier.weight(1f)) {
            BasicText(
                text,
                Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = Hd.type.body.copy(color = colors.chalk),
            )
            if (action != null) action()
        }
    }
}

private val NOTICE_RULE: Dp = 3.dp

/** The name of a section: a mono label that a screen reader lists as a heading, over a hairline. */
@Composable
fun SectionHeader(label: String, modifier: Modifier = Modifier) {
    val hairline = Hd.colors.hairline
    Column(modifier.fillMaxWidth()) {
        Label(label, heading = true)
        Spacer(Modifier.height(HdDimens.Rhythm))
        Box(Modifier.fillMaxWidth().height(HdDimens.Hairline).background(hairline))
    }
}

/**
 * A line of text that has not arrived yet. Still: no shimmer, nothing that moves while the screen
 * waits. Nothing to a screen reader; say what is loading in words beside it.
 */
@Composable
fun SkeletonLine(modifier: Modifier = Modifier, widthFraction: Float = 1f, height: Dp = 12.dp) {
    Box(
        modifier
            .fillMaxWidth(widthFraction.coerceIn(0f, 1f))
            .height(height)
            .background(Hd.colors.hairline, Hd.shapes.chip)
            .clearAndSetSemantics { },
    )
}

/** A block that has not arrived yet: a plate's worth of [SkeletonLine]. Still, and silent. */
@Composable
fun SkeletonBlock(modifier: Modifier = Modifier, height: Dp = 96.dp) {
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .background(Hd.colors.hairline, Hd.shapes.plate)
            .clearAndSetSemantics { },
    )
}

/**
 * Five squares that fill with heat, as on the widget. [lit] of [total] take [color]; the rest are
 * hairline. [description] is what a screen reader says ("Heat 3 of 5"); without one the pixels
 * are decoration and the words beside them carry the state.
 */
@Composable
fun HeatPixels(
    lit: Int,
    modifier: Modifier = Modifier,
    total: Int = 5,
    color: Color = Hd.colors.ember,
    description: String? = null,
) {
    val unlit = Hd.colors.hairline
    val count = total.coerceAtLeast(1)
    Canvas(
        modifier
            .size(width = (6 * count + 2 * (count - 1)).dp, height = 6.dp)
            .heatSemantics(description),
    ) {
        val px = size.height
        val gap = if (count > 1) (size.width - count * px) / (count - 1) else 0f
        repeat(count) { i ->
            drawRect(
                color = if (i < lit) color else unlit,
                topLeft = Offset(i * (px + gap), 0f),
                size = Size(px, px),
            )
        }
    }
}

private fun Modifier.heatSemantics(description: String?): Modifier =
    if (description == null) clearAndSetSemantics { } else semantics { contentDescription = description }
