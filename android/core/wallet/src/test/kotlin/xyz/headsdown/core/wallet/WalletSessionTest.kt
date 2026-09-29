package xyz.headsdown.core.wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WalletSessionTest {

    @Test
    fun `capabilities parse MWA version lists`() {
        // MWA sends "legacy" as a String and versions as JSON numbers (Integer in Java).
        val both = WalletCapabilities.fromMwa(arrayOf<Any>("legacy", 0), maxTransactionsPerRequest = 10)
        assertTrue(both.supportsLegacy)
        assertTrue(both.supportsV0)
        assertEquals(10, both.maxTransactionsPerRequest)

        val v0Only = WalletCapabilities.fromMwa(arrayOf(0L), 0)
        assertFalse(v0Only.supportsLegacy)
        assertTrue(v0Only.supportsV0)

        val legacyOnly = WalletCapabilities.fromMwa(arrayOf("legacy"), 0)
        assertTrue(legacyOnly.supportsLegacy)
        assertFalse(legacyOnly.supportsV0)
    }

    @Test
    fun `unknown or empty version lists fall back to legacy`() {
        assertEquals(WalletCapabilities.LEGACY_ONLY, WalletCapabilities.fromMwa(emptyArray(), 0))
        val odd = WalletCapabilities.fromMwa(arrayOf<Any?>(null, 1, "v2", 2.5), -3)
        assertTrue(odd.supportsLegacy)
        assertFalse(odd.supportsV0)
        assertEquals("a negative limit is treated as unstated", 0, odd.maxTransactionsPerRequest)
    }

    @Test
    fun `only fixed MWA failure strings pass through`() {
        assertEquals("User did not authorize signing", WalletFailures.sanitize("User did not authorize signing"))
        assertEquals("Auth token invalid", WalletFailures.sanitize("Auth token invalid"))
        // MWA builds a Failure from RuntimeException.message: anything could be in there.
        val leaky = "java.lang.IllegalStateException: token=eyJhbGciOi... payload=AQAB..."
        assertEquals(WalletFailures.GENERIC, WalletFailures.sanitize(leaky))
        assertEquals(WalletFailures.GENERIC, WalletFailures.sanitize(null))
        assertEquals(WalletFailures.GENERIC, WalletFailures.sanitize(""))
    }

    @Test
    fun `prepared transactions are copied and validated`() {
        val tx = byteArrayOf(1, 2, 3)
        val prepared = PreparedTransactions(listOf(tx), lastValidBlockHeight = 100)
        tx[0] = 9
        assertEquals(1, prepared.transactions.single()[0].toInt())
        assertThrows(IllegalArgumentException::class.java) { PreparedTransactions(emptyList(), 100) }
        assertThrows(IllegalArgumentException::class.java) { PreparedTransactions(listOf(tx), 0) }
    }
}
