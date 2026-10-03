# Heads Down: pitch deck script (17 slides)

Draft for the founder, written 2026-09-29 and brought up to the state of `main` on 2026-10-03.
Every number cites the repo file it comes from.

- `[TBD after device test]`: needs the Redmi 14C connected (it is not yet).
- `[TBD after launch]`: needs real rigs on mainnet (the program is not deployed on any cluster yet:
  `crank/README.md`, `hd-crank check` output).
- `[TBD]`: a build item or a founder decision.
- Replace a TBD only with a measured value and its source.

**Status chips used on slides.** **Built** = code plus passing tests in the repo. **Built, not on a
device** = tested in CI or LiteSVM, never run on a phone. **Designed** = specified in `docs/`, not
in code. **Next** = planned for the build.

**Rubrics** (`docs/research/judges-preferences.md`):

- Public, 4 × 25%: Stickiness & PMF, UX, Innovation/X-factor, Presentation & Demo.
- Align form: AI 20, SKR 20, UX 15, UI 15, Innovation 15, Ecosystem Impact 15.

**Wording.** Follow `docs/ECONOMICS.md` §4: dig, haul, bond, "accumulate ORE by the cheaper route".
The slide text and speaker notes below contain no word from its banned list.

---

## Slide 1: Heads Down, your phone's night shift

- **Message:** Face-down, your phone digs ORE from your own ORE Automation, and only the phone's
  hardware key can switch it on.
- **On slide:**
  - "Heads Down: your phone's night shift. Powered by ORE."
  - "Face-down, it digs ORE. Pick it up, the rig goes cold."
  - "Any Android. Verified Seeker tier."
- **Visual:** A Redmi 14C face-down on a nightstand, charger cable in, dark room, ORE wordmark as
  the main logo. Small caption: "Filmed on a Redmi 14C."
- **Speaker notes:** "I'm [founder name], a solo builder in Nigeria, building with AI agents.
  Heads Down gives an idle phone a night job. It digs ORE from your own ORE Automation account
  while the phone lies face-down. Nothing but the phone's own hardware key can switch it on: not a
  server, not me."
- **Serves:** Public: Presentation & Demo. Align: Innovation.
- **Sources:** `README.md`, `programs/heads-down/README.md`.

## Slide 2: Most of these phones sit idle

- **Message:** There is a large pool of idle phones, and Seekers are often second phones.
- **On slide:**
  - "121,035 Seeker Genesis Tokens minted (on-chain group size at fetch time)"
  - "About 118.9k of about 121k .skr IDs dormant (SeekerTracker)"
  - "Seeker Season 2: over 9,000 daily active users (June 2026)"
  - "Give the idle phone a job."
- **Visual:** A drawer of phones, one glowing ember-orange.
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
  - "2025 ORE landing card: 'Proof of mobile … Coming soon'"
  - "ORE `claim_seeker`: added Sep 26 2025, removed Nov 6 2025"
  - "ore.com today: web app, PWA since Aug 20 2026"
  - "165 to 172 miners per round (mainnet, 2026-09-29)"
  - "Store autominers (RefinORE, Orestack, Oreminer) run with the phone's app closed"
- **Visual:** A timeline strip from 2025 to Sep 2026, ending in the Heads Down rig icon.
- **Speaker notes:** "The ORE board has fewer than two hundred miners per round, and mobile
  mining was promised and never shipped. The autominers on the dApp Store run from servers; none
  that we found gates mining on the phone itself."
- **Serves:** Public: Innovation/X-factor. Align: Innovation, Ecosystem Impact.
- **Sources:** `docs/research/skr-and-ore.md` (landing card, `claim_seeker` history, PWA),
  `docs/ORE.md` §1 (live Round values), `docs/research/redteam.md` (store autominers),
  `crates/sgt-verify/README.md` (Credits).

## Slide 4: The ritual

- **Message:** One wallet approval at bedtime; the phone does the rest; pick it up and it cools.
- **On slide (loop diagram):**
  1. Tap the Quick Settings tile. **Built, not on a device**
  2. One wallet approval: fund a capped shift in your own ORE Automation and arm the rig.
     **Built, not on a device**
  3. Face-down on the charger. The phone signs a heartbeat every ORE round (about 78 s).
     **Built, not on a device**
  4. Digs happen only when the on-chain price gate opens. **Built**
  5. Pick it up: 10 s cooling grace, then cold. Unlock: cold at once. **Built, not on a device**
  6. Morning haul reveal at your alarm, replayed round by round from chain data.
     **Built, not on a device**
  7. Clock-out: seal the shift, take back a Focus Bond, claim the ORE or leave it in your
     Miner. **Built, not on a device.** The "buy the rest" leg: transactions **Built** and tested
     against recorded Jupiter quotes; offering it in the screen is **Next**
- **Visual:** A circular night-to-morning loop with a status chip on each step.
- **Speaker notes:** "The habit hangs on three things people already do every night: bedtime, the
  charger and the alarm. If the OS kills the app, the rig goes cold and nothing is spent. The
  clock-out screen is built; the buy leg is the next item, and its transactions already exist and
  are tested."
- **Serves:** Public: Stickiness & PMF, UX. Align: UX, UI.
- **Sources:** `android/README.md` (clock-in, shift loop, "still stubbed"), `docs/SPEC.md`
  (CORE_LOOP).
- **Checked 2026-10-03:** the app's instructions match the program's golden vectors byte for
  byte (`android/core/chain/src/test/kotlin/xyz/headsdown/core/chain/ix/GoldenInstructionsTest.kt`),
  and the phone-less end-to-end run passes on a local fork of mainnet (`docs/DEVSTACK.md`):
  clock-in, dig, lift, replay refused, indexed.

## Slide 5: The trustless rig

- **Message:** The executor is a program address that signs only for a fresh phone heartbeat, so no
  server or team key can deploy your SOL.
- **On slide:**
  - Diagram: Your wallet → **your ORE Automation** (your SOL, owned by ORE) ← **Executor PDA**
    (heads_down) ← `dig` (anyone can crank) ← **P-256 heartbeat** from the phone's Keystore,
    verified by Solana's secp256r1 precompile.
  - "No fresh heartbeat, no deploy."
  - "Mainnet-fork spike: 5/5. Program: 171 tests, 138 of them on a fork of live ORE."
- **Visual:** The architecture diagram. Inset: an explorer view of a replayed heartbeat skipped
  with `StaleHeartbeat` next to a landed dig `[TBD after device test]`.
- **Speaker notes:** "Each rig is an ordinary ORE miner with its own Automation. The user sets the
  executor to our program's PDA. The program signs ORE's deploy only if, in the same transaction,
  the precompile verified this rig's key over a message bound to this program, this rig, this
  shift, this ORE round and a counter that must go up. A random signer can't deploy for your
  Automation; ORE checks executor equals signer. The crank decides only whether and when; the
  program computes amount and squares."
- **Serves:** Public: Innovation/X-factor, Presentation & Demo. Align: Innovation, Ecosystem Impact.
- **Sources:** `spikes/ore-executor/README.md`, `programs/heads-down/README.md`,
  `programs/heads-down/INTERFACE.md` (`dig`).

## Slide 6: Measured on the live ORE binary

- **Message:** The core path is measured, not estimated.
- **On slide:**

  | What | Value |
  |---|---|
  | Compute for a dig | 39,414 CU with one rig, 67,421 with two (most of it ORE's own `deploy`) |
  | Max rigs per dig transaction | legacy 2 · v0 + lookup table 5 · v1 11 |
  | secp256r1 check | 0 CU in the precompile + 477 CU introspection; one 5,000-lamport fee per signature |
  | Crank cost per rig-dig | about 6,050 lamports (v0 + table), about 5,500 (v1) |
  | Crank end to end on a validator | 3 rigs, 1 tx, 20,168 lamports fees vs 21,000 reimbursed |
  | Seeker check | about 1,900 CU |

- **Visual:** The table, with a small source tag on each row.
- **Speaker notes:** "Transaction size binds before compute: eleven rigs use about 27% of the
  compute limit. The crank also runs against the real program on the fork and end to end on a local
  validator; the fees depend on signature count and are real."
- **Serves:** Public: Presentation & Demo (technical depth). Align: Innovation.
- **Sources:** `programs/heads-down/README.md` (Measurements), `programs/heads-down/INTERFACE.md`
  §12.11, `spikes/secp256r1/README.md`, `crank/README.md` (packing, operating costs),
  `crates/sgt-verify/README.md`.

## Slide 7: Honest economics

- **Message:** Spread thin, mining costs more than buying. Heads Down digs in gated chunks and buys
  the rest: a modest edge, stated as modest.
- **On slide:**
  - "Dig every round: +90% / +45% / +36% SOL per ORE vs buying (0.02 / 0.04 / 0.05 SOL a night)"
  - "Dig 0.001 SOL chunks only when the on-chain gate opens, buy the rest: −3.0% / −2.4% / −1.4%
    vs buying (95% CIs exclude zero)"
  - "The gate stays shut all night on 30% of nights. Those nights just buy."
  - "Backtest: 58,801 real ORE rounds, 47 nights"
  - "Fixed crank-cost fee, no percentage of mining. Revenue: a disclosed Jupiter fee on the
    user-signed buy leg (bps `[TBD]`)"
- **Visual:** `ml/forecaster/figures/eff_price_vs_budget.png`. Callout: "2026-09-29 snapshot: the
  gate is shut; buying is cheaper."
- **Speaker notes:** "ORE keeps about 10.5% of the SOL you deploy; that is what mined ORE costs.
  Fixed per-dig fees make tiny deploys expensive. So the program digs 0.001 SOL at a time on the
  split squares, only when a Motherlode-aware rule it computes on-chain says mining is the cheaper
  route. At the snapshot the production-cost EMA was 0.919 SOL per ORE against a market of 0.758,
  so the rig would stay cold and the morning leg would buy. This is not a money machine; the value
  is the ritual, the trustless gate and honest route choice. The buy leg's transactions are built
  and tested; its screen is not."
- **Serves:** Public: Stickiness & PMF (trust), Innovation/X-factor. Align: Ecosystem Impact, UX.
- **Sources:** `ml/forecaster/RESULTS.md` (TL;DR, §3, §4), `docs/ECONOMICS.md` (Summary, §5, §7).

## Slide 8: AI, the Foreman: on-device, bounded, honest

- **Message:** The AI plans and polices shifts on the phone and can only tighten limits your wallet
  signed. Our forecaster lost to a simple on-chain rule, so it only advises.
- **On slide:**
  - "Cost Forecaster: **Built, advisory.** 9.2% lower error than 'same as now' one hour ahead;
    no skill at eight hours; as a gate, +0.03% [−0.13, +0.18] vs the live on-chain rule at 0.36 SOL
    a night. Demoted, as the plan required."
  - "Pickup detection: **Built.** Accelerometer only, with hysteresis. The Redmi 14C has no
    gyroscope."
  - "Pickup/bump classifier: **Built, trained on synthetic data.** A 39-feature model over
    5-second accelerometer windows. On synthetic tests: at least 99.85% pickup recall, at most
    0.2% of bumps break a shift. Recordings from the Redmi come next; until then these numbers
    say nothing about real nights. Screen-on and unlock stay hard breaks."
  - "Shift Planner: **Built, evaluated on simulated users.** Its windows finished 90.6% of
    simulated shifts, against 57% for a fixed 23:00 to 07:00 window."
  - "Bounds: a phone-signed plan above the wallet caps fails on-chain (`PlanExceedsCaps`). Sensor
    data never leaves the phone."
- **Visual:** Three model cards with status chips, and a thumbnail of
  `ml/forecaster/figures/forecast_walkforward.png`.
- **Speaker notes:** "We set a promotion rule before training: the forecaster had to beat the
  simple rule or be demoted. It found real one-hour structure: the cost ratio climbs as the
  Motherlode pot grows and collapses at each hit. But the program reads the live EMA and pot on
  every dig for free, and against that the forecast adds nothing measurable. So it explains the
  night and powers the morning accuracy card; it never decides. The classifier can only add a
  break, never remove one, and so far it has seen only synthetic motion: it is retrained on
  recordings from this phone before its numbers mean anything. It matters most for table
  contests, where a bump must not count as a pickup."
- **Serves:** Align: AI (20%), Innovation. Public: Innovation/X-factor.
- **Sources:** `ml/foreman/README.md`, `ml/forecaster/RESULTS.md` §5, `ml/forecaster/MODEL_CARD.md`,
  `android/feature/shift/src/main/kotlin/xyz/headsdown/feature/shift/FaceDownDetector.kt`,
  `buildplan.md` (step 4), `docs/SPEC.md` (AI_FEATURE),
  `programs/heads-down/README.md` (`shifts` suite), `docs/PRIVACY.md` §1.

## Slide 9: Any Android, verified Seeker tier

- **Message:** Guest rigs run on any Android with a mobile wallet. Seeker rigs are verified
  in-program with an SGT check that cannot be forged.
- **On slide:**
  - "Guest rig: any Android + an MWA wallet. Developed against a Redmi 14C, filmed on it
    `[TBD after device test]`."
  - "Seeker rig: SGT verified in-program; one verified rig per SGT mint. **Built**"
  - "Anchor: Token-2022 group membership, which only Solana Mobile's key can write"
  - "A mint forged with the real Token-2022 program passes a model of ORE's old `claim_seeker`
    checks. `verify_sgt` rejects it."
  - "Tested on real mainnet SGTs (#20 and #121,035). Of 3,600 single-bit flips of a real mint,
    only the member-number bytes still verify."
  - "Physical Seeker: not tested yet `[TBD: remote Seeker testers]`"
- **Visual:** Two rig cards (Guest, Seeker) and a "forgery rejected: `MissingGroupMember`" chip.
- **Speaker notes:** "Solana Mobile opened its stack to all Android makers, and the hackathon rules
  allow any Android device, so the guest tier is honest about what I film on. The Seeker tier keys
  every seat by SGT mint, because the holding wallet can change. Credit to ORE's `claim_seeker`,
  the first on-chain SGT check; we keep its checks and add the anchor."
- **Serves:** Align: Ecosystem Impact, Innovation. Public: Innovation/X-factor.
- **Sources:** `crates/sgt-verify/README.md`, `programs/heads-down/README.md` (`seeker` suite),
  `docs/SPEC.md` (corrections banner), `docs/research/judges-preferences.md` (SMS for all OEMs).

## Slide 10: Built for Android, not wrapped

- **Message:** Native Kotlin and Compose on system surfaces a web app cannot reach.
- **On slide:**
  - "Quick Settings tile → non-exported trampoline → one MWA approval (clientlib-ktx 2.2.0)"
  - "One clock-in transaction: ORE automate + register + caps + arm (677 bytes legacy, first run)"
  - "`specialUse` foreground service. Killed by the OS = cold, nothing spent."
  - "HyperOS/MIUI keep-alive onboarding and a 'killed last night' health check"
  - "Android 16 Live Update notification with a 14/15 fallback"
  - "797 JVM unit tests"
- **Visual:** Phone captures: the tile in the Quick Settings panel, the wallet sheet, the ongoing
  notification `[TBD after device test]`.
- **Speaker notes:** "Only the launcher activity and the tile service are exported. The trampoline
  reads no intent extras. Release builds strip every `android.util.Log` call, backups are off, RPC
  is HTTPS-only and the crank uplink WSS-only. The app shows success only after the transaction is
  confirmed with no error."
- **Serves:** Public: UX. Align: UX, UI.
- **Sources:** `android/README.md` (modules, clock-in, security notes),
  `android/INTERFACE-NOTES.md` §3 (677 bytes), `android/` (count of `@Test`).

## Slide 11: SKR is collateral and gifts, and never pays you for holding it

- **Message:** SKR has four jobs, each tied back to ORE, and none of them pays a return for locking
  SKR.
- **On slide:**
  - "Stack: players bond SKR on keeping their phones face-down. Finishers split 80% of forfeits;
    20% buys ORE that goes through ORE's own `bury` (90% burned)."
  - "Focus Bond: a solo bond, chosen on the app's home screen. A forfeit goes to the Bury
    auction, never to the team."
  - "Gift a Rig: SOL escrowed for a wallet, or for a Seeker's SGT (a .skr name resolves to it).
    The claim can arm the recipient's rig in the same transaction. The sender can pay in SKR
    through a swap in the same approval."
  - "SKR fuel: SKR to SOL inside the clock-in transaction (a swap in the user's own
    transaction)."
  - "No SKR emitted, none routed to the team; Solana Mobile's SKR program is never called."
  - "Status: **Built** in the program (13 instructions, tested on the live ORE fork with ORE's
    real `bury`), in the crank (check-ins, settlement, forfeits, refunds), in the indexer and on
    the dashboard. In the app: the Focus Bond. Stack and Gift transactions are built and tested;
    their screens are **Next**."
- **Visual:** Flow diagram: SKR → Stack / Focus Bond / Gift / Fuel → Dutch auction → ORE `bury`
  (90% burned, 10% distributed by ORE, shown as two lines).
- **Speaker notes:** "Forfeits come only from other players at the same table; nothing is minted.
  The Bury auction uses a descending price, so it needs no oracle. We say plainly that forfeit
  payouts may count as wagering in some places, so bury-only tables exist, bonds are capped and
  the app is 18+."
- **Serves:** Align: SKR (20%), Ecosystem Impact. Public: Stickiness & PMF.
- **Sources:** `docs/SKR.md` (flows, §6, §9, §1 legal risk), `docs/ECONOMICS.md` §6,
  `programs/heads-down/INTERFACE.md` §11, `dashboard/src/app/skr/page.tsx`.

## Slide 12: ORE is the product

- **Message:** Every rig is an ORE miner with its own Automation, and ORE can verify our usage from
  its own logs.
- **On slide:**
  - "Each rig: your own ORE Automation; executor = Heads Down PDA; Discretionary, fixed fee"
  - "ORE can recompute our numbers: `DeployEvent`s with signer = Executor PDA, over
    `ResetEvent.total_miners`"
  - "M1, +30 days: live on the dApp Store, mainnet executor, 250 rigs (at least 50 SGT-verified)"
  - "M2, +90 days: 1,000 rigs, 300 nightly, at least 15% of ORE miners per round 00:00 to 06:00 in
    one region"
  - "M3, +180 days: immutable v1, a third-party crank landing digs, monthly ORE mined / bought /
    buried"
  - "Found: ORE stores `max_production_cost` but `deploy` never reads it. Heads Down enforces its
    own gate."
- **Visual:** The dashboard's `/milestones/` meters.
- **Speaker notes:** "At 165 to 172 miners per round, 15% is roughly 25 concurrent rigs. The
  stretch targets from the spec are 250 SGT-verified rigs and 25% in two regions. ORE stays the
  featured brand on the rig, the reveal and every post, and no competing mining product is
  supported. We will raise the `max_production_cost` finding with ORE through their channel."
- **Serves:** Align: Ecosystem Impact. Public: Innovation/X-factor. ORE matched prize.
- **Sources:** `docs/ORE.md` §9 and §1, `services/indexer/README.md` (metric definitions),
  `spikes/ore-executor/README.md` (facts established).

## Slide 13: The worst case for each key

- **Message:** Every key has a bounded, written worst case, and custody stays in ORE.
- **On slide:**

  | Key | Worst case | What bounds it |
  |---|---|---|
  | Your wallet | Everything it controls: it is the root | Your wallet; every tx built on-device, its amounts bounded and shown before the wallet opens |
  | Rig P-256 key (phone) | The armed, capped budget deploys into ORE only while the gate is open; about 10.5% goes to ORE fees, none to the attacker; its own SKR bonds | Caps and expiry, the on-chain gate, Freeze (phone), Unfreeze (wallet) |
  | Crank | For mining, liveness only: nothing mines, nothing is lost. A Focus Bond or a Stack seat is lost if no heartbeat lands | Anyone can run `hd-crank`; fixed reimbursement. Only the team's runs today |
  | Registrar | Software keys pass as hardware on capped remote Stack tables | No custody, no mining power; bond caps; voucher lifetime capped on-chain; an append-only log |
  | Upgrade authority (beta) | Today, with one key and no delay: Heads Down-held SKR and SOL; forced deploys up to 25 × the per-square amount per round. Never an Automation's balance or anyone's ORE | Small custody (bond caps); a multisig with a 72 h delay is planned, then revocation |
  | Team servers | Liveness, privacy, phishing-shaped pushes | No signing from push; txs rebuilt from chain |
  | ORE upstream | ORE owns every Automation and can move funds | Inherited and stated; layout pins and a circuit breaker for accidents |

- **Visual:** The table in the night palette; the rig key row highlighted.
- **Speaker notes:** "Heads Down reuses ORE's custody instead of writing its own vault for mining
  funds, so we inherit ORE's trust assumption in full, and we say so. The rig key can't require
  user authentication because it signs while the phone is locked; that is why the wallet-signed
  caps, not the key, bound the damage. Config changes and governance rotation wait 72 hours in
  code. Program upgrades do not: at launch the upgrade authority is one key, mine, and the threat
  model says so in plain words. A multisig with a delay comes before the caps go up."
- **Serves:** Align: Ecosystem Impact, Innovation. Public: UX (trust).
- **Sources:** `docs/THREAT_MODEL.md` (As built, Summary, K2, K5, K7), `docs/SECURITY_REVIEW.md`,
  `programs/heads-down/README.md` (worst case per key, `admin` suite).

## Slide 14: Open, tested, checkable

- **Message:** The trust layer is open source, with tests anyone can re-run.
- **On slide:**

  | Component | Tests | Where the number comes from |
  |---|---|---|
  | `heads_down` program | 171 passed (138 on a fork of live ORE) | re-run 2026-10-03; `programs/heads-down/README.md` |
  | `hd-crank` | 168 passed, plus 14 against the real program on the fork | re-run 2026-10-03 |
  | Registrar (SIWS + Key Attestation) | 105 passed | re-run 2026-10-03 |
  | `p256-introspect` | 30 passed | re-run 2026-10-03 |
  | `sgt-verify` (host suites) | 69 passed, plus LiteSVM suites | `crates/sgt-verify/README.md` |
  | secp256r1 spike | 16/16 LiteSVM, 9/9 on a real validator | `spikes/secp256r1/README.md` |
  | Indexer | 347 passed | re-run 2026-10-03 |
  | Dashboard | 48 passed | re-run 2026-10-03 |
  | Android | 797 JVM unit tests | re-run 2026-10-03 |
  | End to end, local fork of mainnet | clock-in, dig, lift, replay refused, indexed | `docs/DEVSTACK.md` |

  - "16 bug classes mapped to named tests; 9,000 fuzzed instructions on the program binary, no aborts"
  - "Security review before deployment: 114 findings, 18 confirmed by a reproducing test, each
    fix with its regression test; what is still open is written down"
  - "Registrar: a hash-chained log of every voucher (a file today, not yet published)"
  - "Dashboard: a SIMULATED banner shows on every page while data is simulated"
  - "Not yet: Radiants audit run, Kani proofs, external audit `[TBD]`"
- **Visual:** The table with repo paths in a mono font.
- **Speaker notes:** "`sgt-verify` and `p256-introspect` are standalone crates any Solana program
  can use. The program has one `unsafe` block, the log syscall, and denies panics and unchecked
  arithmetic at lint level."
- **Serves:** Public: Presentation & Demo (technical depth). Align: Ecosystem Impact.
- **Sources:** as in the table; `programs/heads-down/README.md` (audit checklist, fuzz),
  `docs/SECURITY_REVIEW.md`, `registrar/README.md` (transparency log), `dashboard/README.md`
  (honesty and safety).

## Slide 15: Traction (placeholder until real users)

- **Message:** `[TBD after launch]`. Every number will link to the chain.
- **On slide:**
  - "Rigs `[TBD after launch]` · Seeker-verified `[TBD after launch]` · Nightly active
    `[TBD after launch]`"
  - "D1 / D7 / D14 retention `[TBD after launch]`"
  - "Share of ORE miners per round, 00:00 to 06:00 `[TBD after launch]`"
  - "Founder's Redmi rig: nights run, rounds dug `[TBD after device test]`"
  - "Today: heads_down is not deployed on mainnet; the dashboard runs on a labelled simulated
    dataset."
- **Visual:** Dashboard tiles. Show the SIMULATED banner if the data is still simulated.
- **Speaker notes:** "No vanity counts, no paid testers, no referral bounties. Metrics come from
  on-chain rows only, and anyone can recompute them from the CSV exports."
- **Serves:** Public: Stickiness & PMF. Align: Ecosystem Impact.
- **Sources:** `services/indexer/README.md` (metric definitions, simulation mode),
  `dashboard/README.md`, `docs/SPEC.md` (TRACTION_AND_LAUNCH_PLAN rules).

## Slide 16: Landscape

- **Message:** Others mine ORE on a schedule from a server; Heads Down mines only while this phone is
  down, and the chain enforces it.
- **On slide:**

  | | Native Android | Mining gated on the phone | Executor signs only for a phone heartbeat | Mine-or-buy with a published backtest | SGT-verified tier |
  |---|---|---|---|---|---|
  | RefinORE AutoMiner | store app | no (runs with the app closed) | no (server) | not found | not found |
  | Orestack | TWA | no | no (server) | EV-gated, no backtest found | not found |
  | Oreminer | store app | no | no (server) | not found | not found |
  | ore.com | web + PWA | no | no (ORE's own executor) | not found | no (`claim_seeker` removed) |
  | **Heads Down** | Kotlin/Compose | **yes** | **yes** | **yes** | **yes** |

- **Visual:** The matrix, with Heads Down's row in ember orange.
- **Speaker notes:** "'Not found' means our review didn't find it, not that it can't exist. Take
  away the phone gate and Heads Down would be another autominer; the gate is the product."
- **Serves:** Public: Innovation/X-factor. Align: Innovation.
- **Sources:** `docs/research/redteam.md` (store listings and copy), `docs/research/skr-and-ore.md`
  (ore.com, refinORE), `buildplan.md` (no phone-gated ORE repo or app found).

## Slide 17: Where it stands, and who is building it

- **Message:** The hard parts are proven on a mainnet fork; the next step is the phone in the loop.
- **On slide:**
  - "Proven: PDA executor on live ORE; on-chain P-256 heartbeats; unforgeable SGT check; honest
    economics; Stack, Focus Bond, Gift a Rig and the Bury auction on the live ORE fork; the whole
    loop end to end on a local fork of mainnet; registrar on real Google attestation chains."
  - "Before submission: mainnet deploy with tiny caps; the Redmi's Keystore signing on-chain
    `[TBD after device test]`."
  - "After: the Stack and Gift screens; the classifier retrained on real recordings; an upgrade
    multisig, then an immutable v1."
  - "Founder: solo, Nigeria (WAT), building with AI agents on one Redmi 14C. `[founder to
    personalise]`"
- **Visual:** Three columns: Proven / Next / After. Founder photo `[TBD]`.
- **Speaker notes:** "Four hundred and fourteen commits since the repo was created on 29 September
  2026 `[update the count before recording]`. Offline means no mining and no loss, which suits power and
  network cuts where I live."
- **Serves:** Public: Stickiness & PMF (founder-market fit), Presentation & Demo.
- **Sources:** `README.md` (proven so far), `spikes/*/README.md`, `crank/README.md`,
  `registrar/README.md`, `git rev-list --count --no-merges main` (414 on 2026-10-03).

---

## Appendix: one line per criterion

| Criterion | Claim | Source |
|---|---|---|
| Stickiness & PMF | The shift hangs on bedtime, the charger and the alarm; focus-only shifts still count on nights with no SOL. | `docs/SPEC.md` (CORE_LOOP), `android/README.md` |
| UX | One wallet approval per clock-in, from a Quick Settings tile. | `android/INTERFACE-NOTES.md` §3 |
| UI | Night palette (charcoal, ember orange, ORE gold) shared by the app and the dashboard; accessible charts. | `dashboard/README.md` (Design) |
| Innovation | A third-party ORE executor that signs only for an on-chain-verified phone heartbeat. | `programs/heads-down/README.md` |
| Presentation & Demo | The demo shows a replayed heartbeat skipped and a fresh one digging, on an explorer. | `docs/pitch/DEMO_SCRIPT.md` |
| AI | On-device detection, a pickup classifier and a shift planner are built (trained on synthetic data and simulated users so far); the forecaster was demoted to advisory by a pre-set rule. | `ml/foreman/README.md`, `ml/forecaster/MODEL_CARD.md` |
| SKR | Bonds, gifts, fuel and a Bury auction; no emission, nothing paid for locking. Built in the program, the crank, the indexer and the dashboard; the Focus Bond is in the app. | `docs/SKR.md` |
| Ecosystem Impact | Two open-source crates, verifiable ORE usage metrics, a milestone plan. | `crates/`, `docs/ORE.md` §9 |
