plugins {
    alias(libs.plugins.headsdown.android.library)
    alias(libs.plugins.headsdown.hilt)
}

android {
    namespace = "xyz.headsdown.feature.shift"
}

dependencies {
    api(projects.core.keys)
    implementation(projects.surface.notification)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.kotlinx.coroutines.android)
}
