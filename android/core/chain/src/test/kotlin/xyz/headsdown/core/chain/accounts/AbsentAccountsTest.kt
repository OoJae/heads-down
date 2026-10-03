package xyz.headsdown.core.chain.accounts

import com.solana.transaction.Message
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.headsdown.core.chain.FakeChain
import xyz.headsdown.core.chain.HeadsDownProgram
import xyz.headsdown.core.chain.Ore
import xyz.headsdown.core.chain.Pubkey
import xyz.headsdown.core.chain.Skr
import xyz.headsdown.core.chain.TestAccounts
import xyz.headsdown.core.chain.WellKnown
import xyz.headsdown.core.chain.bond.FocusBondService
import xyz.headsdown.core.chain.clockin.ClockInRequest
import xyz.headsdown.core.chain.clockin.ClockInService
import xyz.headsdown.core.chain.clockout.BondOutcome
import xyz.headsdown.core.chain.clockout.ClockOutService
import xyz.headsdown.core.chain.clockout.ShiftOutcome
import xyz.headsdown.core.chain.hexBytes
import xyz.headsdown.core.chain.rpc.AccountInfo
import xyz.headsdown.core.keys.RigSignalState
import xyz.headsdown.core.keys.ShiftEndReason
import xyz.headsdown.core.wallet.WalletCapabilities
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * What an address holds before a program has created its account there, and what `close_rig`
 * leaves behind (INTERFACE §12.2): neither may make a wallet's transactions impossible to build.
 * Anyone can send lamports to someone else's PDA, so a reader that chokes on that is a reader
 * anyone can switch off.
 */
class AbsentAccountsTest {

    private val authority = Pubkey.fromBase58("7xKXtg2CW87d97TXJSDpbD5jBkheTqA83TZRuJosgAsU")
    private val rigAddress = HeadsDownProgram.rig(authority).address
    private val key = hexBytes("0360fed4ba255a9d31c961eb74c6356d68c049b8923b61fa6ce669622e60f29fb6")
    private val now = 1_790_000_000L
    private val v0 = WalletCapabilities(supportsLegacy = true, supportsV0 = true, maxTransactionsPerRequest = 0)

    private val request = ClockInRequest(
        shiftBudgetLamports = 20_000_000uL,
        weeklyBudgetLamports = 140_000_000uL,
        capMaxCostPerOre = 670_000_000uL,
        planMaxEvCostPerOre = 530_000_000uL,
        windowSeconds = 8 * 3600,
    )

    /** A System-owned address with lamports and no data: what a plain transfer to a PDA creates. */
    private val lamportsOnly = AccountInfo(890_880uL, WellKnown.SYSTEM_PROGRAM, ByteArray(0), executable = false)

    private fun tombstone(shiftId: Long, hbCounter: Long, lastDugRound: Long = 0): ByteArray =
        ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(0, 10); put(1, 1); put(2, HeadsDownProgram.rig(authority).bump.toByte())
            putLong(8, shiftId); putLong(16, hbCounter); putLong(24, lastDugRound)
        }.array()

    private fun chain(): FakeChain = FakeChain().apply {
        wallet(authority, 2_000_000_000)
        put(HeadsDownProgram.config.address, HeadsDownProgram.ID, TestAccounts.configBytes())
        put(Ore.BOARD, Ore.PROGRAM_ID, TestAccounts.boardBytes(422_900))
        put(Ore.TREASURY, Ore.PROGRAM_ID, TestAccounts.treasuryBytes(totalUnrefined = 9_000_000_000_000))
    }

    private fun FakeChain.lamportsAt(vararg addresses: Pubkey) = apply { addresses.forEach { wallet(it, 890_880) } }

    private fun programs(tx: ByteArray): List<String> {
        val message = Message.from(tx.copyOfRange(65, tx.size))
        return message.instructions.map { ix ->
            when (val program = message.accounts[ix.programIdIndex.toInt() and 0xFF].base58()) {
                HeadsDownProgram.ID.toBase58() -> "hd:${ix.data[0]}"
                Ore.PROGRAM_ID.toBase58() -> "ore:${ix.data[0]}"
                else -> program
            }
        }
    }

    @Test
    fun `an address that holds only lamports is not a created account`() {
        assertNull(null.ifCreated())
        assertNull(lamportsOnly.ifCreated())
        // A program's account stays what it is, and so does a System account that carries data.
        val ours = TestAccounts.info(HeadsDownProgram.ID, ByteArray(32))
        assertSame(ours, ours.ifCreated())
        val nonce = AccountInfo(1_447_680uL, WellKnown.SYSTEM_PROGRAM, ByteArray(80), executable = false)
        assertSame(nonce, nonce.ifCreated())
    }

    @Test
    fun `a Rig PDA holds nothing, a tombstone or a rig, and nothing else is guessed at`() {
        assertEquals(RigSlot.Empty, HeadsDownAccounts.rigSlot(rigAddress, null))
        assertEquals(RigSlot.Empty, HeadsDownAccounts.rigSlot(rigAddress, lamportsOnly))
        assertNull(HeadsDownAccounts.rigOrNull(rigAddress, lamportsOnly))

        val closed = HeadsDownAccounts.rigSlot(rigAddress, TestAccounts.info(HeadsDownProgram.ID, tombstone(7, 912, 422_900)))
        assertEquals(RigTombstoneAccount(rigAddress, 7uL, 912uL, 422_900uL), closed.tombstoneOrNull)
        assertNull(closed.rigOrNull)

        val live = HeadsDownAccounts.rigSlot(rigAddress, TestAccounts.info(HeadsDownProgram.ID, TestAccounts.rigBytes(authority, key, shiftId = 3, hbCounter = 40)))
        assertEquals(3uL, live.rigOrNull!!.shiftId)
        assertNull(live.tombstoneOrNull)

        // 32 bytes of ours under another tag or version is not a tombstone.
        assertThrows(AccountLayoutException::class.java) {
            HeadsDownAccounts.rigSlot(rigAddress, TestAccounts.info(HeadsDownProgram.ID, tombstone(7, 912).also { it[0] = 4 }))
        }
        assertThrows(AccountLayoutException::class.java) {
            HeadsDownAccounts.rigSlot(rigAddress, TestAccounts.info(HeadsDownProgram.ID, tombstone(7, 912).also { it[1] = 2 }))
        }
        // Another program's account at the address is refused, as it always was.
        assertThrows(AccountLayoutException::class.java) {
            HeadsDownAccounts.rigSlot(rigAddress, TestAccounts.info(Ore.PROGRAM_ID, ByteArray(384)))
        }
        assertThrows(AccountLayoutException::class.java) {
            HeadsDownAccounts.rigSlot(rigAddress, TestAccounts.info(Ore.PROGRAM_ID, tombstone(7, 912)))
        }
    }

    @Test
    fun `a clock-in over a tombstone registers again and resumes the closed rig's counters`() = runBlocking {
        val chain = chain().put(rigAddress, HeadsDownProgram.ID, tombstone(shiftId = 7, hbCounter = 912, lastDugRound = 422_800))
        val prepared = ClockInService(chain.rpc()) { now }.prepare(authority, key, request, v0)!!
        // automate, register_rig (1), set_caps (3), arm_shift (5): the same as a first clock-in.
        assertEquals(listOf("ore:0", "hd:1", "hd:3", "hd:5"), programs(prepared.transactions.single()))
        assertTrue(prepared.plan.registersRig)
        // register_rig resumes shift_id 7, so arm_shift opens shift 8; no message at or below 912 is accepted again.
        assertEquals(8uL, prepared.plan.expectedShiftId)
        assertEquals(912uL, prepared.plan.hbCounterFloor)
        assertNull(ClockInService(chain.rpc()) { now }.readRig(authority))
    }

    @Test
    fun `a Focus Bond at a clock-in over a tombstone is locked on the resumed shift`() = runBlocking {
        val chain = chain().put(rigAddress, HeadsDownProgram.ID, tombstone(shiftId = 7, hbCounter = 912))
            .put(Skr.account(authority), WellKnown.SPL_TOKEN, TestAccounts.tokenAccountBytes(Skr.MINT, authority, 250_000_000))
        val bonded = request.copy(focusBondSkr = 100uL * Skr.ONE_SKR)
        val prepared = ClockInService(chain.rpc()) { now }.prepare(authority, key, bonded, v0)!!
        val message = Message.from(prepared.transactions.single().let { it.copyOfRange(65, it.size) })
        val accounts = message.accounts.map { it.base58() }
        assertTrue(HeadsDownProgram.focusBond(rigAddress, 8uL).address.toBase58() in accounts)
        assertTrue(HeadsDownProgram.focusBond(rigAddress, 1uL).address.toBase58() !in accounts)
    }

    @Test
    fun `lamports sent to a new wallet's addresses do not block its first clock-in`() = runBlocking {
        val chain = chain().lamportsAt(rigAddress, Ore.automation(authority).address, Skr.account(authority))
        val prepared = ClockInService(chain.rpc()) { now }.prepare(authority, key, request, v0)!!
        assertEquals(listOf("ore:0", "hd:1", "hd:3", "hd:5"), programs(prepared.transactions.single()))
        assertTrue(prepared.plan.registersRig)
        assertEquals(1uL, prepared.plan.expectedShiftId)
        assertEquals(0uL, prepared.plan.hbCounterFloor)
    }

    @Test
    fun `lamports sent to a shift's bond and log addresses block neither the clock-in nor the clock-out`() = runBlocking {
        val windowEnd = now - 600
        val chain = chain().put(
            rigAddress, HeadsDownProgram.ID,
            TestAccounts.rigBytesFull(
                authority, key, RigSignalState.DOWN, 7, true, planWindowEndTs = windowEnd, leaseToRound = 422_899,
                shiftStartRound = 422_600, shiftDarkRounds = 200, shiftStartTs = windowEnd - 28_800,
            ),
        ).lamportsAt(
            HeadsDownProgram.focusBond(rigAddress, 7uL).address,
            HeadsDownProgram.shiftLog(rigAddress, 7uL).address,
            Ore.miner(authority).address,
            Skr.account(authority),
        )

        val clockIn = ClockInService(chain.rpc()) { now }.prepare(authority, key, request, v0)!!
        // end_shift (11) for the finished shift, then the usual automate, set_caps, arm_shift.
        assertEquals(listOf("hd:11", "ore:0", "hd:3", "hd:5"), programs(clockIn.transactions.single()))
        assertEquals(8uL, clockIn.plan.expectedShiftId)

        val preview = ClockOutService(chain.rpc()) { now }.preview(authority)
        assertEquals(ShiftOutcome.Ends(ShiftEndReason.COMPLETED), preview.plan.shift)
        assertEquals(BondOutcome.None, preview.plan.bond)
        assertNull(preview.state.miner)
        assertEquals(0uL, preview.skrBalance)

        val bond = FocusBondService(chain.rpc()) { now }.status(authority)
        assertNull(bond.bond)
        assertEquals(0uL, bond.skrBalance)
    }

    @Test
    fun `a closed rig reads as no rig at clock-out`() = runBlocking {
        val chain = chain().put(rigAddress, HeadsDownProgram.ID, tombstone(shiftId = 7, hbCounter = 912))
        val preview = ClockOutService(chain.rpc()) { now }.preview(authority)
        assertNull(preview.state.rig)
        assertEquals(ShiftOutcome.NoOpenShift, preview.plan.shift)
        assertTrue(preview.plan.isEmpty)
        assertNull(FocusBondService(chain.rpc()) { now }.status(authority).rig)
    }
}
