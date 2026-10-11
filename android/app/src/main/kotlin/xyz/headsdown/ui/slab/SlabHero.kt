package xyz.headsdown.ui.slab

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

/**
 * The slab: a dark stone with light underneath, the phone's stand-in.
 *
 * It stays LEVEL WITH THE REAL WORLD, like a bubble level, so turning the phone toward face-down
 * shows it from below, where the 5x5 board is. The light under it is ember while the rig is
 * armed or cooling and ORE gold when hot.
 *
 * WITH NOTHING PROVIDED IT IS INERT: [LocalSlabConfig] defaults to polygons in a fixed pose and
 * [LocalTiltSource] to no sensor, so this composable then builds no shader, registers no
 * listener, starts no loop and animates nothing. An Activity provides the live values.
 *
 * A STILL PHONE DRAWS NO FRAMES. There is no time uniform and no ambient animation: the slab is
 * drawn again only when gravity, a finger, the scroll or the state moves it.
 *
 * It draws no text. Accessibility gets [contentDescription] with the role of an image, or
 * nothing at all when it is null.
 *
 * @param heat how many rows of the board are lit, 0..5. The state caps it: Cold, Broken and
 *   Frozen show none, Armed and Cooling at most three, Hot up to five. An ARMED slab also lights
 *   up to three rows by itself, in ember, as the phone turns over.
 * @param scrollPx the page's scroll in px, read in the draw phase. As it grows the camera drops
 *   under the slab and the slab recedes inside its own bounds.
 * @param interactive whether a HORIZONTAL-first drag turns the slab (a vertical-first drag is
 *   always left to the page). A tap is a small knock either way.
 * @param enter play the launch once: from edge-on, the slab tilts to rest and drops with one bounce.
 * @param onFlipped a DRAG turned the slab over to its underside. Never called for the phone
 *   itself turning over, so it is safe to answer with a haptic.
 * @param onKnock the slab was tapped.
 */
@Composable
fun SlabHero(
    state: SlabState,
    heat: Int,
    modifier: Modifier = Modifier,
    palette: SlabPalette = SlabPalette.Dark,
    scrollPx: () -> Float = { 0f },
    interactive: Boolean = false,
    enter: Boolean = false,
    onFlipped: () -> Unit = {},
    onKnock: () -> Unit = {},
    contentDescription: String? = null,
) {
    // A frame must never be a recomposition: tests and the lab count these against the draws.
    SlabProbe.compositions++
    val config = LocalSlabConfig.current
    val debug = LocalSlabDebug.current
    val live = config.motion == SlabMotion.Live
    val drawer = remember(config.renderer) { SlabDrawers.create(config.renderer) }
    val frame = remember { SlabFrame() }
    val look = remember { SlabLook() }
    val pose = remember { FloatArray(2) }
    val target = remember(state, heat) { SlabTarget.of(state, heat) }
    val motion = if (live) remember { SlabMotionState(enter) } else null
    val scroll by rememberUpdatedState(scrollPx)

    if (motion != null) {
        val source = LocalTiltSource.current
        // The sensor runs only while the hero is composed, the activity is resumed and motion is live.
        LifecycleResumeEffect(source, motion) {
            source.start(motion.listener)
            onPauseOrDispose {
                source.stop(motion.listener)
                motion.sensorStopped()
            }
        }
        LaunchedEffect(motion) { motion.follow() }
        LaunchedEffect(motion, target, palette) { motion.show(target, palette) }
        if (enter) LaunchedEffect(motion) { motion.launch() }
    }

    val semantics = if (contentDescription != null) {
        Modifier.clearAndSetSemantics {
            this.contentDescription = contentDescription
            role = Role.Image
        }
    } else {
        Modifier
    }

    val gestures = if (motion != null) {
        val scope = rememberCoroutineScope()
        val knocked by rememberUpdatedState(onKnock)
        val flipped by rememberUpdatedState(onFlipped)
        Modifier.pointerInput(motion, interactive) {
            slabGestures(interactive, scope, motion, frame, { knocked() }, { flipped() })
        }
    } else {
        Modifier
    }

    Spacer(
        modifier
            .then(semantics)
            .then(gestures)
            .drawWithCache {
                val dropPx = SlabMotionSpec.LAUNCH_DROP_DP.dp.toPx()
                onDrawBehind {
                    val w = size.width
                    val h = size.height
                    if (w <= 0f || h <= 0f) return@onDrawBehind
                    SlabProbe.draws++

                    // The pose: rest, pulled toward gravity, plus the finger, the launch and the scroll.
                    pose[0] = 0f
                    pose[1] = SlabGeometry.REST_DEGREES
                    var scale = 1f
                    var centerY = h * CENTER_Y
                    var flipRows = 0f
                    if (motion != null) {
                        val p = SlabScroll.progress(scroll(), h)
                        val gravity = SlabScroll.gravityWeight(p)
                        motion.gravityCounts(gravity > 0f)
                        val gx = motion.gravityX
                        val gy = motion.gravityY
                        val user = motion.user.value
                        SlabPose.compose(
                            gx, gy, gravity, user.x, user.y, SlabScroll.pitchDegrees(p), motion.launchTurn.value, pose,
                        )
                        scale = SlabScroll.scale(p)
                        centerY += h * SlabScroll.sink(p) - dropPx * motion.launchDrop.value
                        if (state == SlabState.Armed) flipRows = SlabTarget.armedFlipRows(SlabMotionState.lean(gx, gy)) * gravity
                    }
                    if (debug != null && debug.poseOverride) {
                        val clamp = SlabFrame.clampScale(debug.poseX, debug.poseY)
                        pose[0] = debug.poseX * clamp
                        pose[1] = debug.poseY * clamp
                    }
                    val widthFraction = debug?.widthFraction ?: SlabGeometry.WIDTH_FRACTION
                    val slabWidth = min(w * widthFraction, h * MAX_WIDTH_OF_HEIGHT) * scale
                    frame.set(pose[0], pose[1], w / 2f, centerY, slabWidth)

                    if (motion != null) motion.fill(look, target, palette) else look.set(target, palette)
                    look.lit = max(look.lit, flipRows)
                    look.glowPx = slabWidth * (debug?.glowFraction ?: SlabLook.GLOW_FRACTION)
                    if (debug != null) {
                        look.grain = debug.grain
                        look.emboss = debug.emboss
                        look.squared = debug.squared
                        look.passes = debug.passes
                    }
                    clipRect { with(drawer) { draw(frame, look, palette) } }
                }
            },
    )
}

/** The slab's centre, as a fraction of the hero's height: a little above the middle, the halo sits below. */
private const val CENTER_Y = 0.47f

/** The slab is never wider than this much of the hero's height, so a short hero still holds it. */
private const val MAX_WIDTH_OF_HEIGHT = 0.78f

/** Taps this close to the slab's bounding rectangle still count as a knock. */
private val KNOCK_MARGIN = 16.dp

private suspend fun PointerInputScope.slabGestures(
    interactive: Boolean,
    scope: CoroutineScope,
    motion: SlabMotionState,
    frame: SlabFrame,
    onKnock: () -> Unit,
    onFlipped: () -> Unit,
) {
    val tracker = VelocityTracker()
    val degreesPerPx = SlabMotionSpec.DRAG_DEGREES_PER_DP / density
    val margin = KNOCK_MARGIN.toPx()
    awaitEachGesture {
        val down = awaitFirstDown()
        // A finger stops whatever the slab was doing; a drag goes on from where it is.
        scope.launch(start = CoroutineStart.UNDISPATCHED) { motion.user.stop() }
        val dragStart = if (interactive) {
            // Horizontal first, or not at all: a vertical drag belongs to the page's scroll.
            awaitHorizontalTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
        } else {
            null
        }
        if (dragStart != null) {
            tracker.resetTracking()
            tracker.addPosition(dragStart.uptimeMillis, dragStart.position)
            drag(dragStart.id) { change ->
                val delta = change.positionChange()
                change.consume()
                tracker.addPosition(change.uptimeMillis, change.position)
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    motion.dragBy(Offset(delta.x * degreesPerPx, -delta.y * degreesPerPx))
                }
                val user = motion.user.value
                if (motion.crossedToUnderside(SlabMotionState.lean(motion.gravityX + user.x, motion.gravityY + user.y))) {
                    onFlipped()
                }
            }
            val velocity = tracker.calculateVelocity()
            scope.launch { motion.release(Offset(velocity.x * degreesPerPx, -velocity.y * degreesPerPx)) }
        } else {
            val up = if (interactive) {
                currentEvent.changes.firstOrNull { it.id == down.id }?.takeIf { !it.pressed && !it.isConsumed }
            } else {
                waitForUpOrCancellation()
            }
            // Before the first draw there are no bounds yet, and the whole hero stands in for the slab.
            val onSlab = up != null && (
                frame.right <= frame.left ||
                    up.position.x in (frame.left - margin)..(frame.right + margin) &&
                    up.position.y in (frame.top - margin)..(frame.bottom + margin)
                )
            if (up != null && onSlab) {
                // Toward the tapped side, harder the further from the centre; a tap on the centre still nods.
                val halfW = max(1f, (frame.right - frame.left) / 2f)
                val halfH = max(1f, (frame.bottom - frame.top) / 2f)
                var toward = Offset((up.position.x - frame.centerX) / halfW, -(up.position.y - frame.centerY) / halfH)
                val length = toward.getDistance()
                toward = when {
                    length > 1f -> toward / length
                    length < 0.3f -> Offset(0f, -0.3f)
                    else -> toward
                }
                scope.launch { motion.knock(toward) }
                onKnock()
            } else {
                // Not ours (the page scrolled, or the tap missed): let go from wherever the finger stopped it.
                scope.launch { motion.release(Offset.Zero) }
            }
        }
    }
}

/** Builds the renderer a config asks for; the shader only where it exists and compiles. */
internal object SlabDrawers {
    fun create(renderer: SlabRenderer): SlabDrawer {
        if (renderer == SlabRenderer.Shader && Build.VERSION.SDK_INT >= SlabPolicy.SHADER_SDK) {
            shader()?.let { return it }
        }
        return PolygonSlabDrawer()
    }

    @RequiresApi(33)
    private fun shader(): SlabDrawer? = try {
        ShaderSlabDrawer()
    } catch (_: RuntimeException) {
        // A source the device's compiler refuses must cost the shader, never the screen.
        null
    }
}

/**
 * The lab's hooks into the hero (debug builds only provide one): a synthetic pose and the
 * quality switches. Snapshot state, so a change redraws.
 */
@Stable
internal class SlabDebug {
    var poseOverride by mutableStateOf(false)
    var poseX by mutableFloatStateOf(0f)
    var poseY by mutableFloatStateOf(SlabGeometry.REST_DEGREES)
    var widthFraction by mutableFloatStateOf(SlabGeometry.WIDTH_FRACTION)
    var glowFraction by mutableFloatStateOf(SlabLook.GLOW_FRACTION)
    var grain by mutableFloatStateOf(1f)
    var emboss by mutableFloatStateOf(1f)
    var squared by mutableStateOf(true)
    var passes by mutableIntStateOf(1)
}

internal val LocalSlabDebug = staticCompositionLocalOf<SlabDebug?> { null }
