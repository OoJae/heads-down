plugins {
    alias(libs.plugins.headsdown.android.library)
    alias(libs.plugins.headsdown.hilt)
}

android {
    namespace = "xyz.headsdown.feature.shift"
}

dependencies {
    api(projects.core.keys)
    // Heartbeat JSON + crank uplink seam; the Board is read through core/chain decoders.
    implementation(projects.core.chain)
    implementation(projects.surface.notification)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.kotlinx.serialization.json)
}
