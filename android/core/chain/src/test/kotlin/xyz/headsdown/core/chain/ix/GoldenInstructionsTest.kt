package xyz.headsdown.core.chain.ix

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.headsdown.core.chain.Golden
import xyz.headsdown.core.chain.HeadsDownProgram
import xyz.headsdown.core.chain.Ore
import xyz.headsdown.core.chain.Pubkey
import xyz.headsdown.core.chain.WellKnown
import xyz.headsdown.core.chain.arr
import xyz.headsdown.core.chain.bool
import xyz.headsdown.core.chain.hex
import xyz.headsdown.core.chain.hexBytes
import xyz.headsdown.core.chain.int
import xyz.headsdown.core.chain.obj
import xyz.headsdown.core.chain.pubkey
import xyz.headsdown.core.chain.registrar.RegistrarVoucher
import xyz.headsdown.core.chain.str
import xyz.headsdown.core.chain.tx.Instruction
import xyz.headsdown.core.chain.u64
import xyz.headsdown.core.keys.HeartbeatPreimage
import xyz.headsdown.core.keys.PlanPreimage
import xyz.headsdown.core.keys.RigMessage
import xyz.headsdown.core.keys.RigMessageKind
import xyz.headsdown.core.keys.ShiftEndReason
import xyz.headsdown.core.keys.ShiftPlan
import xyz.headsdown.core.keys.ShiftSignalPreimage
import xyz.headsdown.core.keys.SignedRigMessage

/**
 * Every instruction the phone can build, against `programs/heads-down/vectors/instructions.json`
 * (INTERFACE v1.1, executed in LiteSVM on a fork of live ORE): data bytes and the ordered account
 * metas (pubkey, signer, writable) must be identical. The companion instructions of those
 * transactions (Secp256r1SigVerify, the registrar's Ed25519SigVerify) are rebuilt too.
 */
class GoldenInstructionsTest {

    private val vectors = Golden.vectors

    /** The phone's builder for each golden vector it can produce. */
    private val androidBuilds: Map<String, (JsonObject) -> Instruction> = mapOf(
        "register_rig_guest" to { v -> HeadsDownInstructions.registerRig(role(v, "authority"), p256(v), attestation(v)) },
        "register_rig_attested" to { v -> HeadsDownInstructions.registerRig(role(v, "authority"), p256(v), attestation(v)) },
        "rotate_key_unattested" to { v -> HeadsDownInstructions.rotateKey(role(v, "authority"), p256(v), attestation(v)) },
        "rotate_key_attested" to { v -> HeadsDownInstructions.rotateKey(role(v, "authority"), p256(v), attestation(v)) },
        "set_caps" to { v ->
            val a = v.obj("args")
            HeadsDownInstructions.setCaps(
                role(v, "authority"),
                RigCaps(a.u64("cap_week"), a.u64("cap_shift"), a.u64("cap_round"), a.u64("cap_max_cost"), a.str("caps_expiry_ts").toLong()),
            )
        },
        "arm_shift_wallet" to { v -> HeadsDownInstructions.armShift(role(v, "authority"), plan(v)) },
        "arm_shift_p256" to { v -> HeadsDownInstructions.armShiftP256(role(v, "authority"), plan(v), p256Auth(v)) },
        "break_shift_wallet" to { v -> HeadsDownInstructions.breakShift(role(v, "authority"), reason(v)) },
        "break_shift_p256" to { v -> HeadsDownInstructions.breakShiftP256(role(v, "authority"), reason(v), p256Auth(v)) },
        "freeze_rig_wallet" to { v -> HeadsDownInstructions.freezeRig(role(v, "authority"), reason(v)) },
        "freeze_rig_p256" to { v -> HeadsDownInstructions.freezeRigP256(role(v, "authority"), p256Auth(v), reason(v)) },
        "unfreeze_rig" to { v -> HeadsDownInstructions.unfreezeRig(role(v, "authority")) },
        "end_shift_authority" to { v -> endShift(v) },
        "end_shift_permissionless" to { v -> endShift(v) },
        "verify_seeker" to { v ->
            val a = v.obj("args")
            HeadsDownInstructions.verifySeeker(a.pubkey("authority"), a.pubkey("sgt_mint"), a.pubkey("sgt_token_account"))
        },
        "verify_seeker_repoint" to { v ->
            val a = v.obj("args")
            HeadsDownInstructions.verifySeeker(a.pubkey("authority"), a.pubkey("sgt_mint"), a.pubkey("sgt_token_account"), a.pubkey("previous_rig"))
        },
        "close_rig_guest" to { v -> HeadsDownInstructions.closeRig(v.obj("args").pubkey("authority")) },
        // The vector names the seat; the phone derives it from the SGT mint (verify_seeker's).
        "close_rig_seeker" to { v -> HeadsDownInstructions.closeRig(v.obj("args").pubkey("authority"), sgtMint()) },
    )

    /** Admin (governance, upgrade authority) and crank instructions: never built on the phone. */
    private val notOnPhone = setOf(
        "initialize_config", "propose_config", "apply_config",
        "dig_fresh_heartbeat", "dig_reuse_lease", "dig_batch_two_rigs", "record_heartbeats",
    )

    // ------------------------------------------------------------------------------ helpers

    private fun role(v: JsonObject, role: String): Pubkey =
        v.arr("accounts").map { it.jsonObject }.single { it.str("role") == role }.pubkey("pubkey")

    private fun p256(v: JsonObject): ByteArray = hexBytes(v.obj("args").str("p256_pubkey_hex"))

    private fun attestation(v: JsonObject): RegistrarAttestation? {
        val a = v.obj("args")
        return if (a.int("has_attestation") == 0) {
            null
        } else {
            RegistrarAttestation(a.int("ed25519_ix"), a.int("ed25519_sig_index"), a.int("level"), a.u64("expiry_slot"))
        }
    }

    private fun plan(v: JsonObject): ShiftPlan = planOf(v.obj("args").obj("plan"))

    private fun planOf(p: JsonObject) = ShiftPlan(
        maxEvCost = p.u64("max_ev_cost"),
        digLamports = p.u64("dig_lamports"),
        splitTiles = p.int("split"),
        soloTiles = p.int("solo"),
        leaseRounds = p.int("lease"),
        flags = p.int("flags"),
        windowStartTs = p.str("window_start").toLong(),
        windowEndTs = p.str("window_end").toLong(),
    )

    private fun p256Auth(v: JsonObject): P256Auth {
        val a = v.obj("args")
        return P256Auth(a.u64("counter"), a.int("p256_ix"), a.int("p256_sig_index"))
    }

    private fun reason(v: JsonObject): ShiftEndReason = ShiftEndReason.fromWire(v.obj("args").int("reason"))

    private fun endShift(v: JsonObject): Instruction {
        val a = v.obj("args")
        return HeadsDownInstructions.endShift(a.pubkey("caller"), a.pubkey("rig"), a.u64("shift_id"))
    }

    private fun sgtMint(): Pubkey = vectors.getValue("verify_seeker").obj("args").pubkey("sgt_mint")

    private fun txInstruction(v: JsonObject, index: Int): JsonObject =
        v.obj("transaction").arr("instructions").map { it.jsonObject }.single { it.int("index") == index }

    private fun assertSameInstruction(name: String, golden: JsonObject, ix: Instruction) {
        assertEquals("$name program", Pubkey.fromBase58(golden.str("program_id")), ix.programId)
        assertEquals("$name data", golden.str("data_hex"), ix.data.hex())
        assertEquals("$name data_len", golden.int("data_len"), ix.dataSize)
        val metas = golden.arr("accounts").map { it.jsonObject }
        assertEquals("$name account count", metas.size, ix.accounts.size)
        metas.forEachIndexed { i, m ->
            val mine = ix.accounts[i]
            val what = "$name account $i (${m.str("role")})"
            assertEquals(what, m.pubkey("pubkey"), mine.pubkey)
            assertEquals("$what signer", m.bool("is_signer"), mine.isSigner)
            assertEquals("$what writable", m.bool("is_writable"), mine.isWritable)
        }
    }

    // ------------------------------------------------------------------------------ tests

    @Test
    fun `the golden file is the frozen v1_1 contract for this program`() {
        assertEquals("1.1", Golden.instructions.str("interface_version"))
        assertEquals(HeadsDownProgram.ID, Golden.instructions.pubkey("program_id"))
        val c = Golden.instructions.obj("constants")
        assertEquals(HeadsDownProgram.config.address, c.pubkey("config"))
        assertEquals(HeadsDownProgram.executor.address, c.pubkey("executor"))
        assertEquals(Ore.PROGRAM_ID, c.pubkey("ore_program"))
        assertEquals(Ore.BOARD, c.pubkey("ore_board"))
        assertEquals(Ore.CONFIG, c.pubkey("ore_config"))
        assertEquals(Ore.TREASURY, c.pubkey("ore_treasury"))
        assertEquals(Ore.ENTROPY_VAR, c.pubkey("ore_entropy_var"))
        assertEquals(Ore.ENTROPY_PROGRAM, c.pubkey("entropy_program"))
        assertEquals(WellKnown.INSTRUCTIONS_SYSVAR, c.pubkey("instructions_sysvar"))
        assertEquals(WellKnown.SECP256R1_SIG_VERIFY, c.pubkey("secp256r1_program"))
        assertEquals(WellKnown.ED25519_SIG_VERIFY, c.pubkey("ed25519_program"))
        assertEquals(WellKnown.COMPUTE_BUDGET, c.pubkey("compute_budget_program"))
    }

    @Test
    fun `every golden vector is either built by the phone or deliberately not`() {
        assertEquals(vectors.keys, androidBuilds.keys + notOnPhone)
        assertTrue(androidBuilds.keys.intersect(notOnPhone).isEmpty())
        // All 15 tags are covered by the file, and the phone builds 12 of them (not 0, 6, 7, 12, 13).
        assertEquals((0..14).toSet(), vectors.values.map { it.int("tag") }.toSet())
        assertEquals(setOf(1, 2, 3, 4, 5, 8, 9, 10, 11, 14), androidBuilds.keys.map { vectors.getValue(it).int("tag") }.toSet())
    }

    @Test
    fun `every phone-buildable vector matches byte for byte, metas included`() {
        for ((name, build) in androidBuilds) {
            val v = vectors.getValue(name)
            assertEquals("$name executed on the fork", "success", v.obj("litesvm").str("result"))
            assertSameInstruction(name, v, build(v))
        }
    }

    @Test
    fun `PDAs in the vectors are the phone's derivations`() {
        for (v in vectors.values) {
            for (m in v.arr("accounts").map { it.jsonObject }) {
                val pda = m["pda"]?.jsonObject ?: continue
                val seeds = pda.arr("seeds").map { hexBytes(it.jsonObject.str("hex")) }
                // "heads_down (HDn4…)", "ORE (oreV3…)", "BPFLoaderUpgradeable (…)", "AssociatedToken (…)"
                val program = Pubkey.fromBase58(pda.str("program").substringAfter('(').substringBefore(')'))
                val derived = xyz.headsdown.core.chain.Pda.find(seeds, program)
                assertEquals("${v.str("name")} ${m.str("role")}", m.pubkey("pubkey"), derived.address)
                assertEquals("${v.str("name")} ${m.str("role")} bump", pda.int("bump"), derived.bump)
            }
        }
        // The named helpers agree with the seeds.
        val alice = Golden.instructions.arr("users").map { it.jsonObject }.first { it.str("name") == "alice" }
        assertEquals(alice.pubkey("rig"), HeadsDownProgram.rig(alice.pubkey("wallet")).address)
        assertEquals(alice.pubkey("ore_automation"), Ore.automation(alice.pubkey("wallet")).address)
        assertEquals(alice.pubkey("ore_miner"), Ore.miner(alice.pubkey("wallet")).address)
    }

    @Test
    fun `P-256 transactions carry the precompile the phone builds from the signed message`() {
        val messages = Golden.messages.arr("messages").map { it.jsonObject }
        for (name in listOf("arm_shift_p256", "break_shift_p256", "freeze_rig_p256")) {
            val v = vectors.getValue(name)
            val auth = p256Auth(v)
            val kind = when (name) {
                "arm_shift_p256" -> RigMessageKind.PLAN
                "break_shift_p256" -> RigMessageKind.BREAK
                else -> RigMessageKind.FREEZE
            }
            val m = messages.single { it.int("kind") == kind.wire && it.obj("fields").u64("counter") == auth.counter }
            val signed = SignedRigMessage(payload(m), hexBytes(m.str("signature_low_s_hex")), hexBytes(Golden.messages.obj("p256_key").str("public_key_compressed_hex")))
            val precompile = txInstruction(v, auth.precompileIx)
            assertEquals(WellKnown.SECP256R1_SIG_VERIFY.toBase58(), precompile.str("program_id"))
            assertEquals(name, precompile.str("data_hex"), Secp256r1Instructions.verify(listOf(signed)).data.hex())
        }
    }

    @Test
    fun `the crank's heartbeat precompiles decode to the phone's HEARTBEAT preimages`() {
        // dig_batch_two_rigs: two heartbeats (dave, erin) in one precompile instruction.
        val v = vectors.getValue("dig_batch_two_rigs")
        val entries = v.obj("args").arr("entries").map { it.jsonObject }
        val rigs = v.arr("accounts").map { it.jsonObject }.filter { it.str("role").startsWith("rig") }.map { it.pubkey("pubkey") }
        val data = hexBytes(txInstruction(v, entries[0].int("hb_ix")).str("data_hex"))
        val count = data[0].toInt()
        assertEquals(2, count)
        val rebuilt = entries.mapIndexed { i, e ->
            val record = 2 + 14 * e.int("hb_sig_index")
            fun u16(at: Int) = (data[at].toInt() and 0xFF) or ((data[at + 1].toInt() and 0xFF) shl 8)
            val sigOff = u16(record)
            val keyOff = u16(record + 4)
            val msgOff = u16(record + 8)
            // A fresh wallet-armed rig is at shift 1 (vectors scenario steps 18-19).
            val payload = HeartbeatPreimage(HeadsDownProgram.ID.bytes, rigs[i].bytes, e.u64("counter"), 1uL, e.u64("round_id"), e.int("lease_rounds"))
            assertEquals(data.copyOfRange(msgOff, msgOff + 32).hex(), payload.digest().hex())
            SignedRigMessage(payload, data.copyOfRange(sigOff, sigOff + 64), data.copyOfRange(keyOff, keyOff + 33))
        }
        assertEquals(data.hex(), Secp256r1Instructions.verify(rebuilt).data.hex())
    }

    @Test
    fun `attested vectors carry a registrar voucher the phone accepts and references`() {
        for (name in listOf("register_rig_attested", "rotate_key_attested")) {
            val v = vectors.getValue(name)
            val a = v.obj("args")
            val ed = txInstruction(v, a.int("ed25519_ix"))
            assertEquals(WellKnown.ED25519_SIG_VERIFY.toBase58(), ed.str("program_id"))
            val voucher = RegistrarVoucher.verify(hexBytes(ed.str("data_hex")), role(v, "authority"), p256(v), a.int("level"), a.u64("expiry_slot"))
            assertEquals(Golden.instructions.obj("keys").obj("registrar").pubkey("pubkey"), voucher.registrar)
            assertEquals(ed.str("data_hex"), voucher.instruction.data.hex())
            assertEquals(attestation(v), voucher.attestation(a.int("ed25519_ix")))
        }
    }

    private fun payload(m: JsonObject): RigMessage {
        val f = m.obj("fields")
        val programId = HeadsDownProgram.ID.bytes
        val rig = Golden.messages.pubkey("rig").bytes
        val preimage: RigMessage = when (RigMessageKind.fromWire(m.int("kind"))) {
            RigMessageKind.HEARTBEAT -> HeartbeatPreimage(programId, rig, f.u64("counter"), f.u64("shift_id"), f.u64("round_id"), f.int("lease_rounds"))
            RigMessageKind.BREAK, RigMessageKind.FREEZE -> ShiftSignalPreimage(
                programId, rig, RigMessageKind.fromWire(m.int("kind")), f.u64("counter"), f.u64("shift_id"), ShiftEndReason.fromWire(f.int("reason")),
            )
            RigMessageKind.PLAN -> PlanPreimage(programId, rig, f.u64("counter"), planOf(f.obj("plan")))
        }
        assertEquals(m.str("preimage_hex"), preimage.preimage().hex())
        return preimage
    }
}
