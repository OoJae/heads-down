import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import xyz.headsdown.buildlogic.HeadsDownJava
import xyz.headsdown.buildlogic.addUnitTestDependencies
import xyz.headsdown.buildlogic.int
import xyz.headsdown.buildlogic.libs

class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.application")

            extensions.configure<ApplicationExtension> {
                compileSdk = libs.int("compileSdk")
                defaultConfig {
                    minSdk = libs.int("minSdk")
                    targetSdk = libs.int("targetSdk")
                }
                compileOptions {
                    sourceCompatibility = HeadsDownJava
                    targetCompatibility = HeadsDownJava
                }
                testOptions.unitTests.isReturnDefaultValues = true
            }
            addUnitTestDependencies()
        }
    }
}
