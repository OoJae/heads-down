plugins {
    alias(libs.plugins.headsdown.android.library)
    alias(libs.plugins.headsdown.android.compose)
}

android {
    namespace = "xyz.headsdown.feature.reveal"
}

dependencies {
    implementation(projects.surface.notification)
    implementation(libs.androidx.core.ktx)
}
