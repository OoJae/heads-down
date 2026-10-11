package xyz.headsdown.core.wallet

import com.solana.mobilewalletadapter.clientlib.protocol.JsonRpc20Client
import com.solana.mobilewalletadapter.common.ProtocolContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ExecutionException

/**
 * A wallet that refuses the token it handed out (Jupiter Mobile, a day later, on mainnet, on
 * 11 October 2026): the app showed "failed establishing local association with wallet" at every
 * clock-in, because the library reports every wallet-side error with that message and the token
 * was kept.
 */
class TokenRefusalTest {
    private fun wrapped(code: Int): Throwable =
        ExecutionException(JsonRpc20Client.JsonRpc20RemoteException(code, "said by the wallet", null))

    @Test
    fun `the wallet's own answer is read from under the library's one message`() {
        assertEquals(ProtocolContract.ERROR_AUTHORIZATION_FAILED, WalletAnswer.code(wrapped(ProtocolContract.ERROR_AUTHORIZATION_FAILED)))
        assertEquals(ProtocolContract.ERROR_NOT_SIGNED, WalletAnswer.code(RuntimeException(wrapped(ProtocolContract.ERROR_NOT_SIGNED))))
        // A wallet that never came up answered nothing.
        assertNull(WalletAnswer.code(ExecutionException(java.net.ConnectException("refused"))))
        assertNull(WalletAnswer.code(null))
    }

    @Test
    fun `each answer gets the word the rest of the app knows`() {
        val association = "Failed establishing local association with wallet"
        assertEquals("Auth token invalid", WalletAnswer.message(ProtocolContract.ERROR_AUTHORIZATION_FAILED, association))
        assertEquals("User did not authorize signing", WalletAnswer.message(ProtocolContract.ERROR_NOT_SIGNED, association))
        assertEquals("Too many payloads to sign", WalletAnswer.message(ProtocolContract.ERROR_TOO_MANY_PAYLOADS, association))
        assertEquals("Remote exception", WalletAnswer.message(-32601, association))
        assertEquals(association, WalletAnswer.message(null, association))
        for (said in listOf("Auth token invalid", "User did not authorize signing", "Too many payloads to sign", "Remote exception")) {
            assertEquals("the toast shows it as it is", said, WalletFailures.sanitize(said))
        }
        assertTrue(WalletFailures.NOTHING_SIGNED.contains(WalletAnswer.message(ProtocolContract.ERROR_NOT_SIGNED, association)))
    }

    @Test
    fun `a refused token is dropped and the wallet is asked once more`() {
        assertTrue(WalletAnswer.askAgain(presented = true, entered = false, code = ProtocolContract.ERROR_AUTHORIZATION_FAILED))
        // Whatever code a wallet chooses for it: before the session is entered nothing was signed.
        assertTrue(WalletAnswer.askAgain(presented = true, entered = false, code = -32602))
    }

    @Test
    fun `nothing is asked twice once the session is past the authorization`() {
        // From there on the wallet may have signed, or sent: a second call could sign twice.
        assertFalse(WalletAnswer.askAgain(presented = true, entered = true, code = ProtocolContract.ERROR_NOT_SIGNED))
        assertFalse(WalletAnswer.askAgain(presented = true, entered = true, code = ProtocolContract.ERROR_AUTHORIZATION_FAILED))
    }

    @Test
    fun `without a token, or without an answer, the wallet is not opened a second time`() {
        assertFalse(WalletAnswer.askAgain(presented = false, entered = false, code = ProtocolContract.ERROR_AUTHORIZATION_FAILED))
        // The user closed the wallet, or it never came up: no answer, no second try.
        assertFalse(WalletAnswer.askAgain(presented = true, entered = false, code = null))
    }
}
