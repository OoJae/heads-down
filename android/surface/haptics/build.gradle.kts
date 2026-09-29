plugins {
    alias(libs.plugins.headsdown.android.library)
}

android {
    namespace = "xyz.headsdown.surface.haptics"
    // Robolectric loads res/raw (the thunk) and the merged manifest.
    testOptions.unitTests.isIncludeAndroidResources = true
}

// Test-only versions (fold into gradle/libs.versions.toml when the catalog is next touched).
val robolectric = "4.17"
val androidxTestCore = "1.7.0"

dependencies {
    implementation(libs.androidx.core.ktx)

    testImplementation("org.robolectric:robolectric:$robolectric")
    testImplementation("androidx.test:core:$androidxTestCore")
}
