package xyz.headsdown.core.keys

import java.math.BigInteger
import java.security.interfaces.ECPublicKey

/**
 * NIST P-256 (secp256r1) encodings needed to hand an Android Keystore signature to Solana's
 * secp256r1 precompile (SIMD-0075).
 *
 * Android Keystore `SHA256withECDSA` emits an ASN.1 DER `SEQUENCE { INTEGER r, INTEGER s }`
 * with arbitrary `s`. The precompile wants:
 *  - the signature as raw, fixed-width, big-endian `r ‖ s` (64 bytes),
 *  - `s` in the lower half of the group order (**low-S**), otherwise it rejects the signature
 *    (this closes the ECDSA malleability `(r, s) -> (r, n - s)`),
 *  - the public key in SEC1 **compressed** form (33 bytes: `0x02|0x03 ‖ X`).
 *
 * Everything here operates on public data (signatures, public keys), so constant-time
 * arithmetic is not required. All parsers are strict and throw [P256EncodingException]
 * on any malformed input instead of guessing.
 */
object P256 {
    /** Field prime p = 2^256 - 2^224 + 2^192 + 2^96 - 1. */
    val P: BigInteger = BigInteger("FFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFF", 16)

    /** Group order n. */
    val N: BigInteger = BigInteger("FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551", 16)

    /** floor(n / 2): the largest `s` the precompile accepts. */
    val HALF_N: BigInteger = N.shiftRight(1)

    /** Curve coefficient a = p - 3. */
    val A: BigInteger = P.subtract(BigInteger.valueOf(3))

    /** Curve coefficient b. */
    val B: BigInteger = BigInteger("5AC635D8AA3A93E7B3EBBD55769886BC651D06B0CC53B0F63BCE3C3E27D2604B", 16)

    const val SCALAR_BYTES = 32
    const val RAW_SIGNATURE_BYTES = 64
    const val COMPRESSED_PUBLIC_KEY_BYTES = 33
    const val UNCOMPRESSED_PUBLIC_KEY_BYTES = 65

    // Longest valid DER: 2 (SEQUENCE hdr) + 2 * (2 hdr + 33 content) = 72.
    private const val MAX_DER_SIGNATURE_BYTES = 72
    private const val TAG_SEQUENCE: Int = 0x30
    private const val TAG_INTEGER: Int = 0x02

    // ---------------------------------------------------------------------------------------
    // Signatures
    // ---------------------------------------------------------------------------------------

    /**
     * Strict DER -> raw `r ‖ s` (64 bytes, each scalar left-padded to 32 bytes).
     *
     * Rejects: wrong tags, long-form or inconsistent lengths, trailing bytes, negative or
     * non-minimally encoded integers, and scalars outside `[1, n-1]`.
     */
    fun derToRaw(der: ByteArray): ByteArray {
        if (der.size < 8 || der.size > MAX_DER_SIGNATURE_BYTES) {
            throw P256EncodingException("DER signature length ${der.size} out of range")
        }
        var pos = 0
        if (der[pos++].toInt() and 0xFF != TAG_SEQUENCE) throw P256EncodingException("expected SEQUENCE")
        val seqLen = der[pos++].toInt() and 0xFF
        if (seqLen >= 0x80) throw P256EncodingException("long-form length not allowed")
        if (seqLen != der.size - 2) throw P256EncodingException("SEQUENCE length mismatch or trailing bytes")

        val (r, afterR) = readDerInteger(der, pos)
        val (s, afterS) = readDerInteger(der, afterR)
        if (afterS != der.size) throw P256EncodingException("trailing bytes after s")

        requireScalarInRange(r, "r")
        requireScalarInRange(s, "s")

        val out = ByteArray(RAW_SIGNATURE_BYTES)
        r.toFixed32().copyInto(out, 0)
        s.toFixed32().copyInto(out, SCALAR_BYTES)
        return out
    }

    /** Raw `r ‖ s` -> DER. Used to interoperate with JCA verifiers (and in tests). */
    fun rawToDer(raw: ByteArray): ByteArray {
        requireRawSignature(raw)
        val r = derIntegerBytes(raw.copyOfRange(0, SCALAR_BYTES))
        val s = derIntegerBytes(raw.copyOfRange(SCALAR_BYTES, RAW_SIGNATURE_BYTES))
        val body = byteArrayOf(TAG_INTEGER.toByte(), r.size.toByte()) + r +
            byteArrayOf(TAG_INTEGER.toByte(), s.size.toByte()) + s
        return byteArrayOf(TAG_SEQUENCE.toByte(), body.size.toByte()) + body
    }

    /** True if `s <= n/2`, the only form the secp256r1 precompile accepts. */
    fun isLowS(raw: ByteArray): Boolean {
        requireRawSignature(raw)
        return scalarAt(raw, SCALAR_BYTES) <= HALF_N
    }

    /**
     * Returns a copy of [raw] with `s` replaced by `n - s` when `s > n/2`.
     * `(r, n - s)` verifies under the same key and message, so this never changes validity.
     */
    fun normalizeLowS(raw: ByteArray): ByteArray {
        requireRawSignature(raw)
        val s = scalarAt(raw, SCALAR_BYTES)
        if (s <= HALF_N) return raw.copyOf()
        val out = raw.copyOf()
        N.subtract(s).toFixed32().copyInto(out, SCALAR_BYTES)
        return out
    }

    // ---------------------------------------------------------------------------------------
    // Public keys
    // ---------------------------------------------------------------------------------------

    /** SEC1 compressed encoding of an affine point, after checking it lies on P-256. */
    fun compress(x: BigInteger, y: BigInteger): ByteArray {
        requireOnCurve(x, y)
        val out = ByteArray(COMPRESSED_PUBLIC_KEY_BYTES)
        out[0] = if (y.testBit(0)) 0x03 else 0x02
        x.toFixed32().copyInto(out, 1)
        return out
    }

    /** SEC1 compressed encoding of a JCA / Android Keystore EC public key. */
    fun compress(publicKey: ECPublicKey): ByteArray {
        val fieldSize = publicKey.params.curve.field.fieldSize
        if (fieldSize != 256) throw P256EncodingException("not a P-256 key (field size $fieldSize)")
        return compress(publicKey.w.affineX, publicKey.w.affineY)
    }

    /** SEC1 uncompressed (`0x04 ‖ X ‖ Y`, 65 bytes) -> compressed (33 bytes). */
    fun compressUncompressed(sec1: ByteArray): ByteArray {
        if (sec1.size != UNCOMPRESSED_PUBLIC_KEY_BYTES || sec1[0].toInt() != 0x04) {
            throw P256EncodingException("expected 65-byte SEC1 uncompressed point")
        }
        return compress(
            BigInteger(1, sec1.copyOfRange(1, 33)),
            BigInteger(1, sec1.copyOfRange(33, 65)),
        )
    }

    /** Compressed (33 bytes) -> affine `(x, y)`. p ≡ 3 (mod 4), so sqrt(a) = a^((p+1)/4). */
    fun decompress(compressed: ByteArray): Pair<BigInteger, BigInteger> {
        if (compressed.size != COMPRESSED_PUBLIC_KEY_BYTES) {
            throw P256EncodingException("expected 33-byte compressed point")
        }
        val prefix = compressed[0].toInt()
        if (prefix != 0x02 && prefix != 0x03) throw P256EncodingException("bad compressed prefix")
        val x = BigInteger(1, compressed.copyOfRange(1, COMPRESSED_PUBLIC_KEY_BYTES))
        if (x >= P) throw P256EncodingException("x not a field element")
        val rhs = curveRhs(x)
        var y = rhs.modPow(P.add(BigInteger.ONE).shiftRight(2), P)
        if (y.multiply(y).mod(P) != rhs) throw P256EncodingException("x is not on the curve")
        if (y.testBit(0) != (prefix == 0x03)) y = P.subtract(y)
        return x to y
    }

    fun isOnCurve(x: BigInteger, y: BigInteger): Boolean {
        if (x.signum() < 0 || x >= P || y.signum() < 0 || y >= P) return false
        return y.multiply(y).mod(P) == curveRhs(x)
    }

    // ---------------------------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------------------------

    private fun curveRhs(x: BigInteger): BigInteger =
        x.pow(3).add(A.multiply(x)).add(B).mod(P)

    private fun requireOnCurve(x: BigInteger, y: BigInteger) {
        if (!isOnCurve(x, y)) throw P256EncodingException("point is not on P-256")
    }

    private fun requireRawSignature(raw: ByteArray) {
        if (raw.size != RAW_SIGNATURE_BYTES) {
            throw P256EncodingException("raw signature must be 64 bytes, was ${raw.size}")
        }
    }

    private fun requireScalarInRange(v: BigInteger, name: String) {
        if (v.signum() <= 0 || v >= N) throw P256EncodingException("$name out of range [1, n-1]")
    }

    private fun scalarAt(raw: ByteArray, offset: Int): BigInteger =
        BigInteger(1, raw.copyOfRange(offset, offset + SCALAR_BYTES))

    /** Reads one DER INTEGER at [start]; returns its (positive) value and the next offset. */
    private fun readDerInteger(der: ByteArray, start: Int): Pair<BigInteger, Int> {
        var pos = start
        if (pos + 2 > der.size) throw P256EncodingException("truncated INTEGER header")
        if (der[pos++].toInt() and 0xFF != TAG_INTEGER) throw P256EncodingException("expected INTEGER")
        val len = der[pos++].toInt() and 0xFF
        if (len == 0) throw P256EncodingException("empty INTEGER")
        if (len >= 0x80) throw P256EncodingException("long-form length not allowed")
        if (len > SCALAR_BYTES + 1) throw P256EncodingException("INTEGER too long")
        if (pos + len > der.size) throw P256EncodingException("truncated INTEGER")
        val first = der[pos].toInt() and 0xFF
        if (first and 0x80 != 0) throw P256EncodingException("negative INTEGER")
        if (len > 1 && first == 0x00 && (der[pos + 1].toInt() and 0x80) == 0) {
            throw P256EncodingException("non-minimal INTEGER encoding")
        }
        val value = BigInteger(1, der.copyOfRange(pos, pos + len))
        return value to pos + len
    }

    /** Minimal two's-complement DER INTEGER content for an unsigned 32-byte scalar. */
    private fun derIntegerBytes(unsigned: ByteArray): ByteArray {
        var i = 0
        while (i < unsigned.size - 1 && unsigned[i].toInt() == 0) i++
        val stripped = unsigned.copyOfRange(i, unsigned.size)
        return if (stripped[0].toInt() and 0x80 != 0) byteArrayOf(0) + stripped else stripped
    }

    internal fun BigInteger.toFixed32(): ByteArray {
        require(signum() >= 0 && bitLength() <= 256) { "scalar does not fit in 32 bytes" }
        val bytes = toByteArray() // big-endian, may carry a leading sign byte
        val out = ByteArray(SCALAR_BYTES)
        val copy = minOf(bytes.size, SCALAR_BYTES)
        bytes.copyInto(out, SCALAR_BYTES - copy, bytes.size - copy, bytes.size)
        return out
    }
}

class P256EncodingException(message: String) : IllegalArgumentException(message)
