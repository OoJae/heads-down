package xyz.headsdown.config

import kotlinx.coroutines.CoroutineScope
import okhttp3.OkHttpClient
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

    fun uplink(url: String, client: OkHttpClient, scope: CoroutineScope, onConnected: () -> Unit): MessageUplink
}

/** HTTPS JSON-RPC and the WSS crank uplink from core/chain, which refuse anything else. */
object SecureTransports : EndpointTransports {
    override fun rpc(url: String, client: OkHttpClient): JsonRpcTransport = OkHttpJsonRpcTransport(url, client)

    override fun uplink(url: String, client: OkHttpClient, scope: CoroutineScope, onConnected: () -> Unit): MessageUplink =
        CrankUplink(url, client, scope, onConnected = onConnected)
}
