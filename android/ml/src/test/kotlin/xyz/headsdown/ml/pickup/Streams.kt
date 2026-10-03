package xyz.headsdown.ml.pickup

import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** An accelerometer stream as the sensor thread sees it: primitive arrays, nothing to allocate while feeding. */
class Stream(val t: LongArray, val x: FloatArray, val y: FloatArray, val z: FloatArray) {
    val size: Int get() = t.size

    inline fun forEach(action: (tNanos: Long, x: Float, y: Float, z: Float) -> Unit) {
        for (i in t.indices) action(t[i], x[i], y[i], z[i])
    }
}

/**
 * Builds simple, deterministic streams for the on-device plumbing tests (not classifier accuracy:
 * that is what the Python-generated vectors are for). The posture is the angle of gravity from
 * "screen straight down", so 0 reads (0, 0, -g).
 */
class StreamBuilder(
    seed: Long = 1,
    private val rateHz: Double = 50.0,
    private val noise: Double = 0.02,
    startNanos: Long = 1_000_000_000L,
) {
    private val rnd = Random(seed)
    private val ts = ArrayList<Long>()
    private val xs = ArrayList<Float>()
    private val ys = ArrayList<Float>()
    private val zs = ArrayList<Float>()
    private val stepNanos = (1e9 / rateHz).toLong()

    var nowNanos: Long = startNanos
        private set
    var tiltDegrees: Double = 0.0
        private set

    /** Direction the phone tilts toward (it does not change the tilt angle). */
    var azimuthDegrees: Double = 0.0

    private fun emit(tilt: Double, dx: Double = 0.0, dy: Double = 0.0, dz: Double = 0.0) {
        val a = Math.toRadians(tilt)
        val az = Math.toRadians(azimuthDegrees)
        ts += nowNanos
        xs += (G * sin(a) * cos(az) + dx + rnd.nextGaussian() * noise).toFloat()
        ys += (G * sin(a) * sin(az) + dy + rnd.nextGaussian() * noise).toFloat()
        zs += (-G * cos(a) + dz + rnd.nextGaussian() * noise).toFloat()
        nowNanos += stepNanos
    }

    private fun samples(seconds: Double): Int = (seconds * rateHz).toInt()

    /** The phone lies still in its current posture. */
    fun rest(seconds: Double) = apply { repeat(samples(seconds)) { emit(tiltDegrees) } }

    /** A knock on the furniture: a few samples far off 1 g, the posture unchanged. */
    fun knock(peak: Double = 5.0, samples: Int = 3) = apply {
        repeat(samples) { i -> emit(tiltDegrees, dx = if (i % 2 == 0) peak * 0.4 else -peak * 0.3, dz = if (i % 2 == 0) -peak else peak * 0.5) }
    }

    /** Rotates smoothly to [tiltDegrees] over [seconds] and stays there. */
    fun rotateTo(tiltDegrees: Double, seconds: Double) = apply {
        val from = this.tiltDegrees
        val n = samples(seconds).coerceAtLeast(1)
        for (i in 1..n) emit(from + (tiltDegrees - from) * i / n)
        this.tiltDegrees = tiltDegrees
    }

    /** Held in a hand: the posture sways and the hand trembles. */
    fun sway(seconds: Double, amplitudeDegrees: Double = 5.0, hz: Double = 0.7, tremor: Double = 0.15) = apply {
        val n = samples(seconds)
        for (i in 0 until n) {
            val phase = 2 * PI * hz * i / rateHz
            emit(tiltDegrees + amplitudeDegrees * sin(phase), dx = tremor * sin(9.0 * phase), dz = tremor * cos(11.0 * phase))
        }
    }

    /** The stream stalls: no samples for [seconds]. */
    fun gap(seconds: Double) = apply { nowNanos += (seconds * 1e9).toLong() }

    fun build(): Stream = Stream(ts.toLongArray(), xs.toFloatArray(), ys.toFloatArray(), zs.toFloatArray())

    companion object {
        const val G = 9.80665

        /**
         * A random night-like stream: rest, knocks, tips that stay, lifts, in-hand sway, at a
         * random delivery rate with jitter, duplicated and out-of-order timestamps and holes.
         */
        fun random(seed: Long): Stream {
            val rnd = Random(seed)
            val rate = doubleArrayOf(40.0, 50.0, 62.5, 100.0, 200.0)[rnd.nextInt(5)]
            val b = StreamBuilder(seed, rate, noise = 0.005 + rnd.nextDouble() * 0.05)
            b.rest(1.0 + rnd.nextDouble() * 3.0)
            repeat(6 + rnd.nextInt(6)) {
                when (rnd.nextInt(6)) {
                    0 -> b.knock(peak = 2.0 + rnd.nextDouble() * 8.0, samples = 1 + rnd.nextInt(5))
                    1 -> b.rotateTo(rnd.nextDouble() * 35.0, 0.2 + rnd.nextDouble())
                    2 -> b.rotateTo(40.0 + rnd.nextDouble() * 120.0, 0.3 + rnd.nextDouble() * 2.0)
                    3 -> b.sway(1.0 + rnd.nextDouble() * 4.0, amplitudeDegrees = 2.0 + rnd.nextDouble() * 10.0)
                    4 -> b.gap(0.1 + rnd.nextDouble() * 0.7)
                    else -> b.azimuthDegrees = rnd.nextDouble() * 360.0
                }
                b.rest(rnd.nextDouble() * 5.0)
            }
            val s = b.build()
            // HAL artefacts: jitter, a few repeated timestamps, a few that go backwards.
            val t = s.t.copyOf()
            for (i in t.indices) {
                t[i] += (rnd.nextGaussian() * 300_000).toLong()
                if (i > 0 && rnd.nextInt(200) == 0) t[i] = t[i - 1]
                if (i > 1 && rnd.nextInt(300) == 0) t[i] = t[i - 2] - 1
            }
            return Stream(t, s.x, s.y, s.z)
        }
    }
}
