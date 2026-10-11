package xyz.headsdown.ui.slab

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** The numbers of the slab's motion, in one place. Angles in degrees, time in milliseconds. */
object SlabMotionSpec {
    /** A drag turns the slab this many degrees per dp. */
    const val DRAG_DEGREES_PER_DP = 0.45f

    /** The spring that takes the slab back to level after a drag, carrying the fling. */
    const val RELEASE_DAMPING = 0.62f
    const val RELEASE_STIFFNESS = 140f
    const val MAX_FLING_DEGREES_PER_SECOND = 720f

    /** A tap: a short impulse toward the tapped side, on a livelier spring. */
    const val KNOCK_DAMPING = 0.35f
    const val KNOCK_STIFFNESS = 520f
    const val KNOCK_DEGREES_PER_SECOND = 240f

    /** The launch: from edge-on to rest, and a drop of a few dp with one visible bounce. */
    const val LAUNCH_FROM_DEGREES = 90f
    const val LAUNCH_TILT_DAMPING = 0.8f
    const val LAUNCH_TILT_STIFFNESS = 120f
    const val LAUNCH_DROP_DP = 12f
    const val LAUNCH_DROP_DAMPING = 0.5f
    const val LAUNCH_DROP_STIFFNESS = 300f

    const val ROW_MILLIS = 90
    const val CROSSFADE_MILLIS = 320

    /** The shift machine's grace: a cooling rig's heat falls for this long. */
    const val COOLING_MILLIS = 10_000
    const val COOLING_STEP_MILLIS = 100

    /** The per-frame follow of the filtered gravity. */
    const val FOLLOW_TAU_SECONDS = 0.035f

    /** A filtered pose closer than this to the last one is not a new pose. */
    const val DEADBAND_DEGREES = 0.08f

    /** The follow stops (and asks for no more frames) this close to its target. */
    const val PARK_DEGREES = 0.02f

    /** A drag has turned the slab over once the underside shows this far past edge-on. */
    const val FLIPPED_DEGREES = 104f
    const val UNFLIPPED_DEGREES = 86f
}

/**
 * Everything about the slab that changes over time, as snapshot state that only the draw phase
 * reads: a frame is a draw invalidation, never a recomposition. Created only when motion is
 * Live. Main thread only.
 *
 * NOTHING HERE RUNS ON ITS OWN: the gravity follow asks for frames only while the filtered pose
 * is moving and parks when it has converged, the springs end, and the cooling ramp is ten steps
 * a second for ten seconds. A phone lying still draws nothing.
 */
@Stable
internal class SlabMotionState(enter: Boolean) {
    // ---- gravity ----
    private val filter = GravityFilter()
    private val mapper = TiltMapper()
    private var targetX = 0f
    private var targetY = SlabGeometry.REST_DEGREES
    private var lastSampleNanos = 0L
    private var parked = true
    private val wake = Channel<Unit>(Channel.CONFLATED)

    /** The followed gravity pose; read in the draw phase. */
    var gravityX by mutableFloatStateOf(0f)
        private set
    var gravityY by mutableFloatStateOf(SlabGeometry.REST_DEGREES)
        private set

    val listener = TiltListener { x, y, z, timestampNanos -> onSample(x, y, z, timestampNanos) }

    private fun onSample(x: Float, y: Float, z: Float, timestampNanos: Long) {
        val fresh = !filter.hasValue
        if (!filter.add(x, y, z, timestampNanos)) return
        val dt = if (fresh) 0f else ((timestampNanos - lastSampleNanos) * 1e-9f).coerceIn(0f, 0.25f)
        lastSampleNanos = timestampNanos
        mapper.update(
            filter.x, filter.y, filter.z, dt,
            steady = filter.speedDegreesPerSecond < TiltMapper.STEADY_DEGREES_PER_SECOND,
        )
        val dx = mapper.poseX - targetX
        val dy = mapper.poseY - targetY
        if (dx * dx + dy * dy < SlabMotionSpec.DEADBAND_DEGREES * SlabMotionSpec.DEADBAND_DEGREES) return
        targetX = mapper.poseX
        targetY = mapper.poseY
        if (!muted) wakeIfBehind()
    }

    private fun wakeIfBehind() {
        if (parked && (targetX != gravityX || targetY != gravityY)) {
            parked = false
            wake.trySend(Unit)
        }
    }

    /**
     * The hero is scrolled so far that gravity no longer moves it (its weight in the pose is
     * zero). A phone in the hand is never still, so without this a slab nobody can see would ask
     * for a frame sixty times a second for as long as the page is read. Muted, the pose is still
     * tracked and no frame is asked for; when gravity counts again the slab catches up.
     */
    private var muted = false

    /** Called from the draw phase with whether gravity has any weight in the pose just drawn. */
    fun gravityCounts(counts: Boolean) {
        if (muted == !counts) return
        muted = !counts
        if (!muted) wakeIfBehind()
    }

    /** The sensor stopped (the activity paused): the next sample starts the filter again. */
    fun sensorStopped() = filter.reset()

    /**
     * Follows the filtered pose with a short exponential, one step a frame, ONLY while it is
     * moving. Between movements this is suspended on a channel: no frame is requested, so a test
     * clock or uiautomator sees the app idle.
     */
    suspend fun follow() {
        while (true) {
            wake.receive()
            var last = 0L
            while (!parked) {
                withFrameNanos { now ->
                    SlabProbe.frameCallbacks++
                    val dt = if (last == 0L) 1f / 60f else ((now - last) * 1e-9f).coerceIn(0.001f, 0.1f)
                    last = now
                    val k = 1f - exp(-dt / SlabMotionSpec.FOLLOW_TAU_SECONDS)
                    val nx = gravityX + (targetX - gravityX) * k
                    val ny = gravityY + (targetY - gravityY) * k
                    if (abs(targetX - nx) < SlabMotionSpec.PARK_DEGREES && abs(targetY - ny) < SlabMotionSpec.PARK_DEGREES) {
                        gravityX = targetX
                        gravityY = targetY
                        parked = true
                    } else {
                        gravityX = nx
                        gravityY = ny
                        // Scrolled away in the middle of a turn: stop here, catch up when it counts again.
                        if (muted) parked = true
                    }
                }
            }
        }
    }

    // ---- touch ----

    /** What the finger added to the pose. One Animatable: a new animation cancels the old one. */
    val user = Animatable(Offset.Zero, Offset.VectorConverter)

    private var flipped = false

    /** True the first time a drag shows the underside; false again once it is back on top. */
    fun crossedToUnderside(leanDegrees: Float): Boolean {
        if (!flipped && leanDegrees > SlabMotionSpec.FLIPPED_DEGREES) {
            flipped = true
            return true
        }
        if (flipped && leanDegrees < SlabMotionSpec.UNFLIPPED_DEGREES) flipped = false
        return false
    }

    suspend fun dragBy(degrees: Offset) = user.snapTo(user.value + degrees)

    suspend fun release(velocityDegreesPerSecond: Offset) {
        val speed = velocityDegreesPerSecond.getDistance()
        val velocity = if (speed > SlabMotionSpec.MAX_FLING_DEGREES_PER_SECOND) {
            velocityDegreesPerSecond * (SlabMotionSpec.MAX_FLING_DEGREES_PER_SECOND / speed)
        } else {
            velocityDegreesPerSecond
        }
        user.animateTo(
            Offset.Zero,
            spring(SlabMotionSpec.RELEASE_DAMPING, SlabMotionSpec.RELEASE_STIFFNESS, Offset(0.05f, 0.05f)),
            velocity,
        )
    }

    /** [toward] is where on the slab the tap landed, from its centre, y up, at most unit length. */
    suspend fun knock(toward: Offset) {
        user.animateTo(
            Offset.Zero,
            spring(SlabMotionSpec.KNOCK_DAMPING, SlabMotionSpec.KNOCK_STIFFNESS, Offset(0.05f, 0.05f)),
            toward * SlabMotionSpec.KNOCK_DEGREES_PER_SECOND,
        )
    }

    // ---- launch ----

    /**
     * How much of the pose the launch still holds: 1 is exactly edge-on, 0 is the pose the slab
     * would have without a launch, wherever gravity puts that; the spring takes it a little past 0.
     */
    val launchTurn = Animatable(if (enter) 1f else 0f)

    /** 1 at the top of the drop, 0 at rest; the bounce takes it a little below 0. */
    val launchDrop = Animatable(if (enter) 1f else 0f)

    suspend fun launch() = coroutineScope {
        // The first frame is the one that compiles the shader's pipeline and replaces the splash:
        // the launch starts on the frame after it, so its beginning is seen and not skipped.
        withFrameNanos { }
        withFrameNanos { }
        launch {
            launchTurn.animateTo(0f, spring(SlabMotionSpec.LAUNCH_TILT_DAMPING, SlabMotionSpec.LAUNCH_TILT_STIFFNESS, 0.002f))
        }
        launchDrop.animateTo(0f, spring(SlabMotionSpec.LAUNCH_DROP_DAMPING, SlabMotionSpec.LAUNCH_DROP_STIFFNESS, 0.002f))
    }

    // ---- the look ----

    private val lit = Animatable(0f)
    private val blend = Animatable(1f)
    // Snapshot state, so the draw that showed the fallback is drawn again once there is a target.
    private var to by mutableStateOf<SlabTarget?>(null)
    private var fromHeat = 0f
    private var fromSeam = 0f
    private var fromRim = 0.15f
    private var fromEmit = Color.Black
    private var coolHeat by mutableFloatStateOf(1f)

    private fun settledHeat(target: SlabTarget): Float = if (target.emission == SlabEmission.Cooling) coolHeat else target.heat

    /** Fills [look] for this frame; called in the draw phase. Before [show] ran, [fallback] is shown as it is. */
    fun fill(look: SlabLook, fallback: SlabTarget, palette: SlabPalette) {
        val target = to
        if (target == null) {
            look.set(fallback, palette)
            return
        }
        val b = blend.value
        val heat = settledHeat(target)
        look.lit = lit.value
        look.heat = fromHeat + (heat - fromHeat) * b
        look.seam = fromSeam + (target.seam - fromSeam) * b
        look.rim = fromRim + (target.rim - fromRim) * b
        look.emit = lerp(fromEmit, SlabLook.emission(target.emission, heat, palette), b)
    }

    /**
     * Moves to [target]: rows light or go out at [SlabMotionSpec.ROW_MILLIS] each, the light
     * crossfades, and a cooling slab's heat then falls over the grace in small steps.
     */
    suspend fun show(target: SlabTarget, palette: SlabPalette) = coroutineScope {
        val previous = to
        if (previous == null) {
            // First sight: no transition.
            to = target
            coolHeat = 1f
            lit.snapTo(target.lit)
            blend.snapTo(1f)
        } else {
            val b = blend.value
            val heat = settledHeat(previous)
            fromHeat += (heat - fromHeat) * b
            fromSeam += (previous.seam - fromSeam) * b
            fromRim += (previous.rim - fromRim) * b
            fromEmit = lerp(fromEmit, SlabLook.emission(previous.emission, heat, palette), b)
            coolHeat = 1f
            blend.snapTo(0f)
            to = target
            launch {
                val rows = abs(target.lit - lit.value)
                if (rows > 0f) {
                    val millis = (rows * SlabMotionSpec.ROW_MILLIS).roundToInt().coerceAtLeast(1)
                    lit.animateTo(target.lit, tween(millis, easing = LinearEasing))
                }
            }
            blend.animateTo(1f, tween(SlabMotionSpec.CROSSFADE_MILLIS, easing = LinearEasing))
        }
        if (target.emission == SlabEmission.Cooling) {
            val steps = SlabMotionSpec.COOLING_MILLIS / SlabMotionSpec.COOLING_STEP_MILLIS
            for (i in 1..steps) {
                delay(SlabMotionSpec.COOLING_STEP_MILLIS.toLong())
                coolHeat = 1f - (1f - SlabTarget.COOLED) * i / steps
            }
        }
    }

    companion object {
        fun lean(x: Float, y: Float): Float = sqrt(x * x + y * y)
    }
}

/**
 * The pose the hero draws, from its parts: rest pulled toward gravity by gravity's weight, plus
 * what the finger added, plus the scroll's pitch; then the launch, which holds the whole of it
 * toward exactly edge-on; then the clamp. Pure, and called in the draw phase.
 */
internal object SlabPose {
    /**
     * Writes the tilt vector in degrees to [out] at 0 and 1. [launchTurn] is 1 while the launch
     * holds the slab edge-on and 0 once it has let go.
     */
    fun compose(
        gravityX: Float,
        gravityY: Float,
        gravityWeight: Float,
        userX: Float,
        userY: Float,
        scrollPitch: Float,
        launchTurn: Float,
        out: FloatArray,
    ) {
        var x = gravityX * gravityWeight + userX
        var y = SlabGeometry.REST_DEGREES + (gravityY - SlabGeometry.REST_DEGREES) * gravityWeight + userY + scrollPitch
        if (launchTurn != 0f) {
            // From edge-on TO this pose, whatever it is: a phone held flatter or steeper than
            // usual still sees the slab start as a line and open, never start half open.
            x *= 1f - launchTurn
            y += (SlabMotionSpec.LAUNCH_FROM_DEGREES - y) * launchTurn
        }
        val clamp = SlabFrame.clampScale(x, y)
        out[0] = x * clamp
        out[1] = y * clamp
    }
}
