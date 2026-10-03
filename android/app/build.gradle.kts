import java.net.URI

plugins {
    alias(libs.plugins.headsdown.android.application)
    alias(libs.plugins.headsdown.android.compose)
    alias(libs.plugins.headsdown.hilt)
}

fun prop(name: String): String? = findProperty(name) as String?

// Cluster and endpoints. Public, non-secret configuration only: provider API keys never ship
// in the APK (they stay behind the team proxy, THREAT_MODEL §8 "Network"). Override per build:
//   ./gradlew :app:assembleDebug -Pheadsdown.cluster=mainnet \
//       -Pheadsdown.rpcUrl=https://rpc.example.org -Pheadsdown.crankUrl=wss://crank.example.org/ws \
//       -Pheadsdown.registrarUrl=https://registrar.example.org -Pheadsdown.indexerUrl=https://indexer.example.org \
//       -Pheadsdown.identityUri=https://example.org
// An empty crankUrl builds a local-only app (heartbeats stay on the device; no digs). An empty
// registrarUrl registers every rig as a guest; an empty indexerUrl shows no morning haul.
//
// No service has a default host: a default would be a name somebody else can register. A devnet
// build without the three service URLs is a local-only app. A mainnet build must name all four
// (an empty value is a deliberate "off") and the site the app identifies itself with.
val cluster = prop("headsdown.cluster") ?: "devnet"
require(cluster == "devnet" || cluster == "mainnet") { "headsdown.cluster must be devnet or mainnet" }
if (cluster == "mainnet") {
    for (name in listOf("rpcUrl", "crankUrl", "registrarUrl", "indexerUrl", "identityUri")) {
        require(prop("headsdown.$name") != null) {
            "a mainnet build must set -Pheadsdown.$name=... explicitly (empty turns a service off; rpcUrl and identityUri cannot be empty)"
        }
    }
}
val rpcUrl = prop("headsdown.rpcUrl") ?: "https://api.devnet.solana.com"
val crankUrl = prop("headsdown.crankUrl") ?: ""
val registrarUrl = prop("headsdown.registrarUrl") ?: ""
val indexerUrl = prop("headsdown.indexerUrl") ?: ""

// WHO THE APP SAYS IT IS. The wallet shows this site next to every signing prompt (Mobile Wallet
// Adapter identity) and the SIWS message names its host, so it must be a site the team controls:
// whoever controls it can present itself as Heads Down. The default is the project's GitHub Pages
// address, which only the repository owner's GitHub account can publish to.
//   -Pheadsdown.identityUri=https://example.org          (https, no query, no fragment)
//   -Pheadsdown.siwsDomain=example.org                   (default: the host of identityUri; the
//                                                         registrar's HD_SIWS_DOMAIN must equal it)
val identityUri = prop("headsdown.identityUri") ?: "https://oojae.github.io/heads-down"
val identity = URI(identityUri)
require(identity.scheme == "https" && !identity.host.isNullOrEmpty() && identity.rawQuery == null && identity.rawFragment == null && identity.rawUserInfo == null) {
    "headsdown.identityUri must be an https:// URL with a host and no credentials, query string or fragment"
}
val siwsDomain = prop("headsdown.siwsDomain") ?: identity.host.lowercase()
require(siwsDomain.isNotEmpty() && siwsDomain.none { it.isWhitespace() || it == '/' || it == ':' }) { "headsdown.siwsDomain must be a bare host name" }

// LOCAL DEVSTACK (the `localdev` build type only): validator, hd-crank, indexer and registrar on
// the laptop, reached from the phone through `scripts/devstack/phone.sh` (adb reverse).
//   ./gradlew :app:assembleLocaldev [-Pheadsdown.localdev.rpcUrl=http://127.0.0.1:8899]
//       [-Pheadsdown.localdev.crankUrl=ws://127.0.0.1:8787/ws] [-Pheadsdown.localdev.indexerUrl=…]
//       [-Pheadsdown.localdev.registrarUrl=…]
// Debug and release never read these, and still refuse http:// and ws:// outright.
val localdevRpcUrl = prop("headsdown.localdev.rpcUrl") ?: "http://127.0.0.1:8899"
val localdevCrankUrl = prop("headsdown.localdev.crankUrl") ?: "ws://127.0.0.1:8787/ws"
val localdevRegistrarUrl = prop("headsdown.localdev.registrarUrl") ?: "http://127.0.0.1:8790"
val localdevIndexerUrl = prop("headsdown.localdev.indexerUrl") ?: "http://127.0.0.1:8788"

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
if (registrarUrl.isNotEmpty()) requireEndpoint("headsdown.registrarUrl", registrarUrl, "https", "http", allowLoopback = false)
if (indexerUrl.isNotEmpty()) requireEndpoint("headsdown.indexerUrl", indexerUrl, "https", "http", allowLoopback = false)
requireEndpoint("headsdown.localdev.rpcUrl", localdevRpcUrl, "https", "http", allowLoopback = true)
if (localdevCrankUrl.isNotEmpty()) requireEndpoint("headsdown.localdev.crankUrl", localdevCrankUrl, "wss", "ws", allowLoopback = true)
if (localdevRegistrarUrl.isNotEmpty()) {
    requireEndpoint("headsdown.localdev.registrarUrl", localdevRegistrarUrl, "https", "http", allowLoopback = true)
}
if (localdevIndexerUrl.isNotEmpty()) requireEndpoint("headsdown.localdev.indexerUrl", localdevIndexerUrl, "https", "http", allowLoopback = true)

// CLOCK-IN POLICY for demo takes (defaults: the Night Shift of docs/ECONOMICS.md §5). Every value
// is checked here and again by the app (ClockInRequest) and on-chain (caps, plan, arm_shift):
//   ./gradlew :app:assembleDebug -Pheadsdown.policy.mode=day -Pheadsdown.policy.windowMinutes=25 \
//       -Pheadsdown.policy.leaseRounds=2 -Pheadsdown.policy.digLamports=2000000 -Pheadsdown.policy.splitTiles=10 \
//       -Pheadsdown.policy.planMaxEvCost=900000000 -Pheadsdown.policy.capMaxCost=1000000000
val policyMode = prop("headsdown.policy.mode") ?: "night"
require(policyMode == "night" || policyMode == "day") { "headsdown.policy.mode must be night or day" }
fun policyLong(name: String, default: Long): Long =
    prop("headsdown.policy.$name")?.let { it.toLongOrNull() ?: error("headsdown.policy.$name must be an integer") } ?: default
val policyWindowMinutes = policyLong("windowMinutes", if (policyMode == "day") 50 else 8 * 60)
val policyLeaseRounds = policyLong("leaseRounds", 1)
val policyPlanMaxEvCost = policyLong("planMaxEvCost", 530_000_000) // lamports per ORE: Steady
val policyCapMaxCost = policyLong("capMaxCost", 670_000_000) // lamports per ORE: Hunter
val policyDigLamports = policyLong("digLamports", 1_000_000) // 0.001 SOL per dig
val policySplitTiles = policyLong("splitTiles", 4)
val policySoloTiles = policyLong("soloTiles", 0)
val policyShiftBudget = policyLong("shiftBudgetLamports", 20_000_000) // SOL placed per shift
val policyWeeklyBudget = policyLong("weeklyBudgetLamports", 140_000_000)
require(policyWindowMinutes in 1..24 * 60) { "headsdown.policy.windowMinutes must be 1..1440" }
require(policyLeaseRounds in 1..3) { "headsdown.policy.leaseRounds must be 1..3" }
require(policyPlanMaxEvCost in 0..policyCapMaxCost) { "headsdown.policy.planMaxEvCost must be 0..capMaxCost" }
require(policyDigLamports >= 1_000_000) { "headsdown.policy.digLamports must be at least 1000000 (0.001 SOL)" }
require(policySplitTiles in 0..15 && policySoloTiles in 0..10 && policySplitTiles + policySoloTiles >= 1) {
    "headsdown.policy.splitTiles must be 0..15, soloTiles 0..10, and at least one tile in total"
}
require(policyShiftBudget >= policyDigLamports && policyWeeklyBudget >= policyShiftBudget) {
    "headsdown.policy: shiftBudgetLamports must cover one dig and weeklyBudgetLamports the shift"
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
        buildConfigField("String", "REGISTRAR_URL", "\"$registrarUrl\"")
        buildConfigField("String", "INDEXER_URL", "\"$indexerUrl\"")
        // The site the wallet shows for this app, and the domain the app signs in to (SIWS); the
        // registrar must answer for exactly this domain.
        buildConfigField("String", "IDENTITY_URI", "\"$identityUri\"")
        buildConfigField("String", "SIWS_DOMAIN", "\"$siwsDomain\"")
        buildConfigField("boolean", "LOOPBACK_CLEARTEXT_ALLOWED", "false")
        // true: the wallet only signs and the app submits through its own RPC (localdev).
        buildConfigField("boolean", "SUBMIT_THROUGH_APP_RPC", "false")
        buildConfigField("boolean", "POLICY_DAY", "${policyMode == "day"}")
        buildConfigField("long", "POLICY_WINDOW_SECONDS", "${policyWindowMinutes * 60}L")
        buildConfigField("int", "POLICY_LEASE_ROUNDS", "$policyLeaseRounds")
        buildConfigField("long", "POLICY_PLAN_MAX_EV_COST", "${policyPlanMaxEvCost}L")
        buildConfigField("long", "POLICY_CAP_MAX_COST", "${policyCapMaxCost}L")
        buildConfigField("long", "POLICY_DIG_LAMPORTS", "${policyDigLamports}L")
        buildConfigField("int", "POLICY_SPLIT_TILES", "$policySplitTiles")
        buildConfigField("int", "POLICY_SOLO_TILES", "$policySoloTiles")
        buildConfigField("long", "POLICY_SHIFT_BUDGET_LAMPORTS", "${policyShiftBudget}L")
        buildConfigField("long", "POLICY_WEEKLY_BUDGET_LAMPORTS", "${policyWeeklyBudget}L")
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
            buildConfigField("String", "SOLANA_CHAIN", "\"solana:localnet\"")
            buildConfigField("String", "SOLANA_RPC_URL", "\"$localdevRpcUrl\"")
            buildConfigField("String", "CRANK_WS_URL", "\"$localdevCrankUrl\"")
            buildConfigField("String", "REGISTRAR_URL", "\"$localdevRegistrarUrl\"")
            buildConfigField("String", "INDEXER_URL", "\"$localdevIndexerUrl\"")
            // scripts/devstack/up.sh --with-registrar runs the registrar with HD_SIWS_DOMAIN=localhost.
            buildConfigField("String", "SIWS_DOMAIN", "\"localhost\"")
            buildConfigField("boolean", "LOOPBACK_CLEARTEXT_ALLOWED", "true")
            // MWA wallets broadcast to their own cluster, never to the laptop's validator.
            buildConfigField("boolean", "SUBMIT_THROUGH_APP_RPC", "true")
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

// Developer tools (the rig debug screen for scripts/devstack/clock-in.sh) are compiled into the
// debug and localdev builds only: src/devtools is added to those two variants, and release has a
// stub in src/release that reports the screen absent.
androidComponents {
    onVariants(selector().withBuildType("debug")) { it.sources.kotlin?.addStaticSourceDirectory("src/devtools/kotlin") }
    onVariants(selector().withBuildType("localdev")) { it.sources.kotlin?.addStaticSourceDirectory("src/devtools/kotlin") }
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
    implementation(projects.surface.widget)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    // The indexer-backed haul repository and the registrar wiring against fake servers.
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.okhttp.tls)
    testImplementation(libs.kotlinx.serialization.json)
}
