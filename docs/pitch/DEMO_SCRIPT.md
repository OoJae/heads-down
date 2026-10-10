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
| R3 | Phone-to-crank heartbeat JSON | **Done, and run on mainnet.** One contract for both sides, tested over real sockets (`crank/README.md`); the phone-less end-to-end run passes on a local fork of mainnet (`docs/DEVSTACK.md`); the real app on an Android 14 emulator had its Keystore-signed heartbeat dig a round on that fork (`scripts/devstack/emulator-smoke.sh`). On 10 October 2026 the crank on Railway took the Redmi's heartbeats and dug five rounds with them ([the first dig](https://solscan.io/tx/25nBRCP6GExrKG4EDszGrKfWN9yYgJMopgnawTGi2Z9XNGj6UCtZgE6HFJN7rJXvu2C3VMjhh7bpXZnQg8DVzovY)). The first clock-in's shift got no heartbeat to the crank before it was sealed; why was not investigated | trustless beat | android or crank |
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
  settled so far ORE sent 0.000891 SOL back. The fifth round, 435,228, was not checkpointed at
  20:44 UTC on 10 October, so what comes back from it is not known. No ORE came out of the four
  that are settled.

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

| Time | Picture (A-roll) | On-screen text | VO | B-roll / capture |
|---|---|---|---|---|
| 0:00–0:08 | Macro, dark bedroom, 23:0x. Redmi on a nightstand, charger in. Thumb swipes down and taps the "Heads Down" tile next to Do Not Disturb. The wallet sheet appears; fingerprint. The phone goes face-down. | none | "Most phones spend the night doing nothing. Mine works a night shift." | Camera, not screen capture: wallet apps can block recording. Room audio for the arm cue once R10 ships. |
| 0:08–0:22 | Slow push-in on the face-down phone. | "Heads Down · powered by ORE" · "Any Android · verified Seeker tier" | "This is Heads Down. Face-down, my phone digs ORE from my own ORE Automation. Pick it up, and the rig goes cold. Only a heartbeat signed inside this phone can switch it on. No server can forge one, mine included." | none |
| 0:22–0:45 | Screen capture of the same clock-in: tile, wallet approval (camera if the wallet blocks capture), "Rig armed" notification. Cut to Solscan: one transaction with ORE `automate`, `register_rig` (first run), `set_caps`, `arm_shift`. | "One approval · one transaction" · the tx signature | "I tap the Quick Settings tile. One wallet approval funds tonight's capped shift inside ORE's own Automation and arms the rig. That's the only signature tonight. This is a Redmi 14C. Any Android with a mobile wallet can run a guest rig, and Seeker owners get a tier verified on-chain by their Seeker Genesis Token." | `scrcpy` recording started **before** arming (§5). |
| 0:45–1:22 | **Trustless beat** (step by step in §4). Split screen: phone on the left, terminal and Solscan on the right. | "Round N · dig ✓" → "Phone lifted" → "Replayed heartbeat → RigSkipped · 7 StaleHeartbeat · no ORE instruction" → "Round N+1 · fresh heartbeat · dig ✓" · "Demo setting: price ceiling raised for this take" | "Every ORE round, about 78 seconds, the phone signs a heartbeat with a key that never leaves its Keystore. A crank can dig for my rig only if that heartbeat verifies on-chain, through Solana's secp256r1 precompile. Now I pick the phone up. It stops signing. So I play a dishonest crank: I copy my last heartbeat off the chain and resend it. The program skips my rig: stale heartbeat. Nothing reaches ORE. Phone back down. Next round, a fresh heartbeat, and the dig lands in ORE." | Terminal: `hd-crank` log lines for accepted heartbeats; the replay tool's output (R7). |
| 1:22–1:44 | Chart: `ml/forecaster/figures/eff_price_vs_budget.png`, then the live gate value from `hd-crank check`. | "58,801 ORE rounds · 47 nights" · "every round: +36% to +90% vs buying" · "gated chunks + buy the rest: −1.4% to −3.0%" | "For this take I raised my price ceiling so a dig could land on camera. Normally the gate decides. In our backtest of 58,801 ORE rounds, digging a little every round cost 36 to 90 percent more than buying. Gated chunks plus buying the rest came out 1.4 to 3 percent cheaper. Modest, and we say so." | `ml/forecaster/RESULTS.md` TL;DR figures. |
| 1:44–2:03 | The detector on a table: phone face-down, a knock, no change; a lift, cooling. Use the debug sensor view if it exists (captioned), else the terminal. | "On-device · accelerometer only (no gyroscope)" · "Forecaster: advisory (lost to the on-chain rule)" · "Can only tighten wallet-signed limits" | "The AI runs on the phone. Pickup detection uses only the accelerometer; this phone has no gyroscope. A pickup-versus-bump classifier is built, trained on synthetic data so far. Our cost forecaster lost to the simple on-chain rule, so it only explains the night. Nothing on the phone can raise the limits my wallet signed." | `ml/forecaster/MODEL_CARD.md` card as a 2 s insert. |
| 2:03–2:15 | **If R8 ships:** 07:00 alarm opens the full-screen haul reveal. **Fallback:** the "Rig cold · Shift ended · N rounds dark" notification, then Solscan: the `end_shift` tx and its ShiftLog. | reveal: "rounds dark · digs · price per ORE vs market" / fallback: "ShiftLog on-chain" | Reveal: "At my alarm, the haul reveal replays the night: rounds dark, digs landed, and what I paid per ORE against the market." Fallback: "In the morning the shift closes on-chain: dark rounds, rounds dug and SOL deployed, in a ShiftLog anyone can read." | Real footage from a real night only. Never a staged Motherlode. |
| 2:15–2:35 | **Stretch (R9 ships):** a dinner table, friends' phones pair over Nearby, each posts an SKR bond; a knock, all stay hot; a reach, one rig breaks; Solscan settle. **Alternative (default today):** three friends' Android phones as guest rigs on a table; knock the table; one friend reaches for their phone. | Stretch: "SKR bond · 80% of forfeits to finishers · 20% buys ORE for ORE's bury" / Alternative: "3 phones · 3 guest rigs · knock ≠ pickup" | Stretch: "SKR makes it social. At a table, each friend bonds SKR on keeping their phone down. A knock on the table doesn't count; a pickup does. Finishers take back their bonds plus most of the forfeits, and the rest buys ORE that ORE buries." Alternative: "Three friends, three different Android phones, all guest rigs. I knock the table: every rig keeps heartbeating. One of us reaches for a phone: that rig cools. SKR-bonded table contests build on exactly this; the program has them, the app's screens come next." | Terminal: per-rig heartbeat acceptances at the crank (needs R3). |
| 2:35–2:52 | Dashboard scrolled: `/` tiles, `/share/`, `/digs/` with Solscan links. Then the repo: `docs/THREAT_MODEL.md`, `crates/`. | Real data: tile values with links. Simulated: the SIMULATED banner stays in frame. | "Every number on the dashboard comes from chain data and links to the transaction behind it. The program, the crank, the Seeker and P-256 crates and the threat model are open source, with a written worst case for every key." If simulated, add first: "This is still the labelled simulated dataset." | `dashboard/README.md` run steps. |
| 2:52–3:00 | The face-down phone in the dark. | "Heads Down. Put it down. It digs." · "Powered by ORE" · store status `[TBD]` | "Heads Down. Put it down. It digs. Powered by ORE." | none |

VO total: about 405 words, about 2:32 at 160 words a minute, which leaves room for the cold open,
the pauses and the close.

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
  minutes later with no round dug: the crank had accepted no heartbeat yet, and why was not
  investigated. Before a take that needs a dig, watch `heartbeats_accepted` rise on the crank's
  `/metrics` (§6).
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

> Most phones spend the night doing nothing. Mine works a night shift.
>
> This is Heads Down. Face-down, my phone digs ORE from my own ORE Automation. Pick it up, and the
> rig goes cold. Only a heartbeat signed inside this phone can switch it on. No server can forge
> one, mine included.
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
> gyroscope. A pickup-versus-bump classifier is built, trained on synthetic data so far. Our cost
> forecaster lost to the
> simple on-chain rule, so it only explains the night. Nothing on the phone can raise the limits my
> wallet signed.
>
> In the morning the shift closes on-chain: dark rounds, rounds dug and SOL deployed, in a ShiftLog
> anyone can read.
>
> Three friends, three different Android phones, all guest rigs. I knock the table: every rig keeps
> heartbeating. One of us reaches for a phone: that rig cools. SKR-bonded table contests build on
> exactly this; the program has them, the app's screens come next.
>
> Every number on the dashboard comes from chain data and links to the transaction behind it. The
> program, the crank, the Seeker and P-256 crates and the threat model are open source, with a
> written worst case for every key.
>
> Heads Down. Put it down. It digs. Powered by ORE.

Swap in the reveal and Stack lines from §3 if R8 or R9 ship. Keep each claim in the transcript
backed by a file listed in `docs/pitch/DECK.md`.
