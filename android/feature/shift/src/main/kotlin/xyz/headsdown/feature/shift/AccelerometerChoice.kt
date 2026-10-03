package xyz.headsdown.feature.shift

/**
 * Which accelerometer the shift service listens to, and how fast.
 *
 * 50 Hz is the rate the pickup classifier's feature spec resamples to and the sensor lab
 * records at; below about 20 Hz every motion window would be unusable, and so a PICKUP
 * (fail-closed). The face-down detector is time-based and works at any rate.
 *
 * A wake-up accelerometer keeps delivering while the CPU sleeps, so it is preferred, but only
 * if it can run at that rate. Otherwise the plain sensor is used and the shift's partial wake
 * lock keeps samples flowing.
 */
internal object AccelerometerChoice {
    /** 50 Hz. */
    const val SAMPLING_PERIOD_US = 20_000

    enum class Pick { WAKE_UP, DEFAULT, NONE }

    /**
     * @param wakeUpMinDelayUs `Sensor.getMinDelay()` of the wake-up accelerometer, null if there is none
     * @param defaultMinDelayUs the same for the default accelerometer
     */
    fun pick(wakeUpMinDelayUs: Int?, defaultMinDelayUs: Int?): Pick = when {
        fastEnough(wakeUpMinDelayUs) -> Pick.WAKE_UP
        defaultMinDelayUs != null && (fastEnough(defaultMinDelayUs) || wakeUpMinDelayUs == null) -> Pick.DEFAULT
        // Neither reaches 50 Hz: a slow stream that survives CPU sleep beats one that does not.
        wakeUpMinDelayUs != null -> Pick.WAKE_UP
        else -> Pick.NONE
    }

    /** A streaming sensor (min delay 0 means on-change only) whose shortest period is at most ours. */
    private fun fastEnough(minDelayUs: Int?): Boolean = minDelayUs != null && minDelayUs in 1..SAMPLING_PERIOD_US
}
