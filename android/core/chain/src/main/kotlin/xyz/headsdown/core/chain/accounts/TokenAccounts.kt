package xyz.headsdown.core.chain.accounts

import xyz.headsdown.core.chain.Ore
import xyz.headsdown.core.chain.Pubkey
import xyz.headsdown.core.chain.Skr
import xyz.headsdown.core.chain.WellKnown
import xyz.headsdown.core.chain.rpc.AccountInfo

/** A classic SPL Token account (165 bytes): what heads_down accepts as an SKR or ORE account. */
data class TokenAccount(
    val mint: Pubkey,
    /** The owner *field*: the wallet or PDA that may move the tokens. */
    val owner: Pubkey,
    /** Base units. */
    val amount: ULong,
    val hasDelegate: Boolean,
    val hasCloseAuthority: Boolean,
)

/** A classic SPL Token mint (82 bytes). */
data class TokenMint(
    val mintAuthority: Pubkey?,
    val supply: ULong,
    val decimals: Int,
    val freezeAuthority: Pubkey?,
)

/**
 * Classic SPL Token decoders, with the checks the program applies before reading an SKR or ORE
 * account (INTERFACE §11.1, `program/src/token.rs`): owned by the SPL Token program (a Token-2022
 * look-alike fails here), exactly 165 bytes, state Initialized (not Frozen), not native.
 */
object SplTokenAccounts {
    const val ACCOUNT_SIZE = 165
    const val MINT_SIZE = 82

    private const val OFF_MINT = 0
    private const val OFF_OWNER = 32
    private const val OFF_AMOUNT = 64
    private const val OFF_DELEGATE_TAG = 72
    private const val OFF_STATE = 108
    private const val OFF_NATIVE_TAG = 109
    private const val OFF_CLOSE_AUTHORITY_TAG = 129
    private const val STATE_INITIALIZED = 1

    private const val MINT_OFF_AUTHORITY_TAG = 0
    private const val MINT_OFF_AUTHORITY = 4
    private const val MINT_OFF_SUPPLY = 36
    private const val MINT_OFF_DECIMALS = 44
    private const val MINT_OFF_INITIALIZED = 45
    private const val MINT_OFF_FREEZE_TAG = 46
    private const val MINT_OFF_FREEZE = 50

    fun account(account: AccountInfo): TokenAccount {
        if (account.owner != WellKnown.SPL_TOKEN) throw AccountLayoutException("token account is not owned by SPL Token")
        val b = AccountBytes(account.data)
        if (b.size != ACCOUNT_SIZE) throw AccountLayoutException("token account must be $ACCOUNT_SIZE bytes, was ${b.size}")
        if (b.u8(OFF_STATE) != STATE_INITIALIZED) throw AccountLayoutException("token account is not initialized (or is frozen)")
        if (b.u32(OFF_NATIVE_TAG) != 0L) throw AccountLayoutException("native (wrapped SOL) token account")
        return TokenAccount(
            mint = b.pubkey(OFF_MINT),
            owner = b.pubkey(OFF_OWNER),
            amount = b.u64(OFF_AMOUNT),
            hasDelegate = b.u32(OFF_DELEGATE_TAG) != 0L,
            hasCloseAuthority = b.u32(OFF_CLOSE_AUTHORITY_TAG) != 0L,
        )
    }

    /**
     * The balance of a wallet's own account for [mint]: the account must hold [mint] and name
     * [owner] in its owner field (what `check_user_account` requires of a bond source or a payout
     * destination). A missing account is a zero balance.
     */
    fun userBalance(account: AccountInfo?, mint: Pubkey, owner: Pubkey): ULong {
        val token = account(account ?: return 0uL)
        if (token.mint != mint) throw AccountLayoutException("token account is for another mint")
        if (token.owner != owner) throw AccountLayoutException("token account belongs to another owner")
        return token.amount
    }

    fun mint(account: AccountInfo): TokenMint {
        if (account.owner != WellKnown.SPL_TOKEN) throw AccountLayoutException("mint is not owned by SPL Token")
        val b = AccountBytes(account.data)
        if (b.size != MINT_SIZE) throw AccountLayoutException("mint must be $MINT_SIZE bytes, was ${b.size}")
        if (b.u8(MINT_OFF_INITIALIZED) != 1) throw AccountLayoutException("mint is not initialized")
        return TokenMint(
            mintAuthority = option(b, MINT_OFF_AUTHORITY_TAG, MINT_OFF_AUTHORITY),
            supply = b.u64(MINT_OFF_SUPPLY),
            decimals = b.u8(MINT_OFF_DECIMALS),
            freezeAuthority = option(b, MINT_OFF_FREEZE_TAG, MINT_OFF_FREEZE),
        )
    }

    /** The SKR mint as pinned: its address, classic SPL Token, 6 decimals, no freeze authority. */
    fun skrMint(address: Pubkey, account: AccountInfo): TokenMint {
        if (address != Skr.MINT) throw AccountLayoutException("not the SKR mint")
        val mint = mint(account)
        if (mint.decimals != Skr.DECIMALS) throw AccountLayoutException("SKR has ${Skr.DECIMALS} decimals")
        if (mint.freezeAuthority != null) throw AccountLayoutException("SKR has no freeze authority")
        return mint
    }

    /** The ORE mint as pinned: its address, classic SPL Token, 11 decimals. */
    fun oreMint(address: Pubkey, account: AccountInfo): TokenMint {
        if (address != Ore.MINT) throw AccountLayoutException("not the ORE mint")
        val mint = mint(account)
        if (mint.decimals != Ore.DECIMALS) throw AccountLayoutException("ORE has ${Ore.DECIMALS} decimals")
        return mint
    }

    private fun option(b: AccountBytes, tagOffset: Int, keyOffset: Int): Pubkey? = when (b.u32(tagOffset)) {
        0L -> null
        1L -> b.pubkey(keyOffset)
        else -> throw AccountLayoutException("invalid COption tag")
    }
}
