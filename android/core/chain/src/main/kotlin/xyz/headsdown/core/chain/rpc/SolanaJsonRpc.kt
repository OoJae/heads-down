package xyz.headsdown.core.chain.rpc

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import xyz.headsdown.core.chain.Pubkey
import xyz.headsdown.core.wallet.Base58
import xyz.headsdown.core.wallet.Commitment
import xyz.headsdown.core.wallet.SignatureStatus
import xyz.headsdown.core.wallet.SolanaRpc
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong

/** An account as returned by `getAccountInfo` / `getMultipleAccounts` (base64). */
class AccountInfo(
    val lamports: ULong,
    val owner: Pubkey,
    data: ByteArray,
    val executable: Boolean,
) {
    private val bytes = data.copyOf()
    val data: ByteArray get() = bytes.copyOf()
    val size: Int get() = bytes.size

    override fun toString(): String = "AccountInfo(owner=$owner, lamports=$lamports, len=$size)"
}

class KeyedAccount(val pubkey: Pubkey, val account: AccountInfo)

class LatestBlockhash(blockhash: ByteArray, val lastValidBlockHeight: Long, val contextSlot: Long) {
    private val hash = blockhash.copyOf()
    val blockhash: ByteArray get() = hash.copyOf()

    init {
        require(hash.size == 32) { "blockhash is 32 bytes" }
        require(lastValidBlockHeight > 0)
    }
}

/** `getProgramAccounts` filters. */
sealed interface AccountFilter {
    data class DataSize(val bytes: Int) : AccountFilter

    /** Bytes are sent base58-encoded (the RPC limit is 128 bytes). */
    class Memcmp(val offset: Int, bytes: ByteArray) : AccountFilter {
        val bytes: ByteArray = bytes.copyOf()

        init {
            require(offset >= 0) { "negative memcmp offset" }
            require(this.bytes.size in 1..128) { "memcmp bytes must be 1..128" }
        }
    }
}

/**
 * The Solana JSON-RPC methods the app needs, with strict response parsing: anything that is
 * not the documented shape throws [RpcProtocolException] (never an NPE or a silent default),
 * and a response whose `id` is not the request's is rejected.
 *
 * Implements [SolanaRpc] so it plugs straight into the existing `ConfirmationPoller`.
 */
class SolanaJsonRpc(
    private val transport: JsonRpcTransport,
    private val commitment: Commitment = Commitment.CONFIRMED,
) : SolanaRpc {
    private val ids = AtomicLong(1)

    suspend fun getAccountInfo(address: Pubkey, commitment: Commitment = this.commitment): AccountInfo? {
        val result = call("getAccountInfo", buildJsonArray {
            add(address.toBase58())
            addJsonObject {
                put("encoding", "base64")
                put("commitment", commitment.rpcName)
            }
        })
        return parseAccount(result.obj("result").field("value"))
    }

    /** One entry per requested address, in order; null where the account does not exist. */
    suspend fun getMultipleAccounts(addresses: List<Pubkey>, commitment: Commitment = this.commitment): List<AccountInfo?> {
        require(addresses.size in 1..MAX_MULTIPLE_ACCOUNTS) { "1..$MAX_MULTIPLE_ACCOUNTS addresses" }
        val result = call("getMultipleAccounts", buildJsonArray {
            add(buildJsonArray { addresses.forEach { add(it.toBase58()) } })
            addJsonObject {
                put("encoding", "base64")
                put("commitment", commitment.rpcName)
            }
        })
        val values = result.obj("result").field("value").arr("value")
        if (values.size != addresses.size) throw RpcProtocolException("getMultipleAccounts returned ${values.size} of ${addresses.size}")
        return values.map(::parseAccount)
    }

    suspend fun getLatestBlockhash(commitment: Commitment = this.commitment): LatestBlockhash {
        val result = call("getLatestBlockhash", buildJsonArray {
            addJsonObject { put("commitment", commitment.rpcName) }
        }).obj("result")
        val value = result.field("value").obj("value")
        val hash = decodeBase58(value.field("blockhash").string("blockhash"), 32, "blockhash")
        val height = value.field("lastValidBlockHeight").long("lastValidBlockHeight")
        if (height <= 0) throw RpcProtocolException("lastValidBlockHeight")
        val slot = result.field("context").obj("context").field("slot").long("slot")
        return LatestBlockhash(hash, height, slot)
    }

    /** Returns the base58 transaction signature. */
    suspend fun sendTransaction(
        transaction: ByteArray,
        skipPreflight: Boolean = false,
        preflightCommitment: Commitment = this.commitment,
        maxRetries: Int? = null,
    ): String {
        require(transaction.isNotEmpty())
        val result = call("sendTransaction", buildJsonArray {
            add(Base64.getEncoder().encodeToString(transaction))
            addJsonObject {
                put("encoding", "base64")
                put("skipPreflight", skipPreflight)
                put("preflightCommitment", preflightCommitment.rpcName)
                if (maxRetries != null) put("maxRetries", maxRetries)
            }
        })
        val signature = result.string("result")
        decodeBase58(signature, 64, "signature")
        return signature
    }

    /** One entry per signature, in order; null when the cluster has no status for it. */
    suspend fun getSignatureStatuses(signatures: List<String>, searchTransactionHistory: Boolean = false): List<SignatureStatus?> {
        require(signatures.size in 1..MAX_SIGNATURE_STATUSES) { "1..$MAX_SIGNATURE_STATUSES signatures" }
        val result = call("getSignatureStatuses", buildJsonArray {
            add(buildJsonArray { signatures.forEach { add(it) } })
            addJsonObject { put("searchTransactionHistory", searchTransactionHistory) }
        })
        val values = result.obj("result").field("value").arr("value")
        if (values.size != signatures.size) throw RpcProtocolException("getSignatureStatuses returned ${values.size} of ${signatures.size}")
        return values.map { v ->
            if (v is JsonNull) return@map null
            val o = v.obj("status")
            val err = o["err"]
            SignatureStatus(
                slot = o.field("slot").long("slot"),
                confirmationStatus = when (val s = o["confirmationStatus"]) {
                    null, is JsonNull -> null
                    else -> Commitment.entries.firstOrNull { it.rpcName == s.string("confirmationStatus") }
                        ?: throw RpcProtocolException("confirmationStatus")
                },
                // The raw JSON of TransactionError, or null when the transaction succeeded.
                err = if (err == null || err is JsonNull) null else err.toString(),
            )
        }
    }

    suspend fun getBalance(address: Pubkey, commitment: Commitment = this.commitment): ULong {
        val result = call("getBalance", buildJsonArray {
            add(address.toBase58())
            addJsonObject { put("commitment", commitment.rpcName) }
        })
        return result.obj("result").field("value").ulong("value")
    }

    suspend fun getProgramAccounts(
        programId: Pubkey,
        filters: List<AccountFilter>,
        commitment: Commitment = this.commitment,
    ): List<KeyedAccount> {
        require(filters.size <= MAX_FILTERS) { "at most $MAX_FILTERS filters" }
        val result = call("getProgramAccounts", buildJsonArray {
            add(programId.toBase58())
            addJsonObject {
                put("encoding", "base64")
                put("commitment", commitment.rpcName)
                putJsonArray("filters") {
                    for (f in filters) when (f) {
                        is AccountFilter.DataSize -> addJsonObject { put("dataSize", f.bytes) }
                        is AccountFilter.Memcmp -> addJsonObject {
                            putJsonObject("memcmp") {
                                put("offset", f.offset)
                                put("bytes", Base58.encode(f.bytes))
                                put("encoding", "base58")
                            }
                        }
                    }
                }
            }
        })
        return result.arr("result").map { entry ->
            val o = entry.obj("keyed account")
            val pubkey = Pubkey(decodeBase58(o.field("pubkey").string("pubkey"), 32, "pubkey"))
            val account = parseAccount(o.field("account")) ?: throw RpcProtocolException("null account")
            KeyedAccount(pubkey, account)
        }
    }

    override suspend fun getBlockHeight(commitment: Commitment): Long {
        val result = call("getBlockHeight", buildJsonArray {
            addJsonObject { put("commitment", commitment.rpcName) }
        })
        return result.long("result")
    }

    override suspend fun getSignatureStatus(signature: String): SignatureStatus? =
        getSignatureStatuses(listOf(signature)).single()

    // ------------------------------------------------------------------------------ internals

    /** Sends one request and returns its `result` element (after id and error checks). */
    private suspend fun call(method: String, params: JsonArray): JsonElement {
        val id = ids.getAndIncrement()
        val request = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", params)
        }
        val raw = transport.post(request.toString())
        val parsed = try {
            Json.parseToJsonElement(raw)
        } catch (_: SerializationException) {
            throw RpcProtocolException("not JSON")
        } catch (_: IllegalArgumentException) {
            throw RpcProtocolException("not JSON")
        }
        val response = parsed.obj("response")
        val responseId = response.field("id")
        if (responseId !is JsonPrimitive || responseId.content != id.toString()) {
            throw RpcProtocolException("response id does not match request")
        }
        response["error"]?.takeUnless { it is JsonNull }?.let { e ->
            val err = e.obj("error")
            val code = err["code"]?.let { runCatching { it.long("code") }.getOrNull() } ?: 0L
            val message = (err["message"] as? JsonPrimitive)?.content.orEmpty().take(MAX_ERROR_DETAIL)
            throw RpcErrorException(code, message)
        }
        return response["result"] ?: throw RpcProtocolException("no result")
    }

    private fun parseAccount(value: JsonElement): AccountInfo? {
        if (value is JsonNull) return null
        val o = value.obj("account")
        val data = o.field("data").arr("data")
        if (data.size != 2 || data[1].string("encoding") != "base64") throw RpcProtocolException("account data encoding")
        val bytes = try {
            Base64.getDecoder().decode(data[0].string("data"))
        } catch (_: IllegalArgumentException) {
            throw RpcProtocolException("account data base64")
        }
        return AccountInfo(
            lamports = o.field("lamports").ulong("lamports"),
            owner = Pubkey(decodeBase58(o.field("owner").string("owner"), 32, "owner")),
            data = bytes,
            executable = o.field("executable").bool("executable"),
        )
    }

    private fun decodeBase58(text: String, size: Int, what: String): ByteArray {
        val bytes = try {
            Base58.decode(text)
        } catch (_: IllegalArgumentException) {
            throw RpcProtocolException("$what is not base58")
        }
        if (bytes.size != size) throw RpcProtocolException("$what is not $size bytes")
        return bytes
    }

    companion object {
        const val MAX_MULTIPLE_ACCOUNTS = 100
        const val MAX_SIGNATURE_STATUSES = 256
        const val MAX_FILTERS = 4
        private const val MAX_ERROR_DETAIL = 200
    }
}

// ---------------------------------------------------------------------- strict JSON access

private fun JsonElement.obj(what: String): JsonObject = this as? JsonObject ?: throw RpcProtocolException("$what is not an object")

private fun JsonElement.arr(what: String): JsonArray = this as? JsonArray ?: throw RpcProtocolException("$what is not an array")

private fun JsonObject.field(name: String): JsonElement = this[name] ?: throw RpcProtocolException("missing $name")

private fun JsonElement.primitive(what: String): JsonPrimitive =
    this as? JsonPrimitive ?: throw RpcProtocolException("$what is not a primitive")

private fun JsonElement.string(what: String): String {
    val p = primitive(what)
    if (!p.isString) throw RpcProtocolException("$what is not a string")
    return p.content
}

private fun JsonElement.long(what: String): Long {
    val p = primitive(what)
    if (p.isString) throw RpcProtocolException("$what is not a number")
    return p.content.toLongOrNull() ?: throw RpcProtocolException("$what is not an integer")
}

/** u64 values such as `rentEpoch` or lamports exceed Long; parse the literal exactly. */
private fun JsonElement.ulong(what: String): ULong {
    val p = primitive(what)
    if (p.isString) throw RpcProtocolException("$what is not a number")
    return p.content.toULongOrNull() ?: throw RpcProtocolException("$what is not a u64")
}

private fun JsonElement.bool(what: String): Boolean {
    val p = primitive(what)
    if (p.isString) throw RpcProtocolException("$what is not a boolean")
    return p.content.toBooleanStrictOrNull() ?: throw RpcProtocolException("$what is not a boolean")
}
