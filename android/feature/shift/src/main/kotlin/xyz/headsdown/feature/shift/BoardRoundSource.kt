package xyz.headsdown.feature.shift

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * The real round feed: polls ORE `Board.round_id` (read through the owner/size/address-checked
 * decoder by [readRoundId]) and emits an [OreRound] each time it advances.
 *
 * - Only a strictly **greater** round id is emitted: a lagging RPC node answering with an
 *   older Board can never move the feed backwards or re-trigger a round.
 * - Read failures emit nothing and back off (up to [maxErrorDelayMillis]); with no round there
 *   is no heartbeat and so no dig: the rig goes cold, it never guesses a round.
 * - Rounds last about 78 s; polling every [pollMillis] (5 s) signs within a few seconds of the
 *   round opening, well before the crank's dig window closes.
 */
class BoardRoundSource(
    private val readRoundId: suspend () -> ULong,
    private val clock: MonotonicClock,
    private val pollMillis: Long = DEFAULT_POLL_MILLIS,
    private val maxErrorDelayMillis: Long = DEFAULT_MAX_ERROR_DELAY_MILLIS,
) : OreRoundSource {

    init {
        require(pollMillis > 0 && maxErrorDelayMillis >= pollMillis)
    }

    override fun rounds(): Flow<OreRound> = flow {
        var last: ULong? = null
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
                if (last == null || id > last) {
                    last = id
                    emit(OreRound(id, clock.nowMillis()))
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
    }
}
