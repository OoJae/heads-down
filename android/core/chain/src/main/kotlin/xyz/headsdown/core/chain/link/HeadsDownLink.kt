package xyz.headsdown.core.chain.link

import xyz.headsdown.core.chain.Pubkey
import xyz.headsdown.core.wallet.Base58

/**
 * The app's two deep links: an invite to a Stack table and a gift to claim.
 *
 * ```
 * headsdown://stack/<table address, base58>
 * headsdown://gift/<escrow address, base58>
 * ```
 *
 * A link is untrusted text (a QR code, a message, another app's intent). It is parsed from the
 * raw string, never through a URI library's lenient rules, and is accepted only when it is
 * **exactly** one of the two shapes above: lower-case scheme and host, one path segment, an
 * address that is canonical base58 for 32 bytes, and nothing else (no port, user info, query,
 * fragment, trailing slash, whitespace or percent-escapes).
 *
 * A link carries an address and nothing else, so it can only choose **which** on-chain account a
 * review screen reads. It never carries an amount, a recipient or an instruction, and opening one
 * never signs anything (THREAT_MODEL §8).
 */
sealed interface HeadsDownLink {
    val address: Pubkey

    /** An invite to the Stack table at [table]. */
    data class Stack(val table: Pubkey) : HeadsDownLink {
        override val address: Pubkey get() = table
        override fun toString(): String = "$SCHEME://$HOST_STACK/${table.toBase58()}"
    }

    /** A gift escrow to claim (or, for its sender, to refund after expiry). */
    data class Gift(val escrow: Pubkey) : HeadsDownLink {
        override val address: Pubkey get() = escrow
        override fun toString(): String = "$SCHEME://$HOST_GIFT/${escrow.toBase58()}"
    }

    companion object {
        const val SCHEME = "headsdown"
        const val HOST_STACK = "stack"
        const val HOST_GIFT = "gift"

        /** The longest text that can be a link: scheme, the longer host, and a 44-character address. */
        const val MAX_LENGTH = 64

        private val SHAPE = Regex("headsdown://(stack|gift)/([1-9A-HJ-NP-Za-km-z]{32,44})")

        /** The link in [text], or null when [text] is not exactly one well-formed link. */
        fun parse(text: String?): HeadsDownLink? {
            if (text == null || text.length > MAX_LENGTH) return null
            val match = SHAPE.matchEntire(text) ?: return null
            val encoded = match.groupValues[2]
            val bytes = try {
                Base58.decode(encoded)
            } catch (_: IllegalArgumentException) {
                return null
            }
            if (bytes.size != Pubkey.BYTES) return null
            val address = Pubkey(bytes)
            // One address has one spelling, and the all-zero address is the System program, never an account of ours.
            if (address.toBase58() != encoded || address == Pubkey.DEFAULT) return null
            return when (match.groupValues[1]) {
                HOST_STACK -> Stack(address)
                HOST_GIFT -> Gift(address)
                else -> null
            }
        }

        /**
         * A bare address pasted where a link was expected (the "paste the code" fallback of an
         * invite): accepted only as a canonical base58 address, and only for the [host] asked.
         */
        fun fromAddress(text: String?, host: String): HeadsDownLink? = parse(text?.let { "$SCHEME://$host/${it.trim()}" })
    }
}
