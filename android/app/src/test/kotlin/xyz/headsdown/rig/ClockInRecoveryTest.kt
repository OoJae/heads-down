package xyz.headsdown.rig

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.headsdown.core.chain.HeadsDownProgram
import xyz.headsdown.core.chain.Pubkey
import xyz.headsdown.core.chain.accounts.HeadsDownAccounts
import xyz.headsdown.core.chain.accounts.RigAccount
import xyz.headsdown.core.chain.rpc.AccountInfo
import xyz.headsdown.core.keys.RigSignalState
import xyz.headsdown.feature.shift.ShiftMode
import xyz.headsdown.feature.shift.ShiftSpec
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A clock-in the wallet sent and this app never heard back about: the wallet approved, the
 * transaction landed, and the phone stayed cold until a second clock-in was approved and paid
 * for (mainnet, 10 and 11 October 2026).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ClockInRecoveryTest {

    private val authority = Pubkey(ByteArray(32) { 8 })
    private val myKey = byteArrayOf(0x03) + ByteArray(32) { 0x21 }
    private val now = 1_790_640_000L
    private val lastValid = 300_000L

    private val spec = ShiftSpec(shiftId = 5, mode = ShiftMode.NIGHT, plannedRounds = 400, leaseRounds = 3, windowEndUnix = now + 8 * 3600)

    private fun pending(spec: ShiftSpec = this.spec, preparedAt: Long = now - 10) =
        PendingClockIn(authority, myKey, spec, lastValid, preparedAt)

    private fun rig(
        shiftId: Long,
        key: ByteArray = myKey,
        state: RigSignalState = RigSignalState.ARMED,
        shiftOpen: Boolean = true,
    ): RigAccount {
        val pda = HeadsDownProgram.rig(authority)
        val data = ByteBuffer.allocate(384).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(0, 2); put(1, 1); put(2, pda.bump.toByte())
            position(8); put(authority.bytes)
            position(40); put(key)
            put(75, state.wire.toByte())
            put(178, 3)
            putLong(192, now + 8 * 3600)
            putLong(200, shiftId)
            putLong(208, 41) // hb_counter
            put(336, if (shiftOpen) 1 else 0)
        }.array()
        return HeadsDownAccounts.rig(pda.address, AccountInfo(1uL, HeadsDownProgram.ID, data, false))
    }

    private fun decide(rig: RigAccount?, height: Long? = lastValid - 100, pending: PendingClockIn = pending(), key: ByteArray? = myKey, at: Long = now) =
        ClockInRecovery.decide(pending, rig, key, at, height)

    @Test
    fun `the shift this phone prepared, armed on-chain for its key, is armed here`() {
        val arm = decide(rig(shiftId = 5)) as Recovery.Arm
        // The phone's own shift, not one read from the node; only the counter comes from the Rig.
        assertEquals(spec, arm.spec)
        assertEquals(41uL, arm.hbCounter)
        // A heartbeat may already have moved it on, and the blockhash's age no longer matters.
        assertTrue(decide(rig(shiftId = 5, state = RigSignalState.DOWN), height = lastValid + 10_000) is Recovery.Arm)
        assertTrue(decide(rig(shiftId = 5, state = RigSignalState.COOLING)) is Recovery.Arm)
    }

    @Test
    fun `a later shift id is never adopted from the node`() {
        assertTrue(decide(rig(shiftId = 6)) is Recovery.Drop)
        assertTrue(decide(rig(shiftId = 9_000)) is Recovery.Drop)
    }

    @Test
    fun `a clock-in that has not landed is waited for while it still can`() {
        assertEquals(Recovery.Wait, decide(rig(shiftId = 4)))
        // The first clock-in also registers the rig: no Rig yet is the same wait.
        assertEquals(Recovery.Wait, decide(null))
        assertEquals(Recovery.Wait, decide(rig(shiftId = 5, key = byteArrayOf(0x02) + ByteArray(32) { 0x44 })))
        // A node a few blocks behind the one that gave the height is not proof it never landed.
        assertEquals(Recovery.Wait, decide(rig(shiftId = 4), height = lastValid + ClockInRecovery.EXPIRY_MARGIN_BLOCKS))
        // The height could not be read: the clock alone decides, much later.
        assertEquals(Recovery.Wait, decide(rig(shiftId = 4), height = null))
    }

    @Test
    fun `a clock-in that can no longer land is forgotten`() {
        assertTrue(decide(rig(shiftId = 4), height = lastValid + ClockInRecovery.EXPIRY_MARGIN_BLOCKS + 1) is Recovery.Drop)
        assertTrue(decide(null, height = lastValid + 500) is Recovery.Drop)
        val old = pending(preparedAt = now - ClockInRecovery.GIVE_UP_SECONDS - 1)
        assertTrue(decide(rig(shiftId = 4), height = null, pending = old) is Recovery.Drop)
    }

    @Test
    fun `a shift that already ended, an ended window or another rig key arms nothing`() {
        assertTrue(decide(rig(shiftId = 5, shiftOpen = false)) is Recovery.Drop)
        assertTrue(decide(rig(shiftId = 5, state = RigSignalState.BROKEN)) is Recovery.Drop)
        assertTrue(decide(rig(shiftId = 5, state = RigSignalState.IDLE)) is Recovery.Drop)
        assertTrue(decide(rig(shiftId = 5), at = now + 8 * 3600) is Recovery.Drop)
        assertTrue(decide(rig(shiftId = 5), key = null) is Recovery.Drop)
        assertTrue(decide(rig(shiftId = 5), key = byteArrayOf(0x02) + ByteArray(32) { 0x44 }) is Recovery.Drop)
    }

    @Test
    fun `a new clock-in is not built over the one that landed a moment ago`() {
        // The Rig the new transaction was composed from sits on the pending shift, open: the
        // composer put end_shift in front and would arm the one after it.
        assertTrue(ClockInRecovery.landedUnder(pending(), authority, myKey, endsPreviousShift = true, expectedShiftId = 6uL))
        // Not landed yet: the new clock-in is for the same shift, and at most one of the two can arm.
        assertFalse(ClockInRecovery.landedUnder(pending(), authority, myKey, endsPreviousShift = false, expectedShiftId = 5uL))
        // An older shift is open (last night's, never clocked out): sealing that one is what a clock-in does.
        assertFalse(ClockInRecovery.landedUnder(pending(), authority, myKey, endsPreviousShift = true, expectedShiftId = 5uL))
        // The pending shift came and went: the Rig is past it and idle.
        assertFalse(ClockInRecovery.landedUnder(pending(), authority, myKey, endsPreviousShift = false, expectedShiftId = 6uL))
        // Another wallet, or another rig key, is another rig.
        assertFalse(ClockInRecovery.landedUnder(pending(), Pubkey(ByteArray(32) { 9 }), myKey, endsPreviousShift = true, expectedShiftId = 6uL))
        assertFalse(ClockInRecovery.landedUnder(pending(), authority, byteArrayOf(0x02) + ByteArray(32) { 0x44 }, endsPreviousShift = true, expectedShiftId = 6uL))
    }

    @Test
    fun `what was handed to the wallet is read back exactly, by a new process too`() {
        val app: Application = ApplicationProvider.getApplicationContext()
        val store = PendingClockInStore(app)
        assertNull(store.load())
        store.save(pending())
        val back = PendingClockInStore(app).load()!!
        assertEquals(authority, back.authority)
        assertArrayEquals(myKey, back.rigKey)
        assertEquals(spec, back.spec)
        assertEquals(lastValid, back.lastValidBlockHeight)
        assertEquals(now - 10, back.preparedAtUnix)
        // A focus-only shift has no planned length and no window: both come back absent, not zero.
        val focus = ShiftSpec(shiftId = 6, mode = ShiftMode.FOCUS_ONLY)
        store.save(pending(spec = focus))
        assertEquals(focus, store.load()!!.spec)
        store.clear()
        assertNull(PendingClockInStore(app).load())
    }
}
