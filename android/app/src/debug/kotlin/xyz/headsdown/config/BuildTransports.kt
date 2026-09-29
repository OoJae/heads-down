package xyz.headsdown.config

/** Debug builds talk HTTPS/WSS only, exactly like release. The loopback devstack is `localdev`. */
object BuildTransports {
    val transports: EndpointTransports = SecureTransports
}
