import com.android.build.api.variant.HasHostTests
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
    // The Foreman wiring is tested against the files :ml ships and is pinned to: its vectors
    // (recorded synthetic windows, planner logs) and the model assets themselves. They are read
    // from :ml's own folders as test resources, so there is no second copy to drift, and a
    // change to any of them re-runs these tests.
    onVariants { variant ->
        (variant as? HasHostTests)?.hostTests?.get(HostTestBuilder.UNIT_TEST_TYPE)?.sources?.resources?.let { resources ->
            resources.addStaticSourceDirectory("../../ml/src/test/resources")
            resources.addStaticSourceDirectory("../../ml/src/main/assets")
        }
    }
}

// Lint reads the generated (KSP / Hilt) sources of every variant, and `test` also builds this
// module's release unit tests. In a parallel `test lint` run, lintAnalyzeDebugUnitTest once read
// hilt_aggregated_deps while kspReleaseKotlin was rewriting them (FileNotFoundException). Lint
// analysis now always runs after any KSP task scheduled in the same build.
tasks.matching { it.name.startsWith("lintAnalyze") }.configureEach {
    mustRunAfter(tasks.matching { it.name.startsWith("ksp") && it.name.endsWith("Kotlin") })
}

// The sensor-path tests count the bytes a thread allocates. HotSpot's escape analysis would
// optimize a short-lived per-sample object away and hide it; ART does far less of that, so the
// tests run without it and see every allocation the bytecode asks for.
tasks.withType<Test>().configureEach {
    jvmArgs("-XX:+IgnoreUnrecognizedVMOptions", "-XX:-DoEscapeAnalysis")
    // PlannerLogTest reads every FileProvider path file in the app to prove none of them can
    // serve the planner log. They are inputs, so a new or changed one re-runs the test.
    inputs.files(fileTree(rootDir) { include("**/src/*/res/xml/*.xml"); exclude("**/build/**") })
        .withPropertyName("fileProviderPathFiles")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

dependencies {
    api(projects.core.keys)
    // Heartbeat JSON + crank uplink seam; the Board is read through core/chain decoders.
    implementation(projects.core.chain)
    // Foreman: the pickup classifier, the Shift Planner and the bounds on both. `implementation`
    // on purpose: no :ml type appears in this module's API, so the app does not need :ml on its
    // classpath to show a plan (see foreman/TonightPlan.kt).
    implementation(projects.ml)
    implementation(projects.surface.notification)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.kotlinx.serialization.json)
}
