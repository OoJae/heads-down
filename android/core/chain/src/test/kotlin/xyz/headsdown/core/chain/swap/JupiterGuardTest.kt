package xyz.headsdown.core.chain.swap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.headsdown.core.chain.AssociatedToken
import xyz.headsdown.core.chain.Ore
import xyz.headsdown.core.chain.Pubkey
import xyz.headsdown.core.chain.WellKnown
import xyz.headsdown.core.chain.hex
import xyz.headsdown.core.chain.ix.AssociatedTokenInstructions
import xyz.headsdown.core.chain.swap.SwapProviderException.Reason
import xyz.headsdown.core.chain.tx.AccountMeta
import xyz.headsdown.core.chain.tx.Instruction
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The checks between Jupiter's answer and the wallet (THREAT_MODEL K6, "Quote proxy"): live
 * answers pass, and every way a hostile answer could redirect funds, widen the slippage, take a
 * fee or spend more of the wallet is refused.
 */
class JupiterGuardTest {

    private val fx = JupiterFixture.solOre
    private val user = fx.user
    private val attacker = Pubkey.fromBase58("mBKqcnGotbsSb5vNrdyhzZ5EhqZdids9QYiTRckvi7v")
    private val userOre = AssociatedToken.address(user, Ore.MINT).address
    private val userWsol = AssociatedToken.address(user, WellKnown.WRAPPED_SOL_MINT).address

    private fun refused(instructions: SwapInstructions, quote: SwapQuote = fx.quote, who: Pubkey = user): Boolean {
        val e = runCatching { JupiterGuard.check(quote, instructions, who) }.exceptionOrNull() ?: return false
        assertEquals(Reason.INSTRUCTIONS_REFUSED, (e as SwapProviderException).reason)
        return true
    }

    private fun Instruction.withData(edit: (ByteArray) -> Unit) = Instruction(programId, accounts, data.also(edit))

    private fun Instruction.withAccount(index: Int, meta: AccountMeta) =
        Instruction(programId, accounts.toMutableList().also { it[index] = meta }, data)

    private fun ByteArray.putU64(at: Int, v: Long) = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN).putLong(at, v)

    @Test
    fun `live answers for both directions pass`() {
        JupiterGuard.check(fx.quote, fx.instructions, user)
        val gift = JupiterFixture.skrSol
        JupiterGuard.check(gift.quote, gift.instructions, gift.user)
    }

    @Test
    fun `all four exact-in route instructions are understood, data and accounts`() {
        val variants = JupiterVariant.all
        assertEquals(listOf("route", "shared_accounts_route", "route_v2", "shared_accounts_route_v2"), variants.map { it.name })
        for (v in variants) {
            val ix = v.instructions
            val terms = JupiterGuard.terms(ix.swap.data)!!
            assertEquals(v.name, JupiterSwapTerms(v.request.inAmount, v.quote.outAmount, 50, 0, 0), terms)
            JupiterGuard.check(v.quote, ix, v.user)
        }
        // The discriminators are Anchor's sha256("global:<name>")[..8].
        assertEquals("e517cb977ae3ad2a", variants[0].instructions.swap.data.copyOf(8).hex())
        assertEquals("c1209b3341d69c81", variants[1].instructions.swap.data.copyOf(8).hex())
        assertEquals("bb64facc31c4af14", variants[2].instructions.swap.data.copyOf(8).hex())
        assertEquals("d19853937cfed8e9", variants[3].instructions.swap.data.copyOf(8).hex())
    }

    @Test
    fun `in every variant the output must be paid to the user's own account`() {
        // The positions the program pays to (Jupiter v6 IDL): route 3, shared 6, route_v2 2, shared_v2 5.
        val destinationAt = mapOf("route" to 3, "shared_accounts_route" to 6, "route_v2" to 2, "shared_accounts_route_v2" to 5)
        val sourceAt = mapOf("route" to 2, "shared_accounts_route" to 3, "route_v2" to 1, "shared_accounts_route_v2" to 2)
        val userAt = mapOf("route" to 1, "shared_accounts_route" to 2, "route_v2" to 0, "shared_accounts_route_v2" to 1)
        val attackerOre = AssociatedToken.address(attacker, Ore.MINT).address
        for (v in JupiterVariant.all) {
            val ix = v.instructions
            assertEquals(v.name, userOre, ix.swap.accounts[destinationAt.getValue(v.name)].pubkey)
            assertEquals(v.name, userWsol, ix.swap.accounts[sourceAt.getValue(v.name)].pubkey)
            assertEquals(v.name, user, ix.swap.accounts[userAt.getValue(v.name)].pubkey)
            // An attacker's token account where the output lands, with the user's still present elsewhere.
            val redirected = Instruction(
                ix.swap.programId,
                ix.swap.accounts.toMutableList().also { it[destinationAt.getValue(v.name)] = AccountMeta.writable(attackerOre) } + AccountMeta.writable(userOre),
                ix.swap.data,
            )
            assertTrue(v.name, refused(SwapInstructions(ix.setup, redirected, ix.cleanup, ix.lookupTables, null), v.quote))
            // Selling from someone else's account, or another signer in the user's seat.
            val otherSource = ix.swap.withAccount(sourceAt.getValue(v.name), AccountMeta.writable(attackerOre))
            assertTrue(v.name, refused(SwapInstructions(ix.setup, otherSource, ix.cleanup, ix.lookupTables, null), v.quote))
            val otherUser = ix.swap.withAccount(userAt.getValue(v.name), AccountMeta.signer(attacker, writable = false))
            assertTrue(v.name, refused(SwapInstructions(ix.setup, otherUser, ix.cleanup, ix.lookupTables, null), v.quote))
        }
    }

    @Test
    fun `the swap must carry the quote's amounts and no wider slippage`() {
        val swap = fx.instructions.swap
        val tail = swap.dataSize - 19
        // More input than quoted, a lower quoted output (a lower on-chain minimum), a wider slippage.
        assertTrue(refused(fx.instructionsWith(swap = swap.withData { it.putU64(tail, 15_200_001) })))
        assertTrue(refused(fx.instructionsWith(swap = swap.withData { it.putU64(tail + 8, 1_000) })))
        assertTrue(refused(fx.instructionsWith(swap = swap.withData { it[tail + 16] = 0x33 }))) // 51 bps
        assertTrue(refused(fx.instructionsWith(swap = swap.withData { it[tail + 17] = 0x27 }))) // 10,034 bps
        // A platform fee the user was not shown.
        assertTrue(refused(fx.instructionsWith(swap = swap.withData { it[tail + 18] = 25 })))
        // A tighter slippage than asked is fine: it only protects the user more.
        JupiterGuard.check(fx.quote, fx.instructionsWith(swap = swap.withData { it[tail + 16] = 0x0a }), user)
        // The quote the user saw was for another output: the instruction no longer matches it.
        val richer = SwapQuote(fx.request, fx.quote.outAmount + 1uL, 1, listOf("x"), 0, "{}")
        assertTrue(refused(fx.instructions, quote = richer))
        // A v2 instruction with a positive-slippage cut.
        val v2 = JupiterVariant.all.single { it.name == "route_v2" }
        val cut = v2.instructions.swap.withData { it[8 + 8 + 8 + 2 + 2] = 100 }
        assertTrue(refused(SwapInstructions(v2.instructions.setup, cut, v2.instructions.cleanup, emptyList(), null), v2.quote))
        val v2Fee = v2.instructions.swap.withData { it[8 + 8 + 8 + 2] = 25 }
        assertTrue(refused(SwapInstructions(v2.instructions.setup, v2Fee, v2.instructions.cleanup, emptyList(), null), v2.quote))
    }

    @Test
    fun `only Jupiter's known route instructions are a swap`() {
        val swap = fx.instructions.swap
        // Another program, an unknown instruction of Jupiter's, an exact-out route, truncated data.
        assertTrue(refused(fx.instructionsWith(swap = Instruction(Ore.PROGRAM_ID, swap.accounts, swap.data))))
        assertTrue(refused(fx.instructionsWith(swap = swap.withData { it[0] = (it[0] + 1).toByte() })))
        assertNull(JupiterGuard.terms(ByteArray(7)))
        assertNull(JupiterGuard.terms(swap.data.copyOf(20)))
        assertNull(JupiterGuard.terms(byteArrayOf(0xd0.toByte(), 0x33, 0xef.toByte(), 0x97.toByte(), 0x7b, 0x2b, 0xed.toByte(), 0x5c) + ByteArray(40))) // exact_out_route
        assertTrue(refused(fx.instructionsWith(swap = Instruction(swap.programId, swap.accounts.take(8), swap.data))))
        // A named platform-fee account or an alternative payee.
        assertTrue(refused(fx.instructionsWith(swap = swap.withAccount(9, AccountMeta.writable(AssociatedToken.address(attacker, Ore.MINT).address)))))
        val route = JupiterVariant.all.single { it.name == "route" }
        val payee = route.instructions.swap.withAccount(4, AccountMeta.writable(AssociatedToken.address(attacker, Ore.MINT).address))
        assertTrue(refused(SwapInstructions(route.instructions.setup, payee, route.instructions.cleanup, emptyList(), null), route.quote))
        // The mints at their fixed positions must be the quoted ones.
        assertTrue(refused(fx.instructionsWith(swap = swap.withAccount(8, AccountMeta.readonly(WellKnown.WRAPPED_SOL_MINT)))))
    }

    @Test
    fun `nobody but the user signs anything`() {
        val swap = fx.instructions.swap
        val extra = Instruction(swap.programId, swap.accounts + AccountMeta.signer(attacker, writable = false), swap.data)
        assertTrue(refused(fx.instructionsWith(swap = extra)))
        val setup = fx.instructions.setup
        val foreignPayer = setup[0].withAccount(0, AccountMeta.signer(attacker))
        assertTrue(refused(fx.instructionsWith(setup = listOf(foreignPayer) + setup.drop(1))))
        // The same answer checked for another wallet.
        assertTrue(refused(fx.instructions, who = attacker))
    }

    @Test
    fun `setup may only create the user's token accounts and wrap exactly the input`() {
        val setup = fx.instructions.setup
        val (createWsol, transfer, sync, createOre) = setup
        assertEquals(AssociatedTokenInstructions.createIdempotent(user, user, WellKnown.WRAPPED_SOL_MINT), createWsol)
        assertEquals(AssociatedTokenInstructions.createIdempotent(user, user, Ore.MINT), createOre)
        assertEquals("02000000" + "00efe70000000000", transfer.data.hex()) // System Transfer of 15,200,000 lamports
        assertEquals("11", sync.data.hex())

        fun with(index: Int, ix: Instruction) = fx.instructionsWith(setup = setup.toMutableList().also { it[index] = ix })
        // Wrapping more SOL than the quote sells, or sending it anywhere but the user's wSOL account.
        assertTrue(refused(with(1, transfer.withData { it.putU64(4, 15_200_000_000) })))
        assertTrue(refused(with(1, transfer.withAccount(1, AccountMeta.writable(attacker)))))
        assertTrue(refused(fx.instructionsWith(setup = setup + transfer))) // twice the input
        // Any other System instruction (here: Assign), token instruction (Transfer) or program.
        assertTrue(refused(with(1, transfer.withData { it[0] = 1 })))
        assertTrue(refused(with(2, sync.withData { it[0] = 3 })))
        assertTrue(refused(with(2, sync.withAccount(0, AccountMeta.writable(userOre)))))
        assertTrue(refused(with(2, Instruction(Ore.PROGRAM_ID, sync.accounts, sync.data))))
        // Creating a token account for somebody else at the user's expense, or a non-canonical one.
        assertTrue(refused(with(3, AssociatedTokenInstructions.createIdempotent(user, attacker, Ore.MINT))))
        assertTrue(refused(with(3, createOre.withAccount(1, AccountMeta.writable(attacker)))))
        assertTrue(refused(with(3, createOre.withData { it[0] = 2 }))) // RecoverNested
        // A SOL transfer has no place in a swap that sells a token.
        val gift = JupiterFixture.skrSol
        assertTrue(refused(gift.instructionsWith(setup = gift.instructions.setup + transfer), gift.quote, gift.user))
    }

    @Test
    fun `cleanup may only close the user's wrapped SOL account back to the user`() {
        val close = fx.instructions.cleanup.single()
        assertEquals("09", close.data.hex())
        assertEquals(listOf(userWsol, user, user), close.accounts.map { it.pubkey })
        assertTrue(refused(fx.instructionsWith(cleanup = listOf(close.withAccount(1, AccountMeta.writable(attacker))))))
        assertTrue(refused(fx.instructionsWith(cleanup = listOf(close.withAccount(0, AccountMeta.writable(userOre))))))
        assertTrue(refused(fx.instructionsWith(cleanup = listOf(close.withData { it[0] = 3 }))))
        assertTrue(refused(fx.instructionsWith(cleanup = listOf(Instruction(WellKnown.SYSTEM_PROGRAM, close.accounts, close.data)))))
        // No cleanup at all is acceptable (the wrapped SOL simply stays wrapped).
        JupiterGuard.check(fx.quote, fx.instructionsWith(cleanup = emptyList()), user)
    }

    @Test
    fun `the provider refuses a hostile answer before returning it`() {
        // End to end through the provider: a redirected destination never becomes SwapInstructions.
        val hostile = fx.swapBody.replace(userOre.toBase58(), AssociatedToken.address(attacker, Ore.MINT).address.toBase58())
        val parsed = JupiterSwapProvider.parseInstructions(hostile)
        assertThrows(SwapProviderException::class.java) { JupiterGuard.check(fx.quote, parsed, user) }
    }
}
