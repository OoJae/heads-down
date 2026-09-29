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
  rigs: { total: number; seeker: number; guest: number; closed: number; basis: "accounts" | "events"; evidence: Evidence[] };
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
  skips: { code: number; name: string; count: number }[];
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

export interface Health {
  status: "ok";
  txs: number;
  failedTxs: number;
  truncatedTxs: number;
  lastSlot: number | null;
  lastBlockTime: number | null;
  problems: { code: string; count: number }[];
}
