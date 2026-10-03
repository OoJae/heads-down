# Security review, October 2026

This is the record of an internal review of Heads Down before its first deployment: what was
looked at, what was found, what was changed, and what is still open. It is not a third-party
audit. Nothing described here was ever deployed in its unfixed form.

- **When.** 1 to 3 October 2026, on `main` at `e4f9f62` and the branches merged after it.
- **How.** Ten review passes, each over one part of the system (the program's accounts, its
  cryptography, its ORE calls, its state machine and its SKR instructions; the Seeker Genesis
  Token verifier; the crank; the registrar; the Android client; operations and supply chain).
  Each finding of medium severity or higher was then handed to a second pass whose only job was
  to disprove it, by writing a test against the unmodified code.
- **Result.** 114 findings. 18 were confirmed by a reproducing test. 31 of medium severity or
  higher were not put through the second pass (it was cut short); they are treated below as real
  wherever the code agreed with them. 65 were low or informational.

The contract changes are in [INTERFACE.md §12.13](../programs/heads-down/INTERFACE.md). Every fix
has a regression test, named in the tables.

## 1. Confirmed findings

| # | Finding | Severity | Status | Where |
|---|---|---|---|---|
| 1 | The crank charged a rig's rate limit before checking the signature, so anyone could name a rig in garbage frames and silence its heartbeats, BREAK and FREEZE | high | **Fixed.** The bucket is peeked before verification and charged after it | `crank/src/intake.rs`; test `forged_frames_do_not_spend_the_rigs_allowance` |
| 2 | Stack: the attestation was checked only at `join_stack`, so a seat could swap in a software key afterwards and keep counting at attested-only tables | high | **Fixed** (v1.3). The attestation must be live at every check-in | INTERFACE §12.6; `tests/skr_stack.rs` |
| 3 | `dig` reimbursed the cranker whenever SOL was deployed, even when the Executor had received no fee, so a wallet cranking its own rig could draw the shared float down | medium | **Fixed.** Reimbursement only when the fee arrived in the same dig; the crank skips such rigs | test `a_dig_that_brought_no_fee_is_not_reimbursed` |
| 4 | `close_rig` then `register_rig` reset `shift_id`, `hb_counter` and `last_dug_round`: old signed messages replayed, a Stack seat forgot a recorded BREAK, and a round could be dug twice | medium (three findings) | **Fixed** (v1.3 tombstone, plus `last_dug_round`) | INTERFACE §12.4; `tests/tombstone.rs` |
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

## 3. Open, and stated plainly

These are true of what is deployed. None of them lets anyone take a user's mining funds, which
stay in the user's own ORE Automation and Miner accounts.

1. **Heartbeats reach the chain through a crank, and the team runs the only one today.** If no
   crank lands a rig's heartbeats, that rig does not mine (nothing is spent). But a Stack seat
   records gaps, and a Focus Bond's shift seals without dark rounds and the bond goes to the Bury
   lot. The program cannot tell a phone that was down from a relay that was down. Anyone can run
   the crank, and `record_heartbeats` and `stack_checkin` are permissionless; the Nostr mirror and
   a direct path from the phone to the chain are designed and not built.
2. **The upgrade authority is one key.** At launch the program can be upgraded by a single
   keypair held by the founder, with no multisig and no delay. Config changes (registrar, crank
   fee, pause) and governance rotation are behind a 72 hour on-chain timelock; program upgrades
   are not. Whoever holds that key could replace the program and take what the program's own
   accounts hold: Stack and Focus Bond SKR vaults, gift escrows, the Bury lot and the Executor
   float. It could not withdraw from anyone's ORE Automation or claim anyone's ORE.
3. **The app does not yet offer Revoke, Close rig or Claim ORE.** The instruction builders exist
   and are tested; the screens do not. Until they do, those actions need another client (the ORE
   app for the Automation and the claim). Unfreeze is built: a frozen rig's next clock-in carries
   `unfreeze_rig`, signed by the wallet, so Freeze is no longer a one-way door in the app.
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
- Behaviour on real hardware: Keystore attestation, the foreground service surviving a HyperOS
  night, and the wallet flows have been tested with unit tests and a phone-less end-to-end run on
  a local fork of mainnet (`scripts/devstack/smoke.sh`), not yet on a device.
- ORE itself. Heads Down inherits ORE's custody of every Automation and Miner.

## 5. Tests after the fixes

| Component | Tests |
|---|---|
| `programs/heads-down` (LiteSVM on a fork of live mainnet ORE, plus host unit tests, fuzz and golden vectors) | 171 |
| `crank` | 168, and 14 against the real program on the fork |
| `registrar` | 105 |
| `services/indexer` | 347 |
| `android` (JVM unit tests, all modules) | 764, of which 4 are skipped (they need a device or a network) |
| End to end, local mainnet fork | clock-in, dig, lift, replay refused, indexer: passes |

To report a vulnerability, use GitHub's private "Report a vulnerability" advisory on this
repository.
