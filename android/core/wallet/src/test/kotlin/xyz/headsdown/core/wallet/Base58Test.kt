package xyz.headsdown.core.wallet

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Random

class Base58Test {

    @Test
    fun `known vectors`() {
        assertEquals("", Base58.encode(ByteArray(0)))
        assertEquals("JxF12TrwUP45BMd", Base58.encode("Hello World".toByteArray()))
        assertEquals("112", Base58.encode(byteArrayOf(0, 0, 1)))
        // The System Program id is 32 zero bytes.
        assertEquals("11111111111111111111111111111111", Base58.encode(ByteArray(32)))
    }

    @Test
    fun `decodes Solana program ids to 32 bytes`() {
        val ore = Base58.decode("oreV3EG1i9BEgiAJ8b177Z2S2rMarzak4NMv1kULvWv")
        assertEquals(32, ore.size)
        assertEquals("oreV3EG1i9BEgiAJ8b177Z2S2rMarzak4NMv1kULvWv", Base58.encode(ore))
        assertArrayEquals(ByteArray(32), Base58.decode("11111111111111111111111111111111"))
    }

    @Test
    fun `round-trips random byte strings including leading zeros`() {
        val random = Random(7)
        repeat(200) {
            val bytes = ByteArray(random.nextInt(70)).also(random::nextBytes)
            if (bytes.isNotEmpty() && random.nextBoolean()) bytes[0] = 0
            assertArrayEquals(bytes, Base58.decode(Base58.encode(bytes)))
        }
    }

    @Test
    fun `rejects characters outside the alphabet`() {
        for (bad in listOf("0", "O", "I", "l", "abc+", "é")) {
            assertThrows(bad, IllegalArgumentException::class.java) { Base58.decode(bad) }
        }
    }
}
