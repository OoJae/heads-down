package xyz.headsdown.core.chain.accounts

import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.headsdown.core.chain.Fixtures
import xyz.headsdown.core.chain.HeadsDownProgram
import xyz.headsdown.core.chain.Ore
import xyz.headsdown.core.chain.Pubkey
import xyz.headsdown.core.chain.WellKnown
import xyz.headsdown.core.chain.rpc.AccountInfo
import xyz.headsdown.core.keys.RigSignalState
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AccountDecodersTest {

    // ------------------------------------------------------------------ ORE (real mainnet data)
    // Expected values were decoded independently from the same bytes with Python struct.

    @Test
    fun `decodes the live ORE Board`() = runTest {
        val board = OreAccounts.board(Ore.BOARD, Fixtures.account("ore_board"))
        assertEquals(OreBoard(422_682uL, 451_729_164uL, 451_729_404uL, 906_398_931uL), board)
        assertEquals(240uL, board.endSlot - board.startSlot) // Config.round_slots
    }

    @Test
    fun `decodes the live ORE Treasury motherlode`() = runTest {
        val treasury = OreAccounts.treasury(Ore.TREASURY, Fixtures.account("ore_treasury"))
        assertEquals(36_020_000_000_000uL, treasury.motherlode) // 360.2 ORE
        assertEquals(360uL, treasury.motherlode / Ore.ONE_ORE.toULong())
    }

    @Test
    fun `decodes a live Discretionary Automation`() = runTest {
        val a = OreAccounts.automation(Fixtures.address("ore_automation"), Fixtures.account("ore_automation"))
        assertEquals(1_000_000uL, a.amount)
        assertEquals(Pubkey.fromBase58("2Yf3PNJXbdELHD1jPWSaXTWMYQLuH3jYzsAq4L5VWn3X"), a.authority)
        assertEquals(643_258_370uL, a.balance)
        assertEquals(Pubkey.fromBase58("5CxVeb1wdGipAmJWAbEvaCcuPwWLHs9WexQi3bfJcjC5"), a.executor)
        assertEquals(12_000uL, a.fee)
        assertTrue(a.isDiscretionary)
        assertEquals(1uL, a.reload)
        assertEquals(ULong.MAX_VALUE, a.maxProductionCost) // stored, never enforced by ORE
        assertEquals(0, a.minMotherlode)
        assertEquals(65_535, a.maxMotherlode)
        assertEquals(0, a.splitTiles)
        assertEquals(0, a.soloTiles)
    }

    @Test
    fun `decodes a live Miner`() = runTest {
        val m = OreAccounts.miner(Fixtures.address("ore_miner"), Fixtures.account("ore_miner"))
        assertEquals(Pubkey.fromBase58("2Yf3PNJXbdELHD1jPWSaXTWMYQLuH3jYzsAq4L5VWn3X"), m.authority)
        assertEquals(422_680uL, m.checkpointId)
        assertEquals(10_000uL, m.checkpointFee)
        assertEquals(25, m.deployed.size)
        assertEquals(4_627_155uL, m.deployed.fold(0uL) { acc, v -> acc + v })
        assertEquals(422_681uL, m.roundId)
        assertEquals(2_164_468_731_482uL, m.rewardsOre)
        assertEquals(45_897_707_305uL, m.refinedOre)
        assertEquals(2_362_791_953_911uL, m.lifetimeDeployed)
    }

    @Test
    fun `ORE decoders refuse spoofed owner, size, discriminator, header and address`() = runTest {
        val board = Fixtures.account("ore_board")
        val data = board.data
        val cases = mapOf(
            "System-owned copy with a fake low EMA" to Fixtures.accountWith(board, owner = WellKnown.SYSTEM_PROGRAM),
            "heads_down-owned copy" to Fixtures.accountWith(board, owner = HeadsDownProgram.ID),
            "truncated" to Fixtures.accountWith(board, data = data.copyOf(39)),
            "extended" to Fixtures.accountWith(board, data = data.copyOf(41)),
            "wrong discriminator (Treasury's)" to Fixtures.accountWith(board, data = data.copyOf().also { it[0] = 104 }),
            "dirty header" to Fixtures.accountWith(board, data = data.copyOf().also { it[3] = 1 }),
            "empty" to Fixtures.accountWith(board, data = ByteArray(0)),
        )
        for ((name, account) in cases) {
            assertThrows(name, AccountLayoutException::class.java) { OreAccounts.board(Ore.BOARD, account) }
        }
        // Right bytes, wrong address: a Board lookalike at another address is not the Board.
        assertThrows(AccountLayoutException::class.java) { OreAccounts.board(Ore.TREASURY, board) }
        // An Automation passed at a Miner's address (or any non-PDA address) is rejected.
        val automation = Fixtures.account("ore_automation")
        assertThrows(AccountLayoutException::class.java) { OreAccounts.automation(Fixtures.address("ore_miner"), automation) }
        // A Miner whose authority field was swapped no longer matches its own PDA.
        val miner = Fixtures.account("ore_miner")
        val swapped = miner.data.also { Pubkey.fromBase58("7xKXtg2CW87d97TXJSDpbD5jBkheTqA83TZRuJosgAsU").bytes.copyInto(it, 8) }
        assertThrows(AccountLayoutException::class.java) {
            OreAccounts.miner(Fixtures.address("ore_miner"), Fixtures.accountWith(miner, data = swapped))
        }
    }

    // ------------------------------------------------------------------ heads_down (synthetic)

    private val authority = Pubkey.fromBase58("7xKXtg2CW87d97TXJSDpbD5jBkheTqA83TZRuJosgAsU")
    private val rigPda = HeadsDownProgram.rig(authority)
    private val p256 = ByteArray(33) { if (it == 0) 0x02 else it.toByte() }

    /** A Rig laid out exactly per INTERFACE "Rig (384 bytes)", every field a distinct value. */
    private fun rigBytes(): ByteArray = ByteBuffer.allocate(384).order(ByteOrder.LITTLE_ENDIAN).apply {
        put(0, 2); put(1, 1); put(2, rigPda.bump.toByte())
        position(8); put(authority.bytes)
        position(40); put(p256)
        put(73, 1); put(74, 1); put(75, 2)
        position(80); put(ByteArray(32) { 0x55 })
        putLong(112, 1_000); putLong(120, 500_000_000); putLong(128, 120_000_000); putLong(136, 2_000_000)
        putLong(144, 670_000_000); putLong(152, 1_790_600_000)
        putLong(160, 530_000_000); putLong(168, 1_000_000)
        put(176, 4); put(177, 1); put(178, 2); put(179, 0)
        putLong(184, 1_790_000_000); putLong(192, 1_790_028_800)
        putLong(200, 7); putLong(208, 42); putLong(216, 422_600); putLong(224, 422_601)
        putInt(232, 3); putLong(240, 9_000_000); putLong(248, 40_000_000); putLong(256, 1_789_900_000)
        putLong(264, 422_599); putLong(272, 422_500); putLong(280, 90); putLong(288, 9)
        putLong(296, 1_000); putLong(304, 100); putLong(312, 99_000_000)
        putInt(320, 12); put(324, 2); putLong(328, 20_714)
    }.array()

    private fun rigAccount(data: ByteArray = rigBytes(), owner: Pubkey = HeadsDownProgram.ID) =
        AccountInfo(2_000_000uL, owner, data, executable = false)

    @Test
    fun `decodes every Rig field at its INTERFACE offset`() {
        val rig = HeadsDownAccounts.rig(rigPda.address, rigAccount())
        assertEquals(rigPda.address, rig.address)
        assertEquals(authority, rig.authority)
        assertEquals(p256.toByteString(), rig.p256Pubkey)
        assertEquals(1, rig.attestationLevel)
        assertEquals(RigTier.SEEKER, rig.tier)
        assertEquals(RigSignalState.DOWN, rig.state)
        assertEquals(Pubkey(ByteArray(32) { 0x55 }), rig.sgtMint)
        assertEquals(1_000uL, rig.attestationExpirySlot)
        assertEquals(500_000_000uL, rig.capWeek)
        assertEquals(120_000_000uL, rig.capShift)
        assertEquals(2_000_000uL, rig.capRound)
        assertEquals(670_000_000uL, rig.capMaxCost)
        assertEquals(1_790_600_000L, rig.capsExpiryTs)
        assertEquals(530_000_000uL, rig.planMaxEvCost)
        assertEquals(1_000_000uL, rig.planDigLamports)
        assertEquals(listOf(4, 1, 2, 0), listOf(rig.planSplitTiles, rig.planSoloTiles, rig.planLeaseRounds, rig.planFlags))
        assertEquals(1_790_000_000L, rig.planWindowStartTs)
        assertEquals(1_790_028_800L, rig.planWindowEndTs)
        assertEquals(7uL, rig.shiftId)
        assertEquals(42uL, rig.hbCounter)
        assertEquals(422_600uL, rig.leaseFromRound)
        assertEquals(422_601uL, rig.leaseToRound)
        assertEquals(3L, rig.gapCount)
        assertEquals(9_000_000uL, rig.spentShift)
        assertEquals(40_000_000uL, rig.spentWeek)
        assertEquals(1_789_900_000L, rig.weekStartTs)
        assertEquals(422_599uL, rig.lastDugRound)
        assertEquals(422_500uL, rig.shiftStartRound)
        assertEquals(90uL, rig.shiftDarkRounds)
        assertEquals(9uL, rig.shiftRoundsDug)
        assertEquals(1_000uL, rig.lifetimeDarkRounds)
        assertEquals(100uL, rig.lifetimeRoundsDug)
        assertEquals(99_000_000uL, rig.lifetimeLamportsDeployed)
        assertEquals(12L, rig.streak)
        assertEquals(2, rig.freezesLeft)
        assertEquals(20_714L, rig.lastShiftDay)
    }

    @Test
    fun `a guest rig has no SGT mint`() {
        val data = rigBytes().also { ByteArray(32).copyInto(it, 80); it[74] = 0 }
        val rig = HeadsDownAccounts.rig(rigPda.address, rigAccount(data))
        assertNull(rig.sgtMint)
        assertEquals(RigTier.GUEST, rig.tier)
    }

    @Test
    fun `Rig decoder refuses spoofs`() {
        val good = rigBytes()
        val cases = mapOf(
            "wrong owner" to rigAccount(owner = WellKnown.SYSTEM_PROGRAM),
            "ORE-owned" to rigAccount(owner = Ore.PROGRAM_ID),
            "Config tag" to rigAccount(good.copyOf().also { it[0] = 1 }),
            "ShiftLog tag" to rigAccount(good.copyOf().also { it[0] = 4 }),
            "version 2" to rigAccount(good.copyOf().also { it[1] = 2 }),
            "non-canonical bump" to rigAccount(good.copyOf().also { it[2] = (rigPda.bump - 1).toByte() }),
            "unknown state" to rigAccount(good.copyOf().also { it[75] = 6 }),
            "unknown tier" to rigAccount(good.copyOf().also { it[74] = 2 }),
            "short" to rigAccount(good.copyOf(383)),
            "long" to rigAccount(good.copyOf(385)),
            "authority swapped (not its PDA)" to rigAccount(good.copyOf().also { ByteArray(32) { 9 }.copyInto(it, 8) }),
        )
        for ((name, account) in cases) {
            assertThrows(name, AccountLayoutException::class.java) { HeadsDownAccounts.rig(rigPda.address, account) }
        }
        // Valid bytes presented at another address.
        assertThrows(AccountLayoutException::class.java) { HeadsDownAccounts.rig(HeadsDownProgram.executor.address, rigAccount()) }
    }

    private fun configBytes(): ByteArray = ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN).apply {
        put(0, 1); put(1, 1); put(2, HeadsDownProgram.config.bump.toByte())
        position(8); put(ByteArray(32) { 1 })
        position(40); put(ByteArray(32) { 2 })
        putLong(72, 7_000); putLong(80, 10_000); putShort(88, 2_000); put(90, 0)
        put(91, HeadsDownProgram.executor.bump.toByte())
        position(96); put(ByteArray(32) { 0x33 })
        put(128, 0); putLong(136, 0)
    }.array()

    @Test
    fun `decodes Config and requires canonical bumps`() {
        val config = HeadsDownAccounts.config(
            HeadsDownProgram.config.address,
            AccountInfo(3_000_000uL, HeadsDownProgram.ID, configBytes(), false),
        )
        assertEquals(Pubkey(ByteArray(32) { 1 }), config.governance)
        assertEquals(Pubkey(ByteArray(32) { 2 }), config.registrar)
        assertEquals(7_000uL, config.crankFee)
        assertEquals(10_000uL, config.executorFee)
        assertEquals(2_000, config.buryBps)
        assertFalse(config.paused)
        assertEquals(HeadsDownProgram.executor.bump, config.executorBump)
        assertEquals(ByteArray(32) { 0x33 }.toByteString(), config.oreLayoutHash)

        val bad = mapOf(
            "executor bump from data, not canonical" to configBytes().also { it[91] = 1 },
            "paused = 2" to configBytes().also { it[90] = 2 },
            "Rig tag" to configBytes().also { it[0] = 2 },
        )
        for ((name, data) in bad) {
            assertThrows(name, AccountLayoutException::class.java) {
                HeadsDownAccounts.config(HeadsDownProgram.config.address, AccountInfo(1uL, HeadsDownProgram.ID, data, false))
            }
        }
        assertThrows(AccountLayoutException::class.java) {
            HeadsDownAccounts.config(HeadsDownProgram.executor.address, AccountInfo(1uL, HeadsDownProgram.ID, configBytes(), false))
        }
    }

    @Test
    fun `bounded reader never indexes out of range`() {
        val b = AccountBytes(ByteArray(8))
        assertEquals(0uL, b.u64(0))
        for (read in listOf<() -> Unit>({ b.u64(1) }, { b.u8(8) }, { b.u8(-1) }, { b.bytes(4, 5) }, { b.bytes(0, -1) }, { b.u16(Int.MAX_VALUE) })) {
            assertThrows(AccountLayoutException::class.java) { read() }
        }
    }
}
