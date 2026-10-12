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
    fun `xiaomi permission editor is asked for our package and always ends with app details`() {
        val c = KeepAliveIntents.candidates(KeepAliveStep.MORNING_PAGE_PERMISSIONS, xiaomi, pkg, "Heads Down")
        assertEquals(3, c.size)
        // Both tries carry the action and name the app; the first also names the activity.
        for (spec in c.dropLast(1)) {
            assertEquals("miui.intent.action.APP_PERM_EDITOR", spec.action)
            assertEquals(mapOf("extra_pkgname" to pkg), spec.extras)
        }
        assertEquals("com.miui.securitycenter", c[0].packageName)
        assertEquals("com.miui.permcenter.permissions.PermissionsEditorActivity", c[0].className)
        assertEquals(null, c[1].packageName)
        assertEquals(null, c[1].className)
        assertTrue(c.last().isAppDetailsFallback)
        assertEquals("package:$pkg", c.last().dataUri)
    }

    @Test
    fun `non-xiaomi devices only get the safe fallback for xiaomi-only steps`() {
        for (step in listOf(KeepAliveStep.AUTOSTART, KeepAliveStep.BATTERY_NO_RESTRICTIONS, KeepAliveStep.MORNING_PAGE_PERMISSIONS)) {
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
    fun `guide covers autostart, battery, recents lock and the morning page's permissions on xiaomi only`() {
        val x = KeepAliveGuide.steps(xiaomi).mapNotNull { it.action }
        assertEquals(
            listOf(
                KeepAliveStep.AUTOSTART,
                KeepAliveStep.BATTERY_NO_RESTRICTIONS,
                KeepAliveStep.MORNING_PAGE_PERMISSIONS,
                KeepAliveStep.IGNORE_BATTERY_OPTIMIZATIONS,
            ),
            x,
        )
        assertTrue(KeepAliveGuide.steps(xiaomi).any { it.title.contains("Recents") })
        assertEquals(listOf(KeepAliveStep.IGNORE_BATTERY_OPTIMIZATIONS), KeepAliveGuide.steps(pixel).mapNotNull { it.action })
        assertTrue(KeepAliveGuide.BATTERY_JUSTIFICATION.contains("nothing is lost"))
        // ORE rounds are about a minute now: the step gives no count of seconds.
        assertTrue(KeepAliveGuide.BATTERY_JUSTIFICATION.contains("one heartbeat per ORE round (about once a minute)"))
        assertFalse(KeepAliveGuide.BATTERY_JUSTIFICATION.contains("78"))
    }

    @Test
    fun `a xiaomi owner is told the two permissions the morning page needs, by their names and where they are`() {
        val step = KeepAliveGuide.steps(xiaomi).single { it.action == KeepAliveStep.MORNING_PAGE_PERMISSIONS }
        assertEquals("Let the morning page open", step.title)
        assertEquals(
            "Settings > Apps > Heads Down > Other permissions: turn on \"Show on Lock screen\" and " +
                "\"Display pop-up windows while running in the background\". Without both, HyperOS is likely to " +
                "block the full-screen page that opens after your alarm.",
            step.detail,
        )
        // The skin is named as the Autostart step names it.
        val miui = KeepAliveGuide.steps(OemProfile(OemFamily.XIAOMI, XiaomiSkin.MIUI)).single { it.action == KeepAliveStep.MORNING_PAGE_PERMISSIONS }
        assertTrue("MIUI is likely to block" in miui.detail)
        // No other maker has these switches: nothing is said of them there.
        for (other in listOf(pixel, OemProfile(OemFamily.SAMSUNG))) {
            assertFalse(KeepAliveGuide.steps(other).any { "Other permissions" in it.detail || "morning page" in it.title })
        }
    }

    @Test
    fun `every maker's guide ends with how to keep the night quiet`() {
        assertEquals(
            "A call or an alert that lights the screen for more than ten seconds ends the shift: switch on " +
                "Do Not Disturb, or airplane mode with Wi-Fi on, before you lay the phone down.",
            KeepAliveGuide.QUIET_NIGHT,
        )
        for (profile in listOf(xiaomi, pixel) + OemFamily.entries.map { OemProfile(it) }) {
            val last = KeepAliveGuide.steps(profile).last()
            assertEquals(GuideStep("Keep the night quiet", KeepAliveGuide.QUIET_NIGHT, null), last)
            assertEquals(1, KeepAliveGuide.steps(profile).count { it.detail == KeepAliveGuide.QUIET_NIGHT })
        }
    }

    @Test
    fun `the guide never says continue, and never that any of it was tried on a phone`() {
        val profiles = listOf(xiaomi, pixel, OemProfile(OemFamily.XIAOMI, XiaomiSkin.MIUI), OemProfile(OemFamily.XIAOMI, XiaomiSkin.UNKNOWN))
        for (text in profiles.flatMap { KeepAliveGuide.steps(it) }.flatMap { listOf(it.title, it.detail) }) {
            for (word in listOf("continue", "tested", "verified", "confirmed", "we checked")) {
                assertFalse("\"$word\" in: $text", word in text.lowercase())
            }
        }
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
