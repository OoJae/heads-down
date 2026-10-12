# Heads Down: Align submission form, draft answers

Draft for the founder, written 2026-09-29, brought up to the state of `main` on 2026-10-03 and to
the mainnet deploy and the first shift on a phone on 2026-10-10
([MAINNET.md](../MAINNET.md)). Paste only the text inside the quote blocks. Everything
outside them is a note for the founder. Form fields are listed in
`docs/research/judges-preferences.md` (submission form finding). Submissions close on Monday
2026-10-12 at 23:59 UTC (the organisers extended the deadline).

---

## 0. The live form (read from the platform on 12 October 2026)

The form the hackathon uses today is not the one sections 1 to 3 were drafted for. Its record lists
four criteria at 25 each (Stickiness and product-market fit, User experience, Innovation,
Presentation and demo), and it has **no description or tagline field**. The prose is three answers
of up to 5,000 characters each: porting, new mobile development, and SKR integration. The platform's
AI coach reads those answers, about 8,000 characters of the deck's text, a transcript of the video
and the security audit. Paste only the text inside the quote blocks. Numbers in square brackets are
filled in from [MAINNET.md](../MAINNET.md) after the shifts of 12 October.

**Title** (up to 200 characters):

> Heads Down: your phone's night shift, powered by ORE

**Porting features:**

<!-- port:start -->
> No. Nothing was ported. All code was written in this repository for this project, starting on
> 29 September 2026.
<!-- port:end -->

**New mobile development:**

<!-- nmd:start -->
> Yes. Heads Down is a native Android app (Kotlin, Jetpack Compose, minSdk 31, targetSdk 36) written
> in this repository for this hackathon, with its own Solana program, relay and indexer. It is not a
> PWA or a WebView wrapper.
>
> WHAT IT IS. Your phone's night shift, powered by ORE. At bedtime you plug the phone in, set your
> alarm, tap Clock in and approve one transaction in your wallet. You lay the phone face-down. While
> it stays down, it digs ORE from your own ORE Automation account, inside a budget and a price
> ceiling your wallet signed. Pick it up and the rig goes cold. After your alarm a page opens over
> the lock screen; you clock out with one approval and the reveal shows the night: the rounds it was
> dark, the digs that landed, what you paid per ORE, and the market price next to it.
>
> WHY YOU COME BACK. A completed night adds to a streak that the program stores on-chain, with two
> nights off every 30 days, so one bad night does not end it. The reveal follows your own alarm. A
> home-screen widget and a Quick Settings tile show the rig. A Nights screen keeps every shift, and
> a share card shows the night without a wallet address. The posture is the one Pokémon Sleep's own
> tips teach (plugged in, face-down, all night), in an app that has passed 30 million downloads.
> Here the morning holds a real asset the user owns.
>
> WHAT IS NEW. The referee is the phone's own secure hardware. About once a minute the app signs a
> heartbeat with a P-256 key that cannot leave the Android Keystore. The Solana program lets ORE's
> deploy happen only when that signature verifies on-chain, through the secp256r1 precompile, in the
> same transaction as the dig. No server can say the phone was face-down, ours included: any relay
> can carry a heartbeat, none can forge one, and the program alone chooses amounts and squares. The
> SOL never leaves ORE's own Automation account, and "Take it back" withdraws what was not placed.
>
> MOBILE-NATIVE PARTS, all written here: Mobile Wallet Adapter 2.2 (clock-in, clock-out and take-back
> are single approvals); an Android Keystore key with hardware attestation checked by our registrar;
> a foreground shift service that reads the accelerometer and the charger (the test phone has no
> gyroscope); a Quick Settings tile, home-screen widgets, an Android 16 Live Update and haptics; an
> exact alarm aligned to the user's own alarm for the reveal; and an on-device model that tells a
> knock on the table from a pickup.
>
> PLAIN ABOUT MONEY. Mining is one route to ORE, not income. The rig digs only while ORE's own cost
> figure is under the ceiling, the reveal shows the price paid beside the market price, and the
> clock-out can buy ORE through Jupiter instead. In a backtest over 58,801 ORE rounds, digging a
> little every round cost 36 to 90% more than buying, which is why the gate exists.
>
> WHAT HAS RUN, AND WHERE. Every transaction is listed in docs/MAINNET.md.
> - Solana mainnet since 10 October 2026: the program HDn4vgLWFLLdexKEwfZwRHjWtizNvdqFteLbMsE67F9p,
>   the relay, the registrar, the indexer and a public dashboard.
> - One rig, the founder's only phone, a Redmi 14C (Android 16, HyperOS 3) with Jupiter Mobile:
>   [9] shifts and [30] digs to 12 October, each dig gated by a heartbeat from the phone's secure
>   hardware; on 12 October a shift of [three] hours that sealed as completed, and the reveal after
>   the alarm; pickups that ended earlier shifts; clock-outs; SOL taken back to the lamport; and
>   [a first ORE purchase at clock-out].
> - An Android 14 emulator against a local fork of mainnet: the same app end to end, signed in
>   Solana Mobile's test wallet.
> - [1,375] automated tests of the app, 171 of the program and 228 of the relay.
>
> WHAT IS OPEN, stated once. It has not run on a Seeker: the program can verify a Seeker Genesis
> Token (tested on a fork with real tokens) and the app has no screen for it yet. The filmed build
> digs under a raised price ceiling. The pickup model is trained on synthetic data. Once a shift's
> budget is spent, the chain stops recording its dark rounds, so a long night seals with the rounds
> of its digs. One key can upgrade the program. There has been no third-party audit. The APK is
> debug-signed and not yet on the dApp Store. The founder is its only user so far.
<!-- nmd:end -->

**SKR integration** (finish after the bonded shift: the bracketed parts are that run):

<!-- skr-live:start -->
> SKR is Heads Down's commitment collateral. The app's Focus Bond lets you lock SKR on tonight's
> shift: if the shift completes, the same SKR comes back; if you pick the phone up, it is forfeit.
>
> IT RAN ON MAINNET. On 12 October 2026, on the founder's Redmi 14C: 10 SKR locked at clock-in
> [transaction], a [35]-minute shift that sealed as completed, and the same 10 SKR released to the
> wallet at clock-out [transaction]. The lock and the release are checked against the shift's own
> on-chain log, which exists only because the phone's secure hardware signed its heartbeats.
>
> WHY SKR. It is the token Seeker owners already hold from using their phone, so a bond in SKR puts
> something that came from the device behind a habit on the device. Heads Down emits no SKR, pays
> nothing for holding it and keeps none.
>
> WHO DECIDES, AND WHO IS PAID. In commitment apps the company usually judges the failure and keeps
> the money. Here the breach is a message signed by the phone's secure hardware and checked by the
> Solana program. And by the program's rule a lost bond is never paid to the team or to another
> player: it goes to an on-chain Dutch auction with no oracle, whose buyers pay ORE that goes
> through ORE's own bury instruction.
>
> IN THE PROGRAM TODAY (deployed on mainnet; tested on a fork of mainnet and end to end on the
> emulator): the Focus Bond (lock, release, forfeit); Stack, a table of players who each bond SKR on
> keeping their phones face-down for a window of ORE rounds, where a seat counts a round only if its
> phone's heartbeat verified in that round; Gift a Rig, SOL escrowed for a wallet or a Seeker Genesis
> Token that only its holder can claim; and the auction.
>
> IN THE APP TODAY: the Focus Bond chooser on the home screen, the bond's line on the clock-in
> sheet, and its release at clock-out. Not in the app yet: the Stack and Gift screens.
>
> NOT RUN ON MAINNET: a forfeit (the auction's vault has not been created there), Stack, Gift a Rig
> and the Seeker Genesis Token check.
>
> WHAT A BOND CAN COST THAT IS NOT YOUR DOING. A bonded shift is lost if none of its heartbeats
> reaches the chain, so a relay outage or a call that lights the screen can cost the bond. A rule
> that tells a flinch from an outage is the next change to the program.
<!-- skr-live:end -->

Sections 1 to 3 below are the drafts for the earlier form. Their facts stand as of 10 October;
their fields are not on the live form.

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
> as its wallet), has run five short shifts on 10 and 11 October: 20 digs of 0.001 SOL, each one
> gated by a heartbeat signed by a key in the phone's secure hardware, a BREAK each time a shift
> was interrupted, and after the first shift a clock-out and the SOL left in its ORE Automation taken
> back. They ran on a debug build with the cost ceiling raised; the default build would not have dug
> on those evenings. The rig's Miner holds its first 0.0019 ORE, unclaimed. There are no
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
| Deck URL | `https://oojae.github.io/heads-down/deck/` (as a file: `https://oojae.github.io/heads-down/deck/heads-down-deck.pdf`) | 18 slides, published 2026-10-11 from `site/deck/` (`docs/pitch/DECK.md` is its script; `scripts/pitch/render-deck.sh` prints the PDF). Two of its app pictures are from the Redmi (11 October) and the others from the emulator; each caption says which |
| Video URL | `[TBD: video URL]` | filmed from `docs/pitch/DEMO_SCRIPT.md` |
| Repositories | `https://github.com/OoJae/heads-down` | public since 2026-10-01 |
| APK URL | `https://github.com/OoJae/heads-down/releases/download/v0.1.0-clock-in/heads-down-0.1.0-clock-in.apk` | Published 2026-10-11 as the pre-release `v0.1.0-clock-in` ([notes](https://github.com/OoJae/heads-down/releases/tag/v0.1.0-clock-in)): the debug-signed mainnet build with the demo policy and no debug screens, from commit 395cc41; sha256 `5bfc9bee331ac1ab3a2cb3440a2f2448bc4562adb4e9508848691417fee5a17e`. That exact file was installed over the earlier build on the Redmi and clocked out and took SOL back there. Not done: an install from a clean state on the Redmi (it would destroy the rig's key), and a release signing key |

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
  The Quick Settings tile as the way in has run on an emulator only (11 October 2026: tapped
  with a clock-in already on-chain, it armed the shift and did not open the wallet); the phone's
  clock-ins were started from the app's "Clock in" button.
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
- Tests: program 171, crank 214, registrar 116, `p256-introspect` 30, `sgt-verify` host 69,
  indexer 477, dashboard 48, Android 1,330. `docs/pitch/DECK.md` slide 15
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
  as its wallet), has run five short shifts on 10 and 11 October: 20 digs of 0.001 SOL, each one
  gated by a heartbeat signed by a key in the phone's secure hardware, a BREAK each time a shift
  was interrupted, and after the first shift a clock-out and the SOL left in its ORE Automation taken
  back. They ran on a debug build with the cost ceiling raised; the default build would not have dug
  on those evenings. The rig's Miner holds its first 0.0019 ORE, unclaimed. There are no
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
      length. Last run 2026-10-11, after the deck and the second evening were written in: PASS
      (the script prints both lengths). Run it again after the last edit.
- [ ] The final deck file and the video's own transcript scanned for the same wording by hand;
      the script only sees these drafts.
- [x] Repo public (`https://github.com/OoJae/heads-down`).
- [ ] APK installs from a clean state on the Redmi. The hosted APK installed over the earlier
      build there (2026-10-11); a clean install was not tried, because clearing the app destroys
      the rig's key (see the APK row in section 2).
- [x] The dashboard's data is real. The indexer it reads serves the dataset `mainnet`, not the
      simulator (read 2026-10-10 20:44 UTC: 1 rig, 5 rounds dug, 5,000,000 lamports deployed,
      0 ORE mined; on 2026-10-11 at 20:09 UTC: 1 rig, 20 rounds dug). Look at the page itself
      once before filming.
- [ ] Radiants security audit run on the public repo, and its findings fixed.
