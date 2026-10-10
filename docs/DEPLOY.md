# DEPLOY: Heads Down on mainnet

The runbook for putting `heads_down` on mainnet and running its services on Railway: keys,
funding, the preflight, the deploy, `initialize_config`, the Executor float, the Railway
services, monitoring, rollback, upgrades, the move to a Squads multisig, and what it costs.

**It has been run once.** On 10 October 2026 the program was deployed and initialized on
mainnet, the Executor was funded, and the crank was started. [MAINNET.md](MAINNET.md) is the
record of that day: every address and transaction, the first shift a phone ran, what it cost
and what it found. This page stays the runbook for the next deploy or upgrade. The sections that
run touched say what it showed, with figures from the two receipts in `deploy/receipts/mainnet/`.

Before that, the deploy path (keys, funding, preflight, a deploy that is stopped and continued,
`initialize_config`, the Executor float, upgrades, a buffer for a multisig, the pause) was
rehearsed end to end on a local mainnet fork (section 12). Run on mainnet so far: the funding,
the preflight, one fresh deploy, `initialize_config` and the Executor float. Not run on mainnet:
an upgrade, a deploy continued after a stop, a buffer handed to a vault, a pause or any other
governance transaction. Neither rehearsed nor run: closing the program, the move to a Squads
vault, un-pausing.

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

In order, as for a first deploy. Until step 5 no transaction is signed by these keys. Each step
says where it stands on 10 October 2026: steps 1 and 3 to 7 are done, and **steps 8, 9 and 11 are
still to do**.

1. **Helius key.** Create a Helius account (the free plan is enough to start, section 5). Put one line in
   `~/.config/heads-down/mainnet/helius.env`: `HELIUS_API_KEY=<key>`, then
   `chmod 600 ~/.config/heads-down/mainnet/helius.env`. Never paste the key anywhere else.
   *Done. The key was replaced on 10 October 2026, before the deploy: the first one was shared
   with another project and had no credits left (section 5).*
2. **Back up the keys, before any SOL is sent.** `~/.config/heads-down/` holds the only copies
   (no seed phrases were ever shown), and `deployer.json` is the only way to get the program's
   rent back (section 3). Make an encrypted backup (for example an encrypted disk image or
   `age`/`gpg`) of `heads_down-program-keypair.json` and `mainnet/`, and keep a copy off this
   machine.
   *This page does not record whether it was done. The keys now hold SOL and the upgrade
   authority.*
3. **Fund the keys** (section 3; amounts read from mainnet's rent on 2026-10-03):

   | Key | Public key | Send | Sent on 10 October 2026 |
   |---|---|---|---|
   | deployer | `9DSVM862oJrstiPmmQmgqXb7AkuXrKJtYgd1fwbeqeeW` | **1.04 SOL** (needs 1.036316560) | 1.040963 SOL |
   | crank fee payer | `5Xec1ZUwXcB2ZGeWqqBHrxaHT4WQrGVUgH9xmqgC1kzk` | **0.05 SOL** | 0.050963 SOL |
   | governance | `37u9LWbPrzQFkfL6oXGoSfq9souRggVtGvYszRXHezXN` | **0.01 SOL** | 0.014963 SOL |
   | registrar | `9deCPaA6iML39zQw4mptBeHRkm7DE6oVWGijdA9c2zgo` | 0 (never pays fees) | 0 |

   Total **1.10 SOL** (1.106889 SOL was sent). About 0.03 SOL of the deployer's share is never
   spent: the Solana CLI
   only has to see it there, and it is still in the deployer afterwards. Section 3 says what comes
   back and what does not: 0.9996 SOL is locked in the program's rent and 0.0053 SOL is gone for
   good. Two more amounts are not in the table: the **wallet on the phone** needs about 0.04 SOL
   for a week of tests, and a **first upgrade** needs about 0.963 SOL lent to the deployer for a
   few minutes, so the working budget is about 2.11 SOL if an upgrade must stay possible
   (the 1.107 sent, 0.04 and 0.963).
   *Done on 10 October 2026.*
4. **Preflight**: `scripts/mainnet/preflight.sh` must end with `GO`.
   *Done on 10 October 2026, as the first step of the deploy: GO at the second try (section 6).*
5. **Deploy**: `scripts/mainnet/deploy.sh` (type the confirmation, or pass `--yes` once the plan
   has been read). It writes the program into a
   buffer at one transaction a second (198 writes for today's build), then deploys with one more
   transaction. If it stops part
   way, run it again at the same commit: it continues and needs no more SOL for the buffer. Keep
   the Railway crank stopped and the indexer without `RPC_URL` until it is done: they share the
   Helius key's limits. Then commit the receipt it writes under `deploy/receipts/mainnet/`.
   *Done on 10 October 2026 with `--yes`: 198 writes in 288 s, the program deployed in slot
   455,359,196 (section 7). The receipt is committed.*
6. **Initialize**: `scripts/mainnet/init-config.sh` (type the confirmation), commit its receipt,
   and check `scripts/mainnet/governance.sh show`.
   *Done on 10 October 2026, slot 455,359,343 (section 8). The receipt is committed.*
7. **Railway** (section 10): Postgres, the indexer without `RPC_URL`, the dashboard and the
   registrar can run before the deploy, and do. After step 6: set the indexer's `RPC_URL`, then
   start the crank, last, with its fee payer funded.
   *Done on 10 October 2026. The indexer's `RPC_URL` was set at 17:36 UTC, before the deploy and
   not after it; the crank was started at 18:48:47 UTC (section 10.2).*
8. **Monitoring**: add the uptime checks and balance alerts of section 11.
   *To do. Nothing watches the services or the balances yet.*
9. **Decide about Squads** (section 15): a multisig with a 72 h time lock protects users against
   a single key, costs 0.1 SOL that does not come back, and ends the one-signature way of
   getting the program's rent back.
   *To do. Not decided: one key can upgrade the program at once.*
10. **Identity page**: after steps 5 and 6, and again after the first run on a phone, update the
    dated status line in `site/index.html`, commit, and publish it again (section 10.7).
    *The line was changed in the repository twice for 10 October 2026: for the deploy, and for
    the first shift a phone ran that evening. This page does not record that either version was
    published.*
11. **Seal the crank's key variable.** `HD_CRANK_KEYPAIR_JSON` was set on the Railway service on
    10 October 2026 from the key file, through the CLI's standard input, and its sealing was
    asked for. Check in the Railway dashboard that it is sealed, and seal it if it is not
    (section 10.3). Until then anything that lists the crank's variables prints the fee payer's
    key.
    *To do.*

## 1. What runs where

| Piece | Where | Controlled by |
|---|---|---|
| `heads_down` program `HDn4vgLWFLLdexKEwfZwRHjWtizNvdqFteLbMsE67F9p` | mainnet, upgradeable loader, since 2026-10-10 | upgrade authority: `deployer.json` today; a Squads vault, then none, is the plan (section 15) |
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

Every script except `dry-run.sh` and `selftest.sh` takes `--cluster mainnet|localnet` (default
mainnet) and `--keys-dir DIR`. With
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
another key directory (`--keys-dir`, `HD_MAINNET_KEYS`). On mainnet with another key directory
the scripts say so (`funded addresses not checked`); on localnet they say nothing about pins.
After a key is
replaced on purpose, change its pin and the tables in this file.

Who can do what with each key, and the worst case if it leaks, is in
[THREAT_MODEL.md](THREAT_MODEL.md) section 5. In short: the crank key and the registrar key
cannot move user funds; governance can pause at once, change `crank_fee` (≤ `executor_fee`), the
registrar and `bury_bps` after 72 h, name a successor that can take over after the same 72 h
(v1.3, below), and nothing else; the upgrade authority is the full
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
in the dry run, section 12, and 1,076,054 on mainnet on 10 October 2026), so about **0.031 SOL is
still in the deployer afterwards** (36,005,387 lamports on mainnet, where 1.040963 SOL had been
sent). An
earlier version of this page budgeted 0.005 SOL and asked for 1.01 SOL: with exactly that, the
CLI stopped before sending anything ("insufficient funds for spend + fee"). `dry-run.sh --tight`
now funds every key with exactly what this table comes to at the local validator's rent (6,960
lamports per byte there, so the deployer gets 1.407535720 SOL; the fee budget and the two other
keys are the same), and it has to pass.

The buffer is now written by `hd-devstack write-buffer` (section 7), which has no such check and
pays 5,267 lamports a write. The budget is kept so that `deploy.sh --cli-only`, where the CLI
writes the buffer itself, starts with the same funding. A deploy that continues an existing buffer
is budgeted `(chunks still to write + 4) x 145,000` lamports. For a build larger than `--max-len`
preflight sizes the budget for the build. `HD_DEPLOY_FEE_BUDGET=<lamports>` overrides the total:
with the paced writer 3,000,000 lamports were enough for an upgrade on the fork. Raising
`--cu-price` raises the budget in proportion (at 1,000,000 micro-lamports it is 313,315,000
lamports for a first run, computed from the formula, not run), which is friction exactly when a
deploy is stalled: the override is the way around it.

During a fresh deploy the SOL sits in the program **buffer** for a few minutes (about five on
10 October 2026). The buffer is
created holding the ProgramData rent for max-len (999,647,480 lamports, not the buffer's own rent
of 966,282,040), and `DeployWithMaxDataLen` drains the buffer back into the payer before it pays
for the ProgramData, so the peak is one ProgramData rent, not two. If a deploy stops half way,
that SOL is in the buffer, whose authority is the deployer: section 7 says how the deploy is
resumed or the buffer refunded.

**The first upgrade needs more SOL than a 2 SOL budget leaves.** An upgrade needs a temporary
buffer holding the rent of 45 + build size bytes (966,322,680 lamports for today's 190,048-byte
build) plus the fee budget: about 0.999 SOL in the deployer, which holds 36,005,387 lamports
since the deploy. That is a top-up of about **0.963 SOL**, and all of it but about 0.001 SOL of
fees comes back
when the upgrade executes (the buffer's rent goes to the spill account, the deployer). So the
working budget is about **2.07 SOL** if a bug found on the phone must be fixable on-chain: the
1.107 sent on 10 October, and 0.963 lent to the deployer for a few minutes per upgrade. With the
0.04 SOL for the wallet on the phone it is about 2.11 SOL. No upgrade has been run on mainnet.

Today's build fills 97% of the 196,608-byte `--max-len` (6,560 bytes of headroom). A build that
outgrows it must extend the ProgramData, and mainnet enforces a minimum extension of 10,240 bytes:
52,019,200 lamports, locked like the rest.

### What comes back, and what does not

Where the 1,106,889,000 lamports sent on 10 October 2026 were after the deploy and
`initialize_config` (from the two receipts; 1,040,963,000 went to the deployer):

| Where | Lamports | Does it come back? |
|---|---|---|
| ProgramData (45 + 196,608 bytes) | 999,647,480 | **only by closing the program for good** (below) |
| Program account (36 bytes) | 833,120 | never: the loader cannot close a Program account |
| Config (256 bytes) | 1,950,720 | never: no instruction closes it |
| Executor float | 1,450,240 | never: nothing can be withdrawn from the Executor (section 9) |
| deploy and init fees | 1,076,054 (1,053,286 for the buffer, 10,297 for the deploy, 7,335 and 5,136 for the init) | never |
| left in the deployer | 36,005,387 (still there at 21:40 UTC) | yes: a plain transfer |
| crank fee payer | 50,963,000 (50,902,527 after the first shift) | what is left of it: it is spent in use (below) |
| governance | 14,963,000 (it has paid for no transaction yet) | what is left of it: a pause or a proposal costs about 5,100 to 5,500 lamports |

So of the 1.107 SOL: **999,647,480 lamports (0.99965 SOL) are locked** in the program's rent,
**5,310,134 lamports (0.0053 SOL) are gone for good**, and 101,931,387 (0.102 SOL) were liquid
that evening. The rows add up to one lamport more than was sent: the deploy receipt records
999,647,481 lamports in the buffer, one more than its creating transaction put in, and the
deploy handed the buffer's balance back to the deployer. Where that lamport came from was not
looked up.

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
carries one rig's fresh heartbeat costs the crank one transaction signature, one secp256r1
signature and the priority fee. On the fork that was 10,047 lamports at a priority price of 1,000
micro-lamports per CU. On mainnet on 10 October 2026 it was 10,053 at that price and 11,028 to
11,082 at 20,000, the floor set on the running service that evening
([MAINNET.md](MAINNET.md#what-it-cost)). `deploy/railway/crank/crank.toml` lets the price follow
the network up to 50,000, where the same transactions would have cost about 12,600 to 12,700
(computed from their compute-unit limits). The program reimburses `crank_fee` = 7,000: 3,053
lamports lost per dig at a price of 1,000, 4,028 to 4,082 at 20,000 and about 5,600 to 5,700 at
the cap, and the default caps allow
at most 20 digs a shift. At a price of 1,000 the reimbursement
covers the fee from three rigs per transaction up (crank/README.md, from the fork's figures). At
the 20,000 the service has run with since 10 October only a full batch of five fresh heartbeats
comes near it: about 6,760 to 7,040 lamports a rig against the 7,000 (computed for
a compute-unit limit of 38,000 to 52,000 a rig; no transaction with more than one rig has run on
mainnet). Never reimbursed: a dig
that lands after its round or twice (10,038 to 11,042 lamports each on 10 October), the
heartbeat records on nights when the cost gate stays closed (about 10,000 lamports each, every
third round: about 1,240,000 lamports over an 8-hour night for one rig), BREAK and FREEZE (about
10,100 each; the one BREAK on mainnet cost 10,100), Stack check-ins, and the ShiftLog rent of a
shift the crank seals (1,300,480
lamports, which `close_shift_log` returns to the payer after 30 days; nothing sends that
instruction yet). Expect roughly 0.001 to 0.003 SOL a night for one phone (computed). The one
measured figure is far from a night: the first shift, with five digs, one missed round, two
refused second attempts and one BREAK, cost the crank 60,473 lamports.

**The wallet on the phone is a fourth key to fund.** The first clock-in with the default build
moves about 0.029 SOL: 20,200,000 lamports into the wallet's own ORE Automation, and the rent of
the Rig (2,600,960), of ORE's Automation (1,463,040) and of ORE's Miner (4,470,400), plus ORE's
10,000 checkpoint reserve. Each sealed shift then costs 1,300,480 of ShiftLog rent. About 0.04 SOL
covers a week of tests on one phone. What comes back: the Automation's balance and rent through
"Take it back" in the app, the Rig's rent less the 812,800 lamports that stay in the
tombstone when the rig is closed, and each ShiftLog's rent from 30 days after its shift ended
(`close_shift_log`, which neither the app nor the crank sends yet). What does not: the
tombstone, 10,000 lamports per dug round (7,000 to the crank, 3,000 into the Executor), what ORE
keeps of the SOL placed on squares (10.9% of a square that did not win and 1% of one that did,
[ORE.md](ORE.md)), and the fees. Not known: whether the lamports in the Miner, which is ORE's
account, can ever be taken back.

On mainnet on 10 October 2026 the first clock-in was made with the demo build of section 10.7 and
moved 13,604,400 lamports: the same three rents and the reserve, 5,050,000 into the Automation
where the default build puts 20,200,000, and a fee of 10,000. Both sealed shifts cost 1,300,480
of ShiftLog rent and 5,000 of fee. "Take it back" returned the Automation's 5,027,040 lamports.
Closing the rig has not been run on a phone. The whole evening's balance sheet, 11,193,320
lamports for the wallet, is in [MAINNET.md](MAINNET.md#what-it-cost).

## 4. Parameters and why

| Parameter | Value | Why |
|---|---|---|
| `--max-len` | **196,608** (192 KiB) | Chosen as 1.76x the 111,600-byte v1.1 build, to hold the SKR instructions without an extend. They are in now: today's v1.3 build is 190,048 bytes, which leaves 6,560 bytes (3%). Rent 0.9996 SOL. 2x (223,200) would cost 1.1347 SOL and 256 KiB 1.3326 SOL. If a later build outgrows it, `deploy.sh --mode upgrade` extends the ProgramData with `solana program extend` before the upgrade (section 7, step 6): by the shortfall, and by at least 10,240 bytes (52,019,200 lamports for 10,240 bytes, paid by the deployer and locked like the rest). |
| `executor_fee` | **10,000** lamports | **Immutable** (no instruction changes it), and every rig's Automation must use exactly this Discretionary fee (`dig` skips any other value). It must cover the crank's measured cost per rig in a batch with room for congestion: crank/README.md measures 5,493 (v1, 11 rigs) to 7,541 (legacy, 2 rigs) lamports per rig for a fresh-heartbeat dig on the fork, and 10,047 for a transaction that carries a single rig, which the fee does not cover (section 3; on mainnet on 10 October 2026 a single-rig dig cost 10,053 to 11,082); ORE's own executor charges 7,000 and a sampled third-party one 12,000. 10,000 is ECONOMICS.md's figure and lets `crank_fee` rise to 10,000 under congestion without touching user Automations. At 0.001 SOL per dig it is 1% of the per-round spend. |
| `crank_fee` | **7,000** lamports | Covers a v0 + lookup-table batch (5 rigs: 5,000 secp256r1 + 1,000 signature share + priority ≈ 6,040; 3 rigs ≈ 6,723). `crank_fee ≤ executor_fee` is enforced by the program. The 3,000 lamports per dig it leaves behind accrue in the Executor, which only ever pays ORE's CHECKPOINT_FEE top-ups and reimbursements, so the float grows with use. With one rig per transaction the crank pays 10,047 on the fork (10,053 to 11,082 on mainnet on 10 October 2026) and gets 7,000 back; the fee covers it from three rigs per transaction up at a priority price of 1,000, and only for a full batch of five at the 20,000 the service runs with since that day (section 3). Raise it (timelocked) with `governance.sh propose --crank-fee N` if priority fees stay high. |
| `bury_bps` | **0** | Stored, but no instruction reads it (`INTERFACE.md` §10): the Executor's surplus is not routed to Bury, and the v1.2 Bury auction sells SKR forfeits only. |
| `ore_layout_hash` | `cc9b3521…48aa91` | `sha256(heads_down::ore::LAYOUT_PREIMAGE)`, computed by the program crate inside the tool; the program refuses any other value. |
| Executor float | **rent-exempt(0) + 100,000 + 100 x crank_fee** = 1,450,240 lamports on mainnet | rent so the PDA exists, the program's own reserve (10 x CHECKPOINT_FEE, which reimbursements never touch), and 100 reimbursements of slack. A dig pays the Executor 10,000 before the program reimburses 7,000, so reimbursements are self-funding; the slack absorbs late third-party checkpoints that take 10,000 each. |
| priority fee | 100,000 micro-lamports/CU | deploy and admin transactions; at this price a buffer write (2,670 CU) pays 267 lamports of priority fee on top of its 5,000-lamport signature fee, and the whole deploy pays about 0.00005 SOL of priority fees (53,584 lamports in the dry run) out of about 0.0011 SOL of fees (1,076,054 lamports of fees in all on mainnet on 10 October 2026). |
| `--write-rate` | **1** transaction a second | Helius' free plan allows one `sendTransaction` a second. The writer waits that long after each write, so it stays under the limit; a plan that allows more can be given more. On the local fork, behind a proxy that lets one `sendTransaction` a second through, about 185 writes took 188 s and none was refused. On mainnet on 10 October 2026, through Helius' free plan, 198 writes were sent and confirmed in 288 s: none signed again, no slow-down. |
| `--max-sign-attempts` | 20 | only for `deploy.sh --cli-only`, where the CLI writes the buffer itself: 20 signing rounds before it gives up. The default path does not use it: `write-buffer` signs a write again whenever its blockhash expired. |

The program is built with `programs/heads-down/scripts/build.sh` (`--features mainnet`: the real
SGT anchors; `--arch v3`) by `cargo-build-sbf` 4.1.0, which produces **SBPF v3** (ELF `e_flags`
3; `HD_SBF_ARCH=v0` builds the v0 fallback). Mainnet accepts SBPF v3 deployments since slot
428,976,000; `preflight.sh` checks that feature on every run and stops if it is not active.
SIMD-0500 ("disable deployment of SBPF v0, v1 and v2") is **inactive** on mainnet today and does
not touch a v3 build: preflight stops on it, active or pending, only for a v0 to v2 build.

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
  Helius itself: the services have run against it since 10 October 2026, and what they used was
  not read from Helius' meter):

  | Service | Idle, credits a day | A night with one phone adds |
  |---|---|---|
  | crank, the baked `crank.toml` | about 108,500 (3.3M in 30 days: the free plan lasts about 9 days) | about 70,000 with three dig passes a round, which is what a 20-slot dig window gives; about 51,000 with the two passes of the 80-slot window and 40-slot retry (with lookup tables on, the poller also scans the rigs between dig windows while a phone is heads down; computed from the code, not measured) |
  | crank, with the one-phone overrides of `deploy/railway/crank/.env.example` | about 30,500 (0.9M in 30 days) | about 50,000 with three dig passes a round; about 36,000 with two, which is how the service has run since the evening of 10 October 2026 (section 10.4). Both for 8 hours of 75 s rounds; rounds took 63 s that evening, which makes the second figure about 43,000 |
  | indexer at `INGEST_INTERVAL_S=300` | about 2,000 | about 5,000 |
  | indexer at `INGEST_INTERVAL_S=30` | about 12,600 | about 17,000 |
  | registrar | 0 (it uses a keyless RPC, section 10.4) | 0 |

  Before the changes of 2026-10-04 the two services used about 430,000 credits a day idle (crank
  303,000, indexer at 30 s 125,000), and an
  unfunded crank more. So: with the one-phone overrides and the indexer at 300 s, an idle stack
  uses about the whole free plan in a month, and every test night comes on top. **Run the crank
  for test nights and for the recorded demo, and remove its deployment in between**, or put Heads
  Down on a Helius account of its own. Watch the credit meter in the Helius dashboard.
- **Do not share the key with another project.** Credits and rate limits are counted per account,
  so another project on the same key spends the same 1M credits and the same one send a second.
  It happened on 10 October 2026: the key first used here was shared with another project, its
  monthly credits ran out, and Helius answered every call with `max usage reached` until the key
  was replaced.
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
| program id | the program keypair's address is not the id the program crate and the crank are built for |
| program .so | not an SBF ELF; its sha256 and the solana-verify hash are printed |
| SBPF version | the `.so` names no SBPF version in its ELF flags, or it is an SBPF v1 or v2 build whose feature is not active |
| max-len | fresh: smaller than the build or above 10 MiB. Upgrade: a build above 10 MiB; a build that outgrows the deployed ProgramData is a warning that says how many bytes the upgrade adds |
| buffer | the deploy's per-commit buffer address holds something that is not the deployer's buffer for this build: another authority, another size, or not a buffer. A buffer that is the deployer's is counted, and the line says how many chunks are still to write |
| SIMD-0500 | active or pending for an SBPF v0-v2 build (or SBPFv3 not enabled for a v3 build) |
| program account | fresh: something already lives at `HDn4vg…`. Upgrade: nothing is deployed there, or the deployer is not its upgrade authority |
| deployer balance | below what is still needed: ProgramData(max-len), less what the deploy's buffer already holds, + Program + fees for the chunks still to write + Config + float, all from `getMinimumBalanceForRentExemption` |
| ORE upgrade slot | ORE's ProgramData slot is not 452,682,055 (docs/ORE.md) |
| ORE program hash | ORE's bytes are not the verified build `9dbd2e0d…` (commit `48c203bd`) |
| ORE singletons | Board/Treasury/Config/Round owner, size or discriminator differ from 40/105, 48/104, 232/101, 952/109 |
| ORE user accounts | a sampled Automation or Miner from recent ORE transactions is not 160/100 or 752/103 |
| init params | `executor_fee` 0 or `crank_fee > executor_fee` |

Warnings (crank or governance key underfunded, no Automation sampled, the registrar's session
secret missing, the current ORE Round account not there yet) do not block.

Real run against mainnet on 2026-10-04, through Helius, before funding: the verdict is NO-GO for
exactly that one reason.

```text
heads_down preflight: mainnet, mode fresh, 2026-10-04T18:15:00Z
PASS  helius.env             present, mode 600, HELIUS_API_KEY well-formed (36 chars; value not shown)
PASS  key dir                ~/.config/heads-down/mainnet (700)
PASS  deployer.json          9DSVM862oJrstiPmmQmgqXb7AkuXrKJtYgd1fwbeqeeW (600)
PASS  crank-payer.json       5Xec1ZUwXcB2ZGeWqqBHrxaHT4WQrGVUgH9xmqgC1kzk (600)
PASS  governance.json        37u9LWbPrzQFkfL6oXGoSfq9souRggVtGvYszRXHezXN (600)
PASS  registrar.json         9deCPaA6iML39zQw4mptBeHRkm7DE6oVWGijdA9c2zgo (600)
PASS  session secret         present (600; value not shown)
PASS  program keypair        HDn4vgLWFLLdexKEwfZwRHjWtizNvdqFteLbMsE67F9p (~/.config/heads-down/heads_down-program-keypair.json, 600)
PASS  deployer address       9DSVM862oJrstiPmmQmgqXb7AkuXrKJtYgd1fwbeqeeW is the funded address
PASS  crank-payer address    5Xec1ZUwXcB2ZGeWqqBHrxaHT4WQrGVUgH9xmqgC1kzk is the funded address
PASS  governance address     37u9LWbPrzQFkfL6oXGoSfq9souRggVtGvYszRXHezXN is the funded address
PASS  git                    commit 534638af92ff037ffb1b573e888800ce5d44a914, clean tree
PASS  program .so            190048 bytes, sha256 07dd870a879faac20d4932f297da3b50719c9b900761409250c8841cc7b8527d (programs/heads-down/target/deploy/heads_down.so)

chain checks via https://mainnet.helius-rpc.com (Helius, key from helius.env)
[hd-devstack] cluster mainnet via https://mainnet.helius-rpc.com/<redacted> (genesis 5eykt4UsFv8P8NJdTREpY1vzqKqZKvdpKuc147dw2N9d)
PASS  cluster                mainnet via https://mainnet.helius-rpc.com/<redacted> (genesis 5eykt4UsFv8P8NJdTREpY1vzqKqZKvdpKuc147dw2N9d)
PASS  program id             HDn4vgLWFLLdexKEwfZwRHjWtizNvdqFteLbMsE67F9p (keypair = program crate = crank)
PASS  program .so            190048 bytes, sha256 07dd870a879faac20d4932f297da3b50719c9b900761409250c8841cc7b8527d, program hash 0154706c62cb7bb6aacf463501e49b016b87809ed7193fcae08afc4e3f891b58, SBPF v3
PASS  max-len                196608 bytes: 6560 bytes (3%) of headroom over this build
PASS  SIMD-0500              inactive ; build is SBPF v3 and SBPFv3 deployment is active since slot 428976000
PASS  program account        nothing deployed yet: fresh deploy
INFO  buffer                 no buffer for this commit yet: the deploy creates one (198 of 198 chunks to write)
INFO  rent                   ProgramData(196653) 0.999647480 SOL, Program 0.000833120 SOL, a new buffer 0.999647480 SOL (the rent of 196653 bytes, what the Solana CLI puts in for this mode), Config 0.001950720 SOL, Executor rent-exempt(0) 0.000650240 SOL
INFO  deploy cost            1.032815600 SOL: ProgramData 0.999647480 + Program 0.000833120 + fees 0.032335000 (the buffer holds the ProgramData rent while the deploy runs; the final transaction moves it into the ProgramData)
INFO  init cost              0.003500960 SOL: Config 0.001950720 + Executor float 0.001450240 (target 1450240 = rent 650240 + reserve 100000 + 100 x crank_fee 7000; holds 0) + fees 0.000100000
FAIL  deployer balance       9DSVM862oJrstiPmmQmgqXb7AkuXrKJtYgd1fwbeqeeW holds 0.000000000 SOL < 1.036316560 SOL needed: send at least 1.036316560 SOL
PASS  ORE upgrade slot       452682055 = pin 452682055
PASS  ORE program hash       9dbd2e0d232563f0e2b3eae89bf7d6f55d483c464863adb4f46d117f427ca695 = verify.osec.io 48c203bd
PASS  ORE singletons         Board 40/105, Treasury 48/104, Config 232/101, Round 952/109 (round 428227)
PASS  ORE user accounts      13 Automations (160/100) and 19 Miners (752/103) from recent ORE transactions match
PASS  init params            executor_fee 10000 (immutable), crank_fee 7000 (<= executor_fee; +3000 per dig accrues to the Executor)
INFO  ore_layout_hash        cc9b3521eaa022a05fd9c38f745b8844a740ca9e3243a91f1bbda66a3448aa91 = sha256(heads_down::ore::LAYOUT_PREIMAGE)
INFO  heads_down Config      not initialized (init-config.sh creates it)
INFO  Executor PDA           By3vJvQUsCLexnv7VqHuEhtZZCmpmjZjfhxvqCnWPkge holds 0 lamports
WARN  crank-payer            5Xec1ZUwXcB2ZGeWqqBHrxaHT4WQrGVUgH9xmqgC1kzk holds 0.000000000 SOL (recommended 0.050000000): fund 0.050000000 SOL before starting it
WARN  governance             37u9LWbPrzQFkfL6oXGoSfq9souRggVtGvYszRXHezXN holds 0.000000000 SOL (recommended 0.010000000): fund 0.010000000 SOL before starting it
PASS  registrar              9deCPaA6iML39zQw4mptBeHRkm7DE6oVWGijdA9c2zgo holds 0.000000000 SOL (recommended 0.000000000)

NO-GO: 0 local failure(s), chain checks FAILED (see FAIL lines above). Nothing was sent.
```

With the deployer funded, that line turns PASS and the verdict is GO: it was on 10 October 2026,
inside `deploy.sh` (the receipt's `build.preflight`). The first try that day did not get that
far. It stopped at its first chain check with `getGenesisHash: http: error sending request`,
because the founder's connection had dropped (ping showed round trips of 0.8 to 1.8 s, and one
request to Helius timed out). Nothing had been sent, and running `deploy.sh` again a few minutes
later was all it took. Everything ORE-related
passes against mainnet today, with the pin on the build ORE deployed on 2026-10-02. On 2026-10-03
this run answered NO-GO on the two ORE lines, which is how we learned of that upgrade
([ORE.md](ORE.md), section 1). The three `address` lines compare the real key files with the
addresses in the funding table.

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
7. Runs the Solana CLI (Agave 4.1.2 in the rehearsal; `deploy.sh` records `solana --version` in
   the receipt but does not check it), through a private CLI config holding the Helius URL:
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

**The run on mainnet, 10 October 2026** (`deploy.sh --yes`, through Helius' free plan, at commit
`d67a1a40c2a0`; figures from
[the receipt](../deploy/receipts/mainnet/20261010T184116Z-fresh-d67a1a40c2a0.json)):

| Step | What happened |
|---|---|
| preflight | the first run stopped here on a network error, before anything was sent (section 6); the second run answered GO |
| buffer | `26iQMRCbUqtVBm2XC5SztLtJc8PPxgqiNMDrSqyGpxiZ` created; the receipt records 999,647,481 lamports in it, one more than the ProgramData rent its creating transaction put in (section 3) |
| writes | 198 sent, 198 confirmed, 0 signed again, 0 slow-downs, at one a second with a compute-unit limit of 2,670; 288 s; 1,053,286 lamports of fees with the creation |
| deploy | one transaction by the Solana CLI (`solana-cli 4.1.2`), 10,297 lamports, slot 455,359,196 |
| verify | the ProgramData holds the build byte for byte: 190,048 bytes, program hash `0154706c…891b58`; the upgrade authority is the deployer |
| the deployer | 1,001,544,182 lamports less than before the run, 39,418,818 left (36,005,387 after `init-config.sh`) |

The writes took 288 s where the local fork took a little over three minutes, and Helius' free
plan refused none of them. The indexer was already reading the chain through Helius while they
ran (section 10.2). The typed confirmation was not used: the run passed `--yes`.
[MAINNET.md](MAINNET.md#what-is-deployed) has the transactions.

If it stops part way, nothing is lost: the buffer is the deployer's and keeps the chunks that
landed and the rent. Re-run `deploy.sh` at the same commit and it continues: preflight says how
many chunks are still to write and asks for no SOL that is already in the buffer. Or take the rent
back with
`scripts/mainnet/solana.sh -- program close <BUFFER> --recipient 9DSVM862oJrstiPmmQmgqXb7AkuXrKJtYgd1fwbeqeeW`:
every lamport in the buffer returns; the fees of the writes that landed (5,267 lamports each) do
not. If the writer stops with `the payer … holds …`, send the deployer the amount it names and run
`deploy.sh` again. If it stops with `no write was seen to land for 300 s`, the line above it gives
the deployer's balance and the counts: check the RPC and the priority fee (`--cu-price`), then run
it again.

If the final transaction landed but the receipt was not written (an RPC error right after it), a
re-run in fresh mode is refused because the program exists. Write the receipt by hand, from the
repository root at the commit that was deployed. `<UTC>` is the time stamp in the names of the
files that run left in `~/.local/share/heads-down/deploy/`; the signature is in the last line of
`deploy-mainnet-<UTC>.out` there (leave `--signature` out if there is none). The command reads
over the public RPC, needs no key and signs nothing. Its arguments were checked against mainnet
before the deploy (it stopped at `is not a deployed upgradeable program`). The receipt of
10 October 2026 was written by the same command as `deploy.sh` runs it; run by hand like this,
it has not written one:

```bash
export PATH="$HOME/.local/share/solana/install/active_release/bin:$HOME/.cargo/bin:$PATH"
HD_CLUSTER=mainnet scripts/devstack/tool/target/release/hd-devstack verify-deploy --mode fresh \
  --so programs/heads-down/target/deploy/heads_down.so \
  --program-id HDn4vgLWFLLdexKEwfZwRHjWtizNvdqFteLbMsE67F9p \
  --deployer 9DSVM862oJrstiPmmQmgqXb7AkuXrKJtYgd1fwbeqeeW --max-len 196608 \
  --signature <signature> \
  --buffer-write ~/.local/share/heads-down/deploy/write-buffer-mainnet-<UTC>.json \
  --meta git_commit=$(git rev-parse HEAD) --meta git_dirty=0 \
  --meta build_script=programs/heads-down/scripts/build.sh --meta cargo_features=mainnet \
  --meta "solana_cli=$(solana --version)" \
  --meta "cargo_build_sbf=$(cargo-build-sbf --version | tr '\n' ' ' | sed 's/ *$//')" \
  --meta cu_price_micro_lamports=100000 --meta preflight=GO \
  --meta buffer_written_by=hd-devstack-write-buffer \
  --out deploy/receipts/mainnet/<UTC>-fresh-$(git rev-parse HEAD | cut -c1-12).json
```

Flags: `--write-rate N` changes the pace, `--cli-only` lets the CLI write the buffer itself,
`--public-rpc` uses the public RPC.

Why not the CLI alone: with `--use-rpc` it sends all its writes 10 ms apart and never sends one
again inside a blockhash window. On the local fork, behind a proxy that lets one `sendTransaction`
a second through (the free Helius plan's limit), 3 signing rounds took about 260 s, landed about
15 of 198 writes, and ended with `Max retries exceeded`. The paced writer then continued that
same buffer: about 185 writes in 188 s, none refused. That comparison was made behind the proxy,
which copies the answer Helius documents. Against Helius itself the paced writer has run once,
on mainnet (above), and was never refused; the CLI alone (`--cli-only`) and a deploy continued
after a stop have not run there.

Anyone can check the deployed bytes: `solana-verify get-program-hash HDn4vg… -um` equals the
receipt's `onchain.program_hash`, and the first 190,048 bytes of
`solana program dump HDn4vg… -um` hash to `so.sha256`
([MAINNET.md](MAINNET.md#check-it-yourself) has the commands and what they printed).

**Rebuilding does not check the source against those bytes, except in one place.** The recorded
commit, built with the recorded toolchain, gives `so.sha256` in the directory the deployed file
was built in (the preflight of 4 October in section 6 printed the same hash there). In another
directory it does not. On 10 October 2026 the same sources built in two other directories of the same machine
gave two more files of 190,048 bytes with other hashes; one was compared with the deployed file
and differs in 968 bytes (MAINNET.md). Why the directory changes the output was not
investigated, and no build in a pinned container, which a third party could repeat, has been
set up. Until one is, a signer of an upgrade (section 14) can compare a buffer with the
receipt, and cannot confirm the receipt's hash from the source on another machine.

## 8. initialize_config

```bash
scripts/mainnet/init-config.sh          # then: scripts/mainnet/governance.sh show
```

`initialize_config` (tag 0, `programs/heads-down/vectors/instructions.json`) with accounts
`[upgrade authority (signer, pays rent), Config PDA, ProgramData 3jjGZ8EE8DJcRag9eTFMMxStNo55PHLa5xVPktW52WPZ, System]`.
The tool first checks that `deployer.json` is the upgrade authority recorded in ProgramData
(the program checks it too), prints the plan (governance and registrar public keys, the fees,
`bury_bps`, the layout hash and the Config rent), asks you to type
`initialize heads_down config`, sends it with a simulated compute limit and a priority fee,
reads the Config back and compares every field, then funds the Executor float. The receipt
goes to `deploy/receipts/mainnet/<UTC>-init.json`. **Commit it.**

On mainnet on 10 October 2026
([receipt](../deploy/receipts/mainnet/20261010T184715Z-init.json)): `initialize_config` landed in
slot 455,359,343 for a fee of 7,335 lamports and created the Config with `executor_fee` 10,000,
`crank_fee` 7,000, `bury_bps` 0, governance `37u9LWbP…` and registrar `9deCPaA6…`, not paused.
The transfer of 1,450,240 lamports to the Executor PDA landed in slot 455,359,384 for 5,136. The
deployer held 36,005,387 lamports afterwards. No governance transaction has been sent on
mainnet: at 21:40 UTC that day the governance key still held the 14,963,000 lamports it was
funded with, and the deployer its 36,005,387.

The instruction builder is tested byte for byte against the program's golden vectors
(`scripts/devstack/tool/src/hd.rs`, `initialize_config`, `propose_config`, `apply_config`).

## 9. The Executor float

- **What drains it:** only ORE's CHECKPOINT_FEE top-ups (10,000 lamports, when a third party
  checkpointed a Miner late; the crank checkpoints early to avoid that) and `crank_fee`
  reimbursements, which never take it below rent-exempt(0) + 100,000.
- **What fills it:** every dug rig-round pays `executor_fee` (10,000) in and reimburses
  `crank_fee` (7,000) out: +3,000 per dig. On mainnet it started at 1,450,240 lamports on
  10 October 2026 and held 1,465,240 after the first five digs.
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
| `indexer` | `https://indexer-production-88dc.up.railway.app` | - | Postgres. Runs without `RPC_URL` until the program is initialized (it has had one since 2026-10-10) |
| `dashboard` | `https://dashboard-production-b80c.up.railway.app` | - | the indexer's address, at build time |
| `registrar` | `https://registrar-production-71d0.up.railway.app` | `/data`, 1 GB | its key, its session secret, an app certificate digest, `HD_SIWS_DOMAIN` |
| `crank` | `https://crank-production-21c2.up.railway.app` | `/data`, 1 GB | **the deployed and initialized program, a funded fee payer, its key and the Helius key** (running since 2026-10-10, 18:48:47 UTC) |

Since 10 October 2026 all five are running, the crank included.
[MAINNET.md](MAINNET.md#services) says what each showed that day.

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

**How it went on 10 October 2026.** Postgres, the indexer, the dashboard and the registrar were
running before the deploy. The indexer's `RPC_URL` was set at 17:36 UTC, before the deploy and
not after `initialize_config` as step 6 has it; the deploy's 198 writes, an hour later, were
not slowed (section 7). The crank's image was first built without its key, on purpose, to test
the build: Railway built it, and the entrypoint stopped it with `HD_CRANK_KEYPAIR_JSON is not
set`, as designed. With the key set, the crank started at 18:48:47 UTC, after
`initialize_config`, with the fee payer `5Xec1ZUw…`. Its log line
`key file removed; hd-crank (pid 13, uid 10001) on 0.0.0.0:8787` is the check of section 10.5.
The first dig and what the evening changed in the crank's settings are in
[MAINNET.md](MAINNET.md#the-first-shift-10-october-2026).

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

Where the five stand on 10 October 2026: the founder sealed `HD_REGISTRAR_KEYPAIR_JSON`,
`HD_SESSION_SECRET`, the crank's `HELIUS_API_KEY` and the indexer's `RPC_URL`.
`HD_CRANK_KEYPAIR_JSON` was set from the key file through the CLI's standard input, as in the
command above, and its sealing was asked for. This page does not record that it was sealed:
founder checklist, step 11.

### 10.4 Variable matrix

`S` = sealed secret, `R` = Railway reference, `-` = plain.

| Variable | crank | registrar | indexer | dashboard | Value |
|---|---|---|---|---|---|
| `HELIUS_API_KEY` | S | | | | the key |
| `HD_CRANK_KEYPAIR_JSON` | S | | | | contents of `crank-payer.json` |
| `PORT` | 8787 | 8080 | 8080 | 8080 | the domain's target port |
| `RUST_LOG` | `info,hyper=warn,reqwest=warn` | `info` | | | |
| `HD_CRANK_DIG_DEPLOY_MARGIN_SLOTS` | `80` | | | | set on 2026-10-10 at about 18:59 UTC. The crank sends a round's digs in a window before the round's end. With the 20 slots the image was built with, the first dig on mainnet landed 5 slots after its round had ended and was skipped at the crank's cost, and the second landed 11 slots before its round's end. With 80, the four digs that followed landed 49 to 77 slots before theirs ([MAINNET.md](MAINNET.md#what-the-first-night-found)) |
| `HD_CRANK_DIG_CU_PRICE_MICRO_LAMPORTS` | `20000` | | | | set in the same restart: the floor of the dig's priority fee, which had settled on the 1,000 the image was built with. Which of the two changes made the digs land in time is not known. At 20,000 a dig that carries one rig cost 11,028 to 11,082 lamports against the 7,000 the program pays back |
| `HD_CRANK_DIG_RETRY_AFTER_SLOTS` | `40` | | | | set on 2026-10-10 at about 19:00 UTC. With the 6 slots the image was built with, the crank signed a second attempt while the first was still on its way; both landed, and the second was refused, 10,053 and 11,042 lamports for nothing. No second attempt has landed since |
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
| `RPC_URL` | | | S | | unset until `initialize_config` has landed, then `https://mainnet.helius-rpc.com/?api-key=<key>` (set since 2026-10-10, 17:36 UTC) |
| `INGEST_INTERVAL_S` | | | - | | `300` between test sessions, `30` to `60` while recording (section 5); the code's default is 30 |
| `SNAPSHOT_EVERY_N_POLLS` | | | - | | default `20`. The indexer scans the program's accounts when a poll finds a new transaction, and otherwise every Nth poll as a safety net (100 minutes at an interval of 300 s) |
| `ORE_ROUNDS_SINCE` | | | - | | a unix time; optional. It narrows how far back ORE's rounds are read (default 14 days). On 2026-10-04 the first read asked api.ore.com for all 14 days in one poll, got HTTP 429 and stored nothing; since 2026-10-10 the indexer reads `ORE_API_PAGES_PER_PASS` pages a pass (default 10), keeps each page, and takes a 429 as "later", so a first start finishes in about 22 passes (up to two hours at `INGEST_INTERVAL_S=300`; computed, not measured against the real API). The service carried this override; with that fix live it was removed on 2026-10-10 |
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
file. For one phone on Helius' free plan, set the six overrides of the block "one phone on
Helius' free plan" in `deploy/railway/crank/.env.example` on the service: `HD_CRANK_ALT_ENABLED=false`,
`HD_CRANK_STACK_ENABLED=false`, `HD_CRANK_STACK_INIT_BURY_VAULT=false`,
`HD_CRANK_END_SHIFT_ENABLED=false`, `HD_CRANK_CLEANUP_ENABLED=false` and
`HD_CRANK_INTAKE_RIG_FETCHES_PER_SECOND=1`. They cut the idle cost from about 108,500 to about
30,500 credits a day, park no rent in a lookup table (one rig fits a transaction without one), and
keep the crank from paying the Bury vault's rent (3,114,040 lamports that never come back). A
misspelled `HD_CRANK_*` name is only a warning at start: read the first log lines after setting
them, or run `hd-crank config`. An empty `HELIUS_API_KEY` stops the crank with `HELIUS_API_KEY is
set to an empty value`.

**The crank's timing on mainnet.** Since the evening of 10 October 2026 the service also carries
the three `HD_CRANK_DIG_*` variables of the matrix above: an 80-slot dig window, a retry after 40
slots and a priority floor of 20,000. They were found in the first shift, with one rig, in six
rounds, and they override what the image was built with. `crank/README.md` documents the
crank's defaults and how it times a dig. When the service runs an image whose `crank.toml` has
these values, the variables repeat it and can be removed; until then removing one puts the
image's value back. A variable takes effect when the crank restarts. On 10 October it restarted
twice during the shift: the phone reconnected by itself both times, and its heartbeats were
accepted again within the next round.

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
in the deploy log: the line `key file removed; hd-<service> (pid N, uid 10001) on 0.0.0.0:<port>`
(section 12, last paragraph of "Docker-free checks", and deploy/railway/README.md). The crank's
first start on 10 October 2026 logged
`key file removed; hd-crank (pid 13, uid 10001) on 0.0.0.0:8787`.

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

**The cost ceiling.** A shift digs only while ORE's cost per ORE (`ema_ev`) is within the plan
the build arms: 0.53 SOL per ORE by default, under a wallet-signed ceiling of 0.67. On 2026-10-10
the live figure was about 0.713 SOL per ORE, so a build with the defaults clocks in and never
digs (the crank logs `cost_gate`). The build for the recorded demo adds four properties to the
command above:

```bash
  -Pheadsdown.policy.planMaxEvCost=1000000000 -Pheadsdown.policy.capMaxCost=1200000000 \
  -Pheadsdown.policy.shiftBudgetLamports=5000000 -Pheadsdown.policy.weeklyBudgetLamports=35000000
```

That build places real SOL at up to 1.0 SOL per ORE, under a ceiling of 1.2: at most 0.005 SOL a
shift (five digs of 0.001 SOL) and 0.035 SOL a week. `android/README.md` ("Clock-in policy for
demo takes") lists every policy property.

**It is the build the first shift ran on.** On the evening of 10 October 2026 the figure was
0.690 to 0.704 SOL per ORE, still above the default plan. The phone clocked in with the demo
policy, the crank dug five rounds of 0.001 SOL on 4 squares, and the fifth dig used up the
shift's 0.005 SOL. The caps the wallet signed carry the executor fee of each dig inside them:
1,010,000 lamports a round, 5,050,000 a shift, 35,350,000 a week, under a ceiling of
1,200,000,000 lamports per ORE ([MAINNET.md](MAINNET.md#the-first-shift-10-october-2026)). The
default build has not dug on mainnet.

**The app's identity.** `identityUri` is the site the app names to the wallet as its identity
(Mobile Wallet Adapter). Solana Mobile's test wallet shows it on the connect and sign-in prompts,
not on the transaction prompt. Jupiter Mobile is the one other wallet tried (below); Solflare,
Phantom and Seed Vault have not been. Through its host it is also the Sign In
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

The page carries a dated status line. In the repository it was changed when the program was
deployed on 10 October 2026, and again for the first shift a phone ran that evening; the
published page shows a change only once the command above has been run. Keep it true: founder
checklist step 10.

**What a wallet can and cannot check.** The identity is a string the app hands to the wallet.
Nothing ties it to the app, so another app can name the same address, name and icon. On the
emulator, Solana Mobile's test wallet showed the name and the address and "Verification failed". A
wallet can tie the two together through Android's Digital Asset Links: a file at
`https://<identity host>/.well-known/assetlinks.json` naming the app's package and the SHA-256 of
its signing certificate. That file is fetched from the host's root, so for `oojae.github.io` it
belongs in the `OoJae/oojae.github.io` repository (the account's own site, which would then vouch
for every project on that host), not in this project's page under `/heads-down/`. It also needs a
release signing key, which does not exist yet: release builds are unsigned and debug builds carry
the debug key. Solana Mobile's test wallet signs and sends without either. On the Redmi 14C on
10 October 2026, Jupiter's wallet showed "Could not verify request" on its connect prompt, and
still connected and signed the sign-in. The same evening it signed four transactions on mainnet:
two clock-ins, a clock-out and "Take it back" (MAINNET.md). Solflare,
Phantom and Seed Vault have not been tried; the protocol lets a wallet decline an identity it
cannot verify.

## 11. Monitoring and alerts

Railway's healthcheck runs only when a deployment starts, so it is not monitoring. None of the
checks and alerts below is set up yet (10 October 2026): nothing watches the services or the
balances. The thresholds are still the ones to use; that evening the Executor held 1,465,240
lamports and the crank's fee payer 50,902,527.

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
`--max-len`, buffer and receipt. It builds the program itself when the checkout has no build.

`scripts/mainnet/selftest.sh` runs first. Before the deploy that counts, two fresh deploys are
stopped part way by killing the buffer writer: one is refunded with `solana.sh -- program close`
(a throwaway deployer of its own, checked to the lamport), the other is continued by re-running
`deploy.sh` with no top-up. Then `init-config.sh`, `scripts/devstack/smoke.sh`, an upgrade through
`scripts/devstack/rate-limit-proxy.py` (HTTP 429 beyond 4 `sendTransaction` a second, in the form
Helius documents), the same upgrade with `--cli-only`, an upgrade to a build that outgrew
`--max-len` (this build padded with zero bytes to 196,709 bytes: there is no larger build yet, so
this rehearses the path, not a larger program's code), a buffer handed to a stand-in vault
(`--mode buffer`), a pause, and the stack is stopped.

Real output of `scripts/mainnet/dry-run.sh --tight` on 2026-10-04 at commit `db7f948b89e4`, the
tree that became `main` (the preflight tables, the key table, the deploy plans, the progress lines
and the service logs are cut; paths shortened). The fork carries the ORE build deployed on
2026-10-02. `--tight` gives every key exactly what section 3 asks for at the local validator's
rent, and tops the deployer up before each later drill with exactly what that drill needs. The
stopped deploy of step 4 is continued with no top-up at all:

```text
== 0a. selftest.sh (no cluster: which RPC a script picks, the pins on the funded addresses, argument checks) ==
selftest: 72 checks passed

== 0. local stack without heads_down (up.sh --no-deploy, RPC :38899, home ~/.local/share/heads-down/dryrun) ==
[devstack] --no-deploy: heads_down is NOT deployed; next: scripts/mainnet/deploy.sh --cluster localnet, then init-config.sh
[devstack] stack is up (test-validator)

== 1. keys.sh (throwaway deploy keys in ~/.config/heads-down/dryrun/keys) ==
funded k5mgognA8cahpUoZQp2e45DgfU3XHkRZUQqyR73mzDt: balance 1.40753572 SOL
funded 8c3KKqwEPRDKpjqqpi9k4rrSnPu2LbVFn1cPudBHqJMp: balance 0.05 SOL
funded HuTaKR8uXLGSqDGhyiPSuoJcGrU14TUzYBcjkvqGmj7R: balance 0.01 SOL
funded 5sU1nebNJvoDNU6Fti1sETgRuAXDGNDyybLLxYgafuKz: balance 1.40753572 SOL

== 2. preflight.sh --cluster localnet (read-only) ==
INFO  buffer                 no buffer for this commit yet: the deploy creates one (198 of 198 chunks to write)
PASS  deployer balance       k5mgognA8cahpUoZQp2e45DgfU3XHkRZUQqyR73mzDt holds 1.407535720 SOL >= 1.407535720 SOL needed
GO: localnet preflight passed (0 local warnings; chain details in ~/.local/share/heads-down/dryrun/deploy/preflight-localnet-20261004T064410Z.json)

== 3. refund drill: a fresh deploy stopped part way, then its buffer closed (a deployer of its own: ~/.config/heads-down/dryrun/keys-refund) ==
INFO  buffer                 Ct67WMv9ePSxjXy8UcuB5kYimMuLadFdNHzGh7UEw5QH does not exist yet: 198 of 198 chunks to write
PASS  deployer balance       5sU1nebNJvoDNU6Fti1sETgRuAXDGNDyybLLxYgafuKz holds 1.407535720 SOL >= 1.407535720 SOL needed
GO: localnet preflight passed (0 local warnings; chain details in ~/.local/share/heads-down/dryrun/deploy/preflight-localnet-20261004T064411Z.json)
write-buffer: created Ct67WMv9ePSxjXy8UcuB5kYimMuLadFdNHzGh7UEw5QH with 1369595760 lamports (tx 3HsPiKhTtL79KhygBjXnwYTih5jReMyqCaQAgyAK58zFTaijiMFDy76KeV99EzmCcocs7ZEHSx1F5yjnbUvhr6nT)
[dry-run] killed the buffer writer (pid 62468) with 20 chunks written
buffer: Ct67WMv9ePSxjXy8UcuB5kYimMuLadFdNHzGh7UEw5QH is the deployer's buffer for this build: 1.369595760 SOL in it, 174 of 198 chunks still to write
[dry-run] stopped part way: no program; buffer Ct67WMv9ePSxjXy8UcuB5kYimMuLadFdNHzGh7UEw5QH is the deployer's, holds 1369595760 lamports and 24 of 198 chunks
buffer: Ct67WMv9ePSxjXy8UcuB5kYimMuLadFdNHzGh7UEw5QH does not exist yet: 198 of 198 chunks to write
fees: 5sU1nebNJvoDNU6Fti1sETgRuAXDGNDyybLLxYgafuKz paid for 26 transaction(s) after slot 0 (0 failed): 141829 lamports

== 4. a fresh deploy stopped part way, then continued by re-running deploy.sh at the same commit ==
INFO  buffer                 5ti3cZm9pvw5Ehxem1y7cxXNcGrDzFShWuRUR7wdr5zp does not exist yet: 198 of 198 chunks to write
PASS  deployer balance       k5mgognA8cahpUoZQp2e45DgfU3XHkRZUQqyR73mzDt holds 1.407535720 SOL >= 1.407535720 SOL needed
GO: localnet preflight passed (0 local warnings; chain details in ~/.local/share/heads-down/dryrun/deploy/preflight-localnet-20261004T064430Z.json)
write-buffer: created 5ti3cZm9pvw5Ehxem1y7cxXNcGrDzFShWuRUR7wdr5zp with 1369595760 lamports (tx qvMCV63A4Yb8RfZqQbAr1ZNxXJ9vuT3i2u9xaGDU2Qvo1bZvRWNm6QLwxDmHSxBcbejtcKL9FARFxjT7hgL2yVW)
[dry-run] killed the buffer writer (pid 62969) with 23 chunks written
buffer: 5ti3cZm9pvw5Ehxem1y7cxXNcGrDzFShWuRUR7wdr5zp is the deployer's buffer for this build: 1.369595760 SOL in it, 171 of 198 chunks still to write
[dry-run] stopped part way: no program; buffer 5ti3cZm9pvw5Ehxem1y7cxXNcGrDzFShWuRUR7wdr5zp is the deployer's, holds 1369595760 lamports and 27 of 198 chunks
PASS  buffer                 5ti3cZm9pvw5Ehxem1y7cxXNcGrDzFShWuRUR7wdr5zp is the deployer's buffer for this build: 1.369595760 SOL in it, 171 of 198 chunks still to write
PASS  deployer balance       k5mgognA8cahpUoZQp2e45DgfU3XHkRZUQqyR73mzDt holds 0.037787330 SOL >= 0.030979960 SOL needed (the 1.369595760 SOL in the buffer is counted, not asked for again)
GO: localnet preflight passed (0 local warnings; chain details in ~/.local/share/heads-down/dryrun/deploy/preflight-localnet-20261004T064446Z.json)
write-buffer: 171 of 171 writes confirmed, 0 in flight (0 signed again, 0 slow-downs)
write-buffer: 5ti3cZm9pvw5Ehxem1y7cxXNcGrDzFShWuRUR7wdr5zp holds exactly programs/heads-down/target/deploy/heads_down.so (190048 bytes read back and compared); 171 writes sent, 171 confirmed, 0 signed again, 0 slow-downs, 900657 lamports of fees, 5 s
{"programId":"HDn4vgLWFLLdexKEwfZwRHjWtizNvdqFteLbMsE67F9p","signature":"2jkLQe7JAnWLoXgm2j2PZv8YADdmgphcsLUPoqXKFDaQErQkKYRzmamtcd9tEDwcaWgG1wZQ6cjnEVYhPAJYGw6d"}
verify: after slot 191 the Solana CLI sent 1 transaction(s), 10297 lamports of fees
verify: ProgramData 3jjGZ8EE8DJcRag9eTFMMxStNo55PHLa5xVPktW52WPZ holds exactly programs/heads-down/target/deploy/heads_down.so (190048 bytes, program hash 0154706c62cb7bb6aacf463501e49b016b87809ed7193fcae08afc4e3f891b58)
verify: receipt deploy/receipts/localnet/20261004T064446Z-fresh-db7f948b89e4.json

== 5. init-config.sh --cluster localnet (initialize_config + Executor float) ==
init: initialize_config tx 2FAKwje5bAzypLf9cA6C9hhYmjK66EZYek2TX1M1yJrxdfTrPpJCwfgYzDreA6N7w5rVZSiWXDbmHU1s2NSwsfEp -> Config inzDn4ogmXbx9YDAKDHkfwJHy1jhsaWxGQvricDAEmW (executor_fee 10000, crank_fee 7000, governance HuTaKR8uXLGSqDGhyiPSuoJcGrU14TUzYBcjkvqGmj7R, registrar YyyL6FBuH816aWaKoWzWZ8VmecgvmwbMHG1J8zwwZS3); read back and verified
init: funded the Executor PDA By3vJvQUsCLexnv7VqHuEhtZZCmpmjZjfhxvqCnWPkge with 1690880 lamports to 1690880 (tx 5jNLT4vjLqfD5AczdYAk3QZjgGBavQ3F1CubsXRQoBQxXfCKgji5jBD7X4cxqA7LHqHhh5Tabx1L4uoYikgfJHYM)

== 6. scripts/devstack/smoke.sh against the deployed + initialized program ==
[smoke +   0s] stack up: crank healthy, indexer healthy; heads_down Config executor_fee 10000 crank_fee 7000; ORE round 427290 (ema 715235766 lamports/ORE)
[smoke +   1s] clock-in (automate + register_rig + set_caps + arm_shift, 1 tx 5wgHBta3Av5FeL2MFAN2yRRiCUexPhaJJh3akE3uzNgYAVtH8F3SgvnopSEqdmvgHPzwgNpHLikCYq3RjGLCU275): rig 5hVqnQ4BHaibR3nAtWiTj9NZUH3UYtpbbLGMKtVpCHQj Armed, shift 1, 0.001 SOL digs on 10 split tiles
[smoke +   1s] crank intake status: {"ema_ev":853163935,"end_slot":243,"round_id":427290,"slot":199,"start_slot":3,"type":"status"}
[smoke +   1s] phone face-down: heartbeat #1 for round 427290 accepted by the crank (45 slots left)
[smoke +  51s] phone face-down: heartbeat #2 for round 427291 accepted by the crank (not started)
[smoke + 169s] CRANK DUG round 427291: tx 5ikDpMpYjgyVdVbF9mWvfuTKCymJ988WNCVtjjYgbMj2WY9RhpruRBhmkgooLGtJQS1xmx5dSuXGpoVkBJhTdVVz; RigDug 1000000 lamports on 10 squares (mask 0x002cb1d); rig Down, hb_counter 2, lease [427291, 427291]; Automation balance 48990000 (fee 10000)
[smoke + 169s] crank metrics: hd_crank_digs_landed_total 1, heartbeats accepted 2
[smoke + 169s] PHONE LIFTED after round 427291: no more heartbeats
[smoke + 206s] ORE round 427292 is now current (reset by the round driver)
[smoke + 207s] hostile crank REPLAYS heartbeat #2 in round 427292: tx 256v2o2JxLkhpuw8Bkwz5VKxWZXXHE2D3TocxQEqBPNJiyVc3JoMx3eVuRwdStbQfqzToeG2m892C8hgXRATLSnL -> RigSkipped(StaleHeartbeat)
[smoke + 207s] hostile crank REUSES the old lease in round 427292: tx 3WMKD7nNh4QwcJe2M6J7Wpn1EkX26Pb3eUo81J8JoErE96owuZutq46mBXToaQ4C3c3L3eWT2yQagynutiugCa13 -> RigSkipped(LeaseExpired)
[smoke + 340s] round 427292 closed (board at 427292): crank did NOT dig the lifted rig (last_dug_round 427291, lease_to 427291); hd_crank_digs_landed_total 1
[smoke + 340s] INDEXER recorded RigDug: rig "5hVqnQ4BHaibR3nAtWiTj9NZUH3UYtpbbLGMKtVpCHQj" round "427291" lamports "1000000" squares 10 (dataset localnet)
[smoke + 340s] indexer health: 7 txs ingested through slot 588, 0 decode problem kinds
SMOKE PASSED: face-down -> dug (round 427291); lifted -> no dig, replay and lease reuse refused on-chain (round 427292).

== 7. upgrade drill behind a rate limit: deploy.sh --mode upgrade through an RPC that answers HTTP 429 beyond 4 sendTransaction a second ==
funded k5mgognA8cahpUoZQp2e45DgfU3XHkRZUQqyR73mzDt: balance 1.387632105 SOL
rate-limit-proxy: 127.0.0.1:38898 -> http://127.0.0.1:38899, 4.0 sendTransaction a second
INFO  buffer                 5ti3cZm9pvw5Ehxem1y7cxXNcGrDzFShWuRUR7wdr5zp does not exist yet: 198 of 198 chunks to write
PASS  deployer balance       k5mgognA8cahpUoZQp2e45DgfU3XHkRZUQqyR73mzDt holds 1.387632105 SOL >= 1.356273160 SOL needed
GO: localnet preflight passed (0 local warnings; chain details in ~/.local/share/heads-down/dryrun/deploy/preflight-localnet-20261004T065041Z.json)
write-buffer: created 5ti3cZm9pvw5Ehxem1y7cxXNcGrDzFShWuRUR7wdr5zp with 1323938160 lamports (tx FmPngTokJCP3et8Mrany5bCKo3sAE6HCmcNE4tEoWmQ7csuApYiutzEY7z9Eeo72Xit8RN2KmmimupABt5Mvok7)
write-buffer: 39 of 198 writes confirmed, 0 in flight (0 signed again, 10 slow-downs)
write-buffer: 198 of 198 writes confirmed, 0 in flight (0 signed again, 34 slow-downs)
write-buffer: 5ti3cZm9pvw5Ehxem1y7cxXNcGrDzFShWuRUR7wdr5zp holds exactly programs/heads-down/target/deploy/heads_down.so (190048 bytes read back and compared); 198 writes sent, 198 confirmed, 0 signed again, 34 slow-downs, 1053287 lamports of fees, 80 s
{"programId":"HDn4vgLWFLLdexKEwfZwRHjWtizNvdqFteLbMsE67F9p","signature":"5nRFK5DniQwLzPFFmRW6XgqcqKh4NA1HTMZsg7JGcN88bjo2QqLWCjrRQTV3PASKYqCawEbAfGDpwfd2fNkFPypM"}
verify: after slot 992 the Solana CLI sent 1 transaction(s), 5267 lamports of fees
verify: ProgramData 3jjGZ8EE8DJcRag9eTFMMxStNo55PHLa5xVPktW52WPZ holds exactly programs/heads-down/target/deploy/heads_down.so (190048 bytes, program hash 0154706c62cb7bb6aacf463501e49b016b87809ed7193fcae08afc4e3f891b58)
verify: receipt deploy/receipts/localnet/20261004T065041Z-upgrade-db7f948b89e4.json
Last Deployed In Slot: 994
[dry-run] the rate limit let 200 sends through and refused 34; the writer was asked for 50 a second, counted 34 slow-downs and signed 0 writes again

== 8. fallback drill: the same upgrade with --cli-only (the Solana CLI writes the buffer itself) ==
buffer: 5ti3cZm9pvw5Ehxem1y7cxXNcGrDzFShWuRUR7wdr5zp does not exist yet: 198 of 198 chunks to write
INFO  buffer                 5ti3cZm9pvw5Ehxem1y7cxXNcGrDzFShWuRUR7wdr5zp does not exist yet: 198 of 198 chunks to write
PASS  deployer balance       k5mgognA8cahpUoZQp2e45DgfU3XHkRZUQqyR73mzDt holds 1.386573551 SOL >= 1.356273160 SOL needed
GO: localnet preflight passed (0 local warnings; chain details in ~/.local/share/heads-down/dryrun/deploy/preflight-localnet-20261004T065209Z.json)
{"programId":"HDn4vgLWFLLdexKEwfZwRHjWtizNvdqFteLbMsE67F9p","signature":"2CqHQhNXqUrLqU3gjeAhCe44S2mZcN29EA96Cnw3mCZWscrwb65HzxSJrcXdd91Gwu6Ck9hsVWmmiSCS9BncTFAb"}
verify: after slot 1007 the Solana CLI sent 200 transaction(s), 1058415 lamports of fees
verify: ProgramData 3jjGZ8EE8DJcRag9eTFMMxStNo55PHLa5xVPktW52WPZ holds exactly programs/heads-down/target/deploy/heads_down.so (190048 bytes, program hash 0154706c62cb7bb6aacf463501e49b016b87809ed7193fcae08afc4e3f891b58)
verify: receipt deploy/receipts/localnet/20261004T065209Z-upgrade-db7f948b89e4.json

== 9. growth drill: an upgrade to a build that outgrew --max-len (this build, padded with zero bytes to 196709 bytes) ==
funded k5mgognA8cahpUoZQp2e45DgfU3XHkRZUQqyR73mzDt: balance 1.47390412 SOL
WARN  max-len                the build is 101 bytes larger than the 196608 the deployed ProgramData holds: the upgrade extends it by 10240 bytes (the loader's minimum is 10240), and their rent stays locked like the rest
INFO  buffer                 5ti3cZm9pvw5Ehxem1y7cxXNcGrDzFShWuRUR7wdr5zp does not exist yet: 198 of 205 chunks to write
PASS  deployer balance       k5mgognA8cahpUoZQp2e45DgfU3XHkRZUQqyR73mzDt holds 1.473904120 SOL >= 1.473904120 SOL needed
GO: localnet preflight passed (0 local warnings; chain details in ~/.local/share/heads-down/dryrun/deploy/preflight-localnet-20261004T065222Z.json)
  ProgramData        extended by 10240 bytes before the upgrade (the build outgrew it): 0.071270400 SOL of rent, locked like the rest
write-buffer: created 5ti3cZm9pvw5Ehxem1y7cxXNcGrDzFShWuRUR7wdr5zp with 1370298720 lamports (tx W7cxsvkptzRsV3mqSUthtcYHppi2ZJdiCzURwUSiS5aM9Y64aQP8Vh3QNGcQkAR2txjr8jb2t76cU5spcxSPAEu)
write-buffer: 198 of 198 writes confirmed, 0 in flight (0 signed again, 0 slow-downs)
write-buffer: 5ti3cZm9pvw5Ehxem1y7cxXNcGrDzFShWuRUR7wdr5zp holds exactly ~/.local/share/heads-down/dryrun/heads_down-grown.so (196709 bytes read back and compared); 198 writes sent, 198 confirmed, 0 signed again, 0 slow-downs, 1053287 lamports of fees, 7 s
[deploy] extending the ProgramData of HDn4vgLWFLLdexKEwfZwRHjWtizNvdqFteLbMsE67F9p by 10240 bytes (0.071270400 SOL of rent)
Extended Program Id HDn4vgLWFLLdexKEwfZwRHjWtizNvdqFteLbMsE67F9p by 10240 bytes
{"programId":"HDn4vgLWFLLdexKEwfZwRHjWtizNvdqFteLbMsE67F9p","signature":"59qpEjstiqmesDqfbD9RtcRy9NX84QRsSB2GdsB23zKimZsYGgMN6b2TcAQ8dVNv7jBg1LAdRYMX7rdVha55u5QV"}
verify: after slot 1035 the Solana CLI sent 2 transaction(s), 10267 lamports of fees
verify: ProgramData 3jjGZ8EE8DJcRag9eTFMMxStNo55PHLa5xVPktW52WPZ holds exactly ~/.local/share/heads-down/dryrun/heads_down-grown.so (196709 bytes, program hash 0154706c62cb7bb6aacf463501e49b016b87809ed7193fcae08afc4e3f891b58)
verify: receipt deploy/receipts/localnet/20261004T065222Z-upgrade-db7f948b89e4.json

== 10. Squads drill: deploy.sh --mode buffer, handing the buffer to governance.json's key as a stand-in vault ==
funded k5mgognA8cahpUoZQp2e45DgfU3XHkRZUQqyR73mzDt: balance 1.433905166 SOL
INFO  buffer                 5ti3cZm9pvw5Ehxem1y7cxXNcGrDzFShWuRUR7wdr5zp does not exist yet: 198 of 198 chunks to write
PASS  deployer balance       k5mgognA8cahpUoZQp2e45DgfU3XHkRZUQqyR73mzDt holds 1.433905166 SOL >= 1.356217480 SOL needed
GO: localnet preflight passed (0 local warnings; chain details in ~/.local/share/heads-down/dryrun/deploy/preflight-localnet-20261004T065237Z.json)
write-buffer: created 5ti3cZm9pvw5Ehxem1y7cxXNcGrDzFShWuRUR7wdr5zp with 1323882480 lamports (tx VfWefBBaU3RYcRiAyHkh9G6HqpWbHVkZaAJUPKGhkoekoFEmwCwjBCXt9dwoZeN17CBq2TMVSq3tfXiM7GXMDGF)
write-buffer: 198 of 198 writes confirmed, 0 in flight (0 signed again, 0 slow-downs)
write-buffer: 5ti3cZm9pvw5Ehxem1y7cxXNcGrDzFShWuRUR7wdr5zp holds exactly programs/heads-down/target/deploy/heads_down.so (190048 bytes read back and compared); 198 writes sent, 198 confirmed, 0 signed again, 0 slow-downs, 1053287 lamports of fees, 6 s
{"buffer":"5ti3cZm9pvw5Ehxem1y7cxXNcGrDzFShWuRUR7wdr5zp"}
[deploy] handing the buffer to HuTaKR8uXLGSqDGhyiPSuoJcGrU14TUzYBcjkvqGmj7R
verify: after slot 1073 the Solana CLI sent 1 transaction(s), 5000 lamports of fees
verify: buffer 5ti3cZm9pvw5Ehxem1y7cxXNcGrDzFShWuRUR7wdr5zp holds exactly programs/heads-down/target/deploy/heads_down.so (190048 bytes, program hash 0154706c62cb7bb6aacf463501e49b016b87809ed7193fcae08afc4e3f891b58)
verify: receipt deploy/receipts/localnet/20261004T065237Z-buffer-db7f948b89e4.json

== 11. rollback drill: governance.sh pause (immediate), then show ==
propose_config tx 2stSnLf63TDs29qrNm3wHvhtu83bGqB2RYZg8pa8LsVVxVritdknjwXNMvjXRqEjW2ZKSNPthcepi8qbnwRun9ae: pending until slot 865077 (paused now true, pending paused 1)
heads_down      Config inzDn4ogmXbx9YDAKDHkfwJHy1jhsaWxGQvricDAEmW executor_fee 10000 crank_fee 7000 paused true
pending         registrar YyyL6FBuH816aWaKoWzWZ8VmecgvmwbMHG1J8zwwZS3 crank_fee 7000 bury_bps 0 paused 1; apply_config from slot 865077 (864000 slots to go)

== stop the stack ==
[devstack] stopped indexer
[devstack] stopped crank
[devstack] stopped driver
[devstack] stopped validator
summary
  PASS  0a selftest.sh: 72 checks passed
  PASS  0 up.sh --no-deploy: fork, driver, crank, indexer up; no heads_down
  PASS  1 keys.sh: keys created (600 in a 700 dir), every key holds exactly what the funding table asks for (deployer 1407535720 lamports)
  PASS  2 preflight.sh: GO
  PASS  3 refund: writer killed at 24 of 198 chunks; solana.sh program close gave back all but the 141829 lamports of fees (1407535720 -> 1407393891)
  PASS  4 deploy.sh stopped at 27 of 198 chunks, re-run with no top-up: preflight GO, the other 171 written, the CLI sent 1 transaction, bytes verified, receipt written
  PASS  5 init-config.sh: Config created and read back, Executor float funded, receipt written
  PASS  6 smoke.sh: SMOKE PASSED
  PASS  7 deploy.sh --mode upgrade behind a rate limit of 4 sends a second: 34 sends refused with HTTP 429, the writer slowed down and finished, the CLI sent 1 transaction, bytes verified, receipt written
  PASS  8 deploy.sh --mode upgrade --cli-only: the CLI sent 200 transactions itself, bytes verified, receipt written
  PASS  9 deploy.sh --mode upgrade with a build of 196709 bytes: the ProgramData extended by 10240 bytes, then upgraded at the first try (deployer need 1473904120 lamports), bytes verified, receipt written
  PASS  10 deploy.sh --mode buffer: buffer written and handed over, bytes verified, receipt written
  PASS  11 governance.sh pause: Config.paused = 1 immediately; un-pause waits for the timelock
DRY RUN PASSED (log ~/.local/share/heads-down/dryrun/dry-run.log)
```

**What it cost.** The deployer started with 1,407,535,720 lamports and had 31,358,945 left after
the fresh deploy and `init-config`: ProgramData 1,369,595,760 + Program 1,141,440 + Config
2,672,640 + Executor float 1,690,880 + **1,076,055 of fees** (the deploy across its stopped and its
continued run, and the init). The stopped deploy that was refunded cost its own deployer 141,829
lamports of fees and nothing else. The upgrade through the paced writer cost 1,058,554 lamports of
fees, the same upgrade with `--cli-only` 1,058,415. The growing upgrade cost 1,063,554 of fees and
locked 71,270,400 lamports of rent for the 10,240 bytes (the fork's rent; 52,019,200 on mainnet).
The Executor PDA ended at 1,693,880 lamports: the 1,690,880 float, +10,000 fee in, -7,000
reimbursed to the crank.

Note the local validator's rent is the historical 6,960 lamports per byte (mainnet's is now
5,080), which is why the amounts differ from section 3: every amount is read from the cluster it
applies to. The fees and what is left over are the same on both.

What the rehearsal cannot show: Helius' own limiter (the proxy copies the answer Helius
documents), a real network's timeouts, and the mainnet confirmation prompt, which needs a
terminal. The deploy of 10 October 2026 showed the first two once each: Helius' free plan took
198 writes at one a second and refused none, and a dropped connection stopped the first run at
its preflight, before anything was sent (sections 6 and 7). The prompt was not used: that run
passed `--yes`.

The fees on mainnet were 1,076,054 lamports for the fresh deploy and `init-config`, one lamport
less than the rehearsal's 1,076,055 (section 3 has the whole account).

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
crank or the registrar, read the log for `key file removed; hd-<service> (pid N, uid 10001)`. The
crank's entrypoint first ran on Linux on 2026-10-10, on Railway, and its line was the first to
show the uid: `key file removed; hd-crank (pid 13, uid 10001) on 0.0.0.0:8787`. A line
`no Railway volume is mounted at /data` means the volume is missing or Railway did not pass its
mount path: roll back to the previous deployment in Railway.

## 13. Rollback and incidents

| Situation | Do | Effect |
|---|---|---|
| Any doubt about digs (a bug, an ORE change, bad crank behaviour) | `scripts/mainnet/governance.sh pause`. If Helius answers HTTP 429 because the key has no credits left: `scripts/mainnet/governance.sh --public-rpc pause` | **Immediate**: from the next transaction `dig` fails with `Paused` (18). Nothing else stops: users can still break, freeze, end shifts, close rigs and Revoke in ORE. No SOL moves. |
| Resume | `governance.sh unpause`, then `governance.sh apply` 72 hours later. The program waits for both 864,000 slots and 72 hours of cluster time; at today's slots of about 0.27 s the slots pass after about 64 hours, so `governance.sh show` says no slots are left while `apply` is still refused with `TimelockNotElapsed` (19) until the 72 hours are over | Un-pausing always waits for the timelock. A new proposal replaces a pending one and restarts the clock. |
| Program bug | pause, fix, then upgrade with a fresh buffer (section 14) | users' funds stay in their own ORE Automations throughout |
| Crank misbehaving or compromised | stop the Railway service; rename `crank-payer.json` aside, run `keys.sh` (it creates a new one), move the old key's SOL with `solana.sh --keypair <old file> -- transfer <new pubkey> ALL --allow-unfunded-recipient`, update `HD_CRANK_KEYPAIR_JSON` and `TEAM_CRANKERS`, redeploy. `keys.sh` and `preflight.sh` stop on the new key until its pin is changed: set `HD_EXPECTED_CRANK_PAYER=<new pubkey>` for those runs, then update the pin in `scripts/mainnet/lib.sh` and the tables in sections 2, 3 and 10.4. The lookup table named in `/data/hd-crank/lookup_tables.json` belongs to the old key: close it with the old key and remove that file, or the new crank creates no table (it has `alt.max_tables = 1`, and a table on record counts) | liveness only: the worst case is no digs |
| Registrar key compromised | stop the registrar; rename `registrar.json` aside and run `keys.sh` for a new one; `governance.sh propose --registrar <new>`; after 72 h `apply`, then start the registrar with the new key. Until `apply` the chain still trusts the old key (rigs can register unattested meanwhile). For a routine rotation, run old and new side by side until `apply` | attestation levels only (THREAT_MODEL K4) |
| Helius key leaked | rotate in Helius and delete the old key, update `helius.env`, then the crank's `HELIUS_API_KEY` and the indexer's `RPC_URL` (each a sealed variable of its own service; this deployment has no shared variable, section 10.3), and redeploy both services | |
| `deploy.sh` stops at its preflight with an RPC error | check the connection, then run it again. It happened on the first try on 10 October 2026 (`getGenesisHash: http: error sending request`, with ping round trips of 0.8 to 1.8 s): the second run, minutes later, deployed | nothing is sent before the preflight answers GO |
| Deploy stopped part way | re-run `deploy.sh` at the same commit: preflight counts the rent and the chunks the buffer holds, and the writer sends only what is missing; or close the buffer (section 7). No SOL has to be sent for either. The writer says why it stopped: `the payer … holds …` names the SOL to send; `no write was seen to land for 300 s` means writes were taken and did not land (the RPC, the priority fee, or an empty deployer). Rehearsed on the fork; it has not happened on mainnet | |
| The crank's digs land after their round: `hd_crank_digs_skipped_onchain_total{error="RoundNotActive"}` grows, and a rig that heartbeats is not dug | give the dig more room: raise `HD_CRANK_DIG_DEPLOY_MARGIN_SLOTS` on the service, and the priority floor `HD_CRANK_DIG_CU_PRICE_MICRO_LAMPORTS` (section 10.4). On 10 October 2026, with a 20-slot window and a floor of 1,000, the first dig missed its round and the second landed 11 slots before its round's end; with 80 and 20,000 the four that followed landed 49 to 77 slots before theirs. Which of the two changes did it is not known | each such dig costs the crank about 10,000 lamports and the rig its round; no SOL of the user's moves |
| The crank pays for second attempts: `hd_crank_digs_skipped_onchain_total{error="StaleHeartbeat"}` grows next to digs that landed | raise `HD_CRANK_DIG_RETRY_AFTER_SLOTS` (section 10.4): the retry fires before the first attempt is seen to land. On 10 October 2026 it was 6 slots and two of three rounds had a refused second attempt; none landed after it was set to 40 | the program refuses the second attempt, so nothing is dug twice; the crank loses about 10,000 to 11,000 lamports each time |
| A second buffer at a commit whose buffer was handed to the vault | move `buffer-<commit>.json` out of the key directory, then run `deploy.sh` again (it makes a new keypair) | preflight refuses until then, and says so |
| Helius credits used up | every call answers HTTP 429. Operator scripts: add `--public-rpc`. Services: the crank stops digging and the indexer stops reading the chain (its API keeps serving); remove the crank's deployment, and wait for the month to roll over or change the plan or the key. It happened on 10 October 2026, before the deploy: the key first used was shared with another project, and it was replaced | nothing is spent while nothing digs |
| ORE upgraded its program (it did on 2026-09-25 and 2026-10-02) | Nothing to do at once: the crank's breaker has already stopped digs, and `preflight.sh` answers NO-GO. Then: read the diff between the commits verify.osec.io names; refresh the fixtures (`programs/heads-down/tests/fixtures/fetch-fixtures.sh`, `scripts/devstack/up.sh --refresh-fixtures`); run the program's and the crank's fork suites and `dry-run.sh --tight`; move the pin (`crank/src/ore.rs`, `crank/crank.example.toml`, `deploy/railway/crank/crank.toml`, `ORE_PROGRAM_HASH` and the commit named beside it in `scripts/devstack/tool/src/ops.rs`, `docs/ORE.md`, and the slot and hash quoted in section 6 and in the header of `scripts/mainnet/preflight.sh`); redeploy the crank | no digs, so nothing is mined and nothing is spent, until the pin is moved |

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
  2. Every signer checks the buffer: `solana-verify get-buffer-hash <BUFFER> -um` (install once
     with `cargo install solana-verify`) must equal the
     receipt's `onchain.program_hash`. A signer's own rebuild of the recorded commit does not
     give that hash: it comes out only in the directory of the first build (section 7), so
     until a build in a pinned container exists the signers compare the buffer with the
     receipt, not with the source.
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
4. Verify: `scripts/mainnet/solana.sh -- program show HDn4vgLWFLLdexKEwfZwRHjWtizNvdqFteLbMsE67F9p`
   prints `Authority: <VAULT>`. From now on `init-config.sh` refuses to run (its signer is no
   longer the upgrade authority): top up the Executor with
   `scripts/mainnet/solana.sh -- transfer By3vJvQUsCLexnv7VqHuEhtZZCmpmjZjfhxvqCnWPkge <SOL>`
   (section 9).
5. After the audit: revoke through the vault (`set-upgrade-authority --final`) for an
   immutable v1. docs/ORE.md §8 explains why v1 then relies on the layout pins alone.

**Governance.** The multisig's 72 h time lock would also delay the pause, which must be
immediate, so governance does not move to the same vault as the upgrade authority:

- Launch with `governance.json` (a single key the founder keeps offline except for an
  emergency): the pause is one command, and every other governance change already waits 72 h
  on-chain, which users can watch. `Config.governance` has been that key, `37u9LWbP…`, since
  `initialize_config` on 10 October 2026, and it has sent no transaction.
- Since v1.3 the program can rotate `Config.governance` (`propose_governance`, then
  `accept_governance` by the successor after the same 72 h). No script sends these yet: the two
  commands have to be added to `hd-devstack` and `governance.sh` first. When there are
  co-signers, move governance to a **second** Squads multisig **without** a time lock (2-of-3,
  another 0.1 SOL): a pause then takes two signatures but no waiting, and the program's own 72 h
  timelock still covers every other change.

**Until the steps above are done, say so.** Since the deploy on 10 October 2026 the upgrade
authority is one keypair, `9DSVM862…`, and program upgrades have no delay. None of the steps
above has been taken. [THREAT_MODEL.md](THREAT_MODEL.md) ("As built") and
[SECURITY_REVIEW.md](SECURITY_REVIEW.md) state this, and they must keep doing so until the
vault holds the authority.

## 16. Costs per month

| Item | Plan | Monthly |
|---|---|---|
| Railway | Hobby, billed by usage (the account's plan already; $5 a month that counts towards usage across all of the account's projects) | an estimate, not yet measured: about **$8-15** for five small services (crank ~0.1 GB RAM, registrar ~0.05 GB, indexer ~0.2 GB, dashboard ~0.05 GB, Postgres ~0.25 GB at $10 per GB-month; light CPU at $20 per vCPU-month; volumes at $0.15 per GB-month of storage used). Read the project's usage page after the first days and set a usage limit |
| Helius | Free | **$0** while a month stays inside 1M credits (section 5); otherwise **$49** (Developer, 10M credits) plus $5 per further million |
| SOL: crank fees | | about 0.001 to 0.003 SOL per phone-night (section 3; computed); budget 0.01 to 0.05 SOL a month. Measured so far: 60,473 lamports (0.00006 SOL) for the first shift on 10 October 2026, which had five digs, and 4,028 to 4,082 lamports lost on each dig at the priority floor the service has run with since |
| SOL: lookup tables | one-time, only when lookup tables are on | 0.00256 SOL + 0.00065 SOL per rig. It comes back only by hand, with the crank stopped: `scripts/mainnet/solana.sh --keypair <crank-payer.json> -- address-lookup-table deactivate <TABLE> --bypass-warning`, about 5 minutes later `… address-lookup-table close <TABLE> --recipient <ADDR>`. Keep the table's address (the crank logs `created lookup table`). After closing a table, remove `/data/hd-crank/lookup_tables.json` (or the table's entry in it) before the crank runs again: a table on record counts against `alt.max_tables = 1`, so the crank would otherwise create none and dig without one |
| SOL: Executor | | grows by 3,000 lamports per dig (10,000 in, 7,000 reimbursed to the crank); nobody can withdraw from it, and only ORE's checkpoint fee of 10,000 lamports, when a third party checkpointed a Miner late, takes it down (section 9) |
| SOL: an upgrade | per upgrade, temporary | about 0.963 SOL lent to the deployer for a few minutes; all but about 0.001 SOL of fees comes back (section 3). Not run on mainnet |
| SOL: a larger build | only when a build outgrows max-len | 52,019,200 lamports per 10,240-byte extension, locked like the program's rent |
| SOL: Squads | one-time, optional | 0.1 SOL per multisig, never returned (section 15) |
| Domain (optional) | | ~$1 (not needed: the app identifies itself with the project's GitHub Pages address) |
| **Total** | | **about $8-15 a month** on the free Helius plan, plus small SOL top-ups |

One-time: the deploy and initialization, **1.10 SOL** across the three keys (section 3); 1.107
SOL was sent on 10 October 2026. Of it,
0.9996 SOL of ProgramData rent stays locked while the program exists, 0.0053 SOL went into the
Program account, the Config, the Executor float and fees for good, and 0.102 SOL was liquid that
evening: 0.036 in the deployer, and the crank's 0.051 and governance's 0.015, which are spent in
use.

## 17. Files and secrets

| Path | Committed | Holds |
|---|---|---|
| `scripts/mainnet/*.sh` | yes | the runbook's scripts (no secrets; `scripts/mainnet/.gitignore` refuses `*.json` and `*.env` there, and the root `.gitignore` refuses the key file names anywhere in the repository) |
| `deploy/railway/<service>/` | yes | Dockerfile, `railway.json` (a record of the service's settings), `.env.example` (every variable the service reads; names only for secrets), entrypoints, `crank.toml` (placeholders only) |
| `.railway/` | never (ignored) | where `railway config pull` writes the project as code. Never run it with `--include-variables` in the repository: it writes every unsealed value into that file |
| `deploy/receipts/mainnet/` | **yes, after each change** | public receipts |
| `deploy/receipts/localnet/` | no (ignored) | dry-run receipts |
| `~/.config/heads-down/` | never | every key and `helius.env` (dir 700, files 600) |
| `~/.local/share/heads-down/deploy/` | never | build logs, preflight JSON, the buffer writer's output and JSON (`write-buffer-<cluster>-<UTC>.out` / `.json`), the extension's output (`extend-<cluster>-<UTC>.out`), CLI output |
| `~/.local/share/heads-down/dryrun/` | never | the dry run's fork, logs and state |
