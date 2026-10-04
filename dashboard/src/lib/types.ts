/**
 * Response shapes of the indexer's public API (services/indexer/src/api/openapi.ts).
 * u64 values arrive as decimal strings and are formatted with BigInt, never parsed to float.
 */
export type DatasetName = "mainnet" | "devnet" | "localnet" | "simulated";

export interface DatasetInfo {
  name: DatasetName;
  simulated: boolean;
  programId: string;
  executorPda: string;
  simSeed: string | null;
}

export interface Envelope<T> {
  dataset: DatasetInfo;
  asOf: number;
  generatedAt: string;
  data: T;
}

export interface Evidence {
  label: string;
  kind: "tx" | "account";
  id: string;
  url: string | null;
}

export interface Summary {
  asOf: number;
  tzOffsetMinutes: number;
  lastCompleteNight: string | null;
  rigs: {
    total: number;
    seeker: number;
    guest: number;
    closed: number;
    /** "lifecycle" = RigRegistered minus RigClosed events (v1.1); "accounts" = Rig account snapshot; "events" = rigs seen in any event. */
    basis: "lifecycle" | "accounts" | "events";
    everRegistered: number | null;
    crossCheck: { accounts: number | null; accountsSeeker: number | null; matches: boolean | null };
    evidence: Evidence[];
  };
  nightlyActive: {
    lastNight: string | null;
    rigs: number;
    peak: number;
    series: { night: string; rigs: number; seeker: number }[];
    evidence: Evidence[];
  };
  darkHours: { hours: number; darkRounds: string; roundSeconds: number; roundSecondsBasis: "measured" | "fallback"; shiftsEnded: number; evidence: Evidence[] };
  roundsDug: { rigRounds: number; distinctRounds: number; evidence: Evidence[] };
  solDeployed: { lamports: string; oreDeployEventLamports: string; consistent: boolean; evidence: Evidence[] };
  ore: {
    mined: { amount: string; digsPendingRound: number; basis: string; evidence: Evidence[] };
    bought: { amount: null; status: "not_shipped"; note: string };
    buried: { amount: null; status: "not_shipped"; note: string };
  };
  gate: { openRate: number | null; opened: number; closedByCostGate: number; digShareOfDarkRounds: number | null; evidence: Evidence[] };
  skips: { code: number; name: string; range: ErrorRange; label: string; count: number }[];
  crankers: { distinct: number; thirdParty: number | null };
  programPaused: boolean | null;
  consistency: Record<
    "digsWithoutDeploy" | "deploysWithoutDig" | "lamportsMismatch" | "roundsDugExceedsDark" | "seekerTierWithoutSeat" | "hdMinersExceedTotal",
    number
  >;
}

export interface CohortCell {
  day: number;
  retained: number | null;
  rate: number | null;
  mature: boolean;
}

export interface CohortReport {
  lastCompleteNight: string | null;
  cohorts: { cohort: string; size: number; cells: CohortCell[] }[];
  average: { day: number; rate: number | null; cohorts: number; rigs: number }[];
}

export interface HourBucket {
  hour: number;
  rounds: number;
  roundsWithHd: number;
  meanShare: number | null;
  maxShare: number | null;
  meanHdMiners: number | null;
  meanTotalMiners: number | null;
  peak: {
    roundId: string;
    share: number;
    hdMiners: number;
    totalMiners: string;
    resetSignature: string | null;
    sampleDig: string | null;
    resetUrl: string | null;
    digUrl: string | null;
  } | null;
}

export interface ShareByHour {
  tzOffsetMinutes: number;
  windowDays: number;
  from: number;
  to: number;
  rounds: number;
  roundsWithHd: number;
  meanShare: number | null;
  hours: HourBucket[];
}

export interface FeedItem {
  signature: string;
  blockTime: number | null;
  rig: string;
  authority: string | null;
  tier: number | null;
  roundId: string;
  lamports: string;
  squares: number;
  emaEv: string;
  txUrl: string | null;
  rigUrl: string | null;
}

export interface MilestoneMetric {
  key: string;
  label: string;
  value: number | null;
  target: number;
  stretch: number | null;
  unit: "rigs" | "share";
  note?: string;
}

export interface Milestones {
  tzOffsetMinutes: number;
  shareWindow: { fromHour: number; toHour: number; nights: number };
  milestones: { id: "M1" | "M2" | "M3"; title: string; deadline: string; metrics: MilestoneMetric[]; manual: string[] }[];
}

/** GET /v1/skr/summary: counts and sums of the program's SKR events. Amounts are base units as decimal strings. */
export interface SkrSummary {
  stack: {
    tablesOpened: number;
    seatsJoined: number;
    skrBonded: string;
    tablesSettled: number;
    seatsSettled: number;
    finishers: number;
    skrToFinishers: string;
    skrToBury: string;
    checkinsCounted: number;
    checkinsRefused: number;
    payouts: number;
    skrPaidOut: string;
    refunds: number;
    skrRefunded: string;
  };
  focusBond: { locked: number; skrLocked: string; released: number; skrReleased: string; forfeited: number; skrForfeited: string };
  gift: {
    created: number;
    lamportsCreated: string;
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
  units: { skrDecimals: number; oreDecimals: number };
}

export interface Health {
  status: "ok";
  txs: number;
  failedTxs: number;
  truncatedTxs: number;
  lastSlot: number | null;
  lastBlockTime: number | null;
  problems: { code: string; count: number }[];
  /** Unix seconds of the last completed ingest pass, whether it succeeded, and the last one that did. */
  lastPollAt: number | null;
  lastPollOk: boolean | null;
  lastOkPollAt: number | null;
}

export type ErrorRange = "heads_down" | "p256-introspect" | "sgt-verify" | "builtin" | "unknown";

export interface SkipItem {
  signature: string;
  slot: number;
  blockTime: number | null;
  rig: string;
  roundId: string;
  error: number;
  name: string;
  range: ErrorRange;
  label: string;
  txUrl: string | null;
}

export interface Skips {
  filter: { rig: string | null; error: number | null };
  total: number;
  histogram: { code: number; name: string; range: ErrorRange; label: string; count: number }[];
  items: SkipItem[];
  nextCursor: string | null;
}

export interface RigShifts {
  rig: string;
  shifts: { shiftId: string; endedAt: number | null; darkRounds: string; roundsDug: string; reason: number; mode: number | null; signature: string }[];
}

/** u64 as a JSON number up to 2^53, else a decimal string (contract B). */
export type U64 = number | string;

/** The morning haul, shared contract B (not enveloped). */
export interface HaulSummary {
  rig: string;
  shift_id: U64;
  mode: "night" | "day" | "focus_only";
  start_ts: number;
  end_ts: number;
  start_round: U64;
  end_round: U64;
  rounds: { round_id: U64; dark: boolean; dug_mask: number; winning_square: number | null; motherlode: boolean; split: boolean }[];
  dark_rounds: U64;
  rounds_dug: U64;
  sol_placed_lamports: U64;
  fees_lamports: U64;
  ore_mined_atoms: string;
  effective_lamports_per_ore: string | null;
  market_lamports_per_ore: string | null;
  market_source: string | null;
  streak_before: number;
  streak_after: number;
  break_reason: number;
  first_pickup_ts: null;
  simulated: boolean;
  explorer: { shift_log: string | null; sample_digs: string[] };
}
