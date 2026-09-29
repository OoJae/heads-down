plugins {
    alias(libs.plugins.headsdown.android.library)
}

android {
    namespace = "xyz.headsdown.feature.oemkeepalive"
}

dependencies {
    implementation(libs.androidx.core.ktx)
}
