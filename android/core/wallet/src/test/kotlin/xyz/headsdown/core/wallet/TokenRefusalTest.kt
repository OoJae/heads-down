package xyz.headsdown.core.wallet

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A wallet that refuses the token it handed out (Jupiter Mobile, a day later, on mainnet, on
 * 11 October 2026): the app showed "failed establishing local association with wallet" at every
 * clock-in, because the library reports the refusal with that message and the token was kept.
 */
class TokenRefusalTest {
    private val refusal = "Failed establishing local association with wallet"

    @Test
    fun `a refused token is dropped and the wallet is asked once more`() {
        assertTrue(TokenRefusal.askAgain(presented = true, entered = false, message = refusal))
        assertTrue(TokenRefusal.askAgain(presented = true, entered = false, message = "Auth token invalid"))
        assertTrue(TokenRefusal.askAgain(presented = true, entered = false, message = "Remote exception"))
    }

    @Test
    fun `nothing is asked twice once the session is past the authorization`() {
        // From there on the wallet may have signed, or sent: a second call could sign twice.
        assertFalse(TokenRefusal.askAgain(presented = true, entered = true, message = refusal))
        assertFalse(TokenRefusal.askAgain(presented = true, entered = true, message = "Remote exception"))
    }

    @Test
    fun `without a token there is nothing to drop`() {
        assertFalse(TokenRefusal.askAgain(presented = false, entered = false, message = refusal))
    }

    @Test
    fun `a wallet the user closed, or that never came up, is not opened a second time`() {
        for (message in listOf(
            "Request was interrupted",
            "Request was cancelled",
            "Local association was cancelled before connected",
            "Timed out waiting for local association to be ready",
            "Timed out waiting to send association intent",
            "User did not authorize signing",
        )) {
            assertFalse(message, TokenRefusal.askAgain(presented = true, entered = false, message = message))
        }
    }
}
