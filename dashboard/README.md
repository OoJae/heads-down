# Heads Down traction dashboard

The public, verifiable traction site for Heads Down. It is a **fully static** Next.js (App Router)
build. Every number is fetched in the browser from the [indexer's public API](../services/indexer),
and each tile links to the on-chain transactions or accounts behind it.

## Run it

```bash
# 1. API (no Docker needed): simulated dataset on http://127.0.0.1:8787
cd services/indexer && pnpm install && pnpm demo

# 2. Dashboard
cd dashboard && pnpm install
NEXT_PUBLIC_HD_API_BASE=http://127.0.0.1:8787 pnpm dev      # http://localhost:3000
NEXT_PUBLIC_HD_API_BASE=https://api.example.org pnpm build   # static site in out/
pnpm start                                                   # serves out/ locally
```

`NEXT_PUBLIC_HD_API_BASE` is the only configuration. It is inlined at build time and is public by
design: the app has no secrets. A value that is not http(s), or that embeds credentials, is
rejected. Without it the pages explain how to connect one and show no numbers.

## Pages

| Route | What it shows |
|---|---|
| `/` | Headline tiles: rigs (total, Seeker-verified, guest; **registered minus closed from the v1.1 RigRegistered / RigClosed events**, with the Rig-account count as a visible cross-check), nightly active rigs, dark hours, rounds dug, SOL deployed into ORE, ORE mined, ORE bought and buried (**"Not shipped" placeholders, never 0**), gate-open rate, cranks. Also a 30-night active-rigs chart (Seeker vs guest) and a public integrity-check table. |
| `/cohorts/` | D1/D7/D14 retention by first-shift night: average tiles plus a cohort heatmap table (unfinished cells are hatched and say "not yet") |
| `/share/` | Heads Down share of unique ORE miners per round, by hour of day (time zone and 1/7/30-day window selectable), with the 00:00–06:00 milestone window emphasised, plus a "check it yourself" table of each hour's peak round with its ORE reset tx and a dig tx |
| `/digs/` | Recent digs feed: rig, tier, ORE round, SOL, squares, gate cost, with Solscan links for the tx and the rig, and a link to each rig's haul |
| `/skips/` | Every time the program declined to dig (RigSkipped): a histogram of reasons worded as what happened on chain (StaleHeartbeat = **replay rejected**, LeaseExpired = **phone went quiet**, CostGate = **price gate closed**), each with its code and range (heads_down, p256-introspect, sgt-verify). Selecting a bar filters the list below; a rig filter, paging and a CSV download are included |
| `/haul/?rig=<rig>&shift=<id>` | The **morning haul** of one finished shift, mirroring the phone's reveal: the verdict line, the 5×5 board of the selected round (squares dug, the square ORE drew), a strip of every round of the shift (dark, dug, hit), SOL placed, fees, ORE mined, the shift's effective price next to a market quote labelled with its source, and the streak. A shift picker lists the rig's finished shifts |
| `/milestones/` | ORE matched-prize milestones (docs/ORE.md §9) as meters against targets, the items only a human can check, and CSV downloads (per-round shares, digs, monthly report) |
| `/method/` | Sources, exact definitions (rigs, dark rounds, skips, the haul's effective price), what is not shown and why |

**Rigs by region is omitted on purpose.** The app collects no location, and no opt-in region
data exists, so there is nothing honest to plot. Milestone "regions" are time-zone windows.

## Honesty and safety

- **Simulated data cannot pass as real.** The API envelope carries `dataset.simulated`. When it is
  true, every page shows a striped **SIMULATED DATA** banner with the seed, every tile carries a
  **SIM** badge, no explorer links are rendered (the ids exist on no chain), and CSV buttons are
  badged. The client **pins the first dataset it sees** and rejects any later response from another
  dataset (`DatasetMismatchError`), so a misconfigured deploy cannot show simulated and real numbers
  side by side. It also rejects an envelope whose `simulated` flag contradicts its name.
- **Links are allowlisted.** Evidence URLs from the API are rendered only if they match Solscan or
  Solana Explorer patterns for a base58 id (`safeExplorerUrl`). A compromised API cannot inject
  `javascript:` or look-alike domains. External links use `rel="noopener noreferrer"`.
- **CSP** ships as a meta tag (static hosting cannot set headers). Data may only be fetched from
  this origin and the configured API origin, with `object-src 'none'`, `base-uri 'none'` and
  `form-action 'none'`. Requests go out with `credentials: "omit"`.
- **No banned wording.** `test/honesty.test.ts` scans every source file and every built page for
  earn / yield / stake / APY / passive income / "proof of focus", and for the reveal's own list
  (profit, guarantee, jackpot, lottery and the like). Heads Down accumulates ORE by the cheaper
  route; it is not a return on capital.
- **The haul says what happened, not what it is worth.** The verdict compares the shift's own
  effective price with a market quote and says "Buying was the cheaper route" as plainly as
  "Mining was the cheaper route". A shift that mined nothing shows no price instead of a zero.
  The market quote carries its source's name and is the only figure not read from the chain. A
  haul from localnet or devnet says under its verdict that few miners share the board there, so
  its price per ORE says nothing about mainnet (on the local fork a rig mines whole ORE for
  dust). A haul whose `simulated` flag disagrees with the pinned dataset is rejected.
- **u64 stays exact.** Amounts arrive as decimal strings and are formatted with BigInt. Non-zero
  dust shows as `<0.001`, never as `0`.

## Design

- **Palette.** The Heads Down palette from the Android theme: charcoal background, **ember orange =
  rig hot**, **ORE gold = hauls**. Dark by default; a light theme is opt-in (remembered in
  localStorage, with try/catch). Chart marks use steps checked with the dataviz palette validator.
  Every check passes in both modes: lightness band, chroma floor, CVD ΔE ≥ 26, normal-vision ΔE ≥ 29,
  and ≥ 3:1 contrast. Dark: guest `#E8590F`, Seeker `#3987E5`, haul `#C98500` on `#1C1D20`. Light:
  `#C2410C`, `#2A78D6`, `#B07A00` on white. Text always uses text colors. Color marks identity only
  through swatches. The skips histogram uses **one hue** for all reasons (they are categories of the
  same thing, named by their labels), and a selected bar is emphasised by graying the rest. On the
  haul's round strip, ember and gold are too close for color-blind readers (validator ΔE 2), so a
  round whose square came up is marked with an ink ring and a star, not a second hue.
- **Charts** are hand-built SVG. They render at the container's measured pixel width, so tick text
  stays 11 px on a phone. Columns are ≤ 24 px with a rounded data end, there is a 2 px surface gap
  between stacked segments, and grids are solid hairlines. Every column is keyboard-focusable with an
  `aria-label` and a tooltip on hover or focus, and every chart has a "Show as table" view. The
  cohort heatmap is a real `<table>` whose cells print their values (one hue: sequential).
- **Mobile-first.** Two-column tiles on phones and four on desktop, a horizontally scrollable nav,
  and wide tables scroll inside their cards.

## Tests

```bash
pnpm test        # vitest + jsdom + Testing Library
pnpm typecheck   # tsc --noEmit (strict)
pnpm build       # static export must succeed
```

- `test/components.test.tsx` renders every component against **real API responses captured from
  the indexer's simulated dataset** (`test/fixtures/*.json`). It covers the SIMULATED banner, SIM
  badges, the absence of links for simulated ids, allowlisted links on real data, "Not shipped"
  placeholders, integrity flags, chart accessibility (focusable labelled marks, tooltip on focus,
  table view, ≤ 24 px bars), cohort "not yet" cells, the digs feed and milestone meters.
- `test/haul-skips.test.tsx` renders the Skips view and the haul against captured responses
  (`skips.json`, `haul.json`, `shifts.json`): one selectable bar per reason with its name, plain
  meaning and count; the table view; the labels for a replay and a quiet phone; rig links to the
  haul page and no links for simulated transactions; the reveal's title, counts, price against
  market, verdict and streak; the round strip and the board of the selected round; allowlisted
  explorer links; the verdict arithmetic (parity with the Android HaulMath tests); and the haul
  client refusing a contradictory or foreign-dataset response and turning a 404 into a plain message.
- `test/lib.test.ts` covers BigInt formatting (including u64::MAX), dataset pinning and mismatch
  rejection, envelope validation, API base validation and the explorer allowlist.
- `test/honesty.test.ts` scans the sources and the `out/` build for banned wording.
