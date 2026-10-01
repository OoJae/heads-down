package xyz.headsdown.config

import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

enum class EndpointKind(val secureScheme: String, val cleartextScheme: String) {
    /** Solana JSON-RPC. */
    RPC("https", "http"),

    /** The crank's heartbeat intake (WebSocket, contract A). */
    CRANK("wss", "ws"),

    /** The Key Attestation registrar (SIWS + vouchers). */
    REGISTRAR("https", "http"),

    /** The indexer's morning-haul API (contract B). */
    INDEXER("https", "http"),
}

sealed interface EndpointVerdict {
    /** `https` / `wss`: allowed in every build. */
    data object Secure : EndpointVerdict

    /** `http` / `ws` to 127.0.0.1 or localhost: the `localdev` build only (adb reverse). */
    data object LoopbackCleartext : EndpointVerdict

    /**
     * An empty URL where the service is optional: a local-only crank (no digs), no registrar
     * (every rig a guest), no indexer (no morning haul).
     */
    data object Disabled : EndpointVerdict

    data class Refused(val reason: String) : EndpointVerdict
}

/**
 * Which RPC and crank URLs a build may talk to. `app/build.gradle.kts` applies the same rules
 * at configuration time (a release build with an `http://` RPC fails to configure), and
 * `AppModule` applies this class again at startup, before any transport is built.
 *
 * - Every build: `https` RPC and `wss` crank, with a host, and no user-info or query string
 *   (that is where provider keys hide, `?api-key=`), and no fragment.
 * - Only when [allowLoopbackCleartext] (the `localdev` build type): `http` RPC and `ws` crank to
 *   exactly `127.0.0.1` or `localhost`, which `adb reverse` forwards to a devstack on the
 *   laptop. Nothing else is ever cleartext: not a LAN address, not the emulator's 10.0.2.2,
 *   not a look-alike host such as `127.0.0.1.nip.io` or `localhost.example.com`.
 */
object EndpointPolicy {
    val LOOPBACK_HOSTS: Set<String> = setOf("127.0.0.1", "localhost")

    fun check(kind: EndpointKind, url: String, allowLoopbackCleartext: Boolean): EndpointVerdict {
        if (url.isEmpty()) {
            return if (kind == EndpointKind.RPC) EndpointVerdict.Refused("empty RPC URL") else EndpointVerdict.Disabled
        }
        val uri = try {
            URI(url)
        } catch (_: URISyntaxException) {
            return EndpointVerdict.Refused("not a URL")
        }
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return EndpointVerdict.Refused("no scheme")
        val host = uri.host?.lowercase(Locale.ROOT)
        if (host.isNullOrEmpty()) return EndpointVerdict.Refused("no host")
        if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) {
            return EndpointVerdict.Refused("must not carry credentials, a query string or a fragment")
        }
        return when (scheme) {
            kind.secureScheme -> EndpointVerdict.Secure
            kind.cleartextScheme -> when {
                !allowLoopbackCleartext -> EndpointVerdict.Refused("${kind.cleartextScheme}:// is allowed only in the localdev build")
                host !in LOOPBACK_HOSTS -> EndpointVerdict.Refused("${kind.cleartextScheme}:// is allowed only to 127.0.0.1 or localhost")
                else -> EndpointVerdict.LoopbackCleartext
            }
            else -> EndpointVerdict.Refused("must be ${kind.secureScheme}://")
        }
    }

    /** Fails fast (at app start) on a refused endpoint. The message never contains the URL. */
    fun require(kind: EndpointKind, url: String, allowLoopbackCleartext: Boolean): EndpointVerdict {
        val verdict = check(kind, url, allowLoopbackCleartext)
        check(verdict !is EndpointVerdict.Refused) { "$kind endpoint refused: ${(verdict as EndpointVerdict.Refused).reason}" }
        return verdict
    }
}
