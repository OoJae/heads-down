package xyz.headsdown.surface.tile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TrampolineCopyTest {
    @Test
    fun `a phone with no wallet is told of the one wallet this build has run with, and of no other`() {
        assertEquals(
            "Install a Solana wallet to clock in. This build has been run with Jupiter Mobile.",
            TrampolineCopy.NO_WALLET,
        )
        // None of these has signed for this app: the toast must not suggest one would work.
        for (untried in listOf("Solflare", "Phantom", "Seed Vault")) {
            assertFalse(untried, untried in TrampolineCopy.NO_WALLET)
        }
    }
}
