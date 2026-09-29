package xyz.headsdown.core.chain

import com.solana.publickey.ProgramDerivedAddress
import com.solana.publickey.SolanaPublicKey
import kotlinx.coroutines.test.runTest
import org.bouncycastle.math.ec.rfc8032.Ed25519
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.util.Random

class PdaTest {

    private val authority = Pubkey.fromBase58("7xKXtg2CW87d97TXJSDpbD5jBkheTqA83TZRuJosgAsU")

    /**
     * Every expectation below was produced independently with the Agave CLI 4.1.2:
     * `solana find-program-derived-address <program> string:<seed> [pubkey:<k>] [u64le:<n>] --output json`.
     */
    @Test
    fun `heads_down PDAs match the Agave CLI`() {
        assertEquals(ProgramAddress(Pubkey.fromBase58("inzDn4ogmXbx9YDAKDHkfwJHy1jhsaWxGQvricDAEmW"), 253), HeadsDownProgram.config)
        assertEquals(ProgramAddress(Pubkey.fromBase58("By3vJvQUsCLexnv7VqHuEhtZZCmpmjZjfhxvqCnWPkge"), 249), HeadsDownProgram.executor)
        val rig = HeadsDownProgram.rig(authority)
        assertEquals(ProgramAddress(Pubkey.fromBase58("2gQpad6BYLts9d8qHbRRoeYRJnBLUftiFmMM38cW4zco"), 254), rig)
        assertEquals(
            ProgramAddress(Pubkey.fromBase58("tL14hr8oScrfitG2iksFmE8mgkFcEyzR2LterWHQ7rF"), 254),
            HeadsDownProgram.shiftLog(rig.address, 7uL),
        )
        assertEquals(
            Pubkey.fromBase58("FkvYJJx5Yk8bFLYauyex51fX614dBt3KAgutSHnJBiSh"),
            HeadsDownProgram.seekerSeat(authority).address,
        )
    }

    @Test
    fun `ORE PDAs match the pinned addresses and the spike fixture`() {
        // Board / Config / Treasury are ORE's own compile-time constants (consts.rs:110-116).
        assertEquals(Ore.BOARD, Pda.find(listOf("board".toByteArray()), Ore.PROGRAM_ID).address)
        assertEquals(Ore.CONFIG, Pda.find(listOf("config".toByteArray()), Ore.PROGRAM_ID).address)
        assertEquals(Ore.TREASURY, Pda.find(listOf("treasury".toByteArray()), Ore.PROGRAM_ID).address)
        // spikes/ore-executor/fixtures: round_id.txt = 422593, round_address.txt below.
        assertEquals(
            ProgramAddress(Pubkey.fromBase58("9FKLzyDXNVaD79hZDpEsZw59GyH8K8cuWc5pFrSfzfz2"), 255),
            Ore.round(422_593uL),
        )
        assertEquals(ProgramAddress(Pubkey.fromBase58("B5BGeWCGiYUuNq6Dm5qc5V8rMCRmVxWQWWXTadNGdak9"), 254), Ore.automation(authority))
        assertEquals(ProgramAddress(Pubkey.fromBase58("BoKSEj39JhfQZma5UFrexuFHRCoyrmnVH3ZurpvCjJFG"), 255), Ore.miner(authority))
    }

    @Test
    fun `live mainnet miner re-derives from its authority`() {
        // fixtures/ore_miner.json: a Miner read from mainnet on 2026-09-29 and its authority.
        assertEquals(
            Pubkey.fromBase58("4gWunDA4TQ9noMeGLR9ovNiWySFJbU8tSmECvLMp4EXz"),
            Ore.miner(Pubkey.fromBase58("2Yf3PNJXbdELHD1jPWSaXTWMYQLuH3jYzsAq4L5VWn3X")).address,
        )
        assertEquals(
            Pubkey.fromBase58("2fB1ocfUAstKvnXeUGQnzH4WK85oLc2VWC1Cv6DDTpuv"),
            Ore.automation(Pubkey.fromBase58("2Yf3PNJXbdELHD1jPWSaXTWMYQLuH3jYzsAq4L5VWn3X")).address,
        )
    }

    @Test
    fun `find returns the first off-curve bump and create agrees`() {
        // The executor's canonical bump is 249: bumps 255..250 all hash onto the curve.
        val seeds = listOf("executor".toByteArray())
        for (bump in 255 downTo 250) {
            assertNull("bump $bump is on-curve", Pda.createProgramAddress(seeds + byteArrayOf(bump.toByte()), HeadsDownProgram.ID))
        }
        assertEquals(
            HeadsDownProgram.executor.address,
            Pda.createProgramAddress(seeds + byteArrayOf(249.toByte()), HeadsDownProgram.ID),
        )
    }

    @Test
    fun `matches web3-solana PDA derivation on random seeds`() = runTest {
        val random = Random(1)
        repeat(200) {
            val program = Pubkey(ByteArray(32).also(random::nextBytes))
            val seeds = List(random.nextInt(4)) { ByteArray(random.nextInt(33)).also(random::nextBytes) }
            val ours = Pda.find(seeds, program)
            val theirs = ProgramDerivedAddress.find(seeds, SolanaPublicKey(program.bytes)).getOrThrow()
            assertTrue(ours.address.contentEquals(theirs.bytes))
            assertEquals(ours.bump, theirs.nonce.toInt())
        }
    }

    @Test
    fun `on-curve test agrees with BouncyCastle RFC 8032 decoding on random points`() {
        val random = Random(2)
        var on = 0
        repeat(5_000) {
            val bytes = ByteArray(32).also(random::nextBytes)
            val bc = Ed25519.validatePublicKeyPartial(bytes, 0)
            assertEquals(bc, Pda.isOnCurve(bytes))
            if (bc) on++
        }
        // About half of all y values have a square root x.
        assertTrue("saw $on on-curve points", on in 2_000..3_000)
        // Real ed25519 public keys are on the curve; every PDA is off it.
        assertTrue(Pda.isOnCurve(authority.bytes))
        assertFalse(Pda.isOnCurve(HeadsDownProgram.executor.address.bytes))
    }

    @Test
    fun `non-canonical encodings follow curve25519-dalek, not RFC 8032`() {
        // y = p (little-endian) is a non-canonical encoding of y = 0; dalek reduces it mod p
        // and accepts it (x^2 = -1 has a root), so an address with these bytes is not a PDA.
        val p = BigInteger.TWO.pow(255).subtract(BigInteger.valueOf(19))
        val pLe = p.toByteArray().reversedArray().copyOf(32)
        assertTrue(Pda.isOnCurve(pLe))
        assertFalse("RFC 8032 rejects non-canonical y", Ed25519.validatePublicKeyPartial(pLe, 0))
        // The sign bit is ignored: y = 1 with x = 0 and the sign bit set is still "on curve".
        val one = ByteArray(32).also { it[0] = 1; it[31] = 0x80.toByte() }
        assertTrue(Pda.isOnCurve(one))
    }

    @Test
    fun `seed limits match solana-program`() {
        val program = HeadsDownProgram.ID
        assertThrows(IllegalArgumentException::class.java) { Pda.find(listOf(ByteArray(33)), program) }
        assertThrows(IllegalArgumentException::class.java) { Pda.find(List(16) { ByteArray(1) }, program) }
        assertNotNull(Pda.find(List(15) { ByteArray(32) }, program))
        assertThrows(IllegalArgumentException::class.java) { Pda.createProgramAddress(List(17) { ByteArray(0) }, program) }
    }

    @Test
    fun `u64 seeds are little-endian`() {
        assertEquals("c172060000000000", 422_593uL.leBytes().joinToString("") { "%02x".format(it) })
        assertEquals("ffffffffffffffff", ULong.MAX_VALUE.leBytes().joinToString("") { "%02x".format(it) })
    }

    @Test
    fun `pubkey parsing is strict`() {
        assertThrows(IllegalArgumentException::class.java) { Pubkey.fromBase58("0OIl") }
        assertThrows(IllegalArgumentException::class.java) { Pubkey.fromBase58("abc") }
        assertThrows(IllegalArgumentException::class.java) { Pubkey(ByteArray(31)) }
        assertEquals(Pubkey.DEFAULT, WellKnown.SYSTEM_PROGRAM)
        assertTrue(Pubkey.DEFAULT < authority)
    }
}
