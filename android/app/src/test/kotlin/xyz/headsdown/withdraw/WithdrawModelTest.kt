package xyz.headsdown.withdraw

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.headsdown.core.chain.Pubkey
import xyz.headsdown.core.chain.tx.Instruction
import xyz.headsdown.core.chain.withdraw.CloseBlock
import xyz.headsdown.core.chain.withdraw.PreparedWithdraw
import xyz.headsdown.core.chain.withdraw.Revoke
import xyz.headsdown.core.chain.withdraw.RigClose
import xyz.headsdown.core.chain.withdraw.WithdrawPlan
import xyz.headsdown.core.chain.withdraw.WithdrawRefusedException
import xyz.headsdown.core.chain.withdraw.WithdrawRequest
import xyz.headsdown.core.wallet.Commitment
import xyz.headsdown.core.wallet.ConfirmationOutcome
import xyz.headsdown.core.wallet.SubmissionReport
import xyz.headsdown.core.wallet.WalletAccount
import xyz.headsdown.core.wallet.WalletCapabilities
import xyz.headsdown.core.wallet.WalletResult
import xyz.headsdown.core.wallet.WalletSession
import java.io.IOException

/** The withdraw screen's logic: what it asks the wallet for, and what it reports afterwards. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class WithdrawModelTest {

    private val authority = Pubkey(ByteArray(32) { 7 })
    private val account = WalletAccount(authority.bytes, "wallet")
    private val caps = WalletCapabilities(supportsLegacy = true, supportsV0 = true, maxTransactionsPerRequest = 0)
    private val oneIx = listOf(Instruction(Pubkey(ByteArray(32) { 1 }), emptyList(), byteArrayOf(1)))

    private val revoke = Revoke(lamports = 20_004_480uL, balance = 18_000_000uL, headsDown = true)
    private val close = RigClose(rentBackLamports = 2_449_920uL, leavesTombstone = true, closesSeekerSeat = false)
    private val both = WithdrawFacts(revoke, hasRig = true, shiftOpen = false, rigClose = close, closeBlock = null)
    private val shiftOpen = both.copy(shiftOpen = true, rigClose = null, closeBlock = CloseBlock.SHIFT_OPEN.message)

    private inner class FakeChain(var facts: WithdrawFacts) : WithdrawChain {
        val previews = mutableListOf<Pubkey>()
        var previewFails = false
        val requests = mutableListOf<WithdrawRequest>()
        var refuse: String? = null
        var nothing = false

        override suspend fun preview(authority: Pubkey): WithdrawFacts {
            previews += authority
            if (previewFails) throw IOException("secret-host.example refused")
            return facts
        }

        override suspend fun prepare(authority: Pubkey, request: WithdrawRequest, capabilities: WalletCapabilities): PreparedWithdraw? {
            requests += request
            refuse?.let { throw WithdrawRefusedException(it) }
            if (nothing) return null
            val plan = WithdrawPlan(oneIx, facts.revoke.takeIf { request.revoke }, facts.rigClose.takeIf { request.closeRig })
            return PreparedWithdraw(byteArrayOf(1), 100, authority, plan)
        }
    }

    /** A wallet that authorizes [signer], runs the preparer and reports [outcome]. */
    private fun wallet(
        signer: WalletAccount = account,
        outcome: ConfirmationOutcome = ConfirmationOutcome.Confirmed("sig", 1, Commitment.CONFIRMED),
    ): WithdrawSigner = { prepare ->
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
    fun `nothing is chosen by default, and nothing is signed until something is`() = runTest {
        val chain = FakeChain(both)
        val model = WithdrawModel(chain, { authority }, this)
        model.load()
        advanceUntilIdle()
        val ready = model.state.value as WithdrawState.Ready
        assertFalse(ready.revoke)
        assertFalse(ready.closeRig)
        assertFalse(ready.canSign)
        model.confirm(wallet())
        advanceUntilIdle()
        assertTrue(chain.requests.isEmpty())
    }

    @Test
    fun `taking the SOL back asks for the revoke only and keeps the rig bound`() = runTest {
        val chain = FakeChain(both)
        val closed = mutableListOf<Pubkey>()
        val model = WithdrawModel(chain, { authority }, this) { closed += it }
        model.load()
        advanceUntilIdle()
        model.setRevoke(true)
        model.confirm(wallet())
        advanceUntilIdle()
        assertEquals(WithdrawRequest(revoke = true, closeRig = false), chain.requests.single())
        assertEquals("Confirmed on-chain. 0.02000448 SOL back in your wallet from the ORE Automation.", (model.state.value as WithdrawState.Done).message)
        assertTrue(closed.isEmpty())
    }

    @Test
    fun `closing the rig tells the phone which rig is gone, only once it is confirmed`() = runTest {
        val chain = FakeChain(both)
        val closed = mutableListOf<Pubkey>()
        val model = WithdrawModel(chain, { authority }, this) { closed += it }
        model.load()
        advanceUntilIdle()
        model.setRevoke(true)
        model.setCloseRig(true)
        model.confirm(wallet(outcome = ConfirmationOutcome.FailedOnChain("sig", 1, "custom 12")))
        advanceUntilIdle()
        assertEquals(WithdrawModel.NOT_LANDED, (model.state.value as WithdrawState.Ready).problem)
        assertTrue(closed.isEmpty())
        model.confirm(wallet(outcome = ConfirmationOutcome.TimedOut("sig", 30)))
        advanceUntilIdle()
        assertEquals(WithdrawModel.NOT_CONFIRMED, (model.state.value as WithdrawState.Ready).problem)
        assertTrue(closed.isEmpty())

        model.confirm(wallet())
        advanceUntilIdle()
        assertEquals(WithdrawRequest(revoke = true, closeRig = true), chain.requests.last())
        assertTrue((model.state.value as WithdrawState.Done).message.contains("Rig closed: 0.00244992 SOL"))
        assertEquals(listOf(authority), closed)
    }

    @Test
    fun `a rig that cannot be closed cannot be chosen for closing, but the SOL still comes back`() = runTest {
        val chain = FakeChain(shiftOpen)
        val model = WithdrawModel(chain, { authority }, this)
        model.load()
        advanceUntilIdle()
        model.setCloseRig(true)
        assertFalse((model.state.value as WithdrawState.Ready).closeRig)
        model.setRevoke(true)
        assertTrue((model.state.value as WithdrawState.Ready).canSign)
        model.confirm(wallet())
        advanceUntilIdle()
        assertEquals(WithdrawRequest(revoke = true), chain.requests.single())
        assertTrue(model.state.value is WithdrawState.Done)
    }

    @Test
    fun `a refusal at signing time is shown in its fixed words`() = runTest {
        // The rig armed a shift between the preview and the signature.
        val chain = FakeChain(both).apply { refuse = CloseBlock.SHIFT_OPEN.message }
        val closed = mutableListOf<Pubkey>()
        val model = WithdrawModel(chain, { authority }, this) { closed += it }
        model.load()
        advanceUntilIdle()
        model.setCloseRig(true)
        model.confirm(wallet())
        advanceUntilIdle()
        val ready = model.state.value as WithdrawState.Ready
        assertEquals(CloseBlock.SHIFT_OPEN.message + " Nothing was sent.", ready.problem)
        assertFalse(ready.working)
        assertTrue(closed.isEmpty())
    }

    @Test
    fun `without a bound rig the wallet is asked who it is, and its Automation can still be emptied`() = runTest {
        val chain = FakeChain(both.copy(hasRig = false, rigClose = null))
        val model = WithdrawModel(chain, { null }, this)
        model.load()
        advanceUntilIdle()
        assertEquals(WithdrawState.NeedsWallet(), model.state.value)
        assertTrue(chain.previews.isEmpty())

        model.connect { WalletResult.Failed("User did not authorize signing") }
        advanceUntilIdle()
        assertEquals(WithdrawState.NeedsWallet(problem = "Wallet: User did not authorize signing"), model.state.value)
        model.connect { WalletResult.NoWalletInstalled }
        advanceUntilIdle()
        assertEquals(WithdrawState.NeedsWallet(problem = WithdrawModel.NO_WALLET), model.state.value)
        model.connect { throw IllegalStateException("token=abc") }
        advanceUntilIdle()
        assertEquals(WithdrawState.NeedsWallet(problem = "Wallet: Wallet request failed"), model.state.value)
        model.connect { WalletResult.Success(WalletAccount(ByteArray(3), "broken")) }
        advanceUntilIdle()
        assertEquals(WithdrawState.NeedsWallet(problem = "Wallet: Wallet request failed"), model.state.value)

        model.connect { WalletResult.Success(account) }
        advanceUntilIdle()
        assertEquals(listOf(authority), chain.previews)
        model.setRevoke(true)
        model.confirm(wallet())
        advanceUntilIdle()
        assertTrue(model.state.value is WithdrawState.Done)
        // A retry after connecting reads for the connected wallet, without asking again.
        model.load()
        advanceUntilIdle()
        assertEquals(listOf(authority, authority), chain.previews)
    }

    @Test
    fun `another wallet is refused before anything is built`() = runTest {
        val chain = FakeChain(both)
        val model = WithdrawModel(chain, { authority }, this)
        model.load()
        advanceUntilIdle()
        model.setRevoke(true)
        model.confirm(wallet(signer = WalletAccount(ByteArray(32) { 9 }, "other")))
        advanceUntilIdle()
        assertTrue(chain.requests.isEmpty())
        assertEquals(WithdrawModel.OTHER_WALLET, (model.state.value as WithdrawState.Ready).problem)
    }

    @Test
    fun `an unreadable chain shows fixed words and can be retried`() = runTest {
        val chain = FakeChain(both).apply { previewFails = true }
        val model = WithdrawModel(chain, { authority }, this)
        model.load()
        advanceUntilIdle()
        val failed = model.state.value as WithdrawState.Unavailable
        assertEquals(WithdrawModel.UNREADABLE, failed.message)
        chain.previewFails = false
        model.load()
        advanceUntilIdle()
        assertEquals(both, (model.state.value as WithdrawState.Ready).facts)
    }

    @Test
    fun `wallet failures, nothing left to sign, and a locked screen while the wallet is open`() = runTest {
        val chain = FakeChain(both)
        val model = WithdrawModel(chain, { authority }, this)
        model.load()
        advanceUntilIdle()
        model.setRevoke(true)
        model.confirm { WalletResult.NoWalletInstalled }
        advanceUntilIdle()
        assertEquals(WithdrawModel.NO_WALLET, (model.state.value as WithdrawState.Ready).problem)
        model.confirm { WalletResult.Failed("User did not authorize signing") }
        advanceUntilIdle()
        assertEquals("Wallet: User did not authorize signing", (model.state.value as WithdrawState.Ready).problem)
        chain.nothing = true
        model.confirm(wallet())
        advanceUntilIdle()
        assertEquals(WithdrawModel.NOTHING_LEFT, (model.state.value as WithdrawState.Ready).problem)
        chain.nothing = false
        // Choosing again clears the old problem.
        model.setRevoke(true)
        assertNull((model.state.value as WithdrawState.Ready).problem)

        val before = chain.requests.size
        val gate = CompletableDeferred<Unit>()
        model.confirm { prepare ->
            gate.await()
            wallet()(prepare)
        }
        testScheduler.runCurrent()
        assertTrue((model.state.value as WithdrawState.Ready).working)
        model.setCloseRig(true)
        assertFalse((model.state.value as WithdrawState.Ready).closeRig)
        model.confirm(wallet())
        testScheduler.runCurrent()
        assertEquals(before, chain.requests.size)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(before + 1, chain.requests.size)
        assertFalse(chain.requests.last().closeRig)
        assertTrue(model.state.value is WithdrawState.Done)
    }
}
