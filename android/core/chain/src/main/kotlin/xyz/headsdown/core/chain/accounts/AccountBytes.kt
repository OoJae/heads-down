package xyz.headsdown.core.chain.accounts

import xyz.headsdown.core.chain.Pubkey

/** The account is not the type, owner, size or version the caller expects. */
class AccountLayoutException(message: String) : IllegalArgumentException(message)

/**
 * Bounds-checked little-endian reads over account data. Every read checks its range first and
 * throws [AccountLayoutException] instead of an index error, so a hostile or truncated account
 * can only fail a decode, never crash the caller.
 */
internal class AccountBytes(private val data: ByteArray) {
    val size: Int get() = data.size

    private fun check(offset: Int, len: Int) {
        if (offset < 0 || len < 0 || offset > data.size - len) {
            throw AccountLayoutException("read of $len bytes at $offset is outside ${data.size}")
        }
    }

    fun u8(offset: Int): Int {
        check(offset, 1)
        return data[offset].toInt() and 0xFF
    }

    fun u16(offset: Int): Int {
        check(offset, 2)
        return (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
    }

    fun u32(offset: Int): Long {
        check(offset, 4)
        var v = 0L
        for (i in 3 downTo 0) v = (v shl 8) or (data[offset + i].toLong() and 0xFF)
        return v
    }

    fun u64(offset: Int): ULong {
        check(offset, 8)
        var v = 0uL
        for (i in 7 downTo 0) v = (v shl 8) or (data[offset + i].toULong() and 0xFFuL)
        return v
    }

    fun i64(offset: Int): Long = u64(offset).toLong()

    fun bytes(offset: Int, len: Int): ByteArray {
        check(offset, len)
        return data.copyOfRange(offset, offset + len)
    }

    fun pubkey(offset: Int): Pubkey = Pubkey(bytes(offset, Pubkey.BYTES))

    /** `Option<Address>` encoded as 32 zero bytes = None (INTERFACE convention). */
    fun optionalPubkey(offset: Int): Pubkey? = pubkey(offset).takeUnless { it == Pubkey.DEFAULT }
}
