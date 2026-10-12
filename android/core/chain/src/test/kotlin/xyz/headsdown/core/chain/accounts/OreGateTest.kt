package xyz.headsdown.core.chain.accounts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.headsdown.core.chain.Ore
import java.math.BigInteger

/**
 * The vectors of `programs/heads-down/program/src/logic.rs` (`ema_ev_matches_the_python_reference`,
 * `gate_uses_the_tighter_of_plan_and_cap`), value for value: the phone and the program must give
 * the same answer for the same Board and Treasury.
 */
class OreGateTest {

    private val one = Ore.ONE_ORE.toULong()
    private val u64Max = BigInteger.ONE.shiftLeft(64) - BigInteger.ONE

    @Test
    fun `ema_ev matches the program's vectors`() {
        val cases = listOf(
            // pot = 0: ema * 6/5
            Triple(1_000_000_000uL, 0uL, 1_200_000_000L),
            // pot = 500 ORE: ema * 1.2 / 2
            Triple(1_000_000_000uL, 500uL * one, 600_000_000L),
            // live Board/Treasury fixture (round 422,685): ema 903,580,117, pot 360.8 ORE
            Triple(903_580_117uL, 36_080_000_000_000uL, 629_818_854L),
            Triple(1uL, 0uL, 1L),
            Triple(0uL, 123uL * one, 0L),
        )
        for ((ema, pot, want) in cases) {
            assertEquals("ema $ema pot $pot", BigInteger.valueOf(want), OreGate.emaEv(ema, pot))
        }
        // Extreme inputs: above a u64 with an empty pot, and the program's own figure at both maxima.
        assertTrue(OreGate.emaEv(ULong.MAX_VALUE, 0uL) > u64Max)
        assertEquals(BigInteger.valueOf(59_999_837_370_114L), OreGate.emaEv(ULong.MAX_VALUE, ULong.MAX_VALUE))
    }

    @Test
    fun `the gate uses the tighter of plan and cap`() {
        fun gate(emaEv: Long, plan: ULong, cap: ULong) = OreGate.gateOpen(BigInteger.valueOf(emaEv), plan, cap)
        assertTrue(gate(100, 100uL, 200uL))
        assertFalse(gate(101, 100uL, 200uL))
        assertFalse(gate(101, 200uL, 100uL))
        assertTrue(gate(0, 0uL, 0uL))
        assertFalse(OreGate.gateOpen(u64Max + BigInteger.ONE, ULong.MAX_VALUE, ULong.MAX_VALUE))
        // At a u64's last value the ceiling still compares as unsigned.
        assertTrue(OreGate.gateOpen(u64Max, ULong.MAX_VALUE, ULong.MAX_VALUE))
    }

    @Test
    fun `the figure is taken from the Board's EMA and the Treasury's Motherlode`() {
        val board = OreBoard(roundId = 422_685uL, startSlot = 0uL, endSlot = 0uL, productionCostEma = 903_580_117uL)
        val treasury = OreTreasury(motherlode = 36_080_000_000_000uL)
        assertEquals(BigInteger.valueOf(629_818_854L), OreGate.emaEv(board, treasury))
        // 0.63 SOL per ORE: under a Hunter ceiling of 0.67, over a Steady plan of 0.53.
        assertTrue(OreGate.gateOpen(OreGate.emaEv(board, treasury), 670_000_000uL, 670_000_000uL))
        assertFalse(OreGate.gateOpen(OreGate.emaEv(board, treasury), 530_000_000uL, 670_000_000uL))
    }
}
