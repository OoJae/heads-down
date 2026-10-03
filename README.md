# Heads Down: your phone's night shift, powered by ORE

> Put your phone face-down and it mines [ORE](https://ore.com). Pick it up and the rig goes cold.
> Nothing but your phone's own hardware key can switch it on: not a server, not us.

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
| [`programs/heads-down`](programs/heads-down/README.md) | The on-chain program (Pinocchio, `no_std`). 32 instructions. 171 tests, 138 of them on a fork of live mainnet ORE with the real secp256r1 precompile. Contract: [INTERFACE.md](programs/heads-down/INTERFACE.md) v1.3 with machine-checked golden vectors |
| [`crates/sgt-verify`](crates/sgt-verify/README.md) | In-program Seeker Genesis Token verifier, tested on real mainnet SGTs and on a forgery that passes weaker checks |
| [`crates/p256-introspect`](crates/p256-introspect/README.md) | secp256r1 precompile introspection and Android Keystore signature helpers |
| [`crank`](crank/README.md) | The permissionless crank: takes the phones' heartbeats, lands digs, BREAK and FREEZE, Stack check-ins. 168 tests, plus 14 against the real program on the fork |
| [`registrar`](registrar/README.md) | Verifies Android Key Attestation chains and issues the voucher the program checks. 105 tests on real attestation chains |
| [`services/indexer`](services/indexer/README.md) | Chain data into Postgres, a public read API, the morning haul. 347 tests |
| [`dashboard`](dashboard/README.md) | Public numbers, each one recomputable from the chain |
| [`android`](android/README.md) | The app: Kotlin and Compose, Quick Settings tile, foreground shift service, accelerometer-only face-down detection (no gyroscope needed), Keystore rig key, morning reveal, clock-out, widget. 805 JVM unit tests |
| [`ml`](ml/foreman/README.md) | The on-device models: a pickup-or-bump classifier and a shift planner (trained on synthetic data so far), and a cost forecaster that lost to the simple on-chain rule and only advises |

**Not done yet, in plain words.** Nothing is deployed on mainnet. The app has not run on a real
phone (everything above is unit tests, a fork of mainnet and a phone-less end-to-end run). The
app has no screens yet for Stack, Gift, Revoke or Close rig, and its clock-out cannot buy ORE yet.
There has been no third-party audit.

## Security

- Every key's worst case is written down in [docs/THREAT_MODEL.md](docs/THREAT_MODEL.md). Its
  "As built" table says which of the listed protections exist today and which do not.
- Before deploying we went through the whole system looking for holes:
  [docs/SECURITY_REVIEW.md](docs/SECURITY_REVIEW.md) lists what was found, what was fixed (each
  fix with a test) and what is still open.
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
```

Each directory's README has its own instructions. [docs/DEVSTACK.md](docs/DEVSTACK.md) explains
the local stack and [docs/DEPLOY.md](docs/DEPLOY.md) the mainnet runbook.

## More

- [docs/SPEC.md](docs/SPEC.md): the product and technical spec
- [docs/ECONOMICS.md](docs/ECONOMICS.md): what mining costs, and the wording we hold ourselves to
- [docs/ORE.md](docs/ORE.md): how the integration with ORE works, with source references
- [docs/PRIVACY.md](docs/PRIVACY.md): what leaves the phone (heartbeats) and what never does (sensor data)
- [docs/research/](docs/research/): the research behind choosing this project
- [buildplan.md](buildplan.md): the build plan

## License

MIT (TBD)
