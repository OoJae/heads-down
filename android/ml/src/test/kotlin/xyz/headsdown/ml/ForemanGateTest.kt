package xyz.headsdown.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ForemanGateTest {

    private fun decision(verdict: PickupVerdict) =
        PickupDecision(if (verdict == PickupVerdict.PICKUP) 0.9 else 0.1, 0.0, verdict, null, "test")

    @Test
    fun `the classifier can only veto heartbeats, never create them`() {
        val decisions = listOf(null, decision(PickupVerdict.PICKUP), decision(PickupVerdict.NOT_PICKUP),
            PickupDecision.failClosed(FailClosedReason.GAP, "test"))
        for (dark in listOf(true, false)) {
            for (d in decisions) {
                val allowed = ForemanGate.heartbeatAllowed(dark, d)
                if (!dark) assertFalse("not dark (screen on, unlocked, lifted or unplugged): never", allowed)
                if (d?.isPickup == true) assertFalse("a pickup verdict always stops heartbeats", allowed)
            }
        }
        assertTrue(ForemanGate.heartbeatAllowed(true, decision(PickupVerdict.NOT_PICKUP)))
        assertTrue(ForemanGate.heartbeatAllowed(true, null))
    }

    @Test
    fun `a pickup asks for BREAK reason 1, a bump for nothing`() {
        assertEquals(1, ForemanGate.breakReason(decision(PickupVerdict.PICKUP)))
        assertEquals(1, ForemanGate.breakReason(PickupDecision.failClosed(FailClosedReason.TOO_FEW_SAMPLES, "x")))
        assertNull(ForemanGate.breakReason(decision(PickupVerdict.NOT_PICKUP)))
    }

    private val caps = WalletCaps(
        capWeekLamports = 500_000_000, capShiftLamports = 120_000_000, capRoundLamports = 2_000_000,
        capMaxCost = 670_000_000, capsExpiryTs = 1_800_000_000, spentWeekLamports = 100_000_000,
    )

    private fun proposal(
        maxEv: Long = 530_000_000, dig: Long = 1_000_000, split: Int = 4, solo: Int = 0, lease: Int = 1,
        focus: Boolean = false, start: Long = 1_790_000_000, end: Long = 1_790_028_800,
    ) = PlanProposal(maxEv, dig, split, solo, lease, focus, false, start, end)

    @Test
    fun `a plan inside the caps passes unchanged`() {
        val p = proposal()
        assertEquals(p, ForemanGate.tighten(caps, p, nowTs = 1_789_999_000))
        assertEquals(400_000_000, caps.remainingWeekLamports)
    }

    @Test
    fun `everything above the wallet caps is clamped down, never up`() {
        val t = ForemanGate.tighten(caps, proposal(maxEv = 9_000_000_000, dig = 50_000_000, split = 30, solo = 20, lease = 9,
            end = 1_900_000_000), nowTs = 1_789_999_000)!!
        assertEquals(caps.capMaxCost, t.maxEvCost)
        assertEquals(caps.capRoundLamports, t.digLamports)
        assertEquals(15, t.splitTiles)
        assertEquals(10, t.soloTiles)
        assertEquals(3, t.leaseRounds)
        assertEquals("the window ends when the caps expire", caps.capsExpiryTs, t.windowEndTs)
    }

    @Test
    fun `invalid plans are dropped, not loosened`() {
        assertNull("caps expired", ForemanGate.tighten(caps, proposal(), nowTs = caps.capsExpiryTs + 1))
        assertNull("window over", ForemanGate.tighten(caps, proposal(end = 1_790_000_100), nowTs = 1_790_000_200))
        assertNull("empty window", ForemanGate.tighten(caps, proposal(start = 1_790_000_000, end = 1_790_000_000), nowTs = 1_789_000_000))
        assertNull("no tiles", ForemanGate.tighten(caps, proposal(split = 0, solo = 0), nowTs = 1_789_000_000))
        assertNull("no SOL per dig", ForemanGate.tighten(caps, proposal(dig = 0), nowTs = 1_789_000_000))
        val focus = ForemanGate.tighten(caps, proposal(split = 0, solo = 0, dig = 0, focus = true), nowTs = 1_789_000_000)
        assertTrue("focus-only shifts deploy nothing and stay valid", focus != null && focus.focusOnly)
    }

    @Test
    fun `tighten is monotone - randomized`() {
        val rnd = Random(42)
        repeat(5_000) {
            val c = WalletCaps(rnd.nextLong(0, 1_000_000_000), rnd.nextLong(0, 300_000_000), rnd.nextLong(0, 5_000_000),
                rnd.nextLong(0, 2_000_000_000), rnd.nextLong(1_700_000_000, 1_900_000_000))
            val start = rnd.nextLong(1_700_000_000, 1_900_000_000)
            val p = PlanProposal(rnd.nextLong(-10, 5_000_000_000), rnd.nextLong(-10, 50_000_000), rnd.nextInt(-3, 40),
                rnd.nextInt(-3, 40), rnd.nextInt(-3, 9), rnd.nextBoolean(), rnd.nextBoolean(), start, start + rnd.nextLong(-100, 100_000))
            val now = rnd.nextLong(1_700_000_000, 1_900_000_000)
            val t = ForemanGate.tighten(c, p, now) ?: return@repeat
            assertTrue(t.maxEvCost in 0..c.capMaxCost && t.maxEvCost <= maxOf(0, p.maxEvCost))
            assertTrue(t.digLamports in 0..c.capRoundLamports && t.digLamports <= maxOf(0, p.digLamports))
            assertTrue(t.splitTiles in 0..15 && t.soloTiles in 0..10 && t.leaseRounds in 1..3)
            assertTrue(t.windowStartTs == p.windowStartTs && t.windowEndTs <= p.windowEndTs && t.windowEndTs <= c.capsExpiryTs)
            assertTrue(t.windowStartTs < t.windowEndTs && now <= t.windowEndTs && now <= c.capsExpiryTs)
            assertTrue(t.focusOnly || (t.splitTiles + t.soloTiles >= 1 && t.digLamports > 0))
        }
    }
}
