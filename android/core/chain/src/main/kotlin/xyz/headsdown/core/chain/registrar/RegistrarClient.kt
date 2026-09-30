package xyz.headsdown.core.chain.registrar

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add
import xyz.headsdown.core.chain.HeadsDownProgram
import xyz.headsdown.core.chain.Pubkey
import xyz.headsdown.core.chain.http.HttpReply
import xyz.headsdown.core.chain.http.JsonHttp
import xyz.headsdown.core.keys.P256
import xyz.headsdown.core.wallet.SiwsRequest
import java.io.IOException
import java.security.MessageDigest
import java.util.Base64

/**
 * The registrar refused a request or answered outside its protocol. [code] is the registrar's
 * machine code (`session_expired`, `nonce_expired`, `attestation_rejected`, …) or a local one
 * (`malformed`, `mismatch`); the message never contains a token, key or response body.
 */
class RegistrarException(val code: String, val status: Int = 0) : IOException("registrar: $code") {
    /** 5xx or 429: worth retrying later, not a verdict on this phone. */
    val unavailable: Boolean get() = status >= 500 || status == 429
}

/** The registrar's session (`hds1.…`, registrar N8). A bearer credential: memory only, never logged. */
@JvmInline
value class SessionToken(val value: String) {
    init {
        require(value.isNotEmpty()) { "empty session token" }
    }

    override fun toString(): String = "SessionToken(redacted)"
}

class RegistrarSession(val token: SessionToken, val address: Pubkey, val chainId: String, val expiresAt: String) {
    override fun toString(): String = "RegistrarSession(address=$address, chain=$chainId)"
}

/** `GET /attest/challenge`: the single-use nonce and the Keystore attestation challenge it implies. */
class AttestChallenge(val authority: Pubkey, nonce: ByteArray, challenge: ByteArray, val expiresAt: String) {
    private val n = nonce.copyOf()
    private val c = challenge.copyOf()
    val nonce: ByteArray get() = n.copyOf()

    /** Pass to `KeyGenParameterSpec.Builder.setAttestationChallenge` (registrar N1). */
    val challenge: ByteArray get() = c.copyOf()
}

/** `POST /attest`: the level the registrar vouches for, and the voucher when it is usable on-chain. */
class AttestResult(
    val level: Int,
    val expirySlot: ULong,
    /** Null for level 0: the program refuses level-0 vouchers, so the rig registers as a guest. */
    val voucher: RegistrarVoucher?,
)

/**
 * The registrar's HTTP API (`registrar/src/http`), strictly parsed:
 *
 * | Call | Route |
 * |---|---|
 * | [siwsNonce] | `POST /siws/nonce` → the SIWS fields to copy into the wallet's sign-in (registrar N7) |
 * | [siwsVerify] | `POST /siws/verify` → a session token |
 * | [attestChallenge] | `GET /attest/challenge` (Bearer) → nonce and challenge, re-derived locally |
 * | [attest] | `POST /attest` (chain, session token, nonce) → level and the 223-byte Ed25519 voucher |
 *
 * Everything the registrar returns is checked against the phone's own values: the domain and
 * cluster of the sign-in, the session's address, the challenge formula, and the voucher's program,
 * wallet, key, level and expiry. A mismatch is a [RegistrarException] and never reaches a
 * transaction.
 */
class RegistrarClient(
    private val http: JsonHttp,
    private val programId: Pubkey = HeadsDownProgram.ID,
) {
    suspend fun siwsNonce(expectedDomain: String, buildChainId: String): SiwsRequest {
        val o = ok(http.post("/siws/nonce", "{}"))
        return try {
            SiwsRequest.fromRegistrar(
                expectedDomain = expectedDomain,
                buildChainId = buildChainId,
                domain = o.string("domain"),
                uri = o.string("uri"),
                version = o.string("version"),
                chainIds = (o["chain_ids"] as? JsonArray ?: throw malformed()).map { (it as? JsonPrimitive)?.content ?: throw malformed() },
                statement = o.string("statement"),
                nonce = o.string("nonce"),
                issuedAt = o.string("issued_at"),
                expirationTime = o.string("expiration_time"),
            )
        } catch (_: IllegalArgumentException) {
            throw RegistrarException("siws_request_refused")
        }
    }

    suspend fun siwsVerify(signedMessage: ByteArray, signature: ByteArray, address: Pubkey): RegistrarSession {
        val body = buildJsonObject {
            put("signed_message", b64(signedMessage))
            put("signature", b64(signature))
            put("address", address.toBase58())
        }
        val o = ok(http.post("/siws/verify", body.toString()))
        val token = o.string("session_token").takeIf { it.length in 16..4096 } ?: throw malformed()
        val session = RegistrarSession(SessionToken(token), pubkey(o.string("address")), o.string("chain_id"), o.string("expires_at"))
        if (session.address != address) throw RegistrarException("mismatch")
        return session
    }

    suspend fun attestChallenge(session: RegistrarSession): AttestChallenge {
        val o = ok(http.get("/attest/challenge", bearer = session.token.value))
        val authority = pubkey(o.string("authority"))
        val nonce = hex(o.string("nonce"), NONCE_BYTES)
        val challenge = hex(o.string("challenge"), 32)
        // registrar N1: the phone derives the challenge itself; the server's copy must agree.
        if (authority != session.address || !challenge.contentEquals(challengeFor(authority, nonce))) {
            throw RegistrarException("mismatch")
        }
        return AttestChallenge(authority, nonce, challenge, o.string("expires_at"))
    }

    suspend fun attest(
        session: RegistrarSession,
        authority: Pubkey,
        p256Pubkey: ByteArray,
        attestationChain: List<ByteArray>,
        nonce: ByteArray,
    ): AttestResult {
        require(p256Pubkey.size == P256.COMPRESSED_PUBLIC_KEY_BYTES) { "p256 key must be compressed" }
        require(nonce.size == NONCE_BYTES) { "nonce is 16 bytes" }
        val body = buildJsonObject {
            put("authority", authority.toBase58())
            put("p256_pubkey_compressed", p256Pubkey.toHex())
            putJsonArray("attestation_chain") { attestationChain.forEach { add(b64(it)) } }
            put("nonce", nonce.toHex())
            put("session_token", session.token.value)
        }
        val o = ok(http.post("/attest", body.toString(), bearer = session.token.value))
        val level = o.int("level")
        if (level !in 0..2) throw malformed()
        val expiry = o.u64("expiry_slot")
        if (pubkey(o.string("program_id")) != programId || pubkey(o.string("authority")) != authority ||
            !hex(o.string("p256_pubkey"), P256.COMPRESSED_PUBLIC_KEY_BYTES).contentEquals(p256Pubkey)
        ) {
            throw RegistrarException("mismatch")
        }
        if (level == 0) return AttestResult(0, expiry, null)
        val ix = o["ed25519_instruction"] as? JsonObject ?: throw malformed()
        val data = try {
            Base64.getDecoder().decode(ix.string("data_base64"))
        } catch (_: IllegalArgumentException) {
            throw malformed()
        }
        val voucher = try {
            RegistrarVoucher.verify(data, authority, p256Pubkey, level, expiry, programId)
        } catch (_: VoucherException) {
            throw RegistrarException("voucher_refused")
        }
        if (pubkey(o.string("registrar")) != voucher.registrar) throw RegistrarException("mismatch")
        return AttestResult(level, expiry, voucher)
    }

    companion object {
        const val NONCE_BYTES = 16
        private val DOMAIN = "HDattest".toByteArray(Charsets.US_ASCII)
        private val CODE = Regex("[a-z0-9_]{1,48}")

        /** registrar N1: `SHA-256("HDattest" ‖ authority(32) ‖ nonce(16))`. */
        fun challengeFor(authority: Pubkey, nonce: ByteArray): ByteArray {
            require(nonce.size == NONCE_BYTES) { "nonce is 16 bytes" }
            return MessageDigest.getInstance("SHA-256").run {
                update(DOMAIN)
                update(authority.bytes)
                update(nonce)
                digest()
            }
        }

        /** 2xx → the JSON object; anything else → the registrar's error code (never its message). */
        private fun ok(reply: HttpReply): JsonObject {
            val parsed = parse(reply.body)
            if (!reply.isSuccessful) {
                val code = (parsed?.get("error") as? JsonPrimitive)?.content?.takeIf { it.matches(CODE) } ?: "http_${reply.status}"
                throw RegistrarException(code, reply.status)
            }
            return parsed ?: throw malformed()
        }

        private fun parse(body: String): JsonObject? = try {
            Json.parseToJsonElement(body) as? JsonObject
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

        private fun malformed() = RegistrarException("malformed")

        private fun JsonObject.string(key: String): String =
            (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw malformed()

        private fun JsonObject.int(key: String): Int = number(this[key])?.toIntOrNull() ?: throw malformed()

        private fun JsonObject.u64(key: String): ULong = number(this[key])?.toULongOrNull() ?: throw malformed()

        private fun number(e: JsonElement?): String? =
            (e as? JsonPrimitive)?.takeIf { !it.isString }?.content?.takeIf { it.isNotEmpty() && it.all { c -> c in '0'..'9' } }

        private fun pubkey(text: String): Pubkey = try {
            Pubkey.fromBase58(text)
        } catch (_: IllegalArgumentException) {
            throw malformed()
        }

        private fun hex(text: String, bytes: Int): ByteArray {
            if (text.length != bytes * 2 || !text.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) throw malformed()
            return ByteArray(bytes) { text.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
        }

        private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    }
}
