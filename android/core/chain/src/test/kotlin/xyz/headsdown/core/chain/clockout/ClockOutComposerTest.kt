package xyz.headsdown.core.chain.clockout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.headsdown.core.chain.HeadsDownProgram
import xyz.headsdown.core.chain.Ore
import xyz.headsdown.core.chain.Pubkey
import xyz.headsdown.core.chain.Skr
import xyz.headsdown.core.chain.TestAccounts
import xyz.headsdown.core.chain.WellKnown
import xyz.headsdown.core.chain.accounts.FocusBondAccount
import xyz.headsdown.core.chain.accounts.HeadsDownAccounts
import xyz.headsdown.core.chain.accounts.OreAccounts
import xyz.headsdown.core.chain.accounts.OreClaimEstimate
import xyz.headsdown.core.chain.accounts.RigAccount
import xyz.headsdown.core.chain.accounts.ShiftLogAccount
import xyz.headsdown.core.chain.hexBytes
import xyz.headsdown.core.chain.ix.AssociatedTokenInstructions
import xyz.headsdown.core.chain.ix.HeadsDownInstructions
import xyz.headsdown.core.chain.ix.OreInstructions
import xyz.headsdown.core.chain.ix.SkrInstructions
import xyz.headsdown.core.chain.tx.Instruction
import xyz.headsdown.core.keys.RigSignalState
import xyz.headsdown.core.keys.ShiftEndReason

/**
 * Every decision of the clock-out, without a network: when the shift is ended and how it will
 * seal, what happens to a Focus Bond, and which ORE claims are sent. The seal prediction mirrors
 * `programs/heads-down/program/src/instructions/end_shift.rs`.
 */
class ClockOutComposerTest {

    private val authority = Pubkey.fromBase58("7xKXtg2CW87d97TXJSDpbD5jBkheTqA83TZRuJosgAsU")
    private val rigAddress = HeadsDownProgram.rig(authority).address
    private val key = hexBytes("0360fed4ba255a9d31c961eb74c6356d68c049b8923b61fa6ce669622e60f29fb6")
    private val windowEnd = 1_790_028_800L
    private val afterWindow = windowEnd + ShiftSeal.CLOCK_MARGIN_SECONDS + 1
    private val insideWindow = windowEnd - 3_600
    private val round = 422_900L

    private fun rig(
        state: RigSignalState = RigSignalState.DOWN,
        shiftOpen: Boolean = true,
        breakReason: Int = 0,
        dark: Long = 250,
        leaseTo: Long = round - 1,
        shiftId: Long = 7,
    ): RigAccount = HeadsDownAccounts.rig(
        rigAddress,
        TestAccounts.info(
            HeadsDownProgram.ID,
            TestAccounts.rigBytesFull(
                authority, key, state, shiftId, shiftOpen, breakReason,
                planWindowEndTs = windowEnd, leaseToRound = leaseTo, shiftStartRound = 422_600, shiftDarkRounds = dark, shiftStartTs = windowEnd - 28_800,
            ),
        ),
    )

    private val board = OreAccounts.board(Ore.BOARD, TestAccounts.info(Ore.PROGRAM_ID, TestAccounts.boardBytes(round)))
    private val treasury = OreAccounts.treasury(Ore.TREASURY, TestAccounts.info(Ore.PROGRAM_ID, TestAccounts.treasuryBytes(totalUnrefined = 9_000_000_000_000)))

    private fun miner(sol: Long = 0, refined: Long = 0, unrefined: Long = 0, owner: Pubkey = authority) =
        OreAccounts.miner(Ore.miner(owner).address, TestAccounts.info(Ore.PROGRAM_ID, TestAccounts.minerBytes(owner, sol, refined, unrefined)))

    private fun bond(shiftId: Long = 7, amount: Long = 100_000_000, owner: Pubkey = authority): FocusBondAccount {
        val r = HeadsDownProgram.rig(owner).address
        return HeadsDownAccounts.focusBond(
            HeadsDownProgram.focusBond(r, shiftId.toULong()).address,
            TestAccounts.info(HeadsDownProgram.ID, TestAccounts.focusBondBytes(r, owner, shiftId, amount, shiftStartRound = 422_600, shiftStartTs = windowEnd - 28_800)),
        )
    }

    private fun log(reason: Int, shiftId: Long = 7, startRound: Long = 422_600): ShiftLogAccount = HeadsDownAccounts.shiftLog(
        HeadsDownProgram.shiftLog(rigAddress, shiftId.toULong()).address,
        TestAccounts.info(HeadsDownProgram.ID, TestAccounts.shiftLogBytes(rigAddress, shiftId, reason, startRound = startRound, startTs = windowEnd - 28_800)),
    )

    private fun state(
        rig: RigAccount? = rig(),
        miner: xyz.headsdown.core.chain.accounts.OreMiner? = null,
        bond: FocusBondAccount? = null,
        log: ShiftLogAccount? = null,
        skrAccount: Boolean = true,
    ) = ClockOutChainState(rig, board, miner, treasury, bond, log, skrAccount)

    private fun compose(state: ClockOutChainState, request: ClockOutRequest = ClockOutRequest(), now: Long = afterWindow) =
        ClockOutComposer.compose(authority, request, state, now)

    private fun List<Instruction>.tags(): List<String> = map {
        when (it.programId) {
            Ore.PROGRAM_ID -> "ore:${it.data[0]}"
            WellKnown.ASSOCIATED_TOKEN -> "ata"
            else -> "hd:${it.data[0]}"
        }
    }

    private val endShift = HeadsDownInstructions.endShift(authority, rigAddress, 7uL)

    // ------------------------------------------------------------------------- the shift

    @Test
    fun `nothing open and nothing to claim is nothing to sign`() {
        for (s in listOf(state(rig = null), state(rig = rig(state = RigSignalState.IDLE, shiftOpen = false)), state(miner = null, rig = null))) {
            val plan = compose(s)
            assertTrue(plan.isEmpty)
            assertEquals(ShiftOutcome.NoOpenShift, plan.shift)
            assertEquals(BondOutcome.None, plan.bond)
            assertEquals(0uL, plan.claimedSolLamports)
            assertNull(plan.claimedOre)
        }
    }

    @Test
    fun `a shift past its window is ended and seals completed`() {
        val plan = compose(state())
        assertEquals(listOf(endShift), plan.instructions)
        assertEquals(ShiftOutcome.Ends(ShiftEndReason.COMPLETED), plan.shift)
        assertTrue((plan.shift as ShiftOutcome.Ends).completed)
        // Armed with dark rounds (the lease lapsed, the state never moved back) is the same.
        assertEquals(ShiftOutcome.Ends(ShiftEndReason.COMPLETED), compose(state(rig(state = RigSignalState.ARMED))).shift)
    }

    @Test
    fun `a shift inside its window is left open unless the user asks`() {
        val plan = compose(state(), now = insideWindow)
        assertTrue(plan.isEmpty)
        // Ending it now would seal it manual: no streak. So it is left for after the window.
        assertEquals(ShiftOutcome.LeftOpen(ShiftEndReason.MANUAL, windowEnd), plan.shift)
        val early = compose(state(), ClockOutRequest(endShiftEarly = true), now = insideWindow)
        assertEquals(listOf(endShift), early.instructions)
        assertEquals(ShiftOutcome.Ends(ShiftEndReason.MANUAL), early.shift)
    }

    @Test
    fun `the phone's clock gets no benefit of the doubt at the window's edge`() {
        // The program compares the cluster's clock, which can lag the phone's: until 90 s past the
        // window's end the shift still counts as inside it.
        for (now in listOf(windowEnd, windowEnd + 1, windowEnd + ShiftSeal.CLOCK_MARGIN_SECONDS)) {
            assertEquals("$now", ShiftOutcome.LeftOpen(ShiftEndReason.MANUAL, windowEnd), compose(state(), now = now).shift)
        }
        assertEquals(ShiftOutcome.Ends(ShiftEndReason.COMPLETED), compose(state(), now = windowEnd + ShiftSeal.CLOCK_MARGIN_SECONDS + 1).shift)
    }

    @Test
    fun `no dark round seals lease_lapse, counting only rounds up to the end round`() {
        assertEquals(ShiftOutcome.Ends(ShiftEndReason.LEASE_LAPSE), compose(state(rig(dark = 0))).shift)
        // A 3-round lease granted at the live round covers two rounds that have not happened:
        // end_shift takes them back (dark -= lease_to - end_round).
        assertEquals(1uL, ShiftSeal.settledDarkRounds(rig(dark = 3, leaseTo = round + 2), round.toULong()))
        assertEquals(ShiftOutcome.Ends(ShiftEndReason.LEASE_LAPSE), compose(state(rig(dark = 2, leaseTo = round + 2))).shift)
        assertEquals(ShiftOutcome.Ends(ShiftEndReason.COMPLETED), compose(state(rig(dark = 3, leaseTo = round + 2))).shift)
        // Inside the window too: lease_lapse comes before manual in the program's order.
        assertEquals(ShiftOutcome.LeftOpen(ShiftEndReason.LEASE_LAPSE, windowEnd), compose(state(rig(dark = 0)), now = insideWindow).shift)
    }

    @Test
    fun `a broken or frozen shift is ended at once, a cooling one only after its window`() {
        // Broken is final until end_shift: ending it changes nothing, whenever.
        val broken = rig(state = RigSignalState.BROKEN, breakReason = 8)
        assertEquals(ShiftOutcome.Ends(ShiftEndReason.UNLOCKED), compose(state(broken), now = insideWindow).shift)
        assertEquals(ShiftOutcome.Ends(ShiftEndReason.FREEZE), compose(state(rig(state = RigSignalState.FROZEN, breakReason = 3)), now = insideWindow).shift)
        // Cooling can still resume (a fresh heartbeat makes it Down again): left open inside the window.
        val cooling = rig(state = RigSignalState.COOLING, breakReason = 1)
        assertEquals(ShiftOutcome.LeftOpen(ShiftEndReason.PICKUP, windowEnd), compose(state(cooling), now = insideWindow).shift)
        assertEquals(ShiftOutcome.Ends(ShiftEndReason.PICKUP), compose(state(cooling)).shift)
        assertEquals(ShiftOutcome.Ends(ShiftEndReason.PICKUP), compose(state(cooling), ClockOutRequest(endShiftEarly = true), now = insideWindow).shift)
    }

    @Test
    fun `another wallet's rig is refused`() {
        val other = Pubkey.fromBase58("9qQk3i73uAiWs1Wv7jDj4diVmwcbCa1FpokjPz7y5MwQ")
        val e = assertThrows(ClockOutRefusedException::class.java) { ClockOutComposer.compose(other, ClockOutRequest(), state(), afterWindow) }
        assertEquals(ClockOutRefusedException.Reason.OTHER_AUTHORITY, e.reason)
    }

    // ------------------------------------------------------------------------ the Focus Bond

    @Test
    fun `a completed shift releases its bond in the same transaction, right after end_shift`() {
        val plan = compose(state(bond = bond()))
        assertEquals(listOf(endShift, SkrInstructions.releaseFocusBond(authority, 7uL)), plan.instructions)
        assertEquals(BondOutcome.Released(100uL * Skr.ONE_SKR), plan.bond)
        // The wallet's SKR account was closed meanwhile: it is created first.
        val recreated = compose(state(bond = bond(), skrAccount = false))
        assertEquals(listOf("hd:11", "ata", "hd:21"), recreated.instructions.tags())
        assertEquals(AssociatedTokenInstructions.createIdempotent(authority, authority, Skr.MINT), recreated.instructions[1])
    }

    @Test
    fun `inside the window the bond stays locked, and ending early needs an explicit yes`() {
        val waiting = compose(state(bond = bond()), now = insideWindow)
        assertTrue(waiting.isEmpty)
        assertEquals(BondOutcome.StaysLocked(100uL * Skr.ONE_SKR), waiting.bond)
        // The user's own choice to end early forfeits the bond: never without their say-so.
        val e = assertThrows(ClockOutRefusedException::class.java) { compose(state(bond = bond()), ClockOutRequest(endShiftEarly = true), now = insideWindow) }
        assertEquals(ClockOutRefusedException.Reason.BOND_WOULD_FORFEIT, e.reason)
        val accepted = compose(state(bond = bond()), ClockOutRequest(endShiftEarly = true, acceptBondForfeit = true), now = insideWindow)
        assertEquals(listOf(endShift), accepted.instructions)
        assertEquals(BondOutcome.Forfeit(100uL * Skr.ONE_SKR, ShiftEndReason.MANUAL), accepted.bond)
        // Without a bond, ending early needs no such confirmation.
        assertEquals(BondOutcome.None, compose(state(), ClockOutRequest(endShiftEarly = true), now = insideWindow).bond)
    }

    @Test
    fun `a bond already lost to a break is reported, not refused, and nothing is sent for it`() {
        // The shift broke (unlocked): its outcome is fixed, so ending it is not the user's choice.
        val broken = rig(state = RigSignalState.BROKEN, breakReason = 8)
        val plan = compose(state(broken, bond = bond()), now = insideWindow)
        assertEquals(listOf(endShift), plan.instructions)
        assertEquals(BondOutcome.Forfeit(100uL * Skr.ONE_SKR, ShiftEndReason.UNLOCKED), plan.bond)
        // A pickup that never resumed, after the window.
        val cooled = compose(state(rig(state = RigSignalState.COOLING, breakReason = 1), bond = bond()))
        assertEquals(BondOutcome.Forfeit(100uL * Skr.ONE_SKR, ShiftEndReason.PICKUP), cooled.bond)
        assertEquals(listOf("hd:11"), cooled.instructions.tags())
        // No dark round at all.
        assertEquals(BondOutcome.Forfeit(100uL * Skr.ONE_SKR, ShiftEndReason.LEASE_LAPSE), compose(state(rig(dark = 0), bond = bond())).bond)
    }

    @Test
    fun `a bond whose shift is already sealed resolves from its ShiftLog`() {
        val idle = rig(state = RigSignalState.IDLE, shiftOpen = false)
        // Sealed completed (a permissionless end_shift after the window): only the release is sent.
        val released = compose(state(idle, bond = bond(), log = log(reason = 0)))
        assertEquals(listOf(SkrInstructions.releaseFocusBond(authority, 7uL)), released.instructions)
        assertEquals(BondOutcome.Released(100uL * Skr.ONE_SKR), released.bond)
        assertEquals(ShiftOutcome.NoOpenShift, released.shift)
        // Sealed with any other reason: forfeit, nothing to send from the phone.
        val forfeit = compose(state(idle, bond = bond(), log = log(reason = 6)))
        assertTrue(forfeit.isEmpty)
        assertEquals(BondOutcome.Forfeit(100uL * Skr.ONE_SKR, ShiftEndReason.MANUAL), forfeit.bond)
        // The slot holds a later incarnation's log: the bonded shift can never be sealed (abandoned).
        val abandoned = compose(state(idle, bond = bond(), log = log(reason = 0, startRound = 500_000)))
        assertTrue(abandoned.isEmpty)
        assertEquals(BondOutcome.Forfeit(100uL * Skr.ONE_SKR, null), abandoned.bond)
    }

    @Test
    fun `someone else's bond is never touched`() {
        val other = Pubkey.fromBase58("9qQk3i73uAiWs1Wv7jDj4diVmwcbCa1FpokjPz7y5MwQ")
        val plan = compose(state(bond = bond(owner = other)))
        assertEquals(BondOutcome.None, plan.bond)
        assertEquals(listOf(endShift), plan.instructions)
    }

    // --------------------------------------------------------------------------- ORE claims

    @Test
    fun `the default keeps ORE unrefined - no claim_ore, no fee`() {
        val plan = compose(state(rig = null, miner = miner(refined = 1_000, unrefined = 20_000_000)))
        assertTrue(plan.isEmpty)
        assertNull(plan.claimedOre)
        assertEquals(0, ClockOutRequest().claimOreBps)
    }

    @Test
    fun `claiming sends claim_ore with the share and reports the refining fee`() {
        val m = miner(refined = 1_000, unrefined = 20_000_000)
        val all = compose(state(rig = null, miner = m), ClockOutRequest(claimOreBps = 10_000))
        assertEquals(listOf(OreInstructions.claimOre(authority, 10_000)), all.instructions)
        assertEquals(OreClaimEstimate(1_000uL, 20_000_000uL, 2_000_000uL), all.claimedOre)
        assertEquals(18_001_000uL, all.claimedOre!!.received)
        val quarter = compose(state(rig = null, miner = m), ClockOutRequest(claimOreBps = 2_500))
        assertEquals(listOf(OreInstructions.claimOre(authority, 2_500)), quarter.instructions)
        assertEquals(OreClaimEstimate(250uL, 5_000_000uL, 500_000uL), quarter.claimedOre)
        // Nothing in the Miner: no instruction, even when asked.
        assertTrue(compose(state(rig = null, miner = miner()), ClockOutRequest(claimOreBps = 10_000)).isEmpty)
        assertTrue(compose(state(rig = null, miner = null), ClockOutRequest(claimOreBps = 10_000)).isEmpty)
        assertThrows(IllegalArgumentException::class.java) { ClockOutRequest(claimOreBps = 10_001) }
        assertThrows(IllegalArgumentException::class.java) { ClockOutRequest(claimOreBps = -1) }
    }

    @Test
    fun `returned SOL is claimed whenever the Miner holds any`() {
        val plan = compose(state(rig = null, miner = miner(sol = 1_234_567)))
        assertEquals(listOf(OreInstructions.claimSol(authority)), plan.instructions)
        assertEquals(1_234_567uL, plan.claimedSolLamports)
        // A Miner that is not this wallet's is ignored.
        val other = Pubkey.fromBase58("9qQk3i73uAiWs1Wv7jDj4diVmwcbCa1FpokjPz7y5MwQ")
        assertTrue(compose(state(rig = null, miner = miner(sol = 5, owner = other))).isEmpty)
    }

    @Test
    fun `everything together is one ordered list - end, release, SOL, ORE`() {
        val plan = compose(
            state(miner = miner(sol = 9, refined = 5, unrefined = 50), bond = bond()),
            ClockOutRequest(claimOreBps = 10_000),
        )
        assertEquals(listOf("hd:11", "hd:21", "ore:3", "ore:4"), plan.instructions.tags())
    }

    @Test
    fun `a buy leg is validated before anything is fetched`() {
        assertThrows(IllegalArgumentException::class.java) { BuyLeg(0uL, 1uL, 50) }
        assertThrows(IllegalArgumentException::class.java) { BuyLeg(1uL, 0uL, 50) }
        assertThrows(IllegalArgumentException::class.java) { BuyLeg(1uL, 1uL, 0) }
        assertThrows(IllegalArgumentException::class.java) { BuyLeg(1uL, 1uL, 301) }
    }

    @Test
    fun `the seal prediction is the program's rule table`() {
        fun predict(r: RigAccount, now: Long = afterWindow) = ShiftSeal.predict(r, round.toULong(), now)
        // Cooling / Broken: the stored reason, whatever the clock says.
        for (reason in listOf(1, 2, 4, 5, 6, 7, 8)) {
            assertEquals(ShiftEndReason.fromWire(reason), predict(rig(state = RigSignalState.BROKEN, breakReason = reason)))
            assertEquals(ShiftEndReason.fromWire(reason), predict(rig(state = RigSignalState.COOLING, breakReason = reason), insideWindow))
        }
        assertEquals(ShiftEndReason.FREEZE, predict(rig(state = RigSignalState.FROZEN)))
        assertEquals(ShiftEndReason.LEASE_LAPSE, predict(rig(dark = 0)))
        assertEquals(ShiftEndReason.MANUAL, predict(rig(), insideWindow))
        assertEquals(ShiftEndReason.COMPLETED, predict(rig()))
    }
}
