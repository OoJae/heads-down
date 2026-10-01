package xyz.headsdown.feature.shift

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import xyz.headsdown.core.chain.uplink.AckReason
import xyz.headsdown.core.chain.uplink.CrankReply
import xyz.headsdown.core.chain.uplink.HeartbeatJson
import xyz.headsdown.core.chain.uplink.MessageUplink
import xyz.headsdown.core.keys.HeartbeatPreimage
import xyz.headsdown.core.keys.SignedRigMessage

/** Local record of every signed message (the fallback when there is no uplink). */
fun interface HeartbeatLog {
    fun append(json: String)
}

/** The uplink could not take the message now. The tick is reported as not delivered. */
class UplinkUnavailableException : Exception("crank uplink unavailable")

/** Builds the uplink with the sink's callbacks (called on the uplink's thread). */
fun interface UplinkFactory {
    fun create(onConnected: () -> Unit, onText: (String) -> Unit): MessageUplink
}

/**
 * Production [HeartbeatSink] for the crank intake (contract A): every signed message is
 * appended to the local [log] and handed to the crank [UplinkFactory] uplink as a
 * `heartbeat` / `break` / `freeze` frame. The crank lands phone-signed BREAK and FREEZE
 * on-chain itself. It never blocks the shift loop: the uplink's `send` only enqueues.
 *
 * Acks (`{"type":"ack","counter","ok","reason"}`) are matched to the frames this phone sent and
 * published through [monitor] for the UI; `stale_counter` triggers [onStaleCounter] (re-read
 * `Rig.hb_counter`), and every refusal goes to [debugLog] as a counter and a code only.
 *
 * Fail-safe behaviour:
 * - no uplink configured, or not connected: the message stays local, a small bounded queue
 *   keeps it while it can still matter, and [deliver] throws [UplinkUnavailableException] so
 *   the tick counts as undelivered. No uplink means no digs; nothing crashes.
 * - on reconnect, queued messages that are still fresh are flushed in order; stale heartbeats
 *   (their lease has run out) are dropped rather than sent late.
 * - a hostile or broken crank can only make acks disappear or refuse frames: an ack for a
 *   counter this phone did not just send is ignored.
 */
class CrankHeartbeatSink(
    uplinkFactory: UplinkFactory?,
    private val log: HeartbeatLog,
    private val clock: MonotonicClock,
    /** Outlives the shift service, so a graceful close can finish after it is destroyed. */
    private val scope: CoroutineScope,
    private val closeGraceMillis: Long = CLOSE_GRACE_MILLIS,
    private val maxPending: Int = MAX_PENDING,
    private val monitor: CrankLinkMonitor = CrankLinkMonitor(),
    private val wallClock: () -> Long = System::currentTimeMillis,
    /** The crank says this counter is used: raise the local counter from chain. */
    private val onStaleCounter: () -> Unit = {},
    /** Debug-build logging of refusals (counters and codes only, never frames or signatures). */
    private val debugLog: (String) -> Unit = {},
) : HeartbeatSink {

    private class Pending(val json: String, val expiresAt: Long)

    private val pending = ArrayDeque<Pending>()

    /** Counter → frame type of the most recently sent frames, to match acks. */
    private val sent = object : LinkedHashMap<ULong, String>(MAX_TRACKED_ACKS, 0.75f) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ULong, String>?): Boolean = size > MAX_TRACKED_ACKS
    }

    private val uplink: MessageUplink? = uplinkFactory?.create(::flushPending, ::onCrankText)
    private var closing: Job? = null

    init {
        monitor.setConfigured(uplink != null)
    }

    @Synchronized
    override fun open() {
        closing?.cancel() // a new shift within the grace period keeps the connection
        closing = null
        uplink?.start()
    }

    /**
     * Stops the uplink after [closeGraceMillis], so a BREAK/FREEZE relayed while the shift
     * ended is still written; nothing keeps running between shifts.
     */
    @Synchronized
    override fun close() {
        val link = uplink ?: return
        closing?.cancel()
        closing = scope.launch {
            delay(closeGraceMillis)
            link.stop()
            synchronized(pending) { pending.clear() }
        }
    }

    override suspend fun deliver(message: SignedRigMessage<*>) {
        val json = HeartbeatJson.encode(message)
        runCatching { log.append(json) } // the local record must never break delivery
        synchronized(sent) { sent[message.payload.counter] = HeartbeatJson.typeOf(message) }
        if (uplink?.send(json) == true) return
        if (uplink != null) {
            synchronized(pending) {
                while (pending.size >= maxPending) pending.removeFirst()
                pending.addLast(Pending(json, clock.nowMillis() + freshFor(message)))
            }
        }
        throw UplinkUnavailableException()
    }

    /** Sends still-fresh queued messages, oldest first. Runs on the uplink's thread. */
    fun flushPending() {
        val link = uplink ?: return
        val now = clock.nowMillis()
        synchronized(pending) {
            while (pending.isNotEmpty()) {
                val next = pending.first()
                if (next.expiresAt <= now) {
                    pending.removeFirst()
                    continue
                }
                if (!link.send(next.json)) return
                pending.removeFirst()
            }
        }
    }

    /** A crank text frame. Runs on the uplink's thread; never throws. */
    fun onCrankText(text: String) {
        val ack = CrankReply.parse(text) as? CrankReply.Ack ?: return
        val frame = synchronized(sent) { sent[ack.counter] }
        if (frame == null) {
            debugLog("crank ack for counter ${ack.counter} matches no frame this phone sent; ignored")
            return
        }
        monitor.record(CrankAck(ack.counter, frame, ack.ok, ack.reason, wallClock()))
        if (!ack.ok) {
            debugLog("crank refused $frame #${ack.counter}: ${ack.reason.wire}")
            if (ack.reason == AckReason.STALE_COUNTER) runCatching(onStaleCounter)
        }
    }

    val pendingCount: Int get() = synchronized(pending) { pending.size }

    private fun freshFor(message: SignedRigMessage<*>): Long = when (val p = message.payload) {
        // A heartbeat only covers rounds [round, round + lease - 1]: useless after that.
        is HeartbeatPreimage -> p.leaseRounds * StubOreRoundSource.ORE_ROUND_MILLIS
        // BREAK / FREEZE stop digging whenever they land.
        else -> SIGNAL_FRESH_MILLIS
    }

    companion object {
        const val MAX_PENDING = 8
        const val MAX_TRACKED_ACKS = 32
        const val SIGNAL_FRESH_MILLIS = 10 * 60 * 1000L
        const val CLOSE_GRACE_MILLIS = 5_000L
    }
}
