package xyz.headsdown.core.wallet

import java.net.URI
import java.net.URISyntaxException
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/**
 * The Sign In With Solana fields the registrar's `POST /siws/nonce` returns (registrar N7),
 * copied verbatim into MWA's `SignInWithSolana.Payload`: the registrar verifies domain, URI,
 * version, chain id, nonce and the time window of the exact message the wallet signs.
 */
data class SiwsRequest(
    val domain: String,
    val uri: String,
    val statement: String,
    val version: String,
    /** CAIP-2, e.g. `solana:mainnet`, `solana:devnet`, `solana:localnet` (per build). */
    val chainId: String,
    val nonce: String,
    /** RFC 3339. */
    val issuedAt: String,
    /** RFC 3339. */
    val expirationTime: String,
) {
    companion object {
        /**
         * Builds the request from the registrar's answer, refusing anything that would make the
         * wallet sign a message for another site or cluster:
         * - the domain must be this app's SIWS domain, and the URI an `https` URL on it;
         * - [buildChainId] (the cluster this build talks to) must be one the registrar accepts;
         * - version 1, an 8..64 character alphanumeric nonce, RFC 3339 times, a one-line statement.
         *
         * @throws IllegalArgumentException with a fixed message (never the server's text).
         */
        fun fromRegistrar(
            expectedDomain: String,
            buildChainId: String,
            domain: String,
            uri: String,
            version: String,
            chainIds: List<String>,
            statement: String,
            nonce: String,
            issuedAt: String,
            expirationTime: String,
        ): SiwsRequest {
            require(domain == expectedDomain) { "registrar signs in for another domain" }
            val parsed = try {
                URI(uri)
            } catch (_: URISyntaxException) {
                throw IllegalArgumentException("registrar SIWS URI is not a URI")
            }
            val host = parsed.host?.lowercase()
            require(parsed.scheme == "https" && host != null && (host == expectedDomain || host.endsWith(".$expectedDomain"))) {
                "registrar SIWS URI is not an https URL on the app's domain"
            }
            require(parsed.rawUserInfo == null && parsed.rawQuery == null && parsed.rawFragment == null) { "registrar SIWS URI carries extra parts" }
            require(version == "1") { "unsupported SIWS version" }
            require(buildChainId in chainIds) { "registrar does not accept this build's cluster" }
            require(nonce.length in 8..64 && nonce.all { it.isLetterOrDigit() && it.code < 128 }) { "registrar nonce is malformed" }
            require(statement.length <= MAX_STATEMENT_CHARS && statement.none { it == '\n' || it == '\r' }) { "registrar statement is malformed" }
            val issued = rfc3339(issuedAt)
            val expires = rfc3339(expirationTime)
            require(expires.isAfter(issued)) { "registrar SIWS window is empty" }
            return SiwsRequest(domain, uri, statement, version, buildChainId, nonce, issuedAt, expirationTime)
        }

        private const val MAX_STATEMENT_CHARS = 200

        private fun rfc3339(text: String): OffsetDateTime = try {
            OffsetDateTime.parse(text)
        } catch (_: DateTimeParseException) {
            throw IllegalArgumentException("registrar SIWS time is not RFC 3339")
        }
    }
}
