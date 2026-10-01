package xyz.headsdown.ml.pickup

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * "The phone has come to rest", decided without a model: the accelerometer is averaged over
 * half-second blocks, and the phone is at rest once [Config.quietBlocks] consecutive block means
 * stay within a small angle and magnitude of the first one (about 2.5 s of stillness).
 *
 * Why it exists: [MotionTrigger]'s resting reference never follows a posture the phone settled
 * into (laid face-down after arming in the hand, tipped on a pillow), so its trigger would
 * re-fire every refractory period for the rest of the night. [PickupWindowCollector] uses this
 * test to hand such a new resting posture to [MotionTrigger.settle].
 *
 * Block means, not single samples: a mean of ~25 samples is steady to a few hundredths of a
 * degree on the noisiest sensor profile the classifier was trained for, so the tolerances can be
 * generous. A phone held very steadily in a hand can pass too. That is harmless: the trigger's
 * reference then rests on the in-hand posture and the next movement fires a fresh window.
 *
 * It never decides anything about a shift. [onSample] allocates nothing.
 */
class RestTracker(private val config: Config = Config()) {

    data class Config(
        val blockMillis: Long = 500,
        /** Block means that must agree with the first one of the run. */
        val quietBlocks: Int = 4,
        /** Gravity direction tolerance against the first block of the run, degrees. */
        val maxAngleDegrees: Double = 2.0,
        /** |mean| tolerance against the first block of the run, m/s². */
        val maxMagnitudeDelta: Double = 0.3,
        /** A block mean outside this band is not a phone at rest (free fall, a shove), m/s². */
        val minMagnitude: Double = 0.75 * PickupFeatures.G,
        val maxMagnitude: Double = 1.25 * PickupFeatures.G,
        /** A block with fewer samples than this (under ~10 Hz) proves nothing. */
        val minSamplesPerBlock: Int = 5,
    ) {
        init {
            require(blockMillis > 0 && quietBlocks >= 1 && minSamplesPerBlock >= 1)
            require(maxAngleDegrees in 0.0..90.0 && maxMagnitudeDelta >= 0.0)
            require(minMagnitude > 0.0 && minMagnitude < maxMagnitude)
        }
    }

    private val blockNanos = config.blockMillis * 1_000_000
    private val cosSquared = cos(Math.toRadians(config.maxAngleDegrees)).let { it * it }

    private var blockStart = NONE
    private var lastNanos = NONE
    private var sumX = 0.0
    private var sumY = 0.0
    private var sumZ = 0.0
    private var samples = 0

    // The first block of the current run of agreeing blocks.
    private var anchorX = 0.0
    private var anchorY = 0.0
    private var anchorZ = 0.0
    private var anchorMagnitude = 0.0

    /** Agreeing blocks after the anchor; -1 without an anchor. */
    private var quiet = -1

    /** Gravity at rest (the latest agreeing block mean), m/s². Meaningful while [isAtRest]. */
    var restX = 0.0
        private set
    var restY = 0.0
        private set
    var restZ = 0.0
        private set

    val isAtRest: Boolean get() = quiet >= config.quietBlocks

    /** How much stillness an "at rest" verdict stands on: the first block plus the agreeing ones. */
    val evidenceNanos: Long = (config.quietBlocks + 1) * blockNanos

    /**
     * Feeds one sample. True on a sample that completes a block with the phone at rest (so about
     * twice a second while it stays at rest); [restX], [restY], [restZ] then hold its posture.
     */
    fun onSample(tNanos: Long, x: Float, y: Float, z: Float): Boolean {
        if (blockStart != NONE) {
            if (tNanos == lastNanos) return false // a duplicate timestamp
            // A timestamp that went backwards, or a hole in the stream: nothing is known any more.
            if (tNanos < lastNanos || tNanos - lastNanos > blockNanos) reset()
        }
        if (blockStart == NONE) blockStart = tNanos
        lastNanos = tNanos
        var atRest = false
        if (tNanos - blockStart >= blockNanos) {
            closeBlock()
            atRest = isAtRest
            blockStart = tNanos
            sumX = 0.0
            sumY = 0.0
            sumZ = 0.0
            samples = 0
        }
        sumX += x
        sumY += y
        sumZ += z
        samples++
        return atRest
    }

    fun reset() {
        blockStart = NONE
        lastNanos = NONE
        sumX = 0.0
        sumY = 0.0
        sumZ = 0.0
        samples = 0
        quiet = -1
    }

    private fun closeBlock() {
        if (samples < config.minSamplesPerBlock) {
            quiet = -1
            return
        }
        val mx = sumX / samples
        val my = sumY / samples
        val mz = sumZ / samples
        val squared = mx * mx + my * my + mz * mz
        val magnitude = sqrt(squared)
        // NaN fails both comparisons' complements, so a non-finite block drops the anchor too.
        if (!(magnitude >= config.minMagnitude && magnitude <= config.maxMagnitude)) {
            quiet = -1
            return
        }
        if (quiet >= 0) {
            val dot = mx * anchorX + my * anchorY + mz * anchorZ
            val sameDirection = dot > 0.0 && dot * dot >= cosSquared * squared * (anchorMagnitude * anchorMagnitude)
            if (sameDirection && abs(magnitude - anchorMagnitude) <= config.maxMagnitudeDelta) {
                if (quiet < Int.MAX_VALUE) quiet++
                restX = mx
                restY = my
                restZ = mz
                return
            }
        }
        anchorX = mx
        anchorY = my
        anchorZ = mz
        anchorMagnitude = magnitude
        quiet = 0
    }

    private companion object {
        const val NONE = Long.MIN_VALUE
    }
}
