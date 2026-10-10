package xyz.headsdown.core.design.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import xyz.headsdown.core.design.Hd

private const val PRESSED_SCALE = 0.97f
private const val PRESSED_ALPHA_REDUCED = 0.8f

/** What [pressFeedback] draws from: whether a finger is down, and how far the control has given. */
@Stable
internal class PressState {
    /** Set in composition, which rebuilds the modifier: plain fields, not snapshot state. */
    var enabled = true
    var reduced = false
    var pressed by mutableStateOf(false)
    val scale = Animatable(1f)

    val held: Boolean get() = pressed && enabled
}

/**
 * Press feedback for a control: it gives a little under the finger, on touch-down (not after the
 * tap timeout a scrolling container adds), on the `press` spring.
 *
 * With motion reduced nothing moves: the control dims while it is held, in one step.
 */
@Composable
internal fun rememberPressState(enabled: Boolean): PressState {
    val motion = Hd.motion
    val state = remember { PressState() }
    state.enabled = enabled
    state.reduced = motion.reduced
    val held = state.held
    LaunchedEffect(held, motion) {
        val target = if (held && !motion.reduced) PRESSED_SCALE else 1f
        if (motion.reduced) state.scale.snapTo(target) else state.scale.animateTo(target, motion.press())
    }
    return state
}

/** Applies [state]. Put it before the modifier that handles the click. */
internal fun Modifier.pressFeedback(state: PressState): Modifier {
    if (!state.enabled) return this
    return this
        .pointerInput(state) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                state.pressed = true
                try {
                    // Null when the finger left or a scroll took the gesture: either way it is over.
                    waitForUpOrCancellation()
                } finally {
                    // Also when the control is disabled mid-press and this coroutine is cancelled.
                    state.pressed = false
                }
            }
        }
        .graphicsLayer {
            val s = state.scale.value
            scaleX = s
            scaleY = s
            alpha = if (state.held && state.reduced) PRESSED_ALPHA_REDUCED else 1f
        }
}

/** Whether the control has keyboard or switch-access focus. */
@Composable
internal fun InteractionSource.isFocused(): Boolean {
    val focused by collectIsFocusedAsState()
    return focused
}

/**
 * A 2dp ring inside the control's edge while it is [focused]. [color] must read on the control's
 * own fill (pit on a chalk bar, chalk on anything dark).
 */
internal fun Modifier.focusRing(focused: Boolean, color: Color): Modifier {
    if (!focused) return this
    return drawWithContent {
        drawContent()
        val stroke = 2.dp.toPx()
        val inset = 3.dp.toPx() + stroke / 2
        drawRect(
            color = color,
            topLeft = Offset(inset, inset),
            size = Size(size.width - 2 * inset, size.height - 2 * inset),
            style = Stroke(stroke),
        )
    }
}
