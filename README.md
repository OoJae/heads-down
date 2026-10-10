# Heads Down: your phone's night shift, powered by ORE

> Clock in, lay your phone face-down, and it can mine [ORE](https://ore.com). Pick it up and the rig goes cold.
> A dig needs a heartbeat signed by a key in your phone's Android Keystore, verified on-chain. Our servers cannot forge one.

An entry for [Clock In](https://solanamobile.radiant.nexus/), the third Solana Mobile hackathon.
Native Android, built on the Solana Mobile Stack and Mobile Wallet Adapter. Runs on any Android
phone with a Solana wallet; Seeker owners get a tier verified on-chain by their Seeker Genesis Token.

## How it works

You clock in with **one wallet approval**. It funds a capped shift inside **your own ORE Automation
account** and arms your rig. While the phone lies face-down it signs a heartbeat for every ORE
round (about 78 seconds) with a P-256 key that never leaves the Android Keystore. Anyone may
submit a dig for your rig, but the `heads_down` program signs ORE's `deploy` **only** when that
heartbeat verifies on-chain, in the same transaction, through Solana's secp256r1 precompile. Pick
the phone up and the next dig is refused with `StaleHeartbeat`. If the phone dies or the app is
killed, nothing is mined and nothing is spent.

Your SOL never sits in a Heads Down account: it stays in ORE's own Automation and Miner accounts,
which only your wallet can withdraw from or claim.

The program digs only when ORE's own production cost is under the ceiling your wallet signed.
A backtest over 58,801 real ORE rounds showed that mining every round costs more than buying ORE;
mining only when that gate opens came out 1.4 to 3.0% cheaper than buying
([ml/forecaster/RESULTS.md](ml/forecaster/RESULTS.md)). That is a modest edge and we say so.

## SKR

SKR is collateral and a gift currency here. It is never locked to be paid for holding it.

- **Focus Bond:** lock SKR on tonight's shift from the app's home screen. Finish the shift and the
  same SKR comes back. Break it and the SKR goes to the Bury auction.
- **Stack:** a table of players bond SKR on keeping their phones down for a window of ORE rounds.
  Finishers share 80% of what the others forfeited; 20% goes to the Bury auction.
- **Gift a Rig:** SOL escrowed for a wallet, or for whoever holds a given Seeker Genesis Token.
- **Bury auction:** forfeited SKR is sold for ORE in a descending-price auction with no oracle, and
  that ORE goes straight through ORE's own `bury` instruction.

All four are in the program and tested ([docs/SKR.md](docs/SKR.md)). In the app, the Focus Bond is
built; the Stack and Gift screens are not yet.

## What is built

| Part | State |
|---|---|
| [`programs/heads-down`](programs/heads-down/README.md) | The on-chain program (Pinocchio, `no_std`). 32 instructions. 171 tests, 138 of them on a fork of live mainnet ORE with the real secp256r1 precompile. Contract: [INTERFACE.md](programs/heads-down/INTERFACE.md) v1.3 with machine-checked golden vectors. Deployed on mainnet on 10 October 2026 |
| [`crates/sgt-verify`](crates/sgt-verify/README.md) | In-program Seeker Genesis Token verifier, tested on real mainnet SGTs and on a forgery that passes weaker checks |
| [`crates/p256-introspect`](crates/p256-introspect/README.md) | secp256r1 precompile introspection and Android Keystore signature helpers |
| [`crank`](crank/README.md) | The permissionless crank: takes the phones' heartbeats, lands digs, BREAK and FREEZE, Stack check-ins. 213 tests, plus 14 against the real program on the fork and one end to end on a local validator. Running on Railway against mainnet since 10 October 2026 |
| [`registrar`](registrar/README.md) | Verifies Android Key Attestation chains and issues the voucher the program checks. 116 tests, 19 of them on real attestation chains. Running on Railway against mainnet |
| [`services/indexer`](services/indexer/README.md) | Chain data into Postgres, a public read API, the morning haul. 477 tests. Running on Railway against mainnet |
| [`dashboard`](dashboard/README.md) | Public numbers, each one recomputable from the chain. [Running on Railway](https://dashboard-production-b80c.up.railway.app) on the indexer's mainnet data |
| [`android`](android/README.md) | The app: Kotlin and Compose, Quick Settings tile, foreground shift service, accelerometer-only face-down detection (no gyroscope needed), Keystore rig key, morning reveal, clock-out, taking SOL back and closing the rig, widget. 855 JVM unit tests. One short shift on one physical phone, on mainnet, on 10 October 2026 |
| [`ml`](ml/foreman/README.md) | The on-device models: a pickup-or-bump classifier and a shift planner (trained on synthetic data so far), and a cost forecaster that lost to the simple on-chain rule and only advises |

**Live on mainnet since 10 October 2026.** The program is deployed at
[`HDn4vgLWFLLdexKEwfZwRHjWtizNvdqFteLbMsE67F9p`](https://solscan.io/account/HDn4vgLWFLLdexKEwfZwRHjWtizNvdqFteLbMsE67F9p)
(slot 455,359,196, built from commit `d67a1a40c2a0`; the 190,048 bytes on-chain are exactly that
build). Its Config is initialized and its Executor PDA holds its float. The crank, the registrar,
the indexer and the dashboard run on Railway against it. [docs/MAINNET.md](docs/MAINNET.md) is the
record: every address and transaction, what it cost, what went wrong and how to check each
statement yourself; the deploy's receipts are in [deploy/receipts/mainnet/](deploy/receipts/mainnet/).
One key, held by the founder, can upgrade the program at once: no multisig and no delay. There
has been no third-party audit.

**What one phone did.** The same evening one rig, the founder's own phone, ran one short shift. The
phone is a Redmi 14C (Android 16, HyperOS 3, accelerometer only); its rig key lives in the phone's
TEE and was attested by the live registrar, and every wallet step was signed in Jupiter Mobile.
[The first dig](https://solscan.io/tx/25nBRCP6GExrKG4EDszGrKfWN9yYgJMopgnawTGi2Z9XNGj6UCtZgE6HFJN7rJXvu2C3VMjhh7bpXZnQg8DVzovY)
is one transaction: the phone's P-256 heartbeat checked by the secp256r1 precompile, `heads_down`'s
`dig`, and ORE's `deploy` inside it. Five rounds were dug at 0.001 SOL each, which was the shift's
whole budget of 0.005 SOL. Twice the crank sent a heartbeat a second time, and both times the
program skipped the rig with `StaleHeartbeat`. Unlocking the phone landed a phone-signed BREAK, the
clock-out sealed the shift, and "Take it back" returned 0.00502704 SOL from the ORE Automation,
the amount the screen had stated, to the lamport.

What that run was not: it was a debug build with a demo policy that raised the cost ceiling to
1.0 SOL per ORE. ORE's own cost figure was 0.690 to 0.704 SOL per ORE during the shift, above the
app's default of 0.53, so the default build would have dug nothing. No ORE came out of the four
rounds that are settled, and the fifth is not settled (below). The first clock-in armed a shift
that a second clock-in sealed about five minutes later with no round dug in it: the crank had
accepted no heartbeat yet, and why that shift ended before a heartbeat reached the crank was not
investigated. The crank's first dig landed after its round had ended and was skipped; three of
the crank's settings were changed on the running service that evening
([docs/MAINNET.md](docs/MAINNET.md#what-the-first-night-found)).

**Not done yet, in plain words.** There are no users: the one rig on mainnet is the founder's.
Not run on a phone: a whole night under HyperOS, the haul reveal at the alarm, a dig refused
because the cost gate is shut, Close rig, Unfreeze, Claim, the Focus Bond, the Seeker tier, and
any wallet other than Jupiter's (Solflare, Phantom and Seed Vault have not been tried). Close rig
and a clock-in over the tombstone it leaves have run only on an Android 14 emulator against a
local fork of mainnet, signed in Solana Mobile's test wallet
(`scripts/devstack/emulator-smoke.sh --wallet`), where the amounts that came back matched the
screen to the lamport. On that emulator the test wallet showed the name and icon of the published
identity page; on the phone, Jupiter's wallet showed "Could not verify request" on its connect
prompt and still connected and signed. No SKR instruction has been sent on mainnet, and the
BuryVault account does not exist there yet. The app has no screens yet for Stack or Gift, and its
clock-out cannot buy ORE yet. There is no release signing key and no APK to download: the
registrar accepts only the founder's debug-signed build. With one rig the crank pays more in fees
than the program reimburses: that evening each dig cost it 3,053 to 4,082 lamports net, and each
skipped attempt and the BREAK their whole fee: 60,473 lamports net over nine transactions.
Whether the 4,480,400 lamports that ORE holds in the wallet's Miner account can ever be taken
back is not known. The last round dug, 435,228, had not been checkpointed at 20:44 UTC on
10 October, so what it returns is not known either; ORE forfeits what a round returns if nobody
checkpoints it within about a day
([docs/ORE.md](docs/ORE.md), F8; [docs/MAINNET.md](docs/MAINNET.md#what-it-cost)).

## Security

- Every key's worst case is written down in [docs/THREAT_MODEL.md](docs/THREAT_MODEL.md). Its
  "As built" table says which of the listed protections exist today and which do not.
- Before deploying we went through the whole system looking for holes:
  [docs/SECURITY_REVIEW.md](docs/SECURITY_REVIEW.md) lists what was found, what was fixed (with
  the test for each fix that has one) and what is still open.
- To report a vulnerability, use GitHub's private "Report a vulnerability" advisory on this
  repository.

## Run it

```bash
# The program, on a fork of live mainnet ORE (fetches ORE's bytecode and accounts once)
cd programs/heads-down && bash scripts/test.sh

# Everything end to end on your machine, without a phone:
# local fork of mainnet, crank, indexer; clock-in, dig, lift, replay refused
scripts/devstack/smoke.sh

# The app
cd android && ./gradlew test :app:assembleDebug

# The real app in the loop, on an Android emulator against that local stack:
# setup, Keystore key, heartbeat, on-chain dig, pickup, BREAK; with --wallet <fakewallet.apk> also
# clock-in, clock-out, taking SOL back and closing the rig, signed by a wallet app (docs/DEVSTACK.md)
scripts/devstack/up.sh && scripts/devstack/emulator-smoke.sh
```

Each directory's README has its own instructions. [docs/DEVSTACK.md](docs/DEVSTACK.md) explains
the local stack and [docs/DEPLOY.md](docs/DEPLOY.md) the mainnet runbook.

## More

- [docs/MAINNET.md](docs/MAINNET.md): what is deployed on mainnet, the first shift on a phone, what it cost and how to check it
- [docs/SPEC.md](docs/SPEC.md): the product and technical spec
- [docs/ECONOMICS.md](docs/ECONOMICS.md): what mining costs, and the wording we hold ourselves to
- [docs/ORE.md](docs/ORE.md): how the integration with ORE works, with source references
- [docs/PRIVACY.md](docs/PRIVACY.md): what leaves the phone (heartbeats) and what never does (sensor data)
- [docs/research/](docs/research/): the research behind choosing this project
- [buildplan.md](buildplan.md): the build plan

## License

Apache-2.0, as the Cargo manifests of the program, the crank, the registrar and the two crates
declare. A LICENSE file is still to be added.
