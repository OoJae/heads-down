package xyz.headsdown.feature.shift

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * The real round feed: polls ORE `Board.round_id` (read through the owner/size/address-checked
 * decoder by [readRoundId]) and emits an [OreRound] each time it advances.
 *
 * The round id comes from an RPC, and the phone signs a heartbeat for whatever is emitted here,
 * so an answer is believed only when it is plausible:
 *
 * - A round id is emitted when it follows the last one **at the pace ORE allows**: at most one
 *   new round per [minRoundMillis] since the last accepted answer (rounds take about 78 s).
 * - An answer a little **behind** (a lagging node) is ignored: the feed never re-triggers a round.
 * - An answer **far ahead** of that pace, or far behind, proves nothing by itself. It is not
 *   emitted and not remembered as the latest round, so one bad answer can neither make the phone
 *   sign for a round far in the future nor stall the feed. Only when [CONFIRM_READS] answers in a
 *   row tell the same story does the feed move to it.
 * - Read failures emit nothing and back off (up to [maxErrorDelayMillis]); with no round there
 *   is no heartbeat and so no dig: the rig goes cold, it never guesses a round.
 * - Rounds last about 78 s; polling every [pollMillis] (5 s) signs within a few seconds of the
 *   round opening, well before the crank's dig window closes.
 *
 * A node that lies consistently cannot be told from the chain by one client; what it can do is
 * bounded on-chain (a heartbeat for a round that has not opened is refused).
 */
class BoardRoundSource(
    private val readRoundId: suspend () -> ULong,
    private val clock: MonotonicClock,
    private val pollMillis: Long = DEFAULT_POLL_MILLIS,
    private val maxErrorDelayMillis: Long = DEFAULT_MAX_ERROR_DELAY_MILLIS,
    private val minRoundMillis: Long = DEFAULT_MIN_ROUND_MILLIS,
) : OreRoundSource {

    init {
        require(pollMillis > 0 && maxErrorDelayMillis >= pollMillis && minRoundMillis > 0)
    }

    /** Whether [id] can follow [from] (seen at [fromAt]) by [now]: one new round per [minRoundMillis] at most. */
    private fun follows(from: ULong, fromAt: Long, id: ULong, now: Long): Boolean =
        id >= from && id - from <= 1uL + ((now - fromAt).coerceAtLeast(0) / minRoundMillis).toULong()

    override fun rounds(): Flow<OreRound> = flow {
        var last: ULong? = null
        var lastAt = 0L
        // An implausible answer, waiting to be confirmed by the answers that follow it.
        var candidate: ULong? = null
        var candidateAt = 0L
        var candidateReads = 0
        var failures = 0
        while (true) {
            val id = try {
                readRoundId()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (id != null) {
                failures = 0
                val now = clock.nowMillis()
                val prev = last
                var accepted: ULong? = null
                when {
                    prev == null -> accepted = id
                    id == prev -> candidate = null
                    id > prev && follows(prev, lastAt, id, now) -> accepted = id
                    id < prev && prev - id <= LAG_TOLERANCE_ROUNDS -> Unit // a lagging node: never go back for it
                    else -> {
                        val c = candidate
                        if (c != null && follows(c, candidateAt, id, now)) {
                            candidateReads++
                            if (id > c) {
                                candidate = id
                                candidateAt = now
                            }
                        } else {
                            candidate = id
                            candidateAt = now
                            candidateReads = 1
                        }
                        if (candidateReads >= CONFIRM_READS) accepted = candidate
                    }
                }
                if (accepted != null) {
                    last = accepted
                    lastAt = now
                    candidate = null
                    candidateReads = 0
                    emit(OreRound(accepted, now))
                }
                delay(pollMillis)
            } else {
                failures = (failures + 1).coerceAtMost(16)
                delay(minOf(maxErrorDelayMillis, pollMillis shl failures))
            }
        }
    }

    companion object {
        const val DEFAULT_POLL_MILLIS = 5_000L
        const val DEFAULT_MAX_ERROR_DELAY_MILLIS = 60_000L

        /** No ORE round is shorter than this (they take about 78 s): the pace a new id must respect. */
        const val DEFAULT_MIN_ROUND_MILLIS = 30_000L

        /** An answer this many rounds behind the latest is a lagging node, not news. */
        const val LAG_TOLERANCE_ROUNDS: ULong = 3uL

        /** Consecutive answers that must agree before the feed moves to an implausible round id. */
        const val CONFIRM_READS = 3
    }
}
