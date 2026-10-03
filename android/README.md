# Heads Down: Android app

Native Kotlin + Jetpack Compose. Package `xyz.headsdown`. minSdk 31, targetSdk 36, compileSdk 37.
Built and tested against a Redmi 14C (HyperOS / Android 14, no gyroscope, virtual proximity) and
designed for stock Android 16 (Seeker).

The on-chain contract is `programs/heads-down/INTERFACE.md` **v1.3** and its machine-checked
vectors in `programs/heads-down/vectors/`. The app's builders are tested byte for byte against
those vectors (see [Contract tests](#contract-tests)). `INTERFACE-NOTES.md` here keeps only what
the program contract does not cover (the crank and indexer contracts, the registrar flow).

## Build

```sh
cd android
./gradlew :app:assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleLocaldev     # the local devstack build (see below)
./gradlew test                      # JVM unit tests, all modules
./gradlew lint
./gradlew :app:assembleRelease      # R8-minified, unsigned, no android.util.Log calls
./gradlew :core:chain:syncGoldenVectors   # refresh the golden-vector copy after a contract change
```

Needs JDK 17+ (21 used) and an Android SDK with `platforms;android-37` (point `local.properties`
`sdk.dir` at it). The wrapper pins Gradle 9.8.0 by SHA-256.

Toolchain: AGP 9.4.1 (built-in Kotlin) with KGP 2.4.20, Compose BOM 2026.09.00, KSP 2.3.12,
Hilt 2.60.1, androidx.core 1.19.1, Glance 1.2.0, MWA clientlib-ktx 2.2.0, OkHttp 5.5.0,
kotlinx-serialization-json 1.11.0 (tree API only, no compiler plugin). Every version is in
`gradle/libs.versions.toml`; shared config lives in the `build-logic` convention plugins.

### Cluster and endpoints

Devnet by default. The crank, registrar and indexer have **no default host**: a default would be
a name somebody else can register. Without them the app is local-only (heartbeats stay on the
device, the rig registers as a guest, the reveal says "no haul yet"). A mainnet build must name
all four endpoints and the identity site, and the build fails if one is missing. Set them per
build, never in source:

```sh
./gradlew :app:assembleDebug -Pheadsdown.cluster=mainnet \
  -Pheadsdown.rpcUrl=https://rpc-proxy.example.org \
  -Pheadsdown.crankUrl=wss://crank.example.org/ws \
  -Pheadsdown.registrarUrl=https://registrar.example.org \
  -Pheadsdown.indexerUrl=https://indexer.example.org \
  -Pheadsdown.identityUri=https://example.org
```

`identityUri` is the site a wallet shows next to every signing prompt (the Mobile Wallet Adapter
identity), and its host is the Sign In With Solana domain (`-Pheadsdown.siwsDomain` overrides
it; the registrar's `HD_SIWS_DOMAIN` must be the same). It has to be a site the team controls:
whoever controls it can present itself to wallets as Heads Down. The default,
`https://oojae.github.io/heads-down`, is the project's GitHub Pages address.

An empty `crankUrl` builds a local-only app (no digs), an empty `registrarUrl` registers every
rig as a guest, and an empty `indexerUrl` shows no morning haul. The build refuses a non-`https`
RPC, registrar or indexer URL, a non-`wss` crank URL, and any URL with a query string or
user-info, which is where provider keys hide (`?api-key=`). Keys stay behind the proxy.

### Clock-in policy for demo takes

The tile arms the Night Shift of docs/ECONOMICS.md §5 until the plan screens exist: 0.001 SOL digs
on 4 split tiles, a 0.53 SOL/ORE plan under a 0.67 SOL/ORE wallet ceiling, lease 1, 8 hours,
0.02 SOL placed per shift and 0.14 SOL per week. Every value is a build property:

```sh
./gradlew :app:assembleDebug -Pheadsdown.policy.mode=day -Pheadsdown.policy.windowMinutes=25 \
  -Pheadsdown.policy.leaseRounds=2 -Pheadsdown.policy.planMaxEvCost=900000000 \
  -Pheadsdown.policy.capMaxCost=1000000000 -Pheadsdown.policy.digLamports=2000000 \
  -Pheadsdown.policy.splitTiles=10 -Pheadsdown.policy.soloTiles=0 \
  -Pheadsdown.policy.shiftBudgetLamports=20000000 -Pheadsdown.policy.weeklyBudgetLamports=140000000
```

Gradle checks the ranges (lease 1..3, plan ≤ cap, dig ≥ 0.001 SOL, 1..25 tiles, budgets that
cover a dig); the app and the program check them again. `mode=day` sets `plan_flags` bit1.

## Modules

| Module | What it does |
|---|---|
| `core/keys` | Keystore P-256 rig key (StrongBox, else TEE; non-exportable; attested with the registrar's challenge), strict DER to raw `r‖s`, low-S, SEC1 compression, the v1.1 `HDv1` HEARTBEAT / BREAK / FREEZE / PLAN preimages, the shared write-ahead `RigCounter`, `RigMessageSigner` |
| `core/wallet` | MWA 2.2.0: one-approval session (authorize, `get_capabilities`, build, sign and send), SIWS with the registrar's fields, a sign-only mode where the app submits (localdev), AES-GCM Keystore vault for the auth token, confirmation poller (success means confirmed with `err == null`) |
| `core/chain` | Solana JSON-RPC over OkHttp, PDA derivation, checked ORE / heads_down account decoders, the v1.1 instruction builders, legacy/v0 transactions, the single-transaction clock-in, the crank uplink (contract A), the registrar client and attestation flow, the indexer haul client (contract B) |
| `feature/shift` | `ShiftStateMachine`, accelerometer `FaceDownDetector`, per-round `HeartbeatTicker`, `BoardRoundSource` (ORE `Board.round_id`), `CrankHeartbeatSink` (acks, link status), the shift journal (first pickup), the `specialUse` foreground service; the debug-only sensor lab |
| `feature/reveal` | The morning haul reveal: exact alarm aligned to the user's next alarm, full-screen intent, a 120 Hz board replay, counts, effective price against market, streak, share grid, explorer link |
| `feature/oem-keepalive` | HyperOS/MIUI detection, Autostart and "No restrictions" deep links with fallbacks, Doze exemption, "killed by the OS" health check |
| `surface/tile` | Quick Settings tile and the non-exported translucent MWA trampoline |
| `surface/notification` | Ongoing shift notification: Android 16 Live Update (`ProgressStyle` + promoted ongoing), with a fallback on 14/15 |
| `surface/widget` | Glance 1.2 widgets. **Rig**: heat (cold / armed / hot / cooling / frozen) with a five-pixel heat bar, a RemoteViews `Chronometer` that ticks the shift with no app updates, the last on-chain haul and the streak. **Crew**: a marked placeholder ("PREVIEW · NOT LIVE") until rooms exist. Dynamic colour where available, generated previews on Android 15+ |
| `surface/haptics` | The haptic language: arm thunk, cooling tick, reveal drumroll, Motherlode flourish. Composed primitives when every primitive is supported, else an amplitude waveform, else on/off. Basic motors (the Redmi 14C) also get a generated CC0 thunk (`surface/haptics/SOUNDS.md`). `HapticGovernor` keeps it from ever buzzing per round |
| `app` | Hilt graph, Compose home screen and onboarding, the night-shift intro, the shift observer that drives the widget and haptics, hardened manifest, R8 rules, the `localdev` build type and the debug-only rig debug screen |

## Signed messages (INTERFACE v1.1 §4)

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
- `counter`: one sequence for every kind, persisted **before** it is used, raised to the on-chain
  `Rig.hb_counter` at clock-in, and re-read when the crank acks `stale_counter`.
- BREAK reasons: 1 pickup, 2 screen-on and 7 unplugged cool the rig; 4 lease lapse, 5 budget,
  6 manual and 8 unlocked break it. The phone sends 1 (lifted), 2 (screen stayed on), 7
  (unplugged) and 8 (unlocked), only inside the plan window: after it the program digs nothing and
  a BREAK would turn a completed night into a pickup (no streak). FREEZE carries 3.
- `plan_flags`: bit0 focus-only, bit1 Day Shift.

## Contract tests

- `core/chain` **GoldenInstructionsTest** reads `programs/heads-down/vectors/instructions.json`
  (a drift-checked copy in `src/test/resources/golden`) and rebuilds the 18 vectors the phone can
  build (10 of the 15 tags: all but the admin and crank ones): data bytes and ordered account metas (pubkey,
  signer, writable) must be identical, and so must the secp256r1 and Ed25519 companion
  instructions. `GoldenMessagesTest` does the same for `messages.json`, `RegistrarVoucherTest`
  for `registrar.json`. `verifyGoldenVectors` runs before every unit-test task and fails on one
  byte of drift from `programs/heads-down/vectors/`; `syncGoldenVectors` refreshes the copy.
- `core/chain/src/test/resources/ix_vectors.json` is the phone's own output, regenerated by
  `InstructionVectorsTest` (`HD_WRITE_VECTORS=1`), for the program's crosscheck
  (`programs/heads-down/vectors/crosscheck/run.sh`, step 1).
- `core/keys/src/test/resources/vectors.json`: the RFC 6979 message vectors shared with Rust.
- `core/chain` **DevstackClockInE2ETest** (opt-in, skipped by default) runs the phone's own code
  against a live devstack: the composed clock-in lands on the fork, a contract-A heartbeat signed
  like the Keystore key is acked by the real crank, and the crank's dig places the whole planned
  dig with the executor fee taken inside `cap_round`:

  ```sh
  HD_DEVSTACK_RPC=http://127.0.0.1:8899 HD_DEVSTACK_CRANK_WS=ws://127.0.0.1:8787/ws \
    ./gradlew :core:chain:testDebugUnitTest --tests '*DevstackClockInE2ETest*'
  ```

## Clock-in (one wallet approval)

Tile → non-exported trampoline → one MWA session: authorize, `get_capabilities` (v0 if
supported, else legacy), read Config / Rig / Automation and the slot, build one transaction,
sign and send:

`[end_shift?] [ed25519 voucher? rotate_key?] [ORE automate?] [ed25519 voucher? register_rig?] set_caps arm_shift`

- **ORE automate**: executor = heads_down Executor PDA, Discretionary, `fee = Config.executor_fee`
  (Config @80; any other fee makes every dig a `StrategyMismatch` skip), per-tile cap
  `dig_lamports / tiles`, topped up to the shift budget plus one executor fee per dig round.
- **The fee sits inside every cap** (INTERFACE v1.1 §6.4): `cap_round = dig_lamports + executor_fee`,
  and `cap_shift` / `cap_week` add one executor fee per dig round their budgets allow. Focus-only
  shifts grant zero caps.
- **register_rig / rotate_key** carry `has_attestation`. A stored registrar voucher is included
  (its 223-byte Ed25519SigVerify instruction right before, referenced by absolute index) only
  when it covers this wallet and key, was signed by `Config.registrar` and expires well after the
  current slot; a guest rig with the same key is upgraded once. Anything else registers a guest:
  a level-0 or unusable voucher would fail the whole clock-in on-chain.
- **end_shift** when `Rig.shift_open` (@336) is set; a Frozen rig is refused on the phone.
- The shift arms only when the signature is confirmed with `err == null`. If heads_down is not
  initialized on the cluster, the tile arms a zero-SOL focus-only shift locally.

## Rig key attestation (registrar)

Setup step 5 runs `RigOnboarding`: `POST /siws/nonce` → the wallet's Sign In With Solana with
the registrar's exact `nonce`, `uri`, `issued_at`, `expiration_time`, `statement` and the build's
chain id (`solana:mainnet`, `solana:devnet`, or `solana:localnet` in localdev) → `POST
/siws/verify` → `GET /attest/challenge` → the rig key is generated with
`setAttestationChallenge(SHA-256("HDattest" ‖ authority ‖ nonce))` (the phone re-derives it) →
`POST /attest` with the certificate chain, session token and nonce. A level 1 or 2 voucher is
checked against the HDreg preimage and stored for clock-in. No registrar, an outage, a declined
sign-in, level 0 or a refused chain all leave a working guest key, and the step says which. The
session token lives in memory only.

## Shift loop and the crank (contract A)

`Idle → Armed → Down → Cooling (10 s grace) → Down | Broken`, plus `Frozen`. "Dark" means
face-down, screen off, and on the charger for Night Shift. Screen-on and unplugging cool the rig
immediately. Unlocking (`USER_PRESENT`) breaks the shift at once. Heartbeats are signed only
while the rig is DOWN and a posture sample is less than 5 s old, leasing the plan's
`lease_rounds`. The service is `START_NOT_STICKY`: if the OS kills it, the rig goes cold and
nothing is spent. The first pickup after going dark is journaled for the morning haul.

Rounds come from ORE `Board.round_id` (polled every 5 s, only ever increasing). Each signed
message goes to a local JSON-lines log and to the crank's WebSocket intake (`/ws`) as
`{"type":"heartbeat"|"break"|"freeze", "rig", "counter", "shift_id", "round_id" + "lease_rounds" |
"reason", "sig64"}`. The crank lands phone-signed BREAK and FREEZE on-chain itself. Its acks
`{"type":"ack","counter","ok","reason"}` are matched to the frames this phone sent; a current
refusal (`bad_signature`, `stale_counter`, `unknown_rig`, `rate_limited`, `malformed`,
`lease_invalid`) is shown on the rig card and debug-logged as a counter and a code. No uplink
means no digs, never a crash. The uplink reconnects with jittered backoff and closes 5 s after the
shift ends.

## Morning haul (contract B)

The reveal reads `GET /v1/rigs/{rig}/haul/latest` from the indexer for the bound rig (HTTPS
only, except loopback in localdev): rounds with dug masks and winning squares, dark rounds, digs,
SOL placed, executor fees, ORE mined (exact, from ORE's round events), the effective and market
price per ORE, streak before and after, and an explorer link. `first_pickup_ts` is not observable
on-chain, so the phone fills it from its own shift journal. A real haul updates the widget; a
`simulated` one is labelled and never does. With no haul the reveal says so and offers the
labelled sample night only on request.

## Local devstack (`localdev` build type)

For the validator, `hd-crank`, the indexer and the registrar on the laptop
(`scripts/devstack/up.sh [--with-registrar]`), reached from the phone over USB:

```sh
scripts/devstack/phone.sh          # adb reverse 8899, 8900, 8787, 8788, 8790 (and --fund <wallet>)
./gradlew :app:installLocaldev     # xyz.headsdown.localdev, installs beside the debug app
# optional: -Pheadsdown.localdev.rpcUrl=http://localhost:8899 -Pheadsdown.localdev.crankUrl=ws://localhost:8787/ws
#           -Pheadsdown.localdev.indexerUrl=http://127.0.0.1:8788 -Pheadsdown.localdev.registrarUrl=http://127.0.0.1:8790
```

- **Wallet path.** An MWA wallet broadcasts to its own cluster, never to the laptop's validator,
  so localdev sets `SUBMIT_THROUGH_APP_RPC`: the wallet only signs (`signTransactions`; the
  Solana Mobile fakewallet works) and the app submits through its own RPC, then polls it for
  confirmation. MWA has no localnet `Blockchain`, so the session is authorized as devnet; SIWS
  uses `solana:localnet` and the domain `localhost`, as the devstack registrar
  (`up.sh --with-registrar`) is configured, and its loopback `http` URI is accepted in this build
  only. That registrar attests app package `xyz.headsdown` by default: run it with
  `HD_APP_PACKAGE=xyz.headsdown.localdev` to attest the localdev APK (otherwise the rig is a guest).
- **Rig debug screen** (home → "Rig key and devstack (debug)", debug and localdev builds only):
  the rig P-256 public key as 33-byte hex and the `scripts/devstack/clock-in.sh <hex>` command to
  copy, the bound wallet and Rig address, the voucher, the counter, crank acks and endpoints.
  After clock-in.sh armed a Rig for this key with a Mac-held wallet, paste its `authority` and
  **Attach and arm**: the phone reads the Rig, checks the key and the open shift, binds to it,
  raises its counter above `hb_counter` and starts the local shift with the on-chain `shift_id`
  and lease. Then lay the phone face-down on the cable.
- Only `localdev` may use cleartext, and only to `127.0.0.1` or `localhost` (`http` RPC,
  registrar and indexer, `ws` crank). Debug and release still refuse `http://` and `ws://` at
  configuration time. Three layers: Gradle (`app/build.gradle.kts`), runtime (`EndpointPolicy`
  in `AppModule`; the loopback transports exist only in `src/localdev`), and the manifest (only
  `src/localdev` adds a network security config, for exactly those two hosts).
  `app/scripts/check-endpoint-policy.sh` checks the Gradle side; `EndpointPolicyTest` and
  `CleartextConfigTest` the rest.

## Surfaces

**Widget.** The app's shift observer maps every snapshot onto `RigWidgetUpdates.onRig`. A heat
change renders; the per-round count is stored but never pushed (the chronometer ticks by itself),
so a night costs three renders. A fresh process reports the rig cold, and the widget's 30-minute
update starts one, so a shift killed by the OS shows cold within half an hour; a running rig
recorded before a reboot (`Settings.Global.BOOT_COUNT`) shows cold at once. `canDig` is false
while heartbeats are unsigned or bound to the unregistered rig. The streak comes from the
on-chain Rig after a confirmed clock-in and from the indexer's haul; hauls are pushed only when
they are real.

**Haptics.** During a shift there are exactly two cues, both caused by the user: one arm thunk
when the rig first goes hot after arming, and a cooling tick when the user lifts the phone or
wakes the screen. Re-going dark, a slipping charger, breaks, freezes and rounds are silent. The
drumroll plays with the reveal's replay; the flourish only for a real Motherlode share.
`HapticGovernor` refuses reveal cues outside the reveal, spaces every cue, and caps a shift at
two buzzes an hour.

**Reveal.** Opened by the exact alarm or from the home screen. Asks the display for its fastest
mode (120 Hz on the Redmi 14C); the replay is a pure function of elapsed time, drawn in the draw
phase only, and "Remove animations" shows the final board at once. Share: a 1080x1350 PNG of
where the rig dug plus dark time and streak (no amounts, prices, winning tiles or wallet),
through a non-exported FileProvider, plus the same grid as emoji text; over the lock screen it
asks to unlock first.

**Sensor lab** (debug and localdev builds only). Home → "Sensor lab (debug)": record labelled
sessions (bump, pickup, slide, set-down) of 50 Hz accelerometer windows from 2 s before to 3 s
after every motion event, with screen-on, screen-off and unlock as ground truth, in
`filesDir/sensorlab/session-<id>.csv` (`row_type,window_id,t_ns,recv_ns,ax,ay,az,event`). It lives
in `feature/shift/src/debug`; release has only a stub, and `feature/shift` runs its release unit
tests to prove no lab class is compiled into release.

## Still stubbed

- The crank, registrar and indexer URLs are placeholders until the services are deployed.
- `ClockInPolicy`: build-time budgets and cost ceilings until the plan screen and price feed exist.
- "Buy the rest at market" (the Jupiter leg) and the nightly ORE target.
- `StubOreRoundSource` remains for tests only.

## Security notes

- Only `MainActivity` is exported, plus the tile service (`BIND_QUICK_SETTINGS_TILE`) and the two
  widget providers (they must receive `APPWIDGET_UPDATE`; they read nothing from the Intent).
  The trampoline, reveal, receiver, shift service, the reveal's `RevealShareFileProvider` and,
  in debug and localdev only, the sensor lab and the rig debug screen are not exported. The
  trampoline reads no intent extras. Glance pulls in WorkManager (services and receivers guarded
  by system permissions) and the `ACCESS_NETWORK_STATE` and `RECEIVE_BOOT_COMPLETED` permissions;
  the haptics add `VIBRATE`.
- `allowBackup=false`, and data-extraction rules exclude every domain for backup and
  device-to-device transfer.
- The release R8 config strips all `android.util.Log` calls. The app's own are debug-only
  (`DebugLog`, counters and codes, never keys, signatures, tokens or URLs), and lint escalates
  `LogConditional` to an error.
- The rig key needs no user authentication per use, because heartbeats are signed with the
  screen off. The on-chain caps bound what that key can do (see `RigKeyManager` KDoc).
- Network: RPC, registrar and indexer are HTTPS-only and the crank WSS-only. No redirects,
  bounded responses, checked path segments, and timeouts. No interceptors, so no URL, header or
  body is ever logged or put in an exception. Wallet failure text is reduced to MWA's fixed
  strings, and the registrar session token is never stored or logged.
- Every account the app reads is checked for owner, exact size, discriminator or tag and
  version, and PDA address before a field is used. A lagging RPC can never move the round feed
  backwards. A registrar answer for another domain, cluster, wallet or key never reaches the
  wallet or a transaction, and an indexer haul for another rig is refused.
- MWA clientlib-ktx 2.2.0 has its own namespace (`...clientlib.ktx`), so the
  `android.uniquePackageNames=false` workaround needed by 2.0.3 is gone. Its POM still leaks
  `androidx.test.ext:junit-ktx` at runtime; `core/wallet` excludes it.
