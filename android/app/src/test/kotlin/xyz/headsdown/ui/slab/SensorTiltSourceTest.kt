package xyz.headsdown.ui.slab

import android.app.Application
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.SensorEventBuilder
import org.robolectric.shadows.ShadowSensor

/** [SensorTiltSource] against Robolectric's sensor manager: what it registers, and when it lets go. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SensorTiltSourceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager = context.getSystemService(SensorManager::class.java)!!
    private val shadow = shadowOf(manager)
    private val accelerometer: Sensor = ShadowSensor.newInstance(Sensor.TYPE_ACCELEROMETER)

    private class Recorder : TiltListener {
        val samples = ArrayList<List<Float>>()
        override fun onSample(x: Float, y: Float, z: Float, timestampNanos: Long) {
            samples += listOf(x, y, z)
        }
    }

    @Test
    fun `registers the accelerometer while a listener is started and unregisters with the last`() {
        shadow.addSensor(accelerometer)
        val source = SensorTiltSource(context)
        assertTrue("constructing registers nothing", shadow.listeners.isEmpty())

        val hero = Recorder()
        val glow = Recorder()
        source.start(hero)
        assertEquals(1, shadow.listeners.size)
        // The glow is a second client of the source, not a second registration.
        source.start(glow)
        source.start(glow)
        assertEquals(1, shadow.listeners.size)

        shadow.sendSensorEventToListeners(
            SensorEventBuilder.newBuilder().setSensor(accelerometer).setValues(floatArrayOf(1f, 2f, 9f)).setTimestamp(123L).build(),
        )
        assertEquals(listOf(listOf(1f, 2f, 9f)), hero.samples)
        assertEquals(listOf(listOf(1f, 2f, 9f)), glow.samples)

        source.stop(hero)
        assertEquals("one listener is left", 1, shadow.listeners.size)
        source.stop(glow)
        assertTrue("the last listener took the registration with it", shadow.listeners.isEmpty())
        // Stopping twice, or something never started, is harmless.
        source.stop(glow)
        source.stop(Recorder())
        assertTrue(shadow.listeners.isEmpty())

        // And it can start again.
        source.start(hero)
        assertEquals(1, shadow.listeners.size)
        source.stop(hero)
        assertTrue(shadow.listeners.isEmpty())
    }

    @Test
    fun `without an accelerometer it registers nothing and stays quiet`() {
        val source = SensorTiltSource(context)
        val recorder = Recorder()
        source.start(recorder)
        assertTrue(shadow.listeners.isEmpty())
        source.stop(recorder)
        assertTrue(recorder.samples.isEmpty())
    }

    @Test
    fun `asks for 50 Hz with no batching`() {
        assertEquals(20_000, SensorTiltSource.SAMPLING_PERIOD_US)
        assertEquals(0, SensorTiltSource.MAX_REPORT_LATENCY_US)
    }

    @Test
    fun `no tilt is no sensor`() {
        shadow.addSensor(accelerometer)
        val recorder = Recorder()
        NoTilt.start(recorder)
        assertTrue(shadow.listeners.isEmpty())
        NoTilt.stop(recorder)
    }
}
