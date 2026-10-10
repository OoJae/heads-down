# Heads Down: Align submission form, draft answers

Draft for the founder, written 2026-09-29, brought up to the state of `main` on 2026-10-03 and to
the mainnet deploy and the first shift on a phone on 2026-10-10
([MAINNET.md](../MAINNET.md)). Paste only the text inside the quote blocks. Everything
outside them is a note for the founder. Form fields are listed in
`docs/research/judges-preferences.md` (submission form finding). Submissions close on Monday
2026-10-12 at 23:59 UTC (the organisers extended the deadline).

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
> server or crank can deploy your SOL. Guest rigs run on any Android; Seeker owners get a
> tier verified in-program by their Seeker Genesis Token.

**Project description (long):** the short text, then:

> Price: ORE keeps about 10.5% of the SOL a miner deploys. A backtest over 58,801 real ORE rounds
> found that spreading a small nightly budget over every round costs 36 to 90% more than buying
> ORE. So the program digs 0.001 SOL chunks only when a Motherlode-aware gate, computed on-chain from
> ORE's own Board and Treasury, says mining is the cheaper route, and the rest of the budget buys
> ORE at clock-out. In the backtest that was 1.4 to 3.0% cheaper than buying. The clock-out buy leg
> is built and tested, and the app does not offer it yet.
>
> Trust: user SOL never leaves ORE's own Automation account. Any crank can submit digs, but only
> the program chooses amounts and squares. Every key has a written worst case (docs/THREAT_MODEL.md).
> The SGT verifier and the secp256r1 introspection library are standalone open-source crates.
>
> Status on 10 October 2026: the heads_down program is deployed on Solana mainnet
> (HDn4vgLWFLLdexKEwfZwRHjWtizNvdqFteLbMsE67F9p), and the crank, the registrar, the indexer and
> the dashboard run against it. One rig, the founder's own phone (a Redmi 14C, with Jupiter Mobile
> as its wallet), has run one short shift: five digs of 0.001 SOL, each one gated by a heartbeat
> signed by a key in the phone's secure hardware, then a BREAK when the phone was unlocked, a
> clock-out, and the unplaced SOL taken back. That shift ran on a debug build with the cost
> ceiling raised; the default build would not have dug that evening. No ORE was won. There are no
> users. No SKR instruction has run on mainnet. One key can upgrade the program at once, and there
> has been no third-party audit.

The Status paragraph is the line of section 4. Read it again on the day you submit and change
whatever is no longer true.

---

## 2. Form fields

| Field | Draft answer | Source or note |
|---|---|---|
| `priorFunding` | "None." | `[founder to confirm]`. USDC prizes need no VC or angel funding (`docs/research/judges-preferences.md`) |
| `builtLast3Months` | "Yes. The repository was created on 2026-09-29 (first commit 4e5d8ba, 2026-09-29 17:59 +01:00)." | `git log --reverse main` |
| `previousHackathonWin` | `[founder to answer]` | not in the repo |
| `portingFeatures` | "No. All code was written in this repository for this project." | `[founder to confirm]` |
| `newMobileDevelopment` | "Yes. A native Android app in Kotlin and Jetpack Compose (minSdk 31, targetSdk 36), written in this repository: a Quick Settings tile, a foreground shift service, Android Keystore P-256 signing and Mobile Wallet Adapter 2.2.0. It is not a PWA or WebView wrapper." | `android/README.md` |
| `skrIntegration` | Version A below (at most 950 characters); B is a shorter text that is also true | `docs/SKR.md` §8 |
| Deck URL | `[TBD: deck URL]` | built from `docs/pitch/DECK.md` |
| Video URL | `[TBD: video URL]` | filmed from `docs/pitch/DEMO_SCRIPT.md` |
| Repositories | `https://github.com/OoJae/heads-down` | public since 2026-10-01 |
| APK URL | `[TBD: APK URL]` | Still to do. No APK is hosted. Needed: a release signing key (none exists; the build that ran on the phone is debug-signed), the registrar set to accept that key's certificate (today it accepts only the founder's debug certificate, `docs/DEPLOY.md` §10.4), a place to host the file, and one install from a clean state on the Redmi 14C |

### `skrIntegration`

Both were rewritten on 2026-10-10 so that every clause is true of mainnet that evening: the program
is deployed, no SKR instruction has been sent there, and the app has only the Focus Bond chooser
(`docs/SKR.md` §8 carries the same two texts). **Use A.** B is the same statement in fewer words,
for a field that turns out shorter. Read the one you paste again on the day: its second sentence
stops being true as soon as an SKR instruction lands on mainnet. Character counts are checked by
the command in section 5.

<!-- skr-A:start -->
> SKR is Heads Down's commitment collateral and gifting currency, built into its Solana program,
> deployed on mainnet on 10 October 2026. No SKR instruction has run there yet, and the app has
> only the Focus Bond chooser. In tests on a fork of mainnet: Stack: players bond SKR on keeping
> their phones face-down for a window of ORE rounds. A seat counts a round only if its phone's
> Keystore-key heartbeat, verified by the secp256r1 precompile, landed in that round. Finishers
> get their bond back plus 80% of forfeits; 20% goes to a no-oracle Dutch auction whose buyers
> pay ORE that goes through ORE's own bury instruction. Focus Bond: SKR locked on one shift comes
> back if the shift completes, else it goes to that auction. Gift a Rig: SOL is escrowed for a
> wallet or a Seeker Genesis Token that only its current holder can claim; paying in SKR needs a
> swap not yet wired in. Heads Down emits no SKR, pays nothing for holding it and keeps none.
<!-- skr-A:end -->

<!-- skr-B:start -->
> SKR is Heads Down's commitment collateral and gifting currency. Four roles are in its Solana
> program, deployed on mainnet on 10 October 2026 and tested on a fork of mainnet: Stack bonds,
> the Focus Bond, Gift a Rig, and a no-oracle auction that sells forfeited SKR for ORE and sends
> that ORE through ORE's own bury instruction. No SKR instruction has run on mainnet yet. In the
> app the Focus Bond chooser is built; the Stack and Gift screens are not. Heads Down emits no
> SKR, pays nothing for holding it and keeps none.
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
- **Status (10 October 2026):** the program that carries them is deployed on mainnet. No SKR
  instruction has been sent there, and the BuryVault account is not created. All four are tested
  on a fork of mainnet only. `[re-check at submission]`

### UX (Align 15, public 25)

- **On a phone, on mainnet (10 October 2026):** clock-in is one wallet approval. On a Redmi 14C
  with Jupiter Mobile, one transaction carried ORE `automate`, the registrar's voucher, rig
  registration, caps and arming
  ([transaction](https://solscan.io/tx/4DvPBhh4UiEhezYPmjcJvHeqSGGfCAj9prnKEFPMGaGRe9sGg7r9A2SMPVTo4UYXjeEkHUc1WCA7VRdznZuPaQht)).
  The Quick Settings tile as the way in has run on an emulator, with Solana Mobile's test
  wallet; whether the phone's clock-in started from the tile was not recorded.
  `android/INTERFACE-NOTES.md` §4
- **On a phone, on mainnet (10 October 2026):** heartbeats need no wallet prompt. The rig key
  signs them, and on-chain caps bound what it can do. Heartbeats signed by the key in the Redmi's
  TEE dug five ORE rounds
  ([the first](https://solscan.io/tx/25nBRCP6GExrKG4EDszGrKfWN9yYgJMopgnawTGi2Z9XNGj6UCtZgE6HFJN7rJXvu2C3VMjhh7bpXZnQg8DVzovY)).
  Before that, the real app on an Android 14 emulator had a Keystore-signed heartbeat dig a
  round on a fork of mainnet (`scripts/devstack/emulator-smoke.sh`).
  `crates/p256-introspect/README.md`, `android/README.md`
- **Built, not tried on a device:** fail-safe. If the OS kills the shift service, the rig goes
  cold and nothing is spent, and a health check reports it. No whole night has run under HyperOS
  yet. `android/README.md`, `android/feature/oem-keepalive`
- **Built, and seen on the phone:** success is shown only after confirmation with `err == null`.
  The clock-out read "Confirmed on-chain. Shift sealed as ended early." and the take-back
  "Confirmed on-chain. 0.00502704 SOL back in your wallet from the ORE Automation.", which is
  what the chain shows to the lamport. `android/README.md`

### UI (Align 15)

- **Built:** one night-shift palette (charcoal, ember orange for a hot rig, ORE gold for hauls)
  across the app theme and the dashboard. Dashboard chart colours pass contrast and
  colour-vision checks in both themes. `dashboard/README.md`
- **Built; its look on a device not recorded:** an Android 16 Live Update notification with an
  Android 14/15 fallback, and no countdowns. The Redmi runs Android 16 under HyperOS 3; what
  HyperOS shows for it was not written down during the first shift. `android/README.md`,
  `android/surface/notification/src/main/kotlin/xyz/headsdown/surface/notification/RigNotificationCopy.kt`
- **Built, not on a device:** the exact-alarm, full-screen morning haul reveal, replayed round by
  round from the indexer's haul API. `android/README.md`

### Innovation (Align 15, public 25)

- **Built, and run on mainnet:** an ORE Automation executor that signs only for an
  on-chain-verified phone heartbeat, proven on a fork of the live ORE binary (spike 5/5; program
  171 tests) and, on 10 October 2026, on mainnet: five digs from one phone, each with the
  secp256r1 check, the `dig` and ORE's `deploy` in one transaction. Our own search found no public
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
- **Built:** a permissionless crank anyone can run. Measured crank cost is about 6,040 lamports per
  rig-dig (v0 with a lookup table, five rigs in one transaction). On mainnet with one rig, on 10
  October 2026, each dig's transaction cost the crank 10,053 to 11,082 lamports and the program
  reimbursed 7,000; over the evening it paid 95,473 in fees and got 35,000 back. `crank/README.md`
- **Finding:** ORE stores `max_production_cost` but `deploy` never reads it; documented for ORE.
  `docs/ORE.md` §9
- **Designed:** ORE milestones M1 to M3 with verifiable targets. `docs/ORE.md` §9

### Stickiness & PMF (public 25)

- **Designed:** the shift hangs on bedtime, the charger and the alarm, which people already do
  daily. Focus-only shifts count on nights with no SOL. `docs/SPEC.md` (CORE_LOOP)
- **Built:** D1/D7/D14 cohort metrics computed from on-chain rows only.
  `services/indexer/README.md`
- Traction: none. There are no users. One rig, the founder's own phone, has run one short shift
  on mainnet (five digs, 0.005 SOL, 10 October 2026).

### Presentation & Demo (public 25)

- The demo shows a replayed heartbeat skipped with `StaleHeartbeat` and a fresh one digging, on a
  block explorer. The video is still `[TBD]` (section 2). The skip itself has happened on mainnet
  without being staged: twice on 10 October 2026 the crank's own second attempt with a heartbeat that had
  already landed was skipped with error 7
  ([one of the two](https://solscan.io/tx/4EJvGg7akbzgQRbT2cYteaHCogpnEaBpP2ncTHLFS1mtCWt5e9aVFJEmeiFgbaShHC7H7t2EkpwxQYKpcWq6PUpM)).
  `docs/pitch/DEMO_SCRIPT.md` §4
- Tests: program 171, crank 213, registrar 116, `p256-introspect` 30, `sgt-verify` host 69,
  indexer 477, dashboard 48, Android 855. `docs/pitch/DECK.md` slide 14
- A security review before deployment: 114 findings, 18 confirmed by a reproducing test, each fix
  of a confirmed finding with its regression test, and the open items written down. `docs/SECURITY_REVIEW.md`

---

## 4. Status line for the long description

**Use this one.** It was true on the evening of 10 October 2026, and it is the Status paragraph of
the long description in section 1. Check it again on the day you submit, clause by clause, and
change what has moved: another shift, another wallet, an SKR instruction on mainnet, a user who is
not the founder, a change of the upgrade key. The record to check it against is
[MAINNET.md](../MAINNET.md).

- "Status on 10 October 2026: the heads_down program is deployed on Solana mainnet
  (HDn4vgLWFLLdexKEwfZwRHjWtizNvdqFteLbMsE67F9p), and the crank, the registrar, the indexer and
  the dashboard run against it. One rig, the founder's own phone (a Redmi 14C, with Jupiter Mobile
  as its wallet), has run one short shift: five digs of 0.001 SOL, each one gated by a heartbeat
  signed by a key in the phone's secure hardware, then a BREAK when the phone was unlocked, a
  clock-out, and the unplaced SOL taken back. That shift ran on a debug build with the cost
  ceiling raised; the default build would not have dug that evening. No ORE was won. There are no
  users. No SKR instruction has run on mainnet. One key can upgrade the program at once, and there
  has been no third-party audit."

The two earlier options are gone: one claimed live SKR flows, which do not exist, and the other
described the state before the deploy.

---

## 5. Before submitting

- [ ] Every `[TBD]` replaced with a measured value and its source, or the claim removed.
- [x] The app's instruction layouts, heartbeat JSON and SIWS payload match the program, crank and
      registrar (checked 2026-10-03: the app's builders against the program's golden vectors, and
      the phone-less end-to-end run on a local fork of mainnet, `docs/DEVSTACK.md`; and on
      2026-10-10 on mainnet, where the deployed program, the live crank and the live registrar
      accepted what the app on the Redmi sent).
- [ ] Numbers re-run: `cd crank && cargo test`, `cd registrar && cargo test`,
      `cd crates/p256-introspect && cargo test --release --all-features`,
      `cd crates/sgt-verify && cargo test --features std`,
      `cd programs/heads-down && bash scripts/test.sh`, `cd android && ./gradlew test`.
- [x] `skrIntegration` chosen by what is live: A, rewritten on 2026-10-10 for a deployed program
      on which no SKR instruction has run. Read it once more on the day.
- [x] The heads_down program is deployed on mainnet and one shift has run on the Redmi
      (2026-10-10; [MAINNET.md](../MAINNET.md)).
- [ ] `python3 docs/pitch/check_pitch.py` passes. It checks the wording against `docs/ECONOMICS.md`
      §4, text addressed to the people or tools scoring the submission, every repo path cited,
      both `skrIntegration` lengths (at most 950), slide, question and post counts, and the VO
      length. Last run 2026-10-10: PASS (the script prints both lengths). Run it again after the
      last edit.
- [ ] The final deck file and the video's own transcript scanned for the same wording by hand;
      the script only sees these drafts.
- [x] Repo public (`https://github.com/OoJae/heads-down`).
- [ ] APK installs from a clean state on the Redmi. Not possible yet: no APK is hosted and no
      release signing key exists (see the APK row in section 2).
- [x] The dashboard's data is real. The indexer it reads serves the dataset `mainnet`, not the
      simulator (read 2026-10-10 20:44 UTC: 1 rig, 5 rounds dug, 5,000,000 lamports deployed,
      0 ORE mined). Look at the page itself once before filming.
- [ ] Radiants security audit run on the public repo, and its findings fixed.
