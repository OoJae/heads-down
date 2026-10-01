package xyz.headsdown.ml.pickup

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import xyz.headsdown.ml.AccelSample
import xyz.headsdown.ml.Allocations
import xyz.headsdown.ml.FailClosedReason
import xyz.headsdown.ml.MotionWindow
import xyz.headsdown.ml.PickupVerdict
import xyz.headsdown.ml.TestResources
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * The sensor-thread side of the pickup classifier: the allocation-free trigger and collector must
 * behave exactly like the straightforward versions they replaced, tell a fresh motion from a
 * re-fire, and come back to rest after the phone settles somewhere new.
 */
class PickupStreamTest {

    // ------------------------------------------------------------------ parity with the originals

    /** The trigger as first written (arrays allocated per sample). The specification of [MotionTrigger.onSample]. */
    private class ReferenceTrigger {
        private var fast: DoubleArray? = null
        private var slow: DoubleArray? = null
        private var restMagnitude = 0.0
        private var lastNanos = 0L
        private var lastTriggerNanos = Long.MIN_VALUE

        fun onSample(tNanos: Long, x: Float, y: Float, z: Float): Boolean {
            val v = doubleArrayOf(x.toDouble(), y.toDouble(), z.toDouble())
            val mag = norm(v)
            val f = fast
            val sl = slow
            if (f == null || sl == null) {
                fast = v.copyOf()
                slow = v.copyOf()
                restMagnitude = mag
                lastNanos = tNanos
                return false
            }
            if (tNanos <= lastNanos) return false
            val dtMillis = (tNanos - lastNanos) / 1e6
            lastNanos = tNanos
            blend(f, v, dtMillis / (80.0 + dtMillis))
            val tilt = angleDegrees(f, sl)
            val jolt = abs(mag - restMagnitude)
            val moving = jolt > 1.2 || tilt > 12.0
            if (!moving) {
                val a = dtMillis / (2_000.0 + dtMillis)
                blend(sl, f, a)
                restMagnitude += a * (mag - restMagnitude)
            }
            val refractory = lastTriggerNanos != Long.MIN_VALUE && tNanos - lastTriggerNanos < 3_000L * 1_000_000
            if (moving && !refractory) {
                lastTriggerNanos = tNanos
                return true
            }
            return false
        }

        private fun blend(into: DoubleArray, target: DoubleArray, alpha: Double) {
            for (i in 0..2) into[i] += alpha * (target[i] - into[i])
        }

        private fun norm(v: DoubleArray) = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])

        private fun angleDegrees(a: DoubleArray, b: DoubleArray): Double {
            val na = norm(a)
            val nb = norm(b)
            if (na < 1e-6 || nb < 1e-6) return 0.0
            val cos = ((a[0] * b[0] + a[1] * b[1] + a[2] * b[2]) / (na * nb)).coerceIn(-1.0, 1.0)
            return Math.toDegrees(acos(cos))
        }
    }

    /** The collector as first written (an ArrayDeque of sample objects, copied on every trigger). */
    private class ReferenceCollector(private val onWindow: (MotionWindow) -> Unit) {
        private val trigger = ReferenceTrigger()
        private val ring = ArrayDeque<AccelSample>()
        private val pending = ArrayList<Pair<Long, ArrayList<AccelSample>>>()
        private var lastNanos = Long.MIN_VALUE

        fun onSample(s: AccelSample) {
            if (s.tNanos <= lastNanos) return
            lastNanos = s.tNanos
            val fired = trigger.onSample(s.tNanos, s.x, s.y, s.z)
            ring.addLast(s)
            while (ring.isNotEmpty() && s.tNanos - ring.first().tNanos > 2_000_000_000L) ring.removeFirst()
            val it = pending.iterator()
            while (it.hasNext()) {
                val p = it.next()
                p.second += s
                if (s.tNanos >= p.first + 3_000_000_000L) {
                    it.remove()
                    onWindow(MotionWindow(p.first, p.second))
                }
            }
            if (fired) pending += s.tNanos to ArrayList(ring)
        }
    }

    @Test
    fun `the allocation-free trigger fires on exactly the samples the original did`() {
        var fires = 0
        for (seed in 0L until 300) {
            val stream = StreamBuilder.random(seed)
            val reference = ReferenceTrigger()
            val trigger = MotionTrigger()
            stream.forEach { t, x, y, z ->
                val want = reference.onSample(t, x, y, z)
                assertEquals("seed $seed at $t", want, trigger.onSample(t, x, y, z))
                if (want) fires++
            }
        }
        assertTrue("the random streams exercise the trigger ($fires fires)", fires > 500)
    }

    @Test
    fun `the ring-buffer collector cuts exactly the windows the original did`() {
        var cut = 0
        for (seed in 0L until 300) {
            val stream = StreamBuilder.random(seed)
            val want = ArrayList<MotionWindow>()
            val got = ArrayList<MotionWindow>()
            val reference = ReferenceCollector { want += it }
            val collector = PickupWindowCollector { got += it }
            stream.forEach { t, x, y, z ->
                reference.onSample(AccelSample(t, x, y, z))
                collector.onSample(t, x, y, z)
            }
            assertEquals("seed $seed windows", want.map { it.triggerNanos }, got.map { it.triggerNanos })
            for (i in want.indices) assertEquals("seed $seed window $i", want[i].samples, got[i].samples)
            cut += want.size
        }
        assertTrue("the random streams produce windows ($cut)", cut > 300)
    }

    // ------------------------------------------------------------------ onset or re-fire

    private class Recorder : PickupWindowCollector.Listener {
        val triggers = ArrayList<Pair<Long, Boolean>>()
        val windows = ArrayList<Pair<MotionWindow, Int>>()
        override fun onTrigger(triggerNanos: Long, onset: Boolean): Int {
            triggers += triggerNanos to onset
            return triggers.size
        }

        override fun onWindow(window: MotionWindow, tag: Int) {
            windows += window to tag
        }
    }

    @Test
    fun `a knock is an onset, and a posture that stays re-fires as not-an-onset`() {
        val stream = StreamBuilder().rest(4.0).knock().rest(6.0).rotateTo(25.0, 0.5).rest(10.0).build()
        val recorder = Recorder()
        val trigger = MotionTrigger()
        val collector = PickupWindowCollector(trigger = trigger, listener = recorder)
        stream.forEach(collector::onSample)
        assertEquals("the knock, the tip, and re-fires every 3 s while the phone stays tipped", 5, recorder.triggers.size)
        assertEquals(listOf(true, true, false, false, false), recorder.triggers.map { it.second })
        // Windows come back with the tag their trigger was given, in trigger order.
        assertEquals(listOf(1, 2, 3, 4), recorder.windows.map { it.second })
        assertEquals(recorder.triggers.take(4).map { it.first }, recorder.windows.map { it.first.triggerNanos })
        assertEquals("the plain trigger never re-rests", 0, trigger.settles)
    }

    @Test
    fun `the vector stream's third trigger is a re-fire of the rotation`() {
        val trig = TestResources.json("/foreman/pickup_vectors.json")["trigger"]!!
        val stream = TestResources.stream(trig)
        val recorder = Recorder()
        val collector = PickupWindowCollector(listener = recorder)
        stream.forEach(collector::onSample)
        assertEquals("a knock and the start of the rotation are onsets; the rotation outlasting the refractory period is not",
            listOf(true, true, false), recorder.triggers.map { it.second })
    }

    // ------------------------------------------------------------------ coming back to rest

    @Test
    fun `the plain trigger re-fires all night once the phone is laid down in a new posture`() {
        // Armed in the hand (tilted 150 degrees from screen-down), then laid face-down.
        val stream = StreamBuilder().rotateTo(150.0, 0.02).sway(3.0).rotateTo(0.0, 1.0).rest(60.0).build()
        val recorder = Recorder()
        val collector = PickupWindowCollector(listener = recorder)
        stream.forEach(collector::onSample)
        assertTrue("re-fires every refractory period: ${recorder.triggers.size}", recorder.triggers.size >= 18)
    }

    @Test
    fun `the live trigger settles on the new posture and is ready for the next motion`() {
        val b = StreamBuilder().rotateTo(150.0, 0.02).sway(3.0).rotateTo(0.0, 1.0)
        val laidDown = b.nowNanos
        val stream = b.rest(30.0).knock().rest(10.0).rotateTo(20.0, 0.4).rest(20.0).knock().rest(6.0).build()
        val recorder = Recorder()
        val trigger = MotionTrigger.live()
        val collector = PickupWindowCollector(trigger = trigger, listener = recorder)
        stream.forEach(collector::onSample)

        val afterLayDown = recorder.triggers.filter { it.first > laidDown }
        val onceSettled = afterLayDown.filter { it.first > laidDown + 4_000_000_000L }
        assertEquals("once at rest: the first knock, the tip and the second knock are onsets", 3, onceSettled.count { it.second })
        // A re-fire can still land in the 2.5 s it takes to call the phone at rest: once after
        // the lay-down, once after the tip. Never again after that.
        assertTrue("re-fires only while settling: $afterLayDown", afterLayDown.count { !it.second } <= 2)
        assertTrue(onceSettled.count { !it.second } <= 1)
        assertEquals("settled after the lay-down and after the tip", 2, trigger.settles)
        assertFalse(trigger.isMoving)
    }

    @Test
    fun `a knock never settles the trigger, wherever it lands in the rest tracker's blocks`() {
        // "At rest" is about the block that just ended, "moving" about the sample that ended it.
        // Settling on that pair would clear the refractory period mid-knock and fire twice.
        for (offset in 0 until 25) {
            val stream = StreamBuilder(seed = offset.toLong()).rest(6.0 + offset * 0.02).knock(samples = 4).rest(8.0).build()
            val recorder = Recorder()
            val trigger = MotionTrigger.live()
            val collector = PickupWindowCollector(trigger = trigger, listener = recorder)
            stream.forEach(collector::onSample)
            assertEquals("offset $offset: one knock, one trigger", listOf(true), recorder.triggers.map { it.second })
            assertEquals("offset $offset", 0, trigger.settles)
        }
    }

    @Test
    fun `on streams that start at rest the live trigger fires exactly where the plain one does`() {
        // Every training stream starts with the phone lying there, and its window is the first
        // trigger. The live trigger must not move that sample.
        val vectors = TestResources.json("/foreman/pickup_vectors.json")
        var compared = 0
        for (w in vectors["windows"]!!.jsonArray) {
            val input = w.jsonObject["input"]!!
            val stream = TestResources.stream(input)
            val plain = MotionTrigger()
            val live = MotionTrigger.live()
            var first: Long? = null
            var firstLive: Long? = null
            stream.forEach { t, x, y, z ->
                if (plain.onSample(t, x, y, z) && first == null) first = t
                if (live.onSample(t, x, y, z) && firstLive == null) firstLive = t
            }
            assertEquals(w.jsonObject["name"].toString(), first, firstLive)
            if (first != null) compared++
        }
        assertTrue(compared >= 20)
    }

    @Test
    fun `settle makes the given posture the resting reference at once`() {
        val trigger = MotionTrigger()
        val g = StreamBuilder.G.toFloat()
        var t = 0L
        fun feed(x: Float, y: Float, z: Float): Boolean {
            t += 20_000_000
            return trigger.onSample(t, x, y, z)
        }
        feed(0f, 0f, g) // face-up in the hand
        assertTrue("turned over: far from the reference", (0 until 20).any { feed(0f, 0f, -g) })
        assertTrue(trigger.isMoving)
        trigger.settle(0.0, 0.0, -StreamBuilder.G)
        assertFalse(trigger.isMoving)
        assertFalse("at rest on the new reference", (0 until 500).any { feed(0f, 0f, -g) })
        assertTrue("the refractory period was cleared: a jolt fires at once", feed(0f, 0f, -g - 4f))
        trigger.settle(Double.NaN, 0.0, 0.0)
        assertTrue("non-finite input is ignored", trigger.isMoving)
    }

    // ------------------------------------------------------------------ rest tracker

    private fun atRestAfter(stream: Stream, tracker: RestTracker = RestTracker()): Long? {
        var first: Long? = null
        stream.forEach { t, x, y, z -> if (tracker.onSample(t, x, y, z) && first == null) first = t }
        return first
    }

    @Test
    fun `a still phone is at rest after about two and a half seconds, whatever its posture`() {
        for (tilt in listOf(0.0, 15.0, 39.0, 170.0)) {
            val b = StreamBuilder(noise = 0.02).rotateTo(tilt, 0.02)
            val start = b.nowNanos
            val tracker = RestTracker()
            val at = atRestAfter(b.rest(10.0).build(), tracker)
            assertNotNull("tilt $tilt", at)
            val seconds = (at!! - start) / 1e9
            assertTrue("tilt $tilt: at rest after $seconds s", seconds in 2.4..3.1)
            assertTrue(tracker.isAtRest)
            assertEquals(-StreamBuilder.G * kotlin.math.cos(Math.toRadians(tilt)), tracker.restZ, 0.05)
        }
    }

    @Test
    fun `the noisiest trained sensor profile still comes to rest`() {
        // 0.06 m/s² white noise, quantized to 0.077 m/s² (ml/foreman MODEL_CARD, held-out sensors).
        val b = StreamBuilder(seed = 9, rateHz = 40.0, noise = 0.06)
        val s = b.rest(20.0).build()
        val q = 0.077f
        for (i in 0 until s.size) {
            s.x[i] = Math.round(s.x[i] / q) * q
            s.y[i] = Math.round(s.y[i] / q) * q
            s.z[i] = Math.round(s.z[i] / q) * q
        }
        val tracker = RestTracker()
        var restBlocks = 0
        s.forEach { t, x, y, z -> if (tracker.onSample(t, x, y, z)) restBlocks++ }
        assertTrue("at rest for nearly every block: $restBlocks", restBlocks >= 33)
    }

    @Test
    fun `a swaying hand, free fall, a sparse stream and a stalled stream are not rest`() {
        assertEquals(null, atRestAfter(StreamBuilder().sway(30.0, amplitudeDegrees = 6.0).build()))
        // Free fall reads about 0 g: steady, but not a phone lying anywhere.
        val n = 500
        val fall = Stream(LongArray(n) { i -> (i + 1) * 20_000_000L }, FloatArray(n) { 0.1f }, FloatArray(n), FloatArray(n) { -0.2f })
        assertEquals(null, atRestAfter(fall))
        assertEquals("4 Hz proves nothing", null, atRestAfter(StreamBuilder(rateHz = 4.0).rest(30.0).build()))
        // A hole longer than a block restarts the count: 2 s, a 1 s hole, 2 s is never 2.5 s of rest.
        assertEquals(null, atRestAfter(StreamBuilder().rest(2.0).gap(1.0).rest(2.0).build()))
    }

    @Test
    fun `a new posture restarts the count, and duplicate timestamps change nothing`() {
        val b = StreamBuilder().rest(5.0)
        val tipped = b.rotateTo(15.0, 0.3).nowNanos
        val stream = b.rest(5.0).build()
        val tracker = RestTracker()
        val restAt = ArrayList<Long>()
        stream.forEach { t, x, y, z ->
            if (tracker.onSample(t, x, y, z)) restAt += t
            assertFalse("a repeated timestamp is ignored", tracker.onSample(t, 50f, 50f, 50f))
        }
        val firstAfterTip = restAt.first { it > tipped }
        assertTrue("no rest reported while it tipped and re-counted", restAt.none { it in tipped..(tipped + 2_000_000_000L) })
        assertTrue((firstAfterTip - tipped) / 1e9 in 2.4..3.2)
        assertEquals(StreamBuilder.G * kotlin.math.sin(Math.toRadians(15.0)), tracker.restX, 0.05)
    }

    // ------------------------------------------------------------------ robustness

    @Test
    fun `a non-finite sample is skipped instead of poisoning the trigger`() {
        val stream = StreamBuilder().rest(3.0).build()
        val after = StreamBuilder(startNanos = stream.t.last() + 40_000_000).rest(1.0).knock().rest(4.0).build()
        val windows = ArrayList<MotionWindow>()
        val collector = PickupWindowCollector { windows += it }
        stream.forEach(collector::onSample)
        collector.onSample(stream.t.last() + 20_000_000, Float.NaN, 0f, -9.8f)
        collector.onSample(stream.t.last() + 21_000_000, 0f, Float.POSITIVE_INFINITY, -9.8f)
        after.forEach(collector::onSample)
        assertEquals("the knock after the bad samples still fires", 1, windows.size)
        assertTrue(windows.single().samples.all { it.x.isFinite() && it.y.isFinite() && it.z.isFinite() })

        // The trigger on its own (the sensor lab feeds it directly) ignores them too.
        val trigger = MotionTrigger()
        var fires = 0
        stream.forEach { t, x, y, z -> if (trigger.onSample(t, x, y, z)) fires++ }
        assertFalse(trigger.onSample(stream.t.last() + 20_000_000, 0f, 0f, Float.NaN))
        after.forEach { t, x, y, z -> if (trigger.onSample(t, x, y, z)) fires++ }
        assertEquals(1, fires)
    }

    @Test
    fun `history is bounded, and a window the ring could not hold fails closed`() {
        val doc = PickupModelDocument.parse(TestResources.asset("pickup_model.json").readText())
        // 64 samples of history at 50 Hz is 1.3 s: every 5 s window loses its first 3.7 s.
        val windows = ArrayList<MotionWindow>()
        val collector = PickupWindowCollector(maxSamplesPerWindow = 64) { windows += it }
        StreamBuilder().rest(6.0).knock().rest(6.0).build().forEach(collector::onSample)
        val w = windows.single()
        assertEquals(64, w.samples.size)
        val d = ModelPickupClassifier(doc).classify(w)
        assertEquals(PickupVerdict.PICKUP, d.verdict)
        assertEquals(FailClosedReason.TOO_FEW_SAMPLES, d.failClosed)

        // 200 samples keep the last 4 s: enough samples, but the first second is missing (a gap).
        windows.clear()
        val wider = PickupWindowCollector(maxSamplesPerWindow = 200) { windows += it }
        StreamBuilder().rest(6.0).knock().rest(6.0).build().forEach(wider::onSample)
        assertEquals(FailClosedReason.GAP, ModelPickupClassifier(doc).classify(windows.single()).failClosed)
    }

    @Test
    fun `reset from a listener ends the sample cleanly`() {
        lateinit var collector: PickupWindowCollector
        var windows = 0
        collector = PickupWindowCollector(listener = object : PickupWindowCollector.Listener {
            override fun onWindow(window: MotionWindow, tag: Int) {
                windows++
                collector.reset()
            }
        })
        StreamBuilder().rest(3.0).knock().rest(4.0).knock().rest(4.0).build().forEach(collector::onSample)
        assertEquals("the second knock comes less than 2 s after the reset, so it has no history but still closes", 2, windows)
        assertEquals(0, collector.openWindows)
    }

    // ------------------------------------------------------------------ the sample path allocates nothing

    @Test
    fun `feeding samples allocates nothing while no window closes`() {
        assumeTrue("needs HotSpot's per-thread allocation counter", Allocations.supported)
        // Warm up past the JIT's tiering thresholds: while HotSpot recompiles a method it can
        // charge a few hundred bytes of its own to the thread, which is not the code under test.
        val warm = StreamBuilder(seed = 3).rest(800.0).build()
        val night = StreamBuilder(seed = 4, startNanos = warm.t.last() + 20_000_000).rest(1_200.0).build()

        val trigger = MotionTrigger()
        val collector = PickupWindowCollector(trigger = MotionTrigger.live(), listener = Recorder())
        val original = ReferenceTrigger()
        // Load every class and take every branch of the quiet path once before measuring.
        warm.forEach { t, x, y, z ->
            trigger.onSample(t, x, y, z)
            collector.onSample(t, x, y, z)
            original.onSample(t, x, y, z)
        }

        val bytes = Allocations.during {
            night.forEach { t, x, y, z ->
                trigger.onSample(t, x, y, z)
                collector.onSample(t, x, y, z)
            }
        }
        // The counter does see a per-sample allocation when there is one: the trigger as first
        // written made one small array per sample (the build disables escape analysis for tests,
        // so HotSpot cannot optimize it away where ART would not).
        val before = Allocations.during { night.forEach { t, x, y, z -> original.onSample(t, x, y, z) } }
        println("sample path: $bytes bytes allocated over ${night.size} samples (plain trigger + collector with the live trigger); the original trigger alone: $before bytes")
        // One boxed Long per sample would be ~960,000 bytes here, one per rest-tracker block
        // ~38,000. The slack is for the JVM's own one-off allocations.
        assertTrue("the sample path allocated $bytes bytes over ${night.size} samples", bytes <= 2_048)
        assertTrue("the original trigger allocated per sample: $before", before >= 16L * night.size)
        assertEquals(0, collector.openWindows)
    }
}
