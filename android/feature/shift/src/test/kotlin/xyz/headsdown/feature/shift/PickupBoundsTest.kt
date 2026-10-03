package xyz.headsdown.feature.shift

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.headsdown.core.keys.ShiftEndReason
import xyz.headsdown.ml.FailClosedReason
import xyz.headsdown.ml.ForemanGate
import xyz.headsdown.ml.PickupDecision
import xyz.headsdown.ml.PickupVerdict

/**
 * The bounds on the pickup classifier, proven on the state machine: its verdict can add one
 * break to a hot rig and nothing else. It cannot heat a rig, re-arm a shift, end a grace window
 * early or late, or weaken screen-on, unlock, unplugging or the face-down detector.
 */
class PickupBoundsTest {

    private val clock = FakeClock()
    private val night = ShiftSpec(shiftId = 7, mode = ShiftMode.NIGHT)
    private val day = ShiftSpec(shiftId = 8, mode = ShiftMode.DAY)
    private val grace = 10_000L

    private fun hot(spec: ShiftSpec = night): ShiftStateMachine =
        ShiftStateMachine(clock, graceMillis = grace, initialSignals = Signals(charging = true)).apply {
            dispatch(ShiftEvent.Arm(spec))
            dispatch(ShiftEvent.ScreenOff)
            dispatch(ShiftEvent.Posture(faceDown = true))
            check(state is ShiftState.Down)
        }

    /** How close a state is to digging: only DOWN heartbeats. */
    private fun heat(s: ShiftState): Int = when (s) {
        is ShiftState.Down -> 3
        is ShiftState.Cooling -> 2
        is ShiftState.Armed -> 1
        ShiftState.Idle, is ShiftState.Broken, is ShiftState.Frozen -> 0
    }

    // ------------------------------------------------------------------ the one thing it can do

    @Test
    fun `a classifier pickup breaks a hot rig at once and signs BREAK reason 1`() {
        val m = hot()
        clock.advance(60_000)
        val t = m.dispatch(ShiftEvent.PickupDetected)
        assertEquals(ShiftState.Broken(night, clock.now, BreakReason.LIFTED), t.to)
        assertEquals(listOf(ShiftEffect.SignBreak(night, BreakReason.LIFTED)), t.effects)
        assertTrue("it is the morning haul's first pickup", t.isPickup)
        assertEquals("dark signals are untouched: the verdict is not a sensor", Signals(faceDown = true, screenOn = false, charging = true), t.signals)

        // The same number end to end: classifier -> gate -> break reason -> wire byte.
        val pickup = PickupDecision(0.99, 4.6, PickupVerdict.PICKUP, null, "test")
        assertEquals(1, ForemanGate.breakReason(pickup))
        assertEquals(1, PickupDecision.BREAK_REASON_PICKUP)
        assertEquals(ShiftEndReason.PICKUP, BreakReason.LIFTED.wireReason)
        assertEquals(1, BreakReason.LIFTED.wireReason.wire)
    }

    @Test
    fun `a fail-closed window is a pickup too, and a bump is no event at all`() {
        for (reason in FailClosedReason.entries) {
            assertEquals(reason.name, 1, ForemanGate.breakReason(PickupDecision.failClosed(reason, "test")))
        }
        // NOT_PICKUP has no ShiftEvent: there is nothing to dispatch, so nothing it could change.
        assertNull(ForemanGate.breakReason(PickupDecision(0.01, -4.6, PickupVerdict.NOT_PICKUP, null, "test")))
        assertEquals(
            "the classifier's only event",
            listOf("PickupDetected"),
            ShiftEvent::class.java.declaredClasses.map { it.simpleName }.filter { "Pickup" in it },
        )
    }

    // ------------------------------------------------------------------ everything it cannot do

    @Test
    fun `a classifier pickup does nothing outside DOWN`() {
        val machines = listOf<() -> ShiftStateMachine>(
            { ShiftStateMachine(clock, graceMillis = grace) },
            { ShiftStateMachine(clock, graceMillis = grace).apply { dispatch(ShiftEvent.Arm(night)) } },
            { hot().apply { dispatch(ShiftEvent.ScreenOn) } },
            { hot().apply { dispatch(ShiftEvent.Posture(false)) } },
            { hot().apply { dispatch(ShiftEvent.Power(false)) } },
            { hot().apply { dispatch(ShiftEvent.UserPresent) } },
            { hot().apply { dispatch(ShiftEvent.Freeze) } },
        )
        val seen = mutableSetOf<String>()
        for (make in machines) {
            val m = make()
            val before = m.state
            val signals = m.signals
            seen += before.javaClass.simpleName
            repeat(3) {
                clock.advance(1_000) // well inside the 10 s grace window of the cooling rigs
                val t = m.dispatch(ShiftEvent.PickupDetected)
                assertEquals("$before must not move", before, t.to)
                assertTrue("$before must not emit effects", t.effects.isEmpty())
                assertEquals(signals, t.signals)
                assertFalse(t.isPickup)
            }
        }
        assertEquals(setOf("Idle", "Armed", "Cooling", "Broken", "Frozen"), seen)
    }

    @Test
    fun `it never heats, re-arms or un-cools, for every state and every signal`() {
        val specs = listOf(night, day)
        val signals = buildList {
            for (down in listOf(false, true)) for (on in listOf(false, true)) for (charging in listOf(false, true)) {
                add(Signals(down, on, charging))
            }
        }
        val now = 500_000L
        var checked = 0
        for (spec in specs) {
            val states = listOf(
                ShiftState.Idle,
                ShiftState.Armed(spec, now - 5_000),
                ShiftState.Down(spec, now - 5_000, now - 9_000),
                ShiftState.Cooling(spec, now - 2_000, now + 8_000, CoolReason.LIFTED, now - 9_000),
                ShiftState.Cooling(spec, now - 2_000, now + 8_000, CoolReason.SCREEN_ON, now - 9_000),
                ShiftState.Cooling(spec, now - 2_000, now + 8_000, CoolReason.UNPLUGGED, now - 9_000),
                // A grace window that ran out before this event: it breaks for its own reason.
                ShiftState.Cooling(spec, now - 20_000, now - 10_000, CoolReason.SCREEN_ON, now - 30_000),
                ShiftState.Broken(spec, now - 1_000, BreakReason.UNLOCKED),
                ShiftState.Broken(spec, now - 1_000, BreakReason.LIFTED),
                ShiftState.Frozen(spec.shiftId, now - 1_000),
            )
            for (state in states) {
                // Every combination, including ones the machine cannot reach (an armed rig whose
                // signals are already dark): the event must not use them to go hot.
                for (sig in signals) {
                    val (to, after, effects) = ShiftStateMachine.reduce(state, sig, ShiftEvent.PickupDetected, now, grace)
                    assertEquals("signals are never changed", sig, after)
                    assertTrue("$state / $sig -> $to got hotter", heat(to) <= heat(state))
                    assertFalse("$state / $sig reached DOWN", to is ShiftState.Down)
                    assertTrue("only a BREAK may be signed: $effects", effects.all { it is ShiftEffect.SignBreak })
                    when (state) {
                        is ShiftState.Down -> assertEquals(ShiftState.Broken(spec, now, BreakReason.LIFTED), to)
                        is ShiftState.Cooling ->
                            if (now >= state.deadline) {
                                assertEquals("an expired grace window breaks for its own reason", BreakReason.SCREEN_ON, (to as ShiftState.Broken).reason)
                            } else {
                                assertEquals("a running grace window keeps its deadline and reason", state, to)
                            }
                        else -> assertEquals(state, to)
                    }
                    checked++
                }
            }
        }
        assertEquals(2 * 10 * 8, checked)
    }

    @Test
    fun `it leaves a grace window exactly as it was`() {
        // A notification lit the screen; a verdict arrives while cooling; the screen goes off again.
        val m = hot()
        m.dispatch(ShiftEvent.ScreenOn)
        val cooling = m.state as ShiftState.Cooling
        clock.advance(3_000)
        assertEquals(cooling, m.dispatch(ShiftEvent.PickupDetected).to)
        clock.advance(3_000)
        assertTrue("still resumes inside the grace window", m.dispatch(ShiftEvent.ScreenOff).to is ShiftState.Down)

        // And it does not rescue one either: left alone, the window expires on time.
        val n = hot()
        n.dispatch(ShiftEvent.Posture(false))
        val deadline = (n.state as ShiftState.Cooling).deadline
        clock.advance(4_000)
        n.dispatch(ShiftEvent.PickupDetected)
        clock.advance(6_000)
        assertEquals(ShiftState.Broken(night, deadline, BreakReason.LIFTED), n.dispatch(ShiftEvent.Tick).to)
    }

    @Test
    fun `a broken shift stays broken, and only a new arm starts another`() {
        val m = hot()
        m.dispatch(ShiftEvent.PickupDetected)
        // Still face-down, screen off, on the charger: nothing resumes by itself.
        for (e in listOf(ShiftEvent.Posture(true), ShiftEvent.ScreenOff, ShiftEvent.Power(true), ShiftEvent.Tick, ShiftEvent.PickupDetected)) {
            assertTrue("$e must not resume a broken shift", m.dispatch(e).to is ShiftState.Broken)
        }
        val next = ShiftSpec(9, ShiftMode.NIGHT)
        assertEquals(next, (m.dispatch(ShiftEvent.Arm(next)).to as ShiftState.Down).spec)
    }

    // ------------------------------------------------------------------ the hard rules are untouched

    @Test
    fun `screen-on, unlock, unplugging and the tilt rule work exactly as before`() {
        // Around verdicts that changed nothing (an armed rig, a cooling rig)...
        val m = ShiftStateMachine(clock, graceMillis = grace, initialSignals = Signals(charging = true))
        m.dispatch(ShiftEvent.Arm(night))
        m.dispatch(ShiftEvent.PickupDetected)
        m.dispatch(ShiftEvent.ScreenOff)
        assertTrue(m.dispatch(ShiftEvent.Posture(true)).to is ShiftState.Down)

        val screen = m.dispatch(ShiftEvent.ScreenOn)
        assertEquals("screen-on still cools at once", CoolReason.SCREEN_ON, (screen.to as ShiftState.Cooling).reason)
        m.dispatch(ShiftEvent.PickupDetected)
        val unlock = m.dispatch(ShiftEvent.UserPresent)
        assertEquals("unlock still breaks at once, with its own reason", BreakReason.UNLOCKED, (unlock.to as ShiftState.Broken).reason)
        assertEquals(listOf(ShiftEffect.SignBreak(night, BreakReason.UNLOCKED)), unlock.effects)

        // ...and on a hot rig the classifier never spoke about.
        assertEquals(CoolReason.LIFTED, (hot().dispatch(ShiftEvent.Posture(false)).to as ShiftState.Cooling).reason)
        assertEquals(CoolReason.UNPLUGGED, (hot().dispatch(ShiftEvent.Power(false)).to as ShiftState.Cooling).reason)
        assertEquals(BreakReason.UNLOCKED, (hot().dispatch(ShiftEvent.UserPresent).to as ShiftState.Broken).reason)
    }

    @Test
    fun `heartbeats need the machine's own dark verdict, and a pickup verdict only vetoes`() {
        val pickup = PickupDecision(0.99, 4.6, PickupVerdict.PICKUP, null, "test")
        val bump = PickupDecision(0.01, -4.6, PickupVerdict.NOT_PICKUP, null, "test")
        val m = ShiftStateMachine(clock, graceMillis = grace, initialSignals = Signals(charging = true))
        m.dispatch(ShiftEvent.Arm(night))
        fun dark() = m.state is ShiftState.Down && m.signals.isDark(night)

        // Not dark: no verdict can make the rig heartbeat.
        for (d in listOf(null, bump, pickup)) assertFalse(ForemanGate.heartbeatAllowed(dark(), d))
        m.dispatch(ShiftEvent.ScreenOff)
        m.dispatch(ShiftEvent.Posture(true))
        assertTrue(ForemanGate.heartbeatAllowed(dark(), null))
        assertTrue(ForemanGate.heartbeatAllowed(dark(), bump))
        assertFalse("a pickup stops heartbeats even before the break is dispatched", ForemanGate.heartbeatAllowed(dark(), pickup))
        m.dispatch(ShiftEvent.PickupDetected)
        for (d in listOf(null, bump, pickup)) assertFalse("broken: never", ForemanGate.heartbeatAllowed(dark(), d))
    }

    @Test
    fun `randomized storm with classifier pickups keeps every invariant`() {
        val random = java.util.Random(4242)
        val events = listOf(
            ShiftEvent.Arm(night), ShiftEvent.Arm(day), ShiftEvent.Posture(true), ShiftEvent.Posture(false),
            ShiftEvent.ScreenOn, ShiftEvent.ScreenOff, ShiftEvent.UserPresent, ShiftEvent.Power(true),
            ShiftEvent.Power(false), ShiftEvent.Tick, ShiftEvent.End, ShiftEvent.Freeze, ShiftEvent.Unfreeze,
            ShiftEvent.PickupDetected, ShiftEvent.PickupDetected, ShiftEvent.PickupDetected,
        )
        val m = ShiftStateMachine(clock, graceMillis = grace)
        var breaks = 0
        repeat(40_000) {
            clock.advance(random.nextInt(4_000).toLong())
            val event = events[random.nextInt(events.size)]
            val before = m.signals
            val t = m.dispatch(event)
            val s = t.to
            if (s is ShiftState.Down) assertTrue("DOWN while not dark: $t", t.signals.isDark(s.spec))
            if (s is ShiftState.Cooling) assertTrue("overdue cooling: $t", clock.now < s.deadline)
            if (event == ShiftEvent.PickupDetected) {
                assertEquals("a verdict is not a signal", before, t.signals)
                assertTrue("a verdict heated the rig: $t", heat(s) <= heat(t.from))
                assertFalse(s is ShiftState.Down)
                if (t.from is ShiftState.Down) {
                    assertEquals(BreakReason.LIFTED, (s as ShiftState.Broken).reason)
                    assertEquals(listOf<ShiftEffect>(ShiftEffect.SignBreak(s.spec, BreakReason.LIFTED)), t.effects)
                    breaks++
                } else if (t.from !is ShiftState.Cooling) {
                    assertEquals(t.from, s)
                    assertTrue(t.effects.isEmpty())
                }
            }
        }
        println("storm: $breaks classifier breaks of hot rigs")
        assertTrue("the storm reached hot rigs and broke them: $breaks", breaks > 100)
    }
}
