# Heads Down: pitch deck script (18 slides)

Draft for the founder, written 2026-09-29, brought up to the state of `main` on 2026-10-03, to
the mainnet deployment on 2026-10-10, and on 2026-10-11 to the first shift on mainnet and to the
app's redesign. Every number cites the repo file, the receipt or the transaction it comes from.
The deck built from this script is `site/deck/index.html`; `scripts/pitch/render-deck.sh` prints
`site/deck/heads-down-deck.pdf` from it. The slides carry the short text under "On slide"; the
longer sentences are in the speaker notes.

- The values of the first shift on mainnet from the Redmi 14C were recorded on 2026-10-10 and are
  filled in below: every transaction is in `docs/MAINNET.md` with what it did. In the built deck
  the spans that carry them keep their names (`data-slot="first-clock-in-tx"`, `first-dig-tx`,
  `digs-landed`, `break-tx`, `replay-skipped-tx`, `clock-out-tx`, `shift-summary`).
- The app pictures: `site/deck/img/redmi-*.png` are screenshots from the founder's Redmi 14C on
  mainnet on the evening of 2026-10-11; `site/deck/img/app-*.png` are the same build (the
  localdev flavour) on an Android 14 emulator against the local fork of mainnet, taken the same
  day. Each caption says which. On the phone the redesigned build clocked in three times and dug
  ten rounds; its clock-out and its way out have run on the emulator only.
- `[TBD: founder]`: only the founder can supply it (the photo, the personal line).

**Status chips used on slides.** **Built** = code plus passing tests in the repo. **Built, not on a
device** = covered by unit tests or LiteSVM on the build machine, never run on a phone. **On an emulator** = the real app ran it
on an Android 14 emulator against a local fork of mainnet (`scripts/devstack/emulator-smoke.sh`),
not on a phone. **On the Redmi** = the real app did it on the founder's Redmi 14C (Android 16,
HyperOS 3). **On mainnet** = deployed or run on Solana mainnet, with a receipt or a transaction.
**Designed** = specified in `docs/`, not in code. **Next** = planned for the build.

**Rubrics** (`docs/research/judges-preferences.md`):

- Public, 4 × 25%: Stickiness & PMF, UX, Innovation/X-factor, Presentation & Demo.
- Align form: AI 20, SKR 20, UX 15, UI 15, Innovation 15, Ecosystem Impact 15.

**Wording.** Follow `docs/ECONOMICS.md` §4: dig, haul, bond, "accumulate ORE by the cheaper route".
The slide text and speaker notes below contain no word from its banned list.

**Three things stated wherever they apply.** Where trust or security is claimed: one key, the
founder's, can upgrade the program (no multisig, no delay), and there has been no third-party
audit. Where a dig on mainnet is shown: the demo build raises the price ceiling (plan 1.0 SOL per
ORE under a wallet ceiling of 1.2; the defaults are 0.53 under 0.67) and lowers the budgets to
0.005 SOL a shift and 0.035 SOL a week (`docs/DEPLOY.md` §10.7).

**The state on 2026-10-10.** `heads_down` is deployed on mainnet: program
`HDn4vgLWFLLdexKEwfZwRHjWtizNvdqFteLbMsE67F9p`, slot 455,359,196, transaction
`4naPfgTXDKRxG7NhunoNKVBWkCwuvdJoyBGytmebCpuDzdrP6Gm4UwxNYm5r5zzUu8vuZKgYiaFp1BzrTwH2Pae2`
(`deploy/receipts/mainnet/20261010T184116Z-fresh-d67a1a40c2a0.json`). Its Config is initialized
(`deploy/receipts/mainnet/20261010T184715Z-init.json`). The crank, the registrar, the indexer and
the dashboard run on Railway against mainnet. The app ran on the Redmi 14C: setup, a rig key made
in the phone's TEE, its attestation chain accepted by the live registrar, and a sign-in signed in
Jupiter's wallet. The phone's first shift on mainnet ran the same evening: five digs, a pickup's BREAK, a clock-out and the SOL taken back to the lamport (`docs/MAINNET.md`). There are no users.

---

## Slide 1: Heads Down, your phone's night shift

- **Message:** Face-down, your phone digs ORE from your own ORE Automation, and only a heartbeat
  signed inside the phone can switch it on.
- **On slide:**
  - "Heads Down. Your phone's night shift. Powered by ORE."
  - "Face-down, it digs ORE. Pick it up, the rig goes cold."
  - "Any Android. Verified Seeker tier."
  - "Clock In, the Solana Mobile hackathon. On mainnet since 10 October 2026. First shift dug that
    evening, from one Redmi 14C."
- **Visual:** The title set as the wordmark is: the first line in chalk, a line of gold light,
  the second line in gold under it. Beside it the app's first screen
  (`site/deck/img/app-first-launch.png`, emulator): the slab seen from below, and "Turn it over."
- **Speaker notes:** "I'm [TBD: founder's name], a solo builder in Nigeria, building with AI agents.
  Heads Down gives an idle phone a night job. It digs ORE from your own ORE Automation account
  while the phone lies face-down. Only a heartbeat signed inside the phone can switch it on. No
  server can forge one, mine included. The program has been on mainnet since the tenth of October,
  and that evening my own phone ran its first shift there: five digs, a pickup, a clock-out, and
  the SOL back in the wallet to the lamport."
- **Serves:** Public: Presentation & Demo. Align: Innovation.
- **Sources:** `README.md`, `programs/heads-down/README.md`,
  `deploy/receipts/mainnet/20261010T184116Z-fresh-d67a1a40c2a0.json`.

## Slide 2: Most of these phones sit idle

- **Message:** There is a large pool of idle phones, and Seekers are often second phones.
- **On slide:**
  - "Most of these phones sit idle. Heads Down gives the idle one a job."
  - "121,035 Seeker Genesis Tokens minted (on-chain group size when read)"
  - "About 118.9k of about 121k .skr IDs dormant (SeekerTracker)"
  - "Over 9,000 daily active users in Seeker Season 2 (June 2026)"
  - "Three figures from different sources and dates. None is divided by another."
- **Visual:** The three figures as large numbers, the first one in ORE gold.
- **Speaker notes:** "The SGT count is read from chain. The dormancy and daily-user figures are
  third-party numbers from our research notes, from different dates, so I don't divide one by the
  other. The point is the framing: Heads Down doesn't ask you to use your phone less. It gives the
  phone you aren't using a job."
- **Serves:** Public: Stickiness & PMF. Align: Ecosystem Impact.
- **Sources:** `crates/sgt-verify/README.md` (group size), `docs/research/redteam.md` (dormant
  .skr IDs), `docs/research/retention-pmf.md` and `docs/research/seeker-sms-stack.md` (daily users).

## Slide 3: ORE never shipped its mobile miner

- **Message:** ORE advertised mining on Seeker and has no native app. Today's ORE autominers run
  from servers.
- **On slide:**
  - "ORE advertised mining on Seeker and never shipped a native app."
  - "2025: ORE's landing card, 'Proof of mobile … Coming soon'"
  - "26 Sep 2025: ORE adds `claim_seeker`. 6 Nov 2025: ORE removes it"
  - "20 Aug 2026: ore.com becomes a PWA"
  - "29 Sep 2026: 165 to 172 miners per round on mainnet"
  - "10 Oct 2026: `heads_down` on mainnet"
  - "Store autominers (RefinORE, Orestack, Oreminer) run with the phone's app closed."
- **Visual:** A timeline strip from 2025 to October 2026, its last point in ORE gold.
- **Speaker notes:** "The ORE board has fewer than two hundred miners per round, and mobile
  mining was promised and never shipped. The autominers on the dApp Store run from servers; none
  that we found gates mining on the phone itself."
- **Serves:** Public: Innovation/X-factor. Align: Innovation, Ecosystem Impact.
- **Sources:** `docs/research/skr-and-ore.md` (landing card, `claim_seeker` history, PWA),
  `docs/ORE.md` §1 (live Round values), `docs/research/redteam.md` (store autominers),
  `crates/sgt-verify/README.md` (Credits),
  `deploy/receipts/mainnet/20261010T184116Z-fresh-d67a1a40c2a0.json`.

## Slide 4: The ritual

- **Message:** One wallet approval at bedtime; the phone does the rest; pick it up and it cools.
- **On slide (the night, step by step):**
  1. Tap Clock in, in the app or on its Quick Settings tile. **On the Redmi** (the app's button;
     the tile's path has run on the emulator)
  2. One wallet approval funds a capped shift in your own ORE Automation and arms the rig.
     **On mainnet**
  3. Face-down, the phone signs a heartbeat every ORE round, about a minute. **On mainnet**
  4. A dig happens only when the on-chain price gate is open. **On mainnet** (under the demo
     ceiling; a gate that stays shut has not been shown on a phone)
  5. Pick it up: 10 s of cooling, then cold. Unlock: cold at once. **On mainnet**
  6. At your alarm, the morning haul reveal replays the night from chain data.
     **Not on a phone yet**
  7. Clock out: seal the shift. Take your SOL back whenever you like. **On mainnet** (the bond
     and the claim are **Built, not on a device**). The "buy the rest" leg: transactions
     **Built** and tested against recorded Jupiter quotes; the app does not offer it
  - "From the Redmi 14C, 10 Oct 2026, on mainnet, under the demo ceiling: clock-in
    `4DvPBhh4UiEhezYPmjcJvHeqSGGfCAj9prnKEFPMGaGRe9sGg7r9A2SMPVTo4UYXjeEkHUc1WCA7VRdznZuPaQht`, clock-out
    `3VfbzUZHzAYj468iXo2bnsqRfAcrTMt76dHzWFT51ZnrvseFF1bYEzo6fAqurTG8tB9AM1cPRYsXtJK2R25LuTUm`, SOL back
    `46NXJnX54wUdTgBVBGwfid8NQeqRL3LkGCyFkhtqJuVVHwp6R2UbnNxxhTSv8FHgtuyJupEPRQi4Frqf4NAjDcFF`:
    0.00502704 SOL, to the lamport."
- **Visual:** The seven steps as rows, a status chip on each. Beside them the home screen armed,
  on the Redmi (`site/deck/img/redmi-home-armed.png`), and the clock-out sheet on the emulator
  (`site/deck/img/app-clock-out.png`).
- **Speaker notes:** "The habit hangs on three things people already do every night: bedtime, the
  charger and the alarm. If the OS kills the app, the rig goes cold and nothing is spent. On the
  tenth of October my Redmi did all of this on mainnet except the morning reveal: one approval in
  Jupiter's wallet, heartbeats signed by a key in the phone's secure hardware, five digs, a BREAK
  when I picked it up and unlocked it, a clock-out, and the SOL back. Two honest notes. On the
  phone I tapped Clock in inside the app; the tile's path has run on the emulator. And that
  evening the app did not hear that its first clock-in had landed, so I approved a second one;
  the app now finds a landed clock-in on-chain by itself. That repair has run on the emulator; on
  the phone the failure has not come back in the three clock-ins since. The digs ran under a
  price ceiling I raised for the demo."
- **Serves:** Public: Stickiness & PMF, UX. Align: UX, UI.
- **Sources:** `docs/MAINNET.md` (the first shift, every transaction), `android/README.md`
  (clock-in, shift loop), `docs/SPEC.md` (CORE_LOOP), `scripts/devstack/emulator-smoke.sh` (the
  pictures).
- **Checked 2026-10-03:** the app's instructions match the program's golden vectors byte for
  byte (`android/core/chain/src/test/kotlin/xyz/headsdown/core/chain/ix/GoldenInstructionsTest.kt`),
  and the phone-less end-to-end run passes on a local fork of mainnet (`docs/DEVSTACK.md`):
  clock-in, dig, lift, replay refused, indexed.
- **Checked 2026-10-04:** the app itself, on an Android 14 emulator against that fork: its
  Keystore-signed heartbeat was verified on-chain and dug a round; lifting it landed a BREAK;
  and with Solana Mobile's test wallet on the emulator, the clock-in, the clock-out, taking the
  SOL back and closing the rig were each signed in the wallet and confirmed
  (`scripts/devstack/emulator-smoke.sh --wallet`).
- **Checked 2026-10-10:** the app on the physical Redmi 14C (Android 16, HyperOS 3): setup, a rig
  key created in the phone's TEE, its attestation chain accepted by the live registrar, and a
  sign-in signed in Jupiter's wallet (Jupiter Mobile); and that evening its first shift on mainnet
  (`docs/MAINNET.md`).
- **Checked 2026-10-11:** the redesigned app with the clock-in fix, on the Android 14 emulator
  against the fork: both `emulator-smoke.sh` runs pass, and a clock-in that lands without the app
  hearing so is armed from the chain (`docs/MAINNET.md`, "What the first night found", item 4).

## Slide 5: The app

- **Message:** The app is one object and one idea, drawn the same on every screen: a dark slab
  with light underneath.
- **On slide:**
  - "One object on every screen: a dark slab, lit from underneath."
  - "The idea: the app works while you cannot see it. So its mark is the face you never look at:
    light from under a slab."
  - "The object: one shader draws the slab. It stays level with the real world through the
    accelerometer: tilt the phone, or drag, to look underneath."
  - "The colour: one accent at two temperatures. Ember while the rig is armed or cooling; gold
    only for a hot rig and for ORE."
  - "The rules: one action, in the thumb zone. The most a shift can place beside it, before the
    wallet opens. Nothing moves by itself."
  - "The first two: the app on the Redmi 14C, on mainnet, 11 Oct 2026, where this build clocked
    in three times and dug ten rounds. The last two: the same build on an Android 14 emulator
    against the local fork of mainnet; the reveal shows the sample night the app offers before a
    first haul, and says so on screen."
- **Visual:** Four screenshots in a row (`site/deck/img/redmi-home-cold.png`,
  `redmi-home-armed.png`, `app-take-back.png`, `app-reveal.png`): cold and armed on the Redmi,
  Take it back and the morning reveal on the emulator.
- **Speaker notes:** "The first build was a plain dark screen with pill buttons. After it worked
  on mainnet I redesigned it around one idea: this app does its job face-down, so its mark is the
  underside. The slab on the home screen is drawn by one small shader and is held level with the
  real world by the same accelerometer that runs the shift, so tilting the phone to look under it
  teaches the one gesture the product needs. Gold is rationed: it is light, or an amount of ORE,
  never a button. Every sentence and every test tag of the screens that ran on mainnet stayed as
  it was, so the app's tests and the end-to-end run on the emulator, with Solana Mobile's test
  wallet, pass unchanged. The first two pictures are my phone, on the evening of the eleventh:
  this build clocked in three times there and dug ten rounds. The other two are the emulator,
  where its clock-out and the way out have run; on the phone those two ran with the first build."
- **Serves:** Public: UX, Presentation & Demo. Align: UI, UX.
- **Sources:** `android/core/design/` (tokens, type, components), `android/README.md` ("The
  Underside"), `android/app/src/main/kotlin/xyz/headsdown/ui/slab/` (the slab),
  `scripts/devstack/emulator-smoke.sh` (the end-to-end run on the emulator).

## Slide 6: The trustless rig

- **Message:** The executor is a program address that signs only for a fresh phone heartbeat, so no
  server or crank can deploy your SOL. (One key can still upgrade the program: slide 14.)
- **On slide:**
  - "The executor signs ORE's deploy only for a fresh heartbeat from the phone."
  - Diagram, the trust path: **Keystore key** in the phone (P-256, never leaves it) → heartbeat
    → **crank** (anyone can run one) → **secp256r1 precompile** (Solana verifies the signature)
    → **`heads_down`** (the Executor PDA signs) → **ORE `deploy`** from your own Automation,
    which your wallet funds and ORE owns.
  - "No fresh heartbeat, no deploy."
  - "171 program tests, 138 of them on a fork of live mainnet ORE."
  - "On mainnet from the Redmi: a dig
    `25nBRCP6GExrKG4EDszGrKfWN9yYgJMopgnawTGi2Z9XNGj6UCtZgE6HFJN7rJXvu2C3VMjhh7bpXZnQg8DVzovY`, a pickup's BREAK
    `63FZYbEyJyePgGMMHqAxTvwGD5vAvFUggnbkfhmummhPjAzADctceYYJE5UNHp4bFBshJTcGNPDFocE7xw92yqVb`, a replayed heartbeat refused
    `4EJvGg7akbzgQRbT2cYteaHCogpnEaBpP2ncTHLFS1mtCWt5e9aVFJEmeiFgbaShHC7H7t2EkpwxQYKpcWq6PUpM`"
  - "Open today: one key, the founder's, can upgrade the program, with no multisig and no delay.
    No third-party audit. The demo build digs under a raised price ceiling."
- **Visual:** The trust path, drawn. Under it, the three transactions of the trustless beat.
- **Speaker notes:** "Each rig is an ordinary ORE miner with its own Automation. The user sets the
  executor to our program's PDA. The program signs ORE's deploy only if, in the same transaction,
  the precompile verified this rig's key over a message bound to this program, this rig, this
  shift, this ORE round and a counter that must go up. A random signer can't deploy for your
  Automation; ORE checks executor equals signer. The crank decides only whether and when; the
  program computes amount and squares. The program is on mainnet at HDn4…7F9p and its Executor
  is By3v…Pkge. The three transactions on the slide are from my phone's first shift there: a dig,
  the BREAK the phone signed when I unlocked it, and the crank's own second attempt with the same
  heartbeat, which the program refused as stale. That last one was not staged. One key, mine, can
  still upgrade the program, and nobody outside has audited it."
- **Serves:** Public: Innovation/X-factor, Presentation & Demo. Align: Innovation, Ecosystem Impact.
- **Sources:** `spikes/ore-executor/README.md`, `programs/heads-down/README.md`,
  `programs/heads-down/INTERFACE.md` (`dig`), `docs/THREAT_MODEL.md` (K5),
  `docs/DEPLOY.md` §10.7 (the demo ceiling).

## Slide 7: Measured on the live ORE binary

- **Message:** The core path is measured, not estimated.
- **On slide:**

  | What | Value |
  |---|---|
  | Compute for a dig | 39,414 CU with one rig, 67,421 with two (most of it ORE's own `deploy`) |
  | Max rigs per dig transaction | legacy 2 · v0 + lookup table 5 · v1 11 |
  | secp256r1 check | 0 CU in the precompile + 477 CU introspection; one 5,000-lamport fee per signature |
  | Crank cost per rig-dig | about 6,040 lamports (v0 + table), about 5,500 (v1), against 7,000 reimbursed |
  | Seeker check | about 1,900 CU |
  | The binary on mainnet | 190,048 bytes, SBPF v3; the on-chain bytes equal the build |

  - "Rows 1 to 5: LiteSVM on a fork of live mainnet ORE, and a local validator. Row 6: the deploy
    receipt."
- **Visual:** The table, values in a mono font.
- **Speaker notes:** "Transaction size binds before compute: eleven rigs use about 27% of the
  compute limit. The crank also runs against the real program on the fork and end to end on a local
  validator; the fees depend on signature count and are real. The binary on mainnet is the build,
  byte for byte: 198 buffer writes, none sent twice."
- **Serves:** Public: Presentation & Demo (technical depth). Align: Innovation.
- **Sources:** `programs/heads-down/README.md` (Measurements), `programs/heads-down/INTERFACE.md`
  §12.11, `spikes/secp256r1/README.md`, `crank/README.md` (packing, operating costs),
  `crates/sgt-verify/README.md`,
  `deploy/receipts/mainnet/20261010T184116Z-fresh-d67a1a40c2a0.json`.

## Slide 8: Honest economics

- **Message:** Spread thin, mining costs more than buying. Heads Down digs in gated chunks and buys
  the rest: a modest edge in a backtest, stated as modest.
- **On slide:**
  - "Spread thin, mining costs more than buying. Gated chunks came out slightly cheaper in a
    backtest."

  | Backtest: 58,801 real ORE rounds, 47 nights | SOL per ORE, against buying |
  |---|---|
  | Dig every round (0.02 / 0.04 / 0.05 SOL a night) | +90% / +45% / +36% |
  | Dig 0.001 SOL chunks only when the on-chain gate opens, buy the rest | −3.0% / −2.4% / −1.4% |
  | Nights the gate stayed shut all night | 30% (those nights just buy) |

  - "The backtest charged 5,000 lamports per dug round. On mainnet the fee is 10,000, and the
    backtest has not been re-run at that fee."
  - "10 October 2026: ORE's cost figure is about 0.69 to 0.71 SOL per ORE, above the default plan
    of 0.53, so a default build does not dig. The demo build plans 1.0 under a ceiling of 1.2."
- **Visual:** `ml/forecaster/figures/eff_price_vs_budget.png`, with the table beside it.
- **Speaker notes:** "ORE keeps about 10.5% of the SOL you deploy; that is what mined ORE costs.
  Fixed per-dig fees make tiny deploys expensive. So the program digs 0.001 SOL at a time on the
  split squares, only when a Motherlode-aware rule it computes on-chain says mining is the cheaper
  route. Two things I say out loud. First, the backtest charged 5,000 lamports a dug round and the
  program on mainnet charges 10,000; the backtest's own sensitivity is about one point per 1,000
  lamports at this chunk size, so the small edge has to be measured again at the real fee before
  anyone quotes it. Second, today the gate is shut on a default build, so for the recorded demo I
  raised my own ceiling to 1.0 SOL per ORE and cut the budget to 0.005 SOL a shift. This is not a
  money machine; the value is the ritual, the trustless gate and an honest choice of route. There
  is no revenue today: the planned source is a disclosed Jupiter fee on the buy leg the user signs,
  and the app does not offer that leg yet."
- **Serves:** Public: Stickiness & PMF (trust), Innovation/X-factor. Align: Ecosystem Impact, UX.
- **Sources:** `ml/forecaster/RESULTS.md` (TL;DR, §2, §3, §4), `ml/forecaster/orelib.py` (the
  backtest's 5,000-lamport fee), `docs/ECONOMICS.md` (Summary, §1, §5, §7), `docs/DEPLOY.md` §4
  (`executor_fee`) and §10.7 (the demo ceiling),
  `deploy/receipts/mainnet/20261010T184715Z-init.json`.

## Slide 9: AI, the Foreman: on-device, bounded, honest

- **Message:** The AI plans and polices shifts on the phone and can only tighten limits your wallet
  signed. Our forecaster lost to a simple on-chain rule, so it only advises.
- **On slide:**
  - "The AI runs on the phone and can only tighten the limits the wallet signed."
  - "Cost Forecaster: **Built, advisory.** 9.2% lower error one hour ahead than 'same as now'.
    It lost to the on-chain rule, so it never decides."
  - "Pickup detection: **Built.** Accelerometer only. The Redmi 14C has no gyroscope."
  - "Pickup or bump classifier: **Built, trained on synthetic data.** No recording from a real
    phone yet."
  - "Shift Planner: **Built, evaluated on simulated users.** It proposes the shift window from the
    app's own log."
  - "A phone-signed plan above the wallet's caps fails on-chain (`PlanExceedsCaps`). Sensor data
    never leaves the phone."
- **Visual:** Four model cards with status chips.
- **Speaker notes:** "We set a promotion rule before training: the forecaster had to beat the
  simple rule or be demoted. It found real one-hour structure: the cost ratio climbs as the
  Motherlode pot grows and collapses at each hit. But the program reads the live EMA and pot on
  every dig for free, and against that the forecast adds nothing measurable: as a gate, +0.03%
  [−0.13, +0.18] against the live on-chain rule at 0.36 SOL a night. So it explains the night and
  powers the morning accuracy card; it never decides. The classifier is a 39-feature model over
  5-second accelerometer windows. On synthetic tests it keeps at least 99.85% pickup recall with at
  most 0.2% of bumps breaking a shift; those numbers say nothing about real nights until it is
  retrained on recordings from this phone. It can only add a break, never remove one. The planner's
  windows finished 90.6% of simulated shifts, against 57% for a fixed 23:00 to 07:00 window."
- **Serves:** Align: AI (20%), Innovation. Public: Innovation/X-factor.
- **Sources:** `ml/foreman/README.md`, `ml/forecaster/RESULTS.md` §5, `ml/forecaster/MODEL_CARD.md`,
  `android/feature/shift/src/main/kotlin/xyz/headsdown/feature/shift/FaceDownDetector.kt`,
  `buildplan.md` (step 4), `docs/SPEC.md` (AI_FEATURE),
  `programs/heads-down/README.md` (`shifts` suite), `docs/PRIVACY.md` §1.

## Slide 10: Any Android, verified Seeker tier

- **Message:** Guest rigs run on any Android with a mobile wallet. Seeker rigs are verified
  in-program with an SGT check that cannot be forged.
- **On slide:**
  - "Guest rigs run on any Android. Seeker rigs are verified in-program by their SGT."
  - "Guest rig: any Android with a mobile wallet. On the Redmi 14C: a rig key in the TEE that the
    live registrar attested, and a sign-in signed in Jupiter's wallet. **On the Redmi**"
  - "Seeker rig: one verified rig per SGT mint, anchored on Token-2022 group membership, which only
    Solana Mobile's key can write. **Built**"
  - "No Seeker device: tested on a fork of mainnet only, on real SGTs #20 and #121,035."
  - "A mint forged with the real Token-2022 program is rejected: `MissingGroupMember`."
- **Visual:** Two rig cards (Guest, Seeker) and the "forged mint rejected" chip.
- **Speaker notes:** "Solana Mobile opened its stack to all Android makers, and the hackathon rules
  allow any Android device, so the guest tier is honest about what I build on. The Seeker tier keys
  every seat by SGT mint, because the holding wallet can change. A mint forged with the real
  Token-2022 program passes a model of ORE's old `claim_seeker` checks; `verify_sgt` rejects it.
  Of 3,600 single-bit flips of a real mint, only the member-number bytes still verify. Credit to
  ORE's `claim_seeker`, the first on-chain SGT check; we keep its checks and add the anchor. I
  have no Seeker, so this tier has only run on a fork of mainnet."
- **Serves:** Align: Ecosystem Impact, Innovation. Public: Innovation/X-factor.
- **Sources:** `crates/sgt-verify/README.md`, `programs/heads-down/README.md` (`seeker` suite),
  `docs/SPEC.md` (corrections banner), `docs/research/judges-preferences.md` (SMS for all OEMs),
  `docs/SECURITY_REVIEW.md` §4 (the Redmi).

## Slide 11: Built for Android, not wrapped

- **Message:** Native Kotlin and Compose on system surfaces a web app cannot reach.
- **On slide:**
  - "Native Kotlin and Compose, on system surfaces a web app cannot reach."
  - "Quick Settings tile or the app, then one wallet approval (Mobile Wallet Adapter 2.2.0)"
  - "One clock-in transaction: ORE automate, register, caps, arm (one legacy packet)"
  - "Foreground service. Killed by the OS means cold, nothing spent."
  - "HyperOS keep-alive setup and a 'killed last night' check"
  - "Android 16 Live Update notification, with a 14/15 fallback"
  - "A clock-in the wallet sent is found on-chain if the app never heard so"
  - "1,330 JVM unit tests passed (5 more skipped)"
  - "Ran on a Redmi 14C, Android 16, HyperOS 3, on 10 and 11 October 2026: setup, TEE rig key,
    attestation, and five short shifts on mainnet, three of them on the redesigned build.
    **On mainnet**"
- **Visual:** The list, with two figures beside it (the test count and the phone), and a
  screenshot of setup's five steps on the emulator (`site/deck/img/app-setup.png`).
- **Speaker notes:** "Only the launcher activity, the tile service and the two widget receivers are exported. The trampoline
  reads no intent extras. Release builds strip every `android.util.Log` call, backups are off, RPC
  is HTTPS-only and the crank uplink WSS-only. The app shows success only after the transaction is
  confirmed with no error. The phone is a Redmi 14C: accelerometer only, no gyroscope. On mainnet
  the wallet sends the clock-in itself, and twice the app did not hear that it had landed; it now
  writes down what it hands the wallet and arms the shift it prepared once the Rig shows it
  on-chain. That repair has run on the emulator; on the phone the failure has not come back in
  three clock-ins. The same evening Jupiter refused the sign-in token it had given the app the
  day before, and the wallet library reported that as a failed connection: the app now reads the
  wallet's real answer, drops a refused token and asks for one more tap."
- **Serves:** Public: UX. Align: UX, UI.
- **Sources:** `android/README.md` (modules, clock-in, security notes),
  `android/INTERFACE-NOTES.md` §4 (one legacy packet), `docs/MAINNET.md` (the shift on the Redmi),
  `docs/SECURITY_REVIEW.md` §5 (1,330 tests passed).

## Slide 12: SKR is collateral and gifts, and never pays you for holding it

- **Message:** SKR has four jobs, each tied back to ORE, and none of them pays a return for locking
  SKR.
- **On slide:**
  - "SKR is collateral and gifts. Nothing is paid for holding it."
  - "Stack: players bond SKR on keeping their phones face-down. Finishers split 80% of forfeits;
    20% goes to the Bury auction."
  - "Focus Bond: a solo bond. It comes back after a finished shift; a broken one goes to the Bury
    auction, never to the team."
  - "Gift a Rig: SOL escrowed for a wallet, or for a Seeker's SGT."
  - "SKR fuel: SKR swapped to SOL in the user's own clock-in transaction. **Designed**"
  - "Bury auction: forfeits are sold for ORE at a descending price, with no oracle, and the ORE
    goes through ORE's own `bury` (90% burned, 10% distributed by ORE)."
  - "Program: **Built**, 13 instructions tested on a fork of live ORE, on mainnet with the rest of
    the program. App: the Focus Bond. The Stack and Gift screens are not built. No SKR flow on
    mainnet is shown here."
  - "No SKR emitted, none routed to the team."
- **Visual:** Flow diagram: Stack / Focus Bond / Gift a Rig / SKR fuel, the forfeits into the Dutch
  auction, then ORE `bury` (90% burned, 10% distributed by ORE, shown as two lines).
- **Speaker notes:** "Forfeits come only from other players at the same table; nothing is minted.
  The Bury auction uses a descending price, so it needs no oracle. Solana Mobile's SKR program is
  never called. A gift's claim can arm the recipient's rig in the same transaction, and the sender
  can pay in SKR through a swap in the same approval; those transactions are built and tested, and
  their screens are not. We say plainly that forfeit payouts may count as wagering in some places,
  so bury-only tables exist, bonds are capped and the app is 18+."
- **Serves:** Align: SKR (20%), Ecosystem Impact. Public: Stickiness & PMF.
- **Sources:** `docs/SKR.md` (flows, §4, §6, §9, §1 legal risk), `docs/ECONOMICS.md` §6,
  `programs/heads-down/INTERFACE.md` §11, `dashboard/src/app/skr/page.tsx`.

## Slide 13: ORE is the product

- **Message:** Every rig is an ORE miner with its own Automation, and ORE can verify our usage from
  its own logs.
- **On slide:**
  - "Every rig is an ORE miner, and ORE can recompute our usage from its own events."
  - "Each rig is your own ORE Automation. Its executor is the Heads Down PDA, on mainnet at
    `By3vJvQUsCLexnv7VqHuEhtZZCmpmjZjfhxvqCnWPkge`."
  - "Usage: ORE `DeployEvent`s signed by that PDA, over `ResetEvent.total_miners`."

  | Proposed to ORE: targets, none reached | |
  |---|---|
  | M1, +30 days | dApp Store; 250 rigs, at least 50 SGT-verified |
  | M2, +90 days | 1,000 rigs, 300 nightly; 15% of ORE miners per round, 00:00 to 06:00, one region |
  | M3, +180 days | immutable v1; a third-party crank landing digs |

  - "Found: ORE stores `max_production_cost`, but `deploy` never reads it. Heads Down enforces its
    own gate."
- **Visual:** A capture of the live dashboard's `/milestones/` page
  (`site/deck/img/dashboard-milestones.png`, taken by `scripts/pitch/shoot-dashboard.sh`), with
  its time under it.
- **Speaker notes:** "At 165 to 172 miners per round, 15% is roughly 25 concurrent rigs. The
  stretch targets from the spec are 250 SGT-verified rigs and 25% in two regions. These are
  targets I proposed, not results: there are no users yet. The mainnet executor of the first
  milestone exists since the tenth of October. ORE stays the featured brand on the rig, the reveal
  and every post, and no competing mining product is supported. We will raise the
  `max_production_cost` finding with ORE through their channel."
- **Serves:** Align: Ecosystem Impact. Public: Innovation/X-factor. ORE matched prize.
- **Sources:** `docs/ORE.md` §9 and §1, `services/indexer/README.md` (metric definitions),
  `spikes/ore-executor/README.md` (facts established),
  `deploy/receipts/mainnet/20261010T184715Z-init.json` (the Executor PDA).

## Slide 14: The worst case for each key

- **Message:** Every key has a bounded, written worst case, and custody stays in ORE.
- **On slide:**
  - "Every key has a written worst case. Mining funds stay in ORE's custody."

  | Key | Worst case | What bounds it |
  |---|---|---|
  | Your wallet | Everything it controls: it is the root | Your wallet; every tx built on-device, its amounts bounded and shown before the wallet opens |
  | Rig P-256 key (phone) | The armed, capped budget deploys into ORE only while the gate is open; about 10.5% goes to ORE fees, none to the attacker; its own SKR bonds | Caps and expiry, the on-chain gate, Freeze (phone), Unfreeze (wallet) |
  | Crank | For mining, liveness only: nothing mines, nothing is lost. A Focus Bond or a Stack seat is lost if no heartbeat lands | Anyone can run `hd-crank`; fixed reimbursement. Only the team's runs today, on Railway |
  | Registrar | Software keys pass as hardware on capped remote Stack tables | No custody, no mining power; bond caps; voucher lifetime capped on-chain; an append-only log |
  | Upgrade authority | Today, one key (`9DSVM862oJrstiPmmQmgqXb7AkuXrKJtYgd1fwbeqeeW`), no multisig, no delay: Heads Down-held SKR and SOL; forced deploys up to 25 × the per-square amount per round. Never an Automation's balance or anyone's ORE | Small custody (bond caps); a multisig with a 72 h delay is planned, then revocation |
  | Team servers | Liveness, privacy, phishing-shaped pushes | No signing from push; txs rebuilt from chain |
  | ORE upstream | ORE owns every Automation and can move funds | Inherited and stated; layout pins and a circuit breaker for accidents |

  - "No third-party audit."
- **Visual:** The table on the pit; the upgrade authority's row marked in ember.
- **Speaker notes:** "Heads Down reuses ORE's custody instead of writing its own vault for mining
  funds, so we inherit ORE's trust assumption in full, and we say so. The rig key can't require
  user authentication because it signs while the phone is locked; that is why the wallet-signed
  caps, not the key, bound the damage. Config changes and governance rotation wait 72 hours in
  code. Program upgrades do not: the upgrade authority on mainnet is one key, mine, and the threat
  model says so in plain words. A multisig with a delay comes before the caps go up. Nobody
  outside the project has audited any of this."
- **Serves:** Align: Ecosystem Impact, Innovation. Public: UX (trust).
- **Sources:** `docs/THREAT_MODEL.md` (As built, Summary, K2, K5, K7), `docs/SECURITY_REVIEW.md`,
  `programs/heads-down/README.md` (worst case per key, `admin` suite),
  `deploy/receipts/mainnet/20261010T184116Z-fresh-d67a1a40c2a0.json` (the upgrade authority).

## Slide 15: Open, tested, checkable

- **Message:** The trust layer is open source, with tests anyone can re-run.
- **On slide:**
  - "The trust layer is open source, with tests anyone can re-run."

  | Component | Tests | Where the number comes from |
  |---|---|---|
  | `heads_down` program | 171 passed (138 on a fork of live mainnet ORE, 33 on the host) | `programs/heads-down/README.md` |
  | `hd-crank` | 214 passed, plus 14 against the real program on the fork and 1 end to end on a local validator | `docs/SECURITY_REVIEW.md` §5, `README.md` |
  | Registrar (SIWS + Key Attestation) | 116 passed, 19 of them on real Google attestation chains | `registrar/README.md`, `README.md` |
  | Indexer | 477 passed | `docs/SECURITY_REVIEW.md` §5 |
  | Dashboard | 48 passed | the 48 cases in `dashboard/test`; re-run 2026-10-03 |
  | Android | 1,330 passed; 5 more are skipped | `docs/SECURITY_REVIEW.md` §5; re-run 2026-10-11 |
  | `p256-introspect` | 30 passed | `crates/p256-introspect/README.md` |
  | `sgt-verify` (host suites) | 69 passed, plus LiteSVM suites | `crates/sgt-verify/README.md` |

  - "Security review before deployment: 114 findings, 18 confirmed by a reproducing test. What the
    first night on mainnet found is written down beside it, with what is still open."
  - "On mainnet the program's bytes equal the build, byte for byte (program hash
    `0154706c62cb7bb6aacf463501e49b016b87809ed7193fcae08afc4e3f891b58`)."
  - "No third-party audit. One key can upgrade the program."
- **Visual:** The eight test counts as large figures with the component under each.
- **Speaker notes:** "`sgt-verify` and `p256-introspect` are standalone crates any Solana program
  can use. The program has one `unsafe` block, the log syscall, and denies panics and unchecked
  arithmetic at lint level. Sixteen bug classes are mapped to named tests, and 9,000 fuzzed
  instructions on the program binary caused no abort. Each fix of a confirmed finding has its
  regression test. The registrar keeps a hash-chained log of every voucher; it is a file today, not
  yet published. Not done: a third-party audit, the Radiants audit run, Kani proofs."
- **Serves:** Public: Presentation & Demo (technical depth). Align: Ecosystem Impact.
- **Sources:** as in the table; `programs/heads-down/README.md` (audit checklist, fuzz),
  `docs/SECURITY_REVIEW.md`, `registrar/README.md` (transparency log), `dashboard/README.md`
  (honesty and safety), `deploy/receipts/mainnet/20261010T184116Z-fresh-d67a1a40c2a0.json`.

## Slide 16: On mainnet, no users yet

- **Message:** The program is on mainnet since 10 October 2026 and every figure links to the chain.
  There are no users yet, and none is claimed.
- **On slide:**
  - "On mainnet since 10 October 2026. One phone has mined its first ORE. No users yet."

  | What | Where to check it |
  |---|---|
  | Program deployed, slot 455,359,196 | `4naPfgTXDKRxG7NhunoNKVBWkCwuvdJoyBGytmebCpuDzdrP6Gm4UwxNYm5r5zzUu8vuZKgYiaFp1BzrTwH2Pae2` |
  | Config initialized: 10,000 lamports per dug round, 7,000 of it to the crank | `3R4UnCecw7BuzAvLZURtktLRnh6iEboPE6CpX35NdX7sKzjJcL4g84i1uxQdwwWHPioyEVcG3uBp4cfAhQFzJzUs` |
  | Executor PDA, funded with 1,450,240 lamports | `By3vJvQUsCLexnv7VqHuEhtZZCmpmjZjfhxvqCnWPkge` |
  | Crank, registrar, indexer, dashboard | running on Railway against mainnet |
  | Clock-in from the Redmi 14C, signed in Jupiter | `4DvPBhh4UiEhezYPmjcJvHeqSGGfCAj9prnKEFPMGaGRe9sGg7r9A2SMPVTo4UYXjeEkHUc1WCA7VRdznZuPaQht` |
  | First dig, ORE round 435,224 | `25nBRCP6GExrKG4EDszGrKfWN9yYgJMopgnawTGi2Z9XNGj6UCtZgE6HFJN7rJXvu2C3VMjhh7bpXZnQg8DVzovY` |
  | Digs landed in that shift (the first was sent too late) | 5 of 6 rounds |
  | The pickup, and the BREAK the phone signed | `63FZYbEyJyePgGMMHqAxTvwGD5vAvFUggnbkfhmummhPjAzADctceYYJE5UNHp4bFBshJTcGNPDFocE7xw92yqVb` |
  | The same heartbeat sent twice, refused | `4EJvGg7akbzgQRbT2cYteaHCogpnEaBpP2ncTHLFS1mtCWt5e9aVFJEmeiFgbaShHC7H7t2EkpwxQYKpcWq6PUpM` |
  | Clock-out | `3VfbzUZHzAYj468iXo2bnsqRfAcrTMt76dHzWFT51ZnrvseFF1bYEzo6fAqurTG8tB9AM1cPRYsXtJK2R25LuTUm` |
  | SOL taken back: 0.00502704 SOL, what the screen said, to the lamport | `46NXJnX54wUdTgBVBGwfid8NQeqRL3LkGCyFkhtqJuVVHwp6R2UbnNxxhTSv8FHgtuyJupEPRQi4Frqf4NAjDcFF` |
  | The shift: 6 rounds dark, 0.005 SOL placed on 20 squares | no ORE won |
  | Since then, on 11 October: 4 more shifts, 15 digs in 15 rounds | 0.0019 ORE mined (`docs/MAINNET.md`, the third evening) |

  - "Demo build: price ceiling raised to 1.0 SOL per ORE (default 0.53) under a wallet ceiling of
    1.2; budgets cut to 0.005 SOL a shift and 0.035 SOL a week."
  - "One key can upgrade the program. No third-party audit."
- **Visual:** The table, and beside it a capture of the live dashboard's overview
  (`site/deck/img/dashboard-overview.png`, taken by `scripts/pitch/shoot-dashboard.sh`), with its
  time under it. The dashboard marks its data "On-chain data · Solana mainnet"; it would show a
  SIMULATED banner on simulated data.
- **Speaker notes:** "No vanity counts, no paid testers, no referral bounties. There are no users
  yet. Metrics come from on-chain rows only, and anyone can recompute them from the CSV exports.
  What exists today is checkable: the deploy transaction, the Config, the Executor, four services
  answering on mainnet, and one phone's shift, transaction by transaction. The shift was six
  rounds dark. The crank sent the first round's dig too late and it was skipped; the next five
  landed, 0.001 SOL each on four squares. None of the squares won, so no ORE came out, and each
  round sent back the 89.1% that ORE gives back for squares that lose. I picked the phone up, it signed a
  BREAK, I clocked out and took the SOL back, and the wallet received exactly what the screen had
  said. The digs ran under a ceiling I raised for the demo, with the budget cut to 0.005 SOL a
  shift. What that evening found, including a dig window that was too short and a clock-in the
  app did not hear about, is written down in `docs/MAINNET.md` with what was changed. The next
  day the phone worked four more short shifts: fifteen digs in fifteen rounds, and the rig's
  first ORE, 0.0019 of it, sitting unclaimed in its own Miner account. On that small sample it
  cost about 1.13 SOL per ORE: this is not a money machine, and the page says so."
- **Serves:** Public: Stickiness & PMF. Align: Ecosystem Impact.
- **Sources:** `docs/MAINNET.md` (every transaction of the shift, and what it cost),
  `deploy/receipts/mainnet/20261010T184116Z-fresh-d67a1a40c2a0.json`,
  `deploy/receipts/mainnet/20261010T184715Z-init.json`, `services/indexer/README.md` (metric
  definitions, simulation mode), `dashboard/README.md`, `docs/SPEC.md`
  (TRACTION_AND_LAUNCH_PLAN rules), `docs/DEPLOY.md` §10.7 (the demo ceiling).

## Slide 17: Landscape

- **Message:** Others mine ORE on a schedule from a server; Heads Down mines only while this phone is
  down, and the chain enforces it.
- **On slide:**
  - "Others mine ORE from a server on a schedule. Heads Down mines only while this phone is down."

  | | Native Android | Mining gated on the phone | Executor signs only for a phone heartbeat | Mine-or-buy with a published backtest | SGT-verified tier |
  |---|---|---|---|---|---|
  | RefinORE AutoMiner | store app | no (runs with the app closed) | no (server) | not found | not found |
  | Orestack | TWA | no | no (server) | EV-gated, no backtest found | not found |
  | Oreminer | store app | no | no (server) | not found | not found |
  | ore.com | web + PWA | no | no (ORE's own executor) | not found | no (`claim_seeker` removed) |
  | **Heads Down** | Kotlin/Compose | **yes** | **yes** | **yes** | **yes** |

  - "'Not found' means our review did not find it, not that it cannot exist."
- **Visual:** The matrix, with Heads Down's row in ORE gold.
- **Speaker notes:** "'Not found' means our review didn't find it, not that it can't exist. Take
  away the phone gate and Heads Down would be another autominer; the gate is the product."
- **Serves:** Public: Innovation/X-factor. Align: Innovation.
- **Sources:** `docs/research/redteam.md` (store listings and copy), `docs/research/skr-and-ore.md`
  (ore.com, refinORE), `buildplan.md` (no phone-gated ORE repo or app found).

## Slide 18: Where it stands, and who is building it

- **Message:** The hard parts are proven on a fork of mainnet, the program is deployed, the phone
  has worked a shift there, and the open items are written down.
- **On slide:**
  - "Deployed on mainnet, worked by a real phone, with the open items written down."
  - "Proven on a fork of mainnet: the PDA executor on live ORE; P-256 heartbeats verified
    on-chain; the SGT check; Stack, Focus Bond, Gift a Rig and the Bury auction."
  - "On mainnet, 10 October 2026: the program, its bytes equal to the build; crank, registrar,
    indexer and dashboard running; the Redmi's TEE rig key attested. Its first shift, under the raised
    demo ceiling: 5 digs, a BREAK, a clock-out, SOL back to the lamport; 15 more digs since, and
    its first 0.0019 ORE"
  - "Open, and next: one upgrade key, then a multisig, then an immutable v1; no third-party audit;
    the Stack and Gift screens and the buy leg; a whole night, and the morning reveal, on the phone."
  - "Founder: solo, Nigeria (WAT), building with AI agents on one Redmi 14C.
    `[TBD: founder's personal line]`"
  - "github.com/OoJae/heads-down · oojae.github.io/heads-down"
- **Visual:** Three columns: Proven / On mainnet / Open and next. Founder photo
  `[TBD: founder's photo]` (optional figure `site/deck/img/founder.jpg`, shown only when the file
  exists).
- **Speaker notes:** "More than six hundred commits since the repo was created on 29
  September 2026. Offline means no mining and no loss, which suits power and network cuts where I
  live. I have one phone, a Redmi 14C, and no Seeker; everything a Seeker adds is tested on a fork
  of mainnet."
- **Serves:** Public: Stickiness & PMF (founder-market fit), Presentation & Demo.
- **Sources:** `README.md` (proven so far), `spikes/*/README.md`, `crank/README.md`,
  `registrar/README.md`, `deploy/receipts/mainnet/20261010T184116Z-fresh-d67a1a40c2a0.json`,
  `git rev-list --count main` (611 on 2026-10-11, before the deck's own commits).

---

## Appendix: one line per criterion

| Criterion | Claim | Source |
|---|---|---|
| Stickiness & PMF | The shift hangs on bedtime, the charger and the alarm; focus-only shifts still count on nights with no SOL. | `docs/SPEC.md` (CORE_LOOP), `android/README.md` |
| UX | One wallet approval per clock-in, from a Quick Settings tile. | `android/INTERFACE-NOTES.md` §3 |
| UI | One design module ("The Underside": a dark slab lit from underneath; ember and gold as one accent at two temperatures) read by the app, the widget, the notification and the reveal, with the same mark on the identity page and the dashboard. | `android/README.md`, `android/core/design/`, `brand/README.md` |
| Innovation | A third-party ORE executor that signs only for an on-chain-verified phone heartbeat, on mainnet since 2026-10-10. | `programs/heads-down/README.md`, `deploy/receipts/mainnet/20261010T184116Z-fresh-d67a1a40c2a0.json` |
| Presentation & Demo | The demo shows a replayed heartbeat skipped and a fresh one digging, on an explorer. | `docs/pitch/DEMO_SCRIPT.md` |
| AI | On-device detection, a pickup classifier and a shift planner are built (trained on synthetic data and simulated users so far); the forecaster was demoted to advisory by a pre-set rule. | `ml/foreman/README.md`, `ml/forecaster/MODEL_CARD.md` |
| SKR | Bonds, gifts, fuel and a Bury auction; no emission, nothing paid for locking. Built in the program, the crank, the indexer and the dashboard; the Focus Bond is in the app. | `docs/SKR.md` |
| Ecosystem Impact | Two open-source crates, verifiable ORE usage metrics, a milestone plan. | `crates/`, `docs/ORE.md` §9 |
