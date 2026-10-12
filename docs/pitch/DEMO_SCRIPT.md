# Heads Down: 3:00 demo shot list (Redmi 14C)

Draft for the founder, written 2026-09-29, readiness updated 2026-10-10 after the mainnet deploy
and the first shift on the phone ([MAINNET.md](../MAINNET.md)). It is to be filmed on the
builder's only phone, a Redmi 14C (model 2409BRN2CA, Android 16, HyperOS 3, no gyroscope, virtual
proximity sensor). On 10 October 2026 that phone ran these beats once, on mainnet, with Jupiter
Mobile as the wallet: a clock-in, heartbeats and five digs, an unlock and its BREAK, a clock-out,
and taking back the SOL left in the ORE Automation. The table below says for each item what has run, with the
transaction or the file, and what has not.

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

| # | Item | Status on 2026-10-10, with its evidence | Blocks | Owner |
|---|---|---|---|---|
| R1 | `heads_down` deployed on mainnet with tiny caps, Config initialized, Executor PDA funded | **Done, 10 October 2026.** Deployed at slot 455,359,196 ([transaction](https://solscan.io/tx/4naPfgTXDKRxG7NhunoNKVBWkCwuvdJoyBGytmebCpuDzdrP6Gm4UwxNYm5r5zzUu8vuZKgYiaFp1BzrTwH2Pae2)). `initialize_config` ([transaction](https://solscan.io/tx/3R4UnCecw7BuzAvLZURtktLRnh6iEboPE6CpX35NdX7sKzjJcL4g84i1uxQdwwWHPioyEVcG3uBp4cfAhQFzJzUs)): executor fee 10,000 lamports, crank fee 7,000, not paused. Executor PDA funded with 1,450,240 lamports ([transaction](https://solscan.io/tx/5pPKTrBQLPUY8uk2Lf1mMysNAy4EwmgAWi7mDYwTEnwm5Xp5LdQtXptEnq3yf3JkWKuoPQ8EEpDNtbJ6bDjaAeK3)). The caps are each wallet's own, set at clock-in by the build of R13. One key can upgrade the program at once | 0:22 to 1:44 | founder, program |
| R2 | Android instruction builders match the program | **Done.** Every instruction the phone sends is checked byte for byte against the program's golden vectors (`android/core/chain/src/test/kotlin/xyz/headsdown/core/chain/ix/GoldenInstructionsTest.kt`). On mainnet the deployed program accepted the phone's [clock-in](https://solscan.io/tx/4DvPBhh4UiEhezYPmjcJvHeqSGGfCAj9prnKEFPMGaGRe9sGg7r9A2SMPVTo4UYXjeEkHUc1WCA7VRdznZuPaQht) and [clock-out](https://solscan.io/tx/3VfbzUZHzAYj468iXo2bnsqRfAcrTMt76dHzWFT51ZnrvseFF1bYEzo6fAqurTG8tB9AM1cPRYsXtJK2R25LuTUm), and ORE accepted its [take-back](https://solscan.io/tx/46NXJnX54wUdTgBVBGwfid8NQeqRL3LkGCyFkhtqJuVVHwp6R2UbnNxxhTSv8FHgtuyJupEPRQi4Frqf4NAjDcFF) | clock-in | android |
| R3 | Phone-to-crank heartbeat JSON | **Done, and run on mainnet.** One contract for both sides, tested over real sockets (`crank/README.md`); the phone-less end-to-end run passes on a local fork of mainnet (`docs/DEVSTACK.md`); the real app on an Android 14 emulator had its Keystore-signed heartbeat dig a round on that fork (`scripts/devstack/emulator-smoke.sh`). On 10 October 2026 the crank on Railway took the Redmi's heartbeats and dug five rounds with them ([the first dig](https://solscan.io/tx/25nBRCP6GExrKG4EDszGrKfWN9yYgJMopgnawTGi2Z9XNGj6UCtZgE6HFJN7rJXvu2C3VMjhh7bpXZnQg8DVzovY)). The first clock-in's shift got no heartbeat to the crank before it was sealed: the app had not heard that its clock-in landed, so the phone never started that shift (repaired in the app on 11 October: the repair has run on the emulator, and on the phone the failure has not come back in three clock-ins; `docs/MAINNET.md`) | trustless beat | android or crank |
| R4 | BREAK reaches the chain | **Done, and run on mainnet.** After the phone was picked up and unlocked, the crank landed its phone-signed BREAK: `ShiftBroken`, reason 8 ([transaction](https://solscan.io/tx/63FZYbEyJyePgGMMHqAxTvwGD5vAvFUggnbkfhmummhPjAzADctceYYJE5UNHp4bFBshJTcGNPDFocE7xw92yqVb)). Not run on mainnet: a BREAK from a lift without an unlock, and a FREEZE (`crank/README.md`) | cooling shown on-chain | crank or android |
| R5 | Redmi Keystore signature verified on-chain (TEE level, latency, screen-off signing) | **Verified on mainnet, 10 October 2026.** The rig key is in the phone's TEE (attestation level 1 on the Rig, from the live registrar's voucher), and its signatures passed the secp256r1 precompile in five digs and one BREAK (R3, R4). Not measured: sign latency. No signature from the device has been added to the test vectors (`spikes/secp256r1/README.md`, "Still to do on the device") | trustless beat | android |
| R6 | Crank reachable over `wss://` from the phone | **Done.** The crank runs on Railway since 10 October 2026, 18:48 UTC. Intake: `wss://crank-production-21c2.up.railway.app/ws`; health: `https://crank-production-21c2.up.railway.app/healthz`. The phone reconnected by itself after each of two crank restarts during the shift | trustless beat | founder, crank |
| R7 | Replay tool for the "dishonest crank" shot | **Tool done; not run on mainnet.** `hd-crank replay --signature <landed dig tx>` rebuilds the dig and resends it with a fresh blockhash (`crank/README.md`, Demo tools). What mainnet has shown, unstaged, is the same refusal: the crank's own second attempt with a heartbeat that had already landed, skipped with error 7 ([one](https://solscan.io/tx/4EJvGg7akbzgQRbT2cYteaHCogpnEaBpP2ncTHLFS1mtCWt5e9aVFJEmeiFgbaShHC7H7t2EkpwxQYKpcWq6PUpM), [two](https://solscan.io/tx/baNzqcBzu49Rer1rnjcMugBHdPHp7F6wSLQTUUH22hyGSddQZaVLR57eUZNjhi2fCEzdYjZZcrUka4GTcGCbfeo)) | 0:45 to 1:22 | crank |
| R8 | Morning haul reveal | **Built; not run on the device.** Exact alarm, full-screen reveal, rounds replayed from the indexer (`android/feature/reveal`). No shift on the phone has lasted to an alarm | 2:03 | android |
| R9 | Stack (Nearby tables, SKR bonds) | Built and tested in the program and the crank (`docs/SKR.md` §9). No screens in the app and no Nearby pairing yet. No SKR instruction has run on mainnet. For the SKR beat, film the Focus Bond on the home screen; it has not run on the phone yet | 2:15 (stretch) | program, android |
| R10 | Audible arm cue | **Built.** A composed haptic with an audible fallback for motors that cannot compose (`android/surface/haptics`). Whether it sounded on the Redmi on 10 October was not recorded | 0:00 | android |
| R11 | Dashboard on real data | **Done.** The indexer and the dashboard run on Railway against mainnet. On 2026-10-10 at 20:44 UTC the indexer's `/v1/summary` served the dataset `mainnet`, not the simulator: 1 rig (a guest rig), 2 shifts ended, 5 rounds dug, 5,000,000 lamports deployed, 0 ORE mined, and 3 skips (2 `StaleHeartbeat`, 1 `RoundNotActive`). That one rig is the founder's: say so on camera | 2:35 | indexer |
| R12 | Debug-only sensor view (live tilt angle and face-down verdict), excluded from release | **Built.** The Sensor lab, in debug builds only (`android/README.md`). On the Redmi: not recorded | 1:44 | android |
| R13 | Plan settings for the take: `lease_rounds = 1` and a raised, disclosed ceiling | **Done, and used on 10 October 2026.** Build properties `headsdown.policy.*` (`android/README.md`). The build that ran: a plan of 1.0 SOL per ORE under a wallet ceiling of 1.2, 0.005 SOL a shift, 0.035 SOL a week, 0.001 SOL digs on 4 squares, lease 1 | trustless beat | android |

---

## 2. Two chain setups

**A. Mainnet (preferred, and how the first shift ran on 10 October 2026).** The program is deployed
(R1). `hd-crank` runs on Railway, built there from `deploy/railway/crank/Dockerfile`, with its
intake at `wss://crank-production-21c2.up.railway.app/ws`; the app accepts only `wss://`
(`android/README.md`). The wallet on the phone is Jupiter Mobile. The app is the demo build of
R13: a debug build with mainnet endpoints, 0.001 SOL digs on 4 squares and 0.005 SOL a shift, so
a shift is five digs and then the crank sends nothing more for the rig. Solscan shows every
transaction. ORE rounds advance on their own (about 78 s in `docs/ORE.md` §2; on 10 October 2026
the rounds of the first shift ended 289 to 292 slots apart, about 63 s at that evening's slot
time).

- **Gate.** At these prices the default build's gate stays shut: its plan ceiling is 0.53 SOL per
  ORE, and ORE's cost figure (`ema_ev`) was 0.690 to 0.704 SOL per ORE during the first shift
  (691,153,003 to 697,661,524 lamports per ORE in the five `RigDug` events). The default build
  would have dug nothing that evening. The demo build raises the plan to 1.0 and the wallet
  ceiling to 1.2. **Disclose it in the VO and a caption.** What it cost that evening: each
  0.001 SOL dig also paid the 10,000-lamport executor fee, and for each of the four rounds
  settled that evening ORE sent 0.000891 SOL back. The fifth round, 435,228, was settled by the
  next evening's first dig and sent back the same. No ORE came out of the five; the fifteen
  digs of 11 October brought the rig's first 0.0019 ORE (`docs/MAINNET.md`, the third evening).

**B. Local devstack (rehearsal and fallback).** `scripts/devstack/up.sh` (docs/DEVSTACK.md): a
local validator with the live ORE binary and accounts loaded, the program, the crank and the
indexer. Use Solana Explorer with a custom cluster:
`https://explorer.solana.com/tx/<sig>?cluster=custom&customUrl=http%3A%2F%2Flocalhost%3A8899`.

- **Limits.** Its round driver keeps ORE rounds moving, so the beats can run in the script's
  order. Caption it "local fork of mainnet".
- **The phone joins through the `localdev` build.** `scripts/devstack/phone.sh` forwards the ports
  over USB and `./gradlew :app:installLocaldev` installs the build that may use them
  (android/README.md). Debug and release builds still refuse non-`https` RPC and non-`wss` crank
  URLs.

---

## 3. Shot list

Rewritten on 12 October 2026 for the live form. The four criteria are Stickiness and product-market
fit, User experience, Innovation and Presentation, 25 each, and the platform's AI coach reads a
**transcript** of this video. So the voice-over says what ran, where and when, and every sentence
stands without its picture. The film opens on the morning, because that is the moment the product
exists for.

The take is the bonded shift of 12 October on the Redmi 14C (build 0.1.2, mainnet). Numbers in
square brackets are filled in from that run and from [MAINNET.md](../MAINNET.md) before recording
the voice-over. A clause marked (if built) is cut when that screen is not in the filmed build.

| Time | Picture | On-screen text | VO (section 7) |
|---|---|---|---|
| 0:00–0:10 | Daylight, a desk. The Redmi face-down on its wall charger. The alarm rings. A hand dismisses it without unlocking. Camera, not screen capture. | "Redmi 14C · Solana mainnet · 12 October 2026" | 1 |
| 0:10–0:38 | The reveal page opens over the lock screen: "Not sealed yet" and its three facts. Tap Clock out, the sheet, the wallet approval (camera: wallets can block capture), "Reading the haul", then the board replay and the numbers. A cut inside the wait is labelled with the seconds it removes. | "One approval" · the transaction signature | 2 |
| 0:38–0:58 | The start of the same shift, in order: charger in, alarm set, Clock in, the wallet approval, the phone laid down, the rig hot. | "One approval a shift" · "Inside limits the wallet signed" | 3 |
| 0:58–1:18 | The streak on Home and on the reveal. The widget on the launcher. The Nights screen (if built). The share card. | "Streak stored on-chain" · "2 nights off a month" | 4 |
| 1:18–1:45 | Solscan: one dig transaction, with the secp256r1 check, the program's dig and ORE's deploy. Then the plain shift of the same day: the phone lifted from the desk, the cooling cue, and the `ShiftBroken` transaction. | "Signed in the phone's secure hardware" · "Checked by Solana in the same transaction" | 5 |
| 1:45–2:05 | Home's Focus Bond chooser at 10 SKR. The line "It also locks your 10 SKR Focus Bond." Then Solscan: the same 10 SKR back in the wallet. | "10 SKR locked · the same 10 SKR back" | 6 |
| 2:05–2:25 | Home's line with ORE's cost figure and the ceiling (if built). The reveal's price row with the market price beside it. The clock-out sheet's "Buy ORE". | "Demo setting: price ceiling raised for this film" | 7 |
| 2:25–2:48 | The public dashboard, a tile clicked through to Solscan. The table in MAINNET.md. | "On mainnet since 10 October 2026" · "Not run on a Seeker · no third-party audit · one user" | 8 |
| 2:48–3:00 | The founder, or the phone face-down. | "Heads Down. Put it down. It digs." · "Powered by ORE" | 9 |

**What has to be on film from today, and when to shoot it**

1. During the plain shift on 0.1.2: the lift, the cooling cue and the phone picked up (shot 5).
2. Before the bonded clock-in: the bond chooser, the alarm being set, the clock-in and its approval,
   the phone going down (shots 3 and 6).
3. At the alarm: one unbroken camera take from the ringing alarm to the haul (shots 1 and 2). Do
   not unlock before the reveal page appears: an unlocked phone gets a small notification instead.
4. After it: Home, widget, Nights, share card, dashboard, Solscan (shots 4, 7, 8), as screen capture.

**Captions.** Upload the caption file with the video: automatic captions will mangle ORE, SKR and
secp256r1. Its text is the voice-over below, as recorded.

---

## 4. The trustless beat, step by step (setup A, mainnet)

1. **Before the take.** Arm a shift with `lease_rounds = 1` in the plan (R13), so no lease carries
   over after the lift. Raise the ceiling (R13; caption it). Start `scrcpy` and the terminal. Put the phone
   face-down; `adb shell dumpsys power | grep mWakefulness` shows `Asleep`.
2. **Round N.** The phone heartbeats; late in the round the crank digs. On the running service
   the crank has, since 10 October 2026, begun sending 80 slots before `end_slot`: a setting on
   the service, changed that evening because a dig sent 20 slots before the end landed after it
   (`crank/README.md` has the crank's defaults). 80 slots were about 17 s at that evening's slot
   time (about 0.218 s a slot: 4,431 slots in 967 s). On Solscan the dig tx shows a
   `Secp256r1SigVerify1111111111111111111111111` instruction, the `heads_down` dig, and an inner
   instruction into ORE `oreV3EG1i9BEgiAJ8b177Z2S2rMarzak4NMv1kULvWv`. From the second dig of a
   shift on, the transaction also carries ORE's checkpoint of the round before, ahead of the dig.
3. **Lift.** Lift the phone and press power once to light the lock screen. **Do not unlock:**
   unlocking breaks the shift at once (`ShiftStateMachine.kt`). This is what happened on
   10 October: the phone was picked up and unlocked, a BREAK with reason 8 landed, and the shift
   could only be sealed (R4). The notification reads "Rig
   cooling · Put it back face-down to keep the shift alive" (`RigNotificationCopy.kt`). There is no
   countdown, by design. The only BREAK that has landed on mainnet is that unlock's: a lift that
   cools the rig and a put-down that resumes it have not been seen there yet.
4. **Replay (the dishonest crank).** Run the replay tool (R7) on round N's dig tx. On Solscan the new
   transaction **succeeds but deploys nothing**: there is no inner ORE instruction, and the log has
   a `Program data:` line holding `RigSkipped` with error 7 (`StaleHeartbeat`). A batched dig
   skips a failing rig instead of failing the whole transaction (`programs/heads-down/INTERFACE.md`,
   `dig`), so say "skipped", not "failed". The heartbeat's counter was already used, and the
   program checks the counter before the lease, the round and the gate
   (`programs/heads-down/program/src/instructions/mod.rs`, `apply_heartbeat`; test
   `heartbeat::replayed_heartbeat_is_rejected`).
   `hd-crank replay` has not been run on mainnet. The outcome it should show has been seen there
   without staging: on 10 October the crank's own second attempt with a heartbeat that had already
   landed was skipped with error 7, twice, with no ORE `deploy` (the two transactions of R7).
   They may be shown, captioned as what they are ("the crank's own duplicate, 10 October 2026"),
   never as the staged replay. Since about 19:00 UTC that evening the service waits 40 slots
   before a second attempt, and the three rounds dug after that had none.
5. **Decode on screen.** `RigSkipped` is 45 bytes: tag `2` | rig (32) | `round_id` u64 | `error`
   u32 LE, so the event ends `07 00 00 00` (`programs/heads-down/program/src/events.rs`). Caption the
   decoded fields next to the raw base64 line.
6. **Put it down within 10 s** and press power to turn the screen off. After 10 s of cooling the
   shift breaks and needs a new clock-in (`ShiftStateMachine.kt`, `DEFAULT_GRACE_MILLIS`).
7. **Round N+1.** A fresh heartbeat; the dig lands with the same three instructions as step 2. Keep
   Solscan's block time or the round id in frame.

**If the gate is shut and the ceiling was not raised,** step 7 shows `RigSkipped` with error 1
(`CostGate`): the phone was down and the price was wrong. That is an honest beat too; use it with
the 1:22 segment and caption "gate shut: buying is cheaper right now". It has not been seen on
mainnet: no dig has been sent there while the gate was shut.

**The budget of one take.** The demo build places 0.005 SOL a shift, which is five digs. On
10 October the fifth dig used it up and the crank sent nothing for the rig in the next three
rounds. Steps 2 to 7 need two digs; a shift that has already dug five needs a clock-out and a new
clock-in first, and each seal costs the wallet the ShiftLog's rent (1,300,480 lamports, which
`close_shift_log` gives back from 30 days after the shift ended).

---

## 5. Filming notes for the Redmi 14C

What the first shift on the phone settled (10 October 2026):

- **The phone.** Model 2409BRN2CA, Android 16, HyperOS 3. Earlier drafts of this script said
  Android 14.
- **The wallet.** Jupiter Mobile signed the sign-in and all four transactions. On its connect
  prompt it showed "Could not verify request" and still connected (`docs/DEPLOY.md` §10.7): expect
  that line on camera. Solflare, Phantom and Seed Vault have not been tried on this phone.
- **Unlocking ends the shift.** Picking the phone up and unlocking it landed a phone-signed BREAK
  (reason 8), and the shift could then only be clocked out. For the lift in §4, light the screen
  and do not unlock.
- **The clock-out screen.** After the wallet's approval and the confirmation it read "Confirmed
  on-chain. Shift sealed as ended early."
- **The take-back screen.** Before: "Your ORE Automation holds 0.00502704 SOL: 0.003564 SOL not
  yet placed, and the account's rent". After: "Confirmed on-chain. 0.00502704 SOL back in your
  wallet from the ORE Automation." The chain agrees to the lamport: the Automation gave up
  5,027,040 and the wallet, which paid the 5,000-lamport fee, gained 5,022,040. The screen for
  closing the rig read "Your rig can be closed: 0.00178816 SOL of account rent comes back"; it
  was not sent, and the rig is still open.
- **The first heartbeat.** The first clock-in's shift was sealed by a second clock-in about five
  minutes later with no round dug: the wallet had sent the clock-in, the app did not hear so, and
  the phone never started the shift (`docs/MAINNET.md`, "What the first night found", item 4).
  The app now finds a landed clock-in on-chain by itself; that repair has run on the emulator,
  and on the phone the failure has not come back in three clock-ins. In a take, after approving
  in the wallet, wait for the home screen to read "Armed" before laying the phone down, and do
  not tap "Clock in" twice. Keep the charger in: a Night Shift ends when it comes out. If the
  wallet has not been used with the app for a day, the first tap may answer "Your wallet asks to
  connect again. Tap once more.": do that tap before the take. To seal a shift after a pickup,
  tap "Clock out" under "Shift ended"; a second "Clock in" seals it too, and pays for a new one. Before a take that needs
  a dig, watch `heartbeats_accepted` rise on the crank's `/metrics` (§6).
- **Crank restarts.** The phone reconnected by itself after each of two restarts, and its
  heartbeats were accepted again within the next round.

Written before the device run, and not confirmed on it:

- **Weak vibration motor.** Don't rely on haptics on camera. Use the app's audible arm cue (R10),
  recorded with a microphone near the nightstand, once it has been heard on the Redmi. Until
  then, the cold open has room tone only, and the "Rig armed" notification carries the moment.
- **No always-on display expected.** The Redmi 14C has an LCD panel, so plan for no AOD `[verify in
  Settings → Lock screen]`. "Rig hot" only exists with the screen off, and lighting the screen
  cools the rig, so the phone itself can never show "Rig hot" on camera. Show it from the laptop
  instead: `adb shell dumpsys notification --noredact` prints the notification text while
  `dumpsys power` shows `mWakefulness=Asleep`. The Redmi runs Android 16, but what HyperOS 3 shows
  for a Live Update was not recorded on 10 October: check it before planning that shot, and
  otherwise use the Android 16 emulator as clearly captioned B-roll ("Android 16 emulator").
- **What wakes the screen cools the rig.** Turn on Do Not Disturb. Turn off raise-to-wake and
  double-tap-to-wake for the shoot, so every wake on camera is deliberate.
- **Charging.** Night Shift requires the charger. USB to the laptop counts, but for the hero shot use
  the wall charger and wireless adb (`adb pair`, `adb connect`).
- **HyperOS.** Turn on Autostart and battery "No restrictions" for Heads Down, as the in-app
  keep-alive onboarding asks (`android/feature/oem-keepalive`). To let scrcpy send input, also enable "USB
  debugging (Security settings)" in Developer options, and "Install via USB" (needs a Mi account)
  for sideloading.
- **Wallet screens.** A wallet may block screen capture on its signing screens; whether Jupiter
  Mobile does was not recorded. Film the approval with a camera; the explorer shows the result.
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

# Crank side (setup A): the service on Railway. Both pages are public and carry no address.
curl -s https://crank-production-21c2.up.railway.app/healthz    # round, gate value (ema_ev), breaker state
curl -s https://crank-production-21c2.up.railway.app/metrics | grep -E "heartbeats_accepted|digs_landed|digs_skipped"

# Crank side, on the laptop (setup B, or a second crank of your own).
./target/release/hd-crank --config crank.toml check        # live round, gate value, breaker state
./target/release/hd-crank --config crank.toml --keypair ~/.config/hd-crank/id.json run
curl -s localhost:<port>/metrics | grep -E "heartbeats_accepted|digs_landed|digs_skipped"
```

Desktop: OBS with two sources (the scrcpy window and the browser) for the split screen. Keep the
explorer's block time visible.

---

## 7. VO transcript (for captions and the transcript reader)

Read by the founder. The bracketed numbers come from the run; the last block is the founder's own.

> This is a Redmi 14C on its charger. For [thirty-five] minutes it has been lying face-down,
> working a shift on Solana mainnet. That sound is its alarm.
>
> After the alarm, Heads Down opens its reveal page over the lock screen. The shift is over and not
> sealed yet: [31] rounds signed by this phone. I tap Clock out and approve once in my wallet. That
> seals the shift on-chain, returns my Focus Bond and buys a little ORE. Then the haul: the rounds
> it was dark, the digs that landed, and what I paid per ORE with the market price next to it.
>
> Heads Down is your phone's night shift, powered by ORE. You plug in, set your alarm, clock in with
> one approval and lay the phone face-down. While it stays down it digs ORE from your own ORE
> account, inside limits your wallet signed. Pick it up, and the rig goes cold.
>
> The habit is the product. Every completed night adds to a streak stored on-chain, with two nights
> off a month, so one bad night does not end it. A widget shows the rig. The Nights screen keeps
> every shift, and a share card shows the night without a wallet address.
>
> What is new is the referee. Once a minute the phone signs a heartbeat with a key that cannot
> leave its secure hardware. Solana checks that signature in the same transaction as the dig,
> through the secp256r1 precompile. No server can say this phone was face-down, mine included. Here
> I lift it during a shift: it signs a break, and the digging stops.
>
> SKR is the Focus Bond. I locked ten SKR on this shift. It completed, so the same ten came back.
> By the program's rule a lost bond is never paid to the team or to another player: it is sold for
> ORE that is burned.
>
> It is plain about ORE: mining is one route to ORE, not income. The rig digs only while ORE's own
> cost figure is under a ceiling, which I raised for this film so a dig could land. Over this
> rig's first twenty digs I paid about 1.13 SOL per ORE.
>
> All of this ran on Solana mainnet, on this one phone, since 10 October 2026: [10] shifts and [35]
> digs. Through last night it worked for three hours, about 170 heartbeats, until a power cut.
> Every transaction is public. It has not run on a Seeker, nobody outside the project has audited
> it, and so far I am its only user.
>
> [Founder: two sentences of your own, who you are and how this was built.] Heads Down. Put it
> down. It digs. Powered by ORE.

Before recording: replace every bracket, cut the Nights clause if that screen is not in the filmed
build, and read the bond and clock-out sentences against what the run did (how many approvals,
what came back). Every claim above has its row in [MAINNET.md](../MAINNET.md) or its file in
`docs/pitch/DECK.md`.
