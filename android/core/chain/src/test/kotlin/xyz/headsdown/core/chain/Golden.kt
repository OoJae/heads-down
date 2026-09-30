package xyz.headsdown.core.chain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.ec.CustomNamedCurves
import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.crypto.params.ECPrivateKeyParameters
import org.bouncycastle.crypto.signers.ECDSASigner
import org.bouncycastle.crypto.signers.HMacDSAKCalculator
import java.math.BigInteger
import java.security.MessageDigest

/**
 * The frozen contract's golden vectors (`programs/heads-down/vectors`), read from the drift-checked
 * copy under `src/test/resources/golden` (see `verifyGoldenVectors` in build.gradle.kts).
 */
object Golden {
    val instructions: JsonObject by lazy { load("instructions.json") }
    val messages: JsonObject by lazy { load("messages.json") }
    val registrar: JsonObject by lazy { load("registrar.json") }

    /** Every instruction vector by name. */
    val vectors: Map<String, JsonObject> by lazy {
        instructions["instructions"]!!.jsonArray.associate { it.jsonObject.str("name") to it.jsonObject }
    }

    fun load(name: String): JsonObject =
        Json.parseToJsonElement(javaClass.getResource("/golden/$name")?.readText() ?: error("golden/$name missing: run :core:chain:syncGoldenVectors"))
            .jsonObject
}

fun JsonObject.obj(key: String): JsonObject = this[key]?.jsonObject ?: error("missing object $key")

fun JsonObject.arr(key: String): JsonArray = this[key]?.jsonArray ?: error("missing array $key")

fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.content ?: error("missing $key")

/** u64 fields are decimal strings in the golden files; u8 fields are numbers. Both parse. */
fun JsonObject.u64(key: String): ULong = str(key).toULong()

fun JsonObject.int(key: String): Int = str(key).toInt()

fun JsonObject.bool(key: String): Boolean = str(key).toBooleanStrict()

fun JsonObject.pubkey(key: String): Pubkey = Pubkey.fromBase58(str(key))

fun JsonElement.asObject(): JsonObject = jsonObject

fun JsonElement.content(): String = jsonPrimitive.content

/** RFC 6979 (HMAC-SHA256) deterministic ECDSA-P256 over [message] (which is hashed once more). */
fun rfc6979Sign(privateScalarHex: String, message: ByteArray): ByteArray {
    val x9 = CustomNamedCurves.getByName("secp256r1")
    val params = ECDomainParameters(x9.curve, x9.g, x9.n, x9.h)
    val signer = ECDSASigner(HMacDSAKCalculator(SHA256Digest()))
    signer.init(true, ECPrivateKeyParameters(BigInteger(privateScalarHex, 16), params))
    val (r, s) = signer.generateSignature(MessageDigest.getInstance("SHA-256").digest(message))
    fun fixed(v: BigInteger) = v.toByteArray().let { b -> ByteArray(32 - minOf(32, b.size)) + b.takeLast(32).toByteArray() }
    return fixed(r) + fixed(s)
}
