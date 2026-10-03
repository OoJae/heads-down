package xyz.headsdown.core.chain.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.headsdown.core.chain.HeadsDownProgram
import xyz.headsdown.core.chain.Pubkey

/** The two deep links, parsed strictly from untrusted text. */
class HeadsDownLinkTest {

    private val host = Pubkey.fromBase58("7xKXtg2CW87d97TXJSDpbD5jBkheTqA83TZRuJosgAsU")
    private val table = HeadsDownProgram.stackTable(host, 7uL).address
    private val escrow = HeadsDownProgram.giftEscrow(host, 9uL).address

    @Test
    fun `a stack invite and a gift link round-trip`() {
        val stack = HeadsDownLink.Stack(table)
        assertEquals("headsdown://stack/${table.toBase58()}", stack.toString())
        assertEquals(stack, HeadsDownLink.parse(stack.toString()))
        assertEquals(table, HeadsDownLink.parse(stack.toString())!!.address)
        val gift = HeadsDownLink.Gift(escrow)
        assertEquals("headsdown://gift/${escrow.toBase58()}", gift.toString())
        assertEquals(gift, HeadsDownLink.parse(gift.toString()))
        // Every link the app can produce fits the length cap.
        assertTrue(stack.toString().length <= HeadsDownLink.MAX_LENGTH && gift.toString().length <= HeadsDownLink.MAX_LENGTH)
        // An address made of the highest bytes is the longest spelling (44 characters).
        val longest = HeadsDownLink.Stack(Pubkey(ByteArray(32) { 0xFF.toByte() }))
        assertEquals(longest, HeadsDownLink.parse(longest.toString()))
    }

    @Test
    fun `anything that is not exactly a link is refused`() {
        val a = table.toBase58()
        val refused = listOf(
            null,
            "",
            a, // a bare address is not a link
            "headsdown://stack/", // no address
            "headsdown://stack", // no path
            "headsdown://stack/$a/", // trailing slash
            "headsdown://stack/$a/extra",
            "headsdown://stack/$a?amount=5", // a link never carries parameters
            "headsdown://stack/$a#frag",
            "headsdown://stack:80/$a", // a port
            "headsdown://user@stack/$a", // user info
            "headsdown://stack//$a",
            "headsdown:/stack/$a",
            "headsdown:stack/$a",
            "HEADSDOWN://stack/$a", // schemes are matched exactly
            "headsdown://Stack/$a",
            "headsdown://stacks/$a", // an unknown host
            "headsdown://bond/$a",
            "https://stack/$a",
            "https://headsdown.xyz/stack/$a",
            " headsdown://stack/$a", // no trimming of an intent's data
            "headsdown://stack/$a ",
            "headsdown://stack/$a\n",
            "headsdown://stack/%37$a", // no percent-escapes
            "headsdown://stack/${a.dropLast(1)}0", // 0 is not in the base58 alphabet
            "headsdown://stack/${a.dropLast(1)}l", // nor is l
            "headsdown://stack/${a.dropLast(1)}O",
            "headsdown://stack/${a.take(20)}", // too short to be 32 bytes
            "headsdown://stack/${a}1", // decodes to 33 bytes or is not canonical
            "headsdown://stack/11111111111111111111111111111111", // the all-zero address
            "headsdown://stack/" + "z".repeat(44), // 44 characters that decode to more than 32 bytes
            "headsdown://stack/$a" + "x".repeat(200), // far too long
            "javascript:alert(1)",
            "intent://stack/$a#Intent;scheme=headsdown;end",
        )
        for (text in refused) assertNull("should refuse: $text", HeadsDownLink.parse(text))
    }

    @Test
    fun `a pasted address is accepted only as a canonical address, for the host asked`() {
        assertEquals(HeadsDownLink.Stack(table), HeadsDownLink.fromAddress(table.toBase58(), HeadsDownLink.HOST_STACK))
        assertEquals(HeadsDownLink.Gift(escrow), HeadsDownLink.fromAddress("  ${escrow.toBase58()}\n", HeadsDownLink.HOST_GIFT))
        assertNull(HeadsDownLink.fromAddress("not an address", HeadsDownLink.HOST_STACK))
        assertNull(HeadsDownLink.fromAddress(table.toBase58() + "/x", HeadsDownLink.HOST_STACK))
        assertNull(HeadsDownLink.fromAddress(table.toBase58(), "bond"))
        assertNull(HeadsDownLink.fromAddress(null, HeadsDownLink.HOST_STACK))
        // A whole link pasted into the address box is not an address (the screen tries parse() first).
        assertNull(HeadsDownLink.fromAddress(HeadsDownLink.Stack(table).toString(), HeadsDownLink.HOST_STACK))
    }
}
