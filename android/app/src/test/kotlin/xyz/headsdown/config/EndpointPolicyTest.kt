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
            assertEquals(Secure, EndpointPolicy.check(CRANK, "wss://crank-devnet.headsdown.example/v1/heartbeats", allow))
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
        // No registrar: guest rigs. No indexer: no morning haul.
        assertEquals(Disabled, release(EndpointKind.REGISTRAR, ""))
        assertEquals(Disabled, release(EndpointKind.INDEXER, ""))
    }

    @Test
    fun `registrar and indexer follow the same rules as RPC`() {
        for (kind in listOf(EndpointKind.REGISTRAR, EndpointKind.INDEXER)) {
            assertEquals(Secure, release(kind, "https://registrar-devnet.headsdown.example"))
            assertEquals(Secure, release(kind, "https://indexer.example.org/api"))
            assertTrue(refused(release(kind, "http://127.0.0.1:8790")))
            assertEquals(LoopbackCleartext, localdev(kind, "http://127.0.0.1:8790"))
            assertEquals(LoopbackCleartext, localdev(kind, "http://localhost:8788"))
            assertTrue(refused(localdev(kind, "http://10.0.2.2:8788")))
            assertTrue(refused(release(kind, "https://indexer.example.org/?key=secret")))
            assertTrue(refused(release(kind, "wss://indexer.example.org")))
        }
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
        for ((kind, url) in listOf(EndpointKind.REGISTRAR to BuildConfig.REGISTRAR_URL, EndpointKind.INDEXER to BuildConfig.INDEXER_URL)) {
            val v = EndpointPolicy.check(kind, url, BuildConfig.LOOPBACK_CLEARTEXT_ALLOWED)
            assertTrue("$kind", v == Secure || v == Disabled)
        }
        // The crank intake path is contract A's /ws.
        assertTrue(BuildConfig.CRANK_WS_URL.isEmpty() || BuildConfig.CRANK_WS_URL.endsWith("/ws"))
        assertFalse(BuildConfig.SUBMIT_THROUGH_APP_RPC)
        // The app signs in to the host of its own identity site (build configuration, a site the
        // team controls); only localdev uses the devstack's "localhost". No service URL has a
        // default host that somebody else could register.
        assertEquals(java.net.URI(BuildConfig.IDENTITY_URI).host, BuildConfig.SIWS_DOMAIN)
        assertEquals("https", java.net.URI(BuildConfig.IDENTITY_URI).scheme)
        // Wallets resolve the icon against the identity. A site below the host root ends in "/",
        // so a wallet that appends the path and one that follows the URL standard reach the same file.
        val identity = java.net.URI(BuildConfig.IDENTITY_URI)
        if (!identity.rawPath.isNullOrEmpty()) {
            assertTrue(BuildConfig.IDENTITY_URI, identity.rawPath.endsWith("/"))
            assertEquals(
                BuildConfig.IDENTITY_URI + xyz.headsdown.core.wallet.HeadsDownIdentity.ICON,
                identity.resolve(xyz.headsdown.core.wallet.HeadsDownIdentity.ICON).toString(),
            )
        }
        for (url in listOf(BuildConfig.IDENTITY_URI, BuildConfig.CRANK_WS_URL, BuildConfig.REGISTRAR_URL, BuildConfig.INDEXER_URL)) {
            assertFalse(url, url.contains("headsdown.xyz"))
        }
        assertTrue(BuildTransports.transports === SecureTransports)
    }
}
