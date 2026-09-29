plugins {
    alias(libs.plugins.headsdown.android.library)
}

android {
    namespace = "xyz.headsdown.core.keys"
}

// Deliberately dependency-free: the P-256 codec and heartbeat wire format are pure Kotlin/JCA
// so they run identically on-device and in JVM unit tests.
