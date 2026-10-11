package xyz.headsdown.clockout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import xyz.headsdown.config.ExplorerLinks

/** The link the two sheets show under a confirmed result: where it goes, and when there is none. */
class ExplorerLinksTest {

    /** 64 bytes in base58, as a wallet returns a signature. */
    private val signature = "5VERv8NMvzbJMEkV8xnrLkEaWRtSz9CosKDYjCJjBRnbJLgp8uirBgmQpjKhoR4tjF3ZpRzrFmBV6UjKdiSZkQUW"

    @Test
    fun `mainnet goes to Solscan and devnet to Solana's explorer with its cluster named`() {
        assertEquals("https://solscan.io/tx/$signature", ExplorerLinks.tx(signature, "solana:mainnet"))
        assertEquals("https://explorer.solana.com/tx/$signature?cluster=devnet", ExplorerLinks.tx(signature, "solana:devnet"))
        // The constants are what the build writes into BuildConfig.SOLANA_CHAIN.
        assertEquals("solana:mainnet", ExplorerLinks.MAINNET)
        assertEquals("solana:devnet", ExplorerLinks.DEVNET)
    }

    @Test
    fun `a local validator, or a cluster nobody named, has no public page`() {
        assertNull(ExplorerLinks.tx(signature, "solana:localnet"))
        assertNull(ExplorerLinks.tx(signature, "solana:testnet"))
        assertNull(ExplorerLinks.tx(signature, ""))
        assertNull(ExplorerLinks.tx(signature, "mainnet"))
    }

    @Test
    fun `nothing that is not a signature is ever put in an address`() {
        assertNull(ExplorerLinks.tx(null, "solana:mainnet"))
        assertNull(ExplorerLinks.tx("", "solana:mainnet"))
        assertNull(ExplorerLinks.tx("sig", "solana:mainnet"))
        // A wallet app chooses this string: a path, a query or a host in it must not reach a browser.
        for (hostile in listOf(
            "../../account/11111111111111111111111111111111111111111111111111111111111111",
            "$signature?cluster=devnet",
            "$signature/../x",
            "$signature#frag",
            " $signature",
            "$signature\n",
            "https://example.org/${"1".repeat(64)}",
            // 0, O, I and l are not in the base58 alphabet.
            "0".repeat(88),
            "O".repeat(88),
            "l".repeat(64),
            // Too short and too long for 64 bytes.
            "1".repeat(63),
            "1".repeat(89),
        )) {
            assertNull(hostile, ExplorerLinks.tx(hostile, "solana:mainnet"))
            assertNull(hostile, ExplorerLinks.tx(hostile, "solana:devnet"))
        }
        // The check is of the shape only (it decodes nothing): 64 to 88 characters of the alphabet.
        assertEquals("https://solscan.io/tx/${"1".repeat(64)}", ExplorerLinks.tx("1".repeat(64), "solana:mainnet"))
        assertEquals("https://solscan.io/tx/${"z".repeat(88)}", ExplorerLinks.tx("z".repeat(88), "solana:mainnet"))
    }

    @Test
    fun `the address is only ever one of two hosts, over TLS, ending in the signature`() {
        for (cluster in listOf("solana:mainnet", "solana:devnet")) {
            val uri = java.net.URI(ExplorerLinks.tx(signature, cluster)!!)
            assertEquals("https", uri.scheme)
            assertEquals(if (cluster == "solana:mainnet") "solscan.io" else "explorer.solana.com", uri.host)
            assertEquals("/tx/$signature", uri.path)
            assertNull(uri.userInfo)
            assertNull(uri.fragment)
        }
    }
}
