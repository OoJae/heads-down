# SKR in Heads Down

SKR has three jobs in Heads Down, all of them in the `heads_down` program:

- **commitment collateral:** Stack (a contest at a table) and Focus Bonds (solo);
- **gifting currency:** Gift a Rig;
- **sink:** the Bury auction, which sells every SKR forfeit for ORE that ORE's own `bury` instruction burns.

A fourth, **SKR fuel** (paying for a refuel in SKR), is a client-side swap, not a program feature (section 4).

None of it is staking. Heads Down emits no SKR, pays nothing for holding it, and none of it flows to the team.

> **Status (2026-10-10).** Built and tested: the program side of Stack, Focus Bond, Gift a Rig and the Bury auction, as INTERFACE.md v1.2 §11 ([`programs/heads-down/INTERFACE.md`](../programs/heads-down/INTERFACE.md)), with golden vectors executed on a fork of live mainnet ORE, including ORE's real `bury` path. The crank can land Stack check-ins and settlement (that loop is switched off on the running service), and the indexer and the dashboard report every SKR event. The program has been deployed on mainnet since 10 October 2026 ([MAINNET.md](MAINNET.md)), and **no SKR instruction has been sent there**: `init_bury_vault` has not run, so the BuryVault account does not exist on mainnet, and every SKR counter of the indexer reads 0. In the app only the Focus Bond chooser is built, and it has not run on a phone; the Stack and Gift screens are not built. Checks and their tests are in [THREAT_MODEL.md](THREAT_MODEL.md), section 7.

**SKR facts used here** (`docs/research/skr-and-ore.md`):
- Mint `SKRbvo6Gf7GondiT3BbTfuRDPqLWei4j2Qy2NPGZhW3`, a classic SPL Token (not Token-2022) with 6 decimals and no freeze authority. The program refuses any SKR account that is not owned by the classic SPL Token program.
- Price on 2026-09-29: $0.01821 (Jupiter Price v3), so 200 SKR is about $3.64 and 500 SKR about $9.10.
- Solana Mobile's SKR staking program is `SKRskrmtL83pcL4YqLWt6iPefDqwXQWHSw9S9vz94BZ`. Heads Down never calls it, reads it, or references it on-chain.

---

## Summary of flows

| Flow | Instructions | Who signs | Where the asset sits | How it settles | Where it ends up |
|---|---|---|---|---|---|
| **Stack** | `open_stack`, `join_stack`, `stack_checkin`, `settle_stack`, `claim_stack` | the host to open; each seat's wallet to join | per-table SKR vault (an ATA owned by the table PDA) | permissionless, from heartbeats checked in every round | finishers: bond + 80% of forfeits pro rata; 20% (plus rounding dust) to the Bury lot; everything to Bury if nobody finishes or at a bury-only table; every bond back if nobody settles before the timeout |
| **Focus Bond** | `lock_focus_bond`, `release_focus_bond`, `forfeit_focus_bond` | the rig's wallet to lock | per-bond SKR vault (an ATA owned by the bond PDA) | permissionless, from the bonded shift's ShiftLog | back to the owner if the shift sealed `completed`, else to the Bury lot |
| **Gift a Rig** | `create_gift`, `claim_gift`, `refund_gift` | the sender to create; the recipient to claim | lamports (SOL) in a `GiftEscrow` PDA | the recipient wallet, or the current holder of the recipient SGT (verified in the program) | the recipient's wallet (and, in the same transaction, their ORE Automation); or back to the sender from day 30 |
| **Bury auction** | `init_bury_vault`, `bury_auction_buy` | the buyer | the `BuryVault`'s SKR ATA (the lot) | a descending-price (Dutch) sale for ORE; no oracle | the buyer gets the SKR; the ORE goes through ORE's `bury` (90% burned, 10% to ORE's stake program) |
| **SKR fuel** | none (client-side Jupiter swap) | the user | never held by Heads Down | an atomic swap in the user's own transaction | the user's own ORE Automation, as SOL |

---

## 1. Stack: a self-control contest with SKR bonds

**What it is.** A table of players each bond the same amount of SKR on keeping their phones face-down for a window of ORE rounds. Whoever holds out takes back their bond plus a share of the forfeits.

**Modes** (the `flags` byte of `open_stack`):

| Mode | Who may join | Bond cap | Seat key |
|---|---|---|---|
| **In-person** (primary) | any rig; above 500 SKR only a Seeker whose SGT is re-verified at join | 2,000 SKR | the rig (one seat per rig) |
| **In-person, attested-only** | as above, and the rig's key must carry an unexpired registrar attestation (level 1 or 2) | 2,000 SKR | the rig |
| **Remote "honor-plus"** | Seekers only: rig tier 1, SGT re-verified at join, attested key | 1,000 SKR | the SGT mint (one seat per Seeker device) |
| **Bury-only** (combines with either) | as the mode it combines with | as above | as above |

Phones find each other over Nearby Connections at an in-person table (an app feature). The chain does not see co-presence: "in-person" is enforced only through bond caps and the guest rules, and the remote label says plainly that a rooted device with a leaked keybox could fake heartbeats (THREAT_MODEL, section 9).

**Accounts.**
- `StackTable ["stack", host, table_id]`: bond, window `[start_round, end_round]`, grace gaps, flags, seat limit (2 to 8), totals, status (open, settled, refunding), and a refund deadline. Its SKR vault is the table PDA's ATA.
- `StackSeat ["stackseat", table, key]`: rig, wallet, bond, the shift it is bound to, rounds counted, last round counted, broken flag, outcome and payout.

**Lifecycle.**
1. `open_stack`: the host creates the table; the client creates its vault ATA in the same transaction.
2. `join_stack`: the joiner's wallet signs before `start_round`. The program checks eligibility (above), that the SKR account is classic SPL Token for the SKR mint and owned by the joiner, and moves the bond with an SPL Token transfer into the table vault.
3. **During the window**, every round: `stack_checkin` (permissionless; the crank sends it with each round's heartbeats). A seat counts round `r` only if a P-256 heartbeat for `r` from its rig's Keystore key, verified by the secp256r1 precompile, landed during round `r`, either verified inside the check-in or already applied by `dig` / `record_heartbeats` in the same round. Seats need one-round leases.
4. `settle_stack`: permissionless once `Board.round_id > end_round`, with every seat passed once.
5. `claim_stack`: pull-based and permissionless (it pays only the seat's own wallet); it closes the seat, so each seat claims once.
6. **Timeout refund:** if nobody settles by `refund_after_ts` (a pessimistic 120 s per round after opening, plus 3 days), claims return each seat's own bond instead.

**What proves a round, exactly.** The program reads only existing Rig fields (no Rig layout changed):
- `shift_id`: the seat binds to the rig's shift at its first counted round; later rounds must be the same shift.
- `shift_open` and `state`: an open shift, Armed or Down.
- `break_reason`: 0 since the shift was armed. Every BREAK (including a pickup that resumed) and every freeze set it, and only arming a new shift clears it, so a clean check-in proves the shift had no break up to that round. A seat never binds to a shift that already broke (the player ends it and arms a fresh one); once bound, a break is final.
- `plan_lease_rounds == 1` and `lease_from_round == lease_to_round == Board.round_id`: a heartbeat for this round was applied during this round.

**Finish rule.** A seat finishes iff it is not broken, it counted `end_round` itself (a lease covering `end_round`), and its gaps (window rounds not counted) are at most the table's grace.

**Payout arithmetic.**
- Let `B` be the total of all bonds, `W` the bonds of finishers and `F = B - W` the forfeits.
- If `W > 0`, each finisher receives `bond_i + floor(0.8 * F * bond_i / W)`, computed in u128. At a bury-only table the share is 0.
- Bury receives `F` minus the sum of those floored shares, which is the 20% plus any rounding dust.
- If `W == 0`, Bury receives all of `B`.
- Invariant: `sum(payouts) + bury == B`, checked by an exhaustive unit test over every finisher subset of 1 to 8 seats, and end to end on the fork. No seat can claim twice.

**Why this is fair only up to a point.** The chain verifies signatures, not behaviour (THREAT_MODEL, section 9). Stack measures "this phone stayed face-down and its app said so in time", which is why it is labelled a self-control contest and never claims to measure focus. It is also fail-closed: a round nobody checks in for counts as a gap even if the phone was down, so an honest player on a bad network can lose a bond. Grace gaps absorb a few; this is disclosed before joining.

**Legal risk, and the bury-only option.** Forfeits that pay finishers could be treated as wagering in some jurisdictions (SPEC open question). Mitigations:
- **Bury-only tables:** every forfeit goes to the Bury auction, so nobody gains from anyone else's flinch. **Proposal:** make bury-only the app's default for remote tables.
- Low bond caps (500 SKR for guests, about $9).
- The app is 18+.
- No house cut, ever.

---

## 2. Focus Bond: solo commitment

**What it is.** You lock SKR on your rig's open shift. If the shift seals `completed`, it comes back. Otherwise it goes to the Bury auction, never to the team and never to another player.

**Account.** `FocusBond ["bond", rig, shift_id]`, with its own vault ATA. It records the shift's start round and start time, so only that shift's ShiftLog can resolve it.

**Instructions.**
- `lock_focus_bond`: the wallet signs; at most 5,000 SKR; only on an open shift with no break yet (Armed or Down), whose ShiftLog does not exist yet.
- `release_focus_bond`: permissionless once the shift's ShiftLog says `completed`; the SKR goes to the owner only, and the bond and vault close (rents to the owner).
- `forfeit_focus_bond`: permissionless once the ShiftLog says anything else, or when the shift can never be sealed (the rig was closed while it was open); the SKR becomes a Bury lot, and the rents still go to the owner.

Forfeiting is permissionless so that nobody (the owner included) can hold a broken bond hostage. Under the v1.1 shift rules, a soft break (pickup, screen on, unplugged) that resumed with a fresh heartbeat still seals `completed`, so the bond survives it; a hard break, a freeze, a lease lapse or ending early inside the window does not.

---

## 3. Gift a Rig: a gift that arrives as a live ORE rig

**Sender flow.** One wallet approval covers:
- optionally, a Jupiter SKR→SOL swap (a sibling instruction signed by the sender, with a minimum output from the displayed quote);
- `create_gift(nonce, recipient_kind, recipient, lamports)`, which moves SOL from the sender into `GiftEscrow ["gift", sender, nonce]` with a 30-day expiry (at most 10 SOL).

The recipient is either a **wallet** or an **SGT mint**. The app resolves a `.skr` name to its owner wallet (AllDomains), then to the SGT that wallet holds (a Token-2022 lookup), and shows both before signing. An SGT gift binds to the **mint**, not to the name, so a later name transfer cannot redirect it.

**`claim_gift`.**
- Before expiry only. A wallet gift is claimed by that wallet; an SGT gift by whoever holds that SGT **at claim time**, verified in the program with the `sgt-verify` checks (Token-2022 owner, mint, owner field, amount of 1, group and authority anchors).
- The lamports go to the claimer's wallet, so the same transaction can continue with the claimer's own ORE `automate` (executor = the Heads Down Executor PDA) and `register_rig`: the gift arrives as a rig that is already funded. This composition is tested on the fork.

**`refund_gift`.** From day 30, anyone may trigger it, but the lamports go only to the stored sender.

**Honest notes.**
- The escrow holds **SOL**, not SKR. The sender bears the swap price and slippage at send time, and the program never calls Jupiter.
- Solana Mobile, as SGT issuer, holds permanent-delegate authority, so it could move an SGT before a claim. The gift then follows the SGT's new holder, by design.

---

## 4. SKR fuel: pay for a refuel in SKR

One transaction contains `[Jupiter swap SKR to SOL]` and `[ORE automate deposit]`. It is atomic: if the swap delivers less than its minimum output, the whole transaction fails. Heads Down never CPIs Jupiter and has no fuel instruction; this is a client feature.

This is a convenience, not the novelty. refinORE already ships SKR-funded ORE mining (`docs/research/skr-and-ore.md`), and we do not claim otherwise.

---

## 5. Bury auction: forfeits become burned ORE, with no oracle

**Accounts.** `BuryVault ["bury"]` (one per program; anyone can create it, nobody administers it) owns two ATAs: an SKR ATA holding the lot, and an ORE ATA that is the sender ORE `bury` requires.

**One pooled lot.** Every Stack forfeit (at settle) and Focus Bond forfeit is added to the lot and **restarts** the auction.

**Price** (ORE per SKR, computed from the slot in the Clock sysvar; no price feed is read):
- it falls linearly from a start price to a floor over 216,000 slots (about 24 hours), then stays at the floor;
- the start price is 4 times the last clearing price, or 0.001 ORE per SKR before the first sale (several times the 2026-09 market), capped at 0.1 ORE per SKR;
- the floor is 0.0000001 ORE per SKR, so lots always sell eventually;
- the cost of a purchase is rounded up in the lot's favour (at least one ORE atom).

These constants are compile-time constants in `programs/heads-down/program/src/skr.rs`, not admin settings.

**`bury_auction_buy(skr_amount, max_ore)`.**
1. Compute the current price and the cost, and require `cost <= max_ore` (slippage guard).
2. The buyer's ORE moves into the BuryVault's ORE ATA (classic SPL Token; the ORE mint and token program are pinned).
3. The program CPIs ORE `bury` for exactly that amount, signed by the BuryVault PDA.
4. It re-reads the vault and the ORE supply: exactly the payment must have left, and the supply must have fallen by the burned 90%. Otherwise the whole purchase reverts.
5. Only then does the SKR go to the buyer.

**Outcome.** ORE's `bury` sends 10% of the ORE to ORE's stake program (`distribute`) and burns 90% (`bury.rs:45-74`). This path runs against the live ORE and ORE stake programs on the test fork. The dashboard reports both parts, never "100% burned".

**Why not the "grams address".** Research mentions an address `GHRBYPA4…` that "auto-buries" ORE sent to it. It is an ordinary wallet on mainnet and is not part of ORE's program, so its burning cannot be verified. See [ORE.md](ORE.md), section 10.

**Why no oracle.** A descending price finds the market on its own. The worst case is a lot that sells cheaply, which burns less ORE; no user funds are at risk. Each new lot restarts the price; if forfeits arrive more often than the window, the price can stay above the market for a while.

**Not built.** Routing the Executor PDA's SOL surplus through the same auction (the `bury_bps` proposal).

---

## 6. Why none of this is staking

The SKR prize excludes staking integrations ("SKR Staking Integrations do not qualify", per the Clock In FAQ quoted in `docs/research/skr-and-ore.md`). Here, staking means locking SKR, in Solana Mobile's staking program or any pool, to be paid inflation or fees for holding it.

| Test | Heads Down |
|---|---|
| Does SKR sit locked in order to accrue a return? | **No.** Bonds exist to be lost on a flinch, and they return only what the bond holder put in, plus other players' forfeits at Stack tables. |
| Is there an emission or subsidy source? | **No.** Heads Down mints and emits nothing. Every SKR paid out came from another player's forfeit at the same table. |
| Does the protocol pay you for holding or locking? | **No.** A Focus Bond pays nothing on success; you only get your own SKR back. |
| Does it touch the SKR staking program or staked balances? | **No.** No CPI, no reads, and no perk gated on staked SKR. |
| Where does locked SKR go? | Back to its owner, to finishers at the same table, or to a Dutch auction for ORE that ORE's `bury` burns. |

Wording rule: describe SKR flows only as "bond", "forfeit", "gift", "fuel" and "bury", and never with the banned words of [ECONOMICS.md](ECONOMICS.md), section 4.

---

## 7. Metrics (all from on-chain events)

Every SKR flow emits an event (INTERFACE.md v1.2 §11.9, tags 11 to 23):

| Metric | Events |
|---|---|
| Stack tables opened and settled, by mode; seats; finishers | `StackOpened`, `StackJoined`, `StackSettled` |
| Rounds proven per seat (and why a round did not count) | `StackCheckin` (result code) |
| SKR bonded, forfeited, returned, paid to finishers, refunded | `StackJoined`, `StackSettled`, `StackClaimed` (payout or refund) |
| Focus Bonds locked, released and forfeited (with the shift's reason) | `FocusBondLocked`, `FocusBondReleased`, `FocusBondForfeited` |
| Gifts sent, claimed (wallet or SGT) and refunded | `GiftCreated`, `GiftClaimed`, `GiftRefunded` |
| SKR into the Bury lot; ORE paid, burned and sent to ORE's stake program | `BuryLotAdded`, `BuryAuctionSold` |
| Gifts that became live rigs | `GiftClaimed` with `RigRegistered` in the same transaction |

SKR fuel volume is a client-side swap and appears only in the user's own transactions.

---

## 8. Draft of the submission's `skrIntegration` field

These are versions A and B of [pitch/SUBMISSION.md](pitch/SUBMISSION.md), word for word. Both were rewritten on 2026-10-10 so that every clause is true of mainnet that evening: the program is deployed, no SKR instruction has been sent there, and the app has only the Focus Bond chooser. Neither uses a word from the ECONOMICS.md section 4 lists.

**Version A (939 characters, at most 950):**

> SKR is Heads Down's commitment collateral and gifting currency, built into its Solana program, deployed on mainnet on 10 October 2026. No SKR instruction has run there yet, and the app has only the Focus Bond chooser. In tests on a fork of mainnet: Stack: players bond SKR on keeping their phones face-down for a window of ORE rounds. A seat counts a round only if its phone's Keystore-key heartbeat, verified by the secp256r1 precompile, landed in that round. Finishers get their bond back plus 80% of forfeits; 20% goes to a no-oracle Dutch auction whose buyers pay ORE that goes through ORE's own bury instruction. Focus Bond: SKR locked on one shift comes back if the shift completes, else it goes to that auction. Gift a Rig: SOL is escrowed for a wallet or a Seeker Genesis Token that only its current holder can claim; paying in SKR needs a swap not yet wired in. Heads Down emits no SKR, pays nothing for holding it and keeps none.

**Version B, the short one (518 characters):**

> SKR is Heads Down's commitment collateral and gifting currency. Four roles are in its Solana program, deployed on mainnet on 10 October 2026 and tested on a fork of mainnet: Stack bonds, the Focus Bond, Gift a Rig, and a no-oracle auction that sells forfeited SKR for ORE and sends that ORE through ORE's own bury instruction. No SKR instruction has run on mainnet yet. In the app the Focus Bond chooser is built; the Stack and Gift screens are not. Heads Down emits no SKR, pays nothing for holding it and keeps none.

If either text changes, change it in both files and run `python3 docs/pitch/check_pitch.py` again. The sentence "No SKR instruction has run on mainnet yet" stops being true with the first one that lands.

---

## 9. Where it is proven

All on the LiteSVM fork of live mainnet ORE (`programs/heads-down/tests/tests/`), with the real secp256r1 and Ed25519 precompiles, real SGT fixtures, and the live ORE and ORE stake programs for `bury`:

| Suite | What it shows |
|---|---|
| `skr_stack` | a six-seat table settled from real heartbeats (finish, grace, broken, too many gaps, missed end round) with the exact 80/20 split and conservation; bury-only and nobody-finishes tables; the timeout refund; join gating (time, room, signature, uniqueness, guest cap, attested-only, remote Seekers with a re-verified SGT); Token-2022 look-alikes, fake mints and wrong owners refused; check-in rules |
| `skr_bond` | release only after `completed`, forfeit after a hard break, a resumed pickup keeps the bond, abandoned bonds, lock gating |
| `skr_gift` | a wallet gift claimed into a funded rig in one transaction; an SGT gift following the SGT to its holder; refunds from day 30 |
| `skr_bury` | lots from real forfeits sold through the live ORE `bury`; price decay, restarts, floor, guards, pinned accounts; a misbehaving ORE that skips the burn is caught |
| `fuzz_skr` | 4,000 random and mutated SKR instructions, no aborts |
| `skr_capacity` | compute units and transaction sizes (INTERFACE.md §11.11) |
| `vectors` | the golden vectors in `programs/heads-down/vectors/` (all 13 SKR instructions executed) |
