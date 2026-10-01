package xyz.headsdown.config

import kotlinx.coroutines.CoroutineScope
import okhttp3.OkHttpClient
import xyz.headsdown.core.chain.http.JsonHttp
import xyz.headsdown.core.chain.http.OkHttpJsonHttp
import xyz.headsdown.core.chain.rpc.JsonRpcTransport
import xyz.headsdown.core.chain.rpc.OkHttpJsonRpcTransport
import xyz.headsdown.core.chain.uplink.CrankUplink
import xyz.headsdown.core.chain.uplink.MessageUplink

/**
 * Builds the RPC transport and crank uplink for this build's endpoints. Each build type picks
 * its implementation in its own source set (`BuildTransports`): debug and release use
 * [SecureTransports] only; `localdev` adds the loopback cleartext transports.
 */
interface EndpointTransports {
    fun rpc(url: String, client: OkHttpClient): JsonRpcTransport

    /** JSON over HTTP(S) for the registrar and the indexer. */
    fun http(url: String, client: OkHttpClient): JsonHttp

    /** The crank intake (contract A): [onText] receives the crank's frames (acks). */
    fun uplink(
        url: String,
        client: OkHttpClient,
        scope: CoroutineScope,
        onConnected: () -> Unit,
        onText: (String) -> Unit,
    ): MessageUplink
}

/** HTTPS JSON-RPC and the WSS crank uplink from core/chain, which refuse anything else. */
object SecureTransports : EndpointTransports {
    override fun rpc(url: String, client: OkHttpClient): JsonRpcTransport = OkHttpJsonRpcTransport(url, client)

    override fun http(url: String, client: OkHttpClient): JsonHttp = OkHttpJsonHttp(url, client)

    override fun uplink(
        url: String,
        client: OkHttpClient,
        scope: CoroutineScope,
        onConnected: () -> Unit,
        onText: (String) -> Unit,
    ): MessageUplink = CrankUplink(url, client, scope, onConnected = onConnected, onText = onText)
}
