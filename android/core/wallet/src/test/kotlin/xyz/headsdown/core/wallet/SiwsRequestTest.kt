package xyz.headsdown.core.wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** registrar N7: the payload fields are the registrar's, but only for this app and cluster. */
class SiwsRequestTest {

    private fun build(
        domain: String = "headsdown.xyz",
        uri: String = "https://headsdown.xyz",
        version: String = "1",
        chainIds: List<String> = listOf("solana:devnet"),
        statement: String = "Sign in to Heads Down.",
        nonce: String = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6",
        issuedAt: String = "2026-10-01T12:00:00Z",
        expirationTime: String = "2026-10-01T12:10:00Z",
        buildChainId: String = "solana:devnet",
    ) = SiwsRequest.fromRegistrar("headsdown.xyz", buildChainId, domain, uri, version, chainIds, statement, nonce, issuedAt, expirationTime)

    @Test
    fun `the registrar's fields are copied verbatim with the build's chain id`() {
        val r = build(chainIds = listOf("solana:mainnet", "solana:devnet"))
        assertEquals(
            SiwsRequest(
                domain = "headsdown.xyz",
                uri = "https://headsdown.xyz",
                statement = "Sign in to Heads Down.",
                version = "1",
                chainId = "solana:devnet",
                nonce = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6",
                issuedAt = "2026-10-01T12:00:00Z",
                expirationTime = "2026-10-01T12:10:00Z",
            ),
            r,
        )
        assertEquals("solana:localnet", build(chainIds = listOf("solana:localnet"), buildChainId = "solana:localnet").chainId)
        assertEquals("https://app.headsdown.xyz", build(uri = "https://app.headsdown.xyz").uri)
    }

    @Test
    fun `only the localdev build accepts the devstack registrar's loopback URI`() {
        fun local(uri: String, allow: Boolean) = SiwsRequest.fromRegistrar(
            "localhost", "solana:localnet", "localhost", uri, "1", listOf("solana:localnet"), "Sign in to Heads Down.",
            "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6", "2026-10-01T12:00:00Z", "2026-10-01T12:10:00Z", allowLoopbackUri = allow,
        )
        assertEquals("http://127.0.0.1:8790", local("http://127.0.0.1:8790", allow = true).uri)
        assertEquals("http://localhost:8790", local("http://localhost:8790", allow = true).uri)
        assertThrows(IllegalArgumentException::class.java) { local("http://127.0.0.1:8790", allow = false) }
        assertThrows(IllegalArgumentException::class.java) { local("http://192.168.1.5:8790", allow = true) }
        assertThrows(IllegalArgumentException::class.java) { local("http://127.0.0.1.nip.io:8790", allow = true) }
    }

    @Test
    fun `a registrar answer for another site, cluster or shape is refused`() {
        listOf(
            { build(domain = "evil.example") },
            { build(uri = "https://evil.example") },
            { build(uri = "http://headsdown.xyz") },
            { build(uri = "https://headsdown.xyz.evil.example") },
            { build(uri = "https://headsdown.xyz/?next=evil") },
            { build(uri = "not a uri at all") },
            { build(version = "2") },
            { build(chainIds = listOf("solana:mainnet")) },
            { build(nonce = "short") },
            { build(nonce = "has spaces in it!") },
            { build(statement = "line one\nline two") },
            { build(statement = "x".repeat(201)) },
            { build(issuedAt = "yesterday") },
            { build(expirationTime = "2026-10-01T11:00:00Z") },
        ).forEachIndexed { i, attempt -> assertThrows("case $i", IllegalArgumentException::class.java) { attempt() } }
    }
}
