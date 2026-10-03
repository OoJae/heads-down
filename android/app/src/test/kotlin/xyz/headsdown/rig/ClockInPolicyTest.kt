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
    fun `the amounts are stated from the policy alone, before the wallet opens`() {
        // 0.02 SOL a shift and 0.14 a week on squares; the deposit bound adds one fee ceiling
        // (0.0001 SOL) per possible dig: 20 x 100_000 lamports.
        assertEquals(
            "This shift can place up to 0.02 SOL on ORE squares (0.14 SOL a week). " +
                "Clock-in moves at most 0.022 SOL into your own ORE Automation; the first one also pays one-time account rent.",
            ClockInPolicy().disclosure(),
        )
        // With a Focus Bond chosen, the same line says so.
        assertTrue(ClockInPolicy().disclosure(focusBondSkr = 50_000_000uL).endsWith("It also locks your 50 SKR Focus Bond."))
        assertEquals("1", ClockInPolicy.sol(1_000_000_000uL))
        assertEquals("0.000000001", ClockInPolicy.sol(1uL))
        assertEquals("12.5", ClockInPolicy.sol(12_500_000_000uL))
        assertEquals("0", ClockInPolicy.sol(0uL))
        // Nothing in it promises a return.
        assertFalse(Regex("\\b(earn|yield|profit|income|reward)", RegexOption.IGNORE_CASE).containsMatchIn(ClockInPolicy().disclosure()))
    }

    @Test
    fun `the toast after a clock-in says what else the transaction did`() {
        assertEquals(null, ClockInPolicy.noteFor(unfroze = false, bondLocked = 0uL, bondReleased = 0uL, bondDeferred = false))
        assertEquals("Focus Bond locked: 10 SKR.", ClockInPolicy.noteFor(false, 10_000_000uL, 0uL, false))
        assertEquals(
            "Rig unfrozen. Last shift's bond is back: 50 SKR. Focus Bond locked: 100 SKR.",
            ClockInPolicy.noteFor(true, 100_000_000uL, 50_000_000uL, false),
        )
        assertEquals("No Focus Bond this time: the first clock-in had no room for it.", ClockInPolicy.noteFor(false, 0uL, 0uL, true))
        // The offered bonds are small, fixed and far below the program's cap.
        assertEquals(listOf("Off", "10 SKR", "50 SKR", "100 SKR"), FocusBondSetting.CHOICES.map(FocusBondSetting::label))
        assertTrue(FocusBondSetting.CHOICES.all { it <= xyz.headsdown.core.chain.Skr.FOCUS_BOND_CAP })
    }

    @Test
    fun `an impossible policy fails before anything is signed`() {
        assertThrows(IllegalArgumentException::class.java) { ClockInPolicy(leaseRounds = 4).request() }
        assertThrows(IllegalArgumentException::class.java) { ClockInPolicy(planMaxEvCostPerOre = 2_000_000_000uL).request() }
        assertThrows(IllegalArgumentException::class.java) { ClockInPolicy(splitTiles = 0, soloTiles = 0).request() }
        assertThrows(IllegalArgumentException::class.java) { ClockInPolicy(digLamports = 500_000uL).request() }
    }
}
