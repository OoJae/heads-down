plugins {
    alias(libs.plugins.headsdown.android.library)
    // Glance widgets are @Composable: the Compose compiler (and Material 3 colour schemes).
    alias(libs.plugins.headsdown.android.compose)
}

android {
    namespace = "xyz.headsdown.surface.widget"
    testOptions.unitTests.isIncludeAndroidResources = true
}

// Versions not in the catalog yet (fold into gradle/libs.versions.toml when it is next touched).
val glance = "1.2.0"
val robolectric = "4.17"
val androidxTestCore = "1.7.0"

dependencies {
    api("androidx.glance:glance-appwidget:$glance")
    implementation("androidx.glance:glance-material3:$glance")
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation("androidx.glance:glance-appwidget-testing:$glance")
    testImplementation("org.robolectric:robolectric:$robolectric")
    testImplementation("androidx.test:core:$androidxTestCore")
}
