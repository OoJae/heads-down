# DEPLOY: Heads Down on mainnet

The runbook for putting `heads_down` on mainnet and running its services on Railway: keys,
funding, the preflight, the deploy, `initialize_config`, the Executor float, the Railway
services, monitoring, rollback, upgrades, the move to a Squads multisig, and what it costs.
Everything here was rehearsed end to end on a local mainnet fork (section 12).

Contents: [Founder checklist](#founder-checklist) ·
[1. What runs where](#1-what-runs-where) · [2. Keys](#2-keys) · [3. Funding](#3-funding) ·
[4. Parameters and why](#4-parameters-and-why) · [5. Helius](#5-helius) ·
[6. Preflight](#6-preflight) · [7. Deploy](#7-deploy) · [8. initialize_config](#8-initialize_config) ·
[9. Executor float](#9-the-executor-float) · [10. Railway](#10-railway) ·
[11. Monitoring and alerts](#11-monitoring-and-alerts) · [12. Dry run](#12-the-dry-run) ·
[13. Rollback and incidents](#13-rollback-and-incidents) · [14. Upgrades](#14-upgrades-with-a-fresh-buffer) ·
[15. Squads](#15-squads-upgrade-authority-and-governance) · [16. Costs](#16-costs-per-month) ·
[17. Files and secrets](#17-files-and-secrets)

## Founder checklist

In order. Until step 5 no transaction is signed by these keys.

1. **Helius key.** Create a Helius account (the free plan is enough to start, section 5). Put one line in
   `~/.config/heads-down/mainnet/helius.env`: `HELIUS_API_KEY=<key>`, then
   `chmod 600 ~/.config/heads-down/mainnet/helius.env`. Never paste the key anywhere else.
2. **Back up the keys.** `~/.config/heads-down/` holds the only copies (no seed phrases were
   ever shown). Make an encrypted backup (for example an encrypted disk image or
   `age`/`gpg`) of `heads_down-program-keypair.json` and `mainnet/` and store it offline.
3. **Fund the keys** (section 3; amounts read from mainnet's rent on 2026-10-03):

   | Key | Public key | Send |
   |---|---|---|
   | deployer | `9DSVM862oJrstiPmmQmgqXb7AkuXrKJtYgd1fwbeqeeW` | **1.04 SOL** (needs 1.036316560) |
   | crank fee payer | `5Xec1ZUwXcB2ZGeWqqBHrxaHT4WQrGVUgH9xmqgC1kzk` | **0.05 SOL** |
   | governance | `37u9LWbPrzQFkfL6oXGoSfq9souRggVtGvYszRXHezXN` | **0.01 SOL** |
   | registrar | `9deCPaA6iML39zQw4mptBeHRkm7DE6oVWGijdA9c2zgo` | 0 (never pays fees) |

   Total **1.10 SOL** of the ~2 SOL budget; keep the rest for the first upgrade's temporary
   buffer (section 14) and top-ups. About 0.03 SOL of the deployer's share is never spent: the
   Solana CLI only has to see it there (section 3), and it is still in the deployer afterwards.
4. **Preflight**: `scripts/mainnet/preflight.sh` must end with `GO`.
5. **Deploy**: `scripts/mainnet/deploy.sh` (type the confirmation), then commit the receipt it
   writes under `deploy/receipts/mainnet/`.
6. **Initialize**: `scripts/mainnet/init-config.sh` (type the confirmation), commit its receipt,
   and check `scripts/mainnet/governance.sh show`.
7. **Railway**: create the project and the five services (section 10), in order: Postgres,
   indexer, dashboard, registrar, crank. Set the variables from the matrix; attach volumes to
   the crank and the registrar.
8. **Monitoring**: add the uptime checks and balance alerts of section 11.
9. **Within the first week**: create the Squads multisig with a 72 h time lock and move the
   upgrade authority to its vault (section 15). Decide how governance moves (section 15).

## 1. What runs where

| Piece | Where | Controlled by |
|---|---|---|
| `heads_down` program `HDn4vgLWFLLdexKEwfZwRHjWtizNvdqFteLbMsE67F9p` | mainnet, upgradeable loader | upgrade authority: `deployer.json`, then a Squads vault, then none |
| Config PDA `inzDn4ogmXbx9YDAKDHkfwJHy1jhsaWxGQvricDAEmW` | mainnet | `Config.governance` (`governance.json`): pause at once, other changes after 72 h |
| Executor PDA `By3vJvQUsCLexnv7VqHuEhtZZCmpmjZjfhxvqCnWPkge` | mainnet, System-owned, no data | nobody: the program pays ORE's CHECKPOINT_FEE and crank reimbursements from it; no withdraw path |
| `hd-crank` | Railway, `deploy/railway/crank` | `crank-payer.json` (fees only; liveness only) |
| registrar | Railway, `deploy/railway/registrar` | `registrar.json` (= `Config.registrar`; vouchers only) |
| indexer + public API | Railway, `deploy/railway/indexer` | no key; reads the chain |
| dashboard | Railway, `deploy/railway/dashboard` | no key; static site |
| Postgres | Railway PostgreSQL template | Railway |
| RPC | Helius (`mainnet.helius-rpc.com`) | `HELIUS_API_KEY` |

The scripts, all in `scripts/mainnet/` (each has `--help`):

| Script | Does | Sends transactions |
|---|---|---|
| `keys.sh` | creates missing keys, prints public keys and the funding per key | no |
| `preflight.sh` | GO / NO-GO (local + read-only chain checks) | no |
| `deploy.sh` | build, preflight, deploy (`fresh` / `upgrade` / `buffer`), verify, receipt | yes (confirmation on mainnet) |
| `init-config.sh` | `initialize_config` + Executor float; re-run = top-up | yes (confirmation on mainnet) |
| `governance.sh` | `show`, `pause`, `unpause`, `propose`, `apply` | yes except `show` |
| `solana.sh` | any Agave CLI command against the cluster, key kept off the command line | depends |
| `dry-run.sh` | the whole runbook on an isolated local fork | local only |

Every script takes `--cluster mainnet|localnet` (default mainnet) and `--keys-dir DIR`. With
`--cluster localnet` it targets the dev stack and refuses the mainnet key directory. The
Rust half is `hd-devstack` (`scripts/devstack/tool`), whose `--cluster` guard checks the RPC's
genesis hash and host before reading or signing anything.

## 2. Keys

`scripts/mainnet/keys.sh` creates any missing key and never overwrites one. Directory
`~/.config/heads-down/mainnet` is mode 700; every file is mode 600. It prints public keys only.

| File | Public key (mainnet) | Role |
|---|---|---|
| `../heads_down-program-keypair.json` | `HDn4vgLWFLLdexKEwfZwRHjWtizNvdqFteLbMsE67F9p` | signs the first deploy only (it *is* the program address); read, never copied |
| `deployer.json` | `9DSVM862oJrstiPmmQmgqXb7AkuXrKJtYgd1fwbeqeeW` | fee payer of the deploy and of `initialize_config`; upgrade authority until the Squads move |
| `crank-payer.json` | `5Xec1ZUwXcB2ZGeWqqBHrxaHT4WQrGVUgH9xmqgC1kzk` | `hd-crank` fee payer, a hot key on Railway; reimbursed `crank_fee` per real dig |
| `governance.json` | `37u9LWbPrzQFkfL6oXGoSfq9souRggVtGvYszRXHezXN` | `Config.governance`: signs `propose_config` |
| `registrar.json` | `9deCPaA6iML39zQw4mptBeHRkm7DE6oVWGijdA9c2zgo` | `Config.registrar`: Ed25519 voucher key, made by `hd-registrar keygen` (Solana CLI JSON) |
| `registrar-session-secret` | (secret) | the registrar's HMAC session key (`HD_SESSION_SECRET`) |
| `buffer-<commit>.json` | per deploy | the deploy buffer, so a failed deploy resumes and the CLI never prints a recovery phrase |
| `helius.env` | (secret) | `HELIUS_API_KEY=…`, written by you |

Who can do what with each key, and the worst case if it leaks, is in
[THREAT_MODEL.md](THREAT_MODEL.md) section 5. In short: the crank key and the registrar key
cannot move user funds; governance can pause, change `crank_fee` (≤ `executor_fee`), the
registrar and `bury_bps` after 72 h, and nothing else; the upgrade authority is the full
program authority, which is why it moves to a time-locked multisig (section 15).

**Governance is fixed at `initialize_config` in program v1.1.** `propose_config` changes the
registrar, `crank_fee`, `bury_bps` and `paused`, but not `governance`. Either pass the Squads
vault as `--governance` to `init-config.sh` (then every pause needs a multisig vote), or start
with `governance.json` (an immediate pause from one laptop) and add a governance-rotation path
in the first program upgrade (section 15).

## 3. Funding

From `keys.sh` / `hd-devstack funding` against mainnet on 2026-10-03 (mainnet's rent is
**5,080 lamports per byte** today, so `getMinimumBalanceForRentExemption(0)` = 650,240; the
scripts always read it from the cluster):

| Key | Needs (lamports) | SOL | For |
|---|---|---|---|
| deployer | 1,036,316,560 | **1.036316560** | ProgramData for max-len 196,608 (0.999647480) + Program account (0.000833120) + Config (0.001950720) + Executor float (0.001450240) + fee budget (0.032435000, see below) |
| crank fee payer | 50,000,000 | **0.050000000** | lookup-table rent (0.0026 SOL + 0.00065 per rig) and fee float; digs refund `crank_fee` |
| governance | 10,000,000 | **0.010000000** | `propose_config` fees: a pause must never fail for lack of SOL |
| registrar | 0 | 0 | signs off-chain only |
| **Total** | 1,096,316,560 | **1.096316560** | |

**The fee budget is what the Solana CLI wants to see, not what a deploy costs.**
`solana program deploy` refuses to start unless the payer holds the rent plus the CLI's own fee
estimate, and with a priority fee it prices each of its roughly 200 transactions at the 1.4M
compute-unit maximum (it simulates the real limit only afterwards). For today's build that
estimate is 0.02901 SOL. The scripts budget the same sum for a program of `--max-len`:
`(ceil(196,608 / 900) + 4) x (5,000 + 1,400,000 x 0.1)` = 0.032335 SOL, plus 0.0001 SOL for
`init-config`. A deploy and init actually spend about **0.0011 SOL** in fees (1,075,916 lamports
in the dry run, section 12), so about **0.031 SOL is still in the deployer afterwards**. An
earlier version of this page budgeted 0.005 SOL and asked for 1.01 SOL: with exactly that, the
CLI stopped before sending anything ("insufficient funds for spend + fee"). `dry-run.sh --tight`
now funds every key with exactly the amounts above, and it has to pass.

The deploy also needs the program **buffer** (0.966282040 SOL for today's 190,048-byte build)
for a few minutes: `DeployWithMaxDataLen` drains the buffer back into the payer before it pays
for the ProgramData, so the peak is the larger of the two, not their sum. `preflight.sh`
checks the deployer's balance against exactly this. What stays out of the deployer for good:
ProgramData rent (recoverable only by closing the program), the Program account, the Config,
the Executor float and the fees.

Later, not at launch: each upgrade needs a temporary buffer of `(37 + 128 + build size) x 5,080`
lamports (0.97 SOL for today's build) plus the same fee budget, and the buffer's rent is
refunded to the spill account when the upgrade executes.
Today's build fills 97% of the 196,608-byte `--max-len`: an upgrade that grows the program by
more than about 6.5 KB needs `solana program extend` first (5,080 lamports per added byte).

## 4. Parameters and why

| Parameter | Value | Why |
|---|---|---|
| `--max-len` | **196,608** (192 KiB) | 1.76x the 111,600-byte v1.1 build: 85,008 bytes (76%) of headroom for the SKR instructions in development (Stack, Focus Bond, Gift, Bury) without an extend. Rent 0.9996 SOL. 2x (223,200) would cost 1.1347 SOL and 256 KiB 1.3326 SOL, leaving too little of the budget for the first upgrade's temporary buffer. If the SKR build outgrows it, `solana program deploy` extends the ProgramData automatically during the upgrade (the payer funds the extra rent). |
| `executor_fee` | **10,000** lamports | **Immutable** (no instruction changes it), and every rig's Automation must use exactly this Discretionary fee (`dig` skips any other value). It must cover the worst measured crank cost with room for congestion: crank/README.md measures 5,500 (v1, 11 rigs) to 7,550 (legacy, 2 rigs) lamports per fresh-heartbeat dig, 6,723 end to end; ORE's own executor charges 7,000 and a sampled third-party one 12,000. 10,000 is ECONOMICS.md's figure and lets `crank_fee` rise to 10,000 under congestion without touching user Automations. At 0.001 SOL per dig it is 1% of the per-round spend. |
| `crank_fee` | **7,000** lamports | Covers a v0 + lookup-table batch (5 rigs: 5,000 secp256r1 + 1,000 signature share + priority ≈ 6,050-6,723). `crank_fee ≤ executor_fee` is enforced by the program. The 3,000 lamports per dig it leaves behind accrue in the Executor, which only ever pays ORE's CHECKPOINT_FEE top-ups and reimbursements, so the float grows with use. Raise it (timelocked) with `governance.sh propose --crank-fee N` if priority fees stay high. |
| `bury_bps` | **0** | v1.1 has no bury path (`INTERFACE.md` §10). |
| `ore_layout_hash` | `cc9b3521…48aa91` | `sha256(heads_down::ore::LAYOUT_PREIMAGE)`, computed by the program crate inside the tool; the program refuses any other value. |
| Executor float | **rent-exempt(0) + 100,000 + 100 x crank_fee** = 1,450,240 lamports on mainnet | rent so the PDA exists, the program's own reserve (10 x CHECKPOINT_FEE, which reimbursements never touch), and 100 reimbursements of slack. A dig pays the Executor 10,000 before the program reimburses 7,000, so reimbursements are self-funding; the slack absorbs late third-party checkpoints that take 10,000 each. |
| priority fee | 100,000 micro-lamports/CU | deploy and admin transactions; the whole deploy's priority fees stay around 0.001 SOL. |
| `--max-sign-attempts` | 20 | about 20 minutes of re-signing before a deploy gives up (it is resumable anyway). |

The program is built with `programs/heads-down/scripts/build.sh` (`--features mainnet`: the real
SGT anchors) by `cargo-build-sbf` 4.1.0, which produces **SBPF v0**. SIMD-0500 ("disable
deployment of SBPF v0, v1 and v2") is **inactive** on mainnet today; `preflight.sh` checks it on
every run and stops if it becomes active or pending (then the build must move to `--arch v3`,
whose feature is active on mainnet since slot 428,976,000).

## 5. Helius

- **Plan.** Start on the **free plan ($0)**. Its 1M credits a month cover the deploy, the
  first rigs and the demo, not steady use: the crank's watcher and dig loop and the indexer's
  30 s polling use roughly 3-8M standard calls a month at launch volume, which is the
  **Developer plan ($49/month, 10M credits, 50 requests/s, staked sends)**. Nothing breaks when
  the free credits run out except that RPC calls start failing: the crank stops digging (rigs
  go cold, nothing is spent) until the month rolls over or the plan is raised. Watch the credit
  meter in the Helius dashboard; the indexer's poll interval is the biggest lever.
  WebSocket `accountSubscribe` (the crank's watcher) works on both plans.
- **One key, one place on disk:** `~/.config/heads-down/mainnet/helius.env`, mode 600, one line
  `HELIUS_API_KEY=<key>`. The scripts parse it (it is never executed), keep it in a
  non-exported variable, and hand it to child processes through a prefix assignment (the
  environment, never `argv`) or a mode-600 Solana CLI config in a private temp directory that is
  deleted on exit. All output is filtered through a redactor; `set -x` is refused.
- **On Railway:** one project-level **shared, sealed** variable `HELIUS_API_KEY`, referenced
  by services as `${{shared.HELIUS_API_KEY}}`: the crank substitutes it into
  `{HELIUS_API_KEY}` placeholders itself; the indexer and registrar get full URLs built from
  the reference (`https://mainnet.helius-rpc.com/?api-key=${{shared.HELIUS_API_KEY}}`). No
  service logs the URL (only scheme and host).
- **Never** in the APK, the repo, a receipt, a log, or a command line. If it leaks: rotate it in
  the Helius dashboard, update `helius.env` and the shared variable, redeploy the services.

## 6. Preflight

```bash
scripts/mainnet/preflight.sh            # read-only; exit 0 = GO
```

| Check | Fails when |
|---|---|
| `helius.env` | missing, not mode 600, or no well-formed `HELIUS_API_KEY=` (the value is never shown) |
| key dir / key files | the directory is not 700 or a key is not 600, or a key is missing |
| program keypair | it is not `HDn4vg…` |
| deployer | it is not `9DSVM862…` (override with `HD_EXPECTED_DEPLOYER` only on purpose) |
| git | (warning) local changes: `deploy.sh` refuses a dirty tree on mainnet |
| cluster | the RPC's genesis hash is not mainnet's `5eykt4Us…`, or the RPC is a loopback fork |
| program .so | not an SBF ELF; its sha256 and the solana-verify hash are printed |
| max-len | smaller than the build or above 10 MiB |
| SIMD-0500 | active or pending for an SBPF v0-v2 build (or SBPFv3 not enabled for a v3 build) |
| program account | something already lives at `HDn4vg…` (fresh mode) |
| deployer balance | below ProgramData(max-len) + Program + fees + Config + float, all from `getMinimumBalanceForRentExemption` |
| ORE upgrade slot | ORE's ProgramData slot is not 452,682,055 (docs/ORE.md) |
| ORE program hash | ORE's bytes are not the verified build `9dbd2e0d…` (commit `48c203bd`) |
| ORE singletons | Board/Treasury/Config/Round owner, size or discriminator differ from 40/105, 48/104, 232/101, 952/109 |
| ORE user accounts | a sampled Automation or Miner from recent ORE transactions is not 160/100 or 752/103 |
| init params | `executor_fee` 0 or `crank_fee > executor_fee` |

Warnings (crank or governance key underfunded, no Automation sampled) do not block.

Real run against mainnet on 2026-10-03 (before `helius.env` existed and before funding, so
the chain checks used the public RPC and the verdict is NO-GO for exactly those two reasons):

```text
heads_down preflight: mainnet, mode fresh, 2026-10-03T22:13:47Z
FAIL  helius.env             missing: ~/.config/heads-down/mainnet/helius.env (one line HELIUS_API_KEY=...; chmod 600)
PASS  key dir                ~/.config/heads-down/mainnet (700)
PASS  deployer.json          9DSVM862oJrstiPmmQmgqXb7AkuXrKJtYgd1fwbeqeeW (600)
PASS  crank-payer.json       5Xec1ZUwXcB2ZGeWqqBHrxaHT4WQrGVUgH9xmqgC1kzk (600)
PASS  governance.json        37u9LWbPrzQFkfL6oXGoSfq9souRggVtGvYszRXHezXN (600)
PASS  registrar.json         9deCPaA6iML39zQw4mptBeHRkm7DE6oVWGijdA9c2zgo (600)
PASS  session secret         present (600; value not shown)
PASS  program keypair        HDn4vgLWFLLdexKEwfZwRHjWtizNvdqFteLbMsE67F9p (~/.config/heads-down/heads_down-program-keypair.json, 600)
PASS  deployer               9DSVM862oJrstiPmmQmgqXb7AkuXrKJtYgd1fwbeqeeW
PASS  git                    commit 710d661c7ce06336edc045cda6e62c97065cc0b5, clean tree
PASS  program .so            190048 bytes, sha256 07dd870a879faac20d4932f297da3b50719c9b900761409250c8841cc7b8527d (programs/heads-down/target/deploy/heads_down.so)

chain checks via https://api.mainnet-beta.solana.com (public; helius.env missing or invalid)
PASS  cluster                mainnet via https://api.mainnet-beta.solana.com (genesis 5eykt4UsFv8P8NJdTREpY1vzqKqZKvdpKuc147dw2N9d)
PASS  program id             HDn4vgLWFLLdexKEwfZwRHjWtizNvdqFteLbMsE67F9p (keypair = program crate = crank)
PASS  program .so            190048 bytes, sha256 07dd870a879faac20d4932f297da3b50719c9b900761409250c8841cc7b8527d, program hash 0154706c62cb7bb6aacf463501e49b016b87809ed7193fcae08afc4e3f891b58, SBPF v3
PASS  max-len                196608 bytes: 6560 bytes (3%) of headroom over this build
PASS  SIMD-0500              inactive ; build is SBPF v3 and SBPFv3 deployment is active since slot 428976000
PASS  program account        nothing deployed yet: fresh deploy
INFO  rent                   ProgramData(196653) 0.999647480 SOL, Program 0.000833120 SOL, buffer(190085) 0.966282040 SOL, Config 0.001950720 SOL, Executor rent-exempt(0) 0.000650240 SOL
INFO  deploy cost            1.032815600 SOL: ProgramData 0.999647480 + Program 0.000833120 + fees 0.032335000 (the 0.966282040 SOL buffer is refunded into the ProgramData payment)
INFO  init cost              0.003500960 SOL: Config 0.001950720 + Executor float 0.001450240 (target 1450240 = rent 650240 + reserve 100000 + 100 x crank_fee 7000; holds 0) + fees 0.000100000
FAIL  deployer balance       9DSVM862oJrstiPmmQmgqXb7AkuXrKJtYgd1fwbeqeeW holds 0.000000000 SOL < 1.036316560 SOL needed: send at least 1.036316560 SOL
PASS  ORE upgrade slot       452682055 = pin 452682055
PASS  ORE program hash       9dbd2e0d232563f0e2b3eae89bf7d6f55d483c464863adb4f46d117f427ca695 = verify.osec.io 48c203bd
PASS  ORE singletons         Board 40/105, Treasury 48/104, Config 232/101, Round 952/109 (round 427295)
PASS  ORE user accounts      1 Automations (160/100) and 3 Miners (752/103) from recent ORE transactions match
PASS  init params            executor_fee 10000 (immutable), crank_fee 7000 (<= executor_fee; +3000 per dig accrues to the Executor)
INFO  ore_layout_hash        cc9b3521eaa022a05fd9c38f745b8844a740ca9e3243a91f1bbda66a3448aa91 = sha256(heads_down::ore::LAYOUT_PREIMAGE)
INFO  heads_down Config      not initialized (init-config.sh creates it)
INFO  Executor PDA           By3vJvQUsCLexnv7VqHuEhtZZCmpmjZjfhxvqCnWPkge holds 0 lamports
WARN  crank-payer            5Xec1ZUwXcB2ZGeWqqBHrxaHT4WQrGVUgH9xmqgC1kzk holds 0.000000000 SOL (recommended 0.050000000): fund 0.050000000 SOL before starting it
WARN  governance             37u9LWbPrzQFkfL6oXGoSfq9souRggVtGvYszRXHezXN holds 0.000000000 SOL (recommended 0.010000000): fund 0.010000000 SOL before starting it
PASS  registrar              9deCPaA6iML39zQw4mptBeHRkm7DE6oVWGijdA9c2zgo holds 0.000000000 SOL (recommended 0.000000000)

NO-GO: 1 local failure(s), chain checks FAILED (see FAIL lines above). Nothing was sent.
```

With `helius.env` in place and the deployer funded, those two lines turn PASS and the
verdict is GO. Everything ORE-related passes against mainnet today, with the pin moved to the
build ORE deployed on 2026-10-02. Earlier the same day this run answered NO-GO on the two ORE
lines, which is how we learned of that upgrade ([ORE.md](ORE.md), section 1).

## 7. Deploy

```bash
scripts/mainnet/deploy.sh               # fresh deploy, max-len 196608
```

1. Refuses a dirty tree (tracked changes, or untracked files under `programs/heads-down` or
   `crates`), and records the commit.
2. Builds with `bash programs/heads-down/scripts/build.sh` and records the `.so` sha256 and the
   toolchain (`solana --version`, `cargo-build-sbf --version`).
3. Runs `preflight.sh`; anything but GO stops here.
4. Prints the plan and asks you to type `deploy heads_down to mainnet`.
5. Runs, through a private CLI config holding the Helius URL:
   ```text
   solana program deploy --use-rpc --program-id ~/.config/heads-down/heads_down-program-keypair.json \
     --upgrade-authority deployer.json --keypair deployer.json --buffer buffer-<commit>.json \
     --max-len 196608 --with-compute-unit-price 100000 --max-sign-attempts 20 \
     --commitment confirmed --output json-compact programs/heads-down/target/deploy/heads_down.so
   ```
6. `hd-devstack verify-deploy` reads the ProgramData back and checks that it holds the build
   byte for byte (the rest of max-len zero), that the upgrade authority is the deployer, that
   the ProgramData is exactly 45 + max-len bytes, and that the deploy transaction succeeded;
   then it writes `deploy/receipts/mainnet/<UTC>-fresh-<commit>.json` (schema in
   [deploy/receipts/README.md](../deploy/receipts/README.md)). **Commit that file.**

If it fails part way, nothing is lost: re-run it at the same commit and the CLI resumes the
same buffer; or refund the buffer with
`scripts/mainnet/solana.sh -- program close <BUFFER> --recipient 9DSVM862oJrstiPmmQmgqXb7AkuXrKJtYgd1fwbeqeeW`.

Anyone can check the deploy: `solana-verify get-program-hash HDn4vg… -um` equals the
receipt's `onchain.program_hash`, and rebuilding the recorded commit with the recorded
toolchain reproduces `so.sha256`.

## 8. initialize_config

```bash
scripts/mainnet/init-config.sh          # then: scripts/mainnet/governance.sh show
```

`initialize_config` (tag 0, `vectors/instructions.json`) with accounts
`[upgrade authority (signer, pays rent), Config PDA, ProgramData 3jjGZ8EE8DJcRag9eTFMMxStNo55PHLa5xVPktW52WPZ, System]`.
The tool first checks that `deployer.json` is the upgrade authority recorded in ProgramData
(the program checks it too), prints the plan (governance and registrar public keys, the fees,
`bury_bps`, the layout hash and the Config rent), asks you to type
`initialize heads_down config`, sends it with a simulated compute limit and a priority fee,
reads the Config back and compares every field, then funds the Executor float. The receipt
goes to `deploy/receipts/mainnet/<UTC>-init.json`. **Commit it.**

The instruction builder is tested byte for byte against the program's golden vectors
(`scripts/devstack/tool/src/hd.rs`, `initialize_config`, `propose_config`, `apply_config`).

## 9. The Executor float

- **What drains it:** only ORE's CHECKPOINT_FEE top-ups (10,000 lamports, when a third party
  checkpointed a Miner late; the crank checkpoints early to avoid that) and `crank_fee`
  reimbursements, which never take it below rent-exempt(0) + 100,000.
- **What fills it:** every dug rig-round pays `executor_fee` (10,000) in and reimburses
  `crank_fee` (7,000) out: +3,000 per dig.
- **Alert** when the Executor holds less than **rent-exempt(0) + 100,000 + 10 x crank_fee
  = 820,240 lamports** on mainnet: below rent-exempt(0) + 100,000 + 7,000 reimbursements stop
  (digs still count), and below rent-exempt(0) + 10,000 rigs whose Miner needs a checkpoint fee
  are skipped with `ExecutorUnderfunded` (31).
- **Top up** with `scripts/mainnet/init-config.sh` (it only transfers the difference to the
  target, default 1,450,240; pass `--executor-float <lamports>` for more), or any plain
  transfer: `scripts/mainnet/solana.sh -- transfer By3vJvQUsCLexnv7VqHuEhtZZCmpmjZjfhxvqCnWPkge 0.01`.
  Anyone may top it up; nobody can withdraw from it.

## 10. Railway

Five services in one Railway project ("heads-down"), all from the GitHub repo
`OoJae/heads-down`, branch `main`. Every Dockerfile builds from the repository root.

### 10.1 Project and shared variable

1. New project → **Empty project**, name it `heads-down`.
2. Project **Settings → Shared Variables**: add `HELIUS_API_KEY` = your key, then **seal** it
   (sealed values are never shown again and never leave Railway; they are not copied to PR
   environments).

### 10.2 Postgres

**+ New → Database → Add PostgreSQL**, keep the name `Postgres`. Details:
[deploy/railway/postgres/README.md](../deploy/railway/postgres/README.md).

### 10.3 Each service

For each of `indexer`, `dashboard`, `registrar`, `crank` (in that order):

1. **+ New → GitHub Repo → `OoJae/heads-down`**. Rename the service (`indexer`, …).
2. **Settings → Source**: Root Directory `/` (default). **Railway Config File:**
   `/deploy/railway/<service>/railway.json` (an absolute path; it sets the builder, the
   Dockerfile, the watch paths, the healthcheck and the restart policy). Leave **Custom Start
   Command** empty: each image's ENTRYPOINT/CMD is the start command, and a custom one would
   replace the crank's and registrar's entrypoint, so their key would never reach them.
3. **Variables:** from `deploy/railway/<service>/.env.example` (matrix below). Add secrets as
   **sealed**.
4. **Volume** (crank and registrar only; `railway.json` refuses to deploy without it): right-click
   the service → **Attach volume**, mount path `/data`, 1 GB.
5. **Networking → Generate Domain** (target port = the service's `PORT`), or add your own domain.
6. Deploy. The deployment turns healthy when the healthcheck answers 2xx: crank `/healthz`
   (chain view fresh, breaker closed), registrar `/healthz`, indexer `/v1/health`, dashboard
   `/healthz`.

Order matters only because of references: the dashboard is built with the indexer's domain,
the indexer needs Postgres, and the crank should start after the program is initialized (it
runs without a Config but has nothing to dig).

### 10.4 Variable matrix

`S` = sealed secret, `R` = Railway reference, `-` = plain.

| Variable | crank | registrar | indexer | dashboard | Value |
|---|---|---|---|---|---|
| `HELIUS_API_KEY` | R | | | | `${{shared.HELIUS_API_KEY}}` |
| `HD_CRANK_KEYPAIR_JSON` | S | | | | contents of `crank-payer.json` |
| `PORT` | 8787 | 8080 | 8080 | 8080 | the domain's target port |
| `RUST_LOG` | `info,hyper=warn,reqwest=warn` | `info` | | | |
| `HD_REGISTRAR_KEYPAIR_JSON` | | S | | | contents of `registrar.json` |
| `HD_SESSION_SECRET` | | S | | | contents of `registrar-session-secret` |
| `HD_RPC_URL` | | S/R | | | `https://mainnet.helius-rpc.com/?api-key=${{shared.HELIUS_API_KEY}}` |
| `HD_APP_RELEASE_CERT_SHA256` | | - | | | SHA-256 of the release signing cert (`apksigner verify --print-certs`) |
| `HD_SIWS_DOMAIN` / `HD_SIWS_URI` | | - | | | **required, no default.** The host and URL of the site the app identifies itself with: the app build's `-Pheadsdown.identityUri` (default `https://oojae.github.io/heads-down`, so `oojae.github.io` and that URL). It must be a site the team controls |
| `HD_TRUSTED_PROXY_HOPS` | | `1` | | | Railway's edge appends the client to X-Forwarded-For |
| `HD_NONCE_STORE` / `HD_TRANSPARENCY_LOG` | | image defaults | | | `/data/nonces.db`, `/data/attestations.jsonl` |
| `DATABASE_URL` | | | R | | `${{Postgres.DATABASE_URL}}` |
| `RPC_URL` | | | S/R | | `https://mainnet.helius-rpc.com/?api-key=${{shared.HELIUS_API_KEY}}` |
| `INDEXER_DATASET` | | | `mainnet` | | |
| `TEAM_CRANKERS` | | | - | | `5Xec1ZUwXcB2ZGeWqqBHrxaHT4WQrGVUgH9xmqgC1kzk` |
| `CORS_ORIGIN` | | | - | | `https://<dashboard domain>` |
| `HOST` | | | `0.0.0.0` | | |
| `NEXT_PUBLIC_HD_API_BASE` | | | | - (build) | `https://${{indexer.RAILWAY_PUBLIC_DOMAIN}}` |

The full lists, with every optional knob and its default, are the four `.env.example` files.

**The crank's client address.** `deploy/railway/crank/crank.toml` sets `trust_real_ip = true`:
Railway's edge writes the client's address in `X-Real-IP`, and every per-address limit of the
intake is keyed on it. Check it after the first deploy, from any machine:

```bash
curl https://<crank domain>/whoami                                # {"ip":"<your own address>"}
curl -H 'X-Real-IP: 203.0.113.9' https://<crank domain>/whoami    # must still be your own address
```

If the second call answers `203.0.113.9`, the edge is not overwriting the header: set
`trust_real_ip = false` (the limits then key on the edge's address, which is coarse but cannot
be chosen by a client) and report it.

**Gate-closed nights.** The same file sets `[record] gate_closed_rigs = true`: on a night when
ORE's cost never drops under a rig's ceiling nothing is dug, and without a record the shift would
seal with no dark round (no streak day; a Focus Bond would go to the Bury lot). The crank pays for
those records itself, at most 1,000,000 lamports an hour.

### 10.7 The app build that talks to this deployment

A mainnet build must name every endpoint and the site it identifies itself with; the Gradle
configuration fails otherwise (there is no default service host, because a default would be a
name somebody else can register):

```bash
cd android
./gradlew :app:assembleDebug -Pheadsdown.cluster=mainnet \
  -Pheadsdown.rpcUrl=https://<rpc proxy, no key in the URL> \
  -Pheadsdown.crankUrl=wss://<crank domain>/ws \
  -Pheadsdown.registrarUrl=https://<registrar domain> \
  -Pheadsdown.indexerUrl=https://<indexer domain> \
  -Pheadsdown.identityUri=https://oojae.github.io/heads-down
```

`identityUri` is what a wallet shows next to every signing prompt and, through its host, the
Sign In With Solana domain; the registrar's `HD_SIWS_DOMAIN` / `HD_SIWS_URI` must match it. The
default is the project's GitHub Pages address, which only the repository owner's GitHub account
can publish to. The page and its `favicon.ico` (wallets fetch it for the prompt) are in `site/`.
To publish them, once:

```bash
git subtree push --prefix site origin gh-pages     # the site becomes the root of the gh-pages branch
# GitHub → Settings → Pages → Build and deployment → Deploy from a branch → gh-pages, / (root)
curl -sI https://oojae.github.io/heads-down/favicon.ico | head -1     # HTTP/2 200 a minute later
```

The
RPC URL must not carry an API key: the public `https://api.mainnet-beta.solana.com` works for a
demo build; a keyed provider needs a proxy that adds the key server-side.

### 10.5 How the keys reach the processes

The crank and registrar images start their entrypoint as root for two things only: the
keypair JSON from the sealed variable is checked for shape (never printed), written to a
`0600` file in a private `0700` directory on tmpfs (`/dev/shm`), and removed from the
environment; and the Railway volume at `/data` is handed to uid 10001 (Railway mounts volumes
as root). The service then starts as uid 10001 with `setpriv --no-new-privs`, and the key file
is deleted as soon as the service listens (both load their key before binding). Railway's
`SIGTERM` is forwarded (the crank shuts down on `SIGINT`, the registrar on `SIGTERM`).

### 10.6 Checks without Docker

```bash
python3 deploy/railway/check.py     # COPY sources, digest pins, no VOLUME, schema keys, healthcheck routes, empty secrets, hadolint, shellcheck
```

The Docker build steps were also replayed natively (section 12).

## 11. Monitoring and alerts

Railway's healthcheck runs only when a deployment starts, so it is not monitoring.

| What | How | Alert when |
|---|---|---|
| crank alive and pinned | uptime monitor (Better Stack, UptimeRobot, Grafana Cloud synthetic) on `https://<crank>/healthz` every minute | non-200 for 3 minutes. 503 with a `breaker` reason means an ORE account failed its pin or ORE was upgraded: nothing digs until an operator restarts after the fork suites pass (docs/ORE.md §8) |
| crank economics | scrape `https://<crank>/metrics` (public, no addresses) | `hd_crank_executor_lamports` < 820,240; `hd_crank_cranker_lamports` < 10,000,000 (0.01 SOL); `hd_crank_circuit_breaker_tripped` = 1; `hd_crank_digs_landed_total` flat for an hour while `hd_crank_heartbeats_accepted_total` grows; `hd_crank_txs_failed_total` rising |
| indexer | uptime on `/v1/health`; check `data.lastSlot` and `data.problems` | down, `lastSlot` older than 10 minutes, any decode problem |
| registrar | uptime on `/healthz`; `status_list.age_secs` | down, `status: degraded`, status list older than 24 h (it fails closed at 48 h) |
| dashboard | uptime on `/healthz` | down |
| Railway | project → Settings → Webhooks (deploy failed / crashed), and a usage limit | any |
| Helius | dashboard usage alerts | 80% of monthly credits |
| on chain | a Helius webhook (or the metrics above) on the Executor PDA and the crank payer | balances as above; any `propose_config` on the Config |

## 12. The dry run

`scripts/mainnet/dry-run.sh` rehearses sections 2 to 9, 13 and 14 on a local mainnet fork,
isolated from any other dev stack: its own home (`~/.local/share/heads-down/dryrun`), keys
(`~/.config/heads-down/dryrun/`) and ports (RPC 38899, crank 38787, indexer 38788). The fork is
`scripts/devstack/up.sh --no-deploy`: mainnet's ORE programs and accounts and mainnet's feature
set, but no `heads_down`, so `deploy.sh` runs its real fresh path with the real program id,
`--max-len`, buffer and receipt. Then the existing `scripts/devstack/smoke.sh` runs against the
result, the program is upgraded in place (`--mode upgrade`), a buffer is written and handed to
a stand-in vault (`--mode buffer`), a pause is drilled, and the stack is stopped.

Real output of `scripts/mainnet/dry-run.sh --tight` on 2026-10-03 at commit `14b0c3b06110`
(the preflight tables, the key table, the deploy plans and the service logs are cut; paths
shortened). The fork carries the ORE build deployed on 2026-10-02, and `--tight` gives every key
exactly what section 3 asks for at the local validator's rent, then tops the deployer up before
each later drill with what that drill needs (the temporary buffer's rent and the fee budget):

```text
== 0. local stack without heads_down (up.sh --no-deploy, RPC :38899, home ~/.local/share/heads-down/dryrun) ==
[devstack] dumping mainnet ORE state into ~/.local/share/heads-down/dryrun/fixtures (RPC host: api.mainnet-beta.solana.com)
[devstack] fixtures: ORE round 427290, 14 files (sha256_ore.so=b16a10029a35e709956cfe42c4dc51cf2c59522f477eba10816a40bcaa7dd5bd)
[devstack] --no-deploy: heads_down is NOT deployed; next: scripts/mainnet/deploy.sh --cluster localnet, then init-config.sh
[devstack] stack is up (test-validator)

== 1. keys.sh (throwaway deploy keys in ~/.config/heads-down/dryrun/keys) ==
funded k5mgognA8cahpUoZQp2e45DgfU3XHkRZUQqyR73mzDt: balance 1.40753572 SOL
funded 8c3KKqwEPRDKpjqqpi9k4rrSnPu2LbVFn1cPudBHqJMp: balance 0.05 SOL
funded HuTaKR8uXLGSqDGhyiPSuoJcGrU14TUzYBcjkvqGmj7R: balance 0.01 SOL

== 2. preflight.sh --cluster localnet (read-only) ==
PASS  deployer balance       k5mgognA8cahpUoZQp2e45DgfU3XHkRZUQqyR73mzDt holds 1.407535720 SOL >= 1.407535720 SOL needed
PASS  ORE program hash       9dbd2e0d232563f0e2b3eae89bf7d6f55d483c464863adb4f46d117f427ca695: the fork runs mainnet's ORE bytes
GO: localnet preflight passed (0 local warnings; chain details in ~/.local/share/heads-down/dryrun/deploy/preflight-localnet-20261003T220816Z.json)

== 3. deploy.sh --cluster localnet (build, preflight, deploy, verify, receipt) ==
[deploy] built 190048 bytes, sha256 07dd870a879faac20d4932f297da3b50719c9b900761409250c8841cc7b8527d (cargo-build-sbf 4.1.0 platform-tools v1.54 rustc 1.89.0)
deploy plan (localnet, mode fresh)
  program id         HDn4vgLWFLLdexKEwfZwRHjWtizNvdqFteLbMsE67F9p
  max-len            196608 bytes
{"programId":"HDn4vgLWFLLdexKEwfZwRHjWtizNvdqFteLbMsE67F9p","signature":"Dhwss7UfWotYTXnXfUTym6MKsVoQi9FxWYePnEpqZpUhhCkiXNTY1APffAD7h5pHDnfdkTUDSJXLeegmNbgcNy1"}
verify: ProgramData 3jjGZ8EE8DJcRag9eTFMMxStNo55PHLa5xVPktW52WPZ holds exactly programs/heads-down/target/deploy/heads_down.so (190048 bytes, program hash 0154706c62cb7bb6aacf463501e49b016b87809ed7193fcae08afc4e3f891b58)
verify: receipt deploy/receipts/localnet/20261003T220816Z-fresh-14b0c3b06110.json

== 4. init-config.sh --cluster localnet (initialize_config + Executor float) ==
init: initialize_config tx ETQaEAGrHjjSLUbz1NVi1pF8KbXq3A8qjUimd8XZbEvSNvaN5p44wfVjF28Q9uJ16SdtDWqngWQ5NU6qRj8RbDV -> Config inzDn4ogmXbx9YDAKDHkfwJHy1jhsaWxGQvricDAEmW (executor_fee 10000, crank_fee 7000, governance HuTaKR8uXLGSqDGhyiPSuoJcGrU14TUzYBcjkvqGmj7R, registrar YyyL6FBuH816aWaKoWzWZ8VmecgvmwbMHG1J8zwwZS3); read back and verified
init: funded the Executor PDA By3vJvQUsCLexnv7VqHuEhtZZCmpmjZjfhxvqCnWPkge with 1690880 lamports to 1690880 (tx rBgwWDeiec6hKUNHVH3mtPGSSwshv8v1vpdwjGKsbZcnqgsMm8gPt2hX1cpRZHZrS3Le9jtRmbLdWqNC8M14Si2)

== 5. scripts/devstack/smoke.sh against the deployed + initialized program ==
[smoke +   0s] stack up: crank healthy, indexer healthy; heads_down Config executor_fee 10000 crank_fee 7000; ORE round 427290 (ema 715235766 lamports/ORE)
[smoke +   1s] clock-in (automate + register_rig + set_caps + arm_shift, 1 tx XLniLNGQrfff1w257buNS7e4qQJ4S85V3xoHTTBw5fLnyv2AkVQfgGZ1YAUJRM6jQWSKsbA5Ho2fcWdR9VkCUL7): rig 9i3Tdf7iPvxmPa7VsoF66LJ6KNS26hZr8peLup291JF3 Armed, shift 1, 0.001 SOL digs on 10 split tiles
[smoke +   1s] phone face-down: heartbeat #1 for round 427290 accepted by the crank (207 slots left)
[smoke + 106s] CRANK DUG round 427290: tx 3iHEV11s7n3cVMop4xsCQdNkRUNFMKnHPnm5Q9Djw3xgB996ntWgDZrQjxSXyFGdR6XdpmJ6Lw6qwZfkYHhzTE5d; RigDug 1000000 lamports on 10 squares (mask 0x00386ae); rig Down, hb_counter 1, lease [427290, 427290]; Automation balance 48990000 (fee 10000)
[smoke + 106s] PHONE LIFTED after round 427290: no more heartbeats
[smoke + 151s] ORE round 427291 is now current (reset by the round driver)
[smoke + 152s] hostile crank REPLAYS heartbeat #1 in round 427291: tx 3k75Rf4hsGrqiPgpp7RNmwcaz5h6TssWDrVHVt45Q5Rz524pAtBvATFNVmKY2eUCLipYm7SUgsJkwfMo37qjTDzw -> RigSkipped(StaleHeartbeat)
[smoke + 152s] hostile crank REUSES the old lease in round 427291: tx oABFi6ck3oMGZkb6b1NP9haSi8MLPJaY3zv3dE8r4hmHovMtNJzLsMjVc6JWjXKNvLTgi8nxxmQuvCxSAm3zwYi -> RigSkipped(LeaseExpired)
[smoke + 284s] round 427291 closed (board at 427291): crank did NOT dig the lifted rig (last_dug_round 427290, lease_to 427290); hd_crank_digs_landed_total 1
[smoke + 284s] INDEXER recorded RigDug: rig "9i3Tdf7iPvxmPa7VsoF66LJ6KNS26hZr8peLup291JF3" round "427290" lamports "1000000" squares 10 (dataset localnet)
[smoke + 284s] indexer health: 7 txs ingested through slot 296, 0 decode problem kinds
SMOKE PASSED: face-down -> dug (round 427290); lifted -> no dig, replay and lease reuse refused on-chain (round 427291).

== 6. upgrade drill: deploy.sh --mode upgrade (same commit, fresh buffer), then solana.sh program show ==
funded k5mgognA8cahpUoZQp2e45DgfU3XHkRZUQqyR73mzDt: balance 1.387576564 SOL
{"programId":"HDn4vgLWFLLdexKEwfZwRHjWtizNvdqFteLbMsE67F9p","signature":"2riradqjeXqTSgYkg91qtnbCF9qhP7KCgm2fjeiZthfMnKSHFEvDbr9QJyiUGgXuNTVz3jxHar8vnE7DjgQXS7rt"}
verify: ProgramData 3jjGZ8EE8DJcRag9eTFMMxStNo55PHLa5xVPktW52WPZ holds exactly programs/heads-down/target/deploy/heads_down.so (190048 bytes, program hash 0154706c62cb7bb6aacf463501e49b016b87809ed7193fcae08afc4e3f891b58)
verify: receipt deploy/receipts/localnet/20261003T221313Z-upgrade-14b0c3b06110.json
Last Deployed In Slot: 558

== 7. Squads drill: deploy.sh --mode buffer, handing the buffer to governance.json's key as a stand-in vault ==
funded k5mgognA8cahpUoZQp2e45DgfU3XHkRZUQqyR73mzDt: balance 1.418853149 SOL
{"buffer":"6Rj23VBAvb7htNxVa6UWbeQHfSz6VLHwXHXPSUGTkBAt"}
[deploy] handing the buffer to HuTaKR8uXLGSqDGhyiPSuoJcGrU14TUzYBcjkvqGmj7R
verify: buffer 6Rj23VBAvb7htNxVa6UWbeQHfSz6VLHwXHXPSUGTkBAt holds exactly programs/heads-down/target/deploy/heads_down.so (190048 bytes, program hash 0154706c62cb7bb6aacf463501e49b016b87809ed7193fcae08afc4e3f891b58)
verify: receipt deploy/receipts/localnet/20261003T221324Z-buffer-14b0c3b06110.json

== 8. rollback drill: governance.sh pause (immediate), then show ==
propose_config tx H4TScjVLtARnojyqLW1tFDUCFX1uY4qJkKw8waf6JApXjmerm3cqEBMwwnDnurVnECQNLMbtXx534fSm3Q78Wxk: pending until slot 864578 (paused now true, pending paused 1)
heads_down      Config inzDn4ogmXbx9YDAKDHkfwJHy1jhsaWxGQvricDAEmW executor_fee 10000 crank_fee 7000 paused true
pending         registrar YyyL6FBuH816aWaKoWzWZ8VmecgvmwbMHG1J8zwwZS3 crank_fee 7000 bury_bps 0 paused 1; apply_config from slot 864578 (864000 slots to go)
Executor PDA    By3vJvQUsCLexnv7VqHuEhtZZCmpmjZjfhxvqCnWPkge 1693880 lamports

== stop the stack ==
[devstack] stopped indexer
[devstack] stopped crank
[devstack] stopped driver
[devstack] stopped validator

summary
  PASS  0 up.sh --no-deploy: fork, driver, crank, indexer up; no heads_down
  PASS  1 keys.sh: keys created (600 in a 700 dir), every key holds exactly what the funding table asks for (deployer 1407535720 lamports)
  PASS  2 preflight.sh: GO
  PASS  3 deploy.sh: fresh deploy of HDn4vg… with max-len 196608, bytes verified, receipt written
  PASS  4 init-config.sh: Config created and read back, Executor float funded, receipt written
  PASS  5 smoke.sh: SMOKE PASSED
  PASS  6 deploy.sh --mode upgrade: upgraded in place from a fresh buffer, bytes verified, receipt written
  PASS  7 deploy.sh --mode buffer: buffer written and handed over, bytes verified, receipt written
  PASS  8 governance.sh pause: Config.paused = 1 immediately; un-pause waits for the timelock
DRY RUN PASSED (log ~/.local/share/heads-down/dryrun/dry-run.log)
```

**What it cost.** The deployer started with 1,407,535,720 lamports and had 31,359,084 left after
the fresh deploy and `init-config`: ProgramData 1,369,595,760 + Program 1,141,440 + Config
2,672,640 + Executor float 1,690,880 + **1,075,916 of fees**. The buffer's rent came back, as
section 3 says. The upgrade cost 1,058,415 lamports of fees. The Executor PDA ended at 1,693,880
lamports: the 1,690,880 float, +10,000 fee in, -7,000 reimbursed to the crank.

Note the local validator's rent is the historical 6,960 lamports per byte (mainnet's is now
5,080), which is why the amounts differ from section 3: every amount is read from the cluster it
applies to. The fees and what is left over are the same on both.

### Docker-free checks of the Railway images (2026-10-01)

Docker is not available on the build machine, so every image was checked another way:

| Check | Result |
|---|---|
| `python3 deploy/railway/check.py` (COPY sources, digest pins, no VOLUME, schema keys, healthcheck routes, empty secrets, hadolint 2.15.1, shellcheck 0.11.0) | 64 checks, all passed |
| the four `railway.json` against `https://railway.com/railway.schema.json` (jsonschema, Draft 2020-12) | all valid |
| crank: both RUN steps replayed in a scratch copy of exactly the COPY set (`crates/p256-introspect`, crank manifests, `crank/src`), `RUSTUP_TOOLCHAIN=1.97.1`, `--locked` | dependency layer 1 m 22 s, crank 27 s; `hd-crank --version` = `hd-crank 0.1.0` |
| registrar: the same for `registrar/{Cargo.toml,Cargo.lock,src,roots}` with Rust 1.97.1 and `--locked` (identical in both registrar Dockerfiles) | dependency layer 1 m 07 s, service 14 s; `cargo test`: 105 passed |
| indexer: `pnpm install --frozen-lockfile --prod` (pnpm 11.1.2), then `node src/main.ts serve` with the image's environment and an in-memory database | devDependencies skipped (`@electric-sql`, `@solana`, `pg` only); `GET /v1/health` 200 |
| dashboard: `pnpm install --frozen-lockfile`, `pnpm build` with `NEXT_PUBLIC_HD_API_BASE=https://indexer.example.org` | 8 static routes, 56 files; the API base is inlined |
| dashboard `server.mjs` on that export | `/healthz` 200, `/` 200, `/cohorts` 308 → `/cohorts/` 200, unknown page 404 (export's page), `/%2e%2e/%2e%2e/etc/passwd` 404, `/.env` 404, `POST /` 405, `HEAD /` 200, hashed assets `immutable`, HTML `must-revalidate`, `nosniff`/`DENY`/HSTS headers, listening on `::` |
| crank entrypoint (macOS, non-root branch, paths redirected to scratch) | no key → exit 1; malformed key → exit 1 and the value is not echoed; real key → hd-crank started, key file gone once it listened, `/healthz` answered, no key bytes in the log; SIGTERM → hd-crank shut down on SIGINT, exit 0 |
| registrar entrypoint (same) | both key forms set → refused; real key → `/registrar` reports the test key, key file gone, `/healthz` 200; SIGTERM → `hd-registrar stopped` |
| `deploy/railway/crank/crank.toml` under hd-crank's strict loader, `hd-crank check` against mainnet (read-only) | parsed; ORE upgrade slot = pin (450,496,378 at the time), breaker closed |

`setpriv` and a tmpfs `/dev/shm` exist only on Linux, so the root branch of the entrypoints
(dropping to uid 10001) runs for the first time on Railway: check the first deploy's log for
`key file removed` and for the process running as uid 10001 (Railway shell: `ps -o user,cmd`).

## 13. Rollback and incidents

| Situation | Do | Effect |
|---|---|---|
| Any doubt about digs (a bug, an ORE change, bad crank behaviour) | `scripts/mainnet/governance.sh pause` | **Immediate**: from the next transaction `dig` fails with `Paused` (18). Nothing else stops: users can still break, freeze, end shifts, close rigs and Revoke in ORE. No SOL moves. |
| Resume | `governance.sh unpause`, then after 864,000 slots (≥ 72 h; ~96 h at 400 ms) `governance.sh apply` | Un-pausing always waits for the timelock. A new proposal replaces a pending one and restarts the clock. |
| Program bug | pause, fix, then upgrade with a fresh buffer (section 14) | users' funds stay in their own ORE Automations throughout |
| Crank misbehaving or compromised | stop the Railway service; rename `crank-payer.json` aside, run `keys.sh` (it creates a new one), move the old key's SOL with `solana.sh --keypair <old file> -- transfer <new pubkey> ALL --allow-unfunded-recipient`, update `HD_CRANK_KEYPAIR_JSON` and `TEAM_CRANKERS`, redeploy | liveness only: the worst case is no digs |
| Registrar key compromised | stop the registrar; rename `registrar.json` aside and run `keys.sh` for a new one; `governance.sh propose --registrar <new>`; after 72 h `apply`, then start the registrar with the new key. Until `apply` the chain still trusts the old key (rigs can register unattested meanwhile). For a routine rotation, run old and new side by side until `apply` | attestation levels only (THREAT_MODEL K4) |
| Helius key leaked | rotate in Helius, update `helius.env` and the shared variable, redeploy | |
| Deploy failed part way | re-run `deploy.sh` (resumes), or close the buffer (section 7) | |
| ORE upgraded its program (it did on 2026-09-25 and 2026-10-02) | Nothing to do at once: the crank's breaker has already stopped digs, and `preflight.sh` answers NO-GO. Then: read the diff between the commits verify.osec.io names; refresh the fixtures (`programs/heads-down/tests/fixtures/fetch-fixtures.sh`, `scripts/devstack/up.sh --refresh-fixtures`); run the program's and the crank's fork suites and `dry-run.sh --tight`; move the pin (`crank/src/ore.rs`, both `crank.toml`, `ORE_PROGRAM_HASH` in `scripts/devstack/tool/src/ops.rs`, `docs/ORE.md`); redeploy the crank | no digs, so nothing is mined and nothing is spent, until the pin is moved |

## 14. Upgrades with a fresh buffer

Before any upgrade: run the program's fork suite (`programs/heads-down/scripts/test.sh`) and the
crank's against live ORE, and regenerate `vectors/` if the interface changed; consumers must
match first.

- **While `deployer.json` is the upgrade authority:**
  `scripts/mainnet/deploy.sh --mode upgrade` (builds, preflights, writes a fresh per-commit
  buffer, upgrades in place, auto-extends the ProgramData if the build outgrew max-len,
  verifies the bytes, writes `…-upgrade-<commit>.json`).
- **Once a Squads vault is the authority:**
  1. `scripts/mainnet/deploy.sh --mode buffer --buffer-authority <VAULT>` writes a fresh buffer,
     hands it to the vault and records `…-buffer-<commit>.json` with the buffer's program hash.
  2. Every signer checks the buffer: `solana-verify get-buffer-hash <BUFFER> -um` must equal the
     receipt's `onchain.program_hash`, which equals a local rebuild of the recorded commit.
  3. In Squads: **Programs → `HDn4vg…` → Upgrade**, buffer `<BUFFER>`, spill account (where the
     buffer's rent returns) the deployer. Approve to the threshold; the vault's time lock
     (72 h) runs; then execute.
  4. `scripts/mainnet/solana.sh -- program show HDn4vgLWFLLdexKEwfZwRHjWtizNvdqFteLbMsE67F9p`
     shows the new last-deploy slot; record it in the receipt's commit message.
- A build larger than max-len needs the ProgramData extended first (`solana program extend`;
  mainnet enforces a minimum extension size, SIMD-0431). The CLI does it during a direct
  upgrade; for a Squads upgrade, extend before proposing.

## 15. Squads: upgrade authority and governance

**Upgrade authority → Squads multisig with a time lock** (THREAT_MODEL K5):

1. Create a Squads v4 multisig at app.squads.so: members on separate devices (for example the
   founder's hardware wallet, a phone wallet, and a trusted co-signer), threshold 2-of-3, and
   **time lock 259,200 s (72 h)** in the multisig settings. Note the **vault address** (index 0).
2. Rehearse on devnet with a throwaway program: transfer, then upgrade through the vault.
3. Transfer (one transaction, signed by `deployer.json`):
   ```bash
   scripts/mainnet/solana.sh -- program set-upgrade-authority HDn4vgLWFLLdexKEwfZwRHjWtizNvdqFteLbMsE67F9p \
     --new-upgrade-authority <VAULT> --skip-new-upgrade-authority-signer-check
   ```
   The vault is a PDA and cannot co-sign, hence the flag: **check the address character by
   character**, because a typo makes the program un-upgradeable forever.
4. Verify: `scripts/mainnet/solana.sh -- program show HDn4vg…` prints `Authority: <VAULT>`.
5. After the audit: revoke through the vault (`set-upgrade-authority --final`) for an
   immutable v1. docs/ORE.md §8 explains why v1 then relies on the layout pins alone.

**Governance.** The multisig's 72 h time lock would also delay the pause, which must be
immediate, so governance does not move to the same vault as the upgrade authority:

- Launch with `governance.json` (a single key the founder keeps offline except for an
  emergency): the pause is one command, and every other governance change already waits 72 h
  on-chain, which users can watch.
- Since v1.3 `Config.governance` can be rotated (`propose_governance`, then `accept_governance`
  by the successor after the same 72 h; `scripts/mainnet/governance.sh`). When there are
  co-signers, move it to a **second** Squads multisig **without** a time lock (2-of-3): a pause
  then takes two signatures but no waiting, and the program's own 72 h timelock still covers
  every other change.

**Until the steps above are done, say so.** At launch the upgrade authority is one keypair and
program upgrades have no delay. [THREAT_MODEL.md](THREAT_MODEL.md) ("As built") and
[SECURITY_REVIEW.md](SECURITY_REVIEW.md) state this, and they must keep doing so until the
vault holds the authority.

## 16. Costs per month

| Item | Plan | Monthly |
|---|---|---|
| Railway | Hobby ($5 including $5 of usage) | about **$8-15**: crank ~0.1 GB RAM, registrar ~0.05 GB, indexer ~0.2 GB, dashboard ~0.05 GB, Postgres ~0.25 GB at $10/GB-month, light CPU at $20/vCPU-month, volumes ~3 GB at $0.15/GB |
| Helius | Free to start | **$0**, and **$49** (Developer) once steady use outgrows 1M credits a month |
| SOL: crank fees | | ~0: each real dig reimburses 7,000 against ~6,050-6,723 spent; failed attempts and congestion are the cost (budget 0.01-0.05 SOL a month) |
| SOL: lookup tables | one-time | 0.0026 SOL + 0.00065 SOL per rig, recoverable by closing the tables |
| SOL: Executor | | grows by 3,000 lamports per dig; top-ups only after late third-party checkpoints |
| Domain (optional) | | ~$1 (not needed: the app identifies itself with the project's GitHub Pages address) |
| **Total** | | **about $8-15/month** on the free Helius plan, about $60 with the Developer plan, plus small SOL top-ups |

One-time: the deploy and initialization, **1.10 SOL** across the three keys (section 3). Of it,
0.9996 SOL of ProgramData rent stays locked while the program exists, about 0.005 SOL goes into
the Program account, the Config, the Executor float and fees for good, and the rest stays in the
keys it was sent to.

## 17. Files and secrets

| Path | Committed | Holds |
|---|---|---|
| `scripts/mainnet/*.sh` | yes | the runbook's scripts (no secrets; `.gitignore` refuses `*.json`, `*.env`) |
| `deploy/railway/<service>/` | yes | Dockerfile, `railway.json`, `.env.example` (names only), entrypoints, `crank.toml` (placeholders only) |
| `deploy/receipts/mainnet/` | **yes, after each change** | public receipts |
| `deploy/receipts/localnet/` | no (ignored) | dry-run receipts |
| `~/.config/heads-down/` | never | every key and `helius.env` (dir 700, files 600) |
| `~/.local/share/heads-down/deploy/` | never | build logs, preflight JSON, CLI output |
| `~/.local/share/heads-down/dryrun/` | never | the dry run's fork, logs and state |
