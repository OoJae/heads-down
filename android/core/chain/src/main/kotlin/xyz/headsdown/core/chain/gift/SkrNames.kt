package xyz.headsdown.core.chain.gift

import xyz.headsdown.core.chain.Pda
import xyz.headsdown.core.chain.Pubkey
import xyz.headsdown.core.chain.accounts.AccountBytes
import xyz.headsdown.core.chain.accounts.AccountLayoutException
import xyz.headsdown.core.chain.rpc.AccountInfo
import xyz.headsdown.core.chain.rpc.SolanaJsonRpc
import java.security.MessageDigest

/**
 * The AllDomains name service (ANS), which issues `.skr` Seeker IDs. Derivations follow the
 * public `@onsol/tldparser` library (`getHashedName`, `getNameAccountKeyWithBump`,
 * `getOriginNameAccountKey`, `findTldHouse`, `findNameHouse`, `findNftRecord`) and were checked
 * against mainnet on 2026-10-01: the origin account derives to the library's own constant, and
 * real `.skr` records sit at the derived addresses (`fixtures/skr_miner.json`).
 *
 * ```
 * hashed(name)  = SHA-256("ALT Name Service" ‖ name)
 * name account  = PDA([hashed(name), name_class or 32 zero bytes, parent or 32 zero bytes], ANS program)
 * origin        = name account("ANS")                       = 3mX9b4AZ…
 * TLD parent    = name account(".skr", parent = origin)
 * a.skr         = name account("a",    parent = TLD parent)
 * ```
 *
 * A name record is 200 bytes of header (then free data): `discriminator[8] | parent_name[32] |
 * owner[32] | nclass[32] | expires_at u64 | created_at u64 | non_transferable u8 | padding[79]`.
 */
object AllDomains {
    val ANS_PROGRAM: Pubkey = Pubkey.fromBase58("ALTNSZ46uaAUU7XUV6awvdorLGqAsPwa9shm7h4uP2FK")
    val TLD_HOUSE_PROGRAM: Pubkey = Pubkey.fromBase58("TLDHkysf5pCnKsVA4gXpNvmy7psXLPEu4LAdDJthT9S")
    val NAME_HOUSE_PROGRAM: Pubkey = Pubkey.fromBase58("NH3uX6FtVE2fNREAioP7hm5RaozotZxeL6khU1EHx51")

    const val SKR_TLD = ".skr"
    const val RECORD_HEADER_SIZE = 200
    private const val HASH_PREFIX = "ALT Name Service"
    private const val ORIGIN_TLD = "ANS"
    private val ZERO = ByteArray(32)
    private val RECORD_DISCRIMINATOR = byteArrayOf(68, 72, 88, 44, 15, 167.toByte(), 103, 243.toByte())

    fun hashedName(name: String): ByteArray = MessageDigest.getInstance("SHA-256").digest((HASH_PREFIX + name).toByteArray(Charsets.UTF_8))

    /** The name account for [name] under [parent] (null: a root-level name), with no name class. */
    fun nameAccount(name: String, parent: Pubkey?): Pubkey =
        Pda.find(listOf(hashedName(name), ZERO, parent?.bytes ?: ZERO), ANS_PROGRAM).address

    /** The root every TLD hangs from: `3mX9b4AZaQehNoQGfckVcmgmA6bkBoFcbLj9RMmMyNcU`. */
    val origin: Pubkey by lazy { nameAccount(ORIGIN_TLD, null) }

    /** The parent account of every name under [tld] (with its dot: `".skr"`). */
    fun tldParent(tld: String): Pubkey = nameAccount(tld, origin)

    val skrParent: Pubkey by lazy { tldParent(SKR_TLD) }

    /** The record of `<label>.skr`. */
    fun skrNameAccount(label: String): Pubkey = nameAccount(label, skrParent)

    /**
     * The NFT record a name would be owned by if it were wrapped as an NFT
     * (`["nft_record", name_house, name_account]` under the Name House program, where
     * `name_house = ["name_house", tld_house]` and `tld_house = ["tld_house", tld]`).
     */
    fun nftRecord(nameAccount: Pubkey, tld: String): Pubkey {
        val tldHouse = Pda.find(listOf("tld_house".toByteArray(Charsets.US_ASCII), tld.lowercase().toByteArray(Charsets.UTF_8)), TLD_HOUSE_PROGRAM).address
        val nameHouse = Pda.find(listOf("name_house".toByteArray(Charsets.US_ASCII), tldHouse.bytes), NAME_HOUSE_PROGRAM).address
        return Pda.find(listOf("nft_record".toByteArray(Charsets.US_ASCII), nameHouse.bytes, nameAccount.bytes), NAME_HOUSE_PROGRAM).address
    }

    /** A decoded name record header. */
    class NameRecord(
        val parentName: Pubkey,
        val owner: Pubkey,
        /** Unix seconds; 0 = never expires (every `.skr` read so far). */
        val expiresAt: ULong,
        val createdAt: ULong,
        val nonTransferable: Boolean,
    )

    /** Decodes a name record: owned by the ANS program, at least the header, with its discriminator. */
    fun record(account: AccountInfo): NameRecord {
        if (account.owner != ANS_PROGRAM) throw AccountLayoutException("name record is not owned by the name service")
        val b = AccountBytes(account.data)
        if (b.size < RECORD_HEADER_SIZE) throw AccountLayoutException("name record is shorter than its header")
        if (!b.bytes(0, 8).contentEquals(RECORD_DISCRIMINATOR)) throw AccountLayoutException("not a name record")
        return NameRecord(
            parentName = b.pubkey(8),
            owner = b.pubkey(40),
            expiresAt = b.u64(104),
            createdAt = b.u64(112),
            nonTransferable = b.u8(120) == 1,
        )
    }
}

/**
 * A `.skr` name as the app accepts it from untrusted text (a selection, a paste): lower-cased,
 * at most 64 characters in all, ASCII letters, digits, `-` and `_` only. Names with other
 * characters exist on-chain; for those the sender pastes the wallet address instead.
 */
class SkrName private constructor(val label: String) {
    val full: String get() = label + AllDomains.SKR_TLD

    override fun equals(other: Any?): Boolean = other is SkrName && other.label == label
    override fun hashCode(): Int = label.hashCode()
    override fun toString(): String = full

    companion object {
        const val MAX_LENGTH = 64
        private val PATTERN = Regex("[a-z0-9][a-z0-9_-]{0,58}\\.skr")

        /** The name in [text], or null when [text] is not exactly one well-formed `.skr` name. */
        fun parse(text: String): SkrName? {
            if (text.length > MAX_LENGTH) return null
            // ASCII lower-casing only: no locale rules, no Unicode case folding.
            val lowered = text.trim().map { if (it in 'A'..'Z') it + 32 else it }.joinToString("")
            if (!lowered.matches(PATTERN)) return null
            return SkrName(lowered.removeSuffix(AllDomains.SKR_TLD))
        }
    }
}

/** What looking a `.skr` name up found. */
sealed interface SkrResolution {
    /** The record exists and names [owner] as its wallet. */
    data class Found(val name: SkrName, val nameAccount: Pubkey, val owner: Pubkey) : SkrResolution

    /** No such name is registered. */
    data class NotFound(val name: SkrName) : SkrResolution

    /** Not a `.skr` name the app accepts. */
    data object Invalid : SkrResolution

    /**
     * The record exists but its holder cannot be read from it: it has expired, or it is wrapped
     * as an NFT (its holder is then whoever holds that NFT, which the app does not follow).
     * The sender pastes the wallet address instead.
     */
    data class Unresolvable(val name: SkrName) : SkrResolution
}

/**
 * Resolves a `.skr` name to the wallet that owns it, with one account read. The record's address
 * is derived on the device and the record must belong to the name service, carry its
 * discriminator and hang from the `.skr` TLD: an RPC cannot substitute another name's record.
 */
class SkrNames(
    private val rpc: SolanaJsonRpc,
    private val nowUnix: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    suspend fun resolve(text: String): SkrResolution {
        val name = SkrName.parse(text) ?: return SkrResolution.Invalid
        val address = AllDomains.skrNameAccount(name.label)
        val account = rpc.getAccountInfo(address) ?: return SkrResolution.NotFound(name)
        val record = AllDomains.record(account)
        if (record.parentName != AllDomains.skrParent) throw AccountLayoutException("name record is not under .skr")
        val expired = record.expiresAt != 0uL && record.expiresAt <= nowUnix().coerceAtLeast(0).toULong()
        val wrapped = record.owner == AllDomains.nftRecord(address, AllDomains.SKR_TLD)
        if (expired || wrapped || record.owner == Pubkey.DEFAULT) return SkrResolution.Unresolvable(name)
        return SkrResolution.Found(name, address, record.owner)
    }
}
