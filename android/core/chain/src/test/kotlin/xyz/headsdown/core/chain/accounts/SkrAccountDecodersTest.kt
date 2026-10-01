package xyz.headsdown.core.chain.accounts

import kotlinx.coroutines.runBlocking
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
import xyz.headsdown.core.chain.Skr
import xyz.headsdown.core.chain.TestAccounts
import xyz.headsdown.core.chain.WellKnown
import xyz.headsdown.core.chain.rpc.AccountInfo
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The v1.2 decoders (INTERFACE §11.3) and the SPL Token ones (§11.1): every field at its pinned
 * offset, and every account that is not exactly what it claims to be refused before a field is
 * used: wrong owner, size, tag, version, PDA, bump or vault.
 */
class SkrAccountDecodersTest {

    private val host = Pubkey.fromBase58("AxBAe8zXwD3GhbTkwMqQ4VX9LGt1CxGxc7keNPuBinni")
    private val other = Pubkey.fromBase58("HpuzRr6bRVNxjowfTsSjaqL5zmLS7cm4DSHfLPxuLZEX")
    private val table = HeadsDownProgram.stackTable(host, 1uL).address
    private val rig = HeadsDownProgram.rig(host).address
    private val sgtMint = Pubkey.fromBase58("5pWbRnGUS84m3mP1XBMryXjKBTn9EdMUi4a5dPZLVfnp")

    private fun hd(data: ByteArray, lamports: ULong = 1_000_000uL) = TestAccounts.info(HeadsDownProgram.ID, data, lamports)

    private fun rejects(block: () -> Unit) = assertThrows(AccountLayoutException::class.java) { block() }

    /** Owner, size, tag and version: the header rule every heads_down decoder shares. */
    private fun assertHeaderRule(good: ByteArray, decode: (AccountInfo) -> Unit) {
        decode(hd(good))
        rejects { decode(TestAccounts.info(WellKnown.SYSTEM_PROGRAM, good)) }
        rejects { decode(hd(good.copyOf(good.size - 1))) }
        rejects { decode(hd(good + 0)) }
        rejects { decode(hd(good.copyOf().also { it[0] = (it[0] + 1).toByte() })) }
        rejects { decode(hd(good.copyOf().also { it[1] = 2 })) }
        // A non-canonical bump is never an account the program created.
        rejects { decode(hd(good.copyOf().also { it[2] = (it[2] - 1).toByte() })) }
    }

    @Test
    fun `StackTable decodes every field at its offset`() {
        val bytes = TestAccounts.stackTableBytes(
            host, tableId = 1, bond = 200_000_000, startRound = 422_702, endRound = 422_703, graceGaps = 0, flags = 0, maxSeats = 4,
            status = 1, seatCount = 3, finishers = 2, totalBonds = 600_000_000, payoutsTotal = 560_000_000, buryAmount = 40_000_000,
        )
        val t = HeadsDownAccounts.stackTable(table, hd(bytes))
        assertEquals(host, t.host)
        assertEquals(HeadsDownProgram.skrVault(table), t.vault)
        assertEquals(1uL, t.tableId)
        assertEquals(200uL * Skr.ONE_SKR, t.bond)
        assertEquals(422_702uL to 422_703uL, t.startRound to t.endRound)
        assertEquals(2uL, t.rounds)
        assertEquals(0L, t.graceGaps)
        assertEquals(4, t.maxSeats)
        assertEquals(StackStatus.SETTLED, t.status)
        assertEquals(3, t.seatCount)
        assertEquals(2, t.finishers)
        assertEquals(600_000_000uL, t.totalBonds)
        assertEquals(560_000_000uL, t.payoutsTotal)
        assertEquals(40_000_000uL, t.buryAmount)
        assertEquals(1_790_300_000L, t.refundAfterTs)
        assertEquals(990uL, t.openedRound)
        assertFalse(t.remote || t.buryOnly || t.attestedOnly || t.full)
        val remote = HeadsDownAccounts.stackTable(table, hd(TestAccounts.stackTableBytes(host, 1, flags = 0x05, seatCount = 4)))
        assertTrue(remote.remote && remote.attestedOnly && remote.full && !remote.buryOnly)
    }

    @Test
    fun `StackTable must be its own PDA with its own SKR vault`() {
        val good = TestAccounts.stackTableBytes(host, 1)
        assertHeaderRule(good) { HeadsDownAccounts.stackTable(table, it) }
        // Another host's table bytes at this address, another table id, or a foreign vault.
        rejects { HeadsDownAccounts.stackTable(table, hd(TestAccounts.stackTableBytes(other, 1))) }
        rejects { HeadsDownAccounts.stackTable(table, hd(TestAccounts.stackTableBytes(host, 2))) }
        val foreignVault = good.copyOf().also { Skr.account(other).bytes.copyInto(it, 40) }
        rejects { HeadsDownAccounts.stackTable(table, hd(foreignVault)) }
        // Values the program never writes.
        rejects { HeadsDownAccounts.stackTable(table, hd(good.copyOf().also { it[110] = 3 })) }
        rejects { HeadsDownAccounts.stackTable(table, hd(good.copyOf().also { it[108] = 0x08 })) }
        rejects { HeadsDownAccounts.stackTable(table, hd(TestAccounts.stackTableBytes(host, 1, startRound = 10, endRound = 9))) }
    }

    @Test
    fun `StackSeat is keyed by the rig in person and by the SGT mint remotely`() {
        val inPerson = TestAccounts.stackSeatBytes(table, rig, host, bond = 200_000_000, shiftId = 7, checkedRounds = 5, lastRound = 422_703, payout = 280_000_000, seatIndex = 2, outcome = 1)
        val seatAddress = HeadsDownProgram.stackSeat(table, rig).address
        val s = HeadsDownAccounts.stackSeat(seatAddress, hd(inPerson))
        assertEquals(table, s.table)
        assertEquals(rig, s.rig)
        assertEquals(host, s.authority)
        assertNull(s.sgtMint)
        assertEquals(200_000_000uL, s.bond)
        assertEquals(7uL, s.shiftId)
        assertEquals(5uL, s.checkedRounds)
        assertEquals(422_703uL, s.lastRound)
        assertEquals(280_000_000uL, s.payout)
        assertEquals(2, s.seatIndex)
        assertEquals(SeatOutcome.FINISHED, s.outcome)
        assertFalse(s.broken || s.sgtVerified)
        assertHeaderRule(inPerson) { HeadsDownAccounts.stackSeat(seatAddress, it) }

        // A remote seat: ["stackseat", table, sgt_mint].
        val remoteAddress = HeadsDownProgram.stackSeat(table, sgtMint).address
        val remote = TestAccounts.stackSeatBytes(table, rig, host, sgtMint = sgtMint, keyedBySgt = true, broken = true, outcome = 2)
        val r = HeadsDownAccounts.stackSeat(remoteAddress, hd(remote))
        assertEquals(sgtMint, r.sgtMint)
        assertTrue(r.broken && r.sgtVerified)
        assertEquals(SeatOutcome.FORFEITED, r.outcome)
        // An in-person seat above the guest cap also stores its SGT, but stays keyed by the rig.
        val verified = TestAccounts.stackSeatBytes(table, rig, host, sgtMint = sgtMint)
        assertEquals(sgtMint, HeadsDownAccounts.stackSeat(seatAddress, hd(verified)).sgtMint)

        // A seat of another table or rig at this address is refused.
        rejects { HeadsDownAccounts.stackSeat(seatAddress, hd(TestAccounts.stackSeatBytes(HeadsDownProgram.stackTable(host, 2uL).address, rig, host))) }
        rejects { HeadsDownAccounts.stackSeat(seatAddress, hd(TestAccounts.stackSeatBytes(table, HeadsDownProgram.rig(other).address, other))) }
        rejects { HeadsDownAccounts.stackSeat(seatAddress, hd(inPerson.copyOf().also { it[178] = 3 })) }
        rejects { HeadsDownAccounts.stackSeat(seatAddress, hd(inPerson.copyOf().also { it[177] = 2 })) }
    }

    @Test
    fun `FocusBond decodes and resolves only from its own shift's log`() {
        val bondAddress = HeadsDownProgram.focusBond(rig, 3uL).address
        val bytes = TestAccounts.focusBondBytes(rig, host, shiftId = 3, amount = 500_000_000, shiftStartRound = 422_704, shiftStartTs = 1_790_640_100)
        val b = HeadsDownAccounts.focusBond(bondAddress, hd(bytes))
        assertEquals(rig, b.rig)
        assertEquals(host, b.authority)
        assertEquals(HeadsDownProgram.skrVault(bondAddress), b.vault)
        assertEquals(3uL, b.shiftId)
        assertEquals(500uL * Skr.ONE_SKR, b.amount)
        assertEquals(422_704uL, b.shiftStartRound)
        assertEquals(1_790_640_100L, b.shiftStartTs)
        assertEquals(1_790_640_105L, b.lockedTs)
        assertHeaderRule(bytes) { HeadsDownAccounts.focusBond(bondAddress, it) }
        rejects { HeadsDownAccounts.focusBond(bondAddress, hd(TestAccounts.focusBondBytes(rig, host, shiftId = 4))) }
        rejects { HeadsDownAccounts.focusBond(bondAddress, hd(bytes.copyOf().also { Skr.account(host).bytes.copyInto(it, 72) })) }

        val logAddress = HeadsDownProgram.shiftLog(rig, 3uL).address
        fun log(startRound: Long = 422_704, startTs: Long = 1_790_640_100, reason: Int = 0) =
            HeadsDownAccounts.shiftLog(logAddress, hd(TestAccounts.shiftLogBytes(rig, 3, reason, startRound = startRound, startTs = startTs)))
        assertTrue(b.isResolvedBy(log()))
        assertTrue(log().completed)
        assertFalse(log(reason = 6).completed)
        // A later incarnation's log in the same slot (the rig was closed and re-registered).
        assertFalse(b.isResolvedBy(log(startRound = 500_000)))
        assertFalse(b.isResolvedBy(log(startTs = 1_799_000_000)))
    }

    @Test
    fun `ShiftLog decodes the sealed shift`() {
        val logAddress = HeadsDownProgram.shiftLog(rig, 9uL).address
        val bytes = TestAccounts.shiftLogBytes(rig, 9, breakReason = 8, startRound = 100, endRound = 400, darkRounds = 250, mode = 1)
        val l = HeadsDownAccounts.shiftLog(logAddress, hd(bytes))
        assertEquals(rig, l.rig)
        assertEquals(9uL, l.shiftId)
        assertEquals(100uL to 400uL, l.startRound to l.endRound)
        assertEquals(250uL, l.darkRounds)
        assertEquals(12uL, l.roundsDug)
        assertEquals(12_120_000uL, l.lamportsDeployed)
        assertEquals(8, l.breakReason)
        assertEquals(1, l.mode)
        assertEquals(1_790_000_000L to 1_790_028_900L, l.startTs to l.endTs)
        assertHeaderRule(bytes) { HeadsDownAccounts.shiftLog(logAddress, it) }
        rejects { HeadsDownAccounts.shiftLog(logAddress, hd(TestAccounts.shiftLogBytes(rig, 10))) }
    }

    @Test
    fun `GiftEscrow decodes, and must hold what it promises`() {
        val gift = HeadsDownProgram.giftEscrow(host, 2uL).address
        val bytes = TestAccounts.giftEscrowBytes(host, 2, sgtMint, kind = 1, lamports = 500_000_000, createdTs = 1_790_697_602)
        val g = HeadsDownAccounts.giftEscrow(gift, hd(bytes, lamports = 501_781_760uL))
        assertEquals(host, g.sender)
        assertEquals(sgtMint, g.recipient)
        assertEquals(2uL, g.nonce)
        assertEquals(500_000_000uL, g.lamports)
        assertEquals(1_790_697_602L, g.createdTs)
        assertEquals(1_793_289_602L, g.expiryTs) // the golden vector's expiry: created + 30 days
        assertTrue(g.forSgtMint)
        assertFalse(HeadsDownAccounts.giftEscrow(gift, hd(TestAccounts.giftEscrowBytes(host, 2, other), 600_000_000uL)).forSgtMint)
        assertHeaderRule(TestAccounts.giftEscrowBytes(host, 2, other, lamports = 1_000)) { HeadsDownAccounts.giftEscrow(gift, it) }
        // Fewer lamports than the gift, an unknown kind, no recipient, another sender or nonce.
        rejects { HeadsDownAccounts.giftEscrow(gift, hd(bytes, lamports = 499_999_999uL)) }
        rejects { HeadsDownAccounts.giftEscrow(gift, hd(bytes.copyOf().also { it[104] = 2 }, 600_000_000uL)) }
        rejects { HeadsDownAccounts.giftEscrow(gift, hd(TestAccounts.giftEscrowBytes(host, 2, Pubkey.DEFAULT), 600_000_000uL)) }
        rejects { HeadsDownAccounts.giftEscrow(gift, hd(TestAccounts.giftEscrowBytes(other, 2, host), 600_000_000uL)) }
        rejects { HeadsDownAccounts.giftEscrow(gift, hd(TestAccounts.giftEscrowBytes(host, 3, other), 600_000_000uL)) }
    }

    // ------------------------------------------------------------------------- SPL Token

    private fun spl(data: ByteArray) = TestAccounts.info(WellKnown.SPL_TOKEN, data)

    @Test
    fun `an SKR account is classic SPL Token, 165 bytes, initialized and not native`() {
        val bytes = TestAccounts.tokenAccountBytes(Skr.MINT, host, 1_000_000_000)
        val t = SplTokenAccounts.account(spl(bytes))
        assertEquals(TokenAccount(Skr.MINT, host, 1_000uL * Skr.ONE_SKR, hasDelegate = false, hasCloseAuthority = false), t)
        assertEquals(1_000uL * Skr.ONE_SKR, SplTokenAccounts.userBalance(spl(bytes), Skr.MINT, host))
        assertEquals(0uL, SplTokenAccounts.userBalance(null, Skr.MINT, host))
        // A Token-2022 look-alike, a frozen or uninitialized account, wrapped SOL, a wrong length.
        rejects { SplTokenAccounts.account(TestAccounts.info(WellKnown.TOKEN_2022, bytes)) }
        rejects { SplTokenAccounts.account(spl(TestAccounts.tokenAccountBytes(Skr.MINT, host, 1, state = 2))) }
        rejects { SplTokenAccounts.account(spl(TestAccounts.tokenAccountBytes(Skr.MINT, host, 1, state = 0))) }
        rejects { SplTokenAccounts.account(spl(bytes.copyOf().also { it[109] = 1 })) }
        rejects { SplTokenAccounts.account(spl(bytes + 0)) }
        rejects { SplTokenAccounts.account(spl(bytes.copyOf(164))) }
        // Someone else's account, or another mint's, is not this wallet's SKR balance.
        rejects { SplTokenAccounts.userBalance(spl(bytes), Skr.MINT, other) }
        rejects { SplTokenAccounts.userBalance(spl(bytes), Ore.MINT, host) }
        val flagged = SplTokenAccounts.account(spl(bytes.copyOf().also { it[72] = 1; it[129] = 1 }))
        assertTrue(flagged.hasDelegate && flagged.hasCloseAuthority)
    }

    @Test
    fun `the SKR and ORE mints are pinned by address, program and decimals`() {
        fun mint(decimals: Int, freeze: Boolean = false, supply: Long = 10_000_000_000L) = ByteBuffer.allocate(82).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0, 1); position(4); put(other.bytes)
            putLong(36, supply)
            put(44, decimals.toByte()); put(45, 1)
            if (freeze) {
                putInt(46, 1); position(50); put(other.bytes)
            }
        }.array()
        val skr = SplTokenAccounts.skrMint(Skr.MINT, spl(mint(6)))
        assertEquals(TokenMint(other, 10_000_000_000uL, 6, null), skr)
        assertEquals(11, SplTokenAccounts.oreMint(Ore.MINT, spl(mint(11))).decimals)
        rejects { SplTokenAccounts.skrMint(Ore.MINT, spl(mint(6))) }
        rejects { SplTokenAccounts.skrMint(Skr.MINT, spl(mint(9))) }
        rejects { SplTokenAccounts.skrMint(Skr.MINT, spl(mint(6, freeze = true))) }
        rejects { SplTokenAccounts.skrMint(Skr.MINT, TestAccounts.info(WellKnown.TOKEN_2022, mint(6))) }
        rejects { SplTokenAccounts.oreMint(Ore.MINT, spl(mint(6))) }
        rejects { SplTokenAccounts.mint(spl(mint(6).copyOf().also { it[45] = 0 })) }
        rejects { SplTokenAccounts.mint(spl(mint(6).copyOf().also { it[0] = 2 })) }
        rejects { SplTokenAccounts.mint(spl(mint(6).copyOf(81))) }
    }

    // --------------------------------------------------------------------------- ORE claims

    private fun treasury(factorBits: Long = 0, totalUnrefined: Long = 0) =
        OreAccounts.treasury(Ore.TREASURY, TestAccounts.info(Ore.PROGRAM_ID, TestAccounts.treasuryBytes(34_400_000_000_000, factorBits, 5, totalUnrefined)))

    private fun miner(refined: Long = 0, unrefined: Long = 0, factorBits: Long = 0, sol: Long = 0) =
        OreAccounts.miner(Ore.miner(host).address, TestAccounts.info(Ore.PROGRAM_ID, TestAccounts.minerBytes(host, sol, refined, unrefined, factorBits)))

    @Test
    fun `the real mainnet Treasury and Miner decode their claim fields`() = runBlocking {
        val t = OreAccounts.treasury(Ore.TREASURY, Fixtures.account("ore_treasury"))
        assertEquals(36_020_000_000_000uL, t.motherlode)
        // miner_rewards_factor is a small positive I80F48; the totals are ORE atoms.
        assertTrue(t.minerRewardsFactorBits.signum() > 0)
        assertTrue(t.minerRewardsFactorBits < BigInteger.ONE.shiftLeft(48)) // below 1.0 ORE per ORE
        assertTrue(t.totalRefined > 0uL && t.totalUnrefined > t.totalRefined)
        val m = OreAccounts.miner(Fixtures.address("ore_miner"), Fixtures.account("ore_miner"))
        assertTrue(m.rewardsFactorBits.signum() >= 0 && m.rewardsFactorBits <= t.minerRewardsFactorBits)
        // What a full claim would do for that miner today, by ORE's own arithmetic.
        val e = OreClaimMath.estimate(m, t, 10_000)
        assertEquals(m.rewardsOre, e.unrefined)
        assertEquals(if (m.rewardsOre > 0uL) maxOf(1uL, m.rewardsOre / 10uL) else 0uL, e.fee)
        assertTrue(e.refined >= m.refinedOre)
        assertEquals(e.refined + e.unrefined - e.fee, e.received)
    }

    @Test
    fun `claim arithmetic is ORE's - a share of both balances and 10 percent of the unrefined part`() {
        val t = treasury(totalUnrefined = 10_000_000_000)
        val m = miner(refined = 1_000, unrefined = 20_000_000)
        // Everything: 1,000 refined + 20,000,000 unrefined - 2,000,000 fee.
        assertEquals(OreClaimEstimate(1_000uL, 20_000_000uL, 2_000_000uL), OreClaimMath.estimate(m, t, 10_000))
        assertEquals(18_001_000uL, OreClaimMath.estimate(m, t, 10_000).received)
        // A quarter (2,500 bps): floors, like ORE.
        assertEquals(OreClaimEstimate(250uL, 5_000_000uL, 500_000uL), OreClaimMath.estimate(m, t, 2_500))
        // 0 bps moves nothing (the phone sends no instruction then).
        assertEquals(OreClaimEstimate(0uL, 0uL, 0uL), OreClaimMath.estimate(m, t, 0))
        // The fee is at least one atom.
        assertEquals(1uL, OreClaimMath.estimate(miner(unrefined = 5), t, 10_000).fee)
        // Refined-only claims cost nothing.
        assertEquals(OreClaimEstimate(777uL, 0uL, 0uL), OreClaimMath.estimate(miner(refined = 777), t, 10_000))
        // The last holder of unrefined ORE has nobody to pay the fee to: ORE charges none.
        val last = treasury(totalUnrefined = 20_000_000)
        assertEquals(0uL, OreClaimMath.estimate(m, last, 10_000).fee)
        assertThrows(IllegalArgumentException::class.java) { OreClaimMath.estimate(m, t, 10_001) }
    }

    @Test
    fun `refining fees distributed since the Miner was last touched are counted`() {
        // The Treasury factor moved by 0.25 (I80F48: 0.25 * 2^48) since this Miner last updated:
        // 0.25 atoms of refined ORE per atom of unrefined ORE held.
        val quarter = 1L shl 46
        val m = miner(refined = 100, unrefined = 1_000, factorBits = 5)
        assertEquals(100uL + 250uL, OreClaimMath.refined(m, treasury(factorBits = 5 + quarter)))
        // Flooring: 0.25 * 1,003 = 250.75.
        assertEquals(250uL, OreClaimMath.refined(miner(unrefined = 1_003), treasury(factorBits = quarter)))
        // No movement, or a Treasury factor behind the Miner's (never written by ORE): unchanged.
        assertEquals(100uL, OreClaimMath.refined(m, treasury(factorBits = 5)))
        assertEquals(100uL, OreClaimMath.refined(m, treasury(factorBits = 1)))
        assertEquals(OreClaimEstimate(350uL, 1_000uL, 100uL), OreClaimMath.estimate(m, treasury(5 + quarter, totalUnrefined = 9_999), 10_000))
    }
}
