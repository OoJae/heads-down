package xyz.headsdown.core.chain.registrar

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.headsdown.core.chain.Pubkey
import xyz.headsdown.core.chain.TlsServer
import xyz.headsdown.core.chain.hex
import xyz.headsdown.core.chain.hexBytes
import xyz.headsdown.core.chain.http.OkHttpJsonHttp
import xyz.headsdown.core.chain.ix.HeadsDownInstructions
import xyz.headsdown.core.keys.P256
import xyz.headsdown.core.wallet.SignInProof
import xyz.headsdown.core.wallet.SiwsRequest
import xyz.headsdown.core.wallet.WalletAccount
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/** The rig-key attestation against a fake registrar (MockWebServer over TLS). */
class RigAttestationFlowTest {

    private val tls = TlsServer()
    private val fake = FakeRegistrar().also { tls.server.dispatcher = it }
    private val client = RegistrarClient(OkHttpJsonHttp(tls.url(), tls.client))
    private val flow = RigAttestationFlow(client, domain = "headsdown.xyz", chainId = "solana:devnet")

    private val wallet = Pubkey.fromBase58("FGdcxXEsrhitpAXFa17Wn3eV5TCAjaCV1QzfoceyPPyx")
    private val keyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private val p256 = P256.compress(keyPair.public as ECPublicKey)
    private val certChain = listOf(byteArrayOf(0x30, 0x01, 0x01), byteArrayOf(0x30, 0x02, 0x02))

    private var signInRequest: SiwsRequest? = null
    private var challengeUsed: ByteArray? = null

    private val signIn: suspend (SiwsRequest) -> SignInProof? = { request ->
        signInRequest = request
        SignInProof(WalletAccount(wallet.bytes, "test"), "headsdown.xyz wants you to sign in".toByteArray(), ByteArray(64) { 7 })
    }

    private val generateKey: suspend (ByteArray) -> ChallengedKey = { challenge ->
        challengeUsed = challenge
        ChallengedKey(p256, certChain)
    }

    @After
    fun close() = tls.close()

    @Test
    fun `a StrongBox key comes back with a voucher the phone can register with`() = runBlocking {
        val run = flow.run(signIn, generateKey)
        assertEquals(AttestationOutcome.ATTESTED, run.outcome)
        val voucher = run.voucher!!
        assertEquals(2, voucher.level)
        assertTrue(voucher.covers(wallet, p256))
        assertEquals(wallet, run.authority)
        // The SIWS payload is the registrar's (N7), with this build's chain id.
        val request = signInRequest!!
        assertEquals("https://headsdown.xyz", request.uri)
        assertEquals("a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6", request.nonce)
        assertEquals("2026-10-01T12:00:00Z", request.issuedAt)
        assertEquals("2026-10-01T12:10:00Z", request.expirationTime)
        assertEquals("Sign in to Heads Down.", request.statement)
        assertEquals("solana:devnet", request.chainId)
        // The key was generated with SHA-256("HDattest" || authority || nonce) (registrar N1).
        val expected = MessageDigest.getInstance("SHA-256").digest("HDattest".toByteArray() + wallet.bytes + hexBytes(fake.nonceHex))
        assertArrayEquals(expected, challengeUsed)
        // What the phone sent: the chain, the session token (body and Bearer) and the nonce.
        assertEquals(wallet.toBase58(), fake.field("POST /siws/verify", "address"))
        assertEquals(p256.hex(), fake.field("POST /attest", "p256_pubkey_compressed"))
        assertEquals(fake.nonceHex, fake.field("POST /attest", "nonce"))
        assertEquals(fake.token, fake.field("POST /attest", "session_token"))
        assertEquals("Bearer ${fake.token}", fake.request("POST /attest").headers["Authorization"])
        assertEquals("Bearer ${fake.token}", fake.request("GET /attest/challenge").headers["Authorization"])
        val chain = fake.body("POST /attest")["attestation_chain"]!!.jsonArray.map { Base64.getDecoder().decode(it.jsonPrimitive.content) }
        assertEquals(certChain.map { it.hex() }, chain.map { it.hex() })
        // The voucher slots into register_rig as has_attestation = 1.
        val ix = HeadsDownInstructions.registerRig(wallet, p256, voucher.attestation(ed25519Ix = 0))
        assertEquals(46, ix.dataSize)
        assertEquals(1, ix.data[34].toInt())
        assertEquals(2, ix.data[37].toInt())
    }

    @Test
    fun `a level-0 voucher is never used and the rig registers as a guest`() = runBlocking {
        fake.level = 0
        val run = flow.run(signIn, generateKey)
        assertEquals(AttestationOutcome.LEVEL_ZERO, run.outcome)
        assertNull(run.voucher)
        assertNotNull("the key was generated with the challenge and stays", run.key)
    }

    @Test
    fun `an unreachable registrar stops before the wallet and before any key`() = runBlocking {
        fake.down = true
        var walletAsked = false
        val run = flow.run({ walletAsked = true; null }, generateKey)
        assertEquals(AttestationOutcome.REGISTRAR_UNAVAILABLE, run.outcome)
        assertFalse(walletAsked)
        assertNull(run.key)
        assertNull(challengeUsed)
        // No server at all (connection refused) is the same outcome.
        tls.close()
        val offline = RigAttestationFlow(RegistrarClient(OkHttpJsonHttp(tls.url(), tls.client)), "headsdown.xyz", "solana:devnet")
        assertEquals(AttestationOutcome.REGISTRAR_UNAVAILABLE, offline.run(signIn, generateKey).outcome)
    }

    @Test
    fun `the localdev build talks to the devstack registrar on localhost`() = runBlocking {
        // scripts/devstack/up.sh --with-registrar: HD_SIWS_DOMAIN=localhost, loopback URI, solana:localnet.
        fake.domain = "localhost"
        fake.uri = "http://127.0.0.1:8790"
        fake.chainIds = listOf("solana:localnet")
        val localdev = RigAttestationFlow(client, domain = "localhost", chainId = "solana:localnet", allowLoopbackUri = true)
        val run = localdev.run(signIn, generateKey)
        assertEquals(AttestationOutcome.ATTESTED, run.outcome)
        assertEquals("http://127.0.0.1:8790", signInRequest!!.uri)
        assertEquals("solana:localnet", signInRequest!!.chainId)
        // Any other build refuses a loopback URI before the wallet is asked.
        signInRequest = null
        val release = RigAttestationFlow(client, domain = "localhost", chainId = "solana:localnet")
        assertEquals(AttestationOutcome.REGISTRAR_UNAVAILABLE, release.run(signIn, generateKey).outcome)
        assertNull(signInRequest)
    }

    @Test
    fun `a declined sign-in stops before any key`() = runBlocking {
        val run = flow.run({ null }, generateKey)
        assertEquals(AttestationOutcome.SIGN_IN_DECLINED, run.outcome)
        assertNull(run.key)
        assertEquals(1, fake.requests.size) // only the nonce
    }

    @Test
    fun `a registrar for another domain or cluster never reaches the wallet`() = runBlocking {
        var walletAsked = false
        fake.domain = "evil.example"
        assertEquals(AttestationOutcome.REGISTRAR_UNAVAILABLE, flow.run({ walletAsked = true; null }, generateKey).outcome)
        fake.domain = "headsdown.xyz"
        fake.chainIds = listOf("solana:mainnet")
        assertEquals(AttestationOutcome.REGISTRAR_UNAVAILABLE, flow.run({ walletAsked = true; null }, generateKey).outcome)
        assertFalse(walletAsked)
    }

    @Test
    fun `answers that do not check out are rejected and never become a voucher`() = runBlocking {
        // A challenge that is not SHA-256("HDattest" || authority || nonce): no key is generated.
        fake.tamperChallenge = true
        val bad = flow.run(signIn, generateKey)
        assertEquals(AttestationOutcome.REJECTED, bad.outcome)
        assertEquals("mismatch", bad.code)
        assertNull(challengeUsed)
        fake.tamperChallenge = false
        // A session for another wallet.
        fake.sessionAddress = Pubkey(ByteArray(32) { 9 }).toBase58()
        assertEquals(AttestationOutcome.REJECTED, flow.run(signIn, generateKey).outcome)
        fake.sessionAddress = null
        // A voucher whose signed level differs from the level it claims.
        fake.tamperVoucherLevel = true
        val tampered = flow.run(signIn, generateKey)
        assertEquals(AttestationOutcome.REJECTED, tampered.outcome)
        assertEquals("voucher_refused", tampered.code)
        assertNull(tampered.voucher)
    }

    @Test
    fun `a refused chain is a guest, a registrar outage mid-flow is unavailable`() = runBlocking {
        fake.attestStatus = 422
        val refused = flow.run(signIn, generateKey)
        assertEquals(AttestationOutcome.REJECTED, refused.outcome)
        assertEquals("attestation_rejected", refused.code)
        assertNotNull(refused.key)
        fake.attestStatus = 503
        fake.attestError = "slot_unavailable"
        val outage = flow.run(signIn, generateKey)
        assertEquals(AttestationOutcome.REGISTRAR_UNAVAILABLE, outage.outcome)
        assertEquals("slot_unavailable", outage.code)
    }

    @Test
    fun `the registrar client refuses cleartext and never echoes tokens`() {
        assertThrows(IllegalArgumentException::class.java) { OkHttpJsonHttp("http://registrar.example", tls.client) }
        assertThrows(IllegalArgumentException::class.java) { OkHttpJsonHttp("https://registrar.example/?key=x", tls.client) }
        assertThrows(IllegalArgumentException::class.java) { OkHttpJsonHttp("https://user:pw@registrar.example", tls.client) }
        assertEquals("SessionToken(redacted)", SessionToken("hds1.secret.value").toString())
        assertFalse(RegistrarException("session_expired", 401).message!!.contains("hds1"))
    }
}
