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
2. **Back up the keys, before any SOL is sent.** `~/.config/heads-down/` holds the only copies
   (no seed phrases were ever shown), and `deployer.json` is the only way to get the program's
   rent back (section 3). Make an encrypted backup (for example an encrypted disk image or
   `age`/`gpg`) of `heads_down-program-keypair.json` and `mainnet/`, and keep a copy off this
   machine.
3. **Fund the keys** (section 3; amounts read from mainnet's rent on 2026-10-03):

   | Key | Public key | Send |
   |---|---|---|
   | deployer | `9DSVM862oJrstiPmmQmgqXb7AkuXrKJtYgd1fwbeqeeW` | **1.04 SOL** (needs 1.036316560) |
   | crank fee payer | `5Xec1ZUwXcB2ZGeWqqBHrxaHT4WQrGVUgH9xmqgC1kzk` | **0.05 SOL** |
   | governance | `37u9LWbPrzQFkfL6oXGoSfq9souRggVtGvYszRXHezXN` | **0.01 SOL** |
   | registrar | `9deCPaA6iML39zQw4mptBeHRkm7DE6oVWGijdA9c2zgo` | 0 (never pays fees) |

   Total **1.10 SOL**. About 0.03 SOL of the deployer's share is never spent: the Solana CLI
   only has to see it there, and it is still in the deployer afterwards. Section 3 says what comes
   back and what does not: 0.9996 SOL is locked in the program's rent and 0.0053 SOL is gone for
   good. Two more amounts are not in the table: the **wallet on the phone** needs about 0.04 SOL
   for a week of tests, and a **first upgrade** needs about 0.964 SOL lent to the deployer for a
   few minutes, so the working budget is about 2.07 SOL if an upgrade must stay possible.
4. **Preflight**: `scripts/mainnet/preflight.sh` must end with `GO`.
5. **Deploy**: `scripts/mainnet/deploy.sh` (type the confirmation). It writes the program into a
   buffer at one transaction a second (198 writes for today's build: a little over three minutes on
   the local fork, not timed on mainnet), then deploys with one more transaction. If it stops part
   way, run it again at the same commit: it continues and needs no more SOL for the buffer. Keep
   the Railway crank stopped and the indexer without `RPC_URL` until it is done: they share the
   Helius key's limits. Then commit the receipt it writes under `deploy/receipts/mainnet/`.
6. **Initialize**: `scripts/mainnet/init-config.sh` (type the confirmation), commit its receipt,
   and check `scripts/mainnet/governance.sh show`.
7. **Railway** (section 10): Postgres, the indexer without `RPC_URL`, the dashboard and the
   registrar can run before the deploy, and do. After step 6: set the indexer's `RPC_URL`, then
   start the crank, last, with its fee payer funded.
8. **Monitoring**: add the uptime checks and balance alerts of section 11.
9. **Decide about Squads** (section 15): a multisig with a 72 h time lock protects users against
   a single key, costs 0.1 SOL that does not come back, and ends the one-signature way of
   getting the program's rent back.
10. **Identity page**: after steps 5 and 6, and again after the first run on a phone, update the
    dated status line in `site/index.html`, commit, and publish it again (section 10.7).

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
| `deploy.sh` | build, preflight, write the buffer at a set rate, deploy (`fresh` / `upgrade` / `buffer`), verify, receipt | yes (confirmation on mainnet) |
| `init-config.sh` | `initialize_config` + Executor float; re-run = top-up | yes (confirmation on mainnet) |
| `governance.sh` | `show`, `pause`, `unpause`, `propose`, `apply` | yes except `show` |
| `solana.sh` | any Agave CLI command against the cluster, key kept off the command line | depends |
| `dry-run.sh` | the whole runbook on an isolated local fork | local only |
| `selftest.sh` | checks that need no cluster and no real key: which RPC a script picks, the pins on the funded addresses, argument checks | no |

Every script takes `--cluster mainnet|localnet` (default mainnet) and `--keys-dir DIR`. With
`--cluster localnet` it targets the dev stack and refuses the mainnet key directory. The
Rust half is `hd-devstack` (`scripts/devstack/tool`), whose `--cluster` guard checks the RPC's
genesis hash and host before reading or signing anything.

Every script that talks to the cluster also takes `--public-rpc` (or `HD_PUBLIC_RPC=1`): the
public mainnet RPC although `helius.env` is there. Each says which RPC it uses. `hd-devstack` has
three more commands behind these scripts: `write-buffer` (the paced buffer writer),
`buffer-status` and `fees` (both read-only).

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
| `buffer-<commit>.json` | per deploy | the deploy buffer's key, kept per commit: a deploy that stopped part way continues from it, and the CLI never prints a recovery phrase |
| `helius.env` | (secret) | `HELIUS_API_KEY=…`, written by you |

The three funded addresses are pinned in `scripts/mainnet/lib.sh` (`HD_EXPECTED_DEPLOYER`,
`HD_EXPECTED_CRANK_PAYER`, `HD_EXPECTED_GOVERNANCE`). For mainnet with the default key directory,
`keys.sh` stops before it prints any amount, and `preflight.sh` answers NO-GO, if `deployer.json`,
`crank-payer.json` or `governance.json` derives another address: SOL sent to the pinned address
could not be spent with the file at hand. The pins are not checked for `--cluster localnet` or for
another key directory (`--keys-dir`, `HD_MAINNET_KEYS`); the scripts then say so. After a key is
replaced on purpose, change its pin and the tables in this file.

Who can do what with each key, and the worst case if it leaks, is in
[THREAT_MODEL.md](THREAT_MODEL.md) section 5. In short: the crank key and the registrar key
cannot move user funds; governance can pause, change `crank_fee` (≤ `executor_fee`), the
registrar and `bury_bps` after 72 h, and nothing else; the upgrade authority is the full
program authority, which is why it moves to a time-locked multisig (section 15).

**`Config.governance` is set at `initialize_config`.** `propose_config` changes the registrar,
`crank_fee`, `bury_bps` and `paused`, but not `governance`. Since v1.3 the program can rotate it
(`propose_governance`, then `accept_governance` by the successor after 72 h), but **no script
sends those instructions yet**: `governance.sh` knows `show`, `pause`, `unpause`, `propose` and
`apply`, and the deploy tool has no rotation command. So start with `governance.json` (an
immediate pause from one laptop); a rotation needs the commands added to the tool first
(section 15).

## 3. Funding

From `keys.sh` / `hd-devstack funding` against mainnet on 2026-10-03 (mainnet's rent is
**5,080 lamports per byte** today, so `getMinimumBalanceForRentExemption(0)` = 650,240; the
scripts always read it from the cluster):

| Key | Needs (lamports) | SOL | For |
|---|---|---|---|
| deployer | 1,036,316,560 | **1.036316560** | ProgramData for max-len 196,608 (0.999647480) + Program account (0.000833120) + Config (0.001950720) + Executor float (0.001450240) + fee budget (0.032435000, see below) |
| crank fee payer | 50,000,000 | **0.050000000** | a fee float that is spent in use (below), and lookup-table rent (0.00256 SOL + 0.00065 per rig) when lookup tables are on |
| governance | 10,000,000 | **0.010000000** | `propose_config` fees: a pause must never fail for lack of SOL |
| registrar | 0 | 0 | signs off-chain only |
| **Total** | 1,096,316,560 | **1.096316560** | |

**The fee budget is what the Solana CLI wants to see, not what a deploy costs.**
`solana program deploy` refuses to start unless the payer holds the rent plus the CLI's own fee
estimate, and with a priority fee it prices each of its roughly 200 transactions at the 1.4M
compute-unit maximum (it simulates the real limit only afterwards). For today's build that
estimate is 0.02901 SOL. The scripts budget the same sum for a program of `--max-len`:
`(ceil(196,608 / 900) + 4) x (5,000 + 1,400,000 x 0.1)` = 0.032335 SOL, plus 0.0001 SOL for
`init-config`. A deploy and init actually spend about **0.0011 SOL** in fees (1,076,055 lamports
in the dry run, section 12), so about **0.031 SOL is still in the deployer afterwards**. An
earlier version of this page budgeted 0.005 SOL and asked for 1.01 SOL: with exactly that, the
CLI stopped before sending anything ("insufficient funds for spend + fee"). `dry-run.sh --tight`
now funds every key with exactly the amounts above, and it has to pass.

The buffer is now written by `hd-devstack write-buffer` (section 7), which has no such check and
pays 5,267 lamports a write. The budget is kept so that `deploy.sh --cli-only`, where the CLI
writes the buffer itself, starts with the same funding. A deploy that continues an existing buffer
is budgeted `(chunks still to write + 4) x 145,000` lamports. For a build larger than `--max-len`
preflight sizes the budget for the build. `HD_DEPLOY_FEE_BUDGET=<lamports>` overrides the total:
with the paced writer 3,000,000 lamports were enough for an upgrade on the fork. Raising
`--cu-price` raises the budget in proportion (at 1,000,000 micro-lamports it is 313,315,000
lamports for a first run, computed from the formula, not run), which is friction exactly when a
deploy is stalled: the override is the way around it.

During a fresh deploy the SOL sits in the program **buffer** for a few minutes. The buffer is
created holding the ProgramData rent for max-len (999,647,480 lamports, not the buffer's own rent
of 966,282,040), and `DeployWithMaxDataLen` drains the buffer back into the payer before it pays
for the ProgramData, so the peak is one ProgramData rent, not two. If a deploy stops half way,
that SOL is in the buffer, whose authority is the deployer: section 7 says how the deploy is
resumed or the buffer refunded.

**The first upgrade needs more SOL than a 2 SOL budget leaves.** An upgrade needs a temporary
buffer holding the rent of 45 + build size bytes (966,322,680 lamports for today's 190,048-byte
build) plus the fee budget: about 0.999 SOL in the deployer, which will hold about 0.035 SOL.
That is a top-up of about **0.964 SOL**, and all of it but about 0.001 SOL of fees comes back
when the upgrade executes (the buffer's rent goes to the spill account, the deployer). So the
working budget is about **2.07 SOL** if a bug found on the phone must be fixable on-chain: the
1.10 above, and 0.964 lent to the deployer for a few minutes per upgrade.

Today's build fills 97% of the 196,608-byte `--max-len` (6,560 bytes of headroom). A build that
outgrows it must extend the ProgramData, and mainnet enforces a minimum extension of 10,240 bytes:
52,019,200 lamports, locked like the rest.

### What comes back, and what does not

Where the 1.10 SOL is the day after the deploy and `initialize_config` (1.04 SOL sent to the
deployer; rents read from mainnet on 2026-10-04):

| Where | Lamports | Does it come back? |
|---|---|---|
| ProgramData (45 + 196,608 bytes) | 999,647,480 | **only by closing the program for good** (below) |
| Program account (36 bytes) | 833,120 | never: the loader cannot close a Program account |
| Config (256 bytes) | 1,950,720 | never: no instruction closes it |
| Executor float | 1,450,240 | never: nothing can be withdrawn from the Executor (section 9) |
| deploy and init fees | about 1,076,000 (measured in the dry run) | never |
| left in the deployer | 35,042,524 | yes: a plain transfer |
| crank fee payer | 50,000,000 | what is left of it: it is spent in use (below) |
| governance | 10,000,000 | what is left of it: a pause or a proposal costs about 5,100 to 5,500 lamports |

So of 1.10 SOL: **0.99965 SOL is locked** in the program's rent, **0.0053 SOL (5,309,996
lamports) is gone for good**, and 0.095 SOL is still liquid on day one.

**Getting the locked rent back means closing the program.**
`scripts/mainnet/solana.sh -- program close HDn4vgLWFLLdexKEwfZwRHjWtizNvdqFteLbMsE67F9p --bypass-warning --recipient <wallet>`,
signed by the upgrade authority, returns all 999,647,480 lamports to the recipient. It is
permanent: the program id can never be deployed or invoked again, and the app, the crank, the
indexer and the registrar all pin that id. Everything the program owns is then stuck for ever:
the Config, the Executor's balance, every Rig and tombstone, SeekerSeats, ShiftLogs (also those
whose rent the crank paid), Stack tables and the SKR in their vaults, Focus Bonds and their SKR,
gift escrows and the Bury lot. ORE's own Automations and Miners are untouched: users can still
take their SOL back and claim in ORE. Three consequences:

- The exit exists only while `deployer.json` is the upgrade authority. After a move to a Squads
  vault it takes the multisig's threshold and its time lock; after `--final` it is gone
  (section 15). Whoever holds `deployer.json` can also send the rent anywhere, so that file stays
  offline and backed up.
- Do it only before any outside user holds a rig, a bond, a seat or a gift. The program is
  permissionless and a pause stops only digs, so closing would strand other people's accounts.
- The order: stop the crank; from each wallet clock out, take the SOL back and close the rig;
  close the ShiftLogs 30 days after the last shift and the crank's lookup tables (neither has
  tooling yet); sweep the crank's and governance's balances; close the program last.

**The crank's 0.05 SOL is a fee float, and one phone does not pay for itself.** A dig that
carries one rig's fresh heartbeat costs the crank 10,047 lamports (one transaction signature, one
secp256r1 signature, the priority fee) and the program reimburses `crank_fee` = 7,000: about 3,000
lamports lost per dig, and the default caps allow at most 20 digs a shift. The reimbursement
covers the fee only from three rigs per transaction up (crank/README.md). Never reimbursed: the
heartbeat records on nights when the cost gate stays closed (about 10,000 lamports each, every
third round: about 1,240,000 lamports over an 8-hour night for one rig), BREAK and FREEZE (about
10,100 each), Stack check-ins, and the ShiftLog rent of a shift the crank seals (1,300,480
lamports, which `close_shift_log` returns to the payer after 30 days; nothing sends that
instruction yet). Expect roughly 0.001 to 0.003 SOL a night for one phone.

**The wallet on the phone is a fourth key to fund.** The first clock-in with the default build
moves about 0.029 SOL: 20,200,000 lamports into the wallet's own ORE Automation, and the rent of
the Rig (2,600,960), of ORE's Automation (1,463,040) and of ORE's Miner (4,470,400), plus ORE's
10,000 checkpoint reserve. Each sealed shift then costs 1,300,480 of ShiftLog rent. About 0.04 SOL
covers a week of tests on one phone. What comes back: the Automation's balance and rent through
"Take SOL back", and the Rig's rent less the 812,800 lamports that stay in the tombstone through
"Close my rig". What does not: the tombstone, 10,000 lamports per dug round (7,000 to the crank,
3,000 into the Executor), the SOL placed on squares that did not win, the fees, and the Miner's
rent (ORE's account; whether ORE lets a wallet close it was not checked).

## 4. Parameters and why

| Parameter | Value | Why |
|---|---|---|
| `--max-len` | **196,608** (192 KiB) | Chosen as 1.76x the 111,600-byte v1.1 build, to hold the SKR instructions without an extend. They are in now: today's v1.3 build is 190,048 bytes, which leaves 6,560 bytes (3%). Rent 0.9996 SOL. 2x (223,200) would cost 1.1347 SOL and 256 KiB 1.3326 SOL. If a later build outgrows it, `solana program deploy` extends the ProgramData during the upgrade, in steps of at least 10,240 bytes (52,019,200 lamports each, paid by the deployer and locked like the rest). |
| `executor_fee` | **10,000** lamports | **Immutable** (no instruction changes it), and every rig's Automation must use exactly this Discretionary fee (`dig` skips any other value). It must cover the worst measured crank cost with room for congestion: crank/README.md measures 5,500 (v1, 11 rigs) to 7,550 (legacy, 2 rigs) lamports per fresh-heartbeat dig, 6,723 end to end; ORE's own executor charges 7,000 and a sampled third-party one 12,000. 10,000 is ECONOMICS.md's figure and lets `crank_fee` rise to 10,000 under congestion without touching user Automations. At 0.001 SOL per dig it is 1% of the per-round spend. |
| `crank_fee` | **7,000** lamports | Covers a v0 + lookup-table batch (5 rigs: 5,000 secp256r1 + 1,000 signature share + priority ≈ 6,050-6,723). `crank_fee ≤ executor_fee` is enforced by the program. The 3,000 lamports per dig it leaves behind accrue in the Executor, which only ever pays ORE's CHECKPOINT_FEE top-ups and reimbursements, so the float grows with use. With one rig per transaction the crank pays 10,047 and gets 7,000 back; the fee covers it from three rigs per transaction up (section 3). Raise it (timelocked) with `governance.sh propose --crank-fee N` if priority fees stay high. |
| `bury_bps` | **0** | v1.1 has no bury path (`INTERFACE.md` §10). |
| `ore_layout_hash` | `cc9b3521…48aa91` | `sha256(heads_down::ore::LAYOUT_PREIMAGE)`, computed by the program crate inside the tool; the program refuses any other value. |
| Executor float | **rent-exempt(0) + 100,000 + 100 x crank_fee** = 1,450,240 lamports on mainnet | rent so the PDA exists, the program's own reserve (10 x CHECKPOINT_FEE, which reimbursements never touch), and 100 reimbursements of slack. A dig pays the Executor 10,000 before the program reimburses 7,000, so reimbursements are self-funding; the slack absorbs late third-party checkpoints that take 10,000 each. |
| priority fee | 100,000 micro-lamports/CU | deploy and admin transactions; the whole deploy's priority fees stay around 0.001 SOL. |
| `--write-rate` | **1** transaction a second | Helius' free plan allows one `sendTransaction` a second. The writer waits that long after each write, so it stays under the limit; a plan that allows more can be given more. On the local fork, behind a proxy that lets one `sendTransaction` a second through, about 185 writes took 188 s and none was refused. Not run on mainnet. |
| `--max-sign-attempts` | 20 | only for `deploy.sh --cli-only`, where the CLI writes the buffer itself: 20 signing rounds before it gives up. The default path does not use it: `write-buffer` signs a write again whenever its blockhash expired. |

The program is built with `programs/heads-down/scripts/build.sh` (`--features mainnet`: the real
SGT anchors) by `cargo-build-sbf` 4.1.0, which produces **SBPF v0**. SIMD-0500 ("disable
deployment of SBPF v0, v1 and v2") is **inactive** on mainnet today; `preflight.sh` checks it on
every run and stops if it becomes active or pending (then the build must move to `--arch v3`,
whose feature is active on mainnet since slot 428,976,000).

## 5. Helius

- **What is billed.** Helius counts credits, not calls: 1 for a standard call, **10 for
  `getProgramAccounts`**, 2 per 0.1 MB of WebSocket data. The free plan has 1M credits a month, 10
  requests a second, 5 `getProgramAccounts` a second and **1 `sendTransaction` a second**; the
  Developer plan ($49 a month) has 10M credits, 50 requests and 5 sends a second, and $5 per
  further million. When the credits are used up every call answers HTTP 429 ("max usage reached")
  until the month rolls over: the crank stops digging (rigs go cold, nothing is spent) and the
  indexer stops reading the chain while its API keeps serving what is stored.
- **What the services use**, computed from the code and Helius' published prices (the measured
  runs are in `crank/README.md` and `services/indexer/README.md`; nothing was measured against
  Helius itself):

  | Service | Idle, credits a day | A night with one phone adds |
  |---|---|---|
  | crank, the baked `crank.toml` | about 108,500 (3.3M in 30 days: the free plan lasts about 9 days) | about 50,000 |
  | crank, with the one-phone overrides of `deploy/railway/crank/.env.example` | about 30,500 (0.9M in 30 days) | about 50,000 |
  | indexer at `INGEST_INTERVAL_S=300` | about 2,000 | about 5,000 |
  | indexer at `INGEST_INTERVAL_S=30` | about 12,600 | about 17,000 |
  | registrar | 0 (it uses a keyless RPC, section 10.4) | 0 |

  Before the changes of 2026-10-04 the two services used about 410,000 credits a day idle, and an
  unfunded crank more. So: with the one-phone overrides and the indexer at 300 s, an idle stack
  uses about the whole free plan in a month, and every test night comes on top. **Run the crank
  for test nights and for the recorded demo, and remove its deployment in between**, or put Heads
  Down on a Helius account of its own. Watch the credit meter in the Helius dashboard.
- **Do not share the key with another project.** Credits and rate limits are counted per account,
  so another project on the same key spends the same 1M credits and the same one send a second.
- **The operator scripts are not stuck when the credits are gone:** `--public-rpc` (or
  `HD_PUBLIC_RPC=1`) on `governance.sh`, `solana.sh`, `init-config.sh`, `preflight.sh`, `keys.sh`
  and `deploy.sh` uses the public mainnet RPC although `helius.env` is there, and prints which RPC
  is in use. The pause is then `scripts/mainnet/governance.sh --public-rpc pause`. Reads over the
  public RPC were tried; sending over it was not.
- **One key, one place on disk:** `~/.config/heads-down/mainnet/helius.env`, mode 600, one line
  `HELIUS_API_KEY=<key>`. The scripts parse it (it is never executed), keep it in a
  non-exported variable, and hand it to child processes through a prefix assignment (the
  environment, never `argv`) or a mode-600 Solana CLI config in a private temp directory that is
  deleted on exit. All output is filtered through a redactor; `set -x` is refused.
- **On Railway:** the crank's `HELIUS_API_KEY` and the indexer's `RPC_URL` carry the key, each as
  a sealed variable of its own service (section 10.3). The crank substitutes the key into the
  `{HELIUS_API_KEY}` placeholders of its config itself. No service logs the URL (only scheme and
  host), and the indexer and the crank scrub the key out of a provider's error text.
- **Never** in the APK, the repo, a receipt, a log, a chat or a command line. If it leaks: rotate
  it in the Helius dashboard, update `helius.env` and the two Railway variables, redeploy.

## 6. Preflight

```bash
scripts/mainnet/preflight.sh            # read-only; exit 0 = GO
```

| Check | Fails when |
|---|---|
| `helius.env` | missing, not mode 600, or no well-formed `HELIUS_API_KEY=` (the value is never shown) |
| key dir / key files | the directory is not 700 or a key is not 600, or a key is missing |
| program keypair | it is not `HDn4vg…` |
| deployer / crank-payer / governance address | on mainnet with the default key directory: the key file does not derive the funded address (`9DSVM862…`, `5Xec1ZUw…`, `37u9LWbP…`). Override `HD_EXPECTED_DEPLOYER`, `HD_EXPECTED_CRANK_PAYER` or `HD_EXPECTED_GOVERNANCE` only on purpose |
| git | (warning) local changes: `deploy.sh` refuses a dirty tree on mainnet |
| cluster | the RPC's genesis hash is not mainnet's `5eykt4Us…`, or the RPC is a loopback fork |
| program .so | not an SBF ELF; its sha256 and the solana-verify hash are printed |
| max-len | fresh: smaller than the build or above 10 MiB. Upgrade: a build above 10 MiB; a build that outgrows the deployed ProgramData is a warning that says how many bytes the upgrade adds |
| buffer | the deploy's per-commit buffer address holds something that is not the deployer's buffer for this build: another authority, another size, or not a buffer. A buffer that is the deployer's is counted, and the line says how many chunks are still to write |
| SIMD-0500 | active or pending for an SBPF v0-v2 build (or SBPFv3 not enabled for a v3 build) |
| program account | something already lives at `HDn4vg…` (fresh mode) |
| deployer balance | below what is still needed: ProgramData(max-len), less what the deploy's buffer already holds, + Program + fees for the chunks still to write + Config + float, all from `getMinimumBalanceForRentExemption` |
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
3. Fixes the deploy's buffer address (`buffer-<commit>.json` in the key directory) and runs
   `preflight.sh` with it; anything but GO stops here. A buffer left by a run that stopped part way
   is counted: its rent is not asked for again, and the fee budget covers the chunks still to write.
4. Prints the plan and asks you to type `deploy heads_down to mainnet`.
5. Writes the buffer with `hd-devstack write-buffer`: it creates the buffer as the Solana CLI would
   (owner, size, the deployer as authority, the ProgramData rent for max-len in it), checks that
   the deployer can pay for the writes that are left, then sends one write a second, each on a
   recent blockhash with a compute-unit limit from one simulation and the priority fee. It looks
   the writes up in batches, signs a write again if its blockhash expired, takes HTTP 429 and
   timeouts as "slow down", watches a send that got no answer like one that was taken, and ends by
   reading the buffer back and comparing every byte with the build. It stops by itself after five
   minutes without a write landing. It keeps nothing on disk: it can be killed and run again.
6. Only for `--mode upgrade` when the build is larger than the deployed ProgramData holds: extends
   the ProgramData with `solana program extend HDn4vg… <bytes>` (the bytes preflight names, at least
   10,240; one transaction) and waits eight slots. The plan shows a `ProgramData` line with the
   bytes and the rent they lock.
7. Runs the pinned CLI, through a private CLI config holding the Helius URL:
   ```text
   solana program deploy --use-rpc --program-id ~/.config/heads-down/heads_down-program-keypair.json \
     --upgrade-authority deployer.json --keypair deployer.json --buffer buffer-<commit>.json \
     --max-len 196608 --with-compute-unit-price 100000 --max-sign-attempts 20 \
     --commitment confirmed --output json-compact programs/heads-down/target/deploy/heads_down.so
   ```
   The CLI finds every chunk in the buffer, sends no write, and sends one transaction: the deploy.
8. `hd-devstack verify-deploy` reads the ProgramData back and checks that it holds the build
   byte for byte (the rest of max-len zero), that the upgrade authority is the deployer, that
   the ProgramData is exactly 45 + max-len bytes, and that the deploy transaction succeeded;
   then it writes `deploy/receipts/mainnet/<UTC>-fresh-<commit>.json` (schema in
   [deploy/receipts/README.md](../deploy/receipts/README.md)), which also records what the writer
   did (`buffer_write`) and the transactions the CLI sent after it (`cli_phase`: 1, or 2 when the
   ProgramData was extended first). **Commit that file.**

If it stops part way, nothing is lost: the buffer is the deployer's and keeps the chunks that
landed and the rent. Re-run `deploy.sh` at the same commit and it continues: preflight says how
many chunks are still to write and asks for no SOL that is already in the buffer. Or take the rent
back with
`scripts/mainnet/solana.sh -- program close <BUFFER> --recipient 9DSVM862oJrstiPmmQmgqXb7AkuXrKJtYgd1fwbeqeeW`:
every lamport in the buffer returns; the fees of the writes that landed (5,267 lamports each) do
not. If the writer stops with `the payer … holds …`, send the deployer the amount it names and run
`deploy.sh` again. If it stops with `no write was seen to land for 300 s`, the line above it gives
the deployer's balance and the counts: check the RPC and the priority fee (`--cu-price`), then run
it again. If the final transaction landed but the receipt was not written (an RPC error right
after it), a re-run in fresh mode is refused because the program exists: write the receipt with
`hd-devstack verify-deploy` by hand.

Flags: `--write-rate N` changes the pace, `--cli-only` lets the CLI write the buffer itself,
`--public-rpc` uses the public RPC.

Why not the CLI alone: with `--use-rpc` it sends all its writes 10 ms apart and never sends one
again inside a blockhash window. On the local fork, behind a proxy that lets one `sendTransaction`
a second through (the free Helius plan's limit), 3 signing rounds took about 260 s, landed about
15 of 198 writes, and ended with `Max retries exceeded`. The paced writer then continued that
same buffer: about 185 writes in 188 s, none refused. None of this has run against Helius or on
mainnet: the proxy copies the answer Helius documents.

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
- **The float and every top-up are permanent.** No instruction moves lamports out of the Executor
  except the two above, and the 3,000 lamports a dig leaves behind stay there (`Config.bury_bps`
  is stored, but no instruction reads it). Running the crank yourself does not bring the float
  back: the 7,000 it receives come out of the 10,000 the same dig paid in. Top up only what the
  alert asks for.

## 10. Railway

Five services in one Railway project, `heads-down` (Hobby plan). Four are built from the GitHub
repo `OoJae/heads-down`, branch `main`, each from its own Dockerfile with the repository root as
build context; Postgres is Railway's template. The project was created on 2026-10-04:

| Service | Address | Volume | Needs before it starts |
|---|---|---|---|
| `Postgres` | private network only: it has no public port | the template's own | nothing |
| `indexer` | `https://indexer-production-88dc.up.railway.app` | - | Postgres. Runs without `RPC_URL` until the program is initialized |
| `dashboard` | `https://dashboard-production-b80c.up.railway.app` | - | the indexer's address, at build time |
| `registrar` | `https://registrar-production-71d0.up.railway.app` | `/data`, 1 GB | its key, its session secret, an app certificate digest |
| `crank` | `https://crank-production-21c2.up.railway.app` | `/data`, 1 GB | **the deployed and initialized program, a funded fee payer, its key and the Helius key** |

### 10.1 How a service is created

Railway does not read a `railway.json` for services created now (its documentation: "New services
cannot opt into Config as Code"), and the repository root has nothing Railway could build by
default. The four `deploy/railway/<service>/railway.json` files therefore only document the
settings; the settings themselves are set on each service, in the dashboard or through Railway's
API. In this order, because connecting the repository starts the first build at once:

1. Create an **empty** service with exactly the name in the table (variable references use it).
2. **Variables**: the plain ones from the matrix below.
3. **Settings** (Root Directory stays `/`; leave **Custom Start Command** empty, because a custom
   one would replace the crank's and the registrar's entrypoint and their key would never reach
   them):

   | Service | Dockerfile path | Healthcheck (timeout) | Draining | Watch paths |
   |---|---|---|---|---|
   | indexer | `deploy/railway/indexer/Dockerfile` | `/v1/health` (120 s) | 10 s | `/services/indexer/package.json`, `/services/indexer/pnpm-lock.yaml`, `/services/indexer/src/**`, `/services/indexer/migrations/**`, `/deploy/railway/indexer/**` |
   | dashboard | `deploy/railway/dashboard/Dockerfile` | `/healthz` (60 s) | 5 s | `/dashboard/**`, `/deploy/railway/dashboard/**` |
   | registrar | `deploy/railway/registrar/Dockerfile` | `/healthz` (60 s) | 15 s | `/registrar/Cargo.toml`, `/registrar/Cargo.lock`, `/registrar/src/**`, `/registrar/roots/**`, `/deploy/railway/registrar/**` |
   | crank | `deploy/railway/crank/Dockerfile` | `/healthz` (120 s) | 20 s | `/crank/**`, `/crates/p256-introspect/**`, `/deploy/railway/crank/**` |

   Restart policy: on failure, 10 retries (Railway's default). The watch paths keep a push that
   touches only other directories, a deploy receipt for example, from rebuilding the service.
4. **Volume** (crank and registrar only): attach one at `/data`. On Railway both entrypoints refuse
   to start without it: without the volume the crank would forget its lookup table and create a
   new one on every deploy, and the registrar would lose its nonce database and its log.
5. **Secrets** (section 10.3).
6. **Connect the repository** `OoJae/heads-down`, branch `main`. This starts the first build.
7. **Networking → Generate Domain** with the target port equal to the service's `PORT`.

A deployment turns healthy when its healthcheck answers 2xx: crank `/healthz` (chain view fresh,
ORE pins match, breaker closed), registrar `/healthz`, indexer `/v1/health`, dashboard `/healthz`.

### 10.2 The order

1. **Postgres**: Railway's PostgreSQL template, name `Postgres`
   ([deploy/railway/postgres/README.md](../deploy/railway/postgres/README.md)). The indexer uses
   its private address. If the template gives the database a public TCP proxy, delete the proxy
   (the service's Networking settings): nothing needs it, and its password is an ordinary
   variable.
2. **indexer**, with no `RPC_URL` yet and its domain generated. Without `RPC_URL` it reads ORE's
   rounds from api.ore.com and makes no RPC call at all.
3. **dashboard**, after the indexer has its domain: the indexer's address is compiled into the
   pages, so a later change needs a rebuild, not a restart. Open the site afterwards and check
   that it shows data and not "This build has no API configured".
4. **registrar**. It needs neither the RPC nor the program to start.
5. Fund the keys; `preflight.sh`, `deploy.sh`, `init-config.sh` (sections 6 to 8), while nothing
   else uses the Helius key.
6. Set the indexer's `RPC_URL`.
7. **crank, last.** Before the program is initialized it has nothing to dig and still polls (since
   2026-10-04 an idle or unfunded crank is cheap: it reads once, then one full pass in 10 rounds,
   and with an empty fee payer it sends nothing), and it shares the Helius key's limits with the
   deploy. Re-run `preflight.sh` on the
   day (the line "ORE upgrade slot"): if ORE has upgraded since the pin was set, the crank's
   breaker trips at start and its first deployment fails the healthcheck, which is the design.
   After its first start, compare the `cranker` in its first log line with `TEAM_CRANKERS`.

### 10.3 Secrets

| Variable | Service | From |
|---|---|---|
| `HD_REGISTRAR_KEYPAIR_JSON` | registrar | `~/.config/heads-down/mainnet/registrar.json` |
| `HD_SESSION_SECRET` | registrar | `~/.config/heads-down/mainnet/registrar-session-secret` |
| `HD_CRANK_KEYPAIR_JSON` | crank | `~/.config/heads-down/mainnet/crank-payer.json` |
| `HELIUS_API_KEY` | crank | the key in `helius.env` |
| `RPC_URL` | indexer | `https://mainnet.helius-rpc.com/?api-key=<key>` |

Railway seals a variable only from its dashboard (the variable's three-dot menu → **Seal**); its
API and its CLI cannot. A sealed value is given to builds and deployments and is never shown or
returned again. So either paste each value in the dashboard and seal it, or send it from the file,
so that it is never on a command line, on screen or in a chat, and seal it afterwards:

```bash
railway variable set HD_REGISTRAR_KEYPAIR_JSON --stdin --skip-deploys \
  -p <project id> -e production -s registrar < ~/.config/heads-down/mainnet/registrar.json >/dev/null
```

Until a value is sealed, anything that lists the service's variables prints it: do not run
`railway variable list`, `railway run`, `railway shell`, `railway ssh` or
`railway config pull --include-variables` against these services, and do not let an agent do so.
The indexer's `DATABASE_URL` is a reference to a variable Railway generates for Postgres and
cannot be sealed away, so the same rule holds for the indexer and Postgres for good.

Seal the variables that carry the Helius key themselves. Railway's documentation does not say
whether a reference to a sealed shared variable is listed with its resolved value, so this
deployment uses no shared variable: the crank gets the key, the indexer gets the full URL, and
the registrar gets neither (it uses a keyless RPC, below).

### 10.4 Variable matrix

`S` = sealed secret, `R` = Railway reference, `-` = plain.

| Variable | crank | registrar | indexer | dashboard | Value |
|---|---|---|---|---|---|
| `HELIUS_API_KEY` | S | | | | the key |
| `HD_CRANK_KEYPAIR_JSON` | S | | | | contents of `crank-payer.json` |
| `PORT` | 8787 | 8080 | 8080 | 8080 | the domain's target port |
| `RUST_LOG` | `info,hyper=warn,reqwest=warn` | `info` | | | |
| `HD_REGISTRAR_KEYPAIR_JSON` | | S | | | contents of `registrar.json` |
| `HD_SESSION_SECRET` | | S | | | contents of `registrar-session-secret` |
| `HD_RPC_URL` | | - | | | `https://solana-rpc.publicnode.com`, a keyless public RPC: the registrar makes one cached `getSlot` per attestation, so it needs no key and does not depend on Helius credits. Do **not** leave it unset: the code's default, `api.mainnet-beta.solana.com`, refuses requests from Railway's servers. With it every attestation answered 503 `slot_unavailable` and the app registered a guest rig (found on 2026-10-04 by running the app on an emulator against the live service; `/healthz` stayed 200 throughout). A keyed Helius URL also works; it must then be sealed. The registrar asks for the slot once at start: its deploy log then holds `slot source answered`, or the warning `slot source gave no slot` with the HTTP status |
| `HD_APP_DEBUG_CERT_SHA256` | | - | | | **today.** SHA-256 of the certificate that signs the debug build (`apksigner verify --print-certs app-debug.apk`), which is this Mac's Android debug key. The registrar then accepts that build, logs a warning and reports `debug_signers_accepted` on `/registrar`. Good for the founder's own phone only: remove it before anyone else installs the app |
| `HD_APP_RELEASE_CERT_SHA256` | | - | | | SHA-256 of the release signing certificate, once a release key exists (none does). With both digest variables empty the registrar exits at start; with only a release digest it refuses a debug build (`wrong_signer`) and the app registers a guest rig |
| `HD_SIWS_DOMAIN` | | - | | | **required, no default.** The host of the site the app identifies itself with: the host of the app build's `-Pheadsdown.identityUri` (default `oojae.github.io`). It must be a site the team controls |
| `HD_SIWS_URI` | | - | | | that site's URL, `https://oojae.github.io/heads-down/`. The code's default is `https://<HD_SIWS_DOMAIN>` |
| `HD_TRUST_REAL_IP` | | `true` | | | keys the rate limits on `X-Real-IP`, the header Railway's edge writes the client's address in (its documentation does not list `X-Forwarded-For`, so `HD_TRUSTED_PROXY_HOPS` stays `0` or unset; with hops above 0 as well the registrar exits at start). The value is `true` or `false` in lower case. Checked on the live service on 2026-10-04 with the command below: of 60 requests carrying 60 different made-up addresses, 24 answered 200 and 36 answered 429, so the edge replaces a client's own header and a caller cannot choose its bucket |
| `HD_NONCE_STORE` / `HD_TRANSPARENCY_LOG` | | image defaults | | | `/data/nonces.db`, `/data/attestations.jsonl` |
| `DATABASE_URL` | | | R | | `${{Postgres.DATABASE_URL}}` |
| `RPC_URL` | | | S | | unset until `initialize_config` has landed, then `https://mainnet.helius-rpc.com/?api-key=<key>` |
| `INGEST_INTERVAL_S` | | | - | | `300` between test sessions, `30` to `60` while recording (section 5); the code's default is 30 |
| `SNAPSHOT_EVERY_N_POLLS` | | | - | | default `20`. The indexer scans the program's accounts when a poll finds a new transaction, and otherwise every Nth poll as a safety net (100 minutes at an interval of 300 s) |
| `ORE_ROUNDS_SINCE` | | | - | | a unix time. Set it to about a day back on a new database: the default, 14 days, needs about 160 pages from api.ore.com in one poll, which ORE's API rate-limits (HTTP 429), and a poll that fails stores nothing (found on the live service on 2026-10-04) |
| `INDEXER_DATASET` | | | `mainnet` | | |
| `TEAM_CRANKERS` | | | - | | `5Xec1ZUwXcB2ZGeWqqBHrxaHT4WQrGVUgH9xmqgC1kzk` |
| `CORS_ORIGIN` | | | `*` | | the API is public and read-only; or the dashboard's exact origin, with no trailing slash |
| `HOST` | | | `0.0.0.0` | | |
| `NEXT_PUBLIC_HD_API_BASE` | | | | - (build) | the indexer's address, `https://indexer-production-88dc.up.railway.app`. The build fails unless the value is `https://<host>` and nothing else (a port and one trailing `/` are allowed): no path, no `?`, `@` or `api-key`, and not a bare `https://`, because the value is published in the site's JavaScript |

The full lists, with every optional setting and its default, are the four `.env.example` files.
`HD_FIXED_SLOT` and `HD_STATUS_LIST_FILE` are development overrides of the registrar and must
stay unset.

**The crank's settings for one phone.** `deploy/railway/crank/crank.toml` is sized for a 0.05 SOL
fee payer: signals at most 250,000 lamports an hour, `end_shift` 6,000,000 a day, Stack 500,000 an
hour and 10,000,000 a table, one lookup table; `chain_poll_secs = 15` and one full rig read in 10
idle rounds. `[record]` stays at 1,000,000 lamports an hour and `init_bury_vault` stays on in that
file. For one phone on Helius' free plan, set the block of overrides at the end of
`deploy/railway/crank/.env.example` on the service: `HD_CRANK_ALT_ENABLED=false`,
`HD_CRANK_STACK_ENABLED=false`, `HD_CRANK_STACK_INIT_BURY_VAULT=false`,
`HD_CRANK_END_SHIFT_ENABLED=false`, `HD_CRANK_CLEANUP_ENABLED=false` and
`HD_CRANK_INTAKE_RIG_FETCHES_PER_SECOND=1`. They cut the idle cost from about 108,500 to about
30,500 credits a day, park no rent in a lookup table (one rig fits a transaction without one), and
keep the crank from paying the Bury vault's rent (3,114,040 lamports that never come back). A
misspelled `HD_CRANK_*` name is only a warning at start: read the first log lines after setting
them, or run `hd-crank config`. An empty `HELIUS_API_KEY` stops the crank with `HELIUS_API_KEY is
set to an empty value`.

If lookup tables stay on: the fee payer needs at least 3,310,560 lamports before the crank creates
its table (below that it logs `lookup_table_unfunded` once and sends nothing). Once the log shows
`created lookup table`, set `HD_CRANK_ALT_TABLES=<that address>` and
`HD_CRANK_ALT_AUTO_CREATE=false`, so that nothing that happens to the volume can lead to a second
table.

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

**The registrar's client address.** The registrar has no `/whoami`, so its check counts answers.
With `HD_TRUST_REAL_IP=true` set, send 60 requests that each carry a different made-up address
(run it again after any change to the service's networking):

```bash
seq 60 | xargs -P 20 -I{} curl -s -o /dev/null -w '%{http_code}\n' \
  -H 'X-Real-IP: 203.0.113.{}' https://<registrar domain>/registrar | sort | uniq -c
```

About 20 answers of 200 and 40 of 429 mean the edge replaced the made-up addresses with yours, and
the setting can stay. 60 answers of 200 mean a caller can choose its own bucket: unset it again.

**Gate-closed nights.** The same file sets `[record] gate_closed_rigs = true`: on a night when
ORE's cost never drops under a rig's ceiling nothing is dug, and without a record the shift would
seal with no dark round (no streak day; a Focus Bond would go to the Bury lot). The crank pays for
those records itself, at most 1,000,000 lamports an hour (about 1,240,000 lamports over an
8-hour night for one rig).

### 10.5 How the keys reach the processes

The crank and registrar images start their entrypoint as root for two things only: the
keypair JSON from the sealed variable is checked for shape (never printed), written to a
`0600` file in a private `0700` directory on tmpfs (`/dev/shm`), and removed from the
environment; and the Railway volume at `/data` is handed to uid 10001 (Railway mounts volumes
as root). The service then starts as uid 10001 with `setpriv --no-new-privs`, and the key file
is deleted as soon as the service listens (both load their key before binding). Railway's
`SIGTERM` is forwarded (the crank shuts down on `SIGINT`, the registrar on `SIGTERM`).

Both entrypoints refuse shell tracing (`bash -x`, `SHELLOPTS=xtrace`: exit 2), because tracing
would print the key into the deploy log. They remove the key directory when they stop early, on
a failed step or on `SIGTERM` (`SIGKILL` cannot be caught: the file then stays until the container
is gone). On Railway they refuse to start unless `RAILWAY_VOLUME_MOUNT_PATH` is `/data` (exit 1).
In the image they listen on `0.0.0.0:$PORT`.

Do not open a Railway shell on the crank or the registrar. The service's first environment stays
readable to root inside the container whatever the entrypoint unsets, so `env`, `printenv`, `set`
or a read of `/proc/*/environ` there prints the keys. Everything the first-deploy check needs is
in the deploy log (below).

### 10.6 Checks without Docker

```bash
python3 deploy/railway/check.py             # 76 checks: COPY sources, digest pins, no VOLUME, schema keys, healthcheck routes, empty secrets,
                                            # the entrypoint guards, the dashboard build guard under sh and dash, hadolint, shellcheck
python3 deploy/railway/test_entrypoints.py  # both entrypoints with a made-up key and a stub service: 43 cases per bash
```

The Docker build steps were also replayed natively (section 12).

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
  -Pheadsdown.identityUri=https://oojae.github.io/heads-down/
```

The RPC URL must not carry an API key: the public `https://api.mainnet-beta.solana.com` works for a
demo build; a keyed provider needs a proxy that adds the key server-side.

**The app's identity.** `identityUri` is the site the app names to the wallet as its identity
(Mobile Wallet Adapter). Solana Mobile's test wallet shows it on the connect and sign-in prompts,
not on the transaction prompt; other wallets are untested. Through its host it is also the Sign In
With Solana domain; the registrar's `HD_SIWS_DOMAIN` / `HD_SIWS_URI` must match it. The default is
the project's GitHub Pages address, which only the repository owner's GitHub account can publish
to. It ends in `/` on purpose: wallets resolve the icon `icon.png` against it, and without the
slash a wallet that follows the URL standard asks `https://oojae.github.io/icon.png` for it. The
build refuses a path without the slash. The page and its icon are in `site/` (the icon the app
names is a PNG, which every Android image loader decodes; `favicon.ico` is for browsers). To publish them, and
again after every change to `site/` (the command publishes what is committed at HEAD):

```bash
git subtree push --prefix site origin gh-pages     # the site becomes the root of the gh-pages branch
# first time only: GitHub → Settings → Pages → Build and deployment → Deploy from a branch → gh-pages, / (root)
curl -sI https://oojae.github.io/heads-down/ | head -1                # HTTP/2 200 within about ten minutes
curl -sI https://oojae.github.io/heads-down/icon.png | head -1
```

The page carries a dated status line ("not deployed on mainnet, not run on a physical phone yet").
Keep it true: founder checklist step 10.

**What a wallet can and cannot check.** The identity is a string the app hands to the wallet.
Nothing ties it to the app, so another app can name the same address, name and icon. On the
emulator, Solana Mobile's test wallet showed the name and the address and "Verification failed". A
wallet can tie the two together through Android's Digital Asset Links: a file at
`https://<identity host>/.well-known/assetlinks.json` naming the app's package and the SHA-256 of
its signing certificate. That file is fetched from the host's root, so for `oojae.github.io` it
belongs in the `OoJae/oojae.github.io` repository (the account's own site, which would then vouch
for every project on that host), not in this project's page under `/heads-down/`. It also needs a
release signing key, which does not exist yet: release builds are unsigned and debug builds carry
the debug key. Solana Mobile's test wallet signs and sends without either. Solflare, Phantom and
Seed Vault have not been tried; the protocol lets a wallet decline an identity it cannot verify, so
the connect prompt of the wallet used for the demo is the first thing to try on the phone.

## 11. Monitoring and alerts

Railway's healthcheck runs only when a deployment starts, so it is not monitoring.

| What | How | Alert when |
|---|---|---|
| crank alive and pinned | uptime monitor (Better Stack, UptimeRobot, Grafana Cloud synthetic) on `https://<crank>/healthz` every minute | non-200 for 3 minutes. 503 with a `breaker` reason means an ORE account failed its pin or ORE was upgraded: nothing digs until an operator restarts after the fork suites pass (docs/ORE.md §8) |
| crank economics | scrape `https://<crank>/metrics` (public, no addresses) | `hd_crank_executor_lamports` < 820,240; `hd_crank_cranker_lamports` < 10,000,000 (0.01 SOL); `hd_crank_circuit_breaker_tripped` = 1; `hd_crank_digs_landed_total` flat for an hour while `hd_crank_heartbeats_accepted_total` grows; `hd_crank_txs_failed_total` rising; `hd_crank_lookup_tables_total` with event `low_balance`, `create_failed`, `state_file` or `limit` above 0, and the log alerts `lookup_table_unfunded`, `lookup_table_state_unsaved` and `lookup_table_not_on_chain`. `hd_crank_dig_passes_total{why}` shows whether the crank is idle (`skipped`) or awake; `hd_crank_rigs_seen` and `hd_crank_rigs_eligible` keep the value of the last pass that read, so they stay flat while it is idle. With `chain_poll_secs = 15`, `/healthz` can report degraded for a moment when one HTTP poll fails while the WebSocket is stalled |
| indexer | uptime on `/v1/health`; check `data.lastPollOk`, `data.lastPollAt` and `data.problems` | down; `lastPollOk` false for more than two passes; `asOf - lastPollAt` above 3 x `INGEST_INTERVAL_S` plus 2 minutes (17 minutes at 300, 3.5 minutes at 30); any decode problem. `lastSlot` is the newest stored transaction and stands still whenever no rig is active, so it is not a liveness signal. The log line `rpc: transaction not yet available, will retry` repeating for many passes is a stall that `lastPollOk` does not show |
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
| `python3 deploy/railway/check.py` (COPY sources, digest pins, no VOLUME, schema keys, healthcheck routes, empty secrets, hadolint 2.15.1, shellcheck 0.11.0; since 2026-10-04 also the entrypoint guards and the dashboard build guard, run under sh and dash) | 64 checks on 2026-10-01, 76 on 2026-10-04, all passed |
| `python3 deploy/railway/test_entrypoints.py` (2026-10-04): both entrypoints with a made-up key and a stub service, under bash 3.2.57 and bash 5.2.15 (the image's version, built from source) | 86 runs, all passed: tracing refused, the key directory gone after every early exit, the volume refusal, exit codes passed on, no key bytes in any output |
| the four `railway.json` against `https://railway.com/railway.schema.json` (jsonschema, Draft 2020-12) | all valid |
| crank: both RUN steps replayed in a scratch copy of exactly the COPY set (`crates/p256-introspect`, crank manifests, `crank/src`), `RUSTUP_TOOLCHAIN=1.97.1`, `--locked` | dependency layer 1 m 22 s, crank 27 s; `hd-crank --version` = `hd-crank 0.1.0` |
| registrar: the same for `registrar/{Cargo.toml,Cargo.lock,src,roots}` with Rust 1.97.1 and `--locked` (identical in both registrar Dockerfiles) | dependency layer 1 m 07 s, service 14 s; `cargo test`: 105 passed (116 on 2026-10-04) |
| indexer: `pnpm install --frozen-lockfile --prod` (pnpm 11.1.2), then `node src/main.ts serve` with the image's environment and an in-memory database | devDependencies skipped (`@electric-sql`, `@solana`, `pg` only); `GET /v1/health` 200 |
| dashboard: `pnpm install --frozen-lockfile`, `pnpm build` with `NEXT_PUBLIC_HD_API_BASE=https://indexer.example.org` | 8 static routes, 56 files; the API base is inlined |
| dashboard `server.mjs` on that export | `/healthz` 200, `/` 200, `/cohorts` 308 → `/cohorts/` 200, unknown page 404 (export's page), `/%2e%2e/%2e%2e/etc/passwd` 404, `/.env` 404, `POST /` 405, `HEAD /` 200, hashed assets `immutable`, HTML `must-revalidate`, `nosniff`/`DENY`/HSTS headers, listening on `::` |
| crank entrypoint (macOS, non-root branch, paths redirected to scratch) | no key → exit 1; malformed key → exit 1 and the value is not echoed; real key → hd-crank started, key file gone once it listened, `/healthz` answered, no key bytes in the log; SIGTERM → hd-crank shut down on SIGINT, exit 0 |
| registrar entrypoint (same) | both key forms set → refused; real key → `/registrar` reports the test key, key file gone, `/healthz` 200; SIGTERM → `hd-registrar stopped` |
| `deploy/railway/crank/crank.toml` under hd-crank's strict loader, `hd-crank check` against mainnet (read-only) | parsed; ORE upgrade slot = pin (450,496,378 at the time), breaker closed |

`setpriv` and a tmpfs `/dev/shm` exist only on Linux, so the root branch of the entrypoints
(dropping to uid 10001) cannot run on the build machine. It first ran on Railway on 2026-10-04,
for the registrar: the deploy log showed `key file removed; hd-registrar (pid 13) on 0.0.0.0:8080`,
and the service answered. Since then the line also names the uid: on every first deploy of the
crank or the registrar, read the log for `key file removed; hd-<service> (pid N, uid 10001)`. A line
`no Railway volume is mounted at /data` means the volume is missing or Railway did not pass its
mount path: roll back to the previous deployment in Railway.

## 13. Rollback and incidents

| Situation | Do | Effect |
|---|---|---|
| Any doubt about digs (a bug, an ORE change, bad crank behaviour) | `scripts/mainnet/governance.sh pause`. If Helius answers HTTP 429 because the key has no credits left: `scripts/mainnet/governance.sh --public-rpc pause` | **Immediate**: from the next transaction `dig` fails with `Paused` (18). Nothing else stops: users can still break, freeze, end shifts, close rigs and Revoke in ORE. No SOL moves. |
| Resume | `governance.sh unpause`, then after 864,000 slots (≥ 72 h; ~96 h at 400 ms) `governance.sh apply` | Un-pausing always waits for the timelock. A new proposal replaces a pending one and restarts the clock. |
| Program bug | pause, fix, then upgrade with a fresh buffer (section 14) | users' funds stay in their own ORE Automations throughout |
| Crank misbehaving or compromised | stop the Railway service; rename `crank-payer.json` aside, run `keys.sh` (it creates a new one), move the old key's SOL with `solana.sh --keypair <old file> -- transfer <new pubkey> ALL --allow-unfunded-recipient`, update `HD_CRANK_KEYPAIR_JSON` and `TEAM_CRANKERS`, redeploy. `keys.sh` and `preflight.sh` stop on the new key until its pin is changed: set `HD_EXPECTED_CRANK_PAYER=<new pubkey>` for those runs, then update the pin in `scripts/mainnet/lib.sh` and the tables in sections 2, 3 and 10.4. The lookup table named in `/data/hd-crank/lookup_tables.json` belongs to the old key: close it with the old key and remove that file, or the new crank creates no table (it has `alt.max_tables = 1`, and a table on record counts) | liveness only: the worst case is no digs |
| Registrar key compromised | stop the registrar; rename `registrar.json` aside and run `keys.sh` for a new one; `governance.sh propose --registrar <new>`; after 72 h `apply`, then start the registrar with the new key. Until `apply` the chain still trusts the old key (rigs can register unattested meanwhile). For a routine rotation, run old and new side by side until `apply` | attestation levels only (THREAT_MODEL K4) |
| Helius key leaked | rotate in Helius, update `helius.env` and the shared variable, redeploy | |
| Deploy stopped part way | re-run `deploy.sh` at the same commit: preflight counts the rent and the chunks the buffer holds, and the writer sends only what is missing; or close the buffer (section 7). No SOL has to be sent for either. The writer says why it stopped: `the payer … holds …` names the SOL to send; `no write was seen to land for 300 s` means writes were taken and did not land (the RPC, the priority fee, or an empty deployer) | |
| A second buffer at a commit whose buffer was handed to the vault | move `buffer-<commit>.json` out of the key directory, then run `deploy.sh` again (it makes a new keypair) | preflight refuses until then, and says so |
| Helius credits used up | every call answers HTTP 429. Operator scripts: add `--public-rpc`. Services: the crank stops digging and the indexer stops reading the chain (its API keeps serving); remove the crank's deployment, and wait for the month to roll over or change the plan or the key | nothing is spent while nothing digs |
| ORE upgraded its program (it did on 2026-09-25 and 2026-10-02) | Nothing to do at once: the crank's breaker has already stopped digs, and `preflight.sh` answers NO-GO. Then: read the diff between the commits verify.osec.io names; refresh the fixtures (`programs/heads-down/tests/fixtures/fetch-fixtures.sh`, `scripts/devstack/up.sh --refresh-fixtures`); run the program's and the crank's fork suites and `dry-run.sh --tight`; move the pin (`crank/src/ore.rs`, both `crank.toml`, `ORE_PROGRAM_HASH` in `scripts/devstack/tool/src/ops.rs`, `docs/ORE.md`); redeploy the crank | no digs, so nothing is mined and nothing is spent, until the pin is moved |

## 14. Upgrades with a fresh buffer

Before any upgrade: run the program's fork suite (`programs/heads-down/scripts/test.sh`) and the
crank's against live ORE, and regenerate `vectors/` if the interface changed; consumers must
match first.

- **While `deployer.json` is the upgrade authority:**
  `scripts/mainnet/deploy.sh --mode upgrade` (builds, preflights, writes a fresh per-commit
  buffer, extends the ProgramData first if the build outgrew it, upgrades in place, verifies the
  bytes, writes `…-upgrade-<commit>.json`). The extension is a transaction of its own
  (`solana program extend`, at least 10,240 bytes: 0.0520192 SOL of rent that stays locked), sent a
  few seconds before the upgrade; preflight shows it as a `max-len` warning and the plan as a
  `ProgramData` line. Rehearsed on the fork with the build padded past max-len (section 12).
- **Once a Squads vault is the authority:**
  1. `scripts/mainnet/deploy.sh --mode buffer --buffer-authority <VAULT>` writes a fresh buffer,
     hands it to the vault and records `…-buffer-<commit>.json` with the buffer's program hash.
     The plan and the confirmation show `<VAULT>` on a line of its own with the SOL in the buffer:
     the loader hands a buffer over on the deployer's signature alone, so a mistyped address loses
     the buffer's rent (0.966282040 SOL for today's build) for good. Compare it character by
     character. After the hand-over, `deploy.sh` at the same commit is refused by preflight: the
     buffer is the vault's.
  2. Every signer checks the buffer: `solana-verify get-buffer-hash <BUFFER> -um` must equal the
     receipt's `onchain.program_hash`, which equals a local rebuild of the recorded commit.
  3. In Squads: **Programs → `HDn4vg…` → Upgrade**, buffer `<BUFFER>`, spill account (where the
     buffer's rent returns) the deployer. Approve to the threshold; the vault's time lock
     (72 h) runs; then execute.
  4. `scripts/mainnet/solana.sh -- program show HDn4vgLWFLLdexKEwfZwRHjWtizNvdqFteLbMsE67F9p`
     shows the new last-deploy slot; record it in the receipt's commit message.
- A build larger than the ProgramData holds needs it extended before the upgrade, by at least
  10,240 bytes on mainnet (SIMD-0431), and not in the slot of the upgrade: the loader refuses that.
  `deploy.sh --mode upgrade` does both in the same run. For a Squads upgrade, extend before the
  vault's upgrade executes:
  `scripts/mainnet/solana.sh -- program extend HDn4vgLWFLLdexKEwfZwRHjWtizNvdqFteLbMsE67F9p <bytes>`
  (the payer pays the rent; by the loader's source it asks for no authority signature; the command
  was run from `deploy.sh`, not through `solana.sh`). `--mode buffer` does not extend, and its
  preflight does not warn about it.

## 15. Squads: upgrade authority and governance

**Upgrade authority → Squads multisig with a time lock** (THREAT_MODEL K5).

Two things to know before doing it. Squads charges a **0.1 SOL deployment fee** per multisig, plus
network fees (0.0069 SOL to create the time lock): that is more SOL that never comes back than the
whole deploy (0.0053 SOL). And while `deployer.json` is the upgrade authority, one signature can
close the program and return its 0.9996 SOL of rent (section 3); after the move that takes the
multisig's threshold and its 72 hours, and after `--final` it is impossible. So this step is a
decision, not a formality: it trades the founder's cheap exit for the users' protection against a
single key. Until it is done, the documents say plainly that one key can upgrade the program.

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
- Since v1.3 the program can rotate `Config.governance` (`propose_governance`, then
  `accept_governance` by the successor after the same 72 h). No script sends these yet: the two
  commands have to be added to `hd-devstack` and `governance.sh` first. When there are
  co-signers, move governance to a **second** Squads multisig **without** a time lock (2-of-3,
  another 0.1 SOL): a pause then takes two signatures but no waiting, and the program's own 72 h
  timelock still covers every other change.

**Until the steps above are done, say so.** At launch the upgrade authority is one keypair and
program upgrades have no delay. [THREAT_MODEL.md](THREAT_MODEL.md) ("As built") and
[SECURITY_REVIEW.md](SECURITY_REVIEW.md) state this, and they must keep doing so until the
vault holds the authority.

## 16. Costs per month

| Item | Plan | Monthly |
|---|---|---|
| Railway | Hobby, billed by usage (the account's plan already; $5 a month that counts towards usage across all of the account's projects) | an estimate, not yet measured: about **$8-15** for five small services (crank ~0.1 GB RAM, registrar ~0.05 GB, indexer ~0.2 GB, dashboard ~0.05 GB, Postgres ~0.25 GB at $10 per GB-month; light CPU at $20 per vCPU-month; volumes at $0.15 per GB-month of storage used). Read the project's usage page after the first days and set a usage limit |
| Helius | Free | **$0** while a month stays inside 1M credits (section 5); otherwise **$49** (Developer, 10M credits) plus $5 per further million |
| SOL: crank fees | | about 0.001 to 0.003 SOL per phone-night (section 3); budget 0.01 to 0.05 SOL a month |
| SOL: lookup tables | one-time, only when lookup tables are on | 0.00256 SOL + 0.00065 SOL per rig. It comes back only by hand, with the crank stopped: `scripts/mainnet/solana.sh --keypair <crank-payer.json> -- address-lookup-table deactivate <TABLE> --bypass-warning`, about 5 minutes later `… address-lookup-table close <TABLE> --recipient <ADDR>`. Keep the table's address (the crank logs `created lookup table`). After closing a table, remove `/data/hd-crank/lookup_tables.json` (or the table's entry in it) before the crank runs again: a table on record counts against `alt.max_tables = 1`, so the crank would otherwise create none and dig without one |
| SOL: Executor | | grows by 3,000 lamports per dig; nothing ever leaves it (section 9) |
| SOL: an upgrade | per upgrade, temporary | about 0.964 SOL lent to the deployer for a few minutes; all but about 0.001 SOL of fees comes back (section 3) |
| SOL: a larger build | only when a build outgrows max-len | 52,019,200 lamports per 10,240-byte extension, locked like the program's rent |
| SOL: Squads | one-time, optional | 0.1 SOL per multisig, never returned (section 15) |
| Domain (optional) | | ~$1 (not needed: the app identifies itself with the project's GitHub Pages address) |
| **Total** | | **about $8-15 a month** on the free Helius plan, plus small SOL top-ups |

One-time: the deploy and initialization, **1.10 SOL** across the three keys (section 3). Of it,
0.9996 SOL of ProgramData rent stays locked while the program exists, 0.0053 SOL goes into the
Program account, the Config, the Executor float and fees for good, and 0.095 SOL is liquid on day
one: 0.035 in the deployer, and the crank's 0.05 and governance's 0.01, which are spent in use.

## 17. Files and secrets

| Path | Committed | Holds |
|---|---|---|
| `scripts/mainnet/*.sh` | yes | the runbook's scripts (no secrets; `scripts/mainnet/.gitignore` refuses `*.json` and `*.env` there, and the root `.gitignore` refuses the key file names anywhere in the repository) |
| `deploy/railway/<service>/` | yes | Dockerfile, `railway.json` (a record of the service's settings), `.env.example` (names only), entrypoints, `crank.toml` (placeholders only) |
| `.railway/` | never (ignored) | where `railway config pull` writes the project as code. Never run it with `--include-variables` in the repository: it writes every unsealed value into that file |
| `deploy/receipts/mainnet/` | **yes, after each change** | public receipts |
| `deploy/receipts/localnet/` | no (ignored) | dry-run receipts |
| `~/.config/heads-down/` | never | every key and `helius.env` (dir 700, files 600) |
| `~/.local/share/heads-down/deploy/` | never | build logs, preflight JSON, the buffer writer's output and JSON (`write-buffer-<cluster>-<UTC>.out` / `.json`), the extension's output (`extend-<cluster>-<UTC>.out`), CLI output |
| `~/.local/share/heads-down/dryrun/` | never | the dry run's fork, logs and state |
