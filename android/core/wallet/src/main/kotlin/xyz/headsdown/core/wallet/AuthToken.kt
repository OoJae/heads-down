package xyz.headsdown.core.wallet

/**
 * An MWA auth token. It is a bearer credential for the wallet session, so it is wrapped in a
 * type whose [toString] never reveals it: string templates, exceptions and crash reports
 * that accidentally include it print `AuthToken(redacted)`.
 */
@JvmInline
value class AuthToken(val value: String) {
    init {
        require(value.isNotEmpty()) { "empty auth token" }
    }

    override fun toString(): String = "AuthToken(redacted)"
}
