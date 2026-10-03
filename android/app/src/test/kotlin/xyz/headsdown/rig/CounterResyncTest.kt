package xyz.headsdown.rig

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import xyz.headsdown.core.chain.HeadsDownProgram
import xyz.headsdown.core.chain.Pubkey
import xyz.headsdown.core.chain.rpc.JsonRpcTransport
import xyz.headsdown.core.chain.rpc.SolanaJsonRpc
import xyz.headsdown.core.keys.CounterStore
import xyz.headsdown.core.keys.RigCounter
import xyz.headsdown.feature.shift.RigBinding
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

/** stale_counter → re-read Rig.hb_counter on-chain and raise the local floor (never lower it). */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CounterResyncTest {

    private val authority = Pubkey(ByteArray(32) { 5 })
    private val rig = HeadsDownProgram.rig(authority)
    private var local = 3uL
    private val counter = RigCounter(object : CounterStore {
        override fun load() = local
        override fun store(value: ULong): Boolean { local = value; return true }
    })
    private var reads = 0

    private fun rigAccountJson(hbCounter: Long): String {
        val data = ByteBuffer.allocate(384).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(0, 2); put(1, 1); put(2, rig.bump.toByte())
            position(8); put(authority.bytes)
            position(40); put(byteArrayOf(0x02) + ByteArray(32) { 1 })
            putLong(208, hbCounter)
        }.array()
        return """{"data":["${Base64.getEncoder().encodeToString(data)}","base64"],"executable":false,"lamports":1,"owner":"${HeadsDownProgram.ID}","rentEpoch":0,"space":384}"""
    }

    /** What `close_rig` leaves at the Rig PDA: 32 bytes, tag 10, the counters it resumes from. */
    private fun tombstoneJson(hbCounter: Long): String {
        val data = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(0, 10); put(1, 1); put(2, rig.bump.toByte())
            putLong(8, 7); putLong(16, hbCounter)
        }.array()
        return """{"data":["${Base64.getEncoder().encodeToString(data)}","base64"],"executable":false,"lamports":1113600,"owner":"${HeadsDownProgram.ID}","rentEpoch":0,"space":32}"""
    }

    private fun rpc(hbCounter: Long) = rpcAnswering(rigAccountJson(hbCounter))

    private fun rpcAnswering(account: String) = SolanaJsonRpc(JsonRpcTransport { body ->
        reads++
        val id = Json.parseToJsonElement(body).jsonObject["id"]
        """{"jsonrpc":"2.0","id":$id,"result":{"context":{"slot":1},"value":$account}}"""
    })

    private fun resync(scope: TestScope, hbCounter: Long, now: () -> Long = { 0L }) =
        CounterResync(rpc(hbCounter), { RigBinding(HeadsDownProgram.ID.bytes, rig.address.bytes) }, counter, scope, now)

    @Test
    fun `the local counter is raised above the on-chain counter`() = runTest {
        assertEquals(500uL, resync(this, 500).resync())
        assertEquals(500uL, counter.current())
        assertEquals(501uL, counter.next())
        // A lower on-chain value never lowers the local counter.
        resync(this, 7).resync()
        assertEquals(501uL, counter.current())
    }

    @Test
    fun `a closed rig's tombstone still raises the counter, and an address with only lamports reads as no rig`() = runTest {
        val bound = { RigBinding(HeadsDownProgram.ID.bytes, rig.address.bytes) }
        assertEquals(260uL, CounterResync(rpcAnswering(tombstoneJson(260)), bound, counter, this, { 0L }).resync())
        assertEquals(260uL, counter.current())
        val lamportsOnly = """{"data":["","base64"],"executable":false,"lamports":890880,"owner":"11111111111111111111111111111111","rentEpoch":0,"space":0}"""
        assertNull(CounterResync(rpcAnswering(lamportsOnly), bound, counter, this, { 0L }).resync())
        assertNull(CounterResync(rpcAnswering("null"), bound, counter, this, { 0L }).resync())
        assertEquals(260uL, counter.current())
    }

    @Test
    fun `requests are rate-limited and an unbound phone reads nothing`() = runTest {
        var now = 0L
        val r = resync(this, 40, now = { now })
        r.request()
        r.request()
        advanceUntilIdle()
        assertEquals(1, reads)
        now += CounterResync.MIN_INTERVAL_MILLIS
        r.request()
        advanceUntilIdle()
        assertEquals(2, reads)
        assertEquals(40uL, counter.current())
        val unbound = CounterResync(rpc(99), { RigBinding.UNREGISTERED }, counter, this, { 0L })
        assertNull(unbound.resync())
        assertEquals(40uL, counter.current())
    }
}
