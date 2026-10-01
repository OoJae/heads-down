package xyz.headsdown.core.chain.stack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.headsdown.core.chain.HeadsDownProgram
import xyz.headsdown.core.chain.Pubkey
import xyz.headsdown.core.chain.Skr
import xyz.headsdown.core.chain.TestAccounts
import xyz.headsdown.core.chain.accounts.HeadsDownAccounts
import xyz.headsdown.core.chain.accounts.StackSeatAccount
import xyz.headsdown.core.chain.accounts.StackTableAccount
import java.math.BigInteger

/**
 * The Stack arithmetic against the program's own unit tests (`program/src/skr.rs`, `mod tests`):
 * the same inputs, the same outputs, including the exhaustive conservation check.
 */
class StackMathTest {

    private fun settle(bonds: List<Long>, finished: List<Boolean>, bps: Int = StackMath.FINISHER_BPS) =
        StackMath.payouts(bonds.map { it.toULong() }, finished, bps)

    @Test
    fun `everyone finishing gets exactly their bond back`() {
        val s = settle(listOf(100, 100, 100), listOf(true, true, true))
        assertEquals(listOf(100uL, 100uL, 100uL), s.payouts)
        assertEquals(Triple(0uL, 3, 300uL), Triple(s.bury, s.finishers, s.payoutsTotal))
    }

    @Test
    fun `nobody finishing sends everything to bury`() {
        val s = settle(listOf(100, 100), listOf(false, false))
        assertEquals(listOf(0uL, 0uL), s.payouts)
        assertEquals(Triple(200uL, 0, 0uL), Triple(s.bury, s.finishers, s.finisherBonds))
    }

    @Test
    fun `forfeits split 80-20 with the dust to bury`() {
        // 4 x 100, 3 finish: F = 100, 80 to finishers = 26 each (floor), bury gets 20 + 2 dust.
        val s = settle(listOf(100, 100, 100, 100), listOf(true, true, false, true))
        assertEquals(listOf(126uL, 126uL, 0uL, 126uL), s.payouts)
        assertEquals(22uL, s.bury)
        assertEquals(400uL, s.payoutsTotal + s.bury)
        // Real SKR amounts: 4 x 200 SKR, 1 finisher takes 200 + 480.
        val bond = 200uL * Skr.ONE_SKR
        val one = StackMath.payouts(List(4) { bond }, listOf(false, true, false, false), StackMath.FINISHER_BPS)
        assertEquals(bond + 480uL * Skr.ONE_SKR, one.payouts[1])
        assertEquals(120uL * Skr.ONE_SKR, one.bury)
    }

    @Test
    fun `bury-only tables give bonds back and bury every forfeit`() {
        val s = settle(listOf(50, 50, 50), listOf(true, false, true), bps = 0)
        assertEquals(listOf(50uL, 0uL, 50uL), s.payouts)
        assertEquals(50uL, s.bury)
    }

    @Test
    fun `conservation holds for every finisher subset of 1 to 8 seats`() {
        // The program's xorshift64 sequence, so the bonds are the ones its own test uses.
        var seed = 0x1234_5678_9abc_def0uL
        fun next(): ULong {
            seed = seed xor (seed shl 13)
            seed = seed xor (seed shr 7)
            seed = seed xor (seed shl 17)
            return seed
        }
        var cases = 0
        for (n in 1..8) {
            for (mask in 0 until (1 shl n)) {
                for (bps in listOf(0, 8_000, 10_000, 1)) {
                    val bonds = List(n) { 1uL + next() % 5_000_000_000uL }
                    val finished = List(n) { mask and (1 shl it) != 0 }
                    val s = StackMath.payouts(bonds, finished, bps)
                    val total = bonds.fold(0uL) { a, b -> a + b }
                    assertEquals(total, s.payouts.fold(0uL) { a, b -> a + b } + s.bury)
                    assertEquals(total, s.totalBonds)
                    val forfeits = total - s.finisherBonds
                    val pool = (BigInteger(forfeits.toString()) * BigInteger.valueOf(bps.toLong()) / BigInteger.valueOf(10_000)).toString().toULong()
                    val k = finished.count { it }.toULong()
                    if (k == 0uL) {
                        assertEquals(total, s.bury)
                    } else {
                        // Bury gets the complement plus dust below one unit per finisher.
                        assertTrue(s.bury >= forfeits - pool && s.bury <= forfeits - pool + k)
                    }
                    for (i in 0 until n) {
                        if (finished[i]) assertTrue(s.payouts[i] >= bonds[i] && s.payouts[i] <= bonds[i] + forfeits) else assertEquals(0uL, s.payouts[i])
                    }
                    cases++
                }
            }
        }
        assertEquals(4 * (2 + 4 + 8 + 16 + 32 + 64 + 128 + 256), cases)
    }

    @Test
    fun `payout math rejects bad shapes and overflow like the program`() {
        assertThrows(IllegalArgumentException::class.java) { StackMath.payouts(listOf(1uL, 2uL), listOf(true), 8_000) }
        assertThrows(IllegalArgumentException::class.java) { StackMath.payouts(listOf(1uL, 2uL), listOf(true, true), 10_001) }
        assertThrows(IllegalArgumentException::class.java) { StackMath.payouts(List(9) { 1uL }, List(9) { true }, 8_000) }
        assertThrows(ArithmeticException::class.java) { StackMath.payouts(listOf(ULong.MAX_VALUE, 1uL), listOf(true, false), 8_000) }
        // Bonds near u64::MAX overflow the u128 product: a clean error, never a wrong number.
        val half = ULong.MAX_VALUE / 2uL
        assertThrows(ArithmeticException::class.java) { StackMath.payouts(listOf(half, half), listOf(true, false), 8_000) }
        // The largest real table: 8 seats at the cap, one finisher.
        val finished = List(8) { it == 3 }
        val s = StackMath.payouts(List(8) { Skr.STACK_BOND_CAP }, finished, StackMath.FINISHER_BPS)
        assertEquals(Skr.STACK_BOND_CAP + 7uL * Skr.STACK_BOND_CAP * 8uL / 10uL, s.payouts[3])
        assertEquals(8uL * Skr.STACK_BOND_CAP, s.payoutsTotal + s.bury)
    }

    @Test
    fun `the finish rule`() {
        // Window 100..=109 (10 rounds), grace 2.
        assertTrue(StackMath.seatFinishes(false, 109uL, 10uL, 100uL, 109uL, 2))
        assertTrue(StackMath.seatFinishes(false, 109uL, 8uL, 100uL, 109uL, 2))
        assertFalse(StackMath.seatFinishes(false, 109uL, 7uL, 100uL, 109uL, 2)) // 3 gaps
        assertFalse(StackMath.seatFinishes(false, 108uL, 10uL, 100uL, 109uL, 2)) // missed the end round
        assertFalse(StackMath.seatFinishes(true, 109uL, 10uL, 100uL, 109uL, 2)) // broke
        assertFalse(StackMath.seatFinishes(false, 0uL, 0uL, 100uL, 109uL, 9)) // never checked in
        assertEquals(10uL, StackMath.windowLen(100uL, 109uL))
        assertEquals(1uL, StackMath.windowLen(5uL, 5uL))
    }

    @Test
    fun `the refund timeout is pessimistic and checked`() {
        assertEquals(1_000L + 10 * 120 + 3 * 86_400, StackMath.refundAfter(1_000, 10uL, 19uL))
        assertThrows(ArithmeticException::class.java) { StackMath.refundAfter(Long.MAX_VALUE, 0uL, 0uL) }
    }

    @Test
    fun `rounds and minutes convert at the measured round length, for display only`() {
        assertEquals(1L, StackMath.typicalMinutes(0uL))
        assertEquals(2L, StackMath.typicalMinutes(1uL)) // 78 s
        assertEquals(60L, StackMath.typicalMinutes(46uL)) // 3,588 s
        assertEquals(1_872L, StackMath.typicalMinutes(1_440uL)) // the longest window: about 31 hours
        assertEquals(1L, StackMath.roundsFor(1))
        assertEquals(24L, StackMath.roundsFor(30)) // 1,800 s / 78 s, rounded up
        assertEquals(47L, StackMath.roundsFor(60))
    }

    // ------------------------------------------------------------------ the table view

    private val host = Pubkey.fromBase58("7xKXtg2CW87d97TXJSDpbD5jBkheTqA83TZRuJosgAsU")
    private val tableAddress = HeadsDownProgram.stackTable(host, 7uL).address
    private val players = List(4) { Pubkey(ByteArray(32) { _ -> (it + 11).toByte() }) }

    private fun table(
        status: Int = 0,
        seatCount: Int = 4,
        flags: Int = 0,
        grace: Int = 2,
        refundAfter: Long = 1_790_300_000,
        claimed: Int = 0,
    ): StackTableAccount = HeadsDownAccounts.stackTable(
        tableAddress,
        TestAccounts.info(
            HeadsDownProgram.ID,
            TestAccounts.stackTableBytes(
                host, 7, bond = 200_000_000, startRound = 1_000, endRound = 1_009, graceGaps = grace, flags = flags, status = status,
                seatCount = seatCount, totalBonds = 200_000_000L * seatCount, refundAfterTs = refundAfter, claimedCount = claimed,
            ),
        ),
    )

    private fun seat(i: Int, checked: Long = 0, last: Long = 0, broken: Boolean = false, outcome: Int = 0, payout: Long = 0): StackSeatAccount {
        val rig = HeadsDownProgram.rig(players[i]).address
        return HeadsDownAccounts.stackSeat(
            HeadsDownProgram.stackSeat(tableAddress, rig).address,
            TestAccounts.info(
                HeadsDownProgram.ID,
                TestAccounts.stackSeatBytes(tableAddress, rig, players[i], checkedRounds = checked, lastRound = last, seatIndex = i, broken = broken, outcome = outcome, payout = payout),
            ),
        )
    }

    private val now = 1_790_000_500L

    @Test
    fun `before the window every seat waits and the projection gives every bond back`() {
        val view = StackTableView(table(), List(4) { seat(it) }, 995uL, now)
        assertEquals(StackPhase.GATHERING, view.phase)
        assertEquals(5uL, view.roundsUntilStart)
        assertEquals(10uL, view.roundsLeft)
        assertTrue(view.seats.all { it.standing == SeatStanding.WAITING && it.missedRounds == 0uL && !it.claimable })
        assertEquals(List(4) { 200_000_000uL }, view.seats.map { it.payout })
        assertEquals(0uL, view.projection!!.bury)
        assertEquals(players[2], view.seatOf(players[2])!!.seat.authority)
        assertNull(view.seatOf(host))
    }

    @Test
    fun `during the window - dark, missing, not seen, broke and out of grace`() {
        // Round 1,005 is live: rounds 1,000..1,004 are over (five of them).
        val seats = listOf(
            seat(0, checked = 6, last = 1_005), // every round, this one included
            seat(1, checked = 3, last = 1_002), // counted three, then silent: 2 missed, inside grace
            seat(2, checked = 2, last = 1_004), // 3 missed: over the grace of 2
            seat(3, checked = 4, last = 1_003, broken = true),
        )
        val view = StackTableView(table(), seats, 1_005uL, now)
        assertEquals(StackPhase.RUNNING, view.phase)
        assertEquals(0uL, view.roundsUntilStart)
        assertEquals(5uL, view.roundsLeft)
        assertEquals(
            listOf(SeatStanding.DARK, SeatStanding.MISSING, SeatStanding.OUT_OF_GRACE, SeatStanding.BROKE),
            view.seats.map { it.standing },
        )
        assertEquals(listOf(0uL, 2uL, 3uL, 1uL), view.seats.map { it.missedRounds })
        // If it ended like this: two still in, two forfeits. 400 forfeited, 80% split: 200 + 160 each, 80 to Bury.
        assertEquals(listOf(360_000_000uL, 360_000_000uL, 0uL, 0uL), view.seats.map { it.payout })
        assertEquals(80_000_000uL, view.projection!!.bury)
        assertTrue(view.seats.none { it.claimable })
        // A seat counted in the previous round is still dark; one with no round yet is "not seen".
        val early = StackTableView(table(), listOf(seat(0, checked = 1, last = 1_000), seat(1), seat(2), seat(3)), 1_001uL, now)
        assertEquals(SeatStanding.DARK, early.seats[0].standing)
        assertEquals(SeatStanding.NOT_SEEN, early.seats[1].standing)
        assertEquals(1uL, early.seats[1].missedRounds)
        // In the very first round nothing is missed yet.
        val first = StackTableView(table(), List(4) { seat(it) }, 1_000uL, now)
        assertTrue(first.seats.all { it.standing == SeatStanding.NOT_SEEN && it.missedRounds == 0uL })
    }

    @Test
    fun `after the window, before settle, the finish rule decides - exactly as the program will`() {
        val seats = listOf(
            seat(0, checked = 10, last = 1_009),
            seat(1, checked = 8, last = 1_009), // two gaps: inside grace
            seat(2, checked = 9, last = 1_008), // missed the last round
            seat(3, checked = 7, last = 1_009), // three gaps
        )
        val view = StackTableView(table(), seats, 1_010uL, now)
        assertEquals(StackPhase.AWAITING_SETTLE, view.phase)
        assertEquals(0uL, view.roundsLeft)
        assertEquals(
            listOf(SeatStanding.FINISHED, SeatStanding.FINISHED, SeatStanding.FORFEITED, SeatStanding.FORFEITED),
            view.seats.map { it.standing },
        )
        assertEquals(listOf(360_000_000uL, 360_000_000uL, 0uL, 0uL), view.seats.map { it.payout })
        assertTrue(view.seats.none { it.claimable })
        // A bury-only table: finishers get only their own bond back.
        val buryOnly = StackTableView(table(flags = 2), seats, 1_010uL, now)
        assertEquals(listOf(200_000_000uL, 200_000_000uL, 0uL, 0uL), buryOnly.seats.map { it.payout })
        assertEquals(400_000_000uL, buryOnly.projection!!.bury)
    }

    @Test
    fun `a settled table shows what the chain wrote, and seats close as they claim`() {
        // Seat 0 already claimed (and closed); the others carry their settled outcome.
        val seats = listOf(seat(1, 10, 1_009, outcome = 1, payout = 360_000_000), seat(2, 3, 1_002, outcome = 2), seat(3, 1, 1_000, outcome = 2))
        val view = StackTableView(table(status = 1, claimed = 1), seats, 1_020uL, now)
        assertEquals(StackPhase.SETTLED, view.phase)
        assertNull(view.projection)
        assertEquals(listOf(SeatStanding.FINISHED, SeatStanding.FORFEITED, SeatStanding.FORFEITED), view.seats.map { it.standing })
        assertEquals(listOf(360_000_000uL, 0uL, 0uL), view.seats.map { it.payout })
        // Everyone can claim: a forfeited seat claims nothing but gets its seat rent back.
        assertTrue(view.seats.all { it.claimable })
        // Four joined, one claimed and closed, three read: nothing is missing.
        assertEquals(0, view.seatsMissingFromRead)
        assertEquals(1, StackTableView(table(status = 1), seats, 1_020uL, now).seatsMissingFromRead)
    }

    @Test
    fun `an unsettled table past its timeout refunds every bond`() {
        val seats = listOf(seat(0, 10, 1_009), seat(1, 0, 0, broken = true))
        val due = StackTableView(table(seatCount = 2, refundAfter = now - 1), seats, 1_500uL, now)
        assertEquals(StackPhase.REFUND_DUE, due.phase)
        assertNull(due.projection)
        assertTrue(due.seats.all { it.standing == SeatStanding.REFUND && it.payout == 200_000_000uL && it.claimable })
        val refunding = StackTableView(table(status = 2, seatCount = 2), seats, 1_500uL, now)
        assertEquals(StackPhase.REFUNDING, refunding.phase)
        assertTrue(refunding.seats.all { it.standing == SeatStanding.REFUND && it.claimable })
        // Exactly at the timeout the table is still waiting for a settle.
        assertEquals(StackPhase.AWAITING_SETTLE, StackTableView(table(seatCount = 2, refundAfter = now), seats, 1_500uL, now).phase)
    }

    @Test
    fun `a read that misses seats says so and projects nothing`() {
        val view = StackTableView(table(), listOf(seat(0), seat(1)), 995uL, now)
        assertEquals(2, view.seatsMissingFromRead)
        assertNull(view.projection)
        assertEquals(listOf(0uL, 0uL), view.seats.map { it.payout })
    }
}
