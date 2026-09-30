package xyz.headsdown.core.chain

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import xyz.headsdown.core.chain.registrar.HdRegPreimage

/**
 * Registrar vouchers in the registrar's exact 223-byte Ed25519SigVerify format, signed with a
 * public test seed (the `[5; 32]` seed of registrar/src/voucher.rs tests and vectors/registrar.json).
 */
object TestVouchers {
    val DEFAULT_SEED = ByteArray(32) { 5 }

    fun registrarKey(seed: ByteArray = DEFAULT_SEED): Pubkey = Pubkey(Ed25519PrivateKeyParameters(seed, 0).generatePublicKey().encoded)

    fun instructionData(
        authority: Pubkey,
        p256: ByteArray,
        level: Int,
        expirySlot: ULong,
        seed: ByteArray = DEFAULT_SEED,
        programId: Pubkey = HeadsDownProgram.ID,
    ): ByteArray {
        val message = HdRegPreimage.build(programId, authority, p256, level, expirySlot)
        val key = Ed25519PrivateKeyParameters(seed, 0)
        val signature = Ed25519Signer().apply { init(true, key); update(message, 0, message.size) }.generateSignature()
        val header = hexBytes("01003000ffff1000ffff70006f00ffff")
        return header + key.generatePublicKey().encoded + signature + message
    }
}
