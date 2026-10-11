package xyz.headsdown.ui.slab

import android.view.Window
import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlin.math.acos
import kotlin.math.max
import kotlin.math.min

/** Where the lay-down glow is. */
enum class GlowPhase {
    /** Nothing is drawn. */
    Idle,

    /** The phone is turning over: the overlay warms from ember toward gold with the turn. */
    Rising,

    /** The phone is down: full gold, held. */
    Holding,

    /** Gold to black. */
    Fading,

    /**
     * Black coming up over nothing, with no light at all: the phone was put down again after a
     * lift, or it was already turned over when the rig was armed or the app came back.
     */
    Dimming,

    /** Black, and the window asks for minimum brightness. */
    Dark,

    /**
     * No light until the phone has been turned up again. Either it was lifted after the fade
     * began (whatever was on screen lets go), or it was already turned over when the rig was
     * armed (nothing was on screen, and nothing comes).
     */
    Lifted,
}

/**
 * The glow's whole state. [level] is the rise, 0..1, while [GlowPhase.Rising]; in
 * [GlowPhase.Lifted] it is how black the overlay was when it was let go, and [alpha] how opaque.
 */
data class GlowState(
    val phase: GlowPhase = GlowPhase.Idle,
    /** When [phase] began, on the caller's monotonic clock. */
    val sinceMillis: Long = 0L,
    val level: Float = 0f,
    val updatedMillis: Long = 0L,
    /**
     * [GlowPhase.Idle] only: the armed phone has been seen turned up (below
     * [LayDownGlowMachine.START_DEGREES]), so the next turn past it is a lay-down and may glow.
     */
    val primed: Boolean = false,
    /** [GlowPhase.Lifted] only: the overlay's opacity when it was let go. */
    val alpha: Float = 0f,
)

/** What to draw for a [GlowState]: one full-bleed colour. */
data class GlowFrame(
    /** The overlay's opacity. 0 draws nothing. */
    val alpha: Float,
    /** 0 ember, 1 gold. */
    val warmth: Float,
    /** 0 the warm colour, 1 black. */
    val black: Float,
    /** Ask the window for minimum brightness. */
    val minimumBrightness: Boolean,
) {
    companion object {
        val None = GlowFrame(0f, 0f, 0f, false)
    }
}

/**
 * THE ONE RISKY MOMENT OF THE DESIGN, as a pure function of the phone's filtered angle, whether
 * the rig is armed, and time. Light for the room, not for the eye: as an armed phone turns over,
 * the screen warms from ember toward gold, holds for a second face-down, fades to black and
 * dims.
 *
 * IT NEVER FLASHES. There is one slow rise and one fade:
 * - the rise is rate-limited however fast the phone turns, and so is every other change of the
 *   overlay: nothing on screen ever steps (only disarming and leaving clear it at once);
 * - once the fade has begun no light comes back until the phone has been turned up again past
 *   [START_DEGREES]: put straight back down, it only goes black again, slowly;
 * - light needs a LAY-DOWN: an armed phone must first be seen turned up. A rig armed while the
 *   phone is already held over a face in bed, or an app that comes back to a phone already
 *   lying face-down, gets no light at all, only the black once it is down.
 *
 * It only ever draws a colour and lowers brightness: it cannot raise brightness above the
 * user's setting.
 *
 * Angles are the phone's own: 0 lying face-up, 90 upright, 180 face-down.
 */
object LayDownGlowMachine {
    /** The glow starts here: the screen is within 60 degrees of face-down. */
    const val START_DEGREES = 120f

    /** The phone counts as down from here. */
    const val DOWN_DEGREES = 165f

    /** Lifted, once it was down, below this. */
    const val LIFT_DEGREES = 150f

    /** The fastest the glow rises from nothing to full, and falls back if the turn is undone. */
    const val RISE_MILLIS = 700L
    const val FALL_MILLIS = 300L
    const val HOLD_MILLIS = 1_000L
    const val FADE_MILLIS = 600L
    const val LIFT_MILLIS = 200L

    /** The overlay's opacity at full glow: the page stays faintly there until the fade. */
    const val PEAK_ALPHA = 0.88f

    fun step(state: GlowState, thetaDegrees: Float, armed: Boolean, nowMillis: Long): GlowState {
        if (!armed) return if (state == Disarmed) state else Disarmed
        val elapsed = nowMillis - state.sinceMillis
        return when (state.phase) {
            GlowPhase.Idle -> when {
                thetaDegrees < START_DEGREES ->
                    if (state.primed) state else GlowState(updatedMillis = nowMillis, primed = true)
                // Already turned over, and never seen turned up: this is not a lay-down. No light.
                !state.primed -> GlowState(GlowPhase.Lifted, nowMillis, level = 1f, updatedMillis = nowMillis, alpha = 0f)
                else -> rise(state, thetaDegrees, nowMillis)
            }
            GlowPhase.Rising -> rise(state, thetaDegrees, nowMillis)
            GlowPhase.Holding -> when {
                // Picked up again before the fade: the glow goes back the way it came.
                thetaDegrees < LIFT_DEGREES -> GlowState(GlowPhase.Rising, nowMillis, 1f, nowMillis)
                elapsed >= HOLD_MILLIS -> GlowState(GlowPhase.Fading, state.sinceMillis + HOLD_MILLIS, 1f, nowMillis)
                else -> state
            }
            GlowPhase.Fading -> when {
                thetaDegrees < LIFT_DEGREES -> {
                    val t = (elapsed.toFloat() / FADE_MILLIS).coerceIn(0f, 1f)
                    GlowState(
                        GlowPhase.Lifted, nowMillis, level = t, updatedMillis = nowMillis,
                        alpha = PEAK_ALPHA + (1f - PEAK_ALPHA) * t,
                    )
                }
                elapsed >= FADE_MILLIS -> GlowState(GlowPhase.Dark, state.sinceMillis + FADE_MILLIS, 1f, nowMillis)
                else -> state.copy(updatedMillis = nowMillis)
            }
            GlowPhase.Dimming -> when {
                thetaDegrees < LIFT_DEGREES -> GlowState(
                    GlowPhase.Lifted, nowMillis, level = 1f, updatedMillis = nowMillis,
                    alpha = (elapsed.toFloat() / FADE_MILLIS).coerceIn(0f, 1f),
                )
                elapsed >= FADE_MILLIS -> GlowState(GlowPhase.Dark, state.sinceMillis + FADE_MILLIS, 1f, nowMillis)
                else -> state.copy(updatedMillis = nowMillis)
            }
            GlowPhase.Dark ->
                if (thetaDegrees < LIFT_DEGREES) {
                    GlowState(GlowPhase.Lifted, nowMillis, level = 1f, updatedMillis = nowMillis, alpha = 1f)
                } else {
                    state
                }
            GlowPhase.Lifted -> when {
                // Whatever was on screen is still letting go (the frame follows the clock, not the state).
                elapsed < LIFT_MILLIS -> state
                // Turned up again: ready for the next lay-down.
                thetaDegrees < START_DEGREES -> GlowState(updatedMillis = nowMillis, primed = true)
                // Put straight back down: to black again, slowly, and still no second glow.
                thetaDegrees >= DOWN_DEGREES -> GlowState(GlowPhase.Dimming, nowMillis, 1f, nowMillis)
                // Hovering in between with nothing on screen: nothing changes, nothing to redraw.
                else -> state
            }
        }
    }

    /** Idle or rising: the level follows the turn, no faster than the two rates. */
    private fun rise(state: GlowState, thetaDegrees: Float, nowMillis: Long): GlowState {
        val target = ((thetaDegrees - START_DEGREES) / (DOWN_DEGREES - START_DEGREES)).coerceIn(0f, 1f)
        // The rise starts from nothing on its first step: time spent idle is not time to catch up on.
        val dt = if (state.phase == GlowPhase.Idle) 0L else (nowMillis - state.updatedMillis).coerceIn(0L, MAX_STEP_MILLIS)
        val level = if (target > state.level) {
            min(target, state.level + dt.toFloat() / RISE_MILLIS)
        } else {
            max(target, state.level - dt.toFloat() / FALL_MILLIS)
        }
        return when {
            level <= 0f && target <= 0f -> GlowState(updatedMillis = nowMillis, primed = true)
            level >= 1f && thetaDegrees >= DOWN_DEGREES -> GlowState(GlowPhase.Holding, nowMillis, 1f, nowMillis)
            else -> GlowState(
                GlowPhase.Rising,
                if (state.phase == GlowPhase.Rising) state.sinceMillis else nowMillis,
                level,
                nowMillis,
            )
        }
    }

    fun frame(state: GlowState, nowMillis: Long): GlowFrame {
        val elapsed = (nowMillis - state.sinceMillis).coerceAtLeast(0L)
        return when (state.phase) {
            GlowPhase.Idle -> GlowFrame.None
            GlowPhase.Rising -> {
                val l = state.level
                GlowFrame(alpha = PEAK_ALPHA * l * l * (3f - 2f * l), warmth = l, black = 0f, minimumBrightness = false)
            }
            GlowPhase.Holding -> GlowFrame(PEAK_ALPHA, 1f, 0f, false)
            GlowPhase.Fading -> {
                val t = (elapsed.toFloat() / FADE_MILLIS).coerceIn(0f, 1f)
                GlowFrame(PEAK_ALPHA + (1f - PEAK_ALPHA) * t, 1f, t, false)
            }
            GlowPhase.Dimming -> GlowFrame((elapsed.toFloat() / FADE_MILLIS).coerceIn(0f, 1f), 1f, 1f, false)
            GlowPhase.Dark -> GlowFrame(1f, 1f, 1f, true)
            GlowPhase.Lifted -> {
                val t = (elapsed.toFloat() / LIFT_MILLIS).coerceIn(0f, 1f)
                // Whatever the overlay was when the phone was lifted lets go as it is: nothing brightens.
                GlowFrame(alpha = (1f - t) * state.alpha, warmth = 1f, black = state.level, minimumBrightness = false)
            }
        }
    }

    /** Where a rig that is not armed always is: nothing shown, and not yet seen turned up. */
    private val Disarmed = GlowState()

    /** A stall in the samples is not time the rise may catch up on. */
    private const val MAX_STEP_MILLIS = 100L
}

/**
 * Runs [LayDownGlowMachine] from a [TiltSource]. The sensor's own samples are its clock, so
 * there is no frame loop and no timer: no samples, no steps, and a phone that lies still (dark,
 * or idle) changes nothing and draws nothing.
 */
@Stable
internal class LayDownGlowController(private val applyBrightness: (minimum: Boolean) -> Unit) {
    private val filter = GravityFilter()
    private var state = GlowState()
    private var minimum = false

    /**
     * Whether the rig is armed. Turning it off clears the glow and gives the brightness back AT
     * ONCE: it does not wait for the next sample to say so.
     */
    var armed = false
        set(value) {
            field = value
            if (!value) {
                state = GlowState()
                show(GlowFrame.None)
            }
        }

    var alpha by mutableFloatStateOf(0f)
        private set
    var warmth by mutableFloatStateOf(0f)
        private set
    var black by mutableFloatStateOf(0f)
        private set

    val listener = TiltListener { x, y, z, timestampNanos ->
        // A sample the filter refuses (a jolt as the phone lands) still moves time on.
        filter.add(x, y, z, timestampNanos)
        if (filter.hasValue) {
            val now = timestampNanos / 1_000_000L
            val theta = acos(filter.z.coerceIn(-1f, 1f)) * GravityFilter.DEGREES
            state = LayDownGlowMachine.step(state, theta, armed, now)
            show(LayDownGlowMachine.frame(state, now))
        }
    }

    /** The sensor stopped or the glow left: nothing shown, brightness back to the user's. */
    fun reset() {
        filter.reset()
        state = GlowState()
        show(GlowFrame.None)
    }

    private fun show(frame: GlowFrame) {
        if (frame.alpha != alpha) alpha = frame.alpha
        if (frame.warmth != warmth) warmth = frame.warmth
        if (frame.black != black) black = frame.black
        if (frame.minimumBrightness != minimum) {
            minimum = frame.minimumBrightness
            applyBrightness(minimum)
        }
    }
}

/**
 * The lay-down glow, full-bleed over the screen. When [armed] and the phone turns past
 * [LayDownGlowMachine.START_DEGREES] toward face-down, the overlay warms from ember toward gold,
 * holds for about a second once the phone is down, fades to black and asks the window for
 * minimum brightness; the moment the phone is lifted, or [armed] turns false, or this leaves the
 * composition, or the activity pauses, brightness is the user's again.
 *
 * [armed] must stay true for as long as the phone may lie there with its screen on: pass "the
 * rig is armed", which in this app holds until the screen goes off (the rig only goes hot once it
 * is dark), not a value that turns false the moment the phone is face-down.
 *
 * It does nothing when motion is not Live or no [TiltSource] is provided. It has no pointer
 * input at all, so it never blocks a touch, shown or not, and it is invisible to accessibility.
 */
@Composable
fun LayDownGlow(armed: Boolean, modifier: Modifier = Modifier) {
    if (LocalSlabConfig.current.motion != SlabMotion.Live) return
    val source = LocalTiltSource.current
    val window = LocalActivity.current?.window
    val controller = remember(window) { LayDownGlowController { minimum -> window?.setMinimumBrightness(minimum) } }
    // Disarming clears the glow and restores the brightness here, in the same frame.
    SideEffect { if (controller.armed != armed) controller.armed = armed }
    LifecycleResumeEffect(source, controller) {
        source.start(controller.listener)
        onPauseOrDispose {
            source.stop(controller.listener)
            controller.reset()
        }
    }
    DisposableEffect(controller) { onDispose { controller.reset() } }
    Spacer(
        modifier.drawBehind {
            val alpha = controller.alpha
            if (alpha > 0f) {
                val warm = lerp(SlabPalette.Dark.emitEmber, SlabPalette.Dark.emitGold, controller.warmth)
                drawRect(lerp(warm, Color.Black, controller.black), alpha = alpha)
            }
        },
    )
}

/** Minimum, or back to the user's own setting. Never a value above it. */
private fun Window.setMinimumBrightness(minimum: Boolean) {
    val wanted = if (minimum) {
        WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF
    } else {
        WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
    }
    val params = attributes
    if (params.screenBrightness != wanted) {
        params.screenBrightness = wanted
        attributes = params
    }
}
