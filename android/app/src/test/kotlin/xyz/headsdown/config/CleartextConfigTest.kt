package xyz.headsdown.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The manifest side of the endpoint policy, read from the sources every build type merges
 * (unit tests run with the app module as working directory).
 */
class CleartextConfigTest {
    private val android = "http://schemas.android.com/apk/res/android"

    private fun parse(path: String): Element {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        return factory.newDocumentBuilder().parse(File(path)).documentElement
    }

    private fun application(manifest: String): Element? =
        parse(manifest).getElementsByTagName("application").item(0) as Element?

    @Test
    fun `main manifest forbids cleartext and sets no network security config`() {
        val app = application("src/main/AndroidManifest.xml")!!
        assertEquals("false", app.getAttributeNS(android, "usesCleartextTraffic"))
        assertEquals("", app.getAttributeNS(android, "networkSecurityConfig"))
    }

    @Test
    fun `debug and release add no network security config`() {
        listOf("debug", "release").forEach { buildType ->
            val manifest = File("src/$buildType/AndroidManifest.xml")
            if (manifest.exists()) {
                assertEquals("", application(manifest.path)?.getAttributeNS(android, "networkSecurityConfig").orEmpty())
            }
            val xml = File("src/$buildType/res/xml")
            assertTrue("$buildType ships no network security config", xml.listFiles().orEmpty().none { "network" in it.name })
        }
    }

    @Test
    fun `localdev permits cleartext to exactly 127_0_0_1 and localhost`() {
        val app = application("src/localdev/AndroidManifest.xml")!!
        assertEquals("@xml/network_security_config_localdev", app.getAttributeNS(android, "networkSecurityConfig"))

        val config = parse("src/localdev/res/xml/network_security_config_localdev.xml")
        val base = config.getElementsByTagName("base-config").item(0) as Element
        assertEquals("false", base.getAttribute("cleartextTrafficPermitted"))
        val domainConfigs = config.getElementsByTagName("domain-config")
        assertEquals(1, domainConfigs.length)
        val domainConfig = domainConfigs.item(0) as Element
        assertEquals("true", domainConfig.getAttribute("cleartextTrafficPermitted"))
        val domains = domainConfig.getElementsByTagName("domain")
        val hosts = (0 until domains.length).map { domains.item(it) as Element }
        assertEquals(EndpointPolicy.LOOPBACK_HOSTS, hosts.map { it.textContent.trim() }.toSet())
        hosts.forEach { assertEquals("false", it.getAttribute("includeSubdomains")) }
        assertNull(config.getElementsByTagName("debug-overrides").item(0))
        assertNull(config.getElementsByTagName("trust-anchors").item(0))
    }

    @Test
    fun `loopback transports exist only in the localdev source set`() {
        val offenders = listOf("main", "debug", "release").flatMap { set ->
            File("src/$set").walkTopDown().filter { it.isFile && it.extension == "kt" }
                .filter { f -> f.readText().let { "LoopbackJsonRpcTransport" in it || "LoopbackUplink" in it } }
                .map { it.path }
                .toList()
        }
        assertTrue("loopback transport referenced in $offenders", offenders.isEmpty())
        assertTrue(File("src/localdev/kotlin/xyz/headsdown/config/BuildTransports.kt").readText().contains("class LoopbackUplink"))
        assertFalse(File("src/release/kotlin/xyz/headsdown/config/BuildTransports.kt").readText().contains("Loopback"))
    }
}
