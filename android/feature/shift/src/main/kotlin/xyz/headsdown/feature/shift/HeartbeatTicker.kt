package xyz.headsdown.feature.shift

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import xyz.headsdown.core.keys.RigMessageFormat
import xyz.headsdown.core.keys.RigMessageKind
import xyz.headsdown.core.keys.RigMessageSigner
import xyz.headsdown.core.keys.ShiftEndReason
import xyz.headsdown.core.keys.SignedHeartbeat
import xyz.headsdown.core.keys.SignedRigMessage
import xyz.headsdown.core.keys.SignedShiftSignal

/** An ORE round (~78 s: 60 s of deploys + the reset window). */
data class OreRound(val id: ULong, val observedAtMillis: Long)

/**
 * Source of ORE round boundaries. The production implementation follows the owner-checked
 * ORE `Board.round_id` over RPC ([BoardRoundSource]); the id **must** be the real one, or the
 * on-chain `dig` rejects the heartbeat (its lease must cover `Board.round_id`).
 */
fun interface OreRoundSource {
    fun rounds(): Flow<OreRound>
}

/**
 * TEST / DEVELOPMENT STUB. Emits synthetic round ids on a fixed ~78 s cadence so the device
 * loop can be exercised without a network. These ids are not ORE's: heartbeats signed for them
 * will never cover a real Board round and so can never cause a dig.
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

/** Program + Rig account the signed messages are bound to. */
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

/**
 * Where signed rig messages go: the crank uplink (WebSocket) in production, with a local
 * record as the fallback. Must return promptly (it runs on the shift loop) and must throw when
 * the message was not handed to an uplink, so the tick is reported as not delivered.
 */
fun interface HeartbeatSink {
    suspend fun deliver(message: SignedRigMessage<*>)

    /** A shift started: connect whatever the sink needs. */
    fun open() {}

    /** The shift ended: release connections so nothing runs between shifts. */
    fun close() {}
}

sealed interface TickResult {
    data class Signed(val heartbeat: SignedHeartbeat) : TickResult
    data object NotEligible : TickResult
    data class DeliveryFailed(val heartbeat: SignedHeartbeat, val cause: String) : TickResult
    data class SigningFailed(val cause: String) : TickResult
}

/**
 * The `ShiftLog.break_reason` a device-side break is reported with (INTERFACE v1.1 §3.5). On-chain,
 * pickup, screen-on and unplugged cool the rig (a fresh heartbeat revives it); unlocked breaks it.
 */
val BreakReason.wireReason: ShiftEndReason
    get() = when (this) {
        BreakReason.LIFTED -> ShiftEndReason.PICKUP
        BreakReason.SCREEN_ON -> ShiftEndReason.SCREEN_ON
        BreakReason.UNPLUGGED -> ShiftEndReason.UNPLUGGED
        BreakReason.UNLOCKED -> ShiftEndReason.UNLOCKED
    }

/**
 * Once per ORE round: if the rig is hot, sign a HEARTBEAT (the 32-byte digest of the 94-byte
 * preimage) with the Keystore key and hand it to the sink. Anything short of that (not hot,
 * stale posture, key error, uplink down) produces no delivered heartbeat, and no heartbeat
 * means no dig: the failure mode is always "rig goes cold", never "funds move without the
 * phone".
 */
class HeartbeatTicker(
    private val rounds: OreRoundSource,
    /** The armed spec if a heartbeat is allowed right now (DOWN + fresh posture), else null. */
    private val eligibleSpec: () -> ShiftSpec?,
    private val binding: () -> RigBinding,
    private val signer: RigMessageSigner,
    private val sink: HeartbeatSink,
    private val leaseRounds: Int = 1,
    private val onResult: (OreRound, TickResult) -> Unit = { _, _ -> },
) {
    init {
        require(leaseRounds in 1..RigMessageFormat.MAX_LEASE_ROUNDS)
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
            val bound = binding()
            signer.heartbeat(bound.programId, bound.rigAddress, spec.shiftId.toULong(), round.id, leaseRounds)
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

    /** Signs a BREAK for [shiftId] with [reason]. BREAK/FREEZE carry no round id. */
    fun signBreak(shiftId: Long, reason: ShiftEndReason): SignedShiftSignal =
        signSignal(RigMessageKind.BREAK, shiftId, reason)

    /** Signs a FREEZE. With no shift running, `shift_id` is the rig's current (0 before any). */
    fun signFreeze(shiftId: Long?): SignedShiftSignal =
        signSignal(RigMessageKind.FREEZE, shiftId ?: 0L, ShiftEndReason.FREEZE)

    private fun signSignal(kind: RigMessageKind, shiftId: Long, reason: ShiftEndReason): SignedShiftSignal {
        require(shiftId >= 0) { "negative shift id" }
        val bound = binding()
        return signer.shiftSignal(bound.programId, bound.rigAddress, kind, shiftId.toULong(), reason)
    }
}
