package xyz.headsdown.core.chain.accounts

import okio.ByteString
import okio.ByteString.Companion.toByteString
import xyz.headsdown.core.chain.HeadsDownProgram
import xyz.headsdown.core.chain.Pubkey
import xyz.headsdown.core.chain.rpc.AccountInfo
import xyz.headsdown.core.keys.RigSignalState

/** heads_down `Config` (256 bytes, tag 1; INTERFACE "Config"). */
data class HdConfig(
    val bump: Int,
    val governance: Pubkey,
    val registrar: Pubkey,
    val crankFee: ULong,
    /** The Discretionary `fee` every rig's ORE Automation must use (checked in `dig`). */
    val executorFee: ULong,
    val buryBps: Int,
    val paused: Boolean,
    val executorBump: Int,
    val oreLayoutHash: ByteString,
    val pendingExists: Boolean,
    val pendingEtaSlot: ULong,
)

/** `Rig.tier`. */
enum class RigTier(val wire: Int) { GUEST(0), SEEKER(1) }

/** heads_down `Rig` (384 bytes, tag 2; INTERFACE "Rig"). Field names follow the contract. */
data class RigAccount(
    val address: Pubkey,
    val bump: Int,
    val authority: Pubkey,
    /** 33-byte SEC1 compressed Keystore key. */
    val p256Pubkey: ByteString,
    val attestationLevel: Int,
    val tier: RigTier,
    val state: RigSignalState,
    val sgtMint: Pubkey?,
    val attestationExpirySlot: ULong,
    val capWeek: ULong,
    val capShift: ULong,
    val capRound: ULong,
    val capMaxCost: ULong,
    val capsExpiryTs: Long,
    val planMaxEvCost: ULong,
    val planDigLamports: ULong,
    val planSplitTiles: Int,
    val planSoloTiles: Int,
    val planLeaseRounds: Int,
    val planFlags: Int,
    val planWindowStartTs: Long,
    val planWindowEndTs: Long,
    val shiftId: ULong,
    val hbCounter: ULong,
    val leaseFromRound: ULong,
    val leaseToRound: ULong,
    val gapCount: Long,
    val spentShift: ULong,
    val spentWeek: ULong,
    val weekStartTs: Long,
    val lastDugRound: ULong,
    val shiftStartRound: ULong,
    val shiftDarkRounds: ULong,
    val shiftRoundsDug: ULong,
    val lifetimeDarkRounds: ULong,
    val lifetimeRoundsDug: ULong,
    val lifetimeLamportsDeployed: ULong,
    val streak: Long,
    val freezesLeft: Int,
    val lastShiftDay: Long,
)

/**
 * Decoders for heads_down accounts. Per the INTERFACE header rule every read is preceded by an
 * owner, exact-size, tag and version check, and the address must be the canonical PDA (for a
 * Rig: re-derived from the authority stored in it, with the stored bump equal to the canonical
 * one).
 */
object HeadsDownAccounts {
    const val VERSION = 1
    const val CONFIG_TAG = 1
    const val RIG_TAG = 2
    const val CONFIG_SIZE = 256
    const val RIG_SIZE = 384

    fun config(address: Pubkey, account: AccountInfo): HdConfig {
        val canonical = HeadsDownProgram.config
        if (address != canonical.address) throw AccountLayoutException("not the heads_down Config PDA")
        val b = header(account, CONFIG_SIZE, CONFIG_TAG, "Config")
        if (b.u8(2) != canonical.bump) throw AccountLayoutException("Config bump is not canonical")
        val executorBump = b.u8(91)
        if (executorBump != HeadsDownProgram.executor.bump) throw AccountLayoutException("Config executor_bump is not canonical")
        return HdConfig(
            bump = b.u8(2),
            governance = b.pubkey(8),
            registrar = b.pubkey(40),
            crankFee = b.u64(72),
            executorFee = b.u64(80),
            buryBps = b.u16(88),
            paused = flag(b.u8(90), "paused"),
            executorBump = executorBump,
            oreLayoutHash = b.bytes(96, 32).toByteString(),
            pendingExists = flag(b.u8(128), "pending_exists"),
            pendingEtaSlot = b.u64(136),
        )
    }

    fun rig(address: Pubkey, account: AccountInfo): RigAccount {
        val b = header(account, RIG_SIZE, RIG_TAG, "Rig")
        val authority = b.pubkey(8)
        val canonical = HeadsDownProgram.rig(authority)
        if (canonical.address != address) throw AccountLayoutException("Rig is not the PDA of its authority")
        if (b.u8(2) != canonical.bump) throw AccountLayoutException("Rig bump is not canonical")
        val tier = RigTier.entries.firstOrNull { it.wire == b.u8(74) } ?: throw AccountLayoutException("unknown tier")
        val state = try {
            RigSignalState.fromWire(b.u8(75))
        } catch (_: IllegalArgumentException) {
            throw AccountLayoutException("unknown rig state")
        }
        return RigAccount(
            address = address,
            bump = b.u8(2),
            authority = authority,
            p256Pubkey = b.bytes(40, 33).toByteString(),
            attestationLevel = b.u8(73),
            tier = tier,
            state = state,
            sgtMint = b.optionalPubkey(80),
            attestationExpirySlot = b.u64(112),
            capWeek = b.u64(120),
            capShift = b.u64(128),
            capRound = b.u64(136),
            capMaxCost = b.u64(144),
            capsExpiryTs = b.i64(152),
            planMaxEvCost = b.u64(160),
            planDigLamports = b.u64(168),
            planSplitTiles = b.u8(176),
            planSoloTiles = b.u8(177),
            planLeaseRounds = b.u8(178),
            planFlags = b.u8(179),
            planWindowStartTs = b.i64(184),
            planWindowEndTs = b.i64(192),
            shiftId = b.u64(200),
            hbCounter = b.u64(208),
            leaseFromRound = b.u64(216),
            leaseToRound = b.u64(224),
            gapCount = b.u32(232),
            spentShift = b.u64(240),
            spentWeek = b.u64(248),
            weekStartTs = b.i64(256),
            lastDugRound = b.u64(264),
            shiftStartRound = b.u64(272),
            shiftDarkRounds = b.u64(280),
            shiftRoundsDug = b.u64(288),
            lifetimeDarkRounds = b.u64(296),
            lifetimeRoundsDug = b.u64(304),
            lifetimeLamportsDeployed = b.u64(312),
            streak = b.u32(320),
            freezesLeft = b.u8(324),
            lastShiftDay = b.i64(328),
        )
    }

    private fun header(account: AccountInfo, size: Int, tag: Int, name: String): AccountBytes {
        if (account.owner != HeadsDownProgram.ID) throw AccountLayoutException("$name is not owned by heads_down")
        val b = AccountBytes(account.data)
        if (b.size != size) throw AccountLayoutException("$name must be $size bytes, was ${b.size}")
        if (b.u8(0) != tag) throw AccountLayoutException("$name tag ${b.u8(0)} != $tag")
        if (b.u8(1) != VERSION) throw AccountLayoutException("$name version ${b.u8(1)} is not $VERSION")
        return b
    }

    private fun flag(value: Int, name: String): Boolean = when (value) {
        0 -> false
        1 -> true
        else -> throw AccountLayoutException("$name must be 0 or 1")
    }
}
