package xyz.headsdown.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.headsdown.BuildConfig
import xyz.headsdown.config.EndpointKind.CRANK
import xyz.headsdown.config.EndpointKind.RPC
import xyz.headsdown.config.EndpointVerdict.Disabled
import xyz.headsdown.config.EndpointVerdict.LoopbackCleartext
import xyz.headsdown.config.EndpointVerdict.Secure

class EndpointPolicyTest {

    private fun release(kind: EndpointKind, url: String) = EndpointPolicy.check(kind, url, allowLoopbackCleartext = false)
    private fun localdev(kind: EndpointKind, url: String) = EndpointPolicy.check(kind, url, allowLoopbackCleartext = true)
    private fun refused(v: EndpointVerdict) = v is EndpointVerdict.Refused

    @Test
    fun `TLS endpoints are allowed in every build`() {
        listOf(false, true).forEach { allow ->
            assertEquals(Secure, EndpointPolicy.check(RPC, "https://api.devnet.solana.com", allow))
            assertEquals(Secure, EndpointPolicy.check(RPC, "https://rpc-proxy.example.org:8443/solana", allow))
            assertEquals(Secure, EndpointPolicy.check(CRANK, "wss://crank-devnet.headsdown.xyz/v1/heartbeats", allow))
        }
    }

    @Test
    fun `release and debug refuse every cleartext endpoint, loopback included`() {
        listOf(
            RPC to "http://127.0.0.1:8899",
            RPC to "http://localhost:8899",
            RPC to "http://api.devnet.solana.com",
            CRANK to "ws://127.0.0.1:8787/ws",
            CRANK to "ws://localhost:8787/ws",
        ).forEach { (kind, url) -> assertTrue("$kind $url", refused(release(kind, url))) }
    }

    @Test
    fun `localdev allows loopback http RPC and ws crank for adb reverse`() {
        assertEquals(LoopbackCleartext, localdev(RPC, "http://127.0.0.1:8899"))
        assertEquals(LoopbackCleartext, localdev(RPC, "http://localhost:8899/"))
        assertEquals(LoopbackCleartext, localdev(RPC, "http://LOCALHOST:8899"))
        assertEquals(LoopbackCleartext, localdev(CRANK, "ws://127.0.0.1:8787/ws"))
        assertEquals(LoopbackCleartext, localdev(CRANK, "ws://localhost:8787/ws"))
    }

    @Test
    fun `localdev still refuses cleartext to anything that is not loopback`() {
        listOf(
            "http://10.0.2.2:8899", // emulator host alias
            "http://192.168.1.20:8899",
            "http://127.0.0.2:8899",
            "http://[::1]:8899",
            "http://127.0.0.1.nip.io:8899",
            "http://localhost.example.com:8899",
            "http://api.devnet.solana.com",
        ).forEach { url -> assertTrue(url, refused(localdev(RPC, url))) }
        assertTrue(refused(localdev(CRANK, "ws://10.0.2.2:8787/ws")))
        assertTrue(refused(localdev(CRANK, "ws://crank.example.org/ws")))
    }

    @Test
    fun `credentials, query strings and fragments are refused everywhere`() {
        listOf(false, true).forEach { allow ->
            listOf(
                "https://mainnet.helius-rpc.com/?api-key=secret",
                "https://user:pass@rpc.example.org",
                "http://127.0.0.1:8899/?api-key=secret",
                // user-info trick: the host is evil.example, not loopback
                "http://127.0.0.1@evil.example:8899",
                "http://evil.example#@127.0.0.1",
            ).forEach { url -> assertTrue("$allow $url", refused(EndpointPolicy.check(RPC, url, allow))) }
        }
    }

    @Test
    fun `schemes must match the endpoint kind`() {
        assertTrue(refused(localdev(RPC, "ws://127.0.0.1:8899")))
        assertTrue(refused(localdev(CRANK, "http://127.0.0.1:8787/ws")))
        assertTrue(refused(localdev(CRANK, "https://crank.example.org/ws")))
        assertTrue(refused(release(RPC, "wss://rpc.example.org")))
        assertTrue(refused(release(RPC, "ftp://rpc.example.org")))
        assertTrue(refused(release(RPC, "not a url")))
        assertTrue(refused(release(RPC, "https:///no-host")))
    }

    @Test
    fun `empty crank is a local-only build, empty RPC is refused`() {
        assertEquals(Disabled, release(CRANK, ""))
        assertEquals(Disabled, localdev(CRANK, ""))
        assertTrue(refused(release(RPC, "")))
    }

    @Test
    fun `require fails fast without echoing the URL`() {
        val error = runCatching { EndpointPolicy.require(RPC, "http://127.0.0.1:8899/?api-key=hunter2", false) }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertFalse(error!!.message!!.contains("hunter2"))
        assertFalse(error.message!!.contains("127.0.0.1"))
        assertEquals(Secure, EndpointPolicy.require(RPC, "https://api.devnet.solana.com", false))
    }

    @Test
    fun `the debug build under test keeps refusing cleartext`() {
        // Unit tests run against the debug variant: it must not be the loopback build.
        assertFalse(BuildConfig.LOOPBACK_CLEARTEXT_ALLOWED)
        assertEquals(Secure, EndpointPolicy.check(RPC, BuildConfig.SOLANA_RPC_URL, BuildConfig.LOOPBACK_CLEARTEXT_ALLOWED))
        val crank = EndpointPolicy.check(CRANK, BuildConfig.CRANK_WS_URL, BuildConfig.LOOPBACK_CLEARTEXT_ALLOWED)
        assertTrue(crank == Secure || crank == Disabled)
        assertTrue(BuildTransports.transports === SecureTransports)
    }
}
