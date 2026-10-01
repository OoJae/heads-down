package xyz.headsdown.core.chain.registrar

import xyz.headsdown.core.chain.HeadsDownProgram
import xyz.headsdown.core.chain.Pubkey
import xyz.headsdown.core.chain.WellKnown
import xyz.headsdown.core.chain.ix.RegistrarAttestation
import xyz.headsdown.core.chain.tx.Instruction
import xyz.headsdown.core.keys.P256
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A registrar voucher the client refuses to put into a transaction. The message is fixed text. */
class VoucherException(message: String) : IllegalArgumentException(message)

/**
 * The registrar's `HDreg` preimage (INTERFACE v1.1 §4.2, registrar N2), 111 bytes, signed raw
 * with Ed25519 (not SHA-256 first):
 * `"HDreg"(5) | program_id(32) | authority(32) | p256_pubkey(33) | level u8 | expiry_slot u64`.
 */
object HdRegPreimage {
    const val BYTES = 111
    private val DOMAIN = "HDreg".toByteArray(Charsets.US_ASCII)

    fun build(programId: Pubkey, authority: Pubkey, p256Pubkey: ByteArray, level: Int, expirySlot: ULong): ByteArray {
        require(p256Pubkey.size == P256.COMPRESSED_PUBLIC_KEY_BYTES) { "p256 key must be 33-byte compressed" }
        require(level in 0..0xFF) { "level is a u8" }
        return ByteBuffer.allocate(BYTES).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(DOMAIN)
            put(programId.bytes)
            put(authority.bytes)
            put(p256Pubkey)
            put(level.toByte())
            putLong(expirySlot.toLong())
            check(remaining() == 0)
        }.array()
    }
}

/**
 * A registrar voucher, checked against the client's own values before it can reach a
 * transaction: a voucher the program would refuse fails the **whole** clock-in (INTERFACE v1.1
 * §4.2: every failure is `InvalidAttestation`), so anything doubtful registers the rig as a guest.
 *
 * The instruction is the registrar's 223-byte `Ed25519SigVerify` format (registrar N3): header
 * `01003000ffff1000ffff70006f00ffff`, registrar key at 16, signature at 48, message at 112, every
 * instruction index `0xFFFF`.
 */
class RegistrarVoucher private constructor(
    val authority: Pubkey,
    p256Pubkey: ByteArray,
    /** 1 TEE or 2 StrongBox. Level-0 vouchers are never built: they are refused on-chain. */
    val level: Int,
    val expirySlot: ULong,
    /** The Ed25519 key that signed it; must equal `Config.registrar` (@40) on-chain. */
    val registrar: Pubkey,
    instructionData: ByteArray,
) {
    private val key = p256Pubkey.copyOf()
    private val data = instructionData.copyOf()

    val p256Pubkey: ByteArray get() = key.copyOf()
    val instructionData: ByteArray get() = data.copyOf()

    /** The `Ed25519SigVerify` instruction to place in the same transaction. */
    val instruction: Instruction get() = Instruction(WellKnown.ED25519_SIG_VERIFY, emptyList(), data)

    /** The `register_rig` / `rotate_key` fields, with the Ed25519 instruction at top-level [ed25519Ix]. */
    fun attestation(ed25519Ix: Int): RegistrarAttestation = RegistrarAttestation(ed25519Ix, 0, level, expirySlot)

    /** This voucher attests exactly [p256] for [wallet]. */
    fun covers(wallet: Pubkey, p256: ByteArray): Boolean = authority == wallet && key.contentEquals(p256)

    /**
     * Usable in a transaction expected to land by [slot]: the program requires
     * `expiry_slot > Clock.slot`, so a voucher within [marginSlots] of expiring is not used.
     */
    fun usableAt(slot: ULong, marginSlots: ULong = DEFAULT_EXPIRY_MARGIN_SLOTS): Boolean =
        slot <= ULong.MAX_VALUE - marginSlots && expirySlot > slot + marginSlots

    override fun toString(): String = "RegistrarVoucher(level=$level, expirySlot=$expirySlot, registrar=$registrar)"

    companion object {
        const val DATA_BYTES = 223
        const val PUBKEY_OFFSET = 16
        const val SIGNATURE_OFFSET = 48
        const val MESSAGE_OFFSET = 112

        /** About 4 minutes of 400 ms slots between building the clock-in and it landing. */
        const val DEFAULT_EXPIRY_MARGIN_SLOTS: ULong = 600uL

        private val HEADER = byteArrayOf(
            0x01, 0x00, 0x30, 0x00, 0xFF.toByte(), 0xFF.toByte(), 0x10, 0x00,
            0xFF.toByte(), 0xFF.toByte(), 0x70, 0x00, 0x6F, 0x00, 0xFF.toByte(), 0xFF.toByte(),
        )

        /**
         * Checks [instructionData] as the registrar's voucher for exactly ([authority], [p256Pubkey],
         * [level], [expirySlot]) under [programId]: length, the registrar's fixed header (so no
         * offset can point outside the instruction), and the signed message rebuilt from the
         * phone's own values. The registrar key is checked against `Config.registrar` by the
         * composer. The Ed25519 signature itself is verified by the Ed25519SigVerify precompile in
         * the same transaction, which fails atomically (no Ed25519 verifier ships on API 31-32).
         *
         * @throws VoucherException on any mismatch (the message never contains the data).
         */
        fun verify(
            instructionData: ByteArray,
            authority: Pubkey,
            p256Pubkey: ByteArray,
            level: Int,
            expirySlot: ULong,
            programId: Pubkey = HeadsDownProgram.ID,
        ): RegistrarVoucher {
            if (level !in 1..2) throw VoucherException("voucher level must be 1 or 2")
            if (p256Pubkey.size != P256.COMPRESSED_PUBLIC_KEY_BYTES) throw VoucherException("voucher key is not a compressed P-256 key")
            if (instructionData.size != DATA_BYTES) throw VoucherException("voucher instruction must be $DATA_BYTES bytes")
            if (!instructionData.copyOfRange(0, HEADER.size).contentEquals(HEADER)) throw VoucherException("voucher instruction header is not the registrar's")
            val message = instructionData.copyOfRange(MESSAGE_OFFSET, DATA_BYTES)
            val expected = HdRegPreimage.build(programId, authority, p256Pubkey, level, expirySlot)
            if (!message.contentEquals(expected)) throw VoucherException("voucher message is not for this program, wallet, key, level and expiry")
            val registrar = Pubkey(instructionData.copyOfRange(PUBKEY_OFFSET, SIGNATURE_OFFSET))
            return RegistrarVoucher(authority, p256Pubkey, level, expirySlot, registrar, instructionData)
        }
    }
}
