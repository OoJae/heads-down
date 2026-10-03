package xyz.headsdown.core.chain.swap

import xyz.headsdown.core.chain.AssociatedToken
import xyz.headsdown.core.chain.Pubkey
import xyz.headsdown.core.chain.WellKnown
import xyz.headsdown.core.chain.swap.SwapProviderException.Reason
import xyz.headsdown.core.chain.tx.Instruction
import java.security.MessageDigest

/** The terms a Jupiter swap instruction enforces on-chain, read back from its data. */
data class JupiterSwapTerms(
    val inAmount: ULong,
    val quotedOutAmount: ULong,
    val slippageBps: Int,
    val platformFeeBps: Int,
    /** v2 instructions only: a cut of any better-than-quoted fill. */
    val positiveSlippageBps: Int,
)

/**
 * What the app accepts from Jupiter's `/swap-instructions` (THREAT_MODEL K6, "Quote proxy"): the
 * answer is treated as hostile until every instruction is one of a short list, signed by the
 * user alone, and the swap instruction itself carries the quote's terms.
 *
 * - **Swap**: the Jupiter v6 program, with one of the four exact-in route instructions whose data
 *   and account layouts are known (`route`, `shared_accounts_route` and their `_v2` forms;
 *   checked against live answers on 2026-10-01). Its input amount must be the quote's, its
 *   quoted output the quote's, its slippage no wider than asked, with no platform fee and no
 *   positive-slippage cut. The program then enforces at least `quoted_out * (1 - slippage)`
 *   on-chain, which is [SwapQuote.minOutAmount]. The source and the destination must be the
 *   user's own token accounts for the quoted mints, at the positions the program pays from and
 *   to, and no other payee or fee account may be named.
 * - **Setup**: creating the user's own associated token accounts (paid by the user); and, for a
 *   SOL input only, wrapping exactly the input amount (a System transfer into the user's
 *   wrapped-SOL account, then `SyncNative`).
 * - **Cleanup**: closing the user's wrapped-SOL account back to the user.
 *
 * Anything else is [Reason.INSTRUCTIONS_REFUSED]: no swap is built.
 */
object JupiterGuard {
    val JUPITER_V6: Pubkey = Pubkey.fromBase58("JUP6LkbZbjS1jKKwapdHNy74zcZ3tLUZoi5QNyVTaV4")

    private const val TOKEN_SYNC_NATIVE = 17
    private const val TOKEN_CLOSE_ACCOUNT = 9
    private const val ATA_CREATE = 0
    private const val ATA_CREATE_IDEMPOTENT = 1
    private const val SYSTEM_TRANSFER = 2L

    /** Anchor discriminator: `sha256("global:<name>")[..8]`. */
    private fun discriminator(name: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest("global:$name".toByteArray(Charsets.US_ASCII)).copyOf(8)

    /**
     * Where the fixed accounts sit in each route instruction (Jupiter v6 IDL; confirmed against
     * live answers). The output is paid to the account at [destination]: it must be the user's
     * own, or the swap would satisfy its minimum on someone else's account.
     */
    private enum class Layout(
        val discriminator: ByteArray,
        val user: Int,
        val source: Int,
        val destination: Int,
        val sourceMint: Int?,
        val destinationMint: Int,
        /** An optional "pay somebody else" account: must be absent (the program id stands for None). */
        val optionalDestination: Int?,
        /** The optional platform-fee account: must be absent. */
        val platformFee: Int?,
        val fixedAccounts: Int,
    ) {
        ROUTE(discriminator("route"), user = 1, source = 2, destination = 3, sourceMint = null, destinationMint = 5, optionalDestination = 4, platformFee = 6, fixedAccounts = 9),
        SHARED_ACCOUNTS_ROUTE(discriminator("shared_accounts_route"), user = 2, source = 3, destination = 6, sourceMint = 7, destinationMint = 8, optionalDestination = null, platformFee = 9, fixedAccounts = 13),
        ROUTE_V2(discriminator("route_v2"), user = 0, source = 1, destination = 2, sourceMint = 3, destinationMint = 4, optionalDestination = 7, platformFee = null, fixedAccounts = 10),
        SHARED_ACCOUNTS_ROUTE_V2(discriminator("shared_accounts_route_v2"), user = 1, source = 2, destination = 5, sourceMint = 6, destinationMint = 7, optionalDestination = null, platformFee = null, fixedAccounts = 12),
    }

    private fun layout(data: ByteArray): Layout? {
        if (data.size < 8) return null
        val disc = data.copyOf(8)
        return Layout.entries.firstOrNull { it.discriminator.contentEquals(disc) }
    }

    /**
     * The terms inside a Jupiter exact-in route instruction, or null when the instruction is not
     * one of the four known ones (or is too short to be).
     *
     * ```
     * route:                    disc | route_plan (vec) | in u64 | quoted_out u64 | slippage u16 | fee u8
     * shared_accounts_route:    disc | id u8 | route_plan (vec) | in u64 | quoted_out u64 | slippage u16 | fee u8
     * route_v2:                 disc | in u64 | quoted_out u64 | slippage u16 | fee u16 | positive_slippage u16 | route_plan
     * shared_accounts_route_v2: disc | id u8 | in u64 | quoted_out u64 | slippage u16 | fee u16 | positive_slippage u16 | route_plan
     * ```
     */
    fun terms(data: ByteArray): JupiterSwapTerms? = when (layout(data)) {
        Layout.ROUTE -> tail(data, minSize = 8 + 4 + V1_TAIL)
        Layout.SHARED_ACCOUNTS_ROUTE -> tail(data, minSize = 8 + 1 + 4 + V1_TAIL)
        Layout.ROUTE_V2 -> head(data, at = 8)
        Layout.SHARED_ACCOUNTS_ROUTE_V2 -> head(data, at = 9)
        null -> null
    }

    private const val V1_TAIL = 8 + 8 + 2 + 1
    private const val V2_HEAD = 8 + 8 + 2 + 2 + 2

    private fun tail(data: ByteArray, minSize: Int): JupiterSwapTerms? {
        if (data.size < minSize) return null
        val at = data.size - V1_TAIL
        return JupiterSwapTerms(u64(data, at), u64(data, at + 8), u16(data, at + 16), data[at + 18].toInt() and 0xFF, positiveSlippageBps = 0)
    }

    private fun head(data: ByteArray, at: Int): JupiterSwapTerms? {
        if (data.size < at + V2_HEAD + 4) return null
        return JupiterSwapTerms(u64(data, at), u64(data, at + 8), u16(data, at + 16), u16(data, at + 18), u16(data, at + 20))
    }

    /** Throws [SwapProviderException] unless every instruction is acceptable for [quote] and [user]. */
    fun check(quote: SwapQuote, instructions: SwapInstructions, user: Pubkey) {
        val request = quote.request
        val source = tokenAccount(user, request.inputMint)
        val destination = tokenAccount(user, request.outputMint)
        val wrappedSol = tokenAccount(user, WellKnown.WRAPPED_SOL_MINT)

        // Only the user ever signs; nothing else could be signed by the wallet anyway.
        for (ix in instructions.all) {
            if (ix.accounts.any { it.isSigner && it.pubkey != user }) refuse()
        }

        // ---- the swap itself
        val swap = instructions.swap
        if (swap.programId != JUPITER_V6) refuse()
        val terms = terms(swap.data) ?: refuse()
        if (terms.inAmount != request.inAmount) refuse()
        if (terms.quotedOutAmount != quote.outAmount) refuse()
        if (terms.slippageBps > request.slippageBps) refuse()
        if (terms.platformFeeBps != quote.platformFeeBps || terms.positiveSlippageBps != 0) refuse()
        // The fixed accounts: the user signs, sells from and is paid into the user's own token
        // accounts for exactly the quoted mints, and nobody else is named as payee or fee taker.
        val layout = layout(swap.data) ?: refuse()
        val accounts = swap.accounts
        if (accounts.size < layout.fixedAccounts) refuse()
        if (accounts[layout.user].pubkey != user || !accounts[layout.user].isSigner) refuse()
        if (accounts[layout.source].pubkey != source || !accounts[layout.source].isWritable) refuse()
        if (accounts[layout.destination].pubkey != destination || !accounts[layout.destination].isWritable) refuse()
        if (layout.sourceMint != null && accounts[layout.sourceMint].pubkey != request.inputMint) refuse()
        if (accounts[layout.destinationMint].pubkey != request.outputMint) refuse()
        if (layout.optionalDestination != null && accounts[layout.optionalDestination].pubkey != JUPITER_V6) refuse()
        if (layout.platformFee != null && accounts[layout.platformFee].pubkey != JUPITER_V6) refuse()

        // ---- setup: the user's own token accounts, and wrapping exactly the SOL being sold
        var wrapped = 0uL
        for (ix in instructions.setup) {
            when (ix.programId) {
                WellKnown.ASSOCIATED_TOKEN -> checkCreateAta(ix, user)
                WellKnown.SYSTEM_PROGRAM -> {
                    if (request.inputMint != WellKnown.WRAPPED_SOL_MINT) refuse()
                    if (ix.dataSize != 12 || u32(ix.data, 0) != SYSTEM_TRANSFER) refuse()
                    if (ix.accounts.size != 2 || ix.accounts[0].pubkey != user || ix.accounts[1].pubkey != wrappedSol) refuse()
                    wrapped += u64(ix.data, 4)
                }
                WellKnown.SPL_TOKEN -> {
                    if (ix.dataSize != 1 || ix.data[0].toInt() != TOKEN_SYNC_NATIVE) refuse()
                    if (ix.accounts.size != 1 || ix.accounts[0].pubkey != wrappedSol) refuse()
                }
                else -> refuse()
            }
        }
        // A SOL input wraps exactly what the quote sells: never more of the wallet's SOL.
        if (wrapped != 0uL && wrapped != request.inAmount) refuse()

        // ---- cleanup: closing the user's wrapped-SOL account, to the user
        for (ix in instructions.cleanup) {
            if (ix.programId != WellKnown.SPL_TOKEN) refuse()
            if (ix.dataSize != 1 || ix.data[0].toInt() != TOKEN_CLOSE_ACCOUNT) refuse()
            if (ix.accounts.size != 3) refuse()
            if (ix.accounts[0].pubkey != wrappedSol || ix.accounts[1].pubkey != user || ix.accounts[2].pubkey != user) refuse()
        }
    }

    /** `Create` / `CreateIdempotent` of the user's own ATA, paid by the user. */
    private fun checkCreateAta(ix: Instruction, user: Pubkey) {
        val tag = if (ix.dataSize == 0) ATA_CREATE else ix.data[0].toInt()
        if (ix.dataSize > 1 || (tag != ATA_CREATE && tag != ATA_CREATE_IDEMPOTENT)) refuse()
        if (ix.accounts.size != 6) refuse()
        val (payer, ata, owner, mint, system, tokenProgram) = ix.accounts.map { it.pubkey }
        if (payer != user || owner != user || system != WellKnown.SYSTEM_PROGRAM) refuse()
        if (tokenProgram != WellKnown.SPL_TOKEN && tokenProgram != WellKnown.TOKEN_2022) refuse()
        if (ata != AssociatedToken.address(owner, mint, tokenProgram).address) refuse()
    }

    /** The user's classic SPL Token ATA for [mint] (SKR, ORE and wrapped SOL are all classic). */
    private fun tokenAccount(user: Pubkey, mint: Pubkey): Pubkey = AssociatedToken.address(user, mint).address

    private operator fun <T> List<T>.component6(): T = this[5]

    private fun u16(data: ByteArray, at: Int): Int = (data[at].toInt() and 0xFF) or ((data[at + 1].toInt() and 0xFF) shl 8)

    private fun u32(data: ByteArray, at: Int): Long {
        var v = 0L
        for (i in 3 downTo 0) v = (v shl 8) or (data[at + i].toLong() and 0xFF)
        return v
    }

    private fun u64(data: ByteArray, at: Int): ULong {
        var v = 0uL
        for (i in 7 downTo 0) v = (v shl 8) or (data[at + i].toULong() and 0xFFuL)
        return v
    }

    private fun refuse(): Nothing = throw SwapProviderException(Reason.INSTRUCTIONS_REFUSED)
}
