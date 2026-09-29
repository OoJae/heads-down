plugins {
    alias(libs.plugins.headsdown.android.library)
}

android {
    namespace = "xyz.headsdown.core.wallet"
}

dependencies {
    api(libs.mwa.clientlib.ktx) {
        // clientlib-ktx 2.0.3's POM leaks its test stack (junit-ktx, mockito) as *runtime*
        // dependencies. Exclude them so test libraries never ship inside the APK.
        exclude(group = "org.mockito")
        exclude(group = "org.mockito.kotlin")
        exclude(group = "androidx.test.ext")
        exclude(group = "androidx.test")
    }
    implementation(libs.androidx.activity.ktx)
    implementation(libs.kotlinx.coroutines.android)
}
