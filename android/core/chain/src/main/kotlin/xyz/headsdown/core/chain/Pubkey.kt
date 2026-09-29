package xyz.headsdown.core.chain

import xyz.headsdown.core.wallet.Base58

/** A 32-byte Solana address. Immutable; compares by content. */
class Pubkey(bytes: ByteArray) : Comparable<Pubkey> {
    private val value: ByteArray = bytes.copyOf()

    init {
        require(value.size == BYTES) { "a Solana address is 32 bytes, was ${value.size}" }
    }

    val bytes: ByteArray get() = value.copyOf()

    fun toBase58(): String = Base58.encode(value)

    /** Content comparison without exposing a copy. */
    fun contentEquals(other: ByteArray): Boolean = value.contentEquals(other)

    internal fun byteAt(index: Int): Byte = value[index]

    override fun equals(other: Any?): Boolean = other is Pubkey && value.contentEquals(other.value)

    override fun hashCode(): Int = value.contentHashCode()

    /** Unsigned lexicographic byte order (what Solana's `Pubkey: Ord` uses). */
    override fun compareTo(other: Pubkey): Int {
        for (i in 0 until BYTES) {
            val c = (value[i].toInt() and 0xFF) - (other.value[i].toInt() and 0xFF)
            if (c != 0) return c
        }
        return 0
    }

    override fun toString(): String = toBase58()

    companion object {
        const val BYTES = 32

        val DEFAULT = Pubkey(ByteArray(BYTES))

        /** Strict: any non-alphabet character or a length other than 32 bytes throws. */
        fun fromBase58(text: String): Pubkey {
            require(text.length in 32..44) { "not a base58 Solana address" }
            return Pubkey(Base58.decode(text))
        }
    }
}
