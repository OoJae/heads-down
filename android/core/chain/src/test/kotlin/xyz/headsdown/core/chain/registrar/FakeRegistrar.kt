package xyz.headsdown.core.chain.registrar

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import xyz.headsdown.core.chain.HeadsDownProgram
import xyz.headsdown.core.chain.Pubkey
import xyz.headsdown.core.chain.TestVouchers
import xyz.headsdown.core.chain.hex
import xyz.headsdown.core.chain.hexBytes
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A fake of the registrar's HTTP API (registrar/src/http), answering exactly the JSON shapes the
 * real handlers serialize, with knobs for every failure the phone must survive.
 */
class FakeRegistrar : Dispatcher() {
    var down = false
    var domain = "headsdown.example"
    var uri = "https://headsdown.example"
    var chainIds = listOf("solana:devnet")
    var level = 2
    var expirySlot = 458_180_000uL
    var attestStatus = 200
    var attestError = "attestation_rejected"
    var tamperChallenge = false
    var tamperVoucherLevel = false
    var sessionAddress: String? = null

    val token = "hds1.eyJzdWIiOiJ0ZXN0In0.c2lnbmF0dXJlLWJ5dGVz"
    val nonceHex = "00112233445566778899aabbccddeeff"
    val requests = CopyOnWriteArrayList<RecordedRequest>()

    /** The wallet that signed in (from /siws/verify), for the challenge. */
    @Volatile private var authority: Pubkey? = null

    private fun json(status: Int, body: String) = MockResponse.Builder().code(status).addHeader("Content-Type", "application/json").body(body).build()

    private fun error(status: Int, code: String) = json(status, """{"error":"$code","message":"fixed text"}""")

    override fun dispatch(request: RecordedRequest): MockResponse {
        requests += request
        if (down) return error(503, "status_list_unavailable")
        val body = request.body?.utf8().orEmpty()
        return when ("${request.method} ${request.url.encodedPath}") {
            "POST /siws/nonce" -> json(
                200,
                """{"nonce":"a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6","domain":"$domain","uri":"$uri","version":"1",""" +
                    """"chain_ids":[${chainIds.joinToString(",") { "\"$it\"" }}],"statement":"Sign in to Heads Down.",""" +
                    """"issued_at":"2026-10-01T12:00:00Z","expiration_time":"2026-10-01T12:10:00Z"}""",
            )
            "POST /siws/verify" -> {
                val o = Json.parseToJsonElement(body).jsonObject
                if (o.keys != setOf("signed_message", "signature", "address")) return error(400, "invalid_json")
                Base64.getDecoder().decode(o["signed_message"]!!.jsonPrimitive.content)
                if (Base64.getDecoder().decode(o["signature"]!!.jsonPrimitive.content).size != 64) return error(400, "signature_malformed")
                val address = o["address"]!!.jsonPrimitive.content
                authority = Pubkey.fromBase58(address)
                json(200, """{"session_token":"$token","address":"${sessionAddress ?: address}","chain_id":"${chainIds.first()}","expires_at":"2026-10-01T13:00:00Z"}""")
            }
            "GET /attest/challenge" -> {
                if (request.headers["Authorization"] != "Bearer $token") return error(401, "session_missing")
                val who = authority ?: return error(401, "session_invalid")
                val challenge = RegistrarClient.challengeFor(who, hexBytes(nonceHex)).also { if (tamperChallenge) it[0] = (it[0] + 1).toByte() }
                json(
                    200,
                    """{"authority":"$who","nonce":"$nonceHex","challenge":"${challenge.hex()}",""" +
                        """"challenge_base64":"${Base64.getEncoder().encodeToString(challenge)}","expires_at":"2026-10-01T12:10:00Z"}""",
                )
            }
            "POST /attest" -> {
                if (request.headers["Authorization"] != "Bearer $token") return error(401, "session_missing")
                val o = Json.parseToJsonElement(body).jsonObject
                if (o.keys != setOf("authority", "p256_pubkey_compressed", "attestation_chain", "nonce", "session_token")) return error(400, "invalid_json")
                if (o["session_token"]!!.jsonPrimitive.content != token) return error(400, "session_conflict")
                if (o["nonce"]!!.jsonPrimitive.content != nonceHex) return error(401, "nonce_unknown_or_used")
                if (o["attestation_chain"]!!.jsonArray.isEmpty()) return error(400, "chain_length")
                if (attestStatus != 200) return error(attestStatus, attestError)
                val who = Pubkey.fromBase58(o["authority"]!!.jsonPrimitive.content)
                val p256 = hexBytes(o["p256_pubkey_compressed"]!!.jsonPrimitive.content)
                val data = TestVouchers.instructionData(who, p256, if (tamperVoucherLevel) 1 else level, expirySlot)
                val registrar = TestVouchers.registrarKey()
                json(
                    200,
                    """{"level":$level,"expiry_slot":$expirySlot,"issued_slot":451700000,"program_id":"${HeadsDownProgram.ID}",""" +
                        """"authority":"$who","p256_pubkey":"${p256.hex()}","registrar":"$registrar","message":"${data.copyOfRange(112, 223).hex()}",""" +
                        """"signature":"${data.copyOfRange(48, 112).hex()}","ed25519_instruction":{"program_id":"Ed25519SigVerify111111111111111111111111111",""" +
                        """"accounts":[],"data_base64":"${Base64.getEncoder().encodeToString(data)}","data_hex":"${data.hex()}"},""" +
                        """"attestation":{"level":$level},"log_index":7,"entry_hash":"00"}""",
                )
            }
            else -> error(404, "not_found")
        }
    }

    fun request(route: String): RecordedRequest = requests.single { "${it.method} ${it.url.encodedPath}" == route }

    fun body(route: String): JsonObject = Json.parseToJsonElement(request(route).body!!.utf8()).jsonObject

    fun field(route: String, key: String): String = (body(route)[key] as JsonPrimitive).content
}
