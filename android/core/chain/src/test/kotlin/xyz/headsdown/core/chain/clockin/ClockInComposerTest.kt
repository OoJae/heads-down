package xyz.headsdown.core.chain.clockin

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.headsdown.core.chain.FakeTransport
import xyz.headsdown.core.chain.HeadsDownProgram
import xyz.headsdown.core.chain.Ore
import xyz.headsdown.core.chain.Pubkey
import xyz.headsdown.core.chain.TestAccounts
import xyz.headsdown.core.chain.WellKnown
import xyz.headsdown.core.chain.accounts.HeadsDownAccounts
import xyz.headsdown.core.chain.accounts.OreAccounts
import xyz.headsdown.core.chain.hex
import xyz.headsdown.core.chain.hexBytes
import xyz.headsdown.core.chain.ix.HeadsDownInstructions
import xyz.headsdown.core.chain.ix.OreInstructions
import xyz.headsdown.core.chain.rpc.SolanaJsonRpc
import xyz.headsdown.core.chain.tx.Instruction
import xyz.headsdown.core.chain.tx.TransactionBuilder
import xyz.headsdown.core.chain.tx.TxVersion
import xyz.headsdown.core.keys.RigSignalState
import xyz.headsdown.core.keys.ShiftPlan
import xyz.headsdown.core.wallet.Base58
import xyz.headsdown.core.wallet.WalletCapabilities

class ClockInComposerTest {

    private val authority = Pubkey.fromBase58("7xKXtg2CW87d97TXJSDpbD5jBkheTqA83TZRuJosgAsU")
    private val rigAddress = HeadsDownProgram.rig(authority).address
    private val automationAddress = Ore.automation(authority).address
    private val key = hexBytes("0360fed4ba255a9d31c961eb74c6356d68c049b8923b61fa6ce669622e60f29fb6")
    private val otherKey = hexBytes("036b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296")
    private val now = 1_790_000_000L

    private val request = ClockInRequest(
        shiftBudgetLamports = 20_000_000uL, // 0.02 SOL: 20 digs of 0.001
        weeklyBudgetLamports = 140_000_000uL,
        capMaxCostPerOre = 670_000_000uL,
        planMaxEvCostPerOre = 530_000_000uL,
        windowSeconds = 8 * 3600,
    )

    private val config = HeadsDownAccounts.config(
        HeadsDownProgram.config.address,
        TestAccounts.info(HeadsDownProgram.ID, TestAccounts.configBytes(executorFee = 10_000)),
    )

    private fun rig(state: RigSignalState = RigSignalState.IDLE, shiftId: Long = 0, hb: Long = 0, p256: ByteArray = key) =
        HeadsDownAccounts.rig(rigAddress, TestAccounts.info(HeadsDownProgram.ID, TestAccounts.rigBytes(authority, p256, state, shiftId, hb)))

    private fun automation(balance: Long, amount: Long = 250_000, executor: Pubkey = HeadsDownProgram.executor.address, fee: Long = 10_000) =
        OreAccounts.automation(automationAddress, TestAccounts.info(Ore.PROGRAM_ID, TestAccounts.automationBytes(authority, amount, balance, executor, fee)))

    private fun compose(state: ClockInChainState, req: ClockInRequest = request) = ClockInComposer.compose(authority, key, req, state, now)

    private fun List<Instruction>.tags(): List<String> = map {
        when (it.programId) {
            Ore.PROGRAM_ID -> "ore:${it.data[0]}"
            WellKnown.COMPUTE_BUDGET -> "cb:${it.data[0]}"
            else -> "hd:${it.data[0]}"
        }
    }

    @Test
    fun `first clock-in is automate, register_rig, set_caps, arm_shift in one transaction`() {
        val out = compose(ClockInChainState(config, rig = null, automation = null))
        assertEquals(listOf("ore:0", "hd:1", "hd:3", "hd:5"), out.instructions.tags())
        assertTrue(out.registersRig && out.includesAutomate && !out.rotatesKey && !out.endsPreviousShift)
        // Top-up = shift budget + one executor fee per possible dig: 20_000_000 + 20 * 10_000.
        assertEquals(20_200_000uL, out.deposit)
        assertEquals(
            OreInstructions.automateHeadsDown(authority, amountPerTile = 250_000uL, deposit = 20_200_000uL, executorFee = 10_000uL),
            out.instructions[0],
        )
        assertEquals(HeadsDownInstructions.registerRig(authority, key), out.instructions[1])
        assertEquals(ShiftPlan(530_000_000uL, 1_000_000uL, 4, 0, 1, 0, now, now + 8 * 3600), out.plan)
        assertEquals(HeadsDownInstructions.armShift(authority, out.plan), out.instructions[3])
        assertEquals(1_000_000uL, out.caps.capRound)
        assertEquals(20_000_000uL, out.caps.capShift)
        assertEquals(now + 7 * 24 * 3600, out.caps.capsExpiryTs)
        assertEquals(1uL, out.expectedShiftId)
        assertEquals(0uL, out.hbCounterFloor)
        assertEquals(rigAddress, out.rig)
    }

    @Test
    fun `a funded, correctly pointed automation is left alone`() {
        val out = compose(ClockInChainState(config, rig(shiftId = 6, hb = 99), automation(balance = 25_000_000)))
        assertEquals(listOf("hd:3", "hd:5"), out.instructions.tags())
        assertFalse(out.includesAutomate)
        assertEquals(0uL, out.deposit)
        assertEquals(7uL, out.expectedShiftId)
        assertEquals(99uL, out.hbCounterFloor)
    }

    @Test
    fun `a partly funded automation is topped up by the difference`() {
        val out = compose(ClockInChainState(config, rig(), automation(balance = 5_000_000)))
        assertEquals(listOf("ore:0", "hd:3", "hd:5"), out.instructions.tags())
        assertEquals(15_200_000uL, out.deposit)
    }

    @Test
    fun `an automation pointed elsewhere or with a stale fee is re-pointed even when funded`() {
        val elsewhere = compose(ClockInChainState(config, rig(), automation(balance = 50_000_000, executor = Pubkey(ByteArray(32) { 5 }))))
        assertEquals(listOf("ore:0", "hd:3", "hd:5"), elsewhere.instructions.tags())
        assertEquals(0uL, elsewhere.deposit)
        val staleFee = compose(ClockInChainState(config, rig(), automation(balance = 50_000_000, fee = 5_000)))
        assertTrue(staleFee.includesAutomate)
        val staleAmount = compose(ClockInChainState(config, rig(), automation(balance = 50_000_000, amount = 1_000_000)))
        assertTrue(staleAmount.includesAutomate)
    }

    @Test
    fun `an open previous shift is ended first, against its own ShiftLog`() {
        for (open in listOf(RigSignalState.ARMED, RigSignalState.DOWN, RigSignalState.COOLING, RigSignalState.BROKEN)) {
            val out = compose(ClockInChainState(config, rig(state = open, shiftId = 12), automation(balance = 25_000_000)))
            assertEquals("$open", listOf("hd:11", "hd:3", "hd:5"), out.instructions.tags())
            assertEquals(HeadsDownProgram.shiftLog(rigAddress, 12uL).address, out.instructions[0].accounts[2].pubkey)
            assertEquals(13uL, out.expectedShiftId)
        }
    }

    @Test
    fun `a new install key rotates the rig key`() {
        val out = compose(ClockInChainState(config, rig(p256 = otherKey), automation(balance = 25_000_000)))
        assertEquals(listOf("hd:4", "hd:3", "hd:5"), out.instructions.tags())
        assertEquals(HeadsDownInstructions.rotateKey(authority, key), out.instructions[0])
    }

    @Test
    fun `a frozen rig is refused`() {
        val e = assertThrows(ClockInRefusedException::class.java) {
            compose(ClockInChainState(config, rig(state = RigSignalState.FROZEN), null))
        }
        assertEquals(ClockInRefusedException.Reason.RIG_FROZEN, e.reason)
    }

    @Test
    fun `focus-only never deposits and signs a focus-only plan`() {
        val focus = request.copy(focusOnly = true)
        val out = compose(ClockInChainState(config, rig = null, automation = null), focus)
        assertEquals(listOf("hd:1", "hd:3", "hd:5"), out.instructions.tags())
        assertTrue(out.plan.focusOnly)
        assertEquals(0uL, out.plan.digLamports)
        assertEquals(0uL, out.deposit)
    }

    @Test
    fun `priority fees add compute budget instructions first`() {
        val out = compose(ClockInChainState(config, null, null), request.copy(priorityMicroLamports = 5_000uL))
        assertEquals(listOf("cb:2", "cb:3", "ore:0", "hd:1", "hd:3", "hd:5"), out.instructions.tags())
    }

    @Test
    fun `the largest composition fits one legacy packet`() {
        val worst = compose(
            ClockInChainState(config, rig(state = RigSignalState.DOWN, shiftId = 3, p256 = otherKey), null),
            request.copy(priorityMicroLamports = 5_000uL),
        )
        assertEquals(listOf("cb:2", "cb:3", "hd:11", "hd:4", "ore:0", "hd:3", "hd:5"), worst.instructions.tags())
        for (v in TxVersion.entries) {
            val tx = TransactionBuilder.unsignedTransaction(TransactionBuilder.compile(authority, worst.instructions, ByteArray(32), v))
            assertTrue("$v: ${tx.size}", tx.size <= TransactionBuilder.PACKET_DATA_SIZE)
        }
    }

    @Test
    fun `requests are validated`() {
        assertThrows(IllegalArgumentException::class.java) { request.copy(digLamports = 999_999uL) }
        assertThrows(IllegalArgumentException::class.java) { request.copy(shiftBudgetLamports = 500_000uL) }
        assertThrows(IllegalArgumentException::class.java) { request.copy(weeklyBudgetLamports = 1_000_000uL) }
        assertThrows(IllegalArgumentException::class.java) { request.copy(planMaxEvCostPerOre = 700_000_000uL) }
        assertThrows(IllegalArgumentException::class.java) { request.copy(windowSeconds = 30) }
        assertThrows(IllegalArgumentException::class.java) { request.copy(windowSeconds = 25 * 3600) }
        // 1_000_000 over 3 tiles floors to 333_333 per tile: still > 0, fine.
        val three = compose(ClockInChainState(config, null, null), request.copy(splitTiles = 3))
        assertEquals("1516050000000000", three.instructions[0].data.copyOfRange(1, 9).hex()) // 333_333 = 0x051615
    }

    // ------------------------------------------------------------------ service over RPC

    private fun rpcFor(vararg accounts: String?): SolanaJsonRpc {
        val list = accounts.joinToString(",") { it ?: "null" }
        val hash = Base58.encode(ByteArray(32) { 7 })
        return SolanaJsonRpc(
            FakeTransport.results(
                """{"context":{"slot":1},"value":[$list]}""",
                """{"context":{"slot":2},"value":{"blockhash":"$hash","lastValidBlockHeight":5000}}""",
            ),
        )
    }

    @Test
    fun `service builds a v0 or legacy transaction from chain state`() = runTest {
        val config = TestAccounts.json(HeadsDownProgram.ID, TestAccounts.configBytes())
        for ((caps, version) in listOf(WalletCapabilities(true, true, 0) to TxVersion.V0, WalletCapabilities.LEGACY_ONLY to TxVersion.LEGACY)) {
            val prepared = ClockInService(rpcFor(config, null, null), nowUnix = { now }).prepare(authority, key, request, caps)!!
            assertEquals(version, prepared.version)
            assertEquals(5000L, prepared.lastValidBlockHeight)
            val tx = prepared.transactions.single()
            assertEquals(1, tx[0].toInt()) // one signature slot: the wallet
            assertTrue(tx.copyOfRange(1, 65).all { it.toInt() == 0 })
            assertEquals(version == TxVersion.V0, (tx[65].toInt() and 0x80) != 0)
            assertEquals(4, prepared.plan.instructions.size)
        }
    }

    @Test
    fun `service returns null when heads_down is not initialized on the cluster`() = runTest {
        assertNull(ClockInService(rpcFor(null, null, null)).prepare(authority, key, request, WalletCapabilities.LEGACY_ONLY))
    }

    @Test
    fun `service refuses spoofed chain state`() = runTest {
        // A Config owned by the System program is not the heads_down Config.
        val fake = TestAccounts.json(WellKnown.SYSTEM_PROGRAM, TestAccounts.configBytes())
        val e = runCatching { ClockInService(rpcFor(fake, null, null)).prepare(authority, key, request, WalletCapabilities.LEGACY_ONLY) }.exceptionOrNull()
        assertTrue("$e", e is IllegalArgumentException)
        // A Rig for another authority stored at our Rig address fails the PDA check.
        val other = Pubkey(ByteArray(32) { 3 })
        val config = TestAccounts.json(HeadsDownProgram.ID, TestAccounts.configBytes())
        val alien = TestAccounts.json(HeadsDownProgram.ID, TestAccounts.rigBytes(other, key))
        val e2 = runCatching { ClockInService(rpcFor(config, alien, null)).prepare(authority, key, request, WalletCapabilities.LEGACY_ONLY) }.exceptionOrNull()
        assertTrue("$e2", e2 is IllegalArgumentException)
    }
}
