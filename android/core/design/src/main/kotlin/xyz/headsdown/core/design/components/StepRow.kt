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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import xyz.headsdown.core.design.Hd
import xyz.headsdown.core.design.HdDimens

enum class StepStatus(internal val spoken: String) {
    Done("Done"),
    Current("Current step"),
    Todo("Not done yet"),
}

/**
 * A step of a setup or a ritual: a square badge, a [title], a [subtitle], and under them whatever
 * the step needs ([content]: its buttons, its guide).
 *
 * The badge is a square, never a circle. Done is a filled square with a drawn tick; the current
 * step has a heavy outline around its number; a step still to do has a thin one. The state is
 * also said in words: the header is one node to a screen reader, "Notifications, …, Done".
 *
 * With [onClick] the header (badge, title, subtitle) is a button.
 */
@Composable
fun StepRow(
    index: Int,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    status: StepStatus = StepStatus.Todo,
    onClick: (() -> Unit)? = null,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val colors = Hd.colors
    val shape = Hd.shapes.chip
    val interaction = remember { MutableInteractionSource() }
    val header = Modifier
        .fillMaxWidth()
        .defaultMinSize(minHeight = HdDimens.Touch)
        .semantics(mergeDescendants = true) { stateDescription = status.spoken }
        .then(
            if (onClick == null) {
                Modifier
            } else {
                Modifier
                    .focusRing(interaction.isFocused(), colors.chalk)
                    .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            },
        )

    Column(modifier.fillMaxWidth()) {
        Row(header, horizontalArrangement = Arrangement.spacedBy(BADGE_GAP), verticalAlignment = Alignment.Top) {
            val badge = Modifier.padding(top = 2.dp).size(BADGE)
            when (status) {
                StepStatus.Done -> Canvas(badge.background(colors.chalk, shape)) { drawTick(colors.onChalk) }
                else -> Box(
                    badge.border(if (status == StepStatus.Current) 2.dp else HdDimens.Hairline, if (status == StepStatus.Current) colors.chalk else colors.ash, shape),
                    contentAlignment = Alignment.Center,
                ) {
                    // The number is the badge's drawing; the header's words carry the step.
                    BasicText(
                        index.toString(),
                        Modifier.clearAndSetSemantics { },
                        style = Hd.type.label.copy(color = if (status == StepStatus.Current) colors.chalk else colors.ash),
                    )
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                BasicText(title, style = Hd.type.headline.copy(color = colors.chalk))
                if (subtitle != null) BasicText(subtitle, style = Hd.type.body.copy(color = colors.ash))
            }
        }
        if (content != null) {
            Column(
                Modifier.padding(start = BADGE + BADGE_GAP, top = HdDimens.Rhythm),
                verticalArrangement = Arrangement.spacedBy(HdDimens.Rhythm),
                content = content,
            )
        }
    }
}

private val BADGE = 28.dp
private val BADGE_GAP = 12.dp
