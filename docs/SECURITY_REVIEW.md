# Security review, October 2026

This is the record of an internal review of Heads Down before its first deployment: what was
looked at, what was found, what was changed, and what is still open. It is not a third-party
audit. Nothing described here was ever deployed in its unfixed form.

- **When.** 1 to 3 October 2026, on `main` at `e4f9f62` and the branches merged after it. A
  second review on 4 October, of everything a deployment touches, is section 2c.
- **How.** Ten review passes, each over one part of the system (the program's accounts, its
  cryptography, its ORE calls, its state machine and its SKR instructions; the Seeker Genesis
  Token verifier; the crank; the registrar; the Android client; operations and supply chain).
  Each finding of medium severity or higher was then handed to a second pass whose only job was
  to disprove it, by writing a test against the unmodified code.
- **Result.** 114 findings. 18 were confirmed by a reproducing test. 31 of medium severity or
  higher were not put through the second pass (it was cut short); they are treated below as real
  wherever the code agreed with them. 65 were low or informational.

The contract changes are in [INTERFACE.md §12.13](../programs/heads-down/INTERFACE.md). Every fix
in section 1 has a regression test, named in its row. The later tables name a test where there is
one; changes to procedures, settings and documents have none.

## 1. Confirmed findings

| # | Finding | Severity | Status | Where |
|---|---|---|---|---|
| 1 | The crank charged a rig's rate limit before checking the signature, so anyone could name a rig in garbage frames and silence its heartbeats, BREAK and FREEZE | high | **Fixed.** The bucket is peeked before verification and charged after it | `crank/src/intake.rs`; test `forged_frames_do_not_spend_the_rigs_allowance` |
| 2 | Stack: the attestation was checked only at `join_stack`, so a seat could swap in a software key afterwards and keep counting at attested-only tables | high | **Fixed** (v1.3). The attestation must be live at every check-in | INTERFACE §12.6; `programs/heads-down/tests/tests/skr_stack.rs` |
| 3 | `dig` reimbursed the cranker whenever SOL was deployed, even when the Executor had received no fee, so a wallet cranking its own rig could draw the shared float down | medium | **Fixed.** Reimbursement only when the fee arrived in the same dig; the crank skips such rigs | test `a_dig_that_brought_no_fee_is_not_reimbursed` |
| 4 | `close_rig` then `register_rig` reset `shift_id`, `hb_counter` and `last_dug_round`: old signed messages replayed, a Stack seat forgot a recorded BREAK, and a round could be dug twice | medium (three findings) | **Fixed** (v1.3 tombstone, plus `last_dug_round`) | INTERFACE §12.4; `programs/heads-down/tests/tests/tombstone.rs` |
| 5 | One signed message with `counter = u64::MAX` ended a rig's phone-key path for good | medium | **Fixed.** A message may raise the counter by at most 2^32; the app accepts at most 2^20 per chain read | tests `a_counter_cannot_jump_past_the_step_bound`, `one chain read moves the counter by at most a million` |
| 6 | The app's wallet identity, its sign-in domain and its default service hosts pointed at `headsdown.xyz`, a domain registered by someone else | medium | **Fixed.** The identity is build configuration, no service has a default host, and a mainnet build fails without explicit values. The registrar's domain has no default | `android/app/build.gradle.kts`, `registrar/src/config.rs`; `check-endpoint-policy.sh` |
| 7 | The clock-in deposit and caps were computed from RPC data with no bound | medium | **Mitigated.** `executor_fee` above 100,000 lamports is refused, which bounds the deposit by the request; the home screen states the amounts before the wallet opens. The transaction is still not simulated by the app | test `a fee above the ceiling is refused` |
| 8 | A relayer that withholds heartbeats forfeits Focus Bonds and Stack seats, and the mitigations the threat model listed were not built | medium | **Open, documented.** See section 3 | THREAT_MODEL "As built" |
| 9 | The crank took the client address from the first `X-Forwarded-For` line, which the client can write | medium | **Fixed.** The last line of the header the trusted proxy writes; `X-Real-IP` on Railway; `GET /whoami` for the deployment check | test `the_client_address_is_the_proxys_line_never_the_clients` |
| 10 | Silent clients could hold connection slots for ever: a Ping restarted the idle timer | medium | **Fixed.** Only text frames count; a connection that never verified closes after 180 s. A host with many addresses can still churn connections | test `pings_do_not_keep_a_silent_connection_and_unverified_ones_are_closed` |
| 11 | `record_heartbeats` went in address order, so rigs with low-sorting addresses took the whole hourly budget | medium | **Fixed.** Longest-waiting rig first, ties broken per round | test `records_go_to_the_longest_waiting_rig_first_whatever_its_address` |
| 12 | A handful of rigs can use up the crank's hourly BREAK / FREEZE budget | medium | **Open, bounded.** The budget caps the operator's cost; a rig whose signal is not landed stops being dug within 3 rounds, and FREEZE can be sent by the wallet | crank README |
| 13 | Lookup-table slots are never reclaimed | medium | **Open.** Costs the operator rent, which closing the tables returns | crank README |
| 14 | A rig whose ORE checkpoint cannot succeed slows checkpoint sweeps and dig batches | low | **Open** | |
| 15 | "See this shift on-chain" opened any https URL the indexer supplied | low | **Fixed.** Only a tx or account page of solscan.io or explorer.solana.com | test `a link the indexer supplies can only be a page of a known explorer` |

## 2. Unverified findings that were fixed anyway

| Finding | Change |
|---|---|
| An opponent could end a seated rig's shift between two heartbeats once its plan window was over | A caller other than the authority must wait until the lease has been expired for 3 rounds |
| A one-base-unit sale at the floor set the price every later Bury lot started from; a dust forfeit restarted a running auction | Only a sale of 10 SKR or more sets the anchor; a lot never restarts below half the previous start; a deposit smaller than the lot joins the running auction |
| A leaked registrar key could sign vouchers valid for ever | A voucher runs at most 25,920,000 slots; the registrar refuses to issue a longer one |
| A rig closed just before a batch landed failed the whole `dig` or `record_heartbeats` transaction | The closed rig is skipped |
| One bogus `Board.round_id` from the RPC was signed for and then stalled the heartbeat feed | A round id far ahead of ORE's pace is not emitted; three consistent reads move the feed |
| The phone adopted whatever `shift_id` the RPC returned after a clock-in | The shift id is the one the clock-in armed; a re-read can only confirm it |

## 2a. Found after the review, while building

| Finding | Severity | Status | Where |
|---|---|---|---|
| The app decoded whatever sat at a wallet's Rig, Focus Bond, ShiftLog, ORE Automation, ORE Miner and SKR addresses. Anyone can send lamports to such an address before its program creates the account (about 0.00065 SOL at today's rent), and the app then failed to build every clock-in and clock-out for that wallet. A closed rig's 32-byte tombstone had the same effect, and a clock-in over one would have armed the wrong shift id. The program itself always handled both | medium (the app only; no funds at risk) | **Fixed.** An address that holds only lamports reads as not created. A tombstone reads as a closed rig, and the next clock-in resumes its `shift_id` and `hb_counter` | `android/core/chain`: `AccountBytes.kt` (`ifCreated`), `HeadsDownAccounts.rigSlot`; test `AbsentAccountsTest` |

## 2b. Found by running the app on an emulator

| Finding | Severity | Status | Where |
|---|---|---|---|
| On a phone with no secure lock screen the app crashed right after the wallet had signed. The vault that keeps the wallet's session token asked the Keystore for a key that needs a lock screen, and nothing caught the refusal. On mainnet the clock-in would already have been sent: SOL in the user's Automation and the rig armed on-chain, with the app dead and no shift running on the phone | high (no funds lost: the SOL stays in the user's own Automation and nothing digs without heartbeats; but the app was unusable on such a phone, at the moment money moved) | **Fixed.** Not being able to keep the token can no longer fail a session; on a phone with no lock screen the key is made without the unlocked-device requirement | `core/wallet`: `AuthTokenVault.save`, `KeystoreAesGcmCipher`; test `a Keystore that cannot seal the token loses the token, never the session`; `emulator-smoke.sh --wallet` |
| A Night Shift's plan window was a fixed 8 hours from clock-in. Inside the window a pickup is a BREAK, so anyone whose alarm rang earlier had a full night sealed as ended early: no streak, and a Focus Bond on it forfeit | medium (a user's bonded SKR, on an ordinary night) | **Fixed.** The window ends two minutes before the user's own next alarm when there is one for the morning; the home screen says until when a shift started now would run and that a pickup before then ends it early | `android/app`: `ShiftWindow.kt`; test `ShiftWindowTest` |
| Sealing a shift makes the wallet pay the rent of its log (about 0.0013 SOL, reclaimable after 30 days), and no screen said so | low (disclosure) | **Fixed.** The clock-out screen states the amount; the clock-in disclosure says it in words | `ClockOutCopy.rent`; test `sealing a shift states the log's rent` |
| The morning reveal said "the price gate stayed closed all night" for any shift with no dig, including one in which the phone never went dark | low (a false statement on screen) | **Fixed.** A shift with no dark round says so; a dark shift with no dig says nothing was dug and that a closed gate is the usual reason | `HaulMath.verdict`; test in `RevealCopyTest` |
| The fourth Focus Bond choice did not fit its row and was drawn one letter per line | cosmetic | **Fixed.** The choices wrap; a test measures real text | `HomeScreen.kt`; test `every Focus Bond choice stays on one line on a narrow phone` |

## 2c. Found by the review before deployment, and by the first day on Railway (4 October 2026)

Before any SOL was sent, five more passes went over what a deployment touches: the container
builds, the services' settings and their start order, where the money goes and what comes back,
where a secret could leak, and the page behind the app's identity address. A second pass tried to
disprove each finding of medium severity or higher in four of the five (the identity page's pass
had none). 65 findings: 4 that would have stopped a step, 9 high, 19 medium, 21 low, 12
informational. Of the 25 that were re-checked, 24 were confirmed and 1 could not be decided
without sending transactions; the second pass added 17 of its own. Every code change that followed
was written by one pass and attacked by another, which found and fixed further defects in each.
Nothing here was live in its unfixed form, except where the table says "found on the live service".

| Finding | Severity | Status | Where |
|---|---|---|---|
| The deploy would most likely have stopped half way on Helius' free plan (the Solana CLI sends about 200 writes 10 ms apart; the plan allows one a second), and could not be continued: preflight compared the full cost with the deployer's balance while 0.9996 SOL sat in the buffer, and told the founder to send that SOL again | high (no funds lost; a stalled deploy and a misleading request for 1 SOL) | **Fixed.** `deploy.sh` writes the buffer with a paced writer that can be stopped and continued, preflight counts what the buffer holds, and the rehearsal stops a deploy part way and continues it with exact funding. On the local fork behind a one-send-a-second proxy the CLI alone landed about 15 of 198 writes; the paced writer finished. Not run against Helius or on mainnet | `scripts/devstack/tool/src/buffer.rs`, `scripts/mainnet/deploy.sh`; `dry-run.sh` steps 3, 4 and 7 |
| The first version of that writer never stopped when its writes were taken and could not land (a payer that ran out), and could land one chunk twice after a send with no answer | medium (found by the second pass) | **Fixed.** Only a write seen to land counts as progress, the payer is checked first, and a send with no answer is watched, not sent again | tool tests against a mock node |
| An upgrade to a build that outgrew the program's space failed at the first try once the buffer was written beforehand: the CLI extended and upgraded back to back, and the loader refuses both in one slot | high (found by the second pass; no funds at risk, a second run completed) | **Fixed.** `deploy.sh` sends the extension itself and waits; preflight budgets the loader's 10,240-byte minimum | `dry-run.sh` step 9 |
| A crank with an empty fee payer retried creating its lookup table without pause (about 335,000 RPC calls a day), and several failure paths could create a second table and forget the first. Each table locks 0.00256 SOL until it is closed by hand | high | **Fixed.** No create without the balance for it, a growing wait after a failure, the address on record before the create is sent, an answer older than the crank's own transaction is refused, and `max_tables` counts the tables on record | `crank/src/crank.rs`, `crank/src/alt.rs`; `crank/tests/lookup_tables.rs` |
| At the shipped polling rates the crank and the indexer used about 410,000 Helius credits a day with no rig at all: the free plan's month in under three days | high | **Fixed in part.** An idle crank reads nothing in most rounds, its chain poll is a setting, and the indexer scans accounts only when a transaction arrived: about 30,500 credits a day for the crank with the one-phone settings and about 2,000 for the indexer. That is still about the whole free plan in a month, so the crank is run for test nights, not left on | DEPLOY.md section 5; `crank/README.md` "RPC budget" |
| The page at the app's identity address said that the address next to a signing prompt proves the request comes from the app. A wallet cannot verify that; any app can name the address | would have stopped publication | **Fixed before the page was published.** The page says what the wallet can and cannot check | `site/index.html` |
| "Nothing but your phone's own hardware key can switch it on: not a server, not us" was stronger than the threat model: the program accepts any P-256 key the wallet registers, an emulator's key is software, and one key held by the founder can upgrade the program | high (wording) | **Fixed.** The page, the README, the pitch texts, the dashboard and the app say "a key in the phone's Android Keystore"; the app says "secure hardware" only where Android reports the key lives there | test `the rig key is called hardware only where Android says it is` |
| The program's rent (0.9996 SOL) comes back only by closing the program for good, and the runbook's next steps (Squads, then final) remove that way out without saying so | high (a fact to decide on, not a defect) | **Documented.** DEPLOY.md section 3 says what closing strands and in which order to leave; the Squads step is now a decision | DEPLOY.md sections 3 and 15 |
| Railway does not read `railway.json` for services created now, and the documented order deployed a service before it was configured | would have stopped the first deployments | **Fixed.** The settings are set on each service; the runbook describes the order that was used | DEPLOY.md section 10 |
| The registrar exits at start without an app certificate digest, and no release key exists | would have stopped the registrar | **Fixed for the founder's phone only.** The registrar accepts this Mac's debug-signed build and says so on `/registrar`; that setting has to go before anyone else installs the app | DEPLOY.md 10.4 |
| Private keys would have passed through an assistant's conversation on their way to Railway, and Railway's API cannot seal a variable | high | **Procedure.** A secret goes from its file to Railway's CLI through stdin, or is pasted in the dashboard, and is sealed there | DEPLOY.md 10.3 |
| The indexer exited when its RPC check failed at start, after the healthcheck had passed; a lost Postgres connection ended it too | medium | **Fixed.** The check is retried inside the loop while the API serves; the webhook waits for it; pool and client errors are logged | `services/indexer/src/loop.ts`; `test/serve.test.ts`, `test/db.test.ts` |
| Both entrypoints printed the keypair under shell tracing and left the key file behind on an early exit; the only guard for the `/data` volume was in a file Railway does not read; the first-deploy check sent the operator into a shell where `env` prints the keys | medium | **Fixed.** Tracing is refused, the key directory is removed on the way out, the entrypoints refuse to start on Railway without the volume, and the check is a log line. All of it ran on Railway for the registrar | `deploy/railway/test_entrypoints.py` |
| The crank's default spend caps were as large as its whole 0.05 SOL float, and one rig's digs cost the crank more than the program reimburses | medium | **Fixed** (caps sized for the float) and **documented** (the float is spent in use) | `deploy/railway/crank/crank.toml`; DEPLOY.md section 3 |
| The emergency pause used the same Helius key the services could exhaust | medium | **Fixed.** `--public-rpc` on every operator script | `scripts/mainnet/lib.sh`; `selftest.sh` |
| Only the deployer's key file was compared with the address SOL is sent to | low | **Fixed.** The crank payer's and governance's are pinned too; all three pass against the real key files | `scripts/mainnet/lib.sh`; `selftest.sh` |
| A fixture script wrote the RPC URL, key included, into tracked files; the root `.gitignore` did not cover the key file names; the dashboard build accepted a URL with a key in it; the indexer and the registrar could print a keyed URL | medium to low | **Fixed** | `fetch_fixtures.py`, `.gitignore`, `deploy/railway/dashboard/Dockerfile`, `services/indexer/src/sources/rpc.ts`, `registrar/src/config.rs` |
| The registrar keyed its rate limits on a header Railway does not document | low | **Fixed.** An `X-Real-IP` option; on the live service 60 requests with 60 made-up addresses gave 24 answers and 36 refusals, so the edge replaces a client's own header | `registrar/src/http/ratelimit.rs` |
| Costs missing from the runbook: Squads' 0.1 SOL fee, the loader's minimum extension, the SOL an upgrade borrows, the wallet on the phone; governance rotation described as scripted when it is not | medium to info | **Documented** | DEPLOY.md sections 3, 15, 16 |
| Found on the live service: the public Solana RPC, the registrar's default, refuses requests from Railway's servers. Every attestation answered 503 while `/healthz` said ok, and the app registered a guest rig | medium (no funds; a rig that should be attested is not) | **Fixed.** The registrar uses a keyless RPC that answers, and says at start whether its RPC gives a slot | DEPLOY.md 10.4; `registrar/src/slot.rs` |
| Found on the live service: the indexer's first read of ORE's rounds asked ORE's API for 14 days at once, was rate-limited, stored nothing and started over every pass | medium (no funds; an empty dashboard, and over a hundred wasted requests to ORE's API per pass) | **Worked around on the service** (a one-day window). The fix in code is written and waiting for its second pass | `ORE_ROUNDS_SINCE` |
| Found by running the combined code: the crank's end-to-end test on a validator had not been run since 3 October and carried an expectation its own setup contradicted | low (a test, not the crank) | **Fixed.** The suite passes against the real program | `crank/tests/e2e_validator.rs` |
| A build made as the runbook showed would not dig today: ORE's cost gate (about 770,000,000 lamports per ORE) is above the app's default ceilings (530,000,000 and 670,000,000) | medium (the product working as designed; a demo that shows no dig) | **Decided on 10 October 2026.** The build for the recorded demo raises the ceilings to 1,000,000,000 and 1,200,000,000 lamports per ORE and lowers the budgets to 0.005 SOL a shift and 0.035 SOL a week. It places real SOL | DEPLOY.md 10.7 |
| Nothing reclaims the rent of the crank's lookup tables or of the ShiftLogs the crank pays for | low | **Open.** The commands for the tables are in DEPLOY.md section 16; the one-phone settings use no table and seal no shift | |
| With the one-phone settings, a flood of frames naming made-up rigs can delay a phone's first heartbeat after an idle stretch | low | **Open, documented** | `crank/README.md` |
| Attestation is tried once, when the rig key is made. If the registrar cannot be reached at that moment, the rig stays a guest | low | **Open** | |
| The Helius key in use is shared with another project of the founder's, so both draw on the same credits | medium (availability) | **It happened, and the key was replaced.** On 10 October 2026 that key's monthly credits were used up and Helius refused every call (`max usage reached`). The deployment now uses a new key | DEPLOY.md section 5 |

What this pass did not do: build an image with Docker (none on the build machine; the three
services that run were built by Railway), run anything against Helius' rate limiter, send a
transaction to mainnet, or run the app on a phone.

## 3. Open, and stated plainly

These are true of the code as it will be deployed (the program is not on mainnet yet). None of them lets anyone take a user's mining funds, which
stay in the user's own ORE Automation and Miner accounts.

1. **Heartbeats reach the chain through a crank, and the team runs the only one today.** If no
   crank lands a rig's heartbeats, that rig does not mine (nothing is spent). But a Stack seat
   records gaps, and a Focus Bond's shift seals without dark rounds and the bond goes to the Bury
   lot. The program cannot tell a phone that was down from a relay that was down. Anyone can run
   the crank, and `record_heartbeats` and `stack_checkin` are permissionless; the Nostr mirror and
   a direct path from the phone to the chain are designed and not built.
2. **The upgrade authority is one key.** At launch the program can be upgraded by a single
   keypair held by the founder, with no multisig and no delay. Config changes (registrar, crank
   fee, un-pausing) and governance rotation are behind a 72 hour on-chain timelock, and pausing
   is immediate; program upgrades are not delayed at all. Whoever holds that key could replace the program and take what the program's own
   accounts hold: Stack and Focus Bond SKR vaults, gift escrows, the Bury lot and the Executor
   float. It could not withdraw from anyone's ORE Automation or claim anyone's ORE.
3. **The ways out are in the app, and none of them has run on a physical phone or with a
   production wallet yet.** Revoke ("Take SOL
   back") closes the wallet's ORE Automation and returns every lamport in it; it is never refused
   and works without a rig bound to the phone, so it survives a reinstall. Close rig returns the
   Rig's rent, and is offered only when no shift is open and no Focus Bond is still locked.
   Unfreeze rides on the next clock-in. Claim is on the clock-out screen. Revoke and Close rig
   have run in the app on an Android 14 emulator against a local fork of mainnet, signed in
   Solana Mobile's test wallet; Unfreeze and Claim are unit-tested against the program's golden
   vectors and an in-memory cluster only (section 4).
4. **An in-person Stack table is open to any rig that pays the bond.** The program does not check
   that the players are in one room.
5. **The registrar's log is a local file.** It records every voucher, but nothing yet lets a third
   party check it against the chain.
6. **An ORE Board layout change would block `end_shift`**, and with it the release of Focus Bonds,
   until the program is upgraded. This is a reason not to make the program immutable yet.
7. **No certificate pinning** in the app: TLS with the system trust store.
8. **Deployment keys are plain files** on the build machine (mode 600, outside the repository).

## 4. What this review did not cover

- A third-party audit. There has been none.
- Most behaviour on real hardware. The app has run on an Android 14 emulator against a local fork
  of mainnet (`scripts/devstack/emulator-smoke.sh`): setup, a Keystore key, heartbeats, an on-chain
  dig, a pickup and its BREAK, and with Solana Mobile's test wallet the clock-in, the clock-out,
  taking SOL back, closing the rig and clocking in again, each signed in the wallet. The same
  emulator has signed in to the live registrar through the test wallet (the registrar refused its
  software key, as it should). An emulator has a software Keystore and stock Android, and the test
  wallet is not a production wallet. On 10 October 2026 the app ran on a physical phone for the
  first time, a Redmi 14C (Android 16, HyperOS 3): setup, a rig key made in the phone's TEE, that
  key's attestation chain accepted by the live registrar, and a sign-in signed in Jupiter's
  wallet. Still untested: the foreground service surviving a HyperOS night, a production wallet
  signing and sending a transaction, Solflare, Phantom and Seed Vault altogether, and any
  transaction of the program on mainnet.
- ORE itself. Heads Down inherits ORE's custody of every Automation and Miner.

## 5. Tests after the fixes

| Component | Tests |
|---|---|
| `programs/heads-down` (LiteSVM on a fork of live mainnet ORE, plus host unit tests, fuzz and golden vectors) | 171 |
| `crank` | 213, 14 against the real program on the fork, and one end to end on a local validator |
| `registrar` | 116 |
| `services/indexer` | 385 |
| `android` (JVM unit tests, all modules) | 855 passed; 4 more are skipped (they need a mainnet RPC or a running devstack) |
| End to end, local mainnet fork | clock-in, dig, lift, replay refused, indexer: passes |

To report a vulnerability, use GitHub's private "Report a vulnerability" advisory on this
repository.
