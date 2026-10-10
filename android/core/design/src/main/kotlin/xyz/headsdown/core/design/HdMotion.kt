package xyz.headsdown.core.design

import android.animation.ValueAnimator
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LifecycleResumeEffect

/**
 * Three springs, and no other motion. Nothing in this system animates forever: a still screen
 * draws nothing.
 *
 * [reduced] is the system's "Remove animations" setting. Under it the three springs collapse into
 * one that arrives within a frame, so a caller that forgets to ask still does not animate; a
 * caller that must not move at all reads [reduced] and snaps.
 */
@Immutable
class HdMotion(val reduced: Boolean) {

    /** Damped, no bounce: something comes to rest. */
    fun <T> settle(visibilityThreshold: T? = null): SpringSpec<T> = spec(0.86f, 380f, visibilityThreshold)

    /** Fast and tight: the answer to a finger. Press feedback starts on touch-down. */
    fun <T> press(visibilityThreshold: T? = null): SpringSpec<T> = spec(0.9f, 1500f, visibilityThreshold)

    /** One visible bounce: the slab lands. */
    fun <T> thud(visibilityThreshold: T? = null): SpringSpec<T> = spec(0.62f, 220f, visibilityThreshold)

    private fun <T> spec(dampingRatio: Float, stiffness: Float, visibilityThreshold: T?): SpringSpec<T> =
        if (reduced) spring(1f, COLLAPSED_STIFFNESS, visibilityThreshold) else spring(dampingRatio, stiffness, visibilityThreshold)

    override fun equals(other: Any?): Boolean = other is HdMotion && other.reduced == reduced

    override fun hashCode(): Int = reduced.hashCode()

    companion object {
        /** Critically damped at this stiffness, a spring is within a thousandth of its target in under 3 ms. */
        private const val COLLAPSED_STIFFNESS = 10_000_000f

        /** True when the system's "Remove animations" setting is on. */
        fun systemReduced(): Boolean = !ValueAnimator.areAnimatorsEnabled()
    }
}

/** The system setting, read when the theme enters the composition and again on every resume. */
@Composable
internal fun rememberHdMotion(): HdMotion {
    var reduced by remember { mutableStateOf(HdMotion.systemReduced()) }
    // The setting is changed in the Settings app, so this screen is resumed after any change.
    LifecycleResumeEffect(Unit) {
        reduced = HdMotion.systemReduced()
        onPauseOrDispose { }
    }
    return remember(reduced) { HdMotion(reduced) }
}
