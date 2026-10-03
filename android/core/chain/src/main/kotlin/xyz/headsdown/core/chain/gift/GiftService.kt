package xyz.headsdown.core.chain.gift

import kotlinx.coroutines.CancellationException
import xyz.headsdown.core.chain.GiftLimits
import xyz.headsdown.core.chain.HeadsDownProgram
import xyz.headsdown.core.chain.Ore
import xyz.headsdown.core.chain.Pda
import xyz.headsdown.core.chain.Pubkey
import xyz.headsdown.core.chain.Skr
import xyz.headsdown.core.chain.WellKnown
import xyz.headsdown.core.chain.accounts.AccountLayoutException
import xyz.headsdown.core.chain.accounts.GiftEscrowAccount
import xyz.headsdown.core.chain.accounts.HeadsDownAccounts
import xyz.headsdown.core.chain.accounts.OreAccounts
import xyz.headsdown.core.chain.accounts.SgtHolding
import xyz.headsdown.core.chain.accounts.SplTokenAccounts
import xyz.headsdown.core.chain.accounts.ifCreated
import xyz.headsdown.core.chain.ix.ComputeBudgetInstructions
import xyz.headsdown.core.chain.ix.GiftRecipientKind
import xyz.headsdown.core.chain.ix.HeadsDownInstructions
import xyz.headsdown.core.chain.ix.OreInstructions
import xyz.headsdown.core.chain.ix.SgtAccounts
import xyz.headsdown.core.chain.ix.SkrInstructions
import xyz.headsdown.core.chain.rpc.AccountFilter
import xyz.headsdown.core.chain.rpc.SolanaJsonRpc
import xyz.headsdown.core.chain.swap.SwapLegBuilder
import xyz.headsdown.core.chain.swap.SwapLegResult
import xyz.headsdown.core.chain.swap.SwapProvider
import xyz.headsdown.core.chain.swap.SwapProviderException
import xyz.headsdown.core.chain.swap.SwapQuote
import xyz.headsdown.core.chain.swap.SwapRequest
import xyz.headsdown.core.chain.swap.SwapSkip
import xyz.headsdown.core.chain.tx.TransactionBuilder
import xyz.headsdown.core.chain.tx.TxVersion
import xyz.headsdown.core.wallet.PreparedTransactions
import xyz.headsdown.core.wallet.WalletCapabilities
import java.io.IOException
import java.security.SecureRandom

/** Who a gift is for, as the program stores it (INTERFACE §11.7). */
sealed interface GiftRecipient {
    /** The wallet that could claim it today. */
    val wallet: Pubkey

    /** The `.skr` name the sender typed, when there was one. Shown only; never stored on-chain. */
    val name: SkrName?

    /** That wallet claims (`recipient_kind` 0). */
    data class Wallet(override val wallet: Pubkey, override val name: SkrName? = null) : GiftRecipient

    /**
     * Whoever holds the Seeker Genesis Token [sgt] when claiming (`recipient_kind` 1). The gift
     * is bound to the token's mint, not to the name or the wallet: it follows the Seeker.
     */
    data class Seeker(override val wallet: Pubkey, val sgt: SgtHolding, override val name: SkrName?) : GiftRecipient

    val kind: GiftRecipientKind get() = if (this is Seeker) GiftRecipientKind.SGT_MINT else GiftRecipientKind.WALLET

    /** What `create_gift` stores: the wallet, or the SGT mint. */
    val stored: Pubkey get() = if (this is Seeker) sgt.mint else wallet
}

/** What looking a typed recipient up found. */
sealed interface RecipientLookup {
    data class Found(val recipient: GiftRecipient) : RecipientLookup

    /** A well-formed `.skr` name nobody has registered. */
    data class NoSuchName(val name: SkrName) : RecipientLookup

    /** The name exists but its holder cannot be read from it (expired or wrapped): paste the wallet. */
    data class NameUnresolvable(val name: SkrName) : RecipientLookup

    /** A valid address that no wallet can sign for (a program-derived address): nobody could claim. */
    data object NotAWallet : RecipientLookup

    /** Neither a `.skr` name nor a wallet address. */
    data object Invalid : RecipientLookup
}

/** How the sender pays for the gift. The program escrows SOL either way. */
sealed interface GiftFunding {
    /** The gift, in lamports of SOL, straight from the sender's wallet. */
    data class Sol(val lamports: ULong) : GiftFunding

    /**
     * Sell exactly [skr] base units of SKR for SOL in the same transaction, and gift [lamports]:
     * the minimum of the quote the sender was shown. A fresh quote that guarantees less is
     * refused. Whatever the swap pays above the minimum stays in the sender's wallet.
     */
    data class Skr(val skr: ULong, val lamports: ULong, val slippageBps: Int) : GiftFunding
}

/** Why a gift action cannot be built. The message is fixed text, safe to show. */
class GiftRefusedException(val reason: Reason, val swap: SwapSkip? = null) : IllegalStateException(reason.message) {
    enum class Reason(val message: String) {
        NOT_A_GIFT("There is no gift at this address on this cluster. It may have been claimed or taken back already."),
        EXPIRED("This gift has expired. Its sender can take it back."),
        NOT_FOR_THIS_WALLET("This gift is for another wallet."),
        NOT_FOR_THIS_SEEKER("This gift is for a Seeker that this wallet does not hold."),
        NOT_EXPIRED("A gift can be taken back only after it expires."),
        AMOUNT("A gift is between 1 lamport and 10 SOL."),
        INSUFFICIENT_SOL("Not enough SOL in this wallet for the gift, its escrow rent and the network fee."),
        INSUFFICIENT_SKR("Not enough SKR in this wallet."),
        SWAP_UNAVAILABLE("Paying with SKR is not possible right now, so nothing was swapped or sent. You can pay with SOL instead."),
    }
}

/** What the phone needs to turn a claim into a funded rig for a new wallet. */
class RigSetup(
    /** The phone's 33-byte compressed P-256 rig key. */
    rigKey: ByteArray,
    /** The plan's SOL per dig and tiles per dig: ORE's own per-square ceiling is `dig / tiles`. */
    val digLamports: ULong,
    val tiles: Int,
) {
    val rigKey: ByteArray = rigKey.copyOf()

    init {
        require(this.rigKey.size == 33) { "a rig key is 33 bytes" }
        require(tiles in 1..25 && digLamports / tiles.toULong() > 0uL) { "per-tile amount must be positive" }
    }
}

/** A gift escrow as read from the chain at [nowUnix]. */
class GiftView(val gift: GiftEscrowAccount, val nowUnix: Long) {
    /** Past its expiry (by the phone's clock, with a margin for the cluster's): refundable, not claimable. */
    val expired: Boolean get() = nowUnix >= gift.expiryTs
    val refundable: Boolean get() = nowUnix > gift.expiryTs + CLOCK_MARGIN_SECONDS
    val secondsLeft: Long get() = (gift.expiryTs - nowUnix).coerceAtLeast(0)

    companion object {
        /** The program compares the cluster's clock with the expiry; the phone's may run ahead. */
        const val CLOCK_MARGIN_SECONDS = 90L
    }
}

enum class GiftAction { CREATE, CLAIM, REFUND }

/** One gift transaction, serialized for MWA, and what it does once confirmed. */
class PreparedGift(
    transaction: ByteArray,
    lastValidBlockHeight: Long,
    val authority: Pubkey,
    val gift: Pubkey,
    val action: GiftAction,
    /** Lamports escrowed (CREATE), received (CLAIM) or sent back to the sender (REFUND). */
    val lamports: ULong,
    /** CLAIM: lamports of the gift moved on into the claimer's own ORE Automation (0 = none). */
    val deposit: ULong = 0uL,
    /** CLAIM: the transaction also registers the claimer's rig. */
    val registersRig: Boolean = false,
    /** CREATE with SKR: the quote the swap in this transaction executes. */
    val quote: SwapQuote? = null,
) : PreparedTransactions(listOf(transaction), lastValidBlockHeight)

/**
 * Gift a Rig on the phone (INTERFACE §11.7): resolve who it is for, escrow it (paying in SOL, or
 * in SKR through a checked swap in the same transaction), claim it as a funded rig, and take it
 * back after it expires.
 */
class GiftService(
    private val rpc: SolanaJsonRpc,
    /** null: no swap provider on this build or cluster (gifts are paid in SOL only). */
    private val swap: SwapProvider? = null,
    private val names: SkrNames = SkrNames(rpc),
    private val sgt: SgtLookup = SgtLookup(rpc),
    private val nowUnix: () -> Long = { System.currentTimeMillis() / 1000 },
    private val newNonce: () -> ULong = { SecureRandom().nextLong().toULong() },
) {
    val skrFundingAvailable: Boolean get() = swap != null

    // ------------------------------------------------------------------------ recipient

    /**
     * [text] is untrusted (typed, pasted, or another app's selection): a `.skr` name or a wallet
     * address, nothing else. A `.skr` name is resolved to its wallet, and the gift is addressed
     * to the Seeker Genesis Token that wallet holds; a wallet without one, or a pasted address,
     * gets a wallet gift.
     */
    suspend fun resolve(text: String): RecipientLookup {
        val trimmed = text.trim()
        if (trimmed.length > SkrName.MAX_LENGTH) return RecipientLookup.Invalid
        if (SkrName.parse(trimmed) != null) {
            return when (val found = names.resolve(trimmed)) {
                is SkrResolution.Found -> {
                    val holding = sgt.holdings(found.owner).firstOrNull()
                    RecipientLookup.Found(
                        if (holding != null) GiftRecipient.Seeker(found.owner, holding, found.name) else GiftRecipient.Wallet(found.owner, found.name),
                    )
                }
                is SkrResolution.NotFound -> RecipientLookup.NoSuchName(found.name)
                is SkrResolution.Unresolvable -> RecipientLookup.NameUnresolvable(found.name)
                SkrResolution.Invalid -> RecipientLookup.Invalid
            }
        }
        val wallet = try {
            Pubkey.fromBase58(trimmed)
        } catch (_: IllegalArgumentException) {
            return RecipientLookup.Invalid
        }
        // One address has one spelling; and only a point on the curve has a key that can sign a claim.
        if (wallet.toBase58() != trimmed || wallet == Pubkey.DEFAULT) return RecipientLookup.Invalid
        if (!Pda.isOnCurve(wallet.bytes)) return RecipientLookup.NotAWallet
        return RecipientLookup.Found(GiftRecipient.Wallet(wallet))
    }

    // --------------------------------------------------------------------------- create

    /**
     * A quote for selling [skr] base units of SKR for SOL, to show before signing. Null: no
     * provider, no route, or the provider failed; in every one of those cases SKR cannot pay.
     */
    suspend fun quoteSkr(skr: ULong, slippageBps: Int = SwapRequest.DEFAULT_SLIPPAGE_BPS): SwapQuote? {
        val provider = swap ?: return null
        return try {
            provider.quote(skrRequest(skr, slippageBps))
        } catch (e: CancellationException) {
            throw e
        } catch (_: SwapProviderException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /**
     * The swap that pays for a gift: exactly [skr] of SKR for SOL, over a route small enough to
     * leave room for `create_gift` behind it (measured on mainnet: a three-venue route alone is
     * 1,134 bytes and does not; a route held to [SKR_ROUTE_MAX_ACCOUNTS] accounts does).
     */
    private fun skrRequest(skr: ULong, slippageBps: Int) =
        SwapRequest(Skr.MINT, WellKnown.WRAPPED_SOL_MINT, skr, slippageBps, maxAccounts = SKR_ROUTE_MAX_ACCOUNTS)

    /**
     * `create_gift` for [recipient], or `[swap SKR for SOL] create_gift` in one v0 transaction.
     * The swap is built, checked and simulated by [SwapLegBuilder], and the whole transaction is
     * simulated again with the gift in it. Anything short of that refuses the gift: the sender
     * is never left with a swap and no gift, or a gift paid from the wrong pocket.
     */
    suspend fun prepareCreate(sender: Pubkey, recipient: GiftRecipient, funding: GiftFunding, capabilities: WalletCapabilities): PreparedGift {
        val lamports = when (funding) {
            is GiftFunding.Sol -> funding.lamports
            is GiftFunding.Skr -> funding.lamports
        }
        if (lamports < 1uL || lamports > GiftLimits.MAX_LAMPORTS) throw GiftRefusedException(GiftRefusedException.Reason.AMOUNT)
        val nonce = newNonce()
        val escrow = HeadsDownProgram.giftEscrow(sender, nonce).address
        val create = SkrInstructions.createGift(sender, nonce, recipient.kind, recipient.stored, lamports)
        val blockhash = rpc.getLatestBlockhash()

        if (funding is GiftFunding.Sol) {
            val rent = rpc.getMinimumBalanceForRentExemption(HeadsDownAccounts.GIFT_ESCROW_SIZE)
            if (rpc.getBalance(sender) < lamports + rent + FEE_MARGIN_LAMPORTS) throw GiftRefusedException(GiftRefusedException.Reason.INSUFFICIENT_SOL)
            val version = if (capabilities.supportsV0) TxVersion.V0 else TxVersion.LEGACY
            val message = TransactionBuilder.compile(sender, listOf(create), blockhash.blockhash, version)
            return PreparedGift(TransactionBuilder.unsignedTransaction(message), blockhash.lastValidBlockHeight, sender, escrow, GiftAction.CREATE, lamports)
        }

        funding as GiftFunding.Skr
        val provider = swap ?: throw GiftRefusedException(GiftRefusedException.Reason.SWAP_UNAVAILABLE, SwapSkip.NOT_AVAILABLE)
        val skrBalance = SplTokenAccounts.userBalance(rpc.getAccountInfo(Skr.account(sender)).ifCreated(), Skr.MINT, sender)
        if (skrBalance < funding.skr) throw GiftRefusedException(GiftRefusedException.Reason.INSUFFICIENT_SKR)
        val result = try {
            SwapLegBuilder(rpc, provider).build(
                user = sender,
                request = skrRequest(funding.skr, funding.slippageBps),
                minOut = lamports,
                blockhash = blockhash,
                supportsV0 = capabilities.supportsV0,
            )
        } catch (_: IllegalArgumentException) {
            SwapLegResult.Skipped(SwapSkip.PROVIDER_FAILED)
        }
        val leg = when (result) {
            is SwapLegResult.Ready -> result.leg
            is SwapLegResult.Skipped -> throw GiftRefusedException(GiftRefusedException.Reason.SWAP_UNAVAILABLE, result.reason)
        }
        val units = (leg.computeUnits + CREATE_GIFT_UNITS).coerceAtMost(ComputeBudgetInstructions.MAX_UNITS)
        val all = listOf(ComputeBudgetInstructions.setComputeUnitLimit(units)) + leg.instructions + create
        val message = TransactionBuilder.compile(sender, all, blockhash.blockhash, TxVersion.V0, leg.tables)
        if (!TransactionBuilder.fits(message)) throw GiftRefusedException(GiftRefusedException.Reason.SWAP_UNAVAILABLE, SwapSkip.DOES_NOT_FIT)
        val tx = TransactionBuilder.unsignedTransaction(message)
        val simulated = try {
            rpc.simulateTransaction(tx)
        } catch (_: IOException) {
            throw GiftRefusedException(GiftRefusedException.Reason.SWAP_UNAVAILABLE, SwapSkip.SIMULATION_FAILED)
        }
        if (!simulated.succeeded) throw GiftRefusedException(GiftRefusedException.Reason.SWAP_UNAVAILABLE, SwapSkip.SIMULATION_FAILED)
        return PreparedGift(tx, blockhash.lastValidBlockHeight, sender, escrow, GiftAction.CREATE, lamports, quote = leg.quote)
    }

    // ---------------------------------------------------------------------------- read

    /** The gift at [address], or null when there is none (never created, claimed, refunded, or not a gift). */
    suspend fun gift(address: Pubkey): GiftView? {
        val info = rpc.getAccountInfo(address) ?: return null
        return try {
            GiftView(HeadsDownAccounts.giftEscrow(address, info), nowUnix())
        } catch (_: AccountLayoutException) {
            null
        }
    }

    /** Gifts [sender] created that are still in escrow, newest first. */
    suspend fun sent(sender: Pubkey): List<GiftView> = escrows(HeadsDownAccounts.GIFT_ESCROW_SENDER_OFFSET, sender).filter { it.gift.sender == sender }

    /** Gifts waiting for [wallet]: addressed to it, or to a Seeker Genesis Token it holds now. */
    suspend fun waitingFor(wallet: Pubkey): List<GiftView> {
        val direct = escrows(HeadsDownAccounts.GIFT_ESCROW_RECIPIENT_OFFSET, wallet).filter { !it.gift.forSgtMint && it.gift.recipient == wallet }
        val bySeeker = sgt.holdings(wallet).take(MAX_SEEKERS).flatMap { holding ->
            escrows(HeadsDownAccounts.GIFT_ESCROW_RECIPIENT_OFFSET, holding.mint).filter { it.gift.forSgtMint && it.gift.recipient == holding.mint }
        }
        return (direct + bySeeker).filter { !it.expired }.sortedByDescending { it.gift.createdTs }
    }

    private suspend fun escrows(offset: Int, key: Pubkey): List<GiftView> {
        val now = nowUnix()
        return rpc.getProgramAccounts(
            HeadsDownProgram.ID,
            listOf(
                AccountFilter.DataSize(HeadsDownAccounts.GIFT_ESCROW_SIZE),
                AccountFilter.Memcmp(0, byteArrayOf(HeadsDownAccounts.GIFT_ESCROW_TAG.toByte())),
                AccountFilter.Memcmp(offset, key.bytes),
            ),
        ).take(MAX_LISTED).mapNotNull { keyed ->
            runCatching { GiftView(HeadsDownAccounts.giftEscrow(keyed.pubkey, keyed.account), now) }.getOrNull()
        }.sortedByDescending { it.gift.createdTs }
    }

    // --------------------------------------------------------------------------- claim

    /**
     * `claim_gift` for [claimer]. With [setup] and a wallet that has no rig yet, the same
     * transaction goes on to point the claimer's own ORE Automation at the heads_down Executor
     * with part of the gift in it, and registers the rig: `claim_gift, ORE automate,
     * register_rig`. What goes into the Automation is the gift less the rent of the three new
     * accounts and [WALLET_RESERVE_LAMPORTS], which stay in the wallet for the first shifts' fees.
     * A gift too small for that is simply received.
     *
     * The claimer's wallet pays the network fee, so it needs a little SOL of its own first.
     */
    suspend fun prepareClaim(claimer: Pubkey, giftAddress: Pubkey, setup: RigSetup?, capabilities: WalletCapabilities): PreparedGift {
        val view = gift(giftAddress) ?: throw GiftRefusedException(GiftRefusedException.Reason.NOT_A_GIFT)
        val gift = view.gift
        if (view.expired) throw GiftRefusedException(GiftRefusedException.Reason.EXPIRED)
        val holding: SgtHolding? = if (gift.forSgtMint) {
            sgt.holdingOf(claimer, gift.recipient) ?: throw GiftRefusedException(GiftRefusedException.Reason.NOT_FOR_THIS_SEEKER)
        } else {
            if (gift.recipient != claimer) throw GiftRefusedException(GiftRefusedException.Reason.NOT_FOR_THIS_WALLET)
            null
        }
        val instructions = mutableListOf(
            SkrInstructions.claimGift(claimer, giftAddress, gift.sender, holding?.let { SgtAccounts(it.tokenAccount, it.mint) }),
        )
        var deposit = 0uL
        var registers = false
        if (setup != null) {
            val rigAddress = HeadsDownProgram.rig(claimer).address
            val automationAddress = Ore.automation(claimer).address
            val minerAddress = Ore.miner(claimer).address
            val configAddress = HeadsDownProgram.config.address
            val read = rpc.getMultipleAccounts(listOf(configAddress, rigAddress, automationAddress, minerAddress))
            val config = read[0]?.let { HeadsDownAccounts.config(configAddress, it) }
            // Only a wallet with no rig, on a cluster where heads_down is set up, gets the full setup.
            if (config != null && read[1] == null) {
                var newAccounts = rpc.getMinimumBalanceForRentExemption(HeadsDownAccounts.RIG_SIZE)
                if (read[2] == null) newAccounts += rpc.getMinimumBalanceForRentExemption(OreAccounts.AUTOMATION_SIZE)
                if (read[3] == null) newAccounts += rpc.getMinimumBalanceForRentExemption(OreAccounts.MINER_SIZE) + Ore.CHECKPOINT_FEE.toULong()
                val kept = newAccounts + WALLET_RESERVE_LAMPORTS
                if (gift.lamports > kept) {
                    deposit = gift.lamports - kept
                    instructions += OreInstructions.automateHeadsDown(claimer, setup.digLamports / setup.tiles.toULong(), deposit, config.executorFee)
                    instructions += HeadsDownInstructions.registerRig(claimer, setup.rigKey)
                    registers = true
                }
            }
        }
        val blockhash = rpc.getLatestBlockhash()
        val version = if (capabilities.supportsV0) TxVersion.V0 else TxVersion.LEGACY
        // No compute-budget instruction: three program instructions get the default 200,000 units each.
        val message = TransactionBuilder.compile(claimer, instructions, blockhash.blockhash, version)
        check(TransactionBuilder.fits(message)) { "gift claim does not fit one packet" }
        return PreparedGift(
            TransactionBuilder.unsignedTransaction(message), blockhash.lastValidBlockHeight, claimer, giftAddress, GiftAction.CLAIM,
            gift.lamports, deposit, registers,
        )
    }

    // -------------------------------------------------------------------------- refund

    /**
     * `refund_gift`, paid for by [payer]. Permissionless once the gift has expired: every lamport
     * goes to the stored sender, whoever signs.
     */
    suspend fun prepareRefund(payer: Pubkey, giftAddress: Pubkey, capabilities: WalletCapabilities): PreparedGift {
        val view = gift(giftAddress) ?: throw GiftRefusedException(GiftRefusedException.Reason.NOT_A_GIFT)
        if (!view.refundable) throw GiftRefusedException(GiftRefusedException.Reason.NOT_EXPIRED)
        val blockhash = rpc.getLatestBlockhash()
        val version = if (capabilities.supportsV0) TxVersion.V0 else TxVersion.LEGACY
        val message = TransactionBuilder.compile(payer, listOf(SkrInstructions.refundGift(giftAddress, view.gift.sender)), blockhash.blockhash, version)
        return PreparedGift(
            TransactionBuilder.unsignedTransaction(message), blockhash.lastValidBlockHeight, payer, giftAddress, GiftAction.REFUND, view.gift.lamports,
        )
    }

    companion object {
        /** SOL left in a new wallet after a claim sets up its rig: the first shifts' fees and ShiftLog rent. */
        const val WALLET_RESERVE_LAMPORTS: ULong = 5_000_000uL

        /** What a wallet must have on top of a SOL gift and its escrow rent: the network fee, with room. */
        const val FEE_MARGIN_LAMPORTS: ULong = 100_000uL

        /** `create_gift` measured 4,993 units (INTERFACE §11.11); budgeted with room beside a swap. */
        const val CREATE_GIFT_UNITS = 20_000L

        /** The route-size hint sent with an SKR quote, so the swap and the gift fit one packet. */
        const val SKR_ROUTE_MAX_ACCOUNTS = 32
        const val MAX_LISTED = 20
        const val MAX_SEEKERS = 3
    }
}
