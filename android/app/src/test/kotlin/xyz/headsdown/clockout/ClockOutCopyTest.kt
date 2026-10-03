package xyz.headsdown.clockout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.headsdown.core.chain.accounts.OreClaimEstimate
import xyz.headsdown.core.chain.clockout.BondOutcome
import xyz.headsdown.core.chain.clockout.ShiftOutcome
import xyz.headsdown.core.keys.ShiftEndReason
import xyz.headsdown.feature.reveal.haul.HonestCopy
import java.time.ZoneId

/** The clock-out screen's words: amounts, what ORE charges, and nothing that reads as a promise. */
class ClockOutCopyTest {

    private val utc = ZoneId.of("UTC")

    private companion object {
        /** 2026-10-04 08:00:00 UTC. */
        const val WINDOW_END = 1_791_100_800L
    }

    private fun facts(
        shift: ShiftOutcome = ShiftOutcome.Ends(ShiftEndReason.COMPLETED),
        bond: BondOutcome = BondOutcome.None,
        sol: ULong = 0uL,
        nothing: Boolean = false,
        refined: ULong = 1_000uL,
        unrefined: ULong = 20_000_000uL,
        claim: OreClaimEstimate? = OreClaimEstimate(refined, unrefined, unrefined / 10uL),
    ) = ClockOutFacts(shift, bond, sol, nothing, refined, unrefined, claim)

    @Test
    fun `ORE amounts are atoms at eleven decimals and dust never reads as zero`() {
        assertEquals("1", ClockOutCopy.ore(100_000_000_000uL))
        assertEquals("0.0002", ClockOutCopy.ore(20_000_000uL))
        assertEquals("1.23456789", ClockOutCopy.ore(123_456_789_123uL))
        assertEquals("<0.00000001", ClockOutCopy.ore(999uL))
        assertEquals("0", ClockOutCopy.ore(0uL))
        assertEquals("100", ClockOutCopy.skr(100_000_000uL))
        assertEquals("0.5", ClockOutCopy.skr(500_000uL))
    }

    @Test
    fun `a completed shift with a bond says the bond comes back`() {
        val lines = ClockOutCopy.lines(facts(bond = BondOutcome.Released(100_000_000uL)), utc)
        assertTrue(lines.shift, lines.shift.contains("seals it as completed"))
        assertEquals("Your Focus Bond comes back to your wallet: 100 SKR.", lines.bond)
        assertEquals("In your ORE Miner: 0.00020001 ORE (0.00000001 refined, 0.0002 unrefined).", lines.ore)
        assertNull(lines.sol)
    }

    @Test
    fun `the claim line states what arrives and what ORE keeps`() {
        val claim = ClockOutCopy.lines(facts(), utc).claim!!
        // 1,000 refined + 20,000,000 unrefined - 2,000,000 fee = 18,001,000 atoms.
        assertTrue(claim, claim.contains("about 0.00018001 ORE"))
        assertTrue(claim, claim.contains("ORE keeps 0.00002 ORE"))
        assertTrue(claim, claim.contains("10% refining fee"))
        val noFee = ClockOutCopy.claim(OreClaimEstimate(5_000_000uL, 0uL, 0uL))
        assertEquals("Claiming all now sends about 0.00005 ORE to your wallet, with no refining fee.", noFee)
    }

    @Test
    fun `nothing in the Miner means no claim line and nothing to choose`() {
        val f = facts(shift = ShiftOutcome.NoOpenShift, nothing = true, refined = 0uL, unrefined = 0uL, claim = OreClaimEstimate(0uL, 0uL, 0uL))
        val lines = ClockOutCopy.lines(f, utc)
        assertEquals("No shift is open.", lines.shift)
        assertEquals("No ORE in your Miner yet.", lines.ore)
        assertNull(lines.claim)
        assertFalse(f.canClaim)
        assertFalse(f.somethingToSign(claimAll = true, endEarly = true))
        // No Miner account at all reads the same.
        assertNull(ClockOutCopy.lines(f.copy(fullClaim = null), utc).claim)
    }

    @Test
    fun `claiming is the only thing to sign when no shift is open`() {
        val f = facts(shift = ShiftOutcome.NoOpenShift, nothing = true)
        assertFalse(f.somethingToSign(claimAll = false, endEarly = false))
        assertTrue(f.somethingToSign(claimAll = true, endEarly = false))
    }

    @Test
    fun `a shift inside its window is left open and says until when`() {
        // 2026-10-04 08:00:00 UTC.
        val open = ShiftOutcome.LeftOpen(ShiftEndReason.MANUAL, WINDOW_END)
        val line = ClockOutCopy.shift(open, utc)
        assertEquals(
            "Your shift is still inside its window (until 08:00), so clocking out leaves it open. " +
                "Left alone, it seals as completed once the window is over.",
            line,
        )
        assertEquals("until 09:00", Regex("until \\d\\d:\\d\\d").find(ClockOutCopy.shift(open, ZoneId.of("Africa/Lagos")))!!.value)
        // A shift that is cooling or has no dark round yet is not promised a completed seal.
        assertFalse(ClockOutCopy.shift(ShiftOutcome.LeftOpen(ShiftEndReason.PICKUP, WINDOW_END), utc).contains("completed"))
        assertFalse(ClockOutCopy.shift(ShiftOutcome.LeftOpen(ShiftEndReason.LEASE_LAPSE, WINDOW_END), utc).contains("completed"))
    }

    @Test
    fun `ending early states the lost streak and the forfeit before the choice is made`() {
        val f = facts(
            shift = ShiftOutcome.LeftOpen(ShiftEndReason.MANUAL, 1_790_028_800L),
            bond = BondOutcome.StaysLocked(50_000_000uL),
            nothing = true,
        )
        assertTrue(f.canEndEarly)
        assertEquals("Your Focus Bond of 50 SKR stays locked until the shift is sealed.", ClockOutCopy.bond(f.bond))
        val warning = ClockOutCopy.endEarlyWarning(f)!!
        assertTrue(warning, warning.contains("does not count for your streak"))
        assertTrue(warning, warning.contains("Your Focus Bond of 50 SKR is forfeit."))
        assertTrue(warning, warning.contains("cannot be undone"))

        val ended = f.endedEarly()
        assertEquals(ShiftOutcome.Ends(ShiftEndReason.MANUAL), ended.shift)
        assertEquals(BondOutcome.Forfeit(50_000_000uL, ShiftEndReason.MANUAL), ended.bond)
        assertTrue(ClockOutCopy.bond(ended.bond)!!.contains("is forfeit"))
        assertTrue(f.somethingToSign(claimAll = false, endEarly = true))
        assertFalse(f.somethingToSign(claimAll = false, endEarly = false))

        // Without a bond the warning says nothing about one; a settled shift has no such choice.
        assertFalse(ClockOutCopy.endEarlyWarning(f.copy(bond = BondOutcome.None))!!.contains("Focus Bond"))
        assertNull(ClockOutCopy.endEarlyWarning(facts()))
        assertEquals(facts(), facts().endedEarly())
    }

    @Test
    fun `a shift that did not complete says why and that the bond is forfeit`() {
        val lines = ClockOutCopy.lines(
            facts(shift = ShiftOutcome.Ends(ShiftEndReason.UNLOCKED), bond = BondOutcome.Forfeit(10_000_000uL, ShiftEndReason.UNLOCKED)),
            utc,
        )
        assertEquals("Clocking out seals your shift as ended early: the phone was unlocked. It does not count for your streak.", lines.shift)
        assertTrue(lines.bond!!, lines.bond!!.startsWith("Your Focus Bond of 10 SKR is forfeit: the shift did not complete."))
        assertTrue(lines.bond!!, lines.bond!!.contains("The team keeps none of it."))
    }

    @Test
    fun `SOL the Miner hands back is stated as an amount`() {
        assertEquals("0.0123 SOL that ORE handed back to your Miner goes to your wallet.", ClockOutCopy.lines(facts(sol = 12_300_000uL), utc).sol)
    }

    @Test
    fun `the closing line lists only what the signed plan did`() {
        assertEquals("Confirmed on-chain.", ClockOutCopy.done(ShiftOutcome.NoOpenShift, BondOutcome.None, 0uL, null))
        assertEquals(
            "Confirmed on-chain. Shift sealed as completed. Focus Bond back in your wallet: 100 SKR. 0.001 SOL back in your wallet. " +
                "About 0.00018001 ORE claimed to your wallet.",
            ClockOutCopy.done(
                ShiftOutcome.Ends(ShiftEndReason.COMPLETED),
                BondOutcome.Released(100_000_000uL),
                1_000_000uL,
                OreClaimEstimate(1_000uL, 20_000_000uL, 2_000_000uL),
            ),
        )
        assertEquals(
            "Confirmed on-chain. Shift sealed as ended early. Focus Bond forfeit: 50 SKR.",
            ClockOutCopy.done(ShiftOutcome.Ends(ShiftEndReason.MANUAL), BondOutcome.Forfeit(50_000_000uL, ShiftEndReason.MANUAL), 0uL, null),
        )
        // A shift left open and a bond that stays locked are not reported as done.
        assertEquals(
            "Confirmed on-chain. About 0.00005 ORE claimed to your wallet.",
            ClockOutCopy.done(
                ShiftOutcome.LeftOpen(ShiftEndReason.MANUAL, 1L),
                BondOutcome.StaysLocked(1uL),
                0uL,
                OreClaimEstimate(5_000_000uL, 0uL, 0uL),
            ),
        )
    }

    @Test
    fun `every sentence the screen can show passes the honest-copy rule`() {
        val shifts = listOf(ShiftOutcome.NoOpenShift) +
            ShiftEndReason.entries.map { ShiftOutcome.Ends(it) } +
            ShiftEndReason.entries.map { ShiftOutcome.LeftOpen(it, 1_790_028_800L) }
        val bonds = listOf(
            BondOutcome.None,
            BondOutcome.Released(1_000_000uL),
            BondOutcome.Forfeit(1_000_000uL, ShiftEndReason.PICKUP),
            BondOutcome.StaysLocked(1_000_000uL),
        )
        val text = buildList {
            for (s in shifts) for (b in bonds) {
                val f = facts(shift = s, bond = b, sol = 5_000uL)
                addAll(ClockOutCopy.lines(f, utc).all())
                ClockOutCopy.endEarlyWarning(f)?.let(::add)
                add(ClockOutCopy.done(s, b, 5_000uL, f.fullClaim))
            }
            add(ClockOutCopy.claim(OreClaimEstimate(1uL, 0uL, 0uL)))
            add(ClockOutCopy.ORE_KEPT)
            add(ClockOutCopy.NOTHING_TO_SIGN)
            addAll(listOf(ClockOutModel.UNREADABLE, ClockOutModel.OTHER_WALLET, ClockOutModel.NO_WALLET, ClockOutModel.NOTHING_LEFT, ClockOutModel.NOT_LANDED, ClockOutModel.NOT_CONFIRMED))
        }
        assertTrue(text.size > 100)
        text.forEach { assertEquals(it, emptyList<String>(), HonestCopy.violations(it)) }
    }
}
