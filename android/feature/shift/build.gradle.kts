import com.android.build.api.variant.HasHostTestsBuilder
import com.android.build.api.variant.HostTestBuilder

plugins {
    alias(libs.plugins.headsdown.android.library)
    alias(libs.plugins.headsdown.hilt)
}

android {
    namespace = "xyz.headsdown.feature.shift"
}

// The DEV-ONLY sensor lab lives in src/debug; src/release has a stub that reports it absent.
// AGP 9 runs unit tests for the debug build type only, so also run this module's release unit
// tests: src/testRelease proves the lab's classes are not compiled into release.
androidComponents {
    beforeVariants(selector().withBuildType("release")) { variant ->
        (variant as? HasHostTestsBuilder)?.hostTests?.get(HostTestBuilder.UNIT_TEST_TYPE)?.enable = true
    }
}

// Lint reads the generated (KSP / Hilt) sources of every variant, and `test` also builds this
// module's release unit tests. In a parallel `test lint` run, lintAnalyzeDebugUnitTest once read
// hilt_aggregated_deps while kspReleaseKotlin was rewriting them (FileNotFoundException). Lint
// analysis now always runs after any KSP task scheduled in the same build.
tasks.matching { it.name.startsWith("lintAnalyze") }.configureEach {
    mustRunAfter(tasks.matching { it.name.startsWith("ksp") && it.name.endsWith("Kotlin") })
}

dependencies {
    api(projects.core.keys)
    // Heartbeat JSON + crank uplink seam; the Board is read through core/chain decoders.
    implementation(projects.core.chain)
    implementation(projects.surface.notification)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.kotlinx.serialization.json)
}
