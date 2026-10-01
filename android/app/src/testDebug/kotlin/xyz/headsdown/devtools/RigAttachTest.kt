package xyz.headsdown.devtools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.headsdown.core.chain.HeadsDownProgram
import xyz.headsdown.core.chain.Pubkey
import xyz.headsdown.core.chain.accounts.HeadsDownAccounts
import xyz.headsdown.core.chain.accounts.RigAccount
import xyz.headsdown.core.chain.rpc.AccountInfo
import xyz.headsdown.core.keys.RigSignalState
import xyz.headsdown.core.keys.ShiftPlan
import xyz.headsdown.feature.shift.ShiftMode
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Attaching the phone to a Rig that scripts/devstack/clock-in.sh armed with a Mac wallet. */
class RigAttachTest {

    private val authority = Pubkey(ByteArray(32) { 8 })
    private val myKey = byteArrayOf(0x03) + ByteArray(32) { 0x21 }
    private val now = 1_790_640_000L

    private fun rig(
        key: ByteArray = myKey,
        state: RigSignalState = RigSignalState.ARMED,
        shiftOpen: Boolean = true,
        flags: Int = 0,
        lease: Int = 3,
        windowEnd: Long = now + 8 * 3600,
    ): RigAccount {
        val pda = HeadsDownProgram.rig(authority)
        val data = ByteBuffer.allocate(384).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(0, 2); put(1, 1); put(2, pda.bump.toByte())
            position(8); put(authority.bytes)
            position(40); put(key)
            put(75, state.wire.toByte())
            put(178, lease.toByte()); put(179, flags.toByte())
            putLong(192, windowEnd)
            putLong(200, 4) // shift_id
            putLong(208, 41) // hb_counter
            put(336, if (shiftOpen) 1 else 0)
        }.array()
        return HeadsDownAccounts.rig(pda.address, AccountInfo(1uL, HeadsDownProgram.ID, data, false))
    }

    @Test
    fun `this phone's armed rig is attached with its shift, lease and window`() {
        val d = RigAttach.decide(rig(), myKey, now) as AttachDecision.Arm
        assertEquals(4L, d.spec.shiftId)
        assertEquals(ShiftMode.NIGHT, d.spec.mode)
        assertEquals(3, d.spec.leaseRounds)
        assertEquals(now + 8 * 3600, d.spec.windowEndUnix)
        assertEquals(41uL, d.hbCounter)
        assertEquals(ShiftMode.DAY, (RigAttach.decide(rig(flags = ShiftPlan.FLAG_DAY), myKey, now) as AttachDecision.Arm).spec.mode)
        assertEquals(ShiftMode.FOCUS_ONLY, (RigAttach.decide(rig(flags = ShiftPlan.FLAG_FOCUS_ONLY or ShiftPlan.FLAG_DAY), myKey, now) as AttachDecision.Arm).spec.mode)
    }

    @Test
    fun `another key, no shift, an ended window or no key at all is refused`() {
        listOf(
            RigAttach.decide(null, myKey, now),
            RigAttach.decide(rig(), null, now),
            RigAttach.decide(rig(key = byteArrayOf(0x02) + ByteArray(32) { 0x21 }), myKey, now),
            RigAttach.decide(rig(state = RigSignalState.IDLE, shiftOpen = false), myKey, now),
            RigAttach.decide(rig(state = RigSignalState.BROKEN), myKey, now),
            RigAttach.decide(rig(windowEnd = now - 1), myKey, now),
        ).forEach { assertTrue("$it", it is AttachDecision.Refuse) }
    }

    @Test
    fun `the clock-in command carries the 33-byte key as lower-case hex`() {
        val info = RigDebugInfo(myKey.lowerHex(), null, null, "none", "0", emptyList())
        assertEquals("scripts/devstack/clock-in.sh 03" + "21".repeat(32), info.clockInCommand)
        assertEquals(66, myKey.lowerHex().length)
    }
}
