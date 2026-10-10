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
- Sealed on Railway by the founder: `HD_REGISTRAR_KEYPAIR_JSON` and `HD_SESSION_SECRET` on the
  registrar, `HELIUS_API_KEY` on the crank, `RPC_URL` on the indexer. `HD_CRANK_KEYPAIR_JSON` was
  set from the key file through the Railway CLI's standard input, and its sealing was asked for.
  This page does not record that it was done.
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
  crank had accepted no heartbeat yet. Why the first shift ended before a heartbeat reached the
  crank was not investigated.
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
- No ORE came out of the four rounds that are settled. Round 435,228 is not checkpointed, so what
  it sends back is not known.
- The Executor PDA holds 1,465,240 lamports: 3,000 more for each of the five digs.

## What it cost

**The wallet.** It held 53,949,489 lamports before the first clock-in and 42,756,169 after "Take
it back": 11,193,320 less.

| Where | Lamports | Does it come back? |
|---|---|---|
| ORE Miner [`A6Rjhk1S…`](https://solscan.io/account/A6Rjhk1SHe9vKvfpnQ6HHMWrEtRxd8i8hdYXnUYEomAm) | 4,480,400 | Not known: whether a Miner's lamports can ever be taken back from ORE was not established |
| Rig [`DUmg1GrZ…`](https://solscan.io/account/DUmg1GrZhaELtpjY1KpA5ov6ANn4msoUPn4mLV2TWZgL) | 2,600,960 | 1,788,160 when the rig is closed. 812,800 stay behind (below) |
| Two ShiftLogs, [`2wKGfqoT…`](https://solscan.io/account/2wKGfqoT2cesX9swmHv6ZgWRRP7eeV3Ntp8se6VoSQb3) and [`AeNsTaCL…`](https://solscan.io/account/AeNsTaCLqN3cRjbUwy5D7FHN6MeCA6DXWg8y2pEHE7XZ) | 2,600,960 (2 x 1,300,480) | Yes, from 30 days after each shift ended: the program's `close_shift_log` returns it to the wallet that paid. Neither the app nor the crank sends that instruction yet |
| Fees of the four wallet transactions | 25,000 | No |
| Mining | 1,486,000 so far | 5,050,000 went out, 3,564,000 came back |
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
  435,228), while the crank runs and the Rig account stays open. Whether it has happened, and
  what the round sends back, is not known.
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

## Not run yet

On a phone:

- a whole night under HyperOS;
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
  `record_heartbeats`, an `end_shift` or a FREEZE sent by the crank;
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
looked at are call offsets. Why the directory changes the output was not investigated. In the
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
