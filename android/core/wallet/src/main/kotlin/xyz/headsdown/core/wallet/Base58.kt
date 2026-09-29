package xyz.headsdown.core.wallet

import java.math.BigInteger

/** Bitcoin-alphabet Base58, as used for Solana addresses and transaction signatures. */
object Base58 {
    private const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    private val FIFTY_EIGHT = BigInteger.valueOf(58)
    private val INDEX = IntArray(128) { -1 }.also { idx -> ALPHABET.forEachIndexed { i, c -> idx[c.code] = i } }

    fun encode(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val leadingZeros = bytes.takeWhile { it.toInt() == 0 }.size
        var n = BigInteger(1, bytes)
        val sb = StringBuilder()
        while (n.signum() > 0) {
            val (q, r) = n.divideAndRemainder(FIFTY_EIGHT)
            sb.append(ALPHABET[r.toInt()])
            n = q
        }
        repeat(leadingZeros) { sb.append('1') }
        return sb.reverse().toString()
    }

    /** Strict decode: any character outside the alphabet throws [IllegalArgumentException]. */
    fun decode(text: String): ByteArray {
        if (text.isEmpty()) return ByteArray(0)
        var n = BigInteger.ZERO
        for (c in text) {
            val digit = if (c.code < 128) INDEX[c.code] else -1
            require(digit >= 0) { "invalid Base58 character" }
            n = n.multiply(FIFTY_EIGHT).add(BigInteger.valueOf(digit.toLong()))
        }
        val leadingOnes = text.takeWhile { it == '1' }.length
        val body = n.toByteArray().let { if (it.size > 1 && it[0].toInt() == 0) it.copyOfRange(1, it.size) else it }
        return if (n.signum() == 0) ByteArray(leadingOnes) else ByteArray(leadingOnes) + body
    }
}
