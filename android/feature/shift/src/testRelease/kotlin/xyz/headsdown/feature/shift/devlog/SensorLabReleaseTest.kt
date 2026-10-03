package xyz.headsdown.feature.shift.devlog

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

/** Runs against the RELEASE variant: the sensor lab must not exist there at all. */
class SensorLabReleaseTest {

    @Test
    fun `release reports the lab as unavailable`() {
        assertFalse(SensorLab.AVAILABLE)
        // The stub never touches the context.
        assertNull(SensorLab.intent(android.content.ContextWrapper(null)))
    }

    @Test
    fun `no recorder, service, screen, provider or benchmark class is compiled into release`() {
        listOf(
            "SensorLabService", "SensorLabActivity", "SensorLabExportProvider", "SensorLabStore",
            "MotionTrigger", "WindowRecorder", "SensorLogCsv", "PickupBenchmark",
        ).forEach { name ->
            try {
                Class.forName("xyz.headsdown.feature.shift.devlog.$name")
                fail("$name exists in the release variant")
            } catch (_: ClassNotFoundException) {
                // expected
            }
        }
    }
}
