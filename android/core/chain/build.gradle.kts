plugins {
    alias(libs.plugins.headsdown.android.library)
}

android {
    namespace = "xyz.headsdown.core.chain"
}

// Solana chain layer: JSON-RPC over OkHttp (HTTPS only), PDA derivation, ORE and heads_down
// account decoders, instruction and transaction builders, the clock-in composition, the crank
// WebSocket uplink, and the registrar / indexer HTTPS clients. Pure Kotlin on the JVM so
// everything is unit-tested off-device.
dependencies {
    api(projects.core.keys)
    // SolanaRpc / Commitment / SignatureStatus (the confirmation poller's port), SIWS and Base58.
    api(projects.core.wallet)
    // api: OkHttpClient and okio.ByteString appear in this module's public types.
    api(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    // Ed25519 on-curve test for find_program_address and voucher signature checks: the
    // TweetNaCl port web3-solana uses.
    implementation(libs.salkt)

    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.okhttp.tls)
    // Cross-checks: web3-solana's own PDA derivation and message parser, and BouncyCastle's
    // RFC 8032 Ed25519 point decoding.
    testImplementation(libs.web3.solana)
    testImplementation(libs.bouncycastle.bcprov)
}

// ------------------------------------------------------------------------ golden vectors
//
// programs/heads-down/vectors/*.json are the frozen contract (INTERFACE v1.3): every instruction
// executed in LiteSVM on a fork of live ORE. The unit tests read a copy under
// src/test/resources/golden so this module also builds from an android/-only checkout. The copy
// can never silently drift: every unit-test run first compares it byte for byte with the source
// of truth (when the repo has it) and fails with the command that refreshes it.
//
// The real mainnet Seeker Genesis Token accounts of crates/sgt-verify/fixtures are mirrored the
// same way under src/test/resources/sgt: the phone's SgtVerifier is tested on the very bytes the
// in-program verifier is.
//
//   ./gradlew :core:chain:syncGoldenVectors     # refresh the copies after the contract changes
val goldenFiles = listOf("instructions.json", "messages.json", "registrar.json")
val goldenSourceDir: File = rootProject.layout.projectDirectory.dir("../programs/heads-down/vectors").asFile
val goldenCopyDir: File = layout.projectDirectory.dir("src/test/resources/golden").asFile
val sgtFiles = listOf(
    "manifest.json", "group.json",
    "member-20/mint.json", "member-20/token_account.json",
    "member-121035/mint.json", "member-121035/token_account.json",
)
val sgtSourceDir: File = rootProject.layout.projectDirectory.dir("../crates/sgt-verify/fixtures").asFile
val sgtCopyDir: File = layout.projectDirectory.dir("src/test/resources/sgt").asFile

val syncSgtFixtures = tasks.register<Copy>("syncSgtFixtures") {
    group = "verification"
    description = "Copies crates/sgt-verify/fixtures into this module's test resources."
    from(sgtSourceDir) { include(sgtFiles) }
    into(sgtCopyDir)
}

tasks.register<Copy>("syncGoldenVectors") {
    group = "verification"
    description = "Copies programs/heads-down/vectors/*.json (and the SGT fixtures) into this module's test resources."
    from(goldenSourceDir) { include(goldenFiles) }
    into(goldenCopyDir)
    dependsOn(syncSgtFixtures)
}

val verifyGoldenVectors = tasks.register("verifyGoldenVectors") {
    group = "verification"
    description = "Fails when the test-resource copies differ from programs/heads-down/vectors or crates/sgt-verify/fixtures."
    val mirrors = listOf(
        Triple(goldenSourceDir, goldenCopyDir, goldenFiles),
        Triple(sgtSourceDir, sgtCopyDir, sgtFiles),
    )
    doLast {
        for ((source, copy, names) in mirrors) {
            if (!source.isDirectory) {
                logger.lifecycle("golden vectors: ${source.path} not present (android/-only checkout); using the committed copy")
                continue
            }
            val drifted = names.filterNot { name ->
                val want = File(source, name)
                val have = File(copy, name)
                want.isFile && have.isFile && want.readBytes().contentEquals(have.readBytes())
            }
            if (drifted.isNotEmpty()) {
                throw GradleException(
                    "golden vectors drifted from ${source.path}: $drifted. " +
                        "Run ./gradlew :core:chain:syncGoldenVectors and re-run the tests.",
                )
            }
        }
    }
}

tasks.withType<Test>().configureEach { dependsOn(verifyGoldenVectors) }
