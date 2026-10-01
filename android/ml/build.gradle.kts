plugins {
    alias(libs.plugins.headsdown.android.library)
}

android {
    namespace = "xyz.headsdown.ml"
}

// Foreman: the on-device pickup classifier and Shift Planner. Pure Kotlin, JVM unit-tested.
// Model and parameter files ship as assets (src/main/assets/foreman) and are produced by
// ml/foreman (python). The vectors in src/test/resources/foreman pin this code to the Python
// reference at 1e-6. No sensor or usage data leaves the phone: nothing here does any I/O
// besides reading those assets.

// Not in the version catalog yet (fold into gradle/libs.versions.toml when it is next touched).
// Pinned on purpose: 1.4.1 is the last litert-api without native code. The 2.x API AAR bundles
// its own JNI libraries for three ABIs, so lint's "newer version available" warning stays.
val litertApi = "1.4.1"

dependencies {
    // Model / parameter JSON (tree API only, no compiler plugin; the app already ships it).
    implementation(libs.kotlinx.serialization.json)
    // LiteRT's Java API only (23 KB, no native code). The optional .tflite backend compiles
    // against it; the runtime itself comes from the app (com.google.ai.edge.litert:litert) or
    // Google Play services. Without a runtime the classifier uses the pure-Kotlin path.
    implementation("com.google.ai.edge.litert:litert-api:$litertApi")
}
