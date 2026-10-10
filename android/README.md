# Heads Down: Android app

Native Kotlin + Jetpack Compose. Package `xyz.headsdown`. minSdk 31, targetSdk 36, compileSdk 37.
Built for a Redmi 14C (HyperOS, no gyroscope, virtual proximity) and for stock Android 16
(Seeker). It has run on an Android 14 emulator and, on 10 October 2026, on one Redmi 14C with
Android 16 (HyperOS 3), on mainnet: setup, a rig key in the phone's TEE that the live registrar
attested, a sign-in, two clock-ins, a clock-out and "Take it back" signed in Jupiter Mobile,
heartbeats that dug five ORE rounds, and a BREAK after the phone was unlocked
([docs/MAINNET.md](../docs/MAINNET.md#the-first-shift-10-october-2026)). That was one short shift
on a debug build with the demo policy below. Not run on a phone: a whole night under HyperOS, the
haul reveal at the alarm, a dig while the cost gate is shut, Close rig, Unfreeze, Claim, the Focus
Bond, the Seeker tier, and Solflare, Phantom or Seed Vault.

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

`identityUri` is the site the app names to the wallet as its identity (Mobile Wallet Adapter).
Solana Mobile's test wallet shows it on the connect and sign-in prompts, not on the transaction
prompt. Jupiter Mobile, on the Redmi 14C on 10 October 2026, showed "Could not verify request" on
its connect prompt, then connected, signed the sign-in and signed four transactions. Solflare,
Phantom and Seed Vault have not been tried. Its host is the Sign In With Solana domain
(`-Pheadsdown.siwsDomain` overrides it; the registrar's `HD_SIWS_DOMAIN` must be the same). A
wallet cannot verify the identity yet ([docs/DEPLOY.md](../docs/DEPLOY.md), section 10.7),
so it has to be a site the team controls: whoever controls it can present itself to wallets as
Heads Down. The default, `https://oojae.github.io/heads-down/`, is the project's GitHub Pages
address. A site below the host root must end in `/`: the wallet resolves the icon `icon.png`
against it, and the build refuses a path without the slash.

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

The build that ran the first shift on mainnet (10 October 2026) was a debug build with
`planMaxEvCost=1000000000`, `capMaxCost=1200000000`, `shiftBudgetLamports=5000000` and
`weeklyBudgetLamports=35000000` (docs/DEPLOY.md, section 10.7), and the default 0.001 SOL digs on
4 tiles with lease 1. ORE's cost figure was 0.690 to 0.704 SOL per ORE during that shift, so the
default policy above would have dug nothing.

## Modules

| Module | What it does |
|---|---|
| `core/keys` | Keystore P-256 rig key (StrongBox, else TEE; non-exportable; attested with the registrar's challenge), strict DER to raw `r‖s`, low-S, SEC1 compression, the v1.1 `HDv1` HEARTBEAT / BREAK / FREEZE / PLAN preimages, the shared write-ahead `RigCounter`, `RigMessageSigner` |
| `core/wallet` | MWA 2.2.0: one-approval session (authorize, `get_capabilities`, build, sign and send), SIWS with the registrar's fields, a sign-only mode where the app submits (localdev), AES-GCM Keystore vault for the auth token, confirmation poller (success means confirmed with `err == null`) |
| `core/chain` | Solana JSON-RPC over OkHttp, PDA derivation, checked ORE / heads_down account decoders, the instruction builders (the v1.1 core, the v1.2 SKR set and v1.3's `close_shift_log`), legacy/v0 transactions, the single-transaction clock-in, the crank uplink (contract A), the registrar client and attestation flow, the indexer haul client (contract B) |
| `core/design` | "The Underside", the design system: the palette in both modes (`Hd.colors`, `HdArgb` for what Compose does not draw), the type scale and the three bundled font files (`core/design/FONTS.md`), shapes, the three springs, `HeadsDownTheme` (which also dresses `MaterialTheme`), the components (`BarButton`, `ActionDock`, `DisplayText`, `Choice`, `LedgerRow`, …), the hero slot with its static stand-in, `Theme.HeadsDown` with the splash, and the launcher icon's layers |
| `feature/shift` | `ShiftStateMachine`, accelerometer `FaceDownDetector`, per-round `HeartbeatTicker`, `BoardRoundSource` (ORE `Board.round_id`), `CrankHeartbeatSink` (acks, link status), the shift journal (first pickup), the `specialUse` foreground service; the debug-only sensor lab |
| `feature/reveal` | The morning haul reveal: exact alarm aligned to the user's next alarm, full-screen intent, a 120 Hz board replay, counts, effective price against market, streak, share grid, explorer link |
| `feature/oem-keepalive` | HyperOS/MIUI detection, Autostart and "No restrictions" deep links with fallbacks, Doze exemption, "killed by the OS" health check |
| `surface/tile` | Quick Settings tile and the non-exported translucent MWA trampoline |
| `surface/notification` | Ongoing shift notification: Android 16 Live Update (`ProgressStyle` + promoted ongoing), with a fallback on 14/15 |
| `surface/widget` | Glance 1.2 widgets. **Rig**: heat (cold / armed / hot / cooling / frozen) with a five-pixel heat bar, a RemoteViews `Chronometer` that ticks the shift with no app updates, the last on-chain haul and the streak. **Crew**: a marked placeholder ("PREVIEW · NOT LIVE") until rooms exist. The Heads Down palette by default (Material You only if `preferBrandColors` was turned off), generated previews on Android 15+ |
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
  (a drift-checked copy in `src/test/resources/golden`) and rebuilds the 31 vectors the phone can
  build (19 of the 32 tags: all but the admin, governance and crank ones): data bytes and ordered account metas (pubkey,
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

## Run it for real, without a phone

`scripts/devstack/emulator-smoke.sh` (docs/DEVSTACK.md, "The app on an emulator") installs the
`localdev` APK on a running emulator and drives it end to end against the local stack: setup, a
Keystore rig key, heartbeats, an on-chain dig, a pickup and its BREAK. With `--wallet` and
Solana Mobile's test wallet it also runs everything a wallet signs: clock-in, clock-out, taking
SOL back, closing the rig, and a second clock-in over the tombstone. It found what unit tests had
not: a crash right after the wallet signed on a phone with no secure lock screen (the Keystore
refused the key that seals the wallet's session token), a plan window that ended after the user's
alarm, and a Focus Bond choice drawn one letter per line.

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
- **The plan window ends just before the user's alarm.** Inside the window a pickup is a BREAK,
  and the shift then seals as ended early (no streak, a Focus Bond forfeit). So a Night Shift's
  window is not a fixed length: it ends two minutes before `AlarmManager.getNextAlarmClock()`
  when that alarm is between 30 minutes and 14 hours away, and otherwise runs for the policy's
  eight hours (`ShiftWindow`). The home screen says until when a shift started now would run.
- **end_shift** when `Rig.shift_open` (@336) is set. A Frozen rig's clock-in carries
  `unfreeze_rig` first, signed by the wallet ("Unfreeze and clock in" on the home screen).
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
shift ends. On the first shift on mainnet the crank was restarted twice; the phone reconnected by
itself each time and its heartbeats were accepted again within the next round.

## Morning haul (contract B)

The reveal reads `GET /v1/rigs/{rig}/haul/latest` from the indexer for the bound rig (HTTPS
only, except loopback in localdev): rounds with dug masks and winning squares, dark rounds, digs,
SOL placed, executor fees, ORE mined (exact, from ORE's round events), the effective and market
price per ORE, streak before and after, and an explorer link. `first_pickup_ts` is not observable
on-chain, so the phone fills it from its own shift journal. A real haul updates the widget; a
`simulated` one is labelled and never does. With no haul the reveal says so and offers the
labelled sample night only on request.

**What a wallet's addresses can hold.** Anyone can send lamports to an address before its program
creates the account there, so a Rig, Focus Bond, ShiftLog, Automation, Miner or token address
that holds only lamports reads as "not created" (`ifCreated`), never as a broken account. The Rig
address can also hold the 32-byte tombstone `close_rig` leaves (INTERFACE §12.2): it reads as no
rig, and a clock-in over it registers again and resumes the tombstone's `shift_id` and
`hb_counter`, so the shift it arms and the message counter continue where the closed rig stopped.

## Clock-out (one wallet approval)

Home → "Clock out", or the morning reveal's "Clock out" button (after unlocking), opens a
non-exported screen. It first reads the chain (the Rig, ORE's Board,
the wallet's ORE Miner, ORE's Treasury and the Focus Bond on the current shift) and says what a
clock-out would do, in amounts, before the wallet opens. One MWA session then builds, from a
fresh read:

`[end_shift?] [SKR account?] [release_focus_bond?] [ORE claim_sol?] [ORE claim_ore?]`

- Sealing a shift writes its 128-byte ShiftLog, and the wallet that seals it pays the rent (about
  0.0013 SOL at today's rent). The screen states the amount, read from the cluster, and that
  `close_shift_log` can send it back to the payer from 30 days after the shift ended. The
  clock-in disclosure says the same in words, because a clock-in seals the previous shift.
- **end_shift** only when ending changes nothing for the worse: the shift would seal `completed`,
  or its outcome is already fixed (Broken, Frozen, or past its window). A shift inside its window
  is left open unless the user chooses "End the shift now". The screen states the lost streak and
  the forfeit of a Focus Bond beside that choice, and the composer refuses a forfeit the screen
  did not state.
- **release_focus_bond** with a `completed` seal. A bond that is forfeit is said to be forfeit;
  the phone sends nothing for it (forfeiting is permissionless).
- **claim_ore** only on "Claim all to my wallet". The default leaves the ORE in the user's own
  ORE Miner. The screen states what arrives and ORE's 10% refining fee on the unrefined part.
- Success is shown only when the signature is confirmed with `err == null`. A transaction that
  failed or expired, and one whose outcome is unknown, are worded differently. A wallet other
  than the rig's is refused before anything is built. A confirmed `end_shift` also stops the
  phone's shift service.
- Not offered yet: "buy the rest at market". Its transactions are built and tested
  (`ClockOutService`, `SwapLegBuilder`), but no swap provider is wired into the app.
- **Where it has run.** On the Redmi 14C on mainnet on 10 October 2026 the clock-out sealed a
  shift that an unlock had already broken, signed in Jupiter Mobile. After confirmation the
  screen read "Confirmed on-chain. Shift sealed as ended early." The wallet paid 1,305,480
  lamports: the ShiftLog's rent of 1,300,480 and the 5,000-lamport fee. `claim_ore`,
  `claim_sol` and `release_focus_bond` have not run on a phone.

## Taking SOL back and closing the rig (one wallet approval)

Home → "Take SOL back or close the rig" opens a non-exported screen that reads the wallet's ORE
Automation and its Rig, says what each holds, and offers two choices. Neither is on by default.

`[ORE automate(executor = none)?] [close_rig?]`

- **Take it back** (Revoke) is ORE's own `automate` with no executor: ORE closes the Automation
  and sends every lamport in it to the wallet. Nothing more is dug until the next clock-in, which
  creates the Automation again. It is never refused, whatever the rig is doing, and it works for a
  wallet with no rig bound to this phone: the screen asks the wallet which account it is
  ("Connect wallet"), so SOL left in an Automation can be taken back after a reinstall.
- **Close my rig** (`close_rig`) returns the Rig account's rent. The program requires Idle or
  Frozen; the app also requires no open shift and no Focus Bond still locked, because closing
  under either forfeits the bond ("Clock out first"). A rig that armed a shift or accepted a
  signed message leaves a 32-byte tombstone that keeps its own rent. The screen says that the
  streak and lifetime counts are erased and that it cannot be undone. A confirmed close unbinds
  the phone from the rig.
- Success is shown only when the signature is confirmed with `err == null`, and a wallet other
  than the one the screen was read for is refused before anything is built.
- **Where each has run.** "Take it back" ran on the Redmi 14C on mainnet on 10 October 2026,
  signed in Jupiter Mobile. The screen read "Your ORE Automation holds 0.00502704 SOL: 0.003564
  SOL not yet placed, and the account's rent" and afterwards "Confirmed on-chain. 0.00502704 SOL
  back in your wallet from the ORE Automation."; the Automation gave up 5,027,040 lamports and
  the wallet, which paid the 5,000-lamport fee, gained 5,022,040. "Close my rig" has run only on
  the emulator. On the phone its screen read "Your rig can be closed: 0.00178816 SOL of account
  rent comes back", and it was not sent: of the Rig's 2,600,960 lamports, 812,800 would stay as
  the rent of the 32-byte tombstone.

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

## Design system

`core/design` is the one module that may write a colour, name a font file or draw the mark.
`SingleSourceTest` fails if a palette literal appears in the app, the reveal or a surface, or if the
tile icon or the notification icon stops carrying the canonical glyph; `StillnessTest` fails if
anything asks for an animation that never ends (two known exceptions, each to go when its screen is
rebuilt: the home screen's ember breath and the reveal's loading spinner). The screens from before
the redesign still read `MaterialTheme` and their old colour names (`HdColors`, `RevealColors`,
`WidgetPalette`): those are shims over the new palette now, kept until each screen is rebuilt on the
components; their outlined buttons take `HdMaterial.controlBorder()`, because Material draws that
border with the hairline. `HdCompat` holds the three colours the new system has no role for (the
frozen blue, the cooling orange, a dim ember).

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

- The crank, registrar and indexer URLs are build properties with no default. All three run on
  Railway against mainnet (docs/MAINNET.md, "Services"); a build reaches them only when it is
  given their URLs (docs/DEPLOY.md, section 10).
- No release signing key exists. Release builds are unsigned, the one build that has run on a
  phone is a debug build, and the registrar accepts only that build's signing certificate
  (docs/DEPLOY.md, section 10.4).
- `ClockInPolicy`: build-time budgets and cost ceilings until the plan screen and price feed exist.
- "Buy the rest at market" (the Jupiter leg) and the nightly ORE target.
- `StubOreRoundSource` remains for tests only.

## Security notes

- Only `MainActivity` is exported, plus the tile service (`BIND_QUICK_SETTINGS_TILE`) and the two
  widget providers (they must receive `APPWIDGET_UPDATE`; they read nothing from the Intent).
  The trampoline, the clock-out and withdraw screens, reveal, receiver, shift service, the reveal's `RevealShareFileProvider` and,
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
