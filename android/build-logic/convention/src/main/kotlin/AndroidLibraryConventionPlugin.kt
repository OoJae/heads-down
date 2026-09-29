import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import xyz.headsdown.buildlogic.HeadsDownJava
import xyz.headsdown.buildlogic.addUnitTestDependencies
import xyz.headsdown.buildlogic.int
import xyz.headsdown.buildlogic.libs

class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.library")

            extensions.configure<LibraryExtension> {
                compileSdk = libs.int("compileSdk")
                defaultConfig {
                    minSdk = libs.int("minSdk")
                    if (file("consumer-rules.pro").exists()) consumerProguardFiles("consumer-rules.pro")
                }
                compileOptions {
                    sourceCompatibility = HeadsDownJava
                    targetCompatibility = HeadsDownJava
                }
                // Pure-Kotlin logic is unit-tested on the JVM; android.jar stubs return defaults
                // instead of throwing so an accidental framework call fails an assertion, not setup.
                testOptions.unitTests.isReturnDefaultValues = true
            }
            addUnitTestDependencies()
        }
    }
}
