package xyz.headsdown.core.chain

import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import java.io.Closeable

/** A MockWebServer behind TLS, and an OkHttp client that trusts only it. */
class TlsServer : Closeable {
    private val cert = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
    private val trust = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
    val server = MockWebServer()
    val client: OkHttpClient = OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager).build()

    init {
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(cert).build().sslSocketFactory())
        server.start()
    }

    fun url(path: String = ""): String = "https://localhost:${server.port}$path"

    override fun close() = server.close()
}
