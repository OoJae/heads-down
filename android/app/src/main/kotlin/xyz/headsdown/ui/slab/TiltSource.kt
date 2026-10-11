package xyz.headsdown.ui.slab

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Surface

/** Receives raw gravity samples on the main thread. */
fun interface TiltListener {
    /**
     * One sample in the DISPLAY's axes (x right, y up, z out of the screen toward the viewer),
     * m/s^2, with the sensor's own timestamp. A phone lying face-up reads about (0, 0, 9.81).
     */
    fun onSample(x: Float, y: Float, z: Float, timestampNanos: Long)
}

/**
 * Where the interface gets gravity from. UI ONLY: nothing here is shared with the shift service,
 * which keeps its own listener on its own thread.
 *
 * Each listener is a separate client: the hero and the lay-down glow both start one, and the
 * sensor runs while at least one is started.
 */
interface TiltSource {
    fun start(listener: TiltListener)
    fun stop(listener: TiltListener)
}

/** No sensor. The default everywhere: a slab with this source stays at rest. */
object NoTilt : TiltSource {
    override fun start(listener: TiltListener) = Unit
    override fun stop(listener: TiltListener) = Unit
}

/**
 * Gravity from the accelerometer, or from the fused gravity sensor on a phone that has a
 * gyroscope (the Redmi 14C has none). Non-wake-up, 50 Hz, no batching, delivered on the main
 * looper. Create it in an Activity and provide it as [LocalTiltSource]; call it from the main
 * thread only.
 *
 * A second listener on a sensor is a separate client to the sensor service: registering here
 * changes neither the rate nor the batching of the shift service's own listener.
 */
class SensorTiltSource(context: Context, private val preferGravity: Boolean = true) : TiltSource {
    private val app = context.applicationContext
    private val listeners = ArrayList<TiltListener>(2)
    private var registered = false

    /** Read when the sensor starts: the activity is locked to portrait, so it does not change under us. */
    private var rotation = Surface.ROTATION_0

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.values.size < 3) return
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]
            val sx: Float
            val sy: Float
            when (rotation) {
                Surface.ROTATION_90 -> { sx = -y; sy = x }
                Surface.ROTATION_180 -> { sx = -x; sy = -y }
                Surface.ROTATION_270 -> { sx = y; sy = -x }
                else -> { sx = x; sy = y }
            }
            // By index: no iterator, and a listener may stop itself while it is being called.
            var i = 0
            while (i < listeners.size) {
                listeners[i].onSample(sx, sy, z, event.timestamp)
                i++
            }
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
    }

    override fun start(listener: TiltListener) {
        if (listener in listeners) return
        listeners += listener
        if (!registered) register()
    }

    override fun stop(listener: TiltListener) {
        listeners -= listener
        if (listeners.isEmpty() && registered) {
            app.getSystemService(SensorManager::class.java)?.unregisterListener(sensorListener)
            registered = false
        }
    }

    private fun register() {
        val manager = app.getSystemService(SensorManager::class.java) ?: return
        val sensor = pick(manager) ?: return // no accelerometer: the slab stays at rest
        rotation = app.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)?.rotation
            ?: Surface.ROTATION_0
        registered = manager.registerListener(
            sensorListener, sensor, SAMPLING_PERIOD_US, MAX_REPORT_LATENCY_US, Handler(Looper.getMainLooper()),
        )
    }

    private fun pick(manager: SensorManager): Sensor? {
        val accelerometer = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER, false)
            ?: manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (!preferGravity || manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) == null) return accelerometer
        // The fused gravity vector is only better than a filtered accelerometer with a gyroscope behind it.
        return manager.getDefaultSensor(Sensor.TYPE_GRAVITY, false) ?: accelerometer
    }

    companion object {
        /** 50 Hz, the rate the sc7a20 guarantees and the one the shift service already asks for. */
        const val SAMPLING_PERIOD_US = 20_000

        /** No batching: a sample late by a batch is a slab that lags the hand. */
        const val MAX_REPORT_LATENCY_US = 0
    }
}
