package xyz.headsdown.config

/**
 * Release builds talk HTTPS/WSS only. No cleartext transport is compiled into release: the
 * loopback classes exist only in src/localdev.
 */
object BuildTransports {
    val transports: EndpointTransports = SecureTransports
}
