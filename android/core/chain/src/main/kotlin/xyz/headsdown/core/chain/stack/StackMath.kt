package xyz.headsdown.core.chain.stack

import xyz.headsdown.core.chain.Pubkey
import xyz.headsdown.core.chain.accounts.SeatOutcome
import xyz.headsdown.core.chain.accounts.StackSeatAccount
import xyz.headsdown.core.chain.accounts.StackStatus
import xyz.headsdown.core.chain.accounts.StackTableAccount
import java.math.BigInteger

/** What `settle_stack` writes, or would write: one payout per seat and the totals. SKR base units. */
data class StackSettlement(
    /** Same order as the bonds passed in. */
    val payouts: List<ULong>,
    /** `B`: every seat's bond. */
    val totalBonds: ULong,
    /** `W`: the finishers' bonds. */
    val finisherBonds: ULong,
    val finishers: Int,
    val payoutsTotal: ULong,
    /** `B - sum(payouts)`: what goes to the Bury auction. */
    val bury: ULong,
)

/**
 * The Stack arithmetic of `program/src/skr.rs` (INTERFACE §11.5), ported line for line so the
 * phone shows before settlement exactly what the program will write at settlement. Integer-only
 * and checked: an overflow throws [ArithmeticException] where the program returns `MathOverflow`.
 */
object StackMath {
    /** Finishers' share of the forfeits, in basis points; the rest (and the dust) goes to Bury. */
    const val FINISHER_BPS = 8_000
    const val BPS_DENOMINATOR = 10_000

    /** Pessimistic ORE round length the program uses to date the refund timeout. */
    const val MAX_ROUND_SECS = 120L

    /** Extra time after the pessimistic window end before an unsettled table refunds every bond. */
    const val REFUND_GRACE_SECS = 3L * 86_400

    /** What a round took on mainnet when measured (`docs/ORE.md` §2). For "about N minutes" only. */
    const val TYPICAL_ROUND_SECS = 78L

    private val U64_MAX = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE)

    /** Rounds in `[start, end]`. */
    fun windowLen(start: ULong, end: ULong): ULong = if (end >= start) (end - start).let { if (it == ULong.MAX_VALUE) it else it + 1uL } else 1uL

    /**
     * A seat finishes iff it never saw a BREAK or FREEZE in its bound shift, it checked in during
     * [end] itself, and it missed at most [grace] window rounds (`skr::seat_finishes`).
     */
    fun seatFinishes(broken: Boolean, lastRound: ULong, checkedRounds: ULong, start: ULong, end: ULong, grace: Long): Boolean {
        val len = windowLen(start, end)
        val gaps = if (len > checkedRounds) len - checkedRounds else 0uL
        return !broken && lastRound == end && gaps <= grace.toULong()
    }

    /**
     * `skr::stack_payouts`. With `F = B - W` forfeited: a finisher gets
     * `bond + floor(F * bps * bond / (10,000 * W))`, everyone else 0, and Bury the rest. With no
     * finisher Bury gets everything. `sum(payouts) + bury == B` always.
     */
    fun payouts(bonds: List<ULong>, finished: List<Boolean>, finisherBps: Int): StackSettlement {
        require(bonds.size == finished.size && bonds.size <= MAX_SEATS && finisherBps in 0..BPS_DENOMINATOR) { "bad table shape" }
        var total = BigInteger.ZERO
        var winners = BigInteger.ZERO
        var k = 0
        for (i in bonds.indices) {
            total = checked(total.add(big(bonds[i])))
            if (finished[i]) {
                winners = checked(winners.add(big(bonds[i])))
                k++
            }
        }
        val forfeits = total.subtract(winners)
        var paid = BigInteger.ZERO
        val out = bonds.indices.map { i ->
            val payout = if (finished[i] && winners.signum() > 0) {
                val num = forfeits.multiply(BigInteger.valueOf(finisherBps.toLong())).multiply(big(bonds[i]))
                // The program computes this product in u128.
                if (num.bitLength() > 128) throw ArithmeticException("u128 overflow")
                val den = BigInteger.valueOf(BPS_DENOMINATOR.toLong()).multiply(winners)
                checked(big(bonds[i]).add(checked(num.divide(den))))
            } else {
                BigInteger.ZERO
            }
            paid = checked(paid.add(payout))
            payout.toULongExact()
        }
        return StackSettlement(
            payouts = out,
            totalBonds = total.toULongExact(),
            finisherBonds = winners.toULongExact(),
            finishers = k,
            payoutsTotal = paid.toULongExact(),
            bury = total.subtract(paid).toULongExact(),
        )
    }

    /** `skr::refund_after`: `now + (end_round - board_round + 1) * 120 + 3 days`. */
    fun refundAfter(nowUnix: Long, boardRound: ULong, endRound: ULong): Long {
        val rounds = windowLen(boardRound, endRound)
        if (rounds > Long.MAX_VALUE.toULong()) throw ArithmeticException("i64 overflow")
        return Math.addExact(Math.addExact(Math.multiplyExact(rounds.toLong(), MAX_ROUND_SECS), REFUND_GRACE_SECS), nowUnix)
    }

    /** About how long [rounds] ORE rounds take, in whole minutes (at least one). */
    fun typicalMinutes(rounds: ULong): Long = ((rounds.toLong().coerceAtLeast(0) * TYPICAL_ROUND_SECS + 59) / 60).coerceAtLeast(1)

    /** How many rounds cover [minutes] at the typical round length (at least one). */
    fun roundsFor(minutes: Long): Long = ((minutes.coerceAtLeast(0) * 60 + TYPICAL_ROUND_SECS - 1) / TYPICAL_ROUND_SECS).coerceAtLeast(1)

    const val MAX_SEATS = 8

    private fun big(v: ULong) = BigInteger(v.toString())

    private fun checked(v: BigInteger): BigInteger {
        if (v.signum() < 0 || v > U64_MAX) throw ArithmeticException("u64 overflow")
        return v
    }

    private fun BigInteger.toULongExact(): ULong = checked(this).toString().toULong()
}

/** Where a table is in its life. */
enum class StackPhase {
    /** Open, before `start_round`: seats can still be taken. */
    GATHERING,

    /** Open, inside the window: every round needs a check-in. */
    RUNNING,

    /** Open, the window is over: waiting for `settle_stack`. */
    AWAITING_SETTLE,

    /** Open and never settled past the timeout: every seat can take its own bond back. */
    REFUND_DUE,

    /** `settle_stack` ran: every seat has its outcome and payout. */
    SETTLED,

    /** A refund was claimed: every seat takes its own bond back. */
    REFUNDING,
}

/** Where one seat stands, from its on-chain StackSeat. */
enum class SeatStanding {
    /** The window has not started. */
    WAITING,

    /** Inside the window and counted in this round or the one before: still dark. */
    DARK,

    /** Inside the window, within grace, but not counted in the last two rounds. */
    MISSING,

    /** Inside the window with no counted round yet. */
    NOT_SEEN,

    /** A check-in saw a BREAK or FREEZE in the seat's shift: it cannot finish. */
    BROKE,

    /** More rounds missed than the table's grace: it cannot finish. */
    OUT_OF_GRACE,

    /** The finish rule holds (window over, before settle) or the seat is settled as finished. */
    FINISHED,

    /** The finish rule fails (window over, before settle) or the seat is settled as forfeited. */
    FORFEITED,

    /** The table is refunding: the seat takes back exactly its bond. */
    REFUND,
}

/** A seat with what the phone derives from it. */
data class StackSeatView(
    val seat: StackSeatAccount,
    val standing: SeatStanding,
    /** Window rounds this seat has missed so far (rounds that are over and were not counted). */
    val missedRounds: ULong,
    /**
     * SKR the seat receives when it claims: the payout written at settle, its own bond while
     * refunding, the projection of [StackTableView.projection] before that. Base units.
     */
    val payout: ULong,
    /** A claim would go through now (settled, refunding, or past the refund timeout). */
    val claimable: Boolean,
)

/**
 * A table and its seats as read from the chain at round [boardRoundId] and time [nowUnix].
 * Seats close when they claim, so a settled table shows fewer of them over time.
 */
class StackTableView(
    val table: StackTableAccount,
    seats: List<StackSeatAccount>,
    val boardRoundId: ULong,
    val nowUnix: Long,
) {
    val phase: StackPhase = when (table.status) {
        StackStatus.SETTLED -> StackPhase.SETTLED
        StackStatus.REFUNDING -> StackPhase.REFUNDING
        StackStatus.OPEN -> when {
            boardRoundId < table.startRound -> StackPhase.GATHERING
            boardRoundId <= table.endRound -> StackPhase.RUNNING
            nowUnix > table.refundAfterTs -> StackPhase.REFUND_DUE
            else -> StackPhase.AWAITING_SETTLE
        }
    }

    private val ordered = seats.sortedBy { it.seatIndex }

    /**
     * What `settle_stack` would write if the table ended as it stands: seats that can still
     * finish are counted as finishers. Null once the table is settled or refunding (the chain
     * has the real numbers), or while seats are missing from the read.
     */
    val projection: StackSettlement? =
        if (table.status == StackStatus.OPEN && phase != StackPhase.REFUND_DUE && ordered.size == table.seatCount && ordered.isNotEmpty()) {
            StackMath.payouts(
                ordered.map { it.bond },
                ordered.map { stillIn(it) },
                if (table.buryOnly) 0 else StackMath.FINISHER_BPS,
            )
        } else {
            null
        }

    /** Seats in join order. */
    val seats: List<StackSeatView> = ordered.mapIndexed { i, seat ->
        StackSeatView(
            seat = seat,
            standing = standing(seat),
            missedRounds = missed(seat),
            payout = when (phase) {
                StackPhase.SETTLED -> seat.payout
                StackPhase.REFUNDING, StackPhase.REFUND_DUE -> seat.bond
                else -> projection?.payouts?.get(i) ?: 0uL
            },
            claimable = phase == StackPhase.SETTLED || phase == StackPhase.REFUNDING || phase == StackPhase.REFUND_DUE,
        )
    }

    /** Seats the chain says exist (joined, not yet claimed) that this read did not return. */
    val seatsMissingFromRead: Int get() = (table.seatCount - table.claimedCount - ordered.size).coerceAtLeast(0)

    /** Rounds left before the window opens (0 once it has). */
    val roundsUntilStart: ULong get() = if (table.startRound > boardRoundId) table.startRound - boardRoundId else 0uL

    /** Window rounds still to come, the live one included (0 once the window is over). */
    val roundsLeft: ULong
        get() = when {
            boardRoundId > table.endRound -> 0uL
            boardRoundId < table.startRound -> table.rounds
            else -> table.endRound - boardRoundId + 1uL
        }

    fun seatOf(authority: Pubkey): StackSeatView? = seats.firstOrNull { it.seat.authority == authority }

    /** Window rounds that are over (the live round is not). */
    private val roundsOver: ULong
        get() = when {
            boardRoundId <= table.startRound -> 0uL
            boardRoundId > table.endRound -> table.rounds
            else -> boardRoundId - table.startRound
        }

    private fun missed(seat: StackSeatAccount): ULong {
        // A check-in in the live round is counted already but that round is not over yet.
        val live = boardRoundId in table.startRound..table.endRound && seat.lastRound == boardRoundId
        val counted = if (live && seat.checkedRounds > 0uL) seat.checkedRounds - 1uL else seat.checkedRounds
        return if (roundsOver > counted) roundsOver - counted else 0uL
    }

    /** The seat can still satisfy the finish rule. */
    private fun stillIn(seat: StackSeatAccount): Boolean = when (phase) {
        StackPhase.GATHERING -> true
        StackPhase.RUNNING -> !seat.broken && missed(seat) <= table.graceGaps.toULong()
        else -> StackMath.seatFinishes(seat.broken, seat.lastRound, seat.checkedRounds, table.startRound, table.endRound, table.graceGaps)
    }

    private fun standing(seat: StackSeatAccount): SeatStanding = when (phase) {
        StackPhase.GATHERING -> SeatStanding.WAITING
        StackPhase.REFUNDING, StackPhase.REFUND_DUE -> SeatStanding.REFUND
        StackPhase.SETTLED -> when (seat.outcome) {
            SeatOutcome.FINISHED -> SeatStanding.FINISHED
            SeatOutcome.FORFEITED -> SeatStanding.FORFEITED
            // settle_stack writes every seat; a pending one cannot be read here.
            SeatOutcome.PENDING -> if (stillIn(seat)) SeatStanding.FINISHED else SeatStanding.FORFEITED
        }
        StackPhase.AWAITING_SETTLE -> if (stillIn(seat)) SeatStanding.FINISHED else SeatStanding.FORFEITED
        StackPhase.RUNNING -> when {
            seat.broken -> SeatStanding.BROKE
            missed(seat) > table.graceGaps.toULong() -> SeatStanding.OUT_OF_GRACE
            seat.checkedRounds == 0uL -> SeatStanding.NOT_SEEN
            seat.lastRound + 1uL >= boardRoundId -> SeatStanding.DARK
            else -> SeatStanding.MISSING
        }
    }
}
