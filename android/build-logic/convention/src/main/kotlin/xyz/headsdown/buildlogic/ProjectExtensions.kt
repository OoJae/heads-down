package xyz.headsdown.buildlogic

import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.getByType

internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

internal fun VersionCatalog.lib(alias: String): Provider<MinimalExternalModuleDependency> =
    findLibrary(alias).orElseThrow { IllegalStateException("No library '$alias' in libs.versions.toml") }

internal fun VersionCatalog.int(alias: String): Int =
    findVersion(alias).orElseThrow { IllegalStateException("No version '$alias' in libs.versions.toml") }
        .requiredVersion.toInt()

/** Java 17 bytecode everywhere; AGP 9 built-in Kotlin derives its jvmTarget from this. */
internal val HeadsDownJava: JavaVersion = JavaVersion.VERSION_17

/** Plain JVM unit tests (pure-Kotlin logic) for every module. */
internal fun Project.addUnitTestDependencies() {
    dependencies {
        add("testImplementation", libs.lib("junit"))
        add("testImplementation", libs.lib("kotlinx-coroutines-test"))
    }
}
