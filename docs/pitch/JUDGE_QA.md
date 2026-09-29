# Judge Q&A: the 20 hardest questions

Prep for the founder, written 2026-09-29. Each answer is short, honest and linked to the file that
backs it. Where the honest answer is "not built yet" or "not tested yet", it says so.

**Personas** (`docs/research/judges-preferences.md`):

- **Toly:** open source, immutable, no admin keys, consumer apps.
- **Mert:** privacy, no admin keys, security engineering.
- **Chase:** real-world users, public goods, building in public.
- **Beeman:** MWA, official stack, working demos.
- **Akshay:** distribution and retention.
- **EthelSec:** Android and program exploitation.
- **ORE:** the matched-prize sponsor.
- **Align:** the AI pre-screen, which weights AI and SKR at 20% each.

---

### 1. "Isn't this just an autominer with a phone switch?" (Toly, Seeker owners)

The mining is ORE's own Automation; what is new is who can switch it on. The executor is a program
address that signs ORE's `deploy` only when the same transaction carries a secp256r1-verified
heartbeat from the phone's Keystore key, for the current ORE round, with a counter that must go up.
The store autominers run from a server with the app closed. Take away the phone gate and Heads Down
would be one more autominer; the gate is the product.

*Evidence:* `spikes/ore-executor/README.md`, `programs/heads-down/README.md` (`heartbeat` suite,
9 tests), `docs/research/redteam.md` (store autominers).

### 2. "At your budgets mining costs more than buying. Why would anyone do it?" (Toly)

We published that ourselves. Spread over every round, 0.02 to 0.05 SOL a night costs 36 to 90% more
than buying. The design that ships digs 0.001 SOL chunks only when the on-chain Motherlode-aware
gate opens, then buys the rest. That came out 1.4 to 3.0% cheaper than buying when claimed each
morning, and 2.9 to 5.5% when the ORE is kept unrefined (at the smallest budget that interval
includes zero). At the 2026-09-29 snapshot the gate was shut. It's a modest edge. The product's value is the ritual and the trustless gate, and the price
paid is shown, not hidden.

*Evidence:* `ml/forecaster/RESULTS.md` (TL;DR, §3), `docs/ECONOMICS.md` (Summary, §5).
*If pressed:* the clock-out buy leg is not built yet. Until it is, a night with the gate shut digs
nothing and spends nothing.

### 3. "Who holds admin keys? Can you drain users?" (Toly, Mert)

No instruction moves Automation lamports. The Executor PDA signs only ORE `deploy` and its own
reimbursement transfer, and there is no withdraw path. Governance can change the registrar,
`crank_fee` (at most `executor_fee`) and `bury_bps` after a 72 h timelock, and pause at once. It
cannot move funds or change `executor_fee`. During beta the upgrade authority is full program
authority. The plan is a Squads multisig behind a 72 h timelock, then revocation for an immutable v1
`[TBD at deploy]`.

*Evidence:* `programs/heads-down/README.md` (Invariants, worst case per key, `admin` suite),
`docs/THREAT_MODEL.md` K5.

### 4. "Where does my SOL sit, and what if ORE itself turns malicious?" (Mert)

In your own ORE Automation, owned by ORE. Heads Down has no vault for mining funds. ORE's upgrade
authority can move Automation funds whatever we do; that is the trust every ORE miner already places
in ORE, and we inherit it and say so. Our pins protect against accidental layout changes, not
against a malicious ORE.

*Evidence:* `docs/THREAT_MODEL.md` K7 and §10 (item 1), `docs/ORE.md` §8.

### 5. "Your heartbeats publish when I sleep. Isn't that a privacy leak?" (Mert)

Yes, and the app says so before you register a rig. Rig activity is public like any ORE miner's, to
within one round (about 78 s), which reveals idle and sleep windows and probably your time zone.
About one transaction per dug round also lists your wallet as a writable account, because ORE
requires it. Mitigations: a secondary wallet account as the rig authority, or a guest rig with a
fresh wallet. There is no location anywhere, sensor data never leaves the phone, and the registrar
never requests device-ID attestation. Not done: private or confidential execution.

*Evidence:* `docs/PRIVACY.md` §2 and §5, `registrar/README.md` (Logging and PII).

### 6. "If my phone, or your app update, is compromised, what's the worst case?" (Mert, EthelSec)

The rig key cannot be exported from Keystore, but code inside the app can use it. The worst case:
the armed budget (at most the weekly cap, before expiry) deploys into ORE only in rounds where the
gate is at or below the ceiling your wallet signed. About 10.5% of that goes to ORE fees and none to
the attacker. Once SKR ships, it could also forfeit this rig's own bonds. It cannot raise caps,
unfreeze, change the executor, withdraw or claim. Freeze needs only the device key; Revoke is one
wallet approval. The residual risk: the key cannot require user authentication, because it signs
while the phone is locked. On solo shifts, a lease of up to 3 rounds (about 4 minutes) can outlast
an offline pickup.

*Evidence:* `docs/THREAT_MODEL.md` K2 and §9, `crates/p256-introspect/README.md` (the
`setUserAuthenticationRequired` section).

### 7. "What happens when your crank goes down?" (Mert, ORE)

Nothing digs and nothing is lost. The crank has liveness only: it chooses whether and when to submit
a heartbeat, while the program chooses amount and squares and verifies every heartbeat. Anyone can
run `hd-crank`, and the program reimburses a fixed fee only after a real deploy. A measured run
(against the interface mock of the program): 3 rigs cost 20,168 lamports in fees and were
reimbursed 21,000. Honest gaps: the Nostr heartbeat
mirror and the LaserStream source are stubs; the WebSocket source works.

*Evidence:* `crank/README.md` (Threat model, Transactions, Features and stubs).

### 8. "Show me the program's attack surface." (EthelSec)

There is a 16-class checklist, with the concrete check and the named test that fails without it,
from missing signer checks to sysvar spoofing and resource exhaustion. 76 tests run on a fork of the
live ORE binary, including a test-only ORE that tries to drain the Executor float (reverted).
5,000 fuzzed instructions on the SBF binary gave only clean errors. There is one `unsafe` block (the
log syscall), and lints deny panics, indexing and unchecked arithmetic. Not yet: an external audit,
the Radiants audit run, or Kani proofs.

*Evidence:* `programs/heads-down/README.md` (Audit checklist, Tests).

### 9. "And the Android client?" (EthelSec)

Only `MainActivity` is exported, plus the tile service behind `BIND_QUICK_SETTINGS_TILE`. The MWA
trampoline, reveal, receiver and shift service are not exported, and the trampoline reads no intent
extras. R8 strips every `android.util.Log` call in release, and lint fails the build on one. Backups
are off. RPC is HTTPS-only, the crank uplink WSS-only, and the build refuses URLs with a query
string or user-info. The MWA auth token is AES-GCM-encrypted with a Keystore key. Success shows only
after confirmation with `err == null`. Not yet: on-device testing, and a run of the AlignAI MWA
fixtures `[TBD]`.

*Evidence:* `android/README.md` (Security notes), `docs/THREAT_MODEL.md` §8.

### 10. "What does your attestation registrar trust, and what if its key leaks?" (EthelSec, Mert)

It checks the chain up to pinned Google roots, the exact chain shape, TEE or StrongBox, verified
boot with a locked bootloader, our package and release-certificate digest, and a challenge bound to
the wallet. It then signs an Ed25519 voucher, which the program checks through precompile
introspection. Every voucher goes into a public, hash-chained log. If the key leaks, software keys
can pass as hardware, so a cheater could win capped remote Stack tables. It has no custody and no
mining authority. Leaked keyboxes on rooted phones are a residual risk; the revocation list is
checked and fails closed. 105 tests, including real Google attestation chains.

*Evidence:* `registrar/README.md`, `docs/THREAT_MODEL.md` K4.

### 11. "This is a Seeker hackathon. Why film on a Redmi?" (Beeman, Seeker owners)

It's the only phone I have. The rules allow any Android device, and Solana Mobile opened its stack to
all Android makers, so the guest tier runs on any Android with a mobile wallet. The Seeker tier is
verified in-program by SGT and tested against real mainnet SGTs (#20 and #121,035) and against
Token-2022-issued test SGTs. Not tested on a physical Seeker: Seed Vault's double-tap and Live Update
on Seeker. Remote Seeker testers are the plan `[TBD]`.

*Evidence:* `buildplan.md` (device constraint), `crates/sgt-verify/README.md`,
`programs/heads-down/README.md` (`seeker` suite), `docs/research/judges-preferences.md`.

### 12. "Is the MWA integration done properly?" (Beeman)

It uses clientlib-ktx 2.2.0 in one session: authorize, `get_capabilities` (v0 if supported, else
legacy), build, sign and send. The registrar issues single-use, 10-minute SIWS nonces. The auth
token sits in a Keystore AES-GCM vault. A confirmation poller handles blockheight expiry, and
success requires `err == null`. Honest: it has not run against a real wallet on the Redmi
`[TBD after device test]`. Three joins are still open: the app's instruction layouts against the
program, its heartbeat JSON against the crank, and its SIWS payload against the registrar.

*Evidence:* `android/README.md`, `registrar/README.md`. The open joins:
`android/INTERFACE-NOTES.md` §1 vs `programs/heads-down/INTERFACE-NOTES.md` §4, `crank/README.md`
(intake protocol), `registrar/INTERFACE-NOTES.md` N7.

### 13. "Most Seekers are second phones. Success here is a phone nobody touches. How does that help Solana Mobile?" (Akshay, Chase)

That's the point: give the idle phone a job, not "use your phone less". About 118.9k of about 121k
.skr IDs look dormant per SeekerTracker. Each shift starts with a user-signed transaction into ORE,
and clock-out will be a second one (next). We don't claim to measure your focus; we measure this
phone's face-down time. Unknown: whether digs that list the wallet as writable count toward Seeker
Activity Tracking. That's an open question for Solana Mobile.

*Evidence:* `docs/research/redteam.md`, `docs/SPEC.md` (OPEN_QUESTIONS), `docs/PRIVACY.md` §2
(wallet footprint).

### 14. "What brings people back without airdrops or points?" (Chase, Akshay)

The shift hangs on bedtime, the charger and the alarm, which people already do every day.
Focus-only shifts count on nights with no SOL. There is no token, no points, no emissions, no paid
testers and no referral bounties. Retention: `[TBD after launch]`. D1/D7/D14 cohorts are computed
from on-chain rows only.

*Evidence:* `docs/SPEC.md` (CORE_LOOP, TRACTION_AND_LAUNCH_PLAN), `docs/ECONOMICS.md` §7,
`services/indexer/README.md`.
*If pressed:* the morning reveal, the part of the loop that changes night to night, is a stub
today.

### 15. "Aren't the Motherlode and the Stack forfeits just wagering?" (Toly, Seeker owners)

We never script or headline a Motherlode, and the price display puts its odds next to its size. By
default a rig digs on the 15 split squares: in the backtest that gave no zero-ORE nights and a
coefficient of variation of 0.32, against 9.6 on all 25 squares. Stack forfeits that pay finishers
may count as wagering in some places, and we say so. So there are bury-only tables where nobody
gains from a flinch, bonds are capped, the app is 18+ and there is no house cut. Stack is designed,
not built.

*Evidence:* `docs/ECONOMICS.md` §4, `ml/forecaster/RESULTS.md` §4, `docs/SKR.md` §1.

### 16. "Doze, HyperOS killing apps, foreground-service rules: will a shift survive the night?" (Beeman, EthelSec)

Not yet known on the Redmi `[TBD after device test: 8 h with and without Autostart]`. The design
fails safe: a `specialUse` foreground service with `START_NOT_STICKY`, so if the OS kills it, there
are no heartbeats, no digs and nothing spent. The HyperOS/MIUI onboarding deep-links Autostart and
"No restrictions", and a morning check reports a killed shift. Leases of up to 3 rounds tolerate
short network drops.

*Evidence:* `android/README.md`, `buildplan.md` (spike 1(c)), `docs/THREAT_MODEL.md` §8 (Redmi
specifics).

### 17. "Why accumulate ORE instead of saving in USDC?" (Mert, Toly)

They do different jobs. A USDC vault is a balance product, and Solana Mobile already ships one in
Seed Vault Wallet (2026-08-19). Heads Down is a nightly ritual for people who already want ORE: it
picks the cheaper route to ORE each night and gates it on the phone. We make no claim that ORE holds
its value, and the haul is shown against the market price.

*Evidence:* `docs/research/judges-preferences.md` (USDC vault), `docs/ECONOMICS.md` §3 and §4,
`docs/SPEC.md` (GRAFTS: "never claim ORE doesn't melt").

### 18. "ORE changes often. What happens when it does?" (ORE, Toly)

On-chain pins check every ORE account: pinned ids, owner, exact size, discriminator and sanity
values. Any mismatch refuses the dig, and funds stay in users' Automations. The crank latches a
circuit breaker if ORE's ProgramData upgrade slot moves off 450,496,378, and resumes only after the
fork suite passes against the new binary. A breaking change means deploying v2, and users re-point
their executor with one approval. Honest: the on-chain version pin is not implemented. A fee change
that keeps the layout is caught only by the crank's breaker. Rule changes also invalidate the
forecaster, which is one reason it is only advisory.

*Evidence:* `docs/ORE.md` §8, `crank/README.md` (circuit breaker),
`programs/heads-down/INTERFACE-NOTES.md` §10, `ml/forecaster/MODEL_CARD.md` (Limits).

### 19. "Small budgets behind a phone gate: does this bring ORE real usage?" (ORE)

In miners, yes; in SOL, deliberately little. ORE has 165 to 172 miners per round. Milestone M2 asks
for at least 15% of unique miners per round from 00:00 to 06:00 in one region (about 25 concurrent
rigs), and the stretch is 25% in two regions (about 42). Every metric can be recomputed from ORE's
own `DeployEvent`s where the signer is the Executor PDA. The morning buy leg and ORE burned through
the Bury auction add to it, but neither is built yet.

*Evidence:* `docs/ORE.md` §1 and §9, `services/indexer/README.md` (metric definitions).

### 20. "Are the AI and SKR parts real, or slideware?" (Align)

It depends on the part, and each slide says which. AI, built: the cost forecaster (trained,
backtested, and demoted to advisory by a rule set before training) and the accelerometer detector.
AI, next: the LiteRT pickup classifier and the Shift Planner. SKR: fully specified (Stack, Focus
Bond, Gift a Rig, fuel, the Bury auction) but not yet in the program. The SGT verifier those flows
depend on is built.

*Evidence:* `ml/forecaster/MODEL_CARD.md`, `docs/SKR.md`, `programs/heads-down/INTERFACE.md`
(instruction list), `crates/sgt-verify/README.md`.
