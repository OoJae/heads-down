plugins {
    alias(libs.plugins.headsdown.android.library)
    alias(libs.plugins.headsdown.hilt)
}

android {
    namespace = "xyz.headsdown.surface.tile"
}

dependencies {
    implementation(projects.feature.shift)
    implementation(projects.core.wallet)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
}
