package xyz.headsdown.feature.shift

import android.hardware.Sensor
import android.hardware.SensorManager

enum class ProximityKind { NONE, VIRTUAL, PHYSICAL }

/** Which motion sensors this phone really has. Only the accelerometer is required. */
data class SensorProfile(
    val hasAccelerometer: Boolean,
    val hasWakeUpAccelerometer: Boolean,
    val hasGyroscope: Boolean,
    val proximity: ProximityKind,
    val hasSignificantMotion: Boolean,
) {
    /** Proximity may corroborate face-down only when it is a real IR/ToF sensor. */
    val proximityUsable: Boolean get() = proximity == ProximityKind.PHYSICAL
}

object ProximityClassifier {
    // Xiaomi/Redmi ship Elliptic Labs' ultrasound/software "AI Virtual Smart Sensor" instead of
    // an IR emitter; others label software proximity as virtual/pseudo. Such sensors infer
    // proximity from audio/touch/screen state and are unreliable face-down on a nightstand.
    private val VIRTUAL_MARKERS = listOf("virtual", "elliptic", "pseudo", "fake", "ultrasound", "software")

    fun classify(name: String?, vendor: String?): ProximityKind {
        if (name == null && vendor == null) return ProximityKind.NONE
        val haystack = "${name.orEmpty()} ${vendor.orEmpty()}".lowercase()
        return if (VIRTUAL_MARKERS.any { it in haystack }) ProximityKind.VIRTUAL else ProximityKind.PHYSICAL
    }
}

object SensorInventory {
    fun profile(sm: SensorManager): SensorProfile {
        val proximity = sm.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        return SensorProfile(
            hasAccelerometer = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null,
            hasWakeUpAccelerometer = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER, true) != null,
            hasGyroscope = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null,
            proximity = if (proximity == null) ProximityKind.NONE
            else ProximityClassifier.classify(proximity.name, proximity.vendor),
            hasSignificantMotion = sm.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION) != null,
        )
    }
}
