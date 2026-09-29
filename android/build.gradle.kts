// Root build: only puts plugins on the classpath so convention plugins in build-logic
// (compiled against them as compileOnly) resolve the same classes at runtime.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    // Pins the Kotlin Gradle plugin used by AGP 9's built-in Kotlin support.
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}
