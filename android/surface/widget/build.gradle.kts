plugins {
    alias(libs.plugins.headsdown.android.library)
    // Glance widgets are @Composable: the Compose compiler (and Material 3 colour schemes).
    alias(libs.plugins.headsdown.android.compose)
}

android {
    namespace = "xyz.headsdown.surface.widget"
    testOptions.unitTests.isIncludeAndroidResources = true
}

dependencies {
    // The palette (HdArgb, and the colour resources the picker previews use).
    implementation(projects.core.design)
    api(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.androidx.glance.appwidget.testing)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
