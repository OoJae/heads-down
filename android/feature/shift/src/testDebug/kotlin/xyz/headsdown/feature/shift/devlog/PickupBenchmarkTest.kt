package xyz.headsdown.feature.shift.devlog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.headsdown.feature.shift.foreman.ForemanVectors
import xyz.headsdown.ml.PickupClassifier
import xyz.headsdown.ml.PickupDecision
import xyz.headsdown.ml.PickupVerdict
import xyz.headsdown.ml.AccelSample as Sample
import xyz.headsdown.ml.MotionWindow as Window

/**
 * The debug benchmark, and the JVM measurement it gives: a rough proxy for the phone (a laptop
 * core under HotSpot, here without escape analysis, against the Redmi's Cortex-A75 under ART).
 */
class PickupBenchmarkTest {

    private fun recorded(): List<Window> = ForemanVectors.windows.filter { it.expectedPickup != null }.map { w ->
        val samples = ArrayList<Sample>(w.stream.size)
        w.stream.forEach { t, x, y, z -> samples += Sample(t, x, y, z) }
        Window(w.triggerNanos, samples)
    }

    @Test
    fun `per-window inference time of the shipped model on the JVM`() {
        val windows = recorded()
        assertTrue(windows.size >= 20)
        val classifier = ForemanVectors.shippedClassifier()
        val result = PickupBenchmark.run(classifier, windows, warmupPasses = 200, passes = 400)
        println("JVM benchmark, recorded vector windows: ${result.summary()}")
        assertEquals(windows.size * 400, result.calls)
        assertEquals("the timed model is the shipped one, and it answers as the vectors say",
            ForemanVectors.windows.count { it.expectedPickup == true }, result.pickups)
        assertTrue("a window takes microseconds, not milliseconds: ${result.medianMicros} µs", result.medianMicros < 5_000.0)
        assertTrue(result.featureMedianMicros <= result.p95Micros && result.medianMicros <= result.p95Micros && result.p95Micros <= result.maxMicros)
    }

    @Test
    fun `the on-device stand-in windows are usable and cost the same work`() {
        val windows = PickupBenchmark.syntheticWindows()
        assertEquals(8, windows.size)
        val classifier = ForemanVectors.shippedClassifier()
        for (w in windows) {
            val d = classifier.classify(w)
            assertNull("a stand-in window must reach the model, not fail closed", d.failClosed)
            assertTrue(w.samples.size in 250..253)
        }
        val result = PickupBenchmark.run(classifier, windows, warmupPasses = 100, passes = 200)
        println("JVM benchmark, stand-in windows: ${result.summary()}")
        assertTrue(result.medianMicros < 5_000.0)
    }

    @Test
    fun `timings are the differences of the clock, sorted into median, p95 and max`() {
        // The benchmark reads the clock three times per call: before classify, after it, and
        // after the feature pass. This clock makes classify take 1, 2, 3, ... ms and the feature
        // pass 0.5 ms every time.
        var now = 0L
        var reads = 0
        val clock = {
            when (reads % 3) {
                1 -> now += (reads / 3 + 1) * 1_000_000L
                2 -> now += 500_000L
            }
            reads++
            now
        }
        val model = object : PickupClassifier {
            override val modelName = "stand-in"
            override fun classify(window: Window) = PickupDecision(0.99, 5.0, PickupVerdict.PICKUP, null, modelName)
        }
        val windows = PickupBenchmark.syntheticWindows().take(2)
        val r = PickupBenchmark.run(model, windows, warmupPasses = 0, passes = 5, nanoTime = clock)
        assertEquals(10, r.calls)
        assertEquals("1..10 ms: the middle of ten", 5_000.0, r.medianMicros, 0.0)
        assertEquals(9_000.0, r.p95Micros, 0.0)
        assertEquals(10_000.0, r.maxMicros, 0.0)
        assertEquals(500.0, r.featureMedianMicros, 0.0)
        assertEquals("pickups are counted once per window, not per pass", 2, r.pickups)
        assertTrue(r.summary().startsWith("stand-in: median 5000 µs"))
    }
}
