package xyz.headsdown.feature.shift

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.headsdown.core.keys.RigSignalState

class FakeClock(var now: Long = 1_000_000L) : MonotonicClock {
    override fun nowMillis(): Long = now
    fun advance(ms: Long) { now += ms }
}

class ShiftStateMachineTest {

    private val clock = FakeClock()
    private val night = ShiftSpec(shiftId = 7, mode = ShiftMode.NIGHT)
    private val day = ShiftSpec(shiftId = 8, mode = ShiftMode.DAY)

    /** Screen on, face-up, unplugged: a phone in someone's hand. */
    private fun machine(signals: Signals = Signals(faceDown = false, screenOn = true, charging = false)) =
        ShiftStateMachine(clock, graceMillis = 10_000, initialSignals = signals)

    /** Arms a night shift and lays the phone down dark on the charger. */
    private fun hotNightMachine(): ShiftStateMachine = machine(Signals(charging = true)).apply {
        dispatch(ShiftEvent.Arm(night))
        dispatch(ShiftEvent.ScreenOff)
        dispatch(ShiftEvent.Posture(faceDown = true))
        check(state is ShiftState.Down)
    }

    // ------------------------------------------------------------------ arming

    @Test
    fun `starts idle and arms`() {
        val m = machine()
        assertEquals(ShiftState.Idle, m.state)
        val t = m.dispatch(ShiftEvent.Arm(night))
        assertEquals(ShiftState.Armed(night, clock.now), t.to)
        assertTrue(t.effects.isEmpty())
    }

    @Test
    fun `armed goes down only when every dark condition holds`() {
        val m = machine()
        m.dispatch(ShiftEvent.Arm(night))
        m.dispatch(ShiftEvent.Posture(true))
        assertTrue("screen still on", m.state is ShiftState.Armed)
        m.dispatch(ShiftEvent.ScreenOff)
        assertTrue("night shift needs the charger", m.state is ShiftState.Armed)
        clock.advance(500)
        val t = m.dispatch(ShiftEvent.Power(charging = true))
        assertEquals(ShiftState.Down(night, since = clock.now, firstDownAt = clock.now), t.to)
        assertEquals(listOf(ShiftEffect.WentDark), t.effects)
    }

    @Test
    fun `day shift does not need a charger`() {
        val m = machine()
        m.dispatch(ShiftEvent.Arm(day))
        m.dispatch(ShiftEvent.ScreenOff)
        m.dispatch(ShiftEvent.Posture(true))
        assertTrue(m.state is ShiftState.Down)
        m.dispatch(ShiftEvent.Power(charging = false))
        assertTrue("unplugging is irrelevant for DAY", m.state is ShiftState.Down)
    }

    @Test
    fun `arming while already dark goes straight down`() {
        val m = machine(Signals(faceDown = true, screenOn = false, charging = true))
        val t = m.dispatch(ShiftEvent.Arm(night))
        assertTrue(t.to is ShiftState.Down)
        assertEquals(listOf(ShiftEffect.WentDark), t.effects)
    }

    @Test
    fun `unlock and screen-on while armed do not break`() {
        val m = machine()
        m.dispatch(ShiftEvent.Arm(night))
        m.dispatch(ShiftEvent.UserPresent)
        m.dispatch(ShiftEvent.ScreenOn)
        assertTrue(m.state is ShiftState.Armed)
    }

    @Test
    fun `arming again during a shift is a no-op`() {
        val m = hotNightMachine()
        val before = m.state
        val t = m.dispatch(ShiftEvent.Arm(day))
        assertEquals(before, t.to)
        assertTrue(t.effects.isEmpty())
    }

    // ------------------------------------------------------------------ cooling and grace

    @Test
    fun `lifting cools with a 10 second deadline`() {
        val m = hotNightMachine()
        clock.advance(60_000)
        val t = m.dispatch(ShiftEvent.Posture(false))
        val cooling = t.to as ShiftState.Cooling
        assertEquals(CoolReason.LIFTED, cooling.reason)
        assertEquals(clock.now + 10_000, cooling.deadline)
        assertEquals(
            listOf(ShiftEffect.CoolingStarted(CoolReason.LIFTED), ShiftEffect.ScheduleTick(clock.now + 10_000)),
            t.effects,
        )
        assertEquals(RigSignalState.COOLING, cooling.wire)
    }

    @Test
    fun `putting it back within grace resumes and keeps firstDownAt`() {
        val m = hotNightMachine()
        val firstDown = (m.state as ShiftState.Down).firstDownAt
        clock.advance(1_000)
        m.dispatch(ShiftEvent.Posture(false))
        clock.advance(9_999)
        val t = m.dispatch(ShiftEvent.Posture(true))
        val down = t.to as ShiftState.Down
        assertEquals(firstDown, down.firstDownAt)
        assertEquals(clock.now, down.since)
        assertEquals(listOf(ShiftEffect.WentDark), t.effects)
    }

    @Test
    fun `grace expiry on tick breaks and signs a BREAK`() {
        val m = hotNightMachine()
        m.dispatch(ShiftEvent.Posture(false))
        val deadline = (m.state as ShiftState.Cooling).deadline
        clock.advance(9_999)
        assertTrue(m.dispatch(ShiftEvent.Tick).to is ShiftState.Cooling)
        clock.advance(1)
        val t = m.dispatch(ShiftEvent.Tick)
        assertEquals(ShiftState.Broken(night, deadline, BreakReason.LIFTED), t.to)
        assertEquals(listOf(ShiftEffect.SignBreak(night, BreakReason.LIFTED)), t.effects)
    }

    @Test
    fun `a late posture sample cannot resurrect an expired grace window`() {
        // The CPU slept through the deadline; the next thing we see is "face-down again".
        val m = hotNightMachine()
        m.dispatch(ShiftEvent.Posture(false))
        clock.advance(30_000)
        val t = m.dispatch(ShiftEvent.Posture(true))
        assertTrue(t.to is ShiftState.Broken)
        assertEquals(BreakReason.LIFTED, (t.to as ShiftState.Broken).reason)
    }

    @Test
    fun `screen on is a hard signal that cools instantly even while face-down`() {
        val m = hotNightMachine()
        val t = m.dispatch(ShiftEvent.ScreenOn)
        assertEquals(CoolReason.SCREEN_ON, (t.to as ShiftState.Cooling).reason)
        // A notification lit the screen of a phone that never moved: screen off -> hot again.
        clock.advance(4_000)
        assertTrue(m.dispatch(ShiftEvent.ScreenOff).to is ShiftState.Down)
    }

    @Test
    fun `screen left on past grace breaks with SCREEN_ON`() {
        val m = hotNightMachine()
        m.dispatch(ShiftEvent.ScreenOn)
        clock.advance(10_000)
        val t = m.dispatch(ShiftEvent.Tick)
        assertEquals(BreakReason.SCREEN_ON, (t.to as ShiftState.Broken).reason)
    }

    @Test
    fun `unplugging a night shift cools, replugging resumes`() {
        val m = hotNightMachine()
        val t = m.dispatch(ShiftEvent.Power(false))
        assertEquals(CoolReason.UNPLUGGED, (t.to as ShiftState.Cooling).reason)
        clock.advance(2_000)
        assertTrue(m.dispatch(ShiftEvent.Power(true)).to is ShiftState.Down)
    }

    @Test
    fun `cooling needs every condition back before resuming`() {
        val m = hotNightMachine()
        m.dispatch(ShiftEvent.ScreenOn)
        m.dispatch(ShiftEvent.Posture(false))
        m.dispatch(ShiftEvent.ScreenOff)
        assertTrue("still face-up", m.state is ShiftState.Cooling)
        m.dispatch(ShiftEvent.Posture(true))
        assertTrue(m.state is ShiftState.Down)
    }

    @Test
    fun `repeated soft events while cooling do not extend the deadline`() {
        val m = hotNightMachine()
        m.dispatch(ShiftEvent.Posture(false))
        val deadline = (m.state as ShiftState.Cooling).deadline
        clock.advance(5_000)
        val t = m.dispatch(ShiftEvent.Posture(false))
        assertEquals(deadline, (t.to as ShiftState.Cooling).deadline)
        assertTrue(t.effects.isEmpty())
    }

    // ------------------------------------------------------------------ hard breaks

    @Test
    fun `unlock while down breaks immediately with no grace`() {
        val m = hotNightMachine()
        val t = m.dispatch(ShiftEvent.UserPresent)
        assertEquals(ShiftState.Broken(night, clock.now, BreakReason.UNLOCKED), t.to)
        assertEquals(listOf(ShiftEffect.SignBreak(night, BreakReason.UNLOCKED)), t.effects)
        assertTrue("unlock implies the screen is on", t.signals.screenOn)
    }

    @Test
    fun `unlock while cooling breaks immediately`() {
        val m = hotNightMachine()
        m.dispatch(ShiftEvent.ScreenOn)
        clock.advance(2_000)
        val t = m.dispatch(ShiftEvent.UserPresent)
        assertEquals(BreakReason.UNLOCKED, (t.to as ShiftState.Broken).reason)
    }

    @Test
    fun `unlock after the grace deadline reports the original reason once`() {
        val m = hotNightMachine()
        m.dispatch(ShiftEvent.Posture(false))
        clock.advance(11_000)
        val t = m.dispatch(ShiftEvent.UserPresent)
        assertEquals(BreakReason.LIFTED, (t.to as ShiftState.Broken).reason)
        assertEquals(1, t.effects.count { it is ShiftEffect.SignBreak })
    }

    @Test
    fun `broken absorbs signals and can be re-armed`() {
        val m = hotNightMachine()
        m.dispatch(ShiftEvent.UserPresent)
        m.dispatch(ShiftEvent.ScreenOff)
        m.dispatch(ShiftEvent.Posture(true))
        assertTrue("no silent resume after a break", m.state is ShiftState.Broken)
        val next = ShiftSpec(9, ShiftMode.NIGHT)
        val t = m.dispatch(ShiftEvent.Arm(next))
        // Still face-down, screen off, charging: the new shift goes straight to DOWN.
        assertEquals(next, (t.to as ShiftState.Down).spec)
    }

    // ------------------------------------------------------------------ freeze

    @Test
    fun `freeze from any state signs FREEZE and absorbs everything but unfreeze`() {
        val m = hotNightMachine()
        val t = m.dispatch(ShiftEvent.Freeze)
        assertEquals(ShiftState.Frozen(7, clock.now), t.to)
        assertEquals(listOf(ShiftEffect.SignFreeze(7)), t.effects)

        for (e in listOf(ShiftEvent.Arm(day), ShiftEvent.End, ShiftEvent.Posture(false), ShiftEvent.UserPresent, ShiftEvent.Tick, ShiftEvent.Freeze)) {
            val r = m.dispatch(e)
            assertTrue("$e must not leave FROZEN", r.to is ShiftState.Frozen)
            assertTrue("$e must not emit effects", r.effects.isEmpty())
        }
        assertEquals(ShiftState.Idle, m.dispatch(ShiftEvent.Unfreeze).to)
    }

    @Test
    fun `freeze from idle has no shift id`() {
        val t = machine().dispatch(ShiftEvent.Freeze)
        assertEquals(ShiftState.Frozen(null, clock.now), t.to)
        assertEquals(listOf(ShiftEffect.SignFreeze(null)), t.effects)
    }

    @Test
    fun `unfreeze outside frozen is ignored`() {
        val m = hotNightMachine()
        assertTrue(m.dispatch(ShiftEvent.Unfreeze).to is ShiftState.Down)
    }

    // ------------------------------------------------------------------ end

    @Test
    fun `end from any shift state returns to idle without a BREAK`() {
        for (setup in listOf<(ShiftStateMachine) -> Unit>(
            { it.dispatch(ShiftEvent.Arm(night)) },
            { it.dispatch(ShiftEvent.Arm(day)); it.dispatch(ShiftEvent.ScreenOff); it.dispatch(ShiftEvent.Posture(true)) },
            { it.dispatch(ShiftEvent.Arm(day)); it.dispatch(ShiftEvent.ScreenOff); it.dispatch(ShiftEvent.Posture(true)); it.dispatch(ShiftEvent.Posture(false)) },
        )) {
            val m = machine()
            setup(m)
            val t = m.dispatch(ShiftEvent.End)
            assertEquals(ShiftState.Idle, t.to)
            assertEquals(listOf(ShiftEffect.ShiftEnded), t.effects)
        }
        assertTrue(machine().dispatch(ShiftEvent.End).effects.isEmpty())
    }

    // ------------------------------------------------------------------ invariants

    @Test
    fun `only DOWN is hot and only DOWN carries the DOWN wire state`() {
        val states = listOf(
            ShiftState.Idle,
            ShiftState.Armed(night, 0),
            ShiftState.Down(night, 0, 0),
            ShiftState.Cooling(night, 0, 1, CoolReason.LIFTED, 0),
            ShiftState.Broken(night, 0, BreakReason.LIFTED),
            ShiftState.Frozen(null, 0),
        )
        assertEquals(listOf(false, false, true, false, false, false), states.map { it.isHot })
        assertEquals(1, states.count { it.wire == RigSignalState.DOWN })
    }

    @Test
    fun `randomized event storm never reaches DOWN without being dark`() {
        val random = java.util.Random(99)
        val events = listOf(
            ShiftEvent.Arm(night), ShiftEvent.Arm(day), ShiftEvent.Posture(true), ShiftEvent.Posture(false),
            ShiftEvent.ScreenOn, ShiftEvent.ScreenOff, ShiftEvent.UserPresent, ShiftEvent.Power(true),
            ShiftEvent.Power(false), ShiftEvent.Tick, ShiftEvent.End, ShiftEvent.Freeze, ShiftEvent.Unfreeze,
        )
        val m = machine()
        repeat(20_000) {
            clock.advance(random.nextInt(4_000).toLong())
            val t = m.dispatch(events[random.nextInt(events.size)])
            val s = t.to
            if (s is ShiftState.Down) assertTrue("DOWN while not dark: $t", t.signals.isDark(s.spec))
            // Expiry is applied before every event, so a surviving grace window is never overdue.
            if (s is ShiftState.Cooling) assertTrue("overdue cooling: $t", clock.now < s.deadline)
            if (t.from is ShiftState.Frozen && s !is ShiftState.Frozen) assertEquals(ShiftState.Idle, s)
            if (t.from is ShiftState.Frozen) assertTrue(t.effects.isEmpty())
        }
    }
}
