/**
 * Totals of the v1.2 SKR features (INTERFACE §11): Stack tables, Focus Bonds, Gift a Rig and the
 * Bury auction. Every number is a count or a sum of fields of the program's own events (ev_ext),
 * so it can be re-added from the chain. Amounts are base units as decimal strings: SKR has 6
 * decimals, ORE 11, gifts are lamports.
 *
 * Nothing here is a price, a return or a forecast. "skrToFinishers" is the part of the forfeited
 * bonds the finishers of a table received on top of their own bonds; "oreBurned" is the 90% of an
 * auction payment ORE's own `bury` burned, and "oreToStakers" the 10% it sent to ORE's stake
 * program.
 */

/** One group of ev_ext rows: an event name, split by its kind / recipient_kind / source_kind / result field. */
export interface SkrEventGroup {
  name: string;
  /** The event's kind, recipient_kind, source_kind or result, when it has one. */
  variant: number | null;
  count: number;
  /** Sums of the named u64 fields over the group (absent field: 0). */
  sums: Record<SkrSumField, bigint>;
}

export const SKR_SUM_FIELDS = [
  "bond",
  "amount",
  "lamports",
  "total_bonds",
  "finisher_bonds",
  "payouts_total",
  "bury_amount",
  "seats",
  "finishers",
  "skr_amount",
  "ore_paid",
  "ore_burned",
  "ore_shared",
] as const;
export type SkrSumField = (typeof SKR_SUM_FIELDS)[number];

/** The auction after its latest BuryLotAdded / BuryAuctionSold. */
export interface BuryState {
  /** SKR on offer after the latest event. */
  lotSkr: bigint;
  /** Price of the latest sale (ORE atoms per whole SKR), if any. */
  lastPrice: bigint | null;
  /** Start price and slot of the running auction, from the latest BuryLotAdded. */
  startPrice: bigint | null;
  startSlot: bigint | null;
}

export interface SkrSummary {
  stack: {
    tablesOpened: number;
    seatsJoined: number;
    skrBonded: string;
    tablesSettled: number;
    seatsSettled: number;
    finishers: number;
    /** Forfeited SKR paid to finishers on top of their own bonds. */
    skrToFinishers: string;
    skrToBury: string;
    /** StackCheckin events with result 0 (a round counted for a seat). */
    checkinsCounted: number;
    checkinsRefused: number;
    payouts: number;
    skrPaidOut: string;
    refunds: number;
    skrRefunded: string;
  };
  focusBond: {
    locked: number;
    skrLocked: string;
    released: number;
    skrReleased: string;
    forfeited: number;
    skrForfeited: string;
  };
  gift: {
    created: number;
    lamportsCreated: string;
    /** Gifts addressed to a Seeker Genesis Token mint (only that Seeker can claim). */
    createdForSeeker: number;
    claimed: number;
    lamportsClaimed: string;
    claimedBySeeker: number;
    refunded: number;
    lamportsRefunded: string;
  };
  bury: {
    lots: number;
    skrIn: string;
    skrFromStack: string;
    skrFromBonds: string;
    sales: number;
    skrSold: string;
    orePaid: string;
    oreBurned: string;
    oreToStakers: string;
    lotSkr: string | null;
    lastPrice: string | null;
    startPrice: string | null;
    startSlot: string | null;
  };
  units: { skrDecimals: 6; oreDecimals: 11 };
}

export function computeSkrSummary(groups: SkrEventGroup[], bury: BuryState | null): SkrSummary {
  const of = (name: string, variant?: number) => groups.filter((g) => g.name === name && (variant === undefined || g.variant === variant));
  const count = (name: string, variant?: number) => of(name, variant).reduce((n, g) => n + g.count, 0);
  const sum = (name: string, field: SkrSumField, variant?: number) => of(name, variant).reduce((n, g) => n + g.sums[field], 0n);
  const s = (v: bigint) => v.toString();
  const opt = (v: bigint | null | undefined) => (v === null || v === undefined ? null : v.toString());
  const toFinishers = sum("StackSettled", "payouts_total") - sum("StackSettled", "finisher_bonds");
  return {
    stack: {
      tablesOpened: count("StackOpened"),
      seatsJoined: count("StackJoined"),
      skrBonded: s(sum("StackJoined", "bond")),
      tablesSettled: count("StackSettled"),
      seatsSettled: Number(sum("StackSettled", "seats")),
      finishers: Number(sum("StackSettled", "finishers")),
      skrToFinishers: s(toFinishers > 0n ? toFinishers : 0n),
      skrToBury: s(sum("StackSettled", "bury_amount")),
      checkinsCounted: count("StackCheckin", 0),
      checkinsRefused: count("StackCheckin") - count("StackCheckin", 0),
      payouts: count("StackClaimed", 0),
      skrPaidOut: s(sum("StackClaimed", "amount", 0)),
      refunds: count("StackClaimed", 1),
      skrRefunded: s(sum("StackClaimed", "amount", 1)),
    },
    focusBond: {
      locked: count("FocusBondLocked"),
      skrLocked: s(sum("FocusBondLocked", "amount")),
      released: count("FocusBondReleased"),
      skrReleased: s(sum("FocusBondReleased", "amount")),
      forfeited: count("FocusBondForfeited"),
      skrForfeited: s(sum("FocusBondForfeited", "amount")),
    },
    gift: {
      created: count("GiftCreated"),
      lamportsCreated: s(sum("GiftCreated", "lamports")),
      createdForSeeker: count("GiftCreated", 1),
      claimed: count("GiftClaimed"),
      lamportsClaimed: s(sum("GiftClaimed", "lamports")),
      claimedBySeeker: count("GiftClaimed", 1),
      refunded: count("GiftRefunded"),
      lamportsRefunded: s(sum("GiftRefunded", "lamports")),
    },
    bury: {
      lots: count("BuryLotAdded"),
      skrIn: s(sum("BuryLotAdded", "amount")),
      skrFromStack: s(sum("BuryLotAdded", "amount", 1)),
      skrFromBonds: s(sum("BuryLotAdded", "amount", 2)),
      sales: count("BuryAuctionSold"),
      skrSold: s(sum("BuryAuctionSold", "skr_amount")),
      orePaid: s(sum("BuryAuctionSold", "ore_paid")),
      oreBurned: s(sum("BuryAuctionSold", "ore_burned")),
      oreToStakers: s(sum("BuryAuctionSold", "ore_shared")),
      lotSkr: opt(bury?.lotSkr),
      lastPrice: opt(bury?.lastPrice),
      startPrice: opt(bury?.startPrice),
      startSlot: opt(bury?.startSlot),
    },
    units: { skrDecimals: 6, oreDecimals: 11 },
  };
}
