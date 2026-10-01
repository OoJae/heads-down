/** Row shapes shared by the store, the metrics and the API. u64 values stay bigint. */

export const DATASETS = ["mainnet", "devnet", "localnet", "simulated"] as const;
export type Dataset = (typeof DATASETS)[number];

export function isDataset(s: unknown): s is Dataset {
  return typeof s === "string" && (DATASETS as readonly string[]).includes(s);
}

export interface DigRow {
  signature: string;
  idx: number;
  slot: number;
  blockTime: number | null;
  rig: string;
  roundId: bigint;
  lamports: bigint;
  mask: number;
  emaEv: bigint;
  /** Fee payer of the dig transaction = the crank that landed it. */
  feePayer: string;
}

export interface SkipRow {
  signature: string;
  blockTime: number | null;
  rig: string;
  roundId: bigint;
  errorCode: number;
}

export interface RegisteredRow {
  signature: string;
  slot: number;
  blockTime: number | null;
  rig: string;
  authority: string;
  tier: number;
  attestationLevel: number;
}

export interface ClosedRow {
  signature: string;
  slot: number;
  blockTime: number | null;
  rig: string;
}

export interface BrokenRow {
  signature: string;
  slot: number;
  blockTime: number | null;
  rig: string;
  shiftId: bigint;
  reason: number;
}

export interface ArmRow {
  signature: string;
  blockTime: number | null;
  rig: string;
  shiftId: bigint;
}

export interface EndRow {
  signature: string;
  blockTime: number | null;
  rig: string;
  shiftId: bigint;
  darkRounds: bigint;
  roundsDug: bigint;
  /** spent_shift: SOL on squares plus Automation fees. */
  lamports: bigint;
  reason: number;
  /** 10 when the row came from ShiftEndedV2 (then the three fields below are set), 4 for a v1 ShiftEnded. */
  tag?: 4 | 10;
  startRound?: bigint | null;
  endRound?: bigint | null;
  mode?: number | null;
}

export interface SeekerRow {
  signature: string;
  slot?: number;
  blockTime: number | null;
  rig: string;
  sgtMint: string;
  memberNumber: bigint;
}

export interface DeployRow {
  signature: string;
  idx: number;
  blockTime: number | null;
  authority: string;
  amount: bigint;
  mask: number;
  roundId: bigint;
  totalSquares: number;
  ts: number;
}

export interface RoundRow {
  roundId: bigint;
  ts: number;
  winningSquare: number | null;
  topMiner: string;
  totalMiners: bigint;
  motherlode: bigint;
  totalDeployed: bigint;
  totalMinted: bigint;
  deployedWinningSquare: bigint;
  resetSignature: string | null;
}

export interface RoundStateRow {
  roundId: bigint;
  /** Raw Round account bytes (re-decoded on use). */
  data: Uint8Array;
  contextSlot: number;
}

export interface RigRow {
  address: string;
  authority: string;
  tier: number;
  state: number;
  sgtMint: string | null;
  lifetimeDarkRounds: bigint;
  lifetimeRoundsDug: bigint;
  lifetimeLamportsDeployed: bigint;
  streak: number;
  closed: boolean;
}

export interface SeatRow {
  address: string;
  sgtMint: string;
  rig: string;
  memberNumber: bigint;
  closed: boolean;
}

export interface ConfigRow {
  address: string;
  paused: boolean;
  crankFee: bigint;
  executorFee: bigint;
}

export interface MetricsInput {
  digs: DigRow[];
  skips: SkipRow[];
  arms: ArmRow[];
  ends: EndRow[];
  seekers: SeekerRow[];
  /** v1.1 lifecycle events (optional so older callers and fixtures still type-check). */
  registered?: RegisteredRow[];
  closedRigs?: ClosedRow[];
  /** ORE Round accounts read after their reset (exact per-square totals). */
  roundStates?: RoundStateRow[];
  deploys: DeployRow[];
  rounds: RoundRow[];
  rigs: RigRow[];
  seats: SeatRow[];
  config: ConfigRow | null;
}

export interface DatasetInfo {
  name: Dataset;
  simulated: boolean;
  programId: string;
  executorPda: string;
  simSeed: string | null;
  simAsOf: number | null;
}
