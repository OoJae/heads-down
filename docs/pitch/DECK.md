# Heads Down: pitch deck script (10 slides)

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
  day. Each caption says which. On the phone the redesigned build clocked in three times, dug ten
  rounds, clocked out and took its SOL back to the lamport; the morning reveal has run on the
  emulator only.
- `[TBD: founder]`: only the founder can supply it (the photo, the personal line).

**Status chips used on slides.** **Built** = code plus passing tests in the repo. **Built, not on a
device** = covered by unit tests or LiteSVM on the build machine, never run on a phone. **On an emulator** = the real app ran it
on an Android 14 emulator against a local fork of mainnet (`scripts/devstack/emulator-smoke.sh`),
not on a phone. **On the Redmi** = the real app did it on the founder's Redmi 14C (Android 16,
HyperOS 3). **On mainnet** = deployed or run on Solana mainnet, with a receipt or a transaction.
**Designed** = specified in `docs/`, not in code. **Next** = planned for the build.

**Rubrics** (`docs/research/judges-preferences.md`):

- The live form (read from the platform on 12 October 2026), 4 × 25: Stickiness & PMF, User
  Experience, Innovation / X-factor, Presentation & Demo. The "AI 20, SKR 20" set of the earlier
  drafts is not on it.
- The platform's AI coach reads about 8,000 characters of the deck's text, so the deck was cut
  from 18 slides to 10 on 12 October, with the nightly loop and the reasons to return first. The
  printed PDF is checked with `pdftotext` to stay under that.

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

- **Message:** Face-down, your phone digs ORE; pick it up and the rig goes cold. One line says
  what kind of thing it is: a focus timer whose tree is an ORE rig.
- **On slide:**
  - "Your phone's night shift."
  - "Face-down, it digs ORE. Pick it up, the rig goes cold."
  - "A focus timer whose tree is an ORE rig. Any Android."
  - "Built solo in Nigeria, with AI agents, on the one phone I own: a Redmi 14C. On Solana
    mainnet since 10 October 2026."
- **Visual:** The wordmark, with the home screen from the Redmi 14C beside it
  (`site/deck/img/redmi-home-cold.png`).
- **Speaker notes:** The founder's own two sentences, then the product in one breath. `[TBD:
  founder]` confirms the line on the slide: it is the founder's to say.
- **Serves:** Presentation & Demo.
- **Sources:** `README.md`, `docs/MAINNET.md`.

## Slide 2: The night

- **Message:** One approval at bedtime, the phone works the shift, and the alarm ends it.
- **On slide:**
  - "One approval at bedtime. The phone works the shift. Your alarm ends it."
  - Seven steps, each with where it has run: clock in; one approval funds a capped shift in your
    own ORE Automation; a heartbeat every ORE round; a dig only when the price gate is open; a
    pickup cools the rig; after the alarm a page opens over the lock screen and the clock-out
    there shows the reveal; take your SOL back whenever you like.
  - "From the Redmi 14C, 10 Oct 2026, on mainnet: clock-in, clock-out, SOL back to the lamport."
- **Visual:** The steps as a table with a chip per row; two pictures from the Redmi.
- **Speaker notes:** The habit hangs on three things people already do every night: bedtime, the
  charger and the alarm. Say for each step where it ran.
- **Serves:** Stickiness & PMF; User Experience.
- **Sources:** `docs/MAINNET.md`, `android/README.md`.

## Slide 3: Why you come back

- **Message:** A streak stored on-chain with two nights off a month, and a morning worth opening.
- **On slide:**
  - "A streak stored on-chain, two nights off a month, and a morning worth opening."
  - "1 approval a shift." "2 nights off every 30 days." "30M downloads of Pokémon Sleep: its own
    tips teach the same posture: plugged in, face-down, all night."
  - "What brings you back: the reveal after your own alarm, a home-screen widget and a Quick
    Settings tile, every shift on the Nights screen, a share card with no wallet address."
  - "So far: one rig, the founder's", with the state of its streak on the day.
- **Visual:** Three large figures over one line of text.
- **Speaker notes:** The streak and its nights off are in the Rig account, so a new phone does
  not lose them. Pokémon Sleep is cited for the posture only; its numbers are its own.
- **Serves:** Stickiness & PMF.
- **Sources:** `programs/heads-down/INTERFACE.md` (section 6.8), `android/README.md`.

## Slide 4: What is new, the referee

- **Message:** The referee is the phone's own hardware, and Solana checks its signature in the
  same transaction as the dig.
- **On slide:**
  - "The referee is the phone's own hardware. Solana checks its signature in the same transaction
    as the dig."
  - The path: Keystore key, crank, secp256r1 precompile, Executor PDA, ORE's deploy.
  - "No fresh heartbeat, no deploy." "171 program tests, 138 of them on a fork of live mainnet ORE."
  - Three transactions from the Redmi: a dig, a pickup's BREAK, a replayed heartbeat refused.
- **Visual:** The path as five nodes with arrows.
- **Speaker notes:** Any relay can carry a heartbeat and none can forge one; the program alone
  chooses amounts and squares.
- **Serves:** Innovation.
- **Sources:** `programs/heads-down/INTERFACE.md`, `docs/THREAT_MODEL.md`, `docs/MAINNET.md`.

## Slide 5: SKR, the Focus Bond

- **Message:** Lock SKR on a shift; finish and the same SKR comes back; nobody is paid when you
  fail.
- **On slide:**
  - "Lock SKR on a shift. Finish, and the same SKR comes back. Nobody is paid when you fail."
  - A table of who decides and who gets the money: Forfeit (the company; the company keeps it),
    Digital Detox (the app; its developer), Heads Down (the phone's secure hardware, checked by
    Solana; nobody: it is sold for ORE that is burned).
  - The bond's status on the day, with its transactions once it has run on mainnet.
  - "Heads Down emits no SKR, pays nothing for holding it and keeps none."
- **Visual:** The three-row table with Heads Down's row lit.
- **Speaker notes:** SKR is what Seeker owners already hold from using the phone. The auction's
  vault is not created on mainnet and no forfeit has run there: say so.
- **Serves:** Innovation; Stickiness & PMF.
- **Sources:** `docs/SKR.md`.

## Slide 6: Plain about money

- **Message:** Mining is one route to ORE, not income; the rig digs only under a price ceiling
  and the reveal shows the market price beside yours.
- **On slide:**
  - "Mining is one route to ORE, not income. The rig digs only under a price ceiling, and the
    reveal shows the market price beside yours."
  - "58,801 real ORE rounds, 47 nights. Dig every round: +36% to +90% against buying. Dig 0.001
    SOL chunks when the gate opens, buy the rest: -1.4% to -3.0%."
  - "This rig: 20 digs placed 0.02 SOL; the 19 settled mined 0.0019 ORE: about 1.13 SOL per ORE."
  - "The demo: a default build's ceiling is under ORE's cost figure, so it does not dig. The
    filmed build plans 1.0 under a signed ceiling of 1.2."
- **Visual:** The backtest's figure, unchanged, beside the table.
- **Speaker notes:** The backtest charged 5,000 lamports a dug round and mainnet charges 10,000;
  it has not been re-run at that fee.
- **Serves:** Innovation; User Experience.
- **Sources:** `ml/forecaster/RESULTS.md`, `docs/MAINNET.md`, `docs/ECONOMICS.md`.

## Slide 7: ORE

- **Message:** Other ORE miners place SOL from a wallet or a server; Heads Down digs only while
  this phone is face-down.
- **On slide:**
  - "Other ORE miners place SOL from a wallet or a server. Heads Down digs only while this phone
    is face-down."
  - A table: ORE's own app, RefinORE AutoMiner, Orestack, Oreminer and Heads Down, by whether it
    is on the Seeker store, whether it digs only while the phone is down, who can place the SOL,
    and whether it publishes a mine-or-buy backtest.
  - "Each rig is your own ORE Automation, and ORE can recompute our usage from its own
    DeployEvents."
- **Visual:** The table with Heads Down's row lit.
- **Speaker notes:** ORE has its own app on the Seeker store. "Not found" means our review did
  not find it. Heads Down is not on the store yet and the table says so.
- **Serves:** Innovation; Stickiness & PMF.
- **Sources:** `docs/ORE.md`, `docs/research/redteam.md`.

## Slide 8: Native Android

- **Message:** Native Kotlin and Compose, on system surfaces a web app cannot reach.
- **On slide:**
  - "Native Kotlin and Compose, on system surfaces a web app cannot reach."
  - Tile or app and one approval (Mobile Wallet Adapter 2.2.0); one clock-in transaction; a
    foreground service; the HyperOS keep-alive setup; an Android 16 Live Update; widgets, haptics
    and a reveal aligned to your own alarm.
  - The count of the app's unit tests on the day, and what the Redmi 14C has run.
- **Visual:** The list, two facts and the setup screen.
- **Speaker notes:** Killed by the OS means cold and nothing spent.
- **Serves:** User Experience.
- **Sources:** `android/README.md`, `docs/MAINNET.md`.

## Slide 9: Proof

- **Message:** On mainnet since 10 October 2026, and every shift, dig and lamport is public.
- **On slide:**
  - "On mainnet since 10 October 2026. Every shift, dig and lamport below is public."
  - A ledger with its transactions: the deploy, the services, the first clock-in and dig, a
    pickup's BREAK, a replay refused, the clock-out, the SOL taken back, and the shifts since.
- **Visual:** The ledger beside the live dashboard.
- **Speaker notes:** Read one row and open its transaction.
- **Serves:** Presentation & Demo.
- **Sources:** `docs/MAINNET.md`, `dashboard/README.md`.

## Slide 10: Open, and next

- **Message:** What protects the user, what is still open (said once, here), and what comes next.
- **On slide:**
  - "What protects the user, what is still open, and what comes next."
  - Protects: the phone's key cannot leave its Keystore and acts only inside limits the wallet
    signed; success is shown only after the chain confirms it; every key has a written worst case.
  - Open today: one upgrade key; no third-party audit; a debug-signed APK, not on the dApp Store;
    not run on a Seeker and no screen sends the Seeker check; one user so far.
  - Next: the haul already on screen when the alarm rings; nights that arm themselves; a night
    partner; a multisig, then an immutable v1; the dApp Store.
  - "One builder in Nigeria (WAT), building with AI agents, on one Redmi 14C."
- **Visual:** Three cards and the founder's line with the two links.
- **Speaker notes:** The open items are said once and plainly, here.
- **Serves:** Presentation & Demo; User Experience.
- **Sources:** `docs/THREAT_MODEL.md`, `docs/SECURITY_REVIEW.md`, `README.md`.

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
