plugins {
    alias(libs.plugins.headsdown.android.library)
}

android {
    namespace = "xyz.headsdown.core.keys"
}

// Deliberately dependency-free at runtime: the P-256 codec and the signed-message formats are
// pure Kotlin/JCA so they run identically on-device and in JVM unit tests.
dependencies {
    // Golden vectors: RFC 6979 deterministic ECDSA (BouncyCastle) and JSON reading.
    testImplementation(libs.bouncycastle.bcprov)
    testImplementation(libs.kotlinx.serialization.json)
}
