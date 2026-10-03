# Heads Down: 3:00 demo shot list (Redmi 14C)

Draft for the founder, written 2026-09-29, readiness updated 2026-10-03. Filmed on the builder's only phone, a Redmi 14C
(HyperOS, Android 14, no gyroscope, virtual proximity sensor), which is not connected yet. Every
phone beat is therefore `[TBD after device test]` until it has been rehearsed on the device.

**Rules for this video**

- Never script a Motherlode. If one lands on camera, show it with its odds (`docs/ECONOMICS.md` §4).
- Caption every non-default setting on screen, for example "Demo setting: price ceiling raised".
- Never show simulated dashboard data without its SIMULATED banner (`dashboard/README.md`).
- Never add a sound or haptic in post that the app does not make.
- Keep events in real order, with round ids or block times visible, and never reorder them in
  the edit.
- Narrate facts only. The VO carries no text addressed to whoever watches or scores it.
- Wording follows `docs/ECONOMICS.md` §4 (dig, haul, bond, "the cheaper route").

---

## 1. Readiness: what must exist before filming

| # | Item | Status on `main` (2026-10-03) | Blocks | Owner |
|---|---|---|---|---|
| R1 | `heads_down` deployed on mainnet with tiny caps, Config initialized, Executor PDA funded | Not deployed on any cluster yet. The runbook is rehearsed on a local fork of mainnet (`docs/DEPLOY.md` §12); it waits for the deployer key to be funded | 0:22 to 1:44 | founder, program |
| R2 | Android instruction builders match the program | **Done.** Every instruction the phone sends is checked byte for byte against the program's golden vectors (`android/core/chain/src/test/kotlin/xyz/headsdown/core/chain/ix/GoldenInstructionsTest.kt`) | clock-in | android |
| R3 | Phone-to-crank heartbeat JSON | **Done.** One contract for both sides, tested over real sockets (`crank/README.md`), and the phone-less end-to-end run passes on a local fork of mainnet (`docs/DEVSTACK.md`) | trustless beat | android or crank |
| R4 | BREAK reaches the chain | **Done.** The crank lands phone-signed BREAK and FREEZE (`crank/README.md`) | cooling shown on-chain | crank or android |
| R5 | Redmi Keystore signature verified on-chain (TEE level, latency, screen-off signing) | Not run (`spikes/secp256r1/README.md`, "Still to do on the device") | trustless beat | android |
| R6 | Crank reachable over `wss://` from the phone | The crank URL is a build setting with no default host; it needs the Railway deployment (`docs/DEPLOY.md` §10) | trustless beat | founder, crank |
| R7 | Replay tool for the "dishonest crank" shot | **Done.** `hd-crank replay --signature <landed dig tx>` rebuilds the dig and resends it with a fresh blockhash (`crank/README.md`, Demo tools) | 0:45 to 1:22 | crank |
| R8 | Morning haul reveal | **Built, not on a device.** Exact alarm, full-screen reveal, rounds replayed from the indexer (`android/feature/reveal`) | 2:03 | android |
| R9 | Stack (Nearby tables, SKR bonds) | Built and tested in the program and the crank (`docs/SKR.md` §9). No screens in the app and no Nearby pairing yet. For the SKR beat, film the Focus Bond on the home screen | 2:15 (stretch) | program, android |
| R10 | Audible arm cue | **Built, not on a device.** A composed haptic with an audible fallback for motors that cannot compose (`android/surface/haptics`) | 0:00 | android |
| R11 | Dashboard on real data | Indexer runs the simulated dataset until the program is deployed (`services/indexer/README.md`) | 2:35 | indexer |
| R12 | Debug-only sensor view (live tilt angle and face-down verdict), excluded from release | **Built.** The Sensor lab, in debug builds only (`android/README.md`) | 1:44 | android |
| R13 | Plan settings for the take: `lease_rounds = 1` and a raised, disclosed ceiling | **Done.** Build properties `headsdown.policy.*` (`android/README.md`) | trustless beat | android |

---

## 2. Two chain setups

**A. Mainnet (preferred).** The program is deployed with tiny caps (for example `cap_shift` 0.01 SOL,
0.001 SOL digs). `hd-crank run` runs on the founder's laptop with its intake behind a TLS tunnel or
on Railway, because the app accepts only `wss://` (`android/README.md`). Solscan shows every
transaction. ORE rounds advance on their own (about 78 s, `docs/ORE.md` §2).

- **Gate.** At today's prices the calibrated gate stays shut (`docs/ECONOMICS.md`, Scenario A;
  `hd-crank check` read `ema_ev` 657,911,497 lamports/ORE). For the take where a dig must land, raise
  the plan and wallet ceilings above the live `ema_ev`. **Disclose it in the VO and a caption.**
  The cost is small: a 0.001 SOL dig loses about 10.5% to ORE fees plus the fixed fee.

**B. Local devstack (rehearsal and fallback).** `solana-test-validator` with the live ORE binary
and accounts loaded, as in `crank/tests/e2e_validator.rs`, using fixtures from
`spikes/ore-executor/fetch-fixtures.sh`. Use Solana Explorer with a custom cluster:
`https://explorer.solana.com/tx/<sig>?cluster=custom&customUrl=http%3A%2F%2Flocalhost%3A8899`.

- **Limits.** The e2e setup rewrites the fixture Board to one open window, and nothing in it
  advances ORE rounds, so plan for single-round beats only: a dig lands, then the replay is skipped
  with `StaleHeartbeat`. The order is reversed from the script, so caption it "local validator, mainnet
  ORE loaded".
- **The phone cannot join the devstack as-is.** The app refuses non-`https` RPC and non-`wss` crank
  URLs, so the "phone" on the devstack is the e2e test's simulated phone. Rehearse chain-side shots
  here, then film phone shots on A.

---

## 3. Shot list

| Time | Picture (A-roll) | On-screen text | VO | B-roll / capture |
|---|---|---|---|---|
| 0:00–0:08 | Macro, dark bedroom, 23:0x. Redmi on a nightstand, charger in. Thumb swipes down and taps the "Heads Down" tile next to Do Not Disturb. The wallet sheet appears; fingerprint. The phone goes face-down. | none | "Most phones spend the night doing nothing. Mine works a night shift." | Camera, not screen capture: wallet apps can block recording. Room audio for the arm cue once R10 ships. |
| 0:08–0:22 | Slow push-in on the face-down phone. | "Heads Down · powered by ORE" · "Any Android · verified Seeker tier" | "This is Heads Down. Face-down, my phone digs ORE from my own ORE Automation. Pick it up, and the rig goes cold. Only this phone's hardware key can switch it on: not a server, not me." | none |
| 0:22–0:45 | Screen capture of the same clock-in: tile, wallet approval (camera if the wallet blocks capture), "Rig armed" notification. Cut to Solscan: one transaction with ORE `automate`, `register_rig` (first run), `set_caps`, `arm_shift`. | "One approval · one transaction" · the tx signature | "I tap the Quick Settings tile. One wallet approval funds tonight's capped shift inside ORE's own Automation and arms the rig. That's the only signature tonight. This is a Redmi 14C. Any Android with a mobile wallet can run a guest rig, and Seeker owners get a tier verified on-chain by their Seeker Genesis Token." | `scrcpy` recording started **before** arming (§5). |
| 0:45–1:22 | **Trustless beat** (step by step in §4). Split screen: phone on the left, terminal and Solscan on the right. | "Round N · dig ✓" → "Phone lifted" → "Replayed heartbeat → RigSkipped · 7 StaleHeartbeat · no ORE instruction" → "Round N+1 · fresh heartbeat · dig ✓" · "Demo setting: price ceiling raised for this take" | "Every ORE round, about 78 seconds, the phone signs a heartbeat with a key that never leaves its Keystore. A crank can dig for my rig only if that heartbeat verifies on-chain, through Solana's secp256r1 precompile. Now I pick the phone up. It stops signing. So I play a dishonest crank: I copy my last heartbeat off the chain and resend it. The program skips my rig: stale heartbeat. Nothing reaches ORE. Phone back down. Next round, a fresh heartbeat, and the dig lands in ORE." | Terminal: `hd-crank` log lines for accepted heartbeats; the replay tool's output (R7). |
| 1:22–1:44 | Chart: `ml/forecaster/figures/eff_price_vs_budget.png`, then the live gate value from `hd-crank check`. | "58,801 ORE rounds · 47 nights" · "every round: +36% to +90% vs buying" · "gated chunks + buy the rest: −1.4% to −3.0%" | "For this take I raised my price ceiling so a dig could land on camera. Normally the gate decides. In our backtest of 58,801 ORE rounds, digging a little every round cost 36 to 90 percent more than buying. Gated chunks plus buying the rest came out 1.4 to 3 percent cheaper. Modest, and we say so." | `ml/forecaster/RESULTS.md` TL;DR figures. |
| 1:44–2:03 | The detector on a table: phone face-down, a knock, no change; a lift, cooling. Use the debug sensor view if it exists (captioned), else the terminal. | "On-device · accelerometer only (no gyroscope)" · "Forecaster: advisory (lost to the on-chain rule)" · "Can only tighten wallet-signed limits" | "The AI runs on the phone. Pickup detection uses only the accelerometer; this phone has no gyroscope. A learned pickup-versus-bump classifier is next. Our cost forecaster lost to the simple on-chain rule, so it only explains the night. Nothing on the phone can raise the limits my wallet signed." | `ml/forecaster/MODEL_CARD.md` card as a 2 s insert. |
| 2:03–2:15 | **If R8 ships:** 07:00 alarm opens the full-screen haul reveal. **Fallback:** the "Rig cold · Shift ended · N rounds dark" notification, then Solscan: the `end_shift` tx and its ShiftLog. | reveal: "rounds dark · digs · price per ORE vs market" / fallback: "ShiftLog on-chain" | Reveal: "At my alarm, the haul reveal replays the night: rounds dark, digs landed, and what I paid per ORE against the market." Fallback: "In the morning the shift closes on-chain: dark rounds, rounds dug and SOL deployed, in a ShiftLog anyone can read." | Real footage from a real night only. Never a staged Motherlode. |
| 2:15–2:35 | **Stretch (R9 ships):** a dinner table, friends' phones pair over Nearby, each posts an SKR bond; a knock, all stay hot; a reach, one rig breaks; Solscan settle. **Alternative (default today):** three friends' Android phones as guest rigs on a table; knock the table; one friend reaches for their phone. | Stretch: "SKR bond · 80% of forfeits to finishers · 20% buys ORE for ORE's bury" / Alternative: "3 phones · 3 guest rigs · knock ≠ pickup" | Stretch: "SKR makes it social. At a table, each friend bonds SKR on keeping their phone down. A knock on the table doesn't count; a pickup does. Finishers take back their bonds plus most of the forfeits, and the rest buys ORE that ORE buries." Alternative: "Three friends, three different Android phones, all guest rigs. I knock the table: every rig keeps heartbeating. One of us reaches for a phone: that rig cools. SKR-bonded table contests build on exactly this; they're designed, not in this build." | Terminal: per-rig heartbeat acceptances at the crank (needs R3). |
| 2:35–2:52 | Dashboard scrolled: `/` tiles, `/share/`, `/digs/` with Solscan links. Then the repo: `docs/THREAT_MODEL.md`, `crates/`. | Real data: tile values with links. Simulated: the SIMULATED banner stays in frame. | "Every number on the dashboard comes from chain data and links to the transaction behind it. The program, the crank, the Seeker and P-256 crates and the threat model are open source, with a written worst case for every key." If simulated, add first: "This is still the labelled simulated dataset." | `dashboard/README.md` run steps. |
| 2:52–3:00 | The face-down phone in the dark. | "Heads Down. Put it down. It digs." · "Powered by ORE" · store status `[TBD]` | "Heads Down. Put it down. It digs. Powered by ORE." | none |

VO total: about 405 words, about 2:32 at 160 words a minute, which leaves room for the cold open,
the pauses and the close.

---

## 4. The trustless beat, step by step (setup A, mainnet)

1. **Before the take.** Arm a shift with `lease_rounds = 1` in the plan (R13), so no lease carries
   over after the lift. Raise the ceiling (R13; caption it). Start `scrcpy` and the terminal. Put the phone
   face-down; `adb shell dumpsys power | grep mWakefulness` shows `Asleep`.
2. **Round N.** The phone heartbeats; late in the round the crank digs (it waits until about 20
   slots, about 8 s, before `end_slot`: `crank/README.md`). On Solscan the dig tx shows a
   `Secp256r1SigVerify1111111111111111111111111` instruction, the `heads_down` dig, and an inner
   instruction into ORE `oreV3EG1i9BEgiAJ8b177Z2S2rMarzak4NMv1kULvWv`.
3. **Lift.** Lift the phone and press power once to light the lock screen. **Do not unlock:**
   unlocking breaks the shift at once (`ShiftStateMachine.kt`). The notification reads "Rig
   cooling · Put it back face-down to keep the shift alive" (`RigNotificationCopy.kt`). There is no
   countdown, by design.
4. **Replay (the dishonest crank).** Run the replay tool (R7) on round N's dig tx. On Solscan the new
   transaction **succeeds but deploys nothing**: there is no inner ORE instruction, and the log has
   a `Program data:` line holding `RigSkipped` with error 7 (`StaleHeartbeat`). A batched dig
   skips a failing rig instead of failing the whole transaction (`programs/heads-down/INTERFACE.md`,
   `dig`), so say "skipped", not "failed". The heartbeat's counter was already used, and the
   program checks the counter before the lease, the round and the gate
   (`programs/heads-down/program/src/instructions/mod.rs`, `apply_heartbeat`; test
   `heartbeat::replayed_heartbeat_is_rejected`).
5. **Decode on screen.** `RigSkipped` is 45 bytes: tag `2` | rig (32) | `round_id` u64 | `error`
   u32 LE, so the event ends `07 00 00 00` (`programs/heads-down/program/src/events.rs`). Caption the
   decoded fields next to the raw base64 line.
6. **Put it down within 10 s** and press power to turn the screen off. After 10 s of cooling the
   shift breaks and needs a new clock-in (`ShiftStateMachine.kt`, `DEFAULT_GRACE_MILLIS`).
7. **Round N+1.** A fresh heartbeat; the dig lands with the same three instructions as step 2. Keep
   Solscan's block time or the round id in frame.

**If the gate is shut and the ceiling was not raised,** step 7 shows `RigSkipped` with error 1
(`CostGate`): the phone was down and the price was wrong. That is an honest beat too; use it with
the 1:22 segment and caption "gate shut: buying is cheaper right now".

---

## 5. Filming notes for the Redmi 14C

- **Weak vibration motor.** Don't rely on haptics on camera. Once R10 ships, use the app's audible
  arm cue, recorded with a microphone near the nightstand. Until then, the cold open has room tone
  only, and the "Rig armed" notification carries the moment.
- **No always-on display expected.** The Redmi 14C has an LCD panel, so plan for no AOD `[verify in
  Settings → Lock screen]`. "Rig hot" only exists with the screen off, and lighting the screen
  cools the rig, so the phone itself can never show "Rig hot" on camera. Show it from the laptop
  instead: `adb shell dumpsys notification --noredact` prints the notification text while
  `dumpsys power` shows `mWakefulness=Asleep`. For the Live Update chip look, use the Android 16
  emulator as clearly captioned B-roll ("Android 16 emulator").
- **What wakes the screen cools the rig.** Turn on Do Not Disturb. Turn off raise-to-wake and
  double-tap-to-wake for the shoot, so every wake on camera is deliberate.
- **Charging.** Night Shift requires the charger. USB to the laptop counts, but for the hero shot use
  the wall charger and wireless adb (`adb pair`, `adb connect`).
- **HyperOS.** Turn on Autostart and battery "No restrictions" for Heads Down, as the in-app
  keep-alive onboarding asks (`android/feature/oem-keepalive`). To let scrcpy send input, also enable "USB
  debugging (Security settings)" in Developer options, and "Install via USB" (needs a Mi account)
  for sideloading.
- **Wallet screens.** Solflare and Phantom may block screen capture on signing screens. Film the
  approval with a camera; the explorer shows the result.
- **Frame.** The phone's native resolution is 720 × 1640 `[verify: adb shell wm size]`. Camera
  footage at 30 fps; screen recordings at native size.

---

## 6. Capture commands

```sh
adb devices                                   # the Redmi is listed as "device"
adb shell wm size                             # native resolution for the edit
adb shell settings put system show_touches 1  # show taps in screen recordings (set 0 afterwards)

# On-device recording: max 180 s, no audio, black while the screen is off.
adb shell screenrecord --bit-rate 12000000 --time-limit 180 /sdcard/hd-02-clockin.mp4
adb pull /sdcard/hd-02-clockin.mp4 footage/

# Mirror and record on the laptop. scrcpy turns the phone's screen ON when it starts, which cools
# a live rig, so start it before arming, or pass --no-power-on.
# Never use --turn-screen-off, --stay-awake or --power-off-on-close during a shift.
scrcpy --no-power-on --show-touches --max-fps=60 --video-bit-rate=12M --record=footage/hd-02-clockin.mp4
# Check the flags against `scrcpy --help` for your version.

# Show the rig's state without lighting the screen.
adb shell dumpsys power | grep mWakefulness                                   # Asleep = screen off
adb shell dumpsys notification --noredact | grep -A 30 "pkg=xyz.headsdown" | grep -E "android.title|android.text"

# Debug builds only (release strips android.util.Log).
adb logcat --pid="$(adb shell pidof xyz.headsdown)"

# Crank side (setup A).
./target/release/hd-crank --config crank.toml check        # live round, gate value, breaker state
./target/release/hd-crank --config crank.toml --keypair ~/.config/hd-crank/id.json run
curl -s localhost:<port>/metrics | grep -E "heartbeats_accepted|digs_landed|digs_skipped"
```

Desktop: OBS with two sources (the scrcpy window and the browser) for the split screen. Keep the
explorer's block time visible.

---

## 7. VO transcript (for captions and the transcript reader)

> Most phones spend the night doing nothing. Mine works a night shift.
>
> This is Heads Down. Face-down, my phone digs ORE from my own ORE Automation. Pick it up, and the
> rig goes cold. Only this phone's hardware key can switch it on: not a server, not me.
>
> I tap the Quick Settings tile. One wallet approval funds tonight's capped shift inside ORE's own
> Automation and arms the rig. That's the only signature tonight. This is a Redmi 14C. Any Android
> with a mobile wallet can run a guest rig, and Seeker owners get a tier verified on-chain by their
> Seeker Genesis Token.
>
> Every ORE round, about 78 seconds, the phone signs a heartbeat with a key that never leaves its
> Keystore. A crank can dig for my rig only if that heartbeat verifies on-chain, through Solana's
> secp256r1 precompile. Now I pick the phone up. It stops signing. So I play a dishonest crank: I
> copy my last heartbeat off the chain and resend it. The program skips my rig: stale heartbeat.
> Nothing reaches ORE. Phone back down. Next round, a fresh heartbeat, and the dig lands in ORE.
>
> For this take I raised my price ceiling so a dig could land on camera. Normally the gate decides.
> In our backtest of 58,801 ORE rounds, digging a little every round cost 36 to 90 percent more
> than buying. Gated chunks plus buying the rest came out 1.4 to 3 percent cheaper. Modest, and we
> say so.
>
> The AI runs on the phone. Pickup detection uses only the accelerometer; this phone has no
> gyroscope. A learned pickup-versus-bump classifier is next. Our cost forecaster lost to the
> simple on-chain rule, so it only explains the night. Nothing on the phone can raise the limits my
> wallet signed.
>
> In the morning the shift closes on-chain: dark rounds, rounds dug and SOL deployed, in a ShiftLog
> anyone can read.
>
> Three friends, three different Android phones, all guest rigs. I knock the table: every rig keeps
> heartbeating. One of us reaches for a phone: that rig cools. SKR-bonded table contests build on
> exactly this; they're designed, not in this build.
>
> Every number on the dashboard comes from chain data and links to the transaction behind it. The
> program, the crank, the Seeker and P-256 crates and the threat model are open source, with a
> written worst case for every key.
>
> Heads Down. Put it down. It digs. Powered by ORE.

Swap in the reveal and Stack lines from §3 if R8 or R9 ship. Keep each claim in the transcript
backed by a file listed in `docs/pitch/DECK.md`.
