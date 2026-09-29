package xyz.headsdown.core.keys

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.headsdown.core.keys.TestCrypto.hex
import xyz.headsdown.core.keys.TestCrypto.toHex
import java.io.File
import java.math.BigInteger

/**
 * Golden vectors for the signed-message formats, shared with the Rust side
 * (`src/test/resources/vectors.json`).
 *
 * Every vector is produced from fixed inputs with a fixed key and **RFC 6979 deterministic**
 * ECDSA, so any implementation can reproduce `preimage`, `message` and both signatures byte for
 * byte. The committed file must equal the generator's output exactly; regenerate it with
 * `HD_WRITE_VECTORS=1 ./gradlew :core:keys:testDebugUnitTest`.
 */
class GoldenVectorsTest {

    /** RFC 6979 A.2.5 private key: public test material, never a real rig key. */
    private val privateScalar = BigInteger("C9AFA9D845BA75166B5C215767B1D6934E50C3DB36E89B127B8A622B120F6721", 16)
    private val programIdB58 = "HDn4vgLWFLLdexKEwfZwRHjWtizNvdqFteLbMsE67F9p"

    /** Rig PDA `[b"rig", 7xKXtg2CW87d97TXJSDpbD5jBkheTqA83TZRuJosgAsU]` under the program (solana CLI). */
    private val rigB58 = "2gQpad6BYLts9d8qHbRRoeYRJnBLUftiFmMM38cW4zco"
    private val programId = TestCrypto.base58Address(programIdB58)
    private val rig = TestCrypto.base58Address(rigB58)

    private val messages: List<Pair<String, RigMessage>> = listOf(
        "heartbeat_lease_1" to HeartbeatPreimage(programId, rig, counter = 42uL, shiftId = 7uL, roundId = 422_593uL, leaseRounds = 1),
        "heartbeat_lease_3" to HeartbeatPreimage(programId, rig, counter = 43uL, shiftId = 7uL, roundId = 422_594uL, leaseRounds = 3),
        "heartbeat_u64_max" to HeartbeatPreimage(programId, rig, ULong.MAX_VALUE, ULong.MAX_VALUE, ULong.MAX_VALUE, 2),
        "break_pickup" to ShiftSignalPreimage(programId, rig, RigMessageKind.BREAK, 44uL, 7uL, ShiftEndReason.PICKUP),
        "break_screen_on" to ShiftSignalPreimage(programId, rig, RigMessageKind.BREAK, 45uL, 7uL, ShiftEndReason.SCREEN_ON),
        "freeze" to ShiftSignalPreimage(programId, rig, RigMessageKind.FREEZE, 46uL, 7uL, ShiftEndReason.FREEZE),
        "plan_steady" to PlanPreimage(
            programId, rig, counter = 41uL,
            plan = ShiftPlan(530_000_000uL, 1_000_000uL, 4, 0, 1, 0, 1_790_000_000L, 1_790_028_800L),
        ),
        "plan_focus_only" to PlanPreimage(
            programId, rig, counter = 47uL,
            plan = ShiftPlan(0uL, 0uL, 0, 0, 3, ShiftPlan.FLAG_FOCUS_ONLY, 1_790_000_000L, 1_790_005_400L),
        ),
    )

    @Test
    fun `bouncy castle RFC 6979 reproduces the published A-2-5 vector`() {
        // RFC 6979 A.2.5, SHA-256, message "sample" (published s is high-S).
        val sig = TestCrypto.signRfc6979(privateScalar, "sample".toByteArray())
        assertEquals(
            "efd48b2aacb6a8fd1140dd9cd45e81d69d2c877b56aaf991c34d0ea84eaf3716" +
                "f7cb1c942d657c41d436c7a1b6e29f65f3e900dbb9aff4064dc4ab2f843acda8",
            sig.toHex(),
        )
        assertEquals(
            "0360fed4ba255a9d31c961eb74c6356d68c049b8923b61fa6ce669622e60f29fb6",
            TestCrypto.compressedPublicKey(privateScalar).toHex(),
        )
    }

    @Test
    fun `committed vectors json equals the generator output`() {
        val generated = render()
        val file = File(System.getProperty("user.dir"), "src/test/resources/vectors.json")
        if (System.getenv("HD_WRITE_VECTORS") == "1") file.writeText(generated)
        val committed = javaClass.getResource("/vectors.json")?.readText()
            ?: error("vectors.json missing; run with HD_WRITE_VECTORS=1")
        assertEquals(committed, generated)
    }

    @Test
    fun `every committed vector verifies independently`() {
        val root = Json.parseToJsonElement(javaClass.getResource("/vectors.json")!!.readText()).jsonObject
        assertEquals(programId.toHex(), root.str("program_id_hex"))
        assertEquals(rig.toHex(), root.str("rig_hex"))
        val key = root["key"]!!.jsonObject
        val pub = TestCrypto.publicKeyFromCompressed(hex(key.str("public_key_compressed_hex")))
        val vectors = root["vectors"]!!.jsonArray.map { it.jsonObject }
        assertEquals(messages.size, vectors.size)
        for (v in vectors) {
            val name = v.str("name")
            val preimage = hex(v.str("preimage_hex"))
            val message = hex(v.str("message_hex"))
            val lowS = hex(v.str("signature_hex"))
            val rfc = hex(v.str("signature_rfc6979_hex"))
            // Rebuilt from the JSON fields alone (what the Rust side does), byte for byte.
            assertEquals(name, preimage.toHex(), fromFields(v).preimage().toHex())
            // Structure: decodes as the stated kind, sizes are the INTERFACE sizes.
            val decoded = RigMessage.decode(preimage)
            assertEquals(name, v["kind"]!!.jsonPrimitive.int, decoded.kind.wire)
            assertArrayEquals(name, RigMessageFormat.sha256(preimage), message)
            assertEquals(name, v["preimage_len"]!!.jsonPrimitive.int, preimage.size)
            // Signatures: the low-S one is what the precompile accepts; both verify under JCA.
            assertTrue(name, P256.isLowS(lowS))
            assertArrayEquals(name, P256.normalizeLowS(rfc), lowS)
            assertTrue(name, TestCrypto.verifyRaw(pub, message, lowS))
            assertTrue(name, TestCrypto.verifyRaw(pub, message, rfc))
            assertFalse(name, TestCrypto.verifyRaw(pub, preimage, lowS))
        }
    }

    // ------------------------------------------------------------------------------ generator

    private fun render(): String {
        val pub = TestCrypto.compressedPublicKey(privateScalar)
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"format\": \"heads-down/rig-message-vectors\",\n")
        sb.append("  \"version\": 1,\n")
        sb.append("  \"spec\": \"programs/heads-down/INTERFACE.md, Signed P-256 messages\",\n")
        sb.append("  \"notes\": [\n")
        sb.append("    \"All integers little-endian. u64/i64 values are decimal strings; u8 values are JSON numbers.\",\n")
        sb.append("    \"message = SHA-256(preimage) (32 bytes); the secp256r1 precompile verifies ECDSA-P256 over SHA-256(message).\",\n")
        sb.append("    \"signature_rfc6979_hex: raw r||s from RFC 6979 (HMAC-SHA256 nonce) over message; may be high-S.\",\n")
        sb.append("    \"signature_hex: the same signature normalized to low-S (s <= n/2), the only form the precompile accepts.\",\n")
        sb.append("    \"The key is the RFC 6979 A.2.5 test key: public test material only.\"\n")
        sb.append("  ],\n")
        sb.append("  \"program_id\": \"$programIdB58\",\n")
        sb.append("  \"program_id_hex\": \"${programId.toHex()}\",\n")
        sb.append("  \"rig\": \"$rigB58\",\n")
        sb.append("  \"rig_hex\": \"${rig.toHex()}\",\n")
        sb.append("  \"key\": {\n")
        sb.append("    \"private_scalar_hex\": \"${with(P256) { privateScalar.toFixed32() }.toHex()}\",\n")
        sb.append("    \"public_key_compressed_hex\": \"${pub.toHex()}\"\n")
        sb.append("  },\n")
        sb.append("  \"vectors\": [\n")
        messages.forEachIndexed { i, (name, m) ->
            val preimage = m.preimage()
            val message = m.digest()
            val rfc = TestCrypto.signRfc6979(privateScalar, message)
            sb.append("    {\n")
            sb.append("      \"name\": \"$name\",\n")
            sb.append("      \"kind\": ${m.kind.wire},\n")
            sb.append("      \"fields\": ${fields(m)},\n")
            sb.append("      \"preimage_len\": ${preimage.size},\n")
            sb.append("      \"preimage_hex\": \"${preimage.toHex()}\",\n")
            sb.append("      \"message_hex\": \"${message.toHex()}\",\n")
            sb.append("      \"signature_rfc6979_hex\": \"${rfc.toHex()}\",\n")
            sb.append("      \"signature_hex\": \"${P256.normalizeLowS(rfc).toHex()}\"\n")
            sb.append(if (i == messages.lastIndex) "    }\n" else "    },\n")
        }
        sb.append("  ]\n")
        sb.append("}\n")
        return sb.toString()
    }

    private fun fields(m: RigMessage): String = when (m) {
        is HeartbeatPreimage ->
            """{"counter": "${m.counter}", "shift_id": "${m.shiftId}", "round_id": "${m.roundId}", "lease_rounds": ${m.leaseRounds}}"""
        is ShiftSignalPreimage ->
            """{"counter": "${m.counter}", "shift_id": "${m.shiftId}", "reason": ${m.reason.wire}}"""
        is PlanPreimage -> with(m.plan) {
            """{"counter": "${m.counter}", "max_ev_cost": "$maxEvCost", "dig_lamports": "$digLamports", """ +
                """"split": $splitTiles, "solo": $soloTiles, "lease": $leaseRounds, "flags": $flags, """ +
                """"window_start": "$windowStartTs", "window_end": "$windowEndTs"}"""
        }
    }

    private fun fromFields(v: JsonObject): RigMessage {
        val f = v["fields"]!!.jsonObject
        fun u64(k: String) = f.str(k).toULong()
        fun u8(k: String) = f[k]!!.jsonPrimitive.int
        return when (val kind = RigMessageKind.fromWire(v["kind"]!!.jsonPrimitive.int)) {
            RigMessageKind.HEARTBEAT ->
                HeartbeatPreimage(programId, rig, u64("counter"), u64("shift_id"), u64("round_id"), u8("lease_rounds"))
            RigMessageKind.BREAK, RigMessageKind.FREEZE ->
                ShiftSignalPreimage(programId, rig, kind, u64("counter"), u64("shift_id"), ShiftEndReason.fromWire(u8("reason")))
            RigMessageKind.PLAN -> PlanPreimage(
                programId, rig, u64("counter"),
                ShiftPlan(
                    u64("max_ev_cost"), u64("dig_lamports"), u8("split"), u8("solo"), u8("lease"), u8("flags"),
                    f.str("window_start").toLong(), f.str("window_end").toLong(),
                ),
            )
        }
    }

    private fun JsonObject.str(key: String): String = this[key]!!.jsonPrimitive.content
}
