# Heads Down privacy

What Heads Down knows about you, where it lives, who can see it, and what you can do about it. Parts of this are **public on a blockchain forever**, and we say which parts, plainly.

> Status: this describes the design the app and services must implement. Retention periods marked **TBD** will be fixed before the public beta, and this document updated.

---

## Summary

- **Sensor and usage data never leave your phone.** The pickup classifier, the Shift Planner and the Cost Forecaster all run on-device. Only a state bit (face-down or not) and counters go anywhere else.
- **What goes on-chain is public and permanent**, as for any ORE miner. Anyone who knows your rig or wallet can see when your phone was set down and picked up, to within one ORE round (about 78 s). That reveals your sleep or idle schedule and likely your time zone. We show this on screen before you register a rig.
- **No location, anywhere.** Rooms have a coarse region that you choose (for example "Lagos"), and it is optional.
- **No ads, no third-party analytics SDKs, no device IDs.**
- **You can separate the rig from your main wallet** by using a secondary wallet account as the rig's authority (details and limits in section 5).

---

## 1. What stays on your phone

| Data | Used for | Leaves the phone? |
|---|---|---|
| Accelerometer and gravity readings, proximity (virtual on the Redmi 14C), significant motion | face-down detection; pickup vs bump classifier | **Never** |
| Screen on/off, unlock, charging state | hard break signals; shift state | **Never** (only the resulting state bit is signed and sent) |
| Your next system alarm (`AlarmManager.getNextAlarmClock`) | aligning the morning reveal | **Never** |
| Shift history from the app's own service logs | Shift Planner (idle-window model), Sunday rhythm report | **Never** |
| Foreman features and forecasts | tonight's plan and gate | **Never** (the plan's thresholds are signed and go on-chain as numbers) |
| Rig P-256 private key | signing heartbeats, plans, BREAK and FREEZE | **Never**: it is held in Android Keystore and cannot be exported |
| Wallet auth token (MWA), sign-in session token (SIWS) | talking to your wallet and our heartbeat service | Never in plain form: encrypted with a Keystore key and excluded from backups |
| Camera frames, only while scanning a room or gift QR code | reading the invite | **Never**: processed on-device and not stored |

Clearing the app's data or uninstalling it deletes all of this. We do not read UsageStats, contacts, the microphone, location, or the advertising ID.

---

## 2. What is public on-chain (permanent)

| On-chain item | What it contains | What an observer can infer |
|---|---|---|
| `Rig` account | your holder wallet, the rig's P-256 public key, attestation level and expiry, optional SGT mint, caps, plan thresholds, state, shift and heartbeat counters, last heartbeat round, gaps, spend per shift and week, lifetime dark rounds, streak, weekly ShiftLog root | that this wallet runs a Heads Down rig, its budget, and its current state |
| Each dig transaction | slot and time; the rig; **your wallet as a writable account** (ORE requires it in `deploy` and `checkpoint`: `deploy.rs:23`, `checkpoint.rs:16`); your ORE Automation and Miner; the secp256r1 instruction with your rig key and the heartbeat message (round, counter, shift) | **when your phone was face-down, round by round**; your ORE activity |
| ORE's own `DeployEvent` | your wallet (as ORE authority), amount, squares, round, signer = the Heads Down Executor | the same as for any ORE miner |
| `heartbeat` transactions (focus-only shifts, Stack) | the rig and round | when your phone was face-down, even on nights with no mining |
| `ShiftLog` | start and end round, dark rounds, rounds dug, lamports deployed, break reason, mode | your nightly pattern. Closable after 30 days, which removes it from current state but **not** from ledger history |
| Room and Stack seats, bonds | membership, bond amounts, outcomes | which rooms and tables you joined, and whether you held out. Room names are stored as hashes |
| Gifts | sender wallet, recipient SGT mint (or wallet), amount | who gifted whom |
| `SeekerSeat` (Seeker tier) | the SGT mint and the rig it points to | which Seeker device your rig belongs to |

**Wallet footprint.** Because ORE requires the authority to be writable, about one transaction per dug round references your wallet as a writable non-signer. That is around 60 on the example night in [ECONOMICS.md](ECONOMICS.md), and up to about 350 on a long night with no cap. They show up in explorers and wallet histories. We do not yet know whether Solana Mobile's activity scoring counts them; that is an open question in the SPEC.

---

## 3. What our services see

None of these services can move funds ([THREAT_MODEL.md](THREAT_MODEL.md), K6). They still see data, so this table lists what and for how long.

| Service | Sees | Retention |
|---|---|---|
| Heartbeat intake (WebSocket, SIWS session) | your wallet (from sign-in), your rig, heartbeat timing, **your IP address** | heartbeats until the round is dug or settled (hours); connection logs **TBD** (target 7 days or less) |
| Nostr mirror (public relays) | signed heartbeats, published so third-party cranks can dig | public, like on-chain data, and relays may keep it |
| Sign-in (SIWS) | wallet address, nonce, session | nonces are single-use with a 10-minute expiry; sessions last until sign-out or expiry |
| Key Attestation registrar | the Android attestation certificate chain: security level, boot state, OS patch level, app package and signing-certificate digest | transcripts are **published** so anyone can re-verify the registrar. We do **not** request device-ID attestation (no IMEI, serial or MEID). An attestation key provisioned by Google may let an observer link two rigs registered from the same phone while that key is valid |
| Push and presence (FCM) | your FCM token, rig, rooms, and a coarse "dark or awake" state for room members | until sign-out or uninstall, then deletion within **TBD** days |
| Indexer and public dashboard | on-chain data only | public aggregates; per-rig pages show only what is already on-chain |
| Jupiter quote proxy | token pair and amount for the buy leg, and your IP. The swap itself is built directly with Jupiter, which then also sees your wallet | not stored beyond operational logs (**TBD**) |
| Kora relayer | the sponsored transaction (public anyway) and your IP | operational logs (**TBD**) |
| RPC proxy (Helius behind it) | the app's RPC requests and your IP | operational logs (**TBD**) |

**We never log** auth tokens, sign-in messages, transaction bytes, heartbeat payloads beyond what the intake needs, or any sensor data. This is enforced in the app by a lint rule against release logging, and in services by structured-log allowlists.

---

## 4. Third parties

- **Your wallet app** (Seed Vault, Solflare, Phantom and others) sees every transaction you sign.
- **Google Play services** carries FCM push and Nearby Connections (for in-person Stack tables).
- **Helius** provides RPC, LaserStream and webhooks, behind our proxy.
- **Jupiter** handles swaps.
- **AllDomains** is used for `.skr` name lookups, which are on-chain reads.
- **api.ore.com** provides public ORE statistics, fetched through our cache.
- **Nostr relays** carry the public heartbeat mirror.
- **Railway or Fly** hosts our services (region TBD).
- **Your phone's maker** (for example, Xiaomi HyperOS telemetry) is outside our control.

Nearby Connections on Android 13 and later uses Bluetooth and Wi-Fi permissions marked `neverForLocation`. Android 12 devices may need location permission for Nearby over Wi-Fi; if so, we ask with an explanation, or fall back to Bluetooth only. That path is TBD in the spike.

---

## 5. Your choices

- **Separate wallet account as the rig authority.** Seed Vault and most wallets support several accounts. Using a second one keeps rig activity out of your main wallet's history.
  - *Limit:* the Seeker tier requires the holder of your Seeker Genesis Token (SGT) to sign the verification, so a Seeker-tier rig is linked to that SGT, and the SGT to the account that holds it. SGTs move only between your own accounts.
- **Guest rig with a fresh wallet.** No SGT link at all.
- **Rooms.** Private or public; the region is optional and coarse. The Night Map is country-level and opt-in. You can hide your presence dot, and Muster is still verifiable from chain data.
- **Lock screen.** An option hides amounts in notifications and on the lock screen.
- **Classifier data donation (opt-in, off by default).** Alpha testers can choose to upload short labelled accelerometer windows (a few seconds each, no location, no wallet link, tagged with a random tester ID) to help train the pickup classifier. You can withdraw and have your windows deleted. Published datasets are aggregated (format TBD). Models ship inside app releases as signed files, and no code is downloaded at runtime.
- **Deletion.** "Delete my data" in the app deletes our off-chain records: session, FCM token, presence and room metadata, within **TBD** days. On-chain data **cannot** be deleted by anyone. Closing a Rig or a ShiftLog removes it from current state but not from ledger history.

---

## 6. Legal basis and rights

- **Controller:** the Heads Down developer, based in Nigeria. Contact: TBD before the public beta.
- **Laws we design for:** the Nigeria Data Protection Act 2023, and the GDPR for users in the EU.
- **Basis:** providing the service you asked for (heartbeats, push, rooms), and consent for optional features (classifier data donation, Night Map).
- **Rights:** access, correction, deletion and objection for off-chain data, via the in-app request or the contact above. On-chain data is outside anyone's power to change.
- **Age:** 18+, because the app involves SKR bonds.
- **What we do not claim:** Heads Down does not track sleep and makes no health claims. It measures only when this phone is face-down and unused.

---

## 7. Changes

This document lives in the public repository, and every change is a commit, so you can see exactly what changed and when.
