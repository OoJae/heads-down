plugins {
    alias(libs.plugins.headsdown.android.library)
    alias(libs.plugins.headsdown.android.compose)
    alias(libs.plugins.headsdown.hilt)
}

android {
    namespace = "xyz.headsdown.feature.reveal"
    // Robolectric runs the Compose UI tests against the merged manifest and resources.
    testOptions.unitTests.isIncludeAndroidResources = true
}

dependencies {
    implementation(projects.surface.notification)
    implementation(projects.surface.haptics)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    // Registers ComponentActivity for createComposeRule in the unit-test manifest only (never in
    // the library's debug variant, which would ship an exported test activity in debug APKs).
    testImplementation(libs.androidx.compose.ui.test.manifest)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
