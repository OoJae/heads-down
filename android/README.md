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
Hilt 2.60.1, androidx.core 1.19.1, MWA clientlib-ktx 2.2.0. Shared config lives in the
`build-logic` convention plugins.

## Modules

| Module | What it does |
|---|---|
| `core/keys` | Keystore P-256 rig key (StrongBox, else TEE; non-exportable; attested), strict DER to raw `r‖s`, low-S, SEC1 compression, the `HDv1` heartbeat format and signer |
| `core/wallet` | MWA connect / SIWS / signAndSend, AES-GCM Keystore vault for the auth token, confirmation poller (success means confirmed with `err == null`) |
| `feature/shift` | `ShiftStateMachine`, accelerometer `FaceDownDetector`, per-round `HeartbeatTicker`, the `specialUse` foreground service |
| `feature/reveal` | Morning haul reveal: exact alarm aligned to the user's next alarm, full-screen intent, Activity (stub) |
| `feature/oem-keepalive` | HyperOS/MIUI detection, Autostart and "No restrictions" deep links with fallbacks, Doze exemption, "killed by the OS" health check |
| `surface/tile` | Quick Settings tile and the non-exported translucent MWA trampoline |
| `surface/notification` | Ongoing shift notification: Android 16 Live Update (`ProgressStyle` + promoted ongoing), with a fallback on 14/15 |
| `app` | Hilt graph, Compose home screen and onboarding, hardened manifest, R8 rules |

## Heartbeat wire format (contract with `programs/heads-down` and the crank)

101 bytes, integers little-endian, **signed raw**: the secp256r1 precompile hashes it with SHA-256
itself, and Keystore `SHA256withECDSA` hashes the same bytes, so the program compares fields
byte for byte.

| off | len | field |
|---:|---:|---|
| 0 | 4 | `"HDv1"` |
| 4 | 32 | program_id |
| 36 | 32 | rig (Rig account address) |
| 68 | 8 | ore_round_id (u64, = owner-checked `Board.round_id`) |
| 76 | 8 | counter (u64, strictly increasing, write-ahead persisted) |
| 84 | 1 | state: IDLE 0, ARMED 1, DOWN 2, COOLING 3, BROKEN 4, FROZEN 5 |
| 85 | 8 | shift_id (u64) |
| 93 | 8 | lease_end (u64, `ore_round_id ≤ lease_end < ore_round_id + 3`) |

The signature is 64 bytes `r‖s` with `s ≤ n/2`. The public key is 33-byte SEC1 compressed.

## Shift loop

`Idle → Armed → Down → Cooling (10 s grace) → Down | Broken`, plus `Frozen`. "Dark" means
face-down, screen off, and on the charger for Night Shift. Screen-on and unplugging cool the rig
immediately. Unlocking (`USER_PRESENT`) breaks the shift at once. Heartbeats are signed only
while the rig is DOWN and a posture sample is less than 5 s old. The service is
`START_NOT_STICKY`: if the OS kills it, the rig goes cold and nothing is spent.

## Stubbed until the program and services exist (all bound in `app/di/AppModule.kt`)

- `ClockInTransactions`: returns no transaction, so the tile arms a zero-SOL focus-only shift.
- `StubOreRoundSource`: synthetic 78 s rounds. These ids never match a real Board.
- `RigBinding.UNREGISTERED`: heartbeats bind to an all-zero rig, so they can never dig.
- `LocalHeartbeatSink`: heartbeats stay on the device (no intake or Nostr yet).
- `UnconfiguredSolanaRpc`: confirmation fails closed as "unknown", never "success".
- The rig-key attestation challenge is generated locally until the registrar issues one.

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
- MWA clientlib-ktx 2.2.0 has its own namespace (`...clientlib.ktx`), so the
  `android.uniquePackageNames=false` workaround needed by 2.0.3 is gone. Its POM still leaks
  `androidx.test.ext:junit-ktx` at runtime; `core/wallet` excludes it.
