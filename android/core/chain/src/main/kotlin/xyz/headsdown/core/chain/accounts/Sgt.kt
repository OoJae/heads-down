package xyz.headsdown.core.chain.accounts

import xyz.headsdown.core.chain.Pubkey
import xyz.headsdown.core.chain.WellKnown
import xyz.headsdown.core.chain.rpc.AccountInfo

/**
 * Why a (token account, mint) pair is not a Seeker Genesis Token held by the expected wallet.
 * Names and codes are `sgt_verify::SgtError` (`crates/sgt-verify/src/error.rs`); on-chain the
 * same rejection surfaces as `ProgramError::Custom(0x5347_0000 | code)`.
 */
enum class SgtRejection(val code: Int) {
    TOKEN_ACCOUNT_NOT_TOKEN_2022(1),
    MINT_NOT_TOKEN_2022(2),
    DUPLICATE_ACCOUNT(3),
    MINT_INVALID_LENGTH(10),
    MINT_PADDING_NOT_ZERO(11),
    MINT_ACCOUNT_TYPE_MISMATCH(12),
    MINT_NOT_INITIALIZED(13),
    MINT_INVALID_OPTION(14),
    MINT_MISSING_EXTENSIONS(15),
    MINT_AUTHORITY_MISMATCH(20),
    FREEZE_AUTHORITY_MISMATCH(21),
    DECIMALS_NOT_ZERO(22),
    SUPPLY_NOT_ONE(23),
    MALFORMED_TLV(30),
    DUPLICATE_EXTENSION(31),
    TOO_MANY_EXTENSIONS(32),
    INVALID_EXTENSION_LENGTH(33),
    MISSING_GROUP_MEMBER(34),
    GROUP_MISMATCH(35),
    GROUP_MEMBER_MINT_MISMATCH(36),
    INVALID_MEMBER_NUMBER(37),
    MISSING_GROUP_MEMBER_POINTER(38),
    GROUP_MEMBER_POINTER_MISMATCH(39),
    MISSING_PERMANENT_DELEGATE(40),
    PERMANENT_DELEGATE_MISMATCH(41),
    MISSING_METADATA_POINTER(42),
    METADATA_POINTER_MISMATCH(43),
    MISSING_MINT_CLOSE_AUTHORITY(44),
    MINT_CLOSE_AUTHORITY_MISMATCH(45),
    TOKEN_ACCOUNT_INVALID_LENGTH(50),
    TOKEN_ACCOUNT_TYPE_MISMATCH(51),
    TOKEN_ACCOUNT_INVALID_STATE(52),
    TOKEN_ACCOUNT_NOT_INITIALIZED(53),
    TOKEN_ACCOUNT_INVALID_OPTION(54),
    TOKEN_ACCOUNT_MINT_MISMATCH(60),
    TOKEN_ACCOUNT_OWNER_MISMATCH(61),
    AMOUNT_NOT_ONE(62),
    NATIVE_TOKEN_ACCOUNT(63),
}

/** The pair is not a genuine SGT holding. The message is the fixed rejection name, never data. */
class SgtException(val reason: SgtRejection) : IllegalArgumentException("not a Seeker Genesis Token holding: ${reason.name}")

/**
 * The trust anchors: the Token-2022 group every SGT belongs to and Solana Mobile's authority.
 * The mainnet program hardcodes [MAINNET]; a devnet build of the program is compiled against a
 * test group (`crates/sgt-verify`, feature `test-group`), which the app can be pointed at with a
 * build property. The phone's check only decides what to show and build: the program re-verifies.
 */
data class SgtAnchors(val group: Pubkey, val authority: Pubkey) {
    companion object {
        val MAINNET = SgtAnchors(
            group = Pubkey.fromBase58("GT22s89nU4iWFkNXj1Bw6uYhJJWDRPpShHt4Bk8f99Te"),
            authority = Pubkey.fromBase58("GT2zuHVaZQYZSyQMgJPLzvkmyztfyXg2NJunqFp4p3A4"),
        )
    }
}

/** A verified SGT holding: one Seeker device, identified by its mint (never by the wallet). */
data class SgtHolding(
    val mint: Pubkey,
    /** The holder's Token-2022 token account for that mint. */
    val tokenAccount: Pubkey,
    /** `TokenGroupMember.member_number`: the device's issue number, from 1. */
    val memberNumber: ULong,
    /** Real holdings are frozen at rest; a thawed one is still authentic. */
    val frozen: Boolean,
)

/**
 * Client-side verification of a Seeker Genesis Token, a port of `crates/sgt-verify`
 * (`verify_sgt_raw`): the same checks in the same order with the same rejections, so the phone
 * shows a wallet as a Seeker exactly when the program would accept it. The unit tests run it on
 * the crate's real mainnet fixtures and on its spoof cases.
 *
 * The only unforgeable fact is `TokenGroupMember.group`: Token-2022 writes it only in
 * `InitializeMember`, which needs the group's update authority to sign. Everything else is a
 * consistency check layered on that anchor (see the crate's README).
 */
object SgtVerifier {
    private const val MINT_BASE_LEN = 82
    private const val ACCOUNT_BASE_LEN = 165
    private const val MULTISIG_LEN = 355
    private const val ACCOUNT_TYPE_OFFSET = 165
    private const val TLV_START = 166
    private const val TLV_HEADER_LEN = 4
    private const val MAX_TLV_ENTRIES = 32

    private const val ACCOUNT_TYPE_MINT = 1
    private const val ACCOUNT_TYPE_ACCOUNT = 2

    private const val EXT_MINT_CLOSE_AUTHORITY = 3
    private const val EXT_PERMANENT_DELEGATE = 12
    private const val EXT_METADATA_POINTER = 18
    private const val EXT_GROUP_MEMBER_POINTER = 22
    private const val EXT_TOKEN_GROUP_MEMBER = 23

    /**
     * Verifies that [tokenAccount] holds exactly one genuine SGT of [mint] and names
     * [expectedOwner] in its owner field. Throws [SgtException] with the first failing check.
     */
    fun verify(
        tokenAccountAddress: Pubkey,
        tokenAccount: AccountInfo,
        mintAddress: Pubkey,
        mint: AccountInfo,
        expectedOwner: Pubkey,
        anchors: SgtAnchors = SgtAnchors.MAINNET,
    ): SgtHolding {
        // Ownership is checked before any byte is interpreted.
        if (tokenAccount.owner != WellKnown.TOKEN_2022) reject(SgtRejection.TOKEN_ACCOUNT_NOT_TOKEN_2022)
        if (mint.owner != WellKnown.TOKEN_2022) reject(SgtRejection.MINT_NOT_TOKEN_2022)
        if (tokenAccountAddress == mintAddress) reject(SgtRejection.DUPLICATE_ACCOUNT)
        val frozen = checkTokenAccount(tokenAccount.data, mintAddress, expectedOwner)
        val memberNumber = checkMint(mint.data, mintAddress, anchors)
        return SgtHolding(mintAddress, tokenAccountAddress, memberNumber, frozen)
    }

    /** [verify] as a predicate-with-value: null instead of a rejection. */
    fun verifyOrNull(
        tokenAccountAddress: Pubkey,
        tokenAccount: AccountInfo,
        mintAddress: Pubkey,
        mint: AccountInfo,
        expectedOwner: Pubkey,
        anchors: SgtAnchors = SgtAnchors.MAINNET,
    ): SgtHolding? = try {
        verify(tokenAccountAddress, tokenAccount, mintAddress, mint, expectedOwner, anchors)
    } catch (_: SgtException) {
        null
    }

    /**
     * The `(mint, amount)` of a Token-2022 token account, or null when it is not a well-formed
     * one. Used to pick the candidates worth fetching a mint for (amount exactly 1).
     */
    fun peekToken2022Account(account: AccountInfo): Pair<Pubkey, ULong>? {
        if (account.owner != WellKnown.TOKEN_2022) return null
        return try {
            val parsed = parseTokenAccount(account.data)
            parsed.mint to parsed.amount
        } catch (_: SgtException) {
            null
        }
    }

    // ------------------------------------------------------------------------------ policy

    /** The token-account half. Returns the frozen flag. */
    private fun checkTokenAccount(data: ByteArray, mint: Pubkey, expectedOwner: Pubkey): Boolean {
        val account = parseTokenAccount(data)
        if (account.mint != mint) reject(SgtRejection.TOKEN_ACCOUNT_MINT_MISMATCH)
        if (account.owner != expectedOwner) reject(SgtRejection.TOKEN_ACCOUNT_OWNER_MISMATCH)
        if (account.native) reject(SgtRejection.NATIVE_TOKEN_ACCOUNT)
        if (account.amount != 1uL) reject(SgtRejection.AMOUNT_NOT_ONE)
        return account.frozen
    }

    /** The mint half. Returns the member number. */
    private fun checkMint(data: ByteArray, mint: Pubkey, anchors: SgtAnchors): ULong {
        val state = parseMint(data)
        val ext = state.extensions
        if (ext.isEmpty()) reject(SgtRejection.MINT_MISSING_EXTENSIONS)
        if (state.mintAuthority != anchors.authority) reject(SgtRejection.MINT_AUTHORITY_MISMATCH)
        if (state.freezeAuthority != anchors.authority) reject(SgtRejection.FREEZE_AUTHORITY_MISMATCH)
        if (state.decimals != 0) reject(SgtRejection.DECIMALS_NOT_ZERO)
        if (state.supply != 1uL) reject(SgtRejection.SUPPLY_NOT_ONE)

        // The anchor: TokenGroupMember { mint: self, group, member_number >= 1 }.
        val member = ext.sized(EXT_TOKEN_GROUP_MEMBER, 72) ?: reject(SgtRejection.MISSING_GROUP_MEMBER)
        if (key(member, 32) != anchors.group) reject(SgtRejection.GROUP_MISMATCH)
        if (key(member, 0) != mint) reject(SgtRejection.GROUP_MEMBER_MINT_MISMATCH)
        val memberNumber = u64(member, 64)
        if (memberNumber == 0uL) reject(SgtRejection.INVALID_MEMBER_NUMBER)

        // Consistency with how every real SGT is issued.
        val pointer = ext.sized(EXT_GROUP_MEMBER_POINTER, 64) ?: reject(SgtRejection.MISSING_GROUP_MEMBER_POINTER)
        if (key(pointer, 0) != anchors.authority || key(pointer, 32) != mint) reject(SgtRejection.GROUP_MEMBER_POINTER_MISMATCH)

        val delegate = ext.sized(EXT_PERMANENT_DELEGATE, 32) ?: reject(SgtRejection.MISSING_PERMANENT_DELEGATE)
        if (key(delegate, 0) != anchors.authority) reject(SgtRejection.PERMANENT_DELEGATE_MISMATCH)

        val metadata = ext.sized(EXT_METADATA_POINTER, 64) ?: reject(SgtRejection.MISSING_METADATA_POINTER)
        if (key(metadata, 0) != anchors.authority || key(metadata, 32) != anchors.group) reject(SgtRejection.METADATA_POINTER_MISMATCH)

        val close = ext.sized(EXT_MINT_CLOSE_AUTHORITY, 32) ?: reject(SgtRejection.MISSING_MINT_CLOSE_AUTHORITY)
        if (key(close, 0) != anchors.authority) reject(SgtRejection.MINT_CLOSE_AUTHORITY_MISMATCH)

        return memberNumber
    }

    // ------------------------------------------------------------------------------ parsing

    private class ParsedMint(
        val mintAuthority: Pubkey?,
        val supply: ULong,
        val decimals: Int,
        val freezeAuthority: Pubkey?,
        val extensions: Extensions,
    )

    private class ParsedTokenAccount(
        val mint: Pubkey,
        val owner: Pubkey,
        val amount: ULong,
        val frozen: Boolean,
        val native: Boolean,
    )

    /** A fully walked, well-formed TLV region: no repeated type, at most 32 entries. */
    private class Extensions(private val entries: List<Pair<Int, ByteArray>>) {
        fun isEmpty(): Boolean = entries.isEmpty()

        /** The value of a fixed-size extension, which must be exactly [size] bytes; null if absent. */
        fun sized(type: Int, size: Int): ByteArray? {
            val value = entries.firstOrNull { it.first == type }?.second ?: return null
            if (value.size != size) reject(SgtRejection.INVALID_EXTENSION_LENGTH)
            return value
        }
    }

    /** Token-2022 mint: 82 bytes, or extended (more than 165 bytes and not 355). */
    private fun parseMint(data: ByteArray): ParsedMint {
        val tlv = when {
            data.size == MINT_BASE_LEN -> ByteArray(0)
            data.size > ACCOUNT_BASE_LEN && data.size != MULTISIG_LEN -> extendedTlv(
                data, MINT_BASE_LEN, ACCOUNT_TYPE_MINT, SgtRejection.MINT_PADDING_NOT_ZERO, SgtRejection.MINT_ACCOUNT_TYPE_MISMATCH,
            )
            else -> reject(SgtRejection.MINT_INVALID_LENGTH)
        }
        if (u8(data, 45) != 1) reject(SgtRejection.MINT_NOT_INITIALIZED)
        val mintAuthority = optionKey(data, 0, 4, SgtRejection.MINT_INVALID_OPTION)
        val freezeAuthority = optionKey(data, 46, 50, SgtRejection.MINT_INVALID_OPTION)
        val supply = u64(data, 36)
        val decimals = u8(data, 44)
        return ParsedMint(mintAuthority, supply, decimals, freezeAuthority, parseTlv(tlv))
    }

    /** Token-2022 token account: 165 bytes, or extended (more than 165 bytes and not 355). */
    private fun parseTokenAccount(data: ByteArray): ParsedTokenAccount {
        val tlv = when {
            data.size == ACCOUNT_BASE_LEN -> ByteArray(0)
            data.size > ACCOUNT_BASE_LEN && data.size != MULTISIG_LEN -> extendedTlv(
                data, ACCOUNT_BASE_LEN, ACCOUNT_TYPE_ACCOUNT, SgtRejection.TOKEN_ACCOUNT_TYPE_MISMATCH, SgtRejection.TOKEN_ACCOUNT_TYPE_MISMATCH,
            )
            else -> reject(SgtRejection.TOKEN_ACCOUNT_INVALID_LENGTH)
        }
        val frozen = when (u8(data, 108)) {
            1 -> false
            2 -> true
            0 -> reject(SgtRejection.TOKEN_ACCOUNT_NOT_INITIALIZED)
            else -> reject(SgtRejection.TOKEN_ACCOUNT_INVALID_STATE)
        }
        val mint = key(data, 0)
        val owner = key(data, 32)
        val amount = u64(data, 64)
        optionTag(data, 72, SgtRejection.TOKEN_ACCOUNT_INVALID_OPTION) // delegate
        val native = optionTag(data, 109, SgtRejection.TOKEN_ACCOUNT_INVALID_OPTION)
        optionTag(data, 129, SgtRejection.TOKEN_ACCOUNT_INVALID_OPTION) // close authority
        parseTlv(tlv)
        return ParsedTokenAccount(mint, owner, amount, frozen, native)
    }

    /** The TLV region of an extended account, after the padding and account-type checks. */
    private fun extendedTlv(data: ByteArray, baseLen: Int, expectedType: Int, paddingErr: SgtRejection, typeErr: SgtRejection): ByteArray {
        for (i in baseLen until ACCOUNT_TYPE_OFFSET) if (data[i].toInt() != 0) reject(paddingErr)
        if (u8(data, ACCOUNT_TYPE_OFFSET) != expectedType) reject(typeErr)
        return data.copyOfRange(TLV_START, data.size)
    }

    /**
     * Walks the TLV entries as Token-2022's `get_tlv_data_info` does (fewer than 2 bytes left, or
     * type 0, ends the walk), and stricter in two ways that only reject bytes Token-2022 never
     * writes: a repeated type is an error, and at most 32 entries are walked.
     */
    private fun parseTlv(tlv: ByteArray): Extensions {
        val entries = ArrayList<Pair<Int, ByteArray>>()
        var pos = 0
        while (tlv.size - pos >= 2) {
            val type = u16(tlv, pos)
            if (type == 0) break
            if (tlv.size - pos < TLV_HEADER_LEN) reject(SgtRejection.MALFORMED_TLV)
            val len = u16(tlv, pos + 2)
            val end = pos + TLV_HEADER_LEN + len
            if (end > tlv.size) reject(SgtRejection.MALFORMED_TLV)
            if (entries.any { it.first == type }) reject(SgtRejection.DUPLICATE_EXTENSION)
            if (entries.size >= MAX_TLV_ENTRIES) reject(SgtRejection.TOO_MANY_EXTENSIONS)
            entries += type to tlv.copyOfRange(pos + TLV_HEADER_LEN, end)
            pos = end
        }
        return Extensions(entries)
    }

    private fun optionKey(data: ByteArray, tagOffset: Int, keyOffset: Int, err: SgtRejection): Pubkey? =
        if (optionTag(data, tagOffset, err)) key(data, keyOffset) else null

    /** A `PodCOption` tag: exactly `[0,0,0,0]` (None) or `[1,0,0,0]` (Some). */
    private fun optionTag(data: ByteArray, offset: Int, err: SgtRejection): Boolean {
        val b0 = u8(data, offset)
        if (u8(data, offset + 1) != 0 || u8(data, offset + 2) != 0 || u8(data, offset + 3) != 0) reject(err)
        return when (b0) {
            0 -> false
            1 -> true
            else -> reject(err)
        }
    }

    // Lengths are validated before any of these runs, so the offsets are always in range.
    private fun u8(data: ByteArray, offset: Int): Int = data[offset].toInt() and 0xFF

    private fun u16(data: ByteArray, offset: Int): Int = u8(data, offset) or (u8(data, offset + 1) shl 8)

    private fun u64(data: ByteArray, offset: Int): ULong {
        var v = 0uL
        for (i in 7 downTo 0) v = (v shl 8) or (data[offset + i].toULong() and 0xFFuL)
        return v
    }

    private fun key(data: ByteArray, offset: Int): Pubkey = Pubkey(data.copyOfRange(offset, offset + Pubkey.BYTES))

    private fun reject(reason: SgtRejection): Nothing = throw SgtException(reason)
}
