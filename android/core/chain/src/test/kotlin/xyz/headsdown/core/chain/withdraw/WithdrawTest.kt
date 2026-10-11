package xyz.headsdown.core.chain.withdraw

import com.solana.transaction.Message
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.headsdown.core.chain.FakeChain
import xyz.headsdown.core.chain.HeadsDownProgram
import xyz.headsdown.core.chain.Ore
import xyz.headsdown.core.chain.Pubkey
import xyz.headsdown.core.chain.TestAccounts
import xyz.headsdown.core.chain.WellKnown
import xyz.headsdown.core.chain.hexBytes
import xyz.headsdown.core.chain.ix.HeadsDownInstructions
import xyz.headsdown.core.chain.ix.OreInstructions
import xyz.headsdown.core.keys.RigSignalState
import xyz.headsdown.core.wallet.WalletCapabilities

/**
 * The two ways out, against an in-memory cluster: taking the SOL back out of the ORE Automation
 * (never refused), and closing the rig (only when that cannot cost the user a bond or a shift).
 */
class WithdrawTest {

    private val authority = Pubkey.fromBase58("7xKXtg2CW87d97TXJSDpbD5jBkheTqA83TZRuJosgAsU")
    private val rigAddress = HeadsDownProgram.rig(authority).address
    private val automationAddress = Ore.automation(authority).address
    private val key = hexBytes("0360fed4ba255a9d31c961eb74c6356d68c049b8923b61fa6ce669622e60f29fb6")
    private val v0 = WalletCapabilities(supportsLegacy = true, supportsV0 = true, maxTransactionsPerRequest = 0)
    private val legacy = WalletCapabilities(supportsLegacy = true, supportsV0 = false, maxTransactionsPerRequest = 0)

    /** FakeChain's rent: (128 + size) x 6,960 lamports. */
    private val rigRent = (128L + 384) * 6_960
    private val tombstoneRent = (128L + 32) * 6_960
    private val automationRent = (128L + 160) * 6_960

    private fun chain() = FakeChain().apply { wallet(authority, 2_000_000_000) }

    private fun FakeChain.automation(balance: Long = 18_000_000, executor: Pubkey = HeadsDownProgram.executor.address) = put(
        automationAddress, Ore.PROGRAM_ID,
        TestAccounts.automationBytes(authority, amount = 100_000, balance = balance, executor = executor),
        lamports = automationRent + balance,
    )

    private fun FakeChain.rig(
        state: RigSignalState = RigSignalState.IDLE,
        shiftId: Long = 7,
        hb: Long = 912,
        shiftOpen: Boolean = false,
        tier: Int = 0,
        sgtMint: Pubkey? = null,
    ) = put(
        rigAddress, HeadsDownProgram.ID,
        if (tier == 0) {
            TestAccounts.rigBytes(authority, key, state, shiftId, hb, shiftOpen = shiftOpen)
        } else {
            TestAccounts.rigBytesFull(authority, key, state, shiftId, shiftOpen, tier = tier, sgtMint = sgtMint)
        },
        lamports = rigRent,
    )

    private fun FakeChain.bond(shiftId: Long) = put(
        HeadsDownProgram.focusBond(rigAddress, shiftId.toULong()).address, HeadsDownProgram.ID,
        TestAccounts.focusBondBytes(rigAddress, authority, shiftId, 100_000_000, 422_600, 1_790_000_000),
    )

    private fun instructions(prepared: PreparedWithdraw): List<Pair<String, ByteArray>> {
        val tx = prepared.transactions.single()
        val message = Message.from(tx.copyOfRange(65, tx.size))
        return message.instructions.map { message.accounts[it.programIdIndex.toInt() and 0xFF].base58() to it.data }
    }

    @Test
    fun `the preview says what the Automation holds and what closing the rig gives back`() = runBlocking {
        val preview = WithdrawService(chain().automation().rig().rpc()).preview(authority)
        assertEquals(Revoke(lamports = (automationRent + 18_000_000).toULong(), balance = 18_000_000uL, headsDown = true), preview.revoke)
        // A rig that armed shifts keeps a 32-byte tombstone, and the tombstone keeps its own rent.
        assertEquals(RigClose((rigRent - tombstoneRent).toULong(), leavesTombstone = true, closesSeekerSeat = false), preview.rigClose)
        assertNull(preview.closeBlock)
        assertTrue(preview.hasRig)
        assertFalse(preview.shiftOpen)
    }

    @Test
    fun `nothing is signed unless it is asked for`() = runBlocking {
        val service = WithdrawService(chain().automation().rig().rpc())
        assertNull(service.prepare(authority, WithdrawRequest(), v0))
    }

    @Test
    fun `revoke is ORE's automate with no executor, alone in the transaction`() = runBlocking {
        val prepared = WithdrawService(chain().automation().rig().rpc()).prepare(authority, WithdrawRequest(revoke = true), v0)!!
        val expected = OreInstructions.revoke(authority)
        val (program, data) = instructions(prepared).single()
        assertEquals(Ore.PROGRAM_ID.toBase58(), program)
        assertTrue(expected.data.contentEquals(data))
        assertEquals((automationRent + 18_000_000).toULong(), prepared.plan.revoke!!.lamports)
        assertNull(prepared.plan.rigClose)
        assertEquals(authority, prepared.authority)
    }

    @Test
    fun `revoke is never refused, whatever the rig is doing`() = runBlocking {
        for (state in listOf(RigSignalState.ARMED, RigSignalState.DOWN, RigSignalState.COOLING, RigSignalState.BROKEN, RigSignalState.FROZEN)) {
            val chain = chain().automation().rig(state = state, shiftOpen = true).bond(7)
            val service = WithdrawService(chain.rpc())
            val preview = service.preview(authority)
            assertTrue(state.name, preview.shiftOpen)
            assertEquals(state.name, CloseBlock.SHIFT_OPEN, preview.closeBlock)
            assertNull(state.name, preview.rigClose)
            val prepared = service.prepare(authority, WithdrawRequest(revoke = true), legacy)!!
            assertEquals(state.name, 1, instructions(prepared).size)
        }
        // Without a rig at all (a reinstalled app, another client): the SOL still comes back.
        val prepared = WithdrawService(chain().automation().rpc()).prepare(authority, WithdrawRequest(revoke = true, closeRig = true), v0)!!
        assertEquals(1, instructions(prepared).size)
        assertNull(prepared.plan.rigClose)
    }

    @Test
    fun `an Automation set up for another executor is shown as such and can still be closed`() = runBlocking {
        val other = Pubkey(ByteArray(32) { 9 })
        val service = WithdrawService(chain().automation(executor = other).rpc())
        assertFalse(service.preview(authority).revoke!!.headsDown)
        assertEquals(1, instructions(service.prepare(authority, WithdrawRequest(revoke = true), v0)!!).size)
    }

    @Test
    fun `no Automation means nothing to revoke, and lamports sent to its address are not one`() = runBlocking {
        val none = WithdrawService(chain().rig().rpc())
        assertNull(none.preview(authority).revoke)
        assertNull(none.prepare(authority, WithdrawRequest(revoke = true), v0))
        val prefunded = WithdrawService(chain().apply { wallet(automationAddress, 890_880) }.rpc())
        assertNull(prefunded.preview(authority).revoke)
    }

    @Test
    fun `closing an idle rig sends close_rig after the revoke, in one transaction`() = runBlocking {
        val prepared = WithdrawService(chain().automation().rig().rpc()).prepare(authority, WithdrawRequest(revoke = true, closeRig = true), v0)!!
        val ixs = instructions(prepared)
        assertEquals(listOf(Ore.PROGRAM_ID.toBase58(), HeadsDownProgram.ID.toBase58()), ixs.map { it.first })
        assertTrue(HeadsDownInstructions.closeRig(authority).data.contentEquals(ixs[1].second))
        assertEquals((rigRent - tombstoneRent).toULong(), prepared.plan.rigClose!!.rentBackLamports)
    }

    @Test
    fun `a rig that never armed nor signed closes fully, and a frozen one can be closed`() = runBlocking {
        val fresh = WithdrawService(chain().rig(shiftId = 0, hb = 0).rpc()).preview(authority)
        assertEquals(RigClose(rigRent.toULong(), leavesTombstone = false, closesSeekerSeat = false), fresh.rigClose)
        val frozen = WithdrawService(chain().rig(state = RigSignalState.FROZEN).rpc()).preview(authority)
        assertNull(frozen.closeBlock)
        assertTrue(frozen.rigClose!!.leavesTombstone)
        // One signed message is enough for a tombstone, even with no shift ever armed.
        assertTrue(WithdrawService(chain().rig(shiftId = 0, hb = 3).rpc()).preview(authority).rigClose!!.leavesTombstone)
    }

    @Test
    fun `a rig with an open shift or a bond still locked is not closed, and the reason is fixed text`() = runBlocking {
        val open = WithdrawService(chain().rig(state = RigSignalState.BROKEN, shiftOpen = true).rpc())
        assertEquals(CloseBlock.SHIFT_OPEN, open.preview(authority).closeBlock)
        val refusedOpen = assertThrows(WithdrawRefusedException::class.java) { runBlocking { open.prepare(authority, WithdrawRequest(closeRig = true), v0) } }
        assertEquals(CloseBlock.SHIFT_OPEN.message, refusedOpen.reason)

        // The shift is sealed but its bond has not been taken back: closing now would abandon it.
        val bonded = WithdrawService(chain().rig().bond(7).rpc())
        assertEquals(CloseBlock.BOND_HELD, bonded.preview(authority).closeBlock)
        assertNull(bonded.preview(authority).rigClose)
        val refusedBond = assertThrows(WithdrawRefusedException::class.java) { runBlocking { bonded.prepare(authority, WithdrawRequest(revoke = true, closeRig = true), v0) } }
        assertEquals(CloseBlock.BOND_HELD.message, refusedBond.reason)
        // Lamports sent to the bond address are not a bond.
        val prefunded = chain().rig().apply { wallet(HeadsDownProgram.focusBond(rigAddress, 7uL).address, 890_880) }
        assertNull(WithdrawService(prefunded.rpc()).preview(authority).closeBlock)
    }

    @Test
    fun `a Seeker rig's close carries its seat`() = runBlocking {
        val mint = Pubkey(ByteArray(32) { 4 })
        val service = WithdrawService(chain().rig(tier = 1, sgtMint = mint).rpc())
        assertTrue(service.preview(authority).rigClose!!.closesSeekerSeat)
        val prepared = service.prepare(authority, WithdrawRequest(closeRig = true), v0)!!
        val tx = prepared.transactions.single()
        val accounts = Message.from(tx.copyOfRange(65, tx.size)).accounts.map { it.base58() }
        assertTrue(HeadsDownProgram.seekerSeat(mint).address.toBase58() in accounts)
    }

    @Test
    fun `a closed rig and another wallet's rig are never offered for closing`() = runBlocking {
        val tombstone = java.nio.ByteBuffer.allocate(32).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
            put(0, 10); put(1, 1); put(2, HeadsDownProgram.rig(authority).bump.toByte()); putLong(8, 7); putLong(16, 912)
        }.array()
        val closed = WithdrawService(chain().put(rigAddress, HeadsDownProgram.ID, tombstone).rpc())
        assertFalse(closed.preview(authority).hasRig)
        assertNull(closed.preview(authority).rigClose)
        assertNull(closed.prepare(authority, WithdrawRequest(closeRig = true), v0))

        val other = Pubkey(ByteArray(32) { 5 })
        val state = WithdrawService(chain().rig().rpc()).read(authority)
        val refused = assertThrows(WithdrawRefusedException::class.java) { WithdrawComposer.compose(other, WithdrawRequest(closeRig = true), state) }
        assertEquals(WithdrawRefusedException.OTHER_AUTHORITY, refused.reason)
        // Not even the revoke of an Automation that is not this wallet's.
        assertNull(WithdrawComposer.revoke(other, WithdrawService(chain().automation().rpc()).read(authority)))
    }

    @Test
    fun `a priority fee adds the compute budget in front`() = runBlocking {
        val prepared = WithdrawService(chain().automation().rpc()).prepare(authority, WithdrawRequest(revoke = true, priorityMicroLamports = 5_000uL), v0)!!
        assertEquals(
            listOf(WellKnown.COMPUTE_BUDGET.toBase58(), WellKnown.COMPUTE_BUDGET.toBase58(), Ore.PROGRAM_ID.toBase58()),
            instructions(prepared).map { it.first },
        )
    }

    // --- shift-log rent ---

    private val logRent = (128L + 128) * 6_960
    private val day = 86_400L
    private val now = 1_793_000_000L

    /** A sealed shift's log. [payer]: who paid its rent (null: sealed before the program recorded it). */
    private fun FakeChain.log(shiftId: Long, endedDaysAgo: Long, payer: Pubkey? = authority) = put(
        HeadsDownProgram.shiftLog(rigAddress, shiftId.toULong()).address, HeadsDownProgram.ID,
        TestAccounts.shiftLogBytes(rigAddress, shiftId, endTs = now - endedDaysAgo * day).also { bytes ->
            payer?.bytes?.copyInto(bytes, destinationOffset = 112, startIndex = 0, endIndex = 16)
        },
        lamports = logRent,
    )

    private fun service(chain: FakeChain) = WithdrawService(chain.rpc()) { now }

    @Test
    fun `the preview counts the rent the wallet's logs hold and says when the first can come back`() = runBlocking {
        // Shifts 5, 6 and 7 are sealed; all three inside their 30 days.
        val preview = service(chain().rig().log(5, 3).log(6, 2).log(7, 1)).preview(authority)
        assertEquals(
            LogRent(logs = 3, lamports = (3 * logRent).toULong(), ripe = emptyList(), ripeLamports = 0uL, nextRipeAtUnix = now - 3 * day + 30 * day),
            preview.logRent,
        )
        // No sealed shift, no log: nothing to say.
        assertNull(service(chain().rig()).preview(authority).logRent)
        assertNull(service(chain().automation()).preview(authority).logRent)
    }

    @Test
    fun `only logs past their 30 days are closed, oldest first, each to the wallet that paid`() = runBlocking {
        val chain = chain().rig().log(4, 45).log(5, 31).log(6, 29).log(7, 1)
        val preview = service(chain).preview(authority)
        assertEquals(listOf(4uL, 5uL), preview.logRent!!.ripe)
        assertEquals((2 * logRent).toULong(), preview.logRent!!.ripeLamports)
        assertEquals(now + day, preview.logRent!!.nextRipeAtUnix) // shift 6, tomorrow

        val prepared = service(chain).prepare(authority, WithdrawRequest(closeLogs = true), v0)!!
        assertEquals(listOf(4uL, 5uL), prepared.plan.logsClosed)
        assertEquals((2 * logRent).toULong(), prepared.plan.logRentLamports)
        val sent = instructions(prepared)
        assertEquals(2, sent.size)
        for ((i, shiftId) in listOf(4uL, 5uL).withIndex()) {
            val expected = HeadsDownInstructions.closeShiftLog(rigAddress, shiftId, authority)
            assertEquals(HeadsDownProgram.ID.toBase58(), sent[i].first)
            assertTrue(expected.data.contentEquals(sent[i].second))
        }
        // Not asked for: not signed, however ripe.
        assertNull(service(chain).prepare(authority, WithdrawRequest(), v0))
    }

    @Test
    fun `a log on the day its 30 days end waits for the margin between the phone's clock and the chain's`() = runBlocking {
        val state = { endTs: Long -> WithdrawChainState(null, 0uL, null, 0uL, false, 0uL, listOf(SealedLog(7uL, 1_781_760uL, endTs, paidByWallet = true))) }
        val ttl = WithdrawComposer.LOG_TTL_SECONDS
        val margin = WithdrawComposer.LOG_CLOCK_MARGIN_SECONDS
        assertTrue(WithdrawComposer.logRent(state(now - ttl), now)!!.ripe.isEmpty())
        assertTrue(WithdrawComposer.logRent(state(now - ttl - margin + 1), now)!!.ripe.isEmpty())
        assertEquals(listOf(7uL), WithdrawComposer.logRent(state(now - ttl - margin), now)!!.ripe)
        // Until then the sheet names the day the program itself goes by.
        assertEquals(now, WithdrawComposer.logRent(state(now - ttl), now)!!.nextRipeAtUnix)
    }

    @Test
    fun `a log somebody else paid for, or whose bond is still there, is not asked for`() = runBlocking {
        val crank = Pubkey.fromBase58("5Xec1ZUwXcB2ZGeWqqBHrxaHT4WQrGVUgH9xmqgC1kzk")
        // Shift 3: sealed by the crank, which paid its rent. Shift 4: its Focus Bond was never resolved.
        // Shift 5: sealed before the program recorded a payer, so its rent is the rig's wallet's.
        val chain = chain().rig().log(3, 60, payer = crank).log(4, 50).bond(4).log(5, 40, payer = null)
        val rent = service(chain).preview(authority).logRent!!
        assertEquals(2, rent.logs) // 4 and 5 are the wallet's; 3 is the crank's
        assertEquals(listOf(5uL), rent.ripe)
        val prepared = service(chain).prepare(authority, WithdrawRequest(closeLogs = true), v0)!!
        assertEquals(listOf(5uL), prepared.plan.logsClosed)
        assertEquals(1, instructions(prepared).size)
    }

    @Test
    fun `one transaction closes at most eight logs and still takes the SOL back and closes the rig`() = runBlocking {
        var chain = chain().automation().rig(shiftId = 12)
        for (id in 1L..12L) chain = chain.log(id, 40 + id)
        val prepared = service(chain).prepare(authority, WithdrawRequest(revoke = true, closeRig = true, closeLogs = true), legacy)!!
        assertEquals((1uL..8uL).toList(), prepared.plan.logsClosed)
        val sent = instructions(prepared)
        // ORE's automate, eight close_shift_log, close_rig: in that order, in a packet.
        assertEquals(10, sent.size)
        assertEquals(Ore.PROGRAM_ID.toBase58(), sent.first().first)
        assertTrue(HeadsDownInstructions.closeRig(authority).data.contentEquals(sent.last().second))
        assertTrue(prepared.transactions.single().size <= 1232)
        // The four that are left are still there for the next visit.
        assertEquals(12, service(chain).preview(authority).logRent!!.logs)
    }

    @Test
    fun `another wallet's rig has no log rent to offer`() = runBlocking {
        val other = Pubkey.fromBase58("9DSVM862oJrstiPmmQmgqXb7AkuXrKJtYgd1fwbeqeeW")
        val chain = chain().rig().log(5, 40)
        // The rig at this wallet's address is its own by construction; a log names its payer.
        assertEquals(1, service(chain).preview(authority).logRent!!.logs)
        assertNull(service(chain).preview(other).logRent)
    }
}
