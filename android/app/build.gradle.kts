plugins {
    alias(libs.plugins.headsdown.android.application)
    alias(libs.plugins.headsdown.android.compose)
    alias(libs.plugins.headsdown.hilt)
}

android {
    namespace = "xyz.headsdown"

    defaultConfig {
        applicationId = "xyz.headsdown"
        versionCode = 1
        versionName = "0.1.0"
        // Public, non-secret configuration only. RPC/Helius keys never ship in the APK:
        // they stay behind the team proxy (see docs/SPEC.md, off-chain services).
        buildConfigField("String", "SOLANA_CHAIN", "\"solana:devnet\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    lint {
        // Belt and braces with the R8 -assumenosideeffects rule: any unconditional
        // android.util.Log call in our sources is a lint error.
        enable += "LogConditional"
        error += "LogConditional"
        checkReleaseBuilds = true
        abortOnError = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(projects.core.keys)
    implementation(projects.core.wallet)
    implementation(projects.feature.shift)
    implementation(projects.feature.reveal)
    implementation(projects.feature.oemKeepalive)
    implementation(projects.surface.tile)
    implementation(projects.surface.notification)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
}
