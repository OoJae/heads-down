package xyz.headsdown.feature.oemkeepalive

import android.app.ApplicationExitInfo
import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OemDetectorTest {
    private fun props(vararg pairs: Pair<String, String>): (String) -> String? = pairs.toMap()::get

    @Test
    fun `redmi 14C on HyperOS`() {
        val p = OemDetector.detect("Xiaomi", "Redmi", "OS1.0.8.0.UGPMIXM", props("ro.mi.os.version.name" to "OS1.0"))
        assertEquals(OemProfile(OemFamily.XIAOMI, XiaomiSkin.HYPEROS, "OS1.0"), p)
        assertTrue(p.needsAutostartStep)
    }

    @Test
    fun `MIUI via property, POCO brand, and incremental fallbacks`() {
        assertEquals(XiaomiSkin.MIUI, OemDetector.detect("Xiaomi", "POCO", "", props("ro.miui.ui.version.name" to "V140")).xiaomiSkin)
        assertEquals(XiaomiSkin.HYPEROS, OemDetector.detect("xiaomi", "redmi", "OS2.0.1.0.VNQMIXM", props()).xiaomiSkin)
        assertEquals(XiaomiSkin.MIUI, OemDetector.detect("Xiaomi", "Xiaomi", "V14.0.3.0.TKQMIXM", props()).xiaomiSkin)
        assertEquals(XiaomiSkin.UNKNOWN, OemDetector.detect("Xiaomi", "Xiaomi", "eng.build", props()).xiaomiSkin)
        // Blank properties are ignored rather than trusted.
        assertEquals(XiaomiSkin.MIUI, OemDetector.detect("Xiaomi", "Redmi", "V13", props("ro.mi.os.version.name" to " ")).xiaomiSkin)
    }

    @Test
    fun `other OEMs`() {
        assertEquals(OemFamily.SAMSUNG, OemDetector.detect("samsung", "samsung", "", props()).family)
        assertEquals(OemFamily.OPPO_REALME_ONEPLUS, OemDetector.detect("OnePlus", "OnePlus", "", props()).family)
        val seeker = OemDetector.detect("Solana Mobile", "Solana", "", props())
        assertEquals(OemFamily.OTHER, seeker.family)
        assertFalse(seeker.needsAutostartStep)
    }
}

class KeepAliveIntentsTest {
    private val xiaomi = OemProfile(OemFamily.XIAOMI, XiaomiSkin.HYPEROS)
    private val pixel = OemProfile(OemFamily.OTHER)
    private val pkg = "xyz.headsdown"

    @Test
    fun `xiaomi autostart tries the security center first and always ends with app details`() {
        val c = KeepAliveIntents.candidates(KeepAliveStep.AUTOSTART, xiaomi, pkg, "Heads Down")
        assertEquals("com.miui.securitycenter", c.first().packageName)
        assertEquals("com.miui.permcenter.autostart.AutoStartManagementActivity", c.first().className)
        assertTrue(c.last().isAppDetailsFallback)
        assertEquals("package:$pkg", c.last().dataUri)
    }

    @Test
    fun `xiaomi battery page receives our package and label`() {
        val c = KeepAliveIntents.candidates(KeepAliveStep.BATTERY_NO_RESTRICTIONS, xiaomi, pkg, "Heads Down")
        assertEquals("com.miui.powerkeeper.ui.HiddenAppsConfigActivity", c.first().className)
        assertEquals(mapOf("package_name" to pkg, "package_label" to "Heads Down"), c.first().extras)
        assertTrue(c.last().isAppDetailsFallback)
    }

    @Test
    fun `non-xiaomi devices only get the safe fallback for xiaomi-only steps`() {
        for (step in listOf(KeepAliveStep.AUTOSTART, KeepAliveStep.BATTERY_NO_RESTRICTIONS)) {
            val c = KeepAliveIntents.candidates(step, pixel, pkg, "Heads Down")
            assertEquals(1, c.size)
            assertTrue(c.single().isAppDetailsFallback)
        }
    }

    @Test
    fun `battery optimization request targets our package`() {
        val c = KeepAliveIntents.candidates(KeepAliveStep.IGNORE_BATTERY_OPTIMIZATIONS, pixel, pkg, "Heads Down")
        assertEquals(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, c[0].action)
        assertEquals("package:$pkg", c[0].dataUri)
        assertEquals(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS, c[1].action)
        assertTrue(c.last().isAppDetailsFallback)
    }

    @Test
    fun `guide covers autostart, battery and recents lock on xiaomi only`() {
        val x = KeepAliveGuide.steps(xiaomi).mapNotNull { it.action }
        assertEquals(
            listOf(KeepAliveStep.AUTOSTART, KeepAliveStep.BATTERY_NO_RESTRICTIONS, KeepAliveStep.IGNORE_BATTERY_OPTIMIZATIONS),
            x,
        )
        assertTrue(KeepAliveGuide.steps(xiaomi).any { it.title.contains("Recents") })
        assertEquals(listOf(KeepAliveStep.IGNORE_BATTERY_OPTIMIZATIONS), KeepAliveGuide.steps(pixel).mapNotNull { it.action })
        assertTrue(KeepAliveGuide.BATTERY_JUSTIFICATION.contains("nothing is lost"))
    }
}

class ShiftHealthCheckTest {
    private val hour = 60 * 60 * 1000L
    private val now = 100 * hour
    private val armed = now - 10 * hour
    private fun shift(ended: Long? = null, lastHb: Long? = armed + 3 * hour) =
        LastShift(armed, lastHb, ended, if (ended != null) "ended" else null, darkRounds = 140)

    @Test
    fun `no shift or an old one`() {
        assertEquals(ShiftHealth.NoRecentShift, ShiftHealthCheck.evaluate(null, emptyList(), false, now))
        val old = LastShift(now - 48 * hour, null, null, null, 0)
        assertEquals(ShiftHealth.NoRecentShift, ShiftHealthCheck.evaluate(old, emptyList(), false, now))
    }

    @Test
    fun `running and normally ended shifts are healthy`() {
        assertEquals(ShiftHealth.StillRunning, ShiftHealthCheck.evaluate(shift(), emptyList(), true, now))
        assertEquals(ShiftHealth.EndedNormally("ended"), ShiftHealthCheck.evaluate(shift(ended = now - hour), emptyList(), false, now))
    }

    @Test
    fun `killed by the OS last night`() {
        val killedAt = armed + 3 * hour + 5 * 60_000
        val exits = listOf(
            ProcessExit(armed - hour, ExitCause.USER_STOPPED, "before the shift"),
            ProcessExit(killedAt, ExitCause.KILLED_BY_SYSTEM_OR_OEM, "PowerKeeper"),
            ProcessExit(killedAt + hour, ExitCause.OTHER, "later"),
        )
        assertEquals(
            ShiftHealth.KilledByOs(killedAt, ExitCause.KILLED_BY_SYSTEM_OR_OEM, 140),
            ShiftHealthCheck.evaluate(shift(), exits, false, now),
        )
    }

    @Test
    fun `no exit record falls back to the last heartbeat`() {
        assertEquals(ShiftHealth.DiedWithoutRecord(armed + 3 * hour, 140), ShiftHealthCheck.evaluate(shift(), emptyList(), false, now))
        assertEquals(ShiftHealth.DiedWithoutRecord(armed, 140), ShiftHealthCheck.evaluate(shift(lastHb = null), emptyList(), false, now))
    }

    @Test
    fun `exit reasons map to causes`() {
        assertEquals(ExitCause.LOW_MEMORY, ShiftHealthCheck.causeOf(ApplicationExitInfo.REASON_LOW_MEMORY))
        assertEquals(ExitCause.KILLED_BY_SYSTEM_OR_OEM, ShiftHealthCheck.causeOf(ApplicationExitInfo.REASON_SIGNALED))
        assertEquals(ExitCause.KILLED_BY_SYSTEM_OR_OEM, ShiftHealthCheck.causeOf(ApplicationExitInfo.REASON_OTHER))
        assertEquals(ExitCause.CRASH, ShiftHealthCheck.causeOf(ApplicationExitInfo.REASON_CRASH_NATIVE))
        assertEquals(ExitCause.ANR, ShiftHealthCheck.causeOf(ApplicationExitInfo.REASON_ANR))
        assertEquals(ExitCause.USER_STOPPED, ShiftHealthCheck.causeOf(ApplicationExitInfo.REASON_USER_STOPPED))
        assertEquals(ExitCause.OTHER, ShiftHealthCheck.causeOf(ApplicationExitInfo.REASON_EXIT_SELF))
    }
}
