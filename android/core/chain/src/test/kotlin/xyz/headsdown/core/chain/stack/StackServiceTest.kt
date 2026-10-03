package xyz.headsdown.core.chain.stack

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.headsdown.core.chain.ChainServer
import xyz.headsdown.core.chain.DecodedInstruction
import xyz.headsdown.core.chain.FakeChain
import xyz.headsdown.core.chain.HeadsDownProgram
import xyz.headsdown.core.chain.Ore
import xyz.headsdown.core.chain.Pubkey
import xyz.headsdown.core.chain.Skr
import xyz.headsdown.core.chain.TestAccounts
import xyz.headsdown.core.chain.TxInspect
import xyz.headsdown.core.chain.WellKnown
import xyz.headsdown.core.chain.accounts.SgtFixtures
import xyz.headsdown.core.chain.gift.SkrFixture
import xyz.headsdown.core.chain.hexBytes
import xyz.headsdown.core.chain.ix.AssociatedTokenInstructions
import xyz.headsdown.core.chain.ix.HeadsDownInstructions
import xyz.headsdown.core.chain.ix.SgtAccounts
import xyz.headsdown.core.chain.ix.SkrInstructions
import xyz.headsdown.core.chain.ix.StackParams
import xyz.headsdown.core.chain.tx.Instruction
import xyz.headsdown.core.chain.tx.TransactionBuilder
import xyz.headsdown.core.keys.RigSignalState
import xyz.headsdown.core.wallet.WalletCapabilities

/**
 * Stack on an in-memory cluster: opening a table with the host's seat, the join rules the
 * program enforces (refused on the phone first, with a reason), the Seeker verification that
 * goes in front of a join when it is needed, the live read and the claim.
 */
class StackServiceTest {

    /** A wallet with a guest rig and no Seeker Genesis Token. */
    private val guest = Pubkey.fromBase58("7xKXtg2CW87d97TXJSDpbD5jBkheTqA83TZRuJosgAsU")
    private val other = Pubkey.fromBase58("9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM")

    /** A real mainnet wallet holding Seeker Genesis Token #33078 (`fixtures/skr_miner.json`). */
    private val seeker = SkrFixture.owner
    private val sgtMint = Pubkey.fromBase58(SkrFixture.SGT_MINT)
    private val sgtToken = Pubkey.fromBase58(SkrFixture.SGT_TOKEN_ACCOUNT)

    private val key = hexBytes("0360fed4ba255a9d31c961eb74c6356d68c049b8923b61fa6ce669622e60f29fb6")
    private val now = 1_790_000_000L
    private val round = 422_900L
    private val v0 = WalletCapabilities(supportsLegacy = true, supportsV0 = true, maxTransactionsPerRequest = 0)
    private val skr = Skr.ONE_SKR.toLong()

    private val servers = mutableListOf<AutoCloseable>()

    @After
    fun close() = servers.forEach { it.close() }

    private fun chain(): FakeChain = SkrFixture.load(FakeChain()).apply {
        put(Ore.BOARD, Ore.PROGRAM_ID, TestAccounts.boardBytes(round))
        for (w in listOf(guest, other, seeker)) wallet(w, 1_000_000_000)
    }

    private fun FakeChain.rig(authority: Pubkey, tier: Int = 0, sgt: Pubkey? = null, level: Int = 0, expiry: Long = 0) = put(
        HeadsDownProgram.rig(authority).address, HeadsDownProgram.ID,
        TestAccounts.rigBytesFull(authority, key, RigSignalState.IDLE, 3, false, tier = tier, sgtMint = sgt, attestationLevel = level, attestationExpirySlot = expiry),
    )

    private fun FakeChain.skr(authority: Pubkey, wholeSkr: Long) =
        put(Skr.account(authority), WellKnown.SPL_TOKEN, TestAccounts.tokenAccountBytes(Skr.MINT, authority, wholeSkr * skr))

    private fun service(chain: FakeChain, tableId: ULong = 77uL) = StackService(chain.rpc(), nowUnix = { now }, newTableId = { tableId })

    private fun assertInstruction(expected: Instruction, actual: DecodedInstruction) {
        assertEquals(expected.programId, actual.program)
        assertEquals(expected.accounts.map { it.pubkey }, actual.accounts)
        assertArrayEquals(expected.data, actual.data)
    }

    private fun refusal(block: suspend () -> Unit): StackRefusedException.Reason {
        val e = runCatching { runBlocking { block() } }.exceptionOrNull()
        assertTrue("expected a refusal, got $e", e is StackRefusedException)
        return (e as StackRefusedException).reason
    }

    private val draft = StackDraft(bond = 200uL * Skr.ONE_SKR, startsInRounds = 15, rounds = 46, graceGaps = 3, maxSeats = 4)

    // ------------------------------------------------------------------------- opening

    @Test
    fun `opening a table creates its vault, opens it and seats the host, in one transaction`() = runBlocking {
        val chain = chain().apply { rig(guest); skr(guest, 300) }
        val prepared = service(chain).prepareOpen(guest, draft, v0)
        val tx = prepared.transactions.single()
        assertEquals(listOf("ata", "hd:15", "hd:16"), TxInspect.tags(tx))
        val table = HeadsDownProgram.stackTable(guest, 77uL).address
        assertEquals(table, prepared.table)
        val ixs = TxInspect.instructions(tx)
        // The window is counted from the live round: it opens 15 rounds on and lasts 46.
        val params = StackParams(77uL, 200uL * Skr.ONE_SKR, (round + 15).toULong(), (round + 15 + 45).toULong(), 3, 0, 4)
        assertInstruction(SkrInstructions.openStackVault(guest, 77uL), ixs[0])
        assertInstruction(SkrInstructions.openStack(guest, params), ixs[1])
        assertInstruction(SkrInstructions.joinStack(guest, table, remote = false), ixs[2])
        assertEquals(StackAction.OPEN_AND_JOIN, prepared.action)
        assertEquals(200uL * Skr.ONE_SKR, prepared.amount)
        assertFalse(prepared.verifiesSeeker)
        assertTrue(tx.size <= TransactionBuilder.PACKET_DATA_SIZE)
        assertEquals(listOf("getMultipleAccounts", "getLatestBlockhash"), chain.methods)
        // A guest may bond exactly the guest cap, and a legacy-only wallet gets a legacy transaction.
        val capped = service(chain.apply { skr(guest, 500) }).prepareOpen(guest, draft.copy(bond = Skr.GUEST_BOND_CAP), WalletCapabilities.LEGACY_ONLY)
        assertEquals(listOf("ata", "hd:15", "hd:16"), TxInspect.tags(capped.transactions.single()))
        assertEquals(0, capped.transactions.single()[65].toInt() and 0x80)
    }

    @Test
    fun `a table the program would refuse is refused before the wallet is asked`() {
        fun open(d: StackDraft, chain: FakeChain = chain().apply { rig(guest); skr(guest, 5_000) }) = refusal { service(chain).prepareOpen(guest, d, v0) }
        assertEquals(StackRefusedException.Reason.NO_RIG, open(draft, chain().apply { skr(guest, 300) }))
        assertEquals(StackRefusedException.Reason.INSUFFICIENT_SKR, open(draft, chain().apply { rig(guest); skr(guest, 199) }))
        assertEquals(StackRefusedException.Reason.INSUFFICIENT_SKR, open(draft, chain().apply { rig(guest) }))
        // The window: at least three rounds ahead, at most 10,080; 1 to 1,440 rounds long.
        assertEquals(StackRefusedException.Reason.BAD_WINDOW, open(draft.copy(startsInRounds = 2)))
        assertEquals(StackRefusedException.Reason.BAD_WINDOW, open(draft.copy(startsInRounds = 10_081)))
        assertEquals(StackRefusedException.Reason.BAD_WINDOW, open(draft.copy(rounds = 0)))
        assertEquals(StackRefusedException.Reason.BAD_TABLE, open(draft.copy(rounds = 1_441)))
        // Grace below the window length, 2 to 8 seats, a bond inside the cap.
        assertEquals(StackRefusedException.Reason.BAD_TABLE, open(draft.copy(graceGaps = 46)))
        assertEquals(StackRefusedException.Reason.BAD_TABLE, open(draft.copy(maxSeats = 9)))
        assertEquals(StackRefusedException.Reason.BAD_TABLE, open(draft.copy(maxSeats = 1)))
        assertEquals(StackRefusedException.Reason.BAD_TABLE, open(draft.copy(bond = 0uL)))
        assertEquals(StackRefusedException.Reason.BAD_TABLE, open(draft.copy(bond = Skr.STACK_BOND_CAP + 1uL)))
        assertEquals(StackRefusedException.Reason.BAD_TABLE, open(draft.copy(bond = Skr.REMOTE_BOND_CAP + 1uL, remote = true)))
        // A guest above 500 SKR, and a guest at a remote table.
        assertEquals(StackRefusedException.Reason.NEEDS_SEEKER_BOND, open(draft.copy(bond = Skr.GUEST_BOND_CAP + 1uL)))
        assertEquals(StackRefusedException.Reason.NEEDS_ATTESTED_KEY, open(draft.copy(remote = true)))
        val attestedGuest = chain().apply { rig(guest, level = 1, expiry = 460_000_000); skr(guest, 300) }
        assertEquals(StackRefusedException.Reason.NEEDS_SEEKER_REMOTE, open(draft.copy(remote = true), attestedGuest))
        // An attestation that has run out is none (expiry_slot must be above the cluster's slot).
        val lapsed = chain().apply { rig(seeker, level = 2, expiry = 451_700_010); skr(seeker, 300) }
        assertEquals(StackRefusedException.Reason.NEEDS_ATTESTED_KEY, refusal { service(lapsed).prepareOpen(seeker, draft.copy(remote = true), v0) })
    }

    @Test
    fun `a Seeker with a guest rig is verified in the same transaction, then seated by its token`() = runBlocking {
        val chain = chain().apply { rig(seeker, level = 2, expiry = 460_000_000); skr(seeker, 1_000) }
        val remote = draft.copy(bond = Skr.REMOTE_BOND_CAP, remote = true, maxSeats = 2)
        val prepared = service(chain).prepareOpen(seeker, remote, v0)
        val tx = prepared.transactions.single()
        assertEquals(listOf("ata", "hd:15", "hd:2", "hd:16"), TxInspect.tags(tx))
        assertTrue(prepared.verifiesSeeker)
        val table = HeadsDownProgram.stackTable(seeker, 77uL).address
        val ixs = TxInspect.instructions(tx)
        assertInstruction(HeadsDownInstructions.verifySeeker(seeker, sgtMint, sgtToken), ixs[2])
        assertInstruction(SkrInstructions.joinStack(seeker, table, remote = true, sgt = SgtAccounts(sgtToken, sgtMint)), ixs[3])
        // The remote seat is keyed by the token's mint: one seat per Seeker.
        assertEquals(HeadsDownProgram.stackSeat(table, sgtMint).address, ixs[3].accounts[3])
        assertEquals(1, ixs[1].data[37].toInt()) // flags: REMOTE (the program adds ATTESTED_ONLY)
        assertTrue(tx.size <= TransactionBuilder.PACKET_DATA_SIZE)

        // The token was verified for another rig before: that rig is passed so the seat can move.
        val elsewhere = HeadsDownProgram.rig(other).address
        chain.put(HeadsDownProgram.seekerSeat(sgtMint).address, HeadsDownProgram.ID, TestAccounts.seekerSeatBytes(sgtMint, elsewhere, other))
        val moved = TxInspect.instructions(service(chain).prepareOpen(seeker, remote, v0).transactions.single())
        assertInstruction(HeadsDownInstructions.verifySeeker(seeker, sgtMint, sgtToken, previousRig = elsewhere), moved[2])
        assertEquals(7, moved[2].accounts.size)

        // Already verified with the token it still holds: nothing to verify again.
        chain.rig(seeker, tier = 1, sgt = sgtMint, level = 2, expiry = 460_000_000)
        chain.put(HeadsDownProgram.seekerSeat(sgtMint).address, HeadsDownProgram.ID, TestAccounts.seekerSeatBytes(sgtMint, HeadsDownProgram.rig(seeker).address, seeker))
        val verified = service(chain).prepareOpen(seeker, remote, v0)
        assertEquals(listOf("ata", "hd:15", "hd:16"), TxInspect.tags(verified.transactions.single()))
        assertFalse(verified.verifiesSeeker)

        // Verified with a token the wallet no longer holds: the one it holds now is verified first.
        chain.rig(seeker, tier = 1, sgt = SgtFixtures.member20.mint, level = 2, expiry = 460_000_000)
        assertEquals(listOf("ata", "hd:15", "hd:2", "hd:16"), TxInspect.tags(service(chain).prepareOpen(seeker, remote, v0).transactions.single()))

        // In person above the guest cap: the token is passed, but the seat stays keyed by the rig.
        chain.rig(seeker)
        chain.remove(HeadsDownProgram.seekerSeat(sgtMint).address)
        val big = TxInspect.instructions(service(chain).prepareOpen(seeker, draft.copy(bond = 600uL * Skr.ONE_SKR), v0).transactions.single())
        assertEquals(listOf(15, 2, 16), big.drop(1).map { it.tag })
        assertEquals(11, big[3].accounts.size)
        assertEquals(HeadsDownProgram.stackSeat(table, HeadsDownProgram.rig(seeker).address).address, big[3].accounts[3])
    }

    // ------------------------------------------------------------------------- joining

    private val tableId = 5L
    private val table = HeadsDownProgram.stackTable(guest, tableId.toULong()).address

    private fun FakeChain.table(
        flags: Int = 0,
        bond: Long = 200,
        status: Int = 0,
        seatCount: Int = 1,
        maxSeats: Int = 4,
        start: Long = round + 10,
        refundAfter: Long = now + 300_000,
        payoutsTotal: Long = 0,
    ) = put(
        table, HeadsDownProgram.ID,
        TestAccounts.stackTableBytes(
            guest, tableId, bond = bond * skr, startRound = start, endRound = start + 45, graceGaps = 3, flags = flags, maxSeats = maxSeats,
            status = status, seatCount = seatCount, totalBonds = bond * skr * seatCount, refundAfterTs = refundAfter, payoutsTotal = payoutsTotal,
        ),
    )

    private fun FakeChain.seat(authority: Pubkey, index: Int, checked: Long = 0, last: Long = 0, outcome: Int = 0, payout: Long = 0, of: Pubkey = table) {
        val rig = HeadsDownProgram.rig(authority).address
        put(
            HeadsDownProgram.stackSeat(of, rig).address, HeadsDownProgram.ID,
            TestAccounts.stackSeatBytes(of, rig, authority, checkedRounds = checked, lastRound = last, seatIndex = index, outcome = outcome, payout = payout),
        )
    }

    @Test
    fun `joining an in-person table is one instruction, with the table's bond`() = runBlocking {
        val chain = chain().apply { table(); seat(guest, 0); rig(other); skr(other, 250) }
        val check = service(chain).joinCheck(other, table) as JoinCheck.Eligible
        assertFalse(check.plan.needsSeeker)
        assertNull(check.plan.sgt)
        val prepared = service(chain).prepareJoin(other, table, v0)
        val tx = prepared.transactions.single()
        assertEquals(listOf("hd:16"), TxInspect.tags(tx))
        assertInstruction(SkrInstructions.joinStack(other, table, remote = false), TxInspect.instructions(tx).single())
        assertEquals(StackAction.JOIN, prepared.action)
        assertEquals(200uL * Skr.ONE_SKR, prepared.amount)
        assertEquals(table, prepared.table)
        assertEquals(other, prepared.authority)
    }

    @Test
    fun `a join the program would refuse is refused with the reason`() {
        fun join(who: Pubkey = other, setup: FakeChain.() -> Unit): StackRefusedException.Reason {
            val chain = chain().apply { rig(other); skr(other, 250); setup() }
            val reason = refusal { service(chain).prepareJoin(who, table, v0) }
            // The review screen's check says the same thing, without throwing.
            assertEquals(JoinCheck.Refused(reason), runBlocking { service(chain).joinCheck(who, table) })
            return reason
        }
        assertEquals(StackRefusedException.Reason.NOT_A_TABLE, join { })
        // Some other heads_down account at that address is not a table either.
        assertEquals(StackRefusedException.Reason.NOT_A_TABLE, join { put(table, HeadsDownProgram.ID, TestAccounts.rigBytes(guest, key)) })
        // The window started (Board.round_id >= start_round), or the table is no longer open.
        assertEquals(StackRefusedException.Reason.JOIN_CLOSED, join { table(start = round) })
        assertEquals(StackRefusedException.Reason.JOIN_CLOSED, join { table(start = round - 5) })
        assertEquals(StackRefusedException.Reason.JOIN_CLOSED, join { table(status = 1) })
        assertEquals(StackRefusedException.Reason.JOIN_CLOSED, join { table(status = 2) })
        assertEquals(StackRefusedException.Reason.TABLE_FULL, join { table(seatCount = 4) })
        assertEquals(StackRefusedException.Reason.ALREADY_SEATED, join { table(seatCount = 2); seat(other, 1) })
        assertEquals(StackRefusedException.Reason.NO_RIG, join(who = seeker) { table() })
        assertEquals(StackRefusedException.Reason.INSUFFICIENT_SKR, join { table(bond = 251) })
        assertEquals(StackRefusedException.Reason.NEEDS_SEEKER_BOND, join { table(bond = 501); skr(other, 600) })
        assertEquals(StackRefusedException.Reason.NEEDS_ATTESTED_KEY, join { table(flags = 4) })
        assertEquals(StackRefusedException.Reason.NEEDS_ATTESTED_KEY, join { table(flags = 5) })
        assertEquals(StackRefusedException.Reason.NEEDS_SEEKER_REMOTE, join { table(flags = 5); rig(other, level = 1, expiry = 460_000_000) })
    }

    @Test
    fun `a verified Seeker joins a remote table with its token, once`() = runBlocking {
        val chain = chain().apply {
            table(flags = 5)
            rig(seeker, tier = 1, sgt = sgtMint, level = 1, expiry = 460_000_000)
            skr(seeker, 200)
        }
        val check = service(chain).joinCheck(seeker, table) as JoinCheck.Eligible
        assertTrue(check.plan.needsSeeker)
        assertFalse(check.plan.verifiesSeeker)
        assertEquals(33_078uL, check.plan.sgt!!.memberNumber)
        val ix = TxInspect.instructions(service(chain).prepareJoin(seeker, table, v0).transactions.single()).single()
        assertInstruction(SkrInstructions.joinStack(seeker, table, remote = true, sgt = SgtAccounts(sgtToken, sgtMint)), ix)
        // That Seeker's seat exists: a second one is refused, whichever rig asks.
        val seat = HeadsDownProgram.stackSeat(table, sgtMint).address
        chain.put(seat, HeadsDownProgram.ID, TestAccounts.stackSeatBytes(table, HeadsDownProgram.rig(seeker).address, seeker, sgtMint = sgtMint, keyedBySgt = true))
        assertEquals(StackRefusedException.Reason.ALREADY_SEATED, refusal { service(chain).prepareJoin(seeker, table, v0) })
    }

    // ------------------------------------------------------------------------- reading

    @Test
    fun `a table is read with its own seats only, in join order`() = runBlocking {
        val elsewhere = HeadsDownProgram.stackTable(other, 9uL).address
        val chain = chain().apply {
            table(seatCount = 3, start = round - 5)
            seat(seeker, 2, checked = 1, last = round - 4)
            seat(guest, 0, checked = 6, last = round)
            seat(other, 1, checked = 4, last = round - 1)
            seat(guest, 0, of = elsewhere) // a seat at another table
        }
        val view = service(chain).table(table)!!
        assertEquals(StackPhase.RUNNING, view.phase)
        assertEquals(listOf(guest, other, seeker), view.seats.map { it.seat.authority })
        assertEquals(listOf(SeatStanding.DARK, SeatStanding.DARK, SeatStanding.OUT_OF_GRACE), view.seats.map { it.standing })
        assertEquals(0, view.seatsMissingFromRead)
        assertEquals(listOf("getMultipleAccounts", "getProgramAccounts"), chain.methods)
        // The seats are asked for by size and by this table's address at offset 8.
        val filters = chain.requests.last()["params"]!!.jsonArray[1].jsonObject["filters"]!!.jsonArray
        assertEquals("200", filters[0].jsonObject["dataSize"]!!.jsonPrimitive.content)
        assertEquals("8", filters[1].jsonObject["memcmp"]!!.jsonObject["offset"]!!.jsonPrimitive.content)
        assertEquals(table.toBase58(), filters[1].jsonObject["memcmp"]!!.jsonObject["bytes"]!!.jsonPrimitive.content)
        // No account, or an account that is not a table: no table.
        assertNull(service(chain).table(elsewhere))
        assertNull(service(chain).table(HeadsDownProgram.stackSeat(table, HeadsDownProgram.rig(guest).address).address))
        assertNull(service(chain).table(Ore.BOARD))
    }

    @Test
    fun `my tables are the ones I host and the ones I sit at`() = runBlocking {
        val mineToo = HeadsDownProgram.stackTable(other, 9uL).address
        val chain = chain().apply {
            table(seatCount = 2)
            seat(guest, 0)
            seat(other, 1)
            put(mineToo, HeadsDownProgram.ID, TestAccounts.stackTableBytes(other, 9, startRound = round + 100, endRound = round + 120, seatCount = 1, openedTs = now + 50))
            seat(other, 0, of = mineToo)
            // A table neither hosted nor joined by `other`.
            put(HeadsDownProgram.stackTable(seeker, 1uL).address, HeadsDownProgram.ID, TestAccounts.stackTableBytes(seeker, 1))
        }
        val listed = service(chain).mine(other)
        assertEquals(listOf(mineToo, table), listed.map { it.view.table.address }) // most recently opened first
        assertEquals(listOf(true, false), listed.map { it.hostedByMe })
        assertTrue(listed.all { it.mySeat!!.seat.authority == other })
        val hostOnly = service(chain).mine(guest)
        assertEquals(listOf(table), hostOnly.map { it.view.table.address })
        assertTrue(hostOnly.single().hostedByMe)
        assertTrue(service(chain).mine(seeker).single().mySeat == null)
        assertTrue(service(FakeChain().apply { put(Ore.BOARD, Ore.PROGRAM_ID, TestAccounts.boardBytes(round)) }).mine(guest).isEmpty())
    }

    // ------------------------------------------------------------------------- claiming

    @Test
    fun `a settled seat claims its payout, creating the SKR account only when something is paid`() = runBlocking {
        val chain = chain().apply {
            table(status = 1, seatCount = 2, start = round - 100, payoutsTotal = 360 * skr)
            seat(guest, 0, checked = 46, last = round - 55, outcome = 1, payout = 360 * skr)
            seat(other, 1, checked = 2, last = round - 98, outcome = 2)
            skr(guest, 0)
        }
        val seatOf = { who: Pubkey -> HeadsDownProgram.stackSeat(table, HeadsDownProgram.rig(who).address).address }
        val win = service(chain).prepareClaim(guest, table, guest, v0)
        assertEquals(listOf("hd:19"), TxInspect.tags(win.transactions.single()))
        assertInstruction(SkrInstructions.claimStack(table, seatOf(guest), guest), TxInspect.instructions(win.transactions.single()).single())
        assertEquals(StackAction.CLAIM, win.action)
        assertEquals(360uL * Skr.ONE_SKR, win.amount)

        // The winner closed its SKR account meanwhile: it is created first, paid by whoever claims.
        chain.remove(Skr.account(guest))
        val recreated = TxInspect.instructions(service(chain).prepareClaim(other, table, guest, v0).transactions.single())
        assertEquals(listOf(WellKnown.ASSOCIATED_TOKEN, HeadsDownProgram.ID), recreated.map { it.program })
        assertInstruction(AssociatedTokenInstructions.createIdempotent(other, guest, Skr.MINT), recreated[0])
        // Anyone can press claim for anyone: the payout still names the seat's own wallet.
        assertInstruction(SkrInstructions.claimStack(table, seatOf(guest), guest), recreated[1])

        // A forfeited seat claims nothing (it only closes, returning its rent): no SKR account needed.
        val lost = service(chain).prepareClaim(other, table, other, v0)
        assertEquals(listOf("hd:19"), TxInspect.tags(lost.transactions.single()))
        assertEquals(0uL, lost.amount)
        assertEquals(StackRefusedException.Reason.NO_SEAT, refusal { service(chain).prepareClaim(seeker, table, seeker, v0) })
    }

    @Test
    fun `nothing is claimable before settlement, and everything is refundable after the timeout`() = runBlocking {
        val open = chain().apply { table(seatCount = 1, start = round - 100); seat(guest, 0, checked = 46, last = round - 55); skr(guest, 0) }
        assertEquals(StackRefusedException.Reason.NOT_CLAIMABLE, refusal { service(open).prepareClaim(guest, table, guest, v0) })
        val late = chain().apply { table(seatCount = 1, start = round - 100, refundAfter = now - 1); seat(guest, 0); skr(guest, 0) }
        val refund = service(late).prepareClaim(guest, table, guest, v0)
        assertEquals(listOf("hd:19"), TxInspect.tags(refund.transactions.single()))
        assertEquals(200uL * Skr.ONE_SKR, refund.amount)
        assertEquals(StackRefusedException.Reason.NOT_A_TABLE, refusal { service(chain()).prepareClaim(guest, table, guest, v0) })
    }

    // ------------------------------------------------------------------------- over HTTPS

    @Test
    fun `over HTTPS - open, then read the table back from the cluster`() = runBlocking {
        val chain = chain().apply { rig(guest); skr(guest, 300) }
        val server = ChainServer(chain).also { servers += it }
        val service = StackService(server.rpc(), nowUnix = { now }, newTableId = { 77uL })
        val prepared = service.prepareOpen(guest, draft, v0)
        assertEquals(listOf("ata", "hd:15", "hd:16"), TxInspect.tags(prepared.transactions.single()))
        assertEquals(5_000L, prepared.lastValidBlockHeight)
        // What the confirmed transaction leaves on-chain: the table and the host's seat.
        chain.put(
            prepared.table, HeadsDownProgram.ID,
            TestAccounts.stackTableBytes(guest, 77, startRound = round + 15, endRound = round + 60, seatCount = 1, totalBonds = 200 * skr),
        )
        chain.seat(guest, 0, of = prepared.table)
        val view = service.table(prepared.table)!!
        assertEquals(StackPhase.GATHERING, view.phase)
        assertEquals(guest, view.seats.single().seat.authority)
        assertEquals(15uL, view.roundsUntilStart)
        assertEquals(4, server.requestCount)
    }
}
