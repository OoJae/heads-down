package xyz.headsdown.core.chain.ix

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test
import xyz.headsdown.core.chain.Golden
import xyz.headsdown.core.chain.HeadsDownProgram
import xyz.headsdown.core.chain.arr
import xyz.headsdown.core.chain.bool
import xyz.headsdown.core.chain.hex
import xyz.headsdown.core.chain.hexBytes
import xyz.headsdown.core.chain.int
import xyz.headsdown.core.chain.obj
import xyz.headsdown.core.chain.pubkey
import xyz.headsdown.core.chain.rfc6979Sign
import xyz.headsdown.core.chain.str
import xyz.headsdown.core.chain.u64
import xyz.headsdown.core.keys.HeartbeatPreimage
import xyz.headsdown.core.keys.P256
import xyz.headsdown.core.keys.PlanPreimage
import xyz.headsdown.core.keys.RigMessage
import xyz.headsdown.core.keys.RigMessageKind
import xyz.headsdown.core.keys.ShiftEndReason
import xyz.headsdown.core.keys.ShiftPlan
import xyz.headsdown.core.keys.ShiftSignalPreimage
import xyz.headsdown.core.keys.SignedRigMessage
import java.security.MessageDigest

/**
 * `programs/heads-down/vectors/messages.json`: the phone's preimages, digests, low-S signatures
 * and Secp256r1SigVerify data for every message kind (including the v1.1 `unlocked` BREAK), with
 * the RFC 6979 test key, against the program side's bytes.
 */
class GoldenMessagesTest {

    private val root = Golden.messages
    private val messages = root.arr("messages").map { it.jsonObject }
    private val key = root.obj("p256_key")

    private fun preimage(m: JsonObject): RigMessage {
        val f = m.obj("fields")
        val programId = root.pubkey("program_id").bytes
        val rig = root.pubkey("rig").bytes
        return when (RigMessageKind.fromWire(m.int("kind"))) {
            RigMessageKind.HEARTBEAT -> HeartbeatPreimage(programId, rig, f.u64("counter"), f.u64("shift_id"), f.u64("round_id"), f.int("lease_rounds"))
            RigMessageKind.BREAK, RigMessageKind.FREEZE -> ShiftSignalPreimage(
                programId, rig, RigMessageKind.fromWire(m.int("kind")), f.u64("counter"), f.u64("shift_id"), ShiftEndReason.fromWire(f.int("reason")),
            )
            RigMessageKind.PLAN -> {
                val p = f.obj("plan")
                PlanPreimage(
                    programId, rig, f.u64("counter"),
                    ShiftPlan(
                        p.u64("max_ev_cost"), p.u64("dig_lamports"), p.int("split"), p.int("solo"), p.int("lease"), p.int("flags"),
                        p.str("window_start").toLong(), p.str("window_end").toLong(),
                    ),
                )
            }
        }
    }

    @Test
    fun `the vectors bind this program and alice's rig`() {
        assertEquals(HeadsDownProgram.ID, root.pubkey("program_id"))
        assertEquals(HeadsDownProgram.rig(root.pubkey("rig_authority")).address, root.pubkey("rig"))
        // The public key is the private test scalar times G, compressed the phone's way.
        val q = org.bouncycastle.crypto.ec.CustomNamedCurves.getByName("secp256r1").g
            .multiply(java.math.BigInteger(key.str("private_scalar_hex"), 16)).normalize()
        assertEquals(key.str("public_key_compressed_hex"), P256.compress(q.affineXCoord.toBigInteger(), q.affineYCoord.toBigInteger()).hex())
        // Every kind, and both v1.1 BREAK reasons that cool vs break (pickup 1, unlocked 8).
        assertEquals(setOf(1, 2, 3, 4), messages.map { it.int("kind") }.toSet())
        assertEquals(setOf(1, 8), messages.filter { it.int("kind") == 2 }.map { it.obj("fields").int("reason") }.toSet())
    }

    @Test
    fun `preimages, digests, signatures and precompile data are byte-identical`() {
        for (m in messages) {
            val name = m.str("name")
            val pre = preimage(m)
            assertEquals(name, m.int("preimage_len"), pre.preimage().size)
            assertEquals(name, m.str("preimage_hex"), pre.preimage().hex())
            val digest = MessageDigest.getInstance("SHA-256").digest(pre.preimage())
            assertEquals(name, m.str("message_sha256_hex"), digest.hex())
            assertEquals(name, digest.hex(), pre.digest().hex())
            // SHA256withECDSA over the 32-byte digest, deterministic (RFC 6979), then low-S.
            val rfc = rfc6979Sign(key.str("private_scalar_hex"), pre.digest())
            assertEquals(name, m.str("signature_rfc6979_hex"), rfc.hex())
            val low = P256.normalizeLowS(rfc)
            assertEquals(name, m.str("signature_low_s_hex"), low.hex())
            assertEquals(name, m.bool("normalized"), !low.contentEquals(rfc))
            val signed = SignedRigMessage(pre, low, hexBytes(key.str("public_key_compressed_hex")))
            val ix = Secp256r1Instructions.verify(listOf(signed))
            val golden = m.obj("precompile_instruction")
            assertEquals(name, golden.str("program_id"), ix.programId.toBase58())
            assertEquals(name, golden.int("data_len"), ix.dataSize)
            assertEquals(name, golden.str("data_hex"), ix.data.hex())
            // The strict decoder reads the program's bytes back into the same message.
            assertEquals(name, pre, RigMessage.decode(hexBytes(m.str("preimage_hex"))))
        }
    }
}
