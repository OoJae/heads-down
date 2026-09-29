package xyz.headsdown.feature.shift

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import xyz.headsdown.core.keys.HeartbeatMessage
import xyz.headsdown.core.keys.HeartbeatSigner
import xyz.headsdown.core.keys.RigSignalState
import xyz.headsdown.core.keys.SignedHeartbeat

/** An ORE round (~78 s: 60 s of deploys + the reset window). */
data class OreRound(val id: ULong, val observedAtMillis: Long)

/**
 * Source of ORE round boundaries. The production implementation follows the owner-checked
 * ORE `Board.round_id` (via the heartbeat intake / RPC); the id **must** be the real one, or
 * the on-chain `dig` rejects the heartbeat (`ore_round_id == Board.round_id`).
 */
fun interface OreRoundSource {
    fun rounds(): Flow<OreRound>
}

/**
 * DEVELOPMENT STUB. Emits synthetic round ids on a fixed ~78 s cadence so the device loop can
 * be exercised end to end without a network. These ids are not ORE's: heartbeats signed for
 * them will never verify against a real Board and so can never cause a dig.
 */
class StubOreRoundSource(
    private val clock: MonotonicClock,
    private val roundMillis: Long = ORE_ROUND_MILLIS,
    private val firstRoundId: ULong = 1uL,
) : OreRoundSource {
    override fun rounds(): Flow<OreRound> = flow {
        var id = firstRoundId
        while (true) {
            emit(OreRound(id, clock.nowMillis()))
            delay(roundMillis)
            id++
        }
    }

    companion object {
        const val ORE_ROUND_MILLIS = 78_000L
    }
}

/** Program + Rig account the heartbeat is bound to. */
class RigBinding(programId: ByteArray, rigAddress: ByteArray) {
    val programId: ByteArray = programId.copyOf()
    val rigAddress: ByteArray = rigAddress.copyOf()

    init {
        require(this.programId.size == 32 && this.rigAddress.size == 32)
    }

    /** False until `register_rig` has confirmed on-chain. */
    val isRegistered: Boolean get() = rigAddress.any { it.toInt() != 0 }

    companion object {
        /** All-zero binding: a signature over it can never match a real Rig on-chain. */
        val UNREGISTERED = RigBinding(ByteArray(32), ByteArray(32))
    }
}

/** Strictly increasing per-rig counter. Must persist (write-ahead) so it never repeats. */
fun interface HeartbeatCounter {
    fun next(): ULong
}

/** Where signed heartbeats go: the intake WebSocket (and its Nostr mirror) in production. */
fun interface HeartbeatSink {
    suspend fun deliver(heartbeat: SignedHeartbeat)
}

sealed interface TickResult {
    data class Signed(val heartbeat: SignedHeartbeat) : TickResult
    data object NotEligible : TickResult
    data class DeliveryFailed(val heartbeat: SignedHeartbeat, val cause: String) : TickResult
    data class SigningFailed(val cause: String) : TickResult
}

/**
 * Once per ORE round: if the rig is hot, sign a DOWN heartbeat with the Keystore key and hand
 * it to the sink. Anything short of that (not hot, stale posture, key error, network error)
 * simply produces no heartbeat, and no heartbeat means no dig: the failure mode is always
 * "rig goes cold", never "funds move without the phone".
 */
class HeartbeatTicker(
    private val rounds: OreRoundSource,
    /** The armed spec if a heartbeat is allowed right now (DOWN + fresh posture), else null. */
    private val eligibleSpec: () -> ShiftSpec?,
    private val binding: () -> RigBinding,
    private val signer: HeartbeatSigner,
    private val counter: HeartbeatCounter,
    private val sink: HeartbeatSink,
    private val leaseRounds: Int = 1,
    private val onResult: (OreRound, TickResult) -> Unit = { _, _ -> },
) {
    init {
        require(leaseRounds in 1..HeartbeatMessage.MAX_LEASE_ROUNDS)
    }

    @Volatile
    var lastRound: OreRound? = null
        private set

    suspend fun run() {
        rounds.rounds().collect { round ->
            val result = tick(round)
            onResult(round, result)
        }
    }

    suspend fun tick(round: OreRound): TickResult {
        lastRound = round
        val spec = eligibleSpec() ?: return TickResult.NotEligible
        val signed = try {
            sign(RigSignalState.DOWN, spec.shiftId, round.id, leaseEnd = round.id + (leaseRounds - 1).toULong())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return TickResult.SigningFailed(e.javaClass.simpleName)
        }
        return try {
            sink.deliver(signed)
            TickResult.Signed(signed)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TickResult.DeliveryFailed(signed, e.javaClass.simpleName)
        }
    }

    /** Signs a BREAK / FREEZE / COOLING signal for the latest known round. */
    fun signSignal(state: RigSignalState, shiftId: Long?): SignedHeartbeat? {
        require(state != RigSignalState.DOWN) { "DOWN heartbeats come from tick()" }
        val round = lastRound ?: return null
        return sign(state, shiftId ?: 0L, round.id, leaseEnd = round.id)
    }

    private fun sign(state: RigSignalState, shiftId: Long, roundId: ULong, leaseEnd: ULong): SignedHeartbeat {
        require(shiftId >= 0) { "negative shift id" }
        val bound = binding()
        val message = HeartbeatMessage(
            programId = bound.programId,
            rig = bound.rigAddress,
            oreRoundId = roundId,
            counter = counter.next(),
            state = state,
            shiftId = shiftId.toULong(),
            leaseEnd = leaseEnd,
        )
        return signer.sign(message)
    }
}
