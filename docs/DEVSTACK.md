# DEVSTACK: the whole system on a local mainnet fork

ORE does not exist on devnet, so a local fork of mainnet is the only real end-to-end path
before mainnet. `scripts/devstack/` runs every piece on the builder's Mac with one command:

- the live mainnet ORE, entropy and ORE-mint programs, with mainnet's ORE state;
- `heads_down`, built with `scripts/build.sh` (mainnet feature) and deployed with its real
  program keypair;
- the round driver that keeps ORE rounds advancing;
- `hd-crank`, the indexer (real RPC mode) and, optionally, the registrar.

A USB-connected phone reaches the same stack at `127.0.0.1` through `adb reverse`.

```bash
scripts/devstack/up.sh        # build, fork, deploy, init, start everything (~1 min after the first build)
scripts/devstack/smoke.sh     # the phone-less end-to-end smoke (~4 min); brings the stack up/down itself
scripts/devstack/down.sh      # stop (add --wipe to also delete ledger, genesis, crank state and indexer DB)
```

Contents: [Prerequisites](#prerequisites) · [What runs](#what-runs-and-where) ·
[The smoke](#the-smoke-the-trustless-beat) · [Fork engine](#fork-engine-solana-test-validator-surfpool-evaluated) ·
[Keeping ORE rounds moving](#keeping-ore-rounds-moving-the-ore-round-driver) ·
[heads_down deploy and Config](#heads_down-deploy-and-config) · [Phone over USB](#phone-over-usb) ·
[Logs and troubleshooting](#logs-status-troubleshooting) · [What differs from mainnet](#what-differs-from-mainnet) ·
[Files and secrets](#files-and-secrets)

## Prerequisites

| Tool | Why | Check |
|---|---|---|
| Agave CLI 4.1 (`solana`, `solana-test-validator`, `cargo-build-sbf`) | fork engine, deploy, SBF build | `~/.local/share/solana/install/active_release/bin/solana --version` (the scripts add it to `PATH`) |
| Rust 1.97.1 (rustup) | `hd-crank` and `hd-devstack` (both pin it in `rust-toolchain.toml`) | `rustup toolchain list` |
| Node ≥ 26 + pnpm 11 | the indexer (runs `.ts` directly, PGlite: no Postgres, no Docker) | `node --version`, `pnpm --version` |
| `python3`, `curl`, `lsof` | small helpers | macOS ships them |
| `adb` (optional) | phone over USB | `brew install android-platform-tools` |
| The program keypair | `~/.config/heads-down/heads_down-program-keypair.json` must be `HDn4vgLWFLLdexKEwfZwRHjWtizNvdqFteLbMsE67F9p` | `up.sh` checks it |
| Network on the first `up.sh` | `fetch-mainnet.sh` dumps ORE from mainnet once; the validator copies mainnet's feature set at every start | |

**Docker is not used anywhere.** The first `up.sh` builds the program, the crank and the tool,
and runs `pnpm install --frozen-lockfile` for the indexer (a few minutes). After that,
`up.sh --no-build` takes about 60 seconds.

## What runs and where

| Component | Port (127.0.0.1 only) | Log (`~/.local/share/heads-down/devstack/logs/`) | Notes |
|---|---|---|---|
| `solana-test-validator` 4.1.2 | RPC **8899**, WebSocket **8900**, faucet 9900, gossip 18001, dynamic 18002-18040 | `validator.log` | fresh ledger at every `up.sh` (use `--resume` to keep it) |
| `hd-devstack driver` (ore-round-driver) | n/a | `driver.log` | starts, reveals and resets ORE rounds |
| `hd-crank run` | **8787**: `/ws` (heartbeat intake), `/healthz`, `/metrics` | `crank.log` | config generated at `run/crank.toml` |
| indexer (`node src/main.ts serve`) | **8788**: `/v1/health`, `/v1/digs/recent`, … | `indexer.log` | `INDEXER_DATASET=localnet`, real RPC ingest every 5 s (accounts are scanned again when a new transaction arrives, and otherwise every 20th poll), PGlite DB in `run/indexer-pg` |
| registrar (optional, `up.sh --with-registrar`) | **8790** | `registrar.log` | dev key = `Config.registrar`, debug-keystore cert digest, `solana:localnet` SIWS, sample revocation list; `GET /registrar` and `POST /siws/nonce` answered locally |

All ports are environment-overridable (`HD_RPC_PORT`, `HD_CRANK_PORT`, `HD_INDEXER_PORT`, …;
see `scripts/devstack/lib.sh`). `status.sh` prints what is running and the fork's ORE and
heads_down state:

```text
slot            99
ORE round       422771 [38, 278) (179 slots left, reset from 326)
round deployed  140000 lamports by 1 miners
ema / pot       923847698 lamports/ORE, motherlode 378.00 ORE
gate ema_ev     Some(631331000) lamports/ORE
entropy Var     end_at 278 sampled false revealed false samples left 999999999
heads_down      Config inzDn4ogmXbx9YDAKDHkfwJHy1jhsaWxGQvricDAEmW executor_fee 10000 crank_fee 7000 paused false
Executor PDA    By3vJvQUsCLexnv7VqHuEhtZZCmpmjZjfhxvqCnWPkge 1000000000 lamports
```

| Script | What |
|---|---|
| `up.sh [--engine test-validator\|surfpool] [--refresh-fixtures] [--resume] [--with-registrar] [--no-build]` | everything below, in order |
| `down.sh [--wipe]` | stop all processes; `--wipe` deletes ledger, genesis, crank state and indexer DB (keys and the mainnet dump stay) |
| `smoke.sh [--keep]` | the E2E smoke; starts the stack if needed and stops it afterwards unless `--keep` |
| `status.sh` / `logs.sh [name]` | health, ports, ORE round, Config, Executor float / follow logs |
| `fund.sh <pubkey> [sol]` | local airdrop (dev wallet funding) |
| `phone.sh [--serial S] [--fund <pubkey> [sol]] [--remove]` | `adb reverse` for 8899, 8900, 8787, 8788, 8790 |
| `clock-in.sh <p256-hex> [--lease N] [--hours H]` | a Mac-held dev wallet arms a rig for a phone's Keystore key |
| `fetch-mainnet.sh` | re-dump the ORE programs and accounts from mainnet (`up.sh --refresh-fixtures`) |
| `install-surfpool.sh` | pinned, SHA-256-checked Surfpool binary (only for `--engine surfpool`) |

## The smoke: the "trustless beat"

`scripts/devstack/smoke.sh` needs no phone. A test P-256 key (`p256` crate) signs exactly like
Android Keystore: `SHA256withECDSA` over the 32-byte `SHA-256` of the 94-byte HEARTBEAT
preimage, then DER to low-S `r||s`.

1. A fresh wallet clocks in with **one transaction**: ORE `automate` (Discretionary,
   `fee = Config.executor_fee`, executor = Executor PDA, 0.0001 SOL per-square cap, 0.05 SOL
   deposit) + `register_rig` + `set_caps` + `arm_shift` (0.001 SOL digs on the 10
   least-crowded split tiles, lease 1).
2. The "phone" streams one heartbeat per ORE round to the crank's WebSocket intake, in the JSON
   of `crank/INTERFACE-NOTES.md` A6, reading `Board.round_id` from the chain like a phone would.
3. The real `hd-crank` digs late in the round (20 slots before `end_slot`): the secp256r1
   precompile verifies the heartbeat, and heads_down CPIs ORE `deploy` through the Executor PDA.
   The smoke finds the transaction and its `RigDug` event, and checks the rig state and the
   Automation debit.
4. **The phone is lifted** (heartbeats stop). In the next round:
   - a hostile crank **replays the last signed heartbeat**, and the program answers
     `RigSkipped(StaleHeartbeat)`;
   - a hostile crank **reuses the old lease**, and the program answers `RigSkipped(LeaseExpired)`;
   - the real crank never digs the rig: `last_dug_round` is unchanged when that round closes.
5. The indexer, at `finalized` commitment, lists the dig at `/v1/digs/recent`. It pairs the
   `RigDug` with ORE's own `DeployEvent` signed by the Executor PDA, and reports zero decode
   problems.

Real output (2026-09-29, `scripts/devstack/smoke.sh`, test-validator engine, first run on a fresh ledger):

```text
[smoke +   0s] stack up: crank healthy, indexer healthy; heads_down Config executor_fee 10000 crank_fee 7000; ORE round 422771 (ema 923847698 lamports/ORE)
[smoke +   1s] clock-in (automate + register_rig + set_caps + arm_shift, 1 tx 4JDfXf3E…): rig 3pcS1GUpUb3vi8ADUeivKPxu7v1Pdfh3hdyFZke3wYD5 Armed, shift 1
[smoke +   1s] crank intake status: {"ema_ev":631331000,"end_slot":278,"round_id":422771,"slot":120,"start_slot":38,"type":"status"}
[smoke +   1s] phone face-down: heartbeat #1 for round 422771 accepted by the crank (159 slots left)
[smoke +  68s] CRANK DUG round 422771: tx 3NjrtrT7…; RigDug 1000000 lamports on 10 squares (mask 0x000aade); rig Down, hb_counter 1, lease [422771, 422771]; Automation balance 48990000 (fee 10000)
[smoke +  68s] crank metrics: hd_crank_digs_landed_total 1, heartbeats accepted 1
[smoke +  68s] PHONE LIFTED after round 422771: no more heartbeats
[smoke + 101s] ORE round 422772 is now current (reset by the round driver)
[smoke + 102s] hostile crank REPLAYS heartbeat #1 in round 422772: tx 36AovpbL… -> RigSkipped(StaleHeartbeat)
[smoke + 103s] hostile crank REUSES the old lease in round 422772: tx 5a9Lf3DS… -> RigSkipped(LeaseExpired)
[smoke + 220s] round 422772 closed (board at 422772): crank did NOT dig the lifted rig (last_dug_round 422771, lease_to 422771); hd_crank_digs_landed_total 1
[smoke + 220s] INDEXER recorded RigDug: rig "3pcS1GUp…" round "422771" lamports "1000000" squares 10 (dataset localnet)
[smoke + 220s] indexer health: 7 txs ingested through slot 330, 0 decode problem kinds

SMOKE PASSED: face-down -> dug (round 422771); lifted -> no dig, replay and lease reuse refused on-chain (round 422772).
```

The Automation paid exactly 10 × 100,000 lamports into ORE squares plus the 10,000-lamport
Discretionary fee (50,000,000 → 48,990,000). The Executor PDA ended at 1,000,003,000: +10,000
fee in, −7,000 reimbursed to the crank. The crank's own log for the lifted round shows
`planned round=422772 … candidates=0 digs=0`.

The smoke passed on all three runs made on 2026-09-29:

- run 1 against a running stack;
- runs 2 and 3 as one command from a stopped stack: `up.sh` in about 60 s (builds cached),
  then the smoke in 260-275 s, then `down.sh`.

The timing depends on where in the ORE round the rig arms. The crank digs 20 slots before the
round's end, and the lifted round must run to its end.

## Fork engine: solana-test-validator (Surfpool evaluated)

**Default: `solana-test-validator` 4.1.2, with mainnet's programs and accounts dumped once.**
Surfpool was the preferred engine. It was installed without Docker and evaluated, and it is
kept as `up.sh --engine surfpool`, but it is not the default. Here is what was measured on this Mac:

| Check | Surfpool 1.6.0 (`--rpc-url https://api.mainnet-beta.solana.com`) | solana-test-validator 4.1.2 |
|---|---|---|
| Install | pinned release tarball, SHA-256 `a890db2b…` = GitHub's asset digest (`install-surfpool.sh`; the `curl \| bash` installer fetches `releases/latest` without a hash check, so it is not used) | already installed |
| secp256r1 precompile really verifies (valid lands, tampered rejected), `getTransaction` v1, SPL Token (`hd-devstack probe`) | PASS | PASS (the smoke) |
| Fork surgery, heads_down deploy, `initialize_config` | PASS (surgery through `surfnet_setAccount`) | PASS |
| Stays responsive with the crank + indexer running | **FAIL**: every account a local transaction or poll touches is fetched lazily from the public mainnet RPC. Under the crank's and indexer's polling, the RPC timed out on 38 of 40 `getSlot` calls (2 s timeout), the log showed 14 × `Failed to fetch accounts from remote: error sending request`, the driver's first airdrop failed, and no round started. With the crank and indexer stopped: 25 of 30 OK | PASS: no remote calls after start |
| Round ids | mainnet keeps creating `["round", id]` PDAs, so the lazily cloned `round_id + 1` would already exist and `reset` would fail. The surgery therefore moves local ids 10,000,000 ahead | mainnet ids continue from the dump |

Surfpool would probably be usable with a keyed RPC
(`HD_MAINNET_RPC=https://<provider>/?api-key=… up.sh --engine surfpool`, never committed).
That was not tested here. Do not use the project's Helius key for it: unlike the mainnet
scripts, the dev-stack scripts pass the RPC URL to `solana`, `solana-test-validator` and `surfpool`
on their command lines, where `ps` and shell history can see it. The
test-validator path needs the network only to dump fixtures and to copy mainnet's feature set
(`--clone-feature-set`). It is deterministic, and it is the one the smoke passes on.

**Why `--clone-feature-set` matters.** A bare test validator activates every feature it knows,
including SIMD-0500 (`B8JJXCy5…`, "disable deployment of SBPF v0, v1 and v2"). Both heads_down and
ORE are SBPFv0 (`cargo-build-sbf` 4.1.0 defaults to `--arch v0`). With all features on, the
deploy fails with `Detected sbpf_version required by the executable which are not enabled`.
SIMD-0500 is **inactive on mainnet** (checked with `solana -um feature status`), so the stack
copies mainnet's feature set. `HD_CLONE_FEATURES=0` runs offline and deactivates only SIMD-0500.

## Keeping ORE rounds moving: the ore-round-driver

On mainnet, three parties keep ORE going, and none of them runs on a fork:

1. **Miners start rounds.** A round starts at its first `deploy` (`deploy.rs:47-50`).
2. **The entropy provider reveals seeds.** `reset` requires the entropy Var to be sampled
   and revealed (`reset.rs:78-84`). The entropy program is a commit-reveal hash chain:
   `reveal(seed)` needs `keccak(seed) == var.commit`, and `next` (ORE CPIs it on the first
   deploy) sets `commit = seed`. Only ORE's provider knows the preimages.
3. **ORE's own crank calls `reset`.** It is permissionless (`reset.rs:21`), but it panics on a
   wrong top miner (`reset.rs:190-211`).

`hd-devstack driver` (`scripts/devstack/tool/src/driver.rs`) plays all three using only
public instructions of the **unmodified mainnet bytecode**:

| Role | What it sends | When |
|---|---|---|
| background miner | a manual ORE `deploy` of 10,000 lamports on a pseudo-random set of squares (its own wallet) | 2 slots after each reset, so the crank can dig late in the window as on mainnet (the crank keeps `start_rounds = false`) |
| entropy provider | entropy `sample` (tag 5, permissionless) + `reveal` (tag 4) | once `slot ≥ end_slot` (= `var.end_at`) |
| reset | ORE `reset` (tag 9) with the ORE mint CPI, the admin fee collector and the correct top miner | once `slot ≥ end_slot + intermission_slots` |

**The minimal entropy workaround, exactly.** Only one account is rewritten, once, at genesis:
the Var at `BWCaDY96…`. Its last revealed `seed` becomes the head `x_N` of a local hash chain
(`x_0 = keccak(secret | "hd-devstack-entropy-v1")`, `x_{i+1} = keccak(x_i)`, N = 100,000).
Its `end_at` and `start_at` become 0, and `samples` becomes 10^9. The secret is 32 random bytes
in `~/.config/heads-down/devstack/entropy-secret`. From then on, the real entropy program does
everything:

- ORE's `next` sets `commit = x_N`;
- the driver reveals `x_{N-1}` (`keccak(x_{N-1}) = x_N`), then `x_{N-2}`, and so on;
- the value is `keccak(slot_hash | seed | samples)`, where `slot_hash` is the local validator's
  real SlotHashes entry for `end_at`.

100,000 reveals last about 130 days of rounds. The randomness is only as unpredictable as a
local secret on the same machine. That is fine for development, and nothing like mainnet (see
the table below).

**Top miner.** The driver replays ORE's sampling to find the right account for a solo winning
square:

- `rng` = the XOR of the four u64 words of the value;
- the winning square is `rng % 25`;
- the square is solo iff `distribution_mask` has its bit set;
- the sample is `rng.reverse_bits() % deployed[square]`.

Candidates are the background miner and every heads_down rig's authority, then a filtered
`getProgramAccounts` over ORE. Split or empty squares pass a placeholder account, which ORE
never reads.

The other genesis rewrites (all in `tool/src/genesis.rs`):

| Account | Rewrite | Why |
|---|---|---|
| Board | `start_slot = 0`, `end_slot = u64::MAX` | the mainnet window is at slot ~451M, and a fresh ledger starts at 0. `u64::MAX` means "waiting for the first deploy", which is exactly what `reset` writes |
| Round `Board.round_id` | a fresh round: no SOL, `expires_at = u64::MAX`, rent-exempt | mainnet's in-flight round holds other miners' SOL, and its top miner cannot be known locally |
| ORE-mint Authority | `last_mint_at = 0` | the mint program requires 150 slots between mints, measured against mainnet's slot |
| ORE Config | only with `HD_ROUND_SLOTS` / `HD_INTERMISSION_SLOTS` | the default keeps mainnet's 240 / 48; the sum must stay ≥ 150 for the mint spacing |

Every other account is byte-for-byte mainnet: Treasury (Motherlode pot), ORE mint, the
Treasury's ORE token account, and the admin fee collector. So are all three programs: `ore.so`
sha256 `57503f43…`, which is the build verify.osec.io attributes to commit `b92c5043`
(docs/ORE.md), plus the entropy and ORE-mint programs. Driver log from the smoke run:

```text
[21:01:08] round 422771 started by the background miner: 14 squares x 10000 lamports (tx 63LYYcB3…)
[21:03:05] round 422771 ended at slot 278: entropy sampled + revealed (tx 2R6KGci9…, 99999 reveals left)
[21:03:28] round 422771 reset at slot 327: winning square 3 (split, 110000 lamports); next round 422772 (ema 877659561 lamports/ORE) tx 3joBtYDs…
[21:03:30] round 422772 started by the background miner: 12 squares x 10000 lamports (tx cdyS9vDH…)
```

## heads_down deploy and Config

- **Build:** `programs/heads-down/scripts/build.sh`, which uses the `mainnet` feature, so the real SGT
  anchors are compiled in. It produces `target/deploy/heads_down.so` (108,568 bytes).
- **Deploy:** `solana program deploy --use-rpc --program-id ~/.config/heads-down/heads_down-program-keypair.json --upgrade-authority <dev> --keypair <dev>`.
  The upgrade authority and fee payer is the local dev key
  `~/.config/heads-down/devstack/upgrade-authority.json`, airdropped 100 local SOL. Deploying
  leaves the program keypair where it is; it is only read.
- **`initialize_config`:** signed by that upgrade authority. heads_down checks the signer
  against its ProgramData, and the ORE layout hash comes from `heads_down::ore::layout_hash()`.

  | Field | Value | Why |
  |---|---|---|
  | `governance` | dev key `governance.json` | local only |
  | `registrar` | dev key `registrar.json` (also the key of the optional local registrar) | local only |
  | `executor_fee` | **10,000** lamports | ECONOMICS.md's placeholder; every rig's Automation must use exactly this fee |
  | `crank_fee` | **7,000** lamports | ORE's own executor fee. crank/README measures ~6,050 lamports (v0 + table, 5 rigs) to 6,723 lamports (3 rigs) of crank cost per fresh-heartbeat dig; `crank_fee ≤ executor_fee` holds |
  | `bury_bps` | 0 | no bury path yet |

  Override them with `HD_EXECUTOR_FEE` / `HD_CRANK_FEE`.
- **Executor float:** 1 SOL (`HD_EXECUTOR_FLOAT`), a plain transfer to
  `By3vJvQUsCLexnv7VqHuEhtZZCmpmjZjfhxvqCnWPkge`. That covers the rent floor + reserve +
  reimbursements with room to spare.

## Phone over USB

With the stack up and the Redmi plugged in (USB debugging on, RSA prompt accepted):

```bash
scripts/devstack/phone.sh                         # adb reverse tcp:8899/8900/8787/8788/8790
scripts/devstack/phone.sh --fund <wallet pubkey>  # ... and airdrop 10 local SOL to the phone's wallet
scripts/devstack/fund.sh <pubkey> 5               # funding alone
```

The phone then reaches the Mac at `http://127.0.0.1:8899` (RPC), `ws://127.0.0.1:8900`
(WebSocket), `ws://127.0.0.1:8787/ws` (crank intake) and `http://127.0.0.1:8788` (indexer). Only
127.0.0.1 is bound, so nothing is exposed on the LAN. Re-run `phone.sh` after replugging the
cable or restarting adb. Undo it with `phone.sh --remove`.

**How the app uses this stack.** Install the `localdev` build (`cd android && ./gradlew
:app:installLocaldev`): it is the only build that may talk plain `http`/`ws`, and only to
`127.0.0.1` or `localhost`. Its endpoints default to this stack's ports.

1. **Without a wallet app.** In the app: Setup, create the rig key, then Home → "Rig key and
   devstack (debug)". Copy the key's 33-byte hex and run
   `scripts/devstack/clock-in.sh <hex>`: a Mac-held dev wallet arms a Rig for the phone's Keystore
   key and prints `rig`, `authority`, `shift_id` and `hb_counter`. Paste the `authority` into the
   debug screen and tap **Attach and arm**. From then on the phone streams heartbeats to the
   crank over the same frames the smoke uses (`{"type":"heartbeat",…,"sig64"}` to `/ws`).
2. **With a wallet app** (Solana Mobile's fakewallet, or any MWA wallet). A wallet broadcasts to
   its own cluster, never to this fork, so the localdev build asks the wallet to sign only and
   submits through its own RPC. Fund the wallet with `phone.sh --fund <pubkey>`.

The Redmi 14C has no gyroscope and a virtual proximity sensor. That affects face-down detection,
not this stack.

## The app on an emulator

`scripts/devstack/emulator-smoke.sh` runs the real app end to end on a running emulator, with no
wallet app and nobody touching it (about five minutes):

1. installs the `localdev` APK, clears its data and walks the setup screens (notifications,
   background running, the Quick Settings tile, the rig key, created in the emulator's Keystore);
2. reads the key from the app's debug screen, arms a rig for it with a new Mac-held dev wallet,
   and attaches the app to that rig;
3. puts the emulator face-down, on the charger, screen off (`adb emu sensor set acceleration
   0:0:-9.81`, `adb emu power ac on`, `KEYCODE_SLEEP`) and waits until the crank has accepted a
   heartbeat signed by that key and landed a dig on-chain;
4. lifts it (upright, screen on) and waits until the BREAK the app signed has landed and the rig
   reads Cooling or Broken on-chain. It fails if the app crashed at any point.

One-time setup of an emulator (Apple Silicon; any Android 12+ image should do, Android 14 is what
the Redmi 14C runs):

```bash
sdkmanager "system-images;android-34;google_apis;arm64-v8a"
avdmanager create avd -n hd34 -k "system-images;android-34;google_apis;arm64-v8a" -d pixel_6
$ANDROID_HOME/emulator/emulator -avd hd34 -no-window -no-audio -no-boot-anim &
cd android && ./gradlew :app:assembleLocaldev && cd ..
scripts/devstack/up.sh && scripts/devstack/emulator-smoke.sh
```

Real output, 2026-10-04, Android 14 emulator, fork carrying ORE's build of 2026-10-02:

```text
[emulator-smoke +   4s] installed app-localdev.apk on emulator-5554 (Android 14) and started it
[emulator-smoke +  55s] setup done: notifications, background running, tile, rig key in the device's Keystore
[emulator-smoke +  69s] rig EL4oQC576rKZhtcZLjiykyc8QNUqwBZTXSuBeZCF4jLY armed for key 02ef4ab6520a2e68… by the dev wallet 7LxsWUi155FfZ3nLhkbVPq3NWqkhCRVAFJcNVgmRxkSK
[emulator-smoke +  88s] app attached: Attached to shift 1 (NIGHT, lease 2). Lay the phone face-down.
[emulator-smoke + 156s] the crank accepted a heartbeat signed by the device's Keystore key
[emulator-smoke + 265s] DIG LANDED on-chain with that heartbeat: tx 5JnyHyKxDcxBPELQcQhYfZ8U4vfUHRe74cC7ja27XLMToJU5CymZGrSbvV1ZyxPoW6YbHDfR2pSURSnDi9QK2XMR
[emulator-smoke + 277s] LIFTED: the app signed a BREAK and the crank landed it (the rig is now Cooling on-chain)

EMULATOR SMOKE PASSED: setup, Keystore key, attach, heartbeat, on-chain dig, pickup, BREAK landed. No crash.
```

**With a wallet app.** `emulator-smoke.sh --wallet <fakewallet.apk>` replaces the Mac-held dev
wallet with Solana Mobile's test wallet on the emulator (build it once from
`github.com/solana-mobile/mobile-wallet-adapter`, tag `v2.2.0`: `cd android && ./gradlew
:fakewallet:assembleDebug`, then pass `fakewallet/build/outputs/apk/v1/debug/fakewallet-v1-debug.apk`).
The app's own "Clock in" opens the wallet, builds the transaction for the account the wallet
authorizes, gets it signed, submits it through its own RPC and waits for the confirmation. After
the dig and the BREAK the same wallet signs the clock-out that ends the shift early, then the
withdrawal and the rig's close, which the script checks against the wallet's balance to the
lamport, and at last a second clock-in over the tombstone, which must arm shift 2.

```text
[emulator-smoke +   3s] installed app-localdev.apk on emulator-5554 (Android 14) and started it
[emulator-smoke +  52s] setup done: notifications, background running, tile, rig key in the device's Keystore
[emulator-smoke + 105s] CLOCKED IN with the wallet C4s3tEBsAojMXBRpcZGTrJEGWAG2qzV7MCMrCvh3YEw7: the app built the transaction, the wallet signed, the app submitted and confirmed it; rig 735DRy96YMNgR37RJG5Ty4tfCKZyT4AUGKWsoQjU8rt2 is armed
[emulator-smoke + 139s] the crank accepted a heartbeat signed by the device's Keystore key
[emulator-smoke + 248s] DIG LANDED on-chain with that heartbeat: tx 2eb9L2zTXkktmM2yusYMtXbnxsUsQhAtGrunNYwEY7hQqWSfdFdFzpjDo3YkAuzbZgKDGTGByaxSrtAT65BGAh2H
[emulator-smoke + 260s] LIFTED: the app signed a BREAK and the crank landed it (the rig is now Cooling on-chain)
[emulator-smoke + 309s] CLOCKED OUT with the wallet: Confirmed on-chain. Shift sealed as ended early.
[emulator-smoke + 356s] TOOK IT BACK with the wallet: +23639400 lamports, exactly what the screen said; the rig is closed
[emulator-smoke + 391s] CLOCKED IN AGAIN over the tombstone: the same rig address, now on shift 2

EMULATOR SMOKE PASSED (with a wallet app): setup, clock-in, heartbeat, on-chain dig, pickup, BREAK, clock-out, SOL back, rig closed, clock-in again. No crash.
```

The Quick Settings tile was tapped on the same emulator by hand (`adb shell cmd statusbar
click-tile xyz.headsdown.localdev/xyz.headsdown.surface.tile.HeadsDownTileService`): from a
running shift it ends the shift, from a cold rig it opens the wallet and the clock-in arms the
next shift. The morning reveal rendered a real haul from the local indexer.

What an emulator does not show: a hardware-backed Keystore and its attestation (the emulator's is
software, so the rig is a guest), HyperOS's background killing, a real accelerometer, a
production wallet (Solflare, Phantom, Seed Vault) and its own checks, and mainnet. The localdev
build also submits the signed transaction itself, where a release build lets the wallet send it.

## Logs, status, troubleshooting

`status.sh` for health, `logs.sh` (driver, crank and indexer together) or `logs.sh crank`. Every
`up.sh` moves the previous logs to `logs/prev/`.

| Symptom | Cause / fix |
|---|---|
| `port 8899 is in use` | another validator (`lsof -nP -iTCP:8899 -sTCP:LISTEN`), or a stale stack: `down.sh` |
| `deploy failed … sbpf_version … not enabled` | the feature set was not copied from mainnet (offline?). Use `HD_CLONE_FEATURES=0 up.sh`, which deactivates only SIMD-0500 |
| `init: … Program is not deployed` | the deploy landed in the same slot; `up.sh` already waits 2 slots, so re-run `up.sh` |
| driver: `Var commit … is not on the local entropy chain` | the ledger was built with another `entropy-secret`: `down.sh --wipe && up.sh` |
| driver: `no Miner holds sample …` | a solo winner the driver could not find (a miner outside the rigs and the background miner, with `getProgramAccounts` unavailable). Rounds stall until the next restart; please report it |
| crank `/healthz` 503 with a breaker reason | an ORE account failed its layout pin (a new ORE build in the dump?). `fetch-mainnet.sh` again, then run the crank fork suite |
| the indexer lags by ~15 s | it reads at `finalized` commitment, ~32 slots behind, polling every 5 s |
| `requestAirdrop … error sending request` on Surfpool | the lazy mainnet fetches are throttled on the public RPC; use a keyed `HD_MAINNET_RPC` or the default engine |
| fixtures too old | `up.sh --refresh-fixtures`. ORE upgrades change `ore.so`, so re-run the fork suites |

## What differs from mainnet

| Aspect | Local fork | Mainnet |
|---|---|---|
| Other miners | one background miner, 10,000 lamports on ~12 squares per round | ~170 miners, ~11 SOL per round. Tile crowding and `total_vaulted` are tiny locally, so `production_cost_ema` decays toward 0 by 5% per round and **the cost gate opens ever wider** |
| Randomness | local hash chain + local SlotHashes; the secret sits on the same machine | ORE's provider commit-reveal |
| Round starts | the background miner, 2 slots after reset | the first real miner |
| `reset` caller | the round driver | ORE's crank |
| Round ids | continue from the dumped round (Surfpool: +10,000,000) | live |
| Clock | fresh ledger at slot 0, 400 ms slots; unix time = wall clock | slot ~451M |
| Runtime features | copied from mainnet at start (`--clone-feature-set`) | live |
| ORE ProgramData pin | the crank's `ore_programdata_slot = 0` (disabled): genesis programs have no mainnet upgrade slot | 450,496,378 |
| Crank | single rigs per tx, local fees, no priority market; lookup tables are created locally | batched, Helius, real fees |
| Indexer | `localnet` dataset, no genesis-hash check, `ORE_API_ENABLED=0` (no api.ore.com rounds) | `mainnet` dataset, genesis-hash checked |
| SGT | the `mainnet` build's real anchors, but no SGT accounts exist locally, so Seeker verification cannot run here (guest rigs only) | real SGTs |
| Registrar | debug-keystore cert digest, sample revocation list file, `solana:localnet` | release cert, live Google status list |
| heads_down upgrade authority | a local dev key | Squads vault → revoked |

## Files and secrets

- **Nothing secret is in the repo.** Keys live in `~/.config/heads-down/devstack/` (directory
  700, files 600): `upgrade-authority.json`, `governance.json`, `registrar.json`, `crank.json`,
  `round-driver.json`, `background-miner.json`, `dev-wallet.json`, `entropy-secret` and
  `registrar-session-secret`. They are all local-only dev keys.
- The program keypair stays at `~/.config/heads-down/heads_down-program-keypair.json`. The scripts only read it.
- State lives in `~/.local/share/heads-down/devstack/`: `fixtures/` (the mainnet dump),
  `genesis/`, `ledger/`, `logs/`, `run/` (pids, `crank.toml`, crank lookup-table state, indexer
  DB) and `bin/surfpool`. Override the locations with `HD_DEVSTACK_KEYS` / `HD_DEVSTACK_HOME`.
- `scripts/devstack/.gitignore` ignores the tool's `target/` and, as a safety net, keypair-like
  files and state directories.

The Rust tool (`scripts/devstack/tool`, crate `hd-devstack`) reuses the crank's library for
RPC, layouts and events, and the program crate for the ORE layout hash. Build it with
`cargo build --release` (Rust 1.97.1); `cargo test` covers the entropy chain, the cluster guard, the instruction builders, the deploy arithmetic, and the paced buffer writer against a mock JSON-RPC node (42 tests). `scripts/devstack/rate-limit-proxy.py` is a loopback proxy that answers HTTP 429 to `sendTransaction` beyond a set rate; `scripts/mainnet/dry-run.sh` uses it for its rate-limit drill.
