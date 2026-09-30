package xyz.headsdown.core.chain.ix

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.headsdown.core.chain.HeadsDownProgram
import xyz.headsdown.core.chain.Ore
import xyz.headsdown.core.chain.Pubkey
import xyz.headsdown.core.chain.WellKnown
import xyz.headsdown.core.chain.hex
import xyz.headsdown.core.chain.hexBytes
import xyz.headsdown.core.chain.rfc6979Sign
import xyz.headsdown.core.chain.tx.Instruction
import xyz.headsdown.core.keys.HeartbeatPreimage
import xyz.headsdown.core.keys.P256
import xyz.headsdown.core.keys.ShiftEndReason
import xyz.headsdown.core.keys.ShiftPlan
import xyz.headsdown.core.keys.SignedRigMessage
import java.io.File

/**
 * The phone's own instruction output, published as `src/test/resources/ix_vectors.json` for
 * `programs/heads-down/tests/tests/crosscheck.rs`, which rebuilds every entry with the program's
 * reference client from the same args and executes it on the LiteSVM fork (vectors/CROSSCHECK.md).
 * The authoritative byte-for-byte check against the frozen contract is [GoldenInstructionsTest];
 * this file is the phone's side of that conversation and must equal the generator output.
 * Regenerate with `HD_WRITE_VECTORS=1 ./gradlew :core:chain:testDebugUnitTest`.
 *
 * Arg names follow what crosscheck.rs reads (`attestation_ix`, `precompile_ix`, `sig_index`,
 * `payer`, …). `payer` is the fee payer of a P-256 transaction: since v1.1 it is not an
 * instruction account.
 */
class InstructionVectorsTest {

    private val authority = Pubkey.fromBase58("7xKXtg2CW87d97TXJSDpbD5jBkheTqA83TZRuJosgAsU")
    private val rig = HeadsDownProgram.rig(authority).address
    private val payer = Pubkey.fromBase58("9qQk3i73uAiWs1Wv7jDj4diVmwcbCa1FpokjPz7y5MwQ")
    private val sgtMint = Pubkey.fromBase58("FkvYJJx5Yk8bFLYauyex51fX614dBt3KAgutSHnJBiSh")

    /** RFC 6979 A.2.5 public key (the same test key as core/keys vectors.json). */
    private val p256 = hexBytes("0360fed4ba255a9d31c961eb74c6356d68c049b8923b61fa6ce669622e60f29fb6")
    private val testScalar = "C9AFA9D845BA75166B5C215767B1D6934E50C3DB36E89B127B8A622B120F6721"

    // crosscheck.rs runs these at unix 1_790_000_000 (the window start); caps expire later.
    private val plan = ShiftPlan(530_000_000uL, 1_000_000uL, 4, 0, 1, 0, 1_790_000_000L, 1_790_028_800L)
    private val caps = RigCaps(500_000_000uL, 120_000_000uL, 2_000_000uL, 670_000_000uL, 1_790_600_000L)

    /** The golden fork's `Config.executor_fee` (instructions.json `pinned_fork.config`). */
    private val executorFee = 5_000uL

    private class Vector(val name: String, val args: String, val roles: List<String>, val ix: Instruction)

    private val vectors: List<Vector> by lazy {
        listOf(
            Vector(
                "ore_automate_heads_down",
                """{"authority": "$authority", "amount_per_tile": "250000", "deposit": "20000000", "executor_fee": "$executorFee", "strategy": 2, "reload": true}""",
                listOf("authority", "automation", "executor", "miner", "system_program"),
                OreInstructions.automateHeadsDown(authority, 250_000uL, 20_000_000uL, executorFee),
            ),
            Vector(
                "ore_revoke", """{"authority": "$authority"}""",
                listOf("authority", "automation", "executor_default", "miner", "system_program"),
                OreInstructions.revoke(authority),
            ),
            Vector("compute_unit_limit", """{"units": 300000}""", emptyList(), ComputeBudgetInstructions.setComputeUnitLimit(300_000)),
            Vector("compute_unit_price", """{"micro_lamports": "5000"}""", emptyList(), ComputeBudgetInstructions.setComputeUnitPrice(5_000uL)),
            Vector(
                "register_rig_guest", """{"authority": "$authority", "p256_pubkey_hex": "${p256.hex()}", "has_attestation": 0}""",
                listOf("authority", "rig", "config", "system_program"),
                HeadsDownInstructions.registerRig(authority, p256),
            ),
            Vector(
                "register_rig_attested",
                """{"authority": "$authority", "p256_pubkey_hex": "${p256.hex()}", "has_attestation": 1, "attestation_ix": 0, "ed25519_sig_index": 0, "attestation_level": 1, "attestation_expiry_slot": "451800000"}""",
                listOf("authority", "rig", "config", "system_program", "instructions_sysvar"),
                HeadsDownInstructions.registerRig(authority, p256, RegistrarAttestation(0, 0, 1, 451_800_000uL)),
            ),
            Vector(
                "rotate_key", """{"authority": "$authority", "p256_pubkey_hex": "${p256.hex()}", "has_attestation": 0}""",
                listOf("authority", "rig", "config"),
                HeadsDownInstructions.rotateKey(authority, p256),
            ),
            Vector(
                "set_caps",
                """{"authority": "$authority", "cap_week": "500000000", "cap_shift": "120000000", "cap_round": "2000000", "cap_max_cost": "670000000", "caps_expiry_ts": "1790600000"}""",
                listOf("authority", "rig"),
                HeadsDownInstructions.setCaps(authority, caps),
            ),
            Vector(
                "arm_shift_wallet", """{"authority": "$authority", ${planArgs()}}""",
                listOf("rig", "authority", "ore_board"),
                HeadsDownInstructions.armShift(authority, plan),
            ),
            Vector(
                "arm_shift_p256",
                """{"payer": "$payer", "authority": "$authority", "rig": "$rig", ${planArgs()}, "counter": "41", "precompile_ix": 0, "sig_index": 0}""",
                listOf("rig", "authority", "ore_board", "instructions_sysvar"),
                HeadsDownInstructions.armShiftP256(authority, plan, P256Auth(41uL, 0, 0)),
            ),
            Vector(
                "break_shift_wallet", """{"authority": "$authority", "reason": 6}""",
                listOf("rig", "authority"),
                HeadsDownInstructions.breakShift(authority, ShiftEndReason.MANUAL),
            ),
            Vector(
                "break_shift_p256",
                """{"payer": "$payer", "authority": "$authority", "rig": "$rig", "reason": 1, "counter": "44", "precompile_ix": 0, "sig_index": 0}""",
                listOf("rig", "authority", "instructions_sysvar"),
                HeadsDownInstructions.breakShiftP256(authority, ShiftEndReason.PICKUP, P256Auth(44uL, 0, 0)),
            ),
            Vector(
                "freeze_rig_wallet", """{"authority": "$authority", "reason": 3}""",
                listOf("rig", "authority"),
                HeadsDownInstructions.freezeRig(authority),
            ),
            Vector(
                "freeze_rig_p256",
                """{"payer": "$payer", "authority": "$authority", "rig": "$rig", "reason": 3, "counter": "46", "precompile_ix": 0, "sig_index": 0}""",
                listOf("rig", "authority", "instructions_sysvar"),
                HeadsDownInstructions.freezeRigP256(authority, P256Auth(46uL, 0, 0)),
            ),
            Vector("unfreeze_rig", """{"authority": "$authority"}""", listOf("rig", "authority"), HeadsDownInstructions.unfreezeRig(authority)),
            Vector(
                "end_shift", """{"caller": "$authority", "rig": "$rig", "shift_id": "7"}""",
                listOf("caller", "rig", "shift_log", "ore_board", "system_program"),
                HeadsDownInstructions.endShift(authority, rig, 7uL),
            ),
            Vector("close_rig_guest", """{"authority": "$authority"}""", listOf("authority", "rig"), HeadsDownInstructions.closeRig(authority)),
            Vector(
                "close_rig_seeker", """{"authority": "$authority", "sgt_mint": "$sgtMint"}""",
                listOf("authority", "rig", "seeker_seat"),
                HeadsDownInstructions.closeRig(authority, sgtMint),
            ),
            Vector(
                "secp256r1_heartbeat",
                """{"vector": "core/keys vectors.json heartbeat_lease_1", "public_key_hex": "${p256.hex()}"}""",
                emptyList(),
                Secp256r1Instructions.verify(listOf(heartbeatVector())),
            ),
        )
    }

    private fun planArgs() = with(plan) {
        """"max_ev_cost": "$maxEvCost", "dig_lamports": "$digLamports", "split": $splitTiles, "solo": $soloTiles, """ +
            """"lease": $leaseRounds, "flags": $flags, "window_start": "$windowStartTs", "window_end": "$windowEndTs""""
    }

    /** heartbeat_lease_1 from core/keys vectors.json, re-signed with the same RFC 6979 key. */
    private fun heartbeatVector(): SignedRigMessage<HeartbeatPreimage> {
        val payload = HeartbeatPreimage(HeadsDownProgram.ID.bytes, rig.bytes, 42uL, 7uL, 422_593uL, 1)
        return SignedRigMessage(payload, P256.normalizeLowS(rfc6979Sign(testScalar, payload.digest())), p256)
    }

    // ------------------------------------------------------------------------------ tests

    @Test
    fun `committed ix_vectors json equals the generator output`() {
        val generated = render()
        val file = File(System.getProperty("user.dir"), "src/test/resources/ix_vectors.json")
        if (System.getenv("HD_WRITE_VECTORS") == "1") file.writeText(generated)
        val committed = javaClass.getResource("/ix_vectors.json")?.readText() ?: error("ix_vectors.json missing; run with HD_WRITE_VECTORS=1")
        assertEquals(committed, generated)
    }

    @Test
    fun `committed vectors decode to the v1_1 sizes and one authority signer`() {
        val root = Json.parseToJsonElement(javaClass.getResource("/ix_vectors.json")!!.readText()).jsonObject
        val byName = root["instructions"]!!.jsonArray.associateBy { it.jsonObject["name"]!!.jsonPrimitive.content }
        val expectedSizes = mapOf(
            "ore_automate_heads_down" to 66, "ore_revoke" to 66, "register_rig_guest" to 35, "register_rig_attested" to 46,
            "rotate_key" to 35, "set_caps" to 41, "arm_shift_wallet" to 38, "arm_shift_p256" to 48, "break_shift_wallet" to 3,
            "break_shift_p256" to 13, "freeze_rig_wallet" to 3, "freeze_rig_p256" to 13, "unfreeze_rig" to 1, "end_shift" to 1,
            "close_rig_guest" to 1, "close_rig_seeker" to 1, "secp256r1_heartbeat" to 2 + 14 + 33 + 64 + 32,
        )
        for ((name, size) in expectedSizes) {
            val v = byName[name]!!.jsonObject
            assertEquals(name, size, v["data_len"]!!.jsonPrimitive.int)
            assertEquals(name, size * 2, v["data_hex"]!!.jsonPrimitive.content.length)
            val metas = v["accounts"]!!.jsonArray.map { it.jsonObject }
            assertEquals(name, v["roles_count"]!!.jsonPrimitive.int, metas.size)
            // Only the wallet ever signs, never a PDA or a program; the P-256 paths have no signer.
            val signers = metas.filter { it["signer"]!!.jsonPrimitive.boolean }.map { it["role"]!!.jsonPrimitive.content }
            assertTrue("$name signers $signers", signers.all { it == "authority" || it == "caller" } && signers.size <= 1)
            if (name.endsWith("_p256")) assertTrue(name, signers.isEmpty())
        }
    }

    @Test
    fun `ORE automate matches the layout proven against live ORE in spikes-ore-executor`() {
        val ix = OreInstructions.automateHeadsDown(authority, 250_000uL, 20_000_000uL, 10_000uL)
        val expected = "00" + // AutomateV2 tag
            "90d0030000000000" + // amount per tile 250_000
            "002d310100000000" + // deposit 20_000_000
            "1027000000000000" + // fee 10_000
            "0000000000000000" + // mask (unused by Discretionary)
            "02" + // strategy Discretionary
            "0100000000000000" + // reload = 1
            "ffffffffffffffff" + // max_production_cost (stored, not enforced by ORE)
            "0000" + "ffff" + "0000" + "0000" + // min/max motherlode, split, solo
            "0000000000000000" // buffer
        assertEquals(expected, ix.data.hex())
        assertEquals(Ore.PROGRAM_ID, ix.programId)
        assertEquals(
            listOf(authority, Ore.automation(authority).address, HeadsDownProgram.executor.address, Ore.miner(authority).address, WellKnown.SYSTEM_PROGRAM),
            ix.accounts.map { it.pubkey },
        )
        assertEquals(listOf(true, false, false, false, false), ix.accounts.map { it.isSigner })
        assertEquals(listOf(true, true, true, true, false), ix.accounts.map { it.isWritable })
    }

    @Test
    fun `secp256r1 data matches the p256-introspect layout`() {
        val hb = heartbeatVector()
        val data = Secp256r1Instructions.verify(listOf(hb)).data
        // count 1, pad, then one record: sig@49, key@16, msg@113 (32 B), every ix = 0xFFFF.
        assertEquals("0100" + "3100ffff" + "1000ffff" + "71002000ffff", data.copyOfRange(0, 16).hex())
        assertEquals(hb.publicKey.hex(), data.copyOfRange(16, 49).hex())
        assertEquals(hb.signature.hex(), data.copyOfRange(49, 113).hex())
        assertEquals(hb.message.hex(), data.copyOfRange(113, 145).hex())
        assertTrue(P256.isLowS(data.copyOfRange(49, 113)))
        // Two entries: records first, then payloads back to back.
        val two = Secp256r1Instructions.verify(listOf(hb, hb)).data
        assertEquals(2 + 2 * 14 + 2 * 129, two.size)
        assertEquals("0200" + "3f00ffff" + "1e00ffff" + "7f002000ffff", two.copyOfRange(0, 16).hex())
    }

    @Test
    fun `builders refuse invalid input`() {
        assertThrows(IllegalArgumentException::class.java) { HeadsDownInstructions.registerRig(authority, p256.copyOf(32)) }
        assertThrows(IllegalArgumentException::class.java) { HeadsDownInstructions.registerRig(authority, byteArrayOf(0x02) + ByteArray(32) { 0xFF.toByte() }) }
        assertThrows(IllegalArgumentException::class.java) { HeadsDownInstructions.registerRig(authority, byteArrayOf(0x04) + p256.copyOfRange(1, 33)) }
        assertThrows(IllegalArgumentException::class.java) { RegistrarAttestation(0, 0, 3, 1uL) }
        // Level 0 is refused on-chain: the builder cannot express it (register without a voucher).
        assertThrows(IllegalArgumentException::class.java) { RegistrarAttestation(0, 0, 0, 1uL) }
        assertThrows(IllegalArgumentException::class.java) { RegistrarAttestation(0xFF, 0, 1, 1uL) }
        assertThrows(IllegalArgumentException::class.java) { P256Auth(1uL, 255, 0) }
        assertThrows(IllegalArgumentException::class.java) { P256Auth(1uL, 0, 8) }
        // break_shift accepts 1, 2, 4, 5, 6, 7, 8 only.
        assertThrows(IllegalArgumentException::class.java) { HeadsDownInstructions.breakShift(authority, ShiftEndReason.COMPLETED) }
        assertThrows(IllegalArgumentException::class.java) { HeadsDownInstructions.breakShift(authority, ShiftEndReason.FREEZE) }
        assertThrows(IllegalArgumentException::class.java) { HeadsDownInstructions.breakShiftP256(authority, ShiftEndReason.FREEZE, P256Auth(1uL, 0, 0)) }
        assertEquals("080008", HeadsDownInstructions.breakShift(authority, ShiftEndReason.UNLOCKED).data.hex())
        assertEquals("080007", HeadsDownInstructions.breakShift(authority, ShiftEndReason.UNPLUGGED).data.hex())
        assertThrows(IllegalArgumentException::class.java) { HeadsDownInstructions.verifySeeker(authority, sgtMint, payer, previousRig = rig) }
        assertThrows(IllegalArgumentException::class.java) { caps.copy(capRound = caps.capShift + 1uL) }
        assertThrows(IllegalArgumentException::class.java) { caps.copy(capShift = caps.capWeek + 1uL) }
        assertThrows(IllegalArgumentException::class.java) { caps.copy(capsExpiryTs = 0) }
        assertThrows(IllegalArgumentException::class.java) { OreInstructions.automateHeadsDown(authority, 0uL, 1uL, 1uL) }
        assertThrows(IllegalArgumentException::class.java) {
            OreInstructions.automate(authority, 1uL, 1uL, HeadsDownProgram.executor.address, 1uL, 2, true, conditions = AutomationConditions(splitTiles = 4))
        }
        assertThrows(IllegalArgumentException::class.java) {
            OreInstructions.automate(authority, 1uL, 1uL, HeadsDownProgram.executor.address, 1uL, 4, true)
        }
        assertThrows(IllegalArgumentException::class.java) { ComputeBudgetInstructions.setComputeUnitLimit(1_400_001) }
        assertThrows(IllegalArgumentException::class.java) { Secp256r1Instructions.verify(emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { Secp256r1Instructions.verify(List(9) { heartbeatVector() }) }
        assertTrue(caps.admits(plan))
        assertTrue(!caps.copy(capMaxCost = plan.maxEvCost - 1uL).admits(plan))
    }

    // ------------------------------------------------------------------------------ generator

    private fun render(): String {
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"format\": \"heads-down/instruction-vectors\",\n")
        sb.append("  \"version\": 2,\n")
        sb.append("  \"spec\": \"programs/heads-down/INTERFACE.md v1.1 (checked against programs/heads-down/vectors/instructions.json)\",\n")
        sb.append("  \"notes\": [\n")
        sb.append("    \"All integers little-endian. u64/i64 args are decimal strings; u8/u16/bool args are JSON values.\",\n")
        sb.append("    \"accounts are in instruction order; role names map each meta to the program's account list.\",\n")
        sb.append("    \"payer is the fee payer of a P-256 transaction; since v1.1 it is not an instruction account.\",\n")
        sb.append("    \"secp256r1_heartbeat signs core/keys vectors.json heartbeat_lease_1 with the RFC 6979 test key (low-S).\"\n")
        sb.append("  ],\n")
        sb.append("  \"program_ids\": {\"heads_down\": \"${HeadsDownProgram.ID}\", \"ore\": \"${Ore.PROGRAM_ID}\", ")
        sb.append("\"compute_budget\": \"${WellKnown.COMPUTE_BUDGET}\", \"secp256r1\": \"${WellKnown.SECP256R1_SIG_VERIFY}\"},\n")
        sb.append("  \"pdas\": {\"config\": \"${HeadsDownProgram.config.address}\", \"executor\": \"${HeadsDownProgram.executor.address}\", ")
        sb.append("\"rig\": \"$rig\", \"automation\": \"${Ore.automation(authority).address}\", \"miner\": \"${Ore.miner(authority).address}\", ")
        sb.append("\"shift_log_7\": \"${HeadsDownProgram.shiftLog(rig, 7uL).address}\", \"seeker_seat\": \"${HeadsDownProgram.seekerSeat(sgtMint).address}\"},\n")
        sb.append("  \"instructions\": [\n")
        vectors.forEachIndexed { i, v ->
            require(v.roles.size == v.ix.accounts.size) { "${v.name}: roles do not match metas" }
            sb.append("    {\n")
            sb.append("      \"name\": \"${v.name}\",\n")
            sb.append("      \"program_id\": \"${v.ix.programId}\",\n")
            sb.append("      \"args\": ${v.args},\n")
            sb.append("      \"roles_count\": ${v.roles.size},\n")
            sb.append("      \"accounts\": [")
            if (v.roles.isNotEmpty()) sb.append("\n")
            v.ix.accounts.forEachIndexed { j, m ->
                sb.append("        {\"role\": \"${v.roles[j]}\", \"pubkey\": \"${m.pubkey}\", \"signer\": ${m.isSigner}, \"writable\": ${m.isWritable}}")
                sb.append(if (j == v.ix.accounts.lastIndex) "\n      " else ",\n")
            }
            sb.append("],\n")
            sb.append("      \"data_len\": ${v.ix.dataSize},\n")
            sb.append("      \"data_hex\": \"${v.ix.data.hex()}\"\n")
            sb.append(if (i == vectors.lastIndex) "    }\n" else "    },\n")
        }
        sb.append("  ]\n")
        sb.append("}\n")
        return sb.toString()
    }
}
