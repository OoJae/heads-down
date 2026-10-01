package xyz.headsdown.feature.shift.foreman

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import xyz.headsdown.feature.shift.BreakReason
import xyz.headsdown.feature.shift.CoolReason
import xyz.headsdown.feature.shift.FaceDownDetector
import xyz.headsdown.feature.shift.FakeClock
import xyz.headsdown.feature.shift.ShiftEffect
import xyz.headsdown.feature.shift.ShiftEvent
import xyz.headsdown.feature.shift.ShiftMode
import xyz.headsdown.feature.shift.ShiftSpec
import xyz.headsdown.feature.shift.ShiftState
import xyz.headsdown.feature.shift.ShiftStateMachine
import xyz.headsdown.feature.shift.Signals
import xyz.headsdown.feature.shift.Transition
import xyz.headsdown.ml.FailClosedReason
import xyz.headsdown.ml.ForemanGate
import xyz.headsdown.ml.ForemanModels
import xyz.headsdown.ml.MotionWindow
import xyz.headsdown.ml.PickupClassifier
import xyz.headsdown.ml.PickupDecision
import xyz.headsdown.ml.PickupVerdict
import xyz.headsdown.ml.pickup.FailClosedPickupClassifier
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/**
 * The pickup classifier wired into a shift: which motion windows it is asked about, and what its
 * answer can do. Real-model checks replay the recorded synthetic windows of :ml's vectors; the
 * gating checks use stand-in classifiers, so they hold whatever the model says.
 */
class PickupWatchTest {

    private val direct = Executor { it.run() }

    /** Says PICKUP to everything it is shown: a window is judged iff this one breaks. */
    private class Always(private val verdict: PickupVerdict) : PickupClassifier {
        override val modelName = "stand-in"
        val seen = ArrayList<MotionWindow>()
        override fun classify(window: MotionWindow): PickupDecision {
            seen += window
            return PickupDecision(if (verdict == PickupVerdict.PICKUP) 0.99 else 0.01, 0.0, verdict, null, modelName)
        }
    }

    /**
     * The shift service's wiring of detector, watch and machine, on one thread: samples feed the
     * detector and the watch, every transition tells the watch whether the rig is hot, and a
     * verdict is dispatched only to a rig that is still DOWN.
     */
    private inner class Rig(classifier: PickupClassifier, spec: ShiftSpec = ShiftSpec(7, ShiftMode.NIGHT)) {
        val clock = FakeClock(0)
        val machine = ShiftStateMachine(clock, initialSignals = Signals(faceDown = false, screenOn = true, charging = true))
        val detector = FaceDownDetector()
        val pickups = ArrayList<PickupDecision>()
        val transitions = ArrayList<Transition>()
        val watch = PickupWatch({ classifier }, direct, onPickup = { pickups += it; onVerdict() })
        private var lastVerdict = false

        init {
            dispatch(ShiftEvent.Arm(spec))
            dispatch(ShiftEvent.ScreenOff)
        }

        fun dispatch(event: ShiftEvent): Transition {
            val t = machine.dispatch(event)
            transitions += t
            watch.onRig(hot = t.to is ShiftState.Down && !t.signals.screenOn)
            return t
        }

        private fun onVerdict() {
            if (machine.state is ShiftState.Down) dispatch(ShiftEvent.PickupDetected)
            watch.clear()
        }

        fun feed(stream: Stream) {
            stream.forEach { t, x, y, z ->
                clock.now = t / 1_000_000
                val verdict = detector.onSample(x, y, z, t)
                watch.onSample(t, x, y, z)
                if (verdict != lastVerdict) {
                    lastVerdict = verdict
                    dispatch(ShiftEvent.Posture(verdict))
                }
            }
        }
    }

    // ------------------------------------------------------------------ recorded windows, real model

    @Test
    fun `the shipped model breaks on exactly the recorded pickups`() {
        val usable = ForemanVectors.replayable
        var pickups = 0
        var others = 0
        for (w in usable) {
            val broke = ArrayList<PickupDecision>()
            val watch = PickupWatch({ ForemanVectors.shippedClassifier() }, direct, onPickup = { broke += it })
            watch.onRig(hot = true)
            w.stream.forEach(watch::onSample)
            assertEquals("${w.name}: the window was cut and judged", 1, watch.stats.judged)
            assertEquals("${w.name} (${w.label})", w.expectedPickup, broke.isNotEmpty())
            if (broke.isNotEmpty()) {
                assertEquals(1, broke.size)
                assertEquals("${w.name}: BREAK reason 1", 1, ForemanGate.breakReason(broke.single()))
                assertNull("${w.name}: a real verdict, not a fail-closed one", broke.single().failClosed)
                assertSame(broke.single(), watch.veto)
                pickups++
            } else {
                assertNull(watch.veto)
                others++
            }
        }
        assertTrue("the vectors cover both classes ($pickups pickups, $others others)", pickups >= 5 && others >= 5)
        assertTrue("every recorded label is exercised", usable.map { it.label }.toSet().containsAll(setOf("pickup", "bump", "slide")))
    }

    @Test
    fun `on a real shift every recorded pickup leaves DOWN for reason 1, and no bump or slide does`() {
        var byClassifier = 0
        var byTilt = 0
        var quiet = 0
        for (w in ForemanVectors.replayable) {
            val rig = Rig(ForemanVectors.shippedClassifier())
            rig.feed(w.stream)
            val wentHot = rig.transitions.any { it.to is ShiftState.Down }
            assertTrue("${w.name}: the rig was hot before the motion", wentHot)
            val cooled = rig.transitions.any { (it.to as? ShiftState.Cooling)?.reason == CoolReason.LIFTED }
            val broken = rig.machine.state as? ShiftState.Broken
            if (w.label == "pickup") {
                assertTrue("${w.name}: neither the tilt rule nor the classifier caught it", cooled || broken != null)
                if (rig.pickups.isNotEmpty()) {
                    // The lift never tilted past the detector's exit angle: only the model saw it.
                    assertEquals("${w.name}", BreakReason.LIFTED, broken!!.reason)
                    val breaking = rig.transitions.single { it.from is ShiftState.Down && it.to is ShiftState.Broken }
                    assertEquals(listOf<ShiftEffect>(ShiftEffect.SignBreak(broken.spec, BreakReason.LIFTED)), breaking.effects)
                    assertTrue("the journal's first pickup", breaking.isPickup)
                    assertFalse("the detector's own rule had not fired", cooled)
                    byClassifier++
                } else {
                    assertTrue("${w.name}: the tilt rule cooled it, and the grace window is its own", cooled)
                    assertEquals("a window whose rig left DOWN is not judged", 0, rig.watch.stats.judged)
                    byTilt++
                }
            } else {
                assertTrue("${w.name} (${w.label}) broke the shift", rig.pickups.isEmpty() && broken == null)
                assertTrue("${w.name} (${w.label}) is still hot", rig.machine.state is ShiftState.Down)
                quiet++
            }
        }
        println("recorded windows on a shift: $byClassifier pickups broken by the classifier, $byTilt cooled by the tilt rule, $quiet bumps/slides ignored")
        assertTrue("flat carries are what the classifier adds ($byClassifier)", byClassifier >= 2)
        assertTrue(byTilt >= 3 && quiet >= 5)
    }

    @Test
    fun `the vectors' free-running stream is judged at its two onsets only`() {
        // Rest, a knock, then a slow rotation that stays: the trigger fires three times, the
        // third as a re-fire of the rotation. Asked what it sees, the watch shows two windows.
        val model = Always(PickupVerdict.NOT_PICKUP)
        val watch = PickupWatch({ model }, direct, onPickup = { error("NOT_PICKUP must not call back") })
        watch.onRig(hot = true)
        ForemanVectors.triggerStream.forEach(watch::onSample)
        val fires = ForemanVectors.triggerStreamFires
        assertEquals(3, fires.size)
        assertEquals("the knock and the start of the rotation", fires.take(2), model.seen.map { it.triggerNanos })
        assertEquals(PickupWatch.Stats(triggers = 3, reFires = 1, judged = 2), watch.stats)
    }

    // ------------------------------------------------------------------ which windows are judged

    @Test
    fun `nothing is judged while the rig is not hot`() {
        val model = Always(PickupVerdict.PICKUP)
        val watch = PickupWatch({ model }, direct, onPickup = { error("not hot: no break") })
        StreamBuilder().rest(5.0).knock().rest(5.0).knock().rest(5.0).build().forEach(watch::onSample)
        assertTrue(model.seen.isEmpty())
        assertEquals(PickupWatch.Stats(triggers = 2, notHot = 2), watch.stats)
    }

    @Test
    fun `a motion on a hot rig is judged once, three seconds after it began`() {
        val model = Always(PickupVerdict.PICKUP)
        val calls = ArrayList<PickupDecision>()
        val watch = PickupWatch({ model }, direct, onPickup = { calls += it })
        watch.onRig(hot = true)
        val b = StreamBuilder().rest(5.0)
        val knockAt = b.nowNanos
        val stream = b.knock().rest(2.9).build()
        stream.forEach(watch::onSample)
        assertTrue("the window is still open before trigger + 3 s", calls.isEmpty())
        StreamBuilder(startNanos = stream.t.last() + 20_000_000).rest(1.0).build().forEach(watch::onSample)
        assertEquals(1, calls.size)
        val window = model.seen.single()
        assertEquals(knockAt, window.triggerNanos)
        assertEquals("2 s of history", knockAt - 2_000_000_000L, window.samples.first().tNanos)
        assertTrue(window.samples.last().tNanos >= knockAt + 3_000_000_000L)
    }

    @Test
    fun `a window is dropped unless the rig was hot from its trigger to its close`() {
        /** Rest, a knock at 6 s, rest: the window spans 4 s .. 9 s of stream time. */
        fun statsWhen(hotAt: (seconds: Double) -> Boolean): PickupWatch.Stats {
            val model = Always(PickupVerdict.PICKUP)
            val watch = PickupWatch({ model }, direct, onPickup = {})
            StreamBuilder(startNanos = 1_000_000_000L).rest(5.0).knock().rest(6.0).build().forEach { t, x, y, z ->
                watch.onRig(hotAt(t / 1e9))
                watch.onSample(t, x, y, z)
            }
            return watch.stats
        }
        assertEquals("hot throughout", PickupWatch.Stats(triggers = 1, judged = 1, pickups = 1), statsWhen { true })
        assertEquals("went hot after the motion began (the set-down that starts a shift)",
            PickupWatch.Stats(triggers = 1, notHot = 1), statsWhen { it > 7.0 })
        assertEquals("left DOWN before the window closed (the tilt rule or the screen has it)",
            PickupWatch.Stats(triggers = 1, interrupted = 1), statsWhen { it < 8.0 })
        assertEquals("left DOWN and came back inside the window (a forgiven peek)",
            PickupWatch.Stats(triggers = 1, interrupted = 1), statsWhen { it !in 7.0..8.0 })
    }

    @Test
    fun `only the start of a motion is judged, not its re-fires`() {
        // A tip that stays: the trigger fires at the tip and re-fires until it has come to rest
        // again. The re-fire windows hold a still phone, which the model would call a pickup.
        val model = Always(PickupVerdict.NOT_PICKUP)
        val watch = PickupWatch({ model }, direct, onPickup = { error("NOT_PICKUP") })
        watch.onRig(hot = true)
        val b = StreamBuilder().rest(6.0)
        val tipAt = b.nowNanos
        b.rotateTo(20.0, 0.4).rest(30.0)
        val knockAt = b.nowNanos
        b.knock().rest(5.0).build().forEach(watch::onSample)

        assertEquals("the tip, then the knock once the trigger is at rest on the new posture", 2, model.seen.size)
        assertTrue(model.seen[0].triggerNanos in tipAt..(tipAt + 600_000_000L))
        assertEquals(knockAt, model.seen[1].triggerNanos)
        val stats = watch.stats
        assertEquals(2, stats.judged)
        assertEquals(stats.triggers - 2, stats.reFires)
        assertTrue("at most one re-fire while it settles: $stats", stats.reFires <= 1)
    }

    @Test
    fun `the lay-down that starts a shift is not a candidate, and a still night asks the model nothing`() {
        val model = Always(PickupVerdict.PICKUP)
        val rig = Rig(model)
        // Armed in the hand, turned over onto the nightstand, then an hour of nothing.
        rig.feed(StreamBuilder().rotateTo(150.0, 0.02).sway(3.0).rotateTo(0.0, 1.0).rest(3_600.0).build())
        assertTrue(rig.machine.state is ShiftState.Down)
        assertTrue("no window was judged: ${rig.watch.stats}", model.seen.isEmpty())
        assertTrue(rig.pickups.isEmpty())

        // The watch is awake, though: a motion now is judged (and this stand-in calls it a pickup).
        val next = rig.clock.now * 1_000_000 + 20_000_000
        rig.feed(StreamBuilder(startNanos = next).rest(1.0).knock().rest(4.0).build())
        assertEquals(1, model.seen.size)
        assertEquals(ShiftState.Broken::class, rig.machine.state::class)
        assertEquals(BreakReason.LIFTED, (rig.machine.state as ShiftState.Broken).reason)
    }

    // ------------------------------------------------------------------ what a verdict can do

    @Test
    fun `not-a-pickup does nothing at all`() {
        val model = Always(PickupVerdict.NOT_PICKUP)
        val rig = Rig(model)
        rig.feed(StreamBuilder().rest(4.0).build())
        val before = rig.machine.state
        assertTrue(before is ShiftState.Down)
        val next = rig.clock.now * 1_000_000 + 20_000_000
        rig.feed(StreamBuilder(startNanos = next).rest(1.0).knock().rest(6.0).knock().rest(6.0).build())
        assertEquals("both knocks were judged", 2, model.seen.size)
        assertEquals("the state object itself is untouched: nothing was dispatched", before, rig.machine.state)
        assertTrue(rig.pickups.isEmpty())
        assertNull(rig.watch.veto)
        assertEquals(0, rig.watch.stats.pickups)
        assertTrue("no event reached the machine after it went hot", rig.transitions.last().to == before && rig.transitions.count { it.to is ShiftState.Down } == 1)
    }

    @Test
    fun `not-a-pickup cannot bring back a cooling or a broken rig`() {
        val model = Always(PickupVerdict.NOT_PICKUP)
        val rig = Rig(model)
        rig.feed(StreamBuilder().rest(4.0).build())
        rig.dispatch(ShiftEvent.ScreenOn) // a notification: cooling, the phone never moved
        val cooling = rig.machine.state
        val next = rig.clock.now * 1_000_000 + 20_000_000
        rig.feed(StreamBuilder(startNanos = next).rest(1.0).knock().rest(5.0).build())
        assertEquals("a bump while cooling is not even judged, and changes nothing", cooling, rig.machine.state)
        assertTrue(model.seen.isEmpty())

        rig.dispatch(ShiftEvent.UserPresent)
        val broken = rig.machine.state
        assertTrue(broken is ShiftState.Broken)
        val later = rig.clock.now * 1_000_000 + 20_000_000
        rig.feed(StreamBuilder(startNanos = later).rest(1.0).knock().rest(5.0).build())
        assertEquals(broken, rig.machine.state)
        assertTrue(model.seen.isEmpty())
    }

    @Test
    fun `a pickup vetoes heartbeats until the break is dispatched`() {
        val calls = ArrayList<PickupDecision>()
        val watch = PickupWatch({ Always(PickupVerdict.PICKUP) }, direct, onPickup = { calls += it })
        watch.onRig(hot = true)
        assertTrue(ForemanGate.heartbeatAllowed(dark = true, decision = watch.veto))
        StreamBuilder().rest(4.0).knock().rest(4.0).build().forEach(watch::onSample)
        assertSame(calls.single(), watch.veto)
        assertFalse(ForemanGate.heartbeatAllowed(dark = true, decision = watch.veto))
        watch.clear()
        assertNull(watch.veto)
    }

    @Test
    fun `a verdict that arrives after the rig left DOWN is dropped`() {
        val queued = ArrayList<Runnable>()
        val calls = ArrayList<PickupDecision>()
        val watch = PickupWatch({ Always(PickupVerdict.PICKUP) }, Executor { queued += it }, onPickup = { calls += it })
        watch.onRig(hot = true)
        StreamBuilder().rest(4.0).knock().rest(4.0).build().forEach(watch::onSample)
        assertEquals("the window is waiting for the background thread", 1, queued.size)
        watch.onRig(hot = false) // the screen came on while the model ran
        queued.single().run()
        assertTrue(calls.isEmpty())
        assertNull(watch.veto)
        assertEquals(PickupWatch.Stats(triggers = 1, interrupted = 1, judged = 1), watch.stats)
    }

    // ------------------------------------------------------------------ fail closed

    @Test
    fun `no model means every motion on a hot rig breaks the shift`() {
        // What ForemanModels returns for a missing or malformed asset.
        for (classifier in listOf(ForemanModels.pickupClassifier(null), ForemanModels.pickupClassifier("{}"), FailClosedPickupClassifier)) {
            val rig = Rig(classifier)
            rig.feed(StreamBuilder().rest(4.0).knock(peak = 3.0, samples = 1).rest(4.0).build())
            val broken = rig.machine.state as ShiftState.Broken
            assertEquals(BreakReason.LIFTED, broken.reason)
            assertEquals(FailClosedReason.MODEL_UNAVAILABLE, rig.pickups.single().failClosed)
        }
    }

    @Test
    fun `a classifier that throws, a loader that throws and an unusable window all break`() {
        val throwing = object : PickupClassifier {
            override val modelName = "broken"
            override fun classify(window: MotionWindow): PickupDecision = error("runtime crashed")
        }
        val a = Rig(throwing)
        a.feed(StreamBuilder().rest(4.0).knock().rest(4.0).build())
        assertEquals(FailClosedReason.MODEL_UNAVAILABLE, a.pickups.single().failClosed)
        assertTrue(a.machine.state is ShiftState.Broken)

        val calls = ArrayList<PickupDecision>()
        val watch = PickupWatch({ error("asset unreadable") }, direct, onPickup = { calls += it })
        watch.onRig(hot = true)
        StreamBuilder().rest(4.0).knock().rest(4.0).build().forEach(watch::onSample)
        assertEquals(FailClosedReason.MODEL_UNAVAILABLE, calls.single().failClosed)

        // The sensor stream stalls for 0.8 s inside the window: the shipped model is not asked.
        val b = Rig(ForemanVectors.shippedClassifier())
        b.feed(StreamBuilder().rest(4.0).knock().rest(1.0).gap(0.8).rest(3.0).build())
        assertEquals(FailClosedReason.GAP, b.pickups.single().failClosed)
        assertEquals(BreakReason.LIFTED, (b.machine.state as ShiftState.Broken).reason)
    }

    // ------------------------------------------------------------------ the switch

    @Test
    fun `switched off, the classifier is out of the shift and the deterministic rules remain`() {
        val values = HashMap<String, Boolean>()
        val settings = ForemanSettings(object : FlagStore {
            override fun get(key: String, default: Boolean) = values[key] ?: default
            override fun put(key: String, value: Boolean) { values[key] = value }
        })
        assertTrue("on as shipped", ForemanSettings.PICKUP_BREAKS_DEFAULT && settings.pickupBreaksEnabled)

        val model = Always(PickupVerdict.PICKUP)
        val calls = ArrayList<PickupDecision>()
        val watch = PickupWatch({ model }, direct, onPickup = { calls += it }, enabled = { settings.pickupBreaksEnabled })
        watch.onRig(hot = true)
        settings.pickupBreaksEnabled = false
        val first = StreamBuilder().rest(4.0).knock().rest(6.0).build()
        first.forEach(watch::onSample)
        assertTrue("not judged, not called back", model.seen.isEmpty() && calls.isEmpty())
        assertNull(watch.veto)
        assertEquals(PickupWatch.Stats(triggers = 1, switchedOff = 1), watch.stats)

        // It is read when a window closes: switching it back on takes effect for the next motion.
        settings.pickupBreaksEnabled = true
        StreamBuilder(startNanos = first.t.last() + 20_000_000).rest(1.0).knock().rest(4.0).build().forEach(watch::onSample)
        assertEquals(1, calls.size)

        // A switch that cannot be read leaves the classifier in.
        val unreadable = PickupWatch({ model }, direct, onPickup = { calls += it }, enabled = { error("prefs unreadable") })
        unreadable.onRig(hot = true)
        StreamBuilder().rest(4.0).knock().rest(4.0).build().forEach(unreadable::onSample)
        assertEquals(2, calls.size)
    }

    @Test
    fun `a shut-down executor is not a crash`() {
        val watch = PickupWatch({ Always(PickupVerdict.PICKUP) }, Executor { throw RejectedExecutionException() }, onPickup = { error("never") })
        watch.onRig(hot = true)
        StreamBuilder().rest(4.0).knock().rest(4.0).build().forEach(watch::onSample)
        assertEquals(0, watch.stats.judged)
    }

    // ------------------------------------------------------------------ cost

    @Test
    fun `the sensor path of a shift allocates nothing while the phone lies still`() {
        assumeTrue("needs HotSpot's per-thread allocation counter", Allocations.supported)
        val detector = FaceDownDetector()
        val watch = PickupWatch({ ForemanVectors.shippedClassifier() }, direct, onPickup = {})
        watch.onRig(hot = true)
        // Warm up past the JIT's tiering thresholds: while HotSpot recompiles a method it can
        // charge a few hundred bytes of its own to the thread, which is not the code under test.
        val warm = StreamBuilder(seed = 5).rest(800.0).build()
        val night = StreamBuilder(seed = 6, startNanos = warm.t.last() + 20_000_000).rest(1_200.0).build()
        var faceDown = false
        warm.forEach { t, x, y, z ->
            faceDown = detector.onSample(x, y, z, t)
            watch.onSample(t, x, y, z)
        }
        assertTrue(faceDown)

        val bytes = Allocations.during {
            night.forEach { t, x, y, z ->
                faceDown = detector.onSample(x, y, z, t)
                watch.onSample(t, x, y, z)
            }
        }
        println("shift sensor path: $bytes bytes allocated over ${night.size} samples (FaceDownDetector + PickupWatch)")
        // One boxed Long per sample would be ~960,000 bytes here, one per rest-tracker block
        // ~38,000, one per second ~19,000. The slack is for the JVM's own one-off allocations.
        assertTrue("the sample path allocated $bytes bytes over ${night.size} samples", bytes <= 2_048)
        assertEquals("and no inference: nothing triggered", 0, watch.stats.judged)
        assertNotNull(detector.lastSampleMillis)
    }
}
