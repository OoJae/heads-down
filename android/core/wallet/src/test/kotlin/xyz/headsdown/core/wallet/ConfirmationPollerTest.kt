package xyz.headsdown.core.wallet

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** Scripted RPC: each poll pops the next status / block height. */
private class FakeRpc(
    statuses: List<SignatureStatus?>,
    heights: List<Long>,
    private val failFirst: Int = 0,
) : SolanaRpc {
    private val statusQueue = ArrayDeque(statuses)
    private val heightQueue = ArrayDeque(heights)
    private var calls = 0
    var statusCalls = 0

    override suspend fun getSignatureStatus(signature: String): SignatureStatus? {
        if (calls++ < failFirst) throw IOException("rpc down")
        statusCalls++
        return if (statusQueue.size > 1) statusQueue.removeFirst() else statusQueue.firstOrNull()
    }

    override suspend fun getBlockHeight(commitment: Commitment): Long =
        if (heightQueue.size > 1) heightQueue.removeFirst() else heightQueue.first()
}

class ConfirmationPollerTest {
    private val sig = "5sig"

    private fun ok(level: Commitment) = SignatureStatus(slot = 99, confirmationStatus = level, err = null)

    @Test
    fun `confirmed with err null is the only success`() = runTest {
        val rpc = FakeRpc(listOf(null, ok(Commitment.PROCESSED), ok(Commitment.CONFIRMED)), listOf(100))
        val outcome = ConfirmationPoller(rpc).await(sig, lastValidBlockHeight = 150)
        assertEquals(ConfirmationOutcome.Confirmed(sig, 99, Commitment.CONFIRMED), outcome)
        assertTrue(outcome.isSuccess)
    }

    @Test
    fun `processed is not enough`() = runTest {
        val rpc = FakeRpc(listOf(ok(Commitment.PROCESSED)), listOf(100, 101, 151))
        val outcome = ConfirmationPoller(rpc).await(sig, lastValidBlockHeight = 150)
        assertTrue(outcome is ConfirmationOutcome.Expired)
        assertFalse(outcome.isSuccess)
    }

    @Test
    fun `finalized target waits past confirmed`() = runTest {
        val rpc = FakeRpc(listOf(ok(Commitment.CONFIRMED), ok(Commitment.FINALIZED)), listOf(100))
        val outcome = ConfirmationPoller(rpc, target = Commitment.FINALIZED).await(sig, 150)
        assertEquals(Commitment.FINALIZED, (outcome as ConfirmationOutcome.Confirmed).commitment)
    }

    @Test
    fun `on-chain error is failure even when confirmed`() = runTest {
        val failed = SignatureStatus(slot = 7, confirmationStatus = Commitment.CONFIRMED, err = """{"InstructionError":[1,{"Custom":6001}]}""")
        val outcome = ConfirmationPoller(FakeRpc(listOf(failed), listOf(100))).await(sig, 150)
        assertTrue(outcome is ConfirmationOutcome.FailedOnChain)
        assertFalse(outcome.isSuccess)
        assertEquals(failed.err, (outcome as ConfirmationOutcome.FailedOnChain).err)
    }

    @Test
    fun `expires only after block height passes lastValidBlockHeight`() = runTest {
        // Height == H is still valid; H + 1 is expired.
        val rpc = FakeRpc(listOf(null), listOf(140, 150, 151))
        val outcome = ConfirmationPoller(rpc).await(sig, lastValidBlockHeight = 150)
        assertEquals(ConfirmationOutcome.Expired(sig, 150, 151), outcome)
    }

    @Test
    fun `a transaction that lands at the expiry boundary is still confirmed`() = runTest {
        // First status check: not found. Height has passed H. The re-check finds it confirmed.
        val rpc = FakeRpc(listOf(null, ok(Commitment.CONFIRMED)), listOf(151))
        val outcome = ConfirmationPoller(rpc).await(sig, lastValidBlockHeight = 150)
        assertTrue(outcome.isSuccess)
        assertEquals(2, rpc.statusCalls)
    }

    @Test
    fun `transient rpc failures are retried`() = runTest {
        val rpc = FakeRpc(listOf(ok(Commitment.CONFIRMED)), listOf(100), failFirst = 3)
        assertTrue(ConfirmationPoller(rpc, maxConsecutiveRpcFailures = 5).await(sig, 150).isSuccess)
    }

    @Test
    fun `persistent rpc failure is unknown, not success`() = runTest {
        val outcome = ConfirmationPoller(UnconfiguredSolanaRpc).await(sig, 150)
        assertTrue(outcome is ConfirmationOutcome.RpcUnavailable)
        assertFalse(outcome.isSuccess)
    }

    @Test
    fun `stale node that never advances times out`() = runTest {
        val outcome = ConfirmationPoller(FakeRpc(listOf(null), listOf(100)), maxPolls = 10).await(sig, 150)
        assertEquals(ConfirmationOutcome.TimedOut(sig, 10), outcome)
    }

    @Test
    fun `processed can never be the target`() {
        assertThrows(IllegalArgumentException::class.java) {
            ConfirmationPoller(UnconfiguredSolanaRpc, target = Commitment.PROCESSED)
        }
    }

    @Test
    fun `submission report requires every signature confirmed`() {
        val good = ConfirmationOutcome.Confirmed("a", 1, Commitment.CONFIRMED)
        val bad = ConfirmationOutcome.FailedOnChain("b", 1, "{}")
        assertTrue(SubmissionReport(listOf(good, good)).allConfirmed)
        assertFalse(SubmissionReport(listOf(good, bad)).allConfirmed)
        assertFalse(SubmissionReport(emptyList()).allConfirmed)
    }
}
