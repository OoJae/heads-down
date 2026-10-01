package xyz.headsdown.rig

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.headsdown.core.chain.HeadsDownProgram
import xyz.headsdown.core.chain.Pubkey
import xyz.headsdown.core.chain.registrar.AttestationOutcome
import xyz.headsdown.core.chain.registrar.AttestationRun
import xyz.headsdown.core.chain.registrar.ChallengedKey
import xyz.headsdown.core.chain.registrar.HdRegPreimage
import xyz.headsdown.core.chain.registrar.RegistrarVoucher
import xyz.headsdown.core.chain.registrar.RigAttestor
import xyz.headsdown.core.keys.KeySecurityLevel

/** Rig key creation with and without the registrar (fake attestor, fake Keystore). */
class RigOnboardingTest {

    private val wallet = Pubkey(ByteArray(32) { 3 })
    private val p256 = byteArrayOf(0x02) + ByteArray(32) { 0x11 }

    private class FakeKeys : RigKeys {
        val calls = mutableListOf<String>()
        override fun create(): RigKeyStatus {
            calls += "create"
            return RigKeyStatus.Ready(KeySecurityLevel.TRUSTED_ENVIRONMENT, "local", 0)
        }

        override fun generateChallenged(challenge: ByteArray): ChallengedKey {
            calls += "challenged:${challenge.size}"
            return ChallengedKey(byteArrayOf(0x02) + ByteArray(32) { 0x11 }, listOf(byteArrayOf(1)))
        }

        override fun status(voucherLevel: Int?): RigKeyStatus {
            calls += "status:$voucherLevel"
            return RigKeyStatus.Ready(KeySecurityLevel.TRUSTED_ENVIRONMENT, "challenged", 3, voucherLevel)
        }
    }

    private class FakeVouchers : VoucherSink {
        val saved = mutableListOf<RegistrarVoucher>()
        var cleared = 0
        override fun save(voucher: RegistrarVoucher) { saved += voucher }
        override fun clear() { cleared++ }
    }

    /** A structurally valid level-2 voucher (the precompile checks the signature on-chain). */
    private fun voucher(): RegistrarVoucher {
        val message = HdRegPreimage.build(HeadsDownProgram.ID, wallet, p256, 2, 500_000_000uL)
        val data = byteArrayOf(0x01, 0x00, 0x30, 0x00, -1, -1, 0x10, 0x00, -1, -1, 0x70, 0x00, 0x6F, 0x00, -1, -1) +
            ByteArray(32) { 9 } + ByteArray(64) + message
        return RegistrarVoucher.verify(data, wallet, p256, 2, 500_000_000uL)
    }

    private val keys = FakeKeys()
    private val vouchers = FakeVouchers()

    private fun onboarding(attestor: RigAttestor?) = RigOnboarding(attestor, keys, vouchers, keystore = Dispatchers.Unconfined)

    /** An attestor that runs the key generation like the real flow, then ends with [outcome]. */
    private fun attestor(outcome: AttestationOutcome, generate: Boolean, voucher: RegistrarVoucher? = null) = RigAttestor { _, generateKey ->
        val key = if (generate) generateKey(ByteArray(32) { 7 }) else null
        AttestationRun(outcome, wallet, key, voucher, if (outcome == AttestationOutcome.ATTESTED) null else "code")
    }

    @Test
    fun `an attested key keeps its voucher for clock-in`() = runTest {
        val v = voucher()
        val setup = onboarding(attestor(AttestationOutcome.ATTESTED, generate = true, voucher = v)).createKey { null }
        assertEquals(AttestationOutcome.ATTESTED, setup.attestation)
        assertEquals(listOf("challenged:32", "status:2"), keys.calls)
        assertSame(v, vouchers.saved.single())
        assertEquals(2, (setup.status as RigKeyStatus.Ready).voucherLevel)
        assertEquals("an old voucher is dropped before the new key exists", 1, vouchers.cleared)
    }

    @Test
    fun `level 0 or a refused chain keeps the challenged key as a guest`() = runTest {
        for (outcome in listOf(AttestationOutcome.LEVEL_ZERO, AttestationOutcome.REJECTED)) {
            keys.calls.clear()
            val setup = onboarding(attestor(outcome, generate = true)).createKey { null }
            assertEquals(outcome, setup.attestation)
            assertEquals(listOf("challenged:32", "status:null"), keys.calls)
            assertNull((setup.status as RigKeyStatus.Ready).voucherLevel)
        }
        assertTrue(vouchers.saved.isEmpty())
    }

    @Test
    fun `no registrar, an outage or a declined sign-in still makes a guest key`() = runTest {
        assertEquals(AttestationOutcome.REGISTRAR_UNAVAILABLE, onboarding(null).createKey { null }.attestation)
        assertEquals(listOf("create"), keys.calls)
        for (outcome in listOf(AttestationOutcome.REGISTRAR_UNAVAILABLE, AttestationOutcome.SIGN_IN_DECLINED)) {
            keys.calls.clear()
            val setup = onboarding(attestor(outcome, generate = false)).createKey { null }
            assertEquals(outcome, setup.attestation)
            assertEquals(listOf("create"), keys.calls)
            assertTrue(setup.status is RigKeyStatus.Ready)
        }
        assertTrue(vouchers.saved.isEmpty())
    }

    @Test
    fun `a Keystore failure on the challenged key falls back to a guest key`() = runTest {
        val failing = RigAttestor { _, _ -> throw IllegalStateException("StrongBox busy") }
        val setup = onboarding(failing).createKey { null }
        assertEquals(AttestationOutcome.REJECTED, setup.attestation)
        assertEquals(listOf("create"), keys.calls)
    }
}
