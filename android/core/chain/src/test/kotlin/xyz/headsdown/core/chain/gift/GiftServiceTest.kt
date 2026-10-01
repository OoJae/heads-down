package xyz.headsdown.core.chain.gift

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
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
import xyz.headsdown.core.chain.Pda
import xyz.headsdown.core.chain.Pubkey
import xyz.headsdown.core.chain.Skr
import xyz.headsdown.core.chain.TestAccounts
import xyz.headsdown.core.chain.TestVouchers
import xyz.headsdown.core.chain.TlsServer
import xyz.headsdown.core.chain.TxInspect
import xyz.headsdown.core.chain.WellKnown
import xyz.headsdown.core.chain.hexBytes
import xyz.headsdown.core.chain.ix.GiftRecipientKind
import xyz.headsdown.core.chain.ix.HeadsDownInstructions
import xyz.headsdown.core.chain.ix.OreInstructions
import xyz.headsdown.core.chain.ix.SgtAccounts
import xyz.headsdown.core.chain.ix.SkrInstructions
import xyz.headsdown.core.chain.swap.FakeSwapProvider
import xyz.headsdown.core.chain.swap.JupiterFixture
import xyz.headsdown.core.chain.swap.JupiterGuard
import xyz.headsdown.core.chain.swap.JupiterSwapProvider
import xyz.headsdown.core.chain.swap.OkHttpSwapHttp
import xyz.headsdown.core.chain.swap.SwapInstructions
import xyz.headsdown.core.chain.swap.SwapProvider
import xyz.headsdown.core.chain.swap.SwapProviderException
import xyz.headsdown.core.chain.swap.SwapSkip
import xyz.headsdown.core.chain.tx.AccountMeta
import xyz.headsdown.core.chain.tx.Instruction
import xyz.headsdown.core.chain.tx.TransactionBuilder
import xyz.headsdown.core.keys.RigSignalState
import xyz.headsdown.core.wallet.WalletCapabilities

/**
 * Gift a Rig on an in-memory cluster: who a typed recipient really is (`.skr` → wallet → Seeker
 * Genesis Token, on real mainnet bytes), the escrow paid in SOL or in SKR through a checked swap,
 * the claim that becomes a funded rig, and the refund.
 */
class GiftServiceTest {

    /** Wallets with real keys: a gift can only be claimed by an address that can sign. */
    private val sender = TestVouchers.registrarKey(ByteArray(32) { 21 })
    private val friend = TestVouchers.registrarKey(ByteArray(32) { 22 })
    private val stranger = TestVouchers.registrarKey(ByteArray(32) { 23 })

    /** A real mainnet wallet holding Seeker Genesis Token #33078, and its `.skr` name. */
    private val seeker = SkrFixture.owner
    private val sgtMint = Pubkey.fromBase58(SkrFixture.SGT_MINT)
    private val sgtToken = Pubkey.fromBase58(SkrFixture.SGT_TOKEN_ACCOUNT)

    private val key = hexBytes("0360fed4ba255a9d31c961eb74c6356d68c049b8923b61fa6ce669622e60f29fb6")
    private val now = 1_790_000_600L
    private val v0 = WalletCapabilities(supportsLegacy = true, supportsV0 = true, maxTransactionsPerRequest = 0)
    private val setup = RigSetup(key, digLamports = 1_000_000uL, tiles = 4)
    private val escrowRent = (128L + 128) * 6_960

    private val servers = mutableListOf<AutoCloseable>()

    @After
    fun close() = servers.forEach { it.close() }

    private fun chain(): FakeChain = SkrFixture.load(FakeChain()).apply {
        put(HeadsDownProgram.config.address, HeadsDownProgram.ID, TestAccounts.configBytes())
        for (w in listOf(sender, friend, stranger, seeker)) wallet(w, 2_000_000_000)
    }

    private fun service(chain: FakeChain, swap: SwapProvider? = null, nonce: ULong = 41uL) =
        GiftService(chain.rpc(), swap, nowUnix = { now }, newNonce = { nonce })

    private fun FakeChain.gift(nonce: Long, recipient: Pubkey, kind: Int = 0, lamports: Long = 500_000_000, createdTs: Long = 1_790_000_000, from: Pubkey = sender): Pubkey {
        val address = HeadsDownProgram.giftEscrow(from, nonce.toULong()).address
        put(address, HeadsDownProgram.ID, TestAccounts.giftEscrowBytes(from, nonce, recipient, kind, lamports, createdTs), lamports + escrowRent)
        return address
    }

    private fun assertInstruction(expected: Instruction, actual: DecodedInstruction) {
        assertEquals(expected.programId, actual.program)
        assertEquals(expected.accounts.map { it.pubkey }, actual.accounts)
        assertArrayEquals(expected.data, actual.data)
    }

    private fun refused(block: suspend () -> Unit): GiftRefusedException {
        val e = runCatching { runBlocking { block() } }.exceptionOrNull()
        assertTrue("expected a refusal, got $e", e is GiftRefusedException)
        return e as GiftRefusedException
    }

    // ------------------------------------------------------------------------ recipient

    @Test
    fun `a dot-skr name resolves to its wallet and the gift is addressed to that wallet's Seeker token`() = runBlocking {
        val chain = chain()
        val found = (service(chain).resolve("miner.skr") as RecipientLookup.Found).recipient as GiftRecipient.Seeker
        assertEquals(seeker, found.wallet)
        assertEquals("miner.skr", found.name!!.full)
        assertEquals(sgtMint, found.sgt.mint)
        assertEquals(sgtToken, found.sgt.tokenAccount)
        assertEquals(33_078uL, found.sgt.memberNumber)
        // What create_gift will store: the token's mint, as an SGT gift.
        assertEquals(GiftRecipientKind.SGT_MINT, found.kind)
        assertEquals(sgtMint, found.stored)
        // The name record, the wallet's Token-2022 accounts, then their mints: three reads.
        assertEquals(listOf("getAccountInfo", "getTokenAccountsByOwner", "getMultipleAccounts"), chain.methods)
        // The same for the name in any case, with spaces around it.
        assertEquals(found, (service(chain).resolve("  MINER.skr ") as RecipientLookup.Found).recipient)
    }

    @Test
    fun `a name whose wallet holds no Seeker token, and a pasted address, get a wallet gift`() = runBlocking {
        val chain = chain().apply { remove(sgtToken) }
        val named = (service(chain).resolve("miner.skr") as RecipientLookup.Found).recipient
        assertEquals(GiftRecipient.Wallet(seeker, SkrName.parse("miner.skr")), named)
        assertEquals(GiftRecipientKind.WALLET, named.kind)
        assertEquals(seeker, named.stored)

        val pasted = (service(chain).resolve(" ${friend.toBase58()}\n") as RecipientLookup.Found).recipient
        assertEquals(GiftRecipient.Wallet(friend), pasted)
        assertNull(pasted.name)
        // A pasted address is a wallet gift even when that wallet holds a Seeker token: only a name asks for the token.
        assertEquals(GiftRecipient.Wallet(seeker), (service(chain()).resolve(seeker.toBase58()) as RecipientLookup.Found).recipient)
    }

    @Test
    fun `text that is not a name or a wallet resolves to nobody, without a network call`() = runBlocking {
        val chain = chain()
        val s = service(chain)
        for (text in listOf("", "   ", "miner", "miner.sol", "min er.skr", "<script>.skr", "a".repeat(70) + ".skr", friend.toBase58() + "x", friend.toBase58().dropLast(3), "11111111111111111111111111111111", "0x" + "ab".repeat(20))) {
            assertEquals("for '$text'", RecipientLookup.Invalid, s.resolve(text))
        }
        // An address nobody holds a key for (a program-derived address) could never claim.
        val pda = HeadsDownProgram.rig(friend).address
        assertFalse(Pda.isOnCurve(pda.bytes))
        assertEquals(RecipientLookup.NotAWallet, s.resolve(pda.toBase58()))
        assertTrue(chain.requests.isEmpty())
        // A well-formed name nobody registered, and one that cannot be followed to a wallet.
        assertEquals(RecipientLookup.NoSuchName(SkrName.parse("nobody-here.skr")!!), s.resolve("nobody-here.skr"))
        val record = chain.accounts.getValue(SkrFixture.nameAccount)
        val expired = record.data.also { java.nio.ByteBuffer.wrap(it).order(java.nio.ByteOrder.LITTLE_ENDIAN).putLong(104, 1_780_000_000) }
        chain.put(SkrFixture.nameAccount, record.owner, expired)
        assertEquals(RecipientLookup.NameUnresolvable(SkrName.parse("miner.skr")!!), s.resolve("miner.skr"))
    }

    // --------------------------------------------------------------------------- create

    @Test
    fun `a gift paid in SOL is one create_gift, for a wallet or for a Seeker token`() = runBlocking {
        val chain = chain()
        val prepared = service(chain).prepareCreate(sender, GiftRecipient.Wallet(friend), GiftFunding.Sol(500_000_000uL), v0)
        val tx = prepared.transactions.single()
        assertEquals(listOf("hd:23"), TxInspect.tags(tx))
        assertInstruction(SkrInstructions.createGift(sender, 41uL, GiftRecipientKind.WALLET, friend, 500_000_000uL), TxInspect.instructions(tx).single())
        assertEquals(HeadsDownProgram.giftEscrow(sender, 41uL).address, prepared.gift)
        assertEquals(GiftAction.CREATE, prepared.action)
        assertEquals(500_000_000uL, prepared.lamports)
        assertNull(prepared.quote)
        assertEquals(listOf("getLatestBlockhash", "getMinimumBalanceForRentExemption", "getBalance"), chain.methods)

        val toSeeker = (service(chain).resolve("miner.skr") as RecipientLookup.Found).recipient
        val seekerGift = service(chain, nonce = 42uL).prepareCreate(sender, toSeeker, GiftFunding.Sol(1uL), WalletCapabilities.LEGACY_ONLY)
        assertInstruction(
            SkrInstructions.createGift(sender, 42uL, GiftRecipientKind.SGT_MINT, sgtMint, 1uL),
            TxInspect.instructions(seekerGift.transactions.single()).single(),
        )
    }

    @Test
    fun `a gift outside 1 lamport to 10 SOL, or one the wallet cannot cover, is refused`() {
        val to = GiftRecipient.Wallet(friend)
        assertEquals(GiftRefusedException.Reason.AMOUNT, refused { service(chain()).prepareCreate(sender, to, GiftFunding.Sol(0uL), v0) }.reason)
        assertEquals(GiftRefusedException.Reason.AMOUNT, refused { service(chain()).prepareCreate(sender, to, GiftFunding.Sol(10_000_000_001uL), v0) }.reason)
        assertEquals(GiftRefusedException.Reason.AMOUNT, refused { service(chain()).prepareCreate(sender, to, GiftFunding.Skr(5uL, 0uL, 50), v0) }.reason)
        // 2 SOL in the wallet: the gift, the escrow's rent and the fee must all fit.
        val short = chain()
        assertEquals(GiftRefusedException.Reason.INSUFFICIENT_SOL, refused { service(short).prepareCreate(sender, to, GiftFunding.Sol(2_000_000_000uL), v0) }.reason)
        val exact = 2_000_000_000uL - escrowRent.toULong() - GiftService.FEE_MARGIN_LAMPORTS
        assertEquals(GiftRefusedException.Reason.INSUFFICIENT_SOL, refused { service(chain()).prepareCreate(sender, to, GiftFunding.Sol(exact + 1uL), v0) }.reason)
        runBlocking { service(chain()).prepareCreate(sender, to, GiftFunding.Sol(exact), v0) }
    }

    // ------------------------------------------------------------------ create, paid in SKR

    private val skrIn = 500uL * Skr.ONE_SKR

    /** 500 SKR for 0.076 SOL: one small "swap" instruction signed by the sender. */
    private fun fakeSwap(padding: Int = 0) = FakeSwapProvider(outPerIn = 152uL to 1_000uL) { _, user ->
        val swap = Instruction(
            JupiterGuard.JUPITER_V6,
            listOf(AccountMeta.signer(user, writable = true), AccountMeta.writable(Skr.account(user))) +
                List(padding) { AccountMeta.readonly(Pubkey(ByteArray(32) { b -> (it + b + 1).toByte() })) },
            byteArrayOf(9, 9, 9),
        )
        SwapInstructions(emptyList(), swap, emptyList(), emptyList(), computeUnitLimit = 300_000)
    }

    private fun FakeChain.withSkr(who: Pubkey, base: Long) = put(Skr.account(who), WellKnown.SPL_TOKEN, TestAccounts.tokenAccountBytes(Skr.MINT, who, base))

    /** The simulated swap: [skr] base units leave the SKR account and [lamports] arrive in the wallet. */
    private fun FakeChain.swapPays(who: Pubkey, skr: Long, lamports: Long, failMerged: Boolean = false) {
        var runs = 0
        onSimulate = {
            runs++
            if (runs == 1) {
                val had = java.nio.ByteBuffer.wrap(accounts.getValue(Skr.account(who)).data).order(java.nio.ByteOrder.LITTLE_ENDIAN).getLong(64)
                withSkr(who, had - skr)
                wallet(who, accounts.getValue(who).lamports.toLong() + lamports - 5_000)
                null
            } else if (failMerged) {
                """{"InstructionError":[2,{"Custom":1}]}"""
            } else {
                null
            }
        }
    }

    @Test
    fun `a gift paid in SKR swaps and escrows in one transaction, for the minimum the sender was shown`() = runBlocking {
        val chain = chain().apply { withSkr(sender, 600_000_000); swapPays(sender, 500_000_000, 76_000_000) }
        val swap = fakeSwap()
        val service = service(chain, swap)
        assertTrue(service.skrFundingAvailable)
        val quote = service.quoteSkr(skrIn)!!
        assertEquals(76_000_000uL, quote.outAmount)
        assertEquals(75_620_000uL, quote.minOutAmount) // 0.5% slippage, computed on the phone

        val prepared = service.prepareCreate(sender, GiftRecipient.Wallet(friend), GiftFunding.Skr(skrIn, quote.minOutAmount, 50), v0)
        val tx = prepared.transactions.single()
        assertEquals(listOf("cb", "jupiter", "hd:23"), TxInspect.tags(tx))
        // The gift is the agreed minimum, not the quote: whatever the swap pays above it stays with the sender.
        assertInstruction(SkrInstructions.createGift(sender, 41uL, GiftRecipientKind.WALLET, friend, 75_620_000uL), TxInspect.instructions(tx).last())
        assertEquals(75_620_000uL, prepared.lamports)
        assertEquals(quote.minOutAmount, prepared.quote!!.minOutAmount)
        assertTrue(tx[65].toInt() and 0x80 != 0) // a v0 message
        // The swap alone was simulated and its effect on the sender's accounts checked; then the whole transaction.
        assertEquals(2, chain.simulated.size)
        assertEquals(listOf("cb", "jupiter"), TxInspect.tags(chain.simulated[0]))
        assertArrayEquals(tx, chain.simulated[1])
        assertEquals(sender, swap.instructionRequests.single().second)
    }

    @Test
    fun `paying in SKR fails closed - no provider, no route, a moved price, a legacy wallet, a failing simulation`() {
        val to = GiftRecipient.Wallet(friend)
        val funding = GiftFunding.Skr(skrIn, 75_620_000uL, 50)
        fun ready() = chain().apply { withSkr(sender, 600_000_000); swapPays(sender, 500_000_000, 76_000_000) }
        fun skip(chain: FakeChain, swap: SwapProvider?, f: GiftFunding = funding, caps: WalletCapabilities = v0): SwapSkip? {
            val e = refused { service(chain, swap).prepareCreate(sender, to, f, caps) }
            assertEquals(GiftRefusedException.Reason.SWAP_UNAVAILABLE, e.reason)
            return e.swap
        }
        assertEquals(SwapSkip.NOT_AVAILABLE, skip(ready(), null))
        assertFalse(service(ready()).skrFundingAvailable)
        assertNull(runBlocking { service(ready()).quoteSkr(skrIn) })
        assertEquals(SwapSkip.NO_QUOTE, skip(ready(), fakeSwap().apply { nextOutAmount = { null } }))
        assertEquals(SwapSkip.PROVIDER_FAILED, skip(ready(), fakeSwap().apply { failQuote = SwapProviderException.Reason.UNAVAILABLE }))
        assertEquals(SwapSkip.PROVIDER_FAILED, skip(ready(), fakeSwap().apply { failInstructions = SwapProviderException.Reason.INSTRUCTIONS_REFUSED }))
        // The fresh quote guarantees one lamport less than the sender agreed to.
        assertEquals(SwapSkip.PRICE_MOVED, skip(ready(), fakeSwap(), funding.copy(lamports = 75_620_001uL)))
        assertEquals(SwapSkip.NEEDS_V0, skip(ready(), fakeSwap(), caps = WalletCapabilities.LEGACY_ONLY))
        // Simulated alone, the swap delivers far less SOL than it promised.
        val stingy = chain().apply { withSkr(sender, 600_000_000); swapPays(sender, 500_000_000, 60_000_000) }
        assertEquals(SwapSkip.DELIVERS_TOO_LITTLE, skip(stingy, fakeSwap()))
        // ... or takes more SKR than the amount being sold.
        val greedy = chain().apply { withSkr(sender, 600_000_000); swapPays(sender, 500_000_001, 76_000_000) }
        assertEquals(SwapSkip.TAKES_TOO_MUCH, skip(greedy, fakeSwap()))
        // The swap is fine alone, but with the gift behind it the transaction would fail: nothing is signed.
        val failing = chain().apply { withSkr(sender, 600_000_000); swapPays(sender, 500_000_000, 76_000_000, failMerged = true) }
        assertEquals(SwapSkip.SIMULATION_FAILED, skip(failing, fakeSwap()))
        // A route too big to share a packet with the gift.
        assertEquals(SwapSkip.DOES_NOT_FIT, skip(ready(), fakeSwap(padding = 32)))
        // Not enough SKR is not a swap problem.
        val poor = chain().apply { withSkr(sender, 499_999_999) }
        assertEquals(GiftRefusedException.Reason.INSUFFICIENT_SKR, refused { service(poor, fakeSwap()).prepareCreate(sender, to, funding, v0) }.reason)
        assertEquals(GiftRefusedException.Reason.INSUFFICIENT_SKR, refused { service(chain(), fakeSwap()).prepareCreate(sender, to, funding, v0) }.reason)
    }

    // ---------------------------------------------------------------------------- read

    @Test
    fun `a gift is read through its checked decoder, and listed for its sender and its recipient`() = runBlocking {
        val chain = chain()
        val toFriend = chain.gift(1, friend)
        val toSeekerWallet = chain.gift(2, seeker, createdTs = 1_790_000_100)
        val toSeekerToken = chain.gift(3, sgtMint, kind = 1, createdTs = 1_790_000_200)
        val expired = chain.gift(4, friend, createdTs = now - 31L * 86_400)
        chain.gift(5, friend, from = stranger, createdTs = 1_790_000_300)
        // A ShiftLog is 128 bytes too, with a rig where a gift has its sender: never a gift.
        val rig = HeadsDownProgram.rig(sender).address
        chain.put(HeadsDownProgram.shiftLog(rig, 1uL).address, HeadsDownProgram.ID, TestAccounts.shiftLogBytes(rig, 1))
        val s = service(chain)

        val view = s.gift(toFriend)!!
        assertEquals(sender, view.gift.sender)
        assertEquals(friend, view.gift.recipient)
        assertEquals(500_000_000uL, view.gift.lamports)
        assertFalse(view.expired || view.refundable)
        assertEquals(1_790_000_000L + 30 * 86_400 - now, view.secondsLeft)
        assertTrue(s.gift(expired)!!.expired)
        assertNull(s.gift(HeadsDownProgram.giftEscrow(sender, 99uL).address))
        assertNull(s.gift(HeadsDownProgram.shiftLog(rig, 1uL).address))
        assertNull(s.gift(friend))

        assertEquals(listOf(toSeekerToken, toSeekerWallet, toFriend, expired), s.sent(sender).map { it.gift.address })
        // Waiting for a wallet: gifts to it, and to a Seeker token it holds; expired ones are the sender's to take back.
        assertEquals(setOf(toFriend, HeadsDownProgram.giftEscrow(stranger, 5uL).address), s.waitingFor(friend).map { it.gift.address }.toSet())
        assertEquals(listOf(toSeekerToken, toSeekerWallet), s.waitingFor(seeker).map { it.gift.address })
        assertTrue(s.waitingFor(stranger).isEmpty())
    }

    // --------------------------------------------------------------------------- claim

    @Test
    fun `a new wallet claims a gift as a funded rig - claim, ORE automate, register_rig in one transaction`() = runBlocking {
        val chain = chain()
        val gift = chain.gift(7, friend)
        val prepared = service(chain).prepareClaim(friend, gift, setup, v0)
        val tx = prepared.transactions.single()
        assertEquals(listOf("hd:24", "ore:0", "hd:1"), TxInspect.tags(tx))
        // The gift, less the rent of the three new accounts, ORE's checkpoint fee and 0.005 SOL kept for fees.
        val kept = (128L + 384) * 6_960 + (128L + 160) * 6_960 + (128L + 752) * 6_960 + 10_000 + 5_000_000
        assertEquals(16_702_800L, kept)
        assertEquals(500_000_000uL - kept.toULong(), prepared.deposit)
        val ixs = TxInspect.instructions(tx)
        assertInstruction(SkrInstructions.claimGift(friend, gift, sender), ixs[0])
        // The claimer's own Automation, pointed at the heads_down Executor with Config's fee and the plan's per-tile cap.
        assertInstruction(OreInstructions.automateHeadsDown(friend, 250_000uL, prepared.deposit, 10_000uL), ixs[1])
        assertInstruction(HeadsDownInstructions.registerRig(friend, key), ixs[2])
        assertTrue(prepared.registersRig)
        assertEquals(GiftAction.CLAIM, prepared.action)
        assertEquals(500_000_000uL, prepared.lamports)
        assertTrue(tx.size <= TransactionBuilder.PACKET_DATA_SIZE)
        // Legacy wallets get the same three instructions.
        assertEquals(listOf("hd:24", "ore:0", "hd:1"), TxInspect.tags(service(chain).prepareClaim(friend, gift, setup, WalletCapabilities.LEGACY_ONLY).transactions.single()))
    }

    @Test
    fun `a claim that cannot become a rig is simply received`() = runBlocking {
        fun tags(chain: FakeChain, gift: Pubkey, s: RigSetup? = setup) = runBlocking {
            service(chain).prepareClaim(friend, gift, s, v0).also { assertEquals(0uL, it.deposit); assertFalse(it.registersRig) }.transactions.single().let(TxInspect::tags)
        }
        // The wallet already has a rig.
        val withRig = chain().apply { put(HeadsDownProgram.rig(friend).address, HeadsDownProgram.ID, TestAccounts.rigBytesFull(friend, key, RigSignalState.IDLE, 2, false)) }
        assertEquals(listOf("hd:24"), tags(withRig, withRig.gift(7, friend)))
        // The gift does not cover the new accounts and the reserve.
        val small = chain()
        assertEquals(listOf("hd:24"), tags(small, small.gift(7, friend, lamports = 16_702_800)))
        assertEquals(listOf("hd:24", "ore:0", "hd:1"), TxInspect.tags(service(small).prepareClaim(friend, small.gift(8, friend, lamports = 16_702_801), setup, v0).transactions.single()))
        // This phone has no rig key yet.
        val noKey = chain()
        assertEquals(listOf("hd:24"), tags(noKey, noKey.gift(7, friend), s = null))
        // An ORE Miner that already exists is not paid for again.
        val mined = chain().apply { put(Ore.miner(friend).address, Ore.PROGRAM_ID, TestAccounts.minerBytes(friend)) }
        val more = service(mined).prepareClaim(friend, mined.gift(7, friend), setup, v0)
        assertEquals(500_000_000uL - (16_702_800uL - 6_124_800uL - 10_000uL), more.deposit)
    }

    @Test
    fun `a Seeker gift is claimed by whoever holds that token now, and by nobody else`() = runBlocking {
        val chain = chain()
        val gift = chain.gift(9, sgtMint, kind = 1)
        val prepared = service(chain).prepareClaim(seeker, gift, null, v0)
        val ix = TxInspect.instructions(prepared.transactions.single()).single()
        assertInstruction(SkrInstructions.claimGift(seeker, gift, sender, SgtAccounts(sgtToken, sgtMint)), ix)
        assertEquals(5, ix.accounts.size)
        assertEquals(GiftRefusedException.Reason.NOT_FOR_THIS_SEEKER, refused { service(chain).prepareClaim(friend, gift, null, v0) }.reason)
        // The token moved to another wallet: its old holder can no longer claim.
        chain.remove(sgtToken)
        assertEquals(GiftRefusedException.Reason.NOT_FOR_THIS_SEEKER, refused { service(chain).prepareClaim(seeker, gift, null, v0) }.reason)
    }

    @Test
    fun `a claim the program would refuse is refused with the reason`() {
        val chain = chain()
        val gift = chain.gift(7, friend)
        assertEquals(GiftRefusedException.Reason.NOT_FOR_THIS_WALLET, refused { service(chain).prepareClaim(stranger, gift, setup, v0) }.reason)
        assertEquals(GiftRefusedException.Reason.NOT_FOR_THIS_WALLET, refused { service(chain).prepareClaim(sender, gift, setup, v0) }.reason)
        assertEquals(GiftRefusedException.Reason.NOT_A_GIFT, refused { service(chain).prepareClaim(friend, HeadsDownProgram.giftEscrow(sender, 8uL).address, setup, v0) }.reason)
        // At its expiry a gift is no longer claimable (the program refuses a claim at or after expiry_ts).
        val old = chain.gift(10, friend, createdTs = now - 30L * 86_400)
        assertEquals(GiftRefusedException.Reason.EXPIRED, refused { service(chain).prepareClaim(friend, old, setup, v0) }.reason)
        val almost = chain.gift(11, friend, createdTs = now - 30L * 86_400 + 1)
        runBlocking { service(chain).prepareClaim(friend, almost, setup, v0) }
    }

    // -------------------------------------------------------------------------- refund

    @Test
    fun `an expired gift goes back to its sender, whoever asks, and never before it expires`() = runBlocking {
        val chain = chain()
        val expiredAt = now - 91
        val gift = chain.gift(12, friend, createdTs = expiredAt - 30L * 86_400)
        val prepared = service(chain).prepareRefund(stranger, gift, v0)
        val ix = TxInspect.instructions(prepared.transactions.single()).single()
        assertInstruction(SkrInstructions.refundGift(gift, sender), ix)
        assertEquals(GiftAction.REFUND, prepared.action)
        assertEquals(stranger, prepared.authority)
        assertEquals(500_000_000uL, prepared.lamports)
        // Inside the clock margin the cluster may not have reached the expiry yet: wait.
        val justExpired = chain.gift(13, friend, createdTs = now - 30L * 86_400 - 90)
        assertTrue(service(chain).gift(justExpired)!!.expired)
        assertEquals(GiftRefusedException.Reason.NOT_EXPIRED, refused { service(chain).prepareRefund(sender, justExpired, v0) }.reason)
        val live = chain.gift(14, friend)
        assertEquals(GiftRefusedException.Reason.NOT_EXPIRED, refused { service(chain).prepareRefund(sender, live, v0) }.reason)
        assertEquals(GiftRefusedException.Reason.NOT_A_GIFT, refused { service(chain).prepareRefund(sender, friend, v0) }.reason)
    }

    // -------------------------------------------------- over HTTPS, with live Jupiter answers

    /** Jupiter's two endpoints behind TLS, replaying the live answers of [fx]. */
    private fun jupiterServer(fx: JupiterFixture): Pair<TlsServer, MutableList<RecordedRequest>> {
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

    /** The fixture's wallet with SOL and SKR, the route's real lookup tables, and a swap that pays what it quoted. */
    private fun liveChain(fx: JupiterFixture, lamportsOut: Long): FakeChain = chain().apply {
        wallet(fx.user, 50_000_000)
        withSkr(fx.user, 800_000_000)
        fx.tableAccounts.forEach { (address, info) -> put(address, info.owner, info.data, info.lamports.toLong()) }
        swapPays(fx.user, 500_000_000, lamportsOut)
    }

    @Test
    fun `end to end over HTTPS - a live SKR to SOL route and the gift behind it, in one transaction`() = runBlocking {
        val fx = JupiterFixture.skrSolGift
        val user = fx.user
        val chain = liveChain(fx, 77_211_172)
        val chainServer = ChainServer(chain).also { servers += it }
        val (jupiter, seen) = jupiterServer(fx)
        val service = GiftService(chainServer.rpc(), JupiterSwapProvider(OkHttpSwapHttp(jupiter.url("/swap/v1"), jupiter.client)), nowUnix = { now }, newNonce = { 41uL })

        // 500 SKR quoted at 0.0772 SOL over two venues; the minimum is computed on the phone.
        val quote = service.quoteSkr(fx.request.inAmount)!!
        assertEquals(77_211_172uL, quote.outAmount)
        assertEquals(76_825_116uL, quote.minOutAmount)
        assertEquals(listOf("HumidiFi", "Kipseli"), quote.route)

        val prepared = service.prepareCreate(user, GiftRecipient.Wallet(friend), GiftFunding.Skr(fx.request.inAmount, quote.minOutAmount, 50), v0)
        val tx = prepared.transactions.single()
        assertTrue("${tx.size} bytes", tx.size <= TransactionBuilder.PACKET_DATA_SIZE)
        // Budget, Jupiter's wrapped-SOL account, the route, the unwrap, then the gift for exactly the agreed minimum.
        assertEquals(listOf("cb", "ata", "jupiter", "token", "hd:23"), TxInspect.tags(tx))
        assertInstruction(SkrInstructions.createGift(user, 41uL, GiftRecipientKind.WALLET, friend, 76_825_116uL), TxInspect.instructions(tx).last())
        assertEquals(76_825_116uL, prepared.lamports)
        assertEquals(HeadsDownProgram.giftEscrow(user, 41uL).address, prepared.gift)

        // Jupiter was asked for a small route, saw two quote requests and one for instructions, and the wallet only in the last.
        assertEquals(listOf("/swap/v1/quote", "/swap/v1/quote", "/swap/v1/swap-instructions"), seen.map { it.url.encodedPath })
        assertTrue(seen.take(2).all { it.url.queryParameter("maxAccounts") == "32" && it.url.queryParameter("swapMode") == "ExactIn" })
        assertTrue(seen.take(2).none { it.url.toString().contains(user.toBase58()) })
        assertEquals(user.toBase58(), Json.parseToJsonElement(seen[2].body!!.utf8()).jsonObject["userPublicKey"]!!.jsonPrimitive.content)
        // The cluster: blockhash, the SKR balance, the tables with the balances before, the swap alone, then the whole transaction.
        assertEquals(
            listOf("getLatestBlockhash", "getAccountInfo", "getMultipleAccounts", "simulateTransaction", "simulateTransaction"),
            chain.methods,
        )
        assertEquals(listOf("cb", "ata", "jupiter", "token"), TxInspect.tags(chain.simulated[0]))
        assertArrayEquals(tx, chain.simulated[1])
        println("SKR-funded gift over a live two-venue route: one transaction of ${tx.size} bytes (the route alone: ${chain.simulated[0].size})")
    }

    @Test
    fun `end to end over HTTPS - a live route too big to carry the gift is refused, and nothing is signed`() = runBlocking {
        // The same swap as Jupiter routes it by default: three venues, 1,134 bytes on its own.
        val fx = JupiterFixture.skrSol
        val chain = liveChain(fx, 75_989_714)
        val chainServer = ChainServer(chain).also { servers += it }
        val (jupiter, _) = jupiterServer(fx)
        val service = GiftService(chainServer.rpc(), JupiterSwapProvider(OkHttpSwapHttp(jupiter.url("/swap/v1"), jupiter.client)), nowUnix = { now }, newNonce = { 41uL })
        val quote = service.quoteSkr(fx.request.inAmount)!!
        assertEquals(listOf("HumidiFi", "ZeroFi", "Raydium CLMM"), quote.route)
        val e = refused { service.prepareCreate(fx.user, GiftRecipient.Wallet(friend), GiftFunding.Skr(fx.request.inAmount, quote.minOutAmount, 50), v0) }
        assertEquals(GiftRefusedException.Reason.SWAP_UNAVAILABLE, e.reason)
        assertEquals(SwapSkip.DOES_NOT_FIT, e.swap)
        // The route passed its own checks (simulated once, alone); the gift never reached a simulation or a wallet.
        assertEquals(1, chain.simulated.size)
        assertEquals(1_134, chain.simulated[0].size)
    }
}
