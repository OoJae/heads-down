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
import xyz.headsdown.core.chain.tx.Instruction
import xyz.headsdown.core.keys.HeartbeatPreimage
import xyz.headsdown.core.keys.P256
import xyz.headsdown.core.keys.ShiftEndReason
import xyz.headsdown.core.keys.ShiftPlan
import xyz.headsdown.core.keys.SignedRigMessage
import java.io.File
import java.math.BigInteger

/**
 * Golden bytes for every instruction the app builds, shared with the Rust program and crank as
 * `src/test/resources/ix_vectors.json` (inputs, account metas with roles, data hex). The file
 * must equal the generator output exactly; regenerate with
 * `HD_WRITE_VECTORS=1 ./gradlew :core:chain:testDebugUnitTest`.
 */
class InstructionVectorsTest {

    private val authority = Pubkey.fromBase58("7xKXtg2CW87d97TXJSDpbD5jBkheTqA83TZRuJosgAsU")
    private val rig = HeadsDownProgram.rig(authority).address
    private val payer = Pubkey.fromBase58("9qQk3i73uAiWs1Wv7jDj4diVmwcbCa1FpokjPz7y5MwQ")
    private val sgtMint = Pubkey.fromBase58("FkvYJJx5Yk8bFLYauyex51fX614dBt3KAgutSHnJBiSh")

    /** RFC 6979 A.2.5 public key (the same test key as core/keys vectors.json). */
    private val p256 = hexBytes("0360fed4ba255a9d31c961eb74c6356d68c049b8923b61fa6ce669622e60f29fb6")
    private val plan = ShiftPlan(530_000_000uL, 1_000_000uL, 4, 0, 1, 0, 1_790_000_000L, 1_790_028_800L)
    private val caps = RigCaps(500_000_000uL, 120_000_000uL, 2_000_000uL, 670_000_000uL, 1_790_600_000L)

    private class Vector(val name: String, val args: String, val roles: List<String>, val ix: Instruction)

    private val vectors: List<Vector> by lazy {
        listOf(
            Vector(
                "ore_automate_heads_down",
                """{"authority": "$authority", "amount_per_tile": "250000", "deposit": "20000000", "executor_fee": "10000", "strategy": 2, "reload": true}""",
                listOf("authority", "automation", "executor", "miner", "system_program"),
                OreInstructions.automateHeadsDown(authority, 250_000uL, 20_000_000uL, 10_000uL),
            ),
            Vector(
                "ore_revoke", """{"authority": "$authority"}""",
                listOf("authority", "automation", "executor_default", "miner", "system_program"),
                OreInstructions.revoke(authority),
            ),
            Vector("compute_unit_limit", """{"units": 300000}""", emptyList(), ComputeBudgetInstructions.setComputeUnitLimit(300_000)),
            Vector("compute_unit_price", """{"micro_lamports": "5000"}""", emptyList(), ComputeBudgetInstructions.setComputeUnitPrice(5_000uL)),
            Vector(
                "register_rig_guest", """{"authority": "$authority", "p256_pubkey_hex": "${p256.hex()}"}""",
                listOf("authority", "config", "rig", "system_program", "instructions_sysvar"),
                HeadsDownInstructions.registerRig(authority, p256),
            ),
            Vector(
                "register_rig_attested",
                """{"authority": "$authority", "p256_pubkey_hex": "${p256.hex()}", "attestation_ix": 0, "attestation_level": 1, "attestation_expiry_slot": "451800000"}""",
                listOf("authority", "config", "rig", "system_program", "instructions_sysvar"),
                HeadsDownInstructions.registerRig(authority, p256, RegistrarAttestation(0, 1, 451_800_000uL)),
            ),
            Vector(
                "rotate_key", """{"authority": "$authority", "p256_pubkey_hex": "${p256.hex()}"}""",
                listOf("authority", "config", "rig", "instructions_sysvar"),
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
                listOf("authority", "rig"),
                HeadsDownInstructions.armShift(authority, plan),
            ),
            Vector(
                "arm_shift_p256", """{"payer": "$payer", "rig": "$rig", ${planArgs()}, "counter": "41", "precompile_ix": 0, "sig_index": 0}""",
                listOf("payer", "rig", "instructions_sysvar"),
                HeadsDownInstructions.armShiftP256(payer, rig, plan, P256Auth(41uL, 0, 0)),
            ),
            Vector(
                "break_shift_wallet", """{"authority": "$authority", "reason": 6}""",
                listOf("authority", "rig"),
                HeadsDownInstructions.breakShift(authority, ShiftEndReason.MANUAL),
            ),
            Vector(
                "break_shift_p256", """{"payer": "$payer", "rig": "$rig", "reason": 1, "counter": "44", "precompile_ix": 1, "sig_index": 0}""",
                listOf("payer", "rig", "instructions_sysvar"),
                HeadsDownInstructions.breakShiftP256(payer, rig, ShiftEndReason.PICKUP, P256Auth(44uL, 1, 0)),
            ),
            Vector(
                "freeze_rig_wallet", """{"authority": "$authority", "reason": 3}""",
                listOf("authority", "rig"),
                HeadsDownInstructions.freezeRig(authority),
            ),
            Vector(
                "freeze_rig_p256", """{"payer": "$payer", "rig": "$rig", "reason": 3, "counter": "46", "precompile_ix": 0, "sig_index": 2}""",
                listOf("payer", "rig", "instructions_sysvar"),
                HeadsDownInstructions.freezeRigP256(payer, rig, P256Auth(46uL, 0, 2)),
            ),
            Vector("unfreeze_rig", """{"authority": "$authority"}""", listOf("authority", "rig"), HeadsDownInstructions.unfreezeRig(authority)),
            Vector(
                "end_shift", """{"caller": "$authority", "rig": "$rig", "shift_id": "7"}""",
                listOf("caller", "rig", "shift_log", "system_program"),
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
        val d = BigInteger("C9AFA9D845BA75166B5C215767B1D6934E50C3DB36E89B127B8A622B120F6721", 16)
        val x9 = org.bouncycastle.crypto.ec.CustomNamedCurves.getByName("secp256r1")
        val params = org.bouncycastle.crypto.params.ECDomainParameters(x9.curve, x9.g, x9.n, x9.h)
        val signer = org.bouncycastle.crypto.signers.ECDSASigner(
            org.bouncycastle.crypto.signers.HMacDSAKCalculator(org.bouncycastle.crypto.digests.SHA256Digest()),
        )
        signer.init(true, org.bouncycastle.crypto.params.ECPrivateKeyParameters(d, params))
        val (r, s) = signer.generateSignature(java.security.MessageDigest.getInstance("SHA-256").digest(payload.digest()))
        fun fixed(v: BigInteger) = v.toByteArray().let { b -> ByteArray(32 - minOf(32, b.size)) + b.takeLast(32).toByteArray() }
        return SignedRigMessage(payload, P256.normalizeLowS(fixed(r) + fixed(s)), p256)
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
    fun `committed vectors decode to the documented tags, sizes and metas`() {
        val root = Json.parseToJsonElement(javaClass.getResource("/ix_vectors.json")!!.readText()).jsonObject
        val byName = root["instructions"]!!.jsonArray.associateBy { it.jsonObject["name"]!!.jsonPrimitive.content }
        val expectedSizes = mapOf(
            "ore_automate_heads_down" to 66, "ore_revoke" to 66, "register_rig_guest" to 44, "rotate_key" to 44, "set_caps" to 41,
            "arm_shift_wallet" to 38, "arm_shift_p256" to 48, "break_shift_wallet" to 3, "break_shift_p256" to 13,
            "freeze_rig_wallet" to 3, "freeze_rig_p256" to 13, "unfreeze_rig" to 1, "end_shift" to 1, "close_rig_guest" to 1,
            "secp256r1_heartbeat" to 2 + 14 + 33 + 64 + 32,
        )
        for ((name, size) in expectedSizes) {
            val v = byName[name]!!.jsonObject
            assertEquals(name, size, v["data_len"]!!.jsonPrimitive.int)
            assertEquals(name, size * 2, v["data_hex"]!!.jsonPrimitive.content.length)
            val metas = v["accounts"]!!.jsonArray
            assertEquals(name, v["roles_count"]!!.jsonPrimitive.int, metas.size)
            // No instruction ever marks a PDA or program as a signer: only the first meta may sign.
            metas.drop(1).forEach { assertTrue(name, !it.jsonObject["signer"]!!.jsonPrimitive.boolean) }
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
    fun `heads_down layouts, assembled by hand`() {
        assertEquals(
            // tag | week 500_000_000 | shift 120_000_000 | round 2_000_000 | max_cost 670_000_000 | expiry 1_790_600_000
            "03" + "0065cd1d00000000" + "000e270700000000" + "80841e0000000000" + "8063ef2700000000" + "4063ba6a00000000",
            HeadsDownInstructions.setCaps(authority, caps).data.hex(),
        )
        assertEquals("05" + "00" + plan.encode().hex(), HeadsDownInstructions.armShift(authority, plan).data.hex())
        // P-256 tail = the dig entry's order: precompile_ix u8 | sig_index u8 | counter u64.
        assertEquals(
            "05" + "01" + plan.encode().hex() + "00" + "00" + "2900000000000000",
            HeadsDownInstructions.armShiftP256(payer, rig, plan, P256Auth(41uL, 0, 0)).data.hex(),
        )
        assertEquals("080006", HeadsDownInstructions.breakShift(authority, ShiftEndReason.MANUAL).data.hex())
        assertEquals("080101" + "01" + "00" + "2c00000000000000", HeadsDownInstructions.breakShiftP256(payer, rig, ShiftEndReason.PICKUP, P256Auth(44uL, 1, 0)).data.hex())
        assertEquals("090103" + "00" + "02" + "2e00000000000000", HeadsDownInstructions.freezeRigP256(payer, rig, P256Auth(46uL, 0, 2)).data.hex())
        assertEquals("090003", HeadsDownInstructions.freezeRig(authority).data.hex())
        assertEquals("01" + p256.hex() + "ff" + "00" + "0000000000000000", HeadsDownInstructions.registerRig(authority, p256).data.hex())
        assertEquals("0b", HeadsDownInstructions.endShift(authority, rig, 7uL).data.hex())
        assertEquals("0e", HeadsDownInstructions.closeRig(authority).data.hex())
        assertEquals("0a", HeadsDownInstructions.unfreezeRig(authority).data.hex())
        // Wallet-path metas: authority signs (read-only), the Rig is always the authority's PDA.
        val arm = HeadsDownInstructions.armShift(authority, plan)
        assertEquals(HeadsDownProgram.ID, arm.programId)
        assertEquals(listOf(authority, rig), arm.accounts.map { it.pubkey })
        assertTrue(arm.accounts[0].isSigner && !arm.accounts[0].isWritable)
        assertTrue(!arm.accounts[1].isSigner && arm.accounts[1].isWritable)
        // end_shift writes the ShiftLog of the Rig's current shift.
        assertEquals(HeadsDownProgram.shiftLog(rig, 7uL).address, HeadsDownInstructions.endShift(authority, rig, 7uL).accounts[2].pubkey)
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
        assertThrows(IllegalArgumentException::class.java) { RegistrarAttestation(0, 3, 1uL) }
        assertThrows(IllegalArgumentException::class.java) { RegistrarAttestation(0xFF, 1, 1uL) }
        assertThrows(IllegalArgumentException::class.java) { P256Auth(1uL, 255, 0) }
        assertThrows(IllegalArgumentException::class.java) { P256Auth(1uL, 0, 8) }
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
        sb.append("  \"version\": 1,\n")
        sb.append("  \"spec\": \"programs/heads-down/INTERFACE.md + android/INTERFACE-NOTES.md (instruction data layouts)\",\n")
        sb.append("  \"notes\": [\n")
        sb.append("    \"All integers little-endian. u64/i64 args are decimal strings; u8/u16/bool args are JSON values.\",\n")
        sb.append("    \"accounts are in instruction order; role names map each meta to the program's account list.\",\n")
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
