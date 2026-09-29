package xyz.headsdown.core.keys

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import xyz.headsdown.core.keys.TestCrypto.hex
import xyz.headsdown.core.keys.TestCrypto.toHex

class HeartbeatMessageTest {

    private val programId = ByteArray(32) { (it + 1).toByte() }          // 01..20
    private val rig = ByteArray(32) { (0xA0 + it).toByte() }              // a0..bf

    private fun message(
        round: ULong = 0x0102030405060708uL,
        counter: ULong = 42uL,
        state: RigSignalState = RigSignalState.DOWN,
        shift: ULong = 7uL,
        leaseEnd: ULong = round,
    ) = HeartbeatMessage(programId, rig, round, counter, state, shift, leaseEnd)

    @Test
    fun `wire state bytes are frozen`() {
        assertEquals(
            listOf(0, 1, 2, 3, 4, 5),
            listOf(
                RigSignalState.IDLE, RigSignalState.ARMED, RigSignalState.DOWN,
                RigSignalState.COOLING, RigSignalState.BROKEN, RigSignalState.FROZEN,
            ).map { it.wire },
        )
    }

    @Test
    fun `encodes the exact 101-byte little-endian layout`() {
        val encoded = message(leaseEnd = 0x0102030405060709uL).encode()
        val expected = hex("48447631") +                    // "HDv1"
            programId +
            rig +
            hex("0807060504030201") +                       // ore_round_id LE
            hex("2a00000000000000") +                       // counter = 42
            hex("02") +                                     // state = DOWN
            hex("0700000000000000") +                       // shift_id = 7
            hex("0907060504030201")                         // lease_end LE
        assertEquals(101, HeartbeatMessage.ENCODED_SIZE)
        assertEquals(expected.toHex(), encoded.toHex())
    }

    @Test
    fun `documented offsets match the encoding`() {
        val e = message().encode()
        assertEquals(0x01, e[HeartbeatMessage.OFFSET_PROGRAM_ID].toInt())
        assertEquals(0xA0.toByte(), e[HeartbeatMessage.OFFSET_RIG])
        assertEquals(0x08, e[HeartbeatMessage.OFFSET_ROUND_ID].toInt())
        assertEquals(42, e[HeartbeatMessage.OFFSET_COUNTER].toInt())
        assertEquals(RigSignalState.DOWN.wire, e[HeartbeatMessage.OFFSET_STATE].toInt())
        assertEquals(7, e[HeartbeatMessage.OFFSET_SHIFT_ID].toInt())
        assertEquals(0x08, e[HeartbeatMessage.OFFSET_LEASE_END].toInt())
    }

    @Test
    fun `u64 fields use the full unsigned range`() {
        val max = ULong.MAX_VALUE
        val e = HeartbeatMessage(programId, rig, max, max, RigSignalState.FROZEN, max, max).encode()
        assertEquals("ffffffffffffffff", e.copyOfRange(68, 76).toHex())
        assertEquals("ffffffffffffffff", e.copyOfRange(76, 84).toHex())
        assertEquals(max, HeartbeatMessage.decode(e).counter)
    }

    @Test
    fun `lease covers at most three rounds and never precedes the round`() {
        message(round = 100uL, leaseEnd = 102uL) // 3 rounds: 100, 101, 102
        assertThrows(IllegalArgumentException::class.java) { message(round = 100uL, leaseEnd = 103uL) }
        assertThrows(IllegalArgumentException::class.java) { message(round = 100uL, leaseEnd = 99uL) }
    }

    @Test
    fun `rejects wrong address lengths`() {
        assertThrows(IllegalArgumentException::class.java) {
            HeartbeatMessage(ByteArray(31), rig, 1uL, 1uL, RigSignalState.DOWN, 1uL, 1uL)
        }
        assertThrows(IllegalArgumentException::class.java) {
            HeartbeatMessage(programId, ByteArray(33), 1uL, 1uL, RigSignalState.DOWN, 1uL, 1uL)
        }
    }

    @Test
    fun `decode inverts encode and rejects bad input`() {
        val m = message(counter = 9_000uL, state = RigSignalState.BROKEN, shift = 3uL)
        val decoded = HeartbeatMessage.decode(m.encode())
        assertEquals(m, decoded)
        assertArrayEquals(programId, decoded.programId)
        assertArrayEquals(rig, decoded.rig)

        assertThrows(IllegalArgumentException::class.java) { HeartbeatMessage.decode(m.encode().copyOf(100)) }
        assertThrows(IllegalArgumentException::class.java) {
            HeartbeatMessage.decode(m.encode().also { it[3] = '2'.code.toByte() }) // "HDv2"
        }
        assertThrows(IllegalArgumentException::class.java) {
            HeartbeatMessage.decode(m.encode().also { it[HeartbeatMessage.OFFSET_STATE] = 9 })
        }
    }

    @Test
    fun `inputs are defensively copied`() {
        val pid = programId.copyOf()
        val m = HeartbeatMessage(pid, rig, 1uL, 1uL, RigSignalState.DOWN, 1uL, 1uL)
        val before = m.encode()
        pid.fill(0)
        m.programId.fill(0)
        assertArrayEquals(before, m.encode())
    }
}
