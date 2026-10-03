package xyz.headsdown.core.chain.clockout

import com.solana.transaction.Message
import com.solana.transaction.VersionedMessage
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.headsdown.core.chain.ChainServer
import xyz.headsdown.core.chain.FakeChain
import xyz.headsdown.core.chain.HeadsDownProgram
import xyz.headsdown.core.chain.Ore
import xyz.headsdown.core.chain.Pubkey
import xyz.headsdown.core.chain.Skr
import xyz.headsdown.core.chain.TestAccounts
import xyz.headsdown.core.chain.TlsServer
import xyz.headsdown.core.chain.WellKnown
import xyz.headsdown.core.chain.hexBytes
import xyz.headsdown.core.chain.swap.FakeSwapProvider
import xyz.headsdown.core.chain.swap.JupiterFixture
import xyz.headsdown.core.chain.swap.JupiterGuard
import xyz.headsdown.core.chain.swap.JupiterSwapProvider
import xyz.headsdown.core.chain.swap.OkHttpSwapHttp
import xyz.headsdown.core.chain.swap.SwapInstructions
import xyz.headsdown.core.chain.swap.SwapProviderException
import xyz.headsdown.core.chain.swap.SwapSkip
import xyz.headsdown.core.chain.tx.AccountMeta
import xyz.headsdown.core.chain.tx.Instruction
import xyz.headsdown.core.chain.tx.TransactionBuilder
import xyz.headsdown.core.keys.RigSignalState
import xyz.headsdown.core.keys.ShiftEndReason
import xyz.headsdown.core.wallet.WalletCapabilities

/**
 * The clock-out end to end against an in-memory cluster: what is read, what is composed, how the
 * buy leg is checked (quote, instructions, lookup tables, simulation) and when it is dropped.
 * The last tests run the whole flow over HTTPS (MockWebServer) with live Jupiter answers.
 */
class ClockOutServiceTest {

    private val fx = JupiterFixture.solOre
    private val authority = fx.user
    private val rigAddress = HeadsDownProgram.rig(authority).address
    private val key = hexBytes("0360fed4ba255a9d31c961eb74c6356d68c049b8923b61fa6ce669622e60f29fb6")
    private val windowEnd = 1_790_028_800L
    private val now = windowEnd + 600
    private val round = 422_900L
    private val oreAccount = Ore.account(authority)
    private val v0 = WalletCapabilities(supportsLegacy = true, supportsV0 = true, maxTransactionsPerRequest = 0)

    private val servers = mutableListOf<AutoCloseable>()

    @After
    fun close() = servers.forEach { it.close() }

    /** A wallet with SOL, a rig whose completed shift 7 is still open, a Miner with ORE, and the ORE singletons. */
    private fun chain(
        rigState: RigSignalState = RigSignalState.DOWN,
        shiftOpen: Boolean = true,
        minerOre: Pair<Long, Long> = 1_000L to 20_000_000L,
        bond: Boolean = false,
        lamports: Long = 2_000_000_000,
    ): FakeChain = FakeChain().apply {
        wallet(authority, lamports)
        put(
            rigAddress, HeadsDownProgram.ID,
            TestAccounts.rigBytesFull(
                authority, key, rigState, 7, shiftOpen, planWindowEndTs = windowEnd, leaseToRound = round - 1,
                shiftStartRound = 422_600, shiftDarkRounds = 250, shiftStartTs = windowEnd - 28_800,
            ),
        )
        put(Ore.BOARD, Ore.PROGRAM_ID, TestAccounts.boardBytes(round))
        put(Ore.TREASURY, Ore.PROGRAM_ID, TestAccounts.treasuryBytes(totalUnrefined = 9_000_000_000_000))
        put(Ore.miner(authority).address, Ore.PROGRAM_ID, TestAccounts.minerBytes(authority, 0, minerOre.first, minerOre.second))
        put(Skr.account(authority), WellKnown.SPL_TOKEN, TestAccounts.tokenAccountBytes(Skr.MINT, authority, 750_000_000))
        if (bond) {
            val bondAddress = HeadsDownProgram.focusBond(rigAddress, 7uL).address
            put(bondAddress, HeadsDownProgram.ID, TestAccounts.focusBondBytes(rigAddress, authority, 7, 100_000_000, 422_600, windowEnd - 28_800))
        }
    }

    private fun service(chain: FakeChain, swap: xyz.headsdown.core.chain.swap.SwapProvider? = null) = ClockOutService(chain.rpc(), swap) { now }

    /** A fake swap of [units] ORE atoms per lamport: one small "swap" instruction signed by the user. */
    private fun fakeSwap(padding: Int = 0, tables: List<Pubkey> = emptyList()) = FakeSwapProvider { _, user ->
        val swap = Instruction(
            JupiterGuard.JUPITER_V6,
            listOf(AccountMeta.signer(user, writable = false), AccountMeta.writable(oreAccount)) + List(padding) { AccountMeta.readonly(Pubkey(ByteArray(32) { b -> (it + b + 1).toByte() })) },
            byteArrayOf(1, 2, 3),
        )
        SwapInstructions(emptyList(), swap, emptyList(), tables, computeUnitLimit = 300_000)
    }

    /** The simulated swap: the wallet pays [spent] lamports and its ORE account receives [oreOut] atoms. */
    private fun FakeChain.swapMoves(spent: Long, oreOut: Long) {
        var done = false
        onSimulate = {
            if (!done) {
                done = true
                wallet(authority, accounts.getValue(authority).lamports.toLong() - spent)
                put(oreAccount, WellKnown.SPL_TOKEN, TestAccounts.tokenAccountBytes(Ore.MINT, authority, oreOut), 2_039_280)
            }
            null
        }
    }

    private val buy = BuyLeg(spendLamports = 15_200_000uL, minOreAtoms = 1_800_000_000uL, slippageBps = 50)

    private fun programs(tx: ByteArray): List<String> {
        val message = Message.from(tx.copyOfRange(1 + 64 * tx[0], tx.size))
        return message.instructions.map { message.accounts[it.programIdIndex.toInt() and 0xFF].base58() }
    }

    // ------------------------------------------------------------------------- reading

    @Test
    fun `preview reads the Miner, the shift and the bond through the checked decoders`() = runBlocking {
        val chain = chain(bond = true)
        val preview = service(chain).preview(authority)
        assertEquals(1_000uL, preview.refinedOre)
        assertEquals(20_000_000uL, preview.unrefinedOre)
        assertEquals(2_000_000uL, preview.fullClaim!!.fee)
        assertEquals(18_001_000uL, preview.fullClaim!!.received)
        assertEquals(750uL * Skr.ONE_SKR, preview.skrBalance)
        // By default: end the completed shift, release the bond, keep the ORE unrefined.
        assertEquals(ShiftOutcome.Ends(ShiftEndReason.COMPLETED), preview.plan.shift)
        assertEquals(BondOutcome.Released(100uL * Skr.ONE_SKR), preview.plan.bond)
        assertNull(preview.plan.claimedOre)
        // Sealing the open shift writes a 128-byte ShiftLog, and the wallet pays its rent.
        assertEquals(((128L + 128) * 6_960).toULong(), preview.shiftLogRent)
        // Two reads (the wallet's accounts, then the bond and its ShiftLog slot) and that rent.
        assertEquals(listOf("getMultipleAccounts", "getMultipleAccounts", "getMinimumBalanceForRentExemption"), chain.methods)
    }

    @Test
    fun `a wallet with no rig, no Miner and no SKR previews as empty`() = runBlocking {
        val chain = FakeChain().apply {
            put(Ore.BOARD, Ore.PROGRAM_ID, TestAccounts.boardBytes(round))
            put(Ore.TREASURY, Ore.PROGRAM_ID, TestAccounts.treasuryBytes())
        }
        val preview = service(chain).preview(authority)
        assertTrue(preview.plan.isEmpty)
        assertEquals(0uL, preview.refinedOre + preview.unrefinedOre + preview.skrBalance)
        assertNull(preview.fullClaim)
        assertEquals(0uL, preview.shiftLogRent) // no shift to seal: no rent is read or stated
        assertFalse(preview.state.skrAccountExists)
        assertNull(service(chain).prepare(authority, ClockOutRequest(), v0))
    }

    @Test
    fun `spoofed chain state is refused, never signed over`() = runBlocking {
        // A "Rig" at the right address owned by another program.
        val spoofed = chain().apply { put(rigAddress, WellKnown.SYSTEM_PROGRAM, accounts.getValue(rigAddress).data) }
        assertTrue(runCatching { service(spoofed).prepare(authority, ClockOutRequest(), v0) }.exceptionOrNull() is IllegalArgumentException)
        // No ORE Board on this cluster: nothing to build against.
        val noBoard = chain().apply { remove(Ore.BOARD) }
        assertNotNull(runCatching { service(noBoard).prepare(authority, ClockOutRequest(), v0) }.exceptionOrNull())
    }

    // ------------------------------------------------------------------------- composing

    @Test
    fun `one transaction ends the shift, releases the bond and claims, in the wallet's version`() = runBlocking {
        for ((caps, versioned) in listOf(v0 to true, WalletCapabilities.LEGACY_ONLY to false)) {
            val prepared = service(chain(bond = true)).prepare(authority, ClockOutRequest(claimOreBps = 10_000), caps)!!
            val tx = prepared.transactions.single()
            assertEquals(1, tx[0].toInt())
            assertEquals(versioned, (tx[65].toInt() and 0x80) != 0)
            assertEquals(listOf(HeadsDownProgram.ID, HeadsDownProgram.ID, Ore.PROGRAM_ID).map { it.toBase58() }, programs(tx))
            assertEquals(5_000L, prepared.lastValidBlockHeight)
            assertEquals(BuyOutcome.NotRequested, prepared.buy)
            assertEquals(BondOutcome.Released(100uL * Skr.ONE_SKR), prepared.plan.bond)
            assertEquals(18_001_000uL, prepared.plan.claimedOre!!.received)
        }
    }

    @Test
    fun `a priority fee adds the compute budget in front`() = runBlocking {
        val prepared = service(chain()).prepare(authority, ClockOutRequest(priorityMicroLamports = 5_000uL), v0)!!
        assertEquals(
            listOf(WellKnown.COMPUTE_BUDGET, WellKnown.COMPUTE_BUDGET, HeadsDownProgram.ID).map { it.toBase58() },
            programs(prepared.transactions.single()),
        )
    }

    // --------------------------------------------------------------------------- the buy leg

    @Test
    fun `the buy leg joins the same transaction when it fits, after being simulated alone`() = runBlocking {
        val chain = chain().apply { swapMoves(spent = 15_205_000, oreOut = 1_824_000_000) }
        val swap = fakeSwap()
        val prepared = service(chain, swap).prepare(authority, ClockOutRequest(buy = buy), v0)!!
        val included = prepared.buy as BuyOutcome.Included
        assertFalse(included.ownTransaction)
        assertEquals(15_200_000uL * 120uL, included.quote.outAmount)
        val tx = prepared.transactions.single()
        assertEquals(
            listOf(WellKnown.COMPUTE_BUDGET, HeadsDownProgram.ID, JupiterGuard.JUPITER_V6).map { it.toBase58() },
            programs(tx),
        )
        // The swap was simulated alone (with the wallet and its ORE account watched), then merged.
        assertEquals(2, chain.simulated.size)
        assertEquals(listOf(JupiterGuard.JUPITER_V6.toBase58()), programs(chain.simulated[0]).filter { it != WellKnown.COMPUTE_BUDGET.toBase58() })
        assertTrue(chain.simulated[1].contentEquals(tx))
        // The request the provider saw: exact-in SOL to ORE with the user's slippage.
        val asked = swap.quoteRequests.single()
        assertEquals(WellKnown.WRAPPED_SOL_MINT to Ore.MINT, asked.inputMint to asked.outputMint)
        assertEquals(15_200_000uL to 50, asked.inAmount to asked.slippageBps)
        assertEquals(authority, swap.instructionRequests.single().second)
    }

    @Test
    fun `a buy that does not fit beside the clock-out is a second transaction in the same approval`() = runBlocking {
        val chain = chain(bond = true).apply { swapMoves(spent = 15_205_000, oreOut = 1_824_000_000) }
        // 22 extra accounts: fine alone, too large beside end_shift, release and claim.
        val prepared = service(chain, fakeSwap(padding = 22)).prepare(authority, ClockOutRequest(claimOreBps = 10_000, buy = buy), v0)!!
        assertEquals(2, prepared.transactions.size)
        assertTrue((prepared.buy as BuyOutcome.Included).ownTransaction)
        assertEquals(listOf(HeadsDownProgram.ID, HeadsDownProgram.ID, Ore.PROGRAM_ID).map { it.toBase58() }, programs(prepared.transactions[0]))
        assertEquals(listOf(WellKnown.COMPUTE_BUDGET, JupiterGuard.JUPITER_V6).map { it.toBase58() }, programs(prepared.transactions[1]))
        prepared.transactions.forEach { assertTrue(it.size <= TransactionBuilder.PACKET_DATA_SIZE) }
        // A wallet that signs one transaction per request gets the clock-out, and no buy.
        val one = WalletCapabilities(true, true, maxTransactionsPerRequest = 1)
        val chain2 = chain(bond = true).apply { swapMoves(spent = 15_205_000, oreOut = 1_824_000_000) }
        val single = service(chain2, fakeSwap(padding = 22)).prepare(authority, ClockOutRequest(claimOreBps = 10_000, buy = buy), one)!!
        assertEquals(1, single.transactions.size)
        assertEquals(BuyOutcome.Skipped(SwapSkip.ONE_TRANSACTION_ONLY), single.buy)
    }

    @Test
    fun `a buy with nothing else to do is a single swap transaction`() = runBlocking {
        val chain = chain(rigState = RigSignalState.IDLE, shiftOpen = false, minerOre = 0L to 0L).apply { swapMoves(15_205_000, 1_824_000_000) }
        val prepared = service(chain, fakeSwap()).prepare(authority, ClockOutRequest(buy = buy), v0)!!
        assertTrue(prepared.plan.isEmpty)
        assertEquals(listOf(WellKnown.COMPUTE_BUDGET, JupiterGuard.JUPITER_V6).map { it.toBase58() }, programs(prepared.transactions.single()))
        assertFalse((prepared.buy as BuyOutcome.Included).ownTransaction)
    }

    @Test
    fun `every doubt about the buy drops it and keeps the clock-out`() = runBlocking {
        suspend fun outcome(configure: (FakeChain, FakeSwapProvider) -> Unit, caps: WalletCapabilities = v0, provider: FakeSwapProvider = fakeSwap()): BuyOutcome {
            val chain = chain().apply { swapMoves(spent = 15_205_000, oreOut = 1_824_000_000) }
            configure(chain, provider)
            val prepared = service(chain, provider).prepare(authority, ClockOutRequest(buy = buy), caps)!!
            // The clock-out itself is always there, alone.
            assertEquals(listOf(HeadsDownProgram.ID.toBase58()), programs(prepared.transactions.single()))
            return prepared.buy
        }
        // No route.
        assertEquals(BuyOutcome.Skipped(SwapSkip.NO_QUOTE), outcome({ _, p -> p.nextOutAmount = { null } }))
        // The fresh quote guarantees less than the user agreed to (1.8e9 atoms).
        assertEquals(BuyOutcome.Skipped(SwapSkip.PRICE_MOVED), outcome({ _, p -> p.nextOutAmount = { 1_800_000_000uL } }))
        // The provider is down, or its instructions were refused by the guard.
        assertEquals(BuyOutcome.Skipped(SwapSkip.PROVIDER_FAILED), outcome({ _, p -> p.failQuote = SwapProviderException.Reason.UNAVAILABLE }))
        assertEquals(BuyOutcome.Skipped(SwapSkip.PROVIDER_FAILED), outcome({ _, p -> p.failInstructions = SwapProviderException.Reason.INSTRUCTIONS_REFUSED }))
        // A legacy-only wallet cannot sign a transaction with lookup tables.
        assertEquals(BuyOutcome.Skipped(SwapSkip.NEEDS_V0), outcome({ _, _ -> }, caps = WalletCapabilities.LEGACY_ONLY))
        // The cluster says the swap would fail.
        assertEquals(BuyOutcome.Skipped(SwapSkip.SIMULATION_FAILED), outcome({ c, _ -> c.onSimulate = { """{"InstructionError":[1,{"Custom":6001}]}""" } }))
        // Simulated, it delivers less than the minimum, or takes more SOL than it sells.
        assertEquals(BuyOutcome.Skipped(SwapSkip.DELIVERS_TOO_LITTLE), outcome({ c, _ -> c.swapMoves(spent = 15_205_000, oreOut = 1_000) }))
        assertEquals(BuyOutcome.Skipped(SwapSkip.TAKES_TOO_MUCH), outcome({ c, _ -> c.swapMoves(spent = 1_500_000_000, oreOut = 1_824_000_000) }))
        // A lookup table it names does not exist (or is not a table).
        val ghost = Pubkey(ByteArray(32) { 0x42 })
        assertEquals(BuyOutcome.Skipped(SwapSkip.TABLE_UNAVAILABLE), outcome({ _, _ -> }, provider = fakeSwap(tables = listOf(ghost))))
        assertEquals(
            BuyOutcome.Skipped(SwapSkip.TABLE_UNAVAILABLE),
            outcome({ c, _ -> c.put(ghost, WellKnown.SYSTEM_PROGRAM, ByteArray(56)) }, provider = fakeSwap(tables = listOf(ghost))),
        )
        // Too large for a packet even alone.
        assertEquals(BuyOutcome.Skipped(SwapSkip.DOES_NOT_FIT), outcome({ _, _ -> }, provider = fakeSwap(padding = 40)))
    }

    @Test
    fun `instructions that ask anyone else to sign are dropped whatever the provider says`() = runBlocking {
        val stranger = Pubkey(ByteArray(32) { 0x55 })
        val provider = FakeSwapProvider { _, user ->
            val swap = Instruction(JupiterGuard.JUPITER_V6, listOf(AccountMeta.signer(user), AccountMeta.signer(stranger)), byteArrayOf(1))
            SwapInstructions(emptyList(), swap, emptyList(), emptyList(), null)
        }
        val prepared = service(chain(), provider).prepare(authority, ClockOutRequest(buy = buy), v0)!!
        assertEquals(BuyOutcome.Skipped(SwapSkip.PROVIDER_FAILED), prepared.buy)
    }

    @Test
    fun `without a provider there is no buy and no quote`() = runBlocking {
        val svc = service(chain(), swap = null)
        assertFalse(svc.buyAvailable)
        assertNull(svc.quoteBuy(15_200_000uL))
        val prepared = svc.prepare(authority, ClockOutRequest(buy = buy), v0)!!
        assertEquals(BuyOutcome.Skipped(SwapSkip.NOT_AVAILABLE), prepared.buy)
        assertEquals(1, prepared.transactions.size)
        // A failing provider is "no quote" for the reveal, not an error screen.
        val failing = fakeSwap().apply { failQuote = SwapProviderException.Reason.MALFORMED }
        assertNull(service(chain(), failing).quoteBuy(15_200_000uL))
        assertEquals(1_824_000_000uL, service(chain(), fakeSwap()).quoteBuy(15_200_000uL)!!.outAmount)
    }

    // -------------------------------------------------- over HTTPS, with live Jupiter answers

    /** Jupiter's two endpoints behind TLS, replaying the live answers of the fixture. */
    private fun jupiterServer(): Pair<TlsServer, MutableList<RecordedRequest>> {
        val tls = TlsServer().also { servers += it }
        val seen = mutableListOf<RecordedRequest>()
        tls.server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seen += request
                val body = when (request.url.encodedPath) {
                    "/swap/v1/quote" -> fx.quoteBody
                    "/swap/v1/swap-instructions" -> fx.swapBody
                    else -> return MockResponse.Builder().code(404).build()
                }
                return MockResponse.Builder().addHeader("Content-Type", "application/json").body(body).build()
            }
        }
        return tls to seen
    }

    @Test
    fun `end to end over HTTPS - a live Jupiter route joins the clock-out through its real lookup table`() = runBlocking {
        val chain = chain(bond = true).apply {
            // The mainnet lookup table the route names, as mainnet returned it.
            fx.tableAccounts.forEach { (address, info) -> put(address, info.owner, info.data, info.lamports.toLong()) }
            swapMoves(spent = 15_205_000 + 2_039_280, oreOut = 1_824_000_000)
        }
        val chainServer = ChainServer(chain).also { servers += it }
        val (jupiter, seen) = jupiterServer()
        val provider = JupiterSwapProvider(OkHttpSwapHttp(jupiter.url("/swap/v1"), jupiter.client))
        val service = ClockOutService(chainServer.rpc(), provider) { now }

        // What the reveal shows first.
        val quote = service.quoteBuy(15_200_000uL)!!
        assertEquals(1_827_100_837uL, quote.outAmount)
        val request = ClockOutRequest(claimOreBps = 10_000, buy = BuyLeg(15_200_000uL, quote.minOutAmount, 50))
        val prepared = service.prepare(authority, request, v0)!!

        // One transaction: end_shift, release, claim, then Jupiter's wrap, swap and unwrap.
        val tx = prepared.transactions.single()
        assertTrue(tx.size <= TransactionBuilder.PACKET_DATA_SIZE)
        val included = prepared.buy as BuyOutcome.Included
        assertFalse(included.ownTransaction)
        assertEquals(quote.minOutAmount, included.quote.minOutAmount)
        val message = Message.from(tx.copyOfRange(65, tx.size)) as VersionedMessage
        assertEquals(1, message.addressTableLookups.size)
        assertEquals("DnwaKnJk8zmnSywMErVBKDtfZGMCNWvGkxZ4eyAUXpSe", message.addressTableLookups.single().account.base58())
        val order = programs(tx)
        assertEquals(WellKnown.COMPUTE_BUDGET.toBase58(), order.first())
        assertEquals(
            listOf(HeadsDownProgram.ID, HeadsDownProgram.ID, Ore.PROGRAM_ID, WellKnown.ASSOCIATED_TOKEN, WellKnown.SYSTEM_PROGRAM, WellKnown.SPL_TOKEN, WellKnown.ASSOCIATED_TOKEN, JupiterGuard.JUPITER_V6, WellKnown.SPL_TOKEN).map { it.toBase58() },
            order.drop(1),
        )
        // Jupiter saw exactly one quote request per quote and the wallet only in the instructions call.
        assertEquals(listOf("/swap/v1/quote", "/swap/v1/quote", "/swap/v1/swap-instructions"), seen.map { it.url.encodedPath })
        assertTrue(seen.take(2).none { it.url.toString().contains(authority.toBase58()) })
        val posted = Json.parseToJsonElement(seen[2].body!!.utf8()).jsonObject
        assertEquals(authority.toBase58(), posted["userPublicKey"]!!.jsonPrimitive.content)
        // The cluster was asked over HTTPS: reads, a blockhash, and two simulations.
        assertEquals(
            listOf("getMultipleAccounts", "getMultipleAccounts", "getLatestBlockhash", "getMultipleAccounts", "simulateTransaction", "simulateTransaction"),
            chain.methods,
        )
        assertTrue(chainServer.requestCount >= 6)
    }

    @Test
    fun `end to end over HTTPS - a quote that moved against the user buys nothing`() = runBlocking {
        val chain = chain().apply { fx.tableAccounts.forEach { (address, info) -> put(address, info.owner, info.data, info.lamports.toLong()) } }
        val chainServer = ChainServer(chain).also { servers += it }
        val (jupiter, seen) = jupiterServer()
        val service = ClockOutService(chainServer.rpc(), JupiterSwapProvider(OkHttpSwapHttp(jupiter.url("/swap/v1"), jupiter.client))) { now }
        // The user agreed to one atom more than this route guarantees now.
        val tooMuch = BuyLeg(15_200_000uL, fx.quote.minOutAmount + 1uL, 50)
        val prepared = service.prepare(authority, ClockOutRequest(buy = tooMuch), v0)!!
        assertEquals(BuyOutcome.Skipped(SwapSkip.PRICE_MOVED), prepared.buy)
        assertEquals(listOf(HeadsDownProgram.ID.toBase58()), programs(prepared.transactions.single()))
        // The instructions were never even requested, and nothing was simulated.
        assertEquals(listOf("/swap/v1/quote"), seen.map { it.url.encodedPath })
        assertTrue(chain.simulated.isEmpty())
    }
}
