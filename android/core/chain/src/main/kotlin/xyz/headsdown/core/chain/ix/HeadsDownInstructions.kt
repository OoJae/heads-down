package xyz.headsdown.core.chain.ix

import xyz.headsdown.core.chain.HeadsDownProgram
import xyz.headsdown.core.chain.Pubkey
import xyz.headsdown.core.chain.WellKnown
import xyz.headsdown.core.chain.tx.AccountMeta
import xyz.headsdown.core.chain.tx.DataWriter
import xyz.headsdown.core.chain.tx.Instruction
import xyz.headsdown.core.keys.P256
import xyz.headsdown.core.keys.ShiftEndReason
import xyz.headsdown.core.keys.ShiftPlan

/** Wallet-signed caps (`Rig.cap_*`). Lamports, lamports per ORE, and unix seconds. */
data class RigCaps(
    val capWeek: ULong,
    val capShift: ULong,
    val capRound: ULong,
    /** Ceiling on the pot-adjusted cost `ema_ev`, lamports per ORE. */
    val capMaxCost: ULong,
    val capsExpiryTs: Long,
) {
    init {
        require(capRound <= capShift && capShift <= capWeek) { "caps must satisfy round <= shift <= week" }
        require(capsExpiryTs > 0) { "caps need an expiry" }
    }

    /** INTERFACE: the plan must fit the caps (`plan_max_ev_cost <= cap_max_cost`, `dig <= cap_round`). */
    fun admits(plan: ShiftPlan): Boolean =
        plan.maxEvCost <= capMaxCost && plan.digLamports <= capRound
}

/**
 * The P-256 authorization tail for instructions a rig key may sign instead of the wallet
 * (PLAN / BREAK / FREEZE): the message counter plus the location of the signature in the
 * transaction's `Secp256r1SigVerify` instruction. The program rebuilds the preimage from its
 * own state and these fields and requires the precompile's message to equal its SHA-256.
 */
data class P256Auth(val counter: ULong, val precompileIx: Int, val sigIndex: Int) {
    init {
        require(precompileIx in 0..0xFE) { "precompile instruction index must be 0..254" }
        require(sigIndex in 0 until Secp256r1Instructions.MAX_SIGNATURES) { "signature index must be 0..7" }
    }
}

/** An Ed25519 registrar attestation carried by `register_rig` (INTERFACE, tag 1). */
data class RegistrarAttestation(val ed25519Ix: Int, val level: Int, val expirySlot: ULong) {
    init {
        require(ed25519Ix in 0..0xFE) { "Ed25519 instruction index must be 0..254" }
        require(level in 1..2) { "attestation level is 1 (TEE) or 2 (StrongBox)" }
    }
}

/**
 * `heads_down` instruction builders. `data[0]` is the INTERFACE tag; the remaining layouts
 * are defined in `android/INTERFACE-NOTES.md` (INTERFACE.md lists only the summaries) and
 * pinned byte for byte by `core/chain/src/test/resources/ix_vectors.json`.
 *
 * Security properties the builders keep:
 * - the Rig address is always the PDA `[b"rig", authority]`, never a free parameter on the
 *   wallet path;
 * - no bump, executor address or amount the program must derive itself is ever sent;
 * - every value is range-checked, so an invalid instruction is never built.
 */
object HeadsDownInstructions {
    const val TAG_REGISTER_RIG = 1
    const val TAG_SET_CAPS = 3
    const val TAG_ROTATE_KEY = 4
    const val TAG_ARM_SHIFT = 5
    const val TAG_BREAK_SHIFT = 8
    const val TAG_FREEZE_RIG = 9
    const val TAG_UNFREEZE_RIG = 10
    const val TAG_END_SHIFT = 11
    const val TAG_CLOSE_RIG = 14

    /** Authorization-mode byte after the tag of dual-path instructions. */
    const val AUTH_WALLET = 0
    const val AUTH_P256 = 1

    /** `attestation_ix` value meaning "no registrar attestation" (a guest rig). */
    const val NO_ATTESTATION = 0xFF

    private val programId get() = HeadsDownProgram.ID

    /**
     * `register_rig` (tag 1), 44 bytes:
     * `tag | p256_pubkey[33] | attestation_ix u8 (0xFF none) | attestation_level u8 | attestation_expiry_slot u64`.
     *
     * Accounts: `authority (signer, writable: pays rent) | config | rig (w) | system | instructions sysvar`.
     */
    fun registerRig(authority: Pubkey, p256Pubkey: ByteArray, attestation: RegistrarAttestation? = null): Instruction {
        val data = keyBody(TAG_REGISTER_RIG, p256Pubkey, attestation)
        return Instruction(
            programId,
            listOf(
                AccountMeta.signer(authority),
                AccountMeta.readonly(HeadsDownProgram.config.address),
                AccountMeta.writable(HeadsDownProgram.rig(authority).address),
                AccountMeta.readonly(WellKnown.SYSTEM_PROGRAM),
                AccountMeta.readonly(WellKnown.INSTRUCTIONS_SYSVAR),
            ),
            data,
        )
    }

    /**
     * `rotate_key` (tag 4), 44 bytes, same body as [registerRig]:
     * `tag | new p256_pubkey[33] | attestation_ix u8 (0xFF none) | attestation_level u8 | attestation_expiry_slot u64`.
     * Used when the Rig exists but this install holds a different Keystore key (reinstall,
     * new phone): without it every heartbeat would fail the on-chain pubkey check.
     *
     * Accounts: `authority (signer) | config | rig (w) | instructions sysvar`.
     */
    fun rotateKey(authority: Pubkey, p256Pubkey: ByteArray, attestation: RegistrarAttestation? = null): Instruction =
        Instruction(
            programId,
            listOf(
                AccountMeta.signer(authority, writable = false),
                AccountMeta.readonly(HeadsDownProgram.config.address),
                AccountMeta.writable(HeadsDownProgram.rig(authority).address),
                AccountMeta.readonly(WellKnown.INSTRUCTIONS_SYSVAR),
            ),
            keyBody(TAG_ROTATE_KEY, p256Pubkey, attestation),
        )

    /**
     * `set_caps` (tag 3), 41 bytes:
     * `tag | cap_week u64 | cap_shift u64 | cap_round u64 | cap_max_cost u64 | caps_expiry_ts i64`.
     *
     * Accounts: `authority (signer) | rig (w)`.
     */
    fun setCaps(authority: Pubkey, caps: RigCaps): Instruction {
        val data = DataWriter(41)
            .u8(TAG_SET_CAPS)
            .u64(caps.capWeek)
            .u64(caps.capShift)
            .u64(caps.capRound)
            .u64(caps.capMaxCost)
            .i64(caps.capsExpiryTs)
            .build()
        return walletRigInstruction(authority, data)
    }

    /**
     * `arm_shift` (tag 5), wallet path, 38 bytes: `tag | auth=0 | plan(36)` where plan is
     * `max_ev_cost u64 | dig_lamports u64 | split u8 | solo u8 | lease u8 | flags u8 |
     * window_start i64 | window_end i64` (the PLAN preimage field order).
     *
     * Accounts: `authority (signer) | rig (w)`.
     */
    fun armShift(authority: Pubkey, plan: ShiftPlan): Instruction {
        val data = DataWriter(2 + ShiftPlan.ENCODED_BYTES)
            .u8(TAG_ARM_SHIFT)
            .u8(AUTH_WALLET)
            .bytes(plan.encode())
            .build()
        return walletRigInstruction(authority, data)
    }

    /**
     * `arm_shift` (tag 5), P-256 PLAN path, 48 bytes: `tag | auth=1 | plan(36) | precompile_ix u8 |
     * sig_index u8 | counter u64`.
     *
     * Accounts: `payer (signer, w) | rig (w) | instructions sysvar`.
     */
    fun armShiftP256(payer: Pubkey, rig: Pubkey, plan: ShiftPlan, auth: P256Auth): Instruction {
        val data = DataWriter(2 + ShiftPlan.ENCODED_BYTES + 10)
            .u8(TAG_ARM_SHIFT)
            .u8(AUTH_P256)
            .bytes(plan.encode())
            .p256Tail(auth)
            .build()
        return p256RigInstruction(payer, rig, data)
    }

    /** `break_shift` (tag 8), wallet path: `tag | auth=0 | reason u8`. Accounts: `authority (signer) | rig (w)`. */
    fun breakShift(authority: Pubkey, reason: ShiftEndReason): Instruction =
        walletRigInstruction(authority, DataWriter(3).u8(TAG_BREAK_SHIFT).u8(AUTH_WALLET).u8(reason.wire).build())

    /**
     * `break_shift` (tag 8), P-256 BREAK path, 13 bytes: `tag | auth=1 | reason u8 | precompile_ix u8 |
     * sig_index u8 | counter u64`. Accounts: `payer (signer, w) | rig (w) | instructions sysvar`.
     */
    fun breakShiftP256(payer: Pubkey, rig: Pubkey, reason: ShiftEndReason, auth: P256Auth): Instruction =
        p256RigInstruction(payer, rig, DataWriter(13).u8(TAG_BREAK_SHIFT).u8(AUTH_P256).u8(reason.wire).p256Tail(auth).build())

    /** `freeze_rig` (tag 9), wallet path: `tag | auth=0 | reason u8`. */
    fun freezeRig(authority: Pubkey, reason: ShiftEndReason = ShiftEndReason.FREEZE): Instruction =
        walletRigInstruction(authority, DataWriter(3).u8(TAG_FREEZE_RIG).u8(AUTH_WALLET).u8(reason.wire).build())

    /** `freeze_rig` (tag 9), P-256 FREEZE path: same shape as [breakShiftP256]. */
    fun freezeRigP256(payer: Pubkey, rig: Pubkey, auth: P256Auth, reason: ShiftEndReason = ShiftEndReason.FREEZE): Instruction =
        p256RigInstruction(payer, rig, DataWriter(13).u8(TAG_FREEZE_RIG).u8(AUTH_P256).u8(reason.wire).p256Tail(auth).build())

    /** `unfreeze_rig` (tag 10): `tag`. Wallet only. Accounts: `authority (signer) | rig (w)`. */
    fun unfreezeRig(authority: Pubkey): Instruction = walletRigInstruction(authority, byteArrayOf(TAG_UNFREEZE_RIG.toByte()))

    /**
     * `end_shift` (tag 11): `tag`. Callable by the authority, or by anyone once the plan window
     * has ended and the lease expired. [shiftId] is the Rig's current `shift_id`: the ShiftLog
     * it writes is `[b"shift", rig, shift_id]`.
     *
     * Accounts: `caller (signer, w: pays the ShiftLog rent) | rig (w) | shift_log (w) | system`.
     */
    fun endShift(caller: Pubkey, rig: Pubkey, shiftId: ULong): Instruction = Instruction(
        programId,
        listOf(
            AccountMeta.signer(caller),
            AccountMeta.writable(rig),
            AccountMeta.writable(HeadsDownProgram.shiftLog(rig, shiftId).address),
            AccountMeta.readonly(WellKnown.SYSTEM_PROGRAM),
        ),
        byteArrayOf(TAG_END_SHIFT.toByte()),
    )

    /**
     * `close_rig` (tag 14): `tag`. Requires state Idle/Frozen; rent returns to the authority.
     * Accounts: `authority (signer, w) | rig (w) [| seeker_seat (w) for a seeker-tier rig]`.
     */
    fun closeRig(authority: Pubkey, sgtMint: Pubkey? = null): Instruction {
        val accounts = mutableListOf(
            AccountMeta.signer(authority),
            AccountMeta.writable(HeadsDownProgram.rig(authority).address),
        )
        if (sgtMint != null) accounts += AccountMeta.writable(HeadsDownProgram.seekerSeat(sgtMint).address)
        return Instruction(programId, accounts, byteArrayOf(TAG_CLOSE_RIG.toByte()))
    }

    // ------------------------------------------------------------------------------ helpers

    private fun keyBody(tag: Int, p256Pubkey: ByteArray, attestation: RegistrarAttestation?): ByteArray {
        require(p256Pubkey.size == P256.COMPRESSED_PUBLIC_KEY_BYTES) { "p256 key must be 33-byte compressed" }
        P256.decompress(p256Pubkey) // throws unless it is a point on P-256
        return DataWriter(44)
            .u8(tag)
            .bytes(p256Pubkey)
            .u8(attestation?.ed25519Ix ?: NO_ATTESTATION)
            .u8(attestation?.level ?: 0)
            .u64(attestation?.expirySlot ?: 0uL)
            .build()
    }

    private fun walletRigInstruction(authority: Pubkey, data: ByteArray) = Instruction(
        programId,
        listOf(
            AccountMeta.signer(authority, writable = false),
            AccountMeta.writable(HeadsDownProgram.rig(authority).address),
        ),
        data,
    )

    private fun p256RigInstruction(payer: Pubkey, rig: Pubkey, data: ByteArray) = Instruction(
        programId,
        listOf(
            AccountMeta.signer(payer),
            AccountMeta.writable(rig),
            AccountMeta.readonly(WellKnown.INSTRUCTIONS_SYSVAR),
        ),
        data,
    )

    /** Same field order as a `dig` entry: `hb_ix u8 | hb_sig_index u8 | counter u64`. */
    private fun DataWriter.p256Tail(auth: P256Auth) = u8(auth.precompileIx).u8(auth.sigIndex).u64(auth.counter)
}
