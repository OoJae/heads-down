# Heads Down: Android app

Native Kotlin + Jetpack Compose. Package `xyz.headsdown`. minSdk 31, targetSdk 36, compileSdk 37.
Built and tested against a Redmi 14C (HyperOS / Android 14, no gyroscope, virtual proximity) and
designed for stock Android 16 (Seeker).

## Build

```sh
cd android
./gradlew :app:assembleDebug     # app/build/outputs/apk/debug/app-debug.apk
./gradlew test                   # JVM unit tests, all modules
./gradlew lint
./gradlew :app:assembleRelease   # R8-minified, unsigned, no android.util.Log calls
```

Needs JDK 17+ (21 used) and an Android SDK with `platforms;android-37` (point `local.properties`
`sdk.dir` at it). The wrapper pins Gradle 9.8.0 by SHA-256.

Toolchain: AGP 9.4.1 (built-in Kotlin) with KGP 2.4.20, Compose BOM 2026.09.00, KSP 2.3.12,
Hilt 2.60.1, androidx.core 1.19.1, MWA clientlib-ktx 2.2.0, OkHttp 5.5.0,
kotlinx-serialization-json 1.11.0 (tree API only, no compiler plugin). Shared config lives in
the `build-logic` convention plugins.

### Cluster and endpoints

Devnet by default (public RPC; a placeholder crank URL that does not resolve, so the uplink
just backs off). Override per build, never in source:

```sh
./gradlew :app:assembleDebug -Pheadsdown.cluster=mainnet \
  -Pheadsdown.rpcUrl=https://rpc-proxy.example.org \
  -Pheadsdown.crankUrl=wss://crank.example.org/v1/heartbeats   # empty = local-only build
```

The build refuses a non-`https` RPC URL, a non-`wss` crank URL, and any URL with a query string
or user-info, which is where provider keys hide (`?api-key=`). Keys stay behind the proxy.

## Modules

| Module | What it does |
|---|---|
| `core/keys` | Keystore P-256 rig key (StrongBox, else TEE; non-exportable; attested), strict DER to raw `r‖s`, low-S, SEC1 compression, the INTERFACE `HDv1` HEARTBEAT / BREAK / FREEZE / PLAN preimages, the shared write-ahead `RigCounter`, `RigMessageSigner` |
| `core/wallet` | MWA 2.2.0: one-approval session (authorize, `get_capabilities`, build, signAndSend), SIWS, AES-GCM Keystore vault for the auth token, confirmation poller (success means confirmed with `err == null`) |
| `core/chain` | HTTPS-only Solana JSON-RPC over OkHttp, PDA derivation, checked ORE / heads_down account decoders, ORE automate + heads_down instruction builders, legacy/v0 transactions, the single-transaction clock-in, the WSS crank uplink |
| `feature/shift` | `ShiftStateMachine`, accelerometer `FaceDownDetector`, per-round `HeartbeatTicker`, `BoardRoundSource` (ORE `Board.round_id`), `CrankHeartbeatSink`, the `specialUse` foreground service |
| `feature/reveal` | Morning haul reveal: exact alarm aligned to the user's next alarm, full-screen intent, Activity (stub) |
| `feature/oem-keepalive` | HyperOS/MIUI detection, Autostart and "No restrictions" deep links with fallbacks, Doze exemption, "killed by the OS" health check |
| `surface/tile` | Quick Settings tile and the non-exported translucent MWA trampoline |
| `surface/notification` | Ongoing shift notification: Android 16 Live Update (`ProgressStyle` + promoted ongoing), with a fallback on 14/15 |
| `app` | Hilt graph, Compose home screen and onboarding, hardened manifest, R8 rules |

## Signed messages (contract: `programs/heads-down/INTERFACE.md`)

Integers little-endian, no padding. The rig key signs the **32-byte `SHA-256(preimage)`** with
`SHA256withECDSA`; the secp256r1 precompile verifies ECDSA-P256 over SHA-256 of those 32 bytes,
and the program rebuilds the preimage from its own state to compare.

```
HEARTBEAT (94):    "HDv1" | program_id(32) | rig(32) | kind=1 | counter u64 | shift_id u64 | round_id u64 | lease_rounds u8
BREAK/FREEZE (86): "HDv1" | program_id(32) | rig(32) | kind=2|3 | counter u64 | shift_id u64 | reason u8
PLAN (113):        "HDv1" | program_id(32) | rig(32) | kind=4 | counter u64 | max_ev_cost u64 | dig_lamports u64 |
                   split u8 | solo u8 | lease u8 | flags u8 | window_start i64 | window_end i64
```

- Signature: 64-byte `r‖s`, `s ≤ n/2`. Public key: 33-byte SEC1 compressed.
- `counter`: one sequence for every kind, persisted **before** it is used, and raised to the
  on-chain `Rig.hb_counter` at clock-in.
- Golden vectors (RFC 6979, shared with Rust): `core/keys/src/test/resources/vectors.json`.
  Instruction layouts: `core/chain/src/test/resources/ix_vectors.json`. Choices the INTERFACE
  left open are in [`INTERFACE-NOTES.md`](INTERFACE-NOTES.md).

Heartbeats go to the crank as JSON over WSS:
`{"rig","counter","shift_id","round_id","lease_rounds","sig"}` (u64 as exact numbers, `sig` is
base64 low-S `r‖s`). BREAK/FREEZE add `"kind"`.

## Clock-in (one wallet approval)

Tile → non-exported trampoline → one MWA session: authorize, `get_capabilities` (v0 if
supported, else legacy), read Config/Rig/Automation, build one transaction, sign and send:

`[end_shift?] [rotate_key?] ORE automate (deposit; executor = heads_down Executor PDA,
Discretionary, fee = Config.executor_fee) [register_rig if first time] set_caps arm_shift`

The shift arms only when the signature is confirmed with `err == null`. If heads_down is not
initialized on the cluster, the tile arms a zero-SOL focus-only shift locally.

## Shift loop

`Idle → Armed → Down → Cooling (10 s grace) → Down | Broken`, plus `Frozen`. "Dark" means
face-down, screen off, and on the charger for Night Shift. Screen-on and unplugging cool the rig
immediately. Unlocking (`USER_PRESENT`) breaks the shift at once. Heartbeats are signed only
while the rig is DOWN and a posture sample is less than 5 s old. The service is
`START_NOT_STICKY`: if the OS kills it, the rig goes cold and nothing is spent.

Rounds come from ORE `Board.round_id` (polled every 5 s, only ever increasing). Each heartbeat
goes to the crank uplink and a local JSON-lines log. With no uplink the tick is undelivered:
no uplink means no digs, never a crash. The uplink reconnects with jittered backoff and closes
5 s after the shift ends.

## Still stubbed

- The crank URL is a placeholder until the crank is deployed (override with `-Pheadsdown.crankUrl`).
- `ClockInPolicy`: fixed budgets and cost ceilings until the plan screen and price feed exist.
- The rig-key attestation challenge is generated locally until the registrar issues one (rigs
  register as guests).
- `StubOreRoundSource` remains for tests only.

## Security notes

- Only `MainActivity` is exported (plus the tile service, which requires `BIND_QUICK_SETTINGS_TILE`).
  The trampoline, reveal, receiver and shift service are not exported. The trampoline reads no
  intent extras.
- `allowBackup=false`, and data-extraction rules exclude every domain for backup and
  device-to-device transfer.
- The release R8 config strips all `android.util.Log` calls. Lint escalates `LogConditional`
  to an error.
- The rig key needs no user authentication per use, because heartbeats are signed with the
  screen off. The on-chain caps bound what that key can do (see `RigKeyManager` KDoc).
- Network: RPC is HTTPS-only and the crank WSS-only. No redirects, bounded responses, and
  timeouts. No interceptors, so no URL, header or body is ever logged or put in an exception.
  Wallet failure text is reduced to MWA's fixed strings.
- Every account the app reads is checked for owner, exact size, discriminator or tag and
  version, and PDA address before a field is used. A lagging RPC can never move the round feed
  backwards.
- MWA clientlib-ktx 2.2.0 has its own namespace (`...clientlib.ktx`), so the
  `android.uniquePackageNames=false` workaround needed by 2.0.3 is gone. Its POM still leaks
  `androidx.test.ext:junit-ktx` at runtime; `core/wallet` excludes it.
