package xyz.headsdown.core.chain

import com.funkatronics.salt.isOnCurve
import java.security.MessageDigest

/** A program-derived address and the canonical bump that produced it. */
data class ProgramAddress(val address: Pubkey, val bump: Int)

/**
 * `create_program_address` / `find_program_address`, byte-compatible with
 * `solana-address` / `solana-program`:
 *
 * - `address = SHA-256(seed_0 ‖ … ‖ seed_n ‖ program_id ‖ "ProgramDerivedAddress")`,
 *   rejected if it decodes to a point on the Ed25519 curve;
 * - at most [MAX_SEEDS] seeds (the bump counts as one), each at most [MAX_SEED_LEN] bytes;
 * - `find` tries bumps 255 down to **1** (Solana's loop never tries 0).
 *
 * The on-curve test is `com.funkatronics.salt.isOnCurve`, the TweetNaCl port that
 * web3-solana (a dependency of MWA 2.2.0) uses for its own PDA derivation. It has the same
 * acceptance set as curve25519-dalek's `CompressedEdwardsY::decompress` used on-chain
 * (y is reduced mod p; the sign bit is ignored). The unit tests cross-check it against
 * BouncyCastle's RFC 8032 decoder, web3-solana, and addresses from the Agave CLI.
 */
object Pda {
    const val MAX_SEEDS = 16
    const val MAX_SEED_LEN = 32
    private val MARKER = "ProgramDerivedAddress".toByteArray(Charsets.US_ASCII)

    /** The PDA for exactly these seeds, or null when the hash lands on the curve. */
    fun createProgramAddress(seeds: List<ByteArray>, programId: Pubkey): Pubkey? {
        require(seeds.size <= MAX_SEEDS) { "at most $MAX_SEEDS seeds" }
        val sha = MessageDigest.getInstance("SHA-256")
        for (seed in seeds) {
            require(seed.size <= MAX_SEED_LEN) { "seed longer than $MAX_SEED_LEN bytes" }
            sha.update(seed)
        }
        sha.update(programId.bytes)
        sha.update(MARKER)
        val hash = sha.digest()
        return if (isOnCurve(hash)) null else Pubkey(hash)
    }

    /** The canonical (highest-bump) PDA for [seeds]. */
    fun find(seeds: List<ByteArray>, programId: Pubkey): ProgramAddress {
        require(seeds.size < MAX_SEEDS) { "at most ${MAX_SEEDS - 1} seeds plus the bump" }
        for (bump in 255 downTo 1) {
            val address = createProgramAddress(seeds + byteArrayOf(bump.toByte()), programId)
            if (address != null) return ProgramAddress(address, bump)
        }
        // Probability ~2^-255: unreachable in practice, but never loop or return garbage.
        throw IllegalStateException("no viable bump for these seeds")
    }

    /** Ed25519 point decompression succeeds for these 32 bytes. */
    fun isOnCurve(bytes: ByteArray): Boolean {
        require(bytes.size == Pubkey.BYTES)
        return bytes.copyOf().isOnCurve()
    }
}

/** u64 little-endian seed bytes (e.g. `round_id`, `shift_id`). */
fun ULong.leBytes(): ByteArray = ByteArray(8) { i -> (this shr (8 * i)).toByte() }
