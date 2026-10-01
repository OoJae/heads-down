package xyz.headsdown.rig

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.headsdown.core.chain.clockin.ClockInRequest
import xyz.headsdown.feature.shift.ShiftMode

/** The clock-in the tile arms, and its build-time overrides for demo takes. */
class ClockInPolicyTest {

    @Test
    fun `the default build arms the documented Night Shift`() {
        // Unit tests run the debug variant built without -Pheadsdown.policy.* overrides.
        val policy = ClockInPolicy.fromBuildConfig()
        assertEquals(ClockInPolicy(), policy)
        assertEquals(ShiftMode.NIGHT, policy.mode)
        assertEquals(
            ClockInRequest(
                shiftBudgetLamports = 20_000_000uL,
                weeklyBudgetLamports = 140_000_000uL,
                capMaxCostPerOre = 670_000_000uL,
                planMaxEvCostPerOre = 530_000_000uL,
                windowSeconds = 8 * 3600,
                digLamports = 1_000_000uL,
                splitTiles = 4,
                soloTiles = 0,
                leaseRounds = 1,
                day = false,
            ),
            policy.request(),
        )
    }

    @Test
    fun `a demo take can change lease, costs, dig size, tiles and make it a Day Shift`() {
        val demo = ClockInPolicy(
            day = true, windowSeconds = 25 * 60, leaseRounds = 3, planMaxEvCostPerOre = 900_000_000uL,
            capMaxCostPerOre = 1_000_000_000uL, digLamports = 2_000_000uL, splitTiles = 10, soloTiles = 2,
        )
        val r = demo.request()
        assertTrue(r.day)
        assertEquals(ShiftMode.DAY, demo.mode)
        assertEquals(3, r.leaseRounds)
        assertEquals(900_000_000uL, r.planMaxEvCostPerOre)
        assertEquals(1_000_000_000uL, r.capMaxCostPerOre)
        assertEquals(2_000_000uL, r.digLamports)
        assertEquals(10, r.splitTiles)
        assertEquals(2, r.soloTiles)
        assertEquals(20, ClockInPolicy.plannedRounds(25 * 60))
        assertFalse(ClockInPolicy().request().day)
    }

    @Test
    fun `an impossible policy fails before anything is signed`() {
        assertThrows(IllegalArgumentException::class.java) { ClockInPolicy(leaseRounds = 4).request() }
        assertThrows(IllegalArgumentException::class.java) { ClockInPolicy(planMaxEvCostPerOre = 2_000_000_000uL).request() }
        assertThrows(IllegalArgumentException::class.java) { ClockInPolicy(splitTiles = 0, soloTiles = 0).request() }
        assertThrows(IllegalArgumentException::class.java) { ClockInPolicy(digLamports = 500_000uL).request() }
    }
}
