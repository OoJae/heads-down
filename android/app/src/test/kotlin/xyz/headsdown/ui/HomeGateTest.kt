package xyz.headsdown.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import xyz.headsdown.core.chain.Ore
import xyz.headsdown.core.chain.Pubkey
import xyz.headsdown.core.chain.accounts.OreAccounts
import xyz.headsdown.core.chain.rpc.AccountInfo
import xyz.headsdown.rig.ClockInPolicy
import java.io.IOException
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The home screen's read of ORE's Board and Treasury: one call, through the checked decoders, and
 * no line at all for anything it cannot stand behind.
 */
class HomeGateTest {

    private val policy = ClockInPolicy()

    /** The program's live vector: ema 903,580,117 and a 360.8 ORE pot give 629,818,854 lamports per ORE. */
    private val ema = 903_580_117L
    private val pot = 36_080_000_000_000L

    private fun steel(size: Int, discriminator: Int, at: Int, value: Long, owner: Pubkey = Ore.PROGRAM_ID): AccountInfo {
        val data = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        data.put(0, discriminator.toByte())
        data.putLong(at, value)
        return AccountInfo(1uL, owner, data.array(), false)
    }

    private fun board(owner: Pubkey = Ore.PROGRAM_ID) = steel(OreAccounts.BOARD_SIZE, OreAccounts.BOARD_DISC, 32, ema, owner)
    private fun treasury() = steel(OreAccounts.TREASURY_SIZE, OreAccounts.TREASURY_DISC, 8, pot)

    private fun line(read: suspend (List<Pubkey>) -> List<AccountInfo?>): String? = runBlocking { gateLine(policy, read) }

    @Test
    fun `one read of the Board and the Treasury gives the line`() {
        val asked = mutableListOf<List<Pubkey>>()
        val line = line { addresses ->
            asked += addresses
            listOf(board(), treasury())
        }
        assertEquals(listOf(listOf(Ore.BOARD, Ore.TREASURY)), asked)
        assertEquals(HomeCopy.gate(BigInteger.valueOf(629_818_854), policy), line)
        assertEquals("ORE's cost figure is 0.62 SOL per ORE. The ceiling fixed in this build is 0.53. At that figure the rig would wait.", line)
    }

    @Test
    fun `anything it cannot stand behind is no line`() {
        // No ORE on this cluster, or half of it.
        assertNull(line { listOf(null, null) })
        assertNull(line { listOf(board(), null) })
        assertNull(line { listOf(null, treasury()) })
        // An answer of the wrong length, the accounts swapped, a Board another program owns, a Board of another size.
        assertNull(line { listOf(board()) })
        assertNull(line { emptyList() })
        assertNull(line { listOf(treasury(), board()) })
        assertNull(line { listOf(board(owner = Ore.TREASURY), treasury()) })
        assertNull(line { listOf(steel(OreAccounts.BOARD_SIZE + 8, OreAccounts.BOARD_DISC, 32, ema), treasury()) })
        // An RPC that does not answer.
        assertNull(line { throw IOException("timeout") })
        assertNull(line { throw IllegalStateException("not json") })
    }

    @Test
    fun `a cancelled read stays cancelled`() {
        assertThrows(CancellationException::class.java) { line { throw CancellationException("left the screen") } }
    }
}
