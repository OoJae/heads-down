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
import xyz.headsdown.core.chain.Skr
import xyz.headsdown.core.chain.TestAccounts
import xyz.headsdown.core.chain.TestVouchers
import xyz.headsdown.core.chain.WellKnown
import xyz.headsdown.core.chain.accounts.FocusBondAccount
import xyz.headsdown.core.chain.accounts.HdConfig
import xyz.headsdown.core.chain.accounts.HeadsDownAccounts
import xyz.headsdown.core.chain.accounts.OreAccounts
import xyz.headsdown.core.chain.accounts.RigAccount
import xyz.headsdown.core.chain.accounts.ShiftLogAccount
import xyz.headsdown.core.chain.hex
import xyz.headsdown.core.chain.hexBytes
import xyz.headsdown.core.chain.ix.HeadsDownInstructions
import xyz.headsdown.core.chain.ix.OreInstructions
import xyz.headsdown.core.chain.ix.RegistrarAttestation
import xyz.headsdown.core.chain.ix.SkrInstructions
import xyz.headsdown.core.chain.registrar.RegistrarVoucher
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
    private val slot = 451_700_010uL

    private val request = ClockInRequest(
        shiftBudgetLamports = 20_000_000uL, // 0.02 SOL placed: 20 digs of 0.001
        weeklyBudgetLamports = 140_000_000uL,
        capMaxCostPerOre = 670_000_000uL,
        planMaxEvCostPerOre = 530_000_000uL,
        windowSeconds = 8 * 3600,
    )

    private val registrarKey = TestVouchers.registrarKey()

    private fun configWith(executorFee: Long = 10_000, registrar: ByteArray = registrarKey.bytes): HdConfig = HeadsDownAccounts.config(
        HeadsDownProgram.config.address,
        TestAccounts.info(HeadsDownProgram.ID, TestAccounts.configBytes(executorFee = executorFee, registrar = registrar)),
    )

    private val config: HdConfig = configWith()

    private fun rig(
        state: RigSignalState = RigSignalState.IDLE,
        shiftId: Long = 0,
        hb: Long = 0,
        p256: ByteArray = key,
        level: Int = 0,
        attestationExpiry: Long = 0,
        shiftOpen: Boolean? = null,
    ) = HeadsDownAccounts.rig(
        rigAddress,
        TestAccounts.info(
            HeadsDownProgram.ID,
            if (shiftOpen == null) {
                TestAccounts.rigBytes(authority, p256, state, shiftId, hb, attestationLevel = level, attestationExpirySlot = attestationExpiry)
            } else {
                TestAccounts.rigBytes(authority, p256, state, shiftId, hb, shiftOpen, level, attestationExpiry)
            },
        ),
    )

    private fun automation(balance: Long, amount: Long = 250_000, executor: Pubkey = HeadsDownProgram.executor.address, fee: Long = 10_000) =
        OreAccounts.automation(automationAddress, TestAccounts.info(Ore.PROGRAM_ID, TestAccounts.automationBytes(authority, amount, balance, executor, fee)))

    private fun voucher(level: Int = 2, expiry: ULong = slot + 6_480_000uL, p256: ByteArray = key, seed: ByteArray = TestVouchers.DEFAULT_SEED) =
        RegistrarVoucher.verify(TestVouchers.instructionData(authority, p256, level, expiry, seed), authority, p256, level, expiry)

    private fun compose(state: ClockInChainState, req: ClockInRequest = request, voucher: RegistrarVoucher? = null) =
        ClockInComposer.compose(authority, key, req, state, now, voucher)

    private fun List<Instruction>.tags(): List<String> = map {
        when (it.programId) {
            Ore.PROGRAM_ID -> "ore:${it.data[0]}"
            WellKnown.COMPUTE_BUDGET -> "cb:${it.data[0]}"
            WellKnown.ED25519_SIG_VERIFY -> "ed25519"
            WellKnown.ASSOCIATED_TOKEN -> "ata"
            else -> "hd:${it.data[0]}"
        }
    }

    /** ORE AutomateV2 `fee` u64 at data offset 17. */
    private fun Instruction.automateFee(): ULong = data.copyOfRange(17, 25).reversed().fold(0uL) { acc, b -> (acc shl 8) or b.toUByte().toULong() }

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
        assertEquals(HeadsDownInstructions.setCaps(authority, out.caps), out.instructions[2])
        assertEquals(HeadsDownInstructions.armShift(authority, out.plan), out.instructions[3])
        assertEquals(now + 7 * 24 * 3600, out.caps.capsExpiryTs)
        assertEquals(1uL, out.expectedShiftId)
        assertEquals(0uL, out.hbCounterFloor)
        assertEquals(rigAddress, out.rig)
        assertEquals(VoucherUse.NONE, out.voucher)
    }

    @Test
    fun `the executor fee sits inside every cap`() {
        val out = compose(ClockInChainState(config, rig = null, automation = null))
        // INTERFACE v1.1 §6.4: budget = min(plan_dig, min(cap_round, ...) - fee), so the round cap
        // must be dig + fee for a full 0.001 SOL dig, and shift/week carry one fee per dig round.
        assertEquals(1_000_000uL + 10_000uL, out.caps.capRound)
        assertEquals(20_000_000uL + 20uL * 10_000uL, out.caps.capShift)
        assertEquals(140_000_000uL + 140uL * 10_000uL, out.caps.capWeek)
        assertEquals(out.caps.capShift, out.deposit)
        // What dig would place per round with these caps: the whole plan.
        val budget = minOf(out.plan.digLamports, out.caps.capRound - 10_000uL)
        assertEquals(out.plan.digLamports, budget)
        // A budget that is not a whole number of digs still gets a fee for its last, partial dig.
        val partial = compose(ClockInChainState(config, null, null), request.copy(shiftBudgetLamports = 2_500_000uL))
        assertEquals(2_500_000uL + 3uL * 10_000uL, partial.caps.capShift)
    }

    @Test
    fun `the automate fee is Config executor_fee read at offset 80`() {
        for (fee in listOf(5_000L, 7_777L, 10_000L)) {
            val out = compose(ClockInChainState(configWith(executorFee = fee), null, null))
            assertEquals(fee.toULong(), configWith(executorFee = fee).executorFee)
            assertEquals(fee.toULong(), out.instructions.first { it.programId == Ore.PROGRAM_ID }.automateFee())
            assertEquals(1_000_000uL + fee.toULong(), out.caps.capRound)
        }
    }

    @Test
    fun `a fee above the ceiling is refused - RPC data cannot size the wallet prompt`() {
        // The audit's forged Config: executor_fee 5 SOL would have asked the wallet for 100.02 SOL
        // and signed caps of 5.001 / 100.02 / 700.14 SOL.
        for (fee in listOf(100_001L, 5_000_000_000L, 50_000_000_000L)) {
            val refused = assertThrows(ClockInRefusedException::class.java) { compose(ClockInChainState(configWith(executorFee = fee), null, null)) }
            assertEquals(ClockInRefusedException.Reason.FEE_OUT_OF_BOUNDS, refused.reason)
        }
        // At the ceiling the deposit is exactly the bound that is known before any network read.
        val atCeiling = compose(ClockInChainState(configWith(executorFee = 100_000), null, null))
        assertEquals(ClockInComposer.maxDeposit(request), atCeiling.deposit)
        assertEquals(20_000_000uL + 20uL * 100_000uL, ClockInComposer.maxDeposit(request))
        // The deployed fee stays far inside it, and a focus-only shift moves nothing at all.
        assertTrue(compose(ClockInChainState(config, null, null)).deposit < ClockInComposer.maxDeposit(request))
        assertEquals(0uL, ClockInComposer.maxDeposit(request.copy(focusOnly = true)))
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
        assertEquals(10_000uL, staleFee.instructions[0].automateFee())
        val staleAmount = compose(ClockInChainState(config, rig(), automation(balance = 50_000_000, amount = 1_000_000)))
        assertTrue(staleAmount.includesAutomate)
    }

    @Test
    fun `an open previous shift is ended first, against its own ShiftLog`() {
        for (open in listOf(RigSignalState.ARMED, RigSignalState.DOWN, RigSignalState.COOLING, RigSignalState.BROKEN)) {
            val out = compose(ClockInChainState(config, rig(state = open, shiftId = 12), automation(balance = 25_000_000)))
            assertEquals("$open", listOf("hd:11", "hd:3", "hd:5"), out.instructions.tags())
            assertEquals(HeadsDownInstructions.endShift(authority, rigAddress, 12uL), out.instructions[0])
            assertEquals(HeadsDownProgram.shiftLog(rigAddress, 12uL).address, out.instructions[0].accounts[2].pubkey)
            assertEquals(Ore.BOARD, out.instructions[0].accounts[3].pubkey)
            assertEquals(13uL, out.expectedShiftId)
        }
    }

    @Test
    fun `a rig that cannot be armed is refused instead of failing on-chain`() {
        // A live state without an open shift cannot be ended nor armed (INTERFACE v1.1 §6.7).
        val e = assertThrows(ClockInRefusedException::class.java) {
            compose(ClockInChainState(config, rig(state = RigSignalState.ARMED, shiftOpen = false), null))
        }
        assertEquals(ClockInRefusedException.Reason.RIG_BUSY, e.reason)
    }

    @Test
    fun `a new install key rotates the rig key`() {
        val out = compose(ClockInChainState(config, rig(p256 = otherKey), automation(balance = 25_000_000)))
        assertEquals(listOf("hd:4", "hd:3", "hd:5"), out.instructions.tags())
        assertEquals(HeadsDownInstructions.rotateKey(authority, key), out.instructions[0])
    }

    @Test
    fun `a frozen rig is unfrozen by the wallet in the same clock-in`() {
        // Freezing is one tap with the phone's key. It used to be a one-way door in this app: the
        // composer refused a frozen rig and nothing else could unfreeze it.
        val idle = compose(ClockInChainState(config, rig(state = RigSignalState.FROZEN, shiftId = 4), automation(balance = 25_000_000)))
        assertEquals(listOf("hd:10", "hd:3", "hd:5"), idle.instructions.tags())
        assertEquals(HeadsDownInstructions.unfreezeRig(authority), idle.instructions[0])
        assertTrue(idle.unfreezes && !idle.endsPreviousShift)
        assertEquals(5uL, idle.expectedShiftId)
        // Frozen in the middle of a shift: unfreeze leaves it Broken with the shift open, so the
        // shift is ended (sealed with reason freeze) before the new one is armed.
        val mid = compose(ClockInChainState(config, rig(state = RigSignalState.FROZEN, shiftId = 4, shiftOpen = true), automation(balance = 25_000_000)))
        assertEquals(listOf("hd:10", "hd:11", "hd:3", "hd:5"), mid.instructions.tags())
        assertEquals(HeadsDownInstructions.endShift(authority, rigAddress, 4uL), mid.instructions[1])
        assertTrue(mid.unfreezes && mid.endsPreviousShift)
        // A rig that is not frozen gets no unfreeze.
        assertFalse(compose(ClockInChainState(config, rig(shiftId = 4), automation(balance = 25_000_000))).unfreezes)
    }

    @Test
    fun `focus-only never deposits, grants no spending and signs a focus-only plan`() {
        val focus = request.copy(focusOnly = true)
        val out = compose(ClockInChainState(config, rig = null, automation = null), focus)
        assertEquals(listOf("hd:1", "hd:3", "hd:5"), out.instructions.tags())
        assertTrue(out.plan.focusOnly)
        assertEquals(ShiftPlan.FLAG_FOCUS_ONLY, out.plan.flags)
        assertEquals(0uL, out.plan.digLamports)
        assertEquals(0uL, out.deposit)
        assertEquals(listOf(0uL, 0uL, 0uL, 0uL), listOf(out.caps.capWeek, out.caps.capShift, out.caps.capRound, out.caps.capMaxCost))
    }

    @Test
    fun `a day shift sets plan_flags bit1`() {
        val day = compose(ClockInChainState(config, null, null), request.copy(day = true, windowSeconds = 50 * 60))
        assertEquals(ShiftPlan.FLAG_DAY, day.plan.flags)
        assertTrue(day.plan.day)
        // arm_shift data: tag, mode, max_ev_cost(8), dig(8), split, solo, lease, flags @21.
        assertEquals(0x02, day.instructions.last().data[21].toInt())
        val focusDay = compose(ClockInChainState(config, null, null), request.copy(day = true, focusOnly = true))
        assertEquals(ShiftPlan.FLAG_FOCUS_ONLY or ShiftPlan.FLAG_DAY, focusDay.plan.flags)
        assertEquals(0, compose(ClockInChainState(config, null, null)).plan.flags)
    }

    @Test
    fun `lease rounds, tiles and dig size are carried into the plan and caps`() {
        val out = compose(
            ClockInChainState(config, null, null),
            request.copy(leaseRounds = 3, splitTiles = 10, soloTiles = 2, digLamports = 2_000_000uL, planMaxEvCostPerOre = 600_000_000uL),
        )
        assertEquals(3, out.plan.leaseRounds)
        assertEquals(10, out.plan.splitTiles)
        assertEquals(2, out.plan.soloTiles)
        assertEquals(2_000_000uL, out.plan.digLamports)
        assertEquals(600_000_000uL, out.plan.maxEvCost)
        assertEquals(2_010_000uL, out.caps.capRound)
        // Per-tile ORE cap = dig / tiles, floored: 2_000_000 / 12 = 166_666.
        assertEquals(OreInstructions.automateHeadsDown(authority, 166_666uL, out.deposit, 10_000uL), out.instructions[0])
    }

    // ------------------------------------------------------------------ registrar voucher

    @Test
    fun `a first clock-in with a voucher registers attested, referencing the voucher's index`() {
        val v = voucher()
        val out = compose(ClockInChainState(config, null, null, slot), voucher = v)
        assertEquals(listOf("ore:0", "ed25519", "hd:1", "hd:3", "hd:5"), out.instructions.tags())
        assertEquals(VoucherUse.INCLUDED, out.voucher)
        assertEquals(v.instruction, out.instructions[1])
        assertEquals(HeadsDownInstructions.registerRig(authority, key, RegistrarAttestation(1, 0, 2, v.expirySlot)), out.instructions[2])
        // With compute budget first, the absolute index moves with it.
        val cb = compose(ClockInChainState(config, null, null, slot), request.copy(priorityMicroLamports = 1uL), v)
        assertEquals(listOf("cb:2", "cb:3", "ore:0", "ed25519", "hd:1", "hd:3", "hd:5"), cb.instructions.tags())
        assertEquals(46, cb.instructions[4].dataSize)
        assertEquals(3, cb.instructions[4].data[35].toInt()) // ed25519_ix
        assertEquals(WellKnown.INSTRUCTIONS_SYSVAR, cb.instructions[4].accounts.last().pubkey)
    }

    @Test
    fun `a new key with a voucher rotates attested`() {
        val v = voucher(level = 1)
        val out = compose(ClockInChainState(config, rig(p256 = otherKey), automation(balance = 25_000_000), slot), voucher = v)
        assertEquals(listOf("ed25519", "hd:4", "hd:3", "hd:5"), out.instructions.tags())
        assertEquals(HeadsDownInstructions.rotateKey(authority, key, RegistrarAttestation(0, 0, 1, v.expirySlot)), out.instructions[1])
        assertTrue(out.rotatesKey)
    }

    @Test
    fun `a guest rig with the same key is upgraded once, then left alone`() {
        val v = voucher(level = 2)
        val guest = compose(ClockInChainState(config, rig(level = 0), automation(balance = 25_000_000), slot), voucher = v)
        assertEquals(listOf("ed25519", "hd:4", "hd:3", "hd:5"), guest.instructions.tags())
        assertEquals(VoucherUse.INCLUDED, guest.voucher)
        val attested = compose(
            ClockInChainState(config, rig(level = 2, attestationExpiry = v.expirySlot.toLong()), automation(balance = 25_000_000), slot),
            voucher = v,
        )
        assertEquals(listOf("hd:3", "hd:5"), attested.instructions.tags())
        assertEquals(VoucherUse.ALREADY_ATTESTED, attested.voucher)
    }

    @Test
    fun `an unusable voucher never reaches the transaction and the rig registers as a guest`() {
        val cases = mapOf(
            // signed by a key that is not Config.registrar
            voucher(seed = ByteArray(32) { 6 }) to VoucherUse.SKIPPED_WRONG_REGISTRAR,
            // expiring before the transaction can land
            voucher(expiry = slot + 100uL) to VoucherUse.SKIPPED_EXPIRED,
            // for another key
            voucher(p256 = otherKey) to VoucherUse.SKIPPED_OTHER_KEY,
        )
        for ((v, use) in cases) {
            val out = compose(ClockInChainState(config, null, null, slot), voucher = v)
            assertEquals("$use", use, out.voucher)
            assertEquals(listOf("ore:0", "hd:1", "hd:3", "hd:5"), out.instructions.tags())
            assertEquals(HeadsDownInstructions.registerRig(authority, key), out.instructions[1])
        }
        // No slot to date the expiry against: not used.
        assertEquals(VoucherUse.SKIPPED_EXPIRED, compose(ClockInChainState(config, null, null, slot = null), voucher = voucher()).voucher)
    }

    @Test
    fun `priority fees add compute budget instructions first`() {
        val out = compose(ClockInChainState(config, null, null), request.copy(priorityMicroLamports = 5_000uL))
        assertEquals(listOf("cb:2", "cb:3", "ore:0", "hd:1", "hd:3", "hd:5"), out.instructions.tags())
    }

    @Test
    fun `the largest composition fits one legacy packet, voucher included`() {
        val worst = compose(
            ClockInChainState(config, rig(state = RigSignalState.DOWN, shiftId = 3, p256 = otherKey), null, slot),
            request.copy(priorityMicroLamports = 5_000uL),
            voucher(),
        )
        assertEquals(listOf("cb:2", "cb:3", "hd:11", "ed25519", "hd:4", "ore:0", "hd:3", "hd:5"), worst.instructions.tags())
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
        assertThrows(IllegalArgumentException::class.java) { request.copy(leaseRounds = 0) }
        assertThrows(IllegalArgumentException::class.java) { request.copy(leaseRounds = 4) }
        assertThrows(IllegalArgumentException::class.java) { request.copy(splitTiles = 0, soloTiles = 0) }
        assertThrows(IllegalArgumentException::class.java) { request.copy(splitTiles = 16) }
        assertThrows(IllegalArgumentException::class.java) { request.copy(soloTiles = 11) }
        // 1_000_000 over 3 tiles floors to 333_333 per tile: still > 0, fine.
        val three = compose(ClockInChainState(config, null, null), request.copy(splitTiles = 3))
        assertEquals("1516050000000000", three.instructions[0].data.copyOfRange(1, 9).hex()) // 333_333 = 0x051615
    }

    // ------------------------------------------------------------------ Focus Bond

    private val windowEnd = now - 600 // the previous shift's window ended ten minutes ago

    private fun openRig(state: RigSignalState = RigSignalState.DOWN, breakReason: Int = 0, windowEndTs: Long = windowEnd, dark: Long = 200): RigAccount =
        HeadsDownAccounts.rig(
            rigAddress,
            TestAccounts.info(
                HeadsDownProgram.ID,
                TestAccounts.rigBytesFull(
                    authority, key, state, shiftId = 12, shiftOpen = true, breakReason = breakReason, planWindowEndTs = windowEndTs,
                    leaseToRound = 422_899, shiftStartRound = 422_600, shiftDarkRounds = dark, shiftStartTs = windowEndTs - 28_800,
                ),
            ),
        )

    private fun bondOn(shiftId: Long = 12, amount: Long = 100_000_000, windowEndTs: Long = windowEnd): FocusBondAccount = HeadsDownAccounts.focusBond(
        HeadsDownProgram.focusBond(rigAddress, shiftId.toULong()).address,
        TestAccounts.info(HeadsDownProgram.ID, TestAccounts.focusBondBytes(rigAddress, authority, shiftId, amount, 422_600, windowEndTs - 28_800)),
    )

    private fun stateWith(
        rig: RigAccount?,
        skr: ULong = 0uL,
        bond: FocusBondAccount? = null,
        log: ShiftLogAccount? = null,
        skrAccount: Boolean = true,
    ) = ClockInChainState(
        config, rig, automation(balance = 25_000_000), slot, boardRoundId = 422_900uL,
        skrBalance = skr, skrAccountExists = skrAccount, previousBond = bond, previousShiftLog = log,
    )

    @Test
    fun `a focus bond is locked right after arm_shift, behind its vault companion`() {
        val bonded = request.copy(focusBondSkr = 100uL * Skr.ONE_SKR)
        val out = compose(stateWith(rig(shiftId = 6), skr = 250uL * Skr.ONE_SKR), bonded)
        assertEquals(listOf("hd:3", "hd:5", "ata", "hd:20"), out.instructions.tags())
        // The bond is on the shift this transaction arms: shift_id + 1.
        assertEquals(7uL, out.expectedShiftId)
        assertEquals(SkrInstructions.focusBondVault(authority, 7uL), out.instructions[2])
        assertEquals(SkrInstructions.lockFocusBond(authority, 7uL, 100uL * Skr.ONE_SKR), out.instructions[3])
        assertEquals(100uL * Skr.ONE_SKR, out.bondLocked)
        assertEquals(0uL, out.bondReleased)
        // A first clock-in bonds shift 1.
        val first = compose(ClockInChainState(config, null, null, skrBalance = 100uL * Skr.ONE_SKR, skrAccountExists = true), bonded)
        assertEquals(listOf("ore:0", "hd:1", "hd:3", "hd:5", "ata", "hd:20"), first.instructions.tags())
        assertEquals(SkrInstructions.lockFocusBond(authority, 1uL, 100uL * Skr.ONE_SKR), first.instructions.last())
        // No bond asked: nothing of it in the transaction.
        assertEquals(0uL, compose(stateWith(rig(), skr = 250uL * Skr.ONE_SKR)).bondLocked)
    }

    @Test
    fun `a bond the wallet cannot cover or above 5,000 SKR is refused before signing`() {
        val bonded = request.copy(focusBondSkr = 100uL * Skr.ONE_SKR)
        val e = assertThrows(ClockInRefusedException::class.java) { compose(stateWith(rig(), skr = 99uL * Skr.ONE_SKR), bonded) }
        assertEquals(ClockInRefusedException.Reason.INSUFFICIENT_SKR, e.reason)
        compose(stateWith(rig(), skr = 100uL * Skr.ONE_SKR), bonded)
        request.copy(focusBondSkr = Skr.FOCUS_BOND_CAP)
        assertThrows(IllegalArgumentException::class.java) { request.copy(focusBondSkr = Skr.FOCUS_BOND_CAP + 1uL) }
    }

    @Test
    fun `ending a completed shift releases its bond in the clock-in, and the SKR can be bonded again`() {
        // Shift 12 is past its window with dark rounds: end_shift seals it completed.
        val out = compose(stateWith(openRig(), bond = bondOn()))
        assertEquals(listOf("hd:11", "hd:21", "hd:3", "hd:5"), out.instructions.tags())
        assertEquals(SkrInstructions.releaseFocusBond(authority, 12uL), out.instructions[1])
        assertEquals(100uL * Skr.ONE_SKR, out.bondReleased)
        // The released 100 SKR are in the wallet again by the time the new lock runs.
        val rolled = compose(stateWith(openRig(), skr = 0uL, bond = bondOn()), request.copy(focusBondSkr = 100uL * Skr.ONE_SKR))
        assertEquals(listOf("hd:11", "hd:21", "hd:3", "hd:5", "ata", "hd:20"), rolled.instructions.tags())
        assertEquals(SkrInstructions.lockFocusBond(authority, 13uL, 100uL * Skr.ONE_SKR), rolled.instructions.last())
        // The wallet's SKR account was closed meanwhile: it is created before the release.
        val recreated = compose(stateWith(openRig(), bond = bondOn(), skrAccount = false))
        assertEquals(listOf("hd:11", "ata", "hd:21", "hd:3", "hd:5"), recreated.instructions.tags())
    }

    @Test
    fun `a bonded shift still inside its window is never forfeited by clocking in again`() {
        val inside = now + 3_600
        val e = assertThrows(ClockInRefusedException::class.java) {
            compose(stateWith(openRig(windowEndTs = inside), bond = bondOn(windowEndTs = inside)))
        }
        assertEquals(ClockInRefusedException.Reason.BOND_WOULD_FORFEIT, e.reason)
        // Cooling inside the window could still resume: refused too.
        assertThrows(ClockInRefusedException::class.java) {
            compose(stateWith(openRig(RigSignalState.COOLING, breakReason = 1, windowEndTs = inside), bond = bondOn(windowEndTs = inside)))
        }
        // Without a bond the same clock-in goes ahead as before (the shift seals manual).
        assertEquals(listOf("hd:11", "hd:3", "hd:5"), compose(stateWith(openRig(windowEndTs = inside))).instructions.tags())
    }

    @Test
    fun `a bond already lost to a break does not block the clock-in and is not released`() {
        for (lost in listOf(
            openRig(RigSignalState.BROKEN, breakReason = 8, windowEndTs = now + 3_600), // unlocked, inside the window
            openRig(RigSignalState.COOLING, breakReason = 1), // a pickup that never resumed
            openRig(dark = 0), // no dark round: lease_lapse
        )) {
            val out = compose(stateWith(lost, bond = bondOn(windowEndTs = lost.planWindowEndTs)))
            assertEquals(listOf("hd:11", "hd:3", "hd:5"), out.instructions.tags())
            assertEquals(0uL, out.bondReleased)
        }
    }

    @Test
    fun `a bond on a shift someone already sealed is resolved from its ShiftLog`() {
        val idle = rig(shiftId = 12)
        fun log(reason: Int, startRound: Long = 422_600) = HeadsDownAccounts.shiftLog(
            HeadsDownProgram.shiftLog(rigAddress, 12uL).address,
            TestAccounts.info(HeadsDownProgram.ID, TestAccounts.shiftLogBytes(rigAddress, 12, reason, startRound = startRound, startTs = windowEnd - 28_800)),
        )
        val released = compose(stateWith(idle, bond = bondOn(), log = log(0)))
        assertEquals(listOf("hd:21", "hd:3", "hd:5"), released.instructions.tags())
        assertEquals(100uL * Skr.ONE_SKR, released.bondReleased)
        // Sealed with a break, or a log that is not this shift's: nothing to release.
        assertEquals(listOf("hd:3", "hd:5"), compose(stateWith(idle, bond = bondOn(), log = log(6))).instructions.tags())
        assertEquals(listOf("hd:3", "hd:5"), compose(stateWith(idle, bond = bondOn(), log = log(0, startRound = 9))).instructions.tags())
        // Someone else's bond account is ignored.
        val other = Pubkey(ByteArray(32) { 3 })
        val foreign = HeadsDownAccounts.focusBond(
            HeadsDownProgram.focusBond(HeadsDownProgram.rig(other).address, 12uL).address,
            TestAccounts.info(HeadsDownProgram.ID, TestAccounts.focusBondBytes(HeadsDownProgram.rig(other).address, other, 12)),
        )
        assertEquals(listOf("hd:3", "hd:5"), compose(stateWith(idle, bond = foreign, log = log(0))).instructions.tags())
    }

    private fun sizes(plan: ClockInPlan): List<Int> =
        TxVersion.entries.map { TransactionBuilder.unsignedSize(TransactionBuilder.compile(authority, plan.instructions, ByteArray(32), it)) }

    @Test
    fun `a bond fits the clock-in transaction in every ordinary composition`() {
        val bonded = request.copy(focusBondSkr = 100uL * Skr.ONE_SKR)
        val skr = 500uL * Skr.ONE_SKR
        val cases = mapOf(
            // A first clock-in: ORE automate, register_rig, caps, arm, bond.
            "first, guest" to compose(ClockInChainState(config, null, null, slot, skrBalance = skr, skrAccountExists = true), bonded),
            // The same with the registrar's 223-byte voucher.
            "first, attested" to compose(ClockInChainState(config, null, null, slot, skrBalance = skr, skrAccountExists = true), bonded, voucher()),
            // Every night after: end the last shift, take its bond back, top up, arm, bond again.
            "nightly" to compose(
                ClockInChainState(config, openRig(), automation(balance = 5_000_000), slot, 422_900uL, skr, true, bondOn()),
                bonded.copy(priorityMicroLamports = 5_000uL),
            ),
        )
        for ((name, plan) in cases) {
            sizes(plan).forEach { assertTrue("$name: $it bytes", it <= TransactionBuilder.PACKET_DATA_SIZE) }
            assertEquals(name, 100uL * Skr.ONE_SKR, plan.bondLocked)
        }
        assertEquals(listOf("ore:0", "ed25519", "hd:1", "hd:3", "hd:5", "ata", "hd:20"), cases.getValue("first, attested").instructions.tags())
        assertEquals(listOf("cb:2", "cb:3", "hd:11", "hd:21", "ore:0", "hd:3", "hd:5", "ata", "hd:20"), cases.getValue("nightly").instructions.tags())
    }

    @Test
    fun `only the largest composition cannot carry the bond - the service then arms without it`() {
        // Priority fee, an open shift to end, a new device key with its voucher, a refuel and a bond.
        val worst = compose(
            ClockInChainState(
                config, rig(state = RigSignalState.DOWN, shiftId = 3, p256 = otherKey), null, slot,
                boardRoundId = 422_900uL, skrBalance = 500uL * Skr.ONE_SKR, skrAccountExists = true,
            ),
            request.copy(priorityMicroLamports = 5_000uL, focusBondSkr = 100uL * Skr.ONE_SKR),
            voucher(),
        )
        assertEquals(listOf("cb:2", "cb:3", "hd:11", "ed25519", "hd:4", "ore:0", "hd:3", "hd:5", "ata", "hd:20"), worst.instructions.tags())
        assertEquals(listOf(1285, 1287), sizes(worst))
    }

    // ------------------------------------------------------------------ service over RPC

    /** Config, Rig and Automation as given; the ORE Board and the wallet's SKR account absent. */
    private fun rpcFor(vararg accounts: String?, contextSlot: Long = 2): SolanaJsonRpc {
        val list = (accounts.toList() + listOf(null, null)).joinToString(",") { it ?: "null" }
        val hash = Base58.encode(ByteArray(32) { 7 })
        return SolanaJsonRpc(
            FakeTransport.results(
                """{"context":{"slot":1},"value":[$list]}""",
                """{"context":{"slot":$contextSlot},"value":{"blockhash":"$hash","lastValidBlockHeight":5000}}""",
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
    fun `service dates the voucher with the blockhash slot`() = runTest {
        val config = TestAccounts.json(HeadsDownProgram.ID, TestAccounts.configBytes(registrar = registrarKey.bytes))
        val v = voucher(expiry = slot + 6_480_000uL)
        val fresh = ClockInService(rpcFor(config, null, null, contextSlot = slot.toLong()), nowUnix = { now })
            .prepare(authority, key, request, WalletCapabilities.LEGACY_ONLY, v)!!
        assertEquals(VoucherUse.INCLUDED, fresh.plan.voucher)
        val late = ClockInService(rpcFor(config, null, null, contextSlot = (slot + 6_480_000uL).toLong()), nowUnix = { now })
            .prepare(authority, key, request, WalletCapabilities.LEGACY_ONLY, v)!!
        assertEquals(VoucherUse.SKIPPED_EXPIRED, late.plan.voucher)
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
