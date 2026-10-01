package xyz.headsdown.core.chain.bond

import com.solana.transaction.Message
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.headsdown.core.chain.ChainServer
import xyz.headsdown.core.chain.FakeChain
import xyz.headsdown.core.chain.HeadsDownProgram
import xyz.headsdown.core.chain.Ore
import xyz.headsdown.core.chain.Pubkey
import xyz.headsdown.core.chain.Skr
import xyz.headsdown.core.chain.TestAccounts
import xyz.headsdown.core.chain.TestVouchers
import xyz.headsdown.core.chain.WellKnown
import xyz.headsdown.core.chain.clockin.ClockInRefusedException
import xyz.headsdown.core.chain.clockin.ClockInRequest
import xyz.headsdown.core.chain.clockin.ClockInService
import xyz.headsdown.core.chain.hexBytes
import xyz.headsdown.core.chain.registrar.RegistrarVoucher
import xyz.headsdown.core.chain.tx.TransactionBuilder
import xyz.headsdown.core.keys.RigSignalState
import xyz.headsdown.core.keys.ShiftEndReason
import xyz.headsdown.core.wallet.WalletCapabilities

/**
 * The Focus Bond through the services, against an in-memory cluster: locked inside the clock-in
 * transaction, deferred when that transaction is full, locked afterwards on the open shift, and
 * the previous night's bond coming back at the next clock-in.
 */
class FocusBondFlowTest {

    private val authority = Pubkey.fromBase58("7xKXtg2CW87d97TXJSDpbD5jBkheTqA83TZRuJosgAsU")
    private val rigAddress = HeadsDownProgram.rig(authority).address
    private val key = hexBytes("0360fed4ba255a9d31c961eb74c6356d68c049b8923b61fa6ce669622e60f29fb6")
    private val otherKey = hexBytes("036b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296")
    private val now = 1_790_000_000L
    private val slot = 451_700_010uL
    private val v0 = WalletCapabilities(supportsLegacy = true, supportsV0 = true, maxTransactionsPerRequest = 0)
    private val registrar = TestVouchers.registrarKey()

    private val request = ClockInRequest(
        shiftBudgetLamports = 20_000_000uL,
        weeklyBudgetLamports = 140_000_000uL,
        capMaxCostPerOre = 670_000_000uL,
        planMaxEvCostPerOre = 530_000_000uL,
        windowSeconds = 8 * 3600,
        focusBondSkr = 100uL * Skr.ONE_SKR,
    )

    private val servers = mutableListOf<AutoCloseable>()

    @After
    fun close() = servers.forEach { it.close() }

    private fun chain(skr: Long = 250_000_000): FakeChain = FakeChain().apply {
        wallet(authority, 2_000_000_000)
        put(HeadsDownProgram.config.address, HeadsDownProgram.ID, TestAccounts.configBytes(registrar = registrar.bytes))
        put(Ore.BOARD, Ore.PROGRAM_ID, TestAccounts.boardBytes(422_900))
        put(Skr.account(authority), WellKnown.SPL_TOKEN, TestAccounts.tokenAccountBytes(Skr.MINT, authority, skr))
    }

    private fun FakeChain.rig(
        state: RigSignalState,
        shiftId: Long,
        shiftOpen: Boolean,
        windowEnd: Long = now - 600,
        breakReason: Int = 0,
        p256: ByteArray = key,
    ) = put(
        rigAddress, HeadsDownProgram.ID,
        TestAccounts.rigBytesFull(
            authority, p256, state, shiftId, shiftOpen, breakReason, planWindowEndTs = windowEnd, leaseToRound = 422_899,
            shiftStartRound = 422_600, shiftDarkRounds = 200, shiftStartTs = windowEnd - 28_800,
        ),
    )

    private fun FakeChain.bond(shiftId: Long, amount: Long = 100_000_000, windowEnd: Long = now - 600) = put(
        HeadsDownProgram.focusBond(rigAddress, shiftId.toULong()).address, HeadsDownProgram.ID,
        TestAccounts.focusBondBytes(rigAddress, authority, shiftId, amount, 422_600, windowEnd - 28_800),
    )

    private fun programs(tx: ByteArray): List<String> {
        val message = Message.from(tx.copyOfRange(65, tx.size))
        return message.instructions.map { ix ->
            val program = message.accounts[ix.programIdIndex.toInt() and 0xFF].base58()
            when (program) {
                HeadsDownProgram.ID.toBase58() -> "hd:${ix.data[0]}"
                Ore.PROGRAM_ID.toBase58() -> "ore:${ix.data[0]}"
                WellKnown.ASSOCIATED_TOKEN.toBase58() -> "ata"
                WellKnown.ED25519_SIG_VERIFY.toBase58() -> "ed25519"
                WellKnown.COMPUTE_BUDGET.toBase58() -> "cb"
                else -> program
            }
        }
    }

    // ------------------------------------------------------------------ inside the clock-in

    @Test
    fun `the first clock-in arms shift 1 and bonds it in the same transaction`() = runBlocking {
        val chain = chain()
        val prepared = ClockInService(chain.rpc()) { now }.prepare(authority, key, request, v0)!!
        assertEquals(listOf("ore:0", "hd:1", "hd:3", "hd:5", "ata", "hd:20"), programs(prepared.transactions.single()))
        assertFalse(prepared.bondDeferred)
        assertEquals(100uL * Skr.ONE_SKR, prepared.plan.bondLocked)
        // One read (config, rig, automation, board, SKR); no rig yet, so no bond to look for.
        assertEquals(listOf("getMultipleAccounts", "getLatestBlockhash"), chain.methods)
    }

    @Test
    fun `the next night's clock-in ends the shift, takes the bond back and bonds again`() = runBlocking {
        val chain = chain(skr = 0).rig(RigSignalState.DOWN, shiftId = 4, shiftOpen = true).bond(4)
        val prepared = ClockInService(chain.rpc()) { now }.prepare(authority, key, request, v0)!!
        assertEquals(listOf("hd:11", "hd:21", "ore:0", "hd:3", "hd:5", "ata", "hd:20"), programs(prepared.transactions.single()))
        assertEquals(100uL * Skr.ONE_SKR, prepared.plan.bondReleased)
        assertEquals(100uL * Skr.ONE_SKR, prepared.plan.bondLocked)
        assertEquals(5uL, prepared.plan.expectedShiftId)
        // The rig exists: the bond and the ShiftLog slot of its shift are read too.
        assertEquals(listOf("getMultipleAccounts", "getMultipleAccounts", "getLatestBlockhash"), chain.methods)
    }

    @Test
    fun `clocking in over a bonded shift that could still complete is refused`() {
        val inside = now + 3_600
        val chain = chain().rig(RigSignalState.DOWN, shiftId = 4, shiftOpen = true, windowEnd = inside).bond(4, windowEnd = inside)
        val e = runCatching { runBlocking { ClockInService(chain.rpc()) { now }.prepare(authority, key, request, v0) } }.exceptionOrNull()
        assertEquals(ClockInRefusedException.Reason.BOND_WOULD_FORFEIT, (e as ClockInRefusedException).reason)
    }

    @Test
    fun `a clock-in too full for the bond arms without it and says so`() = runBlocking {
        // A new device key with its registrar voucher, an open shift to end, a refuel and a priority fee.
        val chain = chain().rig(RigSignalState.DOWN, shiftId = 3, shiftOpen = true, p256 = otherKey)
        val expiry = slot + 6_480_000uL
        val voucher = RegistrarVoucher.verify(TestVouchers.instructionData(authority, key, 2, expiry), authority, key, 2, expiry)
        chain.slot = slot.toLong()
        val full = request.copy(priorityMicroLamports = 5_000uL)
        val prepared = ClockInService(chain.rpc()) { now }.prepare(authority, key, full, v0, voucher)!!
        assertTrue(prepared.bondDeferred)
        assertEquals(0uL, prepared.plan.bondLocked)
        assertEquals(listOf("cb", "cb", "hd:11", "ed25519", "hd:4", "ore:0", "hd:3", "hd:5"), programs(prepared.transactions.single()))
        assertTrue(prepared.transactions.single().size <= TransactionBuilder.PACKET_DATA_SIZE)
    }

    // ------------------------------------------------------------------ right after, and status

    @Test
    fun `a bond is locked on the open shift in its own transaction`() = runBlocking {
        val inside = now + 3_600
        val chain = chain().rig(RigSignalState.ARMED, shiftId = 7, shiftOpen = true, windowEnd = inside)
        val service = FocusBondService(chain.rpc()) { now }
        val status = service.status(authority)
        assertTrue(status.canLockNow)
        assertFalse(status.bondLost)
        assertEquals(250uL * Skr.ONE_SKR, status.skrBalance)
        assertNull(status.bond)
        // Ending the armed shift now would not seal it completed.
        assertTrue(status.sealIfEndedNow != ShiftEndReason.COMPLETED)

        val prepared = service.prepareLock(authority, 100uL * Skr.ONE_SKR, v0)
        assertEquals(listOf("ata", "hd:20"), programs(prepared.transactions.single()))
        assertEquals(7uL, prepared.shiftId)
        assertEquals(100uL * Skr.ONE_SKR, prepared.amount)
    }

    @Test
    fun `a lock the program would refuse is refused on the phone`() {
        val inside = now + 3_600
        fun refusal(chain: FakeChain, amount: ULong = 100uL * Skr.ONE_SKR): BondRefusedException.Reason {
            val e = runCatching { runBlocking { FocusBondService(chain.rpc()) { now }.prepareLock(authority, amount, v0) } }.exceptionOrNull()
            return (e as BondRefusedException).reason
        }
        assertEquals(BondRefusedException.Reason.NO_OPEN_SHIFT, refusal(chain()))
        assertEquals(BondRefusedException.Reason.NO_OPEN_SHIFT, refusal(chain().rig(RigSignalState.IDLE, 7, shiftOpen = false)))
        // A recorded pickup (Cooling, or back Down with break_reason set), a broken or frozen shift.
        assertEquals(BondRefusedException.Reason.SHIFT_NOT_CLEAN, refusal(chain().rig(RigSignalState.COOLING, 7, true, inside, breakReason = 1)))
        assertEquals(BondRefusedException.Reason.SHIFT_NOT_CLEAN, refusal(chain().rig(RigSignalState.DOWN, 7, true, inside, breakReason = 1)))
        assertEquals(BondRefusedException.Reason.SHIFT_NOT_CLEAN, refusal(chain().rig(RigSignalState.BROKEN, 7, true, inside, breakReason = 8)))
        assertEquals(BondRefusedException.Reason.ALREADY_BONDED, refusal(chain().rig(RigSignalState.DOWN, 7, true, inside).bond(7, windowEnd = inside)))
        assertEquals(BondRefusedException.Reason.INSUFFICIENT_SKR, refusal(chain(skr = 99_999_999).rig(RigSignalState.DOWN, 7, true, inside)))
        // The ShiftLog slot of this shift id is taken (the rig was closed and re-registered).
        val taken = chain().rig(RigSignalState.DOWN, 7, true, inside).apply {
            put(HeadsDownProgram.shiftLog(rigAddress, 7uL).address, HeadsDownProgram.ID, TestAccounts.shiftLogBytes(rigAddress, 7))
        }
        assertEquals(BondRefusedException.Reason.SHIFT_NOT_CLEAN, refusal(taken))
        assertThrows(IllegalArgumentException::class.java) { runBlocking { FocusBondService(chain().rpc()).prepareLock(authority, 0uL, v0) } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { FocusBondService(chain().rpc()).prepareLock(authority, Skr.FOCUS_BOND_CAP + 1uL, v0) } }
    }

    @Test
    fun `status says when a locked bond is already lost`() = runBlocking {
        val inside = now + 3_600
        fun status(chain: FakeChain) = runBlocking { FocusBondService(chain.rpc()) { now }.status(authority) }
        // Locked and intact, inside the window.
        val intact = status(chain().rig(RigSignalState.DOWN, 7, true, inside).bond(7, windowEnd = inside))
        assertEquals(100uL * Skr.ONE_SKR, intact.bond!!.amount)
        assertFalse(intact.bondLost)
        assertFalse(intact.canLockNow)
        assertEquals(ShiftEndReason.MANUAL, intact.sealIfEndedNow)
        // The phone was unlocked: the shift is Broken and the bond is going to the Bury auction.
        val broken = status(chain().rig(RigSignalState.BROKEN, 7, true, inside, breakReason = 8).bond(7, windowEnd = inside))
        assertTrue(broken.bondLost)
        assertEquals(ShiftEndReason.UNLOCKED, broken.sealIfEndedNow)
        // Sealed completed by someone else: waiting to be released, not lost.
        val sealed = chain().rig(RigSignalState.IDLE, 7, shiftOpen = false).bond(7).apply {
            put(HeadsDownProgram.shiftLog(rigAddress, 7uL).address, HeadsDownProgram.ID, TestAccounts.shiftLogBytes(rigAddress, 7, 0, startRound = 422_600, startTs = now - 600 - 28_800))
        }
        assertFalse(status(sealed).bondLost)
        assertNull(status(sealed).sealIfEndedNow)
        // Sealed with a manual end.
        val manual = chain().rig(RigSignalState.IDLE, 7, shiftOpen = false).bond(7).apply {
            put(HeadsDownProgram.shiftLog(rigAddress, 7uL).address, HeadsDownProgram.ID, TestAccounts.shiftLogBytes(rigAddress, 7, 6, startRound = 422_600, startTs = now - 600 - 28_800))
        }
        assertTrue(status(manual).bondLost)
        // No rig at all.
        val none = status(chain())
        assertNull(none.rig)
        assertFalse(none.canLockNow || none.bondLost)
    }

    @Test
    fun `over HTTPS - status and lock read the chain through the real transport`() = runBlocking {
        val server = ChainServer(chain().rig(RigSignalState.DOWN, shiftId = 9, shiftOpen = true, windowEnd = now + 3_600)).also { servers += it }
        val service = FocusBondService(server.rpc()) { now }
        assertTrue(service.status(authority).canLockNow)
        val prepared = service.prepareLock(authority, 50uL * Skr.ONE_SKR, WalletCapabilities.LEGACY_ONLY)
        assertEquals(listOf("ata", "hd:20"), programs(prepared.transactions.single()))
        assertEquals(5_000L, prepared.lastValidBlockHeight)
        assertTrue(server.requestCount >= 5)
    }
}
