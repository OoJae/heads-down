package xyz.headsdown.core.keys

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.headsdown.core.keys.TestCrypto.hex
import xyz.headsdown.core.keys.TestCrypto.toHex
import java.security.MessageDigest

/** Byte-exact layouts of INTERFACE.md "Signed P-256 messages", assembled by hand. */
class RigMessagesTest {

    private val programId = ByteArray(32) { (it + 1).toByte() } // 01..20
    private val rig = ByteArray(32) { (0xA0 + it).toByte() } // a0..bf
    private val header = hex("48447631") + programId + rig // "HDv1" | program_id | rig

    private val plan = ShiftPlan(
        maxEvCost = 530_000_000uL,
        digLamports = 1_000_000uL,
        splitTiles = 4,
        soloTiles = 1,
        leaseRounds = 2,
        flags = 0,
        windowStartTs = 1_790_000_000L,
        windowEndTs = 1_790_028_800L,
    )

    @Test
    fun `kind and reason bytes are frozen`() {
        assertEquals(listOf(1, 2, 3, 4), RigMessageKind.entries.map { it.wire })
        // v1.1 appended 7 unplugged and 8 unlocked.
        assertEquals((0..8).toList(), ShiftEndReason.entries.map { it.wire })
        assertEquals((0..5).toList(), RigSignalState.entries.map { it.wire })
        // INTERFACE v1.1 break_shift: 1, 2, 4, 5, 6, 7, 8 are BREAK reasons; 0 and 3 are not.
        assertEquals(listOf(1, 2, 4, 5, 6, 7, 8), ShiftEndReason.entries.filter { it.isBreakReason }.map { it.wire })
    }

    @Test
    fun `v1_1 break reasons and the DAY flag encode as their bytes`() {
        val unplugged = ShiftSignalPreimage(programId, rig, RigMessageKind.BREAK, 9uL, 7uL, ShiftEndReason.UNPLUGGED)
        val unlocked = ShiftSignalPreimage(programId, rig, RigMessageKind.BREAK, 10uL, 7uL, ShiftEndReason.UNLOCKED)
        assertEquals(7, unplugged.preimage()[RigMessageFormat.OFFSET_SIGNAL_REASON].toInt())
        assertEquals(8, unlocked.preimage()[RigMessageFormat.OFFSET_SIGNAL_REASON].toInt())
        assertEquals(unlocked, RigMessage.decode(unlocked.preimage()))
        val day = plan.copy(flags = ShiftPlan.FLAG_DAY)
        assertTrue(day.day && !day.focusOnly)
        assertEquals(0x02, day.encode()[19].toInt()) // flags byte of the 36-byte plan
        assertEquals(day, ShiftPlan.decode(day.encode(), 0))
        val focusDay = plan.copy(splitTiles = 0, soloTiles = 0, flags = ShiftPlan.FLAG_FOCUS_ONLY or ShiftPlan.FLAG_DAY)
        assertTrue(focusDay.focusOnly && focusDay.day)
    }

    @Test
    fun `heartbeat preimage is the exact 94-byte layout`() {
        val m = HeartbeatPreimage(programId, rig, counter = 42uL, shiftId = 7uL, roundId = 0x0102030405060708uL, leaseRounds = 3)
        val expected = header +
            hex("01") + // kind = HEARTBEAT
            hex("2a00000000000000") + // counter = 42
            hex("0700000000000000") + // shift_id = 7
            hex("0807060504030201") + // round_id LE
            hex("03") // lease_rounds
        assertEquals(94, RigMessageFormat.HEARTBEAT_BYTES)
        assertEquals(expected.toHex(), m.preimage().toHex())
    }

    @Test
    fun `break and freeze preimages are the exact 86-byte layout`() {
        val brk = ShiftSignalPreimage(programId, rig, RigMessageKind.BREAK, 43uL, 7uL, ShiftEndReason.PICKUP)
        val frz = ShiftSignalPreimage(programId, rig, RigMessageKind.FREEZE, 44uL, 7uL, ShiftEndReason.FREEZE)
        assertEquals(86, RigMessageFormat.SIGNAL_BYTES)
        assertEquals(
            (header + hex("02") + hex("2b00000000000000") + hex("0700000000000000") + hex("01")).toHex(),
            brk.preimage().toHex(),
        )
        assertEquals(
            (header + hex("03") + hex("2c00000000000000") + hex("0700000000000000") + hex("03")).toHex(),
            frz.preimage().toHex(),
        )
    }

    @Test
    fun `plan preimage is the exact 113-byte layout`() {
        val m = PlanPreimage(programId, rig, counter = 41uL, plan = plan)
        val expected = header +
            hex("04") + // kind = PLAN
            hex("2900000000000000") + // counter = 41
            hex("8028971f00000000") + // max_ev_cost = 530_000_000 = 0x1F972880
            hex("40420f0000000000") + // dig_lamports = 1_000_000 = 0x0F4240
            hex("04") + hex("01") + hex("02") + hex("00") + // split, solo, lease, flags
            hex("803bb16a00000000") + // window_start = 1_790_000_000 = 0x6AB13B80
            hex("00acb16a00000000") // window_end = 1_790_028_800 = 0x6AB1AC00
        assertEquals(113, RigMessageFormat.PLAN_BYTES)
        assertEquals(36, ShiftPlan.ENCODED_BYTES)
        assertEquals(expected.toHex(), m.preimage().toHex())
    }

    @Test
    fun `documented offsets match the encodings`() {
        val hb = HeartbeatPreimage(programId, rig, 0x11uL, 0x22uL, 0x33uL, 1).preimage()
        assertEquals(0x01, hb[RigMessageFormat.OFFSET_PROGRAM_ID].toInt())
        assertEquals(0xA0.toByte(), hb[RigMessageFormat.OFFSET_RIG])
        assertEquals(1, hb[RigMessageFormat.OFFSET_KIND].toInt())
        assertEquals(0x11, hb[RigMessageFormat.OFFSET_COUNTER].toInt())
        assertEquals(0x22, hb[RigMessageFormat.OFFSET_HB_SHIFT_ID].toInt())
        assertEquals(0x33, hb[RigMessageFormat.OFFSET_HB_ROUND_ID].toInt())
        assertEquals(1, hb[RigMessageFormat.OFFSET_HB_LEASE_ROUNDS].toInt())

        val sig = ShiftSignalPreimage(programId, rig, RigMessageKind.BREAK, 1uL, 0x44uL, ShiftEndReason.MANUAL).preimage()
        assertEquals(0x44, sig[RigMessageFormat.OFFSET_SIGNAL_SHIFT_ID].toInt())
        assertEquals(6, sig[RigMessageFormat.OFFSET_SIGNAL_REASON].toInt())
    }

    @Test
    fun `digest is SHA-256 of the preimage and is what gets signed`() {
        val m = HeartbeatPreimage(programId, rig, 1uL, 2uL, 3uL, 1)
        val expected = MessageDigest.getInstance("SHA-256").digest(m.preimage())
        assertArrayEquals(expected, m.digest())
        assertEquals(32, m.digest().size)
    }

    @Test
    fun `every field changes the digest, so no signature transfers between messages`() {
        val base = HeartbeatPreimage(programId, rig, 10uL, 5uL, 100uL, 1)
        val variants = listOf(
            HeartbeatPreimage(programId, rig, 11uL, 5uL, 100uL, 1),
            HeartbeatPreimage(programId, rig, 10uL, 6uL, 100uL, 1),
            HeartbeatPreimage(programId, rig, 10uL, 5uL, 101uL, 1),
            HeartbeatPreimage(programId, rig, 10uL, 5uL, 100uL, 2),
            HeartbeatPreimage(ByteArray(32), rig, 10uL, 5uL, 100uL, 1),
            HeartbeatPreimage(programId, ByteArray(32), 10uL, 5uL, 100uL, 1),
        )
        val digests = (variants + base).map { it.digest().toHex() }.toSet()
        assertEquals(variants.size + 1, digests.size)
        // Cross-kind: a BREAK with the same counter and shift is a different message.
        val brk = ShiftSignalPreimage(programId, rig, RigMessageKind.BREAK, 10uL, 5uL, ShiftEndReason.COMPLETED)
        val frz = ShiftSignalPreimage(programId, rig, RigMessageKind.FREEZE, 10uL, 5uL, ShiftEndReason.COMPLETED)
        assertNotEquals(brk.digest().toHex(), frz.digest().toHex())
        assertNotEquals(base.digest().toHex(), brk.digest().toHex())
    }

    @Test
    fun `u64 fields use the full unsigned range`() {
        val max = ULong.MAX_VALUE
        val hb = HeartbeatPreimage(programId, rig, max, max, max, 3)
        val e = hb.preimage()
        assertEquals("ffffffffffffffff", e.copyOfRange(69, 77).toHex())
        val decoded = RigMessage.decode(e) as HeartbeatPreimage
        assertEquals(max, decoded.counter)
        assertEquals(max, decoded.roundId)
    }

    @Test
    fun `decode inverts every kind`() {
        val messages = listOf(
            HeartbeatPreimage(programId, rig, 9uL, 3uL, 77uL, 2),
            ShiftSignalPreimage(programId, rig, RigMessageKind.BREAK, 10uL, 3uL, ShiftEndReason.SCREEN_ON),
            ShiftSignalPreimage(programId, rig, RigMessageKind.FREEZE, 11uL, 3uL, ShiftEndReason.FREEZE),
            PlanPreimage(programId, rig, 12uL, plan),
        )
        for (m in messages) {
            val back = RigMessage.decode(m.preimage())
            assertEquals(m, back)
            assertArrayEquals(m.preimage(), back.preimage())
            assertArrayEquals(programId, back.programId)
            assertArrayEquals(rig, back.rig)
        }
    }

    @Test
    fun `decode rejects malformed input`() {
        val hb = HeartbeatPreimage(programId, rig, 1uL, 1uL, 1uL, 1).preimage()
        val cases = mapOf(
            "empty" to ByteArray(0),
            "truncated" to hb.copyOf(93),
            "trailing byte" to hb + byteArrayOf(0),
            "bad domain" to hb.copyOf().also { it[3] = '2'.code.toByte() },
            "unknown kind" to hb.copyOf().also { it[RigMessageFormat.OFFSET_KIND] = 9 },
            "kind zero" to hb.copyOf().also { it[RigMessageFormat.OFFSET_KIND] = 0 },
            "lease zero" to hb.copyOf().also { it[RigMessageFormat.OFFSET_HB_LEASE_ROUNDS] = 0 },
            "lease four" to hb.copyOf().also { it[RigMessageFormat.OFFSET_HB_LEASE_ROUNDS] = 4 },
            "heartbeat bytes labelled BREAK" to hb.copyOf().also { it[RigMessageFormat.OFFSET_KIND] = 2 },
        )
        for ((name, bytes) in cases) {
            assertThrows(name, IllegalArgumentException::class.java) { RigMessage.decode(bytes) }
        }
        val sig = ShiftSignalPreimage(programId, rig, RigMessageKind.BREAK, 1uL, 1uL, ShiftEndReason.PICKUP).preimage()
        assertThrows(IllegalArgumentException::class.java) {
            RigMessage.decode(sig.copyOf().also { it[RigMessageFormat.OFFSET_SIGNAL_REASON] = 9 })
        }
    }

    @Test
    fun `constructors reject out-of-range fields`() {
        assertThrows(IllegalArgumentException::class.java) { HeartbeatPreimage(programId, rig, 1uL, 1uL, 1uL, 0) }
        assertThrows(IllegalArgumentException::class.java) { HeartbeatPreimage(programId, rig, 1uL, 1uL, 1uL, 4) }
        assertThrows(IllegalArgumentException::class.java) { HeartbeatPreimage(ByteArray(31), rig, 1uL, 1uL, 1uL, 1) }
        assertThrows(IllegalArgumentException::class.java) { HeartbeatPreimage(programId, ByteArray(33), 1uL, 1uL, 1uL, 1) }
        assertThrows(IllegalArgumentException::class.java) {
            ShiftSignalPreimage(programId, rig, RigMessageKind.HEARTBEAT, 1uL, 1uL, ShiftEndReason.PICKUP)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ShiftSignalPreimage(programId, rig, RigMessageKind.PLAN, 1uL, 1uL, ShiftEndReason.PICKUP)
        }
    }

    @Test
    fun `plan validation mirrors the INTERFACE ranges`() {
        assertThrows(IllegalArgumentException::class.java) { plan.copy(splitTiles = 16) }
        assertThrows(IllegalArgumentException::class.java) { plan.copy(soloTiles = 11) }
        assertThrows(IllegalArgumentException::class.java) { plan.copy(splitTiles = -1) }
        assertThrows(IllegalArgumentException::class.java) { plan.copy(leaseRounds = 0) }
        assertThrows(IllegalArgumentException::class.java) { plan.copy(leaseRounds = 4) }
        assertThrows(IllegalArgumentException::class.java) { plan.copy(flags = 0x04) }
        assertThrows(IllegalArgumentException::class.java) { plan.copy(splitTiles = 0, soloTiles = 0) }
        assertThrows(IllegalArgumentException::class.java) { plan.copy(windowEndTs = plan.windowStartTs) }
        // Focus-only needs no tiles.
        val focus = plan.copy(splitTiles = 0, soloTiles = 0, flags = ShiftPlan.FLAG_FOCUS_ONLY)
        assertTrue(focus.focusOnly)
        assertEquals(focus, ShiftPlan.decode(focus.encode(), 0))
        assertEquals(15 + 10, plan.copy(splitTiles = 15, soloTiles = 10).tiles)
        assertThrows(IllegalArgumentException::class.java) { ShiftPlan.decode(ByteArray(35), 0) }
    }

    @Test
    fun `inputs are defensively copied`() {
        val pid = programId.copyOf()
        val m = HeartbeatPreimage(pid, rig, 1uL, 1uL, 1uL, 1)
        val before = m.preimage()
        pid.fill(0)
        m.programId.fill(0)
        m.rig.fill(0)
        assertArrayEquals(before, m.preimage())
    }
}
