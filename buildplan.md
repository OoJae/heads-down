# Clock In (Solana Mobile Hackathon #3): Champion Project "Heads Down"

## Context
You want to win 1st place at Solana Mobile x Radiants **Clock In** ($30k + Seekers). The ORE matched prize (up to +$30k) and the $10k SKR prize should stack on top only where they fit naturally. You asked for research within the rules, 5 ideas, and one champion chosen after weighing everything, with the deadline ignored and nothing underscoped. The project folder is empty (greenfield). You are solo with AI agents and have no stack preference. The site showed your timezone as WAT; Nigeria and the rest of West Africa are on the USDC-eligible list, and prizes require KYC via Sumsub.

**Device constraint (confirmed): no Seeker at any point. Build, test and film on a Redmi 14C, end to end.**
- **Redmi 14C:** HyperOS 3 on Android 16 (read from the device on 10 October 2026; this plan first assumed Android 14), Helio G81 Ultra, **no gyroscope**, a **virtual proximity sensor**, an accelerometer, a side fingerprint reader, and aggressive HyperOS background killing.
- The rules allow this ("You can build and test your APK on an Android emulator or any Android device"; the app must run on a real device, and the Redmi counts).
- Seeker-only features are tested on a mainnet-fork simulation and by remote Seeker beta testers.

## How the decision was made
- **Research:** 8 agents, one per dimension (past winners, the Seeker/SMS stack, SKR+ORE, dApp Store gaps, 2026 trends, the judges, Android-native UX, retention), plus a Playwright read of the official site.
- **Ideation:** 6 ideators with deliberately different angles produced 24 ideas, merged into 12 candidates.
- **Scoring:** 7 persona judges (Toly, Mert, Chase, the Solana Mobile team, Ethelsec combined with the Radiants audit, a Seeker-owner panel, and an Align AI screener/prize analyst) scored all 12 under both rubrics.
- **Red team:** 6 adversarial agents checked GitHub, the dApp Store catalog, ORE's source code and Android docs.
- **Decision:** a chief strategist chose the 5 finalists and the champion.
- **Location of the raw artifacts** (session scratchpad; copied into `docs/research/` in step 0): `research/*.md`, `candidates.md`, `redteam.md`, `final_decision.md`, `final_spec.md`.

## Research findings that shape everything
- **Two rubrics.** The public rubric is 4×25% (Stickiness/PMF, UX, Innovation, Demo). **But the Align judging app loads AI 20 / SKR 20 / UX 15 / UI 15 / Innovation 15 / Ecosystem 15.** An AI pre-screen reads the deck text, the demo transcript and the security audit. Radiants also runs an automated audit (16 program bug classes, leaked secrets, "success shown after on-chain failure", logged credentials). **Build for both rubrics.**
- **Calibration.** MONOLITH (the previous hackathon) used the same rubric with Toly, Mert, Chase and Akshay. Its winners had a daily loop, used the phone's hardware, moved a familiar Web2 format onto Solana, showed traction and had a founder story.
- **Saturated:** prediction markets, yield, GPS drops, AI agents, pets, fitness, trading games, shopping checkout, lotteries, check-in streaks, SKR used as a currency or for staking.
- **Audience.** About 121K device-verified Seekers (Seeker Genesis Token, SGT). About 9K DAU and falling. Most devices are idle second phones. Owners are farming-conditioned and security-anxious, and bot-like activity is now penalized. The Solana Mobile Stack (SMS) is now licensed to all Android OEMs, so "any Android" support also has an ecosystem story.
- **ORE.** No native app (stale since Oct 2025; PWA only). ORE advertised "Proof of mobile: mine ORE on Seeker" and never shipped it. About 150–165 miners per round.

## The 5 finalists
| # | Idea | One line | Panel (public / Align) | Red team P(1st) / P(top10) |
|---|---|---|---|---|
| 1 | **Heads Down** ★ | Face-down, your phone mines ORE; pick it up and the rig goes cold. Only the phone's hardware key can switch it on | **8.07** / 6.69 | **0.05 / 0.33** (field best) |
| 2 | Roll Call (reshaped Pulse) | An anonymous census of verified Seekers: one daily question, answered from the widget with a fingerprint, with a synchronized reveal | 7.86 / **7.55** | 0.03 / 0.22 |
| 3 | Gateman | "Clock in with a float": a capped daily allowance for untrusted dApps, plus an SKR-bonded drainer registry | 7.07 / 7.56 | 0.025 / 0.17 |
| 4 | Punch (+ Airtime) | The literal clock: paid by the second through Solana Payment Channel vouchers shown on the lock screen | 7.21 / 5.61 | not red-teamed (Airtime 0.02) |
| 5 | STASH × Melt Meter | A daily hard-money ritual: a personal inflation meter feeding mine-or-buy ORE accumulation | 6.46 / 5.63 | MELT graft 0.015 |

- **Killed or dropped:**
  - Alert (a P2P cash ramp): regulatory exposure for a Nigeria-based founder, and P2P Protocol is ahead.
  - Night Shift crews, Whistle and Foreman Tycoon: grafted into the champion.
  - Parachute: stickiness about 2.9.
  - Ajo: duplicates the "Kin" competitor.
- **Why the runners-up lose:**
  - Pulse's lane is already taken by **TBD** ($3M raised, promoted to every Seeker by Solana Mobile) and by U$ER and HOTSHOT. It also depends entirely on the SGT, which you can't hold without a Seeker.
  - Gateman overlaps **Totem Wallet** (a Clock In entrant with 291 commits), Solflare Guards and Seed Vault's native warnings, and its use is insurance-shaped.
- **Honesty on odds:** no idea "definitely" wins among about 325 teams. The best version of Heads Down is estimated at about 8% for 1st and 40% for the top 10 (about 25× the base rate), with an expected prize of about $11k and a **$70k ceiling** (1st + ORE match + SKR). That is the best in the field. Filming on a Redmi rather than a Seeker costs some "Seeker wow" in the demo; opening the product to any Android and showing Seeker-tier usage from remote testers partly offsets that.

## Champion: Heads Down ("Your phone's night shift", powered by ORE)
**Why it wins**
- It has the top public-rubric score and the best demo (9.0/10). It was crowned by the Solana Mobile, Ethelsec and community seats and sits in the top 3 for 6 of the 7 judges.
- It is the only credible path to the ORE match.
- The red team found no public repo or store app that gates ORE mining on phone state.
- It **refuted** the two biggest objections:
  - ORE's `deploy.rs`/`automate.rs` show that a heartbeat-gated PDA executor is feasible, so no team hot key is needed.
  - Production cost was below price in 150 of 167 hourly snapshots last week, so "pay to focus" was overstated.
- Its habit triggers are existing daily routines (bedtime, charger, alarm), so it needs no content pipeline.
- The core mechanism (accelerometer, Keystore P-256 key, ORE Automation, MWA) is device-agnostic, so it can be built fully on the Redmi.

**Positioning and honesty rules**
- Never say earn, yield, stake, passive income, or "proof of focus".
- Frame it as "give your idle phone a job" and "accumulate ORE by the cheaper route", with an always-visible EV meter and the effective price paid per ORE.
- ORE is the primary featured brand everywhere.
- **Two tiers:**
  - **Guest rig:** any Android phone with an MWA wallet (Solflare, Phantom, Jupiter, Backpack) mines its own funds.
  - **Seeker rig:** verified in-program by SGT, it adds room seats, SKR-bonded Stack, `.skr` gifts, Seeker leaderboards and seasonal perks. Pitch: "the first native ORE rig for Seeker, and it runs on any SMS phone".

### Core loop
- **Night: clock in.**
  - Tap the Quick Settings tile. A non-exported translucent trampoline opens **one** wallet confirmation via MWA (Seed Vault double-tap on a Seeker; biometric in Solflare or Phantom on the Redmi).
  - That one transaction funds a capped shift inside **your own ORE Automation account** and arms the Rig. Then lay the phone face-down on the charger.
  - Every ~78 s round, a permissionless crank may dig **only if** the phone's Keystore P-256 heartbeat for that round verifies on-chain via the secp256r1 precompile.
  - Pick the phone up (screen-on or unlock, or a classifier-confirmed lift) and the rig cools in 10 s. Offline or killed by the OS means no mining and no loss (fail-safe).
- **Morning: clock out.** The exact alarm opens a full-screen haul reveal (rounds replayed, near-misses, effective price against market). One wallet confirmation claims the ORE, or keeps it unrefined to share the 10% refining fee, plus an optional "buy the rest at market" leg via Jupiter. The ShiftLog is sealed and the streak goes up. Then each room's synchronized **Muster** reveal and a share grid.
- **Day, weekly and bad-EV weeks.**
  - Optional 25/50/90-minute Day Shift desk rig.
  - A weekly refuel, after which arming inside an AI-planned window needs zero taps.
  - Stack nights, a Sunday rhythm report, and Gift a Rig.
  - Focus-only shifts that deploy no SOL still count.

### Feature pillars (full scope)
1. **Rig: the trustless phone gate.**
   - The Executor PDA is the ORE Automation executor, using the Discretionary strategy with a fixed crank-cost fee (no percentage).
   - The program computes amounts and least-crowded tiles itself, so crankers have no discretion.
   - Wallet-signed caps (weekly, per-shift, per-round, expiry); the phone key and the AI can only tighten them.
   - ORE-native `max_production_cost` and `min_motherlode` conditions.
   - Mine-or-buy engine, honest EV meter, partial claims, optional stORE, and local-currency display (NGN/KRW/BRL/INR/PHP/USD).
   - Instant Freeze with the device key; Revoke via the wallet.
   - A circuit breaker on ORE layout changes.
2. **Ritual.**
   - QS tile, flip-to-arm, and AI auto-arm.
   - A promoted ongoing notification (the Android 14/15 path), upgraded to an Android 16 Live Update/AOD chip where available, with no tickers or countdowns. The Redmi turned out to run Android 16; what HyperOS 3 shows for a Live Update there has not been recorded.
   - Exact-alarm full-screen reveal, composed haptics with an audible fallback (no per-round buzzing at night), and Glance widgets.
   - Optional DND automation, App Shortcuts, a forgiving streak (2 automatic freezes a month), and cosmetics that level with hours, never with spend.
   - **Per-OEM keep-alive onboarding** (HyperOS/MIUI Autostart + "No restrictions" battery, Samsung and others, following dontkillmyapp) with a health check that warns if the OS killed last night's shift.
3. **Crew.**
   - Private rooms plus public regional rooms (Lagos, Abuja, Nairobi, Manila, Seoul, São Paulo, Global Desk).
   - Guests can join as unverified members; verified seats are one per SGT mint.
   - A "dark" presence row, Muster, an automatic feed built from on-chain outcomes, `.skr` names where present, and deep-link/QR invites.
4. **Stakes (SKR, never staking).**
   - **Stack:** an SKR-bonded self-control contest.
     - In-person tables over Nearby (co-presence makes them sybil-safe, so guests are allowed with capped bonds).
     - Remote "honor-plus" tables for Seeker rigs only.
     - Settlement is permissionless and fail-closed from on-chain heartbeats. Forfeits go 80% to finishers and 20% to Bury.
   - **Focus Bond:** lock SKR on a solo shift.
   - **Gift a Rig:** send SKR to any wallet, or to a `.skr` name, in which case it is escrowed against the recipient's SGT mint and only a real Seeker can claim it. It arrives as a live rig. Also available from `ACTION_PROCESS_TEXT`.
   - **SKR fuel:** an atomic swap inside the clock-in transaction.
   - **Bury auction:** forfeited SKR is sold in a no-oracle Dutch auction for ORE, which goes to ORE's bury address.
5. **Foreman (AI).** Three on-device LiteRT models, and no sensor data leaves the phone.
   - **Shift Planner:** a rhythm model that predicts idle windows, auto-arms and allocates the budget.
   - **Cost Forecaster:** trained on api.ore.com history. It sets the gate and amount curve, recommends the buy leg, and reports its forecast against the realized price every morning. It must beat "always buy" and "always mine" in a published backtest, or it becomes advisory only.
   - **Pickup/bump classifier:** designed **accelerometer-first**, because the Redmi has no gyroscope and only a virtual proximity sensor. Gyroscope and proximity are optional extra features on devices that have them (Seeker). Target ≥99% pickup recall; screen-on and unlock stay hard breaks.
   - Explanations are template-authoritative; an optional LLM may only paraphrase.
6. **Trust.**
   - Open-source crates `sgt-verify` (credits ORE's `claim_seeker`) and `p256-introspect`.
   - THREAT_MODEL.md, fuzzing plus Kani proofs, and reproducible builds.
   - Radiants audit clean. A Key Attestation registrar (TEE level on the Redmi; StrongBox where present).
   - A timelocked upgrade authority, then revoked (immutable v1).
7. **Proof.**
   - A public dashboard of rigs by tier (guest vs SGT-verified), nightly actives, D1/D7/D14 cohorts, dark hours, rounds, SOL deployed, ORE mined/bought/buried, Heads Down's share of ORE miners per round, and Stack/Gift/Bury stats. CSV export for ORE milestones.
   - The founder's build-hours ledger from the Redmi rig.

### On-chain design (`heads_down`, Pinocchio, under 3k lines)
- **Accounts:**
  - `Config`: registrar key, fixed crank fee, pinned ORE layout hashes, 72h timelock; **no withdraw authority**.
  - `Executor`: a data-less, System-owned PDA that fronts ORE's CHECKPOINT_FEE.
  - **`Rig [rig, authority_wallet]`**: holder, P-256 pubkey, attestation level, tier, optional `sgt_mint`, caps, plan, state machine, counters, streak.
  - **`SeekerSeat [seeker, sgt_mint]`**: points to exactly one Rig; created only after the in-program SGT check. It enforces one verified rig per device and is re-pointed on `rebind`.
  - `ShiftLog`.
  - `Room` / `Seat`.
  - `StackTable` / `StackSeat` with an SKR vault and a tier requirement.
  - `FocusBond`.
  - `GiftEscrow`: by recipient wallet, or by `recipient_sgt_mint` for `.skr` gifts.
  - `BuryVault` plus the auction.
- **Instructions:**
  - `register_rig` (guest).
  - `verify_seeker` (in-program SGT check: Token-2022 owner == signer, amount 1, group `GT22s89…`, mint authority `GT2zuH…`), then `rebind_seeker`.
  - `rotate_key`, `refuel`, `arm_shift`.
  - `dig` (permissionless and batched): introspects the secp256r1 instruction through a checked sysvar, with offsets confined to the same instruction. It binds the message to the program, rig, ORE `round_id`, an increasing counter, `shift_id` and lease. It then applies caps and the gate, computes tiles, CPIs ORE `deploy` via `invoke_signed`, and reloads accounts after the CPI.
  - Shift signals: `heartbeat`, `break_shift`, `freeze_rig`, `end_shift`.
  - Rooms, Stack (open/join/settle/claim/refund), bonds, gifts, and `bury_auction_buy`.
- **Invariants (fuzzed):**
  - No value leaves a user's Automation except through ORE's own deploy-or-return.
  - A dig requires a fresh, bound P-256 signature.
  - Spend never exceeds the wallet-signed caps.
  - One verified rig per SGT mint.
  - Seeker-tier actions require a live SeekerSeat.
  - Stack conservation.
  - Gifts go only to the intended recipient or back to the sender.
  - No admin withdraw path anywhere.
  - Discriminators, canonical bumps, checked math, and no reinitialization.
- **Build features:** `mainnet` hardcodes the real SGT group and authority. `devnet`/`test` substitutes a test Token-2022 group mint you control, so SGT flows can be exercised without a Seeker. The mainnet binary never contains the test path.
- **External programs (pinned):**
  - ORE `oreV3EG1i9BEgiAJ8b177Z2S2rMarzak4NMv1kULvWv`
  - ORE mint `oreoU2P8…`
  - SKR `SKRbvo6Gf7GondiT3BbTfuRDPqLWei4j2Qy2NPGZhW3`
  - Secp256r1SigVerify and Ed25519 precompiles, Token-2022, and the ORE bury address `GHRBYPA4…`

### Off-chain services
1. A Rust crank: Helius LaserStream on Board/Round/Treasury, batched digs, Helius Sender + Jito. It is open source and self-funding, so anyone can run one.
2. Heartbeat intake: a WebSocket with a SIWS session, mirrored to Nostr. Heartbeat leases (≤3 rounds) for solo shifts reduce sensitivity to network drops and OS kills.
3. A Key Attestation registrar.
4. SIWS with single-use 10-minute nonces.
5. A Kora fee relayer.
6. Helius webhooks → FCM for the widget, notification and Muster; a presence service.
7. An indexer (Helius Parsed Events → Postgres) and a public Next.js dashboard.
8. A Jupiter quote proxy, with models shipped as signed files.
9. Hosting on Railway or Fly. The Helius key stays behind a proxy and never goes in the APK.

### Security model (a bounded worst case per key, stated in THREAT_MODEL.md)
- **Rig P-256 key compromised:** the armed weekly budget buys ORE at or below the production cost you capped. Instant Freeze limits the damage.
- **Crank or relayer:** liveness only. If they all fail, nothing mines and nothing is lost.
- **Registrar compromised:** only capped Stack tables can be gamed.
- **Upgrade authority:** multisig + 72h timelock, then revoked.
- **Servers:** no authority over funds.
- **ORE upstream:** program ID pinned, owner and re-derivation checks, a circuit breaker, and nightly fork CI.
- **Android:** non-exported trampoline and tile activities; strict deep-link and Nearby payload parsing; auth tokens encrypted with Keystore; a lint rule banning `android.util.Log` in release builds; success shown only after confirmation with `err==null`; transaction version chosen from MWA `get_capabilities` (v0 with lookup tables by default, v1 when the wallet supports it); passes the AlignAI MWA fixtures.

### Tech stack and repo
- **Stack:**
  - Native **Kotlin + Jetpack Compose** (minSdk 31, target 36); MWA `clientlib-ktx 2.0.3`, Glance 1.2, androidx.core 1.19 (ProgressStyle with a compat fallback), LiteRT, Hilt, Room, Ktor.
  - Program: Pinocchio + Shank → Codama.
  - Off-chain: Rust services, Postgres, Next.js.
  - Tests: LiteSVM, Mollusk, Surfpool fork, Trident, Kani.
- **Monorepo (public from day 1, granular commits):**
  - `programs/heads-down`
  - `crates/{sgt-verify,p256-introspect,hd-client}`
  - `crank`, `registrar`, `services/{push-presence,indexer}`, `dashboard`
  - `android/{app,core/{wallet,chain,keys},feature/{shift,reveal,rooms,stack,gift,oem-keepalive},ml,surface/{tile,widget,notification,haptics}}`
  - `ml/` (notebooks and model cards), `tests/{fork,fuzz,kani}`
  - `docs/{THREAT_MODEL,ECONOMICS,ORE,SKR,PRIVACY,DEMO}.md`

## Build order (core-first, so the repo is coherent and demo-able at every point; nothing cut)
0. **Setup.**
   - Scaffold the monorepo and `git init`. Copy the research, candidates, red-team reports and full spec from the scratchpad into `docs/research/` and `docs/SPEC.md`.
   - Delete the stray `.playwright-mcp/` folder that the site read left in the project root.
   - Register on Align.
   - **Device setup.**
     - Redmi 14C: developer options, USB debugging, HyperOS "Install via USB" (needs a Mi account), turn off MIUI optimizations if offered.
     - Install Solflare/Phantom plus Solana Mobile's Mock MWA Wallet or fakewallet (needs a secure lock screen).
     - An **Android 16 emulator** (API 36) for the Live Update path and stock-Android behavior.
1. **De-risking spikes (days 1–4), each with a pass/fail result and a fallback:**
   - **(a) PDA-executor CPI into ORE `deploy`, on a Surfpool mainnet fork.** Check the CHECKPOINT_FEE flow, Discretionary with a fixed fee, conditions enforcement, and compute units and rigs per transaction. **Fail →** a KMS executor with the same published bound.
   - **(b) secp256r1 end to end from the Redmi's Keystore.** Confirm TEE-backed P-256 and attestation, DER→raw conversion, low-S normalization, a compressed key; prove that replay, wrong-round and high-S signatures are rejected.
   - **(c) Redmi device truths.**
     - Overnight (8h) foreground-service survival under HyperOS, with and without Autostart/"No restrictions", holding a WebSocket.
     - Heartbeat delivery on your real network.
     - Accelerometer face-down fidelity.
     - Exact alarm plus full-screen intent over the lock screen.
     - Vibration motor strength.
     - Google Play services for FCM and Nearby.
   - **(d) Wallet limits.** Transaction size and versions via `get_capabilities` in Solflare, Phantom and the mock wallet.
   - **(e) SGT path on the fork.** Use Surfpool cheatcodes to give your test wallet a cloned SGT token account, then run `verify_seeker` plus the spoof suite.
   - **(f) First Radiants audit run** on the repo skeleton.
2. **Program + crank core:** `register_rig`, `verify_seeker`, `refuel`, `arm_shift`, `dig`, `end_shift`, with fork tests against live ORE. Mainnet with tiny caps; **your Redmi guest rig mining nightly from then on**.
3. **Android core loop:** MWA/SIWS, trampoline, QS tile, shift foreground service and heartbeat signer, OEM keep-alive onboarding, ongoing notification (Live Update on the emulator), exact-alarm reveal, clock-out with the buy leg, widget.
4. **Foreman AI:** collect accelerometer classifier data (≥2k labelled events on the Redmi across nightstand, desk and table; plus tester data), forecaster backtest, rhythm model, model cards.
5. **Crew + SKR:** rooms and Muster, Stack (Nearby, in-person tables among friends' Android phones), Focus Bond, Gift a Rig, Bury auction.
6. **Proof + hardening:** indexer and dashboard, fuzzing and Kani, THREAT_MODEL, reproducible builds, clean audit re-run.
7. **Traction + submission:**
   - 20 alpha testers, then 100–300 rigs, **recruiting Seeker owners remotely** (Seeker Telegram/Discord, Radiants and ORE Discords, Superteam Nigeria) so SGT-verified rigs, Seed Vault signing and Live Updates on Seeker are validated in the field and appear on the dashboard.
   - Build in public on X in your own voice.
   - dApp Store submission (review takes 3–5 days).
   - A 3-minute demo **filmed on the Redmi**: cold open on the nightstand, the StaleHeartbeat rejection on Solscan, the morning haul, a table Stack with friends' phones, Gift a Rig, and the dashboard including SGT-verified Seeker rigs from testers.
   - A 17-slide deck with criterion-tagged claims.
   - A `skrIntegration` field draft.
   - Agree milestones with ORE.

**Switch rule:** Roll Call is **no longer a practical fallback**, because it depends on the SGT and you have no Seeker. If the Align AI/SKR form governs judging **and** spike 1(a) fails, the fallback is Heads Down with the KMS executor and its published bound, with extra weight on the Foreman AI and SKR pillars. Gateman is the alternative if ORE itself becomes unworkable.

## Questions to settle early
- **Radiants office hours:** which rubric governs, how the AI score is used, and whether a demo filmed on a non-Seeker Android is judged any differently.
- **ORE (Hardhat Chad):** what "strongest qualifying integration" means, and which milestone metrics count.
- **Solana Mobile:** whether executor transactions that reference the user's wallet affect Activity Tracking scoring.
- **Name:** check "Heads Down" in the dApp Store, on GitHub and on X. Fallbacks: Face Down, Dark Shift, Nightstand.

## Verification
- **Program:** LiteSVM/Mollusk unit tests, including the SGT spoof suite (wrong group, wrong authority, zero balance, fake mint) and the P-256 suite (replay, stale counter, wrong round, high-S, offsets pointing into another instruction). Also Trident fuzzing of `dig`, settlement and bury; Kani proofs for caps and Stack conservation; and Surfpool fork tests against the live ORE binary (including SGT flows via cheatcodes) in nightly CI.
- **End to end on the Redmi 14C:** clock in with Solflare/Phantom, lay the phone face-down, and confirm on Solscan that digs succeed with a Secp256r1SigVerify instruction and an ORE `deploy` CPI. Then lift the phone and confirm the crank's dig fails with `StaleHeartbeat`. Kill the app (HyperOS) and confirm that no heartbeat means no dig and no loss. After the morning reveal, confirm that clock-out claims and buys and that the ShiftLog appears on-chain. Test a Stack table with friends' phones: a bump (no break) and a pickup (forfeit).
- **Emulator (API 36):** Live Update promotion and a stock-Android overnight soak.
- **Seeker coverage via remote testers:** SGT-verified rig registration on mainnet, Seed Vault signing of the clock-in transaction, and a `.skr` gift claim, all visible on the dashboard and Solscan.
- **Submission readiness:** the Radiants audit is clean, the AlignAI MWA fixtures pass, the APK installs from a clean state on the Redmi, the repo clones and runs from the README, and the dashboard metrics match on-chain data.
