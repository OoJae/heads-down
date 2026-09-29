plugins {
    alias(libs.plugins.headsdown.android.library)
}

android {
    namespace = "xyz.headsdown.core.chain"
}

// Solana chain layer: JSON-RPC over OkHttp (HTTPS only), PDA derivation, ORE and heads_down
// account decoders, instruction and transaction builders, the clock-in composition and the
// crank WebSocket uplink. Pure Kotlin on the JVM so everything is unit-tested off-device.
dependencies {
    api(projects.core.keys)
    // SolanaRpc / Commitment / SignatureStatus (the confirmation poller's port) and Base58.
    api(projects.core.wallet)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    // Ed25519 on-curve test for find_program_address: the TweetNaCl port web3-solana uses.
    implementation(libs.salkt)

    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.okhttp.tls)
    // Cross-checks: web3-solana's own PDA derivation and message parser, and BouncyCastle's
    // RFC 8032 Ed25519 point decoding.
    testImplementation(libs.web3.solana)
    testImplementation(libs.bouncycastle.bcprov)
}
