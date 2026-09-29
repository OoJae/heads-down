plugins {
    alias(libs.plugins.headsdown.android.library)
}

android {
    namespace = "xyz.headsdown.core.wallet"
}

dependencies {
    api(libs.mwa.clientlib.ktx) {
        // clientlib-ktx (2.0.3 and still 2.2.0) leaks its test stack (junit-ktx, and in 2.0.3
        // mockito) as *runtime* dependencies. Exclude them so test libraries never ship in the APK.
        exclude(group = "org.mockito")
        exclude(group = "org.mockito.kotlin")
        exclude(group = "androidx.test.ext")
        exclude(group = "androidx.test")
    }
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.kotlinx.coroutines.android)
}
