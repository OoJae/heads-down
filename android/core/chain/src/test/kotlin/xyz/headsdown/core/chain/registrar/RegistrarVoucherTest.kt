package xyz.headsdown.core.chain.registrar

import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.headsdown.core.chain.Golden
import xyz.headsdown.core.chain.HeadsDownProgram
import xyz.headsdown.core.chain.Pubkey
import xyz.headsdown.core.chain.WellKnown
import xyz.headsdown.core.chain.arr
import xyz.headsdown.core.chain.hex
import xyz.headsdown.core.chain.hexBytes
import xyz.headsdown.core.chain.int
import xyz.headsdown.core.chain.ix.HeadsDownInstructions
import xyz.headsdown.core.chain.obj
import xyz.headsdown.core.chain.pubkey
import xyz.headsdown.core.chain.str
import xyz.headsdown.core.chain.u64
import kotlinx.serialization.json.jsonObject

/** `programs/heads-down/vectors/registrar.json`: the HDreg preimage and the 223-byte voucher. */
class RegistrarVoucherTest {

    private val root = Golden.registrar
    private val v = root.obj("voucher")
    private val authority = v.pubkey("authority")
    private val p256 = hexBytes(v.str("p256_pubkey_hex"))
    private val level = v.int("level")
    private val expiry = v.u64("expiry_slot")
    private val data = hexBytes(root.obj("ed25519_instruction").str("data_hex"))

    @Test
    fun `the HDreg preimage is the registrar's 111 bytes`() {
        val pre = HdRegPreimage.build(HeadsDownProgram.ID, authority, p256, level, expiry)
        assertEquals(v.int("preimage_len"), pre.size)
        assertEquals(v.str("preimage_hex"), pre.hex())
    }

    @Test
    fun `the golden voucher verifies and is passed through unchanged`() {
        val voucher = RegistrarVoucher.verify(data, authority, p256, level, expiry)
        assertEquals(root.obj("registrar_key").pubkey("pubkey"), voucher.registrar)
        assertEquals(root.obj("ed25519_instruction").str("header_hex"), data.copyOfRange(0, 16).hex())
        assertEquals(WellKnown.ED25519_SIG_VERIFY, voucher.instruction.programId)
        assertTrue(voucher.instruction.accounts.isEmpty())
        assertEquals(data.hex(), voucher.instruction.data.hex())
        assertTrue(voucher.covers(authority, p256))
        assertFalse(voucher.covers(Pubkey(ByteArray(32) { 9 }), p256))
        // registrar.json's register_rig transaction: voucher at 0, register_rig at 1.
        val tx = root.obj("register_rig").arr("transaction").map { it.jsonObject }
        val register = tx.single { it.int("index") == 1 }
        assertEquals(register.str("data_hex"), HeadsDownInstructions.registerRig(authority, p256, voucher.attestation(0)).data.hex())
    }

    @Test
    fun `the golden voucher is a valid RFC 8032 signature by the registrar key`() {
        // The phone leaves the signature to the Ed25519SigVerify precompile; the test proves the
        // golden bytes it passes through are genuinely signed (BouncyCastle's verifier).
        val pk = data.copyOfRange(16, 48)
        val sig = data.copyOfRange(48, 112)
        val msg = data.copyOfRange(112, 223)
        fun verifies(s: ByteArray) = Ed25519Signer().apply { init(false, Ed25519PublicKeyParameters(pk, 0)); update(msg, 0, msg.size) }.verifySignature(s)
        assertTrue(verifies(sig))
        assertFalse(verifies(sig.copyOf().also { it[10] = (it[10].toInt() xor 1).toByte() }))
        assertEquals(root.obj("registrar_key").str("pubkey_hex"), pk.hex())
        // The test helper signs exactly like the registrar (same seed, same bytes).
        assertEquals(data.hex(), xyz.headsdown.core.chain.TestVouchers.instructionData(authority, p256, level, expiry).hex())
    }

    @Test
    fun `anything the program would refuse is refused before it reaches a transaction`() {
        fun refused(block: () -> Unit) = assertThrows(VoucherException::class.java) { block() }
        // Level 0 is refused on-chain (INTERFACE v1.1 §4.2): register without a voucher instead.
        refused { RegistrarVoucher.verify(data, authority, p256, 0, expiry) }
        refused { RegistrarVoucher.verify(data, authority, p256, 3, expiry) }
        // A different level, expiry, wallet, key or program than the signed message.
        refused { RegistrarVoucher.verify(data, authority, p256, 1, expiry) }
        refused { RegistrarVoucher.verify(data, authority, p256, level, expiry + 1uL) }
        refused { RegistrarVoucher.verify(data, Pubkey(ByteArray(32) { 7 }), p256, level, expiry) }
        refused { RegistrarVoucher.verify(data, authority, hexBytes("0360fed4ba255a9d31c961eb74c6356d68c049b8923b61fa6ce669622e60f29fb6"), level, expiry) }
        refused { RegistrarVoucher.verify(data, authority, p256, level, expiry, programId = Pubkey(ByteArray(32) { 1 })) }
        // Tampering: the signed message, the header (offsets), the length.
        refused { RegistrarVoucher.verify(data.copyOf().also { it[150] = (it[150].toInt() xor 1).toByte() }, authority, p256, level, expiry) }
        refused { RegistrarVoucher.verify(data.copyOf().also { it[2] = 0x31 }, authority, p256, level, expiry) }
        refused { RegistrarVoucher.verify(data.copyOf().also { it[5] = 0x00 }, authority, p256, level, expiry) }
        refused { RegistrarVoucher.verify(data.copyOf(222), authority, p256, level, expiry) }
        refused { RegistrarVoucher.verify(data + 0, authority, p256, level, expiry) }
        // The signing key is carried out so the composer can pin it to Config.registrar.
        val other = RegistrarVoucher.verify(data, authority, p256, level, expiry)
        assertEquals(Pubkey(data.copyOfRange(16, 48)), other.registrar)
    }

    @Test
    fun `expiry is checked with a safety margin`() {
        val voucher = RegistrarVoucher.verify(data, authority, p256, level, expiry)
        assertTrue(voucher.usableAt(expiry - 601uL))
        assertFalse(voucher.usableAt(expiry - 600uL))
        assertFalse(voucher.usableAt(expiry))
        assertFalse(voucher.usableAt(ULong.MAX_VALUE))
        assertTrue(voucher.usableAt(expiry - 1uL, marginSlots = 0uL))
    }
}
