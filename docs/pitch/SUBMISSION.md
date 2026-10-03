# Heads Down: Align submission form, draft answers

Draft for the founder, written 2026-09-29 and brought up to the state of `main` on 2026-10-03.
Paste only the text inside the quote blocks. Everything
outside them is a note for the founder. Form fields are listed in
`docs/research/judges-preferences.md` (submission form finding). Submissions close 2026-10-09
06:59 UTC.

---

## 1. Project

**Name:** Heads Down

**Tagline:**

> Your phone's night shift, powered by ORE.

**Project description (short, about 620 characters):**

> Heads Down gives an idle Android phone a night shift. Tap a Quick Settings tile, approve one
> wallet transaction, and lay the phone face-down: it digs ORE from your own ORE Automation
> account. The Automation's executor is a program address that signs ORE's deploy only when the
> phone's Android Keystore P-256 key has signed a fresh heartbeat for the current ORE round,
> verified on-chain by Solana's secp256r1 precompile. Pick the phone up and the rig goes cold. No
> server, crank or team key can deploy your SOL. Guest rigs run on any Android; Seeker owners get a
> tier verified in-program by their Seeker Genesis Token.

**Project description (long):** the short text, then:

> Price: ORE keeps about 10.5% of the SOL a miner deploys. A backtest over 58,801 real ORE rounds
> found that spreading a small nightly budget over every round costs 36 to 90% more than buying
> ORE. So the program digs 0.001 SOL chunks only when a Motherlode-aware gate, computed on-chain from
> ORE's own Board and Treasury, says mining is the cheaper route, and the rest of the budget buys
> ORE at clock-out. In the backtest that was 1.4 to 3.0% cheaper than buying. The clock-out buy leg
> is `[live / in progress]`.
>
> Trust: user SOL never leaves ORE's own Automation account. Any crank can submit digs, but only
> the program chooses amounts and squares. Every key has a written worst case (docs/THREAT_MODEL.md).
> The SGT verifier and the secp256r1 introspection library are standalone open-source crates.
>
> Status: `[update before submitting, from section 4]`.

---

## 2. Form fields

| Field | Draft answer | Source or note |
|---|---|---|
| `priorFunding` | "None." | `[founder to confirm]`. USDC prizes need no VC or angel funding (`docs/research/judges-preferences.md`) |
| `builtLast3Months` | "Yes. The repository was created on 2026-09-29 (first commit 4e5d8ba, 2026-09-29 17:59 +01:00)." | `git log --reverse main` |
| `previousHackathonWin` | `[founder to answer]` | not in the repo |
| `portingFeatures` | "No. All code was written in this repository for this project." | `[founder to confirm]` |
| `newMobileDevelopment` | "Yes. A native Android app in Kotlin and Jetpack Compose (minSdk 31, targetSdk 36), written in this repository: a Quick Settings tile, a foreground shift service, Android Keystore P-256 signing and Mobile Wallet Adapter 2.2.0. It is not a PWA or WebView wrapper." | `android/README.md` |
| `skrIntegration` | Version A or B below (at most 950 characters) | `docs/SKR.md` |
| Deck URL | `[TBD: deck URL]` | built from `docs/pitch/DECK.md` |
| Video URL | `[TBD: video URL]` | filmed from `docs/pitch/DEMO_SCRIPT.md` |
| Repositories | `https://github.com/OoJae/heads-down` | public since 2026-10-01 |
| APK URL | `[TBD: APK URL]` | must install from a clean state on the Redmi 14C |

### `skrIntegration`

Both describe the code as built (`docs/SKR.md` §8). Use **A** only if the program is deployed on
mainnet when you submit. Otherwise use **B**, which says it is not. Character counts are checked
by the command in section 5.

<!-- skr-A:start -->
> SKR is Heads Down's commitment collateral and gifting currency, built into its Solana program.
> Stack: players bond SKR on keeping their phones face-down for a window of ORE rounds, at a table
> in person or remotely (Seekers only, SGT re-checked on-chain). A seat counts a round only if its
> phone's hardware-key heartbeat, verified by Solana's secp256r1 precompile, landed in that round;
> a recorded pickup breaks it. Anyone can settle: finishers get their bond back plus 80% of
> forfeits, and 20% goes to a no-oracle Dutch auction where buyers pay ORE that the program sends
> through ORE's own bury instruction. Focus Bond: SKR locked on one shift comes back if the shift
> completes, else it goes to that auction. Gift a Rig: SOL, which the sender can swap from SKR in
> the same transaction, is escrowed for a wallet or a Seeker Genesis Token that only its current
> holder can claim. Heads Down emits no SKR, pays nothing for holding it and keeps none.
<!-- skr-A:end -->

<!-- skr-B:start -->
> SKR is Heads Down's commitment collateral and gifting currency. It is built into the Solana
> program and tested on a fork of mainnet; the program is not deployed on mainnet yet. Stack:
> players bond SKR on keeping their phones face-down for a window of ORE rounds. A seat counts a
> round only if its phone's hardware-key heartbeat, verified by Solana's secp256r1 precompile,
> landed in that round. Finishers get their bond back plus 80% of forfeits; 20% goes to a
> no-oracle Dutch auction where buyers pay ORE that goes through ORE's own bury instruction. Focus
> Bond: SKR locked on one shift, chosen on the app's home screen, comes back if the shift
> completes, else it goes to that auction. Gift a Rig: SOL, which the sender can swap from SKR, is
> escrowed for a wallet or a Seeker Genesis Token that only its current holder can claim. Heads
> Down emits no SKR, pays nothing for holding it and keeps none.
<!-- skr-B:end -->

---

## 3. Criterion-by-criterion claims

Factual statements with their evidence, for the deck text and any free-text field. **Built** means
code with passing tests in the repo. **Designed** means specified, not built. **Next** means planned.

### AI (Align 20)

- **Built:** a Cost Forecaster (ridge regression, 19 features, 1,144 hourly rows of ORE data). One
  hour ahead its error is 9.2% lower than persistence; at eight hours it has no skill.
  `ml/forecaster/MODEL_CARD.md`
- **Built:** it failed its pre-set promotion test against the program's on-chain rule
  (+0.03%, 95% CI [−0.13, +0.18]) and was demoted to advisory. It never gates a dig.
  `ml/forecaster/RESULTS.md` §5
- **Built:** on-device face-down and pickup detection from the accelerometer alone, with angle and
  time hysteresis, because the Redmi 14C has no gyroscope.
  `android/feature/shift/src/main/kotlin/xyz/headsdown/feature/shift/FaceDownDetector.kt`
- **Built, trained on synthetic data:** an on-device pickup/bump classifier (39 features over
  5-second accelerometer windows). On synthetic tests it keeps at least 99.85% pickup recall with
  at most 0.2% of bumps breaking a shift. No recording from a real phone exists yet, so these
  numbers say nothing about real nights; retraining on Redmi recordings is next. It can only add
  a break, never remove one. `ml/foreman/README.md`
- **Built, evaluated on simulated users:** a Shift Planner that predicts idle windows from the
  app's own log and proposes the shift window. `ml/foreman/README.md`
- **Built:** a phone-signed plan cannot exceed the wallet-signed caps; the program rejects it with
  `PlanExceedsCaps`. `programs/heads-down/tests/tests/shifts.rs`
- Sensor data never leaves the phone. `docs/PRIVACY.md` §1

### SKR Integration (Align 20)

- **Built:** four roles in the program (Stack bonds, Focus Bonds, Gift a Rig, the Bury auction):
  13 instructions, tested on a fork of live mainnet ORE, with ORE's real `bury` in the auction.
  None pays anything for locking SKR, no SKR is emitted or routed to the team, and Solana Mobile's
  SKR program is never called. `docs/SKR.md` §6, §9, `programs/heads-down/INTERFACE.md` §11
- **Built:** forfeits go 80% to finishers and 20% to a no-oracle Dutch auction for ORE through
  ORE's `bury`, with the invariant `sum(payouts) + bury == sum(bonds)`. `docs/SKR.md` §1, §5
- **Built:** the crank lands Stack check-ins and settlement, forfeits broken bonds and refunds
  expired gifts; the indexer and the dashboard report every SKR flow from its events.
  `crank/README.md`, `dashboard/src/app/skr/page.tsx`
- **Built, not on a device:** the Focus Bond in the app: chosen on the home screen, locked by
  the clock-in transaction, returned at the next clock-in after a completed shift.
  `android/app/src/main/kotlin/xyz/headsdown/rig/FocusBondSetting.kt`
- **Next:** the Stack and Gift screens (their transactions are built and tested).
  `android/core/chain/src/main/kotlin/xyz/headsdown/core/chain/ix/SkrInstructions.kt`
- **Built:** the in-program SGT verifier that Seeker-only tables and SGT-escrowed gifts rely on.
  `crates/sgt-verify/README.md`
- **Status:** tested on a fork of mainnet; not deployed on mainnet yet `[update at submission]`.

### UX (Align 15, public 25)

- **Built, not on a device:** clock-in is one Quick Settings tile tap and one wallet approval. The
  one transaction bundles ORE `automate`, rig registration, caps and arming (677 bytes as legacy).
  `android/INTERFACE-NOTES.md` §3
- **Built, not on a device:** heartbeats need no wallet prompt. The rig key signs with the screen
  off, and on-chain caps bound what it can do. `crates/p256-introspect/README.md`,
  `android/README.md`
- **Built, not on a device:** fail-safe. If the OS kills the shift service, the rig goes cold and
  nothing is spent, and a health check reports it. `android/README.md`,
  `android/feature/oem-keepalive`
- **Built:** success is shown only after confirmation with `err == null`. `android/README.md`

### UI (Align 15)

- **Built:** one night-shift palette (charcoal, ember orange for a hot rig, ORE gold for hauls)
  across the app theme and the dashboard. Dashboard chart colours pass contrast and
  colour-vision checks in both themes. `dashboard/README.md`
- **Built, not on a device:** an Android 16 Live Update notification with an Android 14/15
  fallback, and no countdowns. `android/README.md`,
  `android/surface/notification/src/main/kotlin/xyz/headsdown/surface/notification/RigNotificationCopy.kt`
- **Built, not on a device:** the exact-alarm, full-screen morning haul reveal, replayed round by
  round from the indexer's haul API. `android/README.md`

### Innovation (Align 15, public 25)

- **Built:** an ORE Automation executor that signs only for an on-chain-verified phone heartbeat,
  proven on a fork of the live ORE binary (spike 5/5; program 171 tests). Our own search found no public
  repo or store app that gates ORE mining on phone state. `spikes/ore-executor/README.md`,
  `programs/heads-down/README.md`, `buildplan.md`
- **Built:** an SGT check anchored on Token-2022 group membership. It rejects a mint, forged with the
  real Token-2022 program, that passes a model of ORE's former `claim_seeker` checks.
  `crates/sgt-verify/README.md`
- **Built:** a Motherlode-aware price gate computed on-chain from ORE's Board and Treasury, with no
  oracle. `programs/heads-down/INTERFACE.md` (`dig`, gate)

### Ecosystem Impact (Align 15)

- **Built:** two standalone open-source crates, `sgt-verify` and `p256-introspect`, that any Solana
  program can use. `crates/`
- **Built:** usage metrics ORE can recompute from its own `DeployEvent`s (signer = Executor PDA) and
  `ResetEvent`s, with CSV exports. `services/indexer/README.md`, `docs/ORE.md` §9
- **Built:** a permissionless crank anyone can run. Measured crank cost is about 6,050 lamports per
  rig-dig (v0 with a lookup table). `crank/README.md`
- **Finding:** ORE stores `max_production_cost` but `deploy` never reads it; documented for ORE.
  `docs/ORE.md` §9
- **Designed:** ORE milestones M1 to M3 with verifiable targets. `docs/ORE.md` §9

### Stickiness & PMF (public 25)

- **Designed:** the shift hangs on bedtime, the charger and the alarm, which people already do
  daily. Focus-only shifts count on nights with no SOL. `docs/SPEC.md` (CORE_LOOP)
- **Built:** D1/D7/D14 cohort metrics computed from on-chain rows only.
  `services/indexer/README.md`
- Traction: `[TBD after launch]`.

### Presentation & Demo (public 25)

- The demo shows a replayed heartbeat skipped with `StaleHeartbeat` and a fresh one digging, on a
  block explorer. `docs/pitch/DEMO_SCRIPT.md` §4
- Tests: program 171, crank 168, registrar 105, `p256-introspect` 30, `sgt-verify` host 69,
  indexer 347, dashboard 48, Android 846. `docs/pitch/DECK.md` slide 14
- A security review before deployment: 114 findings, 18 confirmed by a reproducing test, each fix
  with its regression test, and the open items written down. `docs/SECURITY_REVIEW.md`

---

## 4. Status line for the long description

Pick the true one when you submit, and delete the rest.

- "Live on mainnet with tiny caps; SKR flows `[live / specified]`."
- "The heads_down program, crank, registrar and indexer are built and tested on a mainnet fork; the
  Android app is built and tested in CI; on-device testing on the Redmi 14C is in progress."

---

## 5. Before submitting

- [ ] Every `[TBD]` replaced with a measured value and its source, or the claim removed.
- [x] The app's instruction layouts, heartbeat JSON and SIWS payload match the program, crank and
      registrar (checked 2026-10-03: the app's builders against the program's golden vectors, and
      the phone-less end-to-end run on a local fork of mainnet, `docs/DEVSTACK.md`).
- [ ] Numbers re-run: `cd crank && cargo test`, `cd registrar && cargo test`,
      `cd crates/p256-introspect && cargo test --release --all-features`,
      `cd crates/sgt-verify && cargo test --features std`,
      `cd programs/heads-down && bash scripts/test.sh`, `cd android && ./gradlew test`.
- [ ] `skrIntegration` A or B chosen by what is live.
- [ ] `python3 docs/pitch/check_pitch.py` passes. It checks the wording against `docs/ECONOMICS.md`
      §4, text addressed to the people or tools scoring the submission, every repo path cited,
      both `skrIntegration` lengths (at most 950), slide, question and post counts, and the VO
      length. Last run 2026-10-03: PASS (the script prints both lengths).
- [ ] The final deck file and the video's own transcript scanned for the same wording by hand;
      the script only sees these drafts.
- [x] Repo public (`https://github.com/OoJae/heads-down`).
- [ ] APK installs from a clean state on the Redmi. The dashboard shows real data, or its
      SIMULATED banner.
- [ ] Radiants security audit run on the public repo, and its findings fixed.
