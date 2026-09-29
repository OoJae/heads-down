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

// LOCAL DEVSTACK (the `localdev` build type only): a validator and hd-crank on the laptop,
// reached from the phone through `adb reverse tcp:8899 tcp:8899` and `adb reverse tcp:8787 tcp:8787`.
//   ./gradlew :app:assembleLocaldev [-Pheadsdown.localdev.rpcUrl=http://127.0.0.1:8899]
//       [-Pheadsdown.localdev.crankUrl=ws://127.0.0.1:8787/ws]
// Debug and release never read these, and still refuse http:// and ws:// outright.
val localdevRpcUrl = (findProperty("headsdown.localdev.rpcUrl") as String?) ?: "http://127.0.0.1:8899"
val localdevCrankUrl = (findProperty("headsdown.localdev.crankUrl") as String?) ?: "ws://127.0.0.1:8787/ws"

/**
 * The build-time half of `xyz.headsdown.config.EndpointPolicy` (tested in app unit tests; the
 * app re-checks at startup). TLS schemes always; cleartext only with [allowLoopback] and only to
 * 127.0.0.1 / localhost; never user-info, a query string (`?api-key=`) or a fragment.
 */
fun requireEndpoint(name: String, url: String, secure: String, cleartext: String, allowLoopback: Boolean) {
    val uri = URI(url)
    val scheme = uri.scheme?.lowercase()
    val host = uri.host?.lowercase()
    require(!host.isNullOrEmpty()) { "$name must have a host" }
    require(uri.rawQuery == null && uri.rawUserInfo == null && uri.rawFragment == null) {
        "$name must not carry credentials, a query string or a fragment"
    }
    if (scheme == secure) return
    require(scheme == cleartext && allowLoopback) {
        "$name must be a $secure:// URL" + if (allowLoopback) "" else " ($cleartext:// only in the localdev build type)"
    }
    require(host == "127.0.0.1" || host == "localhost") { "$name: $cleartext:// is allowed only to 127.0.0.1 or localhost" }
}
requireEndpoint("headsdown.rpcUrl", rpcUrl, "https", "http", allowLoopback = false)
if (crankUrl.isNotEmpty()) requireEndpoint("headsdown.crankUrl", crankUrl, "wss", "ws", allowLoopback = false)
requireEndpoint("headsdown.localdev.rpcUrl", localdevRpcUrl, "https", "http", allowLoopback = true)
if (localdevCrankUrl.isNotEmpty()) {
    requireEndpoint("headsdown.localdev.crankUrl", localdevCrankUrl, "wss", "ws", allowLoopback = true)
}

android {
    namespace = "xyz.headsdown"

    defaultConfig {
        applicationId = "xyz.headsdown"
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "SOLANA_CHAIN", "\"solana:$cluster\"")
        buildConfigField("String", "SOLANA_RPC_URL", "\"$rpcUrl\"")
        buildConfigField("String", "CRANK_WS_URL", "\"$crankUrl\"")
        buildConfigField("boolean", "LOOPBACK_CLEARTEXT_ALLOWED", "false")
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
        // Debug + loopback cleartext for the local devstack (src/localdev: network security
        // config for 127.0.0.1/localhost and the loopback transports). Installs beside debug.
        create("localdev") {
            initWith(getByName("debug"))
            matchingFallbacks += listOf("debug")
            applicationIdSuffix = ".localdev"
            versionNameSuffix = "-localdev"
            buildConfigField("String", "SOLANA_RPC_URL", "\"$localdevRpcUrl\"")
            buildConfigField("String", "CRANK_WS_URL", "\"$localdevCrankUrl\"")
            buildConfigField("boolean", "LOOPBACK_CLEARTEXT_ALLOWED", "true")
        }
    }

    testOptions.unitTests.isIncludeAndroidResources = true

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

// Test-only versions (fold into gradle/libs.versions.toml when the catalog is next touched).
val robolectric = "4.17"
val androidxTestCore = "1.7.0"

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
    implementation(projects.surface.widget)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("org.robolectric:robolectric:$robolectric")
    testImplementation("androidx.test:core:$androidxTestCore")
}
