plugins {
    `kotlin-dsl`
}

group = "xyz.headsdown.buildlogic"

dependencies {
    // compileOnly: the root build puts the real plugins on the classpath (see ../../build.gradle.kts).
    compileOnly(libs.android.gradlePlugin)
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = libs.plugins.headsdown.android.application.get().pluginId
            implementationClass = "AndroidApplicationConventionPlugin"
        }
        register("androidLibrary") {
            id = libs.plugins.headsdown.android.library.get().pluginId
            implementationClass = "AndroidLibraryConventionPlugin"
        }
        register("androidCompose") {
            id = libs.plugins.headsdown.android.compose.get().pluginId
            implementationClass = "AndroidComposeConventionPlugin"
        }
        register("hilt") {
            id = libs.plugins.headsdown.hilt.get().pluginId
            implementationClass = "HiltConventionPlugin"
        }
    }
}
