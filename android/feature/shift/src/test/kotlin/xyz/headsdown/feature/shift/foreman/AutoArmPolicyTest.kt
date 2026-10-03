package xyz.headsdown.feature.shift.foreman

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.headsdown.feature.shift.BreakReason
import xyz.headsdown.feature.shift.CoolReason
import xyz.headsdown.feature.shift.ShiftMode
import xyz.headsdown.feature.shift.ShiftSpec
import xyz.headsdown.feature.shift.ShiftState
import xyz.headsdown.feature.shift.Signals
import java.util.Random

/**
 * Auto-arm groundwork: off by default, and when on it arms only a plan that went through the
 * gate, on an idle rig lying dark on its charger inside a confident window, within the limits
 * the wallet has signed as they are now.
 */
class AutoArmPolicyTest {

    private val hour = 3_600_000L
    private val start = 1_790_000_000_000L // window start, wall ms
    private val end = start + 8 * hour
    private val now = start + 20 * 60_000L
    private val spec = ShiftSpec(7, ShiftMode.NIGHT)

    private val limits = WalletLimits(
        capWeekLamports = 500_000_000, capShiftLamports = 120_000_000, capRoundLamports = 2_000_000,
        capMaxCost = 670_000_000, capsExpiryUnix = end / 1000 + 86_400, spentWeekLamports = 100_000_000,
    )
    private val template = PlanTemplate(maxEvCost = 530_000_000, digLamports = 1_000_000, splitTiles = 4, soloTiles = 0)

    private fun window(confidence: WindowConfidence = WindowConfidence.CONFIDENT) =
        PlannedWindow(start, end, slots = 32, meanIdle = 0.97, minIdle = 0.93, expectedIdleHours = 7.7, confidence = confidence)

    private fun plan(
        window: PlannedWindow? = window(),
        template: PlanTemplate = this.template,
        plannedWith: WalletLimits? = limits,
        night: Long? = 60_000_000,
        plannedAt: Long = start - 5 * hour,
    ): TonightPlan {
        val signable = if (window != null && plannedWith != null) {
            SignablePlan.tighten(plannedWith, template, window.startWallMillis / 1000, window.endWallMillis / 1000, plannedAt / 1000)
        } else {
            null
        }
        return TonightPlan(
            plannedAtWallMillis = plannedAt, tzMinutes = 60, nightsObserved = 21, window = window,
            week = listOf(NightShare(window, 355.0, night)), signable = signable,
            gate = when {
                window == null -> PlanGate.NO_WINDOW
                plannedWith == null -> PlanGate.NO_LIMITS
                signable == null -> PlanGate.REFUSED_BY_LIMITS
                else -> PlanGate.WITHIN_LIMITS
            },
        )
    }

    /** Switched on, idle, registered, dark on the charger for two minutes, 20 minutes into a confident window. */
    private fun ready() = AutoArmSituation(
        enabled = true,
        nowWallMillis = now,
        state = ShiftState.Idle,
        signals = Signals(faceDown = true, screenOn = false, charging = true),
        darkForMillis = 120_000,
        rigKeyReady = true,
        rigRegistered = true,
        chainShiftOpen = false,
        plan = plan(),
        limits = limits,
        lastShiftArmedWallMillis = start - 20 * hour, // last night's
    )

    private fun reason(s: AutoArmSituation): AutoArmHold? = (AutoArmPolicy.decide(s) as? AutoArmDecision.Hold)?.reason

    // ------------------------------------------------------------------ off by default

    @Test
    fun `auto-arm is off until the user turns it on`() {
        assertFalse(ForemanSettings.AUTO_ARM_DEFAULT)
        val values = HashMap<String, Boolean>()
        val store = object : FlagStore {
            override fun get(key: String, default: Boolean) = values[key] ?: default
            override fun put(key: String, value: Boolean) { values[key] = value }
        }
        val settings = ForemanSettings(store)
        assertFalse("a fresh install", settings.autoArmEnabled)
        assertEquals("with the switch as it ships, everything else being perfect", AutoArmHold.SWITCHED_OFF,
            reason(ready().copy(enabled = settings.autoArmEnabled)))

        settings.autoArmEnabled = true
        assertTrue(ForemanSettings(store).autoArmEnabled)
        assertTrue(AutoArmPolicy.decide(ready().copy(enabled = settings.autoArmEnabled)) is AutoArmDecision.Arm)
        settings.autoArmEnabled = false
        assertFalse(ForemanSettings(store).autoArmEnabled)
    }

    @Test
    fun `switched off, nothing arms - randomized`() {
        val rnd = Random(11)
        repeat(5_000) {
            val s = randomSituation(rnd).copy(enabled = false)
            assertEquals(AutoArmHold.SWITCHED_OFF, reason(s))
        }
    }

    // ------------------------------------------------------------------ when it may arm

    @Test
    fun `everything in place arms the gated plan`() {
        val d = AutoArmPolicy.decide(ready()) as AutoArmDecision.Arm
        assertEquals(window(), d.window)
        assertEquals(60_000_000L, d.nightLamports)
        assertEquals(template.maxEvCost, d.plan.maxEvCost)
        assertEquals(template.digLamports, d.plan.digLamports)
        assertEquals(start / 1000 to end / 1000, d.plan.windowStartUnix to d.plan.windowEndUnix)
        assertFalse(d.plan.day || d.plan.focusOnly)
        // What the rig key would sign: a Night Shift plan inside every cap.
        val wire = d.plan.toShiftPlan()
        assertEquals(0, wire.flags)
        assertTrue(wire.maxEvCost <= limits.capMaxCost.toULong() && wire.digLamports <= limits.capRoundLamports.toULong())
    }

    @Test
    fun `every single condition is needed`() {
        val broken = ShiftState.Broken(spec, 0, BreakReason.LIFTED)
        val cases: List<Pair<AutoArmHold, AutoArmSituation>> = listOf(
            AutoArmHold.SWITCHED_OFF to ready().copy(enabled = false),
            AutoArmHold.FROZEN to ready().copy(state = ShiftState.Frozen(7, 0)),
            AutoArmHold.NOT_IDLE to ready().copy(state = ShiftState.Armed(spec, 0)),
            AutoArmHold.NOT_IDLE to ready().copy(state = ShiftState.Down(spec, 0, 0)),
            AutoArmHold.NOT_IDLE to ready().copy(state = ShiftState.Cooling(spec, 0, 1, CoolReason.LIFTED, 0)),
            AutoArmHold.NOT_IDLE to ready().copy(state = broken),
            AutoArmHold.NO_RIG_KEY to ready().copy(rigKeyReady = false),
            AutoArmHold.RIG_NOT_REGISTERED to ready().copy(rigRegistered = false),
            AutoArmHold.CHAIN_SHIFT_OPEN to ready().copy(chainShiftOpen = true),
            AutoArmHold.SCREEN_ON to ready().copy(signals = Signals(faceDown = true, screenOn = true, charging = true)),
            AutoArmHold.NOT_FACE_DOWN to ready().copy(signals = Signals(faceDown = false, screenOn = false, charging = true)),
            AutoArmHold.NOT_CHARGING to ready().copy(signals = Signals(faceDown = true, screenOn = false, charging = false)),
            AutoArmHold.NOT_SETTLED to ready().copy(darkForMillis = AutoArmPolicy.SETTLE_MILLIS - 1),
            AutoArmHold.NO_PLAN to ready().copy(plan = null),
            AutoArmHold.STALE_PLAN to ready().copy(plan = plan(plannedAt = now - AutoArmPolicy.MAX_PLAN_AGE_MILLIS - 1)),
            AutoArmHold.STALE_PLAN to ready().copy(plan = plan(plannedAt = now + 1)),
            AutoArmHold.NO_WINDOW to ready().copy(plan = plan(window = null)),
            AutoArmHold.WINDOW_NOT_CONFIDENT to ready().copy(plan = plan(window = window(WindowConfidence.NEEDS_HISTORY))),
            AutoArmHold.WINDOW_NOT_CONFIDENT to ready().copy(plan = plan(window = window(WindowConfidence.NOT_CONFIDENT))),
            AutoArmHold.BEFORE_WINDOW to ready().copy(nowWallMillis = start - 1, plan = plan(plannedAt = start - hour)),
            AutoArmHold.WINDOW_ENDING to ready().copy(nowWallMillis = end - AutoArmPolicy.MIN_REMAINING_MILLIS + 1, plan = plan(plannedAt = start)),
            AutoArmHold.WINDOW_ENDING to ready().copy(nowWallMillis = end + hour, plan = plan(plannedAt = start)),
            AutoArmHold.ALREADY_RAN_TONIGHT to ready().copy(lastShiftArmedWallMillis = start + 5 * 60_000L),
            AutoArmHold.ALREADY_RAN_TONIGHT to ready().copy(lastShiftArmedWallMillis = start - hour),
            AutoArmHold.NO_LIMITS to ready().copy(limits = null),
            AutoArmHold.NO_LIMITS to ready().copy(plan = plan(plannedWith = null)),
            AutoArmHold.REFUSED_BY_LIMITS to ready().copy(plan = plan(template = template.copy(splitTiles = 0))),
            AutoArmHold.REFUSED_BY_LIMITS to ready().copy(limits = limits.copy(capsExpiryUnix = now / 1000 - 1)),
            AutoArmHold.REFUSED_BY_LIMITS to ready().copy(limits = limits.copy(capRoundLamports = 0)),
            AutoArmHold.NOT_A_NIGHT_PLAN to ready().copy(plan = plan(template = template.copy(day = true))),
            AutoArmHold.NO_BUDGET to ready().copy(plan = plan(night = 0)),
            AutoArmHold.NO_BUDGET to ready().copy(plan = plan(night = null)),
            AutoArmHold.NO_BUDGET to ready().copy(plan = plan(night = template.digLamports - 1)),
            AutoArmHold.NO_BUDGET to ready().copy(limits = limits.copy(spentWeekLamports = limits.capWeekLamports)),
            AutoArmHold.NO_BUDGET to ready().copy(limits = limits.copy(capShiftLamports = template.digLamports - 1)),
        )
        for ((want, situation) in cases) assertEquals(want, reason(situation))
        assertEquals("every reason to hold is exercised", AutoArmHold.entries.toSet(), cases.map { it.first }.toSet())

        // Boundaries: exactly settled, exactly at the window's start, exactly 30 minutes left.
        assertTrue(AutoArmPolicy.decide(ready().copy(darkForMillis = AutoArmPolicy.SETTLE_MILLIS)) is AutoArmDecision.Arm)
        assertTrue(AutoArmPolicy.decide(ready().copy(nowWallMillis = start, plan = plan(plannedAt = start - hour))) is AutoArmDecision.Arm)
        assertTrue(AutoArmPolicy.decide(ready().copy(nowWallMillis = end - AutoArmPolicy.MIN_REMAINING_MILLIS, plan = plan(plannedAt = start))) is AutoArmDecision.Arm)
        assertTrue("a plan exactly 12 hours old", AutoArmPolicy.decide(ready().copy(plan = plan(plannedAt = now - AutoArmPolicy.MAX_PLAN_AGE_MILLIS))) is AutoArmDecision.Arm)
        // A focus-only plan deploys nothing, so it needs no budget.
        val focus = ready().copy(plan = plan(template = PlanTemplate(0, 0, 0, 0, focusOnly = true), night = null))
        assertTrue((AutoArmPolicy.decide(focus) as AutoArmDecision.Arm).plan.focusOnly)
    }

    @Test
    fun `the plan is re-checked against the limits as they are now, not as they were`() {
        // Since the plan was made, the wallet lowered its cost ceiling and per-round cap, and
        // the caps now run out two hours into the window.
        val expiry = start / 1000 + 2 * 3_600
        val lowered = limits.copy(capMaxCost = 400_000_000, capRoundLamports = 600_000, capsExpiryUnix = expiry)
        val d = AutoArmPolicy.decide(ready().copy(limits = lowered, plan = plan(night = 60_000_000))) as AutoArmDecision.Arm
        assertEquals(400_000_000L, d.plan.maxEvCost)
        assertEquals(600_000L, d.plan.digLamports)
        assertEquals(expiry, d.plan.windowEndUnix)

        // Raised limits change nothing: the plan was clamped to what was signed when it was made.
        val raised = limits.copy(capMaxCost = 2_000_000_000, capRoundLamports = 50_000_000)
        val generous = PlanTemplate(maxEvCost = 1_900_000_000, digLamports = 40_000_000, splitTiles = 4, soloTiles = 0)
        val e = AutoArmPolicy.decide(ready().copy(limits = raised, plan = plan(template = generous))) as AutoArmDecision.Arm
        assertEquals("still the old ceiling", limits.capMaxCost, e.plan.maxEvCost)
        assertEquals(limits.capRoundLamports, e.plan.digLamports)
    }

    // ------------------------------------------------------------------ property

    private fun randomSituation(rnd: Random): AutoArmSituation {
        val states = listOf(
            ShiftState.Idle, ShiftState.Idle, ShiftState.Idle, ShiftState.Armed(spec, 0), ShiftState.Down(spec, 0, 0),
            ShiftState.Cooling(spec, 0, 1, CoolReason.SCREEN_ON, 0), ShiftState.Broken(spec, 0, BreakReason.UNLOCKED), ShiftState.Frozen(7, 0),
        )
        fun mostly() = rnd.nextInt(8) != 0
        val l = limits.copy(
            capMaxCost = rnd.nextInt(1_000_000_000).toLong(), capRoundLamports = rnd.nextInt(3_000_000).toLong(),
            capShiftLamports = rnd.nextInt(200_000_000).toLong(), spentWeekLamports = rnd.nextInt(600_000_000).toLong(),
            capsExpiryUnix = start / 1000 + rnd.nextInt(40_000) - 5_000,
        )
        val t = PlanTemplate(rnd.nextInt(2_000_000_000).toLong(), rnd.nextInt(4_000_000).toLong(), rnd.nextInt(18), rnd.nextInt(12), 1 + rnd.nextInt(3), rnd.nextInt(10) == 0, rnd.nextInt(10) == 0)
        val confidence = if (mostly()) WindowConfidence.CONFIDENT else WindowConfidence.entries[rnd.nextInt(3)]
        return AutoArmSituation(
            enabled = mostly(),
            nowWallMillis = start + rnd.nextInt(10 * 3_600_000) - 3_600_000L,
            state = states[rnd.nextInt(states.size)],
            signals = Signals(mostly(), !mostly(), mostly()),
            darkForMillis = rnd.nextInt(300_000).toLong(),
            rigKeyReady = mostly(), rigRegistered = mostly(), chainShiftOpen = !mostly(),
            plan = if (mostly()) plan(window = if (mostly()) window(confidence) else null, template = t, plannedWith = if (mostly()) limits else null,
                night = if (mostly()) rnd.nextInt(100_000_000).toLong() else null, plannedAt = start - rnd.nextInt(14 * 3_600_000)) else null,
            limits = if (mostly()) l else null,
            lastShiftArmedWallMillis = if (mostly()) start - 20 * hour else start + rnd.nextInt(3_600_000),
        )
    }

    @Test
    fun `whenever it arms, every bound holds - randomized`() {
        val rnd = Random(77)
        var armed = 0
        repeat(200_000) {
            val s = randomSituation(rnd)
            val d = AutoArmPolicy.decide(s) as? AutoArmDecision.Arm ?: return@repeat
            armed++
            val l = s.limits!!
            val planned = s.plan!!.signable!!
            val w = s.plan.window!!
            assertTrue(s.enabled)
            assertEquals(ShiftState.Idle, s.state)
            assertTrue("dark on the charger, and settled", s.signals.faceDown && !s.signals.screenOn && s.signals.charging && s.darkForMillis >= AutoArmPolicy.SETTLE_MILLIS)
            assertTrue(s.rigKeyReady && s.rigRegistered && !s.chainShiftOpen)
            assertTrue("inside a confident window", w.confident && s.nowWallMillis >= w.startWallMillis && s.nowWallMillis <= w.endWallMillis - AutoArmPolicy.MIN_REMAINING_MILLIS)
            val nowUnix = s.nowWallMillis / 1000
            assertTrue("inside the wallet's limits as they are now", d.plan.maxEvCost <= l.capMaxCost && d.plan.digLamports <= l.capRoundLamports)
            assertTrue("before the caps expire", nowUnix <= l.capsExpiryUnix && d.plan.windowEndUnix <= l.capsExpiryUnix)
            assertTrue("never beyond the planned window", d.plan.windowEndUnix <= w.endWallMillis / 1000 && d.plan.windowStartUnix == w.startWallMillis / 1000)
            assertTrue("never above what was planned", d.plan.maxEvCost <= planned.maxEvCost && d.plan.digLamports <= planned.digLamports)
            assertFalse("a Night Shift", d.plan.day)
            if (!d.plan.focusOnly) {
                assertTrue("a budget for at least one dig", d.nightLamports!! >= d.plan.digLamports && l.remainingWeekLamports >= d.plan.digLamports)
                assertTrue(d.plan.digLamports > 0 && d.plan.splitTiles + d.plan.soloTiles >= 1)
            }
            d.plan.toShiftPlan() // always encodable
        }
        println("auto-arm policy: armed $armed of 200000 random situations")
        assertTrue("the random situations reach the arming path: $armed", armed > 200)
    }
}
