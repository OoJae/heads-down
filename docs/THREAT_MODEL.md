# Heads Down threat model

> **Status.** This document states the security requirements and the bound on each key. At this commit the on-chain code consists of the specification ([SPEC.md](SPEC.md)) and one spike (`spikes/ore-executor`). Each check below names the negative test that must prove it. A check counts as done only when that test exists and passes in CI. This is not an audit result.
>
> ORE facts are cited as `file:line` at ORE commit `b92c5043`, which verify.osec.io reports as the deployed program ([ORE.md](ORE.md), section 1).

---

## Summary: the worst case for each key

| Key or party | Worst case if it is compromised or malicious | What bounds it |
|---|---|---|
| **User wallet** (Seed Vault or MWA wallet) | Everything the wallet controls. This key is the root of authority, and Heads Down cannot bound it. | The wallet's own security; on-device transaction building and simulation |
| **Rig P-256 key** (Android Keystore) | The armed weekly budget is deployed into ORE, but only in rounds where ORE's production-cost EMA is at or below the ceiling the wallet signed. Most of each deployed lamport comes back to the user, and none goes to the attacker. It can also forfeit this rig's own SKR bonds, or cheat at Stack. | Wallet-signed caps and expiry; the on-chain cost gate; ORE's per-square cap; Freeze (device key) and Revoke (wallet) |
| **Crank or relayer** (anyone) | Liveness only: nothing mines, nothing is lost. A relayer that withholds heartbeats can make a Stack seat record gaps. | Competing permissionless cranks; the Nostr mirror; phones posting their own heartbeats; grace gaps |
| **Registrar** (Ed25519 key) | Software keys get attestation level 1 or higher, so a cheater can win remote "honor-plus" Stack tables, up to the bond cap per seat. It has no custody and no mining authority. | Bond caps; in-person tables are the primary mode; voucher expiry; published transcripts; timelocked rotation |
| **Upgrade authority** (beta) | After a **public 72 h delay**: take the SKR and SOL held in Heads Down vaults, and force deploys of armed Automations, which ORE limits to `25 x automation.amount` plus one fee per round. It cannot withdraw from Automations or claim anyone's ORE. | 72 h timelock with an in-app banner; one-approval Revoke; small vault caps; then revoked (immutable v1) |
| **Team servers** | Liveness, privacy exposure, and phishing-shaped notifications. None of them holds authority over funds. | No signing from push; every transaction is built on-device from chain state and simulated |
| **ORE upstream** | ORE owns every Automation and Miner, so a malicious or broken ORE can move user funds regardless of Heads Down. | ORE's own governance. Heads Down inherits this trust in full and adds layout and version pins against *accidental* breakage |

---

## 1. Scope and assumptions

**In scope:**
- the `heads_down` program;
- the crank;
- heartbeat intake, the registrar, the Kora relayer, push and presence, the indexer and dashboard, and the Jupiter quote proxy;
- the Android app.

**Out of scope, but named where it matters:**
- bugs in the Solana runtime or the precompiles;
- ORE's own correctness (see K7);
- wallet apps;
- a compromised Android OS below the app (root), except where stated.

**Assumptions:**
- **A1.** SHA-256, ECDSA P-256 and Ed25519 are secure.
- **A2.** On a device with a locked bootloader and verified boot, Keystore keys cannot be exported. The Redmi 14C is expected to be TEE-backed only; spike 1(b) confirms `KeyInfo.getSecurityLevel`.
- **A3.** ORE behaves as its source at `b92c5043` says.
- **A4.** Solana consensus and the runtime behave as specified. In particular, a transaction whose secp256r1 precompile instruction fails is rejected as a whole.

---

## 2. Assets

| Asset | Where it lives | Controlled by | Notes |
|---|---|---|---|
| Mining SOL | the user's ORE `Automation` (owned by ORE) | the user's wallet (authority); the Executor PDA may only deploy | No Heads Down vault exists for mining funds |
| Mined ORE and returned SOL | the user's ORE `Miner` (owned by ORE) | only the user's wallet can claim (`claim_ore.rs:24-27`) | |
| Stack bonds (SKR) | per-table vault ATA, authority = table PDA | `heads_down` | **Custodied by Heads Down** and capped per seat |
| Focus Bonds (SKR) | per-bond vault ATA | `heads_down` | Custodied and capped |
| Gift escrows (SOL) | `GiftEscrow` PDA | `heads_down` | Custodied; refunds after 30 days |
| Bury lots (SKR) and ORE proceeds | `BuryVault` | `heads_down` | ORE leaves only through ORE `bury` |
| Executor float (SOL) | Executor PDA (System-owned, no data) | `heads_down` via `invoke_signed` | Pays `CHECKPOINT_FEE` top-ups and crank reimbursements; has no withdraw path |
| Rig P-256 private key | Android Keystore on the phone | the device | Cannot be exported; can be *used* by code running in the app |
| Rig state integrity | `Rig`, `SeekerSeat`, `ShiftLog` | `heads_down` | caps, counters, one verified rig per SGT |
| Registrar key, upgrade multisig, Kora and crank fee payers | servers / Squads | the team | |
| User data | phone, services, chain | see [PRIVACY.md](PRIVACY.md) | Sleep and idle timing is sensitive |
| Metric integrity | chain plus indexer | anyone can recompute | used for ORE milestones |

---

## 3. Actors

- **Users.** An honest user, and a malicious user who wants to cheat at Stack, get crank work reimbursed without paying the fee, grief crank batches, or drain the Executor float.
- **Attackers on or near the device.** Another app on the same phone (intents, deep links, text selection, share targets); someone with physical access to a locked or unlocked phone; code running inside the Heads Down app process (a malicious update or supply-chain compromise).
- **Infrastructure attackers.** A malicious cranker or relayer; a network adversary who can drop, delay or intercept traffic; a compromised team server, registrar or upgrade multisig.
- **Upstream parties.** ORE (the program and its upgrade authority `J5K5tWj3nKfxuSkAJ25WTMf4u5EsxJRfUoRKKxgrfFGV`); Solana Mobile as SGT issuer (per `docs/research/seeker-sms-stack.md` it holds mint, freeze, close and permanent-delegate authority on SGT mints).
- **Other ORE miners.** They can crowd squares or push the production-cost EMA up, but only by spending their own SOL.

---

## 4. Trust boundaries

```
+----------------------- PHONE (the user's; untrusted by the chain) ----------------------+
|                                                                                         |
|  +-----------------------------+     TB1: MWA intent +      +------------------------+  |
|  | Heads Down app (Kotlin)     |     encrypted session      | Wallet app             |  |
|  |  sensors -> classifier      |<-------------------------->| (Seed Vault, Solflare, |  |
|  |  shift service (FGS)        |                            |  Phantom, ...)         |  |
|  |  [K2] Keystore P-256 key    |     TB7: other apps ------>| [K1] Ed25519 key       |  |
|  +------+--------------+-------+     (intents, links,       +-----------+------------+  |
|         |              |             PROCESS_TEXT, share)               |               |
+---------|--------------|------------------------------------------------|---------------+
          | TB6 Nearby   | TB3: P-256-signed heartbeats, BREAK,           | user-signed
          v              v      FREEZE, plans (can be dropped,          | transactions
   other phones   +---------------------------------------------+       |
   at a Stack     | OFF-CHAIN: intake (SIWS), Nostr mirror,     |       |
   table          | Kora relayer [K4b], push/presence,          |       |
                  | registrar [K4], indexer, quote proxy [K6]   |       |
                  +-----------------+---------------------------+       |
                                    | TB4: registrar voucher = data,    |
                                    |      checked on-chain (Ed25519)   |
                  +-----------------v---------------------------+       |
                  | CRANKS (permissionless, anyone) [K3]        |       |
                  | tx = [secp256r1 verify] + [heads_down::dig] |       |
                  +-----------------+---------------------------+       |
                                    | TB2: every account, byte and      |
                                    |      instruction order is hostile |
==================v=================v===================================v==================
|  SOLANA RUNTIME (trusted): secp256r1 + ed25519 precompiles, instructions sysvar,        |
|  System, SPL Token, Token-2022 (SGT reads)                                              |
|                                                                                         |
|  +------------------- heads_down program [K5 upgrade authority] -------------------+    |
|  | Config  Rig  SeekerSeat  ShiftLog  Room/Seat  Stack vaults  FocusBond vaults   |    |
|  | GiftEscrow  BuryVault  Executor PDA (data-less, System-owned float)            |    |
|  +----------------------------------+---------------------------------------------+    |
|                                     | TB5: invoke_signed(ORE deploy);                   |
|                                     |      the Executor PDA is a signer inside ORE      |
|  +----------------------------------v---------------- ORE [K7 upgrade authority] -+    |
|  | Automation (user SOL, ORE-owned)  Miner  Board  Round  Treasury  Config        |    |
|  +--------------------------------------------------------------------------------+    |
===========================================================================================
```

| Boundary | What crosses it | What is trusted | What is not |
|---|---|---|---|
| TB1 app to wallet | unsigned transactions, SIWS | the wallet displays and signs; the user decides | the app's description of a transaction: the wallet and the simulation show the truth |
| TB2 world to program | every account and every byte | runtime-verified signatures, account owners, PDAs the program re-derives, sysvars read by syscall or checked address | anything else: account order, instruction data, extra instructions |
| TB3 phone to services | signed heartbeats | nothing: services can drop or delay messages but cannot forge them | delivery, timeliness |
| TB4 registrar to chain | an Ed25519 voucher over (sgt_mint or rig, P-256 key, level, expiry) | the registrar key, **only** for the attestation level | anything about funds |
| TB5 Heads Down to ORE | a CPI carrying the Executor PDA signature | ORE for custody and deploy semantics (A3) | ORE leaving the Executor float alone (checked after the CPI) |
| TB6 phone to phone | Nearby payloads | nothing | everything |
| TB7 other apps to the app | intents, URIs, selected text | nothing | everything |

---

## 5. Keys and their worst cases

### K1: the user's wallet key (Seed Vault, Solflare, Phantom and others)

- **Powers.**
  - ORE `automate`: deposit, executor, fee, per-square cap, reload, and Revoke (`automate.rs:88-134`).
  - `claim_ore` and `claim_sol`.
  - Heads Down `register_rig`, `verify_seeker`, `rebind_seeker`, `rotate_key`, `refuel` caps, unfreeze.
  - Stack joins, Focus Bonds, gifts, and the clock-out buy leg.
- **Worst case.** Everything the wallet controls: its tokens, the Automation (Revoke closes it to the signer), unrefined ORE, the rig's bonds and pending gift refunds.
- **Why Heads Down cannot bound it.** This key is the root of authority, and it is the *only* key accepted for actions that add value or raise risk: raising caps, unfreezing, bonding, gifting.
- **Mitigations.**
  - Every transaction is built on-device from on-chain state and simulated before the wallet opens, and the balance changes are shown.
  - `signMessage` is used only for SIWS, with single-use 10-minute server nonces.
  - Success is shown only after confirmation with `err == null`.

### K2: the rig's P-256 key (Keystore; TEE on the Redmi 14C, StrongBox where present)

- **Powers.** Sign heartbeats (DOWN for an ORE round), tighten-only plans, BREAK and FREEZE. Arm a shift with no wallet approval, within the caps and expiry the wallet signed.
- **Cannot.** Raise any cap or expiry, unfreeze, change the executor, withdraw, claim, or move bonds, except by forfeiting its own rig's bonds with a signed BREAK.
- **Worst case.** On a sound device the key cannot be extracted, but code running in the app can use it.
  1. **Mining.** The armed budget (at most the weekly cap, before expiry) is deployed into ORE rounds, only in rounds where `Board.production_cost_ema` is at or below the ceiling the wallet signed. Each deployed lamport returns 89.1% (losing square) or 99% (winning square) to the user's Automation or wallet (`checkpoint.rs:94-97`, `149-153`), so the expected loss is about 10.5% of the SOL deployed plus one fixed crank fee per round dug. That loss goes to ORE's fees; no path sends SOL to the attacker. The gate is a board-wide trailing average, not the rig's own realized price, which is random ([ECONOMICS.md](ECONOMICS.md)).
  2. **Stack and Focus Bonds.** A forged BREAK forfeits this rig's own bonds (at most the cap per table). Heartbeats sent while the phone is actually in use let the cheater win against other seats (see section 9).
  3. **Presence.** It can make the rig appear "dark" when it is not.
- **Mitigations.**
  - **Freeze** needs only the device key and stops digs at once; unfreezing needs the wallet.
  - **Revoke** is one wallet approval.
  - Weekly, per-shift and per-round caps, plus expiry.
  - ORE's own per-square cap `automation.amount` (`deploy.rs:203`) and fixed fee, which the app sets to the plan's needs.
  - Attestation binds the key to the genuine, signed app (`attestationApplicationId`) on a device with verified boot.
- **Residual.** The key cannot require user authentication, because it must sign at night while the phone is locked. So a malicious app update can use it silently until the morning reveal shows the digs.

### K3: crank and relayer (anyone; the team runs one)

- **Powers.** Choose which heartbeats to submit and when within the round; batch and pay fees; checkpoint; relay Stack heartbeats.
- **Cannot.**
  - Forge a heartbeat.
  - Choose the amount or the squares: the program computes both.
  - Dig without a fresh heartbeat for the current ORE round.
  - Touch bonds.
  - Be reimbursed without an ORE deploy for that rig in that CPI. Reimbursement is paid only when ORE credited that rig's fee in the same CPI (`deploy.rs:338-347`), so the pool stays neutral.
- **Worst case.**
  - Withhold every dig: nothing mines and nothing is lost.
  - Deploy early instead of late, so more SOL piles onto the chosen squares after the dig and the realized cost rises. This stays inside the gate and caps.
  - Crowd the rig's squares with its own SOL in the same transaction before the dig. That costs the crank about 10.5% of what it deploys and gains it nothing, because ORE has no parimutuel payout.
  - Withhold a Stack seat's heartbeats so the seat records gaps.
- **Mitigations.**
  - Competing cranks, funded by the fixed reimbursement.
  - Heartbeats mirrored to a public Nostr relay.
  - Phones post their own heartbeats whenever they are online.
  - Grace gaps; at in-person tables every phone relays for every seat.
- **Kora fee payer (K4b).**
  - A compromise loses only its fee float.
  - Kora's policy allowlists `heads_down`, the precompiles and ComputeBudget, and allows at most one sponsored transaction per rig per round.
  - Rent is sponsored only for SGT-gated accounts.

### K4: the registrar's Ed25519 key

- **Why it exists.** Google's Key Attestation root is P-384 inside X.509 chains, which the P-256 precompile cannot verify on-chain. So an off-chain registrar checks the chain and issues an Ed25519 voucher, which the program verifies through Ed25519 precompile introspection with the same offset rules as section 6.
- **The registrar checks:**
  - the root is Google's;
  - the security level is TEE or StrongBox;
  - `verifiedBootState == VERIFIED` and `deviceLocked`;
  - `attestationApplicationId` matches the package and its release-certificate digest;
  - the challenge is bound to the registration nonce.
- **Worst case.**
  - Software keys are vouched as hardware keys. A cheater then scripts heartbeats and wins remote "honor-plus" tables, taking up to `bond_cap x (seats - 1)` per table joined.
  - Leaderboard tiers are inflated.
  - There is no custody and no effect on mining: guest rigs gate only their own money, whatever their attestation.
- **Mitigations.**
  - Bond caps; in-person tables are the primary mode.
  - Vouchers carry an expiry, so rigs must re-attest.
  - Every attestation transcript is published, so anyone can re-verify it against Google's root.
  - Rotating or revoking the registrar key goes through the Config timelock.
- **Residual.** Leaked keyboxes on rooted devices (the "Tricky Store" class; `docs/research/redteam.md`) can produce chains that pass. Revocation lists catch some of them, not all.

### K5: the program upgrade authority

- **Beta.** A Squads multisig behind a 72 h timelock. After the audit it is revoked and v1 is immutable.
- **Worst case (multisig compromised, timelock intact).** After a public 72 h wait, a malicious program could:
  - take everything Heads Down PDAs hold: Stack and Focus SKR vaults, gift escrows, Bury lots, and the Executor float;
  - as the executor, deploy armed users' Automations at any time and on any squares. ORE limits this to `25 x automation.amount` per round and charges the user's own fixed fee once per round (`deploy.rs:201-207`, `338-342`). The value is destroyed into ORE fees, not stolen;
  - forge Rig and Seat state.
- **It cannot** withdraw from Automations, claim ORE, or change users' fees or per-square caps.
- **Mitigations.**
  - The app watches the Heads Down ProgramData and any pending Squads upgrade and shows a banner: "Heads Down upgrade pending. Revoke if you do not trust it."
  - Revoke is one approval.
  - Custodied balances stay small because of caps.
  - Verified builds (`solana-verify`) let anyone diff a pending buffer against source.
  - Revocation removes K5 entirely.
- **Residual.** During beta, holders of bonds and gifts must act within 72 h.

### K6: team servers

These are heartbeat intake, SIWS and auth, push and presence (FCM), the indexer and dashboard, the Jupiter quote proxy, and the Helius proxy. None of them holds a key that controls funds or program state.

| Server | Worst case | Mitigation |
|---|---|---|
| Heartbeat intake | Drops or censors heartbeats, so rigs go cold | The phone can post directly through its own RPC path or through Nostr; mining fails safe |
| SIWS and auth | Impersonates users to intake and presence: reads coarse presence, sends spam | Heartbeats are P-256-signed and cannot be forged; nonces are single-use |
| Push (FCM sender) | Spoofed notifications, such as a fake "claim your gift" lure | Push payloads are hints only; screens re-read the chain; no notification or deep link can reach a signature without the in-app review and the wallet's own approval |
| Quote proxy | Returns a malicious route for the buy leg | The app fetches swap instructions from Jupiter over TLS, checks every instruction's program ID against an allowlist, sets the minimum output on-device from the displayed quote with a slippage cap, and simulates |
| Indexer and dashboard | Publishes false metrics | Every metric can be recomputed from chain data, including ORE's own `DeployEvent`s ([ORE.md](ORE.md), section 9) |
| Helius proxy | Quota abuse | The key is never in the APK |

### K7: ORE upstream (the program and its upgrade authority)

- **Custody.** ORE owns the Automation and Miner accounts that hold users' SOL and ORE. A malicious or buggy ORE can move them no matter what Heads Down does. This is the same trust every ORE miner places in ORE, and Heads Down does not claim to reduce it.
- **Accidental breakage.** A layout or semantic change could make Heads Down misread, for example the EMA offset, and bypass its gate. The defences are the Level 1 layout pins (always on), the Level 2 version pin (beta) and the Level 3 crank pause with fork CI ([ORE.md](ORE.md), section 8).
- **Executor float.** During `deploy` the Executor PDA is a signer, and signer privileges extend to ORE's nested calls. ORE legitimately takes `CHECKPOINT_FEE` (`deploy.rs:327-330`). `dig` asserts `executor_after + CHECKPOINT_FEE >= executor_before`, which bounds any other debit to zero.
- **Strategy removal.** If ORE removed the Discretionary strategy or PDA executors, rigs would go cold and users would Revoke.

### Other parties

- **The Executor PDA** has no private key. Its exposure is limited to the float and bounded as in K7.
- **Solana Mobile (SGT issuer)** can move, freeze or burn an SGT, which is a Token-2022 permanent delegate plus freeze and close authority. Seeker tier, seats and gift claims follow the SGT, so `heads_down` re-verifies SGT ownership at every Seeker-tier value action (Stack join, gift claim) instead of trusting a stale `SeekerSeat`.
- **Jupiter** is a third-party program used only in user-signed sibling instructions. `heads_down` never CPIs it.
- **Other ORE miners** can raise the EMA (which closes gates) or crowd squares, at their own expense. Neither action opens a gate cheaply. The EMA falls only when less SOL is vaulted per ORE minted, which depends on everyone's deployments and on which square wins, and one miner cannot force either. The SOL on a square only grows during a round, so the least-crowded estimate made at dig time is a lower bound on what the rig will face.

---

## 6. The core gate: heartbeat introspection

A dig is valid only if, in the same transaction, a `Secp256r1SigVerify` instruction verified the rig's key over the expected message. The rules below are what the `p256-introspect` crate and `dig` must enforce:

1. **Sysvar.** The instructions sysvar account must be `Sysvar1nstructions1111111111111111111111111`, or the attacker supplies a fake one.
2. **Program.** The instruction at `hb_ix_index` must belong to `Secp256r1SigVerify1111111111111111111111111`.
3. **Same-instruction offsets (the Wormhole class).** In the chosen signature entry, the signature, public key and message instruction indices must all point to *that same instruction* (SIMD-0075 uses `u16::MAX` for "this instruction"). Otherwise the precompile verifies bytes in one place while the program reads different bytes elsewhere.
4. **Bounds.** Every offset plus its size must fall within the instruction data. A bad offset returns an error, never a panic.
5. **Key and message.** The 33-byte compressed public key equals `rig.p256`. The message equals the expected bytes exactly, length included: a domain tag per message type (heartbeat, plan, BREAK, FREEZE, so no type can be replayed as another), the program ID, the rig address, `Board.round_id` (owner-checked), the counter, the state, `shift_id` and `lease_end`. The byte layout is defined in `crates/p256-introspect`.
6. **Freshness.**
   - A new message needs `counter > rig.counter`.
   - A lease is valid only while `signed_round <= Board.round_id <= lease_end`, with `lease_end - signed_round <= Config.max_lease` (3).
   - A rig can be dug at most once per round (`rig.last_dig_round < Board.round_id`).
   - Stack seats require a lease of 1.
7. **Low-S.** The precompile rejects high-S signatures, so the client normalizes DER signatures to low-S raw `r || s`. A test must show that high-S fails.
8. **Key uniqueness (recommended).** A P-256 key may back only one rig. A marker PDA keyed by the hash of the public key enforces this.

---

## 7. On-chain checks mapped to the 16-class audit taxonomy

The Radiants brief names 13 of these classes (marked `*`). Rows 3, 15 and 16 complete the 16 with the classes we consider most relevant here; check them against the audit tool's own list at office hours. "Test" names the negative test that must exist before the row counts as done.

| # | Class | Where it bites in Heads Down | Required check | Test (must fail as shown) |
|---|---|---|---|---|
| 1 | Missing signer check `*` | Value-adding actions | `register_rig`, `verify_seeker`, `rebind_seeker`, `rotate_key`, `refuel`, unfreeze, `join_stack`, `lock_focus_bond`, `create_gift` and `claim_gift` require the holder's wallet as a signer. P-256 messages stand in for a signature only in tighten-only actions (heartbeat, plan, BREAK, FREEZE). | Each instruction without the authority signing fails with `MissingRequiredSignature` |
| 2 | Missing owner check `*` | Every account read | Heads Down accounts owned by the program; ORE accounts by ORE; SGT accounts and mints by Token-2022; SKR and ORE token accounts by SPL Token; the Executor PDA by System; ProgramData by BPFLoaderUpgradeable | A same-layout account owned by another program is rejected |
| 3 | Account data matching (relationships) | Linked accounts | `automation == PDA("automation", rig.authority)`; `automation.executor == Executor PDA`; `automation.fee == Config.crank_fee`; seat belongs to table; vault ATA mint is SKR and its owner is the table PDA; gift `recipient_sgt_mint` matches the claimant's verified SGT; `authority` is never read from instruction data ([ORE.md](ORE.md), section 5: passing the Executor PDA as authority would make ORE spend the float) | Another user's Automation; a seat from a different table; `authority = Executor PDA` |
| 4 | Type cosplay `*` | Deserializing accounts | A distinct 8-byte discriminator per Heads Down account type, checked before any read. For ORE accounts: discriminator byte plus exact length (Board 105 and 40 bytes, Round 109 and 952, Automation 100 and 160, Miner 103 and 752, Treasury 104 and 48, Config 101 and 232) | A `ShiftLog` passed as a `Rig`; an ORE `Miner` passed as an `Automation` |
| 5 | Arbitrary CPI `*` | ORE deploy, token transfers, bury | CPI targets are constants (ORE, System, SPL Token). No program ID is taken from an account without an equality check. Token-2022 is read-only. The ORE stake program reaches `bury` only through ORE, which validates it (`bury.rs:32`) | A fake ORE program ID is rejected |
| 6 | Duplicate mutable accounts `*` | Batched `dig`, Stack settle, transfers | Rigs in a batch are strictly unique (sorted addresses or a seen-set); seats are unique; source differs from destination. Pinocchio's borrow tracking is a second net, not the check | The same rig twice in one batch; the same vault as source and destination |
| 7 | Non-canonical bumps `*` | Every PDA | Derive with `find_program_address` at init, store the canonical bump, and later re-derive only with the stored bump. **The spike takes the Executor bump from instruction data** (`spikes/ore-executor/program/src/lib.rs:66`, `75-80`). That is acceptable for a spike and must not ship: production reads the bump from `Config` | An alternate-bump PDA is rejected |
| 8 | PDA sharing `*` | Vault signers | A separate seed prefix per purpose (`config`, `executor`, `rig`, `seat`, `shift`, `room`, `stack`, `stackseat`, `bond`, `gift`, `bury`). Vault authority is per table and per bond, never one global signer. The Executor PDA signs only ORE `deploy` and its own reimbursement transfers | Table A's authority cannot move table B's bond |
| 9 | Reinitialization `*` | Every init | Init-only paths: the target must be uninitialized (empty data, or discriminator 0 and not program-owned), and there is no init-if-needed. Pre-funding griefing: init must tolerate lamports already at the address (top up to rent, allocate, assign) rather than rely on `CreateAccount` | `register_rig` twice fails; pre-funding a `GiftEscrow` address does not block its creation |
| 10 | Closing accounts `*` | `ShiftLog` after 30 days, gifts, seats, bonds | Move every lamport to the stored rent payer, zero all data including the discriminator, and hand the account back to System. The close recipient is fixed by state, never chosen by the caller | Close then reuse in the same transaction fails; closing to an arbitrary recipient fails |
| 11 | Unchecked arithmetic `*` | Caps, spend, pro-rata payouts, auction price | `checked_*` everywhere; u128 intermediates; round down on payouts and send dust to Bury so that `sum(payouts) + bury == sum(bonds)`; `overflow-checks = true` in the release profile as a backstop; Kani proofs for caps and conservation | Maximum-value caps; pro-rata with remainders; `u64::MAX` counters |
| 12 | Stale data after CPI `*` | After ORE `deploy` and token CPIs | Re-read every ORE account after the CPI. ORE may close the Automation during the CPI (`deploy.rs:273-276`, `350-352`), so check owner and length first. Treat `balance` unchanged as a no-op (`deploy.rs:79-84`). Re-read token balances (`bury` uses `min(balance, amount)`, `bury.rs:35`) | An auto-close during the CPI; a Motherlode-condition no-op; an under-funded bury |
| 13 | Signer passthrough `*` | The Executor PDA signature | The Executor signature goes only to ORE `deploy`. Afterwards, `executor_after + CHECKPOINT_FEE >= executor_before`. No user signature is forwarded except into SPL Token transfers the user asked for. Vault PDAs sign only transfers out of their own vault to computed recipients. The crank's signature never enters a CPI | A mock ORE (LiteSVM) that tries to take the float is reverted by the delta check |
| 14 | Token-2022 extension pitfalls `*` | SGT verification; SKR and ORE mints | **SGT:** account owned by Token-2022; account-type byte; mint equals the claimed mint; `amount == 1`; not Frozen (a policy choice); mint owned by Token-2022; mint authority `GT2zuHVaZQYZSyQMgJPLzvkmyztfyXg2NJunqFp4p3A4`; `TokenGroupMember.group == GT22s89nU4iWFkNXj1Bw6uYhJJWDRPpShHt4Bk8f99Te`; bounded TLV parsing that rejects malformed data; ownership treated as point-in-time because the issuer holds permanent-delegate authority. **SKR and ORE:** classic SPL Token only, mint pinned; Token-2022 look-alikes (whose transfer fees or hooks would break conservation) are rejected | The `sgt-verify` spoof suite: wrong group, wrong authority, zero balance, fake mint, moved SGT, malformed TLV; an SKR mint under Token-2022 |
| 15 | Sysvar and introspection spoofing | Heartbeats, registrar vouchers | Instructions sysvar address checked; Clock read by syscall; precompile program IDs checked; offsets confined to the same instruction; index bounds checked (section 6) | A fake sysvar; offsets into another instruction; the wrong program at `hb_ix_index` |
| 16 | Resource exhaustion and griefing (compute, rent, batches) | `dig` batches, relayer, account creation | Batch size capped by `Config`; bounded loops (25 squares, N rigs, bounded TLV); pre-flight **skip** instead of abort for any rig that would trip an ORE abort ([ORE.md](ORE.md), section 5); the user who benefits pays for account creation; crank reimbursement only from the rig's own fee in the same CPI; relayer rate limits | Maximum-batch compute units; one griefing rig in a batch; a zero-fee Automation |

**Invariants and the checks that enforce them** (from SPEC.md; each is fuzzed with Trident, and proved with Kani where tractable):

| Invariant | Enforced by |
|---|---|
| No value leaves an Automation except through ORE's deploy or return | Heads Down has no instruction that moves Automation lamports; only ORE can (TB5) |
| A dig needs a fresh, bound P-256 signature | Section 6, rules 1 to 6 |
| Spend stays within the wallet-signed caps | Post-CPI `spent` delta checked against the round, shift and week caps (row 12) |
| One verified rig per SGT mint | `SeekerSeat [seeker, sgt_mint]` is init-only (row 9) and re-pointed only by `rebind_seeker` |
| Stack conservation | Row 11 rounding rule; Kani proof |
| Gifts reach only the intended recipient or return to the sender | Row 3 relationship checks; refund goes only to the stored sender |
| No admin withdraw path | No instruction exists; the audit greps for lamport and token moves out of every PDA |
| Discriminators, canonical bumps, checked math, no reinitialization | Rows 4, 7, 9, 11 |

---

## 8. Android client attack surface

The posture below is the manifest and code policy the Android workstream must implement. The Radiants audit and Ethelsec look for exactly these.

| Surface | Exported? | Risk | Policy |
|---|---|---|---|
| Launcher activity | yes (launcher) | none beyond launch | Accepts no data and ignores extras |
| MWA trampoline (translucent) | **no** | intent redirection; a forged sign flow | Started only by explicit intents from our own tile or activities; trusts no extras; creates the `ActivityResultSender` in `onCreate` |
| Quick Settings `TileService` | yes, as Android requires | a spoofed bind | Protected by `android.permission.BIND_QUICK_SETTINGS_TILE`. `onClick` uses `startActivityAndCollapse(PendingIntent)` (required on Android 14). From the lock screen, `unlockAndRun` runs before any signing flow. Long-press Revoke goes through the same path |
| Room and gift deep links (App Links, `autoVerify`) | yes | parameter injection, phishing | Host allowlist; exact path patterns; IDs must be fixed-length base58; the link only opens a review screen that re-reads the chain; it **never signs automatically** |
| `ACTION_PROCESS_TEXT` "Gift a rig" and the `ACTION_SEND` share target | yes (they must be) | untrusted text | Length at most 64; strict `.skr` name regex; resolved on-device; the resolved owner wallet and SGT are shown; the wallet must approve; the selected text is never returned or modified |
| Shift foreground service (`specialUse`) | **no** | being started or stopped by other apps | Explicit start from user actions only; `FOREGROUND_SERVICE_SPECIAL_USE` with a declared subtype |
| Exact alarm and full-screen reveal | **no** | spoofed alarms | Explicit, `FLAG_IMMUTABLE` PendingIntents; the reveal can show over the lock screen, but clock-out signing first calls `requestDismissKeyguard`; `canUseFullScreenIntent()` is checked, with a heads-up fallback |
| FCM service | **no** (`exported=false`) | forged or malicious pushes | Data messages only; schema-validated; hints that trigger a chain re-read; never start a signing flow |
| Glance widget receiver | yes (system `APPWIDGET_UPDATE`) | spoofed updates | Shows only non-sensitive state; every action is an explicit, immutable PendingIntent to a non-exported component |
| Boot receiver | none | | A rebooted phone stays cold until the user re-arms (fail-safe) |
| Content providers | only a `FileProvider` (not exported, `grantUriPermissions`) for share images | file exposure | No other providers |
| Nearby Connections (Stack tables) | n/a | malicious peers, oversized payloads | The connection is accepted only after both users compare Nearby's authentication digits. `BYTES` payloads only (no FILE or STREAM), at most 512 B, versioned TLV, unknown types rejected, rate-limited. Relayed heartbeats are verified locally before submission and on-chain in any case. Table data is re-read from chain; nothing in a payload is executed or followed as a URL |
| Logging | n/a | leaked tokens and sensor data | No `android.util.Log` in release: a lint rule fails the build, and R8 `-assumenosideeffects` strips `Log.*`. No Timber tree in release. Auth tokens, SIWS messages, transaction bytes, heartbeats and sensor windows are never logged. No third-party crash or analytics SDK by default |
| Token storage | n/a | theft of MWA and SIWS tokens | AES-256-GCM, with the key in Android Keystore (not exportable), stored in DataStore. The MWA auth token is used only in the foreground; the SIWS session token must be usable by the night service, so it has no user-auth requirement |
| Rig key | n/a | misuse | Keystore EC P-256, `PURPOSE_SIGN`, SHA-256, StrongBox when available (else TEE), with attestation requested. It cannot require the device to be unlocked or the user to authenticate, because it signs at night (see K2) |
| Backups | n/a | token exfiltration | `allowBackup=false`, with data-extraction rules excluding everything |
| Network | n/a | man-in-the-middle | TLS only; `cleartextTrafficPermitted=false`; our API domain pinned to its CA; no RPC key in the APK |
| Transaction UX | n/a | "success shown after an on-chain failure" | Success only after `getSignatureStatuses` reports confirmed with `err == null`; blockheight expiry and timeouts handled; the transaction version comes from MWA `get_capabilities` |
| Signing screens | n/a | tapjacking | `filterTouchesWhenObscured` on our review screens; the signature itself happens in the wallet app |
| PendingIntents | n/a | mutable-intent hijack | `FLAG_IMMUTABLE` and explicit components everywhere |
| OEM settings links (HyperOS Autostart, battery) | outbound | ActivityNotFound crashes, spoofed targets | `resolveActivity` before launching; package allowlist; errors caught |

**Permissions:**
- `FOREGROUND_SERVICE_SPECIAL_USE`, `POST_NOTIFICATIONS`, the exact-alarm permission and `USE_FULL_SCREEN_INTENT`.
- Optional, requested at runtime: notification-policy access (Do Not Disturb) and the Nearby permissions (`BLUETOOTH_SCAN` with `neverForLocation`, `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT`, `NEARBY_WIFI_DEVICES`).
- No UsageStats and no location on Android 13 and later. Android 12 (API 31-32) may still need location for Nearby over Wi-Fi; that path is TBD in the spike, with a BLE-only fallback.

**Redmi 14C and HyperOS specifics.**
- No gyroscope and a *virtual* proximity sensor, so the pickup classifier works from the accelerometer first. Screen-on and unlock stay hard breaks.
- HyperOS kills background work aggressively. If the service is killed, no heartbeats are sent and no digs happen (fail-safe). The keep-alive onboarding and a morning health check tell the user when this happened.

---

## 9. What Stack can and cannot prove

**What the chain can verify.** A P-256 signature by the key registered to a seat's rig, over a message claiming DOWN for ORE round `r`, which landed before settlement. Remote tables also need the registrar's attestation level 1 or higher.

**What it cannot verify:**

- **That the phone was really face-down and unused.** The DOWN state is the app's own sensor reading. The chain trusts it because the key is (as far as the registrar can tell) held in hardware by the genuine app on a locked, verified-boot device. A rooted device with a leaked keybox can defeat that (K4). This is why **remote** tables are labelled **"honor-plus"**, with capped bonds.
- **That the person was not using another phone.** Stack measures "this phone stayed down", nothing more. We never claim "proof of focus".
- **Delivery.** Airplane mode before a pickup, a force-stop or a dead battery all stop the heartbeat stream, and gaps beyond grace count as breaks (**fail-closed**). That also means an honest player on a flaky network can lose a bond. This is disclosed before joining, and grace is set per table.
- **Relay fairness at a table.** A relaying seat could withhold other seats' heartbeats. Every phone therefore posts its own heartbeats when online, every phone relays for everyone, and settlement counts a heartbeat that landed by any path. The residual risk is a seat with no connectivity of its own whose neighbours all collude.
- **Leases.** Solo shifts may sign leases of up to 3 rounds. A pickup while offline therefore lets digs continue until `lease_end`, about 4 minutes at about 78 s per round. Stack requires a lease of 1.
- **Clock skew** does not matter: messages bind to ORE's `round_id`, not wall-clock time.

---

## 10. Residual risks, ranked

1. **ORE custody trust.** ORE's upgrade authority can move Automation and Miner funds (K7). This is inherited, not reduced.
2. **Code running in the app process** can use the P-256 key: armed budgets get spent at gated prices, and Stack can be cheated (K2).
3. **Registrar and keybox spoofing** on remote tables, up to the bond caps (K4).
4. **What the product can measure.** Only this phone's screen-off, face-down time.
5. **Liveness.** HyperOS kills and network drops mean missed rounds. That is safe for mining, but costly for Stack players, because Stack is fail-closed.
6. **Lease window.** Up to 3 rounds of digs after an offline pickup on solo shifts.
7. **The gate metric** is a trailing, board-wide EMA, while a rig's realized price per ORE is lumpy ([ECONOMICS.md](ECONOMICS.md)).
8. **Beta upgrade authority.** 72 h to react for anyone with custodied bonds or gifts (K5).
9. **Legal.** Stack forfeits that pay finishers may count as wagering in some jurisdictions. Bury-only tables (all forfeits to Bury) are offered, bonds are capped, and the app is 18+ ([SKR.md](SKR.md)).
10. **Public timing metadata.** Rig activity reveals idle and sleep windows ([PRIVACY.md](PRIVACY.md)).
11. **Wallet activity footprint.** `authority` must be writable in `deploy` and `checkpoint` (`deploy.rs:23`, `checkpoint.rs:16`), so about one transaction per dug round references the user's wallet as a writable non-signer. It is disclosed, and a secondary wallet account can be the rig authority.
12. **Upstream semantic changes** that keep the layout the same, such as a fee change. Covered by the Level 2 and Level 3 breakers only.
13. **Spike code.** The client-supplied bump (row 7) must not reach mainnet.

---

## 11. Reporting a vulnerability

Please do not open a public issue. Use GitHub's private "Report a vulnerability" advisory on this repository. For ORE itself, follow ORE's `SECURITY.md` (a private advisory at regolith-labs/ore).
