# Heads Down on mainnet

What is on mainnet, and the first shift a phone ran against it. Every address and transaction on
this page is a link to Solscan. Every figure comes from one of three places, named where it is
used: the two receipts committed under [`deploy/receipts/mainnet/`](../deploy/receipts/mainnet/),
the transactions as the RPC method `getTransaction` gives them, and accounts read from the
public mainnet RPC on 10 October 2026. Times are UTC. Amounts are lamports unless a unit is
written (1 SOL is 1,000,000,000 lamports).

Status on 10 October 2026:

- Deployed on mainnet on 10 October 2026.
- One rig, the founder's own phone, has run one short shift: five digs, 0.005 SOL placed. There
  are no users.
- One key can upgrade the program at once. No third-party audit.
- That shift ran on a debug build with a raised cost ceiling. With the default build nothing
  would have dug that evening.

Contents: [What is deployed](#what-is-deployed) · [Services](#services) ·
[The first shift](#the-first-shift-10-october-2026) · [What it cost](#what-it-cost) ·
[What the first night found](#what-the-first-night-found) · [Not run yet](#not-run-yet) ·
[Check it yourself](#check-it-yourself)

## What is deployed

| What | Address | |
|---|---|---|
| Program `heads_down` | [`HDn4vgLWFLLdexKEwfZwRHjWtizNvdqFteLbMsE67F9p`](https://solscan.io/account/HDn4vgLWFLLdexKEwfZwRHjWtizNvdqFteLbMsE67F9p) | upgradeable; 190,048 bytes of SBPF v3 |
| ProgramData | [`3jjGZ8EE8DJcRag9eTFMMxStNo55PHLa5xVPktW52WPZ`](https://solscan.io/account/3jjGZ8EE8DJcRag9eTFMMxStNo55PHLa5xVPktW52WPZ) | room for 196,608 bytes; holds 999,647,480 lamports of rent |
| Upgrade authority | [`9DSVM862oJrstiPmmQmgqXb7AkuXrKJtYgd1fwbeqeeW`](https://solscan.io/account/9DSVM862oJrstiPmmQmgqXb7AkuXrKJtYgd1fwbeqeeW) | the deployer: **one key**, no multisig, no delay |
| Config | [`inzDn4ogmXbx9YDAKDHkfwJHy1jhsaWxGQvricDAEmW`](https://solscan.io/account/inzDn4ogmXbx9YDAKDHkfwJHy1jhsaWxGQvricDAEmW) | `executor_fee` 10,000, `crank_fee` 7,000, `bury_bps` 0, not paused |
| `Config.governance` | [`37u9LWbPrzQFkfL6oXGoSfq9souRggVtGvYszRXHezXN`](https://solscan.io/account/37u9LWbPrzQFkfL6oXGoSfq9souRggVtGvYszRXHezXN) | one key: it can pause at once, and its other changes wait 72 hours on chain |
| `Config.registrar` | [`9deCPaA6iML39zQw4mptBeHRkm7DE6oVWGijdA9c2zgo`](https://solscan.io/account/9deCPaA6iML39zQw4mptBeHRkm7DE6oVWGijdA9c2zgo) | signs attestation vouchers off chain |
| Executor PDA | [`By3vJvQUsCLexnv7VqHuEhtZZCmpmjZjfhxvqCnWPkge`](https://solscan.io/account/By3vJvQUsCLexnv7VqHuEhtZZCmpmjZjfhxvqCnWPkge) | System-owned, no data; 1,450,240 lamports at the start, 1,465,240 after the first shift |
| Crank fee payer | [`5Xec1ZUwXcB2ZGeWqqBHrxaHT4WQrGVUgH9xmqgC1kzk`](https://solscan.io/account/5Xec1ZUwXcB2ZGeWqqBHrxaHT4WQrGVUgH9xmqgC1kzk) | a hot key on Railway; it pays the crank's fees and holds no other authority |

**The program.** Deployed in slot 455,359,196, at 18:46:48, by transaction
[`4naPfgTX…`](https://solscan.io/tx/4naPfgTXDKRxG7NhunoNKVBWkCwuvdJoyBGytmebCpuDzdrP6Gm4UwxNYm5r5zzUu8vuZKgYiaFp1BzrTwH2Pae2),
from commit `d67a1a40c2a0`. The ProgramData holds exactly the build: 190,048 bytes, sha256
`07dd870a879faac20d4932f297da3b50719c9b900761409250c8841cc7b8527d`, and with the trailing zero
bytes stripped the program hash
`0154706c62cb7bb6aacf463501e49b016b87809ed7193fcae08afc4e3f891b58`. Both hashes were computed
again from the chain for this page ([Check it yourself](#check-it-yourself)).

**One key can upgrade it.** The upgrade authority is the deployer's single keypair. Whoever
holds it can replace the program's code in one transaction, with no second signature and no
waiting time, and can close the program. Moving the authority to a multisig with a time lock is
described in [DEPLOY.md, section 15](DEPLOY.md#15-squads-upgrade-authority-and-governance). It
has not been done and has not been decided.

**No audit.** No third party has audited the program. The reviews in
[SECURITY_REVIEW.md](SECURITY_REVIEW.md) are the team's own.

**How the deploy went.** Three transfers funded the keys: 1,040,963,000 lamports to the deployer,
50,963,000 to the crank fee payer and 14,963,000 to governance. The first run of
`scripts/mainnet/deploy.sh --yes` stopped at its own preflight with
`getGenesisHash: http: error sending request`: the founder's connection had dropped (ping showed
round trips of 0.8 to 1.8 s, and one request to Helius timed out). Nothing was sent. The second
run, minutes later, went through on Helius' free plan:

| Step | Transaction | From the receipts |
|---|---|---|
| The buffer is created | [`4oMZLT6g…`](https://solscan.io/tx/4oMZLT6gapW44Kaz46aB5ALW8XWeCx5gFcGpf8zGyZy3HrKo3jjz3Jf9XShkYnJTMxQCNRt8UiM7vKTcwAxEeSZC) | buffer [`26iQMRCb…`](https://solscan.io/account/26iQMRCbUqtVBm2XC5SztLtJc8PPxgqiNMDrSqyGpxiZ); the receipt records 999,647,481 lamports in it |
| The buffer is written | 198 writes, one a second | 198 sent, 198 confirmed, 0 signed again, 0 slow-downs, 288 s; 1,053,286 lamports of fees with the creation |
| The program is deployed | [`4naPfgTX…`](https://solscan.io/tx/4naPfgTXDKRxG7NhunoNKVBWkCwuvdJoyBGytmebCpuDzdrP6Gm4UwxNYm5r5zzUu8vuZKgYiaFp1BzrTwH2Pae2) | slot 455,359,196; one transaction by the Solana CLI, 10,297 lamports |
| `initialize_config` | [`3R4UnCec…`](https://solscan.io/tx/3R4UnCecw7BuzAvLZURtktLRnh6iEboPE6CpX35NdX7sKzjJcL4g84i1uxQdwwWHPioyEVcG3uBp4cfAhQFzJzUs) | slot 455,359,343; 7,335 lamports |
| The Executor float | [`5pPKTrBQ…`](https://solscan.io/tx/5pPKTrBQLPUY8uk2Lf1mMysNAy4EwmgAWi7mDYwTEnwm5Xp5LdQtXptEnq3yf3JkWKuoPQ8EEpDNtbJ6bDjaAeK3) | slot 455,359,384; 1,450,240 lamports to the Executor PDA, 5,136 of fee |

Where the deployer's 1,040,963,000 lamports are now: 999,647,480 in the ProgramData, 833,120 in
the Program account, 1,950,720 in the Config, 1,450,240 in the Executor, 1,076,054 paid in fees
by the receipts (1,053,286 + 10,297 + 7,335 + 5,136), and 36,005,387 still in the deployer. That
is one lamport more than the sum allows. The receipt's 999,647,481 lamports in the buffer are one
more than the 999,647,480 its creating transaction put in, and the deploy handed the buffer's
balance back to the deployer. Where that lamport came from was not looked up. The receipt's
1,053,286 is itself a difference of the deployer's balances, not a sum of fees. The creating
transaction paid 10,421 (read from the chain), and 198 writes at 5,267 each (one signature and
267 of priority, computed from the receipt's compute-unit limit and price) are 1,042,866:
together 1,053,287. If the writes cost that, a second lamport reached the deployer while they
ran. Its other transactions were not read.

**The Helius key.** Earlier the same day, before the deploy, the Helius key first used here ran
out of monthly credits (`max usage reached`): it was shared with another project. It was
replaced.

## Services

Four services on Railway, all against mainnet, and a Postgres with no public port.

| Service | Address | On 10 October 2026 |
|---|---|---|
| crank | <https://crank-production-21c2.up.railway.app> (`/healthz`, `/metrics`; the phones' intake is `wss://crank-production-21c2.up.railway.app/ws`) | built by Railway from `deploy/railway/crank/Dockerfile`; started at 18:48:47 with the fee payer [`5Xec1ZUw…`](https://solscan.io/account/5Xec1ZUwXcB2ZGeWqqBHrxaHT4WQrGVUgH9xmqgC1kzk) |
| indexer | <https://indexer-production-88dc.up.railway.app> | reads the chain through Helius since 17:36; the fix to its ORE round backfill is live, and its `ORE_ROUNDS_SINCE` override is removed |
| dashboard | <https://dashboard-production-b80c.up.railway.app> | the public numbers, from the indexer |
| registrar | <https://registrar-production-71d0.up.railway.app> | attested the rig key of the phone below |

- The crank's log on its first start shows
  `key file removed; hd-crank (pid 13, uid 10001) on 0.0.0.0:8787`: its entrypoint took the key,
  dropped from root to uid 10001 and removed the key file. It was the first run of the crank's
  entrypoint on Linux, and the first log line of either entrypoint that names the uid. The
  registrar's entrypoint had run on Railway on 4 October.
- An earlier deployment of the crank without its key, a deliberate test of the build, built the
  image and stopped at the entrypoint with `HD_CRANK_KEYPAIR_JSON is not set`, as designed.
- Variables on the crank besides its two secrets: `PORT=8787`, `RUST_LOG`, the six one-phone
  overrides of `deploy/railway/crank/.env.example`, and three more since about 19:00
  ([What the first night found](#what-the-first-night-found)).
- After the first shift the indexer's `/v1/summary` showed one rig in total, a guest rig, with the
  `RigRegistered` transaction as its evidence. At 20:47 it still did, and the crank's `/healthz`
  answered `"status":"ok"`.
- Sealed on Railway by the founder on 10 October 2026: `HD_REGISTRAR_KEYPAIR_JSON` and
  `HD_SESSION_SECRET` on the registrar, `HELIUS_API_KEY` and `HD_CRANK_KEYPAIR_JSON` on the crank
  (the key was set from the key file through the Railway CLI's standard input and sealed the same
  evening), `RPC_URL` on the indexer. Checked afterwards: the CLI's variable listing gives no value
  for any of the five.
- No uptime check and no balance alert is set up yet
  ([DEPLOY.md, section 11](DEPLOY.md#11-monitoring-and-alerts)).

## The first shift (10 October 2026)

**The phone.** A Redmi 14C (model 2409BRN2CA) on Android 16 with HyperOS 3, accelerometer only.
The rig's key is in the phone's TEE and was attested by the live registrar: on chain the rig is
registered as tier 0 (guest) with attestation level 1 (TEE). The wallet is Jupiter Mobile, at
[`D1QTG9WyrRGupYKpB9fpapJm4L9dEEu8HPVFgc5FudCW`](https://solscan.io/account/D1QTG9WyrRGupYKpB9fpapJm4L9dEEu8HPVFgc5FudCW).
The app was a debug build with the mainnet endpoints.

**The policy, and why it was not the default.** The default build arms a plan of 0.53 SOL per
ORE under a wallet-signed ceiling of 0.67, and a rig digs only while ORE's cost figure (`ema_ev`)
is within the plan. During the shift that figure was 0.690 to 0.704 SOL per ORE (the five digs
carry 691,153,003 to 697,661,524 lamports per ORE in their events). With the default build
nothing would have dug. The build for this shift carried the demo policy of
[DEPLOY.md, section 10.7](DEPLOY.md#107-the-app-build-that-talks-to-this-deployment): a plan of
1.0 SOL per ORE under a wallet ceiling of 1.2, at most 0.005 SOL a shift and 0.035 SOL a week,
digs of 0.001 SOL on 4 squares, and a lease of one round. The caps the wallet signed in the first
transaction are those amounts with the 10,000-lamport executor fee of each dig inside them:
1,010,000 a round, 5,050,000 a shift, 35,350,000 a week.

**Every transaction, in order.** The first, second, twelfth and thirteenth were signed by the
wallet in Jupiter Mobile. The other nine were signed and paid for by the crank's fee payer; each
carries a message the phone signed with its rig key, which the program checks through the
secp256r1 precompile. "Reimbursed" is the `crank_fee` the program pays the crank inside a dig that
deployed.

| # | UTC | Slot | Transaction | Signed by | What it did | The program's event, or why it skipped | Fee | Reimbursed |
|---|---|---|---|---|---|---|---|---|
| 1 | 18:52:10 | 455,360,661 | [`4DvPBhh4…`](https://solscan.io/tx/4DvPBhh4UiEhezYPmjcJvHeqSGGfCAj9prnKEFPMGaGRe9sGg7r9A2SMPVTo4UYXjeEkHUc1WCA7VRdznZuPaQht) | wallet | The first clock-in, in one transaction: ORE `automate` (the wallet's own Automation, with the Executor PDA as its executor), the registrar's voucher (Ed25519), then `register_rig`, `set_caps` and `arm_shift` | `RigRegistered` (tier 0, attestation level 1), `ShiftArmed` (shift 1) | 10,000 | |
| 2 | 18:57:08 | 455,362,014 | [`4VGSxKzF…`](https://solscan.io/tx/4VGSxKzFtfnePTtUuVgjjavFFXWXZcN1KLTKU4ePrFcjGUyRwruQXHNvCnticVzmrxSg4QXAetuWn6svKFv3ZCpR) | wallet | A second clock-in: `end_shift` for shift 1, then `set_caps` and `arm_shift` | `ShiftEnded` and `ShiftEndedV2` (shift 1: 0 dark rounds, 0 rounds dug, 0 lamports, reason 4, rounds 435,218 to 435,222), `ShiftArmed` (shift 2) | 5,000 | |
| 3 | 18:58:11 | 455,362,308 | [`3Komo8ws…`](https://solscan.io/tx/3Komo8wsU2WcCwPzEQmjq7EriciqucYeXBKtRUDD9Mu6cr3giq1g9m6YchaaR7QDCy6hkURHGxPfh8W6LByf8KJu) | crank | `dig` for round 435,223 with the phone's heartbeat 1. It landed 5 slots after the round had ended. No ORE instruction ran | `RigSkipped`, error 25 `RoundNotActive` | 10,038 | 0 |
| 4 | 18:59:10 | 455,362,581 | [`25nBRCP6…`](https://solscan.io/tx/25nBRCP6GExrKG4EDszGrKfWN9yYgJMopgnawTGi2Z9XNGj6UCtZgE6HFJN7rJXvu2C3VMjhh7bpXZnQg8DVzovY) | crank | **The first dig on mainnet from a phone.** `dig` for round 435,224 with heartbeat 2, and inside it ORE `deploy` through the Executor PDA. ORE's log: `Round #435224: deploying 0.00025 SOL to 4 squares` | `RigDug` (1,000,000 lamports on 4 squares) | 10,053 | 7,000 |
| 5 | 18:59:10 | 455,362,581 | [`4EJvGg7a…`](https://solscan.io/tx/4EJvGg7akbzgQRbT2cYteaHCogpnEaBpP2ncTHLFS1mtCWt5e9aVFJEmeiFgbaShHC7H7t2EkpwxQYKpcWq6PUpM) | crank | The crank's own second attempt for round 435,224, with the same heartbeat, in the same slot. No ORE instruction ran | `RigSkipped`, error 7 `StaleHeartbeat` | 10,053 | 0 |
| 6 | 19:00:02 | 455,362,819 | [`2DX9jEZf…`](https://solscan.io/tx/2DX9jEZfv8eMUYU1hJ3HRT8Z23fvV6UAxEZr6GKntx5qnmAqbzuvF5rTpJS6AY2gWdkzo9ZdhyNoPbGzY7xh7vfi) | crank | ORE `checkpoint` of round 435,224 (`Sending 0.000891 SOL to automation`), then `dig` for round 435,225 with heartbeat 3 | `RigDug` (1,000,000 on 4 squares) | 11,042 | 7,000 |
| 7 | 19:00:05 | 455,362,834 | [`baNzqcBz…`](https://solscan.io/tx/baNzqcBzu49Rer1rnjcMugBHdPHp7F6wSLQTUUH22hyGSddQZaVLR57eUZNjhi2fCEzdYjZZcrUka4GTcGCbfeo) | crank | The second attempt for round 435,225, with the same heartbeat, 15 slots later. ORE's checkpoint logged `Round not valid`, and no deploy ran | `RigSkipped`, error 7 `StaleHeartbeat` | 11,042 | 0 |
| 8 | 19:01:03 | 455,363,093 | [`4VwGpdfS…`](https://solscan.io/tx/4VwGpdfSz9dkVNfkmoLyG3mc1Qb4QGwmY4wA1GUbKHfYB4aDER81KLehHanEFs9mzeuxPThj9DNaDkpXDTWXVkqx) | crank | `checkpoint` of round 435,225 (0.000891 SOL to the Automation), then `dig` for round 435,226 with heartbeat 4 | `RigDug` (1,000,000 on 4 squares) | 11,082 | 7,000 |
| 9 | 19:02:05 | 455,363,382 | [`4pgkrNUn…`](https://solscan.io/tx/4pgkrNUnHBtsncoFiPD5NDxi4M5bHA14UpFWU3PKm2xioLEFqbzSE1DX1LuqEXLFhDgqk7c8WGQu41owdLubxekR) | crank | `checkpoint` of round 435,226 (0.000891 SOL), then `dig` for round 435,227 with heartbeat 5 | `RigDug` (1,000,000 on 4 squares) | 11,035 | 7,000 |
| 10 | 19:03:16 | 455,363,702 | [`5CodA3FB…`](https://solscan.io/tx/5CodA3FBf4jppDaA7fN6VBRcr1Uu74FJRjLVpR9MazuT6Cn5XH7Z11eG3H9W9iyVSo1C6VSwSwZWxHUHt2B1WES1) | crank | `checkpoint` of round 435,227 (0.000891 SOL), then `dig` for round 435,228 with heartbeat 6: the fifth and last dig | `RigDug` (1,000,000 on 4 squares) | 11,028 | 7,000 |
| 11 | 19:05:51 | 455,364,409 | [`63FZYbEy…`](https://solscan.io/tx/63FZYbEyJyePgGMMHqAxTvwGD5vAvFUggnbkfhmummhPjAzADctceYYJE5UNHp4bFBshJTcGNPDFocE7xw92yqVb) | crank | The BREAK the phone signed when the founder picked it up and unlocked it: `break_shift` | `ShiftBroken` (shift 2, reason 8 `UNLOCKED`) | 10,100 | 0 |
| 12 | 19:06:38 | 455,364,630 | [`3VfbzUZH…`](https://solscan.io/tx/3VfbzUZHzAYj468iXo2bnsqRfAcrTMt76dHzWFT51ZnrvseFF1bYEzo6fAqurTG8tB9AM1cPRYsXtJK2R25LuTUm) | wallet | The clock-out: `end_shift` | `ShiftEnded` and `ShiftEndedV2` (shift 2: 6 dark rounds, 5 rounds dug, 5,050,000 lamports, reason 8, rounds 435,222 to 435,231) | 5,000 | |
| 13 | 19:08:17 | 455,365,092 | [`46NXJnX5…`](https://solscan.io/tx/46NXJnX54wUdTgBVBGwfid8NQeqRL3LkGCyFkhtqJuVVHwp6R2UbnNxxhTSv8FHgtuyJupEPRQi4Frqf4NAjDcFF) | wallet | "Take it back" in the app: one ORE `automate` that closes the Automation and sends its 5,027,040 lamports to the wallet | none (no `heads_down` instruction) | 5,000 | |

What the table does not say by itself:

- **The first shift never dug.** It was sealed by the second clock-in, 4 minutes 58 seconds after
  the first, with reason 4 (`LEASE_LAPSE`: no heartbeat lease was ever granted in the shift). The
  crank had accepted no heartbeat yet, because the phone never started the shift: the wallet
  sent the clock-in, the app did not hear so, and the home screen stayed cold until the founder
  approved a second one ("What the first night found", item 4).
- **Round 435,223 was missed.** The crank sent its dig at slot 455,362,283, 20 slots before the
  round's end, and it landed 25 slots later. The crank paid 10,038 lamports for a skip. A second
  attempt,
  [`3dofzfVP…`](https://solscan.io/tx/3dofzfVP223ktyecMDuUgMHNcM41D1cz7SBNSJtVMogvqrcFDfFyQNcgKGHYohY4H69s1jNzyzeZtVK6zndp2FfQ),
  was sent and never seen to land.
- **The replay protection was seen at work, not staged.** Transactions 5 and 7 are the crank's
  own second attempts. Each carries the same phone signature as the dig before it (the
  `Secp256r1SigVerify` data of 4 and 5, and of 6 and 7, are byte for byte the same; the blockhash
  differs). Each landed, and the program refused it because the rig's counter had moved on.
- **The fifth dig used up the shift.** After it the shift had spent its 5,050,000 lamports. In
  rounds 435,229 to 435,231 the crank sent nothing for the rig.
- **The app's words matched the chain.** After the clock-out the app showed "Confirmed on-chain.
  Shift sealed as ended early." Before "Take it back" it showed "Your ORE Automation holds
  0.00502704 SOL: 0.003564 SOL not yet placed, and the account's rent", and afterwards
  "Confirmed on-chain. 0.00502704 SOL back in your wallet from the ORE Automation." The chain
  moved 5,027,040 lamports out of the Automation: 1,463,040 of rent and 3,564,000 that the four
  checkpoints had sent back to it.

**Afterwards** (accounts read at slot 455,365,470; the Miner again at slot 455,391,819, at
20:45, and at slot 455,433,610, at 23:17, with the same fields each time):

- No rig is armed and no shift is open. The ORE Automation
  [`9BGensrT…`](https://solscan.io/account/9BGensrT33mtZUE3h91GbfCrr4rRM5qUiVvWC2SbRoRk) is
  closed.
- The Rig [`DUmg1GrZ…`](https://solscan.io/account/DUmg1GrZhaELtpjY1KpA5ov6ANn4msoUPn4mLV2TWZgL)
  is still open. It has not been closed.
- The ORE Miner
  [`A6Rjhk1S…`](https://solscan.io/account/A6Rjhk1SHe9vKvfpnQ6HHMWrEtRxd8i8hdYXnUYEomAm) holds
  4,480,400 lamports. Its fields: `checkpoint_id` 435,227, `round_id` 435,228, `rewards_sol` 0,
  `rewards_ore` 0, `lifetime_deployed` 5,000,000, `lifetime_rewards_sol` 3,564,000,
  `lifetime_rewards_ore` 0.
- No ORE came out of the four rounds that were settled that evening. Round 435,228 was not
  checkpointed then; the next evening's first dig settled it, and it sent back the same 89.1%
  ("The second evening", transaction 16).
- The Executor PDA holds 1,465,240 lamports: 3,000 more for each of the five digs.

## What it cost

**The wallet.** It held 53,949,489 lamports before the first clock-in and 42,756,169 after "Take
it back": 11,193,320 less.

| Where | Lamports | Does it come back? |
|---|---|---|
| ORE Miner [`A6Rjhk1S…`](https://solscan.io/account/A6Rjhk1SHe9vKvfpnQ6HHMWrEtRxd8i8hdYXnUYEomAm) | 4,480,400 | Not known: whether a Miner's lamports can ever be taken back from ORE was not established |
| Rig [`DUmg1GrZ…`](https://solscan.io/account/DUmg1GrZhaELtpjY1KpA5ov6ANn4msoUPn4mLV2TWZgL) | 2,600,960 | 1,788,160 when the rig is closed. 812,800 stay behind (below) |
| Two ShiftLogs, [`2wKGfqoT…`](https://solscan.io/account/2wKGfqoT2cesX9swmHv6ZgWRRP7eeV3Ntp8se6VoSQb3) and [`AeNsTaCL…`](https://solscan.io/account/AeNsTaCLqN3cRjbUwy5D7FHN6MeCA6DXWg8y2pEHE7XZ) | 2,600,960 (2 x 1,300,480) | Yes, from 30 days after each shift ended: the program's `close_shift_log` returns it to the wallet that paid. The app's "Take it back" sheet states this rent and offers the instruction from that day (added 11 October; it has not run on mainnet, where no log is 30 days old before 9 November). The crank does not send it |
| Fees of the four wallet transactions | 25,000 | No |
| Mining | 1,486,000 that evening | 5,050,000 went out, 3,564,000 came back. Round 435,228 sent 891,000 more into the wallet's Automation the next evening |
| **Total** | **11,193,320** | |

- *The mining line.* Each dig took 1,010,000 lamports from the Automation: 1,000,000 onto four
  squares, 250,000 each, and the 10,000 executor fee. Five digs are 5,050,000. Each of the four
  settled rounds sent 891,000 back, which is the 89.1% that [ORE.md](ORE.md) gives for squares
  that did not win, so each settled round cost the wallet 119,000 lamports. Round 435,228 is not
  settled. Its Round account,
  [`HQS3u8ir…`](https://solscan.io/account/HQS3u8irVfPifLjmc8wVqqo6QWyqShLwdJWAjhx2cU8e), expires
  at slot 455,651,751, which is 259,932 slots after the read at 20:45: about 15.8 hours at that
  evening's slot time, around 12:30 on 11 October. ORE forfeits what a round owes a Miner if
  nobody checkpoints it before then (ORE.md, F8). The crank's checkpoint sweep is written to do
  that from round 435,640 on (the first multiple of 20 that is at least 400 rounds after
  435,228), while the crank runs and the Rig account stays open. It did not come to that: the
  founder clocked in again a few hours later, and the first dig of that shift checkpointed round
  435,228 at 01:02 UTC on 11 October. ORE sent back 891,000 lamports ("The second evening").
- *The tombstone.* The app's screen for closing the rig said "Your rig can be closed: 0.00178816
  SOL of account rent comes back". That is the Rig's 2,600,960 lamports less 812,800. Since v1.3,
  `close_rig` does not delete a rig that has armed a shift or accepted a phone-signed message: it
  shrinks the account to a 32-byte tombstone that keeps the rig's `shift_id`, `hb_counter` and
  `last_dug_round`, and 812,800 lamports is that account's rent on mainnet. A later
  `register_rig` for the same wallet grows it back into a Rig that goes on from those counters,
  so a shift id is never used twice and an old phone-signed message never verifies again. No
  instruction closes a tombstone
  ([`close_rig.rs`](../programs/heads-down/program/src/instructions/close_rig.rs)).
- *The executor fee.* Of each dig's 10,000 lamports the program paid 7,000 to the crank and left
  3,000 in the Executor PDA, which nobody can withdraw from.

**The crank.** Its fee payer held 50,963,000 lamports before the shift and 50,902,527 after it.
At 21:36 it still held 50,902,527: the nine transactions of the table are all it has paid for.

| | Lamports |
|---|---|
| Fees of its nine transactions | 95,473 |
| Reimbursed by the program (five digs x 7,000) | 35,000 |
| **Net** | **−60,473** |

Each of the nine carries two signatures at 5,000 lamports, the transaction's and the phone's
secp256r1 one. The rest of its fee is its compute-unit limit times the priority price: 53
lamports for the first dig (52,428 units at 1,000 micro-lamports), 1,028 to 1,082 for the four
digs at 20,000. So a dig that carried one rig cost the crank 10,053 lamports at the first price
and 11,028 to 11,082 at the second: after the 7,000 back, a loss of 3,053 and of 4,028 to 4,082 a
dig. The missed round and the two refused second attempts cost 10,038, 10,053 and 11,042 with
nothing back, and the BREAK 10,100. One rig in a transaction does not pay for itself. The
figures for batches of rigs were measured on the fork, not on mainnet, and at a price of 1,000;
they are in [`crank/README.md`](../crank/README.md) ("Operating costs"). At the 20,000 the
service runs with now, only a full batch of five would about break even
([DEPLOY.md, section 3](DEPLOY.md#3-funding); computed, not measured).

**ORE.** Of each settled round's 1,000,000 lamports ORE kept 109,000.

## What the first night found

Slots took about 0.218 s that evening (4,431 slots in the 967 s between the first and the last
transaction above). Figures below are in slots. Where seconds are given, that is the basis.

**1. The dig window was too short.** The crank sends a round's digs in a window before the
round's end, to deploy late. The window was 20 slots, a figure sized on the local fork. Each
round's end slot below is its Round account's `expires_at` less 288,000 slots, read from mainnet
that evening; for round 435,223 it is the slot the crank's log named.

| ORE round | Ends at slot | Window | Dig landed in slot | Slots before the end | Result |
|---|---|---|---|---|---|
| 435,223 | 455,362,303 | 20 slots | 455,362,308 | 5 after it | missed (transaction 3) |
| 435,224 | 455,362,592 | 20 slots | 455,362,581 | 11 | dug |
| 435,225 | 455,362,881 | 80 slots | 455,362,819 | 62 | dug |
| 435,226 | 455,363,170 | 80 slots | 455,363,093 | 77 | dug |
| 435,227 | 455,363,459 | 80 slots | 455,363,382 | 77 | dug |
| 435,228 | 455,363,751 | 80 slots | 455,363,702 | 49 | dug |

Changed at once, at about 18:59, on the running service: `HD_CRANK_DIG_DEPLOY_MARGIN_SLOTS=80`,
and `HD_CRANK_DIG_CU_PRICE_MICRO_LAMPORTS=20000` (the crank's dynamic priority fee had settled
at its floor of 1,000). The dig of round 435,225 already carries the 20,000 price and landed 62
slots before its round's end, which a 20-slot window cannot do, so it was sent with the new
settings. With the 20-slot window the record is one miss in two rounds. With 80 slots it is four
rounds of four. The sample is tiny, and the window and the priority floor were changed in the
same restart, so which of the two made the difference is not known. 80 slots were about 17.5 s
that evening.

**2. The retry came too soon.** The crank signed a second attempt for a dig that was still
unconfirmed after 6 slots. On mainnet the first attempt had not landed by then. Both landed, and
the second was refused (transactions 5 and 7): 10,053 and 11,042 lamports for nothing. Changed at
about 19:00: `HD_CRANK_DIG_RETRY_AFTER_SLOTS=40`. No second attempt has landed since.

**3. The phone found the crank again by itself.** The crank restarted twice during the shift, for
the changes above. After each restart the phone reconnected without being touched, and its
heartbeats were accepted again within the next round.

All three are variables on the Railway service: they override what the crank's image was built
with. The crank's own defaults, and how it times a dig, are described in
[`crank/README.md`](../crank/README.md) ("How it works", "Deploy timing").

**4. The app did not always hear that its clock-in had landed.** On mainnet the wallet sends the
clock-in itself. The app armed the phone's shift only when the wallet's answer came back to the
window that had asked, and the signature then confirmed through the app's own RPC. On 10 October
the first clock-in landed (transaction 1) and the home screen did not change; the founder tapped
"Clock in" again and approved a second one (transaction 2), which sealed the first shift and
paid the rent of a second shift log, 1,300,480 lamports. The same happened on 11 October. Which
step lost the answer on the phone is not known: there is no log from the phone for either night.
The code allowed three ways (Android closing the app's clock-in window behind the wallet, the
wallet session ending after the send, the confirmation not being readable through the public
RPC), and in each of them nothing looked at the chain afterwards.

Fixed in the app on 11 October: what the app hands the wallet is written down before the wallet
is asked to sign, and the shift this phone prepared is armed once the Rig shows it open for this
phone's key. The app looks when it comes to the front, when its clock-in window is opened again
(before any wallet request), and after a wallet session that failed or did not confirm. A shift
id is never taken from the RPC: only the id the phone itself prepared is armed, and a record
whose transaction can no longer land (its blockhash expired, a later shift armed, the window
over) is forgotten. Rehearsed on the Android 14 emulator against the local fork: a clock-in
landed from another wallet for the phone's key while the app was in front (armed within
seconds), one that was already on-chain when the Quick Settings tile was tapped (armed, and the
wallet was not opened), and one that could never land (forgotten). Both emulator end-to-end
runs pass with it. On the phone the failure has not happened again ("The third evening"), so the
repair itself has still only run on the emulator.

## The second evening (11 October 2026)

The founder clocked in again a few hours later, with the same build and the same policy. Times
are UTC on 11 October. The numbering goes on from the first shift's table.

| # | Time | Slot | Transaction | Signed by | What it did |
|---|---|---|---|---|---|
| 14 | 00:51:27 | 455,459,349 | [`5ou3WreP…`](https://solscan.io/tx/5ou3WrePYwPt5qzzcHtMQYiwjLcvzQVMrWrW391RrPqDaKzFPH1VBPyruU7ygexYaU2o8U8tzVqyeodmNJkx7F5g) | wallet | Clock-in: ORE `automate`, `set_caps`, `arm_shift` (`ShiftArmed`) |
| 15 | 00:52:10 | 455,459,548 | [`3M473Ned…`](https://solscan.io/tx/3M473NedEXu1czdcb8f71p9Aaqmgrk3pvLaYgSpJ8eWYnkGysw3zsAYceXHDzHnAyfYRooBNUfFNTfuJMffhFkTe) | wallet | A second clock-in 43 seconds later, for the reason in item 4 above: `end_shift` for the shift just armed, then `set_caps` and `arm_shift` (shift 4) |
| 16 | 01:02:19 | 455,462,352 | [`3HNQyTrx…`](https://solscan.io/tx/3HNQyTrxKZTJrXwQy283QAfHfWDSgPCbNHfCUzFoXL9NgtCUkBF3kpYVZaprjZ6JMpwomf5Qvgb3r4esuRfnT5L) | crank | ORE `checkpoint` of round 435,228, the first evening's last dig (`Sending 0.000891 SOL to automation`), then `dig` for round 435,569 |
| 17 | 01:03:23 | 455,462,644 | [`4TLGYzT1…`](https://solscan.io/tx/4TLGYzT1WFq4ErKvZwNBWErREqhapZaNdjyxopVU6iBzUqh2EVofAbqPEurm4WYE29AiBTyAJutwXyTQEVmfzdxw) | crank | `checkpoint` of round 435,569 (0.000891 SOL), then `dig` for round 435,570 |
| 18 | 01:04:26 | 455,462,932 | [`5RF9F4R5…`](https://solscan.io/tx/5RF9F4R5NmkMdMZt8DwWtgxgm5DQuLjPBJXBPMMYYf4vBi7b6fomspSBkcJMYeQp7hq6jtmzww5ZnQkBf5GR9dbV) | crank | `checkpoint` of round 435,570 (0.000891 SOL), then `dig` for round 435,571 |
| 19 | 01:05:33 | 455,463,244 | [`4zMvxL9G…`](https://solscan.io/tx/4zMvxL9GDT3hazn3QqKKdfeYLxwtBYt9mShScu4zSFmqAkUUZsDg9nxRzyH7DahT79Y4Y1Bf71NHnYsrnmr1h8am) | crank | `checkpoint` of round 435,571 (0.000891 SOL), then `dig` for round 435,572 |
| 20 | 01:06:29 | 455,463,504 | [`zXq1efeP…`](https://solscan.io/tx/zXq1efePF71DmZZDoCUMJWTQacphwTESmsstWhyf5yDMYdpZUFPXs16o5XHxvMnRowsRG1fxUqQT6NoKKFXQ9KU) | crank | `checkpoint` of round 435,572 (0.00091575 SOL), then `dig` for round 435,573: the fifth dig, and the shift's budget |
| 21 | 01:12:13 | 455,465,093 | [`WrpqfU18…`](https://solscan.io/tx/WrpqfU18cEGEfRNxGjMvuPx3fMuCtZkLEURaHNSsQYJSkLcoV14dxXqSXqGJ7TiAGHfhKnQG92SpCwnZPznampW) | crank | A BREAK the phone signed: `ShiftBroken` (shift 4, reason 2 `screen_on`) |
| 22 | 01:35:09 | 455,471,408 | [`4oYkP3LH…`](https://solscan.io/tx/4oYkP3LHFxgnuUMwxvwxUjMKzQ4eh9Rf7Ga2529Q1gNErBz89ivXxaEuwWJHhyJbALPPcN9Mjiyb6MoKVe33GiUs) | crank | A second BREAK the phone signed, after it was laid down again: `ShiftBroken` (shift 4, reason 2) |
| 23 | 08:13:30 | 455,581,102 | [`22dBJyxT…`](https://solscan.io/tx/22dBJyxTfjzAHZv31t8Z7RATgqL323qAZN2nqt8kD7cyrpTKktSsWPU2A6FB8qQJHyHqTh9QcP1QqfzBagx2GNEa) | crank | The crank's checkpoint sweep, with no phone involved: ORE `checkpoint` of round 435,573 (0.000891 SOL to the Automation) |

What it adds to the first evening:

- **Five digs in five consecutive rounds.** With the 80-slot window and the 40-slot retry no
  round was missed and no second attempt landed. With the first evening that is nine rounds dug
  of nine since the window was widened. It is still one rig and two evenings.
- **The first shift's last round is settled, and nothing came of it.** Transaction 16 checkpointed
  round 435,228 and ORE sent back 0.000891 SOL, the 89.1% of squares that did not win. So no
  ORE came out of any of the first shift's five rounds, and its mining cost the wallet 5 x 119,000
  = 595,000 lamports, besides the executor fees. The 0.000891 SOL went into the Automation the
  second clock-in had set up again, where it is the wallet's to take back.
- **The checkpoint sweep ran on mainnet.** The second shift's last round was left without a
  checkpoint when the shift's budget was used up. The crank settled it seven hours later
  (transaction 23), at the first sweep after the round was 400 rounds old, before ORE would have
  forfeited it.
- **Round 435,572 sent back 0.00091575 SOL, not 0.000891.** It was not looked into that evening;
  see "The third evening" for what the Miner showed a day later.
- **The shift is still open.** At 16:25 UTC on 11 October the Rig was Cooling with shift 4 open
  and its plan window over. It has not been clocked out, and the Automation has not been emptied.
- **Not an overnight run.** The phone was in use that night; the shift lasted about twenty
  minutes. A whole night is still in the list below.

## The third evening (11 October 2026): the redesigned app on the phone

The redesigned build (the new screens, the slab, the clock-in fix of item 4) was installed over
the old one on the Redmi 14C, keeping the rig's key, and the founder ran three shifts with it.
Same policy as before. Times are UTC; the numbering goes on.

| # | Time | Slot | Transaction | Signed by | What it did |
|---|---|---|---|---|---|
| 24 | 17:46:53 | 455,738,644 | [`5MVVyjii…`](https://solscan.io/tx/5MVVyjiiCAdpcT7GohYhpHypHNVK1Vh6auYxMrerWQJi1bZuwMHRoQMTF1q5EWUKCzgQsaa6QYSfsprv1QdUXTju) | wallet | Clock-in from the redesigned build, after the wallet had refused its day-old token once (item 5 below): `end_shift` for shift 4, `set_caps`, `arm_shift` (shift 5) |
| 25 | 17:47:22 | 455,738,773 | [`2rNsYwEr…`](https://solscan.io/tx/2rNsYwErmm6LXHc9KTvmXHMHRQEMouJtrpXC4CcLZZ48fTWm4WeweK53jr1AXrr88bgrwGWHajuJkpRQMLSeCj48) | crank | A BREAK the phone signed 29 seconds later: `ShiftBroken` (shift 5, reason 7 `unplugged`). The charger had come out |
| 26 | 17:50:24 | 455,739,603 | [`33zJjHKh…`](https://solscan.io/tx/33zJjHKhrsHQR8QZHg9VssA4M18LxcW9NCc4w2YxVtCVGcgqM25tJfKSimkj9YDcV8RGXcNXoCj19UkxQZXZEDY9) | wallet | Clock-in: `end_shift` for shift 5, `set_caps`, `arm_shift` (shift 6) |
| 27 | 17:51:52 | 455,740,007 | [`4EtZGBZw…`](https://solscan.io/tx/4EtZGBZw3NyrABZ8tAt8kzGos6tnTEesnppbrNKssrWCkMtwJkQA2yUAUfGhe5HsBpAmkPkKEAnAFrY21rirrTWv) | crank | `dig` for round 436,528 |
| 28 | 17:52:53 | 455,740,284 | [`3RjxNU5H…`](https://solscan.io/tx/3RjxNU5H2dD1V4eQ2hUmqX5tyrrdVLFerU5rvsoNdwtVTFuoor5LPJ122Wc7jgsdFqGGM5pgDBEUPiFTNipZiXi2) | crank | `checkpoint` of 436,528 (0.000891 SOL), then `dig` for round 436,529 |
| 29 | 17:54:06 | 455,740,621 | [`2Y3Z22Mm…`](https://solscan.io/tx/2Y3Z22Mm1icqi3AFVx2wernm4vjSAJ5bUzF4v6hiMWyWtJQKxej7vCXas8VHu9nE3MTcGMhVuAMeswL5Zz53aCx1) | crank | `checkpoint` of 436,529 (0.000891 SOL), then `dig` for round 436,530: the crank's second attempt, sent 40 slots after the first |
| 30 | 17:54:06 | 455,740,622 | [`2ygAza8z…`](https://solscan.io/tx/2ygAza8zeAwdUdLe9WE4roYMEajS5C8Pj3Rhzoc9XV9SYmgYFsi5qAJu2XD7AQd6dqca3nwaCeKsD2eu6Mtz97zN) | crank | The first attempt for round 436,530, landing one slot after the second: no deploy ran (`RigSkipped`, error 7 `StaleHeartbeat`) |
| 31 | 17:55:01 | 455,740,877 | [`2Wxoynua…`](https://solscan.io/tx/2WxoynuaFjrDFsfGRvYViigE6hap3PSbuMwug3YppQify8Mi8TAMnyeAHEKZm369HxoRdJVnjnhB9KMPYbSvuyzu) | crank | `checkpoint` of 436,530 (0.000891 SOL), then `dig` for round 436,531 |
| 32 | 17:56:01 | 455,741,155 | [`4F2ykhuf…`](https://solscan.io/tx/4F2ykhufxJ3eNuDAKfyCtHj5BjVGndjQZGA6mrZz4P4r1a18xWxgrwZ3z5XrPxU9UcCBAKcWNRt3AErSEdkckTmX) | crank | `checkpoint` of 436,531 (0.000891 SOL), then `dig` for round 436,532: the fifth dig, and the shift's budget |
| 33 | 17:56:05 | 455,741,172 | [`5SVHu8GF…`](https://solscan.io/tx/5SVHu8GFqNafoK6RptMo6Zfs2DN4REtjpxp3YLnmhhN5WUXfc8HRaYscPg7GorteuXmgjXwU3k6aLR12ByXMFDAZ) | crank | A BREAK the phone signed: `ShiftBroken` (shift 6, reason 2 `screen_on`) |
| 34 | 17:57:13 | 455,741,485 | [`4cRAwhxt…`](https://solscan.io/tx/4cRAwhxtvLgi4e5os3sgXkkPQQNPJNKPo6DjhX73KM5sLoAZEtKMukjaErBFQpcPtJa5bw63bQk2UDHnSL1bzVDS) | wallet | Clock-in: `end_shift` for shift 6, `set_caps`, `arm_shift` (shift 7), and the Automation topped up to the shift's budget |
| 35 | 17:58:14 | 455,741,753 | [`3p4ZTnhz…`](https://solscan.io/tx/3p4ZTnhzGrLRrpFQKUgdd2yvFsLPUDUvGN8DTKGQzoJtYRSUqmZAtTutpUMnWsLAhx8s8rVTqvVhrBWgRnYbzL8e) | crank | `checkpoint` of 436,532 (0.00091575 SOL), then `dig` for round 436,534 |
| 36 | 17:59:22 | 455,742,059 | [`2sjn1wiV…`](https://solscan.io/tx/2sjn1wiVnLS9n7co92nNxFD3PriCvBfFFJyj68A9X87hGUfdLFs3F6ZbTQn8LAahoZW1zYdGpKTQY9SMmcmwi1m2) | crank | `checkpoint` of 436,534 (0.000891 SOL), then `dig` for round 436,535 |
| 37 | 18:00:26 | 455,742,340 | [`3PphFbmJ…`](https://solscan.io/tx/3PphFbmJyAfurvbL9fS7txwotV4yRAuGwpVmVfpNNkwnduHF5SaWbRJckPZfs6sZ9irDwVPJy8igEpVH7qtzxxhy) | crank | `checkpoint` of 436,535 (0.00091575 SOL), then `dig` for round 436,536 |
| 38 | 18:01:38 | 455,742,665 | [`55P8Ezk3…`](https://solscan.io/tx/55P8Ezk3cTVv8qh5zS4TaZDnA4UKSNrX69zmt5LgA7k5JH41RCfqkCn8cHQw7Y1AiKrPU7NWiDQ1PvHQpGQUdRy3) | crank | `checkpoint` of 436,536 (0.000891 SOL), then `dig` for round 436,537: the second attempt again |
| 39 | 18:01:39 | 455,742,671 | [`3mAz8uEu…`](https://solscan.io/tx/3mAz8uEusptEoc3tz3rPSGymfNKhjN3RnTwmoaMKDx1cKfdhrZVLgjoF5AoQ1b8vNPYeSj2Zd1JXRKSLMDSGnQhZ) | crank | The first attempt for round 436,537, landing six slots after the second: `RigSkipped`, error 7 `StaleHeartbeat` |
| 40 | 18:02:28 | 455,742,890 | [`483ABMie…`](https://solscan.io/tx/483ABMieQ5p49ExYdxc8Pxmtskpq3Vsq7N1iTi5NSeFCvCBsUDQ2X2XFG3zWCKm7FF1oEd8ttHzM1K4WG8mU24A2) | crank | `checkpoint` of 436,537 (0.000891 SOL), then `dig` for round 436,538: the fifth dig, and the shift's budget |
| 41 | 18:04:16 | 455,743,392 | [`4iyVhabE…`](https://solscan.io/tx/4iyVhabEtyoPQZhNUCi8sDffuSHxJobTtj7yYq6rrMhYHRaBkDbhDNbx8LeWSCi3isA4onZjGEjRTEn2mmkjr8Ds) | crank | A BREAK the phone signed when it was picked up: `ShiftBroken` (shift 7, reason 1 `pickup`) |
| 42 | 20:35:44 | 455,784,931 | [`4VrBjPok…`](https://solscan.io/tx/4VrBjPokVddo3FQf4dALc8ed59BYbceoyt1e7r1gH4Qq5BDKjKfGzpoapaHrNBu3eGMiFUfB8QZx6G98vQ8UfTmh) | wallet | The clock-out, from the final build's sheet: `end_shift` (`ShiftEnded` and `ShiftEndedV2` for shift 7). The wallet paid the log's rent, 1,300,480 lamports, and the fee |
| 43 | 20:37:41 | 455,785,468 | [`31hLL494…`](https://solscan.io/tx/31hLL494mPMcjH4cjjQ7KigH6P9eAqokGh7CWvUTnpxFmhRQ5bVb2tzCA8FaDjs4SGGY9eB4cFfxPuGCf628dzYG) | wallet | "Take it back": one ORE `automate` that closes the Automation and sends its 5,967,540 lamports to the wallet |

What it showed:

- **After each approval the app armed by itself.** Three clock-ins, each approved once in Jupiter,
  and each time the phone went on to work the shift. After the first, a screenshot taken four
  seconds after the transaction landed shows the home screen reading "Armed". The failure of
  item 4 did not happen. Its repair, arming a clock-in from the chain when the wallet's answer
  is lost, was therefore not exercised on the phone: it has run on the emulator only.
- **Ten digs in ten rounds.** Shift 6 dug rounds 436,528 to 436,532 and shift 7 dug 436,534 to
  436,538 (its clock-in landed during 436,533). No round the phone was down for was missed. With
  the first two evenings: 19 of 19 since the dig window was widened to 80 slots.
- **Twice the first attempt landed after the second** (transactions 30 and 39). The crank signs a
  second attempt when the first has not landed 40 slots after it was sent. Both times the first
  then landed too, one and six slots after the second, and the program refused it as it refuses
  any heartbeat it has already taken: 11,025 and 11,042 lamports of the crank's fees for nothing.
  Two in ten digs that evening, none in nine before. A second attempt that reused the first's
  signed transaction could not land twice; the crank signs a new one. Not changed.
- **ORE has been mined.** At 20:15 UTC the rig's ORE Miner
  [`A6Rjhk1S…`](https://solscan.io/account/A6Rjhk1SHe9vKvfpnQ6HHMWrEtRxd8i8hdYXnUYEomAm) read:
  `lifetime_deployed` 20,000,000 lamports (twenty digs of 0.001 SOL), `lifetime_rewards_sol`
  17,003,250 (the nineteen rounds settled: sixteen sent back 891,000 and three sent back
  915,750), `rewards_ore` 193,224,455 and `refined_ore` 66,899, `lifetime_rewards_ore`
  193,291,354. ORE has 11 decimals, so that is 0.00193 ORE. It was 0 after the first evening.
  Which rounds it came from was not traced; the three rounds that sent back more SOL (435,572,
  436,532 and 436,535) are the candidates. In SOL the nineteen settled rounds cost 1,996,750
  lamports of what was placed and 190,000 of executor fees: about 1.13 SOL per ORE on this small
  sample, with the wallet's ceiling at 1.2. The ORE is in the Miner, unclaimed.
- **The first shift lasted 29 seconds.** The charger came out and the phone signed the BREAK a
  Night Shift signs for that (reason 7). No dig, and the next clock-in paid for sealing it.
- **Three clock-ins, where one and a clock-out were meant.** After a pickup the home screen's
  only bar read "Clock in", and the link that seals a shift was a screen further down. The
  founder clocked in again each time, and then asked how to clock out. Each clock-in sealed the
  shift before it and paid a ShiftLog's rent, 1,300,480 lamports, which the program gives back
  from 30 days after the shift ended (since 11 October the app's "Take it back" sheet states that
  rent and offers to take it back from that day). Changed in the app:
  "Clock out" now stands directly under "Shift ended".
- **Clock-out and "Take it back" ran on the phone with the final build** (transactions 42 and
  43), which had been installed over the evening's earlier one. The sheet said the Automation
  held 0.00596754 SOL; the wallet held 28,545,479 lamports before and 34,508,019 after, which is
  that amount less the 5,000-lamport fee, to the lamport. The ORE was left in the Miner. The Rig
  is Idle with no shift open. Round 436,538 was not yet checkpointed at that point; with the
  Automation closed, what it returns goes to the Miner's own balance when the crank's sweep
  settles it.

**5. A wallet can refuse the token it handed out, and the library hides it.** Jupiter Mobile
refused, on 11 October, the sign-in token it had given the app on 10 October. clientlib-ktx
2.2.0 reports every error the wallet answers with, a refused token and a declined signing
request alike, as "Failed establishing local association with wallet": its catch around the
connection also wraps the whole session. So the app never learned that the token was the
problem, kept it, and every clock-in failed with that message. Fixed the same evening: the
wallet's own answer is read from the exception's cause; a refused token is dropped, and the app
says "Your wallet asks to connect again. Tap once more." A first version called the wallet a
second time by itself; on the phone that second session was cancelled at once ("Request was
cancelled"), because it started while the wallet's first window was still closing, so it was
taken out. Seen on the phone: with the token dropped, the next tap connected, signed and armed
(transaction 24). Anyone using the app on two consecutive days would have met this.

**6. The slab on the Redmi 14C.** Measured with the app's own lab screen, 60 Hz, battery saver
off, the phone warm (thermal status 2): in its worst case (hot rig, all five rows lit, seen from
underneath, turning for ten seconds) the shader drew 600 frames of 600, with 14.4 to 14.8 ms of
GPU time at the 95th percentile. That is over the 12 ms mark set for it beforehand, so by that
mark it fails; the drawn fallback took 18.8 ms in the same test, so the shader is what ships. At
rest it drew 0 frames in five seconds. On the home screen with a cold rig, dragged for ten
seconds, GPU time was 4 ms at the 95th percentile.

An independent review of the clock-in repair (three reviewers, each finding checked by a second
one) confirmed four further cases, all fixed and covered by tests the same evening: a second
tap in the seconds while the first clock-in lands could still build over it; the record of a
pending clock-in was forgotten before the shift had actually started, and a shift could be
started from a window that had left the foreground, which Android refuses; a clock-in the
wallet said it had not signed stayed on record; and a shift armed under the thumb could be
ended by the tap that was on its way to "Clock in".

## The night of 12 October 2026: three hours on the charger, then the power went

The first shift the phone worked through a night. The founder clocked in at 02:09 local time
(UTC+1) on the published build 0.1.0, laid the Redmi 14C face-down on its charger and went to
sleep. No alarm was set, so the window was the build's eight hours. Times are UTC; the numbering
goes on.

| # | Time | Slot | Transaction | Signed by | What it did |
|---|---|---|---|---|---|
| 44 | 01:09:17 | 455,860,112 | [`3Ngnxsv2…`](https://solscan.io/tx/3Ngnxsv2SzUZFY12jhusNEsKYVr5X49VbT9Bi7UUUKxypeiJLFVPYHnMoVM4yLa4NC4g8CrwwZLFWxhh9yS7JJgf) | wallet | Clock-in at 02:09 local time, with no alarm set: ORE `automate`, `set_caps`, `arm_shift` (`ShiftArmed`, shift 8). The plan window is the build's eight hours, to 09:09:11 |
| 45 | 01:10:50 | 455,860,545 | [`2sx1jTmR…`](https://solscan.io/tx/2sx1jTmRem72i8V5gDCA4VZnpsdpSgUwbekDaWwmVzcvfUJWfwNaehtKrjzD7BZH8kF7msZ2Qff4p2wpVqMqi2TQ) | crank | `dig` for round 436,943: 0.001 SOL on four squares |
| 46 | 01:10:56 | 455,860,572 | [`W9hstcLR…`](https://solscan.io/tx/W9hstcLRC5fnnk5z5NZY1vo5scm4gP5YJ81cWcZNuQHSS2dHL5xtJbme8hceund1D49x6KGPKiaTyQsKUjzST9Q) | crank | The crank's other attempt for round 436,943, landing 27 slots later: no deploy ran (`RigSkipped`, error 7 `StaleHeartbeat`) |
| 47 | 01:11:43 | 455,860,791 | [`6GbCLruR…`](https://solscan.io/tx/6GbCLruRt7RLZKBxNTJnqrN9zkA6r2q8WMrpsUMMfbmdjGURkh9v9gubBS2yE3NVyaPsLCbw4kdchSZp74nRpmA) | crank | ORE `checkpoint`, then `dig` for round 436,944 |
| 48 | 01:12:49 | 455,861,089 | [`2u6q8FMg…`](https://solscan.io/tx/2u6q8FMg7EZyvQp7nS9PvwJy7WhqktJZc81g3hY88mJ6UHmpsuYRhKtuA7p9k7WCgAaLs1QEWkTCmCBUaQNbsqqR) | crank | ORE `checkpoint`, then `dig` for round 436,945 |
| 49 | 01:13:57 | 455,861,399 | [`3zJpQ6DH…`](https://solscan.io/tx/3zJpQ6DHs2itwih38hiBMeukr2vH6DsGSTyWrZAX6M279fWSQ7sHprWCSPhugHBXok3SrQADbvj4omJHBgiATYWS) | crank | ORE `checkpoint`, then `dig` for round 436,946 |
| 50 | 01:14:57 | 455,861,678 | [`2kc643V7…`](https://solscan.io/tx/2kc643V7aWMAVTye4MGniGuDVk35ARH57vx2BGgzUQTmtv1wtyRNHpZ45uJd3SLkGJ2TmDsPeVGkAkiptUeuKNH3) | crank | ORE `checkpoint`, then `dig` for round 436,947: the fifth dig, and the shift's budget |
| 51 | 04:01:17 | 455,907,324 | [`4VtssJM3…`](https://solscan.io/tx/4VtssJM3TK26TZ5PkJQcjppYS4XrNqhpQjtFbwoxNrXhRz1YXabybSziTp6Hj95ZGENFgG1Hh3muaH7YVVN9HKFo) | crank | `record_heartbeats`, the first on mainnet: `HeartbeatsRecorded`, round 437,105, one dark round added. The crank sends it only in a round where the rig's cost gate is shut |
| 52 | 04:08:36 | 455,909,336 | [`3TBjJwzz…`](https://solscan.io/tx/3TBjJwzzuMYj9P6CwSD9sqaGzXqf9qchUrdJARDdQqFciq1PT8gTCaSMC6ymEZGkcfFntZ3eyF5mmGYugU5KADmW) | crank | A BREAK the phone signed: `ShiftBroken` (shift 8, reason 7 `unplugged`). The charger had lost power at 05:08 local time |

What it showed, read from the Rig account and these transactions on the morning of 12 October,
before the clock-out:

- **Three hours face-down, signing.** The Rig's heartbeat counter went from 52 to 223 between
  the clock-in and the BREAK: about 170 messages signed by the key in the phone's secure
  hardware, one for each ORE round of those three hours.
- **The chain holds six of those rounds.** Five digs in the first six minutes used the shift's
  budget of 0.005 SOL. After that the crank lands nothing for a rig whose cost gate is open
  (`check_record` in `crank/src/planner.rs` records heartbeats only for focus-only rigs and for
  rigs whose gate is shut). One round at 04:01 was such a round, and the crank recorded the
  phone's heartbeat by itself: `shift_dark_rounds` is 6, `shift_rounds_dug` 5. The phone's own
  count is in its journal and nowhere on-chain. A night's dark rounds should not depend on its
  budget: that is a change to the crank, not made yet.
- **A power cut ended it.** At 05:08 local time the charger lost power; the phone signed the BREAK
  a Night Shift signs for that, and the crank landed it. Inside the window that seals the shift
  as ended early: no streak day. Had a Focus Bond been on it, the bond would have been lost to a
  power cut. The rule that tells a pickup from an outage does not exist yet.
- **No alarm, an eight-hour window.** With an alarm set before the clock-in, the window would have
  ended two minutes before it. Build 0.1.0 also planned its own morning page for 07:00, inside
  that window; the shift was already over by then. Build 0.1.2 never opens that page before the
  window's end.

## Not run yet

On a phone:

- a whole night under HyperOS (the night of 12 October ran three hours, until a power cut);
- the haul reveal at alarm time on the device;
- a dig attempt while the cost gate is shut;
- Solflare, Phantom or Seed Vault (only Jupiter Mobile has signed);
- the Seeker tier on a device;
- Close rig, Unfreeze and Claim;
- Stack, the Focus Bond and Gift (the app has no screens for Stack and Gift);
- the buy leg;
- the default build with its 0.53 plan digging, and a release build: the shift ran on a debug
  build with the demo policy.

On mainnet at all:

- any SKR instruction;
- a crank transaction with more than one rig, a lease reuse, a lookup table, a
  an `end_shift` or a FREEZE sent by the crank (a `record_heartbeats` ran on 12 October);
- a second rig, or a rig that is not the founder's;
- a pause or any other governance transaction (the governance key still holds the 14,963,000
  lamports it was funded with), and an upgrade of the program.

Not done:

- a third-party audit;
- the move of the upgrade authority to a multisig with a time lock;
- monitoring and alerts on the services and the balances.

## Check it yourself

None of this needs a key. `-um` is the Solana CLI's public mainnet endpoint.

**The program, its authority and its last deploy.**

```bash
solana program show HDn4vgLWFLLdexKEwfZwRHjWtizNvdqFteLbMsE67F9p -um
```

On 10 October 2026 this printed:

```text
Program Id: HDn4vgLWFLLdexKEwfZwRHjWtizNvdqFteLbMsE67F9p
Owner: BPFLoaderUpgradeab1e11111111111111111111111
ProgramData Address: 3jjGZ8EE8DJcRag9eTFMMxStNo55PHLa5xVPktW52WPZ
Authority: 9DSVM862oJrstiPmmQmgqXb7AkuXrKJtYgd1fwbeqeeW
Last Deployed In Slot: 455359196
Data Length: 196608 (0x30000) bytes
Balance: 0.99964748 SOL
```

**The program's bytes.** Dump them and hash them. The first hash is the program hash (trailing
zero bytes stripped), the second is the sha256 of the build file; both are in the deploy receipt
as `onchain.program_hash` and `so.sha256`. This was run for this page and printed the two values
shown.

```bash
solana program dump HDn4vgLWFLLdexKEwfZwRHjWtizNvdqFteLbMsE67F9p /tmp/heads_down.onchain.so -um
python3 - /tmp/heads_down.onchain.so <<'PY'
import hashlib, sys
d = open(sys.argv[1], "rb").read()
print(len(d), hashlib.sha256(d.rstrip(b"\0")).hexdigest())  # 196608 0154706c62cb7bb6aacf463501e49b016b87809ed7193fcae08afc4e3f891b58
print(hashlib.sha256(d[:190048]).hexdigest())                # 07dd870a879faac20d4932f297da3b50719c9b900761409250c8841cc7b8527d
PY
```

`solana-verify get-program-hash HDn4vgLWFLLdexKEwfZwRHjWtizNvdqFteLbMsE67F9p -um` prints the
program hash as well. It was not run for this page.

**What this does not check: that the bytes come from the source.** A rebuild does not reproduce
the hash outside the directory the deployed file was built in. On 10 October 2026 the same
program sources (under `programs/heads-down` and `crates` only three Markdown files have changed
since commit `d67a1a40c2a0`, and no source file),
built with the same toolchain (`cargo-build-sbf 4.1.0`) in two other directories of the same
machine, gave two more files of 190,048 bytes with other hashes (`22098a39…2019` and
`f11be448…071c`). The first differs from the deployed file in 968 bytes at 106 places; the ones
looked at are call offsets. A third directory gave a fourth hash (`88a33dc5…c4aa`), and the same
one again when it was built there a second time: the build repeats inside a directory and not
across directories. Why the directory changes the output was not investigated. In the
founder's checkout the build gave `07dd870a…` on 4 October and again for the deploy. So the link
from the source to the deployed bytes rests on that one checkout today. This repository records
no build in a pinned container, which a third party could repeat.

**The Config.** One read of the public RPC, decoded with the offsets of
`programs/heads-down/INTERFACE.md`:

```bash
curl -s https://api.mainnet-beta.solana.com -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"getAccountInfo","params":["inzDn4ogmXbx9YDAKDHkfwJHy1jhsaWxGQvricDAEmW",{"encoding":"base64"}]}' \
  | python3 -c '
import base64, json, struct, sys
v = json.load(sys.stdin)["result"]["value"]
d = base64.b64decode(v["data"][0])
A = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
def b58(b):
    n, s = int.from_bytes(b, "big"), ""
    while n:
        n, r = divmod(n, 58)
        s = A[r] + s
    return "1" * (len(b) - len(b.lstrip(b"\0"))) + s
print("owner", v["owner"], "lamports", v["lamports"], "bytes", len(d))
print("governance", b58(d[8:40]))
print("registrar ", b58(d[40:72]))
print("crank_fee", struct.unpack_from("<Q", d, 72)[0], "executor_fee", struct.unpack_from("<Q", d, 80)[0],
      "bury_bps", struct.unpack_from("<H", d, 88)[0], "paused", d[90])
'
```

```text
owner HDn4vgLWFLLdexKEwfZwRHjWtizNvdqFteLbMsE67F9p lamports 1950720 bytes 256
governance 37u9LWbPrzQFkfL6oXGoSfq9souRggVtGvYszRXHezXN
registrar  9deCPaA6iML39zQw4mptBeHRkm7DE6oVWGijdA9c2zgo
crank_fee 7000 executor_fee 10000 bury_bps 0 paused 0
```

**One dig.** The first one, as the public RPC gives it, with its `RigDug` event decoded
(the layout is in `programs/heads-down/program/src/events.rs`):

```bash
curl -s https://api.mainnet-beta.solana.com -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"getTransaction","params":["25nBRCP6GExrKG4EDszGrKfWN9yYgJMopgnawTGi2Z9XNGj6UCtZgE6HFJN7rJXvu2C3VMjhh7bpXZnQg8DVzovY",{"encoding":"json","maxSupportedTransactionVersion":0}]}' \
  | python3 -c '
import base64, json, struct, sys
r = json.load(sys.stdin)["result"]
m = r["meta"]
print("slot", r["slot"], "blockTime", r["blockTime"], "fee", m["fee"], "err", m["err"])
print("fee payer", r["transaction"]["message"]["accountKeys"][0], m["postBalances"][0] - m["preBalances"][0])
for line in m["logMessages"]:
    if "deploying" in line:
        print(line)
    if line.startswith("Program data: "):
        e = base64.b64decode(line[14:])
        if e[0] == 1 and len(e) == 61:  # RigDug: rig 32, round_id u64, lamports u64, mask u32, ema_ev u64
            rnd, lamports, mask, ema_ev = struct.unpack_from("<QQIQ", e, 33)
            print("RigDug round", rnd, "lamports", lamports, "squares", bin(mask).count("1"), "ema_ev", ema_ev)
'
```

```text
slot 455362581 blockTime 1791658750 fee 10053 err None
fee payer 5Xec1ZUwXcB2ZGeWqqBHrxaHT4WQrGVUgH9xmqgC1kzk -3053
Program log: Round #435224: deploying 0.00025 SOL to 4 squares
RigDug round 435224 lamports 1000000 squares 4 ema_ev 691153003
```

The fee payer's balance fell by 3,053 lamports: the fee of 10,053 less the 7,000 the program paid
it back. Any other signature of the table works in the same command. For the two refused
attempts the event has tag 2 and 45 bytes (`RigSkipped`: rig, round, error code).

**The receipts.** [`20261010T184116Z-fresh-d67a1a40c2a0.json`](../deploy/receipts/mainnet/20261010T184116Z-fresh-d67a1a40c2a0.json)
and [`20261010T184715Z-init.json`](../deploy/receipts/mainnet/20261010T184715Z-init.json) hold the
deploy's and the initialization's signatures, slots, fees and hashes;
[`deploy/receipts/README.md`](../deploy/receipts/README.md) explains every field.

**The services.**

```bash
curl -s https://crank-production-21c2.up.railway.app/healthz        # "status":"ok", the current ORE round, the breaker
curl -s https://indexer-production-88dc.up.railway.app/v1/summary   # the rig count, with the transaction it rests on
```
