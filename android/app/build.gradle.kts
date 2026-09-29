import java.net.URI

plugins {
    alias(libs.plugins.headsdown.android.application)
    alias(libs.plugins.headsdown.android.compose)
    alias(libs.plugins.headsdown.hilt)
}

// Cluster and endpoints. Public, non-secret configuration only: provider API keys never ship
// in the APK (they stay behind the team proxy, THREAT_MODEL §8 "Network"). Override per build:
//   ./gradlew :app:assembleDebug -Pheadsdown.cluster=mainnet \
//       -Pheadsdown.rpcUrl=https://rpc.example.org -Pheadsdown.crankUrl=wss://crank.example.org/v1/heartbeats
// An empty crankUrl builds a local-only app (heartbeats stay on the device; no digs).
val cluster = (findProperty("headsdown.cluster") as String?) ?: "devnet"
require(cluster == "devnet" || cluster == "mainnet") { "headsdown.cluster must be devnet or mainnet" }
val defaultRpc = if (cluster == "mainnet") "https://api.mainnet-beta.solana.com" else "https://api.devnet.solana.com"
// Placeholder until the crank is deployed: it does not resolve, so the uplink backs off (fail-safe).
val defaultCrank = "wss://crank-$cluster.headsdown.xyz/v1/heartbeats"
val rpcUrl = (findProperty("headsdown.rpcUrl") as String?) ?: defaultRpc
val crankUrl = (findProperty("headsdown.crankUrl") as String?) ?: defaultCrank

fun requireEndpoint(name: String, url: String, scheme: String) {
    val uri = URI(url)
    require(uri.scheme == scheme && !uri.host.isNullOrEmpty()) { "$name must be a $scheme:// URL" }
    // A query string or user-info is where provider keys hide (?api-key=...): refuse to bake one in.
    require(uri.rawQuery == null && uri.rawUserInfo == null) { "$name must not carry a query string or credentials" }
}
requireEndpoint("headsdown.rpcUrl", rpcUrl, "https")
if (crankUrl.isNotEmpty()) requireEndpoint("headsdown.crankUrl", crankUrl, "wss")

android {
    namespace = "xyz.headsdown"

    defaultConfig {
        applicationId = "xyz.headsdown"
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "SOLANA_CHAIN", "\"solana:$cluster\"")
        buildConfigField("String", "SOLANA_RPC_URL", "\"$rpcUrl\"")
        buildConfigField("String", "CRANK_WS_URL", "\"$crankUrl\"")
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
    implementation(projects.core.chain)
    implementation(projects.feature.shift)
    implementation(projects.feature.reveal)
    implementation(projects.feature.oemKeepalive)
    implementation(projects.surface.tile)
    implementation(projects.surface.notification)
    implementation(projects.surface.haptics)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
}
