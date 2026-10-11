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
    fun `a refused token is told apart from every other failure`() {
        assertTrue(WalletAnswer.refusedToken(presented = true, entered = false, code = ProtocolContract.ERROR_AUTHORIZATION_FAILED))
        // Whatever code a wallet chooses for it: before the session is entered, the token is all it can be about.
        assertTrue(WalletAnswer.refusedToken(presented = true, entered = false, code = -32602))
        // Past the authorization an error is about what was to be signed: the token is kept.
        assertFalse(WalletAnswer.refusedToken(presented = true, entered = true, code = ProtocolContract.ERROR_NOT_SIGNED))
        assertFalse(WalletAnswer.refusedToken(presented = true, entered = true, code = ProtocolContract.ERROR_AUTHORIZATION_FAILED))
        // No token was presented, or the wallet never answered (closed by the user, never came up).
        assertFalse(WalletAnswer.refusedToken(presented = false, entered = false, code = ProtocolContract.ERROR_AUTHORIZATION_FAILED))
        assertFalse(WalletAnswer.refusedToken(presented = true, entered = false, code = null))
    }

    @Test
    fun `the sentence that asks for one more tap is shown whole`() {
        assertEquals(WalletFailures.RECONNECT, WalletFailures.sanitize(WalletFailures.RECONNECT))
        assertFalse(WalletFailures.RECONNECT in WalletFailures.NOTHING_SIGNED)
    }
}
