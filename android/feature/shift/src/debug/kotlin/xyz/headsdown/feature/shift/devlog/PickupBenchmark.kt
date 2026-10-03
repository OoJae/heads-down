package xyz.headsdown.feature.shift.devlog

import xyz.headsdown.ml.PickupClassifier
import xyz.headsdown.ml.pickup.PickupFeatures
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin
import xyz.headsdown.ml.AccelSample as Sample
import xyz.headsdown.ml.MotionWindow as Window

/*
 * DEBUG BUILDS ONLY (src/debug). Times the pickup classifier on this device: how long one motion
 * window takes from raw samples to a verdict. The shift service runs that once per motion of a
 * hot rig, on a background thread; this is the number to check on the Redmi 14C.
 */
object PickupBenchmark {

    data class Result(
        val model: String,
        val windows: Int,
        val calls: Int,
        /** Whole classify(): cleaning, resampling, 39 features, the model. Microseconds per window. */
        val medianMicros: Double,
        val p95Micros: Double,
        val maxMicros: Double,
        /** The feature pipeline alone (the part every model shares). */
        val featureMedianMicros: Double,
        val pickups: Int,
    ) {
        fun summary(): String = String.format(
            Locale.ROOT,
            "%s: median %.0f µs, p95 %.0f µs, max %.0f µs per window (features alone %.0f µs); %d windows x %d passes, %d of %d called a pickup",
            model, medianMicros, p95Micros, maxMicros, featureMedianMicros, windows, calls / windows.coerceAtLeast(1), pickups, windows,
        )
    }

    /**
     * Classifies every window [passes] times after [warmupPasses] untimed ones (so the runtime
     * has compiled the code), timing each call. Run it off the main thread.
     */
    fun run(
        classifier: PickupClassifier,
        windows: List<Window>,
        warmupPasses: Int = 30,
        passes: Int = 100,
        nanoTime: () -> Long = System::nanoTime,
    ): Result {
        require(windows.isNotEmpty() && passes > 0 && warmupPasses >= 0)
        var pickups = 0
        repeat(warmupPasses) { for (w in windows) classifier.classify(w) }
        val whole = LongArray(windows.size * passes)
        val features = LongArray(windows.size * passes)
        var n = 0
        repeat(passes) { pass ->
            for (w in windows) {
                val t0 = nanoTime()
                val decision = classifier.classify(w)
                val t1 = nanoTime()
                PickupFeatures.extract(w)
                val t2 = nanoTime()
                whole[n] = t1 - t0
                features[n] = t2 - t1
                n++
                if (pass == 0 && decision.isPickup) pickups++
            }
        }
        whole.sort()
        features.sort()
        fun LongArray.at(q: Double) = this[((size - 1) * q).toInt()] / 1_000.0
        return Result(
            model = classifier.modelName,
            windows = windows.size,
            calls = whole.size,
            medianMicros = whole.at(0.5),
            p95Micros = whole.at(0.95),
            maxMicros = whole.at(1.0),
            featureMedianMicros = features.at(0.5),
            pickups = pickups,
        )
    }

    /**
     * Stand-in windows for a phone with no recordings on it: 2 s face-down at rest, then a
     * knock, a lift that turns the phone over, a flat carry, or a slide, at 50 Hz. The cost of
     * a window does not depend on what is in it (fixed-size resampling and features), so these
     * time the same work as real ones. They say nothing about accuracy.
     */
    fun syntheticWindows(): List<Window> = List(8) { index ->
        val trigger = 10_000_000_000L
        val step = 20_000_000L
        val samples = ArrayList<Sample>(252)
        var t = trigger - 2_000_000_000L
        var i = 0
        while (t <= trigger + 3_000_000_000L + step) {
            val s = (t - trigger) / 1e9 // seconds from the trigger
            val wobble = 0.02 * sin(i * 1.7 + index)
            var tilt = 0.0
            var jolt = 0.0
            var push = 0.0
            when (index % 4) {
                0 -> if (s in 0.0..0.08) jolt = 4.0 * cos(i * 2.1) // a knock
                1 -> if (s > 0) tilt = minOf(150.0, s * 120.0) // picked up and turned over
                2 -> if (s > 0) { tilt = minOf(15.0, s * 20.0); jolt = 0.4 * sin(s * 9.0) } // carried flat
                else -> if (s in 0.0..0.8) push = 1.5 * sin(s * 8.0) // slid across the table
            }
            val a = Math.toRadians(tilt)
            samples += Sample(
                t,
                (G * sin(a) + push + wobble).toFloat(),
                (wobble * 0.5).toFloat(),
                (-G * cos(a) + jolt + wobble).toFloat(),
            )
            t += step
            i++
        }
        Window(trigger, samples)
    }

    private const val G = 9.80665
}
