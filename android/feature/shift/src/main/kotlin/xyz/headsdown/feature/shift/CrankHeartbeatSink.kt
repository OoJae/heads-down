package xyz.headsdown.feature.shift

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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

/**
 * Production [HeartbeatSink]: every signed message is appended to the local [log] and handed
 * to the crank [uplink]. It never blocks the shift loop: the uplink's `send` only enqueues.
 *
 * Fail-safe behaviour:
 * - no uplink configured, or not connected: the message stays local, a small bounded queue
 *   keeps it while it can still matter, and [deliver] throws [UplinkUnavailableException] so
 *   the tick counts as undelivered. No uplink means no digs; nothing crashes.
 * - on reconnect, queued messages that are still fresh are flushed in order; stale heartbeats
 *   (their lease has run out) are dropped rather than sent late.
 */
class CrankHeartbeatSink(
    uplinkFactory: ((onConnected: () -> Unit) -> MessageUplink)?,
    private val log: HeartbeatLog,
    private val clock: MonotonicClock,
    /** Outlives the shift service, so a graceful close can finish after it is destroyed. */
    private val scope: CoroutineScope,
    private val closeGraceMillis: Long = CLOSE_GRACE_MILLIS,
    private val maxPending: Int = MAX_PENDING,
) : HeartbeatSink {

    private class Pending(val json: String, val expiresAt: Long)

    private val pending = ArrayDeque<Pending>()
    private val uplink: MessageUplink? = uplinkFactory?.invoke(::flushPending)
    private var closing: Job? = null

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

    val pendingCount: Int get() = synchronized(pending) { pending.size }

    private fun freshFor(message: SignedRigMessage<*>): Long = when (val p = message.payload) {
        // A heartbeat only covers rounds [round, round + lease - 1]: useless after that.
        is HeartbeatPreimage -> p.leaseRounds * StubOreRoundSource.ORE_ROUND_MILLIS
        // BREAK / FREEZE stop digging whenever they land.
        else -> SIGNAL_FRESH_MILLIS
    }

    companion object {
        const val MAX_PENDING = 8
        const val SIGNAL_FRESH_MILLIS = 10 * 60 * 1000L
        const val CLOSE_GRACE_MILLIS = 5_000L
    }
}
