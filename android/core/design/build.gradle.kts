plugins {
    alias(libs.plugins.headsdown.android.library)
    alias(libs.plugins.headsdown.android.compose)
}

android {
    namespace = "xyz.headsdown.core.design"
    // Robolectric composes the components against the module's own fonts, colours and drawables.
    testOptions.unitTests.isIncludeAndroidResources = true
}

// SingleSourceTest and StillnessTest read the main sources and resources of the app, the reveal
// and the surfaces (no palette literal outside this module, no animation that never ends). Those
// files are inputs, so a change to any of them re-runs the tests.
tasks.withType<Test>().configureEach {
    inputs.files(
        fileTree(rootDir) {
            include("app/src/**/*.kt", "app/src/**/*.xml")
            include("feature/reveal/src/**/*.kt", "feature/reveal/src/**/*.xml")
            include("surface/*/src/**/*.kt", "surface/*/src/**/*.xml")
            include("core/design/FONTS.md")
            exclude("**/build/**")
        },
    )
        .withPropertyName("scannedSources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

dependencies {
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    // Registers ComponentActivity for createComposeRule in the unit-test manifest only.
    testImplementation(libs.androidx.compose.ui.test.manifest)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
