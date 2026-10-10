plugins {
    alias(libs.plugins.headsdown.android.library)
}

android {
    namespace = "xyz.headsdown.surface.notification"
}

dependencies {
    // The accent colour only (HdArgb): nothing of Compose is used here.
    implementation(projects.core.design)
    // androidx.core >= 1.17 provides NotificationCompat.ProgressStyle + setRequestPromotedOngoing.
    api(libs.androidx.core.ktx)
}
