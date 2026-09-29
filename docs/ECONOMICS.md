# Heads Down economics

What a rig costs, what mined ORE really costs, when Heads Down mines and when it buys, how to talk about it honestly, where SKR flows, and how the project pays for itself.

> Numbers marked **TBD** depend on the Cost Forecaster backtest or on measurements from the spikes, and must not be quoted as results. Snapshot numbers were read from mainnet on **2026-09-29, around slot 451,705,990**, over RPC: the ORE Board and Treasury; Rounds 422,590 to 422,601 for per-round totals; and 24 consecutive rounds read minutes later for the crowding figure. Prices come from Jupiter Price v3 at the same time. ORE formulas cite ORE commit `b92c5043` ([ORE.md](ORE.md)).

---

## Summary

- **One fee, fixed, no percentage.** Each rig-round that is actually dug pays a fixed crank-cost fee (TBD, sized to measured cost). Heads Down takes no cut of mining. The project's only revenue is a disclosed Jupiter platform fee on the buy leg, which the user signs.
- **About 10.5% of the SOL you deploy does not come back.** ORE takes 1% from every square and a further 9.9% from losing squares (`state/round.rs:86-99`). The rest returns at checkpoint. That 10.5% is what the ORE costs you.
- **The board is nearly flat.** Across 24 recent rounds, the 4 least-crowded squares ended with a median of 97.7% of the average square's SOL (range 96.9% to 99.9%). Choosing squares on-chain buys about a 2% discount, not a large one.
- **A typical night costs more than the ORE average suggests.** Without a Motherlode share, a typical night costs about **1.33x** ORE's `production_cost_ema` per ORE. Counting the Motherlode's long-run value, the average is about **1.105x** the EMA.
- **Today the rig would stay cold.** At the snapshot the EMA was 0.919 SOL per ORE and the market price 0.758 SOL per ORE. A typical night of mining would have cost about 1.19 SOL per ORE, 57% more than buying. The gate stays closed, and the morning buy leg buys at market instead.
- **Honest framing.** Always show the typical-night price next to the average, and the odds of a Motherlode share next to its size. Never show the average alone.

---

## 1. Fees

| Fee | Who pays | Who receives | Size | Notes |
|---|---|---|---|---|
| **Crank-cost fee** (ORE `Discretionary` fixed fee) | the rig's ORE Automation | the Heads Down **Executor PDA** | **TBD**, a fixed number of lamports per dug rig-round | Charged by ORE once per round, on the first deploy (`deploy.rs:338-342`). The Executor reimburses the cranker a fixed amount per successful rig-round and sends any surplus to the Bury path. The Executor has **no withdraw path**. `dig` requires `automation.fee == Config.crank_fee` exactly |
| ORE admin fee | deployed SOL | ORE fee collector | 1% of every square | `state/round.rs:91-92` |
| ORE protocol fee | deployed SOL | ORE Treasury (buyback and bury) | 10% of the remainder on losing squares | `state/round.rs:93-95` |
| `CHECKPOINT_FEE` reserve | user at `automate` | whoever checkpoints late (the last 12 h before expiry) | 10,000 lamports, kept on the Miner | Refilled from the Executor PDA only after a late third-party checkpoint (`deploy.rs:327-330`) |
| ORE refining fee | the ORE you claim | other holders of unrefined ORE | 10% of unrefined ORE claimed | `state/miner.rs:89-99`. Keeping ORE unrefined avoids it and accrues a share of other people's fees |
| Jupiter platform fee (**revenue**) | the user, on the buy leg only | Heads Down's referral account | **TBD** bps (proposal: 25 bps or less), shown in the quote before signing | Proposed: no platform fee on SKR fuel or gift swaps |

**Sizing the crank-cost fee (TBD, measured in spikes 1(a) and 1(b)).** Per dug rig-round the crank pays:
- one secp256r1 precompile signature, expected to be charged like a transaction signature at 5,000 lamports (to confirm in spike 1(b));
- a share of the 5,000-lamport transaction signature, divided by the rigs per transaction (about 6 to 10);
- a share of the priority fee and Jito tip;
- a share of the checkpoint instruction.

That is about 5,900 lamports before priority fees at 8 rigs per transaction.

Two live reference points: ORE's own permissionless executor charges **7,000** lamports per round (`docs/research/skr-and-ore.md`), and a third-party `Discretionary` automation we sampled on mainnet charges **12,000**. The examples below use **10,000 as a placeholder**. If leases were verified once and recorded on-chain, one precompile signature could cover up to 3 rounds, which is a lever for lowering the fee.

**Why a fixed fee rather than basis points.**
- A percentage fee rewards the executor for deploying more, the conflict of interest the red team flagged.
- A fixed fee is independent of the amount deployed, and it is paid only when ORE actually deploys.
- The trade-off: on very small deploys the fee dominates. Proposed rule: the app refuses plans where the fee exceeds 10% of the expected burn per round, which means at least about 0.001 SOL per round at a 10,000-lamport fee.

---

## 2. What mined ORE actually costs

For one square, with your `a` lamports and `D` lamports from everyone else, and `M` ORE in the Motherlode pool:

```
expected SOL that does not come back   = a * L,   L = 0.01 + (24/25) * 0.99 * 0.10 = 0.10504
expected ORE                           = (1/25) * a / (D + a) * (1 + M / 500)
expected cost per ORE (this square)    = 25 * L * (D + a) / (1 + M / 500) = 2.626 * (D + a) / (1 + M / 500)
```

- **Sources.** Fees: `state/round.rs:86-99`. Winning square is `rng % 25`: `state/round.rs:75-77`. 1 ORE per round shared on the winning square, split pro rata or weighted solo (the expectation is the same either way): `checkpoint.rs:100-130`. Motherlode 1-in-500, pro rata on the winning square: `state/round.rs:107-109`, `checkpoint.rs:133-146`.
- **Assumptions.** Squares win uniformly. The Motherlode draw is treated as independent of the winning square, which is approximately true because both come from one 64-bit value. Rounding (`max(..., 1)` lamport) is ignored.

**From ORE's EMA to a price you can compare with the market.** `Board.production_cost_ema` is `total_vaulted * ONE_ORE / 1.2 ORE`, averaged over 20 rounds (`reset.rs:239-251`). Vaulted SOL is only the protocol fee, and the denominator includes the 0.2 ORE Motherlode top-up. So, on a board where deployments are roughly even:

| View | Multiplier on the EMA | At the snapshot (EMA 0.919 SOL/ORE) |
|---|---|---|
| ORE's `production_cost_ema` | 1.000 | 0.919 SOL/ORE |
| Board average, all-in (admin + protocol), with the Motherlode at its long-run rate | x 1.105 (measured 1.1052: admin 0.110 + vaulted 1.049 SOL per round) | 1.015 |
| **Typical night** (no Motherlode share), average crowding | **x 1.326** (= 1.105 x 1.2) | 1.218 |
| Your squares | x (D_chosen / D_average), about 0.977 at round end | |
| Plus the fixed fee | x (1 + fee / expected burn per round), 1.048 in the example plan | |
| If you claim the same morning | / 0.9 (refining fee) | |

Market price at the snapshot: ORE $89.41, SOL $117.96, so **0.758 SOL per ORE**.

**Traps to avoid.**
- **ORE's own helper is not a price.** `Automation.production_cost()` (`state/automation.rs:138-144`) divides **gross** SOL deployed (`total_sol_spent`, which grows by the full deployed amount at `deploy.rs:335`) by ORE mined, so it overstates the cost about 9.5-fold. Heads Down computes the net cost from its own ShiftLog and checkpoint returns.
- **The current Motherlode pool is not the long-run one.** The pool is 344 ORE today; its long-run mean is about 100 ORE (0.2 ORE per round times 500 rounds). The average price including the Motherlode swings with the pool, while the typical-night price does not.

---

## 3. The gate and the mine-or-buy logic

```
 REFUEL (wallet signs)          NIGHT (per ORE round, on-chain)             MORNING (wallet signs)
 weekly / shift / round caps    dig allowed only if:                        realized price from on-chain
 expiry                         fresh P-256 heartbeat                       deltas vs the Jupiter quote;
 hard ceiling C_wallet    ----> caps and expiry left                ----->  if mining was pricier or
 (Foreman proposes; the         Board.production_cost_ema                   short of the night's target:
  P-256 plan can only lower)    <= min(C_wallet, C_plan)                    buy the rest at market
```

**The on-chain check needs no oracle.** `dig` compares `Board.production_cost_ema`, read directly from ORE and owner-checked, with the lower of two ceilings:
- the ceiling the wallet signed at refuel;
- the ceiling in the P-256-signed plan, which can only be lower.

The market price enters only through those two ceilings. Both are signed by the user's own devices, and the Foreman proposes them. Overnight the phone can sign a *tighter* plan if the price falls, never a looser one.

**Default ceilings, in EMA units.** They are computed at arm time from the Jupiter price, with the multipliers from section 2 (`f` = fee share of the expected burn, `m` = safety margin):

| Tile policy | Ceiling | At the snapshot (m = 0) | With m = 5% | Meaning |
|---|---|---|---|---|
| **Steady** (split squares only; default) | `market / (1.326 x 0.977 x (1 + f)) x (1 - m)` | 0.558 SOL/ORE | 0.530 | A *typical* night beats buying |
| **Hunter** (opt-in; solo squares allowed) | `market / (1.105 x 0.977 x (1 + f)) x (1 - m)` | 0.670 | 0.637 | Only the *average*, including the Motherlode, beats buying; most nights will not |
| Claim the same morning | multiply either ceiling by 0.9 | | | Pays the refining fee |

The margin `m`, how often each gate opens, and whether the Forecaster's per-round amount curve beats a flat plan are all **TBD by the backtest**. The Forecaster must beat both "always buy" and "always mine" on effective price per ORE over the backtest window, or it is demoted to advisory and the defaults above apply unchanged. For reference, the build plan's figure (api.ore.com production cost below price in 150 of 167 hourly snapshots, Sep 22 to 29) compares a different metric with no multipliers. It is closer to Hunter with `m = 0` than to Steady, so Steady will open less often than that figure suggests.

**The clock-out buy leg.**
- **Realized effective price** = (SOL that did not come back + crank fees) / ORE mined, all from on-chain deltas (the ShiftLog, the Automation balance, `Miner.rewards_ore`). It is shown both as "kept unrefined" and as "if claimed now".
- **Buy suggestion.** If the user set a nightly target, for example 0.02 ORE, and mined less, or mining was the pricier route, the app offers to buy the remainder at market.
- **The swap** is a Jupiter swap signed by the user, with the minimum output set on-device from the displayed quote, a slippage cap, simulation first, and the platform fee shown.
- **The promise** "every shift ends with more ORE, by the cheaper route" is about choosing the route. It is not a claim of profit.

---

## 4. Honest EV framing

**The EV meter always shows four things together:**
1. The **typical-night price** per ORE (no Motherlode share), for tonight's plan.
2. The **average price** including the Motherlode, labelled with the pool size.
3. The **market price** (Jupiter).
4. The **odds of sharing a Motherlode during this shift** and roughly how much it would be: "about 1.9% tonight, about 0.4 ORE if it lands".

Once there is history, the realized effective price per ORE is shown next to what the Foreman forecast ("Foreman said 0.66, you paid 0.68").

**Variance, stated plainly.**
- In the example plan a night has about 9.6 winning square-rounds (a Poisson count). On split squares each pays a small pro-rata amount, so Steady hauls are smooth.
- Solo squares pay 1 ORE to one weighted winner, which makes Hunter hauls lumpy: most nights come in below the average, and a few come in far above.
- The Motherlode is hit about once per 500 rounds, about every 11 h at 78 s per round. The *board* hits it on about half of all nights, but a rig shares only if one of its squares wins that round.

**Words never to use** (UI, deck, demo transcript, store listing, posts):

| Never say | Why | Say instead |
|---|---|---|
| earn, earnings | implies income | dig, haul, accumulate |
| yield, APY, APR, returns, interest | implies a rate of return | effective price per ORE |
| stake, staking, staked | the SKR prize excludes staking; implies a passive reward | bond (SKR), deploy (SOL) |
| passive income | same | "give your idle phone a job" |
| proof of focus, focus mining | overclaims; we measure one phone's screen-off, face-down time | "dark hours on this phone" |
| profit, guaranteed, risk-free, free ORE | false | "the cheaper route", the EV meter |
| lottery, jackpot, gamble, bet | gambling framing | "Motherlode" (ORE's term), always with its odds |
| invest, investment | regulatory framing | accumulate, buy |
| rewards (for SKR outcomes) | staking connotation | "forfeits paid to finishers" |

---

## 5. Worked example: a night's budget

**The plan the wallet signs at the weekly refuel:**
- Caps: 0.5 SOL deployed per week, 0.12 SOL per shift, 0.002 SOL per round (4 squares x 0.0005 SOL).
- Expiry: Sunday 23:59 local.
- Hard ceiling: 0.67 SOL/ORE in EMA units (Hunter-level). The nightly plan tightens it to Steady.
- ORE settings: `automation.amount = 0.0005 SOL`, which is also ORE's own per-square ceiling on any executor (see [THREAT_MODEL.md](THREAT_MODEL.md), K5); `reload = 1`; crank fee 10,000 lamports (placeholder).

**The night.** 23:10 to 06:50 is about 354 ORE rounds at about 78 s each. At 0.002 SOL per round, the shift cap binds after **60 digs**.

**If the cap is fully used:** 0.12 SOL is deployed. Of that, about **0.0126 SOL** is not expected to come back, plus **0.0006 SOL** in crank fees, for **0.0132 SOL in total (about $1.56)**. The rest returns into the Automation at each checkpoint.

### Scenario A: tonight, at the snapshot (real numbers)

| | Value |
|---|---|
| `production_cost_ema` | 0.919 SOL/ORE |
| Market | 0.758 SOL/ORE |
| Steady ceiling (m = 5%) / Hunter ceiling | 0.530 / 0.637 SOL/ORE |
| **Gate** | **closed every round**: 0 digs, 0 SOL deployed, 0 crank fees |
| What mining would have cost if forced open | typical night **1.19 SOL/ORE** (1.32 if claimed at once); average with today's 344-ORE pool 0.70 SOL/ORE; a 1.9% chance of a Motherlode share worth about 0.40 ORE |
| Morning | "Buying was cheaper all night. Buy your 0.02 ORE for 0.0152 SOL?" (plus price impact and the TBD platform fee) |
| Streak, rooms, Stack | still count: a focus-only shift |

### Scenario B: a quieter board (hypothetical; how often this happens is TBD by the backtest)

| | Value |
|---|---|
| `production_cost_ema` | 0.50 SOL/ORE, which implies about 6.3 SOL per round and about 0.247 SOL on each chosen square |
| **Gate (Steady, m = 5%)** | **open** (0.50 < 0.530) |
| Digs and cost | 60 digs, 0.0132 SOL |
| Typical haul | **0.0194 ORE, i.e. 0.680 SOL/ORE**, 10% below market if kept unrefined; 0.756 if claimed the same morning, about the same as buying |
| Average with the Motherlode (pool at its long-run 100 ORE) | 0.567 SOL/ORE; a 1.9% chance of a share worth about 0.20 ORE |
| Buying the same 0.0194 ORE | 0.0147 SOL. Mining saved about 0.0015 SOL (about $0.18) on a typical night |

**What the example shows.** Even when the gate opens, the typical saving over buying is small, and the refining fee can erase it. Heads Down's value is the ritual, the trustless phone gate and honest route choice, not a money machine. We say that on screen.

---

## 6. SKR flows (economic view)

Details, accounts and rules are in [SKR.md](SKR.md). No SKR is minted, emitted or routed to the team.

| Flow | SKR comes from | SKR goes to | Other assets | Heads Down's share |
|---|---|---|---|---|
| Stack (table contest) | each seat's bond | finishers: their own bond back plus 80% of forfeits pro rata; 20% of forfeits to Bury lots (100% if no one finishes, or at a bury-only table) | none | 0 |
| Focus Bond (solo) | the user | back to the user on a clean finish; otherwise to Bury lots | none | 0 |
| Gift a Rig | the sender | swapped to SOL by Jupiter in the sender's own transaction | SOL is escrowed against the recipient's SGT (or wallet), becomes their rig's deposit, or is refunded after 30 days | 0 (proposal: no platform fee) |
| SKR fuel | the user | swapped to SOL by Jupiter in the refuel transaction | SOL goes into the user's own ORE Automation | 0 (proposal) |
| Bury auction | forfeited lots | the buyer who pays ORE at the descending price | the ORE goes through ORE's `bury`: 90% burned, 10% to ORE's stake program (`bury.rs:45-74`) | 0 |

---

## 7. Revenue and costs

- **Revenue.** A Jupiter platform (referral) fee on the user-signed buy leg: TBD bps, shown in the quote, net of Jupiter's own share under its current terms. At 300 nightly buy legs of 0.02 ORE (0.0152 SOL each) and 25 bps, that is about **0.011 SOL a night (about $1.34)**. That covers some infrastructure; it does not fund a company, and we do not pretend otherwise.
- **Cost recovery, not revenue.** The crank-cost fee reimburses whoever cranks. The Executor's surplus goes to the Bury path.
- **Costs.** RPC (Helius behind a proxy), hosting, and Kora-sponsored heartbeat transactions for Stack seats and focus-only shifts (about one signature fee per sponsored transaction, rate-limited to one per rig per round; TBD per day at launch volumes).
- **Never.** No token of our own, no points, no emissions, no paid referrals, no SKR or ORE paid to testers.

---

## 8. TBD register

| Item | Depends on | Owner |
|---|---|---|
| Crank-cost fee `Config.crank_fee` | compute units and fees measured in spikes 1(a) and 1(b); rigs per transaction | program and crank |
| Safety margin `m`; default policy per user | Forecaster backtest | ml |
| Share of nights and rounds when the Steady and Hunter gates open | backtest on archived api.ore.com history and Round snapshots | ml |
| Forecaster value against "always buy" and "always mine" | backtest (if it fails, advisory only) | ml |
| Least-crowded advantage *at dig time* (0.977 is the end-of-round figure) | Round snapshots taken at the crank's deploy slot | crank |
| Platform fee in bps | product decision after the backtest | product |
| Round cadence (78 s) | continuous measurement | crank |
