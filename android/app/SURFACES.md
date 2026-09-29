# Surfaces, ritual and the local devstack

This file documents the UX surfaces added to the Android app and the `localdev` build type. It is
written to be folded into `android/README.md` (outside this workstream's paths).

## Modules

| Module | What it does |
|---|---|
| `surface/widget` | Glance 1.2 widgets. **Rig**: heat (cold / armed / hot / cooling / frozen) with a five-pixel heat bar, a RemoteViews `Chronometer` that ticks the shift with no app updates, the last on-chain haul and the streak; tapping opens the app. **Crew**: a clearly marked placeholder (empty seats, "PREVIEW · NOT LIVE") until rooms exist. `RigWidgetUpdates` is the hook interface. Dynamic colour where available, the charcoal palette as fallback, generated previews on Android 15+ and static `previewLayout`s before that. |
| `surface/haptics` | The haptic language: arm thunk, cooling tick, reveal drumroll, Motherlode flourish. Composed primitives only when `areAllPrimitivesSupported` holds for every primitive in the cue, else an amplitude waveform, else an on/off pattern. Basic motors (the Redmi 14C) also get a generated CC0 thunk through SoundPool, only in normal ringer mode (`surface/haptics/SOUNDS.md`). `HapticGovernor` keeps it from ever buzzing per round. |
| `feature/reveal` | The morning haul reveal: a 120 Hz 5x5 board replay, rounds / digs / SOL placed / ORE mined, effective price per ORE against market with a route verdict, "buy the rest at market" (stub), the streak, and a spoiler-free share grid. `HaulRepository` is served by `FakeHaulRepository` (a deterministic SAMPLE night, labelled "not your data") until the indexer exists. |
| `feature/shift` (debug only) | The sensor lab for the pickup/bump classifier, in `src/debug`. |
| `app` | The shift observer that drives the widget and haptics, the polished home screen, the "Your phone's night shift" intro, and the `localdev` build type. |

## Widget

- The app's shift observer maps every snapshot onto `RigWidgetUpdates.onRig` (`ShiftSurfaces.widgetRig`).
  Neutral types keep every module below the app independent of the widget.
- A heat change renders. The per-round dark-rounds count is stored but never pushed: the chronometer
  ticks by itself, so a whole night costs three renders (armed, hot, cold).
- A fresh process reports the rig cold, and the widget's 30-minute `updatePeriodMillis` starts one,
  so a shift killed by the OS shows cold within half an hour. A running rig recorded before a reboot
  (`Settings.Global.BOOT_COUNT`) shows cold at once.
- `canDig` is false while heartbeats are unsigned or bound to the unregistered rig; the widget then
  says "no digs".
- The streak comes from the on-chain Rig, read after a confirmed clock-in. Hauls are pushed only when
  they are real (never the sample).

## Haptics

- During a shift there are exactly two cues, both caused by the user: one arm thunk when the rig first
  goes hot after arming, and a cooling tick when the user lifts the phone or wakes the screen.
  Re-going dark after cooling, a slipping charger, breaks, freezes and each round are silent.
- The drumroll plays with the reveal's replay; the flourish only for a real Motherlode share.
- `HapticGovernor` refuses reveal cues outside the reveal, spaces every cue, and caps a shift at two
  buzzes an hour, so even a buggy per-round caller gets a handful of buzzes a night.

## Reveal

- Opened by the exact alarm, or from the home screen ("Preview the morning reveal (sample night)").
- Asks the display for its fastest mode at the current resolution (120 Hz on the Redmi 14C). The
  replay is a pure function of elapsed time and is drawn in the draw phase only. "Remove animations"
  shows the final board at once.
- Share: a 1080x1350 PNG of where the rig dug plus dark time and streak (no amounts, prices,
  winning tiles or wallet), through a non-exported FileProvider with a one-off read grant, plus the
  same grid as emoji text. Over the lock screen it asks to unlock first.

## Sensor lab (debug and localdev builds only)

Home screen → "Sensor lab (debug)". Pick bump, pickup, slide or set-down, then record a session: the
accelerometer at 50 Hz (no gyroscope), a window from 2 s before to 3 s after every motion event, and
screen-on, screen-off and unlock as ground truth, in `filesDir/sensorlab/session-<id>.csv`. Sessions
can be relabelled, deleted, and exported as one labelled CSV through the share sheet.

Everything lives in `feature/shift/src/debug`. `src/release` has only a stub `SensorLab` that reports
the lab absent, the service also refuses to run in a non-debuggable app, and `feature/shift` runs its
release unit tests so `src/testRelease` can prove no lab class is compiled into release.

CSV columns: `row_type,window_id,t_ns,recv_ns,ax,ay,az,event`. `sample` rows carry
`SensorEvent.timestamp` and the delivery time (`elapsedRealtimeNanos`); `window_start`/`window_end`
bracket each window; `event` rows are `screen_on`, `screen_off`, `unlock`, `session_start`,
`session_end`. The export prefixes `session_id,label`.

## Local devstack (`localdev` build type)

For a validator and `hd-crank` on the laptop, reached from the phone over USB:

```sh
adb reverse tcp:8899 tcp:8899      # validator RPC
adb reverse tcp:8787 tcp:8787      # hd-crank intake (/ws)
./gradlew :app:installLocaldev     # xyz.headsdown.localdev, installs beside the debug app
# optional: -Pheadsdown.localdev.rpcUrl=http://localhost:8899 -Pheadsdown.localdev.crankUrl=ws://localhost:8787/ws
```

Only `localdev` may use cleartext, and only to `127.0.0.1` or `localhost` (`http` RPC, `ws` crank).
Debug and release still refuse `http://` and `ws://` at configuration time. Three layers:

1. **Gradle** (`app/build.gradle.kts`): cleartext is accepted only for the `headsdown.localdev.*`
   properties and only to those two hosts; never user-info, a query string or a fragment.
2. **Runtime**: `EndpointPolicy` re-checks `BuildConfig` in `AppModule` before any transport exists,
   and the loopback RPC transport and WebSocket uplink are compiled only into `src/localdev`.
3. **Manifest**: only `src/localdev` adds a network security config, permitting cleartext to exactly
   `127.0.0.1` and `localhost` (`includeSubdomains="false"`).

`app/scripts/check-endpoint-policy.sh` checks the Gradle side; `EndpointPolicyTest` and
`CleartextConfigTest` check the rest. The wallet still submits clock-in transactions through its own
RPC, so a local validator exercises the app's reads and the heartbeat uplink, not the wallet leg.

## Manifest changes to note in the security section

- New exported components: the two widget providers (they must receive `APPWIDGET_UPDATE`; they read
  nothing from the Intent and only re-render local state). Glance pulls in WorkManager, which adds
  services and receivers guarded by system permissions (`BIND_REMOTEVIEWS`, `BIND_JOB_SERVICE`,
  `DUMP`) and the `ACCESS_NETWORK_STATE` and `RECEIVE_BOOT_COMPLETED` permissions.
- The haptics add `VIBRATE`.
- Not exported: the reveal's `RevealShareFileProvider` and, in debug and localdev only, the sensor lab
  activity, service and `SensorLabExportProvider`.
- Only `localdev` sets `android:networkSecurityConfig`.
