# Build in public: 10 draft X posts

> **DRAFTS. Rewrite every post in your own words before posting.** Beeman's advice is to engage
> authentically and not post AI-written text (`docs/research/judges-preferences.md`). These drafts
> only fix the facts: each one is tied to a real commit on `main`, with the file that proves it.

**Rules for every post**

- **Numbers.** Use only numbers that appear in the repo. Each post lists where its numbers come from.
- **Wording.** Follow `docs/ECONOMICS.md` §4 (dig, haul, bond, "the cheaper route"). No hype.
- **Links.** One link per post: the commit or the file, once the repo is public (`[link]`, which X
  counts as 23 characters).
- **ORE.** It is the featured brand. Tag ORE (`[@ORE handle]`) where it fits, which the ORE prize
  asks for (`docs/research/judges-preferences.md`).
- **Media.** Attach a screenshot or a short clip. A terminal or test output is fine; stock images
  are not.
- **Timing.** All of these commits landed on 2026-09-29. Posting them over the following days is
  fine, but don't imply the work happened on the posting day. The linked commit shows its real date.

---

### 1. Spike 1(a): our program can be the ORE executor (DRAFT)

- **Commit:** `c559577` test(spike): PDA executor deploys via ORE CPI on a mainnet fork; cost gate,
  spoof and checkpoint-fee cases pass
- **Proof:** `spikes/ore-executor/README.md` (5/5)
- **Attach:** the `cargo test` output with the five test names.

> Building Heads Down for Clock In, solo with AI agents: face-down, your phone digs ORE, and only
> the phone can switch it on. Q1: can a Solana program be the executor of your ORE Automation?
> Tested against the live ORE binary on a local fork. 5/5 pass. [link]

### 2. The phone's key, checked on-chain (DRAFT)

- **Commits:** `3421853` test(spike-secp256r1): LiteSVM e2e vs Agave precompile; `6ae22a7`
  cross-check on solana-test-validator 4.1.2 (9/9)
- **Proof:** `spikes/secp256r1/README.md` (16/16 LiteSVM, 9/9 validator, 477 CU)
- **Attach:** the attack table from the README.
- **Since 2026-10-10:** the "Next" of the last sentence has happened. The Redmi's own Keystore
  key, in its TEE, signed heartbeats that dug five rounds on mainnet. Reword that sentence if this
  goes out after that date.

> The phone signs, the chain checks. P-256 signatures made the way Android Keystore makes them now
> verify on-chain via the secp256r1 precompile. 16/16 attack tests in LiteSVM, 9/9 on a real
> validator, 477 CU. Next: the same from my Redmi's real Keystore. [link]

### 3. The Seeker check you can't fake (DRAFT)

- **Commits:** `efd15be` feat(sgt-verify): verify_sgt policy anchored on TokenGroupMember;
  `ba2cadc` test(sgt-verify): LiteSVM suite with real Token-2022 and forgeries
- **Proof:** `crates/sgt-verify/README.md` (threat model, Credits)
- **Attach:** the test line where the forged mint fails with `MissingGroupMember`.
- **Tone:** credit ORE. `claim_seeker` was removed from ORE in Nov 2025, so this is not a live bug.

> Checking "is this a real Seeker" on-chain is tricky. With the real Token-2022 program you can
> forge a mint whose authority and metadata pointer look right. Group membership can't be faked, so
> sgt-verify anchors on it. Credit to ORE's claim_seeker. [link]

### 4. ORE doesn't check that price field (DRAFT: post only after ORE has been told)

- **Commits:** `0bbb477` docs(ore): ground the ORE integration in the verified deployed source;
  `eb463f6` docs(spec): correct the max_production_cost enforcement claim
- **Proof:** `docs/ORE.md` §9 ("For ORE"), `spikes/ore-executor/README.md`
- **Before posting:** raise it with ORE through their channel first (`docs/ORE.md` §9 says it will
  be). Post only once they have replied or a week has passed.

> Reading ORE's source: an Automation can store a max_production_cost condition, but deploy never
> checks it. Only the Motherlode bounds are enforced. So Heads Down checks the price itself,
> in-program. Told the ORE team first. [link]

### 5. The economics, before any promises (DRAFT)

- **Commits:** `c510099` feat(forecaster): spend-matched mine-or-buy backtest with night bootstrap;
  `e441eb4` docs(forecaster): publish mine-or-buy results for small nightly budgets
- **Proof:** `ml/forecaster/RESULTS.md` (TL;DR)
- **Attach:** `ml/forecaster/figures/eff_price_vs_budget.png`

> Backtested 58,801 ORE rounds before promising anything. Digging a bit every round on 0.02 to 0.05
> SOL a night costs 36 to 90% more than buying ORE. Fixed fees eat it. Gated 0.001 SOL digs, then
> buy the rest: 1.4 to 3% cheaper. Small, and I'll say so. [link]

### 6. My forecaster lost (DRAFT)

- **Commits:** `d4796b7` feat(forecaster): walk-forward EMA/price forecaster scored as a forecast
  and as a gate; `55d6909` docs(forecaster): model card, advisory demotion and the on-chain rule
  that ships
- **Proof:** `ml/forecaster/MODEL_CARD.md`, `ml/forecaster/RESULTS.md` §5
- **Attach:** `ml/forecaster/figures/forecast_walkforward.png`

> My forecaster lost. An hour ahead it predicts ORE's cost/price ratio 9% better than "same as
> now". As a gate it adds nothing over a rule the program computes on-chain. So it's advisory: it
> explains the night, never decides. Rule set before training. [link]

### 7. No gyroscope, aggressive OS (DRAFT)

- **Commits:** `44de569` feat(shift): accelerometer-only face-down detector with hysteresis;
  `e66e8cd` feat(oem-keepalive): HyperOS/MIUI detection and resilient settings deep links;
  `d5266db` feat(oem-keepalive): "last night's shift was killed by the OS" health check
- **Proof:** `android/README.md` (modules, shift loop)
- **Attach:** a photo of the Redmi on the nightstand.

> My only test phone is a Redmi 14C: no gyroscope, a virtual proximity sensor, and HyperOS that
> kills apps. So face-down detection is accelerometer-only, and if HyperOS kills the shift, the rig
> just goes cold. Nothing spent. The app tells you next morning. [link]

### 8. Clock-in from the Quick Settings tile (DRAFT)

- **Commits:** `d768c1f` feat(tile): Heads Down Quick Settings tile with state subtitle; `93418a4`
  feat(tile): non-exported translucent MWA trampoline; `219a696` feat(app): wire the tile ->
  trampoline -> MWA clock-in to the real chain layer
- **Proof:** `android/README.md` (clock-in), `android/INTERFACE-NOTES.md` §3
- **Attach:** a screen recording of the tile, once filmed on the Redmi `[TBD]`. The phone's first
  clock-in on mainnet was on 2026-10-10, signed in Jupiter Mobile; whether it began from the tile
  was not recorded.

> Clock-in is a Quick Settings tile next to Do Not Disturb. Tap, one wallet approval, phone
> face-down. That one transaction sets up your ORE Automation, registers the rig, sets caps and
> arms the shift. Then the phone's own key signs every heartbeat. [link]

### 9. The program, tested on a fork of mainnet ORE (DRAFT)

- **Commits:** `7dbf256` test(heads-down): LiteSVM mainnet-fork harness and full lifecycle suite;
  `c6c8084` test(heads-down): measure CU per dig and max rigs per v0 / v0+ALT / v1 transaction;
  `dcc89e2` test(heads-down): no-abort fuzzing of the SBF binary
- **Proof:** `programs/heads-down/README.md` (Tests, Measurements)
- **Attach:** the suite table from the README.

> heads_down is in: 15 instructions, 76 tests against a fork of mainnet ORE, including replayed
> heartbeats, forged sysvars and a fake ORE that tries to drain the executor. Up to 11 rigs per v1
> transaction, 5 per v0 with a lookup table, about 37k CU per rig. [link]

### 10. The crank, end to end (DRAFT)

- **Commit:** `15a8827` test(crank): end-to-end run on solana-test-validator with live ORE
- **Proof:** `crank/README.md` (measured run: 3 rigs, 20,168 lamports in fees, 21,000 reimbursed)
- **Honesty note:** that run used the interface mock of the program. The fees are real; the compute
  figures are an upper bound.

> The crank runs end to end on a local validator with the live ORE binary and a stand-in for my
> program: 3 rigs dug in one transaction, 20,168 lamports in fees, 21,000 paid back. Anyone can run
> it. If every crank dies, nothing digs and nothing is lost. [link]

---

**Next posts.** Two of the three that were waiting are real since 10 October 2026 and can be
written from [MAINNET.md](../MAINNET.md): the mainnet deploy (program
`HDn4vgLWFLLdexKEwfZwRHjWtizNvdqFteLbMsE67F9p`, slot 455,359,196) and
[the first dig from the Redmi's own Keystore key](https://solscan.io/tx/25nBRCP6GExrKG4EDszGrKfWN9yYgJMopgnawTGi2Z9XNGj6UCtZgE6HFJN7rJXvu2C3VMjhh7bpXZnQg8DVzovY).
Say what it was: one rig, the founder's own phone, one short shift of five digs of 0.001 SOL, on a
debug build with the cost ceiling raised, and no ORE won so far. The third is not real: a night of rigs
on the dashboard that are not the founder's. There are no users.
