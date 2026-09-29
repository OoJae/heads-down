plugins {
    alias(libs.plugins.headsdown.android.library)
}

android {
    namespace = "xyz.headsdown.surface.notification"
}

dependencies {
    // androidx.core >= 1.17 provides NotificationCompat.ProgressStyle + setRequestPromotedOngoing.
    api(libs.androidx.core.ktx)
}
