# SKR in Heads Down

SKR has four jobs in Heads Down:

- **commitment collateral:** Stack and Focus Bonds;
- **gifting currency:** Gift a Rig;
- **funding rail:** SKR fuel;
- **sink:** the Bury auction, which turns every SKR forfeit into ORE that ORE's own `bury` instruction burns.

None of it is staking. Heads Down emits no SKR, and none of it flows to the team.

> Status: this is the design the program must implement ([SPEC.md](SPEC.md)). Checks and negative tests are listed in [THREAT_MODEL.md](THREAT_MODEL.md), section 7. Anything marked **proposal** or **open** is not settled.

**SKR facts used here** (`docs/research/skr-and-ore.md`):
- Mint `SKRbvo6Gf7GondiT3BbTfuRDPqLWei4j2Qy2NPGZhW3`, a classic SPL Token (not Token-2022) with 6 decimals and no freeze authority.
- Price on 2026-09-29: $0.01821 (Jupiter Price v3), so 200 SKR is about $3.64 and 500 SKR about $9.10.
- Solana Mobile's SKR staking program is `SKRskrmtL83pcL4YqLWt6iPefDqwXQWHSw9S9vz94BZ`. Heads Down never calls it, reads it, or references it.

---

## Summary of flows

| Flow | Who signs | Where the SKR sits | How it settles | Where it ends up |
|---|---|---|---|---|
| **Stack** | each seat's wallet, to join | per-table vault (an ATA owned by the table PDA) | permissionless, from on-chain heartbeats | finishers get their bond back plus 80% of forfeits pro rata; 20% goes to Bury lots (100% if no one finishes, or at a bury-only table) |
| **Focus Bond** | the user's wallet | per-bond vault | permissionless release or forfeit | back to the user, or to Bury lots |
| **Gift a Rig** | the sender's wallet | converted to SOL at once and held in a `GiftEscrow` | the recipient's SGT is verified in-program | the recipient's ORE rig, or back to the sender after 30 days |
| **SKR fuel** | the user's wallet | never held by Heads Down | an atomic swap inside the refuel transaction | the user's own ORE Automation, as SOL |
| **Bury auction** | the buyer | the `BuryVault` | a descending-price (Dutch) sale for ORE; no oracle | the buyer gets the SKR; the ORE goes through ORE `bury` (90% burned, 10% to ORE's stake program) |

---

## 1. Stack: a self-control contest with SKR bonds

**What it is.** A table of players each bond the same amount of SKR on keeping their phones face-down and unused for a window of ORE rounds. Whoever holds out takes back their bond plus a share of the forfeits.

**Modes.**
- **In-person table (primary).** Phones find each other over Nearby Connections. Players compare Nearby's authentication digits before connecting, and every phone relays heartbeats for every seat. Because players are physically together, sybil attacks are hard, so guest rigs (no SGT) may join with capped bonds, but their key must still be attested (level 1 or higher).
- **Remote "honor-plus" table.** Played in a regional room, for Seeker rigs only: a live `SeekerSeat` plus attestation level 1 or higher. The label says plainly that a rooted device with a leaked keybox could fake heartbeats (THREAT_MODEL, section 9). Bonds are capped lower.

**Accounts** (the SPEC's seeds):
- `StackTable ["stack", table_id]`. It holds:
  - the mode;
  - the bond amount and bond cap;
  - the window `[start_round, end_round]` in ORE rounds;
  - grace gaps;
  - the forfeit split in basis points (default 8000/2000, bury-only 0/10000);
  - the tier requirement;
  - a vault ATA for the SKR mint whose authority is this table's PDA;
  - totals and a settled flag.
- `StackSeat ["stackseat", table, sgt_mint]`: the rig, the bond, a status (active, finished or broken) and a claimed flag.
- **Open:** guest seats at in-person tables have no SGT mint, so the seat needs a rig-keyed variant. The program workstream decides.

**Lifecycle.**
1. `open_stack`: the host creates the table and its vault.
2. `join_stack`: the joiner's wallet signs. The program checks:
   - the mint is SKR and the token program is SPL Token (Token-2022 look-alikes are rejected);
   - the bond equals the table bond and is within the cap;
   - it is before `start_round`, the table has room, and the seat is unique;
   - the attestation level (and, at remote tables, a re-verified SGT) meets the table's requirement.

   It then moves the bond with an SPL Token CPI, from the joiner's ATA to the table vault.
3. **During the window.** Each seat needs a heartbeat that lands for every ORE round (lease = 1), verified through secp256r1 introspection ([THREAT_MODEL.md](THREAT_MODEL.md), section 6). A round with no heartbeat landed counts as a gap. A P-256-signed BREAK marks the seat broken.
4. `settle_stack`: permissionless once `Board.round_id > end_round`. A seat has **finished** if it is not broken, its `last_hb_round >= end_round`, and its gaps are within grace.
5. `claim_stack`: pull-based, once per seat.
6. `refund_stack`: if nobody settles within a timeout, every seat gets its own bond back.

**Payout arithmetic (conservation is proved with Kani).**
- Let `B` be the total of all bonds, `W` the bonds of finishers and `F = B - W` the forfeits.
- If `W > 0`, each finisher receives `bond_i + floor(0.8 * F * bond_i / W)`, computed in u128.
- Bury receives `F` minus the sum of those floored shares, which is the 20% plus any rounding dust.
- If `W == 0`, Bury receives all of `B`.
- Invariant: `sum(payouts) + bury == B`. No seat can claim twice.

**Why this is fair only up to a point.** The chain verifies signatures, not behaviour. See THREAT_MODEL, section 9. Stack measures "this phone stayed face-down", which is why it is labelled a self-control contest, never "proof of focus".

**Legal risk, and the bury-only option.** Forfeits that pay finishers could be treated as wagering in some jurisdictions (SPEC open question). Mitigations:
- **Bury-only tables:** every forfeit goes to the Bury auction, so nobody gains from anyone else's flinch. **Proposal:** make bury-only the default for remote tables.
- Low bond caps (**TBD**; for scale, 500 SKR is about $9).
- The app is 18+.
- No house cut, ever.

---

## 2. Focus Bond: solo commitment

**What it is.** You lock SKR on a declared solo shift. Finish clean and it comes back. Break early and it goes to the Bury auction, never to the team and never to another player.

**Account.** `FocusBond ["bond", sgt_mint, shift_id]`, with its own vault ATA (SKR, authority = the bond's PDA).

**Instructions.**
- `lock_focus_bond`: the wallet signs; the bond is capped.
- `release_focus_bond`: after `end_shift` with no break, back to the holder.
- `forfeit_focus_bond`: **permissionless** once a BREAK, or a gap beyond grace, is on-chain; the SKR moves to `BuryVault` lots.

Forfeiting is permissionless so that nobody (the user included) can hold a broken bond hostage.

---

## 3. Gift a Rig: SKR sent to a `.skr` name arrives as a live ORE rig

**Sender flow.** One wallet approval covers:
- `[Jupiter swap SKR to SOL]`, a sibling instruction signed by the sender with a minimum output set from the displayed quote;
- `[heads_down::create_gift(recipient, lamports, nonce)]`, which moves SOL from the sender into `GiftEscrow ["gift", recipient_sgt_mint, nonce]` and records the sender and a 30-day expiry.

A gift can also be keyed to a plain wallet, for recipients without a Seeker. It can be started from `ACTION_PROCESS_TEXT` on any `.skr` name selected in another app.

**Recipient resolution happens off-chain and is shown before signing.** The app resolves the `.skr` name to its owner wallet (AllDomains), then to the SGT mint that wallet holds (a Token-2022 lookup). It shows both. The escrow binds to the **SGT mint**, not to the name, so a later name transfer cannot redirect the gift.

**`claim_gift`.**
- The signer must **currently** hold that SGT, verified in-program with the `sgt-verify` checks (owner, group, mint authority, amount of 1).
- The same transaction can run the claimer's own ORE `automate` (executor = Heads Down Executor PDA) and `register_rig`, so the gift arrives as a rig that is already funded and digging.
- Alternatively, the lamports can go to the claimer's wallet.

**`refund_gift`.** After expiry, anyone may trigger it, but the lamports go only to the stored sender.

**Honest notes.**
- The escrow holds **SOL**, not SKR. The sender bears the swap price and slippage at send time.
- Solana Mobile, as SGT issuer, holds permanent-delegate authority, so it could move an SGT before a claim. The gift then follows the SGT's new holder, by design.

---

## 4. SKR fuel: pay for a refuel in SKR

One transaction contains `[Jupiter swap SKR to SOL]`, `[ORE automate deposit]` and `[heads_down::refuel]`. It is atomic: if the swap delivers less than its minimum output, the whole transaction fails. Heads Down never CPIs Jupiter.

This is a convenience, not the novelty. refinORE already ships SKR-funded ORE mining (`docs/research/skr-and-ore.md`), and we do not claim otherwise.

---

## 5. Bury auction: forfeits become burned ORE, with no oracle

**Accounts.**
- `BuryVault ["bury"]` holds SKR lots created by Stack and Focus Bond forfeits, plus an ORE ATA.
- Each lot records its amount, its start slot, a start price `P0` in ORE per SKR, a floor price, a decay rate, and a sold flag.

**Price.** It falls linearly with slot from `P0` to the floor, computed from the Clock sysvar (read by syscall). `P0` is anchored to the last clearing price times a factor. The factor, floor and decay are **TBD**; they are Config parameters behind the timelock. No price feed is read.

**`bury_auction_buy(lot, max_price)`.**
1. Compute the current price and require `price <= max_price` (slippage guard).
2. The buyer signs a transfer of `amount x price` ORE into the `BuryVault` ORE ATA. The ORE mint is pinned, and the token program must be SPL Token.
3. CPI ORE `bury`, signed by the BuryVault PDA, with the vault's ORE ATA as sender (`bury.rs:13-21`).
4. Re-read balances after the CPI, because `bury` takes `min(balance, amount)` (`bury.rs:35`). Require that the full payment was buried.
5. Send the SKR lot to the buyer and mark the lot sold, so it cannot be sold twice.

**Outcome.** ORE's `bury` sends 10% of the ORE to ORE's stake program (`distribute`) and burns 90% (`bury.rs:45-74`). The dashboard reports both parts, never "100% burned".

**Why not the "grams address".** Research mentions an address `GHRBYPA4…` that "auto-buries" ORE sent to it. It is an ordinary wallet on mainnet and is not part of ORE's program, so its burning cannot be verified. See [ORE.md](ORE.md), section 10.

**Why no oracle.** A descending price finds the market on its own. The worst case is a lot that sells cheaply, which burns less ORE; no user funds are at risk.

**Proposal.** The Executor PDA's SOL surplus (crank fees above reimbursements) is sold through the same Dutch mechanism for ORE and buried the same way.

---

## 6. Why none of this is staking

The SKR prize excludes staking integrations ("SKR Staking Integrations do not qualify", per the Clock In FAQ quoted in `docs/research/skr-and-ore.md`). Here, staking means locking SKR, in Solana Mobile's staking program or any pool, to receive inflation, fees or rewards for holding it.

| Test | Heads Down |
|---|---|
| Does SKR sit locked in order to accrue a return? | **No.** Bonds exist to be lost on a flinch, and they return only what the bond holder put in, plus other players' forfeits at Stack tables. |
| Is there an emission or subsidy source? | **No.** Heads Down mints and emits nothing. Every SKR paid out came from another player's forfeit at the same table. |
| Does the protocol pay you for holding or locking? | **No.** A Focus Bond pays nothing on success; you only get your own SKR back. |
| Does it touch the SKR staking program or staked balances? | **No.** No CPI, no reads, and no perk gated on staked SKR. |
| Where does locked SKR go? | Back to its owner, to finishers at the same table, or to a Dutch auction for ORE that is burned. |

Wording rule: "bond", "forfeit", "gift", "fuel", "bury". Never "stake", "staking", "yield", "earn" or "rewards" for any SKR flow ([ECONOMICS.md](ECONOMICS.md), section 4).

---

## 7. Metrics (all from on-chain events)

- SKR bonded, forfeited, returned, and paid to finishers.
- ORE buried through Bury auctions (burned and distributed shown separately).
- Stack tables opened and settled, by mode.
- Focus Bonds locked, released and forfeited.
- Gifts sent, claimed and refunded, including claims that became live rigs.
- SKR fuel volume.

---

## 8. Draft of the submission's `skrIntegration` field

**Full version (953 characters):**

> SKR is Heads Down's commitment collateral and gifting currency. In Stack, Seekers bond SKR on keeping their phones face-down through the night, at a real table (phones paired over Nearby) or in a regional room. Settlement is permissionless and on-chain: each phone's hardware-key heartbeat is checked by Solana's secp256r1 precompile, and a pickup or missed heartbeats beyond the table's grace forfeit the bond. Finishers take back their bond plus 80% of the forfeits. The other 20% is sold in a no-oracle Dutch auction for ORE, which ORE's own bury instruction burns. Focus Bonds do the same for a solo shift, with every forfeit going to that auction. Gift a Rig turns SKR sent to any .skr name into a live ORE rig, escrowed against the recipient's Seeker Genesis Token so only that Seeker can claim it. SKR can also fuel a night's mining inside the same signature. Heads Down never locks SKR for a return, never emits SKR, and takes no SKR from users.

**Short version (286 characters):**

> SKR is the collateral and gift currency of Heads Down. Seekers bond SKR on keeping their phones face-down, settled on-chain from hardware-key heartbeats; forfeits go to finishers and to buying ORE that is burned. Gift a Rig turns SKR sent to a .skr name into a live, SGT-locked ORE rig.
