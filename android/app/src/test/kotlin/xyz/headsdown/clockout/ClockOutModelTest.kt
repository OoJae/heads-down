package xyz.headsdown.clockout

import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.headsdown.core.chain.Pubkey
import xyz.headsdown.core.chain.accounts.OreClaimEstimate
import xyz.headsdown.core.chain.clockout.BondOutcome
import xyz.headsdown.core.chain.clockout.BuyOutcome
import xyz.headsdown.core.chain.clockout.ClockOutPlan
import xyz.headsdown.core.chain.clockout.ClockOutRefusedException
import xyz.headsdown.core.chain.clockout.ClockOutRequest
import xyz.headsdown.core.chain.clockout.PreparedClockOut
import xyz.headsdown.core.chain.clockout.ShiftOutcome
import xyz.headsdown.core.chain.tx.Instruction
import xyz.headsdown.core.keys.ShiftEndReason
import xyz.headsdown.core.wallet.Commitment
import xyz.headsdown.core.wallet.ConfirmationOutcome
import xyz.headsdown.core.wallet.SubmissionReport
import xyz.headsdown.core.wallet.WalletAccount
import xyz.headsdown.core.wallet.WalletCapabilities
import xyz.headsdown.core.wallet.WalletResult
import xyz.headsdown.core.wallet.WalletSession
import java.io.IOException

/** The clock-out screen's logic: what it asks the wallet for, and what it reports afterwards. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ClockOutModelTest {

    private val authority = Pubkey(ByteArray(32) { 7 })
    private val account = WalletAccount(authority.bytes, "wallet")
    private val caps = WalletCapabilities(supportsLegacy = true, supportsV0 = true, maxTransactionsPerRequest = 0)
    private val oneIx = listOf(Instruction(Pubkey(ByteArray(32) { 1 }), emptyList(), byteArrayOf(1)))

    private val completed = ClockOutFacts(
        shift = ShiftOutcome.Ends(ShiftEndReason.COMPLETED),
        bond = BondOutcome.Released(100_000_000uL),
        returnedSolLamports = 0uL,
        nothingByDefault = false,
        refinedOre = 1_000uL,
        unrefinedOre = 20_000_000uL,
        fullClaim = OreClaimEstimate(1_000uL, 20_000_000uL, 2_000_000uL),
    )
    private val insideWindow = completed.copy(
        shift = ShiftOutcome.LeftOpen(ShiftEndReason.MANUAL, 1_790_028_800L),
        bond = BondOutcome.StaysLocked(100_000_000uL),
        nothingByDefault = true,
    )

    private class FakeChain(var facts: ClockOutFacts, val plan: (ClockOutRequest) -> ClockOutPlan?) : ClockOutChain {
        var previews = 0
        var previewFails = false
        val requests = mutableListOf<ClockOutRequest>()
        var refuse: ClockOutRefusedException.Reason? = null

        override suspend fun preview(authority: Pubkey): ClockOutFacts {
            previews++
            if (previewFails) throw IOException("secret-host.example refused")
            return facts
        }

        override suspend fun prepare(authority: Pubkey, request: ClockOutRequest, capabilities: WalletCapabilities): PreparedClockOut? {
            requests += request
            refuse?.let { throw ClockOutRefusedException(it) }
            val plan = plan(request) ?: return null
            return PreparedClockOut(listOf(byteArrayOf(1)), 100, authority, plan, BuyOutcome.NotRequested)
        }
    }

    private fun planFor(facts: ClockOutFacts): (ClockOutRequest) -> ClockOutPlan? = { request ->
        val shown = if (request.endShiftEarly) facts.endedEarly() else facts
        ClockOutPlan(
            oneIx, shown.shift, shown.bond, shown.returnedSolLamports,
            claimedOre = facts.fullClaim.takeIf { request.claimOreBps > 0 },
        )
    }

    /** A wallet that authorizes [signer], runs the preparer and reports [confirmed]. */
    private fun wallet(
        signer: WalletAccount = account,
        outcome: ConfirmationOutcome = ConfirmationOutcome.Confirmed("sig", 1, Commitment.CONFIRMED),
    ): ClockOutSigner = { prepare ->
        try {
            when (val prepared = prepare(signer, caps)) {
                null -> WalletResult.Success(WalletSession.NothingToSign(signer))
                else -> WalletResult.Success(WalletSession.Submitted(signer, prepared, SubmissionReport(listOf(outcome))))
            }
        } catch (_: Exception) {
            WalletResult.Failed("Could not build the transaction. Nothing was sent.")
        }
    }

    @Test
    fun `no bound rig means no chain read and nothing to sign`() = runTest {
        val chain = FakeChain(completed, planFor(completed))
        val model = ClockOutModel(chain, { null }, this)
        model.load()
        advanceUntilIdle()
        assertEquals(ClockOutState.NoRig, model.state.value)
        assertEquals(0, chain.previews)
        model.confirm(wallet())
        advanceUntilIdle()
        assertTrue(chain.requests.isEmpty())
    }

    @Test
    fun `an unreadable chain shows fixed words and can be retried`() = runTest {
        val chain = FakeChain(completed, planFor(completed)).apply { previewFails = true }
        val model = ClockOutModel(chain, { authority }, this)
        model.load()
        advanceUntilIdle()
        val failed = model.state.value as ClockOutState.Unavailable
        assertEquals(ClockOutModel.UNREADABLE, failed.message)
        assertFalse(failed.message.contains("secret-host"))
        chain.previewFails = false
        model.load()
        advanceUntilIdle()
        assertEquals(completed, (model.state.value as ClockOutState.Ready).facts)
    }

    @Test
    fun `the default clock-out keeps the ORE and ends nothing early`() = runTest {
        val chain = FakeChain(completed, planFor(completed))
        var sealed = 0
        val model = ClockOutModel(chain, { authority }, this) { sealed++ }
        model.load()
        advanceUntilIdle()
        model.confirm(wallet())
        advanceUntilIdle()
        assertEquals(ClockOutRequest(), chain.requests.single())
        val done = model.state.value as ClockOutState.Done
        assertEquals("Confirmed on-chain. Shift sealed as completed. Focus Bond back in your wallet: 100 SKR.", done.message)
        assertEquals(1, sealed)
    }

    @Test
    fun `claim all asks for every basis point and reports the claim`() = runTest {
        val chain = FakeChain(completed, planFor(completed))
        val model = ClockOutModel(chain, { authority }, this)
        model.load()
        advanceUntilIdle()
        model.setClaimAll(true)
        assertTrue((model.state.value as ClockOutState.Ready).claimAll)
        model.confirm(wallet())
        advanceUntilIdle()
        assertEquals(10_000, chain.requests.single().claimOreBps)
        assertFalse(chain.requests.single().endShiftEarly)
        assertTrue((model.state.value as ClockOutState.Done).message.endsWith("About 0.00018001 ORE claimed to your wallet."))
    }

    @Test
    fun `claim all cannot be chosen when the Miner is empty`() = runTest {
        val empty = completed.copy(refinedOre = 0uL, unrefinedOre = 0uL, fullClaim = OreClaimEstimate(0uL, 0uL, 0uL))
        val model = ClockOutModel(FakeChain(empty, planFor(empty)), { authority }, this)
        model.load()
        advanceUntilIdle()
        model.setClaimAll(true)
        assertFalse((model.state.value as ClockOutState.Ready).claimAll)
    }

    @Test
    fun `a shift inside its window is not ended unless the user chooses to, and the forfeit is accepted only then`() = runTest {
        val chain = FakeChain(insideWindow, planFor(insideWindow))
        var sealed = 0
        val model = ClockOutModel(chain, { authority }, this) { sealed++ }
        model.load()
        advanceUntilIdle()
        val ready = model.state.value as ClockOutState.Ready
        assertFalse(ready.canSign) // nothing by default: the shift stays open, the ORE stays put
        model.confirm(wallet())
        advanceUntilIdle()
        assertTrue(chain.requests.isEmpty())

        model.setEndEarly(true)
        val chosen = model.state.value as ClockOutState.Ready
        assertTrue(chosen.canSign)
        assertEquals(BondOutcome.Forfeit(100_000_000uL, ShiftEndReason.MANUAL), chosen.shown.bond)
        model.confirm(wallet())
        advanceUntilIdle()
        val request = chain.requests.single()
        assertTrue(request.endShiftEarly)
        assertTrue(request.acceptBondForfeit)
        assertEquals(0, request.claimOreBps)
        assertEquals("Confirmed on-chain. Shift sealed as ended early. Focus Bond forfeit: 100 SKR.", (model.state.value as ClockOutState.Done).message)
        assertEquals(1, sealed)
    }

    @Test
    fun `ending early without a bond on screen never accepts a forfeit`() = runTest {
        val noBond = insideWindow.copy(bond = BondOutcome.None)
        val chain = FakeChain(noBond, planFor(noBond)).apply { refuse = ClockOutRefusedException.Reason.BOND_WOULD_FORFEIT }
        var sealed = 0
        val model = ClockOutModel(chain, { authority }, this) { sealed++ }
        model.load()
        advanceUntilIdle()
        model.setEndEarly(true)
        model.confirm(wallet())
        advanceUntilIdle()
        assertTrue(chain.requests.single().endShiftEarly)
        assertFalse(chain.requests.single().acceptBondForfeit)
        // A bond appeared since the preview: the composer refuses, and the screen says why.
        val ready = model.state.value as ClockOutState.Ready
        assertEquals(ClockOutRefusedException.Reason.BOND_WOULD_FORFEIT.message + " Nothing was sent.", ready.problem)
        assertFalse(ready.working)
        assertEquals(0, sealed)
    }

    @Test
    fun `end early cannot be chosen for a shift that is already settled`() = runTest {
        val model = ClockOutModel(FakeChain(completed, planFor(completed)), { authority }, this)
        model.load()
        advanceUntilIdle()
        model.setEndEarly(true)
        assertFalse((model.state.value as ClockOutState.Ready).endEarly)
    }

    @Test
    fun `another wallet is refused before anything is built`() = runTest {
        val chain = FakeChain(completed, planFor(completed))
        val model = ClockOutModel(chain, { authority }, this)
        model.load()
        advanceUntilIdle()
        model.confirm(wallet(signer = WalletAccount(ByteArray(32) { 9 }, "other")))
        advanceUntilIdle()
        assertTrue(chain.requests.isEmpty())
        assertEquals(ClockOutModel.OTHER_WALLET, (model.state.value as ClockOutState.Ready).problem)
    }

    @Test
    fun `a transaction that did not confirm is never shown as done`() = runTest {
        val chain = FakeChain(completed, planFor(completed))
        var sealed = 0
        val model = ClockOutModel(chain, { authority }, this) { sealed++ }
        model.load()
        advanceUntilIdle()
        model.confirm(wallet(outcome = ConfirmationOutcome.FailedOnChain("sig", 1, "custom 6")))
        advanceUntilIdle()
        val ready = model.state.value as ClockOutState.Ready
        assertEquals(ClockOutModel.NOT_LANDED, ready.problem)
        assertFalse(ready.working)
        // Not knowing is not the same as knowing it failed: the screen says to check the wallet.
        model.confirm(wallet(outcome = ConfirmationOutcome.TimedOut("sig", 30)))
        advanceUntilIdle()
        assertEquals(ClockOutModel.NOT_CONFIRMED, (model.state.value as ClockOutState.Ready).problem)
        model.confirm(wallet(outcome = ConfirmationOutcome.RpcUnavailable("sig", "down")))
        advanceUntilIdle()
        assertEquals(ClockOutModel.NOT_CONFIRMED, (model.state.value as ClockOutState.Ready).problem)
        assertEquals(0, sealed)
    }

    @Test
    fun `wallet failures are shown in the wallet's fixed words`() = runTest {
        val model = ClockOutModel(FakeChain(completed, planFor(completed)), { authority }, this)
        model.load()
        advanceUntilIdle()
        model.confirm { WalletResult.NoWalletInstalled }
        advanceUntilIdle()
        assertEquals(ClockOutModel.NO_WALLET, (model.state.value as ClockOutState.Ready).problem)
        model.confirm { WalletResult.Failed("User did not authorize signing") }
        advanceUntilIdle()
        assertEquals("Wallet: User did not authorize signing", (model.state.value as ClockOutState.Ready).problem)
        // A signer that throws is a failure too, never a crash and never its own text.
        model.confirm { throw IllegalStateException("token=abc") }
        advanceUntilIdle()
        assertEquals("Wallet: Wallet request failed", (model.state.value as ClockOutState.Ready).problem)
        // Choosing again clears the old problem.
        model.setClaimAll(true)
        assertNull((model.state.value as ClockOutState.Ready).problem)
    }

    @Test
    fun `nothing left to sign at signing time is said, and a shift that stays open does not stop the phone`() = runTest {
        val chain = FakeChain(completed) { null }
        var sealed = 0
        val model = ClockOutModel(chain, { authority }, this) { sealed++ }
        model.load()
        advanceUntilIdle()
        model.confirm(wallet())
        advanceUntilIdle()
        assertEquals(ClockOutModel.NOTHING_LEFT, (model.state.value as ClockOutState.Ready).problem)

        // Claiming while the shift stays open: confirmed, but the shift service is left alone.
        val claimOnly = FakeChain(insideWindow, planFor(insideWindow))
        val second = ClockOutModel(claimOnly, { authority }, this) { sealed++ }
        second.load()
        advanceUntilIdle()
        second.setClaimAll(true)
        second.confirm(wallet())
        advanceUntilIdle()
        assertEquals("Confirmed on-chain. About 0.00018001 ORE claimed to your wallet.", (second.state.value as ClockOutState.Done).message)
        assertEquals(0, sealed)
    }

    @Test
    fun `choices are locked while the wallet is open and a second tap does nothing`() = runTest {
        val chain = FakeChain(completed, planFor(completed))
        val model = ClockOutModel(chain, { authority }, this)
        model.load()
        advanceUntilIdle()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        model.confirm { prepare ->
            gate.await()
            wallet()(prepare)
        }
        testScheduler.runCurrent()
        assertTrue((model.state.value as ClockOutState.Ready).working)
        model.setClaimAll(true)
        assertFalse((model.state.value as ClockOutState.Ready).claimAll)
        model.confirm(wallet())
        testScheduler.runCurrent()
        assertTrue(chain.requests.isEmpty())
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, chain.requests.size)
        assertTrue(model.state.value is ClockOutState.Done)
    }
}
