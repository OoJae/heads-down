package xyz.headsdown.feature.shift

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import xyz.headsdown.core.chain.uplink.AckReason
import javax.inject.Inject
import javax.inject.Singleton

/** One crank ack matched to a frame this phone sent. Public data only (a counter and a code). */
data class CrankAck(
    val counter: ULong,
    /** `heartbeat`, `break` or `freeze`. */
    val frame: String,
    val ok: Boolean,
    val reason: AckReason,
    val atWallMillis: Long,
)

/** What the phone knows about its link to the crank intake. Never holds a signature or key. */
data class CrankLinkStatus(
    /** A crank URL is configured in this build (an empty one means local-only: no digs). */
    val configured: Boolean = false,
    /** Frames the crank accepted since the app started. */
    val accepted: Int = 0,
    val lastAck: CrankAck? = null,
    val lastRejection: CrankAck? = null,
) {
    /** The newest ack is a refusal: the problem is current, not history. */
    val refusing: Boolean get() = lastAck?.ok == false
}

/** In-process holder of [CrankLinkStatus], written by the heartbeat sink, read by the UI. */
@Singleton
class CrankLinkMonitor @Inject constructor() {
    private val _status = MutableStateFlow(CrankLinkStatus())
    val status: StateFlow<CrankLinkStatus> = _status.asStateFlow()

    fun setConfigured(configured: Boolean) = _status.update { it.copy(configured = configured) }

    internal fun record(ack: CrankAck) = _status.update { s ->
        s.copy(
            accepted = if (ack.ok) s.accepted + 1 else s.accepted,
            lastAck = ack,
            lastRejection = if (ack.ok) s.lastRejection else ack,
        )
    }
}

/** Plain-language line for a refused frame, or null when there is nothing to say. Honest copy. */
fun CrankLinkStatus.refusalLine(): String? {
    val ack = lastAck?.takeIf { !it.ok } ?: return null
    val what = when (ack.frame) {
        "break" -> "break signal"
        "freeze" -> "freeze signal"
        else -> "heartbeat"
    }
    return when (ack.reason) {
        AckReason.BAD_SIGNATURE -> "The crank refused this phone's $what: its signature does not match the rig key on-chain. Clock in again to re-register this phone's key."
        AckReason.STALE_COUNTER -> "The crank refused a $what as already used. The phone is re-reading its counter from the chain."
        AckReason.UNKNOWN_RIG -> "The crank does not know this rig yet. Clock in to register it on-chain."
        AckReason.RATE_LIMITED -> "The crank is rate-limiting this phone. The next round will try again."
        AckReason.LEASE_INVALID -> "The crank refused a $what for its lease. The next round will try again."
        AckReason.MALFORMED -> "The crank could not read a $what from this phone."
        AckReason.UNKNOWN -> "The crank refused a $what."
        AckReason.ACCEPTED -> null
    }
}
